package kr.co.crema.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Better "last read" times for books from other reader apps. 교보 only stores the day it was last
 * opened and 밀리 updates readAt only when it syncs, so on their own they can't say which book was
 * read last. crema-home remembers, per book:
 *  - when it last saw the progress (%) change (it re-reads the shelves every time home comes back,
 *    i.e. right after reading), and
 *  - when the book was opened from home.
 * A book's time is the latest of those and the app's own time. KOReader books are left as they are
 * (its history already has exact times).
 */
final class Recency {
    private final SharedPreferences p;

    Recency(Context c) {
        p = c.getSharedPreferences("recency", Context.MODE_PRIVATE);
    }

    /** IO thread. Updates b.time in place for books with a source (교보, 밀리). */
    void apply(List<Koreader.Book> books) {
        long now = System.currentTimeMillis() / 1000;
        SharedPreferences.Editor e = null;
        for (Koreader.Book b : books) {
            if (b.source == null) continue;
            String pk = "pct:" + b.path, tk = "chg:" + b.path;
            int pct = (int) Math.round(b.pct * 1000);
            long changed = p.getLong(tk, 0), opened = p.getLong("open:" + b.path, 0);
            if (!p.contains(pk)) {
                if (e == null) e = p.edit();
                e.putInt(pk, pct);                     // first sight: remember, don't move it up
            } else if (p.getInt(pk, pct) != pct) {
                if (e == null) e = p.edit();
                e.putInt(pk, pct).putLong(tk, now);    // progress moved since last look: read just now
                changed = now;
            }
            b.time = Math.max(b.time, Math.max(changed, opened));
        }
        if (e != null) e.commit();
    }

    /** UI thread: the book was opened from home. */
    void opened(Koreader.Book b) {
        if (b.source != null) p.edit().putLong("open:" + b.path, System.currentTimeMillis() / 1000).commit();
    }
}
