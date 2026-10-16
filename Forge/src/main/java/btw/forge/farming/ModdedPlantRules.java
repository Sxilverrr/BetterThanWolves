package btw.forge.farming;

import btw.forge.BTWForgeMod;
import btw.forge.ModernPlantStandIn;
import btw.forge.TwinRegistry;
import btw.forge.WorldBridge;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BTW's growth rules for modded plants on BTW soil (INTEGRATIONS #026; maintainer ruling: a post-1.5.2 plant, vanilla
 * or modded, takes the 4.B rule of its nearest FC relative). The plants and soils are {@link FcSoil}'s: a governed
 * modded plant standing on BTW soil that takes it. Everything else keeps 1.20.1's behaviour.
 *
 * <ul>
 *   <li><b>Crop growth</b> (settled decision D1 (b)): a modded CropBlock's own random tick asks
 *       {@code CropGrowEvent.Pre} (CropBlock.randomTick); this LOW-priority listener answers with FC's root-crop rule,
 *       the one beetroot's twin runs (FCBlockCarrot through Modern-Common BlockCrops.AttemptToGrow): no growth in the
 *       End, none while the soil has weeds, light of 9 or more above (torchlight counts; sky light follows the time of
 *       day), hydrated soil, then a roll of {@code modded_crop_base_chance} (0.1, Common FCBlockCarrot.java:31) times
 *       the soil's multiplier (fertilized soil doubles it), scaled by maxAge / 7 so ripening takes FC's seven levels
 *       whatever the crop's age count. It acts only on a DEFAULT result: another mod's ALLOW or DENY (a season mod)
 *       stands.</li>
 *   <li><b>Fully grown</b>: {@code CropGrowEvent.Post} of a crop reaching its max age tells the soil
 *       ({@code NotifyOfFullStagePlantGrowthOn}): fertilized farmland 1088 reverts to 1087, planters unfertilize, as
 *       under FC's own crops (Modern-Common BlockCrops.IncrementGrowthLevel).</li>
 *   <li><b>Weeds</b>: right-clicking a modded crop whose soil has weeds pulls them, before any held item (bone meal is
 *       kept), as FCBlockPlants.onBlockActivated does (Server FCBlockPlants.java:84-101), with 1.20.1's activation gate
 *       (no activation when sneaking with an item in either hand). The weeds grow because FC's soil sees the crop as
 *       the plant stand-in (btw.forge.ModernPlantStandIn).</li>
 *   <li><b>Data</b>: {@code data/<ns>/btw_plant_rules/*.json} ({@code {"modded_crop_base_chance": 0.1}}); values
 *       outside 0..1 are refused with a WARN and the default kept.</li>
 *   <li><b>Audit</b>: one {@code [MODDED-PLANTS]} line at server start.</li>
 * </ul>
 * <p>Modded saplings are not here: every unmapped SaplingBlock with STAGE grows by FC's sapling rule through the
 * sapling twin (TwinRegistry, settled decision D2 (b)). Bone meal is BoneMealPolicy's positional route, bees
 * BeeGrowCropGoalMixin's gate, unripe loot FCImmatureCropLootModifier and untilling ProxyBlock.</p>
 */
// FCMOD-INTEGRATION(1.20.1): modded_plant_rules: modded crops on BTW soil grow, fertilize, weed and report by FC's crop rule instead of 1.20.1's
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public final class ModdedPlantRules {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ModdedPlants");

    private ModdedPlantRules() {}

    /** FCBlockCarrot.GetBaseGrowthChance (Common FCBlockCarrot.java:31): the root-crop rule beetroot's twin runs. */
    public static final float DEFAULT_MODDED_CROP_BASE_CHANCE = 0.1F;
    /** FC crops need light 9 above (Modern-Common BlockCrops.AttemptToGrow, getBlockLightValue). */
    private static final int LIGHT_FOR_GROWTH = 9;
    /** FC's crops ripen in seven growth levels. */
    private static final float FC_CROP_LEVELS = 7F;
    /** The weed-pull block-break FX: wheat at age 6 (FCBlockPlants: Block.crops.blockID + (6 << 12)). */
    private static final int AUX_BLOCK_BREAK = 2001;

    private static volatile float baseChance = DEFAULT_MODDED_CROP_BASE_CHANCE;

    private static final Set<String> FAILURES_LOGGED = ConcurrentHashMap.newKeySet();

    /** The chance per random tick of one growth step at maxAge 7 on unfertilized soil (btw_plant_rules data). */
    public static float baseChance() {
        return baseChance;
    }

    // ================================================================
    // Growth (CropGrowEvent.Pre / Post)
    // ================================================================

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onCropGrowPre(BlockEvent.CropGrowEvent.Pre event) {
        if (event.getResult() != Event.Result.DEFAULT) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof CropBlock crop)) return;
        BlockPos pos = event.getPos();
        if (FcSoil.governedKind(state) != FcSoil.Kind.CROP || !FcSoil.isOnAcceptingBtwSoil(level, pos, state)) return;
        try {
            event.setResult(growthAllowed(level, pos, crop) ? Event.Result.ALLOW : Event.Result.DENY);
        } catch (RuntimeException | LinkageError e) {
            failure("crop growth", e);   // the result stays DEFAULT: the crop's own rule decides this once
        }
    }

    /** FC's root-crop rule for one growth step of the modded crop at {@code pos} (see the class javadoc). */
    private static boolean growthAllowed(ServerLevel level, BlockPos pos, CropBlock crop) {
        if (level.dimension() == Level.END) return false;
        btw.modern.Block soil = FcSoil.soilUnder(level, pos, FcSoil.Kind.CROP);
        if (soil == null) return false;
        btw.modern.World w = WorldBridge.getOrCreate(level);
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (soil.GetWeedsGrowthLevel(w, x, y - 1, z) > 0) return false;
        if (w.getBlockLightValue(x, y + 1, z) < LIGHT_FOR_GROWTH) return false;
        if (!soil.IsBlockHydratedForPlantGrowthOn(w, x, y - 1, z)) return false;
        float chance = baseChance * soil.GetPlantGrowthOnMultiplier(w, x, y - 1, z, ModernPlantStandIn.instance())
                * crop.getMaxAge() / FC_CROP_LEVELS;
        return level.random.nextFloat() <= Math.min(1F, chance);
    }

    @SubscribeEvent
    public static void onCropGrowPost(BlockEvent.CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState now = event.getState();
        if (!(now.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(now)) return;
        BlockState before = event.getOriginalState();
        if (before.getBlock() == crop && crop.isMaxAge(before)) return;   // not a step to full growth
        BlockPos pos = event.getPos();
        if (FcSoil.governedKind(now) != FcSoil.Kind.CROP || !FcSoil.isOnAcceptingBtwSoil(level, pos, now)) return;
        try {
            btw.modern.Block soil = FcSoil.soilUnder(level, pos, FcSoil.Kind.CROP);
            if (soil != null) {
                soil.NotifyOfFullStagePlantGrowthOn(WorldBridge.getOrCreate(level), pos.getX(), pos.getY() - 1,
                        pos.getZ(), ModernPlantStandIn.instance());
            }
        } catch (RuntimeException | LinkageError e) {
            failure("full-grown notification", e);
        }
    }

    // ================================================================
    // Weeds (PlayerInteractEvent.RightClickBlock, both sides)
    // ================================================================

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) return;
        Player player = event.getEntity();
        // 1.20.1's activation gate (ServerPlayerGameMode.useItemOn / MultiPlayerGameMode.performUseItemOn). Forge
        // posts RightClickBlock before those methods' spectator branch (ServerPlayerGameMode.java:323-325,
        // MultiPlayerGameMode.java:301-305), which never activates a block, so a spectator must not pull weeds here.
        if (player.isSpectator()) return;
        if (player.isSecondaryUseActive()
                && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CropBlock) || !FcSoil.governsAt(level, pos, state)) return;
        try {
            btw.modern.World w = FcSoil.fcWorld(level);
            btw.modern.Block soil = FcSoil.soilUnder(level, pos, FcSoil.Kind.CROP);
            if (w == null || soil == null) return;
            int x = pos.getX();
            int y = pos.getY() - 1;
            int z = pos.getZ();
            if (soil.GetWeedsGrowthLevel(w, x, y, z) <= 0) return;
            if (!level.isClientSide) {
                soil.RemoveWeeds(w, x, y, z);
                level.levelEvent(AUX_BLOCK_BREAK, pos,
                        Block.getId(Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 6)));
            }
        } catch (RuntimeException | LinkageError e) {
            failure("weed pull", e);
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }

    // ================================================================
    // Data: data/<ns>/btw_plant_rules/*.json
    // ================================================================

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PlantRulesLoader());
    }

    /** Reads {@code btw_plant_rules}: every file in key order, later files overriding earlier keys. */
    private static final class PlantRulesLoader extends SimpleJsonResourceReloadListener {
        private static final Gson GSON = new GsonBuilder().create();

        PlantRulesLoader() {
            super(GSON, "btw_plant_rules");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources,
                             ProfilerFiller profiler) {
            float base = DEFAULT_MODDED_CROP_BASE_CHANCE;
            for (Map.Entry<ResourceLocation, JsonElement> file : new TreeMap<>(files).entrySet()) {
                if (!file.getValue().isJsonObject()) {
                    LOGGER.warn("[MODDED-PLANTS] btw_plant_rules {} is not a JSON object; ignored", file.getKey());
                    continue;
                }
                JsonObject rules = file.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> rule : rules.entrySet()) {
                    if ("modded_crop_base_chance".equals(rule.getKey())) {
                        Float value = chance(file.getKey(), rule.getKey(), rule.getValue());
                        if (value != null) base = value;
                    } else {
                        LOGGER.warn("[MODDED-PLANTS] btw_plant_rules {}: unknown key {}; ignored", file.getKey(),
                                rule.getKey());
                    }
                }
            }
            baseChance = base;
            LOGGER.info("[MODDED-PLANTS] btw_plant_rules: {} file(s), modded_crop_base_chance={}", files.size(), base);
        }

        /** A chance in 0..1, or null (with a WARN) for anything else. */
        private static Float chance(ResourceLocation file, String key, JsonElement value) {
            if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
                float f = primitive.getAsFloat();
                if (f >= 0F && f <= 1F) return f;
            }
            LOGGER.warn("[MODDED-PLANTS] btw_plant_rules {}: {} must be a number in 0..1, got {}; the default is kept",
                    file, key, value);
            return null;
        }
    }

    // ================================================================
    // Audit
    // ================================================================

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            int crops = 0;
            int fallbackSaplings = 0;
            for (Block b : ForgeRegistries.BLOCKS) {
                if (b instanceof CropBlock && FcSoil.governedKind(b.defaultBlockState()) == FcSoil.Kind.CROP) crops++;
                if (TwinRegistry.isFallbackSapling(b) && TwinRegistry.idOf(TwinRegistry.Kind.SAPLING) > 0) {
                    fallbackSaplings++;
                }
            }
            int saplings = countTagGoverned(BlockTags.SAPLINGS, FcSoil.Kind.SAPLING);
            int wild = countTagGoverned(FcSoil.FC_SOIL_PLANTS, FcSoil.Kind.WILD);
            int plantId = ModernPlantStandIn.id();
            LOGGER.info("[MODDED-PLANTS] crops={} saplings={} modded-saplings-on-FC-rule={} fc_soil_plants={} "
                    + "plant stand-in={} base={}", crops, saplings, fallbackSaplings, wild,
                    plantId > 0 ? String.valueOf(plantId) : "none", baseChance);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[MODDED-PLANTS] audit failed: {}", e.toString(), e);
        }
    }

    /** Members of {@code tag} that FcSoil governs as {@code kind}. */
    private static int countTagGoverned(net.minecraft.tags.TagKey<Block> tag, FcSoil.Kind kind) {
        var tags = ForgeRegistries.BLOCKS.tags();
        if (tags == null) return 0;
        int n = 0;
        for (Block b : tags.getTag(tag)) {
            if (FcSoil.governedKind(b.defaultBlockState()) == kind) n++;
        }
        return n;
    }

    /** One WARN per stage and exception class; the rule leaves the event as it was. */
    private static void failure(String stage, Throwable e) {
        if (FAILURES_LOGGED.add(stage + '|' + e.getClass().getName())) {
            LOGGER.warn("[MODDED-PLANTS] FC {} failed: {}; 1.20.1's answer stands (logged once)", stage,
                    e.toString(), e);
        }
    }
}
