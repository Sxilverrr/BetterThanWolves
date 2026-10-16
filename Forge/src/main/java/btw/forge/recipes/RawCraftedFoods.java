package btw.forge.recipes;

import btw.forge.RecipeBridge;
import btw.forge.food.FcFoodValues;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Meals crafted cold from raw meat keep the raw meat's food poisoning (INTEGRATIONS #025, part F; settled decision
 * #12 (a)).
 *
 * <p>In 4.B a raw ingredient's poisoning reaches a meal only through FC's own recipes, which FC writes with poisoned
 * results itself. Modern and modded meals (a 1.20.1 or HarvestCraft stew crafted from raw meat) get their food values
 * from food-core's table or from their own FoodProperties, which know nothing of the raw ingredient. Rule: a crafted
 * food whose values come from the table or from its modern properties (source TABLE or MODERN_CONVERTED) is marked
 * "raw-crafted" when any non-FC CRAFTING recipe that makes it accepts a poisonous raw item (any registered item whose
 * FC food values carry a poison chance, from any source). food-core applies the mark ({@link
 * FcFoodValues#isRawCrafted}).</p>
 *
 * <p>Recomputed on every injection (ServerStarting and /reload), after food-core's table is final
 * (TagsUpdatedEvent(SERVER_DATA_LOAD) precedes both), over the same recipe map the FC workbench fallback offers.</p>
 */
// FCMOD-INTEGRATION(1.20.1): recipes: crafted foods made from poisonous raw items are marked raw-crafted
public final class RawCraftedFoods {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RecipeBridge");

    private RawCraftedFoods() {}

    /**
     * Computes the raw-crafted set over {@code recipes} and publishes it with {@link FcFoodValues#setRawCrafted}.
     *
     * @return the number of items marked
     */
    public static int apply(Iterable<Recipe<?>> recipes, RegistryAccess access) {
        Set<Item> poisonous = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null || item == Items.AIR) continue;
            FcFoodValues.FcFood food = foodOf(new ItemStack(item));
            if (food != null && food.poisonChance() > 0) poisonous.add(item);
        }

        Set<Item> marked = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Item, Boolean> markable = new IdentityHashMap<>();
        if (!poisonous.isEmpty()) {
            for (Recipe<?> r : recipes) {
                if (r == null || r.getType() != RecipeType.CRAFTING || r.getId() == null
                        || RecipeBridge.isFcRecipeId(r.getId())) {
                    continue;
                }
                ItemStack result;
                try {
                    result = r.getResultItem(access);
                } catch (Throwable t) {
                    continue;
                }
                if (result == null || result.isEmpty()) continue;
                Item out = result.getItem();
                if (marked.contains(out) || !markable.computeIfAbsent(out, i -> isMarkable(result))) continue;
                if (takesPoisonous(r, poisonous)) marked.add(out);
            }
        }

        FcFoodValues.setRawCrafted(marked);
        LOGGER.info("RawCrafted: {} crafted food(s) marked as made from raw ingredients"
                + " ({} poisonous raw item(s))", marked.size(), poisonous.size());
        return marked.size();
    }

    /** True for a food whose values come from the table or its own 1.20.1 properties. */
    private static boolean isMarkable(ItemStack result) {
        FcFoodValues.FcFood food = foodOf(result.copyWithCount(1));
        return food != null && (food.source() == FcFoodValues.Source.TABLE
                || food.source() == FcFoodValues.Source.MODERN_CONVERTED);
    }

    private static boolean takesPoisonous(Recipe<?> r, Set<Item> poisonous) {
        try {
            for (Ingredient ing : r.getIngredients()) {
                if (ing == null || ing.isEmpty()) continue;
                for (ItemStack s : ing.getItems()) {
                    if (s != null && !s.isEmpty() && poisonous.contains(s.getItem())) return true;
                }
            }
        } catch (Throwable ignored) {
            // a recipe that cannot list its ingredients marks nothing
        }
        return false;
    }

    private static FcFoodValues.FcFood foodOf(ItemStack stack) {
        try {
            return FcFoodValues.of(stack, null);
        } catch (Throwable t) {
            return null;
        }
    }
}
