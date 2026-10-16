package btw.forge.food;

import btw.forge.BTWLifecycle;
import btw.forge.ItemStackHelper;
import btw.forge.LegacyProxyItem;
import btw.forge.PotionMapping;
import btw.forge.ProxyItem;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The FC food value of every food, from one place (INTEGRATIONS #024 "FC food values for every food").
 *
 * <p>Resolution order of {@link #of}:</p>
 * <ol>
 *   <li>{@link Source#FC_ITEM}: the stack's FC item ({@link ProxyItem#fcItemFor}) is a 1.5.2 ItemFood: FC's own
 *       {@code GetHungerRestored}, fat multiplier, always-edible and wolf-meat flags, and its onFoodEaten effects
 *       ({@code getPortEatEffects}, the enchanted golden apple's three included).</li>
 *   <li>{@link Source#FC_DRINKABLE}: the FC item is an FCItemBucketDrinkable (milk 6, chocolate milk 9, fat 0.25; the
 *       value FC feeds as is, FCItemBucketDrinkable.java:73).</li>
 *   <li>{@link Source#TABLE}: a {@code data/<ns>/btw_foods} entry with a hunger value ({@link BtwFoodTable}): hunger x 3
 *       with the entry's fat and poison.</li>
 *   <li>{@link Source#MODERN_CONVERTED}: any other edible item that is not a ProxyItem: 1.20.1 nutrition x 3 with the
 *       saturation modifier as fat, its effects mapped back to FC potion ids 1-20.</li>
 * </ol>
 *
 * <p>Guarantees other workstreams rely on: {@code of} never throws; it calls ItemStackHelper.toFcStack only for an FC
 * ItemFood with subtypes, so it never allocates a headless id; it never calls a stack-sensitive getFoodProperties on a
 * {@link LegacyProxyItem} (TABLE reads the no-arg Item.getFoodProperties, MODERN_CONVERTED skips them, so
 * ProxyItem.getFoodProperties can call it). {@link #toFoodProperties} never sets {@code fast()}.
 * {@link #foodStackLimit} is an O(1) lookup that never throws and caches nothing before FC is ready.</p>
 */
// FCMOD-INTEGRATION(1.20.1): food_values: one resolver gives every food, FC's or modern, its FC value, for eating, stack sizes, previews and other mods
public final class FcFoodValues {

    private FcFoodValues() {}

    private static final Logger LOGGER = LogManager.getLogger("BTW-Food");

    /** 1.5.2 Potion.hunger.id: FC's food poisoning (vanilla/server Potion.java; FCItemFood.SetStandardFoodPoisoningEffect). */
    static final int HUNGER_POTION_ID = 17;

    /** Where a food's FC value comes from. */
    public enum Source { FC_ITEM, FC_DRINKABLE, TABLE, MODERN_CONVERTED }

    /** An eat effect in FC terms: FC potion id (1-20), duration in ticks, amplifier, and the chance to apply it. */
    public record Effect(int fcPotionId, int durationTicks, int amplifier, float chance) {}

    /**
     * A food's FC value. {@code hunger60} is on FC's 60-unit scale (one pip = 6), {@code fatMultiplier} is FC's fat
     * (saturation) multiplier for the overflow above 60 (FoodStats.addStats).
     */
    public record FcFood(int hunger60, float fatMultiplier, boolean alwaysEdible, boolean wolfMeat,
                         List<Effect> effects, Source source) {

        public FcFood {
            effects = effects == null ? List.of() : List.copyOf(effects);
        }

        /** The chance this food applies FC food poisoning (potion 17, hunger), 0 when it never does. */
        public float poisonChance() {
            float chance = 0F;
            for (Effect e : effects) {
                if (e.fcPotionId() == HUNGER_POTION_ID) {
                    chance = Math.max(chance, e.chance());
                }
            }
            return chance;
        }
    }

    /** What eating a food does to an FC food bar: food 0-60, fat 0-20, and the FC penalty levels before and after. */
    public record EatPreview(int foodBefore, int foodAfter, float fatBefore, float fatAfter, float fatGained,
                             int hungerPenaltyBefore, int hungerPenaltyAfter,
                             int fatPenaltyBefore, int fatPenaltyAfter, boolean regenResumes) {}

    // ------------------------------------------------------------------
    // of
    // ------------------------------------------------------------------

    private static volatile boolean ofFailureLogged;

    /**
     * The FC value of eating {@code stack}, or null when it is no food to FC (empty, a potion, a stick ...).
     * {@code eater} may be null; it only reaches a modern item's stack-sensitive FoodProperties. Never throws.
     */
    @Nullable
    public static FcFood of(ItemStack stack, @Nullable LivingEntity eater) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            Item item = stack.getItem();
            if (item instanceof PotionItem) return null;

            btw.modern.Item fc = ProxyItem.fcItemFor(item);
            if (fc instanceof btw.modern.ItemFood food) {
                return fcItemFood(food, stack);
            }
            if (fc != null && isDrinkable(fc)) {
                return drinkable(fc);
            }

            BtwFoodTable.Entry entry = BtwFoodTable.get(item);
            if (entry != null && entry.hunger() != null) {
                return tableFood(item, entry);
            }

            if (!(item instanceof LegacyProxyItem) && stack.isEdible()) {
                return modernFood(stack, eater, entry);
            }
            return null;
        } catch (Throwable t) {
            if (!ofFailureLogged) {
                ofFailureLogged = true;
                LOGGER.warn("[BTW-Food] food value of {} failed; it counts as no FC food (logged once): {}",
                        stack, t.toString(), t);
            }
            return null;
        }
    }

    private static FcFood fcItemFood(btw.modern.ItemFood food, ItemStack stack) {
        // Only an FC food with subtypes reads the stack's FC damage (the golden apple's 322:1); toFcStack is safe here
        // because an FC item exists (it never allocates a headless id for a mapped item).
        int damage = 0;
        if (food.getHasSubtypes()) {
            btw.modern.ItemStack fcStack = ItemStackHelper.toFcStack(stack);
            if (fcStack != null) damage = fcStack.getItemDamage();
        }
        List<Effect> effects = new ArrayList<>();
        float[][] rows = food.getPortEatEffects(damage);
        if (rows != null) {
            for (float[] row : rows) {
                if (row != null && row.length >= 4 && (int) row[0] > 0) {
                    effects.add(new Effect((int) row[0], Math.round(row[1] * 20F), (int) row[2], row[3]));
                }
            }
        }
        return new FcFood(food.GetHungerRestored(), food.getSaturationModifier(), food.isAlwaysEdible(),
                food.isWolfsFavoriteMeat(), effects, Source.FC_ITEM);
    }

    private static FcFood tableFood(Item item, BtwFoodTable.Entry entry) {
        // No-arg Item.getFoodProperties: static, never stack-sensitive, so a ProxyItem with a table entry cannot recurse
        // into ProxyItem.getFoodProperties(stack, entity).
        FoodProperties props = item.getFoodProperties();
        boolean alwaysEdible = props != null && props.canAlwaysEat();
        List<Effect> effects = entry.poison() != null ? List.of(entry.poison()) : List.of();
        return new FcFood(entry.hunger() * 3, entry.fat(), alwaysEdible, entry.wolfMeat(), effects, Source.TABLE);
    }

    @Nullable
    private static FcFood modernFood(ItemStack stack, @Nullable LivingEntity eater, @Nullable BtwFoodTable.Entry entry) {
        FoodProperties props = stack.getFoodProperties(eater);
        if (props == null) return null;
        List<Effect> effects = new ArrayList<>();
        for (Pair<MobEffectInstance, Float> pair : props.getEffects()) {
            MobEffectInstance instance = pair.getFirst();
            if (instance == null) continue;
            int id = fcPotionId(instance.getEffect());
            if (id > 0) {
                Float chance = pair.getSecond();
                effects.add(new Effect(id, instance.getDuration(), instance.getAmplifier(), chance != null ? chance : 1F));
            }
        }
        // A table entry without hunger may still add FC poisoning or wolf meat to a modern food
        if (entry != null && entry.poison() != null) effects.add(entry.poison());
        boolean wolfMeat = props.isMeat() || (entry != null && entry.wolfMeat());
        return new FcFood(props.getNutrition() * 3, props.getSaturationModifier(), props.canAlwaysEat(), wolfMeat,
                effects, Source.MODERN_CONVERTED);
    }

    /** FC potion id (1-20) of a modern effect, through PotionMapping in reverse; 0 when FC has none. */
    private static int fcPotionId(MobEffect effect) {
        Integer id = ReverseEffects.MAP.get(effect);
        return id != null ? id : 0;
    }

    /** Lazily built reverse of PotionMapping (class-holder idiom: built once, thread-safe). */
    private static final class ReverseEffects {
        static final Map<MobEffect, Integer> MAP;

        static {
            Map<MobEffect, Integer> map = new IdentityHashMap<>();
            for (int id = 1; id <= 20; id++) {
                MobEffect effect = PotionMapping.getEffect(id);
                if (effect != null) map.putIfAbsent(effect, id);
            }
            MAP = map;
        }
    }

    // ------------------------------------------------------------------
    // FC drinkable buckets (FCItemBucketDrinkable's private fields)
    // ------------------------------------------------------------------

    private static final String DRINKABLE_CLASS = "net.minecraft.src.btw.item.FCItemBucketDrinkable";

    /** The FCItemBucketDrinkable class in a class's hierarchy, or null. */
    private static final ClassValue<Class<?>> DRINKABLE_BASE = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> type) {
            for (Class<?> k = type; k != null; k = k.getSuperclass()) {
                if (DRINKABLE_CLASS.equals(k.getName())) return k;
            }
            return null;
        }
    };

    private static volatile Field drinkHungerField;
    private static volatile Field drinkFatField;
    private static volatile boolean drinkReflectionFailed;

    private static boolean isDrinkable(btw.modern.Item fc) {
        return DRINKABLE_BASE.get(fc.getClass()) != null;
    }

    /**
     * FC milk and chocolate milk: FCItemBucketDrinkable.onEaten feeds {@code addStats(m_iHungerHealed,
     * m_fSaturationModifier)} as is (Common FCItemBucketDrinkable.java:73, "already adjusted"), so no x3. The fields are
     * private in frozen FC code and read by reflection, resolved once; null (with one WARN) if they cannot be read.
     */
    @Nullable
    private static FcFood drinkable(btw.modern.Item fc) {
        if (drinkReflectionFailed) return null;
        try {
            Field hunger = drinkHungerField;
            Field fat = drinkFatField;
            if (hunger == null || fat == null) {
                Class<?> base = DRINKABLE_BASE.get(fc.getClass());
                hunger = base.getDeclaredField("m_iHungerHealed");
                fat = base.getDeclaredField("m_fSaturationModifier");
                hunger.setAccessible(true);
                fat.setAccessible(true);
                drinkHungerField = hunger;
                drinkFatField = fat;
            }
            return new FcFood(hunger.getInt(fc), fat.getFloat(fc), false, false, List.of(), Source.FC_DRINKABLE);
        } catch (Throwable t) {
            drinkReflectionFailed = true;
            LOGGER.warn("[BTW-Food] cannot read FC drinkable bucket values ({}); milk reports no FC food value"
                    + " (FC still feeds it)", t.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // preview / toFoodProperties
    // ------------------------------------------------------------------

    /**
     * What eating {@code food} at FC food {@code fcFood60} (0-60) and fat {@code fat} does, computed by FC's own
     * FoodStats.addStats on a scratch copy and FC's penalty rules (EntityPlayer.hungerPenaltyLevelFor /
     * fatPenaltyLevelFor). {@code regenResumes}: the food crosses FC's heal threshold (above 24).
     */
    public static EatPreview preview(int fcFood60, float fat, FcFood food) {
        btw.modern.FoodStats scratch = new btw.modern.FoodStats();
        scratch.setFoodLevel(fcFood60);
        scratch.setFoodSaturationLevel(fat);
        if (food != null) {
            scratch.addStats(food.hunger60(), food.fatMultiplier());
        }
        int foodAfter = scratch.getFoodLevel();
        float fatAfter = scratch.getSaturationLevel();
        return new EatPreview(fcFood60, foodAfter, fat, fatAfter, fatAfter - fat,
                btw.modern.EntityPlayer.hungerPenaltyLevelFor(fcFood60, fat),
                btw.modern.EntityPlayer.hungerPenaltyLevelFor(foodAfter, fatAfter),
                btw.modern.EntityPlayer.fatPenaltyLevelFor(fat),
                btw.modern.EntityPlayer.fatPenaltyLevelFor(fatAfter),
                fcFood60 <= btw.modern.FoodStats.REGEN_FOOD_THRESHOLD
                        && foodAfter > btw.modern.FoodStats.REGEN_FOOD_THRESHOLD);
    }

    /**
     * 1.20.1 FoodProperties carrying an FC value, for item APIs that need one (ProxyItem.getFoodProperties): nutrition
     * ceil(hunger60 / 3), saturation modifier = FC fat multiplier, alwaysEat, meat, and the effects FC maps to modern
     * effects. Never {@code fast()}: LivingEntity.shouldTriggerItemUseEffects is unguarded for it. Null when
     * {@code food} is null or cannot be expressed.
     */
    @Nullable
    public static FoodProperties toFoodProperties(FcFood food) {
        if (food == null) return null;
        try {
            FoodProperties.Builder builder = new FoodProperties.Builder()
                    .nutrition((int) Math.ceil(food.hunger60() / 3.0D))
                    .saturationMod(food.fatMultiplier());
            if (food.alwaysEdible()) builder.alwaysEat();
            if (food.wolfMeat()) builder.meat();
            for (Effect e : food.effects()) {
                MobEffect effect = PotionMapping.getEffect(e.fcPotionId());
                if (effect != null) {
                    int ticks = e.durationTicks();
                    int amplifier = e.amplifier();
                    builder.effect(() -> new MobEffectInstance(effect, ticks, amplifier), e.chance());
                }
            }
            return builder.build();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Stack limits (ItemStackMixin.btw$foodStackLimit, a hot path on both sides)
    // ------------------------------------------------------------------

    private static final Map<Item, Integer> STACK_LIMITS = new ConcurrentHashMap<>();

    /** Bumped by {@link #invalidate}, so a limit computed against the previous table is not cached after the swap. */
    private static volatile int stackLimitGeneration;

    /**
     * The FC stack limit of a food item, or 0 for no limit: an FC food's own FC limit (bread 16, cured meat 32), 16 for
     * table and converted foods (4.B ItemFood, vanilla/server ItemFood.java:56), 0 for drinkable buckets and non-foods.
     * ItemStackMixin only ever LOWERS the 1.20.1 limit to it. O(1) after the first call per item; caches only once FC
     * is ready and is cleared by {@link #invalidate}; never throws and never allocates a headless id.
     */
    public static int foodStackLimit(Item item) {
        if (item == null || !BTWLifecycle.isFcReady()) return 0;
        Integer cached = STACK_LIMITS.get(item);
        if (cached != null) return cached;
        int generation = stackLimitGeneration;
        int limit = computeStackLimit(item);
        if (generation == stackLimitGeneration) {
            STACK_LIMITS.put(item, limit);
        }
        return limit;
    }

    private static int computeStackLimit(Item item) {
        try {
            if (item == Items.AIR || item instanceof PotionItem) return 0;
            btw.modern.Item fc = ProxyItem.fcItemFor(item);
            if (fc instanceof btw.modern.ItemFood) {
                return Math.max(0, fc.getItemStackLimit());
            }
            if (fc != null && isDrinkable(fc)) return 0;
            BtwFoodTable.Entry entry = BtwFoodTable.get(item);
            if (entry != null && entry.hunger() != null) return MODERN_FOOD_STACK_LIMIT;
            if (!(item instanceof LegacyProxyItem) && item.isEdible()) return MODERN_FOOD_STACK_LIMIT;
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 4.B ItemFood's stack size (vanilla/server ItemFood.java:56), for foods FC has no item for. */
    private static final int MODERN_FOOD_STACK_LIMIT = 16;

    /** True for an item FC caps as a food ({@link #foodStackLimit} &gt; 0). */
    public static boolean isFood(Item item) {
        return foodStackLimit(item) > 0;
    }

    /** Drops every cached stack limit; BtwFoodTable calls it whenever its table changes. */
    public static void invalidate() {
        stackLimitGeneration++;
        STACK_LIMITS.clear();
    }

    // ------------------------------------------------------------------
    // Raw-crafted foods (written by the recipe bridge, RawCraftedFoods, INTEGRATIONS #025)
    // ------------------------------------------------------------------

    private static volatile Set<Item> rawCrafted = Set.of();

    /**
     * The modern foods crafted from a poisonous raw ingredient: eating one applies FC's standard food poisoning unless
     * its table entry names its own (BtwFoodEvents). Publishes an immutable copy.
     */
    public static void setRawCrafted(Set<Item> items) {
        rawCrafted = items == null ? Set.of()
                : Set.copyOf(items.stream().filter(Objects::nonNull).toList());
    }

    public static boolean isRawCrafted(Item item) {
        return item != null && rawCrafted.contains(item);
    }

    // ------------------------------------------------------------------
    // Load summary
    // ------------------------------------------------------------------

    /**
     * Logs where every registered food's FC value comes from, and how many items the FC stack cap lowers:
     * {@code [BTW-Food] sources: FC_ITEM a, FC_DRINKABLE b, TABLE c, MODERN_CONVERTED d, capped e}.
     */
    public static void logSummary() {
        int[] counts = new int[Source.values().length];
        int capped = 0;
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            try {
                FcFood food = of(new ItemStack(item), null);
                if (food != null) counts[food.source().ordinal()]++;
                int cap = foodStackLimit(item);
                if (cap > 0 && item.getMaxStackSize() > cap) capped++;
            } catch (Throwable t) {
                // one odd item never hides the summary
            }
        }
        LOGGER.info("[BTW-Food] sources: FC_ITEM {}, FC_DRINKABLE {}, TABLE {}, MODERN_CONVERTED {}, capped {}",
                counts[Source.FC_ITEM.ordinal()], counts[Source.FC_DRINKABLE.ordinal()],
                counts[Source.TABLE.ordinal()], counts[Source.MODERN_CONVERTED.ordinal()], capped);
    }
}
