package btw.forge.mixin;

import btw.forge.FcBlockStateAccess;
import btw.forge.FcPaceAccess;
import btw.forge.TickPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FCMOD-INTEGRATION(1.20.1): tick_ownership (random tick)
 *
 * <p><b>Feature:</b> 1.20.1 {@code BlockStateBase.randomTick}, the one call ServerLevel.tickChunk makes per selected
 * cell (ServerLevel.java:481), which other mods hook too: Serene Seasons 9.1+ injects a cancellable HEAD callback
 * there (sereneseasons.mixin.MixinBlockStateBase -> SeasonalCropGrowthHandler.onCropGrowth) that cancels crop ticks
 * out of season.</p>
 *
 * <p><b>Conflict with FC:</b> FC owns the random tick of every FC-mapped vanilla-id state ({@link TickPolicy}) and
 * cancels the 1.20.1 body to run FC's. Callbacks at the same HEAD run in mixin application order (each is inserted
 * before the original first instruction, so the mixin applied first runs first), and at equal priority that order
 * is mod load order: when BTW's callback came first it cancelled the tick and the seasonal veto never ran for FC
 * wheat, carrots, potatoes, stems, cocoa, wart, cane and cactus. Post-1.5.2 plants grew at 1.20.1 speeds, 2x to
 * 20x faster than their 4.B relatives (bamboo and kelp against reeds, sweet berries against cocoa).</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend, INTEGRATIONS #016): other mods' random-tick vetoes stay in force on
 * FC-owned plants, and post-1.5.2 plants run FC's growth (their twin's FC body) or FC's pace.</p>
 *
 * <p><b>Rebalance:</b> none for FC-mapped plants. Without such a mod nothing changes; with one, its veto applies to
 * the FC tick exactly as it does to the 1.20.1 tick. Paced post-1.5.2 plants ({@link TickPolicy#PACED}) run their
 * 1.20.1 body on {@code pace} of their random ticks and not in the End (chorus excepted), so their mean growth is
 * their 4.B relative's.</p>
 *
 * <p><b>Injection strategy:</b> {@code randomTick} HEAD, cancellable, in a mixin of its own at priority 1100, so it
 * is applied after every default-priority (1000) mixin and its callback runs after theirs: a HEAD canceller such
 * as Serene Seasons vetoes first. It reads the owners {@link BlockStateBaseTickMixin} cached at initCache
 * ({@link FcBlockStateAccess}, {@link FcPaceAccess}) and runs {@link TickPolicy#runRandom}, which also fires Forge's
 * CropGrowEvent for the plants whose 1.20.1 body fired it (Serene Seasons 9.0, crop-gating mods); a paced state
 * either cancels or falls through to its 1.20.1 body ({@link TickPolicy#pacedRollPasses}), which fires its own
 * events.</p>
 *
 * <p><b>Alternative considered and rejected:</b> keeping the dispatch in BlockStateBaseTickMixin (default priority:
 * order left to mod load order), or a higher priority on BlockStateBaseTickMixin as a whole (it would also move the
 * initCache redirect, which must not depend on other mods' initCache changes). For the pace: scaling the
 * randomTickSpeed per plant (not per state, and every other mechanic of the cell would slow too), and a per-cell
 * counter store for FC's exact reed / cactus cadence (a SavedData FC reads never see; thinning gives FC's mean).</p>
 */
// FCMOD-INTEGRATION(1.20.1): tick_ownership: FC's random-tick dispatch runs after other mods' randomTick HEAD vetoes (Serene Seasons), and paces post-1.5.2 plants to 4.B speeds
@Mixin(value = BlockBehaviour.BlockStateBase.class, priority = 1100)
public abstract class BlockStateRandomTickMixin {

    @Shadow
    protected abstract BlockState asState();

    /** Random tick (ServerLevel.tickChunk, plus direct callers such as growth accelerators and bee visits). */
    @Inject(method = "randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void btw$dispatchRandomTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        FcBlockStateAccess owners = (FcBlockStateAccess) (Object) this;
        byte owner = owners.btw$randomOwner();
        if (owner == TickPolicy.FC) {
            if (TickPolicy.runRandom(asState(), owners.btw$randomFcId(), level, pos, random)) {
                ci.cancel();
            }
        } else if (owner == TickPolicy.PACED) {
            if (!TickPolicy.pacedRollPasses((FcPaceAccess) (Object) this, level, random)) {
                ci.cancel();
            }
        }
    }
}
