package io.github.robiii7369.chirari;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Locale;

/**
 * Home-screen widget: shows one word, changes every hour.
 * Tap the word to open it in the app; tap › for the next word right away.
 */
public class WordWidget extends AppWidgetProvider {
    static final String ACTION_NEXT = "io.github.robiii7369.chirari.NEXT_WORD";
    private static final long HOUR_MS = 60L * 60L * 1000L;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) draw(context, manager, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_NEXT.equals(intent.getAction())) {
            int offset = Words.prefs(context).getInt("widget:offset", 0);
            Words.prefs(context).edit().putInt("widget:offset", offset + 1).apply();
            updateAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

    /** Redraw every Chirari widget, e.g. after the user changes language, pack or starred words. */
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WordWidget.class));
        for (int id : ids) draw(context, manager, id);
    }

    static Words.Word currentWord(Context context) {
        List<Words.Word> list = Words.rotation(context);
        if (list.isEmpty()) return null;
        long slot = System.currentTimeMillis() / HOUR_MS + Words.prefs(context).getInt("widget:offset", 0);
        return list.get((int) Math.floorMod(slot, (long) list.size()));
    }

    private static void draw(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_word);
        Words.Word w = currentWord(context);
        String lang = Words.lang(context);

        if (w == null) {
            views.setTextViewText(R.id.widget_pack, "CHIRARI");
            views.setTextViewText(R.id.widget_word, "ちらり");
            views.setTextViewText(R.id.widget_kana, "");
            views.setTextViewText(R.id.widget_meaning, "Open the app to choose your words");
        } else {
            String label = Words.usingSaved(context) ? "My words" : Words.packName(w.pack);
            views.setTextViewText(R.id.widget_pack, label.toUpperCase(Locale.ROOT));
            views.setTextViewText(R.id.widget_word, w.word);
            views.setTextViewText(R.id.widget_kana, w.kana.equals(w.word) ? w.romaji : w.kana);
            views.setTextViewText(R.id.widget_meaning, w.meaningIn(lang));
        }

        // Tap the word: open the app on this word
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (w != null) open.putExtra(MainActivity.EXTRA_WORD_ID, w.id);
        PendingIntent openPi = PendingIntent.getActivity(context, w == null ? 0 : w.id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPi);

        // Tap ›: next word
        Intent next = new Intent(context, WordWidget.class).setAction(ACTION_NEXT);
        PendingIntent nextPi = PendingIntent.getBroadcast(context, 1, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_next, nextPi);

        manager.updateAppWidget(widgetId, views);
    }
}
