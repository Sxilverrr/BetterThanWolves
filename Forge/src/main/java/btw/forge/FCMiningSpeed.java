package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.ForgeEventFactory;
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
 * {@link btw.modern.EntityPlayer#computeHealthAndExhaustionModifier(int, int)} ({@link #miningModifier(Player)}).
 * (Movement takes the same "each side derives it from synced state" approach: FCMovementBonus reads the synced
 * FC_PENALTY_UUID attribute modifier.) For an FC block it is applied only for the FC call, and
 * m_fCurrentMiningSpeedModifier is restored afterwards. Every other block gets it from
 * FCGameplayBridge.onBreakSpeed, on both sides, which skips FC blocks: the penalty applies exactly once and the client
 * predicts the server's progress.
 *
 * <p>FCMOD-INTEGRATION(1.20.1): fc_harvest_events (INTEGRATIONS #022). FC digging takes part in Forge's
 * {@code PlayerEvent.BreakSpeed} contract (ForgeEventFactory.getBreakSpeed, ForgeEventFactory.java:186-190), which
 * vanilla posts from Player.getDigSpeed for every other block: Cold Sweat's temperature slowdown (#030), Dynamic
 * Trees and claim mods see FC blocks too. The event carries FC's strength (the numerator of
 * getPlayerRelativeBlockHardness, BTW's penalty included) and FC's progress is scaled by the listeners' factor, so
 * with no other listener the result is bit-identical to FC's.
 */
public final class FCMiningSpeed {
    private static final Logger LOGGER = LogManager.getLogger("BTW-Mining");
    private static volatile long lastWarnMs;

    /** 1.5.2 Block.wood, the log (FCBlockLog replaces it in blocksList). */
    private static final int LEGACY_LOG_ID = 17;

    /**
     * FC strength out-parameter of {@link #compute}: one array per thread (the server thread, the render thread),
     * so a dig tick allocates nothing.
     */
    private static final ThreadLocal<float[]> STRENGTH = ThreadLocal.withInitial(() -> new float[1]);

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
     * after Forge's BreakSpeed listeners, or {@link Float#NaN} if FC threw (rate-limited warning) so callers can
     * fall back to their old formula. Never NaN otherwise.
     */
    public static float relativeHardness(btw.modern.Block fcBlock, Player player, BlockGetter level, BlockPos pos) {
        if (fcBlock == null || player == null || pos == null) return Float.NaN;
        boolean client = false;
        float[] strength = STRENGTH.get();
        float progress;
        try {
            client = player.level().isClientSide();
            if (client) {
                synchronized (FCMiningSpeed.class) {
                    PlayerBridge fcPlayer = clientBridge(player, level);
                    progress = compute(fcBlock, fcPlayer, clientModifier(), pos, strength);
                }
            } else {
                // Server thread only. getOrCreate is identity-bound (PlayerBridge.Holder) and syncs from the
                // ServerPlayer; worldObj = WorldBridge.
                PlayerBridge fcPlayer = PlayerBridge.getOrCreate(player);
                progress = compute(fcBlock, fcPlayer, fcPlayer.GetHealthAndExhaustionModifierWithSightlessModifier(),
                        pos, strength);
            }
        } catch (Throwable t) {
            warn("[MINING] FC getPlayerRelativeBlockHardness threw for {} at {} ({}): {}",
                    fcBlock.getClass().getSimpleName(), pos, client ? "client" : "server", t.toString(), t);
            return Float.NaN;
        }
        // Outside the client lock and with FC's modifier restored: a listener may dig-query anything.
        return postBreakSpeed(player, level, pos, progress, strength[0]);
    }

    /**
     * FC's progress scaled by Forge's BreakSpeed listeners. The event's original speed is FC's strength, so with no
     * listener other than BTW's own (FCGameplayBridge skips FC blocks) {@code s == strength}, the factor is exactly
     * 1.0F and the result is {@code progress} bit for bit. Infinite progress (hardness 0) is posted too: a cancel
     * (-1) or a zero speed never breaks, as vanilla's -1/0 never does. No progress (FC's 0, an unbreakable block)
     * posts nothing, as vanilla's getDestroyProgress returns 0 for hardness -1 before getDigSpeed. Never NaN: NaN is
     * reserved for "FC threw" (ProxyBlock.getDestroyProgress, BlockBehaviorMixin.btw$getDestroyProgress).
     * A listener that throws is another mod's failure: FC's own progress stands (rate-limited warning), as
     * FcCropGrowthHooks does for CropGrowEvent, instead of crashing the render thread or dropping the server's
     * dig packet on every FC block.
     */
    // FCMOD-INTEGRATION(1.20.1): fc_harvest_events: FC blocks dig through FC's formula, not Player.getDigSpeed, so they must post Forge's BreakSpeed themselves
    private static float postBreakSpeed(Player player, BlockGetter level, BlockPos pos, float progress, float strength) {
        if (!(progress > 0F) || !(strength > 0F) || !Float.isFinite(strength)) return progress;
        float s;
        try {
            BlockState state = level.getBlockState(pos);
            s = ForgeEventFactory.getBreakSpeed(player, state, strength, pos); // -1 when cancelled
        } catch (RuntimeException | LinkageError e) {
            // FCMOD-INTEGRATION(1.20.1): fc_harvest_events: a third-party BreakSpeed listener failure must not break FC digging; FC's progress is kept unscaled
            warn("[MINING] a BreakSpeed listener threw for {} at {}; FC's dig progress is used unscaled: {}",
                    player.getName().getString(), pos, e.toString(), e);
            return progress;
        }
        if (!(s > 0F)) return 0F;
        float r = progress * (s / strength);
        return Float.isNaN(r) ? 0F : r;
    }

    private static float compute(btw.modern.Block fcBlock, PlayerBridge fcPlayer, float miningMod, BlockPos pos,
                                 float[] strengthOut) {
        float prev = fcPlayer.m_fCurrentMiningSpeedModifier;
        strengthOut[0] = 0F;
        try {
            // Capped at 1.0 like NetServerHandler.HandleStartBlockHarvest (NetServerHandler.java:1363).
            fcPlayer.SetMiningSpeedModifier(miningMod);
            float result = fcBlock.getPlayerRelativeBlockHardness(fcPlayer, fcPlayer.worldObj,
                    pos.getX(), pos.getY(), pos.getZ());
            // FCMOD-INTEGRATION(1.20.1): fc_harvest_events: the BreakSpeed original is FC's strength, the exact numerator of getPlayerRelativeBlockHardness (Modern-Common Block.java:393-407), BTW's penalty included
            strengthOut[0] = fcPlayer.getCurrentPlayerStrVsBlock(fcBlock, pos.getX(), pos.getY(), pos.getZ());
            // 0/0 (zero strength on a zero-hardness block) is NaN in FC too, which never reaches >= 1 on
            // either side in 1.5.2 (PlayerControllerMP.java:242,316): no progress. Keep NaN for "FC threw".
            return Float.isNaN(result) ? 0.0F : result;
        } finally {
            fcPlayer.m_fCurrentMiningSpeedModifier = prev; // the FC modifier lives only for the FC call
        }
    }

    /**
     * BTW's health, hunger, fat and gloom mining modifier for {@code player} (1.0 healthy, down to 0.125), on either
     * side: the server bridge's own levels, or on the client the PenaltySync mirror of the local player's, through the
     * one formula {@link btw.modern.EntityPlayer#computeHealthAndExhaustionModifier(int, int)}. 1.0 on any failure
     * (rate-limited warning). FCGameplayBridge.onBreakSpeed applies it to every non-FC block.
     */
    // FCMOD-INTEGRATION(1.20.1): fc_harvest_events: 1.5.2 multiplied every dig by GetMiningSpeedModifier; 1.20.1-only blocks dig through Player.getDigSpeed, so BreakSpeed carries it there
    public static float miningModifier(Player player) {
        if (player == null) return 1F;
        try {
            if (player.level().isClientSide()) {
                return clientModifier();
            }
            return PlayerBridge.getOrCreate(player).GetHealthAndExhaustionModifierWithSightlessModifier();
        } catch (Throwable t) {
            warn("[MINING] BTW mining modifier failed for {}: {}", player.getName().getString(), t.toString(), t);
            return 1F;
        }
    }

    /** The local player's modifier from the PenaltySync mirror (the client has no FC penalty state of its own). */
    private static float clientModifier() {
        int maxPenalty = Math.max(BTWNetwork.clientHealthPenalty,
                Math.max(BTWNetwork.clientHungerPenalty, BTWNetwork.clientFatPenalty));
        return btw.modern.EntityPlayer.computeHealthAndExhaustionModifier(maxPenalty, BTWNetwork.clientGloomLevel);
    }

    /**
     * FC's per-tick dig progress of an FC log (btw.modern.Block.blocksList[17], FCBlockLog) for {@code player} at a
     * position whose block is NOT FC's log, e.g. a Dynamic Trees branch: the FC log rule for blocks that look like
     * logs. 0 when blocksList has no log, on any failure (rate-limited warning) and for non-finite results.
     * <ul>
     *   <li>Hardness: FCBlockLog's field {@code blockHardness} (1.25, Client FCBlockLog.java:28), tripled for a stump
     *       as FCBlockLog.getBlockHardness does (:47-60). The world is never asked: getBlockHardness(world, ...) reads
     *       the metadata of whatever block is at {@code pos}.</li>
     *   <li>Not a stump: FC's strength and FC's effectiveness for the held item (Modern-Common
     *       EntityPlayer.getCurrentPlayerStrVsBlock and IsCurrentToolEffectiveOnBlock), as
     *       getPlayerRelativeBlockHardness (vanilla/server Block.java:2340-2361): {@code str / H / (effective ? 30 :
     *       200)}. The divisor follows effectiveness, not harvestability: an FC chisel is effective on logs (Client
     *       FCBlockLog.java:32) but cannot harvest them.</li>
     *   <li>Stump ({@code asStump}): every FC tool digs a stump at strength 1.0 and is never effective on it
     *       (GetIsProblemToRemove: Common FCItemAxe.java:39-44, :82-87; FCItemChisel.java:56-61), but FC's
     *       GetIsStump reads world id 17 (Client FCBlockLog.java:187-202) and never sees a foreign stump. So the held
     *       slot is hidden for the call (inventory.currentItem = -1: InventoryPlayer.getStrVsBlock returns 1.0,
     *       :220-227, and the Efficiency bonus needs str > 1 in getCurrentPlayerStrVsBlock); haste, fatigue, water
     *       and airborne still apply.</li>
     * </ul>
     * <p>BTW's penalty is EXCLUDED (the FC modifier is 1.0 for the call) and NO event is posted: Dynamic Trees calls
     * this from its own BreakSpeed listener, where a post would recurse and the event already carries the penalty
     * (FCGameplayBridge). Contract for a caller inside a BreakSpeed listener, when {@code event.getOriginalSpeed() >
     * 0}: {@code newSpeed = progress * state.getDestroySpeed(level, pos) * 30 * (event.getNewSpeed() /
     * event.getOriginalSpeed())}, so the BTW penalty and other mods' factors apply once.
     */
    // FCMOD-INTEGRATION(1.20.1): fc_harvest_events: Dynamic Trees branches are trunks FC never knew; FC's log digging rule is exposed for them (INTEGRATIONS #027)
    public static float fcLogDigProgress(Player player, BlockGetter level, BlockPos pos, boolean asStump) {
        if (player == null || pos == null) return 0F;
        btw.modern.Block log = LEGACY_LOG_ID < btw.modern.Block.blocksList.length
                ? btw.modern.Block.blocksList[LEGACY_LOG_ID] : null;
        if (log == null) return 0F;
        boolean client = false;
        try {
            client = player.level().isClientSide();
            if (client) {
                synchronized (FCMiningSpeed.class) {
                    return logProgress(clientBridge(player, level), log, pos, asStump);
                }
            }
            return logProgress(PlayerBridge.getOrCreate(player), log, pos, asStump);
        } catch (Throwable t) {
            warn("[MINING] FC log dig progress threw at {} ({}): {}", pos, client ? "client" : "server",
                    t.toString(), t);
            return 0F;
        }
    }

    private static float logProgress(PlayerBridge fcPlayer, btw.modern.Block log, BlockPos pos, boolean asStump) {
        float prevMod = fcPlayer.m_fCurrentMiningSpeedModifier;
        btw.modern.InventoryPlayer inventory = fcPlayer.inventory;
        int prevItem = inventory != null ? inventory.currentItem : 0;
        try {
            fcPlayer.SetMiningSpeedModifier(1.0F);
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            float hardness = log.blockHardness * (asStump ? 3F : 1F);
            float str;
            boolean effective;
            if (asStump) {
                if (inventory != null) inventory.currentItem = -1;
                str = fcPlayer.getCurrentPlayerStrVsBlock(log, x, y, z);
                effective = false;
            } else {
                str = fcPlayer.getCurrentPlayerStrVsBlock(log, x, y, z);
                effective = fcPlayer.IsCurrentToolEffectiveOnBlock(log, x, y, z);
            }
            float progress = str / hardness / (effective ? 30F : 200F);
            return Float.isFinite(progress) && progress > 0F ? progress : 0F;
        } finally {
            if (inventory != null) inventory.currentItem = prevItem;
            fcPlayer.m_fCurrentMiningSpeedModifier = prevMod;
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
