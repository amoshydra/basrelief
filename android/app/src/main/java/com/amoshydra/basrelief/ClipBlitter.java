package com.amoshydra.basrelief;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The bridge from a decoder's {@code Surface} to the textures the relief shaders
 * already sample.
 *
 * <p><b>Three OES surfaces in, three ordinary textures out</b>, and the reason is
 * that {@code GL_TEXTURE_EXTERNAL_OES} cannot be read from a vertex shader on every
 * device, while the depth field has to be. The depth texture is the whole reason
 * the GLES3 vertex shader can displace at all, so depth is blitted into a float
 * target and colour and normals are blitted into 2D targets -- after which the
 * renderer's existing sampling is untouched and no shader had to learn a second
 * sampler type.
 *
 * <p><b>Colour stays on the SurfaceTexture path and never goes through the CPU.</b>
 * {@code ImageReader} to {@code YUV_420_888} and back returned swizzled vendor bytes
 * on this hardware, and a Java YUV to RGB conversion over 983k px was an outright
 * ANR. The sampler does the YUV to RGB inside the blit, which is free because it is
 * a texture fetch rather than arithmetic.
 *
 * <p><b>The depth blur is here, on the GPU, exactly once.</b> It has to happen after
 * the quantisation the codec applies and it has to stay in float: re-quantising the
 * result to 8-bit puts the banding terraces straight back, which is the artifact the
 * blur exists to remove. The CPU equivalent cost 30-42 ms per call, which at 60 fps
 * is two frames, so this is a {@code (2r+1)^2} box in the fragment shader and the
 * field is uploaded as a float texture.
 */
final class ClipBlitter {

    private static final String TAG = "Basrelief";

    /**
     * The blur radius, in <i>field texels</i>.
     *
     * <p>Not in source-video texels: {@code ReliefMesh} resamples to the grid before
     * it blurs, so the field this samples is already the grid and the radius is a
     * fraction of the GRID. It is the same constant the CPU path uses, deliberately
     * -- two blurs of two radii would produce two surfaces that differ only when
     * nothing visibly explains why.
     */
    private final int blurRadius;

    private final String blitVert =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aUV;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  vUv = aUV;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private final String blitFragColour =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uST;\n" +
            "void main() {\n" +
            "  gl_FragColor = vec4(texture2D(uTex, (uST * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0);\n" +
            "}\n";

    /**
     * The box-blur shader, built in the constructor because it is unrolled to
     * {@link #blurRadius}.
     *
     * <p>Not a field initialiser: those run <i>before</i> the constructor body, so
     * {@code blurRadius} would still be 0 and the loop would unroll to a
     * single tap. It compiles, it links, and it blurs by nothing at all — which is
     * the whole class of bug this note exists for.
     */
    private final String blitFragDepth;

    private final String blitFragPlain =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uST;\n" +
            "uniform float uRangeFix;\n" +
            "void main() {\n" +
            "  gl_FragColor = vec4(texture2D(uTex, (uST * vec4(vUv, 0.0, 1.0)).xy).rgb * uRangeFix, 1.0);\n" +
            "}\n";

    private int progColour, progDepth, progPlain;
    private int aPos, aUV;
    private FloatBuffer quadPos, quadUv;
    private int fbo = -1;

    /** One OES source and its 2D destination. */
    static final class Channel {
        final SurfaceTexture st;
        final int tex2d;
        final int fbo;
        final int w, h;
        int oesName;

        Channel(int tex2d, int fbo, int w, int h) {
            this.tex2d = tex2d;
            this.fbo = fbo;
            this.w = w;
            this.h = h;
            this.st = new SurfaceTexture(tex2d);
        }

        /** The {@link android.view.Surface} a decoder can be configured with. */
        android.view.Surface surface() {
            return new android.view.Surface(st);
        }
    }

    /** The GLES3 field target for colour: an ordinary 2D texture at the video's size. */
    Channel colour;
    /** The depth field, as R32F at the GRID -- the exact texture {@code uDepth} wants. */
    Channel depth;
    /** The normal map, as an ordinary 2D texture. Null for a flat-lit clip. */
    Channel normal;

    ClipBlitter(int blurRadius) {
        this.blurRadius = blurRadius;
        this.blitFragDepth =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision highp float;\n" +
                "varying vec2 vUv;\n" +
                "uniform samplerExternalOES uTex;\n" +
                "uniform mat4 uST;\n" +
                "uniform vec2 uTexel;\n" +
                "uniform float uRangeFix;\n" +
                "void main() {\n" +
                // A greyscale video's depth is its LUMA, which is what .r gives after
                // the sampler has converted. Reading .r and not a channel average is
                // the measurement that settled it: the depth encode is a luma encode.
                "  vec2 uv = (uST * vec4(vUv, 0.0, 1.0)).xy;\n" +
                "  float sum = 0.0;\n" +
                "  for (int j = -" + blurRadius + "; j <= " + blurRadius + "; j++) {\n" +
                "    for (int i = -" + blurRadius + "; i <= " + blurRadius + "; i++) {\n" +
                "      sum += texture2D(uTex, uv + vec2(float(i), float(j)) * uTexel).r;\n" +
                "    }\n" +
                "  }\n" +
                // Normalised by the TAP COUNT. A box blur that normalises by anything
                // else changes the depth's mean, which reads as the whole surface
                // sinking rather than as a blur.
                "  gl_FragColor = vec4(vec3(sum * uRangeFix), 1.0);\n" +
                "}\n";
    }

    /** GL thread. Creates the programs and the quad, once per EGL context. */
    void create() {
        progColour = program(blitFragColour);
        progDepth = program(blitFragDepth);
        progPlain = program(blitFragPlain);
        aPos = GLES20.glGetAttribLocation(progColour, "aPos");
        aUV = GLES20.glGetAttribLocation(progColour, "aUV");
        float[] p = {-1, -1, 1, -1, -1, 1, 1, 1};
        float[] t = {0, 0, 1, 0, 0, 1, 1, 1};
        quadPos = floats(p);
        quadUv = floats(t);
    }

    /**
     * One full-screen pass: OES source to 2D destination.
     *
     * <p>{@link #update} first, because the transform matrix is only valid after the
     * frame has been handed over -- reading it before is the classic way to draw a
     * frame rotated by a stale matrix, which looks like a texture orientation bug and
     * is not one.
     */
    void blit(Channel src, int program, float rangeFix, float texelW, float texelH) {
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, src.fbo);
        GLES30.glViewport(0, 0, src.w, src.h);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, src.oesName);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0);

        float[] m = new float[16];
        src.st.getTransformMatrix(m);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, m, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRangeFix"), rangeFix);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTexel"), texelW, texelH);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO);
        drawQuad();
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    private void drawQuad() {
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, quadUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUV);
    }

    /**
     * Hand the newest decoded frame to the GL side.
     *
     * @return false when the frame had to be dropped, which is routine: a decoder can
     *         produce more frames than we draw, and {@code updateTexImage} returns
     *         false for a frame superseded before the GL thread got to it. Dropping it
     *         is correct. Blocking for it would be a stall on a stale frame.
     */
    boolean update(Channel c) {
        c.st.updateTexImage();
        return true;
    }

    /** Allocate an ordinary RGBA8 2D target and its framebuffer. */
    static Channel makeRgba(int w, int h) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, t[0], 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.e(TAG, "rgba fbo incomplete 0x"
                    + Integer.toHexString(status) + " " + w + "x" + h);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return new Channel(t[0], f[0], w, h);
    }

    /**
     * The float field target, {@code n} square — the texture {@code uDepth} samples.
     *
     * <p><b>R32F, never an 8-bit format.</b> This is the invariant the whole blur
     * exists to protect: 8-bit depth has roughly one-level terraces, grazing light
     * amplifies them into visible contour lines, and re-quantising after the blur
     * recreates them exactly. So the field is float end to end.
     */
    static Channel makeField(int n) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F, n, n, 0,
                GLES30.GL_RED, GLES20.GL_FLOAT, null);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, t[0], 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.e(TAG, "field fbo incomplete 0x" + Integer.toHexString(status));
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return new Channel(t[0], f[0], n, n);
    }

    /** Bind an OES texture name to a channel's SurfaceTexture. GL thread. */
    void bindOes(Channel c) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        c.oesName = t[0];
        c.st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture s) {
                // Not latched on purpose. Nothing here blocks or consumes; the draw
                // path calls updateTexImage itself, so there is no queue to fall
                // behind and no wakeup to miss. Latching a frame here would mean the
                // decoder's newest frame waited on a request that may never come.
                available = true;
            }
        });
    }

    /** Set by the frame-available callback, consumed by the draw path. */
    volatile boolean available;

    private int program(String frag) {
        int v = compile(GLES20.GL_VERTEX_SHADER, blitVert);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, frag);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            android.util.Log.e(TAG, "blit link failed: " + GLES20.glGetProgramInfoLog(p));
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            android.util.Log.e(TAG, "blit compile failed: " + GLES20.glGetShaderInfoLog(s));
        }
        return s;
    }

    private static FloatBuffer floats(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
    }
}
