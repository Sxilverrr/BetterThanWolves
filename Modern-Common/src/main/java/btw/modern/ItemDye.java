package btw.modern;

public class ItemDye extends Item {

    public ItemDye(int id) {
        super(id);
        // FCMOD-INTEGRATION(1.20.1): bone_meal: 1.5.2 ItemDye (vanilla/server ItemDye.java:23-29) has subtypes and no
        // durability; FCItemDye (BTWLifecycle.java:1500) inherits them. FC's merge rule (FCUtilsInventory
        // .CanStacksMerge:405-413) and the merchant slot rule (MerchantRecipeList) compare damage only for subtyped
        // items: without them an ink sac (dye 0) merged into any dye slot and took its colour, and the 1.20.1 dyes,
        // FC (351, 256 / 259 / 260 / 271) (Forge LegacyVariants.MODERN_SPELLING), would merge into an ink sac, cocoa,
        // lapis or bone-meal slot, now that ItemStack.isItemDamaged is at 1.5.2 parity for subtyped items
        // (vanilla/server ItemStack.java:239-242).
        setHasSubtypes(true);
        setMaxDamage(0);
    }
}
