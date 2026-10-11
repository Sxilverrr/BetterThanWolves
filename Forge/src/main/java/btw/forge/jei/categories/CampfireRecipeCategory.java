package btw.forge.jei.categories;

import btw.forge.jei.CampfireRecipeWrapper;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI category for FC campfire spit cooking (FCCraftingManagerCampfire). One input on the
 * spit, one cooked output, plus a note with FC's timing rule (FCTileEntityCampfire.java:26-44,
 * 415-452: 400 * 8 * 3/2 = 4800 ticks over a medium fire; a blazing fire burns the food).
 */
public class CampfireRecipeCategory implements IRecipeCategory<CampfireRecipeWrapper> {

    private static final int SLOT_SIZE = 18;
    private static final int INPUT_X = 20;
    private static final int ARROW_X = INPUT_X + SLOT_SIZE + 8;
    private static final int OUTPUT_X = ARROW_X + 24 + 8;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 44;
    private static final int NOTE_Y = SLOT_SIZE + 8;

    /** Vanilla furnace progress arrow (textures/gui/container/furnace.png, 176,14 24x17). */
    private static final ResourceLocation FURNACE_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/furnace.png");

    private final RecipeType<CampfireRecipeWrapper> recipeType;
    private final Component title;
    private final Component note;
    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawableStatic slot;
    private final IDrawableStatic arrow;

    public CampfireRecipeCategory(IGuiHelper guiHelper, ResourceLocation id, String titleKey,
                                  String noteKey, ItemStack iconStack) {
        this.recipeType = RecipeType.create(id.getNamespace(), id.getPath(), CampfireRecipeWrapper.class);
        this.title = Component.translatable(titleKey);
        this.note = Component.translatable(noteKey);
        this.background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
        this.icon = guiHelper.createDrawableItemStack(iconStack);
        this.slot = guiHelper.getSlotDrawable();
        this.arrow = guiHelper.createDrawable(FURNACE_TEXTURE, 176, 14, 24, 17);
    }

    @Override
    public RecipeType<CampfireRecipeWrapper> getRecipeType() {
        return recipeType;
    }

    @Override
    public Component getTitle() {
        return title;
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, CampfireRecipeWrapper recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + 1, 1)
                .setBackground(slot, -1, -1)
                .addIngredients(VanillaTypes.ITEM_STACK, recipe.inputs());
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, 1)
                .setBackground(slot, -1, -1)
                .addIngredient(VanillaTypes.ITEM_STACK, recipe.output());
    }

    @Override
    public void draw(CampfireRecipeWrapper recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        arrow.draw(guiGraphics, ARROW_X, 1);
        var font = Minecraft.getInstance().font;
        int x = Math.max(0, (WIDTH - font.width(note)) / 2);
        guiGraphics.drawString(font, note, x, NOTE_Y, 0xFF808080, false);
    }
}
