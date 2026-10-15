package btw.forge.mixin.fcext;

import btw.forge.TwinRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_plant_growth (saplings)
 *
 * <p><b>Feature:</b> the saplings 1.5.2 never had: acacia, cherry and dark oak saplings, the planted mangrove
 * propagule (SaplingBlock: light 9 above, 1 in 7, two steps; the propagule without the light check) and azaleas
 * (no random growth at all, bone meal only). 1.20.1 grows them 18x faster than FC grows its saplings.</p>
 *
 * <p><b>Conflict with FC:</b> FC's sapling rule (Server FCBlockSapling.java:40-64: survival on FC's light and soils, no
 * growth in the End, light 9 above, four stages at 1/64) is the 4.B pace of every tree. FCBlockSapling.growTree,
 * reached at stage 3, grows FC's own 1.5.2 trees by the species bits of its metadata (and first removes the
 * sapling); it knows neither the modern species nor their soils (clay and mud for propagules and azaleas).</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend). The modern saplings run FC's sapling: their random tick is the
 * updateTick of a plain FCBlockSapling instance (a twin, btw.forge.TwinRegistry) that FC's world reads see at
 * their cell, so FC's survival, End ban, light and stages apply unchanged; at stage 3 the twin grows the species'
 * own 1.20.1 tree.</p>
 *
 * <p><b>Rebalance:</b> 4.B pace (mean 14.6 days at randomTickSpeed 3, the FC oak's). Dark oak keeps FC's 2x2 rule
 * (the jungle branch of growTree, Server FCBlockSapling.java:105-151): the four saplings must all be at stage 3,
 * otherwise nothing happens ("bomb out entirely"); a lone dark oak waits at stage 3, as 1.20.1 grows no single dark
 * oak. Azaleas gain stages (S1). Propagules and azaleas also keep the soils their 1.20.1 survival rule accepts
 * (S3).</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class (it is not on the bridge's compile classpath:
 * {@code @Pseudo}, {@code remap = false}; descriptors from javap of the remapped FC output), keyed on the twin's
 * id, so FC's own sapling (id 6) is untouched. {@code growTree} HEAD, cancellable: the twin's tree is
 * TwinRegistry.growSaplingTwin (SaplingBlock.advanceTree / AzaleaTreeGrower, with FC's keep-at-stage-3 on failure),
 * after FC's own 2x2 check with FC's own isSameSapling / GetSaplingGrowthStage. {@code CanGrowOnBlock} RETURN: when
 * FC's sapling soils refuse, the live plant's 1.20.1 survival rule may still accept the soil.</p>
 *
 * <p><b>Alternative considered and rejected:</b> a port subclass of FCBlockSapling (puts FC subclasses in the
 * bridge, against the documented layering, or needs a new build subproject); thinning SaplingBlock's own random tick
 * (cannot express FC's four-stage distribution, the 2x2 stage rule or FC's survival); FC's growTree with the species
 * bits (grows 1.5.2 oaks, spruces, birches and jungle trees from modern saplings).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_plant_growth: modern saplings and azaleas grow by FC's sapling rule through an FCBlockSapling twin, into their own 1.20.1 trees
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockSapling", remap = false)
public abstract class FCBlockSaplingTwinMixin {

    @Shadow(remap = false)
    public abstract int GetSaplingGrowthStage(btw.modern.World world, int i, int j, int k);

    /** FCBlockSapling.growTree (Server FCBlockSapling.java:66-208) of a twin: the live sapling's 1.20.1 tree. */
    @Inject(method = "growTree(Lbtw/modern/World;IIILjava/util/Random;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private void btw$growModernTree(btw.modern.World world, int i, int j, int k, Random random, CallbackInfo ci) {
        btw.modern.BlockSapling self = (btw.modern.BlockSapling) (Object) this;
        if (!TwinRegistry.isTwinId(self.blockID)) return;
        ci.cancel();
        if (!TwinRegistry.isTwoByTwoSaplingAt(world, i, j, k)) {
            TwinRegistry.growSaplingTwin(world, i, j, k, random, i, k, i, k);
            return;
        }
        // FC's 2x2 rule, tree type 3 (FCBlockSapling.java:105-151): the first square of four same saplings, scanned
        // x outer and z inner, 0 then -1, must be fully grown; otherwise bomb out entirely.
        int type = TwinRegistry.DARK_OAK_SPECIES;
        for (int di = 0; di >= -1; di--) {
            for (int dk = 0; dk >= -1; dk--) {
                int x = i + di;
                int z = k + dk;
                if (self.isSameSapling(world, x, j, z, type) && self.isSameSapling(world, x + 1, j, z, type)
                        && self.isSameSapling(world, x, j, z + 1, type)
                        && self.isSameSapling(world, x + 1, j, z + 1, type)) {
                    if (GetSaplingGrowthStage(world, x, j, z) == 3 && GetSaplingGrowthStage(world, x + 1, j, z) == 3
                            && GetSaplingGrowthStage(world, x, j, z + 1) == 3
                            && GetSaplingGrowthStage(world, x + 1, j, z + 1) == 3) {
                        TwinRegistry.growSaplingTwin(world, i, j, k, random, x, z, x + 1, z + 1);
                    }
                    return;
                }
            }
        }
        // No square: 1.20.1 has no single dark oak tree. The sapling stays at stage 3 and retries on each success.
    }

    /** FCBlockSapling.CanGrowOnBlock (Server FCBlockSapling.java:217-223) of a twin: FC's soils, or the live plant's. */
    @Inject(method = "CanGrowOnBlock(Lbtw/modern/World;III)Z", at = @At("RETURN"), cancellable = true, remap = false)
    private void btw$modernSoils(btw.modern.World world, int i, int j, int k, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        int id = ((btw.modern.Block) (Object) this).blockID;
        if (TwinRegistry.isTwinId(id) && TwinRegistry.liveSoilAccepts(world, i, j, k, id)) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
