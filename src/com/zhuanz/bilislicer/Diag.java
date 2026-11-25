package com.zhuanz.bilislicer;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 诊断时间线：把一次处理从「检测到链接」到「落库」的关键节点记下来。
 *
 * 出问题时不用猜 —— 点设置里的「复制诊断日志」，把这段发出来就能看出停在哪一步。
 * 只在内存里留最近 400 行，不写文件、不上报。
 */
public class Diag {

    private static final String TAG = "ClipSmithDiag";
    private static final int MAX = 400;
    private static final Deque<String> BUF = new ArrayDeque<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** 当前任务号，方便把同一次处理的多行串起来 */
    private static volatile int jobSeq = 0;
    private static volatile int curJob = 0;

    public static int newJob() {
        curJob = ++jobSeq;
        return curJob;
    }

    public static int cur() {
        return curJob;
    }

    public static void line(String stage, String detail) {
        String s = TS.format(new Date()) + "  #" + curJob + "  " + stage
                + (detail == null || detail.isEmpty() ? "" : "  " + detail);
        Log.i(TAG, s);
        synchronized (BUF) {
            BUF.addLast(s);
            while (BUF.size() > MAX) BUF.removeFirst();
        }
    }

    /** 不带任务号的全局事件（比如权限状态） */
    public static void global(String stage, String detail) {
        String s = TS.format(new Date()) + "  --   " + stage
                + (detail == null || detail.isEmpty() ? "" : "  " + detail);
        Log.i(TAG, s);
        synchronized (BUF) {
            BUF.addLast(s);
            while (BUF.size() > MAX) BUF.removeFirst();
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("ClipSmith 诊断日志\n");
        sb.append("设备 ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" / Android ")
                .append(android.os.Build.VERSION.RELEASE).append('\n');
        synchronized (BUF) {
            for (String s : BUF) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    /** 复制到剪贴板，方便直接发给开发者 */
    public static void copy(Context c) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "ClipSmith 诊断日志", dump()));
            return;
        } catch (Throwable ignored) {
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", dump()));
        } catch (Throwable ignored) {
        }
    }
}
