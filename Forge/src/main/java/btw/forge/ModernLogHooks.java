package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * FC's machines on class-S logs (the log rule, {@link FCLogRules#isForeignLog}: crimson and warped stems and hyphae,
 * modded logs; INTEGRATIONS #022 "FC actions visible to other mods"). FC's saw and stump remover reach such a log only
 * as a {@link ModernBlockStandIn}, which asks {@link ModernBlockHooks}; this hook answers with FCBlockLog's rules.
 * Class F (the bark and stripped forms FC reads as log 17 or 165) never gets here: FC's machines see the log id and
 * run FC's own cases (FCBlockSaw.java:618-639; FCBlockLog.GetDoesStumpRemoverWorkOnBlock, :68-72).
 *
 * <p>Registered once in the BTWForgeMod constructor right after BTWCompat.init, so an integration's own hook (Dynamic
 * Trees #027) is asked first. The predicate is the class-S structural test without its hook clause
 * ({@link FCLogRules#isForeignLogCandidate}): its AXIS and full-cube guards keep Dynamic Trees branches out even when
 * #027 is switched off.</p>
 */
// FCMOD-INTEGRATION(1.20.1): fc_log_rules: FC's saw and stump remover act on stems and modded logs with FCBlockLog's rules through the FC machine hooks
public final class ModernLogHooks implements ModernBlockHooks.Hook {

    /** The one instance; {@link FCLogRules#isForeignLog} compares {@link ModernBlockHooks#find}'s answer to it. */
    static final ModernLogHooks INSTANCE = new ModernLogHooks();

    private static volatile boolean registered;

    private ModernLogHooks() {}

    /** Registers the log hook for class-S states (idempotent). */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ModernBlockHooks.register(FCLogRules::isForeignLogCandidate, INSTANCE);
    }

    /**
     * FCBlockSaw cut a class-S log: FC's log saw output (FCBlockSaw.java:618-639) with the log's own planks: 4 of
     * {@link FCLogRules#sawPlanks}, 2 sawdust and the log's bark only if it still has it ({@link FCLogRules#barked};
     * none for a {@code bark/none} stem), then the cell is cleared. A log with no one-log planks recipe takes FC's
     * generic saw instead (vanilla/server Block.java:2190-2218): it drops as itself (its loot) and nothing else.
     */
    // FCMOD-INTEGRATION(1.20.1): fc_log_rules: the FC saw cuts a stem or modded log into its own planks, 2 sawdust and its bark, as FC's log
    @Override
    public boolean onSawed(ServerLevel level, BlockPos pos, BlockState state, Direction sawFacing) {
        ItemStack planks = FCLogRules.sawPlanks(level, state);
        if (planks.isEmpty()) {
            Block.dropResources(state, level, pos, level.getBlockEntity(pos));
        } else {
            boolean barked = FCLogRules.barked(level, pos, state);
            pop(level, pos, planks.copyWithCount(4));
            pop(level, pos, FCLogRules.sawdust(2));
            if (barked) pop(level, pos, FCLogRules.bark(FCLogRules.barkSpecies(state)));
        }
        level.removeBlock(pos, false);
        return true;
    }

    /**
     * FCItemStumpRemover on a class-S log: FC's rule, GetIsStump (FCBlockLog.java:68-72, no check for a trunk above),
     * with the port's one stump rule ({@link FCLogRules#isStump}). FC then clears the cell and uses one remover.
     */
    // FCMOD-INTEGRATION(1.20.1): fc_log_rules: the FC stump remover clears a stem or modded-log stump, as FC's log stump
    @Override
    public boolean stumpRemoverWorks(ServerLevel level, BlockPos pos, BlockState state) {
        return FCLogRules.isStump(level, pos, state);
    }

    private static void pop(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (!stack.isEmpty()) Block.popResource(level, pos, stack);
    }

    @Override
    public String toString() {
        return "the log rule (stems and modded logs, fc_log_rules)";
    }
}
