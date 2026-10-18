package btw.forge.compat.sereneseasons;

import btw.forge.BTWLifecycle;
import btw.forge.ProxyBlock;
import btw.forge.ProxyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The season name of a BTW ProxyBlock crop (INTEGRATIONS #028): the registry key of the seed that plants it. Serene
 * Seasons gives seeds their seasons through its item tags (and renders them as seed tooltips), so a BTW crop is in
 * season exactly when its seed is.
 *
 * <p>The map is FC's own seed table reversed: every FC item with a public int field {@code m_iCropBlockID}
 * (FCItemSeeds, FCItemSeedFood) plants that crop id. FC's {@code GetSeedItemID()} cannot be used, because BTW wheat
 * answers 0. Only crops that are ProxyBlocks are kept, the only states {@link SeasonalCropGate} asks about; with the
 * default BTW ID config that is wheat 1089 (seed item_22591) and hemp 241 (seed item_482). A crop cell with no seed of
 * its own (the wheat top 1090) takes the season of the ProxyBlock crop below it.</p>
 *
 * <p>Built once, on first use after FC init ({@link BTWLifecycle#isFcReady()}; before that every lookup answers null
 * without caching), by scanning {@code btw.modern.Item.itemsList} in ascending item id, so the lowest seed id wins
 * for a crop. Keys are this run's registry keys, so a moved BTW ID config still finds the seed (its shipped tag entry
 * may be stale; the compat-refs audit WARNs). A reflection failure logs one WARN and publishes an empty map: BTW
 * crops are then season-blind. Names no Serene Seasons class.</p>
 */
// FCMOD-INTEGRATION(1.20.1): serene_seasons: a BTW crop takes its seed's season, read from FC's own seed table
final class FcCropSeasonNames {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Compat");

    /** FC's seed-to-crop field (FCItemSeeds: public final int; FCItemSeedFood: public int). */
    private static final String CROP_FIELD = "m_iCropBlockID";

    private static final Object LOCK = new Object();

    /** ProxyBlock crop id to seed item key; null until built. */
    private static volatile Map<Integer, ResourceLocation> seeds;

    private FcCropSeasonNames() {}

    /**
     * The seed key that names the crop {@code state} at {@code pos} to the season mod, or null (not a ProxyBlock, no
     * known seed, or FC not ready yet).
     */
    static @Nullable ResourceLocation seedKeyFor(ServerLevel level, BlockPos pos, BlockState state) {
        Map<Integer, ResourceLocation> map = seeds();
        if (map == null || !(state.getBlock() instanceof ProxyBlock proxy)) return null;
        ResourceLocation key = map.get(proxy.getLegacyId());
        if (key == null && level.getBlockState(pos.below()).getBlock() instanceof ProxyBlock below) {
            key = map.get(below.getLegacyId());
        }
        return key;
    }

    /** The crop-to-seed map, built once after FC init; null before FC init. */
    private static @Nullable Map<Integer, ResourceLocation> seeds() {
        Map<Integer, ResourceLocation> map = seeds;
        if (map != null) return map;
        if (!BTWLifecycle.isFcReady()) return null;
        synchronized (LOCK) {
            map = seeds;
            if (map == null) {
                map = build();
                seeds = map;
            }
        }
        return map;
    }

    private static Map<Integer, ResourceLocation> build() {
        Map<Integer, ResourceLocation> found = new HashMap<>();
        try {
            Map<Class<?>, Optional<Field>> fields = new HashMap<>();
            btw.modern.Item[] items = btw.modern.Item.itemsList;
            for (int id = 0; id < items.length; id++) {
                btw.modern.Item fcItem = items[id];
                if (fcItem == null) continue;
                Optional<Field> field = fields.computeIfAbsent(fcItem.getClass(), FcCropSeasonNames::cropField);
                if (field.isEmpty()) continue;
                int cropId = field.get().getInt(fcItem);
                if (cropId <= 0 || found.containsKey(cropId) || ProxyRegistry.getProxy(cropId) == null) continue;
                Item mc = ProxyRegistry.getModernItem(fcItem.itemID);
                if (mc == null || mc == Items.AIR) continue;
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(mc);
                if (key != null) found.put(cropId, key);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[BTW-Compat] Serene Seasons: FC's seed table could not be read; BTW crops are "
                    + "season-blind ({})", e.toString(), e);
            return Map.of();
        }
        Map<Integer, ResourceLocation> map = Map.copyOf(found);
        LOGGER.info("[BTW-Compat] Serene Seasons: BTW crop seeds {}", describe(map));
        return map;
    }

    /** The class's public instance {@code int m_iCropBlockID}, or empty. */
    private static Optional<Field> cropField(Class<?> type) {
        try {
            Field field = type.getField(CROP_FIELD);
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) return Optional.empty();
            return Optional.of(field);
        } catch (NoSuchFieldException e) {
            return Optional.empty();
        }
    }

    /** "1089 -> betterthanwolves:item_22591, 241 -> betterthanwolves:item_482": highest crop id first, stable. */
    private static String describe(Map<Integer, ResourceLocation> map) {
        if (map.isEmpty()) return "(none)";
        return map.entrySet().stream()
                .sorted(Map.Entry.<Integer, ResourceLocation>comparingByKey(Comparator.reverseOrder()))
                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
