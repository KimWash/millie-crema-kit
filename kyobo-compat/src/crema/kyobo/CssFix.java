package crema.kyobo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSS fallbacks for Android 4.2 WebKit (534.30):
 *  - transform / transform-origin / transition / animation: add the -webkit- form before the standard one
 *  - display:flex: add display:-webkit-box (2009 flexbox) plus the matching box-orient/pack/align/flex,
 *    except for rules that wrap (flex-wrap:wrap), which -webkit-box cannot do; those stay block.
 * Also used on CSS text inside JS strings (inline style="" built by scripts).
 */
final class CssFix {
    private CssFix() {}

    private static final Pattern PREFIX = Pattern.compile(
        "(^|[;{\\s\"'])(transform|transform-origin|transition|animation|box-sizing|user-select)\\s*:\\s*([^;}\"']*)");
    private static final Pattern RULE = Pattern.compile("\\{([^{}]*)\\}");

    static String fixCss(String css) {
        Matcher m = RULE.matcher(css);
        StringBuffer sb = new StringBuffer(css.length() + 1024);
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("{" + fixDecls(m.group(1)) + "}"));
        m.appendTail(sb);
        return sb.toString();
    }

    private static final Pattern CALC_DECL = Pattern.compile(
        "(^|[;{\\s])([a-z-]+)\\s*:\\s*([^;}]*?calc\\(([^;}]*)\\)[^;}]*?)(\\s*!important)?(?=\\s*(;|\\}|$))");
    private static final Pattern PCT_PM_PX = Pattern.compile("\\s*([0-9.]+)%\\s*[-+]\\s*[0-9.]+(px|em|rem)?\\s*");
    private static final Pattern PCT_DIV = Pattern.compile("\\s*([0-9.]+)%\\s*([/*])\\s*([0-9.]+)\\s*");

    /**
     * Old WebKit drops any declaration that uses calc(). For height-like properties put a fallback in
     * front: calc(P% ...) -> auto / none. This matters when the calc rule is !important and meant to
     * override an inline height (jQuery UI dialog content is sized to 0 first, then by this rule).
     */
    static String calcFallback(String d) {
        if (d.indexOf("calc(") < 0) return d;
        Matcher m = CALC_DECL.matcher(d);
        StringBuffer sb = new StringBuffer(d.length() + 64);
        while (m.find()) {
            String prop = m.group(2), inner = m.group(4), imp = m.group(5) == null ? "" : m.group(5);
            String fb = null;
            Matcher a = PCT_DIV.matcher(inner);
            Matcher b = PCT_PM_PX.matcher(inner);
            // Only heights: widths approximated as P% made the page wider than the screen (search box,
            // sliders), which looked worse than old WebKit simply dropping the declaration.
            boolean heightLike = prop.equals("height") || prop.equals("min-height") || prop.equals("max-height");
            if (heightLike && (a.matches() || b.matches())) fb = prop.equals("max-height") ? "none" : "auto";
            String all = m.group(0);
            if (fb != null && !prop.startsWith("-")) {
                String lead = m.group(1);
                m.appendReplacement(sb, Matcher.quoteReplacement(lead + prop + ":" + fb + imp + ";" + all.substring(lead.length())));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(all));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** a declaration list (rule body or style="" value) */
    static String fixDecls(String d) {
        String out = calcFallback(prefix(d));
        String low = d.toLowerCase();
        if (low.matches("(?s).*display\\s*:\\s*(-webkit-)?flex.*") && !low.matches("(?s).*flex-wrap\\s*:\\s*wrap.*")) {
            StringBuilder box = new StringBuilder("display:-webkit-box;");
            if (low.matches("(?s).*flex-direction\\s*:\\s*column.*")) box.append("-webkit-box-orient:vertical;");
            String jc = value(low, "justify-content");
            if (jc != null) {
                String v = jc.startsWith("center") ? "center" : jc.contains("end") ? "end" : jc.contains("between") || jc.contains("around") || jc.contains("evenly") ? "justify" : "start";
                box.append("-webkit-box-pack:").append(v).append(';');
            }
            String ai = value(low, "align-items");
            if (ai != null) {
                String v = ai.startsWith("center") ? "center" : ai.contains("end") ? "end" : ai.startsWith("baseline") ? "baseline" : ai.startsWith("stretch") ? "stretch" : "start";
                box.append("-webkit-box-align:").append(v).append(';');
            }
            out = box + out;   // the standard display:flex later in the list is ignored by old WebKit
        }
        String flex = value(low, "flex");
        if (flex != null && flex.matches("[0-9]+.*")) out = "-webkit-box-flex:" + flex.split("\\s+")[0] + ";" + out;
        return out;
    }

    private static String prefix(String d) {
        Matcher m = PREFIX.matcher(d);
        StringBuffer sb = new StringBuffer(d.length() + 64);
        while (m.find()) {
            String v = m.group(3).trim();
            m.appendReplacement(sb, Matcher.quoteReplacement(
                m.group(1) + "-webkit-" + m.group(2) + ":" + v.replace("transform", "-webkit-transform") + ";" + m.group(2) + ":" + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String value(String low, String prop) {
        Matcher m = Pattern.compile("(?:^|[;{\\s])" + Pattern.quote(prop) + "\\s*:\\s*([^;}]*)").matcher(low);
        return m.find() ? m.group(1).trim() : null;
    }

    // ---- inside JS source

    private static final Pattern JS_DISPLAY_FLEX = Pattern.compile("(['\"]display['\"]\\s*[,:]\\s*)(['\"])(?:-webkit-)?flex\\2");
    private static final Pattern JS_DISPLAY_FLEX_OBJ = Pattern.compile("(\\bdisplay\\s*:\\s*)(['\"])(?:-webkit-)?flex\\2");

    /** jQuery .css('display','flex') / {display:'flex'} would be ignored -> element stays hidden */
    static String fixJs(String js) {
        String s = JS_DISPLAY_FLEX.matcher(js).replaceAll("$1$2-webkit-box$2");
        s = JS_DISPLAY_FLEX_OBJ.matcher(s).replaceAll("$1$2-webkit-box$2");
        // inline style text built in strings, e.g. "...top:50%;left:50%; transform: translate(-50%, -50%);..."
        if (s.indexOf("transform") >= 0) {
            Matcher m = Pattern.compile("([;\"'\\s])transform\\s*:\\s*(translate[^;\"']*)").matcher(s);
            StringBuffer sb = new StringBuffer(s.length() + 64);
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "-webkit-transform:" + m.group(2) + ";transform:" + m.group(2)));
            m.appendTail(sb);
            s = sb.toString();
        }
        return s;
    }
}
