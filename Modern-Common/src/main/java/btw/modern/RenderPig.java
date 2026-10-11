package btw.modern;

/**
 * 1.5.2 RenderPig (vanilla/client RenderPig.java): the saddle render pass.
 */
public class RenderPig extends RenderLiving {

    public RenderPig(ModelBase par1ModelBase, ModelBase par2ModelBase, float par3) {
        super(par1ModelBase, par3);
        this.setRenderPassModel(par2ModelBase);
    }

    // 1.5.2 RenderPig.java:25-36
    public int renderSaddledPig(EntityPig par1EntityPig, int par2, float par3) {
        if (par2 == 0 && par1EntityPig.getSaddled()) {
            this.loadTexture("/mob/saddle.png");
            return 1;
        } else {
            return -1;
        }
    }

    public void renderLivingPig(EntityPig par1EntityPig, double par2, double par4, double par6, float par8, float par9) {
        super.doRenderLiving(par1EntityPig, par2, par4, par6, par8, par9);
    }

    /**
     * Queries whether should render the specified pass or not.
     */
    @Override
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return this.renderSaddledPig((EntityPig) par1EntityLiving, par2, par3);
    }

    @Override
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        this.renderLivingPig((EntityPig) par1EntityLiving, par2, par4, par6, par8, par9);
    }

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.renderLivingPig((EntityPig) par1Entity, par2, par4, par6, par8, par9);
    }
}
