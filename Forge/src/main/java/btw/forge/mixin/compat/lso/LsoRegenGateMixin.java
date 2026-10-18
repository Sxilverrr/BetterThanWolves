package btw.forge.mixin.compat.lso;

import btw.forge.compat.lso.LsoRegenGate;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FCMOD-INTEGRATION(1.20.1): lso (regeneration gate, INTEGRATIONS #031)
 *
 * <p><b>Feature:</b> Legendary Survival Overhaul's custom health regeneration, which replaces natural regeneration
 * under LSO's default config: every {@code customHealthRegenTickRate} ticks (200) it heals up to
 * {@code customHealthRegenRate}, capped by the player's limb health, and charges
 * {@code customHealthRegenFoodExhaustion} (6.0) exhaustion per point healed.</p>
 *
 * <p><b>Conflict with FC:</b> it heals whenever vanilla food is above 0, which is FC food 3/60 and up, while BTW heals
 * only above 24/60 ({@code FoodStats.REGEN_FOOD_THRESHOLD}). The cost already reaches FC hunger through the hunger
 * contract (#021: FoodData exhaustion is routed to FC), but the threshold does not.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): LSO's regeneration stays whole and only asks BTW's food gate
 * ({@link LsoRegenGate#blocks}) first.</p>
 *
 * <p><b>Rebalance:</b> none to LSO's rate or cost. A blocked step neither heals nor costs hunger; LSO resets its timer
 * before it calls the method ({@code BodyDamageCapability.tickUpdate}), so the next try comes one interval later.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the LSO class ({@code @Pseudo}, {@code remap = false}, so it needs
 * no refmap entry). Without LSO the config's plugin ({@code btw.forge.compat.CompatMixinPlugin}) drops it before Mixin
 * looks the target up, so nothing is logged; {@code @Pseudo} alone would not be silent, since Mixin 0.8.5's
 * {@code ClassInfo.forName} WARNs {@code Error loading class} with a ClassNotFoundException before it checks
 * {@code @Pseudo}. {@code @Inject} at HEAD, cancellable, {@code require = 0},
 * on {@code private void applyCustomHealthRegeneration(Lnet/minecraft/world/entity/player/Player;)V} (javap of
 * 1.20.1-2.4.7, mapped dev jar and SRG production jar alike; the name is LSO's own and the descriptor uses only an
 * unobfuscated class name). The mixin also adds the marker {@link LsoRegenGate.Applied}, which {@code LsoCompat}
 * checks at server start together with the method, and WARNs when a later LSO renamed it. The gate logic lives
 * outside this package, which Mixin will not let other code load.</p>
 *
 * <p><b>Alternative considered and rejected:</b> the naturalRegeneration gamerule (LSO gates its regeneration on its
 * own config field, {@code Config$Baked}, not on the rule); cancelling the whole PlayerTickEvent for LSO (that would
 * stop LSO's limbs and thirst too). No event exists here (Protocol 4, SR15).</p>
 */
// FCMOD-INTEGRATION(1.20.1): lso: LSO's custom regeneration heals only where BTW's own heal would (FC food above 24/60)
@Pseudo
@Mixin(targets = "sfiomn.legendarysurvivaloverhaul.common.capabilities.bodydamage.BodyDamageCapability", remap = false)
public abstract class LsoRegenGateMixin implements LsoRegenGate.Applied {

    /** BTW's food gate at the head of LSO's regeneration step. */
    @Inject(method = "applyCustomHealthRegeneration(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void btw$fcFoodGate(Player player, CallbackInfo ci) {
        if (LsoRegenGate.blocks(player)) ci.cancel();
    }
}
