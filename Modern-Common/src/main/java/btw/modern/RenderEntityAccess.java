package btw.modern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective access, for the ported 1.5.2 renderers and models, to members that the
 * Modern-Common entity stubs do not declare (EntityCreeper.getCreeperFlashIntensity,
 * EntitySlime.field_70811_b, EntityWither.isArmored, ...) and to FC client classes that
 * Modern-Common cannot compile against (FCItemArmorMod, FCClientModelCowUdder).
 *
 * <p>At runtime every btw.modern.Entity* class a renderer sees is the frozen 1.5.2 copy
 * (Forge/build.gradle removeModernCommonShadowedClasses in dev, the jar exclude list in the
 * jar), so the member exists; only the compile-time stub lacks it. This is the same
 * pattern ModelWolf/ModelBat/ModelIronGolem already use, with the lookups cached per
 * (class, member). A missing member or a failing call logs once and yields the caller's
 * default, so nothing here ever throws into a render.</p>
 */
final class RenderEntityAccess {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("BTW-RenderLiving");

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<>();
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private RenderEntityAccess() {}

    // ------------------------------------------------------------------
    // Method calls
    // ------------------------------------------------------------------

    /** No-arg float getter, e.g. EntitySpider.spiderScaleAmount(). */
    static float callFloat(Object target, String name, float def) {
        Object r = call(target, name, NO_TYPES, NO_ARGS);
        return r instanceof Float ? (Float) r : def;
    }

    /** float(float) call, e.g. EntityCreeper.getCreeperFlashIntensity(partialTicks). */
    static float callFloatF(Object target, String name, float arg, float def) {
        Object r = call(target, name, FLOAT_TYPE, new Object[] {arg});
        return r instanceof Float ? (Float) r : def;
    }

    /** float(int) call, e.g. EntityWither.func_82207_a(head). */
    static float callFloatI(Object target, String name, int arg, float def) {
        Object r = call(target, name, INT_TYPE, new Object[] {arg});
        return r instanceof Float ? (Float) r : def;
    }

    static boolean callBoolean(Object target, String name, boolean def) {
        Object r = call(target, name, NO_TYPES, NO_ARGS);
        return r instanceof Boolean ? (Boolean) r : def;
    }

    static int callInt(Object target, String name, int def) {
        Object r = call(target, name, NO_TYPES, NO_ARGS);
        return r instanceof Integer ? (Integer) r : def;
    }

    static String callString(Object target, String name, String def) {
        Object r = call(target, name, NO_TYPES, NO_ARGS);
        return r instanceof String ? (String) r : def;
    }

    // ------------------------------------------------------------------
    // Field reads
    // ------------------------------------------------------------------

    static float getFloat(Object target, String name, float def) {
        Object r = read(target, name);
        return r instanceof Float ? (Float) r : def;
    }

    static int getInt(Object target, String name, int def) {
        Object r = read(target, name);
        return r instanceof Integer ? (Integer) r : def;
    }

    // ------------------------------------------------------------------
    // FC client classes by name
    // ------------------------------------------------------------------

    /** instanceof against an FC class Modern-Common cannot import (false if it is absent). */
    static boolean isInstance(Object target, String className) {
        Class<?> cls = forName(className);
        return cls != null && cls.isInstance(target);
    }

    /** No-arg construction of an FC class Modern-Common cannot import (null on failure). */
    static Object newInstance(String className) {
        Class<?> cls = forName(className);
        if (cls == null) return null;
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            logOnce("new " + className, "could not construct " + className + ": " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Class<?>[] FLOAT_TYPE = new Class<?>[] {float.class};
    private static final Class<?>[] INT_TYPE = new Class<?>[] {int.class};
    private static final Object[] NO_ARGS = new Object[0];

    private static Object call(Object target, String name, Class<?>[] types, Object[] args) {
        if (target == null) return null;
        Class<?> cls = target.getClass();
        String key = cls.getName() + '#' + name + '/' + types.length + (types.length > 0 ? types[0].getName() : "");
        Method m = METHODS.get(key);
        if (m == null) {
            if (MISSING.contains(key)) return null;
            try {
                m = cls.getMethod(name, types);
                METHODS.put(key, m);
            } catch (Throwable t) {
                MISSING.add(key);
                logOnce(key, cls.getSimpleName() + "." + name + " not found (" + t + "); using default");
                return null;
            }
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            logOnce(key + "!invoke", cls.getSimpleName() + "." + name + " failed (" + t + "); using default");
            return null;
        }
    }

    private static Object read(Object target, String name) {
        if (target == null) return null;
        Class<?> cls = target.getClass();
        String key = cls.getName() + '.' + name;
        Field f = FIELDS.get(key);
        if (f == null) {
            if (MISSING.contains(key)) return null;
            f = findField(cls, name);
            if (f == null) {
                MISSING.add(key);
                logOnce(key, cls.getSimpleName() + "." + name + " not found; using default");
                return null;
            }
            FIELDS.put(key, f);
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            logOnce(key + "!read", cls.getSimpleName() + "." + name + " unreadable (" + t + "); using default");
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        try {
            return cls.getField(name);
        } catch (Throwable ignored) {
            // not public: walk the hierarchy for a declared one
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // keep walking
            }
        }
        return null;
    }

    private static Class<?> forName(String className) {
        Class<?> cls = CLASSES.get(className);
        if (cls != null) return cls;
        if (MISSING.contains(className)) return null;
        try {
            cls = Class.forName(className);
            CLASSES.put(className, cls);
            return cls;
        } catch (Throwable t) {
            MISSING.add(className);
            logOnce(className, "class " + className + " not loadable (" + t + ")");
            return null;
        }
    }

    private static void logOnce(String key, String message) {
        if (LOGGED.add(key)) {
            LOGGER.warn("[render] {}", message);
        }
    }
}
