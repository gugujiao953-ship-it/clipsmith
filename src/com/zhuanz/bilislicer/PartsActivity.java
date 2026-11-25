package com.zhuanz.bilislicer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 选集页，两种用法：
 *  - parts：一个视频里的多个分P
 *  - season：B站合集 / 系列里的一串视频
 * 已下载过的会变灰并标注，支持长按拖动连续勾选。
 */
public class PartsActivity extends Activity {

    @Override
    protected void onResume() {
        super.onResume();
        ClipService.appVisible = true;
    }

    @Override
    protected void onPause() {
        ClipService.appVisible = false;
        super.onPause();
    }


    private static final int PINK = 0xFFFB7299;
    private static final int INK = 0xFF18191C;
    private static final int SUB = 0xFF61666D;
    private static final int GRAY = 0xFF9499A0;
    private static final int DIM = 0xFFB0B4BA;
    private static final int BG = 0xFFF1F2F4;
    private static final int CHIP = 0xFFEDEFF2;

    private String mode = "parts";
    private String title = "";
    private String bvid = "";

    private final List<String> labels = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<Integer> secs = new ArrayList<>();
    private final List<String> urls = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private boolean[] sel;

    private LinearLayout rows;
    private final List<View> rowViews = new ArrayList<>();
    private TextView countTv;
    private TextView goBtn;
    private boolean dragValue;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Jobs.init(this);
        Intent it = getIntent();
        mode = it.getStringExtra("mode");
        title = it.getStringExtra("title");
        bvid = it.getStringExtra("bvid");
        String baseUrl = it.getStringExtra("url");
        if (mode == null) mode = "parts";
        if (title == null) title = "";
        if (bvid == null) bvid = "";
        if (baseUrl == null) baseUrl = "";

        int prefer = it.getIntExtra("prefer", 0);

        if ("season".equals(mode)) {
            String[] eps = it.getStringArrayExtra("season");
            if (eps != null) {
                for (String raw : eps) {
                    String[] f = raw.split("\u0001", 3);
                    String bv = f.length > 0 ? f[0] : "";
                    int sec = 0;
                    try {
                        sec = f.length > 1 ? Integer.parseInt(f[1]) : 0;
                    } catch (Exception ignored) {
                    }
                    String nm = f.length > 2 ? f[2] : bv;
                    labels.add("第" + (labels.size() + 1) + "集");
                    titles.add(nm);
                    secs.add(sec);
                    urls.add("https://www.bilibili.com/video/" + bv);
                    keys.add(bv);
                }
            }
        } else {
            String[] pages = it.getStringArrayExtra("pages");
            if (pages != null) {
                for (String raw : pages) {
                    String[] f = raw.split("\u0001", 3);
                    int no = 1;
                    int sec = 0;
                    String nm = "";
                    try {
                        no = Integer.parseInt(f[0]);
                        sec = f.length > 1 ? Integer.parseInt(f[1]) : 0;
                        nm = f.length > 2 ? f[2] : "";
                    } catch (Exception ignored) {
                    }
                    labels.add("P" + no);
                    titles.add(nm);
                    secs.add(sec);
                    urls.add(withPage(baseUrl, no));
                    keys.add(bvid + "_p" + no);
                }
            }
        }
        sel = new boolean[labels.size()];

        if (prefer > 0 && "parts".equals(mode)) {
            for (int i = 0; i < sel.length; i++) {
                sel[i] = labels.get(i).equals("P" + prefer);
            }
        } else {
            boolean preselect = labels.size() <= 10;
            for (int i = 0; i < sel.length; i++) {
                sel[i] = preselect && !done(i);
            }
        }

        setContentView(build());
        refreshCount();
    }

    /** 这条是不是已经下载过 */
    private boolean done(int i) {
        return History.has(this, keys.get(i));
    }

    private static String withPage(String url, int p) {
        String u = url.replaceAll("[?&]p=\\d+", "");
        return u + (u.contains("?") ? "&" : "?") + "p=" + p;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(r));
        return d;
    }

    private TextView chip(String label, int bg, int fg, float sp) {
        TextView t = tv(label, sp, fg, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(round(bg, 999));
        return t;
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(PINK);
        head.setPadding(dp(18), dp(14), dp(14), dp(14));

        TextView back = tv("‹", 26, Color.WHITE, true);
        back.setPadding(dp(4), 0, dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        head.addView(back);

        LinearLayout titles2 = new LinearLayout(this);
        titles2.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = tv(title, 16, Color.WHITE, true);
        t1.setMaxLines(2);
        titles2.addView(t1);
        String sub = "season".equals(mode)
                ? ("共 " + labels.size() + " 集 · 每集当作独立视频切")
                : ("共 " + labels.size() + " 个分P · 每个都会当作独立视频切");
        TextView t2 = tv(sub, 11, 0xFFFFD9E5, false);
        t2.setPadding(0, dp(3), 0, 0);
        titles2.addView(t2);
        head.addView(titles2, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(12), dp(10), dp(12), dp(4));

        TextView all = chip("全选", CHIP, INK, 12);
        all.setOnClickListener(v -> {
            for (int i = 0; i < sel.length; i++) sel[i] = true;
            refreshAll();
        });
        TextView none = chip("全不选", CHIP, INK, 12);
        none.setOnClickListener(v -> {
            for (int i = 0; i < sel.length; i++) sel[i] = false;
            refreshAll();
        });
        TextView fresh = chip("只选没下过的", CHIP, INK, 12);
        fresh.setOnClickListener(v -> {
            for (int i = 0; i < sel.length; i++) sel[i] = !done(i);
            refreshAll();
        });

        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g.rightMargin = dp(8);
        tools.addView(all, g);
        tools.addView(none, g);
        tools.addView(fresh);
        root.addView(tools);

        TextView hint = tv("长按左侧勾选框后上下拖动可连续勾选；已下载的会变灰",
                11, GRAY, false);
        hint.setPadding(dp(14), dp(6), dp(12), dp(6));
        root.addView(hint);

        DragPickScroll scroller = new DragPickScroll(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(12), 0, dp(12), dp(12));
        for (int i = 0; i < labels.size(); i++) rows.addView(buildRow(i));
        scroller.addView(rows);
        scroller.setPickZone(dp(104));
        scroller.setPickListener(new DragPickScroll.Pick() {
            @Override
            public void onPick(float contentY, boolean start) {
                int idx = indexAt(contentY);
                if (idx < 0) return;
                if (start) dragValue = !sel[idx];
                if (sel[idx] != dragValue) {
                    sel[idx] = dragValue;
                    paintRow(idx);
                    refreshCount();
                }
            }

            @Override
            public void onEnd() {
            }
        });
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setBackgroundColor(Color.WHITE);
        footer.setPadding(dp(12), dp(10), dp(12), dp(10));

        countTv = tv("", 12, SUB, false);
        footer.addView(countTv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        goBtn = chip("下载选中", PINK, Color.WHITE, 14);
        goBtn.setPadding(dp(18), dp(11), dp(18), dp(11));
        goBtn.setOnClickListener(v -> submit());
        footer.addView(goBtn);
        root.addView(footer);
        return root;
    }

    private View buildRow(final int i) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(12), dp(10));

        TextView box = tv("", 13, Color.WHITE, true);
        box.setGravity(Gravity.CENTER);
        box.setTag("box");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(24), dp(24));
        bp.rightMargin = dp(10);
        row.addView(box, bp);

        TextView no = tv(labels.get(i), 11, SUB, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(54),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(no, np);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = tv(titles.get(i), 13, INK, false);
        name.setMaxLines(2);
        name.setTag("name");
        mid.addView(name);
        TextView sub = tv(subText(i), 11, GRAY, false);
        sub.setPadding(0, dp(3), 0, 0);
        sub.setTag("sub");
        mid.addView(sub);
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            sel[i] = !sel[i];
            paintRow(i);
            refreshCount();
        });

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(6);
        row.setLayoutParams(rp);

        rowViews.add(row);
        paintRow(i);
        return row;
    }

    private String subText(int i) {
        int sec = secs.get(i);
        StringBuilder sb = new StringBuilder();
        if (sec > 0) sb.append(fmtDur(sec)).append("　");
        sb.append(done(i) ? "已下载" : "未下载");
        return sb.toString();
    }

    private static String fmtDur(int sec) {
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.CHINA, "%d:%02d", m, s);
    }

    private void paintRow(int i) {
        View row = rowViews.get(i);
        TextView box = row.findViewWithTag("box");
        TextView name = row.findViewWithTag("name");
        TextView sub = row.findViewWithTag("sub");
        boolean isDone = done(i);

        if (box != null) {
            boolean on = sel[i];
            box.setText(on ? "✓" : "");
            GradientDrawable d = round(on ? PINK : 0xFFFFFFFF, 7);
            if (!on) d.setStroke(dp(1), isDone ? 0xFFE0E2E6 : 0xFFCFD3D8);
            box.setBackground(d);
        }
        if (name != null) {
            // 已下载的变灰，一眼看出哪些不用再下
            name.setTextColor(isDone ? DIM : (sel[i] ? INK : SUB));
        }
        if (sub != null) {
            sub.setTextColor(isDone ? DIM : GRAY);
            sub.setText(subText(i));
        }
        row.setBackground(round(isDone ? 0xFFFAFAFB : Color.WHITE, 10));
    }

    private void refreshAll() {
        for (int i = 0; i < sel.length; i++) paintRow(i);
        refreshCount();
    }

    private void refreshCount() {
        int n = 0;
        long secTotal = 0;
        for (int i = 0; i < sel.length; i++) {
            if (!sel[i]) continue;
            n++;
            if (i < secs.size() && secs.get(i) > 0) secTotal += secs.get(i);
        }
        // 粗估体积：码率上限 + 音频 64k，够判断「这一批大概多大」
        int kbps = getSharedPreferences("bili", MODE_PRIVATE).getInt("maxKbps", 900);
        if (kbps <= 0) kbps = 900;
        double mb = secTotal * (kbps + 64) / 8.0 / 1000.0;
        String est = secTotal > 0 ? (" · 约 " + Math.max(1, Math.round(mb)) + " MB") : "";
        countTv.setText("已选 " + n + " / " + sel.length + est);
        goBtn.setText(n > 0 ? ("下载选中的 " + n + " 个") : "下载选中");
        goBtn.setAlpha(n > 0 ? 1f : 0.5f);
    }

    private int indexAt(float contentY) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View c = rows.getChildAt(i);
            if (contentY >= c.getTop() && contentY < c.getBottom()) return i;
        }
        return -1;
    }

    private void submit() {
        int n = 0;
        for (boolean b : sel) if (b) n++;
        if (n == 0) {
            Toast.makeText(this, "先勾选要下载的项", Toast.LENGTH_SHORT).show();
            return;
        }
        if (n >= 8) {
            int kbps = getSharedPreferences("bili", MODE_PRIVATE).getInt("maxKbps", 900);
            if (kbps <= 0) kbps = 900;
            long secTotal = 0;
            for (int i = 0; i < sel.length; i++) {
                if (sel[i] && i < secs.size() && secs.get(i) > 0) secTotal += secs.get(i);
            }
            double mb = secTotal * (kbps + 64) / 8.0 / 1000.0;
            new AlertDialog.Builder(this)
                    .setTitle("一次处理 " + n + " 个")
                    .setMessage("预计约 " + Math.max(1, Math.round(mb)) + " MB，会排着队一个个来。"
                            + "\n勾错了可以取消后重新选。")
                    .setNegativeButton("再看看", null)
                    .setPositiveButton("开始", (d, w) -> reallySubmit())
                    .show();
            return;
        }
        reallySubmit();
    }

    private void reallySubmit() {
        SharedPreferences p = getSharedPreferences("bili", MODE_PRIVATE);
        double limit = p.getFloat("limit", 9.6f);
        int parts = p.getInt("parts", 0);
        int queued = 0;
        for (int i = 0; i < sel.length; i++) {
            if (!sel[i]) continue;
            Jobs.enqueue(urls.get(i), limit, parts, true);
            queued++;
        }
        // 不清空也不关页面：想再挑几个可以直接接着点，重复提交同一批不会被记住
        for (int i = 0; i < sel.length; i++) sel[i] = false;
        for (int i = 0; i < rows.getChildCount(); i++) paintRow(i);
        refreshCount();
        Toast.makeText(this, "已加入 " + queued + " 个任务，可继续选其他 P", Toast.LENGTH_SHORT).show();
    }

    // ══════════ 长按拖选用的 ScrollView ══════════

    static class DragPickScroll extends ScrollView {

        interface Pick {
            void onPick(float contentY, boolean start);

            void onEnd();
        }

        private Pick pick;
        private final Handler h = new Handler(Looper.getMainLooper());
        private boolean dragging;
        private float downX, downY;
        /** 只有这个横坐标以内的区域（左侧勾选框）才允许连选，避免滑列表时误触 */
        private float pickZone;

        private final Runnable longPress = () -> {
            if (pickZone > 0 && downX > pickZone) return;   // 按在标题区域：不连选，正常滚动
            dragging = true;
            if (pick != null) pick.onPick(getScrollY() + downY, true);
        };

        DragPickScroll(Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
        }

        void setPickListener(Pick p) {
            pick = p;
        }

        void setPickZone(float px) {
            pickZone = px;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    h.postDelayed(longPress, 450);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) return true;
                    if (Math.abs(e.getY() - downY) > dp(10)
                            || Math.abs(e.getX() - downX) > dp(20)) {
                        h.removeCallbacks(longPress);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(longPress);
                    break;
            }
            return super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (dragging) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        if (pick != null) pick.onPick(getScrollY() + e.getY(), false);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        if (pick != null) pick.onEnd();
                        break;
                }
                return true;
            }
            return super.onTouchEvent(e);
        }

        private int dp(float v) {
            return Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
        }
    }
}
