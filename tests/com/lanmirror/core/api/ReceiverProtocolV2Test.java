package com.lanmirror.core.api;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** Independent v2 tests. The historical WireTest is intentionally untouched. */
public final class ReceiverProtocolV2Test {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    private static void invalidPacket(int type, int length) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(type); out.writeInt(length);
        try { Wire.readPacket(input(bytes.toByteArray())); throw new AssertionError("Accepted malformed frame"); }
        catch (IOException expected) { checks++; }
    }
    private static void badDimensions(int width, int height) throws Exception {
        try { Wire.writeConfig(new DataOutputStream(new ByteArrayOutputStream()), width, height); throw new AssertionError("Accepted oversized codec"); }
        catch (IOException expected) { checks++; }
    }
    private static void liveClock() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<Wire.ClockSync> peer = pool.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setTcpNoDelay(true); socket.setSoTimeout(3000);
                    DataInputStream in = new DataInputStream(socket.getInputStream()); DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    if (!Wire.serverHandshake(in, out, "012345")) throw new AssertionError("Server handshake");
                    Wire.ClockSync sync = Wire.serverClockSync(in, out);
                    if (Wire.readPacket(in).type != Wire.END) throw new AssertionError("Clock exchanges must leave packet framing aligned");
                    return sync;
                }
            });
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                socket.setTcpNoDelay(true); socket.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(socket.getInputStream()); DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                check(Wire.clientHandshake(in, out, "012345"), "Handshake acceptance");
                Wire.ClockSync local = Wire.clientClockSync(in, out); Wire.writeEnd(out);
                Wire.ClockSync remote = peer.get(4, TimeUnit.SECONDS);
                check(local.receiverMinusSenderNs == remote.receiverMinusSenderNs && local.roundTripNs == remote.roundTripNs, "Both peers agree on best sample");
                check(local.roundTripNs >= 0, "Nonnegative RTT");
                check(Math.abs(local.receiverMinusSenderNs) <= local.roundTripNs / 2 + 1, "Same-process clocks offset bounded by transport asymmetry");
            }
        } finally { pool.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        check(Wire.VERSION == 2 && Wire.VIDEO_HEADER_SIZE == 20, "v2 constants");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        Wire.writeConfig(out, 1920, 1080);
        ByteBuffer encoded = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 88}); encoded.position(1); encoded.limit(4);
        Wire.writeVideo(out, encoded, 555L, 1, 123456789L); Wire.writeSyncRequest(out); Wire.writeEnd(out);
        check(encoded.position() == 1 && encoded.limit() == 4, "ByteBuffer range preserved");
        DataInputStream in = input(bytes.toByteArray()); Wire.Config size = Wire.readConfig(Wire.readPacket(in).data);
        check(size.width == 1920 && size.height == 1080, "1080p configuration");
        Wire.Packet video = Wire.readPacket(in); ByteBuffer body = ByteBuffer.wrap(video.data);
        check(video.type == Wire.VIDEO && video.data.length == 23, "20-byte header");
        check(body.getLong() == 555 && body.getInt() == 1 && body.getLong() == 123456789L, "PTS, flags and explicit send stamp");
        check(body.remaining() == 3 && body.get() == 1 && body.get() == 2 && body.get() == 3, "Encoded slice");
        check(Wire.readPacket(in).type == Wire.REQUEST_SYNC && Wire.readPacket(in).type == Wire.END && in.available() == 0, "Reverse control and packet alignment");
        bytes.reset(); long before = System.nanoTime(); Wire.writeVideo(out, ByteBuffer.allocate(0), 0, 0); long after = System.nanoTime();
        body = ByteBuffer.wrap(Wire.readPacket(input(bytes.toByteArray())).data); body.getLong(); body.getInt(); long automatic = body.getLong();
        check(automatic >= before && automatic <= after, "Automatic timestamp at sender packet boundary");
        invalidPacket(Wire.VIDEO, 19); invalidPacket(Wire.VIDEO, -1); invalidPacket(Wire.VIDEO, Wire.MAX_PACKET + 1);
        invalidPacket(Wire.CONFIG, 7); invalidPacket(Wire.END, 1); invalidPacket(Wire.REQUEST_SYNC, 1); invalidPacket(99, 0);
        badDimensions(0, 1080); badDimensions(8193, 1); badDimensions(8192, 8192);
        Wire.writeConfig(out, 8192, 2048); checks++;
        Wire.ClockSync sync = Wire.calculateClockSample(10000, 11010, 11015, 10025);
        check(sync.receiverMinusSenderNs == 1000 && sync.roundTripNs == 20, "Known symmetric clock offset/RTT");
        check(sync.ageNs(10000, 11080) == 80, "Cross-clock packet-to-submission age");
        check(sync.ageNs(10000, 10900) == 0, "Small negative estimated age clamped");
        check(sync.ageNs(Long.MIN_VALUE, Long.MAX_VALUE) == Long.MAX_VALUE, "Malformed time overflow not fresh");
        check(Wire.calculateClockSample(0, 1, 2, 4).receiverMinusSenderNs == 0, "Mixed-sign averaging truncation");
        check(Wire.calculateClockSample(0, -2, -1, 2).receiverMinusSenderNs == -2, "Negative half average rounds toward zero");
        try { Wire.calculateClockSample(10, 20, 15, 30); throw new AssertionError(); } catch (IOException expected) { checks++; }
        try { Wire.calculateClockSample(10, 20, 100, 30); throw new AssertionError(); } catch (IOException expected) { checks++; }
        DropRecovery recovery = new DropRecovery();
        check(recovery.classify(false, 0) == DropRecovery.Decision.DROP, "Initial P cannot feed");
        check(recovery.shouldRequestSync(0) && !recovery.shouldRequestSync(299999999) && recovery.shouldRequestSync(300000000), "300ms request rate limit");
        check(recovery.classify(true, 90000001) == DropRecovery.Decision.DROP && recovery.isWaitingForKey(), "Old IDR rejected");
        check(recovery.classify(true, 90000000) == DropRecovery.Decision.RECOVER_AT_KEY, "Fresh key at recovery threshold");
        check(recovery.classify(false, 1000000) == DropRecovery.Decision.FEED, "P allowed after fresh IDR");
        check(recovery.classify(false, 90000001) == DropRecovery.Decision.DROP, "Old P poisons GOP");
        check(recovery.classify(false, 1000000) == DropRecovery.Decision.DROP, "Must not feed later fresh P from poisoned GOP");
        check(recovery.classify(true, 1000000) == DropRecovery.Decision.RECOVER_AT_KEY, "Recover only at a new fresh IDR");
        LatencyStats stats = new LatencyStats(0, 4);
        for (int i = 1; i <= 10; i++) { stats.recordDecodedOutput(); stats.recordRendered(i * 1000000L); }
        stats.recordDrop(); stats.recordDrop();
        LatencyStats.Snapshot snapshot = stats.snapshotAndReset(1000000000L);
        check(snapshot.renderedSamples == 4 && snapshot.p50Ns == 8000000 && snapshot.p95Ns == 10000000, "Bounded window percentiles");
        check(snapshot.decodedFps == 10.0 && snapshot.droppedTotal == 2, "Output fps and cumulative drops");
        snapshot = stats.snapshotAndReset(2000000000L);
        check(snapshot.decodedFps == 0 && snapshot.p50Ns == -1 && snapshot.droppedTotal == 2, "Window resets but cumulative drops retained");
        FrameTimestampTracker tracker = new FrameTimestampTracker(3);
        tracker.add(5, 100); tracker.add(5, 200); tracker.add(6, 300);
        check(tracker.remove(5) == 100 && tracker.remove(5) == 200, "Repeated PTS matches FIFO, never replaces older send time");
        tracker.add(7, 400); tracker.add(8, 500); tracker.add(9, 600);
        check(tracker.size() == 3 && tracker.remove(6) == null, "Bounded timestamp eviction");
        tracker.clear(); check(tracker.size() == 0 && tracker.remove(7) == null, "Recovery clears old timestamps");
        recovery = new DropRecovery(150000000);
        check(recovery.classify(true, 140000000) == DropRecovery.Decision.RECOVER_AT_KEY, "Slow-link budget may recover fresh IDR above default 90ms");
        recovery.setBudgetNs(1000); check(recovery.budgetNs() == 90000000, "Recovery budget lower bound");
        recovery.setBudgetNs(Long.MAX_VALUE); check(recovery.budgetNs() == 2000000000L, "Recovery budget upper bound");
        liveClock();
        System.out.println("PASS: " + checks + " receiver v2 assertions (clock calibration, ages, limits, recovery, bounded stats, live TCP)");
    }
}
