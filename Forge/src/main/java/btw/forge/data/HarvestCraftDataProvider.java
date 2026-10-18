package btw.forge.data;

import btw.forge.compat.FcRefs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * BTW data for Pam's HarvestCraft 2 and the general rules it uses (INTEGRATIONS #032), written by runData into
 * {@code Forge/src/generated/resources/data/}. 18 files:
 * <ul>
 *   <li>{@code forge/tags/items/flour/flour.json}: BTW flour joins {@code #forge:flour/flour} (Food Core and Extended's
 *       {@code #forge:flour} is that tag), so HC recipes take milled BTW flour. FC wheat and BTW raw meats stay out of
 *       the forge tags.</li>
 *   <li>{@code betterthanwolves/tags/blocks/}: {@code fc_soil_plants} and {@code dung_spreads} (the six HC gardens: FC
 *       wild vegetation for bone meal, BTW dung spreads them) and {@code paced_like_sweet_berries} (95 modded fruit and
 *       pods at FC cocoa's pace: the 50 HC tree fruit, Dynamic Trees' apple and cocoa, the 43 dtphc2 fruit and
 *       pods).</li>
 *   <li>{@code betterthanwolves/btw_machine_recipes/harvestcraft/}: {@code #forge:flour_plants} mills to BTW flour,
 *       {@code #forge:fiber} to two hemp fibres (settled decision #13). Unconditional general rules: an empty tag adds
 *       nothing and FC-mapped members keep FC's own recipes (TagMachineRecipes).</li>
 *   <li>{@code betterthanwolves/btw_recipe_replacements/harvestcraft/}: six reroutes by recipe id, each with its HC
 *       module condition and a reason (RecipeReplacements; {@code btw:<field>} placeholders resolve live).</li>
 *   <li>{@code betterthanwolves/btw_foods/harvestcraft/}: settled decision #12's tiers per HC module, the ground meats
 *       as their FC raw and cooked meats (nearest-FC-relative ruling; the per-meat subtags, because
 *       {@code #forge:groundmeats} holds firm tofu), and FC fodder for seeds, grains and vegetables
 *       (BtwFoodTable). The grain entry sits in the Crops file, as only the Crops jar defines
 *       {@code #forge:grain}, so a Trees-only pack logs no unknown tag.</li>
 *   <li>{@code betterthanwolves/compat_refs/harvestcraft.json}: the BTW key written into static data (BTW flour), for
 *       BridgeSelfAudit's moved-ID check.</li>
 * </ul>
 *
 * <p>No HarvestCraft class or jar is needed: every file names only ids, tags and Forge conditions, and is inert without
 * the mods it names (conditions, {@code required:false} tag entries, empty-tag machine rules). The provider therefore
 * runs in every runData, so the datagen cache never purges its files. It fails runData, rather than shipping a broken
 * reroute, when an FC field it relies on does not resolve.</p>
 */
// FCMOD-INTEGRATION(1.20.1): harvestcraft: HarvestCraft food tiers, tags, millstone and reroute data and the modded-fruit pace tag as reviewable JSON, inert without the mods it names
public final class HarvestCraftDataProvider implements DataProvider {

    private static final String BTW = "betterthanwolves";
    private static final String CROPS = "pamhc2crops";
    private static final String TREES = "pamhc2trees";
    private static final String FOOD_CORE = "pamhc2foodcore";
    private static final String FOOD_EXTENDED = "pamhc2foodextended";

    /** HarvestCraft gardens (pamhc2crops GardenBlock, BonemealableBlock): FC wild vegetation, spread by BTW dung. */
    private static final List<String> GARDENS = List.of("aridgarden", "frostgarden", "shadedgarden", "soggygarden",
            "tropicalgarden", "windygarden");

    /** pamhc2trees fruit blocks (blockstates in the trees jar, age 0-7): BlockPamFruit and BlockPamLogFruit. */
    private static final List<String> HC_FRUIT = List.of("pamacorn", "pamalmond", "pamapple", "pamapricot",
            "pamavocado", "pambanana", "pambreadfruit", "pamcandlenut", "pamcashew", "pamcherry", "pamchestnut",
            "pamcinnamon", "pamcoconut", "pamdate", "pamdragonfruit", "pamdurian", "pamfig", "pamgooseberry",
            "pamgrapefruit", "pamguava", "pamhazelnut", "pamjackfruit", "pamlemon", "pamlime", "pamlychee", "pammango",
            "pammaple", "pamnutmeg", "pamolive", "pamorange", "pampapaya", "pampaperbark", "pampassionfruit",
            "pampawpaw", "pampeach", "pampear", "pampecan", "pampeppercorn", "pampersimmon", "pampinenut",
            "pampistachio", "pamplum", "pampomegranate", "pamrambutan", "pamsoursop", "pamspiderweb", "pamstarfruit",
            "pamtamarind", "pamvanillabean", "pamwalnut");

    /** Dynamic Trees' own fruit and pod blocks (FruitBlock / PodBlock, growth chance 0.2 per random tick). */
    private static final List<String> DT_FRUIT = List.of("dynamictrees:apple", "dynamictrees:cocoa");

    /** dtphc2's 40 fruit and 3 pod blocks (trees/dtphc2/fruits and pods in its jar, each with a blockstate). */
    private static final List<String> DTPHC2_FRUIT = List.of("acorn", "almond", "apricot", "avocado", "banana",
            "breadfruit", "candlenut", "cashew", "cherry", "chestnut", "dragon_fruit", "durian", "fig", "gooseberry",
            "grapefruit", "guava", "hazelnut", "jackfruit", "lemon", "lime", "lychee", "mango", "nutmeg", "olive",
            "orange", "pawpaw", "peach", "pear", "pecan", "persimmon", "pinenut", "pistachio", "plum", "pomegranate",
            "rambutan", "soursop", "spiderweb", "starfruit", "tamarind", "walnut", "coconut", "date", "papaya");

    /** The FC objects the reroutes and the dung rule name, by FCBetterThanWolves field. */
    private static final List<String> REQUIRED_ITEMS = List.of("fcItemFlour", "fcItemBreadDough",
            "fcBlockTorchFiniteUnlit", "fcItemDung");
    private static final String HEMP_FIBERS = "fcItemHempFibers";

    private final PackOutput output;

    public HarvestCraftDataProvider(PackOutput output) {
        this.output = output;
    }

    @Override
    public String getName() {
        return "BTW HarvestCraft compat data";
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        ResourceLocation flour = validate();
        Path data = output.getOutputFolder(PackOutput.Target.DATA_PACK);
        List<CompletableFuture<?>> writes = new ArrayList<>();

        // 1. BTW flour in the forge flour tag
        writes.add(save(cache, data, "forge", "tags/items/flour/flour", tag(List.of(flour.toString()))));

        // 2. Block tags read by BTW's general rules
        List<String> gardens = ids(CROPS, GARDENS);
        writes.add(save(cache, data, BTW, "tags/blocks/fc_soil_plants", tag(gardens)));
        writes.add(save(cache, data, BTW, "tags/blocks/dung_spreads", tag(gardens)));
        List<String> fruit = new ArrayList<>(ids(TREES, HC_FRUIT));
        fruit.addAll(DT_FRUIT);
        fruit.addAll(ids("dtphc2", DTPHC2_FRUIT));
        writes.add(save(cache, data, BTW, "tags/blocks/paced_like_sweet_berries", tag(fruit)));

        // 3. Millstone rules
        writes.add(save(cache, data, BTW, "btw_machine_recipes/harvestcraft/flour_plants",
                millstone("forge:flour_plants", "fcItemFlour", 1)));
        writes.add(save(cache, data, BTW, "btw_machine_recipes/harvestcraft/fiber",
                millstone("forge:fiber", HEMP_FIBERS, 2)));

        // 4. Recipe reroutes
        String replacements = "btw_recipe_replacements/harvestcraft/";
        writes.add(save(cache, data, BTW, replacements + "bread_grain", replace(CROPS + ":bread_grain",
                conditions(modLoaded(CROPS)),
                shapeless(List.of(tagIngredient("forge:flour/flour"), tagIngredient("forge:flour/flour"),
                        tagIngredient("forge:flour/flour"), itemIngredient("minecraft:water_bucket")),
                        "btw:fcItemBreadDough", 1),
                "HarvestCraft grain bakes through BTW dough: three milled flour and water, FC's own dough recipe "
                        + "(FCRecipes.java:1426-1430), instead of three grain straight to bread")));
        writes.add(save(cache, data, BTW, replacements + "bread_dough", replace(FOOD_CORE + ":bread_dough",
                conditions(modLoaded(FOOD_CORE)),
                shapeless(List.of(tagIngredient("forge:tool_bakeware"), tagIngredient("forge:dough")),
                        "btw:fcItemBreadDough", 1),
                "HarvestCraft dough becomes BTW bread dough, which bakes in the BTW oven (settled decision 12 (a)), "
                        + "instead of bread from the crafting grid")));
        writes.add(save(cache, data, BTW, replacements + "torch_cattail", replace(CROPS + ":torch_cattail",
                conditions(modLoaded(CROPS), modLoaded(FOOD_CORE)),
                shapeless(List.of(itemIngredient(FOOD_CORE + ":cookingoilitem"),
                        itemIngredient(CROPS + ":cattailitem")), "btw:fcBlockTorchFiniteUnlit", 1),
                "the cattail torch is a BTW finite torch: 4.B keeps the vanilla torch uncraftable, so cooking oil and "
                        + "a cattail make an unlit finite torch instead of a vanilla torch")));
        writes.add(save(cache, data, BTW, replacements + "flour_x1_flour_plants",
                replace(FOOD_CORE + ":flour_x1_flour_plants",
                        conditions(modLoaded(FOOD_CORE)),
                        shapeless(List.of(tagIngredient("forge:tool_grinder"), itemIngredient("btw:fcItemFlour")),
                                FOOD_CORE + ":flouritem", 1),
                        "flour plants are milled: #forge:flour_plants -> BTW flour in the millstone, and HarvestCraft "
                                + "flour is made from BTW flour, not ground from flour plants in the grid")));
        writes.add(save(cache, data, BTW, replacements + "string_fiber", remove(CROPS + ":string_fiber",
                conditions(modLoaded(CROPS)),
                "fibre crops are milled: #forge:fiber -> BTW hemp fibres in the millstone (settled decision #13)")));
        writes.add(save(cache, data, BTW, replacements + "doughitem_x2", replace(FOOD_CORE + ":doughitem_x2",
                conditions(modLoaded(FOOD_CORE)),
                shapeless(List.of(tagIngredient("forge:tool_mixingbowl"), tagIngredient("forge:flour"),
                        tagIngredient("forge:flour"), tagIngredient("forge:flour"), tagIngredient("forge:water"),
                        tagIngredient("forge:salt")), FOOD_CORE + ":doughitem", 1),
                "HarvestCraft dough costs FC's flour ratio, three flour per dough as FC's own dough "
                        + "(FCRecipes.java:1426-1430), so everything made from it, BTW bread included, costs 4.B "
                        + "flour (decision D2 (b))")));

        // 5. Food values
        String foods = "btw_foods/harvestcraft/";
        writes.add(save(cache, data, BTW, foods + CROPS, foodFile(
                "HarvestCraft crops: settled decision #12 tiers by 1.20.1 nutrition (hunger in 1.5.2 units, FC feeds "
                        + "x3), and FC herbivore food for grains (#forge:grain, defined by the Crops jar alone)",
                conditions(modLoaded(CROPS)), grainFodderEntries(), tier(CROPS, rawProduceBands()))));
        writes.add(save(cache, data, BTW, foods + TREES, foodFile(
                "HarvestCraft tree fruit and nuts: settled decision #12 tiers by 1.20.1 nutrition (hunger in 1.5.2 "
                        + "units, FC feeds x3)",
                conditions(modLoaded(TREES)), null, tier(TREES, rawProduceBands()))));
        writes.add(save(cache, data, BTW, foods + FOOD_CORE, foodFile(
                "HarvestCraft Food Core: settled decision #12 meal tiers (the cap is FC's hearty stew, 10); raw ground "
                        + "meats as their FC raw meat with FC food poisoning, cooked ground meats as their FC cooked "
                        + "meat (nearest-FC-relative ruling). The per-meat subtags are used because #forge:groundmeats "
                        + "also holds firm tofu.",
                conditions(modLoaded(FOOD_CORE)), groundMeatEntries(), tier(FOOD_CORE, mealBands()))));
        writes.add(save(cache, data, BTW, foods + FOOD_EXTENDED, foodFile(
                "HarvestCraft Food Extended: settled decision #12 meal tiers (the cap is FC's hearty stew, 10)",
                conditions(modLoaded(FOOD_EXTENDED)), null, tier(FOOD_EXTENDED, mealBands()))));
        writes.add(save(cache, data, BTW, foods + "fodder", foodFile(
                "FC animal food for HarvestCraft seeds (chickens) and vegetables (pigs), at FC's base values; grains "
                        + "(herbivores) are in pamhc2crops.json",
                conditions(or(modLoaded(CROPS), modLoaded(TREES))), fodderEntries(), null)));

        // 6. The BTW key written into static data, for BridgeSelfAudit's moved-ID check
        writes.add(save(cache, data, BTW, "compat_refs/harvestcraft",
                FcRefs.refsManifest(List.of(), List.of("fcItemFlour"))));

        return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * Fails runData unless every FC object the data names resolves: the reroute results and ingredients and the dung
     * as item keys, the hemp fibres as an FC item id (the millstone output). Returns BTW flour's item key.
     */
    private static ResourceLocation validate() {
        List<String> missing = new ArrayList<>();
        ResourceLocation flour = null;
        for (String field : REQUIRED_ITEMS) {
            ResourceLocation key = FcRefs.itemKey(field);
            if (key == null) missing.add(field);
            else if ("fcItemFlour".equals(field)) flour = key;
        }
        if (FcRefs.legacyItemId(HEMP_FIBERS) <= 0) missing.add(HEMP_FIBERS);
        if (!missing.isEmpty() || flour == null) {
            throw new IllegalStateException("[BTW-HarvestCraft] FC fields the HarvestCraft data names did not resolve: "
                    + missing);
        }
        return flour;
    }

    // ================================================================
    // JSON builders
    // ================================================================

    private static CompletableFuture<?> save(CachedOutput cache, Path data, String namespace, String path,
                                             JsonElement json) {
        return DataProvider.saveStable(cache, json, data.resolve(namespace).resolve(path + ".json"));
    }

    private static List<String> ids(String namespace, List<String> paths) {
        List<String> out = new ArrayList<>(paths.size());
        for (String path : paths) out.add(namespace + ":" + path);
        return out;
    }

    /** A tag file that adds to the tag ({@code replace:false}); every entry {@code required:false}. */
    private static JsonObject tag(List<String> ids) {
        JsonArray values = new JsonArray();
        for (String id : ids) {
            JsonObject value = new JsonObject();
            value.addProperty("id", id);
            value.addProperty("required", false);
            values.add(value);
        }
        JsonObject root = new JsonObject();
        root.addProperty("replace", false);
        root.add("values", values);
        return root;
    }

    private static JsonObject millstone(String inputTag, String fcItem, int count) {
        JsonObject out = new JsonObject();
        out.addProperty("fc_item", fcItem);
        out.addProperty("count", count);
        JsonObject root = new JsonObject();
        root.addProperty("machine", "millstone");
        root.addProperty("input_tag", inputTag);
        root.add("output", out);
        return root;
    }

    private static JsonObject modLoaded(String modId) {
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "forge:mod_loaded");
        condition.addProperty("modid", modId);
        return condition;
    }

    private static JsonObject or(JsonObject... children) {
        JsonArray values = new JsonArray();
        for (JsonObject child : children) values.add(child);
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "forge:or");
        condition.add("values", values);
        return condition;
    }

    private static JsonArray conditions(JsonObject... conditions) {
        JsonArray array = new JsonArray();
        for (JsonObject condition : conditions) array.add(condition);
        return array;
    }

    private static JsonObject tagIngredient(String tag) {
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("tag", tag);
        return ingredient;
    }

    private static JsonObject itemIngredient(String item) {
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("item", item);
        return ingredient;
    }

    private static JsonObject shapeless(List<JsonObject> ingredients, String result, int count) {
        JsonArray list = new JsonArray();
        for (JsonObject ingredient : ingredients) list.add(ingredient);
        JsonObject out = new JsonObject();
        out.addProperty("item", result);
        if (count != 1) out.addProperty("count", count);
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:crafting_shapeless");
        recipe.add("ingredients", list);
        recipe.add("result", out);
        return recipe;
    }

    private static JsonObject replace(String target, JsonArray conditions, JsonObject recipe, String reason) {
        JsonObject root = new JsonObject();
        root.addProperty("replaces", target);
        root.add("conditions", conditions);
        root.addProperty("action", "replace");
        root.add("recipe", recipe);
        root.addProperty("reason", reason);
        return root;
    }

    private static JsonObject remove(String target, JsonArray conditions, String reason) {
        JsonObject root = new JsonObject();
        root.addProperty("replaces", target);
        root.add("conditions", conditions);
        root.addProperty("action", "remove");
        root.addProperty("reason", reason);
        return root;
    }

    // ---- btw_foods ----

    private static JsonObject foodFile(String comment, JsonArray conditions, JsonObject entries, JsonArray tiers) {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", comment);
        root.add("conditions", conditions);
        if (entries != null) root.add("entries", entries);
        if (tiers != null) root.add("tiers", tiers);
        return root;
    }

    private static JsonArray tier(String namespace, JsonArray bands) {
        JsonObject tier = new JsonObject();
        tier.addProperty("namespace", namespace);
        tier.add("bands", bands);
        JsonArray tiers = new JsonArray();
        tiers.add(tier);
        return tiers;
    }

    /** A band; a null {@code maxNutrition} is the catch-all (the last band). */
    private static JsonObject band(Integer maxNutrition, int hunger, double fat) {
        JsonObject band = new JsonObject();
        if (maxNutrition != null) band.addProperty("max_nutrition", maxNutrition);
        band.addProperty("hunger", hunger);
        band.addProperty("fat", fat);
        return band;
    }

    /** Settled decision #12, raw produce (Crops, Trees): nutrition 4 or less -> 1, else 2; no fat. */
    private static JsonArray rawProduceBands() {
        JsonArray bands = new JsonArray();
        bands.add(band(4, 1, 0.0));
        bands.add(band(null, 2, 0.0));
        return bands;
    }

    /** Settled decision #12, meals (Food Core, Food Extended): 2 / 3 / 5 / 8, capped at FC's hearty stew (10). */
    private static JsonArray mealBands() {
        JsonArray bands = new JsonArray();
        bands.add(band(4, 2, 0.0));
        bands.add(band(8, 3, 0.25));
        bands.add(band(16, 5, 0.25));
        bands.add(band(25, 8, 0.25));
        bands.add(band(null, 10, 0.25));
        return bands;
    }

    private static JsonObject food(int hunger, double fat, boolean poison) {
        JsonObject entry = new JsonObject();
        entry.addProperty("hunger", hunger);
        entry.addProperty("fat", fat);
        if (poison) entry.addProperty("poison", true);
        return entry;
    }

    /**
     * Raw ground meats as their FC raw meat (Server FCItemFood.java:134-147: beef and pork 4, the others 3, fat 0.25,
     * FC's standard food poisoning), by the per-meat subtags; cooked ground meats as their FC cooked meat (beef and
     * pork 5, the others 4).
     */
    private static JsonObject groundMeatEntries() {
        JsonObject entries = new JsonObject();
        entries.add("#forge:groundmeats/groundbeef", food(4, 0.25, true));
        entries.add("#forge:groundmeats/groundpork", food(4, 0.25, true));
        entries.add("#forge:groundmeats/groundchicken", food(3, 0.25, true));
        entries.add("#forge:groundmeats/groundfish", food(3, 0.25, true));
        entries.add("#forge:groundmeats/groundmutton", food(3, 0.25, true));
        entries.add("#forge:groundmeats/groundrabbit", food(3, 0.25, true));
        entries.add(FOOD_CORE + ":cookedgroundbeefitem", food(5, 0.25, false));
        entries.add(FOOD_CORE + ":cookedgroundporkitem", food(5, 0.25, false));
        entries.add(FOOD_CORE + ":cookedgroundchickenitem", food(4, 0.25, false));
        entries.add(FOOD_CORE + ":cookedgroundfishitem", food(4, 0.25, false));
        entries.add(FOOD_CORE + ":cookedgroundmuttonitem", food(4, 0.25, false));
        entries.add(FOOD_CORE + ":cookedgroundrabbititem", food(4, 0.25, false));
        return entries;
    }

    private static JsonObject fodder(String animal) {
        JsonObject fodder = new JsonObject();
        fodder.addProperty(animal, "basic");
        JsonObject entry = new JsonObject();
        entry.add("fodder", fodder);
        return entry;
    }

    /** Seeds (Forge's own tag) and vegetables (both the Crops and the Trees jar define it): either module has both. */
    private static JsonObject fodderEntries() {
        JsonObject entries = new JsonObject();
        entries.add("#forge:seeds", fodder("chicken"));
        entries.add("#forge:vegetables", fodder("pig"));
        return entries;
    }

    /**
     * Grains, in the Crops file: only pamhc2crops ships {@code #forge:grain} (Forge 47.3.0 has none), so the entry
     * loads only with it. No item is in two of the fodder tags, so the file split leaves every merge unchanged.
     */
    private static JsonObject grainFodderEntries() {
        JsonObject entries = new JsonObject();
        entries.add("#forge:grain", fodder("herbivore"));
        return entries;
    }
}
