package btw.modern;

/**
 * 1.5.2 RenderChicken (vanilla/client RenderChicken.java): the wing flap is the model's
 * third rotation argument (ModelChicken wing rotateAngleZ = par3), so handleRotationFloat
 * must return getWingRotation, not ticksExisted. The flap fields are not on the
 * Modern-Common EntityChicken stub, so they are read reflectively.
 */
public class RenderChicken extends RenderLiving {

    public RenderChicken(ModelBase par1ModelBase, float par2) {
        super(par1ModelBase, par2);
    }

    public void renderChicken(EntityChicken par1EntityChicken, double par2, double par4, double par6, float par8, float par9) {
        super.doRenderLiving(par1EntityChicken, par2, par4, par6, par8, par9);
    }

    // 1.5.2 RenderChicken.java:29-34
    public float getWingRotation(EntityChicken par1EntityChicken, float par2) {
        float field_70888_h = RenderEntityAccess.getFloat(par1EntityChicken, "field_70888_h", 0.0F);
        float field_70886_e = RenderEntityAccess.getFloat(par1EntityChicken, "field_70886_e", 0.0F);
        float field_70884_g = RenderEntityAccess.getFloat(par1EntityChicken, "field_70884_g", 0.0F);
        float destPos = RenderEntityAccess.getFloat(par1EntityChicken, "destPos", 0.0F);
        float var3 = field_70888_h + (field_70886_e - field_70888_h) * par2;
        float var4 = field_70884_g + (destPos - field_70884_g) * par2;
        return (MathHelper.sin(var3) + 1.0F) * var4;
    }

    /**
     * Defines what float the third param in setRotationAngles of ModelBase is
     */
    @Override
    public float handleRotationFloat(EntityLiving par1EntityLiving, float par2) {
        return this.getWingRotation((EntityChicken) par1EntityLiving, par2);
    }

    @Override
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        this.renderChicken((EntityChicken) par1EntityLiving, par2, par4, par6, par8, par9);
    }

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.renderChicken((EntityChicken) par1Entity, par2, par4, par6, par8, par9);
    }
}
