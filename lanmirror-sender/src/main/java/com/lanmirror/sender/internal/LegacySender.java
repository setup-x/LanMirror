package com.lanmirror.sender.internal;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import com.lanmirror.core.api.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class LegacySender implements SenderEngine.Pipeline {
    private final SenderEngine owner;
    private final Socket socket = new Socket();
    private final OutboundFrameQueue queue = new OutboundFrameQueue(3, 2 * 1024 * 1024);
    private volatile boolean cancelled;

    LegacySender(SenderEngine owner) {
        this.owner = owner;
    }

    @Override
    public void cancel() {
        cancelled = true;
        queue.close();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private boolean stopped() {
        return cancelled || owner.stopping || socket.isClosed() || queue.isClosed();
    }

    @Override
    public void run() {
        setDisplayPriority();
        EncoderFactory.Encoder session = null;
        VirtualDisplay display = null;
        Thread control = null, writer = null, watchdog = null;
        final NetworkStats stats = new NetworkStats();
        final AtomicLong writeStartedNs = new AtomicLong();
        final AtomicLong feedbackRequests = new AtomicLong();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        int profile = owner.config.profile;
        boolean directTransport = owner.config.directTransport, burstEncoder = owner.config.burstEncoder;
        try {
            if (stopped()) return;
            owner.publish("连接接收端… · 建议两端更新 1.5.0");
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(64 * 1024);
            socket.setReceiveBufferSize(64 * 1024);
            socket.connect(new InetSocketAddress(owner.config.host, 7236), 10000);
            socket.setSoTimeout(10000);
            final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 16 * 1024));
            final DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1024));
            if (!Wire.clientHandshake(in, out, owner.config.pin))
                throw new IOException("配对码不正确或协议版本不一致");
            Wire.ClockSync clock = Wire.clientClockSync(in, out);
            socket.setSoTimeout(0);
            control = new Thread(() -> {
                try {
                    while (!stopped()) {
                        Wire.Packet packet = Wire.readPacket(in);
                        if (packet.type == Wire.REQUEST_SYNC) {
                            feedbackRequests.incrementAndGet();
                            queue.requestRecovery();
                        } else if (packet.type == Wire.END) break;
                        else throw new IOException("Unexpected receiver control packet");
                    }
                } catch (IOException e) {
                    if (!stopped()) failure.compareAndSet(null, e);
                } finally {
                    cancel();
                }
            }, "mirror-control");
            control.start();
            DisplayMetrics metrics = new DisplayMetrics();
            owner.windowManager.getDefaultDisplay().getRealMetrics(metrics);
            long captureSize = owner.requestedSize;
            int sourceW = captureSize != 0 ? (int) (captureSize >>> 32) : metrics.widthPixels;
            int sourceH = captureSize != 0 ? (int) captureSize : metrics.heightPixels;
            int[] size = StreamProfile.sizeFor(sourceW, sourceH, profile);
            if (stopped()) return;
            session = EncoderFactory.create(size[0], size[1], StreamProfile.bitrate(profile));
            queue.beginGeneration(size[0], size[1]);
            writer = new Thread(() -> writeFrames(out, stats, writeStartedNs, failure, directTransport), "mirror-network-writer");
            writer.start();
            if (stopped()) return;
            display = owner.projection.createVirtualDisplay("LanMirror", size[0], size[1], metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, session.surface, null, owner.handler);
            watchdog = new Thread(() -> {
                while (!stopped()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long started = writeStartedNs.get();
                    if (started != 0 && System.nanoTime() - started > 2_000_000_000L) {
                        failure.compareAndSet(null, new IOException("网络写入阻塞超过2秒"));
                        cancel();
                        return;
                    }
                }
            }, "mirror-write-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long nextLegacySizePoll = 0, lastSyncRequest = 0, reportAt = System.nanoTime();
            long encoded = 0, previousSent = 0, previousBytes = 0, previousWriteTotal = 0, previousQueueTotal = 0;
            boolean pendingSync = true;
            long burstGroups = 0;
            int maxBurst = 0;
            owner.publish("正在投屏 " + size[0] + " × " + size[1] + " @60 · " + StreamProfile.bitrate(profile) / 1000000 + "Mbps\n编码/网络线程解耦 · 稳60与70ms需真机确认");
            while (!stopped()) {
                if (Build.VERSION.SDK_INT < 34 && SystemClock.elapsedRealtime() >= nextLegacySizePoll) {
                    owner.windowManager.getDefaultDisplay().getRealMetrics(metrics);
                    owner.updateSourceSize(metrics.widthPixels, metrics.heightPixels);
                    nextLegacySizePoll = SystemClock.elapsedRealtime() + 500;
                }
                long resize = owner.requestedSize;
                int rw = (int) (resize >>> 32), rh = (int) resize;
                if (rw > 0 && rh > 0 && (rw != sourceW || rh != sourceH)) {
                    sourceW = rw;
                    sourceH = rh;
                    int[] newSize = StreamProfile.sizeFor(rw, rh, profile);
                    if (newSize[0] != size[0] || newSize[1] != size[1]) {
                        display.setSurface(null);
                        session.close();
                        session = null;
                        size = newSize;
                        session = EncoderFactory.create(size[0], size[1], StreamProfile.bitrate(profile));
                        queue.beginGeneration(size[0], size[1]);
                        display.resize(size[0], size[1], metrics.densityDpi);
                        display.setSurface(session.surface);
                        pendingSync = true;
                        lastSyncRequest = 0;
                    }
                }
                long now = System.nanoTime();
                if (queue.consumeKeyframeRequest()) pendingSync = true;
                if (!queue.isAwaitingKeyFrame()) pendingSync = false;
                if (pendingSync && now - lastSyncRequest >= 300_000_000L) {
                    requestKeyFrame(session.codec);
                    lastSyncRequest = now;
                    pendingSync = false;
                }
                if (queue.isAwaitingKeyFrame() && !queue.hasRecoveryInFlight() && now - lastSyncRequest >= 300_000_000L)
                    pendingSync = true;
                int drainedThisPass = 0;
                long drainStartedNs = 0;
                do {
                    int index = session.codec.dequeueOutputBuffer(info, drainedThisPass == 0 ? 2000 : 0);
                    if (drainedThisPass == 0) drainStartedNs = System.nanoTime();
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        cacheFormatConfig(session.codec.getOutputFormat(), queue);
                    } else if (index >= 0) {
                        final long encodedAtNs = System.nanoTime();
                        try {
                            if (info.size > 0) {
                                ByteBuffer data = session.codec.getOutputBuffer(index);
                                data.position(info.offset);
                                data.limit(info.offset + info.size);
                                byte[] bytes = new byte[info.size];
                                data.get(bytes);
                                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                                    encoded++;
                                queue.offerOwned(info.presentationTimeUs, info.flags, bytes, encodedAtNs);
                            }
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                                throw new IOException("硬件编码输出已结束");
                        } finally {
                            session.codec.releaseOutputBuffer(index, false);
                        }
                    }
                    if (index < 0 && index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break;
                    drainedThisPass++;
                } while (!stopped() && EncoderDrainPolicy.continueBurst(burstEncoder, drainedThisPass, drainStartedNs, System.nanoTime()));
                if (drainedThisPass > 1) burstGroups++;
                maxBurst = Math.max(maxBurst, drainedThisPass);
                now = System.nanoTime();
                if (now - reportAt >= 1_000_000_000L) {
                    double seconds = (now - reportAt) / 1_000_000_000.0;
                    long sent = stats.frames.get(), dataBytes = stats.bytes.get(), writeTotal = stats.writeNs.get(), queueTotal = stats.queueNs.get();
                    long sentDelta = sent - previousSent;
                    owner.publish(String.format(Locale.US,
                            "正在投屏 %d × %d @60 · 目标%d Mbps\n编码输出 %.1f fps / 网络发送 %.1f fps · 实际 %.1f Mbps\n排队平均/最大 %.1f/%.1f ms · 写入平均/最大 %.1f/%.1f ms\n待发 %d/3帧 · 累计丢帧%d / GOP恢复%d / 对端请求%d\nRTT %.1f ms · TCP实际发送缓冲%d KB\n编码器 %s · %s\n稳60和70ms端到端需要真机测量",
                            size[0], size[1], StreamProfile.bitrate(profile) / 1000000,
                            encoded / seconds, sentDelta / seconds, (dataBytes - previousBytes) * 8.0 / seconds / 1000000,
                            sentDelta == 0 ? 0 : (queueTotal - previousQueueTotal) / sentDelta / 1000000.0, stats.maxQueueNs.getAndSet(0) / 1000000.0,
                            sentDelta == 0 ? 0 : (writeTotal - previousWriteTotal) / sentDelta / 1000000.0, stats.maxWriteNs.getAndSet(0) / 1000000.0,
                            queue.depth(), queue.droppedFrames(), queue.recoveryCount(), feedbackRequests.get(), clock.roundTripNs / 1000000.0,
                            socket.getSendBufferSize() / 1024, session.name, (session.hints ? "60fps实时/无B帧参数" : "驱动基础实时配置") + "\n传输A=" + (directTransport ? "直接数组" : "原模式") + " / 编码B=" + (burstEncoder ? "有界连续取帧" : "原模式") + "\n本秒连续取帧组/最大=" + burstGroups + "/" + maxBurst + "；应用丢帧不等于网络丢包"));
                    burstGroups = 0;
                    maxBurst = 0;
                    encoded = 0;
                    previousSent = sent;
                    previousBytes = dataBytes;
                    previousWriteTotal = writeTotal;
                    previousQueueTotal = queueTotal;
                    reportAt = now;
                }
            }
            if (!owner.stopping)
                owner.publish("投屏结束：" + (failure.get() == null ? "接收端连接已关闭" : SenderEngine.message(failure.get())));
        } catch (Exception e) {
            if (!owner.stopping) owner.publish("投屏结束：" + SenderEngine.message(e));
        } finally {
            cancel();
            if (watchdog != null) watchdog.interrupt();
            joinBounded(writer);
            joinBounded(control);
            if (display != null) try {
                display.release();
            } catch (RuntimeException ignored) {
            }
            if (session != null) session.close();
        }
    }

    private void writeFrames(DataOutputStream out, NetworkStats stats, AtomicLong startedNs,
                             AtomicReference<Exception> failure, boolean directTransport) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
        } catch (RuntimeException ignored) {
        }
        long sentGeneration = -1;
        FrameStreamWriter frames = new FrameStreamWriter(out, directTransport);
        try {
            while (!stopped()) {
                OutboundFrameQueue.Frame frame = queue.poll(20);
                if (frame == null) continue;
                long begin = System.nanoTime();
                startedNs.set(begin);
                long queuedNs = Math.max(0, begin - frame.encodedAtNs);
                try {
                    if (sentGeneration != frame.generation) {
                        Wire.writeConfig(out, frame.width, frame.height);
                        sentGeneration = frame.generation;
                    }
                    if (frame.startsGop && frame.codecConfig != null) {
                        for (byte[] config : frame.codecConfig)
                            frames.write(config, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, System.nanoTime());
                    }
                    frames.write(frame.data, frame.ptsUs, frame.flags, frame.encodedAtNs);
                    queue.markSent(frame);
                    stats.bytes.addAndGet(frame.data.length);
                    stats.writeNs.addAndGet(System.nanoTime() - begin);
                    stats.queueNs.addAndGet(queuedNs);
                    updateMax(stats.maxWriteNs, System.nanoTime() - begin);
                    updateMax(stats.maxQueueNs, queuedNs);
                    stats.frames.incrementAndGet();
                } finally {
                    startedNs.set(0);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (!stopped()) failure.compareAndSet(null, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            cancel();
        }
    }

    private static void cacheFormatConfig(MediaFormat format, OutboundFrameQueue queue) {
        for (String key : new String[]{"csd-0", "csd-1", "csd-2"}) {
            if (!format.containsKey(key)) continue;
            ByteBuffer source = format.getByteBuffer(key);
            if (source == null || !source.hasRemaining()) continue;
            ByteBuffer copy = source.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            queue.offerOwned(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, bytes, System.nanoTime());
        }
    }

    private static void updateMax(AtomicLong value, long sample) {
        long old;
        do {
            old = value.get();
            if (old >= sample) return;
        } while (!value.compareAndSet(old, sample));
    }

    private static final class NetworkStats {
        final AtomicLong frames = new AtomicLong(), bytes = new AtomicLong(), writeNs = new AtomicLong(), queueNs = new AtomicLong();
        final AtomicLong maxWriteNs = new AtomicLong(), maxQueueNs = new AtomicLong();
    }

    private static void setDisplayPriority() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (RuntimeException ignored) {
        }
    }

    private static void joinBounded(Thread thread) {
        if (thread != null) try {
            thread.join(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void requestKeyFrame(MediaCodec codec) {
        Bundle parameters = new Bundle();
        parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try {
            codec.setParameters(parameters);
        } catch (RuntimeException ignored) {
        }
    }
}
