package com.zhuanz.bilislicer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 用系统硬件编码器把视频压到指定码率。
 * 视频：解码 → Surface → 硬件编码 H.264（关键帧间隔 1 秒，方便后面精确切）
 * 音频：原样复制
 *
 * 关键点：每次 releaseOutputBuffer(render=true) 之前必须先清空编码器输出，
 * 否则编码器输入队列满时渲染会阻塞 → 互锁（真机实测会卡死）。
 */
public class Transcoder {

    /** 由 Jobs 注入，用来记住哪条转码路径在这台机器上是通的 */
    static android.content.SharedPreferences sp;

    /**
     * 挑一个硬件加速的 H.264 编码器。
     * 默认的 createEncoderByType 经常给到 c2.android.avc.encoder（纯软编），
     * 速度只有硬编的零头 —— 这是转码慢的主因。
     */
    static String pickHwEncoder() {
        try {
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            String fallback = null;
            for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                boolean avc = false;
                for (String t : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(t)) {
                        avc = true;
                        break;
                    }
                }
                if (!avc) continue;
                if (android.os.Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated()) {
                    return info.getName();
                }
                if (fallback == null) fallback = info.getName();
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    static final String TAG = "BiliSlicer";

    public interface Progress {
        void onProgress(int percent);
    }

    private static class Mux {
        MediaMuxer mux;
        int vTrack = -1;
        int aTrack = -1;
        boolean started = false;
        boolean eos = false;
        long written = 0;
    }

    /** 把编码器已产出的数据全部写出去 */
    private static void drainEncoder(MediaCodec enc, Mux m) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!m.started) {
                    m.vTrack = m.mux.addTrack(enc.getOutputFormat());
                    m.mux.start();
                    m.started = true;
                }
                continue;
            }
            if (idx < 0) return;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && m.started) {
                ByteBuffer b = enc.getOutputBuffer(idx);
                if (b != null) {
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    m.mux.writeSampleData(m.vTrack, b, info);
                    m.written++;
                }
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            enc.releaseOutputBuffer(idx, false);
            if (eos) {
                m.eos = true;
                return;
            }
        }
    }

    public static void transcode(File in, File out, int videoBitrate, int maxWidth,
                                 int keyframeSec, long durationHintMs, Progress cb)
            throws Exception {
        long t0 = System.currentTimeMillis();
        String path = sp == null ? "" : sp.getString("encPath2", "");
        // 有 Surface 直通成功的记录就用它（硬件直通，比 YUV 搬运快得多）
        boolean surfaceFirst = path.isEmpty() || "surface".equals(path);
        if (surfaceFirst) {
            try {
                surface(in, out, videoBitrate, maxWidth, keyframeSec, durationHintMs, cb);
                Log.i(TAG, "transcode OK surface " + (System.currentTimeMillis() - t0) + "ms");
                return;
            } catch (Pipeline.Canceled c) {
                throw c;
            } catch (Exception e) {
                Log.w(TAG, "surface 失败，改走 YUV: " + e);
                path = "yuv";
            }
        }
        try {
            TranscoderYuv.transcode(in, out, videoBitrate, maxWidth, keyframeSec,
                    durationHintMs, cb);
            if (!"yuv".equals(path)) notePath("yuv");
            Log.i(TAG, "transcode OK yuv " + (System.currentTimeMillis() - t0) + "ms");
            return;
        } catch (Pipeline.Canceled c) {
            throw c;
        } catch (Exception e) {
            if (surfaceFirst) throw e;
            Log.w(TAG, "YUV 直通失败，退回 Surface 方案: " + e);
        }
        surface(in, out, videoBitrate, maxWidth, keyframeSec, durationHintMs, cb);
        notePath("surface");
        Log.i(TAG, "transcode OK surface " + (System.currentTimeMillis() - t0) + "ms");
    }

    private static void notePath(String path) {
        try {
            if (sp != null) sp.edit().putString("encPath2", path).apply();
        } catch (Exception ignored) {
        }
    }

    /** Surface 直通：解码器直接写编码器的输入表面，全程不碰 CPU */
    private static void surface(File in, File out, int videoBitrate, int maxWidth,
                                int keyframeSec, long durationHintMs, Progress cb)
            throws Exception {
        String hw = pickHwEncoder();
        // 直通路径只试硬件和默认：软编的 Surface 通道基本都不通，试了纯浪费时间
        String[] names = {hw, null};
        boolean[] cbrs = {true, false};
        Exception last = null;
        for (int i = 0; i < names.length; i++) {
            if (i > 0 && java.util.Objects.equals(names[i], names[i - 1])) continue;
            try {
                transcode0(in, out, videoBitrate, maxWidth, keyframeSec, durationHintMs, cb,
                        cbrs[i], names[i]);
                Log.i(TAG, "surface encoder=" + (names[i] == null ? "default" : names[i]));
                return;
            } catch (Exception e) {
                if (e instanceof Pipeline.Canceled) throw e;
                last = e;
                Log.w(TAG, "surface 尝试#" + i + " 失败（" + names[i] + "）: " + e);
            }
        }
        if (last != null) throw last;
    }

    private static void transcode0(File in, File out, int videoBitrate, int maxWidth,
                                   int keyframeSec, long durationHintMs, Progress cb,
                                   boolean cbr, String codecName) throws Exception {

        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());

        int vIdx = -1, aIdx = -1;
        MediaFormat vFmt = null, aFmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            if (mime.startsWith("video/") && vIdx < 0) { vIdx = i; vFmt = f; }
            else if (mime.startsWith("audio/") && aIdx < 0) { aIdx = i; aFmt = f; }
        }
        if (vIdx < 0) throw new IOException("源文件里没有视频轨");

        int w = vFmt.getInteger(MediaFormat.KEY_WIDTH);
        int h = vFmt.getInteger(MediaFormat.KEY_HEIGHT);
        int rot = vFmt.containsKey("rotation-degrees") ? vFmt.getInteger("rotation-degrees") : 0;
        // 注意：编码器的输入表面必须按「未旋转」的原始尺寸配置。
        // 之前按旋转后的尺寸配置，解码器往表面送的是转正后的画面，尺寸对不上，
        // 高通编码器会直接 work failed to complete: 14 → IllegalStateException。
        // 旋转信息改由 MediaMuxer 的 setOrientationHint 记到文件里，播放器照样正着放。
        int dispW = w, dispH = h;

        // 低码率就别配大分辨率：既省算力，画质反而更均匀，
        // 而且部分硬件编码器在「大尺寸 + 低码率」组合下会直接崩
        int widthCap = maxWidth;
        if (videoBitrate < 900_000) widthCap = Math.min(widthCap, 640);
        if (videoBitrate < 450_000) widthCap = Math.min(widthCap, 480);

        int outW = dispW, outH = dispH;
        if (dispW > widthCap) {
            outW = widthCap;
            outH = Math.max(2, (dispH * widthCap / dispW) / 2 * 2);
        }
        // 硬件编码器普遍要求宽高 8/16 对齐；源是 854×480 这种非对齐尺寸时
        // 直接拿去 configure 会成功、但一 dequeue 就 IllegalStateException
        int alignedW = Math.max(16, outW / 16 * 16);
        int alignedH = Math.max(16, outH / 16 * 16);
        if (alignedW != outW || alignedH != outH) {
            android.util.Log.i(TAG, "align " + outW + "x" + outH + " -> "
                    + alignedW + "x" + alignedH);
            outW = alignedW;
            outH = alignedH;
        }
        int fps = vFmt.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? vFmt.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
        if (fps <= 0 || fps > 120) fps = 30;

        long durationMs = vFmt.containsKey(MediaFormat.KEY_DURATION)
                ? vFmt.getLong(MediaFormat.KEY_DURATION) / 1000 : 0;
        if (durationMs <= 0) durationMs = durationHintMs;
        Log.i(TAG, "transcode " + outW + "x" + outH + " @" + videoBitrate / 1000 + "k fps=" + fps
                + " dur=" + durationMs + "ms");

        MediaCodec enc;
        if (codecName == null) {
            enc = MediaCodec.createEncoderByType("video/avc");
        } else {
            enc = MediaCodec.createByCodecName(codecName);
        }
        Log.i(TAG, "encoder=" + enc.getName());
        MediaFormat ef = MediaFormat.createVideoFormat("video/avc", outW, outH);
        ef.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Math.max(1, keyframeSec));
        ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        ef.setInteger(MediaFormat.KEY_BITRATE_MODE, cbr
                ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        Surface surface = enc.createInputSurface();
        enc.start();

        MediaCodec dec = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME));
        MediaFormat dFmt = new MediaFormat(vFmt);
        try {
            dFmt.setInteger("rotation-degrees", 0);
        } catch (Exception ignored) {
        }
        dec.configure(dFmt, surface, null, 0);
        dec.start();
        ex.selectTrack(vIdx);

        Mux m = new Mux();
        m.mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            if (rot != 0) m.mux.setOrientationHint(rot);
        } catch (Exception ignored) {
        }
        if (aFmt != null) {
            try { m.aTrack = m.mux.addTrack(aFmt); } catch (Exception ignored) { }
        }

        MediaCodec.BufferInfo dInfo = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        long frames = 0;
        long lastPts = 0;
        long guard = 0;
        long shift = 0;      // 首帧可能是负时间戳，整体平移
        boolean fed = false; // 还没喂进第一帧之前，别去取编码器输出

        while (!m.eos) {
            boolean didWork = false;

            if (!inputDone) {
                int inIdx = dec.dequeueInputBuffer(4000);
                if (inIdx >= 0) {
                    ByteBuffer ib = dec.getInputBuffer(inIdx);
                    int sz = ib == null ? -1 : ex.readSampleData(ib, 0);
                    if (sz < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long pts = ex.getSampleTime();
                        if (pts < 0 && shift == 0) shift = -pts;
                        lastPts = pts + shift;
                        dec.queueInputBuffer(inIdx, 0, sz, Math.max(0, pts + shift), 0);
                        fed = true;
                        ex.advance();
                    }
                    didWork = true;
                }
            }

            int dIdx = dec.dequeueOutputBuffer(dInfo, 0);
            if (dIdx >= 0) {
                // 一定要先喂过帧再取输出：部分 C2 实现在没有输入时 dequeue 会抛
                // IllegalStateException（编码器输出队列还没建立）
                if (fed) drainEncoder(enc, m);
                dec.releaseOutputBuffer(dIdx, true);
                if (fed) drainEncoder(enc, m);
                frames++;
                didWork = true;
            } else {
                if (fed) drainEncoder(enc, m);
            }

            if (cb != null && durationMs > 0 && lastPts > 0) {
                cb.onProgress((int) Math.min(99, lastPts / 1000 * 100 / durationMs));
            }

            if (inputDone) {
                // 输入结束后主动催 EOS，最多再等 3 秒
                if (!m.eos && guard++ > 30) {
                    try { enc.signalEndOfInputStream(); } catch (Exception ignored) { }
                    drainEncoder(enc, m);
                    if (!m.eos) break;
                }
            }
            if (!didWork) Thread.sleep(2);
        }
        Log.i(TAG, "encode done frames=" + frames + " samples=" + m.written);

        // 音频：原样搬运
        if (aFmt != null && m.aTrack >= 0 && m.started) {
            MediaExtractor ax = new MediaExtractor();
            try {
                ax.setDataSource(in.getAbsolutePath());
                ax.selectTrack(aIdx);
                ByteBuffer ab = ByteBuffer.allocateDirect(512 * 1024);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                while (true) {
                    int sz = ax.readSampleData(ab, 0);
                    if (sz < 0) break;
                    bi.offset = 0;
                    bi.size = sz;
                    bi.presentationTimeUs = ax.getSampleTime();
                    bi.flags = ax.getSampleFlags();
                    m.mux.writeSampleData(m.aTrack, ab, bi);
                    ax.advance();
                }
            } catch (Exception e) {
                Log.w(TAG, "audio copy: " + e.getMessage());
            } finally {
                try { ax.release(); } catch (Exception ignored) { }
            }
        }

        try { dec.stop(); } catch (Exception ignored) { }
        try { dec.release(); } catch (Exception ignored) { }
        try { enc.stop(); } catch (Exception ignored) { }
        try { enc.release(); } catch (Exception ignored) { }
        try { surface.release(); } catch (Exception ignored) { }
        try { ex.release(); } catch (Exception ignored) { }
        try { m.mux.stop(); } catch (Exception ignored) { }
        try { m.mux.release(); } catch (Exception ignored) { }
        if (cb != null) cb.onProgress(100);
    }
}
