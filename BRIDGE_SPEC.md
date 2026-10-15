# BTW Forge Bridge Engineering Specification

## Architecture Overview

FC (FlowerChild's Better Than Wolves) patches 276 vanilla MC 1.5.2 classes inline.
FC code compiles against `btw.api.*`, shadow-remapped to `btw.modern.*` at build time.
The `btw.modern` layer contains FC game logic with real implementations.
Bridge classes connect btw.modern to the real MC 1.20.1 engine.
Mixins on real MC classes hook into btw.modern at the right points.

### Key Principles

**FC code is read-only. Never modify it** (user directive, 2026-10-10). "FC code" means:
FlowerChild's BTW sources (`net.minecraft.src.btw.*` under `Common/`, `Client/`, `Server/`), the
1.5.2 vanilla sources carrying FC's FCMOD edits under `vanilla/`, the frozen compiled 1.5.2/FC
classes in root `build/classes/java/{main,server}`, FlowerChild's original 4.B release files kept in
the repo (`Src/`, `SrcServer/` and `SrcClient/`, holding the flat sources and the `patch.txt` files
that `setupVanilla` applies to build `vanilla/`, plus `BTWMod4-B0000003.zip`), and FlowerChild's own
files such as `Readme.txt`. Nobody edits them, ever: no "small fixes", no tagged edits. When FC
behaviour has to change, the change goes outside FC code:

- **the Forge bridge** (`Forge/src/main/java/btw/forge`, mixins on 1.20.1 classes, data packs);
- **Modern-Common** (`btw.modern`), the port's own pure-Java stand-ins for 1.5.2 vanilla classes.
  They are port code, not FC code. At runtime they share the `btw.modern` package with the remapped
  frozen classes, so the package name alone does not tell you whether a class may be edited: check
  which side it comes from;
- **a planned subproject** (name not chosen yet) that *extends* FC code (subclasses, registries,
  event hooks, replacement classes) and *patches* it (bytecode transforms or mixins applied at build
  or load time). Patches never write back to FC sources. The build-time ASM transforms in
  `Forge/build.gradle` (`widenFcHookDescriptors`, `openUpEntityAccess`,
  `extendFcPathingBuildRange`, `extendFcTreeBuildRange`) are its precursors and are expected to move
  into it, as are the load-time mixins on FC classes in `btw.forge.mixin.fcext` (2026-10-15, keyed
  on the plant-twin ids so FC's own blocks are untouched).

Legacy port edits that predate this rule:

- the restructure of FC's flat `Src/` and `SrcServer/` sources into `net.minecraft.src.btw.*` under
  `Common/`, `Client/` and `Server/`, including hook-signature widening in `Server/` (e.g.
  `FCEntityCreeper.OnKickedByCow( Entity cow )` against FC's `OnKickedByCow( FCEntityCow cow )`,
  `SrcServer/net/minecraft/src/FCEntityCreeper.java:173`);
- `applyFixups` in the root `build.gradle`, which text-edits `vanilla/` during `setupVanilla`
  (landing BTW patch hunks that fail to apply, adding `btw` subpackage imports, widening access
  modifiers, adding `Api` interface clauses);
- the 27 manual `vanilla/` edits captured in `patches/vanilla-manual-edits.patch`, also applied by
  `setupVanilla` (see `STUB_CATALOGUE.md`, "`vanilla/` is now fully reproducible");
- three vanilla classes added under `Client/src/main/java/net/minecraft/src/` in 5257461
  (`ModelChest`, `ModelLargeChest`, `TileEntityChestRenderer`).

They stay so the tree keeps building. Nothing new is ever added to them, and they are candidates to
move into the extension/patch subproject. Player-facing claims that the port runs FC code
"verbatim" (`CURSEFORGE.md`, the `mods.toml` description quoted in `VERSIONING.md`) are overstated
while these edits exist; rewording them is the maintainer's call.

1. **FC code runs natively** in the btw.modern layer — not replicated in mixins.
2. **btw.modern is the FC game engine** — it holds FC state, FC logic, FC methods.
3. **Bridges are narrow** — only sync engine-level state (position, health, blocks, inventory).
4. **Mixins are hooks, not logic** — they call into btw.modern, they don't contain FC behavior.
5. **Vanilla systems FC replaces are DISABLED** — food, mining speed, movement, beds, etc.

### Object Relationships

```
Real MC 1.20.1                        btw.modern FC Layer
──────────────                        ──────────────────
ServerPlayer  ◄──── PlayerBridge ────► btw.modern.EntityPlayer
  ├─ Inventory ◄── InventoryBridge ──► btw.modern.InventoryPlayer
  ├─ Abilities ◄── CapabilitiesBridge► btw.modern.PlayerCapabilities
  ├─ FoodData ──── DISABLED ─────────► btw.modern.FoodStats (FC replacement)
  └─ SynchedEntityData ◄─ DWBridge ─► btw.modern.DataWatcher (FC entries)

ServerLevel   ◄──── WorldBridge ─────► btw.modern.World (already exists)

MC ItemStack  ◄── ItemStackBridge ──► btw.modern.ItemStack
```

### Existing Infrastructure (already working)

- `WorldBridge` extends `btw.modern.World`, wraps `ServerLevel` — block get/set, entity spawn, explosions
- `ProxyBlock` + `ProxyRegistry` — 148 FC blocks registered, vanilla block ID mapping
- `ForgeMinecraftServerWrapper` — wraps `MinecraftServer`
- `btw.modern.Block.blocksList[]` — 148 FC blocks + vanilla blocks with FC properties
- `btw.modern.Item.itemsList[]` — FC items registered
- `btw.modern.NBTTagCompound` — wraps `CompoundTag`
- `btw.modern.AxisAlignedBB` — wraps `AABB`
- `btw.modern.Vec3` — wraps `Vec3`
- `btw.modern.ChunkCoordinates` — data class
- Mixins (`Forge/src/main/resources/betterthanwolves.mixins.json`, updated 2026-10-15):
  - block hooks: `BlockBehaviorMixin` (FC neighbour, place, remove, use, entity, signal and dig hooks;
    no tick routing since 2026-10-14), `BlockMixin`;
  - tick ownership (see [Tick ownership (TickPolicy)](#tick-ownership-tickpolicy)):
    `BlockStateBaseTickMixin`, `BlockStateRandomTickMixin`, `LavaFluidMixin`;
  - rule bridges for the ticks FC now owns: `FireBlockMixin`, `PlantSoilMixin`, `FarmBlockMixin`,
    `AttachedStemBlockMixin`;
  - post-1.5.2 plants (twins and paced owners, see the tick-ownership section):
    `fcext.FCBlockSaplingTwinMixin` and `fcext.FCBlockMushroomTwinMixin` (load-time, on FC classes),
    `BeeGrowCropGoalMixin`, `BeeAccessor`;
  - bone meal (see [Bone meal (BoneMealPolicy)](#bone-meal-bonemealpolicy)): `BoneMealItemMixin`,
    `BonemealableGateMixin`;
  - stays removed (see [Stays removed (RemovedContent)](#stays-removed-removedcontent)):
    `StructureTemplateRemovedMixin`, `StructurePieceRemovedMixin`;
  - entities, players, items and the world: `LivingEntityMixin`, `PlayerMixin`, `ServerPlayerMixin`,
    `ServerPlayerGameModeMixin`, `FoodDataMixin`, `ItemEntityMixin`, `ItemStackMixin`,
    `ServerLevelMixin`, `BonusChestFeatureMixin`;
  - modern-feature integrations (`INTEGRATIONS.md`): `MendingCapMixin`, `FrostWalkerRadiusMixin`,
    `RespawnAnchorChargeMixin`, `SwiftSneakLevelMixin`, `TotemOfUndyingMixin`, `CrossbowPiercingMixin`,
    `SweepingEdgeMixin`;
  - client only: `EntityRendererNameTagMixin`, `client.ClientLevelMixin`.

  `NetherPortalBlockMixin` was deleted on 2026-10-14: the portal's random tick falls under the
  tick-ownership rule.

---

## Tick ownership (TickPolicy)

Added 2026-10-14 (`STUB_CATALOGUE.md` (as)); extended 2026-10-15 to post-1.5.2 plants ((at),
`INTEGRATIONS.md` #018). Code: `Forge/src/main/java/btw/forge/TickPolicy.java` (the rule and the
dispatch bodies), `mixin/BlockStateBaseTickMixin.java`, `mixin/BlockStateRandomTickMixin.java`,
`mixin/LavaFluidMixin.java`, `FcBlockStateAccess.java` (the cached owners), `FcPaceAccess.java` (the
cached pace), `TwinRegistry.java` (twins and paced plants), `mixin/fcext/`,
`mixin/BeeGrowCropGoalMixin.java`, `LightPolicy.java`, `FcMetaOverlay.java`, `BridgeSelfAudit.java`.

**The rule.** FC decides the random and scheduled ticks of every FC-mapped vanilla-id `BlockState`. The
decision is made once per state, from the FC block at the state's legacy id (`TickPolicy.decide`), and
enforced at the BlockState level, which every server tick passes through, so no 1.20.1 per-class
override can skip it. 1.20.1 moved most tick behaviour into `randomTick` / `tick` overrides that never
call super (39 random-tick and 46 tick overrides on mapped blocks), so the former BlockBehaviour-level
hooks in `BlockBehaviorMixin` never ran for them.

- **Managed states:** the block maps to an FC block at a vanilla legacy id (`ProxyRegistry.getBlockId`
  non-zero, `blocksList` slot populated), is not a `ProxyBlock`, and `BTWLifecycle.isFcReady()` holds
  (set as the last statement of `initialize()`). ProxyBlocks keep their own forwarding
  (`ProxyBlock.tick` / `randomTick` / `isRandomlyTicking`). Unmanaged states keep the 1.20.1 flag and
  both owners stay VANILLA. Post-1.5.2 plants have no legacy id; `decide` classifies them first, from
  `TwinRegistry` (see Post-1.5.2 plants below).
- **Random ticks:** gate = `fc.getTickRandomly()` and the runtime FC class declares `updateTick` or
  `RandomUpdateTick` below `btw.modern.Block` (a 4.B tick that reaches only a Modern-Common stub stays
  off). Owner FC when the gate holds, else NONE. An FC random tick runs `RandomUpdateTick` and cancels
  1.20.1, as the 4.B WorldServer loop does (SrcServer/patch.txt:25931-25938).
- **Scheduled ticks:** owner FC (`updateTick`, 1.20.1 cancelled) unless the state's 1.20.1 class
  overrides `tick` (found by reflection from `m_213897_`, cached per class); then VANILLA, because those
  ticks are 1.20.1 machinery FC never scheduled. `BlockBehaviour.tick` is a no-op, so ticks on a
  non-overriding class come only from FC. Exception: the FC_SCHED set below. `LevelChunkTicks`
  de-duplicates by (pos, block), so an FC and a 1.20.1 schedule for one cell become one tick, which the
  rule hands to exactly one engine.
- **Owners** (byte per state): VANILLA 0, the 1.20.1 body runs; FC 1, FC runs and 1.20.1 is cancelled;
  NONE 2, the state does not random-tick (a direct call passes through to 1.20.1); PACED 3 (random
  ticks of post-1.5.2 plants only), the 1.20.1 body runs on the fraction `pace` of the random ticks and
  never in the End under FC's End ban.
- **Determinism:** `decide` is a pure function of the state and FC's static data; it never reads
  gamerules, config or world state. It runs at every registry bake and `Blocks.rebuildCache` (server
  start, /reload, client tag sync), and `LevelChunkSection` recounts ticking states from the cached flag
  when a chunk loads, so existing worlds need no migration. doFireTick is applied at dispatch time.
- **Failures:** an FC tick body that throws is logged once per throw site (logger `BTW-Tick`) and never
  ends the server tick; a state whose decision throws keeps its 1.20.1 values and VANILLA owners.

**Dispatch points** (Forge 47.3.0 mapped sources; the refmap carries the SRG names):

```
# | Target (SRG)                                            | Mixin (priority)                   | Does
──┼─────────────────────────────────────────────────────────┼────────────────────────────────────┼──────────────────────────────────────────
1 | BlockStateBase.initCache m_60611_, the PUTFIELD of      | BlockStateBaseTickMixin (1000),    | writes TickPolicy's gate into the field
  | isRandomlyTicking f_244227_ (BlockBehaviour.java:513)   | @Redirect                          | once, final; caches fcId, schedId and
  |                                                         |                                    | the random / scheduled owners
2 | BlockStateBase.initCache m_60611_, TAIL                 | BlockStateBaseTickMixin, @Inject   | LightPolicy emission over lightEmission
3 | BlockStateBase.randomTick m_222972_, HEAD               | BlockStateRandomTickMixin (1100)   | random owner FC: TickPolicy.runRandom,
  |                                                         |                                    | 1.20.1 body cancelled; PACED: the
  |                                                         |                                    | 1.20.1 body runs when pacedRollPasses,
  |                                                         |                                    | else cancelled
4 | BlockStateBase.tick m_222963_, HEAD                     | BlockStateBaseTickMixin            | scheduled owner FC: runScheduled,
  |                                                         |                                    | 1.20.1 body cancelled
5 | LavaFluid.randomTick m_213812_, HEAD                    | LavaFluidMixin                     | cancelled when the lava state's random
  |                                                         |                                    | owner is FC (fcOwnsRandomTick)
```

- The tick loop reaches the state level at exactly two points: `ServerLevel.tickChunk` ->
  `state.randomTick` (ServerLevel.java:481) and `ServerLevel.tickBlock` -> `state.tick` (:680). The
  fluid random tick follows the block's (:484-487), hence row 5. Other `randomTick` callers (other mods'
  growth accelerators, and since 2026-10-15 a bee's pollination visit, `BeeGrowCropGoalMixin`) reach the
  same dispatch. Bone meal no longer does: `BonemealableGateMixin` cancels `StemBlock.performBonemeal`
  on every state whose growth FC governs (see Bone meal (BoneMealPolicy)).
- Row 1 is a `@Redirect` of the single PUTFIELD, not a TAIL overwrite: `LevelChunkSection` counts
  ticking states with the cached flag from any thread, and an interim 1.20.1 value between two writes
  could drift a section's count. It stays at default priority so it never depends on other mods'
  initCache changes.
- Row 3 is its own mixin at priority 1100: callbacks at one HEAD run in mixin application order, and
  1100 applies after every default-priority mixin, so a HEAD canceller such as Serene Seasons 9.1+
  (`MixinBlockStateBase`) vetoes first. `runRandom` also fires Forge's `CropGrowEvent.Pre` before the FC
  body (DENY skips it) and `Post` when the state changed and is not air, for the classes whose 1.20.1
  `randomTick` fires them (CropBlock, StemBlock, CactusBlock, SugarCaneBlock, NetherWartBlock,
  CocoaBlock; SweetBerryBush, BambooStalk, ChorusFlower and GrowingPlantHead are listed but unmapped).
  `INTEGRATIONS.md` #016. Since 2026-10-15 those four are paced, and their own 1.20.1 body, which fires
  the events, runs when the roll lets it. The beetroot and torchflower twins are CropBlocks, so
  `runRandom` fires the events for them; the sapling and fungus twins end in 1.20.1 growers, which fire
  Forge's `BlockGrowFeatureEvent`.
- Do not add tick or randomTick injects at the `BlockBehaviour` level (see `BlockBehaviorMixin`'s
  javadoc): such a hook keys on the wrong block (StairBlock passes the stair state to `base.tick`, which
  ran FCBlockStoneBrick.updateTick at a stair position), never sees the overriding classes, and would be
  a second routing path that ignores the policy.

**Exception table** (`TickPolicy.decide`, checked in this order before the general rule; "by rule" = FC
unless the 1.20.1 class overrides `tick`):

```
# | Block (legacy id)       | Random owner / gate                   | Scheduled owner      | Why
──┼─────────────────────────┼───────────────────────────────────────┼──────────────────────┼──────────────────────────────────────────
1 | twin state (no id; a    | FC as the twin's id, gate true        | VANILLA              | the nearest FC plant class runs the
  | TwinRegistry family)    |                                       |                      | tick (2026-10-15)
2 | paced state (no id)     | PACED, 1.20.1 flag, pace and End flag | VANILLA              | no FC class can drive it; FC's mean
  |                         |                                       |                      | pace (2026-10-15)
3 | WATER (8)               | NONE, gate false                      | by rule              | 4.B's id-8 random tick is the 1.5.2 flow
  |                         |                                       |                      | step; the 1.20.1 fluid engine owns flow
4 | LAVA (10)               | FC as id 11, gate forced true         | by rule, as id 10    | all settled 4.B lava is id 11
  |                         | (VANILLA if blocksList[11] is empty)  |                      | (FCBlockLavaStationary)
5 | RedStoneOreBlock (73)   | VANILLA, 1.20.1 flag                  | by rule              | blocksList[73] is the unlit ore; 1.20.1
  |                         |                                       |                      | LIT + unlight = 4.B id 74
6 | IceBlock (79)           | VANILLA                               | by rule              | FCBlockIce assumes 1.5.2 opacity 3;
  |                         |                                       |                      | 1.20.1 melts at light 12, as 4.B does
7 | DETECTOR_RAIL (28)      | NONE, gate false                      | VANILLA (override)   | rail SHAPE + POWERED <-> metadata is
  |                         |                                       |                      | not mapped
8 | DEAD_BUSH (32)          | NONE, gate false (decision D3)        | by rule              | its FC tick is survival on sand 12 only
9 | PENDING ids (staging)   | VANILLA, 1.20.1 flag                  | by rule              | empty
10| LeavesBlock (18)        | FC, gate = !PERSISTENT                | VANILLA (DISTANCE)   | replaces 1.20.1's DISTANCE == 7 gate
11| everything else         | general rule                          | by rule / FC_SCHED   | stairs: their own FC block (off), not
  |                         |                                       |                      | StairBlock's base; NETHER_PORTAL (90):
  |                         |                                       |                      | general rule, FC (no entry)
```

**FC_SCHED** (`TickPolicy.FcSched`): blocks whose scheduled ticks FC owns although their 1.20.1 class
overrides `tick`, because FC has the same machine or removed the 1.20.1 one on purpose. Ids listed in
`TickPolicy.PENDING_SCHED` (empty) fall back to the override rule.

```
Block                                 | Id   | 1.20.1 tick it replaces            | FC body
──────────────────────────────────────┼──────┼────────────────────────────────────┼──────────────────────────────────────────
FIRE                                  | 51   | FireBlock.tick (onPlace schedules  | FCBlockFire.updateTick; reschedules itself
                                      |      | 30 + nextInt(10))                  | at tickRate 30 + nextInt(10)
SAND, GRAVEL                          | 12,  | FallingBlock.tick (fall)           | FCBlockFalling.updateTick -> CheckForFall
                                      | 13   |                                    | (FCEntityFallingBlock, to the world floor)
DRAGON_EGG                            | 122  | FallingBlock.tick                  | FCBlockDragonEgg.updateTick -> CheckForFall
                                      |      |                                    | (tickRate 5)
ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL   | 145  | FallingBlock.tick                  | FCBlockAnvil extends Block: the empty
                                      |      |                                    | Block.updateTick, so it never falls
```

1.20.1 keeps the scheduled ticks of these mapped ids (`BridgeSelfAudit.EXPECTED_VANILLA_SCHED_IDS`):
leaves 18, dispenser 23 / dropper 158, detector rail 28, the stairs (53, 67, 108, 109, 114, 128, 134,
135, 136, 156), chests 54 / 130 / 146, farmland 60, cactus 81 and sugar cane 83 (survival executors),
plates 70 / 72 / 147 / 148, redstone torch 76, buttons 77 / 143, soul sand 88, repeater 93, cauldron
118, lamp 123, tripwire 131 / 132, comparator 149.

**doFireTick** (maintainer decision D1, `TickPolicy.HONOUR_DO_FIRE_TICK = true`, `INTEGRATIONS.md`
#012): read at dispatch, never in the gate. `fireTickSuppressed(fc, level)` covers every
`btw.modern.BlockFire` (fire 51, stoked fire 251, ...) and FC lava material. With the rule false, a
scheduled FC fire tick only reschedules at `tickRate + nextInt(10)` (`rescheduleSuppressedFire`), and FC
fire and lava random ticks are skipped. `ProxyBlock.tick` / `randomTick` apply the same check.

**FC metadata on 1.20.1 states.** A ticked FC body reads and writes 1.5.2 metadata. `WorldBridge`
derives it from the state (`deriveVanillaMetadata`) and applies FC's writes to it
(`applyFcMetadataToVanillaBlock`); what no property can hold lives in the persisted overlay.

```
1.20.1 block                          | FC metadata                                    | Held by
──────────────────────────────────────┼────────────────────────────────────────────────┼──────────────────────────────────────────
CropBlock (wheat, carrots, potatoes)  | AGE 0-7                                        | state (fully derived)
NetherWartBlock                       | AGE 0-3                                        | state (fully derived)
CactusBlock, SugarCaneBlock           | AGE 0-15                                       | state (fully derived)
FireBlock                             | AGE 0-15 (faces from BaseFireBlock.getState;   | state (fully derived)
                                      | a soul fire placement stays FIRE)              |
FarmBlock                             | MOISTURE 0-7                                   | state (fully derived)
CocoaBlock                            | 2D FACING value + (AGE << 2)                   | state (fully derived)
VineBlock                             | S 1, W 2, N 4, E 8; 0 = UP only                | state (fully derived)
StemBlock, AttachedStemBlock          | growth 0-14 (AGE = growth / 2); 15 =           | state + overlay (growth)
                                      | attached, facing the fruit FC attached         |
LeavesBlock                           | species, bit 4 PERSISTENT, bit 8 decay check   | state + overlay (bit 8)
SaplingBlock                          | type + (stage << 2)                            | state + overlay (stage)
carved pumpkin, jack o'lantern        | facing 0-3, bit 8 player-placed                | state + overlay (bit 8)
twin saplings, azaleas (2026-10-15)   | species bits; FC stage << 2                    | state (species) + overlay (stage)
beetroots, torchflower and pitcher    | growth level 0-7; AGE shows the levels         | state + overlay (levels between)
crops (twins, 2026-10-15)             | {0,2,4,7}, {0,4} and {0,2,4,6,7}               |
crimson, warped fungus (twins)        | species bit 0                                  | state
```

- **FcMetaOverlay** (`FcMetaOverlay.java`): a SavedData per dimension, key `btw_fc_meta`, file
  `data/btw_fc_meta.dat`, loaded and saved with the level. It replaces the in-memory
  `vanillaMetaOverrides` map that was cleared at server stop (the field kept its name). An entry is
  packed BlockPos -> (block, derivedAtWrite, meta), stored only when FC's metadata differs from the value
  derived from the state after the write, never for ProxyBlocks or fully derived blocks.
- **Validity rule:** `getBlockMetadata` returns an entry's meta only while the block at the position is
  still the entry's block and `deriveVanillaMetadata` of the current state still equals derivedAtWrite;
  otherwise it drops the entry and returns the derived value. 1.20.1 changes blocks without going through
  WorldBridge (explosions, pistons, fluids, bone meal advancing a stem's AGE), and then the derived value
  wins. Leaf DISTANCE does not invalidate a leaf flag (derive ignores it). A dirty save prunes stale
  entries in loaded chunks; the overlay logs its entry count at load and WARNs once above 100,000.
- **Flattened variants:** an FC metadata write on a variant id switches the 1.20.1 block (stone bricks 98
  turning mossy / cracked), copying shared properties (WATERLOGGED, wall connections, DISTANCE), and only
  from a block that is itself a table variant (`ProxyRegistry.getLegacyVariantMeta >= 0`).
- **Metadata-only write guard:** when `setBlockMetadata` has to switch the 1.20.1 block (a variant, stem
  <-> attached stem), `WorldBridge.isMetadataOnlyWrite(pos)` holds for that cell during the setBlock,
  and `BlockBehaviorMixin`'s onPlace / onRemove skip FC `onBlockAdded` / `breakBlock`: 1.5.2
  `Chunk.setBlockMetadata` never calls them. Neighbour updates from the same write still reach FC.
- **Light reads:** `getFullBlockLightValue` is `level.getRawBrightness(pos, 0)`, with no sky darkening,
  as 1.5.2 (`vanilla/server` World.java:799-813).

**LightPolicy** (`LightPolicy.java`). A state whose random owner is FC, that is not a ProxyBlock and has
no LIT or POWERED property, emits 4.B's `Block.lightValue[id]`; every other state keeps 1.20.1's.
`BlockStateBaseTickMixin` writes it over the final `lightEmission` field at initCache TAIL and keeps the
first bake's 1.20.1 value for fallback and the audit. LIT / POWERED states stand for two legacy ids
(furnace 61/62, redstone ore 73/74, torch 75/76, lamp 123/124, repeater 93/94, comparator 149/150), so
one table entry cannot describe them. Effect today: `brown_mushroom` 1 -> 0 (FCBlockMushroomBrown
spreads only from full light 0). Old saves: `relightIfStale`, called from `runRandom`, queues
`LevelLightEngine.checkBlock` when the stored block light exceeds both the emission and what any
neighbour could pass on. Modern-Common `BlockEndPortal` and powered `BlockRedstoneLight` carry their
1.5.2 constructor light (15), so they do not go dark. `Block.lightOpacity` is not filled: `ProxyBlock` reads it for light
blocking.

**extendFcTreeBuildRange** (`Forge/build.gradle`). FC's tree generators test the 1.5.2 build range
before any world read, so saplings at y <= 0 never grew. The task, wired like
`extendFcPathingBuildRange` (after `extractFcClasses`, before `openUpEntityAccess`), rewrites them
against `World.getFcMinBuildHeight()` / `getFcMaxBuildHeight()` (0 / 256 in Modern-Common, the level's
range from WorldBridge):

```
FC method                     | Trees              | Base test                | Scan floor           | 256 tops
──────────────────────────────┼────────────────────┼──────────────────────────┼──────────────────────┼──────────────────────────────────────────
FCUtilsTrees.GenerateTaiga2   | spruce             | j < 1  ->  j - 1 < min   | y < 0  ->  y < min   | none (FC's own 128 kept)
FCUtilsTrees.GenerateForest   | birch              | j < 1  ->  j - 1 < min   | y < 0  ->  y < min   | none (FC's own 128 kept)
FCUtilsTrees.GenerateTrees    | oak, small jungle  | j < 1  ->  j - 1 < min   | y < 0  ->  y < min   | 3, each -> max
FCUtilsGenHugeTree.generate   | 2x2 jungle         | j < 1  ->  j - 1 < min   | y < 0  ->  y < min   | 3, each -> max
```

FC's own 128 top for spruce and birch is a 4.B rule measured from sea level and stays. The task is
idempotent and fails the build on any other bytecode shape. Big oaks use Modern-Common's
`WorldGenBigTree` port, which has no explicit bounds.

**Self-audit** (`BridgeSelfAudit.runServerStart`, called from `BTWForgeMod.onServerStarting`, once per
JVM). It runs at ServerStartingEvent because Forge fills the BlockStateBase caches at the registry
freeze, after FMLCommonSetupEvent: read at common setup, every cached flag is a default (the first run,
2026-10-12, logged 57 false warnings). `Blocks.rebuildCache` refreshes them before the server starts.

```
[SELF-AUDIT/tick] <block key> id=<fcId> random=<FC|VANILLA|NONE> gated=<n>/<states> sched=<FC|VANILLA>
[SELF-AUDIT/tick] <n> blocks, <n> FC-random states, <n> FC-scheduled blocks, <n> warnings (0 expected)
[SELF-AUDIT/light] <block key> id=<id> emission <1.20.1>-><4.B> (<n>/<states> states)
[SELF-AUDIT/light] <n> mapped blocks emit 4.B light that differs from 1.20.1, <n> warnings (0 expected)
[SELF-AUDIT/twin] id <id> = <FC class> (<KIND> twin)
[SELF-AUDIT/twin] family <family> id=<id> (<FC class>) blocks=[...] states=<n> levels=[...]
[SELF-AUDIT/twin] paced <block> (<plant>) pace=<pace> endBan=<true|false> states=<n>
[SELF-AUDIT/twin] <n> twin families (<n> blocks), <n> twin states, <n> paced states, <n> warnings
```

- The tick audit WARNs on: a class that overrides `tick` but is neither FC_SCHED nor an expected
  1.20.1-owned id; a class that overrides `randomTick` and keeps the 1.20.1 random tick without an
  exception entry; a 4.B random-tick flag the port gets wrong (ids 1-174 against
  `EXPECTED_4B_RANDOM`, both directions; ids 75 / 76, the ConcreteTorchBlock placeholders, are logged at
  INFO as a known gap); a cached `isRandomlyTicking` that differs from the gate (mixin not applied); FC
  not initialised.
- The light audit WARNs on a cached emission that differs from LightPolicy's answer, and on a mapped
  emitter made dark other than id 39 (`EXPECTED_DARKENED_IDS`).
- The twin audit (`auditTwins`, 2026-10-15, after the tick audit; the tick audit's 4.B flag check skips
  twin ids, which lie in 1-174) WARNs on: a twin that was not built, whose `blocksList` slot is not its
  FC class, or whose id has a ProxyBlock, a mapped 1.20.1 block or item, or lies in the ProxyBlock range;
  a twin state FC's world reads do not see as its twin's id; a metadata codec that does not round-trip
  (`stateForMeta(s, deriveMeta(s)) == s`, and every crop level lands on the highest visible level not
  above it); a cached owner, twin id, gate or pace that differs from `decide`; a `minecraft` block that
  grows by random tick or bone meal and is neither FC-mapped, a twin, paced nor kept on 1.20.1 on purpose
  (`isKeptOn1201`: the new leaves, nylium, bone-meal-only spreaders, growing-plant bodies, copper,
  dripstone, amethyst, turtle eggs, frosted ice, redstone ore, stairs). Other mods' growing blocks are
  counted at INFO.
- 2026-10-14 values: `238 blocks, 778 FC-random states, 200 FC-scheduled blocks, 0 warnings`;
  `brown_mushroom id=39 emission 1->0 (1/1 states)`, `1 mapped blocks ..., 0 warnings`. 2026-10-15: tick
  and light unchanged; `6 twin families (11 blocks), 41 twin states, 173 paced states, 0 warnings`.

**Rule bridges.** The ticks FC now owns meet 1.20.1 executors that stay 1.20.1's (survival checks, fire
placement, farmland reversion, attached stems). These hooks give those executors FC's rule; they
implement rules, not dispatch.

```
# | Hook (INTEGRATIONS.md)            | Target (SRG)                          | Rule
──┼───────────────────────────────────┼───────────────────────────────────────┼──────────────────────────────────────────
1 | FireBlockMixin (#014)             | FireBlock.getBurnOdds m_221164_ /     | FC's abilityToCatchFire /
  |                                   | getIgniteOdds m_221166_, HEAD         | chanceToEncourageFire for every
  |                                   |                                       | FC-mapped block
2 | ModernBlockStandIn (#014, #013)   | FC's reads of modern-only blocks      | flammable stand-ins with 1.20.1 odds;
  |                                   |                                       | isFireSource for the up face (magma);
  |                                   |                                       | D3 soil hooks from the DIRT / SAND tags
3 | PlantSoilMixin (#013)             | Block.canSustainPlant, HEAD           | FC's soil clause on FC soils
  |                                   | (Forge-added, remap = false)          |
4 | WorldBridge (#013)                | GetModernSoilPlantSurvival, the       | soil with no 1.5.2 identity: the live
  |                                   | Modern-Common World hook that         | 1.20.1 canSurvive decides
  |                                   | BlockFlower.checkFlowerChange asks    |
5 | FarmBlockMixin (#015)             | FarmBlock.turnToDirt m_269406_, HEAD  | FC loose dirt (1011)
6 | AttachedStemBlockMixin            | AttachedStemBlock.updateShape         | FC stems: attachment left to FC
  |                                   | m_7417_, HEAD                         | (FCBlockStem.ValidateFruitState)
```

**Post-1.5.2 plants: twins and paced owners** (2026-10-15, maintainer ruling: each plant added after
1.5.2 takes the 4.B rule of its nearest 1.5.2 / FC relative, adapted to its lore; `STUB_CATALOGUE.md`
(at), `INTEGRATIONS.md` #018). These blocks have no legacy id, so the general rule never saw them.
`decide` asks `TwinRegistry` first, after the FC-ready and ProxyBlock checks (exception rows 1 and 2).
S numbers name the design's per-plant lore calls (scratchpad t2/modern_plants.md, section 2.2).

- **Twins** (random owner FC, scheduled VANILLA, gate true). A twin is a plain instance of an existing
  FC plant class at a reserved legacy id; the twin state's random tick runs that object's FC
  `updateTick` unchanged (survival, End ban, light, stages, weeds, hydration, fertilizer). Gate true:
  azaleas and fungi gain the random tick 1.20.1 never gave them, and crops tick at every age, as FC's
  crops do.

```
Id  | FC class (kind)                 | Family: 1.20.1 blocks                                    | Twin states
────┼─────────────────────────────────┼──────────────────────────────────────────────────────────┼────────────
174 | FCBlockSapling (SAPLING)        | sapling: acacia, cherry, dark oak saplings; mangrove     | 26
    |                                 | propagule with HANGING false                             |
169 | FCBlockSapling (AZALEA)         | azalea: azalea, flowering azalea                         | 2
168 | FCBlockCarrot (ROOT_CROP)       | beetroot: beetroots                                      | 4
167 | FCBlockWheatLegacy (SEED_CROP)  | torchflower: torchflower crop; pitcher: pitcher crop,    | 2 + 5
    |                                 | lower half                                               |
166 | FCBlockMushroom (FUNGUS)        | fungus: crimson, warped fungus                           | 2
```

  - **Ids.** The free legacy range 159-174 lies below the ProxyBlock range (175 up, which
    BTWRegistration and BlockModelBridge walk); 159 and 170-173 are 1.6+ ids the vanilla table maps.
    `BTWLifecycle` step 5d (`TwinRegistry.createTwins`, after the opaque-table fill, before `fcReady`)
    takes the free ids from the top, before any `ModernBlockStandIn` exists; the stand-ins allocate from
    the bottom (160 solid, 161 collider, flammable from 162), so four flammable stand-ins remain. Each
    twin comes from its FC class's own constructor (its ItemBlock at `itemsList[id]` is never
    registered: no loop below 175 does); `opaqueCubeLookup[id] = false`; `ModernTwins.markTwin(id)`. A
    kind that cannot be built leaves its plants on 1.20.1 (logged, and the audit warns). The ids never
    reach a save: the overlay keys on the 1.20.1 block.
  - **World reads only.** `ProxyRegistry.getWorldBlockId` answers the twin id for a twin state (before
    the stripped-log and stand-in branches); `getBlockId` stays 0, so `BlockBehaviorMixin`, items,
    placement, shape-update survival, loot and scheduled ticks stay 1.20.1's. `getRemovalLegacyId`
    answers the twin id for the crop families, so FC farmland under a harvested crop twin becomes loose
    dirt (S16).
  - **Results** (load-time mixins on the FC classes, `btw.forge.mixin.fcext`, `@Pseudo`, `remap =
    false`, descriptors from javap of the remapped FC output, keyed on `TwinRegistry.isTwinId(blockID)`
    so FC's own sapling 6 and mushroom 40 are untouched): `FCBlockSaplingTwinMixin` at `growTree` HEAD,
    cancellable, grows the live sapling's 1.20.1 tree (`SaplingBlock.advanceTree` at STAGE 1, or
    `AzaleaTreeGrower`); dark oak first passes FC's own 2x2 stage check (`isSameSapling`,
    `GetSaplingGrowthStage`), and a tree that does not fit writes FC stage 3 back to every sapling of
    its square, FC's restore rule. At `CanGrowOnBlock` RETURN it also accepts a soil the live plant's
    1.20.1 `canSurvive` accepts (clay and mud, S3). `FCBlockMushroomTwinMixin` at `updateTick` HEAD,
    cancellable, runs `TwinRegistry.fungusTick`: not in the End; on the species' own nylium 1 in 50
    random ticks to its planted huge fungus (with Forge's `BlockGrowFeatureEvent`), no spread.
  - **WorldBridge.** `deriveVanillaMetadata` / `setBlockMetadata` consult `TwinRegistry.deriveMeta` /
    `stateForMeta` first (before the water-holder branch, which would answer 0 for a waterlogged
    propagule, and the CropBlock branch); `setTwinMetadata` switches the torchflower crop to the
    TORCHFLOWER block at level 7 under the metadata-only write guard and places the pitcher's upper half
    as `PitcherCropBlock.grow` does (the write is refused when there is no room); `isMetaFullyDerived`
    is false for twin blocks; `getBlockMaterial` answers water for a waterlogged twin cell; collision
    boxes come from the live block's shape; `DropModernBlockLoot` drops the live state's 1.20.1 loot,
    which Modern-Common `Block` / `BlockCrops.dropBlockAsItemWithChance` call for a twin
    (`ModernTwins.isTwin`).
- **Paced** (random owner PACED, scheduled VANILLA, gate null: 1.20.1's own flag). No FC class can drive
  these bodies (FCBlockReed hard-codes air above and a 3-block cap, FCBlockVine works on side bits,
  FCBlockCocoa on log attachment), so the 1.20.1 body runs on the fraction `pace` = FC relative's chance
  / 1.20.1's chance of the random ticks: the mean pace is FC's. Row 3: `TickPolicy.pacedRollPasses` is
  false in the End under the End ban, else `random.nextFloat() < pace`. `BlockStateBaseTickMixin` caches
  the pace and End flag per state (`FcPaceAccess.btw$growthPace` / `btw$paceEndBan`).

```
Plant (paced states)                    | 4.B relative and rate                         | Pace    | End
────────────────────────────────────────┼───────────────────────────────────────────────┼─────────┼────────
bamboo shoot, bamboo (1 + 12)           | FCBlockReed 1/32 per segment; 1.20.1 1/3      | 3/32    | banned
kelp (26)                               | FCBlockReed 1/32; 1.20.1 0.14                 | 0.2232  | banned
cave, weeping, twisting vines (52+26+26)| FCBlockVine hanging pace 1/16; 1.20.1 0.1     | 0.625   | banned
sweet berry bush (4)                    | FCBlockCocoa 1/20 per stage; 1.20.1 1/5       | 0.25    | banned
mangrove propagule, HANGING true (20)   | FCBlockCocoa 1/20 per age; 1.20.1 every tick  | 0.05    | banned
chorus flower (6)                       | FCBlockCactus 16 ticks per step; 1.20.1 every | 1/16    | allowed
                                        | tick                                          |         | (S10)
```

- **Growth outside the random tick.** `TickPolicy.isFcGrowthGoverned(state)`: an FC-owned growing plant
  (a random owner FC whose FC block has plant, vine or cactus material), a ProxyBlock plant, any twin or
  paced state (pitcher upper halves and hanging propagules included); a growing-plant body (kelp,
  weeping, twisting and cave vine plants) follows its head. Pure; false before FC is ready. Bone meal
  reads it (see Bone meal (BoneMealPolicy)), and so do bees:

```
# | Target (SRG)                                     | Hook                                  | Does
──┼──────────────────────────────────────────────────┼───────────────────────────────────────┼──────────────────────────────────
1 | Bee$BeeGrowCropGoal.tick m_8037_: the one        | BeeGrowCropGoalMixin, @Redirect       | a visit to a governed plant runs
  | Level.getBlockState m_8055_                      | (the bee from the goal's constructor, | its random tick once (FC body or
  |                                                  | RETURN)                               | paced roll) and answers air; 2005
  |                                                  |                                       | when it changed
2 | Bee.incrementNumCropsGrownSincePollination       | BeeAccessor, @Invoker                 | counts the visit where vanilla
  | m_27871_                                         |                                       | counts it (ten per pollination)
```

- **Kept on 1.20.1** (`BridgeSelfAudit.isKeptOn1201`): the leaves of the new species and nylium decay
  (already 4.B pacing, S13, S14), blocks that only multiply by bone meal (S15), growing-plant bodies,
  and what is not a plant.
- LightPolicy is unchanged: the twin plants emit no light in either table, and the light audit still
  reports one block.

---

## Bone meal (BoneMealPolicy)

Added 2026-10-15 (maintainer ruling: bone meal behaves exactly as in 4.B, fertilising the soil through
FC and never growing a plant the 1.20.1 way; `STUB_CATALOGUE.md` (at), `INTEGRATIONS.md` #017). Code:
`Forge/src/main/java/btw/forge/BoneMealPolicy.java`, `client/BoneMealPrediction.java`,
`mixin/BoneMealItemMixin.java`, `mixin/BonemealableGateMixin.java`,
`mixin/ServerPlayerGameModeMixin.java` (the early return), `ProxyBlock.tryPlaceHeldItem` (the white-dye
guard), Modern-Common `BlockFlower.java` (the FCBlockPlants relay and weed pull) and `Item.java` (the
block-dispenser default).

**The rule.** Bone meal is FC fertilizer and nothing else. Its only effect on a block is 4.B's
`FCItemDye.ApplyBoneMeal`: the clicked block's `AttemptToApplyFertilizerTo`, which plants relay to their
soil (FCBlockPlants) and FC farmland and planters take once. The item is used up exactly when that
returns true. On a block the rule owns, 1.20.1's `BonemealableBlock.performBonemeal` and
`BoneMealItem.growWaterPlant` never run and levelEvent 1505 (particles, sound) never plays. Only
`minecraft:bone_meal` is fertilizer (`isFertilizer`); 1.20.1 white dye, which `LegacyVariants` also
reads as FC dye 15, is not.

**Routes** (`BoneMealPolicy.route(state)`, pure; VANILLA before FC is ready):

```
Route       | When                                                     | Effect
────────────┼──────────────────────────────────────────────────────────┼──────────────────────────────────────────
FC_TARGET   | ProxyRegistry.getBlockId != 0: every FC-mapped block,    | blocksList[id].AttemptToApplyFertilizerTo
            | vanilla ids and ProxyBlocks (grass block, netherrack,    | at the clicked cell (the block's own 4.B
            | sand and cocoa included: 4.B's "nothing")                | rule, relays included)
SOIL_RELAY  | TickPolicy.isFcGrowthGoverned: twin and paced            | FCBlockPlants' relay: the soil below the
            | post-1.5.2 plants, bodies following their head           | plant's lowest cell (a multi-cell plant
            |                                                          | relays through its own cells)
VANILLA     | everything else (moss, nylium, pink petals, seagrass,    | 1.20.1 bone meal
            | modded plants, ...)                                      |
```

**Paths** (Forge 47.3.0; the refmap carries the SRG names):

```
# | Path                          | Target (SRG)                              | Hook                      | Does
──┼───────────────────────────────┼───────────────────────────────────────────┼───────────────────────────┼──────────────────────────────
1 | player use, not sneaking,     | ServerPlayerGameMode.useItemOn m_7179_,   | ServerPlayerGameModeMixin | returns early for FC dye 15
  | main hand                     | HEAD (existing inject)                    | (isFcBoneMeal)            | (no pre-emption): RightClick-
  |                               |                                           |                           | Block and the spectator check,
  |                               |                                           |                           | then blockstate.use: Stage 1
  |                               |                                           |                           | onBlockActivated (weed pull),
  |                               |                                           |                           | Stage 2 FCItemDye
2 | sneaking, off hand, targets   | BoneMealItem.useOn m_6225_, HEAD,         | BoneMealItemMixin         | route VANILLA: 1.20.1. Server:
  | without Stage 2, any useOn    | cancellable                               | (onBoneMealUseOn)         | fertilize, shrink 1, CONSUME,
  | caller                        |                                           |                           | else PASS. Client: predict the
  |                               |                                           |                           | swing, SUCCESS / PASS
3 | other applyBonemeal callers   | BonemealEvent (Forge-added, no SRG), LOW, | BoneMealPolicy.onBonemeal | ALLOW when FC took it (Forge
  | (mods, fake players, growCrop)| DEFAULT results only                      |                           | uses one), else cancel; the
  |                               |                                           |                           | client always cancels
4 | direct performBonemeal /      | isValidBonemealTarget m_7370_ and         | BonemealableGateMixin, on | false / cancelled when
  | isValidBonemealTarget callers | performBonemeal m_214148_, HEAD           | the 31 classes declaring  | governsGrowth (route not
  |                               |                                           | them                      | VANILLA), per state
5 | vanilla dispenser             | DispenserBlock.registerBehavior m_52672_  | BoneMealPolicy.Setup      | DefaultDispenseItemBehavior
  |                               | (a call at FMLCommonSetupEvent,           |                           | for Items.BONE_MEAL: eject,
  |                               | enqueueWork; a put into the registry)     |                           | aux 1000 and 2000
6 | FC block dispenser            | Modern-Common Item                        | port code                 | 1.5.2 default: SpitOutItem
  |                               | .OnItemUsedByBlockDispenser               |                           | inlined, aux 1000, true
7 | Stage 2 with white dye        | ProxyBlock.tryPlaceHeldItem               | BoneMealPolicy            | FCItemDye does not run for a
  |                               |                                           | .mayUseAsFcItem           | stack that is not bone meal
```

- Row 1 restores 4.B's `activateBlockOrUseItem` order (vanilla/server ItemInWorldManager.java:376-407)
  for bone meal: weeds are pulled first, claim mods and Serene Seasons' bone-meal veto see the
  `RightClickBlock` event, and a spectator cannot fertilize. Every other FC item still runs at HEAD
  (`STUB_CATALOGUE.md` (at) caveat BM8).
- Modern-Common `BlockFlower` carries FCBlockPlants' `AttemptToApplyFertilizerTo` (Client
  FCBlockPlants.java:129-143) and `onBlockActivated` (the weed pull, FX 2001, :84-102). Without the
  relay FCItemDye returned false for the whole 1.5.2 flower family and 1.20.1's growth ran.
- Client: `BoneMealPrediction` (`@OnlyIn(Dist.CLIENT)`, reached through `DistExecutor`, render thread)
  runs `applyFertilizer` on `ClientFcWorld`, whose writes are no-ops, so it answers 4.B's boolean for
  the swing without changing anything. The client never shrinks the stack (as `BoneMealItem.useOn` does
  not); the server's slot update does, and the soil's new state arrives through the normal block update.
- Creative: `ServerPlayerGameMode.useItemOn` restores the count around `useOn`, and
  `ProxyBlock.tryPlaceHeldItem` restores it for Stage 2, as 4.B did.
- An FC failure inside `AttemptToApplyFertilizerTo` is logged once and counts as declined (the use
  packet handler has no catch above it).
- Dropped bone meal keeps FC's own item rule (`FCBlockFarmlandBase` / `FCBlockPlanterBase
  .onEntityCollidedWithBlock` through the item bridges). Gap: that path sees white dye as FC dye 15 too
  (`STUB_CATALOGUE.md` (at), WD5).
- Extension point: a compat rule for modded plants on BTW soil adds its condition to `route` as a
  SOIL_RELAY and reuses `relayToSoil` and `onBonemeal`; it does not add a second `BonemealEvent`
  listener.

---

## Stays removed (RemovedContent)

Added 2026-10-15 (maintainer ruling: blocks that 4.B explicitly removed stay removed; modern-only blocks
4.B never had are not covered; `STUB_CATALOGUE.md` (at), `INTEGRATIONS.md` #019). Code:
`Forge/src/main/java/btw/forge/removed/RemovedContent.java` (the table),
`removed/RemovedContentEvents.java`, `removed/RemovedCreativeTabs.java`, `FcRecipeRemovals.java`,
`RetiredIngredientWidening.java`, `RecipeBridge.java`, `data/FCRemovedContentLootModifier.java`,
`data/FCLootOverrides.java`, `mixin/StructureTemplateRemovedMixin.java`,
`mixin/StructurePieceRemovedMixin.java`, Modern-Common `CraftingManager.java` (the removal records).

**The table.** One row per removed id: the 1.20.1 item and blocks, the tier, the expected 1.5.2 ids, the
4.B citation, and the structure rule (T1) or BTW successor (T2). Every hook reads it; the lookups are
immutable after class init, so worldgen threads may read them.

```
Tier                | Ids                                               | Recipes | Loot        | Creative | Structures
────────────────────┼───────────────────────────────────────────────────┼─────────┼─────────────┼──────────┼──────────────────
T1 stubbed (12)     | hopper, comparator, daylight_detector, dropper,   | removed | filtered    | hidden   | substituted (below)
                    | trapped_chest, light / heavy weighted plates,     |         |             |          |
                    | activator_rail, tnt_minecart, hopper_minecart,    |         |             |          |
                    | tripwire_hook, tripwire                           |         |             |          |
T2 retired (8)      | crafting_table, chest, ladder, torch (+ wall      | removed | the BTW     | hidden   | kept: FC's legacy
                    | torch), snow_block, clay, cobweb, nether_brick    |         | successor   |          | drops convert them
T3 recipe-only (8)  | wooden sword, shovel, pickaxe, axe, hoe;          | removed | filtered    | listed   | kept
                    | stone_hoe; snow (layer); furnace                  |         |             |          |
```

- T2 successors (FCBetterThanWolves fields, resolved to registered 1.20.1 items at first use): BTW
  workbench, chest, ladder, the unlit finite torch, solid snow, unfired clay, BTW web, BTW nether brick.
  A successor that does not resolve is filtered instead, and the audit warns.
- Ingredient successors (RetiredIngredientWidening): FC's legacy conversion target of each T2 id
  (FCRecipes.java:5080-5102); the torch stands for the nether-coal torch, lit (1057) or unlit (1033),
  never the finite torch, which would become a permanent light.

**Hooks:**

```
# | Concern         | Target (SRG)                                         | Code                          | Does
──┼─────────────────┼──────────────────────────────────────────────────────┼───────────────────────────────┼─────────────────────────────
1 | recipes         | RecipeBridge.injectRecipes, on the map before        | FcRecipeRemovals.apply, then  | the four passes below; then
  |                 | RecipeManager.replaceRecipes (also the               | RetiredIngredientWidening     | widening; /reload re-applies
  |                 | OnDatapackSyncEvent re-inject)                       | .apply                        | both
2 | loot            | global loot modifier                                 | FCRemovedContentLootModifier  | T1 / T3 filtered, T2 the
  |                 | betterthanwolves:removed_content, no conditions:     | (FCLootOverrides emits the    | successor at the same count
  |                 | every top-level LootTable.getRandomItems             | JSON)                         |
  |                 | (ForgeHooks.modifyLoot)                              |                               |
3 | templates       | StructureTemplate.processBlockInfos(                 | StructureTemplateRemovedMixin | T1 states substituted after
  |                 | ServerLevelAccessor, BlockPos, BlockPos,             | at RETURN, cancellable,       | every processor, before
  |                 | StructurePlaceSettings, List, StructureTemplate),    | remap = false (Forge-added,   | mirror / rotation; NBT kept
  |                 | the Forge overload (StructureTemplate.java:374)      | no mapping)                   | only where it still fits
4 | code pieces     | StructurePiece.placeBlock m_73434_, HEAD             | StructurePieceRemovedMixin,   | same substitutes (generateBox
  |                 |                                                      | @ModifyVariable on the state  | and createDispenser go
  |                 |                                                      |                               | through it)
5 | creative, JEI   | BuildCreativeModeTabContentsEvent (MOD bus, LOWEST)  | RemovedCreativeTabs           | T1 and T2 entries removed
  |                 |                                                      |                               | from every tab (JEI and
  |                 |                                                      |                               | Search follow the tabs)
6 | placement       | PlayerInteractEvent.RightClickBlock                  | RemovedContentEvents          | setUseItem(DENY) for an item
  |                 |                                                      |                               | that places a T1 block
  |                 |                                                      |                               | without being one (string)
7 | entities        | EntityJoinLevelEvent (not loadedFromDisk); server    | RemovedContentEvents          | fresh TNT / hopper minecarts
  |                 | tick END and ServerStoppingEvent                     |                               | cancelled, plain minecarts
  |                 |                                                      |                               | added (contents dropped)
8 | trades (guard)  | VillagerTradesEvent, WandererTradesEvent (LOWEST)    | RemovedContentEvents          | listings wrapped: T1 / T3
  |                 |                                                      | .GuardedListing               | offers dropped, T2 sells the
  |                 |                                                      |                               | successor
9 | audit           | FMLCommonSetupEvent (enqueueWork)                    | RemovedContent.audit          | [REMOVED-AUDIT] line
```

**Structure substitutes (T1):** trapped chest -> chest (FACING, TYPE, WATERLOGGED and chest data kept);
dropper -> dispenser (FACING and inventory data kept); comparator -> repeater (same FACING, delay 1);
activator rail -> rail (shape kept); light and heavy weighted plates -> stone pressure plate; hopper,
daylight detector, tripwire hook and tripwire -> air. A modded state without the expected properties
becomes air. `StructurePiece.createChest` writes `Blocks.CHEST` directly, a T2 id that is deliberately
not substituted.

**Recipe passes** (`FcRecipeRemovals.apply`; passes 1-3 behind
`RecipeBridge.MIRROR_FC_VANILLA_REMOVALS`, true; pass 4 always):
1. FC's records: Modern-Common `CraftingManager.RemoveRecipe` / `RemoveShapelessRecipe` record every
   call (`RemovedVanillaRecipe`, parsed as 1.5.2 parsed it). Each removes the `minecraft:` crafting
   recipes whose result and ingredient multiset match: shape ignored, tags resolved (an FC ingredient
   accepts a 1.20.1 `Ingredient` when any of its items maps back to that id and metadata, 32767 = any),
   the count compared only when an exact-count recipe exists, results equivalent across a recipe group
   whose results map to one legacy block (the 16 beds). The MC-to-legacy mapping never allocates a
   headless id.
2. Special recipes: every `MAP_EXTENDING` and `REPAIR_ITEM` recipe (vanilla/server
   CraftingManager.java:49-51, 283-382).
3. Cooking: the inputs FC took out of the 1.5.2 furnace table (vanilla/server FurnaceRecipes.java:37-55,
   compared with FC's live list; clay keeps its brick); every `minecraft:` smelting, blasting, smoking
   and campfire recipe whose ingredient maps to one.
4. Stays removed: every recipe of any type and namespace whose result is a table item; foreign
   namespaces are logged at INFO. FC's injected `fc_*` recipes are never removed; one that makes a table
   item is reported as a WARN.

**Widening** (`RetiredIngredientWidening.apply`, after the removals): a recipe outside
`betterthanwolves` whose ingredient accepts a T2 id also accepts its ingredient successors
(`Ingredient.merge` for a vanilla ingredient, Forge `CompoundIngredient` otherwise). Only ShapedRecipe,
ShapelessRecipe, SmeltingRecipe, BlastingRecipe, SmokingRecipe, CampfireCookingRecipe and
StonecutterRecipe are rebuilt, with the same id, result, group, category, pattern and timings; other
classes are named in one log line. A recipe whose result FC makes its own way is not widened (FC's route
wins). Recipes stranded by a T1 or T3 ingredient are reported once.

**Audit** (`RemovedContent.audit`): every FC block of class FCBlockStub, FCBlockVanillaHopper,
FCBlockDaylightDetector or FCBlockTripWireSource and every FCItemStub must be a T1 row (class names, not
a null-tab test); every row's blocks and item must map to the cited 1.5.2 ids; every T2 successor and
ingredient successor must resolve.

```
[REMOVED-AUDIT] stays-removed table: <n> T1, <n> T2, <n> T3 rows; FC stub blocks [...] and items [...] covered; <n> warning(s)
FC vanilla-recipe removals on: <m>/<n> records matched (<n> count-insensitive, <n> unmatched), <n> crafting
  + <n> special + <n> cooking recipes removed (removed furnace inputs [...]); stays-removed rule removed <n>
  more (<n> outside minecraft); <n> recipes removed in total                          (logger BTW-RecipeRemovals)
Widened <n> recipes whose retired-id ingredients now also accept the BTW successor (...); not widened because
  FC makes the result its own way: [...]; not rebuildable: [...]                     (logger BTW-RecipeWidening)
Stranded by a stubbed (T1) or recipe-only (T3) ingredient, ...: [...]                (logger BTW-RecipeWidening)
```

2026-10-15 values: `12 T1, 8 T2, 8 T3 rows; FC stub blocks [154, 149, 150, 151, 158, 146, 147, 148, 157,
131, 132] and items [404, 407, 408] covered; 0 warning(s)`; `93/94 records matched (3 count-insensitive,
1 unmatched), 108 crafting + 2 special + 20 cooking recipes removed ... 130 recipes removed in total`
(the unmatched record is FC's 6-stick fence); 13 recipes widened; stranded: blast_furnace, smoker,
furnace_minecart, crossbow.

**Not covered, by the ruling's scope:** removed blocks already in a world or placed by commands (left
alone; `/give` and `/setblock` stay op tools, as in 4.B); modern-only blocks 4.B never had; the
remaining CT6 rows (records other than 13, enchanted books, wheat seeds and legacy wheat, glowing
redstone ore, the BlockStem / BlockNetherStalk / BlockRedstoneTorch stand-ins).

---

## CATALOGUE 1: STATE SYNC

### 1.1 Entity Base Fields (24 channels, sync every tick unless noted)

All bidirectional fields must be synced BEFORE btw.modern tick logic runs (MC→btw),
then synced BACK after btw.modern tick logic completes (btw→MC).

```
# | Direction | MC 1.20.1 accessor             | btw.modern field              | Notes
──┼───────────┼────────────────────────────────┼───────────────────────────────┼──────
1  | MC→btw    | Entity.getId()                 | Entity.entityId               | Once on creation
2  | bidir     | Entity.getX()/getY()/getZ()    | Entity.posX/posY/posZ         | FC modifies on dismount, knockback
3  | MC→btw    | Entity.xOld/yOld/zOld          | Entity.prevPosX/prevPosY/prevPosZ |
4  | bidir     | Entity.getDeltaMovement()      | Entity.motionX/motionY/motionZ | FC applies knockback, fling
5  | bidir     | Entity.getXRot()/getYRot()      | Entity.rotationPitch/Yaw      |
6  | MC→btw    | Entity.xRotO/yRotO             | Entity.prevRotationPitch/Yaw  |
7  | MC→btw    | Entity.onGround()              | Entity.onGround               |
8  | MC→btw    | Entity.horizontalCollision     | Entity.isCollidedHorizontally |
9  | MC→btw    | Entity.verticalCollision       | Entity.isCollidedVertically   |
10 | MC→btw    | computed from 8+9              | Entity.isCollided             | = H || V
11 | bidir     | Entity.hurtMarked              | Entity.velocityChanged        |
12 | bidir     | Entity.isRemoved()             | Entity.isDead                 |
13 | MC→btw    | Entity.getBbWidth()/Height()    | Entity.width/height           | On creation + resize
14 | bidir     | Entity.fallDistance             | Entity.fallDistance            |
15 | MC→btw    | Entity.tickCount               | Entity.ticksExisted           |
16 | MC→btw    | Entity.isInWater()             | Entity.inWater                |
17 | bidir     | Entity.invulnerableTime        | Entity.hurtResistantTime      |
18 | MC→btw    | Entity.getRemainingFireTicks() | Entity.fire                   |
19 | MC→btw    | dimension from level           | Entity.dimension              | On creation/transfer
20 | MC→btw    | Entity.getBoundingBox()        | Entity.boundingBox            | Needs AABB wrapper
21 | MC→btw    | Entity.isSprinting()           | via isSprinting()             |
22 | MC→btw    | Entity.isCrouching()           | via isSneaking()              |
23 | MC→btw    | portal state                   | Entity.inPortal/timeInPortal  | FC modifies portal behavior
24 | MC→btw    | Entity.isNoGravity() etc       | Entity.noClip                 | Creative flight
```

### 1.2 EntityLiving Fields (9 channels)

```
# | Direction | MC 1.20.1 accessor             | btw.modern field              | Notes
──┼───────────┼────────────────────────────────┼───────────────────────────────┼──────
25 | bidir     | LivingEntity.getHealth()       | EntityLiving.health           | CAST: MC float → FC int
26 | MC→btw    | LivingEntity.deathTime         | EntityLiving.deathTime        |
27 | MC→btw    | LivingEntity.hurtTime          | EntityLiving.hurtTime         |
28 | MC→btw    | LivingEntity.attackAnim        | EntityLiving.swingProgress    |
29 | MC→btw    | Mob.getTarget() mapped         | EntityLiving.entityLivingToAttack | Entity mapping needed
30 | MC→btw    | LivingEntity.yBodyRot          | EntityLiving.renderYawOffset  |
31 | MC→btw    | LivingEntity.yHeadRot          | EntityLiving.rotationYawHead  |
32 | bidir     | LivingEntity.getSpeed()        | EntityLiving.landMovementFactor | FC modifies via penalties
33 | MC→btw    | LivingEntity.getLastHurtByMob()| EntityLiving.recentlyHit etc  | Entity mapping needed
```

### 1.3 EntityPlayer Fields (28 channels)

```
# | Direction | MC 1.20.1 accessor             | btw.modern field              | Notes
──┼───────────┼────────────────────────────────┼───────────────────────────────┼──────
34 | MC→btw    | ServerPlayer.getInventory()    | EntityPlayer.inventory        | Needs InventoryBridge
35 | REPLACE   | ServerPlayer.getFoodData()     | EntityPlayer.foodStats        | FC replaces entirely (see 1.4)
36 | MC→btw    | ServerPlayer.getAbilities()    | EntityPlayer.capabilities     | Needs CapabilitiesBridge
37 | MC→btw    | ServerPlayer.containerMenu     | EntityPlayer.openContainer    |
38 | MC→btw    | ServerPlayer.inventoryMenu     | EntityPlayer.inventoryContainer| FC uses FCContainerPlayer
39 | MC→btw    | ServerPlayer.experienceLevel   | EntityPlayer.experienceLevel  |
40 | MC→btw    | ServerPlayer.totalExperience   | EntityPlayer.experienceTotal  |
41 | MC→btw    | ServerPlayer.experienceProgress| EntityPlayer.experience       |
42 | MC→btw    | ServerPlayer.isSleeping()      | EntityPlayer.sleeping         | FC disables beds
43 | MC→btw    | Player.getName().getString()   | EntityPlayer.username         | Once
44 | MC→btw    | Player.isCreative()            | capabilities.isCreativeMode   |
45 | MC→btw    | Player.getVehicle()            | EntityPlayer.ridingEntity     | Entity mapping
46 | FC-only   | —                              | m_iTimesCraftedThisTick       | Reset each tick
47 | FC-only   | —                              | m_iTicksSinceEmoteSound       | Incremented each tick
48 | FC-only   | —                              | m_iInGloomCounter             | Gloom tracking
49 | FC-only   | —                              | m_fCurrentMiningSpeedModifier | Computed each tick
50 | FC-only   | —                              | m_HardcoreSpawnChunk          | FC spawn system
51 | FC-only   | —                              | m_iSpawnDimension             | FC spawn system
52 | FC-only   | —                              | m_lTimeOfLastSpawnAssignment  | FC spawn system
53 | FC-only   | —                              | m_lTimeOfLastDimensionSwitch  | FC spawn system
54 | FC-only   | —                              | m_lRespawnAssignmentCooldownTimer | FC spawn system
55 | DW→SED    | SynchedEntityData              | DataWatcher ID 22: magneticI  | Needs EntityDataAccessor
56 | DW→SED    | SynchedEntityData              | DataWatcher ID 23: magneticK  | Needs EntityDataAccessor
57 | DW→SED    | SynchedEntityData              | DataWatcher ID 24: hasMagnetic| Needs EntityDataAccessor
58 | DW→SED    | SynchedEntityData              | DataWatcher ID 25: gloomLevel | Needs EntityDataAccessor
59 | DW→SED    | SynchedEntityData              | DataWatcher ID 26: fatPenalty | Needs EntityDataAccessor
60 | DW→SED    | SynchedEntityData              | DataWatcher ID 27: hungerPen  | Needs EntityDataAccessor
61 | DW→SED    | SynchedEntityData              | DataWatcher ID 28: healthPen  | Needs EntityDataAccessor
```

### 1.4 FoodStats — Complete FC Replacement

FC FoodStats replaces vanilla FoodData entirely. No sync — FC owns this system.

```
Field                  | FC Value     | Vanilla Value | Notes
───────────────────────┼──────────────┼───────────────┼──────
foodLevel              | 0-60         | 0-20          | 3x resolution. Display = FC/3
foodSaturationLevel    | 0.0-20.0     | 0.0-20.0      | Same range, different burn logic
foodExhaustionLevel    | 0.0-40.0     | 0.0-40.0      | Burns at 1.33F/pip (not 4.0F)
foodTimer              | heals@600    | heals@80      | 30 sec vs 4 sec
prevFoodLevel          | init=60      | init=20       |
initial saturation     | 0.0          | 5.0           | FC starts with no saturation
heal threshold         | foodLevel>24 | foodLevel>=18 | ~8 pips vs ~18 pips
starvation condition   | food<=0 AND sat<=0.01 | food<=0 |
```

FC FoodStats behavioral details:
- `ShouldBurnFatBeforeHunger()`: burns fat when `saturation > (foodLevel + 5) / 6 * 2F`
- Fat burns at 0.5F exhaustion per 0.125F saturation
- `addStats()` only converts excess food to saturation, not all food
- `canEat()` additionally blocked by Potion.hunger effect
- On vanilla save import: `foodLevel *= 3`, `saturation = 0`, set `fcFoodLevelAdjusted` NBT flag

### 1.5 NBT Persistence (read/write on save/load)

```
NBT Key                        | btw.modern field                    | Type
───────────────────────────────┼─────────────────────────────────────┼──────
fcTimeOfLastSpawnAssignment    | m_lTimeOfLastSpawnAssignment        | long
fcTimeOfLastDimensionSwitch    | m_lTimeOfLastDimensionSwitch        | long
fcRespawnCooldown              | m_lRespawnAssignmentCooldownTimer   | long
fcHCSpawnX / fcHCSpawnY / fcHCSpawnZ | m_HardcoreSpawnChunk          | int x3
fcSpawnDimension               | m_iSpawnDimension                  | int
fcGloomCounter                 | m_iInGloomCounter                  | int
fcFoodLevelAdjusted            | (migration flag)                   | boolean
```

---

## CATALOGUE 2: SYSTEM OVERRIDES (37 mixins)

### 2.1 Food System (5 overrides)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
1  | FoodData.tick(Player)               | @Inject HEAD cancel        | fcPlayer.foodStats.onUpdate(fcPlayer)
2  | FoodData.eat(int, float)            | @Inject HEAD cancel        | fcPlayer.foodStats.addStats(int, float)
3  | FoodData.addExhaustion(float)       | @Redirect                  | Multiply by GetArmorExhaustionModifier()
4  | Natural regen in FoodData.tick      | Handled by #1 cancel       | FC: heal 1hp/600t when food>24
5  | Peaceful healing in doTick          | @Inject HEAD cancel/redir  | FC removes peaceful healing
```

### 2.2 Movement (8 overrides)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
6  | Player.jumpFromGround() exhaustion  | @Inject TAIL / @Overwrite  | FC: 0.2F/1.0F × armor modifier
7  | LivingEntity.travel() swim speed    | @Redirect on swim calc     | × GetSwimmingHorizontalModifier()
8  | LivingEntity.travel() land speed    | @Redirect on speed attrib  | × GetLandMovementModifier()
9  | LivingEntity.travel() ladder speed  | @Redirect on ladder calc   | × GetLadderVerticalMovementModifier()
10 | jumpMovementFactor assignment       | @Redirect                  | × GetJumpingHorizontalMovementModifier()
11 | LivingEntity.getJumpPower()         | @Inject HEAD cancel        | CanJump(): health>4, food>12, sat<18
12 | Swimming upward logic               | @Inject HEAD cancel        | CanSwim(): !weighted, health>4
13 | LivingEntity.onClimbable()          | @Inject HEAD cancel        | false when GetHealthPenaltyLevel()>=4
```

### 2.3 Exhaustion (4 overrides)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
14 | Player.causeFoodExhaustion(float)   | @Overwrite / @Redirect     | × GetArmorExhaustionModifier() (weight/44)
15 | Walking exhaustion in checkMovement | @Redirect                  | AddExhaustionWithoutVisualFeedback(0.01F)
16 | Swimming exhaustion                 | @Redirect                  | AddExhaustionWithoutVisualFeedback(0.015F)
17 | Swimming upward exhaustion          | @Inject                    | Add 0.025F/tick when going up in water
```

### 2.4 Combat (3 overrides)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
18 | Player.hurt(DamageSource, float)    | @Inject RETURN             | Blasting oil detonation check
19 | Player.actuallyHurt()              | @Inject in blocking branch | OnBlockedDamage() — damages held item
20 | Player.attack(Entity)              | @Redirect on damage calc   | × GetMeleeDamageModifier()
```

### 2.5 Mining (1 override)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
21 | Player.getDestroySpeed(BlockState)  | @Inject RETURN cancel      | × GetMiningSpeedModifier()
```

### 2.6 Sleep (1 override)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
22 | Player.startSleepInBed(BlockPos)    | @Overwrite                 | Always return OTHER_PROBLEM
```

### 2.7 Block Placement (1 override)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
23 | Player.mayUseItemAt()              | @Inject HEAD cancel        | Require onGround||inWater||ladder||riding||lava
```

### 2.8 Eating (1 override)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
24 | Player.canEat(boolean)             | @Inject HEAD cancel        | Block when Hunger potion active
```

### 2.9 Air/Drowning (2 overrides)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
25 | LivingEntity.decreaseAirSupply(int)| @Overwrite                 | Soulforged helm + Respiration custom
26 | Air recovery (instant to 300)       | @Inject replace logic      | Gradual: 10/tick after 20-tick delay
```

### 2.10 Container (1 override)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
27 | ServerPlayer constructor container  | @Redirect                  | FCContainerPlayer instead of InventoryMenu
```

### 2.11 Spawn/Respawn (2 overrides)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
28 | findRespawnPositionAndUseSpawnBlock | @Overwrite                 | FC beacon-based spawn system
29 | Player.setRespawnPosition()        | @Overwrite                 | Add dimension tracking
```

### 2.12 Wolf AI (1 override)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
30 | Wolf alerting logic                 | @Inject / @Redirect        | Force attackIfNotSitting, use FC entities
```

### 2.13 Per-Tick FC Systems (5 hooks)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
31 | ServerPlayer.tick() end            | @Inject TAIL               | Call UpdateModStatusVariables()
32 | — (part of 31)                     | —                          | UpdateGloomState() — light, nausea, damage
33 | — (part of 31)                     | —                          | UpdateFatPenaltyLevel() — sat thresholds
34 | — (part of 31)                     | —                          | UpdateHungerPenaltyLevel() — food thresholds
35 | — (part of 31)                     | —                          | UpdateHealthPenaltyLevel() — hp thresholds
```

### 2.14 Misc (2 overrides)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
36 | Item use comparison in tick         | @Redirect                  | IgnoreDamageWhenComparingDuringUse()
37 | Death drop handling                 | @Inject                    | SetEntityItemAsDroppedOnPlayerDeath()
```

---

## CATALOGUE 3: EVENT HOOKS (btw.modern → MC)

### 3.1 World Actions (22 hooks, plus rows 15a-15m added 2026-10-11) — implement in WorldBridge

```
# | FC Method                                    | MC 1.20.1 Implementation                    | Status
──┼────────────────────────────────────────────────┼──────────────────────────────────────────────┼────────
1  | World.playSoundEffect(x,y,z,name,vol,pitch)    | level.playSound(except, x,y,z, SoundEvent,..)| EXISTS: name via LegacySounds.resolve (S4); except: 15l
2  | World.playSoundAtEntity(entity,name,vol,pitch)| level.playSound(null, player, SoundEvent, ..) | TODO
3  | World.playSoundToNearExcept(pl,name,vol,pitch)| level.playSound(player, x,y,z, SoundEvent,..)| TODO
4  | World.spawnParticle(type,x,y,z,vx,vy,vz)     | level.sendParticles(ParticleOptions, ...)     | TODO: particle mapping
5  | World.spawnEntityInWorld(entity)               | level.addFreshEntity(proxy)                  | EXISTS (EntityProxyFactory)
6  | World.removeEntity(entity)                     | proxy.discard()                              | EXISTS
7  | World.createExplosion(entity,x,y,z,str,fire)  | level.explode(...)                           | EXISTS
8  | World.playAuxSFX(effectID,x,y,z,data)          | = playAuxSFXAtEntity(null, ...): 15j / 15k   | EXISTS (ids < 2222 through AuxSfxMapping)
9  | World.getEntitiesWithinAABB(class,aabb)        | level.getEntities(null, aabb, predicate)     | EXISTS
10 | World.setBlock(x,y,z,id,meta,flags)           | level.setBlock(pos, state, flags)            | EXISTS
11 | World.getBlockId(x,y,z)                        | state→ProxyRegistry.getBlockId(block)        | EXISTS
12 | World.getBlockMetadata(x,y,z)                  | META; else derive + FcMetaOverlay            | EXISTS (2026-10-14, see Tick ownership)
13 | World.getBlockMaterial(x,y,z)                  | blocksList[id].blockMaterial                 | EXISTS
14 | World.scheduleBlockUpdate(x,y,z,id,delay)      | level.scheduleTick(pos, block, delay)        | EXISTS
15 | World.notifyBlockChange(x,y,z,id)              | level.updateNeighborsAt(pos, source)         | EXISTS (2026-10-11, F3; was sendBlockUpdated)
15a| World.notifyBlocksOfNeighborChange(x,y,z,id)   | level.updateNeighborsAt(pos, source)         | EXISTS (source: see the note on 15)
15b| Block.onNeighborBlockChange(..., neighbourId)  | ProxyRegistry.getNeighbourSourceId(block,..) | EXISTS (ProxyBlock, BlockBehaviorMixin)
15c| World.markBlockForUpdate(x,y,z)                | level.sendBlockUpdated(pos, s, s, 3)         | EXISTS (the only sendBlockUpdated)
15d| World.setBlockAndMetadata(x,y,z,id,meta)       | setBlock(x,y,z,id,meta, 2)                   | EXISTS (Modern-Common World, flag 2)
15e| World.isBlockGettingPowered(x,y,z)             | level.getDirectSignalTo(pos) > 0             | EXISTS (strong power only, F4)
15f| World.isBlockIndirectlyGettingPowered(x,y,z)   | level.hasNeighborSignal(pos)                 | EXISTS
15g| World.isBlockTickScheduled(x,y,z,id)           | level.getBlockTicks().willTickThisTick(..)   | EXISTS (this tick's drained ticks)
15h| World.IsUpdatePendingThisTickForBlock(..)      | = isBlockTickScheduled (15g)                 | EXISTS
15i| World.IsUpdateScheduledForBlock(x,y,z,id)      | level.getBlockTicks().hasScheduledTick(..)   | EXISTS (future ticks only)
15j| World.playAuxSFXAtEntity(player,id,x,y,z,data) | id < 2222: AuxSfxMapping -> levelEvent       | EXISTS; actor left out only for 2001 or off a ProxyBlock
15k| FC aux id >= 2222 (playAuxSFX*, func_82739_e)  | BTWNetwork.sendFcAuxSfx -> FcAuxSfx message  | EXISTS; client: FC ClientPlayCustomAuxFX
15l| World.playSoundEffect in a predicted placement | playSoundEffectExcept(PREDICTING_ACTOR, ..)  | EXISTS (BlockBehaviorMixin stage 2, BlockItem)
15m| client World.playSound / playAuxSFX*           | LegacyClientSound.playLocal / playAux        | EXISTS (server playSound: no-op, as 1.5.2)
16 | World.ModSpecificTick()                         | @Inject on ServerLevel.tick() TAIL           | TODO
17 | World.isRaining()/isThundering()                | level.isRaining()/isThundering()             | EXISTS
18 | World.IsRainingAtPos(x,y,z)                    | level.isRainingAt(pos) + biome check         | TODO
19 | World.UpdateActiveChunkMap()                     | FC-only system                              | TODO
20 | World.GetMagneticPointList()                     | FC-only: held on WorldBridge                | TODO
21 | World.GetSpawnLocationList()                     | FC-only: held on WorldBridge                | TODO
22 | World.ComputeOverworldSunBrightness...()        | level.getTimeOfDay() + moon phase            | TODO
```

Rows 1, 8 and 15-15m were re-mapped on 2026-10-11 (`STUB_CATALOGUE.md` (an), (ao), (aq)), row 12 on
2026-10-14 ((as); the overlay and its validity rule are under Tick ownership). The rule
for this table: a row maps the 1.5.2 *body* of the FC primitive (vanilla/server and vanilla/client,
FC-edited), never its name.

- **15, 15a:** 1.5.2 `notifyBlockChange` is `notifyBlocksOfNeighborChange` (vanilla/server
  World.java:649-652, :694-702), a neighbour update, not a client resync. The source block is air for
  id 0 (FC passes 0 after clearing a cell, FCEntityUrn.java:315-317), the variant at `pos` when it
  reads as that id, else the id's canonical block, and for an unmapped id whatever is at `pos`
  (`WorldBridge.neighbourSource`, WorldBridge.java:966-980).
- **15b:** the id FC's `onNeighborBlockChange` receives is `getWorldBlockId` of the source block's
  default state: a ProxyBlock or legacy-id vanilla block gives its legacy id, a colliding post-1.5.2
  block its stand-in id (160/161), air 0 (ProxyRegistry.java:280-288; ProxyBlock.java:379,
  BlockBehaviorMixin.java:178). 1.5.2 passes the notifying id through, the old id for `setBlock`
  (World.java:461-478), as 1.20.1 `neighborChanged` passes the old block. FCBlockBuddyBlock ignores
  id 0 (Client FCBlockBuddyBlock.java:78-80), so a 0 here makes buddy blocks blind.
- **15d:** vanilla/server World.java:4152-4155. With flag 0, 1.20.1 `Level.setBlock` never calls
  `sendBlockUpdated`, so FCBlockNetherGrowth's and FCBlockSapling's changes never reached clients.
- **15e, 15f:** `isBlockGettingPowered` is `getBlockPowerInput > 0` (World.java:4157-4160), the
  strongest `isProvidingStrongPower` of the six neighbours (:3452-3510); a 1.5.2 redstone block gives
  weak power only. `isBlockIndirectlyGettingPowered` (:3558-3561) relays strong power through normal
  cubes, which is `hasNeighborSignal`. Side order matches (1.5.2 sides 0-5 = `Direction`
  `get3DDataValue`).
- **15g-15i:** `isBlockTickScheduled` checks this tick's drained list (WorldServer.java:421-425),
  `IsUpdateScheduledForBlock` the future set (:1258-1263). `LevelChunkTicks.poll` drops a drained
  tick from `ticksPerPosition`, so `hasScheduledTick` cannot answer the first.
- **15j:** 1.5.2 `WorldManager.playAuxSFX` sends Packet61 to everyone within 64 blocks except the
  actor (WorldManager.java:103-106), whose client played the event itself. The bridge leaves the
  actor out only where its 1.20.1 client really predicted it: 2001 (the break, predicted by
  `MultiPlayerGameMode.destroyBlock`), and any id raised off a ProxyBlock, whose `use()` runs FC on
  the server only (the FC wood door, Client FCBlockDoor.java:63) (WorldBridge.java:2059-2085).
  `playAuxSFX` = `playAuxSFXAtEntity(null, ...)` (World.java:3912-3915).
- **15k:** FC ids are played by FC's own client handler, never re-implemented on the server:
  `WorldBridge.sendFcAuxSfx` -> `BTWNetwork.sendFcAuxSfx` (BTWNetwork.java:168-185, network protocol
  4) -> `LegacyClientSound.playAux` -> `FCAddOnHandler.ClientPlayCustomAuxFX`. Non-global: players
  within 64 blocks except the actor (15j). Global (`func_82739_e`): every player on the server, as 1.5.2
  `broadcastSound` (WorldManager.java:108-111).
  `tools/BridgeAudit.java` check [2] fails if a link of this chain disappears.
- **15l:** 1.5.2 clients predicted placement silently (`RenderGlobal.playSound` is a no-op,
  vanilla/client RenderGlobal.java:1812). A 1.20.1 client that got PASS from a block's `use()`
  predicts `BlockItem.useOn` and plays the place sound itself, so while BlockBehaviorMixin stage 2
  places for a held BlockItem, `WorldBridge.withPredictingActor` makes `playSoundEffect` leave that
  player out (WorldBridge.java:1770-1793, BlockBehaviorMixin.java:255-266). `ProxyBlock.use` needs no
  scope: its client returns SUCCESS and predicts nothing.
- **15m:** every client-side FC world (ProxyEntity, ProxyBlockEntity, ProxyBlock's client view,
  ClientFcWorld) implements 1.5.2 WorldClient + RenderGlobal through
  `btw.forge.client.LegacyClientSound`: `playSound` plays locally (WorldClient.java:483-505),
  `playAuxSFX*` plays vanilla ids as local level events and FC ids through `ClientPlayCustomAuxFX`,
  and `playSoundEffect` / `playSoundAtEntity` do nothing (RenderGlobal.java:1812/:1817). Names resolve
  through `LegacySounds.resolve` on both sides (row 1).

### 3.2 Player Actions (28 hooks) — implement in PlayerBridge

```
# | FC Method                                    | MC 1.20.1 Implementation                    | Status
──┼────────────────────────────────────────────────┼──────────────────────────────────────────────┼────────
23 | EntityPlayer.addChatMessage(msg)              | serverPlayer.sendSystemMessage(Component)    | TODO
24 | EntityPlayer.AddRawChatMessage(msg)           | serverPlayer.connection.send(ChatPacket)     | TODO
25 | EntityPlayer.playSound(name,vol,pitch)        | serverPlayer.playSound(SoundEvent, vol, pit) | TODO
26 | EntityPlayer.displayGUIChest(inv)             | serverPlayer.openMenu(MenuProvider)           | TODO
27 | EntityPlayer.displayGUIWorkbench(x,y,z)       | serverPlayer.openMenu(...)                   | TODO
28 | EntityPlayer.displayGUIEditSign(te)            | serverPlayer.openTextEdit(SignBlockEntity)    | TODO
29 | EntityPlayer.triggerAchievement(stat)          | serverPlayer.awardStat(stat)                 | TODO: stat mapping
30 | EntityPlayer.addStat(stat,amount)              | serverPlayer.awardStat(stat, amount)         | TODO
31 | EntityPlayer.addExperience(int)                | serverPlayer.giveExperiencePoints(int)       | TODO
32 | EntityPlayer.setItemInUse(stack,duration)      | serverPlayer.startUsingItem(Hand)            | TODO
33 | EntityPlayer.dropPlayerItem(stack)             | serverPlayer.drop(ItemStack, boolean)        | TODO
34 | EntityPlayer.destroyCurrentEquippedItem()      | serverPlayer.getInventory().removeItem(stack) | TODO
35 | EntityPlayer.heal(amount)                      | serverPlayer.heal(float) [int→float]         | TODO
36 | EntityPlayer.attackEntityFrom(source,amount)   | serverPlayer.hurt(DamageSource, float)       | TODO: DamageSource map
37 | EntityPlayer.addExhaustion(float)              | fcFoodStats.addExhaustion() × armor modifier | TODO
38 | EntityLiving.addPotionEffect(effect)           | serverPlayer.addEffect(MobEffectInstance)     | TODO: potion ID map
39 | EntityLiving.isPotionActive(potion)            | serverPlayer.hasEffect(MobEffect)            | TODO
40 | EntityLiving.getActivePotionEffect(potion)     | serverPlayer.getEffect(MobEffect)            | TODO
41 | EntityPlayer.setSpawnChunk(coords,forced,dim)  | serverPlayer.setRespawnPosition(dim,pos,...) | TODO
42 | EntityPlayer.wakeUpPlayer(a,b,c)               | serverPlayer.stopSleeping()                  | TODO
43 | EntityPlayer.DetonateCarriedBlastingOil()       | Clear inv, kill, explode at MC coords        | TODO (complex)
44 | EntityPlayer.dropHead()                         | Spawn ItemEntity with player skull           | TODO
45 | EntityPlayer.clonePlayer(other,keepAll)         | restoreFrom + clone FC data                  | TODO
46 | EntityPlayer.AttemptToPossessNearbyCreature()  | FC-only mechanic                             | TODO
47 | EntityPlayer.AddHarvestBlockExhaustion(...)    | Tool-specific exhaustion on block break      | TODO
48 | EntityPlayer.OnCantConsume()                    | Play eat fail aux SFX                        | TODO
49 | EntityPlayer.GetWornArmorWeight()               | Sum FC item GetWeightWhenWorn() on armor     | TODO
50 | EntityPlayer.IsWearingFullSuitSoulforgedArmor() | Check 4 armor slots against FC item IDs     | TODO
```

### 3.3 NBT Persistence Hooks (4 hooks)

```
# | Hook Point                                   | Implementation
──┼────────────────────────────────────────────────┼────────────────────────────────
51 | ServerPlayer.readAdditionalSaveData()         | @Inject TAIL: call ReadModDataFromNBT()
52 | ServerPlayer.addAdditionalSaveData()          | @Inject TAIL: call WriteModDataToNBT()
53 | FoodData read from NBT                        | @Inject TAIL: load FC food data, scale ×3 if !adjusted
54 | FoodData write to NBT                         | @Inject TAIL: save FC food data + adjusted flag
```

---

## CATALOGUE 4: BRIDGE CLASSES

### Full Bridge Classes (wrap real MC object)

```
# | Class              | Wraps                | Key Methods                              | Status
──┼────────────────────┼──────────────────────┼──────────────────────────────────────────┼────────
1  | PlayerBridge       | ServerPlayer         | All 50+ FC methods, per-tick sync,       | DONE
   |                    |                      | holds FoodStats, InventoryBridge, etc.   |
2  | WorldBridge        | ServerLevel          | Block get/set, entity spawn, sound,      | EXISTS
   |                    |                      | explosion, particles, FC systems         | (needs additions)
3  | InventoryBridge    | Inventory            | currentItem, armorInventory[],           | DONE
   |                    |                      | mainInventory[], getCurrentItem(),       |
   |                    |                      | hasItem(), getStrVsBlock(),              |
   |                    |                      | canHarvestBlock(), damageArmor(),         |
   |                    |                      | getTotalArmorValue(), getDamageVsEntity() |
4  | ItemStackBridge    | MC ItemStack         | itemID, stackSize, getItemDamage(),      | TODO
   |                    |                      | damageItem(), canHarvestBlock(world,     |
   |                    |                      | block,i,j,k), getStrVsBlock(world,       |
   |                    |                      | block,i,j,k), position-aware methods     |
5  | CapabilitiesBridge | Abilities            | disableDamage, isCreativeMode, isFlying, | DONE
   |                    |                      | allowFlying, allowEdit, getWalkSpeed(),  |
   |                    |                      | getFlySpeed()                            |
6  | DataWatcherBridge  | SynchedEntityData    | 10 FC entries (IDs 22-31),               | TODO
   |                    |                      | getWatchableObjectInt(),                 |
   |                    |                      | updateObject()                           |
```

### btw.modern Classes Needing FC Implementation (no wrap, standalone logic)

```
# | Class                        | What's Needed                                | Status
──┼──────────────────────────────┼──────────────────────────────────────────────┼────────
7  | btw.modern.FoodStats         | Complete FC food system (321 lines from      | DONE
   |                              | patched FoodStats.java)                      |
8  | btw.modern.DamageSource      | Map FC constants → 1.20.1 DamageSource       | DONE (DamageSourceMapping.java)
   |                              | registry (starve, onFire, lava, generic,     |
   |                              | causePlayerDamage, FCDamageSourceGloom)       |
9  | btw.modern.Potion            | Map FC IDs → MobEffects (hunger, nightVision,| DONE (PotionMapping.java)
   |                              | confusion, digSpeed, digSlowdown, blindness, |
   |                              | damageBoost, weakness)                       |
10 | btw.modern.EnchantmentHelper | Delegate to 1.20.1 enchantment system        | STUB
   |                              | (efficiency, respiration, knockback,         |
   |                              | fireAspect, looting, aquaAffinity, thorns)   |
11 | btw.modern.NBTTagCompound    | Wrap CompoundTag                             | EXISTS
12 | btw.modern.AxisAlignedBB     | Wrap AABB                                    | EXISTS
13 | btw.modern.Vec3              | Wrap Vec3                                    | EXISTS
14 | btw.modern.ChunkCoordinates  | Data class (x,y,z)                           | EXISTS
15 | btw.modern.Container         | Wrap AbstractContainerMenu                   | STUB
16 | btw.modern.Block             | Registry bridge + blocksList[]               | EXISTS (working)
17 | btw.modern.Item              | FC method implementations                    | EXISTS (partial)
18 | btw.modern.Scoreboard        | Wrap ServerScoreboard                        | STUB
```

### Mixin Target Classes (6 classes)

```
# | Target Class       | Purpose                                        | Status
──┼────────────────────┼────────────────────────────────────────────────┼────────
1  | ServerPlayer       | Primary: tick, hurt, die, food, exhaustion,    | DONE
   |                    | movement, NBT, container, respawn              |
2  | Player             | Block edit, mining speed, eating, jump exhaust, | DONE
   |                    | movement stats, destroy speed                  |
3  | LivingEntity       | Swimming, jumping, ladder, air supply,         | DONE
   |                    | movement modifiers                             |
4  | Entity             | Portal mods, fire tick mods                    | TODO
5  | FoodData           | Completely disable / redirect to FC FoodStats  | DONE
6  | ServerLevel        | ModSpecificTick hook, active chunk map         | TODO
```

---

## CRITICAL CONVERSION NOTES

### Food Level Scaling
- FC range: 0-60 (3× vanilla resolution)
- Vanilla range: 0-20
- Display to client: `displayPips = fcFoodLevel / 3`
- Import vanilla save: `fcFoodLevel = vanillaFoodLevel * 3`
- FC marks adjusted saves with NBT boolean `fcFoodLevelAdjusted`
- Network packets sending food level must scale: `fcLevel / 3` for vanilla client

### Health Type Mismatch
- MC 1.20.1: `float` health (0.0-20.0)
- FC: `int` health (0-20)
- MC→btw: `fcHealth = (int) serverPlayer.getHealth()`
- btw→MC: `serverPlayer.setHealth((float) fcHealth)`

### Damage Type Mismatch
- MC 1.20.1: `float` damage
- FC: `int` damage
- Cast at bridge boundary in both directions

### Sound System
- FC: string names ("random.drink", "random.eat", "random.classic_hurt", "random.breath")
- MC 1.20.1: `SoundEvent` registry entries
- Need mapping table: FC sound name → SoundEvent
- Custom FC sounds (burp, eat fail) need custom sound resource registration

### Block ID System
- FC: integer block IDs via `Block.blocksList[id]`
- MC 1.20.1: `BlockState` registry
- ProxyRegistry already handles this mapping — extend as needed

### FC Penalty Thresholds (for reference)

Fat penalty levels (from saturation):
```
Level 0: saturation < 12
Level 1: saturation 12-14
Level 2: saturation 14-16
Level 3: saturation 16-18
Level 4: saturation >= 18
```

Hunger penalty levels (from food level, FC 0-60 scale):
```
Level 0: food > 24
Level 1: food 18-24
Level 2: food 12-18
Level 3: food 6-12
Level 4: food 0-6
Level 5: food 0 AND saturation <= 0.01
```

Health penalty levels:
```
Level 0: health > 10
Level 1: health 8-10
Level 2: health 6-8
Level 3: health 4-6
Level 4: health 2-4
Level 5: health <= 2
```

---

## IMPLEMENTATION ORDER

### Phase 1: Core Infrastructure
1. btw.modern.FoodStats — full FC implementation from patched source
2. CapabilitiesBridge — simple 7-field wrapper
3. InventoryBridge — slot access wrapper
4. PlayerBridge — core player wrapper, holds FoodStats + bridges

### Phase 2: System Overrides
5. FoodData mixin — disable vanilla food, redirect to FC
6. ServerPlayer tick mixin — call UpdateModStatusVariables()
7. Mining speed mixin — apply GetMiningSpeedModifier()
8. Movement mixins — apply all 4 movement modifiers + jump/swim gating
9. Exhaustion mixins — armor weight modifier, custom rates

### Phase 3: Combat & Interaction
10. Combat mixins — melee damage modifier, blasting oil, blocked damage
11. Block placement mixin — mid-air restriction
12. Eating mixin — hunger potion block
13. Sleep mixin — disable beds
14. Air/drowning mixins — soulforged helm, gradual recovery

### Phase 4: Persistence & Sync
15. NBT persistence hooks — read/write FC player data
16. DataWatcherBridge — SynchedEntityData for penalty levels
17. DamageSource mapping
18. Potion/Enchantment mapping
19. Sound mapping

### Phase 5: World Integration
20. WorldBridge additions — FC-only systems (magnetic points, spawn locations, beacons)
21. ServerLevel mixin — ModSpecificTick
22. Spawn/respawn system — beacon-based spawn

### Phase 6: Remaining Event Hooks
23. Player action hooks (chat, GUI, XP, drops, clone)
24. ItemStackBridge — position-aware methods
25. Container bridge — FCContainerPlayer
