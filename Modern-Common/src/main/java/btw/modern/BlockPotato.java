package btw.modern;

public class BlockPotato extends BlockCrops {

    protected BlockPotato(int id) {
        super(id);
    }

    /** 1.5.2 BlockPotato.getSeedItem (vanilla/server BlockPotato.java:26-29): a potato replants the crop. */
    @Override
    public int getSeedItem() {
        return Item.potato.itemID;
    }

    /** 1.5.2 BlockPotato.getCropItem (vanilla/server BlockPotato.java:34-37). */
    @Override
    public int getCropItem() {
        return Item.potato.itemID;
    }

    /**
     * 1.5.2 BlockPotato.dropBlockAsItemWithChance (vanilla/server BlockPotato.java:42-53): a fully grown potato
     * also drops a poisonous potato one time in 50.
     */
    @Override
    public void dropBlockAsItemWithChance(World world, int x, int y, int z, int metadata,
            float chance, int fortune) {
        super.dropBlockAsItemWithChance(world, x, y, z, metadata, chance, fortune);

        if (!world.isRemote) {
            if (metadata >= 7 && world.rand.nextInt(50) == 0) {
                dropBlockAsItem_do(world, x, y, z, new ItemStack(Item.poisonousPotato));
            }
        }
    }
}
