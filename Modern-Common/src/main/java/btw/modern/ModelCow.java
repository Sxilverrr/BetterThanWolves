package btw.modern;

public class ModelCow extends ModelQuadruped {
    private float m_fHeadRotation;

    public ModelCow() {
        super(12, 0.0F);
        this.head = new ModelRenderer(this, 0, 0);
        this.head.addBox(-4.0F, -4.0F, -6.0F, 8, 8, 6, 0.0F);
        this.head.setRotationPoint(0.0F, 4.0F, -8.0F);
        this.head.setTextureOffset(22, 0).addBox(-5.0F, -5.0F, -4.0F, 1, 3, 1, 0.0F);
        this.head.setTextureOffset(22, 0).addBox(4.0F, -5.0F, -4.0F, 1, 3, 1, 0.0F);
        this.body = new ModelRenderer(this, 18, 4);
        this.body.addBox(-6.0F, -10.0F, -7.0F, 12, 18, 10, 0.0F);
        this.body.setRotationPoint(0.0F, 5.0F, 2.0F);
        this.body.setTextureOffset(52, 0).addBox(-2.0F, 2.0F, -8.0F, 4, 6, 1);
        --this.leg1.rotationPointX;
        ++this.leg2.rotationPointX;
        --this.leg3.rotationPointX;
        ++this.leg4.rotationPointX;
        --this.leg3.rotationPointZ;
        --this.leg4.rotationPointZ;
        // 1.5.2 ModelCow.java:39: calf head z offset used by ModelQuadruped.render when isChild.
        this.field_78151_h += 2.0F;
    }

    public void setLivingAnimations(EntityLiving entity, float par2, float par3, float fPartialTick) {
        super.setLivingAnimations(entity, par2, par3, fPartialTick);
        try {
            java.lang.reflect.Method m = entity.getClass().getMethod("GetGrazeHeadVerticalOffset", float.class);
            java.lang.reflect.Method m2 = entity.getClass().getMethod("GetGrazeHeadRotation", float.class);
            head.rotationPointY = 4F + ((Float) m.invoke(entity, fPartialTick)) * 4F;
            m_fHeadRotation = ((Float) m2.invoke(entity, fPartialTick));
        } catch (Exception ignored) {}
    }

    public void setRotationAngles(float f, float f1, float f2, float f3, float f4, float f5, Entity entity) {
        super.setRotationAngles(f, f1, f2, f3, f4, f5, entity);
        AdjustRotationAnglesForKickAttack(f, f1, f2, f3, f4, f5, entity);
        head.rotateAngleX = m_fHeadRotation;
    }

    // FCMOD: Added (client only), 1.5.2 ModelCow.java:62 and :67-90. FCEntityCow's kick fields are
    // FC client code Modern-Common cannot import, so they are read reflectively.
    private void AdjustRotationAnglesForKickAttack(float par1, float par2, float par3,
            float par4, float par5, float par6, Entity entity) {
        if (entity != null) {
            int iAttackProgressCounter = RenderEntityAccess.getInt(entity, "m_iKickAttackInProgressCounter", -1);

            if (iAttackProgressCounter > 0) {
                float fRotationFactor = 2F;

                if (RenderEntityAccess.getInt(entity, "m_iKickAttackLegUsed", 0) == 0) {
                    leg1.rotateAngleX = MathHelper.cos((float) Math.PI * fRotationFactor) * 1.4F;
                } else {
                    leg2.rotateAngleX = MathHelper.cos((float) Math.PI * fRotationFactor) * 1.4F;
                }
            }
        }
    }
    // END FCMOD
}
