package btw.forge.compat.sereneseasons;

import btw.forge.climate.BTWClimate;
import btw.forge.climate.SeasonGate;
import btw.forge.compat.BTWCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The Serene Seasons integration (INTEGRATIONS #028): Serene Seasons' live seasons reach BTW's crops, snow and ice.
 *
 * <ul>
 *   <li><b>Modern feature:</b> Serene Seasons' seasons: per-season crop fertility (its season crop tags, out-of-season
 *       behaviour 0, 1 or 2, greenhouse glass) and a seasonal temperature that decides where it snows and freezes.</li>
 *   <li><b>Conflict with FC:</b> BTW wheat and hemp are ProxyBlocks that grow by FC's daily rule, so Serene Seasons'
 *       own random-tick gate never sees them; FC's snow, ice and farmland snow reversion read FC's fixed 1.5.2 cold
 *       table and 1.20.1's unseasonal verdict.</li>
 *   <li><b>Pattern:</b> D (FC-extend) through the C1 seams. {@link SereneSeasonsSeasonGate} is the
 *       {@link SeasonGate} provider and makes every Serene Seasons decision; {@link SeasonalCropGate} asks it at FC's
 *       growth roll ({@code CropGrowEvent.Pre}, posted by {@code FcCropGrowthHooks}); {@link SereneSeasonsClimate} is
 *       the {@link BTWClimate} provider ({@code WorldBridge.IsSnowingAtPos}, {@code FCUtilsMisc.IsIKInColdBiome}).
 *       {@code SereneSeasonsTagProvider} ships the season tags for BTW's seeds and the lit bulb as greenhouse
 *       glass.</li>
 *   <li><b>One gate per crop:</b> a state Serene Seasons tags itself is gated by its own random-tick veto (FC-owned
 *       vanilla-id and twin plants included, #016); the BTW gate answers only for untagged ProxyBlock crops whose
 *       seed is tagged.</li>
 *   <li><b>Alt rejected:</b> GlitchCore events or {@code sereneseasons.api} only. The public API exposes the season
 *       but not the fertility rules, the out-of-season behaviour or the seasonal temperature, so BTW would have to
 *       copy them (Serene Seasons is All Rights Reserved).</li>
 * </ul>
 *
 * <p>Serene Seasons' fertility and climate classes are internal ({@code sereneseasons.init},
 * {@code sereneseasons.season}; identical in 9.1.0.3 and 9.3.0.27). Each feature is therefore probed by reflection
 * that never initializes a class, and a failed probe switches off only that feature with one INFO line: the legacy
 * 9.0.0.46 line has no {@code ModFertility.isCrop} or GlitchCore config, so its own {@code CropGrowEvent} gate (every
 * crop, by block name) applies, while its {@code SeasonHooks} still drives the climate.</p>
 *
 * <p>SR1: only {@code BTWCompat} calls {@link #init}, by a direct {@code invokestatic} inside its
 * {@code isLoaded(SERENE_SEASONS)} branch. This runs during mod construction, before FC init, so it only probes and
 * registers; the seed names resolve lazily in {@link FcCropSeasonNames}. No GlitchCore event is touched.</p>
 */
// FCMOD-INTEGRATION(1.20.1): serene_seasons: Serene Seasons' crop seasons and seasonal cold reach BTW's own crops, snow and ice through the SeasonGate and BTWClimate seams
public final class SereneSeasonsCompat {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Compat");

    private static final String MOD_FERTILITY = "sereneseasons.init.ModFertility";
    private static final String MOD_CONFIG = "sereneseasons.init.ModConfig";
    private static final String SEASON_HOOKS = "sereneseasons.season.SeasonHooks";

    private static final String LEGACY_REASON =
            "no ModFertility.isCrop / GlitchCore config: legacy line, its own CropGrowEvent gate applies";

    private SereneSeasonsCompat() {}

    /**
     * Sets the {@link SeasonGate} and {@link BTWClimate} providers and registers the {@code CropGrowEvent.Pre}
     * listener (HIGH) on the Forge bus, each only when its probe passes. Called only by {@code BTWCompat.init}.
     */
    public static void init(IEventBus modBus) {
        String ver = BTWCompat.version(BTWCompat.SERENE_SEASONS);
        String cropsOff = probeFertility();
        String climateOff = probeClimate();

        if (cropsOff == null) {
            SeasonGate.setProvider(new SereneSeasonsSeasonGate());
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, false, BlockEvent.CropGrowEvent.Pre.class,
                    SeasonalCropGate::onCropGrowPre);
        }
        if (climateOff == null) {
            BTWClimate.setProvider(new SereneSeasonsClimate());
        }

        LOGGER.info("[BTW-Compat] Serene Seasons {}: BTW crop seasons {}, BTW climate {}", ver,
                cropsOff == null ? "on" : "off (" + cropsOff + ")",
                climateOff == null ? "on" : "off (" + climateOff + ")");
    }

    /**
     * Null when the fertility API {@link SereneSeasonsSeasonGate} calls exists: the static
     * {@code ModFertility.isCrop(BlockState)} and {@code isCropFertile(String, Level, BlockPos)}, and the static
     * {@code ModConfig.fertility} whose type has the instance fields {@code boolean seasonalCrops} and
     * {@code int outOfSeasonCropBehavior}. Otherwise the reason, for the INFO line.
     */
    private static @Nullable String probeFertility() {
        try {
            ClassLoader loader = SereneSeasonsCompat.class.getClassLoader();
            Class<?> fertility = Class.forName(MOD_FERTILITY, false, loader);
            Method isCrop = fertility.getMethod("isCrop", BlockState.class);
            Method isCropFertile = fertility.getMethod("isCropFertile", String.class, Level.class, BlockPos.class);
            if (!isStaticReturning(isCrop, boolean.class)) return "ModFertility.isCrop has an unexpected shape";
            if (!isStaticReturning(isCropFertile, boolean.class)) {
                return "ModFertility.isCropFertile has an unexpected shape";
            }
            Class<?> config = Class.forName(MOD_CONFIG, false, loader);
            Field fertilityConfig = config.getField("fertility");
            if (!Modifier.isStatic(fertilityConfig.getModifiers())) return "ModConfig.fertility is not static";
            Class<?> configType = fertilityConfig.getType();
            if (!isInstanceField(configType.getField("seasonalCrops"), boolean.class)
                    || !isInstanceField(configType.getField("outOfSeasonCropBehavior"), int.class)) {
                return "ModConfig.fertility has an unexpected shape";
            }
            return null;
        } catch (Throwable t) {
            return LEGACY_REASON;
        }
    }

    /**
     * Null when {@link SereneSeasonsClimate}'s call exists: the static
     * {@code SeasonHooks.coldEnoughToSnowSeasonal(LevelReader, Holder, BlockPos)} returning boolean. Otherwise the
     * reason.
     */
    private static @Nullable String probeClimate() {
        try {
            Class<?> hooks = Class.forName(SEASON_HOOKS, false, SereneSeasonsCompat.class.getClassLoader());
            Method cold = hooks.getMethod("coldEnoughToSnowSeasonal", LevelReader.class, Holder.class, BlockPos.class);
            return isStaticReturning(cold, boolean.class)
                    ? null : "SeasonHooks.coldEnoughToSnowSeasonal has an unexpected shape";
        } catch (Throwable t) {
            return "no SeasonHooks.coldEnoughToSnowSeasonal(LevelReader, Holder, BlockPos)";
        }
    }

    private static boolean isStaticReturning(Method m, Class<?> returnType) {
        return Modifier.isStatic(m.getModifiers()) && m.getReturnType() == returnType;
    }

    private static boolean isInstanceField(Field f, Class<?> type) {
        return !Modifier.isStatic(f.getModifiers()) && f.getType() == type;
    }
}
