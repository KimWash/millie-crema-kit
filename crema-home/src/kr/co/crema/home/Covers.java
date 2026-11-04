package kr.co.crema.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cover thumbnails. Each cover is decoded once (subsampled, RGB_565),
 * cropped to the exact slot size, flattened to 16 grays and saved as a tiny
 * PNG in the app cache. After that only the small PNG is ever read.
 * The in-memory map is cleared whenever the visible page changes.
 */
final class Covers {
    interface Listener {
        void onCover();
    }

    private final File dir;
    private final Handler io, ui;
    private final Listener listener;
    private final HashMap<String, Bitmap> mem = new HashMap<String, Bitmap>();
    private final HashSet<String> pending = new HashSet<String>();
    private final HashSet<String> missing = new HashSet<String>();

    Covers(File cacheDir, Handler io, Handler ui, Listener l) {
        dir = new File(cacheDir, "covers");
        dir.mkdirs();
        this.io = io;
        this.ui = ui;
        listener = l;
    }

    /** Called on the UI thread. Returns null while loading or when the book has no cover. */
    Bitmap get(final String path, final int w, final int h) {
        final String key = Integer.toHexString(path.hashCode()) + "_" + new File(path).lastModified() % 100000 + "_" + w + "x" + h;
        Bitmap b = mem.get(key);
        if (b != null || missing.contains(key) || pending.contains(key)) return b;
        pending.add(key);
        io.post(new Runnable() {
            public void run() {
                final Bitmap out = load(path, key, w, h);
                ui.post(new Runnable() {
                    public void run() {
                        pending.remove(key);
                        if (out == null) missing.add(key);
                        else {
                            mem.put(key, out);
                            listener.onCover();
                        }
                    }
                });
            }
        });
        return null;
    }

    void trim() {
        mem.clear(); // let GC take bitmaps of the page we left; disk copies stay
    }

    private Bitmap load(String path, String key, int w, int h) {
        File png = new File(dir, key + ".png");
        File none = new File(dir, key + ".none");
        if (none.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        if (png.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(png.getPath(), o);
            if (b != null) return b;
        }
        Bitmap b = null;
        try {
            String lp = path.toLowerCase();
            if (lp.endsWith(".epub")) b = fromEpub(path, w, h);
            else if (lp.endsWith(".jpg") || lp.endsWith(".jpeg") || lp.endsWith(".png")) b = fromImage(path, w, h);
        } catch (Throwable t) {
            b = null; // includes OutOfMemoryError on a huge image
        }
        try {
            if (b == null) {
                new FileOutputStream(none).close();
                return null;
            }
            FileOutputStream out = new FileOutputStream(png);
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
        } catch (Exception ignored) {
        }
        return b;
    }

    private static final Pattern FULL_PATH = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ITEM = Pattern.compile("<(?:opf:)?item\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("<(?:opf:)?meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static Bitmap fromEpub(String path, int w, int h) throws Exception {
        ZipFile zip = new ZipFile(path);
        try {
            String container = readText(zip, "META-INF/container.xml", 16384);
            if (container == null) return null;
            Matcher m = FULL_PATH.matcher(container);
            if (!m.find()) return null;
            String opfPath = m.group(1);
            String opf = readText(zip, opfPath, 262144);
            if (opf == null) return null;
            String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

            String coverId = null;
            Matcher mm = META.matcher(opf);
            while (mm.find()) {
                String tag = mm.group();
                if ("cover".equals(attr(tag, "name"))) coverId = attr(tag, "content");
            }
            String href = null, fallback = null;
            Matcher im = ITEM.matcher(opf);
            while (im.find()) {
                String tag = im.group();
                String type = attr(tag, "media-type"), id = attr(tag, "id"), hr = attr(tag, "href"), props = attr(tag, "properties");
                if (type == null || !type.startsWith("image/") || hr == null) continue;
                if (props != null && props.contains("cover-image")) {
                    href = hr;
                    break;
                }
                if (coverId != null && coverId.equals(id)) href = hr;
                if (fallback == null && ((id != null && id.toLowerCase().contains("cover")) || hr.toLowerCase().contains("cover")))
                    fallback = hr;
            }
            if (href == null) href = fallback;
            if (href == null) return null;
            String entryName = normalize(base + URLDecoder.decode(href, "UTF-8"));
            ZipEntry e = zip.getEntry(entryName);
            if (e == null) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = zip.getInputStream(e);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inDither = false;
            in = zip.getInputStream(e);
            Bitmap src = BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (src == null) return null;
            Bitmap out = cropTo(src, w, h);
            src.recycle();
            toGray16(out);
            return out;
        } finally {
            zip.close();
        }
    }

    /** A cover that is already an image file (e.g. exported by another reader app). */
    private static Bitmap fromImage(String path, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inDither = false;
        Bitmap src = BitmapFactory.decodeFile(path, o);
        if (src == null) return null;
        Bitmap out = cropTo(src, w, h);
        src.recycle();
        toGray16(out);
        return out;
    }

    /** Center-crop ("object-fit: cover") into a w x h RGB_565 bitmap. */
    private static Bitmap cropTo(Bitmap src, int w, int h) {
        int sw = src.getWidth(), sh = src.getHeight();
        float scale = Math.max(w / (float) sw, h / (float) sh);
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int cx = (sw - cw) / 2, cy = (sh - ch) / 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Luma quantized to the panel's 16 gray levels, so the EPD never has to dither colour. */
    static void toGray16(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int l = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 151 + (p & 0xFF) * 28) >> 8;
                l = ((l + 8) / 17) * 17;
                if (l > 255) l = 255;
                row[x] = 0xFF000000 | (l << 16) | (l << 8) | l;
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + Pattern.quote(name) + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String normalize(String p) {
        String[] parts = p.split("/");
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (String s : parts) {
            if (s.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else if (s.length() > 0 && !s.equals(".")) out.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : out) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    private static String readText(ZipFile zip, String name, int max) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        InputStream in = zip.getInputStream(e);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
