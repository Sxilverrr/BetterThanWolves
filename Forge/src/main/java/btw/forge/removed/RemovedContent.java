package btw.forge.removed;

import btw.forge.ProxyRegistry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The single source of truth for the 1.20.1 blocks and items that BTW 4.B removed ("stays removed").
 *
 * <p><b>Maintainer ruling:</b> "blocks that 4.B explicitly removed must stay removed." The ruling overrides
 * INTEGRATIONS.md Protocol #5 ("keep both items/blocks dual-sourced") and Protocol #8 ("integrate, don't disable")
 * for the rows of this table only; nothing outside it is removed.</p>
 *
 * <p>INTEGRATIONS-style summary (the log entry itself is written separately):</p>
 * <ul>
 *   <li><b>Modern feature:</b> 1.20.1 survival sources of ids 4.B removed: crafting/cooking recipes, chest,
 *       archaeology, fishing, barter and block loot, generated structures, creative tabs (and JEI, which lists the
 *       tabs), string placing tripwire, structure/mod/command-spawned TNT and hopper minecarts, and trades.</li>
 *   <li><b>Conflict:</b> 4.B stubbed these ids (FCBlockStub / FCItemStub / inert vanilla classes), retired them to
 *       legacy-only ids with a BTW successor, or removed their recipe (FCRecipes.RemoveVanillaRecipes, Server
 *       FCRecipes.java:5112-5776). The port re-exposed every one through 1.20.1 data and tabs.</li>
 *   <li><b>Pattern:</b> C (rebalance by subtraction), justified by the ruling above. What BTW <b>adds</b>: every
 *       retired (T2) id points at its BTW successor wherever 1.20.1 would hand one out (loot, barter, trades), and
 *       the stubbed (T1) ids leave their roles to the BTW blocks that replaced them in 4.B (BTW hopper
 *       {@code block_225}, block dispenser {@code block_233}, detector {@code block_238}, chest {@code block_1035}).</li>
 *   <li><b>Dual-sourced?</b> No, by ruling. Existing and command-placed blocks are left alone (ops keep
 *       {@code /give} and {@code /setblock}, as in 4.B).</li>
 *   <li><b>Alt rejected:</b> per-feature lists in each hook (recipes, loot, tabs, structures). Rejected: five lists
 *       drift apart; every hook reads this table instead.</li>
 * </ul>
 *
 * <p>Tiers (research report removed_blocks.md, section 0):</p>
 * <ul>
 *   <li><b>T1 stubbed</b>: recipes removed, loot filtered, hidden from creative, generated structures substitute
 *       a 4.B block, string cannot place tripwire, generated TNT/hopper minecarts become plain minecarts.</li>
 *   <li><b>T2 retired</b>: recipes removed, hidden from creative, loot substitutes the BTW successor (filtered when
 *       it does not resolve). Generated T2 blocks stay legacy blocks: FC's legacy drop rules convert them when
 *       broken, which is 4.B behaviour.</li>
 *   <li><b>T3 recipe-only</b>: recipes removed, loot filtered, still listed in creative (4.B listed them).</li>
 * </ul>
 *
 * <p>Ingredient successors, any tier ("integrate, don't disable"; maintainer decision D-B (a)): a non-FC recipe
 * ingredient that accepts a removed id also accepts the BTW items its row names
 * ({@code btw.forge.RetiredIngredientWidening}). T2 rows name FC's legacy conversion target; the furnace (T3) names
 * the brick oven that replaced it, and the tripwire hook (T1) the FC iron nugget FC's stoked crucible values it at.
 * Only the ingredient role is substituted: every removed id stays removed as a result.</p>
 *
 * <p>Consumers: {@code btw.forge.FcRecipeRemovals} (recipes), {@code FCRemovedContentLootModifier} (loot),
 * {@link RemovedCreativeTabs} (tabs), {@link RemovedContentEvents} (placement, minecarts, trades),
 * {@code StructureTemplateRemovedMixin} / {@code StructurePieceRemovedMixin} (structures). All lookups are
 * immutable after class init, so worldgen worker threads may read them.</p>
 *
 * <p>The music-disc, enchanted-book and legacy-wheat creative questions (CT6) are deliberately not rows here.</p>
 */
// FCMOD-INTEGRATION(1.20.1): stays-removed: one table drives recipes, loot, tabs, structures, placement and entities
public final class RemovedContent {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RemovedContent");

    /** FC's core class; its static fields hold the BTW successor blocks and items (not on the compile classpath). */
    private static final String FC_CORE = "net.minecraft.src.btw.core.FCBetterThanWolves";

    public enum Tier {
        /** FC turned the id into a stub or inert class, hid it and removed its recipe. */
        T1_STUBBED,
        /** The vanilla id became legacy-only and hidden; a BTW block or item took over its role. */
        T2_RETIRED,
        /** Recipe removed and nothing makes the id, but 4.B still listed it in creative. */
        T3_RECIPE_ONLY
    }

    /** What a generated structure places instead of a T1 block. */
    private record StructureRule(UnaryOperator<BlockState> substitute, boolean keepBlockEntityData) {}

    /** One row of the table. */
    public static final class Entry {
        /** 1.20.1 registry path, for logs. */
        public final String id;
        public final Tier tier;
        /** The 1.20.1 item, or null for a block-only row (tripwire). */
        public final Item item;
        /** The 1.20.1 blocks this row covers (empty for item-only rows). */
        public final List<Block> blocks;
        /** Expected 1.5.2 block ids of {@link #blocks} (ProxyRegistry.getBlockId), checked by {@link #audit}. */
        final int[] legacyBlockIds;
        /** Expected 1.5.2 item id of a standalone item (ProxyRegistry.getItemId), 0 for block items. */
        final int legacyItemId;
        /** A removed entity (T1 minecarts), replaced by a plain minecart when spawned fresh. */
        public final EntityType<?> entity;
        /** T2: the FCBetterThanWolves field of the BTW successor handed out by loot and trades, else null. */
        final String successorField;
        /**
         * Any tier: the FCBetterThanWolves fields of the BTW items a non-FC recipe ingredient that accepts this id must
         * also accept (RetiredIngredientWidening). T2: FC's own legacy conversion target
         * (FCRecipes.AddLegacyConversionRecipes, Server FCRecipes.java:5080-5102; the torch at AddTorchRecipes
         * :793-795). T1/T3: the BTW item that took the id's place, cited in the row (tripwire hook, furnace). Else
         * empty.
         */
        final List<String> ingredientSuccessorFields;
        /** T1: structure substitution, else null. */
        final StructureRule structure;
        /** The 4.B evidence for the row. */
        public final String citation;

        private Entry(String id, Tier tier, Item item, List<Block> blocks, int[] legacyBlockIds, int legacyItemId,
                      EntityType<?> entity, String successorField, List<String> ingredientSuccessorFields,
                      StructureRule structure, String citation) {
            this.id = id;
            this.tier = tier;
            this.item = item;
            this.blocks = blocks;
            this.legacyBlockIds = legacyBlockIds;
            this.legacyItemId = legacyItemId;
            this.entity = entity;
            this.successorField = successorField;
            this.ingredientSuccessorFields = ingredientSuccessorFields;
            this.structure = structure;
            this.citation = citation;
        }

        /** 4.B hid T1 and T2 ids from creative and listed T3 ones (removed_blocks.md section 3). */
        public boolean hiddenInCreative() {
            return tier != Tier.T3_RECIPE_ONLY;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------------------------------------

    private static final List<Entry> TABLE = List.of(
            // ---- T1: stubbed ------------------------------------------------------------------------
            t1("hopper", Items.HOPPER, List.of(Blocks.HOPPER), new int[]{154}, 0, null,
                    toAir(),
                    "FCBlockVanillaHopper(154): no TE, no GUI, null tab (Server FCBlockVanillaHopper.java:24-118);"
                            + " recipe removed FCRecipes.java:5298; role taken by the BTW hopper block_225"),
            t1("comparator", Items.COMPARATOR, List.of(Blocks.COMPARATOR), new int[]{149, 150}, 404, null,
                    new StructureRule(s -> Blocks.REPEATER.defaultBlockState()
                            .setValue(RepeaterBlock.FACING, s.getValue(RepeaterBlock.FACING))
                            .setValue(RepeaterBlock.DELAY, 1), false),
                    "FCItemStub(148) = item 404 (vanilla/server Item.java:388); FCBlockComparator drops nothing;"
                            + " recipe removed FCRecipes.java:5314"),
            t1("daylight_detector", Items.DAYLIGHT_DETECTOR, List.of(Blocks.DAYLIGHT_DETECTOR), new int[]{151}, 0, null,
                    toAir(),
                    "FCBlockDaylightDetector(151): no power, no TE, null tab (Server FCBlockDaylightDetector.java:25-57);"
                            + " recipe removed FCRecipes.java:5289; role taken by the BTW detector block_238"),
            t1("dropper", Items.DROPPER, List.of(Blocks.DROPPER), new int[]{158}, 0, null,
                    copyInto(Blocks.DISPENSER, true),
                    "FCBlockStub(158) (vanilla/server Block.java:398); recipe removed FCRecipes.java:5251;"
                            + " 4.B keeps the dispenser (and adds the BTW block dispenser block_233)"),
            t1("trapped_chest", Items.TRAPPED_CHEST, List.of(Blocks.TRAPPED_CHEST), new int[]{146}, 0, null,
                    copyInto(Blocks.CHEST, true),
                    "FCBlockStub(146) (vanilla/server Block.java:386); recipe removed FCRecipes.java:5337"),
            t1("light_weighted_pressure_plate", Items.LIGHT_WEIGHTED_PRESSURE_PLATE,
                    List.of(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE), new int[]{147}, 0, null,
                    toDefault(Blocks.STONE_PRESSURE_PLATE),
                    "FCBlockStub(147) (vanilla/server Block.java:387); recipe removed FCRecipes.java:5284"),
            t1("heavy_weighted_pressure_plate", Items.HEAVY_WEIGHTED_PRESSURE_PLATE,
                    List.of(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE), new int[]{148}, 0, null,
                    toDefault(Blocks.STONE_PRESSURE_PLATE),
                    "FCBlockStub(148) (vanilla/server Block.java:388); recipe removed FCRecipes.java:5279"),
            t1("activator_rail", Items.ACTIVATOR_RAIL, List.of(Blocks.ACTIVATOR_RAIL), new int[]{157}, 0, null,
                    copyInto(Blocks.RAIL, false),
                    "FCBlockStub(157) (vanilla/server Block.java:397); recipe removed FCRecipes.java:5306"),
            t1("tnt_minecart", Items.TNT_MINECART, List.of(), new int[0], 407, EntityType.TNT_MINECART,
                    null,
                    "FCItemStub(151) = item 407 (vanilla/server Item.java:404); recipe removed FCRecipes.java:5323"),
            t1("hopper_minecart", Items.HOPPER_MINECART, List.of(), new int[0], 408, EntityType.HOPPER_MINECART,
                    null,
                    "FCItemStub(152) = item 408 (vanilla/server Item.java:405); recipe removed FCRecipes.java:5330"),
            // FCMOD-INTEGRATION(1.20.1): recipes: the crossbow (and any modded recipe) takes an FC iron nugget for it
            t1("tripwire_hook", Items.TRIPWIRE_HOOK, List.of(Blocks.TRIPWIRE_HOOK), new int[]{131}, 0, null,
                    toAir(), List.of("fcItemNuggetIron"),
                    "FCBlockTripWireSource(131): null tab (Server FCBlockTripWireSource.java:23-30); recipe removed"
                            + " FCRecipes.java:5450; FC stripped the pyramid traps"
                            + " (ComponentScatteredFeatureJunglePyramid); ingredient successor: one FC iron nugget, FC's"
                            + " own value for a hook (the stoked crucible renders a tripwire hook into exactly one,"
                            + " FCRecipes.java:4480-4485)"),
            t1("tripwire", null, List.of(Blocks.TRIPWIRE), new int[]{132}, 0, null,
                    toAir(),
                    "string no longer places it: 1.5.2 ItemReed(31, Block.tripWire) became a plain Item"
                            + " (vanilla/server Item.java:107, :119)"),

            // ---- T2: retired id ---------------------------------------------------------------------
            t2("crafting_table", Items.CRAFTING_TABLE, List.of(Blocks.CRAFTING_TABLE), new int[]{58}, 0,
                    "fcBlockWorkbench",
                    "FCBlockWorkbench(58) null tab (Common FCBlockWorkbench.java:40); legacy item -> fcBlockWorkbench"
                            + " 1034 (FCBetterThanWolves.java:1909); recipe removed FCRecipes.java:5735; the work stump"
                            + " (FCBlockLog.java:80-95) is the 4.B crafting surface"),
            t2("chest", Items.CHEST, List.of(Blocks.CHEST), new int[]{54}, 0,
                    "fcBlockChest",
                    "Block.chest setCreativeTab(null) (vanilla/server Block.java:220); legacy item -> fcBlockChest 1035"
                            + " (FCBetterThanWolves.java:1910); recipe removed FCRecipes.java:5741"),
            t2("ladder", Items.LADDER, List.of(Blocks.LADDER), new int[]{65}, 0,
                    "fcBlockLadder",
                    "FCBlockLegacyLadder null tab (:29), drops the BTW ladder (FCBlockLadderBase.java:42-45); legacy"
                            + " item -> fcBlockLadder 1051 (FCBetterThanWolves.java:1916); recipe removed FCRecipes.java:5681"),
            t2("torch", Items.TORCH, List.of(Blocks.TORCH, Blocks.WALL_TORCH), new int[]{50}, 0,
                    "fcBlockTorchFiniteUnlit", List.of("fcBlockTorchNetherBurning", "fcBlockTorchNetherUnlit"),
                    "legacy torch (50) has no tab and drops the nether-coal torch 1057 (FCBlockTorchLegacy.java:35-38);"
                            + " FC converts the item the same way (FCRecipes.java:793-795); recipes removed"
                            + " FCRecipes.java:5219, 5226; loot successor = unlit finite torch 1025 (4.B mineshafts place"
                            + " unlit finite torches, ComponentMineshaftCorridor.java:245-306); as an ingredient it stands"
                            + " for the nether-coal torch, lit (1057) or unlit (1033), never the finite torch, which burns"
                            + " out (FCBlockTorchFiniteBurning) and would become a permanent light"),
            t2("snow_block", Items.SNOW_BLOCK, List.of(Blocks.SNOW_BLOCK), new int[]{80}, 0,
                    "fcBlockSnowSolid",
                    "FCBlockSnowLegacy null tab (:36); legacy item -> fcBlockSnowSolid 1049"
                            + " (FCBetterThanWolves.java:1918); recipe removed FCRecipes.java:5701"),
            t2("clay", Items.CLAY, List.of(Blocks.CLAY), new int[]{82}, 0,
                    "fcBlockUnfiredClay",
                    "FCBlockClay null tab (:33); legacy item -> fcBlockUnfiredClay 1038 (FCBetterThanWolves.java:1914);"
                            + " recipe removed FCRecipes.java:5669"),
            t2("cobweb", Items.COBWEB, List.of(Blocks.COBWEB), new int[]{30}, 0,
                    "fcBlockWeb",
                    "FCBlockWeb(30) null tab (:39); legacy item -> fcBlockWeb 1037 (FCBetterThanWolves.java:1912)"),
            t2("nether_brick", Items.NETHER_BRICK, List.of(), new int[0], 405,
                    "fcItemNetherBrick",
                    "item 405 has no tab (vanilla/server Item.java:389); netherrack smelting removed"
                            + " FCRecipes.java:5771; BTW nether brick from nether sludge (FCRecipes.java:3026)"),

            // ---- T3: recipe-only --------------------------------------------------------------------
            t3("wooden_sword", Items.WOODEN_SWORD, List.of(), new int[0], 268,
                    "recipe removed FCRecipes.java:5485-5517; still in the 4.B Combat tab"),
            t3("wooden_shovel", Items.WOODEN_SHOVEL, List.of(), new int[0], 269,
                    "recipe removed FCRecipes.java:5485-5517; still in the 4.B Tools tab (FCItemTool.java:51)"),
            t3("wooden_pickaxe", Items.WOODEN_PICKAXE, List.of(), new int[0], 270,
                    "recipe removed FCRecipes.java:5485-5517; still in the 4.B Tools tab (FCItemTool.java:51)"),
            t3("wooden_axe", Items.WOODEN_AXE, List.of(), new int[0], 271,
                    "recipe removed FCRecipes.java:5485-5517; still in the 4.B Tools tab (FCItemTool.java:51)"),
            t3("wooden_hoe", Items.WOODEN_HOE, List.of(), new int[0], 290,
                    "recipe removed FCRecipes.java:5485-5517; still in the 4.B Tools tab"),
            t3("stone_hoe", Items.STONE_HOE, List.of(), new int[0], 291,
                    "recipe removed FCRecipes.java:5557; still in the 4.B Tools tab"),
            t3("snow", Items.SNOW, List.of(Blocks.SNOW), new int[]{78}, 0,
                    "snow-layer recipe removed FCRecipes.java:5246; FCBlockSnowCover drops snowballs; in the 4.B"
                            + " Decorations tab (FCBlockGroundCover.java:38)"),
            // FCMOD-INTEGRATION(1.20.1): recipes: blast furnace, smoker and furnace minecart take the brick oven for it
            t3("furnace", Items.FURNACE, List.of(Blocks.FURNACE), new int[]{61}, 0,
                    List.of("fcBlockFurnaceBrickIdle"),
                    "recipe removed FCRecipes.java:5688; silk-touch salvage only (FCBlockFurnace.java:47-75, 137-139);"
                            + " in the 4.B Decorations tab (FCBlockFurnace.java:34-37); successor: brick oven 1023"
                            + " (FCRecipes.java:349-353), also as an ingredient")
    );

    // ------------------------------------------------------------------------------------------------
    // Indexes (immutable after class init)
    // ------------------------------------------------------------------------------------------------

    private static final Map<Item, Entry> BY_ITEM;
    private static final Map<Block, Entry> BY_BLOCK;
    private static final Map<EntityType<?>, Entry> BY_ENTITY;

    static {
        Map<Item, Entry> byItem = new IdentityHashMap<>();
        Map<Block, Entry> byBlock = new IdentityHashMap<>();
        Map<EntityType<?>, Entry> byEntity = new IdentityHashMap<>();
        for (Entry e : TABLE) {
            if (e.item != null && e.item != Items.AIR) byItem.put(e.item, e);
            for (Block b : e.blocks) byBlock.put(b, e);
            if (e.entity != null) byEntity.put(e.entity, e);
        }
        BY_ITEM = Collections.unmodifiableMap(byItem);
        BY_BLOCK = Collections.unmodifiableMap(byBlock);
        BY_ENTITY = Collections.unmodifiableMap(byEntity);
    }

    private RemovedContent() {}

    /** Every row, in table order. */
    public static List<Entry> entries() {
        return TABLE;
    }

    /** The row of a removed item, or null. */
    public static Entry forItem(Item item) {
        return item == null ? null : BY_ITEM.get(item);
    }

    /** The row of a removed block, or null. */
    public static Entry forBlock(Block block) {
        return block == null ? null : BY_BLOCK.get(block);
    }

    // ---- recipes ----

    /** True if no recipe of any type or namespace may produce this item (every tier). */
    public static boolean isRemovedRecipeResult(Item item) {
        return forItem(item) != null;
    }

    // ---- creative ----

    /** True if the item is hidden from every creative tab (T1 and T2; T3 stays listed, as in 4.B). */
    public static boolean hideInCreative(Item item) {
        Entry e = forItem(item);
        return e != null && e.hiddenInCreative();
    }

    // ---- loot and trades ----

    /** True if the stack is a removed item that loot or trades must not hand out as is. */
    public static boolean isRemovedLoot(ItemStack stack) {
        return stack != null && !stack.isEmpty() && forItem(stack.getItem()) != null;
    }

    /**
     * Appends what loot hands out instead of {@code stack}: the stack itself when it is not removed; nothing for
     * T1 and T3 (FC's own loot filters remove, ComponentMineshaftCorridor.FilterChestMinecartContents,
     * WorldGenDungeons.FilterChestContentsForDepth); for T2 the BTW successor at the same count, split into full
     * stacks, or nothing when the successor does not resolve.
     */
    public static void addLootReplacement(ItemStack stack, List<ItemStack> out) {
        if (!isRemovedLoot(stack)) {
            out.add(stack);
            return;
        }
        Item successor = successorItem(forItem(stack.getItem()));
        if (successor == null) return;
        int remaining = stack.getCount();
        int max = Math.max(1, new ItemStack(successor).getMaxStackSize());
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            out.add(new ItemStack(successor, n));
            remaining -= n;
        }
    }

    /** The trade or loot result that replaces {@code stack}: itself, a successor stack, or EMPTY (filtered). */
    public static ItemStack replacementFor(ItemStack stack) {
        if (!isRemovedLoot(stack)) return stack;
        Item successor = successorItem(forItem(stack.getItem()));
        if (successor == null) return ItemStack.EMPTY;
        return new ItemStack(successor, Math.min(stack.getCount(), new ItemStack(successor).getMaxStackSize()));
    }

    /** Resolved T2 successors (FC field -> 1.20.1 item). A miss is not cached (FC init may not have run yet). */
    private static final Map<String, Item> SUCCESSORS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> SUCCESSOR_MISSES_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The 1.20.1 item of a T2 row's BTW successor, or null (T1/T3 rows, or unresolved). */
    public static Item successorItem(Entry e) {
        if (e == null || e.tier != Tier.T2_RETIRED || e.successorField == null) return null;
        return successorForField(e.successorField);
    }

    /**
     * The BTW items a non-FC recipe ingredient that accepts the removed {@code item} must also accept
     * ({@link Entry#ingredientSuccessorFields}, any tier); empty for items not in the table, rows without an
     * ingredient successor and successors that do not resolve.
     */
    // FCMOD-INTEGRATION(1.20.1): recipes: ingredient successors apply to every tier (D-B (a)), not only to T2
    public static List<Item> ingredientSuccessors(Item item) {
        Entry e = forItem(item);
        if (e == null || e.ingredientSuccessorFields.isEmpty()) return List.of();
        List<Item> out = new ArrayList<>(e.ingredientSuccessorFields.size());
        for (String field : e.ingredientSuccessorFields) {
            Item successor = successorForField(field);
            if (successor != null && !out.contains(successor)) out.add(successor);
        }
        return out;
    }

    private static Item successorForField(String field) {
        Item cached = SUCCESSORS.get(field);
        if (cached != null) return cached;
        Optional<Item> resolved = resolveSuccessor(field);
        resolved.ifPresent(item -> SUCCESSORS.put(field, item));
        return resolved.orElse(null);
    }

    /**
     * Reads FCBetterThanWolves.&lt;field&gt; (a btw.modern Block or Item; FC classes are not on the bridge's compile
     * classpath, as in BonusChestFeatureMixin) and maps its legacy id to the registered 1.20.1 item.
     */
    private static Optional<Item> resolveSuccessor(String field) {
        String problem;
        try {
            Object value = Class.forName(FC_CORE).getField(field).get(null);
            Item item = null;
            if (value instanceof btw.modern.Block fcBlock) {
                Block block = ProxyRegistry.getModernBlock(fcBlock.blockID);
                if (block != null) item = block.asItem();
            } else if (value instanceof btw.modern.Item fcItem) {
                item = ProxyRegistry.getModernItem(fcItem.itemID);
            }
            if (item != null && item != Items.AIR) return Optional.of(item);
            problem = "no registered 1.20.1 item (field value " + value + ")";
        } catch (Throwable t) {
            problem = t.toString();
        }
        if (SUCCESSOR_MISSES_LOGGED.add(field)) {
            LOGGER.warn("[REMOVED] BTW successor {} could not be resolved ({}); its row is filtered instead", field,
                    problem);
        }
        return Optional.empty();
    }

    // ---- structures ----

    /**
     * The state a generated structure places instead of a T1 block, or null when the state is not substituted.
     * T2 and T3 blocks are never substituted: FC's legacy drop rules convert generated T2 blocks when broken.
     */
    public static BlockState structureSubstitute(BlockState state) {
        if (state == null) return null;
        Entry e = BY_BLOCK.get(state.getBlock());
        if (e == null || e.structure == null) return null;
        try {
            return e.structure.substitute().apply(state);
        } catch (Throwable t) {
            // a modded state without the expected properties: fall back to air rather than place the removed block
            return Blocks.AIR.defaultBlockState();
        }
    }

    /** True if a template's block-entity data still fits the substitute (chest, dispenser); else it is dropped. */
    public static boolean structureKeepsBlockEntityData(BlockState original) {
        Entry e = original == null ? null : BY_BLOCK.get(original.getBlock());
        return e != null && e.structure != null && e.structure.keepBlockEntityData();
    }

    // ---- placement ----

    /**
     * True if using this item on a block would place a T1 block although the item itself is not a removed item:
     * string (an ItemNameBlockItem of the tripwire block in 1.20.1, Items.java:831), which 4.B made a plain item.
     * A removed item's own placement (an op's /give) is left alone, as in 4.B.
     */
    public static boolean placesRemovedBlock(Item item) {
        if (!(item instanceof BlockItem bi) || forItem(item) != null) return false;
        Entry e = BY_BLOCK.get(bi.getBlock());
        return e != null && e.tier == Tier.T1_STUBBED;
    }

    // ---- entities ----

    /** True if a freshly spawned entity of this type is replaced by a plain minecart (T1 minecarts). */
    public static boolean isRemovedEntity(EntityType<?> type) {
        return type != null && BY_ENTITY.containsKey(type);
    }

    // ------------------------------------------------------------------------------------------------
    // Startup audit
    // ------------------------------------------------------------------------------------------------

    /** FC classes that mark a vanilla block id as stubbed or inert in 4.B (removed_blocks.md section 1.4). */
    private static final Set<String> FC_STUB_BLOCK_CLASSES = Set.of(
            "FCBlockStub", "FCBlockVanillaHopper", "FCBlockDaylightDetector", "FCBlockTripWireSource");
    private static final String FC_STUB_ITEM_CLASS = "FCItemStub";

    /**
     * Cross-checks the table against the live FC objects; run after FC init (BTWLifecycle.initialize, during
     * RegisterEvent/BLOCKS), e.g. from FMLCommonSetupEvent. Logs one INFO summary and a WARN per mismatch:
     * <ul>
     *   <li>every btw.modern.Block.blocksList entry of an FC stub/inert class must be a T1 row's legacy block id,
     *       and every Item.itemsList entry of FCItemStub a T1 row's legacy item id (class names, not a null-tab
     *       test: the port's vanilla initialisers set no tabs, STUB_CATALOGUE.md:3154-3159);</li>
     *   <li>every row's blocks and standalone item map to the 1.5.2 ids the table cites;</li>
     *   <li>every T2 successor resolves to a registered item.</li>
     * </ul>
     */
    public static void audit() {
        int warnings = 0;
        try {
            Set<Integer> t1Blocks = new LinkedHashSet<>();
            Set<Integer> t1Items = new LinkedHashSet<>();
            for (Entry e : TABLE) {
                if (e.tier != Tier.T1_STUBBED) continue;
                for (int id : e.legacyBlockIds) t1Blocks.add(id);
                if (e.legacyItemId > 0) t1Items.add(e.legacyItemId);
            }

            btw.modern.Block[] fcBlocks = btw.modern.Block.blocksList;
            for (int id = 1; id < fcBlocks.length; id++) {
                btw.modern.Block b = fcBlocks[id];
                if (b == null || !FC_STUB_BLOCK_CLASSES.contains(b.getClass().getSimpleName())) continue;
                if (!t1Blocks.contains(id)) {
                    warnings++;
                    LOGGER.warn("[REMOVED-AUDIT] FC block {} is {} (stubbed/inert in 4.B) but no T1 row covers it",
                            id, b.getClass().getSimpleName());
                }
            }
            btw.modern.Item[] fcItems = btw.modern.Item.itemsList;
            for (int id = 1; id < fcItems.length; id++) {
                btw.modern.Item it = fcItems[id];
                if (it == null || !FC_STUB_ITEM_CLASS.equals(it.getClass().getSimpleName())) continue;
                if (!t1Items.contains(id)) {
                    warnings++;
                    LOGGER.warn("[REMOVED-AUDIT] FC item {} is FCItemStub (stubbed in 4.B) but no T1 row covers it", id);
                }
            }

            for (Entry e : TABLE) {
                for (Block b : e.blocks) {
                    int mapped = ProxyRegistry.getBlockId(b);
                    boolean expected = false;
                    for (int id : e.legacyBlockIds) expected |= id == mapped;
                    if (!expected) {
                        warnings++;
                        LOGGER.warn("[REMOVED-AUDIT] {} ({}): block {} maps to legacy id {}, table cites {}",
                                e.id, e.tier, b, mapped, java.util.Arrays.toString(e.legacyBlockIds));
                    }
                }
                if (e.legacyItemId > 0 && e.item != null) {
                    int mapped = ProxyRegistry.getItemId(e.item);
                    if (mapped != e.legacyItemId) {
                        warnings++;
                        LOGGER.warn("[REMOVED-AUDIT] {} ({}): item maps to legacy id {}, table cites {}",
                                e.id, e.tier, mapped, e.legacyItemId);
                    }
                }
                if (e.tier == Tier.T2_RETIRED && e.successorField != null && successorItem(e) == null) {
                    warnings++;
                    LOGGER.warn("[REMOVED-AUDIT] {}: BTW successor {} does not resolve; loot filters it instead",
                            e.id, e.successorField);
                }
                for (String field : e.ingredientSuccessorFields) {
                    if (successorForField(field) == null) {
                        warnings++;
                        LOGGER.warn("[REMOVED-AUDIT] {}: ingredient successor {} does not resolve; recipes that need"
                                + " {} are not widened with it", e.id, field, e.id);
                    }
                }
            }

            int t1 = 0, t2 = 0, t3 = 0;
            for (Entry e : TABLE) {
                switch (e.tier) {
                    case T1_STUBBED -> t1++;
                    case T2_RETIRED -> t2++;
                    case T3_RECIPE_ONLY -> t3++;
                }
            }
            LOGGER.info("[REMOVED-AUDIT] stays-removed table: {} T1, {} T2, {} T3 rows; FC stub blocks {} and items {}"
                    + " covered; {} warning(s)", t1, t2, t3, t1Blocks, t1Items, warnings);
        } catch (Throwable t) {
            LOGGER.error("[REMOVED-AUDIT] audit failed", t);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Row builders and structure rules
    // ------------------------------------------------------------------------------------------------

    private static Entry t1(String id, Item item, List<Block> blocks, int[] legacyBlocks, int legacyItem,
                            EntityType<?> entity, StructureRule structure, String citation) {
        return t1(id, item, blocks, legacyBlocks, legacyItem, entity, structure, List.of(), citation);
    }

    /** A T1 row whose ingredient role passes to the BTW items {@code ingredientSuccessorFields}. */
    private static Entry t1(String id, Item item, List<Block> blocks, int[] legacyBlocks, int legacyItem,
                            EntityType<?> entity, StructureRule structure, List<String> ingredientSuccessorFields,
                            String citation) {
        return new Entry(id, Tier.T1_STUBBED, item, blocks, legacyBlocks, legacyItem, entity, null,
                ingredientSuccessorFields, structure, citation);
    }

    /** A T2 row whose ingredient successor is its loot successor (FC's legacy conversion target). */
    private static Entry t2(String id, Item item, List<Block> blocks, int[] legacyBlocks, int legacyItem,
                            String successorField, String citation) {
        return t2(id, item, blocks, legacyBlocks, legacyItem, successorField, List.of(successorField), citation);
    }

    private static Entry t2(String id, Item item, List<Block> blocks, int[] legacyBlocks, int legacyItem,
                            String successorField, List<String> ingredientSuccessorFields, String citation) {
        return new Entry(id, Tier.T2_RETIRED, item, blocks, legacyBlocks, legacyItem, null, successorField,
                ingredientSuccessorFields, null, citation);
    }

    private static Entry t3(String id, Item item, List<Block> blocks, int[] legacyBlocks, int legacyItem,
                            String citation) {
        return t3(id, item, blocks, legacyBlocks, legacyItem, List.of(), citation);
    }

    /** A T3 row whose ingredient role passes to the BTW items {@code ingredientSuccessorFields}. */
    private static Entry t3(String id, Item item, List<Block> blocks, int[] legacyBlocks, int legacyItem,
                            List<String> ingredientSuccessorFields, String citation) {
        return new Entry(id, Tier.T3_RECIPE_ONLY, item, blocks, legacyBlocks, legacyItem, null, null,
                ingredientSuccessorFields, null, citation);
    }

    private static StructureRule toAir() {
        return new StructureRule(s -> Blocks.AIR.defaultBlockState(), false);
    }

    private static StructureRule toDefault(Block target) {
        return new StructureRule(s -> target.defaultBlockState(), false);
    }

    /**
     * The target's default state with every property of the source that the target shares by name and accepts
     * (FACING, TYPE, WATERLOGGED, rail SHAPE, TRIGGERED, ...). {@code keepData}: the template's block-entity NBT
     * still fits the target's block entity (trapped chest -> chest, dropper -> dispenser share the format).
     */
    private static StructureRule copyInto(Block target, boolean keepData) {
        return new StructureRule(s -> copyProperties(s, target.defaultBlockState()), keepData);
    }

    private static BlockState copyProperties(BlockState from, BlockState to) {
        List<Property<?>> props = new ArrayList<>(from.getProperties());
        for (Property<?> p : props) {
            Property<?> target = to.getBlock().getStateDefinition().getProperty(p.getName());
            if (target != null) to = copyValue(from, p, to, target);
        }
        return to;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState copyValue(BlockState from, Property source, BlockState to, Property target) {
        Comparable value = from.getValue(source);
        if (target.getValueClass().isInstance(value) && target.getPossibleValues().contains(value)) {
            return to.setValue(target, value);
        }
        return to;
    }
}
