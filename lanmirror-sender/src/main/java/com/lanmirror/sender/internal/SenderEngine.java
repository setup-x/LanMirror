package com.lanmirror.sender.internal;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.lanmirror.sender.api.SenderSession;

/**
 * 仅供 SenderSession 跨包委托；应用应使用 api 包。
 */
public final class SenderEngine {
    final MediaProjection projection;
    final Handler handler = new Handler(Looper.getMainLooper());
    final WindowManager windowManager;
    final SenderSession.Config config;
    private final SenderSession.Listener listener;
    volatile long requestedSize;
    volatile boolean stopping;
    private boolean started;
    private Pipeline pipeline;

    interface Pipeline extends Runnable {
        void cancel();
    }

    public SenderEngine(Context context, MediaProjection projection, SenderSession.Config config, SenderSession.Listener listener) {
        this.windowManager = context.getApplicationContext().getSystemService(WindowManager.class);
        this.projection = projection;
        this.config = config;
        this.listener = listener;
    }

    public synchronized void start() {
        if (started) throw new IllegalStateException("发送会话只能使用一次");
        launch();
    }

    private void launch() {
        started = true;
        new Thread(() -> {
            try {
                synchronized (SenderEngine.this) {
                    if (stopping) return;
                    pipeline = config.pipeline == 0 ? new LegacySender(this) : new SmoothSender(this);
                }
                pipeline.run();
            } catch (Exception error) {
                if (!stopping) publish("投屏结束：" + message(error));
            } finally {
                synchronized (SenderEngine.this) {
                    stopping = true;
                    pipeline = null;
                }
                try {
                    listener.onStopped();
                } catch (RuntimeException ignored) {
                }
            }
        }, "mirror-encoder").start();
    }

    public synchronized void stop() {
        stopping = true;
        if (pipeline != null) pipeline.cancel();
        if (!started) launch();
    }

    public void updateSourceSize(int width, int height) {
        if (width > 0 && height > 0) requestedSize = ((long) width << 32) | (height & 0xffffffffL);
    }

    void publish(String text) {
        try {
            listener.onStatus(text);
        } catch (RuntimeException ignored) {
        }
    }

    static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
