package btw.forge.climate;

import btw.forge.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * The live climate seam (INTEGRATIONS #028, with the Serene Seasons entry): one place where a season mod tells BTW
 * whether a position is cold enough to snow, for the FC rules that decide by cold.
 *
 * <ul>
 *   <li>{@link #coldEnoughToSnow}: WorldBridge.IsSnowingAtPos, which FC's farmland snow reversion
 *       (FCBlockFarmland.CheckForSnowReversion), snow cover and FC's precipitation checks read.</li>
 *   <li>{@link #coldBiomeOverride}: FCUtilsMisc.IsIKInColdBiome (through the fcext mixin), FC's own 1.5.2 cold table
 *       for ice and snow melting.</li>
 * </ul>
 *
 * <p>Without a provider both answer exactly as before: 1.20.1's {@code Biome.coldEnoughToSnow} and FC's table. One
 * provider at a time (one volatile slot); only the season integration sets it. A provider that throws a
 * RuntimeException or LinkageError is logged once, dropped, and the fallback answers from then on, so a broken
 * integration can never stop FC's ticks.</p>
 */
// FCMOD-INTEGRATION(1.20.1): climate_seams: one provider slot lets a season mod's live climate reach FC's snow and cold rules
public final class BTWClimate {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Climate");

    private BTWClimate() {}

    /** A live climate: true when {@code pos} in {@code biome} is cold enough to snow now. */
    public interface Provider {
        boolean coldEnoughToSnow(ServerLevel level, Holder<Biome> biome, BlockPos pos);
    }

    private static volatile Provider provider;

    /** Sets (or with null clears) the climate provider. Only the season integration calls this. */
    public static void setProvider(@Nullable Provider p) {
        provider = p;
        if (p != null) {
            LOGGER.info("[CLIMATE] BTWClimate provider set: {}", p);
        }
    }

    /**
     * True when {@code pos} is cold enough to snow: the provider's answer, else 1.20.1's
     * {@code biome.value().coldEnoughToSnow(pos)}.
     */
    public static boolean coldEnoughToSnow(ServerLevel level, Holder<Biome> biome, BlockPos pos) {
        Provider p = provider;
        if (p != null) {
            try {
                return p.coldEnoughToSnow(level, biome, pos);
            } catch (RuntimeException | LinkageError e) {
                drop(p, e);
            }
        }
        return biome.value().coldEnoughToSnow(pos);
    }

    /**
     * FC's cold-biome answer at column (i, k) when the live climate changes it, else null (FC's table answers).
     * Null without a provider or off a WorldBridge. At the column's motion-blocking height, the provider's verdict is
     * compared with 1.20.1's own {@code coldEnoughToSnow}: only the public verdicts can be compared
     * ({@code Biome.getTemperature(BlockPos)} is private), and only a changed verdict replaces FC's table.
     */
    public static @Nullable Boolean coldBiomeOverride(btw.modern.World w, int i, int k) {
        Provider p = provider;
        if (p == null || !(w instanceof WorldBridge wb)) return null;
        ServerLevel level = wb.getServerLevel();
        if (level == null) return null;
        try {
            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(i, 0, k));
            Holder<Biome> biome = level.getBiome(pos);
            boolean vanilla = biome.value().coldEnoughToSnow(pos);
            boolean live;
            try {
                live = p.coldEnoughToSnow(level, biome, pos);
            } catch (RuntimeException | LinkageError e) {
                drop(p, e);
                return null;
            }
            return vanilla == live ? null : live;
        } catch (RuntimeException e) {
            return null;   // read inside FC block ticks: never throw out of them; FC's table answers
        }
    }

    /** Drops a failing provider (once: only the thread that clears the slot logs). */
    private static void drop(Provider p, Throwable e) {
        synchronized (BTWClimate.class) {
            if (provider != p) return;
            provider = null;
        }
        LOGGER.warn("[CLIMATE] BTWClimate provider failed; dropped ({}): {}", p, e.toString(), e);
    }
}
