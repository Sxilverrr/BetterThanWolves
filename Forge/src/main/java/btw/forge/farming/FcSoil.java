package btw.forge.farming;

import btw.forge.BTWForgeMod;
import btw.forge.BTWLifecycle;
import btw.forge.ProxyBlock;
import btw.forge.ProxyRegistry;
import btw.forge.TickPolicy;
import btw.forge.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one predicate for plants of other mods on BTW soil (INTEGRATIONS #026; maintainer ruling: each post-1.5.2 plant,
 * vanilla or modded, takes the 4.B rule of its nearest FC relative). Used by BoneMealPolicy, BonemealableGateMixin,
 * BeeGrowCropGoalMixin, ModdedPlantRules, ProxyBlock (untilling and the weed cue), ModernBlockStandIn (the plant
 * stand-in) and the immature-crop loot modifier, so they all agree on which plant BTW governs where.
 *
 * <p><b>Governed modded plant</b> ({@link #governedKind}): not a ProxyBlock, no FC identity id
 * ({@link ProxyRegistry#getBlockId} 0), not already governed by BTW ({@link TickPolicy#isFcGrowthGoverned}: the twins
 * beetroot, torchflower and pitcher crops, acacia, cherry and dark oak saplings and the propagule have id 0 too, and
 * every unmapped SaplingBlock with STAGE runs the sapling twin), and of a {@link Kind}: a CropBlock, a sapling
 * (SaplingBlock, or in {@code #minecraft:saplings}: Dynamic Trees saplings come in through
 * {@code #dynamictrees:saplings}) or a member of {@code #betterthanwolves:fc_soil_plants} (written by the HarvestCraft
 * integration).</p>
 *
 * <p><b>Accepting BTW soil</b> ({@link #isOnAcceptingBtwSoil}): the FC block below answers its kind's FC soil hook:
 * {@code CanDomesticatedCropsGrowOnBlock} (crops), {@code CanSaplingsGrowOnBlock} (saplings),
 * {@code CanWildVegetationGrowOnBlock} (wild plants). FC grass and dirt accept saplings and wild plants but not crops;
 * modern soils answer through their stand-in tags and never take crops. A fern crop of a survival mod on grass or a
 * water plant on sand therefore grows and drops by 1.20.1's rules; bone meal on it still follows 4.B
 * (BoneMealPolicy.route: it relays to that FC ground, which declines, so the item is kept).</p>
 *
 * <p>Reads only: no world writes. Every FC call is wrapped: a throw is logged once and answers false. Server: the
 * WorldBridge; client: the render thread's ClientFcWorld (btw.forge.client.FcSoilClient); any other reader (world
 * generation, mesh threads) is never governed.</p>
 */
// FCMOD-INTEGRATION(1.20.1): modded_plant_rules: one predicate decides which modded plants stand on BTW soil that takes them, for every BTW rule that applies to them
public final class FcSoil {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ModdedPlants");

    private FcSoil() {}

    /** The FC soil hook a plant asks: domesticated crops, saplings, wild vegetation. */
    public enum Kind { CROP, SAPLING, WILD }

    /** Modded wild plants BTW's wild-vegetation soil rule governs (the HarvestCraft integration writes it). */
    public static final TagKey<Block> FC_SOIL_PLANTS =
            BlockTags.create(new ResourceLocation(BTWForgeMod.MOD_ID, "fc_soil_plants"));

    /** fcBlockDirtLoose's default ParseID (FCBetterThanWolves: ParseID("fcBlockDirtLooseID", 1011)). */
    private static final int DEFAULT_LOOSE_DIRT_ID = 1011;
    private static volatile int looseDirtId;   // 0 = not read yet
    private static volatile boolean looseDirtWarned;

    private static final Set<String> FAILURES_LOGGED = ConcurrentHashMap.newKeySet();

    // ================================================================
    // Plants
    // ================================================================

    /** The FC soil hook {@code s} asks, whoever governs it; null for a block that is none of the kinds. */
    public static @Nullable Kind kindOf(@Nullable BlockState s) {
        if (s == null) return null;
        Block b = s.getBlock();
        if (b instanceof CropBlock) return Kind.CROP;
        if (b instanceof SaplingBlock || s.is(BlockTags.SAPLINGS)) return Kind.SAPLING;
        if (s.is(FC_SOIL_PLANTS)) return Kind.WILD;
        return null;
    }

    /** The kind of a governed modded plant (see the class javadoc), else null. False before FC is ready. */
    public static @Nullable Kind governedKind(@Nullable BlockState s) {
        if (s == null || !BTWLifecycle.isFcReady()) return null;
        Block b = s.getBlock();
        if (b instanceof ProxyBlock || ProxyRegistry.getBlockId(b) != 0) return null;
        if (TickPolicy.isFcGrowthGoverned(s)) return null;
        return kindOf(s);
    }

    /** True for a governed modded plant, wherever it stands. */
    public static boolean isGovernedPlant(@Nullable BlockState s) {
        return governedKind(s) != null;
    }

    /** {@link #isGovernedPlant} of {@code b}'s default state. */
    public static boolean isGovernedPlantBlock(@Nullable Block b) {
        return b != null && isGovernedPlant(b.defaultBlockState());
    }

    // ================================================================
    // Soil
    // ================================================================

    /**
     * The FC world over {@code level}: the server's WorldBridge, or on the client's render thread the read-only
     * ClientFcWorld. Null for any other reader.
     */
    public static @Nullable btw.modern.World fcWorld(@Nullable BlockGetter level) {
        if (level instanceof ServerLevel sl) return WorldBridge.getOrCreate(sl);
        if (level instanceof Level l && l.isClientSide()) {
            return DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> btw.forge.client.FcSoilClient.fcWorld(level));
        }
        return null;
    }

    /**
     * The FC block FC sees below the plant cell {@code plantPos}, for the kind of the plant now there (a crop's soil
     * when the cell is empty). Null when there is none.
     */
    public static @Nullable btw.modern.Block soilUnder(BlockGetter level, BlockPos plantPos) {
        Kind kind;
        try {
            kind = kindOf(level.getBlockState(plantPos));
        } catch (RuntimeException e) {
            failure("soilUnder", e);
            return null;
        }
        return soilUnder(level, plantPos, kind == null ? Kind.CROP : kind);
    }

    /**
     * The FC block FC sees below {@code plantPos} for a plant of {@code kind}. Crops: the soil's identity id (every
     * soil that takes crops is FC-mapped: farmland 60, 1087, 1088, planters), which never re-enters
     * ModernBlockStandIn.idFor. Other kinds: FC's world id, so a modern soil answers through its stand-in tags.
     */
    public static @Nullable btw.modern.Block soilUnder(BlockGetter level, BlockPos plantPos, Kind kind) {
        try {
            BlockPos below = plantPos.below();
            BlockState soil = level.getBlockState(below);
            int id = kind == Kind.CROP ? ProxyRegistry.getBlockId(soil.getBlock())
                    : ProxyRegistry.getWorldBlockId(soil, level, below);
            return id > 0 && id < btw.modern.Block.blocksList.length ? btw.modern.Block.blocksList[id] : null;
        } catch (RuntimeException e) {
            failure("soilUnder", e);
            return null;
        }
    }

    /** True when the FC soil below {@code plantPos} answers the FC soil hook of {@code plant}'s kind. */
    public static boolean isOnAcceptingBtwSoil(BlockGetter level, BlockPos plantPos, BlockState plant) {
        Kind kind = kindOf(plant);
        return kind != null && soilAccepts(level, plantPos, kind);
    }

    /** True when the FC soil below {@code plantPos} answers the FC soil hook of {@code kind}. */
    public static boolean soilAccepts(BlockGetter level, BlockPos plantPos, Kind kind) {
        if (level == null || !BTWLifecycle.isFcReady()) return false;
        try {
            btw.modern.World w = fcWorld(level);
            if (w == null) return false;
            btw.modern.Block soil = soilUnder(level, plantPos, kind);
            if (soil == null) return false;
            int x = plantPos.getX();
            int y = plantPos.getY() - 1;
            int z = plantPos.getZ();
            return switch (kind) {
                case CROP -> soil.CanDomesticatedCropsGrowOnBlock(w, x, y, z);
                case SAPLING -> soil.CanSaplingsGrowOnBlock(w, x, y, z);
                case WILD -> soil.CanWildVegetationGrowOnBlock(w, x, y, z);
            };
        } catch (RuntimeException | LinkageError e) {
            failure("soil hook", e);
            return false;
        }
    }

    /** True when {@code state} at {@code pos} is a governed modded plant on BTW soil that takes it. */
    public static boolean governsAt(BlockGetter level, BlockPos pos, BlockState state) {
        return governedKind(state) != null && isOnAcceptingBtwSoil(level, pos, state);
    }

    /**
     * True when the block at {@code pos} is FC's loose dirt (FCBetterThanWolves.fcBlockDirtLoose), what FC farmland
     * untills to when the plant above is removed (FCBlockFarmland.NotifyOfPlantAboveRemoved).
     */
    public static boolean isUntilledLooseDirt(BlockGetter level, BlockPos pos) {
        if (level == null || !BTWLifecycle.isFcReady()) return false;
        try {
            int id = ProxyRegistry.getBlockId(level.getBlockState(pos).getBlock());
            return id > 0 && id == looseDirtId();
        } catch (RuntimeException e) {
            failure("isUntilledLooseDirt", e);
            return false;
        }
    }

    /** fcBlockDirtLoose.blockID, read once by reflection (FC classes are not on the bridge's compile path). */
    private static int looseDirtId() {
        int id = looseDirtId;
        if (id > 0) return id;
        try {
            Object block = Class.forName("net.minecraft.src.btw.core.FCBetterThanWolves")
                    .getField("fcBlockDirtLoose").get(null);
            if (block instanceof btw.modern.Block fc && fc.blockID > 0) {
                looseDirtId = fc.blockID;
                return fc.blockID;
            }
            throw new IllegalStateException("fcBlockDirtLoose is " + block);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!looseDirtWarned) {
                looseDirtWarned = true;
                LOGGER.warn("[MODDED-PLANTS] could not read FCBetterThanWolves.fcBlockDirtLoose ({}); "
                        + "using legacy id {}", e.toString(), DEFAULT_LOOSE_DIRT_ID);
            }
            looseDirtId = DEFAULT_LOOSE_DIRT_ID;
            return DEFAULT_LOOSE_DIRT_ID;
        }
    }

    /** One WARN per stage and exception class; the caller answers false. */
    private static void failure(String stage, Throwable e) {
        if (FAILURES_LOGGED.add(stage + '|' + e.getClass().getName())) {
            LOGGER.warn("[MODDED-PLANTS] FC {} failed: {}; answered false (logged once)", stage, e.toString(), e);
        }
    }
}
