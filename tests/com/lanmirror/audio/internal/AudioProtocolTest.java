package com.lanmirror.audio.internal;

import com.lanmirror.core.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Pure protocol/loopback tests: no AudioRecord, AudioTrack or physical sound device. */
public final class AudioProtocolTest {
    private static final AtomicInteger checks=new AtomicInteger();
    private static void check(boolean value,String text){checks.incrementAndGet();if(!value)throw new AssertionError(text);}
    private interface Work{void run()throws Exception;}
    private static void bad(Work work)throws Exception{boolean threw=false;try{work.run();}catch(IOException|IllegalArgumentException e){threw=true;}check(threw,"Malformed audio protocol rejected");}
    private static DataInputStream in(byte[] data){return new DataInputStream(new ByteArrayInputStream(data));}
    public static void main(String[] args)throws Exception{
        formatBounds();selection();approvedSeparateLane();fourLanes();
        System.out.println("PASS: "+checks.get()+" audio approval/lane/selection assertions (v1/v2, bounded PCM, exact capability, same video socket, audio-only shutdown, four isolated lanes, latest/manual/mute; NOT real capture/playback)");
    }
    private static void formatBounds()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        PcmAudioFrame frame=new PcmAudioFrame(5,123456,new byte[]{1,2,3,4,5,6,7,8},8);AudioLaneWire.writeFrame(out,frame);
        AudioLaneWire.Message received=AudioLaneWire.read(in(bytes.toByteArray()));check(received.frame.sequence==5&&received.frame.capturedAtNs==123456&&Arrays.equals(frame.data,received.frame.data),"PCM exact metadata and bytes");
        byte[] good=bytes.toByteArray();for(int size=0;size<good.length;size++){final byte[] truncated=Arrays.copyOf(good,size);bad(()->AudioLaneWire.read(in(truncated)));}
        for(int size:new int[]{-1,0,1,2,3,3841,Integer.MAX_VALUE}){
            bytes.reset();out.writeInt(AudioLaneWire.FRAME);out.writeLong(1);out.writeLong(1);out.writeInt(size);byte[] data=bytes.toByteArray();bad(()->AudioLaneWire.read(in(data)));
        }
        bytes.reset();AudioLaneWire.writeStatus(out,"仅声音失败，视频继续");check(AudioLaneWire.read(in(bytes.toByteArray())).status.contains("视频继续"),"Audio status separate and bounded");
        bytes.reset();out.writeInt(AudioLaneWire.STATUS);out.writeInt(513);byte[] tooLong=bytes.toByteArray();bad(()->AudioLaneWire.read(in(tooLong)));
        bytes.reset();out.writeInt(AudioLaneWire.STATUS);out.writeInt(2);out.write(new byte[]{(byte)0xc3,0x28});byte[] invalidUtf=bytes.toByteArray();bad(()->AudioLaneWire.read(in(invalidUtf)));
        for(int port:new int[]{-1,65536})bad(()->new ApprovalProtocol.AudioGrant("123456",port,1,2,"状态"));
        bad(()->new ApprovalProtocol.AudioGrant("123456",0,1,2,"状态"));bad(()->new ApprovalProtocol.AudioGrant("123456",10,0,0,"状态"));
        bad(()->new ApprovalProtocol.AudioGrant("12345",10,1,2,"状态"));
        bytes.reset();ApprovalProtocol.writeWaiting(out);ApprovalProtocol.writeAcceptedAudio(out,new ApprovalProtocol.AudioGrant("001234",12345,99,88,"声音已批准"));
        ByteArrayOutputStream request=new ByteArrayOutputStream();ApprovalProtocol.AudioGrant grant=ApprovalProtocol.clientRequestWithAudio(in(bytes.toByteArray()),new DataOutputStream(request),"测试设备",true);
        check(grant.port==12345&&grant.high==99&&grant.low==88&&grant.pin.equals("001234"),"Audio grant exact framing");
        ApprovalProtocol.Request req=ApprovalProtocol.readRequest(in(request.toByteArray()));check(req.audio&&req.udp,"Version2 requests audio explicitly");
        bytes.reset();ApprovalProtocol.writeAcceptedAudio(out,new ApprovalProtocol.AudioGrant("123456",0,0,0,"音频不可用，视频继续"));
        check(ApprovalProtocol.clientRequestWithAudio(in(bytes.toByteArray()),new DataOutputStream(new ByteArrayOutputStream()),"peer",false).port==0,"Receiver can explicitly disable only audio");
        bytes.reset();ApprovalProtocol.writeRejected(out,"用户拒绝");byte[] rejected=bytes.toByteArray();bad(()->ApprovalProtocol.clientRequestWithAudio(in(rejected),new DataOutputStream(new ByteArrayOutputStream()),"peer",true));
    }
    private static void selection(){
        AudioSelectionState state=new AudioSelectionState();check(state.selected()==0,"Initially silent");
        state.joined(0,99);check(state.selected()==99,"First source selected");state.joined(1,2);check(state.selected()==2,"Latest JOIN wins even with older request id");
        state.toggle(0,99);check(state.selected()==99,"Manual selection independent from visual focus");state.toggle(0,99);check(state.selected()==0,"Selected button mutes all");
        check(!state.joined(1,2)&&state.selected()==0,"Repeated status does not undo manual mute");
        state.joined(2,1);check(state.selected()==1,"New joined source overrides prior mute by requested default");
        state.left(1);check(state.selected()==2,"Selected source end falls back to latest remaining join");
        state.left(99);check(state.selected()==2,"Unselected source end preserves current source");
        state.joined(0,101);state.joined(0,102);check(!state.left(101)&&state.selected()==102,"Late end cannot mute replacement");
        check(!state.toggle(0,101)&&state.selected()==102,"Stale button cannot select old session");
        long revision=state.revision();state.clear();check(state.selected()==0&&state.revision()>revision,"Stop clears sound selection");
    }
    private static void approvedSeparateLane()throws Exception{
        InetAddress loop=InetAddress.getLoopbackAddress();ServerSocket control=new ServerSocket(0,4,loop);CountDownLatch frames=new CountDownLatch(3),ended=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicReference<AudioLaneReceiver> receiver=new AtomicReference<>();
        CountDownLatch sourceError=new CountDownLatch(1);
        Thread server=new Thread(()->{FrameBufferPool pool=new FrameBufferPool(SmoothWire.MAX_VIDEO_BYTES,1024*1024,4);
            try(Socket peer=control.accept()){
                peer.setSoTimeout(5000);DataInputStream input=new DataInputStream(peer.getInputStream());DataOutputStream output=new DataOutputStream(peer.getOutputStream());
                ApprovalProtocol.Request req=ApprovalProtocol.readRequest(input);check(req.audio&&!req.udp,"Audio requested independently of videoTCP");ApprovalProtocol.writeWaiting(output);
                AudioLaneReceiver lane=new AudioLaneReceiver(peer.getInetAddress(),new AudioLaneReceiver.Listener(){
                    public void onFrame(PcmAudioFrame frame,long local){try{check(frame.length==3840&&frame.data[0]==7,"PCM belongs to approved peer");frames.countDown();}catch(Throwable e){failure.compareAndSet(null,e);}}
                    public void onStatus(String text){if(text.contains("测试音源错误"))sourceError.countDown();}
                });receiver.set(lane);
                ApprovalProtocol.writeAcceptedAudio(output,new ApprovalProtocol.AudioGrant("123456",lane.port(),lane.tokenHigh,lane.tokenLow,"可用"));
                check(!SmoothWire.accept(input,output,"123456",555,0),"Video handshake stays on ORIGINAL socket");Wire.ClockSync clock=Wire.serverClockSync(input,output);lane.start(clock);
                check(SmoothWire.readMessage(input,pool).type==SmoothWire.CONFIG,"Video config unaffected by audio prelude");SmoothWire.writeConfigAck(output,1);
                SmoothWire.Message video=SmoothWire.readMessage(input,pool);check(video.type==SmoothWire.VIDEO,"Video still passes after audio-only ending");video.frame.release();
                check(SmoothWire.readMessage(input,pool).type==SmoothWire.END,"Original video channel closes normally");
            }catch(Throwable e){failure.compareAndSet(null,e);}finally{pool.close();AudioLaneReceiver lane=receiver.get();if(lane!=null)lane.close();ended.countDown();}
        },"test-audio-control");server.start();
        AudioLaneSender sender=null;FrameBufferPool pool=new FrameBufferPool(SmoothWire.MAX_VIDEO_BYTES,1024*1024,4);
        try(Socket socket=new Socket(loop,control.getLocalPort())){
            socket.setSoTimeout(5000);DataInputStream input=new DataInputStream(socket.getInputStream());DataOutputStream output=new DataOutputStream(socket.getOutputStream());
            ApprovalProtocol.AudioGrant grant=ApprovalProtocol.clientRequestWithAudio(input,output,"sender",false);SmoothWire.connect(input,output,grant.pin,false);Wire.clientClockSync(input,output);
            SmoothWire.writeConfig(output,new SmoothWire.Config(1,1920,1080,new byte[][]{{0,0,0,1,0x67,1},{0,0,0,1,0x68,1}}));check(SmoothWire.readMessage(input,pool).type==SmoothWire.CONFIG_ACK,"Video ack unchanged");
            try(Socket intruder=new Socket(loop,grant.port)){intruder.setSoTimeout(1000);bad(()->AudioLaneWire.connect(new DataInputStream(intruder.getInputStream()),new DataOutputStream(intruder.getOutputStream()),grant.high^1,grant.low));}
            CountDownLatch ready=new CountDownLatch(1);sender=new AudioLaneSender(loop,grant.port,grant.high,grant.low,new AudioLaneSender.Listener(){public void onStatus(String text){ready.countDown();}public void onError(String text){failure.compareAndSet(null,new AssertionError(text));}});sender.start();
            check(ready.await(3,TimeUnit.SECONDS),"Dedicated audio capability accepted after invalid attempt");
            for(int i=0;i<3;i++){byte[] data=new byte[3840];data[0]=7;sender.offer(new PcmAudioFrame(i,System.nanoTime(),data,data.length));}
            check(frames.await(3,TimeUnit.SECONDS),"Separate audio delivers PCM");sender.finishWithStatus("测试音源错误：停止音频但视频继续");
            check(sourceError.await(2,TimeUnit.SECONDS),"Audio terminal status reaches receiver");
            byte[] data=pool.acquire(4);StreamFrame video=new StreamFrame(1,0,1,1,System.nanoTime(),0,data,0,4,pool);try{SmoothWire.writeVideo(output,video);}finally{video.release();}SmoothWire.writeEnd(output);
            check(ended.await(3,TimeUnit.SECONDS),"Video unaffected by audio-only shutdown");if(failure.get()!=null)throw new AssertionError(failure.get());
        }finally{if(sender!=null)sender.close();AudioLaneReceiver lane=receiver.get();if(lane!=null)lane.close();control.close();pool.close();server.join(1000);}
    }
    private static void fourLanes()throws Exception{
        InetAddress loop=InetAddress.getLoopbackAddress();ArrayList<AudioLaneReceiver> lanes=new ArrayList<>();ArrayList<AudioLaneSender> senders=new ArrayList<>();CountDownLatch received=new CountDownLatch(4);AtomicReference<Throwable> failure=new AtomicReference<>();
        try{for(int i=0;i<4;i++){final int index=i;AudioLaneReceiver lane=new AudioLaneReceiver(loop,new AudioLaneReceiver.Listener(){
            public void onFrame(PcmAudioFrame frame,long local){try{check(frame.data[0]==index&&frame.sequence==index,"Four audio lanes do not mix source bytes");received.countDown();}catch(Throwable e){failure.compareAndSet(null,e);}}
            public void onStatus(String text){}
        });lanes.add(lane);lane.start(new Wire.ClockSync(0,0));CountDownLatch ready=new CountDownLatch(1);
            AudioLaneSender sender=new AudioLaneSender(loop,lane.port(),lane.tokenHigh,lane.tokenLow,new AudioLaneSender.Listener(){public void onStatus(String text){ready.countDown();}public void onError(String text){failure.compareAndSet(null,new AssertionError(text));}});senders.add(sender);sender.start();check(ready.await(3,TimeUnit.SECONDS),"Each audio lane starts independently");byte[] pcm=new byte[3840];pcm[0]=(byte)index;sender.offer(new PcmAudioFrame(index,System.nanoTime(),pcm,pcm.length));
        }check(received.await(3,TimeUnit.SECONDS),"All four PCM lanes delivered");if(failure.get()!=null)throw new AssertionError(failure.get());}
        finally{for(AudioLaneSender sender:senders)sender.close();for(AudioLaneReceiver lane:lanes)lane.close();}
    }
}
