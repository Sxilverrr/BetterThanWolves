package btw.forge;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;

/**
 * A Forge Item that delegates ALL interactions to its corresponding FC item.
 * Every MC item callback is forwarded to the FC item's method via the
 * btw.modern layer — zero FC logic in this class, pure delegation.
 *
 * Used by BTWRegistration for FC items (IDs 256+) so that FC item subclasses
 * (FCItemAxe, FCItemFood, FCItemBow, etc.) have their full behavior expressed
 * through Forge's item system.
 */
public class ProxyItem extends Item implements LegacyProxyItem {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("BTW-ProxyItem");
    private final int legacyId;
    private final String displayName;

    public ProxyItem(Properties props, int legacyId, String displayName) {
        super(props);
        this.legacyId = legacyId;
        this.displayName = displayName;
    }

    @Override
    public int getLegacyId() {
        return legacyId;
    }

    private btw.modern.Item fc() {
        btw.modern.Item item = btw.modern.Item.itemsList[legacyId];
        return item;
    }

    /** "hook:legacyId" keys already logged: an FC failure is reported once per item and hook. */
    private static final java.util.Set<String> FC_FAILURES_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Logs an exception thrown by FC item code, once per FC item id and hook (with stack trace).
     * Item callbacks run inside packet handlers (PacketUtils rethrows, crashing the integrated
     * server) and inventory/tick paths, so FC errors are caught and reported here instead.
     * Also used by ItemStackMixin for vanilla items with FC replacements.
     */
    public static void logFcFailureOnce(String hook, int fcItemId, Throwable t) {
        if (FC_FAILURES_LOGGED.add(hook + ":" + fcItemId)) {
            LOGGER.warn("FC item {} {} failed (further failures for this item and hook are not logged): {}",
                    fcItemId, hook, t.toString(), t);
        }
    }

    /**
     * True when {@code legacyBlockId} names a populated FC block slot. ProxyRegistry.getBlockId
     * returns 0 for modern-only blocks (deepslate, tuff, granite, ...), and 1.5.2 item code
     * dereferences {@code Block.blocksList[iBlockID]} unguarded -- FCItemTool.onBlockDestroyed
     * (Client FCItemTool.java:66), FCItemClub.onBlockDestroyed (Client FCItemClub.java:58);
     * FCItemAxe (Common FCItemAxe.java:123) null-checks but then calls the FCItemTool super -- so
     * passing such an id NPE'd on blocksList[0] whenever a survival player broke a
     * modern-only block with an FC or FC-replaced tool. The NPE escaped
     * ServerPlayerGameMode.destroyBlock into the packet handler, which rethrows: integrated-server
     * crash. Also used by ItemStackMixin.
     */
    public static boolean isFcBlockId(int legacyBlockId) {
        return legacyBlockId > 0 && legacyBlockId < btw.modern.Block.blocksList.length
                && btw.modern.Block.blocksList[legacyBlockId] != null;
    }

    /**
     * Legacy block id to hand FC onBlockDestroyed for a block a tool just mined (mineBlock runs before
     * ServerPlayerGameMode.destroyBlock removes it, :250/:253), or 0 to skip FC. The world-read id
     * (ProxyRegistry.getWorldBlockId, design_blockmap cross-cluster 1): a modern-only block reads as a
     * ModernBlockStandIn whose getBlockHardness is the MC destroy speed, so FC's tool wear is right; never an
     * id without a block (see isFcBlockId). A liquid id is skipped: getWorldBlockId reads seagrass/kelp as
     * water 8 (triage D5), and FCItemTool would wear the tool on water's hardness 100 for an instant break.
     */
    public static int minedBlockIdForFc(BlockState state, ServerLevel level, BlockPos pos) {
        int blockId = ProxyRegistry.getWorldBlockId(state, level, pos);
        if (!isFcBlockId(blockId)) return 0;
        btw.modern.Material material = btw.modern.Block.blocksList[blockId].blockMaterial;
        return material != null && material.isLiquid() ? 0 : blockId;
    }

    /**
     * The FC item that handles a stack of {@code item}, or null when FC has none: its 1.5.2 item id first
     * (ProxyRegistry.getItemId: ProxyItems, the vanilla item table, flattened subtypes), and only for a
     * BlockItem without an FC item there, its block's ItemBlock. 1.20.1 made carrots, potatoes, seeds,
     * string, redstone, sugar cane and the cauldron/brewing stand/sign/door/bed/skull items BlockItems of
     * their crop/wire/block, where 1.5.2 kept separate item ids (carrot 391 vs the carrot crop 141, seeds
     * 295 vs crops 59; vanilla/client Item.java). Resolving every BlockItem through its block reached the
     * crop's ItemBlock instead of the FC food/seed item, so e.g. eaten carrots ran ItemBlock.onEaten
     * (nothing) while FoodDataMixin had already cancelled vanilla's nutrition. Same order as
     * ProxyRegistry.getOrCreateLegacyId (design_blockmap cross-cluster). Used by ItemStackMixin and
     * ServerPlayerGameModeMixin.
     */
    public static btw.modern.Item fcItemFor(Item item) {
        if (item == null) return null;
        btw.modern.Item fcItem = fcItemAt(ProxyRegistry.getItemId(item));
        if (fcItem == null && item instanceof net.minecraft.world.item.BlockItem bi) {
            fcItem = fcItemAt(ProxyRegistry.getBlockId(bi.getBlock()));
        }
        return fcItem;
    }

    private static btw.modern.Item fcItemAt(int legacyId) {
        return legacyId > 0 && legacyId < btw.modern.Item.itemsList.length
                ? btw.modern.Item.itemsList[legacyId] : null;
    }

    /**
     * The FC entity FC item code should see for {@code target}: an FC puppet's own FC entity (so
     * checks like {@code entity instanceof FCEntitySheep} in FCItemDye/FCItemBreedingHarness
     * match), otherwise the generic bridge (PlayerBridge for players).
     */
    private static btw.modern.EntityLiving fcLivingFor(LivingEntity target) {
        btw.modern.EntityLiving fc = null;
        if (target instanceof ProxyMob pm) fc = pm.getFcEntity();
        else if (target instanceof ProxyAnimal pa) fc = pa.getFcEntity();
        else if (target instanceof ProxyPathfinderMob pp) fc = pp.getFcEntity();
        return fc != null ? fc : LivingEntityBridge.wrapLiving(target);
    }

    /**
     * Per FC item class: does it override either 1.5.2 entity-interaction hook? Only Client
     * FCItemDye, FCItemDung and FCItemBreedingHarness do; Modern-Common Item returns false for
     * both, so other items skip the stack conversion and entity wrapping.
     */
    private static final ClassValue<Boolean> ENTITY_INTERACTION_OVERRIDDEN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return declaredBelowItem(type, "itemInteractionForEntity") || declaredBelowItem(type, "useItemOnEntity");
        }
    };

    private static boolean declaredBelowItem(Class<?> type, String name) {
        try {
            return type.getMethod(name, btw.modern.ItemStack.class, btw.modern.EntityLiving.class)
                    .getDeclaringClass() != btw.modern.Item.class;
        } catch (Throwable t) {
            return true;   // cannot tell: call it (the base implementations return false)
        }
    }

    /**
     * Runs FC's entity interaction for the item in {@code stack} on {@code target}, as 1.5.2
     * EntityPlayer.interactWith -> ItemStack.interactWith did once the entity itself declined
     * (vanilla/client EntityPlayer.java:1182-1206, ItemStack.java:416-419). 1.20.1
     * Player.interactOn calls interactLivingEntity at the same point, with the live hand stack
     * (a copy in creative, like 1.5.2). The shipped FC item classes are the Client variants, which
     * override the 1.5.2 CLIENT name itemInteractionForEntity; the Server variants used
     * useItemOnEntity (vanilla/server Item.java:607, ItemStack.java:394), so both are tried.
     * FC edits the stack in place (FCItemDye.java:64, FCItemBreedingHarness.java:77
     * {@code stackSize--}); that count is applied to {@code stack}. Server only. Never throws.
     *
     * @return true if FC handled the interaction
     */
    public static boolean interactWithEntityFc(ItemStack stack, LivingEntity target) {
        int fcItemId = 0;
        try {
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
            if (fcStack == null || fcStack.itemID <= 0 || fcStack.itemID >= btw.modern.Item.itemsList.length) return false;
            fcItemId = fcStack.itemID;
            btw.modern.Item fcItem = btw.modern.Item.itemsList[fcItemId];
            if (fcItem == null || !ENTITY_INTERACTION_OVERRIDDEN.get(fcItem.getClass())) return false;
            int before = fcStack.stackSize;
            btw.modern.EntityLiving fcTarget = fcLivingFor(target);
            if (!fcItem.itemInteractionForEntity(fcStack, fcTarget) && !fcItem.useItemOnEntity(fcStack, fcTarget)) {
                return false;
            }
            if (fcStack.stackSize != before) stack.setCount(Math.max(fcStack.stackSize, 0));
            return true;
        } catch (Throwable t) {
            logFcFailureOnce("itemInteractionForEntity", fcItemId, t);
            return false;
        }
    }

    // ================================================================
    // Taken from a crafting result → FC onCreated (audit sounds S9)
    // ================================================================

    /**
     * Per FC item class: does it override 1.5.2 Item.onCreated? Thirteen FC items do, all for their crafting
     * sound (e.g. Client FCItemTool.java:124-143, Common FCItemArmor.java:39-54); Modern-Common Item.onCreated is
     * empty, so every other item skips the stack conversion and the player bridging.
     */
    private static final ClassValue<Boolean> ON_CREATED_OVERRIDDEN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("onCreated", btw.modern.ItemStack.class, btw.modern.World.class,
                        btw.modern.EntityPlayer.class).getDeclaringClass() != btw.modern.Item.class;
            } catch (Throwable t) {
                return true;   // cannot tell: call it (the base implementation is empty)
            }
        }
    };

    /**
     * 1.5.2 SlotCrafting/SlotFurnace.onCrafting -> ItemStack.onCrafting -> Item.onCreated (vanilla/client
     * SlotCrafting.java:72-75, ItemStack.java:501-504), which 1.20.1 has as ItemStack.onCraftedBy -> Item.onCraftedBy
     * (ResultSlot/FurnaceResultSlot.checkTakeAchievements, InventoryMenu/CraftingMenu.quickMoveStack), on both sides.
     */
    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        super.onCraftedBy(stack, level, player);
        runFcOnCreated(fc(), stack, level, player);
    }

    /**
     * Runs FC's Item.onCreated for a stack {@code player} just took from a crafting (or smelting) result, on the
     * side this runs on, as 1.5.2 ran it on both. FC plays its crafting sounds only on the client
     * ({@code world.isRemote}, once per tick through m_iTimesCraftedThisTick) through the client player's
     * playSound, which plays locally (vanilla/client EntityPlayerSP.java:663-666): the client gets a client FC
     * world and {@link ClientCrafter}; on the server FC's isRemote guard makes the call a no-op, kept for
     * parity. Used by {@link #onCraftedBy} and by ItemStackMixin for vanilla items with an FC replacement (the
     * vanilla tools, swords, armour, shears), and by FCContainerMenu.ClientCraftResultSlot for FC containers
     * (FC workbench, soulforge), whose server SlotCrafting already calls onCreated. Never throws.
     */
    public static void runFcOnCreated(ItemStack stack, Level level, Player player) {
        if (stack == null || stack.isEmpty()) return;
        runFcOnCreated(fcItemFor(stack.getItem()), stack, level, player);
    }

    private static void runFcOnCreated(btw.modern.Item fcItem, ItemStack stack, Level level, Player player) {
        if (fcItem == null || stack == null || stack.isEmpty() || level == null || player == null) return;
        if (!ON_CREATED_OVERRIDDEN.get(fcItem.getClass())) return;
        try {
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
            if (fcStack == null) return;
            if (level instanceof ServerLevel sl) {
                if (player instanceof ServerPlayer sp) {
                    fcItem.onCreated(fcStack, WorldBridge.getOrCreate(sl), PlayerBridge.getOrCreate(sp));
                }
            } else if (level.isClientSide()) {
                ClientCrafter.onCreated(fcItem, fcStack, level, player);
            }
        } catch (Throwable t) {
            // ResultSlot.onTake runs inside the container click handler (PacketUtils rethrows on the server)
            logFcFailureOnce("onCreated", fcItem.itemID, t);
        }
    }

    /**
     * The 1.5.2 client player FC's client-side onCreated sees: m_iTimesCraftedThisTick counts the crafts of the
     * current client tick (1.5.2 zeroed it in EntityPlayer.onUpdate, vanilla/client EntityPlayer.java:227, and
     * SlotCrafting counted each craft, SlotCrafting.java:184), and playSound plays at the player's feet on this
     * client only (EntityPlayerSP.playSound, EntityPlayerSP.java:663-666) through the shared client sink
     * (LegacyClientSound.playLocal). Built only for client levels, on the render thread; holds its player weakly
     * and no world between calls, so a closed world is never kept reachable.
     */
    private static final class ClientCrafter extends btw.modern.EntityClientPlayerMP {
        /** The local player's crafter (render thread only). */
        private static ClientCrafter current;

        private final java.lang.ref.WeakReference<Player> owner;
        private int craftTick;

        private ClientCrafter(Player player) {
            super((btw.modern.World) null);   // as PlayerBridge: built without a world, given one per call
            this.owner = new java.lang.ref.WeakReference<>(player);
            this.craftTick = player.tickCount;
        }

        static void onCreated(btw.modern.Item fcItem, btw.modern.ItemStack fcStack, Level level, Player player) {
            ClientCrafter crafter = current;
            if (crafter == null || crafter.owner.get() != player) {
                crafter = new ClientCrafter(player);
                current = crafter;
            }
            if (crafter.craftTick != player.tickCount) {
                crafter.craftTick = player.tickCount;
                crafter.m_iTimesCraftedThisTick = 0;
            }
            crafter.posX = player.getX();
            crafter.posY = player.getY();
            crafter.posZ = player.getZ();
            btw.modern.World world = ProxyEntity.createDummyClientWorld(level);
            crafter.worldObj = world;
            try {
                fcItem.onCreated(fcStack, world, crafter);
            } finally {
                crafter.worldObj = null;
                crafter.m_iTimesCraftedThisTick++;
            }
        }

        @Override
        public void playSound(String name, float volume, float pitch) {
            // Only ever built for a client level (runFcOnCreated), so the client-only sink is never reached
            // on a server.
            btw.forge.client.LegacyClientSound.playLocal(posX, posY, posZ, name, volume, pitch, false);
        }
    }

    // ================================================================
    // Vanilla eat sounds where FC's onEaten owns the eat (audit sounds S10)
    // ================================================================

    /**
     * Implemented on ItemStack by ItemStackMixin: true while finishUsingItem runs an eat that FC's 1.5.2
     * ItemFood.onEaten already fed (PlayerBridge.fcFedForEat), whose 'random.burp' FC plays itself
     * (vanilla/server ItemFood.java:71). PlayerMixin then drops Player.eat's PLAYER_BURP, and ItemStackMixin
     * makes LivingEntity.eat's closing eat sound (no 1.5.2 equivalent) {@link #SILENT_SOUND}.
     */
    public interface FcOwnedEat {
        boolean btw$isFcOwnedEat();
    }

    /**
     * A sound event that plays nothing: 1.20.1's client resolves minecraft:intentionally_empty to its silent
     * sound without a missing-sound warning (AbstractSoundInstance.resolve, SoundEngine.play).
     */
    public static final net.minecraft.sounds.SoundEvent SILENT_SOUND = net.minecraft.sounds.SoundEvent
            .createVariableRangeEvent(new net.minecraft.resources.ResourceLocation("minecraft", "intentionally_empty"));

    @Override
    public Component getName(ItemStack stack) {
        btw.modern.Item fcItem = fc();
        if (fcItem != null) {
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
            if (fcStack != null) {
                String name = fcItem.getItemDisplayName(fcStack);
                if (name != null && !name.isEmpty()) {
                    return Component.literal(name);
                }
            }
        }
        return Component.literal(displayName);
    }

    // ================================================================
    // Enchantment glint → FC hasEffect
    // ================================================================

    @Override
    public boolean isFoil(ItemStack stack) {
        btw.modern.Item fcItem = fc();
        if (fcItem != null) {
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
            if (fcStack != null) {
                return fcItem.hasEffect(fcStack);
            }
        }
        return super.isFoil(stack);
    }

    // ================================================================
    // Tooltip lore → FC addInformation
    // ================================================================

    @Override
    public void appendHoverText(ItemStack stack, Level level, java.util.List<Component> tooltip, TooltipFlag flag) {
        btw.modern.Item fcItem = fc();
        if (fcItem != null) {
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
            if (fcStack != null) {
                java.util.List<String> fcTooltip = new java.util.ArrayList<>();
                fcItem.addInformation(fcStack, null, fcTooltip, flag.isAdvanced());
                for (String line : fcTooltip) {
                    if (line != null && !line.isEmpty()) {
                        tooltip.add(Component.literal(line));
                    }
                }
            }
        }
    }

    // ================================================================
    // Right-click in air → FC onItemRightClick
    // ================================================================

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (fc() == null) return super.use(level, player, hand);
        // The FC player exposes only the main hand (InventoryBridge.getCurrentItem; 1.5.2 has no off
        // hand). An off-hand use would run this item's FC code on the main-hand stack, and the
        // write-back below would then consume or damage that unrelated stack.
        if (level instanceof ServerLevel sl && hand == InteractionHand.MAIN_HAND) {
            PlayerBridge pb = PlayerBridge.getOrCreate(player);
            pb.syncFromReal();
            btw.modern.ItemStack fcStack = pb.getCurrentEquippedItem();
            if (fcStack != null) {
                btw.modern.ItemStack result = fcStack;
                try {
                    result = fc().onItemRightClick(fcStack, WorldBridge.getOrCreate(sl), pb);
                } catch (Throwable t) {
                    logFcFailureOnce("onItemRightClick", legacyId, t);
                }
                // 1.5.2 ItemInWorldManager.tryUseItem (vanilla/server ItemInWorldManager.java:338-361):
                // the returned stack becomes the held stack, cleared at stackSize 0 (a full FC bucket
                // returns its empty bucket). Edits FC makes in place -- FCItemThrowable's
                // stackSize-- (FCItemThrowable.java:33), FCItemDynamite's flint-and-steel damage --
                // are only in the PlayerBridge snapshot until written back, slot by slot.
                int slot = player.getInventory().selected;
                if (result != fcStack && slot >= 0 && slot < pb.inventory.mainInventory.length) {
                    pb.inventory.setInventorySlotContents(slot,
                            result != null && result.stackSize > 0 ? result : null);
                }
                pb.syncInventoryToReal();
                return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
            }
        }
        return super.use(level, player, hand);
    }

    // ================================================================
    // Right-click block → FC onItemUse (also handled by BlockBehaviorMixin)
    // ================================================================

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        // Handled by ServerPlayerGameModeMixin.btw$useItemOn which intercepts
        // at HEAD, calls FC's onItemUse, syncs inventory, and cancels the
        // original method (preventing creative-mode count restoration).
        return super.useOn(ctx);
    }

    // ================================================================
    // Finish eating/drinking/using → FC onEaten
    // ================================================================

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (fc() == null) return super.finishUsingItem(stack, level, entity);
        if (level instanceof ServerLevel sl && entity instanceof Player player) {
            PlayerBridge pb = PlayerBridge.getOrCreate(player);
            pb.syncFromReal();
            // The FC player holds only the main hand (InventoryBridge.getCurrentItem; 1.5.2 has no off hand): the
            // selected main-hand stack runs on its live snapshot, any other stack on a detached copy. FC's in-place
            // edits to the stack it is handed (FCItemBucketDrinkable's --stackSize) must never reach an unrelated
            // main-hand stack through the write-back below.
            btw.modern.ItemStack fcStack;
            try {
                fcStack = stack == player.getMainHandItem()
                        ? pb.getCurrentEquippedItem() : ItemStackHelper.toFcStack(stack);
            } catch (Throwable t) {
                logFcFailureOnce("onEaten", legacyId, t);
                return stack;
            }
            if (fcStack != null) {
                // FC's onEaten applies hunger/saturation for ItemFood (and any
                // FC subclass override of onEaten). Nutrition lives in the FC
                // layer now — no bridge-side fixup required.
                btw.modern.ItemStack result = fcStack;
                try {
                    result = fc().onEaten(fcStack, WorldBridge.getOrCreate(sl), pb);
                } catch (Throwable t) {
                    // Runs inside LivingEntity.updatingUsingItem: a throw would crash "Ticking player". The
                    // item is still eaten below: 1.5.2 decrements before the nutrition (vanilla/server
                    // ItemFood.java:69).
                    logFcFailureOnce("onEaten", legacyId, t);
                }

                pb.syncToReal();

                // Consume the item like 1.5.2 ItemFood.onEaten (--stackSize; Modern-Common's leaves it to the
                // bridge). Before the write-back: an FC onEaten that already decremented the held stack itself
                // (FCItemBucketDrinkable.java:66-69) then lands its own count over this one instead of a second
                // item being lost. Not in creative, where 1.20.1 keeps the used stack (LivingEntity.eat:3277,
                // MilkBucketItem.finishUsingItem:26-28) and FC's drinks do too (FCItemBucketDrinkable.java:66,
                // FCItemPotion.java:42): creative chocolate milk was used up. FC ItemFoods never get here in
                // creative: FC refuses the eat (Modern-Common EntityPlayer.canEat:88, disableDamage, as 1.5.2
                // vanilla/server EntityPlayer.java:1964), and a ProxyItem has no FoodProperties for vanilla's
                // Item.use to start the eat instead.
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                // FC's inventory edits: FCItemSoup.onEaten (Common FCItemSoup.java:29-41: chicken soup, chowder,
                // hearty stew) returns the bowl with player.inventory.addItemStackToInventory, which only reaches
                // the InventoryBridge snapshot; the next sync() overwrote it and the bowl was lost. The write-back
                // is selective (InventoryBridge.writeBackAll), so only the slot FC put the bowl in is written: the
                // first bowl stack or empty slot, as in 1.5.2 (vanilla/server EntityPlayer.java:406-426 clears
                // the emptied held slot only after onEaten). A full inventory drops it (PlayerBridge.dropPlayerItem).
                try {
                    pb.syncInventoryToReal();
                } catch (Throwable t) {
                    logFcFailureOnce("onEaten write-back", legacyId, t);
                }
                // 1.5.2 EntityPlayer.onItemUseFinish (vanilla/server EntityPlayer.java:412-422) puts a different stack
                // onEaten returns into the held slot: FCItemBucketDrinkable's empty bucket once the last drink is gone
                // (Common FCItemBucketDrinkable.java:76, chocolate milk), the knitting needles, the carving bone's
                // hook. Dropping it lost the bucket. Returned here, LivingEntity.completeUsingItem (:3022-3025) sets
                // it in the hand used; the write-back above already emptied the main-hand slot FC drained. FC soups
                // return the eaten stack itself, so they keep returning `stack`.
                if (result != null && result != fcStack && result.stackSize > 0) {
                    try {
                        ItemStack replacement = ItemStackHelper.toMcStack(result);
                        if (!replacement.isEmpty()) return replacement;
                    } catch (Throwable t) {
                        logFcFailureOnce("onEaten result", legacyId, t);
                    }
                }
            }
        }
        return stack;
    }

    // ================================================================
    // Release bow / stop using → FC onPlayerStoppedUsing
    // ================================================================

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (fc() == null) { super.releaseUsing(stack, level, entity, timeLeft); return; }
        // Main hand only: the FC player's held item is the main-hand stack (see use()).
        if (level instanceof ServerLevel sl && entity instanceof Player player
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            PlayerBridge pb = PlayerBridge.getOrCreate(player);
            pb.syncFromReal();
            btw.modern.ItemStack fcStack = pb.getCurrentEquippedItem();
            if (fcStack != null) {
                boolean hadBow = fcStack.stackSize > 0;
                try {
                    fc().onPlayerStoppedUsing(fcStack, WorldBridge.getOrCreate(sl), pb, timeLeft);
                } catch (Throwable t) {
                    logFcFailureOnce("onPlayerStoppedUsing", legacyId, t);
                }
                // A bow FC wore out (damageItem, FCItemBow.java:82): the break effect 1.5.2's
                // renderBrokenItemStack played (vanilla/server ItemStack.java:331), sent before the
                // write-back empties the slot, as 1.20.1 ItemStack.hurtAndBreak does (:365-367).
                if (hadBow && fcStack.stackSize <= 0) player.broadcastBreakEvent(InteractionHand.MAIN_HAND);
                // FCItemBow consumes the arrow (InventoryPlayer.consumeInventoryItem,
                // FCItemBow.java:60) and damages the bow (:82) in place; without the write-back the
                // FC bows fired for free and never wore.
                pb.syncInventoryToReal();
            }
        }
    }

    // ================================================================
    // Use animation → FC getItemUseAction
    // ================================================================

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        if (fc() == null) return super.getUseAnimation(stack);
        btw.modern.EnumAction action = fc().getItemUseAction(ItemStackHelper.toFcStack(stack));
        if (action == null) return UseAnim.NONE;
        return switch (action.name()) {
            case "eat" -> UseAnim.EAT;
            case "drink" -> UseAnim.DRINK;
            case "bow" -> UseAnim.BOW;
            case "block" -> UseAnim.BLOCK;
            default -> UseAnim.NONE;
        };
    }

    // ================================================================
    // Use duration → FC getMaxItemUseDuration
    // ================================================================

    @Override
    public int getUseDuration(ItemStack stack) {
        if (fc() == null) return super.getUseDuration(stack);
        return fc().getMaxItemUseDuration(ItemStackHelper.toFcStack(stack));
    }

    // ================================================================
    // Per-tick inventory update → FC onUpdate
    // ================================================================

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (fc() == null) return;
        if (level instanceof ServerLevel sl && entity instanceof Player player) {
            PlayerBridge pb = PlayerBridge.getOrCreate(player);
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
            if (fcStack != null) {
                try {
                    fc().onUpdate(fcStack, WorldBridge.getOrCreate(sl), pb, slot, selected);
                } catch (Throwable t) {
                    // every tick for every FC item in the inventory, inside ServerPlayer.doTick
                    logFcFailureOnce("onUpdate", legacyId, t);
                }
            }
        }
    }

    // ================================================================
    // Hit entity → FC hitEntity
    // ================================================================

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (fc() == null) return super.hurtEnemy(stack, target, attacker);
        // 1.5.2 flow damages the held stack in place (FCItemTool.hitEntity →
        // stack.damageItem(2, attacker)); route through PlayerBridge's live
        // inventory stack + writeback so the durability loss isn't discarded
        // on a throwaway toFcStack copy (same pattern as finishUsingItem).
        if (attacker instanceof Player player && !player.level().isClientSide()) {
            PlayerBridge pb = PlayerBridge.getOrCreate(player);
            pb.syncFromReal();
            btw.modern.ItemStack fcStack = pb.getCurrentEquippedItem();
            if (fcStack != null) {
                boolean result = false;
                try {
                    btw.modern.EntityLiving fcTarget = LivingEntityBridge.wrapLiving(target);
                    result = fc().hitEntity(fcStack, fcTarget, pb);
                } catch (Throwable t) {
                    // Player.attack, inside the interact packet handler (PacketUtils rethrows Errors)
                    logFcFailureOnce("hitEntity", legacyId, t);
                }
                pb.syncInventoryToReal();   // wear FC applied before any throw
                return result;
            }
        }
        try {
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
            if (fcStack != null) {
                btw.modern.EntityLiving fcTarget = LivingEntityBridge.wrapLiving(target);
                btw.modern.EntityLiving fcAttacker = LivingEntityBridge.wrapLiving(attacker);
                return fc().hitEntity(fcStack, fcTarget, fcAttacker);
            }
        } catch (Throwable t) {
            logFcFailureOnce("hitEntity", legacyId, t);
        }
        return false;
    }

    // ================================================================
    // Mine block → FC onBlockDestroyed
    // ================================================================

    @Override
    public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity entity) {
        if (fc() == null) return super.mineBlock(stack, level, state, pos, entity);
        if (level instanceof ServerLevel sl) {
            // Only reached for blocks ServerPlayerGameModeMixin.btw$destroyBlock leaves to vanilla (no FC
            // block: deepslate, tuff, granite, ...). FC gets their world-read id (minedBlockIdForFc), so
            // FCItemTool.onBlockDestroyed (Client FCItemTool.java:64-72) wears the tool as 1.5.2 did for any
            // block of hardness > 0.
            int blockId = minedBlockIdForFc(state, sl, pos);
            if (blockId == 0) return false;
            try {
                // 1.5.2 wears the HELD stack in place (vanilla/server ItemInWorldManager.java:494-501
                // currentStack.onBlockDestroyed). As in hurtEnemy: the PlayerBridge's live snapshot plus a
                // write-back, so FC's wear is not discarded with a throwaway toFcStack copy (FC tools took
                // no durability from modern-only blocks).
                if (entity instanceof Player player && player.getMainHandItem() == stack) {
                    PlayerBridge pb = PlayerBridge.getOrCreate(player);
                    btw.modern.ItemStack held = pb.getCurrentEquippedItem();
                    if (held != null) {
                        try {
                            return fc().onBlockDestroyed(held, WorldBridge.getOrCreate(sl),
                                    blockId, pos.getX(), pos.getY(), pos.getZ(), pb);
                        } finally {
                            boolean broke = held.stackSize <= 0;
                            pb.syncInventoryToReal();   // wear (or a broken tool) FC applied before any throw
                            // the break sound/particles MC's own hurtAndBreak would have sent (DiggerItem)
                            if (broke) player.broadcastBreakEvent(InteractionHand.MAIN_HAND);
                        }
                    }
                }
                btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
                if (fcStack != null) {
                    btw.modern.EntityLiving fcEntity = LivingEntityBridge.wrapLiving(entity);
                    return fc().onBlockDestroyed(fcStack, WorldBridge.getOrCreate(sl),
                            blockId, pos.getX(), pos.getY(), pos.getZ(), fcEntity);
                }
            } catch (Throwable t) {
                logFcFailureOnce("onBlockDestroyed", legacyId, t);
            }
        }
        return false;
    }

    // ================================================================
    // Interact with entity → FC itemInteractionForEntity / useItemOnEntity
    // ================================================================

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                   LivingEntity target, InteractionHand hand) {
        if (fc() == null) return super.interactLivingEntity(stack, player, target, hand);
        // Server-authoritative, like ProxyAnimal.mobInteract: FC's client-side branch would act
        // on client puppets (FCItemBreedingHarness setDead()s the cow it replaces). The server's
        // SUCCESS swings the arm (ServerGamePacketListenerImpl.handleInteract).
        if (!player.level().isClientSide() && interactWithEntityFc(stack, target)) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
