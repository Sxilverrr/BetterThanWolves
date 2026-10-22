package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M1, the log)
 *
 * <p><b>Feature:</b> acacia, dark oak, mangrove and cherry logs as FC logs of their own wood types 5-8
 * (btw.forge.ModernWoods): log2, a plain FCBlockLog at legacy id 165.</p>
 *
 * <p><b>Conflict with FC:</b> FCBlockLog turns its metadata into a wood type as {@code meta & 3} (the work stump it
 * makes, Client FCBlockLog.java:92; the bark it ejects, :112; the bark of a bad break, :128; its fuel, :168-171), which
 * for log2 is the species 0-3, so an acacia log would give oak bark and an oak work stump; and its stump test reads
 * {@code Block.wood.blockID} (17) at the cell (:187-202), so a log2 stump was never a stump (the stump remover,
 * GetIsProblemToRemove, incineration, smouldering).</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's log code stays whole and runs on log2; the wood type it reads is
 * log2's ({@code 5 + (meta & 3)}, ModernWoods.woodType).</p>
 *
 * <p><b>Rebalance:</b> none for id 17 (every patch is keyed on log2's id). log2 burns at its wood type's planks fuel
 * x4 (ModernWoods.planksBurnTime through FCBlockPlanksWoodMixin).</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; descriptors
 * and ordinals from javap of Forge/build/classes/java/fc): {@code @ModifyArg} of ConvertBlock's first
 * {@code World.setBlockAndMetadataWithNotify(IIIII)Z} (offset 66, the work stump; ordinal 1 at offset 119 is the
 * chewed log) index 4, and of its only {@code ItemStack.<init>(Item, int, int)} (offset 148) index 2;
 * {@code @ModifyArg} of DropComponentItemsOnBadBreak's second {@code DropItemsIndividualy} (offset 39, constant-pool
 * owner FCBlockLog; ordinal 0 is the sawdust) index 6; {@code GetIsStump(IBlockAccess, int, int, int)} HEAD,
 * cancellable; {@code @ModifyArg} of GetFurnaceBurnTime's {@code FCBlockPlanks.GetFurnaceBurnTimeByWoodType(I)I};
 * {@code getIcon(II)} HEAD, cancellable (client cosmetic: the 1.20.1 log textures by name, FCBakedModel.lookupSprite
 * step 3).</p>
 *
 * <p><b>Alternative considered and rejected:</b> a port subclass of FCBlockLog (puts FC subclasses in the bridge,
 * against the documented layering); a build-time ASM pass (every target is a plain, non-inlined method).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: log2 (165) makes its own wood type's work stump, bark and fuel, and its stump test reads 165
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockLog", remap = false)
public abstract class FCBlockLogWoodMixin {

    /** True for log2; FC's own log (17) and every other FCBlockLog are untouched. */
    @Unique
    private boolean btw$isLog2() {
        return ((btw.modern.Block) (Object) this).blockID == ModernWoods.LOG2_ID;
    }

    /** (a) ConvertBlock's work stump (Client FCBlockLog.java:92): META = log2's wood type 5-8. */
    @ModifyArg(method = "ConvertBlock(Lbtw/modern/ItemStack;Lbtw/modern/World;IIII)Z",
            at = @At(value = "INVOKE", target = "Lbtw/modern/World;setBlockAndMetadataWithNotify(IIIII)Z",
                    ordinal = 0, remap = false),
            index = 4, require = 1, remap = false)
    private int btw$workStumpWoodType(int metadata) {
        return btw$isLog2() ? ModernWoods.woodType(metadata) : metadata;
    }

    /** (b) ConvertBlock's bark (:112): log2's wood type. */
    @ModifyArg(method = "ConvertBlock(Lbtw/modern/ItemStack;Lbtw/modern/World;IIII)Z",
            at = @At(value = "INVOKE", target = "Lbtw/modern/ItemStack;<init>(Lbtw/modern/Item;II)V", remap = false),
            index = 2, require = 1, remap = false)
    private int btw$chewedBarkWoodType(int damage) {
        return btw$isLog2() ? ModernWoods.woodType(damage) : damage;
    }

    /** (c) DropComponentItemsOnBadBreak's bark (:128): log2's wood type. */
    @ModifyArg(method = "DropComponentItemsOnBadBreak(Lbtw/modern/World;IIIIF)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/src/btw/block/FCBlockLog;DropItemsIndividualy(Lbtw/modern/World;IIIIIIF)V",
                    ordinal = 1, remap = false),
            index = 6, require = 1, remap = false)
    private int btw$badBreakBarkWoodType(int damage) {
        return btw$isLog2() ? ModernWoods.woodType(damage) : damage;
    }

    /** (d) GetIsStump(IBlockAccess, ...) (:187-202): log2's stump is log2 at the cell with both axis bits set. */
    @Inject(method = "GetIsStump(Lbtw/modern/IBlockAccess;III)Z", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void btw$log2Stump(btw.modern.IBlockAccess blockAccess, int i, int j, int k,
                               CallbackInfoReturnable<Boolean> cir) {
        if (!btw$isLog2()) return;
        cir.setReturnValue(blockAccess.getBlockId(i, j, k) == ModernWoods.LOG2_ID
                && (blockAccess.getBlockMetadata(i, j, k) & 12) == 12);
    }

    /** (e) GetFurnaceBurnTime (:168-171): the planks fuel of log2's wood type, x4 as FC's. */
    @ModifyArg(method = "GetFurnaceBurnTime(I)I",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/src/btw/block/FCBlockPlanks;GetFurnaceBurnTimeByWoodType(I)I",
                    remap = false),
            index = 0, require = 1, remap = false)
    private int btw$fuelWoodType(int itemDamage) {
        return btw$isLog2() ? ModernWoods.woodType(itemDamage) : itemDamage;
    }

    /** (f) getIcon (:237-253), client cosmetic: the species' 1.20.1 log side and end (a stump renders as a log). */
    @Inject(method = "getIcon(II)Lbtw/modern/Icon;", at = @At("HEAD"), cancellable = true, require = 1,
            remap = false)
    private void btw$log2Icon(int side, int metadata, CallbackInfoReturnable<btw.modern.Icon> cir) {
        if (btw$isLog2()) {
            cir.setReturnValue(ModernWoods.logIcon(side, metadata));
        }
    }
}
