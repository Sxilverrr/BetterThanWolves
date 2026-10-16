package btw.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.common.ForgeMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;

/**
 * Dev diagnostic for BTW's food row (INTEGRATIONS #023): logs where the row and its text land in Forge's right stack,
 * so the HUD layout can be proved from the client log without a human looking at the screen.
 *
 * <p>Inert unless {@code -Dbtw.hudProbe} or the environment variable {@code BTW_HUD_PROBE} is set (the property
 * wins), read once:</p>
 * <ul>
 *   <li>unset, empty or {@code 0}: off; every method returns at once;</li>
 *   <li>{@code 1}: one {@code [BTWHud] probe ...} INFO line whenever the layout tuple changes;</li>
 *   <li>{@code shots}: as {@code 1}, plus a screenshot once a new tuple has held for 20 frames;</li>
 *   <li>{@code throw}: as {@code 1}, plus one registered {@link FoodHudExtension} whose renderOver throws, to prove
 *       the log-once-and-remove rule.</li>
 * </ul>
 *
 * <p>Line format, H being the scaled screen height:
 * {@code [BTWHud] probe scale=<n> food=<drawn|hidden:reason> pipsTop=H-<n> rhBefore=<n> rhAfterPips=<n>
 * rhAtText=<n> textTop=H-<n> penalty=<s|-> gloom=<s|-> tint=<AARRGGBB> ext=<n> mounted=<b> air=<b>}. A frame in which
 * the food_level Pre handler did not run reports {@code food=hidden:nopre}. The probe never changes rendering (the
 * {@code throw} mode's extension aside) and runs on the render thread only.</p>
 */
// FCMOD-INTEGRATION(1.20.1): food_hud_stack: the HUD stack rule is client-only, so its proofs need a scriptable, inert-by-default layout log
final class FoodHudProbe {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Hud");

    private enum Mode { OFF, LOG, SHOTS, THROW }

    private static final Mode MODE = readMode();

    /** Frames a new tuple must hold before shots mode grabs a screenshot of it. */
    private static final int STABLE_FRAMES_FOR_SHOT = 20;

    // This frame's food row, reported by the food_level Pre handler.
    private static boolean pipsReported;
    private static String foodState = "hidden:nopre";
    private static int rhBefore;
    private static int rhAfterPips;
    private static int tint = FoodHudExtension.NO_TINT;
    private static int extCount;

    private static String lastTuple;
    private static int stableFrames;
    private static boolean shotTaken;
    private static boolean throwerRegistered;

    private FoodHudProbe() {}

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
            LOGGER.warn("[BTWHud] probe: unknown btw.hudProbe / BTW_HUD_PROBE value '{}' (use 1, shots or throw); off",
                    value);
            return Mode.OFF;
        }
        if (mode != Mode.OFF) {
            LOGGER.info("[BTWHud] probe enabled, mode={}", mode.name().toLowerCase(Locale.ROOT));
        }
        return mode;
    }

    /**
     * Records this frame's food row, from the food_level Pre handler: {@code state} is {@code drawn} or
     * {@code hidden:<reason>} (dead, hidegui, survival); {@code rhBeforeRow}/{@code rhAfterRow} are
     * ForgeGui.rightHeight before and after the row; {@code pipTint} is the ARGB tint the pips were drawn with.
     */
    static void pips(String state, int rhBeforeRow, int rhAfterRow, int pipTint, int extensions) {
        if (MODE == Mode.OFF) return;
        if (MODE == Mode.THROW && !throwerRegistered) {
            throwerRegistered = true;
            BTWHudOverlay.addFoodHudExtension(new ThrowingExtension());
            LOGGER.info("[BTWHud] probe registered a throwing food HUD extension");
        }
        pipsReported = true;
        foodState = state;
        rhBefore = rhBeforeRow;
        rhAfterPips = rhAfterRow;
        tint = pipTint;
        extCount = extensions;
    }

    /**
     * Closes the frame, from the btw_food above-all overlay: logs the layout tuple when it changed and, in shots
     * mode, screenshots a tuple that held for {@link #STABLE_FRAMES_FOR_SHOT} frames. {@code textShown} is whether
     * the overlay's guards let it draw the penalty and gloom text; {@code rhAtText} is ForgeGui.rightHeight there
     * (the text's top is H-rhAtText).
     */
    static void text(int rhAtText, boolean textShown) {
        if (MODE == Mode.OFF) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Entity vehicle = player != null ? player.getVehicle() : null;
        boolean mounted = vehicle != null && vehicle.showVehicleHealth();
        // The same test as ForgeGui.renderAir, which decides whether the bubbles take a row.
        boolean air = player != null
                && (player.isEyeInFluidType(ForgeMod.WATER_TYPE.get()) || player.getAirSupply() < 300);
        FCHudRendererBridge hud = BTWHudOverlay.renderer();
        boolean drawn = pipsReported && "drawn".equals(foodState);

        StringBuilder tuple = new StringBuilder(160);
        tuple.append("scale=").append(scale(mc.getWindow().getGuiScale()));
        tuple.append(" food=").append(pipsReported ? foodState : "hidden:nopre");
        tuple.append(" pipsTop=").append(drawn ? "H-" + rhBefore : "-");
        tuple.append(" rhBefore=").append(pipsReported ? Integer.toString(rhBefore) : "-");
        tuple.append(" rhAfterPips=").append(pipsReported ? Integer.toString(rhAfterPips) : "-");
        tuple.append(" rhAtText=").append(rhAtText);
        tuple.append(" textTop=").append(textShown ? "H-" + rhAtText : "-");
        tuple.append(" penalty=").append(textShown ? orDash(hud.lastPenaltyText()) : "-");
        tuple.append(" gloom=").append(textShown ? orDash(hud.lastGloomText()) : "-");
        tuple.append(" tint=").append(String.format(Locale.ROOT, "%08X", drawn ? tint : FoodHudExtension.NO_TINT));
        tuple.append(" ext=").append(pipsReported ? extCount : 0);
        tuple.append(" mounted=").append(mounted);
        tuple.append(" air=").append(air);
        pipsReported = false;

        String line = tuple.toString();
        if (!line.equals(lastTuple)) {
            lastTuple = line;
            stableFrames = 0;
            shotTaken = false;
            LOGGER.info("[BTWHud] probe {}", line);
            return;
        }

        if (MODE == Mode.SHOTS && !shotTaken && ++stableFrames >= STABLE_FRAMES_FOR_SHOT) {
            shotTaken = true;
            // Deferred to the end of the frame (RenderSystem.flipFrame replays the queue after the GUI is
            // blitted), where F2 also grabs; Minecraft.execute would run it at once, mid-HUD, on this thread.
            RenderSystem.recordRenderCall(() -> Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(),
                    message -> LOGGER.info("[BTWHud] probe shot {}", message.getString())));
        }
    }

    private static String scale(double guiScale) {
        return guiScale == Math.rint(guiScale) ? Integer.toString((int) guiScale) : Double.toString(guiScale);
    }

    private static String orDash(String text) {
        return text != null ? text : "-";
    }

    /** The {@code throw} mode's extension: its renderOver always throws, so BTW must log it once and remove it. */
    private static final class ThrowingExtension implements FoodHudExtension {
        @Override
        public void renderOver(ForgeGui gui, GuiGraphics g, int barRightX, int barY, int screenWidth,
                               int screenHeight) {
            throw new IllegalStateException("hud probe");
        }
    }
}
