package kr.co.crema.home;

import android.content.SharedPreferences;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLException;

/**
 * RSS/Atom subscriptions. Feeds are parsed with a streaming pull parser that
 * stops after `keep` entries, so a large feed never sits in memory as a DOM.
 * Only title/link/date/summary-text are stored, in one small JSON file per feed.
 */
final class Feeds {
    static final class Feed {
        String name, url;
        boolean on;
        int count;
        long fetchedAt;
        String error;
    }

    static final class Item {
        String feed, title, link, summary;
        long date;
    }

    static final int[] KEEP_OPTIONS = {30, 50, 100};
    private final SharedPreferences prefs;
    private final File dir;
    final List<Feed> feeds = new ArrayList<Feed>();
    boolean showOnHome;
    boolean wifiOnly;
    int keep;

    Feeds(SharedPreferences p, File cacheDir) {
        prefs = p;
        dir = new File(cacheDir, "feeds");
        dir.mkdirs();
        load();
    }

    private void load() {
        showOnHome = prefs.getBoolean("rss_home", true);
        wifiOnly = prefs.getBoolean("rss_wifi_only", true);
        keep = prefs.getInt("rss_keep", 50);
        feeds.clear();
        String js = prefs.getString("rss_feeds", null);
        try {
            if (js == null) {
                // GeekNews itself (news.hada.io) only offers TLS1.2+GCM, which Android 4.2
                // cannot negotiate; the FeedBurner mirror serves the same feed over plain HTTP.
                add("GeekNews", "http://feeds.feedburner.com/geeknews-feed", true);
                add("Hacker News", "https://hnrss.org/frontpage", false);
                save();
                return;
            }
            JSONArray a = new JSONArray(js);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Feed f = new Feed();
                f.name = o.optString("name");
                f.url = o.optString("url");
                f.on = o.optBoolean("on", true);
                f.count = o.optInt("count");
                f.fetchedAt = o.optLong("at");
                f.error = o.has("err") ? o.optString("err") : null;
                feeds.add(f);
            }
        } catch (Exception ignored) {
        }
    }

    Feed add(String name, String url, boolean on) {
        Feed f = new Feed();
        f.name = name;
        f.url = url;
        f.on = on;
        feeds.add(f);
        return f;
    }

    synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Feed f : feeds) {
                JSONObject o = new JSONObject();
                o.put("name", f.name).put("url", f.url).put("on", f.on).put("count", f.count).put("at", f.fetchedAt);
                if (f.error != null) o.put("err", f.error);
                a.put(o);
            }
            prefs.edit().putString("rss_feeds", a.toString()).putBoolean("rss_home", showOnHome)
                    .putBoolean("rss_wifi_only", wifiOnly).putInt("rss_keep", keep).commit();
        } catch (Exception ignored) {
        }
    }

    static String displayUrl(String url) {
        String s = url.replaceFirst("^[a-zA-Z]+://", "");
        if (s.startsWith("www.")) s = s.substring(4);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Fetch every enabled feed. Runs on the IO thread; mutates the Feed objects. */
    void fetchAll() {
        for (Feed f : new ArrayList<Feed>(feeds)) if (f.on) fetch(f);
        save();
    }

    void fetch(Feed f) {
        HttpURLConnection con = null;
        try {
            ArrayList<Item> items = new ArrayList<Item>();
            String name = download(f.url, items, 0);
            f.count = items.size();
            f.fetchedAt = System.currentTimeMillis();
            f.error = null;
            if ((f.name == null || f.name.length() == 0 || f.name.equals(displayUrl(f.url))) && name != null)
                f.name = name;
            writeItems(f, items);
        } catch (SSLException e) {
            f.error = "이 기기가 지원하지 않는 보안 방식이에요";
        } catch (java.net.UnknownHostException e) {
            f.error = "주소를 찾을 수 없어요";
        } catch (java.net.SocketTimeoutException e) {
            f.error = "응답이 없어요";
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            f.error = "RSS 피드가 아니에요";
        } catch (Exception e) {
            f.error = "받을 수 없어요";
        }
    }

    private String download(String url, List<Item> items, int redirects) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setInstanceFollowRedirects(false);
        con.setRequestProperty("User-Agent", "CremaHome/0.1");
        try {
            int code = con.getResponseCode();
            if (code >= 300 && code < 400 && redirects < 4) {
                String loc = con.getHeaderField("Location");
                if (loc != null) return download(new URL(new URL(url), loc).toString(), items, redirects + 1);
            }
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            InputStream in = new BufferedInputStream(con.getInputStream(), 8192);
            try {
                return parse(in, items);
            } finally {
                in.close();
            }
        } finally {
            con.disconnect();
        }
    }

    /** Atom (<entry>) and RSS 2.0 (<item>) in one pass. Returns the feed title. */
    private String parse(InputStream in, List<Item> out) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, null);
        String feedTitle = null;
        Item cur = null;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if (n.equals("entry") || n.equals("item")) {
                    cur = new Item();
                } else if (cur == null) {
                    if (n.equals("title") && feedTitle == null) feedTitle = clean(p.nextText());
                } else if (n.equals("title")) {
                    cur.title = clean(p.nextText());
                } else if (n.equals("link")) {
                    String href = p.getAttributeValue(null, "href");
                    String rel = p.getAttributeValue(null, "rel");
                    if (href != null) {
                        if (cur.link == null || "alternate".equals(rel)) cur.link = href;
                    } else cur.link = p.nextText().trim();
                } else if (n.equals("content") || n.equals("summary") || n.equals("description")) {
                    if (cur.summary == null || n.equals("content")) cur.summary = summarize(p.nextText());
                } else if (n.equals("published") || n.equals("updated") || n.equals("pubDate") || n.equals("date")) {
                    if (cur.date == 0) cur.date = Dates.parse(p.nextText().trim());
                }
            } else if (ev == XmlPullParser.END_TAG && cur != null) {
                String n = local(p.getName());
                if (n.equals("entry") || n.equals("item")) {
                    out.add(cur);
                    cur = null;
                    if (out.size() >= keep) break;
                }
            }
        }
        return feedTitle;
    }

    private static String local(String n) {
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    /** Strip tags/entities, keep the first ~300 chars of text. */
    static String summarize(String html) {
        if (html == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean tag = false;
        for (int i = 0; i < html.length() && sb.length() < 400; i++) {
            char c = html.charAt(i);
            if (c == '<') {
                tag = true;
                String rest = html.substring(i, Math.min(html.length(), i + 4)).toLowerCase();
                if (rest.startsWith("<li") || rest.startsWith("<p") || rest.startsWith("<br")) sb.append('\n');
            } else if (c == '>') tag = false;
            else if (!tag) sb.append(c);
        }
        return clean(sb.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " "));
    }

    private static String clean(String s) {
        if (s == null) return null;
        return s.replaceAll("[ \\t\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    private File file(Feed f) {
        return new File(dir, Integer.toHexString(f.url.hashCode()) + ".json");
    }

    private void writeItems(Feed f, List<Item> items) throws Exception {
        JSONArray a = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject();
            o.put("t", it.title).put("l", it.link).put("s", it.summary).put("d", it.date);
            a.put(o);
        }
        File tmp = new File(dir, "tmp.json");
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        w.write(a.toString());
        w.close();
        tmp.renameTo(file(f));
    }

    List<Item> items(Feed f) {
        ArrayList<Item> out = new ArrayList<Item>();
        File fl = file(f);
        if (!fl.isFile()) return out;
        try {
            byte[] buf = new byte[(int) fl.length()];
            FileInputStream in = new FileInputStream(fl);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            JSONArray a = new JSONArray(new String(buf, "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.feed = f.name;
                it.title = o.optString("t");
                it.link = o.optString("l");
                it.summary = o.optString("s");
                it.date = o.optLong("d");
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    void remove(Feed f) {
        feeds.remove(f);
        file(f).delete();
    }
}
