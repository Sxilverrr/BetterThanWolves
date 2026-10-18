package btw.forge.compat.appleskin;

import btw.forge.BTWNetwork;
import btw.forge.client.BTWHudOverlay;
import btw.forge.food.FcFoodValues;
import btw.modern.FoodStats;
import com.mojang.datafixers.util.Either;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import squeek.appleskin.api.event.FoodValuesEvent;
import squeek.appleskin.api.event.HUDOverlayEvent;
import squeek.appleskin.api.event.TooltipOverlayEvent;
import squeek.appleskin.api.food.FoodValues;

import javax.annotation.Nullable;
import java.text.DecimalFormat;
import java.util.ArrayList;

/**
 * AppleSkin integration (INTEGRATIONS #029): every AppleSkin display shows BTW's food model, through AppleSkin's
 * public API only. Client only.
 *
 * <ul>
 *   <li><b>Modern feature:</b> AppleSkin 2.5.x (food values in tooltips, held-food previews on the food bar, an
 *       exhaustion underlay, an estimated-health hint, an F3 food line).</li>
 *   <li><b>Conflict with FC:</b> AppleSkin reads vanilla's 0-20 FoodData and FoodProperties. BTW feeds FC hunger on a
 *       0-60 scale with overflow-only fat, penalty levels and food poisoning (#021, #024), draws FC's food row in place
 *       of vanilla's (#023, so AppleSkin's food_level hooks never run), and heals above 24/60 instead of from
 *       saturation. AppleSkin's own displays were therefore wrong (bread 2.5 shanks for BTW's 1.5), missing (pumpkin
 *       seeds and milk have no FoodProperties) or invisible (its health hint lands off-screen at y=H).</li>
 *   <li><b>Pattern:</b> AppleSkin's events. {@code FoodValuesEvent} receives FC values in AppleSkin's int units for
 *       third-party listeners; a cancelled {@code TooltipOverlayEvent.Pre} hands AppleSkin's tooltip slot to
 *       {@link BtwFoodTooltip}, which is also added for FC foods AppleSkin cannot see, under AppleSkin's own show
 *       rule; the four {@code HUDOverlayEvent}s are cancelled and {@link AppleSkinHud} draws on BTW's bars instead;
 *       the F3 line is replaced in place. AppleSkin's on/off settings are read by {@link AppleSkinClientOptions}.</li>
 *   <li><b>Alt rejected:</b> a mixin into AppleSkin's handlers. Its internals are not API, and the event API already
 *       reaches every display.</li>
 * </ul>
 *
 * <p>Soft dependency (SR1): this is the only class that names an AppleSkin type, all from AppleSkin's public API
 * package and only inside this class. {@link #initClient} is reached only through BTWCompat's direct
 * {@code invokestatic}, when AppleSkin is loaded on a client; its signature names no AppleSkin type. It links every
 * AppleSkin member used (preflight) before registering anything, so an incompatible AppleSkin fails there, inside
 * BTWCompat's catch, and nothing is left half-registered.</p>
 *
 * <p>Failure isolation: every handler body catches {@code RuntimeException | LinkageError}, logs one ERROR and
 * switches the whole integration off ({@link #switchOff}); from then on every listener returns at once,
 * {@link AppleSkinHud} draws nothing and AppleSkin shows its own displays again. AppleSkin's own settings and
 * third-party AppleSkin listeners are untouched; FC eating is unchanged (display only).</p>
 *
 * <p>Known limit: third-party AppleSkin listeners see {@code ceil(hunger60 / 3)} and a fat-based saturation
 * modifier, AppleSkin's API being in vanilla int units.</p>
 */
// FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin shows vanilla hunger; BTW answers its events with FC's food model and draws AppleSkin's displays on BTW's bars
public final class AppleSkinCompat {

    private static final Logger LOGGER = LogManager.getLogger("BTW-AppleSkin");

    /** True from the end of {@link #initClient} until a handler fails. */
    private static volatile boolean active;

    /** The stack whose AppleSkin tooltip BTW just took over (TooltipOverlayEvent.Pre). Render thread only. */
    @Nullable
    private static ItemStack marked;

    // AppleSkin's flash cycle (HUDOverlayHandler.java:419-441), kept here because AppleSkin's own is not API.
    // Client thread only.
    private static float unclampedFlashAlpha;
    private static float flashAlpha;
    private static int alphaDir = 1;

    /** "#.##" and "0.00" in the default locale, as AppleSkin's own F3 line (DebugInfoHandler.java:18-20). */
    private static final DecimalFormat FAT_FORMAT = new DecimalFormat("#.##");
    private static final DecimalFormat EXHAUSTION_FORMAT = new DecimalFormat("0.00");

    private AppleSkinCompat() {}

    /**
     * Starts the integration; called once from BTWCompat during mod construction, only on a client with AppleSkin
     * loaded. Links every AppleSkin member used, then registers the tooltip factory and the food-row extension on the
     * mod bus and the event listeners on the Forge bus. Any throw propagates to BTWCompat, which logs it and carries
     * on without the integration.
     */
    public static void initClient(IEventBus modBus) {
        preflight();

        modBus.addListener(EventPriority.NORMAL, false, RegisterClientTooltipComponentFactoriesEvent.class,
                AppleSkinCompat::onRegisterTooltipFactories);
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, AppleSkinCompat::onClientSetup);

        IEventBus forge = MinecraftForge.EVENT_BUS;
        forge.addListener(EventPriority.NORMAL, false, FoodValuesEvent.class, AppleSkinCompat::onFoodValues);
        forge.addListener(EventPriority.NORMAL, false, TooltipOverlayEvent.Pre.class, AppleSkinCompat::onTooltipPre);
        forge.addListener(EventPriority.HIGHEST, true, RenderTooltipEvent.GatherComponents.class,
                AppleSkinCompat::onGatherHighest);
        forge.addListener(EventPriority.LOW, false, RenderTooltipEvent.GatherComponents.class,
                AppleSkinCompat::onGatherLow);
        forge.addListener(EventPriority.NORMAL, false, HUDOverlayEvent.HealthRestored.class,
                AppleSkinCompat::cancelHudEvent);
        forge.addListener(EventPriority.NORMAL, false, HUDOverlayEvent.Exhaustion.class,
                AppleSkinCompat::cancelHudEvent);
        forge.addListener(EventPriority.NORMAL, false, HUDOverlayEvent.Saturation.class,
                AppleSkinCompat::cancelHudEvent);
        forge.addListener(EventPriority.NORMAL, false, HUDOverlayEvent.HungerRestored.class,
                AppleSkinCompat::cancelHudEvent);
        forge.addListener(EventPriority.LOW, false, CustomizeGuiOverlayEvent.DebugText.class,
                AppleSkinCompat::onDebugText);
        forge.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, AppleSkinCompat::onClientTick);
        forge.addListener(EventPriority.LOWEST, false, RenderGuiOverlayEvent.Pre.class,
                AppleSkinCompat::onOverlayPre);
        forge.addListener(EventPriority.NORMAL, false, RenderGuiOverlayEvent.Post.class,
                AppleSkinCompat::onOverlayPost);

        active = true;
    }

    /**
     * Links every AppleSkin constructor, field and class this integration uses, before any listener registers: a
     * missing or changed member throws here (a LinkageError), inside BTWCompat's catch, instead of in a handler.
     */
    @SuppressWarnings("unused") // the reads exist only to link each field: javac keeps every getfield
    private static void preflight() {
        FoodValues values = new FoodValues(0, 0F);
        int hunger = values.hunger;
        float saturationModifier = values.saturationModifier;

        FoodValuesEvent foodValuesEvent = new FoodValuesEvent(null, ItemStack.EMPTY, values, values);
        foodValuesEvent.defaultFoodValues = values;
        foodValuesEvent.modifiedFoodValues = values;
        ItemStack foodStack = foodValuesEvent.itemStack;
        Player foodPlayer = foodValuesEvent.player;

        TooltipOverlayEvent.Pre pre = new TooltipOverlayEvent.Pre(ItemStack.EMPTY, values, values);
        pre.setCanceled(true);
        ItemStack preStack = pre.itemStack;

        Class<?>[] hudEvents = {
                HUDOverlayEvent.HealthRestored.class, HUDOverlayEvent.Exhaustion.class,
                HUDOverlayEvent.Saturation.class, HUDOverlayEvent.HungerRestored.class
        };
    }

    /** True while the integration runs (not yet switched off). */
    static boolean active() {
        return active;
    }

    /**
     * Switches the whole integration off after a failure in {@code where}, logging one ERROR the first time. From then
     * on every listener returns at once and AppleSkin shows its own displays (BTW's food row stays as it is).
     */
    static void switchOff(String where, Throwable t) {
        if (active) {
            active = false;
            LOGGER.error("[BTW-AppleSkin] {} handler failed; AppleSkin integration switched off, AppleSkin keeps its "
                    + "own displays", where, t);
        }
    }

    /** AppleSkin's flash alpha for this tick (0 to maxHudOverlayFlashAlpha). */
    static float flashAlpha() {
        return flashAlpha;
    }

    /** Restarts the flash cycle, as AppleSkin does whenever no food preview is shown. */
    static void resetFlash() {
        unclampedFlashAlpha = 0F;
        flashAlpha = 0F;
        alphaDir = 1;
    }

    // ------------------------------------------------------------------
    // Mod bus
    // ------------------------------------------------------------------

    private static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent e) {
        if (!active) return;
        try {
            e.register(BtwFoodTooltip.class, BtwFoodTooltipRenderer::new);
        } catch (RuntimeException | LinkageError t) {
            // Without its factory a BtwFoodTooltip could not be drawn, so none may ever be added.
            switchOff("tooltip factory", t);
        }
    }

    // FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin's food_level hooks never run under BTW's row, so its previews and underlay join BTW's row as an extension
    private static void onClientSetup(FMLClientSetupEvent e) {
        if (!active) return;
        try {
            BTWHudOverlay.addFoodHudExtension(AppleSkinHud.INSTANCE);
        } catch (RuntimeException | LinkageError t) {
            switchOff("client setup", t);
        }
    }

    // ------------------------------------------------------------------
    // Food values and tooltips
    // ------------------------------------------------------------------

    /**
     * FC values in AppleSkin's units, for third-party AppleSkin listeners (BTW draws its own displays): hunger
     * {@code ceil(hunger60 / 3)}; the saturation modifier is the FC fat the food adds as overflow, over
     * {@code 2 * hunger}, at an empty-fat full bar (default) and at the client's current food and fat (modified).
     */
    // FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin's vanilla food values become FC's, so every AppleSkin listener sees what BTW feeds
    private static void onFoodValues(FoodValuesEvent e) {
        if (!active) return;
        try {
            AppleSkinProbe.maybeThrow("FoodValuesEvent");
            FcFoodValues.FcFood food = FcFoodValues.of(e.itemStack, e.player);
            if (food == null) return;
            int hunger20 = (int) Math.ceil(food.hunger60() / 3.0D);
            float defaultModifier = hunger20 == 0 ? 0F
                    : FcFoodValues.preview(60, 0F, food).fatGained() / (2F * hunger20);
            float modifiedModifier = hunger20 == 0 ? 0F
                    : FcFoodValues.preview(BTWNetwork.clientFoodLevel, BTWNetwork.clientSaturation, food).fatGained()
                    / (2F * hunger20);
            e.defaultFoodValues = new FoodValues(hunger20, defaultModifier);
            e.modifiedFoodValues = new FoodValues(hunger20, modifiedModifier);
        } catch (RuntimeException | LinkageError t) {
            switchOff("FoodValuesEvent", t);
        }
    }

    /**
     * AppleSkin posts this from its NORMAL GatherComponents listener with the hovered stack
     * (TooltipOverlayHandler.java:307-310). For a food FC values, BTW cancels it (AppleSkin then adds nothing) and
     * marks the stack, so the LOW listener adds BTW's tooltip in its place.
     */
    // FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin's vanilla shank tooltip gives way to BTW's FC tooltip for every food FC values
    private static void onTooltipPre(TooltipOverlayEvent.Pre e) {
        if (!active) return;
        try {
            if (FcFoodValues.of(e.itemStack, Minecraft.getInstance().player) != null) {
                e.setCanceled(true);
                marked = e.itemStack;
            }
        } catch (RuntimeException | LinkageError t) {
            switchOff("TooltipOverlayEvent.Pre", t);
        }
    }

    /** Clears the mark before any listener of this tooltip runs, so a stale mark never matches. */
    private static void onGatherHighest(RenderTooltipEvent.GatherComponents e) {
        if (!active) return;
        try {
            marked = null;
        } catch (RuntimeException | LinkageError t) {
            switchOff("GatherComponents", t);
        }
    }

    /**
     * Adds BTW's food tooltip after AppleSkin's listener ran: on the marked path (AppleSkin's Pre was cancelled for
     * this stack, so AppleSkin's show rule already passed), or on the unmarked path for a stack FC feeds that AppleSkin
     * cannot see ({@code getFoodProperties(stack, player) == null}: pumpkin seeds, the milk bucket), under AppleSkin's
     * own show rule.
     */
    // FCMOD-INTEGRATION(1.20.1): appleskin: FC foods without 1.20.1 FoodProperties are invisible to AppleSkin, so BTW shows them under AppleSkin's own rule
    private static void onGatherLow(RenderTooltipEvent.GatherComponents e) {
        if (!active) return;
        try {
            ItemStack stack = e.getItemStack();
            if (stack.isEmpty()) return;
            Player player = Minecraft.getInstance().player;
            String path = null;
            if (marked != null && (marked == stack || ItemStack.isSameItemSameTags(marked, stack))) {
                path = "marked";
            } else if (stack.getItem().getFoodProperties(stack, player) == null
                    && AppleSkinClientOptions.tooltipShown()) {
                path = "unmarked";
            }
            marked = null;
            if (path == null) return;

            BtwFoodTooltip tooltip = BtwFoodTooltip.of(stack, player);
            if (tooltip == null) return;
            e.getTooltipElements().add(Either.right(tooltip));
            AppleSkinProbe.tooltip(path, stack, tooltip);
        } catch (RuntimeException | LinkageError t) {
            switchOff("GatherComponents", t);
        }
    }

    // ------------------------------------------------------------------
    // HUD, F3, flash
    // ------------------------------------------------------------------

    /**
     * Cancels AppleSkin's own HUD draws; {@link AppleSkinHud} draws them on BTW's bars instead. HealthRestored
     * estimates vanilla saturation regeneration, which BTW does not have (FC heals above 24/60; FoodData.tick is
     * cancelled for server players), and lands at y=H because AppleSkin's food_level Pre never runs under BTW's row.
     * AppleSkin estimates from the hunger {@link #onFoodValues} hands it, so it posts HealthRestored only when vanilla's
     * food copy plus that hunger reaches 18 or the food carries Regeneration (golden apples), not for bread at 40/60.
     * The other three cannot fire under BTW's row (BTWHudOverlay cancels food_level at HIGHEST) and are cancelled as
     * a safeguard.
     */
    // FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin's vanilla-model HUD draws would sit on the wrong bar with the wrong numbers
    private static void cancelHudEvent(Event e) {
        if (!active) return;
        try {
            e.setCanceled(true);
        } catch (RuntimeException | LinkageError t) {
            switchOff("HUDOverlayEvent", t);
        }
    }

    /**
     * Replaces AppleSkin's F3 line ("hunger: n, sat: s, exh: e/max", DebugInfoHandler.java:39) in place with FC's food,
     * fat and exhaustion against FC's current burn threshold, or adds it when AppleSkin's line is absent. Only while
     * AppleSkin's own F3 setting is on.
     */
    // FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin's F3 line shows vanilla's FoodData copy, not the FC meter BTW runs
    private static void onDebugText(CustomizeGuiOverlayEvent.DebugText e) {
        if (!active) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.options.renderDebug || mc.player == null
                    || !AppleSkinClientOptions.showFoodStatsInDebugOverlay()) {
                return;
            }
            int food = BTWNetwork.clientFoodLevel;
            float fat = BTWNetwork.clientSaturation;
            boolean burningFat = FoodStats.shouldBurnFatBeforeHunger(food, fat);
            float threshold = burningFat ? FoodStats.FAT_BURN_THRESHOLD : FoodStats.HUNGER_BURN_THRESHOLD;
            String line = I18n.get("btw.compat.food.debug", food, FAT_FORMAT.format(fat),
                    EXHAUSTION_FORMAT.format(BTWNetwork.clientExhaustion), FAT_FORMAT.format(threshold),
                    I18n.get(burningFat ? "btw.compat.food.burning_fat" : "btw.compat.food.burning_hunger"));

            ArrayList<String> left = e.getLeft();
            for (int i = 0; i < left.size(); i++) {
                String entry = left.get(i);
                if (entry != null && entry.startsWith("hunger: ") && entry.contains(", exh: ")) {
                    left.set(i, line);
                    return;
                }
            }
            left.add(line);
        } catch (RuntimeException | LinkageError t) {
            switchOff("DebugText", t);
        }
    }

    /** AppleSkin's flash cycle (HUDOverlayHandler.java:419-435), scaled by its maxHudOverlayFlashAlpha. */
    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (!active) return;
        try {
            if (e.phase != TickEvent.Phase.END) return;
            unclampedFlashAlpha += alphaDir * 0.125F;
            if (unclampedFlashAlpha >= 1.5F) {
                alphaDir = -1;
            } else if (unclampedFlashAlpha <= -0.5F) {
                alphaDir = 1;
            }
            float maxAlpha = Math.max(0F, Math.min(1F, AppleSkinClientOptions.maxHudOverlayFlashAlpha()));
            flashAlpha = Math.max(0F, Math.min(1F, unclampedFlashAlpha)) * maxAlpha;
        } catch (RuntimeException | LinkageError t) {
            switchOff("ClientTick", t);
        }
    }

    /**
     * Captures the heart row's top ({@code screenHeight - ForgeGui.leftHeight}, as ForgeGui.renderHealth computes it)
     * after every other player_health Pre listener; a cancelled heart row is skipped.
     */
    private static void onOverlayPre(RenderGuiOverlayEvent.Pre e) {
        if (!active) return;
        try {
            if (e.getOverlay() != VanillaGuiOverlay.PLAYER_HEALTH.type()) return;
            if (Minecraft.getInstance().gui instanceof ForgeGui gui) {
                AppleSkinHud.captureHealthTop(e.getWindow().getGuiScaledHeight() - gui.leftHeight);
            }
        } catch (RuntimeException | LinkageError t) {
            switchOff("player_health Pre", t);
        }
    }

    /** Draws the estimated-health hint over the heart row just drawn. */
    // FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin's health hint predicts vanilla saturation regen off-screen; BTW's marks its real 24/60 regen hinge on the heart row
    private static void onOverlayPost(RenderGuiOverlayEvent.Post e) {
        if (!active) return;
        try {
            if (e.getOverlay() != VanillaGuiOverlay.PLAYER_HEALTH.type()) return;
            AppleSkinHud.INSTANCE.renderHealthHint(e.getGuiGraphics(), e.getWindow().getGuiScaledWidth(),
                    e.getWindow().getGuiScaledHeight());
        } catch (RuntimeException | LinkageError t) {
            switchOff("player_health Post", t);
        }
    }
}
