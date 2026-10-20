package com.segar.livetv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Streaming M3U parser for iptv-org style playlists. */
final class M3u {
    private M3u() {}

    /** Appends channels from {@code in} to {@code out}, skipping keys already in {@code seen}. */
    static void parse(InputStream in, List<Channel> out, Set<String> seen, String extraGroup) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 16 * 1024);
        String line, extinf = null, referrer = null, ua = null;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                extinf = line;
                referrer = ua = null;
            } else if (line.startsWith("#EXTVLCOPT:")) {
                String opt = line.substring(11);
                if (opt.startsWith("http-referrer=")) referrer = opt.substring(14);
                else if (opt.startsWith("http-user-agent=")) ua = opt.substring(16);
            } else if (line.charAt(0) != '#' && extinf != null) {
                Channel c = build(extinf, line, referrer, ua, extraGroup);
                extinf = null;
                if (c != null && seen.add(c.key)) out.add(c);
            }
        }
    }

    private static Channel build(String extinf, String url, String referrer, String ua, String extraGroup) {
        String id = attr(extinf, "tvg-id");
        String logo = attr(extinf, "tvg-logo");
        String group = attr(extinf, "group-title");
        int comma = titleComma(extinf);
        String name = cleanName(comma >= 0 ? extinf.substring(comma + 1) : "");
        if (name.isEmpty()) return null;

        String key = url;
        if (id != null && !id.isEmpty()) {
            int at = id.indexOf('@');
            key = at > 0 ? id.substring(0, at) : id;
        }
        List<String> groups = new ArrayList<>(3);
        if (group != null) {
            for (String g : group.split(";")) {
                g = g.trim();
                if (g.isEmpty() || g.equals("Undefined")) g = "Other";
                if (!groups.contains(g)) groups.add(g);
            }
        }
        if (extraGroup != null && !groups.contains(extraGroup)) groups.add(extraGroup);
        if (groups.isEmpty()) groups.add("Other");
        return new Channel(key, name, empty(logo), groups.toArray(new String[0]), url, empty(referrer), empty(ua));
    }

    private static String attr(String s, String name) {
        int i = s.indexOf(name + "=\"");
        if (i < 0) return null;
        int start = i + name.length() + 2;
        int end = s.indexOf('"', start);
        return end < 0 ? null : s.substring(start, end);
    }

    /** The title follows the first comma that isn't inside an attribute value. */
    private static int titleComma(String s) {
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) return i;
        }
        return -1;
    }

    /** "Sun News (1080p) [Geo-blocked]" → "Sun News". */
    private static String cleanName(String n) {
        n = n.replaceAll("\\s*\\[[^\\]]*\\]", "").replaceAll("\\s*\\(\\d{3,4}[pi]\\)", "");
        return n.trim();
    }

    private static String empty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
