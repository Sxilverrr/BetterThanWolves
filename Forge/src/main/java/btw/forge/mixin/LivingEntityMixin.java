package btw.forge.mixin;

import btw.forge.PlayerBridge;
import btw.forge.WorldBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link LivingEntity} movement calculations to apply FC overrides:
 * <ul>
 *   <li>Vanilla food effect suppression for foods FC itself fed (FC ItemFood)</li>
 *   <li>Jump gating (too hungry/weak to jump)</li>
 *   <li>Jump exhaustion (custom FC values)</li>
 *   <li>Ladder gating (extreme health penalty)</li>
 *   <li>Swim gating (#12): prevent swimming up when CanSwim() is false</li>
 *   <li>Air supply decrease hook for soulforged helm override</li>
 *   <li>Gradual air recovery (FC replaces vanilla instant recovery)</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Shadow protected boolean jumping;

    // NO getBlockSpeedFactor hook: FC GetMovementModifier is applied ONLY to the on-ground input
    // speed by PlayerMixin.btw$applyBlockMovementModifier (Player.getSpeed); Block.getSpeedFactor
    // must stay vanilla (see BlockMixin/ProxyBlock) -- it scales carried momentum every tick, so
    // applying the modifier there (or here as well) compounds into the sprint-jump "flying" bug.

    // Suppress vanilla FoodProperties effects exactly when FC fed this eat (PlayerBridge.fcFedForEat, the same
    // test FoodDataMixin.btw$eatItem uses for the nutrition): the FC ItemFood's onEaten already ran its 1.5.2
    // onFoodEaten effect (vanilla/server ItemFood.java:72-82: FC food poisoning on raw meat, ...) via
    // ItemStackMixin.btw$finishUsing, so the modern effect (raw chicken hunger, rotten flesh hunger, ...) would
    // apply a SECOND time. Everything FC did not feed keeps its vanilla effects -- modern-only foods such as
    // pufferfish, and any non-player eater. Golden apple, spider eye and poisonous potato are 1.5.2 ItemFoods
    // since FD-R1, so FC applies their 1.5.2 effect once. The old test (any FC item at the legacy id) also
    // cancelled vanilla effects for foods FC never fed.
    @Inject(method = "addEatEffect", at = @At("HEAD"), cancellable = true)
    private void btw$addEatEffect(net.minecraft.world.item.ItemStack stack,
                                  net.minecraft.world.level.Level level,
                                  LivingEntity entity, CallbackInfo ci) {
        if (PlayerBridge.fcFedForEat(stack.getItem(), entity)) {
            ci.cancel();
        }
    }

    // NO getSpeed hook here: Player overrides LivingEntity.getSpeed without calling super
    // (Player.java:1442-1444), so an injection here never ran for players, and FC puppets don't use
    // MC travel (Proxy*.travel are no-ops; FC applies its own getSpeedModifier in fc.onUpdate()).
    // FC land penalty = MOVEMENT_SPEED attribute (FCGameplayBridge.onPlayerTick); FC block movement
    // modifier = PlayerMixin.btw$applyBlockMovementModifier.

    // ================================================================
    // FC jump gating — cannot jump when too hungry/weak
    // ================================================================

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void btw$gateJump(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer sp) {
            PlayerBridge pb = PlayerBridge.getOrCreate(sp);
            pb.syncFromReal();
            if (!pb.CanJump()) {
                ci.cancel();
            }
        }
    }

    // ================================================================
    // FC jump exhaustion — custom values (0.2F normal, 1.0F sprinting)
    // ================================================================

    @Inject(method = "jumpFromGround", at = @At("TAIL"))
    private void btw$jumpExhaustion(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer sp) {
            PlayerBridge pb = PlayerBridge.getOrCreate(sp);
            pb.AddExhaustionForJump();
        }
    }

    // ================================================================
    // FC ladder gating — cannot climb at extreme health penalty
    // ================================================================

    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void btw$gateLadder(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer sp) {
            PlayerBridge pb = PlayerBridge.getOrCreate(sp);
            pb.syncFromReal();
            if (pb.GetHealthPenaltyLevel() >= 4) {
                cir.setReturnValue(false);
            }
        }
    }

    // ================================================================
    // FC swim gating (#12) — prevent swimming up when CanSwim() is false
    // ================================================================

    /**
     * Prevents the player from swimming upward when {@code CanSwim()} returns false.
     * In MC 1.20.1, swimming upward happens in {@code aiStep()} when the player
     * presses jump while in water (the {@code jumping} field is true and the entity
     * is in water). When FC says the player cannot swim (too heavy from armor or
     * health too low), we suppress the upward motion by zeroing the Y component
     * of the entity's delta movement when the player is trying to jump in water.
     *
     * @see btw.modern.EntityPlayer#CanSwim()
     */
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void btw$gateSwimming(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer sp) {
            // Only act when the player is in water and trying to jump (swim up)
            if (sp.isInWater() && this.jumping) {
                PlayerBridge pb = PlayerBridge.getOrCreate(sp);
                pb.syncFromReal();
                if (!pb.CanSwim()) {
                    // Suppress upward motion — the player sinks instead of swimming up.
                    // We zero out any positive Y velocity so they cannot ascend in water.
                    Vec3 motion = sp.getDeltaMovement();
                    if (motion.y > 0) {
                        sp.setDeltaMovement(motion.x, 0.0, motion.z);
                    }
                } else {
                    // FC extra swimming exhaustion: when the player CAN swim and is
                    // actively moving upward in water, add 0.025F exhaustion per tick.
                    // This makes swimming up more costly than horizontal swimming.
                    Vec3 motion = sp.getDeltaMovement();
                    if (motion.y > 0) {
                        pb.AddExhaustionWithoutVisualFeedback(0.025F);
                    }
                }
            }
        }
    }

    // ================================================================
    // FC air supply decrease — soulforged helm hook
    // ================================================================

    /**
     * Hook: route player air-supply decrease to FC. The behavior (soulforged-helm
     * Respiration bonus, etc.) lives in {@link btw.modern.EntityPlayer#decreaseAirSupply(int)};
     * this mixin only forwards the call and returns FC's result.
     */
    @Inject(method = "decreaseAirSupply", at = @At("HEAD"), cancellable = true)
    private void btw$decreaseAirSupply(int currentAir, CallbackInfoReturnable<Integer> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayer sp)) return;

        PlayerBridge pb = PlayerBridge.getOrCreate(sp);
        pb.syncFromReal();
        cir.setReturnValue(pb.decreaseAirSupply(currentAir));
    }

    // ================================================================
    // FC gradual air recovery — replaces vanilla instant recovery
    // ================================================================

    /**
     * Tracks whether this entity was submerged in the previous tick,
     * so we can detect the moment the player surfaces.
     */
    @Unique
    private boolean btw$wasInWaterLastTick = false;

    /**
     * FC replaces vanilla's instant air recovery with a gradual system:
     * when the player surfaces, a 20-tick countdown starts. After the
     * countdown expires, air recovers at 10 units per tick until full.
     * This is tracked via {@code PlayerBridge.m_iAirRecoveryCountdown}.
     *
     * <p>Injected at the tail of {@code baseTick()} so it runs after
     * vanilla's own air supply logic. When the player is out of water
     * and air is below max, vanilla would set air to {@code air + 1}
     * per tick. We override that by clamping air to the FC-calculated
     * value instead.</p>
     */
    @Inject(method = "baseTick", at = @At("TAIL"))
    private void btw$gradualAirRecovery(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayer sp)) {
            btw$wasInWaterLastTick = self.isInWater();
            return;
        }

        PlayerBridge pb = PlayerBridge.getOrCreate(sp);
        int maxAir = sp.getMaxAirSupply(); // typically 300
        int currentAir = sp.getAirSupply();
        boolean inWaterNow = sp.isInWater();

        if (inWaterNow) {
            // Player is submerged — reset recovery state
            btw$wasInWaterLastTick = true;
            // Reset countdown so it starts fresh when they surface
            pb.m_iAirRecoveryCountdown = 20;
            return;
        }

        // Player is NOT in water
        if (btw$wasInWaterLastTick && !inWaterNow) {
            // Just surfaced — start the 20-tick delay
            pb.m_iAirRecoveryCountdown = 20;
        }
        btw$wasInWaterLastTick = false;

        if (currentAir >= maxAir) {
            // Air is full, nothing to do
            pb.m_iAirRecoveryCountdown = 0;
            return;
        }

        // Air is below max and player is out of water — apply FC's
        // gradual recovery instead of vanilla's per-tick +1.
        if (pb.m_iAirRecoveryCountdown > 0) {
            // Still in the delay window — undo vanilla's +1 recovery
            // by clamping air back down. Vanilla incremented it by 1
            // in baseTick(); we reverse that.
            pb.m_iAirRecoveryCountdown--;
            // Prevent air from recovering during the countdown
            // (vanilla may have already added some this tick)
            int preVanillaAir = currentAir - 1;
            if (preVanillaAir < 0) preVanillaAir = 0;
            sp.setAirSupply(preVanillaAir);
        } else {
            // Countdown expired — recover 10 units per tick (FC rate)
            // Vanilla already added 1, so we add 9 more to reach 10 total
            int newAir = currentAir + 9;
            if (newAir > maxAir) newAir = maxAir;
            sp.setAirSupply(newAir);
        }
    }
}
