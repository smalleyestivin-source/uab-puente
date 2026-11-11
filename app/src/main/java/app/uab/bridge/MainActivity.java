package app.uab.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    static final String HOME = "file:///android_asset/home.html";
    /** IA oficiales: nombre, dirección de inicio. Claude va primero. */
    static final String[][] CATALOG = {
            {"Claude", "https://claude.ai/"},
            {"ChatGPT", "https://chatgpt.com/"},
            {"Grok", "https://grok.com/"},
            {"DeepSeek", "https://chat.deepseek.com/"},
            {"Gemini", "https://gemini.google.com/"},
            {"Perplexity", "https://www.perplexity.ai/"},
            {"Copilot", "https://copilot.microsoft.com/"},
            {"Mistral", "https://chat.mistral.ai/"},
            {"Qwen", "https://chat.qwen.ai/"},
            {"Kimi", "https://www.kimi.com/"},
            {"Z.ai (GLM)", "https://chat.z.ai/"},
            {"Meta AI", "https://www.meta.ai/"}
    };
    static final String[] DEFAULT_AIS = {"Claude", "ChatGPT", "Grok", "DeepSeek", "Gemini"};
    static boolean okKey(String k) { return k != null && k.matches("(relay|on|auto|autoins|maxh|executed)(@[a-z0-9_-]{1,30})?"); }
    static final Set<String> KEYS = new HashSet<String>(java.util.Arrays.asList("relay", "on", "auto", "executed"));
    static final int FILE_REQ = 7;

    /** Una pestaña = un WebView que sigue vivo aunque no lo estés viendo. */
    class Tab {
        String name, start, ai, acct, who, profile, state = "idle";
        volatile String url = "";
        WebView web;
        Button btn;
    }

    final List<Tab> tabs = new ArrayList<Tab>();
    final Set<String> hosts = new HashSet<String>();
    Tab cur;
    FrameLayout content;
    LinearLayout bar;
    SharedPreferences prefs;
    String shim = "", puente = "";
    ValueCallback<Uri[]> fileCb;
    Boolean multiProfile;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("uab", MODE_PRIVATE);
        shim = asset("shim.js");
        puente = asset("uab-puente.js");
        for (String[] c : CATALOG) {
            try { hosts.add(Uri.parse(c[1]).getHost()); } catch (Exception ignored) { }
        }
        // Mantiene la pantalla encendida mientras la app está abierta: así las IA siguen trabajando.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(bar, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        newTab("Inicio", HOME, null, null);
        newTab("Proyecto", null, null, null);
        for (String[] t : loadList()) addAiTab(t[0], t[1]);
        rebuildBar();
        Tab first = byName(prefs.getString("lastTab", ""));
        select(first != null && first.ai != null ? first : tabs.get(0));
    }

    /* ---------- lista de pestañas guardada ---------- */
    List<String[]> loadList() {
        List<String[]> out = new ArrayList<String[]>();
        try {
            String raw = prefs.getString("tablist", null);
            if (raw != null) {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new String[]{o.getString("ai"), o.getString("acct")});
                }
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) for (String n : DEFAULT_AIS) out.add(new String[]{n, "1"});
        return out;
    }

    void saveList() {
        try {
            JSONArray a = new JSONArray();
            for (Tab t : tabs) {
                if (t.ai == null) continue;
                JSONObject o = new JSONObject();
                o.put("ai", t.ai);
                o.put("acct", t.acct);
                a.put(o);
            }
            prefs.edit().putString("tablist", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    static String slug(String s) {
        String x = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (x.length() > 28) x = x.substring(0, 28);
        return x.length() == 0 ? "x" : x;
    }

    String startOf(String ai) {
        for (String[] c : CATALOG) if (c[0].equals(ai)) return c[1];
        return null;
    }

    Tab addAiTab(String ai, String acct) {
        String start = startOf(ai);
        if (start == null) return null;
        String name = "1".equals(acct) ? ai : ai + " " + acct;
        Tab t = newTab(name, start, ai, acct);
        t.who = slug(ai + "-" + acct);
        t.profile = "p_" + t.who.replace('-', '_');
        return t;
    }

    Tab newTab(String name, String start, String ai, String acct) {
        final Tab t = new Tab();
        t.name = name; t.start = start; t.ai = ai; t.acct = acct;
        t.btn = new Button(this);
        t.btn.setText(name);
        t.btn.setAllCaps(false);
        t.btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { select(t); } });
        if (ai != null) {
            t.btn.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { confirmClose(t); return true; } });
        }
        tabs.add(t);
        return t;
    }

    void rebuildBar() {
        bar.removeAllViews();
        for (Tab t : tabs) bar.addView(t.btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button plus = new Button(this);
        plus.setText("+ IA");
        plus.setAllCaps(false);
        plus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { addDialog(); } });
        bar.addView(plus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button ins = new Button(this);
        ins.setText("Instrucciones");
        ins.setAllCaps(false);
        ins.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (cur != null && cur.web != null) cur.web.evaluateJavascript("window.__uabInsert && window.__uabInsert()", null);
        } });
        bar.addView(ins, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (Tab t : tabs) paint(t);
        if (cur != null) show(cur);
    }

    void addDialog() {
        final String[] names = new String[CATALOG.length];
        for (int i = 0; i < CATALOG.length; i++) names[i] = CATALOG[i][0];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 20, 40, 0);
        final Spinner sp = new Spinner(this);
        List<String> list = new ArrayList<String>(java.util.Arrays.asList(names));
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list));
        box.addView(sp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText et = new EditText(this);
        et.setHint("Nombre de la cuenta (ej. 2, personal, trabajo)");
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Añadir IA o cuenta")
                .setView(box)
                .setPositiveButton("Añadir", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    String ai = String.valueOf(sp.getSelectedItem());
                    String acct = et.getText().toString().trim();
                    addFromUi(ai, acct);
                } })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    void addFromUi(String ai, String acct) {
        int same = 0;
        for (Tab t : tabs) if (ai.equals(t.ai)) same++;
        if (acct.length() == 0) acct = String.valueOf(same + 1);
        if (acct.length() > 20) acct = acct.substring(0, 20);
        for (Tab t : tabs) if (ai.equals(t.ai) && acct.equalsIgnoreCase(t.acct)) {
            Toast.makeText(this, "Ya tienes esa cuenta.", Toast.LENGTH_LONG).show();
            return;
        }
        if (same >= 1 && !profilesSupported()) {
            Toast.makeText(this, "Tu navegador del sistema (Android System WebView) no permite varias cuentas de la misma IA. Actualízalo en Play Store.", Toast.LENGTH_LONG).show();
            return;
        }
        Tab t = addAiTab(ai, acct);
        saveList();
        rebuildBar();
        if (t != null) select(t);
    }

    void confirmClose(final Tab t) {
        new AlertDialog.Builder(this)
                .setTitle("Cerrar " + t.name + "?")
                .setMessage("La pestaña se quita de la barra. La sesión de esa cuenta se conserva por si la vuelves a añadir.")
                .setPositiveButton("Cerrar", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { closeTab(t); } })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    void closeTab(Tab t) {
        if (t.web != null) { content.removeView(t.web); t.web.destroy(); t.web = null; }
        tabs.remove(t);
        saveList();
        if (cur == t) cur = tabs.get(0);
        rebuildBar();
        select(cur);
    }

    /* ---------- varias cuentas (perfiles de WebView) ---------- */
    boolean profilesSupported() {
        if (multiProfile != null) return multiProfile.booleanValue();
        boolean ok = false;
        try {
            Class<?> feat = Class.forName("androidx.webkit.WebViewFeature");
            Object name = feat.getField("MULTI_PROFILE").get(null);
            ok = ((Boolean) feat.getMethod("isFeatureSupported", String.class).invoke(null, (String) name)).booleanValue();
        } catch (Throwable ignored) { }
        multiProfile = Boolean.valueOf(ok);
        return ok;
    }

    /** Asigna al WebView un perfil propio (cookies separadas). Devuelve su CookieManager o null. */
    CookieManager useProfile(WebView w, String name) {
        if (!profilesSupported()) return null;
        try {
            Class<?> store = Class.forName("androidx.webkit.ProfileStore");
            Object st = store.getMethod("getInstance").invoke(null);
            Object prof = store.getMethod("getOrCreateProfile", String.class).invoke(st, name);
            Class<?> compat = Class.forName("androidx.webkit.WebViewCompat");
            compat.getMethod("setProfile", WebView.class, String.class).invoke(null, w, name);
            return (CookieManager) prof.getClass().getMethod("getCookieManager").invoke(prof);
        } catch (Throwable t) { return null; }
    }

    /* ---------- selección y estado ---------- */
    Tab byName(String n) {
        for (Tab t : tabs) if (t.name.equalsIgnoreCase(n)) return t;
        return null;
    }

    void select(Tab t) {
        if (t.web == null) {
            String start = t.start;
            if (start == null) { // pestaña "Proyecto": el panel de tu relay
                String pre = relayPrefix();
                if (pre == null) {
                    Toast.makeText(this, "Primero pega el enlace de tu relay en Inicio.", Toast.LENGTH_LONG).show();
                    t = tabs.get(0);
                    if (t.web == null) makeWeb(t, t.start);
                    show(t);
                    return;
                }
                start = pre + "/admin";
            }
            if (t.ai != null && t.who != null) {
                String saved = prefs.getString("url_" + t.who, null);
                if (saved != null && saved.startsWith("https://") && hosts.contains(Uri.parse(saved).getHost())) start = saved;
            }
            makeWeb(t, start);
        }
        show(t);
    }

    void show(Tab t) {
        cur = t;
        if (t.ai != null) prefs.edit().putString("lastTab", t.name).apply();
        for (Tab x : tabs) {
            if (x.web != null) x.web.setVisibility(x == t ? View.VISIBLE : View.GONE);
            x.btn.setBackgroundColor(x == t ? Color.parseColor("#4f6bff") : Color.parseColor("#2b3142"));
        }
    }

    void paint(Tab t) {
        int c = Color.WHITE;
        String label = t.name;
        if ("working".equals(t.state)) { c = Color.parseColor("#4ade80"); label = "● " + t.name; }
        else if ("approval".equals(t.state)) { c = Color.parseColor("#fbbf24"); label = "● " + t.name; }
        else if ("paused".equals(t.state) || "error".equals(t.state)) { c = Color.parseColor("#f87171"); label = "● " + t.name; }
        t.btn.setText(label);
        t.btn.setTextColor(c);
    }

    void makeWeb(final Tab t, String start) {
        WebView web = new WebView(this);
        // El perfil se asigna ANTES de cualquier otra llamada al WebView.
        CookieManager cm = null;
        if (t.profile != null) cm = useProfile(web, t.profile);
        if (cm == null) cm = CookieManager.getInstance();
        t.web = web;
        t.url = start;
        content.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(t), "UABNative");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("https://") || u.startsWith(HOME)) return false;
                if (u.startsWith("mailto:") || u.startsWith("tel:")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                }
                return true; // http plano e intents: no se abren dentro de la app
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { t.url = url; }
            @Override public void onPageFinished(WebView v, String url) {
                t.url = url;
                if (t.ai != null && t.who != null && url.startsWith("https://")) {
                    try { if (hosts.contains(Uri.parse(url).getHost())) prefs.edit().putString("url_" + t.who, url).apply(); } catch (Exception ignored) { }
                }
                if (trusted(t) && !url.startsWith("file:")) {
                    String id = t.who == null ? "" : t.who.replace("'", "");
                    v.evaluateJavascript("window.__uabWho='" + id + "';", null);
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
        web.loadUrl(start);
    }

    String asset(String n) {
        try {
            InputStream in = getAssets().open(n);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
            in.close();
            return o.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    /** Solo la pantalla de inicio y las IA de la lista pueden usar el puente nativo. */
    boolean trusted(Tab t) {
        String u = t.url;
        if (u == null) return false;
        if (u.startsWith(HOME)) return true;
        if (!u.startsWith("https://") || t.ai == null) return false;
        try { return hosts.contains(Uri.parse(u).getHost()); } catch (Exception e) { return false; }
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
        if (cur != null && cur.web != null && cur.web.canGoBack()) cur.web.goBack();
        else if (cur != tabs.get(0)) select(tabs.get(0));
        else super.onBackPressed();
    }

    class Bridge {
        final Tab tab;
        Bridge(Tab t) { tab = t; }

        @JavascriptInterface public String get(String k) {
            if (!trusted(tab) || !okKey(k)) return null;
            return prefs.getString(k, null);
        }
        @JavascriptInterface public void set(String k, String v) {
            if (!trusted(tab) || !okKey(k) || v == null || v.length() > 60000) return;
            prefs.edit().putString(k, v).apply();
        }
        @JavascriptInterface public void copy(final String t) {
            if (!trusted(tab)) return;
            runOnUiThread(new Runnable() { public void run() {
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("UAB", t));
            } });
        }
        /** El script informa qué está haciendo esta IA: idle, working, approval, paused o error. */
        @JavascriptInterface public void status(final String st) {
            if (!trusted(tab) || st == null) return;
            if (!("idle".equals(st) || "working".equals(st) || "approval".equals(st) || "paused".equals(st) || "error".equals(st))) return;
            runOnUiThread(new Runnable() { public void run() { tab.state = st; paint(tab); } });
        }
        /** Solo la pantalla de inicio puede cambiar de pestaña. */
        @JavascriptInterface public void open(final String name) {
            if (tab != tabs.get(0) || !trusted(tab)) return;
            runOnUiThread(new Runnable() { public void run() { Tab t = byName(name); if (t != null) select(t); } });
        }
        @JavascriptInterface public void addDialog() {
            if (tab != tabs.get(0) || !trusted(tab)) return;
            runOnUiThread(new Runnable() { public void run() { MainActivity.this.addDialog(); } });
        }
        /** Lista de pestañas con su estado, para la pantalla de inicio. */
        @JavascriptInterface public String tabs() {
            if (tab != tabs.get(0) || !trusted(tab)) return "[]";
            try {
                JSONArray a = new JSONArray();
                for (Tab t : tabs) {
                    if (t == tabs.get(0)) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", t.name);
                    o.put("state", t.state);
                    a.put(o);
                }
                return a.toString();
            } catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public void http(final int id, final String url) {
            final String pre = relayPrefix();
            final boolean allowed = trusted(tab) && pre != null && (url.startsWith(pre + "/c/") || url.startsWith(pre + "/r/"));
            new Thread(new Runnable() { public void run() {
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
                runOnUiThread(new Runnable() { public void run() { if (tab.web != null) tab.web.evaluateJavascript(js, null); } });
            } }).start();
        }
    }
}
