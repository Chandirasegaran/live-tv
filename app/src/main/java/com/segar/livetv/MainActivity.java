package com.segar.livetv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.segar.livetv.Ui.px;

/** Home: clock, category pills, and the channel grid or list. All views are built in code. */
public class MainActivity extends Activity {
    private static final int CONTENT_TOP = 340;
    private static final int ROW_H = 96;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root, content;
    private LinearLayout pills;
    private HorizontalScrollView pillScroll;
    private ImageView modeIcon, searchIcon;
    private GridView grid;
    private ListView list;
    private TextView empty;
    private ChannelAdapter adapter;
    private View overlay; // open search panel or dropdown menu
    private String category;
    private boolean listMode;
    private boolean autoResumed;
    private Runnable pendingCategory;
    private List<String> pillCategories;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Prefs.init(this);
        Jio.init(this);
        ImageLoader.init(this);
        Ui.background(this);
        category = Prefs.lastCategory();
        listMode = Prefs.listMode();
        autoResumed = state != null;

        root = new FrameLayout(this);
        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        buildHeader();
        buildPills();
        buildChannels();
        setContentView(root);

        // Entering the pill row from elsewhere lands on the selected category, not the nearest one.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now != null && now.getParent() == pills && (was == null || was.getParent() != pills)
                    && !category.equals(now.getTag())) {
                // Posted: this listener runs before the pill's own focus callback.
                pills.post(this::focusSelectedPill);
            }
        });

        Repo.setListener(this::onChannels);
        if (!Repo.all().isEmpty()) onChannels();
        Repo.load(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Repo.setListener(this::onChannels);
        Repo.load(this);
        Epg.refresh(this::refreshVisible);
        if (favouritesShown()) showCategory(category, false); // favourites may have changed in the player
        else refreshVisible();
    }

    private boolean favouritesShown() {
        return Repo.FAVOURITES.equals(category);
    }

    // ---- Header: date, clock, icon buttons -------------------------------------------------

    private void buildHeader() {
        TextClock date = new TextClock(this);
        style(date, 30, Ui.TEXT_SECONDARY, false);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        content.addView(date, Ui.at(px(Ui.MARGIN), px(56), px(900), px(40)));

        TextClock clock = new TextClock(this);
        style(clock, 88, Ui.TEXT_PRIMARY, true);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setLetterSpacing(-0.028f);
        clock.setFontFeatureSettings("tnum");
        content.addView(clock, Ui.at(px(Ui.MARGIN), px(98), px(600), px(104)));

        int right = 1920 - Ui.MARGIN;
        modeIcon = iconButton(listMode ? LineIcon.GRID : LineIcon.LIST,
                listMode ? Strings.GRID_VIEW : Strings.LIST_VIEW, v -> toggleMode());
        content.addView(modeIcon, Ui.at(px(right - 72 - 12 - 72), px(60), px(72), px(72)));
        searchIcon = iconButton(LineIcon.SEARCH, Strings.SEARCH, v -> openSearch());
        content.addView(searchIcon, Ui.at(px(right - 3 * 72 - 2 * 12), px(60), px(72), px(72)));
        ImageView settings = iconButton(LineIcon.SETTINGS, Strings.SETTINGS, v -> openSettings());
        content.addView(settings, Ui.at(px(right - 72), px(60), px(72), px(72)));
    }

    private void style(TextView t, int size, int color, boolean semibold) {
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(semibold ? Ui.semibold : Ui.regular);
        t.setIncludeFontPadding(false);
    }

    private ImageView iconButton(int[] icon, String label, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setId(View.generateViewId());
        b.setImageDrawable(new LineIcon(icon));
        b.setPadding(px(16), px(16), px(16), px(16));
        b.setContentDescription(label);
        b.setFocusable(true);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0);
        b.setBackground(circle);
        b.setOnFocusChangeListener((v, has) -> Ui.focus(v, circle, has, 0));
        b.setOnClickListener(click);
        return b;
    }

    // ---- Category pills --------------------------------------------------------------------

    private void buildPills() {
        pillScroll = new HorizontalScrollView(this);
        pillScroll.setHorizontalScrollBarEnabled(false);
        pillScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pillScroll.setClipChildren(false);
        pillScroll.setClipToPadding(false);
        pillScroll.setPadding(px(Ui.MARGIN), 0, px(Ui.MARGIN), 0);
        pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        pills.setClipChildren(false);
        pillScroll.addView(pills, new FrameLayout.LayoutParams(-2, -1));
        content.addView(pillScroll, Ui.at(0, px(232), -1, px(96)));
    }

    private void rebuildPills() {
        List<String> cats = Repo.categories();
        if (cats.equals(pillCategories)) return;
        pillCategories = cats;
        boolean hadFocus = pills.hasFocus();
        pills.removeAllViews();
        for (String cat : Repo.categories()) {
            TextView p = Ui.text(this, 26, Ui.TEXT_MENU, Ui.regular);
            p.setText(cat);
            p.setGravity(Gravity.CENTER);
            p.setPadding(px(30), 0, px(30), 0);
            p.setFocusable(true);
            p.setTag(cat);
            p.setNextFocusUpId(searchIcon.getId()); // the icons sit far right, out of the D-pad's straight path
            GradientDrawable bg = Ui.glass(30);
            p.setBackground(bg);
            p.setOnFocusChangeListener((v, has) -> {
                Ui.focus(v, bg, has, cat.equals(category) ? Ui.GLASS_ACTIVE : Ui.GLASS);
                if (has) handler.removeCallbacks(pendingCategory);
                if (has && !cat.equals(category)) {
                    // Switch on focus, after the D-pad settles.
                    pendingCategory = () -> {
                        if (v.isFocused()) showCategory(cat, true);
                    };
                    handler.postDelayed(pendingCategory, 280);
                }
            });
            p.setOnClickListener(v -> showCategory(cat, true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(60));
            lp.rightMargin = px(16);
            pills.addView(p, lp);
        }
        stylePills();
        if (hadFocus) focusSelectedPill();
    }

    private void focusSelectedPill() {
        View sel = pills.findViewWithTag(category);
        if (sel != null) sel.requestFocus();
    }

    private void stylePills() {
        for (int i = 0; i < pills.getChildCount(); i++) {
            TextView p = (TextView) pills.getChildAt(i);
            boolean selected = p.getTag().equals(category);
            p.setTextColor(selected ? Ui.TEXT_PRIMARY : Ui.TEXT_MENU);
            p.setTypeface(selected ? Ui.semibold : Ui.regular);
            ((GradientDrawable) p.getBackground()).setColor(
                    p.hasFocus() ? Ui.GLASS_FOCUS : selected ? Ui.GLASS_ACTIVE : Ui.GLASS);
        }
    }

    // ---- Channel grid / list ---------------------------------------------------------------

    private void buildChannels() {
        adapter = new ChannelAdapter();

        grid = new GridView(this);
        grid.setNumColumns(Ui.PER_ROW);
        grid.setColumnWidth(px(Ui.TILE_W));
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setHorizontalSpacing(px(Ui.GAP));
        grid.setVerticalSpacing(0);
        setupAdapterView(grid);
        // Tiles may grow past their cells on focus, but scrolled rows must not draw over
        // the header: this holder clips at the top of the channel area. The views' top
        // padding leaves room for the focused first row.
        FrameLayout holder = new FrameLayout(this);
        content.addView(holder, Ui.at(0, px(CONTENT_TOP), -1, -1));
        holder.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        setupAdapterView(list);
        holder.addView(list, new FrameLayout.LayoutParams(-1, -1));

        empty = Ui.text(this, 26, Ui.TEXT_SECONDARY, Ui.regular);
        empty.setSingleLine(false);
        content.addView(empty, Ui.at(px(Ui.MARGIN), px(CONTENT_TOP + 40), px(1200), -2));

        applyMode();
    }

    private void setupAdapterView(AbsListView v) {
        v.setAdapter(adapter);
        v.setSelector(new android.graphics.drawable.ColorDrawable(0));
        v.setDrawSelectorOnTop(false);
        v.setClipChildren(false);
        v.setClipToPadding(false);
        v.setVerticalScrollBarEnabled(false);
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        v.setPadding(px(Ui.MARGIN), px(20), px(Ui.MARGIN), px(80));
        v.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                refreshFocus((AbsListView) p);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { refreshFocus((AbsListView) p); }
        });
        v.setOnFocusChangeListener((view, has) -> refreshFocus((AbsListView) view));
        v.setOnItemClickListener((p, view, pos, id) -> play(adapter.getItem(pos)));
        v.setOnItemLongClickListener((p, view, pos, id) -> {
            showMenu(view, adapter.getItem(pos));
            return true;
        });
    }

    /**
     * Redraws focus on the visible tiles/rows. Recomputed from position rather than tracked by
     * reference: AbsListView builds throwaway children while measuring.
     */
    private void refreshFocus(AbsListView v) {
        int sel = v.hasFocus() ? v.getSelectedItemPosition() : -1;
        for (int i = 0; i < v.getChildCount(); i++) {
            adapter.setFocused(v.getChildAt(i), v.getFirstVisiblePosition() + i == sel, true);
        }
    }

    private AbsListView channelsView() {
        return listMode ? list : grid;
    }

    private void applyMode() {
        grid.setVisibility(listMode ? View.GONE : View.VISIBLE);
        list.setVisibility(listMode ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void toggleMode() {
        listMode = !listMode;
        Prefs.setListMode(listMode);
        modeIcon.setImageDrawable(new LineIcon(listMode ? LineIcon.GRID : LineIcon.LIST));
        modeIcon.setContentDescription(listMode ? Strings.GRID_VIEW : Strings.LIST_VIEW);
        applyMode();
    }

    private void onChannels() {
        if (!Repo.categories().contains(category)) category = Repo.ALL;
        rebuildPills();
        showCategory(category, false);
        if (!autoResumed) {
            autoResumed = true;
            Channel last = Prefs.resumeLast() ? Repo.get(Prefs.last()) : null;
            channelsView().requestFocus();
            if (last != null) play(last);
        }
    }

    private void showCategory(String cat, boolean user) {
        boolean changed = !cat.equals(category);
        category = cat;
        if (user) Prefs.setLastCategory(cat);
        String selectedKey = null;
        int sel = channelsView().getSelectedItemPosition();
        if (!changed && sel >= 0 && sel < adapter.getCount()) selectedKey = adapter.getItem(sel).key;

        adapter.set(Repo.filter(cat));
        stylePills();
        if (adapter.getCount() == 0) {
            empty.setText(Repo.FAVOURITES.equals(cat) ? Strings.NO_FAVOURITES : Strings.LOADING);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
        int pos = 0;
        if (selectedKey != null) {
            for (int i = 0; i < adapter.getCount(); i++) {
                if (adapter.getItem(i).key.equals(selectedKey)) { pos = i; break; }
            }
        }
        grid.setSelection(pos);
        list.setSelection(pos);
    }

    private void refreshVisible() {
        adapter.notifyDataSetChanged();
    }

    private void play(Channel c) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_KEY, c.key);
        i.putExtra(PlayerActivity.EXTRA_CATEGORY, category);
        startActivity(i);
    }

    // ---- Dropdown menu (Menu key / hold OK) ------------------------------------------------

    private void showMenu(View anchor, Channel c) {
        closeOverlay();
        boolean fav = Prefs.isFav(c.key);
        TextView row = Ui.text(this, 26, Ui.TEXT_MENU, Ui.regular);
        row.setText(fav ? Strings.REMOVE_FAVOURITE : Strings.ADD_FAVOURITE);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(24), 0, px(24), 0);
        row.setFocusable(true);
        GradientDrawable rowBg = Ui.rounded(0, Ui.RADIUS - 10);
        row.setBackground(rowBg);
        row.setOnFocusChangeListener((v, has) -> {
            rowBg.setColor(has ? Ui.GLASS_FOCUS : 0);
            row.setTextColor(has ? Ui.TEXT_PRIMARY : Ui.TEXT_MENU);
        });
        row.setOnClickListener(v -> {
            Prefs.toggleFav(c.key);
            closeOverlay();
            if (favouritesShown()) showCategory(category, false);
            else refreshVisible();
        });

        FrameLayout menu = new FrameLayout(this);
        GradientDrawable bg = Ui.rounded(Ui.MENU_SURFACE, Ui.RADIUS);
        bg.setStroke(Math.max(1, px(2)), Ui.GLASS_STROKE);
        menu.setBackground(bg);
        menu.setPadding(px(10), px(10), px(10), px(10));
        menu.setElevation(px(30));
        menu.addView(row, new FrameLayout.LayoutParams(-1, px(64)));

        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int w = Math.max(px(320), (int) row.getPaint().measureText(row.getText().toString()) + px(68));
        int h = px(84);
        int tileBottom = loc[1] + (listMode ? anchor.getHeight() : px(Ui.TILE_H + 8));
        int y = tileBottom + px(listMode ? 8 : 64);
        if (y + h > root.getHeight()) y = loc[1] - h - px(16);
        int x = listMode ? loc[0] + px(260) : loc[0];
        x = Math.min(x, root.getWidth() - w - px(Ui.MARGIN));
        root.addView(menu, Ui.at(x, y, w, h));
        openOverlay(menu, row);
    }

    // ---- Search panel ----------------------------------------------------------------------

    private void openSearch() {
        closeOverlay();
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(Ui.SCRIM);

        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Ui.PANEL_SURFACE);
        panel.setPadding(px(48), px(92), px(48), px(48));
        layer.addView(panel, new FrameLayout.LayoutParams(px(640), -1, Gravity.END));

        TextView title = Ui.text(this, 40, Ui.TEXT_PRIMARY, Ui.semibold);
        title.setText(Strings.SEARCH);
        panel.addView(title, Ui.at(0, 0, -1, px(52)));

        EditText input = new EditText(this);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(30));
        input.setTextColor(Ui.TEXT_PRIMARY);
        input.setHintTextColor(Ui.TEXT_SECONDARY);
        input.setHint(Strings.SEARCH_HINT);
        input.setTypeface(Ui.regular);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setPadding(px(24), 0, px(24), 0);
        GradientDrawable inputBg = Ui.glass(Ui.RADIUS);
        input.setBackground(inputBg);
        input.setOnFocusChangeListener((v, has) -> inputBg.setColor(has ? Ui.GLASS_FOCUS : Ui.GLASS));
        panel.addView(input, Ui.at(0, px(52 + 36), -1, px(80)));

        List<Channel> results = new ArrayList<>();
        ListView rows = new ListView(this);
        rows.setDivider(null);
        rows.setSelector(Ui.listSelector());
        rows.setVerticalScrollBarEnabled(false);
        rows.setOverScrollMode(View.OVER_SCROLL_NEVER);
        BaseAdapter rowAdapter = new BaseAdapter() {
            @Override public int getCount() { return results.size(); }
            @Override public Channel getItem(int i) { return results.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                TextView t = v != null ? (TextView) v : Ui.text(MainActivity.this, 30, Ui.TEXT_LIST, Ui.regular);
                t.setPadding(px(24), 0, px(24), 0);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(px(88));
                t.setLayoutParams(new AbsListView.LayoutParams(-1, px(88)));
                Channel c = results.get(i);
                t.setText(c.number + "   " + c.name);
                return t;
            }
        };
        rows.setAdapter(rowAdapter);
        rows.setOnItemClickListener((p, v, pos, id) -> {
            closeOverlay();
            play(results.get(pos));
        });
        panel.addView(rows, Ui.at(0, px(52 + 36 + 80 + 24), -1, -1));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                results.clear();
                List<Channel> found = Repo.search(s.toString());
                results.addAll(found.subList(0, Math.min(100, found.size())));
                rowAdapter.notifyDataSetChanged();
            }
        });
        input.setOnEditorActionListener((v, action, e) -> {
            hideKeyboard(input);
            if (!results.isEmpty()) rows.requestFocus();
            return true;
        });

        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        openOverlay(layer, input);
        input.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---- Settings panel ----------------------------------------------------------------------

    /** One settings row: shows its current value, and OK changes it (updating the value line). */
    private interface Setting {
        String value();
        void ok(TextView value);
        /** Focus left the row (e.g. to cancel a pending confirmation). */
        default void blur(TextView value) {}
    }

    private void openSettings() {
        String[] titles = {Strings.QUALITY, Strings.ASPECT, Strings.ON_START, Strings.UP_BUTTON, Strings.INFO_BAR,
                Strings.JIOTV, Strings.REFRESH_CHANNELS, Strings.REFRESH_GUIDE, Strings.CLEAR_FAVS, Strings.UPDATES, Strings.ABOUT};
        boolean[] armed = {false}; // Clear favourites needs a second OK
        Updater.Release[] update = {null}; // found by the first OK on "Check for updates"
        Setting[] rows = {
                new Setting() {
                    public String value() { return Prefs.highestQuality() ? Strings.QUALITY_HIGH : Strings.QUALITY_AUTO; }
                    public void ok(TextView v) { Prefs.setHighestQuality(!Prefs.highestQuality()); v.setText(value()); }
                },
                new Setting() {
                    public String value() {
                        int m = Prefs.aspectMode();
                        return m == Prefs.ASPECT_ZOOM ? Strings.ASPECT_ZOOM
                                : m == Prefs.ASPECT_ORIGINAL ? Strings.ASPECT_ORIGINAL : Strings.ASPECT_16_9;
                    }
                    public void ok(TextView v) {
                        Prefs.setAspectMode((Prefs.aspectMode() + 1) % 3);
                        v.setText(value());
                    }
                },
                new Setting() {
                    public String value() { return Prefs.resumeLast() ? Strings.START_LAST : Strings.START_GRID; }
                    public void ok(TextView v) { Prefs.setResumeLast(!Prefs.resumeLast()); v.setText(value()); }
                },
                new Setting() {
                    public String value() { return Prefs.upIsNext() ? Strings.UP_NEXT : Strings.UP_PREV; }
                    public void ok(TextView v) { Prefs.setUpIsNext(!Prefs.upIsNext()); v.setText(value()); }
                },
                new Setting() {
                    public String value() { return Prefs.infoSeconds() + Strings.SECONDS; }
                    public void ok(TextView v) {
                        int s = Prefs.infoSeconds();
                        Prefs.setInfoSeconds(s == 3 ? 5 : s == 5 ? 8 : 3);
                        v.setText(value());
                    }
                },
                new Setting() {
                    public String value() {
                        return Jio.loggedIn()
                                ? Strings.JIO_ON + " · " + Jio.maskedNumber() + " · " + Repo.jioCount() + Strings.CHANNELS_COUNT
                                : Strings.JIO_OFF;
                    }
                    public void ok(TextView v) { openJio(); }
                },
                new Setting() {
                    public String value() { return Repo.all().size() + Strings.CHANNELS_COUNT; }
                    public void ok(TextView v) {
                        v.setText(Strings.WORKING);
                        Repo.refresh(MainActivity.this, ok ->
                                v.setText(ok ? Strings.DONE + " · " + value() : Strings.FAILED));
                    }
                },
                new Setting() {
                    public String value() { return Epg.channelCount() + Strings.CHANNELS_COUNT + Strings.GUIDE_COUNT; }
                    public void ok(TextView v) {
                        v.setText(Strings.WORKING);
                        int before = Epg.channelCount();
                        Epg.forceRefresh(() -> {
                            refreshVisible();
                            v.setText(Strings.DONE + " · " + value());
                        });
                        // forceRefresh only calls back with new data; report a failure after a while.
                        v.postDelayed(() -> {
                            if (Strings.WORKING.contentEquals(v.getText())) {
                                v.setText(Epg.channelCount() > 0 && Epg.channelCount() == before
                                        ? Strings.DONE + " · " + value() : Strings.FAILED);
                            }
                        }, 15000);
                    }
                },
                new Setting() {
                    public String value() { return Prefs.favs().size() + Strings.CHANNELS_COUNT; }
                    public void ok(TextView v) {
                        if (!armed[0]) {
                            armed[0] = true;
                            v.setText(Strings.CONFIRM);
                            return;
                        }
                        armed[0] = false;
                        Prefs.clearFavs();
                        v.setText(value());
                        if (favouritesShown()) showCategory(category, false);
                        else refreshVisible();
                    }
                    public void blur(TextView v) {
                        if (armed[0]) { // moving away cancels "Clear favourites"
                            armed[0] = false;
                            v.setText(value());
                        }
                    }
                },
                new Setting() {
                    public String value() { return "Version " + Updater.installedVersion(MainActivity.this); }
                    public void ok(TextView v) {
                        if (update[0] != null) {
                            Updater.install(MainActivity.this, update[0], v::setText);
                            return;
                        }
                        v.setText(Strings.CHECKING);
                        Updater.check(MainActivity.this, r -> {
                            update[0] = r;
                            if (r != null) v.setText(String.format(Strings.UPDATE_AVAILABLE, r.version));
                        }, v::setText);
                    }
                },
                new Setting() {
                    public String value() {
                        String version = "";
                        try {
                            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                            // Our own package always exists.
                        }
                        return "Version " + version + " · " + Repo.all().size() + Strings.CHANNELS_COUNT
                                + " · " + Epg.channelCount() + Strings.GUIDE_COUNT;
                    }
                    public void ok(TextView v) { v.setText(value()); }
                },
        };

        openPanel(Strings.SETTINGS, titles, rows);
    }

    /** A right-hand panel (640 wide, over a dim) with a title; returns {layer, content}. */
    private FrameLayout[] sidePanel(String titleText) {
        closeOverlay();
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(Ui.SCRIM);
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Ui.PANEL_SURFACE);
        panel.setPadding(px(48), px(92), px(48), px(48));
        layer.addView(panel, new FrameLayout.LayoutParams(px(640), -1, Gravity.END));
        TextView title = Ui.text(this, 40, Ui.TEXT_PRIMARY, Ui.semibold);
        title.setText(titleText);
        panel.addView(title, Ui.at(0, 0, -1, px(52)));
        return new FrameLayout[] {layer, panel};
    }

    /** A side panel of title/value rows; OK on a row runs its {@link Setting#ok}. */
    private void openPanel(String titleText, String[] titles, Setting[] rows) {
        FrameLayout[] p = sidePanel(titleText);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        View first = null;
        for (int i = 0; i < rows.length; i++) {
            Setting row = rows[i];
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(24), 0, px(24), 0);
            r.setFocusable(true);
            GradientDrawable bg = Ui.rounded(0, Ui.RADIUS);
            r.setBackground(bg);
            TextView name = Ui.text(this, 28, Ui.TEXT_LIST, Ui.regular);
            name.setText(titles[i]);
            TextView value = Ui.text(this, 22, Ui.TEXT_SECONDARY, Ui.regular);
            value.setText(row.value());
            r.addView(name);
            r.addView(value);
            r.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? Ui.GLASS_FOCUS : 0);
                name.setTextColor(has ? Ui.TEXT_PRIMARY : Ui.TEXT_LIST);
                if (!has) row.blur(value);
            });
            r.setOnClickListener(v -> row.ok(value));
            list.addView(r, new LinearLayout.LayoutParams(-1, px(96)));
            if (first == null) first = r;
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(list);
        p[1].addView(scroll, Ui.at(0, px(52 + 36), -1, -1));
        root.addView(p[0], new FrameLayout.LayoutParams(-1, -1));
        openOverlay(p[0], first);
    }

    // ---- JioTV -------------------------------------------------------------------------------

    private void openJio() {
        if (!Jio.loggedIn()) {
            openJioSignIn();
            return;
        }
        boolean[] armed = {false}; // signing out needs a second OK
        String[] titles = {Strings.JIO_ACCOUNT, Strings.JIO_LANGUAGES, Strings.JIO_REFRESH, Strings.JIO_SIGN_OUT};
        Setting[] rows = {
                new Setting() {
                    public String value() { return Jio.maskedNumber() + " · " + Repo.jioCount() + Strings.CHANNELS_COUNT; }
                    public void ok(TextView v) { v.setText(value()); }
                },
                new Setting() {
                    public String value() { return Jio.languagesLabel(); }
                    public void ok(TextView v) { openJioLanguages(); }
                },
                new Setting() {
                    public String value() { return Strings.JIO_REFRESH_HINT; }
                    public void ok(TextView v) {
                        v.setText(Strings.WORKING);
                        Repo.reloadJio(MainActivity.this, n ->
                                v.setText(n >= 0 ? Strings.DONE + " · " + n + Strings.CHANNELS_COUNT : Strings.FAILED));
                    }
                },
                new Setting() {
                    public String value() { return Strings.JIO_SIGN_OUT_HINT; }
                    public void ok(TextView v) {
                        if (!armed[0]) {
                            armed[0] = true;
                            v.setText(Strings.CONFIRM);
                            return;
                        }
                        Jio.logout();
                        Repo.clearJio(MainActivity.this);
                        closeOverlay();
                    }
                    public void blur(TextView v) {
                        if (armed[0]) {
                            armed[0] = false;
                            v.setText(value());
                        }
                    }
                },
        };
        openPanel(Strings.JIOTV, titles, rows);
    }

    /** Pick JioTV languages; "Save" reloads the JioTV channels with the new choice. */
    private void openJioLanguages() {
        java.util.Set<Integer> chosen = Jio.languages();
        int n = Jio.LANGUAGE_IDS.length;
        String[] titles = new String[n + 1];
        Setting[] rows = new Setting[n + 1];
        titles[0] = Strings.JIO_SAVE;
        rows[0] = new Setting() {
            public String value() { return Strings.JIO_SAVE_HINT; }
            public void ok(TextView v) {
                if (chosen.isEmpty()) {
                    v.setText(Strings.JIO_PICK_ONE);
                    return;
                }
                Jio.setLanguages(chosen);
                v.setText(Strings.WORKING);
                Repo.reloadJio(MainActivity.this, count -> {
                    if (count >= 0) openJio();
                    else v.setText(Strings.FAILED);
                });
            }
        };
        for (int i = 0; i < n; i++) {
            int id = Jio.LANGUAGE_IDS[i];
            titles[i + 1] = Jio.LANGUAGE_NAMES[i];
            rows[i + 1] = new Setting() {
                public String value() { return chosen.contains(id) ? Strings.JIO_INCLUDED : Strings.JIO_NOT_INCLUDED; }
                public void ok(TextView v) {
                    if (!chosen.remove(id)) chosen.add(id);
                    v.setText(value());
                }
            };
        }
        openPanel(Strings.JIO_LANGUAGES, titles, rows);
    }

    /** Number, then OTP, typed with the remote's on-screen keyboard. */
    private void openJioSignIn() {
        FrameLayout[] p = sidePanel(Strings.JIOTV);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView intro = Ui.text(this, 24, Ui.TEXT_SECONDARY, Ui.regular);
        intro.setSingleLine(false);
        intro.setText(Strings.JIO_INTRO);
        col.addView(intro, new LinearLayout.LayoutParams(-1, -2));

        EditText number = jioField(Strings.JIO_NUMBER_HINT, InputType.TYPE_CLASS_PHONE, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(80));
        lp.topMargin = px(28);
        col.addView(number, lp);

        EditText otp = jioField(Strings.JIO_OTP_HINT, InputType.TYPE_CLASS_NUMBER, 8);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, px(80));
        lp2.topMargin = px(20);
        otp.setVisibility(View.GONE);
        col.addView(otp, lp2);

        TextView status = Ui.text(this, 24, Ui.TEXT_SECONDARY, Ui.regular);
        status.setSingleLine(false);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-1, -2);
        lp3.topMargin = px(24);
        col.addView(status, lp3);

        number.setOnEditorActionListener((v, action, e) -> {
            String n = number.getText().toString().replaceAll("[^0-9]", "");
            if (n.length() > 10) n = n.substring(n.length() - 10);
            if (n.length() != 10) {
                status.setText(Strings.JIO_BAD_NUMBER);
                return true;
            }
            String num = n;
            status.setText(Strings.JIO_SENDING);
            Jio.sendOtp(num, err -> {
                if (err != null) {
                    status.setText(err);
                    return;
                }
                status.setText(Strings.JIO_OTP_SENT);
                otp.setVisibility(View.VISIBLE);
                otp.requestFocus();
                otp.setTag(num);
            });
            return true;
        });
        otp.setOnEditorActionListener((v, action, e) -> {
            String code = otp.getText().toString().trim();
            if (code.isEmpty() || otp.getTag() == null) return true;
            hideKeyboard(otp);
            status.setText(Strings.JIO_VERIFYING);
            Jio.verifyOtp((String) otp.getTag(), code, err -> {
                if (err != null) {
                    status.setText(err);
                    otp.requestFocus();
                    return;
                }
                status.setText(Strings.JIO_LOADING);
                Repo.reloadJio(MainActivity.this, n -> {
                    if (n >= 0) {
                        status.setText(String.format(Strings.JIO_ADDED, n));
                        status.postDelayed(this::openJio, 1500);
                    } else {
                        status.setText(Strings.JIO_SIGNED_IN_NO_CHANNELS);
                    }
                });
            });
            return true;
        });

        p[1].addView(col, Ui.at(0, px(52 + 36), -1, -2));
        root.addView(p[0], new FrameLayout.LayoutParams(-1, -1));
        openOverlay(p[0], number);
        number.post(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(number, InputMethodManager.SHOW_IMPLICIT));
    }

    private EditText jioField(String hint, int inputType, int maxLength) {
        EditText f = new EditText(this);
        f.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(30));
        f.setTextColor(Ui.TEXT_PRIMARY);
        f.setHintTextColor(Ui.TEXT_SECONDARY);
        f.setHint(hint);
        f.setTypeface(Ui.regular);
        f.setSingleLine(true);
        f.setInputType(inputType);
        f.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(maxLength + 3)});
        f.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        f.setPadding(px(24), 0, px(24), 0);
        GradientDrawable bg = Ui.glass(Ui.RADIUS);
        f.setBackground(bg);
        f.setOnFocusChangeListener((v, has) -> bg.setColor(has ? Ui.GLASS_FOCUS : Ui.GLASS));
        return f;
    }

    // ---- Overlay plumbing ------------------------------------------------------------------

    private void openOverlay(View v, View focus) {
        overlay = v;
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        focus.requestFocus();
    }

    private void closeOverlay() {
        if (overlay == null) return;
        hideKeyboard(overlay);
        root.removeView(overlay);
        overlay = null;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        channelsView().requestFocus();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && overlay == null) {
            AbsListView v = channelsView();
            int pos = v.getSelectedItemPosition();
            if (v.hasFocus() && pos >= 0 && pos < adapter.getCount()) {
                showMenu(v.getSelectedView(), adapter.getItem(pos));
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (overlay != null) closeOverlay();
        else super.onBackPressed();
    }

    // ---- Adapter: grid tiles and list rows -------------------------------------------------

    private final class ChannelAdapter extends BaseAdapter {
        private List<Channel> items = Collections.emptyList();

        void set(List<Channel> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Channel getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return listMode ? 1 : 0; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Holder h = v == null ? (listMode ? row() : tile()) : (Holder) v.getTag();
            Channel c = items.get(pos);
            h.name.setText(c.name);
            h.name.setVisibility(View.GONE);
            ImageLoader.load(h.logo, c.logo, h.logoW, h.logoH, () -> h.name.setVisibility(View.VISIBLE));
            h.star.setVisibility(Prefs.isFav(c.key) ? View.VISIBLE : View.GONE);
            if (h.number != null) {
                h.number.setText(String.valueOf(c.number));
                Epg.Programme now = Epg.current(c.key);
                h.now.setText(now == null ? "" : now.title);
                h.title.setText(c.name);
            } else {
                h.caption.setText(c.number + "  " + c.name);
            }
            AbsListView owner = (AbsListView) parent;
            boolean focused = owner.hasFocus() && owner.getSelectedItemPosition() == pos;
            setFocused(h.root, focused, false);
            return h.root;
        }

        /** Draws a tile or row as focused or not; animates only when {@code animate}. */
        void setFocused(View v, boolean focused, boolean animate) {
            Holder h = (Holder) v.getTag();
            if (h == null || (h.focused == focused && animate)) return;
            h.focused = focused;
            if (h.caption != null) {
                h.caption.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                if (animate) Ui.focus(h.box, h.bg, focused, Ui.GLASS);
                else {
                    h.bg.setColor(focused ? Ui.GLASS_FOCUS : Ui.GLASS);
                    h.box.animate().cancel();
                    h.box.setScaleX(focused ? 1.1f : 1f);
                    h.box.setScaleY(focused ? 1.1f : 1f);
                    h.box.setTranslationZ(focused ? px(26) : 0);
                }
            } else {
                h.bg.setColor(focused ? Ui.GLASS_FOCUS : 0);
                h.title.setTextColor(focused ? Ui.TEXT_PRIMARY : Ui.TEXT_LIST);
            }
        }

        private Holder tile() {
            Holder h = new Holder();
            FrameLayout cell = new FrameLayout(MainActivity.this);
            cell.setClipChildren(false);
            cell.setLayoutParams(new AbsListView.LayoutParams(px(Ui.TILE_W), px(Ui.ROW_STEP)));

            h.box = new FrameLayout(MainActivity.this);
            h.bg = Ui.glass(Ui.RADIUS);
            h.box.setBackground(h.bg);
            h.box.setPivotX(px(Ui.TILE_W) / 2f);
            h.box.setPivotY(px(Ui.TILE_H) / 2f);
            cell.addView(h.box, Ui.at(0, 0, px(Ui.TILE_W), px(Ui.TILE_H)));

            h.logoW = px(Ui.TILE_W - 72);
            h.logoH = px(Ui.TILE_H - 44);
            h.logo = new ImageView(MainActivity.this);
            h.logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            h.box.addView(h.logo, Ui.centered(h.logoW, h.logoH));

            h.name = Ui.text(MainActivity.this, 24, Ui.TEXT_TILE, Ui.regular);
            h.name.setGravity(Gravity.CENTER);
            h.name.setPadding(px(16), 0, px(16), 0);
            h.box.addView(h.name, Ui.centered(-1, -2));

            h.star = star();
            h.box.addView(h.star, Ui.at(px(Ui.TILE_W - 40), px(12), px(26), px(26)));

            h.caption = Ui.text(MainActivity.this, 24, Ui.TEXT_PRIMARY, Ui.regular);
            h.caption.setGravity(Gravity.CENTER_HORIZONTAL);
            h.caption.setVisibility(View.INVISIBLE);
            cell.addView(h.caption, Ui.at(0, px(Ui.TILE_H + 22), px(Ui.TILE_W), px(34)));

            h.root = cell;
            cell.setTag(h);
            return h;
        }

        private Holder row() {
            Holder h = new Holder();
            FrameLayout r = new FrameLayout(MainActivity.this);
            r.setLayoutParams(new AbsListView.LayoutParams(-1, px(ROW_H)));
            h.bg = Ui.rounded(0, Ui.RADIUS);
            r.setBackground(h.bg);

            h.number = Ui.text(MainActivity.this, 28, Ui.TEXT_SECONDARY, Ui.regular);
            h.number.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            r.addView(h.number, Ui.at(0, 0, px(80), px(ROW_H)));

            FrameLayout box = new FrameLayout(MainActivity.this);
            box.setBackground(Ui.glass(14));
            r.addView(box, Ui.at(px(108), px(12), px(128), px(72)));
            h.logoW = px(104);
            h.logoH = px(56);
            h.logo = new ImageView(MainActivity.this);
            h.logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(h.logo, Ui.centered(h.logoW, h.logoH));
            h.name = Ui.text(MainActivity.this, 18, Ui.TEXT_TILE, Ui.regular); // fallback inside the box
            h.name.setGravity(Gravity.CENTER);
            box.addView(h.name, Ui.centered(-1, -2));

            h.title = Ui.text(MainActivity.this, 30, Ui.TEXT_LIST, Ui.regular);
            h.title.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(h.title, Ui.at(px(268), 0, px(700), px(ROW_H)));

            h.star = star();
            r.addView(h.star, Ui.at(px(984), px(35), px(26), px(26)));

            h.now = Ui.text(MainActivity.this, 26, Ui.TEXT_SECONDARY, Ui.regular);
            h.now.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(h.now, Ui.at(px(1040), 0, px(660), px(ROW_H)));

            h.root = r;
            r.setTag(h);
            return h;
        }

        private ImageView star() {
            ImageView s = new ImageView(MainActivity.this);
            s.setImageDrawable(new LineIcon(LineIcon.STAR));
            return s;
        }
    }

    private static final class Holder {
        View root;
        FrameLayout box;
        GradientDrawable bg;
        ImageView logo, star;
        TextView name, caption, number, title, now;
        int logoW, logoH;
        boolean focused;
    }
}
