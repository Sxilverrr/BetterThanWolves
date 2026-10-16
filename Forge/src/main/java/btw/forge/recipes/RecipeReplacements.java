package btw.forge.recipes;

import btw.forge.compat.FcRefs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BTW's recipe replacements by id: data/&lt;ns&gt;/btw_recipe_replacements/*.json (INTEGRATIONS #025, part C).
 *
 * <p>Format: {@code {"replaces": "<recipe id>", "conditions": [...], "action": "replace"|"remove",
 * "recipe": {...}, "reason": "<text>"}}.</p>
 * <ul>
 *   <li>{@code conditions}: Forge conditions ({@link CraftingHelper#processConditions}); false skips the entry.</li>
 *   <li>{@code replace}: {@code recipe} is a normal recipe JSON, parsed with {@link RecipeManager#fromJson} under the
 *       ORIGINAL id. Every string inside it that is exactly {@code btw:<FCBetterThanWolves field>} becomes that FC
 *       object's item id ({@link FcRefs#itemKey}); an unresolved placeholder or a parse error logs a WARN and the
 *       original recipe stays.</li>
 *   <li>{@code remove}: removes the recipe; needs a non-empty {@code reason}, otherwise the entry is rejected with a
 *       WARN.</li>
 *   <li>Entries apply in file-id order; the first applied entry per target id wins and later ones log a WARN. An
 *       absent target is skipped (DEBUG): replacements never add recipes.</li>
 * </ul>
 *
 * <p>Applied by id inside RecipeBridge.injectRecipes, after every pack has loaded and before 4.B's removals and the
 * retired-ingredient widening, so pack order and mods added to an existing world do not matter and the stays-removed
 * rule also governs replacement recipes.</p>
 */
// FCMOD-INTEGRATION(1.20.1): recipes: data-driven replacements by recipe id, applied after every pack loads
public final class RecipeReplacements {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RecipeBridge");

    public static final String DIRECTORY = "btw_recipe_replacements";

    private static final Gson GSON = new GsonBuilder().create();

    private static final String PLACEHOLDER_PREFIX = "btw:";

    private record Entry(ResourceLocation file, JsonObject json) {}

    private static volatile List<Entry> entries = List.of();
    private static volatile ICondition.IContext context = ICondition.IContext.EMPTY;

    private RecipeReplacements() {}

    /** Reads the replacement files on every data load (registered from RecipeBridge's AddReloadListenerEvent). */
    public static final class Loader extends SimpleJsonResourceReloadListener {
        private final ICondition.IContext ctx;

        public Loader(ICondition.IContext ctx) {
            super(GSON, DIRECTORY);
            this.ctx = ctx != null ? ctx : ICondition.IContext.EMPTY;
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            List<Entry> loaded = new ArrayList<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : files.entrySet()) {
                ResourceLocation file = e.getKey();
                if (file.getPath().startsWith("_")) continue;
                if (e.getValue() == null || !e.getValue().isJsonObject()) {
                    LOGGER.warn("Recipe replacement {} is not a JSON object; ignored", file);
                    continue;
                }
                loaded.add(new Entry(file, e.getValue().getAsJsonObject()));
            }
            loaded.sort(Comparator.comparing(entry -> entry.file().toString()));
            entries = List.copyOf(loaded);
            context = ctx;
            LOGGER.debug("Loaded {} recipe replacement file(s)", loaded.size());
        }
    }

    /**
     * Applies the loaded replacements to {@code all} (id -> recipe) in place.
     *
     * @return {replaced, removed, skipped}
     */
    public static int[] applyTo(Map<ResourceLocation, Recipe<?>> all) {
        List<Entry> list = entries;
        ICondition.IContext ctx = context;
        Set<ResourceLocation> applied = new HashSet<>();
        int replaced = 0;
        int removed = 0;
        int skipped = 0;

        for (Entry entry : list) {
            ResourceLocation file = entry.file();
            JsonObject json = entry.json();
            try {
                ResourceLocation target = ResourceLocation.tryParse(GsonHelper.getAsString(json, "replaces", ""));
                if (target == null || target.getPath().isEmpty()) {
                    LOGGER.warn("Recipe replacement {} has no valid \"replaces\" id; ignored", file);
                    skipped++;
                    continue;
                }
                if (!CraftingHelper.processConditions(json, "conditions", ctx)) {
                    LOGGER.debug("Recipe replacement {} for {}: conditions not met", file, target);
                    skipped++;
                    continue;
                }
                String action = GsonHelper.getAsString(json, "action", "");
                String reason = GsonHelper.getAsString(json, "reason", "").trim();
                if (!"replace".equals(action) && !"remove".equals(action)) {
                    LOGGER.warn("Recipe replacement {} for {} has unknown action \"{}\"; ignored", file,
                            target, action);
                    skipped++;
                    continue;
                }
                if ("remove".equals(action) && reason.isEmpty()) {
                    LOGGER.warn("Recipe replacement {} would remove {} without a \"reason\"; rejected,"
                            + " the recipe stays", file, target);
                    skipped++;
                    continue;
                }
                if (applied.contains(target)) {
                    LOGGER.warn("Recipe replacement {} for {} ignored: an earlier file already replaced"
                            + " or removed it (the first file id wins)", file, target);
                    skipped++;
                    continue;
                }
                if (!all.containsKey(target)) {
                    LOGGER.debug("Recipe replacement {}: {} is not loaded; nothing to replace", file,
                            target);
                    skipped++;
                    continue;
                }

                if ("remove".equals(action)) {
                    all.remove(target);
                    applied.add(target);
                    removed++;
                    LOGGER.debug("Removed {} ({}: {})", target, file, reason);
                    continue;
                }

                if (!json.has("recipe") || !json.get("recipe").isJsonObject()) {
                    LOGGER.warn("Recipe replacement {} for {} has no \"recipe\" object; the recipe"
                            + " stays", file, target);
                    skipped++;
                    continue;
                }
                JsonObject recipeJson = json.getAsJsonObject("recipe").deepCopy();
                String unresolved = resolvePlaceholders(recipeJson);
                if (unresolved != null) {
                    LOGGER.warn("Recipe replacement {} for {}: placeholder {}{} does not name an FC"
                            + " object with an item; the recipe stays", file, target, PLACEHOLDER_PREFIX, unresolved);
                    skipped++;
                    continue;
                }
                Recipe<?> recipe;
                try {
                    recipe = RecipeManager.fromJson(target, recipeJson, ctx);
                } catch (Exception e) {
                    LOGGER.warn("Recipe replacement {} for {} does not parse ({}); the recipe stays",
                            file, target, e.toString());
                    skipped++;
                    continue;
                }
                if (recipe == null || !target.equals(recipe.getId())) {
                    LOGGER.warn("Recipe replacement {} for {} produced no recipe; the recipe stays",
                            file, target);
                    skipped++;
                    continue;
                }
                all.put(target, recipe);
                applied.add(target);
                replaced++;
                LOGGER.debug("Replaced {} ({}: {})", target, file, reason);
            } catch (Exception e) {
                LOGGER.warn("Recipe replacement {} is malformed ({}); ignored", file, e.toString());
                skipped++;
            }
        }

        LOGGER.info("Recipe replacements: {} replaced, {} removed, {} skipped ({} file(s))", replaced,
                removed, skipped, list.size());
        return new int[]{replaced, removed, skipped};
    }

    /**
     * Rewrites, in place, every string that is exactly {@code btw:<java identifier>} to the item id of that
     * FCBetterThanWolves field.
     *
     * @return the first field name that does not resolve, or null when every placeholder resolved
     */
    private static String resolvePlaceholders(JsonElement element) {
        if (element == null) return null;
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String field = placeholderField(e.getValue());
                if (field != null) {
                    ResourceLocation key = FcRefs.itemKey(field);
                    if (key == null) return field;
                    e.setValue(new JsonPrimitive(key.toString()));
                } else {
                    String unresolved = resolvePlaceholders(e.getValue());
                    if (unresolved != null) return unresolved;
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                String field = placeholderField(array.get(i));
                if (field != null) {
                    ResourceLocation key = FcRefs.itemKey(field);
                    if (key == null) return field;
                    array.set(i, new JsonPrimitive(key.toString()));
                } else {
                    String unresolved = resolvePlaceholders(array.get(i));
                    if (unresolved != null) return unresolved;
                }
            }
        }
        return null;
    }

    /** The field name of a {@code btw:<java identifier>} string, else null. */
    private static String placeholderField(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
        String s = element.getAsString();
        if (!s.startsWith(PLACEHOLDER_PREFIX)) return null;
        String field = s.substring(PLACEHOLDER_PREFIX.length());
        if (field.isEmpty() || !Character.isJavaIdentifierStart(field.charAt(0))) return null;
        for (int i = 1; i < field.length(); i++) {
            if (!Character.isJavaIdentifierPart(field.charAt(i))) return null;
        }
        return field;
    }
}
