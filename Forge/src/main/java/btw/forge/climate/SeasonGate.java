package btw.forge.climate;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * The season growth seam (INTEGRATIONS #028, with the Serene Seasons entry): a season mod's verdict on one growth
 * step of a plant BTW grows by its own code path, where no Forge crop event exists (the sapling-to-tree step of a
 * Dynamic Trees sapling, for instance).
 *
 * <p>{@link #growthVerdict} answers {@link Verdict#ALLOW} with no provider, so BTW's growth is unchanged without a
 * season mod. One provider at a time (one volatile slot); a provider that throws a RuntimeException or LinkageError, or
 * answers null, is logged once, dropped, and ALLOW answers from then on. Core BTW has no caller: the season and
 * Dynamic Trees integrations consume it.</p>
 */
// FCMOD-INTEGRATION(1.20.1): climate_seams: one provider slot lets a season mod gate BTW-driven growth steps that fire no Forge crop event
public final class SeasonGate {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Climate");

    private SeasonGate() {}

    /** A season's verdict on one growth step: grow, do not grow, or destroy the plant (out of season). */
    public enum Verdict { ALLOW, DENY, DESTROY }

    /**
     * A live season: the verdict for the plant {@code state} at {@code pos}. {@code fertilityKey} names the plant to
     * the season mod's fertility table (normally the block's registry key).
     */
    public interface Provider {
        Verdict growthVerdict(ServerLevel level, BlockPos pos, BlockState state, ResourceLocation fertilityKey);
    }

    private static volatile Provider provider;

    /** Sets (or with null clears) the season provider. Only the season integration calls this. */
    public static void setProvider(@Nullable Provider p) {
        provider = p;
        if (p != null) {
            LOGGER.info("[CLIMATE] SeasonGate provider set: {}", p);
        }
    }

    /** The provider's verdict, or {@link Verdict#ALLOW} with no provider or after a failure. */
    public static Verdict growthVerdict(ServerLevel level, BlockPos pos, BlockState state,
                                        ResourceLocation fertilityKey) {
        Provider p = provider;
        if (p == null) return Verdict.ALLOW;
        Verdict verdict;
        try {
            verdict = p.growthVerdict(level, pos, state, fertilityKey);
        } catch (RuntimeException | LinkageError e) {
            drop(p, e);
            return Verdict.ALLOW;
        }
        if (verdict == null) {
            drop(p, new IllegalStateException("null verdict"));
            return Verdict.ALLOW;
        }
        return verdict;
    }

    /** Drops a failing provider (once: only the thread that clears the slot logs). */
    private static void drop(Provider p, Throwable e) {
        synchronized (SeasonGate.class) {
            if (provider != p) return;
            provider = null;
        }
        LOGGER.warn("[CLIMATE] SeasonGate provider failed; dropped ({}): {}", p, e.toString(), e);
    }
}
