package btw.forge.client;

import btw.forge.BTWForgeMod;
import btw.forge.BTWNetwork;
import btw.forge.ProxyEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client half of FC's login handshake (triage CTX-5). FCBetterThanWolves.ServerPlayerConnectionInitialized
 * (Client FCBetterThanWolves.java:2213-2314) sends two Packet250CustomPayloads besides its chat lines:
 * FC|VC (server version, multiplayer only) and FC|OP (hardcore buoy + player-names level). 1.5.2
 * NetClientHandler.handleCustomPayload passed them to FCAddOnHandler.ClientCustomPacketReceived
 * (NetClientHandler.java:1578-1582), and FCBetterThanWolves.ClientCustomPacketReceived printed
 * "BTW version check successful." or the red mismatch warning (:2747-2764) and stored the server's
 * hardcore options (:2765-2788). The payloads arrive here through BTWNetwork.FCPayload and go to the
 * same FC dispatcher unchanged.
 *
 * <p>In 1.5.2 the client readers of the stored options (IsHardcorePlayerNamesEnabled etc., which read
 * the server values when world.isRemote, :864-898) were FCMOD edits in vanilla RenderPlayer,
 * RenderLiving and EntityItem, all replaced by 1.20.1 classes in the port. {@link NameTagRules}
 * re-applies the "Hardcore" player-names level (1); btw.forge.mixin.EntityRendererNameTagMixin reads
 * {@link NameTagRules#playerNamesLevel()} for the "Obstructed" level (2; player name tags depth-tested,
 * so walls hide them, RenderLiving.java:512-520). Not reproduced: the buoy flag
 * (EntityItem.UpdateHardcoreBuoy, EntityItem.java:442, is not ported on either side).</p>
 *
 * <p>Client-only: reached solely from BTWNetwork.FCPayload.handle via DistExecutor (Dist.CLIENT),
 * and {@link NameTagRules} is a Dist.CLIENT subscriber also read by a "client"-config mixin
 * (EntityRendererNameTagMixin), so a dedicated server never loads this class.</p>
 */
public final class FCClientPayloadHandler {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ClientPayload");

    private static final String FC_ADDON_HANDLER_CLASS = "net.minecraft.src.btw.core.FCAddOnHandler";
    private static final String FC_CORE_CLASS = "net.minecraft.src.btw.core.FCBetterThanWolves";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private FCClientPayloadHandler() {}

    /** Runs on the client main thread (BTWNetwork.FCPayload.handle enqueues it). Never throws. */
    public static void handle(String channel, byte[] data) {
        if (!BTWNetwork.isForwardedFcChannel(channel)) return;
        try {
            Method dispatcher = Class.forName(FC_ADDON_HANDLER_CLASS).getMethod("ClientCustomPacketReceived",
                    btw.modern.Minecraft.class, btw.modern.Packet250CustomPayload.class);
            // FC reads mcInstance.theWorld (a cast only; null is fine for these two channels) and, for
            // FC|VC, prints through mcInstance.thePlayer.addChatMessage. FC|OP touches neither.
            btw.modern.Minecraft legacyMc = new btw.modern.Minecraft();
            if (BTWNetwork.FC_CHANNEL_VERSION_CHECK.equals(channel)) {
                legacyMc.thePlayer = new ChatSink();
            }
            dispatcher.invoke(null, legacyMc,
                    new btw.modern.Packet250CustomPayload(channel, data != null ? data : new byte[0]));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            warnOnce("invoke|" + channel + "|" + cause.getClass().getName(),
                    "FC client handling of payload '" + channel + "' threw", cause);
        } catch (Throwable t) {
            warnOnce("handle|" + channel + "|" + t.getClass().getName(),
                    "Could not hand FC payload '" + channel + "' to FCAddOnHandler", t);
        }
        if (BTWNetwork.FC_CHANNEL_OPTIONS.equals(channel)) {
            refreshPlayerNamesLevel();
        }
    }

    /**
     * Reads back what FC stored from FC|OP through FC's own client accessors (a remote world selects
     * the server values, Client FCBetterThanWolves.java:876-898), once per FC|OP rather than per frame.
     */
    private static void refreshPlayerNamesLevel() {
        try {
            Class<?> core = Class.forName(FC_CORE_CLASS);
            btw.modern.World remoteWorld = ProxyEntity.createDummyClientWorld();
            boolean hardcore = (Boolean) core.getMethod("IsHardcorePlayerNamesEnabled", btw.modern.World.class)
                    .invoke(null, remoteWorld);
            boolean obstructed = (Boolean) core.getMethod("AreHardcorePlayerNamesObstructed", btw.modern.World.class)
                    .invoke(null, remoteWorld);
            NameTagRules.level = hardcore ? 1 : (obstructed ? 2 : 0);
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            warnOnce("names|" + cause.getClass().getName(),
                    "Could not read FC's hardcore player-names level", cause);
        }
    }

    /**
     * FC's "Hardcore" player-names level (1): 1.5.2 drew no player name tag (RenderPlayer.java:420-425)
     * and no tag for a sneaking living entity (RenderLiving.java:435-440). Other entities' tags (named
     * mobs) were drawn as usual. References nothing in the outer class, so the per-frame event (and the
     * level-2 hook, EntityRendererNameTagMixin, reading playerNamesLevel()) never loads its btw.modern
     * dependencies.
     */
    @Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID, value = Dist.CLIENT)
    public static final class NameTagRules {

        static volatile int level = -1;

        private NameTagRules() {}

        /**
         * FC's player-names level as the client last received it on this connection (0 Displayed,
         * 1 Hardcore, 2 Obstructed), or -1 before any FC|OP arrived (name tags are then left to 1.20.1).
         */
        public static int playerNamesLevel() {
            return level;
        }

        /**
         * Forgets the previous server's rule, so a session whose FC|OP never arrives (FCLoginHandler's
         * fallback paths send none) keeps vanilla name tags instead of the last world's level.
         * Minecraft.clearLevel fires this on every disconnect and before each new connection or
         * integrated-server start, after dropping queued tasks, so it cannot overwrite a fresh FC|OP.
         */
        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            level = -1;
        }

        @SubscribeEvent
        public static void onRenderNameTag(RenderNameTagEvent event) {
            if (level != 1) return;
            Entity entity = event.getEntity();
            // 1.5.2 isSneaking() is the shared flag 1, which 1.20.1 calls isShiftKeyDown().
            if (entity instanceof Player || (entity instanceof LivingEntity && entity.isShiftKeyDown())) {
                event.setResult(Event.Result.DENY);
            }
        }
    }

    /**
     * Stands in for 1.5.2's Minecraft.thePlayer for the FC|VC chat line: EntityPlayerSP.addChatMessage
     * printed to the chat GUI (EntityPlayerSP.java:507-510 -> GuiNewChat.addTranslatedMessage, which
     * passes a non-key string through unchanged). Never added to a world; like PlayerBridge it is
     * built with a null world.
     */
    private static final class ChatSink extends btw.modern.EntityClientPlayerMP {
        ChatSink() {
            super(null);
        }

        @Override
        public void addChatMessage(String message) {
            Minecraft.getInstance().gui.getChat().addMessage(Component.literal(message));
        }
    }

    private static void warnOnce(String key, String message, Throwable t) {
        if (WARNED.add(key)) {
            LOGGER.warn("{} (further occurrences suppressed): {}", message, t.toString());
        }
    }
}
