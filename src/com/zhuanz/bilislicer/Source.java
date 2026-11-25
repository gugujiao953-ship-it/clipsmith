package com.zhuanz.bilislicer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多平台解析：B站 / 抖音 / 快手。
 *
 * 抖音：分享页 window._ROUTER_DATA → item_list[0].video.play_addr（playwm→play 去水印）
 *       注意分享页第一次请求只下发 ttwid Cookie，第二次才带数据，所以要请求两遍。
 * 快手：分享短链 302 拿到 photoId → 作品页 __APOLLO_STATE__ → 拿不到就退到 GraphQL visionVideoDetail。
 */
public class Source {

    public static final String UA_MOBILE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    public static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static class Media {
        public String id = "";          // 去重用的唯一标识（bvid_p2 / 作品 id）
        public int page = 1;            // 第几 P
        public int pageCount = 1;       // 共几 P
        public boolean explicitPage;    // 链接里是否显式指定了 ?p=
        public java.util.List<BiliApi.Page> pages = new java.util.ArrayList<>();
        public String bvid = "";
        public String platform = "";
        public String title = "video";
        public String playUrl;
        public String audioUrl;      // DASH 时的音频流（null 表示单文件流）
        public java.util.List<String> altUrls;   // 同一视频的多个候选地址（某些域名会解析失败）
        public long expectedSize;                // 服务端声明的大小（用来判断是否下全了）
        public java.util.List<BiliApi.Episode> season = new java.util.ArrayList<>();
        public String seasonTitle = "";
        public String quality = "";  // 例如 "480P"
        public String referer = "";
        public String ua = UA_PC;
        public boolean withWatermark = false;
    }

    // ── 平台识别 ──────────────────────────────────────────────

    public static boolean supports(String url) {
        if (url == null) return false;
        return url.contains("bilibili.com") || url.contains("b23.tv") || url.contains("bili2233")
                || url.contains("douyin.com") || url.contains("iesdouyin.com")
                || url.contains("kuaishou.com") || url.contains("chenzhongtech.com")
                || url.contains("kwai.com");
    }

    public static String platformOf(String url) {
        if (url == null) return "";
        if (url.contains("douyin")) return "douyin";
        if (url.contains("kuaishou") || url.contains("chenzhongtech") || url.contains("kwai")) return "kuaishou";
        if (url.contains("bilibili") || url.contains("b23.tv") || url.contains("bili2233")) return "bilibili";
        return "";
    }

    // ── HTTP 小工具（自己管 Cookie）────────────────────────────

    static class Resp {
        int code;
        String body = "";
        Map<String, String> headers = new HashMap<>();
    }

    private static final Map<String, String> COOKIES = new LinkedHashMap<>();

    private static void absorbCookies(HttpURLConnection c) {
        Map<String, java.util.List<String>> h = c.getHeaderFields();
        if (h == null) return;
        for (Map.Entry<String, java.util.List<String>> e : h.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String v : e.getValue()) {
                if (v == null) continue;
                int eq = v.indexOf('=');
                if (eq <= 0) continue;
                int semi = v.indexOf(';');
                String name = v.substring(0, eq).trim();
                String val = v.substring(eq + 1, semi > 0 ? semi : v.length()).trim();
                if (!name.isEmpty()) COOKIES.put(name, val);
            }
        }
    }

    private static String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : COOKIES.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static Resp get(String url, String ua, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", ua);
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        String ck = cookieHeader();
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        Resp r = new Resp();
        r.code = c.getResponseCode();
        absorbCookies(c);
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            r.body = bo.toString("UTF-8");
        }
        r.headers.put("finalUrl", c.getURL().toString());
        c.disconnect();
        return r;
    }

    static Resp postJson(String url, String body, String ua, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (referer != null) c.setRequestProperty("Referer", referer);
        c.setRequestProperty("Origin", "https://www.kuaishou.com");
        String ck = cookieHeader();
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        absorbCookies(c);
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            r.body = bo.toString("UTF-8");
        }
        c.disconnect();
        return r;
    }

    /** 手动跟跳转，用于拿最终地址/取 id */
    static String followTo(String url, String ua) {
        String cur = url;
        for (int i = 0; i < 6; i++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", ua);
                String ck = cookieHeader();
                if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                int code = c.getResponseCode();
                absorbCookies(c);
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (code >= 300 && code < 400 && loc != null) {
                    cur = loc.startsWith("http") ? loc : new URL(new URL(cur), loc).toString();
                } else {
                    return cur;
                }
            } catch (Exception e) {
                return cur;
            }
        }
        return cur;
    }

    /** 从 HTML 里抠出 `var X = {...}` 的 JSON 对象 */
    static JSONObject jsonAfter(String html, String marker) {
        int k = html.indexOf(marker);
        if (k < 0) return null;
        int eq = html.indexOf('=', k);
        if (eq < 0) return null;
        String s2 = html.substring(eq + 1);
        int end = s2.indexOf("</script>");
        if (end > 0) s2 = s2.substring(0, end);
        s2 = s2.trim();
        if (s2.endsWith(";")) s2 = s2.substring(0, s2.length() - 1);
        // 截到括号平衡处
        int depth = 0, cut = -1;
        for (int i = 0; i < s2.length(); i++) {
            char c = s2.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { cut = i + 1; break; }
            }
        }
        if (cut < 0) return null;
        try {
            return new JSONObject(s2.substring(0, cut));
        } catch (Exception e) {
            return null;
        }
    }

    /** 在任意 JSON 结构里递归找目标字段（值可能是内嵌的 JSON 字符串） */
    static void scanFor(Object node, String[] keys, Map<String, String> out, int depth) {
        if (node == null || depth > 6) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                for (String want : keys) {
                    if (want.equals(k) && !out.containsKey(want)) {
                        String sv = String.valueOf(v);
                        if (!"null".equals(sv) && !sv.isEmpty()) out.put(want, sv);
                    }
                }
                if (v instanceof String && ((String) v).startsWith("{")) {
                    try {
                        scanFor(new JSONObject((String) v), keys, out, depth + 1);
                    } catch (Exception ignored) {
                    }
                } else {
                    scanFor(v, keys, out, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) scanFor(a.opt(i), keys, out, depth + 1);
        }
    }

    // ── 总入口 ────────────────────────────────────────────────

    public static Media resolve(Context ctx, String rawUrl) throws Exception {
        return resolve(ctx, rawUrl, false);
    }

    public static Media resolve(Context ctx, String rawUrl, boolean hq) throws Exception {
        String url = rawUrl.trim();
        String p = platformOf(url);
        if ("bilibili".equals(p)) {
            BiliApi.Info info = BiliApi.resolve(url);
            Media m = new Media();
            if (hq) {
                try {
                    BiliApi.Dash dash = BiliApi.playurlDash(info);
                    m.playUrl = dash.videoUrl;
                    m.audioUrl = dash.audioUrl;
                    m.quality = dash.label;
                    // DASH 的 CDN 只认桌面 UA，移动 UA 一律 403
                    m.ua = UA_PC;
                } catch (Exception e) {
                    android.util.Log.w("BiliSlicer", "dash 取流失败，回落 360P", e);
                }
            }
            if (m.playUrl == null) {
                BiliApi.Stream low = BiliApi.playurl(info);
                m.playUrl = low.url;
                m.quality = "360P";
                m.ua = BiliApi.UA;
            }
            m.platform = "bilibili";
            m.page = info.page;
            m.pageCount = Math.max(1, info.pageCount);
            m.pages = info.pages;
            m.bvid = info.bvid;
            m.season = info.season;
            m.seasonTitle = info.seasonTitle;
            m.explicitPage = info.explicitPage;
            // 多 P 视频每个分 P 单独算一条，否则 P2 会被当成 P1 的重复
            m.id = m.pageCount > 1 ? (info.bvid + "_p" + m.page) : info.bvid;
            m.title = info.title;
            // 注意结尾斜杠：B站 DASH 的 CDN 只认带斜杠的 Referer，少了直接 403
            m.referer = "https://www.bilibili.com/";
            return m;
        }
        if ("douyin".equals(p)) return resolveDouyin(ctx, url);
        if ("kuaishou".equals(p)) return resolveKuaishou(url);
        throw new IOException("不支持的链接（目前支持 B站 / 抖音 / 快手）");
    }

    // ── 抖音 ──────────────────────────────────────────────────

    private static final Pattern DY_ID = Pattern.compile(
            "(?:/video/|/note/|/share/video/|/share/note/|modal_id=|item_ids=)(\\d{6,})");

    private static String firstMatch(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String douyinId(String url) {
        String id = firstMatch(DY_ID, url);
        if (id != null) return id;
        // v.douyin.com 短链要跟随跳转
        String followed = followTo(url, UA_MOBILE);
        if (followed != null && !followed.equals(url)) {
            id = firstMatch(DY_ID, followed);
            if (id != null) return id;
        }
        // 有些短链不走 302，而是返回一个 JS 跳转页：直接从页面里抠作品 ID
        return idFromHtml(url);
    }

    private static String idFromHtml(String url) {
        try {
            Resp r = get(url, UA_MOBILE, "https://www.douyin.com/");
            if (r == null || r.body == null) return null;
            String body = r.body;
            Matcher m = Pattern.compile(
                    "(?:/video/|/note/|aweme_id[\"']?\\s*[:=]\\s*[\"']?)(\\d{10,})")
                    .matcher(body);
            if (m.find()) return m.group(1);
            Matcher m2 = Pattern.compile("\"itemId\"\\s*:\\s*\"(\\d{6,})\"").matcher(body);
            if (m2.find()) return m2.group(1);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean dyWarmed;

    /** 抖音不带 cookie 直接请求分享页，很容易被丢到验证页；先摸一次首页 */
    private static void douyinWarmUp() {
        if (dyWarmed) return;
        dyWarmed = true;
        try {
            get("https://www.douyin.com/", UA_MOBILE, "https://www.douyin.com/");
        } catch (Exception ignored) {
        }
    }

    private static Media resolveDouyin(Context ctx, String url) throws Exception {
        douyinWarmUp();
        if (url.contains("live.douyin.com")) {
            throw new IOException("这是抖音直播间，不是视频作品");
        }
        String id = douyinId(url);
        if (id == null) {
            throw new IOException("没能从链接里认出抖音作品 ID"
                    + "（请在抖音里点「分享 → 复制链接」，别复制页面地址）");
        }

        java.util.LinkedHashSet<String> why = new java.util.LinkedHashSet<>();
        JSONObject item = fromSharePage(id, why);
        if (item == null) item = fromItemApi(id);
        if (item == null) {
            String detail = why.isEmpty() ? "分享页没返回数据"
                    : android.text.TextUtils.join("、", why);
            throw new IOException("抖音拿不到作品（" + detail + "）。"
                    + "常见原因：作品已删除/私密、需要登录验证，或这是图文作品");
        }

        JSONObject video = item.optJSONObject("video");
        JSONObject playAddr = video == null ? null : video.optJSONObject("play_addr");

        // 抖音通常提供多档码率。按「每段上限」挑一档，避免动不动就下原画：
        // 一段里塞超过上限的码率，最后只能切得稀碎。
        if (video != null) {
            JSONArray brs = video.optJSONArray("bit_rate");
            if (brs != null && brs.length() > 0) {
                long durMs = video.optLong("duration", 0);
                double durSec = durMs > 0 ? durMs / 1000.0 : 60;
                double limitMb = ctx.getSharedPreferences("bili", Context.MODE_PRIVATE)
                        .getFloat("limit", 9.6f);
                double targetKbps = limitMb * 8 * 1024 / Math.max(1, durSec) * 0.9;
                JSONObject pickedBr = null;
                long bestRate = -1;
                long lowestRate = Long.MAX_VALUE;
                JSONObject lowest = null;
                for (int i = 0; i < brs.length(); i++) {
                    JSONObject br = brs.optJSONObject(i);
                    if (br == null) continue;
                    long rate = br.optLong("bit_rate", 0);
                    JSONObject pa = br.optJSONObject("play_addr");
                    if (pa == null || pa.optJSONArray("url_list") == null) continue;
                    if (rate < lowestRate) {
                        lowestRate = rate;
                        lowest = br;
                    }
                    if (rate <= targetKbps * 1000 && rate > bestRate) {
                        bestRate = rate;
                        pickedBr = br;
                    }
                }
                if (pickedBr == null) pickedBr = lowest;
                if (pickedBr != null) {
                    JSONObject pa = pickedBr.optJSONObject("play_addr");
                    if (pa != null) {
                        playAddr = pa;
                        android.util.Log.i("BiliSlicer", "douyin 选档 "
                                + pickedBr.optString("gear_name") + " "
                                + pickedBr.optLong("bit_rate") / 1000 + "kbps (上限约 "
                                + Math.round(targetKbps) + "kbps)");
                    }
                }
            }
        }
        JSONArray images = item.optJSONArray("images");
        if (playAddr == null) {
            if (images != null && images.length() > 0) {
                throw new IOException("这是图文作品（" + images.length()
                        + " 张图），本工具只处理视频");
            }
            throw new IOException("这个作品里没有视频流（可能是直播回放或纯音频）");
        }
        JSONArray urls = playAddr.optJSONArray("url_list");
        if (urls == null || urls.length() == 0) throw new IOException("抖音播放地址为空");

        Media m = new Media();
        m.platform = "douyin";
        m.id = "dy_" + id;
        m.title = item.optString("desc", "douyin_" + id).trim();
        if (m.title.isEmpty()) m.title = "douyin_" + id;
        m.referer = "https://www.douyin.com/";
        m.ua = UA_MOBILE;

        // 无水印优先，但要逐个探测：抖音经常给出几个只有个别能用
        java.util.List<String> tries = new java.util.ArrayList<>();
        for (int i = 0; i < urls.length(); i++) {
            String u = urls.optString(i, "");
            if (!u.isEmpty()) tries.add(u.replace("playwm", "play"));
        }
        for (int i = 0; i < urls.length(); i++) {
            String u = urls.optString(i, "");
            if (!u.isEmpty() && !tries.contains(u)) tries.add(u);
        }
        String picked = null;
        boolean wm = false;
        for (String cand : tries) {
            if (probe(cand, m.ua, m.referer)) {
                picked = cand;
                wm = cand.contains("playwm");
                break;
            }
        }
        if (picked == null) {
            // 全都探测失败，交给下载器去试第一个（有些机型 probe 会误判）
            picked = tries.get(0);
        }
        m.playUrl = picked;
        m.expectedSize = playAddr.optLong("data_size", 0);
        // 把可用的候选地址都带上：抖音的 CDN 域名经常有的能解析有的不能
        java.util.List<String> ordered = new java.util.ArrayList<>();
        ordered.add(picked);
        for (String cand : tries) if (!ordered.contains(cand)) ordered.add(cand);
        m.altUrls = ordered;
        m.withWatermark = wm;
        m.quality = "原画";
        return m;
    }

    /** 分享页解析：/share/video/ 和 /share/note/ 都试，页面结构里找 videoInfoRes / noteInfoRes */
    private static JSONObject fromSharePage(String id,
                                            java.util.LinkedHashSet<String> why) {
        String[] pages = {
                "https://www.iesdouyin.com/share/video/" + id + "/",
                "https://www.iesdouyin.com/share/note/" + id + "/",
                "https://www.douyin.com/video/" + id,
                "https://m.douyin.com/share/video/" + id
        };
        java.util.LinkedHashSet<String> reasons = new java.util.LinkedHashSet<>();
        for (String page : pages) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    Resp r = get(page, UA_MOBILE, "https://www.douyin.com/");
                    if (r == null || r.body == null) {
                        reasons.add("空响应");
                        continue;
                    }
                    boolean verify = r.body.contains("verify") || r.body.contains("captcha");
                    if (verify) {
                        reasons.add("抖音要验证");
                        // 有时候拿到 ttwid 后再试一次就过了
                        if (attempt == 0) {
                            // 重新摸一次首页刷新 cookie，再等一会儿重试
                            dyWarmed = false;
                            douyinWarmUp();
                            try {
                                Thread.sleep(1200);
                            } catch (InterruptedException ignored) {
                            }
                            continue;
                        }
                    }
                    JSONObject item = pickItem(r.body);
                    if (item != null) return item;
                    if (!r.body.contains("_ROUTER_DATA")) reasons.add("页面结构变了");
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static JSONObject pickItem(String body) {
        int k = body.indexOf("window._ROUTER_DATA");
        if (k < 0) return null;
        int eq = body.indexOf('=', k);
        int end = body.indexOf("</script>", eq);
        if (eq < 0 || end < 0) return null;
        String js = body.substring(eq + 1, end).trim();
        if (js.endsWith(";")) js = js.substring(0, js.length() - 1);
        try {
            JSONObject root = new JSONObject(js);
            JSONObject ld = root.optJSONObject("loaderData");
            if (ld == null) return null;
            java.util.Iterator<String> keys = ld.keys();
            while (keys.hasNext()) {
                Object o = ld.opt(keys.next());
                if (!(o instanceof JSONObject)) continue;
                JSONObject pageObj = (JSONObject) o;
                JSONObject res = pageObj.optJSONObject("videoInfoRes");
                if (res == null) res = pageObj.optJSONObject("noteInfoRes");
                if (res == null) continue;
                JSONArray list = res.optJSONArray("item_list");
                if (list != null && list.length() > 0) return list.getJSONObject(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 备用：老的 iteminfo 接口，页面结构变了时常常还能用 */
    private static JSONObject fromItemApi(String id) {
        try {
            Resp r = get("https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=" + id,
                    UA_MOBILE, "https://www.douyin.com/");
            if (r == null || r.body == null) return null;
            JSONObject root = new JSONObject(r.body);
            JSONArray list = root.optJSONArray("item_list");
            if (list != null && list.length() > 0) return list.getJSONObject(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 只取前 64KB 看有没有真数据 */
    static boolean probe(String url, String ua, String referer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Range", "bytes=0-65535");
            if (c.getResponseCode() >= 400) return false;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            in.close();
            if (n < 512) return false;
            // 只认容器头：mp4(f typ) / flv / mkv，避免把 HTML 错误页当成视频
            String head = new String(buf, 4, 4, "ISO-8859-1");
            boolean mp4 = n > 8 && "ftyp".equals(head);
            boolean flv = buf[0] == 'F' && buf[1] == 'L' && buf[2] == 'V';
            boolean mkv = (buf[0] & 0xFF) == 0x1A && (buf[1] & 0xFF) == 0x45;
            return mp4 || flv || mkv;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ── 快手 ──────────────────────────────────────────────────

    private static String kuaishouId(String url) {
        String u = url;
        if (!u.contains("/short-video/") && !u.contains("/fw/photo/") && !u.contains("photoId=")) {
            u = followTo(url, UA_PC);
        }
        Matcher m = Pattern.compile("/(?:short-video|fw/photo)/([A-Za-z0-9_-]{6,})").matcher(u);
        if (m.find()) return m.group(1);
        Matcher m2 = Pattern.compile("photoId=([A-Za-z0-9_-]{6,})").matcher(u);
        if (m2.find()) return m2.group(1);
        return null;
    }

    private static Media resolveKuaishou(String url) throws Exception {
        String id = kuaishouId(url);
        if (id == null) throw new IOException("没能从链接里认出快手作品 ID");

        String page = "https://www.kuaishou.com/short-video/" + id;
        Resp r = get(page, UA_PC, "https://www.kuaishou.com/");

        String photoUrl = null, caption = null;
        int k = r.body.indexOf("window.__APOLLO_STATE__");
        if (k >= 0) {
            int eq = r.body.indexOf('=', k);
            int end = r.body.indexOf("};", eq);
            if (eq > 0 && end > 0) {
                String js = r.body.substring(eq + 1, end + 1).trim();
                try {
                    JSONObject st = new JSONObject(js);
                    JSONObject dc = st.optJSONObject("defaultClient");
                    if (dc != null) {
                        java.util.Iterator<String> it = dc.keys();
                        while (it.hasNext()) {
                            JSONObject o = dc.optJSONObject(it.next());
                            if (o == null) continue;
                            if (photoUrl == null && o.has("photoUrl")) {
                                photoUrl = o.optString("photoUrl", null);
                                caption = o.optString("caption", null);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (photoUrl == null || photoUrl.isEmpty()) {
            // 兜底一：移动端分享页 window.INIT_STATE（桌面端风控时通常还能用）
            try {
                Resp mob = get("https://v.m.chenzhongtech.com/fw/photo/" + id,
                        UA_MOBILE, "https://v.m.chenzhongtech.com/");
                JSONObject st = jsonAfter(mob.body, "window.INIT_STATE");
                if (st != null) {
                    Map<String, String> hits = new HashMap<>();
                    scanFor(st, new String[]{"photoUrl", "caption", "coverUrl"}, hits, 0);
                    String u = hits.get("photoUrl");
                    if (u != null && u.startsWith("http")) {
                        photoUrl = u;
                        caption = hits.get("caption");
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (photoUrl == null || photoUrl.isEmpty()) {
            // 兜底二：GraphQL visionVideoDetail（可能被要求验证码）
            String query = "query visionVideoDetail($photoId: String, $type: String, $page: String) {"
                    + " visionVideoDetail(photoId: $photoId, type: $type, page: $page) {"
                    + " status photo { id caption photoUrl coverUrl duration } } }";
            JSONObject vars = new JSONObject();
            vars.put("photoId", id);
            vars.put("page", "detail");
            JSONObject payload = new JSONObject();
            payload.put("operationName", "visionVideoDetail");
            payload.put("variables", vars);
            payload.put("query", query);
            Resp g = postJson("https://www.kuaishou.com/graphql", payload.toString(),
                    UA_PC, page);
            try {
                JSONObject root = new JSONObject(g.body);
                JSONObject data = root.optJSONObject("data");
                JSONObject d = data == null ? null : data.optJSONObject("visionVideoDetail");
                JSONObject ph = d == null ? null : d.optJSONObject("photo");
                if (ph != null && ph.has("photoUrl")) {
                    photoUrl = ph.optString("photoUrl");
                    caption = ph.optString("caption", null);
                }
            } catch (Exception ignored) {
            }
        }

        if (photoUrl == null || photoUrl.isEmpty()) {
            throw new IOException("快手解析失败（可能作品已删、私密，或需要登录）");
        }
        Media m = new Media();
        m.platform = "kuaishou";
        m.id = "ks_" + id;
        m.title = (caption == null || caption.trim().isEmpty()) ? "kuaishou_" + id : caption;
        m.playUrl = photoUrl;
        m.referer = "https://www.kuaishou.com/";
        m.ua = UA_PC;
        return m;
    }
}
