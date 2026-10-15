package btw.forge.mixin;

import btw.forge.ProxyRegistry;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): fire_flammability
 *
 * <p><b>Feature:</b> 1.20.1 fire survival and placement. FireBlock.canSurvive / updateShape
 * (FireBlock.java:92-94, 123-126), the survival check every fire placement passes in
 * BaseFireBlock.onPlace (BaseFireBlock.java:123-139), FireBlock.getStateForPlacement and lightning all ask
 * Forge's {@code getFlammability} / {@code getFireSpreadSpeed}, whose IForgeBlock defaults read
 * {@link FireBlock#getBurnOdds(BlockState)} / {@link FireBlock#getIgniteOdds(BlockState)} (IForgeBlock.java:614-617,
 * 655-658), backed by FireBlock's own table (FireBlock.bootStrap).</p>
 *
 * <p><b>Conflict with FC:</b> FC owns the fire tick (TickPolicy routes FIRE to FCBlockFire.updateTick), and FC's
 * fire reads only its own tables, BlockFire.abilityToCatchFire / chanceToEncourageFire, filled by each block's
 * SetFireProperties (vanilla/server BlockFire.java:51-72, FCMOD: the 1.5.2 table removed in favour of the
 * blocks). The 1.20.1 survival checks still run beside it on every placement and neighbour shape update. With
 * FireBlock's table, every FC-only flammable block (wicker 1082, wood sidings and mouldings, baskets, hemp,
 * gearboxes, platforms, and the huge mushroom caps 1078 / 1079 FC grows, HIGH in Server FCBlockMushroomCap.java:39)
 * reads 0, so fire that FC spreads next to them in mid air is removed by BaseFireBlock.onPlace or updateShape at
 * once. The legacy cap ids 99 / 100 are FCBlockMushroomCapLegacy, which FC makes fireproof (SetFireProperties NONE,
 * Common FCBlockMushroomCapLegacy.java:27) because in 4.B a cap only stays 99 / 100 when it was never re-added
 * (its onBlockAdded converts every placed cap to 1078 / 1079, :31-47); this mixin answers 0 / 0 for them, as 1.20.1
 * does, so the huge mushrooms 1.20.1 world generation places (never added through FC) stay fireproof, like 4.B's
 * unconverted caps.</p>
 *
 * <p><b>Integration pattern:</b> bridge correctness, not a modern-feature rebalance (closest: D). The 1.20.1
 * survival executors stay; their flammability input becomes FC's tables for every FC-mapped block.</p>
 *
 * <p><b>Rebalance:</b> none. Mapped blocks get exactly their 4.B fire properties; modern-only blocks keep the
 * 1.20.1 table (and reach FC's fire as flammable stand-ins, ModernBlockStandIn).</p>
 *
 * <p><b>Injection strategy:</b> {@link Inject} at HEAD of the single-argument public {@code getBurnOdds} /
 * {@code getIgniteOdds} (FireBlock.java:208-216), cancellable. A waterlogged state falls through (1.20.1 returns
 * 0); a ProxyBlock or a vanilla block with an FC block returns {@code abilityToCatchFire[id]} (burn) /
 * {@code chanceToEncourageFire[id]} (ignite). Unmapped blocks fall through to the 1.20.1 table. The overload
 * {@code getIgniteOdds(LevelReader, BlockPos)} is not targeted: it asks each neighbour's getFireSpreadSpeed,
 * which lands here. IForgeBlock.isFlammable, which FireBlock.canCatchFire asks, also accepts any state built with
 * {@code ignitedByLava()} (IForgeBlock.java:629-632); that property is left alone.</p>
 *
 * <p><b>Alternative considered and rejected:</b> overriding {@code getFlammability} / {@code getFireSpreadSpeed}
 * / {@code isFlammable} on ProxyBlock only. That covers FC-only blocks but not the vanilla ids FC maps (any block
 * whose 4.B values, set by its own SetFireProperties, differ from FireBlock.bootStrap), which would keep 1.20.1
 * values that FC's own fire never uses. Hooking the one table every default reads covers both with one rule.</p>
 */
// FCMOD-INTEGRATION(1.20.1): fire_flammability: FC owns fire ticks, so 1.20.1's fire survival and placement checks must read FC's fire tables
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Inject(method = "getBurnOdds(Lnet/minecraft/world/level/block/state/BlockState;)I",
            at = @At("HEAD"), cancellable = true)
    private void btw$fcBurnOdds(BlockState state, CallbackInfoReturnable<Integer> cir) {
        int id = btw$fcFireId(state);
        if (id > 0) {
            cir.setReturnValue(btw.modern.BlockFire.abilityToCatchFire[id]);
        }
    }

    @Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/block/state/BlockState;)I",
            at = @At("HEAD"), cancellable = true)
    private void btw$fcIgniteOdds(BlockState state, CallbackInfoReturnable<Integer> cir) {
        int id = btw$fcFireId(state);
        if (id > 0) {
            cir.setReturnValue(btw.modern.BlockFire.chanceToEncourageFire[id]);
        }
    }

    /**
     * Legacy id whose FC fire properties apply to {@code state}, or 0 to keep the 1.20.1 table.
     * ProxyRegistry.getBlockId gives a ProxyBlock its own legacy id and a vanilla block its mapped id only when
     * blocksList holds an FC block there (ProxyRegistry.java:225-232), so modern-only blocks and mapped ids without an
     * FC block (hay 170, carpet 171, terracotta, coal block) keep the 1.20.1 values.
     */
    @Unique
    private static int btw$fcFireId(BlockState state) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
            return 0;   // 1.20.1 answers 0 for a waterlogged state (FireBlock.java:210, 215)
        }
        int id = ProxyRegistry.getBlockId(state.getBlock());
        return id > 0 && id < btw.modern.BlockFire.abilityToCatchFire.length ? id : 0;
    }
}
