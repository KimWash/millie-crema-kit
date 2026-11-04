package kr.co.crema.home;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 밀리의 서재 bookshelf, from the copy of Millie's books.db that shelf-sync/millie-shelf.sh keeps in
 * /sdcard/crema/.millie/ (Millie itself can't be changed). Metadata only; a book is opened by
 * starting Millie (via its wrapper), which resumes where it was. Runs on the IO thread; re-read only
 * when the copy changes. Covers are downloaded once into the app's cache.
 */
final class Millie {
    private final File dir = new File(Environment.getExternalStorageDirectory(), "crema/.millie");
    private final File cache, coverDir;
    private long stamp = -1, lastTry;
    private boolean missingCovers;
    private List<Koreader.Book> books = Collections.emptyList();

    Millie(File cacheDir) {
        cache = cacheDir;
        coverDir = new File(cacheDir, "millie-covers");
    }

    List<Koreader.Book> books() {
        File db = new File(dir, "books.db"), wal = new File(dir, "books.db-wal");
        long s = db.lastModified() ^ (wal.lastModified() * 31);
        long now0 = System.currentTimeMillis();
        // unchanged copy: reuse, unless some covers are still missing (offline earlier) -> retry every 5 min
        if (s == stamp && !(missingCovers && now0 - lastTry > 300000L)) return books;
        stamp = s;
        lastTry = now0;
        missingCovers = false;
        ArrayList<Koreader.Book> out = new ArrayList<Koreader.Book>();
        if (db.isFile()) {
            File cdb = new File(cache, "millie-books.db"), cwal = new File(cache, "millie-books.db-wal");
            SQLiteDatabase d = null;
            Cursor c = null;
            try {
                // private read-write copy: SQLite needs to write -shm (and may checkpoint the WAL)
                new File(cache, "millie-books.db-shm").delete();
                Koreader.copy(db, cdb);
                if (wal.isFile()) Koreader.copy(wal, cwal);
                else cwal.delete();
                d = SQLiteDatabase.openDatabase(cdb.getPath(), null, SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                c = d.rawQuery("SELECT contentSeq, contentName, author, contentThumbUrl, readAt, readPercent, expireAt, createdAt"
                        + " FROM Bookshelf", null);
                long now = System.currentTimeMillis();
                while (c.moveToNext()) {
                    Koreader.Book b = new Koreader.Book();
                    String seq = c.getString(0);
                    b.path = "millie:" + seq;
                    b.title = c.getString(1);
                    b.authors = c.getString(2);
                    b.source = "밀리";
                    b.open = "millie";
                    int pct = c.isNull(5) ? 0 : c.getInt(5);
                    b.pct = Math.max(0, Math.min(100, pct)) / 100.0;
                    b.done = pct >= 100;
                    long exp = parse(c.getString(6));
                    if (exp > 0) {
                        if (exp < now) continue;
                        long days = day(exp) - day(now);
                        if (days <= 30) b.due = days <= 0 ? "오늘까지" : "D-" + days;
                    }
                    long t = parse(c.getString(4));
                    if (t <= 0) t = parse(c.getString(7));
                    b.time = t / 1000;
                    File cover = new File(coverDir, safe(seq) + ".jpg");
                    if (!cover.isFile()) fetch(c.getString(3), cover);
                    if (cover.isFile()) b.cover = cover.getPath();
                    else missingCovers = true;
                    if (b.title != null) out.add(b);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
                if (d != null) d.close();
            }
        }
        books = out;
        return books;
    }

    private static long parse(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long day(long ms) {
        return (ms + java.util.TimeZone.getDefault().getOffset(ms)) / 86400000L;
    }

    private static String safe(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static void fetch(String url, File out) {
        if (url == null || !url.startsWith("http")) return;
        HttpURLConnection h = null;
        try {
            out.getParentFile().mkdirs();
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(15000);
            if (h.getResponseCode() != 200) return;
            InputStream in = h.getInputStream();
            File tmp = new File(out.getPath() + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0) fo.write(b, 0, r);
            fo.close();
            in.close();
            tmp.renameTo(out);
        } catch (Throwable ignored) {
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
