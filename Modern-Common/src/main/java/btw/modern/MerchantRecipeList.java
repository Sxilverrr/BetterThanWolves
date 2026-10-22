package btw.modern;

import java.util.ArrayList;

public class MerchantRecipeList extends ArrayList {
    public MerchantRecipeList() {}
    public MerchantRecipeList(NBTTagCompound tag) { this.readRecipiesFromTags(tag); }

    // FCMOD-INTEGRATION(1.20.1): bone_meal: 4.B's FCMOD slot tests (vanilla/server MerchantRecipeList.java:165-207):
    // a subtyped item must match the damage too, so neither 1.20.1 white dye, FC (351, 271), nor a red dye sells as
    // the farmer's bone meal (FCEntityVillager.java:643), 1.20.1 black dye, FC (351, 256), does not sell as the
    // librarian's ink sac (:745; modern_dyes, INTEGRATIONS #038), and the brown wool knit (:642) and blood wood
    // sapling (:781) trades take only their own subtype. 4.B's index strictness (:138-163: iRecipeIndex >= 0 tests
    // that recipe alone) is not ported: it relies on GuiMerchant.updateScreen selecting the trade from the offered
    // items (vanilla/client GuiMerchant.java:502-560), while the port's screen selects only on a click (Forge
    // FCContainerScreen.java:313-333), so the default index 0 still reaches the scan below.
    public MerchantRecipe canRecipeBeUsed(ItemStack buy1, ItemStack buy2, int index) {
        if (index > 0 && index < this.size()) {
            MerchantRecipe r = (MerchantRecipe) this.get(index);
            if (validFirst(buy1, r) && validSecond(buy2, r)) {
                return r;
            }
        }
        for (int i = 0; i < this.size(); i++) {
            MerchantRecipe r = (MerchantRecipe) this.get(i);
            if (validFirst(buy1, r) && validSecond(buy2, r)) {
                return r;
            }
        }
        return null;
    }

    /** 4.B IsStackValidForFirstSlotOfRecipe (vanilla/server MerchantRecipeList.java:165-180). */
    private static boolean validFirst(ItemStack s, MerchantRecipe r) {
        ItemStack w = r.getItemToBuy();
        return s != null && s.itemID == w.itemID && s.stackSize >= w.stackSize
                && (!w.getHasSubtypes() || s.getItemDamage() == w.getItemDamage());
    }

    /** 4.B IsStackValidForSecondSlotOfRecipe (vanilla/server MerchantRecipeList.java:182-207). */
    private static boolean validSecond(ItemStack s, MerchantRecipe r) {
        if (!r.hasSecondItemToBuy()) return s == null;
        ItemStack w = r.getSecondItemToBuy();
        return s != null && s.itemID == w.itemID && s.stackSize >= w.stackSize
                && (!w.getHasSubtypes() || s.getItemDamage() == w.getItemDamage());
    }

    public void addToListWithCheck(MerchantRecipe recipe) {
        for (int i = 0; i < this.size(); i++) {
            MerchantRecipe existing = (MerchantRecipe) this.get(i);
            if (recipe.hasSameIDsAs(existing)) {
                return;
            }
        }
        this.add(recipe);
    }

    public void readRecipiesFromTags(NBTTagCompound tag) {
        NBTTagList list = tag.getTagList("Recipes");
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound recipeTag = (NBTTagCompound) list.tagAt(i);
            this.add(new MerchantRecipe(recipeTag));
        }
    }

    public NBTTagCompound getRecipiesAsTags() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList("Recipes");
        for (int i = 0; i < this.size(); i++) {
            MerchantRecipe r = (MerchantRecipe) this.get(i);
            list.appendTag(r.writeToTags());
        }
        tag.setTag("Recipes", list);
        return tag;
    }

    public static MerchantRecipeList readRecipiesFromStream(java.io.DataInputStream stream) throws java.io.IOException {
        return new MerchantRecipeList();
    }
}
