package btw.modern;

import java.util.List;

public class BlockLilyPad extends BlockFlower {

    protected BlockLilyPad(int id) {
        super(id, Material.plants);
    }

    // 1.5.2 BlockLilyPad.addCollisionBoxesToList (vanilla/client BlockLilyPad.java:42-48): boats pass through
    // lily pads, everything else collides with the pad (FCBlockLilyPad overrides getCollisionBoundingBoxFromPool,
    // which the Block implementation called here uses)
    @Override
    public void addCollisionBoxesToList(World world, int x, int y, int z, AxisAlignedBB mask, List list, Entity entity) {
        if (entity == null || !(entity instanceof EntityBoat)) {
            super.addCollisionBoxesToList(world, x, y, z, mask, list, entity);
        }
    }

    // 1.5.2 BlockLilyPad.canBlockStay (vanilla/server BlockLilyPad.java:70-73): still water (metadata 0) below,
    // in place of BlockFlower's light and soil rule; FCBlockLilyPad does not override it. Its random tick
    // (BlockFlower.updateTick -> checkFlowerChange) and neighbour check use this. Port: the 1.5.2 0..255 height
    // test was the 1.5.2 build range; the build range here is World.getFcMinBuildHeight / getFcMaxBuildHeight.
    @Override
    public boolean canBlockStay(World world, int i, int j, int k) {
        return j >= world.getFcMinBuildHeight() && j < world.getFcMaxBuildHeight()
            ? world.getBlockMaterial(i, j - 1, k) == Material.water && world.getBlockMetadata(i, j - 1, k) == 0
            : false;
    }

    // 1.5.2 BlockLilyPad.getRenderType (vanilla/client BlockLilyPad.java:33-36), not BlockFlower's crossed
    // squares. FCBlockLilyPad (111, a native MC block) draws itself in-world through its RenderBlock override
    // (renderBlockLilyPad); renderBlockAsItemVanilla's type-23 branch draws nothing, as in 1.5.2, and
    // DoesItemRenderAsBlock stays false (the item is a flat icon).
    @Override
    public int getRenderType() {
        return 23;
    }
}
