package com.lanmirror.receiver.api;

import android.view.Surface;

import com.lanmirror.receiver.internal.SmoothReceiverImpl;

/**
 * Smooth TCP/UDP单路接收入口。回调来自工作线程；Surface所有权留在宿主。
 * Surface销毁前调用stop，连接立即取消且最多等待250ms，native回收异步完成。
 * 重复start不会建立并行监听，重新启动须等旧工作线程结束。
 */
public final class SmoothReceiver {
    private final SmoothReceiverImpl delegate;

    public SmoothReceiver(Surface surface, String code, int port, ReceiverSession.Listener listener) {
        delegate = new SmoothReceiverImpl(surface, code, port, listener);
    }

    public void start() {
        delegate.start();
    }

    public void stop() {
        delegate.stop();
    }
}
