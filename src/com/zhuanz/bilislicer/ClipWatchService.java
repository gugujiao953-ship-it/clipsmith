package com.zhuanz.bilislicer;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 只干一件小事：监听「复制 / 复制链接」按钮被点击。
 *
 * 悬浮球、面板、读剪贴板全部搬到了 {@link ClipService}（普通前台服务），
 * 因为无障碍服务在国产 ROM 上随时会被系统关掉，不该让它承载主逻辑。
 */
public class ClipWatchService extends AccessibilityService {

    private static final String TAG = "BiliSlicer";

    /** 服务是否连着。国产 ROM 会随时关掉它，界面靠这个状态提示用户 */
    public static volatile boolean connected = false;

    /** 系统里这个无障碍服务是否仍被勾选 */
    public static boolean enabledInSettings(Context c) {
        try {
            String key = c.getPackageName() + "/" + ClipWatchService.class.getName();
            String flat = android.provider.Settings.Secure.getString(
                    c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return flat != null && flat.toLowerCase().contains(key.toLowerCase());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        Log.i(TAG, "ClipWatchService connected");
        ClipService.wake(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        String txt = "";
        try {
            CharSequence cs = event.getText() != null && !event.getText().isEmpty()
                    ? event.getText().get(0) : null;
            if (cs != null) txt = cs.toString();
            if (txt.isEmpty() && event.getContentDescription() != null) {
                txt = event.getContentDescription().toString();
            }
        } catch (Exception ignored) {
        }
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        boolean target = pkg.contains("douyin") || pkg.contains("aweme")
                || pkg.contains("bilibili") || pkg.contains("kuaishou") || pkg.contains("snssdk");
        if (target && !txt.isEmpty()) {
            Log.i(TAG, "click in " + pkg + ": " + txt);
        }
        String low = txt.toLowerCase();
        boolean copyish = txt.contains("复制") || txt.contains("拷贝") || low.contains("copy")
                || txt.contains("链接");
        if (!copyish) return;
        if (!getSharedPreferences("bili", MODE_PRIVATE).getBoolean("attach", false)) return;

        ClipService s = ClipService.instance;
        if (s != null) s.handleCopyClick();
        else ClipService.wake(this);
    }

    @Override
    public void onInterrupt() {
        connected = false;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }
}
