package btw.modern;

public class BlockCactus extends Block {

    protected BlockCactus(int id) {
        super(id, Material.cactus);
    }

    // 1.5.2 BlockCactus.getCollisionBoundingBoxFromPool (vanilla/client BlockCactus.java:67-71): inset 1/16 on
    // the sides and the top (FCBlockCactus does not override it)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        float inset = 0.0625F;
        return AxisAlignedBB.getAABBPool().getAABB((double)((float)x + inset), (double)y, (double)((float)z + inset),
            (double)((float)(x + 1) - inset), (double)((float)(y + 1) - inset), (double)((float)(z + 1) - inset));
    }

    // 1.5.2 BlockCactus.getSelectedBoundingBoxFromPool (vanilla/client BlockCactus.java:76-80): same inset,
    // full height
    @Override
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World world, int x, int y, int z) {
        float inset = 0.0625F;
        return AxisAlignedBB.getAABBPool().getAABB((double)((float)x + inset), (double)y, (double)((float)z + inset),
            (double)((float)(x + 1) - inset), (double)(y + 1), (double)((float)(z + 1) - inset));
    }

    // 1.5.2 BlockCactus.renderAsNormalBlock / isOpaqueCube (vanilla/client BlockCactus.java:93-96, 102-105). A mob
    // pressed against the inset box reaches 1/16 into the cell; with the inherited true that counted as being
    // inside an opaque block (Entity.isEntityInsideOpaqueBlock -> inWall hit).
    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    // 1.5.2 BlockCactus.onEntityCollidedWithBlock (vanilla/client BlockCactus.java:166-169; FCBlockCactus does not
    // override it). Only FC physics reaches this (frozen Entity.doBlockCollisions on FC puppets): 1.20.1
    // CactusBlock.entityInside does not call super, so BlockBehaviorMixin never routes MC entities here and
    // they keep MC's own cactus damage.
    @Override
    public void onEntityCollidedWithBlock(World world, int x, int y, int z, Entity entity) {
        if (entity != null) {
            entity.attackEntityFrom(DamageSource.cactus, 1);
        }
    }

    // 1.5.2 BlockCactus.getRenderType (vanilla/client BlockCactus.java:110-113). FCBlockCactus (81, a native MC
    // block) draws itself in-world through its RenderBlock override (renderBlockCactus); the id selects
    // renderBlockAsItemVanilla's inset-cactus branch (an enderman's carried cactus). DoesItemRenderAsBlock stays
    // true, as for 0.
    @Override
    public int getRenderType() {
        return 13;
    }

    private Icon cactusTopIcon;
    private Icon cactusBottomIcon;

    // 1.5.2 BlockCactus.getIcon / registerIcons (vanilla/client BlockCactus.java:85-88, 175-180): top, bottom and
    // side textures. FCBlockCactus overrides neither; the inherited Block.registerIcons registered "cactus", which
    // names no 1.20.1 texture.
    @Override
    public Icon getIcon(int side, int metadata) {
        return side == 1 ? this.cactusTopIcon : (side == 0 ? this.cactusBottomIcon : this.blockIcon);
    }

    @Override
    public void registerIcons(IconRegister register) {
        this.blockIcon = register.registerIcon("cactus_side");
        this.cactusTopIcon = register.registerIcon("cactus_top");
        this.cactusBottomIcon = register.registerIcon("cactus_bottom");
    }
}
