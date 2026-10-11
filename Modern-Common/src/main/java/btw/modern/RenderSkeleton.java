package btw.modern;

/**
 * 1.5.2 RenderSkeleton (vanilla/client RenderSkeleton.java): wither-skeleton scale and the
 * full-3D held-item offset; armour, held bow and arm pose come from {@link RenderBiped}.
 */
public class RenderSkeleton extends RenderBiped {

    public RenderSkeleton() {
        super(new ModelSkeleton(), 0.5F);
    }

    // 1.5.2 RenderSkeleton.java:26-32
    public void func_82438_a(EntitySkeleton par1EntitySkeleton, float par2) {
        if (par1EntitySkeleton.getSkeletonType() == 1) {
            GL11.glScalef(1.2F, 1.2F, 1.2F);
        }
    }

    // 1.5.2 RenderSkeleton.java:34-37
    @Override
    public void func_82422_c() {
        GL11.glTranslatef(0.09375F, 0.1875F, 0.0F);
    }

    /**
     * Allows the render to do any OpenGL state modifications necessary before the model is
     * rendered. Args: entityLiving, partialTickTime
     */
    @Override
    public void preRenderCallback(EntityLiving par1EntityLiving, float par2) {
        this.func_82438_a((EntitySkeleton) par1EntityLiving, par2);
    }
}
