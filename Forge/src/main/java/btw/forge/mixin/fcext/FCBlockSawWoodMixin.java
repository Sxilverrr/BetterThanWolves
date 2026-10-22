package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M6, the saw)
 *
 * <p><b>Feature:</b> FC's saw on a modern log: 4 planks of its own wood, 2 sawdust and its bark, as for oak.</p>
 *
 * <p><b>Conflict with FC:</b> FCBlockSaw.HandleSawingExceptionCases cuts a log only when the cell reads
 * {@code Block.wood.blockID} (17), and pays planks and bark of {@code meta & 3} (Client FCBlockSaw.java:619-639), so
 * the saw did nothing to log2.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's log case, run for log2 with its wood type 5-8.</p>
 *
 * <p><b>Rebalance:</b> none.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; javap of
 * Forge/build/classes/java/fc): the private {@code HandleSawingExceptionCases(World, int x7, Random)} HEAD,
 * cancellable: at a log2 cell, FC's log case (:619-639) with the wood type, each item through FC's own
 * FCUtilsItem.EjectSingleItemWithRandomOffset (ModernWoods.ejectSawnLog2), then FC's tail (:722-727): the shadowed,
 * package-private {@code EmitSawParticles} and {@code setBlockToAir}; answers true as FC does for a cut block. It
 * re-expresses FC's rule as FCBlockSaplingTwinMixin and TwinRegistry.fungusTick do; FC is frozen, so the 10 copied
 * lines cannot drift.</p>
 *
 * <p><b>Alternative considered and rejected:</b> a {@code @Redirect} of the block id read plus {@code @ModifyArg} on
 * the planks and bark ejects (four injections into one method, keyed on a value the redirect changed).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: the saw cuts log2 into 4 planks, 2 sawdust and bark of its wood type 5-8
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockSaw", remap = false)
public abstract class FCBlockSawWoodMixin {

    /** FCBlockSaw.EmitSawParticles (Client FCBlockSaw.java:490), package-private as in FC. */
    @Shadow(remap = false)
    abstract void EmitSawParticles(btw.modern.World world, int i, int j, int k, Random random);

    /** HandleSawingExceptionCases (:606-729) at a log2 cell: FC's log case with log2's wood type. */
    @Inject(method = "HandleSawingExceptionCases(Lbtw/modern/World;IIIIIIILjava/util/Random;)Z", at = @At("HEAD"),
            cancellable = true, require = 1, remap = false)
    private void btw$sawLog2(btw.modern.World world, int i, int j, int k, int sawI, int sawJ, int sawK,
                             int sawFacing, Random random, CallbackInfoReturnable<Boolean> cir) {
        if (!ModernWoods.isBuilt() || world.getBlockId(i, j, k) != ModernWoods.LOG2_ID) return;
        int woodType = ModernWoods.woodType(world.getBlockMetadata(i, j, k));
        if (!ModernWoods.ejectSawnLog2(world, i, j, k, woodType)) return;
        EmitSawParticles(world, i, j, k, random);
        world.setBlockToAir(i, j, k);
        cir.setReturnValue(Boolean.TRUE);
    }
}
