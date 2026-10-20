package com.segar.livetv;

import android.app.Activity;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.segar.livetv.Ui.px;

/** Fullscreen live playback with channel zapping, info bar, and channel and guide panels. */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {
    static final String EXTRA_KEY = "key";
    static final String EXTRA_CATEGORY = "category";

    private static final long ZAP_DELAY_MS = 350;
    private static final long DIGITS_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SurfaceView surface;
    private View info;
    private TextView infoNumber, infoName, infoNow, infoTime, infoNext, infoAudio, status, hint, digits;
    private ImageView infoStar;
    private View progressTrack, progressFill;
    private ImageView spinner;
    private View panel;
    private ExoPlayer player;
    private List<Channel> channels = Collections.emptyList();
    private String category;
    private int index = -1;
    private int previous = -1;
    private int pendingNumber;
    private VideoSize videoSize = VideoSize.UNKNOWN;

    private final Runnable hideInfo = () -> info.setVisibility(View.GONE);
    private final Runnable startPlayback = this::startPlayback;
    private final Runnable showTuning = this::showSpinner;
    private final Runnable commitDigits = this::commitDigits;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Prefs.init(this);
        ImageLoader.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
        category = getIntent().getStringExtra(EXTRA_CATEGORY);

        root = new FrameLayout(this);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitSurface());
        buildStatus();
        buildInfo();
        setContentView(root);

        String key = state != null ? state.getString(EXTRA_KEY) : getIntent().getStringExtra(EXTRA_KEY);
        if (Repo.all().isEmpty()) {
            Repo.setListener(() -> resolve(key));
            Repo.load(this);
        } else {
            resolve(key);
        }
    }

    private void resolve(String key) {
        if (index >= 0 && !channels.isEmpty()) return;
        channels = Repo.filter(category == null ? Repo.ALL : category);
        Channel c = Repo.get(key);
        int i = c == null ? -1 : channels.indexOf(c);
        if (i < 0) {
            channels = Repo.all();
            i = c == null ? 0 : channels.indexOf(c);
        }
        if (channels.isEmpty()) {
            finish();
            return;
        }
        tune(Math.max(0, i), false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        createPlayer();
        if (index >= 0) startPlayback();
        Epg.refresh(this::updateInfo);
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
        closePanel(); // come back to plain video
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (index >= 0) out.putString(EXTRA_KEY, channels.get(index).key);
    }

    // ---- Playback --------------------------------------------------------------------------

    private void createPlayer() {
        if (player != null) return;
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 20_000, 1_500, 3_000) // small buffers for 1 GB TVs
                .build();
        // Prefer H.264 when a stream offers a choice; fall back to another decoder if one fails.
        DefaultTrackSelector selector = new DefaultTrackSelector(this);
        // Highest quality (Settings, on by default): always take the top rendition the TV can decode.
        DefaultTrackSelector.Parameters.Builder params = selector.buildUponParameters()
                .setPreferredVideoMimeType(MimeTypes.VIDEO_H264);
        if (Prefs.highestQuality()) {
            params.setForceHighestSupportedBitrate(true).clearViewportSizeConstraints();
        }
        selector.setParameters(params);
        player = new ExoPlayer.Builder(this, new RenderersFactory(this).setEnableDecoderFallback(true))
                .setTrackSelector(selector)
                .setLoadControl(load)
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_BUFFERING) {
                    handler.postDelayed(showTuning, 700);
                } else {
                    handler.removeCallbacks(showTuning);
                    if (s == Player.STATE_READY) hideStatus();
                }
            }

            @Override public void onPlayerError(PlaybackException e) {
                handler.removeCallbacks(showTuning);
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                showStatus(Strings.UNAVAILABLE, Strings.CHANGE_HINT);
            }

            @Override public void onVideoSizeChanged(VideoSize size) {
                videoSize = size;
                fitSurface();
            }

            @Override public void onTracksChanged(Tracks tracks) {
                updateInfo();
            }
        });
    }

    private void releasePlayer() {
        handler.removeCallbacks(startPlayback);
        handler.removeCallbacks(showTuning);
        if (spinner != null) hideSpinner();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void startPlayback() {
        if (player == null || index < 0) return;
        Channel c = channels.get(index);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(c.userAgent != null ? c.userAgent : Net.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);
        if (c.referrer != null) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", c.referrer);
            http.setDefaultRequestProperties(headers);
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(c.url);
        if (c.url.contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
            // Read the real tracks from the first segment instead of trusting the playlist's
            // CODECS label: some channels list only the video codec, which hides their audio.
            player.setMediaSource(new HlsMediaSource.Factory(http)
                    .setAllowChunklessPreparation(false)
                    .createMediaSource(item.build()));
        } else {
            player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        }
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /** Switches channel now (UI) and starts the stream once the remote has been still briefly. */
    private void tune(int i, boolean debounce) {
        if (channels.isEmpty()) return;
        i = (i % channels.size() + channels.size()) % channels.size();
        if (i != index && index >= 0) previous = index;
        index = i;
        Prefs.setLast(channels.get(i).key);
        hideStatus();
        handler.removeCallbacks(showTuning);
        if (player != null) {
            player.stop();
            // A language picked on one channel shouldn't follow you to the next.
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
        }
        videoSize = VideoSize.UNKNOWN;
        showInfo();
        handler.removeCallbacks(startPlayback);
        if (debounce) handler.postDelayed(startPlayback, ZAP_DELAY_MS);
        else startPlayback();
    }

    private void fitSurface() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        // Settings › Aspect ratio: 16:9 (default), the stream's own shape, or zoom to fill.
        int mode = Prefs.aspectMode();
        boolean known = videoSize.width > 0 && videoSize.height > 0;
        float aspect = mode == Prefs.ASPECT_16_9 || !known
                ? 16f / 9f
                : videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height;
        boolean wider = aspect > w / (float) h;
        int sw = w, sh = h;
        if (mode == Prefs.ASPECT_ZOOM) {
            if (wider) sw = Math.round(h * aspect); // cover the screen; the root clips the edges
            else sh = Math.round(w / aspect);
        } else if (wider) {
            sh = Math.round(w / aspect);
        } else {
            sw = Math.round(h * aspect);
        }
        if (lp.width != sw || lp.height != sh) {
            lp.width = sw;
            lp.height = sh;
            surface.setLayoutParams(lp);
        }
    }

    /** Platform renderers first; the Java MP2 decoder only when the TV has no MP2 decoder. */
    private static final class RenderersFactory extends DefaultRenderersFactory {
        RenderersFactory(android.content.Context context) {
            super(context);
        }

        @Override
        protected void buildAudioRenderers(android.content.Context context, int extensionRendererMode,
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector selector, boolean decoderFallback,
                androidx.media3.exoplayer.audio.AudioSink sink, Handler handler,
                androidx.media3.exoplayer.audio.AudioRendererEventListener listener,
                java.util.ArrayList<androidx.media3.exoplayer.Renderer> out) {
            super.buildAudioRenderers(context, extensionRendererMode, selector, decoderFallback, sink, handler, listener, out);
            out.add(new Mp2Renderer(handler, listener, sink));
        }
    }

    // ---- Info bar --------------------------------------------------------------------------

    private void buildInfo() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF0000000, 0xB0000000, 0x00000000}));
        root.addView(bar, new FrameLayout.LayoutParams(-1, px(420), Gravity.BOTTOM));

        int x = Ui.MARGIN;
        infoNumber = Ui.text(this, 30, Ui.TEXT_SECONDARY, Ui.semibold);
        bar.addView(infoNumber, Ui.at(px(x), px(130), px(400), px(40)));
        infoName = Ui.text(this, 56, Ui.TEXT_PRIMARY, Ui.semibold);
        bar.addView(infoName, Ui.at(px(x), px(176), px(1300), px(70)));
        infoStar = new ImageView(this);
        infoStar.setImageDrawable(new LineIcon(LineIcon.STAR));
        bar.addView(infoStar, Ui.at(px(x), px(176), px(40), px(40)));

        infoNow = Ui.text(this, 30, Ui.TEXT_LIST, Ui.regular);
        bar.addView(infoNow, Ui.at(px(x), px(266), px(1100), px(40)));
        infoTime = Ui.text(this, 24, Ui.TEXT_SECONDARY, Ui.regular);
        infoTime.setGravity(Gravity.END);
        bar.addView(infoTime, Ui.at(px(1920 - Ui.MARGIN - 400), px(272), px(400), px(32)));

        progressTrack = new View(this);
        progressTrack.setBackground(Ui.rounded(Ui.GLASS_FOCUS, 3));
        bar.addView(progressTrack, Ui.at(px(x), px(322), px(1920 - 2 * Ui.MARGIN), px(6)));
        progressFill = new View(this);
        progressFill.setBackground(Ui.rounded(Ui.TEXT_PRIMARY, 3));
        bar.addView(progressFill, Ui.at(px(x), px(322), 0, px(6)));

        infoNext = Ui.text(this, 24, Ui.TEXT_SECONDARY, Ui.regular);
        bar.addView(infoNext, Ui.at(px(x), px(352), px(1100), px(34)));
        infoAudio = Ui.text(this, 24, Ui.TEXT_SECONDARY, Ui.regular);
        infoAudio.setGravity(Gravity.END);
        bar.addView(infoAudio, Ui.at(px(1920 - Ui.MARGIN - 500), px(352), px(500), px(34)));

        TextClock clock = new TextClock(this);
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(40));
        clock.setTextColor(Ui.TEXT_PRIMARY);
        clock.setTypeface(Ui.semibold);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setGravity(Gravity.END);
        bar.addView(clock, Ui.at(px(1920 - Ui.MARGIN - 300), px(184), px(300), px(52)));

        info = bar;
        info.setVisibility(View.GONE);
    }

    private void showInfo() {
        updateInfo();
        info.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, Prefs.infoSeconds() * 1000L);
    }

    private void updateInfo() {
        if (index < 0 || info == null) return;
        Channel c = channels.get(index);
        infoNumber.setText(String.valueOf(c.number));
        infoName.setText(c.name);
        boolean fav = Prefs.isFav(c.key);
        infoStar.setVisibility(fav ? View.VISIBLE : View.GONE);
        infoName.measure(0, 0);
        FrameLayout.LayoutParams starLp = (FrameLayout.LayoutParams) infoStar.getLayoutParams();
        starLp.leftMargin = px(Ui.MARGIN) + Math.min(infoName.getMeasuredWidth(), px(1300)) + px(20);
        starLp.topMargin = px(190);
        infoStar.setLayoutParams(starLp);

        Epg.Programme now = Epg.current(c.key), next = Epg.next(c.key);
        long t = System.currentTimeMillis();
        boolean hasNow = now != null;
        infoNow.setText(hasNow ? now.title : "");
        infoTime.setText(hasNow ? time(now.start) + " – " + time(now.end) : "");
        progressTrack.setVisibility(hasNow ? View.VISIBLE : View.GONE);
        progressFill.setVisibility(hasNow ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
        lp.width = hasNow ? Math.round(px(1920 - 2 * Ui.MARGIN) * now.progress(t)) : 0;
        progressFill.setLayoutParams(lp);
        infoNext.setText(next != null ? Strings.NEXT + "   " + time(next.start) + "   " + next.title : "");
        List<AudioTrack> audio = audioTracks();
        AudioTrack on = audio.isEmpty() ? null : audio.get(0);
        for (AudioTrack a : audio) if (a.selected) on = a;
        infoAudio.setText(audio.size() > 1 ? "\u25C0  " + Strings.AUDIO + ": " + on.label : "");
    }

    private String time(long ms) {
        return DateFormat.getTimeFormat(this).format(new java.util.Date(ms));
    }

    // ---- Status (tuning / errors) and number entry -----------------------------------------

    private void buildStatus() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        status = Ui.text(this, 34, Ui.TEXT_PRIMARY, Ui.semibold);
        status.setGravity(Gravity.CENTER);
        hint = Ui.text(this, 24, Ui.TEXT_SECONDARY, Ui.regular);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = px(16);
        box.addView(status, new LinearLayout.LayoutParams(-2, -2));
        box.addView(hint, hlp);
        root.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        hideStatus();

        digits = Ui.text(this, 96, Ui.TEXT_PRIMARY, Ui.semibold);
        digits.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dlp.topMargin = px(64);
        dlp.rightMargin = px(Ui.MARGIN);
        root.addView(digits, dlp);

        spinner = new ImageView(this);
        spinner.setImageResource(R.drawable.tuning);
        spinner.setContentDescription(Strings.TUNING);
        spinner.setVisibility(View.GONE);
        root.addView(spinner, Ui.centered(px(144), px(144)));
    }

    /** Animated tuning mark while the stream buffers. */
    private void showSpinner() {
        hideStatus();
        spinner.setVisibility(View.VISIBLE);
        ((AnimatedVectorDrawable) spinner.getDrawable()).start();
    }

    private void hideSpinner() {
        if (spinner.getVisibility() == View.GONE) return;
        ((AnimatedVectorDrawable) spinner.getDrawable()).stop();
        spinner.setVisibility(View.GONE);
    }

    private void showStatus(String text, String sub) {
        hideSpinner();
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        hint.setText(sub == null ? "" : sub);
        hint.setVisibility(sub == null ? View.GONE : View.VISIBLE);
    }

    private void hideStatus() {
        if (spinner != null) hideSpinner();
        status.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
    }

    private void digit(int d) {
        pendingNumber = pendingNumber * 10 + d;
        if (pendingNumber > 9999) pendingNumber = d;
        digits.setText(String.valueOf(pendingNumber));
        digits.setVisibility(View.VISIBLE);
        handler.removeCallbacks(commitDigits);
        handler.postDelayed(commitDigits, DIGITS_MS);
    }

    private void commitDigits() {
        digits.setVisibility(View.GONE);
        int n = pendingNumber;
        pendingNumber = 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).number == n) {
                tune(i, false);
                return;
            }
        }
    }

    // ---- Side panels: channel list and guide -----------------------------------------------

    private FrameLayout sidePanel(String titleText) {
        closePanel();
        FrameLayout p = new FrameLayout(this);
        p.setBackgroundColor(Ui.PANEL_SURFACE);
        p.setPadding(px(48), px(92), px(48), px(48));
        TextView title = Ui.text(this, 40, Ui.TEXT_PRIMARY, Ui.semibold);
        title.setText(titleText);
        p.addView(title, Ui.at(0, 0, -1, px(52)));
        root.addView(p, new FrameLayout.LayoutParams(px(640), -1, Gravity.END));
        panel = p;
        info.setVisibility(View.GONE);
        return p;
    }

    private ListView panelList(FrameLayout p) {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setSelector(Ui.listSelector());
        l.setVerticalScrollBarEnabled(false);
        l.setOverScrollMode(View.OVER_SCROLL_NEVER);
        p.addView(l, Ui.at(0, px(52 + 36), -1, -1));
        return l;
    }

    private void openChannels() {
        FrameLayout p = sidePanel(category == null || Repo.ALL.equals(category) ? Strings.CHANNELS : category);
        ListView l = panelList(p);
        l.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return channels.size(); }
            @Override public Object getItem(int i) { return channels.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout row;
                TextView name, now;
                if (v == null) {
                    row = new LinearLayout(PlayerActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(px(24), 0, px(24), 0);
                    row.setLayoutParams(new AbsListView.LayoutParams(-1, px(96)));
                    name = Ui.text(PlayerActivity.this, 30, Ui.TEXT_LIST, Ui.regular);
                    now = Ui.text(PlayerActivity.this, 22, Ui.TEXT_SECONDARY, Ui.regular);
                    row.addView(name);
                    row.addView(now);
                } else {
                    row = (LinearLayout) v;
                    name = (TextView) row.getChildAt(0);
                    now = (TextView) row.getChildAt(1);
                }
                Channel c = channels.get(i);
                name.setText(c.number + "   " + c.name);
                Epg.Programme prog = Epg.current(c.key);
                now.setText(prog == null ? "" : prog.title);
                now.setVisibility(prog == null ? View.GONE : View.VISIBLE);
                return row;
            }
        });
        l.setOnItemClickListener((parent, v, pos, id) -> {
            closePanel();
            tune(pos, false);
        });
        l.setSelection(index);
        l.requestFocus();
    }

    private void openGuide() {
        Channel c = channels.get(index);
        FrameLayout p = sidePanel(c.name);
        TextView note = Ui.text(this, 24, Ui.TEXT_SECONDARY, Ui.regular);
        note.setText(Strings.LOADING_GUIDE);
        p.addView(note, Ui.at(0, px(52 + 36), -1, px(40)));
        ListView l = panelList(p);
        l.setFocusable(true);
        l.requestFocus();
        Epg.schedule(c.key, list -> {
            if (panel != p) return;
            if (list.isEmpty()) {
                note.setText(Strings.NO_GUIDE);
                return;
            }
            note.setVisibility(View.GONE);
            long t = System.currentTimeMillis();
            int current = 0;
            for (int i = 0; i < list.size(); i++) if (list.get(i).end <= t) current = i + 1;
            l.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return list.size(); }
                @Override public Object getItem(int i) { return list.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View v, ViewGroup parent) {
                    TextView row = v != null ? (TextView) v : Ui.text(PlayerActivity.this, 26, Ui.TEXT_LIST, Ui.regular);
                    row.setPadding(px(24), 0, px(24), 0);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setLayoutParams(new AbsListView.LayoutParams(-1, px(80)));
                    Epg.Programme prog = list.get(i);
                    boolean live = prog.start <= t && t < prog.end;
                    row.setText(time(prog.start) + "    " + prog.title);
                    row.setTextColor(prog.end <= t ? Ui.TEXT_SECONDARY : Ui.TEXT_LIST);
                    row.setTypeface(live ? Ui.semibold : Ui.regular);
                    return row;
                }
            });
            l.setSelection(Math.min(current, list.size() - 1));
            l.requestFocus();
        });
    }

    private void closePanel() {
        if (panel == null) return;
        root.removeView(panel);
        panel = null;
    }

    // ---- Audio tracks ----------------------------------------------------------------------

    /** One selectable audio track: a track inside one of the player's audio groups. */
    private static final class AudioTrack {
        final Tracks.Group group;
        final int index;
        final String label;
        final boolean selected;

        AudioTrack(Tracks.Group group, int index, String label, boolean selected) {
            this.group = group;
            this.index = index;
            this.label = label;
            this.selected = selected;
        }
    }

    /** Playable audio tracks of the current stream (alternate HLS renditions show up here). */
    private List<AudioTrack> audioTracks() {
        List<AudioTrack> out = new ArrayList<>();
        if (player == null) return out;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                out.add(new AudioTrack(g, i, audioLabel(g.getTrackFormat(i), out.size() + 1), g.isTrackSelected(i)));
            }
        }
        return out;
    }

    private static String audioLabel(Format f, int n) {
        String name = f.label;
        if ((name == null || name.isEmpty()) && f.language != null && !f.language.equals(C.LANGUAGE_UNDETERMINED)) {
            name = new Locale(f.language).getDisplayLanguage();
        }
        if (name == null || name.isEmpty()) name = Strings.TRACK + " " + n;
        if (f.channelCount > 2) name += f.channelCount == 6 ? "  5.1" : "  " + f.channelCount + " ch";
        return name;
    }

    private void selectAudio(AudioTrack t) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(t.group.getMediaTrackGroup(), t.index))
                .build());
    }

    /** The remote's audio button: step to the next track and say which one is on. */
    private void cycleAudio() {
        List<AudioTrack> list = audioTracks();
        if (list.size() < 2) return;
        int cur = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).selected) cur = i;
        AudioTrack next = list.get((cur + 1) % list.size());
        selectAudio(next);
        showInfo();
        infoAudio.setText(Strings.AUDIO + ": " + next.label);
    }

    private void openAudio() {
        List<AudioTrack> list = audioTracks();
        FrameLayout p = sidePanel(Strings.AUDIO);
        ListView l = panelList(p);
        int current = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).selected) current = i;
        l.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return list.size(); }
            @Override public Object getItem(int i) { return list.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                TextView row = v != null ? (TextView) v : Ui.text(PlayerActivity.this, 30, Ui.TEXT_LIST, Ui.regular);
                row.setPadding(px(24), 0, px(24), 0);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setLayoutParams(new AbsListView.LayoutParams(-1, px(88)));
                AudioTrack t = list.get(i);
                row.setText(t.selected ? t.label + "   \u2713" : t.label);
                row.setTypeface(t.selected ? Ui.semibold : Ui.regular);
                return row;
            }
        });
        l.setOnItemClickListener((parent, v, pos, id) -> {
            selectAudio(list.get(pos));
            closePanel();
        });
        l.setSelection(current);
        l.requestFocus();
    }

    // ---- Remote ----------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (panel != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                closePanel();
                return true;
            }
            return super.onKeyDown(keyCode, e);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                tune(index + (Prefs.upIsNext() ? 1 : -1), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                tune(index + (Prefs.upIsNext() ? -1 : 1), true);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
                tune(index - 1, true);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                tune(index + 1, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) openChannels();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_GUIDE:
                if (e.getRepeatCount() == 0 && index >= 0) openGuide();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (e.getRepeatCount() == 0 && audioTracks().size() > 1) openAudio();
                else showInfo();
                return true;
            case KeyEvent.KEYCODE_INFO:
                showInfo();
                return true;
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                if (e.getRepeatCount() == 0) cycleAudio();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BOOKMARK:
                if (e.getRepeatCount() == 0 && index >= 0) {
                    boolean added = Prefs.toggleFav(channels.get(index).key);
                    showInfo();
                    infoNext.setText(added ? Strings.ADDED : Strings.REMOVED);
                }
                return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (previous >= 0) tune(previous, false);
                return true;
            default:
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    digit(keyCode - KeyEvent.KEYCODE_0);
                    return true;
                }
                return super.onKeyDown(keyCode, e);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (panel != null) closePanel();
        else super.onBackPressed();
    }
}
