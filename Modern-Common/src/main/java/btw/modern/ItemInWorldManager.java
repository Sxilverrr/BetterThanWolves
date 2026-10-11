package btw.modern;

public class ItemInWorldManager {
    public World theWorld;
    public EntityPlayer thisPlayerMP;
    public EnumGameType gameType;

    /** The held item stack after tryHarvestBlock completes — for writeback. */
    public ItemStack lastHeldStack;

    public ItemInWorldManager(World world) {
        this.theWorld = world;
    }

    public void setGameType(EnumGameType type) { this.gameType = type; }
    public EnumGameType getGameType() { return this.gameType; }
    public boolean isCreative() { return false; }
    public void initBlockRemoving() {}

    /**
     * FC's complete block harvest pipeline from the patched ItemInWorldManager.
     * Handles: conversion (log→damaged log), proper tool harvest, improper tool component drops.
     *
     * Called by the mixin when a player finishes breaking a block.
     * ALL gameplay logic is FC code — the mixin just calls this method.
     */
    public boolean tryHarvestBlock(int i, int j, int k, int iFromSide) {
        if (theWorld == null || thisPlayerMP == null) {
            return false;
        }

        int iBlockID = theWorld.getBlockId(i, j, k);
        if (iBlockID <= 0) return false;

        Block block = Block.blocksList[iBlockID];
        if (block == null) {
            return false;
        }

        int iMetadata = theWorld.getBlockMetadata(i, j, k);

        // 1.5.2 emits the break event (sound + particles) for everyone near except the breaker, whose client
        // plays its own, in both branches before anything is removed or converted (vanilla/server
        // ItemInWorldManager.java:432 creative, :458 SurvivalTryHarvestBlock). The bridge cancels 1.20.1's
        // ServerPlayerGameMode.destroyBlock (and with it Block.playerWillDestroy's levelEvent 2001) for every
        // block that reaches here, so this is the only emitter: without it nobody else heard or saw a player
        // break. The world translates the legacy payload (id + meta << 12) to the 1.20.1 state id and leaves
        // the breaker out (1.20.1 MultiPlayerGameMode.destroyBlock already played it on that client).
        theWorld.playAuxSFXAtEntity(thisPlayerMP, 2001, i, j, k, iBlockID + (iMetadata << 12));

        ItemStack currentStack = thisPlayerMP.getCurrentEquippedItem();

        boolean bRemovingBlock = true;
        boolean bConvertingBlock = false;

        boolean bHarvestingBlock = thisPlayerMP.canHarvestBlock(block, i, j, k);

        if (!bHarvestingBlock) {
            bConvertingBlock = block.CanConvertBlock(currentStack, theWorld, i, j, k);

            if (bConvertingBlock) {
                boolean converted = block.ConvertBlock(currentStack, theWorld, i, j, k, iFromSide);
                bRemovingBlock = !converted;
            }
        }

        if (bRemovingBlock) {
            boolean bRemoved = theWorld.setBlockToAir(i, j, k);

            if (bRemoved && !bConvertingBlock) {
                if (bHarvestingBlock) {
                    block.harvestBlock(theWorld, thisPlayerMP, i, j, k, iMetadata);
                } else {
                    block.OnBlockDestroyedWithImproperTool(theWorld, thisPlayerMP, i, j, k, iMetadata);
                }
            }
        }

        // Damage the held tool
        if (currentStack != null) {
            currentStack.onBlockDestroyed(theWorld, iBlockID, i, j, k, thisPlayerMP);

            if (currentStack.stackSize <= 0) {
                thisPlayerMP.destroyCurrentEquippedItem();
            }
        }

        // Store for writeback by the Forge bridge
        this.lastHeldStack = currentStack;

        return true;
    }
}
