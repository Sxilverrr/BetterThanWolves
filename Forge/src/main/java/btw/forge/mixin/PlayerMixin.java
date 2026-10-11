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
 *   <li>Eating restriction (hunger potion)</li>
 *   <li>Armor exhaustion modifier</li>
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

    @Inject(method = "canEat", at = @At("HEAD"), cancellable = true)
    private void btw$restrictEating(boolean canAlwaysEat, CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (self.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER)) {
            cir.setReturnValue(false);
        }
    }

    // ================================================================
    // FC armor exhaustion modifier
    // ================================================================

    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
    private void btw$armorExhaustion(float amount, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self instanceof ServerPlayer sp) {
            PlayerBridge pb = PlayerBridge.getOrCreate(sp);
            // Apply FC armor weight exhaustion modifier
            float modified = amount * pb.GetArmorExhaustionModifier();
            // Route to FC food stats instead of vanilla
            pb.foodStats.addExhaustion(modified);
            ci.cancel();
        }
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
