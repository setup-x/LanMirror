package com.lanmirror.app;

/** Receiver-only GOP policy, independent of Android APIs for deterministic tests. */
public final class DropRecovery {
    public static final long BACKLOG_BUDGET_NS = 90_000_000L;
    public static final long MIN_SYNC_REQUEST_NS = 300_000_000L;
    public enum Decision { FEED, DROP, RECOVER_AT_KEY }
    private long budgetNs = BACKLOG_BUDGET_NS;
    private boolean waitingForKey = true;
    private boolean requested;
    private long lastRequestNs;
    private long recoveryEpoch = 1, recoveryCount;
    public DropRecovery() { }
    public DropRecovery(long budgetNs) { setBudgetNs(budgetNs); }
    public long budgetNs() { return budgetNs; }
    public void setBudgetNs(long value) { budgetNs = Math.max(BACKLOG_BUDGET_NS, Math.min(2_000_000_000L, value)); }
    public boolean isWaitingForKey() { return waitingForKey; }
    public long recoveryEpoch() { return recoveryEpoch; }
    /** Number of genuine post-start GOP recovery transitions, not repeated feedback packets. */
    public long recoveryCount() { return recoveryCount; }
    public void requireKey() { beginRecovery(); }
    /** Repeated losses while waiting cannot move the epoch or restart a decoder flush. */
    public boolean beginRecovery() {
        if (waitingForKey) return false;
        waitingForKey = true; recoveryEpoch++; recoveryCount++; return true;
    }
    /** Dropping encoded input poisons its GOP: do not feed any dependent P frame until a fresh key. */
    public Decision classify(boolean keyFrame, long ageNs) {
        if (ageNs > budgetNs) { beginRecovery(); return Decision.DROP; }
        if (waitingForKey) {
            if (!keyFrame) return Decision.DROP;
            waitingForKey = false; return Decision.RECOVER_AT_KEY;
        }
        return Decision.FEED;
    }
    /** Dropping already decoded display output does NOT poison the decoder's reference frames. */
    public boolean shouldRenderOutput(boolean hasTimestamp, long ageNs) {
        return hasTimestamp && !waitingForKey && ageNs <= budgetNs;
    }
    public boolean shouldRequestSync(long nowNs) {
        if (!waitingForKey) return false;
        if (!requested || nowNs - lastRequestNs >= MIN_SYNC_REQUEST_NS) {
            requested = true; lastRequestNs = nowNs; return true;
        }
        return false;
    }
}
