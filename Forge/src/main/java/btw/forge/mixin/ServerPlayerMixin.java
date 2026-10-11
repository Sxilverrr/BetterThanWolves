package btw.forge.mixin;

import btw.forge.BTWNetwork;
import btw.forge.PlayerBridge;
import btw.modern.ChunkCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks into {@link ServerPlayer} to drive FC player systems each tick.
 *
 * The mixin is a HOOK, not logic. It calls into {@link PlayerBridge}
 * which holds the real FC state and methods (penalty levels, gloom,
 * mining speed modifiers, etc.).
 *
 * <p>No hook here may throw: tick, NBT read/write and restoreFrom all sit on vanilla paths
 * with no BTW-aware error handling (a throw from the FC player NBT read makes the singleplayer
 * owner's world crash on every open; one from the write can abort the stop save before
 * session.lock is released -- triage reentry M2/RE1).</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Unique
    private static final org.apache.logging.log4j.Logger BTW$LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("BTW-PlayerHooks");

    /** "hook:player" keys already logged -- each failure is reported once per player per hook. */
    @Unique
    private static final java.util.Set<String> BTW$FAILURES_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * FC tick failures seen so far, keyed by exception + throw site (triage HAI-10): the first
     * failure at a site logs one ERROR with its stack trace; repeats are only counted, with a
     * one-line ERROR at 10, 100, 1000, ... occurrences. Replaces a 10 s time limiter that let one
     * per-tick failure hide every other site and reported a stack every 10 s.
     */
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> BTW$TICK_FAILURES =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Unique
    private static String btw$nameOf(ServerPlayer player) {
        try {
            return player.getGameProfile().getName();
        } catch (Throwable ignored) {
            return "<unknown>";
        }
    }

    @Unique
    private static void btw$logTickFailure(ServerPlayer player, Throwable t) {
        // First stack frame outside the JDK, so FC failures surfacing through e.g. ArrayList get distinct sites.
        String site = "<no stack>";
        for (StackTraceElement e : t.getStackTrace()) {
            String c = e.getClassName();
            if (!c.startsWith("java.") && !c.startsWith("jdk.") && !c.startsWith("sun.")) {
                site = c + "." + e.getMethodName() + ":" + e.getLineNumber();
                break;
            }
        }
        String key = t.getClass().getName() + '|' + site;
        java.util.concurrent.atomic.AtomicLong count = BTW$TICK_FAILURES.get(key);
        if (count == null) {
            java.util.concurrent.atomic.AtomicLong fresh = new java.util.concurrent.atomic.AtomicLong();
            count = BTW$TICK_FAILURES.putIfAbsent(key, fresh);
            if (count == null) {
                count = fresh;
            }
        }
        long n = count.incrementAndGet();
        long p = 10;
        while (p < n) {
            p *= 10;
        }
        if (n == 1) {
            BTW$LOGGER.error("FC player tick failed for {} at {} (stack trace logged once per throw site): {}",
                    btw$nameOf(player), site, t.toString(), t);
        } else if (p == n) {
            BTW$LOGGER.error("FC player tick failed at {} {} times so far (last for {}: {})",
                    site, n, btw$nameOf(player), t.toString());
        }
    }

    @Unique
    private static void btw$logFailureOnce(String hook, ServerPlayer player, Throwable t) {
        String name = btw$nameOf(player);
        if (BTW$FAILURES_LOGGED.add(hook + ":" + name)) {
            BTW$LOGGER.error("FC player {} failed for {} (logged once per player; the vanilla {} continues without FC data): {}",
                    hook, name, hook, t.toString(), t);
        }
    }

    /**
     * End-of-tick hook: sync state, run FC per-tick updates, sync back.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void btw$tick(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        try {
            btw$tickFc(self);
        } catch (Throwable t) {
            btw$logTickFailure(self, t);
        }
    }

    @Unique
    private static void btw$tickFc(ServerPlayer self) {
        PlayerBridge pb = PlayerBridge.getOrCreate(self);

        // Sync real MC state -> FC layer
        pb.syncFromReal();

        // FC per-tick updates
        pb.m_iTimesCraftedThisTick = 0;
        pb.m_iTicksSinceEmoteSound++;

        // 1.5.2 EntityPlayer.onUpdate (vanilla/server EntityPlayer.java:243-246) —
        // XP-orb absorption cooldown; the frozen EntityXPOrb.onCollideWithPlayer
        // sets it to 2 and skips absorption while it is non-zero.
        if (pb.xpCooldown > 0) {
            --pb.xpCooldown;
        }

        pb.UpdateModStatusVariables();

        // 1.5.2 EntityPlayerMP.ModSpecificOnUpdate (vanilla/server EntityPlayerMP.java:1081-1085) —
        // passive hunger drain + starvation/low-health status effects; runs after
        // UpdateModStatusVariables, matching vanilla's end-of-onUpdate ordering.
        pb.UpdateExhaustionWithTime();
        pb.UpdateHealthAndHungerEffects();

        // Send penalty levels to the client for HUD rendering.
        // This replaces FC's DataWatcher-based sync (IDs 22-31) with a
        // dedicated network packet. Runs every tick after penalty levels
        // are recalculated by UpdateModStatusVariables().
        BTWNetwork.sendPenaltySync(self, pb);

        // Sync FC state -> real MC
        pb.syncToReal();
    }

    /**
     * Load FC custom data from NBT on player load. Never throws: for the singleplayer owner this
     * runs inside PlayerList.load -> Entity.load with no try (PlayerList.java:315-328), so a throw
     * would crash the world on every open and the bad tag would never be rewritten.
     */
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void btw$readNBT(CompoundTag tag, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        try {
            PlayerBridge pb = PlayerBridge.getOrCreate(self);

            btw.forge.ForgeNBTCompound fcTag = new btw.forge.ForgeNBTCompound(tag);
            pb.ReadModDataFromNBT(fcTag);

            // Also load FC food data
            if (tag.contains("foodLevel")) {
                pb.foodStats.readNBT(fcTag);
            }
        } catch (Throwable t) {
            btw$logFailureOnce("NBT read", self, t);
        }
    }

    /**
     * Save FC custom data to NBT on player save. Never throws: IntegratedPlayerList.saveWithoutId
     * runs outside any try (IntegratedPlayerList.java:26), and a throw during the stop save aborts
     * MinecraftServer.stopServer before session.lock is released (MinecraftServer.java:557, :600-604).
     */
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void btw$writeNBT(CompoundTag tag, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        try {
            PlayerBridge pb = PlayerBridge.getOrCreate(self);

            btw.forge.ForgeNBTCompound fcTag = new btw.forge.ForgeNBTCompound(tag);
            pb.WriteModDataToNBT(fcTag);

            // Also save FC food data
            pb.foodStats.writeNBT(fcTag);
        } catch (Throwable t) {
            btw$logFailureOnce("NBT write", self, t);
        }
    }

    /**
     * Clone FC data when player respawns or transfers dimensions.
     *
     * <p>1.20.1 PlayerList.respawn (PlayerList.java:440-443) builds a NEW ServerPlayer, calls this,
     * then setId(old id). Bridges are bound to the player OBJECT (PlayerBridge.Holder), so
     * {@code self} gets its own bridge here and keeps it after setId -- the old id-keyed cache
     * returned the dead player's bridge for the rest of the session (triage HAI-1 / reentry M1,
     * incl. the End-exit credits respawn(player, true) variant).</p>
     */
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void btw$restoreFrom(ServerPlayer oldPlayer, boolean keepEverything, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        try {
            PlayerBridge oldBridge = PlayerBridge.getOrCreate(oldPlayer);
            PlayerBridge newBridge = PlayerBridge.getOrCreate(self);

            // Always copy persistent spawn data (survives death)
            newBridge.m_HardcoreSpawnChunk = oldBridge.m_HardcoreSpawnChunk;
            newBridge.m_iSpawnDimension = oldBridge.m_iSpawnDimension;
            newBridge.m_lTimeOfLastSpawnAssignment = oldBridge.m_lTimeOfLastSpawnAssignment;
            newBridge.m_lTimeOfLastDimensionSwitch = oldBridge.m_lTimeOfLastDimensionSwitch;
            newBridge.m_lRespawnAssignmentCooldownTimer = oldBridge.m_lRespawnAssignmentCooldownTimer;

            if (keepEverything) {
                // Dimension transfer: preserve ALL state
                newBridge.m_iHungerPenaltyLevel = oldBridge.m_iHungerPenaltyLevel;
                newBridge.m_iFatPenaltyLevel = oldBridge.m_iFatPenaltyLevel;
                newBridge.m_iHealthPenaltyLevel = oldBridge.m_iHealthPenaltyLevel;
                newBridge.m_iGloomLevel = oldBridge.m_iGloomLevel;
                newBridge.m_iInGloomCounter = oldBridge.m_iInGloomCounter;
                newBridge.m_fCurrentMiningSpeedModifier = oldBridge.m_fCurrentMiningSpeedModifier;
                newBridge.foodStats.setFoodLevel(oldBridge.foodStats.getFoodLevel());
                newBridge.foodStats.setFoodSaturationLevel(oldBridge.foodStats.getSaturationLevel());
            }
            // Death respawn: penalty levels stay at defaults (0),
            // food stats stay at defaults (60/0), mining speed stays at 1.0.
            // UpdateModStatusVariables() on the next tick will recompute
            // everything from the fresh health/food state.

            // The old player was removed by PlayerList.respawn (removePlayerImmediately). 1.5.2 marks
            // the replaced EntityPlayerMP dead (vanilla/server ServerConfigurationManager.java:1095
            // removePlayerEntityDangerously -> setDead, World.java:1422-1424), so FC AI holding it
            // as a target drops it at once (EntityLiving.isEntityAlive) instead of chasing a removed
            // player frozen at its last position (End exit keeps health > 0). Field only:
            // EntityPlayer.setDead() would also close the old FC containers. Normally already set by
            // PlayerMixin.btw$markBridgeDead on respawn's DISCARDED removal; kept as a fallback.
            if (oldPlayer.isRemoved()) {
                oldBridge.isDead = true;
            }
        } catch (Throwable t) {
            btw$logFailureOnce("restoreFrom", self, t);
        }
    }

    // =========================================================================
    // FC Spawn System Hook
    // =========================================================================

    /**
     * Intercepts {@link ServerPlayer#getRespawnPosition()} to return the FC
     * hardcore spawn chunk coordinates instead of the vanilla spawn point,
     * when the FC spawn system has assigned one.
     *
     * <p>FC replaces vanilla bed-based spawning with a beacon-based system.
     * The spawn coordinates are stored in {@link PlayerBridge#m_HardcoreSpawnChunk}
     * and persisted in NBT (fcHCSpawnX/Y/Z). When set, this hook returns
     * those coordinates as the player's respawn position, overriding the
     * vanilla bed/respawn anchor position.</p>
     *
     * <p>If no FC spawn is set, vanilla behavior is preserved (returns the
     * original respawn position, which may be null for world spawn).</p>
     */
    @Inject(method = "getRespawnPosition", at = @At("HEAD"), cancellable = true)
    private void btw$getRespawnPosition(CallbackInfoReturnable<BlockPos> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        PlayerBridge pb = PlayerBridge.getOrCreate(self);

        ChunkCoordinates fcSpawn = pb.m_HardcoreSpawnChunk;
        if (fcSpawn != null) {
            cir.setReturnValue(new BlockPos(fcSpawn.posX, fcSpawn.posY, fcSpawn.posZ));
        }
        // Otherwise, fall through to vanilla behavior
    }

    /**
     * Intercepts {@link ServerPlayer#getRespawnDimension()} to return the
     * FC spawn dimension when an FC hardcore spawn is set.
     *
     * <p>FC tracks the spawn dimension separately in
     * {@link PlayerBridge#m_iSpawnDimension}. The legacy dimension IDs
     * (0 = overworld, -1 = nether, 1 = end) are mapped to MC 1.20.1
     * {@link ResourceKey ResourceKey&lt;Level&gt;} values.</p>
     */
    @Inject(method = "getRespawnDimension", at = @At("HEAD"), cancellable = true)
    private void btw$getRespawnDimension(CallbackInfoReturnable<ResourceKey<Level>> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        PlayerBridge pb = PlayerBridge.getOrCreate(self);

        if (pb.m_HardcoreSpawnChunk != null) {
            ResourceKey<Level> dim = legacyDimToResourceKey(pb.m_iSpawnDimension);
            cir.setReturnValue(dim);
        }
        // Otherwise, fall through to vanilla behavior
    }

    /**
     * Intercepts {@link ServerPlayer#isRespawnForced()} to return true
     * when an FC hardcore spawn is set. FC spawn points are always "forced"
     * (they do not require an intact bed/anchor at the spawn position;
     * the beacon validation is handled separately by FC code).
     */
    @Inject(method = "isRespawnForced", at = @At("HEAD"), cancellable = true)
    private void btw$isRespawnForced(CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        PlayerBridge pb = PlayerBridge.getOrCreate(self);

        if (pb.m_HardcoreSpawnChunk != null) {
            // FC spawn points are always forced (beacon validation is separate)
            cir.setReturnValue(true);
        }
    }

    // =========================================================================
    // Helper: legacy dimension ID -> MC 1.20.1 ResourceKey<Level>
    // =========================================================================

    /**
     * Maps a legacy numeric dimension ID to an MC 1.20.1 dimension ResourceKey.
     *
     * @param legacyDim 0 = overworld, -1 = nether, 1 = end
     * @return the corresponding {@link ResourceKey}, defaulting to overworld
     */
    private static ResourceKey<Level> legacyDimToResourceKey(int legacyDim) {
        return switch (legacyDim) {
            case -1 -> Level.NETHER;
            case 1 -> Level.END;
            default -> Level.OVERWORLD;
        };
    }
}
