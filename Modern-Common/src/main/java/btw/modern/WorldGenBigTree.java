package btw.modern;

import java.util.Random;

/**
 * 1.5.2 WorldGenBigTree with its FCMOD stump (vanilla/server WorldGenBigTree.java): the big oak that
 * FCBlockSapling.growTree grows from an oak sapling one time in ten, through FCUtilsGenBigTree. FCUtilsGenBigTree
 * overrides genTreeLayer, checkBlockLine (both test air with World.isAirBlock) and validTreeLocation (FC sapling
 * soils); everything else is this class. The logic is the 1.5.2 code. The stand-in's generate returned false, so
 * the big-oak roll always failed and put the sapling back.
 */
public class WorldGenBigTree extends WorldGenerator {

    /**
     * Contains three sets of two values that provide complimentary indices for a given 'major' index - 1 and 2 for 0,
     * 0 and 2 for 1, and 0 and 1 for 2.
     */
    public static final byte[] otherCoordPairs = new byte[] {(byte)2, (byte)0, (byte)0, (byte)1, (byte)2, (byte)1};

    /** Random seed for the tree, set from the caller's random in generate. */
    Random rand = new Random();

    public World worldObj;
    public int[] basePos = new int[] {0, 0, 0};
    public int heightLimit = 0;
    int height;
    double heightAttenuation = 0.618D;
    double branchDensity = 1.0D;
    double branchSlope = 0.381D;
    double scaleWidth = 1.0D;
    double leafDensity = 1.0D;

    /** Always 1; 2 would generate a double-sized trunk. */
    int trunkSize = 1;

    /** The limit of the random value used to initialize the height limit. */
    public int heightLimitLimit = 12;

    /** How far away from the base leaf node the generator populates leaves. */
    int leafDistanceLimit = 4;

    /** The points at which to generate groups of leaves. */
    int[][] leafNodes;

    public WorldGenBigTree(boolean notify) { super(notify); }

    /** 1.5.2 WorldGenBigTree.generateLeafNodeList (vanilla/server WorldGenBigTree.java:66-147). */
    void generateLeafNodeList() {
        this.height = (int)((double)this.heightLimit * this.heightAttenuation);

        if (this.height >= this.heightLimit) {
            this.height = this.heightLimit - 1;
        }

        int nodesPerLayer = (int)(1.382D + Math.pow(this.leafDensity * (double)this.heightLimit / 13.0D, 2.0D));

        if (nodesPerLayer < 1) {
            nodesPerLayer = 1;
        }

        int[][] nodes = new int[nodesPerLayer * this.heightLimit][4];
        int layerY = this.basePos[1] + this.heightLimit - this.leafDistanceLimit;
        int nodeCount = 1;
        int trunkTopY = this.basePos[1] + this.height;
        int layerOffset = layerY - this.basePos[1];
        nodes[0][0] = this.basePos[0];
        nodes[0][1] = layerY;
        nodes[0][2] = this.basePos[2];
        nodes[0][3] = trunkTopY;
        --layerY;

        while (layerOffset >= 0) {
            int nodeIndex = 0;
            float layerSize = this.layerSize(layerOffset);

            if (layerSize < 0.0F) {
                --layerY;
                --layerOffset;
            } else {
                for (double centerOffset = 0.5D; nodeIndex < nodesPerLayer; ++nodeIndex) {
                    double radius = this.scaleWidth * (double)layerSize * ((double)this.rand.nextFloat() + 0.328D);
                    double angle = (double)this.rand.nextFloat() * 2.0D * Math.PI;
                    int nodeX = MathHelper.floor_double(radius * Math.sin(angle) + (double)this.basePos[0] + centerOffset);
                    int nodeZ = MathHelper.floor_double(radius * Math.cos(angle) + (double)this.basePos[2] + centerOffset);
                    int[] nodeBottom = new int[] {nodeX, layerY, nodeZ};
                    int[] nodeTop = new int[] {nodeX, layerY + this.leafDistanceLimit, nodeZ};

                    if (this.checkBlockLine(nodeBottom, nodeTop) == -1) {
                        int[] branchBase = new int[] {this.basePos[0], this.basePos[1], this.basePos[2]};
                        double distance = Math.sqrt(
                                Math.pow((double)Math.abs(this.basePos[0] - nodeBottom[0]), 2.0D) +
                                Math.pow((double)Math.abs(this.basePos[2] - nodeBottom[2]), 2.0D));
                        double branchDrop = distance * this.branchSlope;

                        if ((double)nodeBottom[1] - branchDrop > (double)trunkTopY) {
                            branchBase[1] = trunkTopY;
                        } else {
                            branchBase[1] = (int)((double)nodeBottom[1] - branchDrop);
                        }

                        if (this.checkBlockLine(branchBase, nodeBottom) == -1) {
                            nodes[nodeCount][0] = nodeX;
                            nodes[nodeCount][1] = layerY;
                            nodes[nodeCount][2] = nodeZ;
                            nodes[nodeCount][3] = branchBase[1];
                            ++nodeCount;
                        }
                    }
                }

                --layerY;
                --layerOffset;
            }
        }

        this.leafNodes = new int[nodeCount][4];
        System.arraycopy(nodes, 0, this.leafNodes, 0, nodeCount);
    }

    /**
     * 1.5.2 WorldGenBigTree.genTreeLayer (vanilla/server WorldGenBigTree.java:149-189): a disc of blockID around
     * (x, y, z) perpendicular to axis, over air and leaves only. FCUtilsGenBigTree overrides it.
     */
    public void genTreeLayer(int x, int y, int z, float radius, byte axis, int blockID) {
        int extent = (int)((double)radius + 0.618D);
        byte axisA = otherCoordPairs[axis];
        byte axisB = otherCoordPairs[axis + 3];
        int[] center = new int[] {x, y, z};
        int[] pos = new int[] {0, 0, 0};
        int offsetA = -extent;
        int offsetB;

        for (pos[axis] = center[axis]; offsetA <= extent; ++offsetA) {
            pos[axisA] = center[axisA] + offsetA;
            offsetB = -extent;

            while (offsetB <= extent) {
                double distanceSq = Math.pow((double)Math.abs(offsetA) + 0.5D, 2.0D) +
                        Math.pow((double)Math.abs(offsetB) + 0.5D, 2.0D);

                if (distanceSq > (double)(radius * radius)) {
                    ++offsetB;
                } else {
                    pos[axisB] = center[axisB] + offsetB;
                    int id = this.worldObj.getBlockId(pos[0], pos[1], pos[2]);

                    if (id != 0 && id != Block.leaves.blockID) {
                        ++offsetB;
                    } else {
                        this.setBlockAndMetadata(this.worldObj, pos[0], pos[1], pos[2], blockID, 0);
                        ++offsetB;
                    }
                }
            }
        }
    }

    /** 1.5.2 WorldGenBigTree.layerSize (vanilla/server WorldGenBigTree.java:194-222): rough size of a layer. */
    float layerSize(int layer) {
        if ((double)layer < (double)((float)this.heightLimit) * 0.3D) {
            return -1.618F;
        } else {
            float halfHeight = (float)this.heightLimit / 2.0F;
            float fromMiddle = (float)this.heightLimit / 2.0F - (float)layer;
            float size;

            if (fromMiddle == 0.0F) {
                size = halfHeight;
            } else if (Math.abs(fromMiddle) >= halfHeight) {
                size = 0.0F;
            } else {
                size = (float)Math.sqrt(Math.pow((double)Math.abs(halfHeight), 2.0D) -
                        Math.pow((double)Math.abs(fromMiddle), 2.0D));
            }

            size *= 0.5F;
            return size;
        }
    }

    /** 1.5.2 WorldGenBigTree.leafSize (vanilla/server WorldGenBigTree.java:224-227). */
    float leafSize(int layer) {
        return layer >= 0 && layer < this.leafDistanceLimit ?
                (layer != 0 && layer != this.leafDistanceLimit - 1 ? 3.0F : 2.0F) : -1.0F;
    }

    /** 1.5.2 WorldGenBigTree.generateLeafNode (vanilla/server WorldGenBigTree.java:232-241). */
    void generateLeafNode(int x, int y, int z) {
        int layerY = y;

        for (int topY = y + this.leafDistanceLimit; layerY < topY; ++layerY) {
            float size = this.leafSize(layerY - y);
            this.genTreeLayer(x, layerY, z, size, (byte)1, Block.leaves.blockID);
        }
    }

    /**
     * 1.5.2 WorldGenBigTree.placeBlockLine (vanilla/server WorldGenBigTree.java:246-307): a line of blockID from
     * start to end, logs turned along the line's horizontal axis (metadata 4 or 8).
     */
    public void placeBlockLine(int[] start, int[] end, int blockID) {
        int[] delta = new int[] {0, 0, 0};
        byte axisIndex = 0;
        byte majorAxis;

        for (majorAxis = 0; axisIndex < 3; ++axisIndex) {
            delta[axisIndex] = end[axisIndex] - start[axisIndex];

            if (Math.abs(delta[axisIndex]) > Math.abs(delta[majorAxis])) {
                majorAxis = axisIndex;
            }
        }

        if (delta[majorAxis] != 0) {
            byte axisA = otherCoordPairs[majorAxis];
            byte axisB = otherCoordPairs[majorAxis + 3];
            byte step;

            if (delta[majorAxis] > 0) {
                step = 1;
            } else {
                step = -1;
            }

            double slopeA = (double)delta[axisA] / (double)delta[majorAxis];
            double slopeB = (double)delta[axisB] / (double)delta[majorAxis];
            int[] pos = new int[] {0, 0, 0};
            int offset = 0;

            for (int endOffset = delta[majorAxis] + step; offset != endOffset; offset += step) {
                pos[majorAxis] = MathHelper.floor_double((double)(start[majorAxis] + offset) + 0.5D);
                pos[axisA] = MathHelper.floor_double((double)start[axisA] + (double)offset * slopeA + 0.5D);
                pos[axisB] = MathHelper.floor_double((double)start[axisB] + (double)offset * slopeB + 0.5D);
                byte logMetadata = 0;
                int distanceX = Math.abs(pos[0] - start[0]);
                int distanceZ = Math.abs(pos[2] - start[2]);
                int distanceMax = Math.max(distanceX, distanceZ);

                if (distanceMax > 0) {
                    if (distanceX == distanceMax) {
                        logMetadata = 4;
                    } else if (distanceZ == distanceMax) {
                        logMetadata = 8;
                    }
                }

                this.setBlockAndMetadata(this.worldObj, pos[0], pos[1], pos[2], blockID, logMetadata);
            }
        }
    }

    /** 1.5.2 WorldGenBigTree.generateLeaves (vanilla/server WorldGenBigTree.java:312-323). */
    void generateLeaves() {
        int nodeIndex = 0;

        for (int nodeCount = this.leafNodes.length; nodeIndex < nodeCount; ++nodeIndex) {
            int x = this.leafNodes[nodeIndex][0];
            int y = this.leafNodes[nodeIndex][1];
            int z = this.leafNodes[nodeIndex][2];
            this.generateLeafNode(x, y, z);
        }
    }

    /** 1.5.2 WorldGenBigTree.leafNodeNeedsBase (vanilla/server WorldGenBigTree.java:328-331). */
    boolean leafNodeNeedsBase(int heightAboveBase) {
        return (double)heightAboveBase >= (double)this.heightLimit * 0.2D;
    }

    /** 1.5.2 WorldGenBigTree.generateTrunk (vanilla/server WorldGenBigTree.java:337-359). */
    void generateTrunk() {
        int x = this.basePos[0];
        int bottomY = this.basePos[1];
        int topY = this.basePos[1] + this.height;
        int z = this.basePos[2];
        int[] bottom = new int[] {x, bottomY, z};
        int[] top = new int[] {x, topY, z};
        this.placeBlockLine(bottom, top, Block.wood.blockID);

        if (this.trunkSize == 2) {
            ++bottom[0];
            ++top[0];
            this.placeBlockLine(bottom, top, Block.wood.blockID);
            ++bottom[2];
            ++top[2];
            this.placeBlockLine(bottom, top, Block.wood.blockID);
            bottom[0] += -1;
            top[0] += -1;
            this.placeBlockLine(bottom, top, Block.wood.blockID);
        }
    }

    /**
     * 1.5.2 WorldGenBigTree.generateLeafNodeBases (vanilla/server WorldGenBigTree.java:364-381): branches from the
     * trunk to the leaf nodes high enough to need them.
     */
    public void generateLeafNodeBases() {
        int nodeIndex = 0;
        int nodeCount = this.leafNodes.length;

        for (int[] branchBase = new int[] {this.basePos[0], this.basePos[1], this.basePos[2]};
             nodeIndex < nodeCount; ++nodeIndex) {
            int[] node = this.leafNodes[nodeIndex];
            int[] branchEnd = new int[] {node[0], node[1], node[2]};
            branchBase[1] = node[3];
            int heightAboveBase = branchBase[1] - this.basePos[1];

            if (this.leafNodeNeedsBase(heightAboveBase)) {
                this.placeBlockLine(branchBase, branchEnd, (byte)Block.wood.blockID);
            }
        }
    }

    /**
     * 1.5.2 WorldGenBigTree.checkBlockLine (vanilla/server WorldGenBigTree.java:387-443): the distance from start
     * towards end before a block other than air or leaves, or -1 when the line is clear. FCUtilsGenBigTree overrides it.
     */
    public int checkBlockLine(int[] start, int[] end) {
        int[] delta = new int[] {0, 0, 0};
        byte axisIndex = 0;
        byte majorAxis;

        for (majorAxis = 0; axisIndex < 3; ++axisIndex) {
            delta[axisIndex] = end[axisIndex] - start[axisIndex];

            if (Math.abs(delta[axisIndex]) > Math.abs(delta[majorAxis])) {
                majorAxis = axisIndex;
            }
        }

        if (delta[majorAxis] == 0) {
            return -1;
        } else {
            byte axisA = otherCoordPairs[majorAxis];
            byte axisB = otherCoordPairs[majorAxis + 3];
            byte step;

            if (delta[majorAxis] > 0) {
                step = 1;
            } else {
                step = -1;
            }

            double slopeA = (double)delta[axisA] / (double)delta[majorAxis];
            double slopeB = (double)delta[axisB] / (double)delta[majorAxis];
            int[] pos = new int[] {0, 0, 0};
            int offset = 0;
            int endOffset;

            for (endOffset = delta[majorAxis] + step; offset != endOffset; offset += step) {
                pos[majorAxis] = start[majorAxis] + offset;
                pos[axisA] = MathHelper.floor_double((double)start[axisA] + (double)offset * slopeA);
                pos[axisB] = MathHelper.floor_double((double)start[axisB] + (double)offset * slopeB);
                int id = this.worldObj.getBlockId(pos[0], pos[1], pos[2]);

                if (id != 0 && id != Block.leaves.blockID) {
                    break;
                }
            }

            return offset == endOffset ? -1 : Math.abs(offset);
        }
    }

    /**
     * 1.5.2 WorldGenBigTree.validTreeLocation (vanilla/server WorldGenBigTree.java:449-477): grass or dirt below and
     * at least 6 clear blocks up (the height limit shrinks to the clear run). FCUtilsGenBigTree overrides it.
     */
    public boolean validTreeLocation() {
        int[] bottom = new int[] {this.basePos[0], this.basePos[1], this.basePos[2]};
        int[] top = new int[] {this.basePos[0], this.basePos[1] + this.heightLimit - 1, this.basePos[2]};
        int soilID = this.worldObj.getBlockId(this.basePos[0], this.basePos[1] - 1, this.basePos[2]);

        if (soilID != 2 && soilID != 3) {
            return false;
        } else {
            int clearDistance = this.checkBlockLine(bottom, top);

            if (clearDistance == -1) {
                return true;
            } else if (clearDistance < 6) {
                return false;
            } else {
                this.heightLimit = clearDistance;
                return true;
            }
        }
    }

    /** 1.5.2 WorldGenBigTree.setScale (vanilla/server WorldGenBigTree.java:482-493). */
    @Override
    public void setScale(double heightScale, double widthScale, double leafDensityScale) {
        this.heightLimitLimit = (int)(heightScale * 12.0D);

        if (heightScale > 0.5D) {
            this.leafDistanceLimit = 5;
        }

        this.scaleWidth = widthScale;
        this.leafDensity = leafDensityScale;
    }

    /**
     * 1.5.2 WorldGenBigTree.generate (vanilla/server WorldGenBigTree.java:495-534): leaves, trunk and branches, then
     * the FCMOD stump: the bottom log gets metadata | 12 through a metadata-only write, so no breakBlock makes the
     * leaves around it decay.
     */
    @Override
    public boolean generate(World world, Random random, int x, int y, int z) {
        this.worldObj = world;
        long seed = random.nextLong();
        this.rand.setSeed(seed);
        this.basePos[0] = x;
        this.basePos[1] = y;
        this.basePos[2] = z;

        if (this.heightLimit == 0) {
            this.heightLimit = 5 + this.rand.nextInt(this.heightLimitLimit);
        }

        if (!this.validTreeLocation()) {
            return false;
        } else {
            this.generateLeafNodeList();
            this.generateLeaves();
            this.generateTrunk();
            this.generateLeafNodeBases();

            // FCMOD: Added
            int iTrunkBlockId = world.getBlockId(x, y, z);

            if (iTrunkBlockId == Block.wood.blockID) {
                int iTrunkMetadata = world.getBlockMetadata(x, y, z);

                // set the metadata instead of the whole block to prevent a breakBlock() call forcing surrounding
                // leaves to update and decay
                world.setBlockMetadataWithClient(x, y, z, iTrunkMetadata | 12);
            }
            // END FCMOD

            return true;
        }
    }
}
