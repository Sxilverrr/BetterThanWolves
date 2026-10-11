package btw.modern;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.5.2 RenderLiving (vanilla/client RenderLiving.java, FCMOD version) ported onto the
 * Tessellator capture pipeline: Forge FCEntityRenderer calls doRender and replays the
 * captured quads. The GL11 state calls are issued exactly as 1.5.2 issues them; the
 * capture core decides what each one means.
 *
 * <p>Adaptations, each marked where it happens:</p>
 * <ul>
 *   <li>The untextured colour-multiplier / hurt-death red pass (:190-242) is not drawn.
 *       The Forge renderer applies the hurt/death tint itself through MC's OverlayTexture,
 *       and reads a renderer's white flash through {@link #getOverlayWhiteness}.</li>
 *   <li>Name tags (passSpecialRender, :420-551) are not drawn: the bridge has no
 *       FontRenderer.</li>
 * </ul>
 *
 * <p>The transform is 1.5.2's (rotateCorpse does the only yaw rotation, then
 * glScalef(-1,-1,1)), so the capture needs no extra flip. The stand-in this replaces used
 * glScalef(1,-1,-1), a 180-degree-turned variant that FCEntityRenderer compensated for;
 * GL11.usedLegacyEntityFlip() tells the two apart.</p>
 */
public class RenderLiving extends Render {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("BTW-RenderLiving");

    /** Renderer classes whose doRenderLiving has already logged a failure. */
    private static final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();

    public ModelBase mainModel;

    /** The model to be used during the render passes. */
    public ModelBase renderPassModel;

    public RenderLiving(ModelBase par1ModelBase, float par2) {
        this.mainModel = par1ModelBase;
        this.shadowSize = par2;
    }

    /**
     * Sets the model to be used in the current render pass (the first render pass is done
     * after the primary model is rendered) Args: model
     */
    public void setRenderPassModel(ModelBase par1ModelBase) {
        this.renderPassModel = par1ModelBase;
    }

    // 1.5.2 RenderLiving.java:49-64
    private float interpolateRotation(float par1, float par2, float par3) {
        float var4;

        for (var4 = par2 - par1; var4 < -180.0F; var4 += 360.0F) {
            ;
        }

        while (var4 >= 180.0F) {
            var4 -= 360.0F;
        }

        return par1 + par3 * var4;
    }

    // 1.5.2 RenderLiving.java:66-257
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_CULL_FACE);

        // 1.5.2 wraps :93-245 in try/catch(Exception) + printStackTrace. Widened to
        // Throwable (a NoSuchMethodError from an FC override must not escape the render)
        // and widened to cover the model-flag setup at :70-89 as well, so the matrix stack
        // is always popped.
        try {
            this.mainModel.onGround = this.renderSwingProgress(par1EntityLiving, par9);

            if (this.renderPassModel != null) {
                this.renderPassModel.onGround = this.mainModel.onGround;
            }

            this.mainModel.isRiding = par1EntityLiving.isRiding();

            if (this.renderPassModel != null) {
                this.renderPassModel.isRiding = this.mainModel.isRiding;
            }

            this.mainModel.isChild = par1EntityLiving.isChild();

            if (this.renderPassModel != null) {
                this.renderPassModel.isChild = this.mainModel.isChild;
            }

            float var10 = this.interpolateRotation(par1EntityLiving.prevRenderYawOffset, par1EntityLiving.renderYawOffset, par9);
            float var11 = this.interpolateRotation(par1EntityLiving.prevRotationYawHead, par1EntityLiving.rotationYawHead, par9);
            float var12 = par1EntityLiving.prevRotationPitch + (par1EntityLiving.rotationPitch - par1EntityLiving.prevRotationPitch) * par9;
            // FCMOD: Added (client only)
            if (par1EntityLiving.HasHeadCrabbedSquid()) {
                var12 = 0;
            }
            // END FCMOD
            this.renderLivingAt(par1EntityLiving, par2, par4, par6);
            float var13 = this.handleRotationFloat(par1EntityLiving, par9);
            this.rotateCorpse(par1EntityLiving, var13, var10, par9);
            float var14 = 0.0625F;
            GL11.glEnable(GL11.GL_RESCALE_NORMAL);
            GL11.glScalef(-1.0F, -1.0F, 1.0F);
            this.preRenderCallback(par1EntityLiving, par9);
            GL11.glTranslatef(0.0F, -24.0F * var14 - 0.0078125F, 0.0F);
            float var15 = par1EntityLiving.prevLimbYaw + (par1EntityLiving.limbYaw - par1EntityLiving.prevLimbYaw) * par9;
            float var16 = par1EntityLiving.limbSwing - par1EntityLiving.limbYaw * (1.0F - par9);

            if (par1EntityLiving.isChild()) {
                var16 *= 3.0F;
            }

            if (var15 > 1.0F) {
                var15 = 1.0F;
            }

            GL11.glEnable(GL11.GL_ALPHA_TEST);
            this.mainModel.setLivingAnimations(par1EntityLiving, var16, var15, par9);
            this.renderModel(par1EntityLiving, var16, var15, var13, var11 - var10, var12, var14);
            int var18;
            float var19;
            float var20;
            float var22;

            for (int var17 = 0; var17 < 4; ++var17) {
                var18 = this.shouldRenderPass(par1EntityLiving, var17, par9);

                if (var18 > 0) {
                    this.renderPassModel.setLivingAnimations(par1EntityLiving, var16, var15, par9);
                    this.renderPassModel.render(par1EntityLiving, var16, var15, var13, var11 - var10, var12, var14);

                    if ((var18 & 240) == 16) {
                        this.func_82408_c(par1EntityLiving, var17, par9);
                        this.renderPassModel.render(par1EntityLiving, var16, var15, var13, var11 - var10, var12, var14);
                    }

                    if ((var18 & 15) == 15) {
                        // Enchantment glint. The Forge renderer draws "misc/glint.png" quads with
                        // MC's own glint type (which supplies the scroll), so the texture-matrix
                        // scale/rotate here only matter to 1.5.2; GL11 records translation only.
                        var19 = (float) par1EntityLiving.ticksExisted + par9;
                        this.loadTexture("%blur%/misc/glint.png");
                        GL11.glEnable(GL11.GL_BLEND);
                        var20 = 0.5F;
                        GL11.glColor4f(var20, var20, var20, 1.0F);
                        GL11.glDepthFunc(GL11.GL_EQUAL);
                        GL11.glDepthMask(false);

                        for (int var21 = 0; var21 < 2; ++var21) {
                            GL11.glDisable(GL11.GL_LIGHTING);
                            var22 = 0.76F;
                            GL11.glColor4f(0.5F * var22, 0.25F * var22, 0.8F * var22, 1.0F);
                            GL11.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
                            GL11.glMatrixMode(GL11.GL_TEXTURE);
                            GL11.glLoadIdentity();
                            float var23 = var19 * (0.001F + (float) var21 * 0.003F) * 20.0F;
                            float var24 = 0.33333334F;
                            GL11.glScalef(var24, var24, var24);
                            GL11.glRotatef(30.0F - (float) var21 * 60.0F, 0.0F, 0.0F, 1.0F);
                            GL11.glTranslatef(0.0F, var23, 0.0F);
                            GL11.glMatrixMode(GL11.GL_MODELVIEW);
                            this.renderPassModel.render(par1EntityLiving, var16, var15, var13, var11 - var10, var12, var14);
                        }

                        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                        GL11.glMatrixMode(GL11.GL_TEXTURE);
                        GL11.glDepthMask(true);
                        GL11.glLoadIdentity();
                        GL11.glMatrixMode(GL11.GL_MODELVIEW);
                        GL11.glEnable(GL11.GL_LIGHTING);
                        GL11.glDisable(GL11.GL_BLEND);
                        GL11.glDepthFunc(GL11.GL_LEQUAL);
                    }

                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                }
            }

            GL11.glDepthMask(true);
            this.renderEquippedItems(par1EntityLiving, par9);
            // :190-242 colour-multiplier pass (hurt/death red, creeper flash) intentionally
            // not drawn: the Forge renderer applies it via OverlayTexture (getOverlayWhiteness).
            GL11.glDisable(GL11.GL_RESCALE_NORMAL);
        } catch (Throwable var25) {
            // A pass that threw while in GL_TEXTURE mode (RenderCreeper/RenderWither swirl)
            // would make the pop below miss the model-view stack.
            GL11.glMatrixMode(GL11.GL_MODELVIEW);

            if (loggedFailures.add(this.getClass().getName())) {
                LOGGER.warn("[render] {}.doRenderLiving failed for {}: {}",
                        this.getClass().getSimpleName(),
                        par1EntityLiving != null ? par1EntityLiving.getClass().getSimpleName() : "null",
                        var25.toString(), var25);
            }
        }

        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glPopMatrix();
        this.passSpecialRender(par1EntityLiving, par2, par4, par6);
    }

    /**
     * Renders the model in RenderLiving (1.5.2 RenderLiving.java:262-288).
     */
    public void renderModel(EntityLiving par1EntityLiving, float par2, float par3, float par4, float par5, float par6, float par7) {
        this.func_98190_a(par1EntityLiving);

        if (!par1EntityLiving.isInvisible()) {
            this.mainModel.render(par1EntityLiving, par2, par3, par4, par5, par6, par7);
        } else {
            // 1.5.2 :270 draws a 0.15-alpha ghost only when !func_98034_c(thePlayer). For any
            // non-player entity func_98034_c is isInvisible() (Entity.java:2122-2125), which
            // is true here, so only the else branch (:284-287) is reachable for FC mobs.
            this.mainModel.setRotationAngles(par2, par3, par4, par5, par6, par7, par1EntityLiving);
        }
    }

    // 1.5.2 RenderLiving.java:290-293
    public void func_98190_a(EntityLiving par1EntityLiving) {
        String var2 = par1EntityLiving.getTexture();

        // Bridge guard: a null name would clear the capture's texture (Render.loadTexture).
        if (var2 != null) {
            this.loadTexture(var2);
        }
    }

    /**
     * Sets a simple glTranslate on a LivingEntity.
     */
    public void renderLivingAt(EntityLiving par1EntityLiving, double par2, double par4, double par6) {
        GL11.glTranslatef((float) par2, (float) par4, (float) par6);
    }

    // 1.5.2 RenderLiving.java:303-319. Args: entity, handleRotationFloat, bodyYaw, partialTicks
    public void rotateCorpse(EntityLiving par1EntityLiving, float par2, float par3, float par4) {
        GL11.glRotatef(180.0F - par3, 0.0F, 1.0F, 0.0F);

        if (par1EntityLiving.deathTime > 0) {
            float var5 = ((float) par1EntityLiving.deathTime + par4 - 1.0F) / 20.0F * 1.6F;
            var5 = MathHelper.sqrt_float(var5);

            if (var5 > 1.0F) {
                var5 = 1.0F;
            }

            GL11.glRotatef(var5 * this.getDeathMaxRotation(par1EntityLiving), 0.0F, 0.0F, 1.0F);
        }
    }

    // 1.5.2 RenderLiving.java:321-324 returns getSwingProgress(par2). The Modern-Common
    // EntityLiving stub does not declare it, so its body (EntityLiving.java:2388-2398) is
    // inlined; no FC entity overrides it.
    public float renderSwingProgress(EntityLiving par1EntityLiving, float par2) {
        float var3 = par1EntityLiving.swingProgress - par1EntityLiving.prevSwingProgress;

        if (var3 < 0.0F) {
            ++var3;
        }

        return par1EntityLiving.prevSwingProgress + var3 * par2;
    }

    /**
     * Defines what float the third param in setRotationAngles of ModelBase is
     * (1.5.2 RenderLiving.java:329-332).
     */
    public float handleRotationFloat(EntityLiving par1EntityLiving, float par2) {
        return (float) par1EntityLiving.ticksExisted + par2;
    }

    public void renderEquippedItems(EntityLiving par1EntityLiving, float par2) {}

    /**
     * 1.5.2 subclasses' this.renderManager.itemRenderer.renderItem(entity, stack, pass)
     * (RenderBiped.java:209/291/295, RenderWitch.java:121/125, RenderSnowMan.java:60).
     * Guarded for a renderer built outside RenderManager.AddEntityRenderer.
     */
    protected void renderEquippedStack(EntityLiving entity, ItemStack stack, int pass) {
        if (this.renderManager != null && this.renderManager.itemRenderer != null) {
            this.renderManager.itemRenderer.renderItem(entity, stack, pass);
        }
    }

    /**
     * 1.5.2 Item.shouldRotateAroundWhenRendering: false by default (vanilla/client
     * Item.java:658-661). Only ItemFishingRod and ItemCarrotOnAStick override it, and no
     * FC or Modern-Common item class declares it, so it is always false for anything a
     * mob can hold. A reflective lookup would miss for every item class and log a WARN.
     */
    static boolean itemShouldRotateAroundWhenRendering(Item item) {
        return false;
    }

    /**
     * 1.5.2 :339-381 draws stuck arrows; only RenderPlayer calls it, so FC mobs never do.
     */
    public void renderArrowsStuckInEntity(EntityLiving par1EntityLiving, float par2) {}

    // 1.5.2 RenderLiving.java:383-386
    public int inheritRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return this.shouldRenderPass(par1EntityLiving, par2, par3);
    }

    /**
     * Queries whether should render the specified pass or not.
     */
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return -1;
    }

    public void func_82408_c(EntityLiving par1EntityLiving, int par2, float par3) {}

    public float getDeathMaxRotation(EntityLiving par1EntityLiving) {
        return 90.0F;
    }

    /**
     * Returns an ARGB int color back. Args: entityLiving, lightBrightness, partialTickTime
     */
    public int getColorMultiplier(EntityLiving par1EntityLiving, float par2, float par3) {
        return 0;
    }

    /**
     * Bridge hook (not 1.5.2): how strongly the entity is flashing white this frame, 0..1.
     * 1.5.2 drew that flash as the colour-multiplier pass (:219-236), which this port does
     * not draw; the Forge renderer reads this instead and feeds MC's OverlayTexture.
     * Default 0. RenderCreeper returns the alpha of its 1.5.2 white colour multiplier.
     */
    public float getOverlayWhiteness(EntityLiving entity, float partialTicks) {
        return 0.0F;
    }

    /**
     * Allows the render to do any OpenGL state modifications necessary before the model is
     * rendered. Args: entityLiving, partialTickTime
     */
    public void preRenderCallback(EntityLiving par1EntityLiving, float par2) {}

    /**
     * 1.5.2 :420-477 draws the name tag. Not drawn here: the bridge has no FontRenderer
     * (RenderManager.getFontRenderer returns null).
     */
    public void passSpecialRender(EntityLiving par1EntityLiving, double par2, double par4, double par6) {}

    public void func_96449_a(EntityLiving par1EntityLiving, double par2, double par4, double par6, String par8Str, float par9, double par10) {}

    /**
     * 1.5.2 :494-551 draws the label text. No-op for the same reason as passSpecialRender.
     */
    public void renderLivingLabel(EntityLiving par1EntityLiving, String par2Str, double par3, double par5, double par7, int par9) {}

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.doRenderLiving((EntityLiving) par1Entity, par2, par4, par6, par8, par9);
    }
}
