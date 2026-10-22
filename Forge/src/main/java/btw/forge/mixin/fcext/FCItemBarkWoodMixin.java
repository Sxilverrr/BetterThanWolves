package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M3, bark)
 *
 * <p><b>Feature:</b> acacia, dark oak, mangrove and cherry bark: FC's bark item (item_22547) with subtypes 5-8, which
 * log2 and the modern work stumps eject and the cauldron tans (ModernWoods: 2, 5, 2 and 8 per leather).</p>
 *
 * <p><b>Conflict with FC:</b> FCItemBark knows subtypes 0-4: its name and icon clamp the damage to 0-4
 * (Client FCItemBark.java:54-58, :78-83), so bark 5-8 read and drew as blood wood bark, and the creative list stops at
 * 3 (:86-92). {@code m_iNumSubtypes} is a constant the compiler inlined.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's bark item, with four more subtypes.</p>
 *
 * <p><b>Rebalance:</b> none; every subtype stays kindling (25) as FC's.</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; descriptors
 * from javap of Forge/build/classes/java/fc): {@code getUnlocalizedName(ItemStack)} HEAD, cancellable
 * ({@code item.fcItemBark.{acacia,darkoak,mangrove,cherry}}, named in /lang/BTW_modern_en_US.lang);
 * {@code registerIcons} TAIL registers {@code fcItemBark{Acacia,DarkOak,Mangrove,Cherry}} and
 * {@code getIconFromDamage} HEAD answers them for 5-8, so FCItemSubtypeModel takes
 * models/item/fcitembark&lt;x&gt;.json (layer0 = the species' 1.20.1 log side: FC's bark icon is its log side);
 * {@code getSubItems} TAIL adds 5-8 to the BTW creative tab and JEI.</p>
 *
 * <p><b>Alternative considered and rejected:</b> bark PNGs of their own (Mojang pixels in the jar, and they would not
 * follow resource packs, where the model's reference to the log texture does).</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: bark subtypes 5-8 (acacia, dark oak, mangrove, cherry) have their own name, icon and creative entry
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.item.FCItemBark", remap = false)
public abstract class FCItemBarkWoodMixin {

    /** The icons of bark 5-8, registered with FC's icons; null before registerIcons ran. */
    @Unique
    private btw.modern.Icon[] btw$woodIcons;

    /** getUnlocalizedName (:54-58): item.fcItemBark.&lt;wood&gt; for 5-8, where FC clamps to blood wood. */
    @Inject(method = "getUnlocalizedName(Lbtw/modern/ItemStack;)Ljava/lang/String;", at = @At("HEAD"),
            cancellable = true, require = 1, remap = false)
    private void btw$woodName(btw.modern.ItemStack stack, CallbackInfoReturnable<String> cir) {
        if (stack == null) return;
        String wood = ModernWoods.barkName(stack.getItemDamage());
        if (wood != null) {
            cir.setReturnValue(((btw.modern.Item) (Object) this).getUnlocalizedName() + "." + wood);
        }
    }

    /** registerIcons TAIL (:69-75): the icons of bark 5-8. */
    @Inject(method = "registerIcons(Lbtw/modern/IconRegister;)V", at = @At("TAIL"), require = 1, remap = false)
    private void btw$registerWoodIcons(btw.modern.IconRegister register, CallbackInfo ci) {
        btw$woodIcons = ModernWoods.registerIcons(register, ModernWoods.BARK_ICONS);
    }

    /** getIconFromDamage (:78-83): the icons of bark 5-8, where FC clamps to blood wood. */
    @Inject(method = "getIconFromDamage(I)Lbtw/modern/Icon;", at = @At("HEAD"), cancellable = true, require = 1,
            remap = false)
    private void btw$woodIcon(int damage, CallbackInfoReturnable<btw.modern.Icon> cir) {
        btw.modern.Icon[] icons = btw$woodIcons;
        if (icons != null && ModernWoods.isWoodType(damage)) {
            cir.setReturnValue(icons[damage - ModernWoods.FIRST_WOOD_TYPE]);
        }
    }

    /** getSubItems TAIL (:86-92): bark 5-8 after FC's 0-3. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "getSubItems(ILbtw/modern/CreativeTabs;Ljava/util/List;)V", at = @At("TAIL"), require = 1,
            remap = false)
    private void btw$woodSubItems(int itemId, btw.modern.CreativeTabs tab, List list, CallbackInfo ci) {
        for (int wood = ModernWoods.ACACIA; wood <= ModernWoods.CHERRY; wood++) {
            list.add(new btw.modern.ItemStack(itemId, 1, wood));
        }
    }
}
