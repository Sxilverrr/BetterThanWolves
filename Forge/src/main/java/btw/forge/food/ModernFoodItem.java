package btw.forge.food;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * The FC item behind a headless id (ProxyRegistry.getOrCreateLegacyId) of a modern food: an FC ItemFood, so FC code
 * that only knows items by id treats it as food (FC wolves' loose-food checks, the cauldron's food recipes, FC animal
 * fodder), with the FC value FcFoodValues gives the modern item (a btw_foods table value, or its 1.20.1 nutrition
 * x 3). Every value is read live, so table reloads and load order do not matter.
 *
 * <p>Never eaten through FC: ProxyItem.fcItemFor excludes headless ids, so the modern item keeps its own eat path
 * (FoodDataMixin feeds its FC value). FcFoodValues never resolves to a headless item either, so there is no
 * recursion.</p>
 */
// FCMOD-INTEGRATION(1.20.1): food_values: FC sees a modern food's headless id as an FC food with that food's FC value
public class ModernFoodItem extends btw.modern.ItemFood {

    private final net.minecraft.world.item.Item modern;

    /**
     * @param legacyId the headless id (&gt;= 25000); like every 1.5.2 item, the FC constructor takes it minus 256
     * @param modern   the modern item this id stands for
     */
    public ModernFoodItem(int legacyId, net.minecraft.world.item.Item modern) {
        super(legacyId - 256, 0, 0F, false);
        this.modern = modern;
    }

    /** The modern item behind this headless id. */
    public net.minecraft.world.item.Item getModernItem() {
        return modern;
    }

    @Nullable
    private FcFoodValues.FcFood food() {
        return modern != null ? FcFoodValues.of(new ItemStack(modern), null) : null;
    }

    @Nullable
    private BtwFoodTable.Fodder fodder() {
        BtwFoodTable.Entry entry = BtwFoodTable.get(modern);
        return entry != null ? entry.fodder() : null;
    }

    @Override
    public int GetHungerRestored() {
        FcFoodValues.FcFood food = food();
        return food != null ? food.hunger60() : 0;
    }

    /** 1.5.2 units (ceil of hunger60 / 3), so GetWolfHealAmount agrees with the FC value. */
    @Override
    public int getHealAmount() {
        FcFoodValues.FcFood food = food();
        return food != null ? (int) Math.ceil(food.hunger60() / 3.0D) : 0;
    }

    @Override
    public float getSaturationModifier() {
        FcFoodValues.FcFood food = food();
        return food != null ? food.fatMultiplier() : 0F;
    }

    @Override
    public boolean isWolfsFavoriteMeat() {
        FcFoodValues.FcFood food = food();
        return food != null && food.wolfMeat();
    }

    @Override
    public int GetHerbivoreFoodValue(int iItemDamage) {
        BtwFoodTable.Fodder fodder = fodder();
        return fodder != null ? fodder.herbivore() : 0;
    }

    @Override
    public int GetChickenFoodValue(int iItemDamage) {
        BtwFoodTable.Fodder fodder = fodder();
        return fodder != null ? fodder.chicken() : 0;
    }

    @Override
    public int GetPigFoodValue(int iItemDamage) {
        BtwFoodTable.Fodder fodder = fodder();
        return fodder != null ? fodder.pig() : 0;
    }
}
