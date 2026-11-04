package kr.co.crema.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.EditText;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Home activity. Design rules for this 512MB / single-core e-ink device:
 * - one View, software rendering, no animations or transitions;
 * - nothing polls: the clock follows ACTION_TIME_TICK, battery/Wi-Fi follow
 *   their broadcasts, and every receiver is dropped while we are not visible;
 * - all file / database / network work runs on one low-priority thread and
 *   the screen is repainted only when the data actually changed.
 */
public class Home extends Activity implements Covers.Listener {
    // state read by Screen
    int tab, sub, libPage, appPage;
    List<Koreader.Book> books = Collections.emptyList();
    Koreader.Stats stats;
    List<Apps.App> apps = Collections.emptyList();
    List<Apps.App> homeApps = Collections.emptyList();
    Bitmap sleepThumb;
    String sleepName;
    long[] storage;
    String version = "0.1";
    Device dev;
    Feeds feeds;
    Covers covers;

    private Ui ui;
    private Screen screen;
    private Koreader ko;
    private final Kyobo kyobo = new Kyobo();
    private Millie millie;
    private Recency recency;
    private Handler io, main;
    private HandlerThread ioThread;
    private boolean appsDirty = true, resumed;
    private String dataSig = "";
    private long sleepMtime = -1;
    private int pageFlips;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(null); // Screen paints every pixel; skip the window fill
        getWindow().setWindowAnimations(0);
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        main = new Handler();
        ioThread = new HandlerThread("io", Process.THREAD_PRIORITY_BACKGROUND);
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        ui = new Ui(this);
        dev = new Device(this);
        ko = new Koreader(getCacheDir());
        millie = new Millie(getCacheDir());
        recency = new Recency(this);
        feeds = new Feeds(getSharedPreferences("home", MODE_PRIVATE), getCacheDir());
        loadHomePkgs();
        covers = new Covers(getCacheDir(), io, main, this);
        screen = new Screen(this, this, ui);
        setContentView(screen);
        lastHomeAt = android.os.SystemClock.uptimeMillis(); // a cold start can be the first of two presses

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        ioThread.quit();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        dev.readBattery(null);
        dev.readWifi();
        dev.readLight();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(live, f); // sticky BATTERY_CHANGED arrives at once
        // coming back from a reader/app: one full-waveform refresh clears its ghosting
        screen.requestFullRefresh();
        reload();
    }

    @Override
    protected void onPause() {
        resumed = false;
        unregisterReceiver(live);
        super.onPause();
    }

    /*
     * The front key is mapped to HOME, and every press delivers a HOME intent
     * here (measured: a double press arrives ~200ms apart). A second press
     * within DOUBLE_TAP_MS opens the running-apps panel; a single press keeps
     * its normal meaning with no added delay.
     */
    private static final long DOUBLE_TAP_MS = 700;
    private long lastHomeAt;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHomeAt < DOUBLE_TAP_MS && !tasksOpen) {
            lastHomeAt = 0;
            openTasks();
            return;
        }
        lastHomeAt = now;
        if (tab != Screen.TAB_HOME || sub != 0 || tasksOpen) {
            tab = Screen.TAB_HOME;
            sub = 0;
            tasksOpen = false;
            redraw(true);
        }
    }

    // ================================================================ running apps

    boolean tasksOpen;
    Tasks.Snapshot tasks;
    final java.util.HashSet<String> closing = new java.util.HashSet<String>();

    private void openTasks() {
        tasksOpen = true;
        tasks = null; // "불러오는 중" until the IO thread answers
        redraw(true);
        loadTasks();
    }

    private void loadTasks() {
        final List<Apps.App> a = apps;
        io.post(new Runnable() {
            public void run() {
                final Tasks.Snapshot s = Tasks.load(Home.this, a);
                main.post(new Runnable() {
                    public void run() {
                        tasks = s;
                        if (tasksOpen) screen.invalidate();
                    }
                });
            }
        });
    }

    private void closeApps(final List<String> pkgs) {
        closing.addAll(pkgs);
        screen.invalidate();
        new Thread(new Runnable() { // su may wait for the SuperSU prompt; keep the IO thread free
            public void run() {
                for (String p : pkgs) Tasks.kill(Home.this, p);
                main.post(new Runnable() {
                    public void run() {
                        closing.removeAll(pkgs);
                        loadTasks();
                    }
                });
            }
        }, "close").start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (tasksOpen) {
                tasksOpen = false;
                redraw(true);
            } else if (sub != 0) {
                sub = 0;
                redraw(true);
            } else if (tab != Screen.TAB_HOME) {
                tab = Screen.TAB_HOME;
                redraw(true);
            }
            return true; // a launcher never finishes on Back
        }
        return super.onKeyDown(keyCode, event);
    }

    // ================================================================ data

    private final BroadcastReceiver live = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            boolean status = false;
            if (Intent.ACTION_BATTERY_CHANGED.equals(a)) status = dev.readBattery(i);
            else if (Intent.ACTION_TIME_TICK.equals(a) || Intent.ACTION_TIME_CHANGED.equals(a)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
                status = true;
                if (Intent.ACTION_TIME_TICK.equals(a) && tab == Screen.TAB_HOME) maybeRefreshStats();
            } else if (Intent.ACTION_DATE_CHANGED.equals(a)) reload();
            else {
                int before = dev.wifiState;
                if (dev.readWifi()) {
                    status = true;
                    if (tab == Screen.TAB_SETTINGS && sub == 0) screen.invalidate();
                }
                if (before != 2 && dev.wifiState == 2) fetchFeeds(false);
            }
            if (status) screen.invalidateStatus();
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (resumed) reload();
        }
    };

    private int statsTicks;

    private void maybeRefreshStats() {
        if (++statsTicks % 10 == 0) reload(); // reading time can't change while we're in front
    }

    /** Re-read everything that may have changed while we were away, off the UI thread. */
    void reload() {
        final boolean loadApps = appsDirty;
        appsDirty = false;
        io.post(new Runnable() {
            public void run() {
                List<Koreader.Book> other = new java.util.ArrayList<Koreader.Book>(kyobo.books());
                other.addAll(millie.books());
                recency.apply(other);                       // before sorting: may move them up
                final List<Koreader.Book> b = mergeBooks(ko.books(), other);
                final Koreader.Stats s = ko.stats();
                final List<Apps.App> a = loadApps ? Apps.load(Home.this) : null;
                final long[] st = Device.storage();
                final Object[] sleep = loadSleep();
                StringBuilder sig = new StringBuilder();
                for (Koreader.Book x : b) sig.append(x.path).append(x.pct).append(x.title).append(x.due).append(';');
                sig.append(s.todayMin).append('/').append(s.streakDays).append('/').append(st[0] >> 26);
                if (a != null) sig.append("apps").append(a.size()).append(System.nanoTime());
                sig.append('/').append(sleepMtime);
                final String sg = sig.toString();
                main.post(new Runnable() {
                    public void run() {
                        books = b;
                        stats = s;
                        storage = st;
                        if (a != null) {
                            apps = a;
                            buildHomeApps();
                        }
                        if (sleep != null) {
                            sleepThumb = (Bitmap) sleep[0];
                            sleepName = (String) sleep[1];
                        }
                        if (!sg.equals(dataSig)) {
                            dataSig = sg;
                            screen.invalidate();
                        }
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- home apps (user-chosen)

    static final int HOME_APPS_MAX = 5;
    /** Packages shown in the home "앱" column, in order. Stored as "pkg,pkg,..." in prefs. */
    final ArrayList<String> homePkgs = new ArrayList<String>();
    int homeAppPage;

    private void loadHomePkgs() {
        homePkgs.clear();
        String s = getSharedPreferences("home", MODE_PRIVATE).getString("home_apps", null);
        if (s == null) s = Apps.MILLIE_LAUNCHER + "," + Apps.MILLIE + "," + Koreader.PKG + "," + Apps.SETTINGS;
        for (String p : s.split(",")) {
            p = p.trim();
            if (p.length() == 0 || homePkgs.contains(p)) continue;
            // either Millie package means "Millie"; keep only one entry for it
            if (Apps.isMillie(p) && containsMillie()) continue;
            homePkgs.add(p);
        }
    }

    private boolean containsMillie() {
        for (String p : homePkgs) if (Apps.isMillie(p)) return true;
        return false;
    }

    private void saveHomePkgs() {
        StringBuilder sb = new StringBuilder();
        for (String p : homePkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("home_apps", sb.toString()).commit();
    }

    /** Index of an app in the home list, -1 if not shown. Millie matches either package. */
    int homeIndex(Apps.App a) {
        for (int i = 0; i < homePkgs.size(); i++) {
            String p = homePkgs.get(i);
            if (p.equals(a.pkg) || (Apps.isMillie(p) && Apps.isMillie(a.pkg))) return i;
        }
        return -1;
    }

    private void buildHomeApps() {
        ArrayList<Apps.App> fav = new ArrayList<Apps.App>();
        for (String p : homePkgs) {
            Apps.App a = Apps.find(apps, p);
            if (a == null && Apps.isMillie(p)) a = Apps.find(apps, Apps.MILLIE_LAUNCHER);
            if (a == null && Apps.isMillie(p)) a = Apps.find(apps, Apps.MILLIE);
            if (a == null || fav.size() >= HOME_APPS_MAX) continue;
            // short names for the home column only; the Apps tab keeps real labels
            Apps.App c = new Apps.App();
            c.pkg = a.pkg;
            c.cn = a.cn;
            c.letter = a.letter;
            c.label = Apps.isMillie(a.pkg) ? "밀리의 서재" : Apps.SETTINGS.equals(a.pkg) ? "설정" : a.label;
            fav.add(c);
        }
        homeApps = fav;
    }

    private void toggleHomeApp(Apps.App a) {
        int i = homeIndex(a);
        if (i >= 0) homePkgs.remove(i);
        else if (homePkgs.size() >= HOME_APPS_MAX) {
            android.widget.Toast.makeText(this, "홈에는 앱을 " + HOME_APPS_MAX + "개까지 둘 수 있어요", android.widget.Toast.LENGTH_SHORT).show();
            return;
        } else homePkgs.add(a.pkg);
        saveHomePkgs();
        buildHomeApps();
        screen.invalidate();
    }

    /** First image in /sdcard/sleep as a 60x80 16-gray thumbnail; null when unchanged. */
    private Object[] loadSleep() {
        File dir = new File(Environment.getExternalStorageDirectory(), "sleep");
        long m = dir.lastModified();
        if (m == sleepMtime) return null;
        sleepMtime = m;
        String[] names = dir.list();
        if (names == null || names.length == 0) return new Object[]{null, null};
        Arrays.sort(names);
        for (String n : names) {
            String l = n.toLowerCase(Locale.US);
            if (!(l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".bmp"))) continue;
            Bitmap t = null;
            try {
                String p = new File(dir, n).getPath();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(p, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 58 && o.outHeight / (s * 2) >= 78) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap src = BitmapFactory.decodeFile(p, o);
                if (src != null) {
                    float sc = Math.min(58f / src.getWidth(), 78f / src.getHeight());
                    t = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * sc)),
                            Math.max(1, Math.round(src.getHeight() * sc)), true);
                    if (t != src) src.recycle();
                    if (t.getConfig() != Bitmap.Config.RGB_565) {
                        Bitmap c = t.copy(Bitmap.Config.RGB_565, true);
                        t.recycle();
                        t = c;
                    }
                    if (!t.isMutable()) t = t.copy(Bitmap.Config.RGB_565, true);
                    Covers.toGray16(t);
                }
            } catch (Throwable ignored) {
            }
            return new Object[]{t, n};
        }
        return new Object[]{null, null};
    }

    private void fetchFeeds(final boolean force) {
        if (!force && !feeds.wifiOnly) return;
        io.post(new Runnable() {
            public void run() {
                long newest = 0;
                for (Feeds.Feed f : feeds.feeds) newest = Math.max(newest, f.fetchedAt);
                if (!force && System.currentTimeMillis() - newest < 30 * 60 * 1000L) return;
                feeds.fetchAll();
                main.post(new Runnable() {
                    public void run() {
                        if (tab == Screen.TAB_SETTINGS) screen.invalidate();
                    }
                });
            }
        });
    }

    @Override
    public void onCover() {
        screen.invalidate();
    }

    // ================================================================ actions

    /** Repaint the body; a tab switch also asks the EPD for a full (ghost-clearing) update. */
    private void redraw(boolean fullRefresh) {
        if (fullRefresh) screen.requestFullRefresh();
        else screen.invalidate();
    }

    private void page(boolean library, int delta) {
        int total = library ? books.size() : apps.size();
        int per = library ? Screen.LIB_PER_PAGE : Screen.APPS_PER_PAGE;
        int pages = Math.max(1, (total + per - 1) / per);
        int cur = library ? libPage : appPage;
        int next = Math.max(0, Math.min(pages - 1, cur + delta));
        if (next == cur) return;
        if (library) {
            libPage = next;
            covers.trim();
        } else appPage = next;
        // partial updates for paging, but clear ghosting every few flips
        redraw(++pageFlips % 5 == 0);
    }

    void onSwipe(int dir) {
        if (tasksOpen) return;
        if (tab == Screen.TAB_LIBRARY) page(true, dir);
        else if (tab == Screen.TAB_APPS) page(false, dir);
        else if (tab == Screen.TAB_SETTINGS && sub == 2) onAction(Screen.A_HOME_APP_PAGE, dir);
    }

    void onAction(int action, int arg) {
        switch (action) {
            case Screen.A_HOME_APPS:
                sub = 2;
                homeAppPage = 0;
                redraw(true);
                break;
            case Screen.A_HOME_APP_TOGGLE:
                if (arg < apps.size()) toggleHomeApp(apps.get(arg));
                break;
            case Screen.A_HOME_APP_PAGE: {
                int pages = Math.max(1, (apps.size() + Screen.PICK_PER_PAGE - 1) / Screen.PICK_PER_PAGE);
                int next = Math.max(0, Math.min(pages - 1, homeAppPage + arg));
                if (next != homeAppPage) {
                    homeAppPage = next;
                    redraw(++pageFlips % 5 == 0);
                }
                break;
            }
            case Screen.A_TASK_SWITCH:
                if (tasks != null && arg < tasks.tasks.size()) {
                    tasksOpen = false;
                    launch(tasks.tasks.get(arg).app);
                }
                break;
            case Screen.A_TASK_CLOSE:
                if (tasks != null && arg < tasks.tasks.size())
                    closeApps(java.util.Collections.singletonList(tasks.tasks.get(arg).pkg));
                break;
            case Screen.A_TASK_CLOSE_ALL:
                if (tasks != null && !tasks.tasks.isEmpty()) {
                    ArrayList<String> all = new ArrayList<String>();
                    for (Tasks.Task t : tasks.tasks) all.add(t.pkg);
                    closeApps(all);
                }
                break;
            case Screen.A_TASK_DISMISS:
                tasksOpen = false;
                redraw(true);
                break;
            case Screen.A_TAB:
                if (tab == arg && sub == 0 && !tasksOpen) return;
                tasksOpen = false;
                tab = arg;
                sub = 0;
                if (arg == Screen.TAB_SETTINGS) {
                    dev.readLight();
                    dev.readWifi();
                }
                covers.trim();
                redraw(true);
                break;
            case Screen.A_RESUME:
            case Screen.A_BOOK:
                if (arg < books.size()) openBook(books.get(arg));
                break;
            case Screen.A_HOME_APP:
                if (arg < homeApps.size()) launch(homeApps.get(arg));
                break;
            case Screen.A_APP:
                if (arg < apps.size()) launch(apps.get(arg));
                break;
            case Screen.A_LIB_PAGE:
                page(true, arg);
                break;
            case Screen.A_APP_PAGE:
                page(false, arg);
                break;
            case Screen.A_KOREADER: {
                Apps.App a = Apps.find(apps, Koreader.PKG);
                if (a != null) launch(a);
                break;
            }
            case Screen.A_LIGHT:
                dev.setLightOn(!dev.lightOn);
                redraw(false);
                break;
            case Screen.A_LIGHT_STEP:
                dev.setLightLevel(dev.lightLevel + arg);
                redraw(false);
                break;
            case Screen.A_LIGHT_SET:
                dev.setLightLevel(arg);
                redraw(false);
                break;
            case Screen.A_WIFI:
                dev.setWifi(dev.wifiState == 0);
                break; // the WIFI_STATE broadcast repaints
            case Screen.A_WIFI_SETTINGS:
                start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case Screen.A_SLEEP:
                if (sleepName != null) {
                    File f = new File(new File(Environment.getExternalStorageDirectory(), "sleep"), sleepName);
                    start(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "image/*"));
                }
                break;
            case Screen.A_RSS_HOME:
                feeds.showOnHome = !feeds.showOnHome;
                saveFeeds();
                break;
            case Screen.A_FEEDS:
                sub = 1;
                redraw(true);
                break;
            case Screen.A_RSS_MODE:
                feeds.wifiOnly = !feeds.wifiOnly;
                saveFeeds();
                break;
            case Screen.A_RSS_KEEP: {
                int[] o = Feeds.KEEP_OPTIONS;
                int i = 0;
                while (i < o.length && o[i] != feeds.keep) i++;
                feeds.keep = o[(i + 1) % o.length];
                saveFeeds();
                break;
            }
            case Screen.A_SYSTEM:
                start(new Intent(Settings.ACTION_SETTINGS));
                break;
            case Screen.A_BACK:
                sub = 0;
                redraw(true);
                break;
            case Screen.A_FEED: {
                final Feeds.Feed f = feeds.feeds.get(arg);
                f.on = !f.on;
                saveFeeds();
                if (f.on && dev.wifiState == 2) fetchOne(f);
                break;
            }
            case Screen.A_FEED_ADD:
                addFeedDialog();
                break;
        }
    }

    boolean onLongPress(int action, int arg) {
        if (action != Screen.A_FEED) return false;
        final Feeds.Feed f = feeds.feeds.get(arg);
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(new CharSequence[]{"지금 받기", "구독 해지"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) fetchOne(f);
                        else {
                            feeds.remove(f);
                            saveFeeds();
                        }
                    }
                }).show();
        return true;
    }

    private void saveFeeds() {
        io.post(new Runnable() {
            public void run() {
                feeds.save();
            }
        });
        screen.invalidate();
    }

    private void fetchOne(final Feeds.Feed f) {
        io.post(new Runnable() {
            public void run() {
                feeds.fetch(f);
                feeds.save();
                main.post(new Runnable() {
                    public void run() {
                        screen.invalidate();
                    }
                });
            }
        });
    }

    private void addFeedDialog() {
        final EditText in = new EditText(this);
        in.setHint("https://example.com/rss.xml");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("피드 주소로 추가")
                .setView(in)
                .setPositiveButton("추가", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String u = in.getText().toString().trim();
                        if (u.length() == 0) return;
                        if (!u.matches("^[a-zA-Z]+://.*")) u = "http://" + u;
                        Feeds.Feed f = feeds.add(Feeds.displayUrl(u), u, true);
                        saveFeeds();
                        fetchOne(f);
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ================================================================ launching

    private static final String[][] MIME = {
            {"epub", "application/epub+zip"}, {"pdf", "application/pdf"}, {"djvu", "image/vnd.djvu"},
            {"fb2", "application/x-fb2"}, {"mobi", "application/x-mobipocket-ebook"}, {"cbz", "application/vnd.comicbook+zip"},
            {"txt", "text/plain"}, {"html", "text/html"}, {"htm", "text/html"}, {"rtf", "application/rtf"},
            {"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}, {"chm", "application/x-chm"},
    };

    /** KOReader history and the 교보도서관 / 밀리 shelves as one list, most recently read first. */
    static List<Koreader.Book> mergeBooks(List<Koreader.Book> a, List<Koreader.Book> b) {
        if (b.isEmpty()) return a;
        if (a.isEmpty()) return b;
        java.util.ArrayList<Koreader.Book> out = new java.util.ArrayList<Koreader.Book>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        java.util.Collections.sort(out, new java.util.Comparator<Koreader.Book>() {
            public int compare(Koreader.Book x, Koreader.Book y) {
                return Long.valueOf(y.time).compareTo(x.time);
            }
        });
        return out;
    }

    private void openBook(Koreader.Book b) {
        recency.opened(b);
        if ("millie".equals(b.open)) {
            // 밀리 has no way to open one book from outside: start it (via the wrapper); it resumes
            Apps.App a = Apps.find(apps, Apps.MILLIE_LAUNCHER);
            if (a == null) a = Apps.find(apps, Apps.MILLIE);
            if (a != null) launch(a);
            return;
        }
        if (b.open != null) {
            // 교보도서관 opens the book in its own viewer (kyobo-compat deep link)
            start(new Intent(Intent.ACTION_VIEW, Uri.parse(b.open)).setPackage(Kyobo.PKG));
            return;
        }
        openBook(b.path);
    }

    private void openBook(String path) {
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.US);
        String mime = "application/octet-stream";
        for (String[] m : MIME) if (m[0].equals(ext)) mime = m[1];
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(new File(path)), mime);
        i.setPackage(Koreader.PKG);
        start(i);
    }

    private void launch(Apps.App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn);
        i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(i);
    }

    private void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            overridePendingTransition(0, 0);
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "열 수 있는 앱이 없어요", android.widget.Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            android.widget.Toast.makeText(this, "열 수 없어요", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
