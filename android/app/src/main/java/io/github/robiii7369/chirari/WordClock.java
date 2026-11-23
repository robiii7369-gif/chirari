package io.github.robiii7369.chirari;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.List;
import java.util.Random;

/**
 * Decides which word the widget and the lock-screen card show, when it changes, and keeps the history.
 *
 * Modes (chosen in the app):
 *  - "lock": a new word every time the phone is locked (needs the lock-screen card, which keeps Chirari running)
 *            and every time the user closes the app; falls back to hourly so it never gets stuck
 *  - "5min": every 5 minutes
 *  - "hour": every hour
 *
 * Words come in a shuffled order with no repeats until the whole list has been shown.
 */
final class WordClock {
    static final String MODE_LOCK = "lock", MODE_5MIN = "5min", MODE_HOUR = "hour";
    private static final long MINUTE = 60_000L;
    private static final long TICK = 5 * MINUTE;
    private static final int HISTORY_MAX = 50;

    private WordClock() { }

    private static SharedPreferences p(Context ctx) { return Words.prefs(ctx); }

    static String mode(Context ctx) {
        String m = p(ctx).getString("clock:mode", MODE_LOCK);
        return MODE_5MIN.equals(m) || MODE_HOUR.equals(m) ? m : MODE_LOCK;
    }

    static void setMode(Context ctx, String mode) {
        p(ctx).edit().putString("clock:mode", mode).apply();
        schedule(ctx);
    }

    // ------------------------------------------------------------ which word

    static Words.Word current(Context ctx) {
        List<Words.Word> list = Words.rotation(ctx);
        if (list.isEmpty()) return null;
        int pos = p(ctx).getInt("clock:pos", 0);
        return list.get(order(ctx, list.size(), pos));
    }

    /** Position → index in a shuffled order that changes each full round. */
    private static int order(Context ctx, int n, int pos) {
        long seed = p(ctx).getLong("clock:seed", 0);
        if (seed == 0) {
            seed = new Random().nextLong() | 1;
            p(ctx).edit().putLong("clock:seed", seed).apply();
        }
        int round = pos / n, k = pos % n;
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        Random r = new Random(seed + round * 7919L);
        for (int i = n - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = perm[i]; perm[i] = perm[j]; perm[j] = t; }
        return perm[k];
    }

    /**
     * Show the next word. Automatic triggers (lock, app closed) are ignored if the word changed
     * in the last few seconds, so closing the app and locking the phone together only counts once.
     */
    static synchronized void advance(Context ctx, boolean automatic) {
        long now = System.currentTimeMillis();
        long last = p(ctx).getLong("clock:changedAt", 0);
        if (automatic && now - last < 4000) return;
        int pos = p(ctx).getInt("clock:pos", 0);
        p(ctx).edit().putInt("clock:pos", pos + 1).putLong("clock:changedAt", now).apply();
        WordWidget.updateAll(ctx); // redraws and records the new word in the history
    }

    static void onScreenOff(Context ctx) {
        if (MODE_LOCK.equals(mode(ctx))) advance(ctx, true);
    }

    static void onAppClosed(Context ctx) {
        if (MODE_LOCK.equals(mode(ctx))) advance(ctx, true);
    }

    /** Runs every ~5 minutes while the phone is awake. */
    static void tick(Context ctx) {
        long now = System.currentTimeMillis();
        long last = p(ctx).getLong("clock:changedAt", 0);
        if (last == 0) { p(ctx).edit().putLong("clock:changedAt", now).apply(); return; }
        long every;
        switch (mode(ctx)) {
            case MODE_5MIN: every = TICK; break;
            default: every = 60 * MINUTE; // "hour", and the fallback for "lock"
        }
        if (now - last >= every - 30_000L) advance(ctx, false);
    }

    /** A repeating timer every 5 minutes. It only runs while the phone is awake, so it costs almost no battery. */
    static void schedule(Context ctx) {
        Intent i = new Intent(ctx, WordWidget.class).setAction(WordWidget.ACTION_TICK);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 2, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.setRepeating(AlarmManager.RTC, System.currentTimeMillis() + TICK, TICK, pi);
    }

    // ------------------------------------------------------------ history

    /** Adds the word on screen to the history (newest first), unless it is already the latest entry. */
    static synchronized void recordShown(Context ctx, Words.Word w) {
        if (w == null) return;
        try {
            JSONArray old = new JSONArray(p(ctx).getString("clock:history", "[]"));
            if (old.length() > 0 && old.getJSONArray(0).getInt(0) == w.id) return;
            JSONArray out = new JSONArray();
            out.put(new JSONArray().put(w.id).put(System.currentTimeMillis()));
            for (int i = 0; i < old.length() && out.length() < HISTORY_MAX; i++) out.put(old.get(i));
            p(ctx).edit().putString("clock:history", out.toString()).apply();
        } catch (Exception e) {
            p(ctx).edit().remove("clock:history").apply();
        }
    }

    /** [[wordId, timeMillis], ...] newest first. */
    static String history(Context ctx) {
        return p(ctx).getString("clock:history", "[]");
    }

    static void clearHistory(Context ctx) {
        p(ctx).edit().remove("clock:history").apply();
        recordShown(ctx, current(ctx));
    }
}
