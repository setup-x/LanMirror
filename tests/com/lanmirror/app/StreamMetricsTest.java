package com.lanmirror.app;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** JVM unit/counter validation only, not Android Surface or panel FPS measurement. */
public final class StreamMetricsTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void close(double value,double expected,String message){check(Math.abs(value-expected)<=Math.max(1e-9,Math.abs(expected)*1e-12),message+" got "+value);}
    private interface Work{void run();}
    private static void bad(Work work,String message){boolean failed=false;try{work.run();}catch(IllegalArgumentException e){failed=true;}check(failed,message);}
    public static void main(String[] args)throws Exception{
        StreamMetrics normal=StreamMetrics.fromCounters(10_000_000_000L,2_000_000_000L,90,200,7,3,150_000_000L,80_000_000L);
        close(normal.submitFps,45,"Actual rendered count divided by real 2s interval, never configured60");
        close(normal.stageAverageMs,50,"Nanosecond total/sample -> millisecond mean");close(normal.stageMaximumMs,80,"Nanoseconds -> max milliseconds");
        check(normal.hasStageSamples,"Positive sample flag");check(normal.submittedFrames==200&&normal.droppedFrames==7,"Cumulative counters preserved");
        check(normal.sampledAtNs==10_000_000_000L&&normal.intervalNs==2_000_000_000L,"Sample and actual interval preserved");
        StreamMetrics quarter=StreamMetrics.fromCounters(11,250_000_000,12,212,9,12,120_000_000,20_000_000);
        close(quarter.submitFps,48,"Sub-second actual count");close(quarter.stageAverageMs,10,"Mean divides sample count, not rendered cumulative");
        StreamMetrics late=StreamMetrics.fromCounters(12,4_000_000_000L,30,242,9,1,750_000_000,750_000_000);
        close(late.submitFps,7.5,"Blocked report includes elapsed time, no one-second assumption");close(late.stageAverageMs,750,"Startup old sample remains real, not clamped250");
        StreamMetrics micro=StreamMetrics.fromCounters(0,1_000_000_000,1,1,0,1,1500,1500);
        close(micro.stageAverageMs,.0015,"1500ns is .0015ms, not1500ms or1.5ms");
        StreamMetrics noSamples=StreamMetrics.fromCounters(0,1_000_000_000,0,242,30,0,0,0);
        close(noSamples.submitFps,0,"Active reporter with no submissions is zero FPS");check(!noSamples.hasStageSamples,"No latency samples explicitly absent");
        check(Double.isNaN(noSamples.stageAverageMs)&&Double.isNaN(noSamples.stageMaximumMs),"Absent latency never silently zero");
        StreamMetrics fpsOnly=StreamMetrics.fromCounters(0,1_000_000_000,30,30,0,0,0,0);
        close(fpsOnly.submitFps,30,"FPS remains usable without latency samples");check(!fpsOnly.hasStageSamples,"Missing stage independent from FPS");
        StreamMetrics zeroLatency=StreamMetrics.fromCounters(0,1_000_000_000,1,1,0,1,0,0);
        check(zeroLatency.hasStageSamples,"Measured zero age differs from absent samples");close(zeroLatency.stageAverageMs,0,"True sampled zero");
        check(!normal.isStale(normal.sampledAtNs),"Fresh at snapshot instant");
        check(!normal.isStale(normal.sampledAtNs+StreamMetrics.STALE_NS-1),"Fresh just under2.5s");
        check(normal.isStale(normal.sampledAtNs+StreamMetrics.STALE_NS),"Stale at2.5s boundary");
        check(normal.isStale(normal.sampledAtNs+StreamMetrics.STALE_NS+1),"Stale past threshold");
        check(normal.isStale(normal.sampledAtNs-1),"Backward time never fresh forever");
        StreamMetrics signedOrigin=StreamMetrics.fromCounters(-100,1,0,0,0,0,0,0);
        check(!signedOrigin.isStale(-100)&&!signedOrigin.isStale(0),"nanoTime signed origin allowed");
        StreamMetrics minOrigin=StreamMetrics.fromCounters(Long.MIN_VALUE,1,0,0,0,0,0,0);
        check(minOrigin.isStale(Long.MAX_VALUE),"Subtraction overflow conservatively stale");
        StreamMetrics maxOrigin=StreamMetrics.fromCounters(Long.MAX_VALUE,1,0,0,0,0,0,0);
        check(maxOrigin.isStale(Long.MIN_VALUE),"Wrapped/backward absolute time stale");
        for(long invalidInterval:new long[]{0,-1,Long.MIN_VALUE})bad(()->StreamMetrics.fromCounters(0,invalidInterval,0,0,0,0,0,0),"Reject zero/backward interval");
        bad(()->StreamMetrics.fromCounters(0,1,-1,0,0,0,0,0),"Negative interval count");
        bad(()->StreamMetrics.fromCounters(0,1,0,-1,0,0,0,0),"Negative cumulative count");
        bad(()->StreamMetrics.fromCounters(0,1,3,2,0,0,0,0),"Interval count cannot exceed cumulative");
        bad(()->StreamMetrics.fromCounters(0,1,0,0,-1,0,0,0),"Negative dropped count");
        bad(()->StreamMetrics.fromCounters(0,1,1,1,0,-1,0,0),"Negative stage sample count");
        bad(()->StreamMetrics.fromCounters(0,1,1,1,0,2,0,0),"More latency samples than actual submissions");
        bad(()->StreamMetrics.fromCounters(0,1,1,1,0,1,-1,0),"Negative stage total");
        bad(()->StreamMetrics.fromCounters(0,1,1,1,0,1,0,-1),"Negative stage maximum");
        bad(()->StreamMetrics.fromCounters(0,1,1,1,0,0,1,0),"Total without samples");
        bad(()->StreamMetrics.fromCounters(0,1,1,1,0,0,0,1),"Max without samples");
        bad(()->StreamMetrics.fromCounters(0,1,1,1,0,1,5,6),"Max cannot exceed nonnegative total");
        bad(()->StreamMetrics.fromCounters(0,1,2,2,0,2,11,5),"Max must be at least rounded-up mean");
        StreamMetrics extreme=StreamMetrics.fromCounters(0,1,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,1,Long.MAX_VALUE,Long.MAX_VALUE);
        check(Double.isFinite(extreme.submitFps)&&Double.isFinite(extreme.stageAverageMs)&&Double.isFinite(extreme.stageMaximumMs),"Large long values do not overflow through integer multiply");
        StreamMetrics longInterval=StreamMetrics.fromCounters(0,Long.MAX_VALUE,1,1,0,0,0,0);
        check(longInterval.submitFps>0&&Double.isFinite(longInterval.submitFps),"Long interval finite positive fractional FPS");
        StreamMetrics edgeMean=StreamMetrics.fromCounters(0,1,Long.MAX_VALUE,Long.MAX_VALUE,0,Long.MAX_VALUE,Long.MAX_VALUE,1);
        close(edgeMean.stageAverageMs,.000001,"Large sample arithmetic avoids max*count overflow");
        check(Modifier.isFinal(StreamMetrics.class.getModifiers()),"Snapshot class final");
        for(Field field:StreamMetrics.class.getFields()){
            check(Modifier.isFinal(field.getModifiers()),"Public fields immutable: "+field.getName());
            check(field.getType().isPrimitive(),"No exposed mutable arrays/references: "+field.getName());
        }
        close(normal.submitFps,45,"Later samples do not mutate earlier snapshot");check(normal.submittedFrames==200&&normal.droppedFrames==7,"Earlier cumulative snapshot remains unchanged");
        check(quarter!=normal&&late!=quarter,"Each report independent immutable value");
        System.out.println("PASS: "+checks+" StreamMetrics assertions (actual count/elapsed FPS, ns->ms sampled stage latency, absent samples, finite/bounds, stale/backward/overflow, immutable cumulative snapshots; NOT hardware FPS)");
    }
}
