package btw.modern;

public class BlockCocoa extends BlockDirectional {

    protected BlockCocoa(int id) {
        super(id, Material.plants);
        // 1.5.2 BlockCocoa constructor (vanilla/server BlockCocoa.java:25): FCBlockCocoa.updateTick (survival,
        // ripening) runs on the random tick
        setTickRandomly(true);
    }

    // 1.5.2 BlockCocoa.canBlockStay (vanilla/server BlockCocoa.java:54-61): the block the pod faces (direction
    // 0 = S, 1 = W, 2 = N, 3 = E, toward the log) is a jungle log. FCBlockCocoa.updateTick drops the pod when this
    // fails; with Block's true it never could.
    @Override
    public boolean canBlockStay(World world, int i, int j, int k) {
        int iDirection = getDirection(world.getBlockMetadata(i, j, k));
        i += Direction.offsetX[iDirection];
        k += Direction.offsetZ[iDirection];
        int iBlockID = world.getBlockId(i, j, k);
        return iBlockID == Block.wood.blockID && BlockLog.limitToValidMetadata(world.getBlockMetadata(i, j, k)) == 3;
    }

    // 1.5.2 BlockCocoa.onNeighborBlockChange (vanilla/server BlockCocoa.java:155-162). Reached through
    // BlockBehaviorMixin.btw$neighborChanged.
    @Override
    public void onNeighborBlockChange(World world, int i, int j, int k, int iNeighborBlockID) {
        if (!canBlockStay(world, i, j, k)) {
            dropBlockAsItem(world, i, j, k, world.getBlockMetadata(i, j, k), 0);
            world.setBlockToAir(i, j, k);
        }
    }

    public static int func_72219_c(int metadata) {
        return (metadata >> 2) & 3;
    }
}
