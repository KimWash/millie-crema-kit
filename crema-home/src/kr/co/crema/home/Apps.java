package kr.co.crema.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Launchable apps. Only labels are loaded: the design shows a letter tile per
 * app, so icon drawables (the expensive part) are never decoded.
 */
final class Apps {
    static final String MILLIE = "kr.co.millie.eink";
    /**
     * Our wrapper (launcher/ in this repo): starts Millie at LoginActivity because
     * Millie's own SplashActivity hangs on API 17, and waits until the network
     * really answers. Millie must always be opened through it when installed.
     */
    static final String MILLIE_LAUNCHER = "kr.co.millie.crema.launcher";
    static final String SETTINGS = "com.android.settings";

    static boolean isMillie(String pkg) {
        return MILLIE.equals(pkg) || MILLIE_LAUNCHER.equals(pkg);
    }

    static final class App {
        String label, letter, pkg;
        ComponentName cn;
    }

    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        ArrayList<App> out = new ArrayList<App>(ris.size());
        String self = c.getPackageName();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(self)) continue;
            App a = new App();
            CharSequence l = ri.loadLabel(pm);
            a.label = l == null ? pkg : l.toString().trim();
            a.pkg = pkg;
            a.cn = new ComponentName(pkg, ri.activityInfo.name);
            a.letter = letter(a.label);
            out.add(a);
        }
        // with the wrapper installed, the raw Millie entry would only open the hanging splash
        if (find(out, MILLIE_LAUNCHER) != null) {
            for (int k = out.size() - 1; k >= 0; k--) if (MILLIE.equals(out.get(k).pkg)) out.remove(k);
        }
        final Collator col = Collator.getInstance(Locale.KOREAN);
        Collections.sort(out, new Comparator<App>() {
            public int compare(App a, App b) {
                return col.compare(a.label, b.label);
            }
        });
        return out;
    }

    static String letter(String s) {
        if (s == null || s.length() == 0) return "?";
        int cp = s.codePointAt(0);
        return new String(Character.toChars(cp)).toUpperCase(Locale.US);
    }

    static App find(List<App> apps, String pkg) {
        if (apps != null) for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }
}
