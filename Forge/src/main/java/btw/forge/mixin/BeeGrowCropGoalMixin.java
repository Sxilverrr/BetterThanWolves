package btw.forge.mixin;

import btw.forge.TickPolicy;
import btw.forge.farming.FcSoil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_plant_growth (bees)
 *
 * <p><b>Feature:</b> bee pollination (Bee.BeeGrowCropGoal.tick, 1.15+): a bee carrying nectar grows the plant one or
 * two blocks below it by one stage, writing the next age straight into the world: a crop (CropBlock), a stem, a
 * sweet berry bush, or glow berries on cave vines (their bone meal), up to ten per pollination.</p>
 *
 * <p><b>Conflict with FC:</b> the write bypasses the random tick, so it bypasses FC's growth rules: bees grew FC's
 * carrots, potatoes, legacy wheat and stems one stage per visit (no light, hydration, weeds or End check, none of
 * FC's growth chances), and the post-1.5.2 plants FC now paces or runs through twins
 * (btw.forge.TwinRegistry) the same way.</p>
 *
 * <p><b>Integration pattern:</b> C (rebalance, preserve feature).</p>
 *
 * <p><b>Rebalance:</b> S17: each visit to an FC-governed plant ({@link TickPolicy#isFcGrowthGoverned}) is one FC
 * growth roll of that plant: its own random tick (FC's body for FC-owned and twin plants, the paced 1.20.1 body
 * for paced plants), the roll bone meal on a stem already gets in the port. A modded crop on BTW soil that takes it
 * ({@link FcSoil#governsAt}, INTEGRATIONS #026) is governed too: its own random tick fires CropGrowEvent.Pre, where
 * btw.forge.farming.ModdedPlantRules rolls FC's crop rule once. Bees still help a farm (extra rolls where they fly),
 * but every growth follows FC's rule. The visit counts toward the bee's ten crops exactly where
 * vanilla counts it (a crop, stem or berry bush that is not fully grown), and the bone-meal particles show when the
 * plant changed. Plants FC does not govern keep the vanilla visit.</p>
 *
 * <p><b>Injection strategy:</b> {@code @Redirect} of the one {@code Level.getBlockState} in {@code tick}: for a
 * governed plant vanilla would grow, the handler runs the roll at that position and answers air, so vanilla's
 * growth branch below sees nothing to grow. The bee is remembered from the goal's constructor (RETURN), rather than
 * shadowing the compiler's synthetic outer-instance field; the counter goes through {@link BeeAccessor}.</p>
 *
 * <p><b>Alternative considered and rejected:</b> cancelling the goal for governed plants (bees would stop helping FC
 * farms at all); redirecting the {@code setBlockAndUpdate} write (the cave vines' bone meal is a separate call, and
 * the next-age state is already computed by then).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_plant_growth: a bee visit to an FC-governed plant is one FC growth roll instead of an instant stage (S17)
// FCMOD-INTEGRATION(1.20.1): modded_plant_rules: a bee visit to a modded crop on BTW soil is one FC crop roll too, not a free stage
@Mixin(targets = "net.minecraft.world.entity.animal.Bee$BeeGrowCropGoal")
public abstract class BeeGrowCropGoalMixin {

    /** The bee this goal belongs to (the goal's outer instance, passed to its only constructor). */
    @Unique
    private Bee btw$bee;

    @Inject(method = "<init>(Lnet/minecraft/world/entity/animal/Bee;)V", at = @At("RETURN"))
    private void btw$rememberBee(Bee bee, CallbackInfo ci) {
        btw$bee = bee;
    }

    @Redirect(method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState btw$fcPollinationRoll(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(level instanceof ServerLevel serverLevel) || !state.is(BlockTags.BEE_GROWABLES)
                || !(TickPolicy.isFcGrowthGoverned(state) || FcSoil.governsAt(serverLevel, pos, state))) {
            return state;
        }
        Block block = state.getBlock();
        boolean counted;   // where vanilla counts the visit toward the bee's ten crops per pollination
        if (block instanceof CropBlock crop) {
            if (crop.isMaxAge(state)) return state;
            counted = true;
        } else if (block instanceof StemBlock) {
            if (state.getValue(StemBlock.AGE) >= 7) return state;
            counted = true;
        } else if (state.is(Blocks.SWEET_BERRY_BUSH)) {
            if (state.getValue(SweetBerryBushBlock.AGE) >= 3) return state;
            counted = true;
        } else if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
            counted = false;   // vanilla bone-meals them for berries and does not count the visit
        } else {
            return state;      // vanilla bees grow nothing else (a pitcher crop is a growable they skip)
        }
        Bee bee = btw$bee;
        if (bee == null) return state;
        if (state.isRandomlyTicking()) {
            state.randomTick(serverLevel, pos, bee.getRandom());
        }
        if (level.getBlockState(pos) != state) {
            level.levelEvent(2005, pos, 0);
        }
        if (counted) {
            ((BeeAccessor) bee).btw$incrementNumCropsGrownSincePollination();
        }
        return Blocks.AIR.defaultBlockState();
    }
}
