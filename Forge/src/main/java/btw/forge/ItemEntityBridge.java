package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * FC-side view (a 1.5.2 {@link btw.modern.EntityItem}) of a real MC {@link ItemEntity} (design_items U3).
 *
 * <p>In 1.5.2 World.getEntitiesWithinAABB returned EntityItems like any other entity and FC mutated them in
 * place: animals eat loose food one item per bite (vanilla/server EntityAnimal.java:1322-1368), cauldrons and
 * the BTW hopper absorb items on top, bellows push them, mining charges damage them. Here a dropped item is
 * an MC ItemEntity (EntityProxyFactory.createItemEntity), so FC sees it through this view:</p>
 * <ul>
 *   <li>{@link #wrap} = flush pending FC changes, sync from MC, snapshot;</li>
 *   <li>FC mutates the fields / the FC stack directly; {@link #flush} writes back only the deltas against the
 *       snapshot. A count change is applied as a DELTA on MC's live stack, so a concurrent MC merge or pickup
 *       is never overwritten and the change lands exactly once;</li>
 *   <li>flush points: ItemEntityMixin getItem() HEAD (before every MC read), the next wrap(), isEntityAlive()
 *       (long-held references) and the end of the level tick (safety net); setDead() discards immediately.</li>
 * </ul>
 *
 * <p>The runtime superclass is the frozen 1.5.2 EntityItem (shadow-listed in Forge/build.gradle), compiled
 * against Modern-Common's copy, so only members both declare are used: the (World) constructor,
 * getEntityItem / setEntityItemStack, onUpdate, onCollideWithPlayer, age, delayBeforeCanPickup and the Entity
 * fields. combineItems is NOT overridden (void in Modern-Common, boolean in the frozen class). The view is
 * stored on the ItemEntity itself ({@link FcItemBridgeHolder}), so it lives and dies with it. Server thread
 * only.</p>
 */
public final class ItemEntityBridge extends btw.modern.EntityItem {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ItemEntityBridge");

    /** Bridges wrapped since the last end-of-level-tick flush (server thread only). */
    private static final List<ItemEntityBridge> TOUCHED = new ArrayList<>();
    private static long epoch;
    /** True while a flush or a bridge-initiated MC read runs: keeps the getItem() hook from re-entering. */
    private static boolean flushing;

    private static Field pickupDelayField;
    private static boolean pickupDelayResolved;
    private static int warnCount;
    private static int diagCount;

    private final ItemEntity real;
    private btw.modern.ItemStack fcStack;
    /** MC stack instance fcStack was converted from (reused while MC's stack is untouched). */
    private net.minecraft.world.item.ItemStack lastMc;
    private long touchedEpoch = -1L;

    // Snapshot taken after every sync / flush: flush writes back only what FC changed since.
    private btw.modern.ItemStack snapStack;
    private btw.modern.NBTTagCompound snapTag;
    private int snapItemId, snapDamage, snapCount, snapDelay;
    private double snapX, snapY, snapZ, snapMX, snapMY, snapMZ;

    private ItemEntityBridge(ItemEntity real, ServerLevel level) {
        super(null); // frozen EntityItem(World): 0.25 box, yOffset = height / 2 (vanilla/server EntityItem.java:64-71)
        this.real = real;
        this.worldObj = WorldBridge.getOrCreate(level);
    }

    /** The MC entity this view stands for. */
    public ItemEntity getRealEntity() {
        return real;
    }

    /**
     * A freshly synced FC view of {@code ie}, or null if it is removed, empty, client-side or its stack cannot
     * be mapped. Never throws (called from FC entity/block ticks).
     */
    public static ItemEntityBridge wrap(ItemEntity ie) {
        if (ie == null || ie.isRemoved() || !(ie.level() instanceof ServerLevel level)) return null;
        if (!(ie instanceof FcItemBridgeHolder holder)) return null; // ItemEntityMixin not applied
        try {
            ItemEntityBridge b;
            if (holder.btw$getFcBridge() instanceof ItemEntityBridge existing) {
                b = existing;
                b.flush();                 // land earlier FC changes before re-reading MC
                if (ie.isRemoved()) return null;
            } else {
                b = new ItemEntityBridge(ie, level);
                holder.btw$setFcBridge(b);
            }
            if (!b.syncFromReal(level)) return null;
            if (b.touchedEpoch != epoch) {
                b.touchedEpoch = epoch;
                TOUCHED.add(b);
            }
            if (diagCount < 40) {
                diagCount++;
                LOGGER.debug("[ITEM-BRIDGE] FC view of ItemEntity#{} {}", ie.getId(), b.lastMc);
            }
            return b;
        } catch (Throwable t) {
            warn("wrap failed for ItemEntity#" + ie.getId() + ": " + t);
            return null;
        }
    }

    /** ItemEntityMixin getItem() HEAD: land FC changes before MC reads the stack. */
    public static void onMcRead(Object bridge) {
        if (bridge instanceof ItemEntityBridge b && b.real.level() instanceof ServerLevel l
                && l.getServer().isSameThread()) {
            b.flush();
        }
    }

    private boolean syncFromReal(ServerLevel level) {
        net.minecraft.world.item.ItemStack mc = readMcStack();
        if (mc.isEmpty()) return false;
        // Reuse the FC stack while MC's is untouched (same instance and count): no per-query allocation.
        if (fcStack == null || mc != lastMc || fcStack.stackSize != mc.getCount()) {
            btw.modern.ItemStack converted = ItemStackHelper.toFcStack(mc);
            if (converted == null) return false;
            fcStack = converted;
        }
        lastMc = mc;
        worldObj = WorldBridge.getOrCreate(level);
        entityId = real.getId();
        ticksExisted = real.tickCount;
        age = real.getAge();
        rotationYaw = real.getYRot();
        rotationPitch = real.getXRot();
        prevPosX = real.xo;
        prevPosY = real.yo + yOffset;
        prevPosZ = real.zo;
        refreshTransient();
        snapshotStack();
        return true;
    }

    /** Position, box, motion, ground/dead state and pickup delay from MC (re-read on every liveness check). */
    private void refreshTransient() {
        AABB bb = real.getBoundingBox();
        boundingBox.setBounds(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
        // 1.5.2 convention posY = minY + yOffset (Entity.setPosition): frozen setPosition / moveEntity
        // (bellows lift, platforms) stay consistent with the MC box; written back as setPos(.., posY - yOffset, ..)
        posX = real.getX();
        posY = bb.minY + yOffset;
        posZ = real.getZ();
        Vec3 v = real.getDeltaMovement();
        motionX = v.x;
        motionY = v.y;
        motionZ = v.z;
        onGround = real.onGround();
        isDead = real.isRemoved();
        delayBeforeCanPickup = readPickupDelay(real);
        snapTransient();
    }

    private void snapTransient() {
        snapDelay = delayBeforeCanPickup;
        snapX = posX;
        snapY = posY;
        snapZ = posZ;
        snapMX = motionX;
        snapMY = motionY;
        snapMZ = motionZ;
    }

    private void snapshotStack() {
        snapStack = fcStack;
        snapItemId = fcStack.itemID;
        snapDamage = fcStack.getItemDamage();
        snapCount = fcStack.stackSize;
        snapTag = fcStack.getTagCompound();
    }

    private net.minecraft.world.item.ItemStack readMcStack() {
        boolean was = flushing;
        flushing = true;
        try {
            return real.getItem();
        } finally {
            flushing = was;
        }
    }

    /** Writes the FC-side changes made since the last snapshot back to the ItemEntity (exactly once). */
    public void flush() {
        if (flushing) return;
        flushing = true;
        try {
            if (real.isRemoved()) {
                isDead = true;
                return;
            }
            if (isDead) {
                real.discard();
                return;
            }
            btw.modern.ItemStack fc = fcStack;
            if (fc == null || fc.stackSize <= 0) {
                consumeAll();
                return;
            }
            if (fc != snapStack || fc.itemID != snapItemId || fc.getItemDamage() != snapDamage
                    || fc.getTagCompound() != snapTag) {
                // Replaced or re-typed (setEntityItemStack, frozen combineItems, hopper filters): full convert.
                net.minecraft.world.item.ItemStack mc = ItemStackHelper.toMcStack(fc);
                if (!mc.isEmpty()) {
                    real.setItem(mc);
                    lastMc = mc;
                } else {
                    warn("unconvertible FC stack id=" + fc.itemID + " - MC stack left unchanged");
                }
            } else if (fc.stackSize != snapCount) {
                // In-place count change (eaten, partly stored): a DELTA on MC's live stack.
                net.minecraft.world.item.ItemStack cur = real.getItem();
                int n = cur.getCount() + (fc.stackSize - snapCount);
                if (n <= 0) {
                    consumeAll();
                    return;
                }
                net.minecraft.world.item.ItemStack copy = cur.copy();
                copy.setCount(n);
                real.setItem(copy);        // new instance -> DATA_ITEM dirty -> clients see the new count
                lastMc = copy;
                if (diagCount < 40) {
                    diagCount++;
                    LOGGER.debug("[ITEM-BRIDGE] write-back ItemEntity#{} count {} -> {}",
                            real.getId(), cur.getCount(), n);
                }
            }
            if (delayBeforeCanPickup != snapDelay) {
                real.setPickUpDelay(Math.max(0, delayBeforeCanPickup));
            }
            if (motionX != snapMX || motionY != snapMY || motionZ != snapMZ) {
                real.setDeltaMovement(motionX, motionY, motionZ);
                real.hasImpulse = true;
            }
            if (posX != snapX || posY != snapY || posZ != snapZ) {
                real.setPos(posX, posY - yOffset, posZ);
                real.hasImpulse = true;
            }
            snapshotStack();
            snapTransient();
        } catch (Throwable t) {
            warn("flush failed for ItemEntity#" + real.getId() + ": " + t);
        } finally {
            flushing = false;
        }
    }

    /**
     * FC used the whole stack: empty it BEFORE discarding, so a getItem() already in flight (the playerTouch
     * whose read triggered this flush) cannot hand the consumed items out.
     */
    private void consumeAll() {
        isDead = true;
        real.setItem(net.minecraft.world.item.ItemStack.EMPTY);
        real.discard();
    }

    @Override
    public btw.modern.ItemStack getEntityItem() {
        return fcStack;
    }

    @Override
    public void setEntityItemStack(btw.modern.ItemStack stack) {
        fcStack = stack;
    }

    @Override
    public void setDead() {
        super.setDead();
        if (real != null && !real.isRemoved()) {
            real.discard();
        }
    }

    /** Long-held references (FCEntityAIMoveToLooseFood.temptingItem) re-read MC on every check. */
    @Override
    public boolean isEntityAlive() {
        if (isDead || real.isRemoved()) {
            isDead = true;
            return false;
        }
        flush();
        if (real.isRemoved()) {
            isDead = true;
            return false;
        }
        refreshTransient();
        return true;
    }

    /** Never ticked: MC's ItemEntity runs the physics. */
    @Override
    public void onUpdate() {
    }

    /** MC ItemEntity.playerTouch owns pickup; running the frozen 1.5.2 pickup too would duplicate it. */
    @Override
    public void onCollideWithPlayer(btw.modern.EntityPlayer player) {
    }

    private static int readPickupDelay(ItemEntity ie) {
        if (!pickupDelayResolved) {
            pickupDelayResolved = true;
            try {
                pickupDelayField = ObfuscationReflectionHelper.findField(ItemEntity.class, "f_31986_"); // pickupDelay
            } catch (Throwable t) {
                warn("pickupDelay field unavailable, using hasPickUpDelay(): " + t);
            }
        }
        if (pickupDelayField != null) {
            try {
                return pickupDelayField.getInt(ie);
            } catch (Throwable ignored) {
            }
        }
        return ie.hasPickUpDelay() ? 1 : 0;
    }

    private static void warn(String msg) {
        if (warnCount < 20) {
            warnCount++;
            LOGGER.warn("[ITEM-BRIDGE] {}", msg);
        }
    }

    // ================================================================
    // U3c: items resting ON an FC block
    // ================================================================

    /** FC block classes that override Block.onEntityCollidedWithBlock (only those can react to an item). */
    private static final ClassValue<Boolean> OVERRIDES_COLLISION = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            try {
                return c.getMethod("onEntityCollidedWithBlock", btw.modern.World.class, int.class, int.class,
                        int.class, btw.modern.Entity.class).getDeclaringClass() != btw.modern.Block.class;
            } catch (Throwable t) {
                return false;
            }
        }
    };

    /**
     * 1.5.2 EntityItem.doBlockCollisions (vanilla/server EntityItem.java:483-500) scans from floor(minY - 0.01)
     * "so that blocks like Hoppers get collision events with items on top"; MC 1.20.1 checkInsideBlocks starts
     * at minY + 1e-7, so the supporting block (BTW hopper, cauldron, crucible, planter) never got entityInside.
     * Visits only that extra layer, so MC's own entityInside calls are never duplicated. Every server tick
     * (ItemEntityMixin tick RETURN), as 1.5.2 ran it from every EntityItem.onUpdate -> moveEntity.
     */
    public static void collideWithSupportingFcBlocks(ItemEntity ie, ServerLevel level) {
        try {
            AABB bb = ie.getBoundingBox();
            int j = Mth.floor(bb.minY - 0.01D);
            if (j == Mth.floor(bb.minY + 1.0E-7D)) return;           // not resting on a block top
            int i0 = Mth.floor(bb.minX + 0.001D), i1 = Mth.floor(bb.maxX - 0.001D);
            int k0 = Mth.floor(bb.minZ + 0.001D), k1 = Mth.floor(bb.maxZ - 0.001D);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            ItemEntityBridge bridge = null;
            for (int i = i0; i <= i1; i++) {
                for (int k = k0; k <= k1; k++) {
                    pos.set(i, j, k);
                    if (!level.isLoaded(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    btw.modern.Block fcBlock = ProxyRegistry.getFcBlock(state.getBlock());
                    if (fcBlock == null || !OVERRIDES_COLLISION.get(fcBlock.getClass())) continue;
                    if (bridge == null && (bridge = wrap(ie)) == null) return;
                    try {
                        fcBlock.onEntityCollidedWithBlock(bridge.worldObj, i, j, k, bridge);
                    } catch (Throwable t) {
                        warn("supporting-block collision " + fcBlock.getClass().getSimpleName() + " threw: " + t);
                    }
                    if (ie.isRemoved()) return;                        // collected / converted
                }
            }
            if (bridge != null) bridge.flush();
        } catch (Throwable t) {
            warn("supporting-block scan failed for ItemEntity#" + ie.getId() + ": " + t);
        }
    }

    // ================================================================
    // End-of-tick safety net
    // ================================================================

    /** Flushes every bridge wrapped this level tick. Package-private: called from {@link TickFlush}. */
    static void flushTouched() {
        epoch++;
        if (TOUCHED.isEmpty()) return;
        ItemEntityBridge[] batch = TOUCHED.toArray(new ItemEntityBridge[0]);
        TOUCHED.clear();
        for (ItemEntityBridge b : batch) {
            b.flush();
        }
    }

    /** Drops pending bridges once the server has stopped: each pins its ItemEntity, level and server (RE2). */
    static void clearTouched() {
        TOUCHED.clear();
    }

    @Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID)
    public static final class TickFlush {
        private TickFlush() {
        }

        @SubscribeEvent
        public static void onLevelTick(TickEvent.LevelTickEvent event) {
            if (event.side == LogicalSide.SERVER && event.phase == TickEvent.Phase.END) {
                flushTouched();
            }
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            clearTouched();
        }
    }
}
