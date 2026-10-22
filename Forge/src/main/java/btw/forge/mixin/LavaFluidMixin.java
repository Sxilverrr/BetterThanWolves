package btw.forge.mixin;

import btw.forge.LavaRules;
import btw.forge.TickPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
 *
 * <p>FCMOD-INTEGRATION(1.20.1): hook_ownership (lava pillows; hook design P3(i)(b), D14(b)). <b>Feature:</b> lava
 * flowing down onto a water block becomes stone ({@code LavaFluid.spreadTo}, LavaFluid.java:168-182, the stone at
 * :173). <b>Conflict with FC:</b> 4.B makes FC's lava pillow there instead (vanilla/server BlockFlowing.java:167-178,
 * FCMOD :171-175); see {@link LavaRules}. <b>Pattern:</b> D, 1.20.1's flow places 4.B's block. <b>Rebalance:</b>
 * none beyond 4.B (the pillow drops nothing). <b>Injection strategy:</b> {@link ModifyArg} of the state passed to
 * the one {@code ForgeEventFactory.fireFluidPlaceBlockEvent} call in spreadTo (javap of LavaFluid.spreadTo, offset 59;
 * the other two calls are in randomTick), so FluidPlaceBlockEvent listeners see the pillow and can still replace it.
 * Only a stone argument is mapped ({@link LavaRules#flowOntoWater}); a state another mod's injector already
 * substituted is kept. The call is a Forge method, so its {@code @At} is {@code remap = false}. <b>Alternatives
 * considered and rejected:</b> a {@code @Redirect} of the Blocks.STONE read (exclusive: it fails against another
 * mod's redirect of the same read), and a FluidPlaceBlockEvent listener (the event cannot tell this placement from
 * any other fluid placement of stone, and it would replace a state other listeners already chose).</p>
 *
 * <p>FCMOD-INTEGRATION(1.20.1): hook_ownership (no fluid displaces lava). <b>Feature:</b> 1.20.1 lets water flow into
 * lava whose height is at least 4/9 ({@code LavaFluid.canBeReplacedWith}, LavaFluid.java:143-145; its only caller is
 * FlowingFluid.canSpreadTo, FlowingFluid.java:392-393, through FluidState.canBeReplacedWith). <b>Conflict with
 * FC:</b> 1.5.2 never let a fluid displace lava (vanilla/server BlockFlowing.java:443-447). 1.20.1's own contact rule
 * hid the difference, because every lava cell water touched hardened at once; 4.B leaves falling lava unhardened
 * ({@link LavaRules}), so a water stream would replace the touched cell of a lava fall, run down the column and be
 * capped by a pillow when the lava above fell onto it. <b>Pattern:</b> D, 1.5.2's displacement rule on 1.20.1's flow.
 * <b>Rebalance:</b> none. <b>Injection strategy:</b> HEAD of canBeReplacedWith (declared by LavaFluid, SRG
 * {@code m_5486_}), answering false. Vanilla already answers false for every fluid that is not water, so only water's
 * flow into lava changes; buckets do not ask it (BucketItem uses BlockState.canBeReplaced). <b>Alternatives considered
 * and rejected:</b> letting falling lava harden (4.B never hardens it, BlockFluid.java:338-351), and a check in
 * FlowingFluid.canSpreadTo (it serves every fluid pair, where the lava class answers only for lava).</p>
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

    // FCMOD-INTEGRATION(1.20.1): hook_ownership: lava flowing down onto water makes 4.B's lava pillow, not 1.20.1's stone (BlockFlowing.java:169-175)
    @ModifyArg(method = "spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/event/ForgeEventFactory;fireFluidPlaceBlockEvent(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
                    remap = false),
            index = 3)
    private BlockState btw$lavaOntoWaterPillow(BlockState vanilla) {
        return LavaRules.flowOntoWater(vanilla);
    }

    // FCMOD-INTEGRATION(1.20.1): hook_ownership: no fluid displaces lava, as in 1.5.2 (vanilla/server BlockFlowing.java:443-447), so the falling lava 4.B leaves unhardened stays lava beside water
    @Inject(method = "canBeReplacedWith(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void btw$noFluidDisplacesLava(FluidState lava, BlockGetter level, BlockPos pos, Fluid incoming,
                                          Direction direction, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
