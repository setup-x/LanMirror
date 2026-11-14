package com.lanmirror.core.api;

/**
 * Immutable receive-payload sample; UDP bytes include valid duplicates/retransmissions, not headers.
 * Missing fragments count only timed-out incomplete assemblies, never dependency/capacity discards.
 */
public final class TransportMetrics {
    public enum Mode {UNKNOWN, TCP, UDP}

    public final Mode mode;
    public final boolean sampled;
    public final double receiveMbps;
    public final long intervalNs, missingFragments, expiredFrames;

    private TransportMetrics(Mode mode, boolean sampled, double receiveMbps, long intervalNs, long missingFragments, long expiredFrames) {
        this.mode = mode;
        this.sampled = sampled;
        this.receiveMbps = receiveMbps;
        this.intervalNs = intervalNs;
        this.missingFragments = missingFragments;
        this.expiredFrames = expiredFrames;
    }

    public static TransportMetrics unknown(Mode mode) {
        return new TransportMetrics(mode == null ? Mode.UNKNOWN : mode, false, Double.NaN, 0, 0, 0);
    }

    /**
     * One sampler per session, fed cumulative payload/timeout counters and actual nanoTime.
     */
    public static final class Sampler {
        private boolean initialized;
        private Mode previousMode;
        private long previousNs, previousBytes, previousMissing, previousExpired;

        public synchronized TransportMetrics sample(Mode mode, long nowNs, long receivedBytes, long missingFragments, long expiredFrames) {
            Mode actual = mode == null ? Mode.UNKNOWN : mode;
            long interval = nowNs - previousNs;
            boolean valid = initialized && actual != Mode.UNKNOWN && actual == previousMode && nowNs > previousNs && interval > 0
                    && receivedBytes >= previousBytes && missingFragments >= previousMissing && expiredFrames >= previousExpired
                    && previousBytes >= 0 && previousMissing >= 0 && previousExpired >= 0
                    && receivedBytes >= 0 && missingFragments >= 0 && expiredFrames >= 0;
            TransportMetrics result = valid ? new TransportMetrics(actual, true, (receivedBytes - previousBytes) * (8_000.0 / interval), interval,
                    missingFragments - previousMissing, expiredFrames - previousExpired) : unknown(actual);
            initialized = true;
            previousMode = actual;
            previousNs = nowNs;
            previousBytes = receivedBytes;
            previousMissing = missingFragments;
            previousExpired = expiredFrames;
            return result;
        }
    }
}
