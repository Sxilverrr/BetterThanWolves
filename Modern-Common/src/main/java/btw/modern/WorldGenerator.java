package btw.modern;

import java.util.Random;

public abstract class WorldGenerator {
    /**
     * 1.5.2 WorldGenerator.doBlockNotify (vanilla/server WorldGenerator.java:20-24): false for world generation, true
     * when a sapling or mushroom grows the feature.
     */
    private final boolean doBlockNotify;

    public WorldGenerator() { this.doBlockNotify = false; }
    public WorldGenerator(boolean notify) { this.doBlockNotify = notify; }

    public abstract boolean generate(World world, Random rand, int x, int y, int z);

    public void setScale(double xScale, double yScale, double zScale) {}

    /** 1.5.2 WorldGenerator.setBlock (vanilla/server WorldGenerator.java:46-49). */
    public void setBlock(World world, int x, int y, int z, int blockID) {
        this.setBlockAndMetadata(world, x, y, z, blockID, 0);
    }

    /**
     * 1.5.2 WorldGenerator.setBlockAndMetadata (vanilla/server WorldGenerator.java:54-64): flag 3 (neighbours and
     * clients) when notifying, else flag 2. Every block of FCUtilsGenHugeTree (2x2 jungle trees), WorldGenBigTree /
     * FCUtilsGenBigTree (big oaks) and WorldGenBigMushroom goes through here; the empty stand-in placed nothing.
     */
    public void setBlockAndMetadata(World world, int x, int y, int z, int blockID, int metadata) {
        world.setBlock(x, y, z, blockID, metadata, this.doBlockNotify ? 3 : 2);
    }
}
