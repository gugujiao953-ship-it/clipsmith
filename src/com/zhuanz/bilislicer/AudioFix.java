package com.zhuanz.bilislicer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 音轨重编码。
 *
 * 视频一直是重编码的，但音轨此前是原样拷贝 —— 源音轨多高成品就多高。
 * 有些抖音视频的音轨是 1Mbps，38 秒就 5MB，把「每段 9MB」的预算吃掉一半，
 * 于是怎么调视频码率都压不下来。
 *
 * 这个类把它重编码到 96~128k，一条 180 秒的视频音轨从 20MB 降到 3MB 左右。
 */
public class AudioFix {

    private static final String TAG = "BiliSlicer";

    /**
     * 实测音轨码率：把音频样本的字节数加起来除以时长。
     * 别信容器里写的 KEY_BIT_RATE —— 有些源标 128k，实际流是 1Mbps。
     */
    public static int audioKbpsMeasured(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            int aIdx = -1;
            long durUs = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                String m = mf.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    aIdx = i;
                    if (mf.containsKey(MediaFormat.KEY_DURATION)) {
                        durUs = mf.getLong(MediaFormat.KEY_DURATION);
                    }
                    break;
                }
            }
            if (aIdx < 0 || durUs <= 0) return 0;
            ex.selectTrack(aIdx);
            ByteBuffer buf = ByteBuffer.allocateDirect(512 * 1024);
            long bytes = 0;
            while (true) {
                int sz = ex.readSampleData(buf, 0);
                if (sz < 0) break;
                bytes += sz;
                if (!ex.advance()) break;
            }
            return (int) (bytes * 8_000_000L / durUs / 1000);
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 源音轨码率（kbps），读不到返回 0 */
    public static int audioKbps(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                String m = mf.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")
                        && mf.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    return mf.getInteger(MediaFormat.KEY_BIT_RATE) / 1000;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /** 把音轨解码再编码成 kbps 的 AAC，输出独立的 m4a */
    public static void reencode(File in, File out, int kbps) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());
        int aIdx = -1;
        MediaFormat af = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat mf = ex.getTrackFormat(i);
            String m = mf.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) {
                aIdx = i;
                af = mf;
                break;
            }
        }
        if (aIdx < 0 || af == null) {
            ex.release();
            throw new Exception("没有音轨");
        }
        String mime = af.getString(MediaFormat.KEY_MIME);
        int sampleRate = af.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? af.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = af.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? af.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        long durUs = af.containsKey(MediaFormat.KEY_DURATION)
                ? af.getLong(MediaFormat.KEY_DURATION) : 0;

        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        dec.configure(af, null, null, 0);
        dec.start();

        MediaFormat ef = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels);
        ef.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        ef.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
        ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
        enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean muxStarted = false;
        int aTrack = -1;

        ex.selectTrack(aIdx);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteBuffer buf = ByteBuffer.allocateDirect(512 * 1024);
        int inIdx = -1;
        boolean inputDone = false, outputDone = false;
        int idle = 0;
        long guard = 0;

        try {
            while (!outputDone && guard++ < 200000) {
                if (inIdx < 0 && !inputDone) inIdx = dec.dequeueInputBuffer(2000);
                if (inIdx >= 0) {
                    int sz = ex.readSampleData(buf, 0);
                    if (sz < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        inIdx = -1;
                    } else {
                        ByteBuffer ib = dec.getInputBuffer(inIdx);
                        if (ib != null) {
                            ib.clear();
                            buf.position(0);
                            buf.limit(sz);
                            ib.put(buf);
                            dec.queueInputBuffer(inIdx, 0, sz,
                                    Math.max(0, ex.getSampleTime()), 0);
                        }
                        ex.advance();
                        inIdx = -1;
                    }
                }
                boolean did = false;
                int dIdx = dec.dequeueOutputBuffer(info, 0);
                while (dIdx >= 0) {
                    did = true;
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    ByteBuffer db = dec.getOutputBuffer(dIdx);
                    if (db != null && info.size > 0) {
                        db.position(info.offset);
                        db.limit(info.offset + info.size);
                        int eIn = enc.dequeueInputBuffer(100000);
                        if (eIn >= 0) {
                            ByteBuffer eb = enc.getInputBuffer(eIn);
                            if (eb != null) {
                                eb.clear();
                                eb.put(db);
                                enc.queueInputBuffer(eIn, 0, info.size, info.presentationTimeUs, 0);
                            }
                        }
                    }
                    dec.releaseOutputBuffer(dIdx, false);
                    if (eos) {
                        int eIn = enc.dequeueInputBuffer(200000);
                        if (eIn >= 0) {
                            enc.queueInputBuffer(eIn, 0, 0, info.presentationTimeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                        break;
                    }
                    dIdx = dec.dequeueOutputBuffer(info, 0);
                }
                int eIdx = enc.dequeueOutputBuffer(info, 0);
                while (eIdx >= 0) {
                    did = true;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0) {
                        if (!muxStarted) {
                            aTrack = mux.addTrack(enc.getOutputFormat());
                            mux.start();
                            muxStarted = true;
                        }
                        ByteBuffer eb = enc.getOutputBuffer(eIdx);
                        if (eb != null) {
                            eb.position(info.offset);
                            eb.limit(info.offset + info.size);
                            mux.writeSampleData(aTrack, eb, info);
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    enc.releaseOutputBuffer(eIdx, false);
                    if (eos) {
                        outputDone = true;
                        break;
                    }
                    eIdx = enc.dequeueOutputBuffer(info, 0);
                }
                if (!did) {
                    idle++;
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {
                    }
                    if (idle > 4000) break;
                } else {
                    idle = 0;
                }
            }
            Log.i(TAG, "audio reencode done " + out.length() / 1024 + "KB @" + kbps + "k"
                    + " src=" + durUs / 1000 + "ms");
        } finally {
            try {
                dec.stop();
                dec.release();
            } catch (Throwable ignored) {
            }
            try {
                enc.stop();
                enc.release();
            } catch (Throwable ignored) {
            }
            try {
                if (muxStarted) mux.stop();
                mux.release();
            } catch (Throwable ignored) {
            }
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 用新的音轨替换视频文件里的原音轨（只拷样本，很快） */
    public static void remuxWithAudio(File videoIn, File audioIn, File out) throws Exception {
        MediaExtractor vx = new MediaExtractor();
        vx.setDataSource(videoIn.getAbsolutePath());
        MediaExtractor ax = new MediaExtractor();
        ax.setDataSource(audioIn.getAbsolutePath());

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started = false;
        try {
            int vIdx = -1;
            for (int i = 0; i < vx.getTrackCount(); i++) {
                String m = vx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    vIdx = i;
                    break;
                }
            }
            int aIdx = -1;
            for (int i = 0; i < ax.getTrackCount(); i++) {
                String m = ax.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    aIdx = i;
                    break;
                }
            }
            if (vIdx < 0) throw new Exception("视频文件没有画面轨");
            int vTrack = mux.addTrack(vx.getTrackFormat(vIdx));
            int aTrack = aIdx >= 0 ? mux.addTrack(ax.getTrackFormat(aIdx)) : -1;
            mux.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

            vx.selectTrack(vIdx);
            while (true) {
                int sz = vx.readSampleData(buf, 0);
                if (sz < 0) break;
                bi.offset = 0;
                bi.size = sz;
                bi.presentationTimeUs = Math.max(0, vx.getSampleTime());
                bi.flags = vx.getSampleFlags();
                mux.writeSampleData(vTrack, buf, bi);
                if (!vx.advance()) break;
            }
            if (aTrack >= 0) {
                ax.selectTrack(aIdx);
                while (true) {
                    int sz = ax.readSampleData(buf, 0);
                    if (sz < 0) break;
                    bi.offset = 0;
                    bi.size = sz;
                    bi.presentationTimeUs = Math.max(0, ax.getSampleTime());
                    bi.flags = ax.getSampleFlags();
                    mux.writeSampleData(aTrack, buf, bi);
                    if (!ax.advance()) break;
                }
            }
        } finally {
            try {
                vx.release();
            } catch (Throwable ignored) {
            }
            try {
                ax.release();
            } catch (Throwable ignored) {
            }
            try {
                if (started) mux.stop();
                mux.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
