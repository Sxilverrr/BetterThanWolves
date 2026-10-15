package btw.forge.mixin;

import btw.forge.removed.RemovedContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * FCMOD-INTEGRATION(1.20.1): stays-removed (structure templates)
 *
 * <p><b>Feature:</b> 1.20.1 NBT structure templates: jigsaw villages, ancient cities, woodland mansions, igloos,
 * trail ruins, other mods' templates, and {@code /place template}. They place T1 ids that BTW 4.B removed: the
 * ancient-city centre's comparators ({@code ancient_city/city_center/city_center_1..3}) and the mansion's trapped
 * chest ({@code woodland_mansion/1x2_s2}), plus any hopper, dropper, daylight detector, activator rail, weighted
 * plate or tripwire a modded template carries.</p>
 *
 * <p><b>Conflict with FC:</b> 4.B stubbed or made inert every one of these ids (FCBlockStub 146/147/148/157/158,
 * FCBlockVanillaHopper, FCBlockDaylightDetector, FCItemStub comparator) and its own worldgen substituted or stripped
 * such blocks (every structure chest became the BTW chest, StructureComponent.java:789-799; the pyramid traps were
 * removed, ComponentScatteredFeatureJunglePyramid.java:246-529). Maintainer ruling: "blocks that 4.B explicitly
 * removed must stay removed."</p>
 *
 * <p><b>Integration pattern:</b> C (rebalance by substitution), per the ruling; the substitute is the 4.B block in
 * that role (table {@link RemovedContent}).</p>
 *
 * <p><b>Rebalance:</b> a T1 block in a template becomes: trapped chest -> chest (FACING/TYPE/WATERLOGGED kept, chest
 * data kept); comparator -> repeater (same FACING, delay 1); tripwire and tripwire hook -> air; activator rail ->
 * rail (shape kept); hopper -> air; dropper -> dispenser (FACING kept, inventory data kept); daylight detector ->
 * air; weighted plates -> stone pressure plate. Block-entity data that no longer fits is dropped. T2 and T3 blocks
 * are not touched: generated T2 blocks stay legacy blocks and FC's legacy drop rules convert them when broken, which
 * is 4.B behaviour.</p>
 *
 * <p><b>Injection strategy:</b> {@link Inject} at RETURN of the Forge overload
 * {@code processBlockInfos(ServerLevelAccessor, BlockPos, BlockPos, StructurePlaceSettings, List, StructureTemplate)}
 * (StructureTemplate.java:374), the single point every template placement passes: {@code placeInWorld}
 * (StructureTemplate.java:225) calls it and the deprecated 5-argument overload (:370, SRG {@code m_74517_}) delegates
 * to it. The returned infos are after every processor and before mirror/rotation, so the substitute is rotated like
 * the original. The list is replaced only when a removed block is present. {@code remap = false}: the overload is
 * Forge-added and keeps its name in the SRG runtime (no mapping exists; precedent FoodDataMixin).</p>
 *
 * <p><b>Alternative considered and rejected:</b> a StructureProcessor appended to every template's settings: there is
 * no Forge hook that reaches every caller's {@code StructurePlaceSettings} (jigsaw pieces, mod structures,
 * {@code /place}); {@code WorldGenRegion.setBlock} as one choke point misses {@code /place} (which writes through
 * ServerLevel) and would also rewrite non-structure features. Forge 47.3.0 has no structure-placement event
 * (only {@code RegisterStructureConversionsEvent}), so Protocol #4 allows the mixin.</p>
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateRemovedMixin {

    // FCMOD-INTEGRATION(1.20.1): stays-removed: template-placed T1 blocks become the 4.B block in their role
    @Inject(method = "processBlockInfos(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;"
            + "Ljava/util/List;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private static void btw$substituteRemovedBlocks(ServerLevelAccessor level, BlockPos offset, BlockPos pos,
                                                    StructurePlaceSettings settings,
                                                    List<StructureTemplate.StructureBlockInfo> blockInfos,
                                                    @Nullable StructureTemplate template,
                                                    CallbackInfoReturnable<List<StructureTemplate.StructureBlockInfo>> cir) {
        List<StructureTemplate.StructureBlockInfo> infos = cir.getReturnValue();
        if (infos == null || infos.isEmpty()) return;
        List<StructureTemplate.StructureBlockInfo> out = null;
        for (int i = 0; i < infos.size(); i++) {
            StructureTemplate.StructureBlockInfo info = infos.get(i);
            BlockState substitute = RemovedContent.structureSubstitute(info.state());
            if (substitute == null) continue;
            if (out == null) out = new ArrayList<>(infos);
            out.set(i, new StructureTemplate.StructureBlockInfo(info.pos(), substitute,
                    RemovedContent.structureKeepsBlockEntityData(info.state()) ? info.nbt() : null));
        }
        if (out != null) cir.setReturnValue(out);
    }
}
