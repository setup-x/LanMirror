package com.lanmirror.sender.api;

import android.content.Context;
import android.media.projection.MediaProjection;

import com.lanmirror.core.api.AudioExtensions;
import com.lanmirror.core.api.ApprovalProtocol;
import com.lanmirror.core.api.StreamProfile;
import com.lanmirror.sender.internal.SenderEngine;

/**
 * 单次使用的发送会话。借用宿主持有的 MediaProjection，不注册授权回调，也不停止授权。
 * 除系统撤销授权外，宿主必须在 onStopped 回调后才释放借用的投影授权，
 * 并将采集尺寸变化传入 updateSourceSize。授权被撤销时宿主应立即调用 stop。
 * stop 立即逻辑取消，资源异步释放；编码器、输入 Surface 和虚拟显示只由发送工作线程释放。
 * 生命周期契约：新建 → 启动一次 → 取消/自然结束 → 释放 native 资源 → onStopped 一次。
 * 新建后直接 stop 也会异步通知 onStopped；任何 stop 之后再 start 均抛出 IllegalStateException。
 * onStopped 后不再产生 onStatus；监听器异常不会阻止资源回收。
 */
public final class SenderSession {
    /**
     * 回调来自发送工作线程；宿主更新 UI 时须切换至主线程。
     */
    public interface Listener {
        void onStatus(String text);

        /**
         * 本会话的采集资源已释放；仅通知一次，此后宿主可停止投影授权。
         */
        void onStopped();
    }

    /**
     * 不可变配置；音频工厂为 null 时完全禁用音频扩展。
     */
    public static final class Config {
        public final String host, pin;
        public final int profile, pipeline, port;
        public final boolean directTransport, burstEncoder;
        /**
         * 工作线程创建/启动；close 可从取消线程调用，awaitStopped 返回前须结束对借用投影的使用。
         */
        public final AudioExtensions.SenderFactory audioFactory;

        /**
         * 默认禁用音频；需要声音时使用带工厂参数的构造方法显式注入。
         */
        public Config(String host, String pin, int profile, int pipeline, int port,
                      boolean directTransport, boolean burstEncoder) {
            this(host, pin, profile, pipeline, port, directTransport, burstEncoder, null);
        }

        /**
         * pipeline 为 0..4；原有传输/连续取帧开关仅用于 pipeline 0。
         */
        public Config(String host, String pin, int profile, int pipeline, int port,
                      boolean directTransport, boolean burstEncoder, AudioExtensions.SenderFactory audioFactory) {
            if (host == null || host.trim().isEmpty())
                throw new IllegalArgumentException("缺少接收端地址");
            this.host = host;
            this.pin = pin;
            this.profile = StreamProfile.sanitize(profile);
            this.pipeline = pipeline < 0 || pipeline > 4 ? 3 : pipeline;
            this.port = port > 0 && port <= 65535 ? port : ApprovalProtocol.PORT;
            this.directTransport = directTransport;
            this.burstEncoder = burstEncoder;
            this.audioFactory = audioFactory;
        }
    }

    private final SenderEngine engine;

    /**
     * 仅保存依赖，不启动采集；projection 的创建、前台服务与授权回调由宿主管理。
     */
    public SenderSession(Context context, MediaProjection projection, Config config, Listener listener) {
        if (context == null || projection == null || config == null || listener == null)
            throw new NullPointerException("会话依赖不得为空");
        engine = new SenderEngine(context, projection, config, listener);
    }

    /**
     * 异步启动且只允许调用一次；stop 后不能重新启动。
     */
    public void start() {
        engine.start();
    }

    /**
     * 立即逻辑取消且幂等，不等待 native 释放；释放完成后异步通知 onStopped，未启动时也通知。
     */
    public void stop() {
        engine.stop();
    }

    /**
     * 可从任意线程提供当前采集内容尺寸；忽略非正数。
     */
    public void updateSourceSize(int width, int height) {
        engine.updateSourceSize(width, height);
    }
}
