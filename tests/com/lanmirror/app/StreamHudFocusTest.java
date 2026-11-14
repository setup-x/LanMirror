package com.lanmirror.app;

/** Display semantics and focus identity only: no Android/native Surface is exercised here. */
public final class StreamHudFocusTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface Work{void run();}
    private static void bad(Work work){boolean failed=false;try{work.run();}catch(IllegalArgumentException e){failed=true;}check(failed,"Invalid focus rejected");}
    public static void main(String[] args){
        long now=10_000_000_000L;
        StreamMetrics normal=StreamMetrics.fromCounters(now,2_000_000_000L,90,200,7,3,150_000_000L,80_000_000L);
        check(StreamHud.label(null,now).contains("--ms")&&StreamHud.label(null,now).contains("--fps"),"Waiting is not zero latency or configured60");
        String live=StreamHud.label(normal,now);
        check(live.contains("50ms")&&live.contains("45.0fps"),"HUD uses actual stage mean and interval FPS");
        check(live.contains("≈"),"Latency explicitly estimated");
        String stale=StreamHud.label(normal,now+StreamMetrics.STALE_NS);
        check(stale.contains("指标未更新")&&stale.contains("--fps")&&!stale.contains("45.0"),"No plausible stale FPS remains");
        check(StreamHud.label(normal,now-1).contains("--fps"),"Backward clock not reported live");
        StreamMetrics waiting=StreamMetrics.fromCounters(now,1_000_000_000,0,200,7,0,0,0);
        check(StreamHud.label(waiting,now).contains("--ms")&&StreamHud.label(waiting,now).contains("0.0fps"),"Live empty reporting interval has0FPS but unknown latency");
        check(!StreamHud.label(waiting,now).contains("NaN"),"No NaN exposed to user");
        StreamMetrics protectedOld=StreamMetrics.fromCounters(now,1_000_000_000,1,1,0,1,750_000_000,750_000_000);
        check(StreamHud.label(protectedOld,now).contains("750ms"),"Do not clamp reported startup latency to budget");
        String detail=StreamHud.details(normal,now);
        check(detail.contains("200")&&detail.contains("7")&&detail.contains("2.000"),"Actual cumulative counters/interval in details");
        check(detail.contains("编码输出")&&detail.contains("不是物理面板"),"Metric scope explicit");
        check(StreamHud.details(normal,now+StreamMetrics.STALE_NS).contains("已过期"),"Old details marked as old");
        check(StreamHud.details(null,now).contains("尚未"),"Missing metrics safe in report");
        AdaptiveFocusState focus=new AdaptiveFocusState();long[] ids={11,22,0,44};
        check(focus.resolve(ids)==-1,"Default auto layout");focus.toggle(1,22);check(focus.resolve(ids)==1,"Focus selected session");
        focus.toggle(1,22);check(focus.resolve(ids)==-1,"Second long press returns to auto");
        focus.toggle(3,44);check(focus.resolve(ids)==3,"Can focus another physical slot");
        ids[3]=0;check(focus.resolve(ids)==-1,"Disconnected focus removed");
        ids[3]=45;check(focus.resolve(ids)==-1,"New client in same slot does not inherit focus");
        focus.toggle(0,11);ids[0]=99;check(focus.resolve(ids)==-1,"Batched reuse before UI layout still cannot inherit old focus");
        focus.toggle(1,22);ids[0]=100;check(focus.resolve(ids)==1,"Unrelated new client does not change focus");
        focus.clear();check(focus.resolve(ids)==-1,"Explicit restore clears state");
        bad(()->focus.toggle(-1,1));bad(()->focus.toggle(4,1));bad(()->focus.toggle(0,0));bad(()->focus.toggle(0,-1));bad(()->focus.resolve(null));bad(()->focus.resolve(new long[3]));
        for(int cycle=1;cycle<=100;cycle++){
            for(int i=0;i<4;i++)ids[i]=cycle*10L+i+1;
            int selected=cycle%4;focus.toggle(selected,ids[selected]);check(focus.resolve(ids)==selected,"Repeated focus identity");
            AdaptiveLayoutPolicy.Plan p=AdaptiveLayoutPolicy.arrange(360,640,4,40,new int[]{0,1,2,3},new int[]{1080,1920,1080,1920},new int[]{1920,1080,1920,1080},focus.resolve(ids));
            int visible=0;for(boolean shown:p.shown)if(shown)visible++;check(visible==1&&p.shown[selected],"Focused pane only; other sessions kept in caller state");
            ids[selected]++;check(focus.resolve(ids)==-1,"Reused slot invalidates focus each cycle");
            p=AdaptiveLayoutPolicy.arrange(360,640,4,40,new int[]{0,1,2,3},new int[4],new int[4],focus.resolve(ids));visible=0;for(boolean shown:p.shown)if(shown)visible++;check(visible==4,"Auto grid restored after focus ends");
        }
        System.out.println("PASS: "+checks+" HUD/focus assertions (actual FPS, stage estimates, stale/absent states, cumulative details, session-bound focus and reuse; NOT native Surface lifecycle)");
    }
}
