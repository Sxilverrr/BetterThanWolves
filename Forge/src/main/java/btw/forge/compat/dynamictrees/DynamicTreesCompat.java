package btw.forge.compat.dynamictrees;

import btw.forge.ModernBlockHooks;
import btw.forge.TwinRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dynamic Trees x BTW (INTEGRATIONS #027): living Dynamic Trees follow BTW 4.B's tree rules through DT's own API, and
 * no DT feature is switched off.
 *
 * <ul>
 *   <li><b>Modern feature:</b> Dynamic Trees (1.20.1-1.4.10 and later): trees grow branch by branch on rooty soil, a
 *       cut branch fells everything above it as a falling-tree entity with a wood payload (one level tick later,
 *       through DT's FutureBreak), axes strip branches, bone meal on a tree runs DT's growth pulse, and seeds plant
 *       {@code DynamicSaplingBlock}s, which are not 1.20.1 SaplingBlocks.</li>
 *   <li><b>Conflict with FC:</b> a DT branch is not FC's log (legacy id 17), so none of FCBlockLog's rules reach it:
 *       any hit fells the whole tree with its logs, a bare hand included; there is no stump, no bark, sawdust or
 *       chewed stages, no saw, stump remover or work stump; bone meal grows the tree even at fertility 15 and from
 *       either hand; a DT sapling grows on its first random tick with no light, End or pace gate.</li>
 *   <li><b>Pattern:</b> D+B. D (FC-extend): FC's own rules, read from FC's data through the bridge seams
 *       ({@code FCLogRules}, {@code FCMiningSpeed}, {@link ModernBlockHooks}, {@link TwinRegistry},
 *       {@code SeasonGate}, {@code BoneMealPolicy}), act on DT's blocks through DT's API and events. B (Gate): DT's
 *       whole-tree harvest needs an FC-harvesting axe; every other hit chews the branch as FC chews a log.</li>
 *   <li><b>What BTW adds</b> ({@link DTHarvestHandler}, {@link DTGrowthHandler}, {@link DTBlockHooks},
 *       {@link DTSaplingTwin}):
 *       <ul>
 *         <li>Chewing: hit 1 strips the branch and gives 1 bark, then a stick, sawdust and a stick, and the last hit
 *             gives sawdust and cuts through; a chewed-through tree drops no logs. A chewed branch digs at
 *             FCBlockLogDamaged's hardness.</li>
 *         <li>An axe fell charges FC's harvest hunger and exactly 1 durability per real log, to the stack that made
 *             the break; DT's own deferred axe wear is overwritten on that stack or compensated on a swapped-in
 *             one.</li>
 *         <li>The trunk base on rooty soil is a BTW stump: an axe only chews it (hand speed, 3x hardness, 2 sawdust
 *             per stage), its rooty soil cannot be dug while a branch stands on it, a dead stump takes the stump
 *             remover (from its core or any trunk shell), and an iron or better chisel turns an unchewed one into
 *             FC's work stump, at FC's convert wear.</li>
 *         <li>A powered FC saw fells trunks of radius 8 or more (trunk shells included); the sawed block gives FC's
 *             log saw output and the rest of the tree falls as a harvest.</li>
 *         <li>Bone meal is 4.B fertilizer: +2 rooty fertility through FC's fertilizer path, nothing at 15, the same
 *             from either hand and from machines; pods and fruit take none (as FC cocoa).</li>
 *         <li>DT saplings are FC sapling twins (FC survival, never in the End, light 9 above, four persisted 1/64
 *             stages, then DT's own tree, with SeasonGate asked at the grow step); DT Nether fungi are FC fungus
 *             twins (1 in 50, never in the End), which DT itself never grows from random ticks.</li>
 *         <li>Axe stripping (right click) pays FC bark and 1 durability.</li>
 *       </ul></li>
 *   <li><b>Alt rejected:</b> mapping DT branches onto legacy id 17 (FC's world code would rewrite DT states and never
 *       see radius, rooty soil or shells); loot-only rules (no chew stages, no stump, no wear); shipping a DT tree
 *       pack (changes DT's own species data for every pack); a mixin into {@code BranchBlock.futureBreak} or
 *       {@code ItemUtils.damageAxe} to cancel DT's axe wear (a third foreign mixin that switches DT behaviour off;
 *       the absolute-target wear correction gives the same result with events only).</li>
 *   <li><b>Declared deviations:</b> the stump remover and the work-stump chisel act only on dead DT stumps (FC's
 *       remover writes air, which would collapse a living DT tree with no drops; a chisel on a living stump chews
 *       it); a species FC never had gives no bark and takes the oak work stump; thick trunks (radius 9-24) chew one
 *       radius per hit; DT growth may thicken a chewed trunk again; Dendro potions, enhanced fertilizer and any
 *       non-bone-meal item a pack adds to {@code #dynamictrees:fertilizer} keep DT's substance effects; direct
 *       {@code performBonemeal} callers on branches and rooty soil keep DT's effect; with
 *       {@code enableStripRadiusReduction=false} a full trunk takes one extra chew hit. DT's leaf loot and apples
 *       are unchanged (maintainer decisions D2 and #7); DT's Fortune bonus logs stay (D1 (b)).</li>
 * </ul>
 *
 * <p><b>Soft dependency</b> (SR1): every Dynamic Trees reference lives in this package; no class here carries a
 * Forge mod or automatic bus-subscriber annotation (Forge would load it without DT); the shared seams
 * ({@link ModernBlockHooks.Hook}, {@link TwinRegistry.ModdedSapling}) see only Minecraft types. {@link #init} is the
 * only entry, reached by BTWCompat's direct invokestatic. It probes DT's API by reflection before it registers
 * anything and sets {@link #active} last. Every handler, hook and twin method catches RuntimeException and
 * LinkageError, calls {@link #fail} and answers inertly; after a switch-off Dynamic Trees keeps its own rules (DT
 * saplings and fungi still grow at FC's pace through DT's own body) and BTW and every other integration keep
 * running.</p>
 *
 * <p>Trace: {@code -Dbtw.compat.dt.trace=true} logs {@code [DT]} lines for chews, fells (log count), wear targets,
 * strip bark, saw cuts and sapling verdicts.</p>
 */
// FCMOD-INTEGRATION(1.20.1): dynamic_trees: BTW's log, stump, saw, sapling and fertilizer rules on living Dynamic Trees, through DT's own API (INTEGRATIONS #027)
public final class DynamicTreesCompat {

    static final Logger LOGGER = LogManager.getLogger("BTW-Compat");

    /** {@code -Dbtw.compat.dt.trace=true}: INFO {@code [DT]} lines for the integration's decisions. */
    static final boolean TRACE = Boolean.getBoolean("btw.compat.dt.trace");

    /** True once {@link #init} finished; false again after the first failure ({@link #fail}). */
    static volatile boolean active;

    private static final AtomicBoolean FAILED = new AtomicBoolean();

    private DynamicTreesCompat() {}

    /**
     * The only entry point (BTWCompat, during mod construction, before FC init). Probes DT's API, registers the two
     * Forge-bus handlers, the FC machine hook and the sapling twin kind, then switches the integration on.
     * {@code modBus} is unused. Logs nothing itself: BTWCompat logs the {@code #027 enabled} line when this returns.
     */
    public static void init(IEventBus modBus) {
        DTTrees.probe();
        MinecraftForge.EVENT_BUS.register(new DTHarvestHandler());
        MinecraftForge.EVENT_BUS.register(new DTGrowthHandler());
        ModernBlockHooks.register(DTTrees::isHookTarget, new DTBlockHooks());
        TwinRegistry.registerModdedSapling(new DTSaplingTwin());
        active = true;
    }

    /**
     * Switches the integration off after {@code where} threw: one ERROR the first time, and {@link #active} false
     * from then on, so every handler and hook answers inertly and Dynamic Trees keeps its own rules.
     */
    static void fail(String where, Throwable t) {
        active = false;
        if (FAILED.compareAndSet(false, true)) {
            LOGGER.error("[BTW-Compat] dynamictrees integration switched off after {} failed; Dynamic Trees keeps "
                    + "its own rules", where, t);
        }
    }

    /** An INFO {@code [DT]} line when {@link #TRACE} is on. */
    static void trace(String format, Object... args) {
        if (TRACE) {
            LOGGER.info("[DT] " + format, args);
        }
    }
}
