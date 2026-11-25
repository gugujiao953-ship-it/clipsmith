package com.zhuanz.bilislicer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站接口：解析链接 → 拿 cid → 拿最低画质直链。
 * 走 platform=html5 通道，免登录、直接返回单个 mp4（不需要 ffmpeg 合流）。
 */
public class BiliApi {

    static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static class Page {
        public int page;
        public long cid;
        public String title = "";
        public long seconds;
    }

    public static class Episode {
        public String bvid;
        public String title = "";
        public long seconds;
    }

    public static class Info {
        public java.util.List<Page> pages = new java.util.ArrayList<>();
        public java.util.List<Episode> season = new java.util.ArrayList<>();
        public String seasonTitle = "";
        public String bvid;
        public long aid;
        public int page = 1;          // 当前要处理第几 P
        public int pageCount = 1;     // 总共有几 P
        public boolean explicitPage;  // 链接里是否显式带了 ?p=
        public String title = "";
        public long cid;
        public long durationMs;
    }

    public static class Stream {
        public String url;
        public long size;
        public int quality;
        public String qualityDesc;
    }

    /** 把 B站的错误码翻译成人能看懂的话 */
    static String friendly(int code, String msg) {
        switch (code) {
            case -400:
            case -404:
                return "视频不存在或已被删除（检查一下链接）";
            case -403:
                return "没有权限访问这个视频";
            case 62002:
            case 62004:
            case -10403:
                return "稿件不可见（已删除 / 仅粉丝可见 / 地区限制）";
            case -352:
                return "触发了风控，稍后再试或换个网络";
            default:
                return "B站返回错误：" + (msg == null || msg.isEmpty() ? "code " + code
                        : msg + "（code " + code + "）");
        }
    }

    /** 跟随短链，返回最终 URL */
    static String follow(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.bilibili.com");
        try {
            c.getResponseCode();
            return c.getURL().toString();
        } finally {
            c.disconnect();
        }
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.bilibili.com");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bo.toString("UTF-8");
    }

    /** 解析用户粘贴的链接 */
    public static Info resolve(String raw) throws Exception {
        String url = raw.trim();
        if (!url.startsWith("http")) {
            Matcher bv = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(url);
            if (bv.find()) url = "https://www.bilibili.com/video/" + bv.group(1);
        }
        // 短链（b23.tv / bili2233.cn）需要跟随跳转
        if (url.contains("b23.tv") || url.contains("bili2233")) {
            url = follow(url);
        }

        Info info = new Info();
        Matcher m = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(url);
        if (m.find()) {
            info.bvid = m.group(1);
        } else {
            Matcher a = Pattern.compile("/av(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
            if (!a.find()) throw new IOException("链接里找不到 BV 号 / av 号");
            info.aid = Long.parseLong(a.group(1));
        }
        Matcher p = Pattern.compile("[?&]p=(\\d+)").matcher(url);
        if (p.find()) {
            int pn = Math.max(1, Integer.parseInt(p.group(1)));
            info.page = pn;
            // 网页复制出来的链接几乎都带 ?p=1，这只是「第一集」的默认值，
            // 不能当成用户指定，否则多P 视频会被直接下第一集而跳过选集
            info.explicitPage = pn >= 2;
        }

        String api = info.bvid != null
                ? "https://api.bilibili.com/x/web-interface/view?bvid=" + info.bvid
                : "https://api.bilibili.com/x/web-interface/view?aid=" + info.aid;
        JSONObject root = new JSONObject(get(api));
        if (root.optInt("code") != 0) {
            throw new IOException(friendly(root.optInt("code"), root.optString("message")));
        }
        JSONObject d = root.getJSONObject("data");
        info.title = d.optString("title", "video");
        if (info.bvid == null) info.bvid = d.optString("bvid", "");
        if (info.durationMs == 0) info.durationMs = d.optLong("duration", 0) * 1000L;

        JSONArray pages = d.optJSONArray("pages");
        if (pages != null && pages.length() > 0) {
            info.pageCount = pages.length();
            for (int i = 0; i < pages.length(); i++) {
                JSONObject pg0 = pages.optJSONObject(i);
                if (pg0 == null) continue;
                Page pp = new Page();
                pp.page = i + 1;
                pp.cid = pg0.optLong("cid");
                pp.seconds = pg0.optLong("duration", 0);
                pp.title = pg0.optString("part", "");
                info.pages.add(pp);
            }
            int idx = Math.min(info.page, pages.length()) - 1;
            JSONObject pg = pages.getJSONObject(idx);
            info.cid = pg.optLong("cid");
            if (info.durationMs == 0) info.durationMs = pg.optLong("duration", 0) * 1000L;
        }
        // 合集 / 系列：up 主把多个视频归到一个 ugc_season 里
        JSONObject season = d.optJSONObject("ugc_season");
        if (season != null) {
            info.seasonTitle = season.optString("title", "");
            JSONArray sections = season.optJSONArray("sections");
            if (sections != null) {
                for (int i = 0; i < sections.length(); i++) {
                    JSONObject sec = sections.optJSONObject(i);
                    if (sec == null) continue;
                    JSONArray eps = sec.optJSONArray("episodes");
                    if (eps == null) continue;
                    for (int j = 0; j < eps.length(); j++) {
                        JSONObject ep = eps.optJSONObject(j);
                        if (ep == null) continue;
                        Episode e = new Episode();
                        e.bvid = ep.optString("bvid", "");
                        e.title = ep.optString("title", "");
                        JSONObject arc = ep.optJSONObject("arc");
                        if (arc != null) {
                            e.seconds = arc.optLong("duration", 0);
                            if (e.title.isEmpty()) e.title = arc.optString("title", "");
                        }
                        if (!e.bvid.isEmpty()) info.season.add(e);
                    }
                }
            }
        }
        if (info.cid == 0) info.cid = d.optLong("cid");
        if (info.cid == 0) throw new IOException("拿不到 cid");
        return info;
    }

    /** 取最低画质直链（qn=16 即 360P，html5 通道下限） */
    public static class Dash {
        public String videoUrl;
        public String audioUrl;
        public int width;
        public int height;
        public String label = "";
    }

    /**
     * DASH 取流：免登录最高能拿到 480P（852x480），比 html5 通道的 360P 清楚一档。
     * 视频和音频是分开的两条流，要下载后 remux 合流。
     */
    public static Dash playurlDash(Info info) throws Exception {
        String api = "https://api.bilibili.com/x/player/playurl?bvid=" + info.bvid
                + "&cid=" + info.cid + "&qn=64&fnval=16&fnver=0&fourk=0";
        JSONObject root = new JSONObject(get(api));
        if (root.optInt("code") != 0) {
            throw new IOException(friendly(root.optInt("code"), root.optString("message")));
        }
        JSONObject d = root.optJSONObject("data");
        JSONObject dash = d == null ? null : d.optJSONObject("dash");
        if (dash == null) throw new IOException("没有 DASH 片源");
        JSONArray videos = dash.optJSONArray("video");
        if (videos == null || videos.length() == 0) throw new IOException("DASH 里没有视频流");

        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            int w = v.optInt("width"), h = v.optInt("height");
            String codecs = v.optString("codecs", "");
            // 优先 AVC（兼容性最好），其次按面积
            int score = w * h + ("avc1".equals(codecs) || codecs.startsWith("avc") ? 10000000 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best == null) throw new IOException("挑不出可用的视频流");

        Dash out = new Dash();
        out.videoUrl = best.optString("baseUrl", best.optString("base_url", ""));
        out.width = best.optInt("width");
        out.height = best.optInt("height");
        out.label = out.height + "P";
        if (out.videoUrl.isEmpty()) throw new IOException("视频流地址为空");

        JSONArray audios = dash.optJSONArray("audio");
        if (audios != null && audios.length() > 0) {
            JSONObject ba = null;
            int bw = -1;
            for (int i = 0; i < audios.length(); i++) {
                JSONObject a = audios.getJSONObject(i);
                int b = a.optInt("bandwidth", 0);
                if (b > bw) {
                    bw = b;
                    ba = a;
                }
            }
            if (ba != null) {
                out.audioUrl = ba.optString("baseUrl", ba.optString("base_url", ""));
            }
        }
        return out;
    }

    public static Stream playurl(Info info) throws Exception {
        String api = "https://api.bilibili.com/x/player/playurl?bvid=" + info.bvid
                + "&cid=" + info.cid + "&qn=16&platform=html5&high_quality=1";
        JSONObject root = new JSONObject(get(api));
        if (root.optInt("code") != 0) {
            throw new IOException("取流失败：" + friendly(root.optInt("code"),
                    root.optString("message")));
        }
        JSONObject d = root.getJSONObject("data");
        JSONArray durl = d.optJSONArray("durl");
        if (durl == null || durl.length() == 0) {
            throw new IOException("这个视频没有可用的免登录片源（可能需要大会员或已下架）");
        }
        JSONObject u = durl.getJSONObject(0);
        Stream s = new Stream();
        s.url = u.getString("url");
        s.size = u.optLong("size", 0);
        s.quality = d.optInt("quality", 0);
        s.qualityDesc = d.optString("format", "");
        return s;
    }

    // ═══════════════ UP 主搜索 / 投稿列表（需 wbi 签名） ═══════════════

    public static class Up {
        public String mid;
        public String name;
        public long fans;
        public String sign;
    }

    public static class UpVideo {
        public String bvid;
        public String title;
        public long seconds;
    }

    /** 按名字搜 UP 主 */
    public static java.util.List<Up> searchUp(String keyword) throws Exception {
        Wbi.warmUp();
        java.util.Map<String, String> p = new java.util.HashMap<>();
        p.put("search_type", "bili_user");
        p.put("keyword", keyword);
        p.put("page", "1");
        JSONObject root = Wbi.signedGet(
                "https://api.bilibili.com/x/web-interface/wbi/search/type", p);
        android.util.Log.i("BiliSlicer", "searchUp raw=" + root.toString().substring(0,
                Math.min(400, root.toString().length())));
        java.util.List<Up> out = new java.util.ArrayList<>();
        JSONArray arr = root.optJSONObject("data") == null ? null
                : root.getJSONObject("data").optJSONArray("result");
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && i < 10; i++) {
            JSONObject o = arr.getJSONObject(i);
            Up u = new Up();
            u.mid = String.valueOf(o.optLong("mid"));
            u.name = o.optString("uname", "").replaceAll("<[^>]+>", "");
            u.fans = o.optLong("fans");
            u.sign = o.optString("usign", "");
            if (u.mid != null && !"0".equals(u.mid)) out.add(u);
        }
        return out;
    }

    /**
     * 拉某个 UP 主的投稿（按发布时间倒序）。
     * 注意：这个接口不能带 web_location / dm_img_* 参数，带了反而 -352 风控。
     */
    public static java.util.List<UpVideo> upVideos(String mid, int page, int ps) throws Exception {
        Wbi.warmUp();
        java.util.Map<String, String> p = new java.util.HashMap<>();
        p.put("mid", mid);
        p.put("ps", String.valueOf(ps));
        p.put("pn", String.valueOf(page));
        p.put("order", "pubdate");
        JSONObject root = Wbi.signedGet(
                "https://api.bilibili.com/x/space/wbi/arc/search", p);
        android.util.Log.i("BiliSlicer", "upVideos raw=" + root.toString().substring(0,
                Math.min(300, root.toString().length())));
        java.util.List<UpVideo> out = new java.util.ArrayList<>();
        JSONObject data = root.optJSONObject("data");
        if (data == null) return out;
        JSONObject list = data.optJSONObject("list");
        JSONArray vlist = list == null ? null : list.optJSONArray("vlist");
        if (vlist == null) return out;
        for (int i = 0; i < vlist.length(); i++) {
            JSONObject o = vlist.getJSONObject(i);
            UpVideo v = new UpVideo();
            v.bvid = o.optString("bvid", "");
            v.title = o.optString("title", "");
            v.seconds = parseLength(o.optString("length", "0"));
            if (!v.bvid.isEmpty()) out.add(v);
        }
        return out;
    }

    /** "12:34" / "1:02:03" → 秒 */
    static long parseLength(String s) {
        if (s == null || s.isEmpty()) return 0;
        long sec = 0;
        for (String part : s.split(":")) {
            try {
                sec = sec * 60 + Long.parseLong(part.trim());
            } catch (Exception ignored) {
            }
        }
        return sec;
    }

    /**
     * 拉 UP 主投稿，带兜底：
     *   1) 先试空间接口（按发布时间倒序，最准）
     *   2) 空间接口被风控就退回搜索接口（搜该 UP 名字 → 按 mid 过滤）
     * 手机移动网络下空间接口很容易被风控，没有兜底这个功能基本用不了。
     */
    public static java.util.List<UpVideo> upVideosSmart(Up up) throws Exception {
        try {
            java.util.List<UpVideo> list = upVideos(up.mid, 1, 50);
            if (!list.isEmpty()) return list;
        } catch (Exception e) {
            android.util.Log.w("BiliSlicer", "space api fail, fallback to search: " + e);
        }
        return upVideosBySearch(up.name, up.mid);
    }

    /** 搜索兜底：按 UP 名字搜视频，再用 mid 过滤 */
    static java.util.List<UpVideo> upVideosBySearch(String upName, String mid) throws Exception {
        Wbi.warmUp();
        java.util.Map<String, String> p = new java.util.HashMap<>();
        p.put("search_type", "video");
        p.put("keyword", upName);
        p.put("page", "1");
        JSONObject root = Wbi.signedGet(
                "https://api.bilibili.com/x/web-interface/wbi/search/type", p);
        java.util.List<UpVideo> out = new java.util.ArrayList<>();
        JSONArray arr = root.optJSONObject("data") == null ? null
                : root.getJSONObject("data").optJSONArray("result");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (!mid.equals(String.valueOf(o.optLong("mid")))) continue;
            UpVideo v = new UpVideo();
            v.bvid = o.optString("bvid", "");
            v.title = o.optString("title", "").replaceAll("<[^>]+>", "");
            v.seconds = parseLength(o.optString("duration", "0"));
            if (!v.bvid.isEmpty()) out.add(v);
        }
        return out;
    }
}
