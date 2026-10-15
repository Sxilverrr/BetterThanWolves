package btw.forge.mixin;

import btw.forge.ProxyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FCMOD-INTEGRATION(1.20.1): farmland_reversion
 *
 * <p><b>Feature:</b> 1.20.1 farmland reversion. {@code FarmBlock.turnToDirt} (FarmBlock.java:94-98) is the one
 * place every 1.20.1 path turns farmland back into soil: the 1-tick survival check FarmBlock.updateShape schedules
 * when a solid block sits above it (FarmBlock.java:40-51, tick :65-70) and trampling in FarmBlock.fallOn
 * (:86-92). It places vanilla dirt.</p>
 *
 * <p><b>Conflict with FC:</b> 4.B reverts farmland to FC loose dirt (block 1011): a solid block above on a
 * neighbour change (Common FCBlockFarmlandLegacyBase.java:29-51), trampling (Server FCBlockFarmlandBase.java:80-89)
 * and dry reversion (Server FCBlockFarmlandBase.java:270-276) all set fcBlockDirtLoose. FC's neighbour rule wins
 * only when a neighbour update reaches it before the queued 1.20.1 tick; shape-only updates (structure placement,
 * flag-2 writes) and the 1.20.1 trample ended in vanilla dirt, which a BTW world never produced.</p>
 *
 * <p><b>Integration pattern:</b> bridge correctness, not a modern-feature rebalance (closest: D). The 1.20.1
 * executors keep deciding when farmland reverts; FC decides what it reverts to.</p>
 *
 * <p><b>Rebalance:</b> none. The trample odds are untouched here (1.20.1's fallOn and FC's onFallenUpon both
 * still run; that is a separate finding).</p>
 *
 * <p><b>Injection strategy:</b> {@link Inject} at HEAD of the static {@code turnToDirt(Entity, BlockState, Level,
 * BlockPos)}, cancellable. Only for {@code Blocks.FARMLAND} (DirtPathBlock.tick shares the method and keeps
 * 1.20.1 dirt) and only while FC's loose dirt is registered. The body is 1.20.1's own with the target state
 * swapped: Block.pushEntitiesUp, setBlockAndUpdate and the BLOCK_CHANGE game event, so entities, neighbours and
 * sculk sensors see the change as before.</p>
 *
 * <p><b>Alternative considered and rejected:</b> routing FarmBlock's scheduled tick to FC. FCBlockFarmlandBase's
 * updateTick is the random hydration tick (Server FCBlockFarmlandBase.java:43-57: hydrate, dry one step, or revert
 * when dry), so a survival tick under a solid block would run a hydration step and revert only farmland that was
 * already dry, and the trample path would still place vanilla dirt.</p>
 */
// FCMOD-INTEGRATION(1.20.1): farmland_reversion: 4.B farmland reverts to FC loose dirt, so 1.20.1's revert paths must place block 1011, not vanilla dirt
@Mixin(FarmBlock.class)
public abstract class FarmBlockMixin {

    /** FCBetterThanWolves.fcBlockDirtLoose (Server FCBetterThanWolves.java:1130), a ProxyBlock. */
    @Unique
    private static final int BTW$LOOSE_DIRT_ID = 1011;

    @Inject(method = "turnToDirt(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true)
    private static void btw$turnToLooseDirt(Entity entity, BlockState state, Level level, BlockPos pos,
                                            CallbackInfo ci) {
        if (level.isClientSide || !state.is(Blocks.FARMLAND)) return;
        Block looseDirt = ProxyRegistry.getModernBlock(BTW$LOOSE_DIRT_ID);
        if (looseDirt == null) return;   // FC not registered: keep 1.20.1 dirt

        // setBlockWithNotify(fcBlockDirtLoose) in 4.B: metadata 0, the ProxyBlock's default state.
        BlockState dirt = Block.pushEntitiesUp(state, looseDirt.defaultBlockState(), level, pos);
        level.setBlockAndUpdate(pos, dirt);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(entity, dirt));
        ci.cancel();
    }
}
