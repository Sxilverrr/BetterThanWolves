package btw.forge.mixin;

import btw.forge.FcItemBridgeHolder;
import btw.forge.ItemEntityBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dropped items for FC (design_items U3/U3c). MC {@link ItemEntity}s are the only representation of dropped
 * items, and FC reads/mutates them through an {@link ItemEntityBridge} (its 1.5.2 EntityItem view):
 * <ul>
 *   <li>per-entity storage of that view ({@link FcItemBridgeHolder}), set on server-side entities only;</li>
 *   <li>getItem() HEAD: lands pending FC changes (eaten count, pickup delay, bellows velocity) before MC reads
 *       the stack, so playerTouch / merge / vanilla hopper / save never act on a stale count;</li>
 *   <li>tick() RETURN: the FCMOD EntityItem.doBlockCollisions bottom layer (items resting ON a BTW hopper,
 *       cauldron, crucible or planter trigger it).</li>
 * </ul>
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin implements FcItemBridgeHolder {

    @Unique
    private static final org.apache.logging.log4j.Logger BTW$LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("BTW-ItemEntityBridge");

    /**
     * Set once a call into ItemEntityBridge escaped its own try/catch -- only a failure to load/link the bridge
     * (it extends the frozen 1.5.2 EntityItem), which is permanent: the tick hook then stays off for the session
     * instead of failing on every dropped item's every tick.
     */
    @Unique
    private static volatile boolean btw$fcItemHooksDisabled;

    /** This entity's FC view, or null (never created on the client, or not yet queried by FC). */
    @Unique
    private Object btw$fcBridge;

    @Override
    public Object btw$getFcBridge() {
        return this.btw$fcBridge;
    }

    @Override
    public void btw$setFcBridge(Object bridge) {
        this.btw$fcBridge = bridge;
    }

    @Inject(method = "getItem", at = @At("HEAD"))
    private void btw$flushFcView(CallbackInfoReturnable<ItemStack> cir) {
        Object bridge = this.btw$fcBridge;
        if (bridge != null && !btw$fcItemHooksDisabled) {
            try {
                ItemEntityBridge.onMcRead(bridge);
            } catch (Throwable t) {
                btw$disableFcItemHooks("getItem flush", t);
            }
        }
    }

    /**
     * 1.5.2 EntityItem.doBlockCollisions starts at floor(minY - 0.01) "so that blocks like Hoppers get collision
     * events with items on top" (vanilla/server EntityItem.java:483-500); MC's checkInsideBlocks starts at
     * minY + 1e-7. Every RETURN, so it also runs after Forge's onEntityItemUpdate early return.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void btw$fcSupportingBlockCollisions(CallbackInfo ci) {
        if (btw$fcItemHooksDisabled) return;
        ItemEntity self = (ItemEntity) (Object) this;
        if (!self.isRemoved() && self.level() instanceof ServerLevel level) {
            try {
                ItemEntityBridge.collideWithSupportingFcBlocks(self, level);
            } catch (Throwable t) {
                btw$disableFcItemHooks("supporting-block collisions", t);
            }
        }
    }

    /** Every dropped item ticks: a failure here must never reach ItemEntity.tick ("Ticking entity" crash). */
    @Unique
    private static void btw$disableFcItemHooks(String hook, Throwable t) {
        if (!btw$fcItemHooksDisabled) {
            btw$fcItemHooksDisabled = true;
            BTW$LOGGER.error("[ITEM-BRIDGE] {} failed outside ItemEntityBridge's own guards; FC dropped-item hooks "
                    + "disabled for this session", hook, t);
        }
    }
}
