package kr.co.millie.crema.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Starts Millie straight at LoginActivity (skips the SplashActivity that hangs on API 17).
 *
 * Before a cold start it waits until the internet is actually reachable. Millie's auto login runs once
 * in LoginActivity: if Android reports Wi-Fi as connected it sends the sign-in request, and if that
 * request fails it just shows the login form and never retries. On this device Wi-Fi is switched off
 * on every screen-off, and after waking it can report CONNECTED while no packets get through yet
 * (observed: ~9 min with a phone hotspot, "Unable to resolve host apis.millie.co.kr").
 * Without any network at all Millie logs in offline from its stored session, so we only wait when
 * Wi-Fi claims to be connected.
 */
public class Go extends Activity {
    private static final String PKG = "kr.co.millie.eink";
    private static final String HOST = "apis.millie.co.kr";
    private static final long WAIT_MS = 30000;     // give up waiting after this
    private static final long SHOW_UI_MS = 800;
    private static final long WIFI_CONNECT_MS = 10000; // Wi-Fi on but not connected: wait this long, then go offline

    private final Handler h = new Handler();
    private volatile boolean done;
    private long start;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (isRunning() || !wifiClaimsConnected()) {
            launch();
            return;
        }
        start = SystemClock.uptimeMillis();
        h.postDelayed(new Runnable() { public void run() { if (!done) showWaitingUi(); } }, SHOW_UI_MS);
        probeLoop();
    }

    private void probeLoop() {
        new Thread(new Runnable() {
            public void run() {
                // Phase 1: Wi-Fi is on but not connected yet (just woke up) -> give it up to 10 s to
                // connect. If it doesn't (no known AP in range), open Millie offline right away:
                // without a connection Millie logs in from its stored session.
                while (!done && !activeConnected()) {
                    if (SystemClock.uptimeMillis() - start > WIFI_CONNECT_MS) {
                        h.post(new Runnable() { public void run() { launch(); } });
                        return;
                    }
                    SystemClock.sleep(500);
                }
                // Phase 2: connected -> wait until the internet actually answers.
                while (!done) {
                    if (reachable()) {
                        h.post(new Runnable() { public void run() { launch(); } });
                        return;
                    }
                    if (SystemClock.uptimeMillis() - start > WAIT_MS) {
                        h.post(new Runnable() { public void run() { showGiveUp(); } });
                        return;
                    }
                    SystemClock.sleep(1000);
                    h.post(new Runnable() { public void run() { updateStatus(); } });
                }
            }
        }, "net-probe").start();
    }

    /** DNS lookup + TCP connect to Millie's API host, 3 s timeout. */
    private static boolean reachable() {
        Socket s = new Socket();
        try {
            InetAddress a = InetAddress.getByName(HOST);
            s.connect(new InetSocketAddress(a, 443), 3000);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * True when Millie may decide it is online: a network is connected now, or Wi-Fi is switched on and
     * may (re)connect while Millie is still starting (after screen-on this ROM reconnects ~10 s later,
     * i.e. during Millie's ~15 s start-up, and Millie then attempts an online login).
     */
    private boolean wifiClaimsConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) return true;
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean activeConnected() {
        try {
            NetworkInfo ni = ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps != null) for (ActivityManager.RunningAppProcessInfo p : ps) if (PKG.equals(p.processName)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    // ---- waiting screen (e-ink: white, high contrast, big touch targets, no animation) ----
    private TextView title, detail;
    private Button primary, secondary;
    private long lastShownSec = -1;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button bigButton(String text, boolean filled) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(22);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setMinHeight(dp(72));
        b.setPadding(dp(16), dp(18), dp(16), dp(18));
        int fg = filled ? Color.WHITE : Color.BLACK, bg = filled ? Color.BLACK : Color.WHITE;
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] {android.R.attr.state_pressed}, box(fg, bg));   // pressed: inverted
        sl.addState(new int[] {}, box(bg, Color.BLACK));
        b.setBackgroundDrawable(sl);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][] {{android.R.attr.state_pressed}, {}}, new int[] {bg, fg}));
        return b;
    }

    private android.graphics.drawable.GradientDrawable box(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(3), stroke);
        g.setCornerRadius(dp(14));
        return g;
    }

    private void showWaitingUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(32), dp(48), dp(32), dp(40));

        int w = getResources().getDisplayMetrics().widthPixels;
        android.widget.ImageView logo = new android.widget.ImageView(this);
        setLogo(logo, "millie_logo", "밀리의 서재", 30);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(w * 36 / 100, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = dp(16);
        root.addView(logo, llp);
        android.widget.ImageView name = new android.widget.ImageView(this);
        setLogo(name, "millie_name", "독서와 무제한 친해지리", 18);
        name.setAdjustViewBounds(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(w * 56 / 100, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.gravity = Gravity.CENTER_HORIZONTAL;
        nlp.topMargin = dp(18);
        root.addView(name, nlp);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(26);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        detail = new TextView(this);
        detail.setTextColor(0xFF333333);
        detail.setTextSize(19);
        detail.setLineSpacing(0, 1.25f);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(16);
        root.addView(detail, dlp);

        View spacer2 = new View(this);
        root.addView(spacer2, new LinearLayout.LayoutParams(1, 0, 1f));

        primary = bigButton("다시 시도", true);
        primary.setVisibility(View.GONE);
        primary.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                primary.setVisibility(View.GONE);
                start = SystemClock.uptimeMillis();
                lastShownSec = -1;
                updateStatus();
                probeLoop();
            }
        });
        root.addView(primary, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        secondary = bigButton("기다리지 않고 열기", false);
        secondary.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { launch(); } });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(16);
        root.addView(secondary, slp);
        setContentView(root);
        updateStatus();
    }

    private void updateStatus() {
        if (title == null || done) return;
        long s = (SystemClock.uptimeMillis() - start) / 1000;
        long shown = s / 5 * 5;               // e-ink: refresh the text only every 5 s (or on state change)
        boolean conn = activeConnected();
        long key = conn ? shown + 1000000 : shown;
        if (key == lastShownSec) return;
        lastShownSec = key;
        if (conn) {
            title.setText("인터넷 연결 확인 중");
            detail.setText("Wi-Fi는 연결됐지만 아직 인터넷이 되지 않습니다.\n연결되면 자동으로 열립니다." + (shown > 0 ? "\n(" + shown + "초 경과)" : ""));
        } else {
            title.setText("Wi-Fi 연결 중");
            detail.setText("10초 안에 연결되지 않으면\n오프라인으로 엽니다.");
        }
    }

    private void showGiveUp() {
        if (done) return;
        if (title == null) showWaitingUi();
        title.setText("인터넷에 연결되지 않았습니다");
        detail.setText("핫스팟이나 공유기를 확인한 뒤\n다시 시도해 주세요.\n\n지금 열면 자동 로그인 대신\n로그인 화면이 나옵니다.");
        primary.setVisibility(View.VISIBLE);
        secondary.setText("그래도 열기");
    }

    private void launch() {
        if (done) return;
        done = true;
        boolean running = isRunning();
        if (!running) {
            try { startService(new Intent(this, Splash.class)); } catch (Throwable ignored) {}
        }
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName(PKG, PKG + ".user.LoginActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Process gone (closed, or killed for memory) but Android may still hold its task: the
        // old stack (e.g. LoginActivity -> EPubViewActivity) would be rebuilt on top of a fresh
        // process whose DRM session no longer exists, leaving the viewer loading forever.
        // Start a clean task instead; while Millie is running we keep resuming where it was.
        if (!running) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "밀리의 서재를 열 수 없습니다: " + t, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        done = true;
        super.onDestroy();
    }

    /**
     * Logo images are not part of the repository (they belong to Millie); scripts/build_launcher.sh extracts
     * them from the user's own Millie APK. Without them a plain text title is drawn instead.
     */
    static void setLogo(android.widget.ImageView v, String res, String fallback, int sp) {
        android.content.Context c = v.getContext();
        int id = c.getResources().getIdentifier(res, "drawable", c.getPackageName());
        if (id != 0) {
            v.setImageResource(id);
            return;
        }
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF222222);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
        android.graphics.Rect b = new android.graphics.Rect();
        p.getTextBounds(fallback, 0, fallback.length(), b);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(b.width() + 8, b.height() + 8, android.graphics.Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(bm).drawText(fallback, 4 - b.left, 4 - b.top, p);
        v.setImageBitmap(bm);
        v.setScaleType(android.widget.ImageView.ScaleType.CENTER);
    }
}
