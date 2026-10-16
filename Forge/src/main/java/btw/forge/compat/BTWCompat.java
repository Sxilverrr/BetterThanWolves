package btw.forge.compat;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * The one loader for BTW's optional-mod integrations (INTEGRATIONS #020, mod-compat foundation).
 *
 * <ul>
 *   <li><b>Modern feature:</b> other Forge mods (Dynamic Trees, Serene Seasons, AppleSkin, Cold Sweat, Legendary
 *       Survival Overhaul, Pam's HarvestCraft 2) that BTW feeds its 4.B rules into when they are installed.</li>
 *   <li><b>Conflict with FC:</b> none by itself. Every integration is optional; BTW must load and play the same
 *       when none of these mods is present, and a missing, broken or API-incompatible mod may only switch its own
 *       integration off.</li>
 *   <li><b>Pattern:</b> one guarded dispatch per mod, called once from the {@code BTWForgeMod} constructor right
 *       after the network channel registers. Each integration lives in {@code btw.forge.compat.<mod>} and is
 *       reached only through {@code <Mod>Compat.init(IEventBus)} (AppleSkin: {@code initClient}).</li>
 *   <li><b>Alt rejected:</b> one {@code @Mod.EventBusSubscriber} per integration. Rejected: Forge scans and loads
 *       every annotated class whether or not its mod is present, so a missing mod becomes a linkage error at
 *       startup.</li>
 * </ul>
 *
 * <p><b>Soft-dependency rules</b> (SR1), binding on every compat workstream:</p>
 * <ul>
 *   <li>A compat class is named here only by a direct {@code invokestatic} inside its {@code isLoaded} branch. No
 *       lambda, method reference, {@code Map<String, Runnable>}, {@code Class.forName} or class literal may name
 *       one. The JVM resolves an {@code invokestatic} lazily, so a compat class is never loaded when its mod is
 *       absent, and {@code catch (Throwable)} also absorbs the {@code LinkageError} of an API-incompatible
 *       version. This class therefore contains no {@code invokedynamic} at all (no lambdas, no method references,
 *       no string concatenation): {@code javap -v} of it must show none.</li>
 *   <li>Compat classes carry no {@code @Mod} or {@code @Mod.EventBusSubscriber}; their entry point's signature
 *       exposes no foreign type; no always-loaded class holds a static field of a foreign type.</li>
 *   <li>{@code init} runs during mod construction, possibly on a loader worker thread, BEFORE FC init
 *       (RegisterEvent BLOCKS). It only registers listeners: never call {@link FcRefs},
 *       {@code BtwThermalTable}, {@code ProxyRegistry} or any registry there. Resolve lazily in the handlers.</li>
 *   <li>Shared facts are not re-implemented: RemovedContent / FcRecipeRemovals already remove 4.B-removed ids
 *       in every namespace; BoneMealPolicy owns the BonemealEvent listener (LOW priority); TickPolicy's random
 *       tick dispatch (mixin priority 1100) fires CropGrowEvent for FC growth (INTEGRATIONS #016).</li>
 * </ul>
 *
 * <p>{@link ModList} is filled before the CONSTRUCT state (fmlcore ModLoader.gatherAndInitializeMods calls
 * {@code setLoadedMods} before the GATHER states), so {@link #isLoaded} answers correctly inside the mod
 * constructor.</p>
 */
// FCMOD-INTEGRATION(1.20.1): mod-compat: one guarded loader for optional mods; a missing or broken mod only switches its own integration off
public final class BTWCompat {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Compat");

    public static final String DYNAMIC_TREES = "dynamictrees";
    public static final String SERENE_SEASONS = "sereneseasons";
    public static final String APPLESKIN = "appleskin";
    public static final String COLD_SWEAT = "cold_sweat";
    public static final String LSO = "legendarysurvivaloverhaul";

    /** Pam's HarvestCraft 2 ships as separate modules; any one of them enables the integration. */
    public static final List<String> HARVESTCRAFT = List.of("pamhc2crops", "pamhc2trees", "pamhc2foodcore",
            "pamhc2foodextended");

    private BTWCompat() {}

    /** True when the mod with this id is loaded. Valid from mod construction on. */
    public static boolean isLoaded(String modId) {
        ModList ml = ModList.get();
        return ml != null && ml.isLoaded(modId);
    }

    /** True when at least one Pam's HarvestCraft 2 module ({@link #HARVESTCRAFT}) is loaded. */
    public static boolean anyHarvestCraftLoaded() {
        for (String modId : HARVESTCRAFT) {
            if (isLoaded(modId)) return true;
        }
        return false;
    }

    /** The loaded version of a mod, or {@code "?"} when it is not loaded. */
    public static String version(String modId) {
        ModList ml = ModList.get();
        if (ml == null) return "?";
        Optional<? extends ModContainer> container = ml.getModContainerById(modId);
        return container.isPresent() ? container.get().getModInfo().getVersion().toString() : "?";
    }

    /**
     * Called once from the {@code BTWForgeMod} constructor, after {@code BTWNetwork.register()}. Registers each
     * present integration's listeners on {@code modBus} (and on the Forge bus); see the class javadoc for what an
     * integration may do here.
     */
    public static void init(IEventBus modBus) {
        // FCMOD-INTEGRATION(1.20.1): mod-compat: both mods simulate body temperature from the same BTW heat sources and clothing, so their effects would stack
        if (isLoaded(COLD_SWEAT) && isLoaded(LSO)) {
            LOGGER.warn("[BTW-Compat] Cold Sweat and Legendary Survival Overhaul both simulate body temperature; BTW "
                    + "feeds both and their effects stack. Recommended: Cold Sweat owns temperature; set "
                    + "\"Temperature Enabled\" = false under [core] in "
                    + "config/legendarysurvivaloverhaul/legendarysurvivaloverhaul-common.toml (LSO thirst, limbs and "
                    + "health overhaul keep working).");
        }

        // ---- Per-mod dispatch (INSERTION POINT) ----
        // The six guarded blocks go here, in this order, in the same change that adds their entry-point classes
        // (they are a compile dependency). Shape, one per mod (SR1: direct invokestatic only):
        //   if (isLoaded(DYNAMIC_TREES)) {
        //       try { btw.forge.compat.dynamictrees.DynamicTreesCompat.init(modBus); enabled(DYNAMIC_TREES, "#027"); }
        //       catch (Throwable t) { failed(DYNAMIC_TREES, t); }
        //   }
        //   SERENE_SEASONS -> btw.forge.compat.sereneseasons.SereneSeasonsCompat.init(modBus), "#028"
        //   APPLESKIN, only when net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()
        //             -> btw.forge.compat.appleskin.AppleSkinCompat.initClient(modBus), "#029"
        //   COLD_SWEAT -> btw.forge.compat.coldsweat.ColdSweatCompat.init(modBus), "#030"
        //   LSO -> btw.forge.compat.lso.LsoCompat.init(modBus), "#031"
        //   anyHarvestCraftLoaded() -> btw.forge.compat.harvestcraft.HarvestCraftCompat.init(modBus),
        //             enabled(HARVESTCRAFT, "#032"); failed("harvestcraft", t)
        // ---- end of per-mod dispatch ----
    }

    /** Logged only after the integration's entry point returned normally, so the line means "started". */
    private static void enabled(String modId, String integration) {
        LOGGER.info("[BTW-Compat] {} {} present: integration {} enabled", modId, version(modId), integration);
    }

    /** {@link #enabled(String, String)} for a multi-module mod: lists every loaded module with its version. */
    private static void enabled(List<String> modIds, String integration) {
        StringBuilder present = new StringBuilder();
        for (String modId : modIds) {
            if (!isLoaded(modId)) continue;
            if (present.length() > 0) present.append(", ");
            present.append(modId).append(' ').append(version(modId));
        }
        LOGGER.info("[BTW-Compat] {} present: integration {} enabled", present, integration);
    }

    private static void failed(String modId, Throwable t) {
        LOGGER.error("[BTW-Compat] {} integration failed to start; BTW continues without it", modId, t);
    }
}
