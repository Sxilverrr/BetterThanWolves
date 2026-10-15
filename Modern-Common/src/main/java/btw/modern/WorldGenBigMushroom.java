package btw.modern;

import java.util.Random;

/**
 * 1.5.2 WorldGenBigMushroom (vanilla/server WorldGenBigMushroom.java, no FCMOD edits): the huge brown or red
 * mushroom that BlockMushroom.fertilizeMushroom grows from a mushroom on mycelium. The logic is the 1.5.2 code;
 * the port differences are marked.
 */
public class WorldGenBigMushroom extends WorldGenerator {

    /** 1.5.2 Block.mushroomCapBrown / mushroomCapRed ids (vanilla/server Block.java:314-315): 99 + mushroom type. */
    private static final int MUSHROOM_CAP_BROWN_ID = 99;

    /** The mushroom type. 0 for brown, 1 for red, -1 for a random one. */
    private int mushroomType = -1;

    // Blocks are placed through WorldGenerator.setBlockAndMetadata (1.5.2 flag 3 when grown from a mushroom,
    // flag 2 for world generation).
    public WorldGenBigMushroom(int type) {
        super(true);
        this.mushroomType = type;
    }

    public WorldGenBigMushroom() {
        super(false);
    }

    // 1.5.2 WorldGenBigMushroom.generate (vanilla/server WorldGenBigMushroom.java:34-223). Port: the 1.5.2 world
    // bounds 0 and 256 are the world's build range here (World.getFcMinBuildHeight / getFcMaxBuildHeight, which
    // are 0 / 256 for a 1.5.2 world and the level's range under WorldBridge).
    @Override
    public boolean generate(World world, Random rand, int x, int y, int z) {
        int type = rand.nextInt(2);

        if (mushroomType >= 0) {
            type = mushroomType;
        }

        int height = rand.nextInt(3) + 4;
        boolean clear = true;
        int minY = world.getFcMinBuildHeight();
        int maxY = world.getFcMaxBuildHeight();

        if (y < minY + 1 || y + height + 1 >= maxY) {
            return false;
        }

        // the stalk column and the cap volume must be air or leaves
        for (int checkJ = y; checkJ <= y + 1 + height; ++checkJ) {
            byte radius = 3;

            if (checkJ <= y + 3) {
                radius = 0;
            }

            for (int checkI = x - radius; checkI <= x + radius && clear; ++checkI) {
                for (int checkK = z - radius; checkK <= z + radius && clear; ++checkK) {
                    if (checkJ >= minY && checkJ < maxY) {
                        int checkID = world.getBlockId(checkI, checkJ, checkK);

                        if (checkID != 0 && checkID != Block.leaves.blockID) {
                            clear = false;
                        }
                    } else {
                        clear = false;
                    }
                }
            }
        }

        if (!clear) {
            return false;
        }

        int soilID = world.getBlockId(x, y - 1, z);

        if (soilID != Block.dirt.blockID && soilID != Block.grass.blockID && soilID != Block.mycelium.blockID) {
            return false;
        }

        int capBottomJ = y + height;

        if (type == 1) {
            capBottomJ = y + height - 3;
        }

        for (int capJ = capBottomJ; capJ <= y + height; ++capJ) {
            int capRadius = 1;

            if (capJ < y + height) {
                ++capRadius;
            }

            if (type == 0) {
                capRadius = 3;
            }

            for (int capI = x - capRadius; capI <= x + capRadius; ++capI) {
                for (int capK = z - capRadius; capK <= z + capRadius; ++capK) {
                    // cap texture metadata: 1-9 = the 3x3 compass of cap faces, 0 = pores only
                    int capMeta = 5;

                    if (capI == x - capRadius) {
                        --capMeta;
                    }

                    if (capI == x + capRadius) {
                        ++capMeta;
                    }

                    if (capK == z - capRadius) {
                        capMeta -= 3;
                    }

                    if (capK == z + capRadius) {
                        capMeta += 3;
                    }

                    if (type == 0 || capJ < y + height) {
                        if ((capI == x - capRadius || capI == x + capRadius) &&
                            (capK == z - capRadius || capK == z + capRadius)) {
                            continue;
                        }

                        if (capI == x - (capRadius - 1) && capK == z - capRadius) {
                            capMeta = 1;
                        }

                        if (capI == x - capRadius && capK == z - (capRadius - 1)) {
                            capMeta = 1;
                        }

                        if (capI == x + (capRadius - 1) && capK == z - capRadius) {
                            capMeta = 3;
                        }

                        if (capI == x + capRadius && capK == z - (capRadius - 1)) {
                            capMeta = 3;
                        }

                        if (capI == x - (capRadius - 1) && capK == z + capRadius) {
                            capMeta = 7;
                        }

                        if (capI == x - capRadius && capK == z + (capRadius - 1)) {
                            capMeta = 7;
                        }

                        if (capI == x + (capRadius - 1) && capK == z + capRadius) {
                            capMeta = 9;
                        }

                        if (capI == x + capRadius && capK == z + (capRadius - 1)) {
                            capMeta = 9;
                        }
                    }

                    if (capMeta == 5 && capJ < y + height) {
                        capMeta = 0;
                    }

                    // (the 1.5.2 "y >= y + height - 1" term is never true; kept as written)
                    if ((capMeta != 0 || y >= y + height - 1) &&
                        !Block.opaqueCubeLookup[world.getBlockId(capI, capJ, capK)]) {
                        setBlockAndMetadata(world, capI, capJ, capK, MUSHROOM_CAP_BROWN_ID + type, capMeta);
                    }
                }
            }
        }

        // the stalk: cap block metadata 10
        for (int stalkJ = 0; stalkJ < height; ++stalkJ) {
            int stalkID = world.getBlockId(x, y + stalkJ, z);

            if (!Block.opaqueCubeLookup[stalkID]) {
                setBlockAndMetadata(world, x, y + stalkJ, z, MUSHROOM_CAP_BROWN_ID + type, 10);
            }
        }

        return true;
    }
}
