package btw.modern;

/**
 * 1.5.2 RenderSnowMan (vanilla/client RenderSnowMan.java, FCMOD version): the pumpkin head,
 * drawn through RenderManager.itemRenderer.
 */
public class RenderSnowMan extends RenderLiving {

    /** A reference to the Snowman model in RenderSnowMan. */
    private ModelSnowMan snowmanModel;

    public RenderSnowMan() {
        super(new ModelSnowMan(), 0.5F);
        this.snowmanModel = (ModelSnowMan) super.mainModel;
        this.setRenderPassModel(this.snowmanModel);
    }

    /**
     * Renders this snowman's pumpkin (1.5.2 RenderSnowMan.java:34-63).
     */
    public void renderSnowmanPumpkin(EntitySnowman par1EntitySnowman, float par2) {
        super.renderEquippedItems(par1EntitySnowman, par2);
        ItemStack var3 = new ItemStack(Block.pumpkin, 1);

        // FCMOD: Changed (client only)
        //if (var3 != null && var3.getItem().itemID < 256)
        // (var3.itemID is var3.getItem().itemID; read directly so a missing ItemBlock
        // cannot NPE the check)
        if (var3 != null && var3.itemID < 4096 && Block.blocksList[var3.itemID] != null)
        // END FCMOD
        {
            GL11.glPushMatrix();
            this.snowmanModel.head.postRender(0.0625F);

            // FCMOD: Changed (client only)
            /*
            if (RenderBlocks.renderItemIn3d(Block.blocksList[var3.itemID].getRenderType()))
            */
            if (Block.blocksList[var3.itemID].DoesItemRenderAsBlock(var3.getItemDamage()))
            // END FCMOD
            {
                float var4 = 0.625F;
                GL11.glTranslatef(0.0F, -0.34375F, 0.0F);
                GL11.glRotatef(90.0F, 0.0F, 1.0F, 0.0F);
                GL11.glScalef(var4, -var4, var4);
            }

            this.renderEquippedStack(par1EntitySnowman, var3, 0);
            GL11.glPopMatrix();
        }
    }

    @Override
    public void renderEquippedItems(EntityLiving par1EntityLiving, float par2) {
        this.renderSnowmanPumpkin((EntitySnowman) par1EntityLiving, par2);
    }
}
