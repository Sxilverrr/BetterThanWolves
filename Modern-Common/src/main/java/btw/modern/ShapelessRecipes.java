package btw.modern;

import java.util.ArrayList;
import java.util.List;

public class ShapelessRecipes implements IRecipe {
    private final ItemStack recipeOutput;
    public final List recipeItems;

    public ShapelessRecipes(ItemStack output, List items) {
        this.recipeOutput = output;
        this.recipeItems = items;
    }

    public ItemStack getRecipeOutput() { return recipeOutput; }

    /**
     * FCBetterThanWolves.fcItemMould's itemID: 0 = not resolved yet (FC not initialised), -1 = unavailable.
     * Read reflectively because Modern-Common cannot reference FC code (FC depends on this module); the same
     * pattern as Block.GetFcStaticInt.
     */
    private static volatile int s_iMouldItemID = 0;

    // Package-private so ShapedRecipes.checkMatch can apply 1.5.2's sibling skip (vanilla/server
    // ShapedRecipes.java:115-120). Returns the item id, or <= 0 when moulds cannot be resolved (skip nothing).
    static int GetMouldItemID() {
        int iItemID = s_iMouldItemID;
        if (iItemID == 0) {
            try {
                Object mould = Class.forName("net.minecraft.src.btw.core.FCBetterThanWolves")
                        .getField("fcItemMould").get(null);
                if (!(mould instanceof Item)) {
                    return 0; // FC items not created yet; resolve on a later match
                }
                iItemID = ((Item) mould).itemID;
            } catch (Throwable t) {
                iItemID = -1;
            }
            s_iMouldItemID = iItemID;
        }
        return iItemID;
    }

    @SuppressWarnings("unchecked")
    public boolean matches(InventoryCrafting inv, World world) {
        List<ItemStack> remaining = new ArrayList<>(recipeItems);
        int iMouldItemID = GetMouldItemID();

        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack slot = inv.getStackInSlot(i);
            if (slot != null) {
                // FCMOD (vanilla/server ShapelessRecipes.java:63-68): moulds left in the grid are ignored, so a
                // mould in the Soulforge (FCContainerSoulforge, FCTileEntityAnvil) does not block a shapeless match
                if (iMouldItemID > 0 && slot.itemID == iMouldItemID) {
                    continue;
                }

                boolean matched = false;
                for (int j = 0; j < remaining.size(); j++) {
                    ItemStack expected = (ItemStack) remaining.get(j);
                    // 1.5.2 ShapelessRecipes.matches (vanilla/server ShapelessRecipes.java:76): damage 32767 is the
                    // "any metadata" wildcard (the old -1 could never occur: ItemStack clamps negative damage to 0)
                    if (slot.itemID == expected.itemID
                            && (expected.getItemDamage() == 32767 || expected.getItemDamage() == slot.getItemDamage())) {
                        remaining.remove(j);
                        matched = true;
                        break;
                    }
                }
                if (!matched) return false;
            }
        }

        return remaining.isEmpty();
    }

    public ItemStack getCraftingResult(InventoryCrafting inv) {
        return recipeOutput != null ? recipeOutput.copy() : null;
    }

    public int getRecipeSize() { return recipeItems.size(); }

    public void SetHasSecondaryOutput(boolean hasSecondary) {}
}
