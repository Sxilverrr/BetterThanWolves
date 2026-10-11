package btw.forge.mixin.client;

import btw.forge.client.ClientFcWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FC's client block-change notifications (MPR-6, sounds S2). 1.5.2 called them from the client world:
 * Block.ClientNotificationOfMetadataChange from WorldClient.setBlockAndMetadataAndInvalidate for every
 * server block packet (vanilla/client WorldClient.java:294-315, reached from NetClientHandler.java:624 /
 * :673), and ClientBreakBlock / ClientBlockAdded from Chunk.setBlockIDWithMetadata for every client-side id
 * change (Chunk.java:633 / :683). Nothing in the port called them, so the gear box, mill stone and bellows
 * power-on/off sounds and particles never played and nearby axles kept a stale on/off texture.
 *
 * <p>1.20.1 applies server block updates through {@link ClientLevel#setServerVerifiedBlockState} (both
 * ClientPacketListener.handleBlockUpdate and handleChunkBlocksUpdate) and every other client change,
 * including predictions and their server corrections (syncBlockState), through ClientLevel.setBlock.
 * setServerVerifiedBlockState calls Level.setBlock via super, so the two hooks never see the same change.
 * The work is in {@link ClientFcWorld#beforeClientBlockChange} / {@link ClientFcWorld#afterClientBlockChange}.</p>
 *
 * <p>Client mixin config only: ClientLevel is client-only.</p>
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Shadow @Final private BlockStatePredictionHandler blockStatePredictionHandler;

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void btw$beforeServerVerifiedBlockState(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ClientFcWorld.beforeClientBlockChange((ClientLevel) (Object) this, pos, state);
    }

    @Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"))
    private void btw$afterServerVerifiedBlockState(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ClientFcWorld.afterClientBlockChange((ClientLevel) (Object) this, pos, true);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void btw$beforeSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                    CallbackInfoReturnable<Boolean> cir) {
        ClientFcWorld.beforeClientBlockChange((ClientLevel) (Object) this, pos, state);
    }

    /**
     * Outside a prediction this is a server-driven change (BlockStatePredictionHandler.endPredictionsUpTo ->
     * syncBlockState applying the server's state over a prediction), so a META change is notified as from a
     * server packet; a predicted change only fires the break/added hooks, as 1.5.2's client Chunk did.
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void btw$afterSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                   CallbackInfoReturnable<Boolean> cir) {
        ClientFcWorld.afterClientBlockChange((ClientLevel) (Object) this, pos,
                !this.blockStatePredictionHandler.isPredicting());
    }
}
