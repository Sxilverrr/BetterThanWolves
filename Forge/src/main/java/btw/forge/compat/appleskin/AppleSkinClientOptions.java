package btw.forge.compat.appleskin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.IConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AppleSkin's own client settings, read so that every BTW display that stands in for an AppleSkin one obeys the same
 * switch (INTEGRATIONS #029).
 *
 * <p>AppleSkin keeps its settings in a {@code ForgeConfigSpec} registered as a CLIENT config through
 * {@code ModLoadingContext} (AppleSkin.java:32-35), under the {@code client} section. This class reads that spec
 * through fmlcore's {@link ConfigTracker} by key, so no AppleSkin class is ever named:
 * {@code ConfigTracker.INSTANCE.configSets().get(CLIENT)}, the {@link ModConfig} whose mod id is {@code appleskin},
 * then {@code spec.getValues().get("client.<key>")}. The spec is looked up once; the values are read at use time
 * ({@link ForgeConfigSpec.ConfigValue#get()} is a live cache), so a setting AppleSkin changes at runtime is seen at
 * once.</p>
 *
 * <p>A setting that cannot be read (no spec, a renamed key, a wrong type, a config not loaded yet) answers AppleSkin's
 * own default (every switch on; flash alpha 0.65), with one WARN per key. Render thread and client tick only.</p>
 */
// FCMOD-INTEGRATION(1.20.1): appleskin: BTW's food tooltip, HUD preview, underlay, heart hint and F3 line replace AppleSkin's, so they follow AppleSkin's own switches
final class AppleSkinClientOptions {

    private static final Logger LOGGER = LogManager.getLogger("BTW-AppleSkin");

    private static final String APPLESKIN = "appleskin";

    /** AppleSkin's config section; every key lives under it (its ModConfig.CATEGORY_CLIENT). */
    private static final String SECTION = "client.";

    /** AppleSkin's default for maxHudOverlayFlashAlpha (its ModConfig, range 0-1). */
    private static final float DEFAULT_FLASH_ALPHA = 0.65F;

    /** True once the spec lookup ran; {@link #spec} is then AppleSkin's spec or null for good. */
    private static volatile boolean searched;
    @Nullable
    private static volatile ForgeConfigSpec spec;

    /** Keys whose failed read was already logged (one WARN per key). */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private AppleSkinClientOptions() {}

    /** showFoodValuesInTooltip: food values in the tooltip while Shift is held. */
    static boolean showFoodValuesInTooltip() {
        return bool("showFoodValuesInTooltip");
    }

    /** showFoodValuesInTooltipAlways: food values in the tooltip without Shift. */
    static boolean showFoodValuesInTooltipAlways() {
        return bool("showFoodValuesInTooltipAlways");
    }

    /** showSaturationHudOverlay: the saturation (BTW: fat) overlay and its preview. */
    static boolean showSaturationHudOverlay() {
        return bool("showSaturationHudOverlay");
    }

    /** showFoodValuesHudOverlay: the held-food preview on the food bar. */
    static boolean showFoodValuesHudOverlay() {
        return bool("showFoodValuesHudOverlay");
    }

    /** showFoodValuesHudOverlayWhenOffhand: the HUD previews also for food in the off hand. */
    static boolean showFoodValuesHudOverlayWhenOffhand() {
        return bool("showFoodValuesHudOverlayWhenOffhand");
    }

    /** showFoodExhaustionHudUnderlay: the exhaustion bar behind the food bar. */
    static boolean showFoodExhaustionHudUnderlay() {
        return bool("showFoodExhaustionHudUnderlay");
    }

    /** showFoodStatsInDebugOverlay: the food line in the F3 screen. */
    static boolean showFoodStatsInDebugOverlay() {
        return bool("showFoodStatsInDebugOverlay");
    }

    /** showFoodHealthHudOverlay: the estimated-health hint on the heart row. */
    static boolean showFoodHealthHudOverlay() {
        return bool("showFoodHealthHudOverlay");
    }

    /** showVanillaAnimationsOverlay: overlays shake with vanilla's low-health heart jitter. */
    static boolean showVanillaAnimationsOverlay() {
        return bool("showVanillaAnimationsOverlay");
    }

    /** maxHudOverlayFlashAlpha: the flashing previews' alpha at their most visible point (0-1). */
    static float maxHudOverlayFlashAlpha() {
        String key = "maxHudOverlayFlashAlpha";
        Object value = read(key);
        if (value instanceof Number number) return number.floatValue();
        warnOnce(key, DEFAULT_FLASH_ALPHA);
        return DEFAULT_FLASH_ALPHA;
    }

    /**
     * Whether AppleSkin shows food values in a tooltip right now: its exact rule,
     * {@code (showFoodValuesInTooltip && shift) || showFoodValuesInTooltipAlways} (TooltipOverlayHandler.java:322).
     */
    static boolean tooltipShown() {
        return (showFoodValuesInTooltip() && Screen.hasShiftDown()) || showFoodValuesInTooltipAlways();
    }

    private static boolean bool(String key) {
        Object value = read(key);
        if (value instanceof Boolean b) return b;
        warnOnce(key, Boolean.TRUE);
        return true;
    }

    /** The current value of AppleSkin's {@code client.<key>}, or null when it cannot be read. */
    @Nullable
    private static Object read(String key) {
        try {
            ForgeConfigSpec s = spec();
            if (s == null) return null;
            Object entry = s.getValues().get(SECTION + key);
            if (entry instanceof ForgeConfigSpec.ConfigValue<?> configValue) {
                return configValue.get();
            }
            return null;
        } catch (RuntimeException e) {
            // e.g. IllegalStateException from ConfigValue.get before the config file is loaded
            return null;
        }
    }

    @Nullable
    private static ForgeConfigSpec spec() {
        if (searched) return spec;
        ForgeConfigSpec found = null;
        Map<ModConfig.Type, Set<ModConfig>> sets = ConfigTracker.INSTANCE.configSets();
        Set<ModConfig> client = sets != null ? sets.get(ModConfig.Type.CLIENT) : null;
        if (client != null) {
            for (ModConfig config : client) {
                if (!APPLESKIN.equals(config.getModId())) continue;
                IConfigSpec<?> configSpec = config.getSpec();
                if (configSpec instanceof ForgeConfigSpec forgeSpec) {
                    found = forgeSpec;
                    break;
                }
            }
        }
        spec = found;
        searched = true;
        return found;
    }

    private static void warnOnce(String key, Object defaultValue) {
        if (WARNED.add(key)) {
            LOGGER.warn("[BTW-AppleSkin] cannot read AppleSkin option {}; using its default {}", key, defaultValue);
        }
    }
}
