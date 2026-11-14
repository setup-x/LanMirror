package com.lanmirror.discovery.internal;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Pure Java lifecycle/bounds tests; does not emulate Android NSD or prove multicast reachability. */
public final class DiscoveryRegistryTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static DiscoveryRegistry.Entry entry(String id,String name,int n){return new DiscoveryRegistry.Entry(id,name,"192.168.1."+(n%250+1),7239,4);}
    private static void invalid(Runnable call){boolean rejected=false;try{call.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Invalid discovery entry must be rejected");}
    public static void main(String[]args)throws Exception{
        DiscoveryRegistry registry=new DiscoveryRegistry();
        check(registry.found("before-start")==null,"No resolve ticket before discovery begins");
        long firstGeneration=registry.begin();check(registry.active(),"Begin activates registry");
        DiscoveryRegistry.Ticket alpha=registry.found("alpha");check(alpha!=null,"First appearance gets ticket");
        check(registry.found("alpha")==null&&registry.pendingCount()==1,"Repeated found does not enqueue another resolve");
        check(registry.resolved(alpha,entry("id-a","Beta",1)),"Current resolve accepted");
        check(!registry.resolved(alpha,entry("id-a","Changed",2)),"Duplicate resolve terminal callback ignored");
        check(registry.snapshot().size()==1&&registry.snapshot().get(0).port==7239,"Resolved real advertised port retained");
        List<DiscoveryRegistry.Entry> snapshot=registry.snapshot();
        boolean immutable=false;try{snapshot.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"Snapshot unmodifiable");
        DiscoveryRegistry.Ticket other=registry.found("other");registry.resolved(other,new DiscoveryRegistry.Entry("id-b","Alpha","fe80::1234%wlan0",45678,2));
        check(registry.snapshot().get(0).id.equals("id-b"),"Stable name sorting");
        check(registry.snapshot().get(0).host.equals("fe80::1234%wlan0")&&registry.snapshot().get(0).port==45678,"IPv6 scope and discovered nondefault port retained");
        check(snapshot.size()==1&&snapshot.get(0).name.equals("Beta"),"Previously published snapshot cannot change underneath UI");
        DiscoveryRegistry.Ticket sameId=registry.found("alpha-network2");registry.resolved(sameId,entry("id-a","Newest",3));
        check(registry.snapshot().size()==2,"Installation id deduplicates alternate service appearance");
        check(registry.snapshot().get(1).name.equals("Newest"),"Newest appearance wins duplicate installation");
        check(registry.lost("alpha-network2"),"Lost alternate appearance removed");
        check(registry.snapshot().get(1).name.equals("Beta"),"Losing newest appearance restores still-live duplicate");
        DiscoveryRegistry.Ticket late=registry.found("late");check(registry.lost("late"),"Lost pending service removed");
        check(!registry.resolved(late,entry("late-id","Late",4)),"Resolution after serviceLost cannot resurrect device");
        DiscoveryRegistry.Ticket refound=registry.found("late");check(refound!=late,"Same name discovered again gets distinct ticket");
        check(!registry.failed(late)&&registry.isCurrent(refound),"Old failure cannot cancel replacement ticket");
        check(!registry.resolved(late,entry("old","Old",5)),"Old success cannot replace newer service appearance");
        check(registry.resolved(refound,entry("new","New",6)),"Replacement ticket resolves normally");
        DiscoveryRegistry.Ticket stopped=registry.found("stop-pending");registry.stop();
        check(!registry.active()&&registry.pendingCount()==0&&registry.serviceCount()==0&&registry.snapshot().isEmpty(),"Stop clears all state and snapshots");
        check(!registry.resolved(stopped,entry("after-stop","After stop",7)),"Stopped resolve callback discarded");
        check(registry.found("while-stopped")==null,"Stop prevents queued discovery callbacks from starting work");
        long nextGeneration=registry.begin();check(nextGeneration>firstGeneration,"Restart has new generation");
        check(!registry.isCurrent(alpha)&&!registry.resolved(alpha,entry("oldgen","Wrong",8)),"Old generation callback discarded");
        DiscoveryRegistry.Ticket failure=registry.found("failed");check(registry.failed(failure),"Resolve failure removes pending request");
        check(registry.pendingCount()==0&&!registry.failed(failure),"Repeated failure does not underflow pending count");
        check(registry.found("after-failure")!=null,"Failure does not block subsequent resolutions");
        registry.stop();registry.begin();
        DiscoveryRegistry.Ticket[] pending=new DiscoveryRegistry.Ticket[64];
        for(int i=0;i<64;i++){pending[i]=registry.found("pending-"+i);check(pending[i]!=null,"Accept up to 64 outstanding resolutions");}
        check(registry.pendingCount()==64&&registry.found("pending-overflow")==null,"Pending work bounded at 64");
        for(int i=0;i<64;i++){
            boolean accepted=registry.resolved(pending[i],entry("unique-"+i,"Receiver "+i,i));
            check(accepted==(i<32),"Only 32 distinct devices become visible");
            check(registry.snapshot().size()<=32&&registry.pendingCount()<=64,"Every completion respects both bounds");
        }
        check(registry.pendingCount()==0&&registry.snapshot().size()==32&&registry.serviceCount()==32,"Overflow resolutions clean up pending records");
        for(int i=0;i<64;i++){
            DiscoveryRegistry.Ticket t=registry.found("duplicate-"+i);check(t!=null,"Alternative services fit bounded record capacity");
            check(registry.resolved(t,entry("unique-0","Duplicate "+i,i)),"Duplicate installation does not increase result count");
        }
        check(registry.serviceCount()==96&&registry.snapshot().size()==32,"Physical service records bounded even under duplicate-id flood");
        check(registry.found("service-overflow")==null,"Record cap prevents duplicate-service memory growth");
        check(registry.lost("duplicate-0")&&registry.found("freed-record")!=null,"Loss makes bounded capacity reusable");
        registry.stop();registry.begin();
        for(int i=0;i<100;i++){
            DiscoveryRegistry.Ticket t=registry.found("flapping");check(t!=null,"Repeated found/lost does not retain unbounded tombstones");
            registry.lost("flapping");check(!registry.resolved(t,entry("flap","Flap",i)),"Every flapping late resolve rejected");
        }
        check(registry.pendingCount()==0&&registry.serviceCount()==0,"Flapping cleanup returns empty state");
        check(registry.found(null)==null&&registry.found("")==null&&registry.found(new String(new char[513]).replace('\0','x'))==null,"Untrusted service keys have limits");
        invalid(()->new DiscoveryRegistry.Entry("", "name","host",7239,4));
        invalid(()->new DiscoveryRegistry.Entry("id", " ","host",7239,4));
        invalid(()->new DiscoveryRegistry.Entry("id", "name",null,7239,4));
        invalid(()->new DiscoveryRegistry.Entry("id", "name","host",0,4));
        invalid(()->new DiscoveryRegistry.Entry("id", "name","host",65536,4));
        invalid(()->new DiscoveryRegistry.Entry("id", "name","host",7239,0));
        invalid(()->new DiscoveryRegistry.Entry("id", "name","host",7239,5));
        invalid(()->new DiscoveryRegistry.Entry(new String(new char[129]).replace('\0','x'), "name","host",7239,4));
        AtomicReference<Throwable> failureRef=new AtomicReference<>();Thread[] workers=new Thread[4];
        for(int n=0;n<workers.length;n++){
            final int worker=n;workers[n]=new Thread(()->{
                try{for(int i=0;i<500;i++){
                    String key="thread-"+worker+"-"+i;DiscoveryRegistry.Ticket t=registry.found(key);
                    if(t!=null)registry.resolved(t,entry("id-"+worker,"Worker "+worker,worker));
                    if(registry.snapshot().size()>32||registry.pendingCount()>64||registry.serviceCount()>96)throw new AssertionError("Concurrent bounds violated");
                    registry.lost(key);
                }}catch(Throwable e){failureRef.compareAndSet(null,e);}
            },"discovery-registry-test-"+n);workers[n].start();
        }
        for(Thread worker:workers){worker.join(5000);check(!worker.isAlive(),"Concurrent registry operations terminate");}
        check(failureRef.get()==null,"Concurrent lookup/loss/snapshot preserves state");
        registry.stop();check(registry.snapshot().isEmpty()&&registry.pendingCount()==0,"Final stop clean");
        System.out.println("PASS: "+assertions+" discovery registry assertions (add/loss/dedup, 32/64 bounds, generation/late resolve, stop, immutable snapshots; NOT Android mDNS hardware validation)");
    }
}
