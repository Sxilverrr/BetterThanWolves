package btw.forge.jei;

import btw.forge.BTWForgeMod;
import btw.forge.ItemStackHelper;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Display-only crafting recipes for FC's code-driven IRecipe classes, shown in JEI's
 * crafting category (the real recipes are {@code FCCraftingRecipes.Custom} special recipes,
 * which JEI does not list).
 *
 * <p>Nothing here is hard-coded from the wiki: each FC recipe is probed with FC's own
 * {@code matches}/{@code getCraftingResult}. One known partner seeds the search (the vanilla
 * log for log chopping, the fishing rod for baiting, the knitting needles for knitting -- the
 * items those classes test for by id) and every FC item is tried in the other role.</p>
 */
final class FCCustomCraftingDisplays {

    private static final Logger LOGGER = LogManager.getLogger("BTW-JEI-Custom");

    /** Headless pass-through items (ProxyRegistry, >= 25000) never take part in FC recipes. */
    private static final int MAX_PROBE_ID = 25000;

    private FCCustomCraftingDisplays() {}

    static List<CraftingRecipe> build() {
        List<CraftingRecipe> out = new ArrayList<>();
        List<?> list;
        try {
            list = btw.modern.CraftingManager.getInstance().getRecipeList();
        } catch (Throwable t) {
            LOGGER.warn("FC CraftingManager unavailable: {}", t.toString());
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof btw.modern.IRecipe recipe)
                    || o instanceof btw.modern.ShapedRecipes || o instanceof btw.modern.ShapelessRecipes) {
                continue;
            }
            String name = recipe.getClass().getSimpleName();
            int before = out.size();
            try {
                switch (name) {
                    case "FCRecipesLogChopping" -> logChopping(recipe, name, out);
                    case "FCRecipesFishingRodBaiting" -> rodBaiting(recipe, name, out);
                    case "FCRecipesKnitting" -> knitting(recipe, name, out);
                    default -> LOGGER.debug("No JEI display for FC recipe class {}", recipe.getClass().getName());
                }
            } catch (Throwable t) {
                LOGGER.warn("Could not build JEI displays for {}: {}", name, t.toString());
            }
            LOGGER.info("Built {} JEI crafting displays for {}", out.size() - before, name);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Per-class probes
    // ------------------------------------------------------------------

    /** FCRecipesLogChopping.java:25-138: axe + log -> planks (iron+ axe) or 2 sticks. */
    private static void logChopping(btw.modern.IRecipe recipe, String name, List<CraftingRecipe> out) {
        btw.modern.ItemStack seedLog = new btw.modern.ItemStack(btw.modern.Block.wood.blockID, 1, 0);
        List<btw.modern.ItemStack> axes = partners(recipe, seedLog, false);
        if (axes.isEmpty()) return;
        List<btw.modern.ItemStack> logs = partners(recipe, axes.get(0), true);

        int n = 0;
        for (btw.modern.ItemStack log : logs) {
            for (btw.modern.ItemStack logVariant : distinctVariants(log.itemID)) {
                ItemStack mcLog = ItemStackHelper.toMcStack(logVariant);
                if (mcLog.isEmpty()) continue;
                // Group the axes by what they produce from this log (tool tier decides).
                Map<String, List<ItemStack>> axesByResult = new LinkedHashMap<>();
                Map<String, ItemStack> resultByKey = new LinkedHashMap<>();
                for (btw.modern.ItemStack axe : axes) {
                    btw.modern.ItemStack result = result(recipe, axe, logVariant);
                    ItemStack mcAxe = ItemStackHelper.toMcStack(axe);
                    ItemStack mcResult = ItemStackHelper.toMcStack(result);
                    if (mcAxe.isEmpty() || mcResult.isEmpty()) continue;
                    String key = key(mcResult);
                    axesByResult.computeIfAbsent(key, k -> new ArrayList<>()).add(mcAxe);
                    resultByKey.putIfAbsent(key, mcResult);
                }
                for (Map.Entry<String, List<ItemStack>> e : axesByResult.entrySet()) {
                    out.add(shapeless(name, n++, resultByKey.get(e.getKey()),
                            Ingredient.of(e.getValue().toArray(new ItemStack[0])), Ingredient.of(mcLog)));
                }
            }
        }
    }

    /** FCRecipesFishingRodBaiting.java:25-165: fishing rod + one bait -> baited rod. */
    private static void rodBaiting(btw.modern.IRecipe recipe, String name, List<CraftingRecipe> out) {
        btw.modern.ItemStack rod = new btw.modern.ItemStack(btw.modern.Item.fishingRod.itemID, 1, 0);
        List<btw.modern.ItemStack> baits = partners(recipe, rod, true);
        if (baits.isEmpty()) return;
        ItemStack mcResult = ItemStackHelper.toMcStack(result(recipe, rod, baits.get(0)));
        ItemStack mcRod = ItemStackHelper.toMcStack(rod);
        List<ItemStack> mcBaits = new ArrayList<>();
        for (btw.modern.ItemStack bait : baits) {
            ItemStack mc = ItemStackHelper.toMcStack(bait);
            if (!mc.isEmpty()) mcBaits.add(mc);
        }
        if (mcResult.isEmpty() || mcRod.isEmpty() || mcBaits.isEmpty()) return;
        out.add(shapeless(name, 0, mcResult, Ingredient.of(mcRod),
                Ingredient.of(mcBaits.toArray(new ItemStack[0]))));
    }

    /** FCRecipesKnitting.java:28-123: knitting needles + two wool -> knitting (averaged colour). */
    private static void knitting(btw.modern.IRecipe recipe, String name, List<CraftingRecipe> out) throws Exception {
        Object needlesItem = Class.forName("net.minecraft.src.btw.core.FCBetterThanWolves")
                .getField("fcItemKnittingNeedles").get(null);
        if (!(needlesItem instanceof btw.modern.Item needles)) return;
        btw.modern.ItemStack needlesStack = new btw.modern.ItemStack(needles.itemID, 1, 0);
        ItemStack mcNeedles = ItemStackHelper.toMcStack(needlesStack);
        if (mcNeedles.isEmpty()) return;

        // Wool is the item that pairs with itself next to the needles.
        List<btw.modern.ItemStack> wools = new ArrayList<>();
        for (btw.modern.ItemStack candidate : candidates()) {
            if (matches(recipe, needlesStack, candidate, candidate)) wools.add(candidate);
        }

        int n = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (btw.modern.ItemStack wool : wools) {
            // FC wool colour is the item damage, 0-15 (FCItemWool.java:47-62).
            for (int colour = 0; colour < 16; colour++) {
                btw.modern.ItemStack w = new btw.modern.ItemStack(wool.itemID, 1, colour);
                btw.modern.ItemStack result = result(recipe, needlesStack, w, w);
                ItemStack mcWool = ItemStackHelper.toMcStack(w);
                ItemStack mcResult = ItemStackHelper.toMcStack(result);
                if (mcWool.isEmpty() || mcResult.isEmpty() || !seen.add(key(mcWool) + "->" + key(mcResult))) continue;
                out.add(shapeless(name, n++, mcResult, Ingredient.of(mcNeedles),
                        Ingredient.of(mcWool), Ingredient.of(mcWool)));
            }
        }
    }

    // ------------------------------------------------------------------
    // Probing helpers
    // ------------------------------------------------------------------

    /** Every FC item/block id as a single, damage-0 stack. */
    private static List<btw.modern.ItemStack> candidates() {
        List<btw.modern.ItemStack> out = new ArrayList<>();
        int itemLimit = Math.min(btw.modern.Item.itemsList.length, MAX_PROBE_ID);
        for (int id = 1; id < itemLimit; id++) {
            boolean isItem = btw.modern.Item.itemsList[id] != null;
            boolean isBlock = id < btw.modern.Block.blocksList.length && btw.modern.Block.blocksList[id] != null;
            if (isItem || isBlock) out.add(new btw.modern.ItemStack(id, 1, 0));
        }
        return out;
    }

    /** Candidates that complete {@code seed} into a match (seed first or second in the grid). */
    private static List<btw.modern.ItemStack> partners(btw.modern.IRecipe recipe, btw.modern.ItemStack seed,
                                                       boolean seedFirst) {
        List<btw.modern.ItemStack> out = new ArrayList<>();
        for (btw.modern.ItemStack candidate : candidates()) {
            if (candidate.itemID == seed.itemID) continue;
            boolean ok = seedFirst ? matches(recipe, seed, candidate) : matches(recipe, candidate, seed);
            if (ok) out.add(candidate);
        }
        return out;
    }

    /**
     * The subtypes of {@code id} that are distinct 1.20.1 items (wood species), as FC stacks;
     * just damage 0 when the id has no such subtypes.
     */
    private static List<btw.modern.ItemStack> distinctVariants(int id) {
        List<btw.modern.ItemStack> out = new ArrayList<>();
        List<net.minecraft.world.item.Item> seen = new ArrayList<>();
        for (int meta = 0; meta < 16; meta++) {
            btw.modern.ItemStack fc = new btw.modern.ItemStack(id, 1, meta);
            ItemStack mc = ItemStackHelper.toMcStack(fc);
            if (mc.isEmpty() || seen.contains(mc.getItem())) continue;
            seen.add(mc.getItem());
            out.add(fc);
        }
        return out;
    }

    private static btw.modern.InventoryCrafting grid(btw.modern.ItemStack... stacks) {
        btw.modern.InventoryCrafting inv = new btw.modern.InventoryCrafting(null, 3, 3);
        for (int i = 0; i < stacks.length && i < 9; i++) {
            inv.setInventorySlotContents(i, stacks[i].copy());
        }
        return inv;
    }

    private static boolean matches(btw.modern.IRecipe recipe, btw.modern.ItemStack... stacks) {
        try {
            synchronized (recipe) {
                return recipe.matches(grid(stacks), null);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static btw.modern.ItemStack result(btw.modern.IRecipe recipe, btw.modern.ItemStack... stacks) {
        try {
            synchronized (recipe) {
                return recipe.getCraftingResult(grid(stacks));
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String key(ItemStack mc) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.getItem()) + "x" + mc.getCount()
                + (mc.hasTag() ? String.valueOf(mc.getTag()) : "");
    }

    private static ShapelessRecipe shapeless(String className, int n, ItemStack result, Ingredient... ingredients) {
        ResourceLocation id = new ResourceLocation(BTWForgeMod.MOD_ID,
                "jei/" + className.toLowerCase(Locale.ROOT) + "/" + n);
        return new ShapelessRecipe(id, "", CraftingBookCategory.MISC, result,
                NonNullList.of(Ingredient.EMPTY, ingredients));
    }
}
