package com.lanmirror.app;

import android.media.*;
import android.os.*;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real asynchronous MediaCodec; ALL native codec operations run on one HandlerThread. */
public final class AsyncSurfaceDecoder implements AutoCloseable {
    public interface Listener {
        void onConfigured(long generation);
        void onVideoSize(int width,int height);
        default void onFirstFrameRendered() {}
        default void onMetrics(StreamMetrics metrics) {}
        void onStatus(String status);
        void onNeedKeyFrame();
        void onError(Exception error);
    }
    private static final String MIME="video/avc";
    private static final long NORMAL_BUDGET_NS=250_000_000L, STARTUP_BUDGET_NS=750_000_000L, DEGRADED_BUDGET_NS=500_000_000L;
    private final Surface surface; private final Wire.ClockSync clock; private final Listener listener;
    private final HandlerThread thread=new HandlerThread("smooth-codec-owner");
    private final Handler owner;
    private final SmoothDecodeQueue queue=new SmoothDecodeQueue(12,8*1024*1024);
    private final SmoothFrameMetadata metadata=new SmoothFrameMetadata(256);
    private final SmoothCodecClock codecClock=new SmoothCodecClock();
    private final AtomicBoolean closed=new AtomicBoolean(), pumpPosted=new AtomicBoolean();
    private final ArrayDeque<Integer> available=new ArrayDeque<>();
    private final HashSet<Integer> availableSet=new HashSet<>();
    private final ArrayDeque<byte[]> manualConfig=new ArrayDeque<>();
    private MediaCodec codec;
    private SmoothWire.Config configuration;
    private long budgetNs=NORMAL_BUDGET_NS, openedAtNs, lastRenderedNs, lastCallbackNs;
    private boolean firstInput=true, firstOutput=true;
    private long inputCallbacks, outputCallbacks, rendered, outputDrops, startupShown, starvationShown, setupNs, rebuilds;
    private long intervalRendered, queueSamples, queueTotal, queueMax, decodeSamples, decodeTotal, decodeMax, stageSamples, stageTotal, stageMax, releaseSamples, releaseTotal, releaseMax;
    private long reportAt=System.nanoTime(); private String codecDescription="waiting CONFIG";
    private final Runnable pumpTask=()->{
        pumpPosted.set(false);
        if(closed.get())return;
        try{pump();}catch(Exception e){fail(e);}
    };
    private final Runnable statisticsTask=new Runnable(){public void run(){
        if(closed.get())return;
        try{report();if(queue.waitingForKey())listener.onNeedKeyFrame();}catch(RuntimeException e){fail(e);return;}
        owner.postDelayed(this,1000);
    }};
    public AsyncSurfaceDecoder(Surface surface,Wire.ClockSync clock,Listener listener){
        if(surface==null || clock==null || listener==null)throw new IllegalArgumentException("Missing decoder arguments");
        this.surface=surface;this.clock=clock;this.listener=listener;
        thread.start();owner=new Handler(thread.getLooper());owner.postDelayed(statisticsTask,1000);
    }
    public void configure(SmoothWire.Config config){
        if(config==null)throw new IllegalArgumentException("Missing CONFIG");
        if(closed.get())return;
        if(!owner.post(()->{if(closed.get())return;try{configureOwned(config);}catch(Exception e){fail(e);}}))close();
    }
    /** Always takes ownership of one reference, even after close or generation mismatch. */
    public void offer(StreamFrame frame){
        if(frame==null)return;
        queue.offer(frame);
        if(!closed.get())schedulePump();
    }
    public void discontinuity(){queue.discontinuity();schedulePump();}
    private void schedulePump(){
        if(closed.get())return;
        if(pumpPosted.compareAndSet(false,true) && !owner.post(pumpTask)){pumpPosted.set(false);close();}
    }
    private void configureOwned(SmoothWire.Config config)throws IOException{
        if(config.width<=0 || config.height<=0 || config.width>Wire.MAX_DIMENSION || config.height>Wire.MAX_DIMENSION
            || (long)config.width*config.height>Wire.MAX_PIXELS)throw new IOException("Invalid decoder dimensions");
        int configBytes=0;if(config.csd==null || config.csd.length==0 || config.csd.length>8)throw new IOException("Missing/bounded AVC CSD");
        for(byte[] csd:config.csd){if(csd==null || csd.length==0 || (configBytes+=csd.length)>512*1024)throw new IOException("Invalid AVC CSD");}
        if(configuration!=null && configuration.generation==config.generation && configuration.width==config.width
                && configuration.height==config.height && Arrays.deepEquals(configuration.csd,config.csd)){
            listener.onConfigured(config.generation);return;
        }
        if(codec!=null)rebuilds++;
        releaseCodecOwned();configuration=config;queue.beginGeneration(config.generation);
        firstInput=true;firstOutput=true;budgetNs=NORMAL_BUDGET_NS;openedAtNs=System.nanoTime();lastRenderedNs=openedAtNs;
        CodecBootstrapPolicy.Configuration csd=CodecBootstrapPolicy.select(true,Arrays.asList(config.csd));
        long started=System.nanoTime();
        IOException firstFailure=null;
        if(csd.usable())try{openCodec(config,csd);}catch(IOException failure){firstFailure=failure;}
        if(codec==null){openCodec(config,null);for(byte[] item:config.csd)manualConfig.addLast(item);if(firstFailure!=null)codecDescription+="; CSD configure fallback";}
        setupNs=System.nanoTime()-started;
        listener.onVideoSize(config.width,config.height);
        listener.onConfigured(config.generation);
        listener.onStatus("Async AVC configured: "+codecDescription+"; smooth budget 250ms; first IDR allowance 750ms (real age retained)");
        schedulePump();
    }
    private void openCodec(SmoothWire.Config config,CodecBootstrapPolicy.Configuration csd)throws IOException{
        List<MediaCodecInfo> candidates=new ArrayList<>();
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(info.isEncoder())continue;
            try{MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(MIME);
                if(caps.getVideoCapabilities()!=null && caps.getVideoCapabilities().isSizeSupported(config.width,config.height))candidates.add(info);
            }catch(IllegalArgumentException ignored){}
        }
        Collections.sort(candidates,(a,b)->Boolean.compare(hardware(b),hardware(a)));
        String last="No AVC decoder";
        for(MediaCodecInfo candidate:candidates){
            MediaCodecInfo.CodecCapabilities caps=candidate.getCapabilitiesForType(MIME);
            for(int attempt=0;attempt<2;attempt++){
                MediaCodec created=null;
                try{
                    MediaFormat format=MediaFormat.createVideoFormat(MIME,config.width,config.height);
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,Wire.MAX_PACKET);
                    boolean lowLatency=attempt==0 && Build.VERSION.SDK_INT>=30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
                    if(attempt==0){format.setInteger(MediaFormat.KEY_PRIORITY,0);format.setInteger(MediaFormat.KEY_OPERATING_RATE,60);}
                    if(lowLatency)format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
                    if(csd!=null){format.setByteBuffer("csd-0",ByteBuffer.wrap(csd.sps));format.setByteBuffer("csd-1",ByteBuffer.wrap(csd.pps));}
                    created=MediaCodec.createByCodecName(candidate.getName());codec=created;
                    created.setCallback(callback,owner);created.configure(format,surface,null,0);created.start();
                    codecDescription=candidate.getName()+" / "+(hardware(candidate)?"hardware":"software fallback")
                        +" / callback owner / lowLatency="+(lowLatency?"requested":"off")+" / CSD="+(csd!=null?"configure":"input fallback");
                    return;
                }catch(IOException|RuntimeException e){last=candidate.getName()+": "+e.getMessage();codec=null;available.clear();availableSet.clear();
                    if(created!=null)try{created.release();}catch(RuntimeException ignored){}
                }
            }
        }
        throw new IOException("Async decoder configure failed: "+last);
    }
    private static boolean hardware(MediaCodecInfo info){
        if(Build.VERSION.SDK_INT>=29)return info.isHardwareAccelerated();
        String n=info.getName().toLowerCase(Locale.US);return !n.contains("google")&&!n.contains("android")&&!n.contains("ffmpeg");
    }
    private final MediaCodec.Callback callback=new MediaCodec.Callback(){
        @Override public void onInputBufferAvailable(MediaCodec sender,int index){
            if(closed.get() || sender!=codec)return;
            lastCallbackNs=System.nanoTime();inputCallbacks++;
            if(availableSet.add(index))available.addLast(index);
            schedulePump();
        }
        @Override public void onOutputBufferAvailable(MediaCodec sender,int index,MediaCodec.BufferInfo info){
            if(closed.get() || sender!=codec)return;
            lastCallbackNs=System.nanoTime();outputCallbacks++;
            try{renderOutput(sender,index,info);}catch(Exception e){fail(e);}
        }
        @Override public void onOutputFormatChanged(MediaCodec sender,MediaFormat format){
            if(closed.get() || sender!=codec)return;lastCallbackNs=System.nanoTime();
            // Display crop differs from configured coded dimensions; never mutate configuration.
            try{
                int w=format.containsKey("crop-right")&&format.containsKey("crop-left")?format.getInteger("crop-right")-format.getInteger("crop-left")+1:format.getInteger(MediaFormat.KEY_WIDTH);
                int h=format.containsKey("crop-bottom")&&format.containsKey("crop-top")?format.getInteger("crop-bottom")-format.getInteger("crop-top")+1:format.getInteger(MediaFormat.KEY_HEIGHT);
                if(w>0&&h>0)listener.onVideoSize(w,h);
            }catch(RuntimeException ignored){}
        }
        @Override public void onError(MediaCodec sender,MediaCodec.CodecException error){if(!closed.get()&&sender==codec)fail(error);}
    };
    private void pump()throws IOException{
        if(codec==null)return;
        if(queue.consumeSyncRequest())listener.onNeedKeyFrame();
        int handled=0;
        while(!closed.get() && !available.isEmpty() && handled++<16){
            if(!manualConfig.isEmpty()){
                int index=takeIndex();byte[] csd=manualConfig.removeFirst();ByteBuffer target=codec.getInputBuffer(index);
                if(target==null||target.capacity()<csd.length)throw new IOException("AVC CSD exceeds codec input buffer");
                target.clear();target.put(csd);codec.queueInputBuffer(index,0,csd.length,0,MediaCodec.BUFFER_FLAG_CODEC_CONFIG);continue;
            }
            StreamFrame frame=queue.poll();if(frame==null)break;
            try{
                if(configuration==null||frame.generation!=configuration.generation){queue.recordRejectedInput();continue;}
                long now=System.nanoTime(),age=clock.ageNs(frame.encodedAtNs,now);
                boolean startup=firstInput && frame.keyFrame();
                if(!startup && age>budgetNs && age<=DEGRADED_BUDGET_NS && now-lastRenderedNs>1_000_000_000L)budgetNs=DEGRADED_BUDGET_NS;
                long allowed=startup?STARTUP_BUDGET_NS:budgetNs;
                if(age>allowed){queue.recordRejectedInput();listener.onNeedKeyFrame();continue;}
                int index=takeIndex();ByteBuffer target=codec.getInputBuffer(index);
                if(target==null || target.capacity()<frame.length)throw new IOException("Video exceeds decoder input capacity");
                target.clear();target.put(frame.data,frame.offset,frame.length);
                long fedAt=System.nanoTime();long token=codecClock.next(frame.ptsUs);
                metadata.add(token,new SmoothFrameMetadata.Entry(frame,fedAt,startup));
                codec.queueInputBuffer(index,0,frame.length,token,frame.flags & ~MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                firstInput=false;
                long queued=Math.max(0,fedAt-frame.receivedAtNs);queueSamples++;queueTotal+=queued;queueMax=Math.max(queueMax,queued);
            }finally{frame.release();}
        }
        if(queue.consumeSyncRequest())listener.onNeedKeyFrame();
        // If the bounded pass exhausted but work/input slots remain, only one continuation is posted.
        if(!available.isEmpty() && (!manualConfig.isEmpty() || queue.depth()>0))schedulePump();
    }
    private int takeIndex(){int index=available.removeFirst();availableSet.remove(index);return index;}
    private void renderOutput(MediaCodec sender,int index,MediaCodec.BufferInfo info)throws IOException{
        boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
        boolean configOutput=(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0;
        SmoothFrameMetadata.Entry entry=configOutput?null:metadata.remove(info.presentationTimeUs);
        boolean video=entry!=null || (!configOutput && info.size>0); // Surface output may legitimately report size zero.
        long now=System.nanoTime(), age=entry==null?Long.MAX_VALUE:clock.ageNs(entry.encodedAtNs,now);
        boolean startup=entry!=null && firstOutput && entry.startup;
        boolean rescue=entry!=null && !startup && now-lastRenderedNs>1_000_000_000L;
        if(rescue)budgetNs=DEGRADED_BUDGET_NS;
        boolean render=entry!=null && (age<=budgetNs || startup || rescue);
        if(entry!=null){long delta=Math.max(0,now-entry.fedAtNs);decodeSamples++;decodeTotal+=delta;decodeMax=Math.max(decodeMax,delta);}
        long releaseAt=System.nanoTime();sender.releaseOutputBuffer(index,render);long submitted=System.nanoTime();
        long releaseNs=submitted-releaseAt;releaseSamples++;releaseTotal+=releaseNs;releaseMax=Math.max(releaseMax,releaseNs);
        if(render){
            if(startup && age>NORMAL_BUDGET_NS)startupShown++;
            if(rescue && age>budgetNs)starvationShown++;
            if(firstOutput)listener.onFirstFrameRendered();
            firstOutput=false;lastRenderedNs=submitted;rendered++;intervalRendered++;
            long trueAge=clock.ageNs(entry.encodedAtNs,submitted);stageSamples++;stageTotal+=trueAge;stageMax=Math.max(stageMax,trueAge);
        }else if(video)outputDrops++;
        if(eos)throw new IOException("Decoder output ended");
    }
    private void report(){
        long now=System.nanoTime();double seconds=Math.max(.001,(now-reportAt)/1e9);
        // Optional HUD sampling must not stop the media pipeline.
        try{listener.onMetrics(StreamMetrics.fromCounters(now,now-reportAt,intervalRendered,rendered,queue.droppedFrames()+outputDrops,stageSamples,stageTotal,stageMax));}catch(RuntimeException ignored){}
        listener.onStatus(String.format(Locale.US,
            "流畅模式 / 异步AVC / 直接Surface\n提交 %.1f fps / 累计%d · 队列%d/12 (%dKB) · 元数据%d/256\n回调 input/output %d/%d · 最近回调 %.1fms\n接收排队 avg/max %.1f/%.1fms · 输入到输出 %.1f/%.1fms\nrelease avg/max %.1f/%.1fms · 出帧→提交 %.1f/%.1fms (非端到端)\n应用输入丢弃%d / 输出跳显%d / 恢复%d / 队满%d (非网络丢包率)\n预算%dms %s · 启动旧IDR显示%d / 无显示保护%d · setup %.1fms / 重建%d\n%s",
            intervalRendered/seconds,rendered,queue.depth(),queue.queuedBytes()/1024,metadata.size(),inputCallbacks,outputCallbacks,
            lastCallbackNs==0?-1:(now-lastCallbackNs)/1e6,
            avg(queueTotal,queueSamples),queueMax/1e6,avg(decodeTotal,decodeSamples),decodeMax/1e6,
            avg(releaseTotal,releaseSamples),releaseMax/1e6,avg(stageTotal,stageSamples),stageMax/1e6,
            queue.droppedFrames(),outputDrops,queue.recoveryCount(),queue.overflowEvents(),budgetNs/1000000,
            budgetNs>NORMAL_BUDGET_NS?"慢设备降级，非250ms目标":"150–250ms流畅目标，非已达标",
            startupShown,starvationShown,setupNs/1e6,rebuilds,codecDescription));
        reportAt=now;intervalRendered=0;queueSamples=queueTotal=queueMax=decodeSamples=decodeTotal=decodeMax=stageSamples=stageTotal=stageMax=releaseSamples=releaseTotal=releaseMax=0;
    }
    private static double avg(long sum,long n){return n==0?0:sum/(double)n/1e6;}
    private void fail(Exception e){
        if(!closed.compareAndSet(false,true))return;
        queue.close();owner.removeCallbacks(pumpTask);owner.removeCallbacks(statisticsTask);
        try{listener.onError(e);}catch(RuntimeException ignored){}
        releaseCodecOwned();thread.quitSafely();
    }
    private void releaseCodecOwned(){
        MediaCodec old=codec;codec=null;available.clear();availableSet.clear();manualConfig.clear();metadata.clear();codecClock.reset();
        if(old!=null){try{old.stop();}catch(RuntimeException ignored){}try{old.release();}catch(RuntimeException ignored){}}
    }
    /** Logical close immediate; native release remains on owner, UI waits at most 250ms. */
    /** True only after owner native cleanup and HandlerThread exit, not merely logical close. */
    public boolean isTerminated(){return !thread.isAlive();}
    @Override public void close(){
        if(closed.compareAndSet(false,true)){
            queue.close();owner.removeCallbacks(pumpTask);owner.removeCallbacks(statisticsTask);
            if(Looper.myLooper()==thread.getLooper()){releaseCodecOwned();thread.quitSafely();}
            else owner.post(()->{releaseCodecOwned();thread.quitSafely();});
        }
        if(Thread.currentThread()!=thread)try{thread.join(250);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
