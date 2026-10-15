package btw.modern;

public class BlockFlower extends Block {

    protected BlockFlower(int id, Material material) {
        super(id, material);
        // 1.5.2 BlockFlower constructor (FCMOD, vanilla/client BlockFlower.java:28-38). Subclasses that need
        // other bounds (BlockCrops, most FC plants) call InitBlockBounds again after this.
        // Every 1.5.2 plant random-ticks (vanilla/server BlockFlower.java:30): saplings, tall grass, dead bushes,
        // flowers, mushrooms, crops, stems, nether wart and lily pads all inherit the flag from here.
        setTickRandomly(true);
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

    // In 4.B BlockFlower extends FCBlockPlants (vanilla/server BlockFlower.java:18-21). The port's runtime chain
    // is BlockFlower -> Block (FCBlockCarrot -> BlockCarrot -> BlockCrops -> BlockFlower, FCBlockSapling ->
    // BlockSapling -> BlockFlower, FCBlockHempCrop -> BlockCrops), so the FCBlockPlants members those plants
    // inherit in 4.B live here. FC's own FCBlockPlants subclasses (FCBlockCrops, FCBlockWeeds) keep the real
    // FCBlockPlants and do not pass through this class. Not ported: onBlockActivated weed removal and the
    // AttemptToApplyFertilizerTo relay (use / bone-meal family).

    @Override
    public boolean canPlaceBlockAt(World world, int i, int j, int k) {
        return super.canPlaceBlockAt(world, i, j, k) && CanGrowOnBlock(world, i, j - 1, k);
    }

    // 1.5.2 BlockFlower.canBlockStay (FCMOD "Added New", vanilla/server BlockFlower.java:143-148): enough light
    // (full light >= 8 or a view of the sky; 1.5.2 World.getFullBlockLightValue has no time-of-day darkening,
    // vanilla/server World.java:799-813), then FCBlockPlants.canBlockStay (Client FCBlockPlants.java:79-82):
    // the soil below accepts the plant.
    @Override
    public boolean canBlockStay(World world, int i, int j, int k) {
        return (world.getFullBlockLightValue(i, j, k) >= 8 || world.canBlockSeeTheSky(i, j, k))
            && CanGrowOnBlock(world, i, j - 1, k);
    }

    // 1.5.2 FCBlockPlants.breakBlock (Client FCBlockPlants.java:48-58): the soil hears that its plant is gone
    // (FC farmland untills, FCBlockFarmland.NotifyOfPlantAboveRemoved).
    // Port guard: 1.5.2 calls breakBlock only when Chunk.setBlockIDWithMetadata replaces the block
    // (vanilla/server Chunk.java:534, the call at :581), never for a metadata-only write
    // (Chunk.setBlockMetadata, :681-716).
    // Here breakBlock comes from BlockBehaviorMixin.btw$onRemove on any change of the 1.20.1 block, and one FC
    // block can span two 1.20.1 blocks: StemBlock and AttachedStemBlock are both FC stem 104 / 105. FC's own
    // switch between them (FCBlockStem metadata 15 / 7) is a metadata-only write, which WorldBridge.setBlockMetadata
    // already keeps away from breakBlock (METADATA_ONLY_WRITE). This guard covers the switch FC does not make:
    // 1.20.1's AttachedStemBlock.updateShape reverts to StemBlock AGE 7 when the fruit leaves through a shape-only
    // update (a piston moving the fruit, PistonBaseBlock.moveBlocks). When the cell still holds this FC block,
    // nothing was removed, and the soil must not hear otherwise (FCBlockFarmland.NotifyOfPlantAboveRemoved would
    // untill it; in 1.5.2 losing the fruit is a metadata write).
    @Override
    public void breakBlock(World world, int i, int j, int k, int iBlockID, int iMetadata) {
        super.breakBlock(world, i, j, k, iBlockID, iMetadata);

        if (world.getBlockId(i, j, k) == blockID) {
            return;
        }

        Block blockBelow = Block.blocksList[world.getBlockId(i, j - 1, k)];

        if (blockBelow != null) {
            blockBelow.NotifyOfPlantAboveRemoved(world, i, j - 1, k, this);
        }
    }

    // 1.5.2 FCBlockPlants.GetWeedsGrowthLevel (Client FCBlockPlants.java:105-116): a plant reports the weeds of
    // its soil, so FC farmland weeds stop crop and stem growth (BlockCrops.AttemptToGrow, FCBlockStem.CheckForGrowth).
    @Override
    public int GetWeedsGrowthLevel(IBlockAccess blockAccess, int i, int j, int k) {
        int iBlockBelowID = blockAccess.getBlockId(i, j - 1, k);
        Block blockBelow = Block.blocksList[iBlockBelowID];

        if (blockBelow != null && iBlockBelowID != blockID) {
            return blockBelow.GetWeedsGrowthLevel(blockAccess, i, j - 1, k);
        }

        return 0;
    }

    // 1.5.2 FCBlockPlants.RemoveWeeds (Client FCBlockPlants.java:119-127): relayed to the soil.
    @Override
    public void RemoveWeeds(World world, int i, int j, int k) {
        Block blockBelow = Block.blocksList[world.getBlockId(i, j - 1, k)];

        if (blockBelow != null) {
            blockBelow.RemoveWeeds(world, i, j - 1, k);
        }
    }

    /** FCBlockPlants default (Client FCBlockPlants.java:168-173): checks CanWildVegetationGrowOnBlock. */
    public boolean CanGrowOnBlock(World world, int i, int j, int k) {
        Block blockOn = Block.blocksList[world.getBlockId(i, j, k)];
        return blockOn != null && blockOn.CanWildVegetationGrowOnBlock(world, i, j, k);
    }

    // 1.5.2 BlockFlower.onNeighborBlockChange (vanilla/server BlockFlower.java:73-77). Reached for vanilla-id plants
    // through BlockBehaviorMixin.btw$neighborChanged and for FC-only plants through ProxyBlock.
    @Override
    public void onNeighborBlockChange(World world, int i, int j, int k, int iNeighborBlockID) {
        super.onNeighborBlockChange(world, i, j, k, iNeighborBlockID);
        checkFlowerChange(world, i, j, k);
    }

    // 1.5.2 BlockFlower.updateTick (vanilla/server BlockFlower.java:82-85): the random tick of a plant without a
    // growth body of its own (flowers, tall grass, lily pads) is its survival check.
    @Override
    public void updateTick(World world, int i, int j, int k, java.util.Random rand) {
        checkFlowerChange(world, i, j, k);
    }

    // 1.5.2 BlockFlower.checkFlowerChange (vanilla/server BlockFlower.java:87-94): the one place where a plant is
    // removed because it can no longer stay (its random tick, a neighbour change, and the FCBlockSapling /
    // FCBlockStem / FCBlockNetherStalk ticks that call it directly).
    // Port rule (D3, soils 1.5.2 never had): when the block below is one only the live world knows, 1.20.1 put the
    // plant there and its own survival rule decides (World.GetModernSoilPlantSurvival). FC's light and soil rules
    // keep deciding on every 1.5.2 soil. Without it, FC's light rule removed the grass 1.20.1 grows on moss in
    // dark lush caves, and FCBlockMushroom's light rule the mushrooms on podzol and nylium in full light.
    public final void checkFlowerChange(World world, int i, int j, int k) {
        int iModernSoilSurvival = world.GetModernSoilPlantSurvival(i, j, k);
        boolean bCanStay = iModernSoilSurvival >= 0 ? iModernSoilSurvival > 0 : canBlockStay(world, i, j, k);

        if (!bCanStay) {
            dropBlockAsItem(world, i, j, k, world.getBlockMetadata(i, j, k), 0);
            world.setBlockToAir(i, j, k);
        }
    }
}
