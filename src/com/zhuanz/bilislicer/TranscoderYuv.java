package com.zhuanz.bilislicer;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * YUV 直通转码：解码器输出原始 YUV 帧，直接喂给编码器，**完全不用 Surface**。
 *
 * 为什么需要这个：这台手机（高通 Codec2）在「解码器 → 编码器输入表面」这步会失败——
 * setOutputSurface: failed to set consumer usage (BAD_INDEX)，
 * 随后编码器 work failed to complete: 14。换编码器、换码率都绕不过，
 * 只能改走 ByteBuffer 通道。
 */
public class TranscoderYuv {

    /** 上一次转码收尾是否失败（mp4 没写完，产物不可用） */
    static boolean muxBroken;

    // 逐帧缩放用的行缓冲（每次转码复用，避免每帧 new byte[]）
    private static byte[] rowBuf;
    private static byte[] dstBuf;
    private static int[] xmap;      // 横向缩放映射表（源偏移）

    private static final String TAG = "BiliSlicer";

    public static void transcode(File in, File out, int bitrate, int maxWidth,
                                 int keySec, long durHintMs, Transcoder.Progress cb)
            throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());

        int vIdx = -1, aIdx = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            if (vIdx < 0 && mime.startsWith("video/")) vIdx = i;
            else if (aIdx < 0 && mime.startsWith("audio/")) aIdx = i;
        }
        if (vIdx < 0) throw new Exception("没有视频轨");

        MediaFormat vFmt = ex.getTrackFormat(vIdx);
        int rot = vFmt.containsKey("rotation-degrees")
                ? vFmt.getInteger("rotation-degrees") : 0;
        int srcW = vFmt.getInteger(MediaFormat.KEY_WIDTH);
        int srcH = vFmt.getInteger(MediaFormat.KEY_HEIGHT);
        int fps = vFmt.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? vFmt.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
        if (fps <= 0 || fps > 60) fps = 30;

        // 编码尺寸：等比例缩到上限以内，并 16 对齐
        int outW = srcW, outH = srcH;
        if (srcW > maxWidth) {
            outW = Math.max(16, maxWidth / 16 * 16);
            outH = Math.max(16, (srcH * outW / srcW) / 16 * 16);
        }
        Log.i(TAG, "yuv transcode " + srcW + "x" + srcH + " -> " + outW + "x" + outH
                + " @" + bitrate / 1000 + "k rot=" + rot);

        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            // ---- 解码器：不绑定 Surface ----
            dec = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME));
            MediaFormat dFmt = new MediaFormat(vFmt);
            try {
                dFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            } catch (Exception ignored) {
            }
            try {
                dFmt.setInteger("rotation-degrees", 0);
            } catch (Exception ignored) {
            }
            dec.configure(dFmt, null, null, 0);
            dec.start();

            // ---- 编码器：YUV420Flexible 输入 ----
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", outW, outH);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Math.max(1, keySec));
            // 必须锁 CBR：默认 VBR 会超到 1.5 倍以上，压了个寂寞
            try {
                ef.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            } catch (Exception ignored) {
            }
            // 告诉编码器「按这个帧率实时跑就行」，部分实现会因此缩小运动搜索范围，
            // 速度更快、画质略降；不支持的实现会直接忽略这个键
            try {
                ef.setInteger(MediaFormat.KEY_OPERATING_RATE, Math.max(30, fps));
            } catch (Exception ignored) {
            }
            enc = null;
            String hw = Transcoder.pickHwEncoder();
            if (hw != null) {
                try {
                    enc = MediaCodec.createByCodecName(hw);
                } catch (Exception ignored) {
                    enc = null;
                }
            }
            if (enc == null) enc = MediaCodec.createEncoderByType("video/avc");
            android.util.Log.i("BiliSlicer", "yuv encoder=" + enc.getName());
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            mux = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo vInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
            int vTrack = -1, aTrack = -1;
            long shift = 0;

            ex.selectTrack(vIdx);
            boolean inputDone = false;
            boolean encEos = false;
            int guard = 0;
            muxBroken = false;
            long outFrameIdx = 0;    // 已编码帧数，时间戳 = 帧号 × 帧间隔
            int aSampleRate = 44100;
            long aFrameIdx = 0;
            final long frameUs = 1_000_000L / Math.max(1, fps);
            long lastVPts = -1;   // 已写入的最后时间戳，保证单调
            long lastAPts = -1;
            int ptsFixed = 0;
            int dropped = 0;      // 编码器来不及吃而丢掉的帧
            int idle = 0;         // 一轮下来什么都没干成的次数

            while (!encEos && guard++ < 200000) {
                boolean did = false;
                // 1) 喂解码器
                if (!inputDone) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(inIdx);
                        int sz = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = ex.getSampleTime();
                            if (pts < 0 && shift == 0) shift = -pts;
                            dec.queueInputBuffer(inIdx, 0, sz, Math.max(0, pts + shift), 0);
                            ex.advance();
                        }
                    }
                }

                // 2) 解码输出 → 编码输入
                int dIdx = dec.dequeueOutputBuffer(vInfo, 0);
                if (dIdx >= 0) {
                    if ((vInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        int inIdx = enc.dequeueInputBuffer(500000);
                        if (inIdx >= 0) {
                            enc.queueInputBuffer(inIdx, 0, 0, vInfo.presentationTimeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    } else {
                        // 时间戳完全按帧号重算（CFR）。抖音这类源经常给出重复甚至全 0 的
                        // 时间戳：透传会让编码器输出一堆相同 PTS，MediaMuxer 收尾时报错、
                        // 文件没有 moov（时长读不到）；按重复点逐个 +1 帧又会把时间轴拉长。
                        // 我们本来就是固定帧率重编码，直接 frames / fps 最稳。
                        long feedPts = outFrameIdx * frameUs;
                        if (feedEncoder(enc, dec, dIdx, feedPts, outW, outH)) {
                            outFrameIdx++;
                        } else {
                            dropped++;
                        }
                        did = true;
                        if (cb != null && durHintMs > 0) {
                            cb.onProgress((int) Math.min(99,
                                    vInfo.presentationTimeUs / 1000 * 100 / durHintMs));
                        }
                    }
                    dec.releaseOutputBuffer(dIdx, false);
                } else if (dIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 忽略，输出尺寸已知
                }

                // 3) 取编码器输出
                int eIdx = enc.dequeueOutputBuffer(vInfo, 0);
                if (eIdx >= 0) did = true;
                while (eIdx >= 0) {
                    if ((vInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        vInfo.size = 0;   // SPS/PPS 由 muxer 从 format 里取
                    }
                    if (vInfo.size > 0) {
                        if (!muxStarted) {
                            vTrack = mux.addTrack(enc.getOutputFormat());
                            if (aIdx >= 0) {
                                // 音轨是 opus / flac / ac3 这类时，MP4 容器可能直接拒收，
                                // 以前会让整单失败 —— 现在退化成无声视频，至少能用
                                try {
                                    android.media.MediaFormat af = ex.getTrackFormat(aIdx);
                                    String am = af.getString(android.media.MediaFormat.KEY_MIME);
                                    if (am != null && !am.equals("audio/mp4a-latm")) {
                                        Log.i(TAG, "audio mime unsupported for mp4: " + am);
                                    }
                                    aTrack = mux.addTrack(af);
                                    try {
                                        aSampleRate = af.getInteger(
                                                android.media.MediaFormat.KEY_SAMPLE_RATE);
                                    } catch (Exception ignored) {
                                    }
                                } catch (Exception e) {
                                    android.util.Log.w(TAG, "audio track rejected, mute output", e);
                                    aTrack = -1;
                                    aIdx = -1;
                                }
                            }
                            // 解码出来的帧是「未旋转」的，旋转信息交给播放器去转
                            try {
                                if (rot != 0) mux.setOrientationHint(rot);
                            } catch (Exception ignored) {
                            }
                            mux.start();
                            muxStarted = true;
                        }
                        ByteBuffer eb = enc.getOutputBuffer(eIdx);
                        if (eb != null) {
                            eb.position(vInfo.offset);
                            eb.limit(vInfo.offset + vInfo.size);
                            // 编码器偶发给出回退/重复的时间戳，MediaMuxer 会拒绝，
                            // 结果就是文件写了一大半但 moov 没落盘 —— 时长读不到、整单失败。
                            if (vInfo.presentationTimeUs <= lastVPts) {
                                vInfo.presentationTimeUs = lastVPts + 1000;
                                ptsFixed++;
                            }
                            lastVPts = vInfo.presentationTimeUs;
                            mux.writeSampleData(vTrack, eb, vInfo);
                        }
                    }
                    if ((vInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encEos = true;
                    }
                    enc.releaseOutputBuffer(eIdx, false);
                    eIdx = enc.dequeueOutputBuffer(vInfo, 0);
                }
                // 三条路都没进展：让出 CPU 一下，别空转抢编码/解码线程的时间
                if (!did) {
                    idle++;
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            // 4) 音轨直接搬（不重编码）
            if (muxStarted && aTrack >= 0) {
                ex.selectTrack(aIdx);
                // 刚才解码视频把轨道读到了文件尾，不回到开头的话音轨会从中间开始、
                // 时间戳错乱（严重时 muxer 收尾失败）
                try {
                    ex.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                } catch (Exception ignored) {
                }
                ByteBuffer ab = ByteBuffer.allocateDirect(256 * 1024);
                MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
                // AAC 每帧固定 1024 个采样点，时间戳直接按帧号算。
                // 源里的音频时间戳同样不可靠（重复 / 回退），透传会让 muxer 收尾失败，
                // 产物变成没有 moov 的裸数据 —— 时长读不到、文件还不能播。
                long aFrameUs = 1024L * 1_000_000L / Math.max(8000, aSampleRate);
                while (true) {
                    int sz = ex.readSampleData(ab, 0);
                    if (sz < 0) break;
                    ai.offset = 0;
                    ai.size = sz;
                    ai.presentationTimeUs = aFrameIdx * aFrameUs;
                    ai.flags = ex.getSampleFlags();
                    aFrameIdx++;
                    mux.writeSampleData(aTrack, ab, ai);
                    ex.advance();
                }
            }
            Log.i(TAG, "yuv transcode done size=" + out.length()
                    + " drop=" + dropped + " idle=" + idle + " loops=" + guard
                    + " ptsFixed=" + ptsFixed);
        } finally {
            try {
                if (enc != null) {
                    enc.stop();
                    enc.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (dec != null) {
                    dec.stop();
                    dec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (mux != null) {
                    if (muxStarted) {
                        try {
                            mux.stop();
                        } catch (Exception e) {
                            // 走到这里说明 mp4 没写完（moov 缺失），产物不可用
                            muxBroken = true;
                            android.util.Log.w(TAG, "mux.stop() failed: " + e);
                        }
                    }
                    mux.release();
                }
            } catch (Exception ignored) {
            }
            ex.release();
        }
    }

    /** 把解码出来的一帧 YUV 拷进编码器的输入 Image（顺带缩放） */
    private static boolean feedEncoder(MediaCodec enc, MediaCodec dec, int dIdx, long pts,
                                       int outW, int outH) throws Exception {
        Image src = dec.getOutputImage(dIdx);
        if (src == null) return true;
        int inIdx = enc.dequeueInputBuffer(200000);
        if (inIdx < 0) {
            src.close();
            return false;      // 编码器还没腾出输入缓冲，这帧没喂进去
        }
        Image dst = enc.getInputImage(inIdx);
        if (dst == null) {
            src.close();
            enc.queueInputBuffer(inIdx, 0, 0, pts, 0);
            return true;
        }
        int sw = src.getWidth(), sh = src.getHeight();
        for (int p = 0; p < 3; p++) {
            Image.Plane sp = src.getPlanes()[p];
            Image.Plane dp = dst.getPlanes()[p];
            int w = p == 0 ? sw : sw / 2;
            int h = p == 0 ? sh : sh / 2;
            int dw = p == 0 ? outW : outW / 2;
            int dh = p == 0 ? outH : outH / 2;
            int srs = sp.getRowStride(), sps = sp.getPixelStride();
            int drs = dp.getRowStride(), dps = dp.getPixelStride();
            ByteBuffer sb = sp.getBuffer();
            ByteBuffer db = dp.getBuffer();
            boolean same = (dw == w && dh == h && drs == srs && dps == sps);
            if (same) {
                sb.position(0);
                db.position(0);
                int n = Math.min(sb.remaining(), db.remaining());
                ByteBuffer dup = sb.duplicate();
                dup.limit(n);
                db.put(dup);
            } else {
                // 最近邻缩放。
                // 以前是「ByteBuffer 绝对下标逐像素 get/put」，每个像素两次带边界检查的
                // 绝对读写，800 帧就要几千万次 —— 实测降分辨率比不缩放慢 2.6 倍。
                // 现在改成「整行取到 byte[] → 在数组里挑点 → 整行写回」，
                // 全程只有两次批量拷贝 + 廉价的数组下标运算。
                int srcRowBytes = w * sps;
                int dstRowBytes = dw * dps;
                if (rowBuf == null || rowBuf.length < srcRowBytes) rowBuf = new byte[srcRowBytes];
                if (dstBuf == null || dstBuf.length < dstRowBytes) dstBuf = new byte[dstRowBytes];
                boolean onlyVertical = (dw == w && dps == sps);
                // 横向映射表只算一次：以前是每行每像素都做一次除法，
                // 500 帧 × 3 个平面 × 848 像素 = 一百多万次除法
                if (!onlyVertical) {
                    if (xmap == null || xmap.length < dw) xmap = new int[dw];
                    for (int x = 0; x < dw; x++) xmap[x] = (int) ((long) x * w / dw) * sps;
                }
                for (int y = 0; y < dh; y++) {
                    int sy = (int) ((long) y * h / dh);
                    int sPos = sy * srs;
                    int take = Math.min(srcRowBytes, Math.max(0, sb.capacity() - sPos));
                    if (take <= 0) continue;
                    sb.position(sPos);
                    sb.get(rowBuf, 0, take);
                    if (onlyVertical) {
                        System.arraycopy(rowBuf, 0, dstBuf, 0, Math.min(take, dstRowBytes));
                    } else if (sps == 1 && dps == 1) {
                        // Y 平面最常见：直接下标赋值，别走 arraycopy 的方法调用
                        for (int x = 0; x < dw; x++) dstBuf[x] = rowBuf[xmap[x]];
                    } else if (sps == 2 && dps == 2) {
                        // UV 交错平面：一次搬两个字节
                        for (int x = 0; x < dw; x++) {
                            int si = xmap[x];
                            int di = x << 1;
                            dstBuf[di] = rowBuf[si];
                            dstBuf[di + 1] = rowBuf[si + 1];
                        }
                    } else {
                        for (int x = 0; x < dw; x++) {
                            int si = xmap[x];
                            for (int b = 0; b < dps; b++) {
                                int sbIdx = si + Math.min(b, sps - 1);
                                dstBuf[x * dps + b] = sbIdx < take ? rowBuf[sbIdx] : 0;
                            }
                        }
                    }
                    int dPos = y * drs;
                    int put = Math.min(dstRowBytes, Math.max(0, db.capacity() - dPos));
                    if (put > 0) {
                        db.position(dPos);
                        db.put(dstBuf, 0, put);
                    }
                }
            }
        }
        src.close();
        dst.close();
        int size = outW * outH * 3 / 2;
        ByteBuffer ib = enc.getInputBuffer(inIdx);
        if (ib != null && ib.capacity() < size) size = ib.capacity();
        enc.queueInputBuffer(inIdx, 0, size, pts, 0);
        return true;
    }
}
