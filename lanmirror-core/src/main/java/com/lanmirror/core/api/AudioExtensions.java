package com.lanmirror.core.api;

import java.io.IOException;
import java.net.InetAddress;

/**
 * 可选音频扩展。回调来自工作线程；实现必须幂等关闭且不得关闭视频或宿主授权。
 */
public final class AudioExtensions {
    private AudioExtensions() {
    }

    public interface Status {
        void onStatus(String text);
    }

    public interface Session extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 工厂捕获平台采集依赖；返回未启动会话，避免取消与启动的资源竞争。
     */
    public interface SenderFactory {
        Sender create(InetAddress address, int port, long high, long low, Status status) throws IOException;
    }

    public interface Sender extends Session {
        void start();

        /**
         * close 立即取消；本方法仅在发送清理线程调用，返回时已结束对借用投影的使用。
         */
        void awaitStopped();
    }

    public interface ReceiverListener {
        void onSelection(long sessionId, long revision);

        void onPlaybackStatus(long sessionId, String text, boolean error);
    }

    public interface ReceiverFactory {
        Receiver create(ReceiverListener listener);
    }

    /**
     * 预留端口后才发批准；收到视频时钟后才启动端点。PCM所有权留在音频模块。
     */
    public interface Receiver extends Session {
        Endpoint reserve(InetAddress address, long sessionId, Status status) throws IOException;

        void joined(int slot, long sessionId);

        void left(long sessionId);

        void toggle(int slot, long sessionId);
    }

    public interface Endpoint extends Session {
        int port();

        long tokenHigh();

        long tokenLow();

        void start(Wire.ClockSync clock);
    }
}
