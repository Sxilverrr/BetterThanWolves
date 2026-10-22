package btw.forge.data;

import btw.forge.BTWForgeMod;
import btw.forge.FCLogRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The log rule's bark tags (FCLogRules.barkSpecies), written by runData into
 * {@code Forge/src/generated/resources/data/betterthanwolves/tags/blocks/bark/}: which FC bark (wood type) a 1.20.1 log
 * FC has no wood type for pays, and which logs pay none. FC's own wood type wins over every tag, so these only decide
 * class-S logs (stems, modded logs); an untagged barked log pays oak bark (logs design decision 6 (a)).
 * <ul>
 *   <li>{@code bark/oak}, {@code bark/spruce}, {@code bark/birch}, {@code bark/jungle}: only the FC four (the vanilla
 *       {@code #minecraft:<wood>_logs} tags), where a pack adds its modded relatives.</li>
 *   <li>{@code bark/acacia}, {@code bark/dark_oak}, {@code bark/mangrove}, {@code bark/cherry} (wood types 5-8,
 *       ModernWoods) and {@code bark/blood_wood} (4): empty, for modded logs. The four vanilla species are FC wood
 *       types of their own (maintainer ruling 1, 2026-10-19), so they are in no tag.</li>
 *   <li>{@code bark/none}: {@code #minecraft:crimson_stems} and {@code #minecraft:warped_stems}, no bark and no work
 *       stump (maintainer ruling 2, 2026-10-19).</li>
 * </ul>
 * Every file is {@code replace: false}, so a datapack adds to a tag without restating it.
 */
// FCMOD-INTEGRATION(1.20.1): fc_log_rules: which bark a log FC has no wood type for pays is data (bark/<wood> and bark/none block tags), not code
public final class LogBarkTagProvider implements DataProvider {

    /** The members of the non-empty bark tags; every other name in FCLogRules.BARK_TAG_NAMES is written empty. */
    private static final Map<String, List<String>> MEMBERS = Map.of(
            "oak", List.of("#minecraft:oak_logs"),
            "spruce", List.of("#minecraft:spruce_logs"),
            "birch", List.of("#minecraft:birch_logs"),
            "jungle", List.of("#minecraft:jungle_logs"));
    private static final List<String> NONE = List.of("#minecraft:crimson_stems", "#minecraft:warped_stems");

    private final PackOutput output;

    public LogBarkTagProvider(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        Path base = output.getOutputFolder(PackOutput.Target.DATA_PACK)
                .resolve(BTWForgeMod.MOD_ID + "/tags/blocks/bark");
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (String wood : FCLogRules.BARK_TAG_NAMES) {
            writes.add(DataProvider.saveStable(cache, tag(MEMBERS.getOrDefault(wood, List.of())),
                    base.resolve(wood + ".json")));
        }
        writes.add(DataProvider.saveStable(cache, tag(NONE), base.resolve("none.json")));
        return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]));
    }

    /** {@code {"replace": false, "values": [...]}}. */
    private static JsonObject tag(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        JsonObject root = new JsonObject();
        root.addProperty("replace", false);
        root.add("values", array);
        return root;
    }

    @Override
    public String getName() {
        return "BTW log bark tags";
    }
}
