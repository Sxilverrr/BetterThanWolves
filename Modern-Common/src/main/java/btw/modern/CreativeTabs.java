package btw.modern;

public class CreativeTabs {

    public static final CreativeTabs[] creativeTabArray = new CreativeTabs[12];
    public static final CreativeTabs tabBlock = new CreativeTabs(0, "buildingBlocks");
    public static final CreativeTabs tabDecorations = new CreativeTabs(1, "decorations");
    public static final CreativeTabs tabRedstone = new CreativeTabs(2, "redstone");
    public static final CreativeTabs tabTransport = new CreativeTabs(3, "transportation");
    public static final CreativeTabs tabMisc = new CreativeTabs(4, "misc");
    public static final CreativeTabs tabAllSearch = new CreativeTabs(5, "search");
    public static final CreativeTabs tabFood = new CreativeTabs(6, "food");
    public static final CreativeTabs tabTools = new CreativeTabs(7, "tools");
    public static final CreativeTabs tabCombat = new CreativeTabs(8, "combat");
    public static final CreativeTabs tabBrewing = new CreativeTabs(9, "brewing");
    public static final CreativeTabs tabMaterials = new CreativeTabs(10, "materials");
    public static final CreativeTabs tabInventory = new CreativeTabs(11, "inventory");

    private final int tabIndex;
    private final String tabLabel;

    public CreativeTabs(int index, String label) {
        this.tabIndex = index;
        this.tabLabel = label;
        if (index >= 0 && index < creativeTabArray.length) {
            creativeTabArray[index] = this;
        }
    }

    public int getTabIndex() {
        return this.tabIndex;
    }

    public String getTabLabel() {
        return this.tabLabel;
    }

    // 1.5.2 CreativeTabs.displayAllReleventItems (vanilla/client CreativeTabs.java:134-147): every item whose
    // getCreativeTab() is this tab, expanded through its getSubItems, in Item.itemsList order. (The 1.5.2 Combat and
    // Tools tab subclasses also appended enchanted books through func_92116_a, which FC emptied.) BTWCreativeTab
    // applies the same rule to the FC-owned item ids only.
    @SuppressWarnings("rawtypes")
    public void displayAllReleventItems(java.util.List list) {
        for (Item item : Item.itemsList) {
            if (item != null && item.getCreativeTab() == this) {
                item.getSubItems(item.itemID, this, list);
            }
        }
    }
}
