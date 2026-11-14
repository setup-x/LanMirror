package com.lanmirror.app;

import java.util.Locale;

/** HUD semantics are explicitly software-stage latency and Surface submission FPS. */
public final class StreamHud {
    private StreamHud(){}
    public static String label(StreamMetrics metrics,long nowNs){
        if(metrics==null)return "延时 --ms · --fps";
        if(metrics.isStale(nowNs))return "指标未更新 · --fps";
        return (metrics.hasStageSamples?String.format(Locale.US,"延时≈%.0fms",metrics.stageAverageMs):"延时 --ms")
                +String.format(Locale.US," · %.1ffps",metrics.submitFps);
    }
    public static String details(StreamMetrics metrics,long nowNs){
        String definition="延时为编码输出→渲染提交的估算，包含后续传输/排队/解码，不含此前采集与编码内部、此后的面板刷新。FPS为该路Surface提交帧率，不是物理面板实测或不同内容帧率。";
        if(metrics==null)return "尚未收到本会话指标。\n"+definition;
        String age=metrics.isStale(nowNs)?"已过期/无更新":"当前采样";
        String latency=metrics.hasStageSamples?String.format(Locale.US,"阶段延时平均/最大：%.1f / %.1f ms",metrics.stageAverageMs,metrics.stageMaximumMs):"当前窗口没有已提交帧的延时样本（不是0ms）";
        return String.format(Locale.US,"%s · 采样窗口 %.3f秒\n提交FPS：%.1f\n%s\n本会话累计提交帧数：%d\n应用输入丢弃＋输出跳显：%d（不是无线丢包）\n%s",
                age,metrics.intervalNs/1e9,metrics.submitFps,latency,metrics.submittedFrames,metrics.droppedFrames,definition);
    }
}
