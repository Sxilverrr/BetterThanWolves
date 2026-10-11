package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;

/**
 * FC 1.5.2 per-tick dig progress for BOTH sides. 1.5.2 digs with Block.getPlayerRelativeBlockHardness
 * on the client (PlayerControllerMP.java:242,316) and on the server (ItemInWorldManager.java:106,139,181,219);
 * FC's body (vanilla/server Block.java:2340-2361) = getCurrentPlayerStrVsBlock / hardness / (effective ? 30 : 200).
 * 1.20.1 consumes getDestroyProgress with the same semantics (per tick, break at >= 1, the server accepts a
 * STOP at >= 0.7: ServerPlayerGameMode.java:184-185 == ItemInWorldManager.java:219-221), so returning FC's
 * value reproduces FC digging provided both sides compute the same number. Used by
 * {@link ProxyBlock#getDestroyProgress} (BTW blocks) and BlockBehaviorMixin.btw$getDestroyProgress
 * (vanilla-id blocks with an FC replacement).
 *
 * <p>Inputs per side:
 * <ul>
 *   <li>Server: {@link PlayerBridge#getOrCreate(Player)} (identity-bound, syncs from the ServerPlayer) and its
 *       WorldBridge.</li>
 *   <li>Client: an uncached {@link PlayerBridge#createDetached(Player)} bridge over
 *       {@link ProxyEntity#createDummyClientWorld(Level)}, bound by identity to one LocalPlayer/ClientLevel. That
 *       world reports ProxyBlock META and the same derived vanilla legacy metadata as the server
 *       (WorldBridge.deriveVanillaMetadata: log stump bit, axis, ...), so world-dependent FC hardness (e.g.
 *       FCBlockLog stump x3, FCBlockStone strata) predicts the server's value instead of breaking early and
 *       reappearing.</li>
 * </ul>
 *
 * <p>Mining penalty: 1.5.2 computes UpdateMiningSpeedModifier() client-side at dig start
 * (PlayerControllerMP.java:229-234) and ships it in FCPacket166 (NetServerHandler.java:1363). Here each side
 * derives it from the same penalty levels - the server bridge's own levels, the client the
 * {@link BTWNetwork.PenaltySync} mirror (BTWNetwork.client*Penalty / clientGloomLevel, sent every tick from
 * ServerPlayerMixin and also read by the HUD) - through the single formula
 * {@link btw.modern.EntityPlayer#computeHealthAndExhaustionModifier(int, int)}, and applies it only for the FC
 * call. (Movement takes the same "each side derives it from synced state" approach: FCMovementBonus reads the
 * synced FC_PENALTY_UUID attribute modifier.) m_fCurrentMiningSpeedModifier is restored afterwards, so
 * FCGameplayBridge.onBreakSpeed (non-FC blocks, server only) still reads 1.0: no double application and no
 * client/server desync.
 */
public final class FCMiningSpeed {
    private static final Logger LOGGER = LogManager.getLogger("BTW-Mining");
    private static volatile long lastWarnMs;

    private FCMiningSpeed() {}

    /** Client bridge + read-only world, bound BY IDENTITY to one LocalPlayer/ClientLevel. */
    private static final class ClientCtx {
        final Player owner;
        final Level level;
        final PlayerBridge bridge;

        ClientCtx(Player owner, Level level) {
            this.owner = owner;
            this.level = level;
            this.bridge = PlayerBridge.createDetached(owner); // ctor already syncFromReal()s
            // The ctor already gives a client player a dummy world over player.level(); set it explicitly
            // so the world always matches the level this context is keyed on.
            this.bridge.worldObj = ProxyEntity.createDummyClientWorld(level);
        }
    }

    /**
     * Last client context. Weak: nothing else references it, so a LocalPlayer/ClientLevel the player has left
     * (and, in singleplayer, the closed integrated server its connection reaches) is not pinned by this static
     * after logout. A soft reference kept that graph for minutes on a large heap. The price is a rebuild (one
     * detached bridge) on the first dig after a GC cleared it.
     */
    private static WeakReference<ClientCtx> clientCtx = new WeakReference<>(null);

    /**
     * FC's relative hardness (per-tick dig progress) of {@code fcBlock} at {@code pos} for {@code player},
     * or {@link Float#NaN} if FC threw (rate-limited warning) so callers can fall back to their old formula.
     */
    public static float relativeHardness(btw.modern.Block fcBlock, Player player, BlockGetter level, BlockPos pos) {
        if (fcBlock == null || player == null || pos == null) return Float.NaN;
        boolean client = false;
        try {
            client = player.level().isClientSide();
            if (client) {
                synchronized (FCMiningSpeed.class) {
                    PlayerBridge fcPlayer = clientBridge(player, level);
                    int maxPenalty = Math.max(BTWNetwork.clientHealthPenalty,
                            Math.max(BTWNetwork.clientHungerPenalty, BTWNetwork.clientFatPenalty));
                    float mod = btw.modern.EntityPlayer.computeHealthAndExhaustionModifier(
                            maxPenalty, BTWNetwork.clientGloomLevel);
                    return compute(fcBlock, fcPlayer, mod, pos);
                }
            }
            // Server thread only. getOrCreate is identity-bound (PlayerBridge.Holder) and syncs from the
            // ServerPlayer; worldObj = WorldBridge.
            PlayerBridge fcPlayer = PlayerBridge.getOrCreate(player);
            return compute(fcBlock, fcPlayer, fcPlayer.GetHealthAndExhaustionModifierWithSightlessModifier(), pos);
        } catch (Throwable t) {
            warn("[MINING] FC getPlayerRelativeBlockHardness threw for {} at {} ({}): {}",
                    fcBlock.getClass().getSimpleName(), pos, client ? "client" : "server", t.toString(), t);
            return Float.NaN;
        }
    }

    private static float compute(btw.modern.Block fcBlock, PlayerBridge fcPlayer, float miningMod, BlockPos pos) {
        float prev = fcPlayer.m_fCurrentMiningSpeedModifier;
        try {
            // Capped at 1.0 like NetServerHandler.HandleStartBlockHarvest (NetServerHandler.java:1363).
            fcPlayer.SetMiningSpeedModifier(miningMod);
            float result = fcBlock.getPlayerRelativeBlockHardness(fcPlayer, fcPlayer.worldObj,
                    pos.getX(), pos.getY(), pos.getZ());
            // 0/0 (zero strength on a zero-hardness block) is NaN in FC too, which never reaches >= 1 on
            // either side in 1.5.2 (PlayerControllerMP.java:242,316): no progress. Keep NaN for "FC threw".
            return Float.isNaN(result) ? 0.0F : result;
        } finally {
            fcPlayer.m_fCurrentMiningSpeedModifier = prev; // keep onBreakSpeed (non-FC blocks) a no-op
        }
    }

    private static PlayerBridge clientBridge(Player player, BlockGetter level) {
        Level lvl = level instanceof Level l ? l : player.level();
        ClientCtx ctx = clientCtx.get();
        if (ctx == null || ctx.owner != player || ctx.level != lvl) {
            ctx = new ClientCtx(player, lvl);
            clientCtx = new WeakReference<>(ctx);
        } else {
            ctx.bridge.syncFromReal(); // never touches worldObj for a client level
        }
        return ctx.bridge;
    }

    private static void warn(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 10_000L) return;
        lastWarnMs = now;
        LOGGER.warn(fmt, args);
    }
}
