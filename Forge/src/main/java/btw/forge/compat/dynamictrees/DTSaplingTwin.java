package btw.forge.compat.dynamictrees;

import btw.forge.TwinRegistry;
import btw.forge.climate.SeasonGate;
import com.ferreusveritas.dynamictrees.block.DynamicSaplingBlock;
import com.ferreusveritas.dynamictrees.tree.species.NetherFungusSpecies;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dynamic Trees saplings on FC's rules (INTEGRATIONS #027), as a {@link TwinRegistry.ModdedSapling} kind. A
 * {@code DynamicSaplingBlock} (DT's and dtphc2's) is an FC sapling twin: FCBlockSapling.updateTick applies FC's
 * survival, the End ban, light 9 above and four persisted 1/64 stages, then {@link #growTree} grows DT's own tree. DT's
 * Nether fungi (crimson, warped, mega crimson, mega warped: {@link NetherFungusSpecies}) are FC fungus twins instead,
 * on FC's mushroom rule as vanilla crimson and warped fungi are: 1 in 50 random ticks, never in the End. DT never grows
 * its fungi from random ticks (NetherFungusSpecies.java:63, gated at DynamicSaplingBlock.java:82), so the fungus twin
 * adds BTW's natural growth rather than slowing an existing one.
 */
// FCMOD-INTEGRATION(1.20.1): dynamic_trees: DT saplings grow by FC's sapling rule and DT Nether fungi by FC's mushroom rule, each into DT's own tree
final class DTSaplingTwin implements TwinRegistry.ModdedSapling {

    /** Every DynamicSaplingBlock. Pure (reads only the block) and independent of the switch-off. */
    @Override
    public boolean isSapling(Block block) {
        try {
            return block instanceof DynamicSaplingBlock;
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the sapling twin predicate", t);
            return false;
        }
    }

    /** A DynamicSaplingBlock of a Nether fungus species. Pure and independent of the switch-off. */
    @Override
    public boolean isFungus(Block block) {
        try {
            return block instanceof DynamicSaplingBlock sb && sb.getSpecies() instanceof NetherFungusSpecies;
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the fungus twin predicate", t);
            return false;
        }
    }

    /**
     * FC's rule decided (a sapling's last 1/64 stage, or a fungus' 1-in-50 roll): grow the DT sapling at {@code pos}
     * into DT's own tree, through Species.canSaplingGrow and Species.transitionToTree only, never
     * DynamicSaplingBlock.tick or randomTick (gated on canSaplingGrowNaturally, false for every Nether fungus).
     * <ul>
     *   <li>A sapling that cannot stay drops its seed and is removed (as DynamicSaplingBlock.dropBlock).</li>
     *   <li>A sapling whose species a tree pack set to {@code sapling_grows_naturally false} stays: BTW's rule paces
     *       natural growth, it does not create it. Fungi skip that gate; their natural growth is FC's roll.</li>
     *   <li>SeasonGate, keyed by the seed item: DESTROY removes the sapling with no drops, DENY leaves it (a sapling
     *       twin keeps stage 3 and retries).</li>
     * </ul>
     * Switched off, DT's own body runs instead (performBonemeal, gated as DT gates its natural growth for saplings,
     * ungated for fungi), so DT saplings and fungi still grow at FC's pace.
     */
    // FCMOD-INTEGRATION(1.20.1): dynamic_trees: once FC's sapling or mushroom rule decided, the DT sapling becomes DT's own tree, if the season allows
    @Override
    public void growTree(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        try {
            if (!(state.getBlock() instanceof DynamicSaplingBlock sb)) return;
            boolean fungus = isFungus(sb);
            if (!DynamicTreesCompat.active) {
                if (fungus || sb.getSpecies().canSaplingGrowNaturally(level, pos)) {
                    sb.performBonemeal(level, random, pos, state);
                }
                return;
            }
            if (!state.canSurvive(level, pos)) {
                Block.dropResources(state, level, pos);
                level.removeBlock(pos, false);
                DynamicTreesCompat.trace("sapling at {}: cannot stay, dropped", pos);
                return;
            }
            Species sp = sb.getSpecies().selfOrLocationOverride(level, pos);
            if (!sp.canSaplingGrow(level, pos)) return;
            if (!fungus && !sp.canSaplingGrowNaturally(level, pos)) return;
            ItemStack seed = sp.getSeedStack(1);
            if (!seed.isEmpty()) {
                SeasonGate.Verdict verdict = SeasonGate.growthVerdict(level, pos, state,
                        BuiltInRegistries.ITEM.getKey(seed.getItem()));
                DynamicTreesCompat.trace("sapling at {}: season verdict {}", pos, verdict);
                if (verdict == SeasonGate.Verdict.DESTROY) {
                    level.destroyBlock(pos, false);
                    return;
                }
                if (verdict == SeasonGate.Verdict.DENY) return;
            }
            DTGrowthHandler.GROWING.set(Boolean.TRUE);
            try {
                boolean grown = sp.transitionToTree(level, pos);
                DynamicTreesCompat.trace("sapling at {}: {} ({})", pos, grown ? "grew" : "did not fit",
                        fungus ? "fungus" : "sapling");
            } finally {
                DTGrowthHandler.GROWING.remove();
            }
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the sapling twin's growTree", t);
        }
    }

    @Override
    public String toString() {
        return "Dynamic Trees saplings (#027)";
    }
}
