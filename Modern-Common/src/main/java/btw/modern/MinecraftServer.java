package btw.modern;

public abstract class MinecraftServer {
    private static MinecraftServer instance;

    public WorldServer[] worldServers;

    public static MinecraftServer getServer() {
        return instance;
    }

    public static void setInstance(MinecraftServer server) {
        instance = server;
    }

    public abstract boolean isSinglePlayer();

    public abstract ServerConfigurationManager getConfigurationManager();

    public abstract WorldServer worldServerForDimension(int dimension);

    public abstract ILogAgent getLogAgent();

    public boolean getPublic() { return false; }

    // 1.5.2 MinecraftServer.getAllowNether (vanilla/server MinecraftServer.java:681-684; the
    // dedicated server reads "allow-nether", DedicatedServer.java:264-267). Read by the frozen
    // Entity/EntityMinecart portal block while inPortal (vanilla/client Entity.java:462-485) to
    // decide whether to call travelToDimension. btw.forge.ForgeMinecraftServerWrapper overrides it.
    public boolean getAllowNether() { return true; }
}
