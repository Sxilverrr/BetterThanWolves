package btw.forge.compat.appleskin;

import btw.forge.BTWNetwork;
import btw.forge.LegacyProxyItem;
import btw.forge.PotionMapping;
import btw.forge.client.BTWHudOverlay;
import btw.forge.client.FCHudRendererBridge;
import btw.forge.client.FoodHudExtension;
import btw.forge.food.FcFoodValues;
import btw.modern.FoodStats;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import javax.annotation.Nullable;

/**
 * AppleSkin's HUD features on BTW's bars (INTEGRATIONS #029), drawn with FC's model instead of vanilla's.
 *
 * <ul>
 *   <li><b>Modern feature:</b> AppleSkin's held-food preview, saturation preview, exhaustion underlay and
 *       estimated-health hint.</li>
 *   <li><b>Conflict with FC:</b> AppleSkin draws them in vanilla's {@code food_level} Pre/Post, which never run
 *       under BTW's food row (#023), from vanilla's FoodData copy, and it estimates vanilla's saturation regeneration,
 *       which BTW does not have (#021: FC heals above 24/60).</li>
 *   <li><b>Pattern:</b> a {@link FoodHudExtension} on BTW's row: {@link #renderUnder} draws AppleSkin's exhaustion
 *       underlay at FC's burn threshold (1.33, or 0.5 while burning fat); {@link #renderOver} draws the fat and food
 *       previews with FC's own art through {@link BTWHudOverlay#renderer()}. The heart hint is drawn in the
 *       {@code player_health} Post, on vanilla's heart positions, for BTW's real regeneration: the food crossing
 *       24/60, plus guaranteed Regeneration.</li>
 *   <li><b>Alt rejected:</b> answering AppleSkin's HUDOverlayEvents. Three of the four never fire under BTW's row,
 *       and their values are vanilla 0-20 numbers.</li>
 * </ul>
 *
 * <p>Every display obeys AppleSkin's own switch ({@link AppleSkinClientOptions}) and flashes with AppleSkin's alpha
 * ({@link AppleSkinCompat#flashAlpha()}). The extension is called only while BTW draws its food row, so hideGui,
 * death and creative are already excluded and the row is drawn while mounted (#023). Inert once
 * {@link AppleSkinCompat} switched the integration off. Render thread only.</p>
 *
 * <p>Eat gates. The preview shows only for food whose eat would start:</p>
 * <ul>
 *   <li>Main hand ({@link #mainHandCanStart}): FC's onItemRightClick (ServerPlayerGameModeMixin.btw$useItem,
 *       ProxyItem.use; FC ItemFood checks EntityPlayer.canEat, FC drinkables only CanDrink) and vanilla Item.use
 *       (canEat, which PlayerMixin.btw$restrictEating answers from the FC meter) both run; either can start it.</li>
 *   <li>Off hand ({@link #offHandCanStart}): only the 1.20.1 item's own use() can start it; FC then feeds it through
 *       ItemStackMixin.btw$finishUsing. A BTW item never eats from the off hand.</li>
 * </ul>
 *
 * <p>The client cannot read the naturalRegeneration gamerule (it is not synced), so the heart hint assumes it is on.
 * With Legendary Survival Overhaul the 24/60 hinge holds through the LSO integration's regeneration gate; LSO's
 * health overlay draws over vanilla's heart row and so over this hint.</p>
 */
// FCMOD-INTEGRATION(1.20.1): appleskin: AppleSkin's HUD previews never run under BTW's food row and model vanilla hunger, so BTW draws them on its own row with FC values
final class AppleSkinHud implements FoodHudExtension {

    static final AppleSkinHud INSTANCE = new AppleSkinHud();

    /** Probe heart state: AppleSkin's health hint setting is off. */
    static final int HEART_OFF = -2;
    /** Probe heart state: no hint this frame. */
    static final int HEART_NONE = -1;

    private static final ResourceLocation MC_ICONS = new ResourceLocation("minecraft", "textures/gui/icons.png");

    /** AppleSkin's own underlay strip (HUDOverlayHandler.java:389-403). */
    private static final ResourceLocation APPLESKIN_ICONS = new ResourceLocation("appleskin", "textures/icons.png");

    /** AppleSkin gives up on absurd heart counts (HUDOverlayHandler.java:515); so does the hint. */
    private static final int MAX_HEARTS = 1000;

    // Heart-row top, captured in the player_health Pre and consumed by the Post of the same frame.
    private static int healthTop;
    private static boolean healthTopCaptured;

    // Probe-only state of this frame (the heart row draws before the food row).
    private int probeUnder = -1;
    private boolean probeBurningFat;
    private int probeHeartFrom = HEART_NONE;
    private int probeHeartTo;
    private int probeTopFromBottom = -1;

    private AppleSkinHud() {}

    /** The food a preview is shown for: its stack, hand and FC value. */
    record Pick(ItemStack stack, InteractionHand hand, FcFoodValues.FcFood food) {}

    /**
     * The held food a preview is shown for, in AppleSkin's order (HUDOverlayHandler.java:160-162): the main hand
     * unless its food cannot be eaten now, then the off hand when AppleSkin's off-hand setting is on. Null for none.
     */
    @Nullable
    static Pick pick(Player player) {
        ItemStack main = player.getMainHandItem();
        FcFoodValues.FcFood mainFood = FcFoodValues.of(main, player);
        if (mainFood != null && mainHandCanStart(main, mainFood, player)) {
            return new Pick(main, InteractionHand.MAIN_HAND, mainFood);
        }
        if (AppleSkinClientOptions.showFoodValuesHudOverlayWhenOffhand()) {
            ItemStack off = player.getOffhandItem();
            FcFoodValues.FcFood offFood = FcFoodValues.of(off, player);
            if (offFood != null && offHandCanStart(off, offFood, player)) {
                return new Pick(off, InteractionHand.OFF_HAND, offFood);
            }
        }
        return null;
    }

    /**
     * True when a main-hand eat of this food starts even at a full bar: an FC drinkable (CanDrink has no hunger gate,
     * EntityPlayer.java:1126-1128), an FC always-edible food, or a 1.20.1 food that can always be eaten.
     */
    static boolean alwaysStartable(ItemStack stack, FcFoodValues.FcFood food, Player player) {
        if (food.source() == FcFoodValues.Source.FC_DRINKABLE || food.alwaysEdible()) return true;
        if (!stack.isEdible()) return false;
        FoodProperties props = stack.getFoodProperties(player);
        return props != null && props.canAlwaysEat();
    }

    /** The union of both main-hand eat starts (FC onItemRightClick, vanilla Item.use); none under Hunger. */
    static boolean mainHandCanStart(ItemStack stack, FcFoodValues.FcFood food, Player player) {
        if (player.hasEffect(MobEffects.HUNGER)) return false;
        return alwaysStartable(stack, food, player) || BTWNetwork.clientFoodLevel < 60;
    }

    /**
     * An off-hand eat starts only through the 1.20.1 item's own use(): an edible item while the bar has room (or it
     * can always be eaten), or a drink that starts without a hunger test (milk; not potions). Never a BTW item, never
     * under Hunger; FC's always-edible flag does not count here.
     */
    static boolean offHandCanStart(ItemStack stack, FcFoodValues.FcFood food, Player player) {
        if (stack.getItem() instanceof LegacyProxyItem) return false;
        if (player.hasEffect(MobEffects.HUNGER)) return false;
        if (stack.isEdible()) {
            FoodProperties props = stack.getFoodProperties(player);
            if (props != null && (props.canAlwaysEat() || BTWNetwork.clientFoodLevel < 60)) return true;
        }
        return stack.getUseAnimation() == UseAnim.DRINK && !(stack.getItem() instanceof PotionItem);
    }

    /**
     * AppleSkin's exhaustion underlay behind BTW's pips: the FC exhaustion towards the next burn, as a fraction of
     * FC's threshold (1.33 per hunger unit, or 0.5 while fat burns first), in AppleSkin's strip, tinted orange while
     * burning fat.
     */
    @Override
    public void renderUnder(ForgeGui gui, GuiGraphics g, int barRightX, int barY, int screenWidth, int screenHeight) {
        if (!AppleSkinCompat.active()) return;
        try {
            boolean burningFat = FoodStats.shouldBurnFatBeforeHunger(BTWNetwork.clientFoodLevel,
                    BTWNetwork.clientSaturation);
            probeBurningFat = burningFat;
            if (!AppleSkinClientOptions.showFoodExhaustionHudUnderlay()) {
                probeUnder = -1;
                return;
            }
            float threshold = burningFat ? FoodStats.FAT_BURN_THRESHOLD : FoodStats.HUNGER_BURN_THRESHOLD;
            int width = (int) (Mth.clamp(BTWNetwork.clientExhaustion / threshold, 0F, 1F) * 81);
            probeUnder = width;
            if (width > 0) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                g.setColor(1F, burningFat ? 0.8F : 1F, burningFat ? 0.4F : 1F, 0.75F);
                g.blit(APPLESKIN_ICONS, barRightX - width, barY, 81 - width, 18, width, 9);
            }
        } catch (RuntimeException | LinkageError t) {
            AppleSkinCompat.switchOff("food HUD underlay", t);
        } finally {
            g.setColor(1F, 1F, 1F, 1F);
        }
    }

    /**
     * The held-food preview over BTW's pips: the fat an overflow adds, then the food at 1/6-shank resolution, both
     * flashing at AppleSkin's alpha. Fat first: {@link FCHudRendererBridge#drawFatPreview} redraws the current shank
     * at full alpha over its background. AppleSkin draws neither preview without showFoodValuesHudOverlay
     * (HUDOverlayHandler.java:203) and no saturation preview without showSaturationHudOverlay (:221).
     */
    @Override
    public void renderOver(ForgeGui gui, GuiGraphics g, int barRightX, int barY, int screenWidth, int screenHeight) {
        if (!AppleSkinCompat.active()) return;
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;

            Pick pick = pick(player);
            int to = -1;
            float fatTo = Float.NaN;
            boolean harm = false;
            if (pick == null) {
                AppleSkinCompat.resetFlash();
            } else {
                harm = BtwFoodTooltip.harmful(pick.food());
                if (AppleSkinClientOptions.showFoodValuesHudOverlay()) {
                    FcFoodValues.EatPreview preview = FcFoodValues.preview(BTWNetwork.clientFoodLevel,
                            BTWNetwork.clientSaturation, pick.food());
                    float alpha = AppleSkinCompat.flashAlpha();
                    FCHudRendererBridge renderer = BTWHudOverlay.renderer();
                    if (AppleSkinClientOptions.showSaturationHudOverlay()) {
                        renderer.drawFatPreview(g, barRightX, barY, preview.fatBefore(), preview.fatAfter(), alpha);
                        fatTo = preview.fatAfter();
                    }
                    renderer.drawFoodPreview(g, barRightX, barY, preview.foodBefore(), preview.foodAfter(), alpha,
                            harm);
                    to = preview.foodAfter();
                }
            }

            if (AppleSkinProbe.enabled()) {
                AppleSkinProbe.hud(pick != null ? pick.hand() : null, pick != null ? pick.stack() : ItemStack.EMPTY,
                        BTWNetwork.clientFoodLevel, BTWNetwork.clientSaturation, to, fatTo, harm, probeUnder,
                        probeBurningFat, probeHeartFrom, probeHeartTo, probeTopFromBottom,
                        AppleSkinClientOptions.maxHudOverlayFlashAlpha());
            }
        } catch (RuntimeException | LinkageError t) {
            AppleSkinCompat.switchOff("food HUD preview", t);
        } finally {
            g.setColor(1F, 1F, 1F, 1F);
            probeHeartFrom = HEART_NONE;
            probeTopFromBottom = -1;
        }
    }

    /**
     * Records the heart row's top for this frame, from the {@code player_health} Pre: {@code screenHeight -
     * ForgeGui.leftHeight}, where ForgeGui.renderHealth puts it. When another mod cancels the heart row, its Post never
     * fires and nothing is drawn.
     */
    static void captureHealthTop(int top) {
        healthTop = top;
        healthTopCaptured = true;
    }

    /**
     * AppleSkin's estimated-health hint, for BTW's regeneration: from the {@code player_health} Post, flashing half
     * hearts on vanilla's heart positions for the health eating the held food would restore. That is 1 when the food
     * lifts FC food above 24/60 (BTW's regeneration resumes; naturalRegeneration is assumed on, the client cannot read
     * it), plus every guaranteed Regeneration effect's healing ticks (MobEffect.isDurationEffectTick). AppleSkin's own
     * hide rules apply (shouldShowEstimatedHealth): hurt, not peaceful, no Poison, Wither or Regeneration; its vanilla
     * food-below-18 test becomes FC's 24/60 hinge.
     */
    void renderHealthHint(GuiGraphics g, int screenWidth, int screenHeight) {
        if (!AppleSkinCompat.active()) return;
        boolean captured = healthTopCaptured;
        healthTopCaptured = false;
        int top = healthTop;
        probeTopFromBottom = captured ? screenHeight - top : -1;
        probeHeartFrom = HEART_NONE;

        if (!AppleSkinClientOptions.showFoodHealthHudOverlay()) {
            probeHeartFrom = HEART_OFF;
            return;
        }
        if (!captured) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        // AppleSkin's own guard for its player_health pass (HUDOverlayHandler.java:87): the Post fires even when the
        // heart row is not drawn (hidden GUI, creative, spectator).
        if (player == null || mc.options.hideGui || !(mc.gui instanceof ForgeGui forgeGui)
                || !forgeGui.shouldDrawSurvivalElements()) {
            return;
        }

        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        if (health >= maxHealth) return;
        if (player.level().getDifficulty() == Difficulty.PEACEFUL) return;
        if (player.hasEffect(MobEffects.POISON) || player.hasEffect(MobEffects.WITHER)
                || player.hasEffect(MobEffects.REGENERATION)) {
            return;
        }

        Pick pick = pick(player);
        if (pick == null) return;
        FcFoodValues.EatPreview preview = FcFoodValues.preview(BTWNetwork.clientFoodLevel, BTWNetwork.clientSaturation,
                pick.food());

        int heal = preview.regenResumes() ? 1 : 0;
        for (FcFoodValues.Effect effect : pick.food().effects()) {
            if (effect.chance() >= 1F && PotionMapping.getEffect(effect.fcPotionId()) == MobEffects.REGENERATION) {
                // MobEffect.isDurationEffectTick: Regeneration heals once every 50 >> amplifier ticks (every tick at 0)
                int interval = Math.max(50 >> effect.amplifier(), 1);
                heal += effect.durationTicks() / interval;
            }
        }
        if (heal <= 0) return;

        float modified = Math.min(health + heal, maxHealth);
        int healthCeil = Mth.ceil(health);
        int modifiedCeil = Mth.ceil(modified);
        probeHeartFrom = healthCeil;
        probeHeartTo = modifiedCeil;

        // Positions as ForgeGui.renderHealth (:265-316) and Gui.renderHearts (:836-878) lay the hearts out.
        int absorb = Mth.ceil(player.getAbsorptionAmount());
        float healthMax = Math.max(maxHealth, healthCeil);
        int hearts = Mth.ceil(healthMax / 2.0F);
        int absorbHearts = Mth.ceil(absorb / 2.0F);
        int total = hearts + absorbHearts;
        if (total <= 0 || total > MAX_HEARTS) return;
        int rows = Mth.ceil((healthMax + absorb) / 2.0F / 10.0F);
        int rowHeight = Math.max(10 - (rows - 2), 3);
        int left = screenWidth / 2 - 91;

        // Vanilla's low-health jitter: ForgeGui.renderHealth reseeds Gui.random with (long) (tickCount * 312871)
        // (int multiply) and renderHearts draws one nextInt(2) per heart, last heart first, while
        // ceil(health) + absorption <= 4. Replayed on a fresh LegacyRandomSource with the same seed.
        int[] jitter = new int[total];
        if (AppleSkinClientOptions.showVanillaAnimationsOverlay() && healthCeil + absorb <= 4) {
            RandomSource random = RandomSource.create((long) (mc.gui.getGuiTicks() * 312871));
            for (int i = total - 1; i >= 0; --i) {
                jitter[i] = random.nextInt(2);
            }
        }

        // Drawn as AppleSkin's drawHealthOverlay (HUDOverlayHandler.java:332-387): a faint container, then the heart.
        float alpha = AppleSkinCompat.flashAlpha();
        int v = player.level().getLevelData().isHardcore() ? 45 : 0;
        int start = (int) (healthCeil / 2.0F);
        int end = Math.min(Mth.ceil(modified / 2.0F), total);
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            for (int i = start; i < end; ++i) {
                int x = left + (i % 10) * 8;
                int y = top - (i / 10) * rowHeight + jitter[i];
                int u = 52 + (i * 2 + 1 == modifiedCeil ? 9 : 0);
                g.setColor(1F, 1F, 1F, alpha * 0.25F);
                g.blit(MC_ICONS, x, y, 25, v, 9, 9);
                g.setColor(1F, 1F, 1F, alpha);
                g.blit(MC_ICONS, x, y, u, v, 9, 9);
            }
        } finally {
            g.setColor(1F, 1F, 1F, 1F);
            RenderSystem.disableBlend();
        }
    }
}
