package btw.forge.mixin;

import btw.forge.LavaRules;
import net.minecraftforge.fluids.FluidInteractionRegistry;
import net.minecraftforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * FCMOD-INTEGRATION(1.20.1): hook_ownership (lava pillows; hook design P3(i)(a), D14(b))
 *
 * <p><b>Feature:</b> Forge's fluid interactions (FluidInteractionRegistry, called by LiquidBlock.onPlace and
 * neighborChanged, LiquidBlock.java:109-129). canInteract walks the flow directions and, for each, the interactions of
 * the lava's fluid type in registration order; the first match places its block and answers true
 * (FluidInteractionRegistry.java:59-77). The first registration, in the class's static initialiser, is lava+water:
 * obsidian for a source, cobblestone for any other lava cell (:81-85).</p>
 *
 * <p><b>Conflict with FC:</b> 4.B hardens only a source (obsidian) and flowing lava of meta 1-4 (FC's lava pillow);
 * lava of meta 5-7 and falling lava do not harden (vanilla/server BlockFluid.java:303-357, FCMOD :346-350). See
 * {@link LavaRules}.</p>
 *
 * <p><b>Integration pattern:</b> D. Forge's interaction stays the executor; only its predicate gains 4.B's meta gate
 * and its result becomes obsidian or the pillow ({@link LavaRules#contactRule}).</p>
 *
 * <p><b>Rebalance:</b> none beyond 4.B.</p>
 *
 * <p><b>Injection strategy:</b> {@link ModifyArg} (multi-argument form, so the handler also sees the fluid type) of
 * the first {@code addInteraction} call in {@code <clinit>} (ordinal 0, FluidInteractionRegistry.java:82; javap of
 * forge 47.3.0: the call at offset 40 is the LAVA_TYPE + WATER_TYPE registration, offset 70 the basalt one). The
 * gated interaction takes the place of Forge's in the same list slot, so first-match order is unchanged: basalt
 * (:87-91) and interactions other mods add later still run for a cell 4.B does not harden, and canInteract answering
 * false keeps LiquidBlock's flow tick (LiquidBlock.java:110-111, 125-126). {@code remap = false}: a Forge class, no
 * obfuscated names. The handler is static because its target is. If the slot ever holds another fluid's
 * registration, LavaRules returns it unchanged and WARNs. That guard cannot see lava's two registrations trade places
 * (the basalt entry is lava's too), so re-verify the ordinal-0 javap on a Forge bump.</p>
 *
 * <p><b>Alternatives considered and rejected:</b> a HEAD inject on canInteract that answers the lava+water pair
 * itself: it must either re-implement the direction x interaction loop or cancel it, and cancelling on a decline also
 * skips basalt and every later interaction in the same call (lava beside both water and blue ice over soul soil would
 * lose its basalt). Replacing entry 0 of the private INTERACTIONS map (:37) by reflection at common setup: reflective,
 * and an unsynchronized list edit during the parallel setup, where this edits the registration itself before any
 * lookup can run. A FluidPlaceBlockEvent listener that restores the lava
 * (design D14(a)): canInteract has already answered true, so the restored lava never ticks again (proof row P3d).</p>
 */
// FCMOD-INTEGRATION(1.20.1): hook_ownership: Forge's lava+water interaction hardens only what 4.B hardens (obsidian, the lava pillow), so other lava keeps flowing
@Mixin(value = FluidInteractionRegistry.class, remap = false)
public abstract class FluidInteractionRegistryMixin {

    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/fluids/FluidInteractionRegistry;addInteraction(Lnet/minecraftforge/fluids/FluidType;Lnet/minecraftforge/fluids/FluidInteractionRegistry$InteractionInformation;)V",
            ordinal = 0), index = 1)
    private static FluidInteractionRegistry.InteractionInformation btw$fourBLavaContact(
            FluidType source, FluidInteractionRegistry.InteractionInformation forge) {
        return LavaRules.contactRule(source, forge);
    }
}
