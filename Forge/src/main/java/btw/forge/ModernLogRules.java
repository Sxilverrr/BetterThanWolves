package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FCBlockLog's rule for the 1.20.1 logs FC has no identity for (the log rule; logs design
 * harvest-tools-D2-SR11-post152-logs with the 2026-10-19 rulings). FC's own logs (log 17's oak, spruce, birch and
 * jungle logs; log2 165's acacia, dark oak, mangrove and cherry logs, ModernWoods) keep FC's pipeline
 * (ServerPlayerGameModeMixin, FCMiningSpeed) and never get here. Two classes of cell
 * ({@link FCLogRules#isLogRuleTarget}):
 * <ul>
 *   <li><b>Class F</b> ({@link FCLogRules#isFcReadLog}): the bark and stripped forms FC's world reads as log 17 or 165
 *       (oak_wood, stripped_oak_log, stripped_acacia_log, ...). FC's machines already treat them as FC's log; here
 *       every chew runs FC's own {@code ConvertBlock} of that log at the cell, so FC writes the chewed log or the work
 *       stump and ejects its bark (its stripped forms keep their bark for FC's chew or saw: an axe strip pays none,
 *       decision 8 (a)). The harvest drop stays vanilla, so the block drops itself.</li>
 *   <li><b>Class S</b> ({@link FCLogRules#isForeignLog}): every other log, the crimson and warped stems (bark/none: no
 *       bark and no work stump, maintainer ruling 2) and modded logs. FCBlockLog's rule re-expressed: the axe gate,
 *       the bark of {@link FCLogRules#barkSpecies}, FC's chewed log at once (decision 3 (b)), the work stump of
 *       {@link FCLogRules#workStumpSpecies}; FC's saw and stump remover through {@link ModernLogHooks}.</li>
 * </ul>
 * Handlers, each acting only on a log-rule target:
 * <ul>
 *   <li>(a) {@code PlayerEvent.BreakSpeed}, LOWEST, both sides: FC's log dig rate
 *       ({@link FCMiningSpeed#fcLogDigProgress}, the C1 contract), a stump three times as long, a barkless class-S
 *       chew at FC's chewed-log hardness.</li>
 *   <li>(b) {@code BlockEvent.BreakEvent}, LOWEST, server: a survival hit that does not harvest (a stump, or no
 *       harvesting axe) is cancelled and chewed; ForgeHooks.onBlockBreakEvent then resends the new state
 *       (ForgeHooks.java:548-551). A chew costs no hunger (ItemInWorldManager.java:82-102); a harvest keeps 1.20.1's
 *       0.005 (logs design decision 4 (c), INTEGRATIONS.md:488).</li>
 *   <li>(c) {@code BlockEvent.BlockToolModificationEvent}, LOWEST, server: an axe strip of a barked class-S log pays
 *       its bark, settled at ServerTickEvent END once the cell holds the strip result (Forge posts the event before
 *       the block computes its strip, IForgeBlockState.java:659-663, so {@code getFinalState()} is still the
 *       original here).</li>
 * </ul>
 * Registered on the Forge bus by the BTWForgeMod constructor. Nothing here throws: a failure is logged once and the
 * event is left as 1.20.1 has it.
 */
// FCMOD-INTEGRATION(1.20.1): fc_log_rules: post-1.5.2 and modded logs follow FCBlockLog's rule: FC dig rate, the axe gate, chewing with bark, the stump, the work stump and strip bark
public final class ModernLogRules {

    private static final Logger LOGGER = LogManager.getLogger("BTW-LogRules");

    /** A pending strip older than this many server ticks is dropped (the strip never came). */
    private static final int EXPIRY_TICKS = 5;

    /** A cell in one dimension. */
    private record Key(ResourceKey<Level> dim, BlockPos pos) {}

    /** An axe strip of a barked class-S log, paid at END if the cell then holds {@code result}. */
    private record PendingStrip(BlockState result, Direction face, int barkSpecies, long tick) {}

    /** Server thread only (the event and the tick both run there). */
    private static final Map<Key, PendingStrip> STRIPS = new HashMap<>();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private ModernLogRules() {}

    // ------------------------------------------------------------------ (a) dig speed

    /**
     * Both sides. Every hit on a log-rule target, axes included, digs at FC's log rate:
     * {@code newSpeed = progress * H * divisor * newSpeed / originalSpeed} (FCMiningSpeed.java:222-226), so BTW's
     * penalty (FCGameplayBridge.onBreakSpeed, NORMAL) and other listeners' factors apply exactly once and the client
     * predicts the server. A stump takes FCBlockLog's x3 with the held tool ignored (FC's tools refuse a stump); a
     * chew of a barkless class-S log takes FCBlockLogDamaged's hardness, as FC's chewed log it becomes.
     */
    // FCMOD-INTEGRATION(1.20.1): fc_log_rules: a log FC has no identity for digs at FC's log rate, a stump three times as long
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreakSpeed(PlayerEvent.BreakSpeed e) {
        try {
            BlockState s = e.getState();
            if (s == null || !FCLogRules.isLogRuleTarget(s)) return;
            Optional<BlockPos> at = e.getPosition();
            if (at.isEmpty()) return;
            float orig = e.getOriginalSpeed();
            if (!(orig > 0F)) return;
            BlockPos pos = at.get();
            Player p = e.getEntity();
            Level level = p.level();
            boolean stump = FCLogRules.isStump(level, pos, s);
            boolean chew = stump || !p.getMainHandItem().canPerformAction(ToolActions.AXE_DIG);
            float prog = FCMiningSpeed.fcLogDigProgress(p, level, pos, stump);
            if (!(prog > 0F)) return;
            if (chew && !FCLogRules.isFcReadLog(s) && !FCLogRules.barked(level, pos, s)) {
                prog *= FCLogRules.damagedLogFactor();
            }
            float h = s.getDestroySpeed(level, pos);
            float div = ForgeHooks.isCorrectToolForDrops(s, p) ? 30F : 100F;
            e.setNewSpeed(prog * h * div * (e.getNewSpeed() / orig));
        } catch (RuntimeException | LinkageError t) {
            failOnce("the dig-speed handler", t);
        }
    }

    // ------------------------------------------------------------------ (b) breaking: harvest or chew

    /**
     * A survival player breaks a log-rule target. LOWEST and not receiving cancelled events, so protection mods and
     * adventure pre-cancels act first; creative and spectator keep 1.20.1's break, as FC's creative branch removes
     * the block (ItemInWorldManager.java:421-444).
     * <ol>
     *   <li>Not a stump and an FC-harvesting axe ({@link FCLogRules#canHarvestLog}): 1.20.1 harvests (the block drops
     *       itself, the axe takes its 1).</li>
     *   <li>Class F: FC's own {@code ConvertBlock} of the log it reads as (blocksList[17] or [165]) at the cell, with
     *       the hit face: FC writes the work stump (a stump and an FC chisel of level 2+) or the chewed log and ejects
     *       its bark (FCBlockLog.java:81-116). FC's convert wear for the cell's log id.</li>
     *   <li>Class S, barked: the work stump of {@link FCLogRules#workStumpSpecies} for a stump and a work-stump tool;
     *       else FC's chewed log at once (decision 3 (b): FCBlockLog.ConvertBlock's result, :97-112) and the bark of
     *       {@link FCLogRules#barkSpecies} toward the hit face (none for the stems).</li>
     *   <li>Class S, barkless (stripped): FC's chewed log at level 0, then FCBlockLogDamaged.ConvertBlock at the cell
     *       (level 1 and a stick, or 2 sawdust on a stump, FCBlockLogDamaged.java:163-207).</li>
     * </ol>
     */
    // FCMOD-INTEGRATION(1.20.1): fc_log_rules: a log FC has no identity for is chewed as FCBlockLog.ConvertBlock (bark, chewed log, work stump) unless an FC-harvesting axe takes it whole
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getPlayer() instanceof ServerPlayer p)) return;
        if (p.isCreative() || p.isSpectator()) return;
        try {
            BlockState state = e.getState();
            btw.modern.Block fcLog = FCLogRules.fcReadLog(state);
            if (fcLog == null && !FCLogRules.isForeignLog(state)) return;
            BlockPos pos = e.getPos();
            boolean stump = FCLogRules.isStump(level, pos, state);
            if (!stump && FCLogRules.canHarvestLog(p, pos)) return;
            ItemStack held = p.getMainHandItem();
            Direction face = FCLogRules.hitFace(p, pos);
            if (fcLog != null) {
                chewFcReadLog(e, level, p, held, fcLog, state, pos, face);
            } else if (FCLogRules.barked(level, pos, state)) {
                chewBarked(e, level, p, held, state, pos, stump, face);
            } else {
                chewBarkless(e, level, p, held, state, pos, stump, face);
            }
        } catch (RuntimeException | LinkageError t) {
            failOnce("the break handler", t);
        }
    }

    /**
     * Class F: FC's own ConvertBlock (FC reads species, axis and the stump bit at the cell, WorldBridge
     * deriveVanillaMetadata), then FC's wear for the original state, whose world-read id is the log's (17 or 165), so
     * FCItemChiselIron charges its 10 on a work stump (FCItemChiselIron.java:43-55, FCItemChiselIronWoodMixin).
     */
    private static void chewFcReadLog(BlockEvent.BreakEvent e, ServerLevel level, ServerPlayer p, ItemStack held,
                                      btw.modern.Block fcLog, BlockState state, BlockPos pos, Direction face) {
        WorldBridge world = WorldBridge.getOrCreate(level);
        btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(held);
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (!fcLog.CanConvertBlock(fcStack, world, x, y, z)) return;
        e.setCanceled(true);
        breakEffect(level, p, pos, state);
        fcLog.ConvertBlock(fcStack, world, x, y, z, face.get3DDataValue());
        FCLogRules.wearConvert(level, p, held, state, pos);
    }

    /**
     * Class S with its bark: the work stump, or FC's chewed log plus the bark. The wear hands FC log 17's id (the oak
     * log's world read), as FC's own chew of a log does, so FCItemChiselIron charges 10 on the work stump.
     */
    private static void chewBarked(BlockEvent.BreakEvent e, ServerLevel level, ServerPlayer p, ItemStack held,
                                   BlockState state, BlockPos pos, boolean stump, Direction face) {
        BlockState oakLog = Blocks.OAK_LOG.defaultBlockState();
        int stumpWood = FCLogRules.workStumpSpecies(state);
        if (stump && stumpWood >= 0 && FCLogRules.isWorkStumpTool(p, held, pos)) {
            BlockState workStump = FCLogRules.workStumpState(stumpWood);
            if (workStump != null) {
                e.setCanceled(true);
                breakEffect(level, p, pos, state);
                level.setBlock(pos, workStump, Block.UPDATE_ALL);
                FCLogRules.wearConvert(level, p, held, oakLog, pos);
                return;
            }
        }
        btw.modern.Block chewed = FCLogRules.chewedLog();
        if (chewed == null) return;
        e.setCanceled(true);
        breakEffect(level, p, pos, state);
        WorldBridge.getOrCreate(level).setBlockAndMetadataWithNotify(pos.getX(), pos.getY(), pos.getZ(),
                chewed.blockID, FCLogRules.chewedLogMeta(state, stump));
        FCLogRules.popToward(level, pos, face, FCLogRules.bark(FCLogRules.barkSpecies(state)));
        FCLogRules.wearConvert(level, p, held, oakLog, pos);
    }

    /**
     * Class S without its bark: FC's chewed log at level 0 (orientation or stump), then FC's own chew of it, which
     * pops the stage item and may make a spike; the wear is FC's for the chewed log.
     */
    private static void chewBarkless(BlockEvent.BreakEvent e, ServerLevel level, ServerPlayer p, ItemStack held,
                                     BlockState state, BlockPos pos, boolean stump, Direction face) {
        btw.modern.Block chewed = FCLogRules.chewedLog();
        if (chewed == null) return;
        e.setCanceled(true);
        breakEffect(level, p, pos, state);
        WorldBridge world = WorldBridge.getOrCreate(level);
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        world.setBlockAndMetadataWithNotify(x, y, z, chewed.blockID, FCLogRules.chewedLogMeta(state, stump));
        BlockState chewedState = level.getBlockState(pos);
        chewed.ConvertBlock(ItemStackHelper.toFcStack(held), world, x, y, z, face.get3DDataValue());
        FCLogRules.wearConvert(level, p, held, chewedState, pos);
    }

    /**
     * FC's break event for everyone near a chew but the breaker (Modern-Common ItemInWorldManager.java:68-75, from
     * vanilla/server ItemInWorldManager.java:458: the block's break sound and particles; the breaker's client already
     * played its own). The cancelled BreakEvent skipped 1.20.1's (Block.playerWillDestroy), as for FC's own logs.
     */
    private static void breakEffect(ServerLevel level, ServerPlayer p, BlockPos pos, BlockState state) {
        level.levelEvent(p, 2001, pos, Block.getId(state));
    }

    // ------------------------------------------------------------------ (c) axe stripping

    /**
     * An axe strip (not simulated) of a log-rule target pays bark only when it takes the log's bark: the original is
     * {@link FCLogRules#barked} and the result is not. Never for class F, whose stripped forms keep their bark for FC's
     * chew or saw (decision 8 (a)); so in a vanilla world only a barked modded log pays here. The result is the event's
     * final state when another listener changed it, else the block's own strip ({@code simulate = true}); null (a
     * cancelled strip) pays nothing. Recorded here, paid at END ({@link #onServerTick}); the axe's wear stays
     * AxeItem's 1 (AxeItem.java:58-63).
     */
    // FCMOD-INTEGRATION(1.20.1): fc_log_rules: stripping a barked log FC has no identity for pays its FC bark, once
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onToolModification(BlockEvent.BlockToolModificationEvent e) {
        if (e.isSimulated() || e.getToolAction() != ToolActions.AXE_STRIP) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        try {
            BlockState original = e.getState();
            if (!FCLogRules.isLogRuleTarget(original)) return;
            BlockPos pos = e.getPos();
            BlockState result = e.getFinalState() != original ? e.getFinalState() : stripResult(e, level, original);
            if (result == null || !FCLogRules.barked(level, pos, original) || FCLogRules.barked(level, pos, result)) {
                return;
            }
            int species = FCLogRules.barkSpecies(original);
            if (FCLogRules.bark(species).isEmpty()) return;
            STRIPS.put(new Key(level.dimension(), pos.immutable()), new PendingStrip(result,
                    e.getContext().getClickedFace(), species, level.getServer().getTickCount()));
        } catch (RuntimeException | LinkageError t) {
            failOnce("the axe strip handler", t);
        }
    }

    /** The block's own strip result for the event's context, simulated; the cached synthetic one if it throws. */
    private static BlockState stripResult(BlockEvent.BlockToolModificationEvent e, ServerLevel level,
                                          BlockState original) {
        try {
            return original.getBlock().getToolModifiedState(original, e.getContext(), ToolActions.AXE_STRIP, true);
        } catch (RuntimeException | LinkageError t) {
            failOnce("the strip result of " + original.getBlock(), t);
            return FCLogRules.strippedState(level, e.getPos(), original);
        }
    }

    /** Pays each pending strip whose cell now holds its result; drops the stale ones. */
    // FCMOD-INTEGRATION(1.20.1): fc_log_rules: strip bark is paid after the strip, at the end of the server tick
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || STRIPS.isEmpty()) return;
        try {
            MinecraftServer server = e.getServer();
            long now = server.getTickCount();
            Iterator<Map.Entry<Key, PendingStrip>> it = STRIPS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Key, PendingStrip> entry = it.next();
                Key k = entry.getKey();
                PendingStrip ps = entry.getValue();
                ServerLevel level = server.getLevel(k.dim());
                if (level != null && level.isLoaded(k.pos()) && level.getBlockState(k.pos()) == ps.result()) {
                    FCLogRules.popToward(level, k.pos(), ps.face(), FCLogRules.bark(ps.barkSpecies()));
                    it.remove();
                } else if (now - ps.tick() > EXPIRY_TICKS) {
                    it.remove();
                }
            }
        } catch (RuntimeException | LinkageError t) {
            STRIPS.clear();
            failOnce("the strip settle handler", t);
        }
    }

    // ------------------------------------------------------------------ caches

    /** Tags changed (a datapack reload, or the client received them): bark tags and recipes may differ. */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent e) {
        FCLogRules.clearCaches();
    }

    /** A datapack reload or a login sync: recipes may differ. */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent e) {
        FCLogRules.clearCaches();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        STRIPS.clear();
        FCLogRules.clearCaches();
    }

    private static void failOnce(String what, Throwable t) {
        if (LOGGED.add(what)) {
            LOGGER.warn("Log rule: {} failed; the event keeps 1.20.1's outcome (further failures are not logged): {}",
                    what, t.toString(), t);
        }
    }
}
