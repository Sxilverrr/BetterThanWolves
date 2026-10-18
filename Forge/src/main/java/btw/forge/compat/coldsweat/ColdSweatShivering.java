package btw.forge.compat.coldsweat;

import com.momosoftworks.coldsweat.api.util.Temperature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * Shivering (INTEGRATIONS #030, decision #15): while Cold Sweat reports a player freezing, the cold costs FC exhaustion,
 * so FC's own hunger rule burns fat before hunger.
 *
 * <ul>
 *   <li><b>Modern feature:</b> Cold Sweat's body temperature. Its freeze effects (slower healing, mining and movement,
 *       cold damage) all run between body -50 and -100 ({@code default_player.json}: every {@code freeze_*} effect at
 *       min -50, max -100).</li>
 *   <li><b>Conflict with FC:</b> none to resolve. 4.B has no body temperature, but it does have a hunger cost for time
 *       and work, and a fat reserve that burns first. Cold that costs nothing would make BTW clothing and fires matter
 *       only to Cold Sweat's own effects.</li>
 *   <li><b>Pattern:</b> once per second (server player tick END, {@code tickCount % 20 == 0}) {@link #applyShivering}
 *       charges {@code EXHAUSTION_PER_SECOND x factor(body)} through vanilla {@code Player.causeFoodExhaustion}, which
 *       {@code PlayerMixin.btw$armorExhaustion} routes into {@code PlayerBridge.addExhaustion}: FC's armour weight and
 *       invulnerable exemption apply, and FC FoodStats burns fat first while {@code fat > ((food + 5) / 6) * 2} and
 *       charges nothing on peaceful. The band is linear from {@link #FREEZE_START} (0) to {@link #FREEZE_FULL} (1).</li>
 *   <li><b>Rate:</b> at full freeze 0.05 per second is 1.5 per 600 ticks, three times FC's own time drain
 *       (EntityPlayerMP: 0.5 per 600 ticks). There is no heat counterpart (decision #15).</li>
 *   <li><b>Immunity:</b> nothing is charged while Cold Sweat itself treats the player as immune to temperature
 *       ({@code EntityTempManager.isImmuneToTemperature}: alive and (peaceful mode or the {@code cold_sweat:grace}
 *       effect)). Its {@code TempEffect.getEffectFactor} is 0 then, so every Cold Sweat freeze effect is off, and BTW's
 *       shiver stays in step with them instead of draining a new player through Cold Sweat's 6000-tick Grace.</li>
 * </ul>
 *
 * <p>FakePlayers are not excluded: the server never ticks them, and the proof helper posts the tick event for one
 * explicitly. The band constants mirror Cold Sweat's default data; a pack that edits Cold Sweat's {@code temp_effects}
 * moves Cold Sweat's effects but not this band.</p>
 *
 * <p>Isolation (SR1): the tick handler catches {@code RuntimeException | LinkageError}, logs one ERROR and switches only
 * shivering off. Loaded only through {@link ColdSweatCompat#init}, so never without Cold Sweat.</p>
 */
// FCMOD-INTEGRATION(1.20.1): Cold Sweat: freezing costs FC exhaustion, so the cold burns fat before hunger as 4.B's hunger rule does
public final class ColdSweatShivering {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ColdSweat");

    /** Body temperature at which shivering starts (factor 0): Cold Sweat's default freeze band minimum. */
    public static final double FREEZE_START = -50.0;

    /** Body temperature of full shivering (factor 1): Cold Sweat's default freeze band maximum. */
    public static final double FREEZE_FULL = -100.0;

    /** FC exhaustion per shiver second at full freeze. */
    public static final float EXHAUSTION_PER_SECOND = 0.05F;

    /** Cold Sweat's Grace effect, by registry id (EffectInit registers "grace" in the cold_sweat namespace). */
    private static final ResourceLocation GRACE_ID = new ResourceLocation("cold_sweat", "grace");

    private static volatile boolean disabled;
    private static volatile boolean graceLookedUp;
    private static volatile @Nullable MobEffect grace;

    private ColdSweatShivering() {}

    /** The PlayerTickEvent listener {@link ColdSweatCompat#init} registers: one shiver second per 20 server ticks. */
    // FCMOD-INTEGRATION(1.20.1): Cold Sweat: one shiver check per second on the server; a failure switches only shivering off
    static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (disabled || event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.player instanceof ServerPlayer player) || player.tickCount % 20 != 0) return;
        try {
            applyShivering(player);
        } catch (RuntimeException | LinkageError t) {
            if (!disabled) {
                disabled = true;
                LOGGER.error("[BTW-ColdSweat] shivering disabled: Cold Sweat's body temperature could not be read; BTW "
                        + "continues without shivering", t);
            }
        }
    }

    /**
     * One shiver second: charges {@code EXHAUSTION_PER_SECOND x factor(body)} through {@code causeFoodExhaustion} and
     * returns the amount charged. 0 when the player is not freezing, dead, creative or spectator, or while Cold Sweat
     * treats the player as immune (the {@code cold_sweat:grace} effect, or peaceful difficulty). Public for the proof
     * helper; it throws what Cold Sweat throws.
     */
    public static float applyShivering(ServerPlayer player) {
        if (!player.isAlive() || player.isCreative() || player.isSpectator()) return 0F;
        if (isImmuneToTemperature(player)) return 0F;
        float exhaustion = EXHAUSTION_PER_SECOND * factor(Temperature.get(player, Temperature.Trait.BODY));
        if (exhaustion > 0F) player.causeFoodExhaustion(exhaustion);
        return exhaustion;
    }

    /** The shiver factor of a body temperature: 0 at {@link #FREEZE_START} and above, 1 at {@link #FREEZE_FULL} and below. */
    public static float factor(double body) {
        if (Double.isNaN(body)) return 0F;
        return (float) Mth.clamp((FREEZE_START - body) / (FREEZE_START - FREEZE_FULL), 0.0, 1.0);
    }

    /**
     * Cold Sweat's own immunity test, {@code EntityTempManager.isImmuneToTemperature} (alive is checked by the caller):
     * peaceful difficulty, or the Grace effect. Cold Sweat's peaceful test also needs its internal
     * {@code USE_PEACEFUL_MODE} setting; BTW does not read it, because FC FoodStats charges nothing on peaceful anyway.
     */
    private static boolean isImmuneToTemperature(ServerPlayer player) {
        if (player.level().getDifficulty() == Difficulty.PEACEFUL) return true;
        MobEffect graceEffect = grace();
        return graceEffect != null && player.hasEffect(graceEffect);
    }

    /** {@code cold_sweat:grace}, looked up once (a player only ticks once the registries are frozen); null when absent. */
    private static @Nullable MobEffect grace() {
        if (!graceLookedUp) {
            MobEffect found = ForgeRegistries.MOB_EFFECTS.containsKey(GRACE_ID)
                    ? ForgeRegistries.MOB_EFFECTS.getValue(GRACE_ID) : null;
            grace = found;
            graceLookedUp = true;
            if (found == null) {
                LOGGER.warn("[BTW-ColdSweat] Cold Sweat's {} effect is not registered; shivering treats every player as "
                        + "outside Grace", GRACE_ID);
            }
        }
        return grace;
    }
}
