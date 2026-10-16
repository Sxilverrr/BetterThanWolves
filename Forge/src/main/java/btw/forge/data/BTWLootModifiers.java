package btw.forge.data;

import btw.forge.BTWForgeMod;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for BTW Global Loot Modifiers. Each entry registers a codec;
 * concrete modifier JSON instances live under
 * {@code data/betterthanwolves/loot_modifiers/} and are emitted by
 * {@link FCLootOverrides} during data-gen.
 *
 * Global Loot Modifiers satisfy INTEGRATIONS.md Protocol #3 (prefer data
 * over code) — we tune vanilla loot without replacing full loot-table
 * JSONs, which would bind us to vanilla's exact table layout.
 */
public final class BTWLootModifiers {

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,
                    BTWForgeMod.MOD_ID);

    public static final RegistryObject<Codec<FCRemoveItemFromLootModifier>> REMOVE_ITEM =
            LOOT_MODIFIERS.register("remove_item", FCRemoveItemFromLootModifier.CODEC);

    public static final RegistryObject<Codec<FCAddItemToLootModifier>> ADD_ITEM =
            LOOT_MODIFIERS.register("add_item", FCAddItemToLootModifier.CODEC);

    // FCMOD-INTEGRATION(1.20.1): stays-removed: table-driven filter/substitute for the ids 4.B removed
    public static final RegistryObject<Codec<FCRemovedContentLootModifier>> REMOVED_CONTENT =
            LOOT_MODIFIERS.register("removed_content", FCRemovedContentLootModifier.CODEC);

    // FCMOD-INTEGRATION(1.20.1): modded_plant_rules: unripe crops on BTW soil drop nothing (4.B's crop rule for modded and twin crops)
    public static final RegistryObject<Codec<FCImmatureCropLootModifier>> IMMATURE_CROP =
            LOOT_MODIFIERS.register("immature_crop", FCImmatureCropLootModifier.CODEC);

    private BTWLootModifiers() {}
}
