package btw.forge.compat.appleskin;

import btw.forge.BTWNetwork;
import btw.forge.PotionMapping;
import btw.forge.client.FCHudRendererBridge;
import btw.forge.food.FcFoodValues;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The food tooltip BTW shows in place of AppleSkin's (INTEGRATIONS #029, values from #024): the food's FC value on
 * FC's 0-60 scale, and what eating it now would do to BTW's food bar (#021).
 *
 * <p>{@code food} is the stack's {@link FcFoodValues#of} value; {@code preview} is {@link FcFoodValues#preview} at the
 * client's synced food and fat (null without a player); {@code refused} is true when FC would not start the eat at
 * a full bar ({@link AppleSkinHud#alwaysStartable} is false at 60/60). The record's {@code toString} is the probe's
 * evidence. Drawn by {@link BtwFoodTooltipRenderer}, registered by {@link AppleSkinCompat}.</p>
 *
 * <p>Row 1 names the harmful effects: FC food poisoning by chance ("30% food poisoning"), any other harmful effect by
 * its name, with its chance when below 1. Row 2 is the eat preview: the fat an overflow adds, a new fat penalty level,
 * a hunger penalty the food clears, BTW's regeneration resuming (food above 24/60), or "(full)" when FC refuses.</p>
 */
// FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin's tooltip shows vanilla shanks and saturation; BTW feeds FC hunger on a 60 scale with overflow-only fat, penalties and food poisoning
public record BtwFoodTooltip(FcFoodValues.FcFood food, @Nullable FcFoodValues.EatPreview preview, boolean refused)
        implements TooltipComponent {

    /** 1.5.2 Potion.hunger.id: FC's food poisoning (FCItemFood.SetStandardFoodPoisoningEffect). */
    private static final int FC_FOOD_POISONING = 17;

    /** Fat gains below this are float noise, not a gain worth naming. */
    static final float MIN_FAT_SHOWN = 0.005F;

    /** "#.##" in the default locale, as AppleSkin formats its own values. Render thread only. */
    private static final DecimalFormat FAT_FORMAT = new DecimalFormat("#.##");

    /** The BTW tooltip for {@code stack}, or null when it is no food to FC. {@code player} may be null. */
    @Nullable
    static BtwFoodTooltip of(ItemStack stack, @Nullable Player player) {
        FcFoodValues.FcFood food = FcFoodValues.of(stack, player);
        if (food == null) return null;
        FcFoodValues.EatPreview preview = player == null ? null
                : FcFoodValues.preview(BTWNetwork.clientFoodLevel, BTWNetwork.clientSaturation, food);
        boolean refused = player != null && BTWNetwork.clientFoodLevel >= 60
                && !AppleSkinHud.alwaysStartable(stack, food, player);
        return new BtwFoodTooltip(food, preview, refused);
    }

    /**
     * True when the food has a harmful effect it can apply (chance above 0, a mapped effect of category HARMFUL):
     * FC food poisoning, and Poison on spider eyes and poisonous potatoes. AppleSkin draws such food with rotten
     * shanks; so does BTW.
     */
    static boolean harmful(FcFoodValues.FcFood food) {
        for (FcFoodValues.Effect effect : food.effects()) {
            if (harmfulEffect(effect) != null) return true;
        }
        return false;
    }

    @Nullable
    private static MobEffect harmfulEffect(FcFoodValues.Effect effect) {
        if (effect.chance() <= 0F) return null;
        MobEffect mapped = PotionMapping.getEffect(effect.fcPotionId());
        return mapped != null && mapped.getCategory() == MobEffectCategory.HARMFUL ? mapped : null;
    }

    /** Row 1: the harmful effects, joined with ", "; empty when there is none. */
    String row1Text() {
        List<String> parts = new ArrayList<>();
        for (FcFoodValues.Effect effect : food.effects()) {
            MobEffect mapped = harmfulEffect(effect);
            if (mapped == null) continue;
            int percent = Math.round(effect.chance() * 100F);
            if (effect.fcPotionId() == FC_FOOD_POISONING && effect.chance() < 1F) {
                parts.add(I18n.get("btw.compat.food.poison_chance", percent));
            } else if (effect.chance() >= 1F) {
                parts.add(I18n.get(mapped.getDescriptionId()));
            } else {
                parts.add(percent + "% " + I18n.get(mapped.getDescriptionId()));
            }
        }
        return String.join(", ", parts);
    }

    /**
     * Row 2: what eating does now, joined with ", "; "(full)" when FC refuses; empty without a preview or when eating
     * changes nothing worth naming. Penalty names come from FC's own tables (level 0 is never looked up: it reads
     * "Invalid").
     */
    String row2Text() {
        if (preview == null) return "";
        if (refused) return I18n.get("btw.compat.food.full");

        List<String> parts = new ArrayList<>();
        if (preview.fatGained() > MIN_FAT_SHOWN) {
            parts.add(I18n.get("btw.compat.food.fat_gain", FAT_FORMAT.format(preview.fatGained())));
        }
        if (preview.fatPenaltyAfter() > preview.fatPenaltyBefore()) {
            parts.add("-> " + FCHudRendererBridge.fatPenaltyName(preview.fatPenaltyAfter()));
        }
        if (preview.hungerPenaltyBefore() > 0 && preview.hungerPenaltyAfter() < preview.hungerPenaltyBefore()) {
            parts.add(I18n.get("btw.compat.food.clears",
                    FCHudRendererBridge.hungerPenaltyName(preview.hungerPenaltyBefore())));
        }
        if (preview.regenResumes()) {
            parts.add(I18n.get("btw.compat.food.regen_resumes"));
        }
        return String.join(", ", parts);
    }
}
