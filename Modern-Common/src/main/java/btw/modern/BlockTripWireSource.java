package btw.modern;

public class BlockTripWireSource extends Block {

    // 1.5.2 BlockTripWireSource constructor sets the random-tick flag (vanilla/server BlockTripWireSource.java:24). Flag
    // only: the tripwire hook tick body stays 1.20.1's.
    protected BlockTripWireSource(int id) {
        super(id, Material.circuits);
        this.setTickRandomly(true);
    }

    // 1.5.2 BlockTripWireSource.getCollisionBoundingBoxFromPool (vanilla/client BlockTripWireSource.java:32-35):
    // tripwire hooks have no collision box (FCBlockTripWireSource overrides only the bounds methods)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    // 1.5.2 BlockTripWireSource.isOpaqueCube / renderAsNormalBlock (vanilla/client BlockTripWireSource.java:41-52).
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
}
