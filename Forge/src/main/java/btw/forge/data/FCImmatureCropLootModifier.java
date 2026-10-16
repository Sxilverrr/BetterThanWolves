package btw.forge.data;

import btw.forge.BTWLifecycle;
import btw.forge.ProxyRegistry;
import btw.forge.farming.FcSoil;
import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

import java.util.function.Supplier;

/**
 * Global Loot Modifier: an unripe crop on BTW soil drops nothing, as 4.B's crops do (1.5.2 BlockCrops
 * .dropBlockAsItemWithChance and FCBlockCrops drop produce and seeds only when fully grown: Modern-Common
 * BlockCrops.java, Server FCBlockCrops.java:52-61), whether it is broken or popped (INTEGRATIONS #026; maintainer
 * ruling: each post-1.5.2 plant takes its nearest FC relative's rules, and FC's carrot drops nothing while unripe).
 *
 * <p>Applies to every CropBlock with no FC identity id (FC's own crops drop through FC): modded crops, and the twin
 * crops beetroot and torchflower (settled decision D3 (b); the pitcher crop is not a CropBlock and keeps its loot),
 * when the crop is not at its max age and the soil below its position either takes crops
 * ({@link FcSoil#isOnAcceptingBtwSoil}: FC farmland, planters) or is FC's loose dirt. A player break removes the block
 * before the loot is rolled (ServerPlayerGameMode.destroyBlock, then playerDestroy), and that removal has already
 * untilled FC farmland to loose dirt (ProxyBlock's modded-crop untilling, or S16 for twins). BTW ships no block tags,
 * so no 1.20.1 plant stands on loose dirt otherwise. Mature drops, and crops on other soils (a survival mod's fern crop
 * on grass, a plant on podzol), are unchanged.</p>
 *
 * <p>Registered as {@code betterthanwolves:immature_crop} and shipped as one modifier with no conditions
 * ({@code data/betterthanwolves/loot_modifiers/immature_crops_drop_nothing.json}, emitted by {@link FCLootOverrides}),
 * after {@code removed_content}.</p>
 */
// FCMOD-INTEGRATION(1.20.1): modded_plant_rules: unripe crops on BTW soil drop nothing, the 4.B crop rule, for modded and twin crops alike
public class FCImmatureCropLootModifier extends LootModifier {

    public static final Supplier<Codec<FCImmatureCropLootModifier>> CODEC =
            Suppliers.memoize(() -> RecordCodecBuilder.<FCImmatureCropLootModifier>create(inst ->
                    LootModifier.codecStart(inst).apply(inst, FCImmatureCropLootModifier::new)));

    public FCImmatureCropLootModifier(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (generatedLoot.isEmpty() || !BTWLifecycle.isFcReady()) return generatedLoot;
        BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
        if (state == null || !(state.getBlock() instanceof CropBlock crop)) return generatedLoot;
        if (ProxyRegistry.getBlockId(crop) != 0 || crop.isMaxAge(state)) return generatedLoot;
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin == null) return generatedLoot;
        ServerLevel level = context.getLevel();
        BlockPos pos = BlockPos.containing(origin);
        if (FcSoil.isOnAcceptingBtwSoil(level, pos, state) || FcSoil.isUntilledLooseDirt(level, pos.below())) {
            return new ObjectArrayList<>();
        }
        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
