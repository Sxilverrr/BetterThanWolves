package btw.forge;

import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * FCMOD-INTEGRATION(1.20.1): fc_item_use_order
 *
 * <p>Hands Forge's RightClickBlock {@code useItem} verdict to FC Stage 2. 1.20.1 refuses the item step of a click when a
 * handler set {@code useItem = DENY} (ServerPlayerGameMode.java:351), which is how claim and protection mods stop item
 * use on a block they guard (RemovedContentEvents does the same for string). FC's Stage 2 runs earlier, inside the
 * clicked block's {@code use} (ProxyBlock.use, BlockBehaviorMixin.btw$use; 4.B's item use after a declined activation,
 * vanilla/server ItemInWorldManager.java:390-404), where the event is out of reach. So FC placed blocks, used tools and
 * ran FCItemDye there under a DENY. This listener records the verdict on the click frame
 * ({@link PlayerBridge#setFcUseDenied}), and ProxyBlock.tryPlaceHeldItem, the single FC {@code onItemUse} point of a
 * block click, refuses while it is set.</p>
 *
 * <ul>
 *   <li>LOWEST priority: the verdict after every other handler. A handler at LOWEST that runs after this one is not
 *       seen; the item step itself always reads the final event (:351).</li>
 *   <li>Server side only: the click frame is the server player's, and the client predicts no FC item use.</li>
 *   <li>Every post overwrites the value, so a third-party caller that posts RightClickBlock and then calls
 *       {@code blockstate.use} itself (deployer-style fake players) gets its own verdict for that use.</li>
 *   <li>A cancelled event needs nothing: useItemOn returns before block use (:324), and this listener does not
 *       receive cancelled events.</li>
 * </ul>
 *
 * <p>Alternative considered: {@code @ModifyVariable} at the STORE of the event local in useItemOn (PlayerMixin.java:467
 * is a precedent): the exact final value with no listener ordering, but it covers useItemOn only, never a
 * third-party post. It stays the fallback if listener ordering becomes a problem.</p>
 */
// FCMOD-INTEGRATION(1.20.1): fc_item_use_order: FC Stage 2 runs inside block use, where Forge's useItem DENY is out of reach; this listener carries it there
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public final class FcItemUseOrder {

    private FcItemUseOrder() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        PlayerBridge.getOrCreate(event.getEntity()).setFcUseDenied(event.getUseItem() == Event.Result.DENY);
    }
}
