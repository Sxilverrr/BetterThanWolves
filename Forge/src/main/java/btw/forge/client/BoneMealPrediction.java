package btw.forge.client;

import btw.forge.BoneMealPolicy;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client half of {@link BoneMealPolicy#onBoneMealUseOn}: predicts whether bone meal takes, for the swing. The 4.B
 * client ran FCItemDye locally after the place packet (vanilla/client PlayerControllerMP.java:443-455) and swung on
 * true. Here the same FC decision runs on {@link ClientFcWorld}, which reads the client level and drops every write
 * (setBlock / setBlockMetadata are no-ops), so FC's fertilizer code answers without changing anything. The soil's
 * new state arrives from the server.
 *
 * <p>Reached only through DistExecutor (Dist.CLIENT), so a dedicated server never loads it. Render thread
 * (BoneMealItem.useOn on the client runs from MultiPlayerGameMode.useItemOn), as ClientFcWorld requires.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BoneMealPrediction {

    private BoneMealPrediction() {}

    /** True when FC would take the fertilizer at {@code pos} on {@code route}; false off a client level. */
    public static boolean predict(Level level, BlockPos pos, BlockState state, BoneMealPolicy.Route route) {
        if (!(level instanceof ClientLevel clientLevel)) return false;
        return BoneMealPolicy.applyFertilizer(ClientFcWorld.forLevel(clientLevel), level, pos, state, route);
    }
}
