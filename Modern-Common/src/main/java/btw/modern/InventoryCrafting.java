package btw.modern;

public class InventoryCrafting implements IInventory {
    private ItemStack[] stackList;
    private int inventoryWidth;
    private Container eventHandler;

    public InventoryCrafting(Container container, int width, int height) {
        this.stackList = new ItemStack[width * height];
        this.eventHandler = container;
        this.inventoryWidth = width;
    }

    public int getSizeInventory() { return stackList.length; }

    // FCMOD-INTEGRATION(1.20.1): recipes: the Forge workbench fallback rebuilds this grid as a 1.20.1 crafting
    // container (btw.forge.recipes.WorkbenchFallback) and needs its shape; 1.5.2 has no accessor for it.
    /** Grid width in slots: 3 for the FC workbench and work stump, 4 for the soulforge. Slot index = x + y * width. */
    public int getInventoryWidth() { return inventoryWidth; }
    public ItemStack getStackInSlot(int slot) { return slot >= 0 && slot < stackList.length ? stackList[slot] : null; }
    public ItemStack getStackInRowAndColumn(int row, int col) {
        if (row >= 0 && row < inventoryWidth) {
            int index = row + col * inventoryWidth;
            return getStackInSlot(index);
        }
        return null;
    }
    public ItemStack decrStackSize(int slot, int amount) {
        if (slot >= 0 && slot < stackList.length && stackList[slot] != null) {
            if (stackList[slot].stackSize <= amount) {
                ItemStack stack = stackList[slot];
                stackList[slot] = null;
                onInventoryChanged();
                return stack;
            } else {
                ItemStack split = stackList[slot].splitStack(amount);
                if (stackList[slot].stackSize == 0) {
                    stackList[slot] = null;
                }
                onInventoryChanged();
                return split;
            }
        }
        return null;
    }
    public ItemStack getStackInSlotOnClosing(int slot) {
        if (slot >= 0 && slot < stackList.length && stackList[slot] != null) {
            ItemStack stack = stackList[slot];
            stackList[slot] = null;
            return stack;
        }
        return null;
    }
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (slot >= 0 && slot < stackList.length) stackList[slot] = stack;
        onInventoryChanged();
    }
    public String getInvName() { return "container.crafting"; }
    public boolean isInvNameLocalized() { return false; }
    public int getInventoryStackLimit() { return 64; }
    public void onInventoryChanged() {
        if (eventHandler != null && !inCallback) {
            inCallback = true;
            try {
                eventHandler.onCraftMatrixChanged(this);
            } finally {
                inCallback = false;
            }
        }
    }
    private boolean inCallback = false;
    public boolean isUseableByPlayer(EntityPlayer player) { return true; }
    public void openChest() {}
    public void closeChest() {}
    public boolean isItemValidForSlot(int slot, ItemStack stack) { return true; }
}
