package kr.co.crema.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static kr.co.crema.home.Ui.*;

/**
 * The whole launcher is this one View. No layouts, no inflation, no child
 * views: each tab is painted from fixed Figma coordinates and touches are
 * routed through a list of hit rectangles rebuilt with every body paint.
 * The status bar can be repainted alone through a dirty rect.
 */
final class Screen extends View {
    static final int W = 758;
    static final int STATUS_BOTTOM = 51;
    static final int TAB_HOME = 0, TAB_LIBRARY = 1, TAB_APPS = 2, TAB_SETTINGS = 3;
    static final int LIB_PER_PAGE = 12, APPS_PER_PAGE = 12;
    /** Height of the old top tab bar; page bodies are drawn this much higher. */
    static final int BODY_SHIFT = 52;
    /** Pager buttons' top in body coordinates (= y 899 on screen, 12px above the nav rule). */
    static final float PAGER_TOP = 899 + BODY_SHIFT;

    // actions
    static final int A_TAB = 1, A_RESUME = 2, A_BOOK = 3, A_HOME_APP = 4, A_APP = 5, A_LIB_PAGE = 6, A_APP_PAGE = 7,
            A_KOREADER = 8, A_LIGHT = 9, A_LIGHT_STEP = 10, A_LIGHT_SET = 11, A_WIFI = 12, A_WIFI_SETTINGS = 13,
            A_SLEEP = 14, A_RSS_HOME = 15, A_FEEDS = 16, A_RSS_MODE = 17, A_RSS_KEEP = 18, A_SYSTEM = 19,
            A_BACK = 20, A_FEED = 21, A_FEED_ADD = 22,
            A_TASK_SWITCH = 23, A_TASK_CLOSE = 24, A_TASK_CLOSE_ALL = 25, A_TASK_DISMISS = 26,
            A_HOME_APPS = 27, A_HOME_APP_TOGGLE = 28, A_HOME_APP_PAGE = 29;
    static final int PICK_PER_PAGE = 10;
    static final int TASK_ROWS = 9;

    private static final class Hit {
        float l, t, r, b;
        int action, arg;
    }

    private final Home h;
    private final Ui ui;
    private final ArrayList<Hit> hits = new ArrayList<Hit>();
    private int hitCount;
    private float dy; // current canvas translation, applied to hit rects
    private final Rect clip = new Rect();
    private final SimpleDateFormat clock = new SimpleDateFormat("M월 d일   HH:mm", Locale.KOREA);
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.KOREA);
    private final Date now = new Date();

    Screen(Context c, Home home, Ui ui) {
        super(c);
        h = home;
        this.ui = ui;
        setWillNotDraw(false);
        setLongClickable(true);
    }

    void invalidateStatus() {
        invalidate(0, 0, W, STATUS_BOTTOM);
    }

    /*
     * fullRefresh() asks SurfaceFlinger to redraw the panel with the full
     * (ghost-clearing) waveform. It must run after the new frame reached the
     * surface, otherwise the panel flashes the old screen. So: mark it, and
     * fire it shortly after the next full-body draw.
     */
    private boolean pendingFull;
    private final Runnable fullRefresh = new Runnable() {
        public void run() {
            Device.Epd.full(Screen.this);
        }
    };

    void requestFullRefresh() {
        pendingFull = true;
        invalidate();
    }

    // ================================================================ paint

    @Override
    protected void onDraw(Canvas c) {
        c.getClipBounds(clip);
        boolean statusOnly = clip.top >= 0 && clip.bottom <= STATUS_BOTTOM;
        ui.rect(c, clip.left, clip.top, clip.right, clip.bottom, WHITE);
        drawStatus(c);
        if (statusOnly) return;
        hitCount = 0;
        hit(600, 10, 740, 50, A_TAB, TAB_SETTINGS); // light indicator -> settings
        if (h.tasksOpen) {
            ui.hline(c, 50, 28, 730, INK);
            drawTasks(c);
        } else if (h.tab == TAB_HOME) {
            drawHome(c);
        } else {
            // Same frame as Home: rule under the status bar, tabs in the bottom nav.
            // The page bodies keep their Figma coordinates (drawn for a top tab bar)
            // and are shifted up by the 52px that bar used to take.
            ui.hline(c, 50, 28, 730, INK);
            c.save();
            c.translate(0, -BODY_SHIFT);
            dy = -BODY_SHIFT;
            if (h.tab == TAB_LIBRARY) drawLibrary(c);
            else if (h.tab == TAB_APPS) drawApps(c);
            else if (h.sub == 1) drawFeeds(c);
            else if (h.sub == 2) drawHomeApps(c);
            else drawSettings(c);
            c.restore();
            dy = 0;
        }
        drawNav(c);
        if (pendingFull) {
            pendingFull = false;
            removeCallbacks(fullRefresh);
            postDelayed(fullRefresh, 80); // after this frame is posted to the surface
        }
    }

    /** Bottom nav shared by every tab: reading stats on the left, tabs on the right. */
    private void drawNav(Canvas c) {
        ui.hline(c, 955, 28, 730, INK);
        // left label depends on the page: reading stats, book range, app count, nothing
        if (h.tab == TAB_HOME) {
            Koreader.Stats st = h.stats;
            String today = st == null ? "" : (st.streakDays > 0 ? st.todayMin + "분 · " + st.streakDays + "일째" : "오늘 " + st.todayMin + "분");
            ui.text(c, today, M, 15, TEXT2, 28, 975, 0, LEFT, 335);
        } else if (h.tab == TAB_LIBRARY) {
            int n = h.books.size();
            String s = "읽은 책 0권";
            if (n > 0) {
                int start = Math.min(h.libPage, (n - 1) / LIB_PER_PAGE) * LIB_PER_PAGE;
                s = n + "권 중 " + (start + 1) + "–" + Math.min(n, start + LIB_PER_PAGE);
            }
            ui.text(c, s, M, 16, TEXT2, 28, 973.5f, 20.8f, LEFT, 335);
        } else if (h.tab == TAB_APPS) {
            ui.text(c, h.apps.size() + "개", M, 15, TEXT2, 28, 975, 0, LEFT, 335);
        }
        String[] names = {"홈", "서재", "앱", "설정"};
        for (int i = 0; i < 4; i++) {
            float l = 395 + i * 83.75f;
            boolean on = i == h.tab;
            ui.text(c, names[i], on ? B : M, 20, on ? INK : TEXT2, l + 41.9f, 972, 0, CENTER, 0);
            hit(l, 956, l + 83.75f, 1024, A_TAB, i);
        }
    }

    private void drawStatus(Canvas c) {
        Device d = h.dev;
        ui.wifi(c, 28, 21, d.wifiState);
        ui.battery(c, 66, 23, d.battery, d.charging);
        ui.text(c, d.battery < 0 ? "--" : d.battery + "%", M, 15, INK, 98, 22, 0, LEFT, 0);
        now.setTime(System.currentTimeMillis());
        ui.text(c, clock.format(now), SB, 16, INK, W / 2f, 21.5f, 0, CENTER, 0);
        ui.sun(c, 624, 20);
        ui.levelSquares(c, 654, 25, d.lightOn ? d.lightLevel : 0);
    }

    // ---------------------------------------------------------------- home

    private void drawHome(Canvas c) {
        ui.hline(c, 50, 28, 730, INK);
        List<Koreader.Book> books = h.books;
        Koreader.Book cur = books.isEmpty() ? null : books.get(0);

        // cover slot 206x293
        Bitmap cov = cur == null ? null : h.covers.get(cur.path, 206, 293);
        if (cov != null) {
            ui.drawBitmap(c, cov, 28, 75);
        } else {
            ui.drawImage(c, IMG_CAT_HOME, 28 + 103, 140 + 63);
            ui.text(c, "표지가 없는 책이에요", R, 16, TEXT3, 28 + 103, 282, 20.8f, CENTER, 190);
        }
        ui.outline(c, 28, 75, 234, 368, 4, 1, INK);

        float x = 262, w = 468;
        if (cur != null) {
            ui.text(c, "이어 읽기", M, 16, TEXT3, x, 75, 20.8f, LEFT, 0);
            ui.textBlock(c, cur.title, B, 44, INK, x, 107.8f, 52.8f, w, 1, LEFT);
            String meta = cur.authors != null && cur.authors.length() > 0 ? cur.authors : ext(cur.path);
            ui.text(c, meta, R, 18, TEXT3, x, 172.6f, 23.4f, LEFT, w);
            int page = cur.page();
            if (cur.pages > 0) ui.text(c, page + " / " + cur.pages + "쪽", M, 22, INK, x, 231.4f, 28.6f, LEFT, 0);
            ui.text(c, Math.round(cur.pct * 100) + "%", M, 22, INK, 730, 231.4f, 28.6f, RIGHT, 0);
            ui.progressBold(c, x, 272, 730, (float) cur.pct);
            ui.round(c, x, 304, 730, 368, 8, INK);
            String label = cur.done ? "다시 읽기" : page > 0 && cur.pages > 0 ? page + "쪽부터 이어 읽기" : "이어 읽기";
            ui.text(c, label, SB, 22, WHITE, x + w / 2, 304 + 17.7f, 28.6f, CENTER, 0);
            hit(28, 75, 730, 368, A_RESUME, 0);
        } else {
            ui.text(c, "이어 읽기", M, 16, TEXT3, x, 75, 20.8f, LEFT, 0);
            ui.text(c, "읽던 책이 없어요", B, 44, INK, x, 107.8f, 52.8f, LEFT, w);
            ui.text(c, "KOReader에서 책을 열면 여기에 이어져요", R, 18, TEXT3, x, 172.6f, 23.4f, LEFT, w);
            ui.round(c, x, 304, 730, 368, 8, INK);
            ui.text(c, "KOReader 열기", SB, 22, WHITE, x + w / 2, 304 + 17.7f, 28.6f, CENTER, 0);
            hit(x, 304, 730, 368, A_KOREADER, 0);
        }
        ui.hline(c, 396, 28, 730, INK);

        // left column: other books
        ui.text(c, "서재의 다른 책", B, 22, INK, 28, 421, 28.6f, LEFT, 0);
        float lw = ui.width(M, 16, "전체 보기");
        ui.text(c, "전체 보기", M, 16, TEXT3, 363 - 22 - lw, 424.9f, 20.8f, LEFT, 0);
        ui.chevronRight(c, 343, 425.3f, INK);
        hit(240, 410, 363, 458, A_TAB, TAB_LIBRARY);
        ui.hline(c, 457.6f, 28, 363, INK);
        int shown = 0;
        for (int i = 1; i < books.size() && shown < 3; i++, shown++) {
            Koreader.Book b = books.get(i);
            float top = 458.6f + shown * 117;
            if (shown > 0) ui.hline(c, top - 1, 28, 363, BORDER);
            Bitmap bc = h.covers.get(b.path, 64, 92);
            if (bc != null) ui.drawBitmap(c, bc, 28, top + 12);
            else ui.drawImage(c, IMG_CAT_MINI, 60, top + 12 + 46);
            ui.outline(c, 28, top + 12, 92, top + 104, 3, 1, INK);
            float iy = top + 12 + (92 - 77f) / 2;
            ui.text(c, b.title, M, 18, INK, 108, iy, 23.4f, LEFT, 255);
            String a = b.authors == null || b.authors.length() == 0 ? ext(b.path) : b.authors;
            ui.text(c, a, R, 16, TEXT3, 108, iy + 25.4f, 20.8f, LEFT, 255);
            float py = iy + 48.2f + 8;
            if (b.done) ui.text(c, "다 읽음", M, 16, INK, 108, py, 20.8f, LEFT, 0);
            else {
                String pc = Math.round(b.pct * 100) + "%";
                float pw = ui.width(M, 16, pc);
                ui.progressBold(c, 108, py + 6.4f, 363 - pw - 12, (float) b.pct);
                ui.text(c, pc, M, 16, INK, 363, py, 20.8f, RIGHT, 0);
            }
            hit(28, top, 363, top + 116, A_BOOK, i);
        }
        if (shown == 0) ui.text(c, books.isEmpty() ? "아직 읽은 책이 없어요" : "다른 책이 없어요", M, 16, TEXT2, 28, 478, 20.8f, LEFT, 0);

        // right column: apps
        ui.text(c, "앱", B, 22, INK, 395, 421, 28.6f, LEFT, 0);
        ui.chevronRight(c, 708, 425.3f, INK);
        hit(395, 410, 730, 458, A_TAB, TAB_APPS);
        ui.hline(c, 457.6f, 395, 730, INK);
        List<Apps.App> fav = h.homeApps;
        for (int i = 0; i < fav.size(); i++) {
            Apps.App a = fav.get(i);
            float top = 458.6f + i * 89;
            if (i > 0) ui.hline(c, top - 1, 395, 730, BORDER);
            ui.outline(c, 395, top + 12, 459, top + 76, 12, 1, INK);
            iconContent(c, a, 427, top + 44, true);
            ui.text(c, a.label, M, 22, INK, 475, top + 29.7f, 28.6f, LEFT, 225);
            ui.chevronRight(c, 710, top + 34, INK);
            hit(395, top, 730, top + 88, A_HOME_APP, i);
        }

    }

    // ---------------------------------------------------------------- running apps (home double-tap)

    private void drawTasks(Canvas c) {
        Tasks.Snapshot s = h.tasks;
        ui.text(c, "실행 중인 앱", B, 22, INK, 28, 75, 28.6f, LEFT, 0);
        if (s != null) {
            String mem = "사용 가능 " + (s.availBytes >> 20) + " / " + (s.totalBytes >> 20) + "MB";
            ui.text(c, mem, M, 16, TEXT2, 730, 78.9f, 20.8f, RIGHT, 0);
        }
        ui.hline(c, 111.6f, 28, 730, INK);
        float y = 112.6f;
        if (s == null) {
            ui.text(c, "불러오는 중…", M, 18, TEXT2, 28, y + 24, 23.4f, LEFT, 0);
            return;
        }
        if (s.tasks.isEmpty()) {
            ui.text(c, "실행 중인 앱이 없어요", M, 18, TEXT2, 28, y + 24, 23.4f, LEFT, 0);
        }
        int n = Math.min(s.tasks.size(), TASK_ROWS);
        for (int i = 0; i < n; i++) {
            Tasks.Task t = s.tasks.get(i);
            boolean closing = h.closing.contains(t.pkg);
            if (i > 0) ui.hline(c, y - 1, 28, 730, LINE);
            ui.outline(c, 28, y + 12, 80, y + 64, 10, 1.5f, BORDER);
            iconContent(c, t.app, 54, y + 38, false);
            String label = Apps.isMillie(t.pkg) ? "밀리의 서재" : t.app.label;
            ui.text(c, label, M, 22, closing ? BORDER : INK, 96, y + 23.5f, 28.6f, LEFT, 420);
            String mb = closing ? "닫는 중" : Math.max(1, Math.round(t.pssKb / 1024f)) + "MB";
            ui.text(c, mb, M, 18, TEXT2, 670, y + 26.6f, 23.4f, RIGHT, 0);
            ui.outline(c, 686, y + 16, 730, y + 60, 10, 1.5f, BORDER);
            ui.cross(c, 698, y + 28, closing ? BORDER : INK);
            hit(28, y, 640, y + 76, A_TASK_SWITCH, i);
            if (!closing) hit(640, y, 758, y + 76, A_TASK_CLOSE, i);
            y += 77;
        }
        if (!s.tasks.isEmpty()) {
            ui.hline(c, y - 1, 28, 730, LINE);
            y += 23;
            long total = 0;
            for (Tasks.Task t : s.tasks) total += t.pssKb;
            ui.outline(c, 28, y, 730, y + 60, 8, 1.5f, INK);
            String all = "모두 닫기 · " + (total >> 10) + "MB";
            ui.text(c, all, SB, 22, INK, 379, y + 15.7f, 28.6f, CENTER, 0);
            hit(28, y, 730, y + 60, A_TASK_CLOSE_ALL, 0);
            y += 76;
        }
        ui.textBlock(c, "홈 버튼을 두 번 누르면 열려요. 앱 이름을 누르면 그 앱으로 돌아가고, 닫은 앱은 다음에 처음부터 시작해요.",
                R, 16, TEXT2, 28, y, 20.8f, 702, 2, LEFT);
    }

    /** Letter tile content, or the bundled glyph for Millie / Settings. */
    private void iconContent(Canvas c, Apps.App a, float cx, float cy, boolean big) {
        if (Apps.isMillie(a.pkg) && ui.image(big ? IMG_MILLIE46 : IMG_MILLIE33) != null)
            ui.drawImage(c, big ? IMG_MILLIE46 : IMG_MILLIE33, cx, cy);
        else if (Apps.SETTINGS.equals(a.pkg)) ui.drawImage(c, IMG_GEAR, cx, cy);
        else ui.text(c, a.letter, B, 22, INK, cx, cy - 14.3f, 28.6f, CENTER, 0);
    }

    private static String ext(String path) {
        int d = path.lastIndexOf('.');
        return d > 0 ? path.substring(d + 1).toUpperCase(Locale.US) : "";
    }

    // ---------------------------------------------------------------- library

    private void drawLibrary(Canvas c) {
        List<Koreader.Book> books = h.books;
        if (books.isEmpty()) {
            ui.drawImage(c, IMG_CAT_LARGE, 379, 103 + 206 + 94.5f);
            ui.text(c, "아직 읽은 책이 없어요", B, 22, INK, 379, 526, 28.6f, CENTER, 0);
            ui.text(c, "KOReader에서 책을 열면", R, 16, TEXT2, 379, 567, 20.8f, CENTER, 0);
            ui.text(c, "여기에 차곡차곡 모여요.", R, 16, TEXT2, 379, 587.8f, 20.8f, CENTER, 0);
            ui.round(c, 229, 649, 529, 709, 8, INK);
            ui.text(c, "KOReader 열기", SB, 22, WHITE, 379, 664.7f, 28.6f, CENTER, 0);
            hit(229, 649, 529, 709, A_KOREADER, 0);
            return;
        }
        ui.text(c, "읽은 책", B, 22, INK, 28, 127, 28.6f, LEFT, 0);
        ui.text(c, "최근 읽은 순", M, 16, TEXT2, 730, 130.9f, 20.8f, RIGHT, 0);
        int pages = (books.size() + LIB_PER_PAGE - 1) / LIB_PER_PAGE;
        int page = Math.min(h.libPage, pages - 1);
        int start = page * LIB_PER_PAGE;
        for (int k = 0; k < LIB_PER_PAGE && start + k < books.size(); k++) {
            Koreader.Book b = books.get(start + k);
            float x = Math.round(28 + (k % 4) * 187.33f), y = Math.round(172 + (k / 4) * 252.2f);
            Bitmap cov = h.covers.get(b.path, 140, 180);
            if (cov != null) {
                ui.drawBitmap(c, cov, x, y);
                ui.outline(c, x, y, x + 140, y + 180, 3, 1, BORDER);
            } else {
                ui.round(c, x, y, x + 140, y + 180, 3, SURFACE);
                ui.outline(c, x, y, x + 140, y + 180, 3, 1, BORDER);
                ui.drawImage(c, IMG_CAT_GRID, x + 70, y + 90);
            }
            ui.text(c, b.title, M, 18, INK, x, y + 188, 23.4f, LEFT, 140);
            float py = y + 219.4f;
            if (b.done) ui.text(c, "다 읽음", M, 16, INK, x, py, 20.8f, LEFT, 0);
            else {
                ui.progressThin(c, x, py + 8.4f, x + 100, (float) b.pct);
                ui.text(c, Math.round(b.pct * 100) + "%", M, 16, TEXT2, x + 108, py, 20.8f, LEFT, 0);
            }
            hit(x - 8, y - 6, x + 148, y + 246, A_BOOK, start + k);
        }
        pager(c, page, pages, A_LIB_PAGE);
    }

    private void pager(Canvas c, int page, int pages, int action) {
        if (pages <= 1) return;
        boolean prev = page > 0, next = page < pages - 1;
        float t = PAGER_TOP;
        ui.outline(c, 576, t, 620, t + 44, 10, 1.5f, BORDER);
        ui.chevronLeft(c, 588, t + 12, prev ? INK : BORDER);
        ui.text(c, (page + 1) + " / " + pages, M, 18, INK, 653, t + 10.5f, 23.4f, CENTER, 0);
        ui.outline(c, 686, t, 730, t + 44, 10, 1.5f, BORDER);
        ui.chevronRight(c, 698, t + 12, next ? INK : BORDER);
        // touch targets stop at the bottom nav's rule
        if (prev) hit(560, t - 10, 630, t + 52, action, -1); // bottom edge = nav rule (955)
        if (next) hit(676, t - 10, 758, t + 52, action, 1);
    }

    // ---------------------------------------------------------------- apps

    private void drawApps(Canvas c) {
        List<Apps.App> apps = h.apps;
        ui.text(c, "설치된 앱", B, 22, INK, 28, 127, 28.6f, LEFT, 0);
        ui.text(c, "가나다 순", M, 16, TEXT2, 730, 131, 20.8f, RIGHT, 0);
        int pages = Math.max(1, (apps.size() + APPS_PER_PAGE - 1) / APPS_PER_PAGE);
        int page = Math.min(h.appPage, pages - 1);
        int start = page * APPS_PER_PAGE;
        int n = Math.min(APPS_PER_PAGE, apps.size() - start);
        float top0 = 164;
        int rowsLeft = Math.min(6, n);
        if (n > 0) ui.vline(c, 379, top0, top0 + rowsLeft * 77 - 1, BORDER);
        for (int k = 0; k < n; k++) {
            Apps.App a = apps.get(start + k);
            int col = k / 6, row = k % 6;
            float x = col == 0 ? 28 : 399.5f, top = top0 + row * 77;
            if (row > 0) ui.hline(c, top - 1, x, x + 330.5f, LINE);
            ui.outline(c, x, top + 12, x + 52, top + 64, 10, 1.5f, BORDER);
            iconContent(c, a, x + 26, top + 38, false);
            ui.text(c, a.label, M, 22, INK, x + 68, top + 23.5f, 28.6f, LEFT, 226);
            ui.chevronRight(c, x + 310.5f, top + 28, INK);
            hit(x, top, x + 330.5f, top + 76, A_APP, start + k);
        }
        pager(c, page, pages, A_APP_PAGE);
    }

    // ---------------------------------------------------------------- settings

    private float header(Canvas c, float x, float y, String title, String right) {
        ui.text(c, title, B, 22, INK, x, y, 28.6f, LEFT, 0);
        if (right != null) ui.text(c, right, M, 16, TEXT2, x + 330.5f, y + 3.9f, 20.8f, RIGHT, 0);
        return y + 36.6f;
    }

    private float switchRow(Canvas c, float x, float y, String label, boolean on, int action) {
        ui.text(c, label, M, 18, INK, x, y + 20.3f, 23.4f, LEFT, 0);
        ui.toggle(c, x + 274.5f, y + 16, on);
        hit(x, y, x + 330.5f, y + 64, action, 0);
        return y + 64;
    }

    private float valueRow(Canvas c, float x, float y, String label, String value, boolean chevron, int action) {
        ui.text(c, label, M, 18, INK, x, y + 16, 23.4f, LEFT, 0);
        float r = x + 330.5f;
        if (chevron) {
            ui.chevronRight(c, r - 20, y + 17.7f, INK);
            r -= 28;
        }
        if (value != null) ui.text(c, value, M, 16, TEXT2, r, y + 17.3f, 20.8f, RIGHT, 0);
        if (action != 0) hit(x, y, x + 330.5f, y + 55.4f, action, 0);
        return y + 55.4f;
    }

    private float infoRow(Canvas c, float x, float y, String label, String value) {
        ui.text(c, label, M, 16, TEXT2, x, y + 17.3f, 20.8f, LEFT, 0);
        ui.text(c, value, M, 18, INK, x + 330.5f, y + 16, 23.4f, RIGHT, 0);
        return y + 55.4f;
    }

    private float divider(Canvas c, float x, float y) {
        ui.hline(c, y, x, x + 330.5f, LINE);
        return y + 1;
    }

    private void drawSettings(Canvas c) {
        Device d = h.dev;
        float x = 28, y = 127;
        y = header(c, x, y, "밝기", d.lightOn ? d.lightLevel + " / 5" : "꺼짐");
        y = switchRow(c, x, y, "프런트라이트", d.lightOn, A_LIGHT);
        y = divider(c, x, y);
        // level control
        ui.outline(c, x, y + 16, x + 44, y + 60, 10, 1.5f, BORDER);
        ui.minus(c, x + 12, y + 28, INK);
        hit(x - 8, y, x + 56, y + 76, A_LIGHT_STEP, -1);
        float sx = x + (330.5f - 204) / 2;
        for (int i = 0; i < 5; i++) {
            float l = sx + i * 42;
            boolean filled = i < d.lightLevel;
            int col = d.lightOn ? INK : BORDER;
            if (filled) ui.round(c, l, y + 26, l + 36, y + 50, 4, col);
            else ui.outline(c, l, y + 26, l + 36, y + 50, 4, 1.5f, col);
            hit(l - 3, y + 10, l + 39, y + 66, A_LIGHT_SET, i + 1);
        }
        ui.outline(c, x + 286.5f, y + 16, x + 330.5f, y + 60, 10, 1.5f, BORDER);
        ui.plus(c, x + 298.5f, y + 28, INK);
        hit(x + 274.5f, y, x + 338.5f, y + 76, A_LIGHT_STEP, 1);
        y += 76 + 28;

        y = header(c, x, y, "Wi-Fi", null);
        y = switchRow(c, x, y, "Wi-Fi 사용", d.wifiState > 0, A_WIFI);
        y = divider(c, x, y);
        String conn = d.wifiState == 2 ? (d.ssid != null ? d.ssid : "연결됨") : d.wifiState == 1 ? "연결 안 됨" : "꺼짐";
        ui.text(c, "연결", M, 18, INK, x, y + 16, 23.4f, LEFT, 0);
        ui.text(c, conn, M, 16, TEXT2, x + 330.5f, y + 17.3f, 20.8f, RIGHT, 220);
        y += 55.4f;
        y = divider(c, x, y);
        y = valueRow(c, x, y, "네트워크 선택", null, true, A_WIFI_SETTINGS);
        y += 28;

        y = header(c, x, y, "절전 화면", null);
        Bitmap thumb = h.sleepThumb;
        ui.rect(c, x, y + 12, x + 60, y + 92, WHITE);
        if (thumb != null) ui.drawBitmap(c, thumb, x + (60 - thumb.getWidth()) / 2f, y + 12 + (80 - thumb.getHeight()) / 2f);
        else ui.drawImage(c, IMG_DOODLE, x + 30, y + 52);
        ui.outline(c, x, y + 12, x + 60, y + 92, 3, 1, BORDER);
        ui.text(c, "절전 화면 이미지", M, 18, INK, x + 76, y + 25.9f, 23.4f, LEFT, 0);
        ui.text(c, h.sleepName == null ? "이미지 없음" : "sleep / " + h.sleepName, R, 16, TEXT2, x + 76, y + 53.3f, 20.8f, LEFT, 220);
        ui.chevronRight(c, x + 310.5f, y + 42, INK);
        hit(x, y, x + 330.5f, y + 104, A_SLEEP, 0);
        y += 104 + 28;

        y = header(c, x, y, "홈 화면", null);
        valueRow(c, x, y, "홈에 표시할 앱", h.homeApps.size() + "개", true, A_HOME_APPS);

        ui.vline(c, 378.5f, 127, 990, BORDER);

        Feeds f = h.feeds;
        x = 399.5f;
        y = 127;
        y = header(c, x, y, "RSS 리더", null);
        y = switchRow(c, x, y, "홈에 표시", f.showOnHome, A_RSS_HOME);
        y = divider(c, x, y);
        y = valueRow(c, x, y, "구독 피드", f.feeds.size() + "개", true, A_FEEDS);
        y = divider(c, x, y);
        y = valueRow(c, x, y, "새로 받기", f.wifiOnly ? "Wi-Fi 연결 시" : "직접 받기", true, A_RSS_MODE);
        y = divider(c, x, y);
        y = valueRow(c, x, y, "글 보관", "최근 " + f.keep + "개", true, A_RSS_KEEP);
        y += 28;

        y = header(c, x, y, "기기 정보", null);
        y = infoRow(c, x, y, "배터리", d.battery < 0 ? "-" : d.battery + "%" + (d.charging ? " · 충전 중" : ""));
        y = divider(c, x, y);
        long[] st = h.storage;
        y = infoRow(c, x, y, "저장공간", st == null ? "-" : gb(st[0]) + " / " + gb(st[1]) + " GB");
        y = divider(c, x, y);
        y = infoRow(c, x, y, "Android", android.os.Build.VERSION.RELEASE);
        y = divider(c, x, y);
        y = infoRow(c, x, y, "런처 버전", h.version);
        y += 28;

        y = header(c, x, y, "시스템", null);
        valueRow(c, x, y, "안드로이드 설정 열기", null, true, A_SYSTEM);
    }

    private static String gb(long b) {
        return String.format(Locale.US, "%.1f", b / 1073741824.0);
    }

    // ---------------------------------------------------------------- home apps picker

    private void titleBar(Canvas c, String title) {
        ui.outline(c, 28, 123, 124, 167, 10, 1.5f, BORDER);
        ui.chevronLeft(c, 45, 135, INK);
        ui.text(c, "설정", M, 18, INK, 69, 133.5f, 23.4f, LEFT, 0);
        hit(20, 110, 132, 180, A_BACK, 0);
        ui.text(c, title, B, 22, INK, 140, 130.5f, 28.6f, LEFT, 0);
    }

    private void drawHomeApps(Canvas c) {
        titleBar(c, "홈에 표시할 앱");
        ui.text(c, h.homePkgs.size() + " / " + Home.HOME_APPS_MAX, M, 16, TEXT2, 730, 134.4f, 20.8f, RIGHT, 0);
        ui.hline(c, 187, 28, 730, BORDER);
        List<Apps.App> apps = h.apps;
        int pages = Math.max(1, (apps.size() + PICK_PER_PAGE - 1) / PICK_PER_PAGE);
        int page = Math.min(h.homeAppPage, pages - 1);
        int start = page * PICK_PER_PAGE;
        float y = 188;
        for (int k = 0; k < PICK_PER_PAGE && start + k < apps.size(); k++) {
            Apps.App a = apps.get(start + k);
            int idx = h.homeIndex(a);
            if (k > 0) ui.hline(c, y - 1, 28, 730, LINE);
            ui.outline(c, 28, y + 10, 72, y + 54, 8, 1.5f, BORDER);
            iconContent(c, a, 50, y + 32, false);
            String label = Apps.isMillie(a.pkg) ? "밀리의 서재" : a.label;
            ui.text(c, label, M, 20, INK, 88, y + 19, 26, LEFT, 480);
            if (idx >= 0) { // order on the home screen
                ui.round(c, 616, y + 18, 644, y + 46, 14, INK);
                ui.text(c, String.valueOf(idx + 1), B, 16, WHITE, 630, y + 21.6f, 20.8f, CENTER, 0);
            }
            ui.toggle(c, 674, y + 16, idx >= 0);
            hit(28, y, 730, y + 64, A_HOME_APP_TOGGLE, start + k);
            y += 65;
        }
        ui.hline(c, y - 1, 28, 730, LINE);
        ui.text(c, "켠 순서대로 홈에 보여요. 최대 " + Home.HOME_APPS_MAX + "개까지 둘 수 있어요.", R, 16, TEXT2, 28, y + 16, 20.8f, LEFT, 702);
        pager(c, page, pages, A_HOME_APP_PAGE);
    }

    // ---------------------------------------------------------------- feeds

    private void drawFeeds(Canvas c) {
        titleBar(c, "구독 피드");
        ui.hline(c, 187, 28, 730, BORDER);

        List<Feeds.Feed> list = h.feeds.feeds;
        float y = 188;
        int n = Math.min(list.size(), 6);
        for (int i = 0; i < n; i++) {
            Feeds.Feed f = list.get(i);
            ui.outline(c, 28, y + 26.5f, 80, y + 78.5f, 10, 1.5f, BORDER);
            ui.text(c, Apps.letter(f.name), B, 22, INK, 54, y + 38, 28.6f, CENTER, 0);
            ui.text(c, f.name, M, 18, INK, 96, y + 16, 23.4f, LEFT, 562);
            ui.text(c, Feeds.displayUrl(f.url), R, 16, TEXT2, 96, y + 43, 20.8f, LEFT, 562);
            if (f.error != null) {
                ui.errorMark(c, 96, y + 70.5f);
                ui.text(c, "받기 실패  ·  " + f.error, M, 16, INK, 118, y + 68, 20.8f, LEFT, 540);
            } else if (f.fetchedAt > 0) {
                now.setTime(f.fetchedAt);
                ui.text(c, "새 글 " + f.count + "개  ·  " + hm.format(now) + "에 받음", R, 16, TEXT2, 96, y + 68, 20.8f, LEFT, 540);
            } else {
                ui.text(c, f.on ? "아직 받지 않았어요" : "꺼져 있어요", R, 16, TEXT2, 96, y + 68, 20.8f, LEFT, 540);
            }
            ui.toggle(c, 674, y + 36.5f, f.on);
            hit(28, y, 730, y + 105, A_FEED, i);
            y += 105;
            ui.hline(c, y, 28, 730, LINE);
            y += 1;
        }
        y += 24;
        ui.outline(c, 28, y, 730, y + 60, 8, 1.5f, INK);
        float tw = ui.width(SB, 22, "피드 주소로 추가");
        float bx = 379 - (tw + 28) / 2;
        ui.plus(c, bx, y + 20, INK);
        ui.text(c, "피드 주소로 추가", SB, 22, INK, bx + 28, y + 15.7f, 28.6f, LEFT, 0);
        hit(28, y, 730, y + 60, A_FEED_ADD, 0);
        y += 76;
        ui.text(c, "피드는 Wi-Fi가 연결될 때 새로 받아요. 항목을 길게 누르면 지울 수 있어요.", R, 16, TEXT2, 28, y, 20.8f, LEFT, 702);
    }

    // ================================================================ touch

    private void hit(float l, float t, float r, float b, int action, int arg) {
        Hit x;
        if (hitCount < hits.size()) x = hits.get(hitCount);
        else {
            x = new Hit();
            hits.add(x);
        }
        hitCount++;
        x.l = l;
        x.t = t + dy;
        x.r = r;
        x.b = b + dy;
        x.action = action;
        x.arg = arg;
    }

    private Hit find(float px, float py) {
        for (int i = hitCount - 1; i >= 0; i--) {
            Hit x = hits.get(i);
            if (px >= x.l && px < x.r && py >= x.t && py < x.b) return x;
        }
        return null;
    }

    private float downX, downY;
    private boolean longFired;
    private final Runnable longPress = new Runnable() {
        public void run() {
            Hit x = find(downX, downY);
            if (x != null && h.onLongPress(x.action, x.arg)) longFired = true;
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                longFired = false;
                postDelayed(longPress, 650);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > 24 || Math.abs(e.getY() - downY) > 24) removeCallbacks(longPress);
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (longFired) return true;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > 90 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    h.onSwipe(dx < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(dx) < 30 && Math.abs(dy) < 30) {
                    Hit x = find(downX, downY);
                    if (x != null) h.onAction(x.action, x.arg);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                return true;
        }
        return super.onTouchEvent(e);
    }
}
