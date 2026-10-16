package btw.forge.fcext;

import btw.forge.ProxyBlock;
import btw.forge.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge's crop events at the growth rolls of FC's own crops that are ProxyBlocks (INTEGRATIONS #022, "FC actions
 * visible to other mods"): BTW wheat (block_1089 and its top block_1090, FCBlockCropsDailyGrowth) and hemp
 * (block_241, FCBlockHempCrop). Called by the load-time mixins
 * {@code btw.forge.mixin.fcext.FCBlockCropsDailyGrowthMixin} and {@code FCBlockHempCropMixin}.
 *
 * <p><b>Why at the roll.</b> TickPolicy.runRandom already fires {@code CropGrowEvent.Pre} / {@code Post} around the
 * whole FC tick of FC-owned vanilla-id crops and twin crops (#016), but TickPolicy never sees a ProxyBlock
 * (TickPolicy.decide returns null for it), so these crops fired nothing and a season or crop-gating mod could not
 * stop them. Their FC tick is more than growth: FCBlockCropsDailyGrowth resets its grown-today flag at night (Common
 * FCBlockCropsDailyGrowth.java:41-49), so a whole-tick DENY would break the one-growth-per-day rule. The event is
 * asked exactly where FC rolls ({@code Random.nextFloat() <= chance}), after FC's own light, weeds and hydration
 * conditions passed.</p>
 *
 * <p><b>Contract.</b> {@link #growthRoll} always draws FC's {@code nextFloat} first, so the random stream is unchanged;
 * with no listener result the roll is FC's own. DENY answers {@code +Infinity} (the {@code fcmpg / ifgt} fails), ALLOW
 * answers {@code -Infinity} (the growth happens). A failed roll jumps straight to the method's return in both FC
 * classes (javap: daily {@code ifgt 193}; hemp {@code ifgt 180 -> goto 273} and {@code ifgt 273}), so a DENY listener
 * may destroy the block. Only a live ProxyBlock state posts, so a growth attempt never gets two Pre events (TickPolicy
 * covers every other FC-owned state). A listener that throws is logged once per exception class and the roll stays
 * FC's: BTW farming keeps running.</p>
 */
// FCMOD-INTEGRATION(1.20.1): fc_crop_events: FC's ProxyBlock crops ask Forge's CropGrowEvent at their growth roll, so crop-gating and season mods see BTW wheat and hemp
public final class FcCropGrowthHooks {

    private static final Logger LOGGER = LogManager.getLogger("BTW-CropEvents");

    private FcCropGrowthHooks() {}

    /** The state a Pre event was posted for, per thread: Post's original state (one roll at a time per thread). */
    private record Pending(int i, int j, int k, BlockState original) {
        boolean at(int x, int y, int z) {
            return i == x && j == y && k == z;
        }
    }

    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    /** FC classes whose roll hook has been logged active (by the class declaring AttemptToGrow). */
    private static final Map<Class<?>, Boolean> ACTIVE_LOGGED = new ConcurrentHashMap<>();
    private static final Set<String> FAILURES_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * The growth roll of FCBlockCropsDailyGrowth / FCBlockHempCrop.AttemptToGrow at (i, j, k): FC's
     * {@code rand.nextFloat()}, or +/-Infinity when a {@code CropGrowEvent.Pre} listener decided.
     */
    public static float growthRoll(Random rand, btw.modern.World w, int i, int j, int k) {
        float r = rand.nextFloat();   // always first: the FC random stream is unchanged
        if (!(w instanceof WorldBridge wb)) return r;
        ServerLevel level = wb.getServerLevel();
        if (level == null) return r;
        BlockPos pos = new BlockPos(i, j, k);
        BlockState state;
        BlockEvent.CropGrowEvent.Pre event;
        try {
            state = level.getBlockState(pos);
            // TickPolicy fires the events for every other FC-owned state (#016): only ProxyBlock crops post here.
            if (!(state.getBlock() instanceof ProxyBlock proxy)) return r;
            logActive(proxy);
            event = new BlockEvent.CropGrowEvent.Pre(level, pos, state);
            MinecraftForge.EVENT_BUS.post(event);
        } catch (RuntimeException | LinkageError e) {
            PENDING.remove();
            failure("Pre", e);
            return r;
        }
        switch (event.getResult()) {
            case DENY:
                PENDING.remove();
                return Float.POSITIVE_INFINITY;
            case ALLOW:
                PENDING.set(new Pending(i, j, k, state));
                return Float.NEGATIVE_INFINITY;
            default:
                PENDING.set(new Pending(i, j, k, state));
                return r;
        }
    }

    /** The crop at (i, j, k) grew a level (after FC's IncrementGrowthLevel / UpdateFlagForGrownToday): Post. */
    public static void grown(btw.modern.World w, int i, int j, int k) {
        Pending pending = PENDING.get();
        if (pending == null) return;
        PENDING.remove();
        if (!pending.at(i, j, k) || !(w instanceof WorldBridge wb)) return;
        ServerLevel level = wb.getServerLevel();
        if (level == null) return;
        try {
            ForgeHooks.onCropsGrowPost(level, new BlockPos(i, j, k), pending.original());
        } catch (RuntimeException | LinkageError e) {
            failure("Post", e);
        }
    }

    /**
     * Hemp at (i, j, k) grew its top block (FC's setBlockAndMetadataWithNotify at j + 1): Post at the new top, whose
     * original state is air.
     */
    public static void grownAbove(btw.modern.World w, int i, int j, int k) {
        Pending pending = PENDING.get();
        if (pending == null) return;
        PENDING.remove();
        if (!pending.at(i, j, k) || !(w instanceof WorldBridge wb)) return;
        ServerLevel level = wb.getServerLevel();
        if (level == null) return;
        try {
            ForgeHooks.onCropsGrowPost(level, new BlockPos(i, j + 1, k), Blocks.AIR.defaultBlockState());
        } catch (RuntimeException | LinkageError e) {
            failure("Post", e);
        }
    }

    /** One INFO per FC class: the roll hook runs (the @Pseudo mixins apply silently or not at all). */
    private static void logActive(ProxyBlock proxy) {
        btw.modern.Block fc = proxy.getFcBlock();
        if (fc == null) return;
        Class<?> runtime = fc.getClass();
        if (ACTIVE_LOGGED.putIfAbsent(runtime, Boolean.TRUE) != null) return;
        String declaring;
        try {
            declaring = runtime.getMethod("AttemptToGrow", btw.modern.World.class, int.class, int.class, int.class,
                    Random.class).getDeclaringClass().getSimpleName();
        } catch (NoSuchMethodException | SecurityException e) {
            declaring = runtime.getSimpleName();
        }
        LOGGER.info("[FC-CROP-EVENTS] growth roll hook active in {} ({}, legacy id {})", declaring,
                runtime.getSimpleName(), proxy.getLegacyId());
    }

    /** A crop-event listener threw: WARN once per stage and exception class; FC's own roll decides. */
    private static void failure(String stage, Throwable e) {
        if (FAILURES_LOGGED.add(stage + '|' + e.getClass().getName())) {
            LOGGER.warn("[FC-CROP-EVENTS] a CropGrowEvent.{} listener threw {}; FC's own roll decides (logged once)",
                    stage, e.toString(), e);
        }
    }
}
