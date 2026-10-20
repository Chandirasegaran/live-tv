package com.segar.livetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Channel logos: downloaded once to disk, decoded down to the size they're shown at,
 * kept in a small memory cache. Requests for views that were recycled meanwhile are dropped.
 */
final class ImageLoader {
    private static final int MAX_BYTES = 512 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static LruCache<String, Bitmap> memory;
    private static File dir;

    private ImageLoader() {}

    static void init(Context c) {
        if (memory != null) return;
        int kb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 8, 24 * 1024);
        memory = new LruCache<String, Bitmap>(kb) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
        dir = new File(c.getCacheDir(), "logos");
        dir.mkdirs();
    }

    /** Shows {@code url} in {@code iv}, decoded to fit {@code w}×{@code h}. Calls {@code onMissing} if it can't. */
    static void load(ImageView iv, String url, int w, int h, Runnable onMissing) {
        iv.setImageDrawable(null);
        iv.setTag(url);
        if (url == null) {
            onMissing.run();
            return;
        }
        String id = url + '@' + w;
        Bitmap hit = memory.get(id);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        POOL.execute(() -> {
            if (!url.equals(iv.getTag())) return; // recycled before we started
            Bitmap b = decode(url, w, h);
            if (b != null) memory.put(id, b);
            MAIN.post(() -> {
                if (!url.equals(iv.getTag())) return;
                if (b != null) iv.setImageBitmap(b);
                else onMissing.run();
            });
        });
    }

    private static Bitmap decode(String url, int w, int h) {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
        try {
            byte[] data;
            if (f.exists()) {
                data = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0, n;
                    while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
                }
            } else {
                data = Net.bytes(url, MAX_BYTES);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            float s = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
            if (s < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                        Math.max(1, Math.round(b.getHeight() * s)), true);
                if (scaled != b) b.recycle();
                b = scaled;
            }
            return b;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
