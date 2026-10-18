package btw.forge.compat.sereneseasons;

import btw.forge.climate.SeasonGate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import sereneseasons.config.FertilityConfig;
import sereneseasons.init.ModConfig;
import sereneseasons.init.ModFertility;

import java.util.List;

/**
 * The {@link SeasonGate} provider for Serene Seasons (INTEGRATIONS #028): every Serene Seasons decision on a growth
 * step BTW asks about, from Serene Seasons' own fertility rules and config. Consumers: {@link SeasonalCropGate} (BTW
 * wheat and hemp, keyed by their seed item) and the Dynamic Trees sapling grow step (keyed by the species seed item).
 *
 * <p>Order of the answers:</p>
 * <ol>
 *   <li>{@code ModConfig.fertility} absent or {@code seasonalCrops} off: ALLOW.</li>
 *   <li>The state is in a Serene Seasons season block tag ({@code ModFertility.isCrop}): ALLOW. Serene Seasons' own
 *       random-tick veto already ran for it (one gate per crop).</li>
 *   <li>No key, or a key naming no item and no block in the four season tags: ALLOW. An untagged plant stays
 *       season-blind; {@code ModFertility.isCropFertile} would otherwise call it infertile at every cold
 *       position.</li>
 *   <li>Fertile here and now ({@code ModFertility.isCropFertile} with the key), or greenhouse glass within the 16 cells
 *       above (Serene Seasons' own greenhouse scan): ALLOW.</li>
 *   <li>Out of season, by {@code outOfSeasonCropBehavior}: 1 is DENY; 2 is DESTROY, or DENY for an
 *       {@code #sereneseasons:unbreakable_infertile_crops} state; anything else (0) is ALLOW on one growing day in six
 *       per cell, else DENY.</li>
 * </ol>
 *
 * <p>Behaviour 0 is a fixed growing day instead of Serene Seasons' {@code nextInt(6)} per random tick, which keeps its
 * 1-in-6 ratio under FC's one-step-per-day model (decision #10): BTW wheat rolls about 12 times a day and grows once,
 * so denying 5 rolls in 6 would slow it only about 1.8x. The day key is {@code (dayTime + 2000) / 24000}, so it
 * changes inside FC's night-reset window and one FC growth day is one key.</p>
 *
 * <p>Failures are not caught here: {@link SeasonGate#growthVerdict} drops a provider that throws (LinkageError
 * included) with one WARN, and ALLOW answers from then on.</p>
 */
// FCMOD-INTEGRATION(1.20.1): serene_seasons: Serene Seasons' fertility rules and out-of-season behaviour answer BTW's season gate for crops its own random-tick veto never sees
final class SereneSeasonsSeasonGate implements SeasonGate.Provider {

    private static final List<TagKey<Item>> SEASON_ITEM_TAGS = List.of(
            ItemTags.create(ss("spring_crops")),
            ItemTags.create(ss("summer_crops")),
            ItemTags.create(ss("autumn_crops")),
            ItemTags.create(ss("winter_crops")));

    private static final List<TagKey<Block>> SEASON_BLOCK_TAGS = List.of(
            BlockTags.create(ss("spring_crops")),
            BlockTags.create(ss("summer_crops")),
            BlockTags.create(ss("autumn_crops")),
            BlockTags.create(ss("winter_crops")));

    private static final TagKey<Block> GREENHOUSE_GLASS = BlockTags.create(ss("greenhouse_glass"));
    private static final TagKey<Block> UNBREAKABLE_INFERTILE_CROPS =
            BlockTags.create(ss("unbreakable_infertile_crops"));

    /** Serene Seasons scans this many cells above a crop for greenhouse glass. */
    private static final int GREENHOUSE_SCAN = 16;

    /** Behaviour 0: one growing day in this many, per cell. */
    private static final int GROWING_DAY_ONE_IN = 6;

    private static ResourceLocation ss(String path) {
        return new ResourceLocation("sereneseasons", path);
    }

    @Override
    public SeasonGate.Verdict growthVerdict(ServerLevel level, BlockPos pos, BlockState state,
                                            ResourceLocation fertilityKey) {
        FertilityConfig cfg = ModConfig.fertility;
        if (cfg == null || !cfg.seasonalCrops) return SeasonGate.Verdict.ALLOW;
        if (ModFertility.isCrop(state)) return SeasonGate.Verdict.ALLOW;
        if (fertilityKey == null || !isListed(fertilityKey)) return SeasonGate.Verdict.ALLOW;
        if (ModFertility.isCropFertile(fertilityKey.toString(), level, pos) || greenhouseAbove(level, pos)) {
            return SeasonGate.Verdict.ALLOW;
        }
        switch (cfg.outOfSeasonCropBehavior) {
            case 1:
                return SeasonGate.Verdict.DENY;
            case 2:
                return state.is(UNBREAKABLE_INFERTILE_CROPS) ? SeasonGate.Verdict.DENY : SeasonGate.Verdict.DESTROY;
            default:
                return growingDay(level, pos) ? SeasonGate.Verdict.ALLOW : SeasonGate.Verdict.DENY;
        }
    }

    /**
     * True when {@code key} is in Serene Seasons' merged name set: an item in one of the four season item tags, or a
     * block whose default state is in one of the four season block tags ({@code ModFertility.populate} merges both
     * kinds of name). A key may name a seed item (BTW seeds, Dynamic Trees species seeds) or a block.
     */
    private static boolean isListed(ResourceLocation key) {
        if (ForgeRegistries.ITEMS.containsKey(key)) {
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item != null) {
                ItemStack stack = new ItemStack(item);
                for (TagKey<Item> tag : SEASON_ITEM_TAGS) {
                    if (stack.is(tag)) return true;
                }
            }
        }
        if (ForgeRegistries.BLOCKS.containsKey(key)) {
            Block block = ForgeRegistries.BLOCKS.getValue(key);
            if (block != null) {
                BlockState defaultState = block.defaultBlockState();
                for (TagKey<Block> tag : SEASON_BLOCK_TAGS) {
                    if (defaultState.is(tag)) return true;
                }
            }
        }
        return false;
    }

    /** Greenhouse glass in one of the 16 cells above {@code pos}, as Serene Seasons scans above its own crops. */
    private static boolean greenhouseAbove(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int dy = 1; dy <= GREENHOUSE_SCAN; dy++) {
            cursor.setY(pos.getY() + dy);
            if (level.getBlockState(cursor).is(GREENHOUSE_GLASS)) return true;
        }
        return false;
    }

    /**
     * Behaviour 0 (decision #10): this cell grows on one FC growth day in six. The key is the cell's position seed
     * mixed with the day index {@code floorDiv(dayTime + 2000, 24000)}.
     */
    private static boolean growingDay(ServerLevel level, BlockPos pos) {
        int cell = (int) Mth.getSeed(pos.getX(), pos.getY(), pos.getZ());
        int day = (int) Math.floorDiv(level.getDayTime() + 2000L, 24000L);
        return (Mth.murmurHash3Mixer(cell ^ day) & 0x7fffffff) % GROWING_DAY_ONE_IN == 0;
    }

    @Override
    public String toString() {
        return "SereneSeasonsSeasonGate";
    }
}
