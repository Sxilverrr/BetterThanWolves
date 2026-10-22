package btw.forge.data;

import btw.forge.LeafDrops;
import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

import java.util.function.Supplier;

/**
 * Global Loot Modifier: every {@code #minecraft:leaves} block drops by 4.B's leaf rule (FCBlockLeaves: the species'
 * sapling at 1 in 20, 1 in 40 for jungle; shears and silk touch give the leaves; no sticks, apples or Fortune bonus),
 * whichever path rolls its table. The rule and its integration hooks live in {@link LeafDrops} (INTEGRATIONS #035).
 *
 * <p>Registered as {@code betterthanwolves:fc_leaves} and shipped as one modifier with no conditions
 * ({@code data/betterthanwolves/loot_modifiers/leaves_drop_like_fc.json}, emitted by {@link FCLootOverrides}); which
 * rolls it rewrites is decided at run time. Its place in the hash-ordered GLM list does not matter: it only strips leaf
 * byproducts and adds one sapling, so other modifiers' additions survive either way.</p>
 */
// FCMOD-INTEGRATION(1.20.1): leaves_drop_like_fc: the GLM that applies LeafDrops to every loot roll of a leaves block
public class FCLeavesLootModifier extends LootModifier {

    public static final Supplier<Codec<FCLeavesLootModifier>> CODEC =
            Suppliers.memoize(() -> RecordCodecBuilder.<FCLeavesLootModifier>create(inst ->
                    LootModifier.codecStart(inst).apply(inst, FCLeavesLootModifier::new)));

    public FCLeavesLootModifier(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        return LeafDrops.apply(generatedLoot, context);
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
