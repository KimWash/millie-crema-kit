package kr.co.crema.home;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Method;

/** Battery, Wi-Fi, front light, storage, and the vendor e-ink refresh API. */
final class Device {
    private static final String BL = "/sys/class/backlight/rk28_bl/brightness";
    static final int LIGHT_MAX = 160;
    /** Five visible steps mapped onto the 0..160 range. */
    private static final int[] STEPS = {16, 48, 80, 120, 160};

    private final Context ctx;
    private Object controller; // android.hardware.DeviceController, if present
    private boolean controllerTried;

    int battery = -1;
    boolean charging;
    int wifiState; // 0 off, 1 on/not connected, 2 connected
    String ssid;
    boolean lightOn;
    int lightLevel; // 1..5 (last used level even when off)

    Device(Context c) {
        ctx = c;
    }

    /** Returns true when anything visible in the status bar changed. */
    boolean readBattery(Intent bat) {
        if (bat == null) bat = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat == null) return false;
        int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (scale > 0 && lvl >= 0) lvl = lvl * 100 / scale;
        boolean ch = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        boolean changed = lvl != battery || ch != charging;
        battery = lvl;
        charging = ch;
        return changed;
    }

    boolean readWifi() {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        int st = 0;
        String name = null;
        if (wm != null && wm.isWifiEnabled()) {
            st = 1;
            NetworkInfo ni = cm == null ? null : cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (ni != null && ni.isConnected()) {
                st = 2;
                WifiInfo wi = wm.getConnectionInfo();
                name = wi == null ? null : wi.getSSID();
                if (name != null) {
                    name = name.replace("\"", "");
                    if (name.equals("0x") || name.startsWith("<")) name = null;
                }
            }
        }
        boolean changed = st != wifiState || !eq(name, ssid);
        wifiState = st;
        ssid = name;
        return changed;
    }

    void setWifi(boolean on) {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) wm.setWifiEnabled(on);
    }

    // ------------------------------------------------------------ front light

    private Object controller() {
        if (!controllerTried) {
            controllerTried = true;
            try {
                controller = Class.forName("android.hardware.DeviceController")
                        .getConstructor(Context.class).newInstance(ctx);
            } catch (Throwable t) {
                controller = null;
            }
        }
        return controller;
    }

    private boolean call(String name, Object... args) {
        Object dc = controller();
        if (dc == null) return false;
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++)
                types[i] = args[i] instanceof Integer ? int.class : Context.class;
            Method m = dc.getClass().getMethod(name, types);
            m.invoke(dc, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    boolean readLight() {
        int raw = readSys();
        int saved = Settings.System.getInt(ctx.getContentResolver(), "screen_brightness", 80);
        boolean on = raw > 0;
        int lvl = levelOf(on ? raw : saved);
        boolean changed = on != lightOn || lvl != lightLevel;
        lightOn = on;
        lightLevel = lvl;
        return changed;
    }

    void setLightOn(boolean on) {
        if (on) {
            int v = STEPS[Math.max(1, lightLevel) - 1];
            if (!call("openFrontLight", ctx)) {
                Settings.System.putInt(ctx.getContentResolver(), "brightness_state", 1);
            }
            if (!call("setFrontLightValue", ctx, v)) writeSys(v);
        } else {
            if (!call("closeFrontLight", ctx)) {
                writeSys(0);
                Settings.System.putInt(ctx.getContentResolver(), "brightness_state", 0);
            }
        }
        lightOn = on;
    }

    void setLightLevel(int lvl) {
        lvl = Math.max(1, Math.min(5, lvl));
        lightLevel = lvl;
        int v = STEPS[lvl - 1];
        if (!lightOn) setLightOn(true);
        if (!call("setFrontLightValue", ctx, v)) writeSys(v);
        if (!call("setFrontLightConfigValue", ctx, v))
            Settings.System.putInt(ctx.getContentResolver(), "screen_brightness", v);
    }

    private static int levelOf(int v) {
        for (int i = 0; i < STEPS.length; i++) if (v <= STEPS[i]) return i + 1;
        return 5;
    }

    private static int readSys() {
        try {
            BufferedReader br = new BufferedReader(new FileReader(BL), 64);
            String s = br.readLine();
            br.close();
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeSys(int v) {
        try {
            FileOutputStream f = new FileOutputStream(BL);
            f.write(Integer.toString(v).getBytes());
            f.close();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ storage

    /** {usedBytes, totalBytes} of the shared storage. */
    static long[] storage() {
        try {
            StatFs s = new StatFs(Environment.getExternalStorageDirectory().getPath());
            long bs = s.getBlockSize();
            long total = bs * s.getBlockCount(), free = bs * s.getAvailableBlocks();
            return new long[]{total - free, total};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    // ------------------------------------------------------------ e-ink

    /**
     * Vendor EPD hooks found in this firmware's android.view.View:
     * fullRefresh() and requestEpdMode(View$EINK_MODE). Absent on other builds,
     * so every call is reflective and silently ignored when missing.
     */
    static final class Epd {
        private static Method full, mode;
        private static Class<?> modeEnum;
        private static boolean tried;

        private static void init() {
            if (tried) return;
            tried = true;
            try {
                full = View.class.getMethod("fullRefresh");
            } catch (Throwable ignored) {
            }
            try {
                modeEnum = Class.forName("android.view.View$EINK_MODE");
                mode = View.class.getMethod("requestEpdMode", modeEnum);
            } catch (Throwable ignored) {
            }
        }

        static void full(View v) {
            init();
            try {
                if (full != null) full.invoke(v);
            } catch (Throwable ignored) {
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static void mode(View v, String name) {
            init();
            try {
                if (mode != null) mode.invoke(v, Enum.valueOf((Class) modeEnum, name));
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
