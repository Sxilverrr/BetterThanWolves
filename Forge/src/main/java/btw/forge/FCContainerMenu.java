package btw.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MC 1.20.1 {@link AbstractContainerMenu} that wraps an FC
 * {@link btw.modern.Container}.
 *
 * <h3>Architecture: FC is sole authority</h3>
 * <p>ALL slot interaction logic runs through FC's {@code Container.slotClick()}.
 * The client predicts via vanilla logic for instant feedback; the server (FC)
 * is authoritative and {@code broadcastChanges()} corrects any mismatches.</p>
 *
 * <h3>Data sync</h3>
 * <p>FC containers sync UI state (cook progress, enchantment levels, etc.)
 * via the {@link btw.modern.ICrafting} interface. The PlayerBridge implements
 * ICrafting and writes received values into this menu's {@link #fcData} array,
 * which is backed by MC {@link DataSlot}s — MC automatically detects changes
 * and pushes them to the client.</p>
 *
 * <p>For FC containers that compute state client-side (e.g., enchantment
 * levels), {@link #pushFcComputedData()} reads those fields via reflection
 * and pushes them as additional data slots.</p>
 */
public class FCContainerMenu extends AbstractContainerMenu {

    private static final Logger LOGGER = LogManager.getLogger("BTW-FCContainerMenu");

    /**
     * FC hook failures seen so far, keyed by hook + exception + throw site (triage HAI-10). The first
     * failure at a site logs one WARN with its stack trace; repeats are only counted, with a one-line
     * WARN at 10, 100, 1000, ... occurrences. A hook failing every broadcastChanges tick therefore
     * neither spams the log nor hides how often it fails, and no site is masked by another's limit.
     */
    private static final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> FC_FAILURES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void warnFcFailure(String what, Throwable t) {
        // Key and log the real failure, not a wrapper: frozen 1.5.2 Entity/EntityLiving wrap theirs in a
        // btw.modern.ReportedException (FCPuppetSupport.unwrap, triage ML-1), whose own site and stack would
        // hide it; its getCause() reaches the real failure only as the last "Caused by".
        Throwable root = FCPuppetSupport.unwrap(t);
        String site = throwSite(root);
        long n = FC_FAILURES.computeIfAbsent(what + '|' + root.getClass().getName() + '|' + site,
                k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        if (n == 1) {
            String wrapped = root != t ? " (wrapped in " + t.getClass().getName() + ": " + t.getMessage() + ")" : "";
            LOGGER.warn("{} failed at {}{} (stack trace logged once per throw site): {}",
                    what, site, wrapped, root.toString(), root);
        } else if (isPowerOfTen(n)) {
            LOGGER.warn("{} failed at {} {} times so far (last: {})", what, site, n, root.toString());
        }
    }

    /**
     * First stack frame outside the JDK of {@code t} (the unwrapped failure, see warnFcFailure), so FC
     * failures surfacing through e.g. ArrayList get distinct sites.
     */
    private static String throwSite(Throwable t) {
        StackTraceElement[] trace = t.getStackTrace();
        for (StackTraceElement e : trace) {
            String c = e.getClassName();
            if (!c.startsWith("java.") && !c.startsWith("jdk.") && !c.startsWith("sun.")) {
                return c + "." + e.getMethodName() + ":" + e.getLineNumber();
            }
        }
        return trace.length > 0 ? trace[0].getClassName() + "." + trace[0].getMethodName() : "<no stack>";
    }

    private static boolean isPowerOfTen(long n) {
        long p = 10;
        while (p < n) p *= 10;
        return p == n;
    }

    /** Number of data slots reserved for FC progress bars + computed data. */
    private static final int FC_DATA_SLOT_COUNT = 16;

    private final btw.modern.Container fcContainer;
    private final PlayerBridge fcPlayer;

    /** Server side: the live FC-inventory views behind the ContainerMappedSlots (empty on the client). */
    private final java.util.List<InventoryAdapter> adapters = new java.util.ArrayList<>();

    /**
     * FC container data array. Slots 0+ are used by FC's progress bar system
     * (sendProgressBarUpdate) and by pushFcComputedData() for container-specific
     * computed fields like enchantment levels.
     *
     * <p>Backed by MC DataSlots — changes are auto-synced to the client.</p>
     */
    private final int[] fcData = new int[FC_DATA_SLOT_COUNT];

    /** FC container type name (e.g., "FCContainerSoulforge") for texture selection. */
    private String containerType = "";

    public String getContainerType() { return containerType; }

    /** Client-side merchant recipe data, received via MerchantRecipesSync packet. */
    private java.util.List<MerchantRecipeInfo> merchantRecipes = new java.util.ArrayList<>();
    private int selectedRecipeIndex = 0;

    public record MerchantRecipeInfo(int buy1Id, int buy1Damage, int buy1Count,
                                      int buy2Id, int buy2Damage, int buy2Count,
                                      int sellId, int sellDamage, int sellCount,
                                      boolean expired) {}

    public java.util.List<MerchantRecipeInfo> getMerchantRecipes() { return merchantRecipes; }
    public int getSelectedRecipeIndex() { return selectedRecipeIndex; }
    public void setSelectedRecipeIndex(int idx) { selectedRecipeIndex = idx; }

    public void setMerchantRecipes(net.minecraft.nbt.CompoundTag tag) {
        merchantRecipes.clear();
        if (tag == null) return;
        int count = tag.getInt("count");
        for (int i = 0; i < count; i++) {
            if (!tag.contains("recipe" + i)) continue;
            net.minecraft.nbt.CompoundTag r = tag.getCompound("recipe" + i);
            int buy1Id = getItemId(r, "buy");
            int buy1Dmg = getItemDamage(r, "buy");
            int buy1Count = getItemCount(r, "buy");
            int buy2Id = getItemId(r, "buyB");
            int buy2Dmg = getItemDamage(r, "buyB");
            int buy2Count = getItemCount(r, "buyB");
            int sellId = getItemId(r, "sell");
            int sellDmg = getItemDamage(r, "sell");
            int sellCount = getItemCount(r, "sell");
            boolean expired = r.contains("uses") && r.contains("maxUses")
                    && r.getInt("uses") >= r.getInt("maxUses");
            merchantRecipes.add(new MerchantRecipeInfo(
                    buy1Id, buy1Dmg, buy1Count,
                    buy2Id, buy2Dmg, buy2Count,
                    sellId, sellDmg, sellCount, expired));
        }
    }

    private static int getItemId(net.minecraft.nbt.CompoundTag recipe, String key) {
        if (!recipe.contains(key)) return 0;
        return recipe.getCompound(key).getShort("id");
    }

    private static int getItemCount(net.minecraft.nbt.CompoundTag recipe, String key) {
        if (!recipe.contains(key)) return 0;
        return recipe.getCompound(key).getByte("Count");
    }

    private static int getItemDamage(net.minecraft.nbt.CompoundTag recipe, String key) {
        if (!recipe.contains(key)) return 0;
        return recipe.getCompound(key).getShort("Damage");
    }

    /** Returns the number of container rows for chest-like GUIs. */
    public int getFcNumRows() {
        // Server side: use FC container's slot list
        if (fcContainer != null && fcContainer.inventorySlots != null) {
            int chestSlots = fcContainer.inventorySlots.size() - 36;
            if (chestSlots > 0) return Math.max(1, (chestSlots + 8) / 9);
        }
        // Client side: use MC menu's slot list (populated from network)
        int totalSlots = this.slots.size();
        if (totalSlots > 36) {
            int chestSlots = totalSlots - 36;
            return Math.max(1, (chestSlots + 8) / 9);
        }
        return 3;
    }

    /** Read a synced FC data value (available on both server and client). */
    public int getFcData(int id) {
        return id >= 0 && id < fcData.length ? fcData[id] : 0;
    }

    /** Write an FC data value (called by PlayerBridge.sendProgressBarUpdate). */
    public void setFcData(int id, int value) {
        if (id >= 0 && id < fcData.length) {
            fcData[id] = value;
        }
    }

    /**
     * Server-side constructor: wraps an existing FC container.
     */
    public FCContainerMenu(int containerId, Inventory playerInv,
                           btw.modern.Container fcContainer, PlayerBridge fcPlayer) {
        super(BTWMenuTypes.FC_CONTAINER.get(), containerId);
        this.fcContainer = fcContainer;
        this.fcPlayer = fcPlayer;
        mirrorFcSlots();
        addFcDataSlots();
    }

    /**
     * Client-side constructor: called by the IContainerFactory when the
     * server opens a menu via NetworkHooks.openScreen.
     */
    public FCContainerMenu(int containerId, Inventory playerInv, FriendlyByteBuf buf) {
        super(BTWMenuTypes.FC_CONTAINER.get(), containerId);
        this.fcContainer = null;
        this.fcPlayer = null;
        this.containerType = buf.readUtf();

        int slotCount = buf.readVarInt();
        int containerSlots = buf.readVarInt();

        // Use sequential indices for ALL container slots to avoid collisions.
        // The result slot (slotIndex=0 in craftResult) and first grid slot
        // (slotIndex=0 in craftMatrix) must NOT share the same index in the
        // same SimpleContainer — that causes them to mirror each other.
        net.minecraft.world.SimpleContainer dummyContainer =
                new net.minecraft.world.SimpleContainer(Math.max(slotCount, containerSlots + 1));
        int nextContainerSlot = 0;

        for (int i = 0; i < slotCount; i++) {
            int x = buf.readShort();
            int y = buf.readShort();
            boolean isPlayerSlot = buf.readBoolean();
            int origSlotIndex = buf.readVarInt();

            if (isPlayerSlot) {
                this.addSlot(new Slot(playerInv, origSlotIndex, x, y));
            } else {
                boolean craftResult = buf.readBoolean();
                // Assign sequential index to avoid collisions between
                // different FC inventories (result vs grid vs others)
                int index = nextContainerSlot++;
                this.addSlot(craftResult ? new ClientCraftResultSlot(dummyContainer, index, x, y)
                        : new Slot(dummyContainer, index, x, y));
            }
        }

        addFcDataSlots();
    }

    /** Registers MC DataSlots backed by the fcData array. */
    private void addFcDataSlots() {
        for (int i = 0; i < FC_DATA_SLOT_COUNT; i++) {
            final int idx = i;
            addDataSlot(new DataSlot() {
                @Override public int get() { return fcData[idx]; }
                @Override public void set(int value) { fcData[idx] = value; }
            });
        }
    }

    /**
     * Reads the FC container's slot list and creates MC Slot wrappers.
     */
    private void mirrorFcSlots() {
        java.util.Map<btw.modern.IInventory, InventoryAdapter> byInv = new java.util.IdentityHashMap<>();

        for (int i = 0; i < fcContainer.inventorySlots.size(); i++) {
            btw.modern.Slot fcSlot = fcContainer.inventorySlots.get(i);
            btw.modern.IInventory fcInv = fcSlot.inventory;

            if (fcInv instanceof btw.modern.InventoryPlayer) {
                Inventory realInv = fcPlayer.getRealPlayer().getInventory();
                this.addSlot(new PlayerMappedSlot(realInv, fcSlot));
            } else {
                InventoryAdapter adapter = byInv.computeIfAbsent(fcInv, inv -> {
                    InventoryAdapter created = new InventoryAdapter(inv);
                    adapters.add(created);
                    return created;
                });
                this.addSlot(new ContainerMappedSlot(adapter, fcSlot));
            }
        }
    }

    // ================================================================
    // AbstractContainerMenu overrides
    // ================================================================

    @Override
    public boolean stillValid(Player player) {
        if (fcContainer != null && fcPlayer != null) {
            try {
                return fcContainer.canInteractWith(fcPlayer);
            } catch (Throwable t) {
                warnFcFailure("FC canInteractWith", t); // ServerPlayer.tick asks every tick: was silent
                return true;
            }
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Use MC's native moveItemStackTo instead of FC's mergeItemStack.
        // FC's version operates on FC slots disconnected from MC's InventoryAdapter.
        if (index < 0 || index >= this.slots.size()) return ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        // Live stack: the InventoryAdapter mirror or the real Inventory stack. moveItemStackTo
        // edits it in place; setChanged() then persists that through the adapter.
        ItemStack slotStack = slot.getItem();
        ItemStack originalStack = slotStack.copy();

        // Container -> player inventory (hotbar end first), player -> every container slot
        // that accepts the stack. Result/output slots (SlotCrafting, SlotFurnace, ...) reject it
        // in mayPlace, so they are never merge targets; contiguity is not assumed.
        boolean fromContainer = slot instanceof ContainerMappedSlot;
        if (!moveToOtherSide(slotStack, !fromContainer, fromContainer)) {
            return ItemStack.EMPTY;
        }

        if (slotStack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        if (slotStack.getCount() == originalStack.getCount()) {
            return ItemStack.EMPTY;
        }

        if (slot instanceof ContainerMappedSlot cms) {
            cms.onQuickMoved(player, originalStack, slotStack.getCount());
        } else if (slot instanceof ClientCraftResultSlot) {
            slot.onTake(player, originalStack); // slotStack is drained (empty after a full move)
        } else {
            slot.onTake(player, slotStack);
        }
        return originalStack;
    }

    /**
     * moveItemStackTo over each contiguous run of target slots: container slots whose mayPlace
     * accepts the stack (toContainer), or the player-inventory slots. moveItemStackTo's merge pass
     * does not check mayPlace (AbstractContainerMenu.java:602-636), so filtering the runs here is
     * what keeps output slots out.
     */
    private boolean moveToOtherSide(ItemStack stack, boolean toContainer, boolean reverse) {
        java.util.List<int[]> runs = new java.util.ArrayList<>();
        int start = -1;
        for (int i = 0; i <= this.slots.size(); i++) {
            boolean ok = false;
            if (i < this.slots.size()) {
                Slot s = this.slots.get(i);
                ok = toContainer ? (s instanceof ContainerMappedSlot && s.mayPlace(stack))
                        : !(s instanceof ContainerMappedSlot);
            }
            if (ok && start < 0) {
                start = i;
            } else if (!ok && start >= 0) {
                runs.add(new int[]{start, i});
                start = -1;
            }
        }
        if (reverse) java.util.Collections.reverse(runs);
        boolean moved = false;
        for (int[] run : runs) {
            if (stack.isEmpty()) break;
            moved |= this.moveItemStackTo(stack, run[0], run[1], reverse);
        }
        return moved;
    }

    /**
     * Slot clicks are handled by MC 1.20.1's native AbstractContainerMenu logic.
     *
     * <p>All MC slots are backed by {@link InventoryAdapter} (live proxy to FC
     * inventory) or the real player {@link net.minecraft.world.entity.player.Inventory}.
     * MC's native click/drag/shift-click logic operates directly on these proxies,
     * which read/write to FC's inventories. No FC Container.slotClick reimplementation
     * needed — MC's own implementation handles all interaction modes correctly.</p>
     *
     * <p>FC-specific slot behavior (validation, stack limits) is bridged via
     * {@link ContainerMappedSlot#mayPlace} and {@link ContainerMappedSlot#getMaxStackSize}.</p>
     */
    // Use MC's native click handling — NOT FC's Container.slotClick. Routing through FC
    // slotClick caused two bugs: (1) the cursor/remote-slot state desynced from the client,
    // so interactions needed a second click to "catch up"; (2) FC's 1.5.2 drag encoding
    // doesn't match MC's QUICK_CRAFT protocol, so drag-place-across-slots did nothing.
    // MC's clicked() implements PICKUP / QUICK_MOVE / SWAP / CLONE / THROW / QUICK_CRAFT
    // (drag) / PICKUP_ALL (double-click collect) correctly against the bridged slots
    // (ContainerMappedSlot / PlayerMappedSlot over InventoryAdapter, a live FC-inventory
    // view), and FC slot hooks (isItemValid, canTakeStack, onPickupFromSlot, onSlotChanged
    // -> onInventoryChanged -> onCraftMatrixChanged) fire through those slots. No FC
    // slotClick reimplementation needed — matching the design note above.
    // (Override retained only to keep the intent documented; it is a pure delegation.)
    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType type, Player player) {
        super.clicked(slotId, button, type, player);
    }

    /**
     * Forwards MC's menu button clicks to FC's {@code enchantItem()}.
     * MC sends this when the client clicks an enchantment button or
     * similar UI element (via ServerboundContainerButtonClickPacket).
     */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (fcContainer != null && fcPlayer != null) {
            try {
                fcPlayer.syncFromReal();
                // Merchant containers use setCurrentRecipeIndex instead of enchantItem
                if (fcContainer instanceof btw.modern.ContainerMerchant cm) {
                    cm.setCurrentRecipeIndex(buttonId);
                    return true;
                }
                boolean result = fcContainer.enchantItem(fcPlayer, buttonId);
                // Flush inventory changes (XP cost, scroll consumed, etc.)
                ((InventoryBridge) fcPlayer.inventory).writeBackAll();
                return result;
            } catch (Throwable t) {
                warnFcFailure("FC clickMenuButton", t);
            }
        }
        return false;
    }

    /**
     * Runs FC's sync loop and pushes computed data to MC DataSlots.
     *
     * <p>Called every server tick while the container is open. FC's
     * {@code detectAndSendChanges()} iterates registered crafters and
     * calls {@code sendProgressBarUpdate()} which writes into {@link #fcData}.
     * MC then detects the DataSlot changes and sends them to the client.</p>
     */
    private int lastMerchantRecipeHash = 0;
    private btw.modern.IMerchant cachedMerchant = null;
    private boolean merchantResolved = false;

    @Override
    public void broadcastChanges() {
        // Fold pending in-place MC edits into FC and drop the slot mirrors, so the sync below
        // re-reads FC (and sees FC's own in-place changes). No-op on the client (no adapters).
        flushAdapters();
        if (fcContainer != null && fcPlayer != null) {
            try {
                fcContainer.detectAndSendChanges();
                pushFcComputedData();
                syncMerchantRecipes();
            } catch (Throwable t) {
                // Throwable, not Exception: ServerPlayer.tick calls broadcastChanges unguarded, so a
                // LinkageError from FC would otherwise crash the server as a 'Ticking entity'.
                warnFcFailure("FC detectAndSendChanges", t);
            }
        }
        super.broadcastChanges();
    }

    private void syncMerchantRecipes() {
        if (!(fcContainer instanceof btw.modern.ContainerMerchant cm)) return;
        if (!merchantResolved) {
            merchantResolved = true;
            try {
                var f = btw.modern.ContainerMerchant.class.getDeclaredField("theMerchant");
                f.setAccessible(true);
                cachedMerchant = (btw.modern.IMerchant) f.get(cm);
            } catch (Exception ignored) {}
        }
        if (cachedMerchant == null) return;
        btw.modern.MerchantRecipeList recipes = cachedMerchant.getRecipes(fcPlayer);
        if (recipes == null) return;
        int hash = recipes.size();
        for (int i = 0; i < recipes.size(); i++) {
            btw.modern.MerchantRecipe r = (btw.modern.MerchantRecipe) recipes.get(i);
            hash = hash * 31 + r.getItemToBuy().itemID;
            hash = hash * 31 + r.getItemToSell().itemID;
            hash = hash * 31 + r.getToolUses();
        }
        if (hash != lastMerchantRecipeHash) {
            lastMerchantRecipeHash = hash;
            var sp = fcPlayer.getServerPlayer();
            if (sp != null) {
                BTWNetwork.sendMerchantRecipes(sp, this.containerId, recipes);
            }
        }
    }

    /**
     * Reads FC container fields via reflection and pushes them into
     * the fcData array. MC DataSlots detect changes and sync to client.
     *
     * <p>FC containers like the Infernal Enchanter compute enchantment
     * levels on the client from slot contents. Since the Forge client
     * doesn't have the FC container, we send these values explicitly.</p>
     *
     * <p>Layout:</p>
     * <ul>
     *   <li>0: reserved for FC's native progress bar (e.g., bookshelf level)</li>
     *   <li>1-5: enchantment levels (m_CurrentEnchantmentLevels[0..4])</li>
     *   <li>6-7: enchantment name seed (m_lNameSeed split into two ints)</li>
     * </ul>
     */
    private void pushFcComputedData() {
        // Only check enchanter-specific fields on enchanter containers
        if (!fcContainer.getClass().getSimpleName().contains("InfernalEnchanter")) return;

        // Enchanter: m_CurrentEnchantmentLevels[] and m_lNameSeed
        try {
            java.lang.reflect.Field levelsField = findField(fcContainer.getClass(), "m_CurrentEnchantmentLevels");
            if (levelsField != null) {
                int[] levels = (int[]) levelsField.get(fcContainer);
                for (int i = 0; i < Math.min(levels.length, 5); i++) {
                    fcData[i + 1] = levels[i];
                }
            } else {
                LOGGER.warn("m_CurrentEnchantmentLevels field not found on {}",
                        fcContainer.getClass().getName());
            }

            java.lang.reflect.Field seedField = findField(fcContainer.getClass(), "m_lNameSeed");
            if (seedField != null) {
                long seed = seedField.getLong(fcContainer);
                fcData[6] = (int) (seed & 0xFFFFFFFFL);
                fcData[7] = (int) ((seed >>> 32) & 0xFFFFFFFFL);
            }

            java.lang.reflect.Field bsField = findField(fcContainer.getClass(), "m_iMaxSurroundingBookshelfLevel");
            if (bsField != null) {
                fcData[0] = bsField.getInt(fcContainer); // also push via data slot in case crafter registration didn't fire
            }
        } catch (Throwable t) {
            warnFcFailure("pushFcComputedData", t);
        }
    }

    /** Finds a field by name, searching the class hierarchy. Sets accessible. */
    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private void flushAdapters() {
        for (InventoryAdapter adapter : adapters) {
            try {
                adapter.flushAndForget();
            } catch (Throwable t) {
                warnFcFailure("InventoryAdapter flush", t);
            }
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        flushAdapters();
        if (fcContainer != null && fcPlayer != null) {
            try {
                fcContainer.onCraftGuiClosed(fcPlayer);
            } catch (Throwable t) {
                warnFcFailure("FC onCraftGuiClosed", t);
            }
            // 1.5.2 EntityPlayer.closeScreen resets openContainer to inventoryContainer on
            // every close; now load-bearing since TileEntityChest.updateEntity checks
            // `openContainer instanceof ContainerChest` to keep the lid open.
            fcPlayer.openContainer = fcPlayer.inventoryContainer;
        }
    }

    /**
     * Returns the wrapped FC container, or null on the client side.
     */
    public btw.modern.Container getFcContainer() {
        return fcContainer;
    }

    // ================================================================
    // Custom Slot implementations
    // ================================================================

    private static class PlayerMappedSlot extends Slot {
        private final btw.modern.Slot fcSlot;

        PlayerMappedSlot(Inventory inventory, btw.modern.Slot fcSlot) {
            super(inventory, fcSlot.slotIndex, fcSlot.xDisplayPosition, fcSlot.yDisplayPosition);
            this.fcSlot = fcSlot;
        }
    }

    /**
     * Client mirror of an FC crafting result (1.5.2 SlotCrafting: FC workbench, soulforge). 1.5.2 ran
     * SlotCrafting.onPickupFromSlot -> Item.onCreated on the client too (vanilla/client SlotCrafting.java:72-75,
     * :184), and FC plays its crafting sounds only there (world.isRemote, e.g. FCItemArmor.onCreated). The
     * server's SlotCrafting (ContainerMappedSlot.pickupFc) stays silent under that guard, so this is the one
     * owner of the sound.
     */
    private static final class ClientCraftResultSlot extends Slot {
        ClientCraftResultSlot(net.minecraft.world.Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false; // SlotCrafting.isItemValid
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            if (player.level().isClientSide()) {
                ProxyItem.runFcOnCreated(stack, player.level(), player);
            }
            super.onTake(player, stack);
        }
    }

    private static class ContainerMappedSlot extends Slot {
        private final btw.modern.Slot fcSlot;

        ContainerMappedSlot(InventoryAdapter adapter, btw.modern.Slot fcSlot) {
            super(adapter, fcSlot.slotIndex, fcSlot.xDisplayPosition, fcSlot.yDisplayPosition);
            this.fcSlot = fcSlot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            try {
                // FC holds an item MC cannot show (getItem() is EMPTY): placing would overwrite it
                btw.modern.ItemStack held = fcSlot.getStack();
                if (held != null && held.stackSize > 0 && getItem().isEmpty()) return false;
                btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
                return fcSlot.isItemValid(fcStack);
            } catch (Throwable t) {
                warnFc("isItemValid", t);
                return false;
            }
        }

        @Override
        public int getMaxStackSize() {
            return fcSlot.getSlotStackLimit();
        }

        @Override
        public boolean mayPickup(Player player) {
            try {
                // FC holds an item MC cannot show (getItem() is EMPTY): a Q-throw would delete it,
                // since safeTake -> remove -> FC decrStackSize runs before Slot.tryRemove sees the
                // EMPTY result (Slot.java:128-146). Inert both ways, like mayPlace.
                btw.modern.ItemStack held = fcSlot.getStack();
                if (held != null && held.stackSize > 0 && getItem().isEmpty()) return false;
                PlayerBridge pb = player instanceof net.minecraft.server.level.ServerPlayer sp
                        ? PlayerBridge.getOrCreate(sp) : null;
                return fcSlot.canTakeStack(pb);
            } catch (Throwable t) {
                warnFc("canTakeStack", t);
                return false;
            }
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            // Bridge FC's onPickupFromSlot — critical for crafting
            // (consumes ingredients when result is taken)
            pickupFc(player, ItemStackHelper.toFcStack(stack));
            super.onTake(player, stack);
        }

        /**
         * Shift-click take. 1.5.2 transferStackInSlot hands onPickupFromSlot the slot's own stack,
         * possibly at stackSize 0 (FCContainerWorkbench.java:94-108). MC's drained stack is
         * EMPTY/AIR and converts to null, and the frozen 1.5.2 SlotCrafting.onCrafting dereferences
         * it (vanilla/server SlotCrafting.java:71-73,118-120) before consuming the grid: a free
         * craft per shift-click. Pass a copy of the original at the remaining size instead.
         */
        void onQuickMoved(Player player, ItemStack original, int remaining) {
            btw.modern.ItemStack fc = ItemStackHelper.toFcStack(original);
            if (fc != null) fc.stackSize = remaining;
            pickupFc(player, fc);
            super.onTake(player, ItemStack.EMPTY); // Slot.onTake -> setChanged
        }

        private void pickupFc(Player player, btw.modern.ItemStack fcStack) {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            PlayerBridge pb = PlayerBridge.getOrCreate(sp);
            try {
                fcSlot.onPickupFromSlot(pb, fcStack);
                // SlotCrafting returns container items (buckets) through
                // thePlayer.inventory.addItemStackToInventory, which only edits the FC snapshot
                // (vanilla/server SlotCrafting.java:156-179): flush the slots FC changed.
                pb.syncInventoryToReal();
            } catch (Throwable t) {
                warnFc("onPickupFromSlot", t);
            }
        }

        private void warnFc(String what, Throwable t) {
            warnFcFailure("[FC-TAKE] FC " + what + " on " + fcSlot.getClass().getSimpleName(), t);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            fcSlot.onSlotChanged();
        }
    }
}
