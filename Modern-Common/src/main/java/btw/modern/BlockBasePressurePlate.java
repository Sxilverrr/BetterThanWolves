package btw.modern;

public abstract class BlockBasePressurePlate extends Block {

    // vanilla/client BlockBasePressurePlate.java:27 sets tabRedstone in the constructor (BlockPressurePlate chains to it):
    // FC's soulforged pressure plate 230 (FCBlockPressurePlate, no tab of its own) is listed under Redstone through it.
    // The same constructor sets the random-tick flag (vanilla/server BlockBasePressurePlate.java:27). Flag only: the
    // plate tick body stays 1.20.1's.
    protected BlockBasePressurePlate(int id, Material material) {
        super(id, material);
        this.setCreativeTab(CreativeTabs.tabRedstone);
        this.setTickRandomly(true);
    }

    // 1.5.2 BlockBasePressurePlate.getCollisionBoundingBoxFromPool (vanilla/client BlockBasePressurePlate.java:67-70):
    // pressure plates have no collision box (FCBlockPressurePlate overrides only the bounds methods)
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    // 1.5.2 BlockBasePressurePlate.isOpaqueCube / renderAsNormalBlock (vanilla/client BlockBasePressurePlate.java:76-87).
    // With the inherited true, World.isBlockNormalCube reported this passable block as solid, so an FC mob's
    // eye inside the cell counted as being inside an opaque block (Entity.isEntityInsideOpaqueBlock -> inWall).
    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    public void func_94353_c_(int metadata) {}
    public int getMetaFromWeight(int weight) { return 0; }
    public int getPowerSupply(int metadata) { return 0; }
}
