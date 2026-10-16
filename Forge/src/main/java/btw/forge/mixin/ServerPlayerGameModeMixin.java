package btw.forge.mixin;

import btw.forge.BoneMealPolicy;
import btw.forge.InventoryBridge;
import btw.forge.ItemStackHelper;
import btw.forge.LegacyProxyItem;
import btw.forge.PlayerBridge;
import btw.forge.ProxyItem;
import btw.forge.ProxyRegistry;
import btw.forge.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): fc_harvest_events (INTEGRATIONS #022, FC actions visible to other mods)
 *
 * <p><b>Feature:</b> 1.20.1 / Forge's player block-break contract, {@code ServerPlayerGameMode.destroyBlock}
 * (ServerPlayerGameMode.java:229-262): it posts {@code BlockEvent.BreakEvent} through
 * {@code ForgeHooks.onBlockBreakEvent} (ForgeHooks.java:513-565) before anything is removed, which claim and
 * protection mods cancel and LSO thirst / Cold Sweat listen to; that call also pre-cancels for spectators, for
 * adventure players without a CanDestroy tag and for a held item whose {@code canAttackBlock} is false (a sword in
 * creative). It then honours {@code IForgeItem.onBlockStartBreak} for the held item. The same class carries two
 * more hook groups: right-click on a block ({@code useItemOn} HEAD, FC {@code onItemUse}; RETURN, the placed
 * block's facing) and right-click in air ({@code useItem}, FC {@code onItemRightClick}).</p>
 *
 * <p><b>Conflict with FC:</b> FC's harvest pipeline ({@code ItemInWorldManager.tryHarvestBlock}: FC
 * {@code canHarvestBlock}, then {@code CanConvertBlock}/{@code ConvertBlock} (log to chewed log, bark), then
 * {@code harvestBlock} or {@code OnBlockDestroyedWithImproperTool}) reads the block from the world, so it must run
 * BEFORE vanilla removes it: this mixin cancels {@code destroyBlock} at HEAD for every block with an FC identity.
 * That cancel also skipped the BreakEvent, so claim, protection and thirst handlers never saw an FC harvest, and
 * direct {@code destroyBlock} callers (machines, fake players, vein-miners; {@code handleBlockBreakAction} checks
 * {@code blockActionRestricted} itself at START, ServerPlayerGameMode.java:149) bypassed the spectator and
 * adventure rules. The FC pipeline had no creative branch either, so creative breaks chewed logs, dropped items and
 * wore tools (1.5.2 just removes the block, vanilla/server ItemInWorldManager.java:421-444).</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): the FC harvest keeps its HEAD cancel and runs inside Forge's
 * contract, posting the BreakEvent and honouring {@code onBlockStartBreak} in vanilla's order, then hands FC the
 * player's game mode so FC's own 1.5.2 creative branch decides creative breaks.</p>
 *
 * <p><b>Rebalance:</b> none; 4.B parity plus 1.20.1's break rules. A cancelled BreakEvent (or an
 * {@code onBlockStartBreak} that claims the break) leaves the FC block in place and returns false, so
 * {@code destroyAndAck} resends it. Another mod's BreakEvent listener or {@code onBlockStartBreak} that throws
 * refuses the break the same way (logged once per block id, the block resent), which is vanilla's effective outcome
 * without the per-hit FATAL task log or a LinkageError crash. Creative with a sword can no longer break FC blocks
 * (the 1.20.1 rule for every block). Adventure keeps 1.20.1's rule (CanDestroy), not 1.5.2's {@code canCurrentToolHarvestBlock} clause. The
 * event fires once per FC destroy call, including FC conversion stages: each log-chewing hit (Client
 * FCBlockLog.ConvertBlock) is one BreakEvent, so a thirst mod charges per hit. The event's XP is ignored: FC drops
 * its own XP in {@code harvestBlock}; the BreakEvent constructor's {@code getExpDrop} draw uses
 * {@code level.random}, and FC draws from WorldBridge's own {@code java.util.Random} (WorldBridge.java:108), so FC's
 * random stream is unchanged.</p>
 *
 * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code destroyBlock(BlockPos)}, cancellable, as before.
 * For a block with an FC identity it posts the BreakEvent and checks {@code onBlockStartBreak} first, then runs FC's
 * {@code tryHarvestBlock}. {@code GameMasterBlock} is unreachable (legacy 137, the command block, has no FC block)
 * and {@code blockActionRestricted} (Player.java:186-197) is the condition the BreakEvent pre-cancel already
 * applies, so neither is repeated. Blocks without an FC identity (post-1.5.2 blocks, twin plants, the bark and
 * stripped log forms, Dynamic Trees blocks) keep vanilla {@code destroyBlock}, which posts the event itself
 * (:229), so the event never fires twice. {@code useItemOn} and {@code useItem} are HEAD injects and
 * {@code btw$fixBlockFacing} a RETURN inject, unchanged.</p>
 *
 * <p><b>Alternative considered and rejected:</b> running the FC harvest after Forge's event, at a later point of
 * vanilla's {@code destroyBlock}: by then vanilla has removed the block and spawned its drops, and FC's
 * {@code tryHarvestBlock} reads the block from the world, which is the reason for the HEAD cancel. Posting the event
 * from a BreakEvent listener of BTW's own: it would not see direct callers' game modes or onBlockStartBreak, and
 * would order differently from every other block.</p>
 */
// FCMOD-INTEGRATION(1.20.1): fc_harvest_events: an FC harvest cancels vanilla destroyBlock, so it must post Forge's BreakEvent and honour onBlockStartBreak itself
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow protected ServerLevel level;
    @Shadow protected ServerPlayer player;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void btw$destroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = level.getBlockState(pos);
        btw.modern.Block fcBlock = ProxyRegistry.getFcBlock(state.getBlock());
        if (fcBlock == null) return; // Not an FC block — let vanilla handle it

        // FCMOD-INTEGRATION(1.20.1): fc_harvest_events: Forge's break contract in vanilla's order (destroyBlock:229-242) before the FC pipeline, so claim, protection and thirst handlers see FC harvests
        // onBlockBreakEvent pre-cancels spectators, adventure without CanDestroy and canAttackBlock=false (a creative
        // sword), sends the client the air update first and resends the block (and its BE data) on a cancel. Its XP
        // is ignored: FC drops its own. Fires once per FC destroy call, conversion stages (log chewing) included.
        ServerPlayerGameMode self = (ServerPlayerGameMode) (Object) this;
        GameType gameMode = self.getGameModeForPlayer();
        boolean refused;
        try {
            refused = ForgeHooks.onBlockBreakEvent(level, gameMode, player, pos) == -1
                    || player.getMainHandItem().onBlockStartBreak(pos, player);
        } catch (RuntimeException | LinkageError e) {
            // FCMOD-INTEGRATION(1.20.1): fc_harvest_events: a third-party BreakEvent listener or onBlockStartBreak that throws refuses the break (fail closed) instead of escaping into the packet handler
            // Vanilla's effective outcome (the throw aborts destroyBlock, the block stays) without the FATAL task log
            // per hit or a LinkageError crash; a protection listener that failed is never bypassed. The air update
            // onBlockBreakEvent may already have sent is undone here, also for direct callers that get no resend.
            ProxyItem.logFcFailureOnce("BreakEvent/onBlockStartBreak of another mod (legacy block id)",
                    fcBlock.blockID, e);
            if (player.connection != null) {
                player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
            }
            refused = true;
        }
        if (refused) {
            cir.setReturnValue(false);
            return;
        }

        PlayerBridge fcPlayer = PlayerBridge.getOrCreate(player);
        fcPlayer.syncFromReal();

        // FC's complete harvest pipeline — block is still in the world at this point
        btw.modern.ItemInWorldManager mgr = new btw.modern.ItemInWorldManager(
                WorldBridge.getOrCreate(level));
        mgr.thisPlayerMP = fcPlayer;
        // FCMOD-INTEGRATION(1.20.1): fc_harvest_events: FC's 1.5.2 creative branch keys on the manager's game type (vanilla/server ItemInWorldManager.java:421-444); 1.20.1 GameType ids 0-2 are 1.5.2's
        // Spectator (3) never gets here: the BreakEvent pre-cancel above refuses it. setGameType only stores it
        // (Modern-Common), the abilities stay 1.20.1's.
        mgr.setGameType(btw.modern.EnumGameType.getByID(gameMode.getId()));

        boolean handled = true;
        try {
            mgr.tryHarvestBlock(pos.getX(), pos.getY(), pos.getZ(), 1);
        } catch (Throwable t) {
            // Packet handler: PacketUtils rethrows, which crashes the integrated server. FC may already
            // have removed/converted the block, dropped items or worn the tool, so vanilla must not run
            // its own removal/drops on top: still write back and cancel. Report success only if the block
            // changed; false makes destroyAndAck resend the block, undoing the client's predicted break.
            ProxyItem.logFcFailureOnce("tryHarvestBlock (legacy block id)", fcBlock.blockID, t);
            handled = level.getBlockState(pos) != state;
        }

        // Sync FC item state (damage, stackSize) back to MC inventory.
        // mgr.lastHeldStack is the FC ItemStack that was modified inside
        // tryHarvestBlock (damageItem, stackSize changes); null after a throw, which writes the
        // held slot's own FC state.
        if (fcPlayer.inventory instanceof InventoryBridge invBridge) {
            invBridge.writeBackCurrentItem(mgr.lastHeldStack);
        }

        // Tell vanilla we handled it — don't let vanilla do its own removal/drops
        cir.setReturnValue(handled);
    }

    // ================================================================
    // useItemOn → FC Item.onItemUse (right-click on block)
    // Catches vanilla items with FC replacements when used on non-ProxyBlocks.
    // ProxyBlock.use() handles this for ProxyBlocks, but vanilla blocks don't
    // trigger ProxyBlock.use().
    // ================================================================

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void btw$useItemOn(ServerPlayer serverPlayer, Level world,
                                ItemStack mcStack, InteractionHand hand,
                                net.minecraft.world.phys.BlockHitResult hitResult,
                                CallbackInfoReturnable<InteractionResult> cir) {
        if (mcStack.isEmpty() || !(world instanceof net.minecraft.server.level.ServerLevel sl)) return;

        // The FC player exposes only the main hand (InventoryBridge.getCurrentItem; 1.5.2 has no off
        // hand). The 1.20.1 client retries with the OFF hand whenever the main hand did not consume
        // (Minecraft.startUseItem tries each hand in turn); running the off-hand item's FC onItemUse
        // there acted on, and wrote back, the main-hand snapshot (an off-hand FC seed planted by
        // decrementing the main-hand tool's stack). The off hand stays vanilla.
        if (hand != InteractionHand.MAIN_HAND) return;

        // BlockItems: let MC handle placement, we fix facing in the TAIL injection
        if (mcStack.getItem() instanceof net.minecraft.world.item.BlockItem) return;

        net.minecraft.core.BlockPos pos = hitResult.getBlockPos();

        // Look up FC item — ProxyItems (FC non-block items like seeds, tools)
        // and vanilla items with FC replacements (hoe, shears, etc.)
        btw.modern.Item fcItem = null;
        if (mcStack.getItem() instanceof btw.forge.ProxyItem) {
            int proxyLegacyId = ProxyRegistry.getItemId(mcStack.getItem());
            if (proxyLegacyId > 0 && proxyLegacyId < btw.modern.Item.itemsList.length) {
                fcItem = btw.modern.Item.itemsList[proxyLegacyId];
            }
        } else {
            int legacyId = ProxyRegistry.getItemId(mcStack.getItem());
            if (legacyId > 0 && legacyId < btw.modern.Item.itemsList.length) {
                fcItem = btw.modern.Item.itemsList[legacyId];
            }
        }
        if (fcItem == null) return;

        PlayerBridge fcPlayer = PlayerBridge.getOrCreate(serverPlayer);
        fcPlayer.syncFromReal();
        btw.modern.ItemStack fcStack = fcPlayer.getCurrentEquippedItem();
        if (fcStack == null) return;

        // FCMOD-INTEGRATION(1.20.1): bone_meal: 4.B activates the block before the held bone meal, so bone meal must not pre-empt that order here
        // FC dye 15 is bone meal (FCItemDye.onItemUse acts on nothing else, Client FCItemDye.java:34-47). 1.5.2 runs
        // the clicked block's onBlockActivated before the held item unless the player sneaks (vanilla/server
        // ItemInWorldManager.java:376-407), so bone meal on a weedy plant pulls the weeds and is not used
        // (FCBlockPlants.onBlockActivated, Client FCBlockPlants.java:84-102). Pre-empting here ran FCItemDye first,
        // and before Forge's RightClickBlock event and the spectator check (ServerPlayerGameMode.java:323-332). Left to
        // 1.20.1, the same order holds: blockstate.use runs FC activation, then FCItemDye (BlockBehaviorMixin.btw$use /
        // ProxyBlock.use Stage 1 and 2), and BoneMealItem.useOn applies FC fertilizer for a sneaking player and any
        // target without Stage 2 (BoneMealItemMixin, BoneMealPolicy.onBoneMealUseOn). 1.20.1 white dye reads as the same
        // FC stack (LegacyVariants.java:159) and is not fertilizer, so it never runs FCItemDye here either.
        if (BoneMealPolicy.isFcBoneMeal(fcItem, fcStack)) return;

        int side = hitResult.getDirection().get3DDataValue();
        net.minecraft.world.phys.Vec3 hitLoc = hitResult.getLocation();
        float hitX = (float)(hitLoc.x - pos.getX());
        float hitY = (float)(hitLoc.y - pos.getY());
        float hitZ = (float)(hitLoc.z - pos.getZ());

        // 1.5.2 ItemInWorldManager.activateBlockOrUseItem (vanilla/server ItemInWorldManager.java:394-402)
        // restores a creative player's held stack (damage and size) around tryPlaceItemIntoWorld. 1.20.1
        // restores only the count, around ItemStack.useOn (ServerPlayerGameMode.java:353-356), which this
        // HEAD inject pre-empts by returning CONSUME -- so FC seeds, hoes and flint and steel used in
        // creative were decremented/damaged and the write-back below committed it. Same restore as
        // ProxyBlock.tryPlaceHeldItem.
        boolean creative = serverPlayer.isCreative();
        int damageBefore = fcStack.getItemDamage();
        int sizeBefore = fcStack.stackSize;
        boolean result = false;
        try {
            result = fcItem.onItemUse(fcStack, fcPlayer,
                    WorldBridge.getOrCreate(sl),
                    pos.getX(), pos.getY(), pos.getZ(), side, hitX, hitY, hitZ);
        } catch (Throwable t) {
            // Packet handler: PacketUtils rethrows, which crashes the integrated server. No return:
            // FC may already have edited the world and the held stack, so the write-back still runs.
            ProxyItem.logFcFailureOnce("onItemUse", fcItem.itemID, t);
        }
        if (creative) {
            fcStack.setItemDamage(damageBefore);
            fcStack.stackSize = sizeBefore;
        }

        if (fcPlayer.inventory instanceof InventoryBridge invBridge) {
            invBridge.writeBackCurrentItem(fcStack);
        }

        if (result) {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }

    /**
     * Runs AFTER vanilla useItemOn completes. For BlockItem placements on
     * integrated server, the client prediction places the block with correct
     * facing, but the server's BlockItem.place() fails canPlace() because
     * the block is already there. This ensures blocks always get the correct
     * facing from the server player's look direction.
     */
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void btw$fixBlockFacing(ServerPlayer serverPlayer, Level world,
                                     ItemStack mcStack, InteractionHand hand,
                                     net.minecraft.world.phys.BlockHitResult hitResult,
                                     CallbackInfoReturnable<InteractionResult> cir) {
        if (!(mcStack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) return;
        if (!(world instanceof ServerLevel)) return;

        net.minecraft.core.BlockPos placePos = hitResult.getBlockPos().relative(hitResult.getDirection());
        net.minecraft.world.level.block.state.BlockState placed = world.getBlockState(placePos);

        if (!placed.is(bi.getBlock())) return;

        if (placed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            net.minecraft.core.Direction correct = net.minecraft.core.Direction.orderedByNearest(serverPlayer)[0].getOpposite();
            net.minecraft.world.level.block.state.BlockState fixed = placed.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, correct);
            if (!fixed.equals(placed)) {
                world.setBlock(placePos, fixed, 2);
            }
        } else if (placed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            net.minecraft.core.Direction correct = serverPlayer.getDirection().getOpposite();
            net.minecraft.world.level.block.state.BlockState fixed = placed.setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, correct);
            if (!fixed.equals(placed)) {
                world.setBlock(placePos, fixed, 2);
            }
        }
    }

    // ================================================================
    // useItem → FC Item.onItemRightClick (right-click in air)
    // This catches ALL items — vanilla items with FC replacements AND BTW items.
    // ================================================================

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void btw$useItem(ServerPlayer serverPlayer, Level world,
                              ItemStack mcStack, InteractionHand hand,
                              CallbackInfoReturnable<InteractionResult> cir) {
        if (mcStack.isEmpty()) return;

        // ProxyItems dispatch FC onItemRightClick themselves: useItem goes on to ItemStack.use ->
        // ProxyItem.use (ServerPlayerGameMode.java:286), which runs it main-hand only, applies FC's
        // returned stack (1.5.2 ItemInWorldManager.tryUseItem) and writes the inventory back. Running
        // it here as well ran FC twice per click: soul urns, dynamite and soul-sand piles threw two
        // entities for one consumed item, FC buckets and the baited fishing rod acted twice.
        // ProxyArmorItems lose nothing: Modern-Common ItemArmor has no onItemRightClick (MC
        // ArmorItem.use equips).
        if (mcStack.getItem() instanceof LegacyProxyItem) return;
        // The FC player exposes only the main hand (1.5.2 has no off hand): an off-hand use ran the
        // off-hand item's FC code on the main-hand snapshot (getCurrentEquippedItem below).
        if (hand != InteractionHand.MAIN_HAND) return;

        // Look up the FC item for whatever the player is holding (the 1.5.2 item id first: carrots,
        // seeds, string, ... are BlockItems in 1.20.1, see ProxyItem.fcItemFor)
        btw.modern.Item fcItem = ProxyItem.fcItemFor(mcStack.getItem());
        if (fcItem == null) return; // No FC item — let vanilla handle it
        // Creative: FC's ItemFood.onItemRightClick refuses (EntityPlayer.canEat is false while
        // disableDamage) and plays OnCantConsume, but 1.20.1 Player.canEat lets an invulnerable
        // player eat and vanilla Item.use starts it anyway. Leave creative eating to vanilla, silently.
        if (fcItem instanceof btw.modern.ItemFood && serverPlayer.getAbilities().invulnerable) return;

        PlayerBridge fcPlayer = PlayerBridge.getOrCreate(serverPlayer);
        fcPlayer.syncFromReal();
        btw.modern.World fcWorld = WorldBridge.getOrCreate(level);

        btw.modern.ItemStack fcStack = fcPlayer.getCurrentEquippedItem();
        if (fcStack == null) return;

        // Call FC's onItemRightClick — FC decides what happens
        btw.modern.ItemStack result;
        try {
            result = fcItem.onItemRightClick(fcStack, fcWorld, fcPlayer);
        } catch (Throwable t) {
            // Packet handler: PacketUtils rethrows, which crashes the integrated server.
            ProxyItem.logFcFailureOnce("onItemRightClick", fcItem.itemID, t);
            return;
        }

        // If FC returned a different stack (consumed item, created new item), update
        if (result != fcStack && result != null) {
            // FC changed the held item — sync back
            // The FC code may have modified stack size, damage, etc.
        }

        // Check if FC's item has a use duration (food, bow, etc.)
        // If so, tell MC to start the "using item" animation
        int useDuration = fcItem.getMaxItemUseDuration(fcStack);
        if (useDuration > 0) {
            // FC item wants a use duration — let vanilla handle the use() call
            // which will start the using animation and eventually call
            // finishUsingItem/releaseUsing. ProxyItem handles those for FC items.
            // For vanilla items with FC replacements, we need to NOT cancel here.
            return;
        }

        // For instant-use items (no duration), FC already handled the interaction
        // Don't cancel — let vanilla also process if FC didn't consume
    }
}
