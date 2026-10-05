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
            // The range fix in the 0..1 units THIS shader works in. It was missing
            // entirely, so the uniform went to location -1 and was silently dropped --
            // which is why the colour looked fine while the normal, which declares the
            // same uniform, went black from an offset meant for 0..255.
            "uniform vec2 uRangeFix;\n" +
            "void main() {\n" +
            "  vec3 c = texture2D(uTex, (uST * vec4(vUv, 0.0, 1.0)).xy).rgb;\n" +
            "  gl_FragColor = vec4(c * uRangeFix.x + uRangeFix.y, 1.0);\n" +
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
            "uniform vec2 uRangeFix;\n" +
            "void main() {\n" +
            // 0..1 units, like the colour: the offset must be -16/219 here, not the
            // -16*(255/219) the DEPTH shader uses, because that one scales to 0..255
            // first. Passing the 0..255 offset here made every normal pixel negative
            // and clamped it to black -- reported as "normal is completely black".
            "  gl_FragColor = vec4(texture2D(uTex, (uST * vec4(vUv, 0.0, 1.0)).xy).rgb * uRangeFix.x + uRangeFix.y, 1.0);\n" +
            "}\n";

    /**
     * The three blit programs, named by what they DO rather than left as ints.
     *
     * <p>{@link ClipPlayer} picks one per channel, and a bare int would let it pick
     * the depth box for colour -- which compiles, links, and blurs a colour frame
     * into a grey one with no error anywhere.
     */
    static final int COPY = 0, BLUR_DEPTH = 1, COPY_SCALED = 2;
    /**
     * Bumped by the renderer on every new EGL context, so a release can tell whether
     * the names it holds belong to the current context. Deleting a name in a context
     * that did not create it can delete something else's texture or program.
     */
    static int contextGeneration;

    private int myGeneration = -1;
    private int progColour, progDepth, progPlain;

    int program(int which) {
        return which == BLUR_DEPTH ? progDepth : which == COPY_SCALED ? progPlain : progColour;
    }
    private int aPos, aUV;
    private FloatBuffer quadPos, quadUv;
    private int fbo = -1;

    /**
     * One OES source and its 2D destination.
     *
     * <p><b>The SurfaceTexture is built on the OES texture's name, never on the 2D
     * one's.</b> That is the fault the whole clip was hiding behind: an external image
     * cannot be written into a {@code GL_TEXTURE_2D}, so {@code updateTexImage} throws
     * {@code "Error during updateTexImage"} and the producer and the sampler sit on
     * different textures. It stayed invisible for as long as a guard skipped the call
     * that reports it -- which is the worst way for this class of bug to hide, because
     * removing the guard appears to fix the freeze and reveals the actual fault
     * underneath.
     *
     * <p>The two names are therefore created together, OES first, then the
     * SurfaceTexture over it, then the 2D target the blit writes into.
     */
    static final class Channel {
        final SurfaceTexture st;
        final int tex2d;
        final int fbo;
        final int w, h;
        final int oesName;

        Channel(int oesName, int tex2d, int fbo, int w, int h) {
            this.oesName = oesName;
            this.tex2d = tex2d;
            this.fbo = fbo;
            this.w = w;
            this.h = h;
            this.st = new SurfaceTexture(oesName);
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
        // The tap count of the box below, as a literal, so the normalisation cannot
        // drift from the loop it is dividing. Two copies of one number is the thing
        // that goes stale; one number written into both at construction is not.
        final String TAPS = String.valueOf((2 * blurRadius + 1) * (2 * blurRadius + 1));
        this.blitFragDepth =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision highp float;\n" +
                "varying vec2 vUv;\n" +
                "uniform samplerExternalOES uTex;\n" +
                "uniform mat4 uST;\n" +
                "uniform vec2 uTexel;\n" +
                "uniform vec2 uRangeFix;\n" +
                "void main() {\n" +
                // A greyscale video's depth is its LUMA, which is what .r gives after
                // the sampler has converted. Reading .r and not a channel average is
                // the measurement that settled it: the depth encode is a luma encode.
                "  vec2 uv = (uST * vec4(vUv, 0.0, 1.0)).xy;\n" +
            // UN-FLIP the field's vertical axis. The mesh reads this texture with
            // gy = (1 - aUV.y) * grid -- the STILL path's convention, where the field's
            // row 0 is the image's TOP row (ReliefMesh: "top row = gy 0", uploaded
            // without a flip). The SurfaceTexture's transform includes Android's
            // bottom-up flip, so sampling the OES straight through uST writes the
            // image's BOTTOM row at field row 0 -- the depth vertically mirrored
            // against the video, protrusions appearing where the scene recedes. The
            // colour is unaffected because the still path flips the colour bitmap at
            // upload: colour and clip colour agree; only the field needed the inverse.
            "  uv.y = 1.0 - uv.y;\n" +
                "  float sum = 0.0;\n" +
                "  for (int j = -" + blurRadius + "; j <= " + blurRadius + "; j++) {\n" +
                "    for (int i = -" + blurRadius + "; i <= " + blurRadius + "; i++) {\n" +
                "      sum += texture2D(uTex, uv + vec2(float(i), float(j)) * uTexel).r;\n" +
                "    }\n" +
                "  }\n" +
                // Normalised by the TAP COUNT. A box blur that normalises by anything
                // else changes the depth's mean, which reads as the whole surface
                // sinking rather than as a blur.
                // Normalise FIRST, by the tap count, then expand the range.
                //
                // The order matters and getting it wrong is invisible: without the
                // divide, the field is the sum of N taps, so the relief is N times too
                // deep and the far plane is off the top of the camera. It looks like
                // "depth is too strong" rather than like a missing divide.
                //
                // The expansion is needed because the server writes LIMITED range:
                // measured on a real generated clip, `color_range=tv`, luma 16-235,
                // while the vertex shader reads `/255.0 - 0.5` and so assumes 0-255.
                // Taking a tv-range luma at face value loses 16/255 at the bottom and
                // puts the surface high -- a uniformly shallower, floated relief with
                // no error anywhere. VHS_VideoCombine has no pix_fmt control, so this
                // cannot be fixed at the source and is done in the shader, from the
                // range the DECODER reported.
                "  float mean = sum / " + TAPS + ".0;\n" +
                // The temporal low-pass. MoGe estimates each frame's depth
                // INDEPENDENTLY, so per-frame errors shimmer frame to frame -- the
                // flicker reported on the aligned clip once sync and length were
                // fixed and nothing else moved any more. Mixing the PREVIOUS field
                // with this one at uBlend removes the shimmer; the lag it adds is the
                // price, and uBlend set near 1 keeps it under a frame or two.
                // To the VERTEX shader's units: it unpacks the field as
                // `field / 255.0 - 0.5`, and the CPU path feeds it 0..255 -- but the
                // OES sampler above hands this shader 0..1. Writing 0..1 into the
                // field made every clip draw as a nearly flat plane a half-unit deep:
                // the Depth slider multiplied a near-constant and did nothing, which
                // is how it was reported. The scale 255 belongs to the vertical range
                // fix's units, so it is applied before the fix and the shader's
                // /255 unpack returns exactly what the still path produces.
                "  float cur255 = mean * 255.0;\n" +
                "  float cur = cur255 * uRangeFix.x + uRangeFix.y;\n" +
                "  gl_FragColor = vec4(vec3(cur), 1.0);\n" +
                "}\n";
    }

    /** GL thread. Creates the programs and the quad, once per EGL context. */
    void create() {
        myGeneration = contextGeneration;
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
    void blit(Channel src, int program, float rangeScale, float rangeOffset,
              float texelW, float texelH) {
        blit(src, program, rangeScale, rangeOffset, texelW, texelH, 0, 1f);
    }

    /**
     * One full-screen pass, with an optional previous-frame texture for the depth
     * field's temporal blend.
     *
     * @param prevTex a {@code GL_TEXTURE_2D} to mix into the output, or 0 for none
     * @param blend   the mix weight for the CURRENT frame; 1 when there is no prev
     */
    void blit(Channel src, int program, float rangeScale, float rangeOffset,
              float texelW, float texelH, int prevTex, float blend) {
        // Save the viewport and put it back, because this pass runs at the clip's own
        // size and the relief draw does not set a viewport per frame.
        //
        // THE SPIKE set an explicit viewport before every single pass, so nothing
        // ever inherited anything. This blit set (0, 0, w, h) for its own target and
        // restored only the FRAMEBUFFER, leaving the relief draw clamped into a
        // 720x1366 window at the origin -- the picture rendered correctly but squeezed
        // into the bottom-left quarter of the screen, which is exactly how it was
        // reported. One leak, visible on every frame with a clip on it.
        int[] vp = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0);
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, src.fbo);
        GLES30.glViewport(0, 0, src.w, src.h);
        GLES20.glUseProgram(program);
        // Clear the sticky error left by anything earlier this frame, so the one we
        // report after the draw actually belongs to THIS pass.
        GLES20.glGetError();
        curApos = GLES20.glGetAttribLocation(program, "aPos");
        curAuv = GLES20.glGetAttribLocation(program, "aUV");
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, src.oesName);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0);

        float[] m = new float[16];
        src.st.getTransformMatrix(m);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, m, 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRangeFix"),
                rangeScale, rangeOffset);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTexel"), texelW, texelH);
        if (prevTex > 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPrev"), 1);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBlend"), blend);
        }


        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // NO blending, deliberately. These passes are full-coverage overwrites, so
        // the blend unit does nothing here even where it works -- and where the
        // target is a 32-bit float, blending needs EXT_float_blend and silently
        // mis-writes without it. The passes do not need it, so they do not ask.
        drawQuad();
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        if (program == progDepth) {
            // The depth pass's own GL error. It was 0x0502 -- INVALID_OPERATION at the
            // DRAW -- while the colour pass, the same drawQuad, drew fine: the depth
            // shader's second sampler (a sampler2D over an R32F texture, beside the
            // OES one) was the only state it added, and this driver dropped the draw
            // for it. The temporal blend goes with it until the blend is a separate
            // pass; without this check the drop was invisible and read as depth not
            // working.
            int werr = GLES20.glGetError();
            blitWriteError = werr == 0 ? null : String.format("0x%04x", werr);
        }
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        // The viewport is part of this pass's borrow, not global state: restore it.
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3]);
    }

    /**
     * Attribute locations for the program that is about to be drawn, queried PER
     * PASS rather than cached from one program.
     *
     * <p>They were cached from {@code progColour} once, and locations are assigned
     * PER PROGRAM: {@code progDepth}'s could land at different indices, so the depth
     * pass enabled the wrong slots and drew nothing -- the field FBO stayed at its
     * initial zeros, the vertex shader read a uniform depth everywhere, and the clip
     * rendered as a flat plane whose Depth slider multiplied a constant. FIELD
     * CENTRE reads measured the gap: colour worked, depth read 0.0 every sample.
     * The colour pass looked fine precisely because it came from the chosen cache.
     *
     * <p>Queried per pass, no cache: two glGetAttribLocation calls per blit, three
     * blits a frame, and the alternative is another silent mismatch the next time
     * this shader set changes.
     */
    private int curApos, curAuv;

    private void drawQuad() {
        // BUFFER 0 or the client-side pointers below are offsets into whatever VBO
        // the relief draw left bound, and the quad collapses to a point. The relief
        // path draws AFTER this one and binds gridVbo every frame, so the leaked
        // state is the normal state of the frame we are called from.
        GLES20.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(curApos);
        GLES20.glEnableVertexAttribArray(curAuv);
        GLES20.glVertexAttribPointer(curApos, 2, GLES20.GL_FLOAT, false, 0, quadPos);
        GLES20.glVertexAttribPointer(curAuv, 2, GLES20.GL_FLOAT, false, 0, quadUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(curApos);
        GLES20.glDisableVertexAttribArray(curAuv);
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
        // "Is there a NEW frame", by timestamp delta rather than by the call's return
        // value -- same answer, and it does not depend on a signature that has moved
        // between SDK levels.
        //
        // The timestamp is what LATCHING sets, so comparing it either side of the call
        // is exactly "did that latch something". The distinction matters: a decoder can
        // produce more frames than we draw, and re-blitting the same content costs a
        // full-screen pass for an identical result.
        long before = c.st.getTimestamp();
        c.st.updateTexImage();
        return c.st.getTimestamp() != before;
    }

    /**
     * An external-OES texture for a producer to write into.
     *
     * <p>Must exist and must be the name the {@link SurfaceTexture} is constructed
     * with. It is the SOURCE; the 2D texture beside it is only where the blit puts
     * the result for the relief shader to sample.
     */
    private static int makeOes() {
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
        return t[0];
    }

    /** Allocate an ordinary RGBA8 2D target and its framebuffer. */
    static Channel makeRgba(int w, int h) {
        int oes = makeOes();
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
        return new Channel(oes, t[0], f[0], w, h);
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
        int oes = makeOes();
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        // NEAREST, both filters, exactly like the still path's uploadField: an R32F
        // texture is not filterable without the float-linear extension, and a
        // GL_LINEAR float texture is INCOMPLETE -- the sampler returns a constant
        // wherever it is read. The vertex shader reads this texture as uDepth, so
        // LINEAR here made every clip draw as the constant flat plane 0.5 units
        // deep: h did not vary, and the Depth slider multiplied a constant, which is
        // precisely "changing depth does nothing". The still path was immune because
        // uploadField has said NEAREST since it was written. Nothing is filtered that
        // needs it: the field is sampled one texel per mesh vertex.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
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
        return new Channel(oes, t[0], f[0], n, n);
    }

    /**
     * Listen for a frame arriving. GL thread.
     *
     * <p><b>Nothing is consumed here.</b> The draw path calls
     * {@code updateTexImage} itself, so there is no queue to fall behind and no wakeup
     * to miss; latching from the listener would mean the newest frame waited on a
     * request that may never come.
     *
     * <p>It used to ALSO create the OES texture here, which is the bug: a name created
     * here was never the name any SurfaceTexture was constructed with.
     */
    void bindOes(Channel c) {
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

    /**
     * One field texel, centre, read back — so the field's content is MEASURED rather
     * than assumed. The temporal blend made the field worth observing; an incomplete
     * target or a wrong write reads as a constant here, and a constant field is the
     * symptom the user reports as depth not working. GL_RED/GL_FLOAT for an R32F
     * target; a failed read reads back zeros, which is itself the finding.
     */
    /** "value, GL error" for the centre readback; err 0x0501 = the read was refused. */
    private String blitWriteError;
    private String fboState;

    String fieldCentreSample() {
        if (depth == null) return "no field";
        java.nio.ByteBuffer one = java.nio.ByteBuffer.allocateDirect(4 * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, depth.fbo);
        GLES30.glReadPixels(depth.w / 2, depth.h / 2, 1, 1, GLES30.GL_RGBA,
                GLES30.GL_FLOAT, one);
        int err = GLES20.glGetError();
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        // RGBA+float: four floats, r then zeros. A wrong format combo returns zeros
        // AND an error; the value with err==0 is evidence, the value alone is not --
        // 'GL_RED/GL_FLOAT leaves all zeros' is exactly the trap a failing readback
        // and a failing WRITE share.
        return String.format("v=%.4f err=0x%04x blitErr=%s", one.getFloat(0), err,
                blitWriteError == null ? "0" : blitWriteError);
    }

    /** RGB sum at the normal target's centre; 0 means black, which is the report. */
    String normalCentreSample() {
        if (normal == null) return "none";
        java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(4);
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, normal.fbo);
        GLES20.glReadPixels(normal.w / 2, normal.h / 2, 1, 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        int err = GLES20.glGetError();
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return (px.get(0) & 0xFF) + "," + (px.get(1) & 0xFF) + "," + (px.get(2) & 0xFF)
                + " e=0x" + Integer.toHexString(err);
    }

    /**
     * Delete the programs, if this blitter's names still belong to this context.
     *
     * <p>They were compiled on every clip START and never deleted: a leak, and a
     * shader recompile on every clip switch. The generation guard is the reason this
     * is safe to call from stop(), which also runs on a brand-new context via
     * onSurfaceCreated -- where the old names mean nothing and deleting them would
     * be deleting a stranger.
     */
    void releasePrograms() {
        if (myGeneration != contextGeneration) return;
        int[] p = {progColour, progDepth, progPlain};
        for (int id : p) if (id != 0) GLES20.glDeleteProgram(id);
        progColour = progDepth = progPlain = 0;
        myGeneration = -1;
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
