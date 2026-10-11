package btw.modern;

public class BlockSoulSand extends Block {

    protected BlockSoulSand(int id) {
        super(id, Material.sand);
    }

    // 1.5.2 BlockSoulSand.getCollisionBoundingBoxFromPool (vanilla/client BlockSoulSand.java:29-33): 1/8 lower
    // than a full block (FCBlockSoulSand does not override it)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        float sink = 0.125F;
        return AxisAlignedBB.getAABBPool().getAABB((double)x, (double)y, (double)z,
            (double)(x + 1), (double)((float)(y + 1) - sink), (double)(z + 1));
    }

    // No onEntityCollidedWithBlock override (unlike vanilla 1.5.2's 0.4 motion damping):
    // the modern SoulSandBlock's 0.4 speedFactor supplies the slowdown, and the Forge
    // speed-factor hooks now COMPOSE FC's 1.2 hard-surface bonus with it (0.4*1.2=0.48),
    // reproducing 1.5.2's net soul-sand slow. Adding the FC damping too would double it.
}
