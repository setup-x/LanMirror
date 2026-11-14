package com.lanmirror.core.api;

public final class SmoothCodecClockTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[]args){
        SmoothCodecClock clock=new SmoothCodecClock();
        check(clock.next(1000000)==1000000,"First source time preserved");
        check(clock.next(1016667)==1016667,"60fps source spacing preserved");
        check(clock.next(1016667)==1016668,"Duplicate gets only one microsecond correction");
        check(clock.next(100)==1016669,"Backwards source gets unique monotonic token");
        check(clock.next(1033334)==1033334,"Source clock resumed without permanent artificial offset");
        clock.reset();check(clock.next(0)==1,"Zero reserved for input CSD");
        check(clock.next(-10)==2,"Negative source cannot create negative codec token");
        clock.reset();check(clock.next(700)==700,"New generation reset does not inherit old timeline");
        check(clock.next(Long.MAX_VALUE)==Long.MAX_VALUE,"Last representable timestamp accepted");
        try{clock.next(0);throw new AssertionError();}catch(IllegalStateException expected){checks++;}
        clock.reset();SmoothFrameMetadata metadata=new SmoothFrameMetadata(4);
        StreamFrame a=new StreamFrame(1,0,900,1,111,222,new byte[]{1},0,1,null);
        StreamFrame b=new StreamFrame(1,1,900,0,333,444,new byte[]{2},0,1,null);
        long ta=clock.next(a.ptsUs),tb=clock.next(b.ptsUs);
        metadata.add(ta,new SmoothFrameMetadata.Entry(a,555,true));metadata.add(tb,new SmoothFrameMetadata.Entry(b,666,false));
        a.release();b.release();
        check(ta!=tb,"Duplicate source PTS map to distinct codec output identifiers");
        SmoothFrameMetadata.Entry eb=metadata.remove(tb),ea=metadata.remove(ta);
        check(ea.sourcePtsUs==900&&eb.sourcePtsUs==900,"Original PTS remains available despite correction");
        check(ea.encodedAtNs==111&&eb.encodedAtNs==333&&ea.fedAtNs==555&&eb.fedAtNs==666,"Real phase timestamps never rewritten");
        System.out.println("PASS: "+checks+" smooth codec PTS assertions (source spacing, duplicate/backward correction, generation reset, original stamps)");
    }
}
