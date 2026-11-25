package com.zhuanz.bilislicer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.os.Build;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 自动挂载：常驻的无障碍服务。
 *
 * 为什么必须用无障碍：Android 10 起，没有前台焦点的 App 读不到剪贴板。
 * 系统对「已启用的无障碍服务」有豁免（部分 ROM 也放开），所以这条路能拿到复制的内容。
 *
 * 拿到链接后不切回主界面，直接在屏幕上弹一个小卡片，问要不要处理。
 */
public class ClipService extends Service {

    private static final String TAG = "BiliSlicer";
    private static final long TICK = 700;

    private final Handler h = new Handler(Looper.getMainLooper());
    private ClipboardManager cm;
    private WindowManager wm;
    private View card;
    private View panel;
    private int panelGen;      // 面板代次：旧面板的定时器不能关掉新面板
    private View bubble;          // 常驻小悬浮球：点一下就读剪贴板
    private boolean bubbleShown;
    private String lastSig = "";
    private long lastSigAt = 0;   // 上次记录签名的时间（去重窗口用）
    private boolean loggedDenied;
    public static volatile ClipService instance;
    /** 本应用任意界面是否在前台（主界面/选集/记录） */
    public static volatile boolean appVisible;
    private long probeAt;      // 探针 Activity 的启动时刻，用来判断系统是否拦了

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                poll();
                syncBubble();
            } catch (Exception e) {
                Log.w(TAG, "poll fail", e);
            }
            h.postDelayed(this, TICK);
        }
    };

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> {
        try {
            poll();
        } catch (Exception ignored) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        goForeground();
        cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (cm != null) cm.addPrimaryClipChangedListener(clipListener);
        h.postDelayed(tick, TICK);
        Log.i(TAG, "ClipService started");
    }

    private void goForeground() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "clipw";
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(ch, "自动挂载",
                        NotificationManager.IMPORTANCE_MIN);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
                b = new Notification.Builder(this, ch);
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("自动挂载运行中")
                    .setContentText("复制链接后点一下「片」小球即可处理")
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_MIN);
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            b.setContentIntent(PendingIntent.getActivity(this, 9, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            startForeground(8801, b.build());
        } catch (Exception e) {
            Log.w(TAG, "foreground fail", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (getSharedPreferences("bili", MODE_PRIVATE).getBoolean("attach", false)) {
            startForeground(8801, buildSmall());
            syncBubble();
        } else {
            hideBubble();
            stopSelf();
        }
        return START_STICKY;
    }

    private Notification buildSmall() {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, "clipw")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("自动挂载运行中")
                    .setPriority(Notification.PRIORITY_MIN)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("自动挂载运行中")
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    /** 无障碍那边检测到「复制」被点时调这里 */
    public void handleCopyClick() {
        // 点击「复制」的瞬间系统还没把内容写进剪贴板；
        // 这时候立刻抢焦点去读，可能把这次复制打断，导致永远读到上一次的内容。
        Log.i(TAG, "copy click -> read(after 450ms)");
        h.postDelayed(this::readClipByFocus, 450);
    }

    public static void wake(Context c) {
        try {
            Intent it = new Intent(c, ClipService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it);
            else c.startService(it);
        } catch (Exception ignored) {
        }
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        if (cm != null) cm.removePrimaryClipChangedListener(clipListener);
        dismiss();
        dismissPanel();
        hideBubble();
        instance = null;
        super.onDestroy();
    }

    /**
     * 用「可聚焦的悬浮窗」抢焦点来读剪贴板 —— 全程不启动 Activity，
     * 所以不会把应用带回前台。
     */
    private void readClipByFocus() {
        if (wm == null) wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            showPastePanel();
            return;
        }
        final View probe = new View(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                2, 2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0,                      // 可聚焦
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        try {
            wm.addView(probe, lp);
        } catch (Exception e) {
            Log.w(TAG, "focus probe fail", e);
            showPastePanel();
            return;
        }
        final int[] tries = {0};
        final Runnable[] read = new Runnable[1];
        read[0] = () -> {
            String got = readClipNow();
            String url = got == null ? null : MainActivity.firstBiliUrlStatic(got);
            Log.i(TAG, "focus try=" + tries[0] + " focus=" + probe.hasWindowFocus()
                    + " url=" + url);
            if (url != null) {
                try {
                    wm.removeView(probe);
                } catch (Exception ignored) {
                }
                resolveThenPanel(url);
                return;
            }
            if (tries[0]++ < 4) {
                h.postDelayed(read[0], 200);
            } else {
                try {
                    wm.removeView(probe);
                } catch (Exception ignored) {
                }
                showPastePanel();
            }
        };
        h.postDelayed(read[0], 160);
        // 兜底：2 秒后无论结果如何都把探针窗口摘掉
        h.postDelayed(() -> {
            try {
                if (probe.getParent() != null) wm.removeView(probe);
            } catch (Exception ignored) {
            }
        }, 2000);
    }

    /** 探针 Activity 真的起来了（系统允许后台弹界面） */
    public void noteProbeAlive() {
        probeAt = System.currentTimeMillis();
    }

    /**
     * 剪贴板去重。不能只比「链接 + 时间戳」：
     * 小米等 ROM 重新复制同一段文字时剪贴板时间戳不会变，
     * 只比 sig 会让同一条链接永久跳过，看起来就是「复制了没反应」。
     * 所以只在 30 秒窗口内算重复。
     */
    private boolean isDuplicate(String sig) {
        return sig.equals(lastSig) && System.currentTimeMillis() - lastSigAt < 30_000L;
    }

    private void rememberSig(String sig) {
        lastSig = sig;
        lastSigAt = System.currentTimeMillis();
    }

    /** 探针 Activity 读到链接后回调这里 */
    public void onUrlFound(String url) {
        if (url == null || url.isEmpty()) return;
        if (MainActivity.foreground) return;
        // 用剪贴板自身的时间戳去重：同一条链接重新复制（时间戳变）也要能再次处理
        long ts = 0;
        try {
            ClipDescription d = cm == null ? null : cm.getPrimaryClipDescription();
            if (d != null) ts = d.getTimestamp();
        } catch (Exception ignored) {
        }
        String sig = url + "#" + ts;
        if (isDuplicate(sig)) {
            Log.i(TAG, "same clip within 30s, skip: " + url);
            return;
        }
        rememberSig(sig);
        Log.i(TAG, "resolve for " + url);
        resolveThenPanel(url);
    }

    /** 让看不见的 Activity 闪一下去读剪贴板（唯一能绕开限制的办法） */
    public void probeViaActivity() {
        try {
            android.content.Intent it = new android.content.Intent(this, ClipProbeActivity.class);
            // 单独一个任务栈：这样它结束之后回到用户原来的应用，而不是把本应用带到前台
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(it);
        } catch (Exception e) {
            Log.w(TAG, "probe activity fail", e);
        }
    }

    public void readWithFocus(Runnable whenDone) {
        if (wm == null) wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            whenDone.run();
            return;
        }
        final View probe = new View(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                8, 8,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0,   // 不加 NOT_FOCUSABLE，目的就是抢焦点
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(probe, lp);
        } catch (Exception e) {
            Log.w(TAG, "probe add fail", e);
            whenDone.run();
            return;
        }
        h.postDelayed(() -> {
            String got = null;
            try {
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData cd = cm.getPrimaryClip();
                    if (cd != null && cd.getItemCount() > 0) {
                        CharSequence cs = cd.getItemAt(0).coerceToText(this);
                        got = cs == null ? null : cs.toString();
                    }
                }
            } catch (Exception ignored) {
            }
            Log.i(TAG, "focus-read hasFocus=" + probe.hasWindowFocus()
                    + " result=" + (got == null ? "null" : got.substring(0, Math.min(60, got.length()))));
            try {
                wm.removeView(probe);
            } catch (Exception ignored) {
            }
            if (got != null) {
                String url = MainActivity.firstBiliUrlStatic(got);
                if (url != null) showCard(url);
            }
            whenDone.run();
        }, 350);
    }

    private void poll() {
        boolean attach = getSharedPreferences("bili", MODE_PRIVATE).getBoolean("attach", false);
        if (!attach) return;
        if (cm == null) cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        boolean has = cm != null && cm.hasPrimaryClip();
        if (!has) {
            android.util.Log.i(TAG, "poll: no clip (cm=" + (cm != null) + ")");
            return;
        }
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return;
        CharSequence cs = cd.getItemAt(0).coerceToText(this);
        if (cs == null) return;

        String url = MainActivity.firstBiliUrlStatic(cs.toString());
        if (url == null) {
            String t = cs.toString();
            android.util.Log.i(TAG, "poll: text without url [" + (t.length() > 40 ? t.substring(0, 40) : t) + "]");
            return;
        }
        if (url.equals(getSharedPreferences("bili", MODE_PRIVATE).getString("selfCopiedUrl", null))) {
            getSharedPreferences("bili", MODE_PRIVATE).edit().remove("selfCopiedUrl").apply();
            return;
        }

        long ts = 0;
        ClipDescription desc = cm.getPrimaryClipDescription();
        if (desc != null) ts = desc.getTimestamp();
        String sig = url + "#" + ts;
        if (isDuplicate(sig)) return;
        rememberSig(sig);

        Log.i(TAG, "clip hit: " + url);
        if (MainActivity.foreground) {
            // 用户就在我们的界面里，主界面自己会处理
            return;
        }
        if (queueIfBusy(url)) return;
        showCard(url);
    }

    /** 系统不让后台弹界面时用：一个「点一下处理」的小卡片 */
    private void showAskCard() {
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        if (MainActivity.foreground) return;
        dismiss();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF218191C);
        bg.setCornerRadius(dp(999));
        box.setBackground(bg);
        box.setPadding(dp(14), dp(9), dp(8), dp(9));

        TextView t = new TextView(this);
        t.setText("识别到复制");
        t.setTextSize(12);
        t.setTextColor(Color.WHITE);
        box.addView(t);

        TextView go = new TextView(this);
        go.setText("处理");
        go.setTextSize(12);
        go.setTextColor(Color.WHITE);
        go.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFFFB7299);
        gd.setCornerRadius(dp(999));
        go.setBackground(gd);
        go.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gp.leftMargin = dp(10);
        box.addView(go, gp);
        go.setOnClickListener(v -> {
            dismiss();
            showPastePanel();
        });

        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextSize(13);
        x.setTextColor(0xFFB0B4BA);
        x.setPadding(dp(12), dp(6), dp(6), dp(6));
        x.setOnClickListener(v -> dismiss());
        box.addView(x);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(140);
        try {
            wm.addView(box, lp);
            card = box;
            h.postDelayed(this::dismiss, 6000);
        } catch (Exception e) {
            Log.w(TAG, "ask card fail", e);
        }
    }

    /**
     * 打开主界面并让它立刻读剪贴板。
     * 国产 ROM 可能拦后台启动 Activity，所以 1 秒后如果还没到前台，就补一条通知兜底
     * （点通知属于用户主动操作，系统一定放行）。
     */
    private void openMainWithClip() {
        try {
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            it.putExtra("readClip", true);
            startActivity(it);
        } catch (Exception e) {
            Log.w(TAG, "open main fail", e);
        }
        h.postDelayed(() -> {
            if (!MainActivity.foreground) notifyFallback();
        }, 900);
    }

    /**
     * 处理面板：整块 UI 都在悬浮窗里，应用不进前台。
     * 加窗口时故意「可聚焦」，借此让系统把本应用当成有焦点，从而允许读剪贴板。
     */
    /**
     * 处理面板：整块 UI 都在悬浮窗里，应用不进前台。
     * url 由透明探针页读出来传进来。
     */
    /** 后台解析链接，拿到分P/合集信息后再出面板，全程不打开应用 */
    /** 忙的时候不打扰用户：直接排到队尾 */
    private boolean queueIfBusy(String url) {
        if (!Jobs.isRunning()) return false;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("bili", MODE_PRIVATE);
            double limit = sp.getFloat("limit", 9.6f);
            int parts = sp.getInt("parts", 0);
            Jobs.enqueue(url, limit, parts, false);
            Log.i(TAG, "busy -> queued: " + url);
            android.widget.Toast.makeText(getApplicationContext(),
                    "已排队，前面处理完接着做", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "queue fail", e);
            return false;
        }
    }

    private void resolveThenPanel(final String url) {
        if (queueIfBusy(url)) return;
        showInfoPanel("正在解析…", url, null);
        new Thread(() -> {
            String line1;
            String line2;
            try {
                Source.Media m = Source.resolve(getApplicationContext(), url, true);
                String title = m.title == null ? "" : m.title.trim();
                if (title.length() > 30) title = title.substring(0, 30) + "…";
                if (m.season != null && m.season.size() > 1) {
                    line1 = "合集：" + (m.seasonTitle.isEmpty() ? title : m.seasonTitle);
                    line2 = "共 " + m.season.size() + " 集";
                } else if (m.pageCount > 1) {
                    line1 = title;
                    line2 = "多分P：共 " + m.pageCount + " 个，当前 P" + m.page;
                } else {
                    line1 = title;
                    line2 = "单个视频，可直接下载";
                }
                final String f1 = line1, f2 = line2;
                h.post(() -> showOptions(url, m, f1, f2));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                h.post(() -> showInfoPanel("解析失败：" + msg, url, () -> enqueueInBackground(url)));
            }
        }, "clip-resolve").start();
    }

    /** 只有一行字的面板（解析中 / 解析失败） */
    private void showInfoPanel(String text, String url, Runnable fallbackAction) {
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        dismissPanel();
        dismiss();

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF218191C);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);
        box.setPadding(dp(16), dp(14), dp(16), dp(13));

        TextView head = new TextView(this);
        head.setText("识别到链接");
        head.setTextSize(13);
        head.setTextColor(0xFFFF7AA2);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(head);

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(12);
        body.setTextColor(Color.WHITE);
        body.setPadding(0, dp(6), 0, dp(10));
        box.addView(body);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(acts);
        if (fallbackAction != null) {
            addPanelBtn(acts, "仍然下载", true, () -> {
                dismissPanel();
                fallbackAction.run();
            });
        }
        addPanelBtn(acts, "重新读", false, () -> {
            dismissPanel();
            readClipByFocus();
        });
        addPanelBtn(acts, "取消", false, this::dismissPanel);
        attachPanel(box, true);
    }

    /** 真选项面板：全部 / 只下这个 / 取消，全部在悬浮窗里完成 */
    private void showOptions(final String url, Source.Media m, String line1, String line2) {
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        dismissPanel();
        dismiss();

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF218191C);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);
        box.setPadding(dp(16), dp(14), dp(16), dp(13));

        TextView head = new TextView(this);
        head.setText("识别到链接");
        head.setTextSize(13);
        head.setTextColor(0xFFFF7AA2);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(head);

        TextView t1 = new TextView(this);
        t1.setText(line1);
        t1.setTextSize(13);
        t1.setTextColor(Color.WHITE);
        t1.setMaxLines(2);
        t1.setPadding(0, dp(6), 0, 0);
        box.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(line2);
        t2.setTextSize(11);
        t2.setTextColor(0xFFB0B4BA);
        t2.setPadding(0, dp(3), 0, dp(10));
        box.addView(t2);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(acts);

        final boolean multiSeason = m.season != null && m.season.size() > 1;
        final boolean multiPage = m.pageCount > 1;

        if (multiSeason) {
            addPanelBtn(acts, "全部 " + m.season.size() + " 集", true, () -> {
                dismissPanel();
                for (BiliApi.Episode e : m.season) {
                    enqueueInBackground("https://www.bilibili.com/video/" + e.bvid);
                }
            });
            addPanelBtn(acts, "只下这集", false, () -> {
                dismissPanel();
                enqueueInBackground(url);
            });
        } else if (multiPage) {
            addPanelBtn(acts, "全部 " + m.pageCount + " 个P", true, () -> {
                dismissPanel();
                for (int p = 1; p <= m.pageCount; p++) {
                    enqueueInBackground(withPage(url, p));
                }
            });
            addPanelBtn(acts, "只下 P" + m.page, false, () -> {
                dismissPanel();
                enqueueInBackground(url);
            });
        } else {
            addPanelBtn(acts, "下载", true, () -> {
                dismissPanel();
                enqueueInBackground(url);
            });
        }
        addPanelBtn(acts, "取消", false, this::dismissPanel);
        attachPanel(box, false);
    }

    /**
     * 手动粘贴面板：系统不让后台读剪贴板时用。
     * 整块是可聚焦窗口，长按输入框就能粘贴，全程不离开当前应用。
     */
    private void showPastePanel() {
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        dismissPanel();
        dismiss();

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF218191C);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);
        box.setPadding(dp(16), dp(14), dp(16), dp(13));

        TextView head = new TextView(this);
        head.setText("长按输入框粘贴链接");
        head.setTextSize(13);
        head.setTextColor(0xFFFF7AA2);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(head);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("粘贴 B站 / 抖音链接");
        input.setTextSize(12);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF9AA0A6);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(0x33FFFFFF);
        ib.setCornerRadius(dp(8));
        input.setBackground(ib);
        input.setPadding(dp(8), dp(6), dp(8), dp(6));
        input.setSingleLine(true);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(8);
        ip.bottomMargin = dp(10);
        box.addView(input, ip);

        final TextView tip = new TextView(this);
        tip.setTextSize(11);
        tip.setTextColor(0xFFFF8A8A);
        tip.setVisibility(View.GONE);
        box.addView(tip);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(acts);

        String lastUrl = getSharedPreferences("bili", MODE_PRIVATE).getString("lastUrl", "");
        if (lastUrl != null && !lastUrl.isEmpty()) {
            addPanelBtn(acts, "上次的", false, () -> {
                input.setText(lastUrl);
                input.setSelection(input.getText().length());
            });
        }
        addPanelBtn(acts, "开始", true, () -> {
            String txt = input.getText().toString().trim();
            String u = MainActivity.firstBiliUrlStatic(txt);
            if (u == null) {
                tip.setText("没识别到链接，检查一下再试");
                tip.setVisibility(View.VISIBLE);
                return;
            }
            dismissPanel();
            resolveThenPanel(u);
        });
        addPanelBtn(acts, "取消", false, this::dismissPanel);

        // 注意：这里必须可聚焦，否则输入框接不到键盘
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(130);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try {
            wm.addView(box, lp);
            panel = box;
            input.requestFocus();
            Log.i(TAG, "paste panel shown");
        } catch (Exception e) {
            Log.w(TAG, "paste panel fail", e);
        }
    }

    /** 把面板挂到屏幕上 */
    private void attachPanel(View box, boolean autoDismiss) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(130);
        try {
            wm.addView(box, lp);
            panel = box;
            Log.i(TAG, "panel shown");
            final int gen = ++panelGen;
            h.postDelayed(() -> {
                if (gen == panelGen) dismissPanel();   // 只关自己那一代
            }, autoDismiss ? 15000 : 40000);
        } catch (Exception e) {
            Log.w(TAG, "panel add fail", e);
        }
    }

    /** 把 "?p=3" 换成分P 链接 */
    private static String withPage(String url, int p) {
        String u = url.replaceAll("[?&]p=\\d+", "");
        return u + (u.contains("?") ? "&" : "?") + "p=" + p;
    }

    private void addPanelBtn(LinearLayout parent, String text, boolean primary, Runnable click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(primary ? Color.WHITE : 0xFFD0D3D8);
        GradientDrawable d = new GradientDrawable();
        d.setColor(primary ? 0xFFFB7299 : 0x33FFFFFF);
        d.setCornerRadius(dp(999));
        t.setBackground(d);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        parent.addView(t, lp);
        t.setOnClickListener(v -> click.run());
    }

    private void dismissPanel() {
        if (panel != null && wm != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
        }
    }

    /** 读一次剪贴板（失败返回 null） */
    private String readClipNow() {
        try {
            if (cm == null) cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            CharSequence cs = cd.getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 后台直接开跑，不打开界面 */
    private void enqueueInBackground(String url) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("bili", MODE_PRIVATE);
            double limit = sp.getFloat("limit", 9.6f);
            int parts = sp.getInt("parts", 0);
            Jobs.init(getApplicationContext());
            // 悬浮窗里点「下载」就是明确的意图，不要再去撞「已处理过」的询问
            Jobs.enqueue(url, limit, parts, true);
            Log.i(TAG, "enqueued in background: " + url);
            try {
                android.widget.Toast.makeText(getApplicationContext(),
                        "已加入下载队列", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "enqueue fail", e);
        }
    }

    private void openMainWithUrl(String url) {
        try {
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            it.setAction(Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(Intent.EXTRA_TEXT, url);
            startActivity(it);
        } catch (Exception e) {
            Log.w(TAG, "open main(url) fail", e);
        }
    }

    /** 系统拦了后台启动时的兜底：发一条通知，点一下就进主界面并自动处理 */
    private void notifyFallback() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "clip";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel c = new android.app.NotificationChannel(
                        ch, "剪贴板助手", android.app.NotificationManager.IMPORTANCE_HIGH);
                c.setDescription("复制链接后的快捷处理");
                nm.createNotificationChannel(c);
            }
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            it.putExtra("readClip", true);
            int flags = android.os.Build.VERSION.SDK_INT >= 23
                    ? android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | android.app.PendingIntent.FLAG_IMMUTABLE
                    : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            android.app.PendingIntent pi =
                    android.app.PendingIntent.getActivity(this, 7, it, flags);
            String title = "点一下处理刚复制的链接";
            String body = "系统不允许后台直接打开界面，点这里继续";
            android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(this, ch)
                    : new android.app.Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setPriority(android.app.Notification.PRIORITY_HIGH);
            nm.notify(7001, b.build());
            Log.i(TAG, "fallback notification posted");
        } catch (Exception e) {
            Log.w(TAG, "notify fail", e);
        }
    }

    /** 开关变化时显示/隐藏悬浮球 */
    private void syncBubble() {
        boolean want = getSharedPreferences("bili", MODE_PRIVATE).getBoolean("attach", false)
                && !appVisible;   // 在本应用任何界面都收起，别挡界面
        if (want && !bubbleShown) showBubble();
        else if (!want && bubbleShown) hideBubble();
    }

    private void showBubble() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "没有悬浮窗权限，悬浮球显示不了");
            return;
        }
        if (wm == null) wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        TextView b = new TextView(this);
        b.setText("片");
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xF2FB7299);
        d.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(d);
        b.setAlpha(0.92f);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(46), dp(46),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        android.content.SharedPreferences sp = getSharedPreferences("bili", MODE_PRIVATE);
        lp.x = sp.getInt("bubbleX", dp(10));
        lp.y = sp.getInt("bubbleY", dp(420));

        b.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            lp.x = Math.max(0, startX + dx);
                            lp.y = Math.max(0, startY + dy);
                            try {
                                wm.updateViewLayout(bubble, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (moved) {
                            getSharedPreferences("bili", MODE_PRIVATE).edit()
                                    .putInt("bubbleX", lp.x).putInt("bubbleY", lp.y).apply();
                        }
                        if (!moved) {
                            Log.i(TAG, "bubble tapped");
                            readClipByFocus();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(b, lp);
            bubble = b;
            bubbleShown = true;
            Log.i(TAG, "bubble shown");
        } catch (Exception e) {
            Log.w(TAG, "bubble add fail", e);
        }
    }

    private void hideBubble() {
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {
            }
        }
        bubble = null;
        bubbleShown = false;
    }

    // ── 悬浮小卡片 ─────────────────────────────────────

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void showCard(final String url) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            if (!loggedDenied) {
                loggedDenied = true;
                Log.w(TAG, "没有悬浮窗权限，无法弹卡片");
            }
            // 退一步：直接把主界面拉起来处理
            try {
                Intent it = new Intent(this, MainActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                it.setAction(Intent.ACTION_SEND);
                it.setType("text/plain");
                it.putExtra(Intent.EXTRA_TEXT, url);
                startActivity(it);
            } catch (Exception e) {
                Log.w(TAG, "startActivity fail", e);
            }
            return;
        }
        dismiss();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF218191C);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        box.setPadding(dp(16), dp(14), dp(16), dp(12));

        TextView t1 = new TextView(this);
        t1.setText("识别到链接");
        t1.setTextSize(13);
        t1.setTextColor(0xFFFF7AA2);
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(t1);

        TextView t2 = new TextView(this);
        String show = url.length() > 46 ? url.substring(0, 46) + "…" : url;
        t2.setText(show);
        t2.setTextSize(12);
        t2.setTextColor(Color.WHITE);
        t2.setPadding(0, dp(4), 0, dp(10));
        box.addView(t2);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);

        TextView go = new TextView(this);
        go.setText("处理");
        go.setTextSize(13);
        go.setTextColor(Color.WHITE);
        go.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFFFB7299);
        gd.setCornerRadius(dp(999));
        go.setBackground(gd);
        go.setPadding(dp(18), dp(8), dp(18), dp(8));
        go.setOnClickListener(v -> {
            dismiss();
            try {
                Intent it = new Intent(this, MainActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                it.setAction(Intent.ACTION_SEND);
                it.setType("text/plain");
                it.putExtra(Intent.EXTRA_TEXT, url);
                startActivity(it);
            } catch (Exception ignored) {
            }
        });

        TextView no = new TextView(this);
        no.setText("忽略");
        no.setTextSize(13);
        no.setTextColor(0xFFB0B4BA);
        no.setGravity(Gravity.CENTER);
        GradientDrawable nd = new GradientDrawable();
        nd.setColor(0x33FFFFFF);
        nd.setCornerRadius(dp(999));
        no.setBackground(nd);
        no.setPadding(dp(18), dp(8), dp(18), dp(8));
        no.setOnClickListener(v -> dismiss());

        acts.addView(go);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gp.leftMargin = dp(10);
        acts.addView(no, gp);
        box.addView(acts);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(120);
        try {
            wm.addView(box, lp);
            card = box;
        } catch (Exception e) {
            Log.w(TAG, "addView fail", e);
        }

        // 8 秒自动收掉，别挡着人家
        h.postDelayed(this::dismiss, 8000);
    }

    private void dismiss() {
        if (card != null && wm != null) {
            try {
                wm.removeView(card);
            } catch (Exception ignored) {
            }
            card = null;
        }
    }
}
