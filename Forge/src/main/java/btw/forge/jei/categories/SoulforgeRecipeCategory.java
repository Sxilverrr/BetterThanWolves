package btw.forge.jei.categories;

import btw.forge.jei.SoulforgeRecipeWrapper;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * JEI category for the soulforge's 4x4 crafting grid (FCCraftingManagerAnvil, crafted in
 * FCContainerSoulforge). Shaped patterns keep their layout; shapeless recipes fill the grid
 * in order and show JEI's shapeless marker.
 */
public class SoulforgeRecipeCategory implements IRecipeCategory<SoulforgeRecipeWrapper> {

    private static final int SLOT_SIZE = 18;
    private static final int GRID_SIZE = SoulforgeRecipeWrapper.GRID * SLOT_SIZE; // 72
    private static final int ARROW_X = GRID_SIZE + 6;
    private static final int ARROW_Y = (GRID_SIZE - 17) / 2;
    private static final int OUTPUT_X = ARROW_X + 24 + 6;
    private static final int OUTPUT_Y = (GRID_SIZE - SLOT_SIZE) / 2;
    private static final int WIDTH = OUTPUT_X + SLOT_SIZE + 2;
    private static final int HEIGHT = GRID_SIZE;

    /** Vanilla furnace progress arrow (textures/gui/container/furnace.png, 176,14 24x17). */
    private static final ResourceLocation FURNACE_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/furnace.png");

    private final RecipeType<SoulforgeRecipeWrapper> recipeType;
    private final Component title;
    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawableStatic slot;
    private final IDrawableStatic arrow;

    public SoulforgeRecipeCategory(IGuiHelper guiHelper, ResourceLocation id, String titleKey,
                                   ItemStack iconStack) {
        this.recipeType = RecipeType.create(id.getNamespace(), id.getPath(), SoulforgeRecipeWrapper.class);
        this.title = Component.translatable(titleKey);
        this.background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
        this.icon = guiHelper.createDrawableItemStack(iconStack);
        this.slot = guiHelper.getSlotDrawable();
        this.arrow = guiHelper.createDrawable(FURNACE_TEXTURE, 176, 14, 24, 17);
    }

    @Override
    public RecipeType<SoulforgeRecipeWrapper> getRecipeType() {
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
    public void setRecipe(IRecipeLayoutBuilder builder, SoulforgeRecipeWrapper recipe, IFocusGroup focuses) {
        int grid = SoulforgeRecipeWrapper.GRID;
        List<List<ItemStack>> inputs = recipe.inputs();
        for (int row = 0; row < grid; row++) {
            for (int col = 0; col < grid; col++) {
                List<ItemStack> cell = List.of();
                if (recipe.shapeless()) {
                    int index = col + row * grid;
                    if (index < inputs.size()) cell = inputs.get(index);
                } else if (col < recipe.width() && row < recipe.height()) {
                    int index = col + row * recipe.width();
                    if (index < inputs.size()) cell = inputs.get(index);
                }
                IRecipeSlotBuilder slotBuilder =
                        builder.addSlot(RecipeIngredientRole.INPUT, col * SLOT_SIZE + 1, row * SLOT_SIZE + 1)
                                .setBackground(slot, -1, -1);
                if (!cell.isEmpty()) {
                    slotBuilder.addIngredients(VanillaTypes.ITEM_STACK, cell);
                }
            }
        }
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                .setBackground(slot, -1, -1)
                .addIngredient(VanillaTypes.ITEM_STACK, recipe.output());
        if (recipe.shapeless()) {
            builder.setShapeless();
        }
    }

    @Override
    public void draw(SoulforgeRecipeWrapper recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        arrow.draw(guiGraphics, ARROW_X, ARROW_Y);
    }
}
