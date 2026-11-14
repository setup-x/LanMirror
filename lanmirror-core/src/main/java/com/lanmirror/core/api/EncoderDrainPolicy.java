package com.lanmirror.core.api;

/**
 * Bound nonblocking codec draining so recovery/stop/resize checks are never intentionally starved.
 */
public final class EncoderDrainPolicy {
    public static final int MAX_OUTPUTS = 4;
    public static final long MAX_BURST_NS = 2_000_000L;

    private EncoderDrainPolicy() {
    }

    public static boolean continueBurst(boolean enabled, int outputsProcessed, long startedNs, long nowNs) {
        if (!enabled || outputsProcessed < 1 || outputsProcessed >= MAX_OUTPUTS) return false;
        long elapsed = nowNs - startedNs;
        return elapsed >= 0 && elapsed < MAX_BURST_NS;
    }
}
