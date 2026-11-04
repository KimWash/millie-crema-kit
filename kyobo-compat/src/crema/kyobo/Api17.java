package crema.kyobo;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Stand-ins for API 18/19 members that 교보도서관 1.2.0 (minSdk 19) calls without a version check.
 * build.sh rewrites those call sites in the app's smali to these static methods/fields.
 */
public final class Api17 {
    private Api17() {}

    // java.nio.charset.StandardCharsets (API 19)
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final Charset US_ASCII = Charset.forName("US-ASCII");
    public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    public static final Charset UTF_16 = Charset.forName("UTF-16");
    public static final Charset UTF_16BE = Charset.forName("UTF-16BE");
    public static final Charset UTF_16LE = Charset.forName("UTF-16LE");

    // java.util.Objects (API 19)
    public static boolean equals(Object a, Object b) { return a == b || (a != null && a.equals(b)); }
    public static int hash(Object... values) { return Arrays.hashCode(values); }
    public static int hashCode(Object o) { return o == null ? 0 : o.hashCode(); }
    public static String toString(Object o) { return String.valueOf(o); }
    public static <T> T requireNonNull(T o) { if (o == null) throw new NullPointerException(); return o; }
    public static <T> T requireNonNull(T o, String msg) { if (o == null) throw new NullPointerException(msg); return o; }

    // android.os.StatFs *Long (API 18)
    @SuppressWarnings("deprecation")
    public static long getBlockSizeLong(StatFs s) { return s.getBlockSize(); }
    @SuppressWarnings("deprecation")
    public static long getAvailableBlocksLong(StatFs s) { return s.getAvailableBlocks(); }
    @SuppressWarnings("deprecation")
    public static long getBlockCountLong(StatFs s) { return s.getBlockCount(); }
    @SuppressWarnings("deprecation")
    public static long getFreeBlocksLong(StatFs s) { return s.getFreeBlocks(); }
    public static long getAvailableBytes(StatFs s) { return getAvailableBlocksLong(s) * getBlockSizeLong(s); }
    public static long getTotalBytes(StatFs s) { return getBlockCountLong(s) * getBlockSizeLong(s); }
    public static long getFreeBytes(StatFs s) { return getFreeBlocksLong(s) * getBlockSizeLong(s); }

    /**
     * The epub viewer parses "Chrome/NN." out of the WebView user agent (KitKat+ WebViews are
     * Chromium) and crashes on 4.2's pre-Chromium WebView. Append a KitKat-level token if missing.
     */
    public static String getUserAgentString(android.webkit.WebSettings s) {
        String ua = s.getUserAgentString();
        if (ua == null) ua = "";
        return ua.indexOf("Chrome/") >= 0 ? ua : ua + " Chrome/30.0.0.0";
    }

    // android.content.Context#getExternalFilesDirs (API 19)
    public static File[] getExternalFilesDirs(Context c, String type) {
        File f = c.getExternalFilesDir(type);
        return f == null ? new File[0] : new File[] { f };
    }
}
