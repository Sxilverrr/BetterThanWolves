package btw.modern;

/**
 * 1.5.2 ChunkCache: the read-only IBlockAccess snapshot that World.getEntityPathToXYZ hands to
 * PathFinder (createEntityPathTo's water-surface scan and start-block
 * ShouldOffsetPositionIfPathingOutOf / CanPathThroughBlock checks, vanilla/client
 * PathFinder.java:495-546, 566-592). Ported from vanilla/client ChunkCache.java, the frozen copy
 * that used to win in dev and the jar; this class is now the only copy (Forge/build.gradle no
 * longer merges the frozen one).
 *
 * Port change: 1.5.2 clips y to [0, 256) (ChunkCache.java:82-89, 213-225, 246-253, 340-348,
 * 410-418, 437). A 1.20.1 level spans [minBuildHeight, maxBuildHeight) (overworld -64..320), and
 * reading air below y=0 made PathFinder offset/abort the start point of every cave mob there, so
 * the clip uses the wrapped world's getFcMinBuildHeight/getFcMaxBuildHeight (0/256 unless the world
 * is a WorldBridge, i.e. exactly 1.5.2). Chunks outside the cached area read as air / default light
 * instead of throwing.
 */
public class ChunkCache implements IBlockAccess {

    private final int chunkX;
    private final int chunkZ;
    private final Chunk[][] chunkArray;

    /** set by !chunk.getAreLevelsEmpty */
    private boolean hasExtendedLevels;

    /** Reference to the World object. */
    private final World worldObj;

    /** The wrapped world's build range [buildMinY, buildMaxY); 1.5.2 hardcoded [0, 256). */
    private final int buildMinY;
    private final int buildMaxY;

    /** Same 8-arg signature as 1.5.2 (vanilla/client ChunkCache.java:29-66). */
    public ChunkCache(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int padding) {
        this.worldObj = world;
        this.buildMinY = world.getFcMinBuildHeight();
        this.buildMaxY = world.getFcMaxBuildHeight();
        this.chunkX = (minX - padding) >> 4;
        this.chunkZ = (minZ - padding) >> 4;
        int maxChunkX = (maxX + padding) >> 4;
        int maxChunkZ = (maxZ + padding) >> 4;
        this.chunkArray = new Chunk[maxChunkX - this.chunkX + 1][maxChunkZ - this.chunkZ + 1];
        this.hasExtendedLevels = true;

        for (int cx = this.chunkX; cx <= maxChunkX; ++cx) {
            for (int cz = this.chunkZ; cz <= maxChunkZ; ++cz) {
                Chunk chunk = world.getChunkFromChunkCoords(cx, cz);

                if (chunk != null) {
                    this.chunkArray[cx - this.chunkX][cz - this.chunkZ] = chunk;
                }
            }
        }

        for (int cx = minX >> 4; cx <= maxX >> 4; ++cx) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; ++cz) {
                Chunk chunk = this.chunkArray[cx - this.chunkX][cz - this.chunkZ];

                if (chunk != null && !chunk.getAreLevelsEmpty(minY, maxY)) {
                    this.hasExtendedLevels = false;
                }
            }
        }
    }

    /** Cached chunk holding column (x, z), or null when it is outside the cached area or was not loaded. */
    private Chunk chunkAt(int x, int z) {
        int cx = (x >> 4) - this.chunkX;
        int cz = (z >> 4) - this.chunkZ;

        if (cx >= 0 && cx < this.chunkArray.length && cz >= 0 && cz < this.chunkArray[cx].length) {
            return this.chunkArray[cx][cz];
        }

        return null;
    }

    /** Block.blocksList[getBlockId(x, y, z)] (null for air or an unknown id). */
    private Block blockAt(int x, int y, int z) {
        int id = this.getBlockId(x, y, z);
        return id > 0 && id < Block.blocksList.length ? Block.blocksList[id] : null;
    }

    /**
     * set by !chunk.getAreLevelsEmpty
     */
    public boolean extendedLevelsInChunkCache() {
        return this.hasExtendedLevels;
    }

    @Override
    public int getBlockId(int x, int y, int z) {
        // vanilla/client ChunkCache.java:82-89 clips y to [0, 256); use the level's build range.
        if (y < this.buildMinY || y >= this.buildMaxY) {
            return 0;
        }

        Chunk chunk = this.chunkAt(x, z);
        return chunk == null ? 0 : chunk.getBlockID(x & 15, y, z & 15);
    }

    @Override
    public TileEntity getBlockTileEntity(int x, int y, int z) {
        Chunk chunk = this.chunkAt(x, z);
        return chunk == null ? null : chunk.getChunkBlockTileEntity(x & 15, y, z & 15);
    }

    public float getBrightness(int x, int y, int z, int minLight) {
        int light = this.getLightValue(x, y, z);

        if (light < minLight) {
            light = minLight;
        }

        return this.brightnessFor(light);
    }

    /**
     * Any Light rendered on a 1.8 Block goes through here
     */
    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minBlockLight) {
        int sky = this.getSkyBlockTypeBrightness(EnumSkyBlock.Sky, x, y, z);
        int block = this.getSkyBlockTypeBrightness(EnumSkyBlock.Block, x, y, z);

        if (block < minBlockLight) {
            block = minBlockLight;
        }

        return sky << 20 | block << 4;
    }

    @Override
    public float getLightBrightness(int x, int y, int z) {
        return this.brightnessFor(this.getLightValue(x, y, z));
    }

    /** worldObj.provider.lightBrightnessTable[light] (vanilla/client ChunkCache.java:126, 151); 0 without a provider. */
    private float brightnessFor(int light) {
        WorldProvider provider = this.worldObj.provider;
        return provider == null ? 0.0F : provider.lightBrightnessTable[light];
    }

    public int getLightValue(int x, int y, int z) {
        return this.getLightValueExt(x, y, z, true);
    }

    public int getLightValueExt(int x, int y, int z, boolean useNeighborBrightness) {
        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z <= 30000000) {
            if (useNeighborBrightness) {
                int id = this.getBlockId(x, y, z);

                // FCMOD: Changed (Client Only) for proper lighting on mod blocks
                if (Block.useNeighborBrightness[id]) {
                    int light = this.getLightValueExt(x, y + 1, z, false);
                    int lightXPos = this.getLightValueExt(x + 1, y, z, false);
                    int lightXNeg = this.getLightValueExt(x - 1, y, z, false);
                    int lightZPos = this.getLightValueExt(x, y, z + 1, false);
                    int lightZNeg = this.getLightValueExt(x, y, z - 1, false);

                    if (lightXPos > light) {
                        light = lightXPos;
                    }

                    if (lightXNeg > light) {
                        light = lightXNeg;
                    }

                    if (lightZPos > light) {
                        light = lightZPos;
                    }

                    if (lightZNeg > light) {
                        light = lightZNeg;
                    }

                    return light;
                }
            }

            // vanilla/client ChunkCache.java:213-225 (0 / 256 there)
            if (y < this.buildMinY) {
                return 0;
            } else if (y >= this.buildMaxY) {
                int light = 15 - this.worldObj.skylightSubtracted;
                return light < 0 ? 0 : light;
            } else {
                Chunk chunk = this.chunkAt(x, z);
                return chunk == null ? 0 : chunk.getBlockLightValue(x & 15, y, z & 15, this.worldObj.skylightSubtracted);
            }
        } else {
            return 15;
        }
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        // vanilla/client ChunkCache.java:246-253 clips y to [0, 256); use the level's build range.
        if (y < this.buildMinY || y >= this.buildMaxY) {
            return 0;
        }

        Chunk chunk = this.chunkAt(x, z);
        return chunk == null ? 0 : chunk.getBlockMetadata(x & 15, y, z & 15);
    }

    @Override
    public Material getBlockMaterial(int x, int y, int z) {
        Block block = this.blockAt(x, y, z);
        return block == null ? Material.air : block.blockMaterial;
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return this.worldObj.getBiomeGenForCoords(x, z);
    }

    @Override
    public boolean isBlockOpaqueCube(int x, int y, int z) {
        Block block = this.blockAt(x, y, z);
        return block != null && block.isOpaqueCube();
    }

    /**
     * 1.5.2 ChunkCache's own test (vanilla/client ChunkCache.java:291-295): movement-blocking
     * material and a normal render shape (not World/Block.isNormalCube).
     */
    @Override
    public boolean isBlockNormalCube(int x, int y, int z) {
        Block block = this.blockAt(x, y, z);
        return block != null && block.blockMaterial.blocksMovement() && block.renderAsNormalBlock();
    }

    // FCMOD: Changed (vanilla/client ChunkCache.java:308-313)
    public boolean doesBlockHaveSolidTopSurface(int i, int j, int k) {
        Block block = this.blockAt(i, j, k);
        return block != null && block.HasLargeCenterHardPointToFacing(this, i, j, k, 1);
    }

    @Override
    public Vec3Pool getWorldVec3Pool() {
        return this.worldObj.getWorldVec3Pool();
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        return this.blockAt(x, y, z) == null;
    }

    /**
     * Brightness for SkyBlock.Sky is clear white and (through color computing it is assumed) DEPENDENT ON DAYTIME.
     * Brightness for SkyBlock.Block is yellowish and independent.
     */
    public int getSkyBlockTypeBrightness(EnumSkyBlock type, int x, int y, int z) {
        // vanilla/client ChunkCache.java:340-348 clamps y into [0, 255]; the level's build range here.
        y = this.clampY(y);

        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z <= 30000000) {
            if (type == EnumSkyBlock.Sky && this.worldObj.provider != null && this.worldObj.provider.hasNoSky) {
                return 0;
            }

            if (Block.useNeighborBrightness[this.getBlockId(x, y, z)]) {
                int light = this.getSpecialBlockBrightness(type, x, y + 1, z);
                int lightXPos = this.getSpecialBlockBrightness(type, x + 1, y, z);
                int lightXNeg = this.getSpecialBlockBrightness(type, x - 1, y, z);
                int lightZPos = this.getSpecialBlockBrightness(type, x, y, z + 1);
                int lightZNeg = this.getSpecialBlockBrightness(type, x, y, z - 1);

                if (lightXPos > light) {
                    light = lightXPos;
                }

                if (lightXNeg > light) {
                    light = lightXNeg;
                }

                if (lightZPos > light) {
                    light = lightZPos;
                }

                if (lightZNeg > light) {
                    light = lightZNeg;
                }

                return light;
            }

            Chunk chunk = this.chunkAt(x, z);
            return chunk == null ? type.defaultLightValue : chunk.getSavedLightValue(type, x & 15, y, z & 15);
        } else {
            return type.defaultLightValue;
        }
    }

    /**
     * is only used on stairs and tilled fields
     */
    public int getSpecialBlockBrightness(EnumSkyBlock type, int x, int y, int z) {
        // vanilla/client ChunkCache.java:410-418 clamps y into [0, 255]; the level's build range here.
        y = this.clampY(y);

        if (x >= -30000000 && z >= -30000000 && x < 30000000 && z <= 30000000) {
            Chunk chunk = this.chunkAt(x, z);
            return chunk == null ? type.defaultLightValue : chunk.getSavedLightValue(type, x & 15, y, z & 15);
        } else {
            return type.defaultLightValue;
        }
    }

    private int clampY(int y) {
        if (y < this.buildMinY) {
            y = this.buildMinY;
        }

        if (y >= this.buildMaxY) {
            y = this.buildMaxY - 1;
        }

        return y;
    }

    /**
     * Returns current world height (1.5.2: 256, vanilla/client ChunkCache.java:435-438).
     */
    public int getHeight() {
        return this.buildMaxY;
    }

    /**
     * Is this block powering in the specified direction Args: x, y, z, direction
     */
    public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
        Block block = this.blockAt(x, y, z);
        return block == null ? 0 : block.isProvidingStrongPower(this, x, y, z, side);
    }
}
