package com.lanmirror.audio.internal;

import com.lanmirror.core.api.Wire;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One additional TCP socket for one approved peer; all failures are audio-only.
 */
final class AudioLaneReceiver implements AutoCloseable {
    public interface Listener {
        void onFrame(PcmAudioFrame frame, long localCapturedAtNs);

        void onStatus(String text);
    }

    public final long tokenHigh, tokenLow;
    private final InetAddress expected;
    private final ServerSocket server;
    private volatile Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
    private volatile Socket socket;
    private volatile long readStarted;
    private Thread reader, watchdog;

    public AudioLaneReceiver(InetAddress expected, Listener listener) throws IOException {
        if (expected == null || listener == null)
            throw new IllegalArgumentException("Audio peer/listener missing");
        this.expected = expected;
        this.listener = listener;
        server = new ServerSocket(0, 1);
        server.setSoTimeout(300);
        SecureRandom random = new SecureRandom();
        long high, low;
        do {
            high = random.nextLong();
            low = random.nextLong();
        } while ((high | low) == 0);
        tokenHigh = high;
        tokenLow = low;
    }

    public int port() {
        return server.getLocalPort();
    }

    public void start(Wire.ClockSync clock) {
        if (clock == null) throw new IllegalArgumentException("Clock missing");
        if (closed.get() || !started.compareAndSet(false, true)) return;
        watchdog = new Thread(() -> {
            while (!closed.get()) {
                long read = readStarted;
                if (read != 0 && System.nanoTime() - read > 3_000_000_000L) {
                    status("声音读取超时，已停止音频通道；视频继续");
                    close();
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "audio-read-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        reader = new Thread(() -> run(clock), "approved-audio-receiver");
        reader.setDaemon(true);
        reader.start();
    }

    private void run(Wire.ClockSync clock) {
        String remoteStatus = null;
        try {
            long deadline = System.nanoTime() + 20_000_000_000L;
            DataInputStream input = null;
            for (int attempts = 0; !closed.get() && input == null && attempts < 8; ) {
                if (System.nanoTime() >= deadline) throw new IOException("发送端未建立声音通道");
                Socket candidate;
                try {
                    candidate = server.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                attempts++;
                socket = candidate;
                if (closed.get()) {
                    quiet(candidate);
                    return;
                }
                if (!expected.equals(candidate.getInetAddress())) {
                    quiet(candidate);
                    socket = null;
                    continue;
                }
                candidate.setTcpNoDelay(true);
                candidate.setReceiveBufferSize(16 * 1024);
                candidate.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(new BufferedInputStream(candidate.getInputStream(), 8192));
                DataOutputStream out = new DataOutputStream(candidate.getOutputStream());
                readStarted = System.nanoTime();
                try {
                    AudioLaneWire.accept(in, out, tokenHigh, tokenLow);
                    input = in;
                } catch (IOException rejected) {
                    quiet(candidate);
                    socket = null;
                } finally {
                    readStarted = 0;
                }
            }
            if (closed.get()) return;
            if (input == null) throw new IOException("声音通道鉴权失败次数过多");
            quiet(server);
            status("内部声音通道已连接：PCM16 / 48kHz / 双声道；按窗口按钮选择播放");
            long lastSequence = -1;
            while (!closed.get()) {
                readStarted = System.nanoTime();
                AudioLaneWire.Message message = AudioLaneWire.read(input);
                readStarted = 0;
                if (message.status != null) {
                    remoteStatus = message.status;
                    status(message.status);
                    continue;
                }
                PcmAudioFrame frame = message.frame;
                if (frame.sequence <= lastSequence) throw new IOException("声音帧序号重复或倒退");
                lastSequence = frame.sequence;
                long local;
                try {
                    local = Math.addExact(frame.capturedAtNs, clock.receiverMinusSenderNs);
                } catch (ArithmeticException invalid) {
                    throw new IOException("声音时钟换算越界", invalid);
                }
                Listener events = listener;
                if (!closed.get() && events != null) events.onFrame(frame, local);
            }
        } catch (EOFException ended) {
            if (!closed.get())
                status("声音通道已结束" + (remoteStatus == null ? "" : "：" + remoteStatus) + "；视频可继续");
        } catch (IOException | RuntimeException error) {
            if (!closed.get()) status("声音不可用：" + describe(error) + "；视频继续");
        } finally {
            readStarted = 0;
            close();
        }
    }

    private void status(String text) {
        Listener events = listener;
        if (!closed.get() && events != null) try {
            events.onStatus(text);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        listener = null;
        quiet(server);
        quiet(socket);
        if (reader != null) reader.interrupt();
        if (watchdog != null) watchdog.interrupt();
    }

    private static String describe(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void quiet(Closeable object) {
        if (object != null) try {
            object.close();
        } catch (IOException ignored) {
        }
    }
}
