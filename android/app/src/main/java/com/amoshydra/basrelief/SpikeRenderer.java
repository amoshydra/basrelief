package com.amoshydra.basrelief;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;

import com.amoshydra.basrelief.core.render.CameraModel;
import com.amoshydra.basrelief.core.render.LightDirection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * THROWAWAY SPIKE. Fragment-shader relief drawn once per frame over two
 * synchronised videos (or over two stills as a control), into an offscreen
 * framebuffer at a configurable resolution, then presented to the screen.
 *
 * <p>This is NOT a production renderer and deliberately does not touch
 * {@link ReliefRenderer}. Its only job is to answer one question: can the
 * phone draw per-frame relief in the fragment shader at ~30 fps, and at what
 * render resolution does that fall apart. The number is the deliverable; the
 * look is judged by a human.
 *
 * <p>The depth track is greyscale; the shader derives a normal from four
 * extra taps (central differences) and lights it with {@code uLightDir}, then
 * applies Brightness/Shadow with exactly the arithmetic in
 * {@link com.amoshydra.basrelief.core.render.Picture} so those do not fork.
 */
final class SpikeRenderer implements GLSurfaceView.Renderer {

    static final String TAG = "BasreliefSpike";

    interface Stats {
        void onStats(String line);
    }

    private static final float SMOOTHING_TAU = 0.2f;

    private static final String VERT =
            "uniform mat4 uMVP;\n" +
            "uniform float uModelScale;\n" +
            "uniform mat4 uST;\n" +
            "attribute vec3 aPosition;\n" +
            "attribute vec2 aUV;\n" +
            "varying vec2 vUv;\n" +
            "varying vec3 vWorldPos;\n" +
            "void main() {\n" +
            "  vUv = (uST * vec4(aUV, 0.0, 1.0)).xy;\n" +
            "  vWorldPos = aPosition * uModelScale;\n" +
            "  gl_Position = uMVP * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String FRAG_TEMPLATE =
            "precision highp float;\n" +
            "varying vec2 vUv;\n" +
            "varying vec3 vWorldPos;\n" +
            "uniform __SAMPLER__ uColor;\n" +
            "uniform __SAMPLER__ uDepth;\n" +
            "uniform vec2 uTexel;\n" +
            "uniform vec2 uNormalScale;\n" +
            "uniform vec2 uParallax;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform vec3 uCamPos;\n" +
            "uniform float uBrightnessGain;\n" +
            "uniform float uShadowLift;\n" +
            "float h(vec2 uv) { return texture2D(uDepth, uv).r; }\n" +
            "void main() {\n" +
            "  float d  = h(vUv);\n" +
            "  float dl = h(vUv - vec2(uTexel.x, 0.0));\n" +
            "  float dr = h(vUv + vec2(uTexel.x, 0.0));\n" +
            "  float dd = h(vUv - vec2(0.0, uTexel.y));\n" +
            "  float du = h(vUv + vec2(0.0, uTexel.y));\n" +
            // Height field z = h; n = normalize(-dz/dx, -dz/dy, 1) in world units,
            // with the world-x and world-y texel sizes folded into uNormalScale.
            "  vec3 n = normalize(vec3(-(dr - dl) * uNormalScale.x,\n" +
            "                          -(du - dd) * uNormalScale.y, 1.0));\n" +
            "  vec3 vdir = normalize(uCamPos - vWorldPos);\n" +
            "  vec2 poff = (vdir.xy / max(vdir.z, 0.15)) * (d - 0.5) * uParallax;\n" +
            "  vec2 cuv = clamp(vUv + poff, vec2(0.004), vec2(0.996));\n" +
            "  vec3 color = texture2D(uColor, cuv).rgb;\n" +
            "  color *= uBrightnessGain;\n" +
            "  color += uShadowLift * (1.0 - color);\n" +
            "  float diff = max(dot(n, normalize(uLightDir)), 0.0);\n" +
            "  color *= 1.0 + 0.25 * diff;\n" +
            "  gl_FragColor = vec4(color, 1.0);\n" +
            "}\n";

    private static final String PRESENT_VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aUV;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  vUv = aUV;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String PRESENT_FRAG =
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform sampler2D uTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);\n" +
            "}\n";

    private final Context context;
    private final String colorPath;
    private final String depthPath;
    private final boolean video;
    private final float imageAspect;
    private final Stats stats;

    private volatile int pendingW = 1080, pendingH = 2340;
    private volatile int repeat = 1;
    private int fbW = 1080, fbH = 2340;

    private int program, presentProgram;
    private int aPos, aUV, uMVP, uModelScale, uST, uColor, uDepth, uTexel,
            uNormalScale, uParallax, uLightDir, uCamPos, uBrightnessGain, uShadowLift;
    private int pPos, pUV, pTex, pST;

    private FloatBuffer quadPos, quadUV, presentPos, presentUV;

    private int fbo, fboTex, fboDepthRb;

    private int colorTex, depthTex;
    private SurfaceTexture colorST, depthST;
    private Surface colorSurface, depthSurface;
    private SpikeVideoDecoder colorDec, depthDec;
    private final float[] colorSTM = new float[16];
    private final float[] depthSTM = new float[16];

    private final CameraModel camera = new CameraModel();
    private float surfaceW = 1f, surfaceH = 1f;

    private float targetX, targetY, smoothX, smoothY;
    private long lastFrameNanos;
    private long startNanos;
    private long lastStatNanos;
    private int frames;
    private long totalFrames;
    private int staleFrames;
    private int lastColorDecoded;

    SpikeRenderer(Context context, String colorPath, String depthPath,
                  boolean video, float imageAspect, Stats stats) {
        this.context = context;
        this.colorPath = colorPath;
        this.depthPath = depthPath;
        this.video = video;
        this.imageAspect = imageAspect > 0f ? imageAspect : 1280f / 1920f;
        this.stats = stats;
    }

    void setFboSize(int w, int h) {
        pendingW = w;
        pendingH = h;
    }

    /** How many relief passes to draw per presented frame (workload multiplier). */
    void setRepeat(int n) {
        repeat = Math.max(1, Math.min(1024, n));
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        buildGeometry();

        String frag = FRAG_TEMPLATE.replace("__SAMPLER__",
                video ? "samplerExternalOES" : "sampler2D");
        if (video) {
            frag = "#extension GL_OES_EGL_image_external : require\n" + frag;
        }
        program = createProgram(VERT, frag);
        aPos = GLES20.glGetAttribLocation(program, "aPosition");
        aUV = GLES20.glGetAttribLocation(program, "aUV");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uModelScale = GLES20.glGetUniformLocation(program, "uModelScale");
        uST = GLES20.glGetUniformLocation(program, "uST");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uDepth = GLES20.glGetUniformLocation(program, "uDepth");
        uTexel = GLES20.glGetUniformLocation(program, "uTexel");
        uNormalScale = GLES20.glGetUniformLocation(program, "uNormalScale");
        uParallax = GLES20.glGetUniformLocation(program, "uParallax");
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
        uCamPos = GLES20.glGetUniformLocation(program, "uCamPos");
        uBrightnessGain = GLES20.glGetUniformLocation(program, "uBrightnessGain");
        uShadowLift = GLES20.glGetUniformLocation(program, "uShadowLift");

        presentProgram = createProgram(PRESENT_VERT, PRESENT_FRAG);
        pPos = GLES20.glGetAttribLocation(presentProgram, "aPos");
        pUV = GLES20.glGetAttribLocation(presentProgram, "aUV");
        pTex = GLES20.glGetUniformLocation(presentProgram, "uTex");

        if (video) {
            colorST = new SurfaceTexture(false);
            depthST = new SurfaceTexture(false);
            colorTex = createExternalTexture(colorST);
            depthTex = createExternalTexture(depthST);
            colorSurface = new Surface(colorST);
            depthSurface = new Surface(depthST);
            colorDec = new SpikeVideoDecoder(colorPath, colorSurface);
            depthDec = new SpikeVideoDecoder(depthPath, depthSurface);
            colorDec.start();
            depthDec.start();
            Log.i(TAG, "decoders started color=" + colorPath + " depth=" + depthPath);
        } else {
            colorTex = uploadStill(R.raw.image);
            depthTex = uploadStill(R.raw.depth);
            Log.i(TAG, "stills uploaded");
        }

        startNanos = System.nanoTime();
        lastStatNanos = startNanos;
    }

    private int createExternalTexture(SurfaceTexture st) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        st.attachToGLContext(tex[0]);
        st.setDefaultBufferSize(1280, 1920);
        return tex[0];
    }

    private int uploadStill(int resId) {
        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeStream(context.getResources().openRawResource(resId));
        } catch (Throwable ignored) {
        }
        if (bmp == null) return 0;
        Bitmap upright = flipVertically(bmp);
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upright, 0);
        return tex[0];
    }

    private static Bitmap flipVertically(Bitmap src) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.preScale(1f, -1f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
    }

    private void buildGeometry() {
        float a = imageAspect;
        float[] pos = {
                -a / 2f, -0.5f, 0f,
                a / 2f, -0.5f, 0f,
                -a / 2f, 0.5f, 0f,
                a / 2f, 0.5f, 0f,
        };
        float[] uv = {
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
        };
        quadPos = toBuffer(pos);
        quadUV = toBuffer(uv);
        presentPos = toBuffer(new float[] {
                -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
        });
        presentUV = toBuffer(new float[] {
                0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f,
        });
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceW = Math.max(1, width);
        surfaceH = Math.max(1, height);
        if (fbo == 0) {
            recreateFbo(fbW, fbH);
        }
    }

    private void recreateFbo(int w, int h) {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[] { fbo }, 0);
            GLES20.glDeleteTextures(1, new int[] { fboTex }, 0);
            GLES20.glDeleteRenderbuffers(1, new int[] { fboDepthRb }, 0);
            fbo = fboTex = fboDepthRb = 0;
        }
        fbW = Math.max(64, w);
        fbH = Math.max(64, h);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        fboTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, fbW, fbH, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fbo = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);

        int[] rb = new int[1];
        GLES20.glGenRenderbuffers(1, rb, 0);
        fboDepthRb = rb[0];
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, fboDepthRb);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                fbW, fbH);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, fboDepthRb);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO incomplete: 0x" + Integer.toHexString(status));
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        Log.i(TAG, "FBO " + fbW + "x" + fbH + " status=" + status);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();

        if (fbW != pendingW || fbH != pendingH) {
            recreateFbo(pendingW, pendingH);
        }

        // Slow synthetic tilt, so the relief parallaxes and any swimming is
        // visible. Mirrors the gyro's smoothing so the camera cost is fair.
        double s = now * 1e-9;
        targetX = 0.40f * (float) Math.sin(s * 0.8);
        targetY = 0.30f * (float) Math.sin(s * 0.53 + 1.0);
        float dt = Math.min((now - lastFrameNanos) / 1e9f, 0.1f);
        lastFrameNanos = now;
        float alpha = 1f - (float) Math.exp(-dt / SMOOTHING_TAU);
        smoothX += (targetX - smoothX) * alpha;
        smoothY += (targetY - smoothY) * alpha;

        boolean colorFresh = false;
        if (video && colorDec != null && depthDec != null) {
            if (!colorDec.isDone()) {
                try {
                    colorST.updateTexImage();
                    colorST.getTransformMatrix(colorSTM);
                    colorFresh = true;
                } catch (Throwable t) {
                    Log.w(TAG, "color updateTexImage: " + t);
                }
            }
            if (!depthDec.isDone()) {
                try {
                    depthST.updateTexImage();
                    depthST.getTransformMatrix(depthSTM);
                } catch (Throwable t) {
                    Log.w(TAG, "depth updateTexImage: " + t);
                }
            }
            int c = colorDec.decoded();
            if (c == lastColorDecoded && !colorDec.isDone()) staleFrames++;
            lastColorDecoded = c;
        }

        for (int i = 0; i < repeat; i++) {
            renderRelief(video);
        }
        present();

        frames++;
        totalFrames++;
        if (now - lastStatNanos >= 1_000_000_000L) {
            float secs = (now - lastStatNanos) / 1e9f;
            float fps = frames / secs;
            long elapsed = now - startNanos;
            float avg = totalFrames / (elapsed / 1e9f);
            int cd = colorDec == null ? 0 : colorDec.decoded();
            int dd = depthDec == null ? 0 : depthDec.decoded();
            String line = String.format(Locale.US,
                    "%s %dx%d x%d fps=%.1f avg=%.1f stale=%d/%d dec=%d/%d%s%s",
                    video ? "video" : "still", fbW, fbH, repeat, fps, avg,
                    staleFrames, frames, cd, dd,
                    colorDec != null && colorDec.error() != null
                            ? " colorErr=" + colorDec.error() : "",
                    depthDec != null && depthDec.error() != null
                            ? " depthErr=" + depthDec.error() : "");
            Log.i(TAG, line);
            if (stats != null) stats.onStats(line);
            frames = 0;
            staleFrames = 0;
            lastStatNanos = now;
        }
    }

    private void renderRelief(boolean external) {
        camera.setAspect(imageAspect);
        camera.setSurfaceAspect((float) fbW / fbH);
        camera.setZoom(1f);
        camera.setDepth(1f);
        camera.setMotion(1f);
        CameraModel.Frame f = camera.frame(fbW, fbH, 0f, 0f, 0f, 0f, smoothX, smoothY);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, fbW, fbH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, f.mvp, 0);
        GLES20.glUniform1f(uModelScale, f.scale);
        GLES20.glUniformMatrix4fv(uST, 1, false,
                external ? colorSTM : IDENTITY, 0);
        // World-x per texel = imageAspect * scale / videoW; world-y per texel =
        // scale / videoH. n = (-dz/dx, -dz/dy, 1), z = (h-0.5)*zScale.
        float texelU = 1f / 1280f;
        float texelV = 1f / 1920f;
        float grad = 1.5f;
        GLES20.glUniform2f(uTexel, texelU, texelV);
        GLES20.glUniform2f(uNormalScale,
                f.zScale / (2f * texelU * grad * imageAspect),
                f.zScale / (2f * texelV * grad));
        GLES20.glUniform2f(uParallax,
                f.zScale / (imageAspect * f.scale),
                f.zScale / f.scale);
        GLES20.glUniform3f(uLightDir, LightDirection.DEFAULT.x, LightDirection.DEFAULT.y,
                LightDirection.DEFAULT.z);
        GLES20.glUniform3f(uCamPos, f.eyeX, f.eyeY, f.eyeZ);
        GLES20.glUniform1f(uBrightnessGain, 1f);
        GLES20.glUniform1f(uShadowLift, 0f);

        int target = external ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(target, colorTex);
        GLES20.glUniform1i(uColor, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(target, depthTex);
        GLES20.glUniform1i(uDepth, 1);

        quadPos.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, quadPos);
        quadUV.position(0);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, quadUV);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void present() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, (int) surfaceW, (int) surfaceH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(presentProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glUniform1i(pTex, 0);
        presentPos.position(0);
        GLES20.glEnableVertexAttribArray(pPos);
        GLES20.glVertexAttribPointer(pPos, 2, GLES20.GL_FLOAT, false, 0, presentPos);
        presentUV.position(0);
        GLES20.glEnableVertexAttribArray(pUV);
        GLES20.glVertexAttribPointer(pUV, 2, GLES20.GL_FLOAT, false, 0, presentUV);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    /**
     * Teardown safe from the UI thread, for {@code onDestroy}.
     *
     * <p>The GL-thread {@link #release} is the correct one, but it is queued with
     * {@code queueEvent} and the GL thread is already paused by the time
     * {@code onDestroy} runs, so it never executes. A decoder parked in
     * {@code releaseOutputBuffer} on a full SurfaceTexture queue is then never
     * stopped or released, and every activity recreation leaks two codecs until
     * MediaCodec refuses to create more. Releasing the Surface first is what
     * unblocks that thread; do not reorder.
     */
    void releaseFromUiThread() {
        if (colorSurface != null) colorSurface.release();
        if (depthSurface != null) depthSurface.release();
        if (colorDec != null) colorDec.stop();
        if (depthDec != null) depthDec.stop();
        if (colorST != null) colorST.release();
        if (depthST != null) depthST.release();
        colorSurface = depthSurface = null;
        colorST = depthST = null;
        colorDec = depthDec = null;
    }

    private static final float[] IDENTITY = new float[] {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
    };

    private static FloatBuffer toBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private static int createProgram(String vertexSrc, String fragmentSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] status = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            throw new RuntimeException("program link failed: " + GLES20.glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int compile(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            throw new RuntimeException("shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
