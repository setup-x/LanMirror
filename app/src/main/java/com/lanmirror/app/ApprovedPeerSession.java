package com.lanmirror.app;

import android.view.Surface;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One approved peer, with private codec/pool/UDP/control workers. Never used before user approval. */
final class ApprovedPeerSession implements AutoCloseable {
    interface Listener {void onStatus(String text);void onVideoSize(int width,int height);void onFirstFrame();
        default void onMetrics(StreamMetrics metrics) {}
        default void onClockReady(Wire.ClockSync clock) {}
    }
    private final Socket peer;private final DataInputStream input;private final DataOutputStream output;
    private final Surface surface;private final String pin;private final boolean expectedUdp;private final Listener listener;
    private final FrameBufferPool pool=new FrameBufferPool(SmoothWire.MAX_VIDEO_BYTES,8*1024*1024,16);
    private final ArrayBlockingQueue<Control> controls=new ArrayBlockingQueue<>(16);
    private final Object controlGate=new Object();private final AtomicReference<String> firstFailure=new AtomicReference<>();
    private volatile boolean closed;private volatile AsyncSurfaceDecoder decoder;private volatile DatagramSocket datagrams;
    private volatile UdpVideoTransport.Receiver udp;private Thread udpThread,controlThread;
    private long lastSyncNs=Long.MIN_VALUE,lastHeartbeatNs;
    private volatile long lastDecoderReportNs=System.nanoTime(),lastNetworkReadNs=System.nanoTime();private volatile String lastDecoderReport="等待解码器报告";
    ApprovedPeerSession(Socket peer,DataInputStream input,DataOutputStream output,Surface surface,String pin,boolean expectedUdp,Listener listener){this.peer=peer;this.input=input;this.output=output;this.surface=surface;this.pin=pin;this.expectedUdp=expectedUdp;this.listener=listener;}
    String endReason(){String reason=firstFailure.get();return reason==null?"发送端已断开":reason;}
    void cancel(String reason){if(reason!=null)firstFailure.compareAndSet(null,reason);closed=true;quiet(peer);DatagramSocket socket=datagrams;if(socket!=null)socket.close();Thread t=udpThread;if(t!=null)t.interrupt();t=controlThread;if(t!=null)t.interrupt();}
    private void status(String text){if(!closed)listener.onStatus(text);}
    private void failure(String text){firstFailure.compareAndSet(null,text);status(text);cancel(null);}
    void run()throws IOException {
        try{
            if(closed)return;peer.setSoTimeout(5000);peer.setTcpNoDelay(true);peer.setReceiveBufferSize(256*1024);
            DatagramSocket socket=new DatagramSocket(0);datagrams=socket;
            if(closed){socket.close();return;}
            long token;SecureRandom random=new SecureRandom();do{token=random.nextLong();}while(token==0);
            boolean useUdp=SmoothWire.accept(input,output,pin,token,socket.getLocalPort());
            if(useUdp!=expectedUdp)throw new IOException("批准请求与实际传输模式不一致");
            Wire.ClockSync clock=Wire.serverClockSync(input,output);peer.setSoTimeout(0);
            if(closed)return;listener.onClockReady(clock);if(surface==null||!surface.isValid())throw new IOException("显示区域已经失效");
            controlThread=new Thread(this::writeControls,"multi-peer-control");controlThread.start();
            decoder=new AsyncSurfaceDecoder(surface,clock,new AsyncSurfaceDecoder.Listener(){
                public void onConfigured(long generation){enqueue(new Control(SmoothWire.CONFIG_ACK,generation));}
                public void onVideoSize(int width,int height){if(!closed)listener.onVideoSize(width,height);}
                public void onFirstFrameRendered(){if(!closed)listener.onFirstFrame();}
                public void onMetrics(StreamMetrics metrics){if(!closed)try{listener.onMetrics(metrics);}catch(RuntimeException ignored){}}
                public void onStatus(String text){
                    lastDecoderReportNs=System.nanoTime();lastDecoderReport=text;UdpVideoTransport.Receiver transport=udp;
                    String network=transport==null?"TCP视频":"UDP完整帧="+transport.completedFrames()+" / 缺帧及依赖丢弃="+transport.lostFrames()+" / 无效="+transport.invalidDatagrams()+" / 乱序="+transport.reorderedDatagrams()+" / NACK="+transport.nackRequests()+"（不是无线丢包率）";
                    status(text+"\n每路池 分配/复用="+pool.allocations()+"/"+pool.reuseCount()+" 缓存="+pool.retainedBytes()/1048576.0+"MiB / "+pool.retainedCount()+"数组\n"+network);
                }
                public void onNeedKeyFrame(){requestSync();}
                public void onError(Exception error){failure("异步硬解失败："+describe(error)+"；设备可能达到并发解码或内存资源上限，仅结束本路");}
            });
            if(closed)return;
            if(useUdp){
                udp=new UdpVideoTransport.Receiver(socket,peer.getInetAddress(),token,pool,new UdpVideoTransport.Listener(){
                    public void onFrame(StreamFrame frame){lastNetworkReadNs=System.nanoTime();AsyncSurfaceDecoder d=decoder;if(closed||d==null)frame.release();else d.offer(frame);}
                    public void onGap(){AsyncSurfaceDecoder d=decoder;if(!closed&&d!=null)d.discontinuity();requestSync();}
                });
                udpThread=new Thread(()->{try{while(!closed)udp.receiveOnce();}catch(IOException|RuntimeException e){if(!closed)failure("UDP接收错误："+describe(e));}},"multi-peer-udp");udpThread.start();
            }else{socket.close();datagrams=null;}
            status("本路已批准并连接 · "+(useUdp?"UDP视频/TCP控制":"TCP视频")+" · RTT "+clock.roundTripNs/1e6+"ms；保持发送端原分辨率及帧率请求");
            while(!closed){
                SmoothWire.Message message=SmoothWire.readMessage(input,pool);lastNetworkReadNs=System.nanoTime();
                if(message.type==SmoothWire.CONFIG){decoder.configure(message.config);}
                else if(message.type==SmoothWire.VIDEO){StreamFrame frame=message.frame;if(closed){frame.release();break;}if(useUdp){frame.release();throw new IOException("UDP会话不允许TCP视频数据");}decoder.offer(frame);}
                else if(message.type==SmoothWire.END)break;
                else{if(message.frame!=null)message.frame.release();throw new IOException("不支持的发送端控制消息 "+message.type);}
            }
        }catch(IOException|RuntimeException e){if(!closed)firstFailure.compareAndSet(null,"本路接收结束："+describe(e));throw e;}
    }
    private void requestSync(){synchronized(controlGate){long now=System.nanoTime();if(closed||(lastSyncNs!=Long.MIN_VALUE&&now-lastSyncNs<300_000_000L))return;lastSyncNs=now;enqueue(new Control(SmoothWire.REQUEST_SYNC,0));}}
    private void enqueue(Control control){if(!closed&&!controls.offer(control))failure("本路控制队列持续积压");}
    private void writeControls(){try{while(!closed){Control control=controls.poll(250,TimeUnit.MILLISECONDS);heartbeat();if(control==null)continue;if(control.type==SmoothWire.CONFIG_ACK)SmoothWire.writeConfigAck(output,control.generation);else SmoothWire.writeSync(output);}}catch(IOException|InterruptedException|RuntimeException e){if(!closed)failure("本路控制连接结束："+describe(e));}}
    private void heartbeat(){long now=System.nanoTime(),quiet=now-lastDecoderReportNs;if(closed||decoder==null||quiet<2_000_000_000L||now-lastHeartbeatNs<1_000_000_000L)return;lastHeartbeatNs=now;status("诊断：本路解码线程报告停顿 "+quiet/1000000+"ms，最近网络完整消息/帧距今 "+(now-lastNetworkReadNs)/1000000+"ms；独立观察并非native解锁。\n"+lastDecoderReport);}
    /** Peer worker only: UI uses cancel(), never joins or invokes codec.close(). */
    private volatile AsyncSurfaceDecoder terminatingDecoder;
    boolean isDecoderTerminated(){AsyncSurfaceDecoder active=decoder;if(active!=null&&!active.isTerminated())return false;AsyncSurfaceDecoder ending=terminatingDecoder;return ending==null||ending.isTerminated();}
    @Override public void close(){
        cancel(null);
        try{UdpVideoTransport.Receiver transport=udp;if(transport!=null)transport.close();}
        catch(RuntimeException e){firstFailure.compareAndSet(null,"UDP资源释放异常："+describe(e));}
        finally{
            join(udpThread);join(controlThread);AsyncSurfaceDecoder d=decoder;
            if(d!=null){terminatingDecoder=d;decoder=null;try{d.close();}catch(RuntimeException e){firstFailure.compareAndSet(null,"解码资源释放异常："+describe(e));}}
            controls.clear();pool.close();
        }
    }
    private static void join(Thread t){if(t!=null&&t!=Thread.currentThread())try{t.join(100);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static void quiet(Closeable c){if(c!=null)try{c.close();}catch(IOException ignored){}}
    private static String describe(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static final class Control{final int type;final long generation;Control(int type,long generation){this.type=type;this.generation=generation;}}
}
