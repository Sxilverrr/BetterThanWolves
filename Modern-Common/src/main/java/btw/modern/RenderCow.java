package btw.modern;

/**
 * 1.5.2 RenderCow (vanilla/client RenderCow.java, FCMOD version): the FC udder render pass.
 * FCClientModelCowUdder and FCEntityCow.GotMilk are FC client code Modern-Common cannot
 * import, so the model is built and the flag read reflectively. The commented-out
 * RenderKickAttackDebug line-drawing (:71-156) is not ported.
 */
public class RenderCow extends RenderLiving {

    public RenderCow(ModelBase par1ModelBase, float par2) {
        super(par1ModelBase, par2);
        // FCMOD: Added (client only)
        m_ModelUdder = (ModelBase) RenderEntityAccess.newInstance("net.minecraft.src.btw.client.FCClientModelCowUdder");

        setRenderPassModel(m_ModelUdder);
        // END FCMOD
    }

    public void renderCow(EntityCow par1EntityCow, double par2, double par4, double par6, float par8, float par9) {
        super.doRenderLiving(par1EntityCow, par2, par4, par6, par8, par9);
    }

    @Override
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        this.renderCow((EntityCow) par1EntityLiving, par2, par4, par6, par8, par9);
    }

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.renderCow((EntityCow) par1Entity, par2, par4, par6, par8, par9);
    }

    // FCMOD: Added (client only)
    ModelBase m_ModelUdder;

    // 1.5.2 RenderCow.java:59-69
    @Override
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        if (par2 == 0 && m_ModelUdder != null && RenderEntityAccess.callBoolean(par1EntityLiving, "GotMilk", false)) {
            loadTexture("/btwmodtex/cow_udder.png");

            return 1;
        }

        return -1;
    }
    // END FCMOD
}
