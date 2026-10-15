package btw.modern;

public class BlockMycelium extends Block {

    protected BlockMycelium(int id) {
        super(id, Material.grass);
        // 1.5.2 BlockMycelium constructor (vanilla/server BlockMycelium.java:23): FCBlockMycelium.updateTick (death
        // in the dark, spread) runs on the random tick
        setTickRandomly(true);
    }
}
