package com.segar.livetv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Favourites (in the order added), last channel, view mode and category. */
final class Prefs {
    private static SharedPreferences sp;
    private static List<String> favs;

    private Prefs() {}

    static void init(Context c) {
        if (sp != null) return;
        sp = c.getApplicationContext().getSharedPreferences("livetv", Context.MODE_PRIVATE);
        String s = sp.getString("favs", "");
        favs = new ArrayList<>();
        if (!s.isEmpty()) favs.addAll(Arrays.asList(s.split("\n")));
    }

    static boolean isFav(String key) { return favs.contains(key); }

    static List<String> favs() { return favs; }

    /** Returns the new state. */
    static boolean toggleFav(String key) {
        boolean add = !favs.remove(key);
        if (add) favs.add(key);
        sp.edit().putString("favs", android.text.TextUtils.join("\n", favs)).apply();
        return add;
    }

    static String last() { return sp.getString("last", null); }
    static void setLast(String key) { sp.edit().putString("last", key).apply(); }

    static String lastCategory() { return sp.getString("cat", Repo.ALL); }
    static void setLastCategory(String cat) { sp.edit().putString("cat", cat).apply(); }

    static boolean listMode() { return sp.getBoolean("list", false); }
    static void setListMode(boolean list) { sp.edit().putBoolean("list", list).apply(); }

    // Settings panel
    /** Always pick the best rendition the stream offers (default), or adapt to the network. */
    static boolean highestQuality() { return sp.getBoolean("hq", true); }
    static void setHighestQuality(boolean on) { sp.edit().putBoolean("hq", on).apply(); }

    /** Open straight into the last channel watched (default), or stay on the channel grid. */
    static boolean resumeLast() { return sp.getBoolean("resume", true); }
    static void setResumeLast(boolean on) { sp.edit().putBoolean("resume", on).apply(); }

    /** D-pad up goes to the next channel number, instead of the previous one (default). */
    static boolean upIsNext() { return sp.getBoolean("upNext", false); }
    static void setUpIsNext(boolean on) { sp.edit().putBoolean("upNext", on).apply(); }

    /** Seconds the player's info bar stays up. */
    static int infoSeconds() { return sp.getInt("infoSecs", 5); }
    static void setInfoSeconds(int s) { sp.edit().putInt("infoSecs", s).apply(); }

    static final int ASPECT_16_9 = 0, ASPECT_ORIGINAL = 1, ASPECT_ZOOM = 2;

    /** How video fills the screen: 16:9 (default), the stream's own shape, or zoomed to fill. */
    static int aspectMode() { return sp.getInt("aspect", ASPECT_16_9); }
    static void setAspectMode(int mode) { sp.edit().putInt("aspect", mode).apply(); }

    static void clearFavs() {
        favs.clear();
        sp.edit().remove("favs").apply();
    }
}
