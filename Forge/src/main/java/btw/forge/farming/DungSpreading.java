package btw.forge.farming;

import btw.forge.BTWForgeMod;
import btw.forge.compat.FcRefs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BTW dung grows and spreads the plants of {@code #betterthanwolves:dung_spreads} (INTEGRATIONS #032; settled decisions
 * #13 and 12(a): dung, not bone meal, is what spreads a modded wild garden under 4.B). A general rule: any
 * {@link BonemealableBlock} a pack tags takes BTW dung as its own bone-meal growth; the tag is empty unless data names
 * a block (BTW's datagen writes the six HarvestCraft gardens, every entry {@code required:false}).
 *
 * <ul>
 *   <li><b>Use</b>: right-clicking a tagged block with {@code FCBetterThanWolves.fcItemDung} in the main hand (the FC
 *       player has only the main hand), not as a spectator, runs the block's own {@code isValidBonemealTarget},
 *       {@code isBonemealSuccess} and {@code performBonemeal}, uses up one dung (none in creative), shows the
 *       bone-meal growth particles (level event 1505) and ends the use, on both sides, so the client predicts the
 *       swing.</li>
 *   <li><b>Item-use gates</b>: the use keeps the two checks the RightClickBlock cancel would skip, as bone meal does:
 *       a {@code useItem} DENY from another handler (ServerPlayerGameMode.useItemOn) and the adventure-mode gate
 *       ({@code mayBuild} or the dung's CanPlaceOn tag, ItemStack.onItemUse; 4.B ItemDye.onItemUse's
 *       {@code canPlayerEdit}). Either one leaves the event to vanilla, where dung does nothing.</li>
 *   <li><b>Why an event</b>: FC dung has no onItemUse (Server FCItemDung.java), and ServerPlayerGameModeMixin's FC item
 *       use does not cancel, so Forge posts RightClickBlock for it. A RightClickBlock cancel skips 1.20.1's creative
 *       count restore, hence the explicit {@code instabuild} check.</li>
 *   <li><b>Bone meal</b> is not here: BoneMealPolicy routes it (4.B: an fc_soil_plants member on FC ground relays to
 *       the ground, which declines, so the bone meal is kept). BonemealableGateMixin gates only the vanilla classes
 *       BTW governs, so a tagged modded block's own performBonemeal runs unchanged.</li>
 * </ul>
 *
 * <p>SR1: no foreign type is named. The calls into the tagged block's bone-meal code are isolated: a
 * {@code RuntimeException} or {@code LinkageError} logs one WARN for that block, leaves the event untouched (the dung
 * is not used) and switches the rule off for that block only.</p>
 */
// FCMOD-INTEGRATION(1.20.1): modded_plant_rules: BTW dung spreads the modded wild plants a pack tags, as 4.B's dung is the fertilizer for wild growth
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public final class DungSpreading {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ModdedPlants");

    /** Plants BTW dung grows or spreads (their own bone-meal growth); written by BTW's datagen, inert while empty. */
    public static final TagKey<Block> DUNG_SPREADS =
            BlockTags.create(new ResourceLocation(BTWForgeMod.MOD_ID, "dung_spreads"));

    /** 1.20.1 LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH (BoneMealItem.useOn); data 0 = the default 15 particles. */
    private static final int LEVEL_EVENT_PLANT_GROWTH = 1505;

    /** Blocks whose bone-meal code threw once: the rule leaves them alone afterwards. */
    private static final Set<Block> FAILED = ConcurrentHashMap.newKeySet();

    private DungSpreading() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.getUseItem() == Event.Result.DENY) return;
        Player player = event.getEntity();
        if (player.isSpectator()) return;   // as the weed pull: Forge posts before the spectator branch
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!state.is(DUNG_SPREADS) || !(state.getBlock() instanceof BonemealableBlock plant)) return;
        if (FAILED.contains(state.getBlock())) return;
        Item dung = FcRefs.item("fcItemDung");
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (dung == null || !held.is(dung)) return;
        // 1.20.1's adventure gate for an item used on a block (ItemStack.onItemUse), 4.B's canPlayerEdit
        if (!player.getAbilities().mayBuild && !held.hasAdventureModePlaceTagForBlock(
                level.registryAccess().registryOrThrow(Registries.BLOCK), new BlockInWorld(level, pos, false))) {
            return;
        }
        try {
            if (!plant.isValidBonemealTarget(level, pos, state, level.isClientSide)) return;
            if (level instanceof ServerLevel serverLevel) {
                if (plant.isBonemealSuccess(serverLevel, serverLevel.random, pos, state)) {
                    plant.performBonemeal(serverLevel, serverLevel.random, pos, state);
                }
                if (!player.getAbilities().instabuild) held.shrink(1);
                serverLevel.levelEvent(LEVEL_EVENT_PLANT_GROWTH, pos, 0);
            }
        } catch (RuntimeException | LinkageError e) {
            if (FAILED.add(state.getBlock())) {
                LOGGER.warn("[DUNG-SPREADS] dung on {} threw {}; the rule is switched off for that block, which keeps "
                        + "its own behaviour (logged once)", ForgeRegistries.BLOCKS.getKey(state.getBlock()),
                        e.toString(), e);
            }
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }
}
