package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLES30;
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
import java.nio.IntBuffer;
import java.util.Arrays;

import com.amoshydra.basrelief.core.render.CameraModel;
import com.amoshydra.basrelief.core.render.Framing;
import com.amoshydra.basrelief.core.render.LightDirection;
import com.amoshydra.basrelief.core.render.Picture;
import com.amoshydra.basrelief.core.render.RenderStats;
import com.amoshydra.basrelief.core.store.FramingRowValue;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.PrefFiles;
import com.amoshydra.basrelief.core.render.ReliefMesh;
import com.amoshydra.basrelief.core.render.Splat;
import com.amoshydra.basrelief.core.render.SplatCamera;
import com.amoshydra.basrelief.core.splat.SplatCloud;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * Renderer for a real displaced relief surface (continuous, no tearing): the
 * photo is mapped onto a triangle mesh with per-vertex normals so a directional
 * light shades it -- genuine geometry cues (silhouette, parallax, shading)
 * instead of a flat-card texture trick.
 *
 * <p><b>Two backends, one class, chosen at build time.</b> The shipping path is
 * GLES3: the mesh is a static grid and the vertex shader samples a
 * {@code (GRID+1)^2} float depth field to displace Z. Shading is <b>separate</b>:
 * a supplied normal map is sampled per fragment, and a photo with no normal map
 * is drawn <b>flat lit</b> — displaced correctly, lit as a plane. That is a
 * deliberate state and not a fallback; see {@link #FRAG}. This is the whole
 * reason for GLES3 -- its minimum vertex texture unit count is non-zero, GLES2's
 * is zero, so GLES2's vertex shader could never sample depth at all. The legacy
 * GLES2 + CPU-built mesh path is retained for A/B comparison and as a fallback,
 * and is selected by {@link BuildConfig#RENDERER_GLES3}, which also sets the EGL
 * context version in both hosts. The two share everything except the programs
 * and the per-load mesh strategy (see {@link #install} and {@link #drawRender});
 * framing, glides, cross-fades, decode and prefs are one implementation so they
 * cannot drift. Once the comparison is done the false branch is a deletion.
 *
 * <p><b>GLES3 is a capability, not a power mode.</b> The GPU does the same work
 * or less; there is deliberately no claim of a battery win here. The battery
 * story is unchanged and lives in {@code TiltController}: the draw rate is
 * bounded by the gyro and the expensive sensor sleeps after 10s of stillness.
 *
 * <p><b>The GLES3 path has two modes, and the relief is the default.</b> The
 * relief is one continuous displaced surface; the point-splat mode
 * ({@code render_3d}, read in {@link #reloadFromPrefs}) draws one small card per
 * field sample instead, each at its own depth with a flat colour sample and a
 * normal gradient -- no triangle spans a depth cliff, so the shear a silhouette
 * shows on the relief cannot happen, at the price of holes where the single view
 * never saw behind anything. Both modes go through ONE program and one camera:
 * the mode is a uniform inside the vertex shader's branch, so with the flag off
 * the relief is the same program, the same buffers and the same draw it always
 * was. See {@link #VERT3} and {@link com.amoshydra.basrelief.core.render.Splat}.
 *
 * <p>Settings (SharedPreferences "depthwall") are read at startup; live
 * changes arrive through setDepth / setZoom / setMotion / loadPair,
 * always invoked on the GL thread.
 */
public final class ReliefRenderer implements GLSurfaceView.Renderer {

    /**
     * Which backend this build draws with. A compile-time constant from the same
     * switch the hosts use to set the EGL context version, so the renderer and
     * the context cannot be chosen independently.
     */
    private static final boolean GLES3 = BuildConfig.RENDERER_GLES3;


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
    // Relief grid resolution: (GRID+1)^2 vertices, and the depth field's size.
    // It is the relief's real resolution ceiling: the depth clips and stills are
    // 1080p-class and were being thrown away down to 257 field points, while
    // shading is already per-fragment. Measured on a Pixel 7 Pro (the same clip,
    // all three decoders going):
    //   grid 256 -- pair field ~93-235 ms, clip draws 33-60/s, skew 0
    //   grid 384 -- pair field ~314-442 ms, clip draws 33-60/s, skew 0  <- here
    //   grid 512 -- pair field ~413-455 ms, clip draws 33/s,    skew 0
    // 384 buys 2.25x the sampling with the frame rate untouched and the pair's
    // blur still off the UI thread (a photo lands ~0.2 s later); 512's extra
    // 1.8x of per-frame field work buys facets ~1 px finer, so the battery it
    // costs is the honest reason not to. The GLES2 fallback tessellates on the
    // CPU and would want the old 256 back if that path ever matters again.
    //
    // The commit that claimed this value shipped 512 instead: a patch that failed
    // to match printed success and the constant stayed. The numbers above are what
    // was actually measured at each value.
    private static final int GRID = 384;

    /**
     * The legacy {@code render_3d} pref, and the <b>migration source</b> for the
     * per-item render mode.
     *
     * <p>It used to be the app's one 3D switch. A global render value is the wrong
     * home — a gallery would draw every photograph the same way whichever the single
     * switch last said — so {@code Library}'s v3 to v4 migration reads this once and
     * copies it onto every item, exactly as Motion moved out of {@code Config} in v3
     * and for the same reason. It is read <b>only</b> there now: the product's mode is
     * per-item, and the Stage-0 card grid it also selected has its own dev key
     * ({@link #KEY_SPLAT_GRID}) so that a user who had this on does not get the card
     * mode over every photograph they have not made a cloud for — the doc's rule is
     * that Splat without a cloud shows the relief.
     *
     * <p>Package-private because {@code AppState} reads it for the migration; no
     * other class spells the string out -- the same rule the three switches in
     * {@link Settings} follow.
     */
    static final String KEY_RENDER_3D = "render_3d";

    /**
     * The Stage-0 card grid, as a dev pref: one card per depth-field sample, GLES3
     * only. Off by default and not in the UI. See {@link #splatGridMode}.
     *
     * <p>A key of its own rather than {@code render_3d}, and that is a decision: the
     * latter is the migration source, and letting it also select the card mode would
     * make every SPLAT row with no cloud draw the card grid instead of its relief —
     * which is exactly the look the product's "Splat without a cloud shows the
     * relief" rule forbids.
     */
    static final String KEY_SPLAT_GRID = "splat_grid";

    /**
     * The 3D mode's detail level: an index into {@link Splat#GRID_LEVELS}, written
     * by the Settings screen's slider. 0 -- the field's own sampling -- is the look
     * the mode shipped with, so an absent key draws exactly that.
     */
    static final String KEY_RENDER_3D_LEVEL = "render_3d_level";

    /**
     * The Gaussian splat's render scale, as it existed before it became per-item.
     *
     * <p><b>A migration source now, read only by {@code AppState} for Library's
     * v4->v5 migration</b> — the {@link #KEY_RENDER_3D} precedent one version later.
     * The value the renderer draws with is
     * {@code SurfaceSettings.splatScale}, resolved through {@link Framing}. Nothing
     * reads this pref at draw any more.
     */
    static final String KEY_SPLAT_SCALE = "splat_scale";

    /**
     * The splat look's other three pre-item values, migration sources read only by
     * {@code AppState} for the v4->v5 migration.
     *
     * <p>{@link #KEY_SPLAT_EPS2D} is the 2D low-pass floor (gsplat's {@code eps2d}),
     * stored as a fraction of the cell a splat stands for rather than in pixels --
     * a pixel count means a different thing at every render resolution and every
     * zoom, which is what made the knob's maximum too small on the phone. The draw
     * resolves it to exactly that fraction of the on-screen cell; there is
     * deliberately no separate sampling minimum above it. See {@link Splat#eps2dFor}.
     * {@link #KEY_SPLAT_BLUR} is the post-blur radius in render-target pixels.
     * {@link #KEY_SPLAT_DEPTH} multiplies the cloud's depth: 1 is the depth the
     * node reconstructed, 0 collapses the cloud onto its reference plane (the flat
     * photograph).
     *
     * <p>The product defaults are {@link Splat#EPS_2D_DEFAULT},
     * {@link Splat#BLUR_DEFAULT} and {@link Splat#DEPTH_MUL_DEFAULT}; an untouched
     * install's migration writes nothing, because a value equal to the default is
     * omitted from the blob.
     */
    static final String KEY_SPLAT_EPS2D = "splat_eps2d";
    static final String KEY_SPLAT_BLUR = "splat_blur_px";
    static final String KEY_SPLAT_DEPTH = "splat_depth_mul";

    /**
     * The generation-side dev knob: the budget the next cloud build sends to
     * {@code SharpStill}. Read by {@link SplatImport} at submission and by the
     * confirm sheet, so the number the user compares by eye is the number that runs.
     * Default {@link com.amoshydra.basrelief.core.depth.ComfyWorkflow#SHARP_BUDGET}.
     */
    static final String KEY_SPLAT_BUDGET = "splat_budget";

    // The Stage-0 `splat_file` debug pref lived here: a bare filename used as the
    // splat for the bundled row when that row named no cloud. Stage 1 retired it --
    // the demo's cloud ships in res/raw, the ordinary seed copies it and names it on
    // the row, and `setSplatForItem` resolves the item's own filename and nothing
    // else. A key with no reader is dead, so it is gone rather than kept "for a
    // migration": there is nothing to migrate, because the asset is the migration.

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
     * sensor after 10s of stillness and falls back to a low-power accelerometer
     * until a shake. OS battery-save is handled there too, by widening the
     * sensor period; there is deliberately nothing for the renderer to do about
     * it, because the one lever it has - the frame rate - is the wrong one.
     */

    /**
     * How long one photo takes to cross-fade into the next.
     *
     * <p>Short on purpose. This is not an animation the user asked for; it is
     * long enough to read as a dissolve rather than a cut and short enough not to
     * sit between the finger and the picture. The one hard constraint on it is
     * the system's wallpaper snapshot: it is taken roughly 100 ms after the
     * surface appears, so a fade must never cover the FIRST load of an engine --
     * a half-faded frame would be what the launcher keeps forever. See
     * {@link #install}, which only arms a fade when a pair is already on screen.
     *
     * <p>The timing is wall-clock rather than a frame count, so a 120 Hz surface
     * and a 60 Hz surface show the same duration.
     */
    private static final float CROSS_FADE_MS = 200f;

    private static final String VERT =
            "uniform mat4 uMVP;\n" +
            "uniform float uModelScale;\n" +
            "uniform float uIntensity;\n" +
            "attribute vec3 aPosition;\n" +
            "attribute vec2 aUV;\n" +
            "varying vec2 vUv;\n" +
            // The normal's own UV. It is a straight copy of aUV here and is a
            // separate varying only so this shader and VERT3 stay the same shape:
            // the GPU path needs the two apart (the splat's colour is one texel
            // for a whole card while its normal keeps a gradient), and a
            // fragment shader that sampled a different varying on each path
            // would be two shaders to keep in step instead of one.
            "varying vec2 vNormalUv;\n" +
            "void main() {\n" +
            "  vUv = aUV;\n" +
            "  vNormalUv = aUV;\n" +
            "  gl_Position = uMVP * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String FRAG =
            "precision highp float;\n" +
            "varying vec2 vUv;\n" +
            "varying vec2 vNormalUv;\n" +
            "uniform sampler2D uColor;\n" +
            // The supplied normal map, or nothing. uHasNormal is 0 for a photo with
            // depth and no normals, which is the flat-lit state -- a deliberate
            // render, not a fallback. There is no derivative of the depth here and
            // deliberately so: the whole point of supplied normals is that the
            // gradient of 8-bit depth is never taken.
            "uniform sampler2D uNormal;\n" +
            "uniform float uHasNormal;\n" +
            "uniform float uIntensity;\n" +
            "uniform float uFade;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform float uBrightnessGain;\n" +
            "uniform float uShadowLift;\n" +
            "void main() {\n" +
            "  vec3 n;\n" +
            "  if (uHasNormal > 0.5) {\n" +
            // normal_opengl encodes [-1,1] as [0,1]; the bitmap was flipped like
            // the colour one so image-top is v=1. This is OpenGL convention (+Y
            // up), so G is NOT negated -- picking directx instead inverts the
            // vertical light cue. A device-only check.
            "    n = texture2D(uNormal, vNormalUv).rgb * 2.0 - 1.0;\n" +
            // MoGe predicts the surface before the Depth stretch; the inverse
            // transpose of the z scale is nz/intensity, exactly as the old derived
            // path folded intensity into its derivative. Without it the shading
            // changes shape as the Depth slider moves.
            "    n = normalize(vec3(n.x, n.y, n.z / max(uIntensity, 1e-3)));\n" +
            "  } else {\n" +
            "    n = vec3(0.0, 0.0, 1.0);\n" +
            "  }\n" +
            "  vec3 l = normalize(uLightDir);\n" +
            "  float diff = max(dot(n, l), 0.0);\n" +
            "  vec3 color = texture2D(uColor, vUv).rgb;\n" +
            // Applied to the photograph, before the lighting: a lift that went
            // through the diffuse multiply would be re-darkened by it. Both are
            // identities at their defaults, so an unadjusted photo is untouched.
            "  color *= uBrightnessGain;\n" +
            "  color += uShadowLift * (1.0 - color);\n" +
            "  color *= 1.0 + 0.25 * diff;\n" +
            // uFade is the ALPHA of a cross-fade, not a brightness multiplier:
            // the outgoing pair draws opaque, the incoming pair draws over it at
            // alpha = t with GL_BLEND, so the screen shows old*(1-t) + new*t.
            // Multiplying RGB instead (the old semantics, pinned to 1) would
            // darken the incoming pair by its own alpha and blend to a dip.
            "  gl_FragColor = vec4(color, uFade);\n" +
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

    // ---------------------------------------------------------------- GLES3
    // Same lighting and picture maths as the strings above, deliberately: the
    // fragment shader is the GLES2 one translated to `in`/`out`/`texture`, so a
    // change to one must be made to the other. The vertex shader is the real
    // difference -- it DISPLACES each vertex from a float depth field instead of
    // receiving a CPU-built mesh.

    /**
     * GLES3 relief vertex shader: the GPU half of {@code ReliefMesh.build}.
     *
     * <p>The depth texture is the blurred {@code (GRID+1)^2} float field -- in
     * {@code 0..255}, NOT re-quantised to 8-bit, because that would put the
     * banding back. A vertex at grid {@code (gx, gy)} reads texel
     * {@code (gx, gy)}, so the sample is taken at texel centres rather than at
     * the mesh's {@code u = gx/GRID}. This reproduces {@code ReliefMesh}'s
     * resample exactly, so the surface height matches the CPU path.
     *
     * <p><b>Displacement only.</b> This shader no longer derives a normal from the
     * depth gradient; shading is a supplied normal map, sampled per fragment, or the
     * flat plane when there is none. Removing the derivative is the point: the
     * central-difference normal was a derivative of quantised depth, and blurring
     * the field existed to hide terraces that derivative exposed.
     */
    private static final String VERT3 =
            "#version 300 es\n" +
            "uniform mat4 uMVP;\n" +
            "uniform float uModelScale;\n" +
            "uniform float uIntensity;\n" +
            "uniform sampler2D uDepth;\n" +
            "uniform float uGrid;\n" +
            "uniform float uAspect;\n" +
            // The splat mode's switch and the numbers it is placed with. One
            // PROGRAM for both modes on purpose: the relief must be the same
            // program, the same buffers and the same draws when the flag is off,
            // and a second program would be a second copy of the relief's maths
            // to keep in step with this one. uSplat is 0 for the relief, 1 for
            // the cards.
            "uniform float uSplat;\n" +
            "uniform float uSplatSize;\n" +
            "uniform float uSplatGrid;\n" +
            // The eye the cards are placed against: the camera at REST -- tilt
            // dropped, origin and zoom kept. See the splat branch below; it is
            // deliberately not the moving camera, which is passed to nothing at
            // all here.
            "uniform vec3 uSplatEye;\n" +
            // The photograph's camera, as two numbers that are both already
            // resolved in :core (Splat, with its own test): the effective focal in
            // pixels -- the row's own, or the 50-degree default when it has none --
            // and that default itself. Their ratio is the depth scale, so this
            // shader carries no field-of-view constant of its own to drift from
            // Splat.DEFAULT_FOV_DEG.
            "uniform float uFocal;\n" +
            "uniform float uRefFocal;\n" +
            "in vec2 aPosition;\n" +   // (u-0.5, v-0.5); x is scaled by uAspect here
            "in vec2 aUV;\n" +
            "out vec2 vUv;\n" +
            // TWO UVs, and the split is the whole point of the mode. The colour is
            // sampled at the sample's own texel for all four corners of a card --
            // flat, so the colour cannot stretch across a card however the card is
            // placed. The normal keeps a per-corner UV, so the lighting varies
            // across the card and a cloud of cards reads as a model rather than as
            // facets. On the relief path both are aUV, exactly as before.
            "out vec2 vNormalUv;\n" +
            "void main() {\n" +
            "  float n = uGrid + 1.0;\n" +
            "  float gx = aUV.x * uGrid;\n" +
            "  float gy = (1.0 - aUV.y) * uGrid;\n" +
            "  vec2 duv = vec2(gx + 0.5, gy + 0.5) / n;\n" +
            // ONE FETCH PER SAMPLE, not per corner: the four vertices of a card
            // share aUV, so duv is the sample's texel centre for all of them. This
            // is what keeps a triangle from spanning a depth cliff -- the whole
            // card sits at one depth, and the surface being torn open is the point
            // of the mode rather than an artifact of it.
            "  float h = textureLod(uDepth, duv, 0.0).r / 255.0 - 0.5;\n" +
            "  vec3 a;\n" +
            "  if (uSplat > 0.5) {\n" +
            // EVERY CARD IS DERIVED FROM gl_VertexID, and there is no buffer at
            // all: one sample is six vertices -- two triangles, corners 0,1,2 then
            // 1,3,2 -- so the sample is gl_VertexID / 6 and its corner is that
            // pattern's entry for gl_VertexID % 6. That is what makes the
            // RESOLUTION a uniform, which is the point: the alternative is one
            // quad per sample in a VBO, which is 18 MB and a measured 7.4 s build
            // at the bottom level (an ANR), 71 MB at the top one. It is drawn with
            // glDrawArrays, so there is no index buffer either.
            "    int card = gl_VertexID / 6;\n" +
            "    int k = gl_VertexID - card * 6;\n" +
            "    int c = (k == 0) ? 0 : (k == 1) ? 1 : (k == 2) ? 2 : (k == 3) ? 1 : (k == 4) ? 3 : 2;\n" +
            // The splat grid has uSplatGrid + 1 samples a side at a spacing of
            // 1/uSplatGrid -- the field's own convention, so level 0 is one card
            // per field sample and the picture does not move when the level
            // changes.
            "    float sx = mod(float(card), uSplatGrid + 1.0);\n" +
            "    float sy = floor(float(card) / (uSplatGrid + 1.0));\n" +
            "    vec2 cuv = vec2(sx / uSplatGrid, 1.0 - sy / uSplatGrid);\n" +
            "    vec2 corner = vec2(float(c - (c / 2) * 2) - 0.5, float(c / 2) - 0.5);\n" +
            // The FIELD's texel for this sample: uGrid is the field's grid while
            // the card grid may be finer, so this is a second, independent
            // mapping. A finer card grid interpolates the blurred field rather
            // than reading anything the field does not hold.
            "    float fgx = cuv.x * uGrid;\n" +
            "    float fgy = (1.0 - cuv.y) * uGrid;\n" +
            "    vec2 duv = vec2(fgx + 0.5, fgy + 0.5) / (uGrid + 1.0);\n" +
            "    float h = textureLod(uDepth, duv, 0.0).r / 255.0 - 0.5;\n" +
            // THE CARD IS PLACED ON THE RAY FROM THE EYE THE PICTURE IS PINNED
            // TO -- uSplatEye, the camera at rest -- through its own
            // reference-plane point, closer to that eye by the card's own depth.
            // That is "the card sits at camera distance minus its own depth"
            // generalised: the old line scaled the sample's position about the
            // MODEL CENTRE, which is the same construction with the eye assumed
            // to sit on the model's z axis. It does not, once Origin moves the
            // camera -- Origin aims the camera at a point on the plane and puts
            // it there -- so the whole cloud used to be placed as if the origin
            // were still the image's centre, whatever the user set. The
            // reference eye is therefore the camera the picture is the
            // photograph for: tilt dropped, origin and zoom kept.
            //
            // THE MOVING EYE IS NOT IT, and the difference is the whole mode. A
            // point on the eye's ray projects to that eye's pixel whatever its
            // depth, so placing every card on the MOVING eye's rays would make
            // the render identical at every Depth and every tilt: no parallax,
            // no gaps, no reveal. The depth has to be fixed in world space and
            // the camera has to move through it.
            //
            // Position and card size both carry the depth ratio, so a card's
            // angular size is its footprint's at any depth: the coverage constant
            // tiles the reference plane (h == 0 lands exactly where the relief
            // drew it, so the picture does not move when the mode is switched),
            // and the gaps that open elsewhere are the scene genuinely
            // disoccluding.
            //
            // A card off the reference plane moves along ITS OWN RAY, which is
            // why the picture at rest is the photograph whatever the depth: only
            // the camera moving reveals the depth, as parallax and as occlusion.
            // The focal's whole effect is how much depth there is to reveal (the
            // line below), not where a pixel lands.
            //
            // WHAT THE FOCAL DOES HERE, AND WHY IT IS NOT IN THE RAY.
            // X = (u-0.5)/(f/W)*Z is the unprojection through the PHOTO's camera.
            // Drawing it through this renderer's camera -- which is the same
            // camera the relief and every gesture use, at a fixed 45 degrees --
            // would re-frame the picture: a phone's main camera is nearer 70
            // degrees than 45, so the same photo would arrive up to 70% larger and
            // cropped. The ray is therefore this camera's own, and the stored
            // focal scales the DEPTH, which is the one length the photograph
            // actually carries: its image plane sits f/H image heights in front of
            // the eye. Scaling the whole cloud about the eye is exactly the same
            // statement said the other way round -- the stored focal's ray,
            // compensated back onto this camera -- which is why the two are one
            // implementation and not two ideas.
            "    float depthGain = uRefFocal > 0.0 ? uFocal / uRefFocal : 1.0;\n" +
            // uModelScale * uIntensity IS the world depth of h == 1: the MVP
            // carries S(scale, scale, scale * zScale), so a model z of
            // zOff / denom becomes a world offset of zOff. Computed here
            // because the eye distance is in world units and the model z is
            // not.
            "    float denom = uModelScale * uIntensity;\n" +
            "    float zOff = h * denom * depthGain;\n" +
            // THE IMAGE ASPECT GOES ON X AND ONLY ON X -- the same statement the
            // relief branch makes below, and the one asymmetry the card has.
            // Written as (position + corner * uSplatSize) * uAspect it squashed
            // every non-square photograph to its own aspect: a portrait 9:16 filled
            // the screen's width and 56% of its height. The card's footprint is the
            // cell it replaces, which is aspect/grid wide and one cell tall, so the
            // aspect belongs to the position and to the x half of the footprint
            // alike -- applied to the whole vec2 the cards also stop tiling
            // vertically.
            "    vec2 q = vec2((cuv.x - 0.5) * uAspect, cuv.y - 0.5)\n" +
            "           + vec2(corner.x * uSplatSize * uAspect, corner.y * uSplatSize);\n" +
            // q already carries the corner, so every one of the four corners is
            // placed on its OWN ray and the card covers exactly the cell it
            // replaces at rest.
            "    vec3 refWorld = vec3(q.x * uModelScale, q.y * uModelScale, 0.0);\n" +
            "    vec3 toRef = refWorld - uSplatEye;\n" +
            "    float dist = max(length(toRef), 1e-4);\n" +
            // A card must stay in front of that eye. Pushed past it the card
            // renders mirrored and inside out, and it is a whole card appearing in
            // the middle of the picture, so the distance is clamped here rather
            // than left to the near plane to decide. The margin is the 0.15 world
            // units the fixed camera distance used to leave, and it must be
            // applied to the RAY's distance now that the eye is not a constant.
            "    float closer = max(dist - zOff, 0.15);\n" +
            "    vec3 cardWorld = uSplatEye + toRef * (closer / dist);\n" +
            // Back to model space: x and y are the world position over the model
            // scale, and z is over the world depth of h == 1 -- the same
            // uModelScale * uIntensity the MVP will scale it by. denom == 0 is
            // Depth 0, where there is no depth axis to divide by and the card
            // belongs on the reference plane; it lands there because zOff is 0
            // and cardWorld.z is exactly 0 -- only the division has to be guarded.
            "    a = vec3(cardWorld.x / uModelScale, cardWorld.y / uModelScale,\n" +
            "             denom > 1e-6 ? cardWorld.z / denom : 0.0);\n" +
            // THE COLOUR'S v IS NOT THE FIELD'S v, and this is the second
            // convention the relief also carries: the depth field is uploaded
            // top-first (t = 0 is the image's top row, which is why duv is small
            // at cuv.y = 1), while the colour and normal bitmaps are flipped at
            // upload so their v = 1 is the image's top. Sampling the colour at
            // duv would draw the photograph upside down against its own depth --
            // so the flat texel is named in the IMAGE's convention here, and the
            // normal's gradient follows the plane's corner, which is the same
            // direction.
            "    vec2 suv = vec2(duv.x, 1.0 - duv.y);\n" +
            "    vUv = suv;\n" +
            "    vNormalUv = suv + corner * (uSplatSize * 0.5);\n" +
            "  } else {\n" +
            "    a = vec3(aPosition.x * uAspect, aPosition.y, h);\n" +
            "    vUv = aUV;\n" +
            "    vNormalUv = aUV;\n" +
            "  }\n" +
            // a is the plane-space position the camera's MVP already scales --
            // uMVP carries S(scale, scale, scale*zScale), exactly as the GLES2
            // path's uMVP does. x carries the image aspect here so one static
            // grid fits every photo.
            "  gl_Position = uMVP * vec4(a, 1.0);\n" +
            "}\n";

    /**
     * GLES3 fragment shader: the GLES2 FRAG above, translated.
     *
     * <p>It samples the supplied normal map, or flat-lights the plane when there
     * is none. The two backends share this shading exactly, so a change to the
     * lighting is a change to both.
     */
    private static final String FRAG3 =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec2 vUv;\n" +
            "in vec2 vNormalUv;\n" +
            "uniform sampler2D uColor;\n" +
            "uniform sampler2D uNormal;\n" +
            "uniform float uHasNormal;\n" +
            "uniform float uIntensity;\n" +
            "uniform float uFade;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform float uBrightnessGain;\n" +
            "uniform float uShadowLift;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "  vec3 n;\n" +
            "  if (uHasNormal > 0.5) {\n" +
            // The NORMAL's UV, not the colour's: on the splat path the colour is
            // one texel for a whole card while the shading keeps the card's
            // gradient. On the relief path the two are the same value.
            "    n = texture(uNormal, vNormalUv).rgb * 2.0 - 1.0;\n" +
            "    n = normalize(vec3(n.x, n.y, n.z / max(uIntensity, 1e-3)));\n" +
            "  } else {\n" +
            "    n = vec3(0.0, 0.0, 1.0);\n" +
            "  }\n" +
            "  vec3 l = normalize(uLightDir);\n" +
            "  float diff = max(dot(n, l), 0.0);\n" +
            "  vec3 color = texture(uColor, vUv).rgb;\n" +
            "  color *= uBrightnessGain;\n" +
            "  color += uShadowLift * (1.0 - color);\n" +
            "  color *= 1.0 + 0.25 * diff;\n" +
            "  fragColor = vec4(color, uFade);\n" +
            "}\n";

    private static final String MARKER_VERT3 =
            "#version 300 es\n" +
            "uniform mat4 uMVP;\n" +
            "in vec2 aPos;\n" +
            "void main() {\n" +
            "  gl_PointSize = 7.0;\n" +
            "  gl_Position = uMVP * vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String MARKER_FRAG3 =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "  fragColor = uColor;\n" +
            "}\n";

    private final Context context;

    private int program;
    private int aPosition, aUV, uMVP, uModelScale, uIntensity, uColor, uLightDir;
    private int uFade = -1;
    private int uBrightnessGain = -1, uShadowLift = -1;
    /** The supplied normal map and whether there is one; both backends use these. */
    private int uNormal = -1, uHasNormal = -1;
    // GLES3-only relief uniforms.
    private int uDepth = -1, uGrid = -1, uAspect = -1;
    /**
     * GLES3-only splat uniforms: the mode switch, the card size and grid, the eye
     * the cards are placed against (the camera at REST, so the origin's pivot is
     * the reference the depth is placed around), and the photograph's camera as
     * the effective focal plus the default it is measured against.
     */
    private int uSplat = -1, uSplatSize = -1, uSplatEye = -1, uFocal = -1, uRefFocal = -1;
    private int uSplatGrid = -1;

    /**
     * The splat's grid, as samples a side, from the {@code render_3d_level} pref.
     * Read in {@link #reloadFromPrefs} and applied as a uniform, because the cards
     * are derived from {@code gl_VertexID} and need no buffer at all -- see the
     * vertex shader's splat branch. {@link Splat#GRID_LEVELS} is the ladder; the
     * default is the field's own sampling, which is the look the mode shipped with.
     */
    private int splatGrid = Splat.DEFAULT_FIELD_GRID;
    private boolean splatLevelLogged;

    /**
     * The static relief grid for the GLES3 path: a {@code (GRID+1)^2} vertex
     * interleaved {@code (x, y, u, v)} buffer and its index buffer, built once
     * per EGL context and never per load. This is what replaces the CPU's
     * ~393k-vertex non-indexed mesh: the geometry is identical for every photo
     * (only the field texture changes), so it is uploaded once.
     *
     * <p>VBO names belong to an EGL context, so like everything else here they
     * are dropped and rebuilt in {@link #onSurfaceCreated} -- a stale name drawn
     * into a new context is a black or corrupt surface.
     */
    private int gridVbo = 0, gridIbo = 0;
    private int gridIndexCount = 0;

    /**
     * The Stage-0 <b>card grid</b>: one card per depth-field sample, GLES3 only.
     *
     * <p><b>A dev mode now, not the product's 3D selector.</b> Stage 0 shipped this
     * as the "3D mode" because there was no cloud to draw yet; the real Gaussian
     * cloud ({@link #splatWantedPath}) superseded it, and the product's per-item
     * {@code RenderMode} is the live selector. This flag is reachable only through
     * the {@link #KEY_SPLAT_GRID} dev pref, which is not in the UI — it is kept
     * because the card mode is a documented experiment worth A/B-ing on a device,
     * exactly as {@link #KEY_RENDER_3D_LEVEL} is. (The splat look's own four values
     * used to be dev prefs too; they are per-item render values now, see
     * {@link com.amoshydra.basrelief.core.store.SurfaceSettings}.)
     *
     * <p><b>Its own key, not {@code render_3d}.</b> That pref is the migration source
     * for the per-item mode, and letting it also select the card grid would make every
     * SPLAT row with no cloud draw the card grid instead of its relief — which is the
     * look the product's "Splat without a cloud shows the relief" rule forbids. So the
     * card mode has a key of its own and is off unless someone asks for it by adb.
     *
     * <p>It draws only when there is no real cloud ({@code splatWantedPath == null}),
     * so a row that has one always shows the cloud. The mode a normal install sees is
     * per-item: SPLAT with a cloud draws the cloud, SPLAT without one draws the
     * relief, and RELIEF always draws the relief.
     */
    private volatile boolean splatGridMode = false;

    /**
     * The draw path the last applied {@link Framing} asked for, as
     * {@code Framing.RENDER_RELIEF} or {@code RENDER_SPLAT}.
     *
     * <p><b>A field because {@link #snapshot()} has to be able to say it</b> — the
     * {@code sourceKind} rule one axis over. It is written by {@link #apply(Framing,
     * Apply)} from the resolved framing, so the renderer's snapshot of what it is
     * drawing carries the mode the host asked for, and a relief↔cloud toggle makes
     * {@code sameAs} report a different picture rather than a no-op. It is NOT the
     * draw decision: whether the cloud actually draws is {@link #useSplat()}, which
     * needs the bytes to be here. The two are deliberately separate, exactly as the
     * clip's medium is a fact about the screen while the clip's playability is a
     * file question.
     */
    private volatile float renderMode = Framing.RENDER_RELIEF;

    /**
     * The Gaussian splat cloud, when the active item names one.
     *
     * <p><b>The item's own nullable {@code splat} filename is the whole shape.</b>
     * It resolves through {@link PrefFiles} like every other stored name, and the
     * cloud is drawn when the item's mode is SPLAT and the file is here — see
     * {@link #setSplatForItem} and {@link #useSplat}. The Stage-0 {@code splat_file}
     * debug pref is gone: the demo's cloud ships in {@code res/raw} and the ordinary
     * seed names it on the row, so there is no fallback to reach.
     *
     * <p>{@code splatWantedPath} is the resolve's answer (written on whatever
     * thread {@link #reloadFromPrefs} ran on); {@code splatLoadedPath} is what the
     * GL context actually holds. The two must agree before the cloud draws, which
     * is the {@code hasInstalled} rule one medium over: a path that has been
     * requested but not uploaded is not on screen.
     */
    private SplatRenderer splat;
    private volatile String splatWantedPath;
    private String splatLoadedPath;
    private String splatLoadedId;
    private String splatPendingPath;
    private String splatPendingId;
    private int splatSeq;
    /**
     * The wanted cloud's own lens, as {@code focal / height}, or 0 for unknown.
     *
     * <p>It rides beside {@code splatWantedPath} because it describes the same
     * thing: the cloud the item names. Volatile and read on the GL thread at draw,
     * exactly as the path is; a row that gains a lens is not a new cloud FILE, so it
     * needs no reload — the next frame simply draws the loaded cloud through the
     * new lens. A new cloud (a new filename) is the identity change, and that is
     * {@code splatWantedPath}'s job.
     */
    private volatile float splatWantedLens;

    /**
     * The splat look and shape, written from the resolved {@link Framing} and read
     * at draw.
     *
     * <p><b>Per photo and per surface now, not global prefs.</b> They are ordinary
     * render values: {@link #apply(Framing)} sets them from the row the host
     * resolved, {@link #stepGlide} eases them across a surface boundary, and
     * {@link #snapshot()} reports them so the no-op-paint guard can see a change.
     * The sheet's four splat rows move them live while a finger drags (the same
     * volatile-only contract {@link #setDepth} follows); the store write is on
     * release.
     */
    private volatile float splatRenderScale = 1f;
    private volatile float splatEps2d = Splat.EPS_2D_DEFAULT;
    private volatile float splatBlurPx = Splat.BLUR_DEFAULT;
    private volatile float splatDepthMul = Splat.DEPTH_MUL_DEFAULT;

    /**
     * Live preview values, written by the UI thread and read on the GL thread.
     *
     * <p>Volatile and nothing more, per the threading rule: a UI-thread setter may
     * only write a field, and anything touching GL state goes through
     * {@code glView.queueEvent}. These are consumed in {@link #apply}, which already
     * runs on the GL thread, so no queueEvent is needed for the read — only the
     * field is shared.
     */
    private volatile float pendingBrightnessGain = 1f;
    private volatile float pendingShadowLift = 0f;

    /**
     * One complete relief render: the colour texture, the mesh built from its
     * depth map, the aspect those two share, and the framing they are drawn
     * with.
     *
     * <p><b>Why this is a thing.</b> The image and its depth map are one
     * photograph and must arrive and leave together. Building them into a single
     * object is what makes that structural rather than a rule to remember: the
     * draw path takes a {@code Render} and never sees half of one, and an
     * incoming pair can sit beside the outgoing pair without either being
     * half-updated.
     *
     * <p>The framing lives here rather than staying in the volatile fields
     * because two renders are on screen at once during a cross-fade, and each
     * must be drawn through its OWN depth, zoom and pan. The old photo drawn
     * with the new photo's Depth is exactly the mistiming this class was
     * rewritten to remove.
     */
    private static final class Render {
        int textureColor = -1;
        /**
         * The supplied normal map, sampled by the fragment shader. {@code <= 0}
         * means there is none and the photo draws flat lit -- deliberately, and
         * not a failure: a texture name of 0 is the default texture (no image,
         * pure black), so it is never treated as a normal.
         *
         * <p>Unlike {@link #textureDepth} this is <b>not</b> part of
         * {@link #isDrawable}: a normal is never required to draw.
         */
        int textureNormal = -1;
        /**
         * GLES3 only: the blurred float depth field, sampled by the vertex
         * shader. 0 means the field was not uploaded (a context-less call), which
         * -- like texture name 0 for colour -- is never a success.
         */
        int textureDepth = -1;
        int vertexCount;
        FloatBuffer posBuffer, uvBuffer;
        /** Image width / height, baked into the mesh's x and the camera's aspect. */
        float aspect = 1f;
        /**
         * The photograph's camera, for the splat mode: the focal in pixels of the
         * image above, or 0 for unknown, and that image's height in pixels.
         *
         * <p>Carried on the render rather than read from the index at draw time
         * for the same reason {@link #framing} is: during a cross-fade two
         * photographs are on screen at once and each must be drawn with its OWN
         * camera. A row that gains a focal is a re-install (see
         * {@link #showingSamePair}), so these are set where the pair is.
         */
        float focal;
        float imageH;
        /**
         * The blurred depth field, kept from the install so the camera can aim
         * at the surface under the origin: model-space height per sample,
         * {@code (GRID+1)^2}, top-first. The texture keeps the raw 0..255
         * values; this retained copy is converted once, after the upload, to the
         * unit the pivot is resolved in — the same unit {@code ReliefMesh}'s own
         * field is in, so the sampler does not have to know which path built it.
         * See {@link ReliefMesh#heightAt}.
         *
         * <p><b>For a clip this is the STILL POSTER's field.</b> A clip's live
         * depth is GPU-side: the blitter rewrites the field texture every frame
         * and no CPU copy exists to sample. The poster is a still of the clip's
         * own first frame, so the pivot is the surface of the photograph the
         * row was cut from -- one number per pair, the same lifetime every other
         * framing value has, rather than a pivot that chases the moving subject.
         */
        float[] field;
        /** The framing this render is drawn with while it is on screen or fading. */
        Framing framing;
        /** The light, resolved once per framing capture so no draw does trigonometry. */
        float lightX, lightY, lightZ;

        boolean isDrawable() {
            if (textureColor <= 0) return false;
            // GLES3 draws the static grid, so the per-render evidence that the
            // field arrived is the depth texture, not a vertex count. The grid
            // itself is built in onSurfaceCreated, before any install can run in
            // that context, so a live depth texture implies a live grid.
            return GLES3 ? textureDepth > 0 : vertexCount > 0;
        }
    }

    /**
     * The pair on screen, or null before the first install into this context.
     *
     * <p>While no fade is running this is the only render that matters, and its
     * framing is refreshed from the live volatile fields every frame (so
     * sliders, glides and previews are immediate). While a pair change is
     * pending or fading, the framing is frozen: see {@link #pairChangePending}
     * and {@link #incoming}.
     */
    private Render displayed;

    /**
     * The pair fading in, or null. At most one, ever.
     *
     * <p>A second change mid-fade releases the outgoing pair first, so a fast
     * playlist holds two textures and two meshes at the peak and no more. That
     * is the bound; nothing here accumulates, and a fade that is never stepped
     * (no requester, no sensor) is still eventually finished by the next frame
     * because the timestamp is checked, not a counter.
     */
    private Render incoming;

    /** Wall-clock start of the cross-fade in {@link #incoming}, in nanos. */
    private long fadeStartNanos;

    /**
     * True from the moment a pair DIFFERENT from {@link #displayed} is requested
     * until its install lands (or fails).
     *
     * <p>While true, the live framing fields may already describe the incoming
     * photo -- both hosts apply the new item's {@code Framing} before the decode
     * finishes -- so the draw path must keep using {@link Render#framing}. Held
     * as a volatile because the studio requests from the UI thread and the
     * wallpaper from the GL thread; it is a flag, not a reference, so a torn
     * read is impossible.
     */
    private volatile boolean pairChangePending;

    /**
     * The render whose attribute pointers are currently installed, so the
     * per-install {@link #bindMeshAttribs} optimisation survives the fade.
     *
     * <p>Null means "unknown, bind before the next draw". During a cross-fade
     * the two meshes alternate every frame, so this forces a rebind per draw for
     * the FADE ONLY (200 ms). The alternative -- a combined morph mesh -- is a
     * 12.6 MB buffer rebuilt per pair; two rebinds for two tenths of a second is
     * the cheaper trade, and the steady state still binds exactly once per load.
     */
    private Render boundMesh;

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
     * {@code displayed == null}. When the decode finishes and applyPending()
     * installs it, nothing else in a live wallpaper will ask for a frame -- no
     * preference changed, and the tilt callback only fires while the gyro is
     * awake. Without this the wallpaper loads its image and then stays black
     * until something happens to nudge it. A cross-fade reuses the same request
     * every frame for its span; see onDrawFrame.
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
     * The pair currently on screen; both null when the packaged demo is up.
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
     * The supplied normal path that is on screen, or null for flat lit. Part of
     * the pair identity: a photo whose normal arrives later is not "already
     * showing", or the new texture is never uploaded. See {@link #showingSamePair}.
     */
    private volatile String loadedNormalPath;
    /**
     * The focal of the pair on screen: see {@link #showingSamePair}. Part of the
     * identity for the reason the normal is -- a row whose focal arrives later (an
     * import or a regenerate that finished while it was on screen) must be
     * re-installed, or the new camera never reaches the shader.
     */
    private volatile float loadedFocal;
    /**
     * Whether anything has ever been installed into the current GL context.
     *
     * <p>Load-bearing, and it exists because of a coincidence of representation.
     * "Nothing of the user's to draw" is the pair (null, null), and a renderer that
     * has installed <i>nothing</i> is also (null, null), because that is the initial
     * value of both fields above. So without this flag a fresh engine asked to draw
     * the packaged pair was told it already was and skipped the load entirely: the
     * surface stayed empty, the wallpaper was black, and the status line cheerfully
     * reported the demo as loaded. Photos were unaffected, which is what made it
     * look like the bundled item specifically was broken.
     */
    private volatile boolean hasInstalled = false;

    /**
     * The medium of the pair currently on screen: {@code Framing.SOURCE_STILL} or
     * {@code SOURCE_VIDEO}.
     *
     * <p><b>A field because {@link #snapshot()} has to be able to say it.</b> Not
     * derived per draw and not asked of the index: the renderer is what is drawing,
     * so it is what knows — the same reason {@link #snapshot()} exists rather than
     * taking a caller's idea of the current framing, which a drag or a fade has
     * already moved.
     *
     * <p><b>Written where the pair changes, never per frame.</b> {@code install} is
     * the only thing that can put a different medium on screen, so it is the only
     * place this moves; a draw reads it. And it is written <i>after</i> the pair has
     * been made current rather than before, so a snapshot taken mid-install cannot
     * name a medium the screen has not reached yet.
     *
     * <p><b>Still until something sets it otherwise, and that is the honest default
     * rather than a placeholder.</b> Every photograph in the library renders through
     * this path today, so {@code SOURCE_STILL} is not "unimplemented" — it is what
     * the renderer draws unless a clip arrives. A default of {@code SOURCE_VIDEO}
     * would make every photograph compare unequal to itself.
     */
    private volatile float sourceKind = Framing.SOURCE_STILL;

    /**
     * The clip's playback rate, 1 = normal. A render value like {@code motion}:
     * it changes what is drawn, so it is a volatile here and a field on the item,
     * and the snapshot above must carry it.
     *
     * <p>Applied at the DECODER's gate rather than by dropping frames: the gate
     * compares a frame's clip time against the wall clock, so dividing that
     * comparison by the rate makes the clip play at the rate -- at 0.5x every frame
     * waits twice as long, and no frame is skipped or repeated. The clip's own
     * timeline is untouched, so loops, the seam arithmetic and the skew line all
     * keep their meaning.
     */
    private volatile float speed = Framing.DEFAULT_SPEED;

    /**
     * Set by a host before it asks for a pair, and cleared by nothing: it says what
     * the NEXT decode is for, not what is on screen.
     *
     * <p>Written on the UI thread beside the request, and read on the {@code Io}
     * thread that performs the decode, so it is {@code volatile} for the same reason
     * every other request field is. It is a flag and not the {@link #sourceKind}
     * float deliberately: this is a request, and that is a fact about the screen.
     * Two fields because conflating them would mean a late decode could rename the
     * medium of the pair already being displayed.
     */
    private volatile boolean clipRequested;

    /**
     * The clip currently playing, or null. GL thread only.
     *
     * <p><b>Separate from {@link #hasInstalled} because a clip keeps producing
     * frames after it has arrived.</b> The still path is "install once, draw many";
     * this is "install once, then replace the three textures every frame". So the
     * pair is still installed -- there is still a photograph underneath, which is
     * what the first frames before the first decode are drawn from -- and the clip
     * overlays it rather than replacing it.
     *
     * <p><b>The tick is here and not in the decoder</b> for the same reason the
     * renderer's other GL work is: {@code updateTexImage} and the blit are GL calls,
     * and a decoder thread making them would be a context violation that happens to
     * work until it does not.
     */
    private volatile ClipPlayer clip;

    /**
     * Whether the host has hidden this engine and a clip must not decode behind it.
     *
     * <p><b>A flag as well as a call, because a start can land after the hide.</b>
     * The hide suspends the player that exists, but a decode already in flight can
     * finish afterwards and start a clip on the paused GL thread -- and a running
     * player on a hidden engine is the measured leak this whole path exists to
     * end. So {@link #suspendClip()} sets the flag and the places a start lands
     * ({@code startClipInner}, {@code startPreview}) suspend the new player at
     * once instead of leaving it decoding.
     *
     * <p>Cleared by {@link #resumeClip()}, which the hosts only call for a screen
     * that is actually interactive; a doze pulse leaves it set. Volatile because
     * the hosts write it from their visibility threads while the GL thread reads
     * it where a start lands.
     */
    private volatile boolean clipSuspendWanted;

    /**
     * Debug layer drawn over the relief: 0 off, 1 colour, 2 depth field, 3 normal.
     *
     * <p>A debug view, because alignment questions -- is the depth field flipped,
     * offset, stale, or the other clip's -- are answered by LOOKING at the texture
     * the draw actually samples, not by reasoning about the pipeline that produced
     * it. Long-press the status line cycles; the layer name comes back as a toast.
     */
    private volatile int debugLayer;
    private int dbgProgram, dbgULayer, dbgUTex, dbgAPos;
    private java.nio.FloatBuffer dbgQuad;

    /** Cycle the debug layer; returns the name of the new one, for the toast. */
    public String cycleDebugLayer() {
        debugLayer = (debugLayer + 1) % 4;
        android.util.Log.i("Basrelief", "debugLayer -> " + debugLayer);
        if (renderRequester != null) renderRequester.run();
        return new String[] {"relief", "colour", "depth", "normal"}[debugLayer];
    }

    private void drawDebugQuad(Render r) {
        if (dbgProgram == 0) {
            String v = "#version 300 es\n" +
                    "in vec2 aPos; out vec2 vUv;\n" +
                    "void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
            String f = "#version 300 es\n" +
                    "precision highp float;\n" +
                    "uniform sampler2D uTex; uniform int uLayer; in vec2 vUv; out vec4 o;\n" +
                    "void main(){\n" +
                    // The DEPTH field is stored row 0 = image top (the mesh reads it
                    // with gy = (1 - aUV.y) * grid), while this quad puts v=1 at screen
                    // top -- so the depth layer is viewed through the same flip the
                    // mesh applies, or it shows mirrored, which it did.
                    "  vec2 uv = uLayer == 2 ? vec2(vUv.x, 1.0 - vUv.y) : vUv;\n" +
                    "  vec4 c = texture(uTex, uv);\n" +
                    // /255.0, because the FIELD is 0..255 and this draws into the
                    // default framebuffer, which is 0..1: without the divide every
                    // non-zero texel clamps to white and a good depth map reads as a
                    // black-and-white mask. The field's own numbers said so --
                    // fieldCentre spread 58..138 with err=0 -- while the layer looked
                    // binary, which is a VIEW bug and not a data one.
                    "  if (uLayer == 2) c = vec4(vec3(c.r / 255.0), 1.0);\n" +
                    "  o = vec4(c.rgb, 1.0);\n" +
                    "}\n";
            int vsh = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(vsh, v); GLES20.glCompileShader(vsh);
            int fsh = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(fsh, f); GLES20.glCompileShader(fsh);
            dbgProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(dbgProgram, vsh);
            GLES20.glAttachShader(dbgProgram, fsh);
            GLES20.glLinkProgram(dbgProgram);
            int[] lok = new int[1];
            GLES20.glGetProgramiv(dbgProgram, GLES20.GL_LINK_STATUS, lok, 0);
            int[] cerr = new int[1];
            GLES20.glGetShaderiv(vsh, GLES20.GL_COMPILE_STATUS, cerr, 0);
            android.util.Log.i("Basrelief", "debug prog link=" + lok[0]
                    + " vshCompile=" + cerr[0]
                    + " linkLog=" + GLES20.glGetProgramInfoLog(dbgProgram)
                    + " vshLog=" + GLES20.glGetShaderInfoLog(vsh));
            GLES20.glDeleteShader(vsh); GLES20.glDeleteShader(fsh);
            dbgULayer = GLES20.glGetUniformLocation(dbgProgram, "uLayer");
            dbgUTex = GLES20.glGetUniformLocation(dbgProgram, "uTex");
            dbgAPos = GLES20.glGetAttribLocation(dbgProgram, "aPos");
            float[] q = {-1, -1, 1, -1, -1, 1, 1, 1};
            dbgQuad = java.nio.ByteBuffer.allocateDirect(32)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            dbgQuad.put(q).position(0);
        }
        // The CLIP-AWARE textures, not the render's still fields: the relief plays
        // because the draw swaps in the clip's textures through these helpers, and a
        // debug view sampling the still fields instead shows the frozen poster's
        // colour and normal -- which is exactly how it shipped, the debug colour and normal views did not play. The debug view must show what the draw SHOWS.
        int tex = debugLayer == 1 ? clipColour(r)
                : debugLayer == 2 ? clipDepthField(r)
                : clipNormal(r);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(dbgProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(dbgUTex, 0);
        GLES20.glUniform1i(dbgULayer, debugLayer);
        GLES20.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(dbgAPos);
        GLES20.glVertexAttribPointer(dbgAPos, 2, GLES20.GL_FLOAT, false, 0, dbgQuad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(dbgAPos);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        int err = GLES20.glGetError();
        if (err != 0) {
            // Redo the pass with a clear before each call: which one refused?
            StringBuilder probe = new StringBuilder();
            GLES20.glGetError();
            GLES20.glUseProgram(dbgProgram);
            probe.append("use=0x").append(Integer.toHexString(GLES20.glGetError())).append(' ');
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            probe.append("bind=0x").append(Integer.toHexString(GLES20.glGetError())).append(' ');
            GLES20.glUniform1i(dbgUTex, 0);
            probe.append("uTex=0x").append(Integer.toHexString(GLES20.glGetError())).append(' ');
            GLES20.glUniform1i(dbgULayer, debugLayer);
            probe.append("uL=0x").append(Integer.toHexString(GLES20.glGetError())).append(' ');
            GLES20.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            GLES20.glEnableVertexAttribArray(dbgAPos);
            GLES20.glVertexAttribPointer(dbgAPos, 2, GLES20.GL_FLOAT, false, 0, dbgQuad);
            probe.append("ptr=0x").append(Integer.toHexString(GLES20.glGetError())).append(' ');
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            probe.append("draw=0x").append(Integer.toHexString(GLES20.glGetError()));
            android.util.Log.i("Basrelief", "debug probe " + probe);
        }
        dbgFrames++;
        if (err != 0 || dbgFrames < 3 || dbgFrames % 60 == 0) {
            android.util.Log.i("Basrelief", "debug draw tex=" + tex + " layer=" + debugLayer
                    + " err=0x" + Integer.toHexString(err) + " frames=" + dbgFrames);
        }
    }

    private long dbgFrames;
    private long lastClipStatNanos;

    /**
     * How long a clip may produce nothing before the renderer restarts it.
     *
     * <p><b>A watchdog, because a wedged codec never recovers and nothing reports
     * it.</b> Measured twice: both decoders frozen with the pump still drawing
     * (produced constant, latch/s 0, draws/s 33), the two loop counts diverged and
     * the skew line at 183 SECONDS -- the wallpaper showing one frame for twenty
     * minutes. A living photo is a loop that runs for hours, so a media pipeline that
     * can wedge needs a way back that does not involve the user noticing.
     *
     * <p>Three seconds: a clip at 0.25x still produces a frame every 133ms, and the
     * seam's keyframe hold is a handful of frames, so nothing legitimate is quiet for
     * three seconds while the decoders are alive.
     */
    private static final long CLIP_STALL_MS = 3000L;
    private long watchdogProduced = -1L;
    private long watchdogChangedAtMs;
    /** Draws since the previous stats window; the pump's report card. */
    private long lastClipDraws, clipDraws;
    /** Frames the tick newly latched, last window. 0 means the draw ate nothing. */
    private int lastLatch, latchDelta;

    /**
     * Whether a clip is drawing, so the host can keep frames coming.
     *
     * <p>The frame-pacing invariant says the surface is WHEN_DIRTY and only the gyro
     * asks for a frame, and a still phone produces none -- which is true for the relief
     * and false for a clip: a clip's CONTENT changes with no input at all, so something
     * other than the sensor has to drive the draw. The host that owns the
     * GLSurfaceView is the only thing that can requestRender, so it has to know.
     * Written from the GL thread by the tick; read by the hosts' pumps.
     */
    /**
     * Whether frames should keep being pumped.
     *
     * <p>Three states, and the pump needs ALL of them because each one is false while
     * a clip is still legitimately wanted:
     *
     * <ul>
     *   <li>{@code drawingClip} -- a frame was drawn with a clip on it.</li>
     *   <li>{@code pendingTracks != null} -- armed, decode in flight. The gap between
     *       arming and {@code applyPending} can be a second, and a pump that reads only
     *       "is something drawing" ends itself inside it.</li>
     *   <li>{@code clip.isRunning()} -- the clip STARTED but no frame has been drawn
     *       yet, so {@code drawingClip} is still false. This is the one that killed it
     *       for good: {@code startClip}'s first act is {@code stopClip()}, which clears
     *       {@code pendingTracks} -- the pump's only live signal -- one tick before the
     *       clip comes up. The pump exited, the clip ran, and nothing ever asked for a
     *       frame again: measured on device as {@code Total frames rendered: 32}, the
     *       decode gap's worth, then silence.
     *   </li>
     * </ul>
     *
     * <p>{@code isRunning}, not {@code isLive}, for that last term: a player a hide
     * suspended wants no frames -- it is holding its time, not advancing it -- and it
     * cannot stall, so the pump may idle while the screen is dark and the watchdog has
     * nothing to watch. The host's resume and its pump restart are what start frames
     * again.
     */
    public boolean clipWantsFrames() {
        return startingClip || pendingTracks != null || pendingPreview != null
                || (clip != null && clip.isRunning());
    }

    /**
     * Whether what is on screen already matches this medium request.
     *
     * <p>The question {@code showingSamePair} cannot answer: it compares the STILL
     * pair, and a row's still files do not change when its clips are what changed.
     * So "already showing that pair" is true for a re-selected clip row whose clip
     * never started, and without this test the load is skipped and nothing ever
     * swaps the medium in.
     *
     * <p><b>Three requests, three matches, and the precedence between them is the
     * whole no-restart rule.</b>
     *
     * <ul>
     *   <li><b>Full tracks</b> match only the exact three-file identity. A PREVIEW is
     *       running? It matches, and the load proceeds so {@code applyPending} can
     *       GROW it in place — that is the point of asking.</li>
     *   <li><b>A preview colour</b> matches a running clip playing that colour,
     *       whether it is still a colour-only preview OR has already grown: a grown
     *       clip must not be torn back down to a preview by a lingering request, and
     *       the file is the identity both share (a preview has no tracks to compare,
     *       and must not store any).</li>
     *   <li><b>Null</b> means "no clip wanted": true only when nothing is running, so
     *       a photograph re-selected keeps its cheap no-op and a clip still playing
     *       behind a photograph forces the load that stops it.</li>
     * </ul>
     *
     * <p>{@code isLive}, not {@code isRunning}, in all three branches: a player a
     * hide suspended is still the clip this request names, and answering "not
     * running" would make the resolve reload the pair and rebuild three decoders
     * over it -- the rewind this method exists to prevent.
     */
    public boolean clipMatches(com.amoshydra.basrelief.core.store.VideoTracks t,
                               java.io.File previewColour) {
        if (t != null) return clip != null && clip.isLive() && clip.isSameClips(t);
        if (previewColour != null) {
            return clip != null && clip.isLive() && clip.playsColour(previewColour);
        }
        return clip == null || !clip.isLive();
    }

    /**
     * Play this clip on top of whatever pair is installed. GL thread (queue it).
     *
     * @return false if the clips are not on disk, which is a POSTER state rather than
     *         a failure: the still pair stays on screen and the sheet says why.
     */
    boolean startClip(com.amoshydra.basrelief.core.store.VideoTracks tracks, java.io.File filesDir) {
        startingClip = true;
        try {
            return startClipInner(tracks, filesDir);
        } finally {
            startingClip = false;
        }
    }

    private boolean startClipInner(com.amoshydra.basrelief.core.store.VideoTracks tracks,
                                   java.io.File filesDir) {
        // IDEMPOTENT, and this is the property that actually matters. An install can
        // arrive for a clip that is already playing -- a re-resolve, a surface switch,
        // a prefs write -- and tearing the three decoders down and starting them again
        // rewinds the clip to frame 0. A video that rewinds on every resolve is a
        // photograph, which is exactly what was reported.
        //
        // So the LIVE clip's IDENTITY is compared, not "is one running" -- live,
        // not running, because a player a hide suspended is still this clip and
        // rebuilding it is the same rewind from the other direction. The
        // three filenames are the identity: two VideoTracks naming the same files are
        // the same clip, and comparing the object would restart a clip that had merely
        // been rebuilt from the same JSON.
        if (clip != null && clip.isLive() && clip.isSameClips(tracks)) {
            return true;
        }
        // A GROW, not a stop-then-start. The colour currently playing is the file these
        // tracks name -- which is what an import looks like the moment its depth lands,
        // because the remuxed range is BOTH the preview's colour and the finished clip's
        // colour. GrowTo adds the depth (and normal) decoders to the running player and
        // leaves the colour decoder, its clock and its frame untouched; stopClip would
        // restart the picture at frame 0, and "a video that rewinds is a photograph" is
        // the reported defect this path exists to prevent. Only when the depth is
        // actually resolvable: a grow that cannot get depth would leave the row naming
        // tracks nothing can play, where the ordinary path degrades to the poster and
        // retries on the next resolve. A SUSPENDED player grows too (isLive): the
        // tracks can arrive while the engine is hidden, and the identity has to move
        // to them or every later resolve would grow the same player again.
        if (clip != null && clip.isLive() && tracks != null && filesDir != null) {
            java.io.File cf = tracks.colorFile(filesDir);
            java.io.File df = tracks.depthFile(filesDir);
            if (clip.playsColour(cf) && df != null && df.exists()) {
                clip.growTo(tracks, filesDir);
                return true;
            }
        }
        stopClip();
        if (tracks == null || filesDir == null) return false;
        // GLES3 only, and said out loud rather than half-attempted: the GLES2 path
        // tessellates on the CPU into a vertex array, which a per-frame depth field
        // cannot feed. Pretending otherwise would draw a still and call it a clip.
        if (!GLES3) {
            android.util.Log.w("Basrelief", "clip needs GLES3; drawing its poster instead");
            return false;
        }
        clip = new ClipPlayer();
        // The rate BEFORE the start, so the clip's first frame is already at the
        // user's speed rather than at 1x for one frame.
        clip.setRate(speed);
        if (!clip.start(tracks, filesDir, GRID)) {
            android.util.Log.w("Basrelief", "clip not playable: " + clip.error());
            clip = null;
            return false;
        }
        // What the watchdog restarts from, recorded at the one moment it is true:
        // the player started. See runningTracks.
        runningTracks = tracks;
        runningPreview = null;
        runningDir = filesDir;
        // A start that lands while the engine is hidden must not leave a running
        // player behind it. The hide set clipSuspendWanted and suspended whatever
        // existed THEN; this player did not exist yet, and the GL thread it just
        // started on is the paused one. Suspending here closes that window, so no
        // path leaves decoders running on a hidden engine. The held time is this
        // player's own start, so a later resume begins where it would have -- which
        // is right for a player that never got a visible frame.
        if (clipSuspendWanted) clip.suspend();
        return true;
    }

    /**
     * Play a colour-only preview of a row whose depth import is still running. GL
     * thread (queue it).
     *
     * <p>The sibling of {@link #startClip}, and the same lifecycle rules: armed
     * before the load, started in the same GL event as the pair it belongs to, and
     * idempotent by the one identity a preview has — its colour file. That last test
     * is what makes a re-resolve during a long import cheap: a request for the colour
     * already playing does nothing, and a request for a colour that has GROWN into a
     * full clip (the same file) does nothing either, rather than tearing the grown
     * clip back down to a preview. See {@code clipMatches}.
     *
     * <p>A failed preview start is a poster state, like a failed clip start: the
     * still pair stays on screen and the status line reports what actually happened.
     */
    private boolean startPreview(java.io.File colour, java.io.File filesDir) {
        if (clip != null && clip.isLive() && clip.playsColour(colour)) return true;
        // The same pump window startClip has: this tears a player down before the new
        // one is running, and a poll landing inside it would end the frame chain.
        startingClip = true;
        try {
            stopClip();
            if (colour == null || filesDir == null) return false;
            // GLES3 only, for the same reason startClip is: the per-frame depth FIELD
            // is sampled by the vertex shader, which GLES2 cannot do. A preview is
            // colour-only, but it still draws through that shader.
            if (!GLES3) {
                android.util.Log.w("Basrelief", "clip preview needs GLES3; drawing its poster instead");
                return false;
            }
            clip = new ClipPlayer();
            clip.setRate(speed);
            if (!clip.startPreview(colour, GRID)) {
                android.util.Log.w("Basrelief", "clip preview not playable: " + clip.error());
                clip = null;
                return false;
            }
            runningPreview = colour;
            runningDir = filesDir;
            // Same window as startClipInner's: a preview that starts while the
            // engine is hidden suspends at once rather than decoding behind it.
            if (clipSuspendWanted) clip.suspend();
            return true;
        } finally {
            startingClip = false;
        }
    }

    /**
     * True while {@link #startClip} is mid-flight on the GL thread.
     *
     * <p>Starting a clip takes roughly half a second to a second -- three
     * MediaCodecs, three SurfaceTextures, FBOs and shader compiles -- and its first
     * act is {@code stopClip()}, which clears {@code pendingTracks}, while the new
     * player is not {@code isRunning()} yet. The pump polls every 16ms and ENDS ITS
     * CHAIN on a false sample, so a tick landing inside that window left the clip
     * running with nothing asking for frames: the intermittent pause, and
     * intermittent because it depends on where the poll falls. This is that window,
     * named, so the pump cannot fall into it.
     */
    private volatile boolean startingClip;

    private volatile com.amoshydra.basrelief.core.store.VideoTracks pendingTracks;
    /**
     * The armed colour-only preview, or null. Exclusive with {@link #pendingTracks}:
     * a row either has its clips (the full medium) or it does not and is eligible for
     * a preview, and a request that carried both would be a host deriving the medium
     * twice. See {@link #requestClip}.
     */
    private volatile java.io.File pendingPreview;
    private volatile java.io.File pendingFilesDir;

    /**
     * What the RUNNING clip actually plays, recorded where the start succeeded and
     * cleared with the clip.
     *
     * <p>The watchdog restarts from this, and that is the whole reason it exists:
     * starting a clip CLEARS the arm it was started from ({@link #stopClip} drops
     * {@code pendingTracks}/{@code pendingPreview}), so a clip that stalled a second
     * time had nothing left to restart from, the restart was skipped, and the stats
     * line below then dereferenced the null player. Measured on the Pixel: eleven
     * FATALs between 10-07 and 10-10, every one at that line, and a preview never had
     * {@code pendingTracks} at all — so every preview stall took the same path.
     *
     * <p>Written only when the player actually started, per the rule that any state
     * claiming "this is on screen" is recorded where the GL calls that put it there
     * succeeded. GL thread only, like the player itself.
     */
    private com.amoshydra.basrelief.core.store.VideoTracks runningTracks;
    private java.io.File runningPreview;
    private java.io.File runningDir;

    /**
     * Ask for this clip — or this colour-only preview — to play on top of the pair.
     * Any thread.
     *
     * <p><b>Armed, not started</b>, for the same reason {@code loadSeq} exists: a
     * request that has not been satisfied must not name a fact about the screen. The
     * medium sits here until the pair lands and {@link #applyPending} swaps it in, in
     * the same GL event as everything else -- so a request for a clip whose files are
     * missing leaves the poster frame on screen rather than a claimed-to-be-playing
     * clip with nothing behind it.
     *
     * <p><b>Three requests, one arm:</b> {@code tracks != null} is the full clip;
     * {@code tracks == null && previewColour != null} is the colour-only preview a
     * running import is eligible for; both null is "a photograph, not a clip", which
     * is what a host passes for every row that has neither. That last case has to be
     * explicit: a request never made would leave the previous clip playing over the
     * newly selected photograph, which is the wallpaper showing the wrong medium.
     *
     * <p>Preview wins nothing over tracks: a caller passing both is asking for the
     * full clip, and building the "both" state would be building a state no host
     * derives.
     */
    public void requestClip(com.amoshydra.basrelief.core.store.VideoTracks tracks,
                            java.io.File previewColour,
                            java.io.File filesDir) {
        this.pendingTracks = tracks;
        this.pendingPreview = tracks == null ? previewColour : null;
        this.pendingFilesDir = filesDir;
        this.clipRequested = tracks != null || previewColour != null;
        android.util.Log.i("Basrelief", "requestClip tracks="
                + (tracks == null ? "null" : tracks.toJson())
                + " preview=" + (pendingPreview == null ? "-" : pendingPreview.getName())
                + " running=" + (clip != null && clip.isRunning())
                + " playingJson=" + (clip == null ? "-" : String.valueOf(clip.playingJsonForLog())));
    }

    /**
     * Stop and release the clip, if any. GL thread; safe when there is none.
     *
     * <p>Also drops the armed request, because a clip that was cancelled mid-decode
     * must not start later: the sequence that mattered was the decode's, and by the
     * time it lands the user is somewhere else.
     */
    void stopClip() {
        pendingTracks = null;
        pendingPreview = null;
        pendingFilesDir = null;
        // The running record goes with the player it describes: a restart must never
        // name a clip that is not the one that was playing. See runningTracks.
        runningTracks = null;
        runningPreview = null;
        runningDir = null;
        clipRequested = false;
        if (clip != null) {
            clip.stop();
            clip = null;
        }
    }

    /**
     * Stop a clip decoding while its engine is hidden. Any thread; no GL calls.
     *
     * <p><b>The stop cannot ride the GL queue.</b> The host that hides is about to
     * pause the {@code GLSurfaceView}, and a paused GL thread does not drain
     * {@code queueEvent}: a stop posted there sits undelivered for as long as the
     * engine stays hidden -- measured as three decoder threads and three codec
     * loops burning most of a core for eleven minutes behind a dark screen. So the
     * player is suspended HERE, on the thread the visibility change ran on, and
     * the flag makes any start that lands later suspend itself too. The player
     * holds its clip time, so {@link #resumeClip()} continues rather than rewinds.
     */
    public void suspendClip() {
        clipSuspendWanted = true;
        ClipPlayer c = clip;
        if (c != null) c.suspend();
    }

    /**
     * Resume a suspended clip, if the arm still names it. Any thread; no GL calls.
     *
     * <p><b>In place, never a restart.</b> {@link ClipPlayer#resume()} seeds the
     * decoders back to the clip time the suspension held, so the picture continues
     * where it was; tearing the player down and starting it again would rewind it,
     * which is the reported "a video that rewinds is a photograph".
     *
     * <p><b>Only when the arm still names it.</b> The arm is what the last resolve
     * asked to be on screen. If it names a still or a different clip, this player
     * is about to be replaced by that resolve's load, and resuming it would run
     * decoders the next {@code applyPending}/{@code stopClip} is about to release
     * -- so the match is the guard, and a non-match is not a failure. A null arm
     * with a suspended player is the same non-match: nothing has claimed the
     * screen, so nothing may claim the decoders.
     */
    public void resumeClip() {
        clipSuspendWanted = false;
        ClipPlayer c = clip;
        if (c == null || !c.isSuspended()) return;
        boolean stillWanted = (pendingTracks != null && c.isSameClips(pendingTracks))
                || (pendingPreview != null && c.playsColour(pendingPreview));
        if (!stillWanted) return;
        c.resume();
    }

    /**
     * The colour texture to draw: the clip's while one plays, this render's own
     * otherwise.
     *
     * <p>Takes the render rather than closing over one, and that is the whole reason
     * it is a method. A pair change freezes the drawn framing onto a *different*
     * {@link Render} -- see {@code pairChangePending} -- so "the still texture" is a
     * property of the render being drawn, not a field. Closing over one would have
     * drawn the incoming photo with the outgoing photo's pixels for the length of a
     * decode, which is the reported "depth arrives before the image" all over again.
     *
     * <p><b>A suspended clip falls back to the still poster here, deliberately.</b>
     * While the engine is hidden there is nothing to draw; on the frames that do
     * happen -- a doze pulse -- the poster is the honest picture rather than a frozen
     * clip frame presented as live. The fallback changes what is DRAWN, not what the
     * decoders do next: the resume seeds them at the held clip time, so the picture
     * continues from where it stopped rather than rewinding.
     */
    private int clipColour(Render r) {
        if (clip != null && clip.isRunning() && clip.colourTex() > 0) return clip.colourTex();
        return r.textureColor;
    }

    /** The normal texture, or the render's own -- see {@link #clipColour}. */
    private int clipNormal(Render r) {
        if (clip != null && clip.isRunning() && clip.normalTex() > 0) return clip.normalTex();
        return r.textureNormal;
    }

    /**
     * The depth field the VERTEX shader samples.
     *
     * <p>Falls back to the render's own field, which is the texture
     * {@link ReliefMesh} uploaded on the pair change. A clip replaces it every frame
     * and never rebuilds geometry -- displacement is per vertex per frame in the
     * shader, so a new depth frame is a new texture bind and nothing else. That is
     * what keeps the "pan and orbit may not re-tessellate" invariant true: a new
     * frame is not a gesture.
     */
    private int clipDepthField(Render r) {
        if (clip != null && clip.isRunning() && clip.depthFieldTex() > 0) {
            return clip.depthFieldTex();
        }
        return r.textureDepth;
    }

    /**
     * Monotonic tag for the most recently issued load.
     *
     * <p>Written wherever a request is issued -- the wallpaper requests on the
     * GL thread, the studio's {@code applyFiles} on the UI thread -- and read on
     * the GL thread where results are applied, so it is volatile. The check is
     * still "the newest REQUEST wins", which is the property that matters: a
     * decode that finishes after a newer one is dropped however the two threads
     * interleave. Only the comparison is load-bearing; two requests landing on
     * the same tag is impossible because {@code ++} is serialised by the threads
     * that issue requests (the UI thread hands the studio's requests over one at
     * a time, and the wallpaper issues from one GL thread).
     */
    private volatile int loadSeq = 0;

    /**
     * Whether the given pair is already on screen.
     *
     * <p><b>The normal is part of the identity.</b> A photo can gain a normal
     * after its depth — the "Generate normals" run — and if the normal were not
     * compared, the renderer would be told it is already showing that pair and the
     * new texture would never be uploaded: the sheet would say shaded while the
     * glass stayed flat. Null-safe, because "the packaged demo" is (null, null,
     * null) and calling equals on either side is a crash on exactly that path.
     */
    /**
     * The full identity: the three files <b>and</b> the photograph's camera.
     *
     * <p>The focal is compared exactly, because both sides are the same float off
     * the same row; 0 means unknown on both, so a row that has no focal is "the
     * same pair" as itself. It is in the identity for the same reason the normal
     * is: a row that gains a focal is a re-install -- a regenerate that fits one,
     * or an import whose first frames were drawn before the sidecar had one --
     * and without this the renderer would be told the pair is already showing and
     * the new geometry would never be uploaded.
     *
     * <p>There is deliberately no three-argument overload to forget it: a caller
     * that omitted it would compare a row's real focal against 0, answer "not the
     * same pair" on every resolve, and re-decode the photograph each time -- the
     * waste the guard exists to prevent, from the safe-looking call.
     */
    public boolean showingSamePair(String imagePath, String depthPath, String normalPath,
                                   float focal) {
        if (!hasInstalled) return false; // see hasInstalled
        return samePath(imagePath, loadedImagePath)
                && samePath(depthPath, loadedDepthPath)
                && samePath(normalPath, loadedNormalPath)
                && focal == loadedFocal;
    }

    private static boolean samePath(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Wire this to GLSurfaceView.requestRender(). */
    /**
     * The Speed slider, on the UI thread. A volatile write and nothing else, like
     * {@link #setDepth}: the decoders read it through {@link #clip}.
     */
    public void setSpeed(float v) {
        this.speed = v <= 0f ? Framing.DEFAULT_SPEED : v;
        // Live, not only at start: the slider leads the finger, and a rate that
        // only applied to the NEXT clip would read as a control that does nothing.
        ClipPlayer c = clip;
        if (c != null) c.setRate(this.speed);
    }

    public void setRenderRequester(Runnable r) {
        this.renderRequester = r;
    }

    /**
     * Turn the Stage-0 card grid on or off without the pref. For a test, and for a
     * host that ever wants to A/B it.
     *
     * <p>A volatile write and nothing else, per the threading rule: what it
     * changes is read once per draw on the GL thread, and the geometry that
     * difference needs (the splat grid) is built with every other GL name in
     * {@link #onSurfaceCreated}.
     */
    public void setSplatGridMode(boolean on) {
        this.splatGridMode = on;
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

    /**
     * Which surface this renderer is showing, and the one its writes belong to.
     *
     * <p>Not a parameter to the save methods on purpose. Both savers are called from
     * touch handlers in {@code MainActivity} and there are six call sites between
     * them; passing a surface to each is six chances to pass the wrong one, and the
     * wrong one is silent -- the numbers on screen move and the OTHER surface's
     * stored values change, which is the specific defect this field exists to close.
     *
     * <p>Volatile rather than a queueEvent setter: the studio writes it from the UI
     * thread and the GL thread reads it when saving, which is exactly the pattern the
     * other setters here use. The wallpaper's own renderer sets it from its
     * {@code SurfaceResolver} instead, so a lock engine writes to Lock.
     */
    private volatile Surface editSurface = Surface.HOME;

    /** Which surface this renderer's writes belong to. Safe from the UI thread. */
    void setEditSurface(Surface surface) {
        this.editSurface = surface == null ? Surface.HOME : surface;
    }

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
     * after 10s of stillness and falls back to a low-power accelerometer until a
     * shake. OS battery-save is handled there too, by widening the sensor period;
     * there is deliberately nothing for the renderer to do about it, because the
     * one lever it has - the frame rate - is the wrong one.
     */
    private long lastFrameNanos = 0L;

    /**
     * The relief's own stats window, and the snapshot the studio's readout reads.
     *
     * <p>The relief has no GPU timer and no sort — the two numbers the splat reports
     * and this backend cannot — so its snapshot carries only the frame interval, plus
     * the worse clip track's skew while a clip plays. The window is
     * {@link RenderStats#WINDOW_NANOS}, the same one the splat's log uses, so the two
     * backends cannot report different frame rates for one surface.
     *
     * <p>The counters are GL-thread-only, so they need no synchronisation; the
     * snapshot is a fresh immutable object once a second and the UI thread reads the
     * volatile reference and allocates nothing. Null until the first window has been
     * measured, which the readout blanks rather than showing as a zero.
     */
    private long perfWindowStartNanos;
    private long perfFrames;
    private volatile RenderStats perfStats;

    /**
     * Account one drawn relief frame, and publish the readout's snapshot once a
     * window.
     *
     * <p>Called from {@link #onDrawFrame}'s relief paths only: the splat branch
     * publishes the splat's own numbers, and a frame the surface could not draw is not
     * a frame the readout should count.
     */
    private void accountReliefFrame(long now) {
        if (perfWindowStartNanos == 0L) {
            perfWindowStartNanos = now;
            return;
        }
        perfFrames++;
        long windowNanos = now - perfWindowStartNanos;
        if (windowNanos < RenderStats.WINDOW_NANOS) return;
        float fps = RenderStats.fps((int) perfFrames, windowNanos);
        // The clip's skew is the one cost number the relief has that the splat does
        // not, and it is absent for a photograph. The worse of the two tracks is the
        // one that says the picture is drifting; the once-a-second log still prints
        // both, from the same numeric helpers.
        float skew = RenderStats.NONE;
        if (clip != null && clip.isRunning()) {
            skew = RenderStats.worstOf(clip.depthSkewMs(), clip.normalSkewMs());
        }
        perfStats = new RenderStats(fps, RenderStats.frameMs(fps), RenderStats.NONE,
                RenderStats.NONE, skew, RenderStats.NO_COUNT, 0, 0);
        perfFrames = 0;
        perfWindowStartNanos = now;
    }

    /** The readout's snapshot, or null before the first window; safe from the UI thread. */
    RenderStats perfStats() {
        return perfStats;
    }

    private volatile float panX = 0f, panY = 0f;
    private volatile float zoom = 1f;
    private float surfaceW, surfaceH;

    /**
     * Surface pixels one whole unit of Origin moves the picture, per axis, at
     * the zoom and geometry of the last frame.
     *
     * <p>Written on the GL thread by {@link #recomputeScale} — which every draw
     * calls — and read on the UI thread by the framing rows, so both are
     * volatile and both are written together: a reader can never see the X of
     * one recompute beside the Y of the next. See
     * {@link CameraModel#screenPixelsPerOrbit} for the maths.
     *
     * <p>Zero until the surface exists. The caller treats zero as "no tracking
     * to compute" and falls back to its own rule rather than dividing by it.
     */
    private volatile float orbitPixelsPerUnitX = 0f, orbitPixelsPerUnitY = 0f;

    /**
     * Ask for one still frame, for the lock screen on a platform with no second engine.
     *
     * <p>A one-shot, consumed by the next drawn frame. It is a field rather than a
     * callback parameter because the request comes from the UI thread and the capture
     * has to happen on the GL thread before the next swap — the framebuffer's contents
     * are undefined afterwards.
     */
    private volatile boolean wantLockFrame;

    /** An active glide between the two surfaces' configs, or a null glideTo. */
    private volatile Framing glideFrom, glideTo;
    /**
     * The medium {@link #glideTo} names, held so {@link #stepGlide} can switch the
     * live value when the glide lands WITHOUT interpolating it. See the assignment
     * in {@link #glide} for why a lerp here is the instant-paint snap.
     */
    private volatile float glideSourceKind;
    /**
     * The draw path {@link #glideTo} names, held for the same reason
     * {@link #glideSourceKind} is: the mode is discrete, so a glide switches it when
     * it lands rather than easing it frame by frame — an eased mode would make every
     * frame differ from the one before and defeat the no-op-paint guard.
     */
    private volatile float glideRenderMode;
    private volatile long glideStartNanos;
    private volatile float glideSpanMs;

    /**
     * Request a still frame, published to the lock screen off the GL thread.
     *
     * <p>A no-op where the platform runs a second live engine, because there the lock
     * screen is not this app's to draw. Called from the UI thread; the volatile write
     * is the whole of the cross-thread work.
     *
     * <p>The caller must then ask the GLSurfaceView for a frame. The renderer has no
     * reference to it and deliberately does not acquire one — the view owns the EGL
     * lifecycle, and a renderer that could request its own draw would have a second
     * opinion about when a frame is due.
     */
    void captureLockFrame() {
        if (!StaticLockFrame.isNeeded()) return;
        wantLockFrame = true;
    }

    /**
     * The capture, at the only moment it can happen.
     *
     * <p>After the draw and before returning: the default framebuffer still holds what
     * was just drawn until {@code eglSwapBuffers} recycles it. The publish is handed to
     * the Io executor because {@code setBitmap} is a binder call plus file I/O and a
     * 1440x3120 frame on the main thread is an ANR.
     *
     * <p>A cross-fade defers rather than captures. The frame would be a blend of two
     * photographs, and the lock screen keeps ONE still frame: a half-dissolved
     * snapshot is wrong forever, while the request is still valid 200 ms later. The
     * flag is left set on purpose so the first frame after the fade captures.
     */
    private void captureLockFrameIfWanted() {
        if (!wantLockFrame) return;
        if (incoming != null) return; // half-faded; keep the request for after
        wantLockFrame = false;
        final Bitmap frame = StaticLockFrame.capture((int) surfaceW, (int) surfaceH);
        if (frame == null) return;
        final android.content.Context ctx = context;
        Io.run(new java.util.concurrent.Callable<Void>() {
            @Override
            public Void call() {
                StaticLockFrame.publish(ctx, frame);
                return null;
            }
        });
    }
    private volatile float motion = 1f;
    private volatile float depth = DEFAULT_DEPTH / 100f; // 0..1 fraction of maxZScale

    /**
     * The light: the two editable angles and the unit vector they map to.
     *
     * <p>Both representations are kept, but they are never edited independently:
     * {@link #setLight} is the only writer, and it writes all five fields from one
     * {@link LightDirection#of} call. The angles exist because they are what a
     * glide interpolates and what {@link #snapshot} hands back to a Framing; the
     * components exist so the draw path does no trigonometry and allocates nothing.
     * All five are volatile for the same reason the sliders are: a live preview
     * from the UI thread must be seen by the next frame.
     *
     * <p>The initial values are the resolved default, which is the old hard-coded
     * {@code (-0.35, 0.5, 0.75)} literal expressed as angles. See
     * {@link LightDirection} for why the constants are not round numbers.
     */
    private volatile float lightAzimuth = LightDirection.DEFAULT_AZIMUTH_DEG;
    private volatile float lightElevation = LightDirection.DEFAULT_ELEVATION_DEG;
    private volatile float lightDirX = LightDirection.DEFAULT.x;
    private volatile float lightDirY = LightDirection.DEFAULT.y;
    private volatile float lightDirZ = LightDirection.DEFAULT.z;

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

    // ---------------------------------------------------------------- framing
    // Read-back for the studio's numeric framing fields. The orbit centre and the
    // pan offset are written from the touch handler and from the framing fields,
    // all on the UI thread, so reading them there gets a consistent pair. They are
    // deliberately NOT funnelled through a Framing: the studio is showing a live
    // value the user is editing, and a resolved object per keystroke would be a
    // second answer to a question the renderer already holds the answer to.
    //
    // Orbit is a plain stored fraction, not "fraction of the image". It reaches
    // the camera as orbitX * aspect * scale, so its on-screen meaning moves with
    // Zoom and Depth. Displaying a prettier derived number would mean a second
    // representation of this state and a conversion on the way in and out, which
    // is how this app's two surfaces came to disagree in the first place. The
    // field shows what is stored, so it round-trips exactly and matches the
    // value in the prefs JSON.

    /** @return the stored orbit-centre x, as a fraction. */
    public float getOrbitX() {
        return orbitX;
    }

    /** @return the stored orbit-centre y, as a fraction. */
    public float getOrbitY() {
        return orbitY;
    }

    /** @return the stored pan offset x, in pixels. */
    public float getPanX() {
        return panX;
    }

    /** @return the stored pan offset y, in pixels. */
    public float getPanY() {
        return panY;
    }

    public void setOrbit(float nx, float ny) {
        orbitX = nx;
        orbitY = ny;
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

    /**
     * How many surface pixels one whole unit of Origin moves the picture, at
     * the zoom, image aspect and surface size of the last drawn frame.
     *
     * <p>For the framing rows, which turn it into finger travel per thousandth
     * of Origin. Safe from the UI thread: a volatile read of a value the GL
     * thread just wrote, exactly like {@link #getZoom}.
     *
     * @param horizontal true for the X axis, false for Y
     * @return pixels per whole origin unit, or 0 before the first surface
     */
    float getOrbitPixelsPerUnit(boolean horizontal) {
        return horizontal ? orbitPixelsPerUnitX : orbitPixelsPerUnitY;
    }

    public void setMotion(float v) {
        motion = Math.max(0f, Math.min(3f, v));
    }

    /**
     * Live write for the splat Size row: the 2D low-pass floor, gsplat's
     * {@code eps2d}.
     *
     * <p>Writes only a volatile field and does not touch GL, so it is safe from the
     * UI thread — the same rule {@link #setDepth} and {@link #setMotion} follow. The
     * draw reads it next frame and hands it to {@link SplatRenderer}; no recompile,
     * no restart, and nothing is persisted here (the slider's release writes the
     * item), exactly as the other live previews behave.
     */
    public void setSplatEps2d(float v) {
        splatEps2d = Splat.clampEps2d(v);
    }

    /**
     * Live write for the splat Blur row: the post-blur radius in render-target
     * pixels. Same contract as {@link #setSplatEps2d}; 0 is the identity and skips
     * both blur passes.
     */
    public void setSplatBlurPx(float v) {
        splatBlurPx = Splat.clampBlurPx(v);
    }

    /**
     * Live write for the splat Resolution row: the fraction of the surface the cloud
     * is rasterised at. Same contract as {@link #setSplatEps2d}. This is the one
     * lever that is linear in the thing the frame actually costs -- fill is pixels,
     * so 0.7 is half the fragment work -- at the price of a softer picture, which
     * for a wallpaper is the right trade to be able to make. Clamped through
     * {@link Splat#clampRenderScale}, so the row and the stored value cannot
     * disagree.
     */
    public void setSplatRenderScale(float v) {
        splatRenderScale = Splat.clampRenderScale(v);
    }

    /**
     * Live write for the splat Depth row: a multiplier on the cloud's depth, clamped
     * through {@link Splat#clampDepthMul}. Same contract as {@link #setSplatEps2d}:
     * a volatile field only, read at draw. 1 is the node's own depth and 0 is the
     * flat photograph, so a drag is visible immediately with no re-upload.
     */
    public void setSplatDepthMul(float v) {
        splatDepthMul = Splat.clampDepthMul(v);
    }

    /**
     * Live preview for the Brightness and Shadow sliders.
     *
     * <p>Writes only volatile fields and does not touch GL, so it is safe from the
     * UI thread — the same rule {@link #setDepth} and {@link #setMotion} follow. The
     * mapping is {@link Picture}'s, called here so the slider and the stored value
     * cannot disagree; the caller then calls {@code requestRender()} and the draw
     * loop uploads whatever these now say.
     *
     * <p>Storing the value is what persists it — {@code persist()} on release — so
     * this is preview only and a drag that never ends writes nothing.
     */
    public void setAdjust(int brightness, int shadow) {
        pendingBrightnessGain = Picture.brightnessGain(brightness);
        pendingShadowLift = Picture.shadowLift(shadow);
    }

    /**
     * Live preview for the light sliders.
     *
     * <p>Writes only volatile fields and does not touch GL, so it is safe from the
     * UI thread — the same rule {@link #setAdjust} follows. The mapping from angles
     * to the vector the shader sees is {@link LightDirection}'s, called here so the
     * slider, the stored value and the render cannot disagree. The caller then calls
     * {@code requestRender()} and the draw loop uploads whatever this now says.
     *
     * <p>Angles are normalised exactly as {@code SurfaceSettings} stores them, so a
     * preview of 370° and a later reload of the stored value are the same light.
     * Storing the value is what persists it — a drag that never ends writes
     * nothing.
     *
     * @param azimuthDegrees counter-clockwise from the right; wraps
     * @param elevationDegrees 0 grazing, 90 head-on; clamps
     */
    public void setLight(float azimuthDegrees, float elevationDegrees) {
        lightAzimuth = LightDirection.normaliseAzimuth(azimuthDegrees);
        lightElevation = LightDirection.normaliseElevation(elevationDegrees);
        LightDirection d = LightDirection.of(lightAzimuth, lightElevation);
        lightDirX = d.x;
        lightDirY = d.y;
        lightDirZ = d.z;
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
     * Persist the live framing onto the item being shown: origin, pan and zoom in
     * ONE read-modify-write of the index.
     *
     * <p><b>One method, because there was a time when there were two and a third
     * thing.</b> {@code saveViewToPrefs} wrote orbit and pan and {@code
     * savePanToPrefs} wrote pan, and the two-finger MOVE branch wrote zoom a third
     * time <i>per event</i> — three write paths for one set of render values, each
     * with its own idea of what a gesture ends. One write, once per gesture, with the
     * values the renderer is holding now.
     *
     * <p><b>Why the values are read here and not passed in.</b> They are the live
     * volatiles, and this is a snapshot of what the next frame will draw. A caller
     * that captured them at gesture start and passed them here would persist a value
     * the picture has already moved past — and the item is resolved at save time too,
     * which is the older of the two deliberate choices.
     *
     * <p><b>Safe from the UI thread, and it belongs there.</b> Nothing here touches
     * GL: the four values are volatiles, and the write is the threading table's UI
     * row. The tap's <i>orbit maths</i> does need the GL matrices and stays queued
     * through {@code glView.queueEvent}; the save that follows it does not, and
     * running it there cost the studio 153–213 ms of frozen rendering per tap because
     * {@link LibraryStore#editItem} is a synchronous {@code commit()} of the whole
     * index.
     *
     * <p>The id is resolved here rather than captured by the caller, so a gesture
     * that outlives a selection change is written to whatever is on screen when the
     * finger lifts, which is what the user was looking at.
     */
    public void saveFramingToPrefs() {
        final float ox = orbitX, oy = orbitY, px = panX, py = panY;
        final int pct = FramingRowValue.zoomPercent(zoom);
        SharedPreferences p = context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
        AppState st = AppState.load(context, p, context.getFilesDir());
        final String id = st.activeId();
        if (id == null) return; // nothing is selected, so there is nothing to write
        // AND THE SCREEN MUST STILL BE SHOWING IT. The values above are this
        // renderer's live volatiles; the id is the index's current selection, and the
        // two can disagree for one GL-hop after a selection change: a tap's unproject
        // posts the save, the user picks another photo, applyFiles queues the new
        // framing, and the save runs BEFORE that queued apply -- writing the previous
        // photo's origin, pan and zoom onto the newly selected one. Comparing the pair
        // on screen against the item being written is the same value-comparison remedy
        // the rest of this path uses.
        LibraryItem target = st.activeItem();
        java.io.File img = target == null ? null
                : com.amoshydra.basrelief.core.store.PrefFiles.resolve(
                        context.getFilesDir(), target.imageName);
        String want = img == null ? null : img.getPath();
        if (loadedImagePath == null || !loadedImagePath.equals(want)) return;
        LibraryStore.editItem(p, id, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                Surface on = editSurface;
                return item.withOrigin(on, ox, oy)
                        .withPan(on, px, py)
                        .withZoom(on, pct);
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
     * Load an image + depth + optional normal (GL thread only).
     *
     * <p>A null image path means "nothing of the user's to draw", and the one thing
     * that means is the packaged demo trio. These are null together, and only
     * together: a null image beside a real depth map would draw the demo's marble
     * against somebody else's map, so callers that resolve an item resolve every
     * name from the same item and the demo's own copy failure nulls them all.
     *
     * <p>The demo is a file in {@code filesDir} like any other photograph since the
     * seed copied it there, so this branch is not how it normally draws — it is what
     * a gallery with nothing selected draws, and what a demo whose copy failed
     * draws until the next load succeeds. See {@code BundledFiles}.
     *
     * <p><b>The normal is optional and its absence is not a failure.</b> A photo
     * with depth and no normal decodes a null normal and draws flat lit; a normal
     * file that will not decode degrades to the same state. Neither is allowed to
     * fail the pair, because the pair is the photograph and the normal only decides
     * how it is lit.
     */
    /**
     * The decode, with the camera the row stores.
     *
     * <p>The focal is a request parameter because it is not in the files: it is a
     * property of the row, and the decode is where the request's identity is
     * captured. 0 is a legitimate value -- a row written before the node measured
     * one -- and is the renderer's "unknown", not a missing argument.
     */
    private Pending decodePair(String imagePath, String depthPath, String normalPath,
                               float focal) {
        // The camera, sanitised ONCE here and used for both the cache key and the
        // decode: a NaN reaching either would key a cache entry against itself
        // (NaN != NaN) and the fallback arithmetic would then never see the value
        // it was given. LibraryItem clamps what it stores, so this is the same
        // rule one layer out rather than a second one.
        final float cam = (Float.isFinite(focal) && focal > 0f) ? focal : 0f;
        // The medium travels WITH the decode rather than being re-derived at install
        // time: it is a property of the pair this request is asking for, and asking
        // install() to work it out again from the paths would be a second derivation
        // of a value that has exactly one owner.
        final boolean clip = clipRequested;
        // And so does WHICH clip-request it is. A preview is CLIP too -- its pixels are
        // video and install must swap the medium in -- but the status line has to say
        // "depth pending" rather than "clip + depth", and that answer belongs to the
        // request that produced the decode, exactly like the medium. Read here, not at
        // install: a Preview flag re-derived at applyPending from whatever the fields
        // hold could name a different state than the one this decode ran for.
        final boolean preview = clip && pendingPreview != null;
        // Already decoded by the other surface in this process? Then uploading it
        // is the only work left, which is what makes the wallpaper's very first
        // frame correct: the system snapshots the surface ~100ms after it appears
        // and a decode takes ~1s, so anything decoded after that point is too late
        // to be in the snapshot no matter how it is scheduled.
        //
        // ONLY WHEN IT ANSWERS THE QUESTION ASKED. The cache key is the three
        // paths, and the medium is not a path: both surfaces in this process decode
        // the same pair, and at an import's completion the wallpaper's engine
        // resolves it too -- with the medium its PREVIOUS resolve left armed, which
        // for an import is the poster row's `null` (a photograph). Its decode lands
        // in the cache first; the studio's clip request then hits that entry, the
        // install reports a photograph, and the clip never starts. The row shows
        // its poster until a relaunch misses the cache and decodes fresh, which is
        // why the clip did not "automatically" play. A cache hit must answer the
        // medium it was asked about; a mismatch is a miss, and the fresh decode
        // below replaces the entry.
        //
        // PREVIEW IS PART OF THE QUESTION, not just clip-ness. A preview decode and a
        // full-clip decode of the same still pair are both "clip", but their status
        // lines differ ("depth pending" vs "clip + depth"), and the entry a preview
        // leaves must not answer a full-clip request with the preview's own words.
        Object warm = PairCache.get(imagePath, depthPath, normalPath, cam);
        if (warm instanceof Pending) {
            Pending w = (Pending) warm;
            if (w.isClip() == clip && w.isPreview() == preview) return w;
            android.util.Log.i("Basrelief", "pairCache: medium mismatch (cached clip="
                    + w.isClip() + "/preview=" + w.isPreview()
                    + ", want clip=" + clip + "/preview=" + preview + ") -- decoding fresh");
        }

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

        // The supplied normal: the only thing that decides shaded vs flat lit.
        // It is deliberately allowed to be absent, and a decode failure is treated
        // as absent rather than as a failed load -- the photograph is still whole.
        Bitmap normalBmp = null;
        if (normalPath != null) {
            byte[] normalBytes = readFile(normalPath);
            if (normalBytes != null) {
                normalBmp = BitmapFactory.decodeByteArray(normalBytes, 0, normalBytes.length);
            }
        }
        if (normalPath == null && depthPath == null && imagePath == null) {
            // Bundled demo: normal.png rides beside image.png and depth.png. Wired
            // for the same reason the depth is -- an asset with no reader fails
            // silently, and this one would leave a fresh install flat lit.
            normalBmp = decodeRaw(R.raw.normal);
        }

        Pending result = new Pending(colorBmp, grayOut, dims[0], dims[1],
                customImage, customDepth, embedded, flat, normalBmp,
                imagePath, depthPath, normalPath, clip, preview, cam);
        // Offer it to the other surface. Only the selected pair is worth keeping,
        // and the studio is always the one that decodes first.
        PairCache.put(imagePath, depthPath, normalPath, cam, result);
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
         * The supplied normal bitmap, or null for flat lit.
         *
         * <p>Deliberately nullable and deliberately not a reason to fail: the
         * photograph is whole without it, and the only thing it changes is whether
         * the relief is shaded or lit as a plane. See {@link #suppliedNormal()}.
         */
        final Bitmap normalBmp;
        /**
         * The paths this was decoded from, so the renderer can record what is
         * genuinely on screen rather than what was last asked for. See
         * {@link #loadedImagePath}.
         */
        final String requestedImage, requestedDepth, requestedNormal;
        /**
         * The camera of the pair being decoded: the row's focal in pixels of the
         * image, or 0.
         *
         * <p>Carried on the decode for the same reason the medium is: it is a
         * property of what was asked for, and it must reach {@link #install} in the
         * same event as the textures it belongs to. A request that never completes
         * must not have named a camera either.
         */
        final float focal;
        /**
         * Whether the pair this decode answers is a clip's, rather than a
         * photograph's.
         *
         * <p>Carried on the decode, not re-derived at install: it is a property of
         * what was asked for, and {@link #isClip()} is the only reader so there is one
         * answer rather than a value and a re-derivation that could disagree.
         */
        private final boolean clip;
        /**
         * Whether the clip request was the colour-only PREVIEW, which is a distinct
         * state from a full clip even though both are {@link #isClip()}: the still
         * pair behind it is the poster and the status line has to say the depth is
         * still coming. Carried with the decode for the same reason the medium is.
         */
        private final boolean preview;

        Pending(Bitmap colorBmp, byte[] gray, int w, int h,
                boolean customImage, boolean customDepth, boolean embedded, boolean flat,
                Bitmap normalBmp,
                String requestedImage, String requestedDepth, String requestedNormal,
                boolean clip, boolean preview, float focal) {
            this.colorBmp = colorBmp;
            this.gray = gray;
            this.w = w;
            this.h = h;
            this.customImage = customImage;
            this.customDepth = customDepth;
            this.embedded = embedded;
            this.normalBmp = normalBmp;
            this.requestedImage = requestedImage;
            this.requestedDepth = requestedDepth;
            this.requestedNormal = requestedNormal;
            this.clip = clip;
            this.preview = preview;
            this.focal = focal;
            this.flat = flat;
        }

        /** Whether a normal texture was decoded and will be uploaded. */
        boolean suppliedNormal() {
            return normalBmp != null;
        }

        /** Whether this pair is a clip's. See {@link #clip}. */
        boolean isClip() {
            return clip;
        }

        /** Whether this pair answers a colour-only preview. See {@link #preview}. */
        boolean isPreview() {
            return preview;
        }
    }

    /**
     * Put a decoded pair on screen. GL thread only.
     *
     * <p>The swap and the un-freeze happen here, in one GL event: the mesh, the
     * texture, the recorded pair identity and {@link #pairChangePending} all
     * move together, so no frame can observe the new pair under the old
     * framing's wait or the old pair under the new framing.
     *
     * @return false if there was nothing to load
     */
    private boolean applyPending(Pending p) {
        if (p == null) {
            writeStatus("failed to read image");
            // Nothing is coming, so the display must not stay frozen: the old
            // pair is still drawable and the live framing is all there is to
            // draw it with. A permanently held frame would be worse than the
            // mismatch the hold exists to prevent.
            pairChangePending = false;
            return false;
        }
        writeStatus(statusFor(p));
        if (!install(p)) {
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
            // again. install() uploads the new texture before it touches the old
            // one, so a failure here leaves the previous pair alive and drawable.
            pairChangePending = false;
            return false;
        }
        // Recorded HERE, where the swap actually happens, and not where the load
        // was requested.
        loadedImagePath = p.requestedImage;
        loadedDepthPath = p.requestedDepth;
        // The normal is part of the pair identity, so a photo that gains one is
        // re-installed and this records it. See showingSamePair.
        loadedNormalPath = p.suppliedNormal() ? p.requestedNormal : null;
        // And so is the camera: 0 for a row that has none, which is a value the
        // identity compares like any other.
        loadedFocal = p.focal;
        // The medium moves HERE, beside the pair identity it describes and in the
        // same event as the swap -- not at request time, because a request that
        // never completes would then have named a medium the screen never reached,
        // and not per draw, because the renderer is the only thing that knows what
        // it is drawing.
        //
        // Still until a clip asks otherwise. Every photograph renders through this
        // path, so this is not a placeholder default: it is what the renderer draws
        // unless a clip arrives, and a default of SOURCE_VIDEO would make every
        // photograph compare unequal to itself and repaint on every resolve.
        sourceKind = p.isClip() ? Framing.SOURCE_VIDEO : Framing.SOURCE_STILL;
        android.util.Log.i("Basrelief", "applyPending src="
                + (sourceKind == Framing.SOURCE_VIDEO ? "VIDEO" : "STILL")
                + " stopRunning=" + (clip != null && clip.isRunning()));
        hasInstalled = true;
        // The clip starts HERE, beside the medium it names and in the same event as
        // the pair swap -- so there is no window in which the screen reports a clip
        // and shows a photograph, or the reverse. A request that cannot be satisfied
        // leaves sourceKind as it is written and keeps the poster on screen, which is
        // the honest degradation rather than a claim.
        //
        // Tracks first, preview second: the arm made them exclusive (see requestClip),
        // so this order only states which one the "both" state would mean.
        if (sourceKind == Framing.SOURCE_VIDEO) {
            if (pendingTracks != null && pendingFilesDir != null) {
                startClip(pendingTracks, pendingFilesDir);
            } else if (pendingPreview != null && pendingFilesDir != null) {
                startPreview(pendingPreview, pendingFilesDir);
            }
        } else if (clip != null) {
            // A photograph was selected while a clip was playing. Stopping it is not
            // tidiness: leaving it running would keep three decoders and three
            // textures alive behind a picture that is not using them.
            stopClip();
        }
        // The wait is over in the same event that swapped the pair, so the live
        // framing (the new item's, applied before the decode landed) starts
        // reaching the screen only now -- with the mesh it belongs to.
        pairChangePending = false;
        // The frame that revealed the empty texture has already been drawn;
        // without this request the uploaded texture is never presented. It also
        // starts a cross-fade's frame pump.
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
     * <p>Safe to call from any thread. Returns immediately: frames drawn while
     * the result is in flight keep the PREVIOUS pair and that pair's own framing,
     * and the arrival cross-fades into the new one.
     *
     */
    /**
     * Ask for a pair, with the row's camera.
     *
     * <p>The focal rides with the paths because it is part of the pair's identity:
     * a row that gains one is a different render of the same files. There is no
     * three-argument form, deliberately -- a caller that forgot the focal would
     * silently install "the default lens" over a row that has a measured one, and
     * the pair identity it checks against would agree with itself.
     */
    public void loadPairAsync(final String imagePath, final String depthPath,
                              final String normalPath, final float focal) {
        // Tag the request so a result can be recognised as out of date.
        //
        // Decodes run off the GL thread and finish whenever they finish, so an
        // older request can and does complete after a newer one. Without this,
        // the slower older decode installs second and quietly replaces the newer
        // selection with the previous photo -- observed on device, not merely
        // possible. The fix is a monotonic tag rather than a timestamp: two
        // requests can share a millisecond, and only the order of issue matters.
        final int seq = ++loadSeq;
        // A request for a pair other than the one on screen freezes the drawn
        // framing until the pair lands. Both hosts apply the incoming item's
        // Framing through renderer.apply BEFORE this decode finishes
        // (MainActivity.applyFiles queues it on the event loop;
        // DepthWallpaperService.applyFromPrefs does it in the very same GL
        // event), and without the freeze the OLD photograph is drawn through the
        // NEW photo's Depth and Zoom for the whole decode -- the reported
        // "depth arrives before the image". The freeze is lifted in applyPending,
        // in the same GL event that swaps the mesh.
        //
        // Assigned for every request, not only a different pair: a request that
        // matches the displayed pair must clear an earlier request's freeze, or a
        // superseded decode would leave the display frozen forever.
        pairChangePending = hasInstalled
                && !showingSamePair(imagePath, depthPath, normalPath, focal);
        final java.util.concurrent.Executor glThread = glDispatch;
        if (glThread == null) {
            // No dispatcher yet: we are almost certainly inside onSurfaceCreated
            // before the host could set one, which cannot happen with the current
            // wiring, so just do it inline rather than dropping the image.
            applyPending(decodePair(imagePath, depthPath, normalPath, focal));
            return;
        }
        Io.run(new java.util.concurrent.Callable<Pending>() {
            @Override
            public Pending call() {
                return decodePair(imagePath, depthPath, normalPath, focal);
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
     *
     * <p>The focal is a parameter for the same reason it is on the async form: a
     * pair is its files <i>and</i> its camera, and 0 is the row-without-one.
     */
    public boolean loadPair(String imagePath, String depthPath, String normalPath, float focal) {
        return applyPending(decodePair(imagePath, depthPath, normalPath, focal));
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

    /**
     * One debug sentence describing what the renderer actually put on screen.
     *
     * <p>The status line is the only tell for the failures that are otherwise
     * invisible in a build log: an asset nothing reads, or a normal that arrived
     * without being wired. So the shaded and flat-lit states say different things
     * and "flat lit" names the state rather than sounding like a decode error.
     * "flat" without a qualifier stays for the no-depth case, where the map
     * genuinely failed to arrive.
     */
    private static String statusFor(Pending p) {
        String shade = p.suppliedNormal() ? " + normals" : " (flat lit)";
        // The medium first, and above every other branch, because this line is the
        // only tell for a pair that decoded but is not the medium the row asked for.
        // A clip whose tracks are not being played still has a poster frame, and the
        // poster decodes perfectly -- so without this branch a clip reported itself
        // as "custom image + depth map", which is exactly the failure this status
        // line exists for: an asset or a medium nothing is reading, invisible in a
        // build log.
        //
        // A PREVIEW is said out loud rather than reported as a clip with depth: the
        // colour plays, the depth does not exist yet, and a line reading "clip +
        // depth (flat lit)" over a picture whose relief is deliberately flat would
        // be the status line lying about the one thing it is for. The record is what
        // says this state is expected; this line says which state it is.
        if (p.isPreview()) {
            return "video preview, depth pending";
        }
        if (p.isClip()) {
            return "clip + depth" + shade;
        }
        if (p.customImage && p.customDepth) {
            return p.flat ? "custom image, flat (no depth found)"
                    : p.embedded ? "custom image + embedded depth"
                    : "custom image + depth map" + shade;
        }
        if (p.customImage) {
            return p.embedded ? "custom image + embedded depth" : "custom image";
        }
        return p.flat ? "bundled demo, FLAT (no depth found)"
                : "bundled demo + depth" + shade;
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
        if (w == 0 || h == 0) return false;
        float ndcX = (sx / w) * 2f - 1f;
        float ndcY = -(sy / h) * 2f + 1f;
        // In SPLAT mode the tap RAY-CASTS into the cloud: the origin is the image
        // position of the frontmost splat under the ray through the tapped pixel --
        // the same pick SplatCamera.pivot makes for the orbit, so the tap, the pivot,
        // the aim and the crosshair are one answer. It used to set the origin to the
        // tapped SCREEN fraction, which is the source image position only while
        // nothing has shifted the picture, so after the first tap (or with any Pan)
        // it landed away from the point that was touched. The cast is the SOURCE
        // camera's ray, so neither the Depth knob nor the pivot's depth can move it.
        // See SplatRenderer.tapOrigin and SplatCamera.originForRay.
        //
        // The relief path below is unchanged: its surface is a field with one
        // unambiguous depth, so there is nothing to cast -- unprojecting through the
        // pivot height IS its pick.
        if (useSplat()) {
            if (!splat.tapOrigin(ndcX, ndcY, tapOrbit)) {
                // The cloud is on screen (useSplat) but its drawn frame is not
                // recorded yet: the tapped pixel's own fraction is the honest answer
                // rather than a dropped tap.
                tapOrbit[0] = ndcX * 0.5f;
                tapOrbit[1] = ndcY * 0.5f;
            }
            orbitX = Math.max(-0.5f, Math.min(0.5f, tapOrbit[0]));
            orbitY = Math.max(-0.5f, Math.min(0.5f, tapOrbit[1]));
            return true;
        }
        if (displayed == null || !displayed.isDrawable()) return false;
        recomputeScale();
        // The camera on screen aims at the surface under the CURRENT origin, so
        // the unprojection must use the same pivot depth the draw did -- resolved
        // from the displayed render's field and the live orbit, which is what
        // the steady state is drawing. A tap resolved through the plane's camera
        // would land where the picture is not.
        camera.setPivotHeight(ReliefMesh.heightAt(displayed.field, GRID, orbitX, orbitY));
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
        if (GLES3) {
            // The one measured fact that makes the GPU path possible: GLES2's
            // minimum for this is 0, so a vertex shader could never sample depth.
            int[] units = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, units, 0);
            android.util.Log.i("Basrelief", "renderer=gles3 GL_VERSION="
                    + GLES30.glGetString(GLES30.GL_VERSION)
                    + " vertexTextureUnits=" + units[0]);
        }
        program = createProgram(GLES3 ? VERT3 : VERT, GLES3 ? FRAG3 : FRAG);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aUV = GLES20.glGetAttribLocation(program, "aUV");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uModelScale = GLES20.glGetUniformLocation(program, "uModelScale");
        uIntensity = GLES20.glGetUniformLocation(program, "uIntensity");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uNormal = GLES20.glGetUniformLocation(program, "uNormal");
        uHasNormal = GLES20.glGetUniformLocation(program, "uHasNormal");
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
        uFade = GLES20.glGetUniformLocation(program, "uFade");
        uBrightnessGain = GLES20.glGetUniformLocation(program, "uBrightnessGain");
        uShadowLift = GLES20.glGetUniformLocation(program, "uShadowLift");
        uDepth = GLES20.glGetUniformLocation(program, "uDepth");
        uGrid = GLES20.glGetUniformLocation(program, "uGrid");
        uAspect = GLES20.glGetUniformLocation(program, "uAspect");
        uSplat = GLES20.glGetUniformLocation(program, "uSplat");
        uSplatSize = GLES20.glGetUniformLocation(program, "uSplatSize");
        uSplatGrid = GLES20.glGetUniformLocation(program, "uSplatGrid");
        uSplatEye = GLES20.glGetUniformLocation(program, "uSplatEye");
        uFocal = GLES20.glGetUniformLocation(program, "uFocal");
        uRefFocal = GLES20.glGetUniformLocation(program, "uRefFocal");

        markerProgram = createProgram(GLES3 ? MARKER_VERT3 : MARKER_VERT,
                GLES3 ? MARKER_FRAG3 : MARKER_FRAG);
        markerAPos = GLES20.glGetAttribLocation(markerProgram, "aPos");
        markerUMVP = GLES20.glGetUniformLocation(markerProgram, "uMVP");
        markerUColor = GLES20.glGetUniformLocation(markerProgram, "uColor");
        ringBuffer = buildRing(RING_SEGMENTS);

        // The GLES3 grid. Per-context, like every other GL name: the names below
        // are meaningless in this new context until they are rebuilt here.
        gridVbo = 0;
        gridIbo = 0;
        gridIndexCount = 0;
        if (GLES3) buildGrid();

        // The mesh attribute pointers are bound once per install, not per frame
        // (see bindMeshAttribs). That is only safe if the marker's attribute
        // index differs from the mesh's: they come from two separate programs,
        // and the linker is free to hand out the same index to both. If it
        // does, drawing the marker overwrites a mesh pointer, so we re-bind
        // afterwards instead of assuming it cannot happen.
        markerClobbersMesh = markerAPos == aPosition || markerAPos == aUV;

        if (markerClobbersMesh) {
            android.util.Log.w("Basrelief", "marker attribute index " + markerAPos
                    + " collides with a mesh attribute; re-binding after each marker draw");
        }

        // A new surface means a new EGL context, and everything this renderer
        // believed about the old one is now false: every texture name and every
        // mesh cached from it is meaningless here, and the surface is empty
        // again.
        //
        // All of those fields therefore reset together, and each one is a way
        // this has already gone wrong. displayed/incoming: reloadFromPrefs
        // finds the pair it wants is "already showing" and returns without
        // uploading, into a context holding no texture, so the wallpaper is
        // black. loadedOnce: it gates the one blocking decode whose entire
        // purpose is to stop the system snapshotting an empty surface, and a
        // new surface is empty, so keeping it set skips exactly the guarantee
        // that is needed. The result in both cases is a black wallpaper beside
        // a diagnostic reporting the correct photo loaded.
        //
        // This is the same failure as hasInstalled, one level up: there an
        // unset field read as "the demo is loaded", here state belonging to a
        // destroyed context read as still valid. Losing the context is routine
        // for a live wallpaper, because the system tears the engine down and
        // rebuilds it, so this is a normal event and not an error path.
        //
        // The old textures are not deleted: the context that held them is gone,
        // and their names belong to it. Dropping the references is the whole
        // cleanup.
        displayed = null;
        incoming = null;
        pairChangePending = false;
        boundMesh = null;
        hasInstalled = false;
        loadedOnce = false;
        // Every GL name from the old context is gone: the blit programs' cached ids
        // must not be deleted against this one, where they may name something else.
        ClipBlitter.contextGeneration++;

        // A new context means new GL names, so every cached one is gone -- including
        // the clip's three targets and its three SurfaceTextures. Same rule as
        // hasInstalled, one level out: keeping them would make reloadFromPrefs see a
        // pair it thinks is installed, into a context holding no texture at all, and
        // the clip would hand frames to deleted names. The wallpaper loses its
        // context routinely, because the system tears the engine down and rebuilds it.
        stopClip();

        // The splat's GL names belong to the old context too. Clearing the loaded
        // path is what makes the next reloadFromPrefs re-upload: without it the
        // renderer would believe the cloud it wants is already on the GPU, in a
        // context that holds no texture -- the hasInstalled rule, one medium over.
        if (splat != null) splat.onContextLost();
        splatLoadedPath = null;
        splatLoadedId = null;
        splatPendingPath = null;
        splatPendingId = null;
        // A load in flight when the context died must be dropped: its upload would
        // run against a GL context that is gone (the empty-log "compile failed"
        // this now avoids), and the reload below re-requests it anyway.
        splatSeq++;

        reloadFromPrefs();
    }

    /**
     * Bind one render's mesh attribute pointers, and remember which render they
     * belong to. Deliberately NOT per frame in the steady state; see
     * {@link #boundMesh} for why the cross-fade is the one bounded exception.
     *
     * <p>Re-specifying these every frame made the driver re-validate ~12.6 MB of
     * FloatBuffer on every frame for geometry that only changes on load. That was
     * the largest per-frame cost measured on a Snapdragon 835: 9.2 ms of CPU
     * submit, of which the vertex path was the overwhelming majority.
     */
    private void bindMeshAttribs(Render r) {
        r.posBuffer.position(0);
        r.uvBuffer.position(0);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, r.posBuffer);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, r.uvBuffer);
        boundMesh = r;
    }

    /**
     * Put a resolved framing onto the GL thread's state.
     *
     * <p>Taken from {@link Framing} rather than recomputed per surface, because
     * the two surfaces computing it themselves is how they came to disagree.
     */
    /**
     * Glide the whole framing from one surface's config to another's.
     *
     * <p>The wireframe's "a switch, not a cut": the same photo sits two ways, one per
     * surface, and a surface change eases between them over the one glide rate rather
     * than jumping. The caller says where the glide STARTS, because the two callers
     * know different things: the wallpaper engine knows which surface the user was
     * just looking at, and the studio knows what it is currently showing.
     *
     * <p>Driven by the draw clock, not an animator: this renderer is
     * RENDERMODE_WHEN_DIRTY, and a ValueAnimator attached to a view nobody is
     * requesting frames for is an animation that never runs. Every onDrawFrame during
     * the span steps the tween and the values it sets are exactly the volatile fields
     * the rest of the frame already reads -- so the glide composites with the gyro
     * rather than fighting it.
     *
     * <p>Eleven values, all lerped: the framing IS the config, and leaving any one
     * out is a visible pop in the middle of an otherwise smooth motion. The light
     * is the one pair that needs more than {@code a + (b - a) * t}: its azimuth is
     * interpolated through {@link LightDirection#lerpAzimuth}, which takes the
     * short way around the circle rather than spinning 340° the wrong way.
     */
    void glide(Framing from, Framing to, float spanMs) {
        if (to == null) return;
        if (from == null) {
            // "From what is showing": a snapshot of the current volatile values as a
            // Framing, so a caller that does not know where the view was (the studio,
            // mid-preview) can still ease rather than snap.
            from = snapshot();
        }
        if (from == null) {
            apply(to);
            return;
        }
        // The start values are applied BEFORE the glide is armed, in that order.
        // apply() ends a glide in flight -- it is the caller saying where the view
        // IS -- and arming first meant the glide cancelled itself the instant it was
        // created. That shipped: the studio's switch snapped, and the bug read as
        // "the glide does not work" rather than as an ordering mistake.
        apply(from);
        glideFrom = from;
        glideTo = to;
        // The medium the glide is HEADING FOR, carried whole.
        //
        // A glide moves a value smoothly to its destination; the medium is not a
        // value that can be moved, and stepGlide must not lerp it. A lerp from
        // still to clip is a sourceKind of 0.5, which names neither picture — and
        // because `sameAs` compares sourceKind, every frame of the glide would
        // differ from the one before it, so the no-op-paint guard would be defeated
        // for the whole duration and a resolve would paint mid-glide. That is the
        // instant-paint snap, reached the long way round.
        //
        // So the live value stays on `from`'s medium for the duration and switches
        // when the glide lands. In practice a medium change always arrives as
        // Apply.PAIR_CHANGE with a null origin (the pair changed, so there is no
        // "from" to ease away from), which skips the glide entirely; this is here
        // so that if a glide IS armed across a medium boundary it degrades to a
        // switch at the end rather than to a frame-by-frame lie.
        glideSourceKind = to.sourceKind;
        // The draw path, held whole for the same reason. A glide across a mode
        // boundary is unusual -- a toggle arrives as a resolve -- but if one is armed
        // it must land as a switch rather than spend its duration naming a picture
        // that is neither.
        glideRenderMode = to.renderMode;
        // apply(from) above may have ended an earlier glide; this assignment comes
        // after it so a cancelled glide cannot leave this one pointing at a pair that
        // is no longer on screen. Same reason the values are applied before the glide
        // is armed, which shipped as "the glide does not work".
        glideStartNanos = System.nanoTime();
        glideSpanMs = Math.max(1f, spanMs);
    }

    /**
     * Whether a glide is already heading to this framing.
     *
     * <p>Read by the wallpaper so a repeat resolve of the SAME transition does not cancel
     * the glide that is running towards it. See {@link Framing#sameAs} for the measured
     * failure: a plain apply of the glide's own destination, 1.2s after the glide armed.
     */
    boolean isGlidingToward(Framing f) {
        // A resolve has nothing to say if what it wants is either already on screen or
        // already on its way there. "Already on screen" is the half that survives the
        // glide finishing, and it is the user's rule stated properly: a frame is reached
        // by gliding TOWARDS it, so a resolve that asks for the frame already rendered is
        // a no-op whatever the glide is doing. The same comparison is what makes a
        // return to the launcher an instant rather than a spurious glide from lock.
        if (glideTo != null && glideTo.sameAs(f)) return true;
        return snapshot().sameAs(f);
    }

    /** True while a glide is in flight. Volatile-read from any thread. */
    public boolean isGliding() {
        return glideTo != null;
    }

    /**
     * End a glide now, keeping its destination.
     *
     * <p>Called when the user touches the render: a gesture takes control from the
     * tween. Without this the two contest the same values for the rest of the span --
     * the finger sets them, the next frame overwrites them, and the gesture is
     * effectively lost, which reads as the view fighting back.
     *
     * <p>The destination is kept rather than the current interpolated values, because
     * the tween was already on its way there and the user is about to move from
     * wherever they think it is. Must be called on the GL thread.
     */
    void cancelGlide() {
        if (glideTo == null) return;
        Framing end = glideTo;
        glideFrom = null;
        glideTo = null;
        apply(end);
    }

    /** One step of an active glide, called at the top of every drawn frame. */
    private void stepGlide() {
        if (glideTo == null) return;
        float t = (System.nanoTime() - glideStartNanos) / 1_000_000f / glideSpanMs;
        if (t >= 1f) {
            Framing end = glideTo;
            // The medium switches HERE, with the rest of the destination, and not a
            // lerp per frame above. A glide across a medium boundary is unusual --
            // a pair change glides from null, so it skips here entirely -- but if one
            // is armed it must land as a switch rather than spend its duration
            // naming a picture that is neither.
            sourceKind = glideSourceKind;
            renderMode = glideRenderMode;
            glideFrom = null;
            glideTo = null;
            apply(end);
            return;
        }
        // smoothstep, not linear: the same easing the tilt glide uses, so one rate
        // reads as one motion in both places.
        float e = t * t * (3f - 2f * t);
        // NOT interpolated -- see glideSourceKind. The eleven values below are eased;
        // this one is held at the origin's medium for the whole glide and switched by
        // the t >= 1f branch above. Easing it would make every frame differ from the
        // last, which is precisely what sameAs uses to decide that a resolve may
        // no-op, and the result would be a repaint in the middle of a glide.
        depth = lerp(glideFrom.depth, glideTo.depth, e);
        zoom = lerp(glideFrom.zoom, glideTo.zoom, e);
        motion = lerp(glideFrom.motion, glideTo.motion, e);
        orbitX = lerp(glideFrom.orbitX, glideTo.orbitX, e);
        orbitY = lerp(glideFrom.orbitY, glideTo.orbitY, e);
        panX = lerp(glideFrom.panX, glideTo.panX, e);
        panY = lerp(glideFrom.panY, glideTo.panY, e);
        pendingBrightnessGain = lerp(glideFrom.brightnessGain, glideTo.brightnessGain, e);
        pendingShadowLift = lerp(glideFrom.shadowLift, glideTo.shadowLift, e);
        // Angles, with the azimuth's shortest arc; setLight maps to the vector, so
        // the glide never needs the inverse and never carries a stale component.
        setLight(LightDirection.lerpAzimuth(glideFrom.lightAzimuth, glideTo.lightAzimuth, e),
                lerp(glideFrom.lightElevation, glideTo.lightElevation, e));
        // The splat look and shape, eased like the rest: they are continuous render
        // values on the surface being crossed, so a home<->lock glide must move them
        // rather than snap at the end. (The medium and the draw path are the two that
        // are held whole instead -- see glideSourceKind.)
        splatDepthMul = lerp(glideFrom.splatDepth, glideTo.splatDepth, e);
        splatEps2d = lerp(glideFrom.splatEps2d, glideTo.splatEps2d, e);
        splatBlurPx = lerp(glideFrom.splatBlur, glideTo.splatBlur, e);
        splatRenderScale = lerp(glideFrom.splatScale, glideTo.splatScale, e);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * The current volatile values as a Framing, for a glide "from what is showing".
     *
     * <p>Built here rather than handed in by the caller because only this class knows
     * what it is currently drawing: a caller's idea of the current framing is the
     * last one it applied, which a drag, a preview or a fade has already moved.
     */
    private Framing snapshot() {
        // ofValues, not a reflective constructor: only Framing may build a Framing,
        // and a reflective one is a second way that breaks the day a field is added.
        // The light goes back as angles so the glide interpolates the way the
        // stored value does; setLight's vector is derived, not the source.
        //
        // FOURTEEN arguments now, and the fourteenth is the RENDER MODE -- added for
        // the same reason the twelfth and thirteenth were: a snapshot that cannot
        // reproduce a field compares unequal to the frame on screen, and the
        // no-op-paint guard then repaints on every resolve. A relief and a cloud are
        // different pictures, so the mode must be in here or toggling Splat snaps.
        // (The thirteenth was the SPEED; the twelve-argument form was the last caller
        // of the eleven-argument one, which cannot name the medium either.) AGENTS.md:
        // a field ofValues cannot reproduce is a field the no-op-paint guard cannot see.
        //
        // EIGHTEEN now, the last four being the splat look and shape. They are per
        // photo and per surface, so a snapshot that dropped them would compare equal
        // to a frame drawn with a different Depth, Size, Blur or Resolution -- the
        // same dropped-frame failure, one medium over.
        return com.amoshydra.basrelief.core.render.Framing.ofValues(
                depth, zoom, motion, orbitX, orbitY, panX, panY,
                pendingBrightnessGain, pendingShadowLift,
                lightAzimuth, lightElevation,
                sourceKind, renderMode, speed,
                splatDepthMul, splatEps2d, splatBlurPx, splatRenderScale);
    }

    /**
     * Why an {@code apply} is allowed to paint the framing instantly.
     *
     * <p><b>The rule the caller must state.</b> "Don't paint anything instantly. frame
     * can only be rendered by gliding towards not immediately painting." An instant paint
     * is legitimate for exactly two reasons, and a caller has to name which one it is:
     */
    enum Apply {
        /**
         * A resolve of stored state onto the view. The default, and the safe one: if the
         * framing is already what is showing it is a no-op, and if it is not, the caller
         * should have glided (see {@link #glide}).
         */
        RESOLVE,
        /**
         * A live gesture, where the value must lead the finger. A slider drag is the case:
         * an ease here would sit between the finger and the picture.
         */
        GESTURE,
        /**
         * A different photograph is arriving. Its framing is a new picture, and gliding
         * from the old photo's framing would smear two images together.
         */
        PAIR_CHANGE
    }

    /**
     * Put a resolved framing onto the GL thread's state.
     *
     * <p>Taken from {@link Framing} rather than recomputed per surface, because
     * the two surfaces computing it themselves is how they came to disagree.
     *
     * <p>This overload is the {@link Apply#RESOLVE} case; use {@link #apply(Framing,
     * Apply)} to claim a gesture or a pair change.
     */
    void apply(Framing f) {
        apply(f, Apply.RESOLVE);
    }

    /**
     * Put a framing onto the GL thread's state, refusing the instant paint this rule
     * forbids.
     *
     * <p><b>The guard lives HERE, not at the call sites, and that is the whole point.</b>
     * Every bug this session came from one of five call sites resolving a framing and
     * painting it instantly: the wallpaper's unlock origin, its return-to-launcher
     * resolve, the studio's {@code applyFiles} on resume, and the lock-frame pre-hold.
     * Four separate rules at four call sites is four chances to forget, and a sixth caller
     * reintroduces the bug with nothing to stop it. `apply` is the one place every resolve
     * funnels through, so the rule about what a resolve may paint belongs here.
     *
     * <p><b>What it refuses.</b> A {@link Apply#RESOLVE} whose framing is already what is
     * on screen (within {@code Framing.sameAs}'s epsilon) is a no-op: painting it would be
     * an instant paint of a picture that needs no change, and the measured symptom is the
     * snap -- a resolve re-painting a frame a glide is already easing toward. Not touching
     * the volatile fields also leaves any glide in flight alone, which is the point:
     * a resolve that agrees with a running glide must not cancel it.
     *
     * <p><b>Why the other two reasons bypass it.</b> A gesture must lead the finger even
     * when the value has not visibly moved yet, and a pair change is a genuinely different
     * picture that {@code sameAs} would report as different anyway -- but stating the
     * reason is what stops a future caller inheriting the no-op by accident, or inheriting
     * the paint by accident. The default is the SAFE one: {@link Apply#RESOLVE}.
     */
    void apply(Framing f, Apply why) {
        if (why == Apply.RESOLVE && isGlidingToward(f)) {
            // Same picture, either already showing OR already on its way here.
            //
            // The first version of this guard asked only `snapshot().sameAs(f)`, and that
            // is FALSE mid-glide -- the volatile values are between the endpoints, so they
            // match neither the origin nor the destination. So the guard protected a
            // frame that had ARRIVED and not one in flight, which is the case it exists
            // for: a resolve landing during a glide cancelled it and painted the
            // destination instantly. The wallpaper compensated by calling
            // isGlidingToward itself; every other caller -- the studio's applyFiles among
            // them -- did not, and snapped.
            //
            // Checking BOTH is the whole rule: a resolve has nothing to say if the
            // picture it asks for is on screen or on its way there.
            return;
        }
        depth = f.depth;
        zoom = f.zoom;
        motion = f.motion;
        orbitX = f.orbitX;
        orbitY = f.orbitY;
        setPan(f.panX, f.panY);
        // Set here as well as in setAdjust, so a resolve always wins. Without it,
        // selecting a second photo while the first is still previewed at
        // Brightness +40 would carry that preview onto the new photo -- the draw
        // loop has no other way to learn the value changed.
        pendingBrightnessGain = f.brightnessGain;
        pendingShadowLift = f.shadowLift;
        // Set here as well as in the setters, so a resolve always wins. The light
        // is one call because its angles and vector must never be updated apart.
        setLight(f.lightAzimuth, f.lightElevation);
        // The draw path, carried from the framing so the snapshot can name it. A
        // toggle to Splat therefore makes sameAs report a different picture and the
        // apply is not a no-op; the cloud's own bytes are still a separate question
        // (useSplat), so the relief draws for the frame or two before it loads.
        renderMode = f.renderMode;
        // The splat look and shape, per photo and per surface. Set here with the rest
        // of the render values so a resolve always wins, and so the renderer's own
        // snapshot reports them and the no-op-paint guard can see a change. The store
        // clamps them; SplatRenderer.draw clamps again at the uniform.
        splatDepthMul = f.splatDepth;
        splatEps2d = f.splatEps2d;
        splatBlurPx = f.splatBlur;
        splatRenderScale = f.splatScale;
        // An explicit apply ends any glide in flight: it is the caller saying where
        // the view IS, and a tween arguing with that is a smear, not an ease.
        glideFrom = null;
        glideTo = null;
    }

    /**
     * The framing another engine was showing when this one started, or null.
     *
     * <p><b>Set by the host, consumed by the cold load.</b> On an unlock the home engine
     * is handed the LOCK engine's last framing so its FIRST frame is what the user was
     * already looking at, and it glides from there. Without it the cold load paints its
     * own framing and every later glide is a correction -- the measured home, lock, home
     * flicker.
     *
     * <p>Null is the safe default and means "no inheritance": a reboot, a first install,
     * or a screen-on into the keyguard all take the normal instant apply, because in none
     * of those was another engine showing something this one should continue from.
     */
    private volatile Framing inheritFirstFrame;

    /** The span to use for that first inherited glide, set with the framing. */
    private volatile float inheritSpanMs = 250f;

    /**
     * Hand this renderer the framing to glide from on its first frame.
     *
     * <p>Called by the wallpaper for a HOME engine that is starting BECAUSE the device
     * was just unlocked. Passing null is the normal case and clears any pending one.
     */
    public void inheritFirstFrameFrom(Framing from, float spanMs) {
        this.inheritFirstFrame = from;
        this.inheritSpanMs = Math.max(1f, spanMs);
    }

    public void reloadFromPrefs() {
        SharedPreferences p = context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
        // The Stage-0 card grid, dev-only now (see the field): read HERE, with the
        // other cold-load state, because this is the one path both surfaces converge
        // on, so it can be flipped by adb and A/B-ed without a rebuild. OFF by
        // default, and it draws only when the row has no real cloud. Its key is NOT
        // `render_3d`: that is the migration source for the per-item mode, and
        // reading it here would draw the card grid over every SPLAT row with no cloud.
        splatGridMode = p.getBoolean(KEY_SPLAT_GRID, false);
        // The level, read here with it: an index into Splat.GRID_LEVELS, and any
        // stored value that is not one draws the default (the field's own
        // sampling). Reset per context in onSurfaceCreated, because the log line
        // that says which grid a context actually draws should appear once per
        // context rather than once per process.
        splatGrid = Splat.gridForLevel(p.getInt(KEY_RENDER_3D_LEVEL, 0));
        splatLevelLogged = false;
        // The index owns the selection, so one dereference gives the files and the
        // settings together and cannot come back naming a photo that was deleted.
        // Loaded once: this runs on the GL thread at cold start, and each load
        // re-reads and re-validates the whole gallery.
        final AppState state = AppState.load(context, p, context.getFilesDir());
        final LibraryItem active = state.activeItem();
        // The photograph's camera, from the row and from nothing else. It is part
        // of the pair identity the load below carries (see showingSamePair), so a
        // row that gained a focal while the wallpaper was up is re-installed
        // rather than left rendering through the lens it no longer names.
        final float focal = active == null ? 0f : active.focal;
        // The medium is armed HERE, not only where the activity asks for it, because
        // this is the one path both surfaces converge on -- the studio's cold start
        // and the wallpaper's engine both come through here -- and arming it only at
        // the activity's applyFiles meant a fresh launch resolved and installed the
        // pair with nothing having said whether it was a clip. The poster frame then
        // rendered and stayed there: a video that never started, with a perfectly good
        // clip on disk and nothing anywhere saying so.
        //
        // And the PREVIEW is resolved through the same one derivation, for the same
        // reason: a launch (or a wallpaper engine rebuild) while an import is running
        // must show the moving colour immediately, not the poster -- the record is on
        // disk, and a cold resolve is exactly the moment the user notices it was
        // ignored. JobsStore owns the file half; Jobs owns the precedence rule.
        //
        // Arming rather than starting, because the medium belongs to the install that
        // is about to happen and not to the request -- see requestClip.
        final java.io.File preview =
                JobsStore.previewColour(p, context.getFilesDir(), active);
        requestClip(active == null ? null : active.video, preview, context.getFilesDir());
        // One resolution, shared with the wallpaper: they used to disagree about
        // the bundled item, and the studio was the one that got it wrong.
        // The preview goes in as the medium, because that is what the renderer will
        // report once the install lands -- see Framing.of's three-argument form.
        Framing mine = Framing.of(state.activeItem(), editSurface, preview != null);
        // The rate with it. reloadFromPrefs is the one path both surfaces converge
        // on, so a cold engine starts a clip at the speed the item stores -- the
        // studio and the wallpaper cannot disagree about it.
        setSpeed(mine.speed);
        // THE FIRST FRAME OF AN UNLOCK IS THE LOCK SCREEN, NOT THIS SCREEN.
        //
        // This line is the `1x` the user sees before the snap: the cold load paints
        // home's own framing, and only afterwards does the service arm a glide back to
        // lock's -- so the sequence is home, lock, home, with a visible flicker and a
        // correction. Their instruction was explicit: when handing over to the home engine, the last frame should be taken from the lock engine and glided
        // from rather than drawn again. That is exactly right, and the lock engine's framing is
        // available because both engines share this process.
        //
        // Only on an unlock, and the two terms are both facts rather than guesses:
        //   - inheritedFraming is non-null only when a LOCK engine drew and published
        //     what it was showing, so a reboot or a first install cannot glide from a
        //     screen that was never on; and
        //   - the service clears it when the device is locked, so a screen-on into the
        //     keyguard cannot make home glide from a value it is not destined for.
        Framing inherit = inheritFirstFrame;
        if (inherit != null) {
            inheritFirstFrame = null;   // one unlock, one inheritance
            glide(inherit, mine, inheritSpanMs);
        } else {
            apply(mine);
        }
        // Resolved from the item and from nothing else. There is no branch for "the
        // demo": since the seed copied its pair into filesDir it is a file-backed row
        // like any other, and a null item -- nothing selected -- resolves to nothing
        // and is drawn as the packaged demo pair, which is what a delete of the active
        // item leaves behind.
        //
        // Stored as bare filenames; resolve against this install's filesDir so a
        // restored backup still finds them. Null means it is not present here,
        // which retainExisting has already pruned from the index.
        java.io.File img = PrefFiles.resolve(context.getFilesDir(),
                active == null ? null : active.imageName);
        java.io.File dep = PrefFiles.resolve(context.getFilesDir(),
                active == null ? null : active.depthName);
        java.io.File nrm = PrefFiles.resolve(context.getFilesDir(),
                active == null ? null : active.normalName);
        // The splat look and shape are read from the resolved Framing above, not
        // from prefs: they are per photo and per surface now, and `apply(mine)` (or
        // the glide) has already set the four volatile fields from the row. Reading
        // the old global prefs here would be a second source that disagrees with the
        // row the renderer just resolved.
        setSplatForItem(active == null ? null : active.splatName,
                active != null && active.isSplatMode(),
                active == null ? 0f : active.splatLens);
        load(img == null ? null : img.getAbsolutePath(),
                dep == null ? null : dep.getAbsolutePath(),
                nrm == null ? null : nrm.getAbsolutePath(),
                focal);
    }

    /**
     * Resolve and (re)load the splat for one item. GL thread.
     *
     * <p><b>The rule: a splat renders only when the ITEM names one.</b> The item's
     * own nullable filename is the whole shape, and it resolves through
     * {@link PrefFiles}, so it is a bare filename and a restore onto another device
     * still finds it. There is no fallback: the Stage-0 {@code splat_file} pref is
     * gone, because the demo's cloud ships in {@code res/raw} and the ordinary seed
     * names it on the bundled row like any other photo's cloud.
     *
     * <p>Both paths resolve through {@link PrefFiles}, so both are bare filenames
     * and a restore onto another device still finds them.
     *
     * <p><b>The mode is the ITEM's, not a global.</b> {@code splatMode} is
     * {@code LibraryItem.isSplatMode()} — a render value on the row, migrated once
     * from the legacy {@code render_3d} pref. A row with a cloud and the mode set to
     * Relief renders its relief, exactly as a row with no cloud does; a row asking
     * for Splat but with no cloud yet also renders its relief, which is what makes
     * "press Splat and the offer follows" honest.
     */
    void setSplatForItem(String itemSplatName, boolean splatMode, float cloudLens) {
        // The lens rides with the path, before the mode gate: it is a fact about
        // the row, and a row that asks for the relief has one too. 0 is "unknown"
        // and draws at the demo lens.
        splatWantedLens = cloudLens;
        // The mode is the ITEM's, and a row that asks for the relief wants no cloud
        // even if it has one -- a switch back to Relief must stop the cloud drawing
        // on the next frame, which clearing the wanted path is what does.
        if (!splatMode) {
            splatWantedPath = null;
            return;
        }
        // The item's own filename, and nothing else. The bundled demo used to be the
        // one exception, delivered by a `splat_file` debug pref because Stage 0 had no
        // import UI; Stage 1 ships the demo's cloud in res/raw and the ordinary seed
        // names it on the row, so the pref and its fallback are gone -- the demo is an
        // ordinary 3D row now.
        java.io.File spf = PrefFiles.resolve(context.getFilesDir(), itemSplatName);
        splatWantedPath = spf == null ? null : spf.getAbsolutePath();
        requestSplat(splatWantedPath);
    }

    /** The bytes behind a cloud path, or 0 when it cannot be read. See Splat.cloudIdentity. */
    private static long splatBytes(String path) {
        java.io.File f = new java.io.File(path);
        return f.isFile() ? f.length() : 0L;
    }

    /**
     * Load a splat cloud off the GL thread and upload it on it.
     *
     * <p>The same shape as {@link #loadPairAsync}: the file read and the position
     * mirror are the expensive part and must not sit between two frames of a live
     * wallpaper, so they run on the {@code Io} executor and only the texture upload
     * is queued to the GL thread. A result whose sequence is stale is dropped, the
     * same rule the still pair follows, because two loads can finish out of order.
     *
     * <p>{@code splatLoadedPath} is written only after a successful upload, so a
     * request that fails or is still in flight leaves the previous cloud — or the
     * relief — on screen rather than claiming a cloud that is not there.
     */
    private void requestSplat(final String path) {
        if (path == null) return;
        // The identity is the path AND the bytes behind it; see Splat.cloudIdentity.
        // A rebuild writes the SAME filename, so a path-only identity answers
        // "already showing it", the request is dropped, and the new cloud never
        // reaches the screen -- which is what a rebuild appearing to do nothing was.
        final String id = Splat.cloudIdentity(path, splatBytes(path));
        if (id.equals(splatLoadedId) || id.equals(splatPendingId)) return;
        splatPendingPath = path;
        splatPendingId = id;
        final int seq = ++splatSeq;
        final java.util.concurrent.Executor glThread = glDispatch;
        Io.run(new java.util.concurrent.Callable<SplatCloud>() {
            @Override
            public SplatCloud call() throws Exception {
                SplatCloud c = SplatCloud.read(new java.io.File(path));
                // Warm the median depth HERE, on the IO thread, where the file was
                // already read: the upload reads it for the pivot fallback, and a
                // sort on the GL thread is the kind of stall this whole path exists
                // to avoid.
                c.medianDepth();
                return c;
            }
        }, new Io.Callback<SplatCloud>() {
            @Override
            public void onDone(SplatCloud cloud, Exception error) {
                final SplatCloud c = error != null ? null : cloud;
                if (glThread == null) {
                    applySplat(path, c, seq);
                    return;
                }
                glThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        applySplat(path, c, seq);
                    }
                });
            }
        }, glThread);
    }

    /** Upload a loaded cloud on the GL thread, unless a newer request superseded it. */
    private void applySplat(String path, SplatCloud cloud, int seq) {
        if (seq != splatSeq) return;
        // This request is resolved either way, so it must stop blocking a retry:
        // requestSplat skips a path that is already pending, and a failed upload
        // that stayed "pending" would never be re-asked.
        splatPendingPath = null;
        splatPendingId = null;
        if (splat == null) splat = new SplatRenderer();
        if (cloud == null || !splat.upload(cloud)) {
            android.util.Log.w("Basrelief", "splat: could not load " + path
                    + (cloud == null ? " (read failed)" : " (upload failed)"));
            splatLoadedPath = null;
            splatLoadedId = null;
            return;
        }
        splatLoadedPath = path;
        splatLoadedId = Splat.cloudIdentity(path, splatBytes(path));
        android.util.Log.i("Basrelief", "splat: loaded " + path + " count=" + splat.count());
        // PAINT IT. The upload runs on a GL event that the host queued, and the
        // host's own requestRender() may already have been consumed before the file
        // read finished — so without this a cloud at Motion 0 (sensor parked, no
        // clip pump) would load and never appear until the next tilt. The loaded
        // path is the on-screen identity, and this is the frame that makes it true.
        Runnable r = renderRequester;
        if (r != null) r.run();
    }

    /**
     * Load a pair, inlining the very first one when the surface must not be empty.
     *
     * <p>Both callers go through here, and the packaged demo pair going through here
     * is the point. It used to take the async path unconditionally, which for a live
     * wallpaper means the system snapshots the surface roughly 100 ms after it
     * appears and the decode has not finished, so the snapshot is of an empty
     * surface and the home screen is black -- while the engine went on to install
     * the photo a second later and reported it loaded. The symptom appeared
     * intermittent, because a warm PairCache could make the async load land inside
     * the window, which is a far worse bug than a consistent one: it looks like a
     * rendering fault rather than a missing guarantee.
     */
    private void load(String imgAbs, String depAbs, String nrmAbs, float focal) {
        // Off the GL thread: the read, decode and mesh build are seconds of work
        // and must not sit between two frames of a live wallpaper. The dispatcher
        // comes from the host (see setGlDispatch), which both surfaces set before
        // this can run.
        if (syncFirstLoad && !loadedOnce) {
            loadedOnce = true;
            // Inline on purpose: see syncFirstLoad. Blocks this thread for one
            // decode so the surface is never empty when the system snapshots it.
            applyPending(decodePair(imgAbs, depAbs, nrmAbs, focal));
        } else {
            loadPairAsync(imgAbs, depAbs, nrmAbs, focal);
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
        // A resize invalidates a request made against the old dimensions, so drop it
        // rather than capture at a size the framebuffer no longer has.
        wantLockFrame = false;
        // Now that a bound exists, apply it: a pan stored on a large screen and
        // restored on a small one must not be allowed to push the relief
        // off-screen. Safe to call from the GL thread — panX/panY are only
        // written here and by setPan, both on this thread.
        setPan(panX, panY);
        surfaceAspect = (float) width / height;
        recomputeScale();
    }

    /**
     * Whether the Gaussian cloud, rather than the relief, is what should draw.
     *
     * <p>Three facts, and all three must hold: the item's mode resolved a cloud for
     * this row ({@code splatWantedPath}), the GL context holds exactly that cloud
     * ({@code splatLoadedPath}), and the upload reported success
     * ({@code splat.isReady}). The middle one is the {@code hasInstalled} rule: a
     * request that is still in flight, or that failed, must not be treated as on
     * screen — the failure the wallpaper paid for twice, once as a black surface
     * and once as a cloud that never retried.
     */
    private boolean useSplat() {
        String want = splatWantedPath;
        return want != null && splat != null && splat.isReady() && want.equals(splatLoadedPath);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // --- frame clock: pace draws explicitly ---
        long now = System.nanoTime();
        // First, so a glide composites with the gyro for the whole frame: every value
        // below reads what this just wrote.
        stepGlide();
        // NOTE: there is deliberately NO frame-rate gate here. See the note on
        // FPS_NORMAL below before adding one back.

        // The clip's frames first, before anything reads a texture: updateTexImage and
        // the blit are what make this frame's colour, depth and normals exist, and a
        // draw that ran ahead of them would show the previous frame's depth against
        // this frame's colour -- a relief that does not match its picture.
        if (clip != null && clip.isRunning()) {
            clipDraws++;
            int l = clip.tick();
            // One line a second, because "is it actually playing" cannot be answered
            // by anything else. The log proves the decoders STARTED; only a climbing
            // frame count proves they are still running, and a clip that has stopped
            // advancing is indistinguishable from one that never started -- both show
            // a single correct frame.
            long nowClip = now;
            if (lastClipStatNanos == 0) lastClipStatNanos = nowClip;
            if (nowClip - lastClipStatNanos >= 1_000_000_000L) {
                lastClipStatNanos = nowClip;
                // THE WATCHDOG, once a second, where the stats already are.
                int total = clip.producedTotal();
                long nowMs = System.currentTimeMillis();
                if (total != watchdogProduced) {
                    watchdogProduced = total;
                    watchdogChangedAtMs = nowMs;
                } else if (nowMs - watchdogChangedAtMs > CLIP_STALL_MS) {
                    android.util.Log.w("Basrelief", "clip produced nothing for "
                            + (nowMs - watchdogChangedAtMs) + "ms -- restarting it");
                    // From what is RUNNING first, the armed request only as a
                    // fallback. Starting a clip clears the arm it started from
                    // (stopClip drops pendingTracks/pendingPreview), so a clip that
                    // stalled twice had nothing left to restart from -- and a preview
                    // never had pendingTracks at all. Both left `clip` null, and the
                    // stats below then crashed on it: eleven FATALs on the Pixel
                    // between 10-07 and 10-10, every one at this block.
                    com.amoshydra.basrelief.core.store.VideoTracks t =
                            runningTracks != null ? runningTracks : pendingTracks;
                    java.io.File p = runningPreview != null ? runningPreview : pendingPreview;
                    java.io.File dir = runningDir != null ? runningDir : pendingFilesDir;
                    stopClip();
                    boolean restarted = false;
                    if (t != null) restarted = startClip(t, dir);
                    else if (p != null) restarted = startPreview(p, dir);
                    watchdogProduced = -1L;
                    // A restart begins a new window, so the latch baseline starts
                    // over with it: without this the next line reports a delta across
                    // the restart, which is negative while the new player is younger
                    // than the old baseline.
                    lastLatch = 0;
                    if (!restarted) {
                        android.util.Log.w("Basrelief", "watchdog: nothing restarted ("
                                + (t == null && p == null ? "no running medium" : "start refused") + ")");
                    }
                }
                // THE GUARD, and the half of the fix that would have stopped the
                // crash on its own. stopClip() above is allowed to leave no player
                // running -- that is exactly what a refused start means -- and every
                // read below dereferences `clip`: latches(), stats(),
                // producedCounts(), colourTex(). A stopped clip has no stats to
                // report, so there is nothing to say and nothing to crash on.
                if (clip != null) {
                    // Rolling counters for the next window, so the line reports per
                    // second rather than per process.
                    latchDelta = clip.latches() - lastLatch;
                    // `usingClip` is the whole question. A clip that DECODES and is never
                    // BOUND is indistinguishable from a frozen one -- both show one correct
                    // frame -- so the line says which texture the last draw actually used,
                    // and whether that was the clip's or the still's.
                    int cc = clipColour(displayed);
                    android.util.Log.i("Basrelief", "clip " + clip.stats(debugLayer != 0)
                            + " produced=" + clip.producedCounts()
                            + " texColour=" + cc
                            + " clipTex=" + clip.colourTex()
                            + " stillTex=" + (displayed == null ? -1 : displayed.textureColor)
                            + " usingClip=" + (cc != 0 && cc == clip.colourTex())
                            + " draws/s=" + (clipDraws - lastClipDraws)
                            + " latch/s=" + latchDelta);
                    // AFTER the line: the next window's baseline. Doing it above the log
                    // made the line report the PREVIOUS window's draws, which is exactly
                    // the sort of off-by-one-window that reads as a pump running when it
                    // is not.
                    lastClipDraws = clipDraws;
                    lastLatch = clip.latches();
                }
            }
        }

        recomputeScale();
        float dt = Math.min((now - lastFrameNanos) / 1e9f, 0.1f);
        lastFrameNanos = now;
        float alpha = 1f - (float) Math.exp(-dt / SMOOTHING_TAU);
        // Fast attack: a big error means the user is moving, so follow it at once
        // instead of easing in over ~460 ms. See ATTACK_ERROR.
        smoothX = approach(smoothX, targetX, alpha);
        smoothY = approach(smoothY, targetY, alpha);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // The Gaussian cloud draws INSTEAD of the relief when the 3D switch is on
        // and this row names a cloud that is actually uploaded. The relief's pair
        // is still loaded underneath, so the switch is instant to flip back and a
        // cloud that fails to load falls back to the picture with no error path.
        if (useSplat()) {
            // The cloud is the picture, so the relief's medium must not keep
            // decoding behind it: a clip would otherwise run its three decoders
            // for a frame nobody sees, and its pump would drive the surface at the
            // clip's rate instead of the gyro's -- which is not the wallpaper's
            // model. Stopped once, when it is running; the next resolve re-arms it
            // if the switch goes back off.
            if (clip != null && clip.isRunning()) stopClip();
            SplatCamera.Frame sf = splat.draw(surfaceW, surfaceH, smoothX, smoothY, motion, zoom,
                    orbitX, orbitY, panX, panY,
                    pendingBrightnessGain, pendingShadowLift, splatRenderScale,
                    splatWantedLens, splatEps2d, splatBlurPx, splatDepthMul);
            // The readout reads the splat's own window here, not the relief's: the
            // numbers on the glass are the ones the splat's log just printed. The
            // relief's own window is abandoned while the cloud draws, so the next
            // relief frame starts a fresh one rather than reporting a rate over the
            // whole span the splat was on screen.
            perfStats = splat.perfStats();
            perfWindowStartNanos = 0L;
            perfFrames = 0L;
            // The orbit crosshair belongs to the cloud too, and this branch used to
            // return before it was ever drawn -- so a row in Splat mode had no origin
            // indicator at all, which is the one place the pivot is otherwise
            // invisible. The frame the cloud was DRAWN with is the frame the marker
            // is drawn with, so the crosshair, the aim and the reference eye cannot
            // name different pivots.
            if (showOrbitMarker && sf != null) {
                // The cloud's draw may leave the viewport at its offscreen render
                // size; the marker is a surface overlay.
                GLES20.glViewport(0, 0, Math.round(surfaceW), Math.round(surfaceH));
                drawOrbitMarker(sf);
            }
            captureLockFrameIfWanted();
            return;
        }

        if (displayed == null || !displayed.isDrawable()) {
            // Nothing to draw yet. The readback is still offered so a lock-frame
            // request is not silently dropped on an empty surface; it will
            // capture black once, which is the honest answer there.
            captureLockFrameIfWanted();
            return;
        }

        if (incoming == null) {
            // Steady state: the pair on screen follows every live write, so a
            // slider, a preview or a surface glide is visible immediately. A
            // pending pair change and a fade both opt out and draw from each
            // render's own frozen framing instead; see pairChangePending.
            if (!pairChangePending) captureFraming(displayed);
            CameraModel.Frame f = drawRender(displayed, 1f);
            if (showOrbitMarker) drawOrbitMarker(f);
            if (debugLayer != 0) drawDebugQuad(displayed);
        } else {
            float t = (now - fadeStartNanos) / 1_000_000f / CROSS_FADE_MS;
            if (t >= 1f) {
                finishFadeNow();
                CameraModel.Frame f = drawRender(displayed, 1f);
                if (showOrbitMarker) drawOrbitMarker(f);
            } else {
                // Two complete reliefs, blended. The outgoing pair draws
                // opaque, the incoming over it at alpha t, so the glass shows
                // old*(1-t) + new*t. The depth buffer is cleared between them
                // so the incoming mesh is self-occluded correctly instead of
                // being culled by the outgoing one: the geometry is blended as
                // two full renders, not as one mesh wearing two textures.
                drawRender(displayed, 1f);
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
                GLES20.glEnable(GLES20.GL_BLEND);
                // Separate destination alpha: the RGB blend is the cross-fade,
                // and the framebuffer's alpha is kept at 1 so a surface that
                // composites alpha cannot go translucent mid-dissolve.
                GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA,
                        GLES20.GL_ONE_MINUS_SRC_ALPHA,
                        GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                CameraModel.Frame f = drawRender(incoming, t);
                GLES20.glDisable(GLES20.GL_BLEND);
                if (showOrbitMarker) drawOrbitMarker(f);
                // RENDERMODE_WHEN_DIRTY: nobody else is guaranteeing frames
                // for the span, so the fade pumps itself through the host's own
                // requester -- the same mechanism install() uses, and NOT a
                // frame-rate gate. A null requester simply means the fade
                // advances on whatever frames happen next.
                Runnable r = renderRequester;
                if (r != null) r.run();
            }
        }

        // Account the frame for the readout, and publish a window once a second. Here
        // rather than at the top because only a frame that actually drew is a frame
        // the readout should count; the splat branch above publishes its own.
        accountReliefFrame(now);

        // Last thing in the frame, and deliberately so: the readback needs the pixels
        // that were just drawn, and it stops being possible once the buffer swaps.
        captureLockFrameIfWanted();
    }

    /**
     * Draw one complete render: its mesh, its texture, its framing.
     *
     * <p>Called once per frame in the steady state and twice per frame during a
     * cross-fade. The camera and the adjustment uniforms come from the render,
     * not from the live fields, because during a fade the two renders disagree
     * about Depth, Zoom and Pan by design -- that disagreement is what makes the
     * transition a blend of two photographs instead of the old photograph
     * wearing the new one's depth.
     *
     * @param alpha the cross-fade weight; 1 draws opaque
     * @return the camera frame, for the orbit marker
     */
    private CameraModel.Frame drawRender(Render r, float alpha) {
        Framing fr = r.framing;
        camera.setAspect(r.aspect);
        camera.setSurfaceAspect(surfaceAspect);
        camera.setZoom(fr.zoom);
        camera.setDepth(fr.depth);
        camera.setMotion(fr.motion);
        // The pivot is the surface under the origin: the render's own field,
        // sampled at the origin's image position. It must be set before frame(),
        // which resolves the pivot's world z with the scale and zScale it is
        // about to compute. The render's field is used rather than the live one
        // for the same reason the framing is: during a fade two photographs are
        // on screen and each aims at its own subject.
        camera.setPivotHeight(ReliefMesh.heightAt(r.field, GRID, fr.orbitX, fr.orbitY));
        // One shared reusable Frame, so this allocates nothing; its values must
        // be consumed before the next call, which the uniform uploads do.
        CameraModel.Frame f = camera.frame(surfaceW, surfaceH, fr.panX, fr.panY,
                fr.orbitX, fr.orbitY, smoothX, smoothY);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, f.mvp, 0);
        GLES20.glUniform1f(uModelScale, f.scale);
        GLES20.glUniform1f(uIntensity, f.zScale);
        GLES20.glUniform3f(uLightDir, r.lightX, r.lightY, r.lightZ);
        GLES20.glUniform1f(uBrightnessGain, fr.brightnessGain);
        GLES20.glUniform1f(uShadowLift, fr.shadowLift);
        GLES20.glUniform1f(uFade, alpha);

        // The clip's textures when one is playing, the still's otherwise. Three
        // substitutions and nothing else about the draw changes -- the shaders, the
        // grid and the camera are already per-frame, which is why a clip needs no
        // re-tessellation at all.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clipColour(r));
        GLES20.glUniform1i(uColor, 0);

        // The supplied normal map, or the default texture when there is none.
        // uHasNormal is what makes the fragment shader choose the flat plane, so a
        // texture name of 0 is never sampled as a normal and never a success. GLES3
        // already uses unit 1 for the depth field, so the normal takes unit 2 there
        // and unit 1 on GLES2.
        int normalUnit = GLES3 ? 2 : 1;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + normalUnit);
        int normalTex = clipNormal(r);
        // A clip's normal track is OPTIONAL, exactly as a still's normal map is, and
        // uHasNormal is what makes the fragment shader choose the flat plane. A name
        // of 0 is never sampled as a normal and never a success.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, normalTex > 0 ? normalTex : 0);
        GLES20.glUniform1i(uNormal, normalUnit);
        GLES20.glUniform1f(uHasNormal, normalTex > 0 ? 1f : 0f);

        if (GLES3) {
            // The static grid, bound fresh every draw: two pointer calls into
            // VBOs is cheap, and it makes the marker's possible attribute-index
            // collision harmless rather than something to remember.
            GLES20.glUniform1f(uGrid, GRID);
            GLES20.glUniform1f(uAspect, r.aspect);
            // uSplat is set by the BRANCH THAT DRAWS, not here. It selects the
            // shader's card arithmetic, so a value that disagreed with the geometry
            // would run that arithmetic over the relief's vertices -- which is
            // exactly what happened when the cards stopped reading aCorner: the
            // compiler dropped the attribute, the old guard fell back to the
            // relief, uSplat stayed 1, uSplatGrid was 0, sx / uSplatGrid was NaN,
            // and every triangle was discarded with no error anywhere.
            //
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clipDepthField(r));
            GLES20.glUniform1i(uDepth, 1);
            if (splatGridMode && splatWantedPath == null) {
                drawSplatGrid(r, f);
            } else {
                GLES20.glUniform1f(uSplat, 0f);
                GLES20.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo);
                GLES20.glEnableVertexAttribArray(aPosition);
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, 0);
                GLES20.glEnableVertexAttribArray(aUV);
                GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, 8);
                GLES20.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gridIbo);
                GLES20.glDrawElements(GLES30.GL_TRIANGLES, gridIndexCount,
                        GLES30.GL_UNSIGNED_INT, 0);
            }
            // Leave buffer 0 bound for the marker's client-side ring buffer; a
            // bound VBO would make glVertexAttribPointer read the ring as an
            // offset into the grid.
            GLES20.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        } else {
            // Attribute pointers are only re-specified when the draw switches
            // renders; see boundMesh. In the steady state that is once per load --
            // the measured optimisation -- and during a fade it is once per draw,
            // bounded by CROSS_FADE_MS.
            if (boundMesh != r) bindMeshAttribs(r);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, r.vertexCount);
        }
        return f;
    }

    /**
     * Draw the point-splat cloud in place of the relief: six vertices per sample,
     * every one of them derived in the vertex shader from {@code gl_VertexID}.
     *
     * <p><b>No buffer, no build, and the resolution is a uniform.</b> The card
     * grid used to be one quad per sample in a VBO -- 18 MB at the bottom level
     * and a measured 7.4 s build (the ANR the user saw), 71 MB at the top one.
     * Deriving the sample, its corner and its UV from the vertex id costs two
     * integer divisions per vertex and makes the level a number the pref can move:
     * the whole draw is one uniform block and one {@code glDrawArrays}.
     *
     * <p>Everything about the camera comes from the same place the relief's does
     * -- {@code uMVP}, {@code uModelScale} and {@code uIntensity} are already
     * uploaded by {@link #drawRender} -- so pan, orbit, tilt and zoom drive the
     * cloud exactly as they drive the surface, and there is one camera in the
     * class rather than two. The one extra uniform is the eye the cards are
     * placed against, which is that same frame's camera with the TILT dropped.
     */
    private void drawSplatGrid(Render r, CameraModel.Frame f) {
        GLES20.glUniform1f(uSplat, 1f);
        GLES20.glUniform1f(uSplatGrid, splatGrid);
        GLES20.glUniform1f(uSplatSize, Splat.cardSize(splatGrid));
        // THE REFERENCE EYE IS THE CAMERA AT REST, NOT THE MOVING ONE, and it is
        // the same two terms CameraModel.frame builds the eye from with the tilt
        // left out: the pivot's world x and y -- taken from the frame, so the
        // eye, the aim point and the marker cannot name different pivots -- and
        // the fixed camera distance. The splat branch places every card on a ray
        // from this eye, so it must be the camera the photograph is pinned to.
        // Feeding it the moving eye instead lands every card on its own pixel's
        // ray and the depth stops being visible at all: no parallax, no gaps, at
        // any Depth or tilt.
        GLES20.glUniform3f(uSplatEye, f.pivotX, f.pivotY, CameraModel.CAM_DIST);
        // The row's focal, or the documented default when it has none, and the
        // default itself as the reference the shader divides by. Both resolved in
        // :core, where the fallback is a tested function of the image height
        // (SplatTest) -- the shader receiving a second copy of that arithmetic
        // would be two answers to "what does an unknown focal draw at".
        GLES20.glUniform1f(uFocal, Splat.effectiveFocalPx(r.focal, r.imageH));
        GLES20.glUniform1f(uRefFocal, Splat.fallbackFocalPx(r.imageH));
        // The shader reads NO attribute: enabled arrays it does not use are
        // attribute state the relief's draw re-specifies anyway, but leaving them
        // enabled while they point into a buffer this draw does not bind is how a
        // driver that fetches anyway reads garbage.
        if (aPosition >= 0) GLES20.glDisableVertexAttribArray(aPosition);
        if (aUV >= 0) GLES20.glDisableVertexAttribArray(aUV);
        int samples = (splatGrid + 1) * (splatGrid + 1);
        GLES20.glDrawArrays(GLES30.GL_TRIANGLES, 0, samples * 6);
        if (!splatLevelLogged) {
            splatLevelLogged = true;
            android.util.Log.i("Basrelief", "splat level grid=" + splatGrid
                    + " samples=" + samples + " verts/frame=" + (samples * 6));
        }
    }

    /**
     * The studio's orbit crosshair, drawn over a render.
     *
     * <p>Takes the FRAME the render was drawn with rather than reading the live
     * orbit, so the marker sits on the photograph that was actually drawn:
     * during a pending pair change or a cross-fade the live values already
     * belong to the incoming photo while the outgoing one is still on the
     * glass, and the frame's pivot is that render's own aim point.
     */
    private void drawOrbitMarker(CameraModel.Frame f) {
        // The relief's radius is a fraction of the MODEL SCALE, which is the world
        // size of the plane the marker sits on.
        drawOrbitMarker(f.viewProjection, f.pivotX, f.pivotY, f.pivotZ, 0.035f * f.scale);
    }

    /**
     * The orbit crosshair for the splat cloud, from the frame the cloud was drawn
     * with.
     *
     * <p>The pivot is in the cloud's own world units and the eye keeps its distance
     * to it, so the ring's radius is a constant <b>angle</b>: a fraction of the
     * eye-to-pivot distance. That is the same "constant size on the glass" the
     * relief's marker gets from {@code 0.035 * f.scale}, one medium over, and it is
     * why the radius cannot be a fixed world number — a cloud twice as far away
     * would draw a ring half the size.
     */
    private void drawOrbitMarker(SplatCamera.Frame f) {
        float dx = f.eyeX - f.pivotX;
        float dy = f.eyeY - f.pivotY;
        float dz = f.eyeZ - f.pivotZ;
        float radius = 0.035f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        drawOrbitMarker(f.viewProjection, f.pivotX, f.pivotY, f.pivotZ, radius);
    }

    /**
     * The crosshair itself, from the numbers both cameras can supply.
     *
     * <p>One body for both modes, so the relief and the cloud cannot draw two
     * different crosshairs: the caller passes the frame it drew with — its
     * {@code projPanned * view}, its pivot and the radius that reads as a constant
     * size in its own units.
     */
    private void drawOrbitMarker(float[] viewProjection, float px, float py, float pz,
                                 float r) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(markerProgram);

        // The pivot's world point, from the frame: the same point the camera
        // aims at and the splat's reference eye sits above, so the crosshair,
        // the aim and the cards cannot name different pivots. It is the surface
        // under the origin, not the plane point behind it -- which is what makes
        // the crosshair the point the picture will hold under a tilt.
        Matrix.setIdentityM(markerMat, 0);
        Matrix.translateM(markerMat, 0, px, py, pz);
        Matrix.scaleM(markerMat, 0, r, r, 1f);
        Matrix.multiplyMM(markerMvp, 0, viewProjection, 0, markerMat, 0);
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
        // onSurfaceCreated. The mesh pointers are stale after this draw, so the
        // next drawRender re-binds whichever render it is about to draw.
        if (markerClobbersMesh) boundMesh = null;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Build one complete render from a decoded pair and put it on screen.
     *
     * <p><b>Upload first, delete second.</b> The old pair is not touched until
     * the new texture exists and its mesh is built. The previous version deleted
     * the outgoing texture first and uploaded second, so a failed upload
     * (texture name 0, which is what a call with no current context returns)
     * destroyed the pair that was working and left the surface black. Here a
     * texture name of 0 is still never a success: nothing is recorded and the
     * previous pair stays drawable.
     *
     * <p><b>Fade later loads only.</b> The first pair into a context is shown
     * opaque, because the system snapshots a wallpaper surface about 100 ms
     * after it appears and a half-faded first frame is the only frame the
     * launcher would ever keep. A pair that replaces a pair already on screen
     * cross-fades: see {@link #incoming}.
     *
     * @return false if the pair could not be made drawable
     */
    private boolean install(Pending p) {
        if (p == null || p.colorBmp == null) return false;
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
        Bitmap upright = flipVertically(p.colorBmp);

        Render r = new Render();
        r.aspect = (float) upright.getWidth() / upright.getHeight();
        // The photograph's camera, from the request. imageH is the height of the
        // bitmap actually uploaded: the focal is stored in pixels of this image, so
        // the pair (focal, imageH) is what the vertex shader turns into a field of
        // view, and taking the height from anywhere else would be a second
        // derivation of the size of the picture being drawn.
        r.focal = p.focal;
        r.imageH = upright.getHeight();
        r.textureColor = upload(upright);
        // A texture name of 0 is never a success, it is the default texture,
        // which has no image and samples pure black. Cheap to assert here and
        // impossible to infer from outside. The upload happened with no usable
        // current context, so there is no texture and nothing to draw; report it
        // as a failed install so the pair is not recorded as loaded and the next
        // visibility change retries. The outgoing texture was NOT deleted before
        // this check, so the old pair is still there to be drawn.
        if (r.textureColor == 0) {
            r.textureColor = -1;
            return false;
        }
        // The supplied normal, if any. Optional by design: a failed normal upload
        // degrades to flat lit rather than failing a pair that has depth, and 0 is
        // never treated as a normal -- it is the default texture, no image, black.
        if (p.normalBmp != null) {
            Bitmap normalUpright = flipVertically(p.normalBmp);
            r.textureNormal = upload(normalUpright);
            if (r.textureNormal == 0) r.textureNormal = -1;
        }
        // THE GLES3 PRIZE, measured here per install: the CPU no longer builds
        // the mesh. It builds the small blurred field (the part that must stay in
        // float to keep banding away) and uploads it; the GPU displaces vertices
        // from it. The GLES2 branch still pays for ReliefMesh.build and the
        // 12.6 MB buffer copies, which is the comparison this log line is for.
        long cpuStart = System.nanoTime();
        if (GLES3) {
            float[] field = ReliefMesh.blurredField(p.gray, p.w, p.h, GRID);
            long fieldDone = System.nanoTime();
            r.textureDepth = uploadField(field, GRID + 1);
            long uploaded = System.nanoTime();
            android.util.Log.i("Basrelief", "install gles3 field=" + (fieldDone - cpuStart) / 1e6f
                    + "ms upload=" + (uploaded - fieldDone) / 1e6f + "ms");
            if (r.textureDepth == 0) {
                // A field that did not upload is not installed either. Release the
                // colour and normal textures too, so the pair that failed leaves
                // nothing behind and the previous pair stays on screen.
                releaseTextures(r.textureColor, r.textureNormal);
                r.textureColor = -1;
                r.textureNormal = -1;
                return false;
            }
            // The texture has copied the field, so the retained copy is
            // converted IN PLACE to the model-space height the pivot is resolved
            // in (h = v/255 - 0.5) -- the same unit ReliefMesh.build's own field
            // is in, so the sampler does not have to know which path built it.
            // In place deliberately: the retained copy costs no second 0.6 MB
            // buffer, and nothing else reads the 0..255 form after the upload.
            for (int i = 0; i < field.length; i++) field[i] = field[i] / 255f - 0.5f;
            r.field = field;
        } else {
            ReliefMesh surf = ReliefMesh.build(p.gray, p.w, p.h, GRID, r.aspect);
            r.vertexCount = surf.vertexCount;
            r.posBuffer = toBuffer(surf.positions);
            r.uvBuffer = toBuffer(surf.uvs);
            // The mesh's own field is already the model-space height the pivot
            // is sampled in; keeping the reference is what the GLES2 path pays
            // for the pivot, where GLES3 pays for the retained blurred field.
            r.field = surf.field;
            // surf.normals is deliberately not uploaded: shading is the supplied
            // normal map or the flat plane, and the CPU mesh's derived normals are
            // no longer read by anything. ReliefMesh still builds them because its
            // golden test pins the geometry contract; the renderer ignores them.
            android.util.Log.i("Basrelief", "install gles2 mesh+buffers="
                    + (System.nanoTime() - cpuStart) / 1e6f + "ms");
        }
        // The framing this pair belongs to. While a pair change was pending the
        // live fields stopped being drawn, so this is the value both hosts
        // applied for the incoming item -- mesh, texture and framing meet in
        // this one GL event.
        captureFraming(r);

        // Any install ends a fade in flight: the newest pair wins now, and the
        // outgoing pair of the old fade is released before a new outgoing is
        // named. This is the "never more than one outgoing pair" bound.
        if (incoming != null) finishFadeNow();

        boolean samePair = hasInstalled
                && samePath(p.requestedImage, loadedImagePath)
                && samePath(p.requestedDepth, loadedDepthPath)
                && samePath(p.suppliedNormal() ? p.requestedNormal : null, loadedNormalPath);
        if (displayed == null || samePair) {
            // The first pair into this context has nothing to blend from, and a
            // re-upload of the pair already here has nothing to blend to. Both
            // swap in place, opaque. This is also the path that keeps the first
            // load of an engine out of the system's snapshot window.
            releaseRender(displayed);
            displayed = r;
            aspect = r.aspect;
        } else {
            // A pair already on screen: keep it resident and blend to the new
            // one over CROSS_FADE_MS. displayed is now the outgoing pair and is
            // released either when the fade lands or when a newer pair arrives.
            incoming = r;
            fadeStartNanos = System.nanoTime();
        }
        // The markers and any previous fade may have left attribute pointers
        // referencing another render's buffers; make the next draw bind the
        // right ones. See boundMesh.
        boundMesh = null;
        recomputeScale();
        return true;
    }

    /**
     * Snapshot the live framing onto a render.
     *
     * <p>Called when a render is installed -- naming the framing it belongs to
     * -- and once per frame for the displayed render while it is alone on
     * screen. That per-frame refresh is what keeps sliders, previews and the
     * surface glide immediate in the steady state; it is the frozen paths
     * ({@link #pairChangePending} and {@link #incoming}) that opt out of it.
     */
    private void captureFraming(Render r) {
        r.framing = snapshot();
        // The vector, not the angles: the draw path must not run trigonometry,
        // and setLight has already resolved exactly these values.
        r.lightX = lightDirX;
        r.lightY = lightDirY;
        r.lightZ = lightDirZ;
    }

    /**
     * Make the incoming pair the displayed one and release the outgoing pair.
     *
     * <p>Called when the fade's clock reaches its end and also from
     * {@link #install} when another pair arrives mid-fade: the outgoing pair is
     * dropped rather than kept alongside, which bounds the renderer to the pair
     * on screen, the one fading in, and one decode in flight. Without the drop a
     * fast playlist would grow a texture per swipe.
     */
    private void finishFadeNow() {
        if (incoming == null) return;
        releaseRender(displayed);
        displayed = incoming;
        incoming = null;
        aspect = displayed.aspect;
        boundMesh = null;
    }

    /** Delete a render's GL textures and drop its buffers. GL thread only. */
    private static void releaseRender(Render r) {
        if (r == null) return;
        // All present textures in one call: they were created together and leaving
        // the depth field or the normal behind would leak one texture per pair
        // change. The normal is optional, so it is filtered out rather than assumed.
        releaseTextures(r.textureColor, r.textureDepth, r.textureNormal);
        r.textureColor = -1;
        r.textureDepth = -1;
        r.textureNormal = -1;
        r.vertexCount = 0;
        r.posBuffer = null;
        r.uvBuffer = null;
    }

    /**
     * Delete the given GL texture names, skipping the ones that were never created.
     *
     * <p>Only positive names are real: 0 is the default texture and -1 is "none", and
     * passing either to {@code glDeleteTextures} is at best a no-op and at worst a
     * leak of the wrong object. The normal in particular is optional, so its absence
     * must not cost a deletion call for a name that never existed.
     */
    private static void releaseTextures(int... names) {
        int count = 0;
        int[] real = new int[names.length];
        for (int name : names) {
            if (name > 0) real[count++] = name;
        }
        if (count == 0) return;
        int[] toDelete = count == names.length ? real : java.util.Arrays.copyOf(real, count);
        GLES20.glDeleteTextures(count, toDelete, 0);
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
        // Published for the framing rows' Origin sensitivity, from the camera's
        // own values so the UI thread cannot derive a second, drifting answer.
        // Both axes in one place, so the pair cannot be half-updated; see the
        // fields' note. Cheap enough for the per-frame call: two multiplies.
        //
        // In SPLAT mode the Origin row's fraction IS a viewport fraction -- the
        // camera shifts the projection so NDC (2*originX, 2*originY) lands at the
        // centre -- so one whole Origin unit is the whole surface, and the row
        // tracks the finger 1:1 the same way it does in relief mode. The relief's
        // camera answer would be the wrong number here; the mode is the switch.
        if (renderMode == Framing.RENDER_SPLAT) {
            orbitPixelsPerUnitX = surfaceW;
            orbitPixelsPerUnitY = surfaceH;
        } else {
            orbitPixelsPerUnitX = camera.screenPixelsPerOrbit(true, surfaceH);
            orbitPixelsPerUnitY = camera.screenPixelsPerOrbit(false, surfaceH);
        }
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

    private static IntBuffer toIntBuffer(int[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        IntBuffer ib = bb.asIntBuffer();
        ib.put(data);
        ib.position(0);
        return ib;
    }

    /**
     * Upload the blurred depth field as a GLES3 {@code R32F} texture.
     *
     * <p>{@code R32F} keeps the float precision the blur was computed in; a
     * {@code UNSIGNED_BYTE} or re-quantised upload would recreate the terraces
     * and the banding would return (see {@code ReliefMesh.DEPTH_BLUR_FRACTION}).
     * {@code NEAREST} is both required (R32F is not filterable in unextended
     * GLES3) and correct: every vertex samples the exact texel it displaces.
     *
     * @return the texture name, or 0 if the upload happened with no usable
     *         context -- and 0 is never a success.
     */
    private static int uploadField(float[] field, int n) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        if (tex[0] == 0) return 0;
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE);
        // toBuffer leaves position at the end; glTexImage2D reads from the
        // current position, so rewind it or the upload is empty.
        FloatBuffer fb = toBuffer(field);
        fb.position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, n, n, 0,
                GLES30.GL_RED, GLES30.GL_FLOAT, fb);
        return tex[0];
    }

    /**
     * Build the static GLES3 relief grid: interleaved {@code (x, y, u, v)} for a
     * {@code (GRID+1)^2} vertex grid and the triangle list that connects it.
     *
     * <p>Positions are aspect-free ({@code x = u - 0.5}, {@code y = v - 0.5}) so
     * ONE grid serves every photo; the vertex shader scales x by the image aspect
     * it is drawing. Orientation matches {@link ReliefMesh}: top row is
     * {@code v = 1}, and each cell emits {@code tl,bl,tr} then {@code tr,bl,br}.
     *
     * <p>Uploaded as VBOs, not client arrays: the index list alone is 1.5 MB and
     * a client-side copy would be re-sent every frame for geometry that never
     * changes. The whole point of the GLES3 path is to stop paying per-frame
     * costs for the mesh.
     */
    private void buildGrid() {
        int n = GRID + 1;
        float[] verts = new float[n * n * 4];
        for (int gy = 0; gy < n; gy++) {
            for (int gx = 0; gx < n; gx++) {
                int i = (gy * n + gx) * 4;
                float u = (float) gx / GRID;
                float v = 1f - (float) gy / GRID;
                verts[i] = u - 0.5f;
                verts[i + 1] = v - 0.5f;
                verts[i + 2] = u;
                verts[i + 3] = v;
            }
        }
        int[] idx = new int[GRID * GRID * 6];
        int k = 0;
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                int tl = gy * n + gx, tr = tl + 1, bl = (gy + 1) * n + gx, br = bl + 1;
                idx[k++] = tl; idx[k++] = bl; idx[k++] = tr;
                idx[k++] = tr; idx[k++] = bl; idx[k++] = br;
            }
        }
        int[] vbo = new int[1];
        GLES30.glGenBuffers(1, vbo, 0);
        gridVbo = vbo[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo);
        // toBuffer leaves position at the end (its callers normally rewind);
        // glBufferData reads from the current position, so rewind it first.
        FloatBuffer vb = toBuffer(verts);
        vb.position(0);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.length * 4, vb,
                GLES30.GL_STATIC_DRAW);
        int[] ibo = new int[1];
        GLES30.glGenBuffers(1, ibo, 0);
        gridIbo = ibo[0];
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gridIbo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.length * 4, toIntBuffer(idx),
                GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        gridIndexCount = idx.length;
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
