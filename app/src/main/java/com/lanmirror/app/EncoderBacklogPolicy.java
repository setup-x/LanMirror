package com.lanmirror.app;

/** Encoder-owner-only GOP recovery. Never drop a P frame then decode later dependent frames. */
public final class EncoderBacklogPolicy {
    private long minimumAgeNs = Long.MAX_VALUE;
    private long lastAgeNs = -1;
    private boolean waiting;
    private long requestNs;
    private int slowPFrames;
    public void requestRecovery(long nowNs) { if (!waiting) { waiting = true; requestNs = nowNs; } }
    public void onWriteFinished(int flags, long durationNs, long nowNs) {
        if ((flags & 2) != 0) return;
        if ((flags & 1) != 0) {
            slowPFrames = 0;
            // A large, rare IDR can briefly block a healthy average-bandwidth link.
            // Its induced queued-frame age must not immediately trigger another IDR.
            if (durationNs > 35_000_000L && lastAgeNs >= 0) minimumAgeNs = lastAgeNs + durationNs;
            return;
        }
        slowPFrames = durationNs > 35_000_000L ? slowPFrames + 1 : 0;
        if (slowPFrames >= 2) { requestRecovery(nowNs); slowPFrames = 0; }
    }
    public boolean isWaiting() { return waiting; }
    public long lastAgeNs() { return lastAgeNs; }
    public boolean shouldDiscard(long ptsUs, int flags, long nowNs) {
        if ((flags & 2) != 0) return false; // Codec-config/SPS/PPS must survive recovery.
        boolean key = (flags & 1) != 0;
        long captureNs = ptsUs > 0 && ptsUs < Long.MAX_VALUE / 1000 ? ptsUs * 1000 : 0;
        long age = nowNs - captureNs;
        // Surface timestamps normally use CLOCK_MONOTONIC, but do not assume it on every ROM.
        boolean plausible = captureNs > 0 && age >= 0 && age < 5_000_000_000L;
        lastAgeNs = plausible ? age : -1;
        if (plausible) {
            minimumAgeNs = Math.min(minimumAgeNs, age);
            if (age - minimumAgeNs > 45_000_000L && !waiting) requestRecovery(nowNs);
        }
        if (!waiting) return false;
        // Leave recovery only on a fresh IDR, never an older queued IDR.
        long baseline = minimumAgeNs == Long.MAX_VALUE ? 0 : minimumAgeNs;
        if (key && (!plausible || captureNs + baseline >= requestNs - 17_000_000L)) {
            waiting = false; minimumAgeNs = plausible ? age : Long.MAX_VALUE; return false;
        }
        return true;
    }
}
