package btw.forge.mixin;

import btw.forge.BoneMealPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.GlowLichenBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.MangroveLeavesBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.MossBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherrackBlock;
import net.minecraft.world.level.block.NyliumBlock;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RootedDirtBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): bone_meal
 *
 * <p><b>Feature:</b> 1.20.1 {@code BonemealableBlock}: {@code isValidBonemealTarget} says whether bone meal can grow a
 * block, and {@code performBonemeal} grows it at once. Besides BoneMealItem, direct callers grow plants with it: mod
 * machines and fake players, {@code BoneMealItem.growCrop}, the bee's cave-vine pollination (Bee.java:904-930),
 * GrassBlock (existing short grass, GrassBlock.java:50), GrowingPlantBodyBlock (its head, :63),
 * MangrovePropaguleBlock (:108) and SmallDripleafBlock (:103).</p>
 *
 * <p><b>Conflict with FC:</b> 4.B bone meal never grows a plant (it is FC fertilizer, see btw.forge.BoneMealPolicy),
 * and BTW's own growth rules govern FC-mapped plants and the post-1.5.2 plants BTW paces. A {@code performBonemeal}
 * reached outside the bone-meal item would still jump them stages, trees and huge mushrooms ahead.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): the 1.20.1 bonemealable blocks keep their bone-meal growth wherever BTW
 * does not govern the block; where it does, BTW's growth is the only growth.</p>
 *
 * <p><b>Rebalance:</b> none; 4.B parity. For a state on an owned route at its position
 * ({@link BoneMealPolicy#governsGrowth(net.minecraft.world.level.BlockGetter, BlockPos, BlockState)}: FC-mapped, a
 * post-1.5.2 plant {@code TickPolicy.isFcGrowthGoverned} reports, or a modded plant on BTW soil that takes it,
 * {@code btw.forge.farming.FcSoil.governsAt}, INTEGRATIONS #026), {@code isValidBonemealTarget} is false and
 * {@code performBonemeal} does nothing. Modded CropBlock and SaplingBlock subclasses (HarvestCraft's crops and
 * saplings) inherit the gate from CropBlock and SaplingBlock. Other states, and modded plants on soil BTW's rule does
 * not take (a fern crop on grass, a plant on a modern soil), are untouched.</p>
 *
 * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code isValidBonemealTarget(LevelReader, BlockPos,
 * BlockState, boolean)} (returns false) and {@code performBonemeal(ServerLevel, RandomSource, BlockPos, BlockState)}
 * (cancels), on every 1.20.1 class that declares them (javap: all 31 BonemealableBlock implementations declare
 * both). Subclasses that inherit them (BeetrootBlock, TorchflowerCropBlock, KelpBlock, the twisting and weeping vines)
 * are covered through their superclass. The test is per state (BoneMealPolicy.route), not per class, so a subclass or
 * state BTW does not govern keeps 1.20.1 bone meal.</p>
 *
 * <p><b>Alternative considered and rejected:</b> hooking only {@code BoneMealItem.applyBonemeal}: direct
 * {@code performBonemeal} callers skip it. Listing only the eight classes with an FC-mapped block (crops, stems,
 * saplings, cocoa, mushrooms, grass block, short grass, netherrack): the post-1.5.2 plants BTW governs would still
 * grow instantly. An injection into the interface: performBonemeal is abstract in BonemealableBlock.</p>
 */
// FCMOD-INTEGRATION(1.20.1): bone_meal: BTW governs the growth of FC-mapped and paced plants, so no 1.20.1 bone-meal growth may run on them
// FCMOD-INTEGRATION(1.20.1): modded_plant_rules: the gate asks the positional route, so a modded plant on BTW soil that takes it is governed as BTW's own plants are
@Mixin({AzaleaBlock.class, BambooSaplingBlock.class, BambooStalkBlock.class, BigDripleafBlock.class,
        BigDripleafStemBlock.class, CaveVinesBlock.class, CaveVinesPlantBlock.class, CocoaBlock.class, CropBlock.class,
        FungusBlock.class, GlowLichenBlock.class, GrassBlock.class, GrowingPlantBodyBlock.class,
        GrowingPlantHeadBlock.class, MangroveLeavesBlock.class, MangrovePropaguleBlock.class, MossBlock.class,
        MushroomBlock.class, NetherrackBlock.class, NyliumBlock.class, PinkPetalsBlock.class, PitcherCropBlock.class,
        RootedDirtBlock.class, SaplingBlock.class, SeaPickleBlock.class, SeagrassBlock.class, SmallDripleafBlock.class,
        StemBlock.class, SweetBerryBushBlock.class, TallFlowerBlock.class, TallGrassBlock.class})
public abstract class BonemealableGateMixin {

    @Inject(method = "isValidBonemealTarget", at = @At("HEAD"), cancellable = true)
    private void btw$notBonemealable(LevelReader level, BlockPos pos, BlockState state, boolean isClient,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (BoneMealPolicy.governsGrowth(level, pos, state)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "performBonemeal", at = @At("HEAD"), cancellable = true)
    private void btw$noInstantGrowth(ServerLevel level, RandomSource random, BlockPos pos, BlockState state,
                                     CallbackInfo ci) {
        if (BoneMealPolicy.governsGrowth(level, pos, state)) {
            ci.cancel();
        }
    }
}
