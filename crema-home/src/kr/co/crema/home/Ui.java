package kr.co.crema.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseArray;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.BitSet;

/**
 * Drawing toolkit. Every coordinate is a device pixel taken straight from the
 * Figma frames (758x1024 design == 758x1024 panel), so nothing is scaled.
 * Paints, paths and rects are allocated once and reused on every frame.
 */
final class Ui {
    static final int INK = 0xFF111111, TEXT2 = 0xFF555555, TEXT3 = 0xFF333333,
            BORDER = 0xFFBBBBBB, LINE = 0xFFDDDDDD, SURFACE = 0xFFEEEEEE, WHITE = 0xFFFFFFFF;
    static final int R = 0, M = 1, SB = 2, B = 3; // weights

    static final int LEFT = 0, CENTER = 1, RIGHT = 2;

    private final Typeface[] faces = new Typeface[4];
    private final Typeface[] fallback = new Typeface[4];
    private final SparseArray<Paint> textPaints = new SparseArray<Paint>();
    private final BitSet glyphs = new BitSet(0x10000);
    private final Paint fill = new Paint();
    private final Paint fillAa = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rf = new RectF();
    private final Path path = new Path();
    private final Context ctx;
    private final SparseArray<Bitmap> images = new SparseArray<Bitmap>();

    Ui(Context c) {
        ctx = c;
        String[] files = {"p-Regular", "p-Medium", "p-SemiBold", "p-Bold"};
        for (int i = 0; i < 4; i++) {
            try {
                faces[i] = Typeface.createFromAsset(c.getAssets(), "fonts/" + files[i] + ".otf");
            } catch (Throwable t) {
                faces[i] = null;
            }
            fallback[i] = Typeface.create(Typeface.SANS_SERIF, i >= SB ? Typeface.BOLD : Typeface.NORMAL);
            if (faces[i] == null) faces[i] = fallback[i];
        }
        try {
            InputStream in = c.getAssets().open("fonts/chars.txt");
            Reader r = new InputStreamReader(in, "UTF-8");
            int ch;
            while ((ch = r.read()) >= 0) glyphs.set(ch);
            r.close();
        } catch (Exception ignored) {
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    // ---------------------------------------------------------------- text

    Paint font(int weight, int size) {
        int key = weight * 1000 + size;
        Paint p = textPaints.get(key);
        if (p == null) {
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(faces[weight]);
            p.setTextSize(size);
            p.setHinting(Paint.HINTING_ON);
            textPaints.put(key, p);
        }
        return p;
    }

    /** True when every char of s exists in the subset font; otherwise use system font. */
    private boolean covered(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char ch = s.charAt(i);
            if (ch >= 0x20 && !glyphs.get(ch)) return false;
        }
        return true;
    }

    private Paint prep(int weight, int size, int color, String s) {
        Paint p = font(weight, size);
        p.setTypeface(covered(s) ? faces[weight] : fallback[weight]);
        p.setColor(color);
        return p;
    }

    float width(int weight, int size, String s) {
        return prep(weight, size, INK, s).measureText(s);
    }

    /**
     * Draws a single line. yTop is the top of a line box of height lh (Figma
     * "line height"); lh <= 0 means "normal" (ascent..descent).
     * Returns the drawn width.
     */
    float text(Canvas c, String s, int weight, int size, int color, float x, float yTop, float lh, int align, float maxW) {
        if (s == null || s.length() == 0) return 0;
        Paint p = prep(weight, size, color, s);
        if (maxW > 0 && p.measureText(s) > maxW) s = ellipsize(p, s, maxW);
        Paint.FontMetrics fm = p.getFontMetrics();
        float content = fm.descent - fm.ascent;
        float base = lh > 0 ? yTop + (lh - content) / 2f - fm.ascent : yTop - fm.ascent;
        float w = p.measureText(s);
        float dx = align == CENTER ? x - w / 2f : align == RIGHT ? x - w : x;
        c.drawText(s, dx, base, p);
        return w;
    }

    private static String ellipsize(Paint p, String s, float maxW) {
        float ell = p.measureText("…");
        int n = p.breakText(s, true, Math.max(0, maxW - ell), null);
        while (n > 0 && s.charAt(n - 1) == ' ') n--;
        return s.substring(0, n) + "…";
    }

    /** Wraps text into at most maxLines lines; last line is ellipsized. */
    int textBlock(Canvas c, String s, int weight, int size, int color, float x, float yTop, float lh, float maxW, int maxLines, int align) {
        Paint p = prep(weight, size, color, s);
        int start = 0, line = 0, n = s.length();
        while (start < n && line < maxLines) {
            int nl = s.indexOf('\n', start);
            int end = nl < 0 ? n : nl;
            String seg = s.substring(start, end);
            int fit = p.breakText(seg, true, maxW, null);
            if (fit < seg.length()) {
                int sp = seg.lastIndexOf(' ', fit);
                if (sp > 0) fit = sp;
                end = start + fit;
            }
            String out = s.substring(start, end).trim();
            if (line == maxLines - 1 && end < n) out = ellipsize(p, s.substring(start).replace('\n', ' '), maxW);
            float ax = align == CENTER ? x + maxW / 2f : x;
            text(c, out, weight, size, color, ax, yTop + line * lh, lh, align, 0);
            line++;
            start = end;
            while (start < n && (s.charAt(start) == ' ' || s.charAt(start) == '\n')) start++;
        }
        return line;
    }

    // ---------------------------------------------------------------- shapes

    void rect(Canvas c, float l, float t, float r, float b, int color) {
        fill.setColor(color);
        c.drawRect(l, t, r, b, fill);
    }

    void hline(Canvas c, float y, float l, float r, int color) {
        rect(c, l, y, r, y + 1, color);
    }

    void vline(Canvas c, float x, float t, float b, int color) {
        rect(c, x, t, x + 1, b, color);
    }

    void round(Canvas c, float l, float t, float r, float b, float radius, int color) {
        fillAa.setColor(color);
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, radius, radius, fillAa);
    }

    /** Stroke drawn inside the given bounds, like Figma's "inside" border. */
    void outline(Canvas c, float l, float t, float r, float b, float radius, float w, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(w);
        float h = w / 2f;
        rf.set(l + h, t + h, r - h, b - h);
        if (radius <= 0) c.drawRect(rf, stroke);
        else c.drawRoundRect(rf, radius - h, radius - h, stroke);
    }

    private void line(Canvas c, float w, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(w);
        c.drawPath(path, stroke);
    }

    void chevronRight(Canvas c, float x, float y, int color) { // 20x20 box
        path.reset();
        path.moveTo(x + 7.5f, y + 4.5f);
        path.lineTo(x + 13f, y + 10f);
        path.lineTo(x + 7.5f, y + 15.5f);
        line(c, 2f, color);
    }

    void chevronLeft(Canvas c, float x, float y, int color) {
        path.reset();
        path.moveTo(x + 12.5f, y + 4.5f);
        path.lineTo(x + 7f, y + 10f);
        path.lineTo(x + 12.5f, y + 15.5f);
        line(c, 2f, color);
    }

    void plus(Canvas c, float x, float y, int color) {
        path.reset();
        path.moveTo(x + 4, y + 10);
        path.lineTo(x + 16, y + 10);
        path.moveTo(x + 10, y + 4);
        path.lineTo(x + 10, y + 16);
        line(c, 2f, color);
    }

    void cross(Canvas c, float x, float y, int color) { // 20x20 box
        path.reset();
        path.moveTo(x + 5, y + 5);
        path.lineTo(x + 15, y + 15);
        path.moveTo(x + 15, y + 5);
        path.lineTo(x + 5, y + 15);
        line(c, 2f, color);
    }

    void minus(Canvas c, float x, float y, int color) {
        path.reset();
        path.moveTo(x + 4, y + 10);
        path.lineTo(x + 16, y + 10);
        line(c, 2f, color);
    }

    /** Wi-Fi glyph in a 26x20 box. state: 0 off, 1 on but not connected, 2 connected. */
    void wifi(Canvas c, float x, float y, int state) {
        float cx = x + 13, cy = y + 18;
        int arcColor = state == 2 ? INK : BORDER;
        stroke.setStrokeWidth(2.2f);
        stroke.setColor(arcColor);
        for (int i = 0; i < 3; i++) {
            float r = 5 + i * 5.2f;
            rf.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rf, 225, 90, false, stroke);
        }
        fillAa.setColor(state == 0 ? BORDER : INK);
        c.drawCircle(cx, cy - 0.5f, 1.8f, fillAa);
        if (state == 0) {
            path.reset();
            path.moveTo(x + 4, y + 1);
            path.lineTo(x + 22, y + 19);
            line(c, 2f, INK);
        }
    }

    /** Battery glyph in a 28x16 box. */
    void battery(Canvas c, float x, float y, int level, boolean charging) {
        outline(c, x, y, x + 25, y + 16, 3, 1.5f, INK);
        rect(c, x + 25.5f, y + 5, x + 28, y + 11, INK);
        float inner = 25 - 6;
        float w = Math.max(0, Math.min(1, level / 100f)) * inner;
        if (w > 0) rect(c, x + 3, y + 3, x + 3 + w, y + 13, INK);
        if (charging) {
            path.reset();
            path.moveTo(x + 14, y + 2);
            path.lineTo(x + 9.5f, y + 8.5f);
            path.lineTo(x + 13.5f, y + 8.5f);
            path.lineTo(x + 11.5f, y + 14);
            path.lineTo(x + 16.5f, y + 7);
            path.lineTo(x + 12.5f, y + 7);
            path.close();
            fillAa.setColor(WHITE);
            stroke.setColor(WHITE);
            stroke.setStrokeWidth(2.5f);
            c.drawPath(path, stroke);
            fillAa.setColor(INK);
            c.drawPath(path, fillAa);
        }
    }

    /** Sun glyph in a 22x22 box. */
    void sun(Canvas c, float x, float y) {
        float cx = x + 11, cy = y + 11;
        stroke.setColor(INK);
        stroke.setStrokeWidth(1.6f);
        c.drawCircle(cx, cy, 4.5f, stroke);
        path.reset();
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            path.moveTo(cx + cs * 7.5f, cy + sn * 7.5f);
            path.lineTo(cx + cs * 10f, cy + sn * 10f);
        }
        line(c, 1.6f, INK);
    }

    /** Status-bar light level: 5 squares, 12px, gap 4. */
    void levelSquares(Canvas c, float x, float y, int level) {
        for (int i = 0; i < 5; i++) {
            float l = x + i * 16;
            if (i < level) rect(c, l, y, l + 12, y + 12, INK);
            else outline(c, l, y, l + 12, y + 12, 0, 1.5f, INK);
        }
    }

    /** Figma "Switch" 56x32. */
    void toggle(Canvas c, float x, float y, boolean on) {
        if (on) {
            round(c, x, y, x + 56, y + 32, 16, INK);
            fillAa.setColor(WHITE);
            c.drawCircle(x + 40, y + 16, 12, fillAa);
        } else {
            round(c, x, y, x + 56, y + 32, 16, WHITE);
            outline(c, x, y, x + 56, y + 32, 16, 1.5f, BORDER);
            fillAa.setColor(BORDER);
            c.drawCircle(x + 17.5f, y + 16, 12, fillAa);
        }
    }

    /** Outlined 8px progress used on Home. */
    void progressBold(Canvas c, float l, float t, float r, float frac) {
        frac = Math.max(0, Math.min(1, frac));
        c.save();
        rf.set(l, t, r, t + 8);
        path.reset();
        path.addRoundRect(rf, 4, 4, Path.Direction.CW);
        c.clipPath(path);
        rect(c, l, t, r, t + 8, WHITE);
        rect(c, l, t, l + (r - l) * frac, t + 8, INK);
        c.restore();
        outline(c, l, t, r, t + 8, 4, 1, INK);
    }

    /** Thin 4px progress used in the library grid. */
    void progressThin(Canvas c, float l, float t, float r, float frac) {
        frac = Math.max(0, Math.min(1, frac));
        round(c, l, t, r, t + 4, 2, LINE);
        if (frac > 0) round(c, l, t, l + Math.max(4, (r - l) * frac), t + 4, 2, INK);
    }

    void errorMark(Canvas c, float x, float y) { // 16x16
        fillAa.setColor(INK);
        c.drawCircle(x + 8, y + 8, 7, fillAa);
        rect(c, x + 7, y + 3.5f, x + 9, y + 9.5f, WHITE);
        rect(c, x + 7, y + 10.5f, x + 9, y + 12.5f, WHITE);
    }

    // ---------------------------------------------------------------- bitmaps

    static final int IMG_CAT_LARGE = 1, IMG_CAT_HOME = 2, IMG_CAT_GRID = 3, IMG_CAT_MINI = 4,
            IMG_DOODLE = 5, IMG_GEAR = 6, IMG_MILLIE46 = 7, IMG_MILLIE33 = 8;
    private static final String[] IMG_NAMES = {null, "cat_large", "cat_home", "cat_grid", "cat_mini",
            "doodle", "gear", "millie46", "millie33"};

    /** Bundled art is pre-composited 16-gray; decoded as RGB_565 (no alpha). */
    Bitmap image(int id) {
        Bitmap b = images.get(id);
        if (b == null && (id == IMG_MILLIE46 || id == IMG_MILLIE33)) {
            b = millieIcon(id == IMG_MILLIE46 ? 46 : 33);
            if (b != null) images.put(id, b);
            return b;
        }
        if (b == null) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inScaled = false;
                InputStream in = ctx.getAssets().open("img/" + IMG_NAMES[id] + ".png");
                b = BitmapFactory.decodeStream(in, null, o);
                in.close();
            } catch (Exception e) {
                b = null;
            }
            if (b != null) images.put(id, b);
        }
        return b;
    }

    /**
     * Millie's icon is not bundled (it is Millie's artwork): it is taken from the
     * installed app once, flattened onto white and reduced to 16 grays.
     */
    private Bitmap millieIcon(int size) {
        try {
            android.graphics.drawable.Drawable d = ctx.getPackageManager().getApplicationIcon(Apps.MILLIE);
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(WHITE);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            Bitmap g = out.copy(Bitmap.Config.RGB_565, true);
            out.recycle();
            Covers.toGray16(g);
            return g;
        } catch (Exception e) {
            return null; // Millie not installed: the tile shows its letter instead
        }
    }

    void drawImage(Canvas c, int id, float cx, float cy) {
        Bitmap b = image(id);
        if (b != null) c.drawBitmap(b, Math.round(cx - b.getWidth() / 2f), Math.round(cy - b.getHeight() / 2f), null);
    }

    void drawBitmap(Canvas c, Bitmap b, float l, float t) {
        c.drawBitmap(b, l, t, bmp);
    }
}
