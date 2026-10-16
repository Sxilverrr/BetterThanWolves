package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * FC machine hooks for modern blocks (INTEGRATIONS #022, "FC actions visible to other mods"). FC's saw, stump remover
 * and fertilizer reach a colliding 1.20.1 block only as a {@link ModernBlockStandIn}, whose FC hooks were always
 * inert. An integration registers a {@link Hook} for the states it owns (Dynamic Trees: the saw cuts a branch, the
 * stump remover takes a root), and the stand-in asks it with the live state.
 *
 * <p>Registration is a predicate over the live state plus a hook; the first registered match wins. Lookups run on the
 * server thread inside FC item and block code: a predicate that throws is logged once and skipped, and the stand-in
 * wraps every hook call (a throw answers false, FC's inert default). With no hook every answer is false, exactly as
 * before.</p>
 */
// FCMOD-INTEGRATION(1.20.1): fc_machine_hooks: FC's saw, stump remover and fertilizer can act on modern blocks through hooks that integrations register
public final class ModernBlockHooks {

    private static final Logger LOGGER = LogManager.getLogger("BTW-StandIn");

    private ModernBlockHooks() {}

    /** What FC's machines do to a modern block; every default is FC's inert answer (false). */
    public interface Hook {
        /** FCBlockSaw cut the block (the saw faces {@code sawFacing}); true when the hook handled it. */
        default boolean onSawed(ServerLevel level, BlockPos pos, BlockState state, Direction sawFacing) {
            return false;
        }

        /** FCItemStumpRemover: true when the remover works here (FC then removes the block and uses one item). */
        default boolean stumpRemoverWorks(ServerLevel level, BlockPos pos, BlockState state) {
            return false;
        }

        /** FCItemDye fertilizer (bone meal): true when the hook took it (FC then uses one item). */
        default boolean applyFertilizer(ServerLevel level, BlockPos pos, BlockState state) {
            return false;
        }
    }

    private record Entry(Predicate<BlockState> applies, Hook hook) {}

    private static final CopyOnWriteArrayList<Entry> HOOKS = new CopyOnWriteArrayList<>();
    private static final Set<Entry> FAILED =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /** Registers {@code hook} for the live states {@code applies} accepts. Earlier registrations win. */
    public static void register(Predicate<BlockState> applies, Hook hook) {
        HOOKS.add(new Entry(Objects.requireNonNull(applies, "applies"), Objects.requireNonNull(hook, "hook")));
        LOGGER.info("[STAND-IN] FC machine hook registered: {}", hook);
    }

    /** The first hook whose predicate accepts {@code state}, or null. */
    public static @Nullable Hook find(BlockState state) {
        if (state == null || HOOKS.isEmpty()) return null;
        for (Entry entry : HOOKS) {
            try {
                if (entry.applies().test(state)) return entry.hook();
            } catch (RuntimeException | LinkageError e) {
                if (FAILED.add(entry)) {
                    LOGGER.warn("[STAND-IN] FC machine hook predicate of {} threw {} for {}; skipped (logged once)",
                            entry.hook(), e.toString(), state, e);
                }
            }
        }
        return null;
    }
}
