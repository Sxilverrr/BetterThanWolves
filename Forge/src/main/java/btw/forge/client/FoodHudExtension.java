package btw.forge.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * Extension point on BTW's food row (INTEGRATIONS #023, BTW food bar in Forge's HUD stack).
 *
 * <p>BTW cancels vanilla's {@code food_level} overlay and draws FC's 0-60 food pips in its slot, so another mod's
 * {@code food_level} Pre/Post listeners never run. A mod that decorates the food bar (a food or fat preview, a
 * temperature tint) registers an implementation with {@link BTWHudOverlay#addFoodHudExtension} instead, from
 * client-only code. BTW's own pips and helpers are reached through {@link BTWHudOverlay#renderer()}:
 * {@link FCHudRendererBridge#pipY(int)}, {@link FCHudRendererBridge#drawFoodPreview} and
 * {@link FCHudRendererBridge#drawFatPreview}.</p>
 *
 * <p>Call order, once per frame and only while BTW draws the row (survival elements shown, GUI not hidden, player
 * alive): every {@link #renderUnder}, then the pips (tinted by {@link #foodPipTint}), then every {@link #renderOver}.
 * Extensions run in registration order.</p>
 * <ul>
 *   <li>{@link #renderUnder} runs before the pips; {@link FCHudRendererBridge#pipY(int)} still holds the previous
 *       frame's values there.</li>
 *   <li>{@link #renderOver} runs after the pips, with this frame's {@link FCHudRendererBridge#pipY(int)}.</li>
 *   <li>The first {@link #foodPipTint} that is not {@link #NO_TINT} ({@code 0xFFFFFFFF}) wins, in registration
 *       order.</li>
 *   <li>An extension that throws (any {@code Exception} or {@code LinkageError}) is removed after one WARN log
 *       line; the row keeps drawing without it.</li>
 * </ul>
 *
 * <p>Arguments: {@code barRightX} is the row's right edge ({@code screenWidth / 2 + 91}); {@code barY} is the row's
 * top ({@code screenHeight - gui.rightHeight} before BTW advances it by 10). On entry blend is enabled with the
 * default blend function, depth test is off and the shader colour is white. An implementation restores the shader
 * colour ({@code g.setColor(1, 1, 1, 1)}) and blend state it changes, and pushes/pops the pose stack inside
 * try/finally: BTW resets colour and blend after a throw but cannot repair an unbalanced pose stack.</p>
 */
// FCMOD-INTEGRATION(1.20.1): food_hud_stack: vanilla food_level listeners never see BTW's FC pips, so other mods decorate the food row through this interface instead
public interface FoodHudExtension {

    /** ARGB value of "no tint"; {@link #foodPipTint} returns it to leave the pips untinted. */
    int NO_TINT = 0xFFFFFFFF;

    /** Draws before BTW's pips (under them). {@link FCHudRendererBridge#pipY(int)} holds the previous frame here. */
    default void renderUnder(ForgeGui gui, GuiGraphics g, int barRightX, int barY, int screenWidth, int screenHeight) {}

    /** Draws after BTW's pips (over them). {@link FCHudRendererBridge#pipY(int)} holds this frame here. */
    default void renderOver(ForgeGui gui, GuiGraphics g, int barRightX, int barY, int screenWidth, int screenHeight) {}

    /**
     * ARGB colour the pips are drawn with this frame ({@link #NO_TINT} = none). The first extension that returns
     * something other than {@link #NO_TINT} wins, in registration order.
     */
    default int foodPipTint(LocalPlayer player) {
        return NO_TINT;
    }
}
