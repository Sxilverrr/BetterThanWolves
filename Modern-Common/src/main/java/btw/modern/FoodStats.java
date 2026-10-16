package btw.modern;

public class FoodStats {
    // FCMOD-INTEGRATION(1.20.1): hunger_contract: FC's burn and heal thresholds as named constants, so the HUD, the
    // eat preview (btw.forge.food.FcFoodValues.preview) and other mods' hunger rules read the very values onUpdate uses
    // (INTEGRATIONS #021). Values unchanged from 4.B.

    /** Exhaustion that burns one FC hunger unit (one sixth of a pip). */
    public static final float HUNGER_BURN_THRESHOLD = 1.33F;

    /** Exhaustion that burns {@link #FAT_PER_BURN} fat while fat is burned before hunger. */
    public static final float FAT_BURN_THRESHOLD = 0.5F;

    /** Fat (saturation) burned per {@link #FAT_BURN_THRESHOLD} of exhaustion. */
    public static final float FAT_PER_BURN = 0.125F;

    /** FC heals one point every 600 ticks only while the food level is above this (4 of 10 pips). */
    public static final int REGEN_FOOD_THRESHOLD = 24;

    // FC: 3x resolution — 60 = full (not 20)
    private int foodLevel = 60;

    // FC: player spawns with zero fat (not 5.0F)
    private float foodSaturationLevel = 0F;

    private float foodExhaustionLevel;

    private int foodTimer = 0;

    // FC: 3x resolution
    private int prevFoodLevel = 60;

    /**
     * FC food gain logic.
     * iFoodGain is one third regular hunger gained, with 6 units being a full pip.
     * Only excess food (above the 60 cap) converts to saturation (fat), capped at 20F.
     */
    public void addStats(int iFoodGain, float fFatMultiplier) {
        int iPreviousFoodLevel = foodLevel;

        foodLevel = Math.min(iFoodGain + foodLevel, 60);

        int iExcessFood = iFoodGain - (foodLevel - iPreviousFoodLevel);

        if (iExcessFood > 0) {
            // divide by 3 due to increased resolution
            foodSaturationLevel = Math.min(foodSaturationLevel + (float) iExcessFood * fFatMultiplier / 3F, 20F);
        }
    }

    /**
     * Eat some food.
     */
    public void addStats(ItemFood par1ItemFood) {
        this.addStats(par1ItemFood.GetHungerRestored(), par1ItemFood.getSaturationModifier());
    }

    /**
     * Handles the food game logic (FC version).
     * Called on server only.
     */
    public void onUpdate(EntityPlayer player) {
        int iDifficulty = player.worldObj != null ? player.worldObj.difficultySetting : 2;

        prevFoodLevel = foodLevel;

        if (iDifficulty > 0) {
            // burn hunger
            while (foodLevel > 0 && foodExhaustionLevel >= HUNGER_BURN_THRESHOLD && !shouldBurnFatBeforeHunger()) {
                foodExhaustionLevel -= HUNGER_BURN_THRESHOLD;

                foodLevel = Math.max(foodLevel - 1, 0);
            }

            // burn fat
            while (foodExhaustionLevel >= FAT_BURN_THRESHOLD && shouldBurnFatBeforeHunger()) {
                foodExhaustionLevel -= FAT_BURN_THRESHOLD;

                foodSaturationLevel = Math.max(foodSaturationLevel - FAT_PER_BURN, 0F);
            }
        } else {
            foodExhaustionLevel = 0F;
        }

        // FCMOD-INTEGRATION(1.20.1): hunger_contract: the 1.20.1 naturalRegeneration gamerule gates FC's heal as it gates
        // vanilla's (EntityPlayer.IsNaturalRegenerationEnabled; 1.5.2 had no such rule, so the base answers true)
        if (foodLevel > REGEN_FOOD_THRESHOLD && player.IsNaturalRegenerationEnabled()
                && player.getHealth() > 0 && player.getHealth() < player.getMaxHealth()) {
            ++foodTimer;

            if (foodTimer >= 600) // once every 30 seconds
            {
                player.heal(1);
                foodTimer = 0;
            }
        } else if (foodLevel <= 0 && foodSaturationLevel <= 0.01F) {
            ++foodTimer;

            if (foodTimer >= 80) {
                if (iDifficulty > 0) {
                    player.attackEntityFrom(DamageSource.starve, 1);
                }

                foodTimer = 0;
            }

            // reset the exhaustion level so that it doesn't stack up while the player is starving
            foodExhaustionLevel = 0F;
        } else {
            foodTimer = 0;
        }
    }

    /**
     * Reads the food data for the player.
     */
    public void readNBT(NBTTagCompound par1NBTTagCompound) {
        if (par1NBTTagCompound.hasKey("foodLevel")) {
            this.foodLevel = par1NBTTagCompound.getInteger("foodLevel");
            this.foodTimer = par1NBTTagCompound.getInteger("foodTickTimer");
            this.foodSaturationLevel = par1NBTTagCompound.getFloat("foodSaturationLevel");
            this.foodExhaustionLevel = par1NBTTagCompound.getFloat("foodExhaustionLevel");

            // upgrade vanilla food level x3 if not already adjusted
            if (!par1NBTTagCompound.hasKey("fcFoodLevelAdjusted")) {
                foodLevel = foodLevel * 3;
                foodSaturationLevel = 0F;
            }

            // sanity check the values as apparently they can get fucked up when importing from vanilla
            if (foodLevel > 60 || foodLevel < 0) {
                foodLevel = 60;
            }

            if (foodSaturationLevel > 20F || foodSaturationLevel < 0F) {
                foodSaturationLevel = 20F;
            }
        }
    }

    /**
     * Writes the food data for the player.
     */
    public void writeNBT(NBTTagCompound par1NBTTagCompound) {
        par1NBTTagCompound.setInteger("foodLevel", this.foodLevel);
        par1NBTTagCompound.setInteger("foodTickTimer", this.foodTimer);
        par1NBTTagCompound.setFloat("foodSaturationLevel", this.foodSaturationLevel);
        par1NBTTagCompound.setFloat("foodExhaustionLevel", this.foodExhaustionLevel);

        par1NBTTagCompound.setBoolean("fcFoodLevelAdjusted", true);
    }

    /**
     * Get the player's food level.
     */
    public int getFoodLevel() {
        return this.foodLevel;
    }

    public int getPrevFoodLevel() {
        return this.prevFoodLevel;
    }

    /**
     * Get whether the player must eat food.
     * FC: 60 max instead of 20.
     */
    public boolean needFood() {
        return this.foodLevel < 60;
    }

    /**
     * Adds input to foodExhaustionLevel to a max of 40.
     */
    public void addExhaustion(float par1) {
        this.foodExhaustionLevel = Math.min(this.foodExhaustionLevel + par1, 40.0F);
    }

    /**
     * Get the player's food saturation level.
     */
    public float getSaturationLevel() {
        return this.foodSaturationLevel;
    }

    public void setFoodLevel(int par1) {
        this.foodLevel = par1;
    }

    public void setFoodSaturationLevel(float par1) {
        this.foodSaturationLevel = par1;
    }

    /**
     * The exhaustion accumulated towards the next hunger or fat burn (0-40). Read-only view for the client sync
     * (BTWNetwork.PenaltySync) and food-bar mods.
     */
    public float getFoodExhaustionLevel() {
        return this.foodExhaustionLevel;
    }

    /**
     * FC: Only burn fat when the corresponding hunger pip is completely depleted.
     */
    private boolean shouldBurnFatBeforeHunger() {
        return shouldBurnFatBeforeHunger(foodLevel, foodSaturationLevel);
    }

    /**
     * FC's fat-before-hunger rule for any food level (0-60) and fat (0-20): fat burns first while it exceeds two fat
     * per remaining hunger pip (rounded up). The instance rule above delegates here, so a client that only knows the
     * synced food and fat (HUD, food-bar mods) predicts exactly what the server burns next.
     */
    public static boolean shouldBurnFatBeforeHunger(int food, float fat) {
        return fat > (float) ((food + 5) / 6) * 2F;
    }
}
