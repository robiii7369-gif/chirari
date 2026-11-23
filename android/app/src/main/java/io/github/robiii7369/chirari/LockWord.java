package io.github.robiii7369.chirari;

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
 * No sound, no vibration, no pop-up. It changes together with the home-screen widget.
 */
final class LockWord {
    private static final String CHANNEL = "word_lock";
    static final int NOTIFICATION_ID = 1;
    private static final String PREF_ON = "lockword:on";

    private LockWord() { }

    static boolean isOn(Context ctx) {
        return Words.prefs(ctx).getBoolean(PREF_ON, false);
    }

    static void setOn(Context ctx, boolean on) {
        Words.prefs(ctx).edit().putBoolean(PREF_ON, on).apply();
        if (on) {
            ensureRunning(ctx);
            refresh(ctx);
        } else {
            ctx.stopService(new Intent(ctx, WordService.class));
            nm(ctx).cancel(NOTIFICATION_ID);
        }
    }

    /** Starts the helper that keeps the card up and notices when the phone is locked. */
    static void ensureRunning(Context ctx) {
        if (!isOn(ctx)) return;
        try {
            ctx.startForegroundService(new Intent(ctx, WordService.class));
        } catch (Exception e) {
            refresh(ctx); // Android didn't allow starting it right now: still show the card
        }
    }

    private static NotificationManager nm(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
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
            Notification n = build(ctx);
            if (n != null) nm(ctx).notify(NOTIFICATION_ID, n);
        } catch (Exception ignored) {
            // e.g. notifications switched off: nothing to show
        }
    }

    /** The lock-screen card for the current word. */
    static Notification build(Context ctx) {
            Words.Word w = WordWidget.currentWord(ctx);
            ensureChannel(ctx);
            if (w == null) {
                return new Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_word)
                        .setContentTitle("Chirari").setContentText("Open the app to choose your words")
                        .setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC).build();
            }
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
            return n;
    }
}
