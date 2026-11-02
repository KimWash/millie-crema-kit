package android.webkit;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * API 19 WebView.evaluateJavascript() backport for API 17 (WebViewClassic).
 *
 * One receiver object per WebView is registered as window.__evalBridge from the
 * WebView constructor (JS interfaces are (re)injected on every page load).
 * evaluateJavascript() wraps the script so that its completion value is
 * JSON-stringified and handed back via __evalBridge.onResult(id, json); the
 * result is then delivered to the ValueCallback on the caller's looper.
 */
public final class EvalBridge {
    private static final String TAG = "EvalBridge";
    static final String JS_NAME = "__evalBridge";
    static final String WRAP_PREFIX = "javascript:(function(){/*evb*/";

    private static final WeakHashMap<WebView, Receiver> sReceivers =
            new WeakHashMap<WebView, Receiver>();

    private EvalBridge() {}

    /** Called from the WebView constructor after provider init. */
    static void attach(WebView view) {
        WebCompat.onCreate(view);
        try {
            // Only for apps where @JavascriptInterface is enforced (targetSdk >= 17),
            // so the receiver does not expose getClass() & co. to page script.
            if (view.getContext().getApplicationInfo().targetSdkVersion < 17) return;
            getReceiver(view, true);
        } catch (Throwable t) {
            Log.w(TAG, "attach failed", t);
        }
    }

    private static Receiver getReceiver(WebView view, boolean create) {
        synchronized (sReceivers) {
            Receiver r = sReceivers.get(view);
            if (r == null && create) {
                r = new Receiver(view);
                sReceivers.put(view, r);
                view.addJavascriptInterface(r, JS_NAME);
            }
            return r;
        }
    }

    /**
     * Called from WebView.destroy(): forget this WebView. Pending callbacks will never be answered
     * (the page is gone) and they usually capture the Activity, which in turn references the WebView
     * key — so leaving them in the static map would leak the whole viewer (WebView native memory too).
     */
    static void onDestroy(WebView view) {
        // Keep the (now inert) entry so later evaluateJavascript() calls on this dead WebView are
        // ignored instead of creating a fresh receiver; the destroyed receiver holds no callbacks and
        // no strong path back to the WebView key, so the WeakHashMap entry is still collectable.
        Receiver r;
        synchronized (sReceivers) {
            r = sReceivers.get(view);
            if (r == null) {
                r = new Receiver(view);
                sReceivers.put(view, r);
            }
        }
        r.destroy();
        maybeDumpHeap(view);
    }

    /**
     * Called from Instrumentation.callActivityOnDestroy (after performDestroy). Classic WebView keeps
     * the WebView and its Context alive from the WebViewCore thread until destroy() is called; apps
     * written for chromium WebView often never call it (chromium frees on detach/GC), so on API 17
     * every closed viewer Activity leaks with its whole page (~100MB for an image-heavy EPUB).
     * For WebCompat-configured packages, destroy the WebViews that belonged to this Activity.
     */
    public static void onActivityDestroyed(android.app.Activity a) {
        try {
            if (a == null || !WebCompat.enabled()) return;
            unleakThreadHeldContexts(a);
            final java.util.ArrayList<WebView> victims = new java.util.ArrayList<WebView>();
            synchronized (sReceivers) {
                if (WebCompat.logOn()) Log.i(TAG, "activity destroyed: " + a.getClass().getName() + ", tracked WebViews=" + sReceivers.size());
                for (java.util.Map.Entry<WebView, Receiver> e : sReceivers.entrySet()) {
                    WebView v = e.getKey();
                    if (WebCompat.logOn() && v != null) Log.i(TAG, "  WebView " + v.getClass().getName() + " ctx=" + v.getContext().getClass().getName()
                            + " destroyed=" + e.getValue().isDestroyed() + " attached=" + (v.getParent() != null));
                    if (v == null || e.getValue().isDestroyed()) continue;
                    android.content.Context c = v.getContext();
                    while (c instanceof android.content.ContextWrapper && c != a) {
                        android.content.Context b = ((android.content.ContextWrapper) c).getBaseContext();
                        if (b == null || b == c) break;
                        c = b;
                    }
                    if (c == a) victims.add(v);
                }
            }
            for (WebView v : victims) {
                Log.i(TAG, "auto-destroying WebView left alive by destroyed " + a.getClass().getName());
                try {
                    if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
                } catch (Throwable ignored) {}
                v.destroy();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onActivityDestroyed failed", t);
        }
    }

    /**
     * Leak fix for long-lived threads that keep a destroyed Activity: after an Activity is destroyed,
     * look at every live thread's Runnable target and its enclosing instances; any Context-typed
     * field still pointing at the destroyed Activity is re-pointed to the Application context.
     * (Millie's local DRM web server — NanoHTTPD "LocalServerAgent" — keeps its accept thread running
     * and holds the EPUB viewer Activity in mContext, pinning the whole closed viewer in memory.)
     * Enabled per package by /system/etc/webview_compat/<pkg>.unleak.
     */
    private static void unleakThreadHeldContexts(android.app.Activity a) {
        try {
            if (!new java.io.File("/system/etc/webview_compat/" + a.getPackageName() + ".unleak").exists()) return;
            android.content.Context app = a.getApplicationContext();
            if (app == null || app == a) return;
            java.lang.reflect.Field targetF = Thread.class.getDeclaredField("target");
            targetF.setAccessible(true);
            int fixed = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                Object o = targetF.get(t);
                if (o == null && !t.getClass().getName().startsWith("java.")) o = t;   // Thread subclass
                for (int depth = 0; o != null && depth < 3; depth++) {
                    Object outer = null;
                    for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        if (c.getName().startsWith("java.") || c.getName().startsWith("android.")) break;
                        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                            f.setAccessible(true);
                            Object v = f.get(o);
                            if (v == a && android.content.Context.class.isAssignableFrom(f.getType())) {
                                f.set(o, app);
                                fixed++;
                                Log.i(TAG, "unleak: " + c.getName() + "." + f.getName() + " (thread " + t.getName() + ") -> application context");
                            } else if (f.getName().startsWith("this$") && outer == null) {
                                outer = v;
                            }
                        }
                    }
                    o = outer;
                }
            }
            if (fixed == 0 && WebCompat.logOn()) Log.i(TAG, "unleak: nothing to fix for " + a.getClass().getName());
        } catch (Throwable e) {
            Log.w(TAG, "unleak failed", e);
        }
    }

    /** Debug aid: /system/etc/webview_compat/<pkg>.hprof present -> heap dump 10s after a WebView is destroyed. */
    private static boolean sDumped;
    private static void maybeDumpHeap(WebView view) {
        try {
            if (sDumped) return;
            String pkg = view.getContext().getPackageName();
            if (!new java.io.File("/system/etc/webview_compat/" + pkg + ".hprof").exists()) return;
            sDumped = true;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    try {
                        System.gc(); System.runFinalization(); System.gc();
                        android.os.Debug.dumpHprofData("/sdcard/webcompat_after_destroy.hprof");
                        Log.i(TAG, "heap dumped to /sdcard/webcompat_after_destroy.hprof");
                    } catch (Throwable t) {
                        Log.w(TAG, "heap dump failed", t);
                    }
                }
            }, 10000);
        } catch (Throwable t) {
            Log.w(TAG, "maybeDumpHeap failed", t);
        }
    }

    /** Body of WebView.evaluateJavascript(String, ValueCallback). */
    static void evaluate(WebView view, String script, ValueCallback<String> cb) {
        if (script == null) script = "";
        WebCompat.onEvaluate(script);
        Receiver r = (cb != null) ? getReceiver(view, true) : null;
        if (r != null && r.isDestroyed()) return;   // after destroy(): chromium never calls back either
        StringBuilder js = new StringBuilder(script.length() + 256);
        js.append(WRAP_PREFIX);
        if (r == null) {
            js.append("try{(0,eval)(").append(quote(script)).append(");}catch(e){}");
        } else {
            int id = r.register(cb);
            js.append("var b=window.").append(JS_NAME).append(",s;");
            js.append("try{var v=(0,eval)(").append(quote(script)).append(");");
            js.append("try{s=(v===undefined)?'null':JSON.stringify(v);}catch(e1){s=null;}");
            js.append("if(s===undefined||s===null)s='null';");
            js.append("}catch(e){s='null';}");
            js.append("if(b)b.onResult(").append(id).append(",s);");
        }
        js.append("})();void 0;");
        view.loadUrl(js.toString());
    }

    /** JS string literal; '%' and line separators escaped so the URL stays inert. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '%' || c == '<' || c == 0x2028 || c == 0x2029
                            || (c >= 0x7f && c <= 0x9f)) {
                        String h = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int k = h.length(); k < 4; k++) sb.append('0');
                        sb.append(h);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Exposed to page script as window.__evalBridge. */
    static final class Receiver {
        private final HashMap<Integer, Pending> mPending = new HashMap<Integer, Pending>();
        private int mNextId = 1;
        private final java.lang.ref.WeakReference<WebView> mView;
        private final Handler mMain = new Handler(Looper.getMainLooper());

        private volatile boolean mDestroyed;

        Receiver(WebView v) {
            mView = new java.lang.ref.WeakReference<WebView>(v);
        }

        void destroy() {
            mDestroyed = true;
            synchronized (mPending) {
                mPending.clear();
            }
            mView.clear();
        }

        boolean isDestroyed() {
            return mDestroyed;
        }

        /** Debug: native WebView state as a string (called from page script). */
        @JavascriptInterface
        public String info() {
            WebView v = mView.get();
            if (v == null) return "gone";
            try {
                return "scale=" + v.getScale() + " scroll=" + v.getScrollX() + "," + v.getScrollY()
                        + " size=" + v.getWidth() + "x" + v.getHeight()
                        + " contentH=" + v.getContentHeight()
                        + " layer=" + v.getLayerType() + " hw=" + v.isHardwareAccelerated()
                        + " viewScale=" + v.getScaleX() + "," + v.getScaleY()
                        + " tx=" + v.getTranslationX();
            } catch (Throwable t) {
                return "info err " + t;
            }
        }

        /** Debug: render WebView content (capturePicture) and the view itself (draw) to /sdcard PNGs. */
        @JavascriptInterface
        public void snap(final String tag) {
            mMain.post(new Runnable() {
                public void run() {
                    WebView v = mView.get();
                    if (v == null) return;
                    try {
                        android.graphics.Picture pic = v.capturePicture();
                        int w = Math.min(Math.max(pic.getWidth(), 1), 1600), h = Math.min(Math.max(pic.getHeight(), 1), 2400);
                        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas c = new android.graphics.Canvas(b);
                        c.drawColor(0xffffffff);
                        pic.draw(c);
                        save(b, "/sdcard/webcompat_pic_" + tag + ".png");
                        android.graphics.Bitmap b2 = android.graphics.Bitmap.createBitmap(Math.max(v.getWidth(), 1), Math.max(v.getHeight(), 1), android.graphics.Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas c2 = new android.graphics.Canvas(b2);
                        c2.drawColor(0xffffffff);
                        v.draw(c2);
                        save(b2, "/sdcard/webcompat_view_" + tag + ".png");
                        WebCompat.jsLog("snap " + tag + " pic=" + pic.getWidth() + "x" + pic.getHeight() + " " + info());
                    } catch (Throwable t) {
                        Log.w(TAG, "snap failed", t);
                    }
                }
            });
        }

        private static void save(android.graphics.Bitmap b, String path) throws java.io.IOException {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(path);
            try {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo);
            } finally {
                fo.close();
                b.recycle();
            }
        }

        private static final class Pending {
            final ValueCallback<String> cb;
            final Handler handler;
            Pending(ValueCallback<String> cb, Handler handler) {
                this.cb = cb;
                this.handler = handler;
            }
        }

        int register(ValueCallback<String> cb) {
            Looper l = Looper.myLooper();
            if (l == null) l = Looper.getMainLooper();
            synchronized (mPending) {
                int id = mNextId++;
                if (mNextId <= 0) mNextId = 1;
                mPending.put(Integer.valueOf(id), new Pending(cb, new Handler(l)));
                return id;
            }
        }

        /** Debug channel for injected compat scripts: window.__evalBridge.log(msg). */
        @JavascriptInterface
        public void log(String msg) {
            WebCompat.jsLog(msg);
        }

        @JavascriptInterface
        public void onResult(int id, String json) {
            WebCompat.onResult(id, json);
            final Pending p;
            synchronized (mPending) {
                p = mPending.remove(Integer.valueOf(id));
            }
            if (p == null) return;
            final String value = (json == null) ? "null" : json;
            // Called on the WebViewCore (JS) thread -> hop to the caller's looper.
            p.handler.post(new Runnable() {
                public void run() {
                    try {
                        p.cb.onReceiveValue(value);
                    } catch (Throwable t) {
                        Log.e(TAG, "callback threw", t);
                        if (t instanceof RuntimeException) throw (RuntimeException) t;
                        if (t instanceof Error) throw (Error) t;
                    }
                }
            });
        }
    }
}
