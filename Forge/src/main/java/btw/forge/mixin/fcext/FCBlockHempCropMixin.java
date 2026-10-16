package btw.forge.mixin.fcext;

import btw.forge.fcext.FcCropGrowthHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * FCMOD-INTEGRATION(1.20.1): fc_crop_events (hemp)
 *
 * <p><b>Feature:</b> Forge's {@code BlockEvent.CropGrowEvent} (Pre: a listener may DENY or ALLOW one growth step;
 * Post: the crop grew), which 1.20.1 crops fire from their random tick and which season and crop-gating mods listen
 * to.</p>
 *
 * <p><b>Conflict with FC:</b> hemp (block_241, FCBlockHempCrop, a btw.modern.BlockCrops) is a ProxyBlock, and
 * TickPolicy fires the crop events only around the FC tick of FC-owned vanilla-id and twin crops (#016), so hemp
 * fired nothing and no mod could gate it. Its FC tick also grows the top block from a fully grown base, a second,
 * separate growth.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's hemp rule stays whole; Forge's event is asked at each of FC's own
 * growth rolls (btw.forge.fcext.FcCropGrowthHooks).</p>
 *
 * <p><b>Rebalance:</b> none. With no listener result each roll is FC's ({@code nextFloat} is always drawn first, so
 * the random stream is unchanged). DENY fails the roll, ALLOW passes it. Post fires at the crop after a level step, and
 * at the new top block (original state air) after the top grew.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; descriptors
 * from javap of the remapped FC output, Forge/build/classes/java/fc). In {@code AttemptToGrow(World, int, int, int,
 * Random)}: {@code @Redirect} of both {@code Random.nextFloat()} calls (offset 162, the level roll, and 226, the top
 * roll; mutually exclusive branches, so {@code require = 2}; every failed roll jumps to the return, {@code ifgt 180 ->
 * goto 273} and {@code ifgt 273}); {@code @Inject} AFTER {@code invokevirtual FCBlockHempCrop.IncrementGrowthLevel}
 * (offset 177, constant-pool owner FCBlockHempCrop) and AFTER {@code invokevirtual
 * btw.modern.World.setBlockAndMetadataWithNotify(IIIII)Z} (offset 256, the top block; its result is popped next).</p>
 *
 * <p><b>Alternative considered and rejected:</b> a whole-tick gate in ProxyBlock.randomTick (one event for two
 * different growths, and the base's level and top growth could not be told apart); posting from
 * IncrementGrowthLevel (after the roll, so a DENY could not stop growth without undoing it).</p>
 */
// FCMOD-INTEGRATION(1.20.1): fc_crop_events: hemp asks CropGrowEvent.Pre at both of FC's growth rolls and posts Post after the level step and at the new top
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockHempCrop", remap = false)
public abstract class FCBlockHempCropMixin {

    /** Both growth rolls (Server FCBlockHempCrop.AttemptToGrow: level step and top block). */
    @Redirect(method = "AttemptToGrow(Lbtw/modern/World;IIILjava/util/Random;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Random;nextFloat()F", remap = false),
            require = 2, remap = false)
    private float btw$growthRoll(Random rand, btw.modern.World world, int i, int j, int k, Random randArg) {
        return FcCropGrowthHooks.growthRoll(rand, world, i, j, k);
    }

    /** The base grew a level. */
    @Inject(method = "AttemptToGrow(Lbtw/modern/World;IIILjava/util/Random;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/src/btw/block/FCBlockHempCrop;IncrementGrowthLevel(Lbtw/modern/World;III)V",
                    shift = At.Shift.AFTER, remap = false),
            require = 1, remap = false)
    private void btw$grown(btw.modern.World world, int i, int j, int k, Random rand, CallbackInfo ci) {
        FcCropGrowthHooks.grown(world, i, j, k);
    }

    /** The top block was placed at j + 1. */
    @Inject(method = "AttemptToGrow(Lbtw/modern/World;IIILjava/util/Random;)V",
            at = @At(value = "INVOKE", target = "Lbtw/modern/World;setBlockAndMetadataWithNotify(IIIII)Z",
                    shift = At.Shift.AFTER, remap = false),
            require = 1, remap = false)
    private void btw$grownAbove(btw.modern.World world, int i, int j, int k, Random rand, CallbackInfo ci) {
        FcCropGrowthHooks.grownAbove(world, i, j, k);
    }
}
