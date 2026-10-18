package btw.forge.compat.appleskin;

import btw.modern.FoodStats;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Dev diagnostic for the AppleSkin integration (INTEGRATIONS #029), following {@code FoodHudProbe} (#023): logs what
 * BTW's AppleSkin displays decided, so the integration can be proved from the client log without a human looking at
 * the screen. It names no AppleSkin type.
 *
 * <p>Inert unless {@code -Dbtw.hudProbe} or the environment variable {@code BTW_HUD_PROBE} is set (the property wins),
 * read once:</p>
 * <ul>
 *   <li>unset, empty or {@code 0}: off; every method returns at once;</li>
 *   <li>{@code 1}: one {@code [BTW-AppleSkin] probe ...} INFO line whenever a tuple changes;</li>
 *   <li>{@code shots}: as {@code 1}, plus a screenshot once a new HUD tuple has held for 20 frames;</li>
 *   <li>{@code throw}: as {@code 1}, plus {@link #maybeThrow} throws once, to prove the switch-off rule;</li>
 *   <li>anything else: one WARN, then off.</li>
 * </ul>
 *
 * <p>Line formats (H is the scaled screen height; {@code heart a>b} is ceil(health) &gt; ceil(estimated health)):</p>
 * <ul>
 *   <li>{@code [BTW-AppleSkin] probe hud hand=<MAIN|OFF|-> item=<id|-> food=<n> fat=<f> to=<n|-> fatTo=<f|->
 *       harm=<b> under=<off|w/81> thr=<1.33|0.5> mode=<hunger|fat> heart=<off|-|a>b> top=<H-n|-> alphaMax=<f>}</li>
 *   <li>{@code [BTW-AppleSkin] probe tooltip path=<marked|unmarked> item=<id> h60=<n> src=<Source> row1=<text|->
 *       row2=<text|->}</li>
 *   <li>{@code [BTW-AppleSkin] probe shot <message>}</li>
 * </ul>
 * <p>The probe never changes rendering (the {@code throw} mode aside). Render thread only; numbers are formatted with
 * {@link Locale#ROOT}.</p>
 */
// FCMOD-INTEGRATION(1.20.1): appleskin: the AppleSkin displays are client-only, so their proofs need a scriptable, inert-by-default log
final class AppleSkinProbe {

    private static final Logger LOGGER = LogManager.getLogger("BTW-AppleSkin");

    private enum Mode { OFF, LOG, SHOTS, THROW }

    private static final Mode MODE = readMode();

    /** Frames a new HUD tuple must hold before shots mode grabs a screenshot of it. */
    private static final int STABLE_FRAMES_FOR_SHOT = 20;

    /** "#.##" with a fixed decimal point: 1.33 and 0.5, as the threshold is written in the line format. */
    private static final DecimalFormat THRESHOLD_FORMAT =
            new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private static String lastHud;
    private static int stableFrames;
    private static boolean shotTaken;
    private static String lastTooltip;
    private static boolean thrown;

    private AppleSkinProbe() {}

    private static Mode readMode() {
        String value;
        try {
            value = System.getProperty("btw.hudProbe", System.getenv("BTW_HUD_PROBE"));
        } catch (SecurityException e) {
            return Mode.OFF;
        }
        if (value == null) return Mode.OFF;
        Mode mode = switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "", "0" -> Mode.OFF;
            case "1" -> Mode.LOG;
            case "shots" -> Mode.SHOTS;
            case "throw" -> Mode.THROW;
            default -> null;
        };
        if (mode == null) {
            LOGGER.warn("[BTW-AppleSkin] probe: unknown btw.hudProbe / BTW_HUD_PROBE value '{}' (use 1, shots or "
                    + "throw); off", value);
            return Mode.OFF;
        }
        if (mode != Mode.OFF) {
            LOGGER.info("[BTW-AppleSkin] probe enabled, mode={}", mode.name().toLowerCase(Locale.ROOT));
        }
        return mode;
    }

    /** True unless the probe is off; callers skip building probe-only values when it is. */
    static boolean enabled() {
        return MODE != Mode.OFF;
    }

    /**
     * In {@code throw} mode, the first call throws {@code NoSuchFieldError("btw appleskin probe")}: an AppleSkin API
     * linkage failure as the guarded handler {@code where} would meet it. A no-op in every other mode.
     */
    static void maybeThrow(String where) {
        if (MODE != Mode.THROW || thrown) return;
        thrown = true;
        LOGGER.info("[BTW-AppleSkin] probe throw mode: the {} handler throws once", where);
        throw new NoSuchFieldError("btw appleskin probe");
    }

    /**
     * Closes one frame of BTW's food row, from {@link AppleSkinHud#renderOver}: logs the HUD tuple when it changed and,
     * in shots mode, screenshots a tuple that held for {@link #STABLE_FRAMES_FOR_SHOT} frames.
     *
     * @param hand        the hand whose food is previewed, null for none
     * @param stack       that hand's stack (ignored when {@code hand} is null)
     * @param food        client FC food (0-60)
     * @param fat         client FC fat
     * @param to          previewed food after eating, -1 when no food preview is drawn
     * @param fatTo       previewed fat after eating, NaN when no fat preview is drawn
     * @param harm        the previewed food has a harmful effect
     * @param underWidth  exhaustion underlay width (0-81), -1 when AppleSkin's underlay setting is off
     * @param burningFat  FC burns fat before hunger now
     * @param heartFrom   ceil(health) of the heart hint; {@link AppleSkinHud#HEART_OFF} (setting off) or
     *                    {@link AppleSkinHud#HEART_NONE} (no hint this frame)
     * @param heartTo     ceil(estimated health) of the heart hint (when {@code heartFrom >= 0})
     * @param topFromBottom n of the heart row's top H-n, -1 when it was not captured this frame
     * @param alphaMax    AppleSkin's maxHudOverlayFlashAlpha
     */
    static void hud(@Nullable InteractionHand hand, ItemStack stack, int food, float fat, int to, float fatTo,
                    boolean harm, int underWidth, boolean burningFat, int heartFrom, int heartTo, int topFromBottom,
                    float alphaMax) {
        if (MODE == Mode.OFF) return;

        StringBuilder tuple = new StringBuilder(200);
        tuple.append("hand=").append(hand == null ? "-" : hand == InteractionHand.MAIN_HAND ? "MAIN" : "OFF");
        tuple.append(" item=").append(hand == null ? "-" : itemId(stack));
        tuple.append(" food=").append(food);
        tuple.append(" fat=").append(String.format(Locale.ROOT, "%.2f", fat));
        tuple.append(" to=").append(to < 0 ? "-" : Integer.toString(to));
        tuple.append(" fatTo=").append(Float.isNaN(fatTo) ? "-" : String.format(Locale.ROOT, "%.2f", fatTo));
        tuple.append(" harm=").append(harm);
        tuple.append(" under=").append(underWidth < 0 ? "off" : underWidth + "/81");
        float threshold = burningFat ? FoodStats.FAT_BURN_THRESHOLD : FoodStats.HUNGER_BURN_THRESHOLD;
        tuple.append(" thr=").append(THRESHOLD_FORMAT.format(threshold));
        tuple.append(" mode=").append(burningFat ? "fat" : "hunger");
        tuple.append(" heart=").append(heartFrom == AppleSkinHud.HEART_OFF ? "off"
                : heartFrom < 0 ? "-" : heartFrom + ">" + heartTo);
        tuple.append(" top=").append(topFromBottom < 0 ? "-" : "H-" + topFromBottom);
        tuple.append(" alphaMax=").append(String.format(Locale.ROOT, "%.2f", alphaMax));

        String line = tuple.toString();
        if (!line.equals(lastHud)) {
            lastHud = line;
            stableFrames = 0;
            shotTaken = false;
            LOGGER.info("[BTW-AppleSkin] probe hud {}", line);
            return;
        }

        if (MODE == Mode.SHOTS && !shotTaken && ++stableFrames >= STABLE_FRAMES_FOR_SHOT) {
            shotTaken = true;
            Minecraft mc = Minecraft.getInstance();
            // Deferred to the end of the frame, after the GUI is drawn (as FoodHudProbe and F2 do).
            RenderSystem.recordRenderCall(() -> Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(),
                    message -> LOGGER.info("[BTW-AppleSkin] probe shot {}", message.getString())));
        }
    }

    /** Logs the BTW food tooltip just added for {@code stack} when it differs from the last one logged. */
    static void tooltip(String path, ItemStack stack, BtwFoodTooltip tooltip) {
        if (MODE == Mode.OFF) return;

        String row1 = tooltip.row1Text();
        String row2 = tooltip.row2Text();
        String line = "path=" + path
                + " item=" + itemId(stack)
                + " h60=" + tooltip.food().hunger60()
                + " src=" + tooltip.food().source()
                + " row1=" + (row1.isEmpty() ? "-" : row1)
                + " row2=" + (row2.isEmpty() ? "-" : row2);
        if (!line.equals(lastTooltip)) {
            lastTooltip = line;
            LOGGER.info("[BTW-AppleSkin] probe tooltip {}", line);
        }
    }

    private static String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "-" : String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }
}
