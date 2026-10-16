package btw.forge.recipes;

import btw.forge.ItemStackHelper;
import btw.forge.PlayerBridge;
import btw.forge.RecipeBridge;
import btw.forge.WorldBridge;
import btw.forge.removed.RemovedContent;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.ForgeEventFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The FC crafting grids' fallback to the 1.20.1 RecipeManager (INTEGRATIONS #025, maintainer decision D-B (a)).
 *
 * <p><b>Feature:</b> every {@code minecraft:*} and modded CRAFTING recipe (lantern, chain, barrels, banners, any mod's
 * workstation), and the 1.5.2 vanilla recipes 4.B kept (gold, diamond, emerald and lapis blocks, glass panes,
 * bottles, flower pots).</p>
 *
 * <p><b>Conflict with FC:</b> the FC workbench, work stump and soulforge are the only 3x3/4x4 grids. They ask FC's
 * own list (Modern-Common CraftingManager, FC recipes only) and, in the soulforge, FC's anvil list; neither ever saw
 * the RecipeManager, so 499 of 822 vanilla crafting recipes (every one that needs 3x3) and every modded 3x3 recipe
 * could not be crafted anywhere.</p>
 *
 * <p><b>Integration pattern:</b> B (route the modern feature through BTW's own station). FC always wins: FC's list
 * first, then the soulforge's anvil list, then this fallback. 4.B's removals already reached the RecipeManager
 * (btw.forge.FcRecipeRemovals), so the fallback offers every recipe except FC's own injected copies
 * ({@code betterthanwolves:fc_*}, which FC's list already answered with FC's hooks).</p>
 *
 * <p><b>Rebalance:</b> none of its own. Where 1.20.1 and 1.5.2 recipes differ, 1.20.1's applies (the golden apple
 * costs 8 ingots; the enchanted golden apple has no 1.20.1 recipe). A recipe's remainders are its own (a honey bottle
 * leaves a glass bottle), as in the 2x2 grid.</p>
 *
 * <p><b>Alternative considered and rejected:</b> only namespaces other than {@code minecraft} and
 * {@code betterthanwolves} (the earlier SR13(b) filter). Rejected by D-B: it existed only while 4.B's removals were
 * missing from the RecipeManager, and it kept every vanilla 3x3 recipe uncraftable, including the ones 4.B kept.</p>
 *
 * <p>Server only: the client's FC containers mirror the server's slots (FCContainerMenu.ClientCraftResultSlot), so the
 * world must be a {@link WorldBridge} with a server level. Candidates are {@code getAllRecipesFor(CRAFTING)} minus FC's
 * copies, sorted by id (RecipeManager.getRecipeFor is findFirst over a HashMap, so not deterministic), cached per
 * RecipeManager and recipe generation ({@link #onRecipesReplaced}).</p>
 *
 * <p>An FC cell holds only the 1.5.2 (id, damage), so items 1.20.1 split from one 1.5.2 subtype read back as the
 * canonical pick (a black dye as the ink sac, a stone slab as the smooth stone slab). When no recipe takes the
 * canonical grid, the grid is tried once more with LegacyVariants' modern spellings of the same subtypes
 * ({@link RecipeBridge#legacyAlias}); the craft remembers which grid matched.</p>
 */
// FCMOD-INTEGRATION(1.20.1): recipes: FC grids fall back to every non-FC RecipeManager crafting recipe, FC first
public final class WorkbenchFallback implements btw.modern.CraftingManager.ExternalResolver {

    public static final WorkbenchFallback INSTANCE = new WorkbenchFallback();

    private static final Logger LOGGER = LogManager.getLogger("BTW-RecipeBridge");

    /** FCContainerSoulforge.m_iCraftingGridWidth: the soulforge is the only 4-wide FC grid. */
    private static final int SOULFORGE_GRID_WIDTH = 4;

    /**
     * TransientCraftingContainer.setItem/removeItem call menu.slotsChanged (TransientCraftingContainer.java:49-61), so
     * the copy of the FC grid needs a menu; this one has no slots and ignores every change.
     */
    private static final AbstractContainerMenu NO_OP_MENU = new AbstractContainerMenu(null, -1) {
        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return false;
        }

        @Override
        public void slotsChanged(net.minecraft.world.Container container) {
        }
    };

    /** Recipe ids whose matches/assemble/remainders threw (one WARN each per JVM). */
    private static final Set<ResourceLocation> FAILED_LOGGED = ConcurrentHashMap.newKeySet();

    private record Candidates(RecipeManager manager, long generation, List<CraftingRecipe> recipes) {}

    private static volatile long generation;
    private static volatile Candidates candidates;

    private WorkbenchFallback() {}

    /** The recipe map changed (injection, /reload, server stop): the next lookup rebuilds the candidate list. */
    public static void onRecipesReplaced() {
        generation++;
        candidates = null;
    }

    @Override
    public btw.modern.CraftingManager.ExternalCraft resolve(btw.modern.InventoryCrafting inv, btw.modern.World world) {
        if (inv == null || !(world instanceof WorldBridge wb)) return null;
        ServerLevel level = wb.getServerLevel();
        if (level == null) return null;

        NonNullList<ItemStack> items = toMcItems(inv);
        if (items == null) return null;
        int w = inv.getInventoryWidth();
        TransientCraftingContainer grid = container(items, w);
        if (grid.isEmpty()) return null;

        // The soulforge asks FC's CraftingManager first and its anvil list second (FCContainerSoulforge
        // .onCraftMatrixChanged, javap offsets 11 and 30); this fallback runs inside the first call, so it steps
        // aside whenever the anvil list has an answer. Only the soulforge's 4x4 grid reaches the anvil list.
        if (w == SOULFORGE_GRID_WIDTH && FcCraftingManagers.anvilResult(inv, world) != null) {
            return null;
        }

        List<CraftingRecipe> recipes = candidates(level.getRecipeManager());
        Match match = firstMatch(recipes, grid, level);
        boolean aliased = false;
        if (match == null) {
            // FCMOD-INTEGRATION(1.20.1): recipes: an FC cell holds only the 1.5.2 subtype, so a black dye placed in
            // the grid reads back as the ink sac (351:0) and a stone slab as the smooth stone slab (44:0). When no
            // recipe takes the canonical items, try once more with LegacyVariants' modern spellings of the same
            // subtypes (RecipeBridge.legacyAlias): candles, stained glass, concrete powder, wool dyed with an ink
            // sac (1.5.2 RecipesDyes), the grindstone, any #forge:dyes recipe.
            NonNullList<ItemStack> aliasItems = withAliases(items);
            if (aliasItems != null) {
                match = firstMatch(recipes, container(aliasItems, w), level);
                aliased = match != null;
            }
        }
        if (match == null) return null;

        ItemStack result = match.result();
        if (result == null || result.isEmpty() || RemovedContent.isRemovedRecipeResult(result.getItem())) {
            return null;
        }
        btw.modern.ItemStack fcResult = ItemStackHelper.toFcStack(result);
        if (fcResult == null) return null;
        return new Craft(match.recipe(), level, fcResult, aliased);
    }

    /** A matching recipe and its assembled result. */
    private record Match(CraftingRecipe recipe, ItemStack result) {}

    /** The first candidate (by id) that matches {@code grid}, or null; a recipe that throws is skipped. */
    private static Match firstMatch(List<CraftingRecipe> recipes, TransientCraftingContainer grid, ServerLevel level) {
        for (CraftingRecipe recipe : recipes) {
            try {
                if (!recipe.matches(grid, level)) continue;
                return new Match(recipe, recipe.assemble(grid, level.registryAccess()));
            } catch (Throwable t) {
                logRecipeFailure(recipe, "match", t);
            }
        }
        return null;
    }

    /**
     * FC's grid as 1.20.1 stacks (slot index = x + y * width), or null when the shape is unknown or a cell has no
     * 1.20.1 form (dropping it would let a smaller recipe match).
     */
    private static NonNullList<ItemStack> toMcItems(btw.modern.InventoryCrafting inv) {
        int size = inv.getSizeInventory();
        int w = inv.getInventoryWidth();
        if (w <= 0 || size <= 0 || size % w != 0) return null;
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < size; i++) {
            btw.modern.ItemStack fc = inv.getStackInSlot(i);
            if (fc == null) continue;
            ItemStack mc = ItemStackHelper.toMcStack(fc);
            if (mc.isEmpty()) return null;
            items.set(i, mc);
        }
        return items;
    }

    /**
     * {@code items} with every cell that has a legacy alias ({@link RecipeBridge#legacyAlias}) swapped to it, count and
     * tag kept; null when no cell has one (nothing new to try).
     */
    private static NonNullList<ItemStack> withAliases(NonNullList<ItemStack> items) {
        NonNullList<ItemStack> out = null;
        for (int i = 0; i < items.size(); i++) {
            ItemStack mc = items.get(i);
            if (mc.isEmpty()) continue;
            Item alias = RecipeBridge.legacyAlias(mc.getItem());
            if (alias == null) continue;
            if (out == null) {
                out = NonNullList.withSize(items.size(), ItemStack.EMPTY);
                for (int j = 0; j < items.size(); j++) out.set(j, items.get(j));
            }
            ItemStack swapped = new ItemStack(alias, mc.getCount());
            if (mc.getTag() != null) swapped.setTag(mc.getTag().copy());
            out.set(i, swapped);
        }
        return out;
    }

    private static TransientCraftingContainer container(NonNullList<ItemStack> items, int w) {
        return new TransientCraftingContainer(NO_OP_MENU, w, items.size() / w, items);
    }

    /** The grid as the craft matched it: canonical items, or with the legacy aliases when {@code aliased}. */
    private static TransientCraftingContainer toContainer(btw.modern.InventoryCrafting inv, boolean aliased) {
        NonNullList<ItemStack> items = toMcItems(inv);
        if (items == null) return null;
        if (aliased) {
            NonNullList<ItemStack> aliasItems = withAliases(items);
            if (aliasItems != null) items = aliasItems;
        }
        return container(items, inv.getInventoryWidth());
    }

    private static List<CraftingRecipe> candidates(RecipeManager manager) {
        Candidates cached = candidates;
        long gen = generation;
        if (cached != null && cached.manager() == manager && cached.generation() == gen) return cached.recipes();
        List<CraftingRecipe> list = new ArrayList<>();
        for (CraftingRecipe r : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (r != null && r.getId() != null && !RecipeBridge.isFcRecipeId(r.getId())) list.add(r);
        }
        list.sort(Comparator.comparing(r -> r.getId().toString()));
        List<CraftingRecipe> frozen = List.copyOf(list);
        candidates = new Candidates(manager, gen, frozen);
        return frozen;
    }

    private static void logRecipeFailure(CraftingRecipe recipe, String what, Throwable t) {
        ResourceLocation id = recipe.getId();
        if (id != null && FAILED_LOGGED.add(id)) {
            LOGGER.warn("Recipe {} failed ({}) at an FC crafting grid; skipped (logged once)", id, what,
                    t);
        }
    }

    private static ServerPlayer serverPlayer(btw.modern.EntityPlayer player) {
        return player instanceof PlayerBridge pb ? pb.getServerPlayer() : null;
    }

    /**
     * One match: the recipe, the level it matched in, FC's form of its result, and whether it matched the grid with
     * the legacy aliases (the remainders and crafted hooks then see that same grid).
     */
    private static final class Craft implements btw.modern.CraftingManager.ExternalCraft {
        private final CraftingRecipe recipe;
        private final ServerLevel level;
        private final btw.modern.ItemStack fcResult;
        private final boolean aliased;

        Craft(CraftingRecipe recipe, ServerLevel level, btw.modern.ItemStack fcResult, boolean aliased) {
            this.recipe = recipe;
            this.level = level;
            this.fcResult = fcResult;
            this.aliased = aliased;
        }

        @Override
        public btw.modern.ItemStack result() {
            return fcResult;
        }

        /** 1.20.1 ResultSlot.onTake (:54-80): the recipe's remainders with the taking player set for Forge hooks. */
        @Override
        public btw.modern.ItemStack[] remainders(btw.modern.InventoryCrafting inv, btw.modern.EntityPlayer player) {
            btw.modern.ItemStack[] out = new btw.modern.ItemStack[inv.getSizeInventory()];
            TransientCraftingContainer grid = toContainer(inv, aliased);
            if (grid == null) return out;
            NonNullList<ItemStack> remaining;
            Player previous = ForgeHooks.getCraftingPlayer();
            ForgeHooks.setCraftingPlayer(serverPlayer(player));
            try {
                if (!recipe.matches(grid, level)) return out;
                remaining = recipe.getRemainingItems(grid);
            } catch (Throwable t) {
                logRecipeFailure(recipe, "remainders", t);
                return out;
            } finally {
                ForgeHooks.setCraftingPlayer(previous);
            }
            for (int i = 0; i < out.length && i < remaining.size(); i++) {
                ItemStack rem = remaining.get(i);
                if (rem != null && !rem.isEmpty()) out[i] = ItemStackHelper.toFcStack(rem);
            }
            return out;
        }

        /**
         * 1.20.1 ResultSlot.checkTakeAchievements (:39-50) with ResultContainer.awardUsedRecipes: onCraftedBy, the
         * Forge ItemCraftedEvent (with the grid before consumption), the recipe_crafted trigger and, for a non-special
         * recipe, the recipe book. FC's own onCreated already ran through SlotCrafting.onCrafting; on the server it is
         * a no-op under FC's isRemote guard (ProxyItem.runFcOnCreated), so nothing runs twice.
         */
        @Override
        public void onTaken(btw.modern.InventoryCrafting inv, btw.modern.EntityPlayer player,
                            btw.modern.ItemStack taken) {
            ServerPlayer sp = serverPlayer(player);
            if (sp == null) return;
            ItemStack mc = taken != null && taken.stackSize > 0 ? ItemStackHelper.toMcStack(taken) : ItemStack.EMPTY;
            if (mc.isEmpty()) mc = ItemStackHelper.toMcStack(fcResult);
            TransientCraftingContainer grid = toContainer(inv, aliased);
            if (grid == null) grid = new TransientCraftingContainer(NO_OP_MENU, 1, 1);
            try {
                if (!mc.isEmpty()) {
                    mc.onCraftedBy(level, sp, mc.getCount());
                    ForgeEventFactory.firePlayerCraftingEvent(sp, mc, grid);
                }
                sp.triggerRecipeCrafted(recipe, grid.getItems());
                if (!recipe.isSpecial()) sp.awardRecipes(List.of(recipe));
            } catch (Throwable t) {
                logRecipeFailure(recipe, "crafted hooks", t);
            }
        }
    }
}
