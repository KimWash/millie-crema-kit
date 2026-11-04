package kr.co.crema.home;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads the 교보도서관 bookshelf that kyobo-compat (inside the 교보도서관 app) writes to
 * /sdcard/crema/kyobo_shelf.json: metadata and a cover image only. A book is opened with
 * kyobolibrarykel://crema-open/<id>, in 교보's own viewer. Runs on the IO thread; re-parsed only
 * when the file's mtime changes.
 */
final class Kyobo {
    static final String PKG = "kr.co.kyobobook.KEL";
    private final File file = new File(Environment.getExternalStorageDirectory(), "crema/kyobo_shelf.json");
    private long mtime = -1;
    private List<Koreader.Book> books = Collections.emptyList();

    List<Koreader.Book> books() {
        long m = file.lastModified();
        if (m == mtime) return books;
        mtime = m;
        ArrayList<Koreader.Book> out = new ArrayList<Koreader.Book>();
        if (m > 0) try {
            JSONArray a = new JSONObject(read(file)).optJSONArray("books");
            long now = System.currentTimeMillis();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!o.optBoolean("downloaded", false)) continue;  // not readable offline yet
                Koreader.Book b = new Koreader.Book();
                b.path = "kyobo:" + o.optLong("id");
                b.title = o.optString("title", "");
                b.authors = str(o, "author");
                b.cover = str(o, "cover");
                b.open = str(o, "open");
                b.source = "교보";
                int pct = o.optInt("percent", 0);
                b.pct = Math.max(0, Math.min(100, pct)) / 100.0;
                b.done = pct >= 100;
                long due = parse(str(o, "due"), "yyyyMMddHHmmss");
                if (due > 0) {
                    if (due < now) continue;                       // loan expired: the book can't be opened
                    long days = day(due) - day(now);                // calendar days, like the 교보 app
                    b.due = days <= 0 ? "오늘 반납" : "D-" + days;
                }
                long t = parse(str(o, "access"), "yyyyMMdd");
                if (t <= 0) t = parse(str(o, "borrowed"), "yyyyMMddHHmmss");
                b.time = t > 0 ? t / 1000 + 12 * 3600 : 0;           // KOReader history uses seconds
                out.add(b);
            }
        } catch (Exception ignored) {
        }
        books = out;
        return books;
    }

    private static long day(long ms) {
        return (ms + java.util.TimeZone.getDefault().getOffset(ms)) / 86400000L;
    }

    private static String str(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k, null);
    }

    private static long parse(String s, String fmt) {
        if (s == null || s.length() < fmt.length()) return 0;
        try {
            return new SimpleDateFormat(fmt, Locale.US).parse(s.substring(0, fmt.length())).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
