package io.github.robiii7369.chirari;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the word list straight out of assets/index.html (the same file as the web app),
 * so the widget and the app always use the same words.
 * When a live update has been downloaded, that newer copy is used instead (see Updater).
 */
final class Words {
    static final String PREFS = "chirari";

    static final class Word {
        int id;
        String pack, word, kana, romaji, type;
        JSONObject meaning;

        String meaningIn(String lang) {
            String m = meaning.optString(lang, "");
            return m.isEmpty() ? meaning.optString("en", "") : m;
        }
    }

    private static List<Word> cache;

    static synchronized List<Word> all(Context ctx) {
        if (cache == null) cache = parse(Updater.readPage(ctx));
        return cache;
    }

    /** Forget the loaded words, e.g. after a live update downloaded a new word list. */
    static synchronized void reset() {
        cache = null;
    }

    /** Pulls the WORDS list out of the web app's HTML. Returns an empty list if it can't. */
    static List<Word> parse(String html) {
        List<Word> list = new ArrayList<>();
        try {
            String marker = "const WORDS = ";
            int start = html == null ? -1 : html.indexOf(marker);
            if (start >= 0) {
                JSONArray arr = (JSONArray) new JSONTokener(html.substring(start + marker.length())).nextValue();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Word w = new Word();
                    w.id = o.getInt("id");
                    w.pack = o.optString("pack", "konbini");
                    w.word = o.optString("word");
                    w.kana = o.optString("kana");
                    w.romaji = o.optString("romaji");
                    w.type = o.optString("type", "noun");
                    w.meaning = o.getJSONObject("meaning");
                    list.add(w);
                }
            }
        } catch (Exception e) {
            list.clear(); // the widget shows a friendly message instead
        }
        return list;
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Values are stored by the web app as JSON text, e.g. "\"ne\"" or "[19,28]". */
    static String lang(Context ctx) {
        return readString(ctx, "chirari:lang", "en");
    }

    static String bg(Context ctx) {
        return readString(ctx, "chirari:bg", "nori");
    }

    /** Part of speech in the user's language (non-English labels are drafts for a native-speaker check). */
    static String posLabel(String type, String lang) {
        String[] l; // en, vi, my, ne
        switch (type) {
            case "verb": l = new String[]{"verb", "động từ", "ကြိယာ", "क्रिया"}; break;
            case "i-adjective": l = new String[]{"い-adjective", "tính từ đuôi い", "い-နာမဝိသေသန", "い-विशेषण"}; break;
            case "na-adjective": l = new String[]{"な-adjective", "tính từ đuôi な", "な-နာမဝိသေသန", "な-विशेषण"}; break;
            case "phrase": l = new String[]{"phrase", "cụm từ", "စကားစု", "वाक्यांश"}; break;
            default: l = new String[]{"noun", "danh từ", "နာမ်", "संज्ञा"};
        }
        switch (lang) {
            case "vi": return l[1];
            case "my": return l[2];
            case "ne": return l[3];
            default: return l[0];
        }
    }

    static String pack(Context ctx) {
        return readString(ctx, "chirari:pack", "konbini");
    }

    private static String readString(Context ctx, String key, String fallback) {
        try {
            String raw = prefs(ctx).getString(key, null);
            if (raw == null) return fallback;
            Object v = new JSONTokener(raw).nextValue();
            return (v instanceof String && !((String) v).isEmpty()) ? (String) v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Words the widget rotates through: the user's starred words, or else the chosen pack. */
    static List<Word> rotation(Context ctx) {
        List<Word> all = all(ctx);
        Map<Integer, Word> byId = new HashMap<>();
        for (Word w : all) byId.put(w.id, w);

        List<Word> saved = new ArrayList<>();
        try {
            String raw = prefs(ctx).getString("chirari:saved", null);
            if (raw != null) {
                JSONArray ids = new JSONArray(raw);
                for (int i = 0; i < ids.length(); i++) {
                    Word w = byId.get(ids.optInt(i, -1));
                    if (w != null) saved.add(w);
                }
            }
        } catch (Exception ignored) { }
        if (!saved.isEmpty()) return saved;

        String pack = pack(ctx);
        List<Word> inPack = new ArrayList<>();
        for (Word w : all) if (pack.equals(w.pack)) inPack.add(w);
        return inPack.isEmpty() ? all : inPack;
    }

    static boolean usingSaved(Context ctx) {
        try {
            String raw = prefs(ctx).getString("chirari:saved", null);
            return raw != null && new JSONArray(raw).length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String packName(String pack) {
        switch (pack) {
            case "hotel": return "Hotel";
            case "factory": return "Factory";
            case "daily": return "Daily Life";
            case "care": return "Care work";
            default: return "Konbini & Retail";
        }
    }

    private Words() { }
}
