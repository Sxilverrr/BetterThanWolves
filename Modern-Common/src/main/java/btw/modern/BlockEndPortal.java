package btw.modern;

import java.util.List;

public class BlockEndPortal extends BlockContainer {

    protected BlockEndPortal(int id, Material material) {
        super(id, material);
    }

    // 1.5.2 BlockEndPortal.addCollisionBoxesToList (vanilla/client BlockEndPortal.java:63): the end portal adds
    // no collision boxes (FCBlockEndPortal overrides only setBlockBoundsBasedOnState)
    @Override
    public void addCollisionBoxesToList(World world, int x, int y, int z, AxisAlignedBB mask, List list, Entity entity) {}

    // 1.5.2 BlockEndPortal.isOpaqueCube / renderAsNormalBlock (vanilla/client BlockEndPortal.java:69-80). With the
    // inherited true, World.isBlockNormalCube reported the passable portal as solid (Entity.isEntityInsideOpaqueBlock
    // -> inWall hit).
    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    public TileEntity createNewTileEntity(World world) {
        return null;
    }
}
