package btw.forge.compat.lso;

import btw.forge.data.LsoClimateDataProvider;
import btw.forge.data.LsoClimateDataProvider.LsoFile;
import btw.modern.FoodStats;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sfiomn.legendarysurvivaloverhaul.api.data.manager.BodyDamageDataManager;
import sfiomn.legendarysurvivaloverhaul.api.data.manager.TemperatureDataManager;
import sfiomn.legendarysurvivaloverhaul.api.data.manager.ThirstDataManager;
import sfiomn.legendarysurvivaloverhaul.common.capabilities.bodydamage.BodyDamageCapability;
import sfiomn.legendarysurvivaloverhaul.config.Config;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Legendary Survival Overhaul integration (INTEGRATIONS #031), reached only from {@code BTWCompat} when LSO is loaded.
 *
 * <ul>
 *   <li><b>Data:</b> BTW's heat blocks, clothing, limb armour, hot meals, hydration and fuels ship as LSO JSON
 *       ({@link LsoClimateDataProvider}); at server start this class checks that LSO loaded every planned file with
 *       this server's live BTW keys, and WARNs per missing file.</li>
 *   <li><b>Fat insulation</b> (decision #15): {@link LsoFatInsulation}, a transient cold-resistance modifier from FC
 *       fat.</li>
 *   <li><b>HUD</b> (client, #023): {@link LsoHudCompat} tints BTW's pips frost blue under Cold Hunger and cancels
 *       LSO's second food bar.</li>
 *   <li><b>Regeneration</b> (decision #2, #021): {@code LsoRegenGateMixin} keeps LSO's custom regeneration to BTW's
 *       "heal only above 24/60 FC food" rule; this class reports which clock heals and whether the gate applied.</li>
 * </ul>
 *
 * <p>Soft dependency (SR1): this is the only BTW class that names an LSO ({@code sfiomn.*}) class; the others look
 * LSO's attribute, effect and overlay up by ResourceLocation. {@link #init} only registers listeners (no FC, registry
 * or LSO call), and each handler isolates its own failures.</p>
 */
// FCMOD-INTEGRATION(1.20.1): lso: one entry point for the LSO integration; it registers the handlers and audits LSO's view of BTW at server start
public final class LsoCompat {

    private static final Logger LOGGER = LogManager.getLogger("BTW-LSO");

    private static final String STARTUP_FAILED =
            "[BTW-LSO] startup audit failed; the integration keeps running without it";

    private LsoCompat() {}

    /** Called once from {@code BTWCompat.init} during mod construction, before FC init. Registers listeners only. */
    public static void init(IEventBus modBus) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.PlayerTickEvent.class,
                LsoFatInsulation::onPlayerTick);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerStartedEvent.class,
                LsoCompat::onServerStarted);
        if (FMLEnvironment.dist.isClient()) {
            LsoHudCompat.registerClient(modBus);
        }
    }

    /** The data audit, then the regeneration report; each step logs one ERROR on failure and never stops the server. */
    static void onServerStarted(ServerStartedEvent event) {
        try {
            auditData();
        } catch (Throwable t) {
            LOGGER.error(STARTUP_FAILED, t);
        }
        try {
            reportRegeneration(event.getServer());
        } catch (Throwable t) {
            LOGGER.error(STARTUP_FAILED, t);
        }
    }

    // ------------------------------------------------------------------
    // data audit
    // ------------------------------------------------------------------

    /** Re-plans BTW's LSO data with live keys and asks LSO's data managers for each file. */
    private static void auditData() {
        List<LsoFile> files = LsoClimateDataProvider.plan();
        int loaded = 0;
        for (LsoFile file : files) {
            if (loadedByLso(file)) {
                loaded++;
            } else {
                LOGGER.warn("[BTW-LSO] LSO did not load BTW data {}/{} ({}); that BTW content has no LSO effect "
                        + "(moved BTW id or an LSO data format change)", file.folder(), file.key(), file.source());
            }
        }
        LOGGER.info("[BTW-LSO] data audit: {} files expected, {} loaded by LSO, {} missing", files.size(), loaded,
                files.size() - loaded);
        boolean temperatureOn;
        try {
            temperatureOn = Config.Baked.temperatureEnabled;
        } catch (LinkageError e) {
            return;
        }
        if (!temperatureOn) {
            LOGGER.info("[BTW-LSO] LSO temperature is off; BTW heat, clothing, meal warmth, fuel and fat insulation "
                    + "are inert (thirst and limbs stay active)");
        }
    }

    /** True when the LSO data manager for the file's folder holds an entry under the file's key. */
    private static boolean loadedByLso(LsoFile file) {
        ResourceLocation key = file.key();
        return switch (file.folder()) {
            case LsoClimateDataProvider.BLOCKS -> notEmpty(TemperatureDataManager.getBlock(key));
            case LsoClimateDataProvider.ITEMS -> TemperatureDataManager.getItem(key) != null;
            case LsoClimateDataProvider.CONSUMABLES -> notEmpty(TemperatureDataManager.getConsumable(key));
            case LsoClimateDataProvider.FUELS -> TemperatureDataManager.getFuelItem(key) != null;
            case LsoClimateDataProvider.THIRST -> notEmpty(ThirstDataManager.getConsumable(key));
            case LsoClimateDataProvider.LIMBS -> BodyDamageDataManager.getBodyResistanceItem(key) != null;
            default -> false;
        };
    }

    private static boolean notEmpty(@Nullable List<?> entries) {
        return entries != null && !entries.isEmpty();
    }

    // ------------------------------------------------------------------
    // regeneration report
    // ------------------------------------------------------------------

    /**
     * Which clock heals: BTW's food heal (LSO "Natural Regeneration Enabled" = true, so LSO leaves the gamerule on), or
     * LSO's limb-capped custom regeneration under the BTW food gate. WARNs when nothing heals, when the gate is
     * missing, and when the gamerule disagrees with LSO's config (LSO rewrites it at the next level load).
     */
    private static void reportRegeneration(MinecraftServer server) {
        boolean rule = server.overworld().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        boolean gate = regenGateApplied();
        boolean natural;
        boolean localized;
        boolean custom;
        int rate;
        double exhaustion;
        try {
            natural = Config.Baked.naturalRegenerationEnabled;
            localized = Config.Baked.localizedBodyDamageEnabled;
            custom = Config.Baked.customHealthRegenEnabled;
            rate = Config.Baked.customHealthRegenTickRate;
            exhaustion = Config.Baked.customHealthRegenFoodExhaustion;
        } catch (LinkageError e) {
            LOGGER.info("[BTW-LSO] regeneration: naturalRegeneration={} (LSO config not readable: {})", rule,
                    e.toString());
            return;
        }
        if (natural) {
            LOGGER.info("[BTW-LSO] regeneration: BTW's food heal is the clock (LSO \"Natural Regeneration Enabled\" "
                    + "= true); LSO custom regeneration is off");
        } else if (localized && custom) {
            if (gate) {
                LOGGER.info("[BTW-LSO] regeneration: LSO custom regeneration is the clock (every {} ticks, {} "
                        + "exhaustion per HP paid in FC hunger); BTW food gate applied (heals only above {}/60 FC "
                        + "food)", rate, exhaustion, FoodStats.REGEN_FOOD_THRESHOLD);
            } else {
                LOGGER.warn("[BTW-LSO] regeneration gate did not apply "
                        + "(BodyDamageCapability.applyCustomHealthRegeneration(Player) not found in this LSO version); "
                        + "LSO heals at any FC food above 2/60. Set LSO \"Natural Regeneration Enabled\" = true for "
                        + "BTW's own heal");
            }
        } else {
            LOGGER.warn("[BTW-LSO] no passive regeneration: LSO natural and custom regeneration are both off, and "
                    + "naturalRegeneration=false stops BTW's food heal (INTEGRATIONS #021)");
        }
        if (rule != natural) {
            LOGGER.warn("[BTW-LSO] /gamerule naturalRegeneration={} disagrees with LSO's config ({}); regeneration "
                    + "follows the gamerule until LSO rewrites it at the next level load", rule, natural);
        }
    }

    /**
     * True when LsoRegenGateMixin applied: LSO's BodyDamageCapability carries the {@link LsoRegenGate.Applied} marker
     * and still declares {@code applyCustomHealthRegeneration(Player)}. Naming the class loads it, so the mixin applies
     * here at server start at the latest.
     */
    private static boolean regenGateApplied() {
        try {
            if (!LsoRegenGate.Applied.class.isAssignableFrom(BodyDamageCapability.class)) return false;
            BodyDamageCapability.class.getDeclaredMethod("applyCustomHealthRegeneration", Player.class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
