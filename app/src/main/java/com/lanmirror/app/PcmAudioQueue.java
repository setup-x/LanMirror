package com.lanmirror.app;

import java.util.ArrayDeque;

/** Short nonblocking queue, selected source + generation. Native operations NEVER run under its monitor. */
public final class PcmAudioQueue {
    public static final int MAX_FRAMES=6,MAX_BYTES=MAX_FRAMES*PcmAudioFrame.MAX_BYTES;
    public static final long MAX_QUEUED_NS=120_000_000L,MAX_AGE_NS=250_000_000L;
    public static final class Item {
        public final long sourceId,generation,localCapturedAtNs,enqueuedAtNs;
        public final PcmAudioFrame frame;
        private Item(long source,long generation,PcmAudioFrame frame,long stamp,long now){sourceId=source;this.generation=generation;this.frame=frame;localCapturedAtNs=stamp;enqueuedAtNs=now;}
    }
    private final ArrayDeque<Item> frames=new ArrayDeque<>();
    private long source,generation,dropped;private int bytes;private boolean closed;
    public synchronized long select(long sourceId){
        if(sourceId<0)throw new IllegalArgumentException("Negative source");
        if(closed)return generation;
        source=sourceId;generation++;clear();return generation;
    }
    public synchronized boolean offer(long sourceId,PcmAudioFrame frame,long localCapturedAtNs,long nowNs){
        if(frame==null)throw new IllegalArgumentException("Missing PCM frame");
        prune(nowNs);
        if(closed||sourceId<=0||sourceId!=source||tooOld(localCapturedAtNs,nowNs)){dropped++;return false;}
        while(frames.size()>=MAX_FRAMES||bytes+frame.length>MAX_BYTES)dropHead();
        frames.addLast(new Item(source,generation,frame,localCapturedAtNs,nowNs));bytes+=frame.length;return true;
    }
    public synchronized Item poll(long nowNs){prune(nowNs);Item item=frames.pollFirst();if(item!=null)bytes-=item.frame.length;return item;}
    public synchronized boolean isCurrent(Item item){return item!=null&&!closed&&source>0&&source==item.sourceId&&generation==item.generation;}
    public synchronized long selectedSource(){return source;}
    public synchronized long generation(){return generation;}
    public synchronized int size(){return frames.size();}
    public synchronized int bytes(){return bytes;}
    public synchronized long droppedFrames(){return dropped;}
    /** Clear pending samples without changing selection or focus retry eligibility. */
    public synchronized void clear(){dropped+=frames.size();frames.clear();bytes=0;}
    public synchronized void close(){if(closed)return;closed=true;source=0;generation++;clear();}
    private void prune(long now){while(!frames.isEmpty()){Item i=frames.peekFirst();long residence=now-i.enqueuedAtNs;if(residence<0||residence>MAX_QUEUED_NS||tooOld(i.localCapturedAtNs,now))dropHead();else break;}}
    private void dropHead(){Item i=frames.pollFirst();if(i!=null){bytes-=i.frame.length;dropped++;}}
    /** Allows small clock calibration skew, never seconds of future/old audio. */
    public static boolean tooOld(long stamp,long now){long age=now-stamp;return age>MAX_AGE_NS||age< -MAX_AGE_NS||(now>=stamp&&age<0)||(now<stamp&&age>=0);}
}
