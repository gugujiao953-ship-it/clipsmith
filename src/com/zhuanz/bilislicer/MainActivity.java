package com.zhuanz.bilislicer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements Jobs.Listener {

    // ── 设计规范 ──────────────────────────────────────────────
    private static final int PINK = 0xFFFB7299;
    private static final int PINK_D = 0xFFE85C86;
    private static final int BLUE = 0xFF00AEEC;
    private static final int WECHAT = 0xFF07C160;
    private static final int INK = 0xFF18191C;
    private static final int SUB = 0xFF61666D;
    private static final int GRAY = 0xFF9499A0;
    private static final int LINE = 0xFFE3E5E8;
    private static final int BG = 0xFFF1F2F4;
    private static final int CHIP = 0xFFEDEFF2;

    // ── 控件 ──────────────────────────────────────────────────
    private EditText input;
    private EditText searchBox;
    private TextView searchClear;
    private LinearLayout searchRow;
    private TextView statusLine;
    private ProgressBar bar;
    private LinearLayout progressWrap;
    private LinearLayout libraryBox;
    private ScrollView svRoot;      // 片库外层滚动容器，用来恢复滚动位置
    private final java.util.HashSet<String> expanded = new java.util.HashSet<>();  // 展开的片库分组
    private boolean scrollTopNext = false;
    private boolean libLoaded = false;
    private TextView accBanner;      // 无障碍失效时的提示条
    private LinearLayout libHeadView;     // 片库标题行，通知跳转时滚到这里   // 第一次读完片库了没（用来区分「加载中」和「真的没片子」）   // 下一次渲染后回到顶部（刚下完想看到新的）
    private TextView libStat;
    private TextView chipMonitor;
    private TextView chipSort;
    private TextView segV, segH;
    private TextView goBtn;

    // ── 状态 ──────────────────────────────────────────────────
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String lastLog = "";
    private int lastPct = 0;
    private int pendingCount = 0;
    private boolean resumed = false;
    private TextView chipAttach;
    private TextView chipRetry;    // 失败后出现，一点重跑
    private TextView ruleLine;     // 当前规则摘要
    private TextView chipStop;     // 处理中显示，点了取消
    private static final int REQ_PICK_VIDEO = 4021;
    private boolean picking;                              // 片库多选模式
    private final java.util.LinkedHashSet<Long> picked = new java.util.LinkedHashSet<>();
    private LinearLayout pickBar;
    private TextView chipPick;      // 片库「选择」开关
    private TextView chipUnsent;    // 只看未发微信的
    private boolean onlyUnsent;
    private TextView pickInfo;
    private List<Library.Group> lastGroups = new ArrayList<>();
    /** 主界面是否在前台（自动挂载服务据此决定要不要弹卡片） */
    public static volatile boolean foreground;

    /** 给无障碍服务用的静态版本 */
    public static String firstBiliUrlStatic(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = URL_PAT.matcher(text);
        while (m.find()) {
            if (isBili(m.group())) return m.group();
        }
        return null;
    }
    private boolean searchOpen = false;
    private String lastHandled = "";
    private String orient = "v";
    private String sort = "new";
    private String query = "";
    private SharedPreferences prefs;

    private double limitMb = 9.6;
    private int wantParts = 0;
    private double partMin = 0;      // 每段时长（分钟），0=不限制
    private int maxKbps = 900;       // 码率上限
    private boolean hq = true;       // 480P

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        Jobs.init(this);
        prefs = getSharedPreferences("bili", MODE_PRIVATE);
        lastHandled = prefs.getString("lastUrl", "");
        orient = prefs.getString("orient", "v");
        sort = prefs.getString("sort", "new");
        limitMb = prefs.getFloat("limit", 9.6f);
        wantParts = prefs.getInt("parts", 0);
        partMin = prefs.getFloat("partMin", 0f);
        maxKbps = prefs.getInt("maxKbps", 900);
        hq = prefs.getBoolean("hq", true);

        setContentView(buildUi());
        Thumbs.prune(this);

        updateMonitorChip();
        updateSortChip();
        setOrient(orient);

        handleIntent(getIntent());
        reloadLibrary();
        cleanShareCache();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        foreground = true;
        ClipService.appVisible = true;
        Jobs.setListener(this);
        reloadLibrary();
        syncBusy();
        restoreClipSig();
        cleanShareCache();
        if (prefs.getBoolean("monitor", false)) {
            ui.removeCallbacks(clipTick);
            ui.postDelayed(clipTick, 500);
        }
        updateAttachChip();
        refreshAccBanner();
        // 上次失败的链接存在 prefs 里，重启后重试按钮还在
        if (chipRetry != null && Jobs.lastFailedUrl == null) {
            String last = prefs.getString("lastFailUrl", null);
            if (last != null && !last.isEmpty()) {
                Jobs.lastFailedUrl = last;
                chipRetry.setVisibility(View.VISIBLE);
            }
        }
        refreshRuleLine();
        // 后台（比如选集页）期间监听被清空过，回来补一次队列状态
        onQueueChanged(Jobs.pending());
        // 自动挂载开着就把前台服务拉起来（进程重启后不会自己回来）
        if (prefs.getBoolean("attach", false)) ClipService.wake(this);
        // 之前界面不在前台时攒下的「等用户拍板」请求，现在补上
        Pipeline.NeedConfirm pending = Jobs.takePending();
        if (pending != null) onAsk(pending);
    }

    @Override
    protected void onPause() {
        resumed = false;
        foreground = false;
        ClipService.appVisible = false;
        ui.removeCallbacks(clipTick);
        Jobs.setListener(null);      // 别让静态 listener 一直攥着 Activity
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ═══════════════════════ 剪贴板监视 ═══════════════════════

    private final Runnable clipTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed || !prefs.getBoolean("monitor", false)) return;
            checkClipboard();
            ui.postDelayed(this, 1200);
        }
    };

    private String lastClipSig = "";   // 「链接 + 剪贴板时间戳」，持久化，进程重启也不丢
    private long userActedAt;      // 用户刚手动操作过，短时间内别抢输入框

    /** 启动时把上次的剪贴板签名恢复回来，避免一进应用就把最后那条链接又跑一遍 */
    private void restoreClipSig() {
        lastClipSig = prefs.getString("lastClipSig", "");
        if (!lastClipSig.isEmpty()) return;
        // 老版本没有存过签名：如果剪贴板里的链接已经处理过，就当它「已见过」
        String clip = readClipText();
        String u = clip == null ? null : firstBiliUrl(clip);
        if (u != null && History.has(this, History.keyOf(u))) {
            lastClipSig = u;
        }
    }

    private void checkClipboard() {
        if (lastClipSig == null) restoreClipSig();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return;
            CharSequence cs = cd.getItemAt(0).coerceToText(this);
            if (cs == null) return;
            String url = firstBiliUrl(cs.toString());
            if (url == null) return;
            // 自己应用里「复制链接」放进去的，不该反过来触发下载
            if (url.equals(prefs.getString("selfCopiedUrl", null))) {
                prefs.edit().remove("selfCopiedUrl").apply();
                return;
            }
            // 剪贴板时间戳变了才算「重新复制」：这样同一条链接重试也能触发，
            // 又不会因为定时轮询把同一条链接反复下几十次
            long ts = 0;
            try {
                android.content.ClipDescription desc = cm.getPrimaryClipDescription();
                if (desc != null) ts = desc.getTimestamp();
            } catch (Exception ignored) {
            }
            String sig = url + "#" + ts;
            // 注意：小米等 ROM 上「重新复制同一段文字」不会更新剪贴板时间戳，
            // 所以不能只比 sig —— 否则同一条链接复制第二遍永远不会再处理。
            // 这里改成「内容相同且发生在 30 秒内」才算重复，过了窗口就当作新的一次复制。
            long lastAt = prefs.getLong("lastClipAt", 0);
            boolean same = sig.equals(lastClipSig)
                    && System.currentTimeMillis() - lastAt < 30_000L;
            if (same) return;
            // 处理中也照样收：会自动排到队尾（以前这里直接 return，新链接被丢掉）
            // 只留一个很短的保护窗口，避免刚点完按钮就被剪贴板抢走焦点
            if (System.currentTimeMillis() - userActedAt < 1200) {
                android.util.Log.i("BiliSlicer", "clip skip: just acted");
                return;
            }
            // 说明：这里以前还会检查「这条是不是下过」，下过就静默跳过。
            // 结果是——处理中从别的 app 复制一条以前下过的链接，回到应用什么都不发生，
            // 看起来就像「复制了但没人理」。
            // 去重已经由上面的「剪贴板时间戳」负责（同一条内容只处理一次），
            // 所以这里不再因为历史记录而丢弃用户主动复制的链接。
            // 用户正在输入框里编辑时不抢，免得把人家打的字冲掉
            if (input != null && input.hasFocus() && input.getText().length() > 0) {
                android.util.Log.i("BiliSlicer", "clip skip: input focused");
                return;
            }
            lastClipSig = sig;
            prefs.edit().putString("lastClipSig", sig)
                    .putLong("lastClipAt", System.currentTimeMillis()).apply();
            lastHandled = url;
            prefs.edit().putString("lastUrl", url).apply();
            input.setText(url);
            android.util.Log.i("BiliSlicer", "clip enqueue " + url);
            android.util.Log.i("BiliSlicer", "clip enqueue " + url);
            log("🔗 识别到链接，开始处理");
            enqueueAll(url);
        } catch (Exception ignored) {
            // Android 10+ 失去焦点时读不到剪贴板，属正常
        }
    }

    private static final Pattern URL_PAT = Pattern.compile("https?://[\\w\\-./?=&%#:+]+");

    private static boolean isBili(String s) {
        return Source.supports(s);
    }

    private static String firstBiliUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PAT.matcher(text);
        while (m.find()) {
            if (isBili(m.group())) return m.group();
        }
        return null;
    }

    private static List<String> allBiliUrls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = URL_PAT.matcher(text);
        while (m.find()) {
            String u = m.group();
            if (isBili(u) && !out.contains(u)) out.add(u);
        }
        return out;
    }

    // ═══════════════════════ UI 工具 ═══════════════════════

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** 9.600000381469727 这种 float 尾巴要抹掉 */
    /** 毫秒转 12:34 / 1:02:03 */
    static String fmtClock(long ms) {
        if (ms <= 0) return "";
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.CHINA, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.CHINA, "%d:%02d", m, sec);
    }

    static String fmtMb(double v) {
        String t = String.format(Locale.CHINA, "%.1f", v);
        return t.endsWith(".0") ? t.substring(0, t.length() - 2) : t;
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable cardBg() {
        GradientDrawable d = round(Color.WHITE, 14);
        d.setStroke(dp(1), LINE);
        return d;
    }

    /** 通用小药丸按钮 */
    private TextView chip(String label, int bg, int fg, float sp) {
        TextView t = tv(label, sp, fg, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(round(bg, 999));
        return t;
    }

    private TextView iconBtn(String glyph, int sizeDp, int bg, int fg) {
        TextView t = tv(glyph, 16, fg, false);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        t.setLayoutParams(p);
        t.setBackground(round(bg, 999));
        return t;
    }

    private static LinearLayout.LayoutParams lpMM() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpTop(int topDp) {
        LinearLayout.LayoutParams p = lpMM();
        p.topMargin = dp(topDp);
        return p;
    }

    // ═══════════════════════ 界面 ═══════════════════════

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ── 顶栏
        LinearLayout bar0 = new LinearLayout(this);
        bar0.setOrientation(LinearLayout.HORIZONTAL);
        bar0.setGravity(Gravity.CENTER_VERTICAL);
        bar0.setBackgroundColor(PINK);
        bar0.setPadding(dp(18), dp(16), dp(14), dp(14));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(tv(getString(R.string.app_name), 20, Color.WHITE, true));
        titleBox.addView(tv("B站 · 抖音 · 快手 · 存片库 · 一键发微信", 11, 0xFFFFD9E5, false));
        bar0.addView(titleBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hist = iconBtn("📋", 38, 0x33FFFFFF, Color.WHITE);
        hist.setTextSize(15);
        hist.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        LinearLayout.LayoutParams hg = new LinearLayout.LayoutParams(dp(38), dp(38));
        hg.rightMargin = dp(8);
        bar0.addView(hist, hg);

        TextView gear = iconBtn("⚙", 38, 0x33FFFFFF, Color.WHITE);
        gear.setTextSize(18);
        gear.setOnClickListener(v -> settingsDialog());
        bar0.addView(gear);
        root.addView(bar0, lpMM());

        ScrollView sv = new ScrollView(this);
        svRoot = sv;
        sv.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(24));

        // ── 输入卡
        LinearLayout inputCard = new LinearLayout(this);
        inputCard.setOrientation(LinearLayout.HORIZONTAL);
        inputCard.setGravity(Gravity.BOTTOM);
        inputCard.setBackground(cardBg());
        inputCard.setPadding(dp(12), dp(8), dp(8), dp(8));
        inputCard.setElevation(dp(1));

        input = new EditText(this);
        input.setHint("粘贴 B站 / 抖音 / 快手链接，可一次贴多个");
        input.setTextSize(14);
        input.setTextColor(INK);
        input.setHintTextColor(GRAY);
        input.setBackground(null);
        input.setPadding(dp(4), dp(12), dp(4), dp(12));
        input.setMaxLines(4);
        inputCard.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        goBtn = chip("开始", PINK, Color.WHITE, 14);
        goBtn.setPadding(dp(18), dp(12), dp(18), dp(12));
        goBtn.setOnClickListener(v -> start());
        inputCard.addView(goBtn);
        body.addView(inputCard);

        // ── 快捷药丸
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(10), 0, 0);

        chipMonitor = chip("○ 自动识别", CHIP, SUB, 12);
        chipMonitor.setOnClickListener(v -> toggleMonitor());
        chipAttach = chip("○ 自动挂载", CHIP, SUB, 12);
        chipAttach.setOnClickListener(v -> toggleAttach());
        TextView paste = chip("粘贴", CHIP, SUB, 12);
        chips.addView(chipMonitor);
        LinearLayout.LayoutParams ag = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ag.leftMargin = dp(8);
        chips.addView(chipAttach, ag);
        LinearLayout.LayoutParams cg = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cg.leftMargin = dp(8);
        chips.addView(paste, cg);
        paste.setOnClickListener(v -> {
            String s = readClipText();
            if (s == null) {
                toast("剪贴板是空的");
                return;
            }
            input.setText(s);
            List<String> urls = allBiliUrls(s);
            if (urls.size() > 1) toast("识别到 " + urls.size() + " 个链接，点开始批量处理");
        });
        chipRetry = chip("↻ 重试", CHIP, SUB, 12);
        chipRetry.setVisibility(View.GONE);
        chipRetry.setOnClickListener(v -> {
            String u = Jobs.lastFailedUrl;
            if (u == null) {
                toast("没有失败的记录");
                return;
            }
            input.setText(u);
            Jobs.lastFailedUrl = null;
            prefs.edit().remove("lastFailUrl").apply();
            chipRetry.setVisibility(View.GONE);
            // 用户明确点了重试，就别再弹「已经处理过」了，直接排进队列
            java.util.List<String> one = new java.util.ArrayList<>();
            one.add(u);
            doEnqueue(one, true);
        });
        LinearLayout.LayoutParams rg = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rg.leftMargin = dp(8);
        chips.addView(chipRetry, rg);
        TextView pickFile = chip("选本地视频", CHIP, SUB, 12);
        pickFile.setOnClickListener(v -> pickLocalVideo());
        LinearLayout.LayoutParams pf = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pf.leftMargin = dp(8);
        chips.addView(pickFile, pf);

        chipStop = chip("■ 取消", CHIP, SUB, 12);
        chipStop.setVisibility(View.GONE);
        chipStop.setOnClickListener(v -> {
            Jobs.cancelAll();
            chipStop.setVisibility(View.GONE);
            toast("已取消");
        });
        LinearLayout.LayoutParams sg = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sg.leftMargin = dp(8);
        chips.addView(chipStop, sg);
        body.addView(chips);

        // ── 无障碍失效提示条：「复制链接没反应」九成是这个，别让它藏在设置里
        accBanner = tv("", 12, 0xFF7A4A00, false);
        accBanner.setPadding(dp(10), dp(8), dp(10), dp(9));
        accBanner.setBackground(round(0xFFFFF3D6, dp(8)));
        accBanner.setVisibility(View.GONE);
        accBanner.setOnClickListener(v -> openAccessibilitySettings());
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ab.topMargin = dp(8);
        body.addView(accBanner, ab);

        ruleLine = tv("", 11, GRAY, false);
        ruleLine.setPadding(dp(4), dp(8), 0, 0);
        body.addView(ruleLine);

        // ── 进度
        progressWrap = new LinearLayout(this);
        progressWrap.setOrientation(LinearLayout.VERTICAL);
        progressWrap.setPadding(0, dp(12), 0, 0);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        progressWrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        statusLine = tv("", 12, SUB, false);
        statusLine.setPadding(dp(2), dp(6), 0, 0);
        progressWrap.addView(statusLine);
        body.addView(progressWrap);

        // ── 片库头
        LinearLayout libHead = new LinearLayout(this);
        libHeadView = libHead;
        libHead.setOrientation(LinearLayout.HORIZONTAL);
        libHead.setGravity(Gravity.CENTER_VERTICAL);
        libHead.setPadding(dp(2), dp(18), 0, dp(6));
        TextView libTitle = tv("片库", 18, INK, true);
        libHead.addView(libTitle);
        libStat = tv("", 11, GRAY, false);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.leftMargin = dp(8);
        libHead.addView(libStat, stp);

        libHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView searchIcon = iconBtn("🔍", 32, Color.TRANSPARENT, SUB);
        searchIcon.setTextSize(14);
        searchIcon.setOnClickListener(v -> toggleSearch());
        chipPick = chip("选择", CHIP, SUB, 11);
        chipPick.setOnClickListener(v -> {
            picking = !picking;
            picked.clear();
            renderGroups(lastGroups, "");
            if (picking) toast("点视频缩略图圈选，再点「删除」");
        });
        onlyUnsent = prefs.getBoolean("onlyUnsent", false);
        chipUnsent = chip("只看未发", CHIP, SUB, 11);
        styleUnsent();
        chipUnsent.setOnClickListener(v -> {
            onlyUnsent = !onlyUnsent;
            prefs.edit().putBoolean("onlyUnsent", onlyUnsent).apply();
            styleUnsent();
            renderGroups(lastGroups, "");
            reloadLibrary();
        });
        chipSort = chip("最新", CHIP, SUB, 11);
        chipSort.setOnClickListener(v -> cycleSort());
        segV = chip("竖", CHIP, SUB, 11);
        segH = chip("横", CHIP, SUB, 11);
        segV.setOnClickListener(v -> setOrient("v"));
        segH.setOnClickListener(v -> setOrient("h"));

        LinearLayout.LayoutParams ig = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ig.leftMargin = dp(6);
        libHead.addView(chipPick, ig);
        libHead.addView(chipUnsent, ig);
        libHead.addView(searchIcon);
        libHead.addView(chipSort, ig);
        libHead.addView(segV, ig);
        libHead.addView(segH, ig);
        body.addView(libHead);

        // ── 搜索框（默认隐藏）
        searchBox = new EditText(this);
        // 有内容时右侧出现「×」，一键清空搜索
        searchBox.setSingleLine(true);
        searchBox.setHint("搜索标题或文件名");
        searchBox.setTextSize(13);
        searchBox.setBackground(cardBg());
        searchBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchBox.setVisibility(View.GONE);
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim();
                if (searchClear != null) {
                    searchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
                reloadLibrary();
            }

            public void afterTextChanged(Editable s) {
            }
        });
        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setVisibility(View.GONE);
        searchBox.setVisibility(View.VISIBLE);
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        searchClear = tv("✕", 15, SUB, true);
        searchClear.setPadding(dp(12), dp(8), dp(10), dp(8));
        searchClear.setVisibility(View.GONE);
        searchClear.setOnClickListener(v -> searchBox.setText(""));
        searchRow.addView(searchClear);
        body.addView(searchRow, lpTop(8));

        libraryBox = new LinearLayout(this);
        libraryBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(libraryBox);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ═══════════════════════ 状态药丸 ═══════════════════════

    private void updateMonitorChip() {
        boolean on = prefs.getBoolean("monitor", false);
        chipMonitor.setText(on ? "● 自动识别 开" : "○ 自动识别 关");
        chipMonitor.setTextColor(on ? Color.WHITE : SUB);
        chipMonitor.setBackground(round(on ? BLUE : CHIP, 999));
    }

    /**
     * 自动挂载：前台服务挂一个「片」小球，点一下读剪贴板并弹选项面板。
     * 只依赖悬浮窗权限；无障碍只是「自动识别复制」的加分项，不强制。
     */
    private void toggleAttach() {
        boolean on = !prefs.getBoolean("attach", false);
        if (!on) {
            prefs.edit().putBoolean("attach", on).apply();
            try {
                stopService(new Intent(this, ClipService.class));
            } catch (Exception ignored) {
            }
            updateAttachChip();
            toast("自动挂载已关闭");
            return;
        }

        boolean overlay = android.provider.Settings.canDrawOverlays(this);
        if (!overlay) {
            new AlertDialog.Builder(this)
                    .setTitle("还差一个悬浮窗权限")
                    .setMessage("自动挂载需要在别的应用上显示一个小球。\n"
                            + "点「去开启」→ 找到「B站切片」→ 打开「显示在其他应用的上层」。")
                    .setNegativeButton("算了", null)
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                        }
                    })
                    .show();
            return;
        }

        prefs.edit().putBoolean("attach", true).apply();
        ClipService.wake(this);
        updateAttachChip();
        ui.postDelayed(() -> toast("已开启：屏幕左侧会出现粉色「片」小球，复制链接后点它"), 300);
        if (!isAccessibilityOn()) {
            ui.postDelayed(() -> toast("想让它自动认出「复制」动作，可以再去开无障碍（可选）"), 2600);
        }
    }

    private boolean isAccessibilityOn() {
        try {
            String s = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String me = getPackageName() + "/" + ClipWatchService.class.getName();
            String me2 = getPackageName() + "/.ClipWatchService";
            return s.contains(me) || s.contains(me2);
        } catch (Exception e) {
            return false;
        }
    }

    private void openAccessibilitySettings() {
        try {
            Intent it = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            toast("在列表里找到「片匠」并打开");
        } catch (Exception e) {
            toast("打不开无障碍设置，请手动去系统设置里开");
        }
    }

    private void updateAttachChip() {
        if (chipAttach == null) return;
        boolean on = prefs.getBoolean("attach", false);
        chipAttach.setText(on ? "● 自动挂载 开" : "○ 自动挂载 关");
        chipAttach.setTextColor(on ? Color.WHITE : SUB);
        chipAttach.setBackground(round(on ? PINK : CHIP, 999));
    }

    /** 选手机里的视频来切（不用链接） */
    private void pickLocalVideo() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_PICK_VIDEO);
        } catch (Exception e) {
            toast("打不开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_VIDEO || res != RESULT_OK || data == null) return;
        java.util.List<Uri> list = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                list.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            list.add(data.getData());
        }
        if (list.isEmpty()) return;
        for (Uri u : list) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            Jobs.enqueueLocal(u, nameOf(u), limitMb, wantParts);
        }
        toast("已加入 " + list.size() + " 个本地视频");
        scrollTopNext = true;
        reloadLibrary();
    }

    private String nameOf(Uri u) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return name == null ? "本地视频" : name;
    }

    /** 把当前生效的规则写在首页，省得每次去设置里翻 */
    private void refreshRuleLine() {
        if (ruleLine == null) return;
        StringBuilder sb = new StringBuilder();
        float totalMb = prefs.getFloat("totalMb", 0f);
        float skipSec = prefs.getFloat("skipSec", 0f);
        float tailSec = prefs.getFloat("tailSec", 0f);
        if (totalMb > 0) {
            // 整片目标大小模式下，每段上限/时长/段数/码率上限都不参与计算
            sb.append("整条 ≤").append(trimNum(totalMb)).append(" MB（不分段）");
        } else {
            sb.append("每段 ≤").append(trimNum(limitMb)).append(" MB");
            if (partMin > 0) sb.append(" · ≤").append(trimNum(partMin)).append(" 分钟");
            if (maxKbps > 0) sb.append(" · 码率 ≤").append(maxKbps).append("k");
            if (wantParts > 0) sb.append(" · 固定 ").append(wantParts).append(" 段");
            else sb.append(" · 段数自动");
        }
        if (skipSec > 0) sb.append(" · 跳过前 ").append((int) skipSec).append("s");
        if (tailSec > 0) sb.append(" · 去尾 ").append((int) tailSec).append("s");
        if (!hq) sb.append(" · 360P");
        ruleLine.setText(sb.toString());
    }

    private String trimNum(double v) {
        // prefs 里存的是 float，转 double 会冒出 9.000000381469727 这种尾巴
        double r = Math.round(v * 10) / 10.0;
        return r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private void toggleMonitor() {
        boolean on = !prefs.getBoolean("monitor", false);
        prefs.edit().putBoolean("monitor", on).apply();
        updateMonitorChip();
        if (on) {
            askNotificationPermission();
            log("已开启：复制 B站链接后自动开始");
            ui.removeCallbacks(clipTick);
            ui.post(clipTick);
        } else {
            log("已关闭剪贴板识别");
        }
    }

    private void updateSortChip() {
        String label = "new".equals(sort) ? "最新" : "name".equals(sort) ? "名称" : "大小";
        chipSort.setText(label);
    }

    private void cycleSort() {
        sort = "new".equals(sort) ? "name" : "name".equals(sort) ? "size" : "new";
        prefs.edit().putString("sort", sort).apply();
        updateSortChip();
        reloadLibrary();
    }

    private void toggleSearch() {
        searchOpen = !searchOpen;
        searchRow.setVisibility(searchOpen ? View.VISIBLE : View.GONE);
        if (searchClear != null) searchClear.setVisibility(View.GONE);
        if (searchOpen) {
            searchBox.requestFocus();
            searchBox.setText("");
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(searchBox, 0);
            } catch (Exception ignored) {
            }
        } else {
            query = "";
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
                searchBox.clearFocus();
            } catch (Exception ignored) {
            }
            reloadLibrary();
        }
    }

    private void setOrient(String o) {
        orient = o;
        lastOrientForSig = o;
        prefs.edit().putString("orient", o).apply();
        styleSeg(segV, "v".equals(o));
        styleSeg(segH, "h".equals(o));
        reloadLibrary();
    }

    private void styleUnsent() {
        if (chipUnsent == null) return;
        chipUnsent.setTextColor(onlyUnsent ? Color.WHITE : SUB);
        chipUnsent.setBackground(round(onlyUnsent ? PINK : CHIP, 999));
    }

    private void styleSeg(TextView t, boolean on) {
        if (t == null) return;
        t.setTextColor(on ? Color.WHITE : SUB);
        t.setBackground(round(on ? PINK : CHIP, 999));
    }

    // ═══════════════════════ 设置面板 ═══════════════════════

    private void settingsDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));
        scroll.addView(box);

        // 当前模式：填了整片目标大小 = 「整条一个文件」
        final boolean[] totalMode = {prefs.getFloat("totalMb", 0f) > 0};

        // ── 无障碍状态：复制链接没反应，十有八九是这里被系统关了
        // 以「服务真的连着」为准，勾选状态只用来区分「勾了但没跑」和「压根没勾」
        boolean accOn = ClipWatchService.enabledInSettings(this);
        boolean accLive = ClipWatchService.connected || accOn;
        TextView accLine = tv(accLive ? "✓ 复制监听已连接"
                        : (accOn ? "⚠ 复制监听未运行（点这里重新开启）"
                                 : "✕ 复制监听未开启（点这里去开启）"),
                12, accLive ? SUB : PINK, true);
        accLine.setPadding(0, dp(4), 0, dp(6));
        accLine.setOnClickListener(v -> {
            if (accLive) {
                toast("已连接，在抖音/快手/B站点「复制链接」就会自动处理");
                return;
            }
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("找到「片匠」打开开关，回来即可生效");
            } catch (Exception e) {
                toast("打不开系统设置，请手动去无障碍里开启");
            }
        });
        box.addView(accLine);

        // ── 分组标题
        box.addView(tv("分片方式", 12, PINK, true), lpTop(4));
        box.addView(tv("两种模式二选一，其余参数会自动忽略", 11, GRAY, false), lpTop(2));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(8), 0, 0);
        TextView modeSplit = chip("按每段上限", CHIP, SUB, 12);
        TextView modeTotal = chip("整条一个文件", CHIP, SUB, 12);
        modeRow.addView(modeSplit);
        LinearLayout.LayoutParams mr = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mr.leftMargin = dp(8);
        modeRow.addView(modeTotal, mr);
        box.addView(modeRow);

        // ── 模式一：按每段上限
        LinearLayout gSplit = new LinearLayout(this);
        gSplit.setOrientation(LinearLayout.VERTICAL);

        gSplit.addView(tv("每段上限（MB）", 13, SUB, false), lpTop(12));
        EditText eLimit = new EditText(this);
        eLimit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        eLimit.setHint("9.6");
        eLimit.setText(fmtMb(limitMb));
        eLimit.setTextSize(15);
        gSplit.addView(eLimit, lpTop(2));

        gSplit.addView(tv("每段时长（分钟，可选，填 0 = 只看体积）", 13, SUB, false), lpTop(10));
        EditText eMin = new EditText(this);
        eMin.setInputType(InputType.TYPE_CLASS_NUMBER);
        eMin.setHint("0");
        float pm = prefs.getFloat("partMin", 0f);
        eMin.setText(pm > 0 ? fmtMb(pm) : "");
        eMin.setTextSize(15);
        gSplit.addView(eMin, lpTop(2));

        gSplit.addView(tv("段数（留空 = 自动，够切就行不压画质）", 13, SUB, false), lpTop(10));
        EditText eParts = new EditText(this);
        eParts.setInputType(InputType.TYPE_CLASS_NUMBER);
        eParts.setHint("自动");
        eParts.setText(wantParts > 0 ? String.valueOf(wantParts) : "");
        eParts.setTextSize(15);
        gSplit.addView(eParts, lpTop(2));
        box.addView(gSplit);

        // ── 模式二：整条一个文件
        LinearLayout gTotal = new LinearLayout(this);
        gTotal.setOrientation(LinearLayout.VERTICAL);

        gTotal.addView(tv("整条目标大小（MB）", 13, SUB, false), lpTop(12));
        EditText eTotal = new EditText(this);
        eTotal.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        eTotal.setHint("例如 12");
        float tm = prefs.getFloat("totalMb", 0f);
        eTotal.setText(tm > 0 ? fmtMb(tm) : "");
        eTotal.setTextSize(15);
        gTotal.addView(eTotal, lpTop(2));
        gTotal.addView(tv("压成一个文件，不超过这个大小；本来就够小就只裁剪不重压",
                11, GRAY, false), lpTop(4));
        box.addView(gTotal);

        // ── 画质
        box.addView(tv("画质", 12, PINK, true), lpTop(16));
        android.widget.CheckBox cbHq = new android.widget.CheckBox(this);
        cbHq.setText("高清（480P，音视频合流，文件会大一些）");
        cbHq.setTextSize(13);
        cbHq.setChecked(prefs.getBoolean("hq", true));
        box.addView(cbHq, lpTop(6));

        LinearLayout kbpsBox = new LinearLayout(this);
        kbpsBox.setOrientation(LinearLayout.VERTICAL);
        kbpsBox.addView(tv("码率上限（kbps，可选，留空 = 自动判断）", 13, SUB, false), lpTop(8));
        EditText eKbps = new EditText(this);
        eKbps.setInputType(InputType.TYPE_CLASS_NUMBER);
        eKbps.setHint("900");
        int mk = prefs.getInt("maxKbps", 900);
        eKbps.setText(mk > 0 ? String.valueOf(mk) : "");
        eKbps.setTextSize(15);
        kbpsBox.addView(eKbps, lpTop(2));
        box.addView(kbpsBox);

        // ── 裁剪
        box.addView(tv("裁剪", 12, PINK, true), lpTop(16));
        LinearLayout trims = new LinearLayout(this);
        trims.setOrientation(LinearLayout.HORIZONTAL);
        trims.setPadding(0, dp(6), 0, 0);
        LinearLayout trimL = new LinearLayout(this);
        trimL.setOrientation(LinearLayout.VERTICAL);
        trimL.addView(tv("跳过开头（秒）", 13, SUB, false));
        EditText eSkip = new EditText(this);
        eSkip.setInputType(InputType.TYPE_CLASS_NUMBER);
        eSkip.setHint("0");
        float sk = prefs.getFloat("skipSec", 0f);
        eSkip.setText(sk > 0 ? String.valueOf((int) sk) : "");
        eSkip.setTextSize(15);
        trimL.addView(eSkip, lpTop(2));
        LinearLayout trimR = new LinearLayout(this);
        trimR.setOrientation(LinearLayout.VERTICAL);
        trimR.setPadding(dp(10), 0, 0, 0);
        trimR.addView(tv("裁掉结尾（秒）", 13, SUB, false));
        EditText eTail = new EditText(this);
        eTail.setInputType(InputType.TYPE_CLASS_NUMBER);
        eTail.setHint("0");
        float tl = prefs.getFloat("tailSec", 0f);
        eTail.setText(tl > 0 ? String.valueOf((int) tl) : "");
        eTail.setTextSize(15);
        trimR.addView(eTail, lpTop(2));
        trims.addView(trimL, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        trims.addView(trimR, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(trims);

        // ── 底部：恢复默认 + 说明
        TextView reset = tv("恢复默认设置", 12, BLUE, true);
        reset.setPadding(0, dp(18), 0, dp(4));
        box.addView(reset);
        TextView cleanDirs = tv("清理空文件夹", 12, BLUE, true);
        cleanDirs.setPadding(0, dp(10), 0, dp(4));
        cleanDirs.setOnClickListener(v -> {
            int n = Store.purgeEmptyDirs(this);
            toast(n > 0 ? ("已清理 " + n + " 个空文件夹") : "没有空文件夹");
            reloadLibrary();
        });
        box.addView(cleanDirs);

        TextView clearThumb = tv("清空封面缓存", 12, BLUE, true);
        clearThumb.setPadding(0, dp(10), 0, dp(4));
        clearThumb.setOnClickListener(v -> {
            java.io.File[] fs = Thumbs.dir(this).listFiles();
            if (fs != null) for (java.io.File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            toast("封面缓存已清空，下次进片库重新生成");
        });
        box.addView(clearThumb);
        box.addView(tv("封面缓存 7 天后也会自动清理。", 11, GRAY, false), lpTop(4));

        // 模式切换
        final Runnable[] apply = new Runnable[1];
        apply[0] = () -> {
            boolean t = totalMode[0];
            gSplit.setVisibility(t ? View.GONE : View.VISIBLE);
            gTotal.setVisibility(t ? View.VISIBLE : View.GONE);
            kbpsBox.setVisibility(t ? View.GONE : View.VISIBLE);
            modeSplit.setTextColor(t ? SUB : Color.WHITE);
            modeSplit.setBackground(round(t ? CHIP : PINK, 999));
            modeTotal.setTextColor(t ? Color.WHITE : SUB);
            modeTotal.setBackground(round(t ? PINK : CHIP, 999));
        };
        modeSplit.setOnClickListener(v -> {
            totalMode[0] = false;
            apply[0].run();
        });
        modeTotal.setOnClickListener(v -> {
            totalMode[0] = true;
            apply[0].run();
        });
        apply[0].run();

        reset.setOnClickListener(v -> {
            eLimit.setText("9.6");
            eMin.setText("");
            eParts.setText("");
            eKbps.setText("900");
            cbHq.setChecked(true);
            eTotal.setText("");
            eSkip.setText("");
            eTail.setText("");
            totalMode[0] = false;
            apply[0].run();
            toast("已填入默认值，点「保存」生效");
        });

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    boolean ok = true;
                    float savedMin = 0f;
                    int savedKbps = 0;

                    if (totalMode[0]) {
                        // 整条一个文件：只要目标大小，其它参数保持原样不覆盖
                        double tvv = 0;
                        try {
                            String s = eTotal.getText().toString().trim();
                            tvv = s.isEmpty() ? 0 : Double.parseDouble(s);
                        } catch (Exception ignored) {
                        }
                        if (tvv < 1 || tvv > 500) {
                            toast("整条目标大小要在 1~500 MB 之间");
                            return;
                        }
                        prefs.edit().putFloat("totalMb", (float) tvv).apply();
                    } else {
                        try {
                            double v = Double.parseDouble(eLimit.getText().toString().trim());
                            if (v >= 2 && v <= 200) limitMb = v;
                            else ok = false;
                        } catch (Exception e) {
                            ok = false;
                        }
                        String p = eParts.getText().toString().trim();
                        if (p.isEmpty()) {
                            wantParts = 0;
                        } else {
                            try {
                                int ip = Integer.parseInt(p);
                                if (ip >= 1 && ip <= 50) wantParts = ip;
                                else ok = false;
                            } catch (Exception e) {
                                ok = false;
                            }
                        }
                        try {
                            String ms = eMin.getText().toString().trim();
                            if (!ms.isEmpty()) {
                                double dv = Double.parseDouble(ms);
                                if (dv >= 0 && dv <= 120) savedMin = (float) dv;
                            }
                        } catch (Exception ignored) {
                        }
                        try {
                            String ks = eKbps.getText().toString().trim();
                            if (!ks.isEmpty()) {
                                int v = Integer.parseInt(ks);
                                if (v >= 80 && v <= 8000) savedKbps = v;
                            }
                        } catch (Exception ignored) {
                        }
                        if (!ok) {
                            toast("每段上限要在 2~200 MB、段数 1~50 之间，已保留原设置");
                            return;
                        }
                        partMin = savedMin;
                        maxKbps = savedKbps;
                        hq = cbHq.isChecked();
                        prefs.edit().putFloat("limit", (float) limitMb)
                                .putInt("parts", wantParts)
                                .putFloat("partMin", savedMin)
                                .putInt("maxKbps", savedKbps)
                                .putBoolean("hq", cbHq.isChecked())
                                .putFloat("totalMb", 0).apply();
                    }

                    try {
                        String sv = eSkip.getText().toString().trim();
                        prefs.edit().putFloat("skipSec",
                                (float) (sv.isEmpty() ? 0 : Integer.parseInt(sv))).apply();
                    } catch (Exception ignored) {
                    }
                    try {
                        String tvs = eTail.getText().toString().trim();
                        prefs.edit().putFloat("tailSec",
                                (float) (tvs.isEmpty() ? 0 : Integer.parseInt(tvs))).apply();
                    } catch (Exception ignored) {
                    }

                    refreshRuleLine();
                    toast("已保存：" + ruleLine.getText());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ═══════════════════════ 交互 ═══════════════════════

    private void handleIntent(Intent it) {
        if (it != null && it.getBooleanExtra("toLib", false) && svRoot != null) {
            svRoot.postDelayed(() -> {
                try {
                    svRoot.smoothScrollTo(0, Math.max(0, libHeadView.getTop() - dp(8)));
                } catch (Exception ignored) {
                }
            }, 500);
        }
        if (it == null) return;
        final int parts = it.getIntExtra("parts", 0);
        if (parts > 0) {
            wantParts = parts;
            prefs.edit().putInt("parts", parts).apply();
        }
        if (it.getBooleanExtra("readClip", false)) {
            // 从「自动挂载」小卡片过来：立刻读剪贴板处理，不用用户再粘贴
            ui.postDelayed(() -> {
                String clip = readClipText();
                String u = clip == null ? null : firstBiliUrl(clip);
                if (u != null) {
                    input.setText(u);
                    enqueueAll(u);
                } else {
                    toast("剪贴板里没有可识别的链接");
                }
            }, 350);
            return;
        }
        if (Intent.ACTION_SEND.equals(it.getAction())) {
            String shared = it.getStringExtra(Intent.EXTRA_TEXT);
            String url = firstBiliUrl(shared);
            if (url != null) {
                input.setText(url);
                lastHandled = url;
                prefs.edit().putString("lastUrl", url).apply();
                ui.postDelayed(() -> enqueueAll(url), 300);
            } else if (shared != null && !shared.trim().isEmpty()
                    && !shared.trim().startsWith("http")) {
                // 分享过来一段文字（比如 UP 主名字）——填进输入框，当名字去搜
                input.setText(shared.trim());
                toast("已填入：" + shared.trim() + "，点「开始」批量下载");
            }
        }
    }

    private String readClipText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            CharSequence cs = cd.getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        // 刚给了媒体权限，片库内容可能从空变成有
        lastLibSig = "";
        reloadLibrary();
    }

    private void askNotificationPermission() {
        try {
            java.util.List<String> need = new java.util.ArrayList<>();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    need.add("android.permission.POST_NOTIFICATIONS");
                }
                // 片库要能看到文件夹里所有片段（包括别处拷进来的）
                if (checkSelfPermission("android.permission.READ_MEDIA_VIDEO")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    need.add("android.permission.READ_MEDIA_VIDEO");
                }
            } else if (checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                need.add("android.permission.READ_EXTERNAL_STORAGE");
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), 7);
            }
        } catch (Exception ignored) {
        }
    }

    private void start() {
        userActedAt = System.currentTimeMillis();
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            toast("请先粘贴链接");
            return;
        }
        hideKeyboard();
        enqueueAll(text);
    }

    /** 只藏进度条，状态文字保留（否则错误提示会一起消失） */
    private void showProgress(boolean show) {
        if (bar != null) bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (statusLine != null) {
            statusLine.setVisibility(TextUtils.isEmpty(lastLog) && !show
                    ? View.GONE : View.VISIBLE);
        }
    }

    private void enqueueAll(String text) {
        List<String> urls = allBiliUrls(text);
        if (urls.isEmpty()) {
            if (text.startsWith("http")) {
                urls.add(text);
            } else {
                // UP 主空间链接：直接拿 mid，省掉一次搜索（少一次风控风险）
                String mid = spaceMid(text);
                if (mid != null) {
                    BiliApi.Up up = new BiliApi.Up();
                    up.mid = mid;
                    up.name = "UP " + mid;
                    loadUpVideos(up);
                    return;
                }
                // 不是链接，当成 UP 主名字去搜
                searchUpFlow(text);
                return;
            }
        }

        // 已处理过的挑出来问一句
        final List<String> dupTitles = new ArrayList<>();
        final List<String> fresh = new ArrayList<>();
        for (String u : urls) {
            String k = History.keyOf(u);
            History.Rec r = k.isEmpty() ? null : History.find(this, k);
            // 只把「成功切过」的算重复。失败记录也在历史里，
            // 但失败后重新粘贴/重试本来就该直接再跑一遍 —— 以前不区分，
            // 结果是：失败了点重试，弹一句「这个视频之前已经切过了」，看着就像没反应。
            if (r != null && !r.fail) {
                dupTitles.add(r.title);
            } else {
                fresh.add(u);
            }
        }

        if (dupTitles.isEmpty()) {
            doEnqueue(urls);
            return;
        }

        StringBuilder msg = new StringBuilder("这 " + dupTitles.size() + " 个视频之前已经切过了：\n");
        for (int i = 0; i < dupTitles.size() && i < 5; i++) {
            msg.append("· ").append(dupTitles.get(i)).append('\n');
        }
        if (dupTitles.size() > 5) msg.append("· …等 ").append(dupTitles.size()).append(" 个\n");
        msg.append("\n再处理一遍会覆盖片库里同名的旧文件。");

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("已经处理过")
                .setMessage(msg.toString())
                .setPositiveButton("仍然处理", (d, w) -> doEnqueue(urls, true))
                .setNegativeButton("取消", null);
        if (!fresh.isEmpty()) {
            b.setNeutralButton("只处理新的", (d, w) -> doEnqueue(fresh));
        }
        b.show();
    }

    private void doEnqueue(List<String> urls) {
        doEnqueue(urls, false);
    }

    private void doEnqueue(List<String> urls, boolean force) {
        if (urls.isEmpty()) return;
        showProgress(true);
        lastPct = 0;
        bar.setProgress(0);
        lastLog = urls.size() > 1 ? ("已排队 " + urls.size() + " 个任务") : "准备中…";
        refreshStatus();
        for (String u : urls) Jobs.enqueue(u, limitMb, wantParts, force);
        input.setText("");
        if (urls.size() > 1) toast("已加入队列：" + urls.size() + " 个视频");
        syncBusy();
    }

    // ══════════════ 按 UP 主名批量下载 ══════════════

    /** space.bilibili.com/946974 这种链接里取出 mid */
    private static String spaceMid(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("space\\.bilibili\\.com/(\\d{3,})").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private void searchUpFlow(final String keyword) {
        showProgress(true);
        lastPct = 0;
        bar.setProgress(0);
        lastLog = "正在搜索 UP 主：" + keyword;
        refreshStatus();
        new Thread(() -> {
            try {
                final List<BiliApi.Up> ups = BiliApi.searchUp(keyword);
                ui.post(() -> {
                        if (ups.isEmpty()) {
                        android.util.Log.w("BiliSlicer", "searchUp empty for " + keyword);
                        toast("没搜到这个名字的 UP 主");
                        return;
                    }
                    showUpChooser(keyword, ups);
                });
            } catch (Exception e) {
                android.util.Log.e("BiliSlicer", "searchUp fail", e);
                final String m = e.getMessage() == null ? "搜索失败" : e.getMessage();
                ui.post(() -> {
                    toast(m);
                    lastLog = "✗ " + m;
                    refreshStatus();
                });
            }
        }, "up-search").start();
    }

    private void showUpChooser(String keyword, List<BiliApi.Up> ups) {
        final String[] items = new String[ups.size()];
        for (int i = 0; i < ups.size(); i++) {
            BiliApi.Up u = ups.get(i);
            items[i] = u.name + "   " + fansText(u.fans) + " 粉丝";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 UP 主（" + keyword + "）")
                .setItems(items, (d, which) -> loadUpVideos(ups.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private static String fansText(long f) {
        if (f >= 10000) return String.format(Locale.CHINA, "%.1f 万", f / 10000.0);
        return String.valueOf(f);
    }

    private void loadUpVideos(final BiliApi.Up up) {
        showProgress(true);
        lastPct = 0;
        bar.setProgress(0);
        lastLog = "正在拉取《" + up.name + "》的投稿…";
        refreshStatus();
        new Thread(() -> {
            try {
                final List<BiliApi.UpVideo> vids = BiliApi.upVideosSmart(up);
                final List<BiliApi.UpVideo> fresh = new ArrayList<>();
                int skippedCount = 0;
                for (BiliApi.UpVideo v : vids) {
                    if (History.has(this, v.bvid)) skippedCount++;
                    else fresh.add(v);
                }
                final int skipped = skippedCount;
                ui.post(() -> showCountDialog(up, vids.size(), fresh, skipped));
            } catch (Exception e) {
                android.util.Log.e("BiliSlicer", "upVideos fail", e);
                final String m = e.getMessage() == null ? "拉取失败" : e.getMessage();
                ui.post(() -> {
                    toast(m);
                    lastLog = "✗ " + m;
                    refreshStatus();
                });
            }
        }, "up-videos").start();
    }

    private void showCountDialog(BiliApi.Up up, int total, final List<BiliApi.UpVideo> fresh,
                                 int skipped) {
        if (fresh.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(up.name)
                    .setMessage("最新 " + total + " 个投稿都已经处理过了（跳过 " + skipped + " 个）。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView tip = tv("最新 " + total + " 个投稿里，" + fresh.size()
                + " 个没处理过" + (skipped > 0 ? "（已跳过 " + skipped + " 个重复的）" : "")
                + "。\n要下载最新的多少个？", 13, SUB, false);
        tip.setLineSpacing(dp(3), 1f);
        box.addView(tip);

        final EditText cnt = new EditText(this);
        cnt.setInputType(InputType.TYPE_CLASS_NUMBER);
        cnt.setText(String.valueOf(Math.min(20, fresh.size())));
        cnt.setTextSize(15);
        box.addView(cnt, lpTop(8));

        box.addView(tv("按发布时间从新到旧入队，可以随时在片库看进度。", 11, GRAY, false), lpTop(6));

        new AlertDialog.Builder(this)
                .setTitle(up.name)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始下载", (d, w) -> {
                    int n = 20;
                    try {
                        n = Integer.parseInt(cnt.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    if (n < 1) n = 1;
                    if (n > fresh.size()) n = fresh.size();
                    List<String> urls = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        urls.add("https://www.bilibili.com/video/" + fresh.get(i).bvid);
                    }
                    doEnqueue(urls);
                    toast("已加入 " + n + " 个任务，慢慢下");
                })
                .show();
    }

    private void syncBusy() {
        if (chipStop != null) {
            chipStop.setVisibility(Jobs.busy() ? View.VISIBLE : View.GONE);
        }
        boolean busy = Jobs.busy() || Jobs.pending() > 0;
        goBtn.setText(busy ? "处理中" : "开始");
        goBtn.setBackground(round(busy ? PINK_D : PINK, 999));
        if (busy) {
            goBtn.setOnClickListener(null);
        } else {
            goBtn.setOnClickListener(v -> start());
        }
        if (!busy) {
            reloadLibrary();
            ui.postDelayed(() -> showProgress(false), 1200);
        }
    }

    private void hideKeyboard() {
        ui.postDelayed(() -> {
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                input.clearFocus();
            } catch (Exception ignored) {
            }
        }, 220);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void log(String s) {
        lastLog = s;
        refreshStatus();
    }

    /** 自动挂载开着、但复制监听没连上时，主界面顶部提示一句 */
    private void refreshAccBanner() {
        if (accBanner == null) return;
        boolean attach = prefs.getBoolean("attach", false);
        boolean live = ClipWatchService.connected
                || ClipWatchService.enabledInSettings(this);
        if (!attach) {
            accBanner.setVisibility(View.GONE);
            return;
        }
        if (live) {
            accBanner.setVisibility(View.GONE);
            return;
        }
        accBanner.setText("⚠ 复制监听已关闭（复制链接不会自动处理）· 点这里开启");
        accBanner.setVisibility(View.VISIBLE);
    }

    private void refreshStatus() {
        if (statusLine == null) return;
        boolean busy = Jobs.busy() || Jobs.pending() > 0;
        if (busy) {
            StringBuilder sb = new StringBuilder("处理中 " + lastPct + "%");
            if (pendingCount > 1) sb.append(" · 还有 ").append(pendingCount - 1).append(" 个排队");
            if (!lastLog.isEmpty()) sb.append(" · ").append(stripEmoji(lastLog));
            statusLine.setText(sb.toString());
        } else {
            statusLine.setText(lastLog);
        }
    }

    private static String stripEmoji(String s) {
        return s.replace("🔗", "").replace("⚠", "").trim();
    }

    // ═══════════════════════ Jobs 回调 ═══════════════════════

    @Override
    public void onLog(String s) {
        log(s);
    }

    @Override
    public void onProgress(int pct) {
        lastPct = Math.max(0, Math.min(100, pct));
        bar.setProgress(lastPct);
        refreshStatus();
    }

    @Override
    public void onFinished(String error) {
        if (error == null) scrollTopNext = true;    // 下载完成：回到顶部看新视频
        if (error != null) {
            toast(error);
            String failed = Jobs.lastFailedUrl;
            if (failed != null) prefs.edit().putString("lastFailUrl", failed).apply();
            if (chipRetry != null && failed != null) {
                chipRetry.setVisibility(View.VISIBLE);
            }
        }
        reloadLibrary();
        ui.postDelayed(this::syncBusy, 400);
    }

    @Override
    public void onAsk(Pipeline.NeedConfirm nc) {
        if ("dup".equals(nc.kind)) {
            new AlertDialog.Builder(this)
                    .setTitle("已经处理过")
                    .setMessage("《" + nc.title + "》\n\n上次处理：" + nc.oldInfo
                            + "\n再处理一遍会覆盖片库里同名的旧文件。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("仍然处理", (d, w) ->
                            Jobs.enqueue(nc.url, limitMb, wantParts, true))
                    .show();
            return;
        }

        // 系列 / 合集：一条链接背后是一整个系列
        if ("season".equals(nc.kind)) {
            List<BiliApi.Episode> eps = nc.seasonList;
            if (eps == null || eps.size() < 2) {
                toast("合集信息拿不到，只能单独处理这一条");
                return;
            }
            String[] arr = new String[eps.size()];
            for (int i = 0; i < eps.size(); i++) {
                BiliApi.Episode e = eps.get(i);
                arr[i] = e.bvid + "\u0001" + e.seconds + "\u0001" + e.title;
            }
            Intent sit = new Intent(this, PartsActivity.class);
            sit.putExtra("mode", "season");
            sit.putExtra("title", nc.title);
            sit.putExtra("bvid", nc.bvid);
            sit.putExtra("season", arr);
            startActivity(sit);
            return;
        }

        // 多分P：打开选集页，让他自己勾
        List<BiliApi.Page> list = nc.pageList;
        if (list == null || list.isEmpty()) {
            toast("拿不到这个视频的分P列表");
            return;
        }
        String[] pages = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            BiliApi.Page pg = list.get(i);
            pages[i] = pg.page + "\u0001" + pg.seconds + "\u0001" + pg.title;
        }
        Intent it = new Intent(this, PartsActivity.class);
        it.putExtra("mode", "parts");
        it.putExtra("bvid", nc.bvid);
        it.putExtra("title", nc.title);
        it.putExtra("url", nc.url);
        it.putExtra("pages", pages);
        it.putExtra("prefer", nc.preferPage);
        startActivity(it);
    }

    /** 给链接换成第 p P（先去掉原有 p 参数） */
    private static String withPage(String url, int p) {
        String u = url.replaceAll("[?&]p=\\d+", "");
        return u + (u.contains("?") ? "&" : "?") + "p=" + p;
    }

    @Override
    public void onQueueChanged(int pending) {
        pendingCount = pending;
        refreshStatus();
        ui.postDelayed(this::syncBusy, pending == 0 ? 0 : 400);
    }

    // ═══════════════════════ 片库 ═══════════════════════

    private int libGen = 0;
    private String lastLibSig = "";
    private String lastOrientForSig = "v";
    private final Runnable reloadTask = this::doReloadLibrary;

    /**
     * 去抖 + 后台查询：MediaStore 查询和过滤排序都放到工作线程，
     * 主线程只负责把结果铺上去（条目多了也不会卡界面）。
     */
    private void reloadLibrary() {
        ui.removeCallbacks(reloadTask);
        ui.postDelayed(reloadTask, 60);
    }

    private void doReloadLibrary() {
        if (libraryBox == null) return;
        final int gen = ++libGen;
        final String q = query;
        final String srt = sort;
        new Thread(() -> {
            List<Library.Group> groups;
            try {
                groups = prepareGroups(q, srt);
            } catch (Exception e) {
                groups = new ArrayList<>();
            }
            final List<Library.Group> out = groups;
            final String sig = lastOrientForSig + "|" + sigOf(out, q, srt);
            ui.post(() -> {
                if (gen != libGen || libraryBox == null) return;
                // 数据完全没变就别重建视图：进度回调、返回前台都会触发刷新
                if (sig.equals(lastLibSig)) return;
                lastLibSig = sig;
                renderGroups(out, q);
            });
        }, "lib-load").start();
    }

    /** 内容指纹：标题/文件名/大小/模式，任何一项变了才需要重建 */
    private static String sigOf(List<Library.Group> gs, String q, String srt) {
        StringBuilder sb = new StringBuilder();
        sb.append(srt).append('|').append(q).append('|');
        for (Library.Group g : gs) {
            sb.append(g.title).append('#').append(g.items.size()).append('{');
            for (Library.Item it : g.items) {
                sb.append(it.name).append(':').append(it.size).append(',');
            }
            sb.append('}');
        }
        return String.valueOf(sb.toString().hashCode());
    }

    /** 后台线程：查询 + 过滤 + 排序 */
    private List<Library.Group> prepareGroups(String query, String sort) {
        List<Library.Group> groups = Library.load(this);
        if (!TextUtils.isEmpty(query)) {
            String q = query.toLowerCase(Locale.ROOT);
            List<Library.Group> kept = new ArrayList<>();
            for (Library.Group g : groups) {
                boolean titleHit = g.title.toLowerCase(Locale.ROOT).contains(q);
                List<Library.Item> hits = new ArrayList<>();
                for (Library.Item it : g.items) {
                    if (titleHit || it.name.toLowerCase(Locale.ROOT).contains(q)) hits.add(it);
                }
                if (!hits.isEmpty()) {
                    Library.Group ng = new Library.Group();
                    ng.title = g.title;
                    ng.relPath = g.relPath;
                    ng.items.addAll(hits);
                    for (Library.Item it : hits) ng.bytes += it.size;
                    kept.add(ng);
                }
            }
            groups = kept;
        }
        if (onlyUnsent) {
            java.util.Set<String> sent = wxSentSet();
            List<Library.Group> kept = new ArrayList<>();
            for (Library.Group g : groups) {
                Library.Group ng = new Library.Group();
                ng.title = g.title;
                ng.relPath = g.relPath;
                for (Library.Item it : g.items) {
                    if (!sent.contains(wxKey(it))) {
                        ng.items.add(it);
                        ng.bytes += it.size;
                        ng.durMs += it.durMs;
                    }
                }
                if (!ng.items.isEmpty()) kept.add(ng);
            }
            groups = kept;
        }
        for (Library.Group g : groups) {
            // 组内永远按文件名排（01/02/03 顺序），只有显式选了「大小」才按体积。
            // 按入库时间排会把同一秒入库的两段顺序打乱，出现 2/2 排在 1/2 前面。
            if ("size".equals(sort)) {
                Collections.sort(g.items, (a, b) -> Long.compare(b.size, a.size));
            } else {
                Collections.sort(g.items, (a, b) -> a.name.compareToIgnoreCase(b.name));
            }
        }
        if ("new".equals(sort)) {
            Collections.sort(groups, (a, b) -> {
                long ta = a.items.isEmpty() ? 0 : a.items.get(0).dateAdded;
                long tb = b.items.isEmpty() ? 0 : b.items.get(0).dateAdded;
                return Long.compare(tb, ta);
            });
        }

        return groups;
    }

    /** 让用户去开「所有文件访问」，开了才能删别处拷进来的片段 */
    private void askAllFiles() {
        new AlertDialog.Builder(this)
                .setTitle("删不掉这几个文件")
                .setMessage("它们不是本应用写入的，安卓不允许直接删。\n"
                        + "给「所有文件访问」权限后就能删了。")
                .setNegativeButton("算了", null)
                .setPositiveButton("去开启", (d, w) -> {
                    try {
                        Intent i = new Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .show();
    }

    /** 片库多选时的操作条 */
    private void buildPickBar() {
        pickBar = new LinearLayout(this);
        pickBar.setOrientation(LinearLayout.HORIZONTAL);
        pickBar.setGravity(Gravity.CENTER_VERTICAL);
        pickBar.setBackground(round(0xFFFFF0F5, 12));
        pickBar.setPadding(dp(12), dp(8), dp(12), dp(8));

        pickInfo = tv("", 12, INK, true);
        pickBar.addView(pickInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView all = tv("全选", 12, BLUE, true);
        all.setPadding(dp(10), dp(6), dp(10), dp(6));
        all.setOnClickListener(v -> {
            picked.clear();
            for (Library.Group g : lastGroups) {
                for (Library.Item it : g.items) picked.add(it.id);
            }
            renderGroups(lastGroups, "");
        });
        TextView mark = tv("标已发", 12, BLUE, true);
        mark.setPadding(dp(10), dp(6), dp(10), dp(6));
        mark.setOnClickListener(v -> {
            if (picked.isEmpty()) return;
            java.util.Set<String> set = wxSentSet();
            int n = 0;
            for (Library.Group g : lastGroups) {
                for (Library.Item it : g.items) {
                    if (picked.contains(it.id) && set.add(wxKey(it))) n++;
                }
            }
            prefs.edit().putStringSet("wxSent", set).apply();
            toast("已标记 " + n + " 段为已发");
            picking = false;
            picked.clear();
            reloadLibrary();
        });
        TextView del = tv("删除", 12, PINK, true);
        del.setPadding(dp(10), dp(6), dp(10), dp(6));
        del.setOnClickListener(v -> deletePicked());
        TextView close = tv("取消", 12, SUB, true);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setOnClickListener(v -> {
            picking = false;
            picked.clear();
            renderGroups(lastGroups, "");
        });
        pickBar.addView(all);
        pickBar.addView(mark);
        pickBar.addView(del);
        pickBar.addView(close);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        ((LinearLayout) libraryBox.getParent()).addView(pickBar, indexOfChildInParent(), lp);
        pickBar.setVisibility(View.GONE);
    }

    private int indexOfChildInParent() {
        android.view.ViewParent p = libraryBox.getParent();
        if (p instanceof LinearLayout) {
            return ((LinearLayout) p).indexOfChild(libraryBox);
        }
        return 0;
    }

    private void refreshPickBar() {
        if (pickInfo != null) pickInfo.setText("已选 " + picked.size() + " 段");
        pickBar.setVisibility(picking ? View.VISIBLE : View.GONE);
    }

    private void enterPick(Library.Item it) {
        picking = true;
        picked.clear();
        picked.add(it.id);
        renderGroups(lastGroups, "");
        toast("再点其它项可以多选");
    }

    /** 这一组里已选了几段 */
    private int pickedInGroup(Library.Group g) {
        int n = 0;
        for (Library.Item it : g.items) if (picked.contains(it.id)) n++;
        return n;
    }

    /** 整组选中 / 整组取消 */
    private void toggleGroup(Library.Group g) {
        boolean allIn = !g.items.isEmpty() && pickedInGroup(g) == g.items.size();
        for (Library.Item it : g.items) {
            if (allIn) picked.remove(it.id);
            else picked.add(it.id);
        }
        renderGroups(lastGroups, "");
        toast(allIn ? ("已取消整组 " + g.items.size() + " 段")
                : ("已选整组 " + g.items.size() + " 段"));
    }

    private void togglePick(Library.Item it) {
        if (picked.contains(it.id)) picked.remove(it.id);
        else picked.add(it.id);
        if (picked.isEmpty()) picking = false;
        renderGroups(lastGroups, "");
    }

    private void deletePicked() {
        if (picked.isEmpty()) return;
        final int n = picked.size();
        new AlertDialog.Builder(this)
                .setMessage("删除选中的 " + n + " 段？文件会从手机里真正删掉。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    int ok = 0;
                    for (Library.Group g : lastGroups) {
                        for (Library.Item it : g.items) {
                            if (picked.contains(it.id) && Library.delete(this, it)) ok++;
                        }
                    }
                    if (ok == 0 && !Library.hasAllFiles(this)) {
                        askAllFiles();
                        return;
                    }
                    // 组里删空了就把文件夹也收掉，别在下载目录留一堆空壳
                    for (Library.Group g : lastGroups) {
                        boolean allGone = true;
                        for (Library.Item it : g.items) {
                            if (!picked.contains(it.id)) { allGone = false; break; }
                        }
                        if (allGone && g.relPath != null) {
                            try {
                                new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                                        g.relPath).delete();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    toast("已删除 " + ok + " 段");
                    picking = false;
                    picked.clear();
                    reloadLibrary();
                })
                .show();
    }

    /** 主线程：统计、空态、铺卡片 */
    private void renderGroups(List<Library.Group> groups, String query) {
        final int keepY = svRoot == null ? 0 : svRoot.getScrollY();
        libraryBox.removeAllViews();

        int files = 0;
        long bytes = 0;
        long durAll = 0;
        for (Library.Group g : groups) {
            files += g.items.size();
            bytes += g.bytes;
            durAll += g.durMs;
        }
        if (svRoot != null) {
            if (scrollTopNext) {
                scrollTopNext = false;
                svRoot.post(() -> svRoot.scrollTo(0, 0));
            } else if (keepY > 0) {
                svRoot.post(() -> svRoot.scrollTo(0, keepY));
            }
        }
        int unsent = 0;
        for (Library.Group g : groups) {
            for (Library.Item it : g.items) if (!isWxSent(it)) unsent++;
        }
        String unsentPart = (unsent > 0 && unsent < files) ? (" · 未发 " + unsent + " 段") : "";
        libStat.setText(groups.isEmpty() ? "" : "· " + groups.size() + " 个视频 "
                + files + " 段 " + Library.mb(bytes)
                + (durAll > 0 ? " · " + fmtClock(durAll) : "")
                + unsentPart);

        lastGroups = groups;
        if (!groups.isEmpty()) libLoaded = true;
        if (pickBar == null) buildPickBar();
        pickBar.setVisibility(picking ? View.VISIBLE : View.GONE);
        if (chipPick != null) {
            chipPick.setText(picking ? "完成" : "选择");
            chipPick.setTextColor(picking ? PINK : SUB);
        }
        if (picking) refreshPickBar();

        if (groups.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(40), dp(20), dp(40));
            TextView t1 = tv(!libLoaded ? "正在读取片库…"
                            : TextUtils.isEmpty(query) ? "还没有片子" : "没搜到",
                    15, SUB, true);
            t1.setGravity(Gravity.CENTER);
            TextView t2 = tv(TextUtils.isEmpty(query)
                    ? "复制一个 B站链接，或点上面的「○ 自动识别」" : "换个关键词试试", 12, GRAY, false);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, dp(6), 0, 0);
            empty.addView(t1);
            empty.addView(t2);
            libraryBox.addView(empty);
            return;
        }
        for (Library.Group g : groups) libraryBox.addView(groupCard(g), lpTop(10));
    }

    private View groupCard(Library.Group g) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        card.setElevation(dp(1));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(g.title, 14, INK, true);
        t.setMaxLines(2);
        titles.addView(t);
        final boolean open0 = expanded.contains(g.relPath);
        if (!picking && g.durMs >= 0 && g.items.size() > 2) {
            titles.setOnClickListener(v -> {
                if (expanded.contains(g.relPath)) expanded.remove(g.relPath);
                else expanded.add(g.relPath);
                renderGroups(lastGroups, "");
            });
        }
        String gMeta = g.items.size() + " 段 · " + Library.mb(g.bytes);
        if (g.durMs > 0) gMeta += " · " + fmtClock(g.durMs);
        int inGroup = pickedInGroup(g);
        if (picking && inGroup > 0) gMeta += "（已选 " + inGroup + "/" + g.items.size() + "）";
        titles.addView(tv(gMeta, 11, GRAY, false));
        head.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 微信已发进度：全部发过打勾，发了一部分显示 2/4
        int sentN = 0;
        for (Library.Item it : g.items) if (isWxSent(it)) sentN++;
        if (sentN > 0 && !picking) {
            boolean allSent = sentN == g.items.size();
            TextView wxTag = chip(allSent ? "✓ 已发" : ("已发 " + sentN + "/" + g.items.size()),
                    allSent ? 0xFFECEEF1 : 0xFFEFF6FF,
                    allSent ? 0xFF9AA0A6 : BLUE, 10.5f);
            wxTag.setPadding(dp(8), dp(3), dp(8), dp(5));
            LinearLayout.LayoutParams wt = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            wt.rightMargin = dp(6);
            head.addView(wxTag, wt);
        }

        if (picking) {
            boolean allIn = !g.items.isEmpty() && inGroup == g.items.size();
            TextView pickGroup = chip(allIn ? "✓ 整组" : "选整组",
                    allIn ? PINK : CHIP, allIn ? Color.WHITE : SUB, 11);
            pickGroup.setPadding(dp(9), dp(4), dp(9), dp(6));
            pickGroup.setOnClickListener(v -> toggleGroup(g));
            titles.setOnClickListener(v -> toggleGroup(g));
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.rightMargin = dp(6);
            head.addView(pickGroup, gp);
        }
        if (!picking && g.items.size() > 1) {
            TextView sendAll = chip("发整组", 0xFFFFF0F5, PINK, 11);
            sendAll.setPadding(dp(9), dp(4), dp(9), dp(6));
            sendAll.setOnClickListener(v -> shareGroup(g));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.rightMargin = dp(6);
            head.addView(sendAll, sp);
        }
        TextView more = chip("⋯", CHIP, SUB, 13);
        more.setPadding(dp(12), dp(4), dp(12), dp(6));
        more.setOnClickListener(v -> groupMenu(g));
        head.addView(more);
        card.addView(head);

        if ("h".equals(orient)) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (Library.Item it : g.items) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(148),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                p.rightMargin = dp(10);
                p.topMargin = dp(10);
                row.addView(cardH(it), p);
            }
            hs.addView(row);
            card.addView(hs);
        } else {
            boolean open = expanded.contains(g.relPath);
            int limit = open ? g.items.size() : Math.min(2, g.items.size());
            for (int i = 0; i < limit; i++) card.addView(rowV(g.items.get(i)));
            if (g.items.size() > 2) {
                TextView unfold = tv(open
                                ? "收起"
                                : ("展开全部 " + g.items.size() + " 段"),
                        11, BLUE, true);
                unfold.setGravity(Gravity.CENTER);
                unfold.setPadding(0, dp(10), 0, dp(2));
                unfold.setOnClickListener(v -> {
                    if (open) expanded.remove(g.relPath);
                    else expanded.add(g.relPath);
                    renderGroups(lastGroups, "");
                });
                card.addView(unfold);
            }
        }
        return card;
    }

    private ImageView cover(Library.Item it, int wDp, int hDp) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ph = round(0xFFE9EBEE, 10);
        iv.setBackground(ph);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        Thumbs.load(this, String.valueOf(it.id), it.uri, dp(wDp), bm -> {
            if (bm != null) iv.setImageBitmap(bm);
        });
        iv.setOnClickListener(v -> {
            if (picking) togglePick(it);
            else play(it);
        });
        iv.setOnLongClickListener(v -> {
            if (!picking) enterPick(it);
            return true;
        });
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return iv;
    }

    private View coverWithBadge(Library.Item it, int wDp, int hDp, String badge) {
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        wrap.addView(cover(it, wDp, hDp));

        if (badge != null && !badge.isEmpty()) {
            TextView b = tv(badge, 9, Color.WHITE, true);
            b.setPadding(dp(5), dp(2), dp(5), dp(2));
            b.setBackground(round(0x99000000, 6));
            android.widget.FrameLayout.LayoutParams bp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.gravity = Gravity.BOTTOM | Gravity.END;
            bp.rightMargin = dp(4);
            bp.bottomMargin = dp(4);
            wrap.addView(b, bp);
        }
        wrap.setOnClickListener(v -> {
            if (picking) togglePick(it);
            else play(it);
        });
        wrap.setOnLongClickListener(v -> {
            if (!picking) enterPick(it);
            return true;
        });
        return wrap;
    }

    private View rowV(Library.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);

        row.addView(coverWithBadge(it, 104, 59, segBadge(it)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, 0, 0);

        TextView name = tv(it.name, 12.5f, INK, false);
        name.setMaxLines(2);
        info.addView(name);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(5), 0, 0);
        line.addView(tv(Library.mb(it.size) + (it.durMs > 0 ? " · " + fmtClock(it.durMs) : ""),
                        11, GRAY, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView wx = chip("微信", WECHAT, Color.WHITE, 11);
        styleWx(wx, isWxSent(it));
        wx.setOnClickListener(v -> {
            shareWeChat(it);
            markWxSent(it, wx);
        });
        TextView more = chip("⋯", CHIP, SUB, 12);
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g.leftMargin = dp(6);
        line.addView(wx, g);
        line.addView(more, g);
        more.setOnClickListener(v -> itemMenu(it));
        info.addView(line);

        row.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View cardH(Library.Item it) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);

        c.addView(coverWithBadge(it, 148, 83, segBadge(it)));

        TextView name = tv(it.name, 12, INK, false);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(6), 0, 0);
        c.addView(name);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(4), 0, 0);
        // 单段行也把时长带上，挑片段时不用点进去
        TextView sz = tv(Library.mb(it.size) + (it.durMs > 0 ? " · " + fmtClock(it.durMs) : ""),
                10.5f, GRAY, false);
        sz.setMaxLines(1);
        line.addView(sz, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView wx = chip("微信", WECHAT, Color.WHITE, 10.5f);
        wx.setPadding(dp(8), dp(5), dp(8), dp(5));
        styleWx(wx, isWxSent(it));
        wx.setOnClickListener(v -> {
            shareWeChat(it);
            markWxSent(it, wx);
        });
        TextView more = chip("⋯", CHIP, SUB, 11);
        more.setPadding(dp(8), dp(4), dp(8), dp(6));
        more.setOnClickListener(v -> itemMenu(it));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g.leftMargin = dp(4);
        line.addView(wx, g);
        line.addView(more, g);
        c.addView(line);
        return c;
    }

    /** 从文件名里取 "1/2" 这种分段标记 */
    private static String segBadge(Library.Item it) {
        Matcher m = Pattern.compile("_(\\d+)of(\\d+)").matcher(it.name);
        if (m.find()) return m.group(1) + "/" + m.group(2);
        return "";
    }

    // ═══════════════════════ 播放 / 分享 ═══════════════════════

    private void play(Library.Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(it.uri, "video/mp4");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("没有可用的播放器");
        }
    }

    private interface ShareAction {
        void run(Uri uri, String name);
    }

    /** 整组一次分享：把这一组片段都塞进同一个分享（微信里可以一次发多个视频） */
    private void markGroupSent(Library.Group g) {
        java.util.Set<String> set = wxSentSet();
        boolean changed = false;
        for (Library.Item it : g.items) changed |= set.add(wxKey(it));
        if (changed) {
            prefs.edit().putStringSet("wxSent", set).apply();
            renderGroups(lastGroups, "");
        }
    }

    private void shareGroup(Library.Group g) {
        if (g.items == null || g.items.isEmpty()) return;
        int sentN = 0;
        for (Library.Item it : g.items) if (isWxSent(it)) sentN++;
        if (sentN == g.items.size()) {
            new AlertDialog.Builder(this)
                    .setTitle("这组都发过了")
                    .setMessage("「" + g.title + "」的 " + sentN + " 段都标记成已发过，还要再发一次吗？")
                    .setNegativeButton("算了", null)
                    .setPositiveButton("再发一次", (d, w) -> doShareGroup(g))
                    .show();
            return;
        }
        if (sentN > 0) toast("其中 " + sentN + " 段发过了，一起再发一次");
        doShareGroup(g);
    }

    private void doShareGroup(Library.Group g) {
        toast("正在准备 " + g.items.size() + " 个文件…");
        new Thread(() -> {
            final java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            try {
                for (Library.Item it : g.items) {
                    File dest = new File(VideoProvider.dir(this), it.id + "_" + it.name);
                    if (!dest.exists() || dest.length() != it.size) {
                        try (java.io.InputStream in = getContentResolver().openInputStream(it.uri);
                             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                            if (in == null) continue;
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                    uris.add(VideoProvider.uriFor(dest));
                }
            } catch (Exception e) {
                ui.post(() -> toast("准备文件失败：" + e.getMessage()));
                return;
            }
            ui.post(() -> {
                if (uris.isEmpty()) {
                    toast("没有可分享的文件");
                    return;
                }
                Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("video/mp4");
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(i, "发送 " + uris.size() + " 个视频"));
                    markGroupSent(g);
                } catch (Exception e) {
                    toast("打开分享失败：" + e.getMessage());
                }
            });
        }, "share-group").start();
    }

    private void withShareUri(Library.Item it, ShareAction action) {
        new Thread(() -> {
            try {
                File dest = new File(VideoProvider.dir(this), it.id + "_" + it.name);
                if (!dest.exists() || dest.length() != it.size) {
                    try (java.io.InputStream in = getContentResolver().openInputStream(it.uri);
                         java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                        if (in == null) throw new Exception("读不到文件");
                        // 走通道传输，别再 64KB 一趟趟搬
                        java.nio.channels.WritableByteChannel oc =
                                java.nio.channels.Channels.newChannel(out);
                        if (in instanceof java.io.FileInputStream) {
                            java.nio.channels.FileChannel ic =
                                    ((java.io.FileInputStream) in).getChannel();
                            long total = ic.size(), done = 0;
                            while (done < total) {
                                long n = ic.transferTo(done, total - done, oc);
                                if (n <= 0) break;
                                done += n;
                            }
                        } else {
                            byte[] buf = new byte[1 << 20];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                }
                Uri shareUri = VideoProvider.uriFor(dest);
                ui.post(() -> action.run(shareUri, it.name));
            } catch (Exception e) {
                ui.post(() -> toast("准备文件失败：" + e.getMessage()));
            }
        }, "share-prep").start();
    }

    private Intent sendIntent(Uri uri, String name) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_TITLE, name);
        i.setClipData(ClipData.newRawUri("video", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    /** 已通过微信发过的片段（relPath/name 作为键，重装/重建媒体库也不会丢） */
    private java.util.Set<String> wxSentSet() {
        return new java.util.HashSet<>(prefs.getStringSet("wxSent", new java.util.HashSet<>()));
    }

    private String wxKey(Library.Item it) {
        return it.relPath + "|" + it.name;
    }

    private boolean isWxSent(Library.Item it) {
        return wxSentSet().contains(wxKey(it));
    }

    private void markWxSent(Library.Item it, TextView btn) {
        java.util.Set<String> set = wxSentSet();
        if (set.add(wxKey(it))) {
            prefs.edit().putStringSet("wxSent", set).apply();
        }
        styleWx(btn, true);
    }

    private void styleWx(TextView v, boolean sent) {
        if (v == null) return;
        v.setText(sent ? "已发" : "微信");
        v.setTextColor(sent ? 0xFF9AA0A6 : Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setColor(sent ? 0xFFE9EBEE : WECHAT);
        d.setCornerRadius(dp(999));
        v.setBackground(d);
    }

    private void shareWeChat(Library.Item it) {
        withShareUri(it, (uri, name) -> {
            Intent direct = sendIntent(uri, name);
            direct.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
            try {
                startActivity(direct);
                return;
            } catch (Exception ignored) {
            }
            Intent pkg = sendIntent(uri, name);
            pkg.setPackage("com.tencent.mm");
            try {
                startActivity(pkg);
            } catch (Exception e) {
                toast("没装微信，改用系统分享");
                startActivity(Intent.createChooser(sendIntent(uri, name), "发送视频"));
            }
        });
    }

    private void shareMoment(Library.Item it) {
        withShareUri(it, (uri, name) -> {
            Intent i = sendIntent(uri, name);
            i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareToTimeLineUI");
            try {
                startActivity(i);
            } catch (Exception e) {
                toast("这个微信版本不支持直接发朋友圈");
            }
        });
    }

    private void shareGeneric(Library.Item it) {
        withShareUri(it, (uri, name) ->
                startActivity(Intent.createChooser(sendIntent(uri, name), "发送视频")));
    }

    // ═══════════════════════ 菜单 ═══════════════════════

    private void itemMenu(Library.Item it) {
        String[] opts = {"发给微信好友", "发朋友圈", "分享到其他应用",
                "重命名", "复制文件名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0: shareWeChat(it); break;
                        case 1: shareMoment(it); break;
                        case 2: shareGeneric(it); break;
                        case 3: renameDialog(it); break;
                        case 4: copyName(it); break;
                        default: confirmDelete(it); break;
                    }
                })
                .show();
    }

    private void copyName(Library.Item it) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("name", it.name));
                toast("已复制文件名");
            }
        } catch (Exception ignored) {
        }
    }

    private void groupMenu(Library.Group g) {
        int sentCount = 0;
        for (Library.Item it : g.items) if (isWxSent(it)) sentCount++;
        final boolean allSentGroup = sentCount >= g.items.size();
        String[] opts = {"播放第一段", "一次发整组（微信多选）", "第一段发微信",
                "复制全部文件名",
                allSentGroup ? "取消「已发」标记" : "标记整组为已发",
                "删除整组"};
        new AlertDialog.Builder(this)
                .setTitle(g.title)
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        if (!g.items.isEmpty()) play(g.items.get(0));
                    } else if (which == 1) {
                        shareGroup(g);
                    } else if (which == 2) {
                        if (!g.items.isEmpty()) shareWeChat(g.items.get(0));
                    } else if (which == 4) {
                        java.util.Set<String> set = wxSentSet();
                        if (allSentGroup) {
                            for (Library.Item it : g.items) set.remove(wxKey(it));
                            toast("已取消整组标记");
                        } else {
                            for (Library.Item it : g.items) set.add(wxKey(it));
                            toast("已标记整组 " + g.items.size() + " 段为已发");
                        }
                        prefs.edit().putStringSet("wxSent", set).apply();
                        renderGroups(lastGroups, "");
                    } else if (which == 3) {
                        StringBuilder sb = new StringBuilder();
                        for (Library.Item x : g.items) sb.append(x.name).append('\n');
                        try {
                            ClipboardManager cm = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("names", sb.toString()));
                                toast("已复制 " + g.items.size() + " 个文件名");
                            }
                        } catch (Exception ignored) {
                        }
                    } else if (which == 5) {
                        new AlertDialog.Builder(this)
                                .setMessage("删除这一组的 " + g.items.size() + " 个文件？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除整组", (dd, w) -> {
                                    for (Library.Item it : g.items) Library.delete(this, it);
                                    reloadLibrary();
                                })
                                .show();
                    }
                })
                .show();
    }

    private void renameDialog(Library.Item it) {
        EditText e = new EditText(this);
        String cur = it.name;
        if (cur.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            cur = cur.substring(0, cur.length() - 4);
        }
        e.setText(cur);
        e.setSelection(cur.length());
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(e)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    if (Library.rename(this, it, e.getText().toString())) reloadLibrary();
                    else toast("改名失败");
                })
                .show();
    }

    private void confirmDelete(Library.Item it) {
        new AlertDialog.Builder(this)
                .setMessage("删除「" + it.name + "」？\n文件会从手机里真正删掉。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    if (Library.delete(this, it)) {
                        Store.dropIfEmpty(it.relPath);   // 删空了就把文件夹一起收掉
                        toast("已删除");
                        reloadLibrary();
                    } else {
                        toast("删除失败");
                    }
                })
                .show();
    }

    private void cleanShareCache() {
        new Thread(() -> {
            try {
                File[] fs = VideoProvider.dir(this).listFiles();
                if (fs == null) return;
                long now = System.currentTimeMillis();
                for (File f : fs) {
                    if (now - f.lastModified() > 30 * 60 * 1000L) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            } catch (Exception ignored) {
            }
        }).start();
    }
}
