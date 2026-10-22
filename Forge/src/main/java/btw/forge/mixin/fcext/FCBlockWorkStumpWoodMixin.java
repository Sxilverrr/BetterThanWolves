package btw.forge.mixin.fcext;

import btw.forge.ModernWoods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FCMOD-INTEGRATION(1.20.1): modern_woods (M2, the work stump)
 *
 * <p><b>Feature:</b> the work stumps of the four modern woods: FC's work stump (block_1030) with META 5-8, made by
 * log2's ConvertBlock (FCBlockLogWoodMixin), so every overworld biome has its own work stump (the crafting table
 * stays retired, RemovedContent.java:205-209).</p>
 *
 * <p><b>Conflict with FC:</b> FCBlockWorkStump reads its wood type as {@code meta & 3}: the bark of a chewed stump
 * (Client FCBlockWorkStump.java:93, read from the metadata it held before ConvertBlock replaced it, :86-92) and the
 * side texture ({@code m_sideTextureNames[meta & 3]}, :119-141), so META 5-8 gave spruce, birch, jungle and oak; and
 * the block dispenser takes it back through {@code Block.wood} (:112-115), a log 17 of species {@code meta & 3}.</p>
 *
 * <p><b>Integration pattern:</b> D (FC-extend): FC's work stump stays whole; META 5-8 are wood types 5-8.</p>
 *
 * <p><b>Rebalance:</b> none. The oak plank every broken work stump drops (:67-75) is 4.B's and is kept (woods design
 * question 11.4, default). META 0-3 keep FC's own path, including the block dispenser's (the port's Block
 * GetStackRetrievedByBlockDispenser is still a P2 stub, STUB_CATALOGUE.md:895).</p>
 *
 * <p><b>Injection strategy:</b> load-time mixin on the FC class ({@code @Pseudo}, {@code remap = false}; descriptors
 * from javap of Forge/build/classes/java/fc). ConvertBlock HEAD keeps the stump's metadata in a thread-local, since
 * the only {@code ItemStack.<init>(Item, int, int)} (offset 63) comes after the stump was replaced and takes
 * {@code old & 3}; {@code @ModifyArg} index 2 of that call answers the kept wood type 5-8.
 * {@code GetStackRetrievedByBlockDispenser} HEAD, cancellable: META 5-8 give log2 of that species.
 * {@code registerIcons} TAIL registers {@code fcBlockWorkStump{Acacia,DarkOak,Mangrove,Cherry}}
 * (textures/block/fcblockworkstump&lt;x&gt;.png, deriveModernWoodTextures); {@code getIcon(II)} HEAD, cancellable,
 * answers them for the sides (side &gt; 1) of META 5-8.</p>
 *
 * <p><b>Alternative considered and rejected:</b> {@code @ModifyVariable} on the stored old metadata (local 7): it would
 * change what FC itself reads, where only the bark's subtype is FC's {@code & 3} truncation.</p>
 */
// FCMOD-INTEGRATION(1.20.1): modern_woods: work stumps of wood types 5-8 pay their own bark, return log2 to the block dispenser and draw their own sides
@Pseudo
@Mixin(targets = "net.minecraft.src.btw.block.FCBlockWorkStump", remap = false)
public abstract class FCBlockWorkStumpWoodMixin {

    /** The metadata the stump held when ConvertBlock started (ConvertBlock replaces the block before the bark). */
    @Unique
    private static final ThreadLocal<Integer> btw$oldMetadata = new ThreadLocal<>();

    /** The side icons of META 5-8, registered with FC's icons; null before registerIcons ran. */
    @Unique
    private btw.modern.Icon[] btw$woodSideIcons;

    /** ConvertBlock HEAD (Client FCBlockWorkStump.java:84): keep the stump's metadata for its bark. */
    @Inject(method = "ConvertBlock(Lbtw/modern/ItemStack;Lbtw/modern/World;IIII)Z", at = @At("HEAD"),
            require = 1, remap = false)
    private void btw$keepWoodType(btw.modern.ItemStack stack, btw.modern.World world, int i, int j, int k,
                                  int fromSide, CallbackInfoReturnable<Boolean> cir) {
        btw$oldMetadata.set(world.getBlockMetadata(i, j, k));
    }

    /** ConvertBlock's bark (:93): wood type 5-8 of a modern stump, FC's {@code old & 3} otherwise. */
    @ModifyArg(method = "ConvertBlock(Lbtw/modern/ItemStack;Lbtw/modern/World;IIII)Z",
            at = @At(value = "INVOKE", target = "Lbtw/modern/ItemStack;<init>(Lbtw/modern/Item;II)V", remap = false),
            index = 2, require = 1, remap = false)
    private int btw$chewedStumpBark(int damage) {
        Integer old = btw$oldMetadata.get();
        btw$oldMetadata.remove();
        return old != null && ModernWoods.isWoodType(old) ? old : damage;
    }

    /** GetStackRetrievedByBlockDispenser (:112-115): a modern work stump comes back as its log2 log. */
    @Inject(method = "GetStackRetrievedByBlockDispenser(Lbtw/modern/World;III)Lbtw/modern/ItemStack;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void btw$log2Retrieved(btw.modern.World world, int i, int j, int k,
                                   CallbackInfoReturnable<btw.modern.ItemStack> cir) {
        int metadata = world.getBlockMetadata(i, j, k);
        if (ModernWoods.isBuilt() && ModernWoods.isWoodType(metadata)) {
            cir.setReturnValue(new btw.modern.ItemStack(ModernWoods.LOG2_ID, 1,
                    metadata - ModernWoods.FIRST_WOOD_TYPE));
        }
    }

    /** registerIcons TAIL (:145-156): the side icons of wood types 5-8. */
    @Inject(method = "registerIcons(Lbtw/modern/IconRegister;)V", at = @At("TAIL"), require = 1, remap = false)
    private void btw$registerWoodSides(btw.modern.IconRegister register, CallbackInfo ci) {
        btw$woodSideIcons = ModernWoods.registerIcons(register, ModernWoods.WORK_STUMP_ICONS);
    }

    /** getIcon (:130-141): the sides of META 5-8; the top (workbench) and bottom (trunk top) stay FC's. */
    @Inject(method = "getIcon(II)Lbtw/modern/Icon;", at = @At("HEAD"), cancellable = true, require = 1,
            remap = false)
    private void btw$woodSideIcon(int side, int metadata, CallbackInfoReturnable<btw.modern.Icon> cir) {
        btw.modern.Icon[] icons = btw$woodSideIcons;
        if (side > 1 && icons != null && ModernWoods.isWoodType(metadata)) {
            cir.setReturnValue(icons[metadata - ModernWoods.FIRST_WOOD_TYPE]);
        }
    }
}
