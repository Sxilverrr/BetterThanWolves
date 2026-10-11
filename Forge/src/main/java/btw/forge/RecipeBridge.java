package btw.forge;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Converts FC recipes (from btw.modern.CraftingManager and btw.modern.FurnaceRecipes)
 * into MC 1.20.1 recipes and injects them into the server's RecipeManager.
 *
 * <p>Called after FC initialization completes and the server's recipe manager is available,
 * and again after a datapack reload (/reload builds a fresh RecipeManager without them).
 * FC recipes are keyed {@code betterthanwolves:fc_recipe_<index>} /
 * {@code betterthanwolves:fc_smelting_<inputId>}; an existing recipe with the same id is
 * replaced.</p>
 *
 * <p>Injection goes through {@link RecipeManager#replaceRecipes}, which rebuilds BOTH of the
 * manager's indexes ({@code recipes} by type and {@code byName} by id, RecipeManager.java:37-38).
 * Writing only the by-type map left {@code byKey()} blind to FC recipes, so every login
 * dropped them from the player's recipe book ("Tried to load unrecognized recipe ...
 * removed now", ServerRecipeBook.java:97-105) and recipe-book placement did nothing
 * (triage RE6).</p>
 */
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public class RecipeBridge {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RecipeBridge");

    /** 1.5.2 "any damage" ingredient marker: CraftingManager.java:206 / ShapedRecipes.java:134. */
    private static final int FC_WILDCARD_DAMAGE = 32767;

    private static final String CRAFTING_PREFIX = "fc_recipe_";
    private static final String SMELTING_PREFIX = "fc_smelting_";

    /** The RecipeManager most recently injected, and one id injected into it (reload probe). */
    private static WeakReference<RecipeManager> lastInjected = new WeakReference<>(null);
    private static ResourceLocation injectedProbe;

    /**
     * Injects all FC recipes into the MC RecipeManager.
     * Called from BTWForgeMod.onServerStarting() after the server loads data packs.
     */
    public static synchronized void injectRecipes(MinecraftServer server) {
        try {
            RecipeManager recipeManager = server.getRecipeManager();

            // Everything currently loaded (vanilla + datapacks), minus FC entries left over from
            // an earlier injection; FC recipes are then (re)added under their stable ids.
            Map<ResourceLocation, Recipe<?>> all = new LinkedHashMap<>();
            for (Recipe<?> recipe : recipeManager.getRecipes()) {
                if (!isFcRecipeId(recipe.getId())) {
                    all.put(recipe.getId(), recipe);
                }
            }

            int[] crafting = injectCraftingRecipes(all);
            int smeltingCount = injectSmeltingRecipes(all);

            recipeManager.replaceRecipes(all.values());

            lastInjected = new WeakReference<>(recipeManager);
            injectedProbe = all.keySet().stream().filter(RecipeBridge::isFcRecipeId).findFirst().orElse(null);

            LOGGER.info("Injected {} crafting ({} code-driven) + {} smelting FC recipes into RecipeManager"
                            + " ({} FC crafting recipes skipped: unconvertible)",
                    crafting[0], crafting[1], smeltingCount, crafting[2]);
        } catch (Throwable e) {
            LOGGER.error("Failed to inject FC recipes into the RecipeManager", e);
        }
    }

    /**
     * /reload replaces the server's RecipeManager with one built from JSON only, which would
     * silently drop every FC recipe until restart. {@code OnDatapackSyncEvent} with no player
     * fires after the reload and before the recipes are sent to clients
     * (PlayerList.reloadResources); a join (player != null) needs nothing.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        try {
            MinecraftServer server = event.getPlayerList().getServer();
            RecipeManager current = server.getRecipeManager();
            boolean missing = lastInjected.get() != current
                    || (injectedProbe != null && current.byKey(injectedProbe).isEmpty());
            if (missing) {
                LOGGER.info("Recipe reload detected; re-injecting FC recipes");
                injectRecipes(server);
            }
        } catch (Throwable e) {
            LOGGER.error("Failed to re-inject FC recipes after a reload", e);
        }
    }

    private static boolean isFcRecipeId(ResourceLocation id) {
        return id != null && BTWForgeMod.MOD_ID.equals(id.getNamespace())
                && (id.getPath().startsWith(CRAFTING_PREFIX) || id.getPath().startsWith(SMELTING_PREFIX));
    }

    /**
     * Converts FC crafting recipes to MC recipes: shaped + shapeless as
     * {@link FCCraftingRecipes.Shaped}/{@link FCCraftingRecipes.Shapeless}, and FC's code-driven
     * IRecipe classes (FCRecipesLogChopping / FCRecipesKnitting / FCRecipesFishingRodBaiting,
     * added at FCRecipes.java:5108-5110) as {@link FCCraftingRecipes.Custom}.
     *
     * @return {converted, of which code-driven, skipped}
     */
    private static int[] injectCraftingRecipes(Map<ResourceLocation, Recipe<?>> all) {
        List fcRecipes = btw.modern.CraftingManager.getInstance().getRecipeList();
        int count = 0;
        int customCount = 0;
        int skipped = 0;

        // Recipes are sent to clients by serializer registry key (ClientboundUpdateRecipesPacket
        // .toNetwork); a Custom recipe without its registered serializer would break that packet.
        boolean customSerializerRegistered =
                BuiltInRegistries.RECIPE_SERIALIZER.getKey(FCCraftingRecipes.CUSTOM_SERIALIZER) != null;
        if (!customSerializerRegistered) {
            LOGGER.error("Serializer {} is not registered; FC code-driven crafting recipes are not injected",
                    FCCraftingRecipes.CUSTOM_SERIALIZER_ID);
        }

        for (int i = 0; i < fcRecipes.size(); i++) {
            Object fcRecipe = fcRecipes.get(i);
            ResourceLocation id = new ResourceLocation(BTWForgeMod.MOD_ID, CRAFTING_PREFIX + i);

            try {
                Recipe<?> mcRecipe = null;

                if (fcRecipe instanceof btw.modern.ShapedRecipes shaped) {
                    mcRecipe = convertShaped(id, shaped);
                } else if (fcRecipe instanceof btw.modern.ShapelessRecipes shapeless) {
                    mcRecipe = convertShapeless(id, shapeless);
                } else if (fcRecipe instanceof btw.modern.IRecipe custom && customSerializerRegistered) {
                    mcRecipe = FCCraftingRecipes.custom(id, i, custom);
                    customCount++;
                }

                if (mcRecipe != null) {
                    all.put(id, mcRecipe);
                    count++;
                } else {
                    skipped++;
                }
            } catch (Throwable e) {
                skipped++;
                LOGGER.debug("Failed to convert FC recipe {}: {}", i, e.toString());
            }
        }

        return new int[] { count, customCount, skipped };
    }

    /**
     * Converts FC furnace recipes to MC smelting recipes.
     */
    @SuppressWarnings("unchecked")
    private static int injectSmeltingRecipes(Map<ResourceLocation, Recipe<?>> all) {
        Map<Integer, btw.modern.ItemStack> fcSmelting =
                btw.modern.FurnaceRecipes.smelting().getSmeltingList();
        int count = 0;

        for (Map.Entry<Integer, btw.modern.ItemStack> entry : fcSmelting.entrySet()) {
            int inputId = entry.getKey();
            btw.modern.ItemStack fcOutput = entry.getValue();

            try {
                // Convert input ID to Ingredient
                Ingredient input = fcIdToIngredient(inputId);
                if (input.isEmpty()) continue;

                // Convert output
                ItemStack output = ItemStackHelper.toMcStack(fcOutput);
                if (output.isEmpty()) continue;

                float xp = btw.modern.FurnaceRecipes.smelting().getExperience(inputId);

                ResourceLocation id = new ResourceLocation(
                        BTWForgeMod.MOD_ID, SMELTING_PREFIX + inputId);

                SmeltingRecipe recipe = new SmeltingRecipe(
                        id, "", CookingBookCategory.MISC,
                        input, output, xp, 200);

                all.put(id, recipe);
                count++;
            } catch (Exception e) {
                LOGGER.debug("Failed to convert FC smelting recipe for input {}: {}",
                        inputId, e.getMessage());
            }
        }

        return count;
    }

    /**
     * Converts an FC ShapedRecipes to an MC ShapedRecipe. Returns null when the output or any
     * ingredient has no MC mapping: an EMPTY ingredient in a shaped pattern means "this cell
     * must be empty", so keeping the recipe would let it be crafted without that ingredient.
     */
    private static ShapedRecipe convertShaped(ResourceLocation id,
                                               btw.modern.ShapedRecipes fcRecipe) {
        int w = fcRecipe.recipeWidth;
        int h = fcRecipe.recipeHeight;

        // Convert output
        ItemStack output = ItemStackHelper.toMcStack(fcRecipe.getRecipeOutput());
        if (output.isEmpty()) return null;

        // Convert ingredients
        NonNullList<Ingredient> ingredients = NonNullList.withSize(w * h, Ingredient.EMPTY);
        for (int i = 0; i < fcRecipe.recipeItems.length && i < ingredients.size(); i++) {
            btw.modern.ItemStack fcStack = fcRecipe.recipeItems[i];
            if (fcStack != null) {
                Ingredient ing = fcStackToIngredient(fcStack);
                if (ing.isEmpty()) {
                    LOGGER.debug("Skipping FC recipe {}: ingredient {} has no MC mapping", id, fcStack.itemID);
                    return null;
                }
                ingredients.set(i, ing);
            }
        }

        return new FCCraftingRecipes.Shaped(id, w, h, ingredients, output, fcRecipe);
    }

    /**
     * Converts an FC ShapelessRecipes to an MC ShapelessRecipe. Returns null when the output
     * or any ingredient has no MC mapping (dropping just that ingredient would make the
     * recipe cheaper than in FC).
     */
    @SuppressWarnings("unchecked")
    private static ShapelessRecipe convertShapeless(ResourceLocation id,
                                                     btw.modern.ShapelessRecipes fcRecipe) {
        // Convert output
        ItemStack output = ItemStackHelper.toMcStack(fcRecipe.getRecipeOutput());
        if (output.isEmpty()) return null;

        // Convert ingredients
        NonNullList<Ingredient> ingredients = NonNullList.create();
        List<btw.modern.ItemStack> fcItems = fcRecipe.recipeItems;
        for (Object obj : fcItems) {
            btw.modern.ItemStack fcStack = (btw.modern.ItemStack) obj;
            if (fcStack != null) {
                Ingredient ing = fcStackToIngredient(fcStack);
                if (ing.isEmpty()) {
                    LOGGER.debug("Skipping FC recipe {}: ingredient {} has no MC mapping", id, fcStack.itemID);
                    return null;
                }
                ingredients.add(ing);
            }
        }

        if (ingredients.isEmpty()) return null;

        return new FCCraftingRecipes.Shapeless(id, output, ingredients, fcRecipe);
    }

    /**
     * Converts an FC ItemStack to an MC Ingredient (for recipe inputs).
     */
    private static Ingredient fcStackToIngredient(btw.modern.ItemStack fcStack) {
        List<ItemStack> alternatives = fcStackAlternatives(fcStack);
        if (alternatives.isEmpty()) return Ingredient.EMPTY;
        return Ingredient.of(alternatives.toArray(new ItemStack[0]));
    }

    /**
     * The MC stacks an FC recipe ingredient stands for. A plain stack converts to one stack.
     * The 1.5.2 wildcard damage (32767, "any metadata") stands for every subtype: for ids whose
     * subtypes are separate 1.20.1 items (wood species, wool colours, dyes, ...) one stack per
     * distinct item, otherwise the item at damage 0. Converting the wildcard as a literal
     * damage resolved e.g. "any planks" (FCRecipes.java:2838-2840) to jungle planks only
     * (32767 &amp; 3 == 3) and gave tools a nonsense durability. Also used by the JEI plugin.
     */
    public static List<ItemStack> fcStackAlternatives(btw.modern.ItemStack fcStack) {
        if (fcStack == null) return List.of();
        if (fcStack.getItemDamage() != FC_WILDCARD_DAMAGE) {
            ItemStack mc = ItemStackHelper.toMcStack(fcStack);
            return mc.isEmpty() ? List.of() : List.of(mc);
        }

        List<ItemStack> out = new ArrayList<>();
        ItemStack base = ItemStackHelper.toMcStack(
                new btw.modern.ItemStack(fcStack.itemID, fcStack.stackSize, 0));
        if (base.isEmpty()) return out;
        out.add(base);
        // 1.5.2 metadata subtypes span 0-15 (wool/dye colours, wood species in bits 0-1).
        for (int meta = 1; meta < 16; meta++) {
            ItemStack variant = ItemStackHelper.toMcStack(
                    new btw.modern.ItemStack(fcStack.itemID, fcStack.stackSize, meta));
            if (variant.isEmpty()) continue;
            boolean known = false;
            for (ItemStack seen : out) {
                if (seen.getItem() == variant.getItem()) {
                    known = true;
                    break;
                }
            }
            if (!known) out.add(variant);
        }
        return out;
    }

    /**
     * Converts a legacy item/block ID to an Ingredient.
     */
    private static Ingredient fcIdToIngredient(int legacyId) {
        // Try as item first
        net.minecraft.world.item.Item modernItem = ProxyRegistry.getModernItem(legacyId);
        if (modernItem != null) {
            return Ingredient.of(modernItem);
        }
        // Try as block
        net.minecraft.world.level.block.Block modernBlock = ProxyRegistry.getModernBlock(legacyId);
        if (modernBlock != null) {
            return Ingredient.of(modernBlock.asItem());
        }
        return Ingredient.EMPTY;
    }
}
