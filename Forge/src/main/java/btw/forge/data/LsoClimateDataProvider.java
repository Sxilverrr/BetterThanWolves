package btw.forge.data;

import btw.forge.BTWForgeMod;
import btw.forge.BTWLifecycle;
import btw.forge.ProxyRegistry;
import btw.forge.compat.FcRefs;
import btw.forge.compat.thermal.BtwThermalTable;
import btw.forge.compat.thermal.BtwThermalTable.Clothing;
import btw.forge.compat.thermal.BtwThermalTable.ClothingSet;
import btw.forge.compat.thermal.BtwThermalTable.Fuel;
import btw.forge.compat.thermal.BtwThermalTable.HeatSource;
import btw.forge.compat.thermal.BtwThermalTable.HeatTier;
import btw.forge.compat.thermal.BtwThermalTable.HotMeal;
import btw.forge.compat.thermal.BtwThermalTable.MealTier;
import btw.forge.compat.thermal.BtwThermalTable.Slot;
import btw.forge.compat.thermal.BtwThermalTable.VanillaHeatSource;
import btw.forge.compat.thermal.BtwThermalTable.VanillaHotMeal;
import btw.forge.food.FcFoodValues;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * BTW's climate facts as Legendary Survival Overhaul data (INTEGRATIONS #031): heat blocks, held torches, clothing,
 * limb armour, hot meals, hydration and heater fuels, all read from {@link BtwThermalTable} and written as plain JSON
 * under {@code data/<key namespace>/legendarysurvivaloverhaul/<folder>/<key path>.json}. It names no LSO class.
 * Without LSO the files are inert: LSO's data listeners are their only readers.
 *
 * <p>{@link #plan()} is shared: runData writes it with the default BTW ids, and {@code LsoCompat} re-runs it at server
 * start with this server's live keys to check that LSO loaded every planned file. A moved BTW id therefore shows up
 * as a WARN instead of silently stranding data.</p>
 *
 * <p>Values (decision #14, anchored on LSO's own vanilla files):</p>
 * <ul>
 *   <li>Heat: {@code HEAT_TEMPERATURE}; it must map every {@link HeatTier}, so a tier added to the shared table fails
 *       runData until it is given a value here.</li>
 *   <li>Vanilla-key heat rows (post-1.5.2 candles, candle cakes and the lantern by their nearest FC relative) are
 *       written only where LSO ships no file of its own; {@code minecraft:torch}, {@code minecraft:wall_torch} and
 *       the mushroom-stew warmth keep LSO's values ({@code lsoShips}).</li>
 *   <li>Held burning torches warm like LSO's torch item (1.0).</li>
 *   <li>Clothing: wool cold 1.5/2.0/1.5/1.0; padded, tanned and gimp cold 1.0/1.5/1.0/0.5; plate heat 0.5/1.5/1.0/1.0
 *       (head, chest, legs, feet). Limbs: padded 0.05, plate 0.15 on the slot's limb.</li>
 *   <li>Meals: WARM is FOOD and DRINK level 1 for 1200 ticks and hydration 4; HOT is FOOD level 2 for 1800 ticks and
 *       hydration 3. Every BTW-namespace drinkable (FcFoodValues source FC_DRINKABLE; chocolate milk at default ids)
 *       hydrates like LSO's milk, 5.</li>
 *   <li>Fuel: {@code max(1, round(600 x FC burn time / 1600))} heater ticks, so one FC coal equals LSO's coal (600).</li>
 * </ul>
 *
 * <p>The manifest {@code data/betterthanwolves/compat_refs/lso.json} lists every FC field the files are keyed by, for
 * {@code BridgeSelfAudit}'s compat-refs audit. Fuels and drinkables have no field name; only the runtime audit covers
 * them.</p>
 */
// FCMOD-INTEGRATION(1.20.1): lso: BTW heat, clothing, limb, meal, hydration and fuel facts as LSO data, keyed through FcRefs so the runtime audit can follow a moved BTW id
public final class LsoClimateDataProvider implements DataProvider {

    /** LSO data folders, under {@code data/<ns>/legendarysurvivaloverhaul/}. */
    public static final String BLOCKS = "temperature/blocks";
    public static final String ITEMS = "temperature/items";
    public static final String CONSUMABLES = "temperature/consumables";
    public static final String FUELS = "temperature/fuel_items";
    public static final String THIRST = "thirst/consumables";
    public static final String LIMBS = "body_damage/items";

    /**
     * One planned LSO data file: its folder (one of the constants above), the registry key it is keyed by, its JSON
     * body, and where it comes from (the FC field, the vanilla selector, the vanilla item path, {@code "drinkable"} or
     * {@code "fuel"}), for log lines.
     */
    public record LsoFile(String folder, ResourceLocation key, JsonElement json, String source) {}

    /** LSO temperature per BTW heat tier (LSO's scale: torch 1.5, campfire 10, magma block 12). Covers every tier. */
    private static final Map<HeatTier, Float> HEAT_TEMPERATURE = heatTemperatures();

    /** Held burning torch: LSO's own torch item value. */
    private static final float HELD_TORCH_TEMPERATURE = 1.0F;

    /** BTW drinkables hydrate like LSO's milk bucket. */
    private static final int DRINKABLE_HYDRATION = 5;

    private final PackOutput output;

    public LsoClimateDataProvider(PackOutput output) {
        this.output = output;
    }

    private static Map<HeatTier, Float> heatTemperatures() {
        EnumMap<HeatTier, Float> map = new EnumMap<>(HeatTier.class);
        map.put(HeatTier.EMBERS, 2.0F);
        map.put(HeatTier.SMALL_FIRE, 4.0F);
        map.put(HeatTier.MEDIUM_FIRE, 7.0F);
        map.put(HeatTier.LARGE_FIRE, 10.0F);
        map.put(HeatTier.STOKED_FIRE, 12.0F);
        map.put(HeatTier.HIBACHI_BODY, 1.0F);
        map.put(HeatTier.BRICK_OVEN, 6.0F);
        map.put(HeatTier.TORCH, 1.5F);
        map.put(HeatTier.CANDLE, 1.0F);
        map.put(HeatTier.SMOULDERING_LOG, 3.0F);
        map.put(HeatTier.BURNING_LADDER, 7.0F);
        map.put(HeatTier.LAVA_CRACKS, 12.0F);
        return Collections.unmodifiableMap(map);
    }

    // ------------------------------------------------------------------
    // plan
    // ------------------------------------------------------------------

    /**
     * Every LSO file BTW ships, keyed by this run's live BTW ids, in a stable order. Throws
     * {@link IllegalStateException} before FC init, when a heat tier has no value, when an FC field or a vanilla
     * selector does not resolve, or when a (folder, key) pair would be written twice. Walks the 32000 FC item slots
     * twice (drinkables, then {@link BtwThermalTable#fuels()} once), so call it once per run, never per tick.
     */
    public static List<LsoFile> plan() {
        if (!BTWLifecycle.isFcReady()) {
            throw new IllegalStateException("[BTW-LSO] the LSO data plan needs FC content, which is not built yet");
        }
        for (HeatTier tier : HeatTier.values()) {
            if (!HEAT_TEMPERATURE.containsKey(tier)) {
                throw new IllegalStateException("[BTW-LSO] heat tier " + tier
                        + " has no LSO temperature; map it in LsoClimateDataProvider.HEAT_TEMPERATURE");
            }
        }
        Plan plan = new Plan();

        // (1) Heat blocks: one file per block, one entry per row (a full 0-15 row matches every state).
        Map<ResourceLocation, JsonArray> heatBlocks = new LinkedHashMap<>();
        Map<ResourceLocation, String> heatSourceOf = new HashMap<>();
        for (HeatSource row : BtwThermalTable.heatSources()) {
            ResourceLocation key = plan.resolved(FcRefs.blockKey(row.fcField()), "block " + row.fcField());
            if (key == null) continue;
            float temperature = HEAT_TEMPERATURE.get(row.tier());
            JsonArray entries = heatBlocks.computeIfAbsent(key, k -> new JsonArray());
            heatSourceOf.putIfAbsent(key, row.fcField());
            if (row.metaMin() == 0 && row.metaMax() == 15) {
                entries.add(blockEntry(null, null, temperature));
            } else {
                for (int meta = row.metaMin(); meta <= row.metaMax(); meta++) {
                    entries.add(blockEntry("meta", Integer.toString(meta), temperature));
                }
            }
        }
        for (Map.Entry<ResourceLocation, JsonArray> e : heatBlocks.entrySet()) {
            plan.add(BLOCKS, e.getKey(), e.getValue(), heatSourceOf.get(e.getKey()));
        }

        // (1b) Vanilla-key heat rows, except where LSO ships its own file.
        for (VanillaHeatSource row : BtwThermalTable.vanillaHeatSources()) {
            float temperature = HEAT_TEMPERATURE.get(row.tier());
            for (ResourceLocation key : expandBlockSelector(row.selector())) {
                if (lsoShips(BLOCKS, key)) continue;
                Block block = BuiltInRegistries.BLOCK.get(key);
                if (row.requiresLit() && !block.defaultBlockState().hasProperty(BlockStateProperties.LIT)) {
                    throw new IllegalStateException("[BTW-LSO] " + key + " (from " + row.selector()
                            + ") has no 'lit' property, so a lit-only heat entry would never match");
                }
                JsonArray entries = new JsonArray();
                entries.add(row.requiresLit() ? blockEntry("lit", "true", temperature)
                        : blockEntry(null, null, temperature));
                plan.add(BLOCKS, key, entries, row.selector());
            }
        }

        // (2) Held burning torches: the item form of every TORCH-tier block.
        for (String field : torchFields()) {
            ResourceLocation key = plan.resolved(FcRefs.itemKey(field), "item " + field);
            if (key != null) plan.add(ITEMS, key, resistance(0F, 0F, HELD_TORCH_TEMPERATURE), field);
        }

        // (3) Clothing: plate resists heat, every cloth and leather set resists cold.
        for (Clothing c : BtwThermalTable.clothing()) {
            ResourceLocation key = plan.resolved(FcRefs.itemKey(c.fcField()), "item " + c.fcField());
            if (key == null) continue;
            float value = clothingValue(c.set(), c.slot());
            boolean heat = c.set() == ClothingSet.PLATE;
            plan.add(ITEMS, key, resistance(heat ? 0F : value, heat ? value : 0F, 0F), c.fcField());
        }

        // (4) Limb armour: padded and plate protect the limb of their slot.
        for (Clothing c : BtwThermalTable.clothing()) {
            float limb = limbValue(c.set());
            if (limb <= 0F) continue;
            ResourceLocation key = plan.resolved(FcRefs.itemKey(c.fcField()), "item " + c.fcField());
            if (key != null) plan.add(LIMBS, key, limbResistance(c.slot(), limb), c.fcField());
        }

        // (5) BTW hot meals: warmth and hydration.
        for (HotMeal meal : BtwThermalTable.hotMeals()) {
            ResourceLocation key = plan.resolved(FcRefs.itemKey(meal.fcField()), "item " + meal.fcField());
            if (key == null) continue;
            plan.add(CONSUMABLES, key, mealWarmth(meal.tier()), meal.fcField());
            plan.add(THIRST, key, hydration(mealHydration(meal.tier())), meal.fcField());
        }

        // (6) Vanilla-key hot meals, except what LSO ships (it ships mushroom stew's warmth, not its hydration).
        for (VanillaHotMeal meal : BtwThermalTable.vanillaHotMeals()) {
            ResourceLocation key = meal.item();
            if (!ForgeRegistries.ITEMS.containsKey(key)) {
                throw new IllegalStateException("[BTW-LSO] vanilla hot meal " + key + " is not a registered item");
            }
            String source = key.getPath();
            if (!lsoShips(CONSUMABLES, key)) plan.add(CONSUMABLES, key, mealWarmth(meal.tier()), source);
            if (!lsoShips(THIRST, key)) plan.add(THIRST, key, hydration(mealHydration(meal.tier())), source);
        }

        // (7) Every BTW drinkable (FcFoodValues source FC_DRINKABLE) hydrates like LSO's milk.
        btw.modern.Item[] fcItems = btw.modern.Item.itemsList;
        Set<ResourceLocation> drinkables = new LinkedHashSet<>();
        for (int id = 1; id < fcItems.length; id++) {
            if (fcItems[id] == null) continue;
            Item modern = ProxyRegistry.getModernItem(id);
            if (modern == null || modern == Items.AIR) continue;
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(modern);
            if (key == null || !BTWForgeMod.MOD_ID.equals(key.getNamespace())) continue;
            FcFoodValues.FcFood food = FcFoodValues.of(new ItemStack(modern), null);
            if (food == null || food.source() != FcFoodValues.Source.FC_DRINKABLE) continue;
            if (drinkables.add(key)) plan.add(THIRST, key, hydration(DRINKABLE_HYDRATION), "drinkable");
        }
        if (drinkables.isEmpty()) {
            throw new IllegalStateException("[BTW-LSO] no BTW drinkable found (FcFoodValues source FC_DRINKABLE)");
        }

        // (8) Heater fuels: FC burn time scaled so one FC coal (1600) equals LSO's coal (600).
        List<Fuel> fuels = BtwThermalTable.fuels();
        if (fuels.isEmpty()) {
            throw new IllegalStateException("[BTW-LSO] BtwThermalTable.fuels() is empty");
        }
        for (Fuel fuel : fuels) {
            long duration = Math.max(1L,
                    Math.round(600.0 * fuel.fcBurnTime() / BtwThermalTable.FC_COAL_BURN_TIME));
            JsonObject json = new JsonObject();
            json.addProperty("duration", (int) duration);
            json.addProperty("thermal_type", "HEATING");
            plan.add(FUELS, fuel.key(), json, "fuel");
        }

        return plan.finish();
    }

    /** The distinct heat-source block fields, in table order (the manifest's blocks). */
    public static List<String> manifestBlockFields() {
        Set<String> fields = new LinkedHashSet<>();
        for (HeatSource row : BtwThermalTable.heatSources()) fields.add(row.fcField());
        return List.copyOf(fields);
    }

    /** The clothing fields, then the hot-meal fields, then the TORCH-tier block fields (the manifest's items). */
    public static List<String> manifestItemFields() {
        Set<String> fields = new LinkedHashSet<>();
        for (Clothing c : BtwThermalTable.clothing()) fields.add(c.fcField());
        for (HotMeal meal : BtwThermalTable.hotMeals()) fields.add(meal.fcField());
        fields.addAll(torchFields());
        return List.copyOf(fields);
    }

    // ------------------------------------------------------------------
    // DataProvider
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<LsoFile> files = plan();
        Map<String, PackOutput.PathProvider> folders = new HashMap<>();
        List<CompletableFuture<?>> writes = new ArrayList<>(files.size() + 1);
        for (LsoFile file : files) {
            PackOutput.PathProvider folder = folders.computeIfAbsent(file.folder(),
                    f -> output.createPathProvider(PackOutput.Target.DATA_PACK, "legendarysurvivaloverhaul/" + f));
            writes.add(DataProvider.saveStable(cache, file.json(), folder.json(file.key())));
        }
        writes.add(DataProvider.saveStable(cache,
                FcRefs.refsManifest(manifestBlockFields(), manifestItemFields()),
                output.createPathProvider(PackOutput.Target.DATA_PACK, "compat_refs")
                        .json(new ResourceLocation(BTWForgeMod.MOD_ID, "lso"))));
        return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]));
    }

    @Override
    public String getName() {
        return "BTW LSO climate data";
    }

    // ------------------------------------------------------------------
    // rows -> JSON
    // ------------------------------------------------------------------

    /**
     * True when LSO 2.4.7 already ships this file (jar listing): its vanilla torch blocks and mushroom-stew warmth.
     * BTW's file would replace LSO's by pack order, so those keep LSO's values.
     */
    private static boolean lsoShips(String folder, ResourceLocation key) {
        if (!"minecraft".equals(key.getNamespace())) return false;
        return switch (folder) {
            case BLOCKS -> key.getPath().equals("torch") || key.getPath().equals("wall_torch");
            case CONSUMABLES -> key.getPath().equals("mushroom_stew");
            default -> false;
        };
    }

    /**
     * The blocks a vanilla-key selector names: a block id, or {@code #minecraft:candles} / {@code #minecraft:candle_cakes}
     * expanded by class over the registry in the tag's namespace (tags are not bound in runData). Any other tag throws.
     */
    private static List<ResourceLocation> expandBlockSelector(String selector) {
        if (selector.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(selector.substring(1));
            Class<? extends Block> kind = switch (selector) {
                case "#minecraft:candles" -> CandleBlock.class;
                case "#minecraft:candle_cakes" -> CandleCakeBlock.class;
                default -> null;
            };
            if (tag == null || kind == null) {
                throw new IllegalStateException("[BTW-LSO] heat selector " + selector
                        + " has no LSO expansion; add one to LsoClimateDataProvider.expandBlockSelector");
            }
            List<ResourceLocation> keys = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (!kind.isInstance(block)) continue;
                ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
                if (tag.getNamespace().equals(key.getNamespace())) keys.add(key);
            }
            if (keys.isEmpty()) {
                throw new IllegalStateException("[BTW-LSO] heat selector " + selector + " matched no block");
            }
            return keys;
        }
        ResourceLocation key = ResourceLocation.tryParse(selector);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            throw new IllegalStateException("[BTW-LSO] heat selector " + selector + " is not a registered block");
        }
        return List.of(key);
    }

    /** The distinct fields of the TORCH-tier heat rows, in table order. */
    private static List<String> torchFields() {
        Set<String> fields = new LinkedHashSet<>();
        for (HeatSource row : BtwThermalTable.heatSources()) {
            if (row.tier() == HeatTier.TORCH) fields.add(row.fcField());
        }
        return List.copyOf(fields);
    }

    /** One LSO block entry; {@code property == null} matches every state. */
    private static JsonObject blockEntry(@Nullable String property, @Nullable String value, float temperature) {
        JsonObject properties = new JsonObject();
        if (property != null) properties.addProperty(property, value);
        JsonObject entry = new JsonObject();
        entry.add("properties", properties);
        entry.addProperty("temperature", temperature);
        return entry;
    }

    /** An LSO temperature item: cold and heat resistance plus the temperature it gives when held or worn. */
    private static JsonObject resistance(float cold, float heat, float temperature) {
        JsonObject json = new JsonObject();
        json.addProperty("cold_resistance", cold);
        json.addProperty("heat_resistance", heat);
        json.addProperty("temperature", temperature);
        json.addProperty("thermal_resistance", 0F);
        return json;
    }

    /** Insulation per piece, head/chest/legs/feet (decision #14). */
    private static float clothingValue(ClothingSet set, Slot slot) {
        float[] bySlot = switch (set) {
            case WOOL -> new float[] {1.5F, 2.0F, 1.5F, 1.0F};
            case PADDED, TANNED, GIMP -> new float[] {1.0F, 1.5F, 1.0F, 0.5F};
            case PLATE -> new float[] {0.5F, 1.5F, 1.0F, 1.0F};
        };
        return bySlot[slotIndex(slot)];
    }

    private static int slotIndex(Slot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
        };
    }

    /** Limb damage resistance per piece; 0 for sets that give none. */
    private static float limbValue(ClothingSet set) {
        return switch (set) {
            case PADDED -> 0.05F;
            case PLATE -> 0.15F;
            case WOOL, TANNED, GIMP -> 0F;
        };
    }

    /** LSO body-part resistance: all seven limb fields, the slot's limb set to {@code value}. */
    private static JsonObject limbResistance(Slot slot, float value) {
        String limb = switch (slot) {
            case HEAD -> "head_resistance";
            case CHEST -> "chest_resistance";
            case LEGS -> "legs_resistance";
            case FEET -> "feet_resistance";
        };
        JsonObject json = new JsonObject();
        for (String field : List.of("body_resistance", "chest_resistance", "feet_resistance", "head_resistance",
                "left_arm_resistance", "legs_resistance", "right_arm_resistance")) {
            json.addProperty(field, field.equals(limb) ? value : 0F);
        }
        return json;
    }

    /** LSO temperature consumable: WARM warms as food and drink (level 1, 1200 ticks), HOT as food (level 2, 1800). */
    private static JsonArray mealWarmth(MealTier tier) {
        JsonArray effects = new JsonArray();
        switch (tier) {
            case WARM -> {
                effects.add(consumableEntry("FOOD", 1, 1200));
                effects.add(consumableEntry("DRINK", 1, 1200));
            }
            case HOT -> effects.add(consumableEntry("FOOD", 2, 1800));
        }
        return effects;
    }

    private static JsonObject consumableEntry(String group, int level, int duration) {
        JsonObject json = new JsonObject();
        json.addProperty("duration", duration);
        json.addProperty("group", group);
        json.addProperty("temperature_level", level);
        return json;
    }

    /** Hydration of a hot meal: the thinner WARM soups give more water than HOT stew. */
    private static int mealHydration(MealTier tier) {
        return switch (tier) {
            case WARM -> 4;
            case HOT -> 3;
        };
    }

    /** LSO thirst consumable: {@code hydration} water, saturation 1.0, no effects, any NBT. */
    private static JsonArray hydration(int hydration) {
        JsonObject entry = new JsonObject();
        entry.add("effects", new JsonArray());
        entry.addProperty("hydration", hydration);
        entry.add("properties", new JsonObject());
        entry.addProperty("saturation", 1.0F);
        JsonArray json = new JsonArray();
        json.add(entry);
        return json;
    }

    /** The files in order; refuses a repeated (folder, key) and collects every unresolved FC field. */
    private static final class Plan {
        private final List<LsoFile> files = new ArrayList<>();
        private final Set<String> written = new HashSet<>();
        private final List<String> unresolved = new ArrayList<>();

        @Nullable ResourceLocation resolved(@Nullable ResourceLocation key, String what) {
            if (key == null) unresolved.add(what);
            return key;
        }

        void add(String folder, ResourceLocation key, JsonElement json, String source) {
            if (!written.add(folder + '|' + key)) {
                throw new IllegalStateException("[BTW-LSO] LSO data " + folder + "/" + key + " is planned twice ("
                        + source + ")");
            }
            files.add(new LsoFile(folder, key, json, source));
        }

        List<LsoFile> finish() {
            if (!unresolved.isEmpty()) {
                throw new IllegalStateException("[BTW-LSO] FC fields did not resolve for LSO data: " + unresolved);
            }
            return List.copyOf(files);
        }
    }
}
