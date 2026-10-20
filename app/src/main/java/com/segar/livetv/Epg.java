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
    private static long fetchedAt;
    private static boolean fetching;

    private Epg() {}

    /** Current programme for the channel, or null. */
    static Programme current(String key) {
        Programme[] p = now.get(key);
        if (p == null) return null;
        long t = System.currentTimeMillis();
        for (Programme x : p) if (x.start <= t && t < x.end) return x;
        return null;
    }

    /** The programme after the current one, or null. */
    static Programme next(String key) {
        Programme[] p = now.get(key);
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
