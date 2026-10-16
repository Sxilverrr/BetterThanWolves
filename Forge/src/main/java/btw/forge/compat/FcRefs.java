package btw.forge.compat;

import btw.forge.BTWLifecycle;
import btw.forge.ItemStackHelper;
import btw.forge.ProxyRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves BTW content by its {@code FCBetterThanWolves} field name instead of a hand-typed
 * {@code betterthanwolves:block_<id>} / {@code item_<id>} key (INTEGRATIONS #020, SR2).
 *
 * <p>BTW's registry keys carry FC's numeric ids, and those ids come from the server's BTW ID config
 * ({@code BTWConfig.txt}, FC {@code ParseID}). A key written into code or data is only right for the default config;
 * the field name is right for every config. Every compat integration names BTW content through this class.</p>
 *
 * <p>Patterns followed: {@code RemovedContent.resolveSuccessor} (misses are never cached) and
 * {@code BTWJeiPlugin.fcStack} (a Block field's 1.20.1 item). FC classes are not on the bridge's compile classpath,
 * so the field is read by reflection; its value is a {@link btw.modern.Block} or {@link btw.modern.Item}.</p>
 *
 * <p><b>When it is valid:</b> FC builds its content at RegisterEvent BLOCKS ({@link BTWLifecycle#isFcReady()}), in
 * every run, datagen included (LOAD_REGISTRIES is a GATHER state). {@link #block} works from then on;
 * {@link #item} for a block's {@code ProxyBlockItem} works after RegisterEvent ITEMS. In practice: from
 * FMLCommonSetupEvent on, or inside GatherDataEvent / {@code DataProvider.run} in runData. Before FC init every
 * lookup answers null, -1 or EMPTY without logging or caching, so an early caller cannot poison a later lookup.</p>
 *
 * <p>Thread safety: only concurrent maps and sets. Only successful reflective reads are cached (FC's static fields
 * are final in practice once FC init has run); the 1.20.1 side is looked up on every call, because the item of a
 * ProxyBlock appears only after RegisterEvent ITEMS.</p>
 */
// FCMOD-INTEGRATION(1.20.1): mod-compat: compat code and data name BTW content by FC field, so a moved BTW ID config never strands it
public final class FcRefs {

    private static final Logger LOGGER = LogManager.getLogger("BTW-FcRefs");

    /** FC's core class; its static fields hold every BTW block and item (not on the compile classpath). */
    private static final String FC_CORE = "net.minecraft.src.btw.core.FCBetterThanWolves";

    /** Classpath folder of the datagen-written compat manifests (one {@code <name>.json} per integration). */
    private static final String MANIFEST_DIR = "/data/betterthanwolves/compat_refs/";

    private static volatile Class<?> fcCore;

    /** Successful field reads only; a miss is retried on the next call. */
    private static final Map<String, Object> VALUES = new ConcurrentHashMap<>();

    /** Field names whose miss was already logged (once per name). */
    private static final Set<String> MISSES_LOGGED = ConcurrentHashMap.newKeySet();

    /** Manifest names whose malformed content was already logged (once per name). */
    private static final Set<String> MANIFESTS_WARNED = ConcurrentHashMap.newKeySet();

    /**
     * One BTW reference a datagen-written compat manifest names: the FC field, whether it is a block (else an item),
     * the key written at datagen time, and the manifest that names it.
     */
    public record Expectation(String fcField, boolean block, ResourceLocation writtenKey, String usedBy) {}

    private FcRefs() {}

    // ---- FC side ----

    /**
     * The value of the public static field {@code FCBetterThanWolves.<name>}, or null before FC init, when the field
     * does not exist, or when it is still null after FC init (the last two WARN once per name).
     */
    public static @Nullable Object fcField(String name) {
        if (name == null || !BTWLifecycle.isFcReady()) return null;
        Object cached = VALUES.get(name);
        if (cached != null) return cached;
        String problem;
        try {
            Field field = fcCore().getField(name);
            if (!Modifier.isStatic(field.getModifiers())) {
                problem = "not a static field";
            } else {
                Object value = field.get(null);
                if (value != null) {
                    VALUES.put(name, value);
                    return value;
                }
                problem = "the field is null after FC init";
            }
        } catch (NoSuchFieldException e) {
            problem = "no such public field";
        } catch (Throwable t) {
            problem = t.toString();
        }
        if (MISSES_LOGGED.add(name)) {
            LOGGER.warn("[BTW-FcRefs] FCBetterThanWolves.{} did not resolve ({})", name, problem);
        }
        return null;
    }

    /** The FC block held by the field, or null (not resolved, or not a block field). */
    public static @Nullable btw.modern.Block fcBlock(String name) {
        return fcField(name) instanceof btw.modern.Block block ? block : null;
    }

    /** The FC item held by the field, or null (not resolved, or not an item field). */
    public static @Nullable btw.modern.Item fcItem(String name) {
        return fcField(name) instanceof btw.modern.Item item ? item : null;
    }

    /** The FC block id ({@code blockID}) of a block field, or -1. */
    public static int legacyBlockId(String name) {
        btw.modern.Block block = fcBlock(name);
        return block != null ? block.blockID : -1;
    }

    /**
     * The FC item id of the field: {@code itemID} (256 + ParseID) for an item field, {@code blockID} for a block field
     * (1.5.2 keeps a block's item under its block id), or -1.
     */
    public static int legacyItemId(String name) {
        Object value = fcField(name);
        if (value instanceof btw.modern.Item item) return item.itemID;
        if (value instanceof btw.modern.Block block) return block.blockID;
        return -1;
    }

    // ---- 1.20.1 side ----

    /** The registered 1.20.1 block of a block field (a ProxyBlock for ids 175 and up), or null. */
    public static @Nullable Block block(String name) {
        int id = legacyBlockId(name);
        if (id < 0) return null;
        Block block = ProxyRegistry.getModernBlock(id);
        return block == null || block == Blocks.AIR ? null : block;
    }

    /**
     * The registered 1.20.1 item of the field, never {@link Items#AIR} (null instead). An item field maps through its
     * {@code itemID}; a block field through the {@code ProxyBlockItem} registered under its block id, falling back to
     * the block's item form.
     */
    public static @Nullable Item item(String name) {
        Object value = fcField(name);
        Item item = null;
        if (value instanceof btw.modern.Item fcItem) {
            item = ProxyRegistry.getModernItem(fcItem.itemID);
        } else if (value instanceof btw.modern.Block fcBlock) {
            item = ProxyRegistry.getModernItem(fcBlock.blockID);
            if (item == null || item == Items.AIR) {
                Block block = ProxyRegistry.getModernBlock(fcBlock.blockID);
                item = block != null ? block.asItem() : null;
            }
        }
        return item == null || item == Items.AIR ? null : item;
    }

    /** The live registry key of {@link #block(String)}, or null. */
    public static @Nullable ResourceLocation blockKey(String name) {
        Block block = block(name);
        return block != null ? ForgeRegistries.BLOCKS.getKey(block) : null;
    }

    /** The live registry key of {@link #item(String)}, or null. */
    public static @Nullable ResourceLocation itemKey(String name) {
        Item item = item(name);
        return item != null ? ForgeRegistries.ITEMS.getKey(item) : null;
    }

    /**
     * A 1.20.1 stack of the field's content, with FC's damage converted the way every FC stack is
     * ({@link ItemStackHelper#fcIdToMcStack}); EMPTY when the field does not resolve.
     */
    public static ItemStack stack(String name, int count, int damage) {
        int id = legacyItemId(name);
        if (id < 0) return ItemStack.EMPTY;
        return ItemStackHelper.fcIdToMcStack(id, count, damage);
    }

    // ---- datagen manifests ----

    /**
     * The manifest a compat datagen provider writes to {@code data/betterthanwolves/compat_refs/<name>.json}:
     * {@code {"blocks":{field:key},"items":{field:key}}}, with the keys this run's FC ids give. Throws
     * {@link IllegalStateException} listing every field that does not resolve, so runData fails instead of shipping
     * a wrong key. Iteration order of the collections is the file order.
     */
    public static JsonObject refsManifest(Collection<String> blockFields, Collection<String> itemFields) {
        JsonObject blocks = new JsonObject();
        JsonObject items = new JsonObject();
        List<String> unresolved = new ArrayList<>();
        for (String field : blockFields) {
            ResourceLocation key = blockKey(field);
            if (key == null) unresolved.add("block " + field);
            else blocks.addProperty(field, key.toString());
        }
        for (String field : itemFields) {
            ResourceLocation key = itemKey(field);
            if (key == null) unresolved.add("item " + field);
            else items.addProperty(field, key.toString());
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("[BTW-FcRefs] compat manifest fields did not resolve (FC ready: "
                    + BTWLifecycle.isFcReady() + "): " + unresolved);
        }
        JsonObject root = new JsonObject();
        root.add("blocks", blocks);
        root.add("items", items);
        return root;
    }

    /**
     * The expectations of the compat manifest {@code <name>.json} on the classpath ({@link #refsManifest} format),
     * blocks first, each with {@code usedBy = name}. Empty when the manifest is absent; empty with one WARN when it is
     * malformed.
     */
    public static List<Expectation> readManifest(String name) {
        String path = MANIFEST_DIR + name + ".json";
        try (InputStream in = openResource(path)) {
            if (in == null) return List.of();
            JsonObject root;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            List<Expectation> out = new ArrayList<>();
            readSection(root, "blocks", true, name, out);
            readSection(root, "items", false, name, out);
            return List.copyOf(out);
        } catch (Throwable t) {
            if (MANIFESTS_WARNED.add(name)) {
                LOGGER.warn("[BTW-FcRefs] compat manifest {} is malformed or unreadable and is ignored ({})", path,
                        t.toString());
            }
            return List.of();
        }
    }

    private static void readSection(JsonObject root, String section, boolean block, String name,
                                    List<Expectation> out) {
        JsonElement element = root.get(section);
        if (element == null || element.isJsonNull()) return;
        if (!element.isJsonObject()) throw new JsonParseException("\"" + section + "\" is not an object");
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            ResourceLocation key = ResourceLocation.tryParse(entry.getValue().getAsString());
            if (key == null) {
                throw new JsonParseException("\"" + section + "\"." + entry.getKey() + " is not a resource location");
            }
            out.add(new Expectation(entry.getKey(), block, key, name));
        }
    }

    /** The key this run's BTW ID config gives the expectation's field, or null when it does not resolve. */
    public static @Nullable ResourceLocation liveKey(Expectation e) {
        return e.block() ? blockKey(e.fcField()) : itemKey(e.fcField());
    }

    // ---- internals ----

    private static Class<?> fcCore() throws ClassNotFoundException {
        Class<?> core = fcCore;
        if (core == null) {
            core = Class.forName(FC_CORE);
            fcCore = core;
        }
        return core;
    }

    /** LegacySounds.openSoundsJson pattern: the class's own resource first, then its loader without the slash. */
    private static @Nullable InputStream openResource(String path) {
        InputStream in = FcRefs.class.getResourceAsStream(path);
        if (in == null) {
            ClassLoader loader = FcRefs.class.getClassLoader();
            if (loader != null) in = loader.getResourceAsStream(path.substring(1));
        }
        return in;
    }
}
