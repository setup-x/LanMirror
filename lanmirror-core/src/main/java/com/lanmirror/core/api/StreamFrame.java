package com.lanmirror.core.api;

/**
 * Immutable metadata/read-only payload; one initial reference transfers to consumer.
 * Invalid constructor arguments DO NOT consume the array: caller must recycle it.
 * Every successful retain creates one ownership obligation. Final release returns the loan.
 */
public final class StreamFrame {
    public final long generation, sequence, ptsUs, encodedAtNs, receivedAtNs;
    public final int flags, offset, length;
    public final byte[] data;
    private final FrameBufferPool owner;
    private int references = 1;

    public StreamFrame(long gen, long seq, long pts, int flags, long stamp, long received, byte[] data, int offset, int length, FrameBufferPool owner) {
        if (gen < 0 || seq < 0 || data == null || offset < 0 || length < 0 || (long) offset + length > data.length || length > SmoothWire.MAX_VIDEO_BYTES)
            throw new IllegalArgumentException("Invalid frame range/identity");
        this.generation = gen;
        this.sequence = seq;
        this.ptsUs = pts;
        this.flags = flags;
        this.encodedAtNs = stamp;
        this.receivedAtNs = received;
        this.data = data;
        this.offset = offset;
        this.length = length;
        this.owner = owner;
    }

    public synchronized StreamFrame retain() {
        if (references <= 0) throw new IllegalStateException("Frame already released");
        if (references == Integer.MAX_VALUE)
            throw new IllegalStateException("Frame reference overflow");
        references++;
        return this;
    }

    public synchronized void release() {
        if (references <= 0) throw new IllegalStateException("Frame reference underflow");
        if (--references == 0 && owner != null) owner.recycle(data);
    }

    public synchronized int referenceCount() {
        return references;
    }

    public boolean keyFrame() {
        return (flags & 1) != 0;
    }

    public boolean codecConfig() {
        return (flags & 2) != 0;
    }
}
