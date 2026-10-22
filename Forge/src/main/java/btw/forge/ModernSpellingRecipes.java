package btw.forge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The 1.20.1 dyes that are LegacyVariants MODERN_SPELLINGs of a 4.B dye (white 15, black 0, brown 3, blue 4) keep
 * every FC colour recipe of their subtype (INTEGRATIONS #038, maintainer ruling 2026-10-20).
 *
 * <p><b>Feature:</b> 1.20.1 black, brown and blue dye in FC's colour recipes: candles, FC wool, vases and wool slabs at
 * the FC grids and in the 2x2 grid; wool, wool slabs and FC wool in the cauldron.</p>
 *
 * <p><b>Conflict with FC:</b> FC matches a recipe ingredient on (id, damage) (Modern-Common ShapedRecipes.java:66-70,
 * ShapelessRecipes.java:63-64; FCUtilsInventory.CountItemsInInventory, Common FCUtilsInventory.java:244-273). A
 * spelled dye, FC (351, d | 0x100), matches none of dye d's recipes, so it would lose the colour roles along with the
 * ink, lapis and cocoa roles the ruling takes away.</p>
 *
 * <p><b>Integration pattern:</b> C, the rule decides, no hand list. A recipe that takes dye d is a colour role when FC
 * makes the same recipe (class, shape, other ingredients, output item) with another dye colour: a colour family
 * (Client FCRecipes.java:2319-2373, :3444-3466). Each family member at a spelled subtype gets a twin taking
 * (351, d | MODERN_SPELLING). One-colour recipes (book and quill :2654-2669, chocolate milk :2307-2310, polished lapis
 * :3171-3179, chocolate :3675-3684, chicken feed :1083-1105, dog food :4009-4015) get none and stay with the 4.B item.
 * A metadata-exclusive bulk recipe already takes every spelling (FCUtilsInventory.java:262-264) and is never a
 * member.</p>
 *
 * <p>Grid twins are appended: fc_recipe_&lt;index&gt; ids (RecipeBridge.injectCraftingRecipes) and the client's
 * FCCraftingRecipes.Custom index stay put, and no other FC grid recipe takes a spelled cell, so order cannot matter.
 * Bulk twins sit right after their original, so FC's first match (FCCraftingManagerBulk.GetCraftingResult, Client
 * FCCraftingManagerBulk.java:134-147) is the same as 4.B's for the subtype. Runs once at common setup (BTWForgeMod.commonSetup)
 * on both physical sides, after FC init (RegisterEvent/BLOCKS) and before RecipeBridge (ServerStarting) and JEI read
 * the lists. Never throws: a list it cannot reach logs one WARN and stays as it was.</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_dyes: 1.20.1 dyes keep every 4.B colour recipe of their dye and no other recipe
public final class ModernSpellingRecipes {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ModernDyes");
    private static final String PKG = "net.minecraft.src.btw.crafting.";
    private static final String[] BULK = {"FCCraftingManagerCauldron", "FCCraftingManagerCauldronStoked",
            "FCCraftingManagerCrucible", "FCCraftingManagerCrucibleStoked", "FCCraftingManagerMillStone"};
    /** 1.5.2 "any metadata" marker (FCUtilsInventory.m_iIgnoreMetadata). */
    private static final int WILDCARD = 32767;

    private static boolean applied;

    private ModernSpellingRecipes() {}

    /** A recipe's colour-family key, the spelled id and subtype it takes, and the recipe. */
    private record Member(String key, int id, int subtype, Object recipe) {}

    /**
     * Common setup, both physical sides (BTWForgeMod.commonSetup): the twins exist before RecipeBridge and JEI read
     * FC's lists.
     */
    static synchronized void apply() {
        if (applied) return;
        if (!BTWLifecycle.isFcReady()) {
            LOGGER.warn("[MODERN-DYES] FC is not initialised; the 1.20.1 dyes get no FC colour recipes");
            return;
        }
        applied = true;
        try {
            Map<Integer, Set<Integer>> spelled = LegacyVariants.spelledSubtypes();
            if (spelled.isEmpty()) {
                LOGGER.warn("[MODERN-DYES] no modern dye spelling is registered (LegacyVariants); no colour twins added");
                return;
            }
            StringBuilder sum = new StringBuilder();
            sum.append(grid("crafting", btw.modern.CraftingManager.getInstance().getRecipeList(), spelled));
            sum.append(grid("anvil", anvilList(), spelled));
            for (String m : BULK) sum.append(bulk(m, spelled));
            LOGGER.info("[MODERN-DYES] colour twins for the 1.20.1 dye spellings {}:{}", spelled, sum);
        } catch (Throwable t) {
            // enqueueWork reports a throw as a mod-loading error; the dyes then only lack their FC colour recipes
            LOGGER.warn("[MODERN-DYES] colour twins not added; the 1.20.1 dyes miss FC's colour recipes", t);
        }
    }

    // ---- grids (FC crafting list, soulforge anvil list) ----------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String grid(String name, List list, Map<Integer, Set<Integer>> spelled) {
        if (list == null) return " " + name + " n/a";
        try {
            Map<String, Set<Integer>> families = new HashMap<>();
            List<Member> members = new ArrayList<>();
            for (Object o : list) {
                Member m = gridMember(o, spelled);
                if (m == null) continue;
                families.computeIfAbsent(m.key(), k -> new HashSet<>()).add(m.subtype());
                members.add(m);
            }
            List<Object> twins = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            for (Member m : members) {
                if (!spelled.get(m.id()).contains(m.subtype())) continue;
                if (families.get(m.key()).size() < 2) {
                    kept.add(outputOf(m.recipe()));
                    continue;
                }
                twins.add(gridTwin(m.recipe(), m.id(), m.subtype()));
            }
            list.addAll(twins);
            return " " + name + " +" + twins.size() + " (one colour, 4.B item only: " + kept + ")";
        } catch (Throwable t) {
            LOGGER.warn("[MODERN-DYES] {} list: no colour twins added; the 1.20.1 dyes miss its colour recipes",
                    name, t);
            return " " + name + " FAILED";
        }
    }

    /**
     * The recipe's family key, with its cells of a spelled id blanked to "D&lt;count&gt;". Null when it has no such
     * cell; when such a cell is a wildcard, already a spelling (&gt; 15) or mixed subtypes; or when it is not a plain
     * ShapedRecipes or ShapelessRecipes (a subclass computes its own result; none takes a dye in 4.B).
     */
    private static Member gridMember(Object o, Map<Integer, Set<Integer>> spelled) {
        btw.modern.ItemStack[] cells;
        String head;
        boolean ordered;
        btw.modern.ItemStack out;
        if (o != null && o.getClass() == btw.modern.ShapedRecipes.class) {
            btw.modern.ShapedRecipes r = (btw.modern.ShapedRecipes) o;
            cells = r.recipeItems;
            head = "P" + r.recipeWidth + "x" + r.recipeHeight;
            ordered = true;
            out = r.getRecipeOutput();
        } else if (o != null && o.getClass() == btw.modern.ShapelessRecipes.class) {
            btw.modern.ShapelessRecipes r = (btw.modern.ShapelessRecipes) o;
            cells = ((List<?>) r.recipeItems).toArray(new btw.modern.ItemStack[0]);
            head = "S";
            ordered = false;
            out = r.getRecipeOutput();
        } else {
            return null;
        }
        return member(head, ordered, cells, out == null ? "-" : out.itemID + "x" + out.stackSize, o, spelled);
    }

    private static Member member(String head, boolean ordered, btw.modern.ItemStack[] cells, String outKey,
                                 Object recipe, Map<Integer, Set<Integer>> spelled) {
        int id = -1, d = -1;
        List<String> parts = new ArrayList<>();
        for (btw.modern.ItemStack c : cells) {
            if (c != null && spelled.containsKey(c.itemID)) {
                int m = c.getItemDamage();
                if (m == WILDCARD || m > 15 || (id >= 0 && (c.itemID != id || m != d))) return null;
                id = c.itemID;
                d = m;
                parts.add("D" + c.stackSize);
            } else {
                parts.add(c == null ? "_" : c.itemID + ":" + c.getItemDamage() + "x" + c.stackSize);
            }
        }
        if (id < 0) return null;
        if (!ordered) Collections.sort(parts);
        return new Member(head + "|" + outKey + "|" + String.join(",", parts), id, d, recipe);
    }

    private static Object gridTwin(Object o, int id, int d) {
        int spelling = d | LegacyVariants.MODERN_SPELLING;
        if (o instanceof btw.modern.ShapedRecipes r) {
            btw.modern.ItemStack[] cells = new btw.modern.ItemStack[r.recipeItems.length];
            for (int i = 0; i < cells.length; i++) cells[i] = respell(r.recipeItems[i], id, d, spelling);
            return new btw.modern.ShapedRecipes(r.recipeWidth, r.recipeHeight, cells, copy(r.getRecipeOutput()));
        }
        btw.modern.ShapelessRecipes r = (btw.modern.ShapelessRecipes) o;
        List<btw.modern.ItemStack> items = new ArrayList<>();
        for (Object c : r.recipeItems) items.add(respell((btw.modern.ItemStack) c, id, d, spelling));
        return new btw.modern.ShapelessRecipes(copy(r.getRecipeOutput()), items);
    }

    private static btw.modern.ItemStack respell(btw.modern.ItemStack c, int id, int d, int spelling) {
        if (c == null) return null;
        btw.modern.ItemStack copy = c.copy();
        if (copy.itemID == id && copy.getItemDamage() == d) copy.setItemDamage(spelling);
        return copy;
    }

    private static btw.modern.ItemStack copy(btw.modern.ItemStack s) {
        return s == null ? null : s.copy();
    }

    private static List<?> anvilList() {
        try {
            Class<?> cls = Class.forName(PKG + "FCCraftingManagerAnvil");
            Object instance = cls.getMethod("getInstance").invoke(null);
            return instance == null ? null : (List<?>) cls.getMethod("getRecipeList").invoke(instance);
        } catch (Throwable t) {
            LOGGER.warn("[MODERN-DYES] FC anvil list unreachable: {}", t.toString());
            return null;
        }
    }

    // ---- bulk managers (cauldrons, crucibles, millstone) -------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String bulk(String simpleName, Map<Integer, Set<Integer>> spelled) {
        try {
            Class<?> mgr = Class.forName(PKG + simpleName);
            Object instance = mgr.getMethod("getInstance").invoke(null);
            Field recipesField = field(mgr, "m_recipes");                       // Client FCCraftingManagerBulk :27
            List<Object> list = (List<Object>) recipesField.get(instance);
            Class<?> rc = Class.forName(PKG + "FCCraftingManagerBulkRecipe");
            Method inputs = rc.getMethod("getCraftingIngrediantList");          // Common :59-62
            Method outputs = rc.getMethod("getCraftingOutputList");             // Common :54-57
            Field exclusive = field(rc, "m_bMetadataExclusive");                // Common :29
            Constructor<?> ctor = rc.getConstructor(List.class, List.class, boolean.class); // Common :47-52

            Map<String, Set<Integer>> families = new HashMap<>();
            Map<Object, Member> byRecipe = new IdentityHashMap<>();
            for (Object r : list) {
                // An exclusive recipe counts every damage but its own (FCUtilsInventory.java:262-264): its dye cell
                // already takes every spelling, so it is never a family member (a respelled twin would invert it).
                if (exclusive.getBoolean(r)) continue;
                List<btw.modern.ItemStack> in = (List<btw.modern.ItemStack>) inputs.invoke(r);
                List<btw.modern.ItemStack> out = (List<btw.modern.ItemStack>) outputs.invoke(r);
                if (in == null || out == null) continue;
                StringBuilder outKey = new StringBuilder("B");
                for (btw.modern.ItemStack s : out) {
                    outKey.append(';').append(s == null ? "-" : s.itemID + "x" + s.stackSize);
                }
                Member m = member("B", true, in.toArray(new btw.modern.ItemStack[0]), outKey.toString(), r, spelled);
                if (m == null) continue;
                families.computeIfAbsent(m.key(), k -> new HashSet<>()).add(m.subtype());
                byRecipe.put(r, m);
            }
            List<Object> rebuilt = new ArrayList<>(list.size());
            int twins = 0;
            List<String> kept = new ArrayList<>();
            for (Object r : list) {
                rebuilt.add(r);
                Member m = byRecipe.get(r);
                if (m == null || !spelled.get(m.id()).contains(m.subtype())) continue;
                if (families.get(m.key()).size() < 2) {
                    kept.add(firstOutput(outputs, r));
                    continue;
                }
                int spelling = m.subtype() | LegacyVariants.MODERN_SPELLING;
                List<btw.modern.ItemStack> in = new ArrayList<>();
                for (btw.modern.ItemStack s : (List<btw.modern.ItemStack>) inputs.invoke(r)) {
                    in.add(respell(s, m.id(), m.subtype(), spelling));
                }
                List<btw.modern.ItemStack> out = new ArrayList<>();
                for (btw.modern.ItemStack s : (List<btw.modern.ItemStack>) outputs.invoke(r)) out.add(copy(s));
                rebuilt.add(ctor.newInstance(out, in, false));                   // members are never exclusive
                twins++;
            }
            if (twins > 0) {
                list.clear();
                list.addAll(rebuilt);
            }
            return " " + simpleName.substring("FCCraftingManager".length()) + " +" + twins
                    + (kept.isEmpty() ? "" : " (one colour, 4.B item only: " + kept + ")");
        } catch (ClassNotFoundException e) {
            return " " + simpleName + " absent";
        } catch (Throwable t) {
            LOGGER.warn("[MODERN-DYES] {}: no colour twins added; the 1.20.1 dyes miss its colour recipes",
                    simpleName, t);
            return " " + simpleName + " FAILED";
        }
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(cls.getName() + "." + name);
    }

    private static String outputOf(Object recipe) {
        btw.modern.ItemStack o = recipe instanceof btw.modern.IRecipe r ? r.getRecipeOutput() : null;
        return o == null ? "?" : o.itemID + ":" + o.getItemDamage();
    }

    @SuppressWarnings("unchecked")
    private static String firstOutput(Method outputs, Object r) throws Exception {
        List<btw.modern.ItemStack> out = (List<btw.modern.ItemStack>) outputs.invoke(r);
        return out == null || out.isEmpty() || out.get(0) == null ? "?"
                : out.get(0).itemID + ":" + out.get(0).getItemDamage();
    }
}
