package btw.modern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FC's crafting recipe manager. Stores shaped and shapeless recipes
 * registered during FC initialization.
 *
 * FC code calls addRecipe/AddShapelessRecipe with the MC 1.5.2 format:
 *   addRecipe(output, "###", "# #", "###", '#', ingredient)
 *
 * The recipes are parsed and stored as ShapedRecipes/ShapelessRecipes.
 * The Forge bridge reads getRecipeList() after FC init and converts
 * them to modern MC recipes.
 */
public class CraftingManager {
    private static final CraftingManager instance = new CraftingManager();
    private List recipes = new ArrayList();

    public static CraftingManager getInstance() { return instance; }
    public List getRecipeList() { return recipes; }

    /** ShapedRecipes subclasses AddShapedRecipeWithCustomClass could not instantiate (logged once each). */
    private static final Set<Class<?>> CUSTOM_CLASS_FAILURES = new HashSet<>();

    /**
     * Adds a shaped recipe. FC format:
     *   addRecipe(output, "AB", "CD", 'A', item1, 'B', item2, ...)
     */
    @SuppressWarnings("unchecked")
    public ShapedRecipes addRecipe(ItemStack output, Object... params) {
        ShapedRecipes recipe = createShapedRecipe(output, params);
        recipes.add(recipe);
        return recipe;
    }

    /**
     * Parses FC's shaped-recipe arguments (pattern rows, then character/ingredient pairs) into a ShapedRecipes
     * without registering it. Shared by addRecipe and AddShapedRecipeWithCustomClass, which parse identically in
     * 1.5.2 (vanilla/server CraftingManager.java:163-235, 541-608).
     */
    private static ShapedRecipes createShapedRecipe(ItemStack output, Object... params) {
        String pattern = "";
        int i = 0;
        int width = 0;
        int height = 0;

        // Parse pattern strings
        List<String> rows = new ArrayList<>();
        while (i < params.length && params[i] instanceof String) {
            String row = (String) params[i];
            rows.add(row);
            width = Math.max(width, row.length());
            i++;
        }
        height = rows.size();

        // Build padded pattern
        StringBuilder sb = new StringBuilder();
        for (String row : rows) {
            while (row.length() < width) row = row + " ";
            sb.append(row);
        }
        pattern = sb.toString();

        // Parse character → ingredient map
        Map<Character, ItemStack> charMap = new HashMap<>();
        while (i < params.length) {
            if (params[i] instanceof Character) {
                char c = (Character) params[i];
                i++;
                ItemStack ingredient = toShapedIngredient(params[i]);
                charMap.put(c, ingredient);
                i++;
            } else {
                i++;
            }
        }

        // Build recipe items array
        ItemStack[] recipeItems = new ItemStack[width * height];
        for (int j = 0; j < pattern.length() && j < recipeItems.length; j++) {
            char c = pattern.charAt(j);
            if (c == ' ') {
                recipeItems[j] = null;
            } else {
                ItemStack mapped = charMap.get(c);
                recipeItems[j] = mapped != null ? mapped.copy() : null;
            }
        }

        return new ShapedRecipes(width, height, recipeItems, output);
    }

    /**
     * Adds a shapeless recipe. FC format:
     *   addShapelessRecipe(output, ingredient1, ingredient2, ...)
     */
    @SuppressWarnings("unchecked")
    public void addShapelessRecipe(ItemStack output, Object... params) {
        AddShapelessRecipe(output, params);
    }

    @SuppressWarnings("unchecked")
    public ShapelessRecipes AddShapelessRecipe(ItemStack output, Object... params) {
        List<ItemStack> items = new ArrayList<>();
        for (Object param : params) {
            ItemStack stack = toItemStack(param);
            if (stack != null) {
                items.add(stack);
            }
        }
        ShapelessRecipes recipe = new ShapelessRecipes(output, items);
        recipes.add(recipe);
        return recipe;
    }

    /**
     * 1.5.2 (FCMOD) CraftingManager.AddShapedRecipeWithCustomClass (vanilla/server CraftingManager.java:541-624):
     * parses like addRecipe, then builds the recipe through the given ShapedRecipes subclass's
     * (int, int, ItemStack[], ItemStack) constructor, so FCRecipesWoolBlock / FCRecipesArmorWool colour their
     * results (FCRecipes.java:911-939). It used to register a plain ShapedRecipes, which lost the colouring.
     * 1.5.2 throws when the class can't be built; the port logs once and registers the plain recipe instead.
     * Exactly one recipe is added, so recipe-list indices (FCCraftingRecipes ids) are unchanged.
     */
    @SuppressWarnings("unchecked")
    public ShapedRecipes AddShapedRecipeWithCustomClass(Class clazz, ItemStack output, Object... params) {
        ShapedRecipes recipe = createShapedRecipe(output, params);

        if (clazz != null && clazz != ShapedRecipes.class) {
            try {
                java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor(
                        int.class, int.class, ItemStack[].class, ItemStack.class);
                recipe = (ShapedRecipes) ctor.newInstance(
                        recipe.recipeWidth, recipe.recipeHeight, recipe.recipeItems, output);
            } catch (Throwable t) {
                if (CUSTOM_CLASS_FAILURES.add(clazz)) {
                    org.apache.logging.log4j.LogManager.getLogger("BTW-Crafting").warn(
                            "[CRAFTING] Could not build custom recipe class {} ({}); registering a plain"
                                    + " ShapedRecipes instead", clazz.getName(), t.toString());
                }
            }
        }

        recipes.add(recipe);
        return recipe;
    }

    // FCMOD-INTEGRATION(1.20.1): stays-removed: FC's vanilla-recipe removals are recorded here so the Forge
    // bridge can mirror them onto the 1.20.1 RecipeManager (btw.forge.FcRecipeRemovals).

    /**
     * One FC removal of a 1.5.2 vanilla crafting recipe: FCRecipes.RemoveVanillaRecipe / RemoveVanillaShapelessRecipe
     * (Server FCRecipes.java:78-86), called from RemoveVanillaRecipes (:5112-5760). Parsed exactly as 1.5.2 parsed the
     * removal (vanilla/server CraftingManager.java:382-412 -> CreateRecipe / CreateShapelessRecipe, which parse like
     * addRecipe / addShapelessRecipe): a shaped Block ingredient is "any metadata" (32767), a shapeless one damage 0.
     * Port types only (no MC classes): Modern-Common has no recipe manager of its own to remove from.
     */
    public static final class RemovedVanillaRecipe {
        /** The 1.5.2 result (id, count, damage). */
        public final ItemStack output;
        /** The ingredient stacks: the non-empty cells of a shaped pattern, or the shapeless parameters. */
        public final List<ItemStack> ingredients;
        /** True for RemoveRecipe (shaped), false for RemoveShapelessRecipe. */
        public final boolean shaped;

        RemovedVanillaRecipe(ItemStack output, List<ItemStack> ingredients, boolean shaped) {
            this.output = output;
            this.ingredients = java.util.Collections.unmodifiableList(ingredients);
            this.shaped = shaped;
        }
    }

    /** Every FC vanilla-recipe removal, in call order. Filled once during FC init (FCRecipes.AddAllModRecipes). */
    private static final List<RemovedVanillaRecipe> REMOVED_VANILLA_RECIPES = new ArrayList<>();

    /** Snapshot of the recorded FC vanilla-recipe removals (see {@link RemovedVanillaRecipe}). */
    public static synchronized List<RemovedVanillaRecipe> getRemovedVanillaRecipes() {
        return new ArrayList<>(REMOVED_VANILLA_RECIPES);
    }

    /**
     * 1.5.2 removed the matching vanilla recipe from this manager's list (vanilla/server CraftingManager.java:382-396).
     * The port's list only ever holds FC's own recipes (vanilla recipes live in the 1.20.1 RecipeManager), so the
     * removal is recorded instead and applied to the RecipeManager by the Forge bridge. FC ignores the result.
     */
    public boolean RemoveRecipe(ItemStack output, Object... params) {
        ShapedRecipes parsed = createShapedRecipe(output, params);
        List<ItemStack> cells = new ArrayList<>();
        for (ItemStack cell : parsed.recipeItems) {
            if (cell != null) cells.add(cell.copy());
        }
        record(new RemovedVanillaRecipe(output != null ? output.copy() : null, cells, true));
        return true;
    }

    /** Shapeless twin of {@link #RemoveRecipe} (vanilla/server CraftingManager.java:398-412). */
    public boolean RemoveShapelessRecipe(ItemStack output, Object... params) {
        List<ItemStack> items = new ArrayList<>();
        for (Object param : params) {
            ItemStack stack = toItemStack(param);
            if (stack != null) items.add(stack.copy());
        }
        record(new RemovedVanillaRecipe(output != null ? output.copy() : null, items, false));
        return true;
    }

    private static synchronized void record(RemovedVanillaRecipe removal) {
        REMOVED_VANILLA_RECIPES.add(removal);
    }

    public ItemStack findMatchingRecipe(InventoryCrafting inv, World world) {
        IRecipe recipe = FindMatchingRecipe(inv, world);
        return recipe != null ? recipe.getCraftingResult(inv) : null;
    }

    public IRecipe FindMatchingRecipe(InventoryCrafting inv, World world) {
        for (Object obj : recipes) {
            IRecipe recipe = (IRecipe) obj;
            if (recipe.matches(inv, world)) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * A shaped-recipe ingredient: a Block means "any metadata" (damage 32767), as in 1.5.2 CraftingManager.addRecipe
     * (vanilla/server CraftingManager.java:204-206) and AddShapedRecipeWithCustomClass (:582-585). A shapeless Block
     * stays damage 0 (:264-269), so AddShapelessRecipe keeps toItemStack.
     */
    private static ItemStack toShapedIngredient(Object obj) {
        if (obj instanceof Block) {
            return new ItemStack((Block) obj, 1, 32767);
        }
        return toItemStack(obj);
    }

    /**
     * Converts an Object parameter to an ItemStack.
     * FC passes Item, Block, or ItemStack as recipe ingredients.
     */
    private static ItemStack toItemStack(Object obj) {
        if (obj instanceof ItemStack) {
            return (ItemStack) obj;
        } else if (obj instanceof Item) {
            return new ItemStack((Item) obj);
        } else if (obj instanceof Block) {
            return new ItemStack((Block) obj);
        } else if (obj instanceof Integer) {
            return new ItemStack((Integer) obj, 1, 0);
        }
        return null;
    }
}
