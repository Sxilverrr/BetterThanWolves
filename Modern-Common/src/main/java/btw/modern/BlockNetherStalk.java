package btw.modern;

public class BlockNetherStalk extends BlockFlower {

    protected BlockNetherStalk(int id) {
        super(id, Material.plants);
    }

    // 1.5.2 BlockNetherStalk.getRenderType (vanilla/client BlockNetherStalk.java:77-80): the crop '#' shape, not
    // BlockFlower's crossed squares. FCBlockNetherStalk (115, a native MC block) draws itself in-world through its
    // RenderBlock override (renderBlockCrops); the id selects renderBlockAsItemVanilla's crops branch and keeps
    // DoesItemRenderAsBlock false.
    @Override
    public int getRenderType() {
        return 6;
    }
}
