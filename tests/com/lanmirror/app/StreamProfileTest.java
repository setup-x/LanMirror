package com.lanmirror.app;

public final class StreamProfileTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    public static void main(String[] args) {
        int[] size = StreamProfile.sizeFor(2400,1080,0); check(size[0]==1920 && size[1]==1080);
        size=StreamProfile.sizeFor(1080,2400,0); check(size[0]==1080 && size[1]==1920);
        size=StreamProfile.sizeFor(2400,1080,1); check(size[0]==1920 && size[1]==1080);
        size=StreamProfile.sizeFor(1080,2400,6); check(size[0]==720 && size[1]==1280);
        check(StreamProfile.FPS==60 && StreamProfile.bitrate(0)==24000000);
        check(StreamProfile.bitrate(1)==32000000 && StreamProfile.bitrate(2)==40000000 && StreamProfile.bitrate(3)==60000000 && StreamProfile.bitrate(6)==6000000);
        check(StreamProfile.sanitize(9)==0);
        check(StreamProfile.needsLevel5(false,1920,1080,60000000));
        check(!StreamProfile.needsLevel5(true,1920,1080,60000000));
        check(StreamProfile.needsLevel5(true,1920,1080,63000000));
        check(!StreamProfile.needsLevel5(false,1920,1080,50000000));
        check(!StreamProfile.needsLevel5(false,1280,720,6000000));
        EncoderBacklogPolicy policy=new EncoderBacklogPolicy(); long now=10_000_000_000L;
        check(!policy.shouldDiscard((now-20_000_000)/1000,0,now));
        check(policy.shouldDiscard((now-100_000_000)/1000,0,now)); check(policy.isWaiting());
        check(!policy.shouldDiscard(0,2,now)); check(policy.isWaiting());
        check(policy.shouldDiscard((now-100_000_000)/1000,1,now)); // stale key frame rejected
        check(!policy.shouldDiscard(now/1000,1,now)); check(!policy.isWaiting());
        policy.requestRecovery(now); check(policy.shouldDiscard(now/1000,0,now));
        check(!policy.shouldDiscard(now/1000,1,now));
        EncoderBacklogPolicy otherClock=new EncoderBacklogPolicy();
        check(!otherClock.shouldDiscard(123,0,now)); // do not assume timestamp epoch
        otherClock.requestRecovery(now); check(!otherClock.shouldDiscard(123,1,now));
        EncoderBacklogPolicy intrinsicallySlow=new EncoderBacklogPolicy();
        check(!intrinsicallySlow.shouldDiscard((now-200_000_000)/1000,0,now));
        check(!intrinsicallySlow.shouldDiscard((now-200_000_000)/1000,0,now)); // do not permanently freeze slow hardware
        EncoderBacklogPolicy repeat=new EncoderBacklogPolicy();
        check(!repeat.shouldDiscard((now-20_000_000)/1000,0,now));
        repeat.requestRecovery(now); repeat.requestRecovery(now+150_000_000L);
        check(!repeat.shouldDiscard((now+10_000_000)/1000,1,now+160_000_000));
        check(!repeat.isWaiting());
        check(!repeat.shouldDiscard((now+50_000_000)/1000,0,now+200_000_000)); // new intrinsic baseline 150ms
        EncoderBacklogPolicy burst=new EncoderBacklogPolicy();
        check(!burst.shouldDiscard((now-20_000_000)/1000,1,now));
        burst.onWriteFinished(1,60_000_000L,now+60_000_000L); check(!burst.isWaiting());
        check(!burst.shouldDiscard((now-20_000_000)/1000,0,now+60_000_000)); // backlog caused by IDR alone is not a new recovery
        burst.onWriteFinished(0,8_000_000L,now+68_000_000L); check(!burst.isWaiting());
        burst.onWriteFinished(0,40_000_000L,now+108_000_000L); check(!burst.isWaiting());
        burst.onWriteFinished(0,40_000_000L,now+148_000_000L); check(burst.isWaiting());
        System.out.println("PASS: "+checks+" 1080p60 profile and GOP recovery assertions");
    }
}
