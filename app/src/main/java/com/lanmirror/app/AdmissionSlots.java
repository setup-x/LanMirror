package com.lanmirror.app;

import java.util.*;

/** Pure-Java admission state; never calls listeners, sockets, Surface or codecs under this monitor. */
public final class AdmissionSlots {
    public enum Phase { READING, PENDING, ACTIVE, REJECTED }
    public static final class Snapshot {
        public final long id,deadlineNs; public final Phase phase; public final int slot; public final Boolean choice; public final String reason;
        Snapshot(Ticket t){id=t.id;deadlineNs=t.deadlineNs;phase=t.phase;slot=t.slot;choice=t.choice;reason=t.reason;}
    }
    private static final class Ticket {
        final long id; long deadlineNs; Phase phase=Phase.READING;int slot=-1;Boolean choice;String reason="";
        Ticket(long id){this.id=id;}
    }
    public static final long APPROVAL_NS=30_000_000_000L;
    private final int maxPending,maxAccepted;private final long[] slots;
    private final Map<Long,Ticket> tickets=new HashMap<>();private boolean closed;private int pending,active;
    public AdmissionSlots(int maxActive,int maxPending,int maxAccepted){if(maxActive<1||maxPending<1||maxAccepted<maxActive)throw new IllegalArgumentException("Invalid limits");slots=new long[maxActive];Arrays.fill(slots,-1);this.maxPending=maxPending;this.maxAccepted=maxAccepted;}
    public synchronized boolean register(long id){if(id<0||closed||tickets.size()>=maxAccepted||tickets.containsKey(id))return false;tickets.put(id,new Ticket(id));return true;}
    public synchronized Snapshot beginPending(long id,long now){Ticket t=require(id);if(t.phase!=Phase.READING)return new Snapshot(t);if(closed)reject(t,"接收端已关闭");else if(active>=slots.length)reject(t,"四路已满，请稍后重试");else if(pending>=maxPending)reject(t,"待确认请求已满，请稍后重试");else{t.phase=Phase.PENDING;t.deadlineNs=now+APPROVAL_NS;pending++;}notifyAll();return new Snapshot(t);}
    /** UI-safe first decision wins. Slot/Surface inspection is deferred to the peer owner. */
    public synchronized boolean choose(long id,boolean allow){Ticket t=tickets.get(id);if(t==null||closed||t.phase!=Phase.PENDING||t.choice!=null)return false;t.choice=allow;if(!allow)reject(t,"接收端拒绝了连接");notifyAll();return true;}
    public synchronized Snapshot expire(long id,long now){Ticket t=tickets.get(id);if(t==null)return null;if(t.phase==Phase.PENDING&&now-t.deadlineNs>=0){reject(t,"接收端确认超时（30秒）");notifyAll();}return new Snapshot(t);}
    public synchronized Snapshot awaitChoice(long id)throws InterruptedException{
        for(;;){Snapshot s=expire(id,System.nanoTime());if(s==null||s.phase!=Phase.PENDING||s.choice!=null)return s;long left=s.deadlineNs-System.nanoTime();if(left>0)wait(left/1000000L,(int)(left%1000000L));}
    }
    /** One atomic allocation resolves peers racing for the final free valid Surface slot. */
    public synchronized Snapshot admit(long id,boolean[] validSurfaces,long now){
        Ticket t=require(id);expire(id,now);if(t.phase!=Phase.PENDING||!Boolean.TRUE.equals(t.choice))return new Snapshot(t);
        if(closed){reject(t,"接收端已关闭");return new Snapshot(t);}
        if(validSurfaces==null||validSurfaces.length!=slots.length)throw new IllegalArgumentException("Surface availability size");
        int free=-1;for(int i=0;i<slots.length;i++)if(slots[i]<0&&validSurfaces[i]){free=i;break;}
        if(free<0){reject(t,active>=slots.length?"四路已满，请稍后重试":"显示区域尚未就绪，请稍后重试");}
        else {pending--;active++;t.phase=Phase.ACTIVE;t.slot=free;slots[free]=id;}
        notifyAll();return new Snapshot(t);
    }
    public synchronized Snapshot get(long id){Ticket t=tickets.get(id);return t==null?null:new Snapshot(t);}
    public synchronized boolean owns(int slot,long id){return slot>=0&&slot<slots.length&&slots[slot]==id;}
    public synchronized long owner(int slot){return slot<0||slot>=slots.length?-1:slots[slot];}
    /** Ends admission immediately but keeps the active slot reserved until peer-owned native teardown. */
    public synchronized boolean cancel(long id,String reason){Ticket t=tickets.get(id);if(t==null)return false;if(t.phase==Phase.PENDING||t.phase==Phase.READING)reject(t,reason);else if(t.reason.isEmpty())t.reason=reason;notifyAll();return true;}
    public synchronized void release(long id){Ticket t=tickets.remove(id);if(t==null)return;if(t.phase==Phase.PENDING)pending--;if(t.slot>=0&&slots[t.slot]==id){slots[t.slot]=-1;active--;}notifyAll();}
    public synchronized void close(){if(closed)return;closed=true;for(Ticket t:tickets.values()){if(t.phase==Phase.PENDING||t.phase==Phase.READING)reject(t,"接收端已关闭");else if(t.reason.isEmpty())t.reason="接收端已关闭";}notifyAll();}
    private void reject(Ticket t,String reason){if(t.phase==Phase.PENDING)pending--;t.phase=Phase.REJECTED;t.reason=reason;}
    private Ticket require(long id){Ticket t=tickets.get(id);if(t==null)throw new IllegalArgumentException("Unknown request");return t;}
    public synchronized int activeCount(){return active;}public synchronized int pendingCount(){return pending;}public synchronized int acceptedCount(){return tickets.size();}public synchronized boolean isClosed(){return closed;}
}
