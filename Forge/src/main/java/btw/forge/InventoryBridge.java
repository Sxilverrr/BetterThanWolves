package btw.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bridges {@link Inventory} to {@link btw.modern.InventoryPlayer}.
 * FC code accesses inventory slots, current item, and armor through
 * btw.modern.InventoryPlayer fields and methods.
 *
 * <p>{@link #sync()} snapshots every MC slot into an FC stack and records a per-slot baseline.
 * Write-back is selective: only a slot whose FC stack FC replaced or changed since the baseline is
 * written to the real inventory, and a same-item change is patched onto the existing MC stack in
 * place. 1.5.2 edits the player's ItemStack[] in place (InventoryPlayer.java:214 storePartialItemStack,
 * :250 consumeInventoryItem; ItemStack.damageItem :323-345) and never rewrites a slot it did not
 * touch, so an untouched slot must keep its exact MC stack (subtype, NBT, anything FC cannot
 * represent).</p>
 */
public class InventoryBridge extends btw.modern.InventoryPlayer {
    private static final Logger LOGGER = LogManager.getLogger("BTW-InventoryBridge");

    private final Inventory inventory;

    // Per-slot baseline captured by sync()/write-back. Slot i < mainInventory.length is the main
    // inventory, the rest is armor -- the 1.5.2 InventoryPlayer slot layout, same as MC Inventory.
    private final int slotCount = mainInventory.length + armorInventory.length;
    private final btw.modern.ItemStack[] baseFc = new btw.modern.ItemStack[slotCount];
    private final net.minecraft.world.item.ItemStack[] baseMc = new net.minecraft.world.item.ItemStack[slotCount];
    private final int[] baseId = new int[slotCount];
    private final int[] baseSize = new int[slotCount];
    private final int[] baseDamage = new int[slotCount];
    private final CompoundTag[] baseTag = new CompoundTag[slotCount];
    /**
     * The FC stack sync() handed out per slot. Unlike baseFc it is not moved by write-through
     * rebaselining, so writeBack can tell "FC still holds the synced stack" from "FC replaced it".
     */
    private final btw.modern.ItemStack[] syncFc = new btw.modern.ItemStack[slotCount];
    /** Slots whose MC stack could not be converted: FC sees them empty, write-back never touches them. */
    private final boolean[] locked = new boolean[slotCount];

    private static long lastWarnMs;
    private static long lastRelocateWarnMs;

    public InventoryBridge(Inventory inventory) {
        super(null); // btw.modern.InventoryPlayer(EntityPlayer) constructor
        this.inventory = inventory;
        sync();
    }

    /** Copy current inventory state to FC fields */
    public void sync() {
        this.currentItem = inventory.selected;

        // Sync main inventory (36 slots)
        for (int i = 0; i < mainInventory.length && i < inventory.items.size(); i++) {
            syncSlot(i, inventory.items.get(i));
        }

        // Sync armor inventory (4 slots)
        for (int i = 0; i < armorInventory.length && i < inventory.armor.size(); i++) {
            syncSlot(mainInventory.length + i, inventory.armor.get(i));
        }
    }

    private void syncSlot(int i, net.minecraft.world.item.ItemStack modern) {
        btw.modern.ItemStack fc;
        CompoundTag fingerprint;
        try {
            fc = ItemStackHelper.toFcStack(modern);
            // sync() runs on every PlayerBridge.getOrCreate: keep the previous baseline snapshot
            // while the MC tag still equals it (compare, no copy). A baseline is never mutated.
            CompoundTag prev = baseTag[i];
            fingerprint = prev != null && ItemStackHelper.mcTagMatches(modern, prev)
                    ? prev : ItemStackHelper.mcTagFingerprint(modern);
            locked[i] = false;
        } catch (Throwable t) {
            // FC must not see (and later overwrite) a slot it could not read
            fc = null;
            fingerprint = null;
            locked[i] = modern != null && !modern.isEmpty();
            warn("sync slot " + i + " (" + modern + ")", t);
        }
        setSnapshot(i, fc);
        syncFc[i] = fc;
        rebaseline(i, modern, fingerprint);
    }

    @Override
    public btw.modern.ItemStack getCurrentItem() {
        // Return the FC snapshot from mainInventory[] so FC code that
        // modifies the stack (stackSize--, damageItem, etc.) modifies
        // the same object that writeBackAll() will read.
        int selected = inventory.selected;
        if (selected >= 0 && selected < mainInventory.length) {
            return mainInventory[selected];
        }
        return null;
    }

    @Override
    public btw.modern.ItemStack getStackInSlot(int slot) {
        // Return FC snapshots directly — same reason as getCurrentItem.
        if (slot >= 0 && slot < mainInventory.length) {
            return mainInventory[slot];
        }
        int armorSlot = slot - mainInventory.length;
        if (armorSlot >= 0 && armorSlot < armorInventory.length) {
            return armorInventory[armorSlot];
        }
        return null;
    }

    @Override
    public void setInventorySlotContents(int slot, btw.modern.ItemStack stack) {
        if (slot < 0 || slot >= getSizeInventory()) return;
        // FC snapshot first, then write that one slot through to the REAL MC inventory
        super.setInventorySlotContents(slot, stack);
        writeBackSlot(slot);
    }

    @Override
    public btw.modern.ItemStack decrStackSize(int slot, int count) {
        // 1.5.2 InventoryPlayer.decrStackSize (vanilla/server InventoryPlayer.java:340): split the
        // slot's own stack, null it when emptied. Only this slot is written back; a full re-sync
        // here used to discard FC's pending in-place edits in every other slot and detach the
        // stacks FC still held.
        if (slot < 0 || slot >= getSizeInventory()) return null;
        btw.modern.ItemStack removed = super.decrStackSize(slot, count);
        writeBackSlot(slot);
        return removed;
    }

    @Override
    public btw.modern.ItemStack getStackInSlotOnClosing(int slot) {
        if (slot < 0 || slot >= getSizeInventory()) return null;
        btw.modern.ItemStack stack = super.getStackInSlotOnClosing(slot);
        writeBackSlot(slot);
        return stack;
    }

    @Override
    public int getSizeInventory() {
        return mainInventory.length + armorInventory.length;
    }

    /**
     * Writes FC ItemStack state back to the real MC inventory slot.
     * Call after FC code that may modify items (damageItem, stackSize changes).
     *
     * @param fcStack the FC ItemStack that was modified (from getCurrentItem etc.)
     * @param slot the inventory slot index, or -1 for current held item
     */
    public void writeBack(btw.modern.ItemStack fcStack, int slot) {
        if (slot < 0) slot = inventory.selected;
        if (slot < 0 || slot >= mainInventory.length) return;

        if (fcStack != null && fcStack != mainInventory[slot]) {
            // Detached FC stack (a re-sync replaced the stack FC was holding): adopt it only while
            // the slot still holds the stack sync() handed out. If FC replaced or cleared the slot
            // since (destroyCurrentEquippedItem, setInventorySlotContents with a new stack of the
            // same id), syncFc no longer matches -- baseFc does, after write-through rebaselining --
            // and the slot's own FC state is what gets written.
            if (mainInventory[slot] == syncFc[slot] && syncFc[slot] != null && fcStack.itemID == baseId[slot]) {
                mainInventory[slot] = fcStack;
            }
        }
        writeBackSlot(slot);
    }

    /**
     * Writes the current held item's FC state back to MC inventory.
     */
    public void writeBackCurrentItem(btw.modern.ItemStack fcStack) {
        writeBack(fcStack, inventory.selected);
    }

    /**
     * Flushes the FC inventory snapshot back to the real MC inventory.
     *
     * <p>FC code may mutate ItemStack objects in mainInventory[] directly
     * (e.g., {@code slotStack.stackSize += placeCount} during merge), which
     * bypasses the write-through in setInventorySlotContents/decrStackSize.
     * Call this after any FC operation that may have modified player inventory.
     * Only slots FC changed since the last sync are written.</p>
     */
    public void writeBackAll() {
        for (int i = 0; i < slotCount; i++) writeBackSlot(i);
    }

    private btw.modern.ItemStack snapshot(int i) {
        return i < mainInventory.length ? mainInventory[i] : armorInventory[i - mainInventory.length];
    }

    private void setSnapshot(int i, btw.modern.ItemStack fc) {
        if (i < mainInventory.length) {
            mainInventory[i] = fc;
        } else {
            armorInventory[i - mainInventory.length] = fc;
        }
    }

    private void rebaseline(int i, net.minecraft.world.item.ItemStack mc, CompoundTag tagFingerprint) {
        btw.modern.ItemStack fc = snapshot(i);
        baseFc[i] = fc;
        baseMc[i] = mc;
        baseId[i] = fc != null ? fc.itemID : 0;
        baseSize[i] = fc != null ? fc.stackSize : 0;
        baseDamage[i] = fc != null ? fc.getItemDamage() : 0;
        baseTag[i] = fc != null ? tagFingerprint : null;
    }

    /** True when FC replaced slot i or changed its id/count/damage/NBT since the baseline. */
    private boolean fcChanged(int i) {
        btw.modern.ItemStack fc = snapshot(i);
        if (fc != baseFc[i]) return true;
        if (fc == null) return false;
        if (fc.itemID != baseId[i] || fc.stackSize != baseSize[i] || fc.getItemDamage() != baseDamage[i]) return true;
        return !ItemStackHelper.fcTagMatches(fc, baseTag[i]);
    }

    /**
     * Writes slot i to the real inventory only if FC changed it since the baseline. A change to the
     * same item (count, durability, NBT) is patched onto the live MC stack so the modern identity
     * survives; only a different item/subtype is rebuilt through ItemStackHelper.toMcStack.
     */
    private void writeBackSlot(int i) {
        if (i < 0 || i >= slotCount) return;
        try {
            if (!fcChanged(i)) return;
            boolean armor = i >= mainInventory.length;
            net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> list =
                    armor ? inventory.armor : inventory.items;
            int idx = armor ? i - mainInventory.length : i;
            if (idx >= list.size()) return;
            btw.modern.ItemStack fc = snapshot(i);
            if (locked[i]) {
                // FC never saw this slot's real stack, so it is never overwritten. FC sees the slot
                // empty, though, and may have put an item there (InventoryPlayer.addItemStackToInventory
                // fills the first null slot, as SlotCrafting does with returned buckets,
                // vanilla/server SlotCrafting.java:156-179): move that item, don't lose it. Relocate
                // first: if it throws, the item stays in the snapshot and the next write-back retries.
                if (fc != null && fc.stackSize > 0) relocate(fc, i);
                setSnapshot(i, null);
                rebaseline(i, list.get(idx), null);
                return;
            }
            CompoundTag tagNow = ItemStackHelper.fcTagFingerprint(fc);
            net.minecraft.world.item.ItemStack live = list.get(idx);
            if (fc == null || fc.stackSize <= 0) {
                list.set(idx, net.minecraft.world.item.ItemStack.EMPTY);
            } else if (live == baseMc[i] && !live.isEmpty() && baseFc[i] != null && fc.itemID == baseId[i]
                    && (ItemStackHelper.isDamageable(live) || fc.getItemDamage() == baseDamage[i])) {
                // same item, FC changed count/durability/NBT: patch in place
                ItemStackHelper.applyFcState(live, fc, !java.util.Objects.equals(tagNow, baseTag[i]));
            } else {
                list.set(idx, ItemStackHelper.toMcStack(fc)); // FC put a different item / subtype here
            }
            rebaseline(i, list.get(idx), tagNow);
        } catch (Throwable t) {
            warn("write-back slot " + i, t);
        }
    }

    /**
     * Puts an FC stack that FC placed into locked slot {@code from} into a main-inventory slot that
     * both FC and MC see empty (and FC has not touched since the baseline), so the two views stay
     * consistent; drops it at the player when there is none.
     */
    private void relocate(btw.modern.ItemStack fc, int from) {
        net.minecraft.world.item.ItemStack mc = ItemStackHelper.toMcStack(fc);
        if (mc.isEmpty()) {
            warnRelocate("FC item id " + fc.itemID + " put into locked slot " + from + " has no MC item; discarded");
            return;
        }
        for (int j = 0; j < mainInventory.length && j < inventory.items.size(); j++) {
            if (j == from || locked[j] || mainInventory[j] != null || baseFc[j] != null
                    || !inventory.items.get(j).isEmpty()) continue;
            mainInventory[j] = fc;
            inventory.items.set(j, mc);
            rebaseline(j, mc, ItemStackHelper.fcTagFingerprint(fc));
            warnRelocate("FC put " + mc + " into locked slot " + from + "; moved to slot " + j);
            return;
        }
        if (!inventory.player.level().isClientSide) {
            inventory.player.drop(mc, false, false);
            warnRelocate("FC put " + mc + " into locked slot " + from + " with no free slot; dropped at the player");
        }
    }

    private static void warnRelocate(String what) {
        long now = System.currentTimeMillis();
        if (now - lastRelocateWarnMs < 10_000L) return;
        lastRelocateWarnMs = now;
        LOGGER.warn("[INV-WB] {} (further within 10s suppressed)", what);
    }

    private static void warn(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 10_000L) return;
        lastWarnMs = now;
        LOGGER.warn("[INV-WB] {} failed: {} (further failures within 10s suppressed)", what, t.toString(), t);
    }

    /**
     * FC's harvest check with material fallback.
     * The base InventoryPlayer.canHarvestBlock only checks the held item,
     * returning false for empty hand. Vanilla MC 1.5.2 also checked
     * block.blockMaterial.isToolNotRequired() — blocks like cooked bricks
     * (Material.circuits) don't require a tool to harvest.
     */
    @Override
    public boolean canHarvestBlock(btw.modern.World world, btw.modern.Block block, int i, int j, int k) {
        // Material check first: blocks that don't require tools are always harvestable
        if (block.blockMaterial != null && block.blockMaterial.isToolNotRequired()) {
            return true;
        }
        return super.canHarvestBlock(world, block, i, j, k);
    }

    /** Returns the underlying Forge inventory. */
    public Inventory getRealInventory() {
        return inventory;
    }
}
