package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M9, the iron chisel)
 *
 * <p><b>Feature:</b> carving a modern stump into its work stump costs the iron chisel 10 uses, as an oak stump
 * does.</p>
 *
 * <p><b>Conflict with FC:</b> FCItemChiselIron.onBlockDestroyed charges 10 only when the destroyed block was
 * {@code Block.wood.blockID} (17) and the cell now holds the work stump (Client FCItemChiselIron.java:45-58, the test
 * at :49); for log2 it fell through to FCItemChisel's 1.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's charge, with log2 as the log.</p>
 *
 * <p><b>Rebalance:</b> none.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; javap of
 * Forge/build/classes/java/fc): {@code onBlockDestroyed(ItemStack, World, int, int, int, int, EntityLiving)} HEAD,
 * cancellable: for log2 turned into FC's work stump (FCBetterThanWolves.fcBlockWorkStump, resolved by ModernWoods),
 * FC's {@code damageItem(10, user)} and true, as FC's own branch.</p>
 *
 * <p><b>Alternative considered and rejected:</b> a {@code @ModifyConstant} of the 17 (Block.wood's id is a field
 * read, not a constant).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: carving a log2 stump into a work stump costs the iron chisel 10, as for log 17
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.item.FCItemChiselIron", remap = false)
public abstract class FCItemChiselIronWoodMixin {

    /** onBlockDestroyed (:45-58): log2's work-stump conversion wears the chisel by 10. */
    @Inject(method = "onBlockDestroyed(Lbtw/modern/ItemStack;Lbtw/modern/World;IIIILbtw/modern/EntityLiving;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void btw$log2WorkStumpWear(btw.modern.ItemStack stack, btw.modern.World world, int blockId, int i, int j,
                                       int k, btw.modern.EntityLiving user, CallbackInfoReturnable<Boolean> cir) {
        int workStump = ModernWoods.workStumpId();
        if (blockId == ModernWoods.LOG2_ID && ModernWoods.isBuilt() && workStump > 0
                && world.getBlockId(i, j, k) == workStump) {
            stack.damageItem(10, user);
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
