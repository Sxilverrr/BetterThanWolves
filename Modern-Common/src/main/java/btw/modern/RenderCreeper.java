package btw.modern;

/**
 * 1.5.2 RenderCreeper (vanilla/client RenderCreeper.java): pre-explosion swell, the white
 * flash, and the charged-creeper aura pass. getCreeperFlashIntensity is not on the
 * Modern-Common EntityCreeper stub, so it is called reflectively.
 *
 * <p>Adaptation: the flash colour multiplier is not drawn as a pass (RenderLiving's
 * colour pass is not ported); {@link #getOverlayWhiteness} hands its alpha to the Forge
 * renderer instead. The aura's GL_TEXTURE-matrix scroll is issued as in 1.5.2; GL11
 * records its translation per captured quad.</p>
 */
public class RenderCreeper extends RenderLiving {

    /** The creeper model. */
    private ModelBase creeperModel = new ModelCreeper(2.0F);

    public RenderCreeper() {
        super(new ModelCreeper(), 0.5F);
    }

    /**
     * Updates creeper scale in prerender callback (1.5.2 RenderCreeper.java:32-52).
     */
    public void updateCreeperScale(EntityCreeper par1EntityCreeper, float par2) {
        float var4 = getCreeperFlashIntensity(par1EntityCreeper, par2);
        float var5 = 1.0F + MathHelper.sin(var4 * 100.0F) * var4 * 0.01F;

        if (var4 < 0.0F) {
            var4 = 0.0F;
        }

        if (var4 > 1.0F) {
            var4 = 1.0F;
        }

        var4 *= var4;
        var4 *= var4;
        float var6 = (1.0F + var4 * 0.4F) * var5;
        float var7 = (1.0F + var4 * 0.1F) / var5;
        GL11.glScalef(var6, var7, var6);
    }

    /**
     * Updates color multiplier based on creeper state called by getColorMultiplier
     * (1.5.2 RenderCreeper.java:57-84).
     */
    public int updateCreeperColorMultiplier(EntityCreeper par1EntityCreeper, float par2, float par3) {
        float var5 = getCreeperFlashIntensity(par1EntityCreeper, par3);

        if ((int) (var5 * 10.0F) % 2 == 0) {
            return 0;
        } else {
            int var6 = (int) (var5 * 0.2F * 255.0F);

            if (var6 < 0) {
                var6 = 0;
            }

            if (var6 > 255) {
                var6 = 255;
            }

            short var7 = 255;
            short var8 = 255;
            short var9 = 255;
            return var6 << 24 | var7 << 16 | var8 << 8 | var9;
        }
    }

    /**
     * A method used to render a creeper's powered form as a pass model
     * (1.5.2 RenderCreeper.java:89-132).
     */
    public int renderCreeperPassModel(EntityCreeper par1EntityCreeper, int par2, float par3) {
        if (par1EntityCreeper.getPowered()) {
            if (par1EntityCreeper.isInvisible()) {
                GL11.glDepthMask(false);
            } else {
                GL11.glDepthMask(true);
            }

            if (par2 == 1) {
                float var4 = (float) par1EntityCreeper.ticksExisted + par3;
                this.loadTexture("/armor/power.png");
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glLoadIdentity();
                float var5 = var4 * 0.01F;
                float var6 = var4 * 0.01F;
                GL11.glTranslatef(var5, var6, 0.0F);
                this.setRenderPassModel(this.creeperModel);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glEnable(GL11.GL_BLEND);
                float var7 = 0.5F;
                GL11.glColor4f(var7, var7, var7, 1.0F);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
                return 1;
            }

            if (par2 == 2) {
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glLoadIdentity();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glEnable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_BLEND);
            }
        }

        return -1;
    }

    public int func_77061_b(EntityCreeper par1EntityCreeper, int par2, float par3) {
        return -1;
    }

    /**
     * Allows the render to do any OpenGL state modifications necessary before the model is
     * rendered. Args: entityLiving, partialTickTime
     */
    @Override
    public void preRenderCallback(EntityLiving par1EntityLiving, float par2) {
        this.updateCreeperScale((EntityCreeper) par1EntityLiving, par2);
    }

    /**
     * Returns an ARGB int color back. Args: entityLiving, lightBrightness, partialTickTime
     */
    @Override
    public int getColorMultiplier(EntityLiving par1EntityLiving, float par2, float par3) {
        return this.updateCreeperColorMultiplier((EntityCreeper) par1EntityLiving, par2, par3);
    }

    /**
     * The alpha of the 1.5.2 white colour multiplier (0..0.2 at full flash, 0 on the dark
     * half of each blink), which 1.5.2 RenderLiving.java:219-236 blended over the model.
     */
    @Override
    public float getOverlayWhiteness(EntityLiving entity, float partialTicks) {
        if (!(entity instanceof EntityCreeper)) {
            return 0.0F;
        }

        int var3 = this.updateCreeperColorMultiplier((EntityCreeper) entity, 1.0F, partialTicks);
        return (float) (var3 >> 24 & 255) / 255.0F;
    }

    /**
     * Queries whether should render the specified pass or not.
     */
    @Override
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return this.renderCreeperPassModel((EntityCreeper) par1EntityLiving, par2, par3);
    }

    @Override
    public int inheritRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return this.func_77061_b((EntityCreeper) par1EntityLiving, par2, par3);
    }

    /** 1.5.2 EntityCreeper.getCreeperFlashIntensity(partialTicks), not on the stub. */
    private static float getCreeperFlashIntensity(EntityCreeper creeper, float partialTicks) {
        return RenderEntityAccess.callFloatF(creeper, "getCreeperFlashIntensity", partialTicks, 0.0F);
    }
}
