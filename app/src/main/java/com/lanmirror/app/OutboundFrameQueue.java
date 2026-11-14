package com.lanmirror.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Bounded, GOP-aware handoff from an encoder owner to one network writer.
 * Never waits for queue capacity. It may briefly contend on this object's monitor.
 *
 * offer() defensively copies accepted video data. offerOwned() transfers exclusive
 * ownership of a fresh byte[]: the caller MUST NOT subsequently change or reuse it.
 * CSD is always copied. Frame fields are final; the public arrays must be treated
 * as read-only by the writer. CSD snapshots never alias the cache or other frames.
 *
 * A recovery bootstrap remains protected from repeated requestRecovery() calls
 * while queued and after poll(), until the writer calls markSent(theSameFrame).
 * Overflow or new CSD is a new hard recovery event, not a repeated control request.
 * An already-polled bootstrap cannot be unsent, and is not invalidated by overflow.
 */
public final class OutboundFrameQueue {
    public static final int MAX_CODEC_CONFIG_ITEMS = 8;
    public static final int MAX_CODEC_CONFIG_BYTES = 512 * 1024;
    private static final int KEY_FRAME = 1;
    private static final int CODEC_CONFIG = 2;

    public static final class Frame {
        public final long generation;
        public final long ptsUs;
        /** Encoder output observation time, NOT time of poll()/network write. */
        public final long encodedAtNs;
        public final int width, height, flags;
        public final byte[] data;
        /** Non-null, deep-copied CSD snapshot exactly when startsGop is true. */
        public final byte[][] codecConfig;
        public final boolean startsGop;

        private Frame(long generation, long ptsUs, long encodedAtNs,
                      int width, int height, int flags, byte[] data,
                      byte[][] codecConfig, boolean startsGop) {
            this.generation = generation;
            this.ptsUs = ptsUs;
            this.encodedAtNs = encodedAtNs;
            this.width = width;
            this.height = height;
            this.flags = flags;
            this.data = data;
            this.codecConfig = codecConfig;
            this.startsGop = startsGop;
        }
    }

    private final int maxFrames, maxBytes;
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final ArrayList<byte[]> codecConfig = new ArrayList<>();
    private final Set<Frame> inFlightRecovery = Collections.newSetFromMap(
            new IdentityHashMap<Frame, Boolean>());
    private int queuedBytes, codecConfigBytes;
    private int width, height;
    private long generation, droppedFrames, recoveryCount;
    private boolean closed, awaitingKey = true, keyframeNeeded;

    public OutboundFrameQueue(int maxFrames, int maxBytes) {
        if (maxFrames <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("Positive frame and byte bounds required");
        }
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    /** Must be called before the first offer. A closed queue can never be revived. */
    public synchronized void beginGeneration(int width, int height) {
        if (closed) return;
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid video size");
        clearPending();
        codecConfig.clear();
        codecConfigBytes = 0;
        inFlightRecovery.clear();
        generation++;
        this.width = width;
        this.height = height;
        awaitingKey = true;
        keyframeNeeded = true;
        recoveryCount++;
        notifyAll();
    }

    public boolean offer(long ptsUs, int flags, byte[] data) {
        return offer(ptsUs, flags, data, System.nanoTime());
    }

    public synchronized boolean offer(long ptsUs, int flags, byte[] data, long encodedAtNs) {
        return offerInternal(ptsUs, flags, data, encodedAtNs, false);
    }

    /** Ownership-transfer fast path; freshData must never be mutated after this call. */
    public synchronized boolean offerOwned(long ptsUs, int flags, byte[] freshData, long encodedAtNs) {
        return offerInternal(ptsUs, flags, freshData, encodedAtNs, true);
    }

    private boolean offerInternal(long ptsUs, int flags, byte[] data,
                                  long encodedAtNs, boolean owned) {
        if (closed) return false;
        if (data == null) throw new IllegalArgumentException("data == null");
        if (generation == 0) throw new IllegalStateException("Call beginGeneration first");
        if ((flags & CODEC_CONFIG) != 0) return cacheCodecConfig(data);

        if (data.length > maxBytes) {
            forceRecovery();
            droppedFrames++; // Incoming oversized video never enters the queue.
            return false;
        }
        if (pending.size() >= maxFrames || (long)queuedBytes + data.length > maxBytes) {
            forceRecovery();
        }
        boolean key = (flags & KEY_FRAME) != 0;
        if (awaitingKey && !key) {
            droppedFrames++;
            return false;
        }
        boolean bootstrap = awaitingKey;
        byte[] payload = owned ? data : Arrays.copyOf(data, data.length);
        byte[][] csd = bootstrap ? snapshotCodecConfig() : null;
        Frame frame = new Frame(generation, ptsUs, encodedAtNs, width, height,
                flags, payload, csd, bootstrap);
        pending.addLast(frame);
        queuedBytes += payload.length;
        if (bootstrap) {
            awaitingKey = false;
            // An already available IDR satisfies any not-yet-consumed request.
            keyframeNeeded = false;
        }
        notifyAll();
        return true;
    }

    private boolean cacheCodecConfig(byte[] data) {
        if (data.length == 0) throw new IllegalArgumentException("Empty codec-config");
        for (byte[] existing : codecConfig) if (Arrays.equals(existing, data)) return true;
        if (codecConfig.size() >= MAX_CODEC_CONFIG_ITEMS
                || (long)codecConfigBytes + data.length > MAX_CODEC_CONFIG_BYTES) {
            throw new IllegalArgumentException("Codec-config cache exceeds 8 items / 512 KiB");
        }
        byte[] copy = Arrays.copyOf(data, data.length);
        codecConfig.add(copy);
        codecConfigBytes += copy.length;
        // Initial CSD is cached while waiting. A new midstream CSD invalidates
        // pending video, including a queued bootstrap using the previous CSD.
        if (!awaitingKey) forceRecovery();
        return true;
    }

    private byte[][] snapshotCodecConfig() {
        byte[][] snapshot = new byte[codecConfig.size()][];
        for (int i = 0; i < snapshot.length; i++) {
            byte[] item = codecConfig.get(i);
            snapshot[i] = Arrays.copyOf(item, item.length);
        }
        return snapshot;
    }

    /**
     * Repeated controls must not discard a bootstrap queued/in flight, or repeatedly
     * regenerate an already-consumed key request while still awaiting the same IDR.
     */
    public synchronized void requestRecovery() {
        if (closed || awaitingKey || !inFlightRecovery.isEmpty() || hasPendingBootstrap()) return;
        forceRecovery();
    }

    private boolean hasPendingBootstrap() {
        for (Frame frame : pending) if (frame.startsGop) return true;
        return false;
    }

    /** Hard recovery: overflow/CSD change preserves only already-polled bootstrap tokens. */
    private void forceRecovery() {
        clearPending();
        awaitingKey = true;
        keyframeNeeded = true;
        recoveryCount++;
        notifyAll();
    }

    private void clearPending() {
        droppedFrames += pending.size();
        pending.clear();
        queuedBytes = 0;
    }

    public synchronized boolean consumeKeyframeRequest() {
        if (closed) return false;
        boolean result = keyframeNeeded;
        keyframeNeeded = false;
        return result;
    }

    /** timeoutMs=0 is non-blocking; positive timeouts use a monotonic deadline. */
    public synchronized Frame poll(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) throw new IllegalArgumentException("Negative timeout");
        long timeoutNs = timeoutMs > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE : timeoutMs * 1_000_000L;
        long startNs = System.nanoTime();
        while (pending.isEmpty() && !closed) {
            long remaining = timeoutNs - (System.nanoTime() - startNs);
            if (remaining <= 0) return null;
            wait(remaining / 1_000_000L, (int)(remaining % 1_000_000L));
        }
        if (closed) return null;
        Frame frame = pending.removeFirst();
        queuedBytes -= frame.data.length;
        if (frame.startsGop) inFlightRecovery.add(frame);
        return frame;
    }

    /** Only call after CONFIG/CSD/bootstrap data has successfully been sent. */
    public synchronized void markSent(Frame frame) {
        if (closed || frame == null || frame.generation != generation || !frame.startsGop) return;
        // Identity match: stale/double acknowledgements cannot release a newer IDR.
        inFlightRecovery.remove(frame);
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        clearPending();
        codecConfig.clear();
        codecConfigBytes = 0;
        inFlightRecovery.clear();
        keyframeNeeded = false;
        awaitingKey = true;
        notifyAll();
    }

    public synchronized boolean isClosed() { return closed; }
    public synchronized int depth() { return pending.size(); }
    public synchronized int queuedBytes() { return queuedBytes; }
    public synchronized long droppedFrames() { return droppedFrames; }
    /** Includes generation starts and hard recovery events, excludes repeated controls. */
    public synchronized long recoveryCount() { return recoveryCount; }
    public synchronized long generation() { return generation; }
    public synchronized boolean isAwaitingKeyFrame() { return awaitingKey; }
    public synchronized boolean hasRecoveryInFlight() { return !inFlightRecovery.isEmpty(); }
}
