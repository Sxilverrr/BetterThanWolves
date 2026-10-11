package btw.modern;

public class ShapedRecipes implements IRecipe {

    public final int recipeWidth;
    public final int recipeHeight;
    public final ItemStack[] recipeItems;
    private ItemStack recipeOutput;

    public ShapedRecipes(int width, int height, ItemStack[] items, ItemStack output) {
        this.recipeWidth = width;
        this.recipeHeight = height;
        this.recipeItems = items;
        this.recipeOutput = output;
    }

    public ItemStack getRecipeOutput() {
        return this.recipeOutput;
    }

    public int getRecipeSize() {
        return this.recipeWidth * this.recipeHeight;
    }

    public boolean matches(InventoryCrafting inv, World world) {
        int gridWidth = (int) Math.sqrt(inv.getSizeInventory());
        for (int xOffset = 0; xOffset <= gridWidth - recipeWidth; xOffset++) {
            for (int yOffset = 0; yOffset <= gridWidth - recipeHeight; yOffset++) {
                if (checkMatch(inv, xOffset, yOffset, false, gridWidth)) return true;
                if (checkMatch(inv, xOffset, yOffset, true, gridWidth)) return true;
            }
        }
        return false;
    }

    private boolean checkMatch(InventoryCrafting inv, int xOffset, int yOffset, boolean mirror, int gridWidth) {
        // fcItemMould's itemID, resolved reflectively and cached by ShapelessRecipes; <= 0 skips nothing
        int iMouldItemID = ShapelessRecipes.GetMouldItemID();

        for (int x = 0; x < gridWidth; x++) {
            for (int y = 0; y < gridWidth; y++) {
                int recipeX = x - xOffset;
                int recipeY = y - yOffset;
                ItemStack expected = null;

                if (recipeX >= 0 && recipeY >= 0 && recipeX < recipeWidth && recipeY < recipeHeight) {
                    if (mirror) {
                        expected = recipeItems[recipeWidth - recipeX - 1 + recipeY * recipeWidth];
                    } else {
                        expected = recipeItems[recipeX + recipeY * recipeWidth];
                    }
                }

                ItemStack actual = inv.getStackInRowAndColumn(x, y);

                // FCMOD (vanilla/server ShapedRecipes.java:115-120): moulds left in the grid are ignored, so a
                // legacy mould FCContainerSoulforge moved into the grid does not block a shaped match
                if (actual != null && iMouldItemID > 0 && actual.itemID == iMouldItemID) {
                    actual = null;
                }

                if (expected == null && actual != null) return false;
                if (expected != null && actual == null) return false;
                if (expected == null) continue;

                if (actual.itemID != expected.itemID) return false;
                // 1.5.2 ShapedRecipes.checkMatch (vanilla/server ShapedRecipes.java:134): damage 32767
                // (FCUtilsInventory.m_iIgnoreMetadata) matches any metadata. The old -1 test could never fire:
                // ItemStack clamps negative damage to 0, so FC's wildcard ingredients never matched at the table.
                if (expected.getItemDamage() != 32767 && expected.getItemDamage() != actual.getItemDamage()) return false;
            }
        }
        return true;
    }

    public ItemStack getCraftingResult(InventoryCrafting inv) {
        return this.recipeOutput.copy();
    }
}
