package btw.forge.compat.sereneseasons;

import btw.forge.climate.BTWClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.season.SeasonHooks;

/**
 * The {@link BTWClimate} provider for Serene Seasons (INTEGRATIONS #028): Serene Seasons' seasonal temperature decides
 * whether a position is cold enough to snow, for FC's farmland snow reversion ({@code WorldBridge.IsSnowingAtPos}) and
 * FC's ice and snow-block melting ({@code FCUtilsMisc.IsIKInColdBiome}).
 *
 * <p>Always seasonal (decision #9 (a)), whatever Serene Seasons' {@code generate_snow_ice} setting says: Serene
 * Seasons' rain hook already makes BTW's rain seasonal, so BTW's snow must follow the same season. Serene Seasons
 * never makes a biome warmer (its default adjustments are -0.8, -0.25 or 0), so where it leaves 1.20.1's verdict
 * unchanged FC's own cold table still decides ({@code BTWClimate.coldBiomeOverride}).</p>
 *
 * <p>No precipitation check: 1.20.1 lays snow wherever it rains and {@code Biome.shouldSnow} passes, and neither that
 * nor Serene Seasons' snow hook asks whether the biome has precipitation, so FC's "snowing" matches where snow layers
 * actually form. A throw or LinkageError is caught by {@link BTWClimate}, which drops this provider once and falls back
 * to 1.20.1's verdict and FC's table.</p>
 */
// FCMOD-INTEGRATION(1.20.1): serene_seasons: Serene Seasons' seasonal temperature decides FC's snow, ice and farmland snow reversion
final class SereneSeasonsClimate implements BTWClimate.Provider {

    @Override
    public boolean coldEnoughToSnow(ServerLevel level, Holder<Biome> biome, BlockPos pos) {
        return SeasonHooks.coldEnoughToSnowSeasonal(level, biome, pos);
    }

    @Override
    public String toString() {
        return "SereneSeasonsClimate";
    }
}
