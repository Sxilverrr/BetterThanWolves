package btw.forge.mixin;

import btw.forge.FcBlockStateAccess;
import btw.forge.LightPolicy;
import btw.forge.TickPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FCMOD-INTEGRATION(1.20.1): tick_ownership
 *
 * <p><b>Feature:</b> 1.20.1 per-class tick overrides. 1.20.1 moves most block tick behaviour into
 * {@code randomTick} / {@code tick} overrides on the block class (SpreadingSnowyDirtBlock, SaplingBlock,
 * LeavesBlock, CropBlock, StemBlock, VineBlock, FireBlock, FallingBlock, NetherPortalBlock, ...) and gates
 * random ticks per state through the cached {@code BlockStateBase.isRandomlyTicking}. Light emission is cached per
 * state the same way ({@code BlockStateBase.lightEmission}, from BlockBehaviour.Properties).</p>
 *
 * <p><b>Conflict with FC:</b> 39 randomTick and 46 tick overrides on FC-mapped vanilla blocks never call
 * {@code BlockBehaviour.randomTick} / {@code tick}, so the BlockBehaviour-level hooks never ran for them: FC
 * growth, spread, decay, fire and falling were dead, and 1.20.1 rules and rates ran in their place. The cached
 * random-tick flag also followed 1.20.1 (DISTANCE==7 leaves, no ticks for stone bricks or torches), not 4.B's
 * {@code getTickRandomly()}. The cached emission followed 1.20.1 too, where FC's rules read 4.B's: a 1.20.1 brown
 * mushroom glows 1, so FCBlockMushroomBrown's spread-from-darkness rule never passed ({@link LightPolicy}).</p>
 *
 * <p><b>Integration pattern:</b> bridge correctness, not a modern-feature integration (closest: D). The rules
 * themselves live in {@link TickPolicy} and {@link LightPolicy}.</p>
 *
 * <p><b>Rebalance:</b> none. FC-mapped states random-tick and run their FC bodies exactly when 4.B did;
 * 1.20.1 machinery FC never scheduled keeps its scheduled ticks; the states FC random-ticks emit 4.B's light (the
 * light their FC rules read), every other state keeps 1.20.1's.</p>
 *
 * <p><b>Injection strategy:</b> the BlockState-level single dispatch points. In {@code initCache} the write of the
 * cached {@code isRandomlyTicking} is redirected, so the field is written once, with the final gate
 * ({@link TickPolicy#decide}): LevelChunkSection counts ticking states with that flag from any thread (worldgen
 * keeps placing blocks while /reload rebuilds the caches on the server thread), and an interim 1.20.1 value between
 * vanilla's write and a later overwrite could drift a section's count. The same handler caches the tick owners.
 * {@code initCache} TAIL writes the {@link LightPolicy} emission (which reads the random owner cached a moment
 * earlier) over the final {@code lightEmission} field (vanilla initCache never writes that field, so there is no
 * interim value), keeping the 1.20.1 value of the first bake to fall back to. {@code tick} HEAD (BlockBehaviour.java:757-763) is reached only from ServerLevel.java:680, so an
 * FC-owned scheduled tick runs its FC body and the 1.20.1 body is cancelled whatever its class overrides. The random
 * tick is dispatched by {@link BlockStateRandomTickMixin}, at a later mixin priority, from the owners cached here
 * ({@link FcBlockStateAccess}).</p>
 *
 * <p><b>Alternative considered and rejected:</b> per-class mixins on every overriding class (twelve
 * random-tick and six tick classes today, and a new hole for each future mapping or override), and
 * BlockBehaviour-level hooks, which key on the wrong block (StairBlock passes the stair state to
 * {@code base.randomTick / tick}, which ran FCBlockStoneBrick.updateTick at a stair position) and miss every
 * override. Overwriting {@code isRandomlyTicking} at initCache TAIL (the first version) left the interim 1.20.1 value
 * readable by worldgen threads for the length of the shape-cache build.</p>
 */
// FCMOD-INTEGRATION(1.20.1): tick_ownership: FC owns the ticks of FC-mapped vanilla-id states, enforced at the BlockState level so no 1.20.1 override bypasses it
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseTickMixin implements FcBlockStateAccess {

    @Shadow
    private boolean isRandomlyTicking;

    @Shadow
    @Mutable
    @Final
    private int lightEmission;

    @Shadow
    protected abstract BlockState asState();

    /** Legacy id whose FC block runs this state's random tick (11 for lava). */
    @Unique
    private int btw$fcId;

    /** Legacy id whose FC block runs this state's scheduled tick. */
    @Unique
    private int btw$schedId;

    /** Random-tick owner: TickPolicy.VANILLA / FC / NONE. */
    @Unique
    private byte btw$random;

    /** Scheduled-tick owner: TickPolicy.VANILLA / FC. */
    @Unique
    private byte btw$sched;

    /** The 1.20.1 emission captured at the first bake, so every later bake decides from the same input. */
    @Unique
    private int btw$vanillaEmission;

    @Unique
    private boolean btw$vanillaEmissionKnown;

    /**
     * The one write of the cached random-tick flag in initCache (BlockBehaviour.java:513, vanilla's value
     * {@code owner.isRandomlyTicking(state)} in {@code vanillaFlag}): writes TickPolicy's gate instead when it has
     * one, and caches this state's tick owners. Deterministic (registry bake, Blocks.rebuildCache), so the ticking
     * counts of existing chunk sections stay consistent. Never throws: on failure the state keeps its 1.20.1 values.
     */
    @Redirect(method = "initCache()V",
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                    target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;isRandomlyTicking:Z"))
    private void btw$writeRandomTickGate(BlockBehaviour.BlockStateBase self, boolean vanillaFlag) {
        int fcId = 0;
        int schedId = 0;
        byte random = TickPolicy.VANILLA;
        byte sched = TickPolicy.VANILLA;
        boolean flag = vanillaFlag;
        try {
            TickPolicy.Decision d = TickPolicy.decide(asState());
            if (d != null) {
                fcId = d.fcId();
                schedId = d.schedId();
                random = d.random();
                sched = d.sched();
                if (d.gate() != null) {
                    flag = d.gate();
                }
            }
        } catch (Throwable t) {
            fcId = 0;
            schedId = 0;
            random = TickPolicy.VANILLA;
            sched = TickPolicy.VANILLA;
            flag = vanillaFlag;
            TickPolicy.warnDecideFailure(asState(), t);
        }
        btw$fcId = fcId;
        btw$schedId = schedId;
        btw$random = random;
        btw$sched = sched;
        isRandomlyTicking = flag;
    }

    /** Writes this state's light emission ({@link LightPolicy}) once vanilla filled the cache. Never throws. */
    @Inject(method = "initCache()V", at = @At("TAIL"))
    private void btw$decideLightEmission(CallbackInfo ci) {
        if (!btw$vanillaEmissionKnown) {
            btw$vanillaEmission = lightEmission;
            btw$vanillaEmissionKnown = true;
        }
        int fc = -1;
        try {
            fc = LightPolicy.fcEmission(asState());
        } catch (Throwable t) {
            LightPolicy.warnDecideFailure(asState(), t);
        }
        lightEmission = fc >= 0 ? fc : btw$vanillaEmission;
    }

    /** Scheduled tick (ServerLevel.tickBlock, including every tick FC scheduled through WorldBridge). */
    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void btw$dispatchTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (btw$sched == TickPolicy.FC && TickPolicy.runScheduled(btw$schedId, level, pos, random)) {
            ci.cancel();
        }
    }

    // ---- FcBlockStateAccess (read by BlockStateRandomTickMixin, LavaFluidMixin, LightPolicy, the self-audit)

    @Override
    public byte btw$randomOwner() {
        return btw$random;
    }

    @Override
    public int btw$randomFcId() {
        return btw$fcId;
    }

    @Override
    public int btw$vanillaLightEmission() {
        return btw$vanillaEmissionKnown ? btw$vanillaEmission : -1;
    }
}
