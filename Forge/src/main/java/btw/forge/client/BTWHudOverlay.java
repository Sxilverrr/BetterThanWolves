package btw.forge.client;

import btw.forge.BTWForgeMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Forge overlay that replaces vanilla's food bar with FC's HUD.
 * Delegates all rendering to {@link FCHudRendererBridge}.
 *
 * <p>INTEGRATIONS #023 (BTW food bar in Forge's HUD stack). BTW's food row is the bottom row of Forge's right-hand
 * stack. The FC pips draw in vanilla's {@code food_level} slot at {@code height - rightHeight}, and rightHeight then
 * advances by 10 as {@code ForgeGui.renderFood} does, so air bubbles, mount hearts and other mods' right-hand bars
 * stack above the pips instead of over them. The penalty and gloom text sit on top of the whole stack (the
 * {@code btw_food} above-all overlay, at {@code height - rightHeight}). Without other bars this is FC's 1.5.2 layout:
 * pips at H-39, text at H-49. Other mods decorate the row through {@link FoodHudExtension}.</p>
 */
@Mod.EventBusSubscriber(
        modid = BTWForgeMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = net.minecraftforge.api.distmarker.Dist.CLIENT
)
public class BTWHudOverlay {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Hud");

    private static final FCHudRendererBridge HUD = new FCHudRendererBridge();

    // FCMOD-INTEGRATION(1.20.1): food_hud_stack: vanilla food_level listeners never run under BTW's cancel, so food-bar mods hook the FC row here
    private static final CopyOnWriteArrayList<FoodHudExtension> EXTENSIONS = new CopyOnWriteArrayList<>();

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("btw_food", BTWHudOverlay::renderOverlay);
    }

    /**
     * Registers a food-row extension (client only). Extensions run in registration order; null and an instance
     * that is already registered are ignored. See {@link FoodHudExtension} for the call order and failure rule.
     */
    public static void addFoodHudExtension(FoodHudExtension extension) {
        if (extension != null) {
            EXTENSIONS.addIfAbsent(extension);
        }
    }

    /** The single FC HUD renderer, for extensions: {@code pipY}, the preview helpers and the penalty names. */
    public static FCHudRendererBridge renderer() {
        return HUD;
    }

    /** Replaces vanilla food bar with FC's pips in the same HUD slot. Registered on FORGE bus. */
    @Mod.EventBusSubscriber(
            modid = BTWForgeMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = net.minecraftforge.api.distmarker.Dist.CLIENT
    )
    public static class VanillaFoodCanceller {
        // FCMOD-INTEGRATION(1.20.1): food_hud_stack: HIGHEST with receiveCanceled, so the FC pips draw in the food_level slot even when another mod cancels that overlay
        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public static void onPreRenderFood(RenderGuiOverlayEvent.Pre event) {
            if (event.getOverlay() != VanillaGuiOverlay.FOOD_LEVEL.type()) return;
            // The vanilla bar never draws, whatever happens below.
            event.setCanceled(true);
            renderFoodRow(event);
        }
    }

    /**
     * Draws FC's pips as the food row of Forge's right stack and advances rightHeight past it. The row's y always
     * comes from the current rightHeight; overlays below food_level that advanced it are respected, never undone.
     */
    private static void renderFoodRow(RenderGuiOverlayEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui instanceof ForgeGui gui)) return;

        LocalPlayer player = mc.player;
        int rhBefore = gui.rightHeight;

        // FCMOD-INTEGRATION(1.20.1): food_hud_stack: vanilla food_level's guards, minus its mounted check: 1.5.2 always drew the food row (GuiIngame.java:321), so riding any LivingEntity (FC pig, horse) keeps the pips and the mount hearts stack one row above
        String hidden = null;
        if (player == null || player.isDeadOrDying()) {
            hidden = "hidden:dead";
        } else if (mc.options.hideGui) {
            hidden = "hidden:hidegui";
        } else if (mc.gameMode == null || !gui.shouldDrawSurvivalElements()) {
            hidden = "hidden:survival";
        }
        if (hidden != null) {
            FoodHudProbe.pips(hidden, rhBefore, gui.rightHeight, FoodHudExtension.NO_TINT, EXTENSIONS.size());
            return;
        }

        int w = event.getWindow().getGuiScaledWidth();
        int h = event.getWindow().getGuiScaledHeight();
        int x = w / 2 + 91;
        int y = h - gui.rightHeight;
        GuiGraphics g = event.getGuiGraphics();

        gui.setupOverlayRenderState(true, false);

        for (FoodHudExtension extension : EXTENSIONS) {
            try {
                extension.renderUnder(gui, g, x, y, w, h);
            } catch (Exception | LinkageError t) {
                dropExtension(extension, g, t);
            }
        }

        int tint = pipTint(player, g);
        if (tint != FoodHudExtension.NO_TINT) {
            g.setColor(((tint >> 16) & 0xFF) / 255F, ((tint >> 8) & 0xFF) / 255F, (tint & 0xFF) / 255F,
                    ((tint >>> 24) & 0xFF) / 255F);
        }
        HUD.renderFoodOverlay(g, x, y);
        g.setColor(1F, 1F, 1F, 1F);

        for (FoodHudExtension extension : EXTENSIONS) {
            try {
                extension.renderOver(gui, g, x, y, w, h);
            } catch (Exception | LinkageError t) {
                dropExtension(extension, g, t);
            }
        }

        g.setColor(1F, 1F, 1F, 1F);
        RenderSystem.disableBlend();
        // FCMOD-INTEGRATION(1.20.1): food_hud_stack: the FC row takes one row of the right stack, as ForgeGui.renderFood does, so the bars above it stack instead of overlapping the pips
        gui.rightHeight += 10;

        FoodHudProbe.pips("drawn", rhBefore, gui.rightHeight, tint, EXTENSIONS.size());
    }

    /** The first extension tint that is not {@link FoodHudExtension#NO_TINT}, in registration order. */
    private static int pipTint(LocalPlayer player, GuiGraphics g) {
        for (FoodHudExtension extension : EXTENSIONS) {
            try {
                int tint = extension.foodPipTint(player);
                if (tint != FoodHudExtension.NO_TINT) return tint;
            } catch (Exception | LinkageError t) {
                dropExtension(extension, g, t);
            }
        }
        return FoodHudExtension.NO_TINT;
    }

    /** Removes an extension that threw, logs it once, and resets the colour and blend state it may have left. */
    private static void dropExtension(FoodHudExtension extension, GuiGraphics g, Throwable t) {
        if (EXTENSIONS.remove(extension)) {
            LOGGER.warn("[BTWHud] food HUD extension {} threw; removed", extension.getClass().getName(), t);
        }
        g.setColor(1F, 1F, 1F, 1F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static void renderOverlay(
            ForgeGui forgeGui,
            GuiGraphics guiGraphics,
            float partialTick,
            int width,
            int height) {

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean show = player != null && !player.isDeadOrDying()
                && !mc.options.hideGui
                && mc.gameMode != null && mc.gameMode.canHurtPlayer();

        // FCMOD-INTEGRATION(1.20.1): food_hud_stack: the text sits on top of the whole right stack; the stack gives the air bubbles their own row (FC's H-49 bubble row), so the penalty text no longer has to yield to them as at GuiIngame.java:352
        int textY = height - forgeGui.rightHeight;

        if (show) {
            int textX = width / 2 + 91;

            // Penalty text + gloom (matching FC's original layout from GuiIngame)
            int sightlessTextOffset = -8;
            if (!HUD.renderPenaltyText(guiGraphics, textX, textY)) {
                sightlessTextOffset = 0;
            }
            HUD.renderGloomText(guiGraphics, textX, textY + sightlessTextOffset);
        }

        FoodHudProbe.text(forgeGui.rightHeight, show);
    }
}
