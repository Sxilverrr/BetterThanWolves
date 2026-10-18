package btw.forge.compat.lso;

import btw.forge.PlayerBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * BTW fat insulates against LSO cold (INTEGRATIONS #031, decision #15): FC's fat (FoodStats saturation, 0-20) becomes
 * up to 2.0 points of LSO cold resistance, linear, as a transient ADDITION modifier on LSO's
 * {@code legendarysurvivaloverhaul:cold_resistance} attribute, refreshed once a second on the server.
 *
 * <p>Vanilla attribute API only, looked up by ResourceLocation, so this class names no LSO class. Transient: the
 * modifier is never saved to player NBT, so no stale value outlives the integration. Each refresh compares against
 * the live modifier on the player, never a cache, so a respawned or relogged player object gets it again within a
 * second.</p>
 */
// FCMOD-INTEGRATION(1.20.1): lso: BTW fat (FC saturation 0-20) gives up to 2.0 LSO cold resistance through a transient attribute modifier
final class LsoFatInsulation {

    private static final Logger LOGGER = LogManager.getLogger("BTW-LSO");

    /** e3a7185f-bdc6-37d1-ac73-276764715e52; the proofs use this literal. */
    static final UUID FAT_UUID =
            UUID.nameUUIDFromBytes("betterthanwolves:lso_fat_insulation".getBytes(StandardCharsets.UTF_8));

    /** LSO's cold-resistance attribute (LSO AttributeRegistry). */
    static final ResourceLocation COLD_RESISTANCE = new ResourceLocation("legendarysurvivaloverhaul", "cold_resistance");

    /** Cold resistance at full fat (20). */
    private static final double MAX_INSULATION = 2.0;

    private static volatile boolean disabled;
    private static volatile Attribute coldResistance;

    private LsoFatInsulation() {}

    /** PlayerTickEvent (NORMAL): END phase, server side, every 20 ticks. Switches itself off on any failure. */
    static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (disabled || event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER
                || !(event.player instanceof ServerPlayer player) || player.tickCount % 20 != 0) {
            return;
        }
        try {
            Attribute attribute = coldResistance;
            if (attribute == null) {
                attribute = ForgeRegistries.ATTRIBUTES.getValue(COLD_RESISTANCE);
                if (attribute == null) {
                    disabled = true;
                    LOGGER.warn("[BTW-LSO] LSO attribute {} is not registered; BTW fat insulation is off",
                            COLD_RESISTANCE);
                    return;
                }
                coldResistance = attribute;
            }
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) return;

            float fat = PlayerBridge.getOrCreate(player).foodStats.getSaturationLevel();
            double want = Math.round(MAX_INSULATION * Mth.clamp(fat, 0F, 20F) / 20.0 * 100.0) / 100.0;

            AttributeModifier current = instance.getModifier(FAT_UUID);
            if (current != null && current.getOperation() == AttributeModifier.Operation.ADDITION
                    && Math.abs(current.getAmount() - want) < 1e-6) {
                return;
            }
            if (current != null) instance.removeModifier(FAT_UUID);
            instance.addTransientModifier(new AttributeModifier(FAT_UUID, "BTW fat insulation", want,
                    AttributeModifier.Operation.ADDITION));
        } catch (RuntimeException | LinkageError t) {
            disabled = true;
            LOGGER.warn("[BTW-LSO] BTW fat insulation failed and is off from now on", t);
        }
    }
}
