package kr.co.crema.home;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Running user apps for the home double-tap panel. Only apps that have a
 * launcher entry are listed (no services, no system daemons), most recently
 * used first, each with its total PSS. Runs on the IO thread.
 */
final class Tasks {
    static final class Task {
        Apps.App app;   // what to show / launch
        String pkg;     // the package whose processes run (Millie runs as kr.co.millie.eink)
        int[] pids;
        int pssKb;
    }

    static final class Snapshot {
        final List<Task> tasks;
        final long availBytes, totalBytes;

        Snapshot(List<Task> t, long a, long total) {
            tasks = t;
            availBytes = a;
            totalBytes = total;
        }
    }

    @SuppressWarnings("deprecation")
    static Snapshot load(Context c, List<Apps.App> apps) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        String self = c.getPackageName();
        HashMap<String, Apps.App> launchable = new HashMap<String, Apps.App>();
        for (Apps.App a : apps) if (!launchable.containsKey(a.pkg)) launchable.put(a.pkg, a);
        // Millie's process belongs to kr.co.millie.eink, but it is listed/opened through the wrapper
        Apps.App wrapper = launchable.get(Apps.MILLIE_LAUNCHER);
        if (wrapper != null && !launchable.containsKey(Apps.MILLIE)) launchable.put(Apps.MILLIE, wrapper);
        launchable.remove(Apps.MILLIE_LAUNCHER); // the wrapper itself finishes at once; never list it

        // package -> pids
        HashMap<String, ArrayList<Integer>> pids = new HashMap<String, ArrayList<Integer>>();
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (p.pkgList == null) continue;
            for (String pkg : p.pkgList) {
                if (pkg.equals(self) || !launchable.containsKey(pkg)) continue;
                ArrayList<Integer> l = pids.get(pkg);
                if (l == null) pids.put(pkg, l = new ArrayList<Integer>());
                if (!l.contains(p.pid)) l.add(p.pid);
            }
        }

        // most recent first, then anything else that is running
        LinkedHashMap<String, Task> ordered = new LinkedHashMap<String, Task>();
        try {
            List<ActivityManager.RecentTaskInfo> recent = am.getRecentTasks(30, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            if (recent != null) for (ActivityManager.RecentTaskInfo r : recent) {
                ComponentName cn = r.baseIntent == null ? null : r.baseIntent.getComponent();
                String pkg = cn != null ? cn.getPackageName() : null;
                if (pkg != null && pids.containsKey(pkg) && !ordered.containsKey(pkg)) ordered.put(pkg, task(pkg, launchable.get(pkg), pids.get(pkg)));
            }
        } catch (SecurityException ignored) {
        }
        for (String pkg : pids.keySet())
            if (!ordered.containsKey(pkg)) ordered.put(pkg, task(pkg, launchable.get(pkg), pids.get(pkg)));

        ArrayList<Task> out = new ArrayList<Task>(ordered.values());
        // one binder call for every pid at once
        int n = 0;
        for (Task t : out) n += t.pids.length;
        int[] all = new int[n];
        int k = 0;
        for (Task t : out) for (int p : t.pids) all[k++] = p;
        if (n > 0) {
            Debug.MemoryInfo[] mi = am.getProcessMemoryInfo(all);
            k = 0;
            for (Task t : out) for (int i = 0; i < t.pids.length; i++, k++)
                if (mi != null && k < mi.length && mi[k] != null) t.pssKb += mi[k].getTotalPss();
        }
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(m);
        return new Snapshot(out, m.availMem, m.totalMem);
    }

    private static Task task(String pkg, Apps.App a, List<Integer> pids) {
        Task t = new Task();
        t.pkg = pkg;
        t.app = a;
        t.pids = new int[pids.size()];
        for (int i = 0; i < t.pids.length; i++) t.pids[i] = pids.get(i);
        return t;
    }

    /**
     * Close an app completely. A force-stop (through su; SuperSU asks once on
     * the device the first time) kills the processes AND finishes the app's
     * activities, so the next launch starts clean. killBackgroundProcesses
     * alone keeps the task record: the old screens are rebuilt on a fresh
     * process (Millie's book viewer then waits forever for a DRM session that
     * no longer exists). It is only the fallback when su is unavailable.
     */
    static void kill(Context c, String pkg) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        boolean forced = false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
            forced = p.waitFor() == 0;
        } catch (Exception ignored) {
        }
        if (!forced || running(am, pkg)) am.killBackgroundProcesses(pkg);
    }

    private static boolean running(ActivityManager am, String pkg) {
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs)
            if (p.pkgList != null) for (String s : p.pkgList) if (s.equals(pkg)) return true;
        return false;
    }
}
