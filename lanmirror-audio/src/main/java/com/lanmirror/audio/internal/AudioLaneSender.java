package com.lanmirror.audio.internal;

import com.lanmirror.core.api.ApprovalProtocol;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Bounded PCM writer on a separate socket, never holding a video/control writer lock.
 */
final class AudioLaneSender implements AutoCloseable {
    public interface Listener {
        void onStatus(String text);

        void onError(String text);
    }

    private final InetAddress address;
    private final int port;
    private final long high, low;
    private volatile Listener listener;
    private final Socket socket = new Socket();
    private final ArrayBlockingQueue<PcmAudioFrame> queue = new ArrayBlockingQueue<>(6);
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
    private final AtomicReference<String> finalStatus = new AtomicReference<>();
    private final AtomicLong writeStarted = new AtomicLong(), dropped = new AtomicLong();
    private Thread writer, watchdog;

    public AudioLaneSender(InetAddress address, int port, long high, long low, Listener listener) {
        if (address == null || port < 1 || port > 65535 || (high | low) == 0 || listener == null)
            throw new IllegalArgumentException("Invalid audio endpoint");
        this.address = address;
        this.port = port;
        this.high = high;
        this.low = low;
        this.listener = listener;
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        watchdog = new Thread(() -> {
            while (!closed.get()) {
                long since = writeStarted.get();
                if (since != 0 && System.nanoTime() - since > 750_000_000L) {
                    error("声音写入阻塞，已单独停止音频；视频继续");
                    close();
                    return;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "audio-write-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        writer = new Thread(this::run, "internal-audio-writer");
        writer.setDaemon(true);
        writer.start();
    }

    public void offer(PcmAudioFrame frame) {
        if (frame == null || closed.get() || finalStatus.get() != null) return;
        if (old(frame, System.nanoTime())) {
            dropped.incrementAndGet();
            return;
        }
        while (!closed.get() && !queue.offer(frame)) {
            if (queue.poll() != null) dropped.incrementAndGet();
        }
    }

    public void finishWithStatus(String status) {
        if (!closed.get()) {
            finalStatus.compareAndSet(null, ApprovalProtocol.displayName(status));
            queue.clear();
        }
    }

    public long droppedFrames() {
        return dropped.get();
    }

    private void run() {
        try {
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(16 * 1024);
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress(address, port), 5000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 8192));
            AudioLaneWire.connect(in, out, high, low);
            status("内部声音独立TCP已连接：PCM16/48kHz/双声道，约1.54Mbps");
            long lastHeartbeat = System.nanoTime();
            while (!closed.get()) {
                String finish = finalStatus.get();
                if (finish != null) {
                    writeStarted.set(System.nanoTime());
                    AudioLaneWire.writeStatus(out, finish);
                    writeStarted.set(0);
                    break;
                }
                PcmAudioFrame frame = queue.poll(20, TimeUnit.MILLISECONDS);
                long now = System.nanoTime();
                if (frame != null) {
                    if (old(frame, now)) {
                        dropped.incrementAndGet();
                        continue;
                    }
                    writeStarted.set(now);
                    AudioLaneWire.writeFrame(out, frame);
                    writeStarted.set(0);
                    lastHeartbeat = now;
                } else if (now - lastHeartbeat >= 1_000_000_000L) {
                    writeStarted.set(now);
                    AudioLaneWire.writeStatus(out, "等待可捕获的内部声音；静音内容或音源禁止捕获时可能无声");
                    writeStarted.set(0);
                    lastHeartbeat = now;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            if (!closed.get())
                error("声音发送失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "；视频继续");
        } finally {
            writeStarted.set(0);
            close();
        }
    }

    private static boolean old(PcmAudioFrame frame, long now) {
        try {
            long age = Math.subtractExact(now, frame.capturedAtNs);
            return age > 250_000_000L || age < -250_000_000L;
        } catch (ArithmeticException e) {
            return true;
        }
    }

    private void status(String text) {
        Listener events = listener;
        if (!closed.get() && events != null) try {
            events.onStatus(text);
        } catch (RuntimeException ignored) {
        }
    }

    private void error(String text) {
        Listener events = listener;
        if (!closed.get() && events != null) try {
            events.onError(text);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        listener = null;
        queue.clear();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (writer != null) writer.interrupt();
        if (watchdog != null) watchdog.interrupt();
    }
}
