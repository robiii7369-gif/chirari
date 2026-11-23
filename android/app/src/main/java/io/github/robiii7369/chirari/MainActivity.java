package io.github.robiii7369.chirari;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * The Chirari app: shows the same web app (assets/index.html) inside a WebView,
 * and gives it a small bridge called "ChirariAndroid" for storage (shared with the widget),
 * Japanese audio (Android's own text-to-speech) and choosing a background photo.
 */
public class MainActivity extends Activity {
    static final String EXTRA_WORD_ID = "wordId";
    static final String EXTRA_SPEAK = "speak";
    private static final int PICK_PHOTO = 7;

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean japaneseMissing = false;
    private String pendingSpeech = null;
    private volatile String photoThumbCache = null;
    private boolean updateOffered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#FBF7F0"));
        setContentView(web);

        // Keep the app below the status bar and above the navigation bar
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener((View v, WindowInsets insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(true); // needed to open the downloaded (live-updated) copy of the app page

        web.addJavascriptInterface(new Bridge(), "ChirariAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri)); // outside links: phone's browser
                    return true;
                }
                return false;
            }
        });

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.JAPAN);
                japaneseMissing = (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED);
                tts.setSpeechRate(0.85f);
                ttsReady = !japaneseMissing;
                if (ttsReady && pendingSpeech != null) {
                    tts.speak(pendingSpeech, TextToSpeech.QUEUE_FLUSH, null, "chirari");
                } else if (japaneseMissing && pendingSpeech != null) {
                    showVoiceTip();
                }
                pendingSpeech = null;
            }
        });

        Intent i = getIntent();
        int wordId = i.getIntExtra(EXTRA_WORD_ID, -1);
        boolean speak = i.getBooleanExtra(EXTRA_SPEAK, false);
        String url = Updater.pageUrl(this);
        if (wordId > 0) url += "#w=" + wordId + (speak ? "&say=1" : "");
        web.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int wordId = intent.getIntExtra(EXTRA_WORD_ID, -1);
        boolean speak = intent.getBooleanExtra(EXTRA_SPEAK, false);
        if (wordId > 0 && web != null) {
            web.evaluateJavascript(
                    "if (typeof openWord === 'function' && state.lang) { openWord(" + wordId + ");"
                            + (speak ? " speak(byId(" + wordId + ").kana);" : "") + " }", null);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Close the word or style sheet first; leave the app only when nothing is open
        web.evaluateJavascript(
                "(function(){var s=document.getElementById('sheet');" +
                "if(s&&s.classList.contains('open')){closeSheet();return 1;}return 0;})()",
                value -> {
                    if (!"1".equals(value)) super.onBackPressed();
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkForUpdates();
    }

    /** Live update: new screens and words load by themselves; a new Android version shows an Update button. */
    private void checkForUpdates() {
        if (!Updater.dueForCheck(this)) return;
        new Thread(() -> {
            boolean pageChanged = Updater.fetchPage(this);
            int latest = Updater.latestVersionCode();
            runOnUiThread(() -> {
                if (isFinishing() || web == null) return;
                if (pageChanged) {
                    WordWidget.updateAll(this);
                    // Reload now unless the user is looking at a word; otherwise it applies next time
                    web.evaluateJavascript(
                            "(function(){var s=document.getElementById('sheet');return s&&s.classList.contains('open')?1:0;})()",
                            open -> {
                                if (!"1".equals(open)) {
                                    web.loadUrl(Updater.pageUrl(this));
                                    Toast.makeText(this, "Chirari updated ✓", Toast.LENGTH_SHORT).show();
                                }
                            });
                }
                if (latest > Updater.installedVersionCode(this) && !updateOffered) {
                    updateOffered = true;
                    new AlertDialog.Builder(this)
                            .setTitle("New version of Chirari")
                            .setMessage("A new version is ready. Tap Update, then tap Install when Android asks. Your words and settings stay.")
                            .setPositiveButton("Update", (d, w) ->
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Updater.APK_URL))))
                            .setNegativeButton("Later", null)
                            .show();
                }
            });
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        WordWidget.updateAll(this);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void showVoiceTip() {
        Toast.makeText(this, "Install a Japanese voice: Settings → Text-to-speech", Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- photo background

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PHOTO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        Toast.makeText(this, "Setting your photo…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = savePhoto(uri);
            runOnUiThread(() -> {
                if (ok) {
                    WordWidget.updateAll(this);
                    web.evaluateJavascript("window.onPhotoPicked && window.onPhotoPicked()", null);
                } else {
                    Toast.makeText(this, "Sorry, that photo couldn't be used", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** Copies the chosen photo into the app's private folder: max 1440 px, upright, as JPEG. */
    private boolean savePhoto(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            if (longSide <= 0) return false;
            int sample = 1;
            while (longSide / (sample * 2) >= 1440) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return false;

            int rotate = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
            } catch (Exception ignored) { }

            float scale = Math.min(1f, 1440f / Math.max(bmp.getWidth(), bmp.getHeight()));
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            if (rotate != 0) m.postRotate(rotate);
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);

            final String name = "bg_" + System.currentTimeMillis() + ".jpg"; // new name each time, so the launcher refreshes
            try (FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), name))) {
                out.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            }
            File[] old = getFilesDir().listFiles((dir, n) -> n.matches("bg_\\d+\\.jpg") && !n.equals(name));
            if (old != null) for (File f : old) f.delete();

            Words.prefs(this).edit()
                    .putString("widget:photo", name)
                    .putString("chirari:bg", "\"photo\"")
                    .apply();
            photoThumbCache = makeThumb(out);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String makeThumb(Bitmap src) {
        float scale = Math.min(1f, 520f / Math.max(src.getWidth(), src.getHeight()));
        Bitmap small = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * scale)),
                Math.max(1, Math.round(src.getHeight() * scale)), true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 78, bytes);
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }

    private String currentThumb() {
        if (photoThumbCache != null) return photoThumbCache;
        String name = Words.prefs(this).getString("widget:photo", null);
        if (name == null) return "";
        File f = new File(getFilesDir(), name);
        if (!f.exists()) return "";
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) return "";
        photoThumbCache = makeThumb(b);
        return photoThumbCache;
    }

    // ---------------------------------------------------------------- bridge

    /** Methods the web app can call as window.ChirariAndroid.xxx(...) */
    private class Bridge {
        @JavascriptInterface
        public String getItem(String key) {
            return Words.prefs(MainActivity.this).getString(key, null);
        }

        @JavascriptInterface
        public void setItem(String key, String value) {
            Words.prefs(MainActivity.this).edit().putString(key, value).apply();
            runOnUiThread(() -> WordWidget.updateAll(MainActivity.this));
        }

        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> {
                if (ttsReady) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chirari");
                } else if (japaneseMissing) {
                    showVoiceTip();
                } else {
                    pendingSpeech = text; // voice still starting: play as soon as it's ready
                }
            });
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void pickPhoto() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                try {
                    startActivityForResult(pick, PICK_PHOTO);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No photo app found", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String getPhotoThumb() {
            return currentThumb();
        }
    }
}
