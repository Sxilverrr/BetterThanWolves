package btw.forge.mixin.fcext;

import btw.forge.climate.BTWClimate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): climate_seams (FC cold biomes)
 *
 * <p><b>Feature:</b> a live climate: a season mod (Serene Seasons) makes a biome colder or warmer than its 1.20.1
 * table through {@code Biome.coldEnoughToSnow}, and registers the answer with btw.forge.climate.BTWClimate.</p>
 *
 * <p><b>Conflict with FC:</b> FC decides cold by its own 1.5.2 table: {@code FCUtilsMisc.IsIKInColdBiome} is
 * {@code getBiomeGenForCoords(i, k).getFloatTemperature() <= 0.15}. Its callers keep ice from melting and snow from
 * turning to water: FCBlockIce.onBlockAdded (Common :83), FCBlockSnowSolid (:90), FCBlockSnowSolidSlab (:86),
 * FCBlockSnowLoose (Server :104) and FCBlockSnowLooseSlab (Server :112). A winter that 1.20.1 and the season mod
 * show would melt FC's snow and ice at once.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's table stays the answer wherever the live climate agrees with
 * 1.20.1's; only where a registered provider changes the verdict at that column does FC see the live one.</p>
 *
 * <p><b>Rebalance:</b> none without a provider (BTWClimate.coldBiomeOverride returns null). With one, the verdict at
 * the column's motion-blocking height is compared with 1.20.1's own {@code coldEnoughToSnow}; a difference replaces
 * FC's answer. A provider that throws is dropped and FC's table answers again.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; javap of the
 * remapped FC output: {@code public static boolean IsIKInColdBiome(btw.modern.World, int, int)}). {@code @Inject} at
 * HEAD, cancellable, static handler: a non-null override is the return value.</p>
 *
 * <p><b>Alternative considered and rejected:</b> overriding WorldBridge.getBiomeGenForCoords' temperature (every FC
 * biome read, rain, grass colour and spawning included, would follow the season); comparing temperatures instead of
 * verdicts ({@code Biome.getTemperature(BlockPos)} is private in 1.20.1, only {@code coldEnoughToSnow} is public);
 * mixins on the five callers (five targets for one predicate).</p>
 */
// FCMOD-INTEGRATION(1.20.1): climate_seams: FC's cold-biome table follows a registered live climate where it changes 1.20.1's verdict, so FC ice and snow follow the seasons
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.util.FCUtilsMisc", remap = false)
public abstract class FCUtilsMiscColdBiomeMixin {

    /** FCUtilsMisc.IsIKInColdBiome (Common FCUtilsMisc.java:104): the live climate's verdict where it differs. */
    @Inject(method = "IsIKInColdBiome(Lbtw/modern/World;II)Z", at = @At("HEAD"), cancellable = true, require = 1,
            remap = false)
    private static void btw$liveClimate(btw.modern.World world, int i, int k, CallbackInfoReturnable<Boolean> cir) {
        Boolean override = BTWClimate.coldBiomeOverride(world, i, k);
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
