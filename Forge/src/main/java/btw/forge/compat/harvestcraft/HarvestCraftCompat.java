package btw.forge.compat.harvestcraft;

import btw.forge.TwinRegistry;
import btw.forge.compat.BTWCompat;
import btw.forge.compat.FcRefs;
import btw.forge.farming.DungSpreading;
import btw.forge.farming.FcSoil;
import btw.forge.food.FcFoodValues;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITagManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;

/**
 * Pam's HarvestCraft 2 (INTEGRATIONS #032): the in-game notice on HarvestCraft items BTW governs, and one start-up
 * summary line. Everything else HarvestCraft gets from BTW is data and general rules, inert without the mod:
 * <ul>
 *   <li>food values: the {@code btw_foods} tiers and entries HarvestCraftDataProvider writes (BtwFoodTable), so HC
 *       meals feed at FC scale, raw ground meat poisons like FC raw meat and seeds, grains and vegetables are FC
 *       fodder;</li>
 *   <li>recipes: the {@code btw_recipe_replacements} reroutes (bread to BTW dough, the cattail torch to a BTW finite
 *       torch, HC flour from BTW flour, HC dough at FC's flour ratio, string_fiber removed) and the
 *       {@code btw_machine_recipes} millstone rules for {@code #forge:flour_plants} and {@code #forge:fiber};</li>
 *   <li>plants: crops and saplings already follow BTW's soil, growth and sapling rules (FcSoil, ModdedPlantRules,
 *       TwinRegistry); gardens join through {@code #betterthanwolves:fc_soil_plants} (4.B bone meal) and
 *       {@code #betterthanwolves:dung_spreads} ({@link DungSpreading}); tree fruit through
 *       {@code #betterthanwolves:paced_like_sweet_berries} (FC cocoa's pace, TwinRegistry).</li>
 * </ul>
 *
 * <p>SR1: no HarvestCraft class is named anywhere (Pam's licence; settled decision #18), only registry ids, tags and
 * JSON. This class is reached only through BTWCompat's guarded {@code invokestatic}; {@link #init} only registers two
 * Forge-bus listeners and never touches FcRefs or a registry. Each handler catches {@code RuntimeException} and
 * {@code LinkageError}, logs one WARN and switches itself off; BTW and HarvestCraft keep working without it.</p>
 */
// FCMOD-INTEGRATION(1.20.1): harvestcraft: tells the player which HarvestCraft items follow BTW's rules and reports what the integration data changed
public final class HarvestCraftCompat {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Compat");

    private static final String NOTICE_KEY = "btw.compat.harvestcraft.notice";

    private static final String FOOD_CORE = "pamhc2foodcore";
    private static final String CROPS = "pamhc2crops";

    /** HarvestCraft flour (Food Core): its recipe is rerouted to BTW flour, so the notice names it. */
    private static final ResourceLocation HC_FLOUR = new ResourceLocation(FOOD_CORE, "flouritem");

    private static final TagKey<Item> FLOUR_PLANTS = ItemTags.create(new ResourceLocation("forge", "flour_plants"));
    private static final TagKey<Item> FIBER = ItemTags.create(new ResourceLocation("forge", "fiber"));
    private static final TagKey<Item> SEEDS = ItemTags.create(new ResourceLocation("forge", "seeds"));

    private static volatile boolean tooltipOff;
    private static volatile boolean summaryOff;

    private HarvestCraftCompat() {}

    /**
     * Called once, only from BTWCompat's guarded branch, during mod construction. Registers the tooltip notice (every
     * dist: ItemTooltipEvent is a common Forge class, so a server-side proof can ask for it) and the start-up summary.
     */
    public static void init(IEventBus modBus) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, false, ItemTooltipEvent.class,
                HarvestCraftCompat::onTooltip);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartedEvent.class,
                HarvestCraftCompat::onServerStarted);
    }

    // ================================================================
    // Notice (ItemTooltipEvent)
    // ================================================================

    private static void onTooltip(ItemTooltipEvent event) {
        if (tooltipOff) return;
        try {
            ItemStack stack = event.getItemStack();
            if (stack.isEmpty()) return;
            Item item = stack.getItem();
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || !BTWCompat.HARVESTCRAFT.contains(id.getNamespace())) return;
            if (isGoverned(stack, item, id)) {
                event.getToolTip().add(Component.translatable(NOTICE_KEY).withStyle(ChatFormatting.DARK_GRAY));
            }
        } catch (RuntimeException | LinkageError e) {
            tooltipOff = true;
            LOGGER.warn("[BTW-Compat] HarvestCraft #032: the tooltip notice failed ({}); it is switched off, the rest "
                    + "of the integration stays", e.toString(), e);
        }
    }

    /**
     * True for a HarvestCraft item BTW's rules govern: a food (FC value and stack cap), the item of a crop, sapling,
     * garden or other tagged plant block, a millstone input or FC fodder seed, or HC flour (made from BTW flour).
     * Kitchen tools and other ingredients are not.
     */
    private static boolean isGoverned(ItemStack stack, Item item, ResourceLocation id) {
        if (FcFoodValues.isFood(item)) return true;
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block instanceof CropBlock || block instanceof SaplingBlock) return true;
            BlockState state = block.defaultBlockState();
            if (state.is(DungSpreading.DUNG_SPREADS) || state.is(FcSoil.FC_SOIL_PLANTS)) return true;
        }
        if (stack.is(FLOUR_PLANTS) || stack.is(FIBER) || stack.is(SEEDS)) return true;
        return HC_FLOUR.equals(id);
    }

    // ================================================================
    // Start-up summary (ServerStartedEvent, after RecipeBridge.injectRecipes at ServerStarting)
    // ================================================================

    private static void onServerStarted(ServerStartedEvent event) {
        if (summaryOff) return;
        try {
            MinecraftServer server = event.getServer();
            int foods = 0;
            int table = 0;
            int converted = 0;
            for (Map.Entry<ResourceKey<Item>, Item> e : ForgeRegistries.ITEMS.getEntries()) {
                if (!isHarvestCraft(e.getKey().location())) continue;
                FcFoodValues.FcFood food = FcFoodValues.of(new ItemStack(e.getValue()), null);
                if (food == null) continue;
                foods++;
                if (food.source() == FcFoodValues.Source.TABLE) table++;
                else if (food.source() == FcFoodValues.Source.MODERN_CONVERTED) converted++;
            }
            int crops = 0;
            int saplings = 0;
            for (Map.Entry<ResourceKey<Block>, Block> e : ForgeRegistries.BLOCKS.getEntries()) {
                if (!isHarvestCraft(e.getKey().location())) continue;
                Block block = e.getValue();
                if (FcSoil.governedKind(block.defaultBlockState()) == FcSoil.Kind.CROP) crops++;
                if (TwinRegistry.isFallbackSapling(block)) saplings++;
            }
            // The two tags are general rules: every paced or dung-spread member counts, HarvestCraft's or not
            int fruit = 0;
            int gardens = 0;
            ITagManager<Block> tags = ForgeRegistries.BLOCKS.tags();
            if (tags != null) {
                for (Block block : tags.getTag(TwinRegistry.PACED_LIKE_SWEET_BERRIES)) {
                    if (TwinRegistry.paceFor(block.defaultBlockState()) != null) fruit++;
                }
                for (Block block : tags.getTag(DungSpreading.DUNG_SPREADS)) {
                    if (block instanceof BonemealableBlock) gardens++;
                }
            }
            RecipeManager recipes = server.getRecipeManager();
            RegistryAccess access = server.registryAccess();
            LOGGER.info("[BTW-Compat] HarvestCraft #032: {} foods at BTW values ({} table, {} converted), {} crops on "
                    + "the BTW soil rule, {} saplings on the FC sapling rule, {} fruit at the sweet-berry pace, {} "
                    + "gardens spread by dung; reroutes bread_grain={} bread_dough={} torch_cattail={} "
                    + "flour_x1_flour_plants={} string_fiber={}",
                    foods, table, converted, crops, saplings, fruit, gardens,
                    byResult(recipes, access, CROPS, "bread_grain", "fcItemBreadDough"),
                    byResult(recipes, access, FOOD_CORE, "bread_dough", "fcItemBreadDough"),
                    byResult(recipes, access, CROPS, "torch_cattail", "fcBlockTorchFiniteUnlit"),
                    byIngredient(recipes, FOOD_CORE, "flour_x1_flour_plants", "fcItemFlour"),
                    byResult(recipes, access, CROPS, "string_fiber", null));
        } catch (RuntimeException | LinkageError e) {
            summaryOff = true;
            LOGGER.warn("[BTW-Compat] HarvestCraft #032: the start-up summary failed ({}); it is switched off, the "
                    + "rest of the integration stays", e.toString(), e);
        }
    }

    private static boolean isHarvestCraft(ResourceLocation id) {
        return id != null && BTWCompat.HARVESTCRAFT.contains(id.getNamespace());
    }

    /**
     * The state of a rerouted recipe judged by its result: {@code replaced} when it makes the FC object of
     * {@code fcField}, {@code removed} (its module is loaded) or {@code absent} (it is not) when the id is gone,
     * otherwise {@code original}. A null {@code fcField} names a recipe that is meant to be removed.
     */
    private static String byResult(RecipeManager recipes, RegistryAccess access, String module, String path,
                                   @Nullable String fcField) {
        Optional<? extends Recipe<?>> recipe = recipes.byKey(new ResourceLocation(module, path));
        if (recipe.isEmpty()) return BTWCompat.isLoaded(module) ? "removed" : "absent";
        ResourceLocation wanted = fcField != null ? FcRefs.itemKey(fcField) : null;
        if (wanted == null) return "original";
        ItemStack result = recipe.get().getResultItem(access);
        return !result.isEmpty() && wanted.equals(ForgeRegistries.ITEMS.getKey(result.getItem())) ? "replaced"
                : "original";
    }

    /**
     * The state of a rerouted recipe whose result is unchanged (HC flour): {@code replaced} when one of its ingredients
     * takes the FC object of {@code fcField}, otherwise as {@link #byResult}.
     */
    private static String byIngredient(RecipeManager recipes, String module, String path, String fcField) {
        Optional<? extends Recipe<?>> recipe = recipes.byKey(new ResourceLocation(module, path));
        if (recipe.isEmpty()) return BTWCompat.isLoaded(module) ? "removed" : "absent";
        Item fc = FcRefs.item(fcField);
        if (fc == null) return "original";
        ItemStack probe = new ItemStack(fc);
        for (Ingredient ingredient : recipe.get().getIngredients()) {
            if (!ingredient.isEmpty() && ingredient.test(probe)) return "replaced";
        }
        return "original";
    }
}
