package com.lanmirror.app;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;

/** Bounded cache, not a bound on all checked-out memory. Caller owns each acquired array. */
public final class FrameBufferPool implements AutoCloseable {
    private final int maxArrayBytes,maxRetainedBytes,maxBuffers;
    private final HashMap<Integer,ArrayDeque<byte[]>> buckets=new HashMap<>();
    private final IdentityHashMap<byte[],Boolean> cached=new IdentityHashMap<>();
    private int bytes; private long allocations,reuses; private boolean closed;
    public FrameBufferPool(int maxArrayBytes,int maxRetainedBytes,int maxBuffers){
        if(maxArrayBytes<=0 || maxRetainedBytes<0 || maxBuffers<0)throw new IllegalArgumentException("Invalid pool limits");
        this.maxArrayBytes=maxArrayBytes;this.maxRetainedBytes=maxRetainedBytes;this.maxBuffers=maxBuffers;
    }
    private int capacity(int size){int n=Math.min(1024,maxArrayBytes);while(n<size)n=(int)Math.min((long)maxArrayBytes,(long)n*2);return n;}
    public synchronized byte[] acquire(int min){
        if(closed)throw new IllegalStateException("Pool closed");if(min<0 || min>maxArrayBytes)throw new IllegalArgumentException("Array size limit");
        int n=capacity(min);ArrayDeque<byte[]> q=buckets.get(n);
        if(q!=null&&!q.isEmpty()){byte[] b=q.removeFirst();cached.remove(b);bytes-=b.length;reuses++;return b;}
        allocations++;return new byte[n];
    }
    public synchronized void recycle(byte[] b){
        if(b==null||b.length==0||closed||cached.containsKey(b))return;
        if(b.length>maxArrayBytes||capacity(b.length)!=b.length||cached.size()>=maxBuffers||(long)bytes+b.length>maxRetainedBytes)return;
        ArrayDeque<byte[]> q=buckets.get(b.length);if(q==null){q=new ArrayDeque<>();buckets.put(b.length,q);}q.addLast(b);cached.put(b,Boolean.TRUE);bytes+=b.length;
    }
    public synchronized long allocations(){return allocations;}
    public synchronized long reuseCount(){return reuses;}
    public synchronized int retainedBytes(){return bytes;}
    public synchronized int retainedCount(){return cached.size();}
    public synchronized boolean isClosed(){return closed;}
    public synchronized void close(){closed=true;buckets.clear();cached.clear();bytes=0;}
}
