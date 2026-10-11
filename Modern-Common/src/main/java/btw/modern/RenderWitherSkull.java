package btw.modern;

/**
 * 1.5.2 RenderWitherSkull (vanilla/client RenderWitherSkull.java): a plain Render that draws
 * the wither head cube. The stub this replaces extended RenderLiving, whose doRender cast
 * the skull (an EntityFireball) to EntityLiving and threw, so skulls drew nothing.
 * isInvulnerable is not on the Modern-Common EntityWitherSkull stub.
 */
public class RenderWitherSkull extends Render {

    /** The Skeleton's head model. */
    ModelSkeletonHead skeletonHeadModel = new ModelSkeletonHead();

    // 1.5.2 RenderWitherSkull.java:25-40
    private float func_82400_a(float par1, float par2, float par3) {
        float var4;

        for (var4 = par2 - par1; var4 < -180.0F; var4 += 360.0F) {
            ;
        }

        while (var4 >= 180.0F) {
            var4 -= 360.0F;
        }

        return par1 + par3 * var4;
    }

    // 1.5.2 RenderWitherSkull.java:42-65
    public void func_82399_a(EntityWitherSkull par1EntityWitherSkull, double par2, double par4, double par6, float par8, float par9) {
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_CULL_FACE);
        float var10 = this.func_82400_a(par1EntityWitherSkull.prevRotationYaw, par1EntityWitherSkull.rotationYaw, par9);
        float var11 = par1EntityWitherSkull.prevRotationPitch + (par1EntityWitherSkull.rotationPitch - par1EntityWitherSkull.prevRotationPitch) * par9;
        GL11.glTranslatef((float) par2, (float) par4, (float) par6);
        float var12 = 0.0625F;
        GL11.glEnable(GL11.GL_RESCALE_NORMAL);
        GL11.glScalef(-1.0F, -1.0F, 1.0F);
        GL11.glEnable(GL11.GL_ALPHA_TEST);

        if (RenderEntityAccess.callBoolean(par1EntityWitherSkull, "isInvulnerable", false)) {
            this.loadTexture("/mob/wither_invul.png");
        } else {
            this.loadTexture("/mob/wither.png");
        }

        this.skeletonHeadModel.render(par1EntityWitherSkull, 0.0F, 0.0F, 0.0F, var10, var11, var12);
        GL11.glPopMatrix();
    }

    /**
     * Actually renders the given argument.
     */
    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.func_82399_a((EntityWitherSkull) par1Entity, par2, par4, par6, par8, par9);
    }
}
