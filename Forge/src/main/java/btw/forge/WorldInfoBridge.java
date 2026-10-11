package btw.forge;

import net.minecraft.server.level.ServerLevel;

import java.lang.ref.WeakReference;

/**
 * Bridges MC 1.20.1 ServerLevel data to FC's WorldInfo.
 * FC code accesses world.worldInfo.getWorldTime() etc. directly.
 *
 * The level is held weakly: this object hangs off WorldBridge.worldInfo, the value of
 * WorldBridge's ServerLevel-keyed WeakHashMap, so a strong reference would pin the key (RE2).
 * While the server runs the level is always alive; once it is gone the inherited WorldInfo
 * fields answer instead. The FC-only state (global ender inventory, progress flags) lives in
 * the inherited fields and is persisted by FcWorldSavedData.
 */
public class WorldInfoBridge extends btw.modern.WorldInfo {

    private final WeakReference<ServerLevel> levelRef;

    public WorldInfoBridge(ServerLevel level) {
        this.levelRef = new WeakReference<>(level);
    }

    @Override
    public long getWorldTime() {
        ServerLevel level = levelRef.get();
        return level != null ? level.getDayTime() : super.getWorldTime();
    }

    @Override
    public void setWorldTime(long time) {
        ServerLevel level = levelRef.get();
        if (level != null) {
            level.setDayTime(time);
        } else {
            super.setWorldTime(time);
        }
    }

    @Override
    public long getTotalWorldTime() {
        ServerLevel level = levelRef.get();
        return level != null ? level.getGameTime() : super.getTotalWorldTime();
    }

    @Override
    public boolean isRaining() {
        ServerLevel level = levelRef.get();
        return level != null ? level.isRaining() : super.isRaining();
    }

    @Override
    public boolean isThundering() {
        ServerLevel level = levelRef.get();
        return level != null ? level.isThundering() : super.isThundering();
    }

    @Override
    public int getSpawnX() {
        ServerLevel level = levelRef.get();
        return level != null ? level.getSharedSpawnPos().getX() : super.getSpawnX();
    }

    @Override
    public int getSpawnY() {
        ServerLevel level = levelRef.get();
        return level != null ? level.getSharedSpawnPos().getY() : super.getSpawnY();
    }

    @Override
    public int getSpawnZ() {
        ServerLevel level = levelRef.get();
        return level != null ? level.getSharedSpawnPos().getZ() : super.getSpawnZ();
    }

    @Override
    public long getSeed() {
        ServerLevel level = levelRef.get();
        return level != null ? level.getSeed() : super.getSeed();
    }

    @Override
    public String getWorldName() {
        ServerLevel level = levelRef.get();
        return level != null ? level.getServer().getWorldData().getLevelName() : super.getWorldName();
    }
}
