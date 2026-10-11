package btw.modern;

public class BlockRedstoneWire extends Block {

    protected BlockRedstoneWire(int id) {
        super(id, Material.circuits);
    }

    // 1.5.2 BlockRedstoneWire.getCollisionBoundingBoxFromPool (vanilla/client BlockRedstoneWire.java:45-48):
    // redstone dust has no collision box (FCBlockRedstoneWire does not override it)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    // 1.5.2 BlockRedstoneWire.isOpaqueCube / renderAsNormalBlock (vanilla/client BlockRedstoneWire.java:54-65).
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
