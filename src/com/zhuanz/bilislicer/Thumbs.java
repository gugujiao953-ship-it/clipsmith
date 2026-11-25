package com.zhuanz.bilislicer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频封面（首帧）：
 *
 *  1. 磁盘只存一份 480 宽的 JPEG（原图源），内存按「实际显示宽度」解码 —— 竖排 286px 就解码 286px，
 *     不再把 480 的大图塞进缓存，同样的内存能多装 3~5 倍封面。
 *  2. 内存用 RGB_565，小图看不出差别但内存直接减半。
 *  3. 片头常是黑场，按 12%/35%/60% 依次找非黑帧。
 *  4. 同一 key 的并发请求排队、全部回调，避免「视图重绘后拿不到图」。
 */
public class Thumbs {

    private static final String VER = "v5_";   // v5: 暗帧提亮 + 短视频取中间帧
    private static final int SRC_W = 480;

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(
            (int) Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, List<Waiter>> WAITING = new HashMap<>();

    public interface Cb {
        void onThumb(Bitmap b);
    }

    private static class Waiter {
        final int width;
        final Cb cb;

        Waiter(int width, Cb cb) {
            this.width = width;
            this.cb = cb;
        }
    }

    static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File disk(Context ctx, String key) {
        return new File(dir(ctx), VER + key + ".jpg");
    }

    /** 按目标宽度解码，返回 RGB_565 的小图 */
    static Bitmap decodeScaled(File f, int targetW) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0) return null;

            int sample = 1;
            // 先按 2 的幂粗降采样，再精确缩放到目标宽度
            while (bounds.outWidth / (sample * 2) >= targetW * 2) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap raw = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (raw == null) return null;
            if (raw.getWidth() <= targetW) return raw;

            int h = Math.max(1, raw.getHeight() * targetW / raw.getWidth());
            Bitmap out = Bitmap.createScaledBitmap(raw, targetW, h, true);
            if (out != raw) raw.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void load(Context ctx, String key, Uri uri, int targetWidthPx, final Cb cb) {
        final int w = Math.max(64, targetWidthPx);
        final String memKey = VER + key + "@" + w;

        Bitmap cached = MEM.get(memKey);
        if (cached != null && !cached.isRecycled()) {
            cb.onThumb(cached);
            return;
        }

        File f = disk(ctx, key);
        if (f.exists()) {
            Bitmap b = decodeScaled(f, w);
            if (b != null) {
                MEM.put(memKey, b);
                cb.onThumb(b);
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();     // 坏文件，重新生成
        }

        synchronized (WAITING) {
            List<Waiter> list = WAITING.get(key);
            if (list != null) {
                list.add(new Waiter(w, cb));
                return;
            }
            list = new ArrayList<>();
            list.add(new Waiter(w, cb));
            WAITING.put(key, list);
        }

        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            File target = disk(app, key);
            if (!target.exists()) {
                Bitmap frame = grabFrame(app, uri);
                if (frame != null) {
                    try (FileOutputStream o = new FileOutputStream(target)) {
                        frame.compress(Bitmap.CompressFormat.JPEG, 82, o);
                    } catch (Exception ignored) {
                    }
                    frame.recycle();
                }
            }

            List<Waiter> waiters;
            synchronized (WAITING) {
                waiters = WAITING.remove(key);
            }
            if (waiters == null) return;

            Map<Integer, Bitmap> decoded = new HashMap<>();
            for (Waiter wr : waiters) {
                Bitmap b = decoded.get(wr.width);
                if (b == null) {
                    b = target.exists() ? decodeScaled(target, wr.width) : null;
                    if (b != null) {
                        decoded.put(wr.width, b);
                        MEM.put(VER + key + "@" + wr.width, b);
                    }
                }
                final Bitmap fb = b;
                MAIN.post(() -> wr.cb.onThumb(fb));
            }
        });
    }

    /** 从视频里抓一帧（跳开片头黑场） */
    static Bitmap grabFrame(Context app, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        Bitmap b = null;
        try {
            r.setDataSource(app, uri);
            long durMs = 0;
            try {
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durMs = Long.parseLong(d);
            } catch (Throwable ignored) {
            }
            long[] seeks = durMs > 2000
                    ? new long[]{(long) (durMs * 0.12), (long) (durMs * 0.35),
                                 (long) (durMs * 0.60), 0}
                    : durMs > 100
                      // 短视频常常是黑场淡入，第 0 帧全黑，补一个中间帧
                      ? new long[]{(long) (durMs * 0.5), 0}
                      : new long[]{0};
            for (long ms : seeks) {
                Bitmap fr = r.getFrameAtTime(ms * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (fr == null) continue;
                if (b == null) b = fr;
                if (!isDark(fr)) {
                    b = fr;
                    break;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("BiliSlicer", "thumb fail " + uri + " : " + t);
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        if (b == null) return null;

        // 四个候选时间点都是暗帧（夜景、室内暗光）时缩略图看起来像加载失败，
        // 这里做一次提亮 + 轻微对比拉伸，至少能看清画面轮廓
        if (isDark(b)) {
            b = brighten(b);
        }

        if (b.getWidth() > SRC_W) {
            int h = Math.max(1, b.getHeight() * SRC_W / b.getWidth());
            Bitmap small = Bitmap.createScaledBitmap(b, SRC_W, h, true);
            if (small != b) {
                b.recycle();
                b = small;
            }
        }
        return b;
    }

    /** 平均亮度低于阈值就当黑屏 */
    /** 整体偏暗时提亮：亮度线性放大 + 少量对比，缩略图尺寸下噪点看不出来 */
    static Bitmap brighten(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            Bitmap out = Bitmap.createBitmap(w, h,
                    src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint();
            // 亮度 ×1.9，再减 12 抬黑位（让死黑区域浮出细节）
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
                    1.9f, 0, 0, 0, -12,
                    0, 1.9f, 0, 0, -12,
                    0, 0, 1.9f, 0, -12,
                    0, 0, 0, 1, 0
            });
            p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            cv.drawBitmap(src, 0, 0, p);
            if (out != src) src.recycle();
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    static boolean isDark(Bitmap b) {
        try {
            int w = 32, h = Math.max(1, b.getHeight() * 32 / Math.max(1, b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, false);
            long sum = 0;
            int n = 0;
            for (int y = 0; y < s.getHeight(); y++) {
                for (int x = 0; x < s.getWidth(); x++) {
                    int c = s.getPixel(x, y);
                    sum += ((c >> 16 & 0xFF) * 299 + (c >> 8 & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
                    n++;
                }
            }
            if (s != b) s.recycle();
            return n == 0 || sum / n < 45;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 启动时清理一周前的封面缓存，以及老版本的 v2_ 文件 */
    public static void prune(final Context ctx) {
        POOL.execute(() -> {
            try {
                File[] fs = dir(ctx).listFiles();
                if (fs == null) return;
                long now = System.currentTimeMillis();
                for (File f : fs) {
                    String n = f.getName();
                    boolean stale = now - f.lastModified() > 7L * 24 * 3600 * 1000;
                    boolean legacy = n.startsWith("v2_");
                    if (stale || legacy) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }
}
