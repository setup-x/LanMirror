package com.lanmirror.app;

/** JVM-only cadence/recovery tests. Does not measure Android hardware or panel refresh. */
public final class ReceiverCadenceRegressionTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void recovery() {
        DropRecovery policy = new DropRecovery();
        long initialEpoch = policy.recoveryEpoch();
        check(policy.recoveryCount() == 0 && policy.isWaitingForKey(), "Startup is not a counted recovery");
        for (int i = 0; i < 100; i++) policy.requireKey();
        check(policy.recoveryEpoch() == initialEpoch && policy.recoveryCount() == 0, "Repeated initial begin is idempotent");
        check(policy.shouldRequestSync(0), "Initial request is immediate");
        check(!policy.shouldRequestSync(100_000_000L), "100ms feedback now suppressed");
        policy.requireKey();
        check(!policy.shouldRequestSync(299_999_999L), "Repeated begin cannot reset rate limit");
        check(policy.shouldRequestSync(300_000_000L), "300ms feedback allowed");
        check(policy.classify(true, 10_000_000L) == DropRecovery.Decision.RECOVER_AT_KEY, "Fresh IDR recovers");
        check(!policy.shouldRequestSync(1_000_000_000L), "No recovery feedback during normal feed");
        check(!policy.shouldRenderOutput(true, 100_000_000L), "Old decoded picture skipped");
        check(!policy.isWaitingForKey(), "Skipping decoded output does not poison GOP");
        check(policy.shouldRenderOutput(true, 20_000_000L), "Next timely output stays visible");
        check(!policy.shouldRenderOutput(false, 0), "Unmatched output is skipped safely");
        check(!policy.isWaitingForKey(), "Missing timestamp cannot poison reference frames");
        check(policy.classify(false, 100_000_000L) == DropRecovery.Decision.DROP, "Old encoded P does poison GOP");
        long epoch = policy.recoveryEpoch();
        check(epoch == initialEpoch + 1 && policy.recoveryCount() == 1, "Single recovery transition counted");
        for (int i = 0; i < 100; i++) { policy.requireKey(); policy.classify(false, 500_000_000L); }
        check(policy.recoveryEpoch() == epoch && policy.recoveryCount() == 1, "More stale P cannot restart recovery/flush epoch");
        check(policy.classify(false, 0) == DropRecovery.Decision.DROP, "Later fresh P cannot decode after encoded loss");
        check(policy.classify(true, 200_000_000L) == DropRecovery.Decision.DROP, "Stale IDR cannot end recovery");
        check(policy.classify(true, 0) == DropRecovery.Decision.RECOVER_AT_KEY, "New timely IDR ends recovery");
        check(policy.classify(false, 0) == DropRecovery.Decision.FEED, "P resumes after safe IDR");
        policy.requireKey();
        check(policy.recoveryCount() == 2 && policy.recoveryEpoch() == epoch + 1, "Another genuine recovery gets a new epoch");
    }
    private static void cadence() {
        FrameCadenceStats stable = new FrameCadenceStats();
        check(stable.snapshot().intervals == 0 && stable.snapshot().p50Ns == -1, "No invented intervals before rendering");
        stable.recordSubmission(0);
        check(stable.snapshot().intervals == 0, "First frame is not an interval");
        long now = 0;
        for (int i = 0; i < 150; i++) { now += 16_700_000L; stable.recordSubmission(now); }
        FrameCadenceStats.Snapshot snapshot = stable.snapshot();
        check(snapshot.intervals == 120, "Latest 120 intervals bounded");
        check(snapshot.p50Ns == 16_700_000L && snapshot.p95Ns == 16_700_000L, "Stable 16.7ms cadence");
        check(snapshot.longIntervalsWindow == 0 && snapshot.longIntervalsTotal == 0, "No long intervals for stable 60-ish submission");
        stable.recordSubmission(now - 1);
        check(stable.snapshot().intervals == 120 && stable.snapshot().p95Ns == 16_700_000L, "Backwards timestamp is not a negative interval");
        FrameCadenceStats burst = new FrameCadenceStats(); burst.recordSubmission(0); now = 0;
        for (int i = 0; i < 120; i++) { now += i % 10 == 0 ? 100_000_000L : 1_000_000L; burst.recordSubmission(now); }
        snapshot = burst.snapshot();
        check(snapshot.p50Ns == 1_000_000L && snapshot.p95Ns == 100_000_000L, "p95 exposes pauses hidden by short burst intervals");
        check(snapshot.longIntervalsWindow == 12 && snapshot.longIntervalsTotal == 12, "Long-gap count shows burst/recovery pauses");
        for (int i = 0; i < 120; i++) { now += 16_700_000L; burst.recordSubmission(now); }
        snapshot = burst.snapshot();
        check(snapshot.longIntervalsWindow == 0 && snapshot.longIntervalsTotal == 12, "Bounded recent gaps evict but lifetime count persists");
        FrameCadenceStats boundary = new FrameCadenceStats(3); boundary.recordSubmission(0);
        boundary.recordSubmission(33_400_000L); boundary.recordSubmission(66_800_001L);
        check(boundary.snapshot().longIntervalsWindow == 1, "Threshold is strictly greater than 33.4ms");
        try { new FrameCadenceStats(0); throw new AssertionError("Zero capacity accepted"); } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void trueSubmissionAccounting() {
        LatencyStats stats = new LatencyStats(0, 2);
        for (int i = 0; i < 4; i++) {
            stats.recordDecodedOutput(); stats.recordRendered(i == 3 ? 150_000_000L : 20_000_000L, i * 16_700_000L);
        }
        LatencyStats.Snapshot snapshot = stats.snapshotAndReset(1_000_000_000L);
        check(snapshot.decodedFps == 4 && snapshot.renderedFps == 4, "True fps not clipped by latency sample capacity");
        check(snapshot.renderedSamples == 2 && snapshot.p95Ns == 150_000_000L, "Native submission that crossed budget is recorded, not disguised as a drop");
        check(snapshot.droppedTotal == 0 && snapshot.recoveryTotal == 0, "Submitted slow frame does not increment drops or recovery");
        check(snapshot.cadence.intervals == 3 && snapshot.cadence.p95Ns == 16_700_000L, "Cadence tracks all actual submissions");
        stats.recordDrop(); stats.recordRecovery(); stats.recordDecodedOutput();
        snapshot = stats.snapshotAndReset(2_000_000_000L);
        check(snapshot.decodedFps == 1 && snapshot.renderedFps == 0, "Decoded but hidden output is not submitted fps");
        check(snapshot.cadence.intervals == 3, "Cadence survives one-second report reset");
        check(snapshot.recoveryTotal == 1 && snapshot.droppedTotal == 1, "Cumulative recovery/drop counts retained");
        stats.recordRendered(20_000_000L, 2_100_000_000L);
        snapshot = stats.snapshotAndReset(3_000_000_000L);
        check(snapshot.cadence.longIntervalsTotal == 1, "Pause across report windows stays visible");
    }
    private static void fifoAfterHiddenOutput() {
        FrameTimestampTracker tracker = new FrameTimestampTracker(4);
        tracker.add(123, 1_000_000L); tracker.add(123, 100_000_000L);
        DropRecovery policy = new DropRecovery(); policy.classify(true, 0);
        Long first = tracker.remove(123);
        check(first == 1_000_000L && !policy.shouldRenderOutput(first != null, 199_000_000L), "First repeated-PTS output skipped with its original old timestamp");
        Long second = tracker.remove(123);
        check(second == 100_000_000L && policy.shouldRenderOutput(second != null, 20_000_000L), "Next repeated-PTS output still timely and rendered");
        check(!policy.isWaitingForKey() && tracker.size() == 0, "FIFO output-only drop needs neither flush nor new IDR");
    }
    public static void main(String[] args) {
        recovery(); cadence(); trueSubmissionAccounting(); fifoAfterHiddenOutput();
        System.out.println("PASS: " + checks + " receiver cadence/recovery assertions (300ms idempotence, output-only skips, true submit fps, 120-interval p95, pauses, FIFO)");
    }
}
