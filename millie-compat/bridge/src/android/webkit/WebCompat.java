package android.webkit;

import android.app.ActivityThread;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * Per-package WebView compatibility hooks for old (API 17, WebViewClassic) WebKit.
 * Active only for a package that has config under /system/etc/webview_compat/:
 *   <pkg>.js   inlined as the first <script> of every file:///android_asset/ HTML page
 *   <pkg>.sub  text substitutions ("find<TAB>replace" per line, \n \t \\ escapes)
 *              applied to android_asset .js/.html/.css
 * When active it also logs page loads, resource URLs and JS console output (tag WebCompat).
 */
public final class WebCompat {
    static final String TAG = "WebCompat";
    private static final String DIR = "/system/etc/webview_compat/";

    private static volatile boolean sInit;
    private static boolean sEnabled;
    private static boolean sLog;
    private static String sPkg;
    private static File sJsFile;
    private static String[][] sSubs = new String[0][];
    private static String[] sDedupUrl = new String[0];
    private static long[] sDedupMs = new long[0];
    private static long[] sDedupLast = new long[0];
    private static int[] sDedupSeq = new int[0];
    private static volatile int sInputSeq;

    private WebCompat() {}

    private static synchronized void init() {
        if (sInit) return;
        sInit = true;
        try {
            String pkg = ActivityThread.currentPackageName();
            if (pkg == null) return;
            sPkg = pkg;
            File js = new File(DIR + pkg + ".js");
            File sub = new File(DIR + pkg + ".sub");
            if (js.isFile()) sJsFile = js;
            if (sub.isFile()) sSubs = readSubs(sub);
            File dd = new File(DIR + pkg + ".dedupe");
            if (dd.isFile()) {
                String[][] rules = readSubs(dd);   // "url<TAB>ms"
                sDedupUrl = new String[rules.length];
                sDedupMs = new long[rules.length];
                sDedupLast = new long[rules.length];
                sDedupSeq = new int[rules.length];
                for (int i = 0; i < rules.length; i++) {
                    sDedupUrl[i] = rules[i][0];
                    try { sDedupMs[i] = Long.parseLong(rules[i][1].trim()); } catch (NumberFormatException e) { sDedupMs[i] = 0; }
                    sDedupLast[i] = -1000000L;
                    sDedupSeq[i] = -1;
                }
            }
            sLog = new File(DIR + pkg + ".log").exists();
            sEnabled = sJsFile != null || sub.isFile() || sLog || sDedupUrl.length > 0;
            if (sEnabled) Log.i(TAG, "enabled for " + pkg + " js=" + (sJsFile != null) + " subs=" + sSubs.length);
        } catch (Throwable t) {
            Log.w(TAG, "init failed", t);
        }
    }

    static boolean enabled() {
        if (!sInit) init();
        return sEnabled;
    }

    /** Verbose logging only when /system/etc/webview_compat/<pkg>.log exists (keeps the hot path quiet). */
    static boolean logOn() {
        if (!sInit) init();
        return sLog;
    }

    private static String trunc(String s, int n) {
        if (s == null) return "null";
        return s.length() <= n ? s : s.substring(0, n) + "…(" + s.length() + ")";
    }

    /** Called from the WebView constructor. "<pkg>.swlayer" => force a software layer (no GL tiles). */
    static void onCreate(WebView v) {
        try {
            if (!enabled()) return;
            if (new File(DIR + sPkg + ".swlayer").exists()) {
                v.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                Log.i(TAG, "WebView " + Integer.toHexString(System.identityHashCode(v)) + " -> LAYER_TYPE_SOFTWARE");
            }
        } catch (Throwable t) {
            Log.w(TAG, "onCreate failed", t);
        }
    }

    /** Called from Activity.dispatchTouchEvent / dispatchKeyEvent: counts user input (DOWN events). */
    public static void noteInput(android.view.InputEvent e) {
        if (e instanceof android.view.MotionEvent) {
            if (((android.view.MotionEvent) e).getActionMasked() == android.view.MotionEvent.ACTION_DOWN) sInputSeq++;
        } else if (e instanceof android.view.KeyEvent) {
            if (((android.view.KeyEvent) e).getAction() == android.view.KeyEvent.ACTION_DOWN) sInputSeq++;
        }
    }

    /**
     * WebView.loadUrl hook: drop an exact repeat of a configured URL when no new user input (touch or
     * key DOWN) arrived since the last accepted one and it is within the window — e.g. an app that
     * issues "javascript:app.goToNextPage()" twice for one tap on this WebView. Gating on input
     * rather than time alone keeps genuine fast taps that queue up behind slow page work.
     */
    static boolean shouldDropLoadUrl(String url) {
        if (url == null || !enabled() || sDedupUrl.length == 0) return false;
        for (int i = 0; i < sDedupUrl.length; i++) {
            if (url.equals(sDedupUrl[i])) {
                long now = android.os.SystemClock.uptimeMillis();
                long dt = now - sDedupLast[i];
                int seq = sInputSeq;
                if (seq == sDedupSeq[i] && dt < sDedupMs[i]) {
                    if (sLog) Log.i(TAG, "dropped repeat loadUrl (+" + dt + "ms, no new input): " + url);
                    return true;
                }
                sDedupSeq[i] = seq;
                sDedupLast[i] = now;
                return false;
            }
        }
        return false;
    }

    // ---- logging hooks ----

    static void onLoadUrl(WebView v, String url) {
        if (!logOn() || url == null) return;
        if (url.startsWith(EvalBridge.WRAP_PREFIX)) return; // our own evaluateJavascript wrapper
        Log.i(TAG, "loadUrl " + trunc(url, 300));
        if (url.indexOf("goToNextPage") >= 0 || url.indexOf("goToPrevPage") >= 0) {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder("caller:");
            for (int i = 2; i < st.length && i < 22; i++) {
                String c = st[i].getClassName();
                if (c.startsWith("android.os.") || c.startsWith("com.android.internal.") || c.startsWith("java.lang.reflect.")) continue;
                sb.append("\n   ").append(c).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
            }
            Log.i(TAG, sb.toString());
        }
    }

    /** Hook for WebView.loadDataWithBaseURL: returns (possibly rewritten) data. */
    static String filterData(WebView v, String baseUrl, String data) {
        if (!enabled() || data == null) return data;
        try {
            if (sLog) Log.i(TAG, "loadDataWithBaseURL base=" + baseUrl + " len=" + data.length());
            if (sLog) try {
                File dump = new File(v.getContext().getCacheDir(), "webcompat_last_data.html");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(dump);
                fo.write(data.getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) {}
            boolean html = data.length() > 0 && data.toLowerCase().indexOf("<html") >= 0;
            String s = applySubs(data, "data:" + baseUrl);
            if (html) s = injectJs(s, "data:" + baseUrl);
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "filterData failed", t);
            return data;
        }
    }

    static void onRequest(String url) {
        if (!logOn()) return;
        Log.d(TAG, "req " + trunc(url, 200));
    }

    static void onConsole(String msg, int line, String src) {
        if (!logOn()) return;
        Log.i(TAG, "console: " + trunc(msg, 1000) + " @" + trunc(src, 120) + ":" + line);
    }

    static void onEvaluate(String script) {
        if (!logOn()) return;
        Log.d(TAG, "eval> " + trunc(script, 200));
    }

    static void onResult(int id, String json) {
        if (!logOn()) return;
        Log.d(TAG, "eval< #" + id + " " + trunc(json, 200));
    }

    static void jsLog(String msg) {
        Log.i(TAG, "js: " + trunc(msg, 2000));
    }

    // ---- asset rewriting ----

    /** Called from BrowserFrame.shouldInterceptRequest with the android_asset/res stream. */
    static InputStream filterAsset(String url, InputStream in) {
        if (in == null || url == null || !enabled()) return in;
        try {
            if (!url.startsWith("file:///android_asset/")) return in;
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            String lower = path.toLowerCase();
            boolean html = lower.endsWith(".html") || lower.endsWith(".htm");
            boolean text = html || lower.endsWith(".js") || lower.endsWith(".css");
            if (sLog) Log.i(TAG, "asset " + url);
            if (!text) return in;
            if (!(html && sJsFile != null) && sSubs.length == 0) return in;

            String s = new String(readAll(in), "UTF-8");
            s = applySubs(s, path);
            if (html) s = injectJs(s, path);
            return new ByteArrayInputStream(s.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "filterAsset failed for " + url, t);
            return in;
        }
    }

    private static String applySubs(String s, String where) {
        int applied = 0;
        for (String[] r : sSubs) {
            if (s.indexOf(r[0]) >= 0) {
                s = s.replace(r[0], r[1]);
                applied++;
            }
        }
        if (applied > 0 && sLog) Log.i(TAG, applied + " substitution rule(s) hit in " + where);
        return s;
    }

    private static String injectJs(String s, String where) throws java.io.IOException {
        if (sJsFile == null) return s;
        String js = new String(readAll(new FileInputStream(sJsFile)), "UTF-8");
        js = js.replace("</script", "<\\/script");
        String tag = "<script>/*webview_compat*/\n" + js + "\n</script>";
        int at = indexAfterTag(s, "<head");
        if (at < 0) at = indexAfterTag(s, "<html");
        if (at < 0) at = 0;
        if (sLog) Log.i(TAG, "injected " + sJsFile + " into " + where);
        return s.substring(0, at) + tag + s.substring(at);
    }

    private static int indexAfterTag(String s, String open) {
        int i = s.toLowerCase().indexOf(open);
        if (i < 0) return -1;
        int e = s.indexOf('>', i);
        return e < 0 ? -1 : e + 1;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                if (d == 'n') sb.append('\n');
                else if (d == 't') sb.append('\t');
                else sb.append(d);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String[][] readSubs(File f) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0 || line.startsWith("#")) continue;
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                out.add(new String[] { unescape(line.substring(0, t)), unescape(line.substring(t + 1)) });
            }
        } catch (Throwable t) {
            Log.w(TAG, "readSubs failed", t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return out.toArray(new String[out.size()][]);
    }
}
