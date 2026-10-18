package btw.forge.compat.coldsweat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.List;

/**
 * The JSON shapes of Cold Sweat's data entries that BTW builds at runtime (INTEGRATIONS #030), written with Gson only.
 * {@link ColdSweatCompat} decodes them with Cold Sweat's own codecs, so this class names no Cold Sweat type and stays
 * loadable on its own.
 *
 * <p>Shapes, read from the codecs of Cold Sweat 2.4.3.2 (identical in 2.4.1):</p>
 * <ul>
 *   <li>Block temperature: {@code {"block":{"blocks":[...],"state":{...}},"temperature":t,"units":"f","range":r,
 *       "max_effect":m}}. {@code block} is one BlockRequirement; {@code blocks} takes block ids and {@code #tags}.</li>
 *   <li>Block state ({@code BlockRequirement$StateRequirement}): a map of property name to a boolean, an
 *       {@code {"min","max"}} integer range, a string, or a list of strings; a list matches when
 *       {@code state.getValue(prop).toString()} equals one element, so it also works for an IntegerProperty.</li>
 *   <li>Insulator: {@code {"type":"armor"|"item","item":{"items":[id]},"insulation":{"cold":c,"heat":h}}}. The jar's
 *       key is {@code heat}; the online documentation's {@code hot} is silently dropped by the lenient decoder.</li>
 *   <li>Food: {@code {"item":{"items":[id]},"temperature":t,"duration":d}}.</li>
 *   <li>Fuel: {@code {"type":"hearth"|"boiler","item":{"items":[id]},"fuel":n}}.</li>
 * </ul>
 */
// FCMOD-INTEGRATION(1.20.1): Cold Sweat: BTW's climate rows become Cold Sweat data in Cold Sweat's own JSON shapes, decoded by its codecs
final class ColdSweatJson {

    /** The number of FC metadata values, 0-15 ({@code ProxyBlock.META}). */
    static final int META_VALUES = 16;

    /** The name of the metadata property every BTW heat-source block carries ({@code ProxyBlock.META}, ProxyBlock.java:59). */
    static final String META_PROPERTY = "meta";

    private ColdSweatJson() {}

    /**
     * A block-temperature entry for {@code selectors} (block ids or {@code #tags}), limited to {@code state} when it is
     * not null.
     */
    static JsonObject blockTemp(List<String> selectors, @Nullable JsonObject state, double temperature, String units,
                                double range, double maxEffect) {
        JsonArray blocks = new JsonArray();
        for (String selector : selectors) blocks.add(selector);
        JsonObject block = new JsonObject();
        block.add("blocks", blocks);
        if (state != null) block.add("state", state);
        JsonObject root = new JsonObject();
        root.add("block", block);
        root.addProperty("temperature", temperature);
        root.addProperty("units", units);
        root.addProperty("range", range);
        root.addProperty("max_effect", maxEffect);
        return root;
    }

    /**
     * The state requirement for a set of FC metadata values: null for all 16 (no requirement), a {@code {"min","max"}}
     * range when they are contiguous, otherwise the list of values as strings (the hibachi's 4-7 and 12-15, the odd metas
     * of the lava-receiving blocks).
     *
     * @throws IllegalArgumentException when {@code metas} is empty or holds a value above 15: an empty list would match
     *                                  every state, so callers skip such a group instead
     */
    static @Nullable JsonObject metaState(BitSet metas) {
        int lo = metas.nextSetBit(0);
        if (lo < 0 || metas.length() > META_VALUES) {
            throw new IllegalArgumentException("metadata set " + metas + " is empty or outside 0-15");
        }
        int hi = metas.length() - 1;
        int count = metas.cardinality();
        if (count == META_VALUES) return null;
        JsonObject state = new JsonObject();
        if (count == hi - lo + 1) {
            JsonObject bounds = new JsonObject();
            bounds.addProperty("min", lo);
            bounds.addProperty("max", hi);
            state.add(META_PROPERTY, bounds);
        } else {
            JsonArray values = new JsonArray();
            for (int meta = lo; meta >= 0; meta = metas.nextSetBit(meta + 1)) values.add(Integer.toString(meta));
            state.add(META_PROPERTY, values);
        }
        return state;
    }

    /** The state requirement {@code {"lit":true}}: only lit states count. */
    static JsonObject litState() {
        JsonObject state = new JsonObject();
        state.addProperty("lit", true);
        return state;
    }

    /** An insulator entry: {@code type} is {@code armor} (worn) or {@code item} (sewn into armour). */
    static JsonObject insulator(String type, ResourceLocation item, double cold, double heat) {
        JsonObject insulation = new JsonObject();
        insulation.addProperty("cold", cold);
        insulation.addProperty("heat", heat);
        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        root.add("item", items(item));
        root.add("insulation", insulation);
        return root;
    }

    /** A food entry: eating {@code item} adds {@code temperature} to the eater's base temperature for {@code duration} ticks. */
    static JsonObject food(ResourceLocation item, double temperature, int duration) {
        JsonObject root = new JsonObject();
        root.add("item", items(item));
        root.addProperty("temperature", temperature);
        root.addProperty("duration", duration);
        return root;
    }

    /** A fuel entry: one {@code item} gives {@code fuel} units to a Cold Sweat device of {@code type}. */
    static JsonObject fuel(String type, ResourceLocation item, int fuel) {
        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        root.add("item", items(item));
        root.addProperty("fuel", fuel);
        return root;
    }

    /** The item requirement {@code {"items":[id]}}. */
    private static JsonObject items(ResourceLocation item) {
        JsonArray ids = new JsonArray();
        ids.add(item.toString());
        JsonObject requirement = new JsonObject();
        requirement.add("items", ids);
        return requirement;
    }
}
