package btw.forge.food;

import btw.forge.BTWForgeMod;
import btw.forge.LegacyProxyItem;
import btw.forge.PlayerBridge;
import btw.forge.ProxyItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * FC's drink rule for every drink: a food-poisoned player cannot drink (INTEGRATIONS #021).
 *
 * <p>1.5.2 refuses FC's drinks while the hunger effect is active, with FC's "can't consume" feedback: FCItemPotion
 * (Common FCItemPotion.java:124-133) and FCItemBucketDrinkable (Common FCItemBucketDrinkable.java:47-58) check
 * {@code EntityPlayer.CanDrink}. In 1.20.1 a potion, honey bottle or modded drink starts drinking regardless, and the
 * 1.20.1 milk bucket even cures the poisoning it should be blocked by. Eating is already gated
 * (PlayerMixin.btw$restrictEating); this is the drinking half.</p>
 *
 * <p>Rule: a player with the hunger effect cannot start using any item whose use animation is DRINK (vanilla, FC and
 * modded drinks alike). Splash and lingering potions are thrown, not drunk, and stay usable, as in 1.5.2. Runs on both
 * sides so the client shows no drinking animation. The server plays FC's refusal feedback (PlayerBridge.OnCantConsume)
 * unless FC already did: a main-hand drink with an FC item ran its FC onItemRightClick first
 * (ServerPlayerGameModeMixin.btw$useItem), which refused with the same feedback; ProxyItem drinks refuse inside FC and
 * never start.</p>
 */
// FCMOD-INTEGRATION(1.20.1): hunger_contract: 1.5.2's food-poisoned drink refusal covers every 1.20.1 and modded drink, not only FC's
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public final class FCDrinkRules {

    private FCDrinkRules() {}

    @SubscribeEvent
    public static void onStartUsing(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!player.hasEffect(MobEffects.HUNGER)) return;
        ItemStack stack = event.getItem();
        if (stack.isEmpty() || stack.getUseAnimation() != UseAnim.DRINK) return;
        event.setCanceled(true);
        if (player instanceof ServerPlayer sp && !fcAlreadyRefused(sp, stack)) {
            PlayerBridge.getOrCreate(sp).OnCantConsume();
        }
    }

    /** True when FC's own onItemRightClick already refused this drink with its feedback. */
    private static boolean fcAlreadyRefused(ServerPlayer player, ItemStack stack) {
        if (stack.getItem() instanceof LegacyProxyItem) return true;
        return stack == player.getMainHandItem() && ProxyItem.fcItemFor(stack.getItem()) != null;
    }
}
