package btw.modern;

/**
 * 1.5.2 RenderSilverfish (vanilla/client RenderSilverfish.java): ModelSilverfish, a 180-degree
 * death roll and no render passes. Registered by FCEntityRenderer.registerMissingVanillaRenderers
 * for the frozen EntitySilverfish (betterthanwolves:fc_silverfish), as 1.5.2 RenderManager.java:65
 * did.
 */
public class RenderSilverfish extends RenderLiving {

    public RenderSilverfish() {
        super(new ModelSilverfish(), 0.3F);
    }

    /**
     * Return the silverfish's maximum death rotation.
     */
    public float getSilverfishDeathRotation(EntitySilverfish par1EntitySilverfish) {
        return 180.0F;
    }

    /**
     * Renders the silverfish.
     */
    public void renderSilverfish(EntitySilverfish par1EntitySilverfish, double par2, double par4, double par6, float par8, float par9) {
        super.doRenderLiving(par1EntitySilverfish, par2, par4, par6, par8, par9);
    }

    /**
     * Disallows the silverfish to render the renderPassModel.
     */
    public int shouldSilverfishRenderPass(EntitySilverfish par1EntitySilverfish, int par2, float par3) {
        return -1;
    }

    @Override
    public float getDeathMaxRotation(EntityLiving par1EntityLiving) {
        return this.getSilverfishDeathRotation((EntitySilverfish) par1EntityLiving);
    }

    /**
     * Queries whether should render the specified pass or not.
     */
    @Override
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return this.shouldSilverfishRenderPass((EntitySilverfish) par1EntityLiving, par2, par3);
    }

    @Override
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        this.renderSilverfish((EntitySilverfish) par1EntityLiving, par2, par4, par6, par8, par9);
    }

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.renderSilverfish((EntitySilverfish) par1Entity, par2, par4, par6, par8, par9);
    }
}
