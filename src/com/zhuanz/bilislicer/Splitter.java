package com.zhuanz.bilislicer;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 按「字节数」均衡切分，切点吸附到视频关键帧，只做重封装不重编码。
 * 按时长切会因为码率不均导致某段 11MB、某段 7MB；按字节切才能每段都贴着上限。
 */
public class Splitter {

    public interface Progress {
        void onProgress(int part, int total);
    }

    public static class Result {
        public List<File> files = new ArrayList<>();
        public int parts;
        public int planned;   // 原本打算切几段（关键帧不够密时会变少）
    }

    /** 用一帧一帧的样本大小累加，不做解码，很快 */
    static long[] cumBySecond(File f) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        for (int i = 0; i < ex.getTrackCount(); i++) {
            try { ex.selectTrack(i); } catch (Exception ignored) { }
        }
        TreeMap<Long, Long> perSec = new TreeMap<>();
        while (true) {
            // 注意：抖音视频首帧常是负时间戳（-46ms 之类），负数不代表没有样本。
            // 判断「还有没有样本」要用 getSampleTrackIndex()。
            if (ex.getSampleTrackIndex() < 0) break;
            long t = ex.getSampleTime();
            long sz = ex.getSampleSize();
            long sec = Math.max(0, t / 1_000_000L);
            Long old = perSec.get(sec);
            perSec.put(sec, (old == null ? 0 : old) + Math.max(0, sz));
            if (!ex.advance()) break;
        }
        ex.release();

        long maxSec = perSec.isEmpty() ? 0 : perSec.lastKey();
        long[] cum = new long[(int) maxSec + 2];
        long acc = 0;
        for (int s = 0; s <= maxSec; s++) {
            Long v = perSec.get((long) s);
            acc += (v == null ? 0 : v);
            cum[s] = acc;
        }
        cum[(int) maxSec + 1] = acc;
        return cum;
    }

    /** 视频关键帧时间点（微秒） */
    static List<Long> syncTimes(File f) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        int v = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("video/")) { v = i; break; }
        }
        List<Long> out = new ArrayList<>();
        if (v >= 0) {
            ex.selectTrack(v);
            while (true) {
                long t = ex.getSampleTime();
                if (t < 0) break;
                if ((ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) out.add(t);
                if (!ex.advance()) break;
            }
        }
        ex.release();
        return out;
    }

    /**
     * 读整片时长（微秒）。三级兜底，任何一级拿到就算：
     *   1) MediaExtractor 读轨道头里的 duration —— 最稳，也是最快（几毫秒）
     *   2) MediaMetadataRetriever —— 老办法，对部分容器（碎片 MP4、带 elst 的抖音片）会返回 null
     *   3) 逐样本扫到结尾取最后一个时间戳 —— 前两级都失败时才做，
     *      不解码只读包头，几千个样本也就几十毫秒，胜在几乎不可能失败
     * 以前只用了第 2 级，所以「有些视频总是读不到时长」其实就是它读不出来。
     */
    static long durationUsOf(File f) {
        long us = 0;
        // 1) 轨道头
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                try {
                    android.media.MediaFormat mf = ex.getTrackFormat(i);
                    if (mf.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                        us = Math.max(us, mf.getLong(android.media.MediaFormat.KEY_DURATION));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {
            }
        }
        if (us > 0) return us;

        // 2) MediaMetadataRetriever
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) us = Long.parseLong(d) * 1000L;
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        if (us > 0) return us;

        // 3) 扫样本
        return durationByScan(f);
    }

    /** 逐样本推进到结尾，用最后一个时间戳当总时长（不解码，只读包头） */
    static long durationByScan(File f) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            int v = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                try {
                    String mime = ex.getTrackFormat(i).getString(
                            android.media.MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        v = i;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (v < 0) return 0;
            ex.selectTrack(v);
            long last = 0;
            int guard = 0;
            while (guard++ < 2_000_000 && ex.advance()) {
                long t = ex.getSampleTime();
                if (t > last) last = t;
            }
            return last > 0 ? last : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    public static Result split(File in, File outDir, String base, double partBytes,
                               Progress cb) throws Exception {
        return split(in, outDir, base, partBytes, 0, cb);
    }

    /**
     * @param partUs 每段目标时长（微秒）；>0 时按时长切，忽略 partBytes
     */
    public static Result split(File in, File outDir, String base, double partBytes,
                               long partUs, Progress cb) throws Exception {
        return split(in, outDir, base, partBytes, partUs, 0, 0, cb);
    }

    /**
     * @param startUs 从这里开始（去掉开头），0 = 从头
     * @param endUs   到这里结束（去掉结尾），0 = 到片尾
     */
    public static Result split(File in, File outDir, String base, double partBytes,
                               long partUs, long startUs, long endUs,
                               Progress cb) throws Exception {
        // 只需要总字节数：以前为了「按字节找切点」遍历了全文件所有样本，
        // 现在切点按时间均分，直接取文件长度，省掉一次整文件扫描
        long total = in.length();
        if (total <= 0) throw new IOException("读取文件大小失败");

        long fullUs = durationUsOf(in);
        if (fullUs <= 0) {
            throw new IOException("读不到视频时长，无法切分：" + in.getName()
                    + "（" + (in.length() / 1024 / 1024) + " MB）");
        }
        // 支持裁掉开头 / 结尾：算段数时只用有效时长
        long durUs = fullUs;
        if (startUs > 0 && startUs < durUs) durUs -= startUs;
        if (endUs > 0 && endUs < fullUs) durUs = endUs - (startUs > 0 ? startUs : 0);

        // 两个限制同时生效：谁先到就按谁切。
        // 以前 partUs>0 就完全忽略体积，4 分钟一到就切，
        // 于是 4 分半钟的视频被切成 28MB + 2MB。
        int nBytes = partBytes > 0 ? (int) Math.ceil(total / partBytes) : 1;
        int nTime = (partUs > 0 && durUs > 0) ? (int) Math.ceil((double) durUs / partUs) : 1;
        int n = Math.max(1, Math.max(nBytes, nTime));

        List<Long> syncs = syncTimes(in);
        {
            // 切点 = 平均分段的边界，再吸附到关键帧。
            //
            // 这里有两个坑，都会切出垃圾段：
            //  a) 「吸附到最近关键帧」没有距离上限。关键帧稀疏的片子（长 GOP、录屏、第三方转码）
            //     只有开头和结尾两个关键帧时，中间切点会被吸到结尾 —— 于是第一段 53 秒、
            //     第二段 1 秒，看着就像「后面的都没了」。
            //  b) 尾段没有最短长度保护，差一点点就多切出一段。
            //
            // 现在的策略：只在目标点附近（step 的 25%）找关键帧；找不到就**放弃这个切点**
            // （宁可少切一段，也不从非关键帧开始 —— 那样开头会花屏直到下一个关键帧）。
            long stepUs = durUs / n;
            long tol = Math.max(1_000_000L, stepUs / 4);
            long endBound = (endUs > 0 && endUs < fullUs) ? endUs : (startUs + durUs);
            long minPart = Math.min(stepUs / 3, 3_000_000L);

            List<Long> cutUs = new ArrayList<>();
            for (int k = 1; k < n; k++) {
                long targetUs = startUs + stepUs * k;
                long best = -1;
                long bestDiff = Long.MAX_VALUE;
                for (long sy : syncs) {
                    long d = Math.abs(sy - targetUs);
                    if (d < bestDiff && d <= tol) {
                        bestDiff = d;
                        best = sy;
                    }
                }
                if (best < 0) continue;                       // 附近没关键帧，这个切点放弃
                // 第一个切点离片头太近会切出一个几乎空的段（甚至 0 字节，
                // 入不了库就只能丢掉，等于把开头几十秒内容弄没了）
                long prevCut = cutUs.isEmpty() ? Math.max(0, startUs) : cutUs.get(cutUs.size() - 1);
                if (best - prevCut < minPart) continue;
                if (endBound - best < minPart) continue;      // 会切出过短的尾段，放弃
                cutUs.add(best);
            }

            // 相邻段太近的也合并掉（保留后一个，前一段并进后一段）
            for (int i = cutUs.size() - 1; i > 0; i--) {
                if (cutUs.get(i) - cutUs.get(i - 1) < minPart) cutUs.remove(i - 1);
            }

            final int plan0 = n;
            int realN = cutUs.size() + 1;
            if (realN != n) {
                Log.i("BiliSlicer", "  切点调整：" + n + " 段 -> " + realN
                        + " 段（关键帧不够密，避免切出极短段）");
            }
            n = realN;

            Log.i("BiliSlicer", "split n=" + n + " plan=" + plan0 + " durUs=" + durUs
                    + " syncs=" + syncs.size() + " step=" + stepUs + " tol=" + tol
                    + " cuts=" + cutUs);
            Result r = new Result();
            r.parts = n;
            r.planned = plan0;
            long[] bounds = new long[n + 1];
            bounds[0] = Math.max(0, startUs);
            for (int i = 0; i < cutUs.size(); i++) bounds[i + 1] = cutUs.get(i);
            bounds[n] = (endUs > 0 && endUs < fullUs) ? endUs : Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                String name = n == 1 ? base + ".mp4"
                        : String.format("%02d_%s_%dof%d.mp4", i + 1, base, i + 1, n);
                File out = new File(outDir, name);
                extractRange(in, out, bounds[i], bounds[i + 1]);
                if (out.length() <= 0) {
                    throw new IOException("片段 " + name + " 切出来是空的（关键帧定位失败）");
                }
                r.files.add(out);
                if (cb != null) cb.onProgress(i + 1, n);
            }
            return r;
        }
    }

    /** 把 [startUs, endUs) 区间内的样本原样搬到新文件 */
    static void extractRange(File in, File out, long startUs, long endUs) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int[] trackMap = new int[ex.getTrackCount()];
        java.util.Arrays.fill(trackMap, -1);
        boolean started = false;
        long offset = startUs;   // 配合 shift 使用
        long samples = 0;

        try {
            // 只保留第一条视频 + 第一条音频。源里如果带第二条音轨/字幕轨，
            // 每段都整份拷一遍，产物会比原片还大（实测多出三成多）。
            boolean gotVideo = false, gotAudio = false;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = null;
                try {
                    mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                } catch (Exception ignored) {
                }
                boolean isVideo = mime != null && mime.startsWith("video/");
                boolean isAudio = mime != null && mime.startsWith("audio/");
                if (isVideo && gotVideo) { trackMap[i] = -1; continue; }
                if (isAudio && gotAudio) { trackMap[i] = -1; continue; }
                try {
                    trackMap[i] = mux.addTrack(ex.getTrackFormat(i));
                    if (isVideo) gotVideo = true;
                    if (isAudio) gotAudio = true;
                } catch (Exception ignored) {
                    trackMap[i] = -1;
                }
            }
            for (int i = 0; i < ex.getTrackCount(); i++) {
                try { ex.selectTrack(i); } catch (Exception ignored) { }
            }
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // 首帧可能是负数（B 帧 / edit list 偏移），整体平移，保证输出从 0 开始
            long firstT = ex.getSampleTime();
            long shift = firstT < 0 ? -firstT : 0;
            long wrote = 0;
            ByteBuffer buf = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (true) {
                if (ex.getSampleTrackIndex() < 0) break;
                long t = ex.getSampleTime();
                if (t >= endUs) break;

                int from = ex.getSampleTrackIndex();
                if (from < 0) { ex.advance(); continue; }
                int sz = ex.readSampleData(buf, 0);
                if (sz <= 0) { ex.advance(); continue; }
                if (t < startUs) { ex.advance(); continue; }

                int to = trackMap[from];
                if (to < 0) { ex.advance(); continue; }

                if (!started) {
                    mux.start();
                    started = true;
                }
                bi.offset = 0;
                bi.size = sz;
                bi.presentationTimeUs = Math.max(0, t - offset);
                bi.flags = ex.getSampleFlags();
                mux.writeSampleData(to, buf, bi);
                wrote++;
                samples++;
                ex.advance();
            }
        } finally {
            Log.i("BiliSlicer", "  part " + out.getName() + " samples=" + samples
                    + " size=" + out.length());
            try { ex.release(); } catch (Exception ignored) { }
            if (started) { try { mux.stop(); } catch (Exception ignored) { } }
            try { mux.release(); } catch (Exception ignored) { }
        }
    }
}
