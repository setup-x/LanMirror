package com.lanmirror.receiver.internal;

import com.lanmirror.core.api.*;
import com.lanmirror.receiver.api.ReceiverSession.Listener;

import android.media.*;
import android.os.Build;
import android.view.Surface;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * v2 receiver: all codec calls belong to one owner; reader owns only framed input.
 */
public final class ReceiverSessionImpl {
    private static final String MIME = "video/avc";
    private final Surface surface;
    private final String code;
    private final int port;
    private final Listener listener;
    private final boolean codecExperiment;
    private final Object lifecycleLock = new Object();
    private Worker current;
    private long generation;

    public ReceiverSessionImpl(Surface surface, String code, int port, Listener listener) {
        this(surface, code, port, listener, false);
    }

    public ReceiverSessionImpl(Surface surface, String code, int port, Listener listener, boolean codecExperiment) {
        if (surface == null || listener == null)
            throw new IllegalArgumentException("Missing surface/listener");
        Wire.requireCode(code);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        this.surface = surface;
        this.code = code;
        this.port = port;
        this.listener = listener;
        this.codecExperiment = codecExperiment;
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (current != null) return;
            Worker worker = new Worker(++generation);
            current = worker;
            try {
                worker.thread.start();
            } catch (RuntimeException | Error e) {
                current = null;
                throw e;
            }
        }
    }

    /**
     * Close sockets immediately; UI wait <=250ms, cleanup stays on the codec owner.
     */
    public void stop() {
        Worker worker;
        synchronized (lifecycleLock) {
            worker = current;
        }
        if (worker == null) return;
        worker.cancel();
        if (Thread.currentThread() != worker.thread) joinForStop(worker.thread);
    }

    private final class Worker implements Runnable {
        final long epoch;
        final Thread thread = new Thread(this, "LanMirror-receiver");
        final Object resourceLock = new Object();
        volatile boolean cancelled;
        ServerSocket server;
        Socket socket;
        long connectionSequence;

        Worker(long epoch) {
            this.epoch = epoch;
        }

        void cancel() {
            cancelled = true;
            synchronized (resourceLock) {
                closeQuietly(socket);
                closeQuietly(server);
            }
            thread.interrupt();
        }

        void report(String text) {
            if (cancelled) return;
            synchronized (lifecycleLock) {
                if (generation != epoch) return;
            }
            try {
                listener.onStatus(text);
            } catch (RuntimeException ignored) {
            }
        }

        void reportSize(int width, int height) {
            if (cancelled) return;
            synchronized (lifecycleLock) {
                if (generation != epoch) return;
            }
            try {
                listener.onVideoSize(width, height);
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void run() {
            try {
                ServerSocket listening = new ServerSocket();
                synchronized (resourceLock) {
                    if (cancelled) {
                        closeQuietly(listening);
                        return;
                    }
                    server = listening;
                }
                listening.setReuseAddress(true);
                listening.bind(new InetSocketAddress(port));
                report("正在监听 TCP " + port + "（协议 v2）");
                while (!cancelled) {
                    Socket accepted = listening.accept();
                    synchronized (resourceLock) {
                        if (cancelled) {
                            closeQuietly(accepted);
                            break;
                        }
                        socket = accepted;
                    }
                    long connectionId = ++connectionSequence;
                    try {
                        try {
                            listener.onConnectionStarted(connectionId);
                        } catch (RuntimeException ignored) {
                        }
                        accepted.setTcpNoDelay(true);
                        accepted.setReceiveBufferSize(64 * 1024); // OS may adjust hint.
                        accepted.setSoTimeout(10000);
                        DataInputStream input = new DataInputStream(new BufferedInputStream(accepted.getInputStream(), 16 * 1024));
                        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(accepted.getOutputStream(), 1024));
                        if (!Wire.serverHandshake(input, output, code)) {
                            report("配对码或协议版本不匹配，已拒绝");
                            continue;
                        }
                        Wire.ClockSync clock = Wire.serverClockSync(input, output);
                        accepted.setSoTimeout(0); // Never retry a partial packet read after timeout.
                        report(String.format(Locale.US, "已连接，时钟校准 RTT %.1f ms；等待配置", clock.roundTripNs / 1e6));
                        receive(connectionId, accepted, input, output, clock);
                        report("发送已结束，继续等待连接");
                    } catch (IOException | RuntimeException e) {
                        report("接收中断：" + describe(e));
                    } finally {
                        synchronized (resourceLock) {
                            closeQuietly(accepted);
                            if (socket == accepted) socket = null;
                        }
                        try {
                            listener.onConnectionEnded(connectionId);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                report("监听失败：" + describe(e));
            } finally {
                cancel();
                synchronized (lifecycleLock) {
                    if (current == this) current = null;
                }
                boolean notifyStopped;
                synchronized (lifecycleLock) {
                    notifyStopped = generation == epoch;
                }
                // Never hold the lifecycle lock across caller code: stop() must stay bounded.
                if (notifyStopped) try {
                    listener.onStatus("接收已停止");
                } catch (RuntimeException ignored) {
                }
            }
        }

        private void receive(long connectionId, Socket accepted, DataInputStream input, DataOutputStream output, Wire.ClockSync clock) throws IOException {
            PacketReader reader = new PacketReader(input);
            DecodeSession decode = new DecodeSession(connectionId, clock, output);
            Wire.Packet pending = null;
            boolean ending = false, eosQueued = false;
            long endDeadline = 0;
            try {
                reader.thread.start();
                while (!cancelled) {
                    if (decode.drainOutput()) break;
                    decode.prepareRecovery();
                    decode.maybeRequestKey();
                    decode.maybeStatistics();
                    if (ending && (decode.decoder == null || System.nanoTime() - endDeadline >= 0))
                        break;
                    if (!ending && pending == null) {
                        try {
                            pending = reader.packets.poll(2, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            if (cancelled) break;
                            throw new IOException("Receiver interrupted", e);
                        }
                        // Read done first: after true no producer can add a last packet.
                        if (pending == null && reader.done && reader.packets.isEmpty()) {
                            if (reader.failure != null && !(reader.failure instanceof EOFException))
                                throw new IOException("Packet reader failed", reader.failure);
                            ending = true;
                            endDeadline = System.nanoTime() + 150_000_000L;
                        }
                    }
                    if (pending != null) {
                        if (pending.type == Wire.CONFIG) {
                            decode.configure(Wire.readConfig(pending.data));
                            pending = null;
                        } else if (pending.type == Wire.VIDEO) {
                            if (decode.consumeVideo(pending.data)) pending = null;
                        } else if (pending.type == Wire.END) {
                            pending = null;
                            ending = true;
                            endDeadline = System.nanoTime() + 150_000_000L;
                        } else
                            throw new IOException("Sender sent unexpected receiver-control packet");
                    }
                    if (ending && decode.decoder != null && !eosQueued) {
                        int index = decode.decoder.dequeueInputBuffer(0);
                        if (index >= 0) {
                            decode.decoder.queueInputBuffer(index, 0, 0, decode.lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosQueued = true;
                        }
                    }
                    if (pending != null || ending) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            if (cancelled) break;
                            throw new IOException("Receiver interrupted", e);
                        }
                    }
                }
            } finally {
                // Close read first, interrupt queue.put second, then join, finally release decoder.
                closeQuietly(accepted);
                reader.thread.interrupt();
                joinUninterruptibly(reader.thread);
                reader.packets.clear();
                decode.closeDecoder();
            }
        }

        private final class DecodeSession {
            final long connectionId;
            final Wire.ClockSync clock;
            final DataOutputStream controls;
            final DropRecovery policy;
            final LatencyStats stats = new LatencyStats(System.nanoTime());
            final FrameTimestampTracker frameTimes = new FrameTimestampTracker(128);
            final ArrayList<byte[]> cachedConfig = new ArrayList<>();
            final ArrayDeque<byte[]> configToQueue = new ArrayDeque<>();
            final MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            MediaCodec decoder;
            int width, height, configBytes;
            long lastPts, nextStatsNs = System.nanoTime() + 1_000_000_000L;
            long minimumPacketAgeNs = Long.MAX_VALUE, minimumOutputAgeNs = Long.MAX_VALUE;
            long preparedRecoveryEpoch = -1, recordedRecoveryCount;
            long setupLastNs, setupMaxNs, setupTotalNs, setupCalls, decoderStarts, flushCalls, inputSlotMisses;
            boolean configuredCsd, firstFrameReported;
            String bootstrapMode = codecExperiment ? "ON (pending CSD)" : "OFF (baseline)";
            String decoderMode = "等待关键帧";

            DecodeSession(long connectionId, Wire.ClockSync clock, DataOutputStream controls) {
                this.connectionId = connectionId;
                this.clock = clock;
                this.controls = controls;
                policy = new DropRecovery(clock.roundTripNs / 2 + 40_000_000L);
            }

            void configure(Wire.Config configuration) {
                // Identical configuration and identical CSD do not require another decoder startup.
                if (width == configuration.width && height == configuration.height && width > 0)
                    return;
                closeDecoder();
                width = configuration.width;
                height = configuration.height;
                cachedConfig.clear();
                configBytes = 0;
                policy.requireKey();
                lastPts = 0;
                reportSize(width, height);
                report("等待新关键帧 " + width + "×" + height + "；基础恢复阈值90ms，慢链路可显式放宽（非70ms达标）");
            }

            void maybeRequestKey() throws IOException {
                if (!cancelled && width > 0 && policy.shouldRequestSync(System.nanoTime()))
                    Wire.writeSyncRequest(controls);
            }

            /**
             * Warm synchronous decoder flush is bounded by platform; CSD is explicitly replayed before next IDR.
             */
            void prepareRecovery() {
                while (recordedRecoveryCount < policy.recoveryCount()) {
                    stats.recordRecovery();
                    recordedRecoveryCount++;
                }
                if (!policy.isWaitingForKey() || preparedRecoveryEpoch == policy.recoveryEpoch())
                    return;
                if (decoder != null) {
                    try {
                        flushCalls++;
                        decoder.flush();
                    } catch (RuntimeException e) {
                        closeDecoder();
                    }
                }
                frameTimes.clear();
                configToQueue.clear();
                preparedRecoveryEpoch = policy.recoveryEpoch();
            }

            boolean consumeVideo(byte[] payload) throws IOException {
                if (width == 0) throw new IOException("VIDEO before CONFIG");
                ByteBuffer frame = ByteBuffer.wrap(payload);
                long pts = frame.getLong();
                int flags = frame.getInt();
                long sentNs = frame.getLong();
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    byte[] bytes = new byte[frame.remaining()];
                    frame.get(bytes);
                    boolean duplicate = false;
                    for (byte[] old : cachedConfig)
                        if (Arrays.equals(old, bytes)) {
                            duplicate = true;
                            break;
                        }
                    if (!duplicate && bytes.length > 0) {
                        if (bytes.length > 512 * 1024 || cachedConfig.size() >= 8 || configBytes + bytes.length > 512 * 1024)
                            throw new IOException("AVC codec configuration exceeds receiver bound");
                        cachedConfig.add(bytes);
                        configBytes += bytes.length;
                        policy.requireKey();
                        closeDecoder(); // Changed parameter sets require new decoder + fresh key.
                    }
                    return true;
                }
                boolean key = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                long packetAge = clock.ageNs(sentNs, System.nanoTime());
                minimumPacketAgeNs = Math.min(minimumPacketAgeNs, packetAge);
                DropRecovery.Decision decision = policy.classify(key, packetAge);
                if (decision == DropRecovery.Decision.DROP) {
                    stats.recordDrop();
                    prepareRecovery();
                    maybeRequestKey();
                    return true;
                }
                if (decision == DropRecovery.Decision.RECOVER_AT_KEY) {
                    if (cachedConfig.isEmpty()) {
                        policy.requireKey();
                        stats.recordDrop();
                        prepareRecovery();
                        maybeRequestKey();
                        return true;
                    }
                    boolean freshStart = decoder == null;
                    if (freshStart) openDecoder();
                    if (CodecBootstrapPolicy.requiresExplicitSubmission(configuredCsd, freshStart))
                        configToQueue.addAll(cachedConfig);
                }
                if (decoder == null) {
                    policy.requireKey();
                    stats.recordDrop();
                    prepareRecovery();
                    maybeRequestKey();
                    return true;
                }
                if (!queueCachedConfiguration()) return false;
                // Codec opening/config submission may take time; check age again immediately before feed.
                if (clock.ageNs(sentNs, System.nanoTime()) > policy.budgetNs()) {
                    policy.requireKey();
                    stats.recordDrop();
                    prepareRecovery();
                    maybeRequestKey();
                    return true;
                }
                int index = decoder.dequeueInputBuffer(0);
                if (index < 0) {
                    inputSlotMisses++;
                    return false;
                }
                ByteBuffer target = decoder.getInputBuffer(index);
                if (target == null || target.capacity() < frame.remaining())
                    throw new IOException("Encoded frame exceeds decoder buffer");
                target.clear();
                int size = frame.remaining();
                target.put(frame);
                frameTimes.add(pts, sentNs);
                decoder.queueInputBuffer(index, 0, size, pts, flags);
                lastPts = pts;
                return true;
            }

            boolean queueCachedConfiguration() throws IOException {
                while (!configToQueue.isEmpty()) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index < 0) {
                        inputSlotMisses++;
                        return false;
                    }
                    byte[] config = configToQueue.removeFirst();
                    ByteBuffer input = decoder.getInputBuffer(index);
                    if (input == null || input.capacity() < config.length)
                        throw new IOException("AVC configuration exceeds decoder buffer");
                    input.clear();
                    input.put(config);
                    decoder.queueInputBuffer(index, 0, config.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                }
                return true;
            }

            void openDecoder() throws IOException {
                long started = System.nanoTime();
                setupCalls++;
                CodecBootstrapPolicy.Configuration bootstrap = CodecBootstrapPolicy.select(codecExperiment, cachedConfig);
                DecoderSelection selected;
                configuredCsd = false;
                try {
                    if (bootstrap.usable()) {
                        try {
                            selected = createDecoder(width, height, bootstrap);
                            configuredCsd = true;
                            bootstrapMode = "ON (configure SPS/PPS accepted; not performance proof)";
                        } catch (IOException rejected) {
                            selected = createDecoder(width, height, null);
                            bootstrapMode = "ON -> legacy fallback: configure CSD rejected";
                        }
                    } else {
                        selected = createDecoder(width, height, null);
                        bootstrapMode = codecExperiment ? "ON -> legacy: " + bootstrap.reason : "OFF (baseline)";
                    }
                } finally {
                    setupLastNs = System.nanoTime() - started;
                    setupTotalNs += setupLastNs;
                    setupMaxNs = Math.max(setupMaxNs, setupLastNs);
                }
                decoderStarts++;
                decoder = selected.codec;
                decoderMode = selected.description;
                report("正在解码 " + width + "×" + height + " · " + decoderMode);
            }

            void closeDecoder() {
                MediaCodec old = decoder;
                decoder = null;
                configuredCsd = false;
                frameTimes.clear();
                configToQueue.clear();
                preparedRecoveryEpoch = -1;
                if (old != null) releaseDecoder(old);
            }

            boolean drainOutput() {
                if (decoder == null) return false;
                for (int drained = 0; drained < 32 && !cancelled; drained++) {
                    int index = decoder.dequeueOutputBuffer(outputInfo, 0);
                    if (index >= 0) {
                        boolean eos = (outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean video = outputInfo.size > 0 && (outputInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                        boolean render = false;
                        long age = 0;
                        Long sentStamp = null;
                        if (video) {
                            stats.recordDecodedOutput();
                            sentStamp = frameTimes.remove(outputInfo.presentationTimeUs);
                            age = sentStamp == null ? Long.MAX_VALUE : clock.ageNs(sentStamp, System.nanoTime());
                            if (sentStamp != null)
                                minimumOutputAgeNs = Math.min(minimumOutputAgeNs, age);
                            render = policy.shouldRenderOutput(sentStamp != null, age);
                            // Not presenting an already decoded picture cannot poison codec references.
                            // Keep later timely output; encoded input loss alone requests GOP recovery.
                            if (!render) stats.recordDrop();
                        }
                        // Never use sender PTS as local nanoTime render deadline.
                        decoder.releaseOutputBuffer(index, render);
                        if (render) {
                            long submittedNs = System.nanoTime();
                            long submittedAge = clock.ageNs(sentStamp, submittedNs);
                            // It has really been submitted, even if native release took us over budget.
                            stats.recordRendered(submittedAge, submittedNs);
                            if (!firstFrameReported) {
                                firstFrameReported = true;
                                if (!cancelled) {
                                    boolean live;
                                    synchronized (lifecycleLock) {
                                        live = current == Worker.this && generation == epoch;
                                    }
                                    if (live) try {
                                        listener.onFirstFrame(connectionId);
                                    } catch (RuntimeException ignored) {
                                    }
                                }
                            }
                        }
                        if (eos) return true;
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat format = decoder.getOutputFormat();
                        int w = format.getInteger(MediaFormat.KEY_WIDTH), h = format.getInteger(MediaFormat.KEY_HEIGHT);
                        if (format.containsKey("crop-left") && format.containsKey("crop-right"))
                            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
                        if (format.containsKey("crop-top") && format.containsKey("crop-bottom"))
                            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
                        reportSize(w, h);
                    } else if (index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return false;
                }
                return false;
            }

            void maybeStatistics() {
                long now = System.nanoTime();
                if (now - nextStatsNs < 0) return;
                LatencyStats.Snapshot snapshot = stats.snapshotAndReset(now);
                nextStatsNs = now + 1_000_000_000L;
                // If even the fastest observed packet is old, distinguish slow-link floor from queued backlog.
                // Explicitly degrade the recovery budget instead of permanently blacking out on >90ms links.
                long ageFloor = minimumOutputAgeNs != Long.MAX_VALUE ? minimumOutputAgeNs : minimumPacketAgeNs;
                if (snapshot.renderedSamples == 0 && ageFloor > policy.budgetNs() && ageFloor < 2_000_000_000L)
                    policy.setBudgetNs(ageFloor + 30_000_000L);
                minimumPacketAgeNs = minimumOutputAgeNs = Long.MAX_VALUE;
                String p50 = snapshot.renderedSamples == 0 ? "--" : String.format(Locale.US, "%.1f", snapshot.p50Ns / 1e6);
                String p95 = snapshot.renderedSamples == 0 ? "--" : String.format(Locale.US, "%.1f", snapshot.p95Ns / 1e6);
                FrameCadenceStats.Snapshot cadence = snapshot.cadence;
                String interval50 = cadence.intervals == 0 ? "--" : String.format(Locale.US, "%.1f", cadence.p50Ns / 1e6);
                String interval95 = cadence.intervals == 0 ? "--" : String.format(Locale.US, "%.1f", cadence.p95Ns / 1e6);
                report(String.format(Locale.US,
                        "%d×%d · 实际提交 %.1f fps / 解码输出 %.1f fps\n提交间隔（近%d个，代码节奏非面板刷新）：p50 %s / p95 %s ms\n>33.4ms间隔 %d（累计%d） · GOP恢复 %d\n出帧→渲染提交（时钟校准估算，非端到端）：p50 %s / p95 %s ms\n校准RTT %.1f ms · 累计丢弃 %d · %s%s",
                        width, height, snapshot.renderedFps, snapshot.decodedFps, cadence.intervals, interval50, interval95,
                        cadence.longIntervalsWindow, cadence.longIntervalsTotal, snapshot.recoveryTotal,
                        p50, p95, clock.roundTripNs / 1e6, snapshot.droppedTotal, decoderMode + String.format(Locale.US, "\nCodec experiment: %s | setup last/max/total %.1f/%.1f/%.1f ms, attempts %d, starts/rebuilds %d/%d, flush %d, input-slot misses %d (application events, not network loss)", bootstrapMode, setupLastNs / 1e6, setupMaxNs / 1e6, setupTotalNs / 1e6, setupCalls, decoderStarts, Math.max(0L, decoderStarts - 1), flushCalls, inputSlotMisses),
                        (policy.isWaitingForKey() ? " · 等待新IDR" : "") + String.format(Locale.US, " · 恢复阈值%.0fms%s", policy.budgetNs() / 1e6, policy.budgetNs() > DropRecovery.BACKLOG_BUDGET_NS ? "（慢链路降级，非70ms达标）" : "（非端到端目标）")));
            }
        }

        private final class PacketReader implements Runnable {
            final ArrayBlockingQueue<Wire.Packet> packets = new ArrayBlockingQueue<>(2);
            final DataInputStream input;
            final Thread thread = new Thread(this, "LanMirror-packet-reader");
            volatile boolean done;
            Throwable failure;

            PacketReader(DataInputStream input) {
                this.input = input;
            }

            @Override
            public void run() {
                try {
                    while (!cancelled && !Thread.currentThread().isInterrupted()) {
                        Wire.Packet packet = Wire.readPacket(input);
                        packets.put(packet);
                        if (packet.type == Wire.END) break;
                    }
                } catch (IOException | RuntimeException | InterruptedException e) {
                    failure = e;
                } finally {
                    done = true;
                }
            }
        }
    }

    /**
     * Hardware and declared 60fps support preferred; unsupported low-latency features never enabled.
     */
    private DecoderSelection createDecoder(int width, int height, CodecBootstrapPolicy.Configuration bootstrap) throws IOException {
        ArrayList<CodecCandidate> candidates = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!MIME.equalsIgnoreCase(type)) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                    if (video == null || !video.isSizeSupported(width, height)) continue;
                    candidates.add(new CodecCandidate(info.getName(), isHardware(info), video.areSizeAndRateSupported(width, height, 60),
                            Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
                            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)));
                } catch (RuntimeException ignored) {
                }
                break;
            }
        }
        Collections.sort(candidates, (a, b) -> Integer.compare(b.score(), a.score()));
        IOException failure = null;
        for (CodecCandidate candidate : candidates) {
            int attempts = candidate.lowLatency || candidate.adaptive ? 2 : 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                boolean optimized = attempt == 0;
                MediaCodec codec = null;
                try {
                    codec = MediaCodec.createByCodecName(candidate.name);
                    MediaFormat format = baseFormat(width, height, bootstrap);
                    if (optimized && candidate.lowLatency && Build.VERSION.SDK_INT >= 30) {
                        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                        format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency, true);
                    }
                    if (optimized && candidate.adaptive)
                        format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback, true);
                    codec.configure(format, surface, null, 0);
                    codec.start();
                    boolean lowReported = false;
                    if (optimized && candidate.lowLatency && Build.VERSION.SDK_INT >= 30) {
                        MediaFormat actual = codec.getInputFormat();
                        lowReported = actual.containsKey(MediaFormat.KEY_LOW_LATENCY) && actual.getInteger(MediaFormat.KEY_LOW_LATENCY) == 1;
                    }
                    String description = (candidate.hardware ? "硬件" : "警告：软件") + "解码 " + candidate.name
                            + (candidate.supports60 ? "（声明支持60fps）" : "（警告：未声明60fps能力）")
                            + (lowReported ? " · 低延迟配置报告为1（未真机验证）" : optimized && candidate.lowLatency
                            ? " · 已请求低延迟，但codec未回报确认" : " · 普通模式，未启用低延迟特性")
                            + (optimized && candidate.adaptive ? " · 请求adaptive" : "");
                    return new DecoderSelection(codec, description);
                } catch (IOException | RuntimeException e) {
                    if (codec != null) releaseDecoder(codec);
                    failure = new IOException("Decoder " + candidate.name + " failed: " + describe(e), e);
                }
            }
        }
        MediaCodec fallback = null;
        try {
            fallback = MediaCodec.createDecoderByType(MIME);
            fallback.configure(baseFormat(width, height, bootstrap), surface, null, 0);
            fallback.start();
            return new DecoderSelection(fallback, "警告：默认兼容解码，60fps/低延迟能力未确认");
        } catch (IOException | RuntimeException e) {
            if (fallback != null) releaseDecoder(fallback);
            throw new IOException("No usable AVC decoder" + (failure == null ? "" : ": " + failure.getMessage()), e);
        }
    }

    private static MediaFormat baseFormat(int width, int height, CodecBootstrapPolicy.Configuration bootstrap) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Wire.MAX_PACKET - Wire.VIDEO_HEADER_SIZE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (bootstrap != null && bootstrap.usable()) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(bootstrap.sps).asReadOnlyBuffer());
            format.setByteBuffer("csd-1", ByteBuffer.wrap(bootstrap.pps).asReadOnlyBuffer());
        }
        return format;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        String name = info.getName().toLowerCase(Locale.US);
        return !name.startsWith("omx.google.") && !name.startsWith("c2.google.") && !name.startsWith("c2.android.") && !name.startsWith("omx.android.");
    }

    private static final class CodecCandidate {
        final String name;
        final boolean hardware, supports60, lowLatency, adaptive;

        CodecCandidate(String name, boolean hw, boolean fps60, boolean low, boolean adaptive) {
            this.name = name;
            hardware = hw;
            supports60 = fps60;
            lowLatency = low;
            this.adaptive = adaptive;
        }

        int score() {
            return (hardware ? 100 : 0) + (supports60 ? 20 : 0) + (lowLatency ? 10 : 0);
        }
    }

    private static final class DecoderSelection {
        final MediaCodec codec;
        final String description;

        DecoderSelection(MediaCodec codec, String description) {
            this.codec = codec;
            this.description = description;
        }
    }

    private static void releaseDecoder(MediaCodec codec) {
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        } finally {
            try {
                codec.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void joinForStop(Thread thread) {
        long deadline = System.nanoTime() + 250_000_000L;
        boolean interrupted = false;
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void joinUninterruptibly(Thread thread) {
        boolean interrupted = false;
        while (thread.isAlive()) try {
            thread.join();
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static String describe(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
