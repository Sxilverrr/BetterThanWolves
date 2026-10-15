package btw.forge.mixin;

import btw.forge.EntityBridge;
import btw.forge.ItemEntityBridge;
import btw.forge.PlayerBridge;
import btw.forge.ProxyAnimal;
import btw.forge.ProxyBlock;
import btw.forge.ProxyEntity;
import btw.forge.ProxyMob;
import btw.forge.ProxyPathfinderMob;
import btw.forge.ProxyRegistry;
import btw.forge.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts vanilla {@link BlockBehaviour} callbacks and redirects them
 * to FC block behavior when a matching FC block exists for the vanilla block
 * at the given position.
 *
 * ProxyBlocks already override these methods directly, so these injections
 * only fire for vanilla blocks that have FC replacements in
 * {@code btw.modern.Block.blocksList[]}.
 *
 * Ticks are not routed here. Random and scheduled ticks of FC-mapped states are
 * dispatched at the BlockState level by {@link BlockStateRandomTickMixin} and
 * {@link BlockStateBaseTickMixin} under the rule in {@link btw.forge.TickPolicy}. Do not add tick or randomTick injects to this
 * class: a BlockBehaviour-level hook keys on the wrong block (StairBlock passes the
 * stair state to {@code base.tick / randomTick}, so FCBlockStoneBrick.updateTick
 * would run at a stair position), never sees the 1.20.1 classes that override
 * tick or randomTick, and would be a second routing path that ignores the policy.
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviorMixin {


    // ================================================================
    // Helper: get FC block for this BlockBehaviour instance
    // ================================================================

    private btw.modern.Block btw$getFcBlock() {
        // 'this' is a BlockBehaviour, which all Block instances extend.
        // Skip ProxyBlocks — they already handle FC forwarding directly.
        if ((Object) this instanceof ProxyBlock) {
            return null;
        }
        if ((Object) this instanceof Block block) {
            return ProxyRegistry.getFcBlock(block);
        }
        return null;
    }

    /**
     * The FC twin of a post-1.5.2 plant state (btw.forge.TwinRegistry: modern saplings, azaleas, beetroots, the
     * torchflower and pitcher crops, fungi), or null. Identity routing of these plants stays 0 (btw$getFcBlock), but
     * FC's weeds grow under a twin crop (BlockCrops.CanWeedsGrowInBlock, read by FCBlockFarmland through the twin's
     * world id) and stop it (BlockCrops.AttemptToGrow), and 4.B pulls weeds only through the plant's
     * onBlockActivated (Client FCBlockPlants.java:84-102, Modern-Common BlockFlower.onBlockActivated), which runs
     * before the held item: bone meal on a weedy crop pulls the weeds and is kept. Used by btw$use, Stage 1 only.
     */
    private static btw.modern.Block btw$getTwinFcBlock(BlockState state) {
        int id = btw.forge.TwinRegistry.worldIdFor(state);
        return id > 0 && id < btw.modern.Block.blocksList.length ? btw.modern.Block.blocksList[id] : null;
    }

    /**
     * Wraps a Minecraft entity into an FC entity for use in FC block callbacks.
     * Uses EntityBridge as a fallback for vanilla entities without dedicated proxies.
     */
    private btw.modern.Entity btw$wrapEntity(Entity entity) {
        if (entity == null) return null;

        // Player → PlayerBridge
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            PlayerBridge bridge = PlayerBridge.getOrCreate(player);
            bridge.syncFromReal();
            return bridge;
        }
        // ProxyMob → getFcEntity()
        if (entity instanceof ProxyMob proxy) {
            return proxy.getFcEntity();
        }
        // ProxyAnimal → getFcEntity()
        if (entity instanceof ProxyAnimal proxy) {
            return proxy.getFcEntity();
        }
        // ProxyPathfinderMob → getFcEntity()
        if (entity instanceof ProxyPathfinderMob proxy) {
            return proxy.getFcEntity();
        }
        // ProxyEntity → getFcEntity()
        if (entity instanceof ProxyEntity proxy) {
            return proxy.getFcEntity();
        }
        // ItemEntity → its FC EntityItem view (design_items U3b), so `instanceof EntityItem` callbacks work
        // (FCBlockCampfire:442, FCBlockFarmlandBase:153, FCBlockPlanterBase:61, FCBlockNetherGrowth:276) and
        // EntityItem.CanEntityTriggerTripwire() == false applies: 1.5.2 EntityItem passes itself to the block.
        // Null (never the generic EntityBridge) when the item has no view: consumed by an FC block earlier in
        // the same checkInsideBlocks pass, empty, or failed to sync -- the caller skips the FC callback.
        if (entity instanceof ItemEntity item) {
            return ItemEntityBridge.wrap(item);
        }
        // Vanilla entities without FC proxy → use generic EntityBridge
        EntityBridge bridge = EntityBridge.getOrCreate(entity);
        bridge.syncFromReal();
        return bridge;
    }

    // ================================================================
    // getDestroyProgress -> FC getPlayerRelativeBlockHardness
    // ================================================================

    // FC's getPlayerRelativeBlockHardness (vanilla/server Block.java:2340-2361) folds in block hardness,
    // tool effectiveness/speed, potions, water/airborne and FC penalties with the 30/200 divisor. Routed
    // through FCMiningSpeed so the client predicts with its own LocalPlayer bridge and client world (which
    // derives the same legacy metadata as the server, e.g. the log stump bit) and the server uses the
    // ServerPlayer bridge + WorldBridge - never the server bridge from the render thread.
    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void btw$getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos,
                                         CallbackInfoReturnable<Float> cir) {
        btw.modern.Block fcBlock = btw$getFcBlock();
        if (fcBlock == null) return;

        float result = btw.forge.FCMiningSpeed.relativeHardness(fcBlock, player, level, pos);
        if (!Float.isNaN(result)) {
            cir.setReturnValue(result);
        }
        // NaN: FC threw (logged by FCMiningSpeed) -> vanilla formula instead of failing the dig.
    }

    // ================================================================
    // neighborChanged -> FC onNeighborBlockChange
    // ================================================================

    @Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
    private void btw$neighborChanged(BlockState state, Level level, BlockPos pos,
                                      Block neighborBlock, BlockPos neighborPos, boolean moving,
                                      CallbackInfo ci) {
        btw.modern.Block fcBlock = btw$getFcBlock();
        if (fcBlock == null) return;

        if (level instanceof ServerLevel sl) {
            btw.modern.World world = WorldBridge.getOrCreate(sl);
            int neighborId = ProxyRegistry.getNeighbourSourceId(neighborBlock, level, neighborPos);
            fcBlock.onNeighborBlockChange(world, pos.getX(), pos.getY(), pos.getZ(), neighborId);
            // Don't cancel neighborChanged for vanilla blocks — MC needs its own
            // neighbor logic (redstone, dropper triggering, etc.) to run.
        }
    }

    // ================================================================
    // onPlace -> FC onBlockAdded
    // ================================================================

    @Inject(method = "onPlace", at = @At("HEAD"))
    private void btw$onPlace(BlockState state, Level level, BlockPos pos,
                              BlockState oldState, boolean moving, CallbackInfo ci) {
        btw.modern.Block fcBlock = btw$getFcBlock();
        if (fcBlock == null) return;

        if (!state.is(oldState.getBlock()) && level instanceof ServerLevel sl && !WorldBridge.isMetadataOnlyWrite(pos)) {
            btw.modern.World world = WorldBridge.getOrCreate(sl);
            fcBlock.onBlockAdded(world, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    // ================================================================
    // onRemove -> FC breakBlock
    // ================================================================

    @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
    private void btw$onRemove(BlockState state, Level level, BlockPos pos,
                               BlockState newState, boolean moving, CallbackInfo ci) {
        // The FC block FC's world reads see here: the identity mapping, or the log for a bark / stripped log form
        // (ProxyRegistry.getRemovalLegacyId), so cutting a stripped trunk runs FC's log removal (leaf decay check).
        if ((Object) this instanceof ProxyBlock || !((Object) this instanceof Block self)) return;
        int legacyId = ProxyRegistry.getRemovalLegacyId(self);
        if (legacyId <= 0 || legacyId >= btw.modern.Block.blocksList.length) return;
        btw.modern.Block fcBlock = btw.modern.Block.blocksList[legacyId];
        if (fcBlock == null) return;

        if (!state.is(newState.getBlock()) && level instanceof ServerLevel sl && !WorldBridge.isMetadataOnlyWrite(pos)) {
            btw.modern.World world = WorldBridge.getOrCreate(sl);
            int meta = 0; // vanilla blocks don't carry FC metadata
            fcBlock.breakBlock(world, pos.getX(), pos.getY(), pos.getZ(), legacyId, meta);
            // Do NOT cancel — let vanilla handle block entity removal etc.
        }
    }

    // ================================================================
    // use -> FC onBlockActivated
    // ================================================================

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void btw$use(BlockState state, Level level, BlockPos pos,
                          Player player, InteractionHand hand, BlockHitResult hit,
                          CallbackInfoReturnable<InteractionResult> cir) {
        // Skip for ProxyBlocks — they handle FC delegation in ProxyBlock.use()
        if (state.getBlock() instanceof btw.forge.ProxyBlock) return;
        btw.modern.Block fcBlock = btw$getFcBlock();
        // FCMOD-INTEGRATION(1.20.1): modern_plant_growth: a twin plant gets FC's activation (Stage 1 only), so the weeds FC grows under twin crops can be pulled as under FC's own crops
        boolean twin = false;
        if (fcBlock == null) {
            fcBlock = btw$getTwinFcBlock(state);
            twin = fcBlock != null;
        }
        if (fcBlock == null) return;

        if (level instanceof ServerLevel sl) {
            btw.modern.World world = WorldBridge.getOrCreate(sl);
            PlayerBridge fcPlayer = PlayerBridge.getOrCreate(player);
            fcPlayer.syncFromReal();
            int side = hit.getDirection().get3DDataValue();
            Vec3 hitLoc = hit.getLocation();
            float hitX = (float) (hitLoc.x - pos.getX());
            float hitY = (float) (hitLoc.y - pos.getY());
            float hitZ = (float) (hitLoc.z - pos.getZ());

            // Snapshot openContainer before FC call to detect GUI opens
            btw.modern.Container prevContainer = fcPlayer.openContainer;

            // Stage 1: FC block activation (e.g., opening chests, adding spit to campfire)
            boolean result = fcBlock.onBlockActivated(world, pos.getX(), pos.getY(), pos.getZ(),
                    fcPlayer, side, hitX, hitY, hitZ);

            // A twin stands for a 1.20.1 plant whose items stay 1.20.1's (identity id 0): no FC Stage 2 on it, the held
            // item's own use follows (bone meal: BoneMealItemMixin's soil relay).
            if (!result && !twin) {
                // Stage 2: FC item use on block (e.g., flint & steel igniting campfire)
                // This mirrors FC's ItemInWorldManager.activateBlockOrUseItem pipeline
                // (creative: damage/stackSize restored like ItemInWorldManager.java:394-402, see tryPlaceHeldItem)
                btw.modern.ItemStack fcHeld = fcPlayer.getCurrentEquippedItem();
                // The client got PASS from this block's use and predicted the held BlockItem's placement,
                // place sound included, so FC's place sound leaves this player out (WorldBridge.PREDICTING_ACTOR).
                // ProxyBlock.use needs no such scope: its client returns SUCCESS and predicts nothing.
                if (player instanceof net.minecraft.server.level.ServerPlayer sp
                        && player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem) {
                    final btw.modern.ItemStack held = fcHeld;
                    result = WorldBridge.withPredictingActor(sp, () -> ProxyBlock.tryPlaceHeldItem(held, player,
                            fcPlayer, world, pos, side, hitX, hitY, hitZ));
                } else {
                    result = ProxyBlock.tryPlaceHeldItem(fcHeld, player, fcPlayer, world, pos,
                            side, hitX, hitY, hitZ);
                }
            }

            // Check if FC opened a container GUI and open the MC menu
            btw.forge.ContainerBridge.checkAndOpenContainer(fcPlayer, prevContainer);

            // FC edits the held FC stack in place (e.g. --stackSize when planting/feeding, flint & steel
            // damage) and nothing applies that natively on this path - land it in the real MC inventory, as
            // ProxyBlock.use does. Selective write-back: only slots FC changed are written (InventoryBridge);
            // a creative Stage 2 was already undone above, so it writes nothing.
            fcPlayer.syncInventoryToReal();

            if (result) {
                cir.setReturnValue(InteractionResult.SUCCESS);
            }
        }
    }

    // ================================================================
    // entityInside -> FC onEntityCollidedWithBlock
    // ================================================================

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void btw$entityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                   CallbackInfo ci) {
        btw.modern.Block fcBlock = btw$getFcBlock();
        if (fcBlock == null) return;

        if (level instanceof ServerLevel sl) {
            btw.modern.World world = WorldBridge.getOrCreate(sl);
            btw.modern.Entity fcEntity = btw$wrapEntity(entity);
            if (fcEntity != null) {
                fcBlock.onEntityCollidedWithBlock(world, pos.getX(), pos.getY(), pos.getZ(), fcEntity);
            }
            ci.cancel();
        }
    }

    // ================================================================
    // getSignal -> FC isProvidingWeakPower
    // ================================================================

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void btw$getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction,
                                CallbackInfoReturnable<Integer> cir) {
        btw.modern.Block fcBlock = btw$getFcBlock();
        if (fcBlock == null) return;

        if (level instanceof ServerLevel sl) {
            btw.modern.World world = WorldBridge.getOrCreate(sl);
            int result = fcBlock.isProvidingWeakPower(world, pos.getX(), pos.getY(), pos.getZ(),
                    direction.get3DDataValue());
            cir.setReturnValue(result);
        }
    }

    // ================================================================
    // getDirectSignal -> FC isProvidingStrongPower
    // ================================================================

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void btw$getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction,
                                      CallbackInfoReturnable<Integer> cir) {
        btw.modern.Block fcBlock = btw$getFcBlock();
        if (fcBlock == null) return;

        if (level instanceof ServerLevel sl) {
            btw.modern.World world = WorldBridge.getOrCreate(sl);
            int result = fcBlock.isProvidingStrongPower(world, pos.getX(), pos.getY(), pos.getZ(),
                    direction.get3DDataValue());
            cir.setReturnValue(result);
        }
    }

    // NOTE: getDrops is NOT intercepted. FC's complete drop system runs through
    // Block.harvestBlock() or Block.OnBlockDestroyedWithImproperTool() called from
    // BlockMixin.btw$playerDestroy. FC code internally calls dropBlockAsItem() →
    // dropBlockAsItemWithChance() → dropBlockAsItem_do() → world.spawnEntityInWorld()
    // which creates real MC ItemEntities via EntityProxyFactory.

    // ================================================================
    // isSignalSource -> FC canProvidePower
    // ================================================================

    @Inject(method = "isSignalSource", at = @At("HEAD"), cancellable = true)
    private void btw$isSignalSource(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        btw.modern.Block fcBlock = btw$getFcBlock();
        if (fcBlock == null) return;

        cir.setReturnValue(fcBlock.canProvidePower());
    }

    // ================================================================
    // attack -> FC onBlockClicked
    // ================================================================

    @Inject(method = "attack", at = @At("HEAD"))
    private void btw$attack(BlockState state, Level level, BlockPos pos, Player player,
                             CallbackInfo ci) {
        btw.modern.Block fcBlock = btw$getFcBlock();
        if (fcBlock == null) return;

        if (level instanceof ServerLevel sl) {
            btw.modern.World world = WorldBridge.getOrCreate(sl);
            fcBlock.onBlockClicked(world, pos.getX(), pos.getY(), pos.getZ(), null);
        }
    }
}
