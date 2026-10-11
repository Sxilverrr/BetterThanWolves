package btw.forge.mixin;

import btw.forge.InventoryBridge;
import btw.forge.ItemStackHelper;
import btw.forge.LegacyProxyItem;
import btw.forge.PlayerBridge;
import btw.forge.ProxyItem;
import btw.forge.ProxyRegistry;
import btw.forge.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@link ServerPlayerGameMode#destroyBlock(BlockPos)} at HEAD,
 * BEFORE vanilla removes the block from the world. This is critical because
 * FC's {@code tryHarvestBlock} queries the world for the block at (x,y,z) —
 * if vanilla already removed it, the query returns air and nothing happens.
 *
 * FC's complete harvest pipeline runs here:
 * <ol>
 *   <li>{@code canHarvestBlock} — FC decides if tool is correct</li>
 *   <li>{@code CanConvertBlock} — FC checks if block transforms (log → damaged log)</li>
 *   <li>{@code ConvertBlock} — FC does the transformation, drops bark</li>
 *   <li>{@code harvestBlock} — proper tool drops</li>
 *   <li>{@code OnBlockDestroyedWithImproperTool} — component drops (piles, sawdust)</li>
 * </ol>
 *
 * Zero FC logic in this mixin — it just calls FC's {@code tryHarvestBlock}.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow protected ServerLevel level;
    @Shadow protected ServerPlayer player;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void btw$destroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = level.getBlockState(pos);
        btw.modern.Block fcBlock = ProxyRegistry.getFcBlock(state.getBlock());
        if (fcBlock == null) return; // Not an FC block — let vanilla handle it

        PlayerBridge fcPlayer = PlayerBridge.getOrCreate(player);
        fcPlayer.syncFromReal();

        // FC's complete harvest pipeline — block is still in the world at this point
        btw.modern.ItemInWorldManager mgr = new btw.modern.ItemInWorldManager(
                WorldBridge.getOrCreate(level));
        mgr.thisPlayerMP = fcPlayer;

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
