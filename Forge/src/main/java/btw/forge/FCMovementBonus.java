package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * BTW's block movement modifier ({@link btw.modern.Block#GetMovementModifier}: 1.2 on hard
 * surfaces, 1.0 on soil/grass, per-block overrides down to 0.5) applied the way FlowerChild
 * applied it: to the movement <em>input</em> speed (EntityLiving.getSpeedModifier multiplied
 * {@code landMovementFactor} by GetMovementModifier), gated on {@code onGround} and keyed on the
 * block the feet rest on.
 *
 * <p>The bridge previously routed this modifier through {@code Block.getSpeedFactor()}
 * (BlockMixin / ProxyBlock). MC 1.20.1 multiplies {@code getSpeedFactor()} into the carried
 * {@code deltaMovement} <em>every tick</em> in {@code Entity.move()} — so a factor &gt; 1.0
 * compounds into momentum. On the ground that is bounded (~+32%), but while airborne
 * (sprint-jumping over a hard block) the retention product becomes {@code 0.91 × 1.2 = 1.092
 * &gt; 1}, turning vanilla's momentum decay into geometric growth: the "practically flying"
 * bug. Applying the bonus to {@code getSpeed()} instead makes it a bounded top-speed bonus
 * that never enters carried momentum (airborne horizontal accel uses {@code getFlyingSpeed()},
 * not {@code getSpeed()}), exactly matching FC.</p>
 *
 * <p>Applied by PlayerMixin.btw$applyBlockMovementModifier (Player.getSpeed, both sides): one
 * common hook, so client-authoritative LocalPlayer movement and the ServerPlayer compute the same
 * value and don't rubber-band.</p>
 */
public final class FCMovementBonus {

    private FCMovementBonus() {}

    /**
     * Returns FC's full GetMovementModifier for the supporting block (1.2 hard surfaces; 0.5 leaves,
     * 0.8 sand/farmland/loose snow/vegetation; 1.0 soil/ice/...), or 1.0 when airborne/unmapped.
     * Metadata-dependent overrides (aesthetic dung/packed earth, dirt/sand/gravel slabs) currently see
     * a null World and fall back to 1.0 on both sides.
     */
    public static float getBlockBelowSpeedBonus(LivingEntity self) {
        if (self == null || !self.onGround()) {
            return 1.0F;
        }
        try {
            // 1.5.2 getSpeedModifier:3187-3229 reads the block the feet rest on (feet - 0.03, 'same calc used
            // for step sound') and falls back to the blocks under the bbox edges if that block has no
            // collision box. 1.20.1's equivalent is the supporting block: Entity.getOnPos()
            // (mainSupportingBlockPos, else floor(y - 1e-5)). The old y-0.5 probe read the block UNDER a
            // bottom slab/carpet (stone-slab path over dirt gave 1.0 instead of FC's 1.2).
            BlockPos below = self.getOnPos();
            btw.modern.Block fcBlock = ProxyRegistry.getFcBlock(self.level().getBlockState(below).getBlock());
            if (fcBlock == null) {
                return 1.0F;
            }
            float modifier = fcBlock.GetMovementModifier(null, 0, 0, 0);
            return modifier > 0.0F ? modifier : 1.0F;
        } catch (Throwable t) {
            return 1.0F;
        }
    }

    /**
     * The FC health/hunger/fat/gloom movement modifier (GetLandMovementModifier ==
     * GetJumpingHorizontalMovementModifier, Modern-Common EntityPlayer.java:538-548) as currently
     * carried by the player's MOVEMENT_SPEED attribute modifier {@link FCGameplayBridge#FC_PENALTY_UUID}
     * (MULTIPLY_TOTAL, amount = m - 1). The server reconciles it every tick
     * (FCGameplayBridge.onPlayerTick) and vanilla attribute sync mirrors it onto the LocalPlayer,
     * so both sides read the same value without a packet. 1.0 when no penalty is active.
     */
    public static float getSyncedPenaltyModifier(Player player) {
        if (player == null) {
            return 1.0F;
        }
        try {
            AttributeInstance speedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr == null) {
                return 1.0F;
            }
            AttributeModifier penalty = speedAttr.getModifier(FCGameplayBridge.FC_PENALTY_UUID);
            if (penalty == null) {
                return 1.0F;
            }
            float modifier = (float) (1.0D + penalty.getAmount());
            return modifier > 0.0F && modifier < 1.0F ? modifier : 1.0F;
        } catch (Throwable t) {
            return 1.0F;
        }
    }
}
