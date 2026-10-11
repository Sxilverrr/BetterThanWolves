package btw.forge;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays FC legacy sound names (e.g., "random.pop", "step.grass") on MC 1.20.1. Used by
 * {@link EntityBridge}, {@link LivingEntityBridge} and {@link PlayerBridge} when FC code calls
 * playSound(String, float, float), and by WorldBridge to resolve names.
 *
 * <p>Name resolution is {@link LegacySounds}' job: every 1.5.2 sound name plays its 1.5.2 file pool
 * through a registered betterthanwolves sound event. This class used to keep its own hand table of
 * 1.20.1 replacement events, which picked wrong files (minecart.base -> minecart/inside, creeper hurt
 * -> fuse hiss, numbered cow moos -> random moos, ...) and missed names entirely (audit S4).</p>
 */
public class SoundMapping {

    private static final Logger LOGGER = LogManager.getLogger("BTW-SoundMapping");

    private static final AtomicBoolean PLAY_FAILURE_LOGGED = new AtomicBoolean();

    /** Marks a {@link #SOUND_MAP} entry that is not a sound name (see "fcwhitesmoke"). */
    private static final String NOT_A_SOUND = "not a sound";

    /**
     * Coverage manifest, not a lookup table: every 1.5.2 sound name that FC code (Common/Client/Server)
     * or the frozen 1.5.2 entity classes play as a literal, with where it is played. tools/BridgeAudit
     * reads these put() lines for its static sound-coverage check, and {@link #verifyCoverage()} checks
     * at startup that each one still resolves through {@link LegacySounds}. LegacySounds itself covers
     * every 1.5.2 sound, including names built at runtime ("step." + step sound, "note." + instrument).
     */
    private static final Map<String, String> SOUND_MAP = new LinkedHashMap<>();

    static {
        SOUND_MAP.put("ambient.cave.cave4", "FCEntityVillager, FCItemNetherStar");
        SOUND_MAP.put("ambient.weather.thunder", "FCBetterThanWolves, FCContainerInfernalEnchanter, FCEntityVillager");
        SOUND_MAP.put("damage.fallbig", "1.5.2 EntityLiving");
        SOUND_MAP.put("damage.fallsmall", "1.5.2 EntityLiving");
        SOUND_MAP.put("damage.hit", "1.5.2 EntityLiving");
        SOUND_MAP.put("damage.thorns", "1.5.2 EnchantmentThorns (no 1.5.2 file: silent there too)");
        SOUND_MAP.put("dig.sand", "FCBetterThanWolves");
        SOUND_MAP.put("fire.fire", "FCBlockCampfire, FCBlockFurnace, FCBlockLadderOnFire");
        SOUND_MAP.put("fire.ignite", "FCItemFlintAndSteel");
        SOUND_MAP.put("fireworks.launch", "1.5.2 EntityFireworkRocket");
        SOUND_MAP.put("liquid.lavapop", "FCBetterThanWolves");
        SOUND_MAP.put("liquid.splash", "FCEntitySquid, 1.5.2 Entity");
        SOUND_MAP.put("liquid.swim", "FCEntitySlime, 1.5.2 Entity");
        SOUND_MAP.put("minecart.base", "FCBetterThanWolves, FCBlockMillStone, FCBlockPulley, FCBlockSaw");
        SOUND_MAP.put("mob.attack", "1.5.2 EntitySlime (no 1.5.2 file: silent there too)");
        SOUND_MAP.put("mob.bat.death", "1.5.2 EntityBat");
        SOUND_MAP.put("mob.bat.hurt", "1.5.2 EntityBat");
        SOUND_MAP.put("mob.bat.idle", "1.5.2 EntityBat");
        SOUND_MAP.put("mob.blaze.breathe", "1.5.2 EntityBlaze");
        SOUND_MAP.put("mob.blaze.death", "1.5.2 EntityBlaze");
        SOUND_MAP.put("mob.blaze.hit", "1.5.2 EntityBlaze");
        SOUND_MAP.put("mob.cat.hitt", "1.5.2 EntityOcelot");
        SOUND_MAP.put("mob.cat.meow", "1.5.2 EntityOcelot");
        SOUND_MAP.put("mob.cat.purr", "1.5.2 EntityOcelot");
        SOUND_MAP.put("mob.cat.purreow", "1.5.2 EntityOcelot");
        SOUND_MAP.put("mob.chicken.hurt", "FCBetterThanWolves, FCEntityChicken, 1.5.2 EntityChicken");
        SOUND_MAP.put("mob.chicken.plop", "1.5.2 EntityChicken");
        SOUND_MAP.put("mob.chicken.say", "FCEntityChicken, 1.5.2 EntityChicken");
        SOUND_MAP.put("mob.chicken.step", "1.5.2 EntityChicken");
        SOUND_MAP.put("mob.cow.hurt", "FCBetterThanWolves, FCEntityCow, 1.5.2 EntityCow");
        SOUND_MAP.put("mob.cow.say", "FCEntityCow, 1.5.2 EntityCow");
        SOUND_MAP.put("mob.cow.say2", "FCBlockBellows");
        SOUND_MAP.put("mob.cow.say4", "FCBlockBellows");
        SOUND_MAP.put("mob.cow.step", "1.5.2 EntityCow");
        SOUND_MAP.put("mob.creeper.death", "1.5.2 EntityCreeper");
        SOUND_MAP.put("mob.creeper.say", "FCEntityCreeper, 1.5.2 EntityCreeper");
        SOUND_MAP.put("mob.enderdragon.growl", "FCBetterThanWolves, FCEntityVillager, 1.5.2 EntityDragon");
        SOUND_MAP.put("mob.enderdragon.hit", "1.5.2 EntityDragon");
        SOUND_MAP.put("mob.enderdragon.wings", "1.5.2 EntityDragon");
        SOUND_MAP.put("mob.endermen.death", "1.5.2 EntityEnderman");
        SOUND_MAP.put("mob.endermen.hit", "FCBetterThanWolves, 1.5.2 EntityEnderman");
        SOUND_MAP.put("mob.endermen.idle", "1.5.2 EntityEnderman");
        SOUND_MAP.put("mob.endermen.portal", "FCBetterThanWolves, 1.5.2 EntityEnderman");
        SOUND_MAP.put("mob.endermen.scream", "1.5.2 EntityEnderman");
        SOUND_MAP.put("mob.endermen.stare", "FCBlockEnderChest, FCEntityEnderman, 1.5.2 EntityEnderman");
        SOUND_MAP.put("mob.ghast.affectionate scream", "FCBetterThanWolves, FCEntityWitch");
        SOUND_MAP.put("mob.ghast.death", "1.5.2 EntityGhast");
        SOUND_MAP.put("mob.ghast.fireball", "FCBetterThanWolves, FCBlockBBQ, FCBlockCampfire");
        SOUND_MAP.put("mob.ghast.moan", "FCBetterThanWolves, FCBlockBucketCement, FCBlockCement");
        SOUND_MAP.put("mob.ghast.scream", "FCBetterThanWolves, FCBlockMillStone, FCTileEntityBeacon");
        SOUND_MAP.put("mob.irongolem.death", "FCBetterThanWolves, 1.5.2 EntityIronGolem");
        SOUND_MAP.put("mob.irongolem.hit", "1.5.2 EntityIronGolem");
        SOUND_MAP.put("mob.irongolem.throw", "1.5.2 EntityIronGolem");
        SOUND_MAP.put("mob.irongolem.walk", "FCBetterThanWolves, 1.5.2 EntityIronGolem");
        SOUND_MAP.put("mob.magmacube.big", "1.5.2 EntityMagmaCube");
        SOUND_MAP.put("mob.magmacube.small", "1.5.2 EntityMagmaCube");
        SOUND_MAP.put("mob.pig.death", "FCBetterThanWolves, FCEntityPig, 1.5.2 EntityPig");
        SOUND_MAP.put("mob.pig.say", "FCEntityPig, 1.5.2 EntityPig");
        SOUND_MAP.put("mob.pig.step", "1.5.2 EntityPig");
        SOUND_MAP.put("mob.sheep.say", "1.5.2 EntitySheep");
        SOUND_MAP.put("mob.sheep.shear", "FCBetterThanWolves, FCEntitySheep, FCItemShears");
        SOUND_MAP.put("mob.sheep.step", "1.5.2 EntitySheep");
        SOUND_MAP.put("mob.silverfish.hit", "1.5.2 EntitySilverfish");
        SOUND_MAP.put("mob.silverfish.kill", "1.5.2 EntitySilverfish");
        SOUND_MAP.put("mob.silverfish.say", "1.5.2 EntitySilverfish");
        SOUND_MAP.put("mob.silverfish.step", "FCBlockSilverfish, 1.5.2 EntitySilverfish");
        SOUND_MAP.put("mob.skeleton.death", "1.5.2 EntitySkeleton");
        SOUND_MAP.put("mob.skeleton.hurt", "1.5.2 EntitySkeleton");
        SOUND_MAP.put("mob.skeleton.say", "1.5.2 EntitySkeleton");
        SOUND_MAP.put("mob.skeleton.step", "1.5.2 EntitySkeleton");
        SOUND_MAP.put("mob.slime.attack", "FCBetterThanWolves, FCEntityChicken, FCEntitySheep");
        SOUND_MAP.put("mob.slime.big", "FCEntitySquid");
        SOUND_MAP.put("mob.slime.small", "FCStepSoundSquish");
        SOUND_MAP.put("mob.spider.death", "1.5.2 EntitySpider");
        SOUND_MAP.put("mob.spider.say", "1.5.2 EntitySpider");
        SOUND_MAP.put("mob.spider.step", "1.5.2 EntitySpider");
        SOUND_MAP.put("mob.villager.default", "1.5.2 EntityVillager (no 1.5.2 file: silent there too)");
        SOUND_MAP.put("mob.villager.defaultdeath", "1.5.2 EntityVillager (no 1.5.2 file: silent there too)");
        SOUND_MAP.put("mob.villager.defaulthurt", "1.5.2 EntityVillager (no 1.5.2 file: silent there too)");
        SOUND_MAP.put("mob.witch.death", "1.5.2 EntityWitch (no 1.5.2 file: silent there too)");
        SOUND_MAP.put("mob.witch.hurt", "1.5.2 EntityWitch (no 1.5.2 file: silent there too)");
        SOUND_MAP.put("mob.witch.idle", "1.5.2 EntityWitch (no 1.5.2 file: silent there too)");
        SOUND_MAP.put("mob.wither.death", "FCBetterThanWolves, FCBlockBeacon, FCTileEntityBeacon");
        SOUND_MAP.put("mob.wither.hurt", "1.5.2 EntityWither");
        SOUND_MAP.put("mob.wither.idle", "1.5.2 EntityWither");
        SOUND_MAP.put("mob.wither.shoot", "FCEntityWitch");
        SOUND_MAP.put("mob.wither.spawn", "FCBlockBeacon, FCTileEntityBeacon");
        SOUND_MAP.put("mob.wolf.bark", "FCEntityWolf, 1.5.2 EntityWolf");
        SOUND_MAP.put("mob.wolf.death", "FCEntityWolfDire, 1.5.2 EntityWolf");
        SOUND_MAP.put("mob.wolf.growl", "FCBetterThanWolves, FCEntityWolf, FCEntityWolfDire");
        SOUND_MAP.put("mob.wolf.howl", "FCBetterThanWolves");
        SOUND_MAP.put("mob.wolf.hurt", "FCBetterThanWolves, FCBlockCompanionCube, FCBlockMillStone");
        SOUND_MAP.put("mob.wolf.panting", "FCEntityWolf, 1.5.2 EntityWolf");
        SOUND_MAP.put("mob.wolf.shake", "1.5.2 EntityWolf");
        SOUND_MAP.put("mob.wolf.step", "FCEntityWolfDire, 1.5.2 EntityWolf");
        SOUND_MAP.put("mob.wolf.whine", "FCBetterThanWolves, FCBlockCompanionCube, FCEntityWolf");
        SOUND_MAP.put("mob.zombie.death", "1.5.2 EntityZombie");
        SOUND_MAP.put("mob.zombie.hurt", "1.5.2 EntityZombie");
        SOUND_MAP.put("mob.zombie.remedy", "1.5.2 EntityZombie");
        SOUND_MAP.put("mob.zombie.say", "FCEntityZombie, 1.5.2 EntityZombie");
        SOUND_MAP.put("mob.zombie.step", "1.5.2 EntityZombie");
        SOUND_MAP.put("mob.zombie.wood", "FCBetterThanWolves, FCItemAxe");
        SOUND_MAP.put("mob.zombie.woodbreak", "FCBetterThanWolves, FCItemAxe, FCItemCarvingBone");
        SOUND_MAP.put("mob.zombiepig.zpig", "1.5.2 EntityPigZombie");
        SOUND_MAP.put("mob.zombiepig.zpigangry", "FCBetterThanWolves, 1.5.2 EntityPigZombie");
        SOUND_MAP.put("mob.zombiepig.zpigdeath", "1.5.2 EntityPigZombie");
        SOUND_MAP.put("mob.zombiepig.zpighurt", "1.5.2 EntityPigZombie");
        SOUND_MAP.put("note.bass", "FCBlockStakeString");
        SOUND_MAP.put("portal.portal", "FCEntityWolf");
        SOUND_MAP.put("random.anvil_break", "FCItemBlockMillStone");
        SOUND_MAP.put("random.anvil_land", "FCBetterThanWolves, FCEntityVillager, FCItemBlockDeadWeight");
        SOUND_MAP.put("random.anvil_use", "FCItemArmor, FCItemBlockDeadWeight, FCItemShears");
        SOUND_MAP.put("random.bow", "FCBlockStake, FCBlockWeb, FCEntityCow");
        SOUND_MAP.put("random.bowhit", "1.5.2 EntityArrow");
        SOUND_MAP.put("random.break", "FCItemAxe, FCItemChiselIron, FCItemCompositeBow");
        SOUND_MAP.put("random.breath", "1.5.2 EntityPlayer");
        SOUND_MAP.put("random.burp", "FCBetterThanWolves");
        SOUND_MAP.put("random.chestclosed", "FCEntityWindMill, FCEntityWindMillVertical");
        SOUND_MAP.put("random.chestopen", "FCBlockAxle, FCBlockGearBox");
        SOUND_MAP.put("random.classic_hurt", "FCBetterThanWolves, FCEntityAIVillagerMate, FCEntityVillager");
        SOUND_MAP.put("random.click", "FCBetterThanWolves, FCBlockHandCrank, FCTileEntityTurntable");
        SOUND_MAP.put("random.drink", "FCBlockSoulforgedSteel, 1.5.2 EntityPlayer");
        SOUND_MAP.put("random.eat", "FCBetterThanWolves, FCEntitySquid, FCItemCarvingBone");
        SOUND_MAP.put("random.explode", "FCBetterThanWolves, FCEntityWolf, 1.5.2 EntityLightningBolt");
        SOUND_MAP.put("random.fizz", "FCBetterThanWolves, FCBlockBBQ, FCBlockIce");
        SOUND_MAP.put("random.fuse", "FCBetterThanWolves, FCBlockMiningCharge, FCEntityDynamite");
        SOUND_MAP.put("random.glass", "FCBetterThanWolves");
        SOUND_MAP.put("random.levelup", "FCEntityVillager, 1.5.2 EntityPlayer");
        SOUND_MAP.put("random.orb", "1.5.2 EntityXPOrb");
        SOUND_MAP.put("random.pop", "FCBetterThanWolves, FCBlockAnchor, FCBlockCampfire");
        SOUND_MAP.put("random.splash", "1.5.2 EntityFishHook");
        SOUND_MAP.put("step.cloth", "FCItemArmor, FCItemKnitting");
        SOUND_MAP.put("step.grass", "FCItemWickerWeaving");
        SOUND_MAP.put("step.gravel", "FCBetterThanWolves, FCBlockBasketWicker, FCBlockPulley");
        SOUND_MAP.put("step.wood", "FCItemKnitting");
        // FC's white smoke particle (FCBlockKiln, FCBlockCampfire, FCBlockLogSmouldering spawnParticle).
        // Listed only because BridgeAudit's sound-literal pattern also matches it.
        SOUND_MAP.put("fcwhitesmoke", NOT_A_SOUND);
    }

    /**
     * Returns the MC SoundEvent for the given FC sound name, or null if 1.5.2 had no such sound.
     * Delegates to {@link LegacySounds#resolve}.
     */
    public static SoundEvent get(String fcSoundName) {
        return LegacySounds.resolve(fcSoundName);
    }

    /**
     * Plays an FC-named sound at the given entity's position, for every nearby player.
     * A name 1.5.2 had no sound for plays nothing (LegacySounds logs it once).
     *
     * @param entity the MC entity at whose position the sound should play
     * @param fcSoundName the FC legacy sound name (e.g., "random.pop")
     * @param volume sound volume
     * @param pitch sound pitch
     */
    public static void playAtEntity(Entity entity, String fcSoundName, float volume, float pitch) {
        if (fcSoundName == null || entity == null) return;

        SoundEvent event = LegacySounds.resolve(fcSoundName);
        if (event == null) return;

        try {
            entity.level().playSound(
                    null, // null = play for all nearby players
                    entity.getX(), entity.getY(), entity.getZ(),
                    event,
                    SoundSource.PLAYERS,
                    volume,
                    pitch);
        } catch (Throwable t) {
            if (PLAY_FAILURE_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("Could not play FC sound '{}' at {} (further failures not logged): {}",
                        fcSoundName, entity, t.toString());
            }
        }
    }

    /**
     * Startup check, run once from FMLCommonSetupEvent (registries are populated by then): every
     * sound name in {@link #SOUND_MAP} resolves, except names 1.5.2 had no sound file for. Never throws.
     */
    public static void verifyCoverage() {
        try {
            List<String> unresolved = new ArrayList<>();
            int checked = 0;
            for (Map.Entry<String, String> e : SOUND_MAP.entrySet()) {
                String name = e.getKey();
                if (NOT_A_SOUND.equals(e.getValue()) || LegacySounds.isSilentIn152(name)) continue;
                checked++;
                if (!LegacySounds.isKnown(name)) unresolved.add(name);
            }
            if (unresolved.isEmpty()) {
                LOGGER.info("Legacy sounds: all {} 1.5.2 sound names FC plays resolve ({} sound events)",
                        checked, LegacySounds.eventCount());
            } else {
                LOGGER.warn("Legacy sounds: {} of {} 1.5.2 sound names FC plays have no sound event and stay "
                        + "silent: {}", unresolved.size(), checked, unresolved);
            }
        } catch (Throwable t) {
            LOGGER.warn("Legacy sound coverage check failed: {}", t.toString());
        }
    }
}
