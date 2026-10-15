package btw.forge.mixin;

import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_plant_growth (bees)
 *
 * <p><b>Feature:</b> the bee's pollination budget: a bee grows at most ten crops per pollination
 * (Bee.BeeGrowCropGoal.canBeeUse, {@code getCropsGrownSincePollination() >= 10}), counted by the package-private
 * {@code incrementNumCropsGrownSincePollination}.</p>
 *
 * <p><b>Conflict with FC:</b> none by itself. {@link BeeGrowCropGoalMixin} replaces the bee's instant growth of an
 * FC-governed plant with one FC growth roll and must keep counting the visit, from outside the bee's package.</p>
 *
 * <p><b>Integration pattern:</b> C (rebalance), as part of {@link BeeGrowCropGoalMixin}.</p>
 *
 * <p><b>Rebalance:</b> none here: an accessor only.</p>
 *
 * <p><b>Injection strategy:</b> {@code @Invoker} for {@code Bee.incrementNumCropsGrownSincePollination()}.</p>
 *
 * <p><b>Alternative considered and rejected:</b> writing the private counter field directly (an accessor for the
 * field plus a read-modify-write, where the bee already has the one method that does it).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_plant_growth: lets the FC pollination roll count a bee visit toward the bee's ten crops per pollination
@Mixin(Bee.class)
public interface BeeAccessor {

    @Invoker("incrementNumCropsGrownSincePollination")
    void btw$incrementNumCropsGrownSincePollination();
}
