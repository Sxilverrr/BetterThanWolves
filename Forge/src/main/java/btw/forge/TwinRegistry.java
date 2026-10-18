package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.AzaleaTreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.eventbus.api.Event;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Post-1.5.2 plants at 4.B speeds (maintainer ruling: each modern plant takes the 4.B rule of its nearest 1.5.2 / FC
 * relative, adapted to its nature). {@link TickPolicy#decide} reads this table to give every state of these plants
 * one of two owners:
 *
 * <p><b>Twins</b> (owner {@link TickPolicy#FC}). A twin is a plain instance of an existing FC plant class, built here
 * at a reserved legacy id. FC's world reads ({@link ProxyRegistry#getWorldBlockId}) see the live 1.20.1 plant as the
 * twin's id, its random tick runs the twin's FC {@code updateTick} unchanged, and WorldBridge keeps FC's metadata in the
 * live state plus the FC metadata overlay ({@link #deriveMeta} / {@link #stateForMeta}). Identity routing
 * ({@link ProxyRegistry#getBlockId}, BlockBehaviorMixin, items) stays 0, so 1.20.1 keeps placement, survival from
 * shape updates, loot, collision and scheduled ticks. Where the twin must act on the modern plant rather than on
 * 1.5.2 blocks, load-time mixins on the FC class keyed on the twin id redirect the result
 * ({@code btw.forge.mixin.fcext}: the tree a sapling grows and its soils, the huge fungus a fungus grows); the twin's
 * drops are the live loot (Modern-Common {@code ModernTwins}).</p>
 * <ul>
 *   <li>{@link Family#SAPLING}: acacia, cherry, planted mangrove propagule, dark oak saplings on an FCBlockSapling:
 *       survival (light 8 or sky, sapling soils), not in the End, light 9 above, four stages at 1/64 (Server
 *       FCBlockSapling.java:40-64), then the species' own 1.20.1 tree. Dark oak takes FC's 2x2 jungle rule (all four
 *       at stage 3, FCBlockSapling.java:105-151); a lone dark oak waits at stage 3, as 1.20.1 has no single dark oak.
 *       The propagule keeps its 1.20.1 soils (clay, mud) beside FC's (S3).</li>
 *   <li>Modded saplings ({@link #isFallbackSapling}, INTEGRATIONS #026): every other SaplingBlock with STAGE and no
 *       legacy id (another mod's sapling, for instance HarvestCraft's fruit-tree saplings) is a {@link Family#SAPLING}
 *       twin of species 0, by #018's lore line "a sapling is a sapling: the species only chooses the tree": FC's
 *       survival (with the sapling's own soils through {@link #liveSoilAccepts}), light 9, four stages at 1/64 and the
 *       End ban, then the mod's own tree through SaplingBlock.advanceTree. A modded sapling that overrides its random
 *       tick (a Nether or End sapling) follows FC's rule instead of its own conditions. Saplings of other mods that are
 *       not SaplingBlocks join through their integration ({@link ModdedSapling}, {@link #registerModdedSapling}: Dynamic
 *       Trees, INTEGRATIONS #027); a registered Nether fungus takes {@link Family#FUNGUS} instead.</li>
 *   <li>{@link Family#AZALEA}: azalea and flowering azalea on their own FCBlockSapling (S1, the bush is the sapling
 *       of the azalea tree; 1.20.1 grows it only by bone meal), with 1.20.1's fire odds for FC fire.</li>
 *   <li>{@link Family#BEETROOT}: beetroots on an FCBlockCarrot, the 4.B root crop: 0.1 per tick, seven levels, light
 *       9, hydrated FC soil, weeds stop it, fertilizer doubles it (Common FCBlockCarrot.java:30-51, Modern-Common
 *       BlockCrops.AttemptToGrow).</li>
 *   <li>{@link Family#TORCHFLOWER}, {@link Family#PITCHER}: the sniffer crops on one FCBlockWheatLegacy, the 4.B
 *       BlockCrops pace of legacy wheat (0.05, S4). The torchflower crop becomes the TORCHFLOWER block at level 7;
 *       the pitcher crop's lower half is the twin and shows AGE 3 (upper half, needs room) at level 6, AGE 4 at 7.</li>
 *   <li>{@link Family#FUNGUS}: crimson and warped fungi on an FCBlockMushroom: FC's mushroom-on-mycelium rule on the
 *       species' own nylium, 1 in 50 random ticks to its planted huge fungus, not in the End, no spread (S11).</li>
 * </ul>
 *
 * <p><b>Paced</b> (owner {@link TickPolicy#PACED}). Plants with no FC class whose {@code updateTick} could drive them
 * keep their 1.20.1 body, run only on a fraction {@code pace} of their random ticks, so the mean pace is FC's
 * ({@code pace} = FC chance per tick / 1.20.1 chance per tick), and not at all in the End where FC bans growth:</p>
 * <ul>
 *   <li>bamboo shoot and bamboo: reed pace, 1/32 per segment (Server FCBlockReed.java:36-69), 1.20.1 1/3: 3/32 (S6);</li>
 *   <li>kelp: reed pace 1/32, 1.20.1 0.14 (KelpBlock): 0.2232 (S7);</li>
 *   <li>cave, weeping and twisting vines: FC hanging-vine pace 1/16 (Server FCBlockVine.java:44-181), 1.20.1 0.1:
 *       0.625 (S8, S9: glow berries keep their 11% per new segment);</li>
 *   <li>sweet berry bush: cocoa pace 1/20 per stage (Server FCBlockCocoa.java:33-58), 1.20.1 1/5: 0.25 (S5);</li>
 *   <li>hanging mangrove propagule: cocoa pace 1/20 per age step, 1.20.1 every tick: 0.05 (S12);</li>
 *   <li>chorus flower: cactus pace, 16 ticks per step (Server FCBlockCactus.java:40-48), 1.20.1 every tick: 1/16,
 *       exempt from the End ban: chorus is the End's own plant (S10).</li>
 *   <li>modded plants in {@link #PACED_LIKE_SWEET_BERRIES} (HarvestCraft, Dynamic Trees and dtphc2 fruit and pods, whose
 *       own random tick grows a stage at 1/5): the sweet-berry pace 0.25 with the End ban (INTEGRATIONS #032).</li>
 * </ul>
 *
 * <p><b>Kept on 1.20.1</b>: modern leaves and nylium decay (already 4.B pacing, S13, S14), and every plant that only
 * multiplies by bone meal (S15), which the bone-meal rule governs.</p>
 *
 * <p><b>Reserved ids.</b> The twins live in the free legacy range 159..174 that {@link ModernBlockStandIn} also uses:
 * 1.5.2 vanilla ends at 158, FC's ParseID defaults start at 175 (FCBetterThanWolves.java:914), and 159 / 170-173 are
 * 1.6+ ids the vanilla table maps. Ids from 175 up are walked by BTWRegistration (a ProxyBlock and a block item for
 * every blocksList entry) and BlockModelBridge, so a twin there would become a registered block. The twins take the
 * free ids from the top (174, 169, 168, 167, 166), before any stand-in exists; the stand-ins allocate from the bottom.
 * The twins are FC static data: built in BTWLifecycle before {@link BTWLifecycle#isFcReady()}, never changed after,
 * so {@link TickPolicy#decide} stays a pure function of the state and FC's static data.</p>
 */
public final class TwinRegistry {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Twin");

    private TwinRegistry() {}

    /** One twin object (one reserved id) and the FC class it is an instance of. */
    public enum Kind {
        SAPLING("net.minecraft.src.btw.block.FCBlockSapling", "Sapling"),
        AZALEA("net.minecraft.src.btw.block.FCBlockSapling", "Azalea"),
        ROOT_CROP("net.minecraft.src.btw.block.FCBlockCarrot", "RootCrop"),
        SEED_CROP("net.minecraft.src.btw.block.FCBlockWheatLegacy", "SeedCrop"),
        FUNGUS("net.minecraft.src.btw.block.FCBlockMushroom", "Fungus");

        public final String fcClass;
        final String label;

        Kind(String fcClass, String label) {
            this.fcClass = fcClass;
            this.label = label;
        }
    }

    /** One 4.B rule for a group of modern plants, run by the twin of {@link #kind}. */
    public enum Family {
        SAPLING("sapling", Kind.SAPLING, false),
        AZALEA("azalea", Kind.AZALEA, false),
        BEETROOT("beetroot", Kind.ROOT_CROP, true),
        TORCHFLOWER("torchflower", Kind.SEED_CROP, true),
        PITCHER("pitcher", Kind.SEED_CROP, true),
        FUNGUS("fungus", Kind.FUNGUS, false);

        public final String label;
        public final Kind kind;
        /** A crop: FC metadata = growth level 0..7, and FC farmland hears its removal (S16). */
        public final boolean crop;

        Family(String label, Kind kind, boolean crop) {
            this.label = label;
            this.kind = kind;
            this.crop = crop;
        }
    }

    /** A paced plant: its 1.20.1 random-tick body runs on {@code pace} of its random ticks; none in the End if {@code endBan}. */
    public record Pace(String name, float pace, boolean endBan) {}

    /** FC's tree type of the 2x2 rule (FCBlockSapling.growTree: type 3, the jungle sapling); dark oak's species here. */
    public static final int DARK_OAK_SPECIES = 3;

    private static final Map<Block, Family> FAMILY = new IdentityHashMap<>();
    /** Species bits (FC metadata bits 0-1 of saplings, bit 0 of fungi) by block: the order in {@link #family}. */
    private static final Map<Block, Integer> SPECIES = new IdentityHashMap<>();
    private static final Map<Family, List<Block>> BLOCKS_OF = new java.util.EnumMap<>(Family.class);
    private static final Map<Block, Pace> PACES = new IdentityHashMap<>();
    /** The hanging mangrove propagule: paced, while the planted one (same block, HANGING false) is a sapling twin. */
    private static final Pace HANGING_PROPAGULE = new Pace("hanging-propagule", 1F / 20F, true);

    /**
     * Modded plants whose own random tick grows a stage at 1/5 (HarvestCraft fruit, Dynamic Trees and dtphc2 fruit and
     * pods): the sweet-berry pace, FC cocoa's 1/20 per stage (INTEGRATIONS #032; maintainer decision #7, one rule for
     * all modded fruit). Data written by BTW's datagen (HarvestCraftDataProvider), every entry required:false.
     */
    // FCMOD-INTEGRATION(1.20.1): modded_plant_rules: tagged modded fruit grows at FC cocoa's pace, and not in the End
    public static final TagKey<Block> PACED_LIKE_SWEET_BERRIES =
            BlockTags.create(new ResourceLocation(BTWForgeMod.MOD_ID, "paced_like_sweet_berries"));
    private static final Pace TAGGED_BERRY_PACE = new Pace("sweet-berry-like", (1F / 20F) / (1F / 5F), true);

    static {
        family(Family.SAPLING, Blocks.ACACIA_SAPLING, Blocks.CHERRY_SAPLING, Blocks.MANGROVE_PROPAGULE,
                Blocks.DARK_OAK_SAPLING);
        family(Family.AZALEA, Blocks.AZALEA, Blocks.FLOWERING_AZALEA);
        family(Family.BEETROOT, Blocks.BEETROOTS);
        family(Family.TORCHFLOWER, Blocks.TORCHFLOWER_CROP);
        family(Family.PITCHER, Blocks.PITCHER_CROP);
        family(Family.FUNGUS, Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS);

        float reedPace = 1F / 32F;   // FCBlockReed: 1/2 roll x 16 counter steps per segment
        pace(Blocks.BAMBOO_SAPLING, "bamboo", reedPace / (1F / 3F), true);   // BambooSaplingBlock: nextInt(3) == 0
        pace(Blocks.BAMBOO, "bamboo", reedPace / (1F / 3F), true);           // BambooStalkBlock: nextInt(3) == 0
        pace(Blocks.KELP, "kelp", reedPace / 0.14F, true);                   // KelpBlock: growPerTickProbability 0.14
        float vinePace = (1F / 16F) / 0.1F;   // FCBlockVine hanging pace / GrowingPlantHeadBlock 0.1
        pace(Blocks.CAVE_VINES, "vines", vinePace, true);
        pace(Blocks.WEEPING_VINES, "vines", vinePace, true);
        pace(Blocks.TWISTING_VINES, "vines", vinePace, true);
        pace(Blocks.SWEET_BERRY_BUSH, "berries", (1F / 20F) / (1F / 5F), true);   // FCBlockCocoa 1/20, 1.20.1 1/5
        pace(Blocks.CHORUS_FLOWER, "chorus", 1F / 16F, false);               // cactus counter, End exempt (S10)
    }

    private static void family(Family family, Block... blocks) {
        List<Block> list = new ArrayList<>();
        for (int species = 0; species < blocks.length; species++) {
            FAMILY.put(blocks[species], family);
            SPECIES.put(blocks[species], species);
            list.add(blocks[species]);
        }
        BLOCKS_OF.put(family, Collections.unmodifiableList(list));
    }

    private static void pace(Block block, String name, float pace, boolean endBan) {
        PACES.put(block, new Pace(name, pace, endBan));
    }

    /** Twin id per {@link Kind} (0 = not built). Written by {@link #createTwins} only, before FC is published. */
    private static final int[] KIND_ID = new int[Kind.values().length];

    // ================================================================
    // Construction (BTWLifecycle step 5d)
    // ================================================================

    /**
     * Builds one twin per {@link Kind} at the top free ids of the stand-in range, reflectively, as BTWLifecycle builds
     * FC's stems: the FC class's own constructor fills blocksList[id] (and, as for every 1.5.2 block, an ItemBlock at
     * itemsList[id] that no loop below 175 registers or exposes). Must run after FC's block table is final and before
     * {@link BTWLifecycle#isFcReady()} is set. A kind that cannot be built leaves its plants on 1.20.1 (logged; the
     * self-audit warns).
     */
    static void createTwins() {
        int next = ModernBlockStandIn.LAST_FREE_ID;
        for (Kind kind : Kind.values()) {
            int id = -1;
            for (int candidate = next; candidate >= ModernBlockStandIn.FIRST_FREE_ID; candidate--) {
                if (ModernBlockStandIn.isFreeId(candidate)) {
                    id = candidate;
                    break;
                }
            }
            if (id < 0) {
                LOGGER.error("[TWIN] no free legacy id in {}..{} for the {} twin; its plants keep their 1.20.1 growth",
                        ModernBlockStandIn.FIRST_FREE_ID, ModernBlockStandIn.LAST_FREE_ID, kind.label);
                continue;
            }
            next = id - 1;
            try {
                Class<?> fcClass = Class.forName(kind.fcClass);
                btw.modern.Block twin = kind == Kind.FUNGUS
                        ? (btw.modern.Block) fcClass.getConstructor(int.class, String.class)
                                .newInstance(id, "btwModernTwin" + kind.label)
                        : (btw.modern.Block) fcClass.getConstructor(int.class).newInstance(id);
                twin.setUnlocalizedName("btwModernTwin" + kind.label);
                if (kind == Kind.AZALEA) {
                    int[] odds = fireOdds(Blocks.AZALEA, 30, 60);   // 1.20.1 FireBlock.bootStrap: azalea 30 / 60
                    twin.SetFireProperties(odds[0], odds[1]);
                }
                btw.modern.Block.opaqueCubeLookup[id] = false;   // a plant (the constructor wrote isOpaqueCube())
                btw.modern.ModernTwins.markTwin(id);
                KIND_ID[kind.ordinal()] = id;
                LOGGER.info("[TWIN] {} twin: {} at legacy id {}", kind.label, fcClass.getSimpleName(), id);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                        ? ite.getCause() : t;
                LOGGER.error("[TWIN] could not build the {} twin ({}); its plants keep their 1.20.1 growth",
                        kind.label, kind.fcClass, cause);
                // the FC constructor may have registered itself before failing: leave the id free again
                btw.modern.Block.blocksList[id] = null;
                btw.modern.Item.itemsList[id] = null;
            }
        }
    }

    /** {encourage, catch} fire odds 1.20.1 gives {@code block} (FireBlock igniteOdds / burnOdds), or the defaults. */
    private static int[] fireOdds(Block block, int defaultIgnite, int defaultBurn) {
        try {
            BlockState state = block.defaultBlockState();
            int ignite = state.getFireSpreadSpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
            int burn = state.getFlammability(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
            if (ignite > 0 || burn > 0) return new int[] {ignite, burn};
        } catch (Throwable ignored) {
            // FireBlock's table not readable: the vanilla values
        }
        return new int[] {defaultIgnite, defaultBurn};
    }

    // ================================================================
    // Classification (pure: the state and the table above)
    // ================================================================

    /** Twin id of {@code kind}, 0 when it was not built. */
    public static int idOf(Kind kind) {
        return KIND_ID[kind.ordinal()];
    }

    /** True when blocksList[id] is a twin. */
    public static boolean isTwinId(int id) {
        return btw.modern.ModernTwins.isTwin(id);
    }

    /**
     * True for a modded sapling a twin runs (maintainer decision D2 (b)): a SaplingBlock with STAGE that the table above
     * does not name and that has no legacy id (FC's own oak, spruce, birch and jungle saplings are id 6), or a sapling
     * block of another mod that is not a SaplingBlock and that its integration registered ({@link #registerModdedSapling};
     * Dynamic Trees, INTEGRATIONS #027). A pure function of the block, FC's static id table and the registrations made
     * during mod construction.
     */
    // FCMOD-INTEGRATION(1.20.1): modded_plant_rules: every unmapped SaplingBlock with STAGE, and every registered non-SaplingBlock sapling, grows by FC's rule through a twin, into its own tree
    public static boolean isFallbackSapling(Block block) {
        if (block instanceof SaplingBlock) {
            return !FAMILY.containsKey(block) && ProxyRegistry.getBlockId(block) == 0
                    && block.defaultBlockState().hasProperty(SaplingBlock.STAGE);
        }
        return moddedKind(block) != null && !FAMILY.containsKey(block) && ProxyRegistry.getBlockId(block) == 0;
    }

    /**
     * The family of {@code block}: the table's; for a modded sapling ({@link #isFallbackSapling}) SAPLING, or FUNGUS for
     * a registered Nether fungus ({@link ModdedSapling#isFungus}), whose nearest FC relative is the mushroom, as for
     * vanilla crimson and warped fungi.
     */
    private static Family familyFor(Block block) {
        Family family = FAMILY.get(block);
        if (family != null) return family;
        if (!isFallbackSapling(block)) return null;
        ModdedKind modded = block instanceof SaplingBlock ? null : moddedKind(block);
        return modded != null && modded.fungus() ? Family.FUNGUS : Family.SAPLING;
    }

    /**
     * A sapling of another mod that is not a 1.20.1 SaplingBlock (Dynamic Trees' DynamicSaplingBlock, INTEGRATIONS #027).
     * Its integration registers it during mod construction, before any registry bake. FC's rule then runs it as a twin:
     * {@link Family#SAPLING} (FC's survival with its own soils through {@link #liveSoilAccepts}, the End ban, light 9
     * above, four stages at 1/64, then {@link #growTree}), or {@link Family#FUNGUS} for a Nether fungus
     * ({@link #fungusTick}: not in the End, 1 in 50 random ticks, then {@link #growTree}).
     */
    public interface ModdedSapling {
        /** True for a sapling block of this kind. Pure (reads only the block); runs during registry bakes, any thread. */
        boolean isSapling(Block block);

        /** True for a sapling of this kind that is a Nether fungus (FC's mushroom rule). Pure, like {@link #isSapling}. */
        default boolean isFungus(Block block) {
            return false;
        }

        /**
         * The twin's growTree once FC's rule decided: grow the live sapling at {@code pos} into the mod's own tree, or
         * leave it (a sapling twin keeps stage 3 and retries on its next 1/64 success; a fungus twin rolls again).
         */
        void growTree(ServerLevel level, BlockPos pos, BlockState state, RandomSource random);
    }

    /** A registered kind, and whether the block is one of its Nether fungi. */
    private record ModdedKind(ModdedSapling sapling, boolean fungus) {}

    private static final CopyOnWriteArrayList<ModdedSapling> MODDED_SAPLINGS = new CopyOnWriteArrayList<>();
    /** The answer per block (blocks never change after the registries freeze); an empty Optional means none. */
    private static final Map<Block, Optional<ModdedKind>> MODDED_SAPLING_OF = new ConcurrentHashMap<>();

    /** Registers a modded sapling kind. Call during mod construction (an integration's init). */
    // FCMOD-INTEGRATION(1.20.1): modded_plant_rules: an integration can put a non-SaplingBlock sapling on FC's sapling or mushroom rule (Dynamic Trees, #027)
    public static void registerModdedSapling(ModdedSapling sapling) {
        MODDED_SAPLINGS.add(Objects.requireNonNull(sapling, "sapling"));
        MODDED_SAPLING_OF.clear();
        LOGGER.info("[TWIN] modded sapling kind registered: {}", sapling);
    }

    /** The registered kind of {@code block}, or null. A kind whose predicate throws is skipped (logged once). */
    private static ModdedKind moddedKind(Block block) {
        if (MODDED_SAPLINGS.isEmpty()) return null;
        Optional<ModdedKind> known = MODDED_SAPLING_OF.get(block);
        if (known == null) {
            ModdedKind found = null;
            for (ModdedSapling kind : MODDED_SAPLINGS) {
                try {
                    if (kind.isSapling(block)) {
                        found = new ModdedKind(kind, kind.isFungus(block));
                        break;
                    }
                } catch (RuntimeException | LinkageError e) {
                    warnOnce("MODDED-SAPLING", BlockPos.ZERO, e);
                }
            }
            known = Optional.ofNullable(found);
            MODDED_SAPLING_OF.put(block, known);
        }
        return known.orElse(null);
    }

    /** The registered kind's grower for {@code block}, or null. */
    private static ModdedSapling moddedSapling(Block block) {
        ModdedKind kind = moddedKind(block);
        return kind == null ? null : kind.sapling();
    }

    /** The twin family {@code s} belongs to, or null (not a twin plant, a paced / upper-half state, or no twin built). */
    public static Family familyOf(BlockState s) {
        Block block = s.getBlock();
        Family family = familyFor(block);
        if (family == null || KIND_ID[family.kind.ordinal()] <= 0) return null;
        if (block == Blocks.MANGROVE_PROPAGULE && s.getValue(BlockStateProperties.HANGING)) return null;
        if (family == Family.PITCHER && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return null;
        }
        return family;
    }

    /** The twin id FC's world reads see for {@code s}, or 0 when it is not a twin state. */
    public static int worldIdFor(BlockState s) {
        Family family = familyOf(s);
        return family == null ? 0 : KIND_ID[family.kind.ordinal()];
    }

    /** True when {@code s} is a twin state. */
    public static boolean isTwinState(BlockState s) {
        return familyOf(s) != null;
    }

    /** The pace of a paced state, or null. */
    public static Pace paceFor(BlockState s) {
        Block block = s.getBlock();
        if (block == Blocks.MANGROVE_PROPAGULE) {
            return s.getValue(BlockStateProperties.HANGING) ? HANGING_PROPAGULE : null;
        }
        Pace pace = PACES.get(block);
        if (pace != null) return pace;
        // FCMOD-INTEGRATION(1.20.1): modded_plant_rules: a modded plant in #betterthanwolves:paced_like_sweet_berries runs its own random tick at the sweet-berry pace (FC cocoa), not in the End
        return isTaggedBerryLike(s) ? TAGGED_BERRY_PACE : null;
    }

    /** True for an unmapped, untwinned modded plant in {@link #PACED_LIKE_SWEET_BERRIES}; false before tags bind. */
    private static boolean isTaggedBerryLike(BlockState s) {
        Block block = s.getBlock();
        return !(block instanceof ProxyBlock) && ProxyRegistry.getBlockId(block) == 0 && !FAMILY.containsKey(block)
                && s.is(PACED_LIKE_SWEET_BERRIES);
    }

    /**
     * True when FC governs the growth of {@code s} here: a paced state, or any state of a block whose twin was built
     * (pitcher upper halves included: bone meal on either half grows the plant).
     */
    public static boolean governs(BlockState s) {
        return paceFor(s) != null || isTwinBlock(s.getBlock());
    }

    /**
     * True when some state of {@code block} is classified here (a twin block, a paced block; the mangrove propagule is
     * both), for the self-audit.
     */
    public static boolean governsBlock(Block block) {
        Family family = familyFor(block);
        return (family != null && KIND_ID[family.kind.ordinal()] > 0) || PACES.containsKey(block)
                || block == Blocks.MANGROVE_PROPAGULE || isTaggedBerryLike(block.defaultBlockState());
    }

    /** True when a state of {@code block} can be a twin state (the 1.20.1 block of a twin family whose twin was built). */
    static boolean isTwinBlock(Block block) {
        Family family = familyFor(block);
        return family != null && KIND_ID[family.kind.ordinal()] > 0;
    }

    /** Legacy id whose FC block hears the removal of {@code block} (S16: crop twins untill FC farmland), else 0. */
    static int removalIdFor(Block block) {
        Family family = FAMILY.get(block);
        return family != null && family.crop ? KIND_ID[family.kind.ordinal()] : 0;
    }

    /** The 1.20.1 blocks of {@code family}, in species order, for the self-audit. */
    static List<Block> blocksOf(Family family) {
        return BLOCKS_OF.getOrDefault(family, List.of());
    }

    /** Every paced block (the planted / hanging propagule split is per state), for the self-audit. */
    static Set<Block> pacedBlocks() {
        Set<Block> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
        blocks.addAll(PACES.keySet());
        blocks.add(Blocks.MANGROVE_PROPAGULE);
        return blocks;
    }

    // ================================================================
    // Metadata codec (WorldBridge derive / apply; the overlay keeps what the state cannot hold)
    // ================================================================

    /** Visible growth levels of the crop families, by 1.20.1 AGE. */
    private static final int[] BEETROOT_LEVELS = {0, 2, 4, 7};
    private static final int[] TORCHFLOWER_LEVELS = {0, 4};
    private static final int[] PITCHER_LEVELS = {0, 2, 4, 6, 7};

    /**
     * FC metadata the twin state {@code s} derives to, or -1 when {@code s} is not a twin state. Saplings and azaleas:
     * their species (bits 0-1; FC's stage, bits 2-3, lives in the overlay: STAGE is 1.20.1's own and left alone);
     * fungi: their species; crops: the growth level of their AGE (the levels in between live in the overlay).
     */
    public static int deriveMeta(BlockState s) {
        Family family = familyOf(s);
        if (family == null) return -1;
        return switch (family) {
            case SAPLING, AZALEA, FUNGUS -> SPECIES.getOrDefault(s.getBlock(), 0);
            case BEETROOT -> BEETROOT_LEVELS[s.getValue(BlockStateProperties.AGE_3)];
            case TORCHFLOWER -> TORCHFLOWER_LEVELS[s.getValue(BlockStateProperties.AGE_1)];
            case PITCHER -> PITCHER_LEVELS[s.getValue(BlockStateProperties.AGE_4)];
        };
    }

    /**
     * The 1.20.1 state FC metadata {@code meta} stands for at a twin cell now holding {@code s} (the reverse of
     * {@link #deriveMeta}), or {@code s} itself when the state does not change. Saplings, azaleas and fungi: unchanged
     * (FC never changes their species; the stage is overlay-only). Beetroots: level 7 = AGE 3, else min(2, level / 2).
     * Torchflower: levels 0-3 AGE 0, 4-6 AGE 1, 7 the TORCHFLOWER block (FC's full-grown crop). Pitcher (lower half):
     * {0,1} -> 0, {2,3} -> 1, {4,5} -> 2, 6 -> 3, 7 -> 4; from AGE 3 the upper half is needed
     * ({@link #needsUpperHalf}), which WorldBridge places as PitcherCropBlock.grow does.
     */
    public static BlockState stateForMeta(BlockState s, int meta) {
        Family family = familyOf(s);
        if (family == null) return s;
        int level = meta & 7;
        return switch (family) {
            case SAPLING, AZALEA, FUNGUS -> s;
            case BEETROOT -> s.setValue(BlockStateProperties.AGE_3, level == 7 ? 3 : Math.min(2, level / 2));
            case TORCHFLOWER -> level == 7 ? Blocks.TORCHFLOWER.defaultBlockState()
                    : s.setValue(BlockStateProperties.AGE_1, level >= 4 ? 1 : 0);
            case PITCHER -> s.setValue(BlockStateProperties.AGE_4, level == 7 ? 4 : level == 6 ? 3 : level / 2);
        };
    }

    /** True for a pitcher lower half whose AGE needs the upper half (PitcherCropBlock: AGE 3 and up are double). */
    public static boolean needsUpperHalf(BlockState s) {
        return familyOf(s) == Family.PITCHER && s.getValue(BlockStateProperties.AGE_4) >= 3;
    }

    /** The upper half 1.20.1 places above a pitcher lower half {@code lower} (PitcherCropBlock.grow). */
    public static BlockState upperHalfFor(BlockState lower) {
        return lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
    }

    /** True when the pitcher at {@code pos} may grow its upper half (PitcherCropBlock.canGrowInto: air or a pitcher above). */
    public static boolean canGrowUpperHalf(ServerLevel level, BlockPos pos) {
        BlockState above = level.getBlockState(pos.above());
        return above.isAir() || above.is(Blocks.PITCHER_CROP);
    }

    // ================================================================
    // Growth results (called by the fcext mixins from the twin's FC code)
    // ================================================================

    private static final AzaleaTreeGrower AZALEA_TREE = new AzaleaTreeGrower();

    /** The live level behind an FC world, or null. */
    private static ServerLevel levelOf(btw.modern.World world) {
        return world instanceof WorldBridge wb ? wb.getServerLevel() : null;
    }

    /** True when the live block at (i, j, k) is a dark oak sapling (the 2x2 rule applies). */
    public static boolean isTwoByTwoSaplingAt(btw.modern.World world, int i, int j, int k) {
        ServerLevel level = levelOf(world);
        return level != null && level.getBlockState(new BlockPos(i, j, k)).is(Blocks.DARK_OAK_SAPLING);
    }

    /**
     * FCBlockSapling.growTree of a sapling twin: the 1.20.1 tree of the live sapling at (i, j, k). Saplings and
     * planted propagules: SaplingBlock.advanceTree with STAGE 1, which runs the species' tree grower (the 2x2 dark oak
     * included: the mega grower scans the four 2x2 squares in FC's order, so it finds the square FC's stage rule just
     * passed; Forge's BlockGrowFeatureEvent fires inside). Azaleas: AzaleaTreeGrower, the grower AzaleaBlock's bone
     * meal uses. If the tree does not fit, the 1.20.1 grower puts the sapling back, and FC's rule keeps it fully grown
     * (FCBlockSapling.growTree "restore saplings at full growth"), so the next 1/64 success tries again: stage 3 is
     * written back to every sapling of the square {@code (x0, z0) .. (x1, z1)}.
     */
    public static void growSaplingTwin(btw.modern.World world, int i, int j, int k, Random random,
                                       int x0, int z0, int x1, int z1) {
        ServerLevel level = levelOf(world);
        if (level == null) return;
        BlockPos pos = new BlockPos(i, j, k);
        try {
            BlockState state = level.getBlockState(pos);
            RandomSource rs = RandomSource.create(random.nextLong());
            if (state.getBlock() instanceof SaplingBlock sapling) {
                sapling.advanceTree(level, pos, state.setValue(SaplingBlock.STAGE, 1), rs);
            } else if (state.getBlock() instanceof AzaleaBlock) {
                AZALEA_TREE.growTree(level, level.getChunkSource().getGenerator(), pos, state, rs);
            } else {
                // FCMOD-INTEGRATION(1.20.1): modded_plant_rules: a registered non-SaplingBlock sapling grows its own tree once FC's rule decided (Dynamic Trees, #027)
                ModdedSapling modded = moddedSapling(state.getBlock());
                if (modded == null) return;
                modded.growTree(level, pos, state, rs);
            }
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState left = level.getBlockState(new BlockPos(x, j, z));
                    Family family = familyOf(left);
                    if (family == Family.SAPLING || family == Family.AZALEA) {
                        world.setBlockMetadata(x, j, z, deriveMeta(left) | (3 << 2), 4);
                    }
                }
            }
        } catch (Throwable t) {
            warnOnce("SAPLING-GROW", pos, t);
        }
    }

    /**
     * FCBlockMushroom.updateTick of a fungus twin (S11): FC's two-line rule (Common FCBlockMushroom.java:34-51) with the
     * Nether's mycelium: not in the End; on the species' own nylium, 1 in 50 random ticks grows its planted huge fungus.
     * No spread (FC's 1/25 mushroom spread would thicken the Nether forests).
     */
    public static void fungusTick(btw.modern.World world, int i, int j, int k, Random rand) {
        if (world.provider != null && world.provider.dimensionId == 1) return;
        ServerLevel level = levelOf(world);
        if (level == null) return;
        BlockPos pos = new BlockPos(i, j, k);
        try {
            BlockState state = level.getBlockState(pos);
            Block nylium;
            ResourceKey<ConfiguredFeature<?, ?>> feature;
            if (state.is(Blocks.CRIMSON_FUNGUS)) {
                nylium = Blocks.CRIMSON_NYLIUM;
                feature = TreeFeatures.CRIMSON_FUNGUS_PLANTED;
            } else if (state.is(Blocks.WARPED_FUNGUS)) {
                nylium = Blocks.WARPED_NYLIUM;
                feature = TreeFeatures.WARPED_FUNGUS_PLANTED;
            } else {
                // FCMOD-INTEGRATION(1.20.1): modded_plant_rules: a registered modded Nether fungus (Dynamic Trees' crimson and warped, #027) takes the same 1-in-50 roll; its integration then grows its own tree where its soil allows
                ModdedSapling modded = moddedSapling(state.getBlock());
                if (modded != null && rand.nextInt(50) == 0) {
                    modded.growTree(level, pos, state, RandomSource.create(rand.nextLong()));
                }
                return;
            }
            if (level.getBlockState(pos.below()).is(nylium) && rand.nextInt(50) == 0) {
                growHugeFungus(level, pos, feature, RandomSource.create(rand.nextLong()));
            }
        } catch (Throwable t) {
            warnOnce("FUNGUS-GROW", pos, t);
        }
    }

    /**
     * The huge fungus of a fungus at {@code pos}, as FungusBlock.performBonemeal places it (with Forge's
     * BlockGrowFeatureEvent), without its 40% bone-meal roll: FC's 1/50 roll already decided.
     */
    private static void growHugeFungus(ServerLevel level, BlockPos pos, ResourceKey<ConfiguredFeature<?, ?>> key,
                                       RandomSource random) {
        level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key).ifPresent(holder -> {
            var event = ForgeEventFactory.blockGrowFeature(level, random, pos, holder);
            if (event.getResult() == Event.Result.DENY || event.getFeature() == null) return;
            event.getFeature().value().place(level, level.getChunkSource().getGenerator(), random, pos);
        });
    }

    /**
     * The modern half of a sapling twin's soil rule (S3): the live plant above the soil at (i, j, k) is twin
     * {@code twinId} and its own 1.20.1 survival rule accepts the soil (mangrove propagules and azaleas on clay and
     * mud, which FC's sapling soils do not name). FCBlockSapling.CanGrowOnBlock asks FC's hook first.
     */
    public static boolean liveSoilAccepts(btw.modern.World world, int i, int j, int k, int twinId) {
        ServerLevel level = levelOf(world);
        if (level == null) return false;
        try {
            BlockPos plantPos = new BlockPos(i, j + 1, k);
            BlockState plant = level.getBlockState(plantPos);
            return worldIdFor(plant) == twinId && plant.canSurvive(level, plantPos);
        } catch (Throwable t) {
            warnOnce("SOIL", new BlockPos(i, j + 1, k), t);
            return false;
        }
    }

    // ================================================================
    // Logging
    // ================================================================

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** Runs inside FC plant ticks: never throws out of them; one WARN per site. */
    private static void warnOnce(String stage, BlockPos pos, Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        String site = stage + '|' + t.getClass().getName() + '@' + (frames.length > 0 ? frames[0] : "?");
        if (LOGGED.add(site)) {
            LOGGER.warn("[TWIN] {} at {} threw {} (logged once per site)", stage, pos, t.toString(), t);
        }
    }
}
