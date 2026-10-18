package btw.forge.compat.dynamictrees;

import btw.forge.BoneMealPolicy;
import btw.forge.TwinRegistry;
import com.ferreusveritas.dynamictrees.api.TreeHelper;
import com.ferreusveritas.dynamictrees.api.event.TransitionSaplingToTreeEvent;
import com.ferreusveritas.dynamictrees.block.DynamicSaplingBlock;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Growth on living Dynamic Trees under BTW's rules (INTEGRATIONS #027): a DT sapling twin becomes a tree only through
 * FC's sapling rule ({@link DTSaplingTwin}), and bone meal on a tree is 4.B fertilizer (+2 rooty fertility through
 * FC's fertilizer path, nothing at 15), never DT's growth pulse. BoneMealPolicy keeps the only BonemealEvent
 * listener; this class adds none.
 */
// FCMOD-INTEGRATION(1.20.1): dynamic_trees: DT saplings become trees only by FC's sapling rule; bone meal on a DT tree is FC fertilizer for its rooty soil
public final class DTGrowthHandler {

    /** Set while BTW's own twin growth ({@link DTSaplingTwin#growTree}) transitions a DT sapling. */
    static final ThreadLocal<Boolean> GROWING = new ThreadLocal<>();

    /**
     * Server. Cancels any transition of a twin DT sapling that BTW did not start (direct performBonemeal callers, DT's
     * own tick, which TickPolicy already redirects for twins). A non-DT block at the position (DT's
     * replaceVanillaSapling path removes the vanilla sapling first) and a DT sapling with no FC twin built keep DT's
     * own rule.
     */
    // FCMOD-INTEGRATION(1.20.1): dynamic_trees: a twin DT sapling grows into a tree only when FC's sapling rule decided it
    @SubscribeEvent
    public void onTransition(TransitionSaplingToTreeEvent e) {
        if (!DynamicTreesCompat.active) return;
        try {
            Level level = e.getLevel();
            if (level == null || level.isClientSide()) return;
            BlockPos pos = e.getPos();
            BlockState s = level.getBlockState(pos);
            if (!(s.getBlock() instanceof DynamicSaplingBlock)) return;
            if (Boolean.TRUE.equals(GROWING.get())) return;
            if (!TwinRegistry.isTwinState(s)) return;
            e.setCanceled(true);
            DynamicTreesCompat.trace("sapling at {}: transition outside FC's sapling rule cancelled", pos);
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the sapling transition guard", t);
        }
    }

    /**
     * Both sides, both hands. Bone meal on a branch, rooty soil or trunk shell never runs the block's {@code use()}
     * (DT's growth pulse). Server: the block use is denied, so BoneMealItem.useOn reaches BoneMealPolicy, which routes
     * the hooked state to FC's fertilizer ({@link DTBlockHooks#applyFertilizer}): used up when the soil took it, kept
     * at fertility 15. Client: the swing is predicted from the root's fertility (the use packet is still sent; the
     * server decides).
     */
    // FCMOD-INTEGRATION(1.20.1): dynamic_trees: bone meal on a DT tree is FC fertilizer for its rooty soil, from either hand, never DT's growth pulse
    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (!DynamicTreesCompat.active) return;
        try {
            if (!BoneMealPolicy.isFertilizer(e.getItemStack())) return;
            Level level = e.getLevel();
            BlockPos pos = e.getPos();
            BlockState s = level.getBlockState(pos);
            if (!DTTrees.isTreeUseTarget(s)) return;
            if (!level.isClientSide()) {
                e.setUseBlock(Event.Result.DENY);
                return;
            }
            BlockPos root = TreeHelper.findRootNode(level, pos);
            RootyBlock rooty = root.equals(BlockPos.ZERO) ? null : TreeHelper.getRooty(level.getBlockState(root));
            boolean ok = rooty != null && rooty.getFertility(level.getBlockState(root), level, root) < 15;
            e.setCanceled(true);
            e.setCancellationResult(ok ? InteractionResult.SUCCESS : InteractionResult.PASS);
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the bone-meal use handler", t);
        }
    }

    /**
     * FC fertilizer on the DT tree at {@code pos}: +2 fertility on its rooty soil (RootyBlock.fertilize, which
     * answers false at 15, RootyBlock.java:387-394). No growth pulse. False when no root is found. Public only for
     * package use ({@link DTBlockHooks}).
     */
    public static boolean applyFertilizer(ServerLevel level, BlockPos pos) {
        BlockPos root = TreeHelper.findRootNode(level, pos);
        if (root.equals(BlockPos.ZERO)) return false;
        RootyBlock rooty = TreeHelper.getRooty(level.getBlockState(root));
        return rooty != null && rooty.fertilize(level, root, 2);
    }
}
