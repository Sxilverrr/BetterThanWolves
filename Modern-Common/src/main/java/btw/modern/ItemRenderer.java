package btw.modern;

/**
 * 1.5.2 ItemRenderer — the static 2D-item extruded-quad emitter
 * (vanilla/client ItemRenderer.java renderItemIn2D, verbatim), consumed by
 * RenderItem.renderDroppedItem for the items FC tile renderers embed (placed
 * tools, campfire food, basket contents), and renderItem(EntityLiving,
 * ItemStack, int), which mob renderers use for held items and head-slot blocks
 * (RenderBiped, RenderWitch, RenderSnowMan). Both are pure Tessellator
 * geometry. The first-person hand-rendering paths belong to the modern engine
 * and are intentionally not bridged.
 */
public class ItemRenderer {

    /** 1.5.2 ItemRenderer.renderBlocksInstance. */
    private RenderBlocks renderBlocksInstance = new RenderBlocks();

    /**
     * 1.5.2 ItemRenderer.renderItem (vanilla/client ItemRenderer.java:49-130, FCMOD
     * version). Adaptations: mc.renderEngine.bindTexture only records the texture name for
     * the capture (as RenderEngine.bindTexture does); EntityLiving.getItemIcon is inlined
     * as its 1.5.2 body, stack.getIconIndex() (EntityLiving.java:2517-2520), because the
     * Modern-Common EntityLiving stub does not declare it.
     */
    public void renderItem(EntityLiving par1EntityLiving, ItemStack par2ItemStack, int par3) {
        GL11.glPushMatrix();

        // FCMOD: Code change
        /*
        if (par2ItemStack.getItemSpriteNumber() == 0 && Block.blocksList[par2ItemStack.itemID] != null && RenderBlocks.renderItemIn3d(Block.blocksList[par2ItemStack.itemID].getRenderType()))
        */
        if (par2ItemStack.getItemSpriteNumber() == 0 && par2ItemStack.itemID < Block.blocksList.length
                && Block.blocksList[par2ItemStack.itemID] != null
                && Block.blocksList[par2ItemStack.itemID].DoesItemRenderAsBlock(par2ItemStack.getItemDamage()))
        // END FCMOD
        {
            bindTexture("/terrain.png");
            this.renderBlocksInstance.renderBlockAsItem(Block.blocksList[par2ItemStack.itemID], par2ItemStack.getItemDamage(), 1.0F);
        } else {
            Icon var4 = par2ItemStack.getIconIndex();

            if (var4 == null) {
                GL11.glPopMatrix();
                return;
            }

            if (par2ItemStack.getItemSpriteNumber() == 0) {
                bindTexture("/terrain.png");
            } else {
                bindTexture("/gui/items.png");
            }

            Tessellator var5 = Tessellator.instance;
            float var6 = var4.getMinU();
            float var7 = var4.getMaxU();
            float var8 = var4.getMinV();
            float var9 = var4.getMaxV();
            float var10 = 0.0F;
            float var11 = 0.3F;
            GL11.glEnable(GL11.GL_RESCALE_NORMAL);
            GL11.glTranslatef(-var10, -var11, 0.0F);
            float var12 = 1.5F;
            GL11.glScalef(var12, var12, var12);
            GL11.glRotatef(50.0F, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(335.0F, 0.0F, 0.0F, 1.0F);
            GL11.glTranslatef(-0.9375F, -0.0625F, 0.0F);
            // 1.5.2 passes var4.getSheetWidth()/getSheetHeight(); see sliceSheetWidth.
            renderItemIn2D(var5, var7, var8, var6, var9, sliceSheetWidth(var4), sliceSheetHeight(var4), 0.0625F);

            // ItemStack.hasEffect() is not on the Modern-Common stub; its 1.5.2 body is
            // getItem().hasEffect(this) (ItemStack.java:702-705).
            if (par2ItemStack != null && par2ItemStack.getItem() != null && par2ItemStack.getItem().hasEffect(par2ItemStack) && par3 == 0) {
                GL11.glDepthFunc(GL11.GL_EQUAL);
                GL11.glDisable(GL11.GL_LIGHTING);
                bindTexture("%blur%/misc/glint.png");
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
                float var13 = 0.76F;
                GL11.glColor4f(0.5F * var13, 0.25F * var13, 0.8F * var13, 1.0F);
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPushMatrix();
                float var14 = 0.125F;
                GL11.glScalef(var14, var14, var14);
                // Minecraft.getSystemTime() (milliseconds) is not bridged.
                float var15 = (float) (System.currentTimeMillis() % 3000L) / 3000.0F * 8.0F;
                GL11.glTranslatef(var15, 0.0F, 0.0F);
                GL11.glRotatef(-50.0F, 0.0F, 0.0F, 1.0F);
                renderItemIn2D(var5, 0.0F, 0.0F, 1.0F, 1.0F, 256, 256, 0.0625F);
                GL11.glPopMatrix();
                GL11.glPushMatrix();
                GL11.glScalef(var14, var14, var14);
                var15 = (float) (System.currentTimeMillis() % 4873L) / 4873.0F * 8.0F;
                GL11.glTranslatef(-var15, 0.0F, 0.0F);
                GL11.glRotatef(10.0F, 0.0F, 0.0F, 1.0F);
                renderItemIn2D(var5, 0.0F, 0.0F, 1.0F, 1.0F, 256, 256, 0.0625F);
                GL11.glPopMatrix();
                // Bridge: GL11 keeps only the texture translation and does not stack it, so the
                // push/pop above cannot restore it; reset to the identity 1.5.2 returns to.
                GL11.glLoadIdentity();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_LIGHTING);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
            }

            GL11.glDisable(GL11.GL_RESCALE_NORMAL);
        }

        GL11.glPopMatrix();
    }

    /**
     * The sheet width renderItemIn2D divides an icon's U span by. 1.5.2 passes the atlas
     * width, so that sheetWidth * (maxU - minU) is the icon's pixel width: one edge slice
     * per pixel, with a half-texel inset of 0.5 / sheetWidth. The bridge's icons report
     * 0..1 UVs with a 256 sheet, which would give 256 slices per side instead of 16. This
     * derives the sheet size from the icon instead: the same 256 for a stitched 1.5.2 icon,
     * 16 for a bridge icon. Falls back to the icon's own sheet size when the span is unknown.
     */
    public static int sliceSheetWidth(Icon icon) {
        return sliceSheetSize(icon.getIconWidth(), icon.getMaxU() - icon.getMinU(), icon.getSheetWidth());
    }

    /** The V counterpart of {@link #sliceSheetWidth}. */
    public static int sliceSheetHeight(Icon icon) {
        return sliceSheetSize(icon.getIconHeight(), icon.getMaxV() - icon.getMinV(), icon.getSheetHeight());
    }

    private static int sliceSheetSize(int iconPixels, float uvSpan, int sheetSize) {
        float span = Math.abs(uvSpan);

        if (iconPixels <= 0 || !(span > 0.0F)) {
            return sheetSize;
        }

        return Math.max(1, Math.round((float) iconPixels / span));
    }

    /** 1.5.2 mc.renderEngine.bindTexture: for the capture, recording the name is the bind. */
    private static void bindTexture(String textureName) {
        Tessellator.instance.setCurrentTextureName(textureName);
    }

    public static void renderItemIn2D(Tessellator tess, float maxU, float minV, float minU, float maxV, int sheetWidth, int sheetHeight, float thickness) {
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 0.0F, 1.0F);
        tess.addVertexWithUV(0.0D, 0.0D, 0.0D, (double) maxU, (double) maxV);
        tess.addVertexWithUV(1.0D, 0.0D, 0.0D, (double) minU, (double) maxV);
        tess.addVertexWithUV(1.0D, 1.0D, 0.0D, (double) minU, (double) minV);
        tess.addVertexWithUV(0.0D, 1.0D, 0.0D, (double) maxU, (double) minV);
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 0.0F, -1.0F);
        tess.addVertexWithUV(0.0D, 1.0D, (double) (0.0F - thickness), (double) maxU, (double) minV);
        tess.addVertexWithUV(1.0D, 1.0D, (double) (0.0F - thickness), (double) minU, (double) minV);
        tess.addVertexWithUV(1.0D, 0.0D, (double) (0.0F - thickness), (double) minU, (double) maxV);
        tess.addVertexWithUV(0.0D, 0.0D, (double) (0.0F - thickness), (double) maxU, (double) maxV);
        tess.draw();
        float uSpan = (float) sheetWidth * (maxU - minU);
        float vSpan = (float) sheetHeight * (maxV - minV);
        tess.startDrawingQuads();
        tess.setNormal(-1.0F, 0.0F, 0.0F);
        int i;
        float slice;
        float u;

        for (i = 0; (float) i < uSpan; ++i) {
            slice = (float) i / uSpan;
            u = maxU + (minU - maxU) * slice - 0.5F / (float) sheetWidth;
            tess.addVertexWithUV((double) slice, 0.0D, (double) (0.0F - thickness), (double) u, (double) maxV);
            tess.addVertexWithUV((double) slice, 0.0D, 0.0D, (double) u, (double) maxV);
            tess.addVertexWithUV((double) slice, 1.0D, 0.0D, (double) u, (double) minV);
            tess.addVertexWithUV((double) slice, 1.0D, (double) (0.0F - thickness), (double) u, (double) minV);
        }

        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(1.0F, 0.0F, 0.0F);
        float sliceEnd;

        for (i = 0; (float) i < uSpan; ++i) {
            slice = (float) i / uSpan;
            u = maxU + (minU - maxU) * slice - 0.5F / (float) sheetWidth;
            sliceEnd = slice + 1.0F / uSpan;
            tess.addVertexWithUV((double) sliceEnd, 1.0D, (double) (0.0F - thickness), (double) u, (double) minV);
            tess.addVertexWithUV((double) sliceEnd, 1.0D, 0.0D, (double) u, (double) minV);
            tess.addVertexWithUV((double) sliceEnd, 0.0D, 0.0D, (double) u, (double) maxV);
            tess.addVertexWithUV((double) sliceEnd, 0.0D, (double) (0.0F - thickness), (double) u, (double) maxV);
        }

        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 1.0F, 0.0F);

        for (i = 0; (float) i < vSpan; ++i) {
            slice = (float) i / vSpan;
            u = maxV + (minV - maxV) * slice - 0.5F / (float) sheetHeight;
            sliceEnd = slice + 1.0F / vSpan;
            tess.addVertexWithUV(0.0D, (double) sliceEnd, 0.0D, (double) maxU, (double) u);
            tess.addVertexWithUV(1.0D, (double) sliceEnd, 0.0D, (double) minU, (double) u);
            tess.addVertexWithUV(1.0D, (double) sliceEnd, (double) (0.0F - thickness), (double) minU, (double) u);
            tess.addVertexWithUV(0.0D, (double) sliceEnd, (double) (0.0F - thickness), (double) maxU, (double) u);
        }

        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, -1.0F, 0.0F);

        for (i = 0; (float) i < vSpan; ++i) {
            slice = (float) i / vSpan;
            u = maxV + (minV - maxV) * slice - 0.5F / (float) sheetHeight;
            tess.addVertexWithUV(1.0D, (double) slice, 0.0D, (double) minU, (double) u);
            tess.addVertexWithUV(0.0D, (double) slice, 0.0D, (double) maxU, (double) u);
            tess.addVertexWithUV(0.0D, (double) slice, (double) (0.0F - thickness), (double) maxU, (double) u);
            tess.addVertexWithUV(1.0D, (double) slice, (double) (0.0F - thickness), (double) minU, (double) u);
        }

        tess.draw();
    }
}
