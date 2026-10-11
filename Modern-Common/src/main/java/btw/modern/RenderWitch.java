package btw.modern;

/**
 * 1.5.2 RenderWitch (vanilla/client RenderWitch.java, FCMOD version): the held potion /
 * item, drawn through RenderManager.itemRenderer, and the item-holding nose pose.
 */
public class RenderWitch extends RenderLiving {

    private ModelWitch field_82414_a;
    private int field_82413_f;

    public RenderWitch() {
        super(new ModelWitch(0.0F), 0.5F);
        this.field_82414_a = (ModelWitch) this.mainModel;
        this.field_82413_f = this.field_82414_a.func_82899_a();
    }

    // 1.5.2 RenderWitch.java:32-45
    public void func_82412_a(EntityWitch par1EntityWitch, double par2, double par4, double par6, float par8, float par9) {
        ItemStack var10 = par1EntityWitch.getHeldItem();

        if (this.field_82414_a.func_82899_a() != this.field_82413_f) {
            // 1.5.2 also logs "Loaded new witch model" here.
            this.mainModel = this.field_82414_a = new ModelWitch(0.0F);
            this.field_82413_f = this.field_82414_a.func_82899_a();
        }

        this.field_82414_a.field_82900_g = var10 != null;
        super.doRenderLiving(par1EntityWitch, par2, par4, par6, par8, par9);
    }

    // 1.5.2 RenderWitch.java:47-130
    public void func_82411_a(EntityWitch par1EntityWitch, float par2) {
        float var3 = 1.0F;
        GL11.glColor3f(var3, var3, var3);
        super.renderEquippedItems(par1EntityWitch, par2);
        ItemStack var4 = par1EntityWitch.getHeldItem();

        if (var4 != null) {
            GL11.glPushMatrix();
            float var5;

            if (this.mainModel.isChild) {
                var5 = 0.5F;
                GL11.glTranslatef(0.0F, 0.625F, 0.0F);
                GL11.glRotatef(-20.0F, -1.0F, 0.0F, 0.0F);
                GL11.glScalef(var5, var5, var5);
            }

            this.field_82414_a.field_82898_f.postRender(0.0625F);
            GL11.glTranslatef(-0.0625F, 0.53125F, 0.21875F);

            // FCMOD: Change
            /*
            if (var4.itemID < 256 && RenderBlocks.renderItemIn3d(Block.blocksList[var4.itemID].getRenderType()))
            */
            if (var4.itemID < 4096 && Block.blocksList[var4.itemID] != null &&
                Block.blocksList[var4.itemID].DoesItemRenderAsBlock(var4.getItemDamage()))
            // END FCMOD
            {
                var5 = 0.5F;
                GL11.glTranslatef(0.0F, 0.1875F, -0.3125F);
                var5 *= 0.75F;
                GL11.glRotatef(20.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
                GL11.glScalef(var5, -var5, var5);
            } else if (Item.bow != null && var4.itemID == Item.bow.itemID) {
                var5 = 0.625F;
                GL11.glTranslatef(0.0F, 0.125F, 0.3125F);
                GL11.glRotatef(-20.0F, 0.0F, 1.0F, 0.0F);
                GL11.glScalef(var5, -var5, var5);
                GL11.glRotatef(-100.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
            } else if (Item.itemsList[var4.itemID].isFull3D()) {
                var5 = 0.625F;

                if (itemShouldRotateAroundWhenRendering(Item.itemsList[var4.itemID])) {
                    GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
                    GL11.glTranslatef(0.0F, -0.125F, 0.0F);
                }

                this.func_82410_b();
                GL11.glScalef(var5, -var5, var5);
                GL11.glRotatef(-100.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
            } else {
                var5 = 0.375F;
                GL11.glTranslatef(0.25F, 0.1875F, -0.1875F);
                GL11.glScalef(var5, var5, var5);
                GL11.glRotatef(60.0F, 0.0F, 0.0F, 1.0F);
                GL11.glRotatef(-90.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(20.0F, 0.0F, 0.0F, 1.0F);
            }

            GL11.glRotatef(-15.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(40.0F, 0.0F, 0.0F, 1.0F);
            this.renderEquippedStack(par1EntityWitch, var4, 0);

            if (var4.getItem().requiresMultipleRenderPasses()) {
                this.renderEquippedStack(par1EntityWitch, var4, 1);
            }

            GL11.glPopMatrix();
        }
    }

    public void func_82410_b() {
        GL11.glTranslatef(0.0F, 0.1875F, 0.0F);
    }

    public void func_82409_b(EntityWitch par1EntityWitch, float par2) {
        float var3 = 0.9375F;
        GL11.glScalef(var3, var3, var3);
    }

    /**
     * Allows the render to do any OpenGL state modifications necessary before the model is
     * rendered. Args: entityLiving, partialTickTime
     */
    @Override
    public void preRenderCallback(EntityLiving par1EntityLiving, float par2) {
        this.func_82409_b((EntityWitch) par1EntityLiving, par2);
    }

    @Override
    public void renderEquippedItems(EntityLiving par1EntityLiving, float par2) {
        this.func_82411_a((EntityWitch) par1EntityLiving, par2);
    }

    @Override
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        this.func_82412_a((EntityWitch) par1EntityLiving, par2, par4, par6, par8, par9);
    }

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.func_82412_a((EntityWitch) par1Entity, par2, par4, par6, par8, par9);
    }
}
