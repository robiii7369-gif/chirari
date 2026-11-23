package io.github.robiii7369.chirari;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LocaleSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Home-screen widget: one word every hour.
 * Tap the word to open it, 🔊 to open it and hear it, › for the next word.
 * Background: one of five designs, or the user's own photo (chosen in the app's Style screen).
 */
public class WordWidget extends AppWidgetProvider {
    static final String ACTION_NEXT = "io.github.robiii7369.chirari.NEXT_WORD";
    static final String ACTION_TICK = "io.github.robiii7369.chirari.HOURLY";
    private static final long HOUR_MS = 60L * 60L * 1000L;

    /** Background design: drawable + whether it is light (needs dark text). */
    private static final class Style {
        final int drawable; final boolean light; final int ink;
        Style(int drawable, boolean light, int ink) { this.drawable = drawable; this.light = light; this.ink = ink; }
    }

    private static Style styleFor(String bg) {
        switch (bg) {
            case "dusk": return new Style(R.drawable.bg_dusk, false, Color.WHITE);
            case "yoru": return new Style(R.drawable.bg_yoru, false, Color.WHITE);
            case "sakura": return new Style(R.drawable.bg_sakura, true, Color.parseColor("#3A1F2B"));
            case "washi": return new Style(R.drawable.bg_washi, true, Color.parseColor("#1E2430"));
            default: return new Style(R.drawable.bg_nori, false, Color.WHITE);
        }
    }

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
        String a = intent.getAction();
        if (ACTION_TICK.equals(a) || Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            if (!ACTION_TICK.equals(a)) LockWord.schedule(context); // alarms are cleared by a restart
            updateAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

    /** Redraw every Chirari widget, e.g. after the user changes language, pack, background or starred words. */
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WordWidget.class));
        for (int id : ids) draw(context, manager, id);
        LockWord.refresh(context);
    }

    static Words.Word currentWord(Context context) {
        List<Words.Word> list = Words.rotation(context);
        if (list.isEmpty()) return null;
        long slot = System.currentTimeMillis() / HOUR_MS + Words.prefs(context).getInt("widget:offset", 0);
        return list.get((int) Math.floorMod(slot, (long) list.size()));
    }

    /** Mark text as Japanese so kanji use Japanese letter shapes even when the phone is set to English. */
    static SpannableString japanese(String text) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new LocaleSpan(Locale.JAPAN), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** "しゅっきん (noun)" with the bracket part a little smaller. */
    private static CharSequence kanaLine(Words.Word w, String lang) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        b.append(w.kana.equals(w.word) ? new SpannableString(w.romaji) : japanese(w.kana));
        int start = b.length();
        b.append("  (").append(Words.posLabel(w.type, lang)).append(")");
        b.setSpan(new RelativeSizeSpan(0.8f), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(alpha * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static void draw(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_word);
        Words.Word w = currentWord(context);
        String lang = Words.lang(context);

        // ---- background
        String bg = Words.bg(context);
        String photo = Words.prefs(context).getString("widget:photo", null);
        boolean usePhoto = "photo".equals(bg) && photo != null && new File(context.getFilesDir(), photo).exists();
        Style style = usePhoto ? styleFor("nori") : styleFor(bg);
        views.setInt(android.R.id.background, "setBackgroundResource", style.drawable);
        if (usePhoto) {
            views.setImageViewUri(R.id.widget_photo, BgProvider.uriFor(photo));
            views.setViewVisibility(R.id.widget_photo, View.VISIBLE);
            views.setViewVisibility(R.id.widget_scrim, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.widget_photo, View.GONE);
            views.setViewVisibility(R.id.widget_scrim, View.GONE);
        }

        // ---- colours (dark text on Sakura and Washi)
        int ink = style.ink;
        views.setTextColor(R.id.widget_pack, withAlpha(ink, 0.72f));
        views.setTextColor(R.id.widget_word, ink);
        views.setTextColor(R.id.widget_kana, withAlpha(ink, 0.9f));
        views.setTextColor(R.id.widget_meaning, ink);
        views.setTextColor(R.id.widget_next, ink);
        views.setInt(R.id.widget_say, "setColorFilter", ink);
        int btn = style.light ? R.drawable.widget_btn_light : R.drawable.widget_btn_dark;
        views.setInt(R.id.widget_say, "setBackgroundResource", btn);
        views.setInt(R.id.widget_next, "setBackgroundResource", btn);

        // ---- text
        if (w == null) {
            views.setTextViewText(R.id.widget_pack, "CHIRARI");
            views.setTextViewText(R.id.widget_word, japanese("ちらり"));
            views.setTextViewText(R.id.widget_kana, "");
            views.setTextViewText(R.id.widget_meaning, "Open the app to choose your words");
        } else {
            String label = Words.usingSaved(context) ? "My words" : Words.packName(w.pack);
            views.setTextViewText(R.id.widget_pack, label.toUpperCase(Locale.ROOT));
            views.setTextViewText(R.id.widget_word, japanese(w.word));
            views.setTextViewText(R.id.widget_kana, kanaLine(w, lang));
            views.setTextViewText(R.id.widget_meaning, w.meaningIn(lang));
        }

        // ---- taps
        int wordId = w == null ? 0 : w.id;
        views.setOnClickPendingIntent(R.id.widget_root, openIntent(context, wordId, false));
        views.setOnClickPendingIntent(R.id.widget_say, openIntent(context, wordId, true));
        Intent next = new Intent(context, WordWidget.class).setAction(ACTION_NEXT);
        views.setOnClickPendingIntent(R.id.widget_next, PendingIntent.getBroadcast(context, 1, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        manager.updateAppWidget(widgetId, views);
    }

    /** Opens the app on this word; with speak=true it also plays the word. */
    static PendingIntent openIntent(Context context, int wordId, boolean speak) {
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (wordId > 0) open.putExtra(MainActivity.EXTRA_WORD_ID, wordId);
        open.putExtra(MainActivity.EXTRA_SPEAK, speak);
        int requestCode = (speak ? 500000 : 0) + wordId;
        return PendingIntent.getActivity(context, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
