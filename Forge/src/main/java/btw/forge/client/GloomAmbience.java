package btw.forge.client;

import btw.forge.BTWForgeMod;
import btw.forge.BTWNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;

/**
 * FC's gloom ambience (audit sounds S11): a port of the client-only EntityPlayerSP.UpdateGloomState and
 * PlaySoundInRandomDirection (vanilla/client EntityPlayerSP.java:669-677, 757-838), which lived in the replaced
 * 1.5.2 client player. 1.5.2 ran it once per player tick from EntityPlayer.onUpdate -> UpdateModStatusVariables
 * (vanilla/client EntityPlayer.java:369, :3186-3188):
 * <ul>
 *   <li>reaching Terror (gloom level 3): the enderman stare, at the player;</li>
 *   <li>in Gloom (1): cave ambience 5 blocks away in a random direction, its chance and volume rising over
 *       the minute spent in gloom;</li>
 *   <li>in Dread (2) and Terror (3): cave ambience at full chance and volume, plus wolf growls (rising
 *       over the minute in Dread, full in Terror).</li>
 * </ul>
 * The gloom level is the server's, synced to this client for the HUD (BTWNetwork.clientGloomLevel, the
 * 1.5.2 DataWatcher entry EntityPlayer.GetGloomLevel read, EntityPlayer.java:2890-2893). Everything plays on this
 * client only, as 1.5.2's World.playSound did, through the shared client sink ({@link LegacyClientSound#playLocal}).
 * The in-gloom counter and the previous level are the 1.5.2 client player's own fields, so they restart
 * with a new local player (login, respawn, dimension change), as they did with a new EntityPlayerSP, once that
 * player's own level has been synced ({@link #NEW_PLAYER_SYNC_GRACE_TICKS}).
 */
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID, value = Dist.CLIENT)
public final class GloomAmbience {

    private static final Logger LOGGER = LogManager.getLogger("BTW-GloomAmbience");

    // vanilla/client EntityPlayerSP.java:669-677
    private static final float m_fMinimumGloomCaveSoundChance = 0.01F; // 1/5 seconds
    private static final float m_fMaximumGloomCaveSoundChance = 0.05F; // 1/second
    private static final float m_fMinimumGloomCaveSoundVolume = 0.1F;
    private static final float m_fMaximumGloomCaveSoundVolume = 4.0F;

    private static final float m_fMinimumGloomGrowlSoundChance = 0.01F; // 1/5 seconds
    private static final float m_fMaximumGloomGrowlSoundChance = 0.05F; // 1/second
    private static final float m_fMinimumGloomGrowlSoundVolume = 0.1F;
    private static final float m_fMaximumGloomGrowlSoundVolume = 4.0F;

    /** EntityPlayer.m_iGloomCounterBetweenStateChanges (1 minute). */
    private static final int m_iGloomCounterBetweenStateChanges = btw.modern.EntityPlayer.m_iGloomCounterBetweenStateChanges;

    /** 1.5.2 EntityPlayer.yOffset: a player's posY was its eye height above the feet. */
    private static final double PLAYER_Y_OFFSET = 1.62D;

    /**
     * Client ticks a new local player waits for its own server's gloom level. BTWNetwork.clientGloomLevel is only
     * replaced by the next PenaltySync (sent every server tick, ServerPlayerMixin), so right after a respawn,
     * dimension change or world switch it can still hold the previous player's level; 1.5.2's new EntityPlayerSP
     * started from its own DataWatcher's 0 instead. A reading that differs from the stale one ends the wait at once.
     */
    private static final int NEW_PLAYER_SYNC_GRACE_TICKS = 20;

    /** The local player the state below belongs to; weak, so a closed world's player is not kept reachable. */
    private static WeakReference<LocalPlayer> trackedPlayer = new WeakReference<>(null);
    private static int m_iPreviousGloomLevel;
    private static int m_iInGloomCounter;
    private static int m_iNewPlayerGraceTicks;
    private static int m_iStaleGloomLevel;

    private static boolean failureLogged;

    private GloomAmbience() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            // 1.5.2 ran this from the player's own update, which a paused game does not run.
            if (player == null || mc.level == null || mc.isPaused()) return;
            int iGloomLevel = BTWNetwork.clientGloomLevel;
            if (trackedPlayer.get() != player) {
                trackedPlayer = new WeakReference<>(player);
                m_iPreviousGloomLevel = 0;
                m_iInGloomCounter = 0;
                m_iStaleGloomLevel = iGloomLevel;
                m_iNewPlayerGraceTicks = NEW_PLAYER_SYNC_GRACE_TICKS;
            }
            if (m_iNewPlayerGraceTicks > 0) {
                // the previous player's level (e.g. Terror at death) must not play the stare for this one
                if (iGloomLevel == m_iStaleGloomLevel) {
                    m_iNewPlayerGraceTicks--;
                    return;
                }
                m_iNewPlayerGraceTicks = 0;
            }
            updateGloomState(player, iGloomLevel);
        } catch (Throwable t) {
            // client tick path: never let it escape, report the first failure only
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("Gloom ambience failed (further failures are not logged): {}", t.toString(), t);
            }
        }
    }

    /** EntityPlayerSP.UpdateGloomState (vanilla/client EntityPlayerSP.java:757-821). */
    private static void updateGloomState(LocalPlayer player, int iGloomLevel) {
        RandomSource rand = player.getRandom();

        if (m_iPreviousGloomLevel != iGloomLevel) {
            m_iInGloomCounter = 0;
            m_iPreviousGloomLevel = iGloomLevel;

            if (iGloomLevel == 3) {
                // EntityPlayerSP.playSound (:663-666): at the feet (posY - yOffset), this client only
                LegacyClientSound.playLocal(player.getX(), player.getY(), player.getZ(),
                        "mob.endermen.stare", 1.0F, 1.0F, false);
            }
        }

        if (iGloomLevel > 0) {
            m_iInGloomCounter++;

            float fCounterProgress = (float) m_iInGloomCounter / (float) m_iGloomCounterBetweenStateChanges;

            if (fCounterProgress > 1.0F) {
                fCounterProgress = 1.0F;
            }

            // general cave sounds

            float fCaveSoundChance = m_fMaximumGloomCaveSoundChance;
            float fCaveSoundVolume = m_fMaximumGloomCaveSoundVolume;

            if (iGloomLevel > 1) {
                // growls

                float fGrowlSoundChance = m_fMaximumGloomGrowlSoundChance;
                float fGrowlSoundVolume = m_fMaximumGloomGrowlSoundVolume;

                if (iGloomLevel <= 2) {
                    fGrowlSoundChance = m_fMinimumGloomGrowlSoundChance
                            + (m_fMaximumGloomGrowlSoundChance - m_fMinimumGloomGrowlSoundChance) * fCounterProgress;
                    fGrowlSoundVolume = m_fMinimumGloomGrowlSoundVolume
                            + (m_fMaximumGloomGrowlSoundVolume - m_fMinimumGloomGrowlSoundVolume) * fCounterProgress;
                }

                if (rand.nextFloat() < fGrowlSoundChance) {
                    playSoundInRandomDirection(player, rand, "mob.wolf.growl", fGrowlSoundVolume,
                            (rand.nextFloat() - rand.nextFloat()) * 0.05F + 0.55F, 5D);
                }
            } else {
                fCaveSoundChance = m_fMinimumGloomCaveSoundChance
                        + (m_fMaximumGloomCaveSoundChance - m_fMinimumGloomCaveSoundChance) * fCounterProgress;
                fCaveSoundVolume = m_fMinimumGloomCaveSoundVolume
                        + (m_fMaximumGloomCaveSoundVolume - m_fMinimumGloomCaveSoundVolume) * fCounterProgress;
            }

            if (rand.nextFloat() < fCaveSoundChance) {
                playSoundInRandomDirection(player, rand, "ambient.cave.cave", fCaveSoundVolume,
                        0.5F + rand.nextFloat(), 5D);
            }
        }
    }

    /**
     * EntityPlayerSP.PlaySoundInRandomDirection (vanilla/client EntityPlayerSP.java:823-838): {@code dDistance}
     * blocks away horizontally at the player's posY (eye height in 1.5.2), on this client only. The random
     * yaw in [0, 1) times 360 is fed to sin/cos as radians, exactly as 1.5.2 does.
     */
    private static void playSoundInRandomDirection(LocalPlayer player, RandomSource rand, String sSoundName,
                                                   float fVolume, float fPitch, double dDistance) {
        double dRandomYaw = rand.nextDouble();

        double dXOffset = (double) -Mth.sin((float) (dRandomYaw * 360D)) * dDistance;
        double dZOffset = (double) Mth.cos((float) (dRandomYaw * 360D)) * dDistance;

        LegacyClientSound.playLocal(player.getX() + dXOffset, player.getY() + PLAYER_Y_OFFSET,
                player.getZ() + dZOffset, sSoundName, fVolume, fPitch, false);
    }
}
