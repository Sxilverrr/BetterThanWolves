package btw.modern;

public class BlockPortal extends BlockBreakable {

    protected BlockPortal(int id) {
        super(id, Material.portal, false);
    }

    // 1.5.2 BlockPortal.getCollisionBoundingBoxFromPool (vanilla/client BlockPortal.java:59-62): nether portal
    // blocks have no collision box (FCBlockPortal overrides only the bounds methods)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    // 1.5.2 BlockPortal.isOpaqueCube / renderAsNormalBlock (vanilla/client BlockPortal.java:90-101).
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

    public boolean tryToCreatePortal(World world, int x, int y, int z) { return false; }
}
