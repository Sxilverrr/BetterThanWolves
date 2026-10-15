package btw.modern;

public class BlockReed extends Block {

    protected BlockReed(int id) {
        super(id, Material.plants);
        // 1.5.2 BlockReed constructor (vanilla/server BlockReed.java:25)
        setTickRandomly(true);
    }

    /**
     * 1.5.2 BlockReed.updateTick (vanilla/server BlockReed.java:31-57): with air above and fewer than 3 reeds in the
     * column, the age (metadata 0-15) goes up by one per random tick; at 15 a new reed grows above. FCBlockReed
     * overrides it (half the rate, nothing in the End); ported so the base matches 1.5.2.
     */
    @Override
    public void updateTick(World world, int i, int j, int k, java.util.Random rand) {
        if (world.isAirBlock(i, j + 1, k)) {
            int iHeight = 1;

            while (world.getBlockId(i, j - iHeight, k) == blockID) {
                ++iHeight;
            }

            if (iHeight < 3) {
                int iMetadata = world.getBlockMetadata(i, j, k);

                if (iMetadata == 15) {
                    world.setBlock(i, j + 1, k, blockID);
                    world.setBlockMetadata(i, j, k, 0, 4);
                } else {
                    world.setBlockMetadata(i, j, k, iMetadata + 1, 4);
                }
            }
        }
    }

    // 1.5.2 BlockReed.onNeighborBlockChange (vanilla/server BlockReed.java:72-75). Reached through
    // BlockBehaviorMixin.btw$neighborChanged, ahead of 1.20.1's own scheduled survival tick.
    @Override
    public void onNeighborBlockChange(World world, int i, int j, int k, int iNeighborBlockID) {
        checkBlockCoordValid(world, i, j, k);
    }

    // 1.5.2 BlockReed.checkBlockCoordValid (vanilla/server BlockReed.java:80-87): a reed that can no longer stay
    // drops as an item and is removed.
    public final void checkBlockCoordValid(World world, int i, int j, int k) {
        if (!canBlockStay(world, i, j, k)) {
            dropBlockAsItem(world, i, j, k, world.getBlockMetadata(i, j, k), 0);
            world.setBlockToAir(i, j, k);
        }
    }

    // 1.5.2 BlockReed.canBlockStay (vanilla/server BlockReed.java:92-95): the placement rule, which FCBlockReed
    // overrides (reed below, or reed soil with water in the 3x3 around it).
    @Override
    public boolean canBlockStay(World world, int i, int j, int k) {
        return canPlaceBlockAt(world, i, j, k);
    }

    // 1.5.2 BlockReed.idDropped (vanilla/server BlockReed.java:109-112): the reed item, not the block id.
    @Override
    public int idDropped(int iMetadata, java.util.Random rand, int iFortuneModifier) {
        return Item.reed.itemID;
    }

    // 1.5.2 BlockReed.getCollisionBoundingBoxFromPool (vanilla/client BlockReed.java:102-105): sugar cane has
    // no collision box (FCBlockReed does not override it)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    // 1.5.2 BlockReed.isOpaqueCube / renderAsNormalBlock (vanilla/client BlockReed.java:119-130).
    // With the inherited true, World.isBlockNormalCube reported this passable block as solid, so an FC mob's
    // eye inside the cell counted as being inside an opaque block (Entity.isEntityInsideOpaqueBlock -> inWall).
    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    // 1.5.2 BlockReed.getRenderType (vanilla/client BlockReed.java:135-138): crossed squares. FCBlockReed (83, a
    // native MC block) draws itself in-world through its RenderBlock override (renderCrossedSquares); the id
    // selects renderBlockAsItemVanilla's crossed-squares branch and makes DoesItemRenderAsBlock false.
    @Override
    public int getRenderType() {
        return 1;
    }
}
