package btw.forge.jei;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A single FC bulk-recipe (cauldron / crucible / mill stone) translated
 * into MC ItemStacks, ready to hand to JEI.
 *
 * @param inputs  one entry per input ingredient slot; each entry lists the
 *                stacks that slot accepts (several for a 1.5.2 "any
 *                metadata" ingredient, e.g. any log)
 * @param outputs one stack per output slot
 */
public record BulkRecipeWrapper(List<List<ItemStack>> inputs, List<ItemStack> outputs) {
}
