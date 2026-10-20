package com.segar.livetv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Updates from GitHub Releases: the latest release's tag (e.g. "v1.1") is compared with our
 * versionName, and its .apk asset is installed through the platform PackageInstaller.
 * The new APK must be signed with the same key as the installed one.
 */
final class Updater {
    static final String REPO = "Chandirasegaran/live-tv";
    private static final String TAG = "Updater";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The newest release, when it's newer than what's installed. */
    static final class Release {
        final String version, apkUrl;
        final long size;

        Release(String version, String apkUrl, long size) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.size = size;
        }
    }

    interface Listener {
        void status(String text);
    }

    private Updater() {}

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Looks up the latest release; delivers it on the main thread, or null when we're up to date or it failed. */
    static void check(Context c, Epg.Callback<Release> cb, Listener status) {
        String current = installedVersion(c);
        Net.IO.execute(() -> {
            Release out = null;
            String msg = null;
            try {
                JSONObject r = new JSONObject(new String(
                        Net.bytes("https://api.github.com/repos/" + REPO + "/releases/latest", 1 << 20),
                        StandardCharsets.UTF_8));
                String tag = r.optString("tag_name", "").replaceFirst("^[vV]", "");
                JSONArray assets = r.optJSONArray("assets");
                String url = null;
                long size = 0;
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url");
                        size = a.optLong("size");
                        break;
                    }
                }
                if (newer(tag, current) && url != null) out = new Release(tag, url, size);
                else msg = Strings.UP_TO_DATE + " (" + current + ")";
            } catch (IOException e) {
                // 404 means the repository has no release yet.
                msg = e.getMessage() != null && e.getMessage().startsWith("HTTP 404")
                        ? Strings.UP_TO_DATE + " (" + current + ")" : Strings.UPDATE_FAILED;
                Log.i(TAG, "check failed: " + e.getMessage());
            } catch (JSONException e) {
                msg = Strings.UPDATE_FAILED;
            }
            Release result = out;
            String text = msg;
            MAIN.post(() -> {
                if (text != null) status.status(text);
                cb.done(result);
            });
        });
    }

    /** Downloads the APK and asks Android to install it. Progress and errors go to {@code status}. */
    static void install(Activity a, Release r, Listener status) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            // One-time permission: "Install unknown apps" for Live TV.
            status.status(Strings.ALLOW_INSTALLS);
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (RuntimeException e) {
                Log.w(TAG, "no unknown-sources screen", e);
            }
            return;
        }
        Context app = a.getApplicationContext();
        File apk = new File(app.getCacheDir(), "update.apk");
        Net.IO.execute(() -> {
            try {
                download(r, apk, status);
                MAIN.post(() -> status.status(Strings.INSTALLING));
                commit(app, apk);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "install failed", e);
                MAIN.post(() -> status.status(Strings.UPDATE_FAILED));
            }
        });
    }

    private static void download(Release r, File out, Listener status) throws IOException {
        HttpURLConnection c = Net.open(r.apkUrl);
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            long total = r.size > 0 ? r.size : c.getContentLength();
            byte[] buf = new byte[32 * 1024];
            long done = 0;
            int n, lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : -1;
                if (pct != lastPct) {
                    lastPct = pct;
                    String text = Strings.DOWNLOADING + (pct >= 0 ? " " + pct + "%" : "");
                    MAIN.post(() -> status.status(text));
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static void commit(Context app, File apk) throws IOException {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(app, id,
                    new Intent(app, StatusReceiver.class), flags);
            s.commit(pending.getIntentSender());
        }
    }

    /**
     * Android answers the commit here (declared in the manifest, not exported). When it needs
     * the user's OK, show its install screen.
     */
    public static final class StatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                Log.w(TAG, "install status " + st + ": " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }

    /** "1.10" > "1.9"; missing parts count as 0. */
    static boolean newer(String latest, String current) {
        String[] a = latest.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int num(String s) {
        String d = s.replaceAll("[^0-9].*$", "");
        try {
            return d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
