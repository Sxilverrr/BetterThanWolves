package btw.modern;

public class BlockRedstoneLight extends Block {

    protected BlockRedstoneLight(int id, boolean powered) {
        super(id, Material.glass);

        // 1.5.2 BlockRedstoneLight ctor (vanilla/server BlockRedstoneLight.java:23-32): the lit lamp (124) emits
        // full light, the unlit one (123) none (Block.lightValue, inherited by FCBlockRedstoneLight; read by
        // FCBlockLens).
        if (powered) {
            setLightValue(1.0F);
        }
    }
}
