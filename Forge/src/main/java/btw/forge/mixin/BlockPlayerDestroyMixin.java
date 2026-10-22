package btw.forge.mixin;

import btw.forge.VanillaPairDrops;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * FCMOD-INTEGRATION(1.20.1): vanilla_pair_drops (harvest exhaustion; the FC-identity slice of FU-W5, INTEGRATIONS.md:488)
 *
 * <p>A 1.20.1 harvest of a ruled block ({@link VanillaPairDrops#isRuled}: the beds and doors 1.20.1 breaks and drops)
 * charges FC's harvest exhaustion (Modern-Common Block.harvestBlock, Block.java:630-633: the held item's
 * GetExhaustionOnUsedToHarvestBlock, 0.025 by default) instead of 1.20.1's 0.005 (Block.java:352-357). Every other
 * block with an FC identity is harvested by FC itself (ServerPlayerGameModeMixin cancels destroyBlock at HEAD), and
 * blocks without one keep 0.005 until FU-W5 widens the predicate.</p>
 *
 * <p>Both handlers key on the same {@code isRuled(this)}: the HEAD charge always charges (FC's amount through
 * PlayerBridge for a server player, FC's default 0.025 otherwise or on a failure), and the {@link ModifyArg} zeroes the
 * 0.005, which PlayerMixin then skips. A ModifyArg chains with other mods' injectors; a Redirect would not. 1.20.1
 * calls playerDestroy only when canHarvestBlock holds (ServerPlayerGameMode.java:249-257), as 4.B charged only in
 * harvestBlock, never on an improper-tool break. The block is {@code this}, not the state argument:
 * DoublePlantBlock.playerDestroy passes AIR (DoublePlantBlock.java:84-86).</p>
 */
@Mixin(Block.class)
public abstract class BlockPlayerDestroyMixin {

    // FCMOD-INTEGRATION(1.20.1): vanilla_pair_drops: FC's harvest exhaustion for a bed or door 1.20.1 harvests
    @Inject(method = "playerDestroy", at = @At("HEAD"))
    private void btw$fcHarvestExhaustion(Level level, Player player, BlockPos pos, BlockState state,
                                         @Nullable BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        Block self = (Block) (Object) this;
        if (VanillaPairDrops.isRuled(self)) {
            VanillaPairDrops.chargeHarvest(player, level, pos, self, state);
        }
    }

    // FCMOD-INTEGRATION(1.20.1): vanilla_pair_drops: 1.20.1's 0.005 is replaced by FC's charge above, never paid on top
    @ModifyArg(method = "playerDestroy", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float btw$dropVanillaHarvestExhaustion(float amount) {
        return VanillaPairDrops.isRuled((Block) (Object) this) ? 0F : amount;
    }
}
