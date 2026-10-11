package btw.modern;

public class BlockTallGrass extends BlockFlower {

    protected BlockTallGrass(int id) {
        super(id, Material.vine);
    }

    // 1.5.2 BlockTallGrass.getRenderColor (vanilla/client BlockTallGrass.java:55-58): the dead shrub (meta 0) is
    // untinted, grass and fern take ColorizerFoliage.getFoliageColorBasic() (ColorizerFoliage.java:54-57, the
    // constant 4764952; Modern-Common has no colorizer). Read by RenderBlocks.renderBlockAsItemVanilla's inventory tint.
    @Override
    public int getRenderColor(int metadata) {
        return metadata == 0 ? 16777215 : 4764952;
    }
}
