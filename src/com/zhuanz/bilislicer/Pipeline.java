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
            Source.Media media = Source.resolve(ctx, url, hq);
            // 多分P 的标题带上 P 号，否则记录里几条一模一样分不清
            res.title = media.pageCount > 1
                    ? (media.title + " · P" + media.page) : media.title;
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
            long rawSize = raw.length();
            long durMs = durationOf(raw);
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

                File enc = new File(work, "enc.mp4");
                boolean encoded = true;
                try {
                    Transcoder.transcode(encodeSrc, enc, videoKbps * 1000,
                            needCompress4Total ? targetWidth : 854, 1,
                            encDurMs, pct -> {
                                checkCancel();
                                if (prog != null) prog.pct(55 + pct * 35 / 100);
                            });
                } catch (Pipeline.Canceled c) {
                    throw c;
                } catch (Exception e) {
                    encoded = false;
                    android.util.Log.w("BiliSlicer", "transcode fail", e);
                    log.line("   ⚠ 转码失败：" + e.getMessage() + "，改为直接切原片");
                }
                // 如果预裁剪成功，enc 里已经不含片头片尾了，切分时别再裁一次
                final boolean preTrimmed = !encodeSrc.equals(raw);
                File toSplit = encoded ? enc : encodeSrc;
                long splitStart = preTrimmed ? 0 : startUs;
                long splitEnd = preTrimmed ? 0 : endUs;
                log.line("④ 按字节切分…");
                Splitter.Result r = Splitter.split(toSplit, outDir, base, partBytes, partUs,
                        splitStart, splitEnd,
                        (part, total) -> {
                            checkCancel();
                            if (prog != null) prog.pct(90 + part * 10 / Math.max(1, total));
                        });
                outs.addAll(r.files);
            }

            log.line("⑤ 存入片库…");
            int cleared = Store.clearExisting(ctx, base);
            if (cleared > 0) log.line("   已清掉同视频的旧片段 " + cleared + " 个");
            for (File f : outs) {
                Uri u = Store.saveToDownloads(ctx, f, base, f.getName());
                res.uris.add(u);
            }
            res.parts = outs.size();
            for (File f : outs) res.bytes += f.length();
            log.line("✓ 完成，共 " + outs.size() + " 个文件");
        return res;
    }
}
