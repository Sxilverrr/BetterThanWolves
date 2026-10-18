package btw.forge.compat;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The companion plugin of {@code betterthanwolves.mixins.json} (INTEGRATIONS #020, mod-compat foundation): a mixin on
 * an optional mod's class is prepared only when that mod is installed.
 *
 * <ul>
 *   <li><b>Modern feature:</b> the {@code @Pseudo} mixins on optional mods' classes, under
 *       {@code btw.forge.mixin.compat.<mod>}: {@code sereneseasons.SeasonalBonemealVetoMixin} (#028) and
 *       {@code lso.LsoRegenGateMixin} (#031).</li>
 *   <li><b>Conflict with FC:</b> none in play. Without the mod, Mixin 0.8.5 still looks the target up while it
 *       prepares the config ({@code MixinInfo.getTargetClass} calls {@code ClassInfo.forName} before it checks
 *       {@code @Pseudo}), and {@code ClassInfo.forName} logs {@code WARN Error loading class: <target>
 *       (java.lang.ClassNotFoundException: ...)} on every launch without that mod. The mixin is inert either way;
 *       only the log names a mod the player never installed.</li>
 *   <li><b>Pattern:</b> {@link #shouldApplyMixin} answers false for a compat mixin whose mod file is absent from
 *       FML's {@link LoadingModList}, which the mod scan fills before Mixin selects any config ({@code ModList} does
 *       not exist yet, so {@code BTWCompat.isLoaded} cannot answer here). Mixin asks before it looks the target up
 *       ({@code MixinInfo.readDeclaredTargets}), so the mixin gets no target and nothing is logged. Every other
 *       mixin, and any answer that cannot be made, is true: the config then behaves as it did without a plugin.</li>
 *   <li><b>Alt rejected:</b> accepting the WARN in the no-mod proofs (players would still see a class-load error
 *       naming a mod they never installed); one mixin config per optional mod (more manifest and build-guard
 *       surface for the same effect).</li>
 * </ul>
 *
 * <p>Mixin loads this class itself while it selects the config, before any Minecraft or BTW class: it names only
 * Mixin, ASM and fmlloader types, the mod ids are literals (the same as BTWCompat's), and it has no lambda or string
 * concatenation.</p>
 */
// FCMOD-INTEGRATION(1.20.1): mod-compat: a mixin on an optional mod's class is prepared only when that mod is installed, so plain BTW logs no class-load error naming it
public final class CompatMixinPlugin implements IMixinConfigPlugin {

    /** The package of the mixins on optional mods' classes; the next package segment names the mod. */
    private static final String COMPAT_MIXINS = "btw.forge.mixin.compat.";

    @Override
    public void onLoad(String mixinPackage) {
    }

    /** Null keeps the config's own refmap. */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /** False only for a compat mixin whose mod is not installed. */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String modId = modIdOf(mixinClassName);
        if (modId == null) return true;
        try {
            LoadingModList mods = LoadingModList.get();
            return mods == null || mods.getModFileById(modId) != null;
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** The mod id a compat mixin belongs to, or null for any other mixin (always applied). */
    static String modIdOf(String mixinClassName) {
        if (!mixinClassName.startsWith(COMPAT_MIXINS)) return null;
        String rest = mixinClassName.substring(COMPAT_MIXINS.length());
        if (rest.startsWith("sereneseasons.")) return "sereneseasons";
        if (rest.startsWith("lso.")) return "legendarysurvivaloverhaul";
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    /** No extra mixins. */
    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
