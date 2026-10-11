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
  `extendFcPathingBuildRange`) are its precursors and are expected to move into it.

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
- Mixins: `BlockBehaviorMixin`, `BlockMixin`, `LivingEntityMixin`, `PlayerMixin` — basic block behavior hooks

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
12 | World.getBlockMetadata(x,y,z)                  | state.getValue(META) or 0                   | EXISTS
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

Rows 1, 8 and 15-15m were re-mapped on 2026-10-11 (`STUB_CATALOGUE.md` (an), (ao), (aq)). The rule
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
