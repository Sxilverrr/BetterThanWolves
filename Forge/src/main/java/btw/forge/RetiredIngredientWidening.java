package btw.forge;

import btw.forge.removed.RemovedContent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.common.crafting.CompoundIngredient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * Lets other recipes take the BTW successor of a retired (T2) id wherever they take the retired id.
 *
 * <p><b>Maintainer stance, "integrate, don't disable"</b> (INTEGRATIONS.md Protocol #8), applied to the
 * stays-removed ruling ("blocks that 4.B explicitly removed must stay removed"): hiding the T2 ids of
 * {@link RemovedContent} would otherwise strand every 1.20.1 or modded recipe that consumes them (lantern needs a
 * torch, chest boats and the shulker box a chest, terracotta a clay block, red nether bricks a nether brick).
 * Each such ingredient also accepts the BTW item FC itself converts the retired id into: FC's legacy conversion
 * recipes (Server FCRecipes.java:5080-5102: workbench, chest, web, clay, ladder, snow block) and the legacy torch
 * conversion (:793-795, the nether-coal torch). The nether brick item has no FC conversion; its successor is the
 * BTW nether brick that replaced its furnace route (:3026).</p>
 *
 * <p>Rules, run by {@link RecipeBridge} after {@link FcRecipeRemovals} and before {@code replaceRecipes}, so they
 * are re-applied after every {@code /reload}:</p>
 * <ul>
 *   <li>Only recipes outside the {@code betterthanwolves} namespace (FC's injected {@code fc_*} recipes included).</li>
 *   <li>An ingredient is widened when any of its items (tags resolved) is a T2 id; the successor items it does not
 *       already accept are added: {@link Ingredient#merge} for a vanilla ingredient (tags stay tags, the client
 *       sync stays vanilla), Forge {@link CompoundIngredient} for any other ingredient type (its own matching, NBT
 *       for example, is kept).</li>
 *   <li>The recipe is rebuilt with the same id, result, group, category, pattern, notification flag, experience and
 *       cooking time. Exactly these classes are rebuilt: ShapedRecipe, ShapelessRecipe, SmeltingRecipe,
 *       BlastingRecipe, SmokingRecipe, CampfireCookingRecipe and StonecutterRecipe; a subclass or another type
 *       (smithing, modded) is left as is and named in one log line.</li>
 *   <li>Not widened when FC itself makes the result (an injected FC recipe has the same result item): FC's route
 *       wins, as FC always wins at the workbench. Today that keeps {@code chest_minecart} (FC: BTW chest +
 *       minecart, FCRecipes.java:2504), {@code nether_bricks} (FC: loose bricks and mortar, or two slabs, :2917)
 *       and {@code nether_brick_fence} (FC: mouldings, :4842) on FC's own recipes instead of adding a cheaper
 *       shortcut that would also collide with FC's 4-brick loose-slab recipe in the 2x2 grid.</li>
 *   <li>T1 and T3 ingredients are never widened. Recipes that still need one (blast furnace, smoker and furnace
 *       minecart need the furnace, the crossbow a tripwire hook) are reported once as stranded: a later compat
 *       task. They stay craftable from salvaged or existing items, as in 4.B.</li>
 * </ul>
 */
// FCMOD-INTEGRATION(1.20.1): stays-removed: recipes that consume a retired id also accept its BTW successor
final class RetiredIngredientWidening {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RecipeWidening");

    /** Recipe classes already reported as not rebuildable (once per class per JVM). */
    private static final Set<String> UNREBUILDABLE_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private RetiredIngredientWidening() {}

    /**
     * Widens, in place, every qualifying recipe of {@code all} (id -> recipe, after the removals).
     *
     * @return a one-line summary (also logged at INFO)
     */
    static String apply(Map<ResourceLocation, Recipe<?>> all, RegistryAccess access) {
        Pass pass = new Pass(access);

        Set<Item> fcResults = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Recipe<?> r : all.values()) {
            if (!RecipeBridge.isFcRecipeId(r.getId())) continue;
            ItemStack result = pass.result(r);
            if (!result.isEmpty()) fcResults.add(result.getItem());
        }

        List<ResourceLocation> widened = new ArrayList<>();
        Set<ResourceLocation> fcRouted = new TreeSet<>();
        Set<ResourceLocation> notRebuildable = new TreeSet<>();
        Set<ResourceLocation> stranded = new TreeSet<>();

        for (Recipe<?> r : new ArrayList<>(all.values())) {
            ResourceLocation id = r.getId();
            if (id == null || BTWForgeMod.MOD_ID.equals(id.getNamespace())) continue;
            List<Ingredient> ingredients = pass.ingredients(r);
            if (ingredients.isEmpty()) continue;

            boolean needsWidening = false;
            for (Ingredient ing : ingredients) {
                if (!pass.missingSuccessors(ing).isEmpty()) {
                    needsWidening = true;
                    break;
                }
            }

            ItemStack result = pass.result(r);
            boolean fcMakesResult = !result.isEmpty() && fcResults.contains(result.getItem());
            if (needsWidening) {
                if (fcMakesResult) {
                    fcRouted.add(id);
                    continue;
                }
                Recipe<?> rebuilt;
                try {
                    rebuilt = rebuild(r, pass::widen, access);
                } catch (Throwable t) {
                    rebuilt = null;
                    LOGGER.debug("Could not rebuild {}: {}", id, t.toString());
                }
                if (rebuilt == null) {
                    notRebuildable.add(id);
                    if (UNREBUILDABLE_LOGGED.add(r.getClass().getName())) {
                        LOGGER.info("Recipe {} ({}) takes a retired BTW id but its type cannot be rebuilt safely;"
                                + " left as is (logged once per class)", id, r.getClass().getName());
                    }
                } else {
                    all.put(id, rebuilt);
                    widened.add(id);
                    LOGGER.debug("Widened {}: a retired id it takes now also accepts its BTW successor", id);
                    ingredients = pass.ingredients(rebuilt);
                }
            }

            // After widening: an ingredient whose every item is a removed id cannot be met by new items any more.
            for (Ingredient ing : ingredients) {
                if (pass.onlyRemovedItems(ing)) {
                    if (fcMakesResult) fcRouted.add(id); else stranded.add(id);
                    break;
                }
            }
        }

        String summary = String.format("Widened %d recipes whose retired-id ingredients now also accept the BTW successor"
                        + " (ids at DEBUG); not widened because FC makes the result its own way: %s;"
                        + " not rebuildable: %s", widened.size(), fcRouted, notRebuildable);
        LOGGER.info(summary);
        if (!stranded.isEmpty()) {
            LOGGER.info("Stranded by a stubbed (T1) or recipe-only (T3) ingredient, craftable only from salvaged or"
                    + " existing items (later compat task): {}", stranded);
        }
        return summary;
    }

    /** Rebuilds exactly the vanilla recipe classes with every ingredient passed through {@code widen}, else null. */
    private static Recipe<?> rebuild(Recipe<?> r, UnaryOperator<Ingredient> widen, RegistryAccess access) {
        Class<?> type = r.getClass();
        ResourceLocation id = r.getId();
        ItemStack result = r.getResultItem(access).copy();
        if (type == ShapedRecipe.class) {
            ShapedRecipe s = (ShapedRecipe) r;
            return new ShapedRecipe(id, s.getGroup(), s.category(), s.getWidth(), s.getHeight(),
                    widenAll(s.getIngredients(), widen), result, s.showNotification());
        }
        if (type == ShapelessRecipe.class) {
            ShapelessRecipe s = (ShapelessRecipe) r;
            return new ShapelessRecipe(id, s.getGroup(), s.category(), result, widenAll(s.getIngredients(), widen));
        }
        if (type == SmeltingRecipe.class) {
            SmeltingRecipe c = (SmeltingRecipe) r;
            return new SmeltingRecipe(id, c.getGroup(), c.category(), widen.apply(c.getIngredients().get(0)), result,
                    c.getExperience(), c.getCookingTime());
        }
        if (type == BlastingRecipe.class) {
            BlastingRecipe c = (BlastingRecipe) r;
            return new BlastingRecipe(id, c.getGroup(), c.category(), widen.apply(c.getIngredients().get(0)), result,
                    c.getExperience(), c.getCookingTime());
        }
        if (type == SmokingRecipe.class) {
            SmokingRecipe c = (SmokingRecipe) r;
            return new SmokingRecipe(id, c.getGroup(), c.category(), widen.apply(c.getIngredients().get(0)), result,
                    c.getExperience(), c.getCookingTime());
        }
        if (type == CampfireCookingRecipe.class) {
            CampfireCookingRecipe c = (CampfireCookingRecipe) r;
            return new CampfireCookingRecipe(id, c.getGroup(), c.category(), widen.apply(c.getIngredients().get(0)),
                    result, c.getExperience(), c.getCookingTime());
        }
        if (type == StonecutterRecipe.class) {
            StonecutterRecipe s = (StonecutterRecipe) r;
            return new StonecutterRecipe(id, s.getGroup(), widen.apply(s.getIngredients().get(0)), result);
        }
        return null;
    }

    /** A fresh list (the recipe's own list is its internal state) with each ingredient widened. */
    private static NonNullList<Ingredient> widenAll(NonNullList<Ingredient> original, UnaryOperator<Ingredient> widen) {
        NonNullList<Ingredient> out = NonNullList.withSize(original.size(), Ingredient.EMPTY);
        for (int i = 0; i < original.size(); i++) {
            Ingredient ing = original.get(i);
            out.set(i, ing == null ? Ingredient.EMPTY : widen.apply(ing));
        }
        return out;
    }

    /** Per-pass caches (a /reload builds fresh Ingredient objects). */
    private static final class Pass {
        final RegistryAccess access;
        final Map<Ingredient, List<Item>> missing = new IdentityHashMap<>();
        final Map<Recipe<?>, ItemStack> results = new IdentityHashMap<>();

        Pass(RegistryAccess access) {
            this.access = access;
        }

        ItemStack result(Recipe<?> r) {
            ItemStack cached = results.get(r);
            if (cached != null) return cached;
            ItemStack result;
            try {
                result = r.getResultItem(access);
                if (result == null) result = ItemStack.EMPTY;
            } catch (Throwable t) {
                result = ItemStack.EMPTY;
            }
            results.put(r, result);
            return result;
        }

        List<Ingredient> ingredients(Recipe<?> r) {
            try {
                List<Ingredient> out = new ArrayList<>();
                for (Ingredient ing : r.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) out.add(ing);
                }
                return out;
            } catch (Throwable t) {
                return List.of();
            }
        }

        /** The successors of the T2 items this ingredient accepts that it does not accept yet (empty: nothing to do). */
        List<Item> missingSuccessors(Ingredient ing) {
            List<Item> cached = missing.get(ing);
            if (cached != null) return cached;
            List<Item> out = List.of();
            try {
                Set<Item> accepted = Collections.newSetFromMap(new IdentityHashMap<>());
                ItemStack[] stacks = ing.getItems();
                for (ItemStack s : stacks) {
                    if (s != null && !s.isEmpty()) accepted.add(s.getItem());
                }
                Set<Item> add = new LinkedHashSet<>();
                for (Item item : accepted) {
                    for (Item successor : RemovedContent.ingredientSuccessors(item)) {
                        if (!accepted.contains(successor)) add.add(successor);
                    }
                }
                if (!add.isEmpty()) out = List.copyOf(add);
            } catch (Throwable t) {
                out = List.of();
            }
            missing.put(ing, out);
            return out;
        }

        /** {@code ing} plus the successors it lacks; ingredients without a T2 item are returned unchanged. */
        Ingredient widen(Ingredient ing) {
            if (ing == null || ing.isEmpty()) return ing;
            List<Item> add = missingSuccessors(ing);
            if (add.isEmpty()) return ing;
            Ingredient successors = Ingredient.of(add.toArray(new Item[0]));
            return ing.isVanilla()
                    ? Ingredient.merge(List.of(ing, successors))
                    : CompoundIngredient.of(ing, successors);
        }

        /** True if every item of the ingredient is an id of the removed table (nothing new can meet it). */
        boolean onlyRemovedItems(Ingredient ing) {
            try {
                ItemStack[] stacks = ing.getItems();
                if (stacks.length == 0) return false;
                for (ItemStack s : stacks) {
                    if (s == null || s.isEmpty() || RemovedContent.forItem(s.getItem()) == null) return false;
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
