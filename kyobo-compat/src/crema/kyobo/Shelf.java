package crema.kyobo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Shares the 교보도서관 bookshelf with crema-home (inserted by build.sh):
 *  - start(): from EBookCaseApplication.onCreate. While the app process lives, a background thread
 *    watches databases/books.db and writes /sdcard/crema/kyobo_shelf.json (metadata only: title,
 *    author, cover, progress, due date) plus cover images under /sdcard/crema/covers/.
 *    Login data (login_record) is never read. Book files stay where they are (DRM).
 *  - applink(): from ApplinkActivity.onCreate. kyobolibrarykel://crema-open/<book id> opens that
 *    book in the app's own viewer, the same call the bookshelf makes.
 */
public final class Shelf {
    static final String TAG = "KyoboShelf";
    private static volatile boolean sStarted;

    private Shelf() {}

    static File dir() { return new File(Environment.getExternalStorageDirectory(), "crema"); }

    public static void start(Context c) {
        if (sStarted) return;
        sStarted = true;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        Thread t = new Thread(new Runnable() {
            public void run() {
                long last = -1;
                while (true) {
                    try {
                        File db = app.getDatabasePath("books.db");
                        long m = db.lastModified() ^ new File(db.getPath() + "-wal").lastModified();
                        if (m != last && db.isFile()) {
                            export(app, db);
                            last = m;
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "export failed", e);
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                }
            }
        }, "crema-shelf");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void export(Context app, File dbFile) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        StringBuilder sb = new StringBuilder(4096);
        File covers = new File(dir(), "covers");
        int n = 0;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id, title, author, publisher, coverUrl, readPercent, expyDttm, borDttm, accessDate,"
                    + " downloadStatus, libraryName, fileType FROM book_record"
                    + " WHERE IFNULL(isDelete,0)=0 ORDER BY IFNULL(accessDate,'') DESC, IFNULL(borDttm,'') DESC", null);
            sb.append("{\"updated\":").append(System.currentTimeMillis()).append(",\"books\":[");
            while (c.moveToNext()) {
                long id = c.getLong(0);
                File cover = new File(covers, "kyobo-" + id + ".jpg");
                String coverUrl = c.getString(4);
                if (!cover.isFile() && coverUrl != null && coverUrl.startsWith("http")) fetch(coverUrl, cover);
                if (n++ > 0) sb.append(',');
                sb.append("{\"id\":").append(id)
                  .append(",\"title\":").append(q(c.getString(1)))
                  .append(",\"author\":").append(q(c.getString(2)))
                  .append(",\"publisher\":").append(q(c.getString(3)))
                  .append(",\"cover\":").append(q(cover.isFile() ? cover.getPath() : null))
                  .append(",\"percent\":").append(c.isNull(5) ? 0 : c.getInt(5))
                  .append(",\"due\":").append(q(c.getString(6)))
                  .append(",\"borrowed\":").append(q(c.getString(7)))
                  .append(",\"access\":").append(q(c.getString(8)))
                  .append(",\"downloaded\":").append(!c.isNull(9) && c.getInt(9) == 3)
                  .append(",\"library\":").append(q(c.getString(10)))
                  .append(",\"open\":").append(q("kyobolibrarykel://crema-open/" + id))
                  .append('}');
            }
            sb.append("]}");
        } finally {
            if (c != null) c.close();
            db.close();
        }
        File d = dir();
        d.mkdirs();
        File tmp = new File(d, ".kyobo_shelf.json.tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        fo.write(sb.toString().getBytes("UTF-8"));
        fo.close();
        tmp.renameTo(new File(d, "kyobo_shelf.json"));
        Log.i(TAG, "exported " + n + " book(s)");
    }

    private static void fetch(String url, File out) {
        HttpURLConnection h = null;
        try {
            out.getParentFile().mkdirs();
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(15000);
            if (h.getResponseCode() != 200) return;
            String ct = h.getContentType();
            if (ct != null && !ct.startsWith("image/")) return;
            InputStream in = h.getInputStream();
            File tmp = new File(out.getPath() + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0) fo.write(b, 0, r);
            fo.close();
            in.close();
            tmp.renameTo(out);
        } catch (Throwable t) {
            Log.w(TAG, "cover " + url + ": " + t);
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------- open a book from outside

    /** @return true when the link was ours (the activity is finished by us) */
    public static boolean applink(final Activity a) {
        try {
            Intent in = a.getIntent();
            Uri u = in == null ? null : in.getData();
            if (u == null || !"crema-open".equals(u.getHost())) return false;
            final long id = Long.parseLong(u.getLastPathSegment());
            final Handler main = new Handler(Looper.getMainLooper());
            // ApplinkActivity (AppCompat, no AppCompat theme) must finish inside onCreate, as the
            // original does; the viewer is started with FLAG_ACTIVITY_NEW_TASK, so the
            // application context is enough.
            final Context app = a.getApplicationContext();
            a.finish();
            new Thread(new Runnable() {
                public void run() {
                    final Object rec = record(id);
                    main.post(new Runnable() {
                        public void run() {
                            try {
                                if (rec == null) {
                                    android.widget.Toast.makeText(app, "책을 찾을 수 없습니다", android.widget.Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                Class<?> h = Class.forName("kr.co.kyobobook.KEL.d.c.b.h");
                                Object opener = h.getMethod("q").invoke(null);
                                Method open = h.getMethod("a", Context.class, rec.getClass(), boolean.class);
                                open.invoke(opener, app, rec, false);
                            } catch (Throwable t) {
                                Log.w(TAG, "open " + id, t);
                            }
                        }
                    });
                }
            }, "crema-open").start();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "applink", t);
            return false;
        }
    }

    private static Object record(long id) {
        try {
            Class<?> dbc = Class.forName("kr.co.kyobobook.KEL.room.AppDatabase");
            Object db = dbc.getMethod("x").invoke(null);
            Object dao = dbc.getMethod("o").invoke(db);
            Method get = null;
            for (Method m : dao.getClass().getMethods())
                if (m.getName().equals("a") && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == long.class
                        && m.getReturnType().getName().equals("kr.co.kyobobook.KEL.room.a.a")) get = m;
            return get == null ? null : get.invoke(dao, id);
        } catch (Throwable t) {
            Log.w(TAG, "record " + id, t);
            return null;
        }
    }
}
