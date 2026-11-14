package com.lanmirror.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowManager;

/** Visual test only. A third high-speed camera measures glass-to-glass delay. */
public final class LatencyTestActivity extends Activity {
    private TestView test;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        test = new TestView(); setContentView(test);
    }
    @Override public void onResume() { super.onResume(); test.active = true; Choreographer.getInstance().postFrameCallback(test); }
    @Override public void onPause() { test.active = false; Choreographer.getInstance().removeFrameCallback(test); super.onPause(); }
    private final class TestView extends View implements Choreographer.FrameCallback {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean active; long frameNs, count, intervalStart, intervalCount; double fps;
        TestView() { super(LatencyTestActivity.this); setOnClickListener(v -> finish()); }
        @Override public void doFrame(long time) {
            if (!active) return;
            frameNs = time; count++; intervalCount++;
            if (intervalStart == 0) intervalStart = time;
            if (time - intervalStart >= 1000000000L) { fps = intervalCount * 1e9 / (time - intervalStart); intervalCount = 0; intervalStart = time; }
            invalidate(); Choreographer.getInstance().postFrameCallback(this);
        }
        @Override public void onDraw(Canvas c) {
            boolean phase = ((frameNs / 500000000L) & 1) == 0;
            c.drawColor(phase ? Color.rgb(0, 110, 30) : Color.rgb(160, 20, 20));
            paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
            float width = getWidth(), height = getHeight();
            paint.setTextSize(Math.max(20, width / 18));
            c.drawText("双屏延迟测试 · 点击退出", width / 2, height * .15f, paint);
            paint.setTextSize(Math.max(30, width / 8));
            c.drawText(phase ? "GREEN" : "RED", width / 2, height * .36f, paint);
            paint.setTextSize(Math.max(24, width / 13));
            c.drawText("" + (frameNs / 1000000L), width / 2, height * .50f, paint);
            paint.setTextSize(Math.max(18, width / 23));
            c.drawText("本机帧 " + count + " · UI刷新 " + (int)Math.round(fps) + " fps", width / 2, height * .62f, paint);
            c.drawText("第三台设备以240fps同时拍摄两块屏幕", width / 2, height * .77f, paint);
            c.drawText("同一次变色帧差 × 4.17ms ≈ 双屏延迟", width / 2, height * .84f, paint);
            c.drawText("70ms约为240fps视频的17帧差", width / 2, height * .91f, paint);
        }
    }
}
