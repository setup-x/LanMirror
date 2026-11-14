package com.lanmirror.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.projection.*;
import android.os.*;

import com.lanmirror.audio.api.InternalAudio;
import com.lanmirror.core.api.ApprovalProtocol;
import com.lanmirror.core.api.StreamProfile;
import com.lanmirror.sender.api.SenderSession;

/**
 * 前台通知与授权属于应用；发送会话只借用投影。
 */
public final class SenderService extends Service {
    public static final String STOP = "com.lanmirror.app.STOP";
    public static volatile String status = "未发送";
    public static volatile boolean running;
    private static SenderService active;
    private boolean stopping, destroyed;
    private MediaProjection projection;
    private SenderSession session;
    private long requestedSize;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { requestStop(); }
        @Override public void onCapturedContentResize(int width, int height) {
            if (width <= 0 || height <= 0) return;
            requestedSize = ((long) width << 32) | (height & 0xffffffffL);
            SenderSession current = session;
            if (current != null) current.updateSourceSize(width, height);
        }
    };
    @Override public IBinder onBind(Intent intent) { return null; }
    private void requestStop() {
        stopping = true;
        SenderSession current = session;
        if (current != null) current.stop();
        else finishStop();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { requestStop(); return START_NOT_STICKY;
        }
        if (running || stopping) return START_NOT_STICKY;
        synchronized (SenderService.class) { active = this; running = true; }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("casting", "正在镜像投屏", NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getService(this, 1, new Intent(this, SenderService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "casting")
                .setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("局域镜像 · 1080p60 高码率")
                .setContentText("点击停止可随时结束投屏").setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止投屏", pi).build()).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(1, notification);
            Intent consent = intent.getParcelableExtra("consent");
            if (consent == null) throw new IllegalArgumentException("缺少屏幕采集授权");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result", Activity.RESULT_CANCELED), consent);
            projection.registerCallback(projectionCallback, handler);
            SenderSession.Config config = new SenderSession.Config(
                    intent.getStringExtra("host"), intent.getStringExtra("code"),
                    intent.getIntExtra("profile", StreamProfile.DEFAULT), intent.getIntExtra("pipeline", 2),
                    intent.getIntExtra("port", ApprovalProtocol.PORT),
                    intent.getBooleanExtra("directTransport", false), intent.getBooleanExtra("burstEncoder", false),
                    intent.getBooleanExtra("audio", false) ? InternalAudio.sender(projection) : null);
            session = new SenderSession(this, projection, config, new SenderSession.Listener() {
                @Override
                public void onStatus(String text) {
                    publish(text);
                }

                @Override
                public void onStopped() {
                    handler.post(() -> {
                        session = null;
                        finishStop();
                    });
                }
            });
            if (requestedSize != 0)
                session.updateSourceSize((int) (requestedSize >>> 32), (int) requestedSize);
            session.start();
        } catch (Exception e) { publish("启动失败：" + message(e)); requestStop(); }
        return START_NOT_STICKY;
    }

    private void finishStop() {
        stopping = true;
        // 正常停止等待发送工作线程释放 native 资源，之后才归还宿主授权。
        MediaProjection p = projection; projection = null;
        if (p != null) { p.unregisterCallback(projectionCallback); p.stop(); }
        synchronized (SenderService.class) {
            if (active == this) {
                if (status.startsWith("正在") || status.startsWith("连接") || status.startsWith("流畅模式")) status = "投屏已停止";
                running = false; active = null;
            }
        }
        if (!destroyed) stopSelf();
    }

    private void publish(String message) {
        synchronized (SenderService.class) {
            if (active == this) status = message;
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        requestStop();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
