package btw.forge.compat.appleskin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws a {@link BtwFoodTooltip} (INTEGRATIONS #029): one FC shank per 6 food units at FC's 1/6-shank resolution,
 * with row 1 (harmful effects) beside the shanks and row 2 (the eat preview, with FC's fat pip when it adds fat) below.
 *
 * <p>The shanks use the art of BTW's food row ({@code FCHudRendererBridge}, INTEGRATIONS #023): vanilla icons.png,
 * shank u=52 (u=88 for a harmful food, the green shank), background u=16 (u=133), v=27; a partial shank shows its
 * right-hand {@code 3 + units} pixels, as FC's partial pip does. They are drawn here rather than through
 * {@code FCHudRendererBridge.drawFoodPreview}, which shifts each pip by the HUD's last hunger shake. Shank 0 is the
 * rightmost, as on the HUD. Width and height come from the content.</p>
 *
 * <p>Failure rule (SR1): a throw while drawing switches the AppleSkin integration off with one ERROR, so later
 * tooltips are AppleSkin's own; the shader colour is restored either way.</p>
 */
// FCMOD-INTEGRATION(1.20.1): appleskin: FC food values drawn with FC's own shank art in AppleSkin's tooltip slot
public class BtwFoodTooltipRenderer implements ClientTooltipComponent {

    private static final ResourceLocation ICONS = new ResourceLocation("minecraft", "textures/gui/icons.png");

    private final BtwFoodTooltip tooltip;
    private final int shanks;
    private final String row1;
    private final String row2;
    private final boolean harm;
    private final boolean fatIcon;

    public BtwFoodTooltipRenderer(BtwFoodTooltip tooltip) {
        this.tooltip = tooltip;
        this.shanks = Math.max(1, (int) Math.ceil(tooltip.food().hunger60() / 6.0D));
        this.row1 = tooltip.row1Text();
        this.row2 = tooltip.row2Text();
        this.harm = BtwFoodTooltip.harmful(tooltip.food());
        this.fatIcon = tooltip.preview() != null && !tooltip.refused()
                && tooltip.preview().fatGained() > BtwFoodTooltip.MIN_FAT_SHOWN;
    }

    @Override
    public int getHeight() {
        return 10 + (row2.isEmpty() ? 0 : 10);
    }

    @Override
    public int getWidth(Font font) {
        int top = (shanks - 1) * 8 + 9 + (row1.isEmpty() ? 0 : 2 + font.width(row1));
        int bottom = row2.isEmpty() ? 0 : (fatIcon ? 11 : 0) + font.width(row2);
        return Math.max(top, bottom) + 2;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics g) {
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            int hunger60 = tooltip.food().hunger60();
            int fillU = harm ? 88 : 52;
            for (int j = 0; j < shanks; ++j) {
                int sx = x + (shanks - 1 - j) * 8;
                int units = Mth.clamp(hunger60 - 6 * j, 0, 6);
                g.blit(ICONS, sx, y, harm ? 133 : 16, 27, 9, 9);
                if (units == 6) {
                    g.blit(ICONS, sx, y, fillU, 27, 9, 9);
                } else if (units > 0) {
                    g.blit(ICONS, sx + 7 - units, y, fillU + 7 - units, 27, 3 + units, 9);
                }
            }

            if (!row1.isEmpty()) {
                g.drawString(font, row1, x + (shanks - 1) * 8 + 11, y + 1, 0xFFAAAAAA, false);
            }
            if (!row2.isEmpty()) {
                if (fatIcon) {
                    // FC's full fat pip background (FCHudRendererBridge.renderFoodOverlay)
                    g.blit(ICONS, x, y + 10, 25, 27, 9, 9);
                }
                g.drawString(font, row2, x + (fatIcon ? 11 : 0), y + 11, 0xFFAAAAAA, false);
            }
        } catch (RuntimeException | LinkageError t) {
            AppleSkinCompat.switchOff("tooltip render", t);
        } finally {
            g.setColor(1F, 1F, 1F, 1F);
        }
    }
}
