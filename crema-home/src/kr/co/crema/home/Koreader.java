package kr.co.crema.home;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

/**
 * Reads KOReader's own files (history.lua, per-book .sdr sidecars,
 * statistics.sqlite3). Runs on the IO thread only. Every file is re-parsed
 * only when its mtime changes, so a resume with nothing new costs a few stat()s.
 */
final class Koreader {
    static final String PKG = "org.koreader.launcher";

    static final class Book {
        String path, title, authors;
        double pct;
        int pages;
        long time;
        boolean done;

        int page() {
            return pages <= 0 ? 0 : Math.max(1, (int) Math.round(pct * pages));
        }
    }

    static final class Stats {
        int todayMin, streakDays;
    }

    private final File root = new File(Environment.getExternalStorageDirectory(), "koreader");
    private final File cacheDir;
    private long historyMtime = -1;
    private List<String[]> history = new ArrayList<String[]>(); // {path, time}
    private final HashMap<String, Object[]> sidecarCache = new HashMap<String, Object[]>(); // path -> {mtime, Book}
    private long statsMtime = -1;
    private int statsDay = -1;
    private Stats stats = new Stats();

    Koreader(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    boolean installedDataDir() {
        return root.isDirectory();
    }

    /** Books in history order (most recent first), files that no longer exist are skipped. */
    List<Book> books() {
        File h = new File(root, "history.lua");
        long m = h.lastModified();
        if (m != historyMtime) {
            history = parseHistory(h);
            historyMtime = m;
        }
        ArrayList<Book> out = new ArrayList<Book>();
        for (String[] e : history) {
            File f = new File(e[0]);
            if (!f.isFile()) continue;
            Book b = book(f);
            b.time = Long.parseLong(e[1]);
            out.add(b);
        }
        return out;
    }

    private Book book(File f) {
        File side = sidecar(f);
        long m = side == null ? 0 : side.lastModified();
        Object[] cached = sidecarCache.get(f.getPath());
        if (cached != null && (Long) cached[0] == m) return (Book) cached[1];
        Book b = new Book();
        b.path = f.getPath();
        if (side != null) {
            HashMap<String, String> kv = parseLua(side);
            b.title = kv.get("doc_props.title");
            if (empty(b.title)) b.title = kv.get("stats.title");
            b.authors = kv.get("doc_props.authors");
            if (empty(b.authors)) b.authors = kv.get("stats.authors");
            if ("N/A".equals(b.authors)) b.authors = null;
            b.pct = num(kv.get("percent_finished"));
            int pages = (int) num(kv.get("doc_pages"));
            if (pages <= 0) pages = (int) num(kv.get("stats.pages"));
            b.pages = pages;
            b.done = "complete".equals(kv.get("summary.status")) || b.pct >= 0.999;
        }
        if (empty(b.title)) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            b.title = dot > 0 ? n.substring(0, dot) : n;
        }
        if (b.authors != null) b.authors = b.authors.replace("\n", ", ").trim();
        sidecarCache.put(f.getPath(), new Object[]{m, b});
        return b;
    }

    /** KOReader keeps metadata next to the book (default) or under koreader/docsettings. */
    private File sidecar(File book) {
        String name = book.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
        File[] dirs = {
                new File(book.getParentFile(), base + ".sdr"),
                new File(new File(root, "docsettings"), book.getParent() + "/" + base + ".sdr"),
        };
        for (File d : dirs) {
            File exact = new File(d, "metadata." + ext + ".lua");
            if (exact.isFile()) return exact;
            String[] list = d.list();
            if (list != null) for (String s : list)
                if (s.startsWith("metadata.") && s.endsWith(".lua")) return new File(d, s);
        }
        return null;
    }

    private static List<String[]> parseHistory(File f) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        HashMap<String, String> cur = null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 4096);
            String line;
            String file = null, time = null;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("[\"file\"]")) file = luaString(line);
                else if (line.startsWith("[\"time\"]")) time = luaValue(line);
                else if (line.startsWith("}") && file != null) {
                    out.add(new String[]{file, time == null ? "0" : time});
                    file = null;
                    time = null;
                }
            }
        } catch (Exception ignored) {
        } finally {
            close(r);
        }
        Collections.sort(out, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return Long.valueOf(b[1]).compareTo(Long.valueOf(a[1]));
            }
        });
        return out;
    }

    /**
     * Flat "table.key -> value" view of a KOReader Lua table, enough for the
     * few scalar fields we need. Nested tables are tracked by name.
     */
    private static HashMap<String, String> parseLua(File f) {
        HashMap<String, String> kv = new HashMap<String, String>();
        ArrayList<String> stack = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 8192);
            String line;
            boolean started = false;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!started) {
                    if (line.startsWith("return")) started = true;
                    continue;
                }
                if (line.startsWith("}")) {
                    if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                    continue;
                }
                if (!line.startsWith("[")) continue;
                int close = line.indexOf(']');
                if (close < 0) continue;
                String key = line.substring(1, close);
                if (key.startsWith("\"")) key = key.substring(1, key.length() - 1);
                String rest = line.substring(close + 1).trim();
                if (!rest.startsWith("=")) continue;
                rest = rest.substring(1).trim();
                if (rest.equals("{")) {
                    stack.add(key);
                    continue;
                }
                if (stack.size() > 1) continue; // only top level and one level deep
                String full = stack.isEmpty() ? key : stack.get(0) + "." + key;
                kv.put(full, rest.startsWith("\"") ? luaString(line) : luaValue(line));
            }
        } catch (Exception ignored) {
        } finally {
            close(r);
        }
        return kv;
    }

    private static String luaValue(String line) {
        int eq = line.indexOf('=');
        String v = line.substring(eq + 1).trim();
        if (v.endsWith(",")) v = v.substring(0, v.length() - 1);
        return v.trim();
    }

    private static String luaString(String line) {
        int eq = line.indexOf('=');
        int q = line.indexOf('"', eq);
        if (q < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Today's reading minutes and consecutive reading days (from KOReader statistics). */
    Stats stats() {
        File src = new File(root, "settings/statistics.sqlite3");
        long m = src.lastModified();
        int today = dayNumber(System.currentTimeMillis() / 1000);
        if (m == statsMtime && today == statsDay) return stats;
        Stats s = new Stats();
        if (m > 0) {
            File copy = new File(cacheDir, "stats.sqlite3");
            SQLiteDatabase db = null;
            Cursor c = null;
            try {
                // read a private copy so we never hold a lock on KOReader's file
                copy(src, copy);
                db = SQLiteDatabase.openDatabase(copy.getPath(), null,
                        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                long midnight = midnight();
                c = db.rawQuery("SELECT IFNULL(SUM(duration),0) FROM page_stat_data WHERE start_time >= " + midnight, null);
                if (c.moveToFirst()) s.todayMin = (int) (c.getLong(0) / 60);
                c.close();
                long off = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
                c = db.rawQuery("SELECT DISTINCT (start_time + " + off + ") / 86400 AS d FROM page_stat_data ORDER BY d DESC LIMIT 400", null);
                int expect = today;
                boolean first = true;
                while (c.moveToNext()) {
                    int d = c.getInt(0);
                    if (first && d == today - 1) expect = today - 1; // streak still alive until today ends
                    first = false;
                    if (d == expect) {
                        s.streakDays++;
                        expect--;
                    } else if (d < expect) break;
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
                if (db != null) db.close();
            }
        }
        stats = s;
        statsMtime = m;
        statsDay = today;
        return s;
    }

    private static int dayNumber(long sec) {
        long off = TimeZone.getDefault().getOffset(sec * 1000) / 1000;
        return (int) ((sec + off) / 86400);
    }

    private static long midnight() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000;
    }

    static void copy(File a, File b) throws Exception {
        FileInputStream in = new FileInputStream(a);
        FileOutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private static double num(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean empty(String s) {
        return s == null || s.trim().length() == 0;
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Exception ignored) {
        }
    }
}
