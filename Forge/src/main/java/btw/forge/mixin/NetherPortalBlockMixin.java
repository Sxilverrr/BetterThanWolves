package btw.forge.mixin;

import btw.forge.ProxyRegistry;
import btw.forge.WorldBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Routes the nether portal's random tick to FC's portal block (legacy id 90, FCBlockPortal).
 *
 * <p>{@link BlockBehaviorMixin} routes random ticks of FC-mapped vanilla blocks at the HEAD of
 * {@code BlockBehaviour.randomTick}, but 1.20.1 {@code NetherPortalBlock} overrides
 * {@code randomTick} (zombified piglin spawning) without calling super, so that hook never ran
 * for portals and FCBlockPortal.updateTick was dead code (triage ghast GH5). In 1.5.2 the portal
 * ticks randomly and Block.RandomUpdateTick calls updateTick (vanilla/server Block.java:1496-1499);
 * FCBlockPortal.updateTick (Common FCBlockPortal.java:42-54) then possesses one creature within
 * 16 blocks of a surface-world portal at 1/10 per tick and sets the nether-accessed progress flag
 * (FCUtilsWorld.GameProgressSetNetherBeenAccessedServerOnly).</p>
 *
 * <p>The vanilla body is always cancelled when the FC portal exists: FCBlockPortal.updateTick
 * overrides BlockPortal.updateTick without calling super ("override to prevent pigmen spawning
 * around portals in the overworld"), so BTW 4.B never ran the 1.5.2 pigman spawn
 * (vanilla/server BlockPortal.java:29-52) that 1.20.1's zombified piglin spawn replaces.</p>
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    @Unique
    private static final org.apache.logging.log4j.Logger BTW$LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("BTW-Portal");

    @Unique
    private static volatile long btw$lastFailureMs = 0L;

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void btw$portalRandomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                      CallbackInfo ci) {
        btw.modern.Block fcBlock = ProxyRegistry.getFcBlock((Block) (Object) this);
        if (fcBlock == null) return;   // no FC portal registered: keep vanilla behaviour

        try {
            fcBlock.RandomUpdateTick(WorldBridge.getOrCreate(level), pos.getX(), pos.getY(), pos.getZ(),
                    new Random(random.nextLong()));
        } catch (Throwable t) {
            // Random-tick path: an FC error must not crash the server tick.
            long now = System.currentTimeMillis();
            if (now - btw$lastFailureMs > 60_000L) {
                btw$lastFailureMs = now;
                BTW$LOGGER.warn("FC portal RandomUpdateTick failed at {} (further failures within 60s not logged): {}",
                        pos, t.toString(), t);
            }
        }
        ci.cancel();
    }
}
