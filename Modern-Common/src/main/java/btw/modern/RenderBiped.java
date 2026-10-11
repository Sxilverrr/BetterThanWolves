package btw.modern;

/**
 * 1.5.2 RenderBiped (vanilla/client RenderBiped.java, FCMOD version): armour render passes,
 * the held-item and head-slot item, and the held-item arm pose. FCItemArmorMod is an FC
 * client class Modern-Common cannot import, so its checks and calls go through
 * {@link RenderEntityAccess}.
 */
public class RenderBiped extends RenderLiving {

    private static final String FC_ITEM_ARMOR_MOD = "net.minecraft.src.btw.item.FCItemArmorMod";

    public ModelBiped modelBipedMain;
    public float field_77070_b;
    public ModelBiped field_82423_g;
    public ModelBiped field_82425_h;

    /** List of armor texture filenames. */
    private static final String[] bipedArmorFilenamePrefix = new String[] {"cloth", "chain", "iron", "diamond", "gold"};

    public RenderBiped(ModelBiped par1ModelBiped, float par2) {
        this(par1ModelBiped, par2, 1.0F);
    }

    public RenderBiped(ModelBiped par1ModelBiped, float par2, float par3) {
        super(par1ModelBiped, par2);
        this.modelBipedMain = par1ModelBiped;
        this.field_77070_b = par3;
        this.func_82421_b();
    }

    public void func_82421_b() {
        this.field_82423_g = new ModelBiped(1.0F);
        this.field_82425_h = new ModelBiped(0.5F);
    }

    /**
     * Queries whether should render the specified pass or not (1.5.2 RenderBiped.java:51-124).
     */
    @Override
    public int shouldRenderPass(EntityLiving par1EntityLiving, int par2, float par3) {
        ItemStack var4 = par1EntityLiving.getCurrentArmor(3 - par2);

        if (var4 != null) {
            Item var5 = var4.getItem();

            if (var5 instanceof ItemArmor) {
                ItemArmor var6 = (ItemArmor) var5;
                // FCMOD: Code added
                if (RenderEntityAccess.isInstance(var5, FC_ITEM_ARMOR_MOD)) {
                    return ShouldRenderPassModArmor(var4, par2, var6);
                }
                // END FCMOD
                this.loadTexture("/armor/" + bipedArmorFilenamePrefix[var6.renderIndex] + "_" + (par2 == 2 ? 2 : 1) + ".png");
                ModelBiped var7 = par2 == 2 ? this.field_82425_h : this.field_82423_g;
                var7.bipedHead.showModel = par2 == 0;
                var7.bipedHeadwear.showModel = par2 == 0;
                var7.bipedBody.showModel = par2 == 1 || par2 == 2;
                var7.bipedRightArm.showModel = par2 == 1;
                var7.bipedLeftArm.showModel = par2 == 1;
                var7.bipedRightLeg.showModel = par2 == 2 || par2 == 3;
                var7.bipedLeftLeg.showModel = par2 == 2 || par2 == 3;
                this.setRenderPassModel(var7);

                if (var7 != null) {
                    var7.onGround = this.mainModel.onGround;
                }

                if (var7 != null) {
                    var7.isRiding = this.mainModel.isRiding;
                }

                if (var7 != null) {
                    var7.isChild = this.mainModel.isChild;
                }

                float var8 = 1.0F;

                if (var6.getArmorMaterial() == EnumArmorMaterial.CLOTH) {
                    int var9 = var6.getColor(var4);
                    float var10 = (float) (var9 >> 16 & 255) / 255.0F;
                    float var11 = (float) (var9 >> 8 & 255) / 255.0F;
                    float var12 = (float) (var9 & 255) / 255.0F;
                    GL11.glColor3f(var8 * var10, var8 * var11, var8 * var12);

                    if (var4.isItemEnchanted()) {
                        return 31;
                    }

                    return 16;
                }

                GL11.glColor3f(var8, var8, var8);

                if (var4.isItemEnchanted()) {
                    return 15;
                }

                return 1;
            }
        }

        return -1;
    }

    // 1.5.2 RenderBiped.java:126-150
    @Override
    public void func_82408_c(EntityLiving par1EntityLiving, int par2, float par3) {
        ItemStack var4 = par1EntityLiving.getCurrentArmor(3 - par2);

        if (var4 != null) {
            Item var5 = var4.getItem();

            if (var5 instanceof ItemArmor) {
                // FCMOD
                if (RenderEntityAccess.isInstance(var5, FC_ITEM_ARMOR_MOD)) {
                    LoadSecondLayerOfModArmorTexture(par2, (ItemArmor) var5);

                    return;
                }
                // END FCMOD
                ItemArmor var6 = (ItemArmor) var5;
                this.loadTexture("/armor/" + bipedArmorFilenamePrefix[var6.renderIndex] + "_" + (par2 == 2 ? 2 : 1) + "_b.png");
                float var7 = 1.0F;
                GL11.glColor3f(var7, var7, var7);
            }
        }
    }

    // 1.5.2 RenderBiped.java:152-169
    @Override
    public void doRenderLiving(EntityLiving par1EntityLiving, double par2, double par4, double par6, float par8, float par9) {
        float var10 = 1.0F;
        GL11.glColor3f(var10, var10, var10);
        ItemStack var11 = par1EntityLiving.getHeldItem();
        this.func_82420_a(par1EntityLiving, var11);
        double var12 = par4 - (double) par1EntityLiving.yOffset;

        // 1.5.2 also excludes EntityPlayerSP here; only FC mobs reach this renderer.
        if (par1EntityLiving.isSneaking()) {
            var12 -= 0.125D;
        }

        super.doRenderLiving(par1EntityLiving, par2, var12, par6, par8, par9);
        this.field_82423_g.aimedBow = this.field_82425_h.aimedBow = this.modelBipedMain.aimedBow = false;
        this.field_82423_g.isSneak = this.field_82425_h.isSneak = this.modelBipedMain.isSneak = false;
        this.field_82423_g.heldItemRight = this.field_82425_h.heldItemRight = this.modelBipedMain.heldItemRight = 0;
    }

    // 1.5.2 RenderBiped.java:171-175
    public void func_82420_a(EntityLiving par1EntityLiving, ItemStack par2ItemStack) {
        this.field_82423_g.heldItemRight = this.field_82425_h.heldItemRight = this.modelBipedMain.heldItemRight = par2ItemStack != null ? 1 : 0;
        this.field_82423_g.isSneak = this.field_82425_h.isSneak = this.modelBipedMain.isSneak = par1EntityLiving.isSneaking();
    }

    // 1.5.2 RenderBiped.java:177-300
    @Override
    public void renderEquippedItems(EntityLiving par1EntityLiving, float par2) {
        float var3 = 1.0F;
        GL11.glColor3f(var3, var3, var3);
        super.renderEquippedItems(par1EntityLiving, par2);
        ItemStack var4 = par1EntityLiving.getHeldItem();
        ItemStack var5 = par1EntityLiving.getCurrentArmor(3);
        float var6;

        if (var5 != null) {
            GL11.glPushMatrix();
            this.modelBipedMain.bipedHead.postRender(0.0625F);

            // FCMOD: Change
            //if (var5.getItem().itemID < 256)
            if (var5.itemID < 4096 && Block.blocksList[var5.itemID] != null)
            // END FCMOD
            {
                // FCMOD: Change
                /*
                if (RenderBlocks.renderItemIn3d(Block.blocksList[var5.itemID].getRenderType()))
                */
                if (Block.blocksList[var5.itemID].DoesItemRenderAsBlock(var5.getItemDamage()))
                // END FCMOD
                {
                    var6 = 0.625F;
                    GL11.glTranslatef(0.0F, -0.25F, 0.0F);
                    GL11.glRotatef(90.0F, 0.0F, 1.0F, 0.0F);
                    GL11.glScalef(var6, -var6, -var6);
                }

                this.renderEquippedStack(par1EntityLiving, var5, 0);
            } else if (Item.skull != null && var5.itemID == Item.skull.itemID) {
                // 1.5.2 :211-223
                var6 = 1.0625F;
                GL11.glScalef(var6, -var6, -var6);
                String var7 = "";

                if (var5.hasTagCompound() && var5.getTagCompound().hasKey("SkullOwner")) {
                    var7 = var5.getTagCompound().getString("SkullOwner");
                }

                this.renderWornSkull(-0.5F, 0.0F, -0.5F, 180.0F, var5.getItemDamage(), var7);
            }

            GL11.glPopMatrix();
        }

        if (var4 != null) {
            GL11.glPushMatrix();

            if (this.mainModel.isChild) {
                var6 = 0.5F;
                GL11.glTranslatef(0.0F, 0.625F, 0.0F);
                GL11.glRotatef(-20.0F, -1.0F, 0.0F, 0.0F);
                GL11.glScalef(var6, var6, var6);
            }

            this.modelBipedMain.bipedRightArm.postRender(0.0625F);
            GL11.glTranslatef(-0.0625F, 0.4375F, 0.0625F);

            // FCMOD: Change
            /*
            if (var4.itemID < 256 && RenderBlocks.renderItemIn3d(Block.blocksList[var4.itemID].getRenderType()))
            */
            if (var4.itemID < 4096 && Block.blocksList[var4.itemID] != null && Block.blocksList[var4.itemID].DoesItemRenderAsBlock(var4.getItemDamage()))
            // END FCMOD
            {
                var6 = 0.5F;
                GL11.glTranslatef(0.0F, 0.1875F, -0.3125F);
                var6 *= 0.75F;
                GL11.glRotatef(20.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
                GL11.glScalef(-var6, -var6, var6);
            } else if (Item.bow != null && var4.itemID == Item.bow.itemID) {
                var6 = 0.625F;
                GL11.glTranslatef(0.0F, 0.125F, 0.3125F);
                GL11.glRotatef(-20.0F, 0.0F, 1.0F, 0.0F);
                GL11.glScalef(var6, -var6, var6);
                GL11.glRotatef(-100.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
            } else if (Item.itemsList[var4.itemID].isFull3D()) {
                var6 = 0.625F;

                if (itemShouldRotateAroundWhenRendering(Item.itemsList[var4.itemID])) {
                    GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
                    GL11.glTranslatef(0.0F, -0.125F, 0.0F);
                }

                this.func_82422_c();
                GL11.glScalef(var6, -var6, var6);
                GL11.glRotatef(-100.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
            } else {
                var6 = 0.375F;
                GL11.glTranslatef(0.25F, 0.1875F, -0.1875F);
                GL11.glScalef(var6, var6, var6);
                GL11.glRotatef(60.0F, 0.0F, 0.0F, 1.0F);
                GL11.glRotatef(-90.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(20.0F, 0.0F, 0.0F, 1.0F);
            }

            this.renderEquippedStack(par1EntityLiving, var4, 0);

            if (var4.getItem().requiresMultipleRenderPasses()) {
                this.renderEquippedStack(par1EntityLiving, var4, 1);
            }

            GL11.glPopMatrix();
        }
    }

    /** 1.5.2 TileEntitySkullRenderer's three skull models (TileEntitySkullRenderer.java:23-26). */
    private final ModelSkeletonHead skullModel = new ModelSkeletonHead(0, 0, 64, 32);
    private final ModelSkeletonHead skullModelZombie = new ModelSkeletonHead(0, 0, 64, 64);
    private final ModelSkeletonHead skullModelInfused = new ModelSkeletonHead(0, 0, 32, 16);

    /**
     * 1.5.2 TileEntitySkullRenderer.skullRenderer.func_82393_a (vanilla/client
     * TileEntitySkullRenderer.java:47-144, FCMOD version) for the only call RenderBiped makes,
     * a floor skull (par4 == 1). The bridge has no TileEntitySkullRenderer, so the body is
     * ported here. Adaptation: a player skull named by SkullOwner would download that
     * player's skin; nothing downloads skins here, so it uses /mob/char.png, the fallback
     * bindTextureByURL takes when the download is missing.
     */
    private void renderWornSkull(float par1, float par2, float par3, float par5, int par6, String par7Str) {
        ModelSkeletonHead var8 = this.skullModel;

        switch (par6) {
            case 0:
            default:
                this.loadTexture("/mob/skeleton.png");
                break;

            case 1:
                this.loadTexture("/mob/skeleton_wither.png");
                break;

            case 2:
                this.loadTexture("/mob/zombie.png");
                var8 = this.skullModelZombie;
                break;

            case 3:
                this.loadTexture("/mob/char.png");
                break;

            case 4:
                this.loadTexture("/mob/creeper.png");
                break;

            // FCMOD: Added
            case 5:
                this.loadTexture("/btwmodtex/fcInfusedSkull.png");
                var8 = this.skullModelInfused;
                break;
            // END FCMOD
        }

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glTranslatef(par1 + 0.5F, par2, par3 + 0.5F);
        float var10 = 0.0625F;
        GL11.glEnable(GL11.GL_RESCALE_NORMAL);
        GL11.glScalef(-1.0F, -1.0F, 1.0F);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        var8.render((Entity) null, 0.0F, 0.0F, 0.0F, par5, 0.0F, var10);

        // FCMOD: Added
        if (par6 == 5) {
            this.renderInfusedSkullEyes(var8, par5);
        }
        // END FCMOD

        GL11.glPopMatrix();
    }

    /** 1.5.2 FCMOD TileEntitySkullRenderer.RenderInfusedEyes (TileEntitySkullRenderer.java:152-172). */
    private void renderInfusedSkullEyes(ModelSkeletonHead model, float fYaw) {
        this.loadTexture("/btwmodtex/fcInfusedSkullEyes.png");
        float var4 = 1.0F;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDepthMask(true);
        char var5 = 61680;
        int var6 = var5 % 65536;
        int var7 = var5 / 65536;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, (float) var6 / 1.0F, (float) var7 / 1.0F);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, var4);
        model.render((Entity) null, 0.0F, 0.0F, 0.0F, fYaw, 0.0F, 0.0625F);
    }

    // 1.5.2 RenderBiped.java:302-305
    public void func_82422_c() {
        GL11.glTranslatef(0.0F, 0.1875F, 0.0F);
    }

    @Override
    public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9) {
        this.doRenderLiving((EntityLiving) par1Entity, par2, par4, par6, par8, par9);
    }

    // FCMOD: Function added (1.5.2 RenderBiped.java:319-375)
    private int ShouldRenderPassModArmor(ItemStack stack, int iArmorSlot, ItemArmor armorItem) {
        loadTexture(RenderEntityAccess.callString(armorItem, "GetWornTextureDirectory", "")
                + RenderEntityAccess.callString(armorItem, "GetWornTexturePrefix", "")
                + "_" + (iArmorSlot == 2 ? 2 : 1) + ".png");

        ModelBiped model = iArmorSlot == 2 ? this.field_82425_h : this.field_82423_g;

        model.bipedHead.showModel = iArmorSlot == 0;
        model.bipedHeadwear.showModel = iArmorSlot == 0;
        model.bipedBody.showModel = iArmorSlot == 1 || iArmorSlot == 2;
        model.bipedRightArm.showModel = iArmorSlot == 1;
        model.bipedLeftArm.showModel = iArmorSlot == 1;
        model.bipedRightLeg.showModel = iArmorSlot == 2 || iArmorSlot == 3;
        model.bipedLeftLeg.showModel = iArmorSlot == 2 || iArmorSlot == 3;

        setRenderPassModel(model);

        if (model != null) {
            model.onGround = this.mainModel.onGround;
            model.isRiding = this.mainModel.isRiding;
            model.isChild = this.mainModel.isChild;
        }

        if (RenderEntityAccess.callBoolean(armorItem, "HasCustomColors", false)) {
            int iColor = armorItem.getColor(stack);

            float fRed = (float) (iColor >> 16 & 255) / 255.0F;
            float fGreen = (float) (iColor >> 8 & 255) / 255.0F;
            float fBlue = (float) (iColor & 255) / 255.0F;

            GL11.glColor3f(fRed, fGreen, fBlue);
        } else {
            GL11.glColor3f(1F, 1F, 1F);
        }

        if (RenderEntityAccess.callBoolean(armorItem, "HasSecondRenderLayerWhenWorn", false)) {
            if (stack.isItemEnchanted()) {
                return 31;
            }

            return 16;
        } else {
            if (stack.isItemEnchanted()) {
                return 15;
            }

            return 1;
        }
    }

    // FCMOD: Function added (1.5.2 RenderBiped.java:377-382)
    private void LoadSecondLayerOfModArmorTexture(int iArmorSlot, ItemArmor armorItem) {
        loadTexture(RenderEntityAccess.callString(armorItem, "GetWornTextureDirectory", "")
                + RenderEntityAccess.callString(armorItem, "GetWornTexturePrefix", "")
                + "_" + (iArmorSlot == 2 ? 2 : 1) + "_b.png");

        GL11.glColor3f(1F, 1F, 1F);
    }
    // END FCMOD
}
