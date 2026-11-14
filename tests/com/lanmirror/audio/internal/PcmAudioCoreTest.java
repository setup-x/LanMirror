package com.lanmirror.audio.internal;

import com.lanmirror.core.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Pure logic tests; not AudioRecord/AudioTrack, app capture eligibility, hardware latency or lip sync tests. */
public final class PcmAudioCoreTest {
    private static final AtomicInteger checks=new AtomicInteger();
    private static void check(boolean ok,String message){checks.incrementAndGet();if(!ok)throw new AssertionError(message);}
    private interface Work{void run();}
    private static void bad(Work work){boolean failed=false;try{work.run();}catch(IllegalArgumentException|IllegalStateException e){failed=true;}check(failed,"Invalid operation rejected");}
    private static PcmAudioFrame frame(int bytes){return new PcmAudioFrame(1,1_000_000_000L,new byte[bytes],bytes);}
    public static void main(String[] args)throws Exception{
        frameCases();queueCases();focusCases();leaseCases();concurrentQueue();
        System.out.println("PASS: "+checks.get()+" PCM core assertions (aligned frames/copy ownership, 6-frame/120ms bounds, capture age, selection generations, close, focus and confirmed-native leases; NOT Android audio or A/V sync)");
    }
    private static void frameCases(){
        check(PcmAudioFrame.SAMPLE_RATE==48000&&PcmAudioFrame.CHANNELS==2&&PcmAudioFrame.BYTES_PER_SAMPLE==2,"PCM format contract");
        check(PcmAudioFrame.MAX_BYTES==3840&&PcmAudioFrame.FRAME_NS==20_000_000,"20ms frame bytes/nanoseconds");
        byte[] bytes={1,2,3,4,9,9,9,9};PcmAudioFrame f=new PcmAudioFrame(7,-100,bytes,4);bytes[0]=8;
        check(f.length==4&&f.data.length==4&&f.data[0]==1&&f.sequence==7&&f.capturedAtNs==-100,"Input copied exactly; nanoTime signed origin allowed");
        check(f.sampleFrames()==1&&f.durationNs()==20833,"One stereo sample frame duration");
        for(int n=4;n<=3840;n+=4){PcmAudioFrame p=frame(n);check(p.sampleFrames()==n/4,"Partial frame sample count");check(p.durationNs()<=20_000_000&&p.durationNs()>0,"Partial frame bounded duration");}
        check(frame(3840).durationNs()==20_000_000,"960sampleFrames exactly20ms");
        for(int n:new int[]{0,1,2,3,5,3839,3841,3844})bad(()->new PcmAudioFrame(0,0,new byte[4000],n));
        bad(()->new PcmAudioFrame(-1,0,new byte[4],4));bad(()->new PcmAudioFrame(1,0,null,4));bad(()->new PcmAudioFrame(0,0,new byte[4],8));
    }
    private static void queueCases(){
        PcmAudioQueue q=new PcmAudioQueue();long now=1_000_000_000L;PcmAudioFrame f=frame(3840);
        check(!q.offer(1,f,now,now)&&q.size()==0,"Default mute does not buffer");long g=q.select(1);check(q.selectedSource()==1,"Source selected immediately");
        for(int i=0;i<10;i++){check(q.offer(1,new PcmAudioFrame(i,now,f.data,f.length),now,now),"Offer is nonblocking");check(q.size()<=6&&q.bytes()<=23040,"Count/bytes always bounded");}
        check(q.size()==6&&q.bytes()==23040,"Exactly six retained full frames");PcmAudioQueue.Item old=q.poll(now);check(old.frame.sequence==4&&old.generation==g&&q.isCurrent(old),"Overflow drops oldest, not newest");
        check(!q.offer(2,f,now,now),"Unselected stream discarded");q.select(2);check(!q.isCurrent(old)&&q.size()==0,"Source switch clears queue and invalidates in-flight generation");
        q.offer(2,f,now,now);old=q.poll(now);q.select(2);check(!q.isCurrent(old),"Explicit same-source reselect invalidates old partial writes too");
        q.offer(2,f,now,now);check(q.poll(now+120_000_001L)==null,"Queued residency beyond120ms discarded");
        check(!q.offer(2,f,now-250_000_001L,now),"Old capture input rejected");check(!q.offer(2,f,now+250_000_001L,now),"Wild future timestamp rejected");
        check(q.offer(2,f,now-250_000_000L,now),"Age threshold boundary accepted");check(q.poll(now+1)==null,"Capture expiry enforced again before dequeue");
        check(PcmAudioQueue.tooOld(Long.MIN_VALUE,Long.MAX_VALUE)&&PcmAudioQueue.tooOld(Long.MAX_VALUE,Long.MIN_VALUE),"Timestamp subtraction overflow safely rejected");
        check(!PcmAudioQueue.tooOld(-1,0),"Small signed monotonic origin not treated as invalid");
        q.offer(2,f,now,now);long before=q.generation();q.clear();check(q.size()==0&&q.generation()==before&&q.selectedSource()==2,"Focus pause clears data without granting a fresh request generation");
        q.offer(2,f,now,now);old=q.poll(now);q.select(0);check(!q.isCurrent(old)&&q.size()==0&&!q.offer(2,f,now,now),"Mute invalidates in-flight and incoming PCM");
        q.select(3);q.offer(3,f,now,now);old=q.poll(now);q.close();q.close();check(!q.isCurrent(old)&&q.poll(now)==null&&!q.offer(3,f,now,now),"Close cancels both queued and in-flight state");q.select(3);check(q.selectedSource()==0,"Closed queue cannot resurrect");
        bad(()->q.select(-1));bad(()->q.offer(1,null,0,0));
    }
    private static void focusCases(){
        PcmAudioFocusState f=new PcmAudioFocusState();check(!f.canPlay(),"No focus before selection");f.changed(1);check(!f.canPlay(),"Stray gain cannot unmute");
        f.select(5);check(f.state()==PcmAudioFocusState.State.REQUESTABLE,"Selection permits one request");f.requestResult(false);check(!f.canPlay()&&f.state()==PcmAudioFocusState.State.DENIED,"Denied focus stays muted");
        f.changed(1);check(!f.canPlay(),"Denied request cannot be resurrected by stray gain");bad(()->f.requestResult(true));
        f.select(5);f.requestResult(true);check(f.canPlay(),"User reselect may explicitly retry");
        f.changed(-2);check(!f.canPlay()&&f.state()==PcmAudioFocusState.State.TRANSIENT,"Transient loss pauses");f.changed(1);check(f.canPlay(),"Gain after transient resumes without another request");
        f.changed(-3);check(!f.canPlay(),"Duck request chooses pause, not mixing");f.changed(1);check(f.canPlay(),"Duck gain can resume");
        f.changed(-1);check(!f.canPlay()&&f.state()==PcmAudioFocusState.State.LOST,"Permanent loss stops");f.changed(1);check(!f.canPlay(),"Permanent loss never auto-reacquires");bad(()->f.requestResult(true));
        f.select(0);check(!f.canPlay()&&f.state()==PcmAudioFocusState.State.MUTED,"Mute cancels focus model");f.changed(1);check(!f.canPlay(),"Late gain ignored after mute");bad(()->f.select(-1));
        for(int i=1;i<=20;i++){f.select(i);f.requestResult(true);f.changed(-2);f.changed(-1);f.changed(1);check(!f.canPlay(),"Loss following transient remains lost");}
    }
    private static void leaseCases()throws Exception{
        PcmNativeLease lease=new PcmNativeLease();PcmNativeLease.Ticket a=lease.tryAcquire();check(a!=null&&lease.occupied()&&lease.tryAcquire()==null,"Only one global native owner");
        a.releaseAfterNative(false);check(lease.tryAcquire()==null,"Native release error retains quarantine");a.releaseAfterNative(true);PcmNativeLease.Ticket b=lease.tryAcquire();check(b!=null,"Confirmed cleanup allows next owner");
        a.releaseAfterNative(true);check(lease.tryAcquire()==null,"Repeated old cleanup cannot release newer owner's lease");b.releaseAfterNative(true);check(!lease.occupied(),"New owner's confirmed release");
        CountDownLatch start=new CountDownLatch(1),done=new CountDownLatch(16);AtomicInteger winners=new AtomicInteger();AtomicReference<PcmNativeLease.Ticket> winner=new AtomicReference<>();
        for(int i=0;i<16;i++){Thread t=new Thread(()->{try{start.await();PcmNativeLease.Ticket ticket=lease.tryAcquire();if(ticket!=null){winner.set(ticket);winners.incrementAndGet();}}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{done.countDown();}});t.setDaemon(true);t.start();}
        start.countDown();check(done.await(3,TimeUnit.SECONDS),"Concurrent lease attempts finish");check(winners.get()==1,"Exactly one native owner under concurrent creation");winner.get().releaseAfterNative(true);
    }
    private static void concurrentQueue()throws Exception{
        PcmAudioQueue q=new PcmAudioQueue();q.select(1);PcmAudioFrame f=frame(3840);CountDownLatch done=new CountDownLatch(4);AtomicReference<Throwable> failure=new AtomicReference<>();
        for(int i=0;i<4;i++){final int role=i;Thread t=new Thread(()->{try{for(int step=0;step<1000;step++){
            long now=System.nanoTime();if(role==0)q.select(step%4);else{q.offer(role,f,now,now);if(step%3==0)q.poll(now);}
            check(q.size()<=6,"Concurrent frame bound");check(q.bytes()<=23040&&q.bytes()>=0,"Concurrent byte bound");
        }}catch(Throwable e){failure.compareAndSet(null,e);}finally{done.countDown();}});t.setDaemon(true);t.start();}
        check(done.await(5,TimeUnit.SECONDS),"Concurrent queue/select complete");q.close();if(failure.get()!=null)throw new AssertionError(failure.get());check(q.size()==0&&q.bytes()==0,"Concurrent close clears retained audio");
    }
}
