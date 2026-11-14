package com.lanmirror.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int NETWORK_PERMISSION = 100, PROJECTION = 101;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout page, previewBox;
    private TextView status, addresses, senderStatus;
    private final EditText[] ipParts = new EditText[4];
    private EditText code;
    private SurfaceView preview;
    private ReceiverSession receiver;
    private SmoothReceiver smoothReceiver;
    private boolean smoothReceive = true;
    private int pendingPipeline = 3, pendingPort = ApprovalProtocol.PORT;
    private MultiReceivePanel multiPanel;
    private LanDiscovery senderDiscovery;
    private LinearLayout deviceList;
    private TextView discoveryText,selectedTarget;
    private Spinner pipelineSelector;
    private String discoveredHost,discoveredId;
    private int discoveredPort=ApprovalProtocol.PORT,discoveryGeneration;
    private boolean fillingIp,pendingAudio;
    private int receivePort() { return smoothReceive ? SmoothWire.PORT : 7236; }
    private String receiveCode, pendingHost, pendingCode;
    private String receiverReport = "等待连接";
    private int pendingProfile = StreamProfile.DEFAULT;
    private boolean pendingDirectTransport, pendingBurstEncoder;
    private boolean receiverCodecExperiment;
    private String lastServiceStatus = SenderService.status;
    private int mode; // 0 home, 1 receive, 2 send
    private int videoWidth, videoHeight, receiverGeneration;
    private boolean receiverWanted, receiverFullscreen;
    private Runnable permissionAction;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (mode == 2 && senderStatus != null && !SenderService.status.equals(lastServiceStatus)) {
                lastServiceStatus = SenderService.status; senderStatus.setText(lastServiceStatus);
            }
            handler.postDelayed(this, 700);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(19, 35, 55));
        getWindow().setNavigationBarColor(Color.rgb(19, 35, 55));
        home(); handler.post(ticker);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> { if (receiverFullscreen) setReceiverFullscreen(false); else if (mode==3&&multiPanel!=null&&multiPanel.collapseFocused()) {} else if (mode != 0) home(); else if (isTaskRoot()) moveTaskToBack(true); else finish(); });
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(235, 242, 250));
        t.setPadding(0, dp(8), 0, dp(8)); return t;
    }
    private void newPage(String title) {
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(12), dp(20), dp(12)); page.setBackgroundColor(Color.rgb(19, 35, 55));
        // Target 35+ is edge-to-edge: reserve actual system-bar insets on all Android versions.
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            if (receiverFullscreen) { v.setPadding(0, 0, 0, 0); return insets; }
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (mode == 1) setContentView(page);
        else {
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
            scroll.setBackgroundColor(Color.rgb(19, 35, 55)); scroll.addView(page); setContentView(scroll);
        }
        page.requestApplyInsets(); page.addView(text(title, 26));
    }
    private Button button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setOnClickListener(v -> action.run());
        page.addView(b, new LinearLayout.LayoutParams(-1, dp(52))); return b;
    }
    private void home() {
        if (receiverFullscreen) setReceiverFullscreen(false);
        stopReceiver(); stopSenderDiscovery(); mode = 0; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        newPage("局域镜像 · 1.8.0");
        page.addView(text("1080p60 · 流畅优先：有界缓冲池 / UDP / 异步硬解\n建议所有设备更新至1.8.0，连接同一5GHz/6GHz Wi-Fi或热点。", 16));
        button("接收端 · 自动发现 / 最多四路", () -> withNetworkPermission(this::multiPage));
        button("单路接收 · 配对码 / 旧版兼容", () -> withNetworkPermission(this::receivePage));
        button("发送端 · 镜像本机屏幕", this::sendPage);
        page.addView(text("画面通过局域网直接传输，不经过云端。\n请只在可信网络使用：多路连接逐个确认，单路使用配对码，画面传输未加密。\n\n支持 Android 8.0–17。多路模式支持内部声音（Android10+且音源允许捕获），不录麦克风；无远程触控。", 14));
        button("停止正在进行的发送", () -> { stopService(new Intent(this, SenderService.class)); Toast.makeText(this, "已请求停止", Toast.LENGTH_SHORT).show(); });
    }
    private void receivePage() {
        if (SenderService.running) { Toast.makeText(this, "请先停止本机发送，再切换接收端", Toast.LENGTH_LONG).show(); return; }
        stopReceiver(); stopSenderDiscovery(); mode = 1; receiverWanted = true; receiverCodecExperiment = false;
        receiveCode = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
        newPage("接收端 · 1.8.0"); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        addresses = text("接收地址：" + localAddresses() + "\n端口：" + receivePort() + "  ·  配对码：" + receiveCode, 17); page.addView(addresses);
        receiverReport = "准备接收…";
        status = text(receiverReport, 14); status.setMaxLines(3);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> showPerformanceReport(receiverReport)); page.addView(status);
        button("刷新本机 IP 地址", () -> addresses.setText("接收地址：" + localAddresses() + "\n端口：" + receivePort() + "  ·  配对码：" + receiveCode));
        Button codecOptions = button("解码C：原模式（点击设置）", () -> {});
        codecOptions.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("解码C独立对照 / 改动将断开连接")
            .setSingleChoiceItems(new String[] {"原模式（默认）", "实验：配置时提交SPS/PPS"}, receiverCodecExperiment ? 1 : 0,
                (dialog, which) -> {
                    boolean selected = which == 1; dialog.dismiss();
                    if (selected == receiverCodecExperiment) return;
                    receiverCodecExperiment = selected;
                    codecOptions.setText(selected ? "解码C：SPS/PPS预配置（实验）" : "解码C：原模式（点击设置）");
                    stopReceiverSession();
                    if (preview != null && preview.getHolder().getSurface().isValid()) startReceiver(preview.getHolder().getSurface());
                    Toast.makeText(this, "已重启接收，请发送端重新授权连接；请一次只开一个实验", Toast.LENGTH_LONG).show();
                }).setNegativeButton("取消", null).show());
        codecOptions.setVisibility(smoothReceive ? View.GONE : View.VISIBLE);
        Button pipelineOptions = button(smoothReceive ? "接收：流畅异步（UDP/TCP）" : "接收：旧版兼容TCP", () -> {});
        pipelineOptions.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("切换接收管线会断开当前连接")
            .setSingleChoiceItems(new String[] {"流畅异步：接受UDP或TCP视频（推荐）", "旧版同步TCP：7236"}, smoothReceive ? 0 : 1,
                (dialog, which) -> {
                    boolean chosen = which == 0; dialog.dismiss(); if (chosen == smoothReceive) return;
                    smoothReceive = chosen; stopReceiverSession();
                    codecOptions.setVisibility(chosen ? View.GONE : View.VISIBLE);
                    pipelineOptions.setText(chosen ? "接收：流畅异步（UDP/TCP）" : "接收：旧版兼容TCP");
                    addresses.setText("接收地址：" + localAddresses() + "\n端口：" + receivePort() + "  ·  配对码：" + receiveCode);
                    if (preview != null && preview.getHolder().getSurface().isValid()) startReceiver(preview.getHolder().getSurface());
                    Toast.makeText(this, "接收已重启，请发送端选择对应模式并重新连接", Toast.LENGTH_LONG).show();
                }).setNegativeButton("取消", null).show());
        videoWidth = videoHeight = 0;
        previewBox = new LinearLayout(this); previewBox.setGravity(Gravity.CENTER); previewBox.setBackgroundColor(Color.BLACK);
        previewBox.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l != or-ol || b-t != ob-ot) fitPreview();
        });
        page.addView(previewBox, new LinearLayout.LayoutParams(-1, 0, 1));
        button("全屏观看（点画面或返回退出）", () -> setReceiverFullscreen(true));
        preview = new SurfaceView(this);
        preview.setOnClickListener(v -> setReceiverFullscreen(!receiverFullscreen));
        previewBox.addView(preview, new LinearLayout.LayoutParams(-1, -1));
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { startReceiver(holder.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { stopReceiverSession(); }
        });
        button("结束接收 / 返回", this::home);
    }
    private void startReceiver(Surface surface) {
        if (!receiverWanted || receiver != null || smoothReceiver != null || mode != 1) return;
        final int generation = ++receiverGeneration;
        ReceiverSession.Listener events = new ReceiverSession.Listener() {
            @Override public void onStatus(String message) { runOnUiThread(() -> { if (mode == 1 && receiverWanted && generation == receiverGeneration) { receiverReport = message; status.setText(message); } }); }
            @Override public void onVideoSize(int w, int h) { runOnUiThread(() -> {
                if (mode != 1 || previewBox == null || generation != receiverGeneration) return;
                videoWidth = w; videoHeight = h;
                previewBox.post(MainActivity.this::fitPreview);
            }); }
        };
        if (smoothReceive) {
            SmoothReceiver session = new SmoothReceiver(surface, receiveCode, SmoothWire.PORT, events);
            smoothReceiver = session; session.start();
        } else {
            ReceiverSession session = new ReceiverSession(surface, receiveCode, 7236, events, receiverCodecExperiment);
            receiver = session; session.start();
        }
    }
    private void setReceiverFullscreen(boolean enabled) {
        if (mode != 1 && enabled) return;
        receiverFullscreen = enabled;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child != previewBox) child.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (enabled) controller.hide(WindowInsets.Type.systemBars()); else controller.show(WindowInsets.Type.systemBars());
            }
        } else getWindow().getDecorView().setSystemUiVisibility(enabled ?
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
        if (enabled) page.setPadding(0,0,0,0);
        page.requestApplyInsets(); previewBox.post(this::fitPreview);
    }
    private void fitPreview() {
        if (mode != 1 || preview == null || videoWidth <= 0 || videoHeight <= 0) return;
        int bw = previewBox.getWidth(), bh = previewBox.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float scale = Math.min((float)bw / videoWidth, (float)bh / videoHeight);
        int width = Math.max(1, (int)(videoWidth * scale)), height = Math.max(1, (int)(videoHeight * scale));
        if (preview.getLayoutParams().width != width || preview.getLayoutParams().height != height)
            preview.setLayoutParams(new LinearLayout.LayoutParams(width, height));
    }
    private void stopReceiverSession() { receiverGeneration++; if (receiver != null) { receiver.stop(); receiver = null; } if (smoothReceiver != null) { smoothReceiver.stop(); smoothReceiver = null; } }
    private void stopReceiver() { receiverWanted = false; stopReceiverSession(); if(multiPanel!=null){multiPanel.close();multiPanel=null;} }
    private void sendPage() {
        stopReceiver(); stopSenderDiscovery(); mode = 2; discoveredHost=null; discoveredId=null; newPage("发送端 · 1.8.0");
        page.addView(text("先在接收设备开启多路接收，再从下面点选设备。开始后需要对方允许；发现失败也可手填IP。", 15));
        discoveryText=text("正在查找同一局域网的接收设备…",13);page.addView(discoveryText);
        selectedTarget=text("未选择发现设备，可使用下方手动IP",13);page.addView(selectedTarget);
        deviceList=new LinearLayout(this);deviceList.setOrientation(LinearLayout.VERTICAL);page.addView(deviceList);
        button("刷新设备列表",()->withNetworkPermission(()->{if(mode==2){stopSenderDiscovery();startSenderDiscovery();}}));
        button("改为手动输入IP",this::clearDiscoveredTarget);
        page.addView(text("接收端 IPv4：分四段填写，句点已自动添加", 14));
        LinearLayout ipRow = new LinearLayout(this); ipRow.setGravity(Gravity.CENTER_VERTICAL);
        String[] hints = {"192", "168", "1", "23"};
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                TextView dot = text(".", 22); dot.setGravity(Gravity.CENTER);
                ipRow.addView(dot, new LinearLayout.LayoutParams(dp(14), -2));
            }
            EditText part = new EditText(this); part.setSingleLine(true); part.setMinWidth(0);
            part.setTextColor(Color.WHITE); part.setHintTextColor(Color.LTGRAY); part.setHint(hints[i]);
            part.setGravity(Gravity.CENTER); part.setTextSize(18);
            part.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            part.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(3)});
            part.setImeOptions(i < 3 ? android.view.inputmethod.EditorInfo.IME_ACTION_NEXT : android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            final int index = i;
            part.setOnEditorActionListener((v, action, event) -> {
                if (index < 3 && action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { ipParts[index + 1].requestFocus(); return true; }
                return false;
            });
            ipParts[i] = part; ipRow.addView(part, new LinearLayout.LayoutParams(0, dp(56), 1));
            part.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){if(!fillingIp)clearDiscoveredTarget();}
                public void afterTextChanged(android.text.Editable s){}
            });
        }
        page.addView(ipRow);
        fillIpv4(getPreferences(0).getString("host", ""));
        button("粘贴完整 IP（自动分成四段）", () -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            ClipData clip = clipboard.getPrimaryClip();
            String value = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this).toString().trim() : "";
            if (validIpv4(value)) {clearDiscoveredTarget();fillIpv4(value);}
            else Toast.makeText(this, "剪贴板需要是完整 IP，例如 192.168.1.23", Toast.LENGTH_LONG).show();
        });
        code = new EditText(this); code.setSingleLine(true); code.setTextColor(Color.WHITE); code.setHintTextColor(Color.LTGRAY); code.setHint("6 位配对码"); code.setInputType(2); page.addView(code);
        Spinner quality = new Spinner(this);
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[] {"1080p60 · 24Mbps · 推荐", "1080p60 · 32Mbps · 高清", "1080p60 · 40Mbps · 超清", "1080p60 · 60Mbps · 高速网络/硬件支持", "1080p60 · 18Mbps · 低带宽", "1080p60 · 12Mbps · 兼容", "720p60 · 6Mbps · 手动兼容档"});
        quality.setAdapter(choices); quality.setSelection(StreamProfile.sanitize(getPreferences(0).getInt("profileV120", StreamProfile.DEFAULT))); quality.setBackgroundColor(Color.rgb(215, 230, 240)); page.addView(quality);        page.addView(text("流畅模式采用12帧/8MB有界队列并立即消费，允许短时积压；不是70ms保证。UDP被网络拦截时手动选择TCP流畅。", 13));
        CheckBox directTransport = new CheckBox(this); directTransport.setText("传输A：减少发送中间拷贝（实验）"); directTransport.setTextColor(Color.WHITE); page.addView(directTransport);
        CheckBox burstEncoder = new CheckBox(this); burstEncoder.setText("编码B：连续取出已就绪输出（实验）"); burstEncoder.setTextColor(Color.WHITE); page.addView(burstEncoder);
        Spinner pipeline = new Spinner(this);pipelineSelector=pipeline;
        pipeline.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] {"多路接收 · UDP · 接收端确认（推荐）", "多路接收 · TCP · 接收端确认", "单路流畅 · UDP · 配对码", "单路流畅 · TCP · 配对码", "旧版兼容 · TCP + 同步解码"}));
        pipeline.setBackgroundColor(Color.rgb(215,230,240)); page.addView(pipeline);
        final CheckBox internalAudio=new CheckBox(this);internalAudio.setText("传输内部声音（两端1.8+，不录麦克风）");internalAudio.setTextColor(Color.WHITE);internalAudio.setChecked(Build.VERSION.SDK_INT>=29);internalAudio.setEnabled(Build.VERSION.SDK_INT>=29);page.addView(internalAudio);
        page.addView(text("声音仅支持多路及允许捕获的应用；可能包含其他应用的内部声音，开启时授权整个屏幕。系统会请求录音权限，但不采集麦克风。Android8/9、受保护内容或通话可能无声；关闭此项可兼容旧版。",12));
        directTransport.setVisibility(View.GONE); burstEncoder.setVisibility(View.GONE);
        pipeline.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int visible = position == 4 ? View.VISIBLE : View.GONE;
                directTransport.setVisibility(visible); burstEncoder.setVisibility(visible);
                code.setVisibility(position<2?View.GONE:View.VISIBLE);internalAudio.setEnabled(position<2&&Build.VERSION.SDK_INT>=29);
                if(position>=2&&discoveredHost!=null)clearDiscoveredTarget();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        lastServiceStatus = SenderService.status;
        senderStatus = text(SenderService.status, 14); senderStatus.setOnClickListener(v -> showPerformanceReport(SenderService.status)); page.addView(senderStatus);
        button("授权并开始镜像", () -> {
            if (SenderService.running) { senderStatus.setText("本机已在投屏，请先停止"); return; }
            int choice=pipeline.getSelectedItemPosition();boolean approval=choice<2;
            String h=discoveredHost!=null?discoveredHost:readIpv4(),c=approval?"000000":code.getText().toString().trim();
            if (discoveredHost==null && !validIpv4(h)) {
                ipParts[0].setError("四个框都要填写，每段为 0–255"); ipParts[0].requestFocus();
                senderStatus.setText("请填满四个数字框，每段 0–255，句点由应用自动添加");
                Toast.makeText(this, "请填写完整 IPv4，例如 192.168.1.23", Toast.LENGTH_LONG).show(); return;
            }
            if (!approval && !c.matches("[0-9]{6}")) {
                code.setError("请输入接收端显示的完整 6 位数字，开头的 0 不能省略"); code.requestFocus();
                senderStatus.setText("配对码需要完整的 6 位数字");
                Toast.makeText(this, "请填写接收端的 6 位配对码", Toast.LENGTH_LONG).show(); return;
            }
            for (EditText part : ipParts) part.setError(null); code.setError(null);
            pendingHost = h; pendingCode = c; pendingProfile = quality.getSelectedItemPosition();
            pendingDirectTransport = directTransport.isChecked(); pendingBurstEncoder = burstEncoder.isChecked();
            pendingPipeline = choice==0?3:choice==1?4:choice==2?2:choice==3?1:0;
            pendingPort = discoveredHost==null?ApprovalProtocol.PORT:discoveredPort;pendingAudio=approval&&Build.VERSION.SDK_INT>=29&&internalAudio.isChecked();
            getPreferences(0).edit().putInt("profileV120", pendingProfile).apply();
            getPreferences(0).edit().putString("host", h).apply();
            withNetworkPermission(this::requestProjection);
        });
        button("打开双屏延迟测试画面", () -> {
            if (!SenderService.running) { Toast.makeText(this, "请先开始投屏", Toast.LENGTH_LONG).show(); return; }
            startActivity(new Intent(this, LatencyTestActivity.class));
        });
        button("停止投屏", () -> stopService(new Intent(this, SenderService.class)));
        button("返回模式选择", this::home);
        page.addView(text("授权框建议选择整个屏幕。每次重新投屏都需要重新授权；接收端允许前不会开始传视频。",13));
        code.setVisibility(View.GONE);
        withNetworkPermission(()->{if(mode==2)startSenderDiscovery();});
    }
    private String installationId(){
        String id=getPreferences(0).getString("discoveryId",null);if(id==null){id=UUID.randomUUID().toString();getPreferences(0).edit().putString("discoveryId",id).apply();}return id;
    }
    private void multiPage(){
        if(SenderService.running){Toast.makeText(this,"请先停止本机发送，再切换接收端",Toast.LENGTH_LONG).show();return;}
        stopReceiver();stopSenderDiscovery();mode=3;receiverWanted=true;
        multiPanel=new MultiReceivePanel(this,this::home,installationId(),localAddresses());setContentView(multiPanel.view());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);multiPanel.resume();
    }
    private void clearDiscoveredTarget(){discoveredHost=null;discoveredId=null;discoveredPort=ApprovalProtocol.PORT;if(selectedTarget!=null)selectedTarget.setText("手动IP模式：多路端口7239，无需配对码；单路模式需配对码");}
    private void stopSenderDiscovery(){discoveryGeneration++;LanDiscovery old=senderDiscovery;senderDiscovery=null;if(old!=null)old.close();}
    private void startSenderDiscovery(){
        if(mode!=2||senderDiscovery!=null||deviceList==null)return;
        if(Build.VERSION.SDK_INT>=37&&checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)!=PackageManager.PERMISSION_GRANTED){discoveryText.setText("需要局域网权限，请点刷新设备列表并允许");return;}
        final int generation=++discoveryGeneration;
        senderDiscovery=new LanDiscovery(this,new LanDiscovery.Listener(){
            public void onDevicesChanged(List<LanDiscovery.Device> devices){
                if(mode!=2||generation!=discoveryGeneration)return;deviceList.removeAllViews();
                for(LanDiscovery.Device device:devices){
                    if(device.id.equals(installationId()))continue;
                    Button item=new Button(MainActivity.this);item.setAllCaps(false);item.setText(device.name+"\n"+device.host+":"+device.port+" · 最多"+device.slots+"路 · 接收端确认");
                    item.setOnClickListener(v->{
                        discoveredHost=device.host;discoveredId=device.id;discoveredPort=device.port;
                        if(validIpv4(device.host))fillIpv4(device.host);
                        else {fillingIp=true;try{for(EditText part:ipParts)part.setText("");}finally{fillingIp=false;}}
                        selectedTarget.setText("已选："+device.name+" · "+device.host+":"+device.port+"\n设备名称不是身份认证，请核对接收端");
                        if(pipelineSelector.getSelectedItemPosition()!=1)pipelineSelector.setSelection(0);
                        code.setVisibility(View.GONE);
                    });
                    deviceList.addView(item,new LinearLayout.LayoutParams(-1,-2));
                }
                if(deviceList.getChildCount()==0)deviceList.addView(text("暂未发现接收端。请在另一台设备开启“多路接收”；热点或访客网络可能屏蔽mDNS，可手填IP。",13));
            }
            public void onStatus(String message){if(mode==2&&generation==discoveryGeneration)discoveryText.setText(message);}
        });senderDiscovery.startDiscovery();
    }
    private void showPerformanceReport(String report) {
        String details = "版本 1.8.0 · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE + "\n\n" + report
            + "\n\n出帧→渲染提交指标不含采集、编码和面板刷新，不能当作70ms端到端达标证据。请用双屏高速摄像测量。";
        new AlertDialog.Builder(this).setTitle("性能指标 / 复制报告").setMessage(details)
            .setPositiveButton("复制报告", (dialog, which) -> {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("LanMirror performance", details));
                Toast.makeText(this, "报告已复制", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("关闭", null).show();
    }
    private String readIpv4() {
        String[] values = new String[4];
        for (int i = 0; i < 4; i++) values[i] = ipParts[i].getText().toString();
        return Ipv4.join(values);
    }
    private void fillIpv4(String address) {
        if (!validIpv4(address)) return;
        String[] parts = address.split("\\.");
        boolean was=fillingIp;fillingIp=true;try{for (int i = 0; i < 4; i++) ipParts[i].setText(parts[i]);}finally{fillingIp=was;}
    }
    private static boolean validIpv4(String s) { return Ipv4.isValid(s); }
    private void withNetworkPermission(Runnable action) {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            permissionAction = action; requestPermissions(new String[] {Manifest.permission.ACCESS_LOCAL_NETWORK}, NETWORK_PERMISSION);
        } else action.run();
    }
    private void requestProjection() {
        if(mode!=2)return;
        ArrayList<String> needed = new ArrayList<>();
        if(pendingAudio&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 102);
        else launchProjection();
    }
    private void launchProjection() {
        if(mode!=2||SenderService.running)return;MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        Intent consent=Build.VERSION.SDK_INT>=34&&pendingAudio?manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
        startActivityForResult(consent,PROJECTION);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NETWORK_PERMISSION) {
            Runnable action = permissionAction; permissionAction = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && action != null) action.run();
            else Toast.makeText(this, "需要局域网权限才能连接两台设备，请到应用设置允许。", Toast.LENGTH_LONG).show();
        } else if (requestCode == 102 && mode == 2) {
            if(pendingAudio&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
                new AlertDialog.Builder(this).setTitle("内部声音权限未允许").setMessage("不会录制麦克风，也不会绕过权限。是否仅投画面？")
                    .setPositiveButton("仅投画面",(dialog,which)->{pendingAudio=false;launchProjection();})
                    .setNegativeButton("取消",(dialog,which)->senderStatus.setText("已取消：未允许内部声音权限")).show();
            }else launchProjection(); // Notification denial alone does not block consent.
        }
    }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PROJECTION && resultCode == RESULT_OK && data != null && mode == 2) {
            Intent service = new Intent(this, SenderService.class).putExtra("result", resultCode).putExtra("consent", data)
                .putExtra("host", pendingHost).putExtra("code", pendingCode).putExtra("profile", pendingProfile).putExtra("directTransport", pendingDirectTransport).putExtra("burstEncoder", pendingBurstEncoder).putExtra("pipeline", pendingPipeline).putExtra("port", pendingPort).putExtra("audio", pendingAudio);
            try { startForegroundService(service); senderStatus.setText("正在启动…"); }
            catch (RuntimeException e) { senderStatus.setText("启动失败：" + e.getMessage()); }
        } else if (requestCode == PROJECTION && senderStatus != null) senderStatus.setText("已取消屏幕授权");
    }
    private String localAddresses() {
        ArrayList<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement(); if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> values = ni.getInetAddresses();
                while (values.hasMoreElements()) { InetAddress a = values.nextElement(); if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) result.add(a.getHostAddress() + " (" + ni.getName() + ")"); }
            }
        } catch (Exception ignored) { }
        return result.isEmpty() ? "未找到，请先连接 Wi-Fi 或热点" : android.text.TextUtils.join(" / ", result);
    }
    @Override public void onBackPressed() { if (receiverFullscreen) setReceiverFullscreen(false); else if (mode==3&&multiPanel!=null&&multiPanel.collapseFocused()) {} else if (mode != 0) home(); else super.onBackPressed(); }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){
        super.onConfigurationChanged(configuration);if(mode==3&&multiPanel!=null)multiPanel.configurationChanged(configuration);
    }
    @Override public void onStop() {
        super.onStop();
        stopSenderDiscovery();if(mode==3&&multiPanel!=null)multiPanel.pause();
        if (mode == 1) { stopReceiverSession(); if (status != null) status.setText("接收已暂停，返回应用后恢复"); }
    }
    @Override public void onResume() {
        super.onResume();
        if(mode==3&&multiPanel!=null)multiPanel.resume();
        if(mode==2)startSenderDiscovery();
        if (mode == 1 && receiverWanted && preview != null && preview.getHolder().getSurface().isValid()) startReceiver(preview.getHolder().getSurface());
    }
    @Override public void onDestroy() { stopReceiver(); stopSenderDiscovery(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
