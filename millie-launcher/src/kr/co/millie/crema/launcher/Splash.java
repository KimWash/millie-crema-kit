package kr.co.millie.crema.launcher;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen Millie logo shown on top while Millie cold-starts (~15 s on this device, during which Android
 * would only show Millie's blank starting window).
 *
 * Removed when the framework (AppWarmup.signalShown, config /system/etc/webview_compat/kr.co.millie.eink.shownsignal)
 * reports that a Millie activity has drawn its first frame:
 *  - the bookshelf (auto login worked) or any other screen -> remove now
 *  - the login screen -> auto login is usually done 1-3 s later; remove after LOGIN_GRACE_MS if nothing follows
 *    (auto login failed and the login form is what the user needs to see)
 * Always removed after MAX_MS, or when tapped.
 */
public class Splash extends Service {
    static final String ACTION_SHOWN = "kr.co.millie.crema.launcher.APP_SHOWN";
    private static final long MAX_MS = 45000;
    private static final long LOGIN_GRACE_MS = 8000;

    private final Handler h = new Handler();
    private View overlay;
    private final Runnable remove = new Runnable() { public void run() { hide(); } };

    private final BroadcastReceiver shown = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String act = String.valueOf(i.getStringExtra("activity"));
            if (act.endsWith(".LoginActivity")) {
                h.removeCallbacks(remove);
                h.postDelayed(remove, LOGIN_GRACE_MS);
            } else {
                hide();
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (overlay == null) show();
        return START_NOT_STICKY;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void show() {
        int w = getResources().getDisplayMetrics().widthPixels;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.WHITE);

        ImageView logo = new ImageView(this);
        Go.setLogo(logo, "millie_logo", "밀리의 서재", 30);
        logo.setAdjustViewBounds(true);
        root.addView(logo, new LinearLayout.LayoutParams(w * 45 / 100, LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView name = new ImageView(this);
        Go.setLogo(name, "millie_name", "독서와 무제한 친해지리", 18);
        name.setAdjustViewBounds(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(w * 72 / 100, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(28);
        root.addView(name, nlp);

        TextView hint = new TextView(this);
        hint.setText("불러오는 중…");
        hint.setTextColor(0xFF555555);
        hint.setTextSize(18);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(56);
        root.addView(hint, hlp);

        root.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hide(); } });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(root, lp);
            overlay = root;
            registerReceiver(shown, new IntentFilter(ACTION_SHOWN));
            h.postDelayed(remove, MAX_MS);
        } catch (Throwable t) {
            stopSelf();
        }
    }

    private void hide() {
        h.removeCallbacks(remove);
        if (overlay != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay); } catch (Throwable ignored) {}
            try { unregisterReceiver(shown); } catch (Throwable ignored) {}
            overlay = null;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
