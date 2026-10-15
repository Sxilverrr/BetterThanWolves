package btw.modern;

public class BlockSnowBlock extends Block {

    // 1.5.2 BlockSnowBlock constructor sets the random-tick flag (vanilla/server BlockSnowBlock.java:23)
    protected BlockSnowBlock(int id) {
        super(id, Material.craftedSnow);
        this.setTickRandomly(true);
    }
}
