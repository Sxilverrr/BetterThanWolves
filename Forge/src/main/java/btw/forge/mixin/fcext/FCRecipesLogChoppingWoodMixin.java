package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M5, log chopping)
 *
 * <p><b>Feature:</b> chopping a modern log with an axe at the work stump (4.B's only route from log to planks): 2
 * planks of its own wood with an iron or better axe, 2 sticks with a stone, wooden or gold one, as for oak.</p>
 *
 * <p><b>Conflict with FC:</b> FCRecipesLogChopping accepts only id 17 and blood wood (Common
 * FCRecipesLogChopping.java:178-189), and its planks take the log's item damage as their subtype (:130), which for
 * log2 is the species 0-3 (cherry would chop into jungle planks).</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's chopping recipe, with log2 as a log of wood types 5-8.</p>
 *
 * <p><b>Rebalance:</b> none.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; javap of
 * Forge/build/classes/java/fc): the private {@code IsLog(ItemStack)} HEAD, cancellable, accepts log2's item id; a
 * {@code @Redirect} of the method's only {@code ItemStack.getItemDamage()} in {@code getCraftingResult} (offset 176)
 * answers log2's wood type, the redirect being the one form that sees which log the damage belongs to.</p>
 *
 * <p><b>Alternative considered and rejected:</b> a second recipe of the port's for log2 (FC's recipe list would hold
 * two chopping recipes, and JEI's FCCustomCraftingDisplays would show both).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: FC's work-stump log chopping accepts log2 and gives planks of its wood type 5-8
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.crafting.FCRecipesLogChopping", remap = false)
public abstract class FCRecipesLogChoppingWoodMixin {

    /** IsLog (Common FCRecipesLogChopping.java:178-189): log2 is a log. */
    @Inject(method = "IsLog(Lbtw/modern/ItemStack;)Z", at = @At("HEAD"), cancellable = true, require = 1,
            remap = false)
    private void btw$log2IsLog(btw.modern.ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack != null && stack.itemID == ModernWoods.LOG2_ID && ModernWoods.isBuilt()) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    /** The planks' subtype (:130): log2's wood type 5-8, FC's damage otherwise. */
    @Redirect(method = "getCraftingResult(Lbtw/modern/InventoryCrafting;)Lbtw/modern/ItemStack;",
            at = @At(value = "INVOKE", target = "Lbtw/modern/ItemStack;getItemDamage()I", remap = false),
            require = 1, remap = false)
    private int btw$planksWoodType(btw.modern.ItemStack log) {
        int damage = log.getItemDamage();
        return log.itemID == ModernWoods.LOG2_ID ? ModernWoods.woodType(damage) : damage;
    }
}
