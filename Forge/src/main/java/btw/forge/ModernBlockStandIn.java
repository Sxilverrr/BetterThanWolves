package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What FC sees, through WorldBridge.getBlockId, for a colliding 1.20.1 block state with no usable 1.5.2 id
 * (deepslate, tuff, granite/diorite/andesite, calcite, dripstone, copper, mud, new woods, concrete, hay,
 * terracotta, ...). In 1.5.2 every block in the world had a blocksList entry and World.getCollidingBoundingBoxes
 * collided with all of them (vanilla/client World.java:1601-1625). Only the world-read path uses this
 * (ProxyRegistry.getWorldBlockId): identity routing (getFcBlock / BlockBehaviorMixin / items) never returns a
 * stand-in id, so no FC callback runs on a modern block. Collision/solidity/pathing/hardness are bridged to
 * the live MC state; FC machine hooks stay inert, and WorldBridge.setBlock(standInId) is a no-op
 * (getModernBlock(standIn) == null), so FC can never write one.
 */
final class ModernBlockStandIn extends btw.modern.Block {

    private static final Logger LOGGER = LogManager.getLogger("BTW-StandIn");

    // Unused by 1.5.2 vanilla (Block.java ends at dropper, 158) and by FC (ParseID defaults start at 175,
    // FCBetterThanWolves.java:914). Kept < 175 so the blocksList[175..] loops (BTWRegistration:85,
    // BlockModelBridge:413/424) never give a stand-in a ProxyBlock or a model.
    private static final int FIRST_FREE_ID = 159;
    private static final int LAST_FREE_ID = 174;
    /** FCBlockBedrock: immovable, not BD-retrievable, spared by id in EntityWither (vanilla/client EntityWither.java:369-371). */
    private static final int BEDROCK_ID = 7;

    private static volatile boolean created;   // written last in ensureCreated -> publishes the ids below
    private static int solidId;                // full, solid-render collision cube (1.5.2 "normal cube")
    private static int colliderId;             // any other colliding shape (slab, stair, wall, fence, glass, carpet, mud)
    private static final Set<net.minecraft.world.level.block.Block> LOGGED = ConcurrentHashMap.newKeySet();
    private static volatile boolean idForFailureLogged;

    private final boolean m_bSolidCube;

    private ModernBlockStandIn(int id, boolean solidCube) {
        super(id, btw.modern.Material.rock);   // registers blocksList[id] (+ an ItemBlock, never exposed)
        this.m_bSolidCube = solidCube;
        // Stone defaults (vanilla/client Block.java:72). stepSound must be non-null: frozen
        // Entity.playStepSound dereferences GetStepSound(...) (Entity.java:1028).
        setHardness(1.5F);
        setResistance(10.0F);
        setStepSound(soundStoneFootstep);
        setUnlocalizedName(solidCube ? "btwModernSolid" : "btwModernCollider");
    }

    /** Legacy id FC should see for {@code state}; 0 if it has no collision (plants, light...: unchanged). */
    static int idFor(BlockState state, BlockGetter level, BlockPos pos) {
        try {
            if (!created) ensureCreated();
            if (solidId <= 0) return 0;                                  // allocation failed -> legacy (air)
            VoxelShape shape = state.getCollisionShape(level, pos);      // MC-cached for non-dynamic shapes
            if (shape.isEmpty()) return 0;
            boolean full = net.minecraft.world.level.block.Block.isShapeFullBlock(shape);
            int id = full && state.is(BlockTags.WITHER_IMMUNE) ? BEDROCK_ID
                    : full && state.isSolidRender(level, pos) ? solidId : colliderId;
            net.minecraft.world.level.block.Block mc = state.getBlock();
            if (!LOGGED.contains(mc) && LOGGED.add(mc)) {
                LOGGER.info("[STAND-IN] {} reads as legacy id {} to FC", BuiltInRegistries.BLOCK.getKey(mc), id);
            }
            return id;
        } catch (Throwable t) {
            // World reads run inside FC entity/block ticks: never throw out of them.
            if (!idForFailureLogged) {
                idForFailureLogged = true;
                LOGGER.warn("[STAND-IN] stand-in lookup failed for {}; it reads as air to FC (logged once)", state, t);
            }
            return 0;
        }
    }

    private static synchronized void ensureCreated() {
        if (created) return;
        try {
            int a = nextFreeId(FIRST_FREE_ID);
            int b = a > 0 ? nextFreeId(a + 1) : -1;
            if (a > 0 && b > 0) {
                new ModernBlockStandIn(a, true);
                new ModernBlockStandIn(b, false);
                solidId = a;
                colliderId = b;
                LOGGER.info("[STAND-IN] modern-only blocks read by FC as legacy {} (solid cube) / {} (shaped collider)", a, b);
            } else {
                LOGGER.warn("[STAND-IN] no two free legacy ids in {}..{}; modern-only blocks stay air to FC",
                        FIRST_FREE_ID, LAST_FREE_ID);
            }
        } catch (Throwable t) {
            solidId = 0;
            LOGGER.error("[STAND-IN] creation failed; modern-only blocks stay air to FC", t);
        } finally {
            created = true;
        }
    }

    /** Free = empty block AND item slot, and no 1.20.1 block maps to it (else getFcBlock would route MC callbacks here). */
    private static int nextFreeId(int from) {
        for (int id = from; id <= LAST_FREE_ID; id++) {
            if (btw.modern.Block.blocksList[id] == null && btw.modern.Item.itemsList[id] == null
                    && !ProxyRegistry.isLegacyIdMapped(id)) {
                return id;
            }
        }
        return -1;
    }

    /** Live level behind an FC block access: WorldBridge, or (PathFinder's ChunkCache) the pathing entity's world. */
    private static ServerLevel levelOf(Object access, btw.modern.Entity entity) {
        if (access instanceof WorldBridge wb) return wb.getServerLevel();
        if (entity != null && entity.worldObj instanceof WorldBridge wb) return wb.getServerLevel();
        return null;
    }

    // ---- solidity: Block.isNormalCube(id) / IsNormalCube read these (Modern-Common Block.java:340, 913)
    @Override public boolean isOpaqueCube() { return m_bSolidCube; }
    @Override public boolean renderAsNormalBlock() { return m_bSolidCube; }

    // ---- collision: 1.5.2 World.getCollidingBoundingBoxes -> addCollisionBoxesToList (World.java:1617-1624)
    @Override
    @SuppressWarnings("unchecked")
    public void addCollisionBoxesToList(btw.modern.World world, int x, int y, int z,
                                        btw.modern.AxisAlignedBB mask, List list, btw.modern.Entity entity) {
        ServerLevel level = m_bSolidCube ? null : levelOf(world, entity);
        if (level == null) {                    // solid cube (or no live level): inherited full-cube box
            super.addCollisionBoxesToList(world, x, y, z, mask, list, entity);
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        for (AABB b : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
            btw.modern.AxisAlignedBB bb = btw.modern.AxisAlignedBB.getBoundingBox(
                    x + b.minX, y + b.minY, z + b.minZ, x + b.maxX, y + b.maxY, z + b.maxZ);
            if (mask.intersectsWith(bb)) list.add(bb);
        }
    }

    @Override
    public btw.modern.AxisAlignedBB getCollisionBoundingBoxFromPool(btw.modern.World world, int x, int y, int z) {
        ServerLevel level = m_bSolidCube ? null : levelOf(world, null);
        if (level == null) return super.getCollisionBoundingBoxFromPool(world, x, y, z);
        BlockPos pos = new BlockPos(x, y, z);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return null;
        AABB b = shape.bounds();
        return btw.modern.AxisAlignedBB.getBoundingBox(
                x + b.minX, y + b.minY, z + b.minZ, x + b.maxX, y + b.maxY, z + b.maxZ);
    }

    // ---- pathing: getBlocksMovement is misnamed, true = passable (vanilla/client Block.java:563, 2213-2219)
    @Override
    public boolean getBlocksMovement(btw.modern.IBlockAccess access, int x, int y, int z) {
        return !m_bSolidCube && isPathable(levelOf(access, null), x, y, z);
    }

    @Override
    public boolean CanPathThroughBlock(btw.modern.IBlockAccess access, int i, int j, int k,
                                       btw.modern.Entity entity, btw.modern.PathFinder pathFinder) {
        return !m_bSolidCube && isPathable(levelOf(access, entity), i, j, k);
    }

    private static boolean isPathable(ServerLevel level, int x, int y, int z) {
        if (level == null) return false;
        BlockPos pos = new BlockPos(x, y, z);
        return level.getBlockState(pos).isPathfindable(level, pos, PathComputationType.LAND);
    }

    // FCBlockFence.java:136 / FCBlockWall.java:40 / FCBlockFenceGate.java:43 return -3 so A* never plans a
    // hop over a 1.5-high collider; mirror it for modern fences/walls/gates.
    @Override
    public int GetWeightOnPathBlocked(btw.modern.IBlockAccess access, int i, int j, int k) {
        ServerLevel level = m_bSolidCube ? null : levelOf(access, null);
        if (level == null) return 0;
        BlockPos pos = new BlockPos(i, j, k);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) > 1.0D ? -3 : 0;
    }

    // ---- hardpoints (collider only; the solid cube keeps the isBlockNormalCube default). FC facing 0..5 ==
    // MC Direction 3D data value (DOWN,UP,NORTH,SOUTH,WEST,EAST), cf. WorldBridge.directionToLegacyFacing.
    @Override
    public boolean HasLargeCenterHardPointToFacing(btw.modern.IBlockAccess access, int i, int j, int k,
                                                   int iFacing, boolean bIgnoreTransparency) {
        if (m_bSolidCube) return super.HasLargeCenterHardPointToFacing(access, i, j, k, iFacing, bIgnoreTransparency);
        ServerLevel level = levelOf(access, null);
        if (level == null || iFacing < 0 || iFacing > 5) return false;
        BlockPos pos = new BlockPos(i, j, k);
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.from3DDataValue(iFacing));
    }

    @Override
    public boolean HasSmallCenterHardPointToFacing(btw.modern.IBlockAccess access, int i, int j, int k,
                                                   int iFacing, boolean bIgnoreTransparency) {
        if (m_bSolidCube) return super.HasSmallCenterHardPointToFacing(access, i, j, k, iFacing, bIgnoreTransparency);
        ServerLevel level = levelOf(access, null);
        if (level == null || iFacing < 0 || iFacing > 5) return false;
        return net.minecraft.world.level.block.Block.canSupportCenter(
                level, new BlockPos(i, j, k), Direction.from3DDataValue(iFacing));
    }

    // ---- FC pistons (run on MC pistons via BlockBehaviorMixin.btw$neighborChanged) copy id+meta to move a
    // block; a stand-in id cannot be written back (WorldBridge.setBlock no-op), so pushing one would delete
    // the modern block. 2 = immovable (1.5.2 Material mobility, like obsidian).
    @Override public int getMobilityFlag() { return 2; }

    // ---- hardness / blast resistance from the live state (1.5.2 units: stone 1.5 / 10*3/5 = 6 == MC values)
    @Override
    public float getBlockHardness(btw.modern.World world, int x, int y, int z) {
        ServerLevel level = levelOf(world, null);
        if (level == null) return super.getBlockHardness(world, x, y, z);
        BlockPos pos = new BlockPos(x, y, z);
        return level.getBlockState(pos).getDestroySpeed(level, pos);
    }

    @Override
    public float getExplosionResistance(btw.modern.Entity entity, btw.modern.World world, int i, int j, int k) {
        ServerLevel level = levelOf(world, entity);
        if (level != null) {
            try {
                BlockPos pos = new BlockPos(i, j, k);
                return level.getBlockState(pos).getExplosionResistance(level, pos, null);
            } catch (Throwable ignored) { }
        }
        return super.getExplosionResistance(entity, world, i, j, k);
    }

    // ---- never mint FC stacks for a phantom id; FC-destroyed modern blocks (FCExplosionMining.DestroyBlock,
    // harvest/saw paths) drop the MC loot of the live state instead.
    @Override public int idDropped(int metadata, Random random, int fortune) { return 0; }

    @Override
    public void dropBlockAsItemWithChance(btw.modern.World world, int x, int y, int z,
                                          int metadata, float chance, int fortune) {
        // 1.5.2 Block.dropBlockAsItemWithChance rolls `chance` per drop (vanilla/client Block.java:859-878);
        // the MC loot table is dropped as one roll.
        if (chance < 1.0F && world != null && world.rand != null && world.rand.nextFloat() > chance) return;
        dropMcLoot(world, x, y, z);
    }

    @Override
    public void DropItemsOnDestroyedByExplosion(btw.modern.World world, int i, int j, int k,
                                                btw.modern.Explosion explosion) {
        dropMcLoot(world, i, j, k);
    }

    private static void dropMcLoot(btw.modern.World world, int x, int y, int z) {
        ServerLevel level = levelOf(world, null);
        if (level == null) return;
        try {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                net.minecraft.world.level.block.Block.dropResources(state, level, pos, level.getBlockEntity(pos));
            }
        } catch (Throwable t) {
            LOGGER.debug("[STAND-IN] MC loot drop failed at {},{},{}: {}", x, y, z, t.toString());
        }
    }

    // ---- saw stays inert (modern blocks were air to FCBlockSaw.SawBlockToFront before): no broken saws on
    // modern wood/stone. GetStackRetrievedByBlockDispenser (null) / IsBlockDestroyedByBlockDispenser (false)
    // keep Modern-Common's inert defaults.
    @Override public boolean DoesBlockBreakSaw(btw.modern.World world, int i, int j, int k) { return false; }
    @Override public boolean OnBlockSawed(btw.modern.World world, int i, int j, int k) { return false; }
}
