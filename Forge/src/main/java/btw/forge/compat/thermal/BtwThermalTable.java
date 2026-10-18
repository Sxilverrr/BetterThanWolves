package btw.forge.compat.thermal;

import btw.forge.BTWForgeMod;
import btw.forge.BTWLifecycle;
import btw.forge.ProxyRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BTW's climate facts in mod-neutral form (INTEGRATIONS #020, SR6): which BTW blocks give off heat and in which
 * metadata, which BTW items are clothing, textiles and hot meals, and which BTW items burn as fuel. It names BTW
 * content by {@code FCBetterThanWolves} field (resolve with {@link btw.forge.compat.FcRefs}) and carries no number of
 * any temperature mod; each temperature integration maps the tiers to its own scale.
 *
 * <p>Only BTW's own content is listed. FC content on a fixed vanilla id (the legacy torch 50, mushroom stew 282) and
 * post-1.5.2 flames by their nearest FC relative (candles, candle cakes, the lantern) are listed by vanilla key in
 * vanillaHeatSources / vanillaHotMeals; vanilla fire and the lit furnace keep the temperature mods' own entries.</p>
 *
 * <p>Heat-source metadata, from the FC sources (Server and Client agree); every default id is 175 or higher, so each
 * block is a ProxyBlock carrying {@code ProxyBlock.META} 0-15:</p>
 * <ul>
 *   <li>Unlit campfire: fuel state {@code (meta & 12) >> 2} (FCBlockCampfire.GetFuelState), smouldering = 2
 *       ({@code m_iCampfireFuelStateSmouldering}), so the embers are meta 8-11.</li>
 *   <li>Hibachi: lit while {@code meta & 4} (FCBlockBBQ.IsLit), so meta 4-7 and 12-15.</li>
 *   <li>Candle: always lit; its metadata is the colour.</li>
 *   <li>Loose cobblestone and loose stone brick: lava in the cracks while {@code meta & 1} (FCBlockLavaReceiver.GetHasLavaInCracks), so every odd meta.</li>
 *   <li>The other rows are lit in every metadata.</li>
 * </ul>
 *
 * <p>Every row is checked at startup by {@code BridgeSelfAudit} ({@code [SELF-AUDIT/thermal]}).</p>
 */
// FCMOD-INTEGRATION(1.20.1): mod-compat: one table of BTW heat, clothing, meal and fuel facts that every temperature mod integration reads
public final class BtwThermalTable {

    /** How hot a BTW heat source is, coldest first. Integrations map these to their own scales. */
    public enum HeatTier {
        EMBERS, SMALL_FIRE, MEDIUM_FIRE, LARGE_FIRE, STOKED_FIRE, HIBACHI_BODY, BRICK_OVEN, TORCH, CANDLE,
        SMOULDERING_LOG, BURNING_LADDER, LAVA_CRACKS
    }

    /** A BTW block field that gives off heat while its metadata lies in {@code metaMin..metaMax} (inclusive). */
    public record HeatSource(String fcField, int metaMin, int metaMax, HeatTier tier) {}

    public enum ClothingSet { WOOL, PADDED, TANNED, GIMP, PLATE }

    public enum Slot { HEAD, CHEST, LEGS, FEET }

    /** A BTW armour item field, its set and its slot. */
    public record Clothing(String fcField, ClothingSet set, Slot slot) {}

    public enum TextileKind { WOOL, WOOL_KNIT, PADDING, TANNED_LEATHER }

    /** A BTW cloth or leather item field. */
    public record Textile(String fcField, TextileKind kind) {}

    public enum MealTier { WARM, HOT }

    /** A BTW soup or stew item field that warms whoever eats it. */
    public record HotMeal(String fcField, MealTier tier) {}

    /** A BTW item with its registry key and FC furnace burn time (ticks, measured at damage 0). */
    public record Fuel(Item item, ResourceLocation key, int fcBurnTime) {}

    /** FC's burn time of one coal (1600 ticks), the unit fuel integrations scale BTW burn times against. */
    public static final int FC_COAL_BURN_TIME = btw.modern.FCEnumFurnaceBurnTime.COAL.m_iBurnTime;

    private static final List<HeatSource> HEAT_SOURCES = heatRows();

    private static List<HeatSource> heatRows() {
        List<HeatSource> rows = new ArrayList<>(List.of(
                new HeatSource("fcBlockCampfireUnlit", 8, 11, HeatTier.EMBERS),
                new HeatSource("fcBlockCampfireSmall", 0, 15, HeatTier.SMALL_FIRE),
                new HeatSource("fcBlockCampfireMedium", 0, 15, HeatTier.MEDIUM_FIRE),
                new HeatSource("fcBlockCampfireLarge", 0, 15, HeatTier.LARGE_FIRE),
                new HeatSource("fcBlockFireStoked", 0, 15, HeatTier.STOKED_FIRE),
                new HeatSource("fcBBQ", 4, 7, HeatTier.HIBACHI_BODY),
                new HeatSource("fcBBQ", 12, 15, HeatTier.HIBACHI_BODY),
                new HeatSource("fcBlockFurnaceBrickBurning", 0, 15, HeatTier.BRICK_OVEN),
                new HeatSource("fcBlockTorchFiniteBurning", 0, 15, HeatTier.TORCH),
                new HeatSource("fcBlockTorchNetherBurning", 0, 15, HeatTier.TORCH),
                new HeatSource("fcBlockCandle", 0, 15, HeatTier.CANDLE),
                new HeatSource("fcBlockLogSmouldering", 0, 15, HeatTier.SMOULDERING_LOG),
                new HeatSource("fcBlockLadderOnFire", 0, 15, HeatTier.BURNING_LADDER)));
        // FCMOD-INTEGRATION(1.20.1): mod-compat: FC's own warm-block rule, FCBlockLavaReceiver.GetIsBlockWarm (Server FCBlockLavaReceiver.java:99-109): loose cobblestone and loose stone brick are warm while lava sits in their cracks (metadata bit 0), so every odd meta, one row each because rows are contiguous ranges
        for (String field : List.of("fcBlockCobblestoneLoose", "fcBlockStoneBrickLoose")) {
            for (int meta = 1; meta < 16; meta += 2) rows.add(new HeatSource(field, meta, meta, HeatTier.LAVA_CRACKS));
        }
        return List.copyOf(rows);
    }

    private static final List<Clothing> CLOTHING = List.of(
            new Clothing("fcItemArmorWoolHelm", ClothingSet.WOOL, Slot.HEAD),
            new Clothing("fcItemArmorWoolChest", ClothingSet.WOOL, Slot.CHEST),
            new Clothing("fcItemArmorWoolLeggings", ClothingSet.WOOL, Slot.LEGS),
            new Clothing("fcItemArmorWoolBoots", ClothingSet.WOOL, Slot.FEET),
            new Clothing("fcItemArmorPaddedHelm", ClothingSet.PADDED, Slot.HEAD),
            new Clothing("fcItemArmorPaddedChest", ClothingSet.PADDED, Slot.CHEST),
            new Clothing("fcItemArmorPaddedLeggings", ClothingSet.PADDED, Slot.LEGS),
            new Clothing("fcItemArmorPaddedBoots", ClothingSet.PADDED, Slot.FEET),
            new Clothing("fcItemArmorTannedHelm", ClothingSet.TANNED, Slot.HEAD),
            new Clothing("fcItemArmorTannedChest", ClothingSet.TANNED, Slot.CHEST),
            new Clothing("fcItemArmorTannedLeggings", ClothingSet.TANNED, Slot.LEGS),
            new Clothing("fcItemArmorTannedBoots", ClothingSet.TANNED, Slot.FEET),
            new Clothing("fcItemArmorGimpHelm", ClothingSet.GIMP, Slot.HEAD),
            new Clothing("fcItemArmorGimpChest", ClothingSet.GIMP, Slot.CHEST),
            new Clothing("fcItemArmorGimpLeggings", ClothingSet.GIMP, Slot.LEGS),
            new Clothing("fcItemArmorGimpBoots", ClothingSet.GIMP, Slot.FEET),
            new Clothing("fcItemPlateHelm", ClothingSet.PLATE, Slot.HEAD),
            new Clothing("fcItemPlateBreastPlate", ClothingSet.PLATE, Slot.CHEST),
            new Clothing("fcItemPlateLeggings", ClothingSet.PLATE, Slot.LEGS),
            new Clothing("fcItemPlateBoots", ClothingSet.PLATE, Slot.FEET));

    private static final List<Textile> TEXTILES = List.of(
            new Textile("fcItemWool", TextileKind.WOOL),
            new Textile("fcItemWoolKnit", TextileKind.WOOL_KNIT),
            new Textile("fcItemPadding", TextileKind.PADDING),
            new Textile("fcItemTannedLeather", TextileKind.TANNED_LEATHER));

    private static final List<HotMeal> HOT_MEALS = List.of(
            new HotMeal("fcItemChickenSoup", MealTier.WARM),
            new HotMeal("fcItemFishSoup", MealTier.WARM),
            new HotMeal("fcItemHeartyStew", MealTier.HOT));

    /** A 1.5.2 or post-1.5.2 vanilla-id block that gives off heat by BTW's lore. Vanilla ids never move with the BTW ID config, so the selector (a block id or a #tag) is written directly; requiresLit means only states with lit=true count. Each temperature integration skips selectors its mod already ships data for. */
    public record VanillaHeatSource(String selector, boolean requiresLit, HeatTier tier, String fcIdentity) {}

    /** A bowl food FC cooks in the cauldron that 4.B keeps on its fixed vanilla id. */
    public record VanillaHotMeal(ResourceLocation item, MealTier tier, String fcIdentity) {}

    // FCMOD-INTEGRATION(1.20.1): mod-compat: FC content on a fixed vanilla id, and post-1.5.2 flames by their nearest FC relative (maintainer ruling), warm the player like BTW's own
    private static final List<VanillaHeatSource> VANILLA_HEAT_SOURCES = List.of(
            new VanillaHeatSource("minecraft:torch", false, HeatTier.TORCH, "legacy torch, FC id 50: FCBlockTorchLegacy extends FCBlockTorchBaseBurning like the TORCH rows"),
            new VanillaHeatSource("minecraft:wall_torch", false, HeatTier.TORCH, "legacy torch, FC id 50 (wall form)"),
            new VanillaHeatSource("#minecraft:candles", true, HeatTier.CANDLE, "post-1.5.2 candle: nearest FC relative fcBlockCandle"),
            new VanillaHeatSource("#minecraft:candle_cakes", true, HeatTier.CANDLE, "post-1.5.2 candle cake: its lit candle"),
            new VanillaHeatSource("minecraft:lantern", false, HeatTier.TORCH, "post-1.5.2 lantern: nearest FC relative the burning torch"));

    private static final List<VanillaHotMeal> VANILLA_HOT_MEALS = List.of(
            new VanillaHotMeal(new ResourceLocation("minecraft", "mushroom_stew"), MealTier.WARM, "Item.bowlSoup: FC removes its crafting recipe and cooks it in the cauldron from milk and 3 brown mushrooms (FCRecipes.java:3664-3673, :5167)"));

    private static final List<String> ALL_FIELDS = collectFields();

    private BtwThermalTable() {}

    /** The 29 heat-source rows (14 blocks; the hibachi has two metadata ranges, each lava-receiving block one row per odd meta). */
    public static List<HeatSource> heatSources() {
        return HEAT_SOURCES;
    }

    /** The 20 clothing rows: wool, padded, tanned, gimp and plate, each HEAD, CHEST, LEGS, FEET. */
    public static List<Clothing> clothing() {
        return CLOTHING;
    }

    /** The 4 textile rows. */
    public static List<Textile> textiles() {
        return TEXTILES;
    }

    /** The 3 hot-meal rows. */
    public static List<HotMeal> hotMeals() {
        return HOT_MEALS;
    }

    /** The vanilla-key heat rows (5). Soul torches and soul lanterns are not listed: they keep each mod's soul-fire meaning. */
    public static List<VanillaHeatSource> vanillaHeatSources() { return VANILLA_HEAT_SOURCES; }

    /** The vanilla-key hot meals (1). */
    public static List<VanillaHotMeal> vanillaHotMeals() { return VANILLA_HOT_MEALS; }

    /**
     * Every BTW-namespace item FC burns as fuel, computed live (so it follows the BTW ID config): each FC item in
     * {@code btw.modern.Item.itemsList} whose registered 1.20.1 item has the {@code betterthanwolves} namespace and
     * whose {@code GetFurnaceBurnTime(0)} is positive, in id order. Block items delegate to the block's burn time.
     * Twins and stand-ins resolve outside the namespace and drop out. Empty before FC init.
     *
     * <p>Burn times are measured at damage 0, the item-level granularity the temperature mods' fuel data uses.</p>
     */
    public static List<Fuel> fuels() {
        if (!BTWLifecycle.isFcReady()) return List.of();
        btw.modern.Item[] list = btw.modern.Item.itemsList;
        List<Fuel> out = new ArrayList<>();
        for (int id = 1; id < list.length; id++) {
            btw.modern.Item fc = list[id];
            if (fc == null) continue;
            Item modern = ProxyRegistry.getModernItem(id);
            if (modern == null || modern == Items.AIR) continue;
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(modern);
            if (key == null || !BTWForgeMod.MOD_ID.equals(key.getNamespace())) continue;
            int burn;
            try {
                burn = fc.GetFurnaceBurnTime(0);
            } catch (Throwable t) {
                continue;   // an FC item that cannot answer at damage 0 is not listed
            }
            if (burn > 0) out.add(new Fuel(modern, key, burn));
        }
        return List.copyOf(out);
    }

    /** The distinct FC fields of every row except fuels, in table order (41 names). */
    public static List<String> allFields() {
        return ALL_FIELDS;
    }

    private static List<String> collectFields() {
        Set<String> fields = new LinkedHashSet<>();
        for (HeatSource h : HEAT_SOURCES) fields.add(h.fcField());
        for (Clothing c : CLOTHING) fields.add(c.fcField());
        for (Textile t : TEXTILES) fields.add(t.fcField());
        for (HotMeal m : HOT_MEALS) fields.add(m.fcField());
        return List.copyOf(fields);
    }
}
