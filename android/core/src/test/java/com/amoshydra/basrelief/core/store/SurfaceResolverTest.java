package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amoshydra.basrelief.core.render.Framing;

import org.junit.Test;

/**
 * Which surface an engine draws, what the lock screen is, and what the lock
 * screen can be.
 *
 * <p>Every test pins a trap, and the trap is the test: the failure mode here is
 * not a crash, it is a wallpaper that looks plausible while drawing the other
 * surface's values — which is exactly the shape of bug this project has paid for
 * repeatedly, and the reason this is a class in :core rather than an
 * {@code if} in the engine.
 *
 * <p>The truth table, once, so the tests read against it rather than against each
 * other. It is restated in {@link SurfaceResolver}'s own javadoc, which is where a
 * caller should meet it:
 *
 * <pre>
 *   SDK    flags            surface  lock       coversLock  perSurfaceLock
 *   26-33  any              HOME     STATIC     false       false
 *   34     FLAG_SYSTEM      HOME     STATIC     false       false
 *   34     FLAG_LOCK        LOCK     STATIC     true        false
 *   34     SYSTEM|LOCK      HOME     STATIC     true        false
 *   35+    FLAG_SYSTEM      HOME     LIVE       false       true
 *   35+    FLAG_LOCK        LOCK     LIVE       true        true
 *   35+    SYSTEM|LOCK      HOME     LIVE       true        true
 *   35+    no known bit     HOME     LIVE       false       true
 * </pre>
 *
 * <p>Four rows of that are decisions someone could reasonably have made the other
 * way, and each is a test below:
 *
 * <ol>
 *   <li><b>34 with a lock-only engine.</b> The fact and the promise come apart: a
 *       34 device with {@code persist.wm.debug.lockscreen_live_wallpaper} set does
 *       run a second engine, so {@code isLockOnly()} is true — and the app still
 *       offers no per-surface settings there, because AOSP's default on that level
 *       is one engine and a promise made on a debug property is a promise most
 *       users cannot keep.
 *   <li><b>Both flags.</b> One engine bound to two surfaces is one window that
 *       both screens composite, so one set of values has to be chosen and home is
 *       it.
 *   <li><b>Home only on a capable device.</b> {@code coversLockScreen()} is false
 *       and the lock screen is not showing the photo at all. A capable device that
 *       has not been asked to is not a degraded one.
 *   <li><b>No known bit.</b> Not a documented platform state, so it takes the
 *       fallback — and the fallback is home, because {@code lock: null} means the
 *       lock screen inherits home.
 * </ol>
 */
public class SurfaceResolverTest {

    // ------------------------------------------------------------ the levels

    /**
     * Below 34 there is no signal, so a value claiming otherwise is ignored.
     *
     * <p>Not a defensive measure. On 26–33 there is no {@code mWhich} in the
     * engine plumbing at all, so {@code getWallpaperFlags()} cannot be called and
     * no such value can be read; the only way one arrives here is a caller that
     * invented it or a test that did. Honouring it would mean the app believing a
     * number the platform did not say, and the consequence is a lock screen's
     * framing on the home screen.
     */
    @Test
    public void belowThirtyFourTheFlagsAreIgnoredEntirely() {
        for (int sdk : new int[]{26, 28, 30, 33}) {
            SurfaceResolver r = SurfaceResolver.of(sdk, SurfaceResolver.FLAG_LOCK);

            assertFalse("sdk " + sdk + " cannot tell, so it must not claim to",
                    r.canTellSurface());
            assertEquals("a level that cannot tell must answer the same whatever "
                            + "it is told: sdk " + sdk,
                    SurfaceResolver.of(sdk).surface(), r.surface());
            assertEquals("sdk " + sdk + " must not honour a flag that cannot exist",
                    Surface.HOME, r.surface());
            assertFalse("sdk " + sdk, r.isLockOnly());
            assertFalse("sdk " + sdk + " cannot establish what the lock shows",
                    r.coversLockScreen());
        }
    }

    /**
     * 26–33 degrade to a still, and to nothing else.
     *
     * <p>The honest answer below 35 is a static frame, because
     * {@code WallpaperManager.setBitmap(..., FLAG_LOCK)} is the one wallpaper call
     * a normal app holds and no live lock engine exists. A build that answered
     * LIVE here would be offering a second set of values with no window to render
     * them into — a control whose value is read by nothing, which is the
     * recurring failure this project keeps shipping by accident: a Zoom stored on
     * the bundled demo, a Motion three callers set and no one read.
     */
    @Test
    public void belowThirtyFourTheLockScreenIsAStillAndNotALiveSurface() {
        for (int sdk : new int[]{26, 28, 30, 33}) {
            SurfaceResolver r = SurfaceResolver.of(sdk);
            assertEquals("sdk " + sdk, SurfaceResolver.LockSupport.STATIC_FRAME,
                    r.lockSupport());
            assertFalse("sdk " + sdk, r.supportsPerSurfaceLock());
        }
    }

    /**
     * 34 gained the signal and kept the single engine.
     *
     * <p>The asymmetry is the point. AOSP shipped the lock engine on Android 14
     * behind a debug system property defaulting off, so an engine there reads
     * {@code FLAG_SYSTEM} and the app must not read a second surface into it.
     */
    @Test
    public void thirtyFourCanTellWhichSurfaceButStillHasOneEngine() {
        SurfaceResolver system = SurfaceResolver.of(34, SurfaceResolver.FLAG_SYSTEM);

        assertTrue("34 is the level the signal arrived", system.canTellSurface());
        assertEquals(Surface.HOME, system.surface());
        assertFalse(system.coversLockScreen());
        assertEquals("no second engine in AOSP's default on 34",
                SurfaceResolver.LockSupport.STATIC_FRAME, system.lockSupport());
        assertFalse("and so no per-surface lock settings",
                system.supportsPerSurfaceLock());
    }

    /**
     * The row most easily got wrong in either direction: a 34 device that really
     * does report a lock-only engine.
     *
     * <p>Both halves are asserted, because the temptation is to collapse them
     * into one boolean. The binding is a fact and the app still declines to
     * promise: {@code isLockOnly()} reports what the platform did, and
     * {@code supportsPerSurfaceLock} reports what this build will offer, which on
     * 34 is nothing. Reading the fact as permission is how a Lock toggle ends up
     * on a screen whose value is stored and never rendered.
     *
     * <p>The third assertion is the coherent whole: such an engine draws the lock
     * screen, and what it draws is the lock screen's <i>inherited</i> values,
     * because the lock screen inherits home until someone tunes it. There is
     * nothing missing here — there is nothing to tune.
     */
    @Test
    public void thirtyFourCanHaveALockOnlyEngineWithoutPromisingAnything() {
        SurfaceResolver r = SurfaceResolver.of(34, SurfaceResolver.FLAG_LOCK);

        assertEquals("a lock-only binding is a lock-only engine",
                Surface.LOCK, r.surface());
        assertTrue(r.isLockOnly());
        assertTrue("and the lock screen is compositing it", r.coversLockScreen());
        assertFalse("but 34 is not a level to promise per-surface settings on",
                r.supportsPerSurfaceLock());
        assertEquals("so the app's only lock tool is a still",
                SurfaceResolver.LockSupport.STATIC_FRAME, r.lockSupport());

        LibraryItem plain = LibraryItem.of("a", "a.png", null, 1L)
                .withIntensity(Surface.HOME, 75);
        assertEquals("which is coherent: the lock screen inherits home",
                0.75f, Framing.of(plain, r.surface()).depth, 1e-6f);
    }

    /** 35 is the level the feature is gated on, and it behaves as advertised. */
    @Test
    public void thirtyFiveGivesTheLockScreenItsOwnLiveEngine() {
        SurfaceResolver lock = SurfaceResolver.of(35, SurfaceResolver.FLAG_LOCK);

        assertEquals(Surface.LOCK, lock.surface());
        assertTrue(lock.isLockOnly());
        assertTrue(lock.coversLockScreen());
        assertEquals(SurfaceResolver.LockSupport.LIVE, lock.lockSupport());
        assertTrue(lock.supportsPerSurfaceLock());

        SurfaceResolver system = SurfaceResolver.of(35, SurfaceResolver.FLAG_SYSTEM);
        assertEquals(Surface.HOME, system.surface());
        assertFalse(system.isLockOnly());
        assertFalse("a home-only binding is not the lock screen",
                system.coversLockScreen());
    }

    /**
     * The device gate, stated on every level either side of it.
     *
     * <p>This is R-P3's gate, checked as a table rather than trusted to a
     * comparison at each call site: 34 is the level that looks close enough to be
     * the answer, and it is not. A level this build has never heard of counts as
     * capable — it can only have gained the second engine, never lost it.
     */
    @Test
    public void perSurfaceLockSettingsAreGatedOnThirtyFive() {
        for (int sdk : new int[]{0, 26, 30, 33, 34}) {
            assertFalse("sdk " + sdk, SurfaceResolver.supportsPerSurfaceLock(sdk));
            assertFalse("the instance form must agree, sdk " + sdk,
                    SurfaceResolver.of(sdk).supportsPerSurfaceLock());
            assertEquals("and so the degrade must agree, sdk " + sdk,
                    SurfaceResolver.LockSupport.STATIC_FRAME,
                    SurfaceResolver.of(sdk).lockSupport());
        }
        for (int sdk : new int[]{35, 36, 40, Integer.MAX_VALUE}) {
            assertTrue("sdk " + sdk, SurfaceResolver.supportsPerSurfaceLock(sdk));
            assertEquals(SurfaceResolver.LockSupport.LIVE,
                    SurfaceResolver.of(sdk).lockSupport());
        }
    }

    // ------------------------------------------------------------- the flags

    /**
     * "Both" is home, and the lock screen gets this very surface.
     *
     * <p>One engine bound to home and lock is one window, and the lock screen
     * composites it — so there is one set of values on screen and it has to be
     * one of the two. Home is it, because home is the set the lock screen
     * inherits and the one that exists for every photo. Reading "both" as lock
     * would put the lock framing on the home screen; refusing to answer would
     * blank the wallpaper over a value that is perfectly well defined.
     *
     * <p>The two levels are asserted apart, because they differ on the one column
     * a UI would gate on: the device can do better on 35 and still is not doing
     * it, while on 34 it could not do better at all.
     */
    @Test
    public void anEngineBoundForBothScreensIsTheHomeEngine() {
        int both = SurfaceResolver.FLAG_SYSTEM | SurfaceResolver.FLAG_LOCK;

        for (int sdk : new int[]{34, 35, 36}) {
            SurfaceResolver r = SurfaceResolver.of(sdk, both);
            assertEquals("sdk " + sdk + ": both must not read as lock",
                    Surface.HOME, r.surface());
            assertFalse("sdk " + sdk, r.isLockOnly());
            assertTrue("sdk " + sdk + ": the lock screen does composite it",
                    r.coversLockScreen());
        }

        assertFalse("34 could not run a second engine",
                SurfaceResolver.of(34, both).supportsPerSurfaceLock());
        assertTrue("35 could, and did not",
                SurfaceResolver.of(35, both).supportsPerSurfaceLock());
    }

    /**
     * Zero is not "lock", and neither is a value with no bit we know.
     *
     * <p>Neither state is documented by the platform, so both take the fallback.
     * The trap is treating an unmatched value as a failure and blanking the
     * wallpaper: a wallpaper that renders is wrong in a way a user can describe,
     * and a blank one is not.
     */
    @Test
    public void flagsThatNameNeitherSurfaceResolveToHome() {
        for (int sdk : new int[]{34, 35, 36}) {
            assertEquals("zero flags, sdk " + sdk, Surface.HOME,
                    SurfaceResolver.of(sdk, 0).surface());
            assertEquals("an unknown bit is not a surface either, sdk " + sdk,
                    Surface.HOME, SurfaceResolver.of(sdk, 0x40).surface());
            assertFalse("sdk " + sdk + ": and nothing is compositing the lock screen",
                    SurfaceResolver.of(sdk, 0x40).coversLockScreen());
        }
    }

    /**
     * An unrecognised bit must not cancel a real one.
     *
     * <p>The flags are a bitmask, so the question is which known bits are set and
     * not whether the whole int equals a known constant. A resolver that compared
     * the int to {@code FLAG_LOCK} would agree with the platform on every shipping
     * device and still be wrong: the day a bit is added, it would drop the lock
     * engine back to home and nothing in this project would notice.
     */
    @Test
    public void anUnknownBitDoesNotHideAKnownOne() {
        SurfaceResolver noisy = SurfaceResolver.of(35,
                SurfaceResolver.FLAG_LOCK | 0x40 | 0x0100);

        assertEquals(Surface.LOCK, noisy.surface());
        assertTrue(noisy.isLockOnly());
        assertTrue(noisy.coversLockScreen());
        // and the raw value is kept for the diagnostic, because a resolver that
        // prints a cleaned-up number cannot show the bit that broke it
        assertEquals(0x40 | 0x0100 | SurfaceResolver.FLAG_LOCK, noisy.flags);

        SurfaceResolver bothPlusNoise = SurfaceResolver.of(36,
                SurfaceResolver.FLAG_SYSTEM | SurfaceResolver.FLAG_LOCK | 0x80);
        assertEquals("and 'both' must survive a stray bit too",
                Surface.HOME, bothPlusNoise.surface());
    }

    /**
     * The unread sentinel is a state of its own, not a zero and not a lock.
     *
     * <p>A caller that never passes flags — the shape every call site has on
     * 26–33, since the method does not exist to be called there — must get the
     * safe answer. If that default were ever LOCK, a missed read would put the
     * lock screen's framing on the home screen on every device, which is the
     * "wallpaper shows the wrong thing" failure with no log line to explain it.
     */
    @Test
    public void aCallerThatPassesNoFlagsGetsTheHomeScreen() {
        for (int sdk : new int[]{0, 26, 33, 34, 35, 36, 99}) {
            SurfaceResolver r = SurfaceResolver.of(sdk);
            assertFalse("sdk " + sdk, r.flagsRead());
            assertEquals("sdk " + sdk, Surface.HOME, r.surface());
            assertFalse("sdk " + sdk, r.isLockOnly());
            assertFalse("sdk " + sdk + ": nothing established about the lock screen",
                    r.coversLockScreen());
        }
        // passing the sentinel explicitly is the same state as not passing it
        SurfaceResolver unread = SurfaceResolver.of(35, SurfaceResolver.FLAGS_UNREAD);
        assertFalse(unread.flagsRead());
        assertEquals(Surface.HOME, unread.surface());
        assertEquals(SurfaceResolver.LockSupport.LIVE, unread.lockSupport());
    }

    /**
     * A negative flags value is not a bitmask, and must not be believed as one.
     *
     * <p>The platform answers 1, 2 or 3, and a bit added later can only be
     * switched on — so a negative value can only be the unread sentinel or a
     * caller bug. Masked, it reports every bit set, which resolves to
     * <b>lock</b>: a value nobody supplied turning into the surface with the
     * customisable one. That is the "wallpaper shows the wrong thing" failure
     * arriving through a path nothing logs, and it is why "unread" is checked by
     * sign rather than by equality with the sentinel.
     */
    @Test
    public void aNegativeFlagsValueIsNotABitmask() {
        for (int sdk : new int[]{34, 35, 36}) {
            for (int junk : new int[]{-2, -3, -100, Integer.MIN_VALUE}) {
                SurfaceResolver r = SurfaceResolver.of(sdk, junk);
                assertEquals("sdk " + sdk + " junk " + junk
                        + " must not be a lock screen", Surface.HOME, r.surface());
                assertFalse("sdk " + sdk + " junk " + junk, r.isLockOnly());
                assertFalse("sdk " + sdk + " junk " + junk, r.coversLockScreen());
            }
        }
    }

    /**
     * The unread sentinel's shape is load-bearing, and this is what pins it.
     *
     * <p>{@code FLAGS_UNREAD} is {@code -1}, so every one of its bits is set. That
     * is exactly why the two bit tests exclude it by identity rather than masking
     * it — masked, it reads as "both", and the answers would then depend on a
     * coincidence between the sentinel and the "both" rule instead of on anything
     * checked.
     *
     * <p>So if the sentinel is ever given a different value this fails, because
     * the reason for excluding it has changed and the two bit tests need to be
     * re-read rather than assumed still correct. It is a test about a constant,
     * which is unusual, and it earns its place because the alternative is two
     * branches whose necessity cannot be demonstrated.
     */
    @Test
    public void theUnreadSentinelIsExcludedByBeingAllBitsSet() {
        int known = SurfaceResolver.FLAG_SYSTEM | SurfaceResolver.FLAG_LOCK;
        assertEquals("the sentinel must have every known bit set, or excluding "
                        + "it by identity is no longer necessary",
                known, SurfaceResolver.FLAGS_UNREAD & known);

        for (int sdk : new int[]{34, 35, 36}) {
            SurfaceResolver unread = SurfaceResolver.of(sdk, SurfaceResolver.FLAGS_UNREAD);
            assertFalse("sdk " + sdk, unread.flagsRead());
            assertEquals("sdk " + sdk, Surface.HOME, unread.surface());
            assertFalse("sdk " + sdk, unread.coversLockScreen());
        }
    }

    /**
     * An engine moved between surfaces is a new answer, not a new engine.
     *
     * <p>The platform re-tasks a live engine rather than destroying it, so the
     * same object can be told it is the lock screen and then told it is not. That
     * is why the settings namespace is keyed on the flags and never cached on the
     * engine or the service: both engines share one process, so service-level
     * state would be one value read by two windows — and a wallpaper showing the
     * wrong photo is exactly what that produces.
     */
    @Test
    public void reTaskingChangesTheAnswerWithoutChangingAnythingElse() {
        SurfaceResolver asLock = SurfaceResolver.of(35, SurfaceResolver.FLAG_LOCK);
        SurfaceResolver asSystem = SurfaceResolver.of(35, SurfaceResolver.FLAG_SYSTEM);

        assertEquals(Surface.LOCK, asLock.surface());
        assertEquals(Surface.HOME, asSystem.surface());
        assertEquals("the same device throughout", asLock.sdk, asSystem.sdk);
        assertTrue("the lock screen is compositing one and not the other",
                asLock.coversLockScreen());
        assertFalse(asSystem.coversLockScreen());
    }

    // ---------------------------------------------------------- never throws

    /**
     * Resolution runs on the GL thread at cold start, so junk in must not be a
     * crash on the home screen.
     *
     * <p>The same rule as the index parse, for the same reason. The inputs come
     * from a binder and a version constant rather than from user data, but "cannot
     * happen" is not a property a fallback needs in order to be correct.
     */
    @Test
    public void nothingHereThrowsOnAnyInput() {
        int[] levels = {Integer.MIN_VALUE, -1, 0, 1, 26, 33, 34, 35, Integer.MAX_VALUE};
        int[] flagValues = {Integer.MIN_VALUE, -2, -1, SurfaceResolver.FLAGS_UNREAD, 0,
                1, 2, 3, 0x7fffffff, Integer.MAX_VALUE};

        for (int sdk : levels) {
            for (int flags : flagValues) {
                SurfaceResolver r = SurfaceResolver.of(sdk, flags);
                // queried eagerly and discarded, so an exception here is the
                // failure rather than a compile error
                r.surface();
                r.isLockOnly();
                r.coversLockScreen();
                r.lockSupport();
                r.canTellSurface();
                r.flagsRead();
                r.supportsPerSurfaceLock();
                r.toString();
            }
            SurfaceResolver.of(sdk).lockSupport();
        }

        // an unknown level is not a capable one, and a negative one is not either
        assertFalse(SurfaceResolver.supportsPerSurfaceLock(Integer.MIN_VALUE));
        assertFalse(SurfaceResolver.supportsPerSurfaceLock(0));
        assertEquals("and no level at all cannot conjure a lock engine",
                Surface.HOME, SurfaceResolver.of(0, SurfaceResolver.FLAG_LOCK).surface());
    }

    // ------------------------------------------------- the diagnosis line

    /**
     * The engine's one log line has to name the decision.
     *
     * <p>It exists because three wrong theories about a wrong-surface wallpaper
     * cost a session, and a diagnostic that omits the field being argued about is
     * how that happens again. Asserted loosely on purpose: what is pinned is that
     * the surface, the lock support and the binding are in there, not the
     * punctuation.
     */
    @Test
    public void theDiagnosticNamesTheResolvedSurfaceAndTheLockSupport() {
        String lock = SurfaceResolver.of(36, SurfaceResolver.FLAG_LOCK).toString();
        assertTrue("must name the surface: " + lock, lock.contains("surface=LOCK"));
        assertTrue("must name the lock support: " + lock, lock.contains("lock=LIVE"));
        assertTrue("must name what the lock screen composites: " + lock,
                lock.contains("coversLock=true"));

        String home = SurfaceResolver.of(26).toString();
        assertTrue("and be able to say the flags were never read: " + home,
                home.contains("flags=unread"));
        assertTrue(home.contains("surface=HOME"));
        assertTrue(home.contains("lock=STATIC_FRAME"));
        assertTrue("and that nothing is on the lock screen: " + home,
                home.contains("coversLock=false"));
    }

    // -------------------------------------------- what the answer is used for

    /**
     * The end-to-end property, and the reason the resolver returns a
     * {@link Surface} rather than a boolean.
     *
     * <p>What a lock-only engine renders on the lock screen is the lock screen's
     * own settings — the whole reason this class exists. Below 35 the same engine
     * would be a home engine, so the identical question resolves to the home
     * screen, and a photo that was customised for the lock screen renders its
     * home values there rather than nothing at all.
     */
    @Test
    public void theResolvedSurfaceIsTheOneWhoseSettingsGetRendered() {
        LibraryItem tuned = LibraryItem.of("a", "a.png", null, 1L)
                .withIntensity(Surface.HOME, 80)
                .editSettings(Surface.LOCK,
                        s -> s.with(25, 100, 33, 0f, 0f, 0f, 0f, 0, 0,
                                s.lightAzimuth, s.lightElevation, s.speed,
                                s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale, s.panZ));

        SurfaceResolver lockEngine = SurfaceResolver.of(35, SurfaceResolver.FLAG_LOCK);
        assertEquals(Surface.LOCK, lockEngine.surface());
        assertEquals("a lock engine renders the lock screen's own values",
                0.25f, Framing.of(tuned, lockEngine.surface()).depth, 1e-6f);

        // the same question, asked on a level where the engine cannot tell
        SurfaceResolver blind = SurfaceResolver.of(33);
        assertEquals(Surface.HOME, blind.surface());
        assertEquals(0.8f, Framing.of(tuned, blind.surface()).depth, 1e-6f);
    }

    /**
     * R-U5 and R-U6, end to end: the lock screen follows home until it is
     * written, and a null restores that.
     *
     * <p>The model half of it, with nothing about the platform in the way, because
     * the two halves are independent on purpose — a photo needs no lock-screen
     * work on any device, which is what lets a first-run photo ship with no
     * onboarding for a feature the device may not support at all.
     *
     * <p>{@code lock: null} surviving a round trip is the part worth stating: a
     * restore that turned null into a copy would un-inherit the lock screen for
     * good, and the user would have no way back except by hand-matching every
     * value to home.
     */
    @Test
    public void lockFollowsHomeUntilWrittenAndNullRestoresIt() {
        LibraryItem base = LibraryItem.of("a", "a.png", null, 1L)
                .withIntensity(Surface.HOME, 60);

        assertFalse("a photo nobody customised has no lock of its own",
                base.hasOwnLockSettings());
        assertEquals(60, base.settings(Surface.LOCK).depth);

        LibraryItem tuned = base.editSettings(Surface.LOCK,
                s -> s.with(20, 100, 33, 0f, 0f, 0f, 0f, 0, 0,
                        s.lightAzimuth, s.lightElevation, s.speed,
                        s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale, s.panZ));
        assertTrue(tuned.hasOwnLockSettings());
        assertEquals(20, tuned.settings(Surface.LOCK).depth);
        assertEquals("home must be untouched by a lock edit",
                60, tuned.settings(Surface.HOME).depth);

        LibraryItem reverted = tuned.withSettings(Surface.LOCK, null);
        assertFalse("a null lock restores inheritance",
                reverted.hasOwnLockSettings());
        assertEquals(60, reverted.settings(Surface.LOCK).depth);
        assertEquals(0.6f, Framing.of(reverted, Surface.LOCK).depth, 1e-6f);

        LibraryItem restored = Library.parse(
                Library.empty().withItem(reverted).withSelection("a").toJson())
                .activeItem();
        assertFalse("inheritance survives persistence",
                restored.hasOwnLockSettings());
        assertEquals(60, restored.settings(Surface.LOCK).depth);

        LibraryItem tunedAgain = Library.parse(
                Library.empty().withItem(tuned).withSelection("a").toJson())
                .activeItem();
        assertTrue("and so does a real override", tunedAgain.hasOwnLockSettings());
        assertEquals(20, tunedAgain.settings(Surface.LOCK).depth);
    }
}
