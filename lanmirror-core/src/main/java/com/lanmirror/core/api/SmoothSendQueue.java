package com.lanmirror.core.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Single-owner frame loans crossing encoder/writer threads. Capacity is not a prebuffer target.
 */
public final class SmoothSendQueue {
    public static final class Entry {
        public final StreamFrame frame;
        public final SmoothWire.Config config;
        public final boolean bootstrap;

        private Entry(StreamFrame frame, SmoothWire.Config config, boolean bootstrap) {
            this.frame = frame;
            this.config = config;
            this.bootstrap = bootstrap;
        }
    }

    private final int maxFrames, maxBytes;
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private final ArrayList<byte[]> csd = new ArrayList<>();
    private final Set<Entry> inFlight = Collections.newSetFromMap(new IdentityHashMap<Entry, Boolean>());
    private long generation, drops, overflows, recoveries;
    private int width, height, bytes, csdBytes;
    private boolean closed, awaitKey = true, wantKey;

    public SmoothSendQueue(int maxFrames, int maxBytes) {
        if (maxFrames < 1 || maxBytes < 1) throw new IllegalArgumentException("bounds");
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    public synchronized void begin(int width, int height) {
        if (closed) return;
        if (width < 1 || height < 1) throw new IllegalArgumentException("dimensions");
        clearPending();
        csd.clear();
        csdBytes = 0;
        this.width = width;
        this.height = height;
        generation++;
        awaitKey = true;
        wantKey = true;
    }

    public synchronized void addConfig(byte[] config) {
        if (closed) return;
        if (config == null || config.length == 0) return;
        for (byte[] old : csd) if (Arrays.equals(old, config)) return;
        if (csd.size() >= 8 || (long) csdBytes + config.length > 512 * 1024)
            throw new IllegalArgumentException("CSD limit");
        csd.add(config.clone());
        csdBytes += config.length;
        // Distinct parameter-set snapshot gets a new transport generation and config ACK.
        generation++;
        recover();
    }

    /**
     * Takes this reference on every path (accepted, refused or exception).
     */
    public synchronized boolean offer(StreamFrame frame) {
        if (frame == null) throw new IllegalArgumentException("frame");
        boolean accepted = false;
        try {
            if (closed || frame.generation != generation || width == 0) {
                drops++;
                return false;
            }
            if (frame.length > maxBytes) {
                drops++;
                overflows++;
                recover();
                return false;
            }
            if (pending.size() >= maxFrames || (long) bytes + frame.length > maxBytes) {
                overflows++;
                recover();
            }
            if (awaitKey && (!frame.keyFrame() || csd.isEmpty())) {
                drops++;
                return false;
            }
            boolean bootstrap = awaitKey;
            SmoothWire.Config snapshot = bootstrap ? new SmoothWire.Config(generation, width, height, csd.toArray(new byte[csd.size()][])) : null;
            Entry entry = new Entry(frame, snapshot, bootstrap);
            pending.addLast(entry);
            bytes += frame.length;
            if (bootstrap) {
                awaitKey = false;
                wantKey = false;
            }
            accepted = true;
            notifyAll();
            return true;
        } finally {
            if (!accepted) frame.release();
        }
    }

    public synchronized Entry poll(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) throw new IllegalArgumentException("timeout");
        long end = System.nanoTime() + timeoutMs * 1000000L;
        while (pending.isEmpty() && !closed && timeoutMs > 0) {
            long left = end - System.nanoTime();
            if (left <= 0) break;
            wait(left / 1000000L, (int) (left % 1000000L));
        }
        Entry entry = pending.pollFirst();
        if (entry != null) {
            bytes -= entry.frame.length;
            if (entry.bootstrap) inFlight.add(entry);
        }
        return entry;
    }

    public synchronized void markSent(Entry entry) {
        inFlight.remove(entry);
    }

    public synchronized void requestRecovery() {
        if (closed || awaitKey) return;
        for (Entry entry : pending)
            if (entry.bootstrap && entry.frame.generation == generation) return;
        for (Entry entry : inFlight) if (entry.frame.generation == generation) return;
        recover();
    }

    private void recover() {
        clearPending();
        awaitKey = true;
        wantKey = true;
        recoveries++;
        notifyAll();
    }

    private void clearPending() {
        Entry e;
        while ((e = pending.pollFirst()) != null) {
            drops++;
            e.frame.release();
        }
        bytes = 0;
    }

    public synchronized boolean consumeKeyRequest() {
        boolean v = wantKey;
        wantKey = false;
        return v;
    }

    public synchronized boolean awaitingKey() {
        return awaitKey;
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized int depth() {
        return pending.size();
    }

    public synchronized int queuedBytes() {
        return bytes;
    }

    public synchronized long dropped() {
        return drops;
    }

    public synchronized long overflows() {
        return overflows;
    }

    public synchronized long recoveries() {
        return recoveries;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        clearPending();
        csd.clear();
        csdBytes = 0;
        inFlight.clear();
        notifyAll();
    }
}
