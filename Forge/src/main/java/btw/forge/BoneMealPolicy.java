package btw.forge;

import btw.forge.farming.FcSoil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * The bone-meal rule: bone meal is FC fertilizer and nothing else, as in 4.B.
 *
 * <p><b>4.B.</b> {@code FCItemDye.onItemUse} acts only on dye 15 and calls {@code ApplyBoneMeal}, which is the clicked
 * block's {@code AttemptToApplyFertilizerTo} (Client FCItemDye.java:34-47, 88-99; 4.B SrcServer FCItemDye.java:16-30,
 * 71-82). The item is used up exactly when that returns true. Plants relay it to their soil
 * (FCBlockPlants.AttemptToApplyFertilizerTo, Client FCBlockPlants.java:129-143, ported to btw.modern.BlockFlower),
 * which FC farmland and planters take once (FCBlockFarmlandBase.java:182-192, FCBlockPlanter.java:131-143,
 * FCBlockPlanterSoil.java:46-56). There are no particles and no sound. Vanilla's growth code
 * ({@code ItemDye.func_96604_a}, vanilla/server ItemDye.java:121-274) is dead in 4.B, because FCItemDye.onItemUse
 * never calls super and FC emptied DispenserBehaviorDye (vanilla/server DispenserBehaviorDye.java:23-55).</p>
 *
 * <p><b>Routes</b> ({@link #route}): an FC-mapped block ({@link Route#FC_TARGET}) runs its own 4.B fertilizer rule; a
 * post-1.5.2 plant whose growth BTW governs ({@link Route#SOIL_RELAY}, {@code TickPolicy.isFcGrowthGoverned}) relays
 * the fertilizer to the soil below its lowest cell, as FCBlockPlants does; so does, at its position
 * ({@link #route(BlockGetter, BlockPos, BlockState)}), a modded crop, sapling or fc_soil_plants member standing on BTW
 * soil that takes it ({@code btw.forge.farming.FcSoil.governsAt}, INTEGRATIONS #026); every other block keeps 1.20.1
 * bone meal ({@link Route#VANILLA}). Bone meal on an owned route never runs 1.20.1's instant growth
 * ({@code BonemealableBlock.performBonemeal}) or {@code BoneMealItem.growWaterPlant}, and nothing happens when FC
 * declines: the item is kept.</p>
 *
 * <p><b>Paths.</b></p>
 * <ul>
 *   <li>Player use, not sneaking: 1.20.1 runs {@code blockstate.use} first (ServerPlayerGameMode.useItemOn), where
 *       BlockBehaviorMixin.btw$use / ProxyBlock.use run FC's onBlockActivated (the plant's weed pull) and then
 *       FCItemDye (Stage 2). That is 4.B's ItemInWorldManager.activateBlockOrUseItem order (vanilla/server
 *       ItemInWorldManager.java:376-407); ServerPlayerGameModeMixin leaves bone meal to it
 *       ({@link #isFcBoneMeal}).</li>
 *   <li>Player use otherwise (sneaking, the off hand, a target without Stage 2): {@code BoneMealItem.useOn}, through
 *       BoneMealItemMixin and {@link #onBoneMealUseOn}.</li>
 *   <li>Other callers of {@code BoneMealItem.applyBonemeal} (mod machines, {@code growCrop}): {@link #onBonemeal}.</li>
 *   <li>Direct {@code performBonemeal} / {@code isValidBonemealTarget} callers: BonemealableGateMixin
 *       ({@link #governsGrowth(BlockGetter, BlockPos, BlockState)}).</li>
 *   <li>The vanilla dispenser ejects bone meal ({@link Setup}); the FC block dispenser spits it out
 *       (btw.modern.Item.OnItemUsedByBlockDispenser). An ejected item fertilizes the FC soil it lands on, as in 4.B
 *       (FCBlockFarmlandBase / FCBlockPlanterBase.onEntityCollidedWithBlock, through the item bridges).</li>
 * </ul>
 *
 * <p>Only {@code minecraft:bone_meal} is fertilizer ({@link #isFertilizer}). LegacyVariants also reads 1.20.1 white dye
 * as FC dye 15 (LegacyVariants.java:159), which 4.B never had as a separate item.</p>
 *
 * <p>Modded plants on BTW soil (INTEGRATIONS #026) use this extension point: the positional
 * {@link #route(BlockGetter, BlockPos, BlockState)} adds {@code FcSoil.governsAt} as a {@link Route#SOIL_RELAY} and
 * reuses {@link #relayToSoil}, {@link #onBoneMealUseOn} and the {@link #onBonemeal} listener. On accepting BTW soil,
 * bone meal on such a plant is used up only when the soil takes it (1087 becomes 1088) and the plant does not grow;
 * otherwise it is kept and nothing happens (a Dynamic Trees sapling on grass declines, so bone meal never grows it). No
 * particles or sound, as FC fertilizer has none.</p>
 */
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public final class BoneMealPolicy {

    private BoneMealPolicy() {}

    /** 1.5.2 Item.dyePowder, FCItemDye (vanilla/server Item.java:278: item 95 + 256). Used for failure logs. */
    private static final int DYE_ITEM_ID = 351;

    /** FC dye damage for bone meal (FCItemDye.onItemUse, Client FCItemDye.java:36). */
    public static final int BONE_MEAL_DAMAGE = 15;

    /** How bone meal treats a block. */
    public enum Route {
        /** An FC-mapped block: FCItemDye.ApplyBoneMeal, its own AttemptToApplyFertilizerTo. */
        FC_TARGET,
        /** A post-1.5.2 plant whose growth BTW governs: FCBlockPlants' relay to the soil below its lowest cell. */
        SOIL_RELAY,
        /** Everything else: 1.20.1 bone meal. */
        VANILLA
    }

    /**
     * The route of {@code state}. FC-mapped blocks (vanilla ids and ProxyBlocks, {@link ProxyRegistry#getBlockId}) are
     * {@link Route#FC_TARGET} whatever they are, so the grass block, netherrack, sand and cocoa get 4.B's "nothing"
     * instead of 1.20.1's spread, grass, seagrass or growth. Pure: reads only the state and FC's static tables.
     */
    public static Route route(BlockState state) {
        if (!BTWLifecycle.isFcReady()) return Route.VANILLA;
        if (ProxyRegistry.getBlockId(state.getBlock()) != 0) return Route.FC_TARGET;
        if (TickPolicy.isFcGrowthGoverned(state)) return Route.SOIL_RELAY;
        return Route.VANILLA;
    }

    /** True when 1.20.1 bone-meal growth must never run on {@code state} ({@link #route(BlockState)}). */
    public static boolean governsGrowth(BlockState state) {
        return route(state) != Route.VANILLA;
    }

    /**
     * The route of {@code state} at {@code pos}: {@link #route(BlockState)}, and else {@link Route#SOIL_RELAY} for a
     * modded plant on BTW soil that takes it ({@code FcSoil.governsAt}: a modded crop on FC farmland or a planter, a
     * modded sapling or fc_soil_plants member on FC grass, dirt or a planter). On the client the soil is read on the
     * render thread's read-only FC world, so the swing prediction agrees with the server.
     */
    // FCMOD-INTEGRATION(1.20.1): modded_plant_rules: bone meal on a modded plant on BTW soil is FC fertilizer for that soil, as on BTW's own plants
    public static Route route(BlockGetter level, BlockPos pos, BlockState state) {
        Route r = route(state);
        if (r != Route.VANILLA) return r;
        return level != null && pos != null && FcSoil.governsAt(level, pos, state) ? Route.SOIL_RELAY : Route.VANILLA;
    }

    /** True when 1.20.1 bone-meal growth must never run on {@code state} at {@code pos} (BonemealableGateMixin). */
    public static boolean governsGrowth(BlockGetter level, BlockPos pos, BlockState state) {
        return route(level, pos, state) != Route.VANILLA;
    }

    /** True for the one item that is FC fertilizer: {@code minecraft:bone_meal}, FC's (351, 15). */
    public static boolean isFertilizer(ItemStack stack) {
        return stack.is(Items.BONE_MEAL);
    }

    /**
     * True when the FC player holds FC dye 15, which is bone meal to FC (FCItemDye.onItemUse acts on nothing else,
     * Client FCItemDye.java:34-47). ServerPlayerGameModeMixin's HEAD pre-emption skips it: bone meal goes through
     * 1.20.1's own order (activation first, then {@code useOn}), and 1.20.1 white dye, which reads as the same FC
     * stack, must not run FC's bone-meal code at all.
     */
    public static boolean isFcBoneMeal(btw.modern.Item fcItem, btw.modern.ItemStack fcStack) {
        return fcItem instanceof btw.modern.ItemDye && fcStack != null
                && fcStack.getItemDamage() == BONE_MEAL_DAMAGE;
    }

    /**
     * False when FC item-use code must not run for the FC stack {@code fcStack} built from {@code mcStack}: 1.20.1
     * white dye reads as FC dye 15 (LegacyVariants.java:159), and only {@code minecraft:bone_meal} is FC fertilizer.
     * For the Stage-2 item use of block activation (ProxyBlock.tryPlaceHeldItem), which runs FCItemDye on the held
     * FC stack.
     */
    public static boolean mayUseAsFcItem(ItemStack mcStack, btw.modern.ItemStack fcStack) {
        return fcStack == null || !(fcStack.getItem() instanceof btw.modern.ItemDye)
                || fcStack.getItemDamage() != BONE_MEAL_DAMAGE || isFertilizer(mcStack);
    }

    // ================================================================
    // FC fertilizer
    // ================================================================

    /**
     * FCItemDye.ApplyBoneMeal for {@code route} on {@code world}: true when FC took the fertilizer. {@code world} is
     * the server's WorldBridge, which writes the soil, or the client's read-only ClientFcWorld, whose writes are
     * no-ops, for the prediction. {@code level} is the live world {@code world} reads.
     */
    public static boolean applyFertilizer(btw.modern.World world, BlockGetter level, BlockPos pos, BlockState state,
                                          Route route) {
        return switch (route) {
            case FC_TARGET -> attemptFertilizer(world, pos.getX(), pos.getY(), pos.getZ());
            case SOIL_RELAY -> relayToSoil(world, level, pos, state);
            case VANILLA -> false;
        };
    }

    /**
     * FCBlockPlants.AttemptToApplyFertilizerTo for a plant without an FC block (Client FCBlockPlants.java:129-143):
     * the fertilizer goes to the block below. A plant several cells tall (pitcher crop, bamboo, tall flowers) relays
     * down through its own cells first, as 4.B hemp relays through its lower cell. True when that soil took it: FC
     * farmland and planters; any other block declines (Block.AttemptToApplyFertilizerTo, vanilla/server
     * Block.java:3441-3444).
     */
    public static boolean relayToSoil(btw.modern.World world, BlockGetter level, BlockPos pos, BlockState state) {
        Block plant = state.getBlock();
        BlockPos base = pos;
        while (base.getY() > level.getMinBuildHeight() && level.getBlockState(base.below()).is(plant)) {
            base = base.below();
        }
        return attemptFertilizer(world, base.getX(), base.getY() - 1, base.getZ());
    }

    /** FCItemDye.ApplyBoneMeal (Client FCItemDye.java:88-99): the target block's AttemptToApplyFertilizerTo. */
    private static boolean attemptFertilizer(btw.modern.World world, int i, int j, int k) {
        int id = world.getBlockId(i, j, k);
        btw.modern.Block targetBlock = id > 0 && id < btw.modern.Block.blocksList.length
                ? btw.modern.Block.blocksList[id] : null;
        return targetBlock != null && targetBlock.AttemptToApplyFertilizerTo(world, i, j, k);
    }

    /** {@link #applyFertilizer} on the server world. An FC failure is logged once and counts as declined. */
    public static boolean fertilize(ServerLevel level, BlockPos pos, BlockState state, Route route) {
        try {
            return applyFertilizer(WorldBridge.getOrCreate(level), level, pos, state, route);
        } catch (Throwable t) {
            // Runs inside the use-item packet handler, where a throw takes down the integrated server.
            ProxyItem.logFcFailureOnce("AttemptToApplyFertilizerTo", DYE_ITEM_ID, t);
            return false;
        }
    }

    // ================================================================
    // BoneMealItem.useOn (BoneMealItemMixin)
    // ================================================================

    /**
     * {@code BoneMealItem.useOn} for an owned route: the result to return, or null to leave the block to 1.20.1.
     * <ul>
     *   <li>Server: FC fertilizer; one item used up when FC took it (FCItemDye.onItemUse's {@code stackSize--}).
     *       CONSUME, or PASS with nothing changed. Creative keeps its count: ServerPlayerGameMode.useItemOn restores
     *       it around {@code useOn} (ServerPlayerGameMode.java:353-356), as 4.B did (vanilla/server
     *       ItemInWorldManager.java:394-402). No levelEvent 1505: 4.B bone meal has no particles or sound.</li>
     *   <li>Client: the same FC decision on the read-only client FC world, for the swing only (SUCCESS or PASS). Like
     *       1.20.1 (BoneMealItem.java:71-77), the client never shrinks the stack: the server's slot update does.</li>
     * </ul>
     * A stack that is not {@code minecraft:bone_meal} (a modded BoneMealItem) is not FC fertilizer and does nothing
     * on an owned route.
     */
    public static InteractionResult onBoneMealUseOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState state = level.getBlockState(pos);
        Route route = route(level, pos, state);
        if (route == Route.VANILLA) return null;
        if (!isFertilizer(ctx.getItemInHand())) return InteractionResult.PASS;

        if (level.isClientSide) {
            Boolean predicted;
            try {
                predicted = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () ->
                        btw.forge.client.BoneMealPrediction.predict(level, pos, state, route));
            } catch (Throwable t) {
                ProxyItem.logFcFailureOnce("AttemptToApplyFertilizerTo (client prediction)", DYE_ITEM_ID, t);
                predicted = Boolean.FALSE;
            }
            return Boolean.TRUE.equals(predicted) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        if (!(level instanceof ServerLevel serverLevel) || !fertilize(serverLevel, pos, state, route)) {
            return InteractionResult.PASS;
        }
        ctx.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }

    // ================================================================
    // Other bone-meal callers (BoneMealItem.applyBonemeal -> ForgeEventFactory.onApplyBonemeal)
    // ================================================================

    /**
     * {@code BoneMealItem.applyBonemeal} callers other than the player's {@code useOn} (mod machines and fake players,
     * {@code growCrop}). On an owned route the server applies FC fertilizer: ALLOW when FC took it, which makes
     * ForgeEventFactory.onApplyBonemeal use up one item (ForgeEventFactory.java:432-443), else the event is cancelled
     * and nothing happens or is used. The client always cancels. LOW priority, uncancelled DEFAULT events only, so
     * another mod's veto or own handling comes first.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBonemeal(BonemealEvent event) {
        if (event.getResult() != Event.Result.DEFAULT) return;
        BlockState state = event.getBlock();
        Route route = route(event.getLevel(), event.getPos(), state);
        if (route == Route.VANILLA) return;
        if (event.getLevel() instanceof ServerLevel serverLevel && isFertilizer(event.getStack())
                && fertilize(serverLevel, event.getPos(), state, route)) {
            event.setResult(Event.Result.ALLOW);
        } else {
            event.setCanceled(true);
        }
    }

    // ================================================================
    // Vanilla dispenser
    // ================================================================

    /**
     * 4.B's vanilla dispenser ejects bone meal: FC removed DispenserBehaviorDye's body (vanilla/server
     * DispenserBehaviorDye.java:23-55, 60-74), so BehaviorDefaultDispenseItem ran (BehaviorDefaultDispenseItem.java:
     * 21-71: one item, spread 6, aux 1000 click and 2000 smoke). {@link DefaultDispenseItemBehavior} is the same
     * behaviour (DefaultDispenseItemBehavior.java:11-49). It replaces Bootstrap's BONE_MEAL entry
     * (DispenseItemBehavior.java:433-446, instant growth); registerBehavior is a put into DISPENSER_REGISTRY.
     */
    @Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void onCommonSetup(FMLCommonSetupEvent event) {
            event.enqueueWork(() ->
                    DispenserBlock.registerBehavior(Items.BONE_MEAL, new DefaultDispenseItemBehavior()));
        }
    }
}
