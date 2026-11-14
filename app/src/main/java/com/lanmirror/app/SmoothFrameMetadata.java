package com.lanmirror.app;

import java.util.LinkedHashMap;

/** Local codec tokens preserve original sender PTS and timestamps, never replacing end-to-end age. */
public final class SmoothFrameMetadata {
    public static final class Entry {
        public final long generation, sequence, sourcePtsUs, encodedAtNs, receivedAtNs, fedAtNs;
        public final boolean startup;
        public Entry(StreamFrame frame,long fedAtNs,boolean startup){
            generation=frame.generation;sequence=frame.sequence;sourcePtsUs=frame.ptsUs;
            encodedAtNs=frame.encodedAtNs;receivedAtNs=frame.receivedAtNs;this.fedAtNs=fedAtNs;this.startup=startup;
        }
    }
    private final int capacity;
    private final LinkedHashMap<Long,Entry> entries=new LinkedHashMap<>();
    public SmoothFrameMetadata(int capacity){if(capacity<=0)throw new IllegalArgumentException();this.capacity=capacity;}
    public void add(long token,Entry entry){
        if(entry==null || entries.containsKey(token))throw new IllegalArgumentException("Duplicate codec token");
        if(entries.size()>=capacity)throw new IllegalStateException("Codec metadata backlog exceeded bound");
        entries.put(token,entry);
    }
    public Entry remove(long token){return entries.remove(token);}
    public int size(){return entries.size();}
    public void clear(){entries.clear();}
}
