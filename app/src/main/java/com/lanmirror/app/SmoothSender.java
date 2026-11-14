package com.lanmirror.app;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Encoder owner + bounded loan queue + independent UDP/TCP writer, legacy pipeline untouched. */
final class SmoothSender implements Runnable {
    private final SenderService service;
    private final MediaProjection projection;
    private final Handler handler;
    private final String host,pin;
    private final int profile;
    private final boolean preferUdp,approval,requestAudio;
    private volatile InternalAudioCapture audioCapture;private volatile AudioLaneSender audioLane;
    private volatile String audioStatus="内部声音未启用";private volatile boolean audioFailed;
    private final int targetPort;
    private final Socket socket=new Socket();
    private final SmoothSendQueue queue=new SmoothSendQueue(12,8*1024*1024);
    private final FrameBufferPool pool=new FrameBufferPool(SmoothWire.MAX_VIDEO_BYTES,16*1024*1024,64);
    private final AtomicReference<Exception> failure=new AtomicReference<>();
    private final AtomicLong ackGeneration=new AtomicLong(-1),writeStarted=new AtomicLong(),sent=new AtomicLong(),sentBytes=new AtomicLong(),maxWrite=new AtomicLong(),maxQueueAge=new AtomicLong(),syncRequests=new AtomicLong();
    private final Object ackLock=new Object();
    private volatile boolean cancelled;
    private volatile UdpVideoTransport.Sender udpSender;
    private Thread writer,controls,watchdog;
    SmoothSender(SenderService service,MediaProjection projection,Handler handler,String host,String pin,int profile,boolean udp,boolean approval,int targetPort,boolean requestAudio){
        this.service=service;this.projection=projection;this.handler=handler;this.host=host;this.pin=pin;this.profile=profile;this.preferUdp=udp;this.approval=approval;this.targetPort=approval?targetPort:SmoothWire.PORT;this.requestAudio=requestAudio&&approval&&Build.VERSION.SDK_INT>=29;
    }
    void cancel(){cancelled=true;InternalAudioCapture capture=audioCapture;if(capture!=null)capture.close();AudioLaneSender audio=audioLane;if(audio!=null)audio.close();queue.close();try{socket.close();}catch(IOException ignored){}synchronized(ackLock){ackLock.notifyAll();}}
    private boolean stopped(){return cancelled||service.smoothStopping()||socket.isClosed()||queue.isClosed();}
    private void fail(Exception e){failure.compareAndSet(null,e);cancel();}
    @Override public void run(){
        SenderService.Encoder encoder=null;VirtualDisplay display=null;
        try {
            if(service.smoothStopping()){cancel();return;}
            service.publish("连接"+(approval?"多路接收端（需要对端允许）":"单路接收端")+targetPort+"… · "+(preferUdp?"UDP视频 / TCP控制":"TCP视频 / 异步解码"));
            socket.setTcpNoDelay(true);socket.setSendBufferSize(256*1024);socket.setReceiveBufferSize(64*1024);
            socket.connect(new InetSocketAddress(host,targetPort),10000);socket.setSoTimeout(approval?ApprovalProtocol.CLIENT_TIMEOUT_MS:10000);
            DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream(),16*1024));
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),16*1024));
            String sessionPin=pin;ApprovalProtocol.AudioGrant audioGrant=null;
            if(approval){
                service.publish("连接已建立，等待接收端允许（最多30秒）…");
                if(requestAudio){audioGrant=ApprovalProtocol.clientRequestWithAudio(in,out,Build.MANUFACTURER+" "+Build.MODEL,preferUdp);sessionPin=audioGrant.pin;audioStatus=audioGrant.status;}
                else sessionPin=ApprovalProtocol.clientRequest(in,out,Build.MANUFACTURER+" "+Build.MODEL,preferUdp);
                socket.setSoTimeout(10000);
            }
            SmoothWire.Hello hello=SmoothWire.connect(in,out,sessionPin,preferUdp);
            Wire.ClockSync clock=Wire.clientClockSync(in,out);socket.setSoTimeout(0);
            if(hello.udp){
                DatagramSocket datagrams=new DatagramSocket();
                try{datagrams.setSendBufferSize(256*1024);datagrams.setReceiveBufferSize(128*1024);udpSender=new UdpVideoTransport.Sender(datagrams,new InetSocketAddress(socket.getInetAddress(),hello.udpPort),hello.sessionId);}
                catch(Exception e){datagrams.close();throw e;}
            }
            if(audioGrant!=null&&audioGrant.port>0)startAudio(audioGrant);
            controls=new Thread(()->readControls(in),"smooth-control");controls.start();
            writer=new Thread(()->writeFrames(out),"smooth-video-writer");writer.start();
            watchdog=new Thread(()->{
                while(!stopped()){
                    try{Thread.sleep(100);}catch(InterruptedException e){return;}
                    long start=writeStarted.get();
                    if(start!=0&&System.nanoTime()-start>2_000_000_000L){fail(new IOException("视频写入阻塞超过2秒"));return;}
                }
            },"smooth-writer-watchdog");watchdog.setDaemon(true);watchdog.start();
            DisplayMetrics metrics=new DisplayMetrics();service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
            long captured=service.requestedSize;int sourceW=captured!=0?(int)(captured>>>32):metrics.widthPixels,sourceH=captured!=0?(int)captured:metrics.heightPixels;
            int[] size=StreamProfile.sizeFor(sourceW,sourceH,profile);
            encoder=service.createEncoder(size[0],size[1],StreamProfile.bitrate(profile));queue.begin(size[0],size[1]);
            display=projection.createVirtualDisplay("LanMirrorSmooth",size[0],size[1],metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,encoder.surface,null,handler);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long sequence=0,lastSync=0,nextSizePoll=0,reportAt=System.nanoTime(),encoded=0,lastSent=0,lastBytes=0,copyMax=0;
            boolean wantKey=true;
            while(!stopped()){
                if(Build.VERSION.SDK_INT<34&&SystemClock.elapsedRealtime()>=nextSizePoll){
                    service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
                    service.requestedSize=((long)metrics.widthPixels<<32)|(metrics.heightPixels&0xffffffffL);nextSizePoll=SystemClock.elapsedRealtime()+500;
                }
                long resized=service.requestedSize;int rw=(int)(resized>>>32),rh=(int)resized;
                if(rw>0&&rh>0&&(rw!=sourceW||rh!=sourceH)){
                    sourceW=rw;sourceH=rh;int[] newSize=StreamProfile.sizeFor(rw,rh,profile);
                    if(newSize[0]!=size[0]||newSize[1]!=size[1]){
                        display.setSurface(null);encoder.close();encoder=null;size=newSize;
                        encoder=service.createEncoder(size[0],size[1],StreamProfile.bitrate(profile));queue.begin(size[0],size[1]);
                        display.resize(size[0],size[1],metrics.densityDpi);display.setSurface(encoder.surface);wantKey=true;lastSync=0;
                    }
                }
                long now=System.nanoTime();
                if(queue.consumeKeyRequest())wantKey=true;
                if(!queue.awaitingKey())wantKey=false;
                if((wantKey||queue.awaitingKey())&&now-lastSync>=300_000_000L){
                    Bundle request=new Bundle();request.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);
                    try{encoder.codec.setParameters(request);}catch(RuntimeException ignored){}
                    wantKey=false;lastSync=now;
                }
                int index=encoder.codec.dequeueOutputBuffer(info,2000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){cacheConfig(encoder.codec.getOutputFormat());}
                else if(index>=0){
                    long encodedAt=System.nanoTime();
                    try{
                        if(info.size>0){
                            if(info.size>SmoothWire.MAX_VIDEO_BYTES)throw new IOException("编码视频帧超过协议上限");
                            ByteBuffer source=encoder.codec.getOutputBuffer(index);
                            if(source==null||info.offset<0||info.size>source.capacity()-info.offset)throw new IOException("Invalid encoder output range");
                            source.position(info.offset);source.limit(info.offset+info.size);
                            if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0){byte[] csd=new byte[info.size];source.get(csd);queue.addConfig(csd);}
                            else {
                                byte[] data=pool.acquire(info.size);boolean transferred=false;
                                try{
                                    source.get(data,0,info.size);copyMax=Math.max(copyMax,System.nanoTime()-encodedAt);
                                    StreamFrame frame=new StreamFrame(queue.generation(),sequence++,info.presentationTimeUs,info.flags,encodedAt,0,data,0,info.size,pool);
                                    transferred=true;queue.offer(frame);encoded++;
                                }finally{if(!transferred)pool.recycle(data);}
                            }
                        }
                        if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)throw new IOException("编码器已结束");
                    }finally{encoder.codec.releaseOutputBuffer(index,false);}
                }
                now=System.nanoTime();
                if(now-reportAt>=1_000_000_000L){
                    double seconds=(now-reportAt)/1e9;long delivered=sent.get(),bytes=sentBytes.get();UdpVideoTransport.Sender udp=udpSender;
                    service.publish(String.format(Locale.US,
                        "流畅模式 · %s · %d×%d @60 / %dMbps\n编码 %.1f fps / 已写出 %.1f fps · %.1f Mbps\n队列 %d/12帧 %.1f/8MB · 本秒排队/写入最大 %.1f/%.1f ms\n应用丢帧 %d / 队满 %d / GOP恢复 %d / 对端请求 %d\n池分配/复用 %d/%d，缓存 %.1fMB · copy最大 %.2fms\nRTT %.1fms · UDP数据报/重传 %d/%d\n编码器 %s\n流畅优先允许短时积压；已写出不等于UDP已送达，不代表70ms达标",
                        udp!=null?"UDP视频+TCP控制":"TCP视频",size[0],size[1],StreamProfile.bitrate(profile)/1000000,
                        encoded/seconds,(delivered-lastSent)/seconds,(bytes-lastBytes)*8/seconds/1000000,
                        queue.depth(),queue.queuedBytes()/1048576.0,maxQueueAge.getAndSet(0)/1e6,maxWrite.getAndSet(0)/1e6,
                        queue.dropped(),queue.overflows(),queue.recoveries(),syncRequests.get(),pool.allocations(),pool.reuseCount(),pool.retainedBytes()/1048576.0,copyMax/1e6,
                        clock.roundTripNs/1e6,udp==null?0:udp.sentDatagrams(),udp==null?0:udp.retransmittedDatagrams(),encoder.name)+"\n内部声音："+audioStatus);
                    reportAt=now;encoded=0;lastSent=delivered;lastBytes=bytes;copyMax=0;
                }
            }
        }catch(Exception e){failure.compareAndSet(null,e);}
        finally{
            cancel();if(watchdog!=null)watchdog.interrupt();
            UdpVideoTransport.Sender udp=udpSender;if(udp!=null)udp.close();
            join(writer);join(controls);
            if(display!=null)try{display.release();}catch(RuntimeException ignored){}
            if(encoder!=null)encoder.close();pool.close();
            Exception error=failure.get();if(error!=null&&!service.smoothStopping())service.publish("流畅发送结束："+SenderService.message(error)+"；UDP不可达时可手动改TCP流畅模式");
        }
    }
    private void startAudio(ApprovalProtocol.AudioGrant grant){
        try{
            AudioLaneSender lane=new AudioLaneSender(socket.getInetAddress(),grant.port,grant.high,grant.low,new AudioLaneSender.Listener(){
                public void onStatus(String text){if(!audioFailed)audioStatus=text;}
                public void onError(String text){audioFailed=true;audioStatus=text;InternalAudioCapture capture=audioCapture;if(capture!=null)capture.close();}
            });audioLane=lane;
            InternalAudioCapture capture=new InternalAudioCapture(projection,new InternalAudioCapture.Listener(){
                public void onFrame(PcmAudioFrame frame){lane.offer(frame);}
                public void onStatus(String text){if(!audioFailed)audioStatus=text;}
                public void onError(String text){audioFailed=true;audioStatus=text;lane.finishWithStatus(text);InternalAudioCapture current=audioCapture;if(current!=null)current.close();}
            });audioCapture=capture;
            if(stopped()){capture.close();lane.close();return;}lane.start();capture.start();
        }catch(RuntimeException error){audioFailed=true;audioStatus="内部声音不可用："+error.getMessage()+"；视频继续";InternalAudioCapture capture=audioCapture;if(capture!=null)capture.close();AudioLaneSender lane=audioLane;if(lane!=null)lane.finishWithStatus(audioStatus);}
    }
    private void readControls(DataInputStream in){
        try{
            while(!stopped()){
                SmoothWire.Message message=SmoothWire.readMessage(in,pool);
                if(message.type==SmoothWire.REQUEST_SYNC){syncRequests.incrementAndGet();queue.requestRecovery();}
                else if(message.type==SmoothWire.CONFIG_ACK){synchronized(ackLock){ackGeneration.set(message.generation);ackLock.notifyAll();}}
                else if(message.type==SmoothWire.END){cancel();break;}
                else {if(message.frame!=null)message.frame.release();throw new IOException("Unexpected receiver control");}
            }
        }catch(Exception e){if(!stopped())fail(e);}
        finally{cancel();}
    }
    private void writeFrames(DataOutputStream out){
        long configured=-1;
        try{
            while(!stopped()){
                SmoothSendQueue.Entry entry=queue.poll(20);if(entry==null)continue;
                StreamFrame frame=entry.frame;
                try{
                    if(configured!=frame.generation){
                        if(entry.config==null)throw new IOException("Generation lacks key/config bootstrap");
                        writeStarted.set(System.nanoTime());SmoothWire.writeConfig(out,entry.config);writeStarted.set(0);
                        long deadline=System.nanoTime()+3_000_000_000L;
                        synchronized(ackLock){while(!stopped()&&ackGeneration.get()!=frame.generation){long left=deadline-System.nanoTime();if(left<=0)throw new IOException("接收解码配置确认超时");ackLock.wait(Math.max(1,left/1000000L));}}
                        if(stopped())break;configured=frame.generation;
                    }
                    long start=System.nanoTime();writeStarted.set(start);maximum(maxQueueAge,Math.max(0,start-frame.encodedAtNs));
                    UdpVideoTransport.Sender udp=udpSender;if(udp!=null)udp.send(frame);else SmoothWire.writeVideo(out,frame);
                    maximum(maxWrite,System.nanoTime()-start);queue.markSent(entry);sent.incrementAndGet();sentBytes.addAndGet(frame.length);
                }finally{writeStarted.set(0);frame.release();}
            }
        }catch(Exception e){if(!stopped())fail(e);if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
        finally{cancel();}
    }
    private void cacheConfig(MediaFormat format){
        for(String name:new String[]{"csd-0","csd-1","csd-2"}){
            if(!format.containsKey(name))continue;ByteBuffer buffer=format.getByteBuffer(name);if(buffer==null||!buffer.hasRemaining())continue;
            ByteBuffer copy=buffer.duplicate();byte[] data=new byte[copy.remaining()];copy.get(data);queue.addConfig(data);
        }
    }
    private static void maximum(AtomicLong target,long sample){long old;do{old=target.get();if(old>=sample)return;}while(!target.compareAndSet(old,sample));}
    private static void join(Thread thread){if(thread!=null&&thread!=Thread.currentThread())try{thread.join(250);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
