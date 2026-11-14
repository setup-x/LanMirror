package com.lanmirror.receiver.api;

import android.view.Surface;

import com.lanmirror.receiver.internal.ReceiverSessionImpl;

/**
 * 兼容TCP单路接收入口。回调来自工作线程，宿主负责切换UI线程及持有Surface。
 * Surface销毁前调用stop；它关闭连接并最多等待250ms，解码器仍由原工作线程释放。
 * 重复start不会创建并行会话；旧工作线程结束前不会启动新会话。
 */
public final class ReceiverSession {
    public interface Listener {
        void onStatus(String message);

        void onVideoSize(int width, int height);

        // IDs increase within one listening worker; also isolate callbacks across stop/start.
        default void onConnectionStarted(long connectionId) {
        }

        // Successful Surface submission, not physical panel refresh; may arrive after ended.
        default void onFirstFrame(long connectionId) {
        }

        default void onConnectionEnded(long connectionId) {
        }
    }

    private final ReceiverSessionImpl delegate;

    public ReceiverSession(Surface surface, String code, int port, Listener listener) {
        this(surface, code, port, listener, false);
    }

    public ReceiverSession(Surface surface, String code, int port, Listener listener, boolean codecExperiment) {
        delegate = new ReceiverSessionImpl(surface, code, port, listener, codecExperiment);
    }

    public void start() {
        delegate.start();
    }

    public void stop() {
        delegate.stop();
    }
}
