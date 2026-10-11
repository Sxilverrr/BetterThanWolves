package btw.forge;

import btw.modern.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameRules.BooleanValue;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Full {@link btw.modern.WorldServer} implementation backed by a Forge 1.20.1
 * {@link ServerLevel}: the single bridge between FC legacy code (int IDs, block coords)
 * and the modern block-state system, and the object BTWLifecycle.wireWorlds puts in
 * MinecraftServer.worldServers[] (1.5.2 order: overworld, nether, end).
 *
 * Instances are cached per-ServerLevel via {@link #getOrCreate(ServerLevel)} and
 * released by {@link #clearCache()} when the server stops.
 */
// Extends WorldServer: in 1.5.2 every server-side worldObj IS a WorldServer and FC/frozen code
// casts to it unguarded -- FCEntityCow.java:528 / FCEntitySquid.java:1069
// (SendPacketToAllPlayersTrackingEntity), frozen EntityCreature.java:623
// (TransmitAttackTargetToClients), EntityLiving.java:864 (equipment sync), :2981 (onItemPickup);
// the ClassCastException aborted the rest of the entity's onUpdate. The frozen
// Entity.onEntityUpdate portal block (vanilla/client Entity.java:456-459) that forced the
// 2026-07-09 revert now links: remapFcCode relocates net.minecraft.server.MinecraftServer to
// btw.modern.MinecraftServer and Modern-Common WorldServer declares getMinecraftServer(); its
// travelToDimension branch stays inert because ForgeMinecraftServerWrapper.getAllowNether()
// is false (1.20.1 owns dimension travel of puppets).
// Every WorldServer stub body overrides an abstract World method that this class implements
// against the live level, so inheriting WorldServer changes no dispatch (adds getEntityTracker).
public class WorldBridge extends btw.modern.WorldServer {

    private static final Logger LOGGER = LogManager.getLogger("BTW-WorldBridge");

    /**
     * Metadata storage for vanilla blocks that don't have ProxyBlock.META.
     * FC code sets metadata on vanilla blocks (e.g., cracked flag on stone),
     * but vanilla MC blocks have no META property. This map stores the FC
     * metadata keyed by block position so getBlockMetadata can return it.
     * Entries are removed when FC changes the block at that position, and ignored (then dropped) once the
     * MC block there is no longer the one FC wrote to: MC replaces blocks without going through WorldBridge
     * (explosions, player placement, pistons, fluids), and a stale value used to win over the derived one.
     * Concurrent: the read path now also removes, and FC shape/bounds code can read neighbours off-thread.
     */
    private final Map<BlockPos, MetaOverride> vanillaMetaOverrides = new java.util.concurrent.ConcurrentHashMap<>();

    /** FC metadata written to a vanilla block, valid only while {@code block} is still the block at its position. */
    private record MetaOverride(net.minecraft.world.level.block.Block block, int meta) {}

    private static final Map<ServerLevel, WorldBridge> cache = new WeakHashMap<>();

    /**
     * Returns (or creates) the WorldBridge for the given ServerLevel.
     */
    public static WorldBridge getOrCreate(ServerLevel level) {
        return cache.computeIfAbsent(level, WorldBridge::new);
    }

    /**
     * Drops every cached bridge and detaches it from its level. Called once the server has
     * fully stopped (BTWLifecycle.onServerStopped, ServerStoppedEvent). Each bridge holds its
     * ServerLevel key strongly (directly, and through the proxies in fcToForgeEntity), so the
     * WeakHashMap could never evict an entry: every closed world's server stayed reachable for
     * the life of the JVM (triage RE2). A ServerLevel lives exactly as long as its server, so
     * releasing at stop is leak-free; detach also drops the level and the proxy map so a bridge
     * still referenced from elsewhere (a stale PlayerBridge, an FC object) cannot pin them
     * (WorldInfoBridge only holds the level weakly).
     */
    static void clearCache() {
        List<WorldBridge> bridges = new ArrayList<>(cache.values());
        cache.clear();
        for (WorldBridge bridge : bridges) {
            bridge.detach();
        }
    }

    private void detach() {
        fcToForgeEntity.clear();
        projectileTargets.clear();
        vanillaMetaOverrides.clear();
        level = null;
    }

    // Not final: nulled by detach() once the server has stopped (see clearCache).
    private ServerLevel level;

    public WorldBridge(ServerLevel level) {
        this.level = level;
        this.rand = new Random();
        this.isRemote = false;
        // Eagerly initialise FC-only data holders so FC code never
        // encounters null when accessing these lists.
        this.m_MagneticPointList = new FCMagneticPointList();
        this.m_SpawnLocationList = new FCSpawnLocationList();
        this.m_LootingBeaconLocationList = new FCBeaconEffectLocationList();

        // Set difficulty (FC checks this for food/starvation logic)
        // MC 1.20.1: PEACEFUL=0, EASY=1, NORMAL=2, HARD=3
        this.difficultySetting = level.getDifficulty().getId();

        // WorldInfo bridge — FC code accesses world.worldInfo directly
        this.worldInfo = new WorldInfoBridge(level);

        // Set up the WorldProvider so FC code can read dimensionId.
        this.provider = new btw.modern.WorldProvider() {};
        this.provider.worldObj = this;
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) {
            this.provider.dimensionId = -1;
            this.provider.isHellWorld = true;
            this.provider.hasNoSky = true;
            // 1.5.2 WorldProviderHell.generateLightBrightnessTable — same curve as the
            // base table (generated in the WorldProvider shim ctor) but with a 0.1F
            // ambient floor so the Nether is never pitch black.
            float fAmbient = 0.1F;
            for (int i = 0; i <= 15; ++i) {
                float f = 1.0F - (float) i / 15.0F;
                this.provider.lightBrightnessTable[i] =
                        (1.0F - f) / (f * 3.0F + 1.0F) * (1.0F - fAmbient) + fAmbient;
            }
        } else if (level.dimension() == net.minecraft.world.level.Level.END) {
            this.provider.dimensionId = 1;
            this.provider.hasNoSky = true;
        } else {
            this.provider.dimensionId = 0;
        }

        // 1.5.2 World.setItemData/loadItemData/getUniqueDataId delegate to mapStorage
        // (vanilla/server World.java:3873-3894) — FCItemEmptyMap.java:41 map crafting.
        // Unique IDs come from the live level's map-ID counter so BTW-crafted maps
        // never collide with modern filled maps; the data itself is held in-memory.
        this.mapStorage = new MapStorage() {
            private final Map<String, WorldSavedData> loadedDataMap = new HashMap<>();

            @Override
            public void setData(String key, WorldSavedData data) {
                loadedDataMap.put(key, data);
            }

            @Override
            public WorldSavedData loadData(Class dataClass, String key) {
                return loadedDataMap.get(key);
            }

            @Override
            public int getUniqueDataId(String key) {
                // Read the bridge's field, not the constructor parameter: capturing the
                // parameter would pin the ServerLevel past detach() (RE2).
                ServerLevel live = WorldBridge.this.level;
                return live != null ? live.getFreeMapId() : 0;
            }
        };

        // 1.5.2 World.m_localEnderChestInventory / m_localLowPowerEnderChestInventory
        // (BTW-patched vanilla/server World.java:4861-4862) — FCBlockEnderChest
        // antenna tiers 1/2 open these per-dimension communal inventories. Persisted
        // per-dimension like vanilla SaveHandler.java:435-479 ("FCEnderItems"/"FCLPEnderItems").
        this.m_localEnderChestInventory = new InventoryEnderChest();
        this.m_localLowPowerEnderChestInventory = new InventoryEnderChest();
        try {
            FcWorldSavedData.attach(this);
        } catch (Throwable t) {
            // Runs inside getOrCreate during chunk/entity loads -- never let it escape (RE1).
            LOGGER.warn("Could not attach FC world saved data: {}", t.toString());
        }
    }

    /**
     * Returns the underlying modern ServerLevel.
     */
    public ServerLevel getServerLevel() {
        return level;
    }

    // ================================================================
    // IChunkProvider (required abstract)
    // ================================================================

    
    public IChunkProvider createChunkProvider() {
        // FC code should not call this on the Forge backend
        return null;
    }

    // ================================================================
    // Block access
    // ================================================================


    // Never an id with an empty blocksList slot (frozen PathFinder/Entity/EntityLiving dereference
    // blocksList[id] unguarded, triage HAI-4); colliding modern-only states read as ModernBlockStandIn,
    // not air (design_blockmap U4); double slabs read as 43/125; seagrass/kelp/bubble columns as water (D5).
    public int getBlockId(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return ProxyRegistry.getWorldBlockId(level.getBlockState(pos), level, pos);
    }


    public int getBlockMetadata(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(ProxyBlock.META)) {
            return state.getValue(ProxyBlock.META);
        }
        // Check side map for vanilla blocks with FC metadata overrides, valid only while the block FC wrote is
        // still there (never stored where the MC state is the whole truth, see isMetaFullyDerived)
        MetaOverride override = vanillaMetaOverrides.get(pos);
        if (override != null) {
            if (override.block() == state.getBlock()) return override.meta();
            vanillaMetaOverrides.remove(pos); // MC replaced the block behind FC's back: derive instead
        }

        // Derive metadata from MC 1.20.1 BlockState properties for vanilla blocks
        return deriveVanillaMetadata(level, state, pos);
    }

    /**
     * Blocks whose 1.5.2 metadata is fully encoded by their MC state and which MC mutates without going
     * through WorldBridge: FenceGateBlock.use toggles OPEN without calling super (BlockBehaviorMixin never
     * sees it), and MC merges a placed slab into a DOUBLE one. A vanillaMetaOverrides entry FC wrote earlier
     * would go stale, so these always derive (and never store an override). Trapdoors (MC toggles OPEN on use
     * the same way) and stairs: every 1.5.2 meta bit round-trips through the derive/apply pair.
     */
    private static boolean isMetaFullyDerived(net.minecraft.world.level.block.Block block) {
        return block instanceof net.minecraft.world.level.block.FenceGateBlock
                || block instanceof net.minecraft.world.level.block.SlabBlock
                || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                || block instanceof net.minecraft.world.level.block.StairBlock;
    }

    /**
     * Derives legacy MC 1.5.2 metadata from MC 1.20.1 BlockState properties.
     * For logs: axis → orientation bits, and bottom-of-tree → stump flag.
     *
     * <p>Side-agnostic and static: client code with no WorldBridge (ProxyEntity.createDummyClientWorld, the
     * FCMiningSpeed dig prediction, FCBakedModel.LiveAccess) derives the same metadata as the server, so
     * e.g. a stump log (FCBlockLog hardness x3 for meta 12) is not predicted to break 3x early on the client
     * (w1 review P1). Never throws: any failure answers 0. Server-only vanillaMetaOverrides are applied by
     * {@link #getBlockMetadata}, not here.</p>
     */
    public static int deriveVanillaMetadata(net.minecraft.world.level.BlockGetter level, BlockState state,
                                            BlockPos pos) {
        try {
            return deriveVanillaMetadataUnchecked(level, state, pos);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int deriveVanillaMetadataUnchecked(net.minecraft.world.level.BlockGetter level,
                                                      BlockState state, BlockPos pos) {
        if (state == null) return 0;
        net.minecraft.world.level.block.Block block = state.getBlock();

        // Fluids: MC LEVEL (0 = source, 1-7 = flowing, 8+ = falling) has the same
        // semantics as 1.5.2 fluid metadata -- required by World.handleMaterialAcceleration's
        // fluid-surface check and BlockFluid.getFlowVector (vanilla World.java:2186).
        if (block instanceof net.minecraft.world.level.block.LiquidBlock) {
            if (state.hasProperty(net.minecraft.world.level.block.LiquidBlock.LEVEL)) {
                return state.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL);
            }
            return 0;
        }

        // Unmapped water-holders (seagrass, kelp, bubble columns, coral wall fans, dripleaf, waterlogged modern
        // blocks) read as water id 8 (ProxyRegistry.getWorldBlockId, triage D5) or as a ModernBlockStandIn, which
        // ignores metadata. They only ever hold a SOURCE: 1.5.2 fluid level 0. Answered before the FACING /
        // POWERED branches below, whose 2-5 / 8 would read as flowing / falling water to BlockFluid and
        // Entity.handleMaterialAcceleration (a spurious current at every coral wall fan).
        if (ProxyRegistry.getBlockId(block) == 0
                && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            return 0;
        }

        // Logs: axis property → orientation, bottom log → stump
        if (block instanceof net.minecraft.world.level.block.RotatedPillarBlock) {
            int legacyId = ProxyRegistry.getBlockId(block);
            if (legacyId == 17) { // Block.wood (log)
                int meta = 0; // wood type defaults to 0 (oak)
                // Axis → orientation: Y=0, X(east-west)=1<<2=4, Z(north-south)=2<<2=8
                if (state.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)) {
                    net.minecraft.core.Direction.Axis axis = state.getValue(
                            net.minecraft.world.level.block.RotatedPillarBlock.AXIS);
                    if (axis == net.minecraft.core.Direction.Axis.X) meta |= 4;      // east-west
                    else if (axis == net.minecraft.core.Direction.Axis.Z) meta |= 8;  // north-south
                    // Y = 0 (default, vertical)
                }

                // Stump detection: vertical log with solid non-log block below
                if ((meta & 12) == 0) { // only for vertical logs
                    BlockPos below = pos.below();
                    BlockState belowState = level.getBlockState(below);
                    boolean logBelow = belowState.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock
                            && ProxyRegistry.getBlockId(belowState.getBlock()) == 17;
                    if (!logBelow && !belowState.isAir() && belowState.isSolidRender(level, below)) {
                        meta = 12; // stump: both orientation bits set (3 << 2)
                    }
                }

                // Wood type from block type
                if (block == net.minecraft.world.level.block.Blocks.SPRUCE_LOG) meta = (meta & 12) | 1;
                else if (block == net.minecraft.world.level.block.Blocks.BIRCH_LOG) meta = (meta & 12) | 2;
                else if (block == net.minecraft.world.level.block.Blocks.JUNGLE_LOG) meta = (meta & 12) | 3;

                return meta;
            }
        }

        // Slabs: 1.5.2 BlockHalfSlab meta = species (meta & 7, BlockStep.java:23 / BlockWoodSlab.java:23)
        // | 8 for the upper half (FCMOD GetIsUpsideDown, vanilla/client BlockHalfSlab.java:282-290). A DOUBLE
        // slab reads as id 43/125 (ProxyRegistry.getWorldBlockId) with the species only. Modern-Common
        // BlockHalfSlab re-applies the 1.5.2 half-slab bounds on this encoding.
        if (block instanceof net.minecraft.world.level.block.SlabBlock
                && state.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)) {
            int species = Math.max(ProxyRegistry.getLegacyVariantMeta(block), 0);
            return state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                    == net.minecraft.world.level.block.state.properties.SlabType.TOP ? species | 8 : species;
        }

        // Fence gates: 1.5.2 BlockFenceGate meta = direction (S=0, W=1, N=2, E=3: the placer's facing,
        // vanilla/client BlockFenceGate.java:101-105, same as 1.20.1 Direction.get2DDataValue) | 4 when open.
        // Must precede the generic HORIZONTAL_FACING branch (N=2, S=3, W=4, E=5, OPEN lost), which made closed
        // east/west gates read as open. Modern-Common BlockFenceGate re-applies the 1.5.2 collision on this.
        if (block instanceof net.minecraft.world.level.block.FenceGateBlock) {
            int meta = state.getValue(net.minecraft.world.level.block.FenceGateBlock.FACING).get2DDataValue();
            return state.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN) ? meta | 4 : meta;
        }

        // Stairs: 1.5.2 BlockStairs meta = direction of the high half (0=E, 1=W, 2=S, 3=N, from the placer's yaw,
        // vanilla/client BlockStairs.java:533-556; bounds :82-223) | 4 when upside-down. Must precede the generic
        // HORIZONTAL_FACING branch, which used to return first (2-5): east stairs (5) decoded as west + upside-down.
        // Matches the reverse in applyFcMetadataToVanillaBlock. Keyed on StairBlock, not on HALF: trapdoors also
        // carry HORIZONTAL_FACING + HALF with a different encoding (below).
        if (block instanceof net.minecraft.world.level.block.StairBlock
                && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)) {
            net.minecraft.core.Direction dir = state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
            int meta;
            switch (dir) {
                case EAST: meta = 0; break;
                case WEST: meta = 1; break;
                case SOUTH: meta = 2; break;
                case NORTH: meta = 3; break;
                default: meta = 0;
            }
            if (state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
                    == net.minecraft.world.level.block.state.properties.Half.TOP) {
                meta |= 4;
            }
            return meta;
        }

        // Trapdoors: 1.5.2 BlockTrapDoor meta = hinge direction (0-3) | 4 when open | 8 for the upper half
        // (vanilla/client BlockTrapDoor.java:94-129 bounds, :226-256 placement, :297-300 isTrapdoorOpen).
        // Placement on side 2/3/4/5 gives 0/1/2/3, the same face 1.20.1 TrapDoorBlock.getStateForPlacement
        // stores as FACING NORTH/SOUTH/WEST/EAST, and the open bounds agree (meta 0 -> z 0.8125..1 =
        // NORTH_OPEN_AABB). Block.trapdoor is FCBlockTrapDoor (vanilla/client Block.java:314): its pathing,
        // zombie barricade-breaking and collision read this meta (Server FCBlockTrapDoor.java:81-115, :149-196).
        // The generic HORIZONTAL_FACING branch lost OPEN/HALF and made closed west/east trapdoors (4/5) read open.
        if (block instanceof net.minecraft.world.level.block.TrapDoorBlock
                && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            int meta;
            switch (state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                case SOUTH: meta = 1; break;
                case WEST: meta = 2; break;
                case EAST: meta = 3; break;
                default: meta = 0; // NORTH
            }
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                    && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
                meta |= 4;
            }
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
                    && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
                            == net.minecraft.world.level.block.state.properties.Half.TOP) {
                meta |= 8;
            }
            return meta;
        }

        // Farmland: MC MOISTURE (0-7) → FC legacy metadata (0-7, >0 = hydrated)
        if (block instanceof net.minecraft.world.level.block.FarmBlock) {
            if (state.hasProperty(net.minecraft.world.level.block.FarmBlock.MOISTURE)) {
                return state.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE);
            }
            return 0;
        }

        // Wall torches: FC uses 1=east, 2=west, 3=south, 4=north, 5=floor
        if (block instanceof net.minecraft.world.level.block.WallTorchBlock
                || block instanceof net.minecraft.world.level.block.RedstoneWallTorchBlock) {
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                net.minecraft.core.Direction dir = state.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
                switch (dir) {
                    case EAST: return 1;
                    case WEST: return 2;
                    case SOUTH: return 3;
                    case NORTH: return 4;
                    default: return 5;
                }
            }
            return 5; // floor
        }
        // Floor torches
        if (block instanceof net.minecraft.world.level.block.TorchBlock) {
            return 5; // floor torch
        }

        // Blocks with FACING property (furnace, dispenser, dropper, piston, chest, etc.)
        // MC 1.5.2 metadata: 0=down, 1=up, 2=north, 3=south, 4=west, 5=east
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            net.minecraft.core.Direction dir = state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
            int meta = directionToLegacyFacing(dir);
            // Piston: bit 3 = extended
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED)) {
                if (state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED)) {
                    meta |= 8;
                }
            }
            // Dispenser/dropper: bit 3 = triggered
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.TRIGGERED)) {
                if (state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.TRIGGERED)) {
                    meta |= 8;
                }
            }
            return meta;
        }

        // Blocks with HORIZONTAL_FACING (furnace, chest, ladder, wall signs, etc.)
        // MC 1.5.2: 2=north, 3=south, 4=west, 5=east
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            net.minecraft.core.Direction dir = state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
            int meta = directionToLegacyFacing(dir);
            // Furnace: bit 3 = lit (for burning furnace)
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
                // Lit furnace was a separate block in 1.5.2, not a metadata flag
                // but some FC code checks it
            }
            return meta;
        }

        // Lever/button: powered state
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
            int meta = 0;
            if (state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
                meta |= 8;
            }
            return meta;
        }

        // Flattened subtypes: wool colour, plank/sapling/leaf species, sandstone / stone-brick / infested /
        // wall type, tall grass vs fern (ProxyRegistry variant table, the 1.5.2 subtype orders).
        int variant = ProxyRegistry.getLegacyVariantMeta(block);
        if (variant >= 0) return variant;

        // Everything else (unmapped water-holders already answered 0 above, triage D5).
        return 0;
    }

    /** Converts MC 1.20.1 Direction to MC 1.5.2 facing metadata value. */
    private static int directionToLegacyFacing(net.minecraft.core.Direction dir) {
        switch (dir) {
            case DOWN: return 0;
            case UP: return 1;
            case NORTH: return 2;
            case SOUTH: return 3;
            case WEST: return 4;
            case EAST: return 5;
            default: return 0;
        }
    }

    /** Converts MC 1.5.2 facing metadata to MC 1.20.1 Direction. */
    private static net.minecraft.core.Direction legacyFacingToDirection(int facing) {
        switch (facing) {
            case 0: return net.minecraft.core.Direction.DOWN;
            case 1: return net.minecraft.core.Direction.UP;
            case 2: return net.minecraft.core.Direction.NORTH;
            case 3: return net.minecraft.core.Direction.SOUTH;
            case 4: return net.minecraft.core.Direction.WEST;
            case 5: return net.minecraft.core.Direction.EAST;
            default: return net.minecraft.core.Direction.NORTH;
        }
    }

    /**
     * Applies FC metadata to a vanilla MC block state (reverse of deriveVanillaMetadata).
     * Translates FC's integer metadata into the appropriate MC 1.20.1 BlockState properties.
     */
    private BlockState applyFcMetadataToVanillaBlock(BlockState current, int metadata) {
        net.minecraft.world.level.block.Block block = current.getBlock();

        // Logs: metadata bits 2-3 encode axis orientation
        if (block instanceof net.minecraft.world.level.block.RotatedPillarBlock
                && current.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)) {
            int axisBits = (metadata >> 2) & 3;
            net.minecraft.core.Direction.Axis axis;
            switch (axisBits) {
                case 1: axis = net.minecraft.core.Direction.Axis.X; break;   // east-west
                case 2: axis = net.minecraft.core.Direction.Axis.Z; break;   // north-south
                default: axis = net.minecraft.core.Direction.Axis.Y; break;  // vertical
            }
            return current.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis);
        }

        // Fence gates: reverse of deriveVanillaMetadata's 1.5.2 gate encoding (direction 0-3 | 4 = open)
        if (block instanceof net.minecraft.world.level.block.FenceGateBlock) {
            return current
                    .setValue(net.minecraft.world.level.block.FenceGateBlock.FACING,
                            net.minecraft.core.Direction.from2DDataValue(metadata & 3))
                    .setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, (metadata & 4) != 0);
        }

        // Slabs: bit 8 = upper half (a double slab stays double; resolveVanillaBlockState makes 43/125 double)
        if (block instanceof net.minecraft.world.level.block.SlabBlock
                && current.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)) {
            if (current.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                    == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) {
                return current;
            }
            return current.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, (metadata & 8) != 0
                    ? net.minecraft.world.level.block.state.properties.SlabType.TOP
                    : net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
        }

        // Stairs: reverse of deriveVanillaMetadata's 1.5.2 stairs encoding (0=E, 1=W, 2=S, 3=N | 4 = upside-down).
        // Ahead of the generic HORIZONTAL_FACING branch, which skipped meta 0/1 (DOWN/UP: not horizontal), so FC
        // writes of east/west bottom stairs were dropped.
        if (block instanceof net.minecraft.world.level.block.StairBlock
                && current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                && current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)) {
            net.minecraft.core.Direction stairDir;
            switch (metadata & 3) {
                case 0: stairDir = net.minecraft.core.Direction.EAST; break;
                case 1: stairDir = net.minecraft.core.Direction.WEST; break;
                case 2: stairDir = net.minecraft.core.Direction.SOUTH; break;
                default: stairDir = net.minecraft.core.Direction.NORTH; break;
            }
            return current
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                            stairDir)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                            (metadata & 4) != 0
                                    ? net.minecraft.world.level.block.state.properties.Half.TOP
                                    : net.minecraft.world.level.block.state.properties.Half.BOTTOM);
        }

        // Trapdoors: reverse of deriveVanillaMetadata's 1.5.2 trapdoor encoding (0=N, 1=S, 2=W, 3=E | 4 = open
        // | 8 = upper half). Used to fall into the stairs mapping above (shared HORIZONTAL_FACING + HALF).
        if (block instanceof net.minecraft.world.level.block.TrapDoorBlock
                && current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                && current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                && current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)) {
            net.minecraft.core.Direction trapDir;
            switch (metadata & 3) {
                case 1: trapDir = net.minecraft.core.Direction.SOUTH; break;
                case 2: trapDir = net.minecraft.core.Direction.WEST; break;
                case 3: trapDir = net.minecraft.core.Direction.EAST; break;
                default: trapDir = net.minecraft.core.Direction.NORTH; break;
            }
            return current
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                            trapDir)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN,
                            (metadata & 4) != 0)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                            (metadata & 8) != 0
                                    ? net.minecraft.world.level.block.state.properties.Half.TOP
                                    : net.minecraft.world.level.block.state.properties.Half.BOTTOM);
        }

        // Blocks with HORIZONTAL_FACING (furnace, chest, ladder, etc.)
        // FC metadata: 2=north, 3=south, 4=west, 5=east
        if (current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            int facingBits = metadata & 7; // lower 3 bits for facing
            net.minecraft.core.Direction dir = legacyFacingToDirection(facingBits);
            // HORIZONTAL_FACING only accepts horizontal directions
            if (dir.getAxis().isHorizontal()) {
                return current.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, dir);
            }
        }

        // Blocks with full FACING (dispenser, dropper, piston, observer, etc.)
        if (current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            int facingBits = metadata & 7;
            net.minecraft.core.Direction dir = legacyFacingToDirection(facingBits);
            BlockState result = current.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, dir);
            if (current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED)) {
                result = result.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.EXTENDED,
                        (metadata & 8) != 0);
            }
            if (current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.TRIGGERED)) {
                result = result.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.TRIGGERED,
                        (metadata & 8) != 0);
            }
            return result;
        }

        // Torches on walls: FC metadata 1-4 maps to facing
        // FC: 1=east(+x), 2=west(-x), 3=south(+z), 4=north(-z), 5=floor
        if (block instanceof net.minecraft.world.level.block.WallTorchBlock
                && current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            net.minecraft.core.Direction dir;
            switch (metadata) {
                case 1: dir = net.minecraft.core.Direction.EAST; break;
                case 2: dir = net.minecraft.core.Direction.WEST; break;
                case 3: dir = net.minecraft.core.Direction.SOUTH; break;
                case 4: dir = net.minecraft.core.Direction.NORTH; break;
                default: return current; // floor torch or unknown
            }
            return current.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, dir);
        }

        // No translation available — return unchanged
        return current;
    }

    /**
     * Resolves an FC block ID + metadata into the correct MC 1.20.1 BlockState.
     * Handles split blocks (e.g., floor torch vs wall torch) and applies facing/axis.
     */
    private BlockState resolveVanillaBlockState(int blockID, int metadata) {
        // Torches: FC metadata 1-4 = wall, 5 = floor
        // ID 50 = torch, ID 75 = redstone torch off, ID 76 = redstone torch on
        if (blockID == 50) {
            if (metadata >= 1 && metadata <= 4) {
                BlockState state = Blocks.WALL_TORCH.defaultBlockState();
                return applyTorchFacing(state, metadata);
            }
            return Blocks.TORCH.defaultBlockState(); // floor
        }
        if (blockID == 75) {
            // Redstone torch off — MC 1.20.1 doesn't have a separate block for off state
            // Use the normal redstone torch (it manages its own state)
            if (metadata >= 1 && metadata <= 4) {
                BlockState state = Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, false);
                return applyTorchFacing(state, metadata);
            }
            return Blocks.REDSTONE_TORCH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, false);
        }
        if (blockID == 76) {
            if (metadata >= 1 && metadata <= 4) {
                BlockState state = Blocks.REDSTONE_WALL_TORCH.defaultBlockState();
                return applyTorchFacing(state, metadata);
            }
            return Blocks.REDSTONE_TORCH.defaultBlockState();
        }

        // For other vanilla blocks, get the modern block and apply metadata. The subtype bits pick the
        // flattened variant first (spruce log 17:1, orange wool 35:1, cut sandstone 24:2, cobble slab 44:3),
        // else the id's canonical block; 43/125 are the double forms of the 44/126 slabs.
        net.minecraft.world.level.block.Block mcBlock = ProxyRegistry.getModernBlockVariant(blockID, metadata);
        if (mcBlock == null) {
            mcBlock = ProxyRegistry.getModernBlock(blockID);
        }
        if (mcBlock != null) {
            BlockState state = applyFcMetadataToVanillaBlock(mcBlock.defaultBlockState(), metadata);
            if ((blockID == 43 || blockID == 125)
                    && state.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)) {
                state = state.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
            }
            return state;
        }
        return null;
    }

    /** Applies FC torch metadata (1=east, 2=west, 3=south, 4=north) to a wall torch BlockState. */
    private BlockState applyTorchFacing(BlockState state, int metadata) {
        if (!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            return state;
        }
        net.minecraft.core.Direction dir;
        switch (metadata) {
            case 1: dir = net.minecraft.core.Direction.EAST; break;
            case 2: dir = net.minecraft.core.Direction.WEST; break;
            case 3: dir = net.minecraft.core.Direction.SOUTH; break;
            case 4: dir = net.minecraft.core.Direction.NORTH; break;
            default: return state;
        }
        return state.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, dir);
    }


    public Material getBlockMaterial(int x, int y, int z) {
        int id = getBlockId(x, y, z);
        if (id > 0 && id < btw.modern.Block.blocksList.length) {
            btw.modern.Block fcBlock = btw.modern.Block.blocksList[id];
            if (fcBlock != null) {
                return fcBlock.blockMaterial;
            }
        }
        return Material.air;
    }

    
    public boolean isAirBlock(int x, int y, int z) {
        return level.isEmptyBlock(new BlockPos(x, y, z));
    }

    
    public boolean isBlockNormalCube(int x, int y, int z) {
        int id = getBlockId(x, y, z);
        return btw.modern.Block.isNormalCube(id);
    }

    
    public TileEntity getBlockTileEntity(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;

        // If this is a ProxyBlockEntity, return the FC tile entity directly
        // so that FC code can cast it to the expected subclass (e.g.
        // FCTileEntityCampfire, FCTileEntityHopper).
        if (be instanceof ProxyBlockEntity proxy) {
            return proxy.getFcTileEntity();
        }

        // For vanilla BlockEntities, wrap via TileEntityBridge
        return TileEntityBridge.getOrCreate(be, this);
    }

    // ================================================================
    // Block modification
    // ================================================================

    
    public boolean setBlock(int x, int y, int z, int blockID, int metadata, int flags) {
        BlockPos pos = new BlockPos(x, y, z);
        vanillaMetaOverrides.remove(pos); // clear metadata override on block change

        // Block ID 0 = air — handle directly since air has no proxy/mapping
        if (blockID == 0) {
            return level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        }

        // Preserve tile entity data across block changes when FC says to keep it.
        // (e.g., campfire fire level changes: block type changes but tile entity stays)
        net.minecraft.nbt.CompoundTag savedTileData = null;
        int oldBlockId = getBlockId(x, y, z);
        if (oldBlockId > 0 && oldBlockId != blockID) {
            btw.modern.Block oldFcBlock = btw.modern.Block.blocksList[oldBlockId];
            if (oldFcBlock != null && !oldFcBlock.ShouldDeleteTileEntityOnBlockChange(blockID)) {
                BlockEntity oldBe = level.getBlockEntity(pos);
                if (oldBe instanceof ProxyBlockEntity oldProxy) {
                    btw.modern.TileEntity fcTe = oldProxy.getFcTileEntity();
                    if (fcTe != null) {
                        savedTileData = new net.minecraft.nbt.CompoundTag();
                        ForgeNBTCompound wrapper = new ForgeNBTCompound(savedTileData);
                        try { fcTe.writeToNBT(wrapper); } catch (Exception ignored) {}
                    }
                }
            }
        }

        ProxyBlock proxy = ProxyRegistry.getProxy(blockID);
        if (proxy != null) {
            BlockState state = proxy.defaultBlockState()
                    .setValue(ProxyBlock.META, Math.min(Math.max(metadata, 0), 15));
            boolean result = level.setBlock(pos, state, flags);

            // Restore saved tile entity data
            if (result && savedTileData != null && !savedTileData.isEmpty()) {
                BlockEntity newBe = level.getBlockEntity(pos);
                if (newBe instanceof ProxyBlockEntity newProxy) {
                    btw.modern.TileEntity fcTe = newProxy.getFcTileEntity();
                    if (fcTe != null) {
                        ForgeNBTCompound wrapper = new ForgeNBTCompound(savedTileData);
                        try { fcTe.readFromNBT(wrapper); } catch (Exception ignored) {}
                        fcTe.xCoord = x;
                        fcTe.yCoord = y;
                        fcTe.zCoord = z;
                    }
                }
            }
            return result;
        }
        // For vanilla block IDs without a ProxyBlock, try mapping to the MC block
        // and apply FC metadata → MC block state translation
        BlockState vanillaState = resolveVanillaBlockState(blockID, metadata);
        if (vanillaState != null) {
            if (!isMetaFullyDerived(vanillaState.getBlock())) {
                vanillaMetaOverrides.put(pos.immutable(), new MetaOverride(vanillaState.getBlock(), metadata));
            }
            // Use flags WITHOUT block-update bit for blocks that attach to faces
            // (torches, levers, etc.) — FC manages its own update chain and MC's
            // neighborChanged would break them via canSurvive checks during turntable
            // rotation before they're fully positioned.
            net.minecraft.world.level.block.Block vanillaBlock = vanillaState.getBlock();
            int placeFlags = flags;
            if (vanillaBlock instanceof net.minecraft.world.level.block.WallTorchBlock
                    || vanillaBlock instanceof net.minecraft.world.level.block.RedstoneWallTorchBlock) {
                placeFlags = flags & ~1; // strip BLOCK_UPDATE, keep SEND_TO_CLIENTS
            }
            return level.setBlock(pos, vanillaState, placeFlags);
        }
        return false;
    }

    
    public boolean setBlockToAir(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        vanillaMetaOverrides.remove(pos);
        return level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    
    public boolean setBlockMetadata(int x, int y, int z, int metadata, int flags) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState current = level.getBlockState(pos);
        if (current.hasProperty(ProxyBlock.META)) {
            BlockState newState = current.setValue(ProxyBlock.META, Math.min(Math.max(metadata, 0), 15));
            vanillaMetaOverrides.remove(pos); // clear any override
            return level.setBlock(pos, newState, flags);
        }
        // Vanilla block without META — translate FC metadata to MC block state
        if (!isMetaFullyDerived(current.getBlock())) {
            vanillaMetaOverrides.put(pos.immutable(), new MetaOverride(current.getBlock(), metadata));
        }
        BlockState newState = applyFcMetadataToVanillaBlock(current, metadata);
        if (newState != current) {
            return level.setBlock(pos, newState, flags);
        }
        level.sendBlockUpdated(pos, current, current, flags);
        return true;
    }

    
    public boolean destroyBlock(int x, int y, int z, boolean dropItems) {
        return level.destroyBlock(new BlockPos(x, y, z), dropItems);
    }

    // ================================================================
    // Tile entity management
    // ================================================================

    
    public void setBlockTileEntity(int x, int y, int z, TileEntity tileEntity) {
        BlockPos pos = new BlockPos(x, y, z);
        if (tileEntity instanceof TileEntityBridge bridge) {
            // Re-use the real BlockEntity wrapped by the bridge (null once that BlockEntity
            // has been collected -- the bridge only holds it weakly)
            BlockEntity wrapped = bridge.getBlockEntity();
            if (wrapped != null) {
                level.setBlockEntity(wrapped);
            }
        } else if (tileEntity != null) {
            // FC code created a pure FC TileEntity. Check if a
            // ProxyBlockEntity already exists at this position and
            // inject the FC tile entity into it.
            BlockEntity existing = level.getBlockEntity(pos);
            if (existing instanceof ProxyBlockEntity proxy) {
                proxy.setFcTileEntity(tileEntity);
            } else {
                // No ProxyBlockEntity exists yet. If the block at this
                // position is a ProxyBlock, create a new ProxyBlockEntity.
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof ProxyBlock pb && pb.hasFcTileEntity()) {
                    ProxyBlockEntity newProxy = new ProxyBlockEntity(
                            pos, state, tileEntity, pb.getLegacyId());
                    level.setBlockEntity(newProxy);
                } else {
                    LOGGER.debug("setBlockTileEntity: cannot place pure FC TileEntity {} at {}",
                            tileEntity.getClass().getSimpleName(), pos);
                }
            }
        }
    }

    
    public void removeBlockTileEntity(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            if (!(be instanceof ProxyBlockEntity)) {
                TileEntityBridge.uncache(be);
            }
            level.removeBlockEntity(pos);
        }
    }

    // ================================================================
    // Notifications
    // ================================================================

    
    public void notifyBlockChange(int x, int y, int z, int blockID) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    
    public void notifyBlocksOfNeighborChange(int x, int y, int z, int blockID) {
        net.minecraft.world.level.block.Block modernBlock = ProxyRegistry.getModernBlock(blockID);
        if (modernBlock != null) {
            level.updateNeighborsAt(new BlockPos(x, y, z), modernBlock);
        }
    }

    
    public void notifyBlockOfNeighborChange(int x, int y, int z, int blockID) {
        BlockPos pos = new BlockPos(x, y, z);
        net.minecraft.world.level.block.Block sourceBlock = ProxyRegistry.getModernBlock(blockID);
        if (sourceBlock != null) {
            level.getBlockState(pos).neighborChanged(level, pos, sourceBlock, pos, false);
        }
    }

    
    public void markBlockForUpdate(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        // Render updates are client-side; on server this is a no-op
    }

    // ================================================================
    // Scheduled updates
    // ================================================================

    
    public void scheduleBlockUpdate(int x, int y, int z, int blockID, int delay) {
        BlockPos pos = new BlockPos(x, y, z);
        net.minecraft.world.level.block.Block modernBlock = modernBlockAt(pos, blockID);
        if (modernBlock != null) {
            level.scheduleTick(pos, modernBlock, delay);
        }
    }

    /**
     * The MC block FC means by a legacy id at a position: the block actually there when it reads as that id
     * (spruce leaves / orange wool for 18 / 35), else the id's canonical block. MC drops a scheduled tick or
     * block event whose block is not the one at the position (ServerLevel.tickBlock / doBlockEvent check
     * state.is(block)), so ticks FC scheduled on any non-canonical variant were lost.
     */
    private net.minecraft.world.level.block.Block modernBlockAt(BlockPos pos, int blockID) {
        net.minecraft.world.level.block.Block here = level.getBlockState(pos).getBlock();
        if (blockID > 0 && ProxyRegistry.getBlockId(here) == blockID) {
            return here;
        }
        return ProxyRegistry.getModernBlock(blockID);
    }

    
    public void func_82740_a(int x, int y, int z, int blockID, int delay, int priority) {
        // Same as scheduleBlockUpdate with priority (ignored for simplicity)
        scheduleBlockUpdate(x, y, z, blockID, delay);
    }

    @Override
    public boolean isBlockTickScheduled(int x, int y, int z, int blockID) {
        BlockPos pos = new BlockPos(x, y, z);
        net.minecraft.world.level.block.Block modernBlock = modernBlockAt(pos, blockID);
        if (modernBlock != null) {
            return level.getBlockTicks().hasScheduledTick(pos, modernBlock);
        }
        return false;
    }

    @Override
    public boolean IsUpdateScheduledForBlock(int i, int j, int k, int iBlockID) {
        return isBlockTickScheduled(i, j, k, iBlockID);
    }

    @Override
    public boolean IsUpdatePendingThisTickForBlock(int i, int j, int k, int iBlockID) {
        // MC 1.20.1 does not expose "pending this tick" easily; approximate
        // by checking if any tick is scheduled for the block.
        return isBlockTickScheduled(i, j, k, iBlockID);
    }

    // ================================================================
    // Light methods
    // ================================================================

    // 1.5.2 World.getLightBrightness -> provider.lightBrightnessTable[getBlockLightValue]
    // with the overworld generateLightBrightnessTable curve (ambient 0). MUST read the
    // inherited public skylightSubtracted field: EntityPlayerMP.IsInGloom temporarily
    // overrides it around its two calls to probe "value at night" (gloom only runs in
    // dimension 0, so the overworld curve is correct). Without this override the shim
    // returns 0 and gloom reads perpetual darkness.
    // 1.5.2 World.getLightBrightness = provider.lightBrightnessTable[getBlockLightValue].
    // sky is reduced by skylightSubtracted (ticked in ModSpecificTick; also transiently
    // overridden by EntityPlayerMP.IsInGloom's night-probe), and the provider TABLE — not
    // an inline curve — supplies the value so the Nether's 0.1 ambient floor is honored.
    @Override
    public float getLightBrightness(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) - this.skylightSubtracted;
        int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
        int light = Math.min(Math.max(block, Math.max(sky, 0)), 15);
        return provider.lightBrightnessTable[light];
    }

    public boolean canBlockSeeTheSky(int x, int y, int z) {
        return level.canSeeSky(new BlockPos(x, y, z));
    }

    
    public int getFullBlockLightValue(int x, int y, int z) {
        return level.getMaxLocalRawBrightness(new BlockPos(x, y, z));
    }

    @Override
    public int getBlockLightValue(int x, int y, int z) {
        return level.getMaxLocalRawBrightness(new BlockPos(x, y, z));
    }

    @Override
    public int getBlockLightValue_do(int x, int y, int z, boolean useNeighbors) {
        return level.getMaxLocalRawBrightness(new BlockPos(x, y, z));
    }

    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int lightValue) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
        int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
        if (block < lightValue) block = lightValue;
        return (sky << 20) | (block << 4);
    }

    // GetBlockNaturalLightValue is intentionally NOT overridden here: the Modern-Common
    // World implementation applies skylightSubtracted and the neighbor-brightness pass
    // (needed by the gloom probe), delegating to getSavedLightValue/getChunkFromChunkCoords
    // which this bridge provides. A raw-sky override here would dead-shadow it.

    @Override
    public int getSavedLightValue(EnumSkyBlock enumSkyBlock, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (enumSkyBlock == EnumSkyBlock.Sky) {
            return level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
        } else {
            return level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
        }
    }

    
    public void setLightValue(EnumSkyBlock enumSkyBlock, int x, int y, int z, int lightValue) {
        // Modern MC handles lighting internally; this is largely a no-op on the server.
    }

    // ================================================================
    // Height methods
    // ================================================================

    @Override
    public int getHeightValue(int x, int z) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    @Override
    public int getTopSolidOrLiquidBlock(int x, int z) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    @Override
    public int getPrecipitationHeight(int x, int z) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    @Override
    public int getFirstUncoveredBlock(int x, int z) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    @Override
    public int getChunkHeightMapMinimum(int x, int z) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z);
    }

    // ================================================================
    // Chunk methods
    // ================================================================

    
    public boolean blockExists(int x, int y, int z) {
        return level.isLoaded(new BlockPos(x, y, z));
    }

    @Override
    public boolean doChunksNearChunkExist(int x, int y, int z, int range) {
        return true;
    }

    @Override
    public boolean checkChunksExist(int x1, int y1, int z1, int x2, int y2, int z2) {
        return true;
    }


    public boolean chunkExists(int chunkX, int chunkZ) {
        return level.hasChunk(chunkX, chunkZ);
    }

    @Override
    public btw.modern.Chunk getChunkFromChunkCoords(int chunkX, int chunkZ) {
        // Return a lightweight Chunk that delegates to WorldBridge for block access
        return new btw.modern.Chunk(this, chunkX, chunkZ);
    }

    @Override
    public btw.modern.Chunk getChunkFromBlockCoords(int x, int z) {
        return getChunkFromChunkCoords(x >> 4, z >> 4);
    }

    // ================================================================
    // Biome
    // ================================================================

    
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        Holder<Biome> holder = level.getBiome(new BlockPos(x, 64, z));
        return mapBiomeToFc(holder);
    }

    /**
     * Maps a modern Biome holder to the closest FC BiomeGenBase static
     * instance.  Falls back to {@link BiomeGenBase#plains} if the biome
     * is unknown or FC biomes have not been initialised yet.
     */
    private static BiomeGenBase mapBiomeToFc(Holder<Biome> holder) {
        // Safety: if FC biomes were never initialised, return a non-null default
        if (BiomeGenBase.plains == null) {
            return new BiomeGenBase(1) {};
        }

        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
        if (key == null) return BiomeGenBase.plains;

        ResourceLocation loc = key.location();
        String path = loc.getPath(); // e.g. "plains", "desert", "frozen_ocean"

        return switch (path) {
            case "ocean", "deep_ocean",
                 "deep_lukewarm_ocean", "deep_cold_ocean",
                 "deep_frozen_ocean", "warm_ocean",
                 "lukewarm_ocean", "cold_ocean" -> BiomeGenBase.ocean;
            case "plains", "sunflower_plains",
                 "meadow" -> BiomeGenBase.plains;
            case "desert" -> BiomeGenBase.desert;
            case "windswept_hills", "windswept_gravelly_hills",
                 "windswept_forest", "stony_peaks" -> BiomeGenBase.extremeHills;
            case "forest", "flower_forest",
                 "birch_forest", "old_growth_birch_forest",
                 "dark_forest" -> BiomeGenBase.forest;
            case "taiga", "old_growth_pine_taiga",
                 "old_growth_spruce_taiga" -> BiomeGenBase.taiga;
            case "swamp", "mangrove_swamp" -> BiomeGenBase.swampland;
            case "river" -> BiomeGenBase.river;
            case "nether_wastes", "soul_sand_valley",
                 "crimson_forest", "warped_forest",
                 "basalt_deltas" -> BiomeGenBase.hell;
            case "the_end", "end_highlands",
                 "end_midlands", "end_barrens",
                 "small_end_islands", "the_void" -> BiomeGenBase.sky;
            case "frozen_ocean" -> BiomeGenBase.frozenOcean;
            case "frozen_river" -> BiomeGenBase.frozenRiver;
            case "snowy_plains", "snowy_slopes",
                 "frozen_peaks", "ice_spikes" -> BiomeGenBase.icePlains;
            case "snowy_taiga", "grove",
                 "jagged_peaks" -> BiomeGenBase.iceMountains;
            case "mushroom_fields" -> BiomeGenBase.mushroomIsland;
            case "beach", "stony_shore" -> BiomeGenBase.beach;
            case "jungle", "sparse_jungle",
                 "bamboo_jungle" -> BiomeGenBase.jungle;
            case "snowy_beach" -> BiomeGenBase.frozenOcean;
            case "savanna", "savanna_plateau",
                 "windswept_savanna" -> BiomeGenBase.plains;
            case "badlands", "eroded_badlands",
                 "wooded_badlands" -> BiomeGenBase.desert;
            case "cherry_grove" -> BiomeGenBase.forest;
            case "dripstone_caves", "lush_caves",
                 "deep_dark" -> BiomeGenBase.plains;
            default -> BiomeGenBase.plains;
        };
    }

    
    /**
     * Lazy-initialised WorldChunkManager bridge that delegates biome
     * lookups to the real MC level.
     */
    private WorldChunkManager chunkManagerBridge;

    public WorldChunkManager getWorldChunkManager() {
        if (chunkManagerBridge == null) {
            chunkManagerBridge = new WorldChunkManager() {
                @Override
                public BiomeGenBase getBiomeGenAt(int x, int z) {
                    return getBiomeGenForCoords(x, z);
                }
            };
        }
        return chunkManagerBridge;
    }

    // ================================================================
    // Entity methods
    // ================================================================

    /**
     * Maps FC entity instances to their Forge proxy counterpart so we
     * can look up the proxy for removal, position sync, etc.
     * Values are held weakly too: the proxy owns its FC entity, so a strong value pinned
     * the weak key and every FC-spawned proxy stayed reachable for the world's lifetime (RE2).
     */
    private final WeakValueMap<btw.modern.Entity, net.minecraft.world.entity.Entity> fcToForgeEntity =
            new WeakValueMap<>();

    /**
     * FC views of non-living vanilla entities for FC projectile hit tests ({@link VanillaProjectileTarget}).
     * Weak keys; a view holds its entity weakly and otherwise reaches only this bridge, so an entry dies with
     * its entity and the whole map with the bridge. Server thread only.
     */
    private final Map<net.minecraft.world.entity.Entity, VanillaProjectileTarget> projectileTargets =
            new WeakHashMap<>();

    /**
     * WeakHashMap whose values are WeakReferences, exposing the get/put/remove/clear subset
     * fcToForgeEntity uses. A value is resolvable while something else (the level's entity
     * storage) keeps it alive; once collected, get() answers null like a missing mapping.
     */
    private static final class WeakValueMap<K, V> {
        private final Map<K, java.lang.ref.WeakReference<V>> map = new WeakHashMap<>();

        V get(Object key) {
            java.lang.ref.WeakReference<V> ref = map.get(key);
            return ref != null ? ref.get() : null;
        }

        V put(K key, V value) {
            java.lang.ref.WeakReference<V> old = map.put(key, new java.lang.ref.WeakReference<>(value));
            return old != null ? old.get() : null;
        }

        V remove(Object key) {
            java.lang.ref.WeakReference<V> old = map.remove(key);
            return old != null ? old.get() : null;
        }

        void clear() {
            map.clear();
        }
    }

    
    public boolean spawnEntityInWorld(btw.modern.Entity entity) {
        if (entity == null) return false;
        try {
            // DIAGNOSTIC (design_build_logging U10 F): overworld ghasts come only from possessed-squid
            // conversion. A [GHAST-SPAWN] in dim=minecraft:overworld confirms the possession chain fired --
            // correlate with the BTW-Possession log (PossessionDiagnostics). Kept while STUB_CATALOGUE (f) is
            // open; remove together with PossessionDiagnostics.
            if ("FCEntityGhast".equals(entity.getClass().getSimpleName())) {
                LOGGER.warn("[GHAST-SPAWN] FCEntityGhast (squid->ghast conversion) dim={} pos=({}, {}, {})",
                        level.dimension().location(), entity.posX, entity.posY, entity.posZ);
            }
            net.minecraft.world.entity.Entity proxy =
                    EntityProxyFactory.createProxy(entity, level);
            if (proxy == null) {
                LOGGER.warn("EntityProxyFactory returned null for {}",
                        entity.getClass().getSimpleName());
                return false;
            }
            fcToForgeEntity.put(entity, proxy);
            return level.addFreshEntity(proxy);
        } catch (Exception e) {
            // Was DEBUG with the message only: a failing FC spawn (drops, projectiles, offspring) was invisible.
            warnOncePerThrowSite("SPAWN", "spawnEntityInWorld(" + entity.getClass().getSimpleName() + ")", e);
            return false;
        }
    }

    
    public void removeEntity(btw.modern.Entity entity) {
        if (entity == null) return;
        net.minecraft.world.entity.Entity proxy = fcToForgeEntity.remove(entity);
        if (proxy != null) {
            proxy.discard();
        }
    }

    
    @SuppressWarnings("unchecked")
    public List getEntitiesWithinAABB(Class entityClass, AxisAlignedBB aabb) {
        net.minecraft.world.phys.AABB forgeAABB = toForgeAABB(aabb);
        // 1.5.2 World.getEntitiesWithinAABB returned EntityItems like any entity. MC ItemEntities are not
        // proxies (EntityProxyFactory.createItemEntity), so they are exposed through their ItemEntityBridge
        // view whenever the requested class admits an EntityItem: EntityAnimal.CheckForLooseFood
        // (vanilla/server EntityAnimal.java:1322-1368), FCEntityAIMoveToLooseFood:59, FCBlockCookingVessel:60,
        // FCBlockBellows:432, the mob CheckForLooseFood variants, and frozen EntityLiving loot pickup
        // (EntityLiving.java:1933-2012; its onItemPickup cast to WorldServer, :2850, holds since WorldBridge
        // became one, so the picked-up item is setDead() instead of duplicated).
        final boolean wantItems = entityClass.isAssignableFrom(ItemEntityBridge.class);
        List<net.minecraft.world.entity.Entity> forgeEntities =
                level.getEntities((net.minecraft.world.entity.Entity) null, forgeAABB,
                        e -> e instanceof net.minecraft.world.entity.item.ItemEntity
                                ? wantItems : matchesFcClass(e, entityClass));
        // Return the FC entity instances rather than the proxies so FC
        // code can cast them to the expected FC types.
        List result = new ArrayList();
        for (net.minecraft.world.entity.Entity forgeEntity : forgeEntities) {
            btw.modern.Entity fc = extractFcEntityOrItem(forgeEntity);
            if (fc != null && entityClass.isInstance(fc)) {
                result.add(fc);
            }
        }
        return result;
    }

    
    public List getEntitiesWithinAABBExcludingEntity(btw.modern.Entity entity, AxisAlignedBB aabb) {
        net.minecraft.world.phys.AABB forgeAABB = toForgeAABB(aabb);
        net.minecraft.world.entity.Entity excludeProxy = entity instanceof ItemEntityBridge ib
                ? ib.getRealEntity() : fcToForgeEntity.get(entity);
        // 1.5.2 returned every entity in the box. FC projectiles test them all (EntityArrow.java:234-259,
        // EntityThrowable.java:164, EntityFireball.java:133: canBeCollidedWith + boundingBox intercept), so for
        // them vanilla MC entities are wrapped too -- FC arrows / thrown items / fireballs used to fly through
        // every vanilla mob, boat, end crystal and dragon part (wrapVanillaForFc). Other queries keep FC
        // entities and items only: frozen EntityLiving.func_85033_bc pushes what it finds, and MC already
        // pushes vanilla entities apart.
        final boolean wrapVanilla = isFcProjectile(entity);
        List<net.minecraft.world.entity.Entity> forgeEntities =
                level.getEntities(excludeProxy, forgeAABB, e -> true);
        List result = new ArrayList();
        for (net.minecraft.world.entity.Entity forgeEntity : forgeEntities) {
            btw.modern.Entity fc = extractFcEntityOrItem(forgeEntity);
            if (fc == null && wrapVanilla) {
                fc = wrapVanillaForFc(forgeEntity);
            }
            // `fc != entity`: puppets VanillaMobReplacer/chunk loading created are not in fcToForgeEntity, so
            // excludeProxy is null for them and 1.5.2's "excluding entity" must be applied here
            if (fc != null && fc != entity) {
                result.add(fc);
            }
        }
        return result;
    }

    
    public boolean checkNoEntityCollision(AxisAlignedBB aabb) {
        net.minecraft.world.phys.AABB forgeAABB = toForgeAABB(aabb);
        return level.getEntities((net.minecraft.world.entity.Entity) null,
                forgeAABB, e -> true).isEmpty();
    }

    
    public boolean canPlaceEntityOnSide(int blockID, int x, int y, int z,
                                        boolean skipEntities, int side,
                                        btw.modern.Entity entity, ItemStack stack) {
        if (skipEntities) return true;
        net.minecraft.world.phys.AABB checkBox = new net.minecraft.world.phys.AABB(
                x, y, z, x + 1, y + 1, z + 1);
        return level.getEntities((net.minecraft.world.entity.Entity) null,
                checkBox, e -> true).isEmpty();
    }

    // ------------------------------------------------------------------
    // Entity bridge helpers
    // ------------------------------------------------------------------

    /**
     * Converts an FC AxisAlignedBB to a Forge AABB.
     */
    private static net.minecraft.world.phys.AABB toForgeAABB(AxisAlignedBB aabb) {
        return new net.minecraft.world.phys.AABB(
                aabb.minX, aabb.minY, aabb.minZ,
                aabb.maxX, aabb.maxY, aabb.maxZ);
    }

    /**
     * Extracts the FC entity from a Forge proxy, or null if the entity
     * is not a BTW proxy.
     */
    private static btw.modern.Entity extractFcEntity(net.minecraft.world.entity.Entity forgeEntity) {
        if (forgeEntity instanceof ProxyMob pm) return pm.getFcEntity();
        if (forgeEntity instanceof ProxyAnimal pa) return pa.getFcEntity();
        if (forgeEntity instanceof ProxyPathfinderMob pp) return pp.getFcEntity();
        if (forgeEntity instanceof ProxyEntity pe) return pe.getFcEntity();
        // Players are not proxies — they have a PlayerBridge that wraps the
        // MC Player as an FC EntityPlayerMP. Without this, FC AI tasks that
        // query getEntitiesWithinAABB(EntityPlayer.class, ...) cannot see
        // any players, and mobs never acquire attack targets.
        if (forgeEntity instanceof net.minecraft.world.entity.player.Player p) {
            return PlayerBridge.getOrCreate(p);
        }
        return null;
    }

    /**
     * {@link #extractFcEntity} plus MC ItemEntities exposed as their FC EntityItem view (design_items U3).
     * Spawn-cap counting, the squid-possession cap and GetClosestEntityMatchingCriteriaWithinRange stay on
     * extractFcEntity: items never count there, and it avoids wrapping every item in the level.
     */
    private static btw.modern.Entity extractFcEntityOrItem(net.minecraft.world.entity.Entity forgeEntity) {
        if (forgeEntity instanceof net.minecraft.world.entity.item.ItemEntity ie) {
            return ItemEntityBridge.wrap(ie);
        }
        return extractFcEntity(forgeEntity);
    }

    /** FC projectiles whose 1.5.2 onUpdate hit-tests every entity getEntitiesWithinAABBExcludingEntity returns. */
    private static boolean isFcProjectile(btw.modern.Entity entity) {
        return entity instanceof btw.modern.EntityArrow
                || entity instanceof btw.modern.EntityThrowable
                || entity instanceof btw.modern.EntityFireball;
    }

    /**
     * A vanilla MC entity with no FC counterpart, wrapped for an FC projectile's hit test, or null. Only
     * entities a vanilla projectile could hit are returned (Projectile.canHitEntity -> canBeHitByProjectile =
     * isAlive && isPickable): the ender dragon body is not pickable but its parts are, as in 1.5.2
     * (EntityDragonPart). Living ones get the cached LivingEntityBridge (attackEntityFrom -> MC hurt,
     * addVelocity -> MC knockback, setFire -> MC fire), everything else its cached
     * {@link VanillaProjectileTarget} (boats, minecarts, paintings, item frames, End crystals, dragon parts,
     * fireballs), which forwards the hit to MC's own hurt() -- the shared EntityBridge answers
     * attackEntityFrom with false, which the frozen projectiles treat as a ricochet. Re-synced, with its box
     * set from MC: the bridges' syncFromReal leaves boundingBox at the constructor's empty box, and the
     * projectile intersects exactly that box (EntityArrow.java:245-247).
     */
    private btw.modern.Entity wrapVanillaForFc(net.minecraft.world.entity.Entity forgeEntity) {
        // Items, proxies and players have their own FC object (extractFcEntityOrItem); one that is not available
        // (removed item, puppet without its FC entity yet) must not be wrapped as a generic vanilla entity.
        if (forgeEntity instanceof net.minecraft.world.entity.item.ItemEntity
                || forgeEntity instanceof ProxyMob || forgeEntity instanceof ProxyAnimal
                || forgeEntity instanceof ProxyPathfinderMob || forgeEntity instanceof ProxyEntity
                || forgeEntity instanceof net.minecraft.world.entity.player.Player) {
            return null;
        }
        try {
            if (!forgeEntity.canBeHitByProjectile()) return null;
            btw.modern.Entity fc;
            if (forgeEntity instanceof net.minecraft.world.entity.LivingEntity living) {
                btw.modern.EntityLiving wrapped = LivingEntityBridge.wrapLiving(living);
                if (wrapped instanceof LivingEntityBridge lb) {
                    lb.syncFromReal();
                }
                fc = wrapped;
            } else {
                VanillaProjectileTarget target = projectileTargets.get(forgeEntity);
                if (target == null) {
                    target = new VanillaProjectileTarget(forgeEntity, this);
                    projectileTargets.put(forgeEntity, target);
                }
                target.syncFromReal(forgeEntity);
                fc = target;
            }
            if (fc == null) return null;
            net.minecraft.world.phys.AABB bb = forgeEntity.getBoundingBox();
            fc.boundingBox.setBounds(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
            return fc;
        } catch (Throwable t) {
            warnOncePerThrowSite("WRAP", "wrapping " + forgeEntity.getClass().getSimpleName() + " for FC", t);
            return null;
        }
    }

    /**
     * The MC entity an FC entity stands for: the real entity behind a player / living / item / vanilla-target
     * view, or an FC entity's proxy ({@link #proxyFor}). Null if none.
     */
    private net.minecraft.world.entity.Entity toMcEntity(btw.modern.Entity fc) {
        if (fc == null) return null;
        if (fc instanceof PlayerBridge pb) return pb.getRealPlayer();
        if (fc instanceof LivingEntityBridge lb) return lb.getRealEntity();
        if (fc instanceof EntityBridge eb) return eb.getRealEntity();
        if (fc instanceof ItemEntityBridge ib) return ib.getRealEntity();
        if (fc instanceof VanillaProjectileTarget target) return target.real();
        return proxyFor(fc);
    }

    /**
     * MC damage source for an FC one that hits an MC entity. The 1.5.2 projectile sources -- DamageSource
     * causeArrowDamage / causeThrownDamage / causeFireballDamage / causeIndirectMagicDamage, all
     * EntityDamageSourceIndirect(type, projectile, shooter) -- become the matching 1.20.1 type with the
     * projectile's MC entity as the direct and the shooter's as the causing entity, so the victim credits and
     * retaliates against the shooter, shields and projectile protection apply, and the death message reads
     * "shot by". DamageSources.arrow / fireball take an AbstractArrow / Fireball, which an FC projectile's
     * ProxyEntity is not, so the source is built from the registry holder exactly as DamageSources.source(key,
     * direct, causing) does. Everything else goes through {@link DamageSourceMapping#getModern}. Never throws.
     */
    public static net.minecraft.world.damagesource.DamageSource toMcDamageSource(
            btw.modern.DamageSource fcSource, ServerLevel level) {
        if (fcSource instanceof btw.modern.EntityDamageSourceIndirect && fcSource.damageType != null) {
            ResourceKey<net.minecraft.world.damagesource.DamageType> type = switch (fcSource.damageType) {
                case "arrow" -> net.minecraft.world.damagesource.DamageTypes.ARROW;
                case "thrown" -> net.minecraft.world.damagesource.DamageTypes.THROWN;
                case "fireball" -> net.minecraft.world.damagesource.DamageTypes.FIREBALL;
                // causeFireballDamage without a shooter: EntityDamageSourceIndirect("onFire", fireball, fireball)
                case "onFire" -> net.minecraft.world.damagesource.DamageTypes.UNATTRIBUTED_FIREBALL;
                case "indirectMagic" -> net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC;
                default -> null;
            };
            if (type != null) {
                try {
                    WorldBridge bridge = getOrCreate(level);
                    net.minecraft.world.entity.Entity direct = bridge.toMcEntity(fcSource.getSourceOfDamage());
                    net.minecraft.world.entity.Entity causing = bridge.toMcEntity(fcSource.getEntity());
                    if (causing == null && type == net.minecraft.world.damagesource.DamageTypes.FIREBALL) {
                        type = net.minecraft.world.damagesource.DamageTypes.UNATTRIBUTED_FIREBALL; // as DamageSources.fireball
                    }
                    net.minecraft.core.Registry<net.minecraft.world.damagesource.DamageType> types =
                            level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
                    return new net.minecraft.world.damagesource.DamageSource(
                            types.getHolderOrThrow(type), direct, causing);
                } catch (Throwable t) {
                    warnOncePerThrowSite("DAMAGE-SOURCE", "mapping FC damage source '" + fcSource.damageType + "'", t);
                }
            }
        }
        return DamageSourceMapping.getModern(fcSource, level);
    }

    /**
     * FC view of a NON-living vanilla entity for an FC projectile's hit test (boat, minecart, painting, item
     * frame, End crystal, ender dragon part, ghast fireball, shulker bullet, ...). In 1.5.2 the projectile
     * damaged these through their own attackEntityFrom (EntityBoat, EntityHanging, EntityEnderCrystal,
     * EntityDragonPart -> EntityDragon.attackEntityFromPart); here the hit is MC's own hurt() with the
     * projectile's MC damage source ({@link #toMcDamageSource}), and hurt()'s result is returned: boats and
     * minecarts take damage, hanging entities break, the crystal explodes, EnderDragonPart.hurt forwards to the
     * dragon with the head multiplier, and a rejected hit (falling block, invulnerable entity) ricochets
     * (vanilla/server EntityArrow.java:308-347), exactly as a vanilla arrow does (AbstractArrow.onHitEntity).
     * Cached per entity ({@link #projectileTargets}); holds the entity weakly, like EntityBridge.
     */
    private static final class VanillaProjectileTarget extends btw.modern.Entity {
        private final java.lang.ref.WeakReference<net.minecraft.world.entity.Entity> realRef;

        VanillaProjectileTarget(net.minecraft.world.entity.Entity real, WorldBridge world) {
            super(null); // btw.modern.Entity(World), as EntityBridge
            this.realRef = new java.lang.ref.WeakReference<>(real);
            this.worldObj = world;
        }

        /** The wrapped entity; null while super(null) runs or once it has been collected. */
        net.minecraft.world.entity.Entity real() {
            return realRef != null ? realRef.get() : null;
        }

        /** Position, motion and liveness from MC before each hit test (the box is set by wrapVanillaForFc). */
        void syncFromReal(net.minecraft.world.entity.Entity real) {
            this.entityId = real.getId();
            this.posX = real.getX();
            this.posY = real.getY();
            this.posZ = real.getZ();
            this.prevPosX = real.xOld;
            this.prevPosY = real.yOld;
            this.prevPosZ = real.zOld;
            net.minecraft.world.phys.Vec3 v = real.getDeltaMovement();
            this.motionX = v.x;
            this.motionY = v.y;
            this.motionZ = v.z;
            this.rotationYaw = real.getYRot();
            this.rotationPitch = real.getXRot();
            this.onGround = real.onGround();
            this.isDead = real.isRemoved();
            this.width = real.getBbWidth();
            this.height = real.getBbHeight();
        }

        // Abstract in the frozen 1.5.2 Entity (its constructor calls entityInit); never saved.
        @Override
        public void entityInit() {
        }

        @Override
        public void readEntityFromNBT(btw.modern.NBTTagCompound tag) {
        }

        @Override
        public void writeEntityToNBT(btw.modern.NBTTagCompound tag) {
        }

        @Override
        public boolean canBeCollidedWith() {
            net.minecraft.world.entity.Entity r = real();
            return r != null && r.canBeHitByProjectile();
        }

        @Override
        public boolean attackEntityFrom(btw.modern.DamageSource damageSource, int amount) {
            net.minecraft.world.entity.Entity r = real();
            if (r == null || !(r.level() instanceof ServerLevel sl)) return false;
            try {
                return r.hurt(WorldBridge.toMcDamageSource(damageSource, sl), (float) amount);
            } catch (Throwable t) {
                WorldBridge.warnOncePerThrowSite("PROJECTILE-HIT",
                        "FC projectile hit on " + r.getClass().getSimpleName(), t);
                return false;
            }
        }

        @Override
        public void setFire(int seconds) {
            net.minecraft.world.entity.Entity r = real();
            if (r != null) {
                r.setSecondsOnFire(seconds); // AbstractArrow.onHitEntity: a burning arrow ignites what it hits
            }
        }

        @Override
        public boolean isBurning() {
            net.minecraft.world.entity.Entity r = real();
            return r != null && r.isOnFire();
        }

        @Override
        public boolean isEntityAlive() {
            net.minecraft.world.entity.Entity r = real();
            return r != null && r.isAlive();
        }

        @Override
        public boolean canBePushed() {
            net.minecraft.world.entity.Entity r = real();
            return r != null && r.isPushable();
        }

        @Override
        public void addVelocity(double x, double y, double z) {
            this.motionX += x;
            this.motionY += y;
            this.motionZ += z;
            net.minecraft.world.entity.Entity r = real();
            if (r != null) {
                r.push(x, y, z);
            }
        }

        @Override
        public void setDead() {
            super.setDead();
            net.minecraft.world.entity.Entity r = real();
            if (r != null) {
                r.discard();
            }
        }

        @Override
        public String getEntityName() {
            net.minecraft.world.entity.Entity r = real();
            return r != null ? r.getName().getString() : super.getEntityName();
        }
    }

    /** Failure sites already reported by {@link #warnOncePerThrowSite}. */
    private static final java.util.Set<String> LOGGED_FAILURE_SITES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * One WARN with its stack trace per distinct throw site (stage + exception class + top frame), triage
     * HAI-10: these failures were dropped at DEBUG with the message only (or logged without a trace), which hid
     * how often an FC path fails and where. Reflection wrappers are unwrapped to the FC exception first.
     */
    private static void warnOncePerThrowSite(String stage, String what, Throwable t) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                ? t.getCause() : t;
        StackTraceElement[] frames = cause.getStackTrace();
        String site = stage + '|' + cause.getClass().getName() + '@' + (frames.length > 0 ? frames[0] : "?");
        if (LOGGED_FAILURE_SITES.add(site)) {
            LOGGER.warn("[{}] {} threw {} (logged once per throw site)", stage, what, cause.toString(), cause);
        }
    }

    /**
     * Checks whether a Forge entity wraps an FC entity that is an
     * instance of the given FC class.
     */
    private static boolean matchesFcClass(
            net.minecraft.world.entity.Entity forgeEntity, Class<?> fcClass) {
        btw.modern.Entity fc = extractFcEntity(forgeEntity);
        return fc != null && fcClass.isInstance(fc);
    }

    // ================================================================
    // Sound methods
    // ================================================================


    public void playSoundEffect(double x, double y, double z,
                                String sound, float volume, float pitch) {
        try {
            SoundEvent event = resolveSoundEvent(sound);
            if (event != null) {
                level.playSound(null, x, y, z, event,
                        SoundSource.BLOCKS, volume, pitch);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not play sound '{}': {}", sound, e.getMessage());
        }
    }


    public void playSoundAtEntity(btw.modern.Entity entity, String sound,
                                  float volume, float pitch) {
        // A puppet's own hurt sound raised inside its onUpdate() is held back until FCEnvHurtGuard knows
        // whether the hit was FC's spurious inWall self-hit (dropped) or a real one (replayed) -- w1 P4.
        if (FCEnvHurtGuard.deferSelfSound(entity, sound, volume, pitch)) return;
        try {
            SoundEvent event = resolveSoundEvent(sound);
            if (event != null && entity != null) {
                // Use entity's FC position to play the sound at its location
                level.playSound(null, entity.posX, entity.posY, entity.posZ,
                        event, SoundSource.NEUTRAL, volume, pitch);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not play sound '{}' at entity: {}",
                    sound, e.getMessage());
        }
    }

    @Override
    public void playSoundToNearExcept(EntityPlayer player, String sound,
                                      float volume, float pitch) {
        try {
            SoundEvent event = resolveSoundEvent(sound);
            if (event != null && player != null) {
                // Play sound to all nearby players EXCEPT the given player.
                // If the player is a PlayerBridge, use the real ServerPlayer
                // so level.playSound excludes them from hearing it.
                ServerPlayer excluded = null;
                if (player instanceof PlayerBridge pb) {
                    excluded = pb.getServerPlayer();
                }
                level.playSound(excluded, player.posX, player.posY, player.posZ,
                        event, SoundSource.PLAYERS, volume, pitch);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not play sound '{}' near-except: {}",
                    sound, e.getMessage());
        }
    }


    // 1.5.2 World.spawnParticle — bridges FC particle names to ServerLevel.sendParticles.
    // Name set enumerated from FC spawnParticle callers (smoke, largesmoke, largeexplode,
    // reddust, snowballpoof, iconcrack_*, fcwhitesmoke/fcwhitecloud/fcsmallflame/fccinders, ...).
    // count=0 with speed=1 preserves the 1.5.2 single-particle-with-velocity semantics.
    public void spawnParticle(String particle, double x, double y, double z,
                              double velocityX, double velocityY, double velocityZ) {
        if (particle == null || particle.isEmpty()) return;
        try {
            // reddust encodes its color in the velocity args (1.5.2 EntityReddustFX:
            // red defaults to 1.0 when velocityX is 0)
            if (particle.equals("reddust")) {
                float red = velocityX == 0.0D ? 1.0F : (float) velocityX;
                net.minecraft.core.particles.DustParticleOptions dust =
                        new net.minecraft.core.particles.DustParticleOptions(
                                new org.joml.Vector3f(red, (float) velocityY, (float) velocityZ), 1.0F);
                level.sendParticles(dust, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                return;
            }
            net.minecraft.core.particles.ParticleOptions options = resolveParticleOptions(particle);
            if (options != null) {
                level.sendParticles(options, x, y, z, 0, velocityX, velocityY, velocityZ, 1.0D);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not spawn particle '{}': {}", particle, e.getMessage());
        }
    }

    /**
     * Maps a 1.5.2 particle name (as used by FC code) to a modern ParticleOptions.
     * Returns null for names with no modern equivalent.
     */
    private static net.minecraft.core.particles.ParticleOptions resolveParticleOptions(String name) {
        // iconcrack_<itemID> → item break particles for the legacy item ID
        if (name.startsWith("iconcrack_")) {
            try {
                int legacyItemId = Integer.parseInt(name.substring("iconcrack_".length()));
                net.minecraft.world.item.Item item = ProxyRegistry.getModernItem(legacyItemId);
                if (item != null) {
                    return new net.minecraft.core.particles.ItemParticleOption(
                            net.minecraft.core.particles.ParticleTypes.ITEM,
                            new net.minecraft.world.item.ItemStack(item));
                }
            } catch (NumberFormatException ignored) {}
            return null;
        }
        return switch (name) {
            case "smoke" -> net.minecraft.core.particles.ParticleTypes.SMOKE;
            case "largesmoke" -> net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE;
            case "largeexplode" -> net.minecraft.core.particles.ParticleTypes.EXPLOSION;
            case "hugeexplosion" -> net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER;
            case "explode" -> net.minecraft.core.particles.ParticleTypes.POOF;
            case "flame" -> net.minecraft.core.particles.ParticleTypes.FLAME;
            case "lava" -> net.minecraft.core.particles.ParticleTypes.LAVA;
            case "note" -> net.minecraft.core.particles.ParticleTypes.NOTE;
            case "portal" -> net.minecraft.core.particles.ParticleTypes.PORTAL;
            case "enchantmenttable" -> net.minecraft.core.particles.ParticleTypes.ENCHANT;
            case "townaura" -> net.minecraft.core.particles.ParticleTypes.MYCELIUM;
            case "dripWater" -> net.minecraft.core.particles.ParticleTypes.DRIPPING_WATER;
            case "dripLava" -> net.minecraft.core.particles.ParticleTypes.DRIPPING_LAVA;
            case "bubble" -> net.minecraft.core.particles.ParticleTypes.BUBBLE;
            case "splash" -> net.minecraft.core.particles.ParticleTypes.SPLASH;
            // snowshovel/snowballpoof both rendered snowball puffs in 1.5.2
            case "snowshovel", "snowballpoof" -> net.minecraft.core.particles.ParticleTypes.ITEM_SNOWBALL;
            case "slime" -> net.minecraft.core.particles.ParticleTypes.ITEM_SLIME;
            case "heart" -> net.minecraft.core.particles.ParticleTypes.HEART;
            case "angryVillager" -> net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER;
            case "happyVillager" -> net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER;
            case "witchMagic" -> net.minecraft.core.particles.ParticleTypes.WITCH;
            case "mobSpell" -> net.minecraft.core.particles.ParticleTypes.ENTITY_EFFECT;
            case "mobSpellAmbient" -> net.minecraft.core.particles.ParticleTypes.AMBIENT_ENTITY_EFFECT;
            case "spell" -> net.minecraft.core.particles.ParticleTypes.EFFECT;
            case "instantSpell" -> net.minecraft.core.particles.ParticleTypes.INSTANT_EFFECT;
            case "crit" -> net.minecraft.core.particles.ParticleTypes.CRIT;
            case "magicCrit" -> net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT;
            // FC custom particles — closest modern visual equivalents
            case "fcwhitesmoke", "fcwhitecloud" -> net.minecraft.core.particles.ParticleTypes.CLOUD;
            case "fcsmallflame" -> net.minecraft.core.particles.ParticleTypes.SMALL_FLAME;
            case "fccinders" -> net.minecraft.core.particles.ParticleTypes.ASH;
            default -> null;
        };
    }

    
    public void playAuxSFX(int effectID, int x, int y, int z, int data) {
        // FC custom effect IDs (2222+) need special handling — MC 1.20.1's
        // levelEvent only knows vanilla IDs. Map FC effects to sound playback.
        if (effectID >= 2222) {
            playFcAuxSFX(effectID, x, y, z, data);
            return;
        }
        playLegacyAuxSFX(effectID, x, y, z, data);
    }

    /** 1.5.2 ids already reported as having no 1.20.1 equivalent (logged once each). */
    private static final java.util.Set<Integer> DROPPED_AUX_SFX = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A 1.5.2 vanilla aux-SFX (id &lt; 2222; vanilla/client RenderGlobal.playAuxSFX :2169-2399), translated to
     * its 1.20.1 level event by {@link AuxSfxMapping}. The ids are numbered differently in 1.20.1 and used to
     * be forwarded raw: every fc_bat takeoff (1015, a 0.05-volume flutter) played GHAST_WARN at volume 10 to
     * everyone within 64 blocks (triage GH1), a zombie infecting / curing a villager (1016 / 1017) a ghast /
     * dragon fireball (GH3), a torch fizz (1004) a firework launch. 1.5.2 effects with no 1.20.1 event are
     * played here (1003 door) or dropped -- never passed raw.
     */
    private void playLegacyAuxSFX(int effectID, int x, int y, int z, int data) {
        try {
            int modern = AuxSfxMapping.toModernEvent(effectID, data);
            int modernData = modern != AuxSfxMapping.NONE ? AuxSfxMapping.toModernData(effectID, data) : 0;
            if (modern != AuxSfxMapping.NONE && modernData != AuxSfxMapping.INVALID_DATA) {
                level.levelEvent(null, modern, new BlockPos(x, y, z), modernData);
            }
            double px = x + 0.5D, py = y + 0.5D, pz = z + 0.5D;
            switch (effectID) {
                case 1003: // door open/close, picked at random (RenderGlobal.java:2198-2208); no 1.20.1 event
                    playSoundEffect(px, py, pz, rand.nextBoolean() ? "random.door_open" : "random.door_close",
                            1.0F, rand.nextFloat() * 0.1F + 0.9F);
                    break;
                case 1016: // FCMOD: extra slow zombie moan on villager infection (RenderGlobal.java:2260-2263)
                    playSoundEffect(px, py, pz, "mob.zombie.say", 1.0F + rand.nextFloat(),
                            rand.nextFloat() * 0.2F + 0.5F);
                    break;
                case 2001: // block 1.20.1 cannot express: no particles, but still its FC break sound
                    if (modernData == AuxSfxMapping.INVALID_DATA) {
                        playFcBlockSound(px, py, pz, data & 4095, true);
                    }
                    break;
                default:
                    if (modern == AuxSfxMapping.NONE && DROPPED_AUX_SFX.add(effectID)) {
                        LOGGER.debug("1.5.2 aux-SFX {} has no 1.20.1 level event; dropped", effectID);
                    }
                    break;
            }
        } catch (Throwable t) {
            if (DROPPED_AUX_SFX.add(-effectID - 1)) {   // negative key: "failed", logged once per id
                LOGGER.warn("aux-SFX {} at {},{},{} failed: {}", effectID, x, y, z, t.toString());
            }
        }
    }

    /** Handles FC-custom AuxSFX IDs by playing the corresponding sounds. */
    private void playFcAuxSFX(int effectID, int x, int y, int z, int data) {
        double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
        java.util.Random rand = new java.util.Random();
        // Map FC effect IDs to their sound equivalents (from FC's client handler)
        switch (effectID) {
            case 2226: // burp
                playSoundEffect(px, py, pz, "random.burp", 0.5F, rand.nextFloat() * 0.1F + 0.9F);
                break;
            case 2227: // fire fizz
                playSoundEffect(px, py, pz, "random.fizz", 0.5F, 2.6F + (rand.nextFloat() - rand.nextFloat()) * 0.8F);
                break;
            case 2231: // item collection pop
            case 2232: // XP eject pop
                playSoundEffect(px, py, pz, "random.pop", 0.2F, ((rand.nextFloat() - rand.nextFloat()) * 0.7F + 1F) * 2F);
                break;
            case 2233: // hopper close
                playSoundEffect(px, py, pz, "random.chestclosed", 0.1F, rand.nextFloat() * 0.1F + 0.9F);
                break;
            case 2234: // redstone power click
                playSoundEffect(px, py, pz, "random.click", 0.3F, 0.6F);
                break;
            case 2236: // block place
                playSoundEffect(px, py, pz, "dig.stone", 1F, 0.8F);
                break;
            case 2237: // dynamite fuse
                playSoundEffect(px, py, pz, "random.fuse", 1F, 1F);
                break;
            case 2238: // click low pitch
                playSoundEffect(px, py, pz, "random.click", 0.3F, 0.5F);
                break;
            case 2267: // stump removed
                playSoundEffect(px, py, pz, "mob.slime.attack", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 0.6F);
                break;
            case 2268: // shaft ripped off log
            case 2271: // wood block destroyed
                playSoundEffect(px, py, pz, "mob.zombie.woodbreak", 0.25F, 1F + rand.nextFloat() * 0.25F);
                break;
            case 2269: // stone ripped off
                playSoundEffect(px, py, pz, "random.anvil_land", 0.5F, rand.nextFloat() * 0.25F + 1.75F);
                break;
            case 2270: // gravel ripped off stone
                playSoundEffect(px, py, pz, "random.anvil_land", 0.25F, rand.nextFloat() * 0.25F + 1.5F);
                playSoundEffect(px, py, pz, "step.gravel", 1F, rand.nextFloat() * 0.25F + 1F);
                break;
            case 2274: // mortar applied
                playSoundEffect(px, py, pz, "mob.slime.attack", 0.7F + rand.nextFloat() * 0.1F, 0.85F + rand.nextFloat() * 0.1F);
                break;
            case 2275: // loose block on mortar
                playSoundEffect(px, py, pz, "mob.slime.attack", 0.15F + rand.nextFloat() * 0.1F, 0.6F + rand.nextFloat() * 0.1F);
                break;
            case 2276: // log smouldering fall
                playSoundEffect(px, py, pz, "mob.zombie.woodbreak", 1.25F, 0.5F + rand.nextFloat() * 0.1F);
                playSoundEffect(px, py, pz, "mob.ghast.fireball", 1F, 0.5F + rand.nextFloat() * 0.1F);
                break;
            case 2278: // water evaporate
                playSoundEffect(px, py, pz, "random.fizz", 0.5F, 2.6F + (rand.nextFloat() - rand.nextFloat()) * 0.8F);
                break;
            case 2280: // lightning strike
                playSoundEffect(px, py, pz, "ambient.weather.thunder", 10000F, 0.8F + rand.nextFloat() * 0.2F);
                break;
            case 2283: // animal eat
            case 2284: // wolf eat
                playSoundEffect(px, py, pz, "random.eat", 0.5F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                break;
            case 2285: // eat fail
                playSoundEffect(px, py, pz, "random.eat", 0.25F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 0.5F);
                break;
            // ---- ported from FC ClientPlayCustomAuxFX (2026-07-09) — these effects were
            // silent (fired but unhandled). Sound side only; the FC particle side is a
            // separate follow-up (needs client particle spawning). 2241 (dispenser smoke)
            // and 2282 (cactus explode) are particle-only in FC, so they stay silent.
            case 2222: // animal birthing
                playSoundEffect(px, py, pz, "mob.slime.attack", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                break;
            case 2223: // saw damages entity
                playSoundEffect(px, py, pz, "minecart.base", 1F + rand.nextFloat() * 0.1F, 2F + rand.nextFloat() * 0.1F);
                break;
            case 2224: // nether growth spores burst
                playSoundEffect(px, py, pz, "random.fuse", 2F, rand.nextFloat() * 0.4F + 1.5F);
                break;
            case 2225: // ghast scream (data==1 = soulforged-steel low pitch)
                playSoundEffect(px, py, pz, "mob.ghast.scream", 1F,
                        data == 1 ? rand.nextFloat() * 0.4F + 0.25F : rand.nextFloat() * 0.4F + 0.8F);
                break;
            case 2228: // ghast moan
                playSoundEffect(px, py, pz, "mob.ghast.moan", 0.5F, 2.6F + (rand.nextFloat() - rand.nextFloat()) * 0.8F);
                break;
            case 2229: // mining charge explosion
                playSoundEffect(px, py, pz, "random.explode", 4F, (1F + (rand.nextFloat() - rand.nextFloat()) * 0.2F) * 0.7F);
                break;
            case 2230: // hopper XP eject
                playSoundEffect(px, py, pz, "liquid.lavapop", 0.5F + rand.nextFloat() * 0.25F, 0.5F + rand.nextFloat() * 0.25F);
                break;
            case 2235: // mechanical device explode
                playSoundEffect(px, py, pz, "mob.zombie.woodbreak", 0.5F, 0.60F + rand.nextFloat() * 0.25F);
                break;
            case 2239: // wolf hurt
                playSoundEffect(px, py, pz, "mob.wolf.hurt", 0.4F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                break;
            case 2240: // chicken hurt
                playSoundEffect(px, py, pz, "mob.chicken.hurt", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                break;
            case 2242: // companion cube death
                playSoundEffect(px, py, pz, "mob.wolf.whine", 0.5F, 2.6F + (rand.nextFloat() - rand.nextFloat()) * 0.8F);
                break;
            case 2243: // possessed chicken explosion
                playSoundEffect(px, py, pz, "random.explode", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                playSoundEffect(px, py, pz, "mob.chicken.hurt", 2F, rand.nextFloat() * 0.4F + 1.2F);
                break;
            case 2244: // ender block collect
                playFcBlockSound(px, py, pz, data & 0xfff, true);
                break;
            case 2245: // ender block convert
                playFcBlockSound(px, py, pz, data & 0xfff, true);
                playSoundEffect(px, py, pz, "mob.endermen.portal", 1F, 1F);
                break;
            case 2246: // ender block place
                playFcBlockSound(px, py, pz, data & 0xfff, false);
                playSoundEffect(px, py, pz, "mob.endermen.hit", 1F, 1F);
                break;
            case 2247: // ender change dimension
                playSoundEffect(px, py, pz, "ambient.weather.thunder", 3F, rand.nextFloat() * 0.4F + 0.8F);
                break;
            case 2248: // soul urn shatter
                playSoundEffect(px, py, pz, "random.glass", 1F, 1.2F / (rand.nextFloat() * 0.2F + 0.9F));
                playSoundEffect(px, py, pz, "mob.ghast.scream", 0.2F, rand.nextFloat() * 0.2F + 0.5F);
                break;
            case 2251: // melon impact
                playSoundEffect(px, py, pz, "mob.zombie.wood", 0.1F, 0.40F + rand.nextFloat() * 0.25F);
                break;
            case 2252: // block destroy (respect particle settings)
                playFcBlockSound(px, py, pz, data & 0xfff, true);
                break;
            case 2253: // cow milk fill
            case 2254: // cow milked
            case 2261: // sheep wool regrow
            case 2262: // squid tentacle fling
                playSoundEffect(px, py, pz, "mob.slime.attack", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 0.6F);
                break;
            case 2255: // cow -> mooshroom
                playSoundEffect(px, py, pz, "mob.slime.attack", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                playSoundEffect(px, py, pz, "mob.cow.hurt", 1F,
                        (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F + (data > 0 ? 0.5F : 0F));
                break;
            case 2258: // creeper neutered
                playSoundEffect(px, py, pz, "mob.sheep.shear", 1F, 1F);
                playSoundEffect(px, py, pz, "mob.slime.attack", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.1F + 0.7F);
                break;
            case 2259: // possessed pig -> zombie pigman
                playSoundEffect(px, py, pz, "mob.pig.death", 2F, rand.nextFloat() * 0.4F + 1.2F);
                playSoundEffect(px, py, pz, "mob.zombiepig.zpigangry", 2F, ((rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F) * 1.8F);
                playSoundEffect(px, py, pz, "mob.slime.attack", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                break;
            case 2260: // possessed villager -> witch
                playSoundEffect(px, py, pz, "ambient.weather.thunder", 3F, rand.nextFloat() * 0.4F + 0.8F);
                playSoundEffect(px, py, pz, "mob.ghast.affectionate scream", 2F, 0.5F + rand.nextFloat() * 0.25F);
                break;
            case 2263: // snow golem created
                playSoundEffect(px, py, pz, "random.glass", 1F, 1.2F / (rand.nextFloat() * 0.2F + 0.9F));
                playSoundEffect(px, py, pz, "mob.enderdragon.growl", 0.25F, rand.nextFloat() * 0.2F + 1.8F);
                break;
            case 2264: // iron golem created
                playSoundEffect(px, py, pz, "random.glass", 1F, 1.2F / (rand.nextFloat() * 0.2F + 0.9F));
                playSoundEffect(px, py, pz, "mob.irongolem.death", 1F, rand.nextFloat() * 0.2F + 0.5F);
                playSoundEffect(px, py, pz, "mob.enderdragon.growl", 0.5F, rand.nextFloat() * 0.2F + 1.5F);
                break;
            case 2265: // toss the milk
                playSoundEffect(px, py, pz, "mob.slime.attack", 0.5F, (rand.nextFloat() - rand.nextFloat()) * 0.1F + 0.6F);
                playSoundEffect(px, py, pz, "random.classic_hurt", 0.25F, 1.2F);
                break;
            case 2266: // dung applied to wolf
                playSoundEffect(px, py, pz, "mob.slime.attack", 0.5F, (rand.nextFloat() - rand.nextFloat()) * 0.1F + 0.8F);
                break;
            case 2272: // block destroyed with improper tool
                playSoundEffect(px, py, pz, "mob.zombie.woodbreak", 0.25F, 1F + rand.nextFloat() * 0.25F);
                break;
            case 2273: // possessed squid -> ghast
                playSoundEffect(px, py, pz, "mob.slime.attack", 1F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                playSoundEffect(px, py, pz, "mob.ghast.scream", 10F, (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F);
                break;
            case 2277: // log smouldering explosion
                playSoundEffect(px, py, pz, "random.explode", 4F, (1F + (rand.nextFloat() - rand.nextFloat()) * 0.2F) * 0.7F);
                playSoundEffect(px, py, pz, "mob.zombie.wood", 1.25F, 0.5F + rand.nextFloat() * 0.1F);
                break;
            case 2279: // wither created
                playSoundEffect(px, py, pz, "random.glass", 1F, 1.2F / (rand.nextFloat() * 0.2F + 0.9F));
                playSoundEffect(px, py, pz, "mob.wither.death", 1F, rand.nextFloat() * 0.2F + 0.5F);
                playSoundEffect(px, py, pz, "mob.enderdragon.growl", 0.5F, rand.nextFloat() * 0.2F + 1.5F);
                break;
            case 2281: // flaming netherrack fall
                playSoundEffect(px, py, pz, "mob.ghast.fireball", 0.1F, 0.5F + rand.nextFloat() * 0.1F);
                break;
            default:
                // FC effect with no sound side here (particle-only 2241/2249/2250/2282, ...). Not forwarded
                // raw: 1.20.1 level-event ids mean something else (GH1).
                if (DROPPED_AUX_SFX.add(effectID)) {
                    LOGGER.debug("FC aux-SFX {} has no sound mapping; dropped", effectID);
                }
                break;
        }
    }

    /**
     * Plays a block's break/step sound for the ender-block and destroy aux-FX, which pack the
     * source block id into the aux-FX data (id & 0xfff). Mirrors FC's
     * {@code block.stepSound.getBreakSound()/getStepSound()} lookup in ClientPlayCustomAuxFX.
     */
    private void playFcBlockSound(double px, double py, double pz, int blockId, boolean breakSound) {
        try {
            if (blockId > 0 && blockId < btw.modern.Block.blocksList.length) {
                btw.modern.Block blk = btw.modern.Block.blocksList[blockId];
                if (blk != null && blk.stepSound != null) {
                    String s = breakSound ? blk.stepSound.getBreakSound() : blk.stepSound.getStepSound();
                    playSoundEffect(px, py, pz, s,
                            (blk.stepSound.getVolume() + 1.0F) / 2.0F, blk.stepSound.getPitch() * 0.8F);
                }
            }
        } catch (Throwable ignored) {}
    }

    // 1.5.2 World.playAuxSFXAtEntity reaches the same RenderGlobal.playAuxSFX (FC ids >= 2222 via its
    // FCAddOnHandler default branch), so it shares playAuxSFX's translation. Everyone hears it, as before:
    // the 1.5.2 exclusion of `player` assumed that player's client had already played it locally.
    public void playAuxSFXAtEntity(EntityPlayer player, int effectID,
                                   int x, int y, int z, int data) {
        playAuxSFX(effectID, x, y, z, data);
    }

    // 1.5.2 World.func_82739_e (vanilla/server World.java:3900) — sends an auxSFX to all
    // players regardless of distance. Live callers: FCEntityAIWolfHowl.java:117 /
    // FCEntityAIWolfDireHowl.java:78 (howls), FCEntityWolf.java:894 (dire conversion),
    // FCEntityLightningBolt.java:82 (strike FX). Sound handling ported from the FC
    // client aux-FX handler (Client FCBetterThanWolves.java:3448+); high volumes give
    // the long audible range MC derives from volume.
    @Override
    public void func_82739_e(int effectID, int x, int y, int z, int data) {
        double px = x + 0.5D, py = y + 0.5D, pz = z + 0.5D;
        switch (effectID) {
            case 2256: { // FCBetterThanWolves.m_iWolfHowlAuxFXID
                float fSoundVolume;
                float fSoundPitch;
                if (data > 0) {
                    // dire howl
                    fSoundVolume = 10F;
                    fSoundPitch = (rand.nextFloat() - rand.nextFloat()) * 0.05F + 0.55F;
                } else {
                    // regular wolf howl
                    fSoundVolume = 8.5F;
                    fSoundPitch = (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1F;
                }
                playSoundEffect(px, py, pz, "mob.wolf.howl", fSoundVolume, fSoundPitch);
                break;
            }
            case 2257: // FCBetterThanWolves.m_iWolfConvertToDireAuxFXID
                spawnParticle("largeexplode", px, py, pz, 0.0D, 0.0D, 0.0D);
                playSoundEffect(px, py, pz, "mob.slime.attack", 1.0F,
                        (rand.nextFloat() - rand.nextFloat()) * 0.2F + 1.0F);
                playSoundEffect(px, py, pz, "mob.wolf.growl", 8.5F,
                        (rand.nextFloat() - rand.nextFloat()) * 0.05F + 0.55F);
                break;
            default:
                if (effectID >= 2222) {
                    // Other FC custom IDs (e.g. 2280 lightning strike) share the
                    // playAuxSFX sound mapping.
                    playFcAuxSFX(effectID, x, y, z, data);
                } else {
                    // Vanilla broadcast IDs: 1.5.2 RenderGlobal.broadcastSound plays only 1013 (wither spawn)
                    // and 1018 (dragon death), which 1.20.1 numbers 1023 / 1028; a global level event reaches
                    // every player like broadcastSound. Any other id played nothing in 1.5.2 (and was
                    // forwarded raw here before).
                    int global = AuxSfxMapping.toModernGlobalEvent(effectID);
                    if (global != AuxSfxMapping.NONE) {
                        level.globalLevelEvent(global, new BlockPos(x, y, z), data);
                    }
                }
                break;
        }
    }

    // ================================================================
    // Weather
    // ================================================================

    
    public boolean isRaining() {
        return level.isRaining();
    }


    public boolean isThundering() {
        return level.isThundering();
    }

    @Override
    public float getRainStrength(float partialTicks) {
        return level.getRainLevel(partialTicks);
    }

    @Override
    public float getWeightedThunderStrength(float partialTicks) {
        return level.getThunderLevel(partialTicks);
    }

    @Override
    public boolean IsRainingAtPos(int i, int j, int k) {
        return level.isRainingAt(new net.minecraft.core.BlockPos(i, j, k));
    }

    @Override
    public boolean IsSnowingAtPos(int i, int j, int k) {
        if (!isRaining()) return false;
        if (!canBlockSeeTheSky(i, j, k)) return false;
        if (getPrecipitationHeight(i, k) > j) return false;
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(i, j, k);
        net.minecraft.world.level.biome.Biome biome = level.getBiome(pos).value();
        return biome.coldEnoughToSnow(pos);
    }

    @Override
    public boolean IsPrecipitatingAtPos(int i, int j, int k) {
        return IsRainingAtPos(i, j, k) || IsSnowingAtPos(i, j, k);
    }

    // ================================================================
    // Redstone
    // ================================================================

    
    public boolean isBlockGettingPowered(int x, int y, int z) {
        return level.hasNeighborSignal(new BlockPos(x, y, z));
    }

    
    public boolean isBlockIndirectlyGettingPowered(int x, int y, int z) {
        return level.getBestNeighborSignal(new BlockPos(x, y, z)) > 0;
    }

    // ================================================================
    // Solid top surface
    // ================================================================

    
    public boolean doesBlockHaveSolidTopSurface(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        return state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP);
    }

    // ================================================================
    // Player
    // ================================================================


    public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
        // Check player permissions via the real ServerPlayer if available
        if (player instanceof PlayerBridge pb) {
            ServerPlayer sp = pb.getServerPlayer();
            if (sp != null) {
                BlockPos pos = new BlockPos(x, y, z);
                if (sp.isSpectator()) return false;
                if (!sp.mayBuild()) return false;
                if (sp.server.isUnderSpawnProtection(level, pos, sp)) return false;
            }
            return true;
        }
        return true;
    }


    public EntityPlayer getClosestPlayer(double x, double y, double z, double range) {
        // Delegate to modern level -- range<0 means unlimited
        net.minecraft.world.entity.player.Player nearest =
                level.getNearestPlayer(x, y, z, range, false);
        if (nearest instanceof ServerPlayer sp) {
            return PlayerBridge.getOrCreate(sp);
        }
        return null;
    }

    @Override
    public EntityPlayer getClosestPlayerToEntity(btw.modern.Entity entity, double range) {
        if (entity == null) return null;
        return getClosestPlayer(entity.posX, entity.posY, entity.posZ, range);
    }

    @Override
    public EntityPlayer getPlayerEntityByName(String name) {
        // Called every AI tick by tamed-animal owner lookups (empty name for
        // untamed) — keep the miss path cheap and allocation-free.
        if (name == null || name.isEmpty()) return null;
        for (ServerPlayer player : level.players()) {
            if (name.equals(player.getGameProfile().getName())) {
                return PlayerBridge.getOrCreate(player);
            }
        }
        return null;
    }

    @Override
    public EntityPlayer getClosestVulnerablePlayerToEntity(btw.modern.Entity entity, double range) {
        if (entity == null) return null;
        return getClosestVulnerablePlayer(entity.posX, entity.posY, entity.posZ, range);
    }

    @Override
    public EntityPlayer getClosestVulnerablePlayer(double x, double y, double z, double range) {
        // "Vulnerable" in FC means not in creative/spectator mode
        net.minecraft.world.entity.player.Player nearest =
                level.getNearestPlayer(x, y, z, range, true); // true = ignore creative
        if (nearest instanceof ServerPlayer sp) {
            // Also exclude spectators
            if (sp.isSpectator()) return null;
            return PlayerBridge.getOrCreate(sp);
        }
        return null;
    }

    // ================================================================
    // Entity lookup
    // ================================================================

    @Override
    public btw.modern.Entity getEntityByID(int id) {
        net.minecraft.world.entity.Entity mcEntity = level.getEntity(id);
        if (mcEntity == null) return null;
        // Every proxy type, players, and dropped items (their FC EntityItem view: 1.5.2 World.getEntityByID
        // resolved EntityItems like any entity)
        return extractFcEntityOrItem(mcEntity);
    }

    // 1.5.2 World.CountEntitiesThatApplyToSpawnCap (BTW-patched vanilla/server
    // World.java:4634) — FCTileEntityBeacon.CheckForEndermanSpawn (:584/:649)
    // population cap. MC owns the entity list, so iterate the live level and
    // unwrap FC entities from their proxies.
    @Override
    public int CountEntitiesThatApplyToSpawnCap(Class classToCount) {
        int iEntityCount = 0;

        for (net.minecraft.world.entity.Entity forgeEntity : level.getAllEntities()) {
            btw.modern.Entity tempEntity = extractFcEntity(forgeEntity);

            if (tempEntity != null && tempEntity.DoesEntityApplyToSpawnCap()
                    && classToCount.isAssignableFrom(tempEntity.getClass())) {
                ++iEntityCount;
            }
        }

        return iEntityCount;
    }

    // 1.5.2 World fields backing GetNumEntitiesThatApplyToSquidPossessionCap's
    // once-per-tick cache (vanilla/server World.java:4655).
    private long m_lTimeOfLastSquidPossessionCapCount = -1L;
    private int m_iLastSquidPossessionCapCount = 0;

    // 1.5.2 World.GetNumEntitiesThatApplyToSquidPossessionCap (vanilla/server
    // World.java:4655) — FCEntitySquid.GetCanCreatureBePossessedFromDistance (:630)
    // possessed-squid performance cap.
    @Override
    public int GetNumEntitiesThatApplyToSquidPossessionCap() {
        long lCurrentTime = getWorldTime();

        if (lCurrentTime != m_lTimeOfLastSquidPossessionCapCount) {
            m_iLastSquidPossessionCapCount = 0;
            m_lTimeOfLastSquidPossessionCapCount = lCurrentTime;

            for (net.minecraft.world.entity.Entity forgeEntity : level.getAllEntities()) {
                btw.modern.Entity tempEntity = extractFcEntity(forgeEntity);

                if (tempEntity != null && tempEntity.DoesEntityApplyToSquidPossessionCap()) {
                    m_iLastSquidPossessionCapCount++;
                }
            }
        }

        return m_iLastSquidPossessionCapCount;
    }

    // Cached reflective handles for net.minecraft.src.btw.util.FCClosestEntityInfo /
    // FCClosestEntitySelectionCriteria — FC-only classes that are not on the Forge
    // compile classpath (same reflective FC access pattern as BTWLifecycle).
    private static java.lang.reflect.Constructor<?> closestEntityInfoCtor;
    private static java.lang.reflect.Method closestEntityProcessMethod;
    private static java.lang.reflect.Field closestEntityField;

    // 1.5.2 World.GetClosestEntityMatchingCriteriaWithinRange (BTW-patched
    // vanilla/server World.java:4595) — FCEntitySquid.java:849 staggered secondary
    // possession-target scan. MC owns the chunk entity lists, so iterate live
    // entities in range and apply the FC criteria object's ProcessEntity
    // (selection logic stays in FC code, exactly as Chunk.
    // GetClosestEntityMatchingCriteriaWithinRangeSq does per entity).
    @Override
    public btw.modern.Entity GetClosestEntityMatchingCriteriaWithinRange(
            double dSourcePosX, double dSourcePosY, double dSourcePosZ,
            double dRange, Object criteria) {
        if (criteria == null) return null;
        try {
            if (closestEntityInfoCtor == null) {
                Class<?> infoClass = Class.forName("net.minecraft.src.btw.util.FCClosestEntityInfo");
                Class<?> criteriaClass = Class.forName("net.minecraft.src.btw.util.FCClosestEntitySelectionCriteria");
                closestEntityInfoCtor = infoClass.getConstructor(
                        double.class, double.class, double.class, double.class,
                        btw.modern.Entity.class, criteriaClass, int.class, int.class);
                closestEntityProcessMethod = criteriaClass.getMethod(
                        "ProcessEntity", infoClass, btw.modern.Entity.class);
                closestEntityField = infoClass.getField("m_closestEntity");
            }

            // m_dClosestDistanceSq starts at range² so ProcessEntity rejects anything
            // out of range; the vertical section indices (0,15) are only chunk-list
            // bookkeeping in vanilla and unused by the AABB iteration below.
            Object closestEntityInfo = closestEntityInfoCtor.newInstance(
                    dSourcePosX, dSourcePosY, dSourcePosZ, dRange * dRange,
                    null, criteria, 0, 15);

            net.minecraft.world.phys.AABB searchBox = new net.minecraft.world.phys.AABB(
                    dSourcePosX - dRange, dSourcePosY - dRange, dSourcePosZ - dRange,
                    dSourcePosX + dRange, dSourcePosY + dRange, dSourcePosZ + dRange);

            for (net.minecraft.world.entity.Entity forgeEntity :
                    level.getEntities((net.minecraft.world.entity.Entity) null, searchBox, e -> true)) {
                btw.modern.Entity fc = extractFcEntity(forgeEntity);
                if (fc != null) {
                    closestEntityProcessMethod.invoke(criteria, closestEntityInfo, fc);
                }
            }

            return (btw.modern.Entity) closestEntityField.get(closestEntityInfo);
        } catch (Exception e) {
            warnOncePerThrowSite("CLOSEST-ENTITY", "GetClosestEntityMatchingCriteriaWithinRange", e);
            return null;
        }
    }

    // ================================================================
    // Entity state
    // ================================================================

    @Override
    public void setEntityState(btw.modern.Entity entity, byte state) {
        if (entity == null) return;
        // Drop the hurt flash (event 2) FC raises on a puppet inside its OWN onUpdate() -- that's
        // its spurious self-suffocation (isEntityInsideOpaqueBlock false-fires on FC-driven
        // puppets). Hits it lands on other entities pass the victim (!= updater) and still flash,
        // and real combat runs outside onUpdate, so only self-inflicted flashes are swallowed.
        if (state == 2 && FCEnvHurtGuard.isSelfHurt(entity)) return;
        net.minecraft.world.entity.Entity proxy = proxyFor(entity);
        if (proxy != null) {
            level.broadcastEntityEvent(proxy, state);
        }
    }

    /**
     * The MC entity standing for an FC entity: the proxy spawnEntityInWorld registered, or -- for the
     * puppets VanillaMobReplacer / MC spawning / chunk loading created, which never pass through
     * spawnEntityInWorld -- the level entity with the FC entity's id whose FC entity is this very object
     * (the proxies keep fcEntity.entityId == getId() every tick; the identity check rejects id collisions
     * with FC's own counter). Players are left out: their events come from MC itself. Null if none.
     */
    private net.minecraft.world.entity.Entity proxyFor(btw.modern.Entity entity) {
        if (entity == null || level == null) return null;
        net.minecraft.world.entity.Entity proxy = fcToForgeEntity.get(entity);
        if (proxy == null && entity.entityId != 0) {
            net.minecraft.world.entity.Entity byId = level.getEntity(entity.entityId);
            if (byId != null && !(byId instanceof net.minecraft.world.entity.player.Player)
                    && extractFcEntity(byId) == entity) {
                proxy = byId;
            }
        }
        return proxy;
    }

    // ================================================================
    // Explosion
    // ================================================================

    
    // 1.5.2 World.createExplosion(entity, x, y, z, size, flag) == newExplosion(entity, x, y, z, size, false, flag)
    // (vanilla/server World.java:2287-2290): the flag is isSmoking -- block damage, never fire. Creepers pass
    // the mobGriefing gamerule as that flag (EntityCreeper.java:153-160). It used to be stored as isFlaming.
    public Explosion createExplosion(btw.modern.Entity entity, double x, double y, double z,
                                     float strength, boolean isSmoking) {
        return newExplosion(entity, x, y, z, strength, false, isSmoking);
    }


    // 1.5.2 World.newExplosion: isFlaming sets fires, isSmoking destroys blocks (Explosion.doExplosionB).
    // The FC entity's proxy is the MC source, so deaths read "blown up by Creeper" and the exploder is not
    // hurt by its own blast (1.5.2 Explosion.doExplosionA excludes the exploder too) -- triage HAI-7.
    public Explosion newExplosion(btw.modern.Entity entity, double x, double y, double z,
                                  float strength, boolean isFlaming, boolean isSmoking) {
        try {
            net.minecraft.world.entity.Entity source = proxyFor(entity);
            level.explode(source, x, y, z, strength, isFlaming, explosionInteraction(source, isSmoking));
        } catch (Throwable t) {
            warnOncePerThrowSite("EXPLOSION", "explosion at " + x + "," + y + "," + z + " (size " + strength + ")", t);
        }
        Explosion fcExplosion = new Explosion(this, entity, x, y, z, strength);
        fcExplosion.isFlaming = isFlaming;
        fcExplosion.isSmoking = isSmoking;
        return fcExplosion;
    }

    /**
     * 1.20.1 interaction for a 1.5.2 isSmoking flag. false -> NONE (no block damage, e.g. a creeper or a
     * wither skull with mobGriefing off). true from a mob (creeper, wither) -> MOB: the 1.5.2 caller already
     * read mobGriefing, and MOB re-checks it through Forge's EntityMobGriefingEvent like a 1.20.1 creeper.
     * true from anything else -> BLOCK, as before: FC dynamite, blasting oil, vase, cooking vessel and TNT
     * always destroy blocks in 1.5.2, independent of mobGriefing (ghast fireballs and wither skulls pass the
     * gamerule themselves).
     */
    private static net.minecraft.world.level.Level.ExplosionInteraction explosionInteraction(
            net.minecraft.world.entity.Entity source, boolean isSmoking) {
        if (!isSmoking) return net.minecraft.world.level.Level.ExplosionInteraction.NONE;
        return source instanceof net.minecraft.world.entity.Mob
                ? net.minecraft.world.level.Level.ExplosionInteraction.MOB
                : net.minecraft.world.level.Level.ExplosionInteraction.BLOCK;
    }

    // 1.5.2 World.NewExplosionNoFX (BTW-patched vanilla/server World.java:4902) —
    // FCBlockLogSmouldering.Explode (FCBlockLogSmouldering.java:389); copy of
    // newExplosion with FX suppressed. Built directly instead of level.explode so
    // no ClientboundExplodePacket (sound/particles) is sent — entity damage, fire
    // (isFlaming) and block destruction (isSmoking) still apply, matching
    // doExplosionA()/doExplosionB(false).
    @Override
    public Explosion NewExplosionNoFX(btw.modern.Entity entity, double x, double y, double z,
                                      float strength, boolean isFlaming, boolean isSmoking) {
        net.minecraft.world.entity.Entity source = proxyFor(entity);
        net.minecraft.world.level.Explosion explosion = new net.minecraft.world.level.Explosion(
                level, source, x, y, z, strength, isFlaming,
                isSmoking ? net.minecraft.world.level.Explosion.BlockInteraction.DESTROY_WITH_DECAY
                          : net.minecraft.world.level.Explosion.BlockInteraction.KEEP);
        explosion.explode();
        explosion.finalizeExplosion(false);

        Explosion fcExplosion = new Explosion(this, entity, x, y, z, strength);
        fcExplosion.isFlaming = isFlaming;
        fcExplosion.isSmoking = isSmoking;
        fcExplosion.m_bSuppressFX = true;
        return fcExplosion;
    }

    // ================================================================
    // Time
    // ================================================================

    
    public long getWorldTime() {
        return level.getDayTime();
    }

    
    public void setWorldTime(long time) {
        level.setDayTime(time);
    }

    
    public long getTotalWorldTime() {
        return level.getGameTime();
    }

    @Override
    public float getCelestialAngle(float partialTicks) {
        return level.getTimeOfDay(partialTicks);
    }

    @Override
    public boolean isDaytime() {
        return level.isDay();
    }

    // 1.5.2 World.getMoonPhase (vanilla/server World.java:1576) — FCEntityWolf.
    // IsWildAndHostile full-moon hostility (phase 0), FCEntityAIWolfHowl howl trigger,
    // frozen EntitySlime.getCanSpawnHere; MC 1.20.1 uses the same (time/24000 % 8)
    // numbering with 0 = full moon.
    @Override
    public int getMoonPhase() {
        return level.getMoonPhase();
    }

    // ================================================================
    // Spawn point
    // ================================================================

    
    public ChunkCoordinates getSpawnPoint() {
        BlockPos sp = level.getSharedSpawnPos();
        return new ChunkCoordinates(sp.getX(), sp.getY(), sp.getZ());
    }

    // ================================================================
    // World height
    // ================================================================

    // 1.5.2 World.getHeight / getActualHeight are the TOP of the build range (256, and 128 under a no-sky
    // provider's roof; vanilla/server World.java:3944-3955, the bottom was always 0). Level.getHeight is the
    // range's SIZE (384 in the overworld, -64..320), which put the top 64 layers above the world
    // (FCTileEntityBeacon enderman targets, FCEntityGhast destinations).
    public int getHeight() {
        return level.getMaxBuildHeight();
    }

    
    public int getActualHeight() {
        int top = level.getMaxBuildHeight();
        return provider != null && provider.hasNoSky ? Math.min(128, top) : top;
    }

    // Build-range contract with Modern-Common World.getFcMinBuildHeight / getFcMaxBuildHeight (1.5.2: 0 / 256),
    // which the bridge's frozen-code consumers (pathing below y=0, deepslate caves, superflat at -60) read
    // instead of the hard-coded 1.5.2 range. Block reads here already go straight to the level
    // (Level.getBlockState answers VOID_AIR outside its build range), so no 0..256 clamp applies to them.
    // Null-safe: detach() clears the level when the server stops.
    public int getFcMinBuildHeight() {
        ServerLevel live = level;
        return live != null ? live.getMinBuildHeight() : 0;
    }

    public int getFcMaxBuildHeight() {
        ServerLevel live = level;
        return live != null ? live.getMaxBuildHeight() : 256;
    }

    // ================================================================
    // Block events
    // ================================================================

    
    public void addBlockEvent(int x, int y, int z, int blockID, int eventID, int eventParam) {
        BlockPos pos = new BlockPos(x, y, z);
        net.minecraft.world.level.block.Block modernBlock = modernBlockAt(pos, blockID);
        if (modernBlock != null) {
            level.blockEvent(pos, modernBlock, eventID, eventParam);
        }
    }

    // ================================================================
    // Game rules
    // ================================================================

    /**
     * Lazy-initialised wrapper that delegates btw.modern.GameRules methods
     * to the real MC 1.20.1 GameRules stored in the ServerLevel.
     */
    private GameRules gameRulesBridge;


    public GameRules getGameRules() {
        if (gameRulesBridge == null) {
            gameRulesBridge = new GameRules() {
                @Override
                public String getGameRuleStringValue(String name) {
                    net.minecraft.world.level.GameRules.Key<? extends net.minecraft.world.level.GameRules.Value<?>> key =
                            findRuleKey(name);
                    if (key != null) {
                        return level.getGameRules().getRule(key).toString();
                    }
                    return "";
                }

                @Override
                public boolean getGameRuleBooleanValue(String name) {
                    net.minecraft.world.level.GameRules.Key<BooleanValue> key =
                            findBooleanRuleKey(name);
                    if (key != null) {
                        return level.getGameRules().getBoolean(key);
                    }
                    return false;
                }

                @Override
                public boolean hasRule(String name) {
                    return findRuleKey(name) != null;
                }

                /**
                 * Finds a GameRules key by name. MC 1.20.1 uses typed keys
                 * so we check the well-known set.
                 */
                private net.minecraft.world.level.GameRules.Key<? extends net.minecraft.world.level.GameRules.Value<?>> findRuleKey(String name) {
                    return switch (name) {
                        case "doFireTick" -> net.minecraft.world.level.GameRules.RULE_DOFIRETICK;
                        case "mobGriefing" -> net.minecraft.world.level.GameRules.RULE_MOBGRIEFING;
                        case "keepInventory" -> net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY;
                        case "doMobSpawning" -> net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING;
                        case "doMobLoot" -> net.minecraft.world.level.GameRules.RULE_DOMOBLOOT;
                        case "doTileDrops" -> net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS;
                        case "commandBlockOutput" -> net.minecraft.world.level.GameRules.RULE_COMMANDBLOCKOUTPUT;
                        case "naturalRegeneration" -> net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION;
                        case "doDaylightCycle" -> net.minecraft.world.level.GameRules.RULE_DAYLIGHT;
                        // doWeatherCycle not in MC 1.5.2 FC, skip
                        case "showDeathMessages" -> net.minecraft.world.level.GameRules.RULE_SHOWDEATHMESSAGES;
                        default -> null;
                    };
                }

                @SuppressWarnings("unchecked")
                private net.minecraft.world.level.GameRules.Key<BooleanValue> findBooleanRuleKey(String name) {
                    var key = findRuleKey(name);
                    if (key != null) {
                        // All the rules mapped above are BooleanValue rules
                        return (net.minecraft.world.level.GameRules.Key<BooleanValue>) key;
                    }
                    return null;
                }
            };
        }
        return gameRulesBridge;
    }

    // ================================================================
    // FC-only data holders (magnetic points, spawn locations, beacons)
    // ================================================================

    /**
     * Initialises the FC-only list fields that FC code expects to be
     * non-null on a World instance.  Called lazily / from the constructor.
     */
    private void initFcDataHolders() {
        if (m_MagneticPointList == null) {
            m_MagneticPointList = new FCMagneticPointList();
        }
        if (m_SpawnLocationList == null) {
            m_SpawnLocationList = new FCSpawnLocationList();
        }
        if (m_LootingBeaconLocationList == null) {
            m_LootingBeaconLocationList = new FCBeaconEffectLocationList();
        }
    }

    @Override
    public FCMagneticPointList GetMagneticPointList() {
        initFcDataHolders();
        return m_MagneticPointList;
    }

    @Override
    public FCSpawnLocationList GetSpawnLocationList() {
        initFcDataHolders();
        return m_SpawnLocationList;
    }

    @Override
    public FCBeaconEffectLocationList GetLootingBeaconLocationList() {
        initFcDataHolders();
        return m_LootingBeaconLocationList;
    }

    // ================================================================
    // Tile entity collection
    // ================================================================

    @Override
    @SuppressWarnings("unchecked")
    public List getAllTileEntityInBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        List result = new ArrayList();
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
                    if (be != null) {
                        if (be instanceof ProxyBlockEntity proxy) {
                            TileEntity fcTe = proxy.getFcTileEntity();
                            if (fcTe != null) {
                                result.add(fcTe);
                            }
                        } else {
                            result.add(TileEntityBridge.getOrCreate(be, this));
                        }
                    }
                }
            }
        }
        return result;
    }

    // ================================================================
    // Celestial angle / sun brightness
    // ================================================================

    @Override
    public float ComputeOverworldSunBrightnessWithMoonPhases() {
        // Compute sun angle from time-of-day
        float celestialAngle = level.getTimeOfDay(0);

        // Sun brightness curve: 1.0 at noon, 0.0 at midnight, with
        // smooth transitions at dawn/dusk.
        float sunBrightness;
        if (celestialAngle < 0.25F) {
            // Night to sunrise (0.0 = midnight, 0.25 = sunrise)
            sunBrightness = 0.0F;
        } else if (celestialAngle < 0.27F) {
            // Dawn transition
            sunBrightness = (celestialAngle - 0.25F) / 0.02F;
        } else if (celestialAngle < 0.73F) {
            // Daytime
            sunBrightness = 1.0F;
        } else if (celestialAngle < 0.75F) {
            // Dusk transition
            sunBrightness = 1.0F - (celestialAngle - 0.73F) / 0.02F;
        } else {
            // Night
            sunBrightness = 0.0F;
        }

        // Modulate by moon phase (0=full, 4=new). Full moon adds some
        // night brightness; new moon means total darkness.
        int moonPhase = level.getMoonPhase();
        float moonBrightness = switch (moonPhase) {
            case 0 -> 1.0F;   // full moon
            case 1, 7 -> 0.75F;
            case 2, 6 -> 0.5F;
            case 3, 5 -> 0.25F;
            case 4 -> 0.0F;   // new moon
            default -> 0.5F;
        };

        // When the sun is up, moon has no effect. At night, use moon
        // brightness scaled down.
        if (sunBrightness > 0.0F) {
            return sunBrightness;
        }
        return moonBrightness * 0.25F;
    }

    // ================================================================
    // Sound resolution helper
    // ================================================================

    /**
     * Resolves a legacy FC sound name (e.g. "random.drink") to a modern
     * SoundEvent.  Uses {@link SoundEvent#createVariableRangeEvent} with
     * the FC name as a ResourceLocation under the "minecraft" namespace,
     * converting dots to underscores so "random.drink" becomes
     * "minecraft:random/drink" (matching vanilla's path convention where
     * applicable).  Returns null if the name is blank.
     */
    private static SoundEvent resolveSoundEvent(String fcSoundName) {
        if (fcSoundName == null || fcSoundName.isEmpty()) return null;
        // Use the centralized SoundMapping which maps 1.5.2 names → 1.20.1 SoundEvents
        SoundEvent mapped = SoundMapping.get(fcSoundName);
        if (mapped != null) return mapped;
        // Fallback: try creating a SoundEvent from the raw name (for sounds
        // that happen to have the same name in 1.20.1, or custom registered sounds)
        try {
            String path = fcSoundName.replace('.', '_');
            ResourceLocation loc = new ResourceLocation("minecraft", path);
            return SoundEvent.createVariableRangeEvent(loc);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    // Ray tracing
    // ================================================================

    @Override
    public btw.modern.MovingObjectPosition rayTraceBlocks_do(btw.modern.Vec3 startVec, btw.modern.Vec3 endVec, boolean hitFluids) {
        net.minecraft.world.phys.Vec3 mcStart = new net.minecraft.world.phys.Vec3(startVec.xCoord, startVec.yCoord, startVec.zCoord);
        net.minecraft.world.phys.Vec3 mcEnd = new net.minecraft.world.phys.Vec3(endVec.xCoord, endVec.yCoord, endVec.zCoord);
        net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(
                mcStart, mcEnd,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                hitFluids ? net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY : net.minecraft.world.level.ClipContext.Fluid.NONE,
                null);
        net.minecraft.world.phys.BlockHitResult result = level.clip(ctx);
        if (result == null || result.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return null;
        net.minecraft.core.BlockPos hitPos = result.getBlockPos();
        btw.modern.MovingObjectPosition mop = new btw.modern.MovingObjectPosition(
                hitPos.getX(), hitPos.getY(), hitPos.getZ(),
                result.getDirection().get3DDataValue(),
                btw.modern.Vec3.createVectorHelper(result.getLocation().x, result.getLocation().y, result.getLocation().z));
        return mop;
    }

    @Override
    public btw.modern.MovingObjectPosition rayTraceBlocks(btw.modern.Vec3 startVec, btw.modern.Vec3 endVec) {
        return rayTraceBlocks_do(startVec, endVec, false);
    }

    @Override
    public btw.modern.MovingObjectPosition rayTraceBlocks_do_do(btw.modern.Vec3 startVec, btw.modern.Vec3 endVec, boolean hitFluids, boolean ignoreNonMovement) {
        return rayTraceBlocks_do(startVec, endVec, hitFluids);
    }

    // ================================================================
    // Collision detection
    // ================================================================

    @Override
    @SuppressWarnings("unchecked")
    public java.util.List getCollidingBoundingBoxes(btw.modern.Entity entity, btw.modern.AxisAlignedBB aabb) {
        java.util.List list = new java.util.ArrayList();
        int minX = btw.modern.MathHelper.floor_double(aabb.minX);
        int maxX = btw.modern.MathHelper.floor_double(aabb.maxX + 1.0D);
        // 1.5.2 World.getCollidingBoundingBoxes scans from minY - 1 (vanilla/client World.java:1617) so a
        // fence/wall's 1.5-high box in the block below still collides (stops puppets hopping fences).
        int minY = btw.modern.MathHelper.floor_double(aabb.minY) - 1;
        int maxY = btw.modern.MathHelper.floor_double(aabb.maxY + 1.0D);
        int minZ = btw.modern.MathHelper.floor_double(aabb.minZ);
        int maxZ = btw.modern.MathHelper.floor_double(aabb.maxZ + 1.0D);
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int y = minY; y < maxY; y++) {
                    int blockId = getBlockId(x, y, z);
                    if (blockId > 0) {
                        btw.modern.Block block = btw.modern.Block.blocksList[blockId];
                        if (block != null) {
                            block.addCollisionBoxesToList(this, x, y, z, aabb, list, entity);
                        }
                    }
                }
            }
        }
        return list;
    }

    // ================================================================
    // Liquid / material checks
    // ================================================================

    // ================================================================
    // Liquid / material checks
    // ================================================================

    @Override
    public boolean isAnyLiquid(btw.modern.AxisAlignedBB aabb) {
        int minX = btw.modern.MathHelper.floor_double(aabb.minX);
        int maxX = btw.modern.MathHelper.floor_double(aabb.maxX + 1.0D);
        int minY = btw.modern.MathHelper.floor_double(aabb.minY);
        int maxY = btw.modern.MathHelper.floor_double(aabb.maxY + 1.0D);
        int minZ = btw.modern.MathHelper.floor_double(aabb.minZ);
        int maxZ = btw.modern.MathHelper.floor_double(aabb.maxZ + 1.0D);
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int y = minY; y < maxY; y++) {
                    btw.modern.Material mat = getBlockMaterial(x, y, z);
                    if (mat != null && mat.isLiquid()) return true;
                    if (fluidMaterialAt(x, y, z) != null) return true;
                }
            }
        }
        return false;
    }

    /**
     * 1.5.2 material of the fluid in a cell (water / lava), or null. 1.20.1 keeps water in the FluidState of
     * non-fluid blocks (waterlogged stairs/slabs/fences/...): those keep their own legacy id -- their collision
     * matters to FC -- so the id-based material checks miss the water they hold (triage D5). The liquid
     * queries below also ask this.
     */
    private btw.modern.Material fluidMaterialAt(int x, int y, int z) {
        net.minecraft.world.level.material.FluidState fluid = level.getFluidState(new BlockPos(x, y, z));
        if (fluid.isEmpty()) return null;
        if (fluid.is(net.minecraft.tags.FluidTags.WATER)) return btw.modern.Material.water;
        if (fluid.is(net.minecraft.tags.FluidTags.LAVA)) return btw.modern.Material.lava;
        return null;
    }

    // 1.5.2 World.handleMaterialAcceleration (vanilla/server World.java:2158), the inWater source of frozen
    // Entity.handleWaterMovement. The id-based scan (Modern-Common World) sees mapped water and the cells
    // getWorldBlockId reports as water (seagrass, kelp, bubble columns); a waterlogged block keeps its own
    // id, so its water is added here as a source cell (1.5.2 level 0: no flow push). Without it an FC squid
    // in a waterlogged cell read inWater=false and drowned FC-side (EntityWaterMob.java:63-86, triage D5).
    @Override
    public boolean handleMaterialAcceleration(btw.modern.AxisAlignedBB aabb, btw.modern.Material material,
                                              btw.modern.Entity entity) {
        boolean found = super.handleMaterialAcceleration(aabb, material, entity);
        if (found || material != btw.modern.Material.water || level == null) return found;
        int minX = btw.modern.MathHelper.floor_double(aabb.minX);
        int maxX = btw.modern.MathHelper.floor_double(aabb.maxX + 1.0D);
        int minY = btw.modern.MathHelper.floor_double(aabb.minY);
        int maxY = btw.modern.MathHelper.floor_double(aabb.maxY + 1.0D);
        int minZ = btw.modern.MathHelper.floor_double(aabb.minZ);
        int maxZ = btw.modern.MathHelper.floor_double(aabb.maxZ + 1.0D);
        for (int x = minX; x < maxX; ++x) {
            for (int y = minY; y < maxY; ++y) {
                for (int z = minZ; z < maxZ; ++z) {
                    if (fluidMaterialAt(x, y, z) == btw.modern.Material.water) {
                        double fluidTop = (double) ((float) (y + 1) - btw.modern.BlockFluid.getFluidHeightPercent(0));
                        if ((double) maxY >= fluidTop) return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean isMaterialInBB(btw.modern.AxisAlignedBB aabb, btw.modern.Material material) {
        int minX = btw.modern.MathHelper.floor_double(aabb.minX);
        int maxX = btw.modern.MathHelper.floor_double(aabb.maxX + 1.0D);
        int minY = btw.modern.MathHelper.floor_double(aabb.minY);
        int maxY = btw.modern.MathHelper.floor_double(aabb.maxY + 1.0D);
        int minZ = btw.modern.MathHelper.floor_double(aabb.minZ);
        int maxZ = btw.modern.MathHelper.floor_double(aabb.maxZ + 1.0D);
        boolean liquid = material != null && material.isLiquid();
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int y = minY; y < maxY; y++) {
                    btw.modern.Material mat = getBlockMaterial(x, y, z);
                    if (mat == material) return true;
                    // water held by a waterlogged block (D5); non-liquid queries keep the block's material
                    if (liquid && fluidMaterialAt(x, y, z) == material) return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isAABBInMaterial(btw.modern.AxisAlignedBB aabb, btw.modern.Material material) {
        return isMaterialInBB(aabb, material);
    }

    // 1.5.2 WorldServer.tick — villageCollectionObj.tick() drives village door
    // discovery, center/radius updates, aggressor decay, and reputation for the
    // frozen villager AI (EntityAIMoveIndoors/MoveThroughVillage/RestrictOpenDoor/
    // VillagerMate/DefendVillage). Runs once per server tick via ServerLevelMixin's
    // ModSpecificTick hook; func_82566_a re-binds this world first because the
    // World shim field-initializes villageCollectionObj without one.
    @Override
    public void ModSpecificTick() {
        // 1.5.2 WorldServer.tick updates skylightSubtracted with time of day each tick
        // (World.calculateSkylightSubtracted). getLightBrightness / GetBlockNaturalLightValue
        // and the gloom probe read this field; getSkyDarken is the modern equivalent.
        this.skylightSubtracted = level.getSkyDarken();
        villageCollectionObj.func_82566_a(this);
        villageCollectionObj.tick();
    }

    // Live world seed — Chunk.getRandomWithSeed (slime-chunk determination) needs the real seed.
    @Override
    public long getSeed() {
        return level.getSeed();
    }
}
