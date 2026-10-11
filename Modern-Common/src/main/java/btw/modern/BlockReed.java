package btw.modern;

public class BlockReed extends Block {

    protected BlockReed(int id) {
        super(id, Material.plants);
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
