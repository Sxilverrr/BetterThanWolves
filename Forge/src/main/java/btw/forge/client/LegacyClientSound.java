package btw.forge.client;

import btw.forge.BTWNetwork;
import btw.forge.LegacySounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one client sink for FC's client-local sounds and aux effects (audit sounds S1/S3, MPR-6): the 1.5.2
 * WorldClient + RenderGlobal contract every client-side FC world delegates to.
 *
 * <ul>
 *   <li>{@link #playLocal}: 1.5.2 WorldClient.playSound (vanilla/client WorldClient.java:483-505) -- plays on
 *       this client only, within 16 * max(1, volume) blocks of the view entity, delayed by distance / 40 s
 *       when asked. The 1.5.2 name goes through {@link LegacySounds#resolve} (one resolver, sounds S4).</li>
 *   <li>{@link #playAux}: 1.5.2 RenderGlobal.playAuxSFX (vanilla/client RenderGlobal.java:2169-2399) and
 *       broadcastSound (:2122-2164). Vanilla ids become their 1.20.1 local level event (AuxSfxMapping);
 *       FC ids (&gt;= 2222) run FC's own FCAddOnHandler.ClientPlayCustomAuxFX (the FCMOD default branch,
 *       :2394 / :2159) with a populated legacy Minecraft facade, so FC's exact sounds and particles play.</li>
 * </ul>
 *
 * <p>World.playSoundEffect / playSoundAtEntity / playSoundToNearExcept stay no-ops in the client worlds:
 * 1.5.2 RenderGlobal implemented them as empty methods (:1812, :1817); the server broadcasts those.</p>
 *
 * <p>Render (main) thread only: a call from any other thread is re-queued onto it. Nothing here throws;
 * each failure is logged once.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class LegacyClientSound {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ClientSound");

    /** First FC custom aux-FX id (FCBetterThanWolves m_iAnimalBirthingAuxFXID); lower ids are vanilla 1.5.2. */
    public static final int FIRST_FC_AUX_ID = 2222;

    private static final String FC_ADDON_HANDLER_CLASS = "net.minecraft.src.btw.core.FCAddOnHandler";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** FCAddOnHandler.ClientPlayCustomAuxFX, looked up once (main thread only). */
    private static Method fcAuxHandler;
    private static boolean fcAuxHandlerMissing;

    /** The legacy Minecraft facade handed to FC's aux handler, built once (main thread only). */
    private static btw.modern.Minecraft legacyMc;
    private static LocalPlayerFacade legacyPlayer;
    private static boolean legacyPlayerFailed;

    private LegacyClientSound() {}

    // ------------------------------------------------------------------
    // World.playSound (client-local)
    // ------------------------------------------------------------------

    /**
     * 1.5.2 WorldClient.playSound(x, y, z, name, volume, pitch, distanceDelay): plays the legacy sound on this
     * client only. Unresolvable names (null from {@link LegacySounds#resolve}) are skipped.
     */
    public static void playLocal(double x, double y, double z, String legacyName, float volume, float pitch,
                                 boolean distanceDelay) {
        if (legacyName == null || legacyName.isEmpty()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (!mc.isSameThread()) {
                mc.execute(() -> playLocal(x, y, z, legacyName, volume, pitch, distanceDelay));
                return;
            }
            ClientLevel level = mc.level;
            if (level == null) return;
            // WorldClient.java:485-494: audible within 16 * max(1, volume) of renderViewEntity, whose posY was
            // its eye level for a player (yOffset 1.62).
            float range = 16.0F * Math.max(1.0F, volume);
            Entity view = mc.getCameraEntity();
            if (view != null && view.getEyePosition().distanceToSqr(x, y, z) >= (double) (range * range)) return;
            SoundEvent event = LegacySounds.resolve(legacyName);
            if (event == null) return;
            // ClientLevel.playSound applies the same `delay && dist^2 > 100 -> sqrt(dist^2) / 40 s` as
            // WorldClient.java:496-500.
            level.playLocalSound(x, y, z, event, sourceFor(legacyName), volume, pitch, distanceDelay);
        } catch (Throwable t) {
            warnOnce("local|" + legacyName + "|" + t.getClass().getName(),
                    "Playing legacy sound '" + legacyName + "' failed", t);
        }
    }

    /**
     * 1.5.2 had a single volume slider; the 1.20.1 category is derived from the name, so the matching
     * modern slider applies.
     */
    private static SoundSource sourceFor(String legacyName) {
        if (legacyName.startsWith("ambient.weather.")) return SoundSource.WEATHER;
        if (legacyName.startsWith("ambient.")) return SoundSource.AMBIENT;
        if (legacyName.startsWith("mob.")) return SoundSource.NEUTRAL;
        if (legacyName.startsWith("records.") || legacyName.startsWith("note.")) return SoundSource.RECORDS;
        return SoundSource.BLOCKS;
    }

    // ------------------------------------------------------------------
    // World.playAuxSFX / playAuxSFXAtEntity / func_82739_e (client)
    // ------------------------------------------------------------------

    /** 1.5.2 RenderGlobal.playAuxSFX(player, id, x, y, z, data) on this client. */
    public static void playAux(int sfxId, int x, int y, int z, int data) {
        playAux(sfxId, x, y, z, data, false);
    }

    /**
     * As {@link #playAux(int, int, int, int, int)}; {@code global} selects 1.5.2 RenderGlobal.broadcastSound
     * (World.func_82739_e): vanilla 1013 / 1018 become 1.20.1 global level events, FC ids go to the same FC
     * handler (broadcastSound's FCMOD default branch, RenderGlobal.java:2157-2159).
     */
    public static void playAux(int sfxId, int x, int y, int z, int data, boolean global) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (!mc.isSameThread()) {
                mc.execute(() -> playAux(sfxId, x, y, z, data, global));
                return;
            }
            ClientLevel level = mc.level;
            if (level == null) return;
            if (sfxId >= FIRST_FC_AUX_ID) {
                playFcAux(mc, level, sfxId, x, y, z, data);
            } else if (global) {
                int[] event = BTWNetwork.legacyAuxToLevelEvent(sfxId, data, true);
                if (event != null) level.globalLevelEvent(event[0], new BlockPos(x, y, z), event[1]);
            } else {
                playVanillaAux(level, sfxId, x, y, z, data);
            }
        } catch (Throwable t) {
            warnOnce("aux|" + sfxId + "|" + t.getClass().getName(), "Client aux-SFX " + sfxId + " failed", t);
        }
    }

    /**
     * A vanilla 1.5.2 aux id: its 1.20.1 level event, played locally (LevelRenderer.levelEvent), plus the
     * parts 1.20.1 has no event for, exactly as WorldBridge plays them on the server side.
     */
    private static void playVanillaAux(ClientLevel level, int sfxId, int x, int y, int z, int data) {
        int[] event = BTWNetwork.legacyAuxToLevelEvent(sfxId, data, false);
        if (event != null) {
            level.levelEvent(null, event[0], new BlockPos(x, y, z), event[1]);
        }
        RandomSource rand = level.random;
        double px = x + 0.5D, py = y + 0.5D, pz = z + 0.5D;
        switch (sfxId) {
            case 1003:  // door open/close picked at random (RenderGlobal.java:2198-2208); no 1.20.1 event
                playLocal(px, py, pz, Math.random() < 0.5D ? "random.door_open" : "random.door_close",
                        1.0F, rand.nextFloat() * 0.1F + 0.9F, false);
                break;
            case 1016:  // FCMOD: extra slow zombie moan on villager infection (RenderGlobal.java:2260-2263)
                playLocal(px, py, pz, "mob.zombie.say", 1.0F + rand.nextFloat(), rand.nextFloat() * 0.2F + 0.5F,
                        false);
                break;
            case 2001:  // a block 1.20.1 cannot express: no particles, but its 1.5.2 break sound (:2303-2312)
                if (event == null) {
                    int blockId = data & 4095;
                    if (blockId > 0 && blockId < btw.modern.Block.blocksList.length) {
                        btw.modern.Block block = btw.modern.Block.blocksList[blockId];
                        if (block != null && block.stepSound != null) {
                            playLocal(px, py, pz, block.stepSound.getBreakSound(),
                                    (block.stepSound.getVolume() + 1.0F) / 2.0F, block.stepSound.getPitch() * 0.8F,
                                    false);
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * FC's own client aux handler, invoked reflectively as FCClientPayloadHandler does for
     * ClientCustomPacketReceived (FC code is not on the bridge's compile path). The 1.5.2 caller passed the
     * acting player for local calls and null for Packet61; FC's handler never reads it, so null is passed.
     */
    private static void playFcAux(Minecraft mc, ClientLevel level, int sfxId, int x, int y, int z, int data) {
        Method handler = fcAuxHandler();
        if (handler == null) return;
        // The effect being played, for the FX FC hands the effect renderer (ParticleEngineBridge.addEffect).
        // Saved and restored, not cleared, in case FC's handler ever plays another aux effect from inside.
        int outerId = ParticleEngineBridge.auxId, outerData = ParticleEngineBridge.auxData;
        ParticleEngineBridge.auxId = sfxId;
        ParticleEngineBridge.auxData = data;
        try {
            handler.invoke(null, legacyMinecraft(mc), ClientFcWorld.forLevel(level), null, sfxId, x, y, z, data);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            warnOnce("fcaux|" + sfxId + "|" + cause.getClass().getName(),
                    "FC ClientPlayCustomAuxFX(" + sfxId + ") threw", cause);
        } catch (Throwable t) {
            warnOnce("fcaux|" + sfxId + "|" + t.getClass().getName(),
                    "Could not hand aux-FX " + sfxId + " to FCAddOnHandler", t);
        } finally {
            ParticleEngineBridge.auxId = outerId;
            ParticleEngineBridge.auxData = outerData;
        }
    }

    private static Method fcAuxHandler() {
        Method handler = fcAuxHandler;
        if (handler == null && !fcAuxHandlerMissing) {
            try {
                handler = Class.forName(FC_ADDON_HANDLER_CLASS).getMethod("ClientPlayCustomAuxFX",
                        btw.modern.Minecraft.class, btw.modern.World.class, btw.modern.EntityPlayer.class,
                        int.class, int.class, int.class, int.class, int.class);
                fcAuxHandler = handler;
            } catch (Throwable t) {
                fcAuxHandlerMissing = true;
                warnOnce("fcaux|lookup", "FCAddOnHandler.ClientPlayCustomAuxFX not found; FC aux effects are silent", t);
            }
        }
        return handler;
    }

    /**
     * The 1.5.2 Minecraft fields FC's ClientPlayCustomAuxFX dereferences (verify:sounds missed[2]):
     * gameSettings.particleSetting (Client FCBetterThanWolves.java:2893), effectRenderer (block-destroy and
     * digging FX, :3231/:3249/:3390/:3609), renderEngine (EntityDiggingFX ctor, :3386/:3605) and thePlayer
     * (wolf-howl volume by the local player's height, :3468). theWorld is unused there (FC is handed the
     * world) and stays null.
     */
    private static btw.modern.Minecraft legacyMinecraft(Minecraft mc) {
        btw.modern.Minecraft legacy = legacyMc;
        if (legacy == null) {
            legacy = new btw.modern.Minecraft();
            legacy.gameSettings = new btw.modern.GameSettings();
            legacy.effectRenderer = new ParticleEngineBridge();
            legacy.renderEngine = new btw.modern.RenderEngine();
            legacyMc = legacy;
        }
        // 1.5.2 particleSetting 0 all / 1 decreased / 2 minimal == 1.20.1 ParticleStatus ids.
        legacy.gameSettings.particleSetting = mc.options.particles().get().getId();
        legacy.thePlayer = localPlayer(mc);
        return legacy;
    }

    /**
     * The local player as FC's client code sees it (1.5.2 Minecraft.thePlayer, and the player the client
     * world's entity queries return, {@link ClientFcWorld}), refreshed from the LocalPlayer; null without a
     * player, off the main thread, or if the facade cannot be built. Main thread only.
     */
    static btw.modern.EntityClientPlayerMP localPlayer(Minecraft mc) {
        net.minecraft.client.player.LocalPlayer player = mc.player;
        if (player == null || legacyPlayerFailed || !mc.isSameThread()) return null;
        LocalPlayerFacade legacy = legacyPlayer;
        if (legacy == null) {
            try {
                legacy = new LocalPlayerFacade();
                legacyPlayer = legacy;
            } catch (Throwable t) {
                legacyPlayerFailed = true;
                warnOnce("fcaux|player", "Could not build the legacy local-player facade", t);
                return null;
            }
        }
        legacy.pull(player);
        return legacy;
    }

    /**
     * A stand-in for the 1.5.2 client player over the 1.20.1 LocalPlayer. Like FCClientPayloadHandler.ChatSink
     * it is built with a null world and never added to one. {@link #pull} copies the LocalPlayer's position and
     * box in before each use: FC reads posY for the howl volume (Client FCBetterThanWolves.java:3468) and the
     * box for the bellows lift. {@link #setPosition} moves the LocalPlayer by the same offset. The client owns
     * its player's movement, as in 1.5.2, where the client bellows lifted the local player (Client
     * FCBlockBellows.java:577-605, from ClientNotificationOfMetadataChange :702-706). canBePushed is the
     * inherited 1.5.2 EntityLiving one, !isDead (vanilla/client EntityLiving.java:560-563).
     */
    private static final class LocalPlayerFacade extends btw.modern.EntityClientPlayerMP {

        /**
         * False while the super constructors run: the 1.5.2 Entity constructor calls setPosition(0, 0, 0)
         * (vanilla/client Entity.java:253), which must not move the real player.
         */
        private boolean live;

        LocalPlayerFacade() {
            super((btw.modern.World) null);
            live = true;
        }

        void pull(net.minecraft.client.player.LocalPlayer player) {
            posX = player.getX();
            // A 1.5.2 player's posY is its eye level: yOffset 1.62 (vanilla/client EntityPlayer.java:127,
            // Entity.setPosition :400 puts the box minY at posY - yOffset).
            posY = player.getY() + 1.62D;
            posZ = player.getZ();
            net.minecraft.world.phys.AABB box = player.getBoundingBox();
            boundingBox.setBounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }

        @Override
        public void setPosition(double x, double y, double z) {
            if (!live) {
                super.setPosition(x, y, z);
                return;
            }
            try {
                Minecraft mc = Minecraft.getInstance();
                net.minecraft.client.player.LocalPlayer player = mc.player;
                if (player == null || !mc.isSameThread()) return;
                // By offset, so the 1.62 eye-level convention of posY carries over exactly.
                player.setPos(player.getX() + (x - posX), player.getY() + (y - posY), player.getZ() + (z - posZ));
                pull(player);
            } catch (Throwable t) {
                warnOnce("player|move|" + t.getClass().getName(), "Moving the local player for FC failed", t);
            }
        }
    }

    /**
     * 1.5.2 EffectRenderer for FC's aux handler, over the 1.20.1 ParticleEngine. The Modern-Common
     * EffectRenderer is a no-op stand-in, so its two methods FC calls are bridged here.
     */
    private static final class ParticleEngineBridge extends btw.modern.EffectRenderer {

        /** FCBetterThanWolves m_iBlockDestroyRespectParticleSettingsAuxFXID: digging FX of a broken block. */
        private static final int AUX_BLOCK_DESTROY_RESPECT_PARTICLES = 2252;
        /** FCBetterThanWolves m_iSquidTentacleFlingAuxFXID: digging FX of water. */
        private static final int AUX_SQUID_TENTACLE_FLING = 2262;

        /** The FC aux effect FC's handler is playing ({@link LegacyClientSound#playFcAux}), or 0 outside one. Main thread only. */
        private static int auxId;
        private static int auxData;

        /** 1.5.2 EffectRenderer.addBlockDestroyEffects: the 4x4x4 break particles, i.e. ParticleEngine.destroy. */
        @Override
        public void addBlockDestroyEffects(int x, int y, int z, int blockID, int metadata) {
            try {
                BlockState state = legacyState(blockID, metadata);
                if (state != null) {
                    Minecraft.getInstance().particleEngine.destroy(new BlockPos(x, y, z), state);
                }
            } catch (Throwable t) {
                warnOnce("fx|destroy|" + blockID + "|" + t.getClass().getName(), "Block-destroy FX failed", t);
            }
        }

        /**
         * 1.5.2 EffectRenderer.addEffect. FC's aux handler only adds EntityDiggingFX, for 2252 and 2262
         * (Client FCBetterThanWolves.java:3384-3390, :3603-3609). Each becomes a 1.20.1 block particle of the
         * same block and metadata, at the FX's position.
         * <ul>
         *   <li>Metadata: the FX does not keep it (Modern-Common applyRenderColor is a stub). 2252 carries it in
         *       its data as {@code data >> 12 & 0xff} (:3360-3361), as aux 2001 does; 2262 is always water,
         *       metadata 0 (:3586-3587).</li>
         *   <li>Motion: FC builds each FX at {@code i + (o + 0.5) / n} with velocity {@code x - i - 0.5}
         *       (:3380-3385, :3599-3604). The frozen EntityFX constructor has already randomised and normalised
         *       that velocity (vanilla/client EntityFX.java:71-82), and the 1.20.1 Particle constructor does it
         *       again to whatever it is given. So FC's raw velocity is recovered from the position and randomised
         *       once, as ParticleEngine.destroy and 1.5.2 do.</li>
         * </ul>
         */
        @Override
        public void addEffect(btw.modern.EntityFX effect) {
            if (!(effect instanceof btw.modern.EntityDiggingFX)) return;
            try {
                btw.modern.Block block = diggingBlock((btw.modern.EntityDiggingFX) effect);
                if (block == null) return;
                int metadata = auxId == AUX_BLOCK_DESTROY_RESPECT_PARTICLES ? auxData >> 12 & 0xff : 0;
                BlockState state = legacyState(block.blockID, metadata);
                if (state == null) return;
                double vx = effect.motionX, vy = effect.motionY, vz = effect.motionZ;
                if (auxId == AUX_BLOCK_DESTROY_RESPECT_PARTICLES || auxId == AUX_SQUID_TENTACLE_FLING) {
                    vx = effect.posX - Math.floor(effect.posX) - 0.5D;
                    vy = effect.posY - Math.floor(effect.posY) - 0.5D;
                    vz = effect.posZ - Math.floor(effect.posZ) - 0.5D;
                }
                Minecraft.getInstance().particleEngine.createParticle(
                        new BlockParticleOption(ParticleTypes.BLOCK, state),
                        effect.posX, effect.posY, effect.posZ, vx, vy, vz);
            } catch (Throwable t) {
                warnOnce("fx|add|" + t.getClass().getName(), "Digging FX failed", t);
            }
        }

        /** The 1.20.1 state for a legacy id + metadata, through the same mapping aux 2001 uses. */
        private static BlockState legacyState(int blockID, int metadata) {
            if (blockID <= 0) return null;
            int[] event = BTWNetwork.legacyAuxToLevelEvent(2001, (blockID & 4095) | ((metadata & 255) << 12), false);
            return event != null ? net.minecraft.world.level.block.Block.stateById(event[1]) : null;
        }

        private static java.lang.reflect.Field diggingBlockField;

        /** EntityDiggingFX keeps its block in a private field (Modern-Common EntityDiggingFX.blockInstance). */
        private static btw.modern.Block diggingBlock(btw.modern.EntityDiggingFX fx) throws ReflectiveOperationException {
            java.lang.reflect.Field field = diggingBlockField;
            if (field == null) {
                field = btw.modern.EntityDiggingFX.class.getDeclaredField("blockInstance");
                field.setAccessible(true);
                diggingBlockField = field;
            }
            return (btw.modern.Block) field.get(fx);
        }
    }

    private static void warnOnce(String key, String message, Throwable t) {
        if (WARNED.add(key)) {
            LOGGER.warn("{} (further occurrences suppressed): {}", message, t.toString());
        }
    }
}
