package btw.forge.mixin;

import btw.forge.removed.RemovedContent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * FCMOD-INTEGRATION(1.20.1): stays-removed (hard-coded structure pieces)
 *
 * <p><b>Feature:</b> 1.20.1 structures built in code rather than from templates (jungle temple, stronghold,
 * mineshaft, desert pyramid, swamp hut, ocean monument, nether fortress, and other mods' pieces). The jungle temple
 * places four tripwire hooks and five tripwire blocks (JungleTemplePiece.java:162-165, 181-185).</p>
 *
 * <p><b>Conflict with FC:</b> FC rebuilt the jungle and desert pyramids without their traps ("knock a hole in the
 * wall where the levers used to be", ComponentScatteredFeatureJunglePyramid.java:246-529) and hid the tripwire hook
 * (FCBlockTripWireSource, null tab); string no longer places tripwire in 4.B. Maintainer ruling: "blocks that 4.B
 * explicitly removed must stay removed."</p>
 *
 * <p><b>Integration pattern:</b> C (rebalance by substitution), per the ruling; same table and substitutes as
 * {@link StructureTemplateRemovedMixin} ({@link RemovedContent#structureSubstitute}).</p>
 *
 * <p><b>Rebalance:</b> a T1 block a piece places becomes the 4.B block in its role (tripwire and hooks become air,
 * so the jungle-temple trap is disarmed as in 4.B; the arrow dispensers stay). T2 blocks (stronghold ladders,
 * mineshaft torches and webs, the swamp-hut crafting table, piece chests) are left as legacy blocks: FC's legacy drop
 * rules convert them when broken, which is 4.B behaviour.</p>
 *
 * <p><b>Injection strategy:</b> {@link ModifyVariable} at HEAD of
 * {@code StructurePiece.placeBlock(WorldGenLevel, BlockState, int, int, int, BoundingBox)} (StructurePiece.java:164,
 * SRG {@code m_73434_}) on its only BlockState argument, before the piece mirrors and rotates it. Every hard-coded
 * placement goes through it: {@code generateBox} (:219) and {@code createDispenser} (:390) call it; only
 * {@code createChest} (:372-387) writes {@code Blocks.CHEST} directly, a T2 id that is deliberately not substituted.</p>
 *
 * <p><b>Alternative considered and rejected:</b> {@code WorldGenRegion.setBlock} (WorldGenRegion.java:257) as the
 * only hook: it would also rewrite non-structure features and still misses {@code /place structure} pieces that
 * write outside a region; per-structure mixins (JungleTemplePiece, ...) would miss other mods' pieces. Forge 47.3.0
 * has no structure-placement event, so Protocol #4 allows the mixin.</p>
 */
@Mixin(StructurePiece.class)
public abstract class StructurePieceRemovedMixin {

    // FCMOD-INTEGRATION(1.20.1): stays-removed: piece-placed T1 blocks become the 4.B block in their role
    @ModifyVariable(method = "placeBlock", at = @At("HEAD"), argsOnly = true)
    private BlockState btw$substituteRemovedBlock(BlockState state) {
        BlockState substitute = RemovedContent.structureSubstitute(state);
        return substitute != null ? substitute : state;
    }
}
