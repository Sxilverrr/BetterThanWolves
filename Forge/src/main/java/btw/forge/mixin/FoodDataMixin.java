package btw.forge.mixin;

import btw.forge.PlayerBridge;
import btw.forge.food.FcFoodValues;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disables vanilla food system and redirects to FC's {@link btw.modern.FoodStats}.
 *
 * FC completely replaces the hunger system:
 * - Food level range: 0-60 (3x vanilla's 0-20)
 * - Different exhaustion rates (1.33F/pip vs 4.0F)
 * - Fat-before-hunger burning logic
 * - Healing every 600 ticks (not 80) when food > 24
 * - Starvation only when food ≤ 0 AND saturation ≤ 0.01
 * - Every server player's FoodData.addExhaustion (other mods' hunger costs) drains FC food under FC's rules
 * - Foods with a btw_foods table entry feed their FC value (btw.forge.food.BtwFoodTable)
 *
 * <p>The owning server player is cached ({@link #btw$cachedOwner}): FoodData keeps no owner reference, and fake
 * players are not in the player list.</p>
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {

    @Shadow private int foodLevel;
    @Shadow private float saturationLevel;

    /**
     * The server player whose FoodData this is, set where vanilla hands the owner in (tick, eat). Valid only while it
     * is not removed and still owns this object (see {@link #btw$owner}).
     */
    @Unique
    private ServerPlayer btw$cachedOwner;

    /**
     * Cancel vanilla food tick entirely. FC food system runs from
     * ServerPlayerMixin.btw$tick() via PlayerBridge.
     *
     * <p><b>Feature:</b> 1.20.1 FoodData.tick: vanilla's hunger burn, saturation heal and starvation.</p>
     *
     * <p><b>Conflict with FC:</b> FC's FoodStats.onUpdate is the 4.B hunger (60 units, fat burned before hunger,
     * one heal per 600 ticks above 24); both running would burn and heal twice.</p>
     *
     * <p><b>Integration pattern:</b> A (replace): FC's tick runs for server players, and the vanilla FoodData keeps a
     * display copy (food / 3, fat as saturation) for the client HUD and other mods.</p>
     *
     * <p><b>Rebalance:</b> none; 4.B parity. The ticking player is also cached as this FoodData's owner, so the
     * exhaustion route and the eat conversion find fake players that are not in the player list.</p>
     *
     * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code tick(Player)}, cancelled for a ServerPlayer.</p>
     *
     * <p><b>Alternative considered and rejected:</b> ticking FC from ServerPlayerMixin only and letting vanilla tick
     * too: vanilla would heal and starve on its own 20-unit copy.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: FC's FoodStats is the server player's hunger; the ticking player is cached as the owner for the exhaustion and eat routes
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void btw$tick(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp) {
            this.btw$cachedOwner = sp;
            PlayerBridge pb = PlayerBridge.getOrCreate(sp);

            // Sync real state into FC layer
            pb.syncFromReal();

            // Run FC food tick
            pb.foodStats.onUpdate(pb);

            // Sync FC food level back to vanilla FoodData for client display
            // Client sees foodLevel/20 pips, FC uses foodLevel/60.
            // We set vanilla food level = FC level / 3 so the HUD shows correctly.
            btw$copyFromFc(pb);

            // Sync any health changes back
            pb.syncToReal();

            ci.cancel();
        }
    }

    /**
     * Vanilla items with an FC ItemFood already got their nutrition from FC: ItemStackMixin.btw$finishUsing
     * (ItemStack.finishUsingItem HEAD) runs the FC item's 1.5.2 onEaten -> FoodStats.addStats(ItemFood) with FC's
     * x3 GetHungerRestored (vanilla/server ItemFood.java:67-74, FoodStats.java:63-71). Item.finishUsingItem ->
     * Player.eat then feeds vanilla's FoodProperties through here -> eat(IF) -> {@link #btw$eat} (x3), a SECOND
     * time: raw meat 12 + 9, carrots 3 + 9 instead of 1.5.2's 12 / 3. Cancel exactly when FC fed this eat
     * ({@link PlayerBridge#fcFedForEat}: the FC ItemFood that fed the player this tick is this item's,
     * ProxyItem.fcItemFor). Every other food falls through to the eat(IF) conversion and restores once:
     * modern-only foods (pufferfish, beetroot, sweet berries ...) and an eat FC skipped. The 1.5.2 vanilla foods
     * (bread, cooked meat, golden apple ...) are btw.modern.ItemFood since FD-R1, so FC feeds them.
     *
     * <p>Targets the Forge 3-arg overload: Forge's Player.eat (Player.java:1996) calls
     * {@code eat(Item, ItemStack, LivingEntity)} (FoodData.java:32). The deprecated 2-arg overload this used to
     * target is never called by vanilla or Forge, so the cancel never fired (pre-existing double feed, triage
     * IU-R2); it only forwards here with a null entity (FoodData.java:26-29), which is resolved to this
     * FoodData's owner below, so one injection covers both overloads without checking an eat twice.
     * {@code remap = false}: the overload is Forge-added and keeps the name {@code eat} in the SRG runtime (no
     * mapping exists, and the annotation processor would reject an unmapped injector target).</p>
     *
     * <p><b>Table foods</b> (FCMOD-INTEGRATION(1.20.1): food_values).</p>
     *
     * <p><b>Feature:</b> 1.20.1 and modded foods with no 1.5.2 item (cooked mutton, rabbit, sweet berries, a modded
     * tomato) eaten by a server player.</p>
     *
     * <p><b>Conflict with FC:</b> they reached FC only through {@link #btw$eat}: 1.20.1 nutrition x3 with vanilla
     * saturation as FC fat, so cooked mutton fed 18/60 with fat 0.8 where FC's own cooked mutton feeds 12 with
     * 0.25.</p>
     *
     * <p><b>Integration pattern:</b> D (FC-extend): a data table (data/&lt;ns&gt;/btw_foods, btw.forge.food.BtwFoodTable)
     * gives such foods FC values; FcFoodValues resolves every food to one FC value.</p>
     *
     * <p><b>Rebalance:</b> a food with a table hunger value feeds {@code hunger x 3} with the table's fat multiplier
     * (the shipped minecraft.json gives each post-1.5.2 food its nearest FC relative's value). Foods without one keep
     * the x3 conversion. FC-fed foods are untouched.</p>
     *
     * <p><b>Injection strategy:</b> this HEAD inject, after the FC-fed cancel: FcFoodValues.of(stack, player) with
     * source TABLE feeds FoodStats.addStats(hunger60, fat), copies the FC values into this FoodData and cancels, so
     * eat(IF) never runs. The eater is cached as the owner here and in {@link #btw$tick}.</p>
     *
     * <p><b>Alternative considered and rejected:</b> rewriting the item's FoodProperties to the table value
     * (nutrition / 3): FoodProperties are static per item, rounding loses FC's 60-step resolution, and the fat
     * multiplier would still pass through vanilla's saturation formula.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): food_values: btw_foods table foods feed their FC value once; FC-fed foods stay cancelled and the eater becomes the cached owner
    @Inject(method = "eat(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void btw$eatItem(net.minecraft.world.item.Item item,
                             net.minecraft.world.item.ItemStack stack,
                             net.minecraft.world.entity.LivingEntity entity, CallbackInfo ci) {
        net.minecraft.world.entity.LivingEntity eater = entity != null ? entity : btw$owner();
        ServerPlayer owner = eater instanceof ServerPlayer sp && sp.getFoodData() == (Object) this ? sp : null;
        if (owner != null) {
            this.btw$cachedOwner = owner;
        }
        if (PlayerBridge.fcFedForEat(item, eater)) {
            // FC fed it: keep the vanilla copy current for the rest of this tick (Player.canEat, other mods' reads)
            if (owner != null) {
                btw$copyFromFc(PlayerBridge.getOrCreate(owner));
            }
            ci.cancel();
            return;
        }
        if (owner != null) {
            FcFoodValues.FcFood food = FcFoodValues.of(stack, owner);
            if (food != null && food.source() == FcFoodValues.Source.TABLE) {
                PlayerBridge pb = PlayerBridge.getOrCreate(owner);
                pb.foodStats.addStats(food.hunger60(), food.fatMultiplier());
                btw$copyFromFc(pb);
                ci.cancel();
            }
        }
    }

    /**
     * FC hunger contract: every hunger cost a mod charges through FoodData drains FC food (FCMOD-INTEGRATION(1.20.1):
     * hunger_contract, INTEGRATIONS #021).
     *
     * <p><b>Feature:</b> {@code FoodData.addExhaustion}, the 1.20.1 entry point mods use for their own hunger costs
     * (thirst and temperature mods, custom tools, abilities).</p>
     *
     * <p><b>Conflict with FC:</b> FC's FoodStats replaces FoodData for server players (the tick is cancelled above), so
     * these costs piled up in vanilla's unused exhaustion and never cost BTW food or fat.</p>
     *
     * <p><b>Integration pattern:</b> D (FC-extend): the cost goes to PlayerBridge.addExhaustion, FC's own 1.5.2 rule.</p>
     *
     * <p><b>Rebalance:</b> such costs now burn FC hunger and fat, multiplied by the worn-armour weight, and nothing
     * for an invulnerable player, exactly as FC's own costs.</p>
     *
     * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code addExhaustion(float)} (SRG m_38703_),
     * cancellable. With a known server owner ({@link #btw$owner}) the amount goes to FC and vanilla is cancelled;
     * otherwise (a client FoodData, a fake player never ticked or fed) vanilla runs. No double count: Player's own
     * causeFoodExhaustion is cancelled before it reaches here (PlayerMixin.btw$armorExhaustion) and the cancelled
     * FoodData.tick makes no calls.</p>
     *
     * <p><b>Alternative considered and rejected:</b> converting vanilla's exhaustion into FC's once per tick: it would
     * double-charge Player.causeFoodExhaustion and lose the armour and invulnerable rules.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: mods' FoodData.addExhaustion reaches FC's FoodStats under FC's armour and invulnerable rules
    @Inject(method = "addExhaustion", at = @At("HEAD"), cancellable = true)
    private void btw$routeExhaustion(float amount, CallbackInfo ci) {
        ServerPlayer owner = btw$owner();
        if (owner != null) {
            PlayerBridge.getOrCreate(owner).addExhaustion(amount);
            ci.cancel();
        }
    }

    /** Copies FC's food and fat into this FoodData, the display copy the client HUD and other mods read. */
    @Unique
    private void btw$copyFromFc(PlayerBridge pb) {
        this.foodLevel = pb.foodStats.getFoodLevel() / 3;
        this.saturationLevel = pb.foodStats.getSaturationLevel();
    }

    /**
     * The server player this FoodData belongs to, or null (client, or not a player's). FoodData keeps no owner
     * reference: the owner cached at the last tick or eat is used while it is not removed and still holds this
     * object (ServerPlayer.restoreFrom hands the SAME FoodData to the player that replaces it on End exit,
     * ServerPlayer.java:1127), else the server's player list is scanned.
     */
    @Unique
    private ServerPlayer btw$owner() {
        ServerPlayer cached = this.btw$cachedOwner;
        if (cached != null && !cached.isRemoved() && cached.getFoodData() == (Object) this) {
            return cached;
        }
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    if (sp.getFoodData() == (Object) this) {
                        this.btw$cachedOwner = sp;
                        return sp;
                    }
                }
            }
        } catch (Throwable t) {
            // no owner: vanilla nutrition applies
        }
        return null;
    }

    /**
     * Redirect vanilla food eating to FC food system.
     * Vanilla items call FoodData.eat(nutrition, saturation) — we convert
     * to FC's 3x scale and forward to the FC FoodStats.
     *
     * <p><b>Feature:</b> FoodData.eat(int, float): every food FC did not feed and the table does not cover (modded
     * foods, 1.20.1 foods without a table value), and mods feeding a player directly.</p>
     *
     * <p><b>Conflict with FC:</b> vanilla's 20-unit food and saturation are not BTW's hunger.</p>
     *
     * <p><b>Integration pattern:</b> C (rebalance): the nutrition is converted to FC's scale (x3) with the saturation
     * modifier as the FC fat multiplier.</p>
     *
     * <p><b>Rebalance:</b> unchanged; the owner now comes from {@link #btw$owner} (cached at the last tick or eat), so a
     * fake player that eats is fed as well.</p>
     *
     * <p><b>Injection strategy:</b> {@link Inject} at HEAD of {@code eat(IF)V}, always cancelled.</p>
     *
     * <p><b>Alternative considered and rejected:</b> scanning the player list on every eat (the former lookup): it
     * misses fake players and costs a list walk per eat.</p>
     */
    // FCMOD-INTEGRATION(1.20.1): food_values: non-FC foods feed FC at x3 through the cached owner, so fake players eat too
    @Inject(method = "eat(IF)V", at = @At("HEAD"), cancellable = true)
    private void btw$eat(int nutrition, float saturationModifier, CallbackInfo ci) {
        try {
            ServerPlayer owner = btw$owner();
            if (owner != null) {
                PlayerBridge pb = PlayerBridge.getOrCreate(owner);
                // Convert vanilla nutrition to FC's 3x scale
                int fcNutrition = nutrition * 3;
                pb.foodStats.addStats(fcNutrition, saturationModifier);
                // Sync back to vanilla
                btw$copyFromFc(pb);
            }
        } catch (Exception e) {
            // Fall through silently if we can't find the player
        }
        ci.cancel();
    }
}
