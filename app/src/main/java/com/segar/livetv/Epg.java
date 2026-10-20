package com.segar.livetv;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Now/next guide from the server's compact JSON (docs/PLAN.md "Contract"). Silent when unavailable. */
final class Epg {
    static final class Programme {
        final long start, end; // ms
        final String title, desc;

        Programme(long start, long end, String title, String desc) {
            this.start = start;
            this.end = end;
            this.title = title;
            this.desc = desc;
        }

        /** 0..1 progress through the programme at {@code now}. */
        float progress(long now) {
            return end <= start ? 0 : Math.max(0, Math.min(1, (now - start) / (float) (end - start)));
        }
    }

    interface Callback<T> { void done(T value); }

    private static final String TAG = "Epg";
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Map<String, Programme[]> now = Collections.emptyMap();
    /** JioTV channels' guides, fetched from JioTV per channel as they're watched. */
    private static final Map<String, Programme[]> jio = new HashMap<>();
    private static long fetchedAt;
    private static boolean fetching;

    private Epg() {}

    /** Current programme for the channel, or null. */
    static Programme current(String key) {
        Programme[] p = programmes(key);
        if (p == null) return null;
        long t = System.currentTimeMillis();
        for (Programme x : p) if (x.start <= t && t < x.end) return x;
        return null;
    }

    /** The programme after the current one, or null. */
    static Programme next(String key) {
        Programme[] p = programmes(key);
        if (p == null) return null;
        long t = System.currentTimeMillis();
        for (Programme x : p) if (x.start > t) return x;
        return null;
    }

    /** Channels that currently have guide data. */
    static int channelCount() {
        return now.size();
    }

    /** Fetches now/next straight away, even if the data is fresh. */
    static void forceRefresh(Runnable onChange) {
        if (fetching) return;
        fetchedAt = 0;
        refresh(onChange);
    }

    private static Programme[] programmes(String key) {
        Programme[] p = now.get(key);
        return p != null ? p : jio.get(key);
    }

    /** Fetches a JioTV channel's guide unless what we have still covers now; then runs {@code onLoaded}. */
    static void loadJio(Channel c, Runnable onLoaded) {
        Programme[] have = jio.get(c.key);
        long t = System.currentTimeMillis();
        if (have != null && have.length > 0 && have[have.length - 1].end > t + 3_600_000L) return;
        String id = Jio.channelId(c);
        Net.IO.execute(() -> {
            List<Programme> list = Jio.guide(id);
            if (list.isEmpty()) return;
            Programme[] arr = list.toArray(new Programme[0]);
            MAIN.post(() -> {
                jio.put(c.key, arr);
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    /** Refreshes now/next if stale; {@code onChange} runs on the main thread when new data arrives. */
    static void refresh(Runnable onChange) {
        if (fetching || System.currentTimeMillis() - fetchedAt < REFRESH_MS) return;
        fetching = true;
        Net.IO.execute(() -> {
            Map<String, Programme[]> map = null;
            try {
                JSONObject channels = new JSONObject(text(Net.SERVER + "/epg/now.json", 2 << 20))
                        .getJSONObject("channels");
                map = new HashMap<>(channels.length() * 2);
                for (Iterator<String> it = channels.keys(); it.hasNext(); ) {
                    String k = it.next();
                    map.put(k, programmes(channels.getJSONArray(k)).toArray(new Programme[0]));
                }
            } catch (IOException | JSONException e) {
                Log.i(TAG, "now/next unavailable: " + e.getMessage());
            }
            Map<String, Programme[]> result = map;
            MAIN.post(() -> {
                fetching = false;
                fetchedAt = System.currentTimeMillis();
                if (result != null) {
                    now = result;
                    if (onChange != null) onChange.run();
                }
            });
        });
    }

    /** Today-and-tomorrow schedule for one channel; delivers an empty list when there's none. */
    static void schedule(String key, Callback<List<Programme>> cb) {
        Net.IO.execute(() -> {
            List<Programme> list = Collections.emptyList();
            if (key.startsWith(Jio.SCHEME)) {
                long t = System.currentTimeMillis();
                List<Programme> upcoming = new ArrayList<>();
                for (Programme p : Jio.guide(key.substring(Jio.SCHEME.length()))) if (p.end > t) upcoming.add(p);
                MAIN.post(() -> cb.done(upcoming));
                return;
            }
            try {
                JSONObject o = new JSONObject(text(Net.SERVER + "/epg/" + Uri.encode(key) + ".json", 1 << 20));
                list = programmes(o.getJSONArray("programmes"));
            } catch (IOException | JSONException e) {
                Log.i(TAG, "schedule unavailable for " + key + ": " + e.getMessage());
            }
            List<Programme> result = list;
            MAIN.post(() -> cb.done(result));
        });
    }

    private static List<Programme> programmes(JSONArray arr) throws JSONException {
        List<Programme> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONArray p = arr.getJSONArray(i);
            out.add(new Programme(p.getLong(0) * 1000, p.getLong(1) * 1000, p.getString(2),
                    p.length() > 3 ? p.optString(3, "") : ""));
        }
        return out;
    }

    private static String text(String url, int max) throws IOException {
        return new String(Net.bytes(url, max), StandardCharsets.UTF_8);
    }
}
