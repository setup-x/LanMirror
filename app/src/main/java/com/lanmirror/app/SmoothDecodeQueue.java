package com.lanmirror.app;

import java.util.ArrayDeque;

/** Takes exactly one StreamFrame reference on every offer, including rejection. */
public final class SmoothDecodeQueue implements AutoCloseable {
    private final int maxFrames, maxBytes;
    private final ArrayDeque<StreamFrame> frames = new ArrayDeque<>();
    private int bytes;
    private long generation = Long.MIN_VALUE, drops, recoveries, overflowEvents;
    private boolean closed, waitingKey = true, syncNeeded;
    public SmoothDecodeQueue(int maxFrames, int maxBytes) {
        if (maxFrames <= 0 || maxBytes <= 0) throw new IllegalArgumentException("Positive bounds required");
        this.maxFrames=maxFrames; this.maxBytes=maxBytes;
    }
    public synchronized void beginGeneration(long next) {
        if (closed) return;
        clearPending(); generation=next; waitingKey=true; syncNeeded=true;
    }
    public synchronized boolean offer(StreamFrame frame) {
        if (frame == null) throw new IllegalArgumentException("Missing frame");
        boolean accepted=false;
        try {
            if (closed || frame.generation != generation) { drops++; return false; }
            if (frame.offset < 0 || frame.length <= 0 || (long)frame.offset+frame.length>frame.data.length
                    || (frame.flags & 2)!=0) { drops++; return false; }
            if (frame.length > maxBytes) { recover(); drops++; return false; }
            if (frames.size() >= maxFrames || (long)bytes+frame.length>maxBytes) { overflowEvents++; recover(); }
            if (waitingKey && !frame.keyFrame()) { drops++; return false; }
            if (frame.keyFrame()) waitingKey=false;
            frames.addLast(frame); bytes+=frame.length; accepted=true; return true;
        } finally { if (!accepted) frame.release(); }
    }
    public synchronized StreamFrame poll() {
        StreamFrame frame=frames.pollFirst(); if(frame!=null)bytes-=frame.length; return frame;
    }
    /** A known missing encoded input invalidates pending dependencies, never already-decoded outputs. */
    public synchronized void discontinuity() { if(!closed) recover(); }
    private void recover() {
        if(!waitingKey || !frames.isEmpty())recoveries++;
        clearPending(); waitingKey=true; syncNeeded=true;
    }
    private void clearPending() { StreamFrame frame; while((frame=frames.pollFirst())!=null){drops++;frame.release();}bytes=0; }
    public synchronized void recordRejectedInput() { drops++; if(!closed)recover(); }
    public synchronized boolean consumeSyncRequest(){boolean value=syncNeeded;syncNeeded=false;return value;}
    public synchronized boolean waitingForKey(){return waitingKey;}
    public synchronized int depth(){return frames.size();}
    public synchronized int queuedBytes(){return bytes;}
    public synchronized long droppedFrames(){return drops;}
    public synchronized long recoveryCount(){return recoveries;}
    public synchronized long overflowEvents(){return overflowEvents;}
    public synchronized boolean isClosed(){return closed;}
    public synchronized long generation(){return generation;}
    @Override public synchronized void close(){if(closed)return;closed=true;clearPending();syncNeeded=false;}
}
