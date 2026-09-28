package com.amoshydra.basrelief;

/**
 * The most recently decoded photo pair, shared by every surface in the process.
 *
 * <p><b>Why this exists.</b> The system snapshots the wallpaper surface roughly
 * 100 ms after it appears, and a decode takes about a second. So a wallpaper
 * showing a newly selected photo cannot be made correct by anything that happens
 * after the surface appears: not a re-bind, not a synchronous load, not better
 * scheduling. The snapshot is taken long before the pixels exist. The only way
 * for that first snapshot to be right is for the pixels to already be decoded
 * before the surface comes up.
 *
 * <p>And they can be. The studio is already showing the selected photo, which
 * means it has already decoded it. The engine is a separate surface with a
 * separate GL context in the <i>same process</i>, so it can be handed the result
 * instead of doing the work twice " + "-" + " and by the time the user leaves the studio and the
 * wallpaper appears, the content is already there to upload.
 *
 * <p>That is what removes the need for a re-bind. The re-bind was a workaround
 * for a timing problem, and this removes the timing problem.
 *
 * <p><b>Deliberately holds one pair.</b> Not a cache in the general sense: a second
 * entry would be memory nobody asked for, and the case that matters is always the
 * one just shown in the studio. Keyed on both paths, because a photo can share a
 * file with a different depth map and swapping only the depth is a real edit.
 *
 * <p>Bitmaps are never recycled. A recycled bitmap still referenced by a queued
 * upload is a crash on the GL thread, and the alternative "recycle when
 * evicting" is exactly the mistake that makes this unsafe.
 */
final class PairCache {

    private PairCache() {
    }

    private static final Object LOCK = new Object();

    private static String imagePath;
    private static String depthPath;
    private static Object cached; // a ReliefRenderer.Pending, opaque on purpose

    /**
     * Remember a decoded pair, replacing whatever was there.
     *
     * <p>Called on whichever thread finished the decode, which is the IO
     * executor, not the GL thread.
     */
    static void put(String image, String depth, Object pending) {
        if (pending == null) return;
        synchronized (LOCK) {
            imagePath = image;
            depthPath = depth;
            cached = pending;
        }
    }

    /**
     * The decoded pair for these exact paths, or null.
     *
     * <p>Null for the bundled demo, which is not worth caching: it is decoded from
     * a resource in memory already and is the same bytes every time.
     */
    static Object get(String image, String depth) {
        if (image == null) return null;
        synchronized (LOCK) {
            if (cached != null && eq(imagePath, image) && eq(depthPath, depth)
                    && ReliefRenderer.isUsable(cached)) {
                return cached;
            }
            return null;
        }
    }

    /** Forgets everything. Used when a photo is deleted, so its bytes go too. */
    static void clear() {
        synchronized (LOCK) {
            imagePath = null;
            depthPath = null;
            cached = null;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
