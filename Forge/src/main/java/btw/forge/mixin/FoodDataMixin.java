package btw.forge.mixin;

import btw.forge.PlayerBridge;
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
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {

    @Shadow private int foodLevel;
    @Shadow private float saturationLevel;

    /**
     * Cancel vanilla food tick entirely. FC food system runs from
     * ServerPlayerMixin.btw$tick() via PlayerBridge.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void btw$tick(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp) {
            PlayerBridge pb = PlayerBridge.getOrCreate(sp);

            // Sync real state into FC layer
            pb.syncFromReal();

            // Run FC food tick
            pb.foodStats.onUpdate(pb);

            // Sync FC food level back to vanilla FoodData for client display
            // Client sees foodLevel/20 pips, FC uses foodLevel/60.
            // We set vanilla food level = FC level / 3 so the HUD shows correctly.
            this.foodLevel = pb.foodStats.getFoodLevel() / 3;
            this.saturationLevel = pb.foodStats.getSaturationLevel();

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
     */
    @Inject(method = "eat(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void btw$eatItem(net.minecraft.world.item.Item item,
                             net.minecraft.world.item.ItemStack stack,
                             net.minecraft.world.entity.LivingEntity entity, CallbackInfo ci) {
        net.minecraft.world.entity.LivingEntity eater = entity != null ? entity : btw$owner();
        if (PlayerBridge.fcFedForEat(item, eater)) {
            ci.cancel();
        }
    }

    /**
     * The server player this FoodData belongs to, or null (client, or not a player's). FoodData keeps no owner
     * reference; same lookup as {@link #btw$eat}. Only used for the deprecated 2-arg eat, which passes no entity.
     */
    @Unique
    private ServerPlayer btw$owner() {
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    if (sp.getFoodData() == (Object) this) {
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
     */
    @Inject(method = "eat(IF)V", at = @At("HEAD"), cancellable = true)
    private void btw$eat(int nutrition, float saturationModifier, CallbackInfo ci) {
        // Find the owning player via Minecraft's internals
        // FoodData doesn't store a player reference, but the mixin can
        // intercept and route through PlayerBridge
        try {
            // Find the player that owns this FoodData by checking all server players.
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    if (sp.getFoodData() == (Object) this) {
                        PlayerBridge pb = PlayerBridge.getOrCreate(sp);
                        // Convert vanilla nutrition to FC's 3x scale
                        int fcNutrition = nutrition * 3;
                        pb.foodStats.addStats(fcNutrition, saturationModifier);
                        // Sync back to vanilla
                        this.foodLevel = pb.foodStats.getFoodLevel() / 3;
                        this.saturationLevel = pb.foodStats.getSaturationLevel();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Fall through silently if we can't find the player
        }
        ci.cancel();
    }
}
