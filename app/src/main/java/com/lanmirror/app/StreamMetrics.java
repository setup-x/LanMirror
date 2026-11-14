package com.lanmirror.app;

/** Immutable per-session HUD sample. Stage latency is encoder-output observation to Surface
 * submission (clock-calibrated), NOT capture/encode/panel glass-to-glass latency.
 * droppedFrames counts application input discards plus decoded-output display skips; it does
 * not count every UDP reconstruction loss and is not a Wi-Fi packet-loss measurement.
 */
public final class StreamMetrics {
    public static final long STALE_NS=2_500_000_000L;
    public final long sampledAtNs,intervalNs,submittedFrames,droppedFrames;
    public final double submitFps,stageAverageMs,stageMaximumMs;
    public final boolean hasStageSamples;

    private StreamMetrics(long sampledAtNs,long intervalNs,long submittedFrames,long droppedFrames,
                          double submitFps,double stageAverageMs,double stageMaximumMs,boolean hasStageSamples){
        this.sampledAtNs=sampledAtNs;this.intervalNs=intervalNs;this.submittedFrames=submittedFrames;
        this.droppedFrames=droppedFrames;this.submitFps=submitFps;this.stageAverageMs=stageAverageMs;
        this.stageMaximumMs=stageMaximumMs;this.hasStageSamples=hasStageSamples;
    }
    /** Durations/totals/max are nanoseconds; counts are actual submitted video frames, not configured FPS.
     * The timestamp may be negative because System.nanoTime has an arbitrary signed origin.
     * No stage sample is represented by hasStageSamples=false and NaN, never a fictitious 0ms.
     */
    public static StreamMetrics fromCounters(long sampledAtNs,long intervalNs,long intervalSubmittedFrames,
            long submittedFrames,long droppedFrames,long stageSamples,long stageTotalNs,long stageMaximumNs){
        if(intervalNs<=0||intervalSubmittedFrames<0||submittedFrames<intervalSubmittedFrames||droppedFrames<0
                ||stageSamples<0||stageSamples>intervalSubmittedFrames||stageTotalNs<0||stageMaximumNs<0)
            throw new IllegalArgumentException("Invalid metrics interval/counters");
        boolean sampled=stageSamples>0;
        if(!sampled&&(stageTotalNs!=0||stageMaximumNs!=0))throw new IllegalArgumentException("Stage totals without samples");
        if(sampled){
            long ceilingMean=stageTotalNs/stageSamples+(stageTotalNs%stageSamples==0?0:1);
            if(stageMaximumNs>stageTotalNs||stageMaximumNs<ceilingMean)throw new IllegalArgumentException("Inconsistent stage maximum");
        }
        double fps=intervalSubmittedFrames*(1_000_000_000.0/intervalNs);
        double average=sampled?(stageTotalNs/(double)stageSamples)/1_000_000.0:Double.NaN;
        double maximum=sampled?stageMaximumNs/1_000_000.0:Double.NaN;
        if(Double.isNaN(fps)||Double.isInfinite(fps)||sampled&&(Double.isNaN(average)||Double.isInfinite(average)||Double.isNaN(maximum)||Double.isInfinite(maximum)))
            throw new IllegalArgumentException("Non-finite metrics");
        return new StreamMetrics(sampledAtNs,intervalNs,submittedFrames,droppedFrames,fps,average,maximum,sampled);
    }
    /** Backward timestamps and subtraction overflow are conservatively stale, not fresh indefinitely. */
    public boolean isStale(long nowNs){
        if(nowNs<sampledAtNs)return true;
        long age=nowNs-sampledAtNs;
        return age<0||age>=STALE_NS;
    }
}
