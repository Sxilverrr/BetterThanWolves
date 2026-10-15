package btw.modern;

public class BlockIce extends BlockBreakable {

    // 1.5.2 BlockIce constructor sets the random-tick flag (vanilla/server BlockIce.java:24); FCBlockIce.updateTick melts on it
    protected BlockIce(int id) {
        super(id, Material.ice, false);
        this.setTickRandomly(true);
    }
}
