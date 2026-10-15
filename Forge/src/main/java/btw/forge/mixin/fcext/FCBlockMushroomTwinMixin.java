package btw.forge.mixin.fcext;

import btw.forge.TwinRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_plant_growth (Nether fungi)
 *
 * <p><b>Feature:</b> crimson and warped fungi (FungusBlock, 1.16+). 1.20.1 never grows them on its own: no random
 * tick, only bone meal (40%) makes the huge fungus.</p>
 *
 * <p><b>Conflict with FC:</b> under 4.B's bone-meal rule (bone meal fertilizes the soil and never grows a plant)
 * they would never grow at all. FC's nearest rule is its red mushroom: "mushrooms growing on mycelium have a chance
 * of sprouting into giant mushrooms" (Common FCBlockMushroom.java:34-51: not in the End, on mycelium 1 in 50 random
 * ticks to fertilizeMushroom, else the 1.5.2 spread). Its soil test names Block.mycelium, and fertilizeMushroom grows
 * the 1.5.2 red or brown huge mushroom.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend). The fungi random-tick as a plain FCBlockMushroom instance (a twin,
 * btw.forge.TwinRegistry) that FC's world reads see at their cell; nylium is the Nether's mycelium.</p>
 *
 * <p><b>Rebalance:</b> FC's numbers: not in the End, on the species' own nylium 1 in 50 random ticks grows its
 * planted huge fungus (mean 2.8 days at randomTickSpeed 3), as FungusBlock's bone meal places it (Forge's
 * BlockGrowFeatureEvent included). No spread (S11): FC's 1/25 mushroom spread would thicken the Nether forests.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; descriptor
 * from javap of the remapped FC output), keyed on the twin's id, so FC's red mushroom (id 40) is untouched.
 * {@code updateTick} HEAD, cancellable: FC's rule re-expressed with the nylium soil test
 * (TwinRegistry.fungusTick); survival stays 1.20.1's (FungusBlock's shape updates), as FCBlockMushroom's tick never
 * checks survival.</p>
 *
 * <p><b>Alternative considered and rejected:</b> nylium read as mycelium 110 to FC (FC's mycelium rules, spread and
 * decay, would run on nylium); a port subclass of FCBlockMushroom (FC subclasses in the bridge, against the
 * documented layering); leaving the fungi bone-meal-only (never grow under the bone-meal rule).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_plant_growth: crimson and warped fungi grow by FC's mushroom-on-mycelium rule on their nylium through an FCBlockMushroom twin
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockMushroom", remap = false)
public abstract class FCBlockMushroomTwinMixin {

    /** FCBlockMushroom.updateTick (Common FCBlockMushroom.java:34-51) of a twin: the fungus rule on nylium. */
    @Inject(method = "updateTick(Lbtw/modern/World;IIILjava/util/Random;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private void btw$fungusTick(btw.modern.World world, int i, int j, int k, Random rand, CallbackInfo ci) {
        if (!TwinRegistry.isTwinId(((btw.modern.Block) (Object) this).blockID)) return;
        ci.cancel();
        TwinRegistry.fungusTick(world, i, j, k, rand);
    }
}
