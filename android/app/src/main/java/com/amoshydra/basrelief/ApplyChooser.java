package com.amoshydra.basrelief;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceResolver;

/**
 * What is bound to each screen, <b>read</b> rather than chosen.
 *
 * <p><b>This screen used to ask a question and throw the answer away.</b> It showed
 * a Home/Lock toggle, and on Continue it did {@code dismiss(); launchPicker();} —
 * where {@code launchPicker()} takes no arguments and never reads the selection.
 * The chosen set was a local variable that died with the tap. The user noticed
 * from the other side of it: <i>the same question was being asked twice</i>, which is
 * exactly what happened. The app asked which screen, and then the system dialog
 * asked again, because nothing in the app can honour the first answer.
 *
 * <p><b>Nothing in the app can re-bind a live wallpaper.** Measured, not assumed:
 * {@code setWallpaperComponent} is refused by {@code WallpaperManagerService} with a
 * {@code SecurityException}, and the permission it wants is {@code signature|privileged},
 * so declaring it changes nothing. {@code WallpaperManager} has no public equivalent
 * ({@code setBitmap}, {@code setResource} and {@code setStream} all replace a live
 * wallpaper with a static image), and {@code ACTION_CHANGE_LIVE_WALLPAPER} has no
 * surface parameter, so the picker cannot even be pointed at one screen. The system
 * picker is therefore the only route, and the most this screen can honestly do is
 * <b>tell the user what to pick</b> — never decide it. A control here that implied
 * otherwise was lying, so there is no control.
 *
 * <p><b>So the answer is a measurement instead.</b> {@code WallpaperManager.getWallpaperInfo(flags)}
 * reads what is bound to a screen and needs no permission — the refusal is on
 * <i>writing</i> — so the two rows are a readout of the platform's own state, and
 * each one carries the instruction for the dialog the user is about to see. A row
 * reading "Not set" is tappable for exactly that reason: it is not a dead end, it is
 * the button.
 *
 * <p><b>The persisted "applied" set is gone, and that is what makes this honest.</b>
 * It was written by {@code recordAndLaunch()} before the picker ran, so it recorded
 * the user's <i>intent</i> and nothing more — the app cannot observe what they picked
 * there. Worse, it was a second copy of a fact the platform already holds and can
 * change behind our back: System Settings, a restore, another app, or an
 * {@code install -r}, which drops the binding and has done so repeatedly in this
 * project. This file is the one that read and wrote it; what replaced it is
 * {@link #read(Context)}, which asks the platform every time. {@code Library}'s own
 * {@code appliedSurfaces} field is untouched — it is persisted schema with tests
 * around it, and after this change nothing in the app reads it. That is recorded
 * here rather than acted on, because {@code :core} was not this change's to edit.
 *
 * <p><b>Bound correctly means bound to both screens.</b> Not one binding covering
 * both: a single "Home and lock" application is <b>one</b> engine in
 * {@code flags=0x3}, the lock screen composites it, and the per-surface settings
 * this app is built around are rendered by nothing. Note what the readout cannot
 * tell: {@code getWallpaperInfo} answers per screen and both surfaces report
 * {@link Bound#OURS} either way, so a combined binding is indistinguishable from two
 * separate ones from the studio. That is why the note under the list says so rather
 * than claiming the lock screen has its own engine.
 *
 * <p><b>One reminder, gated on the binding having changed</b> — not on the user
 * having been asked. The user may genuinely want the home screen only, and nagging
 * them about the lock screen on every launch is how an advisory becomes wallpaper
 * paper. The gate is a comparison against the state last presented, held in memory
 * and never persisted: the user explicitly asked for nothing to remember, so a
 * cold start is allowed to say it once and nothing survives the process.
 */
final class ApplyChooser {

    /** The mockup's {@code .modal{max-height:88%}}. */
    private static final float MAX_FRACTION = 0.88f;

    // The note ink. The title and rows are ModalSheet's; this one is the qualifier
    // under the list, which no shared factory draws.
    private static final int DIMMER = 0x57FFFFFF;

    /** What is bound to one surface, as the platform reports it right now. */
    enum Bound {
        /** Nothing is bound to this surface. */
        NOTHING,
        /** A still image — a wallpaper file, not a live wallpaper. */
        STILL,
        /** A live wallpaper, and it is this app's. */
        OURS,
        /** A live wallpaper, and it belongs to some other app. */
        OTHER
    }

    /** Nothing is missing. The one state in which there is nothing to advise. */
    static final int MISSING_NONE = 0;
    /** Our wallpaper is not on the home screen. */
    static final int MISSING_HOME = 1;
    /** Our wallpaper is not on the lock screen, and could be. */
    static final int MISSING_LOCK = 2;
    /** Neither. Which makes home the first step, not the lock screen. */
    static final int MISSING_BOTH = 3;

    /**
     * The binding state the last reminder was about, or -1 if none has been shown.
     *
     * <p>In memory on purpose, and it is the whole of the anti-nag rule rather
     * than an input to it: a reminder is offered when the state <b>differs</b> from
     * the last one offered, so applying home fires it once, dismissing it silences
     * it, and it fires again only when the binding moves. No persisted suppression flag
     * exists — the user asked for nothing to remember — and no second copy of the
     * binding exists, because {@link #read(Context)} is the only reader of it.
     */
    private static int lastReminderFingerprint = -1;

    private final Activity activity;

    /** The one window. Both rows live in it. */
    private ModalSheet sheet;

    private ApplyChooser(Activity activity) {
        this.activity = activity;
    }

    static void show(Activity activity) {
        new ApplyChooser(activity).present();
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private String str(int res) {
        return activity.getString(res);
    }

    // ------------------------------------------------------------------ reading

    /** Both surfaces' bindings, and the one question they answer together. */
    static final class Status {

        final Bound home;
        final Bound lock;
        /** Which of {@link #MISSING_*} this is. Derived once, read everywhere. */
        final int missing;

        Status(Bound home, Bound lock) {
            this.home = home;
            this.lock = lock;
            // LOCK only counts as missing where the platform could put it there at
            // all. Below 35 there is no second live engine, so a lock binding exists
            // and still composites the home one — telling the user to go and fix it
            // would be asking for a change with no effect. HOME always counts.
            boolean wantLock = lockOwnEngine() && lock != Bound.OURS;
            boolean wantHome = home != Bound.OURS;
            this.missing = wantHome
                    ? (wantLock ? MISSING_BOTH : MISSING_HOME)
                    : (wantLock ? MISSING_LOCK : MISSING_NONE);
        }

        /** Whether both screens are bound to this app, which is the correct state. */
        boolean complete() {
            return missing == MISSING_NONE;
        }

        /**
         * This exact pair of readings as one number.
         *
         * <p>Needed because "has the binding changed" is asked about <i>state</i>,
         * not about the two objects that happen to hold it, and a caller holding
         * two enums has no way to compare them as one thing.
         */
        int fingerprint() {
            return home.ordinal() * 4 + lock.ordinal();
        }
    }

    /**
     * What is bound to each screen, asked of the platform rather than remembered.
     *
     * <p>The one read path, so the sheet, the studio's status line and the reminder
     * cannot be three binder calls that disagree. Each surface is asked
     * independently and a failure of either is answered pessimistically —
     * {@link Bound#NOTHING}, "claims nothing" — because {@code getWallpaperInfo}
     * rethrows a dead system server's {@code DeadSystemException} unchecked, and a
     * studio that dies at its most ordinary resume is worse than one that says it
     * cannot tell.
     */
    static Status read(Context context) {
        return new Status(readBound(context, Surface.HOME), readBound(context, Surface.LOCK));
    }

    private static Bound readBound(Context context, Surface surface) {
        WallpaperManager wm =
                (WallpaperManager) context.getSystemService(Context.WALLPAPER_SERVICE);
        // No wallpaper service to ask is "cannot tell", which is read the same way
        // as a failure below rather than thrown out of a resume.
        if (wm == null) return Bound.NOTHING;
        // FLAG_LOCK arrived in Android 14. Below it the lock screen has no
        // readable binding of its own, so there is nothing to report and the row
        // says why.
        if (surface == Surface.LOCK && !lockReadable()) return Bound.NOTHING;
        try {
            WallpaperInfo info = wm.getWallpaperInfo(flagFor(surface));
            if (info == null) return Bound.NOTHING;
            // A wallpaper FILE rather than a live wallpaper: still an image on the
            // screen, but nothing this app can draw and nothing that tilts. The
            // two are different answers and the row says which, because "Set" here
            // would be a claim about a wallpaper that cannot move.
            if (info.getServiceName() == null) return Bound.STILL;
            ComponentName ours = DepthWallpaperService.wallpaperComponent(context);
            // Both halves of the component, not either: a different service class
            // in our own package is not us, and neither is our class name behind
            // somebody else's package.
            return ours.getPackageName().equals(info.getPackageName())
                    && ours.getClassName().equals(info.getServiceName())
                    ? Bound.OURS : Bound.OTHER;
        } catch (Exception e) {
            android.util.Log.w("Basrelief",
                    "binding read failed on " + surface.key + ": " + e);
            return Bound.NOTHING;
        }
    }

    /**
     * The {@code WallpaperManager} flag for a screen.
     *
     * <p>Written out rather than imported from {@code SurfaceResolver}, because
     * those are two different namespaces with the same names: the manager's
     * {@code FLAG_SYSTEM}/{@code FLAG_LOCK} are what {@code getWallpaperInfo} takes,
     * while {@code WallpaperService.Engine}'s are what an <i>engine</i> reports and
     * are what {@code SurfaceResolver} copies. Reading the wrong pair asks the
     * platform about the wrong screen and gets a confident answer.
     */
    private static int flagFor(Surface surface) {
        return surface == Surface.LOCK
                ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
    }

    /** Whether the lock screen's binding can be read at all. Android 14+. */
    static boolean lockReadable() {
        return android.os.Build.VERSION.SDK_INT >= SurfaceResolver.API_SURFACE_SIGNAL;
    }

    /**
     * Whether the lock screen can be a live wallpaper in its own right.
     *
     * <p>Asked of {@code SurfaceResolver}, which holds the tested truth table,
     * because {@code FLAG_LOCK} being readable (34) and a second engine existing
     * (35) are two different facts and only one of them makes a binding worth
     * advising about.
     */
    static boolean lockOwnEngine() {
        return SurfaceResolver.supportsPerSurfaceLock(android.os.Build.VERSION.SDK_INT);
    }

    /**
     * Whether the reminder is due, and note what was asked about on the way past.
     *
     * <p>Update-before-answer on purpose: the comparison is against the last state
     * a reminder was <i>offered</i> for, so a state that is still incomplete and
     * still unchanged says nothing again, while a return to complete — or a move
     * from one incomplete shape to another — is a change and speaks once.
     */
    static boolean reminderDue(Status now) {
        int seen = now.fingerprint();
        if (seen == lastReminderFingerprint) return false;
        lastReminderFingerprint = seen;
        return !now.complete();
    }

    /**
     * What the studio's own row says, and what a toast says: the same sentence,
     * because it is the same fact and one word for it is one place to be wrong.
     */
    static String statusText(Context context, Status status) {
        switch (status.missing) {
            case MISSING_HOME:
                return context.getString(R.string.apply_reminder_home);
            case MISSING_LOCK:
                return context.getString(R.string.apply_reminder_lock);
            case MISSING_BOTH:
                return context.getString(R.string.apply_reminder_both);
            default:
                return context.getString(lockOwnEngine()
                        ? R.string.apply_status_both : R.string.apply_status_home);
        }
    }

    // ------------------------------------------------------------------ build

    private void present() {
        final Status status = read(activity);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(ModalSheet.title(activity, str(R.string.apply_title)));
        body.addView(ModalSheet.subtitle(activity, str(R.string.apply_subtitle)));

        // Two status rows and no footer. A Cancel/Continue pair belonged to a
        // question with a decision in it; there is no decision here, so a Continue
        // button would be a second copy of "open the dialog" sitting under two rows
        // that already say what it will do.
        body.addView(ModalSheet.actionRow(activity, str(R.string.apply_home),
                homeSub(status), true, onRowTap()));
        body.addView(lockRow(status));

        // The reason, under the list. Which one depends on which screen is the
        // missing one, and the note is where the reason lives because the reason is
        // about the PAIR — a single "Home and lock" application is one window — and
        // so belongs to neither row alone.
        body.addView(note(str(noteFor(status)), 11f, 8));

        sheet = new ModalSheet(activity, MAX_FRACTION);
        // Every ModalSheet carries a handle bar; this one is the sheet that reports
        // the binding, and a panel with no handle had no discoverable way out.
        // Named, because it closes this sheet and no other.
        sheet.grip(activity.getString(R.string.apply_close));
        sheet.body(body);
        sheet.show(activity);
    }

    /**
     * The lock row: an action where the platform can do the thing, and a reading
     * where it cannot.
     *
     * <p>{@link ModalSheet#actionRow} rather than a switch and rather than a
     * framed row, for one reason: a tappable row has to carry an affordance, and
     * the honest one for "tapping this opens a dialog somewhere else" is the
     * chevron on a plate. Below 35 the row is a
     * {@link ModalSheet#readingRow} instead — a factory that cannot be handed a
     * tap at all — because there is no action to offer and a row that looked
     * pressable would be the one lie this screen is here to stop telling.
     */
    private View lockRow(Status status) {
        String sub = lockSub(status);
        if (!lockOwnEngine()) {
            return ModalSheet.readingRow(activity, str(R.string.apply_lock), sub);
        }
        return ModalSheet.actionRow(activity, str(R.string.apply_lock), sub, true,
                onRowTap());
    }

    /**
     * The home row's subtitle: what is bound, then what to pick if it is not us.
     *
     * <p>Two lines because the two are different jobs. The first is the
     * measurement; the second is the advice the app cannot carry anywhere else,
     * because the picker has no surface parameter and therefore cannot be told.
     */
    private String homeSub(Status status) {
        return stateText(status.home, status.home == Bound.OURS
                ? 0 : R.string.apply_pick_home);
    }

    /** The lock row's subtitle, and the one order that matters on it. */
    private String lockSub(Status status) {
        if (!lockOwnEngine()) {
            // Nothing to act on here, so the second line is the version that
            // explains why rather than an instruction to act on — and where even
            // the binding is unreadable (below 34) there is no state to report
            // either, so it is the version alone rather than a "Not set" the
            // platform never told us.
            return lockReadable()
                    ? stateText(status.lock, R.string.apply_lock_unavailable)
                    : str(R.string.apply_lock_unavailable);
        }
        int next;
        if (status.lock == Bound.OURS) {
            next = 0;
        } else if (status.home != Bound.OURS) {
            // Home first: the picker offers a destination per application, and the
            // note under the list is already saying to choose "Home screen only".
            next = R.string.apply_lock_after_home;
        } else {
            next = R.string.apply_pick_lock;
        }
        return stateText(status.lock, next);
    }

    /** One surface's live state in words. */
    private String stateText(Bound bound, int instruction) {
        int state = bound == Bound.OURS ? R.string.apply_bound_ours
                : bound == Bound.STILL ? R.string.apply_bound_still
                : bound == Bound.OTHER ? R.string.apply_bound_other
                : R.string.apply_bound_none;
        return instruction == 0 ? str(state) : str(state) + "\n" + str(instruction);
    }

    /** The note under the list, chosen by which screen is the one missing. */
    private int noteFor(Status status) {
        if (!lockOwnEngine()) return R.string.apply_lock_note;
        switch (status.missing) {
            case MISSING_LOCK:
                return R.string.apply_lock_note_separate;
            case MISSING_HOME:
            case MISSING_BOTH:
                // Both missing is still a home-first instruction: the note has to say
                // what to do next, and the next dialog is the home screen's.
                return R.string.apply_home_note_separate;
            default:
                return R.string.apply_note_both;
        }
    }

    /**
     * A qualifier under the list: 11px, the dimmer ink, wrapped.
     *
     * <p>Not {@link ModalSheet#note}: that is the mockup's centred aside, and this
     * is the reason a whole row cannot be acted on, which reads as a footnote and
     * is deliberately left-aligned with the rows above it.
     */
    private TextView note(String text, float sizeSp, int topDp) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(DIMMER);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(2), dp(topDp), dp(2), 0);
        return t;
    }

    // ------------------------------------------------------------------ outcome

    /**
     * The rows' one action: close this sheet and hand over to the system.
     *
     * <p>No surface is passed, because none can be. {@code
     * ACTION_CHANGE_LIVE_WALLPAPER} takes a component and nothing else, so a row
     * that said "set the lock screen" could not open a dialog about the lock
     * screen — which is why the instruction is on the row and not here, where it
     * would have had nowhere to be seen.
     */
    private View.OnClickListener onRowTap() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                launchPicker();
            }
        };
    }

    private void launchPicker() {
        // One still frame for the lock screen on a platform that cannot run a
        // second live engine. Asked for on EVERY launch now, where it used to be
        // asked for when the user chose Lock in this dialog: the app cannot observe
        // what they picked in the system's, so a chooser could predict it and a
        // readout cannot. The method is a no-op where a second engine exists.
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).onPickerLaunched();
        }
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                DepthWallpaperService.wallpaperComponent(activity));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Some OEM builds have no live-wallpaper change intent. The chooser is
            // the older route; if that is missing too, the only honest answer is to
            // name the place the user has to go themselves.
            try {
                activity.startActivity(
                        new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(activity, R.string.apply_manual_hint, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void dismiss() {
        if (sheet != null) {
            sheet.dismiss();
            sheet = null;
        }
    }
}
