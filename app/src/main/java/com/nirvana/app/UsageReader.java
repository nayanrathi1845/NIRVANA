package com.nirvana.app;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the phone's own usage log (the same data behind Digital Wellbeing) and turns it
 * into the numbers NIRVANA scores: short-form minutes, app opens, and the two protected hours.
 */
public class UsageReader {

    // Event type values. Written as numbers so the app runs on Android 9, where some names don't exist yet.
    static final int RESUMED = 1;          // app came to the foreground
    static final int PAUSED = 2;           // app left the foreground
    static final int SCREEN_ON = 15;
    static final int SCREEN_OFF = 16;
    static final int UNLOCKED = 18;

    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    /** Reopening the same app within this gap counts as the same open. */
    static final long MERGE_GAP = 30_000L;

    public static final int BROKEN = 0, KEPT = 1, UNKNOWN = 2;

    public static class DayStats {
        public int minutes;
        public int opens;
        public int morning = UNKNOWN;
        public int night = UNKNOWN;
        public long wakeMs = -1;
        public long sleepMs = -1;
        public final Map<String, Integer> perAppMinutes = new LinkedHashMap<>();
    }

    static class Span {
        final String pkg;
        long start, end;
        Span(String pkg, long start, long end) { this.pkg = pkg; this.start = start; this.end = end; }
    }

    private final UsageStatsManager usm;

    public UsageReader(Context c) {
        usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    /**
     * @param dayStart local midnight that starts the day
     * @param dayEnd   local midnight that ends it
     * @param tracked  package names in the short-form bucket
     */
    public DayStats read(long dayStart, long dayEnd, Set<String> tracked) {
        long now = System.currentTimeMillis();
        long scanFrom = dayStart - HOUR;
        long scanTo = Math.min(now, dayEnd + 12 * HOUR);   // look into the next morning to find bedtime

        List<Span> raw = new ArrayList<>();
        List<long[]> screen = new ArrayList<>();           // {timestamp, 0=off 1=unlock 2=on}
        scan(scanFrom, scanTo, now, tracked, raw, screen);

        List<Span> sessions = mergePerApp(raw);
        DayStats d = new DayStats();
        long countEnd = Math.min(dayEnd, now);

        // Minutes and per-app minutes, clipped to the day.
        Map<String, Long> perApp = new HashMap<>();
        List<long[]> clipped = new ArrayList<>();
        for (Span s : sessions) {
            long a = Math.max(s.start, dayStart), b = Math.min(s.end, countEnd);
            if (b <= a) continue;
            clipped.add(new long[]{a, b});
            Long prev = perApp.get(s.pkg);
            perApp.put(s.pkg, (prev == null ? 0L : prev) + (b - a));
        }
        d.minutes = (int) Math.round(unionLength(clipped) / (double) MIN);
        List<Map.Entry<String, Long>> entries = new ArrayList<>(perApp.entrySet());
        Collections.sort(entries, (x, y) -> Long.compare(y.getValue(), x.getValue()));
        for (Map.Entry<String, Long> e : entries) {
            int m = (int) Math.round(e.getValue() / (double) MIN);
            if (m > 0) d.perAppMinutes.put(e.getKey(), m);
        }

        // Opens: sessions that started during the day.
        for (Span s : sessions) if (s.start >= dayStart && s.start < dayEnd) d.opens++;

        // Morning hour: the first unlock after 4 am is taken as waking up.
        long wake = -1;
        for (long[] ev : screen) {
            if (ev[1] == 1 && ev[0] >= dayStart + 4 * HOUR && ev[0] < dayEnd) { wake = ev[0]; break; }
        }
        if (wake < 0) {
            for (long[] ev : screen) {
                if (ev[1] == 2 && ev[0] >= dayStart + 4 * HOUR && ev[0] < dayEnd) { wake = ev[0]; break; }
            }
        }
        if (wake >= 0) {
            d.wakeMs = wake;
            boolean used = overlaps(sessions, wake, wake + HOUR);
            if (used) d.morning = BROKEN;
            else d.morning = (now >= wake + HOUR) ? KEPT : UNKNOWN;
        }

        // Night hour: bedtime is the start of the longest screen-off stretch (3 h or more)
        // that begins between 6 pm and 10 am the next morning.
        // A notification can light the screen without anyone picking the phone up, so the
        // stretch ends at the next unlock (or the next screen-on, on phones with no lock).
        boolean hasUnlocks = false;
        for (long[] ev : screen) if (ev[1] == 1) { hasUnlocks = true; break; }
        long bestOff = -1, bestGap = 0;
        for (int i = 0; i < screen.size(); i++) {
            long[] ev = screen.get(i);
            if (ev[1] != 0) continue;
            if (ev[0] < dayStart + 18 * HOUR || ev[0] > dayEnd + 10 * HOUR) continue;
            long next = -1;
            for (int j = i + 1; j < screen.size(); j++) {
                long kind = screen.get(j)[1];
                if (hasUnlocks ? kind == 1 : kind != 0) { next = screen.get(j)[0]; break; }
            }
            if (next < 0) continue;                 // still asleep, or the night isn't over yet
            long gap = next - ev[0];
            if (gap >= 3 * HOUR && gap > bestGap) { bestGap = gap; bestOff = ev[0]; }
        }
        if (bestOff >= 0) {
            d.sleepMs = bestOff;
            d.night = overlaps(sessions, bestOff - HOUR, bestOff) ? BROKEN : KEPT;
        }
        return d;
    }

    private void scan(long from, long to, long now, Set<String> tracked, List<Span> out, List<long[]> screen) {
        UsageEvents events = usm.queryEvents(from, to);
        if (events == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        // Tracked per activity, because one app can hand over between its own screens.
        Map<String, Long> open = new HashMap<>();
        Map<String, String> openPkg = new HashMap<>();
        Set<String> seen = new HashSet<>();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            long ts = e.getTimeStamp();
            if (type == SCREEN_OFF) {
                for (Map.Entry<String, Long> o : open.entrySet()) {
                    out.add(new Span(openPkg.get(o.getKey()), o.getValue(), ts));
                }
                open.clear();
                screen.add(new long[]{ts, 0});
                continue;
            }
            if (type == UNLOCKED) { screen.add(new long[]{ts, 1}); continue; }
            if (type == SCREEN_ON) { screen.add(new long[]{ts, 2}); continue; }

            String pkg = e.getPackageName();
            if (pkg == null || !tracked.contains(pkg)) continue;
            String key = pkg + "/" + e.getClassName();
            if (type == RESUMED) {
                seen.add(key);
                if (!open.containsKey(key)) { open.put(key, ts); openPkg.put(key, pkg); }
            } else if (type == PAUSED) {
                Long start = open.remove(key);
                if (start != null) out.add(new Span(pkg, start, ts));
                else if (!seen.contains(key)) out.add(new Span(pkg, from, ts));  // was already open when the log window began
                seen.add(key);
            }
        }
        for (Map.Entry<String, Long> o : open.entrySet()) {
            out.add(new Span(openPkg.get(o.getKey()), o.getValue(), Math.min(to, now)));
        }
    }

    /** Joins each app's back-to-back spans into single sessions. */
    private static List<Span> mergePerApp(List<Span> raw) {
        Map<String, List<Span>> byApp = new HashMap<>();
        for (Span s : raw) {
            if (s.end <= s.start) continue;
            List<Span> l = byApp.get(s.pkg);
            if (l == null) { l = new ArrayList<>(); byApp.put(s.pkg, l); }
            l.add(s);
        }
        List<Span> merged = new ArrayList<>();
        for (List<Span> l : byApp.values()) {
            Collections.sort(l, (x, y) -> Long.compare(x.start, y.start));
            Span cur = null;
            for (Span s : l) {
                if (cur != null && s.start - cur.end <= MERGE_GAP) {
                    cur.end = Math.max(cur.end, s.end);
                } else {
                    if (cur != null) merged.add(cur);
                    cur = new Span(s.pkg, s.start, s.end);
                }
            }
            if (cur != null) merged.add(cur);
        }
        Collections.sort(merged, (x, y) -> Long.compare(x.start, y.start));
        return merged;
    }

    private static long unionLength(List<long[]> spans) {
        Collections.sort(spans, (x, y) -> Long.compare(x[0], y[0]));
        long total = 0, curA = -1, curB = -1;
        for (long[] s : spans) {
            if (curB < 0 || s[0] > curB) {
                if (curB >= 0) total += curB - curA;
                curA = s[0];
                curB = s[1];
            } else {
                curB = Math.max(curB, s[1]);
            }
        }
        if (curB >= 0) total += curB - curA;
        return total;
    }

    private static boolean overlaps(List<Span> sessions, long a, long b) {
        for (Span s : sessions) if (s.start < b && s.end > a) return true;
        return false;
    }
}
