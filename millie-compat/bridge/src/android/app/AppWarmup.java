package android.app;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;

/**
 * Per-package background warm-up of kotlin-reflect, for apps that hit kotlin-reflect's very slow
 * first-use initialization (builtins metadata deserialization) on the main thread — e.g. Jackson
 * KotlinModule parsing JSON in a UI callback. On a slow API 17 CPU that first use can block the
 * main thread for over a minute (ANR); doing it early on a background thread fills kotlin-reflect's
 * global caches so the main thread later finds them warm.
 *
 * Config: /system/etc/webview_compat/<pkg>.warm — one entry per line:
 *   fully.qualified.ClassName      warm that class
 *   some.package.prefix.           (trailing dot) warm every class in the app dex with that prefix
 *   # comment
 *   @activity fully.qualified.ActivityClass
 *             run synchronously on the main thread when that activity is created (instead of at
 *             application start). Use for apps that juggle their own dex/resource files during
 *             startup (packers): by then the files are stable.
 *   @sync     run on the calling (main) thread before Application.onCreate instead of a background
 *             thread. kotlin-reflect's first-time init is NOT safe to race with the main thread
 *             (can fail with "Built-in class kotlin.Any is not found"), so @sync is the safe mode.
 * Started from Instrumentation.callApplicationOnCreate (after attachBaseContext, so packed/
 * protected dex loaders have already installed the app classes).
 */
final class AppWarmup {
    private static final String TAG = "AppWarmup";
    private static final String DIR = "/system/etc/webview_compat/";
    private static boolean sStarted;
    private static String sActivityTrigger;   // set when config says "@activity X"
    private static ArrayList<String> sLines;
    private static boolean sActivityDone;
    private static String sIdleTrigger;       // "@idle X [ms]": warm on the main thread when idle, ms after X is created
    private static long sIdleDelay;
    private static boolean sIdleScheduled;

    private AppWarmup() {}

    /**
     * Debug aid: /system/etc/webview_compat/<pkg>.setstatic — lines "fully.qualified.Class field value"
     * (value: true/false/integer/string). Sets an app static field after Application.onCreate and on each
     * Activity creation (idempotent). Used to switch on an app's own release-disabled logger
     * (e.g. Millie's ZLog.LOG_MODE) without touching the APK. Remove the file to disable.
     */
    private static void applyStatics(android.content.Context c) {
        try {
            if (c == null) return;
            File cfg = new File(DIR + c.getPackageName() + ".setstatic");
            if (!cfg.isFile()) return;
            ClassLoader cl = c.getClassLoader();
            for (String l : readLines(cfg)) {
                String[] p = l.split("\\s+", 3);
                if (p.length < 3) continue;
                try {
                    // p[1] may be a path: staticField.field.field ; the last element is set, or, when
                    // the value is "()", invoked as a no-arg method. A null link skips the line (e.g. a
                    // lazily created singleton that does not exist yet; retried on later triggers).
                    String[] path = p[1].split("\\.");
                    Class<?> k = Class.forName(p[0], true, cl);
                    Object target = null;
                    for (int i = 0; i < path.length - 1; i++) {
                        Field f = findField(k, path[i]);
                        target = f.get(target);
                        if (target == null) break;
                        k = target.getClass();
                    }
                    if (path.length > 1 && target == null) continue;
                    String last = path[path.length - 1];
                    if (p[2].equals("()")) {
                        Method m = findMethod(k, last);
                        m.invoke(target);
                        Log.i(TAG, "setstatic " + p[0] + "." + p[1] + "()");
                        continue;
                    }
                    Field f = findField(k, last);
                    Class<?> t = f.getType();
                    Object v = t == boolean.class ? (Object) Boolean.valueOf(p[2])
                            : t == int.class ? (Object) Integer.valueOf(p[2])
                            : t == long.class ? (Object) Long.valueOf(p[2]) : p[2];
                    if (!v.equals(f.get(target))) {
                        f.set(target, v);
                        Log.i(TAG, "setstatic " + p[0] + "." + p[1] + " = " + v);
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "setstatic failed: " + l + ": " + e);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "setstatic failed", e);
        }
    }

    private static Field findField(Class<?> k, String name) throws NoSuchFieldException {
        for (Class<?> c = k; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(k.getName() + "." + name);
    }

    private static Method findMethod(Class<?> k, String name) throws NoSuchMethodException {
        for (Class<?> c = k; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(k.getName() + "." + name);
    }

    /** applyStatics now, and again a little later (lazily created singletons such as Glide's). */
    private static void applyStaticsSoon(android.content.Context ctx) {
        applyStatics(ctx);
        try {
            if (ctx == null || !new File(DIR + ctx.getPackageName() + ".setstatic").isFile()) return;
            android.content.Context app = ctx.getApplicationContext();
            final android.content.Context c = app != null ? app : ctx;   // don't pin the Activity
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable r = new Runnable() { public void run() { applyStatics(c); } };
            for (long d : new long[] {500, 1500, 3000, 5000, 20000}) h.postDelayed(r, d);
        } catch (Throwable ignored) {}
    }

    static void onApplicationCreate(final Application app) {
        applyStatics(app);
        try {
            if (sStarted || app == null) return;
            String pkg = app.getPackageName();
            if (pkg == null) return;
            final File cfg = new File(DIR + pkg + ".warm");
            if (!cfg.isFile()) return;
            sStarted = true;
            final ClassLoader cl = app.getClassLoader();
            final ArrayList<String> lines = readLines(cfg);
            boolean deferred = false;
            for (String l : lines) {
                if (l.startsWith("@activity ")) {
                    sActivityTrigger = l.substring(10).trim();
                    deferred = true;
                } else if (l.startsWith("@idle ")) {
                    String[] p = l.substring(6).trim().split("\\s+");
                    sIdleTrigger = p[0];
                    sIdleDelay = p.length > 1 ? Long.parseLong(p[1]) : 5000;
                    deferred = true;
                }
            }
            if (deferred) {
                sLines = lines;
                return;   // deferred to onActivityCreate
            }
            if (lines.contains("@sync")) {
                warm(cl, lines);
                return;
            }
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                        warm(cl, lines);
                    } catch (Throwable e) {
                        Log.w(TAG, "warm-up failed", e);
                    }
                }
            }, "AppWarmup");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            Log.w(TAG, "start failed", e);
        }
    }

    private static String sShownTarget;      // "" = no config, null = not read yet

    /**
     * /system/etc/webview_compat/<pkg>.shownsignal — one line: the package to notify. After each Activity of
     * this app draws its first frame, an explicit broadcast "<target>.APP_SHOWN" (extras "pkg", "activity")
     * is sent to that package. Lets a launcher keep a splash overlay up exactly until the app is visible
     * (Android's own starting window shows a blank screen during a long cold start).
     */
    private static void signalShown(final Activity a) {
        try {
            if (a == null) return;
            if (sShownTarget == null) {
                File f = new File(DIR + a.getPackageName() + ".shownsignal");
                ArrayList<String> l = f.isFile() ? readLines(f) : new ArrayList<String>();
                sShownTarget = l.isEmpty() ? "" : l.get(0).trim();
            }
            if (sShownTarget.length() == 0) return;
            final String target = sShownTarget;
            final String pkg = a.getPackageName();
            final String act = a.getClass().getName();
            final android.content.Context app = a.getApplicationContext();
            // Posted from inside the launch message: ViewRootImpl's first traversal (sync barrier) runs before it,
            // so this executes after the first frame has been drawn.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        android.content.Intent i = new android.content.Intent(target + ".APP_SHOWN");
                        i.setPackage(target);
                        i.putExtra("pkg", pkg);
                        i.putExtra("activity", act);
                        app.sendBroadcast(i);
                    } catch (Throwable e) {
                        Log.w(TAG, "shown signal failed: " + e);
                    }
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "shown signal failed", e);
        }
    }

    /** Called from Instrumentation.callActivityOnCreate (main thread). */
    static void onActivityCreate(Activity a) {
        applyStaticsSoon(a);
        signalShown(a);
        try {
            if (a != null && !sActivityDone && sIdleTrigger != null && !sIdleScheduled
                    && sIdleTrigger.equals(a.getClass().getName())) {
                sIdleScheduled = true;
                final ClassLoader cl = a.getClassLoader();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        android.os.Looper.myQueue().addIdleHandler(new android.os.MessageQueue.IdleHandler() {
                            public boolean queueIdle() {
                                if (sActivityDone) return false;
                                sActivityDone = true;
                                long t0 = SystemClock.uptimeMillis();
                                try {
                                    warm(cl, sLines);
                                } catch (Throwable e) {
                                    Log.w(TAG, "idle warm-up failed", e);
                                }
                                Log.i(TAG, "idle warm-up after " + sIdleTrigger + " took " + (SystemClock.uptimeMillis() - t0) + "ms");
                                return false;
                            }
                        });
                    }
                }, sIdleDelay);
            }
            if (sActivityTrigger == null || sActivityDone || a == null) return;
            if (!sActivityTrigger.equals(a.getClass().getName())) return;
            sActivityDone = true;
            long t0 = SystemClock.uptimeMillis();
            warm(a.getClassLoader(), sLines);
            Log.i(TAG, "activity-triggered warm-up for " + sActivityTrigger + " took " + (SystemClock.uptimeMillis() - t0) + "ms");
        } catch (Throwable e) {
            Log.w(TAG, "activity warm-up failed", e);
        }
    }

    private static ArrayList<String> readLines(File f) throws java.io.IOException {
        ArrayList<String> out = new ArrayList<String>();
        BufferedReader r = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && !line.startsWith("#")) out.add(line);
            }
        } finally {
            r.close();
        }
        return out;
    }

    private static void warm(ClassLoader cl, ArrayList<String> entries) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        Class<?> reflection = Class.forName("kotlin.jvm.internal.Reflection", true, cl);
        Method getKClass = reflection.getMethod("getOrCreateKotlinClass", Class.class);
        Class<?> kClassItf = Class.forName("kotlin.reflect.KClass", true, cl);
        Class<?> kClasses = Class.forName("kotlin.reflect.full.KClasses", true, cl);
        Method declaredMemberProps = kClasses.getMethod("getDeclaredMemberProperties", kClassItf);
        Method memberProps = kClasses.getMethod("getMemberProperties", kClassItf);
        Method getConstructors = kClassItf.getMethod("getConstructors");
        Class<?> kCallable = Class.forName("kotlin.reflect.KCallable", true, cl);
        Method getParameters = kCallable.getMethod("getParameters");
        Method getReturnType = kCallable.getMethod("getReturnType");

        ArrayList<String> names = new ArrayList<String>();
        ArrayList<String> prefixes = new ArrayList<String>();
        for (String e : entries) {
            if (e.startsWith("@")) continue;
            if (e.endsWith(".")) prefixes.add(e); else names.add(e);
        }
        if (!prefixes.isEmpty()) names.addAll(listClasses(cl, prefixes));
        names = expandByFieldTypes(cl, names);

        int ok = 0, skipped = 0;
        long tFirst = -1;
        for (String n : names) {
            if (n.indexOf('$') >= 0 && (n.endsWith("$Companion") || n.matches(".*\\$\\d+$"))) {
                skipped++;
                continue;
            }
            try {
                Class<?> c = Class.forName(n, false, cl);
                if (c.isInterface() || c.isAnnotation() || c.isSynthetic()) {
                    skipped++;
                    continue;
                }
                Object kc = getKClass.invoke(null, c);
                touch(declaredMemberProps.invoke(null, kc), getReturnType);
                touch(memberProps.invoke(null, kc), getReturnType);
                Object ctors = getConstructors.invoke(kc);
                if (ctors instanceof Collection) {
                    for (Object k : (Collection<?>) ctors) {
                        Object ps = getParameters.invoke(k);
                        if (ps instanceof Collection) ((Collection<?>) ps).size();
                    }
                }
                ok++;
                if (tFirst < 0) tFirst = SystemClock.uptimeMillis() - t0;
            } catch (Throwable e) {
                skipped++;
            }
        }
        Log.i(TAG, "kotlin-reflect warm-up done: " + ok + " classes (" + skipped + " skipped), first="
                + tFirst + "ms total=" + (SystemClock.uptimeMillis() - t0) + "ms");
    }

    /**
     * Jackson introspects not only the root class but every bean type reachable through its
     * properties (incl. List<T>/Map<K,V> element types). Follow declared field types within the same
     * top-level package as the roots (depth <= 4, <= 60 classes) so those get warmed too.
     */
    private static ArrayList<String> expandByFieldTypes(ClassLoader cl, ArrayList<String> roots) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>(roots);
        java.util.ArrayList<String> frontier = new java.util.ArrayList<String>(roots);
        java.util.HashSet<String> pkgs = new java.util.HashSet<String>();
        for (String r : roots) {
            String[] parts = r.split("\\.");
            if (parts.length >= 3) pkgs.add(parts[0] + "." + parts[1] + "." + parts[2] + ".");
        }
        for (int depth = 0; depth < 4 && !frontier.isEmpty() && seen.size() < 60; depth++) {
            java.util.ArrayList<String> next = new java.util.ArrayList<String>();
            for (String n : frontier) {
                try {
                    Class<?> c = Class.forName(n, false, cl);
                    java.util.ArrayList<java.lang.reflect.Type> types = new java.util.ArrayList<java.lang.reflect.Type>();
                    for (Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        types.add(f.getGenericType());
                    }
                    if (c.getSuperclass() != null) types.add(c.getSuperclass());
                    while (!types.isEmpty()) {
                        java.lang.reflect.Type t = types.remove(types.size() - 1);
                        if (t instanceof Class) {
                            Class<?> k = (Class<?>) t;
                            while (k.isArray()) k = k.getComponentType();
                            String kn = k.getName();
                            boolean inPkg = false;
                            for (String p : pkgs) if (kn.startsWith(p)) inPkg = true;
                            if (inPkg && !k.isEnum() && !k.isInterface() && seen.size() < 60 && seen.add(kn)) next.add(kn);
                        } else if (t instanceof java.lang.reflect.ParameterizedType) {
                            for (java.lang.reflect.Type a : ((java.lang.reflect.ParameterizedType) t).getActualTypeArguments()) types.add(a);
                            types.add(((java.lang.reflect.ParameterizedType) t).getRawType());
                        } else if (t instanceof java.lang.reflect.GenericArrayType) {
                            types.add(((java.lang.reflect.GenericArrayType) t).getGenericComponentType());
                        } else if (t instanceof java.lang.reflect.WildcardType) {
                            for (java.lang.reflect.Type a : ((java.lang.reflect.WildcardType) t).getUpperBounds()) types.add(a);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            frontier = next;
        }
        Log.i(TAG, "warm set: " + seen);
        return new ArrayList<String>(seen);
    }

    private static void touch(Object props, Method getReturnType) throws Exception {
        if (!(props instanceof Collection)) return;
        for (Object p : (Collection<?>) props) getReturnType.invoke(p);
    }

    /** Enumerate class names in the loader's dex elements (BaseDexClassLoader.pathList.dexElements[].dexFile). */
    private static ArrayList<String> listClasses(ClassLoader cl, ArrayList<String> prefixes) {
        ArrayList<String> out = new ArrayList<String>();
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            Field pathListF = base.getDeclaredField("pathList");
            pathListF.setAccessible(true);
            Object pathList = pathListF.get(cl);
            Field elementsF = pathList.getClass().getDeclaredField("dexElements");
            elementsF.setAccessible(true);
            Object elements = elementsF.get(pathList);
            int n = Array.getLength(elements);
            for (int i = 0; i < n; i++) {
                Object el = Array.get(elements, i);
                Field dexF = el.getClass().getDeclaredField("dexFile");
                dexF.setAccessible(true);
                Object dex = dexF.get(el);
                if (dex == null) continue;
                Enumeration<?> en = (Enumeration<?>) dex.getClass().getMethod("entries").invoke(dex);
                while (en.hasMoreElements()) {
                    String name = (String) en.nextElement();
                    for (String p : prefixes) {
                        if (name.startsWith(p)) {
                            out.add(name);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "class enumeration failed: " + e);
        }
        return out;
    }
}
