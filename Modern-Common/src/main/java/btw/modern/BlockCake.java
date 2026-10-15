package btw.modern;

public class BlockCake extends Block {

    // 1.5.2 BlockCake constructor sets the random-tick flag (vanilla/server BlockCake.java:23)
    protected BlockCake(int id) {
        super(id, Material.cake);
        this.setTickRandomly(true);
    }
}
