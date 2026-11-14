package com.lanmirror.core.api;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** JVM tests: no Android device or dependency needed. */
public final class WireTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    private static void badPacket(int type, int length) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(type); out.writeInt(length);
        try { Wire.readPacket(input(bytes.toByteArray())); throw new AssertionError("Accepted bad packet"); }
        catch (IOException expected) { assertions++; }
    }
    private static void handshake(String supplied, boolean expected) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<Boolean> result = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    return Wire.serverHandshake(new DataInputStream(socket.getInputStream()), new DataOutputStream(socket.getOutputStream()), "012345");
                }
            });
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                socket.setSoTimeout(2000);
                check(Wire.clientHandshake(new DataInputStream(socket.getInputStream()), new DataOutputStream(socket.getOutputStream()), supplied) == expected, "Client handshake");
            }
            check(result.get(3, TimeUnit.SECONDS) == expected, "Server handshake");
        } finally { executor.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        Wire.writeConfig(out, 1280, 720);
        ByteBuffer video = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 88}); video.position(1); video.limit(4);
        Wire.writeVideo(out, video, 123456789L, 2, 99887766L); Wire.writeEnd(out);
        check(video.position() == 1 && video.limit() == 4, "ByteBuffer range preserved");
        DataInputStream in = input(bytes.toByteArray()); Wire.Packet config = Wire.readPacket(in);
        check(config.type == Wire.CONFIG, "CONFIG type");
        Wire.Config size = Wire.readConfig(config.data); check(size.width == 1280 && size.height == 720, "CONFIG dimensions");
        Wire.Packet packet = Wire.readPacket(in); check(packet.type == Wire.VIDEO, "VIDEO type");
        ByteBuffer payload = ByteBuffer.wrap(packet.data);
        check(payload.getLong() == 123456789L && payload.getInt() == 2, "PTS and codec-config flag");
        check(payload.getLong() == 99887766L, "Sender output timestamp");
        check(payload.remaining() == 3 && payload.get() == 1 && payload.get() == 2 && payload.get() == 3, "Correct BufferInfo slice");
        check(Wire.readPacket(in).type == Wire.END && in.available() == 0, "END and frame boundaries");
        // readFully must tolerate transport fragmentation, not assume a single read is a frame.
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(len, 1)); }
        };
        check(Wire.readConfig(Wire.readPacket(new DataInputStream(fragmented)).data).width == 1280, "Fragmented TCP payload");
        badPacket(99, 0); badPacket(Wire.VIDEO, -1); badPacket(Wire.VIDEO, Wire.MAX_PACKET + 1);
        badPacket(Wire.CONFIG, 7); badPacket(Wire.END, 1); badPacket(Wire.VIDEO, Wire.VIDEO_HEADER_SIZE - 1);
        try { Wire.readPacket(input(new byte[] {0,0,0,1, 0,0,0,8, 0})); throw new AssertionError("Accepted truncation"); } catch (EOFException expected) { assertions++; }
        try { Wire.writeConfig(out, 0, 720); throw new AssertionError("Accepted zero width"); } catch (IOException expected) { assertions++; }
        try { Wire.clientHandshake(input(new byte[0]), out, "ABCDEF"); throw new AssertionError("Accepted invalid PIN"); } catch (IllegalArgumentException expected) { assertions++; }
        handshake("012345", true); handshake("999999", false);
        System.out.println("PASS: " + assertions + " protocol assertions (framing, payload, truncation, limits, fragmented reads, live TCP accept/reject)");
    }
}
