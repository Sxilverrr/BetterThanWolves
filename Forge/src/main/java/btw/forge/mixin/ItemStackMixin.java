package btw.forge.mixin;

import btw.forge.PlayerBridge;
import btw.forge.LivingEntityBridge;
import btw.forge.LegacyProxyItem;
import btw.forge.ProxyItem;
import btw.forge.ProxyRegistry;
import btw.forge.WorldBridge;
import btw.forge.ItemStackHelper;
import btw.forge.food.FcFoodValues;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts ALL item interaction methods on {@link ItemStack} and delegates
 * to the corresponding FC item if one exists. This is the GENERAL bridge —
 * it works for ANY item the player holds, whether vanilla (with FC replacement)
 * or BTW-specific (ProxyItem).
 *
 * Hooks with side effects (finishUsingItem, releaseUsing, hurtEnemy, mineBlock,
 * interactLivingEntity) skip ProxyItems, which delegate to FC themselves: running
 * both fired FC twice (e.g. two arrows from an FC bow). The read-only hooks
 * (getUseDuration, getUseAnimation) apply to every item.
 * For vanilla items with FC replacements, this mixin is the ONLY path to FC code.
 *
 * <ul>
 *   <li>finishUsingItem: FC onEaten (one feed, one bowl, one burp)</li>
 *   <li>getEatingSound: silent closing sound while FC owns the eat</li>
 *   <li>onCraftedBy: FC onCreated</li>
 *   <li>releaseUsing: FC onPlayerStoppedUsing (bows)</li>
 *   <li>hurtEnemy / mineBlock / interactLivingEntity: FC hitEntity / onBlockDestroyed / entity use</li>
 *   <li>getUseDuration / getUseAnimation: FC use duration and action</li>
 *   <li>getMaxStackSize: foods stack no higher than their FC limit (FC item limit, else 16; #024)</li>
 * </ul>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin implements ProxyItem.FcOwnedEat {

    /**
     * Look up the FC item for this MC ItemStack: the 1.5.2 item id first, a BlockItem's block only when
     * that has no FC item (carrots/potatoes/seeds are BlockItems in 1.20.1; see ProxyItem.fcItemFor).
     */
    private btw.modern.Item btw$getFcItem() {
        ItemStack self = (ItemStack) (Object) this;
        if (self.isEmpty()) return null;
        return ProxyItem.fcItemFor(self.getItem());
    }

    // ================================================================
    // finishUsingItem → FC onEaten
    // ================================================================

    /**
     * Set by {@link #btw$finishUsing} when FC's own bowl replaced vanilla's for this eat; read and cleared by
     * {@link #btw$finishUsingResult} at the RETURN of the same call.
     */
    @Unique
    private boolean btw$fcBowlReturned;

    /**
     * S10: set by {@link #btw$finishUsing} when FC's 1.5.2 ItemFood.onEaten fed this eat (PlayerBridge.fcFedForEat)
     * and so played its own 'random.burp' (vanilla/server ItemFood.java:71); cleared at the RETURN of the same
     * call. While set, vanilla's closing eat sounds of this eat are silenced: Player.eat's PLAYER_BURP
     * (PlayerMixin.btw$fcOwnsBurp) and LivingEntity.eat's eat sound ({@link #btw$silenceClosingEatSound}).
     */
    @Unique
    private boolean btw$fcOwnedEat;

    @Override
    public boolean btw$isFcOwnedEat() {
        return this.btw$fcOwnedEat;
    }

    @Inject(method = "finishUsingItem", at = @At("HEAD"))
    private void btw$finishUsing(Level level, LivingEntity entity,
                                  CallbackInfoReturnable<ItemStack> cir) {
        this.btw$fcBowlReturned = false;
        this.btw$fcOwnedEat = false;
        // ProxyItems run onEaten in ProxyItem.finishUsingItem. Doing it here too
        // would double-apply nutrition now that ItemFood.onEaten is no longer a
        // no-op. This path handles only vanilla items with FC replacements.
        ItemStack self = (ItemStack) (Object) this;
        if (self.getItem() instanceof LegacyProxyItem) return;
        btw.modern.Item fcItem = btw$getFcItem();
        if (fcItem == null) return;
        // Vanilla potions (legacy 373 -> FCItemPotion) stay vanilla: 1.20.1 PotionItem.finishUsingItem applies the
        // effects and hands back the one glass bottle (PotionItem.java:42-71). FC reads an MC potion as 373:0
        // (ItemStackHelper.toFcStack has no Potion-tag -> 1.5.2 damage mapping), a water bottle without effects, so
        // FCItemPotion.onEaten (Common FCItemPotion.java:64-72) only added a second bottle: invisible while the
        // snapshot had room (never written back), but dropped for real through PlayerBridge.dropPlayerItem when the
        // inventory was full -- one bottle in the hand plus one on the ground.
        if (self.getItem() instanceof net.minecraft.world.item.PotionItem) return;
        if (level instanceof ServerLevel sl && entity instanceof Player player) {
            PlayerBridge pb = PlayerBridge.getOrCreate(player);
            pb.syncFromReal();
            // The FC player holds only the main hand (1.5.2 has no off hand): the selected main-hand stack runs
            // on its live snapshot, any other stack (the off hand) on a detached copy. FC then reads the stack
            // actually eaten (the enchanted golden apple's subtype, ItemAppleGold.onFoodEaten) instead of the
            // unrelated main-hand stack, and an off-hand eat with an empty main hand
            // is no longer skipped (it fell back to vanilla's 1.20.1 nutrition).
            btw.modern.ItemStack fcStack;
            try {
                fcStack = self == player.getMainHandItem()
                        ? pb.getCurrentEquippedItem() : ItemStackHelper.toFcStack(self);
            } catch (Throwable t) {
                ProxyItem.logFcFailureOnce("onEaten", fcItem.itemID, t);
                return;
            }
            if (fcStack != null) {
                boolean fcDone = false;
                try {
                    fcItem.onEaten(fcStack, WorldBridge.getOrCreate(sl), pb);
                    fcDone = true;
                } catch (Throwable t) {
                    // Runs inside LivingEntity.updatingUsingItem: a throw would crash "Ticking player".
                    ProxyItem.logFcFailureOnce("onEaten", fcItem.itemID, t);
                }
                pb.syncToReal();
                // S10: FC fed this eat, so its ItemFood.onEaten played the 1.5.2 burp: one owner for the eat's
                // closing sounds (vanilla's are silenced for the rest of this call).
                this.btw$fcOwnedEat = fcDone && PlayerBridge.fcFedForEat(self.getItem(), player);
                // FC bowl foods (FCItemMushroomSoup, the 1.5.2 mushroom stew 282: Common FCItemMushroomSoup.java:29-41)
                // return their bowl with player.inventory.addItemStackToInventory, which only reaches the
                // InventoryBridge snapshot, while vanilla BowlFoodItem.finishUsingItem hands back its own bowl.
                // Exactly one bowl, as 1.5.2: land FC's (the write-back is selective, InventoryBridge.writeBackAll:
                // only the slot FC put it in -- the first bowl stack or empty slot, never the held one, which FC
                // still sees holding the stew, as 1.5.2 still held the emptied stack until onItemUseFinish cleared
                // it, vanilla/server EntityPlayer.java:406-426; a full inventory drops it through
                // PlayerBridge.dropPlayerItem) and return the eaten stack instead
                // of vanilla's bowl (btw$finishUsingResult). Only when FC fed this eat (PlayerBridge.fcFedForEat,
                // the FoodDataMixin cancel) and outside creative, where vanilla keeps the stew and gives no bowl.
                if (fcDone && btw$vanillaReturnsBowl(self) && !player.getAbilities().instabuild
                        && PlayerBridge.fcFedForEat(self.getItem(), player)) {
                    try {
                        pb.syncInventoryToReal();
                        this.btw$fcBowlReturned = true;
                    } catch (Throwable t) {
                        ProxyItem.logFcFailureOnce("onEaten write-back", fcItem.itemID, t);
                    }
                }
                // 1.5.2 ItemFood.onEaten eats one item (vanilla/server ItemFood.java:69 --stackSize, before the
                // nutrition); Modern-Common ItemFood.onEaten leaves that to the bridge. Vanilla shrinks MC-edible
                // stacks itself (Item.finishUsingItem -> LivingEntity.eat), not an FC food whose 1.20.1 item is
                // not edible -- pumpkin seeds (FCItemSeedFood 361, a BlockItem of the pumpkin stem), which FC
                // starts eating in ServerPlayerGameModeMixin.btw$useItem: one seed fed the player forever.
                if (fcItem instanceof btw.modern.ItemFood && !self.isEdible()
                        && !player.getAbilities().instabuild) {
                    self.shrink(1);
                }
            }
        }
    }

    /**
     * FC's bowl already landed (btw$finishUsing): return the eaten stack -- shrunk by vanilla's LivingEntity.eat --
     * instead of the bowl BowlFoodItem/SuspiciousStewItem.finishUsingItem made (1.20.1 BowlFoodItem.java:12-15).
     * LivingEntity.completeUsingItem then leaves the emptied hand alone, as for any other food.
     */
    @Inject(method = "finishUsingItem", at = @At("RETURN"), cancellable = true)
    private void btw$finishUsingResult(Level level, LivingEntity entity,
                                        CallbackInfoReturnable<ItemStack> cir) {
        this.btw$fcOwnedEat = false;
        if (this.btw$fcBowlReturned) {
            this.btw$fcBowlReturned = false;
            cir.setReturnValue((ItemStack) (Object) this);
        }
    }

    /**
     * S10: LivingEntity.eat (via Item.finishUsingItem -> Player.eat -> super.eat, this same stack) closes every
     * eat with one more eating sound at volume 1.0; 1.5.2 had none (its closing sounds were updateItemUse's eat
     * sound, which 1.20.1's completeUsingItem -> triggerItemUseEffects keeps, and ItemFood.onEaten's burp). While
     * FC owns the eat ({@link #btw$fcOwnedEat}) it is made silent. The sounds while eating are untouched: the
     * flag is only set inside finishUsingItem.
     */
    @Inject(method = "getEatingSound", at = @At("HEAD"), cancellable = true)
    private void btw$silenceClosingEatSound(CallbackInfoReturnable<net.minecraft.sounds.SoundEvent> cir) {
        if (this.btw$fcOwnedEat) {
            cir.setReturnValue(ProxyItem.SILENT_SOUND);
        }
    }

    // ================================================================
    // onCraftedBy → FC onCreated
    // ================================================================

    /**
     * S9: a vanilla item with an FC replacement taken from a crafting or smelting result runs FC's onCreated
     * (FCItemTool / FCItemSword / FCItemArmor / FCItemShears crafting sounds), after the crafting stat, where 1.5.2
     * ItemStack.onCrafting called it (vanilla/client ItemStack.java:501-504). ProxyItems run it in
     * ProxyItem.onCraftedBy; ProxyArmorItems (no override there) come through here.
     */
    @Inject(method = "onCraftedBy", at = @At("TAIL"))
    private void btw$onCraftedBy(Level level, Player player, int amount, CallbackInfo ci) {
        ItemStack self = (ItemStack) (Object) this;
        if (self.isEmpty() || self.getItem() instanceof ProxyItem) return;
        ProxyItem.runFcOnCreated(self, level, player);
    }

    /** Vanilla items whose finishUsingItem hands back a bowl outside creative (1.20.1 Items.java:830). */
    private static boolean btw$vanillaReturnsBowl(ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        return item instanceof net.minecraft.world.item.BowlFoodItem
                || item instanceof net.minecraft.world.item.SuspiciousStewItem;
    }

    // ================================================================
    // releaseUsing → FC onPlayerStoppedUsing
    // ================================================================

    /**
     * FCItemBow.onPlayerStoppedUsing (Client FCItemBow.java:39-91, the only FC override of the
     * release) fires FC's own arrow -- hotbar arrows and rotten arrows only, pull strength scaled by
     * GetBowPullStrengthModifier -- consumes it and wears the bow. Running it AND vanilla
     * BowItem.releaseUsing fired two arrows per shot. The vanilla bow (legacy 261) fires exactly one:
     * vanilla's whenever vanilla has something to fire (an arrow anywhere in the inventory,
     * creative, or Infinity; 1.20.1 BowItem.java:26-33), FC's only when vanilla has nothing --
     * a rotten arrow in the hotbar (FCItemBow.java:176-179), whose draw FC starts itself
     * (ServerPlayerGameModeMixin.btw$useItem -> FCItemBow.onItemRightClick -> setItemInUse).
     * Vanilla wins the overlap until FC's frozen EntityArrow is verified in game as a full stand-in
     * for a vanilla arrow (hits on vanilla entities through WorldBridge.getEntitiesWithinAABBExcludingEntity;
     * pickup now writes back in ProxyEntity.playerTouch; FC reads the bow's MC enchantments through the
     * "ench" mirror of ItemStackHelper.toFcStack and wears it through ItemBow's setMaxDamage(384),
     * vanilla/client ItemBow.java:26). The vanilla body is never cancelled: when FC fires, vanilla has
     * no ammo and does nothing, so even an FC throw cannot double the shot.
     * ProxyItems (the FC composite bow) are skipped: ProxyItem.releaseUsing runs FC for them.
     */
    @Inject(method = "releaseUsing", at = @At("HEAD"))
    private void btw$releaseUsing(Level level, LivingEntity entity, int timeLeft,
                                   CallbackInfo ci) {
        ItemStack self = (ItemStack) (Object) this;
        if (self.getItem() instanceof LegacyProxyItem) return;
        btw.modern.Item fcItem = btw$getFcItem();
        if (fcItem == null) return;
        // The FC player exposes only the main hand (1.5.2 has no off hand): an off-hand release
        // stays vanilla rather than running FC on the main-hand stack.
        if (!(entity instanceof Player player) || player.getUsedItemHand() != InteractionHand.MAIN_HAND) return;
        if (!(level instanceof ServerLevel sl)) return;
        boolean bow = fcItem instanceof btw.modern.ItemBow;
        if (bow && btw$vanillaBowHasAmmo(self, player)) return;   // vanilla's arrow is the one arrow

        PlayerBridge pb;
        btw.modern.ItemStack fcStack;
        try {
            pb = PlayerBridge.getOrCreate(player);
            pb.syncFromReal();
            fcStack = pb.getCurrentEquippedItem();
        } catch (Throwable t) {
            ProxyItem.logFcFailureOnce("onPlayerStoppedUsing", fcItem.itemID, t);
            return;
        }
        if (fcStack == null) return;   // unreadable slot: vanilla release

        boolean hadBow = fcStack.stackSize > 0;
        try {
            // FC reads its remaining-ticks argument against ITS use duration (FCItemBow.java:146).
            // MC counted down from the duration it started with, which getUseDuration below
            // takes from FC only when FC reports one (> 0). Pass FC the same ticks-in-use: identical
            // to timeLeft whenever the two durations agree (the bow: 72000 on both sides,
            // vanilla/client ItemBow.java:106-109).
            int ticksInUse = self.getUseDuration() - timeLeft;
            fcItem.onPlayerStoppedUsing(fcStack, WorldBridge.getOrCreate(sl), pb,
                    fcItem.getMaxItemUseDuration(fcStack) - ticksInUse);
        } catch (Throwable t) {
            // No return: FC consumes and spawns the arrow (FCItemBow.java:60, :78) before
            // damageItem (:82) and PlayerBowSound (:84), so a later throw must still write back.
            ProxyItem.logFcFailureOnce("onPlayerStoppedUsing", fcItem.itemID, t);
        }
        // A bow FC wore out: 1.5.2 ItemStack.damageItem plays the break effect (renderBrokenItemStack,
        // vanilla/server ItemStack.java:331), Modern-Common's does not. Sent before the write-back empties
        // the slot, as 1.20.1 ItemStack.hurtAndBreak does (:365-367), so clients still see the bow break.
        if (hadBow && fcStack.stackSize <= 0) player.broadcastBreakEvent(InteractionHand.MAIN_HAND);
        // FC consumed the arrow (InventoryPlayer.consumeInventoryItem, FCItemBow.java:60) and wore the
        // bow (damageItem, :82; Modern-Common ItemBow has 1.5.2's max damage 384) in the snapshot.
        try {
            pb.syncInventoryToReal();
        } catch (Throwable t) {
            ProxyItem.logFcFailureOnce("onPlayerStoppedUsing write-back", fcItem.itemID, t);
        }
    }

    /**
     * Whether vanilla BowItem.releaseUsing has something to fire (1.20.1 BowItem.java:26-33):
     * creative, Infinity, or an arrow anywhere in the inventory (Player.getProjectile, which
     * accepts only ItemTags.ARROWS -- never FC's rotten arrow).
     */
    private static boolean btw$vanillaBowHasAmmo(ItemStack bow, Player player) {
        return player.getAbilities().instabuild
                || net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                        net.minecraft.world.item.enchantment.Enchantments.INFINITY_ARROWS, bow) > 0
                || !player.getProjectile(bow).isEmpty();
    }

    // ================================================================
    // hurtEnemy → FC hitEntity
    // ================================================================

    @Inject(method = "hurtEnemy", at = @At("HEAD"))
    private void btw$hurtEnemy(LivingEntity target, Player player, CallbackInfo ci) {
        // ProxyItems wrap target/attacker in ProxyItem.hurtEnemy; skip here to
        // avoid a second hitEntity call (e.g. double tool-durability loss on FC
        // weapons like the battleaxe). This path is for vanilla-item replacements.
        if (((ItemStack) (Object) this).getItem() instanceof LegacyProxyItem) return;
        btw.modern.Item fcItem = btw$getFcItem();
        if (fcItem == null) return;
        try {
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack((ItemStack) (Object) this);
            if (fcStack != null) {
                btw.modern.EntityLiving fcTarget = LivingEntityBridge.wrapLiving(target);
                btw.modern.EntityLiving fcAttacker = LivingEntityBridge.wrapLiving(player);
                fcItem.hitEntity(fcStack, fcTarget, fcAttacker);
            }
        } catch (Throwable t) {
            // Player.attack, inside the interact packet handler (PacketUtils rethrows Errors)
            ProxyItem.logFcFailureOnce("hitEntity", fcItem.itemID, t);
        }
    }

    // ================================================================
    // mineBlock → FC onBlockDestroyed
    // ================================================================

    @Inject(method = "mineBlock", at = @At("HEAD"))
    private void btw$mineBlock(Level level, net.minecraft.world.level.block.state.BlockState state,
                                net.minecraft.core.BlockPos pos, Player player, CallbackInfo ci) {
        // ProxyItems run onBlockDestroyed in ProxyItem.mineBlock.
        if (((ItemStack) (Object) this).getItem() instanceof LegacyProxyItem) return;
        btw.modern.Item fcItem = btw$getFcItem();
        if (fcItem == null) return;
        if (level instanceof ServerLevel sl) {
            // ServerPlayerGameModeMixin.btw$destroyBlock handles every FC-mapped block, so this is
            // reached for modern-only blocks (before removeBlock: the state is still in the world). FC
            // gets their world-read id -- a ModernBlockStandIn whose getBlockHardness is the MC destroy
            // speed (design_blockmap cross-cluster 1) -- never 0: FC item code dereferences
            // blocksList[id] unguarded (FCItemTool.java:66) and the NPE crashed the integrated server.
            // FC works on a toFcStack copy here; the vanilla mineBlock that follows applies the wear.
            int blockId = ProxyItem.minedBlockIdForFc(state, sl, pos);
            if (blockId == 0) return;
            try {
                btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack((ItemStack) (Object) this);
                if (fcStack != null) {
                    PlayerBridge pb = PlayerBridge.getOrCreate(player);
                    fcItem.onBlockDestroyed(fcStack, WorldBridge.getOrCreate(sl),
                            blockId, pos.getX(), pos.getY(), pos.getZ(), pb);
                }
            } catch (Throwable t) {
                ProxyItem.logFcFailureOnce("onBlockDestroyed", fcItem.itemID, t);
            }
        }
    }

    // ================================================================
    // interactLivingEntity → FC itemInteractionForEntity / useItemOnEntity
    // ================================================================

    /**
     * Vanilla items whose FC replacement interacts with entities: FCItemDye (legacy 351, every dye
     * and the ink sac/cocoa/lapis/bone meal) recolours FC sheep. 1.20.1 DyeItem only knows vanilla
     * Sheep, and FC sheep are ProxyAnimals, so FC dyeing never happened. Resolved through
     * ItemStackHelper.toFcStack (the dye subtype is the item); see ProxyItem.interactWithEntityFc.
     * Server only, as ProxyItem.interactLivingEntity.
     */
    @Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
    private void btw$interactLivingEntity(Player player, LivingEntity target, InteractionHand hand,
                                           CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack self = (ItemStack) (Object) this;
        if (self.isEmpty() || self.getItem() instanceof LegacyProxyItem) return;   // ProxyItem.interactLivingEntity
        if (player.level().isClientSide()) return;
        if (ProxyItem.interactWithEntityFc(self, target)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    // ================================================================
    // getUseDuration → FC getMaxItemUseDuration
    // ================================================================

    @Inject(method = "getUseDuration", at = @At("HEAD"), cancellable = true)
    private void btw$getUseDuration(CallbackInfoReturnable<Integer> cir) {
        btw.modern.Item fcItem = btw$getFcItem();
        if (fcItem == null) return;
        btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack((ItemStack) (Object) this);
        if (fcStack != null) {
            int duration = fcItem.getMaxItemUseDuration(fcStack);
            if (duration > 0) {
                cir.setReturnValue(duration);
            }
        }
    }

    // ================================================================
    // getMaxStackSize → FC food stack limit
    // ================================================================

    /**
     * FCMOD-INTEGRATION(1.20.1): food_values
     *
     * <p><b>Feature:</b> 1.20.1 stack sizes: bread, cooked meat, apples, seeds and most modded foods stack to 64.</p>
     *
     * <p><b>Conflict with FC:</b> 4.B stacks every food to 16 (ItemFood's constructor, vanilla/server
     * ItemFood.java:56) and some to their own FC size (cured meat 32, FCItemFoodCured.java:27); a stack of 64 bread is
     * four times FC's carrying limit for food.</p>
     *
     * <p><b>Integration pattern:</b> C (rebalance, preserve feature): FcFoodValues.foodStackLimit gives each food its FC
     * limit: the FC item's own limit for a food FC has an item for, 16 for table and converted foods (modded foods
     * included), none for drinkable buckets and non-foods.</p>
     *
     * <p><b>Rebalance:</b> food stacks are capped at that limit. The value is only ever lowered: the 1.20.1 mushroom
     * stew stays at 1 (raising it to 4.B's 16 would let BowlFoodItem replace a whole stack with one bowl), the milk
     * bucket stays at 1. Existing oversized stacks stay valid but no longer merge past the cap.</p>
     *
     * <p><b>Injection strategy:</b> {@link Inject} at RETURN of {@code getMaxStackSize()} (SRG m_41741_), cancellable:
     * the final value of Forge's {@code Item.getMaxStackSize(ItemStack)}, so another mod's own size change is seen and
     * only lowered further. foodStackLimit is a cached O(1) lookup that never throws, for this hot path on both
     * sides.</p>
     *
     * <p><b>Alternative considered and rejected:</b> re-registering vanilla foods with {@code stacksTo(16)}: vanilla
     * items cannot be re-registered, and modded foods would not be covered. Capping every food at a flat 16 shrank BTW's
     * cured meat from 32.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): food_values: foods stack no higher than their FC limit (FC item limit, else 4.B's 16); lower only
    @Inject(method = "getMaxStackSize", at = @At("RETURN"), cancellable = true)
    private void btw$foodStackLimit(CallbackInfoReturnable<Integer> cir) {
        int cap = FcFoodValues.foodStackLimit(((ItemStack) (Object) this).getItem());
        if (cap > 0 && cir.getReturnValueI() > cap) {
            cir.setReturnValue(cap);
        }
    }

    // ================================================================
    // getUseAnimation → FC getItemUseAction
    // ================================================================

    @Inject(method = "getUseAnimation", at = @At("HEAD"), cancellable = true)
    private void btw$getUseAnimation(CallbackInfoReturnable<net.minecraft.world.item.UseAnim> cir) {
        btw.modern.Item fcItem = btw$getFcItem();
        if (fcItem == null) return;
        btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack((ItemStack) (Object) this);
        if (fcStack != null) {
            btw.modern.EnumAction action = fcItem.getItemUseAction(fcStack);
            if (action != null && action != btw.modern.EnumAction.none) {
                net.minecraft.world.item.UseAnim anim = switch (action) {
                    case eat -> net.minecraft.world.item.UseAnim.EAT;
                    case drink -> net.minecraft.world.item.UseAnim.DRINK;
                    case bow -> net.minecraft.world.item.UseAnim.BOW;
                    case block -> net.minecraft.world.item.UseAnim.BLOCK;
                    default -> net.minecraft.world.item.UseAnim.NONE;
                };
                cir.setReturnValue(anim);
            }
        }
    }
}
