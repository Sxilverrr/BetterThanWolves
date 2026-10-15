package btw.modern;

public class BlockLog extends Block {

    protected BlockLog(int id) {
        super(id, Material.wood);
    }

    protected BlockLog(int id, Material material) {
        super(id, material);
    }

    public static final String[] woodType = new String[] {"oak", "spruce", "birch", "jungle"};
    public static int limitToValidMetadata(int metadata) { return metadata & 3; }

    // Wood species lives in metadata bits 0-1 (orientation in 2-3). Without this the drop
    // stack carries damage 0, so a chopped spruce/birch/jungle log dropped as OAK. The bridge
    // (ItemStackHelper + ProxyRegistry variant table) turns this damage back into the right
    // 1.20.1 log block.
    @Override
    public int damageDropped(int metadata) {
        return metadata & 3;
    }

    /**
     * 1.5.2 BlockLog.breakBlock (vanilla/server BlockLog.java:57-85): a removed log flags (metadata bit 8, flag 4)
     * every leaves block (Block.leaves, id 18) in the 9x9x9 around it that is not flagged yet.
     * FCBlockLeaves.updateTick then decays the flagged leaves that no live log within 4 steps still holds.
     */
    @Override
    public void breakBlock(World world, int i, int j, int k, int iBlockID, int iMetadata) {
        byte range = 4;
        int checkRange = range + 1;

        if (world.checkChunksExist(i - checkRange, j - checkRange, k - checkRange,
                i + checkRange, j + checkRange, k + checkRange)) {
            for (int offsetI = -range; offsetI <= range; ++offsetI) {
                for (int offsetJ = -range; offsetJ <= range; ++offsetJ) {
                    for (int offsetK = -range; offsetK <= range; ++offsetK) {
                        int iTempID = world.getBlockId(i + offsetI, j + offsetJ, k + offsetK);

                        if (iTempID == Block.leaves.blockID) {
                            int iTempMetadata = world.getBlockMetadata(i + offsetI, j + offsetJ, k + offsetK);

                            if ((iTempMetadata & 8) == 0) {
                                world.setBlockMetadata(i + offsetI, j + offsetJ, k + offsetK, iTempMetadata | 8, 4);
                            }
                        }
                    }
                }
            }
        }
    }
}
