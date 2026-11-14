package com.lanmirror.core.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Admission-only deterministic tests; no claim of actual multi-codec hardware availability. */
public final class AdmissionSlotsTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static boolean[] all(){return new boolean[]{true,true,true,true};}
    private static void pending(AdmissionSlots gate,long id,long now){check(gate.register(id),"register "+id);check(gate.beginPending(id,now).phase==AdmissionSlots.Phase.PENDING,"pending "+id);}
    private static AdmissionSlots.Snapshot approve(AdmissionSlots gate,long id,long now){check(gate.choose(id,true),"first allow accepted");return gate.admit(id,all(),now);}
    public static void main(String[]args)throws Exception {
        long now=1000000000L;AdmissionSlots gate=new AdmissionSlots(4,8,12);
        pending(gate,1,now);check(gate.choose(1,false),"deny accepted");check(!gate.choose(1,true),"late approval cannot undo denial");check(gate.get(1).phase==AdmissionSlots.Phase.REJECTED,"denied without active resource");check(gate.activeCount()==0&&gate.pendingCount()==0,"denial removes pending without allocating slot");gate.release(1);
        pending(gate,2,now);check(gate.expire(2,now+AdmissionSlots.APPROVAL_NS-1).phase==AdmissionSlots.Phase.PENDING,"before deadline pending");check(gate.expire(2,now+AdmissionSlots.APPROVAL_NS).phase==AdmissionSlots.Phase.REJECTED,"30s deadline exact");check(!gate.choose(2,true),"late decide cannot revive timeout");gate.release(2);
        pending(gate,3,now);check(gate.choose(3,true),"choice remembered");check(!gate.choose(3,false),"second decision ignored");check(gate.admit(3,all(),now+AdmissionSlots.APPROVAL_NS).phase==AdmissionSlots.Phase.REJECTED,"deadline applies before allocation even after choice");gate.release(3);
        pending(gate,4,now);check(gate.choose(4,true),"allow no Surface");AdmissionSlots.Snapshot noSurface=gate.admit(4,new boolean[4],now);check(noSurface.phase==AdmissionSlots.Phase.REJECTED&&gate.activeCount()==0,"invalid Surfaces allocate no slot");gate.release(4);
        for(long id=10;id<18;id++)pending(gate,id,now);
        check(gate.pendingCount()==8,"exactly eight dialogs maximum");check(gate.register(18),"ninth request can read bounded header");check(gate.beginPending(18,now).phase==AdmissionSlots.Phase.REJECTED,"ninth pending rejected");check(gate.pendingCount()==8,"pending count unchanged after cap");
        for(long id=10;id<19;id++)gate.release(id);check(gate.acceptedCount()==0,"pending close returns all leases");
        for(long id=30;id<42;id++)check(gate.register(id),"up to twelve accepted");check(!gate.register(42),"unparsed accepted cap twelve");check(!gate.register(30),"duplicate request id refused");for(long id=30;id<42;id++)gate.release(id);
        for(long id=100;id<104;id++){pending(gate,id,now);AdmissionSlots.Snapshot s=approve(gate,id,now);check(s.phase==AdmissionSlots.Phase.ACTIVE&&s.slot==id-100,"four slots allocated distinctly");}
        check(gate.activeCount()==4,"four concurrent approved slots");check(gate.register(104),"full manager still reads bounded request");AdmissionSlots.Snapshot full=gate.beginPending(104,now);check(full.phase==AdmissionSlots.Phase.REJECTED&&full.reason.contains("满"),"full rejects without evicting existing stream");gate.release(104);
        check(!gate.owns(0,101)&&gate.owns(0,100),"old/wrong slot id guarded");gate.cancel(100,"断开");check(gate.owns(0,100)&&gate.activeCount()==4,"cancel does not reuse Surface before owner cleanup");gate.release(100);gate.release(100);check(gate.activeCount()==3,"duplicate final release harmless");pending(gate,105,now);check(approve(gate,105,now).slot==0,"released slot reused");check(!gate.owns(0,100)&&gate.owns(0,105),"old disconnect id cannot match replacement");
        for(long id:new long[]{101,102,103,105})gate.release(id);
        // Race eight independently approved requests for four slots; no UI/native lock needed.
        for(int round=0;round<20;round++){
            AdmissionSlots racing=new AdmissionSlots(4,8,12);for(long id=1;id<=8;id++){pending(racing,id,now);check(racing.choose(id,true),"racing choice");}
            CountDownLatch ready=new CountDownLatch(8),start=new CountDownLatch(1);AtomicInteger winners=new AtomicInteger();Set<Integer> chosen=ConcurrentHashMap.newKeySet();AtomicReference<Throwable> failure=new AtomicReference<>();ArrayList<Thread> threads=new ArrayList<>();
            for(long id=1;id<=8;id++){final long request=id;Thread t=new Thread(()->{try{ready.countDown();start.await();AdmissionSlots.Snapshot s=racing.admit(request,all(),1000000001L);if(s.phase==AdmissionSlots.Phase.ACTIVE){winners.incrementAndGet();if(!chosen.add(s.slot))throw new AssertionError("duplicate slot");}}catch(Throwable error){failure.compareAndSet(null,error);}});threads.add(t);t.start();}
            if(!ready.await(2,TimeUnit.SECONDS))throw new AssertionError("worker startup");start.countDown();for(Thread t:threads)t.join(2000);
            check(failure.get()==null&&winners.get()==4&&chosen.size()==4,"concurrent atomic allocation exactly four winners");check(racing.pendingCount()==0&&racing.activeCount()==4,"all losing approvals rejected with no dangling pending");racing.close();for(long id=1;id<=8;id++)racing.release(id);check(racing.activeCount()==0&&racing.acceptedCount()==0,"close cleanup all racing permits");
        }
        // Close while awaiting UI approval wakes worker; no codec can be admitted after stop.
        AdmissionSlots closing=new AdmissionSlots(4,8,12);pending(closing,1,System.nanoTime());AtomicReference<AdmissionSlots.Snapshot> woke=new AtomicReference<>();Thread waiter=new Thread(()->{try{woke.set(closing.awaitChoice(1));}catch(InterruptedException e){throw new AssertionError(e);}});waiter.start();closing.close();waiter.join(1000);check(!waiter.isAlive()&&woke.get().phase==AdmissionSlots.Phase.REJECTED,"stop wakes pending waiter");check(!closing.choose(1,true)&&!closing.register(2),"closed manager blocks decisions and new accepts");closing.release(1);check(closing.pendingCount()==0&&closing.acceptedCount()==0,"close leaves no pending or accepted leases");
        for(int round=0;round<40;round++){
            AdmissionSlots closeRace=new AdmissionSlots(4,8,12);pending(closeRace,1,now);closeRace.choose(1,true);CountDownLatch go=new CountDownLatch(1);Thread contender=new Thread(()->{try{go.await();closeRace.admit(1,all(),1000000001L);}catch(InterruptedException ignored){}});contender.start();go.countDown();closeRace.close();contender.join(1000);check(closeRace.activeCount()<=1&&closeRace.pendingCount()==0,"approve/stop race bounded");closeRace.release(1);check(closeRace.activeCount()==0&&closeRace.acceptedCount()==0,"race cleanup returns all slots");
        }
        gate.close();check(!gate.register(500)&&!gate.choose(500,true),"closed/missing request ignored safely");
        System.out.println("PASS: "+checks+" admission assertions (4 slots, 8 pending, 12 accepted, timeout/deny/full, duplicate decisions, Surface readiness, stale ids, concurrent slot races and stop cleanup; NOT multi-codec FPS)");
    }
}
