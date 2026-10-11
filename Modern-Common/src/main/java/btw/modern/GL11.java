package btw.modern;

/**
 * GL11 replacement with a functional matrix stack for entity/tile entity rendering.
 *
 * <p>Tracks translate/rotate/scale transforms in a software matrix stack.
 * The Tessellator reads the current matrix via {@link #getMatrix()} and
 * applies it to vertex positions, producing correctly-transformed geometry
 * for FC entity renderers (windmill, water wheel, baskets, etc.).</p>
 *
 * <p>The fixed-function state that changes what a captured quad looks like is
 * tracked too: the current colour and normal (used by vertices that set none,
 * exactly as GL's current colour/normal were in 1.5.2), GL_TEXTURE_2D per texture
 * unit, GL_BLEND + glBlendFunc, GL_ALPHA_TEST, GL_LIGHTING, the lightmap coords
 * from {@link OpenGlHelper#setLightmapTextureCoords} and the GL_TEXTURE matrix
 * translation. {@link Tessellator} snapshots that state onto every captured quad
 * so the Forge capture renderers can pick a matching RenderType. Everything else
 * (depth, culling, filtering) stays a no-op: MC 1.20.1's pipeline owns it.</p>
 *
 * <p><b>Thread-local state:</b> the matrix stack, color, and normal are held
 * per-thread. The {@link Tessellator} is itself thread-local (the render thread
 * captures entity/tile-entity geometry while chunk-builder worker threads capture
 * block-model geometry) and it consults this GL state <i>per vertex</i>. When this
 * state was process-global {@code static}, a render-thread FC TESR that enabled
 * matrix tracking and pushed an animation matrix (e.g. the animated wicker-basket
 * lid) would contaminate a <i>concurrent</i> worker-thread block capture, baking
 * that foreign transform into the block's box vertices — the "scrambled mesh"
 * symptom. Making the state thread-local isolates the two capture pipelines.</p>
 */
public class GL11 {

    // --- Constants (same values as real GL11) ---
    public static final int GL_ZERO = 0;
    public static final int GL_ONE = 1;
    public static final int GL_ALPHA_TEST = 0x0BC0;
    public static final int GL_BLEND = 0x0BE2;
    public static final int GL_COLOR_MATERIAL = 0x0B57;
    public static final int GL_CULL_FACE = 0x0B44;
    public static final int GL_DEPTH_TEST = 0x0B71;
    public static final int GL_LIGHTING = 0x0B50;
    public static final int GL_SRC_COLOR = 0x0300;
    public static final int GL_ONE_MINUS_SRC_COLOR = 0x0301;
    public static final int GL_SRC_ALPHA = 0x0302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_DST_ALPHA = 0x0304;
    public static final int GL_ONE_MINUS_DST_ALPHA = 0x0305;
    public static final int GL_DST_COLOR = 0x0306;
    public static final int GL_ONE_MINUS_DST_COLOR = 0x0307;
    public static final int GL_NEVER = 0x0200;
    public static final int GL_LESS = 0x0201;
    public static final int GL_EQUAL = 0x0202;
    public static final int GL_LEQUAL = 0x0203;
    public static final int GL_GREATER = 0x0204;
    public static final int GL_ALWAYS = 0x0207;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_SMOOTH = 0x1D01;
    public static final int GL_FLAT = 0x1D00;
    public static final int GL_FRONT_AND_BACK = 0x0408;
    public static final int GL_AMBIENT_AND_DIFFUSE = 0x1602;
    public static final int GL_MODELVIEW = 0x1700;
    public static final int GL_PROJECTION = 0x1701;
    public static final int GL_TEXTURE = 0x1702;
    public static final int GL_POINTS = 0x0000;
    public static final int GL_LINES = 0x0001;
    public static final int GL_LINE_LOOP = 0x0002;
    public static final int GL_LINE_STRIP = 0x0003;
    public static final int GL_TRIANGLES = 0x0004;
    public static final int GL_TRIANGLE_STRIP = 0x0005;
    public static final int GL_TRIANGLE_FAN = 0x0006;
    public static final int GL_QUADS = 0x0007;
    public static final int GL_QUAD_STRIP = 0x0008;
    public static final int GL_POLYGON = 0x0009;
    public static final int GL_NORMALIZE = 0x0BA1;
    public static final int GL_RESCALE_NORMAL = 0x803A;
    public static final int GL_POLYGON_OFFSET_FILL = 0x8037;
    public static final int GL_FOG = 0x0B60;

    /** 1.20.1 LightTexture.FULL_BRIGHT (block 15, sky 15), packed like 1.5.2 getBrightnessForRender. */
    static final int FULL_BRIGHT = 0xF000F0;

    // ================================================================
    // Software matrix stack (thread-local — see class javadoc)
    // ================================================================

    /** 4x4 matrix stored as float[16] in column-major order (OpenGL convention). */
    private static final int STACK_DEPTH = 32;

    /**
     * Per-thread GL emulation state (matrix stack, tracking flag, color, normal and the
     * render state snapshotted onto captured quads). Package-private so {@link Tessellator}
     * can read it per vertex without allocating.
     */
    static final class State {
        final float[][] matrixStack = new float[STACK_DEPTH][16];
        int stackPointer = 0;
        boolean matrixTrackingEnabled = false;
        /** glMatrixMode: only GL_MODELVIEW ops move geometry; GL_TEXTURE translation is recorded. */
        int matrixMode = GL_MODELVIEW;
        /** Set when the pre-port RenderLiving stand-in's glScalef(1,-1,-1) model flip ran. */
        boolean legacyEntityFlip = false;
        float colorR = 1, colorG = 1, colorB = 1, colorA = 1;
        float normalX = 0, normalY = 1, normalZ = 0;
        /** 0 = OpenGlHelper.defaultTexUnit (diffuse texture), 1 = lightmapTexUnit. */
        int activeTextureUnit = 0;
        final boolean[] texture2D = {true, true};
        boolean blend = false;
        int blendSrc = GL_SRC_ALPHA, blendDst = GL_ONE_MINUS_SRC_ALPHA;
        boolean alphaTest = true;
        boolean lighting = true;
        /** Lightmap coords from setLightmapTextureCoords, packed block | sky << 16; -1 = host light. */
        int lightmap = -1;
        /** GL_TEXTURE matrix translation (1.5.2 RenderCreeper/RenderWither power swirl). */
        float texOffsetU = 0, texOffsetV = 0;

        State() {
            loadIdentity(matrixStack[0]);
        }

        /**
         * The state 1.5.2 had when it dispatched one entity / tile entity: RenderManager.renderEntity
         * (RenderManager.java:203-204) and TileEntityRenderer.renderTileEntity
         * (TileEntityRenderer.java:154-155) re-set the lightmap and glColor4f(1,1,1,1) per object,
         * with texturing, alpha test and lighting on and blending off (RenderGlobal's entity pass).
         * Here the lightmap goes back to "host light" (-1). The matrix stack and tracking flag are
         * NOT touched.
         */
        void resetRenderState() {
            colorR = colorG = colorB = colorA = 1;
            normalX = 0; normalY = 1; normalZ = 0;
            matrixMode = GL_MODELVIEW;
            activeTextureUnit = 0;
            texture2D[0] = true;
            texture2D[1] = true;
            blend = false;
            blendSrc = GL_SRC_ALPHA;
            blendDst = GL_ONE_MINUS_SRC_ALPHA;
            alphaTest = true;
            lighting = true;
            lightmap = -1;
            texOffsetU = 0;
            texOffsetV = 0;
        }
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private static State s() {
        return STATE.get();
    }

    /** This thread's GL state, for {@link Tessellator}'s per-vertex / per-quad snapshot. */
    static State state() {
        return STATE.get();
    }

    /** Enable matrix tracking for entity rendering capture (this thread only). */
    public static void enableMatrixTracking() {
        State st = s();
        st.matrixTrackingEnabled = true;
        st.stackPointer = 0;
        st.matrixMode = GL_MODELVIEW;
        st.legacyEntityFlip = false;
        loadIdentity(st.matrixStack[0]);
    }

    /** Disable matrix tracking (returns to no-op mode for block rendering). */
    public static void disableMatrixTracking() {
        s().matrixTrackingEnabled = false;
    }

    public static boolean isMatrixTrackingEnabled() {
        return s().matrixTrackingEnabled;
    }

    /**
     * Resets this thread's colour/normal/blend/texture/lightmap state to the per-object defaults
     * (see {@link State#resetRenderState}). {@link Tessellator#startCapturing} calls this, so every
     * capture starts clean and no colour left by a previous capture can leak into it.
     */
    public static void resetCaptureState() {
        s().resetRenderState();
    }

    /**
     * True if, since the last {@link #enableMatrixTracking}, this thread ran the model flip of the
     * pre-port RenderLiving stand-in, {@code glScalef(1,-1,-1)}. That flip is 1.5.2's
     * {@code glScalef(-1,-1,1)} (RenderLiving.java:107) turned 180 degrees about Y, which
     * FCEntityRenderer has to undo. No 1.5.2 entity renderer issues it.
     */
    public static boolean usedLegacyEntityFlip() {
        return s().legacyEntityFlip;
    }

    /** Returns a copy of the current 4x4 matrix (column-major). */
    public static float[] getMatrix() {
        State st = s();
        return st.matrixStack[st.stackPointer].clone();
    }

    /** Transforms a point (x,y,z) by the current matrix. Returns {x',y',z'}. */
    public static float[] transformPoint(float x, float y, float z) {
        State st = s();
        float[] m = st.matrixStack[st.stackPointer];
        float rx = m[0]*x + m[4]*y + m[8]*z  + m[12];
        float ry = m[1]*x + m[5]*y + m[9]*z  + m[13];
        float rz = m[2]*x + m[6]*y + m[10]*z + m[14];
        return new float[]{rx, ry, rz};
    }

    /** Returns true if the current matrix has a negative determinant (odd number of axis flips). */
    public static boolean hasNegativeDeterminant() {
        State st = s();
        float[] m = st.matrixStack[st.stackPointer];
        // 3x3 upper-left determinant (rotation+scale part)
        float det = m[0] * (m[5] * m[10] - m[6] * m[9])
                  - m[4] * (m[1] * m[10] - m[2] * m[9])
                  + m[8] * (m[1] * m[6]  - m[2] * m[5]);
        return det < 0;
    }

    /** Transforms a normal (x,y,z) by the current matrix (no translation). */
    public static float[] transformNormal(float nx, float ny, float nz) {
        State st = s();
        float[] m = st.matrixStack[st.stackPointer];
        float rx = m[0]*nx + m[4]*ny + m[8]*nz;
        float ry = m[1]*nx + m[5]*ny + m[9]*nz;
        float rz = m[2]*nx + m[6]*ny + m[10]*nz;
        float len = (float) Math.sqrt(rx*rx + ry*ry + rz*rz);
        if (len > 0.0001f) { rx /= len; ry /= len; rz /= len; }
        return new float[]{rx, ry, rz};
    }

    // --- Matrix operations ---
    // Only GL_MODELVIEW moves geometry. 1.5.2 RenderCreeper/RenderWither (power swirl) and the
    // RenderLiving glint pass switch to GL_TEXTURE and call glLoadIdentity/glTranslatef there;
    // applying those to the modelview stack would wipe the entity's transform.

    public static void glPushMatrix() {
        State st = s();
        if (!st.matrixTrackingEnabled || st.matrixMode != GL_MODELVIEW) return;
        if (st.stackPointer < STACK_DEPTH - 1) {
            System.arraycopy(st.matrixStack[st.stackPointer], 0, st.matrixStack[st.stackPointer + 1], 0, 16);
            st.stackPointer++;
        }
    }

    public static void glPopMatrix() {
        State st = s();
        if (!st.matrixTrackingEnabled || st.matrixMode != GL_MODELVIEW) return;
        if (st.stackPointer > 0) st.stackPointer--;
    }

    public static void glTranslatef(float x, float y, float z) {
        State st = s();
        if (st.matrixMode == GL_TEXTURE) {
            st.texOffsetU += x;
            st.texOffsetV += y;
            return;
        }
        if (!st.matrixTrackingEnabled || st.matrixMode != GL_MODELVIEW) return;
        float[] m = st.matrixStack[st.stackPointer];
        m[12] += m[0]*x + m[4]*y + m[8]*z;
        m[13] += m[1]*x + m[5]*y + m[9]*z;
        m[14] += m[2]*x + m[6]*y + m[10]*z;
    }

    public static void glTranslated(double x, double y, double z) {
        glTranslatef((float)x, (float)y, (float)z);
    }

    public static void glRotatef(float angle, float ax, float ay, float az) {
        State st = s();
        if (!st.matrixTrackingEnabled || st.matrixMode != GL_MODELVIEW) return;
        float rad = (float) Math.toRadians(angle);
        float c = (float) Math.cos(rad);
        float s2 = (float) Math.sin(rad);
        float len = (float) Math.sqrt(ax*ax + ay*ay + az*az);
        if (len < 0.0001f) return;
        ax /= len; ay /= len; az /= len;
        float t = 1 - c;

        float[] r = new float[16];
        r[0]  = t*ax*ax + c;      r[4]  = t*ax*ay - s2*az;  r[8]  = t*ax*az + s2*ay;  r[12] = 0;
        r[1]  = t*ax*ay + s2*az;  r[5]  = t*ay*ay + c;      r[9]  = t*ay*az - s2*ax;  r[13] = 0;
        r[2]  = t*ax*az - s2*ay;  r[6]  = t*ay*az + s2*ax;  r[10] = t*az*az + c;      r[14] = 0;
        r[3]  = 0;                 r[7]  = 0;                r[11] = 0;                r[15] = 1;

        multiplyMatrix(st.matrixStack[st.stackPointer], r);
    }

    public static void glScalef(float sx, float sy, float sz) {
        State st = s();
        if (!st.matrixTrackingEnabled || st.matrixMode != GL_MODELVIEW) return;
        if (sx == 1.0F && sy == -1.0F && sz == -1.0F) st.legacyEntityFlip = true;
        float[] m = st.matrixStack[st.stackPointer];
        m[0] *= sx; m[1] *= sx; m[2] *= sx;
        m[4] *= sy; m[5] *= sy; m[6] *= sy;
        m[8] *= sz; m[9] *= sz; m[10] *= sz;
    }

    public static void glScaled(double x, double y, double z) {
        glScalef((float)x, (float)y, (float)z);
    }

    public static void glLoadIdentity() {
        State st = s();
        if (st.matrixMode == GL_TEXTURE) {
            st.texOffsetU = 0;
            st.texOffsetV = 0;
            return;
        }
        if (!st.matrixTrackingEnabled || st.matrixMode != GL_MODELVIEW) return;
        loadIdentity(st.matrixStack[st.stackPointer]);
    }

    public static void glMatrixMode(int mode) {
        s().matrixMode = mode;
    }

    public static void glMultMatrixf(java.nio.FloatBuffer m) {}
    public static void glOrtho(double l, double r, double b, double t, double n, double f) {}

    // --- State enable/disable (tracked caps feed the per-quad render state) ---
    public static void glEnable(int cap) {
        setCap(cap, true);
    }

    public static void glDisable(int cap) {
        setCap(cap, false);
    }

    public static boolean glIsEnabled(int cap) {
        State st = s();
        switch (cap) {
            case GL_TEXTURE_2D: return st.texture2D[st.activeTextureUnit];
            case GL_BLEND: return st.blend;
            case GL_ALPHA_TEST: return st.alphaTest;
            case GL_LIGHTING: return st.lighting;
            default: return false;
        }
    }

    private static void setCap(int cap, boolean on) {
        State st = s();
        switch (cap) {
            // 1.5.2 RenderLiving.java:192-194/:251-253 toggles GL_TEXTURE_2D on the lightmap unit
            // (OpenGlHelper.setActiveTexture), so the cap is per texture unit.
            case GL_TEXTURE_2D: st.texture2D[st.activeTextureUnit] = on; break;
            case GL_BLEND: st.blend = on; break;
            case GL_ALPHA_TEST: st.alphaTest = on; break;
            case GL_LIGHTING: st.lighting = on; break;
            default: break; // depth, culling, normalize, fog...: owned by MC's pipeline
        }
    }

    /** OpenGlHelper.setActiveTexture: 0 = default (diffuse) unit, 1 = lightmap unit. */
    public static void setActiveTextureUnit(int unit) {
        s().activeTextureUnit = unit == 1 ? 1 : 0;
    }

    /**
     * OpenGlHelper.setLightmapTextureCoords(lightmapTexUnit, s, t): 1.5.2 passes
     * getBrightnessForRender split into block (s) and sky (t), 0..240 each. The 1.5.2 lightmap
     * texture clamps out-of-range coords to its edge, so the eyes passes' s = 61680
     * (RenderSpider.java:60-63, RenderEnderman.java:109-112) means block light 15.
     */
    public static void setLightmapCoords(float blockCoord, float skyCoord) {
        int block = Math.max(0, Math.min(240, (int) blockCoord));
        int sky = Math.max(0, Math.min(240, (int) skyCoord));
        s().lightmap = block | sky << 16;
    }

    // --- Color (tracked: the colour of vertices that set none, see Tessellator) ---
    public static void glColor3f(float r, float g, float b) {
        State st = s();
        st.colorR = r; st.colorG = g; st.colorB = b; st.colorA = 1;
    }
    public static void glColor4f(float r, float g, float b, float a) {
        State st = s();
        st.colorR = r; st.colorG = g; st.colorB = b; st.colorA = a;
    }
    public static float[] getColor() {
        State st = s();
        return new float[]{st.colorR, st.colorG, st.colorB, st.colorA};
    }

    public static void glColorMaterial(int face, int mode) {}

    // --- Depth / alpha func / masks (no-op: MC's RenderType owns them) ---
    public static void glDepthMask(boolean flag) {}
    public static void glDepthFunc(int func) {}
    public static void glAlphaFunc(int func, float ref) {}
    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) {}
    public static void glPolygonOffset(float factor, float units) {}

    // --- Normal (tracked) ---
    public static void glNormal3f(float x, float y, float z) {
        State st = s();
        st.normalX = x; st.normalY = y; st.normalZ = z;
    }
    public static float[] getNormal() {
        State st = s();
        return new float[]{st.normalX, st.normalY, st.normalZ};
    }

    // --- Blending (tracked) ---
    public static void glBlendFunc(int sfactor, int dfactor) {
        State st = s();
        st.blendSrc = sfactor;
        st.blendDst = dfactor;
    }

    // --- Texture (no-op) ---
    public static void glBindTexture(int target, int texture) {}
    public static void glTexParameteri(int target, int pname, int param) {}

    // --- Misc (no-op) ---
    public static void glShadeModel(int mode) {}
    public static void glLineWidth(float width) {}
    public static void glClearColor(float r, float g, float b, float a) {}
    public static void glClear(int mask) {}
    public static void glViewport(int x, int y, int w, int h) {}
    public static void glFlush() {}

    // --- Vertex immediate mode (no-op — Tessellator handles this) ---
    public static void glBegin(int mode) {}
    public static void glEnd() {}
    public static void glVertex3f(float x, float y, float z) {}
    public static void glVertex3d(double x, double y, double z) {}
    public static void glTexCoord2f(float u, float v) {}

    // ================================================================
    // Internal matrix utilities
    // ================================================================

    private static void loadIdentity(float[] m) {
        java.util.Arrays.fill(m, 0);
        m[0] = m[5] = m[10] = m[15] = 1;
    }

    /** result = a * b, stored in a */
    private static void multiplyMatrix(float[] a, float[] b) {
        float[] tmp = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                tmp[col * 4 + row] =
                        a[0*4+row]*b[col*4+0] + a[1*4+row]*b[col*4+1] +
                        a[2*4+row]*b[col*4+2] + a[3*4+row]*b[col*4+3];
            }
        }
        System.arraycopy(tmp, 0, a, 0, 16);
    }
}
