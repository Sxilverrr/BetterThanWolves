import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Member-level link audit for the BTW Forge port.
 *
 * Rebuilds the EFFECTIVE runtime class set using the single precedence rule shared
 * by the dist jar and dev runs (Forge/build.gradle "Class precedence"): a btw.modern
 * class present in both Modern-Common and the relocated fc set resolves to
 * Modern-Common unless it is shadow-listed (the excludes of the one copySpec
 * modernCommonRuntime, which the jar and syncDevModernCommon share). Then only the fc
 * copy exists. In the jar the Modern-Common child spec is walked before the fc child
 * spec under EXCLUDE; in dev securejarhandler searches mods{} sources in reverse order
 * and the last one, devModernCommon, is Modern-Common's output minus the list. Forge
 * main (btw/forge) collides with nothing. Every method/field/ctor reference is then
 * resolved against the winning class. Unresolved refs = latent
 * NoSuchMethodError / NoSuchFieldError / NoClassDefFoundError.
 *
 * DEV FILTER CHECK compares that dev view (Forge/build/classes/java/devModernCommon,
 * written only by syncDevModernCommon) with Modern-Common's output: leaked (a listed or
 * btw/api class in the view), missing (an unlisted class absent from it), stale (bytes
 * differ from Modern-Common's) and extra (no Modern-Common copy). All four must be empty.
 *
 * It also lists FOREIGN net.minecraft refs: any class name (owner, supertype, class
 * constant, member/indy descriptor) in a winning fc or Modern-Common class that lies in
 * net/minecraft/** outside net/minecraft/src/**, or in net/minecraftforge/** or
 * com/mojang/**. At runtime such a name binds to the 1.20.1 class of that name, whose
 * members differ (e.g. frozen Entity's invokestatic net/minecraft/server/MinecraftServer
 * .getServer before the remapFcCode relocate), so it must be relocated to btw.modern.
 * Forge main (btw/forge) links 1.20.1 legitimately and is exempt.
 *
 * If a built jar is present (-Dbtw.jar=<jar>, else the newest Forge/build/libs/*.jar),
 * the audit also checks that the jar really ships the modelled winner of every
 * collision (the check that would have caught the 2026-10-10 jar-only fc-wins drift).
 *
 * Finally it reports whether the fc dir carries the build's below-y=0 pathing patch
 * (Forge/build.gradle extendFcPathingBuildRange); NOT APPLIED means a stale fc dir.
 *
 * Run from the repo root: java tools/LinkAudit.java   (or -Dbtw.root=<repo>)
 */
public class LinkAudit {
    static final String ROOT = resolveRoot();
    static final String FC_DIR = ROOT + "/Forge/build/classes/java/fc";
    static final String MC_DIR = ROOT + "/Modern-Common/build/classes/java/main";
    /** The dev runs' view of Modern-Common: MC_DIR minus the shadow list (Forge/build.gradle syncDevModernCommon). */
    static final String DEV_MC_DIR = ROOT + "/Forge/build/classes/java/devModernCommon";
    static final String FORGE_DIR = ROOT + "/Forge/build/classes/java/main";
    static final String API_DIR = ROOT + "/Api/build/classes/java/main";
    static final String GRADLE = ROOT + "/Forge/build.gradle";

    /** -Dbtw.root=<repo>, else the nearest ancestor of the working dir holding Forge/build.gradle. */
    static String resolveRoot() {
        String prop = System.getProperty("btw.root");
        if (prop != null && !prop.isEmpty()) return prop.replace('\\', '/');
        for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("Forge").resolve("build.gradle")))
                return p.toString().replace('\\', '/');
        }
        throw new IllegalStateException("Run inside the BetterThanWolves repo or pass -Dbtw.root=<repo>");
    }

    static final Set<String> OBJECT_METHODS = new HashSet<>(Arrays.asList(
        "equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;",
        "getClass()Ljava/lang/Class;", "clone()Ljava/lang/Object;",
        "wait()V", "wait(J)V", "wait(JI)V", "notify()V", "notifyAll()V",
        "finalize()V", "<init>()V"));

    static class ClassInfo {
        String name, superName, source, sha;
        List<String> interfaces = new ArrayList<>();
        Set<String> methods = new HashSet<>();  // name+desc
        Set<String> fields = new HashSet<>();   // name+desc
        List<String[]> refs = new ArrayList<>(); // [kind, owner, name, desc]
        Set<String> typeRefs = new TreeSet<>();  // every class name the bytecode can link
    }

    static Map<String, ClassInfo> runtime = new TreeMap<>();
    static Map<String, ClassInfo> api = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        // ---- parse the shadow list from build.gradle ----
        // One list since the dev-run fix: the literal excludes of the copySpec modernCommonRuntime, which the jar
        // (`with modernCommonRuntime`) and syncDevModernCommon (the dev view) share, so dev and jar cannot drift.
        String gradle = new String(Files.readAllBytes(Paths.get(GRADLE)), StandardCharsets.UTF_8);
        Set<String> jarExcludes = new TreeSet<>();
        Matcher m = Pattern.compile("exclude 'btw/modern/([A-Za-z0-9_$]+)\\.class'").matcher(gradle);
        while (m.find()) jarExcludes.add(m.group(1));
        System.out.println("== Shadow list (modernCommonRuntime: jar and dev view), " + jarExcludes.size() + " classes ==");
        System.out.println(jarExcludes);

        // ---- build effective runtime set, mirroring the jar task and dev runs ----
        Map<String, ClassInfo> fc = new TreeMap<>();
        loadDir(FC_DIR, "fc", fc, null);
        runtime.putAll(fc);
        // Modern-Common overwrites fc unless shadow-listed: same winner as the jar (MC child spec before fc child, EXCLUDE) and dev (reverse search order over devModernCommon, MC minus the list)
        Map<String, ClassInfo> mc = new TreeMap<>();
        loadDir(MC_DIR, "modern-common", mc, null);
        List<String> orphanExcludes = new ArrayList<>();
        for (ClassInfo ci : mc.values()) {
            if (ci.name.startsWith("btw/api/")) continue;
            String simple = ci.name.startsWith("btw/modern/") ? ci.name.substring("btw/modern/".length()) : null;
            if (simple != null && jarExcludes.contains(simple)) {
                if (!runtime.containsKey(ci.name)) orphanExcludes.add(ci.name);
                continue; // fc version wins
            }
            runtime.put(ci.name, ci);
        }
        checkDevFilter(mc, jarExcludes);
        loadDir(FORGE_DIR, "forge", runtime, null);
        loadDir(API_DIR, "api(dev-only)", api, null);
        System.out.println("\nruntime classes: " + runtime.size() + " (api dev-only: " + api.size() + ")");
        if (!orphanExcludes.isEmpty())
            System.out.println("-- shadow-listed Modern-Common class with no fc copy (ships nowhere; a defect only "
                + "if it also appears under MISSING CLASSES): " + orphanExcludes);

        // ---- resolve every reference ----
        // key: kind|owner|name|desc  -> referencing classes
        Map<String, Set<String>> missing = new TreeMap<>();
        Map<String, Set<String>> inconclusive = new TreeMap<>();
        Map<String, Set<String>> apiRefs = new TreeMap<>();
        Set<String> missingClasses = new TreeSet<>();

        // Supertype resolution: a class whose superclass or an implemented interface is an
        // "ours" class missing from the runtime set fails to LINK (NoClassDefFoundError at
        // class load) — e.g. `EntityMinecartHopper implements btw.modern.Hopper` when Hopper
        // isn't shipped. This is a class-level ref not present in the method/field constant
        // pool, so it must be checked separately.
        for (ClassInfo ci : runtime.values()) {
            java.util.List<String> supers = new java.util.ArrayList<>(ci.interfaces);
            if (ci.superName != null) supers.add(ci.superName);
            for (String sup : supers) {
                boolean ours = sup.startsWith("btw/modern/") || sup.startsWith("net/minecraft/src/")
                        || sup.startsWith("btw/forge/") || sup.startsWith("btw/api/");
                if (ours && !sup.startsWith("btw/api/") && !runtime.containsKey(sup)) {
                    missingClasses.add(sup + "  (SUPERTYPE of " + ci.name + ")");
                }
            }
        }

        for (ClassInfo ci : runtime.values()) {
            for (String[] r : ci.refs) {
                String kind = r[0], owner = r[1], name = r[2], desc = r[3];
                if (owner.startsWith("[")) continue;
                boolean ours = owner.startsWith("btw/modern/") || owner.startsWith("net/minecraft/src/")
                        || owner.startsWith("btw/forge/") || owner.startsWith("btw/api/");
                if (!ours) continue;
                if (owner.startsWith("btw/api/")) {
                    apiRefs.computeIfAbsent(owner + " " + name + desc, k -> new TreeSet<>()).add(ci.name);
                    continue;
                }
                if (!runtime.containsKey(owner)) {
                    missingClasses.add(owner + "  (referenced by " + ci.name + ")");
                    continue;
                }
                int res = (kind.equals("F")) ? resolveField(owner, name, desc)
                        : name.equals("<init>") ? resolveCtor(owner, name, desc)
                        : resolveMethod(owner, name, desc);
                String key = kindLabel(kind, name) + " " + owner + "." + name + " " + desc
                        + "   [winner: " + runtime.get(owner).source + "]";
                if (res == MISSING) missing.computeIfAbsent(key, k -> new TreeSet<>()).add(ci.name);
                else if (res == INCONCLUSIVE) inconclusive.computeIfAbsent(key, k -> new TreeSet<>()).add(ci.name);
            }
        }

        // Foreign names: fc / Modern-Common must only link btw.modern, net.minecraft.src.btw and
        // the JDK/LWJGL. A 1.20.1 or 1.5.2-server/client name resolves to the 1.20.1 class.
        Map<String, Set<String>> foreign = new TreeMap<>();
        for (ClassInfo ci : runtime.values()) {
            if (!ci.source.equals("fc") && !ci.source.equals("modern-common")) continue;
            for (String t : ci.typeRefs)
                if (isForeign(t)) foreign.computeIfAbsent(t, k -> new TreeSet<>()).add(ci.name);
        }

        System.out.println("\n== MISSING CLASSES (NoClassDefFoundError) ==");
        missingClasses.forEach(s -> System.out.println("  " + s));
        System.out.println("\n== FOREIGN net.minecraft REFS (fc/Modern-Common must not link 1.20.1/1.5.2-server names;"
            + " relocate them in remapFcCode) == count=" + foreign.size());
        dump(foreign);
        System.out.println("\n== MISSING MEMBERS (NoSuchMethodError / NoSuchFieldError) == count=" + missing.size());
        dump(missing);
        System.out.println("\n== REFS TO btw.api (absent from PROD jar; dev-only classpath) == count=" + apiRefs.size());
        dump(apiRefs);
        System.out.println("\n== INCONCLUSIVE (hierarchy leaves audited set) == count=" + inconclusive.size());
        dump(inconclusive);

        // extendFcPathingBuildRange rewrites PathFinder.getSafePoint's `par3 > 0` drop-scan bound to
        // World.getFcMinBuildHeight(); without it FC pathing walks on air below y=0.
        ClassInfo pathFinder = fc.get("btw/modern/PathFinder");
        boolean pathingPatched = pathFinder != null && pathFinder.refs.stream().anyMatch(r ->
            r[1].equals("btw/modern/World") && r[2].equals("getFcMinBuildHeight") && r[3].equals("()I"));
        System.out.println("\n== FC BYTECODE PATCHES ==");
        System.out.println("extendFcPathingBuildRange (PathFinder -> World.getFcMinBuildHeight): "
            + (pathFinder == null ? "PathFinder absent from fc" : pathingPatched ? "applied" : "NOT APPLIED (stale fc dir?)"));

        checkJar(fc, mc);
    }

    // ---------------- dev view cross-check ----------------
    /**
     * Dev runs load DEV_MC_DIR (syncDevModernCommon's copy of Modern-Common minus the shadow list) instead of MC_DIR.
     * Every unlisted, non-btw/api Modern-Common class must be there with identical bytes, and nothing else.
     */
    static void checkDevFilter(Map<String, ClassInfo> mc, Set<String> shadowList) throws IOException {
        if (!Files.isDirectory(Paths.get(DEV_MC_DIR))) {
            System.out.println("\n== DEV FILTER CHECK == skipped: run :Forge:syncDevModernCommon (no " + DEV_MC_DIR + ")");
            return;
        }
        Map<String, ClassInfo> dev = new TreeMap<>();
        loadDir(DEV_MC_DIR, "dev-modern-common", dev, null);
        Set<String> leaked = new TreeSet<>(), missing = new TreeSet<>(), stale = new TreeSet<>(), extra = new TreeSet<>();
        for (ClassInfo ci : dev.values()) {
            ClassInfo src = mc.get(ci.name);
            if (!expectedInDevView(ci.name, shadowList)) leaked.add(ci.name);
            else if (src == null) extra.add(ci.name);
            else if (!src.sha.equals(ci.sha)) stale.add(ci.name);
        }
        for (ClassInfo ci : mc.values())
            if (expectedInDevView(ci.name, shadowList) && !dev.containsKey(ci.name)) missing.add(ci.name);
        System.out.println("\n== DEV FILTER CHECK (" + DEV_MC_DIR.substring(ROOT.length() + 1) + ": " + dev.size()
            + " classes; Modern-Common " + mc.size() + ", shadow list " + shadowList.size() + ") ==");
        System.out.println("leaked (shadow-listed or btw/api class in the dev view, stub shadows FC in DEV): " + leaked);
        System.out.println("missing (unlisted Modern-Common class absent from the dev view): " + missing);
        System.out.println("stale (bytes differ from Modern-Common's; rerun :Forge:syncDevModernCommon): " + stale);
        System.out.println("extra (no Modern-Common copy): " + extra);
    }

    /** True for a Modern-Common class the dev view (and the jar) must carry: not btw/api, not shadow-listed. */
    static boolean expectedInDevView(String name, Set<String> shadowList) {
        if (name.startsWith("btw/api/")) return false;
        return !(name.startsWith("btw/modern/") && shadowList.contains(name.substring("btw/modern/".length())));
    }

    // ---------------- built-jar cross-check ----------------
    /** -Dbtw.jar=<jar>, else the newest *.jar in Forge/build/libs (null if none). */
    static Path findJar() throws IOException {
        String prop = System.getProperty("btw.jar");
        if (prop != null && !prop.isEmpty()) return Paths.get(prop);
        Path libs = Paths.get(ROOT, "Forge", "build", "libs");
        if (!Files.isDirectory(libs)) return null;
        Path newest = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libs, "*.jar")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.endsWith("-sources.jar") || n.endsWith("-javadoc.jar")) continue;
                if (newest == null || Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(newest)) > 0)
                    newest = p;
            }
        }
        return newest;
    }

    /**
     * For every class the model resolves and that exists in BOTH fc and Modern-Common (a
     * collision), report which copy the built jar actually ships. Identified by bytes (btw.modern
     * classes reference no 1.20.1 names, so reobf leaves them byte-identical), else by member set.
     * A jar copy matching neither means the jar is stale relative to the class dirs: rebuild it.
     */
    static void checkJar(Map<String, ClassInfo> fc, Map<String, ClassInfo> mc) throws IOException {
        Path jarPath = findJar();
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            System.out.println("\n== JAR PRECEDENCE CHECK == skipped (no jar: run :Forge:jar or pass -Dbtw.jar=<jar>)");
            return;
        }
        List<String> drift = new ArrayList<>(), stale = new ArrayList<>(), absent = new ArrayList<>();
        int collisions = 0;
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            for (ClassInfo want : runtime.values()) {
                if (!want.name.startsWith("btw/modern/") && !want.name.startsWith("net/minecraft/src/")) continue;
                ZipEntry e = zip.getEntry(want.name + ".class");
                if (e == null) { absent.add(want.name); continue; }
                ClassInfo f = fc.get(want.name), m = mc.get(want.name);
                if (f == null || m == null) continue; // no collision, nothing to choose
                collisions++;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) { bytes = in.readAllBytes(); }
                String got = classify(bytes, f, m);
                if (got == null) stale.add(want.name);
                else if (!got.equals(want.source)) drift.add(want.name + "  jar=" + got + "  expected=" + want.source);
            }
        }
        System.out.println("\n== JAR PRECEDENCE CHECK (" + jarPath.getFileName() + ", " + collisions
            + " fc/Modern-Common collisions) ==");
        System.out.println("winner differs from dev/model (precedence drift): " + drift.size());
        drift.forEach(s -> System.out.println("  " + s));
        System.out.println("jar copy matches neither class dir (stale jar? rebuild): " + stale.size());
        stale.forEach(s -> System.out.println("  " + s));
        System.out.println("modelled btw.modern / net.minecraft.src class absent from jar: " + absent.size());
        absent.stream().limit(25).forEach(s -> System.out.println("  " + s));
        if (absent.size() > 25) System.out.println("  ... +" + (absent.size() - 25) + " more");
    }

    /** "fc" / "modern-common" for the copy the jar entry matches, null if neither (or ambiguous). */
    static String classify(byte[] bytes, ClassInfo f, ClassInfo m) {
        String sha = sha1(bytes);
        if (sha.equals(f.sha)) return f.source;
        if (sha.equals(m.sha)) return m.source;
        ClassInfo j = parse(bytes);
        boolean likeF = j.methods.equals(f.methods) && j.fields.equals(f.fields);
        boolean likeM = j.methods.equals(m.methods) && j.fields.equals(m.fields);
        return likeF == likeM ? null : likeF ? f.source : m.source;
    }

    static String sha1(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-1").digest(b)) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void dump(Map<String, Set<String>> map) {
        for (Map.Entry<String, Set<String>> e : map.entrySet()) {
            System.out.println("  " + e.getKey());
            int shown = 0;
            for (String c : e.getValue()) {
                if (shown++ == 5) { System.out.println("      ... +" + (e.getValue().size() - 5) + " more"); break; }
                System.out.println("      <- " + c);
            }
        }
    }

    static boolean isForeign(String t) {
        return (t.startsWith("net/minecraft/") && !t.startsWith("net/minecraft/src/"))
            || t.startsWith("net/minecraftforge/") || t.startsWith("com/mojang/");
    }

    static final Pattern DESC_TYPE = Pattern.compile("L([^;]+);");

    /** Adds every L...; class name in a field/method descriptor (or array class name). */
    static void addDescTypes(Set<String> into, String desc) {
        if (desc == null) return;
        Matcher dm = DESC_TYPE.matcher(desc);
        while (dm.find()) into.add(dm.group(1));
    }

    static String kindLabel(String kind, String name) {
        return kind.equals("F") ? "FIELD " : name.equals("<init>") ? "CTOR  " : "METHOD";
    }

    static final int OK = 0, MISSING = 1, INCONCLUSIVE = 2;

    static int resolveCtor(String owner, String name, String desc) {
        ClassInfo ci = runtime.get(owner);
        return ci.methods.contains(name + desc) ? OK : MISSING;
    }

    static int resolveMethod(String owner, String name, String desc) { return walk(owner, name + desc, true); }
    static int resolveField(String owner, String name, String desc)  { return walk(owner, name + desc, false); }

    static int walk(String owner, String sig, boolean method) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(owner);
        boolean external = false;
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (c == null || !seen.add(c)) continue;
            ClassInfo ci = runtime.get(c);
            if (ci == null) {
                if (c.equals("java/lang/Object")) { if (method && OBJECT_METHODS.contains(sig)) return OK; }
                else external = true;
                continue;
            }
            if (method ? ci.methods.contains(sig) : ci.fields.contains(sig)) return OK;
            if (ci.superName != null) queue.add(ci.superName);
            queue.addAll(ci.interfaces);
        }
        return external ? INCONCLUSIVE : MISSING;
    }

    // ---------------- class file parsing ----------------
    static void loadDir(String dir, String source, Map<String, ClassInfo> into, String prefixFilter) throws IOException {
        Path base = Paths.get(dir);
        if (!Files.isDirectory(base)) { System.out.println("!! missing dir: " + dir); return; }
        Files.walk(base).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
            try {
                byte[] bytes = Files.readAllBytes(p);
                ClassInfo ci = parse(bytes);
                ci.source = source;
                ci.sha = sha1(bytes);
                into.put(ci.name, ci);
            } catch (Exception e) {
                System.out.println("!! parse error " + p + ": " + e);
            }
        });
    }

    static int u2(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    static long u4(byte[] b, int o) { return ((long) u2(b, o) << 16) | u2(b, o + 2); }

    static ClassInfo parse(byte[] b) {
        int off = 8;
        int cpCount = u2(b, off); off += 2;
        Object[] cp = new Object[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = b[off++] & 0xff;
            switch (tag) {
                case 1: { int len = u2(b, off); off += 2;
                          cp[i] = new String(b, off, len, StandardCharsets.UTF_8); off += len; break; }
                case 7: case 8: case 16: case 19: case 20:
                    // Class -> {nameIdx}; MethodType -> {-16, descIdx} (indy/lambda types)
                    cp[i] = (tag == 7) ? new int[]{u2(b, off)} : (tag == 16) ? new int[]{-16, u2(b, off)} : null;
                    off += 2; break;
                case 15: off += 3; break;
                case 3: case 4: off += 4; break;
                case 5: case 6: off += 8; i++; break;
                case 9: case 10: case 11: cp[i] = new int[]{tag, u2(b, off), u2(b, off + 2)}; off += 4; break;
                case 12: cp[i] = new int[]{-1, u2(b, off), u2(b, off + 2)}; off += 4; break;
                case 17: case 18: off += 4; break;
                default: throw new RuntimeException("cp tag " + tag);
            }
        }
        ClassInfo ci = new ClassInfo();
        off += 2; // access
        ci.name = className(cp, u2(b, off)); off += 2;
        int sup = u2(b, off); off += 2;
        ci.superName = sup == 0 ? null : className(cp, sup);
        if (ci.superName != null) ci.typeRefs.add(ci.superName);
        int nIf = u2(b, off); off += 2;
        for (int i = 0; i < nIf; i++) { ci.interfaces.add(className(cp, u2(b, off))); off += 2; }
        ci.typeRefs.addAll(ci.interfaces);
        for (int pass = 0; pass < 2; pass++) { // fields then methods
            int n = u2(b, off); off += 2;
            for (int i = 0; i < n; i++) {
                String name = (String) cp[u2(b, off + 2)];
                String desc = (String) cp[u2(b, off + 4)];
                off += 6;
                int attrs = u2(b, off); off += 2;
                for (int a = 0; a < attrs; a++) { off += 2; long len = u4(b, off); off += 4 + (int) len; }
                if (pass == 0) ci.fields.add(name + desc); else ci.methods.add(name + desc);
                addDescTypes(ci.typeRefs, desc);
            }
        }
        // collect refs from constant pool
        for (int i = 1; i < cpCount; i++) {
            if (cp[i] instanceof int[]) {
                int[] e = (int[]) cp[i];
                if (e.length == 1) { // Class: new/checkcast/instanceof/anewarray/ldc/member owner
                    String cn = (String) cp[e[0]];
                    if (cn.startsWith("[")) addDescTypes(ci.typeRefs, cn); else ci.typeRefs.add(cn);
                } else if (e.length == 2) { // MethodType
                    addDescTypes(ci.typeRefs, (String) cp[e[1]]);
                } else if (e[0] == -1) { // NameAndType: every member-ref and indy descriptor
                    addDescTypes(ci.typeRefs, (String) cp[e[2]]);
                }
                if (e.length == 3 && e[0] >= 9 && e[0] <= 11) {
                    String owner = className(cp, e[1]);
                    int[] nat = (int[]) cp[e[2]];
                    String name = (String) cp[nat[1]];
                    String desc = (String) cp[nat[2]];
                    ci.refs.add(new String[]{e[0] == 9 ? "F" : "M", owner, name, desc});
                }
            }
        }
        return ci;
    }

    static String className(Object[] cp, int idx) {
        int[] c = (int[]) cp[idx];
        return (String) cp[c[0]];
    }
}
