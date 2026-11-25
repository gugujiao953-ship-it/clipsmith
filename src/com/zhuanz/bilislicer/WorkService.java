package com.zhuanz.bilislicer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：只是为了让处理过程在切到别的 App（比如微信）之后不被系统杀掉。
 * 真正的活在 Jobs 的工作线程里干。
 */
public class WorkService extends Service {

    static final String CHANNEL = "bili_work";
    static final int NOTI_ID = 1001;

    public static void start(Context c) {
        if (c == null) return;
        Intent i = new Intent(c, WorkService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, WorkService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── 让通知跟着进度走 ──────────────────────────────
    private static volatile WorkService inst;
    private static volatile String lastText = "";
    private static volatile int lastPct = -1;
    private long lastRefresh;

    public static void setText(String t) {
        if (t == null || t.equals(lastText)) return;
        lastText = t;
        WorkService w = inst;
        if (w != null) w.refresh(false);
    }

    public static void setProgress(int pct) {
        if (pct == lastPct) return;
        lastPct = pct;
        WorkService w = inst;
        if (w != null) w.refresh(true);
    }

    /** @param force true=进度变化（限流 600ms），false=阶段变化（立即刷） */
    private void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (force && now - lastRefresh < 600) return;
        lastRefresh = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        String text = lastText.isEmpty() ? "下载 / 压缩 / 切分中" : lastText;
        if (text.length() > 56) text = text.substring(0, 56) + "…";
        b.setContentTitle(lastPct > 0 ? ("B站切片 · " + lastPct + "%") : "B站切片正在处理")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true);
        if (lastPct > 0) b.setProgress(100, lastPct, false);
        try {
            nm.notify(NOTI_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "视频处理", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            b.setContentIntent(android.app.PendingIntent.getActivity(this, 3, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE));
            Intent cancel = new Intent(this, CancelReceiver.class);
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消",
                    android.app.PendingIntent.getBroadcast(this, 5, cancel,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                    | android.app.PendingIntent.FLAG_IMMUTABLE));
            Notification n = b.setContentTitle("B站切片正在处理")
                    .setContentText("下载 / 压缩 / 切分中，完成后会存到片库")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true)
                    .build();
            startForeground(NOTI_ID, n);
            inst = this;
            refresh(false);
        }
        return START_NOT_STICKY;
    }

    /** 一批任务完成：单独发一条可清除的完成通知，方便切到别的应用时也知道 */
    public static void notifyDone(android.content.Context ctx, String title, int parts) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        "done", "处理完成", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("toLib", true);   // 点通知直接定位到片库
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, "done")
                    : new Notification.Builder(ctx);
            String t = title == null ? "视频" : title;
            if (t.length() > 24) t = t.substring(0, 24) + "…";
            b.setContentTitle("✓ 已存到片库")
                    .setContentText(t + " · " + parts + " 个文件")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .setContentIntent(android.app.PendingIntent.getActivity(ctx, 4, open,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                    | android.app.PendingIntent.FLAG_IMMUTABLE));
            nm.notify(9001, b.build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        inst = null;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
