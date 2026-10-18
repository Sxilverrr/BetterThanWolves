package btw.forge.food;

import btw.forge.LegacyProxyItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FC food values for foods FC has no item for, from data: {@code data/<ns>/btw_foods/*.json} (INTEGRATIONS #024).
 *
 * <pre>
 * { "conditions": [ ...Forge conditions, optional... ],
 *   "entries": {
 *     "&lt;item id&gt;" | "#&lt;item tag&gt;": {
 *       "hunger": int,          // 1.5.2 units (one pip = 2 half-shanks); FC applies x 3
 *       "fat": float,           // FC fat multiplier (default 0)
 *       "poison": { "chance": float, "seconds": int, "amplifier": int },   // FC food poisoning; defaults 0.3, 60, 0
 *       "fodder": { "herbivore": "basic" | int, "chicken": ..., "pig": ... }, // FC animal food values; "basic" = FC's
 *       "wolf_meat": bool       // FC wolves eat it
 *     } },
 *   "tiers": [                  // optional: one band rule per mod namespace
 *     { "namespace": "&lt;mod id&gt;",
 *       "bands": [ { "max_nutrition": int, "hunger": int, "fat": float }, ..., { "hunger": int, "fat": float } ] } ] }
 * </pre>
 *
 * <p>Every field is optional. An item entry wins per field; a field it lacks comes from the first matching tag entry.
 * Files load in resource-location order and a later file's entry for the same key replaces an earlier one; a datapack
 * overrides a whole file by its path. Item entries resolve when the files load; tag entries when the server's tags are
 * bound (TagsUpdatedEvent SERVER_DATA_LOAD, before the table is synced to players). Unknown ids are skipped with one
 * WARN each. The table is published as immutable maps through volatile fields: the integrated server and its client
 * share it. A dedicated server's client receives the resolved table (BTWNetwork.FoodTableSync).</p>
 *
 * <p><b>Tiers</b> (INTEGRATIONS #032): a band rule for every food of a namespace, so a mod's foods get FC values that
 * follow its own item set in every version. Scope: each registered item of the namespace whose no-arg
 * {@code Item.getFoodProperties()} is non-null and that is not a {@link LegacyProxyItem}. Its 1.20.1 nutrition picks
 * the first band whose {@code max_nutrition} is at least it; the last band has no {@code max_nutrition} and takes the
 * rest.
 * A band fills only the fields an item entry and the first matching tag entry leave open (item, then tag, then band).
 * A later file's tier for the same namespace replaces an earlier one. A malformed tier logs one WARN, is skipped and
 * counts as unknown. Tier entries resolve with the item and tag entries, so they are synced like them.</p>
 */
// FCMOD-INTEGRATION(1.20.1): food_values: post-1.5.2 and modded foods get FC values from reviewable data instead of a x3 guess
public final class BtwFoodTable {

    private BtwFoodTable() {}

    private static final Logger LOGGER = LogManager.getLogger("BTW-Food");

    /** The data directory under {@code data/<ns>/}. */
    public static final String DIRECTORY = "btw_foods";

    /** FC animal food values (FCEntityAnimal: herbivores, chickens, pigs); 0 = not their food. */
    public record Fodder(int herbivore, int chicken, int pig) {}

    /**
     * A resolved table entry. {@code hunger} is in 1.5.2 units (FC feeds hunger x 3) and null when the entry sets no
     * hunger (fodder, poison or wolf meat only); {@code poison} is FC food poisoning (potion 17).
     */
    public record Entry(@Nullable Integer hunger, float fat, @Nullable FcFoodValues.Effect poison,
                        @Nullable Fodder fodder, boolean wolfMeat) {}

    /** FC's standard food poisoning: hunger for 60 s, amplifier 0, chance 0.3 (Server FCItemFood.java:23-24, :173-178). */
    static final FcFoodValues.Effect STANDARD_POISON =
            new FcFoodValues.Effect(FcFoodValues.HUNGER_POTION_ID, 60 * 20, 0, 0.3F);

    private static volatile Map<Item, Entry> byItem = Map.of();
    private static volatile Map<ResourceLocation, Entry> byId = Map.of();

    /** The last load's parsed entries, kept for the tag resolution. Server thread. */
    private static volatile Loaded pending = new Loaded(Map.of(), List.of(), Map.of(), 0);

    /** The entry for {@code item}, or null. O(1), lock-free. */
    @Nullable
    public static Entry get(Item item) {
        return item == null ? null : byItem.get(item);
    }

    /** The resolved table by item id, as sent to clients. Immutable. */
    public static Map<ResourceLocation, Entry> snapshotForSync() {
        return byId;
    }

    /**
     * Client: replaces the table with the server's resolved one (BTWNetwork.FoodTableSync). Ids this client does not
     * know are skipped. The integrated server's own client never gets here (it shares the server's table).
     */
    public static void acceptSync(Map<ResourceLocation, Entry> entries) {
        Map<ResourceLocation, Entry> ids = new LinkedHashMap<>();
        Map<Item, Entry> items = new IdentityHashMap<>();
        if (entries != null) {
            for (Map.Entry<ResourceLocation, Entry> e : entries.entrySet()) {
                Item item = itemOf(e.getKey());
                if (item != null && e.getValue() != null) {
                    ids.put(e.getKey(), e.getValue());
                    items.put(item, e.getValue());
                }
            }
        }
        publish(items, ids);
    }

    /**
     * Server, TagsUpdatedEvent SERVER_DATA_LOAD: resolves the last load's tag entries against the bound item tags,
     * merges them under the item entries and the tier bands over them, publishes the table and logs
     * {@code [BTW-Food] loaded N entries (M tag, K tiered), U unknown ids skipped} (M tag entries, K namespace tiers).
     */
    public static void resolveTags(RegistryAccess access) {
        Loaded loaded = pending;
        int unknown = loaded.unknown();
        Map<Item, List<Raw>> tagMatches = new IdentityHashMap<>();
        Registry<Item> registry = access.registryOrThrow(Registries.ITEM);
        for (TagRaw tagRaw : loaded.tags()) {
            Optional<HolderSet.Named<Item>> set = registry.getTag(tagRaw.tag());
            if (set.isEmpty()) {
                unknown++;
                LOGGER.warn("[BTW-Food] unknown item tag #{} in {}; entry skipped", tagRaw.tag().location(), tagRaw.raw().file());
                continue;
            }
            for (Holder<Item> holder : set.get()) {
                tagMatches.computeIfAbsent(holder.value(), k -> new ArrayList<>()).add(tagRaw.raw());
            }
        }
        resolveAndPublish(loaded.items(), tagMatches, loaded.tiers());
        LOGGER.info("[BTW-Food] loaded {} entries ({} tag, {} tiered), {} unknown ids skipped",
                byItem.size(), loaded.tags().size(), loaded.tiers().size(), unknown);
    }

    /** Per field: the item entry, then the matching tag entries in load order, then the item's tier band. */
    private static void resolveAndPublish(Map<ResourceLocation, Raw> itemRaws, Map<Item, List<Raw>> tagMatches,
                                          Map<String, Tier> tiers) {
        Map<Item, Entry> items = new IdentityHashMap<>();
        Map<ResourceLocation, Entry> ids = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Raw> e : itemRaws.entrySet()) {
            Item item = itemOf(e.getKey());
            if (item == null) continue;
            Entry entry = merge(e.getValue(),
                    withBand(tagMatches.getOrDefault(item, List.of()), bandFor(item, e.getKey(), tiers)));
            items.put(item, entry);
            ids.put(e.getKey(), entry);
        }
        for (Map.Entry<Item, List<Raw>> e : tagMatches.entrySet()) {
            if (items.containsKey(e.getKey())) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(e.getKey());
            if (id == null) continue;
            Entry entry = merge(null, withBand(e.getValue(), bandFor(e.getKey(), id, tiers)));
            items.put(e.getKey(), entry);
            ids.put(id, entry);
        }
        if (!tiers.isEmpty()) {
            for (Map.Entry<ResourceKey<Item>, Item> e : ForgeRegistries.ITEMS.getEntries()) {
                Item item = e.getValue();
                if (item == null || item == Items.AIR || items.containsKey(item)) continue;
                ResourceLocation id = e.getKey().location();
                Raw band = bandFor(item, id, tiers);
                if (band == null) continue;
                Entry entry = merge(null, List.of(band));
                items.put(item, entry);
                ids.put(id, entry);
            }
        }
        publish(items, ids);
    }

    /** {@code tags} with {@code band} appended last (the lowest priority), or {@code tags} when there is no band. */
    private static List<Raw> withBand(List<Raw> tags, @Nullable Raw band) {
        if (band == null) return tags;
        List<Raw> out = new ArrayList<>(tags.size() + 1);
        out.addAll(tags);
        out.add(band);
        return out;
    }

    private static volatile boolean bandReadWarned;

    /**
     * The tier band of {@code item}: its namespace's tier, chosen by its no-arg 1.20.1 nutrition; null when the
     * namespace has no tier, the item is a {@link LegacyProxyItem} or no food, or its food properties cannot be read.
     * The no-arg read is deliberate (as FcFoodValues' TABLE path): it is static, never stack- or eater-sensitive.
     */
    // FCMOD-INTEGRATION(1.20.1): food_values: a tier fills only what the item and tag entries leave open, by the food's own nutrition (INTEGRATIONS #032)
    @Nullable
    @SuppressWarnings("deprecation")
    private static Raw bandFor(Item item, @Nullable ResourceLocation id, Map<String, Tier> tiers) {
        if (tiers.isEmpty() || id == null || item instanceof LegacyProxyItem) return null;
        Tier tier = tiers.get(id.getNamespace());
        if (tier == null) return null;
        FoodProperties props;
        try {
            props = item.getFoodProperties();
        } catch (RuntimeException | LinkageError e) {
            if (!bandReadWarned) {
                bandReadWarned = true;
                LOGGER.warn("[BTW-Food] food properties of {} cannot be read ({}); it gets no tier value (logged once)",
                        id, e.toString());
            }
            return null;
        }
        if (props == null) return null;
        int nutrition = props.getNutrition();
        for (Band band : tier.bands()) {
            if (nutrition <= band.maxNutrition()) return band.raw();
        }
        return null;
    }

    private static void publish(Map<Item, Entry> items, Map<ResourceLocation, Entry> ids) {
        byItem = Collections.unmodifiableMap(items);
        byId = Collections.unmodifiableMap(ids);
        FcFoodValues.invalidate();
    }

    @Nullable
    private static Item itemOf(ResourceLocation id) {
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) return null;
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null || item == Items.AIR ? null : item;
    }

    // ------------------------------------------------------------------
    // Parsing and merging
    // ------------------------------------------------------------------

    /** Sentinel for a "basic" fodder value, resolved to FC's base value when the entry is merged. */
    private static final int BASIC = Integer.MIN_VALUE;

    /** One parsed entry; a null field is absent (filled from tag entries). */
    private record Raw(String file, @Nullable Integer hunger, @Nullable Float fat, boolean poisonSet,
                       @Nullable FcFoodValues.Effect poison, @Nullable int[] fodder, @Nullable Boolean wolfMeat) {}

    private record TagRaw(TagKey<Item> tag, Raw raw) {}

    /** One band of a tier: foods whose 1.20.1 nutrition is at most {@code maxNutrition} (MAX_VALUE: the catch-all). */
    private record Band(int maxNutrition, Raw raw) {}

    /** A namespace's band rule, its bands in ascending order ending with the catch-all. */
    private record Tier(String namespace, String file, List<Band> bands) {}

    private record Loaded(Map<ResourceLocation, Raw> items, List<TagRaw> tags, Map<String, Tier> tiers,
                          int unknown) {}

    private static Entry merge(@Nullable Raw item, List<Raw> tags) {
        Integer hunger = item != null ? item.hunger() : null;
        Float fat = item != null ? item.fat() : null;
        boolean poisonSet = item != null && item.poisonSet();
        FcFoodValues.Effect poison = item != null ? item.poison() : null;
        int[] fodder = item != null ? item.fodder() : null;
        Boolean wolfMeat = item != null ? item.wolfMeat() : null;
        for (Raw tag : tags) {
            if (hunger == null) hunger = tag.hunger();
            if (fat == null) fat = tag.fat();
            if (!poisonSet && tag.poisonSet()) {
                poisonSet = true;
                poison = tag.poison();
            }
            if (fodder == null) fodder = tag.fodder();
            if (wolfMeat == null) wolfMeat = tag.wolfMeat();
        }
        return new Entry(hunger, fat != null ? fat : 0F, poison, fodder != null ? resolveFodder(fodder) : null,
                wolfMeat != null && wolfMeat);
    }

    /** "basic" resolves to Modern-Common Item's FC base food values (herbivore 800, chicken 1600, pig 800 at runtime). */
    private static Fodder resolveFodder(int[] raw) {
        return new Fodder(
                raw[0] == BASIC ? btw.modern.Item.m_iBaseHerbivoreItemFoodValue : raw[0],
                raw[1] == BASIC ? btw.modern.Item.m_iBaseChickenItemFoodValue : raw[1],
                raw[2] == BASIC ? btw.modern.Item.m_iBasePigItemFoodValue : raw[2]);
    }

    private static Raw parseRaw(String file, JsonElement element) {
        JsonObject obj = GsonHelper.convertToJsonObject(element, "btw_foods entry");
        Integer hunger = obj.has("hunger") ? GsonHelper.getAsInt(obj, "hunger") : null;
        Float fat = obj.has("fat") ? GsonHelper.getAsFloat(obj, "fat") : null;
        boolean poisonSet = obj.has("poison");
        FcFoodValues.Effect poison = null;
        if (poisonSet) {
            JsonElement p = obj.get("poison");
            if (p.isJsonObject()) {
                JsonObject po = p.getAsJsonObject();
                poison = new FcFoodValues.Effect(FcFoodValues.HUNGER_POTION_ID,
                        GsonHelper.getAsInt(po, "seconds", STANDARD_POISON.durationTicks() / 20) * 20,
                        GsonHelper.getAsInt(po, "amplifier", STANDARD_POISON.amplifier()),
                        GsonHelper.getAsFloat(po, "chance", STANDARD_POISON.chance()));
            } else if (p.isJsonPrimitive() && p.getAsJsonPrimitive().isBoolean()) {
                poison = p.getAsBoolean() ? STANDARD_POISON : null;   // true = FC's standard poisoning, false = none
            } else if (!p.isJsonNull()) {
                throw new JsonParseException("poison must be an object or a boolean");
            }
        }
        int[] fodder = null;
        if (obj.has("fodder")) {
            JsonObject fo = GsonHelper.getAsJsonObject(obj, "fodder");
            fodder = new int[] {fodderValue(fo, "herbivore"), fodderValue(fo, "chicken"), fodderValue(fo, "pig")};
        }
        Boolean wolfMeat = obj.has("wolf_meat") ? GsonHelper.getAsBoolean(obj, "wolf_meat") : null;
        return new Raw(file, hunger, fat, poisonSet, poison, fodder, wolfMeat);
    }

    private static int fodderValue(JsonObject fodder, String key) {
        if (!fodder.has(key)) return 0;
        JsonElement v = fodder.get(key);
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isString() && "basic".equals(p.getAsString())) return BASIC;
            if (p.isNumber()) return p.getAsInt();
        }
        throw new JsonParseException("fodder." + key + " must be \"basic\" or an int");
    }

    /**
     * One {@code tiers} element: a valid namespace and a non-empty band list whose {@code max_nutrition} values ascend,
     * every band but the last with one and the last without. Throws on anything else (the caller skips the tier).
     */
    // FCMOD-INTEGRATION(1.20.1): food_values: a mod's foods take FC values by nutrition band, so its whole item set is covered in every version (INTEGRATIONS #032)
    private static Tier parseTier(String file, JsonElement element) {
        JsonObject obj = GsonHelper.convertToJsonObject(element, "btw_foods tier");
        String namespace = GsonHelper.getAsString(obj, "namespace");
        if (namespace.isEmpty() || !ResourceLocation.isValidNamespace(namespace)) {
            throw new JsonParseException("namespace \"" + namespace + "\" is not a valid namespace");
        }
        JsonArray array = GsonHelper.getAsJsonArray(obj, "bands");
        if (array.size() == 0) throw new JsonParseException("bands is empty");
        List<Band> bands = new ArrayList<>(array.size());
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < array.size(); i++) {
            JsonObject b = GsonHelper.convertToJsonObject(array.get(i), "btw_foods tier band");
            boolean last = i == array.size() - 1;
            int max;
            if (b.has("max_nutrition")) {
                if (last) throw new JsonParseException("the last band must have no max_nutrition (it takes the rest)");
                max = GsonHelper.getAsInt(b, "max_nutrition");
                if (max <= previous) throw new JsonParseException("max_nutrition must ascend (band " + i + ")");
                previous = max;
            } else {
                if (!last) {
                    throw new JsonParseException("band " + i + " has no max_nutrition; only the last may omit it");
                }
                max = Integer.MAX_VALUE;
            }
            int hunger = GsonHelper.getAsInt(b, "hunger");
            if (hunger < 0) throw new JsonParseException("hunger must not be negative (band " + i + ")");
            Float fat = b.has("fat") ? GsonHelper.getAsFloat(b, "fat") : null;
            bands.add(new Band(max, new Raw(file, hunger, fat, false, null, null, null)));
        }
        return new Tier(namespace, file, List.copyOf(bands));
    }

    // ------------------------------------------------------------------
    // Reload listener (registered by BtwFoodEvents on AddReloadListenerEvent)
    // ------------------------------------------------------------------

    /** Loads {@code data/<ns>/btw_foods/*.json} on every server (re)load. */
    public static final class Loader extends SimpleJsonResourceReloadListener {
        private static final Gson GSON = new GsonBuilder().create();

        private final ICondition.IContext conditions;

        public Loader(ICondition.IContext conditions) {
            super(GSON, DIRECTORY);
            this.conditions = conditions != null ? conditions : ICondition.IContext.EMPTY;
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, Raw> items = new LinkedHashMap<>();
            List<TagRaw> tags = new ArrayList<>();
            Map<String, Tier> tiers = new LinkedHashMap<>();
            int unknown = 0;
            List<ResourceLocation> order = new ArrayList<>(files.keySet());
            order.sort(null);
            for (ResourceLocation fileId : order) {
                String file = fileId.toString();
                try {
                    JsonObject root = GsonHelper.convertToJsonObject(files.get(fileId), "btw_foods file");
                    if (root.has("conditions") && !CraftingHelper.processConditions(root, "conditions", conditions)) {
                        LOGGER.debug("[BTW-Food] {} skipped: conditions not met", file);
                        continue;
                    }
                    JsonObject entries = GsonHelper.getAsJsonObject(root, "entries", new JsonObject());
                    for (Map.Entry<String, JsonElement> e : entries.entrySet()) {
                        String key = e.getKey();
                        Raw raw;
                        try {
                            raw = parseRaw(file, e.getValue());
                        } catch (RuntimeException ex) {
                            unknown++;
                            LOGGER.warn("[BTW-Food] bad entry {} in {}: {}; entry skipped", key, file, ex.getMessage());
                            continue;
                        }
                        if (key.startsWith("#")) {
                            ResourceLocation tagId = ResourceLocation.tryParse(key.substring(1));
                            if (tagId == null) {
                                unknown++;
                                LOGGER.warn("[BTW-Food] bad item tag {} in {}; entry skipped", key, file);
                                continue;
                            }
                            tags.add(new TagRaw(TagKey.create(Registries.ITEM, tagId), raw));
                        } else {
                            ResourceLocation id = ResourceLocation.tryParse(key);
                            if (itemOf(id) == null) {
                                unknown++;
                                LOGGER.warn("[BTW-Food] unknown item id {} in {}; entry skipped", key, file);
                                continue;
                            }
                            items.put(id, raw);
                        }
                    }
                    if (root.has("tiers")) {
                        JsonElement tierList = root.get("tiers");
                        if (!tierList.isJsonArray()) {
                            unknown++;
                            LOGGER.warn("[BTW-Food] \"tiers\" in {} is not an array; tiers skipped", file);
                        } else {
                            JsonArray array = tierList.getAsJsonArray();
                            for (int i = 0; i < array.size(); i++) {
                                try {
                                    Tier tier = parseTier(file, array.get(i));
                                    tiers.put(tier.namespace(), tier);   // a later tier for the namespace wins
                                } catch (RuntimeException ex) {
                                    unknown++;
                                    LOGGER.warn("[BTW-Food] bad tier {} in {}: {}; tier skipped", i, file,
                                            ex.getMessage());
                                }
                            }
                        }
                    }
                } catch (RuntimeException ex) {
                    LOGGER.warn("[BTW-Food] cannot read {}: {}; file skipped", file, ex.getMessage());
                }
            }
            pending = new Loaded(Collections.unmodifiableMap(items), List.copyOf(tags),
                    Collections.unmodifiableMap(tiers), unknown);
            // Item entries and tiers apply at once; the tag entries join them when the tags are bound (resolveTags)
            resolveAndPublish(items, Map.of(), tiers);
        }
    }
}
