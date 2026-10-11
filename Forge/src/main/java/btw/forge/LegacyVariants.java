package btw.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item-level (legacy id, damage) &lt;-&gt; 1.20.1 item table for the 1.5.2 subtypes that 1.13
 * flattened into separate items. In 1.5.2 a stack's subtype is (itemID, damage): wool colour = meta,
 * wood species = meta &amp; 3, dye d = colour 15-d, skull 0-4, spawn egg = EntityList id. The block-id
 * map in {@link ProxyRegistry} collapses every variant onto one legacy id, so without this table
 * an orange wool round-tripped through FC came back as an arbitrary colour and a spawn egg was
 * deleted.
 *
 * <p>Consulted by {@link ItemStackHelper#toFcStack} / {@link ItemStackHelper#toMcStack} before the
 * ProxyRegistry fallbacks. Holder-style lazy class init (first conversion), thread-safe through the
 * JVM; the maps are read-only afterwards, so the render thread and the server thread may both read
 * them. Initialisation never throws: a failure leaves a partial table and the old mapping paths.</p>
 */
final class LegacyVariants {

    private static final Logger LOGGER = LogManager.getLogger("BTW-LegacyVariants");

    /** 1.5.2 Item.monsterPlacer. */
    static final int SPAWN_EGG_ID = 383;

    private static final Map<Item, int[]> TO_LEGACY = new IdentityHashMap<>();
    private static final Map<Long, Item> TO_MODERN = new HashMap<>();
    /** 1.5.2 EntityList name -> modern entity registry path (reverse of ItemStackHelper.LEGACY_ENTITY_NAMES). */
    private static final Map<String, String> MODERN_PATH_BY_LEGACY_NAME = new HashMap<>();
    /**
     * entity id -> egg; Items.AIR marks an id with no modern egg (ConcurrentHashMap takes no nulls).
     * A miss is cached only after a complete scan of a filled EntityList.
     */
    private static final Map<Integer, Item> EGG_CACHE = new ConcurrentHashMap<>();

    private LegacyVariants() {}

    private static long key(int id, int meta) {
        return ((long) id << 16) | (meta & 0xFFFF);
    }

    /** Both directions; the first registration of a key wins on each side. */
    private static void both(int id, int meta, ItemLike m) {
        Item it = m.asItem();
        if (it == null || it == Items.AIR) return;
        TO_MODERN.putIfAbsent(key(id, meta), it);
        TO_LEGACY.putIfAbsent(it, new int[]{id, meta});
    }

    /** Modern-only spelling of a 1.5.2 subtype: MC -> FC only (FC -> MC uses the canonical pick). */
    private static void rev(ItemLike m, int id, int meta) {
        Item it = m.asItem();
        if (it != null && it != Items.AIR) TO_LEGACY.putIfAbsent(it, new int[]{id, meta});
    }

    /** FC -> MC only (the modern item already has its own legacy id elsewhere). */
    private static void fwd(int id, int meta, ItemLike m) {
        Item it = m.asItem();
        if (it != null && it != Items.AIR) TO_MODERN.putIfAbsent(key(id, meta), it);
    }

    static {
        try {
            // 1.5.2 subtype tables: BlockWood.java:19, BlockSapling.java:20, BlockLog.java:21,
            // BlockLeaves.java:20, BlockWoodSlab.java:21 (wood species = meta & 3)
            int[] woodIds = {5, 6, 17, 18, 126};
            ItemLike[][] wood = {
                    {Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS},
                    {Blocks.OAK_SAPLING, Blocks.SPRUCE_SAPLING, Blocks.BIRCH_SAPLING, Blocks.JUNGLE_SAPLING},
                    {Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG},
                    {Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES},
                    {Blocks.OAK_SLAB, Blocks.SPRUCE_SLAB, Blocks.BIRCH_SLAB, Blocks.JUNGLE_SLAB}};
            for (int r = 0; r < woodIds.length; r++) {
                for (int m = 0; m < 4; m++) both(woodIds[r], m, wood[r][m]);
            }

            // BlockSandStone.java:18 {default, chiseled, smooth}; 1.5.2 "smooth" is 1.20.1 cut sandstone
            both(24, 0, Blocks.SANDSTONE);
            both(24, 1, Blocks.CHISELED_SANDSTONE);
            both(24, 2, Blocks.CUT_SANDSTONE);
            rev(Blocks.SMOOTH_SANDSTONE, 24, 2);

            // BlockTallGrass.java:20 {deadbush, tallgrass, fern}; dead bush keeps its own id 32 MC -> FC
            fwd(31, 0, Blocks.DEAD_BUSH);
            both(31, 1, Blocks.GRASS);
            both(31, 2, Blocks.FERN);

            // BlockCloth: wool meta == colour == DyeColor id
            ItemLike[] wool = {Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL, Blocks.LIGHT_BLUE_WOOL,
                    Blocks.YELLOW_WOOL, Blocks.LIME_WOOL, Blocks.PINK_WOOL, Blocks.GRAY_WOOL,
                    Blocks.LIGHT_GRAY_WOOL, Blocks.CYAN_WOOL, Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL,
                    Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL};
            for (int m = 0; m < 16; m++) both(35, m, wool[m]);

            // BlockStep.java:21 {stone, sand, wood, cobble, brick, smoothStoneBrick, netherBrick, quartz}
            both(44, 0, Blocks.SMOOTH_STONE_SLAB);
            rev(Blocks.STONE_SLAB, 44, 0);
            both(44, 1, Blocks.SANDSTONE_SLAB);
            both(44, 2, Blocks.PETRIFIED_OAK_SLAB);
            both(44, 3, Blocks.COBBLESTONE_SLAB);
            both(44, 4, Blocks.BRICK_SLAB);
            both(44, 5, Blocks.STONE_BRICK_SLAB);
            both(44, 6, Blocks.NETHER_BRICK_SLAB);
            both(44, 7, Blocks.QUARTZ_SLAB);

            // BlockSilverfish.java:21 {stone, cobble, brick}
            both(97, 0, Blocks.INFESTED_STONE);
            both(97, 1, Blocks.INFESTED_COBBLESTONE);
            both(97, 2, Blocks.INFESTED_STONE_BRICKS);

            // BlockStoneBrick.java:18 {default, mossy, cracked, chiseled}
            both(98, 0, Blocks.STONE_BRICKS);
            both(98, 1, Blocks.MOSSY_STONE_BRICKS);
            both(98, 2, Blocks.CRACKED_STONE_BRICKS);
            both(98, 3, Blocks.CHISELED_STONE_BRICKS);

            // BlockWall.java:19 {normal, mossy}
            both(139, 0, Blocks.COBBLESTONE_WALL);
            both(139, 1, Blocks.MOSSY_COBBLESTONE_WALL);

            // BlockAnvil.java:19 {intact, slightlyDamaged, veryDamaged}
            both(145, 0, Blocks.ANVIL);
            both(145, 1, Blocks.CHIPPED_ANVIL);
            both(145, 2, Blocks.DAMAGED_ANVIL);

            // BlockQuartz.java:18 {default, chiseled, lines}
            both(155, 0, Blocks.QUARTZ_BLOCK);
            both(155, 1, Blocks.CHISELED_QUARTZ_BLOCK);
            both(155, 2, Blocks.QUARTZ_PILLAR);

            // ItemCoal (263:1 charcoal), ItemAppleGold (322:1 enchanted)
            both(263, 0, Items.COAL);
            both(263, 1, Items.CHARCOAL);
            both(322, 0, Items.GOLDEN_APPLE);
            both(322, 1, Items.ENCHANTED_GOLDEN_APPLE);

            // ItemDye.java:19 dyeColorNames: dye d is colour 15-d. The 1.5.2 "dyes" that are
            // separate items in 1.20.1 (ink sac, cocoa, lapis, bone meal) are the canonical
            // FC -> MC picks; the modern black/brown/blue/white dye items read as the same subtype.
            both(351, 0, Items.INK_SAC);
            rev(Items.BLACK_DYE, 351, 0);
            both(351, 3, Items.COCOA_BEANS);
            rev(Items.BROWN_DYE, 351, 3);
            both(351, 4, Items.LAPIS_LAZULI);
            rev(Items.BLUE_DYE, 351, 4);
            both(351, 15, Items.BONE_MEAL);
            rev(Items.WHITE_DYE, 351, 15);
            for (int d = 0; d < 16; d++) {
                DyeItem dye = DyeItem.byColor(DyeColor.byId(15 - d));
                if (dye != null) both(351, d, dye);
            }

            // ItemSkull.java:24 {skeleton, wither, zombie, char, creeper, infused}; 5 (FC infused
            // skull) has no modern item and rides the Damage tag on a skeleton skull.
            both(397, 0, Items.SKELETON_SKULL);
            both(397, 1, Items.WITHER_SKELETON_SKULL);
            both(397, 2, Items.ZOMBIE_HEAD);
            both(397, 3, Items.PLAYER_HEAD);
            both(397, 4, Items.CREEPER_HEAD);
        } catch (Throwable t) {
            LOGGER.error("[LEGACY-VARIANTS] table init failed ({} item entries kept); variant mapping degraded",
                    TO_LEGACY.size(), t);
        }

        try {
            for (Map.Entry<String, String> e : ItemStackHelper.LEGACY_ENTITY_NAMES.entrySet()) {
                MODERN_PATH_BY_LEGACY_NAME.putIfAbsent(e.getValue(), e.getKey());
            }
            // "cat" and "ocelot" both map to 1.5.2 "Ozelot"; the 1.5.2 mob is the ocelot.
            MODERN_PATH_BY_LEGACY_NAME.put("Ozelot", "ocelot");
        } catch (Throwable t) {
            LOGGER.error("[LEGACY-VARIANTS] spawn-egg name table init failed", t);
        }
    }

    /**
     * Returns {legacyId, damage} for a modern item that is a flattened 1.5.2 subtype, or null.
     * The returned array is shared; callers must not modify it.
     */
    static int[] toLegacy(Item item) {
        if (item == null) return null;
        return TO_LEGACY.get(item);
    }

    /** Returns the modern item for a 1.5.2 (id, damage) subtype, or null if the pair is not a table entry. */
    static Item toModern(int id, int meta) {
        if (id == SPAWN_EGG_ID) return eggFor(meta);
        return TO_MODERN.get(key(id, meta));
    }

    /**
     * Forward spawn egg: 1.5.2 ItemMonsterPlacer stores the EntityList id in the damage. Resolved
     * through btw.modern.EntityList.stringToIDMapping, the same map ItemStackHelper's MC -> FC egg
     * conversion uses, so the round trip is a bijection over the mobs in LEGACY_ENTITY_NAMES.
     */
    static Item eggFor(int entityId) {
        if (entityId <= 0) return null;
        Item cached = EGG_CACHE.get(entityId);
        if (cached != null) return cached == Items.AIR ? null : cached;
        try {
            Map<?, ?> ids = btw.modern.EntityList.stringToIDMapping;
            for (Map.Entry<?, ?> e : ids.entrySet()) {
                if (!(e.getValue() instanceof Integer v) || v != entityId) continue;
                if (!(e.getKey() instanceof String name)) continue;
                String path = MODERN_PATH_BY_LEGACY_NAME.get(name);
                if (path == null) continue;
                ResourceLocation rl = new ResourceLocation("minecraft", path);
                // ENTITY_TYPES is a defaulted registry (pig): check the key before getValue
                if (!net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(rl)) continue;
                net.minecraft.world.entity.EntityType<?> type =
                        net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(rl);
                Item egg = net.minecraftforge.common.ForgeSpawnEggItem.fromEntityType(type);
                if (egg != null && egg != Items.AIR) {
                    EGG_CACHE.put(entityId, egg);
                    return egg;
                }
            }
            // No modern egg for this id (FC-only or eggless mob, or an unmapped id). Remember it, so
            // the per-frame (FCBlockEntityRenderer) and per-broadcast (InventoryAdapter.getItem)
            // conversions of the same egg stop rescanning. EntityList's static init fills the map,
            // FC's ReplaceExistingMapping keeps every id and FC's own AddMapping names have no
            // modern egg, so a miss on a filled map is final.
            if (!ids.isEmpty()) EGG_CACHE.put(entityId, Items.AIR);
        } catch (Throwable t) {
            LOGGER.debug("[LEGACY-VARIANTS] spawn egg lookup failed for entity id {}: {}", entityId, t.toString());
        }
        return null;
    }
}
