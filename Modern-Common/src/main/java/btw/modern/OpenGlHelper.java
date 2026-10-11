package btw.modern;

public class OpenGlHelper {

    // 1.5.2 OpenGlHelper.initializeTextures (OpenGlHelper.java:43-57) sets these to GL_TEXTURE0 /
    // GL_TEXTURE1. Nothing calls it in the port, so initialise them here: with both left at 0,
    // setActiveTexture could not tell the lightmap unit from the diffuse unit.
    public static int defaultTexUnit = 33984;
    public static int lightmapTexUnit = 33985;
    public static float lastBrightnessX;
    public static float lastBrightnessY;

    public static void initializeTextures() {
        defaultTexUnit = 33984;
        lightmapTexUnit = 33985;
    }

    /** Routes the active unit to GL11 so glEnable/glDisable(GL_TEXTURE_2D) hit the right one. */
    public static void setActiveTexture(int texture) {
        GL11.setActiveTextureUnit(texture == lightmapTexUnit ? 1 : 0);
    }

    public static void setClientActiveTexture(int texture) {}

    /**
     * 1.5.2 glMultiTexCoord2f on the lightmap unit: the light every following vertex of this
     * object uses. GL11 records it so captured quads drawn after it (the full-bright eyes passes)
     * carry it instead of the host's packed light.
     */
    public static void setLightmapTextureCoords(int target, float s, float t) {
        if (target == lightmapTexUnit) {
            GL11.setLightmapCoords(s, t);
        }
        lastBrightnessX = s;
        lastBrightnessY = t;
    }

    public static void glBlendFunc(int sFactorRGB, int dFactorRGB, int sFactorAlpha, int dFactorAlpha) {
        GL11.glBlendFunc(sFactorRGB, dFactorRGB);
    }
}
