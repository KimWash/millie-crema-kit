package kr.co.crema.home;

import java.text.SimpleDateFormat;
import java.util.Locale;

/** RFC 3339 (Atom) and RFC 822 (RSS) dates; returns 0 when unparseable. */
final class Dates {
    private static final String[] FORMATS = {
            "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ss",
            "EEE, dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd",
    };

    static long parse(String s) {
        if (s == null || s.length() == 0) return 0;
        String v = s.trim();
        if (v.endsWith("Z")) v = v.substring(0, v.length() - 1) + "+0000";
        // "+09:00" -> "+0900" for SimpleDateFormat's Z
        if (v.length() > 6 && v.charAt(v.length() - 3) == ':' && (v.charAt(v.length() - 6) == '+' || v.charAt(v.length() - 6) == '-'))
            v = v.substring(0, v.length() - 3) + v.substring(v.length() - 2);
        v = v.replace("GMT", "+0000").replace("UTC", "+0000");
        for (String f : FORMATS) {
            try {
                return new SimpleDateFormat(f, Locale.US).parse(v).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
