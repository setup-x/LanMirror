package com.lanmirror.app;

import java.util.Locale;

import com.lanmirror.core.api.StreamMetrics;
import com.lanmirror.core.api.TransportMetrics;

/** HUD semantics are explicitly software-stage latency and Surface submission FPS. */
public final class StreamHud {
    private StreamHud(){}
    public static String label(StreamMetrics metrics,long nowNs){
        if (metrics == null) return "延时 --ms · --fps\n-- Mbps";
        if (metrics.isStale(nowNs)) return "指标未更新 · --fps\n-- Mbps";
        return (metrics.hasStageSamples?String.format(Locale.US,"延时≈%.0fms",metrics.stageAverageMs):"延时 --ms")
                + String.format(Locale.US, " · %.1ffps", metrics.submitFps) + "\n" + rateLabel(metrics.transport);
    }

    private static String rateLabel(TransportMetrics transport) {
        return transport.sampled ? String.format(Locale.US, "%.2f Mbps", transport.receiveMbps) : "-- Mbps";
    }

    private static String transportLabel(TransportMetrics transport) {
        String rate = rateLabel(transport);
        if (transport.mode == TransportMetrics.Mode.TCP) return rate + " · TCP丢包不适用";
        if (transport.mode != TransportMetrics.Mode.UDP || !transport.sampled)
            return rate + " · 缺片 --";
        return rate + (transport.missingFragments > 0 ? " · 超时缺片 " + transport.missingFragments : " · 本窗超时缺片 0");
    }
    public static String details(StreamMetrics metrics,long nowNs){
        String definition = "延时为编码输出→渲染提交的估算，包含后续传输/排队/解码，不含此前采集与编码内部、此后的面板刷新。FPS为该路Surface提交帧率，不是物理面板实测或不同内容帧率。\n码率为实际接收的视频载荷，不含协议头；UDP包含重复和重传。超时缺片仅统计已开始重组且超时的缺失分片，无法覆盖整帧完全未到；0表示本窗口未观察到超时缺片，不等于无线无丢包。TCP无法从应用帧统计底层丢包。";
        if(metrics==null)return "尚未收到本会话指标。\n"+definition;
        String age=metrics.isStale(nowNs)?"已过期/无更新":"当前采样";
        String latency=metrics.hasStageSamples?String.format(Locale.US,"阶段延时平均/最大：%.1f / %.1f ms",metrics.stageAverageMs,metrics.stageMaximumMs):"当前窗口没有已提交帧的延时样本（不是0ms）";
        return String.format(Locale.US,"%s · 采样窗口 %.3f秒\n提交FPS：%.1f\n%s\n本会话累计提交帧数：%d\n应用输入丢弃＋输出跳显：%d（不是无线丢包）\n%s",
                age, metrics.intervalNs / 1e9, metrics.submitFps, latency, metrics.submittedFrames, metrics.droppedFrames,
                (metrics.isStale(nowNs) ? "传输指标已过期：--" : "传输：" + transportLabel(metrics.transport)) + "\n" + definition);
    }
}
