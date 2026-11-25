package com.zhuanz.bilislicer;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 把 DASH 的「纯视频流」和「纯音频流」合成一个 mp4。
 * 全程不重编码，只搬样本（remux），几秒就能完成。
 */
public class Muxer {

    private static final String TAG = "BiliSlicer";

    public static void remux(File video, File audio, File out) throws IOException {
        MediaExtractor ev = new MediaExtractor();
        MediaExtractor ea = null;
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ev.setDataSource(video.getAbsolutePath());
            int vIdx = pickTrack(ev, "video/");
            if (vIdx < 0) throw new IOException("视频流里没有画面");
            MediaFormat vf = ev.getTrackFormat(vIdx);

            ea = audio != null && audio.exists() ? new MediaExtractor() : null;
            int aIdx = -1;
            MediaFormat af = null;
            if (ea != null) {
                ea.setDataSource(audio.getAbsolutePath());
                aIdx = pickTrack(ea, "audio/");
                if (aIdx >= 0) af = ea.getTrackFormat(aIdx);
            }

            mux = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = mux.addTrack(vf);
            int aOut = -1;
            if (af != null) {
                try {
                    aOut = mux.addTrack(af);
                } catch (Exception e) {
                    Log.w(TAG, "audio track 添加失败，只保留画面", e);
                    aOut = -1;
                }
            }
            mux.start();
            started = true;

            ev.selectTrack(vIdx);
            if (ea != null && aIdx >= 0) {
                ea.selectTrack(aIdx);
            }

            // 两条流按时间戳交替写入，播放器 seek 才顺
            long vPts = ev.getSampleTime();
            long aPts = (ea != null && aIdx >= 0) ? ea.getSampleTime() : -1;
            ByteBuffer buf = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long written = 0;

            while (vPts >= 0 || aPts >= 0) {
                boolean takeVideo = aPts < 0 || (vPts >= 0 && vPts <= aPts);
                MediaExtractor src = takeVideo ? ev : ea;
                if (src == null) break;

                int sz = src.readSampleData(buf, 0);
                if (sz < 0) {
                    if (takeVideo) {
                        vPts = -1;
                        ev.advance();
                    } else {
                        aPts = -1;
                        ea.advance();
                    }
                    continue;
                }
                info.offset = 0;
                info.size = sz;
                info.presentationTimeUs = src.getSampleTime();
                info.flags = src.getSampleFlags();
                mux.writeSampleData(takeVideo ? vOut : aOut, buf, info);
                written++;
                src.advance();

                vPts = ev.getSampleTime();
                if (ea != null && aIdx >= 0) aPts = ea.getSampleTime();
                else aPts = -1;
            }

            mux.stop();
            started = false;
            Log.i(TAG, "remux done samples=" + written + " out=" + out.length());
        } finally {
            try {
                if (mux != null && started) mux.stop();
            } catch (Exception ignored) {
            }
            try {
                if (mux != null) mux.release();
            } catch (Exception ignored) {
            }
            try {
                ev.release();
            } catch (Exception ignored) {
            }
            try {
                if (ea != null) ea.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static int pickTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }
}
