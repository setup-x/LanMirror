package com.lanmirror.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Four persistent Surface children, adaptive geometry and session-bound live HUDs. */
final class MultiReceivePanel implements AutoCloseable {
    private final Activity activity;
    private final LinearLayout root;
    private final AdaptiveGrid grid;
    private final TextView summary,discoveryStatus;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final AtomicReferenceArray<Surface> surfaces=new AtomicReferenceArray<>(4);
    private final Tile[] tiles=new Tile[4];
    private final String installationId,deviceName,networkAddresses;
    private MultiReceiver receiver;
    private LanDiscovery discovery;
    private boolean wanted,closed,compact;
    private int epoch;private long selectedAudioId,audioRevision;
    private final LinkedHashMap<Long,Prompt> prompts=new LinkedHashMap<>();
    private AlertDialog approvalDialog;
    private long shownRequest;
    private String overall="准备自适应显示窗口…";
    private final Runnable hudTick=new Runnable(){public void run(){
        if(closed||!wanted)return;long now=System.nanoTime();for(Tile tile:tiles)tile.refreshHud(now);ui.postDelayed(this,500);
    }};
    MultiReceivePanel(Activity activity,Runnable goBack,String installationId,String addresses){
        this.activity=activity;this.installationId=installationId;networkAddresses=addresses;deviceName=ApprovalProtocol.displayName(Build.MANUFACTURER+" "+Build.MODEL);
        root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(19,35,55));root.setPadding(dp(12),dp(8),dp(12),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(12)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(12)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());return insets;});
        TextView heading=label("智能分屏 · 1.8.0（点此查看状态）",18);heading.setOnClickListener(v->showAllReports());heading.setOnLongClickListener(v->{gridRestore();return true;});root.addView(heading);
        TextView address=label(deviceName+"\n"+addresses+" · TCP "+ApprovalProtocol.PORT,12);address.setMaxLines(3);root.addView(address);
        summary=label(overall,13);summary.setMaxLines(2);summary.setOnClickListener(v->showAllReports());root.addView(summary);
        discoveryStatus=label("设备发现待启动；新连接需要你逐个允许",12);discoveryStatus.setMaxLines(2);root.addView(discoveryStatus);
        grid=new AdaptiveGrid();root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<4;i++){tiles[i]=new Tile(i);grid.addView(tiles[i].box,new ViewGroup.LayoutParams(-1,-1));}
        TextView hint=label("每格按钮选声；新加入一路默认播放，点已选按钮静音，放大不切声音。长按画面放大/恢复；延时与FPS均为软件阶段指标。",12);hint.setMaxLines(3);root.addView(hint);
        Button stop=new Button(activity);stop.setAllCaps(false);stop.setText("结束所有接收 / 返回");stop.setOnClickListener(v->goBack.run());root.addView(stop,new LinearLayout.LayoutParams(-1,dp(48)));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b>t)setCompact(b-t<dp(450));});
        setCompact(activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE);root.requestApplyInsets();
    }
    View view(){return root;}
    void configurationChanged(android.content.res.Configuration configuration){if(configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)setCompact(true);grid.requestLayout();}
    boolean collapseFocused(){if(grid.focusedSlot()<0)return false;gridRestore();return true;}
    private void gridRestore(){grid.focus.clear();grid.requestLayout();refreshSummary();}
    private void setCompact(boolean enabled){
        if(compact==enabled)return;compact=enabled;
        for(int i=1;i<root.getChildCount();i++){View child=root.getChildAt(i);if(child!=grid)child.setVisibility(enabled?View.GONE:View.VISIBLE);}
    }
    private int dp(int n){return (int)(activity.getResources().getDisplayMetrics().density*n+0.5f);}
    private TextView label(String text,int size){TextView t=new TextView(activity);t.setText(text);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(dp(3),dp(2),dp(3),dp(2));return t;}
    void resume(){if(closed)return;wanted=true;ui.removeCallbacks(hudTick);ui.post(hudTick);maybeStart();}
    void pause(){
        wanted=false;epoch++;selectedAudioId=audioRevision=0;ui.removeCallbacks(hudTick);MultiReceiver old=receiver;receiver=null;if(old!=null)old.stop();
        LanDiscovery nsd=discovery;discovery=null;if(nsd!=null)nsd.close();
        prompts.clear();shownRequest=0;AlertDialog dialog=approvalDialog;approvalDialog=null;if(dialog!=null)dialog.dismiss();
        for(Tile tile:tiles){tile.highestId=0;tile.activeId=0;tile.report="接收已暂停";tile.firstFrame=false;tile.quarantined=false;tile.title.setText((tile.index+1)+" · 空闲");tile.coverMessage="已暂停";tile.updateCover();tile.refreshHud(System.nanoTime());}
        grid.focus.clear();grid.requestLayout();overall="接收已暂停，返回前台后恢复";summary.setText(overall);discoveryStatus.setText("已停止设备发布");
    }
    @Override public void close(){if(closed)return;closed=true;pause();ui.removeCallbacksAndMessages(null);}
    private boolean live(int generation){return !closed&&wanted&&epoch==generation&&receiver!=null;}
    private void maybeStart(){
        if(closed||!wanted||receiver!=null)return;
        for(int i=0;i<4;i++){Surface s=surfaces.get(i);if(s==null||!s.isValid())return;}
        final int generation=++epoch;
        receiver=new MultiReceiver(activity,surfaces::get,new MultiReceiver.Listener(){
            public void onListening(int port){ui.post(()->{if(live(generation)&&discovery!=null)discovery.startAdvertising(deviceName,installationId,port,4);});}
            public void onStopped(){ui.post(()->{if(live(generation)&&discovery!=null)discovery.stopAdvertising();});}
            public void onApprovalRequested(long id,String name,String address){ui.post(()->{if(!live(generation))return;prompts.put(id,new Prompt(id,name,address));showNextPrompt();refreshSummary();});}
            public void onRequestFinished(long id,String reason){ui.post(()->{if(!live(generation))return;finishPrompt(id);overall=reason;refreshSummary();});}
            public void onSlotChanged(int slot,long id,String name,String address,String report){ui.post(()->{
                if(!live(generation)||slot<0||slot>=4)return;Tile t=tiles[slot];if(id<t.highestId)return;
                boolean newSession=id>t.highestId;t.highestId=id;long oldActive=t.activeId;
                if(name==null||name.isEmpty()){
                    t.activeId=0;t.report=report;t.firstFrame=false;t.title.setText((slot+1)+" · 空闲");t.coverMessage="等待连接";overall="第"+(slot+1)+"路："+report;
                }else{
                    if(t.activeId==0&&!newSession)return;
                    if(newSession){t.activeId=id;t.audioStatus="等待声音状态";t.playbackStatus="等待选声/数据";t.playbackError=false;t.videoW=t.videoH=0;t.metrics=null;t.firstFrame=false;t.quarantined=false;t.coverMessage="正在连接…";}
                    t.name=name;t.address=address;t.report=report;t.title.setText((slot+1)+" · "+name);
                }
                t.quarantined=report!=null&&report.contains("正在释放解码资源");
                if(t.quarantined){t.coverMessage="资源释放中，暂不可重用";overall="第"+(slot+1)+"路："+report;}
                t.updateCover();t.refreshHud(System.nanoTime());t.refreshAudio();if(oldActive!=t.activeId)grid.requestLayout();refreshSummary();
            });}
            public void onVideoSize(int slot,long id,int w,int h){ui.post(()->{if(!live(generation)||slot<0||slot>=4)return;Tile t=tiles[slot];if(t.activeId!=id)return;if(t.videoW!=w||t.videoH!=h){t.videoW=w;t.videoH=h;grid.requestLayout();}});}
            public void onFirstFrame(int slot,long id){ui.post(()->{if(live(generation)&&slot>=0&&slot<4&&tiles[slot].activeId==id){tiles[slot].firstFrame=true;tiles[slot].updateCover();}});}
            public void onAudioSelection(long id,long revision){ui.post(()->{if(!live(generation)||revision<audioRevision)return;audioRevision=revision;selectedAudioId=id;for(Tile tile:tiles)tile.refreshAudio();refreshSummary();});}
            public void onAudioStatus(int slot,long id,String text){ui.post(()->{if(live(generation)&&slot>=0&&slot<4&&tiles[slot].activeId==id){tiles[slot].audioStatus=text;tiles[slot].refreshAudio();}});}
            public void onAudioPlaybackStatus(int slot,long id,String text,boolean error){ui.post(()->{if(live(generation)&&slot>=0&&slot<4&&tiles[slot].activeId==id){tiles[slot].playbackStatus=text;tiles[slot].playbackError=error;tiles[slot].refreshAudio();}});}
            public void onMetrics(int slot,long id,StreamMetrics metrics){ui.post(()->{
                if(!live(generation)||slot<0||slot>=4||metrics==null)return;Tile tile=tiles[slot];if(tile.activeId!=id)return;
                if(tile.metrics!=null&&metrics.sampledAtNs<tile.metrics.sampledAtNs)return;
                tile.metrics=metrics;tile.refreshHud(System.nanoTime());
            });}
            public void onStatus(String message){ui.post(()->{if(live(generation)){overall=message;refreshSummary();}});}
        });
        receiver.start();
        discovery=new LanDiscovery(activity,new LanDiscovery.Listener(){public void onDevicesChanged(List<LanDiscovery.Device> devices){}public void onStatus(String message){if(live(generation))discoveryStatus.setText(message);}});
        for(Tile tile:tiles){tile.coverMessage="等待连接";tile.updateCover();}refreshSummary();
    }
    private void refreshSummary(){MultiReceiver r=receiver;int focus=grid.focusedSlot();summary.setText("已占用 "+(r==null?0:r.activeCount())+"/4 · 待确认 "+(r==null?0:r.pendingCount())+(focus>=0?" · 放大第"+(focus+1)+"路":" · 自动分屏")+"\n"+(selectedAudioId==0?"声音：全部静音 · ":"声音：已选一路 · ")+overall);}
    private void showNextPrompt(){
        if(closed||!wanted||approvalDialog!=null||prompts.isEmpty())return;
        Prompt p=prompts.values().iterator().next();shownRequest=p.id;
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("允许这台设备投屏？")
            .setMessage(p.name+"\n来源："+p.address+"\n\n允许后加入自动分屏及所请求的内部声音；可逐窗口选声或静音。设备名可被冒用，音视频未加密。")
            .setPositiveButton("允许",(d,w)->decide(p.id,true)).setNegativeButton("拒绝",(d,w)->decide(p.id,false)).create();
        approvalDialog=dialog;dialog.setOnCancelListener(d->decide(p.id,false));
        dialog.setOnDismissListener(d->{if(shownRequest==p.id){shownRequest=0;approvalDialog=null;showNextPrompt();}});dialog.show();
    }
    private void decide(long id,boolean allowed){MultiReceiver r=receiver;if(r!=null)r.decide(id,allowed);finishPrompt(id);}
    private void finishPrompt(long id){prompts.remove(id);if(shownRequest==id){shownRequest=0;AlertDialog d=approvalDialog;approvalDialog=null;if(d!=null)d.dismiss();}showNextPrompt();}
    private void showAllReports(){
        StringBuilder b=new StringBuilder(deviceName+"\n"+networkAddresses+"\n接收端口 "+ApprovalProtocol.PORT+"\n"+discoveryStatus.getText()+"\n"+overall+"\n布局："+grid.layoutKind+"；放大其它路仍继续接收和解码。");
        long now=System.nanoTime();for(Tile t:tiles)b.append("\n\n[物理槽 ").append(t.index+1).append("] ").append(t.name).append(" ").append(t.address).append('\n').append(StreamHud.details(t.metrics,now)).append('\n').append(t.report).append("\n声音传输：").append(t.audioStatus).append("\n声音播放：").append(t.playbackStatus);
        showReport("逐路指标 / 自动分屏",b.toString(),null);
    }
    private void showReport(String title,String message,Runnable disconnect){
        AlertDialog.Builder b=new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton("复制报告",(d,w)->activity.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("LanMirror 1.8.0",message))).setNeutralButton("关闭",null);
        if(disconnect!=null)b.setNegativeButton("断开这一路",(d,w)->disconnect.run());
        else if(grid.focusedSlot()>=0)b.setNegativeButton("恢复自动分屏",(d,w)->gridRestore());b.show();
    }
    /** Keep every Tile attached/visible. Park nonparticipants at 1x1 in the outer gap. */
    private final class AdaptiveGrid extends ViewGroup {
        final AdaptiveFocusState focus=new AdaptiveFocusState();
        AdaptiveLayoutPolicy.Plan plan;String layoutKind="empty";
        AdaptiveGrid(){super(activity);setClipChildren(true);setClipToPadding(true);setBackgroundColor(Color.rgb(19,35,55));}
        long[] activeIds(){long[] ids=new long[4];for(int i=0;i<4;i++)if(tiles[i]!=null)ids[i]=tiles[i].activeId;return ids;}
        int focusedSlot(){return focus.resolve(activeIds());}
        void toggle(Tile tile){if(tile.activeId<=0)return;focus.toggle(tile.index,tile.activeId);requestLayout();refreshSummary();}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int w=resolveSize(dp(320),widthSpec),h=resolveSize(dp(360),heightSpec);setMeasuredDimension(w,h);
            ArrayList<Integer> order=new ArrayList<>();int[] widths=new int[4],heights=new int[4];
            for(int i=0;i<4;i++){Tile t=tiles[i];if(t==null)continue;if(t.activeId>0)order.add(i);widths[i]=t.videoW;heights[i]=t.videoH;}
            Collections.sort(order,(a,b)->Long.compare(tiles[a].activeId,tiles[b].activeId));int[] active=new int[order.size()];for(int i=0;i<active.length;i++)active[i]=order.get(i);
            int header=dp(40);for(Tile tile:tiles)if(tile!=null)header=Math.max(header,tile.titleHeight()+tile.textHeight(tile.hud));
            plan=AdaptiveLayoutPolicy.arrange(w,h,dp(4),header,active,widths,heights,focusedSlot());layoutKind=plan.kind;
            for(int i=0;i<4;i++){
                Tile tile=tiles[i];if(tile==null)continue;tile.setParked(!plan.shown[i]);AdaptiveLayoutPolicy.Rect r=plan.bounds[i];
                tile.box.measure(MeasureSpec.makeMeasureSpec(Math.max(1,r.width()),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(1,r.height()),MeasureSpec.EXACTLY));
            }
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            if(plan==null)return;for(int i=0;i<4;i++){AdaptiveLayoutPolicy.Rect rect=plan.bounds[i];tiles[i].box.layout(rect.left,rect.top,rect.left+Math.max(1,rect.width()),rect.top+Math.max(1,rect.height()));}
        }
    }
    private final class Tile {
        final int index;final TileBox box;final TextView title,hud,cover;final Button sound;final FrameLayout image;final SurfaceView video;
        long highestId,activeId;int videoW,videoH;boolean parked,firstFrame,quarantined;
        String name="",address="",report="等待连接",coverMessage="等待连接",lastHud="",audioStatus="未连接声音",playbackStatus="未选择";boolean playbackError;StreamMetrics metrics;
        Tile(int index){
            this.index=index;box=new TileBox();box.setBackgroundColor(Color.rgb(35,52,70));
            title=label((index+1)+" · 空闲",12);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(title);
            hud=label("延时 --ms · --fps",11);hud.setSingleLine(true);hud.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(hud);
            sound=new Button(activity);sound.setTextSize(10);sound.setAllCaps(false);sound.setMinWidth(0);sound.setMinimumWidth(0);sound.setMinHeight(0);sound.setMinimumHeight(0);sound.setPadding(dp(2),0,dp(2),0);box.addView(sound);
            sound.setOnClickListener(v->{MultiReceiver r=receiver;if(r!=null&&activeId>0)r.selectAudio(index,activeId);});refreshAudio();
            View.OnClickListener reportClick=v->{long id=activeId;String details=address+"\n\n"+StreamHud.details(metrics,System.nanoTime())+"\n\n"+report+"\n声音传输："+audioStatus+"\n声音播放："+playbackStatus+"\n当前声音按钮表示选择，不保证音源应用允许捕获。";
                showReport("第"+(index+1)+"路 · "+name,details,id==0?null:()->{MultiReceiver r=receiver;if(r!=null&&activeId==id){coverMessage="正在断开…";quarantined=true;updateCover();r.disconnect(index,id);}});
            };
            title.setOnClickListener(reportClick);hud.setOnClickListener(reportClick);sound.setOnLongClickListener(v->{reportClick.onClick(v);return true;});
            image=new FrameLayout(activity);image.setBackgroundColor(Color.BLACK);box.addView(image);
            video=new SurfaceView(activity);image.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
            cover=label("等待连接",16);cover.setGravity(Gravity.CENTER);cover.setBackgroundColor(Color.rgb(10,18,28));image.addView(cover,new FrameLayout.LayoutParams(-1,-1));
            View.OnLongClickListener focusAction=v->{grid.toggle(this);return true;};title.setOnLongClickListener(focusAction);hud.setOnLongClickListener(focusAction);video.setOnLongClickListener(focusAction);cover.setOnLongClickListener(focusAction);
            video.getHolder().addCallback(new SurfaceHolder.Callback(){
                public void surfaceCreated(SurfaceHolder holder){surfaces.set(index,holder.getSurface());maybeStart();}
                public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){surfaces.set(index,holder.getSurface());}
                public void surfaceDestroyed(SurfaceHolder holder){surfaces.set(index,null);MultiReceiver r=receiver;if(r!=null)r.surfaceLost(index);firstFrame=false;updateCover();}
            });
        }
        void setParked(boolean value){if(parked==value)return;parked=value;box.setImportantForAccessibility(value?View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS:View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);updateCover();}
        void updateCover(){cover.setText(parked?"":coverMessage);cover.setVisibility(parked||activeId==0||!firstFrame||quarantined?View.VISIBLE:View.GONE);}
        void refreshHud(long now){
            String value=activeId>0?StreamHud.label(metrics,now):"延时 --ms · --fps";
            if(!value.equals(lastHud)){lastHud=value;hud.setText(value);}
            hud.setTextColor(metrics!=null&&activeId>0&&metrics.isStale(now)?Color.rgb(255,181,90):Color.rgb(155,229,214));
        }
        void refreshAudio(){
            boolean selected=activeId>0&&activeId==selectedAudioId;
            sound.setText(selected?(playbackError?"声音 !":"声音 ✓"):"选声音");sound.setEnabled(activeId>0);
            sound.setTextColor(Color.WHITE);sound.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected?(playbackError?Color.rgb(160,92,24):Color.rgb(30,119,88)):Color.rgb(65,81,99)));
            sound.setContentDescription((selected?"已选择本路，再点静音。":"选择本路声音。")+audioStatus+"。"+playbackStatus);
        }
        int titleHeight(){return Math.max(textHeight(title),dp(32));}
        int textHeight(TextView text){return Math.max(dp(20),text.getLineHeight()+text.getCompoundPaddingTop()+text.getCompoundPaddingBottom());}
        void fit(int w,int h){
            int outW=w,outH=h;if(videoW>0&&videoH>0){double factor=Math.min((double)w/videoW,(double)h/videoH);outW=Math.max(1,(int)(videoW*factor));outH=Math.max(1,(int)(videoH*factor));}
            if(video.getLayoutParams().width!=outW||video.getLayoutParams().height!=outH)video.setLayoutParams(new FrameLayout.LayoutParams(outW,outH,Gravity.CENTER));
        }
        /** Even a parked tile measures its Surface to at least 1x1; text may measure zero, never Surface. */
        final class TileBox extends ViewGroup {
            int titleH,hudH,audioW;
            TileBox(){super(activity);setClipChildren(true);}
            @Override protected void onMeasure(int wSpec,int hSpec){
                int w=Math.max(1,MeasureSpec.getSize(wSpec)),h=Math.max(1,MeasureSpec.getSize(hSpec));setMeasuredDimension(w,h);
                titleH=parked?0:Math.min(titleHeight(),Math.max(0,h-1));hudH=parked||activeId==0?0:Math.min(textHeight(hud),Math.max(0,h-1-titleH));int imageH=Math.max(1,h-titleH-hudH);
                audioW=parked||activeId==0?0:Math.min(dp(54),w/2);
                title.measure(MeasureSpec.makeMeasureSpec(w-audioW,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(titleH,MeasureSpec.EXACTLY));
                sound.measure(MeasureSpec.makeMeasureSpec(audioW,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(titleH,MeasureSpec.EXACTLY));
                hud.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(hudH,MeasureSpec.EXACTLY));fit(w,imageH);
                image.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(imageH,MeasureSpec.EXACTLY));
            }
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){int w=getMeasuredWidth();title.layout(0,0,w-audioW,titleH);sound.layout(w-audioW,0,w,titleH);hud.layout(0,titleH,w,titleH+hudH);image.layout(0,titleH+hudH,w,getMeasuredHeight());}
        }
    }
    private static final class Prompt{final long id;final String name,address;Prompt(long id,String name,String address){this.id=id;this.name=name;this.address=address;}}
}
