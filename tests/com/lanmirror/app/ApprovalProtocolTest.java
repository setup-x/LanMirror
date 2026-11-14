package com.lanmirror.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Approval wire and four simultaneous TCP sessions, not Android UI or hardware codec tests. */
public final class ApprovalProtocolTest {
    private static final AtomicInteger checks=new AtomicInteger();
    private static void check(boolean value,String message){checks.incrementAndGet();if(!value)throw new AssertionError(message);}
    private interface Work{void run()throws Exception;}
    private static void bad(Work work,String message)throws Exception{boolean failed=false;try{work.run();}catch(IOException e){failed=true;}check(failed,message);}
    private static byte[] request(int magic,int version,byte[] name,int mode)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(magic);out.writeInt(version);out.writeInt(name.length);out.write(name);out.writeByte(mode);return bytes.toByteArray();
    }
    private static DataInputStream input(byte[] data){return new DataInputStream(new ByteArrayInputStream(data));}
    public static void main(String[] args)throws Exception{
        String label="Phone\n\u202eScreen";check(ApprovalProtocol.displayName(label).equals("PhoneScreen"),"Strip misleading control/bidi labels");
        check(ApprovalProtocol.displayName(null).length()>0,"Null label default");
        check(ApprovalProtocol.displayName(new String(new char[300]).replace('\0','中')).getBytes(StandardCharsets.UTF_8).length<=192,"UTF8 bounded by bytes");
        check(ApprovalProtocol.displayName("😀😀\ud800").equals("😀😀"),"Surrogate handling");
        ApprovalProtocol.Request good=ApprovalProtocol.readRequest(input(request(ApprovalProtocol.MAGIC,1,"Phone".getBytes(StandardCharsets.UTF_8),1)));
        check(good.udp&&good.name.equals("Phone"),"Valid request fields");
        bad(()->ApprovalProtocol.readRequest(input(request(0,1,new byte[]{65},0))),"Magic reject");
        bad(()->ApprovalProtocol.readRequest(input(request(ApprovalProtocol.MAGIC,3,new byte[]{65},0))),"Version reject");
        bad(()->ApprovalProtocol.readRequest(input(request(ApprovalProtocol.MAGIC,1,new byte[0],0))),"Empty encoded name reject");
        bad(()->ApprovalProtocol.readRequest(input(request(ApprovalProtocol.MAGIC,1,new byte[193],0))),"Name upper limit");
        bad(()->ApprovalProtocol.readRequest(input(request(ApprovalProtocol.MAGIC,1,new byte[]{(byte)0xc3,0x28},0))),"Malformed UTF8 reject");
        bad(()->ApprovalProtocol.readRequest(input(request(ApprovalProtocol.MAGIC,1,new byte[]{65},2))),"Transport reject");
        byte[] complete=request(ApprovalProtocol.MAGIC,1,new byte[]{65,66,67},1);
        for(int n=0;n<complete.length;n++){final byte[] truncated=Arrays.copyOf(complete,n);bad(()->ApprovalProtocol.readRequest(input(truncated)),"Truncated approval request");}
        ByteArrayOutputStream accepted=new ByteArrayOutputStream();DataOutputStream acceptOut=new DataOutputStream(accepted);
        ApprovalProtocol.writeWaiting(acceptOut);ApprovalProtocol.writeAccepted(acceptOut,"001234");
        ByteArrayOutputStream clientHello=new ByteArrayOutputStream();
        check(ApprovalProtocol.clientRequest(input(accepted.toByteArray()),new DataOutputStream(clientHello),"Sender",false).equals("001234"),"Approved token preserves leading zeros");
        check(!ApprovalProtocol.readRequest(input(clientHello.toByteArray())).udp,"Client transport requested unchanged");
        ByteArrayOutputStream denied=new ByteArrayOutputStream();ApprovalProtocol.writeRejected(new DataOutputStream(denied),"4路已满");
        bad(()->ApprovalProtocol.clientRequest(input(denied.toByteArray()),new DataOutputStream(new ByteArrayOutputStream()),"Sender",true),"Reject before WAIT supported for full server");
        bad(()->ApprovalProtocol.writeAccepted(new DataOutputStream(new ByteArrayOutputStream()),"123"),"Internal token validated");
        for(int code:new int[]{-1,9,ApprovalProtocol.WAIT}){
            ByteArrayOutputStream malformed=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(malformed);out.writeInt(ApprovalProtocol.WAIT);out.writeInt(code);
            bad(()->ApprovalProtocol.clientRequest(input(malformed.toByteArray()),new DataOutputStream(new ByteArrayOutputStream()),"Sender",true),"No unlimited WAIT loop");
        }
        gatedConnection();fourParallelConnections();
        System.out.println("PASS: "+checks.get()+" approval/multi-socket assertions (bounded UTF8, explicit consent gate, no preapproval media, same-stream continuation, four isolated TCP sessions; NOT Android UI/FPS)");
    }
    private static void gatedConnection()throws Exception{
        ServerSocket listener=new ServerSocket(0,4,InetAddress.getLoopbackAddress());CountDownLatch waiting=new CountDownLatch(1),allow=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean granted=new AtomicBoolean();
        Thread server=new Thread(()->{try(Socket peer=listener.accept()){
            peer.setSoTimeout(2000);DataInputStream in=new DataInputStream(peer.getInputStream());DataOutputStream out=new DataOutputStream(peer.getOutputStream());
            ApprovalProtocol.Request request=ApprovalProtocol.readRequest(in);check(request.udp,"UDP request preserved");ApprovalProtocol.writeWaiting(out);waiting.countDown();
            if(!allow.await(2,TimeUnit.SECONDS))throw new AssertionError("No approval decision");ApprovalProtocol.writeAccepted(out,"123456");
            check(SmoothWire.accept(in,out,"123456",77,12345),"Existing SmoothWire continues on SAME socket");Wire.serverClockSync(in,out);
        }catch(Throwable t){failure.compareAndSet(null,t);}finally{done.countDown();}},"test-approval-gate");server.start();
        Socket socket=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());socket.setSoTimeout(3000);
        Thread client=new Thread(()->{try{
            DataInputStream in=new DataInputStream(socket.getInputStream());DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            String pin=ApprovalProtocol.clientRequest(in,out,"Pending sender",true);granted.set(true);
            SmoothWire.Hello hello=SmoothWire.connect(in,out,pin,true);check(hello.sessionId==77&&hello.udpPort==12345,"Original session negotiated after consent");Wire.clientClockSync(in,out);
        }catch(Throwable t){failure.compareAndSet(null,t);}},"test-approval-client");client.start();
        try{check(waiting.await(2,TimeUnit.SECONDS),"WAIT published");check(!granted.get(),"No grant before explicit decision");allow.countDown();check(done.await(4,TimeUnit.SECONDS),"Approved exchange ends");client.join(2000);check(!client.isAlive(),"Client completed");if(failure.get()!=null)throw new AssertionError(failure.get());}
        finally{allow.countDown();socket.close();listener.close();server.join(1000);client.join(1000);}
    }
    private static void fourParallelConnections()throws Exception{
        ServerSocket listener=new ServerSocket(0,8,InetAddress.getLoopbackAddress());ExecutorService executor=Executors.newFixedThreadPool(8);List<Socket> sockets=Collections.synchronizedList(new ArrayList<Socket>());
        CountDownLatch received=new CountDownLatch(4),release=new CountDownLatch(1),serverDone=new CountDownLatch(4);AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread acceptor=new Thread(()->{try{for(int count=0;count<4;count++){
            Socket peer=listener.accept();sockets.add(peer);executor.execute(()->{
                FrameBufferPool pool=new FrameBufferPool(SmoothWire.MAX_VIDEO_BYTES,1024*1024,8);
                try{
                    peer.setSoTimeout(5000);DataInputStream in=new DataInputStream(peer.getInputStream());DataOutputStream out=new DataOutputStream(peer.getOutputStream());
                    ApprovalProtocol.Request req=ApprovalProtocol.readRequest(in);int id=Integer.parseInt(req.name.substring(4));check(!req.udp,"Each parallel TCP mode independent");
                    ApprovalProtocol.writeWaiting(out);received.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("Approval batch timeout");
                    ApprovalProtocol.writeAccepted(out,"123456");SmoothWire.accept(in,out,"123456",id+100,0);Wire.serverClockSync(in,out);
                    SmoothWire.Message config=SmoothWire.readMessage(in,pool);check(config.type==SmoothWire.CONFIG&&config.config.generation==id+1,"Configuration routed to its own session");SmoothWire.writeConfigAck(out,id+1);
                    for(int seq=0;seq<12;seq++){
                        SmoothWire.Message message=SmoothWire.readMessage(in,pool);check(message.type==SmoothWire.VIDEO,"Parallel video type");
                        StreamFrame f=message.frame;try{check(f.generation==id+1&&f.sequence==seq&&f.length==257&&f.data[f.offset]==(byte)id&&f.data[f.offset+256]==(byte)seq,"Four sessions never mix buffers or metadata");}finally{f.release();}
                    }
                    check(SmoothWire.readMessage(in,pool).type==SmoothWire.END,"Independent end message");
                }catch(Throwable e){failure.compareAndSet(null,e);}finally{pool.close();try{peer.close();}catch(IOException ignored){}serverDone.countDown();}
            });
        }}catch(Throwable e){failure.compareAndSet(null,e);}},"test-four-acceptor");acceptor.start();
        List<Future<?>> clients=new ArrayList<>();
        try{
            for(int id=0;id<4;id++){final int session=id;clients.add(executor.submit(()->{
                FrameBufferPool pool=new FrameBufferPool(SmoothWire.MAX_VIDEO_BYTES,1024*1024,8);
                try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort())){
                    sockets.add(socket);socket.setSoTimeout(5000);DataInputStream in=new DataInputStream(socket.getInputStream());DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                    String pin=ApprovalProtocol.clientRequest(in,out,"Peer"+session,false);SmoothWire.Hello hello=SmoothWire.connect(in,out,pin,false);check(hello.sessionId==session+100,"Per-client negotiated ID");Wire.clientClockSync(in,out);
                    SmoothWire.writeConfig(out,new SmoothWire.Config(session+1,1920,1080,new byte[][]{{0,0,0,1,0x67,1,2,3},{0,0,0,1,0x68,1}}));
                    SmoothWire.Message ack=SmoothWire.readMessage(in,pool);check(ack.type==SmoothWire.CONFIG_ACK&&ack.generation==session+1,"Per-client ACK");
                    for(int seq=0;seq<12;seq++){byte[] data=pool.acquire(257);data[0]=(byte)session;data[256]=(byte)seq;StreamFrame frame=new StreamFrame(session+1,seq,seq*16667,seq==0?1:0,1_000_000+seq,0,data,0,257,pool);try{SmoothWire.writeVideo(out,frame);}finally{frame.release();}}
                    SmoothWire.writeEnd(out);
                }catch(Exception e){throw new RuntimeException(e);}finally{pool.close();}
            }));}
            check(received.await(4,TimeUnit.SECONDS),"Four clients can wait independently before approval");release.countDown();
            for(Future<?> future:clients)future.get(7,TimeUnit.SECONDS);check(serverDone.await(4,TimeUnit.SECONDS),"All four independent sessions finish");if(failure.get()!=null)throw new AssertionError(failure.get());
        }finally{release.countDown();listener.close();synchronized(sockets){for(Socket s:sockets)try{s.close();}catch(IOException ignored){}}executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);acceptor.join(1000);}
    }
}
