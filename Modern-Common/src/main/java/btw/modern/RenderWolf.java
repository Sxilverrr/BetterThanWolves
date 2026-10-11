package btw.modern;

/**
 * 1.5.2 RenderWolf (vanilla/client RenderWolf.java): tail angle as the model's third
 * rotation argument, the wet-shake shading pass and the dyed collar pass. FCClientRenderWolf
 * adds its glowing-eyes pass on top through super.shouldRenderPass. getWolfShaking and
 * getShadingWhileShaking are not on the Modern-Common EntityWolf stub.
 */
public class RenderWolf extends RenderLiving {

    public RenderWolf(ModelBase par1ModelBase, ModelBase par2ModelBase, float par3) {
        super(par1ModelBase, par3);
        this.setRenderPassModel(par2ModelBase);
    }

    public float getTailRotation(EntityWolf par1EntityWolf, float par2) {
        return par1EntityWolf.getTailRotation();
    }

    // 1.5.2 RenderWolf.java:34-57
    public int func_82447_a(EntityWolf par1EntityWolf, int par2, float par3) {
        float var4;

        if (par2 == 0 && RenderEntityAccess.callBoolean(par1EntityWolf, "getWolfShaking", false)) {
            var4 = par1EntityWolf.getBrightness(par3) * RenderEntityAccess.callFloatF(par1EntityWolf, "getShadingWhileShaking", par3, 1.0F);
            this.loadTexture(par1EntityWolf.getTexture());
            GL11.glColor3f(var4, var4, var4);
            return 1;
        } else if (par2 == 1 && par1EntityWolf.isTamed()) {
            this.loadTexture("/mob/wolf_collar.png");
            var4 = 1.0F;
            int var5 = par1EntityWolf.getCollarColor();
            GL11.glColor3f(var4 * EntitySheep.fleeceColorTable[var5][0], var4 * EntitySheep.fleeceColorTable[var5][1], var4 * EntitySheep.fleeceColorTable[var5][2]);
            return 1;
        } else {
            return -1;
        }
    }

    /**
     * Queries whether should render the specified pass or not.
     */
    @Override
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return this.func_82447_a((EntityWolf) par1EntityLiving, par2, par3);
    }

    /**
     * Defines what float the third param in setRotationAngles of ModelBase is
     */
    @Override
    public float handleRotationFloat(EntityLiving par1EntityLiving, float par2) {
        return this.getTailRotation((EntityWolf) par1EntityLiving, par2);
    }
}
