package btw.forge.mixin;

import btw.forge.ProxyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): hook_ownership (D16(b), hook delta A7)
 *
 * <p><b>Feature:</b> 1.20.1's FenceGateBlock.use toggles OPEN with flag 10 (FenceGateBlock.java:129, :137), plays the
 * wood type's sound (:141) and emits GameEvent BLOCK_OPEN / BLOCK_CLOSE (:142), so sculk sensors hear gates.</p>
 *
 * <p><b>Conflict with FC:</b> 4.B's only change to gate activation is the neighbour notification: FCBlockFenceGate
 * writes the toggle with flag 3 (Client FCBlockFenceGate.java:57, :69; the comment at :51), where 1.5.2 wrote flag 2
 * (vanilla/server BlockFenceGate.java:107, :119). Running FC's activation instead (D16(a)) would lose the game event
 * and the per-wood sounds.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend). HookPolicy rules gate USE VANILLA (ADAPTER(D16b)), so 1.20.1's use
 * runs, and this adapter adds 4.B's notify. Gate NEIGHBOR is RULED(4): gates keep 1.20.1 redstone (ruling 4).</p>
 *
 * <p><b>Rebalance:</b> none: the 1.20.1 toggle, sound and game event, plus 4.B's neighbour update after it.</p>
 *
 * <p><b>Injection strategy:</b> {@link Inject} at the RETURN of FenceGateBlock.use (its one return, :143), server
 * side, for a gate with an FC identity (today the oak gate, legacy 107): {@code Level.updateNeighborsAt}, the flag-1
 * part of 4.B's flag 3 (Forge posts its NeighborNotifyEvent there). FenceGateBlock.use always toggles, so every
 * return follows a toggle.</p>
 *
 * <p><b>Alternative considered and rejected:</b> FC owning gate USE (D16(a)), compatible with ruling 4 (FC writes keep
 * POWERED, WorldBridge's gate branch starts from the current state) but without the game event and sounds.</p>
 */
// FCMOD-INTEGRATION(1.20.1): hook_ownership: the 1.20.1 gate toggle notifies its neighbours as FCBlockFenceGate's flag-3 write did (D16(b), delta A7)
@Mixin(FenceGateBlock.class)
public abstract class FenceGateBlockMixin {

    @Inject(method = "use", at = @At("RETURN"))
    private void btw$notifyOnToggle(BlockState s, Level l, BlockPos p, Player pl, InteractionHand h,
                                    BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (!l.isClientSide && ProxyRegistry.getFcBlock(s.getBlock()) != null) l.updateNeighborsAt(p, s.getBlock());
    }
}
