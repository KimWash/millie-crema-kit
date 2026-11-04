package crema.kyobo;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inserted (by build.sh) as the superclass of the library WebViewClient in 교보도서관 1.2.0.
 * For the library web pages (Kyobo "elibrary-front"), it fetches .js/.css itself and returns
 * a version that Android 4.2 WebKit can run: ES5 syntax (Es5), CSS fallbacks (CssFix) and
 * polyfills (assets/crema/poly.js) in front of every script.
 * Anything that fails falls back to the normal WebView load (returns null).
 */
public class CompatWebViewClient extends WebViewClient {
    static final String TAG = "KyoboCompat";
    private static final int CACHE_MAX = 80;
    private static final Map<String, byte[]> CACHE = new LinkedHashMap<String, byte[]>(32, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > CACHE_MAX; }
    };
    private static volatile String sPoly;
    private static volatile String sDebug;
    private static volatile String sUa;

    public CompatWebViewClient() { super(); }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        try {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null;
            int es = url.indexOf("/__crema_es5__?c=");
            if (es > 0) {
                // an inline <script> that failed to parse (see poly.js): return it as ES5
                String code = java.net.URLDecoder.decode(url.substring(es + 17), "UTF-8");
                String out = CssFix.fixJs(Es5.transform(code));
                if (new File("/sdcard/kyobo_compat.debug").exists()) Log.i(TAG, "inline script " + code.length() + "->" + out.length());
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(out.getBytes("UTF-8")));
            }
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            String low = path.toLowerCase();
            boolean js = low.endsWith(".js"), css = low.endsWith(".css");
            if (!js && !css) return null;
            if (low.indexOf("/elibrary-front/") < 0 && low.indexOf("kyobobook.co.kr/") < 0) return null;

            if (sUa == null) try { sUa = view.getSettings().getUserAgentString(); } catch (Throwable t) { sUa = System.getProperty("http.agent"); }
            if (sPoly == null) sPoly = asset(view, "crema/poly.js");
            boolean debug = new File("/sdcard/kyobo_compat.debug").exists();
            if (debug && sDebug == null) sDebug = asset(view, "crema/debug.js");

            String key = (debug ? "d|" : "") + url;
            byte[] body;
            synchronized (CACHE) { body = CACHE.get(key); }
            // URLs without a query (libraries like jquery-ui) also go to a disk cache across app starts
            File disk = q < 0 ? diskFile(view, key) : null;
            if (body == null && disk != null && disk.isFile()) {
                body = readAll(new java.io.FileInputStream(disk));
                synchronized (CACHE) { CACHE.put(key, body); }
            }
            if (body == null) {
                long t0 = System.currentTimeMillis();
                String src = fetch(url);
                if (src == null) return null;
                String out;
                if (js) {
                    out = CssFix.fixJs(Es5.transform(src));
                    StringBuilder sb = new StringBuilder(out.length() + 8192);
                    if (sPoly != null) sb.append(sPoly).append('\n');
                    if (debug && sDebug != null) sb.append(sDebug).append('\n');
                    out = sb.append(out).toString();
                } else {
                    out = CssFix.fixCss(src);
                }
                body = out.getBytes("UTF-8");
                synchronized (CACHE) { CACHE.put(key, body); }
                if (disk != null) try {
                    disk.getParentFile().mkdirs();
                    File tmp = new File(disk.getPath() + ".tmp");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
                    fo.write(body);
                    fo.close();
                    tmp.renameTo(disk);
                } catch (Throwable ignored) {}
                if (debug) Log.i(TAG, (System.currentTimeMillis() - t0) + "ms " + src.length() + "->" + out.length() + " " + url);
            }
            return new WebResourceResponse(js ? "application/javascript" : "text/css", "UTF-8", new ByteArrayInputStream(body));
        } catch (Throwable t) {
            Log.w(TAG, "intercept failed " + url, t);
            return null;
        }
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            if (sUa != null) c.setRequestProperty("User-Agent", sUa);
            try {
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) c.setRequestProperty("Cookie", ck);
            } catch (Throwable ignored) {}
            int code = c.getResponseCode();
            if (code != 200) { Log.w(TAG, "HTTP " + code + " " + url); return null; }
            String charset = "UTF-8";
            String ct = c.getContentType();
            if (ct != null) {
                int i = ct.toLowerCase().indexOf("charset=");
                if (i >= 0) charset = ct.substring(i + 8).replace("\"", "").split(";")[0].trim();
            }
            return new String(readAll(c.getInputStream()), charset);
        } finally {
            c.disconnect();
        }
    }

    /** bump when Es5/CssFix/poly.js change, so old cached output is not reused */
    static final String VERSION = "5";

    private static File diskFile(WebView v, String key) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((VERSION + "|" + key).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(Integer.toHexString((b & 0xff) | 0x100).substring(1));
            return new File(new File(v.getContext().getCacheDir(), "crema_compat"), sb.toString());
        } catch (Throwable t) { return null; }
    }

    private static String asset(WebView v, String name) {
        try { return new String(readAll(v.getContext().getAssets().open(name)), "UTF-8"); }
        catch (Throwable t) { Log.w(TAG, "asset " + name, t); return null; }
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
}
