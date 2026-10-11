package btw.modern;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures vertex data from FC's rendering code.
 *
 * FC code calls addVertexWithUV/addVertex to build quads.
 * In MC 1.5.2 these went to OpenGL. Here we capture them
 * so the Forge bridge can convert them to BakedQuads.
 *
 * When capturing is enabled, every 4 vertices form a quad
 * stored in the {@link #capturedQuads} list (other GL primitives
 * are converted, see {@link #addToPrimitive}).
 *
 * <p>Per-vertex state follows 1.5.2 (vanilla/client Tessellator.java): colour, normal and
 * brightness belong to a vertex only if the current draw set them. startDrawing clears the
 * hasColor/hasNormals/hasBrightness flags (Tessellator.java:322-339); a draw that never calls
 * setColor* leaves the colour array off, so its vertices take GL's current colour, i.e. the
 * {@link GL11} stand-in's glColor (draw() :218-230). The port used to keep one sticky colour per
 * thread instead, so the last colour any capture set (a dragon orb's dark red, a block face
 * shade) was baked into every later FC model on that thread.</p>
 */
public class Tessellator {

    /** Thread-local Tessellator instance — prevents race conditions between
     *  Render thread (entity/block entity capture) and Worker threads (block model capture). */
    private static final ThreadLocal<Tessellator> THREAD_LOCAL = ThreadLocal.withInitial(Tessellator::new);

    /** The static instance — returns the thread-local Tessellator for the current thread. */
    public static final Tessellator instance = new Tessellator() {
        private Tessellator local() { return THREAD_LOCAL.get(); }
        @Override public void startCapturing() { local().startCapturing(); }
        @Override public java.util.List<CapturedQuad> stopCapturing() { return local().stopCapturing(); }
        @Override public int getCurrentVertexCount() { return local().getCurrentVertexCount(); }
        @Override public int getCapturedQuadCount() { return local().getCapturedQuadCount(); }
        @Override public boolean isCapturing() { return local().isCapturing(); }
        @Override public int draw() { return local().draw(); }
        @Override public void startDrawingQuads() { local().startDrawingQuads(); }
        @Override public void startDrawing(int drawMode) { local().startDrawing(drawMode); }
        @Override public void setTextureUV(double u, double v) { local().setTextureUV(u, v); }
        @Override public void setBrightness(int brightness) { local().setBrightness(brightness); }
        @Override public void setColorOpaque_F(float r, float g, float b) { local().setColorOpaque_F(r, g, b); }
        @Override public void setColorRGBA_F(float r, float g, float b, float a) { local().setColorRGBA_F(r, g, b, a); }
        @Override public void setColorOpaque(int r, int g, int b) { local().setColorOpaque(r, g, b); }
        @Override public void setColorRGBA(int r, int g, int b, int a) { local().setColorRGBA(r, g, b, a); }
        @Override public void setColorOpaque_I(int color) { local().setColorOpaque_I(color); }
        @Override public void setColorRGBA_I(int color, int alpha) { local().setColorRGBA_I(color, alpha); }
        @Override public void disableColor() { local().disableColor(); }
        @Override public void setNormal(float x, float y, float z) { local().setNormal(x, y, z); }
        @Override public void setTranslation(double x, double y, double z) { local().setTranslation(x, y, z); }
        @Override public void addTranslation(float x, float y, float z) { local().addTranslation(x, y, z); }
        @Override public void setCurrentTextureName(String name) { local().setCurrentTextureName(name); }
        @Override public void addVertexWithUV(double x, double y, double z, double u, double v) { local().addVertexWithUV(x, y, z, u, v); }
        @Override public void addVertex(double x, double y, double z) { local().addVertex(x, y, z); }
    };

    /** Whether vertex capture is active. */
    private boolean capturing = false;

    /** Captured quads — each quad is 4 CapturedVertex objects. */
    private final List<CapturedQuad> capturedQuads = new ArrayList<>();

    /** Vertices being accumulated for the current primitive. */
    private final List<CapturedVertex> currentVertices = new ArrayList<>();

    // Current state applied to each vertex
    private double curU, curV;
    private float curR = 1, curG = 1, curB = 1, curA = 1;
    private float curNX, curNY, curNZ;
    private int curBrightness = GL11.FULL_BRIGHT;
    private double transX, transY, transZ;

    // 1.5.2 per-draw flags (Tessellator.java:71/77/82/94), cleared by startDrawing (:322-339).
    private boolean hasColor;
    private boolean hasNormals;
    private boolean hasBrightness;
    private boolean isColorDisabled;

    /** GL primitive of the current draw; captures that never call startDrawing (block models) are quads. */
    private int drawMode = GL11.GL_QUADS;

    /** The texture name to associate with captured quads (set before adding vertices). */
    private String currentTextureName;

    protected Tessellator() {}

    /** A single captured vertex with position, UV, color, normal. */
    public static class CapturedVertex {
        public final double x, y, z;
        public final double u, v;
        public final float r, g, b, a;
        public final float nx, ny, nz;
        public final int brightness;

        public CapturedVertex(double x, double y, double z, double u, double v,
                              float r, float g, float b, float a,
                              float nx, float ny, float nz, int brightness) {
            this.x = x; this.y = y; this.z = z;
            this.u = u; this.v = v;
            this.r = r; this.g = g; this.b = b; this.a = a;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.brightness = brightness;
        }
    }

    /**
     * A captured quad (4 vertices) with its associated texture name and the {@link GL11}
     * render state in effect when it was drawn, so a capture renderer can pick the matching
     * RenderType (opaque / blended / additive, untextured, full-bright).
     */
    public static class CapturedQuad {
        public final CapturedVertex[] vertices = new CapturedVertex[4];
        /** The texture/icon name for this quad (from the Icon passed to the face method). */
        public String textureName;
        /** GL_TEXTURE_2D was enabled on the diffuse texture unit. */
        public boolean textured = true;
        /** GL_BLEND was enabled; blendSrc/blendDst are the glBlendFunc factors. */
        public boolean blend;
        public int blendSrc = GL11.GL_SRC_ALPHA;
        public int blendDst = GL11.GL_ONE_MINUS_SRC_ALPHA;
        public boolean alphaTest = true;
        public boolean lighting = true;
        /**
         * Light set by OpenGlHelper.setLightmapTextureCoords during this capture (packed
         * block | sky << 16, full-bright while the lightmap unit's texture is off), or -1 to
         * use the host renderer's light.
         */
        public int lightmap = -1;
        /** GL_TEXTURE matrix translation (1.5.2 RenderCreeper/RenderWither swirl). */
        public float texOffsetU, texOffsetV;
    }

    // ================================================================
    // Capture control
    // ================================================================

    /**
     * Enable vertex capture mode. Clears any previously captured data, starts a fresh draw
     * (no explicit colour/normal/brightness) and resets the thread's {@link GL11} colour and
     * render state to 1.5.2's per-object defaults (RenderManager.java:203-204,
     * TileEntityRenderer.java:154-155), so nothing a previous capture left behind leaks in.
     *
     * <p>The translation is cleared too. 1.5.2 renderers pair setTranslation/addTranslation with an
     * undo only on their success path (RenderFallingSand, RenderBlocks' inset faces, FC's basket
     * TESR); one that throws in between would otherwise displace every later capture on this
     * thread. Callers that need an offset (FCBakedModel) set it after this call.</p>
     */
    public void startCapturing() {
        capturing = true;
        capturedQuads.clear();
        currentVertices.clear();
        currentTextureName = null;
        drawMode = GL11.GL_QUADS;
        transX = 0; transY = 0; transZ = 0;
        resetDrawFlags();
        GL11.resetCaptureState();
    }

    /** Disable capture mode and return captured quads. */
    public List<CapturedQuad> stopCapturing() {
        capturing = false;
        List<CapturedQuad> result = new ArrayList<>(capturedQuads);
        capturedQuads.clear();
        currentVertices.clear();
        return result;
    }

    /** Whether capture mode is active. */
    public int getCurrentVertexCount() { return currentVertices.size(); }
    public int getCapturedQuadCount() { return capturedQuads.size(); }

    public boolean isCapturing() {
        return capturing;
    }

    // ================================================================
    // Drawing lifecycle
    // ================================================================

    public int draw() {
        currentVertices.clear();
        return 0;
    }

    public void startDrawingQuads() {
        startDrawing(GL11.GL_QUADS);
    }

    /** 1.5.2 Tessellator.startDrawing (Tessellator.java:322-339): new primitive, no per-vertex state yet. */
    public void startDrawing(int drawMode) {
        currentVertices.clear();
        this.drawMode = drawMode;
        resetDrawFlags();
    }

    private void resetDrawFlags() {
        hasColor = false;
        hasNormals = false;
        hasBrightness = false;
        isColorDisabled = false;
    }

    // ================================================================
    // Vertex state
    // ================================================================

    public void setTextureUV(double u, double v) {
        this.curU = u;
        this.curV = v;
    }

    public void setBrightness(int brightness) {
        this.hasBrightness = true;
        this.curBrightness = brightness;
    }

    public void setColorOpaque_F(float r, float g, float b) {
        setColorRGBA_F(r, g, b, 1.0f);
    }

    public void setColorRGBA_F(float r, float g, float b, float a) {
        if (isColorDisabled) return;
        this.hasColor = true;
        this.curR = clamp01(r); this.curG = clamp01(g); this.curB = clamp01(b); this.curA = clamp01(a);
    }

    public void setColorOpaque(int r, int g, int b) {
        setColorRGBA(r, g, b, 255);
    }

    /** 1.5.2 Tessellator.setColorRGBA (Tessellator.java:384-439): ignored after disableColor, clamps 0-255. */
    public void setColorRGBA(int r, int g, int b, int a) {
        if (isColorDisabled) return;
        this.hasColor = true;
        this.curR = Math.min(255, Math.max(0, r)) / 255f;
        this.curG = Math.min(255, Math.max(0, g)) / 255f;
        this.curB = Math.min(255, Math.max(0, b)) / 255f;
        this.curA = Math.min(255, Math.max(0, a)) / 255f;
    }

    public void setColorOpaque_I(int color) {
        setColorOpaque((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF);
    }

    public void setColorRGBA_I(int color, int alpha) {
        setColorRGBA((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, alpha);
    }

    /** 1.5.2 Tessellator.disableColor (Tessellator.java:547-550): later setColor* calls in this draw are ignored. */
    public void disableColor() {
        this.isColorDisabled = true;
    }

    public void setNormal(float x, float y, float z) {
        this.hasNormals = true;
        this.curNX = x; this.curNY = y; this.curNZ = z;
    }

    public void setTranslation(double x, double y, double z) {
        this.transX = x; this.transY = y; this.transZ = z;
    }

    public void addTranslation(float x, float y, float z) {
        this.transX += x; this.transY += y; this.transZ += z;
    }

    /**
     * Sets the texture name to associate with subsequently captured quads.
     * Called by face rendering methods before emitting vertices so that
     * the captured quad knows which sprite to resolve later.
     *
     * <p>"/terrain.png" and "/gui/items.png" were 1.5.2's icon sheets: what a quad on them
     * shows is decided by the Icon whose UVs it uses, and the port's icons report their name
     * here when those UVs are read. 1.5.2 code may read the UVs first and bind the sheet
     * afterwards (RenderItem.renderDroppedItem), so a sheet bind does not replace an icon
     * name already recorded.</p>
     */
    public void setCurrentTextureName(String name) {
        if (isIconSheet(name) && isIconName(this.currentTextureName)) return;
        this.currentTextureName = name;
    }

    private static boolean isIconSheet(String name) {
        return "/terrain.png".equals(name) || "/gui/items.png".equals(name);
    }

    /** A bare Icon name (NamedIcon), as opposed to a texture path such as "/mob/cow.png". */
    private static boolean isIconName(String name) {
        return name != null && !name.isEmpty() && name.indexOf('/') < 0 && !name.endsWith(".png");
    }

    // ================================================================
    // Vertex submission
    // ================================================================

    public void addVertexWithUV(double x, double y, double z, double u, double v) {
        this.curU = u;
        this.curV = v;
        addVertex(x, y, z);
    }

    public void addVertex(double x, double y, double z) {
        if (!capturing) return;

        GL11.State gl = GL11.state();
        double vx = x + transX, vy = y + transY, vz = z + transZ;

        // No setNormal in this draw: GL's current normal (glNormal3f) applies, as in 1.5.2.
        double nx, ny, nz;
        if (hasNormals) {
            nx = curNX; ny = curNY; nz = curNZ;
        } else {
            nx = gl.normalX; ny = gl.normalY; nz = gl.normalZ;
        }

        // Apply GL11 matrix stack if tracking is active (entity/TE rendering)
        if (gl.matrixTrackingEnabled) {
            float[] p = GL11.transformPoint((float) vx, (float) vy, (float) vz);
            vx = p[0]; vy = p[1]; vz = p[2];
            float[] n = GL11.transformNormal((float) nx, (float) ny, (float) nz);
            nx = n[0]; ny = n[1]; nz = n[2];
        }

        // No setColor* in this draw: the colour array is off and GL's current colour applies
        // (1.5.2 Tessellator.java:499-502 / draw() :218-230), i.e. GL11.glColor, white by default.
        float r, g, b, a;
        if (hasColor) {
            r = curR; g = curG; b = curB; a = curA;
        } else {
            r = clamp01(gl.colorR); g = clamp01(gl.colorG); b = clamp01(gl.colorB); a = clamp01(gl.colorA);
        }

        // No setBrightness in this draw: the lightmap multitexcoord (OpenGlHelper) applies.
        int brightness = hasBrightness ? curBrightness : (gl.lightmap >= 0 ? gl.lightmap : GL11.FULL_BRIGHT);

        CapturedVertex vert = new CapturedVertex(
                vx, vy, vz,
                curU, curV,
                r, g, b, a,
                (float) nx, (float) ny, (float) nz,
                brightness
        );
        addToPrimitive(vert);
    }

    /**
     * Assembles vertices of the current GL primitive into quads. GL_QUADS is the normal case.
     * The few non-quad draws 1.5.2 renderers issue are converted: triangle/quad strips
     * (FCClientRenderLightningBolt.java:95 draws its bolt segments as GL_TRIANGLE_STRIP pairs)
     * become one quad per vertex pair, triangles and fans become degenerate quads, and points
     * and lines are dropped because a quad cannot represent them.
     */
    private void addToPrimitive(CapturedVertex vert) {
        switch (drawMode) {
            case GL11.GL_POINTS:
            case GL11.GL_LINES:
            case GL11.GL_LINE_LOOP:
            case GL11.GL_LINE_STRIP:
                return;
            case GL11.GL_TRIANGLES:
                currentVertices.add(vert);
                if (currentVertices.size() == 3) {
                    emitQuad(currentVertices.get(0), currentVertices.get(1), currentVertices.get(2), currentVertices.get(2));
                    currentVertices.clear();
                }
                return;
            case GL11.GL_TRIANGLE_STRIP:
            case GL11.GL_QUAD_STRIP:
                currentVertices.add(vert);
                if (currentVertices.size() == 4) {
                    // pairs (v0,v1) (v2,v3) -> quad v0 v1 v3 v2; the new pair starts the next segment
                    emitQuad(currentVertices.get(0), currentVertices.get(1), currentVertices.get(3), currentVertices.get(2));
                    currentVertices.remove(0);
                    currentVertices.remove(0);
                }
                return;
            case GL11.GL_TRIANGLE_FAN:
            case GL11.GL_POLYGON:
                currentVertices.add(vert);
                if (currentVertices.size() == 3) {
                    emitQuad(currentVertices.get(0), currentVertices.get(1), currentVertices.get(2), currentVertices.get(2));
                    currentVertices.remove(1);
                }
                return;
            case GL11.GL_QUADS:
            default:
                currentVertices.add(vert);
                if (currentVertices.size() == 4) {
                    emitQuad(currentVertices.get(0), currentVertices.get(1), currentVertices.get(2), currentVertices.get(3));
                    currentVertices.clear();
                }
        }
    }

    private void emitQuad(CapturedVertex v0, CapturedVertex v1, CapturedVertex v2, CapturedVertex v3) {
        CapturedQuad quad = new CapturedQuad();
        quad.vertices[0] = v0;
        quad.vertices[1] = v1;
        quad.vertices[2] = v2;
        quad.vertices[3] = v3;
        quad.textureName = currentTextureName;

        // Render state at draw time (GL11 stand-in, this thread)
        GL11.State gl = GL11.state();
        quad.textured = gl.texture2D[0];
        quad.blend = gl.blend;
        quad.blendSrc = gl.blendSrc;
        quad.blendDst = gl.blendDst;
        quad.alphaTest = gl.alphaTest;
        quad.lighting = gl.lighting;
        // Lightmap texture off (1.5.2 RenderLiving.java:192-194) = no lightmap modulation = full-bright.
        quad.lightmap = !gl.texture2D[1] ? GL11.FULL_BRIGHT : gl.lightmap;
        quad.texOffsetU = gl.texOffsetU;
        quad.texOffsetV = gl.texOffsetV;
        capturedQuads.add(quad);
    }

    private static float clamp01(float f) {
        return f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f);
    }
}
