package btw.forge.mixin;

import btw.forge.client.FCClientPayloadHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BTW's "Obstructed" player-names level (fcEnableHardcorePlayerNames=2 in the server's BTWConfig.txt,
 * synced by FC|OP; triage CN-R1). 1.5.2 RenderLiving.renderLivingLabel normally disabled GL_DEPTH_TEST for
 * the faint first pass of a label (and its dark background), so names showed through walls; FCMOD kept
 * the depth test on when FCBetterThanWolves.AreHardcorePlayerNamesObstructed (vanilla/client
 * RenderLiving.java:512-520). The second, full-white pass was always depth-tested.
 *
 * <p>1.20.1 {@link EntityRenderer#renderNameTag} draws the same two passes: the first
 * {@code Font.drawInBatch} (ordinal 0, with the background) in {@code DisplayMode.SEE_THROUGH} unless the
 * entity is discrete (sneaking), then a NORMAL full-white pass. At level 2 the first pass is switched to
 * NORMAL, which depth-tests the faint text and the background like 1.5.2. isDiscrete() is left alone:
 * forcing it would give the faint sneaking look and drop the white pass. PlayerRenderer.renderNameTag
 * calls this method for both the scoreboard line and the name (1.5.2 RenderPlayer.java:418-448 also used
 * renderLivingLabel for both), so both are obstructed.</p>
 *
 * <p>Players only. 1.5.2 applied the rule in renderLivingLabel, i.e. to every living label, including
 * named mobs; the port limits it to players, the option's subject. Levels 0 (Displayed), 1 (Hardcore,
 * handled by FCClientPayloadHandler.NameTagRules) and -1 (no FC|OP received) leave 1.20.1 unchanged.</p>
 *
 * <p>Client mixin config only: EntityRenderer is client-only. The level is read from
 * {@link FCClientPayloadHandler.NameTagRules}, which does not load the outer handler class (and its
 * btw.modern dependencies) per frame.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNameTagMixin {

    /**
     * Decided at renderNameTag HEAD for the current call, read by the drawInBatch argument hook in the
     * same call. Name tags are drawn on the render thread only and renderNameTag does not recurse.
     */
    @Unique
    private boolean btw$depthTestNameTag;

    @Inject(method = "renderNameTag", at = @At("HEAD"))
    private void btw$checkObstructedNameTag(Entity entity, Component text, PoseStack poseStack,
                                            MultiBufferSource buffers, int packedLight, CallbackInfo ci) {
        this.btw$depthTestNameTag = entity instanceof Player
                && FCClientPayloadHandler.NameTagRules.playerNamesLevel() == 2;
    }

    @ModifyArg(method = "renderNameTag",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
                    ordinal = 0),
            index = 7)
    private Font.DisplayMode btw$obstructNameTag(Font.DisplayMode mode) {
        if (this.btw$depthTestNameTag && mode == Font.DisplayMode.SEE_THROUGH) {
            this.btw$depthTestNameTag = false;
            return Font.DisplayMode.NORMAL;
        }
        return mode;
    }
}
