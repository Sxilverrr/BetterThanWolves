package btw.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for converting between FC ({@link btw.modern.ItemStack}) and
 * MC 1.20.1 ({@link net.minecraft.world.item.ItemStack}) item stacks.
 *
 * <p>Conversion relies on {@link ProxyRegistry} to map legacy numeric IDs
 * to modern Item/Block instances and vice versa.</p>
 */
public class ItemStackHelper {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ItemStackHelper");

    /**
     * Convert an FC ItemStack to an MC ItemStack.
     *
     * @param fcStack the FC item stack (may be null)
     * @return the equivalent MC item stack, or {@link net.minecraft.world.item.ItemStack#EMPTY}
     *         if the input is null or the legacy ID cannot be resolved
     */
    /**
     * Converts an FC legacy item ID + damage + count to an MC ItemStack.
     * Used for rendering FC items in GUIs (merchant trades, etc.).
     */
    public static net.minecraft.world.item.ItemStack fcIdToMcStack(int fcItemId, int count, int damage) {
        if (fcItemId <= 0) return net.minecraft.world.item.ItemStack.EMPTY;
        return toMcStack(new btw.modern.ItemStack(fcItemId, count, damage));
    }

    public static net.minecraft.world.item.ItemStack toMcStack(btw.modern.ItemStack fcStack) {
        if (fcStack == null) return net.minecraft.world.item.ItemStack.EMPTY;

        int legacyId = fcStack.itemID;
        if (legacyId <= 0) return net.minecraft.world.item.ItemStack.EMPTY;
        int damage = fcStack.getItemDamage();

        // Resolve the item FIRST. 1.5.2 subtypes that 1.20.1 split into separate items (wool
        // colour, wood species, dye, coal/charcoal, skull, spawn egg, ...) are (id, damage) pairs
        // (BlockLog.java:21, ItemDye.java:19, ItemSkull.java:24): LegacyVariants, then the
        // ProxyRegistry wood-species table. Such an item carries no damage subtype in 1.20.1.
        Item modernItem = variantItem(legacyId, damage);
        boolean variant = modernItem != null;

        // Try as an item first
        if (modernItem == null) modernItem = ProxyRegistry.getModernItem(legacyId);

        // If no item mapping, try as a block and get its item form
        if (modernItem == null) {
            net.minecraft.world.level.block.Block block = ProxyRegistry.getModernBlock(legacyId);
            if (block != null) {
                modernItem = block.asItem();
            }
        }

        if (modernItem == null || modernItem == net.minecraft.world.item.Items.AIR) {
            if (WARNED_UNMAPPED.add(legacyId)) {
                LOGGER.warn("Cannot convert FC ItemStack with legacy ID {} to MC ItemStack: no mapping found"
                        + " (further occurrences for this id suppressed).", legacyId);
            }
            return net.minecraft.world.item.ItemStack.EMPTY;
        }

        net.minecraft.world.item.ItemStack mcStack = new net.minecraft.world.item.ItemStack(
                modernItem, fcStack.stackSize);

        // Copy NBT/enchantments BEFORE durability. The FC tag of a converted stack is a copy taken
        // at toFcStack time, so its "Damage" (1.20.1 keeps durability there, IForgeItem.getDamage)
        // is stale; applying the tag last made it overwrite FC's durability change. A variant
        // item's subtype is the item itself, so a "Damage" riding its tag is dropped.
        if (fcStack.hasTagCompound()) {
            CompoundTag mcTag = toMcTag(fcStack.getTagCompound());
            if (mcTag != null) {
                if (variant) mcTag.remove("Damage");
                if (!mcTag.isEmpty()) mcStack.setTag(mcTag);
            }
        }

        // Convert FC enchantments (ench tag with numeric IDs) to
        // MC 1.20.1 format (Enchantments tag with string registry names).
        // FC stores: {ench: [{id: 16s, lvl: 1s}]}
        // MC wants:  applied via ItemStack.enchant() which handles format
        convertFcEnchantments(mcStack);

        // Carry over damage value LAST: durability on damageable items (not on Unbreakable ones,
        // which MC never wears), metadata subtype on the rest. setDamageValue on a non-damageable
        // item creates {Damage:n} (IForgeItem.setDamage), so only write it for a real subtype and
        // otherwise strip a stale one -- a stray {Damage:0} stops the stack merging with plain ones.
        boolean damageable = isDamageable(mcStack);
        if (damageable ? mcStack.isDamageableItem() : (!variant && damage > 0)) {
            mcStack.setDamageValue(damage);
        } else if (!damageable && mcStack.hasTag() && mcStack.getTag().contains("Damage")) {
            mcStack.getTag().remove("Damage");
            if (mcStack.getTag().isEmpty()) mcStack.setTag(null);
        }

        return mcStack;
    }

    /** Legacy ids already warned about by {@link #toMcStack} (once per id, not per conversion). */
    private static final java.util.Set<Integer> WARNED_UNMAPPED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean variantFailureLogged;

    /**
     * Item for a flattened 1.5.2 subtype (id, damage), or null when the id has no per-damage
     * variants. LegacyVariants first, then the ProxyRegistry wood-species table (damage &amp; 3).
     */
    private static Item variantItem(int legacyId, int damage) {
        Item item = null;
        try {
            item = LegacyVariants.toModern(legacyId, damage);
        } catch (Throwable t) {
            logVariantFailure(t);
        }
        if (item == null) {
            net.minecraft.world.level.block.Block variant = ProxyRegistry.getModernBlockVariant(legacyId, damage);
            if (variant != null) {
                Item variantItem = variant.asItem();
                if (variantItem != null && variantItem != net.minecraft.world.item.Items.AIR) item = variantItem;
            }
        }
        return item;
    }

    private static void logVariantFailure(Throwable t) {
        if (!variantFailureLogged) {
            variantFailureLogged = true;
            LOGGER.error("LegacyVariants lookup failed; falling back to plain id mapping (logged once)", t);
        }
    }

    /** Item-level damageability (IForgeItem.isDamageable), ignoring the Unbreakable tag. */
    static boolean isDamageable(net.minecraft.world.item.ItemStack mc) {
        return mc != null && !mc.isEmpty() && mc.getItem().isDamageable(mc);
    }

    /**
     * Copies FC count/durability/NBT onto an existing MC stack in place, so the modern item
     * identity (and anything FC cannot represent) survives. Order: tag first, then durability,
     * then count -- the FC tag is a toFcStack-time copy holding a stale tag.Damage
     * (IForgeItem.getDamage). Durability is written only to damageable items, because elsewhere
     * setDamageValue creates {Damage:n} (IForgeItem.setDamage) and breaks stacking; a
     * non-damageable item's Damage subtype is preserved across a tag replacement.
     */
    static void applyFcState(net.minecraft.world.item.ItemStack mc, btw.modern.ItemStack fc, boolean tagChanged) {
        if (tagChanged) {
            boolean keepSubtype = !isDamageable(mc) && mc.hasTag() && mc.getTag().contains("Damage");
            int subtype = mc.getDamageValue();
            CompoundTag tag = fc.hasTagCompound() ? toMcTag(fc.getTagCompound()) : null;
            mc.setTag(tag != null && !tag.isEmpty() ? tag : null);
            if (keepSubtype) mc.getOrCreateTag().putInt("Damage", subtype);
            convertFcEnchantments(mc);
        }
        if (mc.isDamageableItem()) mc.setDamageValue(fc.getItemDamage());
        mc.setCount(fc.stackSize);
    }

    /**
     * Comparable copy of an FC stack's NBT without "Damage" (durability/subtype is compared through
     * getItemDamage), or null for no tag data. Never mutates the FC tag. The NBT is taken in its MC form:
     * the "ench" mirror toFcStack adds is folded into "Enchantments" ({@link #foldFcEnchantments}), so an
     * enchanted stack FC did not touch fingerprints exactly like its MC stack ({@link #mcTagFingerprint}).
     */
    static CompoundTag fcTagFingerprint(btw.modern.ItemStack fc) {
        if (fc == null || !fc.hasTagCompound()) return null;
        btw.modern.NBTTagCompound tag = fc.getTagCompound();
        if (tag instanceof ForgeNBTCompound fnbt && !fnbt.hasLiveTags() && !fnbt.getTag().contains(FC_ENCH)) {
            return stripDamageCopy(fnbt.getTag());
        }
        return stripDamage(toMcFormTag(tag));
    }

    /** Same fingerprint for an MC stack (its tag minus "Damage"), or null. */
    static CompoundTag mcTagFingerprint(net.minecraft.world.item.ItemStack mc) {
        return mc == null || mc.isEmpty() ? null : stripDamageCopy(mc.getTag());
    }

    /**
     * True when the FC stack's NBT equals {@code fingerprint} (as from {@link #fcTagFingerprint}),
     * "Damage" ignored. A converted tag without live tagMap objects is compared in place, without the
     * copy fcTagFingerprint makes: InventoryBridge runs this for every tagged slot per write-back.
     */
    static boolean fcTagMatches(btw.modern.ItemStack fc, CompoundTag fingerprint) {
        if (fc == null || !fc.hasTagCompound()) return fingerprint == null;
        btw.modern.NBTTagCompound tag = fc.getTagCompound();
        if (tag instanceof ForgeNBTCompound fnbt && !fnbt.hasLiveTags() && !fnbt.getTag().contains(FC_ENCH)) {
            return tagMatches(fnbt.getTag(), fingerprint);
        }
        return java.util.Objects.equals(stripDamage(toMcFormTag(tag)), fingerprint);
    }

    /** toMcTag plus the "ench" fold: the tag exactly as a write-back would put it on the MC stack. */
    private static CompoundTag toMcFormTag(btw.modern.NBTTagCompound fcTag) {
        CompoundTag tag = toMcTag(fcTag);
        foldFcEnchantments(tag);
        return tag;
    }

    /** {@link #fcTagMatches} for an MC stack (its tag minus "Damage"), without copying the tag. */
    static boolean mcTagMatches(net.minecraft.world.item.ItemStack mc, CompoundTag fingerprint) {
        return tagMatches(mc == null || mc.isEmpty() ? null : mc.getTag(), fingerprint);
    }

    /** {@code tag} minus "Damage" equals the (Damage-free, null when empty) fingerprint. */
    private static boolean tagMatches(CompoundTag tag, CompoundTag fingerprint) {
        int n = tag == null ? 0 : tag.size() - (tag.contains("Damage") ? 1 : 0);
        if (fingerprint == null || fingerprint.isEmpty()) return n == 0;
        if (n != fingerprint.size()) return false;
        for (String key : fingerprint.getAllKeys()) {
            if ("Damage".equals(key) || !java.util.Objects.equals(tag.get(key), fingerprint.get(key))) return false;
        }
        return true;
    }

    private static CompoundTag stripDamageCopy(CompoundTag src) {
        if (src == null || src.isEmpty() || (src.size() == 1 && src.contains("Damage"))) return null;
        return stripDamage(src.copy());
    }

    private static CompoundTag stripDamage(CompoundTag tag) {
        if (tag == null) return null;
        tag.remove("Damage");
        return tag.isEmpty() ? null : tag;
    }

    /** FC/1.5.2 enchantment list: {id: short, lvl: short} (vanilla/server ItemStack.java:589-605). */
    private static final String FC_ENCH = "ench";
    /** 1.20.1 enchantment list: {id: "namespace:path", lvl: short} (ItemStack.TAG_ENCH). */
    private static final String MC_ENCH = "Enchantments";
    private static volatile boolean enchMirrorFailureLogged;

    /**
     * Converts FC-format enchantments ({@code ench} tag with numeric IDs) to MC 1.20.1 format
     * ("Enchantments", string ids) and removes "ench"; see {@link #foldFcEnchantments}.
     * FC and MC 1.5.2 use the same numeric enchantment IDs.
     */
    static void convertFcEnchantments(net.minecraft.world.item.ItemStack mcStack) {
        CompoundTag tag = mcStack.getTag();
        if (tag == null || !tag.contains(FC_ENCH, 9)) return; // 9 = list tag
        foldFcEnchantments(tag);
        // a tag that held nothing but "ench" would stay {} and stop the stack merging with plain ones
        if (tag.isEmpty()) mcStack.setTag(null);
    }

    /**
     * Folds the FC "ench" list of an MC-form tag into "Enchantments" and removes it. "ench" is either the
     * mirror toFcStack made of the stack's own "Enchantments" ({@link #mirrorEnchantmentsForFc}) or FC's
     * own list: an FC-made stack, FC appending to the mirror (ItemStack.addEnchantment,
     * FCContainerInfernalEnchanter.java:507) or replacing it (EnchantmentHelper.setEnchantments).
     * <ul>
     *   <li>Still equal to the mirror of "Enchantments": FC changed nothing and "Enchantments" stays exactly
     *       as it was (re-adding every entry, as the old ItemStack.enchant append would, duplicated them on
     *       each round trip).</li>
     *   <li>Otherwise "ench" is FC's word on every enchantment it can express: "Enchantments" becomes the
     *       entries FC cannot see (1.20.1-only: mending, sweeping edge, ...; kept as they were) followed by
     *       "ench" converted, so nothing is duplicated or dropped.</li>
     * </ul>
     * Never throws.
     */
    static void foldFcEnchantments(CompoundTag tag) {
        if (tag == null || !tag.contains(FC_ENCH, 9)) return; // 9 = list tag
        net.minecraft.nbt.ListTag fcList = tag.getList(FC_ENCH, 10); // 10 = compound
        tag.remove(FC_ENCH); // Remove FC format
        net.minecraft.nbt.ListTag mcList = tag.getList(MC_ENCH, 10);
        if (sameFcEnchantments(fcList, fcEnchantmentMirror(mcList))) return;

        net.minecraft.nbt.ListTag merged = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < mcList.size(); i++) {
            CompoundTag mcEnch = mcList.getCompound(i);
            if (mcEnchToFcId(mcEnch) < 0) merged.add(mcEnch.copy());
        }
        for (int i = 0; i < fcList.size(); i++) {
            CompoundTag enchTag = fcList.getCompound(i);
            int enchId = enchTag.getShort("id");
            int enchLvl = enchTag.getShort("lvl");

            net.minecraft.world.item.enchantment.Enchantment mcEnch = fcEnchIdToMc(enchId);
            if (mcEnch != null && enchLvl > 0) {
                // the entry layout ItemStack.enchant(mcEnch, enchLvl) appends (1.20.1 ItemStack.java:820-828),
                // without enchant's (byte) cast: the mirror hands FC levels up to 255 (fcEnchantmentMirror), and
                // 128..255 wrapped negative, read back as level 0 and dropped the enchantment once FC edited
                // the list. storeEnchantment writes the short "lvl"; enchLvl came from a short.
                merged.add(net.minecraft.world.item.enchantment.EnchantmentHelper.storeEnchantment(
                        net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentId(mcEnch),
                        enchLvl));
            }
        }
        if (merged.isEmpty()) {
            tag.remove(MC_ENCH);
        } else {
            tag.put(MC_ENCH, merged);
        }
    }

    /**
     * MC -> FC: FC reads enchantments only from the 1.5.2 "ench" list (Modern-Common
     * ItemStack.getEnchantmentTagList -> EnchantmentHelper.getEnchantmentLevel), never from 1.20.1
     * "Enchantments", so Infinity/Power/Punch/Flame (FCItemBow.java:43, :100, :202-222),
     * Unbreaking (ItemStack.damageItem), Efficiency/Silk Touch/Fortune (FC harvest and mining speed) and
     * Looting on MC-enchanted stacks did nothing in FC code. Adds the mirror through setTag, i.e. as a LIVE
     * list in the tagMap: FC appending to it (ItemStack.addEnchantment -> getTagList("ench").appendTag) keeps
     * the entry. {@link #foldFcEnchantments} reverses it. A tag that already has "ench" is left alone.
     */
    private static void mirrorEnchantmentsForFc(ForgeNBTCompound fcTag) {
        CompoundTag tag = fcTag.getTag();
        if (!tag.contains(MC_ENCH, 9) || tag.contains(FC_ENCH)) return;
        try {
            int[] mirror = fcEnchantmentMirror(tag.getList(MC_ENCH, 10));
            if (mirror.length == 0) return;
            btw.modern.NBTTagList list = new btw.modern.NBTTagList();
            for (int i = 0; i < mirror.length; i += 2) {
                btw.modern.NBTTagCompound ench = new btw.modern.NBTTagCompound();
                ench.setShort("id", (short) mirror[i]);
                ench.setShort("lvl", (short) mirror[i + 1]);
                list.appendTag(ench);
            }
            fcTag.setTag(FC_ENCH, list);
        } catch (Throwable t) {
            if (!enchMirrorFailureLogged) {
                enchMirrorFailureLogged = true;
                LOGGER.error("Enchantment mirror for FC failed; FC sees this stack unenchanted (logged once)", t);
            }
        }
    }

    /**
     * What FC sees as "ench" for an MC "Enchantments" list: (1.5.2 id, level) pairs, flattened, for every
     * entry 1.5.2 has, in order. Levels as MC reads them (EnchantmentHelper.getEnchantmentLevel, 0..255);
     * level-0 entries are left out.
     */
    private static int[] fcEnchantmentMirror(net.minecraft.nbt.ListTag mcList) {
        int[] pairs = new int[mcList.size() * 2];
        int n = 0;
        for (int i = 0; i < mcList.size(); i++) {
            CompoundTag mcEnch = mcList.getCompound(i);
            int fcId = mcEnchToFcId(mcEnch);
            int lvl = net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentLevel(mcEnch);
            if (fcId >= 0 && lvl > 0) {
                pairs[n++] = fcId;
                pairs[n++] = lvl;
            }
        }
        return n == pairs.length ? pairs : java.util.Arrays.copyOf(pairs, n);
    }

    /** True when the FC "ench" list holds exactly {@code mirror}'s (id, level) pairs, in order. */
    private static boolean sameFcEnchantments(net.minecraft.nbt.ListTag fcList, int[] mirror) {
        if (fcList.size() * 2 != mirror.length) return false;
        for (int i = 0; i < fcList.size(); i++) {
            CompoundTag ench = fcList.getCompound(i);
            if (ench.getShort("id") != mirror[2 * i] || ench.getShort("lvl") != mirror[2 * i + 1]) return false;
        }
        return true;
    }

    /** The 1.5.2 id of an MC "Enchantments" entry, or -1 when 1.5.2 has no such enchantment. Never throws. */
    private static int mcEnchToFcId(CompoundTag mcEnch) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentId(mcEnch);
        Integer fcId = key == null ? null : FcEnchIds.BY_MC_ID.get(key);
        return fcId != null ? fcId : -1;
    }

    /** 1.20.1 enchantment registry id -> FC/1.5.2 numeric id: the reverse of {@link #fcEnchIdToMc}. */
    private static final class FcEnchIds {
        static final java.util.Map<net.minecraft.resources.ResourceLocation, Integer> BY_MC_ID = build();

        private static java.util.Map<net.minecraft.resources.ResourceLocation, Integer> build() {
            java.util.Map<net.minecraft.resources.ResourceLocation, Integer> map = new java.util.HashMap<>();
            for (int fcId = 0; fcId < 64; fcId++) {   // every 1.5.2 enchantment id is below 64
                try {
                    net.minecraft.world.item.enchantment.Enchantment mcEnch = fcEnchIdToMc(fcId);
                    net.minecraft.resources.ResourceLocation key = mcEnch == null ? null
                            : net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentId(mcEnch);
                    if (key != null) map.putIfAbsent(key, fcId);
                } catch (Throwable t) {
                    LOGGER.error("No FC id for enchantment {}: {}", fcId, t.toString());
                }
            }
            return map;
        }
    }

    /** Maps FC/MC 1.5.2 numeric enchantment IDs to MC 1.20.1 Enchantment objects. */
    private static net.minecraft.world.item.enchantment.Enchantment fcEnchIdToMc(int fcId) {
        var E = net.minecraft.world.item.enchantment.Enchantments.class;
        return switch (fcId) {
            case 0  -> net.minecraft.world.item.enchantment.Enchantments.ALL_DAMAGE_PROTECTION;
            case 1  -> net.minecraft.world.item.enchantment.Enchantments.FIRE_PROTECTION;
            case 2  -> net.minecraft.world.item.enchantment.Enchantments.FALL_PROTECTION;
            case 3  -> net.minecraft.world.item.enchantment.Enchantments.BLAST_PROTECTION;
            case 4  -> net.minecraft.world.item.enchantment.Enchantments.PROJECTILE_PROTECTION;
            case 5  -> net.minecraft.world.item.enchantment.Enchantments.RESPIRATION;
            case 6  -> net.minecraft.world.item.enchantment.Enchantments.AQUA_AFFINITY;
            case 7  -> net.minecraft.world.item.enchantment.Enchantments.THORNS;
            case 16 -> net.minecraft.world.item.enchantment.Enchantments.SHARPNESS;
            case 17 -> net.minecraft.world.item.enchantment.Enchantments.SMITE;
            case 18 -> net.minecraft.world.item.enchantment.Enchantments.BANE_OF_ARTHROPODS;
            case 19 -> net.minecraft.world.item.enchantment.Enchantments.KNOCKBACK;
            case 20 -> net.minecraft.world.item.enchantment.Enchantments.FIRE_ASPECT;
            case 21 -> net.minecraft.world.item.enchantment.Enchantments.MOB_LOOTING;
            case 32 -> net.minecraft.world.item.enchantment.Enchantments.BLOCK_EFFICIENCY;
            case 33 -> net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH;
            case 34 -> net.minecraft.world.item.enchantment.Enchantments.UNBREAKING;
            case 35 -> net.minecraft.world.item.enchantment.Enchantments.BLOCK_FORTUNE;
            case 48 -> net.minecraft.world.item.enchantment.Enchantments.POWER_ARROWS;
            case 49 -> net.minecraft.world.item.enchantment.Enchantments.PUNCH_ARROWS;
            case 50 -> net.minecraft.world.item.enchantment.Enchantments.FLAMING_ARROWS;
            case 51 -> net.minecraft.world.item.enchantment.Enchantments.INFINITY_ARROWS;
            default -> null;
        };
    }

    /**
     * Convert an MC ItemStack to an FC ItemStack.
     *
     * @param mcStack the MC item stack (may be null or empty)
     * @return the equivalent FC item stack, or null if the input is null/empty
     *         or the item cannot be mapped to a legacy ID
     */
    public static btw.modern.ItemStack toFcStack(net.minecraft.world.item.ItemStack mcStack) {
        if (mcStack == null || mcStack.isEmpty()) return null;

        int legacyId;
        int damage;

        // Flattened 1.5.2 subtypes first: orange wool is (35, 1), a spruce log (17, 1), red dye
        // (351, 1) -- the subtype is the item, not tag.Damage (BlockLog.java:21, ItemDye.java:19).
        int[] legacy = null;
        try {
            legacy = LegacyVariants.toLegacy(mcStack.getItem());
        } catch (Throwable t) {
            logVariantFailure(t);
        }
        if (legacy != null && legacyIdExists(legacy[0])) {
            legacyId = legacy[0];
            damage = legacy[1];
            // An FC-only subtype rides the Damage tag of its nearest modern item (e.g. the FC
            // infused skull 397:5 on a skeleton skull, ItemSkull.java:24).
            int tagged = mcStack.getDamageValue();
            if (tagged != 0 && tagged != damage && safeToModern(legacyId, tagged) == null) {
                damage = tagged;
            }
        } else {
            // getOrCreateLegacyId always succeeds: if the item has no FC
            // equivalent, a headless passthrough entry is created so the
            // item roundtrips losslessly through FC container logic.
            legacyId = ProxyRegistry.getOrCreateLegacyId(mcStack.getItem());

            if (legacyId <= 0) {
                LOGGER.debug("Cannot convert MC ItemStack {} to FC ItemStack: no legacy ID mapping.", mcStack);
                return null;
            }

            damage = mcStack.getDamageValue();

            // Spawn eggs all map to legacy id 383 (monsterPlacer); 1.5.2 encodes WHICH mob in the
            // item damage as the EntityList id. EntityAgeable.interact reads getItemDamage() ->
            // EntityList.getClassFromID to spawn a baby, so set it from the egg's entity type.
            if (mcStack.getItem() instanceof net.minecraft.world.item.SpawnEggItem eggItem) {
                int entityId = legacyEntityIdForEgg(eggItem, mcStack);
                if (entityId > 0) {
                    damage = entityId;
                }
            }
        }

        btw.modern.ItemStack fcStack = new btw.modern.ItemStack(
                legacyId, mcStack.getCount(), damage);

        // Copy NBT/enchantments from MC CompoundTag to FC NBTTagCompound, plus the "ench" mirror of MC's
        // "Enchantments" that FC actually reads (folded back by convertFcEnchantments)
        CompoundTag mcTag = mcStack.getTag();
        if (mcTag != null && !mcTag.isEmpty()) {
            ForgeNBTCompound fcTag = new ForgeNBTCompound(mcTag.copy());
            mirrorEnchantmentsForFc(fcTag);
            fcStack.setTagCompound(fcTag);
        }

        return fcStack;
    }

    private static Item safeToModern(int legacyId, int damage) {
        try {
            return LegacyVariants.toModern(legacyId, damage);
        } catch (Throwable t) {
            logVariantFailure(t);
            return null;
        }
    }

    /** True when FC has a block or item registered at this legacy id. */
    private static boolean legacyIdExists(int id) {
        if (id <= 0) return false;
        return (id < btw.modern.Item.itemsList.length && btw.modern.Item.itemsList[id] != null)
                || (id < btw.modern.Block.blocksList.length && btw.modern.Block.blocksList[id] != null);
    }

    // Modern spawn-egg entity registry path -> 1.5.2 EntityList name. The numeric id is then
    // resolved through btw.modern.EntityList.stringToIDMapping so it reflects FC's mapping
    // replacements. Names that aren't simple capitalizations (MushroomCow, Ozelot, LavaSlime)
    // follow the 1.5.2 EntityList table. LegacyVariants reverses it for the FC -> MC egg.
    static final java.util.Map<String, String> LEGACY_ENTITY_NAMES = new java.util.HashMap<>();
    static {
        LEGACY_ENTITY_NAMES.put("pig", "Pig");
        LEGACY_ENTITY_NAMES.put("sheep", "Sheep");
        LEGACY_ENTITY_NAMES.put("cow", "Cow");
        LEGACY_ENTITY_NAMES.put("chicken", "Chicken");
        LEGACY_ENTITY_NAMES.put("wolf", "Wolf");
        LEGACY_ENTITY_NAMES.put("mooshroom", "MushroomCow");
        LEGACY_ENTITY_NAMES.put("ocelot", "Ozelot");
        LEGACY_ENTITY_NAMES.put("cat", "Ozelot");
        LEGACY_ENTITY_NAMES.put("villager", "Villager");
        LEGACY_ENTITY_NAMES.put("creeper", "Creeper");
        LEGACY_ENTITY_NAMES.put("skeleton", "Skeleton");
        LEGACY_ENTITY_NAMES.put("zombie", "Zombie");
        LEGACY_ENTITY_NAMES.put("spider", "Spider");
        LEGACY_ENTITY_NAMES.put("cave_spider", "CaveSpider");
        LEGACY_ENTITY_NAMES.put("enderman", "Enderman");
        LEGACY_ENTITY_NAMES.put("silverfish", "Silverfish");
        LEGACY_ENTITY_NAMES.put("blaze", "Blaze");
        LEGACY_ENTITY_NAMES.put("slime", "Slime");
        LEGACY_ENTITY_NAMES.put("magma_cube", "LavaSlime");
        LEGACY_ENTITY_NAMES.put("ghast", "Ghast");
        LEGACY_ENTITY_NAMES.put("zombified_piglin", "PigZombie");
        LEGACY_ENTITY_NAMES.put("squid", "Squid");
        LEGACY_ENTITY_NAMES.put("bat", "Bat");
        LEGACY_ENTITY_NAMES.put("witch", "Witch");
    }

    private static int legacyEntityIdForEgg(net.minecraft.world.item.SpawnEggItem eggItem,
                                            net.minecraft.world.item.ItemStack mcStack) {
        // Defensive: this runs on the combat path (hurtEnemy) among others; a spawn-egg id
        // lookup must never crash the hit. Any failure -> no damage value set (id 0).
        try {
            net.minecraft.world.entity.EntityType<?> type = eggItem.getType(mcStack.getTag());
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (key == null) return 0;
            String fcName = LEGACY_ENTITY_NAMES.get(key.getPath());
            if (fcName == null) return 0;
            Object id = btw.modern.EntityList.stringToIDMapping.get(fcName);
            return id instanceof Integer ? (Integer) id : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Convert an FC {@link btw.modern.NBTTagCompound} to an MC {@link CompoundTag}.
     *
     * <p>If the FC tag is a {@link ForgeNBTCompound} (backed by a real CompoundTag),
     * the inner tag is extracted and copied. Otherwise, conversion is not possible
     * because the plain {@link btw.modern.NBTTagCompound} does not expose its keys
     * for iteration.</p>
     *
     * @param fcTag the FC NBT compound (may be null)
     * @return a copy of the underlying MC CompoundTag, or null if conversion is not possible
     */
    @SuppressWarnings("unchecked")
    static CompoundTag toMcTag(btw.modern.NBTTagCompound fcTag) {
        if (fcTag == null) return null;

        if (fcTag instanceof ForgeNBTCompound fnbt) {
            CompoundTag result = fnbt.getTag().copy();
            // FC code uses generic setTag(String, NBTBase) which stores in
            // the inherited tagMap, NOT in the CompoundTag. Merge any tagMap
            // data that the CompoundTag doesn't have (e.g., enchantments
            // added by ItemStack.addEnchantment).
            mergeTagMapInto(fcTag, result);
            return result;
        }

        // Plain NBTTagCompound — iterate its tagMap and build a real CompoundTag.
        // FC creates these when adding enchantments, custom data, etc.
        CompoundTag result = new CompoundTag();
        try {
            java.util.Map<String, Object> tagMap = tagMapOf(fcTag);

            for (var entry : tagMap.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if (val instanceof Integer i) result.putInt(key, i);
                else if (val instanceof String s) result.putString(key, s);
                else if (val instanceof Boolean b) result.putBoolean(key, b);
                else if (val instanceof Byte b) result.putByte(key, b);
                else if (val instanceof Short s) result.putShort(key, s);
                else if (val instanceof Long l) result.putLong(key, l);
                else if (val instanceof Float f) result.putFloat(key, f);
                else if (val instanceof Double d) result.putDouble(key, d);
                else if (val instanceof byte[] ba) result.putByteArray(key, ba);
                else if (val instanceof int[] ia) result.putIntArray(key, ia);
                else if (val instanceof btw.modern.NBTTagCompound sub) {
                    CompoundTag mcSub = toMcTag(sub);
                    if (mcSub != null) result.put(key, mcSub);
                } else if (val instanceof btw.modern.NBTTagList list) {
                    net.minecraft.nbt.ListTag mcList = toMcList(list);
                    if (mcList != null) result.put(key, mcList);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to convert plain NBTTagCompound: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Converts an FC NBTTagList to an MC ListTag.
     */
    static net.minecraft.nbt.ListTag toMcList(btw.modern.NBTTagList fcList) {
        if (fcList == null) return null;
        net.minecraft.nbt.ListTag result = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < fcList.tagCount(); i++) {
            btw.modern.NBTBase tag = fcList.tagAt(i);
            if (tag instanceof btw.modern.NBTTagCompound sub) {
                CompoundTag mcSub = toMcTag(sub);
                if (mcSub != null) result.add(mcSub);
            } else if (tag instanceof btw.modern.NBTTagDouble d) {
                // Vanilla 1.5.2 Entity.writeToNBT stores Pos and Motion as
                // a list of NBTTagDouble. Without this branch they would be
                // silently dropped, breaking entity load (Pos/Motion become
                // empty lists, vanilla readFromNBT throws when indexing them).
                result.add(net.minecraft.nbt.DoubleTag.valueOf(d.data));
            } else if (tag instanceof btw.modern.NBTTagFloat f) {
                // Vanilla 1.5.2 Entity.writeToNBT stores Rotation as a list
                // of NBTTagFloat. Same fix as above.
                result.add(net.minecraft.nbt.FloatTag.valueOf(f.data));
            } else if (tag instanceof btw.modern.NBTTagString s) {
                result.add(net.minecraft.nbt.StringTag.valueOf(s.data));
            }
        }
        return result;
    }

    /** Cached reflective handle on btw.modern.NBTTagCompound.tagMap (resolved once). */
    private static volatile java.lang.reflect.Field tagMapField;

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> tagMapOf(btw.modern.NBTTagCompound fcTag)
            throws ReflectiveOperationException {
        java.lang.reflect.Field f = tagMapField;
        if (f == null) {
            f = btw.modern.NBTTagCompound.class.getDeclaredField("tagMap");
            f.setAccessible(true);
            tagMapField = f;
        }
        return (java.util.Map<String, Object>) f.get(fcTag);
    }

    /**
     * Merges data from NBTTagCompound's inherited tagMap into a CompoundTag.
     * ForgeNBTCompound stores typed data in the real CompoundTag, but FC's
     * generic setTag(String, NBTBase) stores in the inherited tagMap. This
     * method picks up anything in tagMap that the CompoundTag is missing, and
     * lets tagMap lists/compounds win: those are the live objects FC stored and
     * keeps mutating (1.5.2 ItemStack.addEnchantment, ItemStack.java:589-605:
     * setTag("ench", list) then getTagList("ench").appendTag), while
     * ForgeNBTCompound.setTag only wrote a snapshot of them into the CompoundTag.
     */
    private static void mergeTagMapInto(btw.modern.NBTTagCompound fcTag, CompoundTag target) {
        try {
            java.util.Map<String, Object> tagMap = tagMapOf(fcTag);

            for (var entry : tagMap.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                // lists/compounds in tagMap are FC's live setTag objects (ForgeNBTCompound.setTag
                // only snapshots them), so they win; typed primitives live only in the CompoundTag
                boolean liveContainer = val instanceof btw.modern.NBTTagList
                        || val instanceof btw.modern.NBTTagCompound;
                if (target.contains(key) && !liveContainer) continue; // CompoundTag data wins
                if (val instanceof Integer i) target.putInt(key, i);
                else if (val instanceof String s) target.putString(key, s);
                else if (val instanceof Boolean b) target.putBoolean(key, b);
                else if (val instanceof Byte b) target.putByte(key, b);
                else if (val instanceof Short s) target.putShort(key, s);
                else if (val instanceof Long l) target.putLong(key, l);
                else if (val instanceof Float f) target.putFloat(key, f);
                else if (val instanceof Double d) target.putDouble(key, d);
                else if (val instanceof byte[] ba) target.putByteArray(key, ba);
                else if (val instanceof int[] ia) target.putIntArray(key, ia);
                else if (val instanceof btw.modern.NBTTagCompound sub) {
                    CompoundTag mcSub = toMcTag(sub);
                    if (mcSub != null) target.put(key, mcSub);
                } else if (val instanceof btw.modern.NBTTagList list) {
                    net.minecraft.nbt.ListTag mcList = toMcList(list);
                    if (mcList != null) target.put(key, mcList);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("mergeTagMapInto failed: {}", e.getMessage());
        }
    }

    /**
     * Check whether a given FC ItemStack can be mapped to a valid MC item.
     *
     * @param fcStack the FC item stack to check
     * @return true if the stack is non-null and its legacy ID resolves to a modern item or block
     */
    public static boolean canConvert(btw.modern.ItemStack fcStack) {
        if (fcStack == null) return false;
        int legacyId = fcStack.itemID;
        if (ProxyRegistry.getModernItem(legacyId) != null) return true;
        return ProxyRegistry.getModernBlock(legacyId) != null;
    }
}
