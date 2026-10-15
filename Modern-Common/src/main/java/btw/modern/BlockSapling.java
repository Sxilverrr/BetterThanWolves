package btw.modern;

public class BlockSapling extends BlockFlower {

    protected BlockSapling(int id) {
        super(id, Material.plants);
    }

    // 1.5.2 BlockSapling.isSameSapling (vanilla/server BlockSapling.java:148-151): same block and same species
    // (meta & 3; FC keeps its growth stage in bits 2-3). FCBlockSapling.growTree needs it for the 2x2 jungle tree.
    public boolean isSameSapling(World world, int i, int j, int k, int metadata) {
        return world.getBlockId(i, j, k) == blockID && (world.getBlockMetadata(i, j, k) & 3) == metadata;
    }

    public void growTree(World world, int i, int j, int k, java.util.Random random) {}

    // 1.5.2 BlockSapling.damageDropped (vanilla/server BlockSapling.java:156-159): the dropped sapling keeps its
    // species and loses FC's growth stage bits. With Block's 0 every sapling dropped by checkFlowerChange or a
    // harvest came out as an oak sapling.
    @Override
    public int damageDropped(int metadata) {
        return metadata & 3;
    }
}
