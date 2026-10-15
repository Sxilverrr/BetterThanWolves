package btw.modern;

import java.util.Random;

public class BlockNetherStalk extends BlockFlower {

    protected BlockNetherStalk(int id) {
        super(id, Material.plants);
        // 1.5.2 BlockNetherStalk constructor (vanilla/server BlockNetherStalk.java:25)
        setTickRandomly(true);
    }

    /**
     * 1.5.2 BlockNetherStalk.updateTick (vanilla/server BlockNetherStalk.java:51-62): below age 3, 1 in 10 random
     * ticks grows one stage (flag 2), then BlockFlower.updateTick checks survival. FCBlockNetherStalk calls it in
     * the Nether only; elsewhere it runs checkFlowerChange alone.
     */
    @Override
    public void updateTick(World world, int i, int j, int k, Random rand) {
        int iMetadata = world.getBlockMetadata(i, j, k);

        if (iMetadata < 3 && rand.nextInt(10) == 0) {
            ++iMetadata;
            world.setBlockMetadata(i, j, k, iMetadata, 2);
        }

        super.updateTick(world, i, j, k, rand);
    }

    // 1.5.2 BlockNetherStalk.getRenderType (vanilla/client BlockNetherStalk.java:77-80): the crop '#' shape, not
    // BlockFlower's crossed squares. FCBlockNetherStalk (115, a native MC block) draws itself in-world through its
    // RenderBlock override (renderBlockCrops); the id selects renderBlockAsItemVanilla's crops branch and keeps
    // DoesItemRenderAsBlock false.
    @Override
    public int getRenderType() {
        return 6;
    }

    /**
     * 1.5.2 BlockNetherStalk.dropBlockAsItemWithChance (vanilla/server BlockNetherStalk.java:75-96): one wart, or
     * 2-4 (plus up to the fortune level) when ripe (age 3). FCBlockNetherStalk does not override it. With
     * Block's drop the block id came out as a single wart whatever the age, for harvests and for the warts that
     * checkFlowerChange drops.
     */
    @Override
    public void dropBlockAsItemWithChance(World world, int i, int j, int k, int iMetadata, float fChance,
            int iFortuneModifier) {
        if (!world.isRemote) {
            int iCount = 1;

            if (iMetadata >= 3) {
                iCount = 2 + world.rand.nextInt(3);

                if (iFortuneModifier > 0) {
                    iCount += world.rand.nextInt(iFortuneModifier + 1);
                }
            }

            for (int iTemp = 0; iTemp < iCount; ++iTemp) {
                dropBlockAsItem_do(world, i, j, k, new ItemStack(Item.netherStalkSeeds));
            }
        }
    }

    // 1.5.2 BlockNetherStalk.idDropped / quantityDropped (vanilla/server BlockNetherStalk.java:101-112): the drop is
    // made in dropBlockAsItemWithChance alone.
    @Override
    public int idDropped(int iMetadata, Random rand, int iFortuneModifier) {
        return 0;
    }

    @Override
    public int quantityDropped(Random rand) {
        return 0;
    }
}
