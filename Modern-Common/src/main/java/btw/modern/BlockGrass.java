package btw.modern;

public class BlockGrass extends Block {

    protected BlockGrass(int id) {
        super(id, Material.grass);
    }

    /**
     * 1.5.2 ColorizerGrass.getGrassColor(0.5, 1.0) (vanilla/client BlockGrass.java:72-77). Modern-Common has no
     * colorizer; this is the grass colormap at that point (1.20.1 GrassColor.getDefaultColor()), the same constant
     * BiomeGenBase.getBiomeGrassColor returns for every biome here.
     */
    private static final int DEFAULT_GRASS_COLOR = 0x7CBD6B;

    private static Icon sideOverlayIcon;

    private Icon iconGrassTop;
    private Icon iconGrassSideOverlay;

    // 1.5.2 BlockGrass.getIconSideOverlay (vanilla/client BlockGrass.java:148-151): Block.grass's overlay icon, which
    // FCBlockGrass.RenderBlock passes to renderGrassBlockWith* for the tinted side fringe. An icon set through
    // setIconSideOverlay is the fallback while Block.grass has none.
    public static Icon getIconSideOverlay() {
        BlockGrass grass = Block.grass;
        Icon icon = grass != null ? grass.iconGrassSideOverlay : null;
        return icon != null ? icon : sideOverlayIcon;
    }

    public static void setIconSideOverlay(Icon icon) {
        sideOverlayIcon = icon;
    }

    // 1.5.2 BlockGrass.getIcon / registerIcons (vanilla/client BlockGrass.java:35-38, 64-70): grass top, dirt bottom,
    // grass side. FCBlockGrass overrides neither (its registerIcons calls super, then fills its own copies for
    // getBlockTexture); the inherited Block.registerIcons registered "grass", which in 1.20.1 is the tall-grass
    // plant texture, on every face. Read by the item path (renderBlockAsItemVanilla: a held, worn or carried grass
    // block). The world's grass is a 1.20.1 block drawn by MC, so world rendering is unchanged. snow_side is not
    // registered: only 1.5.2 BlockGrass.getBlockTexture read it, and FCBlockGrass overrides that.
    @Override
    public Icon getIcon(int side, int metadata) {
        if (side == 1) {
            return this.iconGrassTop;
        }
        if (side == 0) {
            return Block.dirt != null ? Block.dirt.getBlockTextureFromSide(side) : this.blockIcon;
        }
        return this.blockIcon;
    }

    @Override
    public void registerIcons(IconRegister register) {
        this.blockIcon = register.registerIcon("grass_side");
        this.iconGrassTop = register.registerIcon("grass_top");
        this.iconGrassSideOverlay = register.registerIcon("grass_side_overlay");
    }

    // 1.5.2 BlockGrass.getBlockColor / getRenderColor (vanilla/client BlockGrass.java:72-85): the default grass
    // colour, which renderBlockAsItemVanilla applies to the grass item's top face only. With Block's 0xFFFFFF
    // a held or worn grass block drew a grey top.
    @Override
    public int getBlockColor() {
        return DEFAULT_GRASS_COLOR;
    }

    @Override
    public int getRenderColor(int metadata) {
        return this.getBlockColor();
    }

    // 1.5.2 BlockGrass.colorMultiplier (vanilla/client BlockGrass.java:91-109): the average biome grass colour of
    // the 3x3 columns around the block. Reached only by FC captures that draw grass with a world access
    // (FCBlockGrass.RenderBlock, e.g. a block lifted by a platform); FCBlockGrass returns white over it while
    // snow covers the top. Port: an access with no biomes (IBlockAccess's default null) counts as the default
    // grass colour, where 1.5.2 had a biome everywhere.
    @Override
    public int colorMultiplier(IBlockAccess blockAccess, int x, int y, int z) {
        int red = 0;
        int green = 0;
        int blue = 0;

        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                BiomeGenBase biome = blockAccess != null ? blockAccess.getBiomeGenForCoords(x + dx, z + dz) : null;
                int color = biome != null ? biome.getBiomeGrassColor() : DEFAULT_GRASS_COLOR;
                red += (color & 16711680) >> 16;
                green += (color & 65280) >> 8;
                blue += color & 255;
            }
        }

        return (red / 9 & 255) << 16 | (green / 9 & 255) << 8 | blue / 9 & 255;
    }
}
