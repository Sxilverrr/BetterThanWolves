package btw.forge.food;

/**
 * The FC item behind a headless id of a modern item that is no food but FC animal fodder in the btw_foods table
 * (beetroot seeds feed FC chickens, like wheat seeds). The fodder values are read live from the table.
 */
// FCMOD-INTEGRATION(1.20.1): food_values: FC animals eat modern fodder the btw_foods table names, by its headless id
public class ModernFodderItem extends btw.modern.Item {

    private final net.minecraft.world.item.Item modern;

    /**
     * @param legacyId the headless id (&gt;= 25000); like every 1.5.2 item, the FC constructor takes it minus 256
     * @param modern   the modern item this id stands for
     */
    public ModernFodderItem(int legacyId, net.minecraft.world.item.Item modern) {
        super(legacyId - 256);
        this.modern = modern;
    }

    /** The modern item behind this headless id. */
    public net.minecraft.world.item.Item getModernItem() {
        return modern;
    }

    private BtwFoodTable.Fodder fodder() {
        BtwFoodTable.Entry entry = BtwFoodTable.get(modern);
        return entry != null ? entry.fodder() : null;
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
