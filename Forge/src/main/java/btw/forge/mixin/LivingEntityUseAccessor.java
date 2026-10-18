package btw.forge.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to LivingEntity.useItemRemaining (protected, LivingEntity.java:219; read through the public
 * getUseItemRemainingTicks :3038, no setter) for PlayerBridge.SetItemInUseCount: 1.5.2 kept the use countdown on the
 * player (EntityPlayer.itemInUseCount) and FC ends a hold-to-craft use with SetItemInUseCount(1)
 * (FCItemCraftingProgressive.java:83).
 */
// FCMOD-INTEGRATION(1.20.1): held_use: FC's use countdown is the real player's, so a hold-to-craft use can end itself
@Mixin(LivingEntity.class)
public interface LivingEntityUseAccessor {

    @Accessor("useItemRemaining")
    void btw$setUseItemRemaining(int ticks);
}
