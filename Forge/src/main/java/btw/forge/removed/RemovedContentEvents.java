package btw.forge.removed;

import btw.forge.BTWForgeMod;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Forge-bus guards of the stays-removed rule ({@link RemovedContent}).
 *
 * <p><b>Maintainer ruling:</b> "blocks that 4.B explicitly removed must stay removed." Existing and command-placed
 * removed blocks are left alone; these guards only close the survival sources that are not recipes, loot, tabs or
 * structure blocks:</p>
 * <ul>
 *   <li><b>String placing tripwire.</b> 4.B made string a plain item (1.5.2 {@code ItemReed(31, Block.tripWire)}
 *       became {@code Item}, vanilla/server Item.java:107/119); 1.20.1 string is an {@code ItemNameBlockItem} of the
 *       tripwire block. {@code RightClickBlock} with {@code setUseItem(DENY)} stops {@code ItemStack.useOn} before it
 *       runs (ServerPlayerGameMode.java:323-351) on both sides, so the client does not predict a placement either.
 *       Using the clicked block (opening a chest while holding string) is unaffected. Generalised by the table:
 *       any item that places a T1 block without being a removed item itself.</li>
 *   <li><b>TNT and hopper minecarts</b> spawned by structures, mods, spawn eggs, dispensers or commands become plain
 *       minecarts (4.B: FCItemStub 407/408). The join is cancelled and the cart replaced at the end of the server
 *       tick, the {@code VanillaMobReplacer} pattern: worldgen entities join from LevelChunk.runPostLoad before the
 *       chunk is FULL. Carts loaded from disk ({@code loadedFromDisk}) are existing world content and are left
 *       alone. A hopper cart's contents (and unrolled loot table) are dropped where it stood.</li>
 *   <li><b>Trades (guard).</b> {@code VanillaMobReplacer} already swaps every 1.20.1 villager for an FC villager, and
 *       the 1.20.1 wandering-trader table sells no removed id; the villager and wanderer listings are still wrapped
 *       (at LOWEST priority, after other mods add theirs) so a modded or future trade selling a removed id is
 *       dropped, or for a T2 id sells the BTW successor, like loot. {@code AbstractVillager
 *       .addOffersFromItemListings} skips null offers (AbstractVillager.java:221-222).</li>
 * </ul>
 */
// FCMOD-INTEGRATION(1.20.1): stays-removed: string/tripwire, generated T1 minecarts and trades follow the removed-id table
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
public final class RemovedContentEvents {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RemovedContent");

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** A cancelled removed minecart waiting for its replacement at the end of the server tick. */
    private record PendingCart(ServerLevel level, Entity original) {}

    private static final Queue<PendingCart> PENDING = new ConcurrentLinkedQueue<>();

    private RemovedContentEvents() {}

    // ---- placement -------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !RemovedContent.placesRemovedBlock(held.getItem())) return;
        event.setUseItem(Event.Result.DENY);
    }

    // ---- minecarts -------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof AbstractMinecart) || !RemovedContent.isRemovedEntity(entity.getType())) return;
        event.setCanceled(true);
        PENDING.add(new PendingCart(level, entity));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) drainCarts();
    }

    /** Before stopServer()'s final chunk save (as VanillaMobReplacer). */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        drainCarts();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private static void drainCarts() {
        if (PENDING.isEmpty()) return;
        List<PendingCart> batch = new ArrayList<>();
        for (PendingCart p; (p = PENDING.poll()) != null; ) batch.add(p);
        for (PendingCart p : batch) {
            try {
                replaceCart(p.level(), p.original());
            } catch (Throwable t) {
                if (LOGGED.add("cart|" + t.getClass().getName())) {
                    LOGGER.warn("[REMOVED] could not replace {} with a minecart: {}", p.original().getType(), t.toString());
                }
            }
        }
    }

    private static void replaceCart(ServerLevel level, Entity original) {
        if (original instanceof ContainerEntity container && original instanceof Container inventory) {
            container.unpackChestVehicleLootTable(null);
            Containers.dropContents(level, original, inventory);
        }
        Minecart cart = new Minecart(EntityType.MINECART, level);
        cart.moveTo(original.getX(), original.getY(), original.getZ(), original.getYRot(), original.getXRot());
        cart.setDeltaMovement(original.getDeltaMovement());
        cart.setUUID(original.getUUID());   // the cancelled cart never registered its UUID
        if (original.hasCustomName()) {
            cart.setCustomName(original.getCustomName());
            cart.setCustomNameVisible(original.isCustomNameVisible());
        }
        if (!level.addFreshEntity(cart)) {
            LOGGER.warn("[REMOVED] the plain minecart replacing {} at {} was rejected", original.getType(),
                    original.blockPosition());
        } else if (LOGGED.add("cartdone|" + EntityType.getKey(original.getType()))) {
            LOGGER.info("[REMOVED] {} spawned at {} became a plain minecart (logged once per type)",
                    EntityType.getKey(original.getType()), original.blockPosition());
        }
    }

    // ---- trades (guard) --------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onVillagerTrades(VillagerTradesEvent event) {
        for (Int2ObjectMap.Entry<List<VillagerTrades.ItemListing>> level : event.getTrades().int2ObjectEntrySet()) {
            guardListings(level.getValue());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onWandererTrades(WandererTradesEvent event) {
        guardListings(event.getGenericTrades());
        guardListings(event.getRareTrades());
    }

    private static void guardListings(List<VillagerTrades.ItemListing> listings) {
        if (listings == null) return;
        for (int i = 0; i < listings.size(); i++) {
            VillagerTrades.ItemListing original = listings.get(i);
            if (original == null || original instanceof GuardedListing) continue;
            listings.set(i, new GuardedListing(original));
        }
    }

    /** A listing whose offer may not sell a removed id: dropped (T1/T3) or selling the BTW successor (T2). */
    private record GuardedListing(VillagerTrades.ItemListing delegate) implements VillagerTrades.ItemListing {
        @Override
        public MerchantOffer getOffer(Entity trader, net.minecraft.util.RandomSource random) {
            MerchantOffer offer = delegate.getOffer(trader, random);
            if (offer == null || !RemovedContent.isRemovedLoot(offer.getResult())) return offer;
            ItemStack replacement = RemovedContent.replacementFor(offer.getResult());
            if (replacement.isEmpty()) return null;
            return new MerchantOffer(offer.getBaseCostA(), offer.getCostB(), replacement, offer.getUses(),
                    offer.getMaxUses(), offer.getXp(), offer.getPriceMultiplier(), offer.getDemand());
        }
    }
}
