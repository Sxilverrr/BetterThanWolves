package btw.forge;

/**
 * The per-BlockState FC decisions cached at {@code BlockStateBase.initCache} by
 * {@code btw.forge.mixin.BlockStateBaseTickMixin}, which implements this interface on every BlockState (a mixin
 * duck interface: cast through Object, {@code (FcBlockStateAccess) (Object) state}). Read by the random-tick
 * dispatch ({@code BlockStateRandomTickMixin}), {@code LavaFluidMixin}, {@link TickPolicy}, {@link LightPolicy} and
 * the self-audit, so every reader sees the one decision the state was baked with.
 */
public interface FcBlockStateAccess {

    /** Random-tick owner: {@link TickPolicy#VANILLA}, {@link TickPolicy#FC} or {@link TickPolicy#NONE}. */
    byte btw$randomOwner();

    /** Legacy id whose FC block runs this state's random tick (11 for every lava state). */
    int btw$randomFcId();

    /** The state's 1.20.1 light emission (BlockBehaviour.Properties), before {@link LightPolicy}; -1 before the first bake. */
    int btw$vanillaLightEmission();
}
