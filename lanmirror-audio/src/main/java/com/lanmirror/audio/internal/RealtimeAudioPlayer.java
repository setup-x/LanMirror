package com.lanmirror.audio.internal;

import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ArrayBlockingQueue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * One shared selected-source AudioTrack. Never controls a video session. Worker starts in constructor.
 */
final class RealtimeAudioPlayer implements AutoCloseable {
    public interface Listener {
        void onStatus(long sourceId, String text);

        void onError(long sourceId, String text);
    }

    private static final PcmNativeLease NATIVE_LEASE = new PcmNativeLease();
    private final Context appContext;
    private volatile Listener listener;
    private volatile String terminalFailure;

    public String failureReason() {
        return terminalFailure;
    }

    private final PcmAudioQueue queue = new PcmAudioQueue();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayBlockingQueue<FocusEvent> focusEvents = new ArrayBlockingQueue<>(16);
    private final Thread owner;

    public RealtimeAudioPlayer(Context appContext, Listener listener) {
        if (appContext == null || listener == null)
            throw new IllegalArgumentException("Context/listener required");
        Context application = appContext.getApplicationContext();
        if (application == null && appContext instanceof android.app.Activity)
            throw new IllegalArgumentException("Application context required");
        this.appContext = application == null ? appContext : application;
        this.listener = listener;
        owner = new Thread(this::run, "selected-pcm-player");
        owner.setDaemon(true);
        owner.start();
    }

    /**
     * Synchronously changes logical source and clears queued old PCM; native flush is owner-only.
     */
    public void select(long sourceId) {
        queue.select(sourceId);
        LockSupport.unpark(owner);
    }

    /**
     * Nonselected sources never accumulate. localCapturedAtNs is already converted by parent's clock sync.
     */
    public void offer(long sourceId, PcmAudioFrame frame, long localCapturedAtNs) {
        if (queue.offer(sourceId, frame, localCapturedAtNs, System.nanoTime()))
            LockSupport.unpark(owner);
    }

    @Override
    public void close() {
        closed.set(true);
        queue.close();
        listener = null;
        focusEvents.clear();
        owner.interrupt();
        LockSupport.unpark(owner);
    }

    private void run() {
        AudioTrack track = null;
        AudioFocusRequest request = null;
        AudioManager manager = null;
        PcmNativeLease.Ticket lease = null;
        boolean playing = false;
        long observed = -1, source = 0, lastInput = System.nanoTime(), zeroSince = 0;
        int offset = 0, stalls = 0;
        PcmAudioQueue.Item pending = null;
        PcmAudioFocusState focus = new PcmAudioFocusState();
        try {
            manager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) throw new IllegalStateException("AudioManager unavailable");
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
            Handler callbacks = new Handler(Looper.getMainLooper());
            while (!closed.get()) {
                long generation = queue.generation(), selected = queue.selectedSource();
                if (generation != queue.generation()) continue;
                if (generation != observed) {
                    observed = generation;
                    source = selected;
                    pending = null;
                    offset = 0;
                    zeroSince = 0;
                    stalls = 0;
                    if (track != null) {
                        pauseFlush(track);
                        playing = false;
                    }
                    if (request != null) {
                        manager.abandonAudioFocusRequest(request);
                        request = null;
                    }
                    focus.select(source);
                    focusEvents.clear();
                    if (source > 0) {
                        final long focusGeneration = observed;
                        request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                                .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
                                .setOnAudioFocusChangeListener(change -> {
                                    if (closed.get()) return;
                                    FocusEvent event = new FocusEvent(focusGeneration, change);
                                    if (!focusEvents.offer(event)) {
                                        focusEvents.clear();
                                        focusEvents.offer(new FocusEvent(focusGeneration, AudioManager.AUDIOFOCUS_LOSS));
                                    }
                                    LockSupport.unpark(owner);
                                }, callbacks).build();
                        boolean granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                        focus.requestResult(granted);
                        if (granted)
                            status(source, "已选择此路声音，等待PCM；其他路静音，视频放大不改变音源");
                        else
                            status(source, "未获得音频焦点，本路暂静音；可再次选择音源重试，不自动抢占");
                    } else status(0, "所有窗口声音已静音");
                }
                FocusEvent event;
                while ((event = focusEvents.poll()) != null) {
                    if (event.generation != observed) continue;
                    PcmAudioFocusState.State before = focus.state();
                    focus.changed(event.change);
                    if (before != focus.state()) {
                        pending = null;
                        offset = 0;
                        queue.clear();
                        zeroSince = 0;
                        if (track != null) {
                            pauseFlush(track);
                            playing = false;
                        }
                        if (focus.state() == PcmAudioFocusState.State.LOST) {
                            if (request != null) {
                                manager.abandonAudioFocusRequest(request);
                                request = null;
                            }
                            status(source, "音频焦点已失去；停止声音，下一次选择音源才重新申请");
                        } else if (!focus.canPlay())
                            status(source, "音频焦点暂时丢失/要求duck，暂停并清除旧声音");
                        else status(source, "音频焦点恢复，从当前新PCM继续，不补播旧积压");
                    }
                }
                if (closed.get()) break;
                if (!focus.canPlay() || source == 0) {
                    queue.clear();
                    pending = null;
                    park(20_000_000L);
                    continue;
                }
                long now = System.nanoTime();
                if (pending == null) {
                    pending = queue.poll(now);
                    offset = 0;
                    if (pending != null) lastInput = now;
                }
                if (pending == null) {
                    if (playing && now - lastInput > 600_000_000L) {
                        pauseFlush(track);
                        playing = false;
                        status(source, "暂未收到音频，已暂停并清空输出；等待新数据");
                    }
                    park(5_000_000L);
                    continue;
                }
                if (!queue.isCurrent(pending) || PcmAudioQueue.tooOld(pending.localCapturedAtNs, now) || now - pending.enqueuedAtNs > PcmAudioQueue.MAX_QUEUED_NS) {
                    pending = null;
                    offset = 0;
                    zeroSince = 0;
                    if (track != null && playing) {
                        pauseFlush(track);
                        playing = false;
                    }
                    continue;
                }
                if (track == null) {
                    if ((lease = NATIVE_LEASE.tryAcquire()) == null)
                        throw new IllegalStateException("旧AudioTrack资源仍在释放，暂不可创建新播放器");
                    int min = AudioTrack.getMinBufferSize(PcmAudioFrame.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                    if (min <= 0 || min > PcmAudioQueue.MAX_BYTES)
                        throw new IllegalStateException("48k双声道PCM输出不支持120ms内有界缓冲，min=" + min);
                    AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(PcmAudioFrame.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
                    track = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(Math.max(min, PcmAudioFrame.MAX_BYTES * 3)).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
                    if (track.getState() != AudioTrack.STATE_INITIALIZED || track.getBufferSizeInFrames() > PcmAudioQueue.MAX_BYTES / 4)
                        throw new IllegalStateException("AudioTrack初始化失败或实际缓冲超过120ms");
                }
                // Never hold queue monitor across native calls. One already-entered nonblocking native write
                // may race a UI selection; owner flushes on next generation before accepting its new source.
                if (!queue.isCurrent(pending)) continue;
                if (!playing) {
                    track.play();
                    playing = true;
                }
                if (!queue.isCurrent(pending)) continue;
                int remaining = pending.frame.length - offset;
                int written = track.write(pending.frame.data, offset, remaining, AudioTrack.WRITE_NON_BLOCKING);
                if (written < 0 || written > remaining || written % 4 != 0)
                    throw new IllegalStateException("PCM输出失败/非帧对齐 code=" + written);
                if (written > 0) {
                    offset += written;
                    zeroSince = 0;
                    stalls = 0;
                    if (offset == pending.frame.length) {
                        pending = null;
                        offset = 0;
                    }
                } else {
                    now = System.nanoTime();
                    if (zeroSince == 0) zeroSince = now;
                    if (now - zeroSince > 120_000_000L) {
                        pauseFlush(track);
                        playing = false;
                        pending = null;
                        offset = 0;
                        queue.clear();
                        zeroSince = 0;
                        if (++stalls >= 3)
                            throw new IllegalStateException("AudioTrack多次无进展，停止声音以避免积压");
                        status(source, "音频输出短时无进展，已清除旧PCM");
                    }
                    park(3_000_000L);
                }
            }
        } catch (RuntimeException e) {
            if (!closed.get()) {
                terminalFailure = "音频播放停止：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "；请重新进入接收端恢复，不会主动停止视频";
                error(source, terminalFailure);
            }
        } finally {
            closed.set(true);
            queue.close();
            listener = null;
            focusEvents.clear();
            try {
                if (manager != null && request != null) manager.abandonAudioFocusRequest(request);
            } catch (RuntimeException ignored) {
            }
            boolean released = track == null;
            try {
                if (track != null) {
                    try {
                        pauseFlush(track);
                        track.stop();
                    } catch (RuntimeException ignored) {
                    } finally {
                        track.release();
                        released = true;
                    }
                }
            } catch (RuntimeException ignored) {
            } finally {
                if (lease != null) lease.releaseAfterNative(released);
            }
        }
    }

    private void park(long nanos) {
        Thread.interrupted();
        if (!closed.get()) LockSupport.parkNanos(nanos);
    }

    private static void pauseFlush(AudioTrack track) {
        if (track == null) return;
        if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
        track.flush();
    }

    private void status(long id, String text) {
        Listener l = listener;
        if (l != null && !closed.get()) try {
            l.onStatus(id, text);
        } catch (RuntimeException ignored) {
        }
    }

    private void error(long id, String text) {
        Listener l = listener;
        if (l != null && !closed.get()) try {
            l.onError(id, text);
        } catch (RuntimeException ignored) {
        }
    }

    private static final class FocusEvent {
        final long generation;
        final int change;

        FocusEvent(long generation, int change) {
            this.generation = generation;
            this.change = change;
        }
    }
}
