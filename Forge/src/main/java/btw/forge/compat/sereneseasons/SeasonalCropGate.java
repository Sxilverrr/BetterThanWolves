package btw.forge.compat.sereneseasons;

import btw.forge.ProxyBlock;
import btw.forge.climate.SeasonGate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Seasons for BTW's own ProxyBlock crops (INTEGRATIONS #028): BTW wheat (block_1089 and its top block_1090) and hemp
 * (block_241) ask the {@link SeasonGate} at FC's growth roll, where {@code FcCropGrowthHooks} posts
 * {@code CropGrowEvent.Pre}. Registered on the Forge bus at HIGH by {@link SereneSeasonsCompat} only when Serene
 * Seasons' fertility API is present.
 *
 * <ul>
 *   <li>Acts only on a DEFAULT result (another mod's ALLOW or DENY is never overwritten), on a server level, and on a
 *       ProxyBlock state whose seed key is known ({@link FcCropSeasonNames}). A crop's season is its seed's.</li>
 *   <li>DENY fails this growth attempt; FC's daily growth, the night reset of the grown-today flag, maintenance,
 *       light, weeds and hydration are untouched. DESTROY also breaks the crop without drops; FC's failed roll jumps
 *       straight to the method's return, so the destroy is safe. ALLOW leaves DEFAULT: FC's own roll decides.</li>
 *   <li>Bone meal on BTW crops stays FC fertilizer: Serene Seasons' bone-meal veto acts only on states in its own
 *       season block tags, and BTW's crop blocks are never put there.</li>
 * </ul>
 *
 * <p>Names no Serene Seasons class: every Serene Seasons decision is the provider's. A RuntimeException or LinkageError
 * switches this listener off once with one WARN; BTW crops are then season-blind until restart.</p>
 */
// FCMOD-INTEGRATION(1.20.1): serene_seasons: BTW wheat and hemp ask the season gate at FC's growth roll; out of season the roll fails or the crop breaks
final class SeasonalCropGate {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Compat");

    private static volatile boolean disabled;

    private SeasonalCropGate() {}

    /** {@code CropGrowEvent.Pre} at HIGH priority, on the Forge bus. */
    static void onCropGrowPre(BlockEvent.CropGrowEvent.Pre e) {
        if (disabled || e.getResult() != Event.Result.DEFAULT) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockState state = e.getState();
        if (!(state.getBlock() instanceof ProxyBlock)) return;
        BlockPos pos = e.getPos();
        try {
            ResourceLocation key = FcCropSeasonNames.seedKeyFor(level, pos, state);
            if (key == null) return;
            switch (SeasonGate.growthVerdict(level, pos, state, key)) {
                case DENY:
                    e.setResult(Event.Result.DENY);
                    break;
                case DESTROY:
                    e.setResult(Event.Result.DENY);
                    level.destroyBlock(pos, false);
                    break;
                default:
                    break;   // ALLOW: leave DEFAULT, so FC's own roll and rules decide
            }
        } catch (RuntimeException | LinkageError t) {
            disable(t);
        }
    }

    /** Switches the gate off; only the call that flips the flag logs. */
    private static void disable(Throwable t) {
        synchronized (SeasonalCropGate.class) {
            if (disabled) return;
            disabled = true;
        }
        LOGGER.warn("[BTW-Compat] Serene Seasons crop gate failed; BTW crops are season-blind until restart", t);
    }
}
