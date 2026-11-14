package com.lanmirror.core.api;

import java.util.Arrays;

/**
 * Bounded codec submission cadence; not a measurement of display-panel refresh.
 */
public final class FrameCadenceStats {
    public static final long LONG_INTERVAL_NS = 33_400_000L;
    private final long[] intervals;
    private int count, cursor;
    private boolean hasPrevious;
    private long previousNs, longIntervalsTotal;

    public FrameCadenceStats() {
        this(120);
    }

    public FrameCadenceStats(int capacity) {
        if (capacity < 1 || capacity > 4096)
            throw new IllegalArgumentException("Invalid cadence window");
        intervals = new long[capacity];
    }

    public void recordSubmission(long nowNs) {
        if (!hasPrevious) {
            hasPrevious = true;
            previousNs = nowNs;
            return;
        }
        // A backwards clock sample does not become a bogus negative interval.
        if (nowNs < previousNs) return;
        long interval = nowNs - previousNs;
        previousNs = nowNs;
        intervals[cursor] = interval;
        cursor = (cursor + 1) % intervals.length;
        if (count < intervals.length) count++;
        if (interval > LONG_INTERVAL_NS) longIntervalsTotal++;
    }

    public Snapshot snapshot() {
        long[] sorted = Arrays.copyOf(intervals, count);
        Arrays.sort(sorted);
        long longWindow = 0;
        for (long interval : sorted) if (interval > LONG_INTERVAL_NS) longWindow++;
        return new Snapshot(count, percentile(sorted, .50), percentile(sorted, .95), longWindow, longIntervalsTotal);
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted.length == 0 ? -1 : sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }

    public static final class Snapshot {
        public final int intervals;
        public final long p50Ns, p95Ns, longIntervalsWindow, longIntervalsTotal;

        private Snapshot(int intervals, long p50Ns, long p95Ns, long longWindow, long longTotal) {
            this.intervals = intervals;
            this.p50Ns = p50Ns;
            this.p95Ns = p95Ns;
            longIntervalsWindow = longWindow;
            longIntervalsTotal = longTotal;
        }
    }
}
