package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceSettings;
import com.amoshydra.basrelief.core.store.VideoTracks;

import org.junit.Test;

/**
 * The one resolution of "what should be on screen".
 *
 * <p>This class lived in :app for one commit, and the bug it now exists to
 * prevent happened in that window: the studio loaded a newly selected photo's
 * <i>files</i> without loading its <i>framing</i>, so the photo arrived through
 * the previous photo's pan, zoom and depth. It was invisible to the unit tests
 * because :app cannot be unit tested, and invisible on the device for as long as
 * nobody switched between two photos with different framing. It is in :core now so
 * that the next version of this mistake is a red test rather than a bug report.
 *
 * <p>The themes are the ones this class exists to hold:
 *
 * <ol>
 *   <li><b>A stored value for the demo is used, not discarded.</b> It once was
 *       discarded, so a slider could display a number the renderer never drew
 *       with — and the first fix disabled the controls rather than fixing the
 *       bug, which hid the symptom and left the class still unable to honour a
 *       stored value. The demo is an ordinary row with a file behind it now, so
 *       there is nothing left for this class to treat differently.
 *   <li><b>A null item is the demo</b>, because that is what "nothing is
 *       selected" means and it is the state a delete of the active item lands in.
 *   <li><b>Motion is read from the photo, not from the caller.</b> It used to be
 *       a parameter, from a global. Now that it is per-photo, a caller cannot hand
 *       this class a Motion the photo disagrees with — which matters beyond
 *       tidiness, because the active photo's Motion is what decides whether the
 *       tilt sensor runs at all.
 *   <li><b>A surface without an override resolves to the same place its parent
 *       does.</b> The lock screen mirroring home is the default for every photo,
 *       and it has to be resolved here rather than by each caller choosing a
 *       fallback.
 * </ol>
 */
public class FramingTest {

    private static LibraryItem photo(String id, int depth, int zoom,
                                     float ox, float oy, float px, float py) {
        LibraryItem base = LibraryItem.of(id, id + ".png", null, 1L)
                .withIntensity(Surface.HOME, depth)
                .withZoom(Surface.HOME, zoom)
                .withOrigin(Surface.HOME, ox, oy)
                .withPan(Surface.HOME, px, py);
        return base;
    }

    private static Framing home(LibraryItem item) {
        return Framing.of(item, Surface.HOME);
    }

    /**
     * The demo, carrying the files the seed copied out of {@code res/raw}.
     *
     * <p>Framing never asked what those files were — it takes an item and reads
     * its settings — so this is here only so the tests below are about the item's
     * values rather than about the fact that the demo once had none.
     */
    private static LibraryItem demo() {
        return LibraryItem.bundled(
                com.amoshydra.basrelief.core.store.LibraryTest.DEMO_IMAGE,
                com.amoshydra.basrelief.core.store.LibraryTest.DEMO_DEPTH);
    }

    // -------------------------------------------------------------- the bug

    /**
     * The regression, stated as a test: two photos with different framing, and
     * resolving the second must not inherit any of the first.
     */
    /**
     * {@code sameAs}, and the reflection guard that makes it safe to add a field.
     *
     * <p>The wallpaper uses this to decide whether a repeat resolve of the same
     * transition has anything to say. A resolve that returns a DIFFERENT picture must
     * never compare equal, or the renderer drops a real frame; a resolve that returns the
     * SAME picture must compare equal, or it cancels the glide running toward it — the
     * measured snap the user reported.
     */
    @Test
    public void sameAsComparesEveryValueAndCountsThemAll() throws Exception {
        // The base vector: eighteen values, and the four splat ones are deliberately
        // NOT their defaults so the varied vectors below differ from them.
        Framing a = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f,
                Framing.SOURCE_STILL, Framing.RENDER_RELIEF, 1f,
                1.2f, 0.6f, 1.5f, 0.7f);
        Framing b = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f,
                Framing.SOURCE_STILL, Framing.RENDER_RELIEF, 1f,
                1.2f, 0.6f, 1.5f, 0.7f);
        assertTrue("equal values must compare equal", a.sameAs(b));
        assertTrue(a.sameAs(a));
        assertFalse("a different item must not", a.sameAs(null));

        // EVERY field, by rebuilding the framing with one value moved -- not by
        // mutating in place, because the fields are final. The loop is driven by
        // reflection over the field COUNT so a field added later is covered without
        // editing this test, and the count is asserted because a new field would
        // otherwise be silently uncompared and a real frame would be dropped.
        //
        // STATIC FIELDS ARE EXCLUDED, and that filter is not tidiness. SOURCE_STILL
        // and SOURCE_VIDEO are public static final floats, so a reflection that
        // counted every float found more than the instance fields, asserted nothing
        // useful, and would have let the count be "updated" to match whatever the code
        // happened to declare -- which is a guard that can no longer fail. An instance
        // field is the only thing sameAs has to compare.
        java.lang.reflect.Field[] fields = Framing.class.getFields();
        java.util.List<String> numeric = new java.util.ArrayList<String>();
        for (java.lang.reflect.Field f : fields) {
            if (f.getType() == float.class
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                numeric.add(f.getName());
            }
        }
        assertEquals("sameAs must compare every float field, and there are this many",
                18, numeric.size());

        // One call per field: the same vector with exactly one component moved. The
        // last four are the splat look (Depth, Size, Blur, Resolution), added when
        // they moved off the global prefs into the counted chain.
        float[][] varied = {
                {91f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // depth
                {90f, 1.5f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // zoom
                {90f, 1.4f, 34f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // motion
                {90f, 1.4f, 33f, 0.2f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // orbitX
                {90f, 1.4f, 33f, 0.1f, 0.3f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // orbitY
                {90f, 1.4f, 33f, 0.1f, 0.2f, 15f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // panX
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -2f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // panY
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 1f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // brightness
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 1f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // shadow
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 126f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // azimuth
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 52f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // elevation
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 1f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // sourceKind
                // The draw path. Compared where `bundled` is not, because a relief
                // and a cloud at identical numbers are two pictures.
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 1f, 1f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // renderMode
                // A CLIP's rate. It is compared where `bundled` is not, because it
                // changes what is drawn -- a frame at 0.5x is a different picture.
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 0.5f,
                        1.2f, 0.6f, 1.5f, 0.7f},   // speed
                // The splat look: a cloud at a different Depth, Size, Blur or
                // Resolution is a different picture, so sameAs must see each.
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.3f, 0.6f, 1.5f, 0.7f},   // splatDepth
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.7f, 1.5f, 0.7f},   // splatEps2d
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 2.5f, 0.7f},   // splatBlur
                {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f, 0f, 0f, 125f, 51f, 0f, 0f, 1f,
                        1.2f, 0.6f, 1.5f, 0.5f},   // splatScale
        };
        assertEquals("one varied vector per float field", numeric.size(), varied.length);
        for (float[] v : varied) {
            Framing moved = Framing.ofValues(v[0], v[1], v[2], v[3], v[4], v[5], v[6],
                    v[7], v[8], v[9], v[10], v[11], v[12], v[13],
                    v[14], v[15], v[16], v[17]);
            assertFalse("a moving value must make sameAs false", a.sameAs(moved));
        }
    }

    /**
     * A whisker is not a difference; a real step is.
     *
     * <p>The measured failure: a glide settled at {@code lightAzimuth 209.00562, panY
     * 0.00486} against a target of exactly {@code 209.0, 0.0}. An {@code ==} comparison
     * called that a different picture, so a later resolve painted it INSTANTLY -- the snap
     * the user reported, mid-transition. The same log shows the genuine differences that
     * MUST still count: {@code azimuth 124.99 -> 239.0}, {@code depth 0.9 -> 0.29}.
     */
    @Test
    public void aRoundingWhiskerIsNotADifferenceButARealStepIs() {
        // panY 0, from the log: the residue was against a target of exactly 0.
        Framing target = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, 0f,
                0f, 0f, 209f, 46f, Framing.SOURCE_STILL, 1f);
        // What a glide actually lands on.
        Framing landed = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, 0.00486f,
                0f, 0f, 209.00562f, 46.000908f, Framing.SOURCE_STILL, 1f);
        assertTrue("a whisker from the glide must read as the same picture",
                target.sameAs(landed));

        // The real transition from the same log: lock framing to home framing.
        assertFalse("azimuth 209 -> 239 and the rest is a real change",
                target.sameAs(Framing.ofValues(29f, 2.0f, 33f, 0.1f, 0.2f, 14f, 26f,
                        0f, 0f, 239f, 50.862324f, Framing.SOURCE_STILL, 1f)));

        // EVERY residue measured on the device must read as the same picture. These are
        // the real numbers from three logs, not invented ones: the first attempt at this
        // tolerance was set below two of them, which is how a settle at 0.00024 still
        // painted instantly.
        float[] residues = {0.007f, 0.00486f, 0.00024f};
        float[] base = {90f, 1.4f, 33f, 0.1f, 0.2f, 14f, 0f, 0f, 0f, 209f, 46f,
                Framing.SOURCE_STILL};
        for (float residue : residues) {
            float[] withResidue = base.clone();
            withResidue[9] = 209f + residue;   // azimuth
            withResidue[6] = residue;          // panY
            assertTrue("a measured residue of " + residue + " must read as the same picture",
                    target.sameAs(Framing.ofValues(withResidue[0], withResidue[1],
                            withResidue[2], withResidue[3], withResidue[4], withResidue[5],
                            withResidue[6], withResidue[7], withResidue[8], withResidue[9],
                            withResidue[10], withResidue[11], 1f)));
        }

        // And the OTHER side of the margin: the smallest real step in those logs must NOT
        // read as the same, or a genuine change would stop painting.
        assertFalse("depth 0.9 -> 0.29 is a real change",
                target.sameAs(Framing.ofValues(29f, 1.4f, 33f, 0.1f, 0.2f, 14f, 0f,
                        0f, 0f, 209f, 46f, Framing.SOURCE_STILL, 1f)));
    }

    /**
     * <b>The bundled demo is the one row the guard must not miss.</b>
     *
     * <p>{@code sameAs} compared {@code bundled} while {@code ofValues} -- which builds
     * every snapshot -- hard-codes it {@code false}. So a resolve of the marble bust could
     * NEVER no-op: the guard that stops an instant paint of a frame already showing was
     * structurally absent on the row every install starts with, and every resolve of it
     * painted instantly, mid-glide included.
     *
     * <p>{@code bundled} says WHICH ROW a framing came from, not what the picture is. Two
     * framings of the same numbers are the same picture whatever produced them.
     */
    @Test
    public void theBundledDemoIsNotADifferentPictureFromItsOwnNumbers() {
        LibraryItem demo = LibraryItem.bundled("item-bundled.png", "item-bundled-depth.png");
        Framing fromRow = Framing.of(demo, Surface.HOME);
        assertTrue("the row is the demo", fromRow.bundled);
        // A snapshot is built from numbers and can never carry the flag -- which is the
        // whole trap. Same values, so it must read as the same picture.
        Framing asNumbers = Framing.ofValues(fromRow.depth, fromRow.zoom, fromRow.motion,
                fromRow.orbitX, fromRow.orbitY, fromRow.panX, fromRow.panY,
                fromRow.brightnessGain, fromRow.shadowLift,
                fromRow.lightAzimuth, fromRow.lightElevation, fromRow.sourceKind, 1f);
        assertFalse("a snapshot never carries the flag", asNumbers.bundled);
        assertTrue("so the demo must still compare equal to its own numbers",
                fromRow.sameAs(asNumbers));
        // And a real difference in the demo's numbers is still a difference.
        assertFalse(fromRow.sameAs(Framing.ofValues(fromRow.depth - 1f, fromRow.zoom,
                fromRow.motion, fromRow.orbitX, fromRow.orbitY, fromRow.panX, fromRow.panY,
                fromRow.brightnessGain, fromRow.shadowLift,
                fromRow.lightAzimuth, fromRow.lightElevation, fromRow.sourceKind, 1f)));
    }

    /**
     * <b>A clip and a still are different pictures at identical numbers</b>, so
     * {@code sameAs} must say so.
     *
     * <p>This is the one new field, and it is the exact opposite of
     * {@link #bundled}. {@code bundled} says which ROW produced a framing and is
     * therefore uncompared; the medium says what the PIXELS are, so a still and a
     * clip at the same depth, zoom and light are two pictures and a resolve that
     * conflated them would declare a frame it was about to show as already on
     * screen — which drops it, silently, on the user's home screen.
     *
     * <p>Stated over identical numbers on purpose. It is easy to write a test that
     * passes because the zoom also changed; this one cannot.
     */
    @Test
    public void aClipAndAStillOfIdenticalNumbersAreDifferentPictures() {
        LibraryItem still = photo("a", 90, 100, 0f, 0f, 0f, 0f);
        LibraryItem clip = still.withVideo(VideoTracks.of("item-a.mp4", "item-a-depth.mp4"));

        Framing fromStill = home(still);
        Framing fromClip = home(clip);

        // Every other number is identical, which is the whole point.
        assertEquals(fromStill.depth, fromClip.depth, 0f);
        assertEquals(fromStill.zoom, fromClip.zoom, 0f);
        assertEquals(fromStill.lightAzimuth, fromClip.lightAzimuth, 0f);
        assertEquals(fromStill.bundled, fromClip.bundled);

        assertFalse("THE failure: a clip must never read as the still already showing",
                fromStill.sameAs(fromClip));
        assertFalse(fromClip.sameAs(fromStill));

        assertFalse("and it is a number, not a flag: the renderer reads one",
                fromStill.isVideo());
        assertTrue(fromClip.isVideo());
        assertEquals(Framing.SOURCE_STILL, fromStill.sourceKind, 0f);
        assertEquals(Framing.SOURCE_VIDEO, fromClip.sourceKind, 0f);
    }

    /**
     * An INTERPOLATED medium matches neither picture — which is why the glide must
     * never lerp it.
     *
     * <p>This is the trap in the one field nobody would think to guard. A glide eases
     * eleven values; it is obvious that easing the medium is wrong because a
     * half-way point is not a picture. It is less obvious that the damage is to the
     * no-op-paint guard rather than to the picture: {@code sourceKind} is compared
     * by {@code sameAs}, so a glide that eased it would make **every frame differ
     * from the one before it**. The guard would then be defeated for the whole
     * duration, and a resolve arriving mid-glide would paint — the instant-paint
     * snap, reached without anybody changing the picture at all.
     *
     * <p>So the property pinned here is that an intermediate value is a third thing,
     * equal to neither endpoint. A test that only asserted "still differs from clip"
     * would pass happily while the renderer interpolated between them.
     */
    @Test
    public void anInterpolatedMediumIsNeitherPicture() {
        Framing still = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f,
                0f, 0f, 125f, 51f, Framing.SOURCE_STILL, 1f);
        Framing clip = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f,
                0f, 0f, 125f, 51f, Framing.SOURCE_VIDEO, 1f);
        // Exactly what lerp(SOURCE_STILL, SOURCE_VIDEO, t) would produce at t = 0.5.
        Framing half = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f,
                0f, 0f, 125f, 51f,
                Framing.SOURCE_STILL + (Framing.SOURCE_VIDEO - Framing.SOURCE_STILL) * 0.5f, 1f);

        assertFalse("a half-eased medium is not the frame already showing",
                half.sameAs(still));
        assertFalse("nor the one being glided toward", half.sameAs(clip));
        // The sharper half, and the reason this test exists. isVideo() was
        // `!= SOURCE_STILL`, so a half-eased medium answered TRUE: an interpolated
        // value did not merely fail to name a picture, it claimed to be a clip.
        // Exact match is the fix, and it is also what survives a third medium.
        assertFalse("and it does not claim to be a clip either",
                half.isVideo());
        assertTrue("the real clip still does", clip.isVideo());
        assertFalse("and the real still still does not", still.isVideo());
        // The consequence, stated as the property it exists to protect: the frame
        // before and the frame during a glide differ, which is exactly why the
        // renderer must hold the medium whole rather than easing it.
        assertFalse(still.sameAs(half));
    }

    /**
     * A relief and a cloud at identical numbers are two different pictures.
     *
     * <p>The {@code sourceKind} lesson one axis over: the draw path is discrete and
     * compared by {@code sameAs}, so toggling Splat must make the renderer paint
     * rather than answer "already showing". And the mode is read from the item, so
     * the two surfaces and the renderer's own snapshot cannot disagree about it.
     */
    @Test
    public void aReliefAndASplatOfIdenticalNumbersAreDifferentPictures() {
        Framing relief = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f,
                0f, 0f, 125f, 51f, Framing.SOURCE_STILL, Framing.RENDER_RELIEF, 1f);
        Framing cloud = Framing.ofValues(90f, 1.4f, 33f, 0.1f, 0.2f, 14f, -3f,
                0f, 0f, 125f, 51f, Framing.SOURCE_STILL, Framing.RENDER_SPLAT, 1f);
        assertFalse("a cloud is not the relief at the same numbers",
                relief.sameAs(cloud));
        assertTrue("and it is itself", cloud.sameAs(cloud));

        LibraryItem photo = demo();
        assertEquals(Framing.RENDER_RELIEF, Framing.of(photo, Surface.HOME).renderMode, 0f);
        assertEquals(Framing.RENDER_SPLAT,
                Framing.of(photo.withRenderMode(
                        com.amoshydra.basrelief.core.store.RenderMode.SPLAT), Surface.HOME)
                        .renderMode, 0f);
    }

    /**
     * Nothing selected, and the bundled demo, are both stills — the medium follows
     * the row and never a fallback.
     *
     * <p>There is no clip behind the packaged demo pair, so there is nothing for the
     * medium to be anything but {@link Framing#SOURCE_STILL}. Asserted because the
     * null branch of the constructor is the one that returns early, and a field
     * assigned after that return would be a compile error rather than a silent wrong
     * answer — but a field assigned to {@code SOURCE_VIDEO} there would not be.
     */
    @Test
    public void noSelectionAndTheDemoAreBothStills() {
        assertEquals(Framing.SOURCE_STILL, Framing.of(null, Surface.HOME).sourceKind, 0f);
        assertEquals(Framing.SOURCE_STILL, home(demo()).sourceKind, 0f);
        assertFalse(Framing.of(null, Surface.HOME).isVideo());
    }

    /**
     * A row running a COLOUR-ONLY PREVIEW is video, because its pixels are.
     *
     * <p>The row is deliberately a photograph for the whole server round — a preview
     * is never stored on it — so the row alone cannot answer "what medium is on
     * screen". The renderer, which does know, reports VIDEO while a preview plays;
     * a host framing that said STILL would differ from the frame on screen on every
     * resolve, so the no-op-paint guard could never fire, and a surface glide landing
     * over a preview would ease from a video snapshot to a "still" destination,
     * stamping the wrong medium onto a picture that is still moving.
     *
     * <p>The flag only ADDS video: a row with its own tracks is video with or without
     * it, and a plain photograph is still either way.
     */
    @Test
    public void aPreviewRowNamesTheMediumOfItsPixels() {
        LibraryItem photograph = photo("a", 90, 100, 0f, 0f, 0f, 0f);
        assertEquals(Framing.SOURCE_STILL, home(photograph).sourceKind, 0f);
        assertFalse(home(photograph).isVideo());
        assertEquals("a preview's pixels are video",
                Framing.SOURCE_VIDEO,
                Framing.of(photograph, Surface.HOME, true).sourceKind, 0f);
        assertTrue(Framing.of(photograph, Surface.HOME, true).isVideo());

        LibraryItem grown = photograph.withVideo(
                VideoTracks.of("item-a.mp4", "item-a-depth.mp4"));
        assertEquals("a grown clip is video whether or not the flag is set",
                Framing.SOURCE_VIDEO, Framing.of(grown, Surface.HOME, false).sourceKind, 0f);
        assertEquals(Framing.SOURCE_VIDEO, Framing.of(grown, Surface.HOME, true).sourceKind, 0f);
    }

    /**
     * A snapshot has to be able to say it is a clip, or the no-op-paint guard misses
     * every frame of one.
     *
     * <p>The counterpart of the {@link #bundled} lesson. {@code bundled} is hard-coded
     * false by {@code ofValues} and therefore cannot be compared; the medium is a
     * float precisely so a snapshot CAN carry it. This asserts the eighteen-argument
     * form round-trips, and pins the loss in the eleven-argument one rather than
     * leaving it to be discovered on a device — where the symptom is an instant
     * repaint the user's rule calls a snap.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void aSnapshotCanCarryTheMediumAndTheLossyFormCannot() {
        Framing clip = home(photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .withVideo(VideoTracks.of("item-a.mp4", "item-a-depth.mp4")));
        Framing snapshot = Framing.ofValues(clip.depth, clip.zoom, clip.motion,
                clip.orbitX, clip.orbitY, clip.panX, clip.panY,
                clip.brightnessGain, clip.shadowLift,
                clip.lightAzimuth, clip.lightElevation, clip.sourceKind,
                clip.renderMode, clip.speed,
                clip.splatDepth, clip.splatEps2d, clip.splatBlur, clip.splatScale);
        assertTrue("what is showing must compare equal to itself",
                clip.sameAs(snapshot));
        assertTrue("including the medium", snapshot.isVideo());

        // The old eleven-argument form cannot say. It always says still, so it
        // compares UNEQUAL to a clip -- which fails towards an extra repaint rather
        // than a dropped frame, and is why ReliefRenderer.snapshot() has to move to
        // the twelve-argument form before any clip can be imported.
        Framing lossy = Framing.ofValues(clip.depth, clip.zoom, clip.motion,
                clip.orbitX, clip.orbitY, clip.panX, clip.panY,
                clip.brightnessGain, clip.shadowLift,
                clip.lightAzimuth, clip.lightElevation);
        assertFalse(lossy.isVideo());
        assertFalse("known loss, pinned so it cannot be forgotten",
                clip.sameAs(lossy));
    }

    @Test
    public void eachPhotoResolvesItsOwnFraming() {
        Framing first = home(photo("a", 90, 100, 0f, 0f, 0f, 0f));
        Framing second = home(photo("b", 20, 175, 0.3f, -0.4f, -135f, 260f));

        assertEquals(0.9f, first.depth, 1e-6f);
        assertEquals(1.0f, first.zoom, 1e-6f);

        assertEquals(0.2f, second.depth, 1e-6f);
        assertEquals(1.75f, second.zoom, 1e-6f);
        assertEquals(0.3f, second.orbitX, 1e-6f);
        assertEquals(-0.4f, second.orbitY, 1e-6f);
        assertEquals(-135f, second.panX, 1e-6f);
        assertEquals(260f, second.panY, 1e-6f);

        // The actual failure was the *second* render keeping the first's numbers.
        assertFalse("the second photo inherited the first's depth",
                first.depth == second.depth);
        assertFalse("the second photo inherited the first's zoom",
                first.zoom == second.zoom);
    }

    // ---------------------------------------------------------------- motion

    /**
     * Motion is the photo's, not the caller's.
     *
     * <p>This inverts a previous test. Motion used to be global and was passed in,
     * and there was a deliberate test pinning that it came from the caller — which
     * was correct then and is the bug now. A caller that can name a Motion the
     * photo disagrees with is a caller that can draw a photo at a responsiveness
     * the user never chose for it, and whose stored value then disagrees with what
     * is on the home screen.
     */
    @Test
    public void motionComesFromThePhotoAndNotFromTheCaller() {
        LibraryItem item = LibraryItem.of("a", "a.png", null, 1L)
                .withMotion(Surface.HOME, 50);

        // 50 on the 0..100 slider is the middle of the 0..3 tilt scale.
        assertEquals(1.5f, home(item).motion, 1e-6f);
        assertEquals(0.3f, home(item.withMotion(Surface.HOME, 10)).motion, 1e-6f);
    }

    /**
     * The bundled demo's Motion is honoured, unlike its Depth and Zoom.
     *
     * <p>The one asymmetry in this class, and it is deliberate. Motion answers a
     * question about the photo being looked at, so its slider stays live on the
     * demo while Depth and Zoom are switched off. Ignoring it here would make that
     * slider display a number the renderer never uses — the exact lie the
     * bundled-demo rule exists to prevent, reintroduced through the one value the
     * rule was relaxed for.
     */
    @Test
    public void aStoredMotionForTheBundledDemoIsUsed() {
        LibraryItem tampered = demo().withMotion(Surface.HOME, 100);
        assertTrue(home(tampered).bundled);
        assertEquals(3f, home(tampered).motion, 1e-6f);
        assertEquals(0f, home(demo().withMotion(Surface.HOME, 0)).motion, 1e-6f);
    }

    /** A demo with no stored Motion still gets the default, not a null deref. */
    @Test
    public void theBundledDemoWithoutAStoredMotionGetsTheDefault() {
        assertEquals(SurfaceSettings.DEFAULT_MOTION / 100f * 3f,
                home(demo()).motion, 1e-6f);
        assertEquals("and the same for no item at all",
                SurfaceSettings.DEFAULT_MOTION / 100f * 3f,
                Framing.of(null, Surface.HOME).motion, 1e-6f);
    }

    // ---------------------------------------------------------- the picture

    /**
     * A photo's Brightness and Shadow reach the renderer as mapped scalars.
     *
     * <p>Pinned as the mapped values rather than the stored -100..100 because the
     * shader only ever sees these two numbers, so these are what "the photo is at
     * +40" actually means at the point of use.
     */
    @Test
    public void thePictureAdjustmentsArriveAlreadyMapped() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .withAdjust(Surface.HOME, 100, -100);
        Framing f = home(item);
        assertEquals("one stop up", 2f, f.brightnessGain, 1e-6f);
        assertEquals(-1f, f.shadowLift, 1e-6f);
    }

    /** Picture follows the same rule as everything else: stored means used. */
    @Test
    public void aStoredPictureForTheBundledDemoIsUsed() {
        LibraryItem stored = demo().withAdjust(Surface.HOME, 100, -100);
        Framing f = home(stored);
        assertTrue(f.bundled);
        assertEquals("one stop up", 2f, f.brightnessGain, 1e-6f);
        assertEquals(-1f, f.shadowLift, 1e-6f);
    }

    /** And an untouched demo is unadjusted, so 0 stays the identity. */
    @Test
    public void anUntouchedBundledDemoIsUnadjusted() {
        Framing f = home(demo());
        assertEquals(1f, f.brightnessGain, 0f);
        assertEquals(0f, f.shadowLift, 0f);
    }

    /** Picture is per surface, like everything else a photo owns. */
    @Test
    public void thePictureAdjustmentsArePerSurface() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .withAdjust(Surface.HOME, 100, 0)
                .editSettings(Surface.LOCK, s -> s.with(s.depth, s.zoom, s.motion,
                        s.originX, s.originY, s.panX, s.panY, 0, -100,
                        s.lightAzimuth, s.lightElevation, s.speed,
                        s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale));
        assertEquals(2f, home(item).brightnessGain, 1e-6f);
        assertEquals(0f, home(item).shadowLift, 1e-6f);
        assertEquals(1f, Framing.of(item, Surface.LOCK).brightnessGain, 1e-6f);
        assertEquals(-1f, Framing.of(item, Surface.LOCK).shadowLift, 1e-6f);
    }

    // --------------------------------------------------------------- surfaces

    /**
     * A photo with no lock override resolves the lock screen to its home values.
     *
     * <p>Not a fallback: inheriting is the default state of every photo, so if this
     * resolved differently, every photo would need an explicit lock entry before
     * its lock screen looked like its home screen.
     */
    @Test
    public void aPhotoWithNoLockOverrideResolvesLockToHome() {
        LibraryItem item = photo("a", 20, 175, 0.3f, -0.4f, -135f, 260f);
        Framing onLock = Framing.of(item, Surface.LOCK);

        assertFalse("a photo with no lock override must not claim one",
                item.hasOwnLockSettings());
        assertEquals(home(item).depth, onLock.depth, 0f);
        assertEquals(home(item).zoom, onLock.zoom, 0f);
        assertEquals(home(item).orbitX, onLock.orbitX, 0f);
        assertEquals(home(item).orbitY, onLock.orbitY, 0f);
        assertEquals(home(item).panX, onLock.panX, 0f);
        assertEquals(home(item).panY, onLock.panY, 0f);
        assertEquals(home(item).motion, onLock.motion, 0f);
    }

    /**
     * A lock override is used, and home is left alone.
     *
     * <p>The direction of the second half is the one that matters. How a landscape
     * reads on the home screen says nothing about how it reads behind a lock-screen
     * clock, so the lock screen exists to be tuned separately — and a write to one
     * surface that leaked onto the other would make the second surface
     * un-customisable in practice.
     */
    @Test
    public void aLockOverrideIsUsedAndLeavesHomeAlone() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .editSettings(Surface.LOCK, s -> s.with(30, 140, 80, 0.1f, -0.2f,
                        -40f, 15f, -20, 35, 200f, 15f, SurfaceSettings.DEFAULT_SPEED,
                        SurfaceSettings.DEFAULT_SPLAT_DEPTH, SurfaceSettings.DEFAULT_SPLAT_EPS2D,
                        SurfaceSettings.DEFAULT_SPLAT_BLUR, SurfaceSettings.DEFAULT_SPLAT_SCALE));

        assertTrue(item.hasOwnLockSettings());
        Framing onLock = Framing.of(item, Surface.LOCK);
        assertEquals(0.3f, onLock.depth, 1e-6f);
        assertEquals(1.4f, onLock.zoom, 1e-6f);
        assertEquals(2.4f, onLock.motion, 1e-6f);
        assertEquals(0.1f, onLock.orbitX, 1e-6f);
        assertEquals(-0.2f, onLock.orbitY, 1e-6f);
        assertEquals(-40f, onLock.panX, 1e-6f);
        assertEquals(15f, onLock.panY, 1e-6f);
        assertEquals(200f, onLock.lightAzimuth, 1e-6f);
        assertEquals(15f, onLock.lightElevation, 1e-6f);

        assertEquals("home must not have followed the lock override",
                0.9f, home(item).depth, 1e-6f);
        assertEquals(1.0f, home(item).zoom, 1e-6f);
        assertEquals(0f, home(item).orbitX, 0f);
        assertEquals(LightDirection.DEFAULT_AZIMUTH_DEG,
                home(item).lightAzimuth, 1e-6f);
    }

    /**
     * The light is per surface like every other render value.
     *
     * <p>The default really is the historical literal, expressed as angles; the
     * wrong reading of this field is a slider that shows the default while the
     * renderer draws the previous photo's light, which is why the resolved values
     * are asserted here and not only on the stored settings.
     */
    @Test
    public void theLightIsResolvedPerSurface() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .withLight(Surface.HOME, 40f, 20f)
                .withLight(Surface.LOCK, 300f, 80f);

        assertEquals(40f, home(item).lightAzimuth, 1e-6f);
        assertEquals(20f, home(item).lightElevation, 1e-6f);
        Framing onLock = Framing.of(item, Surface.LOCK);
        assertEquals(300f, onLock.lightAzimuth, 1e-6f);
        assertEquals(80f, onLock.lightElevation, 1e-6f);
    }

    /** Two surfaces, two Motions, on the one photo. */
    @Test
    public void motionIsPerSurface() {
        LibraryItem item = LibraryItem.of("a", "a.png", null, 1L)
                .withMotion(Surface.HOME, 100)
                .withMotion(Surface.LOCK, 0);

        assertEquals(3f, home(item).motion, 1e-6f);
        assertEquals(0f, Framing.of(item, Surface.LOCK).motion, 1e-6f);
    }

    /**
     * A lock override is only created by writing to the lock screen.
     *
     * <p>Edits to the home surface must not conjure one. If they did, a photo the
     * user never touched on the lock screen would stop following its home framing
     * the first time they nudged a home slider — which is exactly the "lock screen
     * silently stops tracking the photo" failure the private fields exist to
     * prevent, arriving by the other route.
     */
    @Test
    public void editingHomeDoesNotCreateALockOverride() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .withIntensity(Surface.HOME, 40)
                .withZoom(Surface.HOME, 150)
                .withPan(Surface.HOME, 20f, 20f)
                .withAdjust(Surface.HOME, -30, 10);

        assertFalse("a home edit created a lock override", item.hasOwnLockSettings());
        assertEquals(0.4f, home(item).depth, 1e-6f);
        assertEquals(0.4f, Framing.of(item, Surface.LOCK).depth, 1e-6f);
    }

    // -------------------------------------------------------- the bundled demo

    @Test
    public void aNullItemIsTheBundledDemo() {
        Framing f = Framing.of(null, Surface.HOME);
        assertTrue(f.bundled);
        assertEquals(SurfaceSettings.DEFAULT_DEPTH / 100f, f.depth, 1e-6f);
        assertEquals(SurfaceSettings.DEFAULT_ZOOM / 100f, f.zoom, 1e-6f);
        assertEquals(0f, f.orbitX, 0f);
        assertEquals(0f, f.orbitY, 0f);
        assertEquals(0f, f.panX, 0f);
        assertEquals(0f, f.panY, 0f);
    }

    /**
     * The bundled item, resolved, is the same thing as no item at all.
     *
     * <p>Two shapes of the same state that must not be able to differ.
     */
    @Test
    public void theBundledItemResolvesIdenticallyToNoItem() {
        Framing viaNull = Framing.of(null, Surface.HOME);
        Framing viaItem = home(demo());
        assertEquals(viaNull.bundled, viaItem.bundled);
        assertEquals(viaNull.depth, viaItem.depth, 0f);
        assertEquals(viaNull.zoom, viaItem.zoom, 0f);
        assertEquals(viaNull.motion, viaItem.motion, 0f);
        assertEquals(viaNull.orbitX, viaItem.orbitX, 0f);
        assertEquals(viaNull.panX, viaItem.panX, 0f);
    }

    /**
     * A stored value for the bundled demo is <b>used</b>.
     *
     * <p><b>This test asserts the opposite of what it used to, and that is the
     * point.</b> It was named
     * {@code aStoredValueForTheBundledDemoIsIgnoredRatherThanUsed}, and it existed
     * because of a real bug: the sliders wrote zoom 42 onto the bundled item —
     * the guard tested {@code activeId() == null} and the reserved id is not null —
     * and this class ignored it, so the slider displayed 0.42x while the renderer
     * drew at 1.0x. Storing a value nothing reads is the silent-drift shape this
     * project keeps getting bitten by.
     *
     * <p>The bug was fixed in the wrong place. The controls were switched off for
     * the demo, which hides the symptom and leaves this class still unable to
     * honour a stored value. Its image is a file in {@code filesDir} like every
     * other photo's, and Depth, Zoom, Motion, Origin, Pan and the adjustments are
     * ordinary render values the reserved item holds perfectly well.
     *
     * <p>So the demo is editable and the value is read. The rule that actually
     * matters is unchanged and is the first assertion below: a control must never
     * display a number the renderer does not use.
     */
    @Test
    public void aStoredValueForTheBundledDemoIsUsedRatherThanIgnored() {
        LibraryItem stored = demo()
                .withZoom(Surface.HOME, 42)
                .withIntensity(Surface.HOME, 13);
        Framing f = home(stored);
        assertTrue("the demo is still the demo", f.bundled);
        assertEquals("a stored zoom is now honoured", 0.42f, f.zoom, 1e-6f);
        assertEquals(0.13f, f.depth, 1e-6f);
    }

    /** The same on the lock screen: the demo is not special on either surface. */
    @Test
    public void aStoredValueForTheBundledDemoIsUsedOnTheLockScreenToo() {
        LibraryItem stored = demo().withZoom(Surface.LOCK, 42);
        assertTrue(Framing.of(stored, Surface.LOCK).bundled);
        assertEquals(0.42f, Framing.of(stored, Surface.LOCK).zoom, 1e-6f);
    }

    /**
     * A demo with nothing stored renders at the defaults.
     *
     * <p>The property the old test was really protecting, still true and still worth
     * pinning: an untouched demo looks like the demo rather than like whatever the
     * last photo left behind.
     */
    @Test
    public void anUntouchedBundledDemoRendersAtTheDefaults() {
        Framing f = home(demo());
        assertTrue(f.bundled);
        assertEquals(SurfaceSettings.DEFAULT_ZOOM / 100f, f.zoom, 0f);
        assertEquals(SurfaceSettings.DEFAULT_DEPTH / 100f, f.depth, 1e-6f);
        assertEquals(0f, f.orbitX, 0f);
        assertEquals(0f, f.panX, 0f);
    }

    @Test
    public void aRealPhotoIsNotTheBundledDemo() {
        assertFalse(home(photo("a", 90, 100, 0f, 0f, 0f, 0f)).bundled);
    }

    // -------------------------------------------------------------- selection

    /**
     * Whatever a delete of the active item does to the index, the render has to
     * end up somewhere renderable rather than throwing or going blank.
     */
    @Test
    public void deletingTheActiveItemLandsOnTheBundledDemo() {
        Library lib = Library.empty().withItem(demo())
                .withItem(photo("a", 90, 100, 0f, 0f, 0f, 0f))
                .withSelection("a");
        assertFalse(home(lib.activeItem()).bundled);

        Library after = lib.remove("a");
        assertNull(after.activeId());
        Framing f = home(after.activeItem());
        assertTrue("must land on the demo, not on nothing", f.bundled);
    }

    /**
     * ofValues hands back exactly what it was given, and never claims to be the
     * bundled special case.
     *
     * <p>The surface glide's start point is a renderer handing its CURRENT numbers
     * back in, so a value that came back changed would make the glide start from
     * somewhere the view never was -- an invisible jump inside a visible ease.
     */
    @Test
    public void ofValuesReturnsExactlyWhatItWasGiven() {
        Framing f =
                Framing.ofValues(
                        0.9f, 1.2f, 0.3f, 0.11f, -0.22f, 40f, -60f, 1.5f, 0.5f,
                        330f, 12f, Framing.SOURCE_VIDEO, Framing.RENDER_SPLAT, 1f,
                        1.4f, 0.55f, 2.5f, 0.5f);
        assertEquals(0.9f, f.depth, 0f);
        assertEquals(1.2f, f.zoom, 0f);
        assertEquals(0.3f, f.motion, 0f);
        assertEquals(0.11f, f.orbitX, 0f);
        assertEquals(-0.22f, f.orbitY, 0f);
        assertEquals(40f, f.panX, 0f);
        assertEquals(-60f, f.panY, 0f);
        assertEquals(1.5f, f.brightnessGain, 0f);
        assertEquals(0.5f, f.shadowLift, 0f);
        assertEquals(330f, f.lightAzimuth, 0f);
        assertEquals(12f, f.lightElevation, 0f);
        assertEquals("and the medium, which is the field a snapshot cannot lose",
                Framing.SOURCE_VIDEO, f.sourceKind, 0f);
        // The four splat values, handed back exactly. A snapshot that could not
        // reproduce them would compare unequal to the frame on screen every resolve.
        assertEquals(1.4f, f.splatDepth, 0f);
        assertEquals(0.55f, f.splatEps2d, 0f);
        assertEquals(2.5f, f.splatBlur, 0f);
        assertEquals(0.5f, f.splatScale, 0f);
        assertFalse("a snapshot is numbers, not the bundled file case",
                f.bundled);
    }

    /**
     * The default light a photo resolves to is the historical literal.
     *
     * <p>Asserted on {@link Framing}, not only on {@link SurfaceSettings}, because
     * this is the value the renderer actually receives: the renderer used to hold
     * the literal itself, and the whole point of this field is that an upgrade
     * cannot change anyone's shading. The epsilon is discussed in
     * {@code LightDirectionTest}, which pins the mapping.
     */
    @Test
    public void theDefaultLightResolvesThroughEveryPath() {
        assertEquals(LightDirection.DEFAULT_AZIMUTH_DEG,
                home(demo()).lightAzimuth, 1e-6f);
        assertEquals(LightDirection.DEFAULT_ELEVATION_DEG,
                home(demo()).lightElevation, 1e-6f);
        assertEquals(LightDirection.DEFAULT_ELEVATION_DEG,
                Framing.of(null, Surface.HOME).lightElevation, 1e-6f);
        assertEquals(LightDirection.DEFAULT_AZIMUTH_DEG,
                Framing.of(photo("a", 90, 100, 0f, 0f, 0f, 0f), Surface.HOME)
                        .lightAzimuth, 1e-6f);
    }
}
