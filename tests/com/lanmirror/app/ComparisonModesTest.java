package com.lanmirror.app;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** A/B equality and deterministic scheduler bounds, not an Android FPS benchmark. */
public final class ComparisonModesTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static byte[] encode(byte[] frame,boolean direct,long pts,int flags,long stamp)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();new FrameStreamWriter(new DataOutputStream(bytes),direct).write(frame,pts,flags,stamp);return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        for(int size:new int[]{0,1,27,16383,16384,16385,65536,Wire.MAX_PACKET-Wire.VIDEO_HEADER_SIZE}){
            byte[] frame=new byte[size];for(int i=0;i<size;i++)frame[i]=(byte)(i*31);
            byte[] before=frame.clone(), baseline=encode(frame,false,1234567,1,987654321), experiment=encode(frame,true,1234567,1,987654321);
            check(Arrays.equals(baseline,experiment),"Direct writer byte-for-byte v2 equality size="+size);
            check(Arrays.equals(before,frame),"Never mutate source array");
            Wire.Packet packet=Wire.readPacket(new DataInputStream(new ByteArrayInputStream(experiment)));
            ByteBuffer body=ByteBuffer.wrap(packet.data);check(body.getLong()==1234567 && body.getInt()==1 && body.getLong()==987654321,"Source timestamp preserved");
            check(body.remaining()==size,"Exact body size");
        }
        CountingOutput counting=new CountingOutput();FrameStreamWriter writer=new FrameStreamWriter(new DataOutputStream(counting),true);
        for(int i=0;i<60;i++)writer.write(new byte[20000],i,0,100+i);
        check(counting.arrayWrites==120 && counting.singleWrites==0 && counting.flushes==60,"Two app writes and immediate flush per frame; not packet count");
        for(boolean enabled:new boolean[]{false,true}){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();FrameStreamWriter w=new FrameStreamWriter(new DataOutputStream(bytes),enabled);
            try{w.write(new byte[Wire.MAX_PACKET-19],0,0,0);throw new AssertionError();}catch(IOException expected){checks++;}
            check(bytes.size()==0,"Oversize rejected before partial header");
        }
        boolean[] emitted={false};FrameStreamWriter fail=new FrameStreamWriter(new DataOutputStream(new OutputStream(){
            public void write(int x)throws IOException{throw new IOException("closed");}
            public void write(byte[]b,int o,int n)throws IOException{if(emitted[0])throw new IOException("body failed");emitted[0]=true;}
        }),true);
        try{fail.write(new byte[5],0,0,0);throw new AssertionError();}catch(IOException expected){checks++;}
        for(int count=1;count<=10;count++)check(!EncoderDrainPolicy.continueBurst(false,count,0,1),"Default performs no extra dequeue");
        check(EncoderDrainPolicy.continueBurst(true,1,0,1),"First ready output allows followup");
        check(EncoderDrainPolicy.continueBurst(true,3,0,1999999),"Up to four outputs in time budget");
        check(!EncoderDrainPolicy.continueBurst(true,4,0,1),"Four outputs hard bound");
        check(!EncoderDrainPolicy.continueBurst(true,1,0,2000000),"Two ms budget exact boundary");
        check(!EncoderDrainPolicy.continueBurst(true,0,0,1),"No progress stops nonblocking drain");
        check(!EncoderDrainPolicy.continueBurst(true,1,10,9),"Invalid time stops drain");
        liveCompatibility(false);liveCompatibility(true);
        System.out.println("PASS: "+checks+" A/B comparison assertions (wire equality, original TCP, flush/error boundaries, bounded encoder drain; NOT FPS)");
    }
    private static void liveCompatibility(boolean direct)throws Exception{
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(5000);
            Thread reader=new Thread(()->{
                try(Socket peer=server.accept()){
                    peer.setSoTimeout(5000);DataInputStream in=new DataInputStream(new BufferedInputStream(peer.getInputStream(),16384));DataOutputStream out=new DataOutputStream(peer.getOutputStream());
                    if(!Wire.serverHandshake(in,out,"123456"))throw new AssertionError("pairing");Wire.serverClockSync(in,out);
                    if(Wire.readConfig(Wire.readPacket(in).data).width!=1920)throw new AssertionError("CONFIG");
                    for(int i=0;i<60;i++){
                        Wire.Packet packet=Wire.readPacket(in);ByteBuffer b=ByteBuffer.wrap(packet.data);
                        if(b.getLong()!=i || b.getInt()!=(i==0?1:0) || b.getLong()!=1000+i || b.remaining()!=20000 || b.get()!=(byte)i)throw new AssertionError("wire frame");
                    }
                }catch(Throwable e){failure.set(e);}
            },"compare-wire-reader");reader.start();
            try(Socket socket=new Socket()){
                socket.connect(new InetSocketAddress("127.0.0.1",server.getLocalPort()),5000);socket.setSoTimeout(5000);socket.setTcpNoDelay(true);
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),16384));DataInputStream in=new DataInputStream(socket.getInputStream());
                check(Wire.clientHandshake(in,out,"123456"),"Original Socket handshake");Wire.clientClockSync(in,out);Wire.writeConfig(out,1920,1080);
                FrameStreamWriter writer=new FrameStreamWriter(out,direct);byte[] frame=new byte[20000];
                for(int i=0;i<60;i++){frame[0]=(byte)i;writer.write(frame,i,i==0?1:0,1000+i);}
            }
            reader.join(6500);check(!reader.isAlive(),"TCP reader completes");if(failure.get()!=null)throw new AssertionError(failure.get());check(true,"Sixty original-reader frames exact in each mode");
        }
    }
    private static final class CountingOutput extends OutputStream{
        int arrayWrites,singleWrites,flushes;
        public void write(int b){singleWrites++;} public void write(byte[]b,int o,int n){arrayWrites++;}public void flush(){flushes++;}
    }
}
