package btw.forge.compat.dynamictrees;

import btw.forge.FCLogRules;
import com.ferreusveritas.dynamictrees.api.TreeHelper;
import com.ferreusveritas.dynamictrees.api.event.TransitionSaplingToTreeEvent;
import com.ferreusveritas.dynamictrees.block.DynamicSaplingBlock;
import com.ferreusveritas.dynamictrees.block.GrowableBlock;
import com.ferreusveritas.dynamictrees.block.branch.BasicRootsBlock;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.branch.TrunkShellBlock;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import com.ferreusveritas.dynamictrees.entity.FallingTreeEntity;
import com.ferreusveritas.dynamictrees.init.DTConfigs;
import com.ferreusveritas.dynamictrees.systems.nodemapper.NetVolumeNode;
import com.ferreusveritas.dynamictrees.tree.family.Family;
import com.ferreusveritas.dynamictrees.tree.species.NetherFungusSpecies;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import com.ferreusveritas.dynamictrees.util.BranchDestructionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.Cancelable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The tree facts the Dynamic Trees integration (INTEGRATIONS #027) decides by: which DT blocks are logs, stumps and
 * dead stumps, which trunk a trunk shell belongs to, FC's wood type of a DT family and the bark, work stump and saw
 * planks the shared log rule (FCLogRules) gives it, the item each FC chew stage pops, and the dig factor of a chewed
 * log. Also the init-time API probe. Package-private static helpers; every DT type stays inside this package.
 */
// FCMOD-INTEGRATION(1.20.1): dynamic_trees: one place that names Dynamic Trees' trunks, stumps and shells in BTW's log terms
final class DTTrees {

    private DTTrees() {}

    // ------------------------------------------------------------------ API probe (SR1)

    /**
     * Resolves by reflection every Dynamic Trees member the integration calls, with its exact parameter, return and
     * field types (the JVM links by full descriptor). A DT override of a vanilla method is probed as that vanilla
     * supertype, never by name: production jars carry it under its SRG name. Throws IllegalStateException naming the
     * first one that is missing or changed, so {@link DynamicTreesCompat#init} registers nothing on an incompatible
     * DT version. Reflection neither initializes DT classes nor touches any registry.
     */
    static void probe() {
        try {
            staticMethod(TreeHelper.class, boolean.class, "isBranch", BlockState.class);
            staticMethod(TreeHelper.class, boolean.class, "isRooty", BlockState.class);
            staticMethod(TreeHelper.class, RootyBlock.class, "getRooty", BlockState.class);
            staticMethod(TreeHelper.class, BlockPos.class, "dereferenceTrunkShell", Level.class, BlockPos.class);
            staticMethod(TreeHelper.class, BlockPos.class, "findRootNode", Level.class, BlockPos.class);

            method(BranchBlock.class, int.class, "getRadius", BlockState.class);
            method(BranchBlock.class, int.class, "setRadius", LevelAccessor.class, BlockPos.class, int.class,
                    Direction.class, int.class);
            method(BranchBlock.class, void.class, "stripBranch", BlockState.class, LevelAccessor.class,
                    BlockPos.class);
            method(BranchBlock.class, boolean.class, "isStrippedBranch");
            method(BranchBlock.class, Family.class, "getFamily");
            method(BranchBlock.class, void.class, "setBlockStateIgnored", Level.class, BlockPos.class,
                    BlockState.class, int.class);
            method(BranchBlock.class, BranchDestructionData.class, "destroyBranchFromNode", Level.class,
                    BlockPos.class, Direction.class, boolean.class, LivingEntity.class);
            subtype(BasicRootsBlock.class, BranchBlock.class);
            subtype(TrunkShellBlock.class, Block.class);

            method(RootyBlock.class, boolean.class, "fertilize", Level.class, BlockPos.class, int.class);
            method(RootyBlock.class, int.class, "getFertility", BlockState.class, BlockGetter.class, BlockPos.class);

            subtype(DynamicSaplingBlock.class, Block.class);
            method(DynamicSaplingBlock.class, Species.class, "getSpecies");
            // performBonemeal overrides vanilla's BonemealableBlock method, so the release DT jars carry it only under
            // its SRG name (m_214148_): probed as the vanilla interface, which the reobfuscated call links through.
            subtype(DynamicSaplingBlock.class, BonemealableBlock.class);
            if (!GrowableBlock.class.isInterface()) throw changed("GrowableBlock is no longer an interface");

            method(Family.class, boolean.class, "hasStrippedBranch");
            method(Family.class, int.class, "getMinRadiusForStripping");
            method(Family.class, boolean.class, "canStripBranch", BlockState.class, Level.class, BlockPos.class,
                    Player.class, ItemStack.class);
            method(Family.class, Optional.class, "getPrimitiveLog");
            method(Family.class, float.class, "getLootVolumeMultiplier");
            field(DTConfigs.class, ForgeConfigSpec.DoubleValue.class, "TREE_HARVEST_MULTIPLIER", true);

            method(Species.class, Family.class, "getFamily");
            method(Species.class, Species.class, "selfOrLocationOverride", BlockGetter.class, BlockPos.class);
            method(Species.class, boolean.class, "canSaplingGrow", LevelReader.class, BlockPos.class);
            method(Species.class, boolean.class, "canSaplingGrowNaturally", Level.class, BlockPos.class);
            method(Species.class, boolean.class, "transitionToTree", Level.class, BlockPos.class);
            method(Species.class, ItemStack.class, "getSeedStack", int.class);
            method(Species.class, List.class, "getBranchesDrops", Level.class, NetVolumeNode.Volume.class);
            subtype(NetherFungusSpecies.class, Species.class);

            field(FallingTreeEntity.class, FallingTreeEntity.DestroyType.class, "destroyType", false);
            method(FallingTreeEntity.class, BranchDestructionData.class, "getDestroyData");
            method(FallingTreeEntity.class, List.class, "getPayload");
            staticMethod(FallingTreeEntity.class, FallingTreeEntity.class, "dropTree", Level.class,
                    BranchDestructionData.class, List.class, FallingTreeEntity.DestroyType.class);
            field(FallingTreeEntity.DestroyType.class, FallingTreeEntity.DestroyType.class, "HARVEST", true);

            field(BranchDestructionData.class, Species.class, "species", false);
            field(BranchDestructionData.class, NetVolumeNode.Volume.class, "woodVolume", false);
            field(BranchDestructionData.class, BlockPos.class, "cutPos", false);
            method(NetVolumeNode.Volume.class, float.class, "getVolume");

            method(TransitionSaplingToTreeEvent.class, Level.class, "getLevel");
            method(TransitionSaplingToTreeEvent.class, BlockPos.class, "getPos");
            if (!TransitionSaplingToTreeEvent.class.isAnnotationPresent(Cancelable.class)) {
                throw changed("TransitionSaplingToTreeEvent is no longer @Cancelable");
            }
        } catch (LinkageError e) {
            throw new IllegalStateException("Dynamic Trees API class missing: " + e.getMessage(), e);
        }
    }

    private static void method(Class<?> owner, Class<?> returns, String name, Class<?>... params) {
        member(owner, returns, name, false, params);
    }

    private static void staticMethod(Class<?> owner, Class<?> returns, String name, Class<?>... params) {
        member(owner, returns, name, true, params);
    }

    private static void member(Class<?> owner, Class<?> returns, String name, boolean isStatic, Class<?>... params) {
        String what = owner.getName() + "." + name + Arrays.toString(params);
        Method m;
        try {
            m = owner.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw changed("no method " + what);
        }
        if (m.getReturnType() != returns) throw changed(what + " returns " + m.getReturnType().getName());
        if (Modifier.isStatic(m.getModifiers()) != isStatic) throw changed(what + " changed static-ness");
    }

    private static void field(Class<?> owner, Class<?> type, String name, boolean isStatic) {
        String what = owner.getName() + "." + name;
        Field f;
        try {
            f = owner.getField(name);
        } catch (NoSuchFieldException e) {
            throw changed("no field " + what);
        }
        if (f.getType() != type) throw changed(what + " is now " + f.getType().getName());
        if (Modifier.isStatic(f.getModifiers()) != isStatic) throw changed(what + " changed static-ness");
    }

    private static void subtype(Class<?> child, Class<?> parent) {
        if (!parent.isAssignableFrom(child)) throw changed(child.getName() + " no longer extends " + parent.getName());
    }

    private static IllegalStateException changed(String what) {
        return new IllegalStateException("Dynamic Trees API member missing or changed: " + what);
    }

    // ------------------------------------------------------------------ tree facts

    /** The trunk a trunk shell at {@code pos} belongs to (its muse), or {@code pos} itself for any other block. */
    static BlockPos resolveCore(Level level, BlockPos pos) {
        return TreeHelper.dereferenceTrunkShell(level, pos);
    }

    /** A DT branch that is a log: any BranchBlock except roots (mangrove roots are not logs). */
    static boolean isLog(BlockState s) {
        return TreeHelper.isBranch(s) && !(s.getBlock() instanceof BasicRootsBlock);
    }

    /**
     * The stump authority: the branch at {@code core} stands on rooty soil. FC's GetIsStump reads world id 17 and
     * never sees a DT stump.
     */
    static boolean isStump(BlockGetter level, BlockPos core) {
        return TreeHelper.isRooty(level.getBlockState(core.below()));
    }

    /** A stump with no branch above it: the stub left after a fell. */
    static boolean isDeadStump(BlockGetter level, BlockPos core) {
        return isStump(level, core) && !TreeHelper.isBranch(level.getBlockState(core.above()));
    }

    /**
     * FC's wood type of a DT branch, from its family's unstripped primitive log ({@link FCLogRules#fcWoodMeta}), so a
     * stripped branch still names its wood: 0-3 for FC's oak, spruce, birch and jungle, 5-8 for acacia, dark oak,
     * mangrove and cherry (ModernWoods; the DT dark oak family is wood type 6), -1 for a wood FC has no type for
     * (the fungus families, modded families).
     */
    // FCMOD-INTEGRATION(1.20.1): dynamic_trees: DT families of acacia, dark oak, mangrove and cherry are FC wood types 5-8 (ModernWoods), as their vanilla logs
    static int fcWoodMeta(BranchBlock b) {
        return b.getFamily().getPrimitiveLog().map(FCLogRules::fcWoodMeta).orElse(-1);
    }

    /**
     * The bark a DT branch of this family pays, by the shared log rule ({@link FCLogRules#barkSpecies} of its
     * family's primitive log): FC's wood type, else its {@code betterthanwolves:bark/*} tag, -1 for {@code bark/none}
     * (the crimson and warped fungi, maintainer ruling 2), else oak (logs design decision 6 (a)). Oak for a family
     * with no primitive log.
     */
    // FCMOD-INTEGRATION(1.20.1): dynamic_trees: a DT tree pays the same bark as its family's vanilla or modded log (the log rule, logs design decision 7 (a))
    static int barkSpecies(BranchBlock b) {
        return b.getFamily().getPrimitiveLog().map(log -> FCLogRules.barkSpecies(log.defaultBlockState()))
                .orElse(FCLogRules.UNTAGGED_BARK);
    }

    /** The wood type of the work stump a dead DT stump of this family makes, or -1 when it makes none. */
    static int workStumpSpecies(BranchBlock b) {
        int wood = barkSpecies(b);
        return FCLogRules.isWorkStumpWood(wood) ? wood : -1;
    }

    /**
     * One plank of the family's own wood for the saw, for a wood FC has no planks subtype for (the fungi, modded
     * families): {@link FCLogRules#sawPlanks} of its primitive log; EMPTY when it has none.
     */
    static ItemStack sawPlanks(ServerLevel level, BranchBlock b) {
        return b.getFamily().getPrimitiveLog().map(log -> FCLogRules.sawPlanks(level, log.defaultBlockState()))
                .orElse(ItemStack.EMPTY);
    }

    /**
     * The states the FC machine hook ({@link DTBlockHooks}) owns while the integration is on: branches (roots
     * included, for fertilizer), rooty soil, trunk shells, and DT's pods and fruit ({@link GrowableBlock}, dtphc2's
     * included). Saplings are not here: they are twins ({@link DTSaplingTwin}).
     */
    static boolean isHookTarget(BlockState s) {
        if (!DynamicTreesCompat.active) return false;
        try {
            Block block = s.getBlock();
            return TreeHelper.isBranch(s) || TreeHelper.isRooty(s) || block instanceof TrunkShellBlock
                    || block instanceof GrowableBlock;
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the FC machine hook predicate", t);
            return false;
        }
    }

    /** The blocks whose {@code use()} runs DT's substance code (the bone-meal growth pulse). */
    static boolean isTreeUseTarget(BlockState s) {
        return TreeHelper.isBranch(s) || TreeHelper.isRooty(s) || s.getBlock() instanceof TrunkShellBlock;
    }

    /**
     * The dig factor of a chewed (stripped) branch: FCBlockLog's hardness over FCBlockLogDamaged's (1.25 / 2.0 =
     * 0.625 with FC's values), the log rule's own factor for a barkless log ({@link FCLogRules#damagedLogFactor}).
     */
    static float damagedLogFactor() {
        return FCLogRules.damagedLogFactor();
    }

    /**
     * The item one chew stage pops (FCBlockLogDamaged.ConvertBlock, Server :168-206): 2 sawdust on a stump; else a
     * stick at an even new radius and 1 sawdust at an odd one. From radius 8: strip + bark to 7, then a stick (6),
     * sawdust (5), a stick (4); the last chew pops sawdust and cuts through.
     */
    static ItemStack stageItem(boolean stump, int newRadius) {
        if (stump) return FCLogRules.sawdust(2);
        return (newRadius & 1) == 0 ? FCLogRules.stick(1) : FCLogRules.sawdust(1);
    }
}
