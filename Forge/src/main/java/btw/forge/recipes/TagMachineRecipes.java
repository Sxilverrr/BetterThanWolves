package btw.forge.recipes;

import btw.forge.ProxyRegistry;
import btw.forge.compat.FcRefs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
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
 * Tag-driven FC machine recipes: data/&lt;ns&gt;/btw_machine_recipes/*.json (INTEGRATIONS #025, part E).
 *
 * <p>Format: {@code {"machine": "millstone", "input_tag": "<item tag>", "output": {"fc_item":
 * "<FCBetterThanWolves field>", "count": n}, "conditions": [...]}}. Every member of the tag that has no 1.5.2 or FC
 * id gets the millstone recipe {@code member -> count x fc_item}; mapped members keep FC's own recipes (the dandelion
 * still mills to two yellow dye, FCRecipes.java:4749; wheat to flour, :4754), and an input the millstone already
 * takes is never given a second recipe. An unknown machine or an unresolved {@code fc_item} logs a WARN and the entry
 * is skipped. Only {@code millstone} exists today.</p>
 *
 * <p>{@link #apply} runs only from RecipeBridge.injectRecipes (ServerStarting, and after /reload), never from
 * TagsUpdatedEvent: the initial tag load comes before the overworld's LevelEvent.Load restores the world's headless
 * ids (FcWorldSavedData), and ProxyRegistry.restoreHeadlessIds evicts ids handed out before it, which would key the
 * recipes to the wrong items. {@link #clear} removes every added recipe again (re-injection, server stop).</p>
 */
// FCMOD-INTEGRATION(1.20.1): recipes: item tags feed FC's millstone for members with no FC id
public final class TagMachineRecipes {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RecipeBridge");

    public static final String DIRECTORY = "btw_machine_recipes";

    private static final String MILLSTONE = "millstone";

    private static final Gson GSON = new GsonBuilder().create();

    private record Entry(ResourceLocation file, JsonObject json) {}

    /** One added millstone recipe (the FC stacks passed to AddRecipe, so RemoveRecipe finds it again). */
    private record Added(btw.modern.ItemStack out, btw.modern.ItemStack in) {}

    private static volatile List<Entry> entries = List.of();
    private static volatile ICondition.IContext context = ICondition.IContext.EMPTY;

    private static final List<Added> ADDED = new ArrayList<>();

    private TagMachineRecipes() {}

    /** Reads the machine-recipe files on every data load (registered from RecipeBridge's AddReloadListenerEvent). */
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
                    LOGGER.warn("Machine recipe {} is not a JSON object; ignored", file);
                    continue;
                }
                loaded.add(new Entry(file, e.getValue().getAsJsonObject()));
            }
            loaded.sort(Comparator.comparing(entry -> entry.file().toString()));
            entries = List.copyOf(loaded);
            context = ctx;
            LOGGER.debug("Loaded {} machine recipe file(s)", loaded.size());
        }
    }

    /**
     * Adds the millstone recipes of every loaded entry, in file-id order. Call {@link #clear} first.
     *
     * @return the number of recipes added
     */
    public static synchronized int apply() {
        List<Entry> list = entries;
        ICondition.IContext ctx = context;
        Set<Integer> inputsThisPass = new HashSet<>();
        int added = 0;

        for (Entry entry : list) {
            ResourceLocation file = entry.file();
            JsonObject json = entry.json();
            try {
                if (!CraftingHelper.processConditions(json, "conditions", ctx)) {
                    LOGGER.debug("Machine recipe {}: conditions not met", file);
                    continue;
                }
                String machine = GsonHelper.getAsString(json, "machine", "");
                if (!MILLSTONE.equals(machine)) {
                    LOGGER.warn("Machine recipe {} names unknown machine \"{}\"; ignored", file, machine);
                    continue;
                }
                String tagName = GsonHelper.getAsString(json, "input_tag", "");
                if (tagName.startsWith("#")) tagName = tagName.substring(1);
                ResourceLocation tagId = ResourceLocation.tryParse(tagName);
                if (tagId == null || tagName.isEmpty()) {
                    LOGGER.warn("Machine recipe {} has no valid \"input_tag\"; ignored", file);
                    continue;
                }
                JsonObject output = GsonHelper.getAsJsonObject(json, "output");
                String field = GsonHelper.getAsString(output, "fc_item");
                int count = Math.max(1, GsonHelper.getAsInt(output, "count", 1));
                int outId = FcRefs.legacyItemId(field);
                if (outId <= 0) {
                    LOGGER.warn("Machine recipe {}: fc_item {} does not name an FC item or block;"
                            + " ignored", file, field);
                    continue;
                }

                List<Item> members = new ArrayList<>();
                TagKey<Item> tag = TagKey.create(Registries.ITEM, tagId);
                for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                    if (holder.isBound()) members.add(holder.value());
                }
                members.sort(Comparator.comparing(item -> String.valueOf(BuiltInRegistries.ITEM.getKey(item))));

                for (Item item : members) {
                    if (ModernCookingImport.isMapped(item)) continue; // FC's own recipes stay
                    int inId = ProxyRegistry.getOrCreateLegacyId(item);
                    if (inId <= 0 || !inputsThisPass.add(inId)) continue;
                    btw.modern.ItemStack in = new btw.modern.ItemStack(inId, 1, 0);
                    if (FcCraftingManagers.millResult(in) != null) continue; // never a second recipe for an input
                    btw.modern.ItemStack out = new btw.modern.ItemStack(outId, count, 0);
                    if (FcCraftingManagers.millAdd(out, in)) {
                        ADDED.add(new Added(out, in));
                        added++;
                        LOGGER.debug("Millstone: {} (fc {}) -> {} x{} ({})",
                                BuiltInRegistries.ITEM.getKey(item), inId, field, count, file);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Machine recipe {} is malformed ({}); ignored", file, e.toString());
            }
        }

        LOGGER.info("Tag machine recipes: {} millstone recipe(s) added from {} file(s)", added,
                list.size());
        return added;
    }

    /** Removes every millstone recipe {@link #apply} added. */
    public static synchronized void clear() {
        for (Added a : ADDED) {
            FcCraftingManagers.millRemove(a.out(), a.in());
        }
        ADDED.clear();
    }
}
