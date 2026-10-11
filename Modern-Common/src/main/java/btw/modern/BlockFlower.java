package btw.modern;

public class BlockFlower extends Block {

    protected BlockFlower(int id, Material material) {
        super(id, material);
        // 1.5.2 BlockFlower constructor (FCMOD, vanilla/client BlockFlower.java:28-38). Subclasses that need
        // other bounds (BlockCrops, most FC plants) call InitBlockBounds again after this.
        float halfWidth = 0.2F;
        InitBlockBounds(0.5F - halfWidth, 0.0F, 0.5F - halfWidth, 0.5F + halfWidth, halfWidth * 3.0F, 0.5F + halfWidth);
    }

    protected BlockFlower(int id) {
        this(id, Material.plants);
    }

    // 1.5.2 BlockFlower.getCollisionBoundingBoxFromPool (vanilla/client BlockFlower.java:110-113; its FCMOD
    // superclass FCBlockPlants returns null too): plants, saplings, tall grass, crops, mushrooms, nether wart
    // and stems have no collision box. FCBlockLilyPad keeps its own override.
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    // 1.5.2 BlockFlower.isOpaqueCube / renderAsNormalBlock (vanilla/client BlockFlower.java:119-130; FCBlockPlants
    // returns false too). With the inherited true, World.isBlockNormalCube reported plant cells as solid, so a
    // small FC mob walking into tall grass had its eye "inside" an opaque block (Entity.isEntityInsideOpaqueBlock)
    // and took an inWall hit every tick.
    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    // 1.5.2 BlockFlower.getRenderType (vanilla/client BlockFlower.java:135-138): crossed squares. BlockSapling,
    // BlockMushroom, BlockDeadBush and BlockTallGrass inherit it as in 1.5.2; BlockCrops (6) and BlockStem (19)
    // override. Read by RenderBlocks.renderBlockByRenderType (in-world, only for subclasses without a
    // RenderBlock override), renderBlockAsItemVanilla (carried/held/embedded drawing, e.g. the iron golem's rose)
    // and Block.DoesItemRenderAsBlock (type 1 draws a flat icon). FCBlockPlants (FC crops/weeds) extends Block in
    // the port, not this class, so it keeps type 0.
    @Override
    public int getRenderType() {
        return 1;
    }

    // 1.5.2 FCBlockPlants.onEntityCollidedWithBlock (Client FCBlockPlants.java:60-70), inherited by every
    // BlockFlower in 1.5.2: entities on the ground move through vegetation at 0.8 speed. Same code as
    // FCBlockReed / FCBlockVine. The null check is a port guard: BlockBehaviorMixin.btw$entityInside routes
    // MC entities here and a ProxyEntity whose FC entity failed to build would pass null.
    @Override
    public void onEntityCollidedWithBlock(World world, int i, int j, int k, Entity entity) {
        // don't slow down movement in air as it affects the ability of entities to jump up blocks
        if (entity != null && entity.IsAffectedByMovementModifiers() && entity.onGround) {
            entity.motionX *= 0.8D;
            entity.motionZ *= 0.8D;
        }
    }

    // Shadow remapping maps FCBlockPlants → BlockFlower.
    // These methods replicate FCBlockPlants's overrides so FC subclasses
    // (FCBlockCrops, FCBlockHempCrop, etc.) dispatch correctly.

    @Override
    public boolean canPlaceBlockAt(World world, int i, int j, int k) {
        return super.canPlaceBlockAt(world, i, j, k) && CanGrowOnBlock(world, i, j - 1, k);
    }

    @Override
    public boolean canBlockStay(World world, int i, int j, int k) {
        return CanGrowOnBlock(world, i, j - 1, k);
    }

    /** FCBlockPlants default: checks CanWildVegetationGrowOnBlock. */
    public boolean CanGrowOnBlock(World world, int i, int j, int k) {
        Block blockOn = Block.blocksList[world.getBlockId(i, j, k)];
        return blockOn != null && blockOn.CanWildVegetationGrowOnBlock(world, i, j, k);
    }

    public final void checkFlowerChange(World world, int i, int j, int k) {}
}
