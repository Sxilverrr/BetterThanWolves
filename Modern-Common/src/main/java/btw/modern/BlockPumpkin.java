package btw.modern;

public class BlockPumpkin extends BlockDirectional {

    // 1.5.2 BlockPumpkin constructor sets the random-tick flag (vanilla/server BlockPumpkin.java:24); both port
    // constructors stand for that one 1.5.2 constructor
    protected BlockPumpkin(int id) {
        super(id, Material.pumpkin);
        this.setTickRandomly(true);
    }

    protected BlockPumpkin(int id, boolean isLantern) {
        super(id, Material.pumpkin);
        this.setTickRandomly(true);
    }
}
