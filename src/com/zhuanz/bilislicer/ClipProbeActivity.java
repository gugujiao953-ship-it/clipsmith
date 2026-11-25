package com.zhuanz.bilislicer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

/**
 * 一个看不见的 Activity：只活 100 毫秒，作用是让本应用短暂成为「前台」，
 * 从而能读剪贴板（Android 10+ 只允许前台应用读）。
 * 读完立刻自己关掉，用户几乎察觉不到。
 */
public class ClipProbeActivity extends Activity {

    private boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (ClipService.instance != null) ClipService.instance.noteProbeAlive();
        // 窗口拿到焦点之前，系统仍认为「不在前台」，读剪贴板会被拒；
        // 正常由 onWindowFocusChanged 触发，这里做个兜底
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!done) doRead();
        }, 600);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !done) {
            // 焦点刚拿到，稍等一拍再读，避免系统状态没同步
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    this::doRead, 60);
        }
    }

    private int tries;

    /**
     * 刚拿到焦点时系统可能还没把「前台」状态同步过来，第一次读经常是 null，
     * 所以重试几次（总共约 1.2 秒），读到就立刻收工。
     */
    private void doRead() {
        if (done) return;
        String text = readClip();
        String url = text == null ? null : MainActivity.firstBiliUrlStatic(text);
        Log.i("BiliSlicer", "probe try=" + tries + " clip=" + (url == null ? "null" : url));
        if (url != null) {
            done = true;
            if (ClipService.instance != null) ClipService.instance.onUrlFound(url);
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        if (tries++ >= 5) {
            done = true;
            Log.i("BiliSlicer", "probe give up (clip empty or denied)");
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::doRead, 220);
    }

    private String readClip() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            CharSequence cs = cd.getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onBackPressed() {
    }
}
