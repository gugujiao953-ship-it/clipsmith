package com.zhuanz.bilislicer;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 一条链接的完整处理流程：解析 → 下载最低画质 → 按需压缩 → 切分 → 存进片库 */
public class Pipeline {

    /** 用户点了取消 */
    public static class Canceled extends RuntimeException {
        public Canceled() {
            super("已取消");
        }
    }

    static void checkCancel() {
        if (Jobs.isCanceled()) throw new Canceled();
    }

    public interface Log {
        void line(String s);
    }

    /**
     * 抖音等平台会给同一视频多个 CDN 地址，某些域名在特定网络下解析不了
     * （aweme.snssdk.com 就很常见）。逐个试，全都失败才报错。
     */
    private static void downloadAny(Source.Media media, File dest, Progress prog)
            throws Exception {
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (media.playUrl != null) urls.add(media.playUrl);
        if (media.altUrls != null) {
            for (String u : media.altUrls) if (!urls.contains(u)) urls.add(u);
        }
        Exception last = null;
        for (int i = 0; i < urls.size(); i++) {
            final int idx = i;
            try {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                Downloader.download(urls.get(i), dest, media.ua, media.referer,
                        (got, total) -> {
                            checkCancel();
                            if (prog != null) {
                                prog.pct(total > 0 ? (int) (got * 55 / total) : 55);
                            }
                        });
                // CDN 有时只给一部分就断开；按服务端声明的 data_size 续传补齐
                if (media.expectedSize > 0) {
                    for (int k = 0; k < 3 && dest.length() < media.expectedSize; k++) {
                        try {
                            Downloader.append(urls.get(i), dest, media.ua, media.referer,
                                    (got, total) -> {
                                        checkCancel();
                                        if (prog != null && media.expectedSize > 0) {
                                            prog.pct((int) Math.min(55,
                                                    got * 55 / media.expectedSize));
                                        }
                                    });
                        } catch (Exception stop) {
                            break;
                        }
                        if (dest.length() >= media.expectedSize) break;
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                android.util.Log.i("BiliSlicer", "dl# " + i + " host="
                        + hostOf(urls.get(i)) + " len=" + dest.length()
                        + " expect=" + media.expectedSize);
                // CDN 有时返回 200 + 一个错误页，字节数够但根本不是视频
                if (validMedia(dest)) return;
                last = new java.io.IOException("下载回来的不是有效视频（CDN 返回了错误页）");
                logHead(dest);
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            } catch (Exception e) {
                last = e;
            }
        }
        String msg = last == null ? "没有可用地址" : String.valueOf(last.getMessage());
        if (msg != null && (msg.contains("resolve host") || msg.contains("UnknownHost"))) {
            msg = "播放地址的域名解析不了（" + msg + "），可能是网络/DNS 拦截了抖音 CDN，"
                    + "换 Wi-Fi 或移动数据再试";
        }
        throw new java.io.IOException(msg);
    }

    /**
     * 判断下载回来的东西是不是「完整可用的视频」：
     * 光有轨道还不够 —— CDN 断流时 moov 在，mdat 却是空的，读不到任何样本。
     */
    /** 校验失败时把文件头和轨道信息打出来，方便定位到底下到了什么 */
    private static void logHead(File f) {
        try {
            byte[] b = new byte[32];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(n, 16); i++) {
                hex.append(String.format("%02x ", b[i]));
            }
            String trackInfo;
            android.media.MediaExtractor ex = new android.media.MediaExtractor();
            try {
                ex.setDataSource(f.getAbsolutePath());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    String mime = ex.getTrackFormat(i)
                            .getString(android.media.MediaFormat.KEY_MIME);
                    boolean selOk;
                    try {
                        ex.selectTrack(i);
                        selOk = true;
                    } catch (Exception e) {
                        selOk = false;
                    }
                    long firstTime = -1;
                    try {
                        firstTime = ex.getSampleTime();
                    } catch (Exception ignored) {
                    }
                    sb.append(mime).append("[sel=").append(selOk)
                            .append(",t=").append(firstTime).append("] ");
                }
                trackInfo = sb.length() == 0 ? "无轨道" : sb.toString();
            } catch (Exception e) {
                trackInfo = "打不开:" + e.getMessage();
            } finally {
                try {
                    ex.release();
                } catch (Exception ignored) {
                }
            }
            android.util.Log.w("BiliSlicer", "bad file len=" + f.length()
                    + " hex=" + hex + " tracks=" + trackInfo);

        } catch (Exception ignored) {
        }
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean validMedia(File f) {
        if (f == null || f.length() < 100 * 1024) return false;
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            if (ex.getTrackCount() <= 0) return false;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                try {
                    ex.selectTrack(i);
                } catch (Exception ignored) {
                }
            }
            // 真正读一个样本出来，比看时间戳靠谱（时间戳可能是负数）
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(1 << 20);
            return ex.readSampleData(buf, 0) > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {
            }
        }
    }

    public interface Progress {
        void pct(int v);
    }

    /** 需要用户拍板才能继续的情况（重复 / 多分P） */
    public static class NeedConfirm extends Exception {
        public final String kind;    // "dup" | "multipart"
        public final String key;     // 去重 key
        public final String url;
        public final String title;
        public final int pages;
        public final String oldInfo;
        public String bvid = "";
        public int preferPage;        // 链接里指定的第几P（0 = 没指定）
        public java.util.List<BiliApi.Episode> seasonList;
        public java.util.List<BiliApi.Page> pageList;

        NeedConfirm(String kind, String key, String url, String title, int pages, String oldInfo) {
            super(kind);
            this.kind = kind;
            this.key = key;
            this.url = url;
            this.title = title;
            this.pages = pages;
            this.oldInfo = oldInfo;
        }
    }

    public static class Result {
        public String key;
        public String platform;
        public String title;
        public long bytes;
        public int parts;
        public final List<Uri> uris = new ArrayList<>();
    }

    /**
     * 先按时间把源切成若干段，再逐段转码。
     *
     * 为什么不用「整片转码再切」：这台机器上整片转码的产物一旦到 60MB 量级，
     * MediaMuxer 收尾就会失败（moov 不落盘），文件读不到时长也没法播。
     * 拆成十来兆的小段后每段都能正常收尾，顺带码率控制也更准。
     */
    static java.util.List<File> encodeInParts(File src, File outDir, String base, long partUs,
                                              long partBytes, int kbps, int width,
                                              long startUs, long endUs,
                                              File work, Log log, Progress prog)
            throws Exception {
        java.util.List<File> outs = new java.util.ArrayList<>();
        File segDir = new File(work, "seg");
        //noinspection ResultOfMethodCallIgnored
        segDir.mkdirs();
        Splitter.Result r = Splitter.split(src, segDir, base, partBytes, partUs,
                startUs, endUs, null);
        java.util.List<File> segs = r.files;
        Diag.line("切段待压", segs.size() + " 段");
        final int segTotal = Math.max(1, segs.size());
        for (int i = 0; i < segs.size(); i++) {
            checkCancel();
            final int segNo = i;
            File seg = segs.get(i);
            File out = new File(outDir, seg.getName());
            long segDurMs = durationUsOf(seg) / 1000;
            boolean ok = false;
            if (segDurMs > 0) {
                try {
                    Transcoder.transcode(seg, out, kbps * 1000, width, 1, segDurMs, pct -> {
                        checkCancel();
                        if (prog != null) {
                            prog.pct(55 + (segNo * 100 + pct) * 35 / segTotal / 100);
                        }
                    });
                    ok = durationUsOf(out) > 0;
                    if (!ok) Diag.line("分段产物异常", seg.getName() + " " + Library.mb(out.length()));
                    // 这台机器的编码器不老实按码率走（实测能超发到 2-3 倍），
                    // 所以压完看体积，超了就按比例重压一次，保证每段真的装得下。
                    if (ok && out.length() > partBytes * 11 / 10 && segDurMs > 0) {
                        int kbps2 = (int) Math.max(150,
                                (double) kbps * partBytes / Math.max(1, out.length()));
                        Diag.line("超发重压", seg.getName() + " " + Library.mb(out.length())
                                + " @" + kbps + "k -> " + kbps2 + "k");
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        try {
                            Transcoder.transcode(seg, out, kbps2 * 1000, width, 1, segDurMs, null);
                            ok = durationUsOf(out) > 0;
                        } catch (Pipeline.Canceled c) {
                            throw c;
                        } catch (Exception e) {
                            ok = false;
                        }
                        // 降码率都不管用（这台机器的编码器不认码率参数），
                        // 那就只能从分辨率下手，画面小一半数据量自然下来。
                        if (ok && out.length() > partBytes * 11 / 10) {
                            int w2 = Math.max(400, width * 2 / 3);
                            Diag.line("降分辨率重压", seg.getName() + " " + Library.mb(out.length())
                                    + " 宽" + width + " -> " + w2);
                            //noinspection ResultOfMethodCallIgnored
                            out.delete();
                            try {
                                Transcoder.transcode(seg, out, kbps2 * 1000, w2, 1, segDurMs, null);
                                ok = durationUsOf(out) > 0;
                            } catch (Pipeline.Canceled c) {
                                throw c;
                            } catch (Exception e) {
                                ok = false;
                            }
                        }
                    }
                } catch (Pipeline.Canceled c) {
                    throw c;
                } catch (Exception e) {
                    Diag.line("分段转码异常", seg.getName() + " " + e);
                    // 这台机器同时能开的硬编码器实例有限（常见的就 2 个），
                    // 连续分段时容易撞上「start failed」——等实例回收后重试一次。
                    if (String.valueOf(e.getMessage()).contains("start failed")) {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                        }
                        try {
                            Transcoder.transcode(seg, out, kbps * 1000, width, 1, segDurMs, null);
                            ok = durationUsOf(out) > 0;
                        } catch (Exception e2) {
                            Diag.line("分段转码重试失败", seg.getName() + " " + e2);
                            ok = false;
                        }
                    }
                }
            }
            if (!ok) {
                // 这一段没压成：直接用未压缩的段，至少能播
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                copyFile(seg, out);
                log.line("   ⚠ 第 " + (i + 1) + " 段压缩失败，保留原画质");
            }
            outs.add(out);
            // 给编码器实例留点回收时间，不然下一段容易 start failed
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
            }
        }
        return outs;
    }

    /** 采样几百帧看时间戳是否重复/回退（坏容器的典型症状） */
    static boolean timestampsLookBroken(File f) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            int v = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    v = i;
                    break;
                }
            }
            if (v < 0) return false;
            ex.selectTrack(v);
            long last = Long.MIN_VALUE;
            int n = 0, bad = 0;
            while (n < 800) {
                long t = ex.getSampleTime();
                if (t < 0) break;
                if (t <= last) bad++;
                last = t;
                n++;
                if (!ex.advance()) break;
            }
            return n > 60 && bad > n / 20;   // 超过 5% 重复/回退就当坏了
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 只重写时间轴，不动画面数据：按轨道分别用「样本序号 × 帧长」给新时间戳。
     * 拷贝很快（不解码），但能让时长、码率、切分全部回到正常轨道。
     */
    static void normalizeTimestamps(File in, File out) throws Exception {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());
        android.media.MediaMuxer mux = new android.media.MediaMuxer(out.getAbsolutePath(),
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int tracks = ex.getTrackCount();
        int[] map = new int[tracks];
        java.util.Arrays.fill(map, -1);
        long[] frameUs = new long[tracks];
        long[] idx = new long[tracks];
        int vTrack = -1;
        boolean started = false;
        try {
            for (int i = 0; i < tracks; i++) {
                android.media.MediaFormat mf = ex.getTrackFormat(i);
                String m = mf.getString(android.media.MediaFormat.KEY_MIME);
                try {
                    map[i] = mux.addTrack(mf);
                } catch (Exception e) {
                    map[i] = -1;
                    continue;
                }
                if (m != null && m.startsWith("video/")) {
                    int fps = mf.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)
                            ? mf.getInteger(android.media.MediaFormat.KEY_FRAME_RATE) : 30;
                    if (fps <= 0 || fps > 120) fps = 30;
                    frameUs[i] = 1_000_000L / fps;
                    if (vTrack < 0) vTrack = i;
                } else {
                    int sr = mf.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            ? mf.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) : 44100;
                    frameUs[i] = 1024L * 1_000_000L / Math.max(8000, sr);
                }
                ex.selectTrack(i);
            }
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(4 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
            while (true) {
                int from = ex.getSampleTrackIndex();
                if (from < 0) break;
                if (map[from] >= 0) {
                    int sz = ex.readSampleData(buf, 0);
                    if (sz > 0) {
                        if (!started) {
                            mux.start();
                            started = true;
                        }
                        bi.offset = 0;
                        bi.size = sz;
                        bi.presentationTimeUs = idx[from] * frameUs[from];
                        bi.flags = ex.getSampleFlags();
                        mux.writeSampleData(map[from], buf, bi);
                    }
                    idx[from]++;
                }
                if (!ex.advance()) break;
            }
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
            if (started) {
                try {
                    mux.stop();
                } catch (Throwable ignored) {
                }
            }
            try {
                mux.release();
            } catch (Throwable ignored) {
            }
        }
    }


    static void copyFile(File src, File dst) throws Exception {
        java.nio.channels.FileChannel in = new java.io.FileInputStream(src).getChannel();
        java.nio.channels.FileChannel out = new java.io.FileOutputStream(dst).getChannel();
        try {
            long total = in.size(), done = 0;
            while (done < total) {
                long n = in.transferTo(done, total - done, out);
                if (n <= 0) break;
                done += n;
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    static void del(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) del(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String platformName(String p) {
        if ("bilibili".equals(p)) return "B站";
        if ("douyin".equals(p)) return "抖音";
        if ("kuaishou".equals(p)) return "快手";
        return "视频";
    }

    /** 从文件里读真实时长（各平台都给不准，直接读最稳） */
    /** 有没有音轨 */
    static boolean hasAudio(File f) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) return true;
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 读源文件的视频编码，用来判断「不重编码直接切」是否安全 */
    static String videoMimeOf(File f) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat mf = ex.getTrackFormat(i);
                String m = mf.getString(android.media.MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) return m;
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static long durationOf(File f) {
        long us = durationUsOf(f);
        return us > 0 ? us / 1000 : 0;
    }

    /** 微秒级时长，三级兜底（与 Splitter 保持一致） */
    static long durationUsOf(File f) {
        long us = Splitter.durationUsOf(f);
        if (us > 0) return us;
        android.util.Log.w("BiliSlicer", "duration unreadable: " + f.getName());
        return 0;
    }

    public static Result run(Context ctx, String url, double limitMb, int wantParts,
                             boolean force, Log log, Progress prog) throws Exception {
        Result res = new Result();

        File work = new File(ctx.getCacheDir(), "job");
        del(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new Exception("建临时目录失败");

        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("bili", android.content.Context.MODE_PRIVATE);
            boolean hq = sp.getBoolean("hq", true);
            double partMin = sp.getFloat("partMin", 0f);   // 分钟，0 = 按体积切
            int maxKbps = sp.getInt("maxKbps", 900);        // 码率上限，0 = 不限

            log.line("① 解析链接…");
            Diag.line("解析", "请求 " + url);
            Source.Media media = Source.resolve(ctx, url, hq);
            // 多分P 的标题带上 P 号，否则记录里几条一模一样分不清
            res.title = media.pageCount > 1
                    ? (media.title + " · P" + media.page) : media.title;
            Diag.line("解析成功", media.platform + " id=" + media.id
                    + " 分P=" + (media.page + "/" + media.pageCount) + " 《" + media.title + "》");
            res.key = media.id;
            res.platform = media.platform;
            android.util.Log.i("BiliSlicer", "resolved id=" + media.id + " page=" + media.page
                    + " pageCount=" + media.pageCount + " pages=" + media.pages.size()
                    + " season=" + (media.season == null ? -1 : media.season.size())
                    + " explicit=" + media.explicitPage);

            // 1) 多分P：链接没指定第几P时，先问清楚
            // 合集 / 系列：一条链接背后是一整个系列，先问要不要一起下
            if (media.season != null && media.season.size() > 1 && !force) {
                NeedConfirm sc = new NeedConfirm("season", media.bvid, url,
                        media.seasonTitle.isEmpty() ? media.title : media.seasonTitle,
                        media.season.size(), null);
                sc.bvid = media.bvid;
                sc.seasonList = media.season;
                throw sc;
            }

            // 多P 一律让用户自己选（链接带了 ?p=N 就预选那一集，但仍然给他看列表）
            if (media.pageCount > 1 && !force) {
                NeedConfirm nc = new NeedConfirm("multipart", media.id, url, media.title,
                        media.pageCount, null);
                nc.bvid = media.bvid;
                nc.pageList = media.pages;
                nc.preferPage = media.explicitPage ? media.page : 0;
                throw nc;
            }
            // 2) 查重（放在解析之后，短链 b23.tv 也能识别出来）
            if (!force) {
                History.Rec old = History.find(ctx, media.id);
                if (old != null) {
                    String oldInfo = old.parts + " 段 · " + Library.mb(old.size);
                    throw new NeedConfirm("dup", media.id, url, media.title, 0, oldInfo);
                }
            }
            log.line("   " + platformName(media.platform) + "《" + media.title + "》");

            File raw = new File(work, "raw.mp4");
            log.line("② 下载中…" + (media.quality.isEmpty() ? "" : "（" + media.quality + "）"));
            if (media.audioUrl != null && !media.audioUrl.isEmpty()) {
                // DASH：画面和声音是两条流，下完再合流（纯 remux，不重编码）
                File vf = new File(work, "v.mp4");
                File af = new File(work, "a.mp4");
                Downloader.download(media.playUrl, vf, media.ua, media.referer, (got, total) -> {
                    if (prog != null) prog.pct(total > 0 ? (int) (got * 45 / total) : 45);
                });
                Downloader.download(media.audioUrl, af, media.ua, media.referer, (got, total) -> {
                    if (prog != null) prog.pct(45 + (total > 0 ? (int) (got * 10 / total) : 10));
                });
                log.line("   合流音视频…");
                Muxer.remux(vf, af, raw);
                //noinspection ResultOfMethodCallIgnored
                vf.delete();
                //noinspection ResultOfMethodCallIgnored
                af.delete();
            } else {
                downloadAny(media, raw, prog);
            }
            String base = Store.safe(media.title);
            if (media.pageCount > 1) base = base + "_P" + media.page;
            return finish(ctx, work, raw, res, base, limitMb, wantParts, partMin, maxKbps,
                    sp.getFloat("totalMb", 0f), sp.getFloat("skipSec", 0f),
                    sp.getFloat("tailSec", 0f), log, prog);
        } finally {
            del(work);
        }
    }

    /** 本地文件：拷进临时目录后走同一条路 */
    public static Result runLocal(Context ctx, android.net.Uri uri, String displayName,
                                  double limitMb, int wantParts, Log log, Progress prog)
            throws Exception {
        Result res = new Result();
        File work = new File(ctx.getCacheDir(), "job");
        del(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new Exception("建临时目录失败");
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("bili", android.content.Context.MODE_PRIVATE);
            double partMin = sp.getFloat("partMin", 0f);
            int maxKbps = sp.getInt("maxKbps", 900);

            log.line("① 读取本地视频…");
            File raw = new File(work, "raw.mp4");
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
                 java.io.OutputStream out = new java.io.FileOutputStream(raw)) {
                if (in == null) throw new Exception("读不到这个文件");
                byte[] buf = new byte[1 << 16];
                int n;
                long got = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (prog != null) prog.pct((int) Math.min(50, got / 200000));
                }
            }
            long rawSize = raw.length();
            if (rawSize < 10000) throw new Exception("这个文件太小，可能不是视频");

            String name = displayName == null ? "" : displayName.trim();
            if (name.isEmpty()) name = "本地视频";
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            String base = Store.safe(name);

            res.title = name;
            res.key = "local_" + Math.abs((name + rawSize).hashCode());
            res.platform = "local";
            log.line("   《" + name + "》 " + Library.mb(rawSize));

            return finish(ctx, work, raw, res, base, limitMb, wantParts, partMin, maxKbps,
                    sp.getFloat("totalMb", 0f), sp.getFloat("skipSec", 0f),
                    sp.getFloat("tailSec", 0f), log, prog);
        } finally {
            del(work);
        }
    }

    /** 从「已就绪的原片」跑完压缩 / 切分 / 入库；链接下载和本地文件共用这一段 */
    static Result finish(Context ctx, File work, File raw, Result res, String base,
                         double limitMb, int wantParts, double partMin, int maxKbps,
                         double totalMb, double skipSec, double tailSec,
                         Log log, Progress prog) throws Exception {
            // 有些下载源（抖音最常见）给的样本时间戳是坏的：大量重复甚至回退。
            // 后果一路往下传——编码器输出暴涨、muxer 收尾失败、切分切歪。
            // 这里先不改画质地重写一遍时间轴，后面所有步骤就都正常了。
            if (timestampsLookBroken(raw)) {
                try {
                    File fixed = new File(work, "norm.mp4");
                    normalizeTimestamps(raw, fixed);
                    if (durationUsOf(fixed) > 0 && fixed.length() > 10000) {
                        Diag.line("时间戳异常", "已重写时间轴 " + Library.mb(raw.length())
                                + " -> " + Library.mb(fixed.length()));
                        log.line("   源时间戳有问题（重复/回退），已重写时间轴");
                        raw = fixed;
                    }
                } catch (Exception e) {
                    Diag.line("时间戳归一化失败", e.toString());
                }
            }

            long rawSize = raw.length();
            long durMs = durationOf(raw);
            Diag.line("素材就绪", "原片 " + Library.mb(rawSize) + "，时长 " + (durMs / 1000) + "s");
            log.line("   已下载 " + Library.mb(rawSize));
            if (rawSize < 10000) throw new Exception("下载到的文件异常（可能链接失效）");

            // 裁切：跳过开头 skipSec 秒 / 去掉结尾 tailSec 秒
            long startUs = (long) Math.max(0, skipSec * 1e6);
            long endUs = tailSec > 0 ? (long) ((durMs / 1000.0 - tailSec) * 1e6) : 0;
            double fullSec = durMs > 2000 ? durMs / 1000.0 : 0;
            double seconds = fullSec - Math.max(0, skipSec) - Math.max(0, tailSec);
            if (fullSec > 0 && seconds <= 0) {
                throw new Exception("跳过开头 + 裁掉结尾 已经超过视频总长（" + (int) fullSec + " 秒）");
            }
            if (startUs > 0 || endUs > 0) {
                log.line("   裁切：去掉开头 " + (int) skipSec + " 秒"
                        + (tailSec > 0 ? "、结尾 " + (int) tailSec + " 秒" : "")
                        + "，成片约 " + (int) seconds + " 秒");
            }

            // 「整片目标大小」模式：算总码率，强制压成 1 个文件
            boolean byTotal = totalMb > 0 && seconds > 0;
            double srcKbps = seconds > 0 ? rawSize * 8.0 / 1000.0 / seconds : 999999;
            // 裁切后的有效内容本身就不超目标，就别重编码了，只裁剪
            double effBytes = srcKbps * 1000.0 * seconds / 8.0;
            boolean needCompress4Total = byTotal && effBytes > totalMb * 1e6 * 0.95;
            if (byTotal) wantParts = 1;

            double partBytes = (byTotal ? totalMb * 1.02 : (limitMb - 0.4)) * 1e6;

            // 规则：每段至少要有 2 分钟。若 2 分钟的内容就装不进一段，
            // 说明码率偏高 —— 这种情况不继续切碎，而是先压缩。
            long partUs = partMin > 0 ? (long) (partMin * 60_000_000L) : 0L;
            // 每段的「时长预算」：取「每段时长」和「按体积算出来的段长」里更小的那个。
            // 只用每段时长会在一分钟一段的视频上把码率压得过狠。
            int nBySize = (int) Math.max(1, Math.ceil(rawSize / partBytes));
            double partSec = seconds > 0 ? seconds / nBySize : 120.0;
            if (partUs > 0) partSec = Math.min(partSec, partUs / 1e6);
            if (partSec < 1) partSec = 1;
            // 这段时长里能装多少码率，才是体积上限对应的码率
            double capKbps = partBytes * 8.0 / 1000.0 / partSec;
            // 滞回：只超一点点就重压，体积几乎不省、画质白掉一层，不划算
            // 「码率上限」是用户主动设的硬指标，优先级最高
            boolean byCap = maxKbps > 0 && seconds > 0 && srcKbps > maxKbps * 1.08;
            boolean heavy = (seconds > 0 && srcKbps > capKbps * 1.15) || byCap || needCompress4Total;

            int autoParts = (int) Math.max(1, Math.ceil(rawSize / partBytes));
            if (byTotal) { autoParts = 1; partUs = 0; }   // 整片一个文件，不再分段
            int plan = (wantParts > 0) ? Math.min(wantParts, autoParts) : autoParts;

            // 压完如果不能少切一段，那这趟重编码毫无意义
            // （除非是用户设了码率上限，那种情况本来就是为了缩体积）
            if (heavy && !byCap) {
                double afterKbps = Math.max(120, capKbps * 0.92) + 64;
                int partsAfter = (int) Math.max(1,
                        Math.ceil(rawSize * (afterKbps / srcKbps) / partBytes));
                if (partsAfter >= autoParts) heavy = false;
            }
            // 多分P：每个 P 单独一个文件夹 / 前缀，否则不同 P 会切出同名文件互相覆盖，
            // 而且「清空旧片段」是按文件夹来的，会误删同视频其它 P 的文件

            File outDir = new File(work, "out");
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();

            List<File> outs = new ArrayList<>();
            if (durMs <= 2000) plan = autoParts;
            if (!heavy && plan >= autoParts) {
                log.line("③ 需切 " + autoParts + " 段（每段上限 "
                        + String.format(Locale.CHINA, "%.1f", partBytes / 1e6)
                        + " MB），无需压缩，直接切…");
                if (autoParts <= 1 && startUs == 0 && endUs == 0) {
                    // 不切分时不能直接存临时文件，否则片库里会冒出个 raw.mp4
                    File single = new File(work, base + ".mp4");
                    if (!raw.renameTo(single)) single = raw;
                    outs.add(single);
                    if (prog != null) prog.pct(100);
                } else {
                    Splitter.Result r0 = Splitter.split(raw, outDir, base, partBytes, partUs,
                            startUs, endUs,
                            (part, total) -> {
                                checkCancel();
                                if (prog != null) prog.pct(90 + part * 10 / Math.max(1, total));
                            });
                    outs.addAll(r0.files);
                }
            } else {
                int videoKbps;
                int targetWidth = 0;
                if (needCompress4Total) {
                    // 留 8% 余量：CBR 实际输出会略超理论值，不预留就会被切成两段
                    // 实测 CBR 输出会比理论值高 10~15%（关键帧开销），
                    // 系数留到 0.85 才能真正落在目标以内
                    double totalKbps = totalMb * 0.85 * 1e6 * 8 / 1000.0 / Math.max(1, seconds);
                    videoKbps = (int) Math.max(80, totalKbps - 64);
                    targetWidth = videoKbps > 700 ? 848
                            : videoKbps > 420 ? 720
                            : videoKbps > 260 ? 640 : 480;
                    log.line("③ 整条压到 " + String.format(Locale.CHINA, "%.1f", totalMb)
                            + " MB 以内 → 约 " + videoKbps + "k / " + targetWidth + " 宽…");
                } else if (byCap) {
                    // 用户设了码率上限：直接压到上限（再留一点容器开销）
                    videoKbps = (int) Math.max(90, maxKbps * 0.95);
                    log.line("③ 码率 " + (int) srcKbps + "k 超过上限 " + maxKbps
                            + "k，压到约 " + videoKbps + "k…");
                } else if (heavy) {
                    // 码率偏高：按「2 分钟装进一段」的上限来压，再留 8% 余量
                    videoKbps = (int) Math.max(120, capKbps * 0.92 - 64);
                    log.line("③ 码率偏高（约 " + (int) srcKbps + "k，"
                            + (int) partSec + " 秒就会超上限），先压到约 "
                            + videoKbps + "k…");
                } else {
                    log.line("③ 压到 " + plan + " 段（每段上限 "
                            + String.format(Locale.CHINA, "%.1f", partBytes / 1e6)
                            + " MB），压缩中…");
                    double budgetKbps = partBytes * 8 * plan / 1000.0
                            / Math.max(1, seconds);
                    videoKbps = (int) Math.max(90, budgetKbps - 64);
                }

                // 要裁的话先裁再压：样本级拷贝几乎不耗时，
                // 省掉的是「把片头几十秒完整解码+编码一遍」的算力
                File encodeSrc = raw;
                final long preTrimStart = startUs;
                final long preTrimEnd = endUs;
                long encStart = startUs, encEnd = endUs;
                long encDurMs = durMs;
                if (startUs > 0 || endUs > 0) {
                    try {
                        File cut = new File(work, "cut.mp4");
                        long cutEnd = endUs > 0 ? endUs : Long.MAX_VALUE;
                        Splitter.extractRange(raw, cut, startUs, cutEnd);
                        if (cut.length() > 10000) {
                            encodeSrc = cut;
                            encStart = 0;
                            encEnd = 0;
                            encDurMs = Math.max(1, (long) (seconds * 1000));
                            log.line("   先裁掉不要的部分，少压 "
                                    + (int) Math.max(0, (durMs - encDurMs) / 1000) + " 秒…");
                        }
                    } catch (Exception e) {
                        android.util.Log.w("BiliSlicer", "预裁剪失败，按原片处理", e);
                        log.line("   ⚠ 预裁剪失败，按原片处理");
                    }
                }

                // 这台机器上整片转码的产物一旦到几十兆，MediaMuxer 收尾就会失败
                // （连 ftyp 都没写，读不到时长也不能播）。所以需要压缩时直接走
                // 「按目标体积切段 → 逐段压」，每段十来兆，稳定且码率更准。
                boolean encodedInParts = false;
                java.util.List<File> partOuts = new java.util.ArrayList<>();
                try {
                    long segUs = (long) (Math.max(8.0, partSec) * 1_000_000L);
                    partOuts = encodeInParts(encodeSrc, outDir, base, segUs, (long) partBytes,
                            videoKbps, needCompress4Total ? targetWidth : 854,
                            preTrimStart, preTrimEnd, work, log, prog);
                    encodedInParts = !partOuts.isEmpty();
                    if (encodedInParts) {
                        long sum = 0;
                        for (File f : partOuts) sum += f.length();
                        // 压完比原片还大就没意义了：扔掉，改用原画质切分
                        if (sum >= rawSize) {
                            Diag.line("放弃压缩", "压缩后 " + Library.mb(sum)
                                    + " ≥ 原片 " + Library.mb(rawSize) + "，改用原画质切分");
                            log.line("   ⚠ 压缩没效果（比原片还大），改用原画质切分");
                            for (File f : partOuts) {
                                //noinspection ResultOfMethodCallIgnored
                                f.delete();
                            }
                            partOuts.clear();
                            encodedInParts = false;
                        }
                    }
                    if (encodedInParts) {
                        Diag.line("分段转码", partOuts.size() + " 段，首段 "
                                + Library.mb(partOuts.get(0).length()));
                        log.line("   分 " + partOuts.size() + " 段分别压缩（整片一次压会写坏）");
                    }
                } catch (Pipeline.Canceled c) {
                    throw c;
                } catch (Exception e) {
                    Diag.line("分段转码失败", e.toString());
                    log.line("   ⚠ 分段压缩失败：" + e.getMessage());
                }

                File enc = new File(work, "enc.mp4");
                boolean encoded = true;
                final long encT0 = System.currentTimeMillis();
                Diag.line("开始转码", "目标 " + videoKbps + "k 宽" + (needCompress4Total ? targetWidth : 854));
                if (encodedInParts) {
                    outs.addAll(partOuts);
                } else try {
                    Transcoder.transcode(encodeSrc, enc, videoKbps * 1000,
                            needCompress4Total ? targetWidth : 854, 1,
                            encDurMs, pct -> {
                                checkCancel();
                                if (prog != null) prog.pct(55 + pct * 35 / 100);
                            });
                    // 转码「成功」不等于产物可用：muxer 收尾失败时会留下一个没 moov 的
                    // 大文件，时长读不出来。这里立刻验证，别等到切分时才报错。
                    if (encoded && durationUsOf(enc) <= 0) {
                        Diag.line("转码产物异常", "enc.mp4 " + Library.mb(enc.length())
                                + " 但读不到时长（muxer 收尾失败？）");
                        //noinspection ResultOfMethodCallIgnored
                        enc.delete();
                        encoded = false;
                        log.line("   ⚠ 转码没写完整，改用原片切分");
                    }
                } catch (Pipeline.Canceled c) {
                    throw c;
                } catch (Exception e) {
                    encoded = false;
                    android.util.Log.w("BiliSlicer", "transcode fail", e);
                    String srcMime = videoMimeOf(raw);
                    if (srcMime != null && !srcMime.equals("video/avc")) {
                        // 源是 HEVC/AV1 之类：直接切出来的段微信和相册很可能播不了，
                        // 与其产出坏文件，不如明确报错
                        throw new Exception("转码失败（" + e.getMessage() + "），且源视频是 "
                                + srcMime + "，直接切分可能无法在微信里播放");
                    }
                    Diag.line("转码失败", e.toString());
                    log.line("   ⚠ 转码失败：" + e.getMessage() + "，改为直接切原片");
                }
                // 如果预裁剪成功，enc 里已经不含片头片尾了，切分时别再裁一次
                final boolean preTrimmed = !encodeSrc.equals(raw);
                if (encoded && hasAudio(raw) && !hasAudio(enc)) {
                    log.line("   ⚠ 源音轨 MP4 装不下（如 opus），这一版成品没有声音");
                }
                File toSplit = encoded ? enc : encodeSrc;
                long splitStart = preTrimmed ? 0 : startUs;
                long splitEnd = preTrimmed ? 0 : endUs;
                if (encodedInParts) {
                    // 分段压缩时已经切好了，跳过按字节切分
                    res.parts = outs.size();
                    for (File f : outs) res.bytes += f.length();
                    log.line("✓ 完成，共 " + outs.size() + " 个文件");
                    return res;
                }
                log.line("④ 按字节切分…");
                Splitter.Result r = Splitter.split(toSplit, outDir, base, partBytes, partUs,
                        splitStart, splitEnd,
                        (part, total) -> {
                            checkCancel();
                            if (prog != null) prog.pct(90 + part * 10 / Math.max(1, total));
                        });
                Diag.line("切分", "段数 " + r.parts + "（计划 " + r.planned + "），输入 "
                        + Library.mb(toSplit.length()));
                if (r.parts != r.planned) {
                    log.line("   关键帧不够密：本来切 " + r.planned + " 段，改成 " + r.parts
                            + " 段（否则会切出几秒的碎片）");
                }
                outs.addAll(r.files);
            }

            log.line("⑤ 存入片库…");
            int cleared = Store.clearExisting(ctx, base);
            if (cleared > 0) log.line("   已清掉同视频的旧片段 " + cleared + " 个");
            for (File f : outs) {
                Uri u = Store.saveToDownloads(ctx, f, base, f.getName());
                res.uris.add(u);
            }
            Diag.line("落库", outs.size() + " 个文件，清旧 " + cleared + " 个");
            res.parts = outs.size();
            for (File f : outs) res.bytes += f.length();
            log.line("✓ 完成，共 " + outs.size() + " 个文件");
        return res;
    }
}
