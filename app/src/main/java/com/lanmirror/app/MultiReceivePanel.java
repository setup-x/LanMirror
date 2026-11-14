package com.lanmirror.app;

import com.lanmirror.core.api.*;
import com.lanmirror.discovery.api.LanDiscovery;
import com.lanmirror.receiver.api.MultiReceiver;
import com.lanmirror.audio.api.InternalAudio;

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
    private static final int C_BG = Color.rgb(243, 246, 251);
    private static final int C_SURFACE = Color.WHITE;
    private static final int C_INK = Color.rgb(16, 27, 45);
    private static final int C_BODY = Color.rgb(51, 65, 92);
    private static final int C_MUTED = Color.rgb(108, 124, 150);
    private static final int C_LINE = Color.rgb(228, 234, 244);
    private static final int C_ACCENT = Color.rgb(47, 98, 232);
    private static final int C_ACCENT_SOFT = Color.rgb(235, 241, 255);
    private static final int C_GRID = Color.rgb(232, 237, 245);
    private final Activity activity;
    private final FrameLayout root;
    private final AdaptiveGrid grid;
    private final ScrollView information;
    private final Button continueWatching;
    private final TextView summary,discoveryStatus;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final AtomicReferenceArray<Surface> surfaces=new AtomicReferenceArray<>(4);
    private final Tile[] tiles=new Tile[4];
    private final String installationId,deviceName,networkAddresses;
    private MultiReceiver receiver;
    private LanDiscovery discovery;
    private boolean wanted, closed, fullscreen, immersive, manualInformation, informationVisible = true;
    private int insetLeft, insetTop, insetRight, insetBottom;
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
        root = new FrameLayout(activity);
        root.setBackgroundColor(C_GRID);
        applySystemBars(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetTop = insets.getSystemWindowInsetTop();
            insetRight = insets.getSystemWindowInsetRight();
            insetBottom = insets.getSystemWindowInsetBottom();
            applyPadding();
            return insets;
        });
        grid = new AdaptiveGrid();
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        for(int i=0;i<4;i++){tiles[i]=new Tile(i);grid.addView(tiles[i].box,new ViewGroup.LayoutParams(-1,-1));}
        information = new ScrollView(activity);
        information.setFillViewport(true);
        information.setBackgroundColor(C_BG);
        information.setClickable(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(28));
        information.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(information, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout brandRow = new LinearLayout(activity);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        View brandDot = new View(activity);
        brandDot.setBackground(circle(C_ACCENT));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotParams.rightMargin = dp(8);
        brandRow.addView(brandDot, dotParams);
        TextView brand = label("LANMIRROR  /  多路接收  ·  1.8.0", 11);
        brand.setTextColor(C_ACCENT);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setLetterSpacing(0.12f);
        brand.setPadding(0, 0, 0, 0);
        brandRow.addView(brand);
        content.addView(brandRow);

        TextView heading = label("智能分屏", 28);
        heading.setTextColor(C_INK);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(0, dp(10), 0, dp(2));
        heading.setOnLongClickListener(v -> {
            gridRestore();
            return true;
        });
        content.addView(heading);
        content.addView(body("最多 4 路 · 同一局域网自动发现，连接需逐台允许。"));

        LinearLayout infoCard = card(content);
        infoCard.addView(caption("本机接收信息"));
        TextView device = label(deviceName, 18);
        device.setTextColor(C_INK);
        device.setTypeface(null, android.graphics.Typeface.BOLD);
        infoCard.addView(device);
        divider(infoCard);
        infoRow(infoCard, "接收地址", networkAddresses);
        infoRow(infoCard, "接收端口", String.valueOf(ApprovalProtocol.PORT));
        infoRow(infoCard, "传输模式", "多路确认");

        LinearLayout statusCard = sectionCard(content, "当前状态");
        summary = label(overall, 14);
        summary.setTextColor(C_BODY);
        summary.setLineSpacing(0, 1.18f);
        summary.setOnClickListener(v -> showAllReports());
        statusCard.addView(summary);
        discoveryStatus = label("设备发现待启动；新连接需要你逐个允许", 12);
        discoveryStatus.setTextColor(C_MUTED);
        discoveryStatus.setLineSpacing(0, 1.15f);
        discoveryStatus.setPadding(0, dp(8), 0, dp(4));
        statusCard.addView(discoveryStatus);
        button(statusCard, "查看逐路状态", false, this::showAllReports);

        continueWatching = button(content, "继续观看", true, () -> {
            if (hasWatchable()) {
                manualInformation = false;
                updatePresentation();
            }
        });
        continueWatching.setVisibility(View.GONE);
        outlineButton(content, "结束所有接收 / 返回", () -> {
            applySystemBars(false);
            goBack.run();
        });
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(16);
        content.addView(body("每格按钮选声；新加入一路默认播放，点已选按钮静音，放大不切声音。点击画面全窗口放大，返回恢复分屏；网速为实际接收的视频载荷，不含音频及协议头。"), hintParams);
        updatePresentation();
        root.requestApplyInsets();
    }
    View view(){return root;}

    void configurationChanged(android.content.res.Configuration configuration) {
        grid.requestLayout();
        root.requestApplyInsets();
    }
    boolean collapseFocused(){if(grid.focusedSlot()<0)return false;gridRestore();return true;}
    private void gridRestore(){grid.focus.clear();grid.requestLayout();refreshSummary();}

    private boolean hasWatchable() {
        for (Tile tile : tiles)
            if (tile != null && tile.activeId > 0 && tile.firstFrame && !tile.quarantined)
                return true;
        return false;
    }

    private void showInformation() {
        manualInformation = true;
        updatePresentation();
    }

    private void updatePresentation() {
        boolean watchable = hasWatchable();
        if (!watchable) manualInformation = false;
        boolean show = !watchable || manualInformation;
        continueWatching.setVisibility(watchable ? View.VISIBLE : View.GONE);
        if (informationVisible != show) {
            informationVisible = show;
            information.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        grid.setImportantForAccessibility(show ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        grid.setDescendantFocusability(show ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (show) grid.clearFocus();
        syncFocus();
        syncImmersive();
    }

    private void applyPadding() {
        root.setPadding(immersive ? 0 : insetLeft, immersive ? 0 : insetTop, immersive ? 0 : insetRight, immersive ? 0 : insetBottom);
    }

    private void syncFocus() {
        boolean enabled = !informationVisible && grid.focusedSlot() >= 0;
        if (fullscreen == enabled) return;
        fullscreen = enabled;
        for (Tile tile : tiles) if (tile != null) tile.updateCover();
        grid.requestLayout();
    }

    private void syncImmersive() {
        boolean enabled = !informationVisible;
        if (immersive == enabled) return;
        immersive = enabled;
        applyPadding();
        applySystemBars(enabled);
        root.requestApplyInsets();
        grid.requestLayout();
    }

    private void applySystemBars(boolean enabled) {
        Window window = activity.getWindow();
        window.setStatusBarColor(enabled ? Color.BLACK : C_BG);
        window.setNavigationBarColor(enabled ? Color.BLACK : C_BG);
        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        final int immersiveFlags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        window.getDecorView().setSystemUiVisibility(enabled ? immersiveFlags : lightFlags);
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!enabled);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(enabled ? 0 : appearance, appearance);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (enabled) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        }
        if (enabled) {
            final View decor = window.getDecorView();
            // Some devices drop hide() before the window gains focus; retry once after layout.
            decor.post(() -> {
                if (!immersive || closed) return;
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController controller = activity.getWindow().getInsetsController();
                    if (controller != null) controller.hide(WindowInsets.Type.systemBars());
                } else decor.setSystemUiVisibility(immersiveFlags);
            });
        }
    }
    private int dp(int n){return (int)(activity.getResources().getDisplayMetrics().density*n+0.5f);}

    private TextView label(String text, int size) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(C_INK);
        t.setTextSize(size);
        t.setPadding(dp(3), dp(2), dp(3), dp(2));
        return t;
    }

    private TextView body(String text) {
        TextView t = label(text, 13);
        t.setTextColor(C_MUTED);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView caption(String text) {
        TextView t = label(text, 11);
        t.setTextColor(C_MUTED);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setLetterSpacing(0.10f);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private void divider(LinearLayout parent) {
        View line = new View(activity);
        line.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        p.topMargin = dp(10);
        p.bottomMargin = dp(2);
        parent.addView(line, p);
    }

    private void infoRow(LinearLayout parent, String name, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView nameView = label(name, 13);
        nameView.setTextColor(C_MUTED);
        nameView.setPadding(0, 0, 0, 0);
        row.addView(nameView, new LinearLayout.LayoutParams(dp(84), -2));
        TextView valueView = label(value, 13);
        valueView.setTextColor(C_INK);
        valueView.setGravity(Gravity.END);
        valueView.setPadding(0, 0, 0, 0);
        valueView.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL));
        row.addView(valueView, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(outline(C_SURFACE, C_LINE, 20));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        parent.addView(card, p);
        return card;
    }

    private LinearLayout sectionCard(LinearLayout parent, String title) {
        LinearLayout card = card(parent);
        card.addView(caption(title));
        return card;
    }

    private Button button(LinearLayout parent, String text, boolean primary, Runnable action) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setBackgroundTintList(null);
        b.setBackground(ripple(rounded(primary ? C_ACCENT : C_ACCENT_SOFT, 16), 16));
        b.setTextColor(primary ? Color.WHITE : C_ACCENT);
        b.setTextSize(15);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setMinHeight(dp(50));
        b.setMinimumHeight(dp(50));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setStateListAnimator(null);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
        p.topMargin = dp(8);
        parent.addView(b, p);
        return b;
    }

    private Button outlineButton(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setBackgroundTintList(null);
        b.setBackground(ripple(outline(C_SURFACE, C_LINE, 16), 16));
        b.setTextColor(C_BODY);
        b.setTextSize(15);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setMinHeight(dp(50));
        b.setMinimumHeight(dp(50));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
        p.topMargin = dp(8);
        parent.addView(b, p);
        return b;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, int radius) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private android.graphics.drawable.GradientDrawable outline(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable shape = rounded(fill, radius);
        shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    private android.graphics.drawable.RippleDrawable ripple(android.graphics.drawable.Drawable content, int radius) {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14101B2D), content, rounded(Color.WHITE, radius));
    }

    private android.graphics.drawable.GradientDrawable circle(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }
    void resume(){if(closed)return;wanted=true;ui.removeCallbacks(hudTick);ui.post(hudTick);maybeStart();}
    void pause(){
        wanted=false;epoch++;selectedAudioId=audioRevision=0;ui.removeCallbacks(hudTick);MultiReceiver old=receiver;receiver=null;if(old!=null)old.stop();
        LanDiscovery nsd=discovery;discovery=null;if(nsd!=null)nsd.close();
        prompts.clear();shownRequest=0;AlertDialog dialog=approvalDialog;approvalDialog=null;if(dialog!=null)dialog.dismiss();
        for(Tile tile:tiles){tile.highestId=0;tile.activeId=0;tile.report="接收已暂停";tile.firstFrame=false;tile.quarantined=false;tile.title.setText((tile.index+1)+" · 空闲");tile.coverMessage="已暂停";tile.updateCover();tile.refreshHud(System.nanoTime());}
        grid.focus.clear();
        manualInformation = false;
        updatePresentation();
        applySystemBars(false);
        root.requestApplyInsets();
        grid.requestLayout();
        overall = "接收已暂停，返回前台后恢复";
        summary.setText(overall);
        discoveryStatus.setText("已停止设备发布");
    }
    @Override public void close(){if(closed)return;closed=true;pause();ui.removeCallbacksAndMessages(null);}
    private boolean live(int generation){return !closed&&wanted&&epoch==generation&&receiver!=null;}
    private void maybeStart(){
        if(closed||!wanted||receiver!=null)return;
        for(int i=0;i<4;i++){Surface s=surfaces.get(i);if(s==null||!s.isValid())return;}
        final int generation=++epoch;
        receiver = new MultiReceiver(surfaces::get, new MultiReceiver.Listener() {
            public void onListening(int port){ui.post(()->{if(live(generation)&&discovery!=null)discovery.startAdvertising(deviceName,installationId,port,4);});}
            public void onStopped(){ui.post(()->{if(live(generation)&&discovery!=null)discovery.stopAdvertising();});}
            public void onApprovalRequested(long id,String name,String address){ui.post(()->{if(!live(generation))return;prompts.put(id,new Prompt(id,name,address));showNextPrompt();refreshSummary();});}
            public void onRequestFinished(long id,String reason){ui.post(()->{if(!live(generation))return;finishPrompt(id);overall=reason;refreshSummary();});}
            public void onSlotChanged(int slot,long id,String name,String address,String report){ui.post(()->{
                if(!live(generation)||slot<0||slot>=4)return;Tile t=tiles[slot];if(id<t.highestId)return;
                boolean newSession=id>t.highestId;t.highestId=id;long oldActive=t.activeId;
                if(name==null||name.isEmpty()){
                    t.activeId = 0;
                    t.metrics = null;
                    t.report = report;
                    t.firstFrame = false;
                    t.title.setText((slot + 1) + " · 空闲");
                    t.coverMessage = "等待连接";
                    overall = "第" + (slot + 1) + "路：" + report;
                }else{
                    if(t.activeId==0&&!newSession)return;
                    if(newSession){t.activeId=id;t.audioStatus="等待声音状态";t.playbackStatus="等待选声/数据";t.playbackError=false;t.videoW=t.videoH=0;t.metrics=null;t.firstFrame=false;t.quarantined=false;t.coverMessage="正在连接…";}
                    t.name=name;t.address=address;t.report=report;t.title.setText((slot+1)+" · "+name);
                }
                t.quarantined = (t.activeId > 0 && t.quarantined) || (report != null && report.contains("正在释放解码资源"));
                if(t.quarantined){t.coverMessage="资源释放中，暂不可重用";overall="第"+(slot+1)+"路："+report;}
                t.updateCover();t.refreshHud(System.nanoTime());t.refreshAudio();if(oldActive!=t.activeId)grid.requestLayout();refreshSummary();
            });}
            public void onVideoSize(int slot,long id,int w,int h){ui.post(()->{if(!live(generation)||slot<0||slot>=4)return;Tile t=tiles[slot];if(t.activeId!=id)return;if(t.videoW!=w||t.videoH!=h){t.videoW=w;t.videoH=h;grid.requestLayout();}});}

            public void onFirstFrame(int slot, long id) {
                ui.post(() -> {
                    if (!live(generation) || slot < 0 || slot >= 4 || id <= 0 || tiles[slot].activeId != id)
                        return;
                    Surface surface = surfaces.get(slot);
                    if (surface == null || !surface.isValid()) return;
                    tiles[slot].firstFrame = true;
                    tiles[slot].updateCover();
                    updatePresentation();
                });
            }
            public void onAudioSelection(long id,long revision){ui.post(()->{if(!live(generation)||revision<audioRevision)return;audioRevision=revision;selectedAudioId=id;for(Tile tile:tiles)tile.refreshAudio();refreshSummary();});}
            public void onAudioStatus(int slot,long id,String text){ui.post(()->{if(live(generation)&&slot>=0&&slot<4&&tiles[slot].activeId==id){tiles[slot].audioStatus=text;tiles[slot].refreshAudio();}});}
            public void onAudioPlaybackStatus(int slot,long id,String text,boolean error){ui.post(()->{if(live(generation)&&slot>=0&&slot<4&&tiles[slot].activeId==id){tiles[slot].playbackStatus=text;tiles[slot].playbackError=error;tiles[slot].refreshAudio();}});}
            public void onMetrics(int slot,long id,StreamMetrics metrics){ui.post(()->{
                if(!live(generation)||slot<0||slot>=4||metrics==null)return;Tile tile=tiles[slot];if(tile.activeId!=id)return;
                if(tile.metrics!=null&&metrics.sampledAtNs<tile.metrics.sampledAtNs)return;
                tile.metrics=metrics;tile.refreshHud(System.nanoTime());
            });}
            public void onStatus(String message){ui.post(()->{if(live(generation)){overall=message;refreshSummary();}});}
        }, InternalAudio.receiver(activity));
        receiver.start();
        discovery=new LanDiscovery(activity,new LanDiscovery.Listener(){public void onDevicesChanged(List<LanDiscovery.Device> devices){}public void onStatus(String message){if(live(generation))discoveryStatus.setText(message);}});
        for(Tile tile:tiles){tile.coverMessage="等待连接";tile.updateCover();}refreshSummary();
    }

    private void refreshSummary() {
        updatePresentation();
        MultiReceiver r = receiver;
        int focus = grid.focusedSlot();
        summary.setText("已占用 " + (r == null ? 0 : r.activeCount()) + "/4 · 待确认 " + (r == null ? 0 : r.pendingCount()) + (focus >= 0 ? " · 放大第" + (focus + 1) + "路" : " · 自动分屏") + "\n" + (selectedAudioId == 0 ? "声音：全部静音 · " : "声音：已选一路 · ") + overall);
    }
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
                .setPositiveButton("复制报告", (d, w) -> activity.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("LanMirror 1.8.0", message))).setNeutralButton("连接信息 / 全部状态", (d, w) -> showInformation());
        if(disconnect!=null)b.setNegativeButton("断开这一路",(d,w)->disconnect.run());
        else if(grid.focusedSlot()>=0)b.setNegativeButton("恢复自动分屏",(d,w)->gridRestore());b.show();
    }
    /** Keep every Tile attached/visible. Park nonparticipants at 1x1 in the outer gap. */
    private final class AdaptiveGrid extends ViewGroup {
        final AdaptiveFocusState focus=new AdaptiveFocusState();
        AdaptiveLayoutPolicy.Plan plan;String layoutKind="empty";

        AdaptiveGrid() {
            super(activity);
            setClipChildren(true);
            setClipToPadding(true);
            setBackgroundColor(C_GRID);
        }
        long[] activeIds(){long[] ids=new long[4];for(int i=0;i<4;i++)if(tiles[i]!=null)ids[i]=tiles[i].activeId;return ids;}
        int focusedSlot(){return focus.resolve(activeIds());}

        void toggle(Tile tile) {
            if (informationVisible || tile.activeId <= 0) return;
            focus.toggle(tile.index, tile.activeId);
            requestLayout();
            refreshSummary();
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int w=resolveSize(dp(320),widthSpec),h=resolveSize(dp(360),heightSpec);setMeasuredDimension(w,h);
            ArrayList<Integer> order=new ArrayList<>();int[] widths=new int[4],heights=new int[4];
            for(int i=0;i<4;i++){Tile t=tiles[i];if(t==null)continue;if(t.activeId>0)order.add(i);widths[i]=t.videoW;heights[i]=t.videoH;}
            Collections.sort(order,(a,b)->Long.compare(tiles[a].activeId,tiles[b].activeId));int[] active=new int[order.size()];for(int i=0;i<active.length;i++)active[i]=order.get(i);
            int header=dp(40);for(Tile tile:tiles)if(tile!=null)header=Math.max(header,tile.titleHeight()+tile.textHeight(tile.hud));
            int selected = focusedSlot();
            plan = AdaptiveLayoutPolicy.arrange(w, h, selected >= 0 ? 0 : dp(4), header, active, widths, heights, selected);
            layoutKind = plan.kind;
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
            this.index = index;
            box = new TileBox();
            box.setBackgroundColor(C_SURFACE);
            title = label((index + 1) + " · 空闲", 12);
            title.setTextColor(C_INK);
            title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            title.setPadding(dp(10), dp(6), dp(10), 0);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(title);
            hud = label(StreamHud.label(null, System.nanoTime()), 11);
            hud.setTextColor(C_MUTED);
            hud.setPadding(dp(10), dp(2), dp(10), dp(6));
            hud.setLineSpacing(0, 1.10f);
            hud.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(hud);
            sound = new Button(activity);
            sound.setTextSize(11);
            sound.setAllCaps(false);
            sound.setMinWidth(0);
            sound.setMinimumWidth(0);
            sound.setMinHeight(0);
            sound.setMinimumHeight(0);
            sound.setPadding(dp(10), 0, dp(10), 0);
            sound.setOnClickListener(v->{MultiReceiver r=receiver;if(r!=null&&activeId>0)r.selectAudio(index,activeId);});refreshAudio();
            View.OnClickListener reportClick=v->{long id=activeId;String details=address+"\n\n"+StreamHud.details(metrics,System.nanoTime())+"\n\n"+report+"\n声音传输："+audioStatus+"\n声音播放："+playbackStatus+"\n当前声音按钮表示选择，不保证音源应用允许捕获。";
                showReport("第" + (index + 1) + "路 · " + name, details, id == 0 ? null : () -> {
                    MultiReceiver r = receiver;
                    if (r != null && activeId == id) {
                        coverMessage = "正在断开…";
                        quarantined = true;
                        updateCover();
                        updatePresentation();
                        r.disconnect(index, id);
                    }
                });
            };
            title.setOnClickListener(reportClick);hud.setOnClickListener(reportClick);sound.setOnLongClickListener(v->{reportClick.onClick(v);return true;});
            image=new FrameLayout(activity);image.setBackgroundColor(Color.BLACK);box.addView(image);
            video=new SurfaceView(activity);image.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
            cover = label("等待连接", 14);
            cover.setTextColor(C_MUTED);
            cover.setGravity(Gravity.CENTER);
            cover.setLineSpacing(0, 1.25f);
            cover.setBackgroundColor(Color.rgb(246, 248, 252));
            image.addView(cover, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout.LayoutParams soundParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30), Gravity.BOTTOM | Gravity.END);
            soundParams.setMargins(dp(8), dp(8), dp(8), dp(8));
            sound.setElevation(dp(2));
            image.addView(sound, soundParams);
            image.setOnClickListener(v -> grid.toggle(this));
            video.setOnClickListener(v -> grid.toggle(this));
            cover.setOnClickListener(v -> grid.toggle(this));
            View.OnLongClickListener focusAction=v->{grid.toggle(this);return true;};title.setOnLongClickListener(focusAction);hud.setOnLongClickListener(focusAction);video.setOnLongClickListener(focusAction);cover.setOnLongClickListener(focusAction);
            video.getHolder().addCallback(new SurfaceHolder.Callback(){
                public void surfaceCreated(SurfaceHolder holder){surfaces.set(index,holder.getSurface());maybeStart();}
                public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){surfaces.set(index,holder.getSurface());}

                public void surfaceDestroyed(SurfaceHolder holder) {
                    surfaces.set(index, null);
                    firstFrame = false;
                    if (activeId > 0) {
                        quarantined = true;
                        coverMessage = "显示窗口已失效，等待释放";
                    }
                    updateCover();
                    updatePresentation();
                    MultiReceiver r = receiver;
                    if (r != null) r.surfaceLost(index);
                }
            });
        }
        void setParked(boolean value){if(parked==value)return;parked=value;box.setImportantForAccessibility(value?View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS:View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);updateCover();}

        void updateCover() {
            String message = coverMessage;
            if (!parked && "等待连接".equals(message)) message = "等待连接\n在发送端选择这台设备";
            cover.setText(parked ? "" : message);
            cover.setVisibility(parked || activeId == 0 || !firstFrame || quarantined ? View.VISIBLE : View.GONE);
            sound.setVisibility(parked || fullscreen || activeId == 0 || !firstFrame || quarantined ? View.GONE : View.VISIBLE);
        }
        void refreshHud(long now){
            String value = StreamHud.label(activeId > 0 ? metrics : null, now);
            if(!value.equals(lastHud)){lastHud=value;hud.setText(value);}
            hud.setTextColor(metrics != null && activeId > 0 && metrics.isStale(now) ? Color.rgb(180, 83, 9) : C_MUTED);
        }
        void refreshAudio(){
            boolean selected=activeId>0&&activeId==selectedAudioId;
            sound.setText(selected?(playbackError?"声音 !":"声音 ✓"):"选声音");sound.setEnabled(activeId>0);
            sound.setTextColor(selected ? (playbackError ? Color.rgb(180, 83, 9) : Color.rgb(20, 122, 91)) : C_ACCENT);
            sound.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            sound.setBackground(rounded(selected ? (playbackError ? Color.rgb(255, 241, 222) : Color.rgb(225, 245, 236)) : C_ACCENT_SOFT, 15));
            sound.setVisibility(parked || fullscreen || activeId == 0 || !firstFrame || quarantined ? View.GONE : View.VISIBLE);
            sound.setContentDescription((selected?"已选择本路，再点静音。":"选择本路声音。")+audioStatus+"。"+playbackStatus);
        }
        int titleHeight(){return Math.max(textHeight(title),dp(32));}

        int textHeight(TextView text) {
            return Math.max(dp(20), text.getLineHeight() * (text == hud ? 2 : 1) + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
        }
        void fit(int w,int h){
            int outW=w,outH=h;if(videoW>0&&videoH>0){double factor=Math.min((double)w/videoW,(double)h/videoH);outW=Math.max(1,(int)(videoW*factor));outH=Math.max(1,(int)(videoH*factor));}
            if(video.getLayoutParams().width!=outW||video.getLayoutParams().height!=outH)video.setLayoutParams(new FrameLayout.LayoutParams(outW,outH,Gravity.CENTER));
        }
        /** Even a parked tile measures its Surface to at least 1x1; text may measure zero, never Surface. */
        final class TileBox extends ViewGroup {
            int titleH, hudH;
            TileBox(){super(activity);setClipChildren(true);}
            @Override protected void onMeasure(int wSpec,int hSpec){
                int w=Math.max(1,MeasureSpec.getSize(wSpec)),h=Math.max(1,MeasureSpec.getSize(hSpec));setMeasuredDimension(w,h);
                titleH = parked || fullscreen ? 0 : Math.min(titleHeight(), Math.max(0, h - 1));
                // Reserve by width/font, never by live values; extra text stays available in details.
                int contentW = Math.max(1, w - hud.getCompoundPaddingLeft() - hud.getCompoundPaddingRight());
                float referenceW = Math.max(hud.getPaint().measureText("延时≈9999ms · 999.9fps"), hud.getPaint().measureText("999.99 Mbps"));
                int lines = contentW >= referenceW ? 2 : 4;
                if (hud.getMinLines() != lines || hud.getMaxLines() != lines) hud.setLines(lines);
                hud.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                hudH = parked || activeId == 0 ? 0 : Math.min(hud.getMeasuredHeight(), Math.max(0, h - 1 - titleH));
                int imageH = Math.max(1, h - titleH - hudH);
                title.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(titleH, MeasureSpec.EXACTLY));
                hud.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(hudH,MeasureSpec.EXACTLY));fit(w,imageH);
                image.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(imageH,MeasureSpec.EXACTLY));
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int w = getMeasuredWidth();
                title.layout(0, 0, w, titleH);
                hud.layout(0, titleH, w, titleH + hudH);
                image.layout(0, titleH + hudH, w, getMeasuredHeight());
            }
        }
    }
    private static final class Prompt{final long id;final String name,address;Prompt(long id,String name,String address){this.id=id;this.name=name;this.address=address;}}
}
