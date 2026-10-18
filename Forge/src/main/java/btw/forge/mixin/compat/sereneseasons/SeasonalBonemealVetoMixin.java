package btw.forge.mixin.compat.sereneseasons;

import btw.forge.BoneMealPolicy;
import glitchcore.event.player.PlayerInteractEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FCMOD-INTEGRATION(1.20.1): serene_seasons (bone meal, INTEGRATIONS #028 with #017)
 *
 * <p><b>Feature:</b> Serene Seasons' bone-meal veto ({@code SeasonalCropGrowthHandler.applyBonemeal}): out of season,
 * bone meal on a plant in its season block tags is cancelled and used up (behaviours 0 and 1), or the plant breaks
 * (behaviour 2), so bone meal cannot force growth that the season forbids.</p>
 *
 * <p><b>Conflict with FC:</b> bone meal is 4.B's FC fertilizer and nothing else (BoneMealPolicy): on an FC-owned or
 * governed plant it never grows the plant, and it is used up only when the soil below takes it. The veto is a
 * GlitchCore listener fed from Forge's {@code PlayerInteractEvent.RightClickBlock} (NORMAL), which 1.20.1's
 * {@code ServerPlayerGameMode.useItemOn} fires before the block's use and the held item, where BTW's fertilizer runs.
 * So out of season Serene Seasons ate the bone meal, or broke the plant, on its tagged FC-owned plants (carrots,
 * potatoes, stems, cane, cactus, cocoa, saplings, the beetroot and sapling twins) before FC's rule could act.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): Serene Seasons' veto stands aside exactly where
 * {@code BoneMealPolicy.route(level, pos, state)} owns the bone meal (anything but VANILLA), and FC's fertilizer rule
 * decides there. Serene Seasons still gates the growth of these plants through its random-tick veto, and keeps its
 * bone-meal veto on every VANILLA route. The logic lives in BoneMealPolicy; this class only asks it.</p>
 *
 * <p><b>Rebalance:</b> none beyond 4.B: out of season, bone meal on these plants is FC fertilizer for the soil, as in
 * season. Without Serene Seasons, or if it renames the method, nothing changes.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on Serene Seasons' class ({@code @Pseudo}, {@code remap = false},
 * {@code require = 0}; javap of 9.1.0.3, identical in 9.3.0.27: {@code public static void
 * applyBonemeal(glitchcore.event.player.PlayerInteractEvent$UseBlock)}). {@code @Inject} at HEAD, cancellable: when
 * the stack is bone meal ({@code BoneMealPolicy.isFertilizer}) and the clicked block's route is not VANILLA, return
 * before the veto. It runs on both sides, as the veto does; the route reads the same FC world on the client, so the
 * swing prediction agrees. A RuntimeException or LinkageError lets Serene Seasons' own veto run.</p>
 *
 * <p><b>Alternative considered and rejected:</b> a HIGHEST-priority {@code RightClickBlock} listener that runs BTW's
 * bone-meal path itself and cancels the event (it would re-implement 1.20.1's use order, where 4.B activates the
 * block, the weed pull included, before the held item, and duplicate BoneMealPolicy); accepting the veto (it eats the
 * item, or breaks the plant, where 4.B's fertilizer rule applies). No event lets BTW act before GlitchCore's listener
 * without cancelling 1.20.1's use path (SR15).</p>
 */
// FCMOD-INTEGRATION(1.20.1): serene_seasons: Serene Seasons' bone-meal veto stands aside where BTW's bone-meal rule owns the route, so bone meal stays 4.B fertilizer out of season
@Pseudo
@Mixin(targets = "sereneseasons.season.SeasonalCropGrowthHandler", remap = false)
public abstract class SeasonalBonemealVetoMixin {

    /** Skips the veto when BTW's bone-meal rule owns the clicked block (any route but VANILLA). */
    @Inject(method = "applyBonemeal(Lglitchcore/event/player/PlayerInteractEvent$UseBlock;)V", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private static void btw$fcBoneMealOwnsRoute(PlayerInteractEvent.UseBlock event, CallbackInfo ci) {
        try {
            if (!BoneMealPolicy.isFertilizer(event.getItemStack())) return;
            Level level = event.getPlayer().level();
            BlockPos pos = event.getHitResult().getBlockPos();
            if (BoneMealPolicy.route(level, pos, level.getBlockState(pos)) != BoneMealPolicy.Route.VANILLA) {
                ci.cancel();
            }
        } catch (RuntimeException | LinkageError e) {
            // Serene Seasons' own veto runs.
        }
    }
}
