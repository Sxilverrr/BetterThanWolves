package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M8, the chewed log)
 *
 * <p><b>Feature:</b> a chewed modern log (FC's FCBlockLogDamaged, block_1010, which has no species in 4.B either)
 * joining the uncut trunk above or below it, as a chewed oak log does.</p>
 *
 * <p><b>Conflict with FC:</b> FCBlockLogDamaged.DoesTargetBlockConnectToFacing connects only to itself, the log spike
 * and {@code Block.wood.blockID} (Client FCBlockLogDamaged.java:542-561, the 17 test at :555), so a chewed segment of
 * a log2 trunk drew narrowed ends against the log2 around it, and FC's model choice (SetCurrentModelForBlock,
 * :495-536) treated it as a free-standing segment.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): log2 counts as the log FC connects to.</p>
 *
 * <p><b>Rebalance:</b> none.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; javap of
 * Forge/build/classes/java/fc): {@code DoesTargetBlockConnectToFacing(int, IBlockAccess, int, int, int, int)}
 * RETURN, cancellable: a false answer becomes true when the target reads log2 (165).</p>
 *
 * <p><b>Alternative considered and rejected:</b> a HEAD answer for log2 (would duplicate FC's own branches before
 * them).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: chewed log segments connect to log2 as they do to FC's log 17
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockLogDamaged", remap = false)
public abstract class FCBlockLogDamagedWoodMixin {

    /** DoesTargetBlockConnectToFacing (:542-561): log2 connects like id 17. */
    @Inject(method = "DoesTargetBlockConnectToFacing(ILbtw/modern/IBlockAccess;IIII)Z", at = @At("RETURN"),
            cancellable = true, require = 1, remap = false)
    private void btw$connectToLog2(int myOrientation, btw.modern.IBlockAccess blockAccess, int i, int j, int k,
                                   int facing, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && ModernWoods.isBuilt()
                && blockAccess.getBlockId(i, j, k) == ModernWoods.LOG2_ID) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
