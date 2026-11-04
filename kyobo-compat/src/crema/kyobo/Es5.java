package crema.kyobo;

import java.util.ArrayList;

/**
 * Small source-to-source pass that turns the ES2015 syntax used by Kyobo's elibrary-front
 * scripts into ES5 that Android 4.2's WebKit (534.30) can parse:
 *   arrow functions, template literals, let/const.
 * It is a tokenizer, not a full parser: `this` inside former arrow functions is not rebound,
 * and block scoping of let/const is lost (same as the bridge's own let/const -> var rewrite).
 * Anything it does not understand is left as is.
 */
final class Es5 {
    private Es5() {}

    // token kinds
    static final int WS = 0, COMMENT = 1, STR = 2, TPL = 3, REGEX = 4, IDENT = 5, NUM = 6, PUNCT = 7;

    static final class Tok {
        final int kind;
        String text;
        Tok(int kind, String text) { this.kind = kind; this.text = text; }
        public String toString() { return text; }
    }

    static boolean needs(String s) {
        return s.indexOf("=>") >= 0 || s.indexOf('`') >= 0 || s.indexOf("let ") >= 0 || s.indexOf("const ") >= 0;
    }

    static String transform(String src) {
        if (!needs(src)) return src;
        ArrayList<Tok> t = tokenize(src);
        if (t == null) return src;
        rewriteSimple(t);
        // arrows: one at a time, left to right, until none are left (nested ones get picked up later)
        for (int guard = 0; guard < 2000; guard++) {
            if (!rewriteFirstArrow(t)) break;
        }
        StringBuilder sb = new StringBuilder(src.length() + 256);
        for (Tok k : t) sb.append(k.text);
        return sb.toString();
    }

    // ---------------------------------------------------------------- tokenizer

    private static final String REGEX_PREV_KEYWORDS = " return typeof case do else in instanceof new delete void throw yield ";

    static ArrayList<Tok> tokenize(String s) {
        ArrayList<Tok> out = new ArrayList<Tok>(s.length() / 3);
        int n = s.length(), i = 0;
        Tok lastSig = null;
        while (i < n) {
            char c = s.charAt(i);
            int st = i;
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' ' || c == '﻿' || c == ' ' || c == ' ') {
                while (i < n && Character.isWhitespace(s.charAt(i)) || i < n && (s.charAt(i) == ' ' || s.charAt(i) == '﻿')) i++;
                out.add(new Tok(WS, s.substring(st, i)));
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
                out.add(new Tok(COMMENT, s.substring(st, i)));
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                out.add(new Tok(COMMENT, s.substring(st, i)));
                continue;
            }
            if (c == '\'' || c == '"') {
                i++;
                while (i < n && s.charAt(i) != c) {
                    if (s.charAt(i) == '\\') i++;
                    else if (s.charAt(i) == '\n') break; // unterminated: stop at line end
                    i++;
                }
                i = Math.min(n, i + 1);
                Tok k = new Tok(STR, s.substring(st, i));
                out.add(k); lastSig = k;
                continue;
            }
            if (c == '`') {
                i = skipTemplate(s, i);
                if (i < 0) return null;
                Tok k = new Tok(TPL, s.substring(st, i));
                out.add(k); lastSig = k;
                continue;
            }
            if (c == '/' && regexAllowed(lastSig)) {
                i++;
                boolean cls = false;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '\\') { i += 2; continue; }
                    if (d == '\n') break;
                    if (cls) { if (d == ']') cls = false; }
                    else if (d == '[') cls = true;
                    else if (d == '/') break;
                    i++;
                }
                i = Math.min(n, i + 1);
                while (i < n && Character.isLetter(s.charAt(i))) i++;
                Tok k = new Tok(REGEX, s.substring(st, i));
                out.add(k); lastSig = k;
                continue;
            }
            if (Character.isJavaIdentifierStart(c) || c == '\\') {
                i++;
                while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) i++;
                Tok k = new Tok(IDENT, s.substring(st, i));
                out.add(k); lastSig = k;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                i++;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.'
                        || ((s.charAt(i) == '+' || s.charAt(i) == '-') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')))) i++;
                Tok k = new Tok(NUM, s.substring(st, i));
                out.add(k); lastSig = k;
                continue;
            }
            // punctuation; keep "=>" "..." and multi-char operators that contain '/' or '=' together
            String p = punct(s, i);
            i += p.length();
            Tok k = new Tok(PUNCT, p);
            out.add(k); lastSig = k;
        }
        return out;
    }

    private static final String[] PUNCTS = {
        ">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "=>", "==", "!=", "<=", ">=", "&&", "||", "??",
        "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "**"
    };

    private static String punct(String s, int i) {
        for (String p : PUNCTS) if (s.startsWith(p, i)) {
            if (p.equals("?.") && i + 2 < s.length() && Character.isDigit(s.charAt(i + 2))) continue;
            return p;
        }
        return String.valueOf(s.charAt(i));
    }

    private static boolean regexAllowed(Tok last) {
        if (last == null) return true;
        if (last.kind == PUNCT) {
            String p = last.text;
            return !(p.equals(")") || p.equals("]") || p.equals("}") || p.equals("++") || p.equals("--"));
        }
        if (last.kind == IDENT) return REGEX_PREV_KEYWORDS.indexOf(" " + last.text + " ") >= 0;
        return false;
    }

    /** returns index after the closing backtick, or -1 */
    private static int skipTemplate(String s, int i) {
        int n = s.length();
        i++;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '`') return i + 1;
            if (c == '$' && i + 1 < n && s.charAt(i + 1) == '{') {
                i = skipBraces(s, i + 1);
                if (i < 0) return -1;
                continue;
            }
            i++;
        }
        return -1;
    }

    /** s[i] == '{'; returns index after the matching '}' (strings/templates/comments aware) */
    private static int skipBraces(String s, int i) {
        int n = s.length(), depth = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i++;
                while (i < n && s.charAt(i) != c) { if (s.charAt(i) == '\\') i++; i++; }
                i++;
                continue;
            }
            if (c == '`') { i = skipTemplate(s, i); if (i < 0) return -1; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') { int e = s.indexOf("*/", i + 2); if (e < 0) return -1; i = e + 2; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i + 1; }
            i++;
        }
        return -1;
    }

    // ---------------------------------------------------------------- templates, let/const

    private static void rewriteSimple(ArrayList<Tok> t) {
        for (int i = 0; i < t.size(); i++) {
            Tok k = t.get(i);
            if (k.kind == TPL) {
                k.text = templateToConcat(k.text);
            } else if (k.kind == IDENT && (k.text.equals("let") || k.text.equals("const"))) {
                Tok prev = prevSig(t, i), next = nextSig(t, i);
                boolean member = prev != null && prev.kind == PUNCT && (prev.text.equals(".") || prev.text.equals("?."));
                boolean decl = next != null && (next.kind == IDENT || next.text.equals("[") || next.text.equals("{"));
                if (!member && decl && !(next.kind == IDENT && next.text.equals("in"))) k.text = "var";
            }
        }
    }

    /** `a${b}c` -> ("a" + (b) + "c") */
    static String templateToConcat(String tpl) {
        String body = tpl.substring(1, tpl.length() - 1);
        StringBuilder out = new StringBuilder("(");
        StringBuilder lit = new StringBuilder();
        boolean first = true;
        int n = body.length(), i = 0;
        while (i < n) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char d = body.charAt(i + 1);
                if (d == '`' || d == '$') lit.append(d); else lit.append(c).append(d);
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < n && body.charAt(i + 1) == '{') {
                int e = skipBraces(body, i + 1);
                if (e < 0) return tpl;
                String expr = body.substring(i + 2, e - 1);
                if (!first) out.append(" + ");
                out.append(quote(lit.toString()));
                lit.setLength(0);
                out.append(" + (").append(transform(expr)).append(")");
                first = false;
                i = e;
                continue;
            }
            lit.append(c);
            i++;
        }
        if (!first) out.append(" + ");
        out.append(quote(lit.toString()));
        return out.append(")").toString();
    }

    /** raw template text -> JS double-quoted string (keeps the original escapes) */
    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) { sb.append(c).append(raw.charAt(++i)); continue; }
            if (c == '"') sb.append("\\\"");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') { /* template literals normalise CRLF to LF */ }
            else if (c == ' ') sb.append("\\u2028");
            else if (c == ' ') sb.append("\\u2029");
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------- arrows

    private static boolean rewriteFirstArrow(ArrayList<Tok> t) {
        for (int i = 0; i < t.size(); i++) {
            Tok k = t.get(i);
            if (k.kind != PUNCT || !k.text.equals("=>")) continue;

            // parameters
            int pEnd = prevSigIndex(t, i);
            if (pEnd < 0) return false;
            int pStart;
            String params;
            Tok pe = t.get(pEnd);
            if (pe.kind == IDENT) {
                pStart = pEnd;
                params = pe.text;
            } else if (pe.text.equals(")")) {
                pStart = matchBack(t, pEnd);
                if (pStart < 0) { k.text = "/*=>?*/"; return true; }
                StringBuilder sb = new StringBuilder();
                for (int j = pStart + 1; j < pEnd; j++) sb.append(t.get(j).text);
                params = sb.toString();
            } else {
                k.text = "/*=>?*/";
                return true;
            }
            // drop `async` before the params, if any (not supported; keep it a plain function)
            int before = prevSigIndex(t, pStart);
            if (before >= 0 && t.get(before).kind == IDENT && t.get(before).text.equals("async")) t.get(before).text = "";

            // body
            int b = nextSigIndex(t, i);
            if (b < 0) return false;
            boolean block = t.get(b).text.equals("{");
            int bodyEnd = -1;
            if (!block) bodyEnd = expressionEnd(t, b);

            // rewrite (from the back so indices stay valid)
            if (!block) {
                t.add(bodyEnd, new Tok(PUNCT, ";}"));
                t.add(b, new Tok(PUNCT, "{return "));
            }
            k.text = "";
            for (int j = pStart; j <= pEnd; j++) t.get(j).text = "";
            t.get(pStart).text = "function(" + params + ")";
            return true;
        }
        return false;
    }

    /** index of the first token after an arrow's expression body */
    private static int expressionEnd(ArrayList<Tok> t, int from) {
        int depth = 0;
        for (int j = from; j < t.size(); j++) {
            Tok k = t.get(j);
            if (k.kind != PUNCT) continue;
            String p = k.text;
            if (p.equals("(") || p.equals("[") || p.equals("{")) depth++;
            else if (p.equals(")") || p.equals("]") || p.equals("}")) {
                if (depth == 0) return j;
                depth--;
            } else if (depth == 0 && (p.equals(",") || p.equals(";"))) return j;
        }
        return t.size();
    }

    private static int matchBack(ArrayList<Tok> t, int close) {
        int depth = 0;
        for (int j = close; j >= 0; j--) {
            Tok k = t.get(j);
            if (k.kind != PUNCT) continue;
            if (k.text.equals(")") || k.text.equals("]") || k.text.equals("}")) depth++;
            else if (k.text.equals("(") || k.text.equals("[") || k.text.equals("{")) {
                depth--;
                if (depth == 0) return k.text.equals("(") ? j : -1;
            }
        }
        return -1;
    }

    private static boolean sig(Tok k) { return k.kind != WS && k.kind != COMMENT && k.text.length() > 0; }

    private static int prevSigIndex(ArrayList<Tok> t, int i) {
        for (int j = i - 1; j >= 0; j--) if (sig(t.get(j))) return j;
        return -1;
    }

    private static int nextSigIndex(ArrayList<Tok> t, int i) {
        for (int j = i + 1; j < t.size(); j++) if (sig(t.get(j))) return j;
        return -1;
    }

    private static Tok prevSig(ArrayList<Tok> t, int i) { int j = prevSigIndex(t, i); return j < 0 ? null : t.get(j); }
    private static Tok nextSig(ArrayList<Tok> t, int i) { int j = nextSigIndex(t, i); return j < 0 ? null : t.get(j); }
}
