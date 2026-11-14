package com.segar.livetv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Channel list from iptv-org playlists: the cached copy (or built-in snapshot) right away,
 * then a refresh from iptv-org when the cache is stale. Held in memory for both activities.
 */
final class Repo {
    static final String FAVOURITES = "Favourites";
    static final String ALL = "All";
    static final String TAMIL = "Tamil";
    static final String OTHER = "Other";

    private static final String TAG = "Repo";
    private static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    private static final String[][] IPTV_ORG = {
            {"https://iptv-org.github.io/iptv/countries/in.m3u", null}};

    /** Languages kept: Hindi, Punjabi and English. The language playlists say which channel ids belong to
     *  each; the India playlist supplies the streams, filtered down to those ids, dropping the rest. */
    private static final String[] LANGUAGES = {
            "https://iptv-org.github.io/iptv/languages/hin.m3u",
            "https://iptv-org.github.io/iptv/languages/pan.m3u",
            "https://iptv-org.github.io/iptv/languages/eng.m3u"};

    /** Other states' regional channels (they broadcast in Hindi, so the language filter can't catch them).
     *  Punjab's stay, everything else state-named goes. Both scripts: many channels name themselves in
     *  Devanagari ("Zee राजस्थान"). */
    private static final String[] BLOCKED_STATES = {
            "rajasthan", "himachal", "uttar pradesh", "up/uk", "up/bihar", "madhya pradesh", "mp/cg",
            "bihar", "jharkhand", "chhattisgarh", "haryana", "uttarakhand", "bhojpuri", "purvanchal",
            "राजस्थान", "हिमाचल", "उत्तर प्रदेश", "उत्तराखंड", "उत्तराखण्ड", "मध्य प्रदेश", "छत्तीसगढ़",
            "छत्तीसगढ", "बिहार", "झारखंड", "झारखण्ड", "हरियाणा", "भोजपुरी"};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static List<Channel> all = Collections.emptyList();
    private static Map<String, Channel> byKey = Collections.emptyMap();
    private static List<String> categories = Collections.emptyList();
    private static boolean loading;
    // The two sources, combined by publish(): JioTV first, then iptv-org.
    private static final Object SOURCES = new Object();
    private static List<Channel> iptvList = Collections.emptyList();
    private static List<Channel> jioList = Collections.emptyList();
    private static Runnable listener;

    private Repo() {}

    static List<Channel> all() { return all; }
    static List<String> categories() { return categories; }
    static Channel get(String key) { return key == null ? null : byKey.get(key); }

    /** Called on the main thread whenever the list changes. */
    static void setListener(Runnable r) { listener = r; }

    static List<Channel> filter(String cat) {
        if (ALL.equals(cat)) {
            List<Channel> out = new ArrayList<>(all.size());
            for (Channel c : all) if (!Prefs.isDead(c.key)) out.add(c);
            return out;
        }
        List<Channel> out = new ArrayList<>();
        if (FAVOURITES.equals(cat)) {
            for (String k : Prefs.favs()) {
                Channel c = byKey.get(k);
                if (c != null) out.add(c);
            }
            return out;
        }
        for (Channel c : all) if (c.inGroup(cat) && !Prefs.isDead(c.key)) out.add(c);
        return out;
    }

    static List<Channel> search(String q) {
        q = q.trim().toLowerCase(java.util.Locale.ROOT);
        List<Channel> starts = new ArrayList<>(), contains = new ArrayList<>();
        if (q.isEmpty()) return starts;
        for (Channel c : all) {
            int i = c.search.indexOf(q);
            if (i == 0) starts.add(c);
            else if (i > 0) contains.add(c);
        }
        starts.addAll(contains);
        return starts;
    }

    static void load(Context context) {
        load(context, false, null);
    }

    /** Re-downloads the playlists now; {@code done} runs on the main thread with the outcome. */
    static void refresh(Context context, Epg.Callback<Boolean> done) {
        load(context, true, done);
    }

    private static void load(Context context, boolean force, Epg.Callback<Boolean> done) {
        if (loading) {
            if (done != null) done.done(false);
            return;
        }
        loading = true;
        Context app = context.getApplicationContext();
        File cache = new File(app.getFilesDir(), "playlist.m3u");
        File jioCache = new File(app.getFilesDir(), "jio.m3u");
        Net.IO.execute(() -> {
            if (all.isEmpty()) {
                List<Channel> jio = Collections.emptyList();
                if (Jio.loggedIn() && jioCache.exists()) {
                    try (InputStream in = new FileInputStream(jioCache)) {
                        jio = parse(in, null);
                    } catch (IOException e) {
                        Log.w(TAG, "local JioTV list", e);
                    }
                }
                try (InputStream in = cache.exists() ? new FileInputStream(cache) : app.getAssets().open("channels.m3u")) {
                    setSources(parse(in, null), jio);
                } catch (IOException e) {
                    Log.w(TAG, "local playlist", e);
                }
            }
            boolean ok = false;
            if (force || !cache.exists() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS) {
                List<Channel> fresh = fetch();
                if (fresh != null) {
                    save(fresh, cache);
                    setSources(fresh, null);
                    ok = true;
                }
            }
            if (Jio.loggedIn() && (force || !jioCache.exists()
                    || System.currentTimeMillis() - jioCache.lastModified() > MAX_AGE_MS)) {
                fetchJio(jioCache);
            }
            boolean result = ok;
            MAIN.post(() -> {
                loading = false;
                if (result) Prefs.clearFailures(); // new links may work where the old ones died
                if (done != null) done.done(result);
            });
        });
    }

    /** Fetches the JioTV channels now (after sign-in or a language change); {@code done} gets the count, or -1. */
    static void reloadJio(Context context, Epg.Callback<Integer> done) {
        File jioCache = new File(context.getApplicationContext().getFilesDir(), "jio.m3u");
        Net.IO.execute(() -> {
            int n = fetchJio(jioCache);
            MAIN.post(() -> done.done(n));
        });
    }

    /** Removes JioTV channels from the library (after signing out). */
    static void clearJio(Context context) {
        new File(context.getApplicationContext().getFilesDir(), "jio.m3u").delete();
        Net.IO.execute(() -> setSources(null, Collections.emptyList()));
    }

    /** Number of JioTV channels currently in the library. */
    static int jioCount() {
        synchronized (SOURCES) {
            return jioList.size();
        }
    }

    private static int fetchJio(File jioCache) {
        try {
            List<Channel> jio = Jio.fetchChannels();
            save(jio, jioCache);
            setSources(null, jio);
            return jio.size();
        } catch (IOException e) {
            Log.w(TAG, "JioTV channels unavailable", e);
            return -1;
        }
    }

    /** Replaces one or both sources (null keeps the current one) and republishes. */
    private static void setSources(List<Channel> iptv, List<Channel> jio) {
        List<Channel> combined;
        synchronized (SOURCES) {
            if (iptv != null) iptvList = iptv;
            if (jio != null) jioList = jio;
            combined = new ArrayList<>(jioList.size() + iptvList.size());
            combined.addAll(jioList);
            combined.addAll(iptvList);
        }
        publish(combined);
    }

    /** iptv-org Tamil first, then India; duplicates (by tvg-id) keep their Tamil entry. */
    private static List<Channel> fetch() {
        try {
            Set<String> kept = new HashSet<>();
            for (String lang : LANGUAGES) {
                try {
                    M3u.collectIds(new ByteArrayInputStream(Net.bytes(lang, 16 << 20)), kept);
                } catch (IOException e) {
                    Log.w(TAG, "language playlist unavailable: " + lang, e); // the other two still count
                }
            }
            List<Channel> list = new ArrayList<>();
            Map<String, Channel> seen = new HashMap<>();
            M3u.parse(new ByteArrayInputStream(Net.bytes(IPTV_ORG[0][0], 8 << 20)), list, seen, null);
            if (list.size() <= 20) return null;
            // No language data at all: show India as-is rather than an empty TV.
            if (kept.isEmpty()) return list;
            List<Channel> out = new ArrayList<>();
            for (Channel c : list) {
                if (c.key.equals(c.url) || !kept.contains(c.key)) continue;
                if (stateBlocked(c.name + " " + c.key)) continue;
                out.add(c);
            }
            return out.size() > 20 ? out : list;
        } catch (IOException e) {
            Log.w(TAG, "iptv-org playlist unavailable", e);
        }
        return null;
    }

    /** True when the channel name or id names another state's region: "News18 UP/UK", "1st India Rajasthan". */
    private static boolean stateBlocked(String s) {
        s = s.toLowerCase(java.util.Locale.ROOT);
        // Punjab's own regional channels stay: "Zee पंजाब हरियाणा हिमाचल", "PTC Punjabi" etc.
        if (s.contains("punjab") || s.contains("पंजाब")) return false;
        for (String k : BLOCKED_STATES) if (s.contains(k)) return true;
        // Bare state abbreviations at word boundaries: "News18 UP", "India News MP", "News18 CG".
        return s.matches("(?s).*\\b(up|mp|cg)\\b.*");
    }

    private static List<Channel> parse(InputStream in, String extraGroup) throws IOException {
        List<Channel> list = new ArrayList<>(1024);
        M3u.parse(in, list, new HashMap<>(), extraGroup);
        return list;
    }

    private static void save(List<Channel> list, File cache) {
        File tmp = new File(cache.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("#EXTM3U\n");
            for (Channel c : list) {
                w.write("#EXTINF:-1 tvg-id=\"" + (c.key.equals(c.url) ? "" : c.key) + "\"");
                if (c.logo != null) w.write(" tvg-logo=\"" + c.logo + "\"");
                w.write(" group-title=\"" + android.text.TextUtils.join(";", c.groups) + "\"," + c.name + "\n");
                if (c.referrer != null) w.write("#EXTVLCOPT:http-referrer=" + c.referrer + "\n");
                if (c.userAgent != null) w.write("#EXTVLCOPT:http-user-agent=" + c.userAgent + "\n");
                w.write(c.url + "\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "cache write", e);
            return;
        }
        if (!tmp.renameTo(cache)) tmp.delete();
    }

    private static void publish(List<Channel> list) {
        Map<String, Channel> map = new HashMap<>(list.size() * 2);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            Channel c = list.get(i);
            c.number = i + 1;
            map.put(c.key, c);
            for (String g : c.groups) {
                Integer n = counts.get(g);
                counts.put(g, n == null ? 1 : n + 1);
            }
        }
        List<String> groups = new ArrayList<>(counts.keySet());
        groups.remove(TAMIL);
        groups.remove(Jio.GROUP);
        groups.remove(OTHER);
        Collections.sort(groups, (a, b) -> counts.get(b) - counts.get(a));
        List<String> cats = new ArrayList<>();
        cats.add(FAVOURITES);
        cats.add(ALL);
        if (counts.containsKey(TAMIL)) cats.add(TAMIL);
        if (counts.containsKey(Jio.GROUP)) cats.add(Jio.GROUP);
        cats.addAll(groups);
        if (counts.containsKey(OTHER)) cats.add(OTHER);

        MAIN.post(() -> {
            all = Collections.unmodifiableList(list);
            byKey = map;
            categories = cats;
            if (listener != null) listener.run();
        });
    }
}
