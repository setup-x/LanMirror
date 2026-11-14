package com.lanmirror.audio.internal;

import android.media.*;
import android.media.projection.MediaProjection;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * API29+ playback capture only. Does NOT request permission, use MIC, or stop the shared projection.
 */
final class InternalAudioCapture implements AutoCloseable {
    public interface Listener {
        void onFrame(PcmAudioFrame frame);

        void onStatus(String text);

        void onError(String text);
    }

    private static final PcmNativeLease NATIVE_LEASE = new PcmNativeLease();
    private final MediaProjection projection;
    private volatile Listener listener;
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private volatile Thread owner;

    public InternalAudioCapture(MediaProjection projection, Listener listener) {
        if (projection == null || listener == null)
            throw new IllegalArgumentException("Projection/listener required");
        this.projection = projection;
        this.listener = listener;
    }

    public void start() {
        synchronized (started) {
            if (closed.get() || !started.compareAndSet(false, true)) return;
            Thread t = new Thread(this::run, "internal-pcm-capture");
            t.setDaemon(true);
            owner = t;
            t.start();
        }
    }

    void awaitStopped() {
        Thread t;
        synchronized (started) {
            t = owner;
        }
        boolean interrupted = false;
        if (t != null && t != Thread.currentThread()) while (t.isAlive()) try {
            t.join();
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * No UI native operation or join. A stuck vendor release retains its native lease.
     */
    @Override
    public void close() {
        closed.set(true);
        listener = null;
        Thread t = owner;
        if (t != null) {
            t.interrupt();
            LockSupport.unpark(t);
        }
    }

    private void run() {
        AudioRecord record = null;
        PcmNativeLease.Ticket lease = null;
        try {
            if (closed.get()) return;
            if (Build.VERSION.SDK_INT < 29)
                throw new IllegalStateException("内部音频捕获需要Android10/API29；不回退麦克风");
            if ((lease = NATIVE_LEASE.tryAcquire()) == null)
                throw new IllegalStateException("旧内部音频采集资源仍在释放，暂不能重建；视频不受此错误主动停止");
            int min = AudioRecord.getMinBufferSize(PcmAudioFrame.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0 || min > PcmAudioQueue.MAX_BYTES)
                throw new IllegalStateException("设备不支持有界48k双声道PCM内录，minBuffer=" + min);
            int buffer = Math.max(min, 3 * PcmAudioFrame.MAX_BYTES);
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(PcmAudioFrame.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            try {
                record = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture).setAudioFormat(format).setBufferSizeInBytes(buffer).build();
            } catch (SecurityException denied) {
                error("内部声音未获RECORD_AUDIO授权或授权已撤销；视频仍可继续。");
                return;
            }
            if (record.getState() != AudioRecord.STATE_INITIALIZED || record.getBufferSizeInFrames() > PcmAudioQueue.MAX_BYTES / 4)
                throw new IllegalStateException("内录初始化失败或系统缓冲超过120ms限制");
            if (closed.get()) return;
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("内部音频未进入录制状态");
            status("仅内部媒体音频：PCM16/48kHz/双声道；应用策略、工作资料或本来静音均可能无声，绝不启用MIC");
            byte[] block = new byte[PcmAudioFrame.MAX_BYTES];
            int used = 0;
            long bytesRead = 0, blockStart = 0, firstRead = 0, sequence = 0, lastProgress = System.nanoTime(), lastNotice = lastProgress;
            boolean estimateNotice = false;
            AudioTimestamp stamp = new AudioTimestamp();
            while (!closed.get()) {
                int read = record.read(block, used, block.length - used, AudioRecord.READ_NON_BLOCKING);
                long now = System.nanoTime();
                if (read < 0)
                    throw new IllegalStateException("内部音频读取失败 code=" + read + "；不回退MIC");
                if (read > block.length - used)
                    throw new IllegalStateException("内部音频返回长度越界");
                if (read > 0) {
                    if (used == 0) {
                        firstRead = now;
                        blockStart = bytesRead / 4;
                    }
                    used += read;
                    bytesRead += read;
                    lastProgress = now;
                }
                if (used == block.length) {
                    long capturedAt = now - PcmAudioFrame.FRAME_NS;
                    boolean mapped = false;
                    int timestampResult;
                    try {
                        timestampResult = record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC);
                    } catch (RuntimeException unavailable) {
                        timestampResult = AudioRecord.ERROR_INVALID_OPERATION;
                    }
                    long consumed = bytesRead / 4, positionGap = stamp.framePosition - consumed;
                    // A large position disagreement can indicate overrun/reset; do not pretend it is precise.
                    if (timestampResult == AudioRecord.SUCCESS && stamp.framePosition >= 0 && positionGap >= -1920 && positionGap <= record.getBufferSizeInFrames() * 2L) {
                        double delta = (blockStart - (double) stamp.framePosition) * 1_000_000_000.0 / PcmAudioFrame.SAMPLE_RATE;
                        if (delta >= -1_000_000_000.0 && delta <= 1_000_000_000.0) {
                            capturedAt = stamp.nanoTime + (long) delta;
                            mapped = true;
                        }
                    }
                    if (!mapped && !estimateNotice) {
                        estimateNotice = true;
                        status("内录时间戳暂不可可靠映射：用采集返回时刻减20ms估算，不是精确采集点；无精确唇同步保证");
                    }
                    if (!closed.get() && !PcmAudioQueue.tooOld(capturedAt, now) && now - firstRead <= PcmAudioQueue.MAX_QUEUED_NS) {
                        PcmAudioFrame frame = new PcmAudioFrame(sequence++, capturedAt, block, used);
                        Listener target = listener;
                        if (target != null && !closed.get()) target.onFrame(frame);
                    }
                    used = 0;
                    firstRead = 0;
                } else if (used > 0 && now - firstRead > PcmAudioQueue.MAX_QUEUED_NS) {
                    // Drop complete old sampleFrames; retain at most3 bytes to keep the next sample aligned.
                    int tail = used % 4;
                    if (tail > 0) System.arraycopy(block, used - tail, block, 0, tail);
                    used = tail;
                    firstRead = now;
                    blockStart = (bytesRead - tail) / 4;
                }
                if (read == 0) {
                    if (now - lastProgress > 1_000_000_000L && now - lastNotice > 5_000_000_000L) {
                        lastNotice = now;
                        status("尚未读到内部音频；可能未播放可捕获内容或受系统策略限制，不能据此断言音源不允许");
                    }
                    Thread.interrupted();
                    LockSupport.parkNanos(3_000_000L);
                }
            }
        } catch (RuntimeException e) {
            if (!closed.get())
                error("内部音频停止：" + message(e) + "。请检查RECORD_AUDIO授权及音源捕获策略；视频仍可继续。");
        } finally {
            closed.set(true);
            listener = null;
            boolean released = record == null;
            try {
                if (record != null) {
                    try {
                        if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
                            record.stop();
                    } catch (RuntimeException ignored) {
                    } finally {
                        record.release();
                        released = true;
                    }
                }
            } catch (RuntimeException ignored) {
            } finally {
                if (lease != null) lease.releaseAfterNative(released);
            }
        }
    }

    private void status(String text) {
        Listener l = listener;
        if (l != null && !closed.get()) try {
            l.onStatus(text);
        } catch (RuntimeException ignored) {
        }
    }

    private void error(String text) {
        Listener l = listener;
        if (l != null && !closed.get()) try {
            l.onError(text);
        } catch (RuntimeException ignored) {
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
