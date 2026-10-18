package btw.forge.data;

import btw.forge.compat.FcRefs;
import com.google.gson.JsonArray;
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
 * Serene Seasons data for BTW content (INTEGRATIONS #028), as plain JSON tag files that are inert without Serene
 * Seasons (every entry {@code required: false}, {@code replace: false}).
 *
 * <ul>
 *   <li>{@code #sereneseasons:summer_crops} and {@code autumn_crops}: BTW wheat seeds; {@code summer_crops} and
 *       {@code spring_crops}: BTW hemp seeds. Serene Seasons renders the seed tooltips from these item tags, and the
 *       BTW season gate names each BTW crop by its seed (SereneSeasonsSeasonGate, FcCropSeasonNames).</li>
 *   <li>{@code #sereneseasons:greenhouse_glass}: the lit BTW light bulb only (block_223), so a bulb protects the crop
 *       column below it.</li>
 *   <li>{@code data/betterthanwolves/compat_refs/sereneseasons.json}: the FC fields behind those keys, which
 *       BridgeSelfAudit checks against the live BTW ID config.</li>
 * </ul>
 *
 * <p>Keys are resolved through {@link FcRefs} by FC field, never typed: an unresolved field throws, so runData fails
 * instead of shipping a wrong key. BTW crop BLOCKS are never put in Serene Seasons' block tags: its random-tick mixin
 * would then cancel the whole FC tick of a tagged ProxyBlock, including UpdateIfBlockStays and the night reset of the
 * grown-today flag. Names no Serene Seasons class.</p>
 */
// FCMOD-INTEGRATION(1.20.1): serene_seasons: SS season tags for BTW seeds and the lit bulb as greenhouse glass, keys resolved through FcRefs
public final class SereneSeasonsTagProvider implements DataProvider {

    private static final String WHEAT_SEEDS = "fcItemWheatSeeds";
    private static final String HEMP_SEEDS = "fcItemHempSeeds";
    private static final String LIT_BULB = "fcLightBulbOn";

    private final PackOutput output;

    public SereneSeasonsTagProvider(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        ResourceLocation wheat = FcRefs.itemKey(WHEAT_SEEDS);
        ResourceLocation hemp = FcRefs.itemKey(HEMP_SEEDS);
        ResourceLocation bulb = FcRefs.blockKey(LIT_BULB);
        List<String> unresolved = new ArrayList<>();
        if (wheat == null) unresolved.add("item " + WHEAT_SEEDS);
        if (hemp == null) unresolved.add("item " + HEMP_SEEDS);
        if (bulb == null) unresolved.add("block " + LIT_BULB);
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("[BTW-Data] Serene Seasons tags: FC fields did not resolve: " + unresolved);
        }
        JsonObject manifest = FcRefs.refsManifest(List.of(LIT_BULB), List.of(WHEAT_SEEDS, HEMP_SEEDS));

        Path base = output.getOutputFolder(PackOutput.Target.DATA_PACK);
        return CompletableFuture.allOf(
                DataProvider.saveStable(cache, tag(wheat, hemp),
                        base.resolve("sereneseasons/tags/items/summer_crops.json")),
                DataProvider.saveStable(cache, tag(wheat),
                        base.resolve("sereneseasons/tags/items/autumn_crops.json")),
                DataProvider.saveStable(cache, tag(hemp),
                        base.resolve("sereneseasons/tags/items/spring_crops.json")),
                DataProvider.saveStable(cache, tag(bulb),
                        base.resolve("sereneseasons/tags/blocks/greenhouse_glass.json")),
                DataProvider.saveStable(cache, manifest,
                        base.resolve("betterthanwolves/compat_refs/sereneseasons.json")));
    }

    /** {@code {"replace": false, "values": [{"id": <id>, "required": false}, ...]}}. */
    private static JsonObject tag(ResourceLocation... ids) {
        JsonArray values = new JsonArray();
        for (ResourceLocation id : ids) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id.toString());
            entry.addProperty("required", false);
            values.add(entry);
        }
        JsonObject root = new JsonObject();
        root.addProperty("replace", false);
        root.add("values", values);
        return root;
    }

    @Override
    public String getName() {
        return "BTW Serene Seasons tags";
    }
}
