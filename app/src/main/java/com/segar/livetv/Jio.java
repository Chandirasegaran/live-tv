package com.segar.livetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * JioTV, talked to directly from the TV: JioTV only serves Indian home connections, so this
 * can't live on a cloud server. Ported from JioTV Go (https://github.com/jiotv-go/jiotv_go,
 * CC BY 4.0): OTP login, token refresh, channel list, stream links and guide.
 * Network calls here are blocking; run them on {@link Net#IO}.
 */
final class Jio {
    static final String SCHEME = "jio:";
    static final String GROUP = "JioTV";
    /** Player-like agent: some JioTV CDNs reject okhttp for media requests. */
    static final String PLAYER_UA = "plaYtv/7.1.8 (Linux;Android 8.1.0) ExoPlayerLib/2.11.7";

    private static final String TAG = "Jio";
    private static final String API = "https://jiotvapi.media.jio.com";
    private static final String CHANNELS_AUTH = "https://jiotv.data.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?os=android&devicetype=phone&usertype=tvYR7NSNn7rymo3F";
    private static final String CHANNELS_PUBLIC = "https://jiotvapi.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?langId=6&os=android&devicetype=phone&usertype=JIO&version=315&langId=6";
    private static final String REFRESH_ACCESS = "https://auth.media.jio.com/tokenservice/apis/v1/refreshtoken?langId=6";
    private static final String REFRESH_SSO = "https://tv.media.jio.com/apis/v2.0/loginotp/refresh?langId=6";
    private static final String PLAYBACK = API + "/playback/apis/v1.1/geturl?langId=6";
    private static final String EPG = "https://jiotv.data.cdn.jio.com/apis/v1.3/getepg/get?offset=%d&channel_id=%s";
    private static final String LOGO = "https://jiotv.catchup.cdn.jio.com/dare_images/images/";
    private static final String OKHTTP = "okhttp/4.12.0";
    private static final String APPKEY = "NzNiMDhlYzQyNjJm";
    private static final String USERGROUP = "tvYR7NSNn7rymo3F";

    // JioTV language and category ids (JioTV Go's LanguageMap / CategoryMap).
    static final int[] LANGUAGE_IDS = {8, 6, 1, 11, 7, 13, 5, 2, 9, 3, 10, 12, 14, 4, 15, 16, 18};
    static final String[] LANGUAGE_NAMES = {"Tamil", "English", "Hindi", "Telugu", "Malayalam", "Kannada",
            "Bengali", "Marathi", "Gujarati", "Punjabi", "Odia", "Bhojpuri", "Assamese", "Urdu", "Nepali",
            "French", "Other"};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SharedPreferences sp;

    private Jio() {}

    static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("jio", Context.MODE_PRIVATE);
    }

    static boolean loggedIn() {
        return !sp.getString("access", "").isEmpty();
    }

    /** "•••• 6627" for the settings row. */
    static String maskedNumber() {
        String n = sp.getString("number", "");
        return n.length() >= 4 ? "•••• " + n.substring(n.length() - 4) : "";
    }

    static boolean isJio(Channel c) {
        return c.url.startsWith(SCHEME);
    }

    static String channelId(Channel c) {
        return c.url.substring(SCHEME.length());
    }

    // ---- Languages ---------------------------------------------------------------------------

    /** Chosen JioTV language ids; Tamil and English until the user changes them. */
    static Set<Integer> languages() {
        Set<Integer> out = new HashSet<>();
        for (String s : sp.getString("langs", "8,6").split(",")) {
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    static void setLanguages(Set<Integer> ids) {
        StringBuilder b = new StringBuilder();
        for (int id : ids) b.append(b.length() == 0 ? "" : ",").append(id);
        sp.edit().putString("langs", b.toString()).apply();
    }

    static String languageName(int id) {
        for (int i = 0; i < LANGUAGE_IDS.length; i++) if (LANGUAGE_IDS[i] == id) return LANGUAGE_NAMES[i];
        return "Other";
    }

    static String languagesLabel() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < LANGUAGE_IDS.length; i++) {
            if (languages().contains(LANGUAGE_IDS[i])) b.append(b.length() == 0 ? "" : ", ").append(LANGUAGE_NAMES[i]);
        }
        return b.toString();
    }

    // ---- Login -------------------------------------------------------------------------------

    /** Sends the OTP to a 10-digit Jio number; {@code done} gets null on success or an error message. */
    static void sendOtp(String number, Epg.Callback<String> done) {
        Net.IO.execute(() -> {
            String err = null;
            try {
                JSONObject body = new JSONObject().put("number", b64("+91" + number));
                Response r = request("POST", API + "/userservice/apis/v1/loginotp/send", loginHeaders(), json(body));
                if (r.code != 204 && r.code != 200) {
                    Log.w(TAG, "send OTP: " + r.code + " " + r.body);
                    err = "Couldn't send the OTP (" + r.code + ")" + reason(r.body);
                }
            } catch (IOException | JSONException e) {
                err = "Couldn't reach JioTV";
            }
            String result = err;
            MAIN.post(() -> done.done(result));
        });
    }

    /** Verifies the OTP and stores the session; {@code done} gets null on success or an error message. */
    static void verifyOtp(String number, String otp, Epg.Callback<String> done) {
        Net.IO.execute(() -> {
            String err = null;
            try {
                JSONObject body = new JSONObject()
                        .put("number", b64("+91" + number))
                        .put("otp", otp)
                        .put("deviceInfo", new JSONObject()
                                .put("consumptionDeviceName", "SM-G930F")
                                .put("info", new JSONObject()
                                        .put("type", "android")
                                        .put("platform", new JSONObject().put("name", "SM-G930F").put("version", ""))
                                        .put("androidId", deviceId())));
                Response r = request("POST", API + "/userservice/apis/v1/loginotp/verify", loginHeaders(), json(body));
                JSONObject o = r.code == 200 ? new JSONObject(r.body) : null;
                String access = o == null ? "" : o.optString("authToken");
                if (access.isEmpty()) {
                    Log.w(TAG, "verify OTP: " + r.code + " " + r.body);
                    err = r.code == 200 ? "Wrong OTP" : "Sign-in failed (" + r.code + ")" + reason(r.body);
                } else {
                    JSONObject user = o.optJSONObject("sessionAttributes") == null ? null
                            : o.optJSONObject("sessionAttributes").optJSONObject("user");
                    long now = System.currentTimeMillis();
                    sp.edit()
                            .putString("number", number)
                            .putString("access", access)
                            .putString("refresh", o.optString("refreshToken"))
                            .putString("sso", o.optString("ssoToken"))
                            .putString("crm", user == null ? "" : user.optString("subscriberId"))
                            .putString("unique", user == null ? "" : user.optString("unique"))
                            .putLong("accessAt", now)
                            .putLong("ssoAt", now)
                            .apply();
                }
            } catch (IOException | JSONException e) {
                err = "Couldn't reach JioTV";
            }
            String result = err;
            MAIN.post(() -> done.done(result));
        });
    }

    static void logout() {
        sp.edit().remove("access").remove("refresh").remove("sso").remove("crm").remove("unique")
                .remove("accessAt").remove("ssoAt").remove("number").apply();
    }

    /** Refreshes the access and SSO tokens when they're about to expire (JioTV Go's rules). */
    static synchronized void ensureFresh() {
        if (!loggedIn()) return;
        try {
            if (expiring(sp.getString("access", ""), sp.getLong("accessAt", 0), 2 * HOUR, 10 * MIN)) refreshAccess();
            if (expiring(sp.getString("sso", ""), sp.getLong("ssoAt", 0), 24 * HOUR, HOUR)) refreshSso();
        } catch (IOException | JSONException e) {
            Log.w(TAG, "token refresh failed", e);
        }
    }

    private static final long MIN = 60_000L, HOUR = 60 * MIN;

    private static boolean expiring(String token, long savedAt, long fallbackTtl, long fallbackLead) {
        long exp = jwtExpiry(token);
        long now = System.currentTimeMillis();
        if (exp > 0) return exp <= now + 30_000;
        return savedAt == 0 || now >= savedAt + fallbackTtl - fallbackLead;
    }

    private static long jwtExpiry(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) return 0;
        try {
            byte[] json = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(json, StandardCharsets.UTF_8)).optLong("exp", 0) * 1000L;
        } catch (IllegalArgumentException | JSONException e) {
            return 0;
        }
    }

    private static void refreshAccess() throws IOException, JSONException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("devicetype", "phone");
        h.put("versionCode", "422");
        h.put("os", "android");
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("User-Agent", OKHTTP);
        h.put("accessToken", sp.getString("access", ""));
        JSONObject body = new JSONObject().put("appName", "RJIL_JioTV").put("deviceId", deviceId())
                .put("refreshToken", sp.getString("refresh", ""));
        Response r = request("POST", REFRESH_ACCESS, h, body.toString());
        String token = r.code == 200 ? new JSONObject(r.body).optString("authToken") : "";
        if (!token.isEmpty()) {
            sp.edit().putString("access", token).putLong("accessAt", System.currentTimeMillis()).apply();
        } else {
            Log.w(TAG, "access refresh: " + r.code);
        }
    }

    private static void refreshSso() throws IOException, JSONException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("devicetype", "phone");
        h.put("versionCode", "422");
        h.put("os", "android");
        h.put("User-Agent", OKHTTP);
        h.put("ssoToken", sp.getString("sso", ""));
        h.put("uniqueid", sp.getString("unique", ""));
        h.put("deviceid", deviceId());
        Response r = request("GET", REFRESH_SSO, h, null);
        String token = r.code == 200 ? new JSONObject(r.body).optString("ssoToken") : "";
        if (!token.isEmpty()) {
            sp.edit().putString("sso", token).putLong("ssoAt", System.currentTimeMillis()).apply();
        } else {
            Log.w(TAG, "sso refresh: " + r.code);
        }
    }

    // ---- Channels ----------------------------------------------------------------------------

    /** The account's channels in the chosen languages, as library entries. */
    static List<Channel> fetchChannels() throws IOException {
        ensureFresh();
        Response r = request("GET", CHANNELS_AUTH, channelHeaders(true), null);
        if (r.code != 200) r = request("GET", CHANNELS_PUBLIC, channelHeaders(false), null);
        if (r.code != 200) throw new IOException("JioTV channels: HTTP " + r.code);
        List<Channel> out = new ArrayList<>();
        Set<Integer> langs = languages();
        try {
            JSONArray list = new JSONObject(r.body).getJSONArray("result");
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                int lang = o.optInt("channelLanguageId");
                if (!langs.contains(lang)) continue;
                String id = o.optString("channel_id");
                String name = o.optString("channel_name").trim();
                if (id.isEmpty() || name.isEmpty()) continue;
                List<String> groups = new ArrayList<>();
                groups.add(GROUP);
                String cat = category(o.optInt("channelCategoryId"));
                if (cat != null) groups.add(cat);
                String language = languageName(lang);
                if (!groups.contains(language)) groups.add(language);
                String logo = o.optString("logoUrl");
                out.add(new Channel(SCHEME + id, name, logo.isEmpty() ? null : LOGO + logo,
                        groups.toArray(new String[0]), SCHEME + id, null, null));
            }
        } catch (JSONException e) {
            throw new IOException("JioTV channels: bad JSON", e);
        }
        return out;
    }

    /** JioTV categories mapped onto the library's iptv-org category names, so they merge. */
    private static String category(int id) {
        switch (id) {
            case 5: return "Entertainment";
            case 6: return "Movies";
            case 7: return "Kids";
            case 8: return "Sports";
            case 9: return "Lifestyle";
            case 10: return "Documentary";
            case 12: return "News";
            case 13: return "Music";
            case 15: case 19: return "Religious";
            case 16: return "Business";
            case 17: return "Education";
            case 18: return "Shopping";
            default: return null;
        }
    }

    // ---- Playback ----------------------------------------------------------------------------

    /** A resolved stream: an HLS link plus the token its media requests need as a cookie. */
    static final class Stream {
        final String url, hdnea, error;
        /** Token expiry (ms since epoch), 0 if unknown. */
        final long expires;

        Stream(String url, String hdnea, String error) {
            this.url = url;
            this.hdnea = hdnea;
            this.error = error;
            this.expires = hdneaExpiry(hdnea);
        }
    }

    /** Asks JioTV for a channel's stream; never returns null. */
    static Stream resolve(String channelId) {
        if (!loggedIn()) return new Stream(null, null, "Sign in to JioTV in Settings");
        ensureFresh();
        try {
            Map<String, String> h = tvHeaders(channelId);
            h.put("Content-Type", "application/x-www-form-urlencoded");
            h.put("accessToken", sp.getString("access", ""));
            String form = "channel_id=" + channelId + "&stream_type=Seek&begin=" + utc("yyyyMMdd'T'HHmmss")
                    + "&srno=" + utc("yyyyMMdd");
            Response r = request("POST", PLAYBACK, h, form);
            if (r.code == 401 || r.code == 419) return new Stream(null, null, "JioTV sign-in expired. Sign in again in Settings");
            if (r.code != 200) return new Stream(null, null, "JioTV couldn't play this channel (" + r.code + ")");
            JSONObject o = new JSONObject(r.body);
            JSONObject bitrates = o.optJSONObject("bitrates");
            String url = null;
            if (bitrates != null) {
                for (String q : new String[] {"high", "auto", "medium", "low"}) {
                    String u = bitrates.optString(q);
                    if (!u.isEmpty()) {
                        url = u;
                        break;
                    }
                }
            }
            if (url == null && o.optString("result").contains(".m3u8")) url = o.optString("result");
            if (url == null) {
                boolean drm = o.optBoolean("isDRM") || o.optJSONObject("mpd") != null;
                return new Stream(null, null, drm ? "This JioTV channel is DRM-protected (not supported yet)"
                        : "JioTV has no stream for this channel");
            }
            if (url.startsWith("//")) url = "https:" + url;
            // The token comes as "__hdnea__=" on newer links and "hdnea=" on older ones, URL-encoded.
            String token = param(url, "__hdnea__");
            if (token == null) token = param(url, "hdnea");
            return new Stream(url, token == null ? null : android.net.Uri.decode(token), null);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "resolve " + channelId, e);
            return new Stream(null, null, "Couldn't reach JioTV");
        }
    }

    /** Headers JioTV expects on playlist, segment and key requests (JioTV Go's TV headers). */
    static Map<String, String> tvHeaders(String channelId) {
        String crm = sp.getString("crm", "");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("appkey", APPKEY);
        h.put("channel_id", channelId);
        h.put("channelId", channelId);
        h.put("crmid", crm);
        h.put("userId", crm);
        h.put("subscriberId", crm);
        h.put("deviceId", deviceId());
        h.put("devicetype", "phone");
        h.put("isott", "false");
        h.put("languageId", "6");
        h.put("lbcookie", "1");
        h.put("os", "android");
        h.put("osVersion", "13");
        h.put("uniqueId", sp.getString("unique", ""));
        h.put("usergroup", USERGROUP);
        h.put("versionCode", "422");
        h.put("ssotoken", sp.getString("sso", ""));
        h.put("srno", "230203144000");
        h.put("User-Agent", OKHTTP);
        return h;
    }

    /**
     * Cookie for one media request: the stream token, plus (for key requests) the key URL's own
     * query values, which JioTV's key server also expects as cookies.
     */
    static String cookieFor(String url, String hdnea) {
        StringBuilder c = new StringBuilder();
        if (hdnea != null && !hdnea.isEmpty()) c.append("__hdnea__=").append(hdnea);
        int q = url.indexOf('?');
        if (q >= 0 && url.toLowerCase(Locale.ROOT).contains("key")) {
            for (String pair : url.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq);
                if (k.equals("hdnea") || k.equals("__hdnea__")) continue;
                c.append(c.length() == 0 ? "" : "; ").append(k).append('=').append(pair.substring(eq + 1));
            }
        }
        return c.toString();
    }

    /** "st=…~exp=1760000000~acl=…" → exp in ms, or 0. */
    static long hdneaExpiry(String hdnea) {
        if (hdnea == null) return 0;
        int i = hdnea.indexOf("exp=");
        if (i < 0) return 0;
        int end = i + 4;
        while (end < hdnea.length() && Character.isDigit(hdnea.charAt(end))) end++;
        try {
            return Long.parseLong(hdnea.substring(i + 4, end)) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Guide -------------------------------------------------------------------------------

    /** Today's programmes for a JioTV channel (empty if unavailable). */
    static List<Epg.Programme> guide(String channelId) {
        List<Epg.Programme> out = new ArrayList<>();
        for (int day = 0; day <= 1; day++) {
            try {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("User-Agent", OKHTTP);
                Response r = request("GET", String.format(Locale.ROOT, EPG, day, channelId), h, null);
                if (r.code != 200) break;
                JSONArray list = new JSONObject(r.body).optJSONArray("epg");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject p = list.getJSONObject(i);
                    long s = p.optLong("startEpoch"), e = p.optLong("endEpoch");
                    if (s > 0 && e > s) out.add(new Epg.Programme(s, e, p.optString("showname"), p.optString("description")));
                }
            } catch (IOException | JSONException e) {
                break;
            }
        }
        return out;
    }

    // ---- HTTP --------------------------------------------------------------------------------

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static Response request(String method, String url, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", OKHTTP);
            c.setRequestProperty("Accept-Encoding", "gzip");
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                if (!headers.containsKey("Content-Type")) c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return new Response(code, "");
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            try (InputStream s = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
                return new Response(code, out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    /** JioTV's own error text, if its reply carries one (": Invalid mobile number"). */
    private static String reason(String body) {
        try {
            JSONObject o = new JSONObject(body);
            for (String k : new String[] {"message", "errorMessage", "error", "msg"}) {
                Object v = o.opt(k);
                if (v instanceof String && !((String) v).isEmpty()) return ": " + v;
                if (v instanceof JSONObject && !((JSONObject) v).optString("message").isEmpty()) {
                    return ": " + ((JSONObject) v).optString("message");
                }
            }
        } catch (JSONException ignored) {
            // not JSON
        }
        return "";
    }

    private static Map<String, String> loginHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("appname", "RJIL_JioTV");
        h.put("os", "android");
        h.put("devicetype", "phone");
        return h;
    }

    private static Map<String, String> channelHeaders(boolean auth) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("devicetype", "phone");
        h.put("os", "android");
        h.put("appkey", APPKEY);
        h.put("lbcookie", "1");
        h.put("usertype", "JIO");
        if (auth) {
            String crm = sp.getString("crm", "");
            h.put("versionCode", "422");
            h.put("usergroup", USERGROUP);
            h.put("languageId", "6");
            h.put("isott", "false");
            h.put("accessToken", sp.getString("access", ""));
            h.put("ssotoken", sp.getString("sso", ""));
            h.put("crmid", crm);
            h.put("subscriberId", crm);
            h.put("userId", crm);
            h.put("uniqueId", sp.getString("unique", ""));
        }
        return h;
    }

    /** Random 16-hex-digit device id, created once (like JioTV Go's). */
    private static String deviceId() {
        String id = sp.getString("device", "");
        if (id.isEmpty()) {
            byte[] b = new byte[8];
            new SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
            id = s.toString();
            sp.edit().putString("device", id).apply();
        }
        return id;
    }

    /** JSON text as Go would write it: Android's JSONObject escapes slashes, which JioTV may reject. */
    private static String json(JSONObject o) {
        return o.toString().replace("\\/", "/");
    }

    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String utc(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String param(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String pair : url.substring(q + 1).split("&")) {
            if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
        }
        return null;
    }
}
