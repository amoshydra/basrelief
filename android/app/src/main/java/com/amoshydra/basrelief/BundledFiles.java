package com.amoshydra.basrelief;

import android.content.Context;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The demo photograph's two files, in {@code filesDir}.
 *
 * <p><b>Why the demo is copied out of the APK at all.</b> It used to be read
 * straight from {@code res/raw} through a null image name, and that one missing
 * file was the whole of the special-casing: the depth panel refused to generate
 * for a subject with no file, the map section could not offer to regenerate a map
 * it had nowhere to write, a picked map had nothing to be stored against, and
 * every gallery tile needed a branch to decode a resource instead of a path. The
 * special logic was not "the marble is a demo", it was "the marble has no file".
 *
 * <p>So the seed copies {@code res/raw/image.png} and {@code res/raw/depth.png}
 * into {@code filesDir} under {@code item-bundled.png} and
 * {@code item-bundled-depth.png} — the very names
 * {@link LibraryItem#imageFileName}/{@link LibraryItem#depthFileName} derive from
 * the reserved id, exactly as they would for any other item. The demo then travels
 * every path an imported photo does, and {@code res/raw} keeps exactly one job:
 * being the source the copy reads from.
 *
 * <p><b>Both files or neither, and the seed writes both.</b> The two assets are
 * loaded as a pair; ship one without the other and the renderer falls back to a
 * flat map, which draws correctly and has no relief at all, with a status line as
 * the only tell. So the pair is staged under temporary names and moved into place
 * only once both copies succeeded: a half-written pair would be indistinguishable
 * from the bug that pair once caused.
 *
 * <p><b>Idempotent, and cheaply so.</b> The steady state is two {@code exists()}
 * checks and no I/O at all, which matters because this runs on the read path —
 * including on the GL thread at wallpaper cold start. The 2.5 MB copy happens
 * once per install, and again only if the files go missing.
 */
final class BundledFiles {

    private BundledFiles() {
    }

    /** The demo's image, named by its id like any other item's. */
    static final String IMAGE = LibraryItem.imageFileName(Library.BUNDLED_ID);

    /** The demo's depth map. Loaded as a pair with {@link #IMAGE}. */
    static final String DEPTH = LibraryItem.depthFileName(Library.BUNDLED_ID);

    /** The demo's normal map. The third file, and the one that decides whether a
     * fresh install lands shaded or flat lit. */
    static final String NORMAL = LibraryItem.normalFileName(Library.BUNDLED_ID);

    /**
     * The demo's splat cloud. The fourth file, and the one that makes the demo an
     * ordinary 3D row rather than a debug path: it is copied by the same seed and
     * named by the same per-id scheme, so the bundled item carries a real
     * {@code splatName} and the dialog's Splat tab can show its count.
     */
    static final String SPLAT = LibraryItem.splatFileName(Library.BUNDLED_ID);

    /**
     * Put the demo's files in {@code filesDir} if they are not already there.
     *
     * <p><b>Copying is not the same as seeding.</b> This only moves bytes; whether
     * the index should name them is {@code Library.withBundledFiles} (repairing a
     * row) or {@code Library.withBundled} (adding one), and keeping those apart is
     * what stops this from re-adding a demo the user deleted. A caller must
     * therefore only ask for the copy when a demo row exists or is about to be
     * seeded — otherwise a deleted demo leaves orphan files behind forever.
     *
     * <p><b>Three files, and the normal is optional to the pair.</b> The image and
     * depth are the renderable pair and must arrive together or not at all. The
     * normal is the shading: an install that predates the asset, or a copy that
     * failed, is missing it and renders flat lit — which the sheet reports — so a
     * missing normal does not null the pair. That is the difference between the
     * two failure modes and it is deliberate.
     *
     * @return {@code {imageName, depthName, normalName}} when the image and depth
     *         are present afterwards, with {@code normalName} possibly null; or
     *         null when the pair could not be made. Null is not a shrug: the caller
     *         then stores no filename at all, which the renderer reads as "use the
     *         packaged trio" — the state this app shipped in for its whole life, and
     *         a far better outcome than a filename that names a file which is not
     *         there.
     */
    static String[] ensure(Context context, File filesDir) {
        if (context == null || filesDir == null) return null;
        File image = new File(filesDir, IMAGE);
        File depth = new File(filesDir, DEPTH);
        File normal = new File(filesDir, NORMAL);
        File splat = new File(filesDir, SPLAT);

        // Copy only what is missing, so an upgraded install missing the normal
        // copies 176 KB and not the 2.5 MB image beside it.
        boolean wantImage = !image.isFile();
        boolean wantDepth = !depth.isFile();
        boolean wantNormal = !normal.isFile();
        boolean wantSplat = !splat.isFile();
        if (!wantImage && !wantDepth && !wantNormal && !wantSplat) {
            return new String[]{IMAGE, DEPTH, NORMAL, SPLAT};
        }

        File stagedImage = new File(filesDir, IMAGE + ".part");
        File stagedDepth = new File(filesDir, DEPTH + ".part");
        File stagedNormal = new File(filesDir, NORMAL + ".part");
        File stagedSplat = new File(filesDir, SPLAT + ".part");
        try {
            if (wantImage) copy(context, R.raw.image, stagedImage);
            if (wantDepth) copy(context, R.raw.depth, stagedDepth);
            if (wantNormal) copy(context, R.raw.normal, stagedNormal);
            if (wantSplat) copy(context, R.raw.demo, stagedSplat);
            // Renamed into place. The image and depth are the pair: if either
            // cannot be moved, nothing is kept, because a half pair renders flat
            // with no relief and no error. The normal and the cloud are renamed
            // independently -- a failed one of those leaves a real, renderable demo
            // (flat lit, or relief-only) and the sheet says so.
            if (wantImage && !stagedImage.renameTo(image)) {
                throw new IOException("staged image would not move");
            }
            if (wantDepth && !stagedDepth.renameTo(depth)) {
                throw new IOException("staged depth would not move");
            }
            boolean haveNormal = normal.isFile();
            if (wantNormal) {
                haveNormal = stagedNormal.renameTo(normal);
            }
            boolean haveSplat = splat.isFile();
            if (wantSplat) {
                haveSplat = stagedSplat.renameTo(splat);
            }
            // The pair must be there; a normal or cloud that did not move is a
            // degraded demo, not a failed copy.
            if (!image.isFile() || !depth.isFile()) {
                throw new IOException("the demo pair is incomplete after the copy");
            }
            return new String[]{IMAGE, DEPTH, haveNormal ? NORMAL : null,
                    haveSplat ? SPLAT : null};
        } catch (IOException | RuntimeException e) {
            // The rare states are a full disk and a packaged asset that will not
            // open. Neither is worth a crash on the read path -- the wallpaper
            // parses this on the GL thread at cold start, where an exception means
            // no wallpaper at all -- and the caller copes: no filename stored, the
            // packaged trio rendered, and the next attempt tries again.
            android.util.Log.w("Basrelief", "bundled demo copy failed: " + e);
            stagedImage.delete();
            stagedDepth.delete();
            stagedNormal.delete();
            stagedSplat.delete();
            // Only delete what this call was creating; a pre-existing pair must not
            // be destroyed by a failed normal copy.
            if (wantImage) image.delete();
            if (wantDepth) depth.delete();
            if (wantNormal) normal.delete();
            if (wantSplat) splat.delete();
            return null;
        }
    }

    private static void copy(Context context, int resource, File to) throws IOException {
        InputStream in = context.getResources().openRawResource(resource);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
