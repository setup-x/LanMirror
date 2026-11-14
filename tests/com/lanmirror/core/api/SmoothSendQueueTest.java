package com.lanmirror.core.api;

/** Smooth sender buffer loans/GOP boundaries. No Android codec or performance assertions. */
public final class SmoothSendQueueTest {
    private static int checks;
    private static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    private static StreamFrame frame(FrameBufferPool pool,long gen,long seq,boolean key,int length){byte[] b=pool.acquire(length);b[0]=(byte)seq;return new StreamFrame(gen,seq,seq*16667,key?1:0,1000000+seq,0,b,0,length,pool);}
    private static SmoothSendQueue.Entry poll(SmoothSendQueue q){try{return q.poll(0);}catch(InterruptedException e){throw new AssertionError(e);}}
    private static void config(SmoothSendQueue q){q.begin(1920,1080);q.addConfig(new byte[]{0,0,0,1,0x67,1,2,3,4});q.addConfig(new byte[]{0,0,0,1,0x68,1});}
    public static void main(String[]args){
        FrameBufferPool pool=new FrameBufferPool(4*1024*1024,16*1024*1024,64);SmoothSendQueue q=new SmoothSendQueue(12,8*1024*1024);config(q);long gen=q.generation();
        check(q.depth()==0&&q.consumeKeyRequest(),"No prebuffer delay; ask initial IDR");
        check(!q.offer(frame(pool,gen,0,false,100)),"Initial P rejected");check(pool.retainedCount()==1,"Rejected loan recycled");
        StreamFrame key=frame(pool,gen,1,true,100);q.offer(key);SmoothSendQueue.Entry first=poll(q);
        check(first.bootstrap&&first.config.generation==gen&&first.config.csd.length==2,"Bootstrap carries complete reliable configuration");
        q.requestRecovery();check(!q.awaitingKey(),"In-flight bootstrap protected from repeat feedback");
        for(int i=0;i<12;i++)check(q.offer(frame(pool,gen,i+2,false,100)),"Twelve pending frames permitted");
        check(q.depth()==12&&q.queuedBytes()==1200,"Logical bytes not buffer capacities");
        check(!q.offer(frame(pool,gen,14,false,100)),"Overflow drops dependent GOP");
        check(q.depth()==0&&q.awaitingKey()&&q.overflows()==1,"Bounded overflow recovery");
        check(key.data[0]==1,"In-flight data remains valid");q.markSent(first);first.frame.release();
        long dropped=q.dropped();check(dropped==14,"Initial P plus 12 pending plus overflow P counted");
        q.offer(frame(pool,gen,15,true,100));SmoothSendQueue.Entry recovered=poll(q);q.markSent(recovered);recovered.frame.release();
        for(int i=0;i<120;i++){
            StreamFrame f=frame(pool,gen,16+i,false,100);check(q.offer(f),"Continuous feed accepted");SmoothSendQueue.Entry e=poll(q);
            check(e.frame.encodedAtNs==1000000+16+i&&e.frame.length==100,"Original stamp/logical length preserved");q.markSent(e);e.frame.release();
        }
        check(pool.allocations()<=14&&pool.reuseCount()>=120,"Retained allocations reused across steady stream");
        q.requestRecovery();long recovery=q.recoveries();q.requestRecovery();check(q.recoveries()==recovery,"Repeated request idempotent");
        q.offer(frame(pool,gen,200,true,100));SmoothSendQueue.Entry old=poll(q);
        q.addConfig(new byte[]{0,0,0,1,0x68,2});check(q.generation()>gen&&q.awaitingKey(),"New CSD snapshot creates new ACK generation");
        q.markSent(old);old.frame.release();check(!q.offer(frame(pool,gen,201,true,100)),"Stale generation rejected/released");
        long newGen=q.generation();q.offer(frame(pool,newGen,202,true,100));SmoothSendQueue.Entry latest=poll(q);
        check(latest.config.csd.length==3&&latest.config.width==1920,"New snapshot is immutable independent config");
        q.close();check(latest.frame.length==100,"Closing queue does not release writer loan");latest.frame.release();
        check(!q.offer(frame(pool,newGen,203,true,100)),"Late offered loan rejected safely");
        pool.close();check(pool.retainedCount()==0,"Pool close releases retained storage");
        FrameBufferPool small=new FrameBufferPool(2048,4096,4);SmoothSendQueue bytes=new SmoothSendQueue(12,150);config(bytes);
        bytes.offer(frame(small,bytes.generation(),0,true,100));check(!bytes.offer(frame(small,bytes.generation(),1,false,100)),"Byte limit independent of frame limit");
        check(bytes.depth()==0&&bytes.overflows()==1,"Byte overflow recovers whole pending GOP");bytes.close();small.close();
        System.out.println("PASS: "+checks+" smooth send queue assertions (12-frame/byte bounds, pooled loans, generations/ACK snapshots, GOP-safe recovery; NOT FPS)");
    }
}
