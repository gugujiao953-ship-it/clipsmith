package com.zhuanz.bilislicer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 处理记录：每页 50 条，可翻页、可重新处理 / 删除 */
public class HistoryActivity extends Activity {

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
    private static final int LINE = 0xFFE3E5E8;
    private static final int BG = 0xFFF1F2F4;
    private static final int CHIP = 0xFFEDEFF2;

    private LinearLayout listBox;
    private TextView pageInfo;
    private TextView prevBtn;
    private TextView nextBtn;
    private int page = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Jobs.init(this);
        setContentView(build());
        render();
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

        // 顶部
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(PINK);
        head.setPadding(dp(18), dp(16), dp(14), dp(14));

        TextView back = tv("‹", 26, Color.WHITE, true);
        back.setPadding(dp(4), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        head.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(tv("处理记录", 19, Color.WHITE, true));
        TextView sub = tv("已经切过的视频会在这里，重复粘贴会提示", 11, 0xFFFFD9E5, false);
        sub.setPadding(0, dp(3), 0, 0);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clearFail = chip("清除失败", 0x33FFFFFF, Color.WHITE, 12);
        clearFail.setOnClickListener(v -> {
            int n = History.clearFails(this);
            android.widget.Toast.makeText(this, n > 0 ? ("已清除 " + n + " 条失败记录") : "没有失败记录", android.widget.Toast.LENGTH_SHORT).show();
            render();
        });
        LinearLayout.LayoutParams cfp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cfp.rightMargin = dp(6);
        head.addView(clearFail, cfp);

        TextView clear = chip("清空", 0x33FFFFFF, Color.WHITE, 12);
        clear.setOnClickListener(v -> confirmClear());
        head.addView(clear);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部翻页条
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setBackgroundColor(Color.WHITE);
        footer.setPadding(dp(12), dp(10), dp(12), dp(10));

        prevBtn = chip("‹ 上一页", CHIP, INK, 13);
        prevBtn.setOnClickListener(v -> {
            if (page > 0) {
                page--;
                render();
                sv.scrollTo(0, 0);
            }
        });
        nextBtn = chip("下一页 ›", CHIP, INK, 13);
        nextBtn.setOnClickListener(v -> {
            if (page + 1 < History.pageCount(this)) {
                page++;
                render();
                sv.scrollTo(0, 0);
            }
        });

        footer.addView(prevBtn);
        pageInfo = tv("", 12, SUB, false);
        pageInfo.setGravity(Gravity.CENTER);
        footer.addView(pageInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footer.addView(nextBtn);
        root.addView(footer);
        return root;
    }

    private GradientDrawable cardBg() {
        GradientDrawable d = round(Color.WHITE, 12);
        d.setStroke(dp(1), LINE);
        return d;
    }

    private void render() {
        listBox.removeAllViews();
        List<History.Rec> recs = History.page(this, page);
        int total = History.all(this).size();
        int pages = History.pageCount(this);

        pageInfo.setText(pages <= 1 ? ("共 " + total + " 条")
                : ("第 " + (page + 1) + " / " + pages + " 页 · 共 " + total + " 条"));
        prevBtn.setAlpha(page > 0 ? 1f : 0.4f);
        nextBtn.setAlpha(page + 1 < pages ? 1f : 0.4f);

        if (recs.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(60), dp(20), dp(20));
            TextView t = tv("还没有处理记录", 15, SUB, true);
            t.setGravity(Gravity.CENTER);
            TextView t2 = tv("下载并切完一个视频后，这里会自动记一条", 12, GRAY, false);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, dp(6), 0, 0);
            empty.addView(t);
            empty.addView(t2);
            listBox.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (History.Rec r : recs) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(cardBg());
            card.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView title = tv(r.title, r.fail ? 12.5f : 13.5f,
                    r.fail ? 0xFFE85C86 : INK, !r.fail);
            title.setMaxLines(2);
            card.addView(title);

            // 长按记录可以复制原始链接（也方便重新处理）
            card.setOnLongClickListener(v -> {
                if (r.url == null || r.url.isEmpty()) {
                    android.widget.Toast.makeText(this, "这条没有原始链接",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", r.url));
                    getSharedPreferences("bili", MODE_PRIVATE).edit()
                            .putString("selfCopiedUrl", r.url).apply();
                    android.widget.Toast.makeText(this, "已复制链接",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            String plat = "bilibili".equals(r.platform) ? "B站"
                    : "douyin".equals(r.platform) ? "抖音"
                    : "kuaishou".equals(r.platform) ? "快手" : "";
            String meta;
            if (r.fail) {
                meta = "失败 · " + fmt.format(new Date(r.time))
                        + (r.note.isEmpty() ? "" : "\n" + r.note);
            } else {
                meta = (plat.isEmpty() ? "" : plat + " · ") + r.parts + " 段 · "
                        + Library.mb(r.size) + " · " + fmt.format(new Date(r.time));
            }
            TextView m = tv(meta, 11, GRAY, false);
            m.setPadding(0, dp(3), 0, dp(8));
            card.addView(m);

            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            TextView again = chip(r.fail ? "重试" : "重新处理", PINK, Color.WHITE, 12);
            again.setOnClickListener(v -> reprocess(r));
            TextView del = chip("删除记录", CHIP, SUB, 12);
            del.setOnClickListener(v -> {
                History.remove(this, r.key);
                render();
            });
            acts.addView(again);
            LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            g.leftMargin = dp(8);
            acts.addView(del, g);
            card.addView(acts);

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(8);
            listBox.addView(card, cp);
        }
    }

    private void reprocess(History.Rec r) {
        String url = r.url;
        if (url == null || url.isEmpty()) {
            if (r.key != null && r.key.startsWith("BV")) {
                url = "https://www.bilibili.com/video/" + r.key;
            } else {
                Toast.makeText(this, "这条记录没有原始链接，重新粘贴一下链接吧",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final String u = url;
        new AlertDialog.Builder(this)
                .setMessage("重新处理这条？\n\n" + r.title
                        + "\n\n会覆盖片库里同一视频的旧文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (d, w) -> {
                    Jobs.enqueue(u, 9.6, 0);
                    Toast.makeText(this, "已加入队列，回主界面看进度", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("清空所有处理记录？\n（只删记录，片库里的文件不动）")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    History.clear(this);
                    page = 0;
                    render();
                })
                .show();
    }
}
