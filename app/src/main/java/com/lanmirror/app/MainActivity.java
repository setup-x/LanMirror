package com.lanmirror.app;

import com.lanmirror.core.api.*;
import com.lanmirror.discovery.api.LanDiscovery;
import com.lanmirror.receiver.api.ReceiverSession;
import com.lanmirror.receiver.api.SmoothReceiver;

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
    // Shared visual tokens for every page.
    private static final int C_BG = Color.rgb(243, 246, 251);
    private static final int C_SURFACE = Color.WHITE;
    private static final int C_INK = Color.rgb(16, 27, 45);
    private static final int C_BODY = Color.rgb(51, 65, 92);
    private static final int C_MUTED = Color.rgb(108, 124, 150);
    private static final int C_LINE = Color.rgb(228, 234, 244);
    private static final int C_ACCENT = Color.rgb(47, 98, 232);
    private static final int C_ACCENT_SOFT = Color.rgb(235, 241, 255);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private FrameLayout previewBox;
    private ScrollView receiverOverlay;
    private Button continueWatching;
    private TextView status, addresses, portValue, transportValue, receiveTitle, senderStatus;
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
    private boolean receiverWanted, receiverFullscreen, receiverResumed, receiverFirstFrame;
    private long receiverConnectionId, receiverEndedId;
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
        return text(value, size, C_INK);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.12f);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView caption(String value) {
        TextView t = text(value, 11, C_MUTED);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setLetterSpacing(0.10f);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }
    private void newPage(String title) {
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int baseH = mode == 1 ? 0 : dp(24), baseV = mode == 1 ? 0 : dp(16);
        page.setPadding(baseH, baseV, baseH, baseV);
        page.setBackgroundColor(C_BG);
        // Target 35+ is edge-to-edge: reserve actual system-bar insets on all Android versions.
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            if (receiverFullscreen) { v.setPadding(0, 0, 0, 0); return insets; }
            int h = mode == 1 ? 0 : dp(24), vertical = mode == 1 ? 0 : dp(16);
            v.setPadding(h + insets.getSystemWindowInsetLeft(), vertical + insets.getSystemWindowInsetTop(),
                    h + insets.getSystemWindowInsetRight(), vertical + insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (mode == 1) setContentView(page);
        else {
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
            scroll.setBackgroundColor(C_BG);
            scroll.addView(page);
            setContentView(scroll);
        }
        setHomeBars(true);
        page.requestApplyInsets();
        if (mode != 1) {
            TextView heading = text(title, 26);
            heading.setTypeface(null, android.graphics.Typeface.BOLD);
            page.addView(heading);
        }
    }

    private Button button(LinearLayout parent, String value, boolean primary, Runnable action) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        styleButton(b, primary);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.topMargin = dp(8);
        parent.addView(b, params);
        return b;
    }

    private Button outlineButton(LinearLayout parent, String value, Runnable action) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setBackgroundTintList(null);
        b.setBackground(ripple(outlineShape(C_SURFACE, C_LINE, 16), 16));
        b.setTextColor(C_BODY);
        b.setTextSize(15);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setMinHeight(dp(50));
        b.setMinimumHeight(dp(50));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.topMargin = dp(8);
        parent.addView(b, params);
        return b;
    }

    private void styleButton(Button button, boolean primary) {
        button.setBackgroundTintList(null);
        button.setBackground(ripple(rounded(primary ? C_ACCENT : C_ACCENT_SOFT, 16), 16));
        button.setTextColor(primary ? Color.WHITE : C_ACCENT);
        button.setTextSize(15);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setMinHeight(dp(50));
        button.setMinimumHeight(dp(50));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setStateListAnimator(null);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(cardSurface());
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        parent.addView(card, params);
        return card;
    }

    private LinearLayout sectionCard(LinearLayout parent, String title) {
        LinearLayout card = card(parent);
        card.addView(caption(title));
        return card;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        parent.addView(line, params);
    }

    private TextView infoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        TextView name = text(label, 13, C_MUTED);
        name.setPadding(0, 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(dp(84), -2));
        TextView content = text(value, 14, C_INK);
        content.setPadding(0, 0, 0, 0);
        content.setGravity(Gravity.END);
        content.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
        return content;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, int radius) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private android.graphics.drawable.GradientDrawable outlineShape(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable shape = rounded(fill, radius);
        shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    private android.graphics.drawable.RippleDrawable ripple(android.graphics.drawable.Drawable content, int radius) {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14101B2D),
                content, rounded(Color.WHITE, radius));
    }

    private android.graphics.drawable.GradientDrawable cardSurface() {
        return outlineShape(C_SURFACE, C_LINE, 20);
    }

    private android.graphics.drawable.GradientDrawable circle(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private android.graphics.drawable.Drawable inputBackground() {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, outlineShape(C_SURFACE, C_ACCENT, 14));
        states.addState(new int[]{}, rounded(Color.rgb(242, 245, 251), 14));
        return states;
    }

    private void styleInput(EditText input) {
        input.setTextColor(C_INK);
        input.setHintTextColor(C_MUTED);
        input.setTextSize(15);
        input.setBackground(inputBackground());
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setMinHeight(dp(52));
        input.setMinimumHeight(dp(52));
    }

    private void refreshReceiveInfo() {
        if (addresses != null) addresses.setText(localAddresses());
        if (portValue != null) portValue.setText(String.valueOf(receivePort()));
        if (transportValue != null) transportValue.setText(smoothReceive ? "流畅异步" : "旧版兼容");
    }

    private ArrayAdapter<String> spinnerChoices(String[] values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return style(super.getView(position, convertView, parent));
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return style(super.getDropDownView(position, convertView, parent));
            }

            private View style(View view) {
                TextView label = (TextView) view;
                label.setTextColor(C_INK);
                label.setTextSize(14);
                label.setSingleLine(false);
                label.setMaxLines(3);
                label.setMinHeight(dp(48));
                label.setPadding(dp(8), dp(6), dp(8), dp(6));
                label.setBackgroundColor(Color.TRANSPARENT);
                return label;
            }
        };
    }

    private void styleCheckBox(CheckBox check) {
        check.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{C_MUTED, C_INK}));
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(C_ACCENT));
        check.setMinHeight(dp(48));
        check.setTextSize(14);
    }
    private void home() {
        if (receiverFullscreen) setReceiverFullscreen(false);
        stopReceiver();
        stopSenderDiscovery();
        mode = 0;
        restoreSystemBars();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        renderHome();
    }

    private void renderHome() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(28), dp(24), dp(24));
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(28) + insets.getSystemWindowInsetTop(), dp(24) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);
        scroll.setClipToPadding(false);
        scroll.addView(page);
        setContentView(scroll);
        setHomeBars(true);
        page.requestApplyInsets();
        TextView brand = homeText("LANMIRROR  /  1.8.0", 11, C_ACCENT);
        brand.setLetterSpacing(0.14f);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setPadding(0, 0, 0, 0);
        page.addView(brand);
        TextView title = homeText("局域镜像", 34, C_INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(14), 0, dp(2));
        page.addView(title);
        page.addView(homeText("连接同一网络，让屏幕自由流动。", 15, C_MUTED));
        TextView section = homeText("选择投屏方式", 11, C_MUTED);
        section.setTypeface(null, android.graphics.Typeface.BOLD);
        section.setLetterSpacing(0.10f);
        section.setPadding(0, dp(30), 0, dp(10));
        page.addView(section);
        homeCard("多路接收", "最多 4 路 · 自动发现", "接收其他设备的画面，逐路确认连接。", true, () -> withNetworkPermission(this::multiPage));
        homeCard("发送屏幕", "镜像本机屏幕", "选择接收设备，授权后开始投屏。", false, this::sendPage);
        homeCard("单路兼容", "配对码 · 旧版兼容", "通过配对码连接单个接收设备。", false, () -> withNetworkPermission(this::receivePage));
        TextView stop = homeText("停止正在进行的发送", 13, C_MUTED);
        stop.setGravity(Gravity.CENTER);
        stop.setMinHeight(dp(48));
        stop.setPadding(dp(12), dp(12), dp(12), dp(12));
        stop.setBackground(ripple(rounded(Color.TRANSPARENT, 14), 14));
        stop.setFocusable(true);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, SenderService.class));
            Toast.makeText(this, "已请求停止", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, -2);
        stopParams.topMargin = dp(10);
        page.addView(stop, stopParams);
        LinearLayout notes = new LinearLayout(this);
        notes.setOrientation(LinearLayout.VERTICAL);
        notes.setPadding(dp(20), dp(18), dp(20), dp(18));
        notes.setBackground(cardSurface());
        notes.setElevation(dp(1));
        TextView network = homeText("连接建议", 14, C_INK);
        network.setTypeface(null, android.graphics.Typeface.BOLD);
        network.setPadding(0, 0, 0, dp(4));
        notes.addView(network);
        notes.addView(homeText("设备请更新至 1.8.0，并连接同一 5GHz / 6GHz Wi-Fi 或热点。1080p60 · 流畅优先：有界缓冲池 / UDP / 异步硬解。", 13, C_MUTED));
        TextView safety = homeText("仅在可信局域网使用", 14, C_INK);
        safety.setTypeface(null, android.graphics.Typeface.BOLD);
        safety.setPadding(0, dp(18), 0, dp(4));
        notes.addView(safety);
        notes.addView(homeText("画面直接传输，不经过云端；传输未加密。多路连接逐个确认，单路使用配对码。", 13, C_MUTED));
        notes.addView(homeText("支持 Android 8.0–17。内部声音需 Android 10+ 且音源允许捕获；不录麦克风，无远程触控。", 12, C_MUTED));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(22);
        page.addView(notes, noteParams);
    }

    private TextView homeText(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private void homeCard(String title, String tag, String description, boolean primary, Runnable action) {
        int ink = primary ? Color.WHITE : C_INK, muted = primary ? Color.rgb(214, 226, 255) : C_MUTED;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setMinimumHeight(dp(48));
        card.setBackground(primary ? ripple(rounded(C_ACCENT, 22), 22) : ripple(cardSurface(), 22));
        card.setElevation(dp(1));
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());
        card.setContentDescription(title + "，" + tag + "，" + description);
        TextView tagView = homeText(tag, 11, muted);
        tagView.setLetterSpacing(0.08f);
        tagView.setTypeface(null, android.graphics.Typeface.BOLD);
        tagView.setPadding(0, 0, 0, 0);
        card.addView(tagView);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = homeText(title, 22, ink);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(0, dp(6), 0, dp(2));
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = homeText("›", 24, muted);
        arrow.setPadding(0, dp(6), 0, 0);
        titleRow.addView(arrow);
        card.addView(titleRow);
        card.addView(homeText(description, 13, muted));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        params.bottomMargin = dp(2);
        page.addView(card, params);
    }

    private void setHomeBars(boolean light) {
        int color = light ? C_BG : Color.rgb(19, 35, 55);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null)
                controller.setSystemBarsAppearance(light ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            View decor = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(light ? decor.getSystemUiVisibility() | flags : decor.getSystemUiVisibility() & ~flags);
        }
    }
    private void receivePage() {
        if (SenderService.running) { Toast.makeText(this, "请先停止本机发送，再切换接收端", Toast.LENGTH_LONG).show(); return; }
        setHomeBars(true);
        stopReceiver(); stopSenderDiscovery(); mode = 1; receiverWanted = true; receiverCodecExperiment = false;
        receiveCode = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
        newPage("接收端");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        previewBox = new FrameLayout(this);
        previewBox.setBackgroundColor(Color.BLACK);
        previewBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) fitPreview();
        });
        preview = new SurfaceView(this);
        preview.setOnClickListener(v -> {
            if (receiverFullscreen) setReceiverFullscreen(false);
        });
        previewBox.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        receiverOverlay = new ScrollView(this);
        receiverOverlay.setFillViewport(true);
        receiverOverlay.setBackgroundColor(C_BG);
        receiverOverlay.setClickable(true);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(24), dp(20), dp(24), dp(28));
        receiverOverlay.addView(controls, new ScrollView.LayoutParams(-1, -2));
        previewBox.addView(receiverOverlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        View brandDot = new View(this);
        brandDot.setBackground(circle(C_ACCENT));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotParams.rightMargin = dp(8);
        brandRow.addView(brandDot, dotParams);
        TextView brand = text("LANMIRROR  /  接收端  ·  1.8.0", 11, C_ACCENT);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setLetterSpacing(0.12f);
        brand.setPadding(0, 0, 0, 0);
        brandRow.addView(brand);
        controls.addView(brandRow);

        receiveTitle = text("等待设备投屏", 28, C_INK);
        receiveTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        receiveTitle.setPadding(0, dp(10), 0, dp(2));
        controls.addView(receiveTitle);
        controls.addView(text("在发送端选择这台设备，或输入下方配对码。连接需要发送端确认。", 14, C_MUTED));

        LinearLayout codeCard = card(controls);
        codeCard.addView(caption("配对码"));
        TextView codeView = text(receiveCode, 40, C_ACCENT);
        codeView.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD));
        codeView.setLetterSpacing(0.16f);
        codeView.setPadding(0, 0, 0, 0);
        codeCard.addView(codeView);
        codeCard.addView(text("在发送端输入这 6 位数字即可配对", 12, C_MUTED));
        divider(codeCard);
        addresses = infoRow(codeCard, "接收地址", localAddresses());
        portValue = infoRow(codeCard, "接收端口", String.valueOf(receivePort()));
        transportValue = infoRow(codeCard, "传输模式", smoothReceive ? "流畅异步" : "旧版兼容");

        LinearLayout statusCard = sectionCard(controls, "连接状态");
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        View statusDot = new View(this);
        statusDot.setBackground(circle(C_ACCENT));
        LinearLayout.LayoutParams statusDotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        statusDotParams.rightMargin = dp(10);
        statusDotParams.topMargin = dp(8);
        statusRow.addView(statusDot, statusDotParams);
        receiverReport = "准备接收…";
        status = text(receiverReport, 14, C_BODY);
        status.setMaxLines(4);
        status.setPadding(0, 0, 0, 0);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> showPerformanceReport(receiverReport));
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        statusCard.addView(statusRow);
        TextView metricsLink = text("查看性能指标  ›", 12, C_ACCENT);
        metricsLink.setTypeface(null, android.graphics.Typeface.BOLD);
        metricsLink.setOnClickListener(v -> showPerformanceReport(receiverReport));
        statusCard.addView(metricsLink);

        LinearLayout settings = sectionCard(controls, "接收设置");
        button(settings, "刷新本机 IP 地址", false, this::refreshReceiveInfo);
        Button codecOptions = button(settings, "解码C：原模式（点击设置）", false, () -> {
        });
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
        Button pipelineOptions = button(settings, smoothReceive ? "接收：流畅异步（UDP/TCP）" : "接收：旧版兼容TCP", false, () -> {
        });
        pipelineOptions.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("切换接收管线会断开当前连接")
            .setSingleChoiceItems(new String[] {"流畅异步：接受UDP或TCP视频（推荐）", "旧版同步TCP：7236"}, smoothReceive ? 0 : 1,
                (dialog, which) -> {
                    boolean chosen = which == 0; dialog.dismiss(); if (chosen == smoothReceive) return;
                    smoothReceive = chosen; stopReceiverSession();
                    codecOptions.setVisibility(chosen ? View.GONE : View.VISIBLE);
                    pipelineOptions.setText(chosen ? "接收：流畅异步（UDP/TCP）" : "接收：旧版兼容TCP");
                    refreshReceiveInfo();
                    if (preview != null && preview.getHolder().getSurface().isValid()) startReceiver(preview.getHolder().getSurface());
                    Toast.makeText(this, "接收已重启，请发送端选择对应模式并重新连接", Toast.LENGTH_LONG).show();
                }).setNegativeButton("取消", null).show());

        continueWatching = button(controls, "继续观看", true, () -> setReceiverFullscreen(true));
        continueWatching.setVisibility(View.GONE);
        outlineButton(controls, "结束接收 / 返回", this::home);
        LinearLayout.LayoutParams footnoteParams = new LinearLayout.LayoutParams(-1, -2);
        footnoteParams.topMargin = dp(14);
        controls.addView(text("画面仅在你允许后传输，未加密 · 建议使用 5GHz / 6GHz 可信局域网", 11, C_MUTED), footnoteParams);

        resetReceiverConnection();
        page.addView(previewBox, new LinearLayout.LayoutParams(-1, 0, 1));
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (mode == 1 && preview.getHolder() == holder) startReceiver(holder.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mode == 1 && preview.getHolder() == holder) stopReceiverSession();
            }
        });
    }
    private void startReceiver(Surface surface) {
        if (!receiverWanted || !receiverResumed || receiver != null || smoothReceiver != null || mode != 1)
            return;
        final int generation = ++receiverGeneration;
        resetReceiverConnection();
        receiverEndedId = 0;
        ReceiverSession.Listener events = new ReceiverSession.Listener() {
            @Override
            public void onStatus(String message) {
                handler.post(() -> {
                    if (acceptReceiverEvent(generation)) {
                        receiverReport = message;
                        status.setText(message);
                    }
                });
            }

            @Override
            public void onVideoSize(int w, int h) {
                handler.post(() -> {
                    if (!acceptReceiverEvent(generation) || receiverConnectionId <= receiverEndedId)
                        return;
                videoWidth = w; videoHeight = h;
                previewBox.post(MainActivity.this::fitPreview);
            }); }

            @Override
            public void onConnectionStarted(long id) {
                handler.post(() -> {
                    if (!acceptReceiverEvent(generation) || id <= receiverEndedId || id <= receiverConnectionId)
                        return;
                    resetReceiverConnection();
                    receiverConnectionId = id;
                    receiveTitle.setText("设备已连接");
                });
            }

            @Override
            public void onFirstFrame(long id) {
                handler.post(() -> {
                    if (!acceptReceiverEvent(generation) || id <= receiverEndedId || id != receiverConnectionId || receiverFirstFrame)
                        return;
                    receiverFirstFrame = true;
                    continueWatching.setVisibility(View.VISIBLE);
                    receiveTitle.setText("正在接收画面");
                    setReceiverFullscreen(true);
                });
            }

            @Override
            public void onConnectionEnded(long id) {
                handler.post(() -> {
                    if (!acceptReceiverEvent(generation) || id <= receiverEndedId) return;
                    receiverEndedId = id;
                    if (receiverConnectionId <= id) resetReceiverConnection();
                });
            }
        };
        if (smoothReceive) {
            SmoothReceiver session = new SmoothReceiver(surface, receiveCode, SmoothWire.PORT, events);
            smoothReceiver = session; session.start();
        } else {
            ReceiverSession session = new ReceiverSession(surface, receiveCode, 7236, events, receiverCodecExperiment);
            receiver = session; session.start();
        }
    }

    private boolean acceptReceiverEvent(int generation) {
        return mode == 1 && receiverWanted && receiverResumed && generation == receiverGeneration;
    }

    private void resetReceiverConnection() {
        receiverConnectionId = 0;
        receiverFirstFrame = false;
        videoWidth = videoHeight = 0;
        if (mode != 1 || receiverOverlay == null) return;
        setReceiverFullscreen(false);
        continueWatching.setVisibility(View.GONE);
        preview.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        if (receiveTitle != null) receiveTitle.setText("等待设备投屏");
    }

    private void restoreSystemBars() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(true);
        View decor = getWindow().getDecorView();
        int immersive = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~immersive);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.show(WindowInsets.Type.systemBars());
            }
        }
        setHomeBars(true);
    }
    private void setReceiverFullscreen(boolean enabled) {
        if (mode != 1 || receiverOverlay == null) return;
        if (enabled && (!receiverWanted || !receiverResumed || !receiverFirstFrame || receiverConnectionId <= receiverEndedId))
            return;
        receiverFullscreen = enabled;
        receiverOverlay.setVisibility(enabled ? View.GONE : View.VISIBLE);
        preview.setImportantForAccessibility(enabled ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        final View decor = getWindow().getDecorView();
        final int immersiveFlags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (enabled) {
            setHomeBars(false);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(immersiveFlags);
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                }
            }
            // Some devices drop hide() before the window gains focus; retry once after layout.
            decor.post(() -> {
                if (!receiverFullscreen || mode != 1) return;
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) controller.hide(WindowInsets.Type.systemBars());
                } else decor.setSystemUiVisibility(immersiveFlags);
            });
        } else restoreSystemBars();
        page.setBackgroundColor(enabled ? Color.BLACK : C_BG);
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
            preview.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
    }

    private void stopReceiverSession() {
        receiverGeneration++;
        resetReceiverConnection();
        receiverEndedId = 0;
        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }
        if (smoothReceiver != null) {
            smoothReceiver.stop();
            smoothReceiver = null;
        }
    }
    private void stopReceiver() { receiverWanted = false; stopReceiverSession(); if(multiPanel!=null){multiPanel.close();multiPanel=null;} }
    private void sendPage() {
        setHomeBars(true);
        stopReceiver(); stopSenderDiscovery(); mode = 2; discoveredHost=null; discoveredId=null; newPage("发送端 · 1.8.0");
        page.addView(text("先在接收设备开启多路接收，再从下面点选设备。开始后需要对方允许；发现失败也可手填 IP。", 14, C_MUTED));
        LinearLayout discoveryCard = sectionCard(page, "01  发现接收设备");
        discoveryText = text("正在查找同一局域网的接收设备…", 13, C_MUTED);
        discoveryCard.addView(discoveryText);
        selectedTarget = text("未选择发现设备，可使用下方手动 IP", 13, C_ACCENT);
        discoveryCard.addView(selectedTarget);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        discoveryCard.addView(deviceList);
        button(discoveryCard, "刷新设备列表", false, () -> withNetworkPermission(() -> {
            if (mode == 2) {
                stopSenderDiscovery();
                startSenderDiscovery();
            }
        }));
        button(discoveryCard, "改为手动输入IP", false, this::clearDiscoveredTarget);
        LinearLayout manualCard = sectionCard(page, "02  手动连接");
        manualCard.addView(text("接收端 IPv4：分四段填写，句点已自动添加", 13, C_MUTED));
        LinearLayout ipRow = new LinearLayout(this); ipRow.setGravity(Gravity.CENTER_VERTICAL);
        String[] hints = {"192", "168", "1", "23"};
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                TextView dot = text(".", 22); dot.setGravity(Gravity.CENTER);
                ipRow.addView(dot, new LinearLayout.LayoutParams(dp(14), -2));
            }
            EditText part = new EditText(this); part.setSingleLine(true); part.setMinWidth(0);
            styleInput(part);
            part.setHint(hints[i]);
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
        manualCard.addView(ipRow);
        fillIpv4(getPreferences(0).getString("host", ""));
        button(manualCard, "粘贴完整 IP（自动分成四段）", false, () -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            ClipData clip = clipboard.getPrimaryClip();
            String value = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this).toString().trim() : "";
            if (validIpv4(value)) {clearDiscoveredTarget();fillIpv4(value);}
            else Toast.makeText(this, "剪贴板需要是完整 IP，例如 192.168.1.23", Toast.LENGTH_LONG).show();
        });
        code = new EditText(this);
        code.setSingleLine(true);
        styleInput(code);
        code.setHint("6 位配对码");
        code.setInputType(2);
        manualCard.addView(code);
        LinearLayout optionsCard = sectionCard(page, "03  画质与声音");
        optionsCard.addView(text("画质档位", 13, C_MUTED));
        Spinner quality = new Spinner(this);
        ArrayAdapter<String> choices = spinnerChoices(new String[]{"1080p60 · 24Mbps · 推荐", "1080p60 · 32Mbps · 高清", "1080p60 · 40Mbps · 超清", "1080p60 · 60Mbps · 高速网络/硬件支持", "1080p60 · 18Mbps · 低带宽", "1080p60 · 12Mbps · 兼容", "720p60 · 6Mbps · 手动兼容档"});
        quality.setAdapter(choices);
        quality.setSelection(StreamProfile.sanitize(getPreferences(0).getInt("profileV120", StreamProfile.DEFAULT)));
        quality.setMinimumHeight(dp(48));
        optionsCard.addView(quality);
        optionsCard.addView(text("流畅模式采用12帧/8MB有界队列并立即消费，允许短时积压；不是70ms保证。UDP被网络拦截时手动选择TCP流畅。", 12, C_MUTED));
        CheckBox directTransport = new CheckBox(this);
        directTransport.setText("传输A：减少发送中间拷贝（实验）");
        styleCheckBox(directTransport);
        optionsCard.addView(directTransport);
        CheckBox burstEncoder = new CheckBox(this);
        burstEncoder.setText("编码B：连续取出已就绪输出（实验）");
        styleCheckBox(burstEncoder);
        optionsCard.addView(burstEncoder);
        Spinner pipeline = new Spinner(this);pipelineSelector=pipeline;
        pipeline.setAdapter(spinnerChoices(
            new String[] {"多路接收 · UDP · 接收端确认（推荐）", "多路接收 · TCP · 接收端确认", "单路流畅 · UDP · 配对码", "单路流畅 · TCP · 配对码", "旧版兼容 · TCP + 同步解码"}));
        optionsCard.addView(text("连接模式", 13, C_MUTED));
        pipeline.setMinimumHeight(dp(48));
        optionsCard.addView(pipeline);
        final CheckBox internalAudio = new CheckBox(this);
        internalAudio.setText("传输内部声音（两端1.8+，不录麦克风）");
        styleCheckBox(internalAudio);
        internalAudio.setChecked(Build.VERSION.SDK_INT >= 29);
        internalAudio.setEnabled(Build.VERSION.SDK_INT >= 29);
        optionsCard.addView(internalAudio);
        optionsCard.addView(text("声音仅支持多路及允许捕获的应用；可能包含其他应用的内部声音，开启时授权整个屏幕。系统会请求录音权限，但不采集麦克风。Android8/9、受保护内容或通话可能无声；关闭此项可兼容旧版。", 12, C_MUTED));
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
        LinearLayout actionsCard = sectionCard(page, "04  状态与操作");
        senderStatus = text(SenderService.status, 14);
        senderStatus.setOnClickListener(v -> showPerformanceReport(SenderService.status));
        actionsCard.addView(senderStatus);
        button(actionsCard, "授权并开始镜像", true, () -> {
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
        button(actionsCard, "打开双屏延迟测试画面", false, () -> {
            if (!SenderService.running) { Toast.makeText(this, "请先开始投屏", Toast.LENGTH_LONG).show(); return; }
            startActivity(new Intent(this, LatencyTestActivity.class));
        });
        button(actionsCard, "停止投屏", false, () -> stopService(new Intent(this, SenderService.class)));
        button(actionsCard, "返回模式选择", false, this::home);
        page.addView(text("授权框建议选择整个屏幕。每次重新投屏都需要重新授权；接收端允许前不会开始传视频。", 12, C_MUTED));
        code.setVisibility(View.GONE);
        withNetworkPermission(()->{if(mode==2)startSenderDiscovery();});
    }
    private String installationId(){
        String id=getPreferences(0).getString("discoveryId",null);if(id==null){id=UUID.randomUUID().toString();getPreferences(0).edit().putString("discoveryId",id).apply();}return id;
    }
    private void multiPage(){
        if(SenderService.running){Toast.makeText(this,"请先停止本机发送，再切换接收端",Toast.LENGTH_LONG).show();return;}
        setHomeBars(true);
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
                    Button item = new Button(MainActivity.this);
                    item.setAllCaps(false);
                    styleButton(item, false);
                    item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    item.setText(device.name + "\n" + device.host + ":" + device.port + " · 最多" + device.slots + "路 · 接收端确认");
                    item.setOnClickListener(v->{
                        discoveredHost=device.host;discoveredId=device.id;discoveredPort=device.port;
                        if(validIpv4(device.host))fillIpv4(device.host);
                        else {fillingIp=true;try{for(EditText part:ipParts)part.setText("");}finally{fillingIp=false;}}
                        selectedTarget.setText("已选："+device.name+" · "+device.host+":"+device.port+"\n设备名称不是身份认证，请核对接收端");
                        if(pipelineSelector.getSelectedItemPosition()!=1)pipelineSelector.setSelection(0);
                        code.setVisibility(View.GONE);
                    });
                    LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, -2);
                    itemParams.topMargin = dp(6);
                    deviceList.addView(item, itemParams);
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

    @Override
    public void onPause() {
        receiverResumed = false;
        if (mode == 1) {
            stopReceiverSession();
            receiverReport = "接收已暂停，返回应用后恢复";
            if (status != null) status.setText(receiverReport);
            if (receiveTitle != null) receiveTitle.setText("接收已暂停");
        }
        super.onPause();
    }
    @Override public void onStop() {
        super.onStop();
        stopSenderDiscovery();if(mode==3&&multiPanel!=null)multiPanel.pause();
    }
    @Override public void onResume() {
        super.onResume();
        receiverResumed = true;
        if(mode==3&&multiPanel!=null)multiPanel.resume();
        if(mode==2)startSenderDiscovery();
        if (mode == 1 && receiverWanted && preview != null && preview.getHolder().getSurface().isValid()) startReceiver(preview.getHolder().getSurface());
    }
    @Override public void onDestroy() { stopReceiver(); stopSenderDiscovery(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
