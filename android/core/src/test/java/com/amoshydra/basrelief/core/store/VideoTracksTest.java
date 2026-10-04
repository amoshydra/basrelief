package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The clip's three files, and the invariant that makes them one thing.
 *
 * <p><b>The invariant is the reason this is a type.</b> §2 of
 * {@code docs/VIDEO-PLAN.md}: <i>"never store one without the other"</i> — colour
 * without depth renders <b>flat, with no relief and no error</b>. Three loose
 * {@code String} fields could each be null and every writer would have to remember;
 * a type that refuses to be constructed without both cannot be wrong.
 *
 * <p>The traps, in the order they can bite:
 *
 * <ol>
 *   <li><b>A stored absolute path is the restore case.</b> With
 *       {@code allowBackup="true"} it survives a restore and still names the previous
 *       device's {@code filesDir}, so the clip is not degraded — it is a gallery row
 *       that renders nothing. The same rule as an image, and the same fix.
 *   <li><b>A half-written clip object is not a clip.</b> A truncated or hand-edited
 *       blob must degrade to the poster photograph, because the alternative describes
 *       a depth track that is not there.
 *   <li><b>The normal track is optional, and its absence is a state.</b> No normal
 *       track is <b>flat lit</b> — correct displacement, plane shading — and it is
 *       reported rather than worked around, because {@code AGENTS.md} forbids
 *       deriving shading twice over.
 *   <li><b>Present is not decodable.</b> {@link VideoTracks#playable} is deliberately
 *       the cheap question, because {@link VideoSource} owns the window above it.
 * </ol>
 */
public class VideoTracksTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static JSONObject json(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            fail("test JSON should parse: " + raw);
            return null;
        }
    }

    private static VideoTracks fromJson(String raw) {
        return VideoTracks.fromJson(json(raw));
    }

    private File write(String name) throws Exception {
        File f = tmp.newFile(name);
        return f;
    }

    // ----------------------------------------------------------- the invariant

    /**
     * <b>Colour without depth is not a clip.</b>
     *
     * <p>The failure it prevents is silent and total: a colour clip with no depth
     * track plays, it just has no relief, so nothing errors and the user sees a flat
     * video. §2 calls this out by name. Refusing to construct one puts the rule where
     * a writer cannot forget it.
     */
    @Test
    public void aClipWithoutItsDepthTrackCannotBeBuilt() {
        for (String[] pair : new String[][]{
                {null, "d.mp4"}, {"c.mp4", null}, {"", "d.mp4"}, {"c.mp4", ""},
                {"null", "d.mp4"}, {"c.mp4", "null"}, {"..", "d.mp4"}, {"c.mp4", ".."},
                {"/", "d.mp4"}, {"c.mp4", ".."}}) {
            try {
                VideoTracks.of(pair[0], pair[1]);
                fail("must refuse a clip with no depth track: " + Arrays.toString(pair));
            } catch (IllegalArgumentException expected) {
                // the only way to stop this at a write site
            }
        }
    }

    /** The normal track is legal to be absent, and says so. */
    @Test
    public void aClipWithNoNormalTrackIsFlatLitRatherThanInert() {
        VideoTracks flat = VideoTracks.of("c.mp4", "d.mp4");
        assertNull(flat.normal);
        assertFalse("absent, not present-and-empty", flat.hasNormalTrack());
        assertTrue("the pair is still complete", flat.depth != null && flat.color != null);
        // And the writer that supplies one changes nothing else.
        VideoTracks shaded = flat.withNormal("n.mp4");
        assertTrue(shaded.hasNormalTrack());
        assertEquals("c.mp4", shaded.color);
        assertEquals("d.mp4", shaded.depth);
    }

    /** {@code withNormal} is the only writer of the normal, as depth's pair writer is. */
    @Test
    public void withNormalCarriesThePairItself() {
        VideoTracks shaded = VideoTracks.of("c.mp4", "d.mp4", "n.mp4");
        assertEquals("c.mp4", shaded.color);
        assertEquals("d.mp4", shaded.depth);
        assertEquals("n.mp4", shaded.normal);
        assertNull("a later write of null clears it rather than half-applying",
                shaded.withNormal(null).normal);
    }

    // -------------------------------------------------------------- filenames

    /**
     * <b>An absolute path is never stored</b>, for any of the three.
     *
     * <p>The restore case, and it is the one that silently costs a user their
     * picture: {@code allowBackup="true"} backs up prefs and {@code filesDir}
     * together, so a stored absolute path comes back pointing at the OLD device's
     * {@code /data/user/0/.../files/}. Reduced to a basename, it re-resolves.
     */
    @Test
    public void anAbsolutePathIsStoredAsABareFilename() {
        VideoTracks clips = VideoTracks.of(
                "/old/device/files/c.mp4",
                "/old/device/files/d.mp4",
                "/old/device/files/n.mp4");
        assertEquals("c.mp4", clips.color);
        assertEquals("d.mp4", clips.depth);
        assertEquals("n.mp4", clips.normal);
        for (String name : new String[]{clips.color, clips.depth, clips.normal}) {
            assertFalse("not a bare filename: " + name, name.contains("/"));
            assertEquals(name, LibraryItem.normaliseName(name));
        }
    }

    /** The same rule on a read value, since parse is where old data enters. */
    @Test
    public void aRestoredAbsolutePathInAStoredClipIsReducedToItsBasename() {
        VideoTracks clips = fromJson("{\"color\":\"/old/device/files/c.mp4\","
                + "\"depth\":\"/old/device/files/d.mp4\"}");
        assertNotNull(clips);
        assertEquals("c.mp4", clips.color);
        assertEquals("d.mp4", clips.depth);
    }

    /** A corrupt blob must not be able to name a file outside filesDir. */
    @Test
    public void aTraversalCollapsesToABasenameRatherThanEscaping() {
        assertEquals("c.mp4", fromJson("{\"color\":\"../../c.mp4\",\"depth\":\"d.mp4\"}")
                .color);
        assertNull("and a backslash is rejected, as it is everywhere else",
                fromJson("{\"color\":\"..\\\\c.mp4\",\"depth\":\"d.mp4\"}"));
    }

    // ------------------------------------------------------------- serialise

    /**
     * The three names survive a round trip, and an absent normal stays absent.
     *
     * <p>Absent rather than null, for the reason every other optional name in this
     * index is: Android's {@code optString} turns a JSON null into the four-character
     * string {@code "null"}, and a name of {@code "null"} is a file nothing will read.
     */
    @Test
    public void theThreeNamesSurviveARoundTrip() {
        VideoTracks shaded = VideoTracks.of("c.mp4", "d.mp4", "n.mp4");
        VideoTracks back = fromJson(shaded.toJson());
        assertNotNull(back);
        assertEquals("c.mp4", back.color);
        assertEquals("d.mp4", back.depth);
        assertEquals("n.mp4", back.normal);

        VideoTracks flat = VideoTracks.of("c.mp4", "d.mp4");
        VideoTracks flatBack = fromJson(flat.toJson());
        assertNotNull(flatBack);
        assertNull(flatBack.normal);
        assertFalse("a flat-lit clip must not write a normal key",
                flat.toJson().contains("normal"));

        // The literal "null" heals to absent, like every other name in the index.
        assertNull(fromJson("{\"color\":\"c.mp4\",\"depth\":\"d.mp4\",\"normal\":\"null\"}")
                .normal);
        assertNull(fromJson("{\"color\":\"c.mp4\",\"depth\":\"d.mp4\",\"normal\":null}")
                .normal);
    }

    /**
     * A half-written clip object is not a clip.
     *
     * <p>The degradation is the poster photograph, and it is strictly better than
     * both alternatives: dropping the row loses the user's photo outright, and
     * accepting the half would describe a depth track that is not there. This is
     * {@link DepthSource}'s standing rule — an absent depth is never a claim — turned
     * into a rule about the object that names the depth.
     */
    @Test
    public void aHalfWrittenClipParsesAsNoClipAtAll() {
        assertNull("no colour", fromJson("{\"depth\":\"d.mp4\"}"));
        assertNull("no depth", fromJson("{\"color\":\"c.mp4\"}"));
        assertNull("nothing at all", fromJson("{}"));
        assertNull("an explicit JSON null is not a name",
                fromJson("{\"color\":null,\"depth\":\"d.mp4\"}"));
        assertNull("the literal \"null\" is not a name either",
                fromJson("{\"color\":\"null\",\"depth\":\"null\"}"));
        // And it never throws, whatever it is handed.
        assertNull(VideoTracks.fromJson(null));
    }

    /** The three names and nothing else, enumerated so a fourth has to be deliberate. */
    @Test
    public void theClipObjectCarriesTheThreeNamesAndNothingElse() {
        Set<String> keys = new HashSet<String>(
                json(VideoTracks.of("c.mp4", "d.mp4", "n.mp4").toJson()).keySet());
        assertEquals(keys.toString(),
                new HashSet<String>(Arrays.asList("color", "depth", "normal")), keys);
    }

    // ------------------------------------------------------------- resolving

    /**
     * The restore case end to end: a stale absolute value re-resolves to this
     * device's file, exactly as {@link PrefFilesTest} pins for an image.
     *
     * <p>So the pair's rule is not merely "store a basename" but "resolve through
     * the one resolver that accepts both forms" — a basename that is never
     * re-resolved against {@code filesDir} is the same bug with the other half of
     * the fix missing.
     */
    @Test
    public void aStaleAbsoluteValueReResolvesToThisDevicesFile() throws Exception {
        write("c.mp4");
        write("d.mp4");
        VideoTracks clips = fromJson("{\"color\":\"/data/user/0/com.amoshydra.basrelief/"
                + "files/c.mp4\",\"depth\":\"/data/user/0/com.amoshydra.basrelief/files/"
                + "d.mp4\"}");
        assertNotNull(clips);
        File got = clips.colorFile(tmp.getRoot());
        assertTrue("a restored absolute value must re-resolve to the local file",
                got != null && got.exists());
        assertEquals("c.mp4", got.getName());
        assertNotNull(clips.depthFile(tmp.getRoot()));
    }

    /** A missing file resolves to null rather than to a dangling path. */
    @Test
    public void aMissingClipFileResolvesToNull() {
        VideoTracks clips = VideoTracks.of("gone.mp4", "also-gone.mp4", "n.mp4");
        assertNull(clips.colorFile(tmp.getRoot()));
        assertNull(clips.depthFile(tmp.getRoot()));
        assertNull(clips.normalFile(tmp.getRoot()));
        assertFalse(clips.playable(tmp.getRoot()));
    }

    /**
     * <b>Playable means the pair, not the colour.</b>
     *
     * <p>Same trap as the constructor, one layer out: a colour clip whose depth track
     * is missing would play and look flat, so answering "yes" here would let the
     * renderer pick it and the user would see a video with no relief and nothing in
     * the log.
     */
    @Test
    public void playableRequiresBothAndDoesNotRequireTheNormal() throws Exception {
        write("c.mp4");
        write("d.mp4");

        VideoTracks both = VideoTracks.of("c.mp4", "d.mp4");
        assertTrue("colour and depth is the whole requirement", both.playable(tmp.getRoot()));
        assertTrue("and no normal track is a deliberate state, not a broken one",
                both.withNormal("n.mp4").playable(tmp.getRoot()));

        assertFalse("a colour clip with no depth is flat, so it is not playable",
                VideoTracks.of("c.mp4", "missing.mp4").playable(tmp.getRoot()));
        assertFalse("and the normal track changes nothing about that",
                VideoTracks.of("c.mp4", "missing.mp4", "n.mp4").playable(tmp.getRoot()));
        assertFalse("a null filesDir is not a playable answer",
                VideoTracks.of("c.mp4", "d.mp4").playable(null));
    }

    /** The answers are named for the diagnostic, which is the only place they print. */
    @Test
    public void eachAnswerNamesItself() {
        assertEquals("VideoTracks{item-a.mp4 +depth (flat lit)}",
                VideoTracks.of("item-a.mp4", "item-a-depth.mp4").toString());
        assertEquals("VideoTracks{item-a.mp4 +depth +normal}",
                VideoTracks.of("item-a.mp4", "item-a-depth.mp4", "item-a-normal.mp4")
                        .toString());
    }

    // ------------------------------------------------- the per-id name scheme

    /**
     * Clips are named from the item's id like every other file, and cannot collide
     * with that row's own stills.
     *
     * <p>Two items must never share a file — the share path that wrote every import to
     * one fixed name filled a gallery with nine rows showing one photo — so the name
     * derives from the id rather than being chosen. The extensions differ from the
     * stills', which is what lets a poster and a clip of the same row live side by
     * side under one id without either overwriting the other (§3 step 8).
     */
    @Test
    public void clipFileNamesAreUniquePerItemAndNeverCollideWithTheStills() {
        String one = LibraryItem.videoFileName("id-one");
        String two = LibraryItem.videoFileName("id-two");
        Set<String> names = new HashSet<String>();
        for (String n : new String[]{
                one, two,
                LibraryItem.videoDepthFileName("id-one"),
                LibraryItem.videoDepthFileName("id-two"),
                LibraryItem.videoNormalFileName("id-one"),
                LibraryItem.videoNormalFileName("id-two"),
                LibraryItem.imageFileName("id-one"),
                LibraryItem.depthFileName("id-one"),
                LibraryItem.normalFileName("id-one")}) {
            assertEquals("not a bare filename: " + n, n, LibraryItem.normaliseName(n));
            assertTrue("two files would collide: " + n, names.add(n));
        }
        assertTrue("a clip is an mp4 beside a png, not instead of it",
                one.endsWith(".mp4") && LibraryItem.imageFileName("id-one").endsWith(".png"));
    }

    @Test
    public void clipFileNamesRejectAnUnusableId() {
        for (String bad : new String[]{"../evil", "", null}) {
            try {
                LibraryItem.videoFileName(bad);
                fail("must not produce a filename from: " + bad);
            } catch (IllegalArgumentException expected) {
                // the id is interpolated, so it has to be checked rather than trusted
            }
        }
    }
}