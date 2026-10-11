package btw.forge;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Adapts an FC {@link btw.modern.IInventory} to MC 1.20.1
 * {@link net.minecraft.world.Container} so that it can be used
 * with the modern inventory/menu system.
 *
 * <p>Item stacks are converted on-the-fly between FC and MC formats
 * using {@link ItemStackHelper}. The FC inventory remains the
 * authoritative data store; this adapter is a live view.</p>
 *
 * <p>Live view: {@link #getItem} hands out one stable MC stack ("mirror") per FC slot.
 * AbstractContainerMenu edits Slot.getItem() in place and then only calls setChanged()
 * (moveItemStackTo, AbstractContainerMenu.java:612-627; quickMoveStack), exactly as 1.5.2
 * Container.AttemptToMergeWithSlot edits the slot's live stack and calls onSlotChanged
 * (vanilla/server Container.java:837-873). {@link #setChanged} folds such edits back into the FC
 * inventory; a fresh copy per getItem() call silently threw them away (shift-click into a partial
 * stack deleted items, a partial shift-click out duplicated them).</p>
 */
public class InventoryAdapter implements net.minecraft.world.Container {

    private static final Logger LOGGER = LogManager.getLogger("BTW-InventoryAdapter");

    private final btw.modern.IInventory fcInventory;

    /** One stable MC stack per FC slot, valid while the FC slot still holds the stack it mirrors. */
    private static final class Mirror {
        final btw.modern.ItemStack fcRef;
        final int fcId, fcSize, fcDamage;
        final ItemStack mc, pristine;

        Mirror(btw.modern.ItemStack fc, ItemStack mc) {
            this.fcRef = fc;
            this.fcId = fc != null ? fc.itemID : 0;
            this.fcSize = fc != null ? fc.stackSize : 0;
            this.fcDamage = fc != null ? fc.getItemDamage() : 0;
            this.mc = mc;
            this.pristine = mc.copy();
        }

        /** FC still holds the mirrored stack, unchanged in id/count/damage. */
        boolean tracks(btw.modern.ItemStack fc) {
            return fc == fcRef && (fc == null
                    || (fc.itemID == fcId && fc.stackSize == fcSize && fc.getItemDamage() == fcDamage));
        }
    }

    private final Mirror[] mirrors;
    private long lastWarnMs;

    public InventoryAdapter(btw.modern.IInventory fcInventory) {
        this.fcInventory = fcInventory;
        this.mirrors = new Mirror[Math.max(0, fcInventory.getSizeInventory())];
    }

    /**
     * Returns the underlying FC inventory.
     */
    public btw.modern.IInventory getFcInventory() {
        return fcInventory;
    }

    @Override
    public int getContainerSize() {
        return fcInventory.getSizeInventory();
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < fcInventory.getSizeInventory(); i++) {
            btw.modern.ItemStack fcStack = fcInventory.getStackInSlot(i);
            if (fcStack != null && fcStack.stackSize > 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        btw.modern.ItemStack fcStack = fcInventory.getStackInSlot(slot);
        if (slot < 0 || slot >= mirrors.length) return ItemStackHelper.toMcStack(fcStack);
        Mirror m = mirrors[slot];
        if (m != null && m.tracks(fcStack)) return m.mc;
        ItemStack mc = ItemStackHelper.toMcStack(fcStack);
        // EMPTY (no FC stack, or one MC cannot show) is never edited in place: no mirror needed
        mirrors[slot] = mc.isEmpty() ? null : new Mirror(fcStack, mc);
        return mc;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        flushSlot(slot);
        forget(slot);
        btw.modern.ItemStack fcStack = fcInventory.decrStackSize(slot, amount);
        return ItemStackHelper.toMcStack(fcStack);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        flushSlot(slot);
        forget(slot);
        btw.modern.ItemStack fcStack = fcInventory.getStackInSlotOnClosing(slot);
        return ItemStackHelper.toMcStack(fcStack);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        // replaces the slot (stack may be the old mirror itself, e.g. Slot.safeInsert grow + set)
        forget(slot);
        btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
        fcInventory.setInventorySlotContents(slot, fcStack);
    }

    @Override
    public void setChanged() {
        flush();
        fcInventory.onInventoryChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        // FC validity checks are done at the Container level, not inventory level.
        // Always return true here; FCContainerMenu handles stillValid.
        return true;
    }

    @Override
    public void clearContent() {
        java.util.Arrays.fill(mirrors, null);
        for (int i = 0; i < fcInventory.getSizeInventory(); i++) {
            fcInventory.setInventorySlotContents(i, null);
        }
    }

    @Override
    public int getMaxStackSize() {
        return fcInventory.getInventoryStackLimit();
    }

    /**
     * Called once per menu broadcast (FCContainerMenu.broadcastChanges): folds pending MC edits
     * into FC, then drops every mirror so the slot sync re-reads FC. That catches FC's own in-place
     * NBT edits (e.g. Infernal Enchanter addEnchantment) which {@link Mirror#tracks} cannot see.
     */
    public void flushAndForget() {
        if (flush()) fcInventory.onInventoryChanged();
        java.util.Arrays.fill(mirrors, null);
    }

    private void forget(int slot) {
        if (slot >= 0 && slot < mirrors.length) mirrors[slot] = null;
    }

    private boolean flush() {
        boolean wrote = false;
        for (int i = 0; i < mirrors.length; i++) wrote |= flushSlot(i);
        return wrote;
    }

    /**
     * Writes an MC in-place edit of slot i's mirror into the FC inventory. A count change on the
     * same item is applied to FC's stack in place (as 1.5.2 does); anything else replaces the
     * slot. Never writes over a slot FC changed after the mirror was taken (FC wins).
     */
    private boolean flushSlot(int i) {
        if (i < 0 || i >= mirrors.length) return false;
        Mirror m = mirrors[i];
        if (m == null || ItemStack.matches(m.mc, m.pristine)) return false; // MC did not touch it
        btw.modern.ItemStack fc = fcInventory.getStackInSlot(i);
        if (!m.tracks(fc)) {
            mirrors[i] = null;
            LOGGER.debug("[INV-ADAPT] slot {} changed under an MC edit; FC wins", i);
            return false;
        }
        try {
            if (m.mc.isEmpty()) {
                fcInventory.setInventorySlotContents(i, null);
            } else if (fc != null && ItemStack.isSameItemSameTags(m.mc, m.pristine)) {
                fc.stackSize = m.mc.getCount(); // in place, as 1.5.2 Container.AttemptToMergeWithSlot
            } else {
                fcInventory.setInventorySlotContents(i, ItemStackHelper.toFcStack(m.mc));
            }
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastWarnMs > 10_000L) {
                lastWarnMs = now;
                LOGGER.warn("[INV-ADAPT] flush of slot {} into {} failed: {}", i,
                        fcInventory.getClass().getSimpleName(), t.toString(), t);
            }
        }
        btw.modern.ItemStack now = fcInventory.getStackInSlot(i);
        mirrors[i] = (now != null && now == fc && !m.mc.isEmpty()) ? new Mirror(now, m.mc) : null;
        return true;
    }
}
