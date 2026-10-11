package btw.forge;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restores FC's login handshake. In 1.5.2 vanilla ServerConfigurationManager called
 * {@code FCBetterThanWolves.ServerPlayerConnectionInitialized(netHandler, player)} once a player
 * connection was set up (vanilla/server/net/minecraft/src/ServerConfigurationManager.java:133);
 * the port uses MC's PlayerList, so that FCMOD hook was lost (triage CTX-5).
 *
 * <p>FC's method (Server FCBetterThanWolves.java:2213-2315) sends, through the player's
 * NetServerHandler: in single player the white "BTW V4.B0000003" chat line; on a server the
 * yellow "&lt;name&gt; connected to Better Than Wolves server V..." line, the version-check
 * payload and the "Hardcore Modes: Buoy (...) Player Names (...)" line; in both cases the FC|OP
 * hardcore-options payload. ForgeNetServerHandler delivers the chat packets as system messages
 * and forwards the two custom payloads (FC|VC, FC|OP) raw through BTWNetwork.FCPayload. On the
 * client, btw.forge.client.FCClientPayloadHandler hands them to FC's own dispatcher
 * (FCAddOnHandler.ClientCustomPacketReceived, as 1.5.2 NetClientHandler.java:1578-1582 did),
 * which prints "BTW version check successful." (or FC's red mismatch warning) on servers and
 * stores the server's hardcore buoy / player-names options. FCClientPayloadHandler.NameTagRules
 * applies the "Hardcore" player-names level and EntityRendererNameTagMixin the "Obstructed" one;
 * the buoy flag has no client consumer yet (EntityItem.UpdateHardcoreBuoy is not ported). Forge's
 * own channel version check still refuses mismatched clients.</p>
 *
 * <p>After FC's lines the port adds one note, once per player per port version, pointing at
 * the issue tracker.</p>
 */
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public final class FCLoginHandler {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Login");

    private static final String FC_CORE_CLASS = "net.minecraft.src.btw.core.FCBetterThanWolves";
    private static final String ISSUES_URL = "https://github.com/ThatSoulyGuy/BetterThanWolves/issues";
    /** Player-persisted tag (survives death, Player.PERSISTED_NBT_TAG) remembering the last note shown. */
    private static final String PORT_NOTE_KEY = "betterthanwolves_port_note_version";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private FCLoginHandler() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            bindUsername(player);
        } catch (Throwable t) {
            warnOnce("username", "Could not set the FC player name", t);
        }
        try {
            if (!runFcHandshake(player)) {
                sendFallbackVersionLine(player);
            }
        } catch (Throwable t) {
            warnOnce("handshake", "FC login handshake failed", t);
        }
        try {
            sendPortNote(player);
        } catch (Throwable t) {
            warnOnce("note", "Port login note failed", t);
        }
    }

    /**
     * PlayerList.respawn (death, End exit) builds a NEW ServerPlayer, which gets its own bridge
     * (PlayerBridge.Holder); without this its name would be null from the first death on.
     * Clone fires inside ServerPlayer.restoreFrom with the new player as the entity.
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            bindUsername(player);
        } catch (Throwable t) {
            warnOnce("username", "Could not set the FC player name", t);
        }
    }

    /**
     * 1.5.2 EntityPlayer.username is the login name, set for every player entity; the bridge
     * does not set it. FC reads it directly: the server connect line
     * (FCBetterThanWolves.java:2220-2222) and the wolf owner checks (FCEntityWolf.java:310, 704).
     */
    private static void bindUsername(ServerPlayer player) {
        PlayerBridge bridge = PlayerBridge.getOrCreate(player);
        if (bridge.username == null) {
            bridge.username = player.getGameProfile().getName();
        }
    }

    /**
     * Calls FC's ServerPlayerConnectionInitialized through the player's bridge.
     *
     * @return false only when FC was not invoked at all (nothing was sent), so the caller can
     *         send the version line itself. A failure inside FC returns true: FC's first
     *         statement is the chat line, which has then most likely gone out already.
     */
    private static boolean runFcHandshake(ServerPlayer player) {
        // FC's first statement is MinecraftServer.getServer().isSinglePlayer().
        if (btw.modern.MinecraftServer.getServer() == null) return false;

        Method handshake;
        try {
            handshake = Class.forName(FC_CORE_CLASS).getMethod("ServerPlayerConnectionInitialized",
                    btw.modern.NetServerHandler.class, btw.modern.EntityPlayerMP.class);
        } catch (Throwable t) {
            warnOnce("lookup", "FC ServerPlayerConnectionInitialized not found", t);
            return false;
        }

        PlayerBridge bridge = PlayerBridge.getOrCreate(player);
        btw.modern.NetServerHandler handler = bridge.playerNetServerHandler;
        if (handler == null) return false;
        // bridge.username (FC's connect line) was set by bindUsername in onPlayerLoggedIn.

        try {
            handshake.invoke(null, handler, bridge);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            warnOnce("invoke", "FC ServerPlayerConnectionInitialized threw", cause);
        } catch (Throwable t) {
            warnOnce("invoke", "FC ServerPlayerConnectionInitialized could not be called", t);
            return false;
        }
        return true;
    }

    /** The FC version line, sent directly when FC's handshake could not run (FCBetterThanWolves.java:2216-2249). */
    private static void sendFallbackVersionLine(ServerPlayer player) {
        String version = fcVersion();
        if (version == null) return;
        MinecraftServer server = player.getServer();
        if (server != null && !server.isSingleplayer()) {
            player.sendSystemMessage(Component.literal("§e" + player.getName().getString()
                    + " connected to Better Than Wolves server V" + version));
        } else {
            player.sendSystemMessage(Component.literal("§fBTW V" + version));
        }
    }

    /** One port note per player per port version (kept with the player's persisted data). */
    private static void sendPortNote(ServerPlayer player) {
        String portVersion = ModList.get().getModContainerById(BTWForgeMod.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("");

        CompoundTag root = player.getPersistentData();
        CompoundTag persisted = root.getCompound(Player.PERSISTED_NBT_TAG);
        if (portVersion.equals(persisted.getString(PORT_NOTE_KEY))) return;

        player.sendSystemMessage(Component.literal("Better Than Wolves port " + portVersion
                        + " (Forge 1.20.1): some mechanics still differ from BTW 4.B. Report problems at ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ISSUES_URL).withStyle(style -> style
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, ISSUES_URL)))));

        persisted.putString(PORT_NOTE_KEY, portVersion);
        root.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private static String fcVersion() {
        try {
            Object value = Class.forName(FC_CORE_CLASS).getField("fcVersionString").get(null);
            return value instanceof String s ? s : null;
        } catch (Throwable t) {
            warnOnce("version", "FC version string unavailable", t);
            return null;
        }
    }

    private static void warnOnce(String key, String message, Throwable t) {
        if (WARNED.add(key)) {
            LOGGER.warn("{} (further occurrences suppressed): {}", message, t.toString());
        }
    }
}
