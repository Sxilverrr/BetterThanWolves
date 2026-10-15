package btw.forge.mixin;

import btw.forge.BoneMealPolicy;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): bone_meal
 *
 * <p><b>Feature:</b> 1.20.1 bone meal used on a block, {@code BoneMealItem.useOn} (BoneMealItem.java:32-55):
 * {@code applyBonemeal} grows any {@code BonemealableBlock} at once ({@code performBonemeal}: crops +2..5 ages,
 * saplings to trees, mushrooms to huge mushrooms, grass and flowers on the grass block, nylium on netherrack), else
 * {@code growWaterPlant} plants seagrass or coral in the water on the clicked face; every success plays levelEvent
 * 1505 (particles and the bone-meal sound).</p>
 *
 * <p><b>Conflict with FC:</b> in 4.B bone meal never grows anything. It is fertilizer: FCItemDye.onItemUse applies the
 * clicked block's {@code AttemptToApplyFertilizerTo} and uses the item up exactly when that is true (Client
 * FCItemDye.java:34-47, 88-99); plants relay it to their soil (FCBlockPlants, Client FCBlockPlants.java:129-143), and
 * fertilized FC farmland and planters double crop growth until the next full stage. Vanilla's growth code is dead in
 * 4.B (FCItemDye never calls super). The port reached {@code useOn} whenever FC declined, and for every off-hand use
 * (the FC player has only the main hand), so carrots, stems and saplings grew instantly, the grass block sprouted
 * flowers, cocoa ripened and sand under water grew seagrass, with the item consumed, while the soil stayed
 * unfertilized.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): bone meal keeps its 1.20.1 item and use path; on every block whose
 * growth BTW governs, its effect is FC's fertilizer rule (btw.forge.BoneMealPolicy).</p>
 *
 * <p><b>Rebalance:</b> none; 4.B parity. On an FC-mapped block, or a post-1.5.2 plant BTW governs, bone meal applies
 * FC fertilizer (the plant's soil below), is used up when FC took it, and otherwise does nothing and is kept; there
 * are no particles or sound. Other blocks keep 1.20.1 bone meal. Only {@code minecraft:bone_meal} is FC fertilizer.</p>
 *
 * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code useOn(UseOnContext)}, cancellable. BoneMealPolicy
 * answers the result for an owned route, or null to let 1.20.1 run. The server applies FC fertilizer through
 * WorldBridge and shrinks the stack by one on success (CONSUME), else PASS; ServerPlayerGameMode.useItemOn restores a
 * creative count around {@code useOn} (ServerPlayerGameMode.java:353-356). The client predicts the swing with the
 * same FC decision on the read-only ClientFcWorld (SUCCESS or PASS) and never shrinks, as 1.20.1 does
 * (BoneMealItem.java:71-77).</p>
 *
 * <p><b>Alternative considered and rejected:</b> cancelling only {@code applyBonemeal} through a BonemealEvent
 * listener: the event cannot stop {@code growWaterPlant} or levelEvent 1505 (an ALLOW still plays it,
 * BoneMealItem.java:36-41), and a cancel turns the client prediction into a PASS with no swing. Running FC's
 * fertilizer at HEAD of ServerPlayerGameMode.useItemOn for bone meal (the former path): it ran before Forge's
 * RightClickBlock event, the spectator check and block activation, so weeds were not pulled first, claim mods could
 * not veto it, and it never covered the off hand.</p>
 */
// FCMOD-INTEGRATION(1.20.1): bone_meal: bone meal is FC fertilizer in 4.B, so 1.20.1's instant growth must not run on blocks whose growth BTW governs
@Mixin(BoneMealItem.class)
public abstract class BoneMealItemMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void btw$fcFertilizer(UseOnContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = BoneMealPolicy.onBoneMealUseOn(ctx);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
