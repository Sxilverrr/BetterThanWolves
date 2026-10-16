package btw.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * Client half of btw.forge.farming.FcSoil.fcWorld: the read-only {@link ClientFcWorld} over the client level, on the
 * render thread only (ClientFcWorld's rule). Used for the bone-meal swing prediction and the weed pull's client answer
 * on modded plants. Reached only through DistExecutor (Dist.CLIENT), so a dedicated server never loads it.
 */
// FCMOD-INTEGRATION(1.20.1): modded_plant_rules: the client predicts BTW's soil answers for modded plants on the same read-only FC world BoneMealPrediction uses
@OnlyIn(Dist.CLIENT)
public final class FcSoilClient {

    private FcSoilClient() {}

    /** ClientFcWorld for a client level on the render thread, else null. */
    public static @Nullable btw.modern.World fcWorld(BlockGetter level) {
        if (level instanceof ClientLevel clientLevel && Minecraft.getInstance().isSameThread()) {
            return ClientFcWorld.forLevel(clientLevel);
        }
        return null;
    }
}
