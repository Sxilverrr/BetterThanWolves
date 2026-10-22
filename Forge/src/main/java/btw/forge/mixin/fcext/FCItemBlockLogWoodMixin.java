package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M4, the log item in crafting)
 *
 * <p><b>Feature:</b> the by-products of chopping a modern log at the work stump: FC's sawdust and the log's own
 * bark, as for oak.</p>
 *
 * <p><b>Conflict with FC:</b> FCItemBlockLog.OnUsedInCrafting ejects bark of the log's item damage in the stick branch
 * (Common FCItemBlockLog.java:47), which for log2 is the species 0-3 (cherry would pay jungle bark). The planks
 * branch (:39) reads the planks' damage, which FCRecipesLogChoppingWoodMixin already makes 5-8.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's log item runs unchanged on log2's item (itemsList[165]).</p>
 *
 * <p><b>Rebalance:</b> none.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; javap of
 * Forge/build/classes/java/fc): {@code @ModifyArg} index 2 (damage) of the fourth {@code ItemStack.<init>(Item, int,
 * int)} of {@code OnUsedInCrafting(int, EntityPlayer, ItemStack)} (offset 160, ordinal 3: 0 and 2 are sawdust, 1 is
 * the planks branch's bark), keyed on log2's item id.</p>
 *
 * <p><b>Alternative considered and rejected:</b> {@code @ModifyVariable} of the method's damage argument at HEAD
 * (would also be what any later FC read of it sees).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: chopping a log2 log into sticks pays its own wood type's bark
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.item.FCItemBlockLog", remap = false)
public abstract class FCItemBlockLogWoodMixin {

    /** The stick branch's bark (Common FCItemBlockLog.java:47): log2's wood type. */
    @ModifyArg(method = "OnUsedInCrafting(ILbtw/modern/EntityPlayer;Lbtw/modern/ItemStack;)V",
            at = @At(value = "INVOKE", target = "Lbtw/modern/ItemStack;<init>(Lbtw/modern/Item;II)V", ordinal = 3,
                    remap = false),
            index = 2, require = 1, remap = false)
    private int btw$stickBranchBark(int damage) {
        return ((btw.modern.Item) (Object) this).itemID == ModernWoods.LOG2_ID && ModernWoods.isBuilt()
                ? ModernWoods.woodType(damage) : damage;
    }
}
