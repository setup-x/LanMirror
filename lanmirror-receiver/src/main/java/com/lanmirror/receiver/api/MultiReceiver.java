package com.lanmirror.receiver.api;

import android.view.Surface;

import com.lanmirror.core.api.AudioExtensions;
import com.lanmirror.core.api.StreamMetrics;
import com.lanmirror.receiver.internal.MultiReceiverImpl;

/**
 * 单次使用的审批多路接收器，缺省不创建音频通道。
 * Surface由宿主持有；销毁前调用surfaceLost。回调来自工作线程，UI操作须切主线程。
 * stop立即取消连接且幂等；onStopped表示逻辑停止，解码器由各路工作线程异步释放。
 */
public final class MultiReceiver {
    public interface SurfaceProvider {
        Surface getSurface(int slot);
    }

    public interface Listener {
        void onApprovalRequested(long requestId, String name, String address);

        void onRequestFinished(long requestId, String reason);

        void onSlotChanged(int slot, long sessionId, String name, String address, String status);

        void onVideoSize(int slot, long sessionId, int width, int height);

        void onStatus(String status);

        default void onFirstFrame(int slot, long sessionId) {
        }

        default void onMetrics(int slot, long sessionId, StreamMetrics metrics) {
        }

        default void onListening(int port) {
        }

        default void onStopped() {
        }

        default void onAudioSelection(long sessionId, long revision) {
        }

        default void onAudioStatus(int slot, long sessionId, String text) {
        }

        default void onAudioPlaybackStatus(int slot, long sessionId, String text, boolean error) {
        }
    }

    private final MultiReceiverImpl delegate;

    public MultiReceiver(SurfaceProvider surfaces, Listener listener) {
        this(surfaces, listener, null);
    }

    public MultiReceiver(SurfaceProvider surfaces, Listener listener, AudioExtensions.ReceiverFactory audio) {
        delegate = new MultiReceiverImpl(surfaces, listener, audio);
    }

    public void start() {
        delegate.start();
    }

    public void stop() {
        delegate.stop();
    }

    public void decide(long requestId, boolean allow) {
        delegate.decide(requestId, allow);
    }

    public void disconnect(int slot, long sessionId) {
        delegate.disconnect(slot, sessionId);
    }

    public void surfaceLost(int slot) {
        delegate.surfaceLost(slot);
    }

    public void selectAudio(int slot, long sessionId) {
        delegate.selectAudio(slot, sessionId);
    }

    public int activeCount() {
        return delegate.activeCount();
    }

    public int pendingCount() {
        return delegate.pendingCount();
    }
}
