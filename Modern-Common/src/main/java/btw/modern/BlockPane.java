package btw.modern;

public class BlockPane extends Block {

    // vanilla/client BlockPane.java:41 sets tabDecorations in the constructor: FC's wicker pane, grate and slats
    // (FCBlockPane subclasses, no tab of their own) are listed under Decorations through it.
    protected BlockPane(int id, Material material) {
        super(id, material);
        this.setCreativeTab(CreativeTabs.tabDecorations);
    }

    protected BlockPane(int id, String texture, String sideTexture, Material material, boolean canDropItself) {
        super(id, material);
        this.setCreativeTab(CreativeTabs.tabDecorations);
    }

    public final boolean canThisPaneConnectToThisBlockID(int blockID) {
        return blockID > 0 && Block.blocksList[blockID] != null && Block.blocksList[blockID].isOpaqueCube();
    }
}
