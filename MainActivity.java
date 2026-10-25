package app.uab.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    static final String HOME = "file:///android_asset/home.html";
    static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "grok.com", "chat.deepseek.com", "chatgpt.com", "gemini.google.com",
            "www.perplexity.ai", "copilot.microsoft.com"));
    static final Set<String> KEYS = new HashSet<>(Arrays.asList("relay", "on", "auto", "executed"));
    static final int FILE_REQ = 7;

    WebView web;
    SharedPreferences prefs;
    volatile String curUrl = HOME;
    String shim = "", puente = "";
    ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("uab", MODE_PRIVATE);
        shim = asset("shim.js");
        puente = asset("uab-puente.js");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(btn("Inicio", v -> web.loadUrl(HOME)));
        bar.addView(btn("Atrás", v -> { if (web.canGoBack()) web.goBack(); }));
        bar.addView(btn("Instrucciones", v -> web.evaluateJavascript("window.__uabInsert && window.__uabInsert()", null)));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // Quita la marca de WebView: algunos sitios de inicio de sesión la rechazan.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "UABNative");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("https://") || u.startsWith(HOME)) return false;
                if (u.startsWith("mailto:") || u.startsWith("tel:")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                }
                return true; // http plano e intents: no se abren dentro de la app
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { curUrl = url; }
            @Override public void onPageFinished(WebView v, String url) {
                curUrl = url;
                if (trusted() && !url.startsWith("file:")) {
                    v.evaluateJavascript(shim, null);
                    v.evaluateJavascript(puente, null);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), FILE_REQ);
                } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.loadUrl(HOME);
    }

    Button btn(String t, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(t);
        x.setAllCaps(false);
        x.setOnClickListener(l);
        return x;
    }

    String asset(String n) {
        try (InputStream in = getAssets().open(n)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
            return o.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    /** Solo la pantalla de inicio y las IA de la lista pueden usar el puente nativo. */
    boolean trusted() {
        String u = curUrl;
        if (u == null) return false;
        if (u.startsWith(HOME)) return true;
        if (!u.startsWith("https://")) return false;
        try { return HOSTS.contains(Uri.parse(u).getHost()); } catch (Exception e) { return false; }
    }

    String relayPrefix() {
        try {
            String raw = prefs.getString("relay", null);
            if (raw == null) return null;
            String v = new JSONObject("{\"v\":" + raw + "}").optString("v");
            int i = v.indexOf("/c/");
            if (i < 0 || !v.startsWith("https://")) return null;
            return v.substring(0, i);
        } catch (Exception e) { return null; }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_REQ && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData c = data.getClipData();
                    r = new Uri[c.getItemCount()];
                    for (int i = 0; i < r.length; i++) r[i] = c.getItemAt(i).getUri();
                } else if (data.getData() != null) r = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
        } else super.onActivityResult(req, res, data);
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    class Bridge {
        @JavascriptInterface public String get(String k) {
            if (!trusted() || !KEYS.contains(k)) return null;
            return prefs.getString(k, null);
        }
        @JavascriptInterface public void set(String k, String v) {
            if (!trusted() || !KEYS.contains(k) || v == null || v.length() > 60000) return;
            prefs.edit().putString(k, v).apply();
        }
        @JavascriptInterface public void copy(final String t) {
            if (!trusted()) return;
            runOnUiThread(() -> ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("UAB", t)));
        }
        @JavascriptInterface public void http(final int id, final String url) {
            final String pre = relayPrefix();
            final boolean allowed = trusted() && pre != null && (url.startsWith(pre + "/c/") || url.startsWith(pre + "/r/"));
            new Thread(() -> {
                boolean ok = false; String text = "";
                if (allowed) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(15000); c.setReadTimeout(30000);
                        c.setInstanceFollowRedirects(false);
                        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int k;
                        while (in != null && (k = in.read(buf)) > 0 && o.size() < 2000000) o.write(buf, 0, k);
                        text = o.toString("UTF-8"); ok = true;
                    } catch (Exception ignored) { }
                }
                final String js = "window.__uabHttp&&window.__uabHttp(" + id + "," + ok + "," + JSONObject.quote(text) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }
}
