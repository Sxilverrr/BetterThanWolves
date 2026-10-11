package btw.forge.jei;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A campfire spit-cooking recipe (FCCraftingManagerCampfire) translated into MC ItemStacks.
 *
 * @param inputs the stacks that cook into {@code output} (every variant of the input id)
 * @param output the cooked stack
 */
public record CampfireRecipeWrapper(List<ItemStack> inputs, ItemStack output) {
}
