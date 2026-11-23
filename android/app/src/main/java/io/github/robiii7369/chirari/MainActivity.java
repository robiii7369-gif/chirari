package io.github.robiii7369.chirari;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;

/**
 * The Chirari app: shows the same web app (assets/index.html) inside a WebView,
 * and gives it a small bridge called "ChirariAndroid" for storage (shared with the widget)
 * and Japanese audio (Android's own text-to-speech).
 */
public class MainActivity extends Activity {
    static final String EXTRA_WORD_ID = "wordId";
    private static final String START_URL = "file:///android_asset/index.html";

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean japaneseMissing = false;

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

        web.addJavascriptInterface(new Bridge(), "ChirariAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // open outside links in the phone's browser
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
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
            }
        });

        int wordId = getIntent().getIntExtra(EXTRA_WORD_ID, -1);
        web.loadUrl(wordId > 0 ? START_URL + "#w=" + wordId : START_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int wordId = intent.getIntExtra(EXTRA_WORD_ID, -1);
        if (wordId > 0 && web != null) {
            web.evaluateJavascript(
                    "if (typeof openWord === 'function' && state.lang) openWord(" + wordId + ");", null);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Close the word sheet first; leave the app only when nothing is open
        web.evaluateJavascript(
                "(function(){var s=document.getElementById('sheet');" +
                "if(s&&s.classList.contains('open')){closeSheet();return 1;}return 0;})()",
                value -> {
                    if (!"1".equals(value)) super.onBackPressed();
                });
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
                    Toast.makeText(MainActivity.this,
                            "Install a Japanese voice: Settings → Text-to-speech", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Audio is starting… tap again", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
