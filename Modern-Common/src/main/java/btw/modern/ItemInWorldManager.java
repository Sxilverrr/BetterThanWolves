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

    // FCMOD-INTEGRATION(1.20.1): fc_harvest_parity: 1.5.2 isCreative() is the manager's game type (vanilla/server ItemInWorldManager.java:69-72); a constant false left FC without its creative branch
    // A manager nobody gave a game type (null) stays survival, as before.
    public boolean isCreative() { return gameType != null && gameType.isCreative(); }
    public void initBlockRemoving() {}

    /**
     * FC's complete block harvest pipeline from the patched ItemInWorldManager.
     * Handles: conversion (log→damaged log), proper tool harvest, improper tool component drops.
     *
     * Called by the mixin when a player finishes breaking a block.
     * ALL gameplay logic is FC code — the mixin just calls this method.
     *
     * <p>1.5.2 order (vanilla/server ItemInWorldManager.java:421-444): a creative player only removes the block,
     * everyone else runs SurvivalTryHarvestBlock (:446-504). 1.5.2's first clause (adventure without
     * canCurrentToolHarvestBlock returns false) is not ported: adventure keeps 1.20.1's CanDestroy rule, which
     * Forge's BreakEvent pre-cancel applies before the bridge gets here (ServerPlayerGameModeMixin).</p>
     */
    public boolean tryHarvestBlock(int i, int j, int k, int iFromSide) {
        if (theWorld == null || thisPlayerMP == null) {
            return false;
        }

        // FCMOD-INTEGRATION(1.20.1): fc_harvest_parity: 1.5.2's creative branch (vanilla/server ItemInWorldManager.java:427-439): the break effect and removeBlock, never a harvest, conversion or tool wear
        if (isCreative()) {
            int iBlockID = theWorld.getBlockId(i, j, k);
            int iBlockMetadata = theWorld.getBlockMetadata(i, j, k);

            theWorld.playAuxSFXAtEntity(thisPlayerMP, 2001, i, j, k, iBlockID + (iBlockMetadata << 12));

            boolean bBlockRemoved = removeBlock(i, j, k);

            // 1.5.2 then resends the block to the breaker (Packet53BlockChange). 1.20.1 does that through the
            // break's sequence ack: destroyAndAck resends the block when destroyBlock reports failure, and an acked
            // client prediction falls back to the last server state; the removal itself reaches every client
            // through the chunk broadcast. The held stack is untouched.
            this.lastHeldStack = thisPlayerMP.getCurrentEquippedItem();

            return bBlockRemoved;
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
            // FCMOD-INTEGRATION(1.20.1): fc_harvest_parity: 1.5.2 removes through removeBlock (vanilla/server ItemInWorldManager.java:479), whose hooks a bare setBlockToAir skipped
            boolean bRemoved = removeBlock(i, j, k);

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

    /**
     * Removes a block and triggers the appropriate events: 1.5.2 ItemInWorldManager.removeBlock (vanilla/server
     * ItemInWorldManager.java:258-280). The block hears onBlockHarvested before it goes (FCBlockVase explodes its
     * contents unless silk-touched, Client FCBlockVase.java:154-160) and onBlockDestroyedByPlayer after (the mining
     * charge drops itself, Client FCBlockMiningCharge.java:186-195; FCBlockStairs forwards it to its base block,
     * :50-53), and FC's animals within 8 blocks panic at a survival player's removal (World.java:991-1010).
     */
    // FCMOD-INTEGRATION(1.20.1): fc_harvest_parity: the port removed FC blocks with setBlockToAir alone, so vases never exploded, mining charges dropped nothing and animals never panicked
    private boolean removeBlock(int i, int j, int k) {
        int iBlockID = theWorld.getBlockId(i, j, k);
        Block block = iBlockID > 0 && iBlockID < Block.blocksList.length ? Block.blocksList[iBlockID] : null;
        int iMetadata = theWorld.getBlockMetadata(i, j, k);

        if (block != null) {
            block.onBlockHarvested(theWorld, i, j, k, iMetadata, thisPlayerMP);
        }

        boolean bRemoved = theWorld.setBlockToAir(i, j, k);

        if (block != null && bRemoved) {
            block.onBlockDestroyedByPlayer(theWorld, i, j, k, iMetadata);

            // FC animals panic at blocks removed near them (1.5.2 :273-275); the world skips creative players.
            if (block.blockMaterial != null) {
                theWorld.NotifyNearbyAnimalsOfPlayerBlockAddOrRemove(thisPlayerMP, block, i, j, k);
            }
        }

        return bRemoved;
    }
}
