package btw.modern;

public class BlockCarrot extends BlockCrops {

    protected BlockCarrot(int id) {
        super(id);
    }

    /** 1.5.2 BlockCarrot.getSeedItem (vanilla/server BlockCarrot.java:26-29): a carrot replants the crop. */
    @Override
    public int getSeedItem() {
        return Item.carrot.itemID;
    }

    /** 1.5.2 BlockCarrot.getCropItem (vanilla/server BlockCarrot.java:34-37). */
    @Override
    public int getCropItem() {
        return Item.carrot.itemID;
    }
}
