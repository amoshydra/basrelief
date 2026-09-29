package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import com.amoshydra.basrelief.core.render.CameraModel;
import com.amoshydra.basrelief.core.render.Framing;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;
import com.amoshydra.basrelief.core.render.ReliefMesh;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLES2 renderer for a real displaced relief surface (continuous, no tearing):
 * the photo is mapped onto a CPU-built triangle mesh with per-vertex normals
 * so a directional light shades it — genuine geometry cues (silhouette,
 * parallax, shading) instead of a flat-card texture trick.
 *
 * Settings (SharedPreferences "depthwall") are read at startup; live
 * changes arrive through setDepth / setZoom / setMotion / loadPair,
 * always invoked on the GL thread.
 */
public final class ReliefRenderer implements GLSurfaceView.Renderer {


    // FOV / camera distance / crop margin / tilt range live in CameraModel —
    // do NOT duplicate them here, or the two will drift apart.
    // Smoothing time constant (seconds) — display-refresh independent, so the
    // feel is the same at 60/90/120 Hz. This is the value used for SMALL errors,
    // i.e. sensor jitter while the phone is held still; it is what stops the
    // relief from shimmering when you are not moving.
    private static final float SMOOTHING_TAU = 0.2f;

    // Fast attack / slow release, on top of the above.
    //
    // A single time constant cannot tell a deliberate movement from a sensor
    // wobble, so it treats both identically: with tau=0.2 the filter closes only
    // ~9.5% of the gap per frame at 50 Hz, and a 30 degree tilt takes ~460 ms to
    // get 90% of the way there. That reads as "the gyro lags".
    //
    // So the fraction followed per frame is raised once the error is large:
    // below ATTACK_ERROR the exponential applies unchanged (jitter stays
    // smoothed), above it alpha saturates at 1 and the value snaps in a single
    // frame. Same idea as a camera autofocus that snaps when swung somewhere
    // new and glides when nearly there.
    //
    // This is a FEEL constant. Lower it if the motion reads as twitchy; raise it
    // if mid-movement still feels like it lags. Tilt is normalised as
    // roll/RANGE_DEG with RANGE_DEG = 25, so 0.125 here is about 3 degrees of
    // phone rotation, not 7.
    private static final float ATTACK_ERROR = 0.125f;
    /** Depth default: 90% of the maximum the camera allows at this zoom. */
    private static final int DEFAULT_DEPTH = 90;
    private static final int GRID = 256; // relief grid resolution

    /**
     * There is deliberately no frame-rate cap. An earlier version capped draws
     * at 60/30fps to save battery; it was removed because it could not do that,
     * and it caused visible tearing:
     *
     * - The surface is RENDERMODE_WHEN_DIRTY and only the GYRO asks for a
     *   frame, at roughly 50Hz. A 60fps cap therefore never fired and saved
     *   nothing; a 30fps cap silently DISCARDED half the tilt updates, since
     *   the requestRender() is already consumed by the time onDrawFrame runs.
     * - Worse, returning early does not cancel the present. GLSurfaceView still
     *   calls eglSwapBuffers, so a skipped frame re-presents the previous
     *   buffer mid-scanout, which tears.
     *
     * The draw rate is already bounded by the sensor, and the real battery win
     * is elsewhere: TiltController unregisters the expensive rotation-vector
     * sensor after 5s of stillness and falls back to a low-power accelerometer
     * until a shake. See also setPowerSave(), which currently only records the
     * OS power-save state for a future resolution-scale change.
     */

    /**
     * Opacity, pinned to 1. It was a 0.25s fade up from black, which cannot work
     * here: the system snapshots the surface about 100 ms after it appears, so the
     * black first frame was what got snapshotted. Kept as a uniform because a
     * cross-fade between photos is the obvious next thing to want, and it has to be
     * timed to fit inside that snapshot window. See the note where it is set.
     */
    private float fade = 1f;
    private volatile float powerSave = 0f; // 0..1, written from the UI thread

    private static final String VERT =
            "uniform mat4 uMVP;\n" +
            "uniform float uModelScale;\n" +
            "uniform float uIntensity;\n" +
            "attribute vec3 aPosition;\n" +
            "attribute vec2 aUV;\n" +
            "attribute vec3 aNormal;\n" +
            "varying vec2 vUv;\n" +
            "varying vec3 vWorldPos;\n" +
            "varying vec3 vWorldNormal;\n" +
            "void main() {\n" +
            "  vUv = aUV;\n" +
            "  vec3 p = aPosition * uModelScale;\n" +
            "  p.z *= uIntensity;\n" +
            "  vWorldPos = p;\n" +
            "  // inverse-transpose of the non-uniform z scale (model diag(1,1,uIntensity))\n" +
            "  vWorldNormal = normalize(vec3(aNormal.x, aNormal.y, aNormal.z / max(uIntensity, 1e-3)));\n" +
            "  gl_Position = uMVP * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String FRAG =
            "precision highp float;\n" +
            "varying vec2 vUv;\n" +
            "varying vec3 vWorldPos;\n" +
            "varying vec3 vWorldNormal;\n" +
            "uniform sampler2D uColor;\n" +
            "uniform float uFade;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform vec3 uCamPos;\n" +
            "void main() {\n" +
            "  vec3 n = normalize(vWorldNormal);\n" +
            "  vec3 l = normalize(uLightDir);\n" +
            "  vec3 v = normalize(uCamPos - vWorldPos);\n" +
            "  float diff = max(dot(n, l), 0.0);\n" +
            "  vec3 color = texture2D(uColor, vUv).rgb;\n" +
            "  color *= 1.0 + 0.25 * diff;\n" +
            "  gl_FragColor = vec4(color * uFade, 1.0);\n" +
            "}\n";

    private static final String MARKER_VERT =
            "uniform mat4 uMVP;\n" +
            "attribute vec2 aPos;\n" +
            "void main() {\n" +
            "  gl_PointSize = 7.0;\n" +
            "  gl_Position = uMVP * vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String MARKER_FRAG =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "void main() {\n" +
            "  gl_FragColor = uColor;\n" +
            "}\n";

    private final Context context;

    private int program;
    private int aPosition, aUV, aNormal, uMVP, uModelScale, uIntensity, uColor, uLightDir, uCamPos;
    private int uFade = -1;
    private FloatBuffer posBuffer, uvBuffer, normalBuffer;
    private int vertexCount;

    private int textureColor = -1;

    // marker overlay (studio only)
    private int markerProgram;
    private int markerAPos, markerUMVP, markerUColor;

    /**
     * True when the marker program's attribute index overlaps a mesh attribute
     * index, so drawing the marker overwrites a mesh pointer. Set in
     * onSurfaceCreated; the mesh pointers are only re-bound after a marker draw
     * when this is set.
     */
    private boolean markerClobbersMesh;

    /**
     * How to get back onto the GL thread. Supplied by the host, which owns the
     * GLSurfaceView.
     *
     * <p>Deliberately NOT {@code new Handler(Looper.myLooper())} taken on the GL
     * thread: that is null on a real device, because GLSurfaceView's GLThread
     * does not prepare a Looper, and constructing a Handler from it throws an
     * NPE inside onSurfaceCreated. The host's {@code queueEvent} is the
     * supported route and behaves the same on every version.
     */
    private volatile java.util.concurrent.Executor glDispatch;

    /**
     * Asks the host for another frame. Supplied by the host, which owns the
     * GLSurfaceView.
     *
     * <p>This is REQUIRED, not an optimisation. The surface is
     * RENDERMODE_WHEN_DIRTY, so a frame is only drawn when someone asks. The
     * image load is asynchronous, so the first frame after a surface change
     * almost always arrives BEFORE the texture exists: it clears and bails on
     * textureColor < 0. When the decode finishes and applyPending() uploads the
     * texture, nothing else in a live wallpaper will ask for a frame -- no
     * preference changed, and the tilt callback only fires while the gyro is
     * awake. Without this the wallpaper loads its image and then stays black
     * until something happens to nudge it.
     */
    private volatile Runnable renderRequester;

    /**
     * Load the first image synchronously instead of on the Io thread.
     *
     * <p>Set by the live wallpaper ONLY, and for a specific reason.
     *
     * <p>The system takes a snapshot of the wallpaper surface roughly 100 ms
     * after the engine's surface becomes valid, then destroys the engine and
     * keeps that snapshot. An asynchronous first load cannot finish inside that
     * window -- a 6 MB PNG decode plus a 393k-vertex mesh build is close to a
     * second -- so the snapshot captures an empty surface. The launcher then
     * displays black with nothing alive to redraw it, and it never recovers.
     *
     * <p>So the first load of each engine runs inline, putting a texture in the
     * surface before anything can snapshot it. Every LATER load stays
     * asynchronous: by then the wallpaper is on screen and a change does not
     * race a snapshot.
     *
     * <p>Each engine builds its own renderer, so "first load" is naturally
     * per-engine. An engine recreated after the wallpaper is hidden and shown
     * again gets a synchronous first load too, which is exactly when the race
     * would otherwise come back.
     *
     * <p>The studio leaves this off. It redraws continuously, has no snapshot
     * step, and would only suffer the stall.
     */
    private volatile boolean syncFirstLoad = false;
    private boolean loadedOnce = false;

    /**
     * The pair currently on screen; null for the bundled demo.
     *
     * <p>Written on the GL thread inside {@link #applyPending} and read by the
     * host to decide whether a reload is needed, so both are volatile: the studio
     * asks from the UI thread and the wallpaper from its pref listener.
     *
     * <p>This is the renderer's own knowledge rather than the host's bookkeeping
     * because the host cannot know whether a load it asked for survived. A
     * request handed to a paused GLSurfaceView can be dropped, and a host that
     * assumed otherwise skips the retry forever.
     */
    private volatile String loadedImagePath;
    private volatile String loadedDepthPath;
    /**
     * Whether anything has ever been installed into the current GL context.
     *
     * <p>Load-bearing, and it exists because of a coincidence of representation.
     * The bundled demo is the pair (null, null) because it has no file, and a
     * renderer that has installed <i>nothing</i> is also (null, null), because that
     * is the initial value of both fields above. So without this flag a fresh
     * engine asked to show the demo was told it already was and skipped the load
     * entirely: the surface stayed empty, the wallpaper was black, and the status
     * line cheerfully reported the demo as loaded. Photos were unaffected, which
     * is what made it look like the bundled item specifically was broken.
     */
    private volatile boolean hasInstalled = false;

    /**
     * Monotonic tag for the most recently issued load. Incremented on the GL
     * thread (where every load is requested) and read on it (where results are
     * applied), so no synchronisation is needed.
     */
    private int loadSeq = 0;

    /**
     * Whether the given pair is already on screen.
     *
     * <p>Null-safe, because the bundled demo is (null, null) and calling equals
     * on either side is a crash on exactly that path.
     */
    public boolean showingSamePair(String imagePath, String depthPath) {
        if (!hasInstalled) return false; // see hasInstalled
        return samePath(imagePath, loadedImagePath) && samePath(depthPath, loadedDepthPath);
    }

    private static boolean samePath(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Wire this to GLSurfaceView.requestRender(). */
    public void setRenderRequester(Runnable r) {
        this.renderRequester = r;
    }

    private FloatBuffer ringBuffer;
    private static final int RING_SEGMENTS = 48;
    private final float[] markerMat = new float[16];
    private final float[] markerMvp = new float[16];
    private volatile boolean showOrbitMarker = false;

    private float aspect = 1f;   // image w / h, baked into quad x
    private float scale = 1f;    // model scale (covers viewport with crop margin)
    private final float[] tapOrbit = new float[2]; // scratch, avoids per-tap allocation
    private float surfaceAspect = 1f;

    private volatile float targetX = 0f, targetY = 0f;
    private volatile float orbitX = 0f, orbitY = 0f;
    private float smoothX = 0f, smoothY = 0f;
    /**
     * The frame clock. There is deliberately no frame-rate cap. An earlier version
     * capped draws at 60/30fps to save battery; it was removed because it could
     * not do that, and it caused visible tearing:
     *
     * - The surface is RENDERMODE_WHEN_DIRTY and only the GYRO asks for a frame,
     *   at roughly 50Hz. A 60fps cap therefore never fired and saved nothing; a
     *   30fps cap silently DISCARDED half the tilt updates, since the
     *   requestRender() is already consumed by the time onDrawFrame runs.
     * - Worse, returning early does not cancel the present. GLSurfaceView still
     *   calls eglSwapBuffers, so a skipped frame re-presents the previous buffer
     *   mid-scanout, which tears.
     *
     * The draw rate is already bounded by the sensor, and the real battery win is
     * elsewhere: TiltController unregisters the expensive rotation-vector sensor
     * after 5s of stillness and falls back to a low-power accelerometer until a
     * shake. See also setPowerSave(), which currently only records the OS
     * power-save state for a future resolution-scale change.
     */
    private long lastFrameNanos = 0L;

    private volatile float panX = 0f, panY = 0f;
    private volatile float zoom = 1f;
    private float surfaceW, surfaceH;
    private volatile float motion = 1f;
    private volatile float depth = DEFAULT_DEPTH / 100f; // 0..1 fraction of maxZScale

    /** Pure-JVM camera model: scale, projection, view, z-clamp, tap unprojection. */
    private final CameraModel camera = new CameraModel();

    public ReliefRenderer(Context context) {
        this.context = context;
    }

    /** Request a 4x-MSAA EGL config (falls back to no MSAA), to smooth polygon edges. */
    public static void enableMSAA(final GLSurfaceView glView) {
        glView.setEGLConfigChooser(new GLSurfaceView.EGLConfigChooser() {
            @Override
            public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
                EGLConfig c = tryConfig(egl, display, 4);
                if (c == null) c = tryConfig(egl, display, 0);
                return c;
            }

            private EGLConfig tryConfig(EGL10 egl, EGLDisplay display, int samples) {
                int[] attribs = new int[] {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_SAMPLE_BUFFERS, samples > 0 ? 1 : 0,
                    EGL10.EGL_SAMPLES, samples,
                    EGL10.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] num = new int[1];
                if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0) {
                    return configs[0];
                }
                return null;
            }
        });
    }

    public void setTilt(float x, float y) {
        targetX = x;
        targetY = y;
    }

    public void setOrbit(float nx, float ny) {
        orbitX = nx;
        orbitY = ny;
    }

    /**
     * Drop to the power-saving frame rate. Called from the UI thread; the
     * derived interval is recomputed on the GL thread.
     */
    public void setPowerSave(boolean on) {
        powerSave = on ? 1f : 0f;
    }

    /** @param v Depth as a 0..1 fraction of the maximum safe z-scale. */
    public void setDepth(float v) {
        depth = Math.max(0f, Math.min(1f, v));
    }

    public void setZoom(float v) {
        zoom = Math.max(0.25f, Math.min(2f, v));
    }

    public float getZoom() {
        return zoom;
    }

    public void setMotion(float v) {
        motion = Math.max(0f, Math.min(3f, v));
    }

    public void setShowOrbitMarker(boolean show) {
        showOrbitMarker = show;
    }

    public void panBy(float dx, float dy) {
        float limit = Math.max(surfaceW, surfaceH);
        panX = Math.max(-limit, Math.min(limit, panX - dx));
        panY = Math.max(-limit, Math.min(limit, panY - dy));
    }

    public void setPan(float px, float py) {
        float limit = Math.max(surfaceW, surfaceH);
        if (limit <= 0f) {
            // The GL surface does not exist yet, so there is no meaningful bound
            // on a PIXEL-valued pan. Clamping against limit == 0 here would
            // silently destroy the stored offset — Math.max(-0f, -135f) is -0f —
            // and the zero would then be persisted back to prefs on the next
            // gesture, losing the user's pan across a restart. Accept it
            // unclamped; onSurfaceChanged re-clamps once the size is known.
            panX = px;
            panY = py;
            return;
        }
        panX = Math.max(-limit, Math.min(limit, px));
        panY = Math.max(-limit, Math.min(limit, py));
    }

    /**
     * Persist the framing onto the item being shown.
     *
     * <p>On the item, not on the config, so panning a portrait does not reframe
     * the next photo. The id is read at save time rather than captured at gesture
     * start, so a drag that outlives a selection change is written to whatever is
     * on screen when the finger lifts -- which is what the user was looking at.
     */
    public void saveViewToPrefs() {
        final float ox = orbitX, oy = orbitY, px = panX, py = panY;
        SharedPreferences p = context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
        final String id = AppState.load(p, context.getFilesDir()).activeId();
        if (id == null) return; // the bundled demo keeps its own framing
        LibraryStore.editItem(p, id, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                return item.withOrbit(ox, oy).withPan(px, py);
            }
        });
    }

    /** @see #saveViewToPrefs */
    public void savePanToPrefs() {
        final float px = panX, py = panY;
        SharedPreferences p = context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
        final String id = AppState.load(p, context.getFilesDir()).activeId();
        if (id == null) return;
        LibraryStore.editItem(p, id, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                return item.withPan(px, py);
            }
        });
    }

    public void resetView() {
        orbitX = 0f;
        orbitY = 0f;
        panX = 0f;
        panY = 0f;
        zoom = 1f;
    }

    /**
     * Load an image + depth pair (GL thread only). Either path may be null:
     * image falls back to the bundled demo; depth falls back to the bundled
     * demo depth, then to the image's embedded chunk, then to a flat surface.
     */
    private Pending decodePair(String imagePath, String depthPath) {
        // Already decoded by the other surface in this process? Then uploading it
        // is the only work left, which is what makes the wallpaper's very first
        // frame correct: the system snapshots the surface ~100ms after it appears
        // and a decode takes ~1s, so anything decoded after that point is too late
        // to be in the snapshot no matter how it is scheduled.
        Object warm = PairCache.get(imagePath, depthPath);
        if (warm instanceof Pending) return (Pending) warm;

        byte[] imageBytes = null;
        Bitmap colorBmp;
        int imgW, imgH;
        boolean customImage = imagePath != null;
        if (imagePath == null) {
            colorBmp = decodeRaw(R.raw.image);
        } else {
            imageBytes = readFile(imagePath);
            colorBmp = imageBytes != null
                    ? BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length)
                    : null;
        }
        if (colorBmp == null) {
            return null;
        }
        imgW = colorBmp.getWidth();
        imgH = colorBmp.getHeight();

        int[] dims = new int[2];
        byte[] grayOut = null;
        boolean customDepth = depthPath != null;
        boolean embedded = false;
        boolean flat = false;
        if (depthPath != null) {
            byte[] depthBytes = readFile(depthPath);
            if (depthBytes != null) {
                grayOut = extractDepth(depthBytes, dims);
                if (grayOut == null) {
                    Bitmap db = BitmapFactory.decodeByteArray(depthBytes, 0, depthBytes.length);
                    if (db != null) {
                        dims[0] = db.getWidth();
                        dims[1] = db.getHeight();
                        grayOut = readGray(db);
                        db.recycle();
                    }
                }
            }
        }
        if (depthPath == null && imagePath == null) {
            // Bundled demo: ship image.png AND depth.png as a pair.
            // Without this the fallback below fills a flat depth map, so the
            // demo silently rendered as a flat plane with no relief at all.
            Bitmap db = decodeRaw(R.raw.depth);
            if (db != null) {
                dims[0] = db.getWidth();
                dims[1] = db.getHeight();
                grayOut = readGray(db);
                db.recycle();
            }
        }
        if (grayOut == null && imageBytes != null) {
            grayOut = extractDepth(imageBytes, dims);
            embedded = grayOut != null;
        }
        if (grayOut == null) {
            grayOut = new byte[imgW * imgH];
            Arrays.fill(grayOut, (byte) 128);
            dims[0] = imgW;
            dims[1] = imgH;
            flat = true;
        }

        Pending result = new Pending(colorBmp, grayOut, dims[0], dims[1],
                customImage, customDepth, embedded, flat, imagePath, depthPath);
        // Offer it to the other surface. Only the selected pair is worth keeping,
        // and the studio is always the one that decodes first.
        PairCache.put(imagePath, depthPath, result);
        return result;
    }

    /**
     * Whether a decoded pair is still usable, for {@link PairCache}.
     *
     * <p>Defence in depth. Nothing recycles these any more, so this should always
     * be true; but a stale cache entry that is somehow unusable should cost one
     * re-decode, not a crash on someone's home screen. That trade is obvious in
     * one direction and not the other, so it is made explicit here.
     */
    static boolean isUsable(Object pending) {
        if (!(pending instanceof Pending)) return false;
        Bitmap b = ((Pending) pending).colorBmp;
        return b == null || !b.isRecycled();
    }

    /** Everything {@link #decodePair} produces, ready for the GL half. */
    private static final class Pending {
        final Bitmap colorBmp;
        final byte[] gray;
        final int w, h;
        final boolean customImage, customDepth, embedded, flat;
        /**
         * The paths this was decoded from, so the renderer can record what is
         * genuinely on screen rather than what was last asked for. See
         * {@link #loadedImagePath}.
         */
        final String requestedImage, requestedDepth;

        Pending(Bitmap colorBmp, byte[] gray, int w, int h,
                boolean customImage, boolean customDepth, boolean embedded, boolean flat,
                String requestedImage, String requestedDepth) {
            this.colorBmp = colorBmp;
            this.gray = gray;
            this.w = w;
            this.h = h;
            this.customImage = customImage;
            this.customDepth = customDepth;
            this.embedded = embedded;
            this.requestedImage = requestedImage;
            this.requestedDepth = requestedDepth;
            this.flat = flat;
        }
    }

    /**
     * Upload the texture and build the mesh. GL thread only.
     *
     * @return false if there was nothing to load
     */
    private boolean applyPending(Pending p) {
        if (p == null) {
            writeStatus("failed to read image");
            return false;
        }
        if (p.customImage && p.customDepth) {
            writeStatus(p.flat ? "custom image, flat (no depth found)"
                    : p.embedded ? "custom image + embedded depth"
                    : "custom image + depth map");
        } else if (p.customImage) {
            writeStatus(p.embedded ? "custom image + embedded depth" : "custom image");
        } else {
            writeStatus(p.flat ? "bundled demo, FLAT (no depth found)" : "bundled demo + depth");
        }
        if (!install(p.colorBmp, p.gray, p.w, p.h)) {
            // Nothing was installed, so nothing may be recorded as installed.
            //
            // Recorded here, at the swap, and only once the swap has actually
            // happened: the engine used to keep its own copy updated at request
            // time, so a load that never completed still counted as done and the
            // next comparison said "already showing that", leaving the wallpaper
            // on the old photo with nothing to retry it. Recording a failed
            // install is the same mistake one level deeper, and it is sticky --
            // a texture that never existed is still texture 0 at the next
            // visibility change, so the black would survive being made visible
            // again.
            return false;
        }
        // Recorded HERE, where the swap actually happens, and not where the load
        // was requested.
        loadedImagePath = p.requestedImage;
        loadedDepthPath = p.requestedDepth;
        hasInstalled = true;
        // The frame that revealed the empty texture has already been drawn;
        // without this request the uploaded texture is never presented.
        Runnable r = renderRequester;
        if (r != null) r.run();
        return true;
    }

    /**
     * Read, decode and build the mesh off the GL thread, then apply on it.
     *
     * <p>This used to run end to end on the GL thread, so a full-size PNG read, a
     * bitmap decode and a 393k-vertex mesh build all happened between two frames.
     * On a live wallpaper that reads as a multi-second freeze every time the
     * image changes, which is why sharing a photo looked like it had blanked the
     * wallpaper.
     *
     * <p>Safe to call from any thread. Returns immediately: frames drawn before
     * the result lands simply clear and bail, because there is no texture yet,
     * and the existing fade-in covers the arrival.
     *
     */
    public void loadPairAsync(final String imagePath, final String depthPath) {
        // Tag the request so a result can be recognised as out of date.
        //
        // Decodes run off the GL thread and finish whenever they finish, so an
        // older request can and does complete after a newer one. Without this,
        // the slower older decode installs second and quietly replaces the newer
        // selection with the previous photo -- observed on device, not merely
        // possible. The fix is a monotonic tag rather than a timestamp: two
        // requests can share a millisecond, and only the order of issue matters.
        final int seq = ++loadSeq;
        final java.util.concurrent.Executor glThread = glDispatch;
        if (glThread == null) {
            // No dispatcher yet: we are almost certainly inside onSurfaceCreated
            // before the host could set one, which cannot happen with the current
            // wiring, so just do it inline rather than dropping the image.
            applyPending(decodePair(imagePath, depthPath));
            return;
        }
        Io.run(new java.util.concurrent.Callable<Pending>() {
            @Override
            public Pending call() {
                return decodePair(imagePath, depthPath);
            }
        }, new Io.Callback<Pending>() {
            @Override
            public void onDone(final Pending pending, Exception error) {
                final Pending p = error != null ? null : pending;
                glThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        // A newer request was issued while this one decoded, so
                        // this result is stale and installing it would show the
                        // previous selection. Dropping it is safe: the newer
                        // request is already in flight and will install itself.
                        if (seq != loadSeq) return;
                        applyPending(p);
                    }
                });
            }
        }, glThread);
    }

    /** Wrap a GLSurfaceView's queueEvent as the Executor {@link Io#run} wants. */
    public static java.util.concurrent.Executor glExecutor(final GLSurfaceView view) {
        return new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable r) {
                view.queueEvent(r);
            }
        };
    }

    /**
     * Supply the GL-thread dispatcher. Both hosts call this once, right after
     * constructing the renderer and before the surface exists.
     */
    public void setGlDispatch(java.util.concurrent.Executor e) {
        this.glDispatch = e;
    }

    /**
     * Synchronous load, for tests and any caller that needs the result before
     * continuing. Must be called on the GL thread.
     */
    public boolean loadPair(String imagePath, String depthPath) {
        return applyPending(decodePair(imagePath, depthPath));
    }

    /**
     * One axis of the tilt smoother: exponential for small errors, instant for
     * large ones.
     *
     * @param alpha the exponential step for this frame, from SMOOTHING_TAU
     */
    private static float approach(float smooth, float target, float alpha) {
        float err = target - smooth;
        float a = alpha;
        float mag = Math.abs(err);
        if (mag > ATTACK_ERROR) {
            a = 1f;                 // deliberate movement: snap
        } else if (mag * 8f > a) {
            a = mag * 8f;           // blend smoothly into the fast regime
        }
        return smooth + err * a;
    }

    private void writeStatus(String status) {
        context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
                .edit().putString("status", status).apply();
    }

    private byte[] extractDepth(byte[] pngBytes, int[] dims) {
        DepthPng.Result res = DepthPng.extract(pngBytes);
        if (res == null) return null;
        if (res.rawGray != null) {
            dims[0] = res.rawW;
            dims[1] = res.rawH;
            return res.rawGray;
        }
        Bitmap db = BitmapFactory.decodeByteArray(res.png, 0, res.png.length);
        if (db == null) return null;
        dims[0] = db.getWidth();
        dims[1] = db.getHeight();
        byte[] g = readGray(db);
        db.recycle();
        return g;
    }

    /** Returns true if the tap landed on the quad and the orbit was set. */
    public boolean screenToOrbit(float sx, float sy, int w, int h) {
        if (w == 0 || h == 0 || textureColor < 0) return false;
        float ndcX = (sx / w) * 2f - 1f;
        float ndcY = -(sy / h) * 2f + 1f;
        recomputeScale();
        if (!camera.screenToOrbit(ndcX, ndcY, w, h, panX, panY,
                orbitX, orbitY, smoothX, smoothY, tapOrbit)) return false;
        orbitX = tapOrbit[0];
        orbitY = tapOrbit[1];
        return true;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f); // pitch black
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        program = createProgram(VERT, FRAG);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aUV = GLES20.glGetAttribLocation(program, "aUV");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uModelScale = GLES20.glGetUniformLocation(program, "uModelScale");
        uIntensity = GLES20.glGetUniformLocation(program, "uIntensity");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
        uCamPos = GLES20.glGetUniformLocation(program, "uCamPos");
        uFade = GLES20.glGetUniformLocation(program, "uFade");

        markerProgram = createProgram(MARKER_VERT, MARKER_FRAG);
        markerAPos = GLES20.glGetAttribLocation(markerProgram, "aPos");
        markerUMVP = GLES20.glGetUniformLocation(markerProgram, "uMVP");
        markerUColor = GLES20.glGetUniformLocation(markerProgram, "uColor");
        ringBuffer = buildRing(RING_SEGMENTS);

        // The mesh attribute pointers are bound once per install, not per frame
        // (see bindMeshAttribs). That is only safe if the marker's attribute
        // index differs from the mesh's: they come from two separate programs,
        // and the linker is free to hand out the same index to both. If it
        // does, drawing the marker overwrites a mesh pointer, so we re-bind
        // afterwards instead of assuming it cannot happen.
        markerClobbersMesh = markerAPos == aPosition || markerAPos == aUV
                || markerAPos == aNormal;
        if (markerClobbersMesh) {
            android.util.Log.w("Basrelief", "marker attribute index " + markerAPos
                    + " collides with a mesh attribute; re-binding after each marker draw");
        }

        // A new surface means a new EGL context, and everything this renderer
        // believed about the old one is now false: every texture and buffer name
        // cached from it is meaningless here, and the surface is empty again.
        //
        // All four fields therefore reset together, and each one is a way this has
        // already gone wrong. textureColor and vertexCount: reloadFromPrefs finds
        // the pair it wants is "already showing" and returns without uploading,
        // into a context holding no texture, so the wallpaper is black. loadedOnce:
        // it gates the one blocking decode whose entire purpose is to stop the
        // system snapshotting an empty surface, and a new surface is empty, so
        // keeping it set skips exactly the guarantee that is needed. The result in
        // both cases is a black wallpaper beside a diagnostic reporting the
        // correct photo loaded.
        //
        // This is the same failure as hasInstalled, one level up: there an unset
        // field read as "the demo is loaded", here state belonging to a destroyed
        // context read as still valid. Losing the context is routine for a live
        // wallpaper, because the system tears the engine down and rebuilds it, so
        // this is a normal event and not an error path.
        textureColor = -1;
        vertexCount = 0;
        hasInstalled = false;
        loadedOnce = false;

        reloadFromPrefs();
    }

    /**
     * Bind the mesh attribute pointers. Called once from {@link #install}, and
     * after a marker draw when the indices collide — deliberately NOT per frame.
     *
     * <p>Re-specifying these every frame made the driver re-validate ~12.6 MB of
     * FloatBuffer on every frame for geometry that only changes on load. That was
     * the largest per-frame cost measured on a Snapdragon 835: 9.2 ms of CPU
     * submit, of which the vertex path was the overwhelming majority.
     */
    private void bindMeshAttribs() {
        posBuffer.position(0);
        uvBuffer.position(0);
        normalBuffer.position(0);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, posBuffer);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, uvBuffer);
        GLES20.glEnableVertexAttribArray(aNormal);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, normalBuffer);
    }

    /**
     * Put a resolved framing onto the GL thread's state.
     *
     * <p>Taken from {@link Framing} rather than recomputed per surface, because
     * the two surfaces computing it themselves is how they came to disagree.
     */
    void apply(Framing f) {
        depth = f.depth;
        zoom = f.zoom;
        motion = f.motion;
        orbitX = f.orbitX;
        orbitY = f.orbitY;
        setPan(f.panX, f.panY);
    }

    public void reloadFromPrefs() {
        SharedPreferences p = context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
        // The index owns the selection, so one dereference gives the files and the
        // settings together and cannot come back naming a photo that was deleted.
        // Loaded once: this runs on the GL thread at cold start, and each load
        // re-reads and re-validates the whole gallery.
        final AppState state = AppState.load(p, context.getFilesDir());
        final LibraryItem active = state.activeItem();
        // One resolution, shared with the wallpaper: they used to disagree about
        // the bundled item, and the studio was the one that got it wrong.
        apply(Framing.of(state.activeItem(), state.config.motionScale()));
        if (active == null || active.isBundled()) {
            // No file to resolve: the marble bust, read from res/raw. This is a
            // renderable state and not a failure, and it is where a delete of the
            // active item lands.
            load(null, null);
            return;
        }
        // Stored as bare filenames; resolve against this install's filesDir so a
        // restored backup still finds them. Null means it is not present here,
        // which retainExisting has already pruned from the index.
        java.io.File img = PrefFiles.resolve(context.getFilesDir(), active.imageName);
        java.io.File dep = PrefFiles.resolve(context.getFilesDir(), active.depthName);
        load(img == null ? null : img.getAbsolutePath(),
                dep == null ? null : dep.getAbsolutePath());
    }

    /**
     * Load a pair, inlining the very first one when the surface must not be empty.
     *
     * <p>Both callers go through here, and the bundled demo going through here is
     * the point. It used to take the async path unconditionally, which for a live
     * wallpaper means the system snapshots the surface roughly 100 ms after it
     * appears and the decode has not finished, so the snapshot is of an empty
     * surface and the home screen is black -- while the engine went on to install
     * the photo a second later and reported it loaded. The symptom appeared
     * intermittent, because a warm PairCache could make the async load land inside
     * the window, which is a far worse bug than a consistent one: it looks like a
     * rendering fault rather than a missing guarantee.
     */
    private void load(String imgAbs, String depAbs) {
        // Off the GL thread: the read, decode and mesh build are seconds of work
        // and must not sit between two frames of a live wallpaper. The dispatcher
        // comes from the host (see setGlDispatch), which both surfaces set before
        // this can run.
        if (syncFirstLoad && !loadedOnce) {
            loadedOnce = true;
            // Inline on purpose: see syncFirstLoad. Blocks this thread for one
            // decode so the surface is never empty when the system snapshots it.
            applyPending(decodePair(imgAbs, depAbs));
        } else {
            loadPairAsync(imgAbs, depAbs);
        }
    }

    /** The live wallpaper sets this; the studio must not. */
    public void setSyncFirstLoad(boolean b) {
        this.syncFirstLoad = b;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (height == 0) height = 1;
        GLES20.glViewport(0, 0, width, height);
        surfaceW = width;
        surfaceH = height;
        // Now that a bound exists, apply it: a pan stored on a large screen and
        // restored on a small one must not be allowed to push the relief
        // off-screen. Safe to call from the GL thread — panX/panY are only
        // written here and by setPan, both on this thread.
        setPan(panX, panY);
        surfaceAspect = (float) width / height;
        recomputeScale();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // --- frame clock: pace draws explicitly ---
        long now = System.nanoTime();
        // NOTE: there is deliberately NO frame-rate gate here. See the note on
        // FPS_NORMAL below before adding one back.

        recomputeScale();
        float dt = Math.min((now - lastFrameNanos) / 1e9f, 0.1f);
        lastFrameNanos = now;
        float alpha = 1f - (float) Math.exp(-dt / SMOOTHING_TAU);
        // Fast attack: a big error means the user is moving, so follow it at once
        // instead of easing in over ~460 ms. See ATTACK_ERROR.
        smoothX = approach(smoothX, targetX, alpha);
        smoothY = approach(smoothY, targetY, alpha);

        // Projection, view, z-clamp and the model scale all live in the
        // pure-JVM CameraModel (unit-tested). One call fills a reusable Frame,
        // so drawing allocates nothing.
        CameraModel.Frame f = camera.frame(surfaceW, surfaceH, panX, panY,
                orbitX, orbitY, smoothX, smoothY);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (textureColor < 0) return;

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, f.mvp, 0);
        GLES20.glUniform1f(uModelScale, f.scale);
        GLES20.glUniform1f(uIntensity, f.zScale);
        GLES20.glUniform3f(uCamPos, f.eyeX, f.eyeY, f.eyeZ);
        GLES20.glUniform3f(uLightDir, -0.35f, 0.5f, 0.75f);
        GLES20.glUniform1f(uFade, fade);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureColor);
        GLES20.glUniform1i(uColor, 0);

        // Attribute pointers are NOT set here: they were bound by install() and
        // stay bound. See bindMeshAttribs for why that is safe and what it buys.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);

        if (showOrbitMarker) drawOrbitMarker(f);
    }

    private void drawOrbitMarker(CameraModel.Frame f) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(markerProgram);

        float cx = orbitX * aspect * f.scale;
        float cy = orbitY * f.scale;
        float r = 0.035f * f.scale;
        Matrix.setIdentityM(markerMat, 0);
        Matrix.translateM(markerMat, 0, cx, cy, 0f);
        Matrix.scaleM(markerMat, 0, r, r, 1f);
        Matrix.multiplyMM(markerMvp, 0, f.viewProjection, 0, markerMat, 0);
        GLES20.glUniformMatrix4fv(markerUMVP, 1, false, markerMvp, 0);

        ringBuffer.position(0);
        GLES20.glEnableVertexAttribArray(markerAPos);
        GLES20.glVertexAttribPointer(markerAPos, 2, GLES20.GL_FLOAT, false, 0, ringBuffer);

        GLES20.glUniform4f(markerUColor, 0.20f, 0.80f, 1f, 0.95f);
        GLES20.glLineWidth(2f);
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, RING_SEGMENTS);

        GLES20.glUniform4f(markerUColor, 1f, 1f, 1f, 1f);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1);

        GLES20.glDisableVertexAttribArray(markerAPos);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        // Only needed if the two programs' attribute indices overlap; see
        // onSurfaceCreated. Costs three calls, and only in the studio.
        if (markerClobbersMesh) bindMeshAttribs();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Put a decoded pair on screen. Returns false if nothing was installed, so the
     * caller does not record a pair as loaded that never made it into a texture.
     */
    private boolean install(Bitmap colorBmp, byte[] grayOut, int w, int h) {
        if (colorBmp == null) return false;
        // Nothing here recycles a bitmap, and that is load-bearing rather than
        // tidiness.
        //
        // A decoded pair is not owned by whoever decoded it. PairCache hands the
        // same one to the other surface in the process precisely so the
        // wallpaper's first frame can be correct without decoding twice, so the
        // studio uploads this bitmap and the engine then tries to use it -- and
        // this used to recycle it on the way out, which made the engine throw
        // "cannot use a recycled source in createBitmap" out of flipVertically
        // and killed the app on load.
        //
        // Recycling buys nothing here anyway. upload() copies the pixels into a
        // GL texture, after which the bitmap is unreachable garbage that the
        // collector reclaims on its own. Bitmap.recycle() is a pre-Honeycomb
        // idiom for a bitmap heap that no longer exists, and its only remaining
        // effect is to make the bitmap impossible to share -- which is the
        // opposite of what this app now needs.
        Bitmap upright = flipVertically(colorBmp);
        colorBmp = upright;

        aspect = (float) colorBmp.getWidth() / colorBmp.getHeight();
        if (textureColor >= 0) GLES20.glDeleteTextures(1, new int[] { textureColor }, 0);
        textureColor = upload(colorBmp);
        // TEMPORARY TRACING: a texture name of 0 is never a success, it is the
        // default texture, which has no image and samples pure black. Cheap to
        // assert here and impossible to infer from outside.
        if (textureColor == 0) {
            // The upload happened with no usable current context, so there is no
            // texture and nothing to draw. Report it as a failed install so the
            // pair is not recorded as loaded and the next visibility change
            // retries, rather than leaving a black wallpaper that the renderer
            // believes is correct.
            textureColor = -1;
            return false;
        }

        // Drawn at full opacity, deliberately: there is no fade-in.
        //
        // There used to be a 0.25s fade up from black, and it is incompatible with
        // a live wallpaper. The system snapshots the surface roughly 100 ms after
        // it appears, so the first frame, drawn at fade = 0 and therefore black, was
        // what got snapshotted, and the engine was gone before the fade could
        // finish. The wallpaper was black while the engine reported the correct
        // photo uploaded, which is the most confusing way this could have failed.
        //
        // A cosmetic transition is not worth a broken wallpaper. The uniform is
        // kept so a future cross-fade has somewhere to go, but it is pinned to 1,
        // and any reintroduced fade has to fit inside the snapshot window.
        fade = 1f;
        ReliefMesh surf = ReliefMesh.build(grayOut, w, h, GRID, aspect);
        vertexCount = surf.vertexCount;
        posBuffer = toBuffer(surf.positions);
        uvBuffer = toBuffer(surf.uvs);
        normalBuffer = toBuffer(surf.normals);
        recomputeScale();
        // New buffers: (re)bind the pointers now, once, rather than every frame.
        bindMeshAttribs();
        return true;
    }

    private static Bitmap flipVertically(Bitmap src) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.preScale(1f, -1f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
    }

    /** Push the current view state into the camera model and refresh `scale`. */
    private void recomputeScale() {
        if (aspect <= 0f) return;
        camera.setAspect(aspect);
        camera.setSurfaceAspect(surfaceAspect);
        camera.setZoom(zoom);
        camera.setDepth(depth);
        camera.setMotion(motion);
        scale = camera.computeScale();
    }

    private Bitmap decodeRaw(int resId) {
        try {
            return BitmapFactory.decodeStream(context.getResources().openRawResource(resId));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readFile(String path) {
        try {
            InputStream in = new FileInputStream(new File(path));
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] readGray(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] argb = new int[w * h];
        bmp.getPixels(argb, 0, w, 0, 0, w, h);
        byte[] gray = new byte[w * h];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) (argb[i] >> 16 & 0xff);
        }
        return gray;
    }

    private static FloatBuffer toBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        return fb;
    }

    private static FloatBuffer buildRing(int segments) {
        float[] pts = new float[segments * 2];
        for (int i = 0; i < segments; i++) {
            double a = Math.PI * 2 * i / segments;
            pts[i * 2] = (float) Math.cos(a);
            pts[i * 2 + 1] = (float) Math.sin(a);
        }
        return toBuffer(pts);
    }

    private static int upload(Bitmap bmp) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        return tex[0];
    }

    private static int createProgram(String vertexSrc, String fragmentSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            throw new RuntimeException("program link failed: " + GLES20.glGetProgramInfoLog(program));
        }
        return program;
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
