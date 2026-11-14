package com.lanmirror.core.api;

/** JVM-only ownership and GOP tests; does not run Android MediaCodec. */
public final class SmoothDecodeQueueTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static FrameBufferPool pool(){return new FrameBufferPool(1024*1024,8*1024*1024,32);}
    private static StreamFrame frame(FrameBufferPool pool,long generation,long seq,boolean key,int length){
        byte[] data=pool.acquire(length+13);data[13]=(byte)seq;
        return new StreamFrame(generation,seq,777,key?1:0,1000+seq,2000+seq,data,13,length,pool);
    }
    public static void main(String[]args){boundsAndRecovery();ownershipAndGeneration();metadata();System.out.println("PASS: "+checks+" smooth decode queue/metadata assertions (12-frame bounds, GOP, ownership, generations, real PTS/stamps; NOT codec FPS)");}
    private static void boundsAndRecovery(){
        FrameBufferPool pool=pool();SmoothDecodeQueue q=new SmoothDecodeQueue(12,8*1024*1024);q.beginGeneration(1);
        check(q.waitingForKey()&&q.consumeSyncRequest()&&!q.consumeSyncRequest(),"Fresh generation waits key; sync coalesced");
        check(!q.offer(frame(pool,1,0,false,100)),"Initial P cannot decode");
        long drops=q.droppedFrames();
        check(q.offer(frame(pool,1,1,true,100)),"First key");
        for(int i=2;i<=12;i++)check(q.offer(frame(pool,1,i,false,100)),"Twelve-frame capacity");
        check(q.depth()==12 && q.queuedBytes()==1200,"Logical bytes not pooled capacity");
        check(!q.offer(frame(pool,1,13,false,100)),"Overflow P safely rejected");
        check(q.depth()==0&&q.waitingForKey()&&q.droppedFrames()==drops+13,"Whole pending GOP discarded");
        check(q.overflowEvents()==1&&q.recoveryCount()==1,"Overflow recovery counted");
        check(!q.offer(frame(pool,1,14,false,100)),"Later dependent P rejected");
        check(q.offer(frame(pool,1,15,true,100)),"New IDR restarts");
        StreamFrame got=q.poll();check(got.sequence==15 && got.offset==13 && got.length==100 && got.data[13]==15,"Offset and true sequence preserved");got.release();
        check(q.offer(frame(pool,1,16,false,100)),"P after restart accepted");q.discontinuity();check(q.waitingForKey()&&q.depth()==0,"Gap clears references without codec flush");
        q.close();check(q.isClosed()&&q.queuedBytes()==0,"Close clears queued bytes");pool.close();
        pool=pool();q=new SmoothDecodeQueue(12,250);q.beginGeneration(2);
        check(q.offer(frame(pool,2,0,true,100))&&q.offer(frame(pool,2,1,false,100)),"Byte capacity setup");
        check(!q.offer(frame(pool,2,2,false,100))&&q.depth()==0,"Byte bound causes GOP recovery before count bound");
        check(!q.offer(frame(pool,2,3,true,251)),"Oversize key cannot bypass bound");q.close();pool.close();
    }
    private static void ownershipAndGeneration(){
        FrameBufferPool pool=pool();SmoothDecodeQueue q=new SmoothDecodeQueue(12,8*1024*1024);q.beginGeneration(4);
        StreamFrame held=frame(pool,4,0,true,100);held.retain();q.offer(held);StreamFrame polled=q.poll();
        int retained=pool.retainedCount();q.close();check(pool.retainedCount()==retained,"Closing queue cannot return polled owner-held lease");
        polled.release();check(pool.retainedCount()==retained,"Extra retain keeps data alive");held.release();check(pool.retainedCount()==retained+1,"Final ref returns lease");
        q=new SmoothDecodeQueue(12,8*1024*1024);q.beginGeneration(5);StreamFrame old=frame(pool,5,1,true,100);q.offer(old);int before=pool.retainedCount();
        q.beginGeneration(6);check(pool.retainedCount()==before+1,"Generation switch returns queued old lease");
        check(!q.offer(frame(pool,5,2,true,100)),"Stale generation rejected");check(q.offer(frame(pool,6,3,true,100)),"Current generation key accepted");
        StreamFrame current=q.poll();q.close();current.release();
        int cached=pool.retainedCount();check(!q.offer(frame(pool,6,4,true,100))&&pool.retainedCount()==cached,"Offer to closed queue returns lease");
        SmoothDecodeQueue configs=new SmoothDecodeQueue(12,1000);configs.beginGeneration(1);byte[] data=pool.acquire(20);
        StreamFrame csd=new StreamFrame(1,0,0,3,0,0,data,0,20,pool);check(!configs.offer(csd)&&configs.waitingForKey(),"Codec config with key bit is not an IDR");configs.close();pool.close();
    }
    private static void metadata(){
        FrameBufferPool pool=pool();SmoothFrameMetadata m=new SmoothFrameMetadata(256);
        for(int i=0;i<256;i++){
            StreamFrame f=frame(pool,7,i,i==0,100);m.add(i+1,new SmoothFrameMetadata.Entry(f,3000+i,i==0));f.release();
        }
        check(m.size()==256,"Metadata bounded at declared capacity");
        StreamFrame f=frame(pool,7,256,false,100);
        try{m.add(257,new SmoothFrameMetadata.Entry(f,4000,false));throw new AssertionError("expected full");}catch(IllegalStateException expected){checks++;}finally{f.release();}
        for(int i=0;i<256;i++){
            SmoothFrameMetadata.Entry e=m.remove(i+1);check(e.sequence==i&&e.sourcePtsUs==777&&e.encodedAtNs==1000+i&&e.receivedAtNs==2000+i&&e.fedAtNs==3000+i,"Unique token preserves repeated sourcePTS and true timestamps");
        }
        check(m.size()==0&&m.remove(1)==null,"Consumed metadata removed");
        f=frame(pool,7,0,true,100);m.add(1,new SmoothFrameMetadata.Entry(f,999,true));
        try{m.add(1,new SmoothFrameMetadata.Entry(f,1000,false));throw new AssertionError("expected duplicate");}catch(IllegalArgumentException expected){checks++;}finally{f.release();}
        m.clear();check(m.size()==0,"Reconfiguration clears token map");pool.close();
    }
}
