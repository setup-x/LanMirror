package com.lanmirror.core.api;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic ownership/framing and localhost UDP fault injection, NOT phone/network performance. */
public final class SmoothTransportTest {
    private static int checks;
    private static final InetAddress LOOP;
    static{try{LOOP=InetAddress.getByName("127.0.0.1");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static void check(boolean good,String why){checks++;if(!good)throw new AssertionError(why);}
    private static FrameBufferPool pool(){return new FrameBufferPool(SmoothWire.MAX_VIDEO_BYTES,8*1024*1024,16);}
    private static StreamFrame frame(FrameBufferPool p,long gen,long seq,int flags,int length){byte[] data=p.acquire(length+7);for(int i=0;i<length;i++)data[7+i]=(byte)(i*17+seq);return new StreamFrame(gen,seq,seq*16667,flags,12345+seq,0,data,7,length,p);}
    public static void main(String[] args)throws Exception{
        referencesAndBounds();tcpFraming();pairing(false);pairing(true);udpOrderingAndGaps();udpRepairAndInvalid();udpSenderCache();udpCloseBounded();udpLimitsAndCallbackFailure();udpMetrics();
        System.out.println("PASS: "+checks+" smooth transport assertions (reference/pool, TCP framing/pairing, custom UDP reorder/duplicate/gaps/NACK/cache/close; NOT hardware performance)");
    }
    private static void referencesAndBounds(){
        FrameBufferPool p=new FrameBufferPool(4096,2048,2);byte[] a=p.acquire(2);check(a.length>=2,"Minimum capacity");
        StreamFrame f=new StreamFrame(1,8,9,1,10,11,a,0,2,p);check(f.retain()==f&&f.referenceCount()==2,"Retain same frame");f.release();check(p.retainedCount()==0,"Cache owner keeps loan");f.release();check(p.retainedCount()==1,"Final reference returns loan");
        try{f.retain();throw new AssertionError();}catch(IllegalStateException expected){checks++;}try{f.release();throw new AssertionError();}catch(IllegalStateException expected){checks++;}
        byte[] b=p.acquire(8);try{new StreamFrame(-1,1,1,0,0,0,b,0,8,p);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        check(p.retainedCount()==0,"Invalid constructor does not take ownership");p.recycle(b);
        byte[] c=p.acquire(3),d=p.acquire(3),e=p.acquire(3);p.recycle(c);p.recycle(d);p.recycle(e);check(p.retainedCount()==2&&p.retainedBytes()==2048,"Bounded count/bytes");
        p.close();p.recycle(e);check(p.retainedCount()==0&&p.retainedBytes()==0,"Late recycle cannot revive");try{p.acquire(1);throw new AssertionError();}catch(IllegalStateException expected){checks++;}
    }
    private static void tcpFraming()throws Exception{
        FrameBufferPool p=pool();byte[][] csd={{0,0,0,1,103,1},{0,0,1,104,2}};SmoothWire.Config config=new SmoothWire.Config(3,1920,1080,csd);csd[0][4]=99;check(config.csd[0][4]==103,"CONFIG deep copy");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        SmoothWire.writeConfig(out,config);SmoothWire.writeConfigAck(out,3);StreamFrame frame=frame(p,3,99,1,4001);SmoothWire.writeVideo(out,frame);frame.release();SmoothWire.writeSync(out);SmoothWire.writeEnd(out);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));SmoothWire.Message msg=SmoothWire.readMessage(in,p);
        check(msg.type==SmoothWire.CONFIG&&msg.config.generation==3&&msg.config.width==1920&&msg.config.csd[0][4]==103,"CONFIG round trip");
        check(SmoothWire.readMessage(in,p).generation==3,"ACK generation");msg=SmoothWire.readMessage(in,p);StreamFrame decoded=msg.frame;
        check(decoded.generation==3&&decoded.sequence==99&&decoded.ptsUs==99*16667&&decoded.encodedAtNs==12444,"Video metadata");
        check(decoded.length==4001&&decoded.data.length>decoded.length&&decoded.offset==0,"Logical payload not capacity");
        for(int i=0;i<decoded.length;i+=137)check(decoded.data[i]==(byte)(i*17+99),"Payload slice");check(decoded.receivedAtNs>0,"Read-complete timestamp");decoded.release();
        check(SmoothWire.readMessage(in,p).type==SmoothWire.REQUEST_SYNC&&SmoothWire.readMessage(in,p).type==SmoothWire.END&&in.available()==0,"Control roundtrip");
        bytes.reset();frame=frame(p,1,1,0,100);SmoothWire.writeVideo(out,frame);frame.release();byte[] truncated=Arrays.copyOf(bytes.toByteArray(),bytes.size()-1);long reused=p.reuseCount();
        try{SmoothWire.readMessage(new DataInputStream(new ByteArrayInputStream(truncated)),p);throw new AssertionError();}catch(EOFException expected){checks++;}
        check(p.retainedCount()>0&&p.reuseCount()>=reused,"Truncated read returns acquired loan");
        bytes.reset();out.writeInt(SmoothWire.VIDEO);out.writeInt(SmoothWire.MAX_PACKET+1);long alloc=p.allocations();
        try{SmoothWire.readMessage(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),p);throw new AssertionError();}catch(IOException expected){checks++;}check(p.allocations()==alloc,"Oversize rejects before allocation");
        try{new SmoothWire.Config(1,8193,1,new byte[0][]);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        try{new SmoothWire.Config(1,1,1,new byte[][]{new byte[SmoothWire.MAX_CSD_BYTES+1]});throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        p.close();
    }
    private static void pairing(boolean udp)throws Exception{
        AtomicReference<Throwable> problem=new AtomicReference<>();try(ServerSocket server=new ServerSocket(0,1,LOOP)){
            server.setSoTimeout(3000);Thread t=new Thread(()->{try(Socket socket=server.accept()){socket.setSoTimeout(3000);DataInputStream in=new DataInputStream(socket.getInputStream());DataOutputStream out=new DataOutputStream(socket.getOutputStream());if(SmoothWire.accept(in,out,"123456",0x11223344,7238)!=udp)throw new AssertionError("Transport");Wire.serverClockSync(in,out);}catch(Throwable e){problem.set(e);}});t.start();
            try(Socket socket=new Socket(LOOP,server.getLocalPort())){socket.setSoTimeout(3000);DataInputStream in=new DataInputStream(socket.getInputStream());DataOutputStream out=new DataOutputStream(socket.getOutputStream());SmoothWire.Hello hello=SmoothWire.connect(in,out,"123456",udp);check(hello.udp==udp&&hello.sessionId==0x11223344&&hello.udpPort==(udp?7238:0),"Negotiation fields");check(Wire.clientClockSync(in,out).roundTripNs>=0,"Legacy clock calibration follows new hello");}
            t.join(4000);check(!t.isAlive(),"Handshake thread terminates");if(problem.get()!=null)throw new AssertionError(problem.get());
        }
        ByteArrayOutputStream request=new ByteArrayOutputStream(),reply=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(request);out.writeInt(SmoothWire.MAGIC);out.writeInt(SmoothWire.VERSION);out.writeBoolean(udp);out.writeUTF("999999");
        try{SmoothWire.accept(new DataInputStream(new ByteArrayInputStream(request.toByteArray())),new DataOutputStream(reply),"123456",42,9999);throw new AssertionError();}catch(IOException expected){checks++;}check(new DataInputStream(new ByteArrayInputStream(reply.toByteArray())).readInt()==0,"Wrong PIN explicit rejection");
    }
    private static byte[] datagram(long session,long gen,long seq,int flags,byte[] payload,int index){
        int count=Math.max(1,(payload.length+UdpVideoTransport.FRAGMENT_BYTES-1)/UdpVideoTransport.FRAGMENT_BYTES),offset=index*UdpVideoTransport.FRAGMENT_BYTES,n=Math.min(UdpVideoTransport.FRAGMENT_BYTES,payload.length-offset);
        byte[] bytes=new byte[UdpVideoTransport.DATA_HEADER+n];ByteBuffer b=ByteBuffer.wrap(bytes);b.putInt(0x4c4d5531).put((byte)1).put((byte)1).putShort((short)0).putLong(session).putLong(gen).putLong(seq).putLong(seq*16667).putLong(777+seq).putInt(flags).putInt(payload.length).putInt(offset).putShort((short)index).putShort((short)count).putShort((short)n).putShort((short)0).put(payload,offset,n);return bytes;
    }
    private static byte[] payload(int size){byte[] b=new byte[size];for(int i=0;i<size;i++)b[i]=(byte)(i*7);return b;}
    private static void feed(DatagramSocket sender,DatagramSocket target,UdpVideoTransport.Receiver receiver,byte[] bytes)throws Exception{sender.send(new DatagramPacket(bytes,bytes.length,LOOP,target.getLocalPort()));receiver.receiveOnce();}
    private static void pump(UdpVideoTransport.Receiver receiver,long millis)throws Exception{long end=System.nanoTime()+millis*1_000_000;do{receiver.receiveOnce();}while(System.nanoTime()<end);}
    private static final class Sink implements UdpVideoTransport.Listener{
        final ArrayList<Long> seqs=new ArrayList<>();final ArrayList<byte[]> frames=new ArrayList<>();int gaps;
        public void onFrame(StreamFrame f){try{seqs.add(f.sequence);frames.add(Arrays.copyOfRange(f.data,f.offset,f.offset+f.length));}finally{f.release();}}
        public void onGap(){gaps++;}
    }
    private static void udpOrderingAndGaps()throws Exception{
        FrameBufferPool p=pool();Sink sink=new Sink();try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket dest=new DatagramSocket(0,LOOP)){
            UdpVideoTransport.Receiver r=new UdpVideoTransport.Receiver(dest,LOOP,88,p,sink);byte[] key=payload(2800);
            feed(source,dest,r,datagram(88,1,40,1,key,2));feed(source,dest,r,datagram(88,1,40,1,key,0));feed(source,dest,r,datagram(88,1,40,1,key,0));check(sink.seqs.isEmpty(),"Partial/duplicate cannot deliver");feed(source,dest,r,datagram(88,1,40,1,key,1));
            check(sink.seqs.equals(Arrays.asList(40L))&&Arrays.equals(key,sink.frames.get(0)),"Reordered IDR reassembles exactly with nonzero first sequence");
            for(int i=0;i<3;i++)feed(source,dest,r,datagram(88,1,40,1,key,i));check(r.completedFrames()==1,"Duplicate complete delivered only once");check(r.reorderedDatagrams()>0,"Reorder counter");
            feed(source,dest,r,datagram(88,2,100,0,payload(10),0));check(sink.seqs.size()==1,"New generation P waits for IDR");
            feed(source,dest,r,datagram(88,2,101,1,payload(12),0));check(sink.seqs.equals(Arrays.asList(40L,101L)),"New generation can start at later key");
            feed(source,dest,r,datagram(88,2,103,0,payload(13),0));check(sink.seqs.size()==2,"Whole missing sequence blocks P");pump(r,130);check(sink.gaps>0&&r.lostFrames()>0,"Bounded gap timeout requests IDR");
            feed(source,dest,r,datagram(88,2,104,0,payload(14),0));check(sink.seqs.size()==2,"After loss later P never fed");
            feed(source,dest,r,datagram(88,2,105,1,payload(15),0));check(sink.seqs.equals(Arrays.asList(40L,101L,105L)),"Recovery at fresh key");
            feed(source,dest,r,datagram(88,1,999,1,payload(2),0));check(sink.seqs.size()==3,"Old generation cannot resurrect");r.close();check(p.retainedCount()>0,"Close/delivery returns all loans");
        }finally{p.close();}
    }
    private static void udpRepairAndInvalid()throws Exception{
        FrameBufferPool p=pool();Sink sink=new Sink();try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket dest=new DatagramSocket(0,LOOP);DatagramSocket outsider=new DatagramSocket(0,LOOP)){
            source.setSoTimeout(1000);UdpVideoTransport.Receiver r=new UdpVideoTransport.Receiver(dest,LOOP,99,p,sink);byte[] body=payload(2100);
            feed(source,dest,r,datagram(99,1,10,1,body,0));pump(r,25);check(r.nackRequests()>=1,"Missing fragment NACK after short repair delay");
            byte[] buf=new byte[1201];DatagramPacket nack=new DatagramPacket(buf,buf.length);source.receive(nack);ByteBuffer b=ByteBuffer.wrap(buf);check(nack.getLength()<=164&&b.getInt()==0x4c4d5531&&b.get()==1&&b.get()==2,"Bounded custom NACK format");
            feed(source,dest,r,datagram(99,1,10,1,body,1));check(sink.seqs.equals(Arrays.asList(10L))&&Arrays.equals(body,sink.frames.get(0)),"Repair before deadline preserves frame");
            byte[] corrupt=datagram(99,1,11,0,payload(1),0);ByteBuffer.wrap(corrupt).putInt(56,7);feed(source,dest,r,corrupt);
            feed(source,dest,r,datagram(123,999,11,1,payload(1),0));feed(outsider,dest,r,datagram(99,999,11,1,payload(1),0));check(r.invalidDatagrams()==3,"Bad offsets/session/port rejected before generation reset");
            feed(source,dest,r,datagram(99,1,11,0,payload(1),0));check(sink.seqs.equals(Arrays.asList(10L,11L)),"Invalid high generation did not disrupt valid stream");
            feed(source,dest,r,datagram(99,1,12,0,payload(2300),0));pump(r,135);check(r.lostFrames()>0&&sink.gaps>0,"Incomplete frame expires");check(r.nackRequests()<=3,"At most two NACK rounds per assembly");
            r.close();
        }finally{p.close();}
    }
    private static void payloadSample(TransportMetrics metrics,long bytes,String why){
        check(metrics.sampled&&metrics.intervalNs>0,why+" sampled actual elapsed");
        check(Math.abs(metrics.receiveMbps*metrics.intervalNs/8_000.0-bytes)<.00001,why+" logical payload bytes including duplicates, excluding headers");
    }
    private static void udpMetrics()throws Exception{
        FrameBufferPool p=pool();Sink sink=new Sink();
        try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket dest=new DatagramSocket(0,LOOP);DatagramSocket outsider=new DatagramSocket(0,LOOP);DatagramSocket wrongIp=new DatagramSocket(0,InetAddress.getByName("127.0.0.2"))){
            UdpVideoTransport.Receiver r=new UdpVideoTransport.Receiver(dest,LOOP,808,p,sink);
            TransportMetrics.Sampler sampler=new TransportMetrics.Sampler();
            check(!r.transportMetrics(sampler).sampled,"UDP first sample unknown");
            byte[] body=payload(2300);byte[] first=datagram(808,1,10,1,body,0);
            feed(source,dest,r,first);feed(source,dest,r,first);
            byte[] inconsistent=first.clone();ByteBuffer.wrap(inconsistent).putLong(32,9);feed(source,dest,r,inconsistent);
            byte[] corrupt=first.clone();ByteBuffer.wrap(corrupt).putInt(56,7);feed(source,dest,r,corrupt);
            feed(source,dest,r,datagram(999,1,10,1,body,0));feed(outsider,dest,r,first);feed(wrongIp,dest,r,first);
            TransportMetrics duplicate=r.transportMetrics(sampler);payloadSample(duplicate,2L*UdpVideoTransport.FRAGMENT_BYTES,"UDP partial duplicate");
            check(r.invalidDatagrams()==5&&duplicate.missingFragments==0&&duplicate.expiredFrames==0,"Invalid metadata/session/port/IP not counted as payload or loss");
            pump(r,130);TransportMetrics expired=r.transportMetrics(sampler);
            check(expired.missingFragments==2&&expired.expiredFrames==1,"Timeout counts count-received once despite duplicates");
            pump(r,120);TransportMetrics repeat=r.transportMetrics(sampler);
            check(repeat.missingFragments==0&&repeat.expiredFrames==0,"Expired assembly never counted twice");payloadSample(repeat,0,"UDP idle zero");
            for(int i=0;i<3;i++)feed(source,dest,r,datagram(808,1,11,1,body,i));
            feed(source,dest,r,datagram(808,1,11,1,body,0));payloadSample(r.transportMetrics(sampler),body.length+UdpVideoTransport.FRAGMENT_BYTES,"UDP complete duplicate");
            feed(source,dest,r,datagram(808,1,12,0,body,0));
            feed(source,dest,r,datagram(808,2,20,1,body,0));
            TransportMetrics generation=r.transportMetrics(sampler);check(generation.missingFragments==0&&generation.expiredFrames==0,"Generation reset not timeout loss");
            r.close();TransportMetrics closed=r.transportMetrics(sampler);check(closed.missingFragments==0&&closed.expiredFrames==0,"Close incomplete assembly not timeout loss");
        }finally{p.close();}
        p=pool();
        try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket dest=new DatagramSocket(0,LOOP)){
            UdpVideoTransport.Receiver r=new UdpVideoTransport.Receiver(dest,LOOP,809,p,new Sink());TransportMetrics.Sampler sampler=new TransportMetrics.Sampler();r.transportMetrics(sampler);
            byte[] body=payload(2000);for(int i=0;i<8;i++)feed(source,dest,r,datagram(809,1,100+i,0,body,0));
            feed(source,dest,r,datagram(809,1,108,1,payload(1),0));
            TransportMetrics capacity=r.transportMetrics(sampler);check(r.lostFrames()>=8&&capacity.missingFragments==0&&capacity.expiredFrames==0,"Capacity/dependency discards do not count timeout fragments");r.close();
        }finally{p.close();}
        p=pool();
        try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket dest=new DatagramSocket(0,LOOP)){
            UdpVideoTransport.Receiver r=new UdpVideoTransport.Receiver(dest,LOOP,810,p,new Sink());TransportMetrics.Sampler sampler=new TransportMetrics.Sampler();r.transportMetrics(sampler);
            byte[] body=payload(1_000_000);for(int i=0;i<9;i++)feed(source,dest,r,datagram(810,1,100+i,1,body,0));
            TransportMetrics capacity=r.transportMetrics(sampler);payloadSample(capacity,9L*UdpVideoTransport.FRAGMENT_BYTES,"UDP capacity rejection");check(r.lostFrames()>0&&capacity.missingFragments==0&&capacity.expiredFrames==0,"Capacity reject is not timeout");r.close();
        }finally{p.close();}
    }
    private static byte[] nack(long session,long gen,long seq,int...indices){ByteBuffer b=ByteBuffer.allocate(36+indices.length*2);b.putInt(0x4c4d5531).put((byte)1).put((byte)2).putShort((short)0).putLong(session).putLong(gen).putLong(seq).putShort((short)indices.length).putShort((short)0);for(int i:indices)b.putShort((short)i);return b.array();}
    private static void udpSenderCache()throws Exception{
        FrameBufferPool p=pool();try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket peer=new DatagramSocket(0,LOOP);DatagramSocket outsider=new DatagramSocket(0,LOOP)){
            peer.setSoTimeout(60);UdpVideoTransport.Sender sender=new UdpVideoTransport.Sender(source,new InetSocketAddress(LOOP,peer.getLocalPort()),55);
            StreamFrame frame=frame(p,5,17,1,2400);sender.send(frame);frame.release();check(p.retainedCount()==0,"Retransmission cache retains its own reference");
            byte[] buf=new byte[1201];DatagramPacket packet=new DatagramPacket(buf,buf.length);int received=0;for(int i=0;i<3;i++){packet.setLength(buf.length);peer.receive(packet);check(packet.getLength()<=1200,"MTU bound including header");received++;}check(sender.sentDatagrams()==received,"Sent counter");
            byte[] request=nack(55,5,17,1);peer.send(new DatagramPacket(request,request.length,LOOP,source.getLocalPort()));packet.setLength(buf.length);peer.receive(packet);check(ByteBuffer.wrap(buf).getShort(60)==1,"Only requested fragment retransmitted");
            peer.send(new DatagramPacket(request,request.length,LOOP,source.getLocalPort()));try{packet.setLength(buf.length);peer.receive(packet);throw new AssertionError("Repeated repair amplification");}catch(SocketTimeoutException expected){checks++;}
            check(sender.retransmittedDatagrams()==1,"Each fragment at most one retry");
            byte[] another=nack(55,5,17,0);outsider.send(new DatagramPacket(another,another.length,LOOP,source.getLocalPort()));try{packet.setLength(buf.length);peer.receive(packet);throw new AssertionError("Uninvited peer repair");}catch(SocketTimeoutException expected){checks++;}
            peer.send(new DatagramPacket(another,another.length,LOOP,source.getLocalPort()));try{packet.setLength(buf.length);peer.receive(packet);throw new AssertionError("Expired cache repair");}catch(SocketTimeoutException expected){checks++;}
            check(p.retainedCount()==1,"Expired cache returns loan");sender.close();check(source.isClosed(),"Sender owns/closes socket");
        }finally{p.close();}
    }
    private static void udpCloseBounded()throws Exception{
        FrameBufferPool p=pool();Sink sink=new Sink();DatagramSocket source=new DatagramSocket(0,LOOP),dest=new DatagramSocket(0,LOOP);
        UdpVideoTransport.Receiver receiver=new UdpVideoTransport.Receiver(dest,LOOP,1,p,sink);feed(source,dest,receiver,datagram(1,1,50,1,payload(2000),0));check(p.retainedCount()==0,"Partial assembler owns loan");
        long at=System.nanoTime();receiver.close();receiver.receiveOnce();check(System.nanoTime()-at<500_000_000L&&dest.isClosed(),"Receiver close/read bounded");check(p.retainedCount()==1,"Close recycles partial assembly");source.close();p.close();
    }
    private static void udpLimitsAndCallbackFailure()throws Exception{
        FrameBufferPool p=pool();Sink sink=new Sink();
        try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket dest=new DatagramSocket(0,LOOP)){
            UdpVideoTransport.Receiver r=new UdpVideoTransport.Receiver(dest,LOOP,333,p,sink);byte[] big=payload(1_000_000);
            for(int i=0;i<9;i++)feed(source,dest,r,datagram(333,1,100+i,1,big,0));
            check(p.allocations()<=8,"Assemblers bound at eight frames/eight MiB actual arrays");
            check(r.lostFrames()>0&&sink.gaps>0,"Assembler pressure explicitly requests IDR");
            byte[] huge=new byte[1201];feed(source,dest,r,huge);check(r.invalidDatagrams()>0,"Oversized datagram rejected");
            r.close();check(p.retainedBytes()<=8*1024*1024&&p.retainedCount()==p.allocations(),"Close returns every incomplete large-frame loan");
        }finally{p.close();}
        p=pool();final FrameBufferPool callbacks=p;
        try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket dest=new DatagramSocket(0,LOOP)){
            UdpVideoTransport.Receiver r=new UdpVideoTransport.Receiver(dest,LOOP,444,p,new UdpVideoTransport.Listener(){
                public void onFrame(StreamFrame f){try{throw new AssertionError("Gap callback must fail first");}finally{f.release();}}
                public void onGap(){throw new IllegalStateException("Injected gap listener failure");}
            });
            byte[] body=payload(2000);for(int i=0;i<8;i++)feed(source,dest,r,datagram(444,1,200+i,0,body,0));
            try{feed(source,dest,r,datagram(444,1,208,1,payload(1),0));throw new AssertionError();}catch(IllegalStateException expected){checks++;}
            r.close();check(callbacks.retainedCount()==callbacks.allocations(),"Gap callback exception releases not-yet-transferred ready frame");
        }finally{p.close();}
        p=pool();
        try(DatagramSocket source=new DatagramSocket(0,LOOP);DatagramSocket peer=new DatagramSocket(0,LOOP)){
            UdpVideoTransport.Sender sender=new UdpVideoTransport.Sender(source,new InetSocketAddress(LOOP,peer.getLocalPort()),555);
            ArrayList<StreamFrame> frames=new ArrayList<>();for(int i=0;i<3;i++){
                byte[] large=p.acquire(2*1024*1024);StreamFrame f=new StreamFrame(1,i,0,i==0?1:0,0,0,large,0,1,p);frames.add(f);sender.send(f);f.release();
            }
            int live=0;for(StreamFrame f:frames)live+=f.referenceCount();check(live<=2,"Retransmission cache bounds actual retained arrays at four MiB");
            sender.close();for(StreamFrame f:frames)check(f.referenceCount()==0,"Close releases cache-only reference");
        }finally{p.close();}
        FrameBufferPool finalPool=pool();DatagramSocket socket=new DatagramSocket(0,LOOP);
        UdpVideoTransport.Receiver receiver=new UdpVideoTransport.Receiver(socket,LOOP,666,finalPool,new Sink());
        final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);final java.util.concurrent.atomic.AtomicBoolean stop=new java.util.concurrent.atomic.AtomicBoolean();final AtomicReference<Throwable> error=new AtomicReference<>();
        Thread reader=new Thread(()->{entered.countDown();try{while(!stop.get())receiver.receiveOnce();}catch(Throwable failure){error.set(failure);}});reader.start();
        check(entered.await(1,java.util.concurrent.TimeUnit.SECONDS),"UDP reader started");stop.set(true);receiver.close();reader.join(1000);
        check(!reader.isAlive()&&error.get()==null,"Close wakes timeout/blocked UDP reader without spurious failure");finalPool.close();
    }
}
