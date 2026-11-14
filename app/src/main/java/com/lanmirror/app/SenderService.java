package com.lanmirror.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** The encoder drains independently of TCP. Both queues and recovery state are bounded. */
public final class SenderService extends Service {
    public static final String STOP = "com.lanmirror.app.STOP";
    public static volatile String status = "未发送";
    public static volatile boolean running;
    private static SenderService active;
    private volatile boolean stopping;
    private volatile Socket socket;
    private volatile OutboundFrameQueue outbound;
    private volatile MediaProjection projection;
    volatile long requestedSize;
    private volatile SmoothSender smoothSender;
    boolean smoothStopping() { return stopping; }
    private Thread worker;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { requestStop(); }
        @Override public void onCapturedContentResize(int width, int height) {
            if (width > 0 && height > 0) requestedSize = ((long)width << 32) | (height & 0xffffffffL);
        }
    };
    @Override public IBinder onBind(Intent intent) { return null; }
    private void requestStop() {
        stopping = true;
        SmoothSender smooth = smoothSender; if (smooth != null) smooth.cancel();
        OutboundFrameQueue queue = outbound; if (queue != null) queue.close();
        Socket s = socket; if (s != null) try { s.close(); } catch (IOException ignored) { }
        stopSelf();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { requestStop(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        synchronized (SenderService.class) { active = this; running = true; }
        stopping = false;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("casting", "正在镜像投屏", NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getService(this, 1, new Intent(this, SenderService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "casting")
                .setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("局域镜像 · 1080p60 高码率")
                .setContentText("点击停止可随时结束投屏").setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止投屏", pi).build()).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(1, notification);
            Intent consent = intent.getParcelableExtra("consent");
            if (consent == null) throw new IllegalArgumentException("缺少屏幕采集授权");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result", Activity.RESULT_CANCELED), consent);
            projection.registerCallback(projectionCallback, handler);
            String host = intent.getStringExtra("host"), code = intent.getStringExtra("code");
            int profile = StreamProfile.sanitize(intent.getIntExtra("profile", StreamProfile.DEFAULT));
            boolean directTransport = intent.getBooleanExtra("directTransport", false);
            boolean burstEncoder = intent.getBooleanExtra("burstEncoder", false);
            int selectedPipeline = intent.getIntExtra("pipeline", 2);
            final int pipeline = selectedPipeline < 0 || selectedPipeline > 4 ? 3 : selectedPipeline;
            int offeredPort = intent.getIntExtra("port", ApprovalProtocol.PORT);
            final int targetPort = offeredPort > 0 && offeredPort <= 65535 ? offeredPort : ApprovalProtocol.PORT;
            worker = new Thread(() -> {
                if (pipeline == 0) { stream(host, code, profile, directTransport, burstEncoder); return; }
                SmoothSender smooth = new SmoothSender(this, projection, handler, host, code, profile, pipeline == 2 || pipeline == 3, pipeline >= 3, targetPort, intent.getBooleanExtra("audio", false));
                smoothSender = smooth;
                try { smooth.run(); } finally { if (smoothSender == smooth) smoothSender = null; stopSelf(); }
            }, "mirror-encoder"); worker.start();
        } catch (Exception e) { publish("启动失败：" + message(e)); requestStop(); }
        return START_NOT_STICKY;
    }
    private void stream(String host, String code, int profile, boolean directTransport, boolean burstEncoder) {
        setDisplayPriority();
        Encoder session = null; VirtualDisplay display = null;
        Socket ownSocket = new Socket(); Thread control = null, writer = null, watchdog = null;
        final OutboundFrameQueue queue = new OutboundFrameQueue(3, 2 * 1024 * 1024);
        final NetworkStats stats = new NetworkStats();
        final AtomicLong writeStartedNs = new AtomicLong();
        final AtomicLong feedbackRequests = new AtomicLong();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            socket = ownSocket; outbound = queue; if (stopping) return;
            publish("连接接收端… · 建议两端更新 1.5.0");
            ownSocket.setTcpNoDelay(true); ownSocket.setSendBufferSize(64 * 1024); ownSocket.setReceiveBufferSize(64 * 1024);
            ownSocket.connect(new InetSocketAddress(host, 7236), 10000); ownSocket.setSoTimeout(10000);
            final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(ownSocket.getOutputStream(), 16 * 1024));
            final DataInputStream in = new DataInputStream(new BufferedInputStream(ownSocket.getInputStream(), 1024));
            if (!Wire.clientHandshake(in, out, code)) throw new IOException("配对码不正确或协议版本不一致");
            Wire.ClockSync clock = Wire.clientClockSync(in, out); ownSocket.setSoTimeout(0);
            control = new Thread(() -> {
                try {
                    while (!stopping && !ownSocket.isClosed()) {
                        Wire.Packet packet = Wire.readPacket(in);
                        if (packet.type == Wire.REQUEST_SYNC) { feedbackRequests.incrementAndGet(); queue.requestRecovery(); }
                        else if (packet.type == Wire.END) break;
                        else throw new IOException("Unexpected receiver control packet");
                    }
                } catch (IOException e) { if (!stopping && !ownSocket.isClosed()) failure.compareAndSet(null, e); }
                finally { queue.close(); try { ownSocket.close(); } catch (IOException ignored) { } }
            }, "mirror-control"); control.start();
            DisplayMetrics metrics = new DisplayMetrics();
            getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
            long captureSize = requestedSize;
            int sourceW = captureSize != 0 ? (int)(captureSize >>> 32) : metrics.widthPixels;
            int sourceH = captureSize != 0 ? (int)captureSize : metrics.heightPixels;
            int[] size = StreamProfile.sizeFor(sourceW, sourceH, profile);
            session = createEncoder(size[0], size[1], StreamProfile.bitrate(profile));
            queue.beginGeneration(size[0], size[1]);
            writer = new Thread(() -> writeFrames(ownSocket, out, queue, stats, writeStartedNs, failure, directTransport), "mirror-network-writer"); writer.start();
            display = projection.createVirtualDisplay("LanMirror", size[0], size[1], metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, session.surface, null, handler);
            watchdog = new Thread(() -> {
                while (!stopping && !ownSocket.isClosed()) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                    long started = writeStartedNs.get();
                    // Only the actual writer controls this deadline; encoder progress cannot hide a stalled write.
                    if (started != 0 && System.nanoTime() - started > 2_000_000_000L) {
                        failure.compareAndSet(null, new IOException("网络写入阻塞超过2秒"));
                        queue.close(); try { ownSocket.close(); } catch (IOException ignored) { } return;
                    }
                }
            }, "mirror-write-watchdog"); watchdog.setDaemon(true); watchdog.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long nextLegacySizePoll = 0, lastSyncRequest = 0, reportAt = System.nanoTime();
            long encoded = 0, previousSent = 0, previousBytes = 0, previousWriteTotal = 0, previousQueueTotal = 0;
            boolean pendingSync = true;
            long burstGroups = 0; int maxBurst = 0;
            publish("正在投屏 " + size[0] + " × " + size[1] + " @60 · " + StreamProfile.bitrate(profile) / 1000000 + "Mbps\n编码/网络线程解耦 · 稳60与70ms需真机确认");
            while (!stopping && !ownSocket.isClosed() && !queue.isClosed()) {
                if (Build.VERSION.SDK_INT < 34 && SystemClock.elapsedRealtime() >= nextLegacySizePoll) {
                    getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
                    requestedSize = ((long)metrics.widthPixels << 32) | (metrics.heightPixels & 0xffffffffL);
                    nextLegacySizePoll = SystemClock.elapsedRealtime() + 500;
                }
                long resize = requestedSize; int rw = (int)(resize >>> 32), rh = (int)resize;
                if (rw > 0 && rh > 0 && (rw != sourceW || rh != sourceH)) {
                    sourceW = rw; sourceH = rh;
                    int[] newSize = StreamProfile.sizeFor(rw, rh, profile);
                    if (newSize[0] != size[0] || newSize[1] != size[1]) {
                        display.setSurface(null); session.close(); session = null;
                        size = newSize; session = createEncoder(size[0], size[1], StreamProfile.bitrate(profile));
                        queue.beginGeneration(size[0], size[1]);
                        display.resize(size[0], size[1], metrics.densityDpi); display.setSurface(session.surface);
                        pendingSync = true; lastSyncRequest = 0;
                    }
                }
                long now = System.nanoTime();
                if (queue.consumeKeyframeRequest()) pendingSync = true;
                if (!queue.isAwaitingKeyFrame()) pendingSync = false; // A usable recovery key is already queued/in-flight.
                if (pendingSync && now - lastSyncRequest >= 300_000_000L) {
                    requestKeyFrame(session.codec); lastSyncRequest = now; pendingSync = false;
                }
                // Retry only if no recovery IDR is already queued/in-flight; do not continually reset it.
                if (queue.isAwaitingKeyFrame() && !queue.hasRecoveryInFlight() && now - lastSyncRequest >= 300_000_000L) pendingSync = true;
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
                            data.position(info.offset); data.limit(info.offset + info.size);
                            byte[] bytes = new byte[info.size]; data.get(bytes);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) encoded++;
                            // Ownership moves to the queue, so no second frame copy or synchronous network write occurs here.
                            queue.offerOwned(info.presentationTimeUs, info.flags, bytes, encodedAtNs);
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) throw new IOException("硬件编码输出已结束");
                    } finally { session.codec.releaseOutputBuffer(index, false); }
                }
                if (index < 0 && index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break;
                drainedThisPass++;
                } while (!stopping && !ownSocket.isClosed() && !queue.isClosed()
                    && EncoderDrainPolicy.continueBurst(burstEncoder, drainedThisPass, drainStartedNs, System.nanoTime()));
                if (drainedThisPass > 1) burstGroups++;
                maxBurst = Math.max(maxBurst, drainedThisPass);
                now = System.nanoTime();
                if (now - reportAt >= 1_000_000_000L) {
                    double seconds = (now - reportAt) / 1_000_000_000.0;
                    long sent = stats.frames.get(), dataBytes = stats.bytes.get(), writeTotal = stats.writeNs.get(), queueTotal = stats.queueNs.get();
                    long sentDelta = sent - previousSent;
                    publish(String.format(Locale.US,
                        "正在投屏 %d × %d @60 · 目标%d Mbps\n编码输出 %.1f fps / 网络发送 %.1f fps · 实际 %.1f Mbps\n排队平均/最大 %.1f/%.1f ms · 写入平均/最大 %.1f/%.1f ms\n待发 %d/3帧 · 累计丢帧%d / GOP恢复%d / 对端请求%d\nRTT %.1f ms · TCP实际发送缓冲%d KB\n编码器 %s · %s\n稳60和70ms端到端需要真机测量",
                        size[0], size[1], StreamProfile.bitrate(profile) / 1000000,
                        encoded / seconds, sentDelta / seconds, (dataBytes - previousBytes) * 8.0 / seconds / 1000000,
                        sentDelta == 0 ? 0 : (queueTotal - previousQueueTotal) / sentDelta / 1000000.0, stats.maxQueueNs.getAndSet(0) / 1000000.0,
                        sentDelta == 0 ? 0 : (writeTotal - previousWriteTotal) / sentDelta / 1000000.0, stats.maxWriteNs.getAndSet(0) / 1000000.0,
                        queue.depth(), queue.droppedFrames(), queue.recoveryCount(), feedbackRequests.get(), clock.roundTripNs / 1000000.0,
                        ownSocket.getSendBufferSize() / 1024, session.name, (session.hints ? "60fps实时/无B帧参数" : "驱动基础实时配置") + "\n传输A=" + (directTransport ? "直接数组" : "原模式") + " / 编码B=" + (burstEncoder ? "有界连续取帧" : "原模式") + "\n本秒连续取帧组/最大=" + burstGroups + "/" + maxBurst + "；应用丢帧不等于网络丢包"));
                    burstGroups = 0; maxBurst = 0; encoded = 0; previousSent = sent; previousBytes = dataBytes; previousWriteTotal = writeTotal; previousQueueTotal = queueTotal; reportAt = now;
                }
            }
            if (!stopping) publish("投屏结束：" + (failure.get() == null ? "接收端连接已关闭" : message(failure.get())));
        } catch (Exception e) { if (!stopping) publish("投屏结束：" + message(e)); }
        finally {
            queue.close(); try { ownSocket.close(); } catch (IOException ignored) { }
            if (watchdog != null) watchdog.interrupt();
            joinBounded(writer); joinBounded(control);
            if (display != null) try { display.release(); } catch (RuntimeException ignored) { }
            if (session != null) session.close();
            if (outbound == queue) outbound = null;
            stopSelf();
        }
    }
    private void writeFrames(Socket connection, DataOutputStream out, OutboundFrameQueue queue,
                             NetworkStats stats, AtomicLong startedNs, AtomicReference<Exception> failure, boolean directTransport) {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE); } catch (RuntimeException ignored) { }
        long sentGeneration = -1;
        FrameStreamWriter frames = new FrameStreamWriter(out, directTransport);
        try {
            while (!stopping && !connection.isClosed() && !queue.isClosed()) {
                OutboundFrameQueue.Frame frame = queue.poll(20);
                if (frame == null) continue;
                long begin = System.nanoTime(); startedNs.set(begin);
                long queuedNs = Math.max(0, begin - frame.encodedAtNs);
                try {
                    if (sentGeneration != frame.generation) {
                        Wire.writeConfig(out, frame.width, frame.height); sentGeneration = frame.generation;
                    }
                    if (frame.startsGop && frame.codecConfig != null) {
                        for (byte[] config : frame.codecConfig) frames.write(config, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, System.nanoTime());
                    }
                    // Timestamp stays at encoder output, not writer start: queue latency is not hidden from receiver metrics.
                    frames.write(frame.data, frame.ptsUs, frame.flags, frame.encodedAtNs);
                    queue.markSent(frame);
                    stats.bytes.addAndGet(frame.data.length); stats.writeNs.addAndGet(System.nanoTime() - begin); stats.queueNs.addAndGet(queuedNs);
                    updateMax(stats.maxWriteNs, System.nanoTime() - begin); updateMax(stats.maxQueueNs, queuedNs); stats.frames.incrementAndGet();
                } finally { startedNs.set(0); }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (!stopping && !connection.isClosed()) failure.compareAndSet(null, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { queue.close(); try { connection.close(); } catch (IOException ignored) { } }
    }
    private static void cacheFormatConfig(MediaFormat format, OutboundFrameQueue queue) {
        for (String key : new String[] {"csd-0", "csd-1", "csd-2"}) {
            if (!format.containsKey(key)) continue;
            ByteBuffer source = format.getByteBuffer(key); if (source == null || !source.hasRemaining()) continue;
            ByteBuffer copy = source.duplicate(); byte[] bytes = new byte[copy.remaining()]; copy.get(bytes);
            queue.offerOwned(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, bytes, System.nanoTime());
        }
    }
    private static void updateMax(AtomicLong value, long sample) {
        long old; do { old = value.get(); if (old >= sample) return; } while (!value.compareAndSet(old, sample));
    }
    private static final class NetworkStats {
        final AtomicLong frames = new AtomicLong(), bytes = new AtomicLong(), writeNs = new AtomicLong(), queueNs = new AtomicLong();
        final AtomicLong maxWriteNs = new AtomicLong(), maxQueueNs = new AtomicLong();
    }
    private static void setDisplayPriority() { try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (RuntimeException ignored) { } }
    private static void joinBounded(Thread thread) {
        if (thread != null) try { thread.join(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static void requestKeyFrame(MediaCodec codec) {
        Bundle parameters = new Bundle(); parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try { codec.setParameters(parameters); } catch (RuntimeException ignored) { }
    }
    private static boolean hardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        String name = info.getName().toLowerCase(Locale.US);
        return !name.contains("google") && !name.contains("android") && !name.contains("ffmpeg");
    }
    Encoder createEncoder(int width, int height, int bitrate) throws IOException {
        String lastFailure = "没有可用硬件编码器";
        for (MediaCodecInfo candidate : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!candidate.isEncoder() || !hardware(candidate)) continue;
            MediaCodecInfo.CodecCapabilities caps;
            try { caps = candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC); }
            catch (IllegalArgumentException unsupported) { continue; }
            if (caps.getVideoCapabilities() == null || !caps.getVideoCapabilities().areSizeAndRateSupported(width, height, StreamProfile.FPS)) continue;
            if (!caps.getVideoCapabilities().getBitrateRange().contains(bitrate)) {
                lastFailure = candidate.getName() + ": 请求" + bitrate / 1000000 + "Mbps，设备声明范围" + caps.getVideoCapabilities().getBitrateRange(); continue;
            }
            int selectedProfile = 0, selectedLevel = 0;
            int[] preferredProfiles = Build.VERSION.SDK_INT >= 29
                ? new int[] {MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh}
                : new int[] {MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline};
            for (int preferred : preferredProfiles) {
                int level = (long)width * height >= 2000000L ? MediaCodecInfo.CodecProfileLevel.AVCLevel42 : MediaCodecInfo.CodecProfileLevel.AVCLevel32;
                if (StreamProfile.needsLevel5(preferred == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, width, height, bitrate)) level = MediaCodecInfo.CodecProfileLevel.AVCLevel5;
                for (MediaCodecInfo.CodecProfileLevel p : caps.profileLevels) {
                    if (p.profile == preferred && p.level >= level) { selectedProfile = preferred; selectedLevel = level; break; }
                }
                if (selectedProfile != 0) break;
            }
            if (selectedProfile == 0) { lastFailure = candidate.getName() + ": no suitable AVC profile/level for requested 60fps/bitrate"; continue; }
            for (int attempt = 0; attempt < 2; attempt++) {
                MediaCodec codec = null; Surface surface = null;
                try {
                    MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, StreamProfile.FPS);
                    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    format.setInteger(MediaFormat.KEY_PROFILE, selectedProfile);
                    format.setInteger(MediaFormat.KEY_LEVEL, selectedLevel);
                    if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    if (caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                    if (attempt == 0) {
                        format.setInteger(MediaFormat.KEY_LATENCY, 0);
                        format.setInteger(MediaFormat.KEY_OPERATING_RATE, StreamProfile.FPS);
                        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 16667L);
                        if (Build.VERSION.SDK_INT >= 29) format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, StreamProfile.FPS);
                    }
                    if (!caps.isFormatSupported(format)) { lastFailure = candidate.getName() + ": full requested 60fps/profile/level/bitrate format not supported"; continue; }
                    codec = MediaCodec.createByCodecName(candidate.getName());
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = codec.createInputSurface(); codec.start();
                    return new Encoder(codec, surface, candidate.getName(), attempt == 0);
                } catch (IOException | RuntimeException e) {
                    lastFailure = candidate.getName() + ": " + message(e);
                    if (surface != null) surface.release();
                    if (codec != null) try { codec.release(); } catch (RuntimeException ignored) { }
                }
            }
        }
        throw new IOException("设备不支持 " + width + "×" + height + " AVC@60 / " + bitrate / 1000000 + "Mbps；未自动降帧/码率。可手动选18/12Mbps或720p60。 " + lastFailure);
    }
    static final class Encoder {
        final MediaCodec codec; final Surface surface; final String name; final boolean hints;
        Encoder(MediaCodec codec, Surface surface, String name, boolean hints) { this.codec = codec; this.surface = surface; this.name = name; this.hints = hints; }
        void close() { try { codec.stop(); } catch (RuntimeException ignored) { } try { codec.release(); } catch (RuntimeException ignored) { } surface.release(); }
    }
    void publish(String message) { synchronized (SenderService.class) { if (active == this) status = message; } }
    static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    @Override public void onDestroy() {
        stopping = true;
        SmoothSender smooth = smoothSender; if (smooth != null) smooth.cancel();
        OutboundFrameQueue queue = outbound; if (queue != null) queue.close();
        Socket s = socket; if (s != null) try { s.close(); } catch (IOException ignored) { }
        MediaProjection p = projection; projection = null;
        if (p != null) { p.unregisterCallback(projectionCallback); p.stop(); }
        synchronized (SenderService.class) {
            if (active == this) {
                if (status.startsWith("正在") || status.startsWith("连接") || status.startsWith("流畅模式")) status = "投屏已停止";
                running = false; active = null;
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
