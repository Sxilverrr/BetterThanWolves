package btw.forge;

/**
 * Implemented on {@link net.minecraft.world.entity.item.ItemEntity} by mixin.ItemEntityMixin: the entity's FC
 * view ({@link ItemEntityBridge}) lives and dies with it -- no static map, so no leak (a WeakHashMap whose value
 * references its key never evicts, triage RE2). Typed {@code Object} so merging the field into ItemEntity, which
 * loads during MC bootstrap, never forces the frozen 1.5.2 entity hierarchy to load early. Only server-side
 * entities ever get a bridge.
 */
public interface FcItemBridgeHolder {
    Object btw$getFcBridge();

    void btw$setFcBridge(Object bridge);
}
