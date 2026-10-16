package btw.forge.recipes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place the recipe bridge reaches FC's own recipe managers (INTEGRATIONS #025). FC classes are not on the
 * Forge module's compile classpath (Forge/build.gradle lists only Modern-Common and Api), so each manager is found by
 * reflection, as jei/FCRecipeExtractor and RemovedContent do. Handles resolve lazily, once; a failure logs one WARN
 * and the manager then counts as absent (every lookup answers null, every write answers false). Descriptors, checked
 * with javap on Forge/build/classes/java/fc:
 * <ul>
 *   <li>{@code FCCraftingManagerAnvil.getInstance()} and
 *       {@code findMatchingRecipe(Lbtw/modern/InventoryCrafting;Lbtw/modern/World;)Lbtw/modern/ItemStack;}
 *       (the soulforge's 4x4 list);</li>
 *   <li>{@code FCCraftingManagerCampfire.instance} (public static), {@code GetRecipeResult(I)}, {@code
 *       AddRecipe(ILbtw/modern/ItemStack;)V} and the private {@code Map m_recipeMap} (FC has no remove);</li>
 *   <li>{@code FCCraftingManagerMillStone.getInstance()} with FCCraftingManagerBulk's
 *       {@code AddRecipe(Lbtw/modern/ItemStack;Lbtw/modern/ItemStack;)V}, {@code
 *       RemoveRecipe(Lbtw/modern/ItemStack;Lbtw/modern/ItemStack;)Z} and {@code
 *       GetCraftingResult(Lbtw/modern/ItemStack;)Ljava/util/List;}.</li>
 * </ul>
 */
// FCMOD-INTEGRATION(1.20.1): recipes: reflective handles on FC's anvil, campfire and millstone managers
public final class FcCraftingManagers {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RecipeBridge");

    private static final String PKG = "net.minecraft.src.btw.crafting.";
    private static final String ANVIL = PKG + "FCCraftingManagerAnvil";
    private static final String CAMPFIRE = PKG + "FCCraftingManagerCampfire";
    private static final String MILLSTONE = PKG + "FCCraftingManagerMillStone";

    /** Managers whose handles failed to resolve or whose call threw (one WARN each per JVM). */
    private static final Set<String> FAILURES_LOGGED = ConcurrentHashMap.newKeySet();

    private FcCraftingManagers() {}

    // ---- anvil (soulforge) -----------------------------------------------------------------------------

    private record Anvil(Object instance, Method find) {}

    private static volatile Anvil anvil;
    private static volatile boolean anvilResolved;

    private static Anvil anvil() {
        if (!anvilResolved) {
            synchronized (FcCraftingManagers.class) {
                if (!anvilResolved) {
                    try {
                        Class<?> cls = Class.forName(ANVIL);
                        Object instance = cls.getMethod("getInstance").invoke(null);
                        Method find = cls.getMethod("findMatchingRecipe", btw.modern.InventoryCrafting.class,
                                btw.modern.World.class);
                        if (instance != null) anvil = new Anvil(instance, find);
                    } catch (Throwable t) {
                        fail(ANVIL, t);
                    }
                    anvilResolved = true;
                }
            }
        }
        return anvil;
    }

    /** FC's soulforge (anvil) list result for this grid, or null (no match, or the anvil list is unreachable). */
    public static btw.modern.ItemStack anvilResult(btw.modern.InventoryCrafting inv, btw.modern.World world) {
        Anvil a = anvil();
        if (a == null || inv == null) return null;
        try {
            return (btw.modern.ItemStack) a.find().invoke(a.instance(), inv, world);
        } catch (Throwable t) {
            fail(ANVIL + ".findMatchingRecipe", t);
            return null;
        }
    }

    // ---- campfire spit ---------------------------------------------------------------------------------

    private record Campfire(Object instance, Method get, Method add, Field map) {}

    private static volatile Campfire campfire;
    private static volatile boolean campfireResolved;

    private static Campfire campfire() {
        if (!campfireResolved) {
            synchronized (FcCraftingManagers.class) {
                if (!campfireResolved) {
                    try {
                        Class<?> cls = Class.forName(CAMPFIRE);
                        Object instance = cls.getField("instance").get(null);
                        Method get = cls.getMethod("GetRecipeResult", int.class);
                        Method add = cls.getMethod("AddRecipe", int.class, btw.modern.ItemStack.class);
                        Field map = null;
                        try {
                            map = cls.getDeclaredField("m_recipeMap");
                            map.setAccessible(true);
                        } catch (Throwable t) {
                            fail(CAMPFIRE + ".m_recipeMap", t);
                        }
                        if (instance != null) campfire = new Campfire(instance, get, add, map);
                    } catch (Throwable t) {
                        fail(CAMPFIRE, t);
                    }
                    campfireResolved = true;
                }
            }
        }
        return campfire;
    }

    /** The campfire spit's result for this input id, or null. */
    public static btw.modern.ItemStack campfireGet(int inputId) {
        Campfire c = campfire();
        if (c == null) return null;
        try {
            return (btw.modern.ItemStack) c.get().invoke(c.instance(), inputId);
        } catch (Throwable t) {
            fail(CAMPFIRE + ".GetRecipeResult", t);
            return null;
        }
    }

    /** Adds a campfire spit recipe; false when the campfire manager is unreachable. */
    public static boolean campfireAdd(int inputId, btw.modern.ItemStack output) {
        Campfire c = campfire();
        if (c == null || output == null) return false;
        try {
            c.add().invoke(c.instance(), inputId, output);
            return true;
        } catch (Throwable t) {
            fail(CAMPFIRE + ".AddRecipe", t);
            return false;
        }
    }

    /**
     * Removes a campfire spit recipe. FC has no remove: the private map is edited directly; without it the entry is
     * overwritten with null, which every reader checks (FCBlockCampfire.java:673, FCTileEntityCampfire.java:422 and
     * :458).
     */
    @SuppressWarnings("rawtypes")
    public static boolean campfireRemove(int inputId) {
        Campfire c = campfire();
        if (c == null) return false;
        try {
            if (c.map() != null) {
                Object map = c.map().get(c.instance());
                if (map instanceof Map m) {
                    m.remove(inputId);
                    return true;
                }
            }
            c.add().invoke(c.instance(), inputId, null);
            return true;
        } catch (Throwable t) {
            fail(CAMPFIRE + " remove", t);
            return false;
        }
    }

    // ---- millstone -------------------------------------------------------------------------------------

    private record Mill(Object instance, Method add, Method remove, Method result) {}

    private static volatile Mill mill;
    private static volatile boolean millResolved;

    private static Mill mill() {
        if (!millResolved) {
            synchronized (FcCraftingManagers.class) {
                if (!millResolved) {
                    try {
                        Class<?> cls = Class.forName(MILLSTONE);
                        Object instance = cls.getMethod("getInstance").invoke(null);
                        Method add = cls.getMethod("AddRecipe", btw.modern.ItemStack.class,
                                btw.modern.ItemStack.class);
                        Method remove = cls.getMethod("RemoveRecipe", btw.modern.ItemStack.class,
                                btw.modern.ItemStack.class);
                        Method result = cls.getMethod("GetCraftingResult", btw.modern.ItemStack.class);
                        if (instance != null) mill = new Mill(instance, add, remove, result);
                    } catch (Throwable t) {
                        fail(MILLSTONE, t);
                    }
                    millResolved = true;
                }
            }
        }
        return mill;
    }

    /** Adds a millstone recipe {@code in -> out} (FCCraftingManagerBulk.AddRecipe copies both stacks). */
    public static boolean millAdd(btw.modern.ItemStack out, btw.modern.ItemStack in) {
        Mill m = mill();
        if (m == null || out == null || in == null) return false;
        try {
            m.add().invoke(m.instance(), out, in);
            return true;
        } catch (Throwable t) {
            fail(MILLSTONE + ".AddRecipe", t);
            return false;
        }
    }

    /** Removes the millstone recipe {@code in -> out}; true if one was removed. */
    public static boolean millRemove(btw.modern.ItemStack out, btw.modern.ItemStack in) {
        Mill m = mill();
        if (m == null || out == null || in == null) return false;
        try {
            return Boolean.TRUE.equals(m.remove().invoke(m.instance(), out, in));
        } catch (Throwable t) {
            fail(MILLSTONE + ".RemoveRecipe", t);
            return false;
        }
    }

    /** The millstone's outputs for this single input stack, or null when no recipe takes it. */
    @SuppressWarnings("unchecked")
    public static List<btw.modern.ItemStack> millResult(btw.modern.ItemStack in) {
        Mill m = mill();
        if (m == null || in == null) return null;
        try {
            return (List<btw.modern.ItemStack>) m.result().invoke(m.instance(), in);
        } catch (Throwable t) {
            fail(MILLSTONE + ".GetCraftingResult", t);
            return null;
        }
    }

    private static void fail(String what, Throwable t) {
        if (FAILURES_LOGGED.add(what)) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : t;
            LOGGER.warn("FC recipe manager {} is unavailable ({}); treated as absent (logged once)",
                    what, cause.toString());
        }
    }
}
