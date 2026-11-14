package com.lanmirror.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Version 2 big-endian framing. All clock times are monotonic nanoTime values, not wall time. */
public final class Wire {
    public static final int MAGIC = 0x4c4d5231;
    public static final int VERSION = 2;
    public static final int CONFIG = 1, VIDEO = 2, END = 3, REQUEST_SYNC = 4;
    public static final int MAX_PACKET = 4 * 1024 * 1024;
    public static final int VIDEO_HEADER_SIZE = 20;
    public static final int CLOCK_SAMPLES = 8;
    public static final int MAX_DIMENSION = 8192;
    public static final long MAX_PIXELS = 16777216L;
    private Wire() { }

    public static final class Packet {
        public final int type;
        public final byte[] data;
        private Packet(int type, byte[] data) { this.type = type; this.data = data; }
    }
    public static final class Config {
        public final int width, height;
        private Config(int width, int height) { this.width = width; this.height = height; }
    }
    /** Receiver clock minus sender clock; RTT is calibration transport RTT, not frame latency. */
    public static final class ClockSync {
        public final long receiverMinusSenderNs, roundTripNs;
        public ClockSync(long receiverMinusSenderNs, long roundTripNs) {
            if (roundTripNs < 0) throw new IllegalArgumentException("Negative round-trip time");
            this.receiverMinusSenderNs = receiverMinusSenderNs;
            this.roundTripNs = roundTripNs;
        }
        /** Estimated sender packet-write -> receiver render-submission age, NEVER end-to-end. */
        public long ageNs(long sentNs, long receiverNowNs) {
            try {
                return Math.max(0L, Math.subtractExact(Math.subtractExact(receiverNowNs, sentNs), receiverMinusSenderNs));
            } catch (ArithmeticException overflow) {
                // Malformed/out-of-range time must not be treated as a fresh frame.
                return Long.MAX_VALUE;
            }
        }
    }

    /** Handshake only. Caller must explicitly clock-sync before reading/writing v2 packets. */
    public static boolean clientHandshake(DataInputStream input, DataOutputStream output, String code) throws IOException {
        requireCode(code);
        output.writeInt(MAGIC); output.writeInt(VERSION); output.writeUTF(code); output.flush();
        int result = input.readInt();
        if (result != 0 && result != 1) throw new IOException("Invalid handshake response: " + result);
        return result == 1;
    }
    public static boolean serverHandshake(DataInputStream input, DataOutputStream output, String code) throws IOException {
        requireCode(code);
        int magic = input.readInt(), version = input.readInt();
        String supplied = input.readUTF();
        boolean accepted = magic == MAGIC && version == VERSION && code.equals(supplied);
        output.writeInt(accepted ? 1 : 0); output.flush();
        return accepted;
    }

    /** Pure Java NTP calculation. Invalid samples throw; clients skip them but complete all 8 exchanges. */
    public static ClockSync calculateClockSample(long t1, long t2, long t3, long t4) throws IOException {
        try {
            long clientElapsed = Math.subtractExact(t4, t1);
            long serverElapsed = Math.subtractExact(t3, t2);
            long rtt = Math.subtractExact(clientElapsed, serverElapsed);
            if (clientElapsed < 0 || serverElapsed < 0 || rtt < 0) throw new IOException("Invalid clock sample ordering");
            long a = Math.subtractExact(t2, t1), b = Math.subtractExact(t3, t4);
            // Average without overflowing a+b. Division follows Java truncation toward zero.
            long offset = (a & b) + ((a ^ b) >> 1);
            if (offset < 0 && ((a ^ b) & 1L) != 0) offset++;
            return new ClockSync(offset, rtt);
        } catch (ArithmeticException overflow) { throw new IOException("Clock sample overflow", overflow); }
    }
    public static ClockSync clientClockSync(DataInputStream input, DataOutputStream output) throws IOException {
        ClockSync best = null;
        for (int i = 0; i < CLOCK_SAMPLES; i++) {
            long t1 = System.nanoTime(); output.writeLong(t1); output.flush();
            long t2 = input.readLong(), t3 = input.readLong(), t4 = System.nanoTime();
            try {
                ClockSync sample = calculateClockSample(t1, t2, t3, t4);
                if (best == null || sample.roundTripNs < best.roundTripNs) best = sample;
            } catch (IOException invalid) { /* Keep the fixed exchange count so the peer stays aligned. */ }
        }
        if (best == null) {
            // Negative RTT is a failure sentinel: server also fails rather than interpreting packets as clocks.
            output.writeLong(0); output.writeLong(-1); output.flush();
            throw new IOException("No valid clock synchronization sample");
        }
        output.writeLong(best.receiverMinusSenderNs); output.writeLong(best.roundTripNs); output.flush();
        return best;
    }
    public static ClockSync serverClockSync(DataInputStream input, DataOutputStream output) throws IOException {
        for (int i = 0; i < CLOCK_SAMPLES; i++) {
            input.readLong(); // Sender t1 is not needed by the receiver's reply.
            long t2 = System.nanoTime(), t3 = System.nanoTime();
            output.writeLong(t2); output.writeLong(t3); output.flush();
        }
        long offset = input.readLong(), rtt = input.readLong();
        if (rtt < 0) throw new IOException("Sender could not calibrate clocks");
        return new ClockSync(offset, rtt);
    }

    public static Packet readPacket(DataInputStream input) throws IOException {
        int type = input.readInt(), length = input.readInt();
        checkLength(type, length);
        byte[] data = new byte[length]; input.readFully(data);
        if (type == CONFIG) readConfig(data);
        return new Packet(type, data);
    }
    public static Config readConfig(byte[] data) throws IOException {
        if (data == null || data.length != 8) throw new IOException("CONFIG must contain exactly 8 bytes");
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int width = buffer.getInt(), height = buffer.getInt(); checkDimensions(width, height);
        return new Config(width, height);
    }
    public static void writeConfig(DataOutputStream output, int width, int height) throws IOException {
        checkDimensions(width, height);
        output.writeInt(CONFIG); output.writeInt(8); output.writeInt(width); output.writeInt(height); output.flush();
    }
    /** Preserve caller position/limit. Timestamp is recorded at this sender packet-write boundary. */
    public static void writeVideo(DataOutputStream output, ByteBuffer encoded, long pts, int flags) throws IOException {
        writeVideo(output, encoded, pts, flags, System.nanoTime());
    }
    /** Explicit timestamp overload for deterministic tests. Header: long PTS, int flags, long sentNs. */
    public static void writeVideo(DataOutputStream output, ByteBuffer encoded, long pts, int flags, long sentNs) throws IOException {
        if (encoded == null) throw new IllegalArgumentException("encoded == null");
        ByteBuffer source = encoded.duplicate(); int size = source.remaining();
        if (size > MAX_PACKET - VIDEO_HEADER_SIZE) throw new IOException("VIDEO exceeds MAX_PACKET");
        output.writeInt(VIDEO); output.writeInt(VIDEO_HEADER_SIZE + size);
        output.writeLong(pts); output.writeInt(flags); output.writeLong(sentNs);
        byte[] chunk = new byte[Math.min(size, 16 * 1024)];
        while (source.hasRemaining()) { int count = Math.min(source.remaining(), chunk.length); source.get(chunk, 0, count); output.write(chunk, 0, count); }
        output.flush();
    }
    public static void writeEnd(DataOutputStream output) throws IOException { writeEmpty(output, END); }
    public static void writeSyncRequest(DataOutputStream output) throws IOException { writeEmpty(output, REQUEST_SYNC); }
    private static void writeEmpty(DataOutputStream output, int type) throws IOException { output.writeInt(type); output.writeInt(0); output.flush(); }
    static void requireCode(String code) {
        if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Pairing code must contain six ASCII digits");
    }
    private static void checkDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION || (long)width * height > MAX_PIXELS)
            throw new IOException("Invalid/oversized video dimensions: " + width + "x" + height);
    }
    private static void checkLength(int type, int length) throws IOException {
        if (length < 0 || length > MAX_PACKET) throw new IOException("Invalid packet length: " + length);
        switch (type) {
            case CONFIG: if (length != 8) throw new IOException("Invalid CONFIG length"); break;
            case VIDEO: if (length < VIDEO_HEADER_SIZE) throw new IOException("Invalid VIDEO length"); break;
            case END: case REQUEST_SYNC: if (length != 0) throw new IOException("Control packet must be empty"); break;
            default: throw new IOException("Unknown packet type: " + type);
        }
    }
}
