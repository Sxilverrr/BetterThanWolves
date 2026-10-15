package btw.forge.mixin;

import btw.forge.ProxyRegistry;
import btw.forge.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.PlantType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FCMOD-INTEGRATION(1.20.1): plant_soil_rules
 *
 * <p><b>Feature:</b> Forge's soil predicate {@code Block.canSustainPlant} (Forge-patched Block.java:514-552).
 * Every 1.20.1 plant survival check asks it: BushBlock.canSurvive (BushBlock.java:26-31, behind the updateShape
 * removal of every flower, sapling, grass, crop, stem, wart, mushroom and lily pad), CactusBlock.canSurvive
 * (CactusBlock.java:83-91, whose updateShape schedules the 1-tick CactusBlock.tick that destroys the cactus),
 * SugarCaneBlock.canSurvive (SugarCaneBlock.java:72-95, same executor), MushroomBlock.canSurvive and
 * FarmBlock.shouldMaintainFarmland.</p>
 *
 * <p><b>Conflict with FC:</b> those executors stay 1.20.1-owned (TickPolicy keeps the cactus, sugar cane and
 * farmland scheduled ticks VANILLA: FC's updateTick there is the random growth or hydration body), but their soil
 * rule was 1.20.1's: tags and Blocks.FARMLAND. FC soils are ProxyBlocks outside every tag, so a cactus on a planter
 * or planter soil, cane on loose dirt or with only diagonal water, and carrots, potatoes or stems on FC farmland
 * 1087/1088 were destroyed by their first neighbour shape update, which 4.B keeps (FCBlockCactus.canBlockStay,
 * FCBlockReed.canPlaceBlockAt, FCBlockPlants.CanGrowOnBlock).</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): the 1.20.1 survival executors stay; the soil answer comes from
 * FC's soil hooks whenever the soil is an FC block.</p>
 *
 * <p><b>Rebalance:</b> none for 1.5.2 plants, which get exactly the soil clause of their 4.B rule. Modern-only plants
 * (beetroot, modern saplings and flowers, berry bushes, seagrass) keep 1.20.1's rules and additionally accept the FC
 * soils FC's hook for their kind accepts (FC rules for modern plants are the deferred decision D4).</p>
 *
 * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code Block.canSustainPlant(BlockState, BlockGetter,
 * BlockPos, Direction, IPlantable)}, cancellable, {@code remap = false} (Forge-added, no SRG name). It acts only on
 * a {@link ServerLevel} (feature placement passes a WorldGenRegion and the client its own level; both keep 1.20.1).
 * That includes chunk generation's last step: LevelChunk.postProcessGeneration (LevelChunk.java:491-507) runs the
 * shape updates and fluid ticks of the cells world generation marked (every mushroom, Blocks.java:210-211; aquifer
 * and surface fluids) in the ServerLevel, on the server thread, so FC's soil rules, and FC neighbour rules reached
 * through fluid spread, do run at generation: check changes to them against freshly generated chunks. It acts for
 * {@link Direction#UP}, and only when the soil block has an FC block (modern soils keep 1.20.1 here; FC's own checks
 * see them through ModernBlockStandIn's soil hooks). The soil position is the one holding the soil state:
 * CactusBlock.canSurvive passes the cactus position with the state below it (CactusBlock.java:90). FC then reads
 * the soil through WorldBridge, as its canBlockStay does with {@code world.getBlockId(i, j - 1, k)}:</p>
 * <ul>
 *   <li>cactus 81: soil is cactus or {@code CanCactusGrowOnBlock} (Server FCBlockCactus.java:67-78);</li>
 *   <li>sugar cane 83: soil is cane, or {@code CanReedsGrowOnBlock} and
 *       {@code IsConsideredNeighbouringWaterForReedGrowthOn} (Server FCBlockReed.java:72-80; 1.5.2
 *       BlockReed.canBlockStay = canPlaceBlockAt, vanilla/server BlockReed.java:92-95);</li>
 *   <li>lily pad 111: the soil cell holds a water source. 1.5.2 BlockLilyPad.canBlockStay asks for water material
 *       with metadata 0 below (vanilla/server BlockLilyPad.java:70-73); FCBlockLilyPad.CanGrowOnBlock (id ==
 *       waterStill 9) is its placement test, and the port reads every water cell as id 8 (ProxyRegistry.java:343);</li>
 *   <li>any other 1.5.2 plant whose FC class declares a soil rule: its {@code CanGrowOnBlock} at the soil position,
 *       the soil clause of BlockFlower / FCBlockPlants.canBlockStay (Server FCBlockPlants.java:78-81, 167-171);</li>
 *   <li>a mapped block without a soil rule (the stem fruit StemBlock passes as the plantable): 1.20.1;</li>
 *   <li>a modern-only plant: true when the soil's FC hook for its kind accepts it (SaplingBlock:
 *       CanSaplingsGrowOnBlock; PlantType CROP: CanDomesticatedCropsGrowOnBlock; PLAINS: CanWildVegetationGrowOnBlock;
 *       DESERT: CanCactusGrowOnBlock; NETHER: CanNetherWartGrowOnBlock; BEACH: the reed rule), otherwise 1.20.1.</li>
 * </ul>
 * <p>Any throw is logged once and falls back to 1.20.1.</p>
 *
 * <p><b>Alternative considered and rejected:</b> mixins on each plant's canSurvive (CactusBlock, SugarCaneBlock,
 * BushBlock and subclasses with their own canSurvive). Five or more targets for one predicate, and every modded plant
 * that asks canSustainPlant would still see 1.20.1 soil rules on FC soils. Replacing the 1.20.1 answer for modern-only
 * plants as well was rejected: Forge types every unlisted plantable PLAINS, so seagrass and sea pickles on sand, gravel
 * or clay, wither roses on netherrack and flowers on vanilla farmland would pop under FC's wild-vegetation rule.</p>
 */
// FCMOD-INTEGRATION(1.20.1): plant_soil_rules: the 1.20.1 survival executors stay, so their soil predicate must apply FC's soil rules on FC soils
@Mixin(Block.class)
public abstract class PlantSoilMixin {

    @Unique
    private static final Logger BTW$LOGGER = LogManager.getLogger("BTW-PlantSoil");

    /** 1.5.2 ids whose 4.B soil rule is not CanGrowOnBlock (FCBlockCactus, FCBlockReed, BlockLilyPad). */
    @Unique
    private static final int BTW$CACTUS_ID = 81;
    @Unique
    private static final int BTW$REED_ID = 83;
    @Unique
    private static final int BTW$LILY_PAD_ID = 111;

    /** Per FC class: does it declare CanGrowOnBlock below btw.modern.Block (a plant with a soil rule)? */
    @Unique
    private static final Map<Class<?>, Boolean> BTW$HAS_SOIL_RULE = new ConcurrentHashMap<>();

    @Unique
    private static volatile boolean btw$failureLogged;

    @Inject(method = "canSustainPlant(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraftforge/common/IPlantable;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void btw$fcSoilRules(BlockState state, BlockGetter getter, BlockPos pos, Direction facing,
                                 IPlantable plantable, CallbackInfoReturnable<Boolean> cir) {
        if (!(getter instanceof ServerLevel level) || facing != Direction.UP) return;
        try {
            // Modern soil (podzol, moss, mud, red sand, ...): 1.20.1's tags already accept what D3 accepts.
            if (ProxyRegistry.getFcBlock(state.getBlock()) == null) return;
            BlockPos soilPos = btw$soilPos(level, state, pos);
            if (soilPos == null) return;   // a state that is not in the world here: nothing for FC to read

            BlockPos plantPos = pos.relative(facing);
            Block plant = plantable.getPlant(level, plantPos).getBlock();
            int plantId = ProxyRegistry.getBlockId(plant);

            btw.modern.World world = WorldBridge.getOrCreate(level);
            int x = soilPos.getX();
            int y = soilPos.getY();
            int z = soilPos.getZ();
            int soilId = world.getBlockId(x, y, z);
            btw.modern.Block soilFc = btw.modern.Block.blocksList[soilId];
            if (soilFc == null) return;

            if (plantId > 0) {
                Boolean fc = btw$fcPlantRule(plantId, soilId, soilFc, state, world, x, y, z);
                if (fc != null) cir.setReturnValue(fc);
            } else {
                PlantType type = plantable.getPlantType(level, plantPos);
                if (btw$modernPlantAccepted(plant, type, soilFc, world, x, y, z)) cir.setReturnValue(true);
            }
        } catch (Throwable t) {
            // Survival checks run inside neighbour shape updates: never throw out of them.
            if (!btw$failureLogged) {
                btw$failureLogged = true;
                BTW$LOGGER.warn("FC soil rule failed for {} under {} at {}; 1.20.1's rule applies (logged once)",
                        state, plantable, pos, t);
            }
        }
    }

    /**
     * The position holding {@code state}: {@code pos} itself, or the cell below it for CactusBlock.canSurvive, which
     * passes the cactus position with the soil's state (CactusBlock.java:90). Null when neither holds it.
     */
    @Unique
    private static BlockPos btw$soilPos(ServerLevel level, BlockState state, BlockPos pos) {
        if (level.getBlockState(pos) == state) return pos;
        BlockPos below = pos.below();
        return level.getBlockState(below) == state ? below : null;
    }

    /** The soil clause of the 4.B rule of the 1.5.2 plant {@code plantId}, or null to keep 1.20.1's answer. */
    @Unique
    private static Boolean btw$fcPlantRule(int plantId, int soilId, btw.modern.Block soilFc, BlockState soilState,
                                           btw.modern.World world, int x, int y, int z) {
        switch (plantId) {
            case BTW$CACTUS_ID:
                return soilId == BTW$CACTUS_ID || soilFc.CanCactusGrowOnBlock(world, x, y, z);
            case BTW$REED_ID:
                return soilId == BTW$REED_ID || btw$reedSoil(soilFc, world, x, y, z);
            case BTW$LILY_PAD_ID: {
                // Water material with metadata 0 in 1.5.2 terms: WorldBridge reads source water as meta 0
                // (LiquidBlock.LEVEL), and a waterlogged block holds the same source.
                FluidState fluid = soilState.getFluidState();
                return fluid.is(FluidTags.WATER) && fluid.isSource();
            }
            default: {
                btw.modern.Block plantFc = btw.modern.Block.blocksList[plantId];
                if (plantFc == null || !btw$hasSoilRule(plantFc.getClass())) return null;
                return plantFc.CanGrowOnBlock(world, x, y, z);
            }
        }
    }

    /** FC's soil hook for a modern-only plant of this kind; true only adds support to 1.20.1's rule. */
    @Unique
    private static boolean btw$modernPlantAccepted(Block plant, PlantType type, btw.modern.Block soilFc,
                                                   btw.modern.World world, int x, int y, int z) {
        if (plant instanceof SaplingBlock) return soilFc.CanSaplingsGrowOnBlock(world, x, y, z);
        if (PlantType.CROP.equals(type)) return soilFc.CanDomesticatedCropsGrowOnBlock(world, x, y, z);
        if (PlantType.PLAINS.equals(type)) return soilFc.CanWildVegetationGrowOnBlock(world, x, y, z);
        if (PlantType.DESERT.equals(type)) return soilFc.CanCactusGrowOnBlock(world, x, y, z);
        if (PlantType.NETHER.equals(type)) return soilFc.CanNetherWartGrowOnBlock(world, x, y, z);
        if (PlantType.BEACH.equals(type)) return btw$reedSoil(soilFc, world, x, y, z);
        return false;
    }

    /**
     * FCBlockReed's soil clause: reed soil with water in the 3x3 at soil level, diagonals included (1.5.2 FCMOD
     * Block.IsConsideredNeighbouringWaterForReedGrowthOn, vanilla/server Block.java:3376-3390).
     */
    @Unique
    private static boolean btw$reedSoil(btw.modern.Block soilFc, btw.modern.World world, int x, int y, int z) {
        return soilFc.CanReedsGrowOnBlock(world, x, y, z)
                && soilFc.IsConsideredNeighbouringWaterForReedGrowthOn(world, x, y, z);
    }

    /**
     * True when the runtime FC class declares CanGrowOnBlock below btw.modern.Block, i.e. FC gives the plant a soil
     * rule (BlockFlower and every FC plant). FC classes are remapped btw.api -> btw.modern at build time, so the
     * parameter is btw.modern.World. Cached per class.
     */
    @Unique
    private static boolean btw$hasSoilRule(Class<?> fcClass) {
        Boolean cached = BTW$HAS_SOIL_RULE.get(fcClass);
        if (cached != null) return cached;
        boolean declared;
        try {
            declared = fcClass.getMethod("CanGrowOnBlock", btw.modern.World.class, int.class, int.class, int.class)
                    .getDeclaringClass() != btw.modern.Block.class;
        } catch (NoSuchMethodException e) {
            declared = false;
        }
        BTW$HAS_SOIL_RULE.put(fcClass, declared);
        return declared;
    }
}
