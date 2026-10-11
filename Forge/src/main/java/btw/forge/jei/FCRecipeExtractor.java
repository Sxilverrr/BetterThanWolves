package btw.forge.jei;

import btw.forge.ItemStackHelper;
import btw.forge.RecipeBridge;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pulls recipes out of FC's private recipe managers and converts them into
 * JEI-friendly wrappers:
 * <ul>
 *   <li>bulk managers ({@code FCCraftingManagerCauldron}, {@code FCCraftingManagerCrucible},
 *       etc.) -> {@link BulkRecipeWrapper};</li>
 *   <li>{@code FCCraftingManagerAnvil} (the soulforge's 4x4 grid) -> {@link SoulforgeRecipeWrapper};</li>
 *   <li>{@code FCCraftingManagerCampfire} (spit cooking) -> {@link CampfireRecipeWrapper}.</li>
 * </ul>
 *
 * <p>The FC managers live in FC code, which this module does not compile against, so they
 * are reached by reflection. If a layout changes the extractor logs a warning and returns an
 * empty list rather than crashing the plugin.</p>
 */
public final class FCRecipeExtractor {

    private static final Logger LOGGER = LogManager.getLogger("BTW-JEI-Extractor");

    /** 1.5.2 "any metadata" marker (FCUtilsInventory.m_iIgnoreMetadata). */
    private static final int FC_WILDCARD_DAMAGE = 32767;

    private static final String MGR_ANVIL =
            "net.minecraft.src.btw.crafting.FCCraftingManagerAnvil";
    private static final String MGR_CAMPFIRE =
            "net.minecraft.src.btw.crafting.FCCraftingManagerCampfire";

    private FCRecipeExtractor() {}

    /**
     * Extracts all recipes from the given FC crafting manager singleton.
     *
     * @param managerClassName fully-qualified name of the FC manager class
     *                         (e.g. {@code net.minecraft.src.btw.crafting.FCCraftingManagerCauldron})
     * @return a list of wrapped recipes in JEI-ready form, or an empty list
     *         if the manager cannot be reached or has no recipes
     */
    public static List<BulkRecipeWrapper> extract(String managerClassName) {
        try {
            Class<?> managerCls = Class.forName(managerClassName);

            // All FC bulk managers expose a static getInstance() returning
            // the singleton.
            Object singleton = managerCls.getMethod("getInstance").invoke(null);
            if (singleton == null) {
                LOGGER.warn("{}.getInstance() returned null", managerClassName);
                return Collections.emptyList();
            }

            // The m_recipes field lives on the FCCraftingManagerBulk base.
            Field recipesField = findRecipesField(managerCls);
            if (recipesField == null) {
                LOGGER.warn("Could not find m_recipes field on {}", managerClassName);
                return Collections.emptyList();
            }
            recipesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> fcRecipes = (List<Object>) recipesField.get(singleton);
            if (fcRecipes == null || fcRecipes.isEmpty()) return Collections.emptyList();

            List<BulkRecipeWrapper> result = new ArrayList<>(fcRecipes.size());
            for (Object fcRecipe : fcRecipes) {
                BulkRecipeWrapper wrapper = convertRecipe(fcRecipe);
                if (wrapper != null) result.add(wrapper);
            }
            LOGGER.info("Extracted {} recipes from {}", result.size(), managerClassName);
            return result;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("FC manager {} not present: {}", managerClassName, e.getMessage());
            return Collections.emptyList();
        } catch (Throwable e) {
            LOGGER.warn("Failed to extract recipes from {}: {}", managerClassName, e.toString());
            return Collections.emptyList();
        }
    }

    /** Walks up the class hierarchy to find the {@code m_recipes} field. */
    private static Field findRecipesField(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField("m_recipes");
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /**
     * Converts a single {@code FCCraftingManagerBulkRecipe} into an MC
     * ItemStack pair. Uses reflection for the input/output list getters so
     * the extractor does not depend on the FC class at compile time.
     */
    private static BulkRecipeWrapper convertRecipe(Object fcRecipe) {
        try {
            Class<?> cls = fcRecipe.getClass();
            @SuppressWarnings("unchecked")
            List<Object> fcInputs =
                    (List<Object>) cls.getMethod("getCraftingIngrediantList").invoke(fcRecipe);
            @SuppressWarnings("unchecked")
            List<Object> fcOutputs =
                    (List<Object>) cls.getMethod("getCraftingOutputList").invoke(fcRecipe);

            List<List<ItemStack>> inputs = new ArrayList<>();
            List<ItemStack> outputs = new ArrayList<>();
            if (fcInputs != null) {
                for (Object fcStack : fcInputs) {
                    // Wildcard ingredients (e.g. any log for potash, FCRecipes.java:3911-3915)
                    // cycle through every variant instead of resolving to a single one.
                    List<ItemStack> alternatives = RecipeBridge.fcStackAlternatives((btw.modern.ItemStack) fcStack);
                    if (!alternatives.isEmpty()) inputs.add(alternatives);
                }
            }
            if (fcOutputs != null) {
                for (Object fcStack : fcOutputs) {
                    ItemStack mc = ItemStackHelper.toMcStack((btw.modern.ItemStack) fcStack);
                    if (mc != null && !mc.isEmpty()) outputs.add(mc);
                }
            }
            if (inputs.isEmpty() && outputs.isEmpty()) return null;
            return new BulkRecipeWrapper(inputs, outputs);
        } catch (Throwable e) {
            LOGGER.debug("Skipping recipe: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Soulforge (FCCraftingManagerAnvil)
    // ------------------------------------------------------------------

    /**
     * Extracts the soulforge recipes: FCCraftingManagerAnvil keeps btw.modern ShapedRecipes
     * (4x4 patterns, FCCraftingManagerAnvil.java:44-105) and ShapelessRecipes (:107-137),
     * registered through FCRecipes.AddAnvilRecipe / AddShapelessAnvilRecipe.
     */
    public static List<SoulforgeRecipeWrapper> extractSoulforge() {
        try {
            Class<?> managerCls = Class.forName(MGR_ANVIL);
            Object singleton = managerCls.getMethod("getInstance").invoke(null);
            if (singleton == null) return Collections.emptyList();
            Object list = managerCls.getMethod("getRecipeList").invoke(singleton);
            if (!(list instanceof List<?> fcRecipes) || fcRecipes.isEmpty()) return Collections.emptyList();

            List<SoulforgeRecipeWrapper> result = new ArrayList<>(fcRecipes.size());
            for (Object fcRecipe : fcRecipes) {
                try {
                    SoulforgeRecipeWrapper wrapper = convertAnvilRecipe(fcRecipe);
                    if (wrapper != null) result.add(wrapper);
                } catch (Throwable e) {
                    LOGGER.debug("Skipping soulforge recipe: {}", e.toString());
                }
            }
            LOGGER.info("Extracted {} recipes from {}", result.size(), MGR_ANVIL);
            return result;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("FC manager {} not present: {}", MGR_ANVIL, e.getMessage());
            return Collections.emptyList();
        } catch (Throwable e) {
            LOGGER.warn("Failed to extract recipes from {}: {}", MGR_ANVIL, e.toString());
            return Collections.emptyList();
        }
    }

    private static SoulforgeRecipeWrapper convertAnvilRecipe(Object fcRecipe) {
        if (fcRecipe instanceof btw.modern.ShapedRecipes shaped) {
            ItemStack output = ItemStackHelper.toMcStack(shaped.getRecipeOutput());
            if (output.isEmpty()) return null;
            int w = shaped.recipeWidth;
            int h = shaped.recipeHeight;
            if (w <= 0 || h <= 0 || w > SoulforgeRecipeWrapper.GRID || h > SoulforgeRecipeWrapper.GRID) return null;
            List<List<ItemStack>> cells = new ArrayList<>(w * h);
            for (int i = 0; i < w * h; i++) {
                btw.modern.ItemStack fcStack = i < shaped.recipeItems.length ? shaped.recipeItems[i] : null;
                if (fcStack == null) {
                    cells.add(List.of());
                    continue;
                }
                List<ItemStack> alternatives = RecipeBridge.fcStackAlternatives(fcStack);
                if (alternatives.isEmpty()) return null; // an unmappable ingredient: don't show a wrong recipe
                cells.add(alternatives);
            }
            return new SoulforgeRecipeWrapper(w, h, cells, output, false);
        }
        if (fcRecipe instanceof btw.modern.ShapelessRecipes shapeless) {
            ItemStack output = ItemStackHelper.toMcStack(shapeless.getRecipeOutput());
            if (output.isEmpty()) return null;
            List<List<ItemStack>> cells = new ArrayList<>();
            for (Object o : shapeless.recipeItems) {
                if (!(o instanceof btw.modern.ItemStack fcStack)) continue;
                List<ItemStack> alternatives = RecipeBridge.fcStackAlternatives(fcStack);
                if (alternatives.isEmpty()) return null;
                cells.add(alternatives);
            }
            if (cells.isEmpty() || cells.size() > SoulforgeRecipeWrapper.GRID * SoulforgeRecipeWrapper.GRID) return null;
            return new SoulforgeRecipeWrapper(SoulforgeRecipeWrapper.GRID, SoulforgeRecipeWrapper.GRID,
                    cells, output, true);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Campfire (FCCraftingManagerCampfire)
    // ------------------------------------------------------------------

    /**
     * Extracts the campfire recipes: FCCraftingManagerCampfire.m_recipeMap maps an input item
     * id to its cooked stack (FCCraftingManagerCampfire.java:27-43; FCRecipes.java:3036-3046).
     * FCTileEntityCampfire looks the input up by item id only, so every metadata of the input
     * counts.
     */
    public static List<CampfireRecipeWrapper> extractCampfire() {
        try {
            Class<?> managerCls = Class.forName(MGR_CAMPFIRE);
            Object singleton = managerCls.getField("instance").get(null);
            if (singleton == null) return Collections.emptyList();
            Field mapField = managerCls.getDeclaredField("m_recipeMap");
            mapField.setAccessible(true);
            Object map = mapField.get(singleton);
            if (!(map instanceof Map<?, ?> recipes) || recipes.isEmpty()) return Collections.emptyList();

            List<CampfireRecipeWrapper> result = new ArrayList<>(recipes.size());
            for (Map.Entry<?, ?> entry : recipes.entrySet()) {
                if (!(entry.getKey() instanceof Integer inputId)
                        || !(entry.getValue() instanceof btw.modern.ItemStack fcOutput)) continue;
                List<ItemStack> inputs = RecipeBridge.fcStackAlternatives(
                        new btw.modern.ItemStack(inputId, 1, FC_WILDCARD_DAMAGE));
                ItemStack output = ItemStackHelper.toMcStack(fcOutput);
                if (inputs.isEmpty() || output.isEmpty()) continue;
                result.add(new CampfireRecipeWrapper(inputs, output));
            }
            LOGGER.info("Extracted {} recipes from {}", result.size(), MGR_CAMPFIRE);
            return result;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("FC manager {} not present: {}", MGR_CAMPFIRE, e.getMessage());
            return Collections.emptyList();
        } catch (Throwable e) {
            LOGGER.warn("Failed to extract recipes from {}: {}", MGR_CAMPFIRE, e.toString());
            return Collections.emptyList();
        }
    }
}
