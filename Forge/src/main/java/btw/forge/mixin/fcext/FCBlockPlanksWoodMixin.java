package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M7, planks)
 *
 * <p><b>Feature:</b> FC planks of wood types 5-8 (item level: the 1.20.1 acacia, dark oak, mangrove and cherry planks
 * items read as FC planks 5:5-8), with FC fuel in FC's furnaces, kilns and campfires, and the fuel of log2 (x4).</p>
 *
 * <p><b>Conflict with FC:</b> FCBlockPlanks.GetFurnaceBurnTimeByWoodType answers blood wood's 200 for every wood type
 * above 3 (Client FCBlockPlanks.java:97-119), and its client {@code getIcon} indexes {@code m_iconArray[meta]}, a
 * five-entry array, so any meta above 4 threw (:126-129).</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's planks, with the four modern wood types' fuel by lore (the
 * nearest FC tier, ModernWoods.planksBurnTime).</p>
 *
 * <p><b>Rebalance:</b> acacia 400 and dark oak 400 (the oak tier), mangrove 200 (the jungle tier), cherry 500 (the
 * birch tier). At HEAD these planks and logs were headless items: no fuel in FC machines.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; javap of
 * Forge/build/classes/java/fc): the static {@code GetFurnaceBurnTimeByWoodType(I)I} HEAD, cancellable, for 5-8 (FC's
 * GetFurnaceBurnTime and FCBlockLog's both call it); {@code getIcon(II)} HEAD, cancellable: 5-8 the 1.20.1 planks by
 * name, any other meta past FC's array its first icon (closes the existing overflow).</p>
 *
 * <p><b>Alternative considered and rejected:</b> widening {@code m_iconArray} (a registerIcons rewrite for icons
 * that only name 1.20.1 textures).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: FC planks of wood types 5-8 burn at their tier's fuel and draw the 1.20.1 planks
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockPlanks", remap = false)
public abstract class FCBlockPlanksWoodMixin {

    /** FCBlockPlanks.m_iconArray (:123), one icon per FC wood type 0-4; null before registerIcons ran. */
    @Shadow(remap = false)
    private btw.modern.Icon[] m_iconArray;

    /** GetFurnaceBurnTimeByWoodType (:97-119): the fuel of wood types 5-8. */
    @Inject(method = "GetFurnaceBurnTimeByWoodType(I)I", at = @At("HEAD"), cancellable = true, require = 1,
            remap = false)
    private static void btw$woodTypeFuel(int woodType, CallbackInfoReturnable<Integer> cir) {
        if (ModernWoods.isWoodType(woodType)) {
            cir.setReturnValue(ModernWoods.planksBurnTime(woodType));
        }
    }

    /** getIcon (:126-129): wood types 5-8 by their 1.20.1 planks; past FC's array, FC's first icon. */
    @Inject(method = "getIcon(II)Lbtw/modern/Icon;", at = @At("HEAD"), cancellable = true, require = 1,
            remap = false)
    private void btw$woodTypeIcon(int side, int metadata, CallbackInfoReturnable<btw.modern.Icon> cir) {
        if (ModernWoods.isWoodType(metadata)) {
            cir.setReturnValue(ModernWoods.planksIcon(metadata));
            return;
        }
        btw.modern.Icon[] icons = m_iconArray;
        if (icons != null && icons.length > 0 && (metadata < 0 || metadata >= icons.length)) {
            cir.setReturnValue(icons[0]);
        }
    }
}
