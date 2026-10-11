package btw.modern;

public class BlockWeb extends Block {

    protected BlockWeb(int id) {
        super(id, Material.web);
    }

    // 1.5.2 BlockWeb.onEntityCollidedWithBlock (vanilla/client BlockWeb.java:30-33). Only FC physics reaches
    // this (frozen Entity.moveEntity block collisions): 1.20.1 WebBlock.entityInside does not call super, so
    // BlockBehaviorMixin never routes MC entities here.
    @Override
    public void onEntityCollidedWithBlock(World world, int x, int y, int z, Entity entity) {
        entity.setInWeb();
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    // 1.5.2 BlockWeb.getCollisionBoundingBoxFromPool (vanilla/client BlockWeb.java:48-51): cobwebs have no
    // collision box, they slow entities via setInWeb instead (FCBlockWeb does not override either)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public int getRenderType() {
        return 1; // crossed squares
    }
}
