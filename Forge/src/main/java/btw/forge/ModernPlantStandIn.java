package btw.forge;

import btw.forge.farming.FcSoil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;

/**
 * What FC sees, through WorldBridge.getBlockId, for a governed modded crop on BTW soil that takes it
 * ({@link FcSoil#governsAt}: a CropBlock of another mod on FC farmland or a planter; maintainer decision #11,
 * INTEGRATIONS #026). Before, such a crop read as air to FC (no collision, no 1.5.2 id), so FC farmland treated the
 * cell as empty: weeds sprouted over the crop at night, FC animals could not graze it, FC fire ignored it and legacy
 * farmland never converted under it.
 *
 * <p>The stand-in is the FC crop as FC's soil and neighbours read it (Server FCBlockCrops / FCBlockPlants): no
 * collision, not opaque; weeds share its cell ({@code CanWeedsGrowInBlock}), and its weeds, weed removal and
 * fertilizer are the soil's below; animals graze it (the live crop's loot drops, then the soil hears it, as 1.5.2
 * Block.OnGrazed); FC fire burns it at FC's crop odds ({@code FCEnumFlammability.CROPS}); it converts legacy soil. Its
 * own drops are the live crop's 1.20.1 loot. FC can never write it: WorldBridge.setBlock of a stand-in id is a no-op
 * (no 1.20.1 block maps to it).</p>
 *
 * <p>One id from the free legacy range {@link ModernBlockStandIn} manages, allocated right after the plain stand-ins
 * and only when the pack has a modded crop ({@link #isNeeded}); plain BTW allocates nothing. The live crop keeps its
 * own growth (ModdedPlantRules' CropGrowEvent rule), placement, survival and rendering.</p>
 */
// FCMOD-INTEGRATION(1.20.1): modded_plant_rules: FC's soil, animals and fire see a governed modded crop on BTW soil as an FC crop instead of air
public final class ModernPlantStandIn extends btw.modern.Block {

    private static final Logger LOGGER = LogManager.getLogger("BTW-StandIn");

    /** Null until decided (needs FC and the block registry): does the pack hold a modded crop? */
    private static volatile Boolean needed;

    ModernPlantStandIn(int id) {
        super(id, btw.modern.Material.plants);   // registers blocksList[id] (+ an ItemBlock, never exposed)
        setHardness(0F);
        setStepSound(soundGrassFootstep);
        SetFireProperties(btw.modern.FCEnumFlammability.CROPS);   // FCBlockCrops' fire odds
        setUnlocalizedName("btwModernPlant");
        opaqueCubeLookup[id] = false;
    }

    /**
     * True when the block registry holds a modded crop BTW can govern: a CropBlock that is not a ProxyBlock, has no FC
     * identity id and is no twin (beetroots and the torchflower crop are). Decided once FC is ready; false before.
     */
    static boolean isNeeded() {
        Boolean n = needed;
        if (n != null) return n;
        if (!BTWLifecycle.isFcReady()) return false;
        boolean any = false;
        try {
            for (net.minecraft.world.level.block.Block b : ForgeRegistries.BLOCKS) {
                if (b instanceof CropBlock && !(b instanceof ProxyBlock) && ProxyRegistry.getBlockId(b) == 0
                        && !TwinRegistry.governsBlock(b)) {
                    any = true;
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[STAND-IN] modded crop scan failed; no plant stand-in", e);
        }
        needed = any;
        return any;
    }

    /** The plant stand-in's legacy id (allocating the stand-ins if they are not yet), 0 when there is none. */
    public static int id() {
        return ModernBlockStandIn.plantStandInId();
    }

    /** The plant stand-in FC block, or null when there is none (the plantBlock argument of FC's soil hooks). */
    public static btw.modern.Block instance() {
        int id = ModernBlockStandIn.plantId();
        return id > 0 ? btw.modern.Block.blocksList[id] : null;
    }

    private static ServerLevel levelOf(Object access) {
        return access instanceof WorldBridge wb ? wb.getServerLevel() : null;
    }

    /** The FC block FC sees below (i, j, k) (FCBlockPlants' relays read the same cell), or null. */
    private btw.modern.Block soilBelow(btw.modern.IBlockAccess access, int i, int j, int k) {
        int id = access.getBlockId(i, j - 1, k);
        return id > 0 && id < blocksList.length && id != blockID ? blocksList[id] : null;
    }

    // ---- FCBlockPlants: no collision, not a cube
    @Override public boolean isOpaqueCube() { return false; }
    @Override public boolean renderAsNormalBlock() { return false; }

    @Override
    public btw.modern.AxisAlignedBB getCollisionBoundingBoxFromPool(btw.modern.World world, int i, int j, int k) {
        return null;
    }

    // ---- weeds (Common FCBlockFarmland.CanWeedsShareSpaceWithBlockAt, Server FCBlockPlants:104-130)
    @Override
    public boolean CanWeedsGrowInBlock(btw.modern.IBlockAccess access, int i, int j, int k) {
        return true;
    }

    @Override
    public int GetWeedsGrowthLevel(btw.modern.IBlockAccess access, int i, int j, int k) {
        btw.modern.Block soil = soilBelow(access, i, j, k);
        return soil != null ? soil.GetWeedsGrowthLevel(access, i, j - 1, k) : 0;
    }

    @Override
    public void RemoveWeeds(btw.modern.World world, int i, int j, int k) {
        btw.modern.Block soil = soilBelow(world, i, j, k);
        if (soil != null) soil.RemoveWeeds(world, i, j - 1, k);
    }

    // ---- fertilizer relays to the soil (FCBlockPlants.AttemptToApplyFertilizerTo, Server FCBlockPlants:132-145)
    @Override
    public boolean AttemptToApplyFertilizerTo(btw.modern.World world, int i, int j, int k) {
        btw.modern.Block soil = soilBelow(world, i, j, k);
        return soil != null && soil.AttemptToApplyFertilizerTo(world, i, j - 1, k);
    }

    // ---- grazing (Server FCBlockCrops.CanBeGrazedOn / OnGrazed, 1.5.2 Block.OnGrazed)
    @Override
    public boolean CanBeGrazedOn(btw.modern.IBlockAccess access, int i, int j, int k, btw.modern.EntityAnimal animal) {
        return true;
    }

    /**
     * FCBlockCrops.OnGrazed: the crop's drops (the live crop's loot, rolled while the soil below is intact, so the
     * immature-crop rule sees FC farmland), then 1.5.2 Block.OnGrazed: the crop is removed (whose neighbour update
     * untills FC farmland, as FC's breakBlock does) and the soil below hears the grazing.
     */
    @Override
    public void OnGrazed(btw.modern.World world, int i, int j, int k, btw.modern.EntityAnimal animal) {
        ServerLevel level = levelOf(world);
        if (level == null) return;
        BlockPos pos = new BlockPos(i, j, k);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        net.minecraft.world.level.block.Block.dropResources(state, level, pos, level.getBlockEntity(pos));
        level.removeBlock(pos, false);
        int below = world.getBlockId(i, j - 1, k);
        btw.modern.Block soil = below > 0 && below < blocksList.length ? blocksList[below] : null;
        if (soil != null) soil.OnVegetationAboveGrazed(world, i, j - 1, k, animal);
    }

    // ---- legacy farmland converts under a crop (FCBlockFarmlandLegacyBase, Server FCBlockCrops.GetConvertsLegacySoil)
    @Override
    public boolean GetConvertsLegacySoil(btw.modern.IBlockAccess access, int i, int j, int k) {
        return true;
    }

    // ---- drops: the live crop's 1.20.1 loot, never an FC stack for a phantom id
    @Override public int idDropped(int metadata, Random random, int fortune) { return 0; }

    @Override
    public void dropBlockAsItemWithChance(btw.modern.World world, int x, int y, int z, int metadata, float chance,
                                          int fortune) {
        if (chance < 1.0F && world != null && world.rand != null && world.rand.nextFloat() > chance) return;
        ModernBlockStandIn.dropMcLoot(world, x, y, z);
    }
}
