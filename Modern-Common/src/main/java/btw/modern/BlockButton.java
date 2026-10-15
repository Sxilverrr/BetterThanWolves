package btw.modern;

public abstract class BlockButton extends Block {

    // 1.5.2 BlockButton constructor sets the random-tick flag (vanilla/server BlockButton.java:27); both port
    // constructors stand for that one 1.5.2 constructor. Flag only: the button tick body stays 1.20.1's.
    protected BlockButton(int id) {
        super(id, Material.circuits);
        this.setTickRandomly(true);
    }

    protected BlockButton(int id, boolean sensitive) {
        super(id, Material.circuits);
        this.setTickRandomly(true);
    }

    // 1.5.2 BlockButton.getCollisionBoundingBoxFromPool (vanilla/client BlockButton.java:37-40): buttons have
    // no collision box (FCBlockButton overrides only the bounds methods)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    // 1.5.2 BlockButton.isOpaqueCube / renderAsNormalBlock (vanilla/client BlockButton.java:54-65).
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
