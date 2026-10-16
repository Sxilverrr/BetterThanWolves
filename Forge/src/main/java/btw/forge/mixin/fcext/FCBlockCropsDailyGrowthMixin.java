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
 * FCMOD-INTEGRATION(1.20.1): fc_crop_events (BTW wheat)
 *
 * <p><b>Feature:</b> Forge's {@code BlockEvent.CropGrowEvent} (Pre: a listener may DENY or ALLOW one growth step;
 * Post: the crop grew), which 1.20.1 crops fire from their random tick and which season and crop-gating mods (Serene
 * Seasons 9.0, crop-yield mods) listen to.</p>
 *
 * <p><b>Conflict with FC:</b> BTW wheat (block_1089 and its top block_1090, FCBlockWheatCrop and FCBlockWheatCropTop,
 * both FCBlockCropsDailyGrowth) is a ProxyBlock. TickPolicy fires the crop events only around the FC tick of FC-owned
 * vanilla-id and twin crops (#016, TickPolicy.decide returns null for ProxyBlocks), so BTW wheat fired nothing and no
 * mod could gate it. A whole-tick gate is wrong for it: the same FC tick resets the grown-today flag at night (Common
 * FCBlockCropsDailyGrowth.java:41-49), and denying that reset would break FC's once-per-day growth.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's daily rule stays whole; Forge's event is asked at FC's own growth
 * roll (btw.forge.fcext.FcCropGrowthHooks).</p>
 *
 * <p><b>Rebalance:</b> none. With no listener result the roll is FC's ({@code nextFloat} is always drawn first, so the
 * random stream is unchanged). DENY fails the roll, ALLOW passes it; Post fires after FC's
 * {@code UpdateFlagForGrownToday}, with the pre-growth state as the original.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; descriptors
 * from javap of the remapped FC output, Forge/build/classes/java/fc). In {@code AttemptToGrow(World, int, int, int,
 * Random)}: {@code @Redirect} of the method's only {@code Random.nextFloat()} (offset 166, followed by
 * {@code fcmpg / ifgt 193}, and 193 is the return), with the target method's arguments captured; {@code @Inject} AFTER
 * the {@code invokevirtual FCBlockCropsDailyGrowth.UpdateFlagForGrownToday} (offset 190, constant-pool owner
 * FCBlockCropsDailyGrowth, so FCBlockWheatCropTop's override is the same call site).</p>
 *
 * <p><b>Alternative considered and rejected:</b> a whole-tick gate in ProxyBlock.randomTick (breaks the night reset of
 * the grown-today flag); a mixin on FCBlockCrops.AttemptToGrow (dead code in 4.B: its only subclass,
 * FCBlockCropsDailyGrowth, overrides it without calling super); posting the event in the bridge's
 * IncrementGrowthLevel path (after the roll, so a DENY could not stop growth without undoing it).</p>
 */
// FCMOD-INTEGRATION(1.20.1): fc_crop_events: BTW wheat asks CropGrowEvent.Pre at FC's growth roll and posts Post after it grew, keeping FC's daily rule whole
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockCropsDailyGrowth", remap = false)
public abstract class FCBlockCropsDailyGrowthMixin {

    /** The growth roll (Common FCBlockCropsDailyGrowth.AttemptToGrow: {@code rand.nextFloat() <= fChance}). */
    @Redirect(method = "AttemptToGrow(Lbtw/modern/World;IIILjava/util/Random;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Random;nextFloat()F", remap = false),
            require = 1, remap = false)
    private float btw$growthRoll(Random rand, btw.modern.World world, int i, int j, int k, Random randArg) {
        return FcCropGrowthHooks.growthRoll(rand, world, i, j, k);
    }

    /** The crop grew: after IncrementGrowthLevel and UpdateFlagForGrownToday. */
    @Inject(method = "AttemptToGrow(Lbtw/modern/World;IIILjava/util/Random;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/src/btw/block/FCBlockCropsDailyGrowth;UpdateFlagForGrownToday(Lbtw/modern/World;III)V",
                    shift = At.Shift.AFTER, remap = false),
            require = 1, remap = false)
    private void btw$grown(btw.modern.World world, int i, int j, int k, Random rand, CallbackInfo ci) {
        FcCropGrowthHooks.grown(world, i, j, k);
    }
}
