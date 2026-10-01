package com.nirvana.app;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String SITE = "https://nayanrathi1845.github.io/NIRVANA/";

    /** Package, label, counted by default. */
    static final String[][] APPS = {
            {"com.instagram.android", "Instagram", "1"},
            {"com.instagram.lite", "Instagram Lite", "1"},
            {"in.mohalla.video", "Moj", "1"},
            {"com.eterno.shortvideos", "Josh", "1"},
            {"com.google.android.youtube", "YouTube (all of it; Shorts can't be separated)", "0"},
            {"com.facebook.katana", "Facebook", "0"},
            {"com.instagram.barcelona", "Threads", "0"},
            {"com.snapchat.android", "Snapchat", "0"},
    };

    // Palette, matching the NIRVANA page's dark theme.
    static final int BG = Color.parseColor("#0F151B");
    static final int SURFACE = Color.parseColor("#17202A");
    static final int SUNK = Color.parseColor("#121A22");
    static final int INK = Color.parseColor("#E4EAF0");
    static final int MUTED = Color.parseColor("#92A0AD");
    static final int LINE = Color.parseColor("#283440");
    static final int ACCENT = Color.parseColor("#7D9BFF");
    static final int ACCENT_INK = Color.parseColor("#0B1020");
    static final int GOLD = Color.parseColor("#E9B64C");
    static final int GOOD = Color.parseColor("#4DC48B");
    static final int BAD = Color.parseColor("#F07474");

    SharedPreferences prefs;
    UsageReader reader;
    LinearLayout root;
    int dayOffset = 0;                  // 0 = today, -1 = yesterday
    UsageReader.DayStats stats;
    int focus, div;

    TextView scoreTotal, scoreBreakdown;

    Firebase fb;
    Firebase.Profile me;                 // your league profile, once loaded
    boolean profileMissing;              // signed in, but hasn't joined on the website yet
    boolean loadingProfile;
    String profileError;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    LinearLayout boardBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("nirvana", MODE_PRIVATE);
        reader = new UsageReader(this);
        fb = new Firebase(prefs);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(32));
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    // ---------- screen ----------

    void build() {
        root.removeAllViews();

        TextView title = text("NIRVANA", 34, INK, true);
        title.setLetterSpacing(0.04f);
        root.addView(title);
        TextView sub = text("Less scrolling, more living.", 14, MUTED, false);
        root.addView(sub, lp(0, dp(2), 0, dp(16)));

        if (!fb.signedIn()) { root.addView(loginCard()); return; }
        if (me == null) {
            if (profileMissing) { root.addView(joinFirstCard()); return; }
            root.addView(loadingCard());
            loadProfile();
            return;
        }

        root.addView(dayToggle(), lp(0, 0, 0, dp(14)));

        if (!hasUsageAccess()) {
            root.addView(permissionCard());
            root.addView(settingsCard(), lp(0, dp(14), 0, 0));
            return;
        }

        long start = dayStart(dayOffset), end = dayStart(dayOffset + 1);
        stats = reader.read(start, end, trackedApps());
        focus = prefs.getInt("focus_" + ymd(start), 0);
        div = prefs.getInt("div_" + ymd(start), 0);

        root.addView(statsCard());
        root.addView(manualCard(), lp(0, dp(14), 0, 0));
        root.addView(scoreCard(), lp(0, dp(14), 0, 0));
        root.addView(actions(), lp(0, dp(14), 0, 0));
        root.addView(boardCard(), lp(0, dp(14), 0, 0));
        root.addView(settingsCard(), lp(0, dp(22), 0, 0));
        updateScore();
        loadBoard();
    }

    View dayToggle() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(box(SURFACE, LINE, 12));
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] labels = {"Yesterday", "Today"};
        int[] offsets = {-1, 0};
        for (int i = 0; i < 2; i++) {
            final int off = offsets[i];
            TextView b = text(labels[i] + "  ·  " + prettyDate(dayStart(off)), 14, off == dayOffset ? ACCENT_INK : MUTED, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(8), dp(10), dp(8), dp(10));
            if (off == dayOffset) b.setBackground(box(ACCENT, ACCENT, 9));
            b.setOnClickListener(v -> { dayOffset = off; build(); });
            row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    View permissionCard() {
        LinearLayout c = card();
        c.addView(text("Allow usage access", 20, INK, true));
        TextView t = text("NIRVANA reads how long Instagram and other short-form apps were open, straight from your phone's usage log. "
                + "Nothing leaves your phone until you tap Send.\n\nOn the next screen, find NIRVANA and turn on \"Permit usage access\".", 15, MUTED, false);
        c.addView(t, lp(0, dp(8), 0, dp(14)));
        Button b = primaryButton("Open settings");
        b.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        c.addView(b);
        return c;
    }

    View statsCard() {
        LinearLayout c = card();
        c.addView(label("SHORT-FORM TODAY".replace("TODAY", dayOffset == 0 ? "SO FAR TODAY" : "YESTERDAY")));

        LinearLayout big = new LinearLayout(this);
        big.setOrientation(LinearLayout.HORIZONTAL);
        big.addView(metric(String.valueOf(stats.minutes), "minutes", stats.minutes > 60 ? BAD : INK),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        big.addView(metric(String.valueOf(stats.opens), "opens", INK),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(big, lp(0, dp(6), 0, dp(6)));

        if (stats.minutes > 60) c.addView(text("Over the 60-minute cap: −15 today.", 13, BAD, false), lp(0, 0, 0, dp(6)));

        if (!stats.perAppMinutes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : stats.perAppMinutes.entrySet()) {
                if (sb.length() > 0) sb.append("   ");
                sb.append(appLabel(e.getKey())).append(" ").append(e.getValue()).append(" min");
            }
            c.addView(text(sb.toString(), 13, MUTED, false), lp(0, 0, 0, dp(10)));
        }

        c.addView(divider());
        c.addView(windowRow("First hour after waking", stats.morning,
                stats.wakeMs > 0 ? "Woke " + clock(stats.wakeMs) : "No unlock after 4 am yet"), lp(0, dp(10), 0, 0));
        String nightNote;
        if (stats.sleepMs > 0) nightNote = "Slept " + clock(stats.sleepMs);
        else if (dayOffset == 0) nightNote = "Known tomorrow morning. Switch to Yesterday then and save again.";
        else nightNote = "No long screen-off found";
        c.addView(windowRow("Last hour before sleep", stats.night, nightNote), lp(0, dp(10), 0, 0));
        return c;
    }

    View manualCard() {
        LinearLayout c = card();
        c.addView(label("YOUR PART"));
        c.addView(stepper("45-min focus blocks", "10 pts each, max 2", true), lp(0, dp(8), 0, 0));
        c.addView(stepper("Diverse inputs", "Book, long read or podcast", false), lp(0, dp(12), 0, 0));
        c.addView(text("Your baseline: " + me.baseMin + " min · " + me.baseOpens + " opens a day. Change it on the website.", 13, MUTED, false), lp(0, dp(12), 0, 0));
        return c;
    }

    View scoreCard() {
        LinearLayout c = card();
        c.setBackground(box(SURFACE, GOLD, 16));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = label(dayOffset == 0 ? "DAY SCORE SO FAR" : "DAY SCORE");
        top.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        scoreTotal = text("0", 40, GOLD, true);
        top.addView(scoreTotal);
        c.addView(top);
        scoreBreakdown = text("", 13, MUTED, false);
        scoreBreakdown.setTypeface(Typeface.MONOSPACE);
        c.addView(scoreBreakdown, lp(0, dp(6), 0, 0));
        return c;
    }

    View actions() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final Button send = primaryButton("Save to NIRVANA");
        send.setOnClickListener(v -> saveToNirvana(send));
        col.addView(send);
        Button share = secondaryButton("Share to the group");
        share.setOnClickListener(v -> shareSummary());
        col.addView(share, lp(0, dp(10), 0, 0));
        TextView hint = text("Save puts these numbers on the leaderboard straight away. Add your one-line takeaway on the website if you like; saving again here keeps it.", 13, MUTED, false);
        col.addView(hint, lp(0, dp(8), 0, 0));
        return col;
    }

    View settingsCard() {
        LinearLayout c = card();
        c.setBackground(box(SUNK, LINE, 16));
        c.addView(label("APPS THAT COUNT AS SHORT-FORM"));
        Set<String> tracked = trackedApps();
        for (String[] app : APPS) {
            CheckBox cb = new CheckBox(this);
            cb.setText(app[1]);
            cb.setTextColor(INK);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            cb.setChecked(tracked.contains(app[0]));
            final String pkg = app[0];
            cb.setOnCheckedChangeListener((btn, on) -> {
                Set<String> s = new HashSet<>(trackedApps());
                if (on) s.add(pkg); else s.remove(pkg);
                prefs.edit().putStringSet("tracked", s).apply();
                build();
            });
            c.addView(cb, lp(0, dp(4), 0, 0));
        }
        c.addView(text("All three of you should tick the same apps.", 13, MUTED, false), lp(0, dp(6), 0, dp(12)));

        c.addView(label("ACCOUNT"));
        c.addView(text("Signed in as " + fb.email(), 14, INK, false), lp(0, dp(6), 0, dp(8)));
        Button site = secondaryButton("Open the NIRVANA website");
        site.setOnClickListener(v -> openSite());
        c.addView(site);
        Button out = secondaryButton("Sign out");
        out.setOnClickListener(v -> { fb.signOut(); me = null; profileMissing = false; build(); });
        c.addView(out, lp(0, dp(10), 0, 0));
        return c;
    }

    // ---------- pieces ----------

    View metric(String value, String unit, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(value, 44, color, true));
        col.addView(text(unit, 14, MUTED, false));
        return col;
    }

    View windowRow(String name, int state, String note) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(name, 15, INK, true));
        col.addView(text(note, 13, MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String label;
        int color;
        if (state == UsageReader.KEPT) { label = "KEPT +5"; color = GOOD; }
        else if (state == UsageReader.BROKEN) { label = "BROKEN"; color = BAD; }
        else { label = "NOT YET"; color = MUTED; }
        TextView pill = text(label, 12, color, true);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(box(SUNK, color, 20));
        row.addView(pill);
        return row;
    }

    View stepper(String name, String hint, final boolean isFocus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(name, 15, INK, true));
        col.addView(text(hint, 13, MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = text(String.valueOf(isFocus ? focus : div), 22, INK, true);
        value.setGravity(Gravity.CENTER);
        TextView minus = roundButton("−");
        TextView plus = roundButton("+");
        View.OnClickListener change = v -> {
            int d = (v == plus) ? 1 : -1;
            String key = (isFocus ? "focus_" : "div_") + ymd(dayStart(dayOffset));
            if (isFocus) { focus = clamp(focus + d); value.setText(String.valueOf(focus)); prefs.edit().putInt(key, focus).apply(); }
            else { div = clamp(div + d); value.setText(String.valueOf(div)); prefs.edit().putInt(key, div).apply(); }
            updateScore();
        };
        minus.setOnClickListener(change);
        plus.setOnClickListener(change);
        row.addView(minus, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.addView(value, new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return row;
    }

    View numberField(String name, final String key, int hint) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(name, 13, MUTED, false));
        int cur = prefs.getInt(key, 0);
        EditText e = input(cur > 0 ? String.valueOf(cur) : "", InputType.TYPE_CLASS_NUMBER);
        e.setHint(String.valueOf(hint));
        e.addTextChangedListener(new SimpleWatcher(s -> {
            int v = 0;
            try { v = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
            prefs.edit().putInt(key, Math.max(0, v)).apply();
            updateScore();
        }));
        col.addView(e, lp(0, dp(4), 0, 0));
        return col;
    }

    void updateScore() {
        if (stats == null || scoreTotal == null) return;
        int baseMin = me.baseMin, baseOpens = me.baseOpens;
        Score s = Score.of(stats.minutes, stats.opens, stats.morning, stats.night, focus, div, baseMin, baseOpens);
        scoreTotal.setText(String.valueOf(s.total));
        String b = String.format(Locale.US,
                "Time      %4.1f / 35\nOpens     %4.1f / 15\nProtected %4d / 10\nFocus     %4d / 20\nDiverse   %4d / 20%s",
                s.time, s.opens, s.prot, s.focus, s.div, s.pen != 0 ? "\nPenalty    -15" : "");
        scoreBreakdown.setText(b);
    }

    // ---------- actions ----------

    // ---------- league (network) ----------

    View loginCard() {
        LinearLayout c = card();
        c.addView(text("Sign in", 22, INK, true));
        c.addView(text("Use the same email and password as the NIRVANA website. New here? Create your account and join the league on the website first.", 14, MUTED, false), lp(0, dp(6), 0, dp(14)));
        c.addView(text("Email", 13, MUTED, false));
        final EditText email = input(fb.email(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        c.addView(email, lp(0, dp(4), 0, dp(10)));
        c.addView(text("Password", 13, MUTED, false));
        final EditText pw = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(pw, lp(0, dp(4), 0, dp(10)));
        final TextView err = text("", 14, BAD, false);
        c.addView(err, lp(0, 0, 0, dp(8)));
        final Button go = primaryButton("Sign in");
        go.setOnClickListener(v -> {
            final String e = email.getText().toString().trim(), p = pw.getText().toString();
            if (e.isEmpty() || p.isEmpty()) { err.setText("Enter your email and password."); return; }
            go.setEnabled(false);
            go.setText("Signing in…");
            io.execute(() -> {
                try {
                    fb.signIn(e, p);
                    runOnUiThread(() -> { me = null; profileMissing = false; build(); });
                } catch (Firebase.Problem ex) {
                    runOnUiThread(() -> { err.setText(ex.getMessage()); go.setEnabled(true); go.setText("Sign in"); });
                }
            });
        });
        c.addView(go);
        Button site = secondaryButton("Create an account on the website");
        site.setOnClickListener(v -> openSite());
        c.addView(site, lp(0, dp(10), 0, 0));
        return c;
    }

    View loadingCard() {
        LinearLayout c = card();
        c.addView(text(profileError != null ? profileError : "Opening your league…", 16, profileError != null ? BAD : MUTED, false));
        if (profileError != null) {
            Button retry = primaryButton("Try again");
            retry.setOnClickListener(v -> { profileError = null; build(); });
            c.addView(retry, lp(0, dp(12), 0, 0));
        }
        return c;
    }

    View joinFirstCard() {
        LinearLayout c = card();
        c.addView(text("Join the league first", 20, INK, true));
        c.addView(text("You're signed in as " + fb.email() + ", but you haven't joined NIRVANA yet. Open the website, sign in, and enter the invite code, your name and your baseline. Then come back here.", 14, MUTED, false), lp(0, dp(6), 0, dp(14)));
        Button site = primaryButton("Open the website");
        site.setOnClickListener(v -> openSite());
        c.addView(site);
        Button again = secondaryButton("I've joined, check again");
        again.setOnClickListener(v -> { profileMissing = false; build(); });
        c.addView(again, lp(0, dp(10), 0, 0));
        Button out = secondaryButton("Sign out");
        out.setOnClickListener(v -> { fb.signOut(); profileMissing = false; build(); });
        c.addView(out, lp(0, dp(10), 0, 0));
        return c;
    }

    void loadProfile() {
        if (loadingProfile || profileError != null) return;
        loadingProfile = true;
        io.execute(() -> {
            try {
                Firebase.Profile p = fb.myProfile();
                runOnUiThread(() -> { loadingProfile = false; me = p; profileMissing = (p == null); build(); });
            } catch (Firebase.Problem ex) {
                runOnUiThread(() -> {
                    loadingProfile = false;
                    if (ex.status == 401) { fb.signOut(); build(); return; }
                    profileError = ex.getMessage();
                    build();
                });
            }
        });
    }

    void saveToNirvana(final Button btn) {
        final String date = isoDate(dayStart(dayOffset));
        final Map<String, Object> v = new HashMap<>();
        v.put("date", date);
        v.put("min", stats.minutes);
        v.put("opens", stats.opens);
        v.put("shot", true);                       // read straight from the phone, so it counts as proof
        v.put("focus", focus);
        v.put("div", div);
        v.put("source", "app");
        v.put("updatedAt", System.currentTimeMillis());
        if (stats.morning != UsageReader.UNKNOWN) v.put("am", stats.morning == UsageReader.KEPT);
        if (stats.night != UsageReader.UNKNOWN) v.put("pm", stats.night == UsageReader.KEPT);
        btn.setEnabled(false);
        btn.setText("Saving…");
        io.execute(() -> {
            try {
                fb.saveDay(date, v);
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setText("Saved ✓  Save again");
                    Toast.makeText(this, "Saved " + prettyDate(dayStart(dayOffset)) + " to NIRVANA", Toast.LENGTH_SHORT).show();
                    loadBoard();
                });
            } catch (Firebase.Problem ex) {
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setText("Save to NIRVANA");
                    Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
                    if (ex.status == 401) { fb.signOut(); me = null; build(); }
                });
            }
        });
    }

    View boardCard() {
        LinearLayout c = card();
        c.addView(label(dayOffset == 0 ? "TODAY'S STANDINGS" : "YESTERDAY'S STANDINGS"));
        boardBox = new LinearLayout(this);
        boardBox.setOrientation(LinearLayout.VERTICAL);
        boardBox.addView(text("Loading…", 14, MUTED, false));
        c.addView(boardBox, lp(0, dp(8), 0, 0));
        return c;
    }

    static class Row { String name; int score; int min; boolean logged; boolean mine; }

    void loadBoard() {
        final LinearLayout box = boardBox;
        if (box == null) return;
        final String date = isoDate(dayStart(dayOffset));
        final String myUid = fb.uid();
        io.execute(() -> {
            try {
                Map<String, Firebase.Profile> members = fb.members();
                final List<Row> rows = new ArrayList<>();
                for (Firebase.Profile p : members.values()) {
                    Row r = new Row();
                    r.name = p.name;
                    r.mine = p.uid.equals(myUid);
                    Map<String, Object> d = fb.day(p.uid, date);
                    if (d != null) {
                        r.logged = true;
                        r.min = Firebase.asInt(d.get("min"));
                        r.score = Score.ofDay(r.min, Firebase.asInt(d.get("opens")), Firebase.asBool(d.get("shot")),
                                Firebase.asBool(d.get("am")), Firebase.asBool(d.get("pm")),
                                Firebase.asInt(d.get("focus")), Firebase.asInt(d.get("div")), p.baseMin, p.baseOpens).total;
                    }
                    rows.add(r);
                }
                rows.sort((a, b) -> {
                    if (a.logged != b.logged) return a.logged ? -1 : 1;
                    if (a.score != b.score) return b.score - a.score;
                    return a.min - b.min;
                });
                runOnUiThread(() -> showBoard(box, rows));
            } catch (Firebase.Problem ex) {
                runOnUiThread(() -> { box.removeAllViews(); box.addView(text(ex.getMessage(), 14, BAD, false)); });
            }
        });
    }

    void showBoard(LinearLayout box, List<Row> rows) {
        box.removeAllViews();
        int pos = 0;
        for (Row r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            boolean win = r.logged && pos == 0;
            String place = r.logged ? String.valueOf(++pos) : "–";
            row.addView(text(place, 18, win ? GOLD : MUTED, true), new LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT));
            String who = r.name + (r.mine ? " (you)" : "");
            TextView name = text(who + (r.logged ? "  ·  " + r.min + " min" : "  ·  not logged"), 15, INK, r.mine);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text(r.logged ? String.valueOf(r.score) : "", 20, win ? GOLD : INK, true));
            box.addView(row);
        }
        if (rows.isEmpty()) box.addView(text("No members yet.", 14, MUTED, false));
    }

    void openSite() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SITE)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No browser found. Open " + SITE, Toast.LENGTH_LONG).show();
        }
    }

    static String isoDate(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(ms); }

    void shareSummary() {
        long start = dayStart(dayOffset);
        int baseMin = me.baseMin, baseOpens = me.baseOpens;
        Score s = Score.of(stats.minutes, stats.opens, stats.morning, stats.night, focus, div, baseMin, baseOpens);
        StringBuilder sb = new StringBuilder();
        sb.append("NIRVANA · ").append(prettyDate(start)).append("\n");
        sb.append("Short-form: ").append(stats.minutes).append(" min, ").append(stats.opens).append(" opens\n");
        for (Map.Entry<String, Integer> e : stats.perAppMinutes.entrySet()) {
            sb.append("  ").append(appLabel(e.getKey())).append(": ").append(e.getValue()).append(" min\n");
        }
        sb.append("Morning hour: ").append(stateWord(stats.morning)).append(" · Night hour: ").append(stateWord(stats.night)).append("\n");
        sb.append("Focus blocks: ").append(focus).append(" · Diverse inputs: ").append(div).append("\n");
        sb.append("Day score: ").append(s.total).append(dayOffset == 0 ? " (so far)" : "").append("\n");
        sb.append("Read automatically by the NIRVANA app");
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Share to the group"));
    }

    // ---------- data helpers ----------

    boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        @SuppressWarnings("deprecation")
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    Set<String> trackedApps() {
        Set<String> s = prefs.getStringSet("tracked", null);
        if (s != null) return s;
        Set<String> d = new HashSet<>();
        for (String[] app : APPS) if ("1".equals(app[2])) d.add(app[0]);
        return d;
    }

    static String appLabel(String pkg) {
        for (String[] app : APPS) if (app[0].equals(pkg)) {
            String l = app[1];
            int p = l.indexOf(" (");
            return p > 0 ? l.substring(0, p) : l;
        }
        return pkg;
    }

    static String stateWord(int s) {
        return s == UsageReader.KEPT ? "kept" : s == UsageReader.BROKEN ? "broken" : "not known yet";
    }

    static long dayStart(int offset) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, offset);
        return c.getTimeInMillis();
    }

    static String ymd(long ms) { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(ms); }
    static String prettyDate(long ms) { return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(ms); }
    static String clock(long ms) { return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(ms); }
    static int clamp(int v) { return Math.max(0, Math.min(6, v)); }

    // ---------- view helpers ----------

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(SURFACE, LINE, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        return c;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    TextView label(String s) {
        TextView t = text(s, 12, MUTED, true);
        t.setLetterSpacing(0.1f);
        return t;
    }

    View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    Button primaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(ACCENT_INK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(box(ACCENT, ACCENT, 12));
        b.setPadding(dp(12), dp(14), dp(12), dp(14));
        b.setStateListAnimator(null);
        return b;
    }

    Button secondaryButton(String s) {
        Button b = primaryButton(s);
        b.setTextColor(INK);
        b.setBackground(box(SURFACE, LINE, 12));
        return b;
    }

    TextView roundButton(String s) {
        TextView t = text(s, 20, INK, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(box(SUNK, LINE, 20));
        return t;
    }

    EditText input(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setBackground(box(SUNK, LINE, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    interface OnText { void changed(String s); }

    static class SimpleWatcher implements TextWatcher {
        final OnText cb;
        SimpleWatcher(OnText cb) { this.cb = cb; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { cb.changed(s.toString()); }
    }
}
