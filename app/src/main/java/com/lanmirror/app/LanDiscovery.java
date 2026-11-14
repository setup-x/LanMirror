package com.lanmirror.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android NSD/mDNS adapter. Service names and installation ids are NOT authenticated identities. */
public final class LanDiscovery implements AutoCloseable {
    public static final String SERVICE_TYPE="_lanmirror._tcp.";
    public interface Listener {
        void onDevicesChanged(List<Device> immutableSnapshot);
        void onStatus(String status);
    }
    public static final class Device {
        public final String id,name,host;
        public final int port,slots;
        public final boolean requiresApproval=true;
        public Device(String id,String name,String host,int port,int slots){
            DiscoveryRegistry.Entry checked=new DiscoveryRegistry.Entry(id,name,host,port,slots);
            this.id=checked.id;this.name=checked.name;this.host=checked.host;this.port=checked.port;this.slots=checked.slots;
        }
    }
    private final Context context;
    private final NsdManager manager;
    private volatile Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Object listenerGate=new Object();
    private final DiscoveryRegistry registry=new DiscoveryRegistry();
    private final ArrayDeque<Resolution> resolutions=new ArrayDeque<>();
    private boolean discoveryWanted;
    private Discovery discovery;
    private Advertisement advertisement;
    private AdSpec desiredAdvertisement;
    private Resolution resolving;
    private WifiManager.MulticastLock multicastLock;
    private boolean multicastAttempted;
    private long lastResolveErrorMs;
    private final Runnable resolvePump=this::pumpResolution;

    public LanDiscovery(Context context,Listener listener){
        if(context==null||listener==null)throw new IllegalArgumentException("Context/listener required");
        Context app=context.getApplicationContext();this.context=app==null?context:app;this.listener=listener;
        manager=(NsdManager)this.context.getSystemService(Context.NSD_SERVICE);
    }
    public void startDiscovery(){runMain(()->{
        if(closed.get())return;
        discoveryWanted=true;
        if(discovery==null)beginDiscovery();
    });}
    public void stopDiscovery(){runMain(()->{if(!closed.get())stopDiscoveryOwned();});}
    public void startAdvertising(String deviceName,String installationId,int port,int maxSlots){runMain(()->{
        if(closed.get())return;
        final AdSpec spec;
        try{spec=new AdSpec(deviceName,installationId,port,maxSlots);}catch(IllegalArgumentException e){
            desiredAdvertisement=null;if(advertisement!=null)advertisement.requestStop();
            status("局域网发布参数无效；仍可用人工IP连接。");updateMulticast();return;
        }
        desiredAdvertisement=spec;updateMulticast();
        if(advertisement==null)beginAdvertising();
        else if(!advertisement.spec.same(spec))advertisement.requestStop();
    });}
    public void stopAdvertising(){runMain(()->{
        if(closed.get())return;desiredAdvertisement=null;
        if(advertisement!=null)advertisement.requestStop();updateMulticast();
    });}
    /** Logical close is immediate from every thread. Cleanup callbacks continue, UI callbacks do not. */
    @Override public void close(){
        synchronized(listenerGate){if(!closed.compareAndSet(false,true))return;listener=null;}
        runMain(()->{
            stopDiscoveryOwned();desiredAdvertisement=null;
            if(advertisement!=null)advertisement.requestStop();
            cancelResolution();main.removeCallbacks(resolvePump);releaseMulticast();
        });
    }
    private void runMain(Runnable runnable){if(Looper.myLooper()==main.getLooper())runnable.run();else main.post(runnable);}
    /** NSD callbacks may arrive on binder/framework threads, including after cancellation. */
    private void callback(Runnable runnable){main.post(runnable);}
    private void status(String message){synchronized(listenerGate){Listener target=listener;if(!closed.get()&&target!=null)try{target.onStatus(message);}catch(RuntimeException ignored){}}}
    private void publish(){
        if(closed.get())return;
        List<Device> devices=new ArrayList<>();for(DiscoveryRegistry.Entry e:registry.snapshot())devices.add(new Device(e.id,e.name,e.host,e.port,e.slots));
        synchronized(listenerGate){Listener target=listener;if(!closed.get()&&target!=null)try{target.onDevicesChanged(Collections.unmodifiableList(devices));}catch(RuntimeException ignored){}}
    }
    private void beginDiscovery(){
        if(closed.get()||!discoveryWanted||discovery!=null)return;
        if(manager==null){discoveryWanted=false;status("系统不支持NSD发现，请使用人工IP连接。");return;}
        registry.begin();resolutions.clear();publish();updateMulticast();
        Discovery next=new Discovery();discovery=next;
        try{manager.discoverServices(SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD,next);}
        catch(RuntimeException e){next.startFailed(-1);}
    }
    private void stopDiscoveryOwned(){
        discoveryWanted=false;registry.stop();resolutions.clear();publish();
        cancelResolution();if(discovery!=null)discovery.requestStop();updateMulticast();
    }
    private void discoveryFinished(Discovery session){
        main.removeCallbacks(session.retryStop);session.done=true;
        if(discovery!=session)return;discovery=null;
        if(discoveryWanted&&!closed.get())beginDiscovery();else updateMulticast();
    }
    private final class Discovery implements NsdManager.DiscoveryListener {
        boolean started,stopRequested,stopIssued,done;int stopRetries;
        final Runnable retryStop=()->{stopIssued=false;requestStop();};
        boolean accepts(){return discovery==this&&!stopRequested&&!done&&discoveryWanted&&!closed.get();}
        void requestStop(){
            stopRequested=true;if(done||!started||stopIssued)return;stopIssued=true;
            try{manager.stopServiceDiscovery(this);}
            catch(IllegalArgumentException e){discoveryFinished(this);}
            catch(RuntimeException e){stopFailed(-1);}
        }
        void startFailed(int code){
            if(done)return;
            if(discovery==this&&!stopRequested){discoveryWanted=false;registry.stop();resolutions.clear();publish();status("局域网发现启动失败("+code+")；请允许局域网权限。热点/AP隔离可能不支持mDNS，可用人工IP。");}
            discoveryFinished(this);
        }
        void stopFailed(int code){
            if(done)return;
            if(code==NsdManager.FAILURE_OPERATION_NOT_RUNNING){discoveryFinished(this);return;}
            stopIssued=false;status("系统暂未停止设备发现("+code+")，正在重试清理。");
            main.removeCallbacks(retryStop);main.postDelayed(retryStop,Math.min(10000L,250L*(1L<<Math.min(6,stopRetries++))));
        }
        public void onDiscoveryStarted(String type){callback(()->{
            if(done)return;started=true;
            if(!accepts())requestStop();else status("正在发现同局域网接收端；只显示支持接收端确认的设备。热点/AP隔离可能阻止mDNS，可人工输入IP。");
        });}
        public void onStartDiscoveryFailed(String type,int code){callback(()->startFailed(code));}
        public void onStopDiscoveryFailed(String type,int code){callback(()->stopFailed(code));}
        public void onDiscoveryStopped(String type){callback(()->{
            if(discovery==this&&!stopRequested){discoveryWanted=false;registry.stop();resolutions.clear();publish();status("系统已停止局域网发现，可重试或使用人工IP。");}
            discoveryFinished(this);
        });}
        public void onServiceFound(NsdServiceInfo info){callback(()->{
            if(!accepts())return;String key=serviceKey(info);if(key==null)return;
            DiscoveryRegistry.Ticket ticket=registry.found(key);if(ticket==null)return;
            resolutions.addLast(new Resolution(info,ticket));pumpResolution();
        });}
        public void onServiceLost(NsdServiceInfo info){callback(()->{
            if(!accepts())return;String key=serviceKey(info);if(key==null)return;
            if(registry.lost(key))publish();
            for(Iterator<Resolution> i=resolutions.iterator();i.hasNext();)if(i.next().ticket.serviceKey.equals(key))i.remove();
            if(resolving!=null&&resolving.ticket.serviceKey.equals(key))cancelResolution();
        });}
    }
    private static String serviceKey(NsdServiceInfo info){
        if(info==null)return null;String name=info.getServiceName(),type=info.getServiceType();
        if(name==null||name.isEmpty()||name.length()>256||type==null||type.length()>64)return null;
        String normalized=type.toLowerCase(Locale.ROOT);if(!normalized.endsWith("."))normalized+=".";
        if(!SERVICE_TYPE.equals(normalized))return null;return name+"\u0000"+SERVICE_TYPE;
    }
    private void pumpResolution(){
        if(resolving!=null||closed.get()||!discoveryWanted||manager==null)return;
        Resolution next;
        do{next=resolutions.pollFirst();}while(next!=null&&!registry.isCurrent(next.ticket));
        if(next==null)return;resolving=next;
        try{manager.resolveService(next.info,next);main.postDelayed(next.warning,8000);}
        catch(RuntimeException e){finishResolution(next,null,-1);}
    }
    private void cancelResolution(){
        Resolution current=resolving;if(current==null)return;main.removeCallbacks(current.warning);
        // Before API34 legacy one-shot resolve has no public cancellation API. Its late result is ignored.
        // Keep its slot until terminal callback: do not overlap old/new platform resolve operations.
        if(Build.VERSION.SDK_INT>=34&&!current.cancellationRequested){
            current.cancellationRequested=true;
            try{manager.stopServiceResolution(current);}catch(IllegalArgumentException e){finishResolution(current,null,0);}
            catch(RuntimeException e){current.cancellationRequested=false;}
        }
    }
    private final class Resolution implements NsdManager.ResolveListener {
        final NsdServiceInfo info;final DiscoveryRegistry.Ticket ticket;boolean cancellationRequested;
        final Runnable warning;
        Resolution(NsdServiceInfo info,DiscoveryRegistry.Ticket ticket){
            this.info=info;this.ticket=ticket;
            warning=()->{if(resolving==this&&registry.isCurrent(this.ticket))status("设备地址解析较慢；系统完成后继续下一台，或使用人工IP。名称不是身份认证，请核对IP。");};
        }
        public void onResolveFailed(NsdServiceInfo info,int code){callback(()->finishResolution(this,null,code));}
        public void onServiceResolved(NsdServiceInfo info){callback(()->finishResolution(this,info,0));}
        public void onResolutionStopped(NsdServiceInfo info){callback(()->finishResolution(this,null,0));}
        public void onStopResolutionFailed(NsdServiceInfo info,int code){callback(()->{
            if(resolving!=this)return;
            if(code==NsdManager.FAILURE_OPERATION_NOT_RUNNING)finishResolution(this,null,0);
            else cancellationRequested=false;
        });}
    }
    private void finishResolution(Resolution resolution,NsdServiceInfo info,int code){
        if(resolving!=resolution)return;main.removeCallbacks(resolution.warning);resolving=null;
        if(registry.isCurrent(resolution.ticket)&&!closed.get()&&discoveryWanted){
            DiscoveryRegistry.Entry entry=parse(info);
            if(entry!=null){if(registry.resolved(resolution.ticket,entry))publish();}
            else {
                registry.failed(resolution.ticket);
                long now=android.os.SystemClock.elapsedRealtime();
                if(info==null&&code!=0&&now-lastResolveErrorMs>=1500){lastResolveErrorMs=now;status("个别设备地址解析失败("+code+")，继续发现其它设备；也可人工输入IP。");}
            }
        }
        main.removeCallbacks(resolvePump);if(!closed.get())main.post(resolvePump);
    }
    private static DiscoveryRegistry.Entry parse(NsdServiceInfo info){
        if(info==null)return null;
        try{
            Map<String,byte[]> attributes=info.getAttributes();
            if(attributes==null||!"approval1".equals(txt(attributes.get("proto"),16)))return null;
            String id=installationId(txt(attributes.get("id"),64));
            String slotText=txt(attributes.get("slots"),4);if(slotText==null||!slotText.matches("[1-4]"))return null;
            String advertised=txt(attributes.get("deviceName"),128);
            if(advertised==null)advertised=info.getServiceName();String name=clean(advertised,96);
            InetAddress address=info.getHost();if(address==null||address.isAnyLocalAddress())return null;
            String host=address.getHostAddress();int port=info.getPort();
            return new DiscoveryRegistry.Entry(id,name,host,port,Integer.parseInt(slotText));
        }catch(RuntimeException e){return null;}
    }
    private static String txt(byte[] bytes,int max){
        if(bytes==null||bytes.length==0||bytes.length>max)return null;
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){return null;}
    }
    private static String installationId(String value){
        if(value==null||value.length()!=36||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("Installation UUID required");
        return UUID.fromString(value).toString();
    }
    /** UTF-8 length limit without splitting surrogate pairs or broadcasting control characters. */
    private static String clean(String value,int maxBytes){
        if(value==null)value="Android";StringBuilder out=new StringBuilder();int bytes=0,limit=Math.min(value.length(),4096);
        for(int i=0;i<limit;){int cp=value.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isISOControl(cp)||cp==0x202a||cp==0x202b||cp==0x202d||cp==0x202e||cp==0x202c||cp>=0x2066&&cp<=0x2069||cp>=0xd800&&cp<=0xdfff)continue;
            int n=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;if(bytes+n>maxBytes)break;out.appendCodePoint(cp);bytes+=n;
        }
        String result=out.toString().trim();return result.isEmpty()?"Android":result;
    }
    private static final class AdSpec {
        final String name,id;final int port,slots;
        AdSpec(String name,String id,int port,int slots){
            if(port<1||port>65535||slots<1||slots>4)throw new IllegalArgumentException("Port/slot limit");
            this.name=clean(name,96);this.id=installationId(id);this.port=port;this.slots=slots;
        }
        boolean same(AdSpec other){return other!=null&&name.equals(other.name)&&id.equals(other.id)&&port==other.port&&slots==other.slots;}
        NsdServiceInfo info(){
            NsdServiceInfo info=new NsdServiceInfo();info.setServiceType(SERVICE_TYPE);
            info.setServiceName("LanMirror-"+clean(name,45)+"-"+id.substring(0,8));info.setPort(port);
            info.setAttribute("proto","approval1");info.setAttribute("slots",Integer.toString(slots));
            info.setAttribute("id",id);info.setAttribute("deviceName",name);return info;
        }
    }
    private void beginAdvertising(){
        if(closed.get()||desiredAdvertisement==null||advertisement!=null)return;
        if(manager==null){desiredAdvertisement=null;status("系统不支持NSD发布，请对端人工输入本机IP。");updateMulticast();return;}
        updateMulticast();Advertisement next=new Advertisement(desiredAdvertisement);advertisement=next;
        try{manager.registerService(next.spec.info(),NsdManager.PROTOCOL_DNS_SD,next);}
        catch(RuntimeException e){next.registrationFailed(-1);}
    }
    private void advertisementFinished(Advertisement session){
        main.removeCallbacks(session.retryStop);session.done=true;
        if(advertisement!=session)return;advertisement=null;
        if(desiredAdvertisement!=null&&!closed.get())beginAdvertising();else updateMulticast();
    }
    private final class Advertisement implements NsdManager.RegistrationListener {
        final AdSpec spec;boolean registered,stopRequested,stopIssued,done;int stopRetries;
        final Runnable retryStop=()->{stopIssued=false;requestStop();};
        Advertisement(AdSpec spec){this.spec=spec;}
        void requestStop(){
            stopRequested=true;if(done||!registered||stopIssued)return;stopIssued=true;
            try{manager.unregisterService(this);}
            catch(IllegalArgumentException e){advertisementFinished(this);}
            catch(RuntimeException e){unregistrationFailed(-1);}
        }
        void registrationFailed(int code){
            if(done)return;
            if(advertisement==this&&spec.same(desiredAdvertisement)&&!stopRequested)desiredAdvertisement=null;
            status("局域网发布失败("+code+")；请检查局域网权限，仍可通过本机IP连接。");advertisementFinished(this);
        }
        void unregistrationFailed(int code){
            if(done)return;
            if(code==NsdManager.FAILURE_OPERATION_NOT_RUNNING){advertisementFinished(this);return;}
            stopIssued=false;status("系统暂未撤销设备广播("+code+")，正在重试清理。");
            main.removeCallbacks(retryStop);main.postDelayed(retryStop,Math.min(10000L,250L*(1L<<Math.min(6,stopRetries++))));
        }
        public void onServiceRegistered(NsdServiceInfo info){callback(()->{
            if(done)return;registered=true;
            if(closed.get()||advertisement!=this||stopRequested||!spec.same(desiredAdvertisement))requestStop();
            else status("本机接收端已发布："+spec.name+"，需本机确认接入。设备名称可被冒用，请核对连接IP。");
        });}
        public void onRegistrationFailed(NsdServiceInfo info,int code){callback(()->registrationFailed(code));}
        public void onServiceUnregistered(NsdServiceInfo info){callback(()->advertisementFinished(this));}
        public void onUnregistrationFailed(NsdServiceInfo info,int code){callback(()->unregistrationFailed(code));}
    }
    private void updateMulticast(){
        boolean needed=!closed.get()&&(discoveryWanted||discovery!=null||desiredAdvertisement!=null||advertisement!=null);
        if(!needed){releaseMulticast();return;}if(multicastLock!=null||multicastAttempted)return;multicastAttempted=true;
        try{
            WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);if(wifi==null)return;
            WifiManager.MulticastLock lock=wifi.createMulticastLock("LanMirror-NSD");lock.setReferenceCounted(false);lock.acquire();multicastLock=lock;
        }catch(RuntimeException e){status("无法获取Wi-Fi组播锁；系统NSD仍会尝试发现，热点/AP隔离或权限可能限制发现，可人工输入IP。");}
    }
    private void releaseMulticast(){
        WifiManager.MulticastLock lock=multicastLock;multicastLock=null;multicastAttempted=false;
        if(lock!=null)try{if(lock.isHeld())lock.release();}catch(RuntimeException ignored){}
    }
}
