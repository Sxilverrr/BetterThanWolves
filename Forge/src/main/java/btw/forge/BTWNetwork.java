package btw.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * Bridges BTW's legacy packet system to Forge's SimpleChannel networking.
 *
 * <p>Handles two concerns:</p>
 * <ul>
 *   <li>Legacy BTW packets: FC's login handshake payloads (FC|VC version check,
 *       FC|OP hardcore options; {@link FCPayload}). FCPacket166StartBlockHarvest
 *       needs no packet (see register()).</li>
 *   <li>Penalty level sync: sends FC penalty levels (gloom, fat, hunger,
 *       health) and the FC food level from server to client each tick,
 *       so the client-side HUD overlay can display them.</li>
 * </ul>
 *
 * <p>FC originally synced penalty levels via DataWatcher entries (IDs 22-31).
 * In MC 1.20.1, injecting custom {@code SynchedEntityData} accessors onto
 * {@code Player} is complex. Instead, we use a simple custom packet that
 * sends the five integer values to the owning client every tick.</p>
 */
public class BTWNetwork {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Network");
    // "2": FCAnimSync added (message ids are positional, so a mismatched peer must be refused).
    // "3": FCPayload added (FC login handshake payloads).
    // "4": FcAuxSfx added (1.5.2 Packet61 aux effects run by the client's FC handler).
    private static final String PROTOCOL_VERSION = "4";

    /** FCBetterThanWolves.fcCustomPacketChannelVersionCheck (Client FCBetterThanWolves.java:666). */
    public static final String FC_CHANNEL_VERSION_CHECK = "FC|VC";
    /** FCBetterThanWolves.fcCustomPacketChannelBTWOptions (Client FCBetterThanWolves.java:667). */
    public static final String FC_CHANNEL_OPTIONS = "FC|OP";
    /** 1.5.2 Packet250CustomPayload limits: channel <= 20 chars, data <= 32767 bytes (Packet250CustomPayload.java:42,54). */
    private static final int FC_CHANNEL_MAX_LENGTH = 20;
    private static final int FC_PAYLOAD_MAX_BYTES = 32767;

    public static SimpleChannel CHANNEL;

    // -----------------------------------------------------------------
    // Client-side storage for penalty levels (received from server).
    // These are read by BTWHudOverlay for rendering.
    // -----------------------------------------------------------------

    /** FC gloom level (0 = none, 1 = Gloom, 2 = Dread, 3 = Terror). */
    public static int clientGloomLevel = 0;

    /** FC fat penalty level (0 = none, 1-4 = Plump/Chubby/Fat/Obese). */
    public static int clientFatPenalty = 0;

    /** FC hunger penalty level (0 = none, 1-5 = Peckish/Hungry/Famished/Starving/Dying). */
    public static int clientHungerPenalty = 0;

    /** FC health penalty level (0 = none, 1-5 = Hurt/Injured/Wounded/Crippled/Dying). */
    public static int clientHealthPenalty = 0;

    /** FC food level on the 0-60 scale (3x vanilla resolution). */
    public static int clientFoodLevel = 60;

    /** FC saturation level (0-20). */
    public static float clientSaturation = 0f;

    private static int nextMessageId = 0;

    public static void register() {
        LOGGER.info("Registering BTW network channel...");

        CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BTWForgeMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
        );

        // Penalty sync: server -> client
        CHANNEL.registerMessage(nextMessageId++, PenaltySync.class,
                PenaltySync::encode, PenaltySync::decode, PenaltySync::handle);

        // FC entity state sync: server -> client (NBT + DataWatcher snapshot)
        CHANNEL.registerMessage(nextMessageId++, FCEntityStateSync.class,
                FCEntityStateSync::encode, FCEntityStateSync::decode, FCEntityStateSync::handle);

        // StartBlockHarvest packet (FC's Packet166):
        // In legacy FC, the client sends FCPacket166StartBlockHarvest to the server
        // when beginning to mine a block. It carries the block position, hit face,
        // and the client's computed mining speed modifier (penalties applied;
        // PlayerControllerMP.java:229-234 -> NetServerHandler.java:1363).
        // In the Forge 1.20.1 port, FC mining penalties are applied per dig tick on
        // both sides by btw.forge.FCMiningSpeed from the PenaltySync levels; no packet needed.

        // Merchant recipe list sync: server -> client
        CHANNEL.registerMessage(nextMessageId++, MerchantRecipesSync.class,
                MerchantRecipesSync::encode, MerchantRecipesSync::decode, MerchantRecipesSync::handle);

        // FC mob per-tick animation (body/head yaw, arm swing): server -> client only
        // (the handler touches client classes, so a client-sent copy must never reach it).
        CHANNEL.registerMessage(nextMessageId++, FCAnimSync.class,
                FCAnimSync::encode, FCAnimSync::decode, FCAnimSync::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));

        // FC login handshake payloads (FC|VC, FC|OP): server -> client only (client-side FC handling).
        // Id 4 here; EntityTrackerBridge registers FCCustomEntityEvent at the explicit id 100.
        CHANNEL.registerMessage(nextMessageId++, FCPayload.class,
                FCPayload::encode, FCPayload::decode, FCPayload::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));

        // 1.5.2 aux effects (Packet61DoorChange): server -> client only (the handler runs client FC code).
        // Id 5.
        CHANNEL.registerMessage(nextMessageId++, FcAuxSfx.class,
                FcAuxSfx::encode, FcAuxSfx::decode, FcAuxSfx::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));

        LOGGER.info("BTW network channel registered.");
    }

    /**
     * True for the FC Packet250CustomPayload channels whose client half is bridged by
     * {@link FCPayload}: the two payloads FCBetterThanWolves.ServerPlayerConnectionInitialized
     * sends at login (Client FCBetterThanWolves.java:2213-2280).
     */
    public static boolean isForwardedFcChannel(String channel) {
        return FC_CHANNEL_VERSION_CHECK.equals(channel) || FC_CHANNEL_OPTIONS.equals(channel);
    }

    /**
     * Sends one of FC's login payloads (channel + raw bytes, as FC built them) to the player's
     * client, where FCClientPayloadHandler hands it to FC's client dispatcher the way 1.5.2
     * NetClientHandler.handleCustomPayload did (NetClientHandler.java:1578-1582). Never throws.
     */
    public static void sendFcPayload(ServerPlayer player, String channel, byte[] data) {
        if (CHANNEL == null || player == null || !isForwardedFcChannel(channel)) return;
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new FCPayload(channel, data != null ? data : new byte[0]));
        } catch (Throwable t) {
            if (FCPuppetSupport.firstFailure("fcPayload|" + channel + "|" + t.getClass().getName())) {
                LOGGER.warn("Sending FC payload '{}' to {} failed: {}",
                        channel, player.getGameProfile().getName(), t.toString());
            }
        }
    }

    /**
     * Sends a 1.5.2 aux effect to clients, which play it through the client sink
     * (btw.forge.client.LegacyClientSound.playAux): FC ids (&gt;= 2222) run FC's own
     * FCAddOnHandler.ClientPlayCustomAuxFX there, with FC's exact sounds and particles (audit sounds S3).
     * 1.5.2 WorldManager sent Packet61DoorChange the same way (vanilla/server WorldManager.java:103-111):
     * <ul>
     *   <li>{@code global} (World.func_82739_e / broadcastSound): every player, in every dimension;</li>
     *   <li>otherwise (playAuxSFX / playAuxSFXAtEntity): players within 64 blocks of (x, y, z) except
     *       {@code except}, the actor whose client already played it (null: nobody excluded).</li>
     * </ul>
     * Never throws.
     */
    public static void sendFcAuxSfx(ServerLevel level, @javax.annotation.Nullable ServerPlayer except,
                                    int sfxId, int x, int y, int z, int data, boolean global) {
        if (CHANNEL == null || level == null) return;
        try {
            FcAuxSfx msg = new FcAuxSfx(sfxId, x, y, z, data, global);
            if (global) {
                // 1.5.2 WorldManager.broadcastSound: sendPacketToAllPlayers, every dimension (vanilla/server
                // WorldManager.java:108-111), as 1.20.1 ServerLevel.globalLevelEvent also does
                CHANNEL.send(PacketDistributor.ALL.noArg(), msg);
            } else {
                PacketDistributor.TargetPoint near = new PacketDistributor.TargetPoint(
                        except, x, y, z, 64.0D, level.dimension());
                CHANNEL.send(PacketDistributor.NEAR.with(() -> near), msg);
            }
        } catch (Throwable t) {
            if (FCPuppetSupport.firstFailure("fcAuxSfx|" + sfxId + "|" + t.getClass().getName())) {
                LOGGER.warn("Sending aux-SFX {} at {},{},{} failed: {}", sfxId, x, y, z, t.toString());
            }
        }
    }

    /**
     * The 1.20.1 level event and data for a vanilla 1.5.2 aux-SFX id (&lt; 2222) as {@code {event, data}}, or
     * null when 1.20.1 has no such event or cannot express the data. {@code global} maps a
     * World.func_82739_e id to its global level event instead. Public face of the package-private
     * {@link AuxSfxMapping} for the client sink (btw.forge.client.LegacyClientSound), so the client plays the
     * same translation the server's WorldBridge sends. Pure lookup; never throws.
     */
    @javax.annotation.Nullable
    public static int[] legacyAuxToLevelEvent(int legacyId, int legacyData, boolean global) {
        try {
            if (global) {
                int event = AuxSfxMapping.toModernGlobalEvent(legacyId);
                return event != AuxSfxMapping.NONE ? new int[] {event, legacyData} : null;
            }
            int event = AuxSfxMapping.toModernEvent(legacyId, legacyData);
            if (event == AuxSfxMapping.NONE) return null;
            int data = AuxSfxMapping.toModernData(legacyId, legacyData);
            return data != AuxSfxMapping.INVALID_DATA ? new int[] {event, data} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Sends the merchant recipe list to the player after opening a merchant GUI.
     */
    public static void sendMerchantRecipes(ServerPlayer player, int containerId,
                                            btw.modern.MerchantRecipeList recipes) {
        if (recipes == null || recipes.isEmpty()) return;
        try {
            // Serialize each recipe via FC's writeToTags, then convert to MC NBT
            net.minecraft.nbt.CompoundTag mcTag = new net.minecraft.nbt.CompoundTag();
            mcTag.putInt("count", recipes.size());
            for (int i = 0; i < recipes.size(); i++) {
                btw.modern.MerchantRecipe r = (btw.modern.MerchantRecipe) recipes.get(i);
                // Write FC recipe to a ForgeNBTCompound wrapper
                ForgeNBTCompound wrapper = new ForgeNBTCompound(new net.minecraft.nbt.CompoundTag());
                r.writeToTags(wrapper);
                mcTag.put("recipe" + i, wrapper.getTag());
            }
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new MerchantRecipesSync(containerId, mcTag));
        } catch (Exception e) {
            LOGGER.warn("Failed to send merchant recipes: {}", e.getMessage(), e);
        }
    }

    /**
     * Sends the current penalty levels and food level to the given player's
     * client. Called once per tick from {@code ServerPlayerMixin.btw$tick()}.
     */
    public static void sendPenaltySync(ServerPlayer player, PlayerBridge pb) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PenaltySync(
                        pb.GetGloomLevel(),
                        pb.GetFatPenaltyLevel(),
                        pb.GetHungerPenaltyLevel(),
                        pb.GetHealthPenaltyLevel(),
                        pb.foodStats.getFoodLevel(),
                        pb.foodStats.getSaturationLevel()
                ));
    }

    // =================================================================
    // Packet: PenaltySync (server -> client)
    // =================================================================

    /**
     * Carries the five FC penalty/status values from server to client.
     * All values are small non-negative integers (0-5 for penalties,
     * 0-60 for food level), so {@code writeVarInt}/{@code readVarInt}
     * keeps the packet compact.
     */
    public static class PenaltySync {
        final int gloom;
        final int fat;
        final int hunger;
        final int health;
        final int food;
        final float saturation;

        public PenaltySync(int gloom, int fat, int hunger, int health, int food, float saturation) {
            this.gloom = gloom;
            this.fat = fat;
            this.hunger = hunger;
            this.health = health;
            this.food = food;
            this.saturation = saturation;
        }

        public static void encode(PenaltySync msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.gloom);
            buf.writeVarInt(msg.fat);
            buf.writeVarInt(msg.hunger);
            buf.writeVarInt(msg.health);
            buf.writeVarInt(msg.food);
            buf.writeFloat(msg.saturation);
        }

        public static PenaltySync decode(FriendlyByteBuf buf) {
            return new PenaltySync(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readFloat()
            );
        }

        public static void handle(PenaltySync msg,
                Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
            net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                clientGloomLevel = msg.gloom;
                clientFatPenalty = msg.fat;
                clientHungerPenalty = msg.hunger;
                clientHealthPenalty = msg.health;
                clientFoodLevel = msg.food;
                clientSaturation = msg.saturation;
            });
            ctx.setPacketHandled(true);
        }
    }

    // =================================================================
    // Packet: FCEntityStateSync (server -> client)
    // =================================================================

    /**
     * Carries an FC entity's live state (NBT fields + DataWatcher snapshot)
     * to the client so proxy-wrapped FC entities render with the correct
     * dynamic state (windmill rotation speed, blade colors, etc.).
     */
    public static class FCEntityStateSync {
        final int entityId;
        final byte[] payload;

        public FCEntityStateSync(int entityId, byte[] payload) {
            this.entityId = entityId;
            this.payload = payload;
        }

        public static void encode(FCEntityStateSync msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeByteArray(msg.payload);
        }

        public static FCEntityStateSync decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            byte[] payload = buf.readByteArray();
            return new FCEntityStateSync(id, payload);
        }

        public static void handle(FCEntityStateSync msg,
                Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
            net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> applyOnClient(msg));
            ctx.setPacketHandled(true);
        }

        private static void applyOnClient(FCEntityStateSync msg) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
            net.minecraft.world.entity.Entity e = mc.level.getEntity(msg.entityId);
            btw.modern.Entity fc = null;
            // Living puppets animate client-side (FCPuppetSupport.clientAnimTick): live apply.
            boolean live = true;
            if (e instanceof ProxyEntity pe) { fc = pe.getFcEntity(); live = false; }
            else if (e instanceof ProxyMob pm) fc = pm.getFcEntity();
            else if (e instanceof ProxyAnimal pa) fc = pa.getFcEntity();
            else if (e instanceof ProxyPathfinderMob pp) fc = pp.getFcEntity();
            else return;
            if (fc == null) return;
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.payload));
            try {
                FCEntityStateCodec.applyState(buf, fc, live);
            } catch (Throwable t) {
                if (FCPuppetSupport.firstFailure("stateSync|" + fc.getClass().getName() + "|" + t.getClass().getName())) {
                    LOGGER.warn("Applying FC state to {} failed: {}", fc.getClass().getSimpleName(), t.toString());
                }
            }
        }
    }

    /**
     * Encodes the server FC mob's animation state that the client can't derive from MC's
     * interpolated position: body yaw (FC EntityBodyHelper), head yaw and arm swing progress.
     * Sent every tick it changes ({@link FCPuppetSupport.AnimSender}); MC tracks head yaw only every
     * 3rd tick, and the 2-tick FCEntityStateSync made the client replay each pair twice (R8).
     */
    public static void broadcastFCAnim(net.minecraft.world.entity.Entity mcEntity,
                                       float body, float head, float swing) {
        if (CHANNEL == null || !(mcEntity.level() instanceof net.minecraft.server.level.ServerLevel)) return;
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> mcEntity),
                new FCAnimSync(mcEntity.getId(), body, head, swing));
    }

    // =================================================================
    // Packet: FCAnimSync (server -> client)
    // =================================================================

    public static class FCAnimSync {
        final int entityId;
        final float body;
        final float head;
        final float swing;

        public FCAnimSync(int entityId, float body, float head, float swing) {
            this.entityId = entityId;
            this.body = body;
            this.head = head;
            this.swing = swing;
        }

        public static void encode(FCAnimSync msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeFloat(msg.body);
            buf.writeFloat(msg.head);
            buf.writeFloat(msg.swing);
        }

        public static FCAnimSync decode(FriendlyByteBuf buf) {
            return new FCAnimSync(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }

        public static void handle(FCAnimSync msg,
                Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
            net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> applyOnClient(msg));
            ctx.setPacketHandled(true);
        }

        private static void applyOnClient(FCAnimSync msg) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
            net.minecraft.world.entity.Entity e = mc.level.getEntity(msg.entityId);
            FCPuppetSupport.ClientAnim anim;
            if (e instanceof ProxyMob pm) anim = pm.getClientAnim();
            else if (e instanceof ProxyAnimal pa) anim = pa.getClientAnim();
            else if (e instanceof ProxyPathfinderMob pp) anim = pp.getClientAnim();
            else return;
            anim.accept(msg.body, msg.head, msg.swing);
        }
    }

    /**
     * Encodes the current FC entity state and broadcasts to all players
     * tracking the entity. No-op if the entity has no FC entity attached.
     */
    public static void broadcastFCEntityState(net.minecraft.world.entity.Entity mcEntity,
                                              btw.modern.Entity fcEntity) {
        if (fcEntity == null || !(mcEntity.level() instanceof net.minecraft.server.level.ServerLevel)) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        FCEntityStateCodec.writeState(buf, fcEntity);
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> mcEntity),
                new FCEntityStateSync(mcEntity.getId(), payload));
    }

    // -----------------------------------------------------------------
    // Merchant recipe list sync
    // -----------------------------------------------------------------

    public static class MerchantRecipesSync {
        int containerId;
        net.minecraft.nbt.CompoundTag recipesTag;

        public MerchantRecipesSync(int containerId, net.minecraft.nbt.CompoundTag tag) {
            this.containerId = containerId;
            this.recipesTag = tag;
        }

        public static void encode(MerchantRecipesSync msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.containerId);
            buf.writeNbt(msg.recipesTag);
        }

        public static MerchantRecipesSync decode(FriendlyByteBuf buf) {
            return new MerchantRecipesSync(buf.readVarInt(), buf.readNbt());
        }

        public static void handle(MerchantRecipesSync msg,
                Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
            var ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null && mc.player.containerMenu instanceof FCContainerMenu fcm
                        && fcm.containerId == msg.containerId) {
                    fcm.setMerchantRecipes(msg.recipesTag);
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    // =================================================================
    // Packet: FCPayload (server -> client) - FC Packet250CustomPayload
    // =================================================================

    /**
     * A raw FC Packet250CustomPayload (channel + data) for the client. 1.5.2 sent these as vanilla
     * custom payloads and NetClientHandler passed every unknown channel to
     * FCAddOnHandler.ClientCustomPacketReceived; only the login channels
     * ({@link BTWNetwork#isForwardedFcChannel}) are bridged. Registered PLAY_TO_CLIENT, and the handler
     * reaches the client-only {@code btw.forge.client.FCClientPayloadHandler} through
     * DistExecutor, so a dedicated server never loads it.
     */
    public static class FCPayload {
        final String channel;
        final byte[] data;

        public FCPayload(String channel, byte[] data) {
            this.channel = channel;
            this.data = data;
        }

        public static void encode(FCPayload msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.channel, FC_CHANNEL_MAX_LENGTH);
            buf.writeByteArray(msg.data);
        }

        public static FCPayload decode(FriendlyByteBuf buf) {
            String channel = buf.readUtf(FC_CHANNEL_MAX_LENGTH);
            byte[] data = buf.readByteArray(FC_PAYLOAD_MAX_BYTES);
            return new FCPayload(channel, data);
        }

        public static void handle(FCPayload msg,
                Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
            net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
            if (isForwardedFcChannel(msg.channel)) {
                ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> btw.forge.client.FCClientPayloadHandler.handle(msg.channel, msg.data)));
            }
            ctx.setPacketHandled(true);
        }
    }

    // =================================================================
    // Packet: FcAuxSfx (server -> client) - 1.5.2 Packet61DoorChange
    // =================================================================

    /**
     * A 1.5.2 aux effect (Packet61DoorChange: id, x, y, z, data, and the broadcastSound flag;
     * vanilla/server WorldManager.java:103-111). The client handled Packet61 with WorldClient.playAuxSFX
     * or func_82739_e (NetClientHandler.handleDoorChange), i.e. RenderGlobal.playAuxSFX / broadcastSound;
     * here btw.forge.client.LegacyClientSound.playAux, reached through DistExecutor so a dedicated server
     * never loads it. Registered PLAY_TO_CLIENT. Sent by {@link #sendFcAuxSfx}.
     */
    public static class FcAuxSfx {
        final int sfxId;
        final int x;
        final int y;
        final int z;
        final int data;
        final boolean global;

        public FcAuxSfx(int sfxId, int x, int y, int z, int data, boolean global) {
            this.sfxId = sfxId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
            this.global = global;
        }

        public static void encode(FcAuxSfx msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.sfxId);
            buf.writeInt(msg.x);
            buf.writeInt(msg.y);
            buf.writeInt(msg.z);
            buf.writeInt(msg.data);
            buf.writeBoolean(msg.global);
        }

        public static FcAuxSfx decode(FriendlyByteBuf buf) {
            return new FcAuxSfx(buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readBoolean());
        }

        public static void handle(FcAuxSfx msg,
                Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
            net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> btw.forge.client.LegacyClientSound.playAux(
                            msg.sfxId, msg.x, msg.y, msg.z, msg.data, msg.global)));
            ctx.setPacketHandled(true);
        }
    }
}
