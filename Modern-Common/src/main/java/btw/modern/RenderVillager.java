package btw.modern;

/**
 * 1.5.2 RenderVillager (vanilla/client RenderVillager.java, FCMOD version): child scale and
 * the FC priest glowing-eyes pass. GetCurrentTradeLevel is an FCMOD addition to
 * EntityVillager that the Modern-Common stub does not declare.
 */
public class RenderVillager extends RenderLiving {

    /** Model of the villager. */
    public ModelVillager villagerModel;

    public RenderVillager() {
        super(new ModelVillager(0.0F), 0.5F);
        this.villagerModel = (ModelVillager) this.mainModel;

        // FCMOD: Added (client only)
        setRenderPassModel(villagerModel);
        // END FCMOD
    }

    /**
     * Determines whether Villager Render pass or not.
     */
    public int shouldVillagerRenderPass(EntityVillager par1EntityVillager, int par2, float par3) {
        // FCMOD: Changed (client only)
        /*
        return -1;
        */
        return renderEyes(par1EntityVillager, par2);
        // END FCMOD
    }

    public void renderVillager(EntityVillager par1EntityVillager, double par2, double par4, double par6, float par8, float par9) {
        super.doRenderLiving(par1EntityVillager, par2, par4, par6, par8, par9);
    }

    public void renderVillagerEquipedItems(EntityVillager par1EntityVillager, float par2) {
        super.renderEquippedItems(par1EntityVillager, par2);
    }

    // 1.5.2 RenderVillager.java:57-72
    public void preRenderVillager(EntityVillager par1EntityVillager, float par2) {
        float var3 = 0.9375F;

        if (par1EntityVillager.getGrowingAge() < 0) {
            var3 = (float) ((double) var3 * 0.5D);
            this.shadowSize = 0.25F;
        } else {
            this.shadowSize = 0.5F;
        }

        GL11.glScalef(var3, var3, var3);
    }

    /**
     * Allows the render to do any OpenGL state modifications necessary before the model is
     * rendered. Args: entityLiving, partialTickTime
     */
    @Override
    public void preRenderCallback(EntityLiving par1EntityLiving, float par2) {
        this.preRenderVillager((EntityVillager) par1EntityLiving, par2);
    }

    /**
     * Queries whether should render the specified pass or not.
     */
    @Override
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return this.shouldVillagerRenderPass((EntityVillager) par1EntityLiving, par2, par3);
    }

    @Override
    public void renderEquippedItems(EntityLiving par1EntityLiving, float par2) {
        this.renderVillagerEquipedItems((EntityVillager) par1EntityLiving, par2);
    }

    @Override
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        this.renderVillager((EntityVillager) par1EntityLiving, par2, par4, par6, par8, par9);
    }

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.renderVillager((EntityVillager) par1Entity, par2, par4, par6, par8, par9);
    }

    // FCMOD: Added (client only) (1.5.2 RenderVillager.java:113-147)
    public int renderEyes(EntityVillager villager, int iRenderPass) {
        if (iRenderPass == 0 && villager.getProfession() == 2
                && RenderEntityAccess.callInt(villager, "GetCurrentTradeLevel", 0) >= 5) {
            loadTexture("/btwmodtex/fcPriestEyes.png");

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GL11.glDisable(GL11.GL_LIGHTING);

            if (villager.isInvisible()) {
                GL11.glDepthMask(false);
            } else {
                GL11.glDepthMask(true);
            }

            char var5 = 61680;
            int var6 = var5 % 65536;
            int var7 = var5 / 65536;

            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, (float) var6 / 1.0F, (float) var7 / 1.0F);

            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

            return 1;
        }

        return -1;
    }
    // END FCMOD
}
