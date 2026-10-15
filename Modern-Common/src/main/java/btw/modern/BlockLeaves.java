package btw.modern;

public class BlockLeaves extends Block {

    public int[] adjacentTreeBlocks;
    public boolean graphicsLevel;

    protected BlockLeaves(int id) {
        super(id, Material.leaves);
        // 1.5.2 BlockLeaves constructor (vanilla/server BlockLeaves.java:28): every leaf random-ticks, so the ones
        // flagged below get FCBlockLeaves.updateTick's decay check.
        setTickRandomly(true);
    }

    /**
     * 1.5.2 BlockLeaves.breakBlock (vanilla/server BlockLeaves.java:35-59): a removed leaf flags (metadata bit 8,
     * flag 4) every leaves block (Block.leaves, id 18) in the 3x3x3 around it. Only flagged leaves decay
     * (FCBlockLeaves.updateTick), and a decaying leaf is removed in turn, so the flag spreads through a canopy
     * that has lost its logs. FCBlockBloodWoodLeaves flags its own leaves, then calls this.
     */
    @Override
    public void breakBlock(World world, int i, int j, int k, int iBlockID, int iMetadata) {
        byte range = 1;
        int checkRange = range + 1;

        if (world.checkChunksExist(i - checkRange, j - checkRange, k - checkRange,
                i + checkRange, j + checkRange, k + checkRange)) {
            for (int offsetI = -range; offsetI <= range; ++offsetI) {
                for (int offsetJ = -range; offsetJ <= range; ++offsetJ) {
                    for (int offsetK = -range; offsetK <= range; ++offsetK) {
                        int iTempID = world.getBlockId(i + offsetI, j + offsetJ, k + offsetK);

                        if (iTempID == Block.leaves.blockID) {
                            int iTempMetadata = world.getBlockMetadata(i + offsetI, j + offsetJ, k + offsetK);
                            world.setBlockMetadata(i + offsetI, j + offsetJ, k + offsetK, iTempMetadata | 8, 4);
                        }
                    }
                }
            }
        }
    }
}
