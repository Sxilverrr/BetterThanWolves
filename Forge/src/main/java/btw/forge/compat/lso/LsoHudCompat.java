package btw.forge.compat.lso;

import btw.forge.client.BTWHudOverlay;
import btw.forge.client.FoodHudExtension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * LSO's Cold Hunger on BTW's food row (INTEGRATIONS #031, client only; the HUD stack is #023).
 *
 * <p>LSO shows Cold Hunger with its own overlay, registered below {@code food_level} as
 * {@code legendarysurvivaloverhaul:cold_hunger}: a second, vanilla-scale food bar that also adds 10 to
 * {@code rightHeight}, so it doubles BTW's FC pips and pushes the row stack up. BTW cancels that overlay and instead
 * tints its own pips frost blue while the {@code legendarysurvivaloverhaul:cold_hunger} effect is active, through
 * {@link FoodHudExtension#foodPipTint}. A cancelled Pre skips the overlay's render and Post (ForgeGui).</p>
 *
 * <p>Reached only from {@code LsoCompat.init} on the client dist. It names no LSO class: the overlay and the effect
 * are looked up by ResourceLocation. An extension that throws is removed by {@link BTWHudOverlay}.</p>
 */
// FCMOD-INTEGRATION(1.20.1): lso: Cold Hunger tints BTW's FC food pips instead of LSO drawing a second food bar over them
public final class LsoHudCompat implements FoodHudExtension {

    private static final Logger LOGGER = LogManager.getLogger("BTW-LSO");

    /** LSO's Cold Hunger: both the overlay id and the MobEffect id. */
    static final ResourceLocation COLD_HUNGER = new ResourceLocation("legendarysurvivaloverhaul", "cold_hunger");

    /** Frost blue, ARGB. */
    static final int FROST_TINT = 0xFFB0E0FF;

    private static final LsoHudCompat INSTANCE = new LsoHudCompat();

    private static volatile MobEffect coldHunger;
    private static volatile boolean effectMissing;

    private LsoHudCompat() {}

    /** Registers the food-row extension at client setup and the overlay cancel on the Forge bus. */
    static void registerClient(IEventBus modBus) {
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class,
                event -> BTWHudOverlay.addFoodHudExtension(INSTANCE));
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, false, RenderGuiOverlayEvent.Pre.class,
                LsoHudCompat::onPreOverlay);
    }

    /** Cancels LSO's Cold Hunger food bar; BTW's pips show the state instead. */
    static void onPreOverlay(RenderGuiOverlayEvent.Pre event) {
        if (COLD_HUNGER.equals(event.getOverlay().id())) {
            event.setCanceled(true);
        }
    }

    @Override
    public int foodPipTint(LocalPlayer player) {
        MobEffect effect = coldHunger;
        if (effect == null) {
            if (effectMissing) return NO_TINT;
            effect = ForgeRegistries.MOB_EFFECTS.getValue(COLD_HUNGER);
            if (effect == null) {
                effectMissing = true;
                LOGGER.warn("[BTW-LSO] LSO effect {} is not registered; the frost tint on BTW's food pips is off",
                        COLD_HUNGER);
                return NO_TINT;
            }
            coldHunger = effect;
        }
        return player.hasEffect(effect) ? FROST_TINT : NO_TINT;
    }
}
