package btw.forge.mixin;

import btw.forge.PlayerBridge;
import btw.forge.ProxyItem;
import btw.forge.ProxyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link Player} methods to apply FC overrides:
 * <ul>
 *   <li>Per-object {@link PlayerBridge} storage ({@link PlayerBridge.Holder}); bridge marked dead on removal</li>
 *   <li>Dig speed / tool effectiveness (mining)</li>
 *   <li>Bed sleeping disabled</li>
 *   <li>Mid-air block placement restriction</li>
 *   <li>Eating restriction (hunger potion; the FC food meter decides "needs food" on the server)</li>
 *   <li>Every causeFoodExhaustion goes to FC under FC's rule: armour weight, invulnerable exemption (#021)</li>
 *   <li>1.5.2 exhaustion amounts at Player's own sites: jump once, walking and swimming per metre, melee hit and
 *       hurt 0.3 (jumpFromGround, checkMovementStatistics, attack, actuallyHurt)</li>
 *   <li>Blasting oil detonation on damage (combat #18)</li>
 *   <li>Melee damage modifier from penalties (combat #20)</li>
 *   <li>FC block movement modifier on ground (getSpeed) and FC penalty while airborne (getFlyingSpeed)</li>
 *   <li>FC step sounds: low-health grunt, soulforged boots (playStepSound)</li>
 *   <li>No vanilla burp where FC's onEaten owns the eat (eat)</li>
 * </ul>
 */
@Mixin(Player.class)
public abstract class PlayerMixin implements PlayerBridge.Holder {

    // ================================================================
    // PlayerBridge storage — bound to THIS player object (see PlayerBridge.Holder)
    // ================================================================

    /**
     * This player's FC bridge. Identity-bound (a respawned ServerPlayer, which 1.20.1 gives the
     * dead player's entity id, and the singleplayer LocalPlayer, which shares its ServerPlayer's
     * id, each get their own), collected together with the player, and volatile so the
     * double-checked creation in PlayerBridge.getOrCreate publishes it safely across threads.
     */
    @Unique
    private volatile PlayerBridge btw$playerBridge;

    @Override
    public PlayerBridge btw$getPlayerBridge() {
        return this.btw$playerBridge;
    }

    @Override
    public void btw$setPlayerBridge(PlayerBridge bridge) {
        this.btw$playerBridge = bridge;
    }

    /**
     * Marks this player's bridge dead when the player leaves the world for good, as 1.5.2 does:
     * logout (ServerConfigurationManager.java:280 removeEntity -> World.java:1410 setDead) and the
     * death timer (EntityLiving.java:733 setDead) -- 1.20.1 PlayerList.remove (UNLOADED_WITH_PLAYER),
     * LivingEntity.tickDeath (KILLED) and PlayerList.respawn (DISCARDED) all end here (Player.java:1274).
     * FC AI holding the bridge as entityToAttack/attackTarget then drops it via isEntityAlive instead of
     * chasing a removed player frozen at its last position (PlayerBridge.syncFromReal never derives
     * isDead). CHANGED_DIMENSION is skipped: portal/teleport travel revive()s the same player right
     * after (ServerPlayer.changeDimension); the End-exit variant is replaced by PlayerList.respawn,
     * whose DISCARDED removal (and ServerPlayerMixin.btw$restoreFrom) marks it. Field only, never
     * creates a bridge, client players included (harmless: client bridges drive no AI).
     */
    @Inject(method = "remove", at = @At("TAIL"))
    private void btw$markBridgeDead(Entity.RemovalReason reason, CallbackInfo ci) {
        PlayerBridge bridge = this.btw$playerBridge;
        if (bridge != null && reason != Entity.RemovalReason.CHANGED_DIMENSION) {
            bridge.isDead = true;
        }
    }

    // NOTE: getDestroySpeed is NOT intercepted here.
    // FC's complete mining speed calculation runs through
    // fcBlock.getPlayerRelativeBlockHardness() called from BlockBehaviorMixin,
    // which internally calls player.getCurrentPlayerStrVsBlock() →
    // inventory.getStrVsBlock() → item.getStrVsBlock() — all FC code.

    // ================================================================
    // FC mining speed modifier (penalty-based slowdown)
    // ================================================================

    // ================================================================
    // FC bed sleeping disabled
    // ================================================================

    @Inject(method = "startSleepInBed", at = @At("HEAD"), cancellable = true)
    private void btw$disableBeds(BlockPos pos,
                                  CallbackInfoReturnable<Player.BedSleepingProblem> cir) {
        // FC completely disables bed sleeping — always returns OTHER_PROBLEM
        cir.setReturnValue(Player.BedSleepingProblem.OTHER_PROBLEM);
    }

    // ================================================================
    // FC mid-air block placement restriction
    // ================================================================

    @Inject(method = "mayUseItemAt", at = @At("HEAD"), cancellable = true)
    private void btw$restrictMidAirPlacement(BlockPos pos, net.minecraft.core.Direction facing,
                                              net.minecraft.world.item.ItemStack stack,
                                              CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (self instanceof ServerPlayer sp) {
            // Creative mode bypasses
            if (sp.getAbilities().instabuild) return;

            // FC requires: onGround OR inWater OR on ladder OR riding OR in lava
            boolean canPlace = sp.onGround()
                    || sp.isInWater()
                    || sp.onClimbable()
                    || sp.isPassenger()
                    || sp.isInLava();

            if (!canPlace) {
                cir.setReturnValue(false);
            }
        }
    }

    // ================================================================
    // FC eating restriction — block when Hunger potion active
    // ================================================================

    /**
     * FCMOD-INTEGRATION(1.20.1): hunger_contract
     *
     * <p><b>Feature:</b> 1.20.1 {@code Player.canEat(boolean)}: whether a food may be started (Item.use and every
     * modded food's use), {@code abilities.invulnerable || alwaysEat || foodData.needsFood()}.</p>
     *
     * <p><b>Conflict with FC:</b> 1.5.2 refuses food while the hunger effect is active (Modern-Common
     * EntityPlayer.canEat), and FC's 60-unit FoodStats, not vanilla's FoodData, is the hunger meter. The FoodData copy
     * is refreshed once per tick (FoodDataMixin.btw$tick), so it lags any change FC made in the same tick, and a player
     * whose FoodData is never ticked (a fake player) is judged on a stale 20.</p>
     *
     * <p><b>Integration pattern:</b> D (FC-extend): the hunger effect refuses every food; on the server the FC meter
     * decides "needs food".</p>
     *
     * <p><b>Rebalance:</b> none; 4.B parity. A server player can start a food unless the FC food level is full (60),
     * as FC's own foods already decide (FoodStats.needFood); alwaysEat foods and invulnerable players keep 1.20.1's
     * answer. The client keeps vanilla's test on its synced copy (FC food / 3 &lt; 20, the same condition).</p>
     *
     * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code canEat(boolean)}, cancellable: false with the
     * hunger effect; for a ServerPlayer without alwaysEat or invulnerability, the FC meter's {@code needFood()}.</p>
     *
     * <p><b>Alternative considered and rejected:</b> refreshing the FoodData copy after every FC change instead: every
     * FC write site (FC items, mods' exhaustion, the tick) would need the copy, and a missed one re-opens the lag.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: the FC food meter, not vanilla's per-tick copy, decides whether a server player may start eating
    @Inject(method = "canEat", at = @At("HEAD"), cancellable = true)
    private void btw$restrictEating(boolean canAlwaysEat, CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (self.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER)) {
            cir.setReturnValue(false);
            return;
        }
        if (self instanceof ServerPlayer sp && !canAlwaysEat && !sp.getAbilities().invulnerable) {
            cir.setReturnValue(PlayerBridge.getOrCreate(sp).foodStats.needFood());
        }
    }

    // ================================================================
    // FC hunger contract: every Player.causeFoodExhaustion goes to FC
    // ================================================================

    /**
     * FCMOD-INTEGRATION(1.20.1): hunger_contract (INTEGRATIONS #021)
     *
     * <p><b>Feature:</b> 1.20.1 {@code Player.causeFoodExhaustion(float)}: vanilla's own hunger costs (movement, jump,
     * attack, damage, the hunger effect, block breaking) and the call other mods use for theirs (shivering, thirst,
     * abilities).</p>
     *
     * <p><b>Conflict with FC:</b> FC's FoodStats is the server player's hunger. The former hook applied the armour weight
     * but ran before vanilla's invulnerable check (Player.java:1666-1672), so creative and invulnerable players paid,
     * and FC's own costs (PlayerBridge.addExhaustion) skipped the armour weight.</p>
     *
     * <p><b>Integration pattern:</b> D (FC-extend): every cost goes through PlayerBridge.addExhaustion, FC's 1.5.2 rule
     * (Modern-Common EntityPlayer.addExhaustion, vanilla/server EntityPlayer.java:1934-1946).</p>
     *
     * <p><b>Rebalance:</b> none; 4.B parity: x armour weight, nothing while invulnerable (disableDamage). A zero cost
     * (1.20.1 walking, a dropped vanilla charge, see below) is a no-op without touching the bridge.</p>
     *
     * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code causeFoodExhaustion(float)}, cancelled for a
     * ServerPlayer, so FoodData.addExhaustion is never reached from here (FoodDataMixin routes only other callers).</p>
     *
     * <p><b>Alternative considered and rejected:</b> applying the armour weight here and calling FoodStats directly (the
     * former hook): FC's own costs, which call EntityPlayer.addExhaustion, then followed a different rule.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: vanilla's and other mods' hunger costs follow FC's 1.5.2 rule (armour weight, invulnerable exemption)
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
    private void btw$armorExhaustion(float amount, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self instanceof ServerPlayer sp) {
            if (amount != 0F) {
                PlayerBridge.getOrCreate(sp).addExhaustion(amount);
            }
            ci.cancel();
        }
    }

    // ================================================================
    // FC exhaustion parity: vanilla Player's own exhaustion sites charge 1.5.2 amounts (decision D-A)
    // ================================================================
    //
    // 1.20.1 charges a jump twice (FC's AddExhaustionForJump in LivingEntityMixin.btw$jumpExhaustion plus vanilla's
    // 0.05 / 0.2), walking 0, swimming 0.01 per metre, a melee hit and a hurt 0.1. 1.5.2 charges a jump once (0.2 /
    // 1.0 sprinting), walking 0.01 and water 0.015 per metre, a hit 0.3 and a hurt 0.3 (0 for armour-bypassing damage).
    // Only these four methods of Player itself change; other mods' direct causeFoodExhaustion and
    // FoodData.addExhaustion calls keep their amounts. Server players only (FC's hunger; the client's calls are no-ops).

    @Unique
    private static volatile boolean btw$movementExhaustionFailureLogged;

    /**
     * FCMOD-INTEGRATION(1.20.1): hunger_contract
     *
     * <p><b>Feature:</b> 1.20.1 {@code Player.jumpFromGround} charges 0.2 sprinting, else 0.05 (Player.java:1391-1399).</p>
     *
     * <p><b>Conflict with FC:</b> FC charges its own 1.5.2 jump cost (EntityPlayer.AddExhaustionForJump: 0.2, 1.0
     * sprinting; vanilla/server EntityPlayer.java:1658-1676) from LivingEntityMixin.btw$jumpExhaustion, inside the
     * super call; vanilla's charge came on top: 0.25 and 1.2 per jump.</p>
     *
     * <p><b>Integration pattern:</b> C (rebalance): vanilla's charge is dropped; FC's remains the one jump cost.</p>
     *
     * <p><b>Rebalance:</b> a jump costs 0.2, a sprint jump 1.0 (x armour weight), as in 4.B.</p>
     *
     * <p><b>Injection strategy:</b> {@link ModifyArg} on both {@code causeFoodExhaustion} calls (SRG m_36399_) in
     * {@code jumpFromGround} (m_6135_): the amount becomes 0 for a ServerPlayer, which btw$armorExhaustion skips.</p>
     *
     * <p><b>Alternative considered and rejected:</b> {@code @Redirect} of the calls (the briefed form): two mods
     * redirecting one call fail at load, while ModifyArg handlers chain. Cancelling LivingEntityMixin's FC charge
     * instead: FC's amount and its jump gate belong together.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: a jump costs FC's 1.5.2 amount once; vanilla's second charge is dropped
    @ModifyArg(method = "jumpFromGround",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float btw$dropVanillaJumpExhaustion(float vanillaAmount) {
        return (Object) this instanceof ServerPlayer ? 0F : vanillaAmount;
    }

    /**
     * FCMOD-INTEGRATION(1.20.1): hunger_contract
     *
     * <p><b>Feature:</b> 1.20.1 {@code Player.checkMovementStatistics(dx, dy, dz)}, run for every move of a server
     * player (ServerGamePacketListenerImpl.handleMovePlayer): walking stats and hunger, 0 per metre walking and
     * sneaking, 0.01 swimming, 0.1 sprinting (Player.java:1446-1494).</p>
     *
     * <p><b>Conflict with FC:</b> 1.5.2 addMovementStat charges 0.01 per metre walking and 0.015 in water
     * (vanilla/server EntityPlayer.java:1707-1780); walking hunger, a core 4.B pressure, cost nothing.</p>
     *
     * <p><b>Integration pattern:</b> C (rebalance): the 1.5.2 rule, ported to Modern-Common
     * EntityPlayer.AddMovementExhaustion, replaces vanilla's amounts.</p>
     *
     * <p><b>Rebalance:</b> 100 m walked cost 1.0, swum 1.5, sprinted 10 (x armour weight); climbing and riding cost
     * nothing, as in 4.B.</p>
     *
     * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code checkMovementStatistics} (m_36378_) charges the
     * 1.5.2 amount through the player's bridge (not while a passenger, vanilla's own guard); {@link #btw$dropVanillaMovementExhaustion}
     * zeroes vanilla's six charges. Never throws into the movement packet handler. The method runs for every movement
     * packet and every server tick (Player.travel), mostly with no movement, and the bridge lookup re-syncs the whole
     * FC inventory (plus the ladder gate's own sync), so two exact early-outs skip it: a move whose 3D length rounds
     * to 0 cm (no branch of the 1.5.2 rule can charge it, the horizontal length being no longer), and an invulnerable
     * player (disableDamage: EntityPlayer.addExhaustion charges nothing).</p>
     *
     * <p><b>Alternative considered and rejected:</b> rewriting each vanilla charge by ordinal: walking's charge is
     * {@code 0 x distance}, so the distance cannot be recovered from the argument, and 1.20.1's branches (sprint
     * swimming, walking under water) do not match 1.5.2's.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: moving costs 1.5.2's per-metre hunger (walking 0.01, water 0.015, sprinting 0.1)
    @Inject(method = "checkMovementStatistics", at = @At("HEAD"))
    private void btw$fcMovementExhaustion(double dx, double dy, double dz, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer sp && !sp.isPassenger() && !sp.getAbilities().invulnerable
                && Math.round((float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0F) > 0) {
            try {
                PlayerBridge.getOrCreate(sp).AddMovementExhaustion(dx, dy, dz);
            } catch (Throwable t) {
                if (!btw$movementExhaustionFailureLogged) {
                    btw$movementExhaustionFailureLogged = true;
                    BTW$LOGGER.warn("FC movement exhaustion failed; moving costs no FC hunger (logged once): {}",
                            t.toString(), t);
                }
            }
        }
    }

    /**
     * FCMOD-INTEGRATION(1.20.1): hunger_contract
     *
     * <p><b>Feature:</b> the six {@code causeFoodExhaustion} calls of 1.20.1 {@code checkMovementStatistics} (swimming,
     * walking under and on water, sprinting, sneaking, walking).</p>
     *
     * <p><b>Conflict with FC:</b> {@link #btw$fcMovementExhaustion} charges 1.5.2's movement cost for the same move;
     * vanilla's amounts on top would charge water and sprinting twice.</p>
     *
     * <p><b>Integration pattern:</b> C (rebalance): vanilla's amounts give way to the 1.5.2 charge.</p>
     *
     * <p><b>Rebalance:</b> see {@link #btw$fcMovementExhaustion}; vanilla's movement charges cost a server player
     * nothing.</p>
     *
     * <p><b>Injection strategy:</b> {@link ModifyArg} on every {@code causeFoodExhaustion} call (SRG m_36399_) in
     * {@code checkMovementStatistics} (m_36378_): the amount becomes 0 for a ServerPlayer, which btw$armorExhaustion
     * skips. The stats calls are untouched.</p>
     *
     * <p><b>Alternative considered and rejected:</b> cancelling {@code checkMovementStatistics} and porting it whole: the
     * movement stats (and other mods' injections there) would be lost.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: vanilla's movement charges give way to the 1.5.2 charge above
    @ModifyArg(method = "checkMovementStatistics",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float btw$dropVanillaMovementExhaustion(float vanillaAmount) {
        return (Object) this instanceof ServerPlayer ? 0F : vanillaAmount;
    }

    /**
     * FCMOD-INTEGRATION(1.20.1): hunger_contract
     *
     * <p><b>Feature:</b> 1.20.1 {@code Player.attack} charges 0.1 per hit that lands (Player.java:1224).</p>
     *
     * <p><b>Conflict with FC:</b> 1.5.2 charges 0.3 per landed hit (vanilla/server EntityPlayer.java:1330); fighting
     * cost a third of its 4.B hunger.</p>
     *
     * <p><b>Integration pattern:</b> C (rebalance).</p>
     *
     * <p><b>Rebalance:</b> a landed melee hit costs 0.3 (x armour weight), as in 4.B.</p>
     *
     * <p><b>Injection strategy:</b> {@link ModifyArg} on the one {@code causeFoodExhaustion} call in {@code attack}
     * (m_5706_), for a ServerPlayer.</p>
     *
     * <p><b>Alternative considered and rejected:</b> {@code @Redirect}: conflicts with another mod's redirect of the
     * same call, where ModifyArg chains.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: a landed melee hit costs 1.5.2's 0.3
    @ModifyArg(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float btw$fcAttackExhaustion(float vanillaAmount) {
        return (Object) this instanceof ServerPlayer ? 0.3F : vanillaAmount;
    }

    /**
     * FCMOD-INTEGRATION(1.20.1): hunger_contract
     *
     * <p><b>Feature:</b> 1.20.1 {@code Player.actuallyHurt} charges the damage type's exhaustion (data: 0.1, or 0 for
     * the types tagged {@code minecraft:bypasses_armor}) when damage gets through (Player.java:919).</p>
     *
     * <p><b>Conflict with FC:</b> 1.5.2 charges {@code DamageSource.hungerDamage}: 0.3, and 0 for armour-bypassing
     * sources (vanilla/server DamageSource.java:19-36, EntityPlayer.java:1094); taking a hit cost a third.</p>
     *
     * <p><b>Integration pattern:</b> C (rebalance): minecraft damage types follow 1.5.2's rule; other namespaces keep
     * their own data value.</p>
     *
     * <p><b>Rebalance:</b> a minecraft-type hit costs 0 when it bypasses armour, else 0.3 (x armour weight), as in 4.B.
     * A modded damage type keeps its exhaustion.</p>
     *
     * <p><b>Injection strategy:</b> {@link Redirect} of the {@code DamageSource.getFoodExhaustion()} call (m_19377_)
     * in {@code actuallyHurt} (m_6475_), which hands the source to the handler; the charge itself stays vanilla's
     * {@code causeFoodExhaustion}, so other mods' ModifyArgs on it still apply.</p>
     *
     * <p><b>Alternative considered and rejected:</b> {@code @ModifyArg} on {@code causeFoodExhaustion}: its handler
     * cannot see the damage source (Mixin 0.8.5 ModifyArg captures no target arguments). Multiplying the data value by
     * 3: equal for vanilla's data, but a datapack's 0.1 on an armour-bypassing type would cost 0.3.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: a hurt costs 1.5.2's 0.3, 0 for armour-bypassing minecraft damage types
    @Redirect(method = "actuallyHurt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/damagesource/DamageSource;getFoodExhaustion()F"))
    private float btw$fcHurtExhaustion(DamageSource source) {
        float vanillaAmount = source.getFoodExhaustion();
        if (!((Object) this instanceof ServerPlayer)) return vanillaAmount;
        boolean minecraftType = source.typeHolder().unwrapKey()
                .map(key -> "minecraft".equals(key.location().getNamespace()))
                .orElse(false);
        if (!minecraftType) return vanillaAmount;
        return source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR) ? 0F : 0.3F;
    }

    // ================================================================
    // FC combat: blasting oil detonation on damage (#18)
    // ================================================================

    /**
     * After a player takes damage, check if they are carrying blasting oil.
     * If so, detonate it. Only applies to ServerPlayer (server-side).
     *
     * @see btw.modern.EntityPlayer#IsCarryingBlastingOil()
     * @see btw.modern.EntityPlayer#DetonateCarriedBlastingOil()
     */
    @Inject(method = "hurt", at = @At("RETURN"))
    private void btw$checkBlastingOilOnDamage(DamageSource source, float amount,
                                               CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (self instanceof ServerPlayer sp) {
            // Only proceed if damage was actually applied (return value true)
            if (cir.getReturnValue()) {
                PlayerBridge pb = PlayerBridge.getOrCreate(sp);
                pb.syncFromReal();
                if (pb.IsCarryingBlastingOil()) {
                    pb.DetonateCarriedBlastingOil();
                }
            }
        }
    }

    // ================================================================
    // FC combat: melee damage modifier (#20)
    // ================================================================

    /**
     * Before a player attacks an entity, store the current melee damage
     * modifier on the PlayerBridge so FC code can reference it.
     * <p>
     * In vanilla, {@code Player.attack(Entity)} calculates damage internally
     * using attribute values. FC applies a penalty-based modifier to melee
     * damage via {@code GetMeleeDamageModifier()}. Since we cannot easily
     * redirect the internal damage calculation, we sync the modifier to
     * the bridge before the attack method runs, and apply it in a
     * secondary injection at the damage application point.
     * <p>
     * The modifier is stored as {@link PlayerBridge#pendingMeleeDamageModifier}
     * so it can be applied by downstream logic.
     */
    @Inject(method = "attack", at = @At("HEAD"))
    private void btw$storeMeleeDamageModifier(Entity target, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self instanceof ServerPlayer sp) {
            PlayerBridge pb = PlayerBridge.getOrCreate(sp);
            pb.syncFromReal();
            pb.pendingMeleeDamageModifier = pb.GetMeleeDamageModifier();
        }
    }

    /**
     * Applies the FC melee damage penalty to the BASE attack damage — the first float local
     * in Player.attack, `getAttributeValue(ATTACK_DAMAGE)`, before enchantment damage is
     * added. Mirrors 1.5.2 EntityPlayer.attackTargetEntityWithCurrentItem:1226-1230
     * (`if (fModifier < 0.99F) var2 = (int)(var2 * fModifier)` applied to base, pre-enchant).
     * Modifier comes from btw$storeMeleeDamageModifier (@HEAD, already synced). This is the
     * consumer the stored value never had — FC weight/health/exhaustion now actually weakens
     * hits. Server-only (client attack() also runs but the server value is authoritative).
     * <p>@At ordinal 0 = ONLY the first store of f (bytecode offset 32,
     * `f = getAttributeValue(ATTACK_DAMAGE)`). Without it Mixin matches every store of f (base,
     * cooldown scale, crit, +enchant) and compounded the penalty to m^3..m^4. Crit (x1.5) then
     * scales the reduced f and the enchant bonus f1 stays unreduced, exactly as 1.5.2
     * EntityPlayer.java:1225-1242. Float multiply, no (int) truncation: 1.20.1 damage is float
     * and truncating would only add zero-damage hits at high penalty.
     */
    @ModifyVariable(method = "attack", at = @At(value = "STORE", ordinal = 0), ordinal = 0)
    private float btw$applyMeleeDamageModifier(float baseDamage) {
        Player self = (Player) (Object) this;
        if (self instanceof ServerPlayer sp) {
            float modifier = PlayerBridge.getOrCreate(sp).pendingMeleeDamageModifier;
            if (modifier < 0.99F) {
                return baseDamage * modifier;
            }
        }
        return baseDamage;
    }

    // ================================================================
    // FC block movement modifier (Block.GetMovementModifier, e.g. +20% on hard surfaces)
    // ================================================================

    /**
     * 1.5.2 EntityLiving.getSpeedModifier (vanilla/server EntityLiving.java:3185-3235) multiplies the
     * ON-GROUND move speed (landMovementFactor, used only in moveEntityWithHeading's onGround branch,
     * :1505-1517) by the GetMovementModifier of the block underfoot. The 1.20.1 equivalent input term
     * is getSpeed(): LivingEntity.getFrictionInfluencedSpeed uses it only when onGround, and airborne
     * accel uses Player.getFlyingSpeed (never getSpeed). So this is a bounded additive-accel change
     * that cannot re-create the sprint-jump 'flying' bug (that came from getSpeedFactor scaling
     * carried momentum, STUB 2026-07-09 (j)).
     * MUST target Player.getSpeed: Player overrides LivingEntity.getSpeed without calling super
     * (Player.java:1442-1444), so the old LivingEntity.getSpeed hooks never ran for any player.
     * Pure function of world state, so LocalPlayer (client-authoritative movement) and ServerPlayer
     * compute the same value. The FC health/hunger/gloom penalty is NOT applied here -- it is already
     * in the MOVEMENT_SPEED attribute this method returns (FCGameplayBridge.onPlayerTick, synced to
     * the client by vanilla); applying it here too would square it.
     */
    @Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
    private void btw$applyBlockMovementModifier(CallbackInfoReturnable<Float> cir) {
        float modifier = btw.forge.FCMovementBonus.getBlockBelowSpeedBonus((Player) (Object) this);
        if (modifier != 1.0F) {
            cir.setReturnValue(cir.getReturnValue() * modifier);
        }
    }

    // ================================================================
    // FC penalty on airborne movement (jumpMovementFactor)
    // ================================================================

    /**
     * 1.5.2 EntityPlayer.onLivingUpdate (vanilla/server EntityPlayer.java:513-523):
     * {@code jumpMovementFactor = speedInAir * GetJumpingHorizontalMovementModifier()}, plus an
     * UNpenalised {@code speedInAir * 0.3} while sprinting. 1.20.1's equivalent is
     * Player.getFlyingSpeed (Player.java:2073-2079: 0.02, or 0.026 sprinting, for non-flight), which
     * the MOVEMENT_SPEED attribute never touches -- so the FC slowdown "vanished" mid-jump.
     * m (GetJumpingHorizontalMovementModifier == GetLandMovementModifier, Modern-Common
     * EntityPlayer.java:538-548) is read from the synced FC attribute modifier, so LocalPlayer
     * (client-authoritative movement) and ServerPlayer agree without a packet. Creative/spectator
     * flight keeps vanilla's value (1.5.2 uses capabilities.getFlySpeed() there, no penalty).
     * Bounded: it only ever LOWERS airborne acceleration.
     */
    @Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
    private void btw$applyAirbornePenalty(CallbackInfoReturnable<Float> cir) {
        Player self = (Player) (Object) this;
        if (self.getAbilities().flying && !self.isPassenger()) {
            return;
        }
        float modifier = btw.forge.FCMovementBonus.getSyncedPenaltyModifier(self);
        if (modifier < 0.99F) {
            cir.setReturnValue(0.02F * modifier + (self.isSprinting() ? 0.006F : 0.0F));
        }
    }

    // ================================================================
    // FC step sounds: low-health grunt, soulforged boots (audit sounds S11)
    // ================================================================

    @Unique
    private static final org.apache.logging.log4j.Logger BTW$LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("BTW-PlayerSounds");

    @Unique
    private static volatile boolean btw$stepSoundFailureLogged;

    /**
     * FC's EntityPlayer.playStepSound override (vanilla/server EntityPlayer.java:2454-2492), ported to Modern-Common
     * EntityPlayer.PlayStepGrunt / PlaySoulforgedBootsStep: a grunt with every step while the health/exhaustion
     * modifier is below 0.26, and with soulforged boots a quieter, lower step in place of the regular one. Both
     * are server playSoundAtEntity broadcasts that reach the stepping player too; the 1.5.2 client's own copy
     * went to WorldClient's no-op playSoundAtEntity.
     * <ul>
     *   <li>Server: runs the port through the player's bridge on the block 1.20.1 steps on (getOnPosLegacy, the
     *       1.5.2 posY - 0.2 ground block); the 1.20.1 step is cancelled only when the boots step replaced it.</li>
     *   <li>Client: 1.20.1 plays the player's own step locally (Player.playSound to itself); with the boots on,
     *       1.5.2 played none there, so that local step is dropped and the server's boots step is the one heard.</li>
     *   <li>In water the 1.20.1 swim sound and muffled step stay (1.5.2 played liquid.swim on its own, and the
     *       boots branch is silent on a liquid); only the grunt is added.</li>
     * </ul>
     */
    @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
    private void btw$fcStepSound(BlockPos pos, BlockState state, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        try {
            if (self instanceof ServerPlayer sp) {
                PlayerBridge pb = PlayerBridge.getOrCreate(sp);
                if (pb.worldObj == null) return;
                pb.PlayStepGrunt();
                if (!self.isInWater()) {
                    int blockId = pb.worldObj.getBlockId(pos.getX(), pos.getY(), pos.getZ());
                    if (pb.PlaySoulforgedBootsStep(pos.getX(), pos.getY(), pos.getZ(), blockId)) {
                        ci.cancel();
                    }
                }
            } else if (self.level().isClientSide() && !self.isInWater() && btw$wearsSoulforgedBoots(self)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // Entity.move, inside the movement packet handler (server) or the player tick (client)
            if (!btw$stepSoundFailureLogged) {
                btw$stepSoundFailureLogged = true;
                BTW$LOGGER.warn("FC step sound failed; vanilla step sounds stay (logged once): {}", t.toString(), t);
            }
        }
    }

    /** The client half of EntityPlayer.IsWearingSoulforgedBoots: the same test on the synced boots slot. */
    @Unique
    private static boolean btw$wearsSoulforgedBoots(Player player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        return !boots.isEmpty() && btw.modern.EntityPlayer.IsSoulforgedBootsItem(ProxyItem.fcItemFor(boots.getItem()));
    }

    // ================================================================
    // No vanilla burp where FC's onEaten owns the eat (audit sounds S10)
    // ================================================================

    /** Whether the eat Player.eat is running belongs to FC (read by {@link #btw$fcOwnsBurp}). */
    @Unique
    private boolean btw$fcOwnedEat;

    @Inject(method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"))
    private void btw$noteFcOwnedEat(Level level, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        this.btw$fcOwnedEat = ((Object) stack) instanceof ProxyItem.FcOwnedEat owned && owned.btw$isFcOwnedEat();
    }

    /**
     * 1.5.2 burps once, in ItemFood.onEaten (vanilla/server ItemFood.java:71, broadcast), which FC's food code
     * runs at ItemStack.finishUsingItem HEAD (ItemStackMixin.btw$finishUsing) before vanilla's Item.finishUsingItem
     * reaches Player.eat. When FC fed this eat (ProxyItem.FcOwnedEat) Player.eat's PLAYER_BURP would be a second
     * burp: it is skipped. Every other eat (modern-only foods, eats FC did not feed) keeps vanilla's.
     */
    @Redirect(method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void btw$fcOwnsBurp(Level level, Player except, double x, double y, double z,
                                SoundEvent sound, SoundSource source, float volume, float pitch) {
        if (!this.btw$fcOwnedEat) {
            level.playSound(except, x, y, z, sound, source, volume, pitch);
        }
    }
}
