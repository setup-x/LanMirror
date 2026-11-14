package com.lanmirror.audio.internal;

import java.util.Arrays;

/**
 * PCM16 little-endian stereo. Public data is owned by this frame: consumers must never modify/reuse it.
 */
final class PcmAudioFrame {
    public static final int SAMPLE_RATE = 48000, CHANNELS = 2, BYTES_PER_SAMPLE = 2, MAX_BYTES = 3840;
    public static final long FRAME_NS = 20_000_000L;
    public final long sequence, capturedAtNs;
    public final byte[] data;
    public final int length;

    /**
     * Copies exactly length bytes; caller may reuse its input after construction.
     */
    public PcmAudioFrame(long sequence, long capturedAtNs, byte[] data, int length) {
        if (sequence < 0 || data == null || length <= 0 || length > MAX_BYTES || length > data.length || length % 4 != 0)
            throw new IllegalArgumentException("PCM requires 1..3840 bytes, stereo16 frame aligned");
        this.sequence = sequence;
        this.capturedAtNs = capturedAtNs;
        this.length = length;
        this.data = Arrays.copyOf(data, length);
    }

    public int sampleFrames() {
        return length / 4;
    }

    public long durationNs() {
        return sampleFrames() * 1_000_000_000L / SAMPLE_RATE;
    }
}
