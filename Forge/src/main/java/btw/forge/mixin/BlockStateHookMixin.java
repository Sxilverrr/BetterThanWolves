package btw.forge.mixin;

import btw.forge.HookPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): hook_ownership
 *
 * <p><b>Feature:</b> the 1.20.1 BlockStateBase forwarders {@code use} (BlockBehaviour.java:777-779) and
 * {@code onPlace} (:749-751), the single pass every block activation (ServerPlayerGameMode.java:343) and every
 * placement (LevelChunk.java:257; Forge's replay of captured placements, ForgeHooks.java:644) goes through, whatever
 * the block class overrides.</p>
 *
 * <p><b>Conflict with FC:</b> 4.B replaced two 1.20.1 activations the port never reached, because the 1.20.1 class
 * overrides the hook without calling the base body that BlockBehaviorMixin hooks: the anvil's 3x3 crafting station
 * (Client FCBlockAnvil.java:134-150 against AnvilBlock.java:55-63) and the carved pumpkin's and jack o'lantern's
 * onBlockAdded, which 4.B overrides "to prevent vanilla golem creation" (Common FCBlockPumpkinCarved.java:39-43,
 * Client FCBlockJackOLantern.java:54-64, against CarvedPumpkinBlock.java:44-48).</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend). The owners are {@link HookPolicy}'s; this mixin caches them per state
 * ({@link HookPolicy.Access}) and enforces the FC rows: on the server, an FC-owned hook runs FC and cancels the 1.20.1
 * body. The client keeps the 1.20.1 body as its prediction (the use packet is always sent).</p>
 *
 * <p><b>Rebalance:</b> FC-owned USE: the anvil opens FC's crafting station; an empty-handed sneak keeps 1.20.1's
 * AnvilMenu (R15); the off hand runs nothing. FC-owned PLACE: no iron or snow golem from a pumpkin or a jack
 * o'lantern, dispensers included; a jack o'lantern placed now goes out next to water, as in 4.B.</p>
 *
 * <p><b>Injection strategy:</b> {@code initCache()} TAIL caches {@link HookPolicy#decide} (never throws: a failure
 * leaves every hook UNDECIDED). Cancellable {@link Inject}s at HEAD of the {@code use} and {@code onPlace} forwarders,
 * in a mixin of its own at priority 1100 so they are applied after every default-priority mixin and another mod's
 * HEAD veto runs first (as BlockStateRandomTickMixin). They return at once unless the cached owner is FC.</p>
 *
 * <p><b>Alternative considered and rejected:</b> per-class mixins on AnvilBlock and CarvedPumpkinBlock (a new hole for
 * every future FC row, and a second routing path that ignores the policy); cancellable injects on every forwarder
 * (neighborChanged, entityInside and attack would allocate a CallbackInfo per call with no FC row to enforce).</p>
 */
// FCMOD-INTEGRATION(1.20.1): hook_ownership: per-state hook owners, and FC-owned use / onPlace enforced at the BlockStateBase forwarders
@Mixin(value = BlockBehaviour.BlockStateBase.class, priority = 1100)
public abstract class BlockStateHookMixin implements HookPolicy.Access {

    @Shadow
    protected abstract BlockState asState();

    /** The packed hook owners of this state (HookPolicy.owner), 0 = every hook UNDECIDED. */
    @Unique
    private int btw$hookOwnerBits;

    /**
     * Caches this state's hook owners once vanilla filled the cache (registry bake, Blocks.rebuildCache). Never
     * throws: on failure every hook stays UNDECIDED and the executors keep their dynamic lookup.
     */
    @Inject(method = "initCache()V", at = @At("TAIL"))
    private void btw$decideHookOwners(CallbackInfo ci) {
        int owners = 0;
        try {
            HookPolicy.Decision d = HookPolicy.decide(asState());
            if (d != null) owners = d.owners();
        } catch (Throwable t) {
            owners = 0;
            HookPolicy.warnDecideFailure(asState(), t);
        }
        btw$hookOwnerBits = owners;
    }

    @Override
    public int btw$hookOwners() {
        return btw$hookOwnerBits;
    }

    /** Block activation (ServerPlayerGameMode.useItemOn :343, StairBlock's base delegation, other mods' callers). */
    @Inject(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"), cancellable = true)
    private void btw$fcOwnedUse(Level level, Player player, InteractionHand hand, BlockHitResult hit,
                                CallbackInfoReturnable<InteractionResult> cir) {
        if (HookPolicy.owner(btw$hookOwnerBits, HookPolicy.USE) != HookPolicy.FC || !(level instanceof ServerLevel sl)) {
            return;
        }
        InteractionResult result = HookPolicy.runFcOwnedUse(asState(), sl, player, hand, hit);
        if (result != null) cir.setReturnValue(result);
    }

    /** Placement (LevelChunk.setBlockState, Forge's captured-placement replay). */
    @Inject(method = "onPlace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void btw$fcOwnedPlace(Level level, BlockPos pos, BlockState oldState, boolean moving, CallbackInfo ci) {
        if (HookPolicy.owner(btw$hookOwnerBits, HookPolicy.PLACE) != HookPolicy.FC || !(level instanceof ServerLevel sl)) {
            return;
        }
        if (HookPolicy.runFcOwnedPlace(asState(), sl, pos, oldState)) ci.cancel();
    }
}
