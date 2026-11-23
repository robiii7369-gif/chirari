package io.github.robiii7369.chirari;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Live updates, so most changes reach phones without reinstalling:
 *  - the app screens and the word list (index.html) are downloaded from GitHub and used from the next screen load;
 *  - for changes to the Android part itself (widget code), the app sees a newer version number and offers an
 *    "Update" button that downloads the new APK (Android always asks the user to tap Install for apps outside Play).
 * If there is no internet, or a download looks broken, the app keeps using the copy it already has.
 */
final class Updater {
    private static final String REPO = "https://raw.githubusercontent.com/robiii7369-gif/chirari/main/";
    private static final String PAGE_URL = REPO + "index.html";
    private static final String RELEASE = "https://github.com/robiii7369-gif/chirari/releases/download/apk/";
    static final String APK_URL = RELEASE + "chirari.apk";
    private static final String VERSION_URL = RELEASE + "version.json";
    private static final String ASSET_URL = "file:///android_asset/index.html";

    private Updater() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("chirari-updates", Context.MODE_PRIVATE);
    }

    private static File liveFile(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "live"), "index.html");
    }

    /** The page the WebView should open: the downloaded copy if there is one, otherwise the built-in one. */
    static String pageUrl(Context ctx) {
        File f = liveFile(ctx);
        return f.exists() ? "file://" + f.getAbsolutePath() : ASSET_URL;
    }

    /** The current page's HTML, for the widget's word list. */
    static String readPage(Context ctx) {
        File f = liveFile(ctx);
        try (InputStream in = f.exists() ? new java.io.FileInputStream(f) : ctx.getAssets().open("index.html")) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (f.exists()) { f.delete(); return readPage(ctx); } // broken copy: fall back to the built-in one
            return "";
        }
    }

    /**
     * Downloads the latest app page if it changed. Returns true when a new copy was saved.
     * Runs on a background thread.
     */
    static boolean fetchPage(Context ctx) {
        HttpURLConnection c = null;
        try {
            // the time parameter skips GitHub's few-minute cache
            c = open(PAGE_URL + "?t=" + System.currentTimeMillis() / 60000L);
            String etag = prefs(ctx).getString("etag", null);
            if (etag != null && liveFile(ctx).exists()) c.setRequestProperty("If-None-Match", etag);
            int code = c.getResponseCode();
            if (code != 200) return false;
            byte[] body;
            try (InputStream in = c.getInputStream()) { body = readAll(in); }
            String html = new String(body, StandardCharsets.UTF_8);

            // Only accept a page that is clearly a working Chirari page
            if (!html.contains("ChirariAndroid") || Words.parse(html).size() < 50) return false;
            if (html.equals(readPage(ctx))) {
                prefs(ctx).edit().putString("etag", c.getHeaderField("ETag")).apply();
                return false;
            }

            File f = liveFile(ctx);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            File tmp = new File(dir, "index.html.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(body); }
            if (!tmp.renameTo(f)) return false;
            prefs(ctx).edit().putString("etag", c.getHeaderField("ETag")).apply();
            Words.reset();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The newest APK version on GitHub, or -1 if it can't be checked. Runs on a background thread. */
    static int latestVersionCode() {
        HttpURLConnection c = null;
        try {
            c = open(VERSION_URL);
            if (c.getResponseCode() != 200) return -1;
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)).optInt("versionCode", -1);
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static int installedVersionCode(Context ctx) {
        try {
            android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) return (int) info.getLongVersionCode();
            @SuppressWarnings("deprecation") int code = info.versionCode;
            return code;
        } catch (Exception e) {
            return Integer.MAX_VALUE; // unknown: never nag
        }
    }

    /** Checks at most once every 2 minutes, so reopening the app doesn't use data each time. */
    static boolean dueForCheck(Context ctx) {
        long now = System.currentTimeMillis();
        long last = prefs(ctx).getLong("checked", 0);
        if (now - last < 2 * 60 * 1000L) return false;
        prefs(ctx).edit().putLong("checked", now).apply();
        return true;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
