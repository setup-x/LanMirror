package com.lanmirror.app;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Custom fragmented UDP for trusted LAN. NOT RTP, WebRTC, KCP, encryption, or authentication.
 * TCP supplies PIN/session/CONFIG+ACK. Source IP/port+random session only restrict pairing.
 */
public final class UdpVideoTransport {
    public static final int MAX_DATAGRAM=1200;
    public static final int DATA_HEADER=68,FRAGMENT_BYTES=MAX_DATAGRAM-DATA_HEADER;
    public static final long REASSEMBLY_NS=100_000_000L,CACHE_NS=120_000_000L;
    private static final int MAGIC=0x4c4d5531,VERSION=1,DATA=1,NACK=2,NACK_HEADER=36;
    private static final int MAX_ASSEMBLIES=8,MAX_ASSEMBLY_BYTES=8*1024*1024,MAX_CACHE_BYTES=4*1024*1024;
    private UdpVideoTransport(){}
    public interface Listener {
        /** Ownership transfers on invocation, including if implementation throws: release in caller's finally. */
        void onFrame(StreamFrame frame);
        void onGap();
    }
    private static int fragments(int length){return Math.max(1,(length+FRAGMENT_BYTES-1)/FRAGMENT_BYTES);}
    private static void prefix(ByteBuffer b,int type,long session){b.putInt(MAGIC).put((byte)VERSION).put((byte)type).putShort((short)0).putLong(session);}
    private static boolean prefixValid(ByteBuffer b,int type,long session){return b.getInt()==MAGIC&&(b.get()&255)==VERSION&&(b.get()&255)==type&&b.getShort()==0&&b.getLong()==session;}
    private static final class Key {
        final long gen,seq;Key(long gen,long seq){this.gen=gen;this.seq=seq;}
        public int hashCode(){return (int)(gen^(gen>>>32)^seq^(seq>>>32));}
        public boolean equals(Object o){return o instanceof Key&&((Key)o).gen==gen&&((Key)o).seq==seq;}
    }
    private static final class Cached {
        final StreamFrame frame;final boolean[] retried;final long at;long lastNack;
        Cached(StreamFrame frame,long at){this.frame=frame.retain();this.at=at;retried=new boolean[fragments(frame.length)];}
    }
    public static final class Sender implements AutoCloseable {
        private final DatagramSocket socket;private final InetSocketAddress peer;private final long session;
        private final Object lock=new Object();private final LinkedHashMap<Key,Cached> cache=new LinkedHashMap<>();
        private final byte[] sendBytes=new byte[MAX_DATAGRAM];private final ByteBuffer sendView=ByteBuffer.wrap(sendBytes);
        private final DatagramPacket outgoing;private final Thread feedback;
        private int cacheBytes;private volatile boolean closed;
        private final AtomicLong sent=new AtomicLong(),retransmitted=new AtomicLong();
        public Sender(DatagramSocket socket,InetSocketAddress peer,long session)throws SocketException{
            if(socket==null||peer==null||peer.getAddress()==null||peer.getPort()<1||session==0)throw new IllegalArgumentException("Invalid UDP sender pairing");
            this.socket=socket;this.peer=peer;this.session=session;socket.setSoTimeout(10); outgoing=new DatagramPacket(sendBytes,sendBytes.length,peer);
            feedback=new Thread(this::readFeedback,"smooth-udp-feedback");feedback.setDaemon(true);feedback.start();
        }
        /** Borrows caller reference only for duration; a separate retained reference backs retransmission cache. */
        public void send(StreamFrame frame)throws IOException{
            if(frame==null||frame.referenceCount()<=0||frame.codecConfig())throw new IOException("Invalid UDP video frame");
            synchronized(lock){
                if(closed)throw new IOException("UDP sender closed");long now=System.nanoTime();prune(now);
                Key key=new Key(frame.generation,frame.sequence);Cached previous=cache.remove(key);if(previous!=null)release(previous);
                int held=frame.data.length;
                while(!cache.isEmpty()&&(cache.size()>=8||(long)cacheBytes+held>MAX_CACHE_BYTES))removeOldest();
                if(held<=MAX_CACHE_BYTES){Cached item=new Cached(frame,now);cache.put(key,item);cacheBytes+=held;}
                for(int i=0,n=fragments(frame.length);i<n;i++)sendFragment(frame,i,false);
            }
        }
        private void sendFragment(StreamFrame frame,int index,boolean retry)throws IOException{
            int offset=index*FRAGMENT_BYTES,length=Math.min(FRAGMENT_BYTES,frame.length-offset);
            ByteBuffer b=sendView; b.clear();prefix(b,DATA,session);
            b.putLong(frame.generation).putLong(frame.sequence).putLong(frame.ptsUs).putLong(frame.encodedAtNs);
            b.putInt(frame.flags).putInt(frame.length).putInt(offset).putShort((short)index).putShort((short)fragments(frame.length)).putShort((short)length).putShort((short)0);
            System.arraycopy(frame.data,frame.offset+offset,sendBytes,DATA_HEADER,length);
            outgoing.setLength(DATA_HEADER+length);socket.send(outgoing);sent.incrementAndGet();if(retry)retransmitted.incrementAndGet();
        }
        private void readFeedback(){
            byte[] input=new byte[MAX_DATAGRAM+1];DatagramPacket packet=new DatagramPacket(input,input.length);
            while(!closed){
                try{packet.setLength(input.length);socket.receive(packet);if(!peer.getAddress().equals(packet.getAddress())||peer.getPort()!=packet.getPort())continue;
                    int length=packet.getLength();if(length<NACK_HEADER||length>NACK_HEADER+128)continue;
                    ByteBuffer b=ByteBuffer.wrap(input,0,length);if(!prefixValid(b,NACK,session))continue;
                    long gen=b.getLong(),seq=b.getLong();int count=b.getShort()&65535;if(b.getShort()!=0||count<1||count>64||length!=NACK_HEADER+count*2)continue;
                    int[] indices=new int[count];for(int i=0;i<count;i++)indices[i]=b.getShort()&65535;
                    synchronized(lock){long now=System.nanoTime();prune(now);Cached item=cache.get(new Key(gen,seq));if(item==null||closed)continue;
                        if(item.lastNack!=0&&now-item.lastNack<5_000_000L)continue;
                        boolean valid=true;for(int index:indices)if(index>=item.retried.length){valid=false;break;}if(!valid)continue;
                        item.lastNack=now;for(int index:indices){if(closed||System.nanoTime()-item.at>=CACHE_NS)break;if(!item.retried[index]){item.retried[index]=true;sendFragment(item.frame,index,true);}}
                    }
                }catch(SocketTimeoutException timeout){synchronized(lock){prune(System.nanoTime());}}
                catch(IOException failure){if(!closed){closed=true;socket.close();}break;}
                catch(RuntimeException failure){if(!closed){closed=true;socket.close();}break;}
            }
            synchronized(lock){clearCache();}
        }
        private void release(Cached c){cacheBytes-=c.frame.data.length;c.frame.release();}
        private void removeOldest(){Iterator<Cached> it=cache.values().iterator();if(it.hasNext()){Cached c=it.next();it.remove();release(c);}}
        private void prune(long now){Iterator<Cached> it=cache.values().iterator();while(it.hasNext()){Cached c=it.next();if(now-c.at>=CACHE_NS){it.remove();release(c);}}}
        private void clearCache(){for(Cached c:cache.values())c.frame.release();cache.clear();cacheBytes=0;}
        public long sentDatagrams(){return sent.get();}public long retransmittedDatagrams(){return retransmitted.get();}
        public void close(){closed=true;socket.close();synchronized(lock){clearCache();}if(Thread.currentThread()!=feedback)try{feedback.join(250);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    private static final class Assembly {
        final long gen,seq,pts,stamp,firstAt;final int flags,length,count;final byte[] data;final boolean[] present;
        int received,highest=-1,nackCount,nackCursor;long lastNack,completedAt;
        Assembly(long gen,long seq,long pts,long stamp,int flags,int length,int count,byte[] data,long now){this.gen=gen;this.seq=seq;this.pts=pts;this.stamp=stamp;this.flags=flags;this.length=length;this.count=count;this.data=data;firstAt=now;present=new boolean[count];}
        boolean key(){return (flags&1)!=0;}boolean complete(){return received==count;}
        boolean consistent(long pts,long stamp,int flags,int length,int count){return this.pts==pts&&this.stamp==stamp&&this.flags==flags&&this.length==length&&this.count==count;}
    }
    public static final class Receiver implements AutoCloseable {
        private final DatagramSocket socket;private final InetAddress expectedSender;private final long session;
        private final FrameBufferPool pool;private final Listener listener;private final Object lock=new Object();
        private final TreeMap<Long,Assembly> pending=new TreeMap<>();private final ArrayDeque<StreamFrame> ready=new ArrayDeque<>();
        private final byte[] packetBytes=new byte[MAX_DATAGRAM+1],nackBytes=new byte[NACK_HEADER+128];
        private final DatagramPacket incoming=new DatagramPacket(packetBytes,packetBytes.length);
        private final ByteBuffer receiveView=ByteBuffer.wrap(packetBytes),nackView=ByteBuffer.wrap(nackBytes);
        private volatile boolean closed;private int peerPort=-1,assemblyBytes;
        private long generation=-1,expected=-1,lastDelivered=-1,gapSince,waitingSince=System.nanoTime(),lastGapReport;
        private boolean waitingKey=true,gapNotification;
        private final AtomicLong completed=new AtomicLong(),lost=new AtomicLong(),invalid=new AtomicLong(),reordered=new AtomicLong(),nacks=new AtomicLong();
        public Receiver(DatagramSocket socket,InetAddress expectedSender,long session,FrameBufferPool pool,Listener listener)throws SocketException{
            if(socket==null||expectedSender==null||session==0||pool==null||listener==null)throw new IllegalArgumentException("Invalid UDP receiver pairing");
            this.socket=socket;this.expectedSender=expectedSender;this.session=session;this.pool=pool;this.listener=listener;socket.setSoTimeout(10);socket.setReceiveBufferSize(512*1024);
        }
        /** One owner calls repeatedly; timeout is maintenance, never an incomplete packet retry. */
        public void receiveOnce()throws IOException{
            if(closed)return;
            try{incoming.setLength(packetBytes.length);socket.receive(incoming);long now=System.nanoTime();synchronized(lock){if(!closed){acceptPacket(now);maintenance(now);}}}
            catch(SocketTimeoutException timeout){synchronized(lock){if(!closed)maintenance(System.nanoTime());}}
            catch(SocketException e){if(!closed)throw e;}
            dispatch();
        }
        private void acceptPacket(long now)throws IOException{
            int size=incoming.getLength();if(!expectedSender.equals(incoming.getAddress())||size<DATA_HEADER||size>MAX_DATAGRAM){invalid.incrementAndGet();return;}
            ByteBuffer b=receiveView;b.clear();b.limit(size);if(!prefixValid(b,DATA,session)){invalid.incrementAndGet();return;}
            long gen=b.getLong(),seq=b.getLong(),pts=b.getLong(),stamp=b.getLong();int flags=b.getInt(),length=b.getInt(),offset=b.getInt();
            int index=b.getShort()&65535,count=b.getShort()&65535,part=b.getShort()&65535,reserved=b.getShort()&65535;
            if(gen<0||seq<0||length<0||length>SmoothWire.MAX_VIDEO_BYTES||count!=fragments(length)||index>=count||offset!=index*FRAGMENT_BYTES||part!=Math.min(FRAGMENT_BYTES,length-offset)||part<0||size!=DATA_HEADER+part||reserved!=0||(flags&2)!=0){invalid.incrementAndGet();return;}
            if(peerPort<0)peerPort=incoming.getPort();else if(peerPort!=incoming.getPort()){invalid.incrementAndGet();return;}
            if(gen<generation)return;
            if(gen>generation){clearPending();clearReady();generation=gen;expected=-1;lastDelivered=-1;waitingKey=true;waitingSince=now;gapSince=0;}
            if(seq<=lastDelivered)return; // Duplicates cannot be completed/delivered a second time.
            Assembly a=pending.get(seq);
            if(a==null){
                if(pending.size()>=MAX_ASSEMBLIES||(long)assemblyBytes+length>MAX_ASSEMBLY_BYTES){enterGap(now);discardNonKeys();}
                if(pending.size()>=MAX_ASSEMBLIES||(long)assemblyBytes+length>MAX_ASSEMBLY_BYTES){lost.incrementAndGet();requestGap(now);return;}
                byte[] bytes;try{bytes=pool.acquire(length);}catch(RuntimeException e){throw new IOException("UDP pool unavailable",e);}
                if((long)assemblyBytes+bytes.length>MAX_ASSEMBLY_BYTES){pool.recycle(bytes);enterGap(now);return;}
                a=new Assembly(gen,seq,pts,stamp,flags,length,count,bytes,now);pending.put(seq,a);assemblyBytes+=bytes.length;
            }else if(!a.consistent(pts,stamp,flags,length,count)){invalid.incrementAndGet();return;}
            if(a.present[index])return;
            if(index<a.highest||index>a.received)reordered.incrementAndGet();a.highest=Math.max(a.highest,index);
            System.arraycopy(packetBytes,DATA_HEADER,a.data,offset,part);a.present[index]=true;a.received++;
            if(a.complete())a.completedAt=now;
            drain(now);
        }
        private void maintenance(long now)throws IOException{
            boolean expired=false;Iterator<Assembly> it=pending.values().iterator();
            while(it.hasNext()){
                Assembly a=it.next();
                if(!a.complete()&&now-a.firstAt>=REASSEMBLY_NS){it.remove();release(a);lost.incrementAndGet();expired=true;continue;}
                if(!a.complete()&&peerPort>0&&a.nackCount<2&&now-a.firstAt>=20_000_000L&&(a.lastNack==0||now-a.lastNack>=30_000_000L))sendNack(a,now);
            }
            if(expired)enterGap(now);
            drain(now);
            if(!waitingKey&&expected>=0&&!pending.isEmpty()&&(!pending.containsKey(expected)||!pending.get(expected).complete())){
                if(gapSince==0)gapSince=now;
                if(now-gapSince>=REASSEMBLY_NS){lost.incrementAndGet();enterGap(now);drain(now);}
            }else gapSince=0;
            if(waitingKey&&now-waitingSince>=REASSEMBLY_NS)requestGap(now);
        }
        private void sendNack(Assembly a,long now)throws IOException{
            ByteBuffer b=nackView;b.clear();prefix(b,NACK,session);b.putLong(a.gen).putLong(a.seq);b.putShort((short)0).putShort((short)0);
            int count=0,scanned=0,index=a.nackCursor;
            while(scanned<a.count&&count<64){if(!a.present[index]){b.putShort((short)index);count++;}index=(index+1)%a.count;scanned++;}
            if(count==0)return;a.nackCursor=index;b.putShort(32,(short)count);
            socket.send(new DatagramPacket(nackBytes,NACK_HEADER+count*2,expectedSender,peerPort));a.nackCount++;a.lastNack=now;nacks.incrementAndGet();
        }
        private void drain(long now){
            if(waitingKey){
                Long chosen=null;for(Map.Entry<Long,Assembly> e:pending.entrySet())if(e.getValue().complete()&&e.getValue().key()){chosen=e.getKey();break;}
                if(chosen==null)return;
                Iterator<Map.Entry<Long,Assembly>> it=pending.entrySet().iterator();while(it.hasNext()){Map.Entry<Long,Assembly> e=it.next();if(e.getKey()>=chosen)break;it.remove();release(e.getValue());lost.incrementAndGet();}
                expected=chosen;waitingKey=false;gapSince=0;
            }
            while(!waitingKey){Assembly a=pending.get(expected);if(a==null||!a.complete())break;pending.remove(expected);assemblyBytes-=a.data.length;
                StreamFrame frame=new StreamFrame(a.gen,a.seq,a.pts,a.flags,a.stamp,a.completedAt,a.data,0,a.length,pool);ready.addLast(frame);completed.incrementAndGet();lastDelivered=a.seq;
                if(expected==Long.MAX_VALUE){waitingKey=true;waitingSince=now;break;}expected++;gapSince=0;
            }
        }
        private void requestGap(long now){if(lastGapReport==0||now-lastGapReport>=REASSEMBLY_NS){gapNotification=true;lastGapReport=now;}}
        private void enterGap(long now){if(!waitingKey){waitingKey=true;expected=-1;waitingSince=now;}requestGap(now);discardNonKeys();gapSince=0;}
        private void discardNonKeys(){Iterator<Assembly> it=pending.values().iterator();while(it.hasNext()){Assembly a=it.next();if(!a.key()){it.remove();release(a);lost.incrementAndGet();}}}
        private void release(Assembly a){assemblyBytes-=a.data.length;pool.recycle(a.data);}
        private void clearPending(){for(Assembly a:pending.values())pool.recycle(a.data);pending.clear();assemblyBytes=0;}
        private void clearReady(){while(!ready.isEmpty())ready.removeFirst().release();}
        private void dispatch(){
            for(;;){StreamFrame frame;boolean gap;synchronized(lock){if(closed)return;gap=gapNotification;gapNotification=false;frame=ready.pollFirst();}
                try{if(gap)listener.onGap();}catch(RuntimeException | Error failure){if(frame!=null)frame.release();throw failure;}if(frame==null)return;listener.onFrame(frame);
            }
        }
        public long completedFrames(){return completed.get();}public long lostFrames(){return lost.get();}public long invalidDatagrams(){return invalid.get();}
        public long reorderedDatagrams(){return reordered.get();}public long nackRequests(){return nacks.get();}
        public void close(){closed=true;socket.close();synchronized(lock){clearPending();clearReady();}}
    }
}
