package btw.forge.client;

import btw.forge.BTWNetwork;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;

import java.util.Random;

/**
 * Bridge that renders FC's custom HUD elements using modern MC's GuiGraphics.
 *
 * <p>This wraps FC's original DrawFoodOverlay / DrawPenaltyText / DrawSightlessText
 * logic (from FC's patched GuiIngame, patch.txt lines 20232-20405) and renders
 * it through Forge's overlay system. All FC state is read from
 * {@link BTWNetwork} (synced from server) — no FC code is modified.</p>
 *
 * <p>The food pip rendering uses vanilla's {@code icons.png} texture atlas
 * with the same UV coordinates FC used in 1.5.2, since FC's food pips
 * reuse vanilla's shank icon slots.</p>
 *
 * <p>Timing follows FC's GuiIngame: the hunger-shake test and the per-frame reseed of {@code rand} read the GUI
 * tick counter ({@link net.minecraft.client.gui.Gui#getGuiTicks()}), which advances once per unpaused client tick
 * like FC's {@code updateCounter} (GuiIngame.java:137, :887-894). The 20-frame exhaustion shake counts frames, as
 * FC's did (:1400-1408).</p>
 *
 * <p>Helpers for {@link FoodHudExtension}s (INTEGRATIONS #023): {@link #pipY(int)}, {@link #drawFoodPreview},
 * {@link #drawFatPreview}, {@link #hungerPenaltyName(int)} and {@link #fatPenaltyName(int)}. The single instance is
 * {@link BTWHudOverlay#renderer()}. Every helper restores the shader colour to white and leaves blend enabled with
 * the default blend function.</p>
 */
public class FCHudRendererBridge {

    private static final ResourceLocation ICONS =
            new ResourceLocation("minecraft", "textures/gui/icons.png");

    private final Random rand = new Random();
    private int foodOverlayShakeCounter = 0;

    // Track previous food level to detect exhaustion (food decrease = shake trigger)
    private int prevFoodLevel = 60;

    // FCMOD-INTEGRATION(1.20.1): food_hud_stack: where each pip landed (shake included) in the last renderFoodOverlay, so extensions draw exactly on BTW's pips
    private final int[] pipYs = new int[10];
    private int lastRowY = 0;
    private boolean rowRecorded = false;

    // Probe only (FoodHudProbe): the strings the last renderPenaltyText / renderGloomText chose, null for none.
    private String lastPenaltyText;
    private String lastGloomText;

    /**
     * Renders the FC food overlay: 10 pips using FC's 0-60 food scale.
     * Verbatim port of FC's DrawFoodOverlay from patched GuiIngame.
     */
    public void renderFoodOverlay(GuiGraphics gui, int screenX, int screenY) {
        Minecraft mc = Minecraft.getInstance();

        // FCMOD-INTEGRATION(1.20.1): food_hud_stack: FC's updateCounter advanced once per unpaused client tick and FC reseeded rand with it every frame (GuiIngame.java:137); the GUI tick counter is that clock, so the hunger shake no longer depends on frame rate
        int ticks = mc.gui.getGuiTicks();
        rand.setSeed((long) (ticks * 312871));

        int foodLevel = BTWNetwork.clientFoodLevel;
        float saturation = BTWNetwork.clientSaturation;
        int hungerPenalty = BTWNetwork.clientHungerPenalty;
        boolean hasHungerPotion = mc.player != null &&
                mc.player.hasEffect(MobEffects.HUNGER);

        int saturationPips = (int) ((saturation + 0.124F) * 4F);

        // Detect exhaustion: food level decreased since last frame
        if (foodLevel < prevFoodLevel) {
            foodOverlayShakeCounter = 20;
        }
        prevFoodLevel = foodLevel;

        if (foodOverlayShakeCounter > 0) {
            foodOverlayShakeCounter--;
        }

        for (int i = 0; i < 10; ++i) {
            int shankY = screenY;
            int shankTexOffsetX = 16;
            int bgTexOffsetX = 0;

            if (hasHungerPotion) {
                shankTexOffsetX += 36;
                bgTexOffsetX = 13;
            } else if (i < saturationPips >> 3) {
                bgTexOffsetX = 1;
            }

            // Shake when hungry
            if (hungerPenalty > 0 && ticks % (foodLevel * 5 + 1) == 0) {
                shankY = screenY + (rand.nextInt(3) - 1);
            } else if (foodOverlayShakeCounter > 0) {
                int shake = 1;
                if (rand.nextInt(2) == 0) shake = -shake;
                shankY = screenY + shake;
            }

            // FCMOD-INTEGRATION(1.20.1): food_hud_stack: record the pip's final y for pipY and the preview helpers
            pipYs[i] = shankY;

            int shankX = screenX - i * 8 - 9;

            // Background pip
            gui.blit(ICONS, shankX, shankY, 16 + bgTexOffsetX * 9, 27, 9, 9);

            // Partial saturation pip
            if (i == saturationPips >> 3 && !hasHungerPotion) {
                int partial = saturationPips % 8;
                if (partial != 0) {
                    gui.blit(ICONS, shankX + 8 - partial, shankY,
                            25 + 8 - partial, 27, 1 + partial, 9);
                }
            }

            drawShankFill(gui, shankX, shankY, i, foodLevel, shankTexOffsetX);
        }

        lastRowY = screenY;
        rowRecorded = true;
    }

    /**
     * FC's shank fill for pip {@code i}: a full shank below {@code foodLevel / 6}, the partial shank at it. Shared by
     * {@link #renderFoodOverlay} and {@link #drawFatPreview} so the redrawn shank is the pip's exact art.
     */
    private static void drawShankFill(GuiGraphics gui, int shankX, int shankY, int i, int foodLevel,
                                      int shankTexOffsetX) {
        int fullHungerPips = foodLevel / 6;

        // Full hunger pip
        if (i < fullHungerPips) {
            gui.blit(ICONS, shankX, shankY, shankTexOffsetX + 36, 27, 9, 9);
        }
        // Partial hunger pip
        else if (i == fullHungerPips) {
            int partial = foodLevel % 6;
            if (partial != 0) {
                gui.blit(ICONS, shankX + 7 - partial, shankY,
                        shankTexOffsetX + 36 + 7 - partial, 27,
                        3 + partial, 9);
            }
        }
    }

    /**
     * The y the last {@link #renderFoodOverlay} drew pip {@code index} at, shake included (pip 0 is the rightmost;
     * {@code index} is clamped to 0..9). In {@link FoodHudExtension#renderOver} this is the current frame; in
     * {@link FoodHudExtension#renderUnder} it is the previous frame. 0 before the first frame.
     */
    // FCMOD-INTEGRATION(1.20.1): food_hud_stack: extensions align with BTW's shaking pips instead of guessing the row
    public int pipY(int index) {
        return pipYs[Mth.clamp(index, 0, 9)];
    }

    /**
     * Pip {@code i}'s y on a row whose top is {@code barY}: {@code barY} plus that pip's shake from the last frame.
     * Equals {@link #pipY(int)} when {@code barY} is this frame's row (inside {@link FoodHudExtension#renderOver}).
     */
    private int previewY(int i, int barY) {
        return rowRecorded ? barY + pipYs[i] - lastRowY : barY;
    }

    /**
     * Draws the food that eating would add, on FC's 0-60 scale at 1/6-shank resolution: for each pip, the shank
     * part between {@code fromFood60} and {@code toFood60}, at {@code alpha}, with FC's shank UVs. Values are clamped
     * to 0..60; nothing is drawn unless {@code toFood60 > fromFood60}. {@code hungerTexture} selects the green
     * shank FC shows under the Hunger effect.
     */
    // FCMOD-INTEGRATION(1.20.1): food_hud_stack: a food preview drawn with FC's 1/6-shank art instead of vanilla's half-shank bar
    public void drawFoodPreview(GuiGraphics g, int barRightX, int barY, int fromFood60, int toFood60, float alpha,
                                boolean hungerTexture) {
        int from = Mth.clamp(fromFood60, 0, 60);
        int to = Mth.clamp(toFood60, 0, 60);
        if (to <= from) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1F, 1F, 1F, alpha);

        int u = (hungerTexture ? 52 : 16) + 36;
        for (int i = 0; i < 10; ++i) {
            int cur = Mth.clamp(from - 6 * i, 0, 6);
            int nxt = Mth.clamp(to - 6 * i, 0, 6);
            if (nxt <= cur) continue;

            int sx = barRightX - i * 8 - 9;
            int sy = previewY(i, barY);
            if (nxt == 6) {
                g.blit(ICONS, sx, sy, u, 27, 9, 9);
            } else {
                g.blit(ICONS, sx + 7 - nxt, sy, u + 7 - nxt, 27, 3 + nxt, 9);
            }
        }

        g.setColor(1F, 1F, 1F, 1F);
    }

    /**
     * Draws the fat (FC saturation) that eating would add: FC's fat pip background between {@code fromFat} and
     * {@code toFat} (2 fat per pip, 8 steps each, as {@link #renderFoodOverlay} computes them) at {@code alpha}, then
     * the pip's current shank again at full alpha, since the fat indicator is a background and would cover it.
     * Nothing is drawn under the Hunger effect, where FC shows no fat background.
     */
    // FCMOD-INTEGRATION(1.20.1): food_hud_stack: a fat preview that keeps FC's background-plus-shank layering
    public void drawFatPreview(GuiGraphics g, int barRightX, int barY, float fromFat, float toFat, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.hasEffect(MobEffects.HUNGER)) return;

        int from = fatSteps(fromFat);
        int to = fatSteps(toFat);
        if (to <= from) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        int foodLevel = BTWNetwork.clientFoodLevel;
        for (int i = 0; i < 10; ++i) {
            int cur = Mth.clamp(from - 8 * i, 0, 8);
            int nxt = Mth.clamp(to - 8 * i, 0, 8);
            if (nxt <= cur) continue;

            int sx = barRightX - i * 8 - 9;
            int sy = previewY(i, barY);

            g.setColor(1F, 1F, 1F, alpha);
            if (nxt == 8) {
                g.blit(ICONS, sx, sy, 25, 27, 9, 9);
            } else {
                g.blit(ICONS, sx + 8 - nxt, sy, 25 + 8 - nxt, 27, 1 + nxt, 9);
            }

            g.setColor(1F, 1F, 1F, 1F);
            drawShankFill(g, sx, sy, i, foodLevel, 16);
        }

        g.setColor(1F, 1F, 1F, 1F);
    }

    /** FC's fat-to-pip-steps conversion from DrawFoodOverlay: 8 steps per pip, 2 fat per pip. */
    private static int fatSteps(float fat) {
        return (int) ((fat + 0.124F) * 4F);
    }

    /**
     * Draws the highest-priority penalty text. Port of FC's DrawPenaltyText.
     * @return true if a penalty string was drawn
     */
    public boolean renderPenaltyText(GuiGraphics gui, int screenX, int screenY) {
        lastPenaltyText = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isDeadOrDying()) return false;

        int healthP = BTWNetwork.clientHealthPenalty;
        int hungerP = BTWNetwork.clientHungerPenalty;
        int fatP = BTWNetwork.clientFatPenalty;

        String text = null;
        if (healthP > 0 && healthP >= hungerP && healthP >= fatP) {
            text = HEALTH_PENALTY[Math.min(healthP, HEALTH_PENALTY.length - 1)];
        } else if (hungerP > 0 && hungerP >= fatP) {
            text = HUNGER_PENALTY[Math.min(hungerP, HUNGER_PENALTY.length - 1)];
        } else if (fatP > 0) {
            text = FAT_PENALTY[Math.min(fatP, FAT_PENALTY.length - 1)];
        }

        if (text != null) {
            lastPenaltyText = text;
            int w = mc.font.width(text);
            gui.drawString(mc.font, text, screenX - w, screenY, 0xFFFFFF, true);
            return true;
        }
        return false;
    }

    /**
     * Draws gloom level text. Port of FC's DrawSightlessText.
     */
    public void renderGloomText(GuiGraphics gui, int screenX, int screenY) {
        lastGloomText = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isDeadOrDying()) return;

        int gloom = BTWNetwork.clientGloomLevel;
        if (gloom <= 0) return;

        String text;
        switch (gloom) {
            case 2: text = "Dread"; break;
            case 3: text = "Terror"; break;
            default: text = "Gloom"; break;
        }

        lastGloomText = text;
        int w = mc.font.width(text);
        gui.drawString(mc.font, text, screenX - w, screenY, 0xFFFFFF, true);
    }

    /** FC's hunger penalty name for {@code level} ("Peckish" .. "Dying"); {@code level} is clamped to the table. */
    // FCMOD-INTEGRATION(1.20.1): food_hud_stack: extensions name FC's penalty levels with FC's own strings
    public static String hungerPenaltyName(int level) {
        return HUNGER_PENALTY[Mth.clamp(level, 0, HUNGER_PENALTY.length - 1)];
    }

    /** FC's fat penalty name for {@code level} ("Plump" .. "Obese"); {@code level} is clamped to the table. */
    public static String fatPenaltyName(int level) {
        return FAT_PENALTY[Mth.clamp(level, 0, FAT_PENALTY.length - 1)];
    }

    /** Probe only: the penalty string the last {@link #renderPenaltyText} drew, or null. */
    String lastPenaltyText() {
        return lastPenaltyText;
    }

    /** Probe only: the gloom string the last {@link #renderGloomText} drew, or null. */
    String lastGloomText() {
        return lastGloomText;
    }

    private static final String[] HEALTH_PENALTY = {
            "Invalid", "Hurt", "Injured", "Wounded", "Crippled", "Dying"
    };
    private static final String[] HUNGER_PENALTY = {
            "Invalid", "Peckish", "Hungry", "Famished", "Starving", "Dying"
    };
    private static final String[] FAT_PENALTY = {
            "Invalid", "Plump", "Chubby", "Fat", "Obese", "Invalid"
    };
}
