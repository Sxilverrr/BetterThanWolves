package btw.modern;

public abstract class BlockRedstoneLogic extends BlockDirectional {

    protected BlockRedstoneLogic(int id, Material material) {
        super(id, material);
        // 1.5.2 BlockRedstoneLogic constructor (FCMOD, vanilla/client BlockRedstoneLogic.java:24-32): repeaters
        // and comparators are 1/8 high. Without this they collided as full blocks (FCBlockRedstoneRepeater /
        // FCBlockComparator set no bounds of their own).
        InitBlockBounds(0.0F, 0.0F, 0.0F, 1.0F, 0.125F, 1.0F);
    }

    // 1.5.2 BlockRedstoneLogic.renderAsNormalBlock / isOpaqueCube (vanilla/client BlockRedstoneLogic.java:37-40,
    // 331-334). A small FC mob standing on the 1/8-high box has its eye inside the cell; with the inherited true
    // that counted as being inside an opaque block (Entity.isEntityInsideOpaqueBlock -> inWall hit).
    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    public abstract int func_94481_j_(int var1);
    public int func_94480_d(IBlockAccess blockAccess, int i, int j, int k, int side) { return 0; }
    public boolean func_94490_c(int metadata) { return false; }
    public boolean func_94478_d(World world, int i, int j, int k, int side) { return false; }
    public int getInputStrength(World world, int i, int j, int k, int side) { return 0; }
    public void func_94479_f(World world, int i, int j, int k, int metadata) {}
    public boolean func_96470_c(int metadata) { return false; }
}
