package btw.modern;

public class ItemBow extends Item {

    public ItemBow(int id) {
        super(id);
        this.maxStackSize = 1;
        // vanilla/client ItemBow.java:26. FCItemBow inherits it (its damageItem wear, FCItemBow.java:82,
        // was a no-op at max damage 0); FCItemCompositeBow raises it to 576 in its own constructor.
        this.setMaxDamage(384);
        // vanilla/client ItemBow.java:27; FCItemBow / FCItemCompositeBow set no tab of their own
        this.setCreativeTab(CreativeTabs.tabCombat);
    }

    /**
     * vanilla/client ItemBow.java:106-109. FCItemBow.onItemRightClick starts the draw with it
     * (setItemInUse, FCItemBow.java:102) and GetCurrentPullStrength measures ticks in use against it
     * (FCItemBow.java:144-146); at 0 the FC composite bow could never be drawn.
     */
    @Override
    public int getMaxItemUseDuration(ItemStack stack) {
        return 72000;
    }

    /** vanilla/client ItemBow.java:114-117 (the bow-pull pose). */
    @Override
    public EnumAction getItemUseAction(ItemStack stack) {
        return EnumAction.bow;
    }
}
