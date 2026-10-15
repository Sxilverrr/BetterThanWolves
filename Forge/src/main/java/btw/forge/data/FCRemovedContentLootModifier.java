package btw.forge.data;

import btw.forge.removed.RemovedContent;
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
 * Global Loot Modifier that keeps the ids BTW 4.B removed out of every generated loot list, driven by the
 * {@link RemovedContent} table (stays-removed).
 *
 * <p><b>Maintainer ruling:</b> "blocks that 4.B explicitly removed must stay removed." Loot is a survival source
 * of several of them in 1.20.1 (mineshaft activator rails, outpost and fishing tripwire hooks, ruined-portal light
 * weighted plates, village torches and snow blocks, piglin-barter nether bricks, bonus-chest and archaeology
 * wooden tools: removed_blocks.md section 2).</p>
 *
 * <p>Registered as {@code betterthanwolves:removed_content} and shipped as one modifier with no conditions
 * ({@code data/betterthanwolves/loot_modifiers/removed_content.json}, emitted by {@link FCLootOverrides}). With
 * no conditions {@link LootModifier#apply} always runs it, and Forge applies global modifiers to every top-level
 * {@code LootTable.getRandomItems}: chests and chest minecarts, archaeology, fishing, piglin barter, gifts, entity
 * drops and block drops (FC's own block harvest spawns items directly and is unaffected).</p>
 *
 * <p>Per table row: T1 and T3 are filtered (FC's own loot filters remove, ComponentMineshaftCorridor
 * .FilterChestMinecartContents and WorldGenDungeons.FilterChestContentsForDepth); T2 becomes its BTW successor at
 * the same count (legacy torch to the unlit finite torch, nether brick to the BTW nether brick, chest to the BTW
 * chest, ...), or is filtered when the successor does not resolve. Reading the table at run time, rather than one
 * JSON per item, keeps loot from drifting away from recipes, tabs and structures.</p>
 */
// FCMOD-INTEGRATION(1.20.1): stays-removed: one table-driven, condition-free GLM covers every loot source
public class FCRemovedContentLootModifier extends LootModifier {

    public static final Supplier<Codec<FCRemovedContentLootModifier>> CODEC =
            Suppliers.memoize(() -> RecordCodecBuilder.<FCRemovedContentLootModifier>create(inst ->
                    LootModifier.codecStart(inst).apply(inst, FCRemovedContentLootModifier::new)));

    public FCRemovedContentLootModifier(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        boolean any = false;
        for (ItemStack stack : generatedLoot) {
            if (RemovedContent.isRemovedLoot(stack)) {
                any = true;
                break;
            }
        }
        if (!any) return generatedLoot;
        ObjectArrayList<ItemStack> out = new ObjectArrayList<>(generatedLoot.size());
        for (ItemStack stack : generatedLoot) {
            RemovedContent.addLootReplacement(stack, out);
        }
        return out;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
