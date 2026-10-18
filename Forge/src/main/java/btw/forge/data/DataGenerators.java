package btw.forge.data;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Central registration point for BTW datapack providers.
 *
 * FC integration uses datapack overrides (loot tables, recipes, tags,
 * advancements, biome modifiers) wherever the result can be expressed
 * as data — this satisfies INTEGRATIONS.md Protocol #3 (prefer data
 * over code) and keeps integration changes reviewable as JSON.
 *
 * Subscribe to {@link GatherDataEvent} on the mod event bus. This
 * class is invoked ONLY when the Forge {@code runData} task runs;
 * production/dev game runs ignore it.
 */
@Mod.EventBusSubscriber(modid = btw.forge.BTWForgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DataGenerators {

    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

        // Server-side providers (loot, recipes, tags). All Pattern-C
        // integrations from INTEGRATIONS.md #004–#007 live here.
        if (event.includeServer()) {
            generator.addProvider(true, new FCLootOverrides(packOutput));
            generator.addProvider(true, new FCRecipeOverrides(packOutput));
            // FCMOD-INTEGRATION(1.20.1): serene_seasons: SS season tags for BTW seeds and the lit bulb as greenhouse glass, keys resolved through FcRefs (INTEGRATIONS #028)
            generator.addProvider(true, new SereneSeasonsTagProvider(packOutput));
            // FCMOD-INTEGRATION(1.20.1): lso: BTW heat, clothing, limb, meal, hydration and fuel data for Legendary Survival Overhaul, keys resolved through FcRefs (INTEGRATIONS #031)
            generator.addProvider(true, new LsoClimateDataProvider(packOutput));
            // FCMOD-INTEGRATION(1.20.1): harvestcraft: HarvestCraft food tiers, tags, millstone and reroute data and the modded-fruit pace tag; inert without the mods it names (INTEGRATIONS #032)
            generator.addProvider(true, new HarvestCraftDataProvider(packOutput));
        }

        // Client-side providers (none yet). Advancements datapack
        // (#061) and biome modifiers (#046/#047) will slot in here as
        // their phases land.
        if (event.includeClient()) {
            // reserved
        }
    }
}
