package btw.modern;

public class BlockStem extends BlockFlower {

    public Block fruitType;

    protected BlockStem(int id) {
        super(id, Material.plants);
    }

    protected BlockStem(int id, Block fruitBlock) {
        super(id, Material.plants);
        this.fruitType = fruitBlock;
    }

    // 1.5.2 BlockStem.getRenderType (vanilla/client BlockStem.java:229-232). FCBlockStem draws itself in-world
    // through its RenderBlock override (renderBlockStem); the id selects RenderBlocks.renderBlockAsItemVanilla's
    // stem branch and makes DoesItemRenderAsBlock false.
    @Override
    public int getRenderType() {
        return 19;
    }
}
