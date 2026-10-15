package btw.modern;

public class BlockStationary extends BlockFluid {

    // 1.5.2 BlockStationary constructor (vanilla/server BlockStationary.java:20-28): still fluids do not random-tick,
    // except lava. BlockFluid turns the flag on; still water (9) turns it back off, still lava (11, FCBlockLavaStationary)
    // keeps it, so blocksList[11] reports the 4.B random-tick flag that FCBlockLavaStationary.updateTick's fire walk needs.
    protected BlockStationary(int id, Material material) {
        super(id, material);
        this.setTickRandomly(false);

        if (material == Material.lava) {
            this.setTickRandomly(true);
        }
    }
}
