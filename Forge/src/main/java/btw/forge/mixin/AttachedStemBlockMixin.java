package btw.forge.mixin;

import btw.forge.BTWLifecycle;
import btw.forge.ProxyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): attached_stems
 *
 * <p><b>Feature:</b> 1.20.1 attached stems ({@code ATTACHED_PUMPKIN_STEM} / {@code ATTACHED_MELON_STEM}): the bent
 * stem shape a StemBlock turns into once its fruit has grown, kept only while that fruit stays on its FACING side.
 * {@code AttachedStemBlock.updateShape} (AttachedStemBlock.java:38-40) reverts it to a StemBlock AGE 7 on the first
 * shape update from that side whose neighbour is not {@code Blocks.PUMPKIN} / {@code Blocks.MELON}.</p>
 *
 * <p><b>Conflict with FC:</b> both stems are FCBlockStem (legacy ids 104 / 105), whose fruit attachment is FC
 * metadata 15 (Server FCBlockStem.java:182-272), shown by WorldBridge as the attached stem facing the fruit FC
 * attached. FC owns the attachment, and the 1.20.1 revert broke it twice. (1) BTW pumpkin stems grow the fresh
 * pumpkin, FCBlockPumpkin at legacy id 1006 (FCBetterThanWolves.java:1115-1116, a ProxyBlock), not
 * {@code Blocks.PUMPKIN}, so the first shape update from the fruit's side detached every BTW pumpkin stem. (2) When
 * the fruit goes without a neighbour update coming first (a piston moving it: PistonBaseBlock.moveBlocks runs the
 * shape updates, :326 / :333, before updateNeighborsAt, :343), the revert made the stem AGE 7, which FC reads as
 * growth 14 (WorldBridge derives AGE * 2), not FC's reset to growth 7 (FCBlockStem.ValidateFruitState, :204-210),
 * so the stem re-fruited about seven growth steps early.</p>
 *
 * <p><b>Integration pattern:</b> bridge correctness, not a modern-feature integration (closest: D).</p>
 *
 * <p><b>Rebalance:</b> none. Attachment follows FC exactly: FCBlockStem.onNeighborBlockChange and its tick run
 * ValidateFruitState, which resets a stem whose fruit is gone to growth 7 (WorldBridge.setBlockMetadata: StemBlock
 * AGE 3, the 7 in the FC metadata overlay). Every removal of the fruit is followed by that neighbour update (piston
 * moves too), and the stem's own FC tick catches any other.</p>
 *
 * <p><b>Injection strategy:</b> {@code updateShape} HEAD, cancellable. For an attached stem whose block maps to an
 * FC block, an update from its FACING side answers what the vanilla path answers for a recognised fruit:
 * {@code BushBlock.updateShape}, i.e. the state if it can still survive, else air. Other sides, and every state
 * while FC is not initialised, keep the 1.20.1 rule.</p>
 *
 * <p><b>Alternative considered and rejected:</b> answering FC's reset in updateShape itself (StemBlock AGE 3 plus an
 * overlay entry): updateShape also runs on WorldGenRegion and client levels, which have no FC overlay, and it would
 * repeat ValidateFruitState's rule instead of running it. Keeping BTW pumpkin stems a StemBlock AGE 7 with the
 * attachment in the overlay only: the stem would look unattached next to its pumpkin, and 1.20.1 code that reads
 * attachment from the state (shape, cloning) would disagree with FC.</p>
 */
// FCMOD-INTEGRATION(1.20.1): attached_stems: FC owns stem-fruit attachment, so the 1.20.1 fruit-based revert of attached stems must not run on FC stems
@Mixin(AttachedStemBlock.class)
public abstract class AttachedStemBlockMixin {

    @Inject(method = "updateShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true)
    private void btw$fcOwnsFruitAttachment(BlockState state, Direction direction, BlockState neighborState,
                                           LevelAccessor level, BlockPos pos, BlockPos neighborPos,
                                           CallbackInfoReturnable<BlockState> cir) {
        if (direction != state.getValue(AttachedStemBlock.FACING) || !BTWLifecycle.isFcReady()
                || ProxyRegistry.getFcBlock((Block) (Object) this) == null) {
            return;
        }
        cir.setReturnValue(state.canSurvive(level, pos) ? state : Blocks.AIR.defaultBlockState());
    }
}
