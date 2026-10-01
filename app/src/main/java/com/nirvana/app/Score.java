package com.nirvana.app;

/** The NIRVANA daily score, identical to the web page's rules. */
public final class Score {
    public double time, opens;
    public int prot, focus, div, pen, total;

    static double lin(double v, double best, double base, double max) {
        if (v <= best) return max;
        if (base <= best || v >= base) return 0;
        return max * (base - v) / (base - best);
    }

    /** am and pm use UsageReader.KEPT / BROKEN / UNKNOWN; only KEPT earns points. */
    public static Score of(int minutes, int opens, int am, int pm, int focus, int div, int baseMin, int baseOpens) {
        Score s = new Score();
        s.time = lin(minutes, 15, baseMin, 35);
        s.opens = lin(opens, 5, baseOpens, 15);
        s.prot = (am == UsageReader.KEPT ? 5 : 0) + (pm == UsageReader.KEPT ? 5 : 0);
        s.focus = 10 * Math.min(2, focus);
        s.div = 10 * Math.min(2, div);
        s.pen = minutes > 60 ? -15 : 0;
        s.total = (int) Math.max(0L, Math.round(s.time + s.opens + s.prot + s.focus + s.div + s.pen));
        return s;
    }

    /** Scores a day as saved in the league, where "shot" means proof was given; without it time and opens score 0. */
    public static Score ofDay(int minutes, int opens, boolean shot, boolean am, boolean pm, int focus, int div, int baseMin, int baseOpens) {
        Score s = of(minutes, opens, am ? UsageReader.KEPT : UsageReader.BROKEN, pm ? UsageReader.KEPT : UsageReader.BROKEN, focus, div, baseMin, baseOpens);
        if (!shot) {
            s.time = 0;
            s.opens = 0;
            s.total = (int) Math.max(0L, Math.round(s.prot + s.focus + s.div + s.pen));
        }
        return s;
    }
}
