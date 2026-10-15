package btw.forge.mixin;

import btw.forge.TickPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FCMOD-INTEGRATION(1.20.1): tick_ownership (lava)
 *
 * <p><b>Feature:</b> 1.20.1 lava fluid random tick. ServerLevel.tickChunk random-ticks the fluid state of
 * the selected cell after the block state (ServerLevel.java:484-487; FluidState.java:87-89), and
 * {@code LavaFluid.randomTick} (LavaFluid.java:60-96) starts fires above and beside lava.</p>
 *
 * <p><b>Conflict with FC:</b> FC owns lava's random tick: {@link TickPolicy} routes every lava block state to
 * FCBlockLavaStationary (legacy id 11, the id of all settled 4.B lava) from the block path, which adds BTW's
 * direct ignition ({@code GetCanBeSetOnFireDirectly}) to the 1.5.2 fire start. The fluid path would also run
 * 1.20.1 fire spread for the same selection, and LiquidBlock.randomTick forwards to it too
 * (LiquidBlock.java:75-77); before this change every selected lava cell ran two 1.20.1 fire walks.</p>
 *
 * <p><b>Integration pattern:</b> bridge correctness, not a modern-feature integration (closest: D).</p>
 *
 * <p><b>Rebalance:</b> none. One FC lava tick per selection, as in 4.B; /gamerule doFireTick is applied by
 * TickPolicy at dispatch (maintainer decision D1).</p>
 *
 * <p><b>Injection strategy:</b> {@code LavaFluid.randomTick} HEAD, cancelled when the lava block state at the
 * position has random owner FC ({@link TickPolicy#fcOwnsRandomTick}), i.e. exactly when FC's lava tick ran (or
 * runs) from the block path. A lava state whose tick decision failed keeps owner VANILLA: its block path runs
 * LiquidBlock.randomTick, which forwards here, and both 1.20.1 fluid paths then run as in 1.20.1 instead of no
 * lava tick at all. Fluid flow ticks (ServerLevel.tickFluid) are a separate queue and are not touched.</p>
 *
 * <p><b>Alternative considered and rejected:</b> clearing lava's fluid random-tick flag
 * ({@code LavaFluid.isRandomlyTicking}). LiquidBlock.isRandomlyTicking derives the lava block's own 1.20.1 flag
 * from it (LiquidBlock.java:71-73), so whenever TickPolicy leaves lava unmanaged (FC not initialised, or no
 * FC lava block) lava would stop random-ticking altogether instead of falling back to 1.20.1. Cancelling only
 * the body keeps that fallback and leaves the flag other code reads untouched.</p>
 */
// FCMOD-INTEGRATION(1.20.1): tick_ownership: FC lava ticks once per selection from the block path, so the fluid path must not also run 1.20.1 fire spread
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {

    @Inject(method = "randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void btw$cancelFluidRandomTick(Level level, BlockPos pos, FluidState state, RandomSource random,
                                           CallbackInfo ci) {
        if (TickPolicy.fcOwnsRandomTick(level.getBlockState(pos))) {
            ci.cancel();
        }
    }
}
