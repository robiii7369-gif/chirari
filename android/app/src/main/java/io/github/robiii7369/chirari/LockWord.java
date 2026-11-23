package io.github.robiii7369.chirari;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.text.SpannableStringBuilder;

/**
 * "Word on lock screen" for Android phones, which (mostly) don't allow widgets on the lock screen:
 * a quiet notification that shows the current word, with 🔊 Listen and › Next buttons.
 * No sound, no vibration, no pop-up. It changes every hour together with the home-screen widget.
 */
final class LockWord {
    private static final String CHANNEL = "word_lock";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_ON = "lockword:on";

    private LockWord() { }

    static boolean isOn(Context ctx) {
        return Words.prefs(ctx).getBoolean(PREF_ON, false);
    }

    static void setOn(Context ctx, boolean on) {
        Words.prefs(ctx).edit().putBoolean(PREF_ON, on).apply();
        if (on) {
            schedule(ctx);
            refresh(ctx);
        } else {
            nm(ctx).cancel(NOTIFICATION_ID);
            alarm(ctx).cancel(tick(ctx));
        }
    }

    private static NotificationManager nm(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static AlarmManager alarm(Context ctx) {
        return (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent tick(Context ctx) {
        Intent i = new Intent(ctx, WordWidget.class).setAction(WordWidget.ACTION_TICK);
        return PendingIntent.getBroadcast(ctx, 2, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Wake up shortly after every full hour (when the word changes) to refresh the lock-screen word. */
    static void schedule(Context ctx) {
        if (!isOn(ctx)) return;
        long hour = AlarmManager.INTERVAL_HOUR;
        long next = (System.currentTimeMillis() / hour + 1) * hour + 20_000L;
        alarm(ctx).setInexactRepeating(AlarmManager.RTC, next, hour, tick(ctx));
    }

    private static void ensureChannel(Context ctx) {
        if (nm(ctx).getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Word on lock screen",
                NotificationManager.IMPORTANCE_DEFAULT); // DEFAULT so it shows on the lock screen; sound is off below
        ch.setDescription("Shows your Chirari word on the lock screen. Silent.");
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.enableLights(false);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm(ctx).createNotificationChannel(ch);
    }

    /** Show or update the lock-screen word (does nothing if the setting is off). */
    static void refresh(Context ctx) {
        if (!isOn(ctx)) return;
        try {
            Words.Word w = WordWidget.currentWord(ctx);
            if (w == null) { nm(ctx).cancel(NOTIFICATION_ID); return; }
            ensureChannel(ctx);
            String lang = Words.lang(ctx);

            SpannableStringBuilder title = new SpannableStringBuilder();
            title.append(WordWidget.japanese(w.word));
            if (!w.kana.equals(w.word)) title.append("   ").append(WordWidget.japanese(w.kana));
            String text = w.meaningIn(lang) + "   (" + Words.posLabel(w.type, lang) + ")";
            String label = Words.usingSaved(ctx) ? "My words" : Words.packName(w.pack);

            Intent next = new Intent(ctx, WordWidget.class).setAction(WordWidget.ACTION_NEXT);
            PendingIntent nextPi = PendingIntent.getBroadcast(ctx, 1, next,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_word)
                    .setColor(Color.parseColor("#E0573F"))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSubText(label)
                    .setContentIntent(WordWidget.openIntent(ctx, w.id, false))
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(ctx, R.drawable.ic_volume), "🔊 Listen",
                            WordWidget.openIntent(ctx, w.id, true)).build())
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(ctx, R.drawable.ic_stat_word), "Next word ›", nextPi).build())
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build();
            nm(ctx).notify(NOTIFICATION_ID, n);
        } catch (Exception ignored) {
            // e.g. notifications switched off: nothing to show
        }
    }
}
