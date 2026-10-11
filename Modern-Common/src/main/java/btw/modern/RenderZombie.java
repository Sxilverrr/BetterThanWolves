package btw.modern;

/**
 * 1.5.2 RenderZombie (vanilla/client RenderZombie.java): swaps the main and armour models
 * to {@link ModelZombieVillager} for villager zombies (func_82427_a) and shakes a converting
 * zombie's corpse yaw. Also used for pig zombies (FCBetterThanWolves.java:2445).
 */
public class RenderZombie extends RenderBiped {

    private ModelBiped field_82434_o;
    private ModelZombieVillager field_82432_p;
    // Assigned by func_82421_b() during the RenderBiped constructor: no initializers here,
    // or they would overwrite those values once super() returns.
    public ModelBiped field_82437_k;
    public ModelBiped field_82435_l;
    public ModelBiped field_82436_m;
    public ModelBiped field_82433_n;
    private int field_82431_q = 1;

    public RenderZombie() {
        super(new ModelZombie(), 0.5F, 1.0F);
        this.field_82434_o = this.modelBipedMain;
        this.field_82432_p = new ModelZombieVillager();
    }

    // 1.5.2 RenderZombie.java:34-42
    @Override
    public void func_82421_b() {
        this.field_82423_g = new ModelZombie(1.0F, true);
        this.field_82425_h = new ModelZombie(0.5F, true);
        this.field_82437_k = this.field_82423_g;
        this.field_82435_l = this.field_82425_h;
        this.field_82436_m = new ModelZombieVillager(1.0F, 0.0F, true);
        this.field_82433_n = new ModelZombieVillager(0.5F, 0.0F, true);
    }

    public int func_82429_a(EntityZombie par1EntityZombie, int par2, float par3) {
        this.func_82427_a(par1EntityZombie);
        return super.shouldRenderPass(par1EntityZombie, par2, par3);
    }

    public void func_82426_a(EntityZombie par1EntityZombie, double par2, double par4, double par6, float par8, float par9) {
        this.func_82427_a(par1EntityZombie);
        super.doRenderLiving(par1EntityZombie, par2, par4, par6, par8, par9);
    }

    public void func_82428_a(EntityZombie par1EntityZombie, float par2) {
        this.func_82427_a(par1EntityZombie);
        super.renderEquippedItems(par1EntityZombie, par2);
    }

    // 1.5.2 RenderZombie.java:62-86
    private void func_82427_a(EntityZombie par1EntityZombie) {
        if (par1EntityZombie.isVillager()) {
            if (this.field_82431_q != this.field_82432_p.func_82897_a()) {
                this.field_82432_p = new ModelZombieVillager();
                this.field_82431_q = this.field_82432_p.func_82897_a();
                this.field_82436_m = new ModelZombieVillager(1.0F, 0.0F, true);
                this.field_82433_n = new ModelZombieVillager(0.5F, 0.0F, true);
            }

            this.mainModel = this.field_82432_p;
            this.field_82423_g = this.field_82436_m;
            this.field_82425_h = this.field_82433_n;
        } else {
            this.mainModel = this.field_82434_o;
            this.field_82423_g = this.field_82437_k;
            this.field_82425_h = this.field_82435_l;
        }

        this.modelBipedMain = (ModelBiped) this.mainModel;
    }

    // 1.5.2 RenderZombie.java:88-96. isConverting is not on the Modern-Common stub.
    public void func_82430_a(EntityZombie par1EntityZombie, float par2, float par3, float par4) {
        if (RenderEntityAccess.callBoolean(par1EntityZombie, "isConverting", false)) {
            par3 += (float) (Math.cos((double) par1EntityZombie.ticksExisted * 3.25D) * Math.PI * 0.25D);
        }

        super.rotateCorpse(par1EntityZombie, par2, par3, par4);
    }

    @Override
    public void renderEquippedItems(EntityLiving par1EntityLiving, float par2) {
        this.func_82428_a((EntityZombie) par1EntityLiving, par2);
    }

    @Override
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        this.func_82426_a((EntityZombie) par1EntityLiving, par2, par4, par6, par8, par9);
    }

    /**
     * Queries whether should render the specified pass or not.
     */
    @Override
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        return this.func_82429_a((EntityZombie) par1EntityLiving, par2, par3);
    }

    @Override
    public void rotateCorpse(EntityLiving par1EntityLiving, float par2, float par3, float par4) {
        this.func_82430_a((EntityZombie) par1EntityLiving, par2, par3, par4);
    }

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.func_82426_a((EntityZombie) par1Entity, par2, par4, par6, par8, par9);
    }
}
