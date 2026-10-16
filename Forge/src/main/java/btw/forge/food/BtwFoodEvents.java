package btw.forge.food;

import btw.forge.BTWForgeMod;
import btw.forge.BTWNetwork;
import btw.forge.PotionMapping;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The FC food table's life cycle and the FC poisoning of foods FC has no item for (INTEGRATIONS #024):
 * <ul>
 *   <li>{@link AddReloadListenerEvent}: loads {@code data/<ns>/btw_foods} (BtwFoodTable.Loader) on every server load
 *       and {@code /reload}.</li>
 *   <li>{@link TagsUpdatedEvent} (SERVER_DATA_LOAD): resolves the tag entries once the server's tags are bound, then
 *       logs the two {@code [BTW-Food]} summary lines. It runs before the reload is synced to players
 *       (ReloadableServerResources.updateRegistryTags, then PlayerList.reloadResources).</li>
 *   <li>{@link OnDatapackSyncEvent}: sends the resolved table to the joining player, or to everyone after a reload,
 *       even when empty, so a client never keeps another server's table.</li>
 *   <li>{@link LivingEntityUseItemEvent.Finish}: a server player who ate a TABLE or MODERN_CONVERTED food rolls its
 *       table poison, or FC's standard food poisoning for a food crafted from a poisonous raw ingredient
 *       ({@link FcFoodValues#isRawCrafted}, written by the recipe bridge). FC's own foods are skipped: their 1.5.2
 *       onFoodEaten applies their effects.</li>
 * </ul>
 */
// FCMOD-INTEGRATION(1.20.1): food_values: the btw_foods table loads, syncs and poisons like FC's own food items, with no foreign types
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public final class BtwFoodEvents {

    private BtwFoodEvents() {}

    private static final Logger LOGGER = LogManager.getLogger("BTW-Food");

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new BtwFoodTable.Loader(event.getConditionContext()));
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
        try {
            BtwFoodTable.resolveTags(event.getRegistryAccess());
        } catch (Throwable t) {
            LOGGER.error("[BTW-Food] resolving the btw_foods tag entries failed; the item entries stay", t);
        }
        FcFoodValues.logSummary();
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        BTWNetwork.sendFoodTable(event.getPlayer());
    }

    @SubscribeEvent
    public static void onFinishUsing(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // getItem() is the copy taken before the use (LivingEntity.completeUsingItem), so the eaten item is known
        ItemStack eaten = event.getItem();
        if (eaten.isEmpty()) return;
        try {
            FcFoodValues.FcFood food = FcFoodValues.of(eaten, player);
            if (food == null || (food.source() != FcFoodValues.Source.TABLE
                    && food.source() != FcFoodValues.Source.MODERN_CONVERTED)) {
                return;
            }
            Item item = eaten.getItem();
            BtwFoodTable.Entry entry = BtwFoodTable.get(item);
            FcFoodValues.Effect poison = entry != null ? entry.poison() : null;
            if (poison == null && FcFoodValues.isRawCrafted(item)) {
                poison = BtwFoodTable.STANDARD_POISON;
            }
            // 1.5.2 ItemFood.onFoodEaten: one roll, server only (vanilla/server ItemFood.java:76-82)
            if (poison != null && player.getRandom().nextFloat() < poison.chance()) {
                MobEffect effect = PotionMapping.getEffect(poison.fcPotionId());
                if (effect != null) {
                    player.addEffect(new MobEffectInstance(effect, poison.durationTicks(), poison.amplifier()));
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[BTW-Food] food poisoning for {} failed: {}", eaten, t.toString());
        }
    }
}
