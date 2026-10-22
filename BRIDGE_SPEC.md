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
  `extendFcPathingBuildRange`, `extendFcTreeBuildRange`; passes inside `extractFcClasses` since 2026-10-19,
  no longer tasks) are its precursors and are expected to move into it, as are the load-time mixins on FC
  classes in `btw.forge.mixin.fcext` (2026-10-15, keyed on the plant-twin ids so FC's own blocks are
  untouched; 2026-10-20, the modern-woods patches M1-M9, keyed on log2's id 165 and wood types 5-8, see
  [Woods and logs (ModernWoods, FCLogRules)](#woods-and-logs-modernwoods-fclogrules)).

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
5. **Vanilla systems FC replaces are DISABLED** — food, mining speed, movement, etc. (beds sleep since
   2026-10-21: maintainer ruling 3, `INTEGRATIONS.md` #037).

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
  - placement validity (2026-10-21, `INTEGRATIONS.md` #040): `canPlaceEntityOnSide` is 4.B's rule (an entity
    check in which the placing player counts, a free or replaceable target, `canPlaceBlockOnSide`;
    WorldBridge.java:1920-1999); `checkNoEntityCollision` follows 1.5.2 (entities that block building, or FC's
    `preventEntitySpawning`: items and XP orbs no longer obstruct; :1877, :1896); a fully derived block never
    reads the metadata overlay (`getBlockMetadata`, :219-235); the tripwire hook codec (:533-542, :809-818).
    See [Placement validity (WorldBridge)](#placement-validity-worldbridge).
- `ProxyBlock` + `ProxyRegistry` — 148 FC blocks registered, vanilla block ID mapping
- `ForgeMinecraftServerWrapper` — wraps `MinecraftServer`
- `btw.modern.Block.blocksList[]` — 148 FC blocks + vanilla blocks with FC properties
- `btw.modern.Item.itemsList[]` — FC items registered
- `btw.modern.NBTTagCompound` — wraps `CompoundTag`
- `btw.modern.AxisAlignedBB` — wraps `AABB`
- `btw.modern.Vec3` — wraps `Vec3`
- `btw.modern.ChunkCoordinates` — data class
- Mixins (`Forge/src/main/resources/betterthanwolves.mixins.json`, updated 2026-10-21: 52 common and 2 client
  entries; the config's `plugin` is `btw.forge.compat.CompatMixinPlugin`, see
  [Mod compatibility (BTWCompat)](#mod-compatibility-btwcompat)):
  - block hooks: `BlockBehaviorMixin` (FC neighbour, place, remove, use, entity, signal and dig hooks;
    no tick routing since 2026-10-14; since 2026-10-21 the NOTIFY executors of hook ownership), `BlockMixin`;
  - hook ownership (`INTEGRATIONS.md` #036, see [Hook ownership (HookPolicy)](#hook-ownership-hookpolicy)):
    `BlockStateHookMixin` (the owners cached at initCache; FC-owned use and onPlace at the forwarders),
    `FenceGateBlockMixin` (4.B's neighbour notify after a gate toggle), `FluidInteractionRegistryMixin` (4.B's
    lava contact rule); `FarmBlockMixin` also carries farmland's trample threshold (ADAPTER(D9b));
  - tick ownership (see [Tick ownership (TickPolicy)](#tick-ownership-tickpolicy)):
    `BlockStateBaseTickMixin`, `BlockStateRandomTickMixin`, `LavaFluidMixin` (since 2026-10-21 it also hooks
    `spreadTo`, `m_6364_`: lava flowing down onto water makes the pillow, and `canBeReplacedWith`, `m_5486_`:
    no fluid displaces lava, for hook ownership, #036);
  - rule bridges for the ticks FC now owns: `FireBlockMixin`, `PlantSoilMixin`, `FarmBlockMixin`,
    `AttachedStemBlockMixin`;
  - post-1.5.2 plants (twins and paced owners, see the tick-ownership section):
    `fcext.FCBlockSaplingTwinMixin` and `fcext.FCBlockMushroomTwinMixin` (load-time, on FC classes),
    `BeeGrowCropGoalMixin`, `BeeAccessor`;
  - modern woods (`INTEGRATIONS.md` #034, see
    [Woods and logs (ModernWoods, FCLogRules)](#woods-and-logs-modernwoods-fclogrules)):
    `fcext.FCBlockLogWoodMixin`, `fcext.FCBlockWorkStumpWoodMixin`, `fcext.FCItemBarkWoodMixin`,
    `fcext.FCItemBlockLogWoodMixin`, `fcext.FCRecipesLogChoppingWoodMixin`, `fcext.FCBlockSawWoodMixin`,
    `fcext.FCBlockPlanksWoodMixin`, `fcext.FCBlockLogDamagedWoodMixin`, `fcext.FCItemChiselIronWoodMixin` (M1-M9;
    load-time, on FC classes, keyed on log2's id 165 or wood types 5-8);
  - bone meal (see [Bone meal (BoneMealPolicy)](#bone-meal-bonemealpolicy)): `BoneMealItemMixin`,
    `BonemealableGateMixin`;
  - stays removed (see [Stays removed (RemovedContent)](#stays-removed-removedcontent)):
    `StructureTemplateRemovedMixin`, `StructurePieceRemovedMixin`;
  - entities, players, items and the world: `LivingEntityMixin`, `PlayerMixin`, `ServerPlayerMixin` (since
    2026-10-21 also the bed rest check, #037), `ServerPlayerGameModeMixin` (since 2026-10-20 also the use order,
    #033, and the bed and door drops, #039; since 2026-10-21 placement validity, #040), `FoodDataMixin`,
    `ItemEntityMixin`, `ItemStackMixin`, `ServerLevelMixin`, `BonusChestFeatureMixin`;
  - beds and doors (`INTEGRATIONS.md` #039, see [Beds and doors (VanillaPairDrops)](#beds-and-doors-vanillapairdrops)):
    `BlockPlayerDestroyMixin` (FC's harvest exhaustion for the beds and doors 1.20.1 harvests);
  - modern-feature integrations (`INTEGRATIONS.md`): `MendingCapMixin`, `FrostWalkerRadiusMixin`,
    `RespawnAnchorChargeMixin`, `SwiftSneakLevelMixin`, `TotemOfUndyingMixin`, `CrossbowPiercingMixin`,
    `SweepingEdgeMixin`;
  - FC crop events (`INTEGRATIONS.md` #022): `fcext.FCBlockCropsDailyGrowthMixin`,
    `fcext.FCBlockHempCropMixin` (load-time, on FC classes; see the notes under the dispatch points);
  - live climate (`INTEGRATIONS.md` #028): `fcext.FCUtilsMiscColdBiomeMixin` (load-time, on
    `FCUtilsMisc.IsIKInColdBiome`);
  - held use (see [Held use (hold-to-craft)](#held-use-hold-to-craft)): `LivingEntityUseAccessor`;
  - optional mods, prepared only when the mod is installed: `compat.sereneseasons.SeasonalBonemealVetoMixin`
    (#028), `compat.lso.LsoRegenGateMixin` (#031);
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
  gamerules, config or world state. Since compat wave C2 two more inputs are fixed before any bake that
  matters: the modded sapling kinds an integration registers during mod construction
  (`TwinRegistry.registerModdedSapling`), and one block tag,
  `#betterthanwolves:paced_like_sweet_berries` (`Holder.is` answers false before tags bind, and
  `Blocks.rebuildCache` runs `decide` again after every bind, so the cached owner follows the tags in
  force). It runs at every registry bake and `Blocks.rebuildCache` (server start, /reload, client tag
  sync), and `LevelChunkSection` recounts ticking states from the cached flag when a chunk loads, so
  existing worlds need no migration. doFireTick is applied at dispatch time.
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
- ProxyBlock crops never reach row 3 (`decide` returns null for a ProxyBlock). Since compat wave C1
  (`INTEGRATIONS.md` #022) FC's own wheat (block_1089 and its top block_1090, FCBlockCropsDailyGrowth) and
  hemp (block_241, FCBlockHempCrop) post `CropGrowEvent.Pre` at FC's growth roll and `Post` after FC's
  growth step: the load-time mixins `fcext.FCBlockCropsDailyGrowthMixin` and `fcext.FCBlockHempCropMixin`
  (`@Pseudo`, `remap = false`) redirect the roll's `Random.nextFloat` in `AttemptToGrow` to
  `FcCropGrowthHooks.growthRoll`, which always draws FC's `nextFloat` first (the random stream is unchanged)
  and answers +Infinity for DENY, -Infinity for ALLOW. The roll, not the whole tick: FC's tick also resets
  the grown-today flag at night (Common FCBlockCropsDailyGrowth.java:41-49), which a whole-tick DENY would
  break. Only a live ProxyBlock state posts, so no growth attempt gets two Pre events. Serene Seasons' crop
  gate for BTW wheat and hemp listens here (#028). Open: the wheat top block_1090 gets no `Post` (hemp's
  top does).
- Row 3's order is runtime-proven (2026-10-18, `INTEGRATIONS.md` #016): with Serene Seasons 9.1.0.3 out of
  season, its veto held 16 of 16 FC carrots and 16 of 16 beetroot twins at age 0, while the same carrots
  under greenhouse glass grew in that window (ss_after I1, I2, I3).
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
before any world read, so saplings at y <= 0 never grew. The pass, run like `extendFcPathingBuildRange`
inside `extractFcClasses` (after `widenFcHookDescriptors` and `extendFcPathingBuildRange`, before
`openUpEntityAccess`; Forge/build.gradle:1584-1587; a task of its own until 2026-10-19, `STUB_CATALOGUE.md` (av)),
rewrites them against `World.getFcMinBuildHeight()` / `getFcMaxBuildHeight()` (0 / 256 in Modern-Common, the level's
range from WorldBridge):

```
FC method                     | Trees              | Base test                | Scan floor           | 256 tops
──────────────────────────────┼────────────────────┼──────────────────────────┼──────────────────────┼──────────────────────────────────────────
FCUtilsTrees.GenerateTaiga2   | spruce             | j < 1  ->  j - 1 < min   | y < 0  ->  y < min   | none (FC's own 128 kept)
FCUtilsTrees.GenerateForest   | birch              | j < 1  ->  j - 1 < min   | y < 0  ->  y < min   | none (FC's own 128 kept)
FCUtilsTrees.GenerateTrees    | oak, small jungle  | j < 1  ->  j - 1 < min   | y < 0  ->  y < min   | 3, each -> max
FCUtilsGenHugeTree.generate   | 2x2 jungle         | j < 1  ->  j - 1 < min   | y < 0  ->  y < min   | 3, each -> max
```

FC's own 128 top for spruce and birch is a 4.B rule measured from sea level and stays. The pass is
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
    the bottom (160 solid, 161 collider, then the plant stand-in `ModernPlantStandIn` when the pack has
    a modded crop (compat wave C1, #026), then the flammable ones), so three flammable stand-ins remain
    (162-164), or two with the plant stand-in: 165 is log2 since 2026-10-20 (BTWLifecycle step 5e, after
    the twins, which skip the mapped 165; see
    [Woods and logs (ModernWoods, FCLogRules)](#woods-and-logs-modernwoods-fclogrules)). Each twin comes from its
    FC class's own constructor (its ItemBlock at `itemsList[id]` is never registered: no loop below 175 does);
    `opaqueCubeLookup[id] = false`; `ModernTwins.markTwin(id)`. A kind that cannot be built leaves its plants on
    1.20.1 (logged, and the audit warns). The ids never reach a save: the overlay keys on the 1.20.1 block.
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
  - **Modded saplings and fungi** (compat waves C1 and C2, `INTEGRATIONS.md` #026, #027). Every other
    SaplingBlock with STAGE and no legacy id (another mod's sapling, for example HarvestCraft's 50 fruit-tree
    saplings) is a SAPLING twin of species 0, grown into its own tree by `SaplingBlock.advanceTree` (C1,
    maintainer decision D2 (b)). A sapling of another mod that is not a SaplingBlock joins through its
    integration (C2, the seam below): `isFallbackSapling` then accepts the block, and a kind's Nether fungus
    takes the FUNGUS twin (FCBlockMushroom), as vanilla crimson and warped fungi do. `growSaplingTwin` (a
    sapling twin whose FC stages are done) or `fungusTick` (after FC's 1-in-50 roll, never in the End) calls
    `growTree`; a sapling twin that did not grow keeps stage 3 and retries on its next 1/64 success, a fungus
    rolls again. The answer per block is cached (blocks never change after the registries freeze); a
    predicate that throws is skipped, logged once. Registration logs `[TWIN] modded sapling kind registered:
    <kind>`. Dynamic Trees is the only kind today (`DTSaplingTwin`, #027: DT's own tree through
    `Species.canSaplingGrow` and `transitionToTree`; a tree pack's `canSaplingGrowNaturally` false is
    honoured for saplings, not fungi; SeasonGate is asked by the species' seed item). The server-start line
    `[MODDED-PLANTS] ... modded-saplings-on-FC-rule=<n>` counts the blocks on this rule: 19 with Dynamic Trees
    alone, 114 with Dynamic Trees, HarvestCraft and dtphc2 (2026-10-18 production smoke).

```
public interface TwinRegistry.ModdedSapling {
    boolean isSapling(Block block);                 // pure, reads only the block; any thread, registry bakes
    default boolean isFungus(Block block) { return false; }   // a Nether fungus: FC's mushroom rule
    void growTree(ServerLevel level, BlockPos pos, BlockState state, RandomSource random);
}
public static void TwinRegistry.registerModdedSapling(ModdedSapling sapling)   // during mod construction
```

  - **Unripe loot** (compat wave C1, #026, settled decision D3 (b)). The global loot modifier
    `betterthanwolves:immature_crop` (`data/FCImmatureCropLootModifier.java`, file
    `immature_crops_drop_nothing.json`; GlobalLootModifierProvider writes the generated
    `global_loot_modifiers.json` in hash order, and the modifier needs none: it acts only on a CropBlock's own
    loot, which neither `removed_content` nor `leaves_drop_like_fc` changes) empties the loot of an unripe
    CropBlock with no FC id (modded crops, and the beetroot and torchflower twins; the pitcher crop is not a
    CropBlock) on soil that takes crops or on FC loose dirt: 4.B crops drop nothing until ripe (Server
    FCBlockCrops.java:55-64). Mature drops, and crops on other soils, keep 1.20.1 loot.
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
tagged modded fruit and pods:           | FCBlockCocoa 1/20 per stage; their own        | 0.25    | banned
#betterthanwolves:                      | random tick 1/5 (HarvestCraft, Dynamic        |         |
paced_like_sweet_berries (C2)           | Trees and dtphc2 fruit and pods)              |         |
```

- The tag row (compat wave C2, `INTEGRATIONS.md` #032; maintainer decision #7, one rule for all modded
  fruit): `TwinRegistry.PACED_LIKE_SWEET_BERRIES`, data written by `HarvestCraftDataProvider` (95 ids, each
  `required: false`: the 50 HarvestCraft tree fruit, Dynamic Trees' apple and cocoa, the 43 dtphc2 fruit and
  pods). It applies only to an unmapped block that is neither a ProxyBlock nor a twin. Those plants' own
  random tick grows a stage at 1/5 (DT's default growth chance 0.2, DT Fruit.java:84, Pod.java:119), so the
  pace is (1/20) / (1/5) = 0.25. Checked 2026-10-17: DT apples, DT cocoa pods and dtphc2 apricots grow at the
  paced rate and not in the End (dt_p8 F1-F4). The self-audit's paced count (173) covers the vanilla table
  only; it is unchanged with every optional mod installed.

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
`mixin/ServerPlayerGameModeMixin.java` (the item step `btw$fcItemStep` skips bone meal; it replaced the early
return on 2026-10-20, `INTEGRATIONS.md` #033), `ProxyBlock.tryPlaceHeldItem` (Stage 2), Modern-Common
`BlockFlower.java` (the FCBlockPlants relay and weed pull) and `Item.java` (the block-dispenser default).
Extended by the compat waves (`INTEGRATIONS.md` #026, #027, #028): `farming/FcSoil.java` (the positional
route), `ModernBlockHooks.java` (hooked modern blocks),
`mixin/compat/sereneseasons/SeasonalBonemealVetoMixin.java`.

**The rule.** Bone meal is FC fertilizer and nothing else. Its only effect on a block is 4.B's
`FCItemDye.ApplyBoneMeal`: the clicked block's `AttemptToApplyFertilizerTo`, which plants relay to their
soil (FCBlockPlants) and FC farmland and planters take once. The item is used up exactly when that
returns true. On a block the rule owns, 1.20.1's `BonemealableBlock.performBonemeal` and
`BoneMealItem.growWaterPlant` never run and levelEvent 1505 (particles, sound) never plays. Only
`minecraft:bone_meal` is fertilizer (`isFertilizer`); 1.20.1 white dye is FC 351:271
(`LegacyVariants.MODERN_SPELLING`, since 2026-10-20), so it is never fertilizer, on any path.

**Routes** (`BoneMealPolicy.route(state)`, pure; VANILLA before FC is ready). Since the compat waves every
caller asks the positional `route(level, pos, state)`, which tries `route(state)` first and adds the last
SOIL_RELAY row; rows are checked in this order:

```
Route       | When                                                     | Effect
────────────┼──────────────────────────────────────────────────────────┼──────────────────────────────────────────
FC_TARGET   | ProxyRegistry.getBlockId != 0: every FC-mapped block,    | blocksList[id].AttemptToApplyFertilizerTo
            | vanilla ids and ProxyBlocks (grass block, netherrack,    | at the clicked cell (the block's own 4.B
            | sand and cocoa included: 4.B's "nothing")                | rule, relays included)
SOIL_RELAY  | TickPolicy.isFcGrowthGoverned: twin and paced            | FCBlockPlants' relay: the soil below the
            | post-1.5.2 plants (the tagged modded fruit included),    | plant's lowest cell (a multi-cell plant
            | bodies following their head                              | relays through its own cells)
FC_TARGET   | ModernBlockHooks.find(state) != null: a modern block an  | the stand-in's AttemptToApplyFertilizerTo
            | integration registered FC machine hooks for (Dynamic     | asks the hook (Dynamic Trees: +2 rooty
            | Trees branches and roots, rooty soil, trunk shells, and  | fertility, nothing at 15; pods and fruit
            | pods and fruit outside the paced tag; C2, #027; DT's and | take none, as FC cocoa)
            | dtphc2's tagged pods and fruit take the SOIL_RELAY row   |
            | above)                                                   |
SOIL_RELAY  | positional only (route(level, pos, state)): a modded     | the same relay: FC farmland and planters
            | CropBlock, sapling or #betterthanwolves:fc_soil_plants   | take it; FC grass, dirt and sand decline,
            | member (FcSoil.governedKind) on BTW soil that takes it   | so the item is kept and the plant does
            | (FcSoil.governsAt) or on any other FC-mapped ground      | not grow (an LSO fern crop on grass)
            | (C1, C2, #026)                                           |
VANILLA     | everything else (moss, nylium, pink petals, seagrass,    | 1.20.1 bone meal
            | modded plants on modern or modded soil, ...)             |
```

**Paths** (Forge 47.3.0; the refmap carries the SRG names):

```
# | Path                          | Target (SRG)                              | Hook                      | Does
──┼───────────────────────────────┼───────────────────────────────────────────┼───────────────────────────┼──────────────────────────────
1 | player use, not sneaking,     | ServerPlayerGameMode.useItemOn m_7179_:   | ServerPlayerGameModeMixin | 4.B's order, no HEAD pre-
  | main hand                     | the item step, INVOKE ItemStack.useOn     | .btw$fcItemStep skips it  | emption (since 2026-10-20):
  |                               | m_41661_ (require = 2)                    | (isFcBoneMeal)            | RightClickBlock, the spectator
  |                               |                                           |                           | check, then blockstate.use:
  |                               |                                           |                           | Stage 1 onBlockActivated (weed
  |                               |                                           |                           | pull), Stage 2 FCItemDye (main
  |                               |                                           |                           | hand, refused under DENY); the
  |                               |                                           |                           | item step leaves bone meal to
  |                               |                                           |                           | BoneMealItem.useOn (row 2)
2 | sneaking, off hand, targets   | BoneMealItem.useOn m_6225_, HEAD,         | BoneMealItemMixin         | route VANILLA: 1.20.1. Server:
  | without Stage 2, any useOn    | cancellable                               | (onBoneMealUseOn)         | fertilize, shrink 1, CONSUME,
  | caller                        |                                           |                           | else PASS. Client: predict the
  |                               |                                           |                           | swing, SUCCESS / PASS
3 | other applyBonemeal callers   | BonemealEvent (Forge-added, no SRG), LOW, | BoneMealPolicy.onBonemeal | ALLOW when FC took it (Forge
  | (mods, fake players, growCrop)| DEFAULT results only                      |                           | uses one), else cancel; the
  |                               |                                           |                           | client always cancels
4 | direct performBonemeal /      | isValidBonemealTarget m_7370_ and         | BonemealableGateMixin, on | false / cancelled when
  | isValidBonemealTarget callers | performBonemeal m_214148_, HEAD           | the 31 classes declaring  | governsGrowth(level, pos,
  |                               |                                           | them (modded CropBlock /  | state): the positional route
  |                               |                                           | SaplingBlock subclasses   | is not VANILLA
  |                               |                                           | inherit it)               |
5 | vanilla dispenser             | DispenserBlock.registerBehavior m_52672_  | BoneMealPolicy.Setup      | DefaultDispenseItemBehavior
  |                               | (a call at FMLCommonSetupEvent,           |                           | for Items.BONE_MEAL: eject,
  |                               | enqueueWork; a put into the registry)     |                           | aux 1000 and 2000
6 | FC block dispenser            | Modern-Common Item                        | port code                 | 1.5.2 default: SpitOutItem
  |                               | .OnItemUsedByBlockDispenser               |                           | inlined, aux 1000, true
7 | Stage 2 with white dye        | ProxyBlock.tryPlaceHeldItem               | BoneMealPolicy            | white dye is FC 351:271 since
  |                               |                                           | .mayUseAsFcItem           | 2026-10-20: the guard lets it
  |                               |                                           |                           | through, and FCItemDye acts
  |                               |                                           |                           | only at damage 15 (Client
  |                               |                                           |                           | FCItemDye.java:36): nothing
  |                               |                                           |                           | happens
8 | Serene Seasons' bone-meal     | SeasonalCropGrowthHandler.applyBonemeal(  | SeasonalBonemealVetoMixin | returns before the veto when
  | veto (a GlitchCore UseBlock   | UseBlock), HEAD, static, remap = false,   | (@Pseudo, require = 0;    | the stack is bone meal and the
  | listener fed from Forge's     | no refmap entry                           | prepared only with Serene | positional route is not
  | RightClickBlock, NORMAL)      |                                           | Seasons; C2)              | VANILLA
```

- Row 1 restores 4.B's `activateBlockOrUseItem` order (vanilla/server ItemInWorldManager.java:376-407)
  for bone meal: weeds are pulled first, claim mods and Serene Seasons' bone-meal veto see the
  `RightClickBlock` event, and a spectator cannot fertilize. Since 2026-10-20 every FC item follows that order
  (`INTEGRATIONS.md` #033, see [Use order (FC item step and Stage 2)](#use-order-fc-item-step-and-stage-2)), and the
  HEAD pre-emption the 2026-10-15 early return carved bone meal out of is gone (`STUB_CATALOGUE.md` (at) caveat
  BM8). Bone meal differs only at the item step, which skips it (ServerPlayerGameModeMixin.java:307-315), so
  `BoneMealItem.useOn` (row 2) stays its one route for sneaking, the off hand and targets without Stage 2.
- Modern-Common `BlockFlower` carries FCBlockPlants' `AttemptToApplyFertilizerTo` (Client
  FCBlockPlants.java:129-143) and `onBlockActivated` (the weed pull, FX 2001, :84-102). Without the
  relay FCItemDye returned false for the whole 1.5.2 flower family and 1.20.1's growth ran.
- Client: `BoneMealPrediction` (`@OnlyIn(Dist.CLIENT)`, reached through `DistExecutor`, render thread)
  runs `applyFertilizer` on `ClientFcWorld`, whose writes are no-ops, so it answers 4.B's boolean for
  the swing without changing anything. The client never shrinks the stack (as `BoneMealItem.useOn` does
  not); the server's slot update does, and the soil's new state arrives through the normal block update.
- Creative: `ServerPlayerGameMode.useItemOn` restores the count around `useOn`, and FC's own item use (Stage 2
  and the item step) gets 1.5.2's restore in `ProxyBlock.runFcItemUse` (ProxyBlock.java:778-811, inside an
  INV-RESYNC shadow scope), as 4.B did.
- An FC failure inside `AttemptToApplyFertilizerTo` is logged once and counts as declined (the use
  packet handler has no catch above it).
- Dropped bone meal keeps FC's own item rule (`FCBlockFarmlandBase` / `FCBlockPlanterBase
  .onEntityCollidedWithBlock` through the item bridges). Dropped white dye is FC 351:271 since 2026-10-20, so
  that rule no longer takes it for bone meal (`STUB_CATALOGUE.md` (at) WD5, fixed by (aw)).
- Modded plants (compat waves C1 and C2, `INTEGRATIONS.md` #026) use the extension point this section
  planned: the positional `route(level, pos, state)` adds the FcSoil row, and `onBoneMealUseOn`, `onBonemeal`
  and BonemealableGateMixin ask it; `relayToSoil` and the one `BonemealEvent` listener are reused. No
  integration adds a second listener. On the client the soil is read on the render thread's read-only FC
  world, so the swing prediction agrees with the server.
- Dynamic Trees (#027): its `RightClickBlock` handler denies the block use of a branch, rooty soil or trunk
  shell to bone meal on the server (DT's growth pulse never runs), so `BoneMealItem.useOn` reaches row 2 and
  the hooked state's FC_TARGET; on the client it predicts the swing from the root's fertility.
- Serene Seasons (#028, row 8): the 2026-10-15 early return (row 1) put bone meal behind Forge's
  `RightClickBlock`, where Serene Seasons' veto ate the bone meal, or broke the plant, on its out-of-season
  tagged FC-owned plants before FC's rule ran. The mixin makes the veto stand aside on every owned route; it
  keeps its veto on VANILLA routes, and its random-tick veto still gates growth. Without Serene Seasons, or if
  it renames the method, nothing changes, so proof H3 is its only runtime check (2026-10-18: bone meal on an
  out-of-season tagged FC crop fertilizes the soil to block_1088).
- HarvestCraft dung (#032) is not bone meal: `DungSpreading` runs the tagged block's own bone-meal growth
  for BTW dung on `#betterthanwolves:dung_spreads`. BonemealableGateMixin gates only the 31 vanilla classes,
  so it does not gate that call.

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
- Ingredient successors (RetiredIngredientWidening, `RemovedContent.ingredientSuccessors`; any tier since
  compat wave C1, maintainer decision D-B (a)): FC's legacy conversion target of each T2 id
  (FCRecipes.java:5080-5102); the torch stands for the nether-coal torch, lit (1057) or unlit (1033),
  never the finite torch, which would become a permanent light. The furnace (T3) takes the brick oven that
  replaced it (FCRecipes.java:349-353); the tripwire hook (T1) takes one FC iron nugget, FC's own value for
  a hook (the stoked crucible, FCRecipes.java:4480-4485). Only the ingredient role is substituted: every
  removed id stays removed as a result.

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
   headless id. Since compat wave C1 a record that matches nothing by ingredients but whose result FC's
   own list makes (the 6-stick fence, FCRecipes.java:5651, against FC's fences at :4987-4996) removes
   every `minecraft:` crafting recipe of that result instead, counted "result-only": the FC workbench now
   falls back to the RecipeManager, so a surviving `minecraft:oak_fence` would bypass FC's route.
2. Special recipes: every `MAP_EXTENDING` and `REPAIR_ITEM` recipe (vanilla/server
   CraftingManager.java:49-51, 283-382).
3. Cooking: the inputs FC took out of the 1.5.2 furnace table (vanilla/server FurnaceRecipes.java:37-55,
   compared with FC's live list; clay keeps its brick); every `minecraft:` smelting, blasting, smoking
   and campfire recipe whose ingredient maps to one.
4. Stays removed: every recipe of any type and namespace whose result is a table item; foreign
   namespaces are logged at INFO. FC's injected `fc_*` recipes are never removed; one that makes a table
   item is reported as a WARN.

**Widening** (`RetiredIngredientWidening.apply`, after the removals): a recipe outside
`betterthanwolves` whose ingredient accepts a removed id that has ingredient successors also accepts them
(`Ingredient.merge` for a vanilla ingredient, Forge `CompoundIngredient` otherwise). Only ShapedRecipe,
ShapelessRecipe, SmeltingRecipe, BlastingRecipe, SmokingRecipe, CampfireCookingRecipe and
StonecutterRecipe are rebuilt, with the same id, result, group, category, pattern and timings; other
classes are named in one log line. A recipe whose result FC makes its own way is not widened (FC's route
wins). A recipe that still needs, in one ingredient, only removed ids without a successor is reported once
as stranded. Since D-B none is stranded in 1.20.1 itself (blast furnace, smoker and furnace minecart take
the brick oven, the crossbow an FC iron nugget), so that line names modded recipes only.

**Audit** (`RemovedContent.audit`): every FC block of class FCBlockStub, FCBlockVanillaHopper,
FCBlockDaylightDetector or FCBlockTripWireSource and every FCItemStub must be a T1 row (class names, not
a null-tab test); every row's blocks and item must map to the cited 1.5.2 ids; every T2 successor and
ingredient successor must resolve.

```
[REMOVED-AUDIT] stays-removed table: <n> T1, <n> T2, <n> T3 rows; FC stub blocks [...] and items [...] covered; <n> warning(s)
FC vanilla-recipe removals on: <m>/<n> records matched (<n> count-insensitive, <n> result-only, <n> unmatched),
  <n> crafting + <n> special + <n> cooking recipes removed (removed furnace inputs [...]); stays-removed rule
  removed <n> more (<n> outside minecraft); <n> recipes removed in total             (logger BTW-RecipeRemovals)
Removed <recipe id> by result only: FC removal <record> has no 1.20.1 counterpart and FC's own recipes make
  <id>:<meta>                                                                       (logger BTW-RecipeRemovals)
Widened <n> recipes whose removed-id ingredients now also accept the BTW successor (...); not widened because
  FC makes the result its own way: [...]; not rebuildable: [...]                     (logger BTW-RecipeWidening)
Stranded by a removed ingredient that has no BTW ingredient successor, ...: [...]    (logger BTW-RecipeWidening)
```

2026-10-15 values: `12 T1, 8 T2, 8 T3 rows; FC stub blocks [154, 149, 150, 151, 158, 146, 147, 148, 157,
131, 132] and items [404, 407, 408] covered; 0 warning(s)`; `93/94 records matched (3 count-insensitive,
1 unmatched), 108 crafting + 2 special + 20 cooking recipes removed ... 130 recipes removed in total`
(the unmatched record is FC's 6-stick fence); 13 recipes widened; stranded: blast_furnace, smoker,
furnace_minecart, crossbow.

2026-10-18 values (production smoke, BTW alone): `94/94 records matched (3 count-insensitive, 1 result-only,
0 unmatched), 109 crafting + 2 special + 20 cooking recipes removed ... 131 recipes removed in total`, with
`Removed minecraft:oak_fence by result only`; 17 recipes widened (19 with every optional mod of
`INTEGRATIONS.md` #027-#032 installed); no stranded line; `not widened because FC makes the result its own
way: [minecraft:chest_minecart, minecraft:nether_brick_fence, minecraft:nether_bricks]`.

**Not covered, by the ruling's scope:** removed blocks already in a world or placed by commands (left
alone; `/give` and `/setblock` stay op tools, as in 4.B); modern-only blocks 4.B never had; the
remaining CT6 rows (records other than 13, enchanted books, wheat seeds and legacy wheat, glowing
redstone ore, the BlockStem / BlockNetherStalk / BlockRedstoneTorch stand-ins).

---

## Recipe bridge (RecipeBridge, WorkbenchFallback)

Added in compat wave C1 (2026-10-15, committed as 3985147; maintainer decision D-B (a): the FC crafting
fallback covers every recipe, FC first; `INTEGRATIONS.md` #025). Code:
`Forge/src/main/java/btw/forge/RecipeBridge.java`, `recipes/WorkbenchFallback.java`,
`recipes/FcCraftingManagers.java`, `recipes/RecipeReplacements.java`, `recipes/TagMachineRecipes.java`,
`recipes/ModernCookingImport.java`, `recipes/RawCraftedFoods.java`, Modern-Common `CraftingManager.java`
(`ExternalResolver`, `ExternalCraft`) and `SlotCrafting.java` (`onPickupExternal`).

**The rule.** FC's crafting grids (the FC workbench, the work stump and the soulforge) ask FC's own list
first, exactly as in 1.5.2; the soulforge then asks FC's anvil list. Only when both find nothing does the
1.20.1 RecipeManager answer, with every crafting recipe except FC's own injected copies (`fc_recipe_*`).
Before C1, 499 of 822 vanilla crafting recipes (every one that needs 3x3) and every modded 3x3 recipe could
not be crafted anywhere. 4.B's removals reach the RecipeManager first (see Stays removed), so the fallback
offers nothing 4.B removed, and it refuses a result that is a removed id
(`RemovedContent.isRemovedRecipeResult`). Where 1.20.1 and 1.5.2 recipes differ, 1.20.1's applies (the golden
apple costs 8 ingots).

**Steps** (`RecipeBridge.injectRecipes`, at ServerStarting after the overworld's `LevelEvent.Load` restored the
world's headless ids, and again after every /reload through `OnDatapackSyncEvent`, never earlier: steps 7 and
8 hand out headless ids; each step has its own try/catch that logs one ERROR and leaves the recipes as they
were):

```
#  | Step                          | Code                                    | Does
───┼───────────────────────────────┼─────────────────────────────────────────┼─────────────────────────────────────────
1  | clear the previous imports    | ModernCookingImport.clear,              | FC's static tables keep no stale
   |                               | TagMachineRecipes.clear                 | headless id
2  | FC's recipes                  | injectCraftingRecipes,                  | fc_recipe_<index>, fc_smelting_<id>
   |                               | injectSmeltingRecipes                   |
3  | replacements by id            | RecipeReplacements.applyTo              | btw_recipe_replacements (below)
4  | 4.B removals                  | FcRecipeRemovals.apply                  | see Stays removed
5  | widening                      | RetiredIngredientWidening.apply         | see Stays removed
6  | install                       | RecipeManager.replaceRecipes            |
7  | cooking import                | ModernCookingImport.apply               | edible unmapped smelting and campfire
   |                               |                                         | inputs join FC's furnace and campfire
8  | millstone tag recipes         | TagMachineRecipes.apply                 | btw_machine_recipes (below)
9  | raw-crafted foods             | RawCraftedFoods.apply                   | crafted foods made from poisonous raw
   |                               |                                         | items take FC food poisoning (#024)
10 | FC grid fallback              | CraftingManager.setExternalResolver(    | behind MIRROR_FC_VANILLA_REMOVALS
   |                               | WorkbenchFallback.INSTANCE)             | (true): one switch reverts the 4.B
   |                               |                                         | removals, the fence rule and the
   |                               |                                         | fallback together
```

At ServerStopped the resolver is removed and every import cleared, so the next world in the JVM starts clean.

```
[BTW-Recipes] <n> recipes installed: FC <n> crafting + <n> smelting; replacements <n> replaced, <n> removed;
  4.B removals <n> recipes; widened <n>; cooking import <n> furnace + <n> campfire; millstone tag recipes <n>;
  raw-crafted foods <n>; FC workbench fallback on|OFF                                 (logger BTW-RecipeBridge)
```

2026-10-18 values (production smoke). BTW alone: `1735 recipes installed: FC 666 crafting + 23 smelting;
replacements 0 replaced, 0 removed; 4.B removals 131 recipes; widened 17; cooking import 4 furnace + 4 campfire;
millstone tag recipes 0; raw-crafted foods 0; FC workbench fallback on`. With every optional mod: `3327 recipes
installed: ...; replacements 5 replaced, 1 removed; 4.B removals 131 recipes; widened 19; cooking import 48
furnace + 48 campfire; millstone tag recipes 40; raw-crafted foods 181; FC workbench fallback on`.

**ExternalResolver and ExternalCraft** (Modern-Common `CraftingManager`, port code, not FC code):

```
public interface ExternalResolver {
    ExternalCraft resolve(InventoryCrafting inv, World world);   // null: no match; never asked when FC's list answers
}
public interface ExternalCraft {
    ItemStack result();                                            // shown in the result slot (a copy)
    ItemStack[] remainders(InventoryCrafting inv, EntityPlayer player);   // per grid slot, at take time
    void onTaken(InventoryCrafting inv, EntityPlayer player, ItemStack taken);   // the recipe system's hooks
}
CraftingManager.setExternalResolver(ExternalResolver r)   // null removes it and drops every pending craft
CraftingManager.takeExternalCraft(IInventory inv)         // the pending craft behind inv's result, or null
```

- `findMatchingRecipe(inv, world)` runs `FindMatchingRecipe` (FC's list) first and asks the resolver only on no
  match. The craft is remembered per grid (weak keys) until the result is taken or the next
  `findMatchingRecipe` call for that grid replaces it. A resolver that throws logs one ERROR
  (`[CRAFTING] External recipe resolver failed; the grid shows no result (logged once)`, logger BTW-Crafting).
- `SlotCrafting.onPickupFromSlot` takes the pending craft before the first `decrStackSize` (which re-enters
  `onCraftMatrixChanged`) and runs `onPickupExternal` in the order of 1.20.1 `ResultSlot.onTake`: the crafted
  hooks first (`onTaken`, so ItemCraftedEvent listeners see the full grid), then `remainders` (with the taking
  player known), then one of each ingredient is consumed and its remainder placed. FC's per-item crafting rules
  (`OnUsedInCrafting`, `IsConsumedInCrafting`, damage in crafting, container items) belong to FC's own recipes
  and do not run: an external recipe's remainders are its own (a honey bottle leaves a glass bottle), as in the
  2x2 grid.
- `WorkbenchFallback` is the only resolver. Server only: the world must be a WorldBridge with a ServerLevel (the
  client's FC containers mirror the server's slots). Candidates: `getAllRecipesFor(CRAFTING)` minus FC's copies,
  sorted by id (`RecipeManager.getRecipeFor` is findFirst over a HashMap, so not deterministic), cached per
  RecipeManager and generation (`onRecipesReplaced`: injection, /reload, server stop). A grid cell with no
  1.20.1 form refuses the whole grid (dropping it would let a smaller recipe match); a recipe that throws is
  skipped, one WARN per recipe id. The soulforge (grid width 4) asks FC's anvil list second
  (`FCContainerSoulforge.onCraftMatrixChanged`); the fallback runs inside the first call, so it steps aside
  whenever `FcCraftingManagers.anvilResult` answers. `onTaken` runs `ItemStack.onCraftedBy`, Forge's
  ItemCraftedEvent (`firePlayerCraftingEvent`, with the grid before consumption), `triggerRecipeCrafted` and,
  for a non-special recipe, `awardRecipes`; `remainders` runs `getRemainingItems` with
  `ForgeHooks.setCraftingPlayer` set to the taker. FC's own `onCreated` already ran through
  `SlotCrafting.onCrafting`, a no-op on the server under FC's isRemote guard, so nothing runs twice.

**RecipeBridge.legacyAlias(Item).** An FC cell holds only the 1.5.2 (id, damage), so an item 1.20.1 split from
one 1.5.2 subtype reads back as the canonical item `ItemStackHelper.toMcStack` picks. `legacyAlias(canonical)`
answers the 1.20.1 item that 1.20.1 recipes name for the canonical item's 1.5.2 subtype, or null; one way
(RecipeBridge.java:289). Since 2026-10-20 the map is built from `LegacyVariants.baseSubtype` (RecipeBridge.java:300-308):
an item's legacy damage without the `MODERN_SPELLING` bit names the canonical item it stands beside. So:
- the 4.B dye items stand in for the 1.20.1 dyes, one way: the ink sac, cocoa beans, lapis and bone meal (1.5.2 dye
  0, 3, 4 and 15) alias the black, brown, blue and white dye. The dyes are no longer same-subtype spellings: each
  has its own FC stack, 351:256/259/260/271, and reads back as itself (see
  [Dyes (LegacyVariants spellings, ModernSpellingRecipes)](#dyes-legacyvariants-spellings-modernspellingrecipes));
- the stone slab (for the smooth stone slab, 44:0) and smooth sandstone (for cut sandstone, 24:2) remain
  same-subtype spellings: FC reads them as that subtype, and they read back as the canonical item.

The map is built once, after FC is ready (never cached earlier), in registry order, first entry wins; it never
allocates a headless id. Lossy collapses (every modern flower onto the rose, the 16 bed colours onto one bed)
are deliberately not aliases: those items are not the same 1.5.2 thing (at FC's grids and in FC storage only: a
broken bed drops its own colour since 2026-10-20, see
[Beds and doors (VanillaPairDrops)](#beds-and-doors-vanillapairdrops)). WorkbenchFallback tries the canonical grid
first and, only when nothing matches and a cell has an alias, once more with every aliased cell swapped (count and
tag kept); the craft remembers which grid matched, so its remainders and hooks see that grid. Without it no FC grid
matched a recipe that names one of those items (black candles and stained glass, the grindstone, ...; C1 review
finding, fixed in the wave).

**btw_recipe_replacements** (`data/<ns>/btw_recipe_replacements/*.json`; a file whose path inside the folder
starts with `_` is skipped):

```
{ "replaces": "<recipe id>",
  "conditions": [ ...Forge conditions, optional... ],     // false: the entry is skipped (DEBUG)
  "action": "replace" | "remove",
  "recipe": { ...a normal recipe JSON... },               // replace only
  "reason": "<text>" }                                    // required for remove
```

- `replace`: the recipe JSON is parsed with `RecipeManager.fromJson` under the ORIGINAL id. Every string inside
  it that is exactly `btw:<FCBetterThanWolves field>` becomes that FC object's item id (`FcRefs.itemKey`). An
  unresolved placeholder or a parse error logs a WARN and the original recipe stays.
- `remove`: needs a non-empty `reason`; without one the entry is rejected with a WARN and the recipe stays.
- Entries apply in file-id order; the first applied entry per target id wins, a later one logs a WARN. An
  absent target is skipped (DEBUG): replacements never add recipes.
- Applied by id inside `injectRecipes` (step 3), after every pack has loaded and before 4.B's removals and the
  widening, so pack order and mods added to an existing world do not matter, and the stays-removed rule also
  governs replacement recipes.
- Log: `Recipe replacements: <n> replaced, <n> removed, <n> skipped (<n> file(s))` (logger BTW-RecipeBridge).
  BTW ships six generated HarvestCraft files (`btw_recipe_replacements/harvestcraft/`: bread_dough,
  bread_grain, doughitem_x2, flour_x1_flour_plants, string_fiber, torch_cattail), each behind
  `forge:mod_loaded`: `0 replaced, 0 removed, 6 skipped (6 file(s))` without HarvestCraft, `5 replaced, 1
  removed, 0 skipped (6 file(s))` with it.

**btw_machine_recipes** (`data/<ns>/btw_machine_recipes/*.json`):

```
{ "machine": "millstone",                                 // the only machine today
  "input_tag": "<item tag>",
  "output": { "fc_item": "<FCBetterThanWolves field>", "count": n },
  "conditions": [ ...Forge conditions, optional... ] }
```

- Every member of the tag that has no 1.5.2 or FC id gets the millstone recipe `member -> count x fc_item`.
  Mapped members keep FC's own recipes (the dandelion still mills to two yellow dye, FCRecipes.java:4749; wheat
  to flour, :4754), and an input the millstone already takes never gets a second recipe. An unknown machine, a
  missing `input_tag` or an `fc_item` that names no FC item or block logs a WARN and the entry is skipped.
- Applied only from `injectRecipes` (step 8), never from TagsUpdatedEvent: the first tag load comes before the
  overworld's `LevelEvent.Load` restores the headless ids, and `ProxyRegistry.restoreHeadlessIds` evicts ids
  handed out before it. `clear()` removes every added recipe (re-injection, server stop).
- Log: `Tag machine recipes: <n> millstone recipe(s) added from <n> file(s)`. BTW ships two generated files,
  `harvestcraft/flour_plants.json` (`#forge:flour_plants` to 1 BTW flour) and `harvestcraft/fiber.json`
  (`#forge:fiber` to 2 hemp fibres); both are inert while their tag is empty: 0 recipes from 2 files without
  HarvestCraft, 40 with it.

**Caveats** (C1 review findings, open):
- A replacement may name FC's own index-based `fc_recipe_<index>` or `fc_smelting_<id>`; that changes only the
  RecipeManager copy (the 2x2 grid and JEI), not FC's own list, which the FC grids use.
- The candidate cache keys on the RecipeManager's identity and the generation: a mod that calls
  `replaceRecipes` on the same manager after BTW's injection is seen only after the next /reload or injection.
- `onCraftedBy` and ItemCraftedEvent run on a converted copy of the taken stack, so a listener's change to the
  crafted stack (an owner tag, for example) does not reach the stack the player receives. Vanilla results are
  unaffected.
- Items LegacyVariants collapses onto one 1.5.2 subtype stay one item at an FC grid: the dye recipes of the
  modern flowers and the flower banner pattern see a poppy, and dyed beds come back as the red bed (a broken
  bed keeps its colour since 2026-10-20: `INTEGRATIONS.md` #039).
- The cooking import takes every edible unmapped smelting input, so FC's campfire spit also accepts chorus
  fruit.

---

## Food values (BtwFoodTable, FcFoodValues)

Added in compat wave C1 (`INTEGRATIONS.md` #024); namespace tiers added in compat wave C2 (#032). Code:
`food/FcFoodValues.java`, `food/BtwFoodTable.java`, `food/BtwFoodEvents.java`, `food/ModernFoodItem.java`,
`food/ModernFodderItem.java`, `BTWNetwork.FoodTableSync`; data `data/betterthanwolves/btw_foods/minecraft.json`
and the generated `btw_foods/harvestcraft/*.json`.

**Resolution** (`FcFoodValues.of(ItemStack, @Nullable LivingEntity)`: never throws, never allocates a headless
id, never calls a stack-sensitive `getFoodProperties` on a LegacyProxyItem), first match wins:

```
Source           | When                                          | Value
─────────────────┼───────────────────────────────────────────────┼─────────────────────────────────────────
FC_ITEM          | the stack's FC item is a 1.5.2 ItemFood       | FC's GetHungerRestored, fat multiplier,
                 |                                               | flags and onFoodEaten effects
FC_DRINKABLE     | the FC item is an FCItemBucketDrinkable       | milk 6, chocolate milk 9, fat 0.25
TABLE            | a btw_foods entry with a hunger value         | hunger x 3, the entry's fat and poison
MODERN_CONVERTED | any other edible item that is not a ProxyItem | 1.20.1 nutrition x 3, the saturation
                 |                                               | modifier as fat, effects mapped back to
                 |                                               | FC potion ids 1-20
```

AppleSkin and the LSO datagen read it directly; every other mod sees it through `ProxyItem.getFoodProperties`
(`FcFoodValues.toFoodProperties`, which never sets `fast()`). Start-up line: `[BTW-Food] sources: FC_ITEM <n>,
FC_DRINKABLE <n>, TABLE <n>, MODERN_CONVERTED <n>, capped <n>`; 2026-10-18 production smoke, BTW alone:
`FC_ITEM 63, FC_DRINKABLE 2, TABLE 17, MODERN_CONVERTED 0, capped 36`; with every optional mod: `FC_ITEM 63,
FC_DRINKABLE 2, TABLE 1304, MODERN_CONVERTED 1, capped 1324`.

**btw_foods** (`data/<ns>/btw_foods/*.json`):

```
{ "conditions": [ ...Forge conditions, optional... ],     // false: the whole file is skipped
  "entries": {
    "<item id>" | "#<item tag>": {
      "hunger": int,          // 1.5.2 units (one pip = 2 half-shanks); FC feeds x 3
      "fat": float,           // FC fat multiplier, default 0
      "poison": { "chance": float, "seconds": int, "amplifier": int } | true | false,
                              // FC food poisoning (potion 17); object defaults 0.3, 60, 0;
                              // true = FC's standard poisoning, false = none
      "fodder": { "herbivore": "basic" | int, "chicken": ..., "pig": ... },
                              // FC animal food; "basic" = FC's base value (herbivore 800, chicken 1600, pig 800)
      "wolf_meat": bool } },  // FC wolves eat it
  "tiers": [                  // optional (C2): one band rule per mod namespace
    { "namespace": "<mod id>",
      "bands": [ { "max_nutrition": int, "hunger": int, "fat": float }, ..., { "hunger": int, "fat": float } ] } ] }
```

- Every field is optional. Per field, an item entry wins, then the first matching tag entry, then the tier band.
  Files load in resource-location order and a later file's entry for the same key replaces an earlier one; a
  datapack overrides a whole file by its path.
- Item entries resolve when the files load; tag entries when the server's tags bind (TagsUpdatedEvent
  SERVER_DATA_LOAD), before the table is synced to players. Unknown ids are skipped, one WARN each.
- Tiers: the scope is every registered item of the namespace whose no-arg `Item.getFoodProperties()` is non-null
  and that is not a LegacyProxyItem. Its 1.20.1 nutrition picks the first band whose `max_nutrition` is at least
  it; the last band has no `max_nutrition` and takes the rest; `max_nutrition` must ascend, `hunger` must not be
  negative, `fat` is optional. A later file's tier for the same namespace replaces an earlier one. A malformed
  tier logs one WARN, is skipped and counts as unknown.
- The table is published as immutable maps through volatile fields (an integrated server and its client share
  it). A dedicated server sends the resolved table to each client (`BTWNetwork.FoodTableSync`, PLAY_TO_CLIENT,
  id 6); the client skips ids it does not know.
- Log: `[BTW-Food] loaded <n> entries (<n> tag, <n> tiered), <n> unknown ids skipped` (logger BTW-Food).
  2026-10-18: `18 entries (0 tag, 0 tiered)` with BTW alone (`minecraft.json`, the post-1.5.2 vanilla foods at
  their nearest FC relative's values), `1324 entries (9 tag, 4 tiered)` with every optional mod.
- HarvestCraft data (five generated files, each behind `forge:mod_loaded`): settled decision #12's tiers per
  module; the ground meats as their FC raw meats with FC food poisoning and the cooked ones as FC cooked meats,
  by the `#forge:groundmeats/<meat>` subtags (never `#forge:groundmeats`, which holds firm tofu); FC fodder for
  seeds (chickens), vegetables (pigs) and grains (herbivores).
- Caveats (C1 review findings, open): an explicit `"poison": false` does not stop FC's standard poisoning of a
  raw-crafted food (`BtwFoodEvents` applies it when the entry has no poison; recipe-bridge step 9). The decoder
  bound of 65,536 entries is above what fits in the 1 MiB server-to-client payload: a very large table fails to
  send (one WARN) and the client keeps no table.
- Open maintainer question, not decided: HarvestCraft makes its 97 seeds edible through its shared item
  properties, and the pamhc2crops tier (nutrition up to 4 -> hunger 1) values each at 3 FC points. 4.B values
  its only edible seed, pumpkin seeds, at 1: heal amount 1 (vanilla/server Item.java:288), and
  `FCItemSeedFood.GetHungerRestored` does not multiply it (Common FCItemSeedFood.java:89-94).

---

## Modded plants (FcSoil, ModdedPlantRules)

Added in compat wave C1 (`INTEGRATIONS.md` #026; maintainer ruling: a post-1.5.2 plant, vanilla or modded,
takes the 4.B rule of its nearest FC relative); extended in compat wave C2 (#027, #032). Code:
`farming/FcSoil.java`, `farming/ModdedPlantRules.java`, `farming/DungSpreading.java`, `ModernPlantStandIn.java`,
`TwinRegistry.java`, `data/FCImmatureCropLootModifier.java`, `data/HarvestCraftDataProvider.java` (the tag data).

**The predicate** (`FcSoil`, one answer for every rule below). A governed modded plant (`governedKind`) is not a
ProxyBlock, has no FC identity id, is not already governed by `TickPolicy.isFcGrowthGoverned`, and is a
CropBlock (kind CROP), a sapling (a SaplingBlock or a `#minecraft:saplings` member: SAPLING) or a
`#betterthanwolves:fc_soil_plants` member (WILD). It stands on accepting BTW soil (`isOnAcceptingBtwSoil`) when
the FC block below answers its kind's FC soil hook: `CanDomesticatedCropsGrowOnBlock`,
`CanSaplingsGrowOnBlock` or `CanWildVegetationGrowOnBlock`. FC grass and dirt take saplings and wild plants, not
crops; modern soils answer through their stand-in tags and never take crops. Reads only: the WorldBridge on the
server, the render thread's ClientFcWorld on the client; any other reader (world generation, mesh threads) is
never governed. Every FC call is wrapped: a throw is logged once and answers false.

```
Rule        | Hook                                            | Does
────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────────
crop growth | CropGrowEvent.Pre at LOW, DEFAULT results only  | FC's root-crop rule (beetroot's twin): not
            | (ModdedPlantRules.onCropGrowPre; the crop's own | in the End, no weeds, light 9 above,
            | random tick fires it)                           | hydrated soil, then modded_crop_base_chance
            |                                                 | x the soil's multiplier x maxAge / 7
fully grown | CropGrowEvent.Post at max age                   | NotifyOfFullStagePlantGrowthOn: fertilized
            |                                                 | farmland 1088 -> 1087, planters unfertilize
weeds       | RightClickBlock (ModdedPlantRules)              | pulls weeds before any held item (bone
            |                                                 | meal kept), with 1.20.1's activation gate;
            |                                                 | never for a spectator
bone meal   | BoneMealPolicy.route(level, pos, state)         | SOIL_RELAY: FC fertilizer, never 1.20.1
            |                                                 | growth (see Bone meal)
bees        | BeeGrowCropGoalMixin (FcSoil.governsAt)         | one FC crop roll per visit, not a stage
unripe loot | global loot modifier                            | an unripe crop on BTW soil or FC loose
            | betterthanwolves:immature_crop                  | dirt drops nothing
FC's view   | ModernPlantStandIn (ModernBlockStandIn.idFor;   | FC's weeds, animals and fire treat the
            | the id after the collider, only when the pack   | crop as an FC crop
            | has a modded crop)                              |
dung        | DungSpreading (RightClickBlock, main hand;      | BTW dung runs the tagged block's own
            | #betterthanwolves:dung_spreads)                 | bone-meal growth, one dung used (none in
            |                                                 | creative), levelEvent 1505
saplings    | TwinRegistry (see Tick ownership, twins)        | FC's sapling or mushroom rule, then the
            |                                                 | mod's own tree
fruit       | #betterthanwolves:paced_like_sweet_berries      | FC cocoa's pace, 0.25, End ban
            | (see Tick ownership, paced)                     |
```

A season mod's ALLOW or DENY on `CropGrowEvent.Pre` stands (the crop rule acts only on DEFAULT). A plant on FC
ground that does not take it (an LSO fern crop on grass, a garden on sand) keeps 1.20.1 growth and loot, but
bone meal on it is 4.B's (the ground declines, the item is kept).

**btw_plant_rules** (`data/<ns>/btw_plant_rules/*.json`): `{"modded_crop_base_chance": 0.1}`, the chance per
random tick of one growth step at maxAge 7 on unfertilized soil (FCBlockCarrot.GetBaseGrowthChance, Common
FCBlockCarrot.java:31). Files are read in key order, a later file overriding an earlier key. A value that is not
a number in 0..1 is refused with a WARN and the default kept; an unknown key is ignored with a WARN. BTW ships
`btw_plant_rules/default.json` with 0.1. Logs: `[MODDED-PLANTS] btw_plant_rules: <n> file(s),
modded_crop_base_chance=<x>` at each data load, and at ServerStarted `[MODDED-PLANTS] crops=<n> saplings=<n>
modded-saplings-on-FC-rule=<n> fc_soil_plants=<n> plant stand-in=<id|none> base=<x>`. 2026-10-18 production
smoke: BTW alone `crops=0 saplings=0 modded-saplings-on-FC-rule=0 fc_soil_plants=0 plant stand-in=none base=0.1`;
with every optional mod `crops=100 saplings=0 modded-saplings-on-FC-rule=114 fc_soil_plants=6 plant
stand-in=162 base=0.1`.

**Tags** (general-rule data: the rule is BTW's, the members are data; BTW's datagen writes the HarvestCraft and
Dynamic Trees members, every entry `required: false` with `replace: false`, so a tag is inert without the mods
it names, and a pack puts another mod's plant under a rule by tagging it):

```
Tag                                        | Read by                                       | Members BTW ships
───────────────────────────────────────────┼───────────────────────────────────────────────┼─────────────────────────────────────
#betterthanwolves:fc_soil_plants           | FcSoil.FC_SOIL_PLANTS (kind WILD)             | the 6 HarvestCraft gardens
#betterthanwolves:dung_spreads             | DungSpreading.DUNG_SPREADS (BonemealableBlock | the 6 HarvestCraft gardens
                                           | members only)                                 |
#betterthanwolves:paced_like_sweet_berries | TwinRegistry.PACED_LIKE_SWEET_BERRIES         | 95: the 50 HarvestCraft tree fruit,
                                           | (TickPolicy.decide, isFcGrowthGoverned)       | Dynamic Trees' apple and cocoa, the
                                           |                                               | 43 dtphc2 fruit and pods
```

`DungSpreading` keeps the two item-use gates a cancelled `RightClickBlock` would skip (another handler's
`useItem` DENY, and the adventure-mode `mayBuild` / CanPlaceOn check); a block whose bone-meal code throws is
switched off for that block alone, with one WARN.

---

## Mod compatibility (BTWCompat)

Added in compat wave C1 (`INTEGRATIONS.md` #020, the foundation); the six integrations followed in compat wave
C2 (#027-#032). Code: `compat/BTWCompat.java`, `compat/CompatMixinPlugin.java`, `compat/FcRefs.java`,
`compat/thermal/BtwThermalTable.java`, `BridgeSelfAudit.java` (the compat-refs and thermal audits),
`compat/<mod>/`, `mixin/compat/<mod>/`, `data/SereneSeasonsTagProvider.java`, `data/LsoClimateDataProvider.java`,
`data/HarvestCraftDataProvider.java`.

**Loader** (`BTWCompat.init(IEventBus)`, called once from the `BTWForgeMod` constructor after
`BTWNetwork.register()`; `ModList` is filled before mod construction, so `isLoaded` answers there):

```
Mod id(s)                          | Entry point (btw.forge.compat.)         | Entry
───────────────────────────────────┼─────────────────────────────────────────┼───────────
dynamictrees                       | dynamictrees.DynamicTreesCompat.init    | #027
sereneseasons                      | sereneseasons.SereneSeasonsCompat.init  | #028
appleskin (client dist only)       | appleskin.AppleSkinCompat.initClient    | #029
cold_sweat                         | coldsweat.ColdSweatCompat.init          | #030
legendarysurvivaloverhaul          | lso.LsoCompat.init                      | #031
any of pamhc2crops, pamhc2trees,   | harvestcraft.HarvestCraftCompat.init    | #032
pamhc2foodcore, pamhc2foodextended |                                         |
```

- Each entry point is `public static void (IEventBus)`, names no foreign type, and is reached by one direct
  `invokestatic` inside its `isLoaded` branch and its own `catch (Throwable)`. Success logs `[BTW-Compat]
  <modid> <version> present: integration #0NN enabled`; a failure logs `[BTW-Compat] <modid> integration failed
  to start; BTW continues without it`. With Cold Sweat and LSO both loaded, exactly one WARN recommends that
  Cold Sweat owns temperature (`INTEGRATIONS.md` #030, #031).
- Soft-dependency rules (SR1), binding on every integration: `BTWCompat` contains no `invokedynamic` (no lambda,
  method reference or string concatenation; `javap -v` must show none), so no compat class loads while its mod is
  absent; no compat class carries `@Mod` or `@Mod.EventBusSubscriber`; no always-loaded class holds a static
  field of a foreign type. `init` runs during mod construction, before FC init: it registers listeners and
  fills seams (`ModernBlockHooks`, `TwinRegistry.registerModdedSapling`, `SeasonGate.setProvider`,
  `BTWClimate.setProvider`) only, and never calls FcRefs, BtwThermalTable, ProxyRegistry or a registry. Every
  handler, hook, twin method and renderer that touches a foreign API or FC code catches
  `RuntimeException | LinkageError`, logs once and switches only its own integration (or handler) off; the
  mod's own behaviour then stands.
- An integration that probes another mod's API by reflection must look members up under the names the release
  jar carries. Dynamic Trees' `performBonemeal` overrides a vanilla method, so its release jar has only the SRG
  name; `DTTrees.probe` checks the `BonemealableBlock` subtype instead (fixed 2026-10-17 and confirmed by the
  2026-10-18 production smoke; a lookup by the dev name would have switched #027 off in production).

**Mixins on optional mods, and `CompatMixinPlugin`**:

```
Mixin (btw.forge.mixin.)                       | Target (@Pseudo, remap = false, require = 0)        | Does
───────────────────────────────────────────────┼─────────────────────────────────────────────────────┼─────────────────────────────────────────
compat.sereneseasons.SeasonalBonemealVetoMixin | sereneseasons.season.SeasonalCropGrowthHandler      | returns before Serene Seasons' bone-meal
(#028)                                         | .applyBonemeal(glitchcore.event.player              | veto when BoneMealPolicy.isFertilizer
                                               | .PlayerInteractEvent$UseBlock), static, HEAD,       | and the positional route is not VANILLA
                                               | cancellable                                         |
compat.lso.LsoRegenGateMixin (#031)            | sfiomn.legendarysurvivaloverhaul.common             | cancels the step while LsoRegenGate
                                               | .capabilities.bodydamage.BodyDamageCapability       | .blocks (FC food at or below 24/60);
                                               | .applyCustomHealthRegeneration(Player), HEAD,       | adds the marker LsoRegenGate.Applied
                                               | cancellable                                         |
```

- A foreign mixin is allowed only where no event lets BTW act first (SR15). Its logic lives outside the mixin
  package (`BoneMealPolicy`, `compat.lso.LsoRegenGate`), because Mixin refuses to let other code load classes from
  there; a throw lets the foreign mod's own code run.
- `CompatMixinPlugin` is the `plugin` of `betterthanwolves.mixins.json`. `shouldApplyMixin` answers false for a
  mixin under `btw.forge.mixin.compat.<mod>.` whose mod file is absent from FML's `LoadingModList`
  (`sereneseasons.` -> sereneseasons, `lso.` -> legendarysurvivaloverhaul), and true for every other mixin and
  whenever it cannot answer. Why: Mixin 0.8.5 looks the target up while it prepares the config
  (`MixinInfo.getTargetClass` -> `ClassInfo.forName`) before it checks `@Pseudo`, and `ClassInfo.forName` WARNs
  `Error loading class: <target>` with a ClassNotFoundException on every launch without that mod. The plugin is
  asked first (`MixinInfo.readDeclaredTargets`), so the mixin gets no target and nothing is logged. `ModList`
  does not exist yet at that point, so `BTWCompat.isLoaded` cannot answer there. Mixin loads the plugin before
  any Minecraft or BTW class: it names only Mixin, ASM and fmlloader types, the mod ids are literals, and it has
  no lambda or string concatenation.
- 2026-10-18 production smoke: with every optional mod, `Mixing compat.lso.LsoRegenGateMixin from
  betterthanwolves.mixins.json into sfiomn.legendarysurvivaloverhaul.common.capabilities.bodydamage
  .BodyDamageCapability` and `Mixing compat.sereneseasons.SeasonalBonemealVetoMixin ... into
  sereneseasons.season.SeasonalCropGrowthHandler`; BTW alone logs neither, and no `Error loading class`.
- **LsoRegenGate.Applied.** `LsoRegenGateMixin` implements the empty marker interface `LsoRegenGate.Applied`, so
  LSO's `BodyDamageCapability` carries it once the mixin applied. At server start `LsoCompat` checks that the
  class is assignable to the marker and still declares `applyCustomHealthRegeneration(Player)` (naming the
  class loads it, so the mixin has applied by then); otherwise it WARNs `[BTW-LSO] regeneration gate did not
  apply (BodyDamageCapability.applyCustomHealthRegeneration(Player) not found in this LSO version)`. The gate
  names no LSO class, logs `[BTW-LSO] regeneration gate engaged: ...` once, and fails open: on any error it
  switches off and LSO regenerates as it would without BTW.

**FcRefs and the compat_refs manifests (#020).** Static data that names BTW content by key
(`betterthanwolves:block_<id>`, `item_<id>`) breaks when a server's BTW ID config moves an id. Every compat
datagen provider therefore also writes a manifest of the FC fields behind its keys:

```
data/betterthanwolves/compat_refs/<name>.json:
{ "blocks": { "<FCBetterThanWolves field>": "<key>", ... },
  "items":  { "<FCBetterThanWolves field>": "<key>", ... } }
```

- `FcRefs.refsManifest(blockFields, itemFields)` writes it with the keys this run's FC ids give (runData uses
  the default ids) and throws `IllegalStateException` listing every field that does not resolve, so runData
  fails instead of shipping a wrong key.
- `BridgeSelfAudit` (`[SELF-AUDIT/compat-refs]`, FMLCommonSetupEvent) reads the manifests listed in
  `COMPAT_MANIFESTS` (sereneseasons, lso, harvestcraft) and compares each key with `FcRefs.liveKey`: a stale key
  WARNs when the manifest's mod is loaded and is INFO `(mod absent, data inert)` otherwise; an absent manifest is
  skipped, a malformed one WARNs once and is ignored. Line: `[SELF-AUDIT/compat-refs] <n> manifest(s) read
  [...], <n> entries, <n> stale (<n> warned, <n> inert)`.
- Shipped: `sereneseasons.json` (1 block, 2 items), `lso.json` (14 blocks, 25 items), `harvestcraft.json` (1
  item): `3 manifest(s) read [sereneseasons, lso, harvestcraft], 43 entries, 0 stale (0 warned, 0 inert)`. A
  moved id was proven both ways on 2026-10-17: `1 stale (1 warned, 0 inert)` with Serene Seasons loaded,
  `2 stale (0 warned, 2 inert)` with only Cold Sweat.
- Runtime-generated data, which would follow the ID config, is deferred (decision #16).

**BtwThermalTable (#020; LAVA_CRACKS and the vanilla keys in C2).** BTW's climate facts in mod-neutral form: it
names BTW content by `FCBetterThanWolves` field (resolve with FcRefs) and carries no number of any temperature
mod; each integration maps the tiers to its own scale.

```
public enum HeatTier { EMBERS, SMALL_FIRE, MEDIUM_FIRE, LARGE_FIRE, STOKED_FIRE, HIBACHI_BODY, BRICK_OVEN,
                       TORCH, CANDLE, SMOULDERING_LOG, BURNING_LADDER, LAVA_CRACKS }
record HeatSource(String fcField, int metaMin, int metaMax, HeatTier tier)    // heatSources(): 29 rows, 14 blocks
record Clothing(String fcField, ClothingSet set, Slot slot)                   // clothing(): 20 rows
record Textile(String fcField, TextileKind kind)                              // textiles(): 4 rows
record HotMeal(String fcField, MealTier tier)                                 // hotMeals(): 3 rows (WARM, HOT)
record VanillaHeatSource(String selector, boolean requiresLit, HeatTier tier, String fcIdentity)
                                                                              // vanillaHeatSources(): 5 rows
record VanillaHotMeal(ResourceLocation item, MealTier tier, String fcIdentity) // vanillaHotMeals(): 1 row
record Fuel(Item item, ResourceLocation key, int fcBurnTime)                  // fuels(): computed live
allFields(): the 41 distinct FC fields of every row except fuels
```

- Heat rows are contiguous metadata ranges of ProxyBlocks (every default id is 175 or higher): the embers are
  meta 8-11 of the unlit campfire, the lit hibachi meta 4-7 and 12-15 (two rows), the lava-receiving blocks one
  row per odd meta (FCBlockLavaReceiver.GetIsBlockWarm, Server FCBlockLavaReceiver.java:99-102: lava in the
  cracks while `meta & 1`).
- Vanilla keys (vanilla ids never move with the BTW ID config, so the selector is written directly):
  `minecraft:torch` and `minecraft:wall_torch` (TORCH: FC's legacy torch, id 50), `#minecraft:candles` and
  `#minecraft:candle_cakes` (CANDLE, lit states only), `minecraft:lantern` (TORCH), and mushroom stew (WARM:
  FC removes its crafting recipe and cooks it in the cauldron). Post-1.5.2 flames take their nearest FC
  relative's tier (maintainer ruling); soul torches and soul lanterns are not listed and keep each mod's
  soul-fire meaning.
- Skip-mod-native rule: each temperature integration skips the selectors its mod already ships data for. LSO
  2.4.7 ships `minecraft:torch`, `minecraft:wall_torch` and mushroom stew's warmth, so `LsoClimateDataProvider`
  writes candles, candle cakes, the lantern and the stew's hydration only (BTW's file would replace LSO's by
  pack order). Cold Sweat writes all five vanilla rows and the stew.
- Tag selectors: LSO's datagen expands `#minecraft:candles` and `#minecraft:candle_cakes` by class
  (`CandleBlock`, `CandleCakeBlock`) over `BuiltInRegistries.BLOCK` in the tag's namespace, because tags are not
  bound in runData; any other `#` selector, a selector that matches no block, and a lit-only selector whose block
  has no `lit` property throw and fail runData. Cold Sweat takes the selector as written.
- `fuels()` walks all 32,000 `Item.itemsList` slots on each call: a consumer calls it once per load.
- Calibrations (maintainer decision #14, anchored on each mod's own vanilla values: Cold Sweat's fire and
  campfire are 25 / 7 / 50, its magma block 20 / 3 / 48; LSO's torch is 1.5, campfire 10, magma block 12):

```
HeatTier        | BTW rows                          | Cold Sweat, F     | LSO
                |                                   | (temp/range/max)  |
────────────────┼───────────────────────────────────┼───────────────────┼───────
EMBERS          | unlit campfire, meta 8-11         | 6 / 3 / 12        | 2.0
SMALL_FIRE      | small campfire                    | 12 / 5 / 24       | 4.0
MEDIUM_FIRE     | medium campfire                   | 20 / 6 / 40       | 7.0
LARGE_FIRE      | large campfire                    | 25 / 7 / 50       | 10.0
STOKED_FIRE     | stoked fire                       | 35 / 7 / 70       | 12.0
HIBACHI_BODY    | lit hibachi, meta 4-7 and 12-15   | 6 / 2 / 12        | 1.0
BRICK_OVEN      | burning brick oven                | 11.25 / 5 / 29.7  | 6.0
TORCH           | burning finite and nether-coal    | 2 / 2 / 4         | 1.5
                | torches; vanilla keys: the legacy |                   |
                | torch (FC id 50), the lantern     |                   |
CANDLE          | BTW candle; vanilla keys: lit     | 1 / 2 / 2         | 1.0
                | candles and candle cakes          |                   |
SMOULDERING_LOG | smouldering log                   | 6 / 3 / 12        | 3.0
BURNING_LADDER  | burning ladder                    | 25 / 7 / 50       | 7.0
LAVA_CRACKS     | loose cobblestone, loose stone    | 20 / 3 / 48       | 12.0
                | brick: every odd meta (C2)        |                   |
```

  An unknown tier gets no Cold Sweat row (one WARN); LSO's map must cover every tier, so a tier added to the
  table fails runData until LSO gives it a value. Logs: `[SELF-AUDIT/thermal] 29 heat-source rows (14 blocks),
  20 clothing, 4 textiles, 3 meals, 39 fuels (nethercoal betterthanwolves:item_481 = 3200); 0 warning(s)`;
  Cold Sweat `Loaded 19 block temperatures` (14 BTW blocks, merged per block, and the 5 vanilla rows); LSO
  `[BTW-LSO] data audit: 126 files expected, 126 loaded by LSO, 0 missing`.

**Seams for integrations.** One slot or list each, set during construction or client setup; each answers
exactly as before when nothing is registered.

```
Seam                                  | Set or registered by                | Read by
──────────────────────────────────────┼─────────────────────────────────────┼─────────────────────────────────────────────
ModernBlockHooks.register(            | an integration, during mod          | ModernBlockStandIn: the 7-argument
  Predicate<BlockState>, Hook)        | construction (Dynamic Trees); then  | OnBlockSawed, GetDoesStumpRemover
                                      | the log rule (ModernLogHooks,       | WorkOnBlock, AttemptToApplyFertilizerTo;
                                      | 2026-10-20)                         | BoneMealPolicy.route (FC_TARGET)
TwinRegistry.registerModdedSapling(   | an integration, during mod          | isFallbackSapling, familyFor,
  ModdedSapling)                      | construction (Dynamic Trees)        | growSaplingTwin, fungusTick
LeafDrops.register(                   | an integration, during mod          | LeafDrops.apply, the leaves GLM
  Predicate<BlockState>, Hook)        | construction (Dynamic Trees)        | (betterthanwolves:fc_leaves): the hook
                                      |                                     | decides a leaves roll before BTW's own
                                      |                                     | sapling rule (2026-10-20, #035)
SeasonGate.setProvider(Provider)      | the season integration (Serene      | SeasonalCropGate (BTW wheat and hemp),
                                      | Seasons)                            | DTSaplingTwin (the DT grow step)
BTWClimate.setProvider(Provider)      | the season integration (Serene      | WorldBridge.IsSnowingAtPos,
                                      | Seasons)                            | FCUtilsMiscColdBiomeMixin
BTWHudOverlay.addFoodHudExtension(    | client setup (FMLClientSetupEvent,  | BTW's food row, every frame
  FoodHudExtension)                   | mod bus): AppleSkin, LSO            |
```

- **ModernBlockHooks** (#022). `Hook` has three defaults, each FC's inert false: `onSawed(ServerLevel, BlockPos,
  BlockState, Direction sawFacing)`, `stumpRemoverWorks(ServerLevel, BlockPos, BlockState)` (FC then removes the
  block and uses one item) and `applyFertilizer(ServerLevel, BlockPos, BlockState)` (FC then uses one item). The
  first registered predicate that accepts the live state wins; a predicate that throws is logged once and
  skipped. `ModernBlockStandIn.askHook` works only on a ServerLevel (on the client it answers false, so a client
  fertilizer prediction on a hooked block is PASS: Dynamic Trees predicts its own swing) and turns a throwing
  hook into false with one WARN. FC reaches a modern block only through its stand-in id, so a state with no
  collision reads as air (id 0) and its hook is never asked; `idFor` asks the live collision shape at the
  position when MC's cached one is empty. Since 2026-10-20 the log rule registers a hook of its own
  (`ModernLogHooks.register`, from the BTWForgeMod constructor right after `BTWCompat.init`,
  BTWForgeMod.java:58-60), so an integration's predicate is asked first for its blocks (Dynamic Trees keeps its
  branches, roots and trunk shells); `FCLogRules.isForeignLog` relies on that order (FCLogRules.java:314-316).
- **SeasonGate** (#028). `Verdict { ALLOW, DENY, DESTROY }`; `growthVerdict(ServerLevel, BlockPos, BlockState,
  ResourceLocation fertilityKey)`, where the key names the plant to the season mod's fertility table (BTW wheat
  and hemp: their seed item; Dynamic Trees: the species' seed item). DESTROY means the caller destroys the plant
  without drops. No provider: ALLOW. A provider that throws (`RuntimeException` or `LinkageError`) or answers null
  is dropped with one WARN, and ALLOW answers from then on. Core BTW has no caller: it serves growth steps that
  fire no Forge crop event. Serene Seasons' provider answers ALLOW for a key in none of its four season item
  tags, so a Dynamic Trees sapling stays season-blind unless a pack tags its seed.
- **BTWClimate** (#028). `coldEnoughToSnow(ServerLevel, Holder<Biome>, BlockPos)`: the provider, else 1.20.1's
  `Biome.coldEnoughToSnow`; read by `WorldBridge.IsSnowingAtPos` (FC's farmland snow reversion, snow cover, FC's
  precipitation checks). `coldBiomeOverride(World, i, k)`: at the column's motion-blocking height, the provider's
  verdict replaces FC's 1.5.2 cold table (`FCUtilsMisc.IsIKInColdBiome`, `getFloatTemperature() <= 0.15`) only
  where it differs from 1.20.1's own; null without a provider or off a WorldBridge. A throwing provider is
  dropped once. Serene Seasons' provider is always seasonal (decision #9) and has no precipitation check: 1.20.1
  lays snow wherever it rains and `Biome.shouldSnow` passes, without asking whether the biome has precipitation
  (a C1 review suggestion to add one was not adopted).
- **FoodHudExtension** (#023). `renderUnder(ForgeGui, GuiGraphics, barRightX, barY, screenWidth,
  screenHeight)`, `renderOver(...)`, `foodPipTint(LocalPlayer)` and `NO_TINT = 0xFFFFFFFF`. Per frame, while BTW
  draws its row: every `renderUnder`, the pips (tinted), every `renderOver`, in registration order; the first
  tint that is not `NO_TINT` wins. `barRightX` is `screenWidth / 2 + 91`, `barY` the row's top before BTW
  advances `rightHeight` by 10. An extension restores the shader colour and blend it changes and balances the
  pose stack in try/finally; one that throws an Exception or LinkageError is removed after one WARN. BTW cancels
  vanilla's `food_level` overlay, so this interface replaces other mods' `food_level` listeners. AppleSkin draws
  its previews and underlay (fat preview before food preview) and returns `NO_TINT`; LSO tints the pips frost
  blue (`0xFFB0E0FF`) while `legendarysurvivaloverhaul:cold_hunger` is active and cancels LSO's own cold-hunger
  bar.

---

## Held use (hold-to-craft)

Added 2026-10-18 (player report; `CHANGELOG.md` [Unreleased], "Knitting, bone carving and wicker weaving never
finished"; `STUB_CATALOGUE.md` (ao), caveats). Code: `ProxyItem.onUseTick` and
`ProxyItem.shouldCauseReequipAnimation`, `PlayerBridge.getItemInUseCount` and `PlayerBridge.SetItemInUseCount`,
`mixin/LivingEntityUseAccessor.java`, Modern-Common `Item.GetItemUseWarmupDuration`.

**The rule.** 1.5.2 ran the held item's `UpdateUsingItem` every tick of a use, before the countdown, on both
sides, and kept the countdown on the player. 1.20.1 calls `Item.onUseTick` at the same point and keeps the
countdown in `LivingEntity.useItemRemaining`. The bridge maps each piece; FC's `FCItemCraftingProgressive
.UpdateUsingItem` runs unchanged (Common FCItemCraftingProgressive.java:58-87: after the warm-up, every 4 ticks
it takes 1 off the item's damage on the server and plays the crafting effects; at damage 0 it ends the use).

```
# | 1.5.2 / FC                              | 1.20.1 / bridge                             | Does
──┼─────────────────────────────────────────┼─────────────────────────────────────────────┼───────────────────────────────────────────
1 | EntityPlayer.onUpdate runs the held     | ProxyItem.onUseTick (an Item override, no   | runs FC UpdateUsingItem, main hand only,
  | item's UpdateUsingItem every tick of a  | mixin), called by LivingEntity              | for FC classes that override it
  | use, before the countdown (vanilla/     | .updateUsingItem :2898, before the          | (USE_TICK_OVERRIDDEN: only
  | server EntityPlayer.java:229, :232)     | decrement :2903, on both sides              | FCItemCraftingProgressive and its
  |                                         |                                             | knitting, bone carving and wicker
  |                                         |                                             | weaving subclasses)
2 | EntityPlayer.getItemInUseCount, the     | PlayerBridge.getItemInUseCount: the real    | FC's warm-up and its every-4-ticks step
  | countdown kept on the player (vanilla/  | player's getUseItemRemainingTicks (:3038),  | read the real counter
  | server EntityPlayer.java:160)           | 0 when no item is in use                    |
3 | EntityPlayer.SetItemInUseCount(1)       | PlayerBridge.SetItemInUseCount also writes  | this tick's decrement completes the use
  | (FCItemCraftingProgressive.java:83)     | the real counter through LivingEntityUse    | (completeUsingItem -> finishUsingItem
  |                                         | Accessor (@Accessor useItemRemaining,       | -> FC onEaten)
  |                                         | f_20936_; LivingEntity.java:219)            |
4 | Item.GetItemUseWarmupDuration = 7       | Modern-Common Item: 7 (it answered 0)       | no progress in the first 7 ticks
  | (vanilla/server Item.java:888-891)      |                                             |
5 | ItemRenderer keeps the held item when   | ProxyItem.shouldCauseReequipAnimation:      | the hand does not dip at each progress
  | only its damage changed and the item    | false for such an item when only the        | step the server sends
  | ignores damage during use (vanilla/     | stack changed in the same slot              |
  | client ItemRenderer.java:724)           |                                             |
```

- Server: `onUseTick` syncs the bridge's inventory snapshot (`PlayerBridge.getOrCreate`), runs FC's step on the
  main-hand FC stack, then writes the damage back onto the same MC stack (`syncInventoryToReal`), which
  `completeUsingItem`'s identity test needs (LivingEntity.java:3016). Client: a detached copy on the render
  thread's ClientFcWorld, for FC's client-only effects (the bone-carving particles); FC changes the stack and the
  counter only when `!world.isRemote`, and the client bridge's null-player sound is silent, so the server's
  broadcast stays the one sound.
- Every other FC item returns at once: Modern-Common `Item.UpdateUsingItem` is empty, so foods, drinks, bows and
  block-action tools keep 1.20.1's use. A throw inside `onUseTick` is logged once per item and never ends the
  entity tick.
- Verification (2026-10-18, dev dedicated server, `plan_holduse_before` and `plan_holduse_after`): before the
  bridge, 2,600 held ticks left carving and knitting at 599/600 and 1,400 left wicker at 299/300, still in use.
  After it, 14 of 14 checks: a carving bone finishes into the bone fish hook on tick 2401 (no progress in the
  7-tick warm-up, the first step on tick 9, one step short on tick 2400), knitting finishes on tick 2401, wicker
  weaving on tick 1201 (one short on 1200); letting go after 400 ticks keeps 98 steps, and holding again resumes
  from 501 and finishes on tick 2009; the bone fish hook makes the 4.B fishing rod (FCRecipes.java:1117-1122);
  bread, an FC donut and FC chocolate milk still finish in 32 ticks. A real client holding the use key
  (`plan_holduse_client`): 6 of 6 checks, carving and knitting finished in hand.
- Not checked at runtime: the crafting sounds and particles (code review only).

---

## Woods and logs (ModernWoods, FCLogRules)

Added 2026-10-20 (maintainer rulings 1 and 2 of 2026-10-19; `STUB_CATALOGUE.md` (ay), `INTEGRATIONS.md` #034). Code:
`Forge/src/main/java/btw/forge/ModernWoods.java`, `ProxyRegistry.java` (the log API), `LegacyVariants.java` (the log2
and planks item rows, LegacyVariants.java:138-146), `mixin/fcext/` (M1-M9), `BTWLifecycle.java` (step 5e and the lang
file), `Forge/gradle/modern-woods.gradle`, `FCLogRules.java`, `ModernLogHooks.java`, `ModernLogRules.java`,
`data/LogBarkTagProvider.java`, `WorldBridge.deriveVanillaMetadata` (the log metadata); Dynamic Trees (#027):
`compat/dynamictrees/DTTrees.java`, `DTBlockHooks.java`, `DTHarvestHandler.java`.

**The rule.** FC keys a wood on one number, the wood type: the bark subtype, the planks subtype and the work-stump
metadata (oak 0, spruce 1, birch 2, jungle 3, blood wood 4), read from its one log, id 17, as `meta & 3`. Acacia,
dark oak, mangrove and cherry are FC wood types 5-8 (ruling 1: real FC woods through runtime patches, not mapped onto
FC's four). A second, plain FCBlockLog at legacy id 165 ("log2") carries them, so FC's own log code runs on them as on
oak: chewing, bark (tanning), the work stump, the saw, chopping, FC fuel, FC's dig rate and its 0.025 harvest hunger,
fire, explosions. Every other 1.20.1 log follows FCBlockLog's rule through the log rule below; the crimson and warped
stems and hyphae give no bark and leave no work stump (ruling 2). Sidings, mouldings, corners and the other decorative
blocks of the four woods are follow-up FU-W1 (approved 2026-10-20, not done).

**log2** (tag `modern_woods`):

- Its metadata is FC's log layout exactly: bits 0-1 the species (wood type - 5: acacia 0, dark oak 1, mangrove 2,
  cherry 3), bits 2-3 the axis, 12 the stump. The four logs map to 165 (ProxyRegistry.java:505-511), and their wood
  and stripped forms read as 165 in FC's world reads. Id 165 must never change: FC stacks store numeric ids, and
  client and server must agree. A fifth species needs an id of its own: log2's two species bits are full.
- `ModernWoods.create` (ModernWoods.java:193) runs at BTWLifecycle step 5e (BTWLifecycle.java:163-172), after the twins
  (which skip the mapped 165) and before `isFcReady`, so every bake sees it. It builds FC's own `FCBlockLog(165)` and
  `FCItemBlockLog` reflectively, as FC builds id 17's (FCBetterThanWolves.java:1880). If 165 is already taken it logs
  one ERROR and builds nothing; the four logs then keep no FC identity and stay on the log rule (class S).
- FC's recipes for wood types 5-8 go through FC's own public API, reflectively (`addRecipes`, :260): tanning with
  bark 5-8 at 2, 5, 2 and 8 bark per leather (`BARK_PER_LEATHER`, :91; cut leather the same), and the removal records
  of the four vanilla log -> 4 planks recipes, which FcRecipeRemovals pass 1 applies (see
  [Stays removed (RemovedContent)](#stays-removed-removedcontent)). Chopping at the work stump is FC's own
  FCRecipesLogChopping (M5). Planks fuel is 400, 400, 200 and 500 (`PLANKS_BURN_TIME`, :97: the oak, oak, jungle and
  birch tiers), logs x4.
- `/lang/BTW_modern_en_US.lang` (the bark names) is loaded third, after the vanilla and FC lang files
  (BTWLifecycle.java:261-270); FC's own `BTW_en_US.lang` copy is untouched.

```
public static final int ModernWoods.LOG2_ID = 165;              // compile-time constant
public static final int ModernWoods.FIRST_WOOD_TYPE = 5;        // log2 species s is wood type 5 + s
public static int ModernWoods.woodType(int meta);               // 5 + (meta & 3)
public static boolean ModernWoods.isWoodType(int woodType);     // 5-8
public static boolean ModernWoods.isBuilt();                    // log2 exists (165 was free at step 5e)
public static int ModernWoods.workStumpId();                    // fcBlockWorkStump's id, -1 until create resolved it
static void ModernWoods.create();                               // step 5e; never throws
public static void ModernWoods.audit();                         // [SELF-AUDIT/wood]; never throws

public static int ProxyRegistry.getBlockId(Block);              // 165 for the four logs, only while isBuilt()
public static boolean ProxyRegistry.isLogForm(Block);           // a wood or stripped form FC reads as 17 or 165
public static int ProxyRegistry.getTreeLogId(Block);            // 17 or 165, else -1
public static int ProxyRegistry.getTreeLogSpecies(Block);       // 0-3 within that log id, else -1 (public since 2026-10-20)
public static int ProxyRegistry.getWoodType(Block);             // FC wood type 0-3 or 5-8, else -1 (stems, modded logs)
```

- `getBlockId` (ProxyRegistry.java:230-251) still answers 0 for modern-only blocks, so they never run FC block code. The
  four logs are the one exception, gated on `ModernWoods.isBuilt()`, so another block at 165 never receives them.
- The log forms: `LogForm` (Block -> (log id, species)) and `LOG_FORMS` (:271-298), for the bark and stripped forms of
  the eight species; `isLogForm` :315, `getTreeLogId` :325, `getTreeLogSpecies` :338 (a species within its log id:
  cherry is log2 species 3, as jungle is log 17's, so it is not a wood type), `getWoodType` :351.
- Variants (:162-171): FC planks 5 take mask 15, so 5:5-8 are the acacia, dark oak, mangrove and cherry planks (item
  level only: the modern planks keep no FC identity, and 5:4, blood wood, stays oak); log2's variants are the four
  logs (mask 3).

**The patches** (`btw.forge.mixin.fcext`, `@Pseudo`, `remap = false`, descriptors and ordinals from javap of the FC
output, so no refmap entries). Each is keyed on id 165 or on wood types 5-8, so id 17 and FC's own wood types are
untouched:

```
#  | Mixin                         | FC target                                      | Does
───┼───────────────────────────────┼────────────────────────────────────────────────┼─────────────────────────────────────────
M1 | FCBlockLogWoodMixin           | FCBlockLog: ConvertBlock, DropComponentItems   | log2's wood type for the work stump's
   |                               | OnBadBreak, GetIsStump, GetFurnaceBurnTime,    | META and the bark; a log2 stump is a
   |                               | getIcon                                        | stump; fuel; the 1.20.1 log textures
M2 | FCBlockWorkStumpWoodMixin     | FCBlockWorkStump: ConvertBlock, GetStack       | META 5-8: the chewed stump's bark, the
   |                               | RetrievedByBlockDispenser, registerIcons,      | side textures; the block dispenser takes
   |                               | getIcon                                        | back log2 (META 0-3 still meet the P2
   |                               |                                                | stub, STUB_CATALOGUE.md:895)
M3 | FCItemBarkWoodMixin           | FCItemBark: getUnlocalizedName, registerIcons, | bark subtypes 5-8: names, icons, the
   |                               | getIconFromDamage, getSubItems                 | creative tab and JEI
M4 | FCItemBlockLogWoodMixin       | FCItemBlockLog.OnUsedInCrafting                | chopping's by-product bark is the log's
   |                               |                                                | own
M5 | FCRecipesLogChoppingWoodMixin | FCRecipesLogChopping: IsLog,                   | log2 chops at the work stump into its
   |                               | getCraftingResult                              | own planks (5:5-8)
M6 | FCBlockSawWoodMixin           | FCBlockSaw.HandleSawingExceptionCases          | the saw cuts log2: 4 planks of its wood,
   |                               |                                                | 2 sawdust, its bark
M7 | FCBlockPlanksWoodMixin        | FCBlockPlanks: GetFurnaceBurnTimeByWoodType,   | planks fuel 5-8; icons 5-8 (also closes
   |                               | getIcon                                        | the meta > 4 icon overflow)
M8 | FCBlockLogDamagedWoodMixin    | FCBlockLogDamaged                              | a chewed segment joins the log2 trunk
   |                               | .DoesTargetBlockConnectToFacing                | around it
M9 | FCItemChiselIronWoodMixin     | FCItemChiselIron.onBlockDestroyed              | carving a log2 stump costs the chisel
   |                               |                                                | 10 uses
```

- Bark icons are item models (`models/item/fcitembark<x>.json`) whose layer0 is the species' 1.20.1 log side, so they
  follow resource packs. The work-stump sides (`textures/block/fcblockworkstump<x>.png`) are derived by
  `deriveModernWoodTextures` (Forge/gradle/modern-woods.gradle:22; group `btw`, part of `processResources`): FC's
  work-stump overlay of the nearest FC relative laid over the 1.20.1 log side. Its inputs are FC's release zip and
  ForgeGradle's client-extra jar (`-PbtwClientExtraJar=<path>` overrides). The outputs are checked in, a missing input
  WARNs and skips, and a PNG is rewritten only when its pixels and its bytes differ (2026-10-20: `4 work-stump
  textures checked (0 written)`). They carry Mojang pixels and do not follow resource packs.

**The log rule** (tag `fc_log_rules`; `FCLogRules`, `ModernLogHooks`, `ModernLogRules`):

```
Class | Blocks                                             | Gets
──────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────
log   | the eight logs with an FC identity: 17 (oak,       | FC's own pipeline (ServerPlayerGameModeMixin, FCMiningSpeed)
      | spruce, birch, jungle) and 165 (acacia, dark oak,  |
      | mangrove, cherry)                                  |
F     | their bark ("wood") and stripped forms, which      | FC's dig rate and FC's own ConvertBlock chew at the cell (its
      | FC's world reads see as 17 or 165 (isFcReadLog)    | bark, chewed log or work stump); the harvest drop stays
      |                                                    | vanilla; FC's saw and stump remover already see FC's log
S     | any other #minecraft:logs block with an AXIS, a    | the axe gate, an immediate chewed log with its bark, the work
      | full collision cube and no block entity, whose     | stump of its bark; FC's saw and stump remover through
      | first FC machine hook is the log rule's            | ModernLogHooks; stems and hyphae: no bark, no work stump
      | (isForeignLog): stems, hyphae, modded logs         |
```

```
public static int FCLogRules.fcWoodMeta(Block log);        // contract C1: ProxyRegistry.getWoodType (0-3, 5-8), -1 none
public static boolean FCLogRules.isStump(BlockGetter, BlockPos, BlockState);   // contract C2: the one stump rule
public static boolean FCLogRules.isFcReadLog(BlockState);       // class F
public static boolean FCLogRules.isForeignLog(BlockState);      // class S
public static boolean FCLogRules.isLogRuleTarget(BlockState);   // class F or S
public static int FCLogRules.barkSpecies(BlockState);      // FC's wood type, else the first bark/<wood> tag, -1 for
                                                           // bark/none, else oak (UNTAGGED_BARK)
public static int FCLogRules.workStumpSpecies(BlockState); // barkSpecies when FC makes a work stump of it, else -1
public static ItemStack FCLogRules.bark(int woodType);     // one FC bark, 0-8
public static BlockState FCLogRules.workStumpState(int woodType);   // the work stump with META 0-3 or 5-8
public static void FCLogRules.chargeLogHarvest(ServerPlayer, int logs, int woodType, BlockPos);   // FC's exhaustion
public static ItemStack FCLogRules.sawPlanks(ServerLevel, BlockState);   // the one-ingredient planks recipe, cached
public static void FCLogRules.clearCaches();
```

- Lines: `fcWoodMeta` FCLogRules.java:166 (`fcWoodType` :177 is the same value), `bark` :186, `workStumpState` :219,
  `chargeLogHarvest` :241, `isFcReadLog` :265, `isForeignLogCandidate` :294, `isForeignLog` :314, `isLogRuleTarget`
  :319, `isStump` :331, `barkSpecies` :409, `sawPlanks` :451, `clearCaches` :486. Nothing throws: a failure is logged
  once (logger `BTW-LogRules`) and degrades to EMPTY, null, -1 or false.
- `isStump` is the port's one stump rule: a vertical log (AXIS Y) standing on a block that is not air, renders solid
  and is not in `#minecraft:logs`. `WorldBridge.deriveVanillaMetadata` sets the stump bit of FC's own logs with it
  (WorldBridge.java:368-371), so an oak log on a stem or a Dynamic Trees branch is no longer a stump.
- `ModernLogHooks.register` (ModernLogHooks.java:33) runs once from the BTWForgeMod constructor, right after
  `BTWCompat.init` (BTWForgeMod.java:58-60), with the class-S structural predicate `isForeignLogCandidate`, so an
  integration's own hook (Dynamic Trees) is asked first. `onSawed`: 4 planks of `sawPlanks`, 2 sawdust and the bark
  if the log still has it; a log with no one-log planks recipe takes FC's generic saw and drops as itself.
  `stumpRemoverWorks`: `isStump`. Log: `[STAND-IN] FC machine hook registered: the log rule (stems and modded logs,
  fc_log_rules)`.
- `ModernLogRules` is registered on the Forge bus by the BTWForgeMod constructor. Each handler acts only on a log-rule
  target; a failure is logged once and leaves the event as 1.20.1 has it:

```
Event (Forge bus)                      | Priority | Side   | Does
───────────────────────────────────────┼──────────┼────────┼───────────────────────────────────────────────────────────
PlayerEvent.BreakSpeed (:99)           | LOWEST   | both   | FC's log dig rate (FCMiningSpeed.fcLogDigProgress), so the
                                       |          |        | client predicts the server; a stump x3; a barkless
                                       |          |        | class-S chew at FC's chewed-log hardness
BlockEvent.BreakEvent (:146)           | LOWEST   | server | a survival hit that does not harvest (a stump, or no
                                       |          |        | harvesting axe) is cancelled and chewed; a chew costs no
                                       |          |        | hunger, a harvest keeps 1.20.1's 0.005
BlockEvent.BlockToolModificationEvent  | LOWEST   | server | an axe strip of a barked class-S log records its bark
(:254)                                 |          |        |
TickEvent.ServerTickEvent END (:288)   | default  | server | pays a recorded strip's bark once the cell holds the strip
                                       |          |        | result (Forge posts the event before the block strips);
                                       |          |        | drops it after 5 ticks
TagsUpdatedEvent, OnDatapackSyncEvent, | default  |        | FCLogRules.clearCaches (bark tags and planks recipes may
ServerStoppedEvent (:316-331)          |          |        | have changed)
```

- An axe strip pays bark only for a barked class-S log; for FC's woods and their forms the chew or the saw pays it.
- The bark tags are written by runData (`data/LogBarkTagProvider.java:46`) under
  `Forge/src/generated/resources/data/betterthanwolves/tags/blocks/bark/`. FC's own wood type wins over every tag, so
  they decide class S only, and an untagged barked log pays oak bark and makes an oak work stump (logs design decision
  6(a)):

```
Tag (blocks)                                 | Members BTW ships                                  | Means
─────────────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────
#betterthanwolves:bark/oak, spruce, birch,   | #minecraft:<wood>_logs                             | that FC bark
  jungle                                     |                                                    |
#betterthanwolves:bark/blood_wood, acacia,   | empty (for packs: modded logs)                     | that FC bark
  dark_oak, mangrove, cherry                 |                                                    |
#betterthanwolves:bark/none                  | #minecraft:crimson_stems, #minecraft:warped_stems  | no bark, no work stump
```

- Dynamic Trees (#027) takes the same rule through FCLogRules: `fcWoodMeta` of a family's primitive log is its wood
  type, so the DT families of the four woods give their own bark, work stump and saw planks (dark oak is wood type 6);
  the crimson and warped fungus families give no bark and no work stump and are chewed; a family with no primitive log
  pays oak bark; modded families pay their bark tag, and their saw planks come from the log's planks recipe.

```
[WOOD] log2 FCBlockLog at 165: acacia, dark oak, mangrove and cherry are FC wood types 5-8            (logger BTW-Wood)
[WOOD] tanning with bark 5-8 ([2, 5, 2, 8] bark per leather) and the 4 vanilla log -> planks removals added
[SELF-AUDIT/wood] log2 <FC block class> / <FC item class> at 165: 4 woods (wood types 5-8), <n> warnings (0 expected)
```

- `ModernWoods.audit` runs from `BridgeSelfAudit.runCommon` (BridgeSelfAudit.java:44-45). It WARNs when log2 was not
  built (see the `[WOOD]` ERROR), when 165 holds another block or item, when FC's bark, sawdust, work stump or eject
  helper does not resolve, when a log, form or planks block does not map both ways as expected, and when a model or
  texture is missing from the jar.
- Known limits: the stand-in pool is one id smaller (see the twins' Ids); class F and S keep 1.20.1's 0.005 harvest
  hunger (`INTEGRATIONS.md` #021; follow-up FU-W5); stand-in logs have no kiln and no smouldering; explosions drop
  whole logs; a chew can flicker for one frame; `barked` uses a synthetic strip context with no player; old headless
  acacia stacks do not stack with new (165, s) stacks until they pass a 1.20.1 inventory. As in 4.B, a broken work
  stump drops an oak plank and the chewed log is oak-textured (stems become FC's oak-textured, flammable chewed log).
- Verification (2026-10-20, stage B of scratchpad `fu_runs/BASELINE_RUNS_FU.md`): wood_p1 67/67 (HEAD 14/67), mlog_p1
  73/73 (HEAD 18/73), dt_wood 4/4, dt_p2 27/27 (the three new S4 rows), dt_off 2/2; dt_p1 and dt_p5 unchanged.
  Clients: wood_c1 (the derived work-stump sprites, bark from the 1.20.1 log textures, dig progress 0.004) and mlog_c1
  (client dig progress equals the server's). runData wrote the 10 bark tags.

---

## Leaves (LeafDrops)

Added 2026-10-20 (`STUB_CATALOGUE.md` (ax), `INTEGRATIONS.md` #035). Code: `Forge/src/main/java/btw/forge/LeafDrops.java`,
`data/FCLeavesLootModifier.java`, `data/BTWLootModifiers.java` (codec `fc_leaves`, :42), `data/FCLootOverrides.java`
(instance `leaves_drop_like_fc`, :104-107), Modern-Common `BlockLeaves.java` (the 1.5.2 drop methods),
`compat/dynamictrees/DTLeafDrops.java`, `DynamicTreesCompat.java`.

**The rule.** A `#minecraft:leaves` block drops what FCBlockLeaves drops (Server FCBlockLeaves.java:62-85), whichever
path rolls its loot: shears and Silk Touch give the leaves; anything else gives the species' sapling at 1 in 20 (1 in
40 for FC wood species 3, jungle), never sticks or apples, with no Fortune bonus and no explosion penalty.

- **Two sources, nothing twice.** FC's own paths (the FC harvest, FCBlockLeaves decay, FC machines, the block
  dispenser) never roll a table: they run FCBlockLeaves on the 1.5.2 drop methods now in Modern-Common BlockLeaves
  (`quantityDropped` 1 in 20, `idDropped` the sapling, `damageDropped` `meta & 3`, `harvestBlock` with shears,
  `createStackedBlock`, and `canSilkHarvest` true, an interim until the general canSilkHarvest, decision D6(b)). Every
  path that rolls a table goes through the GLM: 1.20.1 breaks and decay of leaves with no FC identity, every 1.20.1
  explosion, stand-in machine drops and foreign `destroyBlock`. FC's mining charge on leaves is FC's own path, a stub
  until CF2-DR5.
- **Who decides the sapling.** A registered hook first. Otherwise, only for the block's own table: FC identity (legacy
  id 18) takes FC's `idDropped` / `damageDropped` through the item bridge; any other leaves block takes the
  same-namespace `#minecraft:saplings` member named `<stem>_sapling`, else `<stem>`, else the only `<stem>_*` (the
  acacia, dark oak and cherry saplings, the azaleas, the mangrove propagule). A block it cannot resolve keeps its own
  loot.
- **Strip.** Saplings, the resolved sapling item, `#forge:rods/wooden`, the hook's byproducts and, for
  minecraft-namespace leaves only, apples. Everything else stays: modded fruit, other mods' GLM additions. Shears or
  Silk Touch loot is kept as rolled.
- One condition-free modifier. It never returns early on empty loot (most leaf rolls yield nothing and FC still rolls)
  and skips `LootTable.EMPTY` rolls (`forge:unknown_loot_table`). Its place in the generated GLM list (hash order) does
  not matter: it only removes leaf byproducts and adds one sapling. runData must write
  `loot_modifiers/leaves_drop_like_fc.json` and its `global_loot_modifiers.json` entry, or the GLM never loads.

```
public record LeafDrops.Resolution(ItemStack sapling, int fcSpecies, Predicate<LootContext> allows, Set<Item> byproducts)
        // sapling: one at FC's odds (EMPTY rolls nothing); fcSpecies 3: 1 in 40; allows: a veto tested after FC's
        // roll succeeds; byproducts: extra items to strip
public static final LeafDrops.Resolution KEEP;            // leave the table's loot alone (compared by identity)
@FunctionalInterface public interface LeafDrops.Hook {
    Resolution resolve(BlockState leaves, LootContext ctx);
}
public static void LeafDrops.register(Predicate<BlockState> owns, Hook hook);   // during mod construction; the first
                                                                                // registered match wins
public static ObjectArrayList<ItemStack> LeafDrops.apply(ObjectArrayList<ItemStack> loot, LootContext ctx);   // GLM body
```

- Lines: `Resolution` LeafDrops.java:87, `KEEP` :90, `Hook` :95-97, `register` :115, `apply` :121. `register` touches
  no registry. Static resolutions are cached per block and cleared at every server tag load (:245-246; LeafDrops is a
  Forge-bus `@Mod.EventBusSubscriber`). Every failure logs one WARN and keeps the table's loot.
- Dynamic Trees: `DynamicTreesCompat.init` registers its hook as its first statement, with Minecraft types only
  (DynamicTreesCompat.java:122, `#dynamictrees:leaves`), then probes DT (`DTTrees.probe`, `DTLeafDrops.probe`).
  `DTLeafDrops.resolve` takes the species from the roll (orphan leaves: the family's common species), gives the seed
  only when the species drops seeds (fruit species give none), takes the 1-in-40 odds from `FCLogRules.fcWoodMeta` of
  the family's primitive log, uses DT's seasonal seed chance as a veto and strips the family's stick; a throw switches
  #027 off and answers `KEEP`.

```
[LEAVES] leaf drop hook registered: <hook>                         INFO (Dynamic Trees: "Dynamic Trees leaves (#027)")
[LEAVES] <id>: no sapling in #minecraft:saplings; its own leaf loot is kept                                    INFO
[LEAVES] <id>: FC block <n> owns its drops; its own leaf loot is kept                                          INFO
[LEAVES] <id>: FC's leaves drop <stack>, which is not a sapling; its own leaf loot is kept                     WARN
[LEAVES] the leaf drop rule failed for <state>; the table's loot is kept (logged once)                         WARN
                                                                                                (logger BTW-Leaves)
```

- Expected today: the registration line only, in a run with Dynamic Trees; no `[LEAVES] minecraft:` or
  `[LEAVES] dynamictrees:` line.
- Verification (2026-10-20): P-LEAF-V 32/32 (HEAD 12/32), P-LEAF-DT 10/10 (HEAD 2/10). The blood wood row reads 100
  saplings in 2000 breaks once the proof harness counts FC item entities; its HEAD reading had been blind.

---

## Inventory sync (InventoryBridge)

Added 2026-10-20 (CurseForge triage CF2-I1, maintainer ruling 2026-10-20 to fix it first; `STUB_CATALOGUE.md` (az)).
Code: `Forge/src/main/java/btw/forge/InventoryBridge.java`, `PlayerBridge.discardInventoryEdits`, and the call sites
below.

**The rule.** 1.5.2 has one live `ItemStack[]` that FC edits in place (vanilla/server InventoryPlayer.java:21), so a
stack FC takes from the inventory stays the inventory's stack for the whole FC call. The bridge holds FC snapshots,
and `sync()` runs on every `PlayerBridge.getOrCreate`, including the ones nested inside FC's own calls (an FC entity
query whose box holds the player, the ladder gate under `canPlayerEdit`). Until 2026-10-20 each of those replaced
FC's stacks with fresh copies, so the count change FC then made landed on a detached stack: a block placed without
sneaking against BTW terrain was never used up (CF2-I1; the phantom items, the blocks back after death and the ladder
case, CF2-I2, CF2-I3 and CF2-I5, followed from it). The invariant now:

- While a real slot is exactly what the bridge last baselined (the same `ItemStack` object, or empty then and now,
  with the same count, damage and NBT), `sync()` keeps the FC object in that slot.
- If FC changed that object or slot since the baseline, the change is written to the real slot first (server only),
  unless a shadow scope is open, whose owner lands or drops it.
- Only a real slot that changed is re-read, and then the real slot wins. A slot whose write-back could not land FC's
  state is re-read too.
- Every FC call ends its edits in exactly one way: a write-back lands them; `discardPendingEdits()` drops them (the
  call threw, or FC only shadowed a 1.20.1 action that owns the stack); or, for an FC-owned action with no
  write-back, the next sync lands them, as 1.5.2's live array would have.

```
public void sync();                                   // per slot: keepLive, else re-read (InventoryBridge.java:63, :77)
public void writeBack(ItemStack fcStack, int slot);   // the slot's own FC state; fcStack is no longer adopted (:239)
public void writeBackCurrentItem(ItemStack fcStack);  // the held slot (:248)
public void writeBackAll();                           // every slot FC changed since its baseline (:261)
public void discardPendingEdits();                    // FC's unwritten edits dropped: each changed slot re-read (:274)
public void beginShadow();  public void endShadow();  // a nested sync keeps FC's objects but writes nothing (:288, :293)
public void resendHeldSlot();                         // the held slot's real stack to the client (:308)
PlayerBridge.discardInventoryEdits()                  // the one-line helper for catch paths (PlayerBridge.java:244)
```

- Per slot, `keepLive` (:93) needs `mcUnchanged` (:112: the very baselined object with its baselined count, damage and
  NBT, so an in-place 1.20.1 edit such as BlockItem's shrink is a change) and `faithful` (:126: FC's object shows what
  the real stack holds); `fcChanged` (:353) compares with the FC baseline, and `rebaseline` (:340) records both sides.
  A client bridge never writes (`serverSide`, :141): a client-side FC edit is re-read, as before. A stack the
  write-through used up stays in the slot for the rest of FC's call (1.5.2's 0-size stack, which
  NetServerHandler.java:551-556 cleared after the packet), and the next sync clears it.
- `writeBack` no longer adopts a detached FC stack: under `keepLive` one exists only when the real slot changed (the
  real slot wins), FC replaced the slot (the replacement is written) or the slot could not take FC's state (it was
  re-read).
- `resendHeldSlot` sends the held slot as 1.5.2 did after every place packet (vanilla/server
  NetServerHandler.java:551-569): a `ClientboundContainerSetSlotPacket` for the open menu when it shows the slot, else
  for container 0, recorded as the remote view; never while an item is in use. It runs after the write-back of a
  consumed click (ProxyBlock.java:606, HookPolicy.java:443, the item step at ServerPlayerGameModeMixin.java:336) and
  after a REFUSED placement (:285). It repairs a client that predicted a change the server did not make, which
  `broadcastChanges` never does.

How each trigger resolves now (invresync design, section 2):

```
Trigger                                   | Nested sync                                 | Now
──────────────────────────────────────────┼─────────────────────────────────────────────┼─────────────────────────────────────────
ItemBlock and ItemReed (stakes, CF2-I6):  | the animal notify's entity query            | the sync keeps the object, the decrement
the notify comes before stackSize--       | (WorldBridge.extractFcEntity ->             | lands on it, and the caller's write-back
                                          | PlayerBridge.getOrCreate)                   | patches the MC stack in place
FC slabs (FCItemBlockSlab and two more):  | the same query, after FC's decrement        | written through (3 against 4),
stackSize-- before the notify             |                                             | rebaselined and kept
a ladder (CF2-I5)                         | canPlayerEdit -> isOnLadder -> onClimbable  | both syncs keep the object
                                          | (LivingEntityMixin.btw$gateLadder)          |
the last item (survival)                  | any                                         | the write-through empties the slot; the
                                          |                                             | used-up object stays until the next sync
```

Call sites, by group (invresync design, section 5):

```
Group | Sites                                                          | Since 2026-10-20
──────┼────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────
N     | syncs nested inside FC calls: WorldBridge entity queries and   | keep FC's objects (the fix); a pending edit on an unchanged
      | player lookups, the ladder gate, LivingEntityBridge, the       | slot is written through, or kept unwritten inside a shadow
      | entity-callback wrappers, FC damage and death paths            | scope
T     | a top-level sync, FC acts, a write-back follows: Stage 1 and 2 | the same results, on the kept object. A catch that skips its
      | (ProxyBlock.use, HookPolicy.activate), the item step,          | write-back now discards: HookPolicy.java:412,
      | btw$destroyBlock, ProxyItem use / finish / release / onUseTick | BlockBehaviorMixin.java:422 (attack), FCContainerMenu.java:417,
      | / hurtEnemy / mineBlock, ItemStackMixin, FCContainerMenu, the  | :694, ProxyAnimal.java:502, ProxyMob.java:737,
      | mobInteract of ProxyAnimal, ProxyMob and ProxyPathfinderMob,   | ProxyPathfinderMob.java:422, ProxyEntity.java:400, :450,
      | ProxyEntity playerTouch and interact                           | ProxyItem.java:645. ProxyEntity.interact gained its write-back
      |                                                                | (a windmill's dye is used up)
P     | FC shadows a 1.20.1 action that owns the stack:                | a shadow scope, then a discard: FC's edits never land on top of
      | ServerPlayerGameModeMixin.btw$useItem (onItemRightClick of     | 1.20.1's (ServerPlayerGameModeMixin.java:468-478;
      | vanilla items FC replaces), ItemStackMixin.btw$finishUsing     | ItemStackMixin.java:131-167, where only the bowl branch lands)
      | (onEaten)                                                      |
R     | read-only syncs: food, exhaustion, mining speed, the movement  | cheaper (an identity check per slot); FC sees the same state
      | gates, ServerPlayerMixin's per-tick and NBT syncs              |
C     | client bridges (EntityTrackerBridge, ProxyItem.onUseTick's     | never write; a client-side FC edit is re-read, as before
      | client branch, FCMiningSpeed's detached bridge)                |
```

- A shadow scope also wraps a creative FC item use (`ProxyBlock.runFcItemUse`, ProxyBlock.java:794-810), so 1.5.2's
  creative restore lands on the slot's own object however many nested syncs FC runs (review finding: a creative blood
  wood sapling planted near FC pigmen ran two and lost the last item).
- Caveats: `writeBackSlot` still lets FC win when the real slot changed after the baseline with no sync in between
  (pre-existing); `ProxyItem.finishUsingItem` applies a relative shrink after FC's `onEaten` and stays safe only while
  no FC food that uses up a stack of 2 or more syncs mid-call; the shadow paths stay a port rule (open question 1), as
  does "a failed FC call lands nothing" (open question 2), which 1.5.2 never had. Watch items: once, a client that
  joined after a stalled login missed slot updates until a full resync (never on a first-attempt join); one of four
  client runs missed a single stake click (timing).
- Verification (2026-10-20, stage INV of scratchpad `fu_runs/BASELINE_RUNS_FU.md`): the proof player is not in the
  level, so CF2-I1 needs a development client joined to the server. `placeconsume` 30/30 (GAP 7/7; 0/7 before), the
  client's hand lines matching the server; with the resend alone every GAP still failed, but the client showed the
  server's count; `windmill` 4/4; P-INV-S 3/3; 24 of 25 server plans SAME (the 25th, lso_p1, a pre-existing flaky
  campfire row); still 30/30 after I-1, the bed and door drops and wave C.

---

## Use order (FC item step and Stage 2)

Added 2026-10-20 (`STUB_CATALOGUE.md` (ba), `INTEGRATIONS.md` #033; landed after INV-RESYNC and rebased on it). Code:
`mixin/ServerPlayerGameModeMixin.java` (`btw$beginClick`, `btw$fcItemStep`, `btw$afterUseItemOn`), `ProxyBlock.java`
(`use`, `tryPlaceHeldItem`, `runFcItemUse`), `HookPolicy.activate` (Stage 1 and 2 of vanilla-id blocks since
2026-10-21, called by `BlockBehaviorMixin.btw$use`), `FcItemUseOrder.java`, `PlayerBridge.java` (the click frame).

**The rule.** One right-click on a block runs in the order of 1.20.1's `ServerPlayerGameMode.useItemOn`
(ServerPlayerGameMode.java:317-370): Forge's RightClickBlock (a cancel ends the click), the spectator branch,
`onItemUseFirst`, the sneak rule, the clicked block's `use`, then, unless the event denied the item or it is on
cooldown, the item step `ItemStack.useOn`. Apart from the off hand and Forge's gates this is 4.B's
`activateBlockOrUseItem` (vanilla/server ItemInWorldManager.java:376-407): the block first, unless the player sneaks
with an item, and the held item only when the activation declined. FC's activation (Stage 1, `onBlockActivated`) and,
when it declines, FC's item use on the block (Stage 2) run inside block use; FC's item use for the blocks Stage 2 does
not serve runs at the item step. One click runs at most one FC block activation and at most one FC item use, main hand
only, through the single FC `onItemUse` point, `ProxyBlock.tryPlaceHeldItem`. Until 2026-10-20 FC's `onItemUse` ran
at HEAD of `useItemOn`, ahead of all of it (`STUB_CATALOGUE.md` (aj), (at) caveat BM8).

```
# | Target (SRG)                                 | Hook                                         | Does
──┼──────────────────────────────────────────────┼──────────────────────────────────────────────┼──────────────────────────────────────────
1 | ServerPlayerGameMode.useItemOn m_7179_, HEAD | ServerPlayerGameModeMixin.btw$beginClick     | opens the click frame; never cancels
  |                                              | (ServerPlayerGameModeMixin.java:244-249)     |
2 | PlayerInteractEvent.RightClickBlock (Forge   | FcItemUseOrder (FcItemUseOrder.java:41-45)   | records useItem == DENY on the frame
  | event), LOWEST, server                       |                                              |
3 | blockstate.use: ProxyBlock.use (:567);       | Stage 1, then Stage 2 through                | main hand only (the off hand: PASS on the
  | BlockBehaviorMixin.btw$use (:256) ->         | ProxyBlock.tryPlaceHeldItem                  | server for a ProxyBlock, else 1.20.1's
  | HookPolicy.activate (:388)                   |                                              | use); Stage 2 refused under DENY
4 | useItemOn, INVOKE ItemStack.useOn m_41661_   | ServerPlayerGameModeMixin.btw$fcItemStep     | FC onItemUse where Stage 2 did not run at
  | (both calls, require = 2)                    | (:261-346), cancellable                      | this block; CONSUME on success
5 | useItemOn, RETURN                            | ServerPlayerGameModeMixin.btw$afterUseItemOn | finishes a block FC placed (Placement
  |                                              | (:368-421)                                   | validity); closes the frame
```

- **The item step** skips the off hand, BlockItems (their FC route is Stage 2; a REFUSED one ends the click there, see
  [Placement validity (WorldBridge)](#placement-validity-worldbridge)), items with no FC identity, a block where Stage 2
  already ran FC in this click (`fcItemUseRanAt`), and bone meal (`BoneMealPolicy.isFcBoneMeal`: `BoneMealItem.useOn`
  stays its route, see [Bone meal (BoneMealPolicy)](#bone-meal-bonemealpolicy)). It reaches every block without Stage 2
  (a 1.20.1 class that overrides `use`, a block with no FC identity), every block when the player sneaks with an item,
  and a block whose `use` declined without running Stage 2 (Dynamic Trees' branches). On success it opens an FC
  container (ContainerBridge), writes back every slot FC changed, resends the held slot (INV-RESYNC), syncs the
  ProxyBlockEntity, fires `ITEM_USED_ON_BLOCK`, closes the frame and returns CONSUME; FC false lets 1.20.1's `useOn`
  run (an axe strip or a hoe till where FC declined). Callback injects apply before redirects, so another mod's
  `@Redirect` of `useOn` stays compatible.
- **`tryPlaceHeldItem`** (ProxyBlock.java:657-705), in order: the white-dye guard (`BoneMealPolicy.mayUseAsFcItem`);
  the DENY check, which refuses with nothing recorded; the oak-door decline (Placement validity); `markFcItemUse(pos)`;
  FC `onItemUse` through `runFcItemUse` (:778-811: in survival as it is, in creative with 1.5.2's restore of damage and
  count inside a shadow scope; a throw is logged once and counts as false); `Stats.ITEM_USED` for the clicked item on
  true (vanilla/server ItemStack.java:127-130); the Stage2 record. It does not fire `ITEM_USED_ON_BLOCK`: a Stage 2
  success makes block use consume, which fires it, and the item step fires it itself.
- **The client** gets SUCCESS from `ProxyBlock.use` and predicts nothing; `MultiPlayerGameMode.useItemOn` sends the use
  packet whatever `use` returns (MultiPlayerGameMode.java:284-296), so the server decides the click alone (the
  ProxyBlock comment said otherwise until 2026-10-20).

The click frame (PlayerBridge.java:276-419; contract C5, extended by placement validity):

```
private static final class UseClick {             // per useItemOn call, innermost last (:313-325)
    long tick; boolean fcUseDenied; BlockPos fcUseRanAt; Stage2 stage2; BlockPos stage2At; }
public void beginUseClick(long tick);             // :355; drops frames of other ticks (a foreign cancel or a throw)
public void endUseClick();                        // :361; also called before each of the mixin's own cancels
public boolean isFcUseDenied();                   // :366
public void setFcUseDenied(boolean denied);       // :372; outside any useItemOn the verdict is kept loose
public void markFcItemUse(BlockPos pos);          // :382; nothing outside useItemOn
public boolean fcItemUseRanAt(BlockPos pos);      // :390; keyed by position
public enum Stage2 { NONE, PLACED, REFUSED }      // :331
public Stage2 stage2();                           // :396; NONE outside useItemOn
public void setStage2(Stage2 s[, BlockPos at]);   // :402, :407; nothing outside useItemOn
public BlockPos stage2At();                       // :416; PLACED only
```

- A RETURN inject does not run after an injector cancels, so the mixin closes the frame before each of its own
  cancels. Nested `useItemOn` calls in one tick stack. Outside any `useItemOn` (a deployer-style caller that posts
  RightClickBlock and calls `blockstate.use` itself) only the DENY verdict is kept, and every post overwrites it.
  Server thread only.
- `stage2` (placement validity, 2026-10-21) is set by `tryPlaceHeldItem` only when the clicked main-hand item is a
  1.20.1 BlockItem whose FC item is of the 4.B ItemBlock family; REFUSED only for a player who may build (`mayBuild`);
  the item step reads it only at the clicked block (`fcItemUseRanAt(hitResult.getBlockPos())`).
- Accepted residuals (`INTEGRATIONS.md` #033; the activation audit is next): 1.20.1's `use` of a vanilla class with an
  FC identity now comes before the FC item (redstone ore, an empty flower pot, signs, a tuning fork on a note block;
  TNT primed by flint and steel costs 1 durability, a fire charge primes it at once) (D6); the client's own `useItem`
  after an FC success (D8); the decline fallback (D4); an off-hand sneak (D9). A bed clicked in the Nether or the End
  explodes whatever is held: BedBlock.use consumes every server click.
- Verification (2026-10-20, stage I1): P-USE 39/39 (HEAD 21/39; GAP 18/18), P-HOOK P1a and P1b (flint and steel on a
  lever toggles the lever; the item under a RightClickBlock cancel does nothing); refmap `m_7179_` and `m_41661_`;
  the joined-client `placeconsume` 30/30, its stake row S1 placed through the item step (CF2-I6); every other plan
  SAME.

---

## Placement validity (WorldBridge)

Added 2026-10-21 (CurseForge triage CF2-P8a; `STUB_CATALOGUE.md` (bd), `INTEGRATIONS.md` #040). Code: `WorldBridge.java`
(`canPlaceEntityOnSide`, `checkNoEntityCollision`, `getBlockMetadata`, `isMetaFullyDerived`, the tripwire hook codec),
`ProxyBlock.tryPlaceHeldItem`, `mixin/ServerPlayerGameModeMixin.java` (`btw$fcItemStep`, `btw$afterUseItemOn`),
`PlayerBridge.java` (`Stage2`, `syncFromReal`, `canPlayerEdit`), Modern-Common `BlockTripWireSource.java` and
`BlockLog.java`.

**The rule.** BTW's placements through FC's code (FC ItemBlocks and FCItemPlacesAsBlock items, Stage 2 of a click on a
block FC models) follow 4.B's validity rules, and FC's refusal is final, as it was in 4.B, whose client never sent a
placement its own check refused (vanilla/client PlayerControllerMP.java:425-433). `WorldBridge.canPlaceEntityOnSide`
was a stub until 2026-10-21: it only checked that no entity of any kind stood in the cell, so the FC route put flowers
on stone and slabs and hooks into solid cells, and falling sand deleted rails.

- **`canPlaceEntityOnSide`** (WorldBridge.java:1920-1999; 1.5.2 World.java:3377-3407), in 1.5.2's order: (1) the
  entity check: the new block's collision box holds no entity that prevents spawning, other than an `entity` that is
  not a player, so the placing player counts (4.B's client pre-check passed a null entity, Common
  FCItemPlacesAsBlock :202), skipped for `skipEntities` (FC falling blocks); (2) a free target: air, water, lava,
  fire, a replaceable material, FC's detector logic blocks or stoked fire, while a cell FC does not model (post-1.5.2
  and modded blocks, bark and stripped logs) is free by 1.20.1's own `canBeReplaced(BlockPlaceContext)`; (3) an anvil
  may take a circuits cell; (4) otherwise the new block's `canPlaceBlockOnSide` must agree (the soil of a plant, the
  wall of a hook, the support of a rail). A throw is logged once and refuses.
- **`checkNoEntityCollision`** (one argument :1877, two arguments :1896; 1.5.2 World.java:1990-2013): true unless a
  live entity other than `except` that prevents spawning is in the box: 1.20.1's `Entity.blocksBuilding` (living
  entities, boats, minecarts, falling blocks, primed TNT, end crystals) or the FC object's `preventEntitySpawning`
  (windmills, water wheels, platforms, dynamite, mining charges). Removed entities and spectators never count; items
  and XP orbs no longer obstruct; a scan failure refuses. Its other callers (FC's spawn checks, the windmill and the
  water wheel, the ghast, the beacon) get the same rule.
- **Metadata:** `getBlockMetadata` (:219-235) never reads the overlay for a fully derived block and drops a stale entry
  (`isMetaFullyDerived`, :284-300, now with the tripwire hook). The hook codec (derive :533-542, write :809-818):
  direction | 4 attached | 8 powered.
- **Base ports** (Modern-Common, 1.5.2 parity): `BlockTripWireSource.canPlaceBlockOnSide`, `canPlaceBlockAt` and
  `onBlockPlaced` (BlockTripWireSource.java:32-60: a hook goes on the side of a normal cube and faces away from it),
  and `BlockLog.onBlockPlaced` (BlockLog.java:26-35: the axis follows the clicked face; FCBlockLog inherits it, so
  FC-placed logs, log2's included, lie along the clicked side).

The verdict, on the click frame (see [Use order (FC item step and Stage 2)](#use-order-fc-item-step-and-stage-2)):

```
Step                         | Where                                     | Does
─────────────────────────────┼───────────────────────────────────────────┼───────────────────────────────────────────────────────
the oak door (FCItemDoor,    | ProxyBlock.tryPlaceHeldItem :667-668      | declined before FC runs, nothing recorded: 1.20.1's
item 324)                    | (isPortGapDoorItem :771)                  | DoorItem places it (Modern-Common
                             |                                           | ItemDoor.placeDoorBlock is a stub)
PLACED or REFUSED            | tryPlaceHeldItem :673-703                 | for a 1.20.1 BlockItem whose FC item is an ItemBlock
                             | (isItemBlockFamily :756)                  | or FCItemPlacesAsBlock: PLACED with the cell FC filled
                             |                                           | (read before FC runs), or REFUSED (false or a throw)
                             |                                           | for a player who may build
REFUSED is final             | ServerPlayerGameModeMixin.btw$fcItemStep  | at the clicked block: resend the held slot, close the
                             | (:272-290)                                | frame, FAIL; 1.20.1's BlockItem does not place it
                             |                                           | instead
finish a PLACED block        | ServerPlayerGameModeMixin                 | the look-based facing, only for FC's own placement,
                             | .btw$afterUseItemOn (:368-421)            | never on a tripwire hook and only to a facing the
                             |                                           | block survives in; an oak trapdoor or gate against a
                             |                                           | live signal opens and powers (ruling 4)
```

- An adventure-mode refusal records nothing, so 1.20.1's CanPlaceOn rule decides at the item step
  (ForgeHooks.onPlaceItemIntoWorld). 1.20.1's own placements (stairs, doors, gates, beds and observers placed the
  Minecraft way) are no longer turned round, and the facing no longer depends on the stack size.
- **The mid-air rule** (Modern-Common `EntityPlayer.canPlayerEdit`): `PlayerBridge.syncFromReal` copies `inWater`
  (PlayerBridge.java:213-217), so a swimming player may place and FC squids see a swimming player as in water; a
  riding player is answered from the real player (`canPlayerEdit`, :838-843; FC's `ridingEntity` stays unset, since
  FCEntitySquid could dismount through it). Other mods' fake players are never ticked, so `onGround` stays false and
  their FC-route placements are refused (maintainer ruling 2026-10-20: a known issue; Create's deployer calls `useOn`
  directly and is not affected).
- Known: glass counts as a normal cube (decision 7(b), row H4 KNOWN); FC-route stairs face the wrong way until decision
  6(c) (row H14 KNOWN); sneak placement keeps 1.20.1's rules (8(b)); FC placements post no EntityPlaceEvent (9(b));
  FC-route trapdoors take meta 0 until decision 3 stage 2; an FC-placed hook shows as attached only after a neighbour
  update (4(b')). Not runtime-tested: swimming or riding players, a real player's own collision box, adventure-mode
  CanPlaceOn, a hook saved before this change.
- Verification (2026-10-21, stage C): P-PLACE 27/29 (8/29 before; the 2 FAILs are the KNOWN rows H4 and H14), with
  P8-1 to P8-5 (CF2-P8a: flowers refused on stone, a smooth stone slab and cobblestone; the deepslate and grass
  controls); the joined client `P-PLACE-C` (hand `minecraft:tripwire_hook x1`); the place sweeps change 52 lines, each
  by a 1.5.2 rule (logs on a north face get axis z; saplings, grass, fern, dead bush, flowers, mushrooms, ladder and
  cactus refused on stone's top).

---

## Beds and doors (VanillaPairDrops)

Added 2026-10-20 (maintainer ruling 2026-10-20: beds and doors take 1.20.1 drops; CurseForge triage CF2-DR1, CF2-DR6;
`STUB_CATALOGUE.md` (bb), `INTEGRATIONS.md` #039). Code: `Forge/src/main/java/btw/forge/VanillaPairDrops.java`,
`mixin/ServerPlayerGameModeMixin.java` (`btw$destroyBlock`, `btw$afterPairRemoved`), `mixin/BlockPlayerDestroyMixin.java`,
`mixin/ItemStackMixin.java` (`btw$mineBlock`), `ProxyItem.onBlockDestroyedLive`, Modern-Common `World.java` and
`Block.java`, `WorldBridge.UsesModernBlockLoot`.

**The rule.** A bed or a door is one item in two cells, and 1.20.1 handles that itself: each loot table yields the item
from one half only, removing either half makes the other half's `updateShape` return air and pops it with its own
loot, and a creative break clears the partner without drops. FC's harvest dropped the legacy id of whichever half it
removed, and 1.20.1 then popped the partner: two items, the red bed for every colour, iron and gold from a melted iron
door. So a ruled block takes 1.20.1's own break, drops and partner handling (pattern E, by the ruling), and FC keeps
what is not a drop: dig speed, harvest exhaustion, tool wear and the panic of FC animals.

- **The predicate** (contract C-BD1): `VanillaPairDrops.isRuled(Block)` (VanillaPairDrops.java:95), a non-ProxyBlock
  whose default state has `BED_PART` or `DOUBLE_BLOCK_HALF` (`isPairBlock`, :84) and that has an FC identity, cached
  per block once FC is ready. Today: the 16 beds (legacy 26), OAK_DOOR (64) and IRON_DOOR (71). FC's own door, the
  ProxyBlock 1036, is outside the rule.

```
#  | Target                                     | Hook                                          | Does
───┼────────────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────────────────────────────────
-  | ServerPlayerGameMode.destroyBlock m_9280_, | ServerPlayerGameModeMixin.btw$destroyBlock    | returns without cancelling for a ruled state:
   | HEAD                                       | (ServerPlayerGameModeMixin.java:118-125)      | 1.20.1 posts the BreakEvent, runs
   |                                            |                                               | playerWillDestroy, the loot and the partner pop
S1 | ServerPlayerGameMode.removeBlock (Forge-   | btw$afterPairRemoved (:105-116), locals       | FC's animal panic (afterPlayerBreak, :122),
   | added, remap = false), RETURN              | captured, CAPTURE_FAILHARD                    | after the removal, before the drops
S2 | Modern-Common World.UsesModernBlockLoot    | WorldBridge.UsesModernBlockLoot (:3236); read | FC's drop entry points at a live ruled cell
   | (World.java:887-895, base false), next to  | by Block.dropBlockAsItemWithChance            | (the saw, the mining charge) drop that cell's
   | DropModernBlockLoot (:879-885)             | (Block.java:653-654) and                      | 1.20.1 loot through DropModernBlockLoot
   |                                            | Block.DropItemsOnDestroyedByExplosion         |
   |                                            | (:1534-1545)                                  |
B3 | Block.playerDestroy m_6240_: HEAD, and its | BlockPlayerDestroyMixin (:39, :49)            | FC's harvest exhaustion (chargeHarvest, :145;
   | causeFoodExhaustion call                   |                                               | 0.025 by default); 1.20.1's 0.005 zeroed
W  | ItemStack.mineBlock                        | ItemStackMixin.btw$mineBlock (:353),          | FC's wear on the live FC stack for an FC item
   |                                            | ProxyItem.onBlockDestroyedLive (:739)         | on an FC-identity block; 1.20.1's
   |                                            |                                               | Item.mineBlock skipped
```

- S2 keys on the live cell (a ruled block at the position whose legacy id is the dropped id; it never loads a chunk),
  so an FC drop that comes before the removal gives one item, and the partner pops with the other half.
  `DropItemsOnDestroyedByExplosion` got 1.5.2's base body (vanilla/server Block.java:1730-1747) for ruled cells only;
  every other block keeps the empty stub until CF2-DR5, which then deletes one clause. S2 is also the seam the
  collapsed-identity rule (triage CF2-DR3) can extend.
- W: a tool BTW breaks while mining fires Forge's `PlayerDestroyItemEvent`.
- Hook ownership: bed NEIGHBOR is RULED(5) through `isRuled` (BD's recommendation, pending the maintainer's answer to
  BD Q7); doors are RULED(4) first. The drop-parity follow-up D7 must drop its FCBlockBed, FCBlockDoor and
  FCBlockDoorWood rows.
- FC's grids and storage still see one bed for the 16 colours (see
  [Recipe bridge (RecipeBridge, WorkbenchFallback)](#recipe-bridge-recipebridge-workbenchfallback)); only the drop is
  the bed's own.

```
[SELF-AUDIT/pairdrops] <block> id=<legacy id> class=<simple name>: break, drops and partner = 1.20.1    (each block)
[SELF-AUDIT/pairdrops] 18 ruled blocks (16 BedBlock, 2 DoorBlock, 0 other); FC drops of ids [26, 64, 71] at a live
  cell take that cell's 1.20.1 loot; 0 warnings (0 expected)                                (logger BTW-PairDrops)
```

- `VanillaPairDrops.audit` (:174) runs from `BridgeSelfAudit.runServerStart` (BridgeSelfAudit.java:62-63). It WARNs on
  W1 a ruled block that is neither a BedBlock nor a DoorBlock, W2 a ruled block whose 1.20.1 class does not override
  `neighborChanged` while its FC class declares `onNeighborBlockChange`, W3 a legacy id that maps both ruled and
  unruled blocks, W4 26, 64 or 71 with no ruled block.
- Known: a door whose top half burns first drops itself (1.20.1; 4.B dropped nothing); an iron door's top broken by
  hand drops the door (1.20.1 and 4.B agree); FC's ProxyBlock door 1036 would still double-drop if placed (not
  reachable in survival); a mod that changes `removeBlock`'s locals fails this mixin at load (CAPTURE_FAILHARD).
- Verification (2026-10-20, stage BDD): P-BEDDOOR 288/288 (GAP 63/63; 225/288 before), the audit line above, every
  regression plan SAME.

---

## Dyes (LegacyVariants spellings, ModernSpellingRecipes)

Added 2026-10-20: white dye first (`STUB_CATALOGUE.md` (aw); `INTEGRATIONS.md` #017, #025), then black, brown and blue
dye (maintainer ruling 2026-10-20: colour only, with 1.5.2 subtype stacking restored; `STUB_CATALOGUE.md` (bc),
`INTEGRATIONS.md` #038). Code: `Forge/src/main/java/btw/forge/LegacyVariants.java`, `ModernSpellingRecipes.java`,
`BTWForgeMod.commonSetup`, `RecipeBridge.legacyAlias`, Modern-Common `ItemDye.java`, `MerchantRecipeList.java` and
`ItemStack.isItemDamaged`.

**The rule.** 1.13 split four 1.5.2 dye subtypes into items of their own: dye 15, 0, 3 and 4 are bone meal, the ink
sac, cocoa beans and lapis, and 1.20.1 adds white, black, brown and blue dye beside them. In 4.B those subtypes have
roles beyond colour (fertilizer, chicken feed and dog food, the farmer's and the librarian's trades, the book and
quill, chocolate, polished lapis). The 1.20.1 dyes are colours only: each keeps an FC stack of its own, the 4.B item
keeps every 4.B role, and the dye gets every FC colour recipe of its subtype.

- **The spelling rule.** `MODERN_SPELLING = 0x100` (LegacyVariants.java:61). `spelling(id, meta, item)` (:110)
  registers the item at FC (id, meta | 0x100) in both directions and records the subtype only when both directions
  hold (the first registration of a key wins on each side). White, black, brown and blue dye are FC 351:271, 256, 259
  and 260 (:219-226), registered before the DyeItem loop, so (351, d) keeps its 4.B item. Items only: a block
  subtype's damage is FC metadata. A positive short, never 32767 (the 1.5.2 wildcard).
- FC's colour code masks the damage (`BlockCloth.getBlockFromDye`, `~d & 15`), so sheep, collars, windmills and wool
  read the dye's colour; FC's (id, damage) tests tell the dye from the 4.B item, and FC's merges tell every spelling
  apart (through ItemDye's subtypes).
- `baseSubtype(meta)` (:272) strips the bit (271 -> 15): RecipeBridge builds its one-way aliases from it (see
  `RecipeBridge.legacyAlias` under Recipe bridge). `spelledSubtypes()` (:277) is a read-only copy of legacy id ->
  spelled base subtypes, today `{351=[0, 3, 4, 15]}`.
- **ItemDye** (Modern-Common ItemDye.java:5-17): `setHasSubtypes(true); setMaxDamage(0)`, as 1.5.2's; FCItemDye
  inherits them.
- **The merchant slot rule** (Modern-Common MerchantRecipeList.java:9-46): 4.B's slot tests compare the damage of a
  subtyped item (`validFirst` :34, `validSecond` :41), so white dye never sells as the farmer's bone meal, black dye
  never as the librarian's ink sac, and the brown wool knit and blood wood sapling trades take only their own subtype;
  the log trades stay lenient until the setHasSubtypes audit. 4.B's index strictness is not ported: it relies on
  GuiMerchant selecting the trade from the offered items, while the port's screen selects a trade only on a click
  (client/FCContainerScreen.java:313-333).
- **`isItemDamaged`** (Modern-Common ItemStack.java:110-118): an item that declares subtypes and has no durability is
  never damaged, 1.5.2's rule, so dyes, FC wool, candles, vases and slabs stack in FC hoppers and chests again (option
  A of the dye ruling); items without the subtype flag keep `damage > 0` until the setHasSubtypes audit.

```
static final int LegacyVariants.MODERN_SPELLING = 0x100;
static int LegacyVariants.baseSubtype(int meta);                       // meta & ~MODERN_SPELLING
static Map<Integer, Set<Integer>> LegacyVariants.spelledSubtypes();    // {351=[0, 3, 4, 15]}, read-only
static void ModernSpellingRecipes.apply();                             // once, from BTWForgeMod.commonSetup
```

**ModernSpellingRecipes** (ModernSpellingRecipes.java:66) runs once at common setup on both physical sides, enqueued
from `BTWForgeMod.commonSetup` (`event.enqueueWork(ModernSpellingRecipes::apply)`, BTWForgeMod.java:117-118), after FC
init and before RecipeBridge (ServerStarting) and JEI read FC's lists. It is not its own listener: the first draft's
annotated MOD-bus `Setup` class was registered but never called (cause not found) and was removed when it landed. The
colour-family rule, with no hand list: a recipe that takes dye d is a colour role when FC makes the same recipe (class,
shape, other ingredients, output item) with another dye colour, and each member at a spelled subtype gets a twin that
takes (351, d | 0x100).

- Grids (FC's crafting list and the soulforge's anvil list; plain ShapedRecipes and ShapelessRecipes only): the twins
  are appended after FC's list, so the `fc_recipe_<index>` ids (RecipeBridge.injectCraftingRecipes) and the client's
  FCCraftingRecipes.Custom index stay put.
- Bulk lists (cauldron, stoked cauldron, crucible, stoked crucible, millstone): each twin is inserted right after its
  original, so FC's first match is 4.B's; a metadata-exclusive recipe (`m_bMetadataExclusive`) already takes every
  spelling and is never a member.
- One-colour recipes get no twin and stay with the 4.B item: the book and quill, chocolate milk, polished lapis,
  chocolate, chicken feed, dog food.

```
[MODERN-DYES] colour twins for the 1.20.1 dye spellings {351=[0, 3, 4, 15]}: crafting +<n> (one colour, 4.B item
  only: [...]) anvil +<n> (...) Cauldron +<n> (...) CauldronStoked +<n> (...) Crucible +<n> CrucibleStoked +<n>
  MillStone +<n>                                                                          (logger BTW-ModernDyes)
```

- 2026-10-20 values: crafting +12 (8 one-colour recipes kept), anvil +0 (1 kept), Cauldron +9 (1 kept),
  CauldronStoked +0 (1 kept), Crucible, CrucibleStoked and MillStone +0. WARNs: FC not initialised; no spelling
  registered (no twins); a list it cannot reach, which stays as it was. Never throws.
- Known limits: dyes saved in FC storage before the change reload as the ink sac, cocoa, lapis or bone meal (no
  migration; R1); an older jar reads Damage 256, 259, 260 or 271 as item 351 with that damage (R2); in the 2x2 grid
  FC's colour recipes match FC candles, wool, vases and wool slabs by item only (pre-existing; R11).
- Verification (2026-10-20): P-WD 40/40 (HEAD 16/40) and the bone-meal plan 96/96 (its two dropped-white-dye checks
  flipped); P-DYE3 60/60 (35/60 before); P-DYE3Q2 10/10 (6/10 without the `isItemDamaged` change); P-RV
  `sweep fc total=675 skipped=3 fail=0` and `sweep ns minecraft total=708 pass=663 fc=18 fail=27` (32 failed before
  the dye change; scratchpad `fu_runs/INV/C_after/P-RV.txt`, `fu_runs/INV/cur/P-RV.txt`).

---

## Hook ownership (HookPolicy)

Added 2026-10-21 (wave C: hook ownership, beds, the anvil, golems, the enchanting table, fence gates and farmland, then
lava; maintainer rulings 3 and 4 of 2026-10-19; `STUB_CATALOGUE.md` (be), (bf); `INTEGRATIONS.md` #036, #037). Code:
`Forge/src/main/java/btw/forge/HookPolicy.java`, `mixin/BlockStateHookMixin.java`, `mixin/BlockBehaviorMixin.java` and
`mixin/BlockMixin.java` (the NOTIFY executors), `mixin/FenceGateBlockMixin.java`, `mixin/FarmBlockMixin.java`,
`BedSpawnPolicy.java`, `mixin/ServerPlayerMixin.java` (the rest check), `LavaRules.java`, `mixin/LavaFluidMixin.java`,
`mixin/FluidInteractionRegistryMixin.java`, `BridgeSelfAudit.auditHookPolicy`.

**The rule.** Shaped like TickPolicy: each block hook of a managed state has one owner, decided once per state from 4.B
at `initCache` and cached on the state. 1.20.1 moved block behaviour into per-class overrides of `use`, `attack`,
`onPlace`, `onRemove`, `neighborChanged`, `entityInside`, the signal methods, `stepOn` and `fallOn`, reached through
the BlockStateBase forwarders. Until 2026-10-21 BlockBehaviorMixin ran FC from the base bodies only, so whether FC ran
depended on 1.20.1's class layout (231 override-without-super pairs bypassed FC, 108 of them hooks 4.B changed), and
nothing could say "FC replaces 1.20.1 here" (the anvil, golems) or "1.20.1 owns this hook" (FC block-entity bodies on
a 1.20.1 block entity, which crashed every beacon and ender-chest removal; beds; door, trapdoor and gate redstone).

```
Owner     | Bits | Meaning
──────────┼──────┼──────────────────────────────────────────────────────────────────────────────────────────────────
UNDECIDED | 0    | no decision (FC not ready at the bake, a ProxyBlock, an unmapped block, a failed decision): the
          |      | executors keep their dynamic lookup, so nothing regresses on a stale cache
NOTIFY    | 1    | FC runs where the 1.20.1 chain reaches the base body (non-overriding or super-calling classes):
          |      | the behaviour before 2026-10-21, made explicit
FC        | 2    | FC replaces 1.20.1 on the server at the BlockStateBase forwarder and the 1.20.1 body is cancelled
          |      | (USE and PLACE, the only hooks with an FC row)
VANILLA   | 3    | FC's block never runs for the hook, not even through a super call (USE: the held FC item,
          |      | Stage 2, still runs where the base body is reached, as 4.B ran it whatever the block did)
```

- Two bits per hook in one int, ten hooks (HookPolicy.java:121-141): USE (`use` -> onBlockActivated), ATTACK
  (`attack` -> onBlockClicked), PLACE (`onPlace` -> onBlockAdded), REMOVE (`onRemove` -> breakBlock), NEIGHBOR
  (`neighborChanged`), INSIDE (`entityInside`), SIGNAL (`getSignal`, `getDirectSignal`, `isSignalSource`), STEP
  (`stepOn`), FALL (`fallOn`), RESIST (`getExplosionResistance`, left UNDECIDED until Phase 5, explosions).

```
# | Rule (HookPolicy.decide :216-254, in order)       | Owner   | Rows today
──┼───────────────────────────────────────────────────┼─────────┼───────────────────────────────────────────────────────
1 | FC not ready, a ProxyBlock, legacy id 0 or an     | (none)  | unmanaged: every hook UNDECIDED
  | empty blocksList slot                             |         |
2 | a RemovedContent T1 block (D6)                    | VANILLA | every hook of the stubbed (T1) blocks
3 | the block-entity guard (isTeGuarded :272): USE    | VANILLA | every FC BlockContainer on a vanilla id (the note
  | and REMOVE of a state whose FC block is a         |         | block, furnace, ender chest, beacon, spawner, skulls,
  | btw.modern.BlockContainer                         |         | end portal, ...)
4 | RULED and ADAPTER rows, permanent                 | VANILLA | RULED(3) bed USE (ruling 3); RULED(4) door, trapdoor
  | (ruledRule :277)                                  |         | and gate NEIGHBOR (ruling 4); RULED(5) NEIGHBOR of
  |                                                   |         | VanillaPairDrops.isRuled (the beds); ADAPTER(D16b)
  |                                                   |         | gate USE; ADAPTER(D9b) farmland FALL
5 | PENDING metadata prerequisites (pendingRule :290) | VANILLA | door USE (the door codec), cake USE, workbench USE
  |                                                   |         | (D12), flower pot USE, detector rail INSIDE
6 | EQUIVALENT: 1.20.1 already runs 4.B's effect      | NOTIFY  | stairs, redstone ore USE / ATTACK / STEP, FallingBlock
  | (equivalentRule :305)                             |         | PLACE, lamp NEIGHBOR
7 | the general rule                                  | FC or   | FC iff the 1.20.1 class overrides the hook and the
  |                                                   | NOTIFY  | runtime FC class declares it in FC code (below
  |                                                   |         | btw.modern): today the three anvils' USE and the
  |                                                   |         | carved pumpkin's and jack o'lantern's PLACE
```

- `decide` is pure, as TickPolicy.decide: it reads the state, FC's static block table, the 1.20.1 and FC classes and
  the static RemovedContent table, and touches no Blocks or Items at class init (initCache runs inside Blocks' static
  init, before FC is ready).
- **The forwarders** (`BlockStateHookMixin`, priority 1100, after other mods' HEAD vetoes) cache `decide` at
  `initCache()` TAIL (BlockStateHookMixin.java:67; never throws: a failure leaves every hook UNDECIDED) through the
  duck interface `HookPolicy.Access`, and enforce the FC rows on the server with cancellable HEAD injects on the `use`
  (:86) and `onPlace` (:98) forwarders, the one pass of every activation and every placement whatever the class
  overrides. The client keeps the 1.20.1 body as its prediction.
  - FC-owned USE (`runFcOwnedUse`, HookPolicy.java:463; the three anvils, decisions D2(c) and R15): the off hand gets
    PASS; an empty-handed sneak runs 1.20.1's use (AnvilMenu, the repair menu); otherwise FC's activation
    (`activate`), whose PASS lets the item step follow in 4.B's order; a Stage 1 throw answers 1.20.1's use.
  - FC-owned PLACE (`runFcOwnedPlace`, :483; the carved pumpkin and the jack o'lantern, D3(a)): FC `onBlockAdded`, and
    the 1.20.1 body, the golem check, is always cancelled, also on a throw, so no golem forms from a pumpkin or a jack
    o'lantern on any placement path (LevelChunk.setBlockState and Forge's captured-placement replay, so dispensers
    too). A jack o'lantern placed from now on goes out next to water, as in 4.B. Villager-spawned golems are outside
    this rule.
- **The NOTIFY executors** (`BlockBehaviorMixin`: neighborChanged :171, onPlace :197, onRemove :219, use :256,
  entityInside :303, the signal methods :329, :353, :385, attack :403; `BlockMixin`: stepOn :103, fallOn :137) run FC only
  when `HookPolicy.notifyRuns(state, hook)` (HookPolicy.java:342): NOTIFY, UNDECIDED, or FC on a hook with no
  forwarder inject. StairBlock's base delegation gets the base block's owner.
- **The catch.** Every FC call of an executor is caught (`HookPolicy.warnOnce`, logger `BTW-Hook`, once per throw
  site): an FC failure answers the 1.20.1 result and never escapes `LevelChunk.setBlockState` or a packet handler.
  Only an FC-owned PLACE still cancels on a throw. A throw on a click (Stage 1, attack) discards FC's pending
  inventory edits (see [Inventory sync (InventoryBridge)](#inventory-sync-inventorybridge)).
- **USE** (`btw$use`, BlockBehaviorMixin.java:256-297): `stage1 = notifyRuns(state, USE) && !isTeGuarded(fcBlock)`,
  then `HookPolicy.activate` (HookPolicy.java:388-446), shared with the FC-owned executor: Stage 1 when allowed;
  Stage 2 when it declined or did not run (never for a twin); the menu, the write-back and the held-slot resend; a
  Stage 1 throw returns null, so the 1.20.1 result and the item step follow. A VANILLA USE owner therefore drops
  Stage 1 only: a BlockItem clicked against a spawner, a skull, an end portal or a removed T1 block now places through
  FC's ItemBlock (no proof row).
- **REMOVE** (`btw$onRemove`, :219-250) applies the block-entity guard also to a state without a cached owner and
  passes FC the old state's derived metadata (it passed 0). **ATTACK** (`btw$attack`, :403) gets a synced
  PlayerBridge (it got none).
- The block-entity guard keys on the FC class alone, never on the block entity in the world, so it never lifts by
  itself: when Phase 4 bridges FC tile entities onto 1.20.1 block entities, it must narrow with it. Until then 6 known
  gaps run 1.20.1: note and furnace USE, ender chest and beacon USE and REMOVE.

**Rulings and adapters:**

- **Beds** (RULED(3), ruling 3; tag `beds_sleep_no_spawn`, `INTEGRATIONS.md` #037): bed USE is 1.20.1's sleep, with
  the night skip, the action-bar messages and the Nether and End explosion. `BedSpawnPolicy.onSetSpawn`
  (BedSpawnPolicy.java:59-75), a Forge `PlayerSetSpawnEvent` listener, cancels every non-forced spawn at a block in
  `#minecraft:beds` or one that reports `isBed`; it never loads a chunk and never throws. Forced spawns (`/spawnpoint`),
  respawn anchors and any spawn at a block that is not a bed stand (the FC beacon bind passes `forced` false, Server
  FCBlockBeacon.java:93, and stands as a non-bed spawn; it is unreachable until hup-43, CATALOGUE 2, 2.11); a bed
  spawn set before this change is used once more and then dropped.
  `ServerPlayerMixin.btw$fcHostilesPreventRest` (ServerPlayerMixin.java:343-360), at the INVOKE of
  `Player.startSleepInBed` inside `ServerPlayer.startSleepInBed`, answers NOT_SAFE for a live ProxyMob whose FC entity
  is an EntityMob within +-8 / +-5 / +-8 (1.5.2's rule; FC hostiles are Mobs, not Monsters, so 1.20.1's own check never
  saw them); creative players are exempt. `PlayerMixin.btw$disableBeds` is deleted (CATALOGUE 2, 2.6). Phantoms stay
  off by default (`doInsomnia` false, WorldGamerulesHandler.java:58).
- **Doors, trapdoors and gates** (RULED(4), ruling 4): NEIGHBOR keeps 1.20.1 redstone (OAK_DOOR, IRON_DOOR,
  OAK_TRAPDOOR, OAK_FENCE_GATE), the oak ones included, although 4.B's wooden ones ignore redstone.
- **Fence gates** (ADAPTER(D16b)): gate USE is 1.20.1's (the per-wood sounds and the sculk game event), and
  `FenceGateBlockMixin` (FenceGateBlockMixin.java:45-49), at the RETURN of `FenceGateBlock.use` on the server, updates
  the neighbours of a gate with an FC identity, as FCBlockFenceGate's flag-3 write did.
- **Farmland** (ADAPTER(D9b); tags `hook_ownership` and `farmland_reversion`): FALL is 1.20.1's, and `FarmBlockMixin`
  (FarmBlockMixin.java:86-93: `@ModifyArg` index 3 on `ForgeHooks.onFarmlandTrample` in `fallOn`, `remap = false` on
  the `@At`) lowers the fall distance by 0.25, so 1.20.1's one roll uses 4.B's 0.75 threshold: one trample roll per
  fall (it rolled 1.20.1's and then FC's).
- **Bed NEIGHBOR** (RULED(5)): BD's recommendation, pending the maintainer's answer to BD Q7 (one line in
  `HookPolicy.ruledRule` and its `RULED_IDS` entry).
- **The enchanting table** works: FCGameplayBridge's cancel is deleted (FCGameplayBridge.java:47-50; 4.B kept the
  table).

**Lava** (`LavaRules`, logger `BTW-Lava`):

```
# | Target                                         | Hook                                         | Does
──┼────────────────────────────────────────────────┼──────────────────────────────────────────────┼───────────────────────────────────────────
1 | FluidInteractionRegistry.<clinit>, the first   | FluidInteractionRegistryMixin (:50-55) ->    | gates Forge's lava+water entry in place:
  | addInteraction call (ordinal 0, argument 1;    | LavaRules.contactRule (LavaRules.java:105)   | hardensOnContact (:130) and Forge's water
  | remap = false, no refmap entry)                |                                              | test; a source -> obsidian, flowing amount
  |                                                |                                              | >= 4 (4.B meta 1-4) -> the FC lava pillow;
  |                                                |                                              | thinner and falling lava keeps flowing
2 | LavaFluid.spreadTo m_6364_: argument 3 of      | LavaFluidMixin (:90-98) ->                   | lava flowing down onto water: 1.20.1's
  | ForgeEventFactory.fireFluidPlaceBlockEvent     | LavaRules.flowOntoWater (:152)               | stone -> the pillow
3 | LavaFluid.canBeReplacedWith m_5486_, HEAD      | LavaFluidMixin (:100-106)                    | false: no fluid displaces lava (1.5.2
  |                                                |                                              | BlockFlowing.java:443-447)
```

- The gated entry keeps its first-match place, ahead of basalt and every interaction registered later, and a declined
  contact keeps LiquidBlock's flow tick, so lava 4.B would not harden keeps flowing and recedes when unfed. Without
  row 3, water would flow into the falling lava 4.B leaves unhardened. The pillow is named by its FC field
  (`fcBlockLavaPillow`, through FcRefs); while it does not resolve, the cell takes 1.20.1's cobblestone or stone, with
  one WARN. Never throws into the fluid engine. Re-verify the ordinal-0 javap on a Forge bump.
- `[LAVA] 4.B lava contact rule installed on Forge's lava+water interaction: source -> obsidian, flowing meta 1-4 ->
  FC lava pillow, meta 5-7 and falling lava keep flowing` is logged at the first fluid update (LiquidBlock.onPlace and
  neighborChanged first load FluidInteractionRegistry), not at start-up, so a fresh log without it is not a failure;
  `[LAVA] ... NOT installed` means Forge's first interaction was not lava's.
- The tick table's row 5 (LavaFluid.randomTick) is unchanged. HookPolicy must keep LAVA (10) and WATER (8) `onPlace`
  and `neighborChanged` NOTIFY, never FC-owned, or the forwarder would cancel `LiquidBlock.onPlace`.
- Known: 4.B fizzed on every water contact, also when nothing hardened; 1.20.1 fizzes only with a hardening. No plan
  exercises breaking a pillow.

```
[SELF-AUDIT/hook] <block> id=<legacy id> <HOOK>=<OWNER>[<rule>] ... rest=NOTIFY[general]                (each block)
[SELF-AUDIT/hook] <block> (id <n>): <FC class>.<HOOK> stays 1.20.1's until its block entity is bridged (Phase 4)
[SELF-AUDIT/hook] <n> blocks, <n> FC-owned and <n> VANILLA hook pairs, rows {...}, <n> known block-entity gaps,
  <n> warnings (0 expected)                                                                 (logger BTW-SelfAudit)
```

- `auditHookPolicy` (BridgeSelfAudit.java:291) runs in `runServerStart` right after the tick audit (:60). WARNs: (a) a
  pair the general rule hands to FC that no row expects (`EXPECTED_FC`: the anvils' USE, the carved pumpkin's and the
  jack o'lantern's PLACE); (b) a block-entity-guarded hook FC declares, other than the 6 known gaps (those log at
  INFO); (c) a cached owner that differs from `decide` (BlockStateHookMixin not applied), or states of one block
  decided differently; (d) a RULED or ADAPTER row on a block that no longer maps to the legacy id its ruling names, or
  a ruled id with no row (`RULED_IDS`); (e) an expected FC row that no block of its legacy id carries.
- 2026-10-21 values: `242 blocks, 5 FC-owned and 182 VANILLA hook pairs, rows {ADAPTER(D16b)=[107], ADAPTER(D9b)=[60],
  RULED(3)=[26], RULED(4)=[64, 71, 96, 107], RULED(5)=[26]}, 6 known block-entity gaps, 0 warnings (0 expected)`.
- Queued: P3(iii) the detector rail, P3(iv) the lamp's notify, D12(c) the crafting-table hardpoint veto; P4 (the door
  codec, then door USE; the cake; the workbench; the flower pot; the ProxyBlockEntity bridge for the beacon and the
  ender chest); P5 (pistons, explosions, RESIST).
- Verification (2026-10-21, stage C): P-HOOK 34/36 (20/36 before wave C; the 2 FAILs are the INVERTED rows P0b and P0c,
  which is the fix), with the bed rows P2g, P2n, P2h and P2m and the lava rows P3a-P3h; P-HOOK-CRASH 1/1 (TNT next to a
  beacon had stopped the server); the joined client `P-BED-C` (night skipped, no bed spawn); the refmap carries
  BlockStateHookMixin, FenceGateBlockMixin, `startSleepInBed`, `m_5486_` and `m_6364_`; no ClassCastException,
  "invalid for ticking" or "Failed to handle packet" in the logs; the place sweeps now run past the ender chest (1440
  -> 4172 lines, 0 THREW).

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
42 | MC→btw    | ServerPlayer.isSleeping()      | EntityPlayer.sleeping         | 1.20.1 sleep (ruling 3, #037): PlayerBridge.isPlayerSleeping reads realPlayer.isSleeping() (PlayerBridge.java:677-680)
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

### 2.6 Sleep (2 hooks, 2026-10-21)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
22 | ServerPlayer.startSleepInBed, at    | @Inject at the INVOKE,     | ServerPlayerMixin.btw$fcHostilesPreventRest: an FC hostile
   | its Player.startSleepInBed call     | cancellable                | (an EntityMob) within 8/5/8 gives NOT_SAFE (1.5.2's rule)
22a| PlayerSetSpawnEvent (Forge event)   | listener, cancel           | BedSpawnPolicy.onSetSpawn: a non-forced spawn at a bed is
   |                                     |                            | cancelled, so a bed never sets the spawn
```

Row 22 used to read `Player.startSleepInBed(BlockPos) | @Overwrite | Always return OTHER_PROBLEM`, which was
wrong twice (CurseForge triage CF2-U1, correction 4): the hook was a HEAD inject, `PlayerMixin.btw$disableBeds`, not an
`@Overwrite`, and it declared the wrong return type, so a night click threw a ClassCastException after 1.20.1 had
already set the spawn. Maintainer ruling 3 (2026-10-19): beds sleep with 1.20.1's rules, messages and night skip, and
a bed never sets the spawn. The inject was deleted on 2026-10-21 (`INTEGRATIONS.md` #037, `STUB_CATALOGUE.md` (be)):
bed USE is RULED(3), see [Hook ownership (HookPolicy)](#hook-ownership-hookpolicy).

### 2.7 Block Placement (1 override)

```
# | MC 1.20.1 Target                    | Override Type              | FC Replacement
──┼──────────────────────────────────────┼────────────────────────────┼─────────────────
23 | Player.mayUseItemAt()              | @Inject HEAD cancel        | Require onGround||inWater||ladder||riding||lava
```

Row 23 is the 1.20.1 side (`PlayerMixin.btw$restrictMidAirPlacement`). FC's own side of the same rule, Modern-Common
`EntityPlayer.canPlayerEdit` (vanilla/server EntityPlayer.java:2034-2040), sees swimming players since 2026-10-21
(`PlayerBridge.syncFromReal` copies `inWater`, PlayerBridge.java:213-217) and riding ones (`PlayerBridge.canPlayerEdit`
answers from the real player, :838-843). It matters because FC's refusal of a 4.B ItemBlock-family placement is now
final (`INTEGRATIONS.md` #040, see [Placement validity (WorldBridge)](#placement-validity-worldbridge)). Other mods'
fake players are never ticked, so they fail the rule (maintainer ruling 2026-10-20: a known issue).

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

Neither row is an `@Overwrite` (2026-10-21 note). The respawn hooks are `ServerPlayerMixin` HEAD injects on
`getRespawnPosition`, `getRespawnDimension` and `isRespawnForced` (ServerPlayerMixin.java:275-323), which answer FC's
`m_HardcoreSpawnChunk` when it is set; `setRespawnPosition` is not hooked. A bed never sets the spawn:
`BedSpawnPolicy` cancels every non-forced `PlayerSetSpawnEvent` at a bed (2.6 row 22a, `INTEGRATIONS.md` #037). The
FC spawn is set only by the FC beacon bind, which stays unreachable until hup-43 bridges the beacon, and 4.B's random
hardcore respawn is not ported (CurseForge triage CF2-U1-HS), so players respawn at the world spawn or a charged
respawn anchor.

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

Row 36 is not a mixin: since 2026-10-18 it is `ProxyItem.shouldCauseReequipAnimation`, see
[Held use (hold-to-craft)](#held-use-hold-to-craft).

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
  `WorldBridge.sendFcAuxSfx` -> `BTWNetwork.sendFcAuxSfx` (BTWNetwork.java:183-202, network protocol 5
  since compat wave C1) -> `LegacyClientSound.playAux` -> `FCAddOnHandler.ClientPlayCustomAuxFX`.
  Non-global: players within 64 blocks except the actor (15j). Global (`func_82739_e`): every player on
  the server, as 1.5.2 `broadcastSound` (WorldManager.java:108-111). `tools/BridgeAudit.java` check [2]
  fails if a link of this chain disappears.
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
42 | EntityPlayer.wakeUpPlayer(a,b,c)               | serverPlayer.stopSleeping()                  | not needed: 1.20.1 owns sleep (ruling 3); FC never puts a player to sleep
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

Row 3 (2026-10-20, CurseForge triage CF2-I1, `STUB_CATALOGUE.md` (az)): `sync()` keeps FC's live stack while the real
slot is unchanged, writes FC's pending edit through first and re-reads only a slot the real inventory changed;
`discardPendingEdits`, `beginShadow` / `endShadow` and `resendHeldSlot` are new. See
[Inventory sync (InventoryBridge)](#inventory-sync-inventorybridge).

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
13. Sleep mixin — disable beds (superseded 2026-10-21: beds sleep and never set the spawn, `INTEGRATIONS.md` #037)
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
