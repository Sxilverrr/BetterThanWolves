package btw.forge.compat.lso;

import btw.forge.PlayerBridge;
import btw.modern.FoodStats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * BTW's food gate on LSO's custom health regeneration (INTEGRATIONS #031, decision #2; the hunger contract is #021).
 *
 * <p>Under LSO's default config ("Natural Regeneration Enabled" = false) LSO turns the naturalRegeneration gamerule
 * off, which pauses BTW's own food heal, and heals through its limb-capped custom regeneration whenever vanilla food is
 * above 0, which is FC food 3 and up. BTW heals only above {@link FoodStats#REGEN_FOOD_THRESHOLD} (24/60).
 * {@code LsoRegenGateMixin} asks {@link #blocks} at the head of LSO's regeneration step and cancels the step when BTW's
 * rule forbids it; a cancelled step neither heals nor costs hunger, and LSO tries again after its next interval.</p>
 *
 * <p>This class lives outside the mixin package because Mixin refuses to let other code load classes from there. It
 * names no LSO class and fails open: on any error the gate switches off and LSO regenerates as it would without BTW.</p>
 */
// FCMOD-INTEGRATION(1.20.1): lso: LSO's custom regeneration heals only where BTW's own heal would (FC food above 24/60)
public final class LsoRegenGate {

    private static final Logger LOGGER = LogManager.getLogger("BTW-LSO");

    /** Added to LSO's BodyDamageCapability by LsoRegenGateMixin; LsoCompat reads it to report that the gate applied. */
    public interface Applied {}

    private static volatile boolean engaged;
    private static volatile boolean failed;

    private LsoRegenGate() {}

    /**
     * True when BTW's heal rule forbids this LSO regeneration step: the player's FC food is at or below
     * {@link FoodStats#REGEN_FOOD_THRESHOLD} (24/60). Only server players are gated. Fails open.
     */
    public static boolean blocks(Player player) {
        if (failed || !(player instanceof ServerPlayer serverPlayer)) return false;
        try {
            boolean block = PlayerBridge.getOrCreate(serverPlayer).foodStats.getFoodLevel()
                    <= FoodStats.REGEN_FOOD_THRESHOLD;
            if (!engaged) {
                engaged = true;
                LOGGER.info("[BTW-LSO] regeneration gate engaged: LSO custom regeneration heals only above {}/60 FC "
                        + "food", FoodStats.REGEN_FOOD_THRESHOLD);
            }
            return block;
        } catch (RuntimeException | LinkageError t) {
            failed = true;
            LOGGER.warn("[BTW-LSO] regeneration gate failed; LSO regeneration runs ungated from now on", t);
            return false;
        }
    }
}
