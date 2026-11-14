package com.lanmirror.app;

import android.view.Surface;
import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Four isolated approved streams. This manager is single-use; create a fresh instance after stop(). */
public final class MultiReceiver {
    public interface SurfaceProvider {Surface getSurface(int slot);}
    public interface Listener {
        void onApprovalRequested(long requestId,String name,String address);
        void onRequestFinished(long requestId,String reason);
        void onSlotChanged(int slot,long sessionId,String name,String address,String status);
        void onVideoSize(int slot,long sessionId,int width,int height);
        void onStatus(String status);
        default void onFirstFrame(int slot,long sessionId){}
        default void onMetrics(int slot,long sessionId,StreamMetrics metrics){}
        default void onListening(int port){}
        default void onStopped(){}
        default void onAudioSelection(long sessionId,long revision){}
        default void onAudioStatus(int slot,long sessionId,String text){}
        default void onAudioPlaybackStatus(int slot,long sessionId,String text,boolean error){}
    }
    private static final DecodeLeaseBudget DECODE_BUDGET=new DecodeLeaseBudget(4);
    private final SurfaceProvider surfaces;private final Listener listener;
    private final AudioSelectionState audioSelection=new AudioSelectionState();private final Object audioGate=new Object();
    private final RealtimeAudioPlayer audioPlayer;private long appliedAudioSource=-1;
    private final AdmissionSlots admissions=new AdmissionSlots(4,8,12);
    private final Semaphore acceptedPermits=new Semaphore(12);
    private final ConcurrentHashMap<Long,Peer> peers=new ConcurrentHashMap<>();
    private final AtomicLong ids=new AtomicLong();private final AtomicBoolean started=new AtomicBoolean(),closed=new AtomicBoolean();
    private final Object registration=new Object();private volatile ServerSocket server;private volatile Thread acceptThread;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"multi-request-deadlines");t.setDaemon(true);return t;});
    public MultiReceiver(Context context,SurfaceProvider surfaces,Listener listener){
        if(context==null||surfaces==null||listener==null)throw new IllegalArgumentException("Missing manager arguments");this.surfaces=surfaces;this.listener=listener;
        audioPlayer=new RealtimeAudioPlayer(context.getApplicationContext(),new RealtimeAudioPlayer.Listener(){
            public void onStatus(long id,String text){playbackStatus(id,text,false);}
            public void onError(long id,String text){playbackStatus(id,text,true);}
        });
    }
    public void selectAudio(int slot,long sessionId){synchronized(audioGate){Peer peer=peers.get(sessionId);if(peer!=null&&current(peer)&&peer.slot==slot&&audioSelection.toggle(slot,sessionId))publishAudioSelection();}}
    private void joinAudio(Peer peer){synchronized(audioGate){if(current(peer)&&audioSelection.joined(peer.slot,peer.id))publishAudioSelection();}}
    private void leaveAudio(long id){synchronized(audioGate){if(audioSelection.left(id))publishAudioSelection();}}
    private void publishAudioSelection(){
        long selected=closed.get()?0:audioSelection.selected();if(selected!=appliedAudioSource){appliedAudioSource=selected;audioPlayer.select(selected);}
        if(!closed.get())try{listener.onAudioSelection(selected,audioSelection.revision());}catch(RuntimeException ignored){}
        String failure=audioPlayer.failureReason();if(selected>0&&failure!=null)playbackStatus(selected,failure,true);
    }
    private void playbackStatus(long id,String text,boolean error){
        if(audioPlayer!=null&&audioPlayer.failureReason()!=null){for(Peer peer:peers.values())if(current(peer))try{listener.onAudioPlaybackStatus(peer.slot,peer.id,text,true);}catch(RuntimeException ignored){}}
        else{Peer peer=peers.get(id);if(peer!=null&&current(peer))try{listener.onAudioPlaybackStatus(peer.slot,id,text,error);}catch(RuntimeException ignored){}}
    }
    private void audioStatus(Peer peer,String text){if(current(peer))try{listener.onAudioStatus(peer.slot,peer.id,text);}catch(RuntimeException ignored){}}
    public void start(){if(closed.get()||!started.compareAndSet(false,true))return;Thread t=new Thread(this::acceptLoop,"multi-approval-listener");acceptThread=t;t.start();}
    /** UI only marks choices; no Surface/native/socket/write/join operations. */
    public void decide(long requestId,boolean allow){admissions.choose(requestId,allow);}
    public void disconnect(int slot,long sessionId){if(!admissions.owns(slot,sessionId))return;Peer peer=peers.get(sessionId);if(peer!=null)peer.cancel("接收端断开了本路");}
    public void surfaceLost(int slot){long id=admissions.owner(slot);if(id>=0)disconnect(slot,id);}
    public int activeCount(){return closed.get()?0:admissions.activeCount();}
    public int pendingCount(){return closed.get()?0:admissions.pendingCount();}
    /** Immediate logical cancel and I/O close; native teardown stays on each peer's worker. */
    public void stop(){
        if(!closed.compareAndSet(false,true))return;admissions.close();deadlines.shutdownNow();
        synchronized(audioGate){audioSelection.clear();publishAudioSelection();}audioPlayer.close();
        synchronized(registration){quiet(server);for(Peer peer:peers.values())peer.cancel("接收端已关闭");}
        Thread t=acceptThread;if(t!=null)t.interrupt();
        try{listener.onStopped();}catch(RuntimeException ignored){}
    }
    private void acceptLoop(){
        try{
            ServerSocket listening=new ServerSocket();synchronized(registration){if(closed.get()){quiet(listening);return;}server=listening;}
            listening.setReuseAddress(true);listening.bind(new InetSocketAddress(ApprovalProtocol.PORT));
            if(closed.get())return;listener.onListening(ApprovalProtocol.PORT);status("四路接收已监听 "+ApprovalProtocol.PORT+"；需逐个确认，仅可信局域网；设备名称可伪造，请核对IP");
            deadlines.scheduleAtFixedRate(()->{long now=System.nanoTime();for(Peer peer:peers.values())if(peer.readingRequest&&now-peer.acceptedAt>5_000_000_000L)peer.cancel("读取连接请求超时（5秒）");},250,250,TimeUnit.MILLISECONDS);
            while(!closed.get()){
                acceptedPermits.acquire();Socket socket=null;boolean transferred=false;
                try{
                    if(closed.get())break;socket=listening.accept();socket.setSoTimeout(5000);socket.setTcpNoDelay(true);
                    long id=ids.incrementAndGet();Peer peer=new Peer(id,socket);
                    synchronized(registration){
                        if(closed.get())break;
                        if(!admissions.register(id))throw new IOException("接收准入容量状态异常");
                        peers.put(id,peer);transferred=true;
                    }
                    peer.thread.start();
                }finally{if(!transferred){quiet(socket);acceptedPermits.release();}}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(IOException|RuntimeException e){if(!closed.get())status("四路监听失败："+describe(e));}
        finally{stop();}
    }
    private void status(String text){if(!closed.get())try{listener.onStatus(text);}catch(RuntimeException ignored){}}
    private boolean current(Peer peer){return !closed.get()&&!peer.cancelled.get()&&peer.slot>=0&&admissions.owns(peer.slot,peer.id);}
    private void slotStatus(Peer peer,String text){if(current(peer))try{listener.onSlotChanged(peer.slot,peer.id,peer.name,peer.address,text);}catch(RuntimeException ignored){}}
    private final class Peer implements Runnable {
        final long id,acceptedAt=System.nanoTime();final Socket socket;final String address;final Thread thread;
        final AtomicBoolean cancelled=new AtomicBoolean(),requestFinished=new AtomicBoolean();final AtomicReference<String> endReason=new AtomicReference<>();
        volatile boolean readingRequest=true;volatile int slot=-1;volatile String name="";volatile ApprovedPeerSession engine;
        DecodeLeaseBudget.Lease nativeLease;volatile AudioLaneReceiver audioLane;
        Peer(long id,Socket socket){this.id=id;this.socket=socket;address=socket.getInetAddress().getHostAddress();thread=new Thread(this,"multi-peer-"+id);thread.setDaemon(true);}
        void cancel(String reason){endReason.compareAndSet(null,reason);cancelled.set(true);admissions.cancel(id,reason);leaveAudio(id);AudioLaneReceiver lane=audioLane;if(lane!=null)lane.close();quiet(socket);ApprovedPeerSession active=engine;if(active!=null)active.cancel(reason);thread.interrupt();}
        void finishRequest(String reason){if(requestFinished.compareAndSet(false,true))try{listener.onRequestFinished(id,reason);}catch(RuntimeException ignored){}}
        public void run(){
            boolean approved=false;
            try{
                if(cancelled.get()||closed.get())return;
                DataInputStream input=new DataInputStream(new BufferedInputStream(socket.getInputStream(),64*1024));
                DataOutputStream output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),1024));
                ApprovalProtocol.Request request=ApprovalProtocol.readRequest(input);name=request.name;readingRequest=false;
                if(cancelled.get()||closed.get())return;
                ApprovalProtocol.writeWaiting(output);
                AdmissionSlots.Snapshot state=admissions.beginPending(id,System.nanoTime());
                if(state.phase==AdmissionSlots.Phase.PENDING){
                    listener.onApprovalRequested(id,name,address);
                    state=ApprovalWaiter.await(input,socket,admissions,id,()->cancelled.get()||closed.get());
                    if(state.phase==AdmissionSlots.Phase.PENDING&&Boolean.TRUE.equals(state.choice)){
                        boolean[] valid=new boolean[4];for(int i=0;i<4;i++){Surface s=surfaces.getSurface(i);valid[i]=s!=null&&s.isValid();}
                        state=admissions.admit(id,valid,System.nanoTime());
                    }
                }
                if(state.phase!=AdmissionSlots.Phase.ACTIVE){String reason=state.reason.isEmpty()?"接收端未批准连接":state.reason;endReason.compareAndSet(null,reason);ApprovalProtocol.writeRejected(output,reason);finishRequest(reason);return;}
                slot=state.slot;
                if(cancelled.get()||closed.get())return;
                Surface surface=surfaces.getSurface(slot);
                if(surface==null||!surface.isValid()){String reason="显示区域已失效，请重新连接";endReason.compareAndSet(null,reason);ApprovalProtocol.writeRejected(output,reason);finishRequest(reason);return;}
                nativeLease=DECODE_BUDGET.tryAcquire();
                if(nativeLease==null){String reason="四路解码资源仍在使用或释放中，请稍后重试；持续卡住可重启应用";endReason.compareAndSet(null,reason);ApprovalProtocol.writeRejected(output,reason);finishRequest(reason);return;}
                String ephemeralPin=String.format(java.util.Locale.US,"%06d",new SecureRandom().nextInt(1000000));
                String audioInfo="此路未启用内部声音";
                if(request.audio){
                    try{audioLane=new AudioLaneReceiver(socket.getInetAddress(),new AudioLaneReceiver.Listener(){
                        public void onFrame(PcmAudioFrame frame,long localCapturedAtNs){if(current(Peer.this))audioPlayer.offer(id,frame,localCapturedAtNs);}
                        public void onStatus(String text){audioStatus(Peer.this,text);}
                    });audioInfo="等待发送端内部声音";}
                    catch(IOException|RuntimeException error){audioInfo="音频监听不可用，继续视频："+describe(error);}
                    if(cancelled.get()||closed.get())return;
                    AudioLaneReceiver lane=audioLane;
                    ApprovalProtocol.writeAcceptedAudio(output,new ApprovalProtocol.AudioGrant(ephemeralPin,lane==null?0:lane.port(),lane==null?0:lane.tokenHigh,lane==null?0:lane.tokenLow,audioInfo));
                }else ApprovalProtocol.writeAccepted(output,ephemeralPin);
                approved=true;finishRequest("已批准");
                slotStatus(this,"已批准，正在协商本路独立视频通道");audioStatus(this,audioInfo);joinAudio(this);
                ApprovedPeerSession session=new ApprovedPeerSession(socket,input,output,surface,ephemeralPin,request.udp,new ApprovedPeerSession.Listener(){
                    public void onStatus(String text){slotStatus(Peer.this,text);}
                    public void onClockReady(Wire.ClockSync clock){AudioLaneReceiver lane=audioLane;if(lane!=null&&!cancelled.get()&&!closed.get())lane.start(clock);}
                    public void onVideoSize(int width,int height){if(current(Peer.this))try{listener.onVideoSize(slot,id,width,height);}catch(RuntimeException ignored){}}
                    public void onFirstFrame(){if(current(Peer.this))try{listener.onFirstFrame(slot,id);}catch(RuntimeException ignored){}}
                    public void onMetrics(StreamMetrics metrics){if(current(Peer.this))try{listener.onMetrics(slot,id,metrics);}catch(RuntimeException ignored){}}
                });
                engine=session;
                if(cancelled.get()||closed.get()){session.cancel("接收端已关闭或本路已取消");return;}
                session.run();endReason.compareAndSet(null,session.endReason());
            }catch(IOException|RuntimeException e){ApprovedPeerSession session=engine;endReason.compareAndSet(null,session==null?describe(e):session.endReason());}
            finally{
                readingRequest=false;AudioLaneReceiver lane=audioLane;if(lane!=null)lane.close();leaveAudio(id);ApprovedPeerSession session=engine;if(session!=null)session.close();quiet(socket);
                String reason=endReason.get();if(reason==null)reason=closed.get()?"接收端已关闭":approved?"发送端已断开":"请求已结束";
                finishRequest(reason);
                // Retain identity on end callback; the UI also guards late old callbacks after slot reuse.
                if(session!=null&&!session.isDecoderTerminated()){
                    if(!closed.get()&&admissions.owns(slot,id))try{listener.onSlotChanged(slot,id,name,address,"正在释放解码资源，暂不可复用；其它路不受影响");}catch(RuntimeException ignored){}
                    while(!session.isDecoderTerminated())try{Thread.sleep(250);}catch(InterruptedException ignored){/* UI cancel must not return native lease prematurely. */}
                }
                if(nativeLease!=null)nativeLease.releaseIfTerminated(session==null||session.isDecoderTerminated());
                int oldSlot=slot;admissions.release(id);peers.remove(id);acceptedPermits.release();
                if(oldSlot>=0)try{listener.onSlotChanged(oldSlot,id,"",address,reason);}catch(RuntimeException ignored){}
            }
        }
    }
    private static void quiet(Closeable c){if(c!=null)try{c.close();}catch(IOException ignored){}}
    private static String describe(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
