package btw.forge.jei;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A soulforge (FCCraftingManagerAnvil) recipe translated into MC ItemStacks.
 *
 * @param width     pattern width (1-4); {@link #GRID} for shapeless recipes
 * @param height    pattern height (1-4); {@link #GRID} for shapeless recipes
 * @param inputs    shaped: one entry per pattern cell (x + y * width), empty for a blank
 *                  cell; shapeless: one entry per ingredient. Each entry lists the stacks
 *                  the cell accepts.
 * @param output    the crafted stack
 * @param shapeless true for recipes added with AddShapelessAnvilRecipe
 */
public record SoulforgeRecipeWrapper(int width, int height, List<List<ItemStack>> inputs,
                                     ItemStack output, boolean shapeless) {
    /** The soulforge crafting grid is 4x4 (FCContainerSoulforge). */
    public static final int GRID = 4;
}
