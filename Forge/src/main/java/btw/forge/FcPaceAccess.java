package btw.forge;

/**
 * The growth pace of a {@link TickPolicy#PACED} BlockState, cached at {@code BlockStateBase.initCache} next to the
 * tick owners by {@code btw.forge.mixin.BlockStateBaseTickMixin}, which implements it on every BlockState (a mixin
 * duck interface: cast through Object, {@code (FcPaceAccess) (Object) state}). Read by the random-tick dispatch
 * ({@code BlockStateRandomTickMixin}, {@link TickPolicy#pacedRollPasses}) and the self-audit.
 */
public interface FcPaceAccess {

    /** Fraction of the random ticks on which a paced state's 1.20.1 body runs (1 for every other state). */
    float btw$growthPace();

    /** True when a paced state does not grow in the End (FC's End ban). */
    boolean btw$paceEndBan();
}
