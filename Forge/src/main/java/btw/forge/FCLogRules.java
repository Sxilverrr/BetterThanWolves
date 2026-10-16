package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolActions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FC's log rules (1.5.2 FCBlockLog, id 17) for 1.20.1 blocks that look like logs but are not FC's log: Dynamic Trees
 * branches and stumps (INTEGRATIONS #027), and later the post-1.5.2 vanilla logs. FC's own log keeps FC's code
 * (ServerPlayerGameModeMixin, FCMiningSpeed); these helpers answer the same questions for a block FC never knew,
 * from FC's own data, so a caller reproduces FC's harvest without copying FC logic:
 * <ul>
 *   <li>{@link #canHarvestLog}: does the held item harvest a log whole (FC's log material needs a tool,
 *       FCBetterThanWolves.java:50)? Otherwise FC chews it (ConvertBlock: bark, then the chewed stages).</li>
 *   <li>{@link #isWorkStumpTool}: does the held item turn a stump into a work stump (an FC chisel of harvest level
 *       2 or more, Client FCBlockLog.java:219-229)?</li>
 *   <li>{@link #fcWoodMeta}, {@link #bark}, {@link #sawdust}, {@link #stick}, {@link #workStumpState}: FC's species
 *       and the items and block FC's log produces.</li>
 *   <li>{@link #chargeLogHarvest}: FC's harvest exhaustion for each log taken.</li>
 * </ul>
 * Stumps are the caller's decision: FC's GetIsStump reads world id 17 (Client FCBlockLog.java:187-202), so it never
 * sees a foreign stump. Server side unless stated. Nothing here throws: every failure logs once and degrades to
 * {@link ItemStack#EMPTY}, null, -1 or false. FC's statics are read by reflection (the Forge source set cannot name
 * FC classes), only once BTWLifecycle.isFcReady(), and cached.
 */
// FCMOD-INTEGRATION(1.20.1): fc_log_rules: trunks FC never knew (Dynamic Trees branches, modern logs) follow FC's log rules through FC's own data, not a copy of FCBlockLog
public final class FCLogRules {
    private static final Logger LOGGER = LogManager.getLogger("BTW-LogRules");

    private static final String FC_CORE = "net.minecraft.src.btw.core.FCBetterThanWolves";
    private static final String FC_LOG = "net.minecraft.src.btw.block.FCBlockLog";
    /** 1.5.2 Block.wood, the log (FCBlockLog replaces it in blocksList). */
    private static final int LEGACY_LOG_ID = 17;
    /** 1.5.2 Item.stick (256 + 24). */
    private static final int LEGACY_STICK_ID = 280;

    /** FCBetterThanWolves statics by field name; empty when missing (logged once). Filled only after FC init. */
    private static final Map<String, Optional<Object>> FC_STATICS = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    /** FCBlockLog.IsWorkStumpItemConversionTool (Lbtw/modern/ItemStack;Lbtw/modern/World;III)Z; null until resolved. */
    private static volatile Optional<Method> isWorkStumpTool;

    private FCLogRules() {}

    /**
     * True if {@code p}'s held item harvests an FC log whole at {@code pos}: FC's canHarvestBlock against
     * blocksList[17] (Modern-Common EntityPlayer.canHarvestBlock; FCItemAxe.canHarvestBlock, Common
     * FCItemAxe.java:50-68). A held item with no FC item at all (another mod's axe) counts when it declares AXE_DIG.
     * A stump is the caller's decision: FC's axe refuses a stump only through its world-read GetIsProblemToRemove.
     */
    public static boolean canHarvestLog(ServerPlayer p, BlockPos pos) {
        if (p == null || pos == null) return false;
        try {
            btw.modern.Block log = fcLog();
            if (log != null) {
                PlayerBridge pb = PlayerBridge.getOrCreate(p); // syncs from the ServerPlayer
                if (pb.canHarvestBlock(log, pos.getX(), pos.getY(), pos.getZ())) return true;
            }
            ItemStack held = p.getMainHandItem();
            return !held.isEmpty() && ProxyItem.fcItemFor(held.getItem()) == null
                    && held.canPerformAction(ToolActions.AXE_DIG);
        } catch (Throwable t) {
            failOnce("canHarvestLog", t);
            return false;
        }
    }

    /**
     * True if {@code held} turns an FC stump into a work stump: FC's FCBlockLog.IsWorkStumpItemConversionTool on
     * blocksList[17] (an FC chisel of harvest level 2 or more, Client FCBlockLog.java:219-229), given FC's view of the
     * stack and {@code p}'s world.
     */
    public static boolean isWorkStumpTool(ServerPlayer p, ItemStack held, BlockPos pos) {
        if (p == null || held == null || held.isEmpty() || pos == null) return false;
        try {
            btw.modern.Block log = fcLog();
            Method m = workStumpToolMethod();
            if (log == null || m == null || !m.getDeclaringClass().isInstance(log)) return false;
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(held);
            if (fcStack == null) return false;
            Object r = m.invoke(log, fcStack, WorldBridge.getOrCreate(p.serverLevel()),
                    pos.getX(), pos.getY(), pos.getZ());
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            failOnce("isWorkStumpTool", t);
            return false;
        }
    }

    /**
     * FC's wood species of {@code log}: 0-3 (oak, spruce, birch, jungle) for the four FC-mapped logs and their bark
     * and stripped forms (ProxyRegistry.getTreeLogSpecies), -1 for every other block, post-1.5.2 species included.
     * Callers pass the unstripped log when they mean its bark. Both sides.
     */
    public static int fcWoodMeta(Block log) {
        if (log == null) return -1;
        try {
            return ProxyRegistry.getTreeLogSpecies(log);
        } catch (Throwable t) {
            failOnce("fcWoodMeta", t);
            return -1;
        }
    }

    /**
     * One FC bark of species {@code woodMeta} (FCBlockLog.ConvertBlock ejects bark with damage {@code meta & 3},
     * Client FCBlockLog.java:112); EMPTY outside 0-3, so a species FC never had gives no bark.
     */
    public static ItemStack bark(int woodMeta) {
        if (woodMeta < 0 || woodMeta > 3) return ItemStack.EMPTY;
        btw.modern.Item bark = fcItem("fcItemBark");
        return bark != null ? stack(bark.itemID, 1, woodMeta) : ItemStack.EMPTY;
    }

    /** {@code n} FC sawdust (FCBlockLog's improper-tool drop, Client FCBlockLog.java:125-131); EMPTY for n <= 0. */
    public static ItemStack sawdust(int n) {
        if (n <= 0) return ItemStack.EMPTY;
        btw.modern.Item sawDust = fcItem("fcItemSawDust");
        return sawDust != null ? stack(sawDust.itemID, n, 0) : ItemStack.EMPTY;
    }

    /** {@code n} sticks (1.5.2 Item.stick, 280); EMPTY for n <= 0. */
    public static ItemStack stick(int n) {
        if (n <= 0) return ItemStack.EMPTY;
        return stack(LEGACY_STICK_ID, n, 0);
    }

    /**
     * The FC work stump of species {@code woodMeta} as a 1.20.1 state: FCBetterThanWolves.fcBlockWorkStump's block
     * with META {@code woodMeta & 3}, as FCBlockLog.ConvertBlock places it (Client FCBlockLog.java:92). Null for a
     * negative species (none FC knows) or when the block cannot be resolved.
     */
    @Nullable
    public static BlockState workStumpState(int woodMeta) {
        if (woodMeta < 0) return null;
        try {
            btw.modern.Block stump = fcBlock("fcBlockWorkStump");
            if (stump == null) return null;
            Block modern = ProxyRegistry.getModernBlock(stump.blockID);
            if (modern == null) return null;
            BlockState state = modern.defaultBlockState();
            return state.hasProperty(ProxyBlock.META) ? state.setValue(ProxyBlock.META, woodMeta & 3) : null;
        } catch (Throwable t) {
            failOnce("workStumpState", t);
            return null;
        }
    }

    /**
     * FC's harvest exhaustion for {@code logs} logs of species {@code woodMeta} taken at {@code pos}: Modern-Common
     * EntityPlayer.AddHarvestBlockExhaustion for FC's log once per log (the held item's
     * GetExhaustionOnUsedToHarvestBlock, 0.025 by default), as FC charges each block it harvests. A negative
     * species (none FC knows) is charged as species 0; FC's exhaustion does not depend on it.
     */
    public static void chargeLogHarvest(ServerPlayer p, int logs, int woodMeta, BlockPos pos) {
        if (p == null || pos == null || logs <= 0) return;
        try {
            if (fcLog() == null) return;
            PlayerBridge pb = PlayerBridge.getOrCreate(p); // syncs from the ServerPlayer
            for (int n = 0; n < logs; n++) {
                pb.AddHarvestBlockExhaustion(LEGACY_LOG_ID, pos.getX(), pos.getY(), pos.getZ(), Math.max(woodMeta, 0));
            }
        } catch (Throwable t) {
            failOnce("chargeLogHarvest", t);
        }
    }

    // ------------------------------------------------------------------ FC lookups

    @Nullable
    private static btw.modern.Block fcLog() {
        return LEGACY_LOG_ID < btw.modern.Block.blocksList.length ? btw.modern.Block.blocksList[LEGACY_LOG_ID] : null;
    }

    private static ItemStack stack(int fcItemId, int count, int damage) {
        try {
            return ItemStackHelper.fcIdToMcStack(fcItemId, count, damage);
        } catch (Throwable t) {
            failOnce("item " + fcItemId, t);
            return ItemStack.EMPTY;
        }
    }

    @Nullable
    private static btw.modern.Item fcItem(String field) {
        return fcStatic(field) instanceof btw.modern.Item item ? item : null;
    }

    @Nullable
    private static btw.modern.Block fcBlock(String field) {
        return fcStatic(field) instanceof btw.modern.Block block ? block : null;
    }

    /** An FCBetterThanWolves static; null before FC init (not cached then) or when missing (logged once). */
    @Nullable
    private static Object fcStatic(String field) {
        if (!BTWLifecycle.isFcReady()) return null;
        return FC_STATICS.computeIfAbsent(field, name -> {
            try {
                Object value = Class.forName(FC_CORE).getField(name).get(null);
                if (value == null) LOGGER.warn("FCBetterThanWolves.{} is null after FC init", name);
                return Optional.ofNullable(value);
            } catch (Throwable t) {
                LOGGER.warn("FCBetterThanWolves.{} could not be read: {}", name, t.toString());
                return Optional.empty();
            }
        }).orElse(null);
    }

    @Nullable
    private static Method workStumpToolMethod() {
        Optional<Method> m = isWorkStumpTool;
        if (m == null) {
            if (!BTWLifecycle.isFcReady()) return null;
            try {
                m = Optional.of(Class.forName(FC_LOG).getMethod("IsWorkStumpItemConversionTool",
                        btw.modern.ItemStack.class, btw.modern.World.class, int.class, int.class, int.class));
            } catch (Throwable t) {
                LOGGER.warn("FCBlockLog.IsWorkStumpItemConversionTool could not be resolved: {}", t.toString());
                m = Optional.empty();
            }
            isWorkStumpTool = m;
        }
        return m.orElse(null);
    }

    private static void failOnce(String what, Throwable t) {
        if (LOGGED.add(what)) {
            LOGGER.warn("FC log rule {} failed (further failures are not logged): {}", what, t.toString(), t);
        }
    }
}
