package btw.forge.compat.dynamictrees;

import btw.forge.FCLogRules;
import btw.forge.LeafDrops;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.block.leaves.LeavesProperties;
import com.ferreusveritas.dynamictrees.loot.DTLootContextParams;
import com.ferreusveritas.dynamictrees.loot.condition.SeasonalSeedDropChance;
import com.ferreusveritas.dynamictrees.tree.family.Family;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * BTW's leaf rule for Dynamic Trees leaves ({@link LeafDrops}, INTEGRATIONS #035 and #027): a DT leaves roll, a
 * direct break (DynamicLeavesBlock.getDrops) or a fell (LeavesProperties.getDrops, rolled before the leaves become
 * air, BranchBlock.java:521-531), drops only the species' seed at FCBlockLeaves's odds, never sticks or a Fortune
 * bonus:
 * <ul>
 *   <li>the species is the roll's {@code SPECIES}; orphan leaves ({@code NULL_SPECIES}) take their family's common
 *       species, as FC's leaves always know their species;</li>
 *   <li>a species that drops no leaf seeds (fruit species, {@code Species.shouldDropSeeds}, Species.java:2464-2466) keeps
 *       DT's verdict: no seed, since it propagates through its fruit;</li>
 *   <li>1 in 40 when the family's primitive log is FC's jungle (wood type 3, {@link FCLogRules#fcWoodMeta}), else 1 in
 *       20;</li>
 *   <li>DT's own seasonal and config seed chance (SeasonalSeedDropChance, Serene Seasons' factor and
 *       {@code leavesSeedDropRate}) vetoes FC's roll on the same context; with no seasons mod and the default rate it
 *       always passes, so the rate is exactly FC's, and a rate above 1.0 cannot raise it;</li>
 *   <li>the family's stick is a byproduct and is stripped with the saplings and {@code #forge:rods/wooden}.</li>
 * </ul>
 * Shears and silk touch give DT's primitive leaves, which LeafDrops keeps. Reached only through the hook
 * DynamicTreesCompat registers, and only while the integration is active; a throw switches the integration off and
 * leaves DT's loot to DT.
 */
// FCMOD-INTEGRATION(1.20.1): dynamic_trees: DT leaves drop by BTW's leaf rule through DT's own species, seed and seasonal chance (INTEGRATIONS #027, #035)
final class DTLeafDrops {

    private DTLeafDrops() {}

    /**
     * Resolves by reflection every Dynamic Trees member {@link #resolve} uses, with its exact types, as
     * {@link DTTrees#probe} does for the rest of the integration. Throws IllegalStateException naming the first one
     * that is missing or changed, so {@link DynamicTreesCompat#init} never switches the integration on against an
     * incompatible DT. Reflection neither initializes DT classes nor touches any registry.
     */
    static void probe() {
        try {
            subtype(DynamicLeavesBlock.class, Block.class);
            method(DynamicLeavesBlock.class, LeavesProperties.class, "getProperties", false, BlockState.class);
            method(LeavesProperties.class, Family.class, "getFamily", false);
            field(DTLootContextParams.class, LootContextParam.class, "SPECIES");
            field(DTLootContextParams.class, LootContextParam.class, "SEASONAL_SEED_DROP_FACTOR");
            field(Species.class, Species.class, "NULL_SPECIES");
            method(Species.class, boolean.class, "shouldDropSeeds", false);
            method(Species.class, ItemStack.class, "getSeedStack", false, int.class);
            method(Family.class, Species.class, "getCommonSpecies", false);
            method(Family.class, Optional.class, "getPrimitiveLog", false);
            method(Family.class, ItemStack.class, "getStick", false, int.class);
            subtype(SeasonalSeedDropChance.class, LootItemCondition.class);
            method(SeasonalSeedDropChance.class, LootItemCondition.Builder.class, "seasonalSeedDropChance", true);
        } catch (LinkageError e) {
            throw new IllegalStateException("Dynamic Trees API class missing: " + e.getMessage(), e);
        }
    }

    /** The leaf rule's answer for a DT leaves roll; {@link LeafDrops#KEEP} for any other block or on failure. */
    @SuppressWarnings("deprecation") // getProperties(BlockState) is the accessor DT's own fell uses (BranchBlock.java:526)
    static LeafDrops.Resolution resolve(BlockState state, LootContext ctx) {
        try {
            if (!(state.getBlock() instanceof DynamicLeavesBlock leaves)) return LeafDrops.KEEP;
            Family family = leaves.getProperties(state).getFamily();
            Species species = ctx.getParamOrNull(DTLootContextParams.SPECIES);
            if (species == null || species == Species.NULL_SPECIES) species = family.getCommonSpecies();
            ItemStack seed = species != null && species.shouldDropSeeds() ? species.getSeedStack(1) : ItemStack.EMPTY;
            int fcWoodType = family.getPrimitiveLog().map(FCLogRules::fcWoodMeta).orElse(-1);
            ItemStack stick = family.getStick(1);
            Set<Item> byproducts = stick.isEmpty() ? Set.of() : Set.of(stick.getItem());
            LootItemCondition seasonal = SeasonalSeedDropChance.seasonalSeedDropChance().build();
            return new LeafDrops.Resolution(seed, fcWoodType, c -> allows(seasonal, c), byproducts);
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the leaf drop rule", t);
            return LeafDrops.KEEP;
        }
    }

    /**
     * DT's SeasonalSeedDropChance on the roll's own context (SeasonalSeedDropChance.java:28-34); a roll without the
     * factor (another mod's table) passes. A throw switches the integration off and is rethrown, so LeafDrops keeps
     * the table's loot.
     */
    private static boolean allows(LootItemCondition seasonal, LootContext c) {
        try {
            return c.getParamOrNull(DTLootContextParams.SEASONAL_SEED_DROP_FACTOR) == null || seasonal.test(c);
        } catch (RuntimeException | LinkageError t) {
            DynamicTreesCompat.fail("the leaf drop rule's seasonal seed chance", t);
            throw t;
        }
    }

    // ------------------------------------------------------------------ probe helpers (DTTrees's are private)

    private static void method(Class<?> owner, Class<?> returns, String name, boolean isStatic, Class<?>... params) {
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

    private static void field(Class<?> owner, Class<?> type, String name) {
        String what = owner.getName() + "." + name;
        Field f;
        try {
            f = owner.getField(name);
        } catch (NoSuchFieldException e) {
            throw changed("no field " + what);
        }
        if (f.getType() != type) throw changed(what + " is now " + f.getType().getName());
        if (!Modifier.isStatic(f.getModifiers())) throw changed(what + " is no longer static");
    }

    private static void subtype(Class<?> child, Class<?> parent) {
        if (!parent.isAssignableFrom(child)) throw changed(child.getName() + " no longer extends " + parent.getName());
    }

    private static IllegalStateException changed(String what) {
        return new IllegalStateException("Dynamic Trees API member missing or changed: " + what);
    }
}
