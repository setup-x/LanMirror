package com.lanmirror.core.api;

import java.util.Arrays;

/**
 * Codec-owner-only bounded stage-latency and submission-cadence statistics, not glass-to-glass.
 */
public final class LatencyStats {
    private final long[] ages;
    private final FrameCadenceStats cadence = new FrameCadenceStats(120);
    private int count, cursor;
    private long windowStartNs, decodedOutputs, renderedOutputs, droppedTotal, recoveryTotal;

    public LatencyStats(long nowNs) {
        this(nowNs, 256);
    }

    public LatencyStats(long nowNs, int capacity) {
        if (capacity < 1 || capacity > 4096)
            throw new IllegalArgumentException("Invalid statistics capacity");
        ages = new long[capacity];
        windowStartNs = nowNs;
    }

    public void recordDecodedOutput() {
        decodedOutputs++;
    }

    public void recordRendered(long ageNs) {
        recordRendered(ageNs, System.nanoTime());
    }

    /**
     * Count an actual successful Surface submission even if the native call crossed the age budget.
     */
    public void recordRendered(long ageNs, long submittedNs) {
        renderedOutputs++;
        cadence.recordSubmission(submittedNs);
        ages[cursor] = Math.max(0L, ageNs);
        cursor = (cursor + 1) % ages.length;
        if (count < ages.length) count++;
    }

    public void recordDrop() {
        droppedTotal++;
    }

    public void recordRecovery() {
        recoveryTotal++;
    }

    public Snapshot snapshotAndReset(long nowNs) {
        long elapsed = Math.max(1L, nowNs - windowStartNs);
        long[] sorted = Arrays.copyOf(ages, count);
        Arrays.sort(sorted);
        Snapshot result = new Snapshot(decodedOutputs * 1_000_000_000.0 / elapsed,
                renderedOutputs * 1_000_000_000.0 / elapsed, count,
                percentile(sorted, 0.50), percentile(sorted, 0.95), droppedTotal, recoveryTotal, cadence.snapshot());
        count = cursor = 0;
        decodedOutputs = renderedOutputs = 0;
        windowStartNs = nowNs;
        // Keep the cadence window across reports: a pause/recovery gap must remain visible.
        return result;
    }

    private static long percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) return -1L;
        return sorted[Math.max(0, (int) Math.ceil(fraction * sorted.length) - 1)];
    }

    public static final class Snapshot {
        public final double decodedFps, renderedFps;
        public final int renderedSamples;
        public final long p50Ns, p95Ns, droppedTotal, recoveryTotal;
        public final FrameCadenceStats.Snapshot cadence;

        private Snapshot(double decodedFps, double renderedFps, int samples, long p50, long p95, long drops, long recoveries, FrameCadenceStats.Snapshot cadence) {
            this.decodedFps = decodedFps;
            this.renderedFps = renderedFps;
            renderedSamples = samples;
            p50Ns = p50;
            p95Ns = p95;
            droppedTotal = drops;
            recoveryTotal = recoveries;
            this.cadence = cadence;
        }
    }
}
