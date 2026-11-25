package com.zhuanz.bilislicer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;

/**
 * 任务队列 + 后台工作线程。
 * 放在静态里，进程被前台服务保活时任务不会因为界面退出而中断。
 */
public class Jobs {

    public interface Listener {
        void onLog(String s);

        void onProgress(int pct);

        void onFinished(String error);

        void onQueueChanged(int pending);

        /** Pipeline 需要用户确认（重复视频 / 多分P） */
        void onAsk(Pipeline.NeedConfirm what);
    }

    static class Job {
        final String url;
        final android.net.Uri localUri;   // 非空表示处理本地文件
        final String localName;
        final double limit;
        final int parts;
        final boolean force;      // 用户已确认「仍然处理」

        Job(String url, double limit, int parts, boolean force) {
            this.url = url;
            this.localUri = null;
            this.localName = null;
            this.limit = limit;
            this.parts = parts;
            this.force = force;
        }

        Job(android.net.Uri uri, String name, double limit, int parts) {
            this.url = name == null ? "本地视频" : name;
            this.localUri = uri;
            this.localName = name;
            this.limit = limit;
            this.parts = parts;
            this.force = true;
        }
    }

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Listener listener;
    private static volatile Thread worker;
    /** 取消：当前任务尽快停下，排队中的直接丢掉 */
    private static volatile boolean cancelFlag;

    public static boolean isCanceled() {
        return cancelFlag;
    }

    public static void cancelAll() {
        synchronized (LOCK) {
            cancelFlag = true;
            QUEUE.clear();
        }
        notifyQueue();
    }

    static void resetCancel() {
        cancelFlag = false;
    }

    /** 最近一次失败的链接，供界面上「重试」用 */
    public static volatile String lastFailedUrl;
    public static volatile String lastFailedMsg;
    private static Context app;

    public static void init(Context c) {
        if (app == null) app = c.getApplicationContext();
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    static void post(Runnable r) {
        MAIN.post(r);
    }

    static void logLine(String s) {
        try {
            if (app != null) WorkService.setText(s);
        } catch (Exception ignored) {
        }
        post(() -> {
            Listener l = listener;
            if (l != null) l.onLog(s);
        });
    }

    public static int pending() {
        synchronized (QUEUE) {
            return QUEUE.size() + ((worker != null && worker.isAlive()) ? 1 : 0);
        }
    }

    public static boolean busy() {
        return worker != null && worker.isAlive();
    }

    /**
     * 入队。注意：worker 的置空和「队列取空」必须在同一把锁里完成，
     * 否则会出现「新任务已入队、但旧线程看似还活着」的窗口 —— 任务会被静默丢弃。
     */
    private static Pipeline.NeedConfirm pending;

    /** 队列里还有没有活（用来避免剪贴板监视打断正在处理的任务） */
    public static boolean isRunning() {
        synchronized (QUEUE) {
            return busy() || !QUEUE.isEmpty();
        }
    }

    /** 取出「等用户拍板」的请求（界面恢复前台时调用） */
    public static synchronized Pipeline.NeedConfirm takePending() {
        Pipeline.NeedConfirm p = pending;
        pending = null;
        return p;
    }

    public static void enqueue(String url, double limit, int parts) {
        enqueue(url, limit, parts, false);
    }

    /** 排队一个本地视频 */
    public static void enqueueLocal(android.net.Uri uri, String name,
                                    double limit, int parts) {
        if (app == null) return;
        synchronized (LOCK) {
            QUEUE.add(new Job(uri, name, limit, parts));
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(Jobs::loop, "bili-worker");
                worker.start();
            }
        }
        notifyQueue();
    }

    public static void enqueue(String url, double limit, int parts, boolean force) {
        if (app == null) return;
        synchronized (LOCK) {
            // 队列里已经有同一条链接、还没开始跑，就不要再排一遍
            // （复制事件可能重复触发，或者用户连点两下）
            if (url != null) {
                for (Job j : QUEUE) {
                    if (url.equals(j.url)) {
                        android.util.Log.i("BiliSlicer", "already queued, skip: " + url);
                        return;
                    }
                }
            }
            QUEUE.add(new Job(url, limit, parts, force));
            // 线程可能已经异常退出但引用还在，这种情况必须重启，否则任务永远排着不动
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(Jobs::loop, "bili-worker");
                worker.start();
            }
        }
        notifyQueue();
    }

    private static void notifyQueue() {
        final int n = pending();
        post(() -> {
            Listener l = listener;
            if (l != null) l.onQueueChanged(n);
        });
    }

    private static void loop() {
        try {
            WorkService.start(app);
        } catch (Exception e) {
            // 平板/旧系统上启动前台服务可能被拦，不能让它把工作线程带死
            android.util.Log.w("BiliSlicer", "WorkService.start fail", e);
        }
        try {
            while (true) {
                Job j;
                synchronized (LOCK) {
                    j = QUEUE.poll();
                    if (j == null) {
                        worker = null;      // 在锁内收尾，入队方不会再看到「假活的线程」
                        return;
                    }
                }
                notifyQueue();
                resetCancel();
                try {
                    Pipeline.Result r = (j.localUri != null)
                            ? Pipeline.runLocal(app, j.localUri, j.localName,
                                    j.limit, j.parts, Jobs::logLine,
                                    pct -> {
                                        try {
                                            WorkService.setProgress(pct);
                                        } catch (Exception ignored) {
                                        }
                                        post(() -> {
                                            Listener l = listener;
                                            if (l != null) l.onProgress(pct);
                                        });
                                    })
                            : Pipeline.run(app, j.url, j.limit, j.parts, j.force,
                            Jobs::logLine,
                            pct -> {
                                try {
                                    WorkService.setProgress(pct);
                                } catch (Exception ignored) {
                                }
                                post(() -> {
                                    Listener l = listener;
                                    if (l != null) l.onProgress(pct);
                                });
                            });
                    History.Rec rec = new History.Rec();
                    rec.key = r.key;
                    rec.title = r.title;
                    rec.parts = r.parts;
                    rec.size = r.bytes;
                    rec.platform = r.platform;
                    rec.time = System.currentTimeMillis();
                    rec.url = j.url;
                    History.add(app, rec);

                    WorkService.notifyDone(app, r.title, r.parts);
                    lastFailedUrl = null;
                    final String done = "✓ 《" + r.title + "》完成，" + r.parts + " 个文件";
                    logLine(done);
                    post(() -> {
                        Listener l = listener;
                        if (l != null) l.onFinished(null);
                    });
                } catch (Pipeline.NeedConfirm nc) {
                    logLine("⏸ 等你确认（不是卡住）");
                    android.util.Log.i("BiliSlicer", "needConfirm kind=" + nc.kind
                            + " pages=" + nc.pages + " season="
                            + (nc.seasonList == null ? -1 : nc.seasonList.size())
                            + " pageList=" + (nc.pageList == null ? -1 : nc.pageList.size()));
                    // 不是失败，是等用户拍板；界面不在前台就先存着，回来再问
                    post(() -> {
                        Listener l = listener;
                        android.util.Log.i("BiliSlicer", "needConfirm listener="
                                + (l == null ? "null" : l.getClass().getSimpleName()));
                        if (l != null) l.onAsk(nc);
                        else pending = nc;
                    });
                } catch (Pipeline.Canceled c) {
                    logLine("⏹ 已取消");
                    post(() -> {
                        Listener l = listener;
                        if (l != null) l.onFinished(null);
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    History.addFail(app, j.url, msg);
                    lastFailedUrl = j.url;
                    lastFailedMsg = msg;
                    logLine("✗ 失败：" + msg);
                    post(() -> {
                        Listener l = listener;
                        if (l != null) l.onFinished(msg);
                    });
                }
            }
        } finally {
            boolean stopService;
            boolean restart = false;
            synchronized (LOCK) {
                Thread me = Thread.currentThread();
                if (worker == me) worker = null;
                // 队列还有活却没线程了：补一个，避免任务被静默丢弃
                if (!QUEUE.isEmpty()) {
                    worker = new Thread(Jobs::loop, "bili-worker");
                    worker.start();
                    restart = true;
                }
                stopService = (worker == null && QUEUE.isEmpty());
            }
            if (restart) return;
            // 只有确实没人干活了才停服务；否则可能把新开的那个线程的前台保护掐掉
            if (stopService) WorkService.stop(app);
            post(() -> {
                Listener l = listener;
                if (l != null) l.onQueueChanged(0);
            });
        }
    }
}
