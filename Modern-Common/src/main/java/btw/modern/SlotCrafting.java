package btw.modern;

/**
 * Crafting result slot. When the result is picked up, ingredients
 * in the crafting matrix are consumed.
 *
 * <p>FC's patched version adds tool damage-in-crafting and
 * non-consumed-item logic. These are delegated to the FC Item
 * methods (IsConsumedInCrafting, IsDamagedInCrafting, etc.)
 * which FC subclasses override.</p>
 */
public class SlotCrafting extends Slot {
    private final IInventory craftMatrix;
    private EntityPlayer thePlayer;
    private int amountCrafted;

    // Second parameter MUST be IInventory (not InventoryCrafting) to match FC's
    // shadow-remapped constructor descriptor — otherwise Modern-Common callers
    // emit a `(EntityPlayer, InventoryCrafting, IInventory, ...)` invokespecial
    // that doesn't exist on the FC class that wins the shadow-merge at runtime.
    public SlotCrafting(EntityPlayer player, IInventory craftMatrix, IInventory craftResult, int index, int x, int y) {
        super(craftResult, index, x, y);
        this.thePlayer = player;
        this.craftMatrix = craftMatrix;
    }

    @Override
    public boolean isItemValid(ItemStack stack) { return false; }

    @Override
    public ItemStack decrStackSize(int amount) {
        if (this.getHasStack()) {
            this.amountCrafted += Math.min(amount, this.getStack().stackSize);
        }
        return super.decrStackSize(amount);
    }

    // 1.5.2 SlotCrafting.onCrafting(ItemStack, int) — bumps the crafted count then fires the
    // single-arg hook; called by vanilla when a full shift-click craft completes.
    protected void onCrafting(ItemStack stack, int count) {
        this.amountCrafted += count;
        this.onCrafting(stack);
    }

    // 1.5.2 SlotCrafting.onCrafting(ItemStack) (vanilla/client SlotCrafting.java:70-117) —
    // fires ItemStack.onCrafting stats, resets the crafted count, and runs the vanilla
    // crafting-achievement dispatch (addStat is a bridge no-op today, kept for parity).
    protected void onCrafting(ItemStack stack) {
        stack.onCrafting(this.thePlayer.worldObj, this.thePlayer, this.amountCrafted);
        this.amountCrafted = 0;

        if (stack.itemID == Block.workbench.blockID) {
            this.thePlayer.addStat(AchievementList.buildWorkBench, 1);
        } else if (stack.itemID == Item.pickaxeWood.itemID) {
            this.thePlayer.addStat(AchievementList.buildPickaxe, 1);
        } else if (stack.itemID == Block.furnaceIdle.blockID) {
            this.thePlayer.addStat(AchievementList.buildFurnace, 1);
        } else if (stack.itemID == Item.hoeWood.itemID) {
            this.thePlayer.addStat(AchievementList.buildHoe, 1);
        } else if (stack.itemID == Item.bread.itemID) {
            this.thePlayer.addStat(AchievementList.makeBread, 1);
        } else if (stack.itemID == Item.cake.itemID) {
            this.thePlayer.addStat(AchievementList.bakeCake, 1);
        } else if (stack.itemID == Item.pickaxeStone.itemID) {
            this.thePlayer.addStat(AchievementList.buildBetterPickaxe, 1);
        } else if (stack.itemID == Item.swordWood.itemID) {
            this.thePlayer.addStat(AchievementList.buildSword, 1);
        } else if (stack.itemID == Block.enchantmentTable.blockID) {
            this.thePlayer.addStat(AchievementList.enchantments, 1);
        } else if (stack.itemID == Block.bookShelf.blockID) {
            this.thePlayer.addStat(AchievementList.bookcase, 1);
        }
    }

    // 1.5.2 SlotCrafting.onPickupFromSlot (vanilla/client SlotCrafting.java:119-186 incl. FCMOD
    // consume/damage hooks) — fired by Container.slotClick and FCContainerMenu$ContainerMappedSlot.onTake.
    @Override
    public void onPickupFromSlot(EntityPlayer player, ItemStack resultStack) {
        // FCMOD-INTEGRATION(1.20.1): recipes: a result from the 1.20.1 RecipeManager fallback (CraftingManager
        // ExternalResolver) consumes the grid by its own recipe's rules. Taken before the first decrStackSize, which
        // re-enters onCraftMatrixChanged and would replace it with the next match.
        CraftingManager.ExternalCraft ext = this.craftMatrix instanceof InventoryCrafting ic
                ? CraftingManager.takeExternalCraft(ic) : null;
        if (ext != null) {
            this.onPickupExternal((InventoryCrafting) this.craftMatrix, player, resultStack, ext);
            return;
        }

        if (resultStack != null) {
            this.onCrafting(resultStack);
        }

        // Consume ingredients from the crafting matrix
        for (int i = 0; i < this.craftMatrix.getSizeInventory(); i++) {
            ItemStack ingredient = this.craftMatrix.getStackInSlot(i);
            if (ingredient == null) continue;

            Item item = ingredient.getItem();
            if (item == null) {
                // No FC Item entry — just consume
                this.craftMatrix.decrStackSize(i, 1);
                continue;
            }

            // FCMOD: Added
            // FC hooks: notify item it was used in crafting
            item.OnUsedInCrafting(ingredient.getItemDamage(), player, resultStack);

            // FC: some items are not consumed (e.g., tools used as crafting aids)
            if (!item.IsConsumedInCrafting()) {
                continue;
            }

            // FC: some items are damaged instead of consumed
            if (item.IsDamagedInCrafting()) {
                if (ingredient.getItemDamage() >= ingredient.getMaxDamage() - 1) {
                    item.OnBrokenInCrafting(player);
                    this.craftMatrix.decrStackSize(i, 1);
                } else {
                    item.OnDamagedInCrafting(player);
                    ingredient.damageItem(1, player);
                }
                continue;
            }
            // END FCMOD

            // Default: consume 1 from the stack
            this.craftMatrix.decrStackSize(i, 1);

            // 1.5.2 SlotCrafting.onPickupFromSlot:157-179 — return container items (e.g. empty
            // buckets) unless the result item explicitly consumes them (FCMOD hook).
            if (item.hasContainerItem()) {
                // FCMOD: Code added
                if (resultStack != null && resultStack.getItem() != null
                        && resultStack.getItem().DoesConsumeContainerItemWhenCrafted(item.getContainerItem())) {
                    continue;
                }
                // END FCMOD

                ItemStack containerStack = new ItemStack(item.getContainerItem());

                if (!item.doesContainerItemLeaveCraftingGrid(ingredient)
                        || !this.thePlayer.inventory.addItemStackToInventory(containerStack)) {
                    if (this.craftMatrix.getStackInSlot(i) == null) {
                        this.craftMatrix.setInventorySlotContents(i, containerStack);
                    } else {
                        this.thePlayer.dropPlayerItem(containerStack);
                    }
                }
            }
        }

        // FCMOD: Code added
        player.m_iTimesCraftedThisTick++;
        // END FCMOD
    }

    private static boolean externalTakeFailureLogged;

    /**
     * Take of an external (1.20.1 RecipeManager) result, in the order of 1.20.1 ResultSlot.onTake (:54-80): the
     * crafted hooks first (checkTakeAchievements, so ItemCraftedEvent listeners see the full grid), then the
     * recipe's remainders, then one of each ingredient is consumed and its remainder placed. FC's per-item crafting
     * rules (OnUsedInCrafting, IsConsumedInCrafting, damage-in-crafting, container items) belong to FC's own recipes
     * and do not run: an external recipe's remainders are its own, as in the 2x2 grid.
     */
    private void onPickupExternal(InventoryCrafting grid, EntityPlayer player, ItemStack resultStack,
                                  CraftingManager.ExternalCraft ext) {
        EntityPlayer taker = player != null ? player : this.thePlayer;
        if (resultStack != null) {
            this.onCrafting(resultStack);
        }
        try {
            ext.onTaken(grid, taker, resultStack);
        } catch (Throwable t) {
            logExternalTakeFailure("crafted hooks", t);
        }

        ItemStack[] remainders;
        try {
            remainders = ext.remainders(grid, taker);
        } catch (Throwable t) {
            logExternalTakeFailure("remainders", t);
            remainders = null;
        }

        for (int i = 0; i < grid.getSizeInventory(); i++) {
            ItemStack current = grid.getStackInSlot(i);
            ItemStack remainder = remainders != null && i < remainders.length ? remainders[i] : null;
            if (current != null) {
                grid.decrStackSize(i, 1);
                current = grid.getStackInSlot(i);
            }
            if (remainder == null || remainder.stackSize <= 0) continue;

            if (current == null) {
                grid.setInventorySlotContents(i, remainder);
            } else if (current.itemID == remainder.itemID && current.getItemDamage() == remainder.getItemDamage()
                    && ItemStack.areItemStackTagsEqual(current, remainder)
                    && current.stackSize + remainder.stackSize
                            <= Math.min(current.getMaxStackSize(), grid.getInventoryStackLimit())) {
                remainder.stackSize += current.stackSize;
                grid.setInventorySlotContents(i, remainder);
            } else if (taker == null || !taker.inventory.addItemStackToInventory(remainder)) {
                if (taker != null && remainder.stackSize > 0) taker.dropPlayerItem(remainder);
            }
        }

        if (taker != null) taker.m_iTimesCraftedThisTick++;
    }

    private static void logExternalTakeFailure(String what, Throwable t) {
        if (externalTakeFailureLogged) return;
        externalTakeFailureLogged = true;
        org.apache.logging.log4j.LogManager.getLogger("BTW-Crafting").error(
                "[CRAFTING] External recipe {} failed when the result was taken (logged once)", what, t);
    }
}
