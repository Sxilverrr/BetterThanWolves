package btw.forge;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Translates 1.5.2 aux-SFX ids (World.playAuxSFX / playAuxSFXAtEntity / func_82739_e) into 1.20.1 level-event
 * ids and data. The two versions number these events differently and WorldBridge used to forward the 1.5.2 id
 * raw: the bat takeoff (1.5.2 1015, "mob.bat.takeoff" at volume 0.05, vanilla/client RenderGlobal.java:2254-2255)
 * reached 1.20.1 LevelRenderer.levelEvent as 1015 = GHAST_WARN at volume 10 (triage GH1), the zombie infect /
 * cure (1016 / 1017) as GHAST_SHOOT / ENDER_DRAGON_SHOOT (GH3), the torch fizz (1004) as a firework launch.
 *
 * <p>Each mapping below was checked against 1.5.2 RenderGlobal.playAuxSFX (vanilla/client RenderGlobal.java:
 * 2169-2399) / broadcastSound (:2122-2164) and 1.20.1 LevelRenderer.levelEvent / globalLevelEvent: same sound
 * file, volume and pitch formula. FC custom ids (&gt;= 2222) never come here (WorldBridge.sendFcAuxSfx -&gt;
 * BTWNetwork.sendFcAuxSfx -&gt; FC ClientPlayCustomAuxFX on the client). A 1.5.2
 * id with no 1.20.1 event answers {@link #NONE} and is never passed raw: the caller plays it itself (1003
 * door sound) or drops it.</p>
 *
 * <p>Pure lookup, safe on any thread; nothing here throws.</p>
 */
final class AuxSfxMapping {

    /** No 1.20.1 level event matches the 1.5.2 id: the caller must play (or drop) it itself. */
    static final int NONE = -1;
    /** {@link #toModernData} could not translate the payload (unknown block / item): drop the level event. */
    static final int INVALID_DATA = Integer.MIN_VALUE;

    /** 1.5.2 PotionHelper.calcPotionLiquidColor default (vanilla/client PotionHelper.java:80). */
    private static final int DEFAULT_POTION_COLOR = 3694022;

    private AuxSfxMapping() {}

    /**
     * 1.20.1 {@code LevelRenderer.levelEvent} id for a 1.5.2 {@code RenderGlobal.playAuxSFX} id, or {@link #NONE}.
     * {@code legacyData} only matters for 1005 (record vs stop) and 2002 (instant potion).
     */
    static int toModernEvent(int legacyId, int legacyData) {
        switch (legacyId) {
            case 1000: return 1000;     // random.click 1.0/1.0      -> DISPENSER_DISPENSE (random/click)
            case 1001: return 1001;     // random.click 1.0/1.2      -> DISPENSER_FAIL (random/click)
            case 1002: return 1002;     // random.bow 1.0/1.2        -> DISPENSER_LAUNCH (random/bow)
            case 1004: return 1009;     // random.fizz 0.5, 2.6+-0.8 -> 1009 data 0 FIRE_EXTINGUISH (random/fizz)
            case 1005: return isRecord(legacyData) ? 1010 : 1011;   // playRecord(name | null)
            case 1007: return 1015;     // mob.ghast.charge 10       -> GHAST_WARN
            case 1008: return 1016;     // mob.ghast.fireball 10     -> GHAST_SHOOT
            case 1009: return 1018;     // mob.ghast.fireball 2      -> BLAZE_SHOOT (mob/ghast/fireball4)
            case 1010: return 1019;     // mob.zombie.wood 2         -> ZOMBIE_ATTACK_WOODEN_DOOR
            case 1011: return 1020;     // mob.zombie.metal 2        -> ZOMBIE_ATTACK_IRON_DOOR
            case 1012: return 1021;     // mob.zombie.woodbreak 2    -> ZOMBIE_BREAK_WOODEN_DOOR
            case 1014: return 1024;     // mob.wither.shoot 2        -> WITHER_SHOOT
            case 1015: return 1025;     // mob.bat.takeoff 0.05      -> BAT_TAKEOFF (GH1)
            case 1016: return 1026;     // mob.zombie.infect 2       -> ZOMBIE_INFECT (GH3; FC's extra moan: caller)
            case 1017: return 1027;     // mob.zombie.unfect 2       -> ZOMBIE_VILLAGER_CONVERTED
            case 1020: return 1029;     // random.anvil_break 1.0    -> ANVIL_DESTROY
            case 1021: return 1030;     // random.anvil_use 1.0      -> ANVIL_USE
            case 1022: return 1031;     // random.anvil_land 0.3     -> ANVIL_LAND
            case 2000: return 2000;     // dispenser smoke (data re-encoded)
            case 2001: return 2001;     // block break sound + particles (data re-encoded)
            case 2002: return isInstantPotion(legacyData) ? 2007 : 2002;  // potion splash (instantSpell)
            case 2003: return 2003;     // eye of ender break
            case 2004: return 2004;     // spawner smoke + flame
            case 2005: return 2005;     // bone meal growth particles (data = count, 0 -> 15 in both)
            // 1003 (door open/close): 1.20.1 has no door level event (1003 is ENDER_EYE_LAUNCH).
            // 1013 / 1018 are broadcastSound-only in 1.5.2 (no playAuxSFX case): see toModernGlobalEvent.
            default: return NONE;
        }
    }

    /**
     * 1.20.1 global level event for a 1.5.2 {@code World.func_82739_e} id (RenderGlobal.broadcastSound,
     * vanilla/client RenderGlobal.java:2122-2164: only 1013 wither spawn and 1018 dragon death play anything).
     */
    static int toModernGlobalEvent(int legacyId) {
        switch (legacyId) {
            case 1013: return 1023;     // mob.wither.spawn 1.0      -> WITHER_SPAWN
            case 1018: return 1028;     // mob.enderdragon.end 5.0   -> ENDER_DRAGON_DEATH
            default: return NONE;
        }
    }

    /** 1.20.1 data for {@link #toModernEvent}'s event, or {@link #INVALID_DATA} when it cannot be expressed. */
    static int toModernData(int legacyId, int legacyData) {
        try {
            switch (legacyId) {
                case 1004:
                    return 0;           // 1009 data 0 = FIRE_EXTINGUISH (data 1 is the generic extinguish)
                case 1005: {
                    net.minecraft.world.item.Item item = recordItem(legacyData);
                    return item != null ? net.minecraft.world.item.Item.getId(item) : 0;
                }
                case 2000:
                    return dispenserSmokeDirection(legacyData);
                case 2001: {
                    // 1.5.2: blockID | metadata << 12 (RenderGlobal.java:2303-2312); 1.20.1: Block.getId(state)
                    BlockState state = legacyBlockState(legacyData & 4095, legacyData >> 12 & 255);
                    return state != null && !state.isAir()
                            ? net.minecraft.world.level.block.Block.getId(state) : INVALID_DATA;
                }
                case 2002: {
                    // 1.5.2: potion damage, coloured via Item.potion.getColorFromDamage (RenderGlobal.java:2326);
                    // 1.20.1: the RGB colour itself.
                    btw.modern.ItemPotion potion = btw.modern.Item.potion;
                    return potion != null ? potion.getColorFromDamage(legacyData) : DEFAULT_POTION_COLOR;
                }
                default:
                    return legacyData;
            }
        } catch (Throwable t) {
            return INVALID_DATA;
        }
    }

    /**
     * 1.5.2 dispenser smoke data is (offsetX + 1) + (offsetZ + 1) * 3 (BlockDispenser; decoded at
     * RenderGlobal.java:2283-2284); 1.20.1 takes the facing's 3D data value (LevelRenderer case 2000).
     */
    private static int dispenserSmokeDirection(int legacyData) {
        int dx = Math.floorMod(legacyData, 3) - 1;
        int dz = Math.floorMod(legacyData / 3, 3) - 1;
        if (dx > 0) return net.minecraft.core.Direction.EAST.get3DDataValue();
        if (dx < 0) return net.minecraft.core.Direction.WEST.get3DDataValue();
        if (dz > 0) return net.minecraft.core.Direction.SOUTH.get3DDataValue();
        if (dz < 0) return net.minecraft.core.Direction.NORTH.get3DDataValue();
        return net.minecraft.core.Direction.UP.get3DDataValue();   // vertical dispenser: no x/z offset
    }

    /** Modern state for a legacy block id + metadata (aux 2001 payload), or null if it has none. */
    private static BlockState legacyBlockState(int id, int meta) {
        if (id <= 0) return null;
        ProxyBlock proxy = ProxyRegistry.getProxy(id);
        if (proxy != null) {
            return proxy.defaultBlockState().setValue(ProxyBlock.META, meta & 15);
        }
        net.minecraft.world.level.block.Block block = ProxyRegistry.getModernBlockVariant(id, meta);
        if (block == null) block = ProxyRegistry.getModernBlock(id);
        return block != null ? block.defaultBlockState() : null;
    }

    private static net.minecraft.world.item.Item recordItem(int legacyItemId) {
        if (legacyItemId <= 0) return null;
        net.minecraft.world.item.Item item = ProxyRegistry.getModernItem(legacyItemId);
        return item instanceof net.minecraft.world.item.RecordItem ? item : null;
    }

    private static boolean isRecord(int legacyItemId) {
        try {
            return recordItem(legacyItemId) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isInstantPotion(int potionDamage) {
        try {
            btw.modern.ItemPotion potion = btw.modern.Item.potion;
            return potion != null && potion.isEffectInstant(potionDamage);
        } catch (Throwable t) {
            return false;
        }
    }
}
