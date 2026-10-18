package btw.forge.compat.dynamictrees;

import btw.forge.FCLogRules;
import btw.forge.ItemStackHelper;
import btw.forge.ModernBlockHooks;
import com.ferreusveritas.dynamictrees.block.GrowableBlock;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.entity.FallingTreeEntity;
import com.ferreusveritas.dynamictrees.util.BranchDestructionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * FC's machines on Dynamic Trees (INTEGRATIONS #027), reached through ModernBlockStandIn's OnBlockSawed,
 * GetDoesStumpRemoverWorkOnBlock and AttemptToApplyFertilizerTo for the states {@link DTTrees#isHookTarget} claims:
 * the powered saw fells a full-width trunk with FC's log saw output for the cut block, the stump remover clears a
 * dead stump, and bone meal fertilizes the tree's rooty soil (pods and fruit take none, as FC cocoa).
 */
// FCMOD-INTEGRATION(1.20.1): dynamic_trees: FC's saw, stump remover and fertilizer act on Dynamic Trees through the FC machine hooks
final class DTBlockHooks implements ModernBlockHooks.Hook {

    /** 1.5.2 Block.planks; its metadata 0-3 is FC's wood species. */
    private static final int LEGACY_PLANKS_ID = 5;

    /**
     * FCBlockSaw cut the block at {@code pos}. A trunk of radius 8 or more (a trunk shell resolves to its core) is
     * felled away from the saw: the cut block gives FC's log saw output (FCBlockSaw.java:618-639: 4 planks of the
     * wood, 2 sawdust, 1 bark if unstripped) and leaves the payload one log short; the rest falls as a DT harvest. A
     * species FC never had gives 2 sawdust and keeps its log. Young trunks keep growing.
     */
    // FCMOD-INTEGRATION(1.20.1): dynamic_trees: the FC saw fells a full-width DT trunk, its cut block sawn into FC's log output
    @Override
    public boolean onSawed(ServerLevel level, BlockPos pos, BlockState state, Direction sawFacing) {
        if (!DynamicTreesCompat.active) return false;
        try {
            BlockPos core = DTTrees.resolveCore(level, pos);
            BlockState cs = level.getBlockState(core);
            if (!DTTrees.isLog(cs)) return false;
            BranchBlock b = (BranchBlock) cs.getBlock();
            if (b.getRadius(cs) < 8) return false;
            int meta = DTTrees.fcWoodMeta(b);
            boolean stripped = b.isStrippedBranch();
            BranchDestructionData d = b.destroyBranchFromNode(level, core, sawFacing.getOpposite(), false, null);
            List<ItemStack> drops = new ArrayList<>(d.species.getBranchesDrops(level, d.woodVolume));
            if (meta >= 0) {
                for (Iterator<ItemStack> it = drops.iterator(); it.hasNext(); ) {
                    ItemStack st = it.next();
                    if (st.is(ItemTags.LOGS)) {
                        st.shrink(1);
                        if (st.isEmpty()) it.remove();
                        break;
                    }
                }
                pop(level, core, ItemStackHelper.fcIdToMcStack(LEGACY_PLANKS_ID, 4, meta));
            }
            FallingTreeEntity.dropTree(level, d, drops, FallingTreeEntity.DestroyType.HARVEST);
            pop(level, core, FCLogRules.sawdust(2));
            if (!stripped) pop(level, core, FCLogRules.bark(meta));
            DynamicTreesCompat.trace("saw cut at {} (wood {}, stripped {}): payload {}", core, meta, stripped, drops);
            return true;
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the FC saw hook", t);
            return false;
        }
    }

    /**
     * FCItemStumpRemover works on a dead DT stump only (declared deviation): FC then writes air and DT's sloppy break
     * removes the lone stub with no drops. A living stump is refused, because air there would collapse the whole tree
     * with no drops. On a trunk shell of a thick stump the core goes first: FC writes air only at the clicked shell,
     * which the core would place again at once (ThickBranchBlock.neighborChanged, updateTrunkShells); the orphaned
     * shells then drop out on their scheduled tick (TrunkShellBlock.tick).
     */
    // FCMOD-INTEGRATION(1.20.1): dynamic_trees: the FC stump remover clears a dead DT stump, from its core or any trunk shell
    @Override
    public boolean stumpRemoverWorks(ServerLevel level, BlockPos pos, BlockState state) {
        if (!DynamicTreesCompat.active) return false;
        try {
            BlockPos core = DTTrees.resolveCore(level, pos);
            if (!DTTrees.isLog(level.getBlockState(core)) || !DTTrees.isDeadStump(level, core)) return false;
            if (!core.equals(pos)) level.setBlock(core, Blocks.AIR.defaultBlockState(), 3);
            return true;
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the FC stump remover hook", t);
            return false;
        }
    }

    /**
     * FCItemDye fertilizer (bone meal, from either hand or a machine through BoneMealPolicy): +2 fertility on the
     * tree's rooty soil, false at 15 or with no root. Pods and fruit ({@link GrowableBlock}) take none, as FC cocoa.
     */
    // FCMOD-INTEGRATION(1.20.1): dynamic_trees: bone meal on a DT tree fertilizes its rooty soil; DT pods and fruit take none, as FC cocoa
    @Override
    public boolean applyFertilizer(ServerLevel level, BlockPos pos, BlockState state) {
        if (!DynamicTreesCompat.active) return false;
        try {
            if (state.getBlock() instanceof GrowableBlock) return false;
            return DTGrowthHandler.applyFertilizer(level, pos);
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the FC fertilizer hook", t);
            return false;
        }
    }

    private static void pop(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (!stack.isEmpty()) Block.popResource(level, pos, stack);
    }

    @Override
    public String toString() {
        return "Dynamic Trees (#027)";
    }
}
