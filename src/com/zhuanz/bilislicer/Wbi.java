package com.zhuanz.bilislicer;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B站 wbi 签名 + 简易 Cookie 管理。
 *
 * 为什么要这个：搜 UP 主、拉 UP 主投稿列表这些接口从 2023 年起要求 wbi 签名，
 * 而且没带 buvid3 cookie 会直接 412（被风控）。
 * 签名算法：mixin_key = 打乱表重排(img_key + sub_key) 取前 32 位，
 *           w_rid = md5(排序后的 query + mixin_key)
 */
public class Wbi {

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int[] MIXIN = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    private static final Map<String, String> COOKIES = new LinkedHashMap<>();
    private static String mixinKey;
    private static long mixinAt;
    private static boolean warmedOnce;

    // ── HTTP ────────────────────────────────────────────────

    static String get(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", referer == null ? "https://www.bilibili.com" : referer);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        synchronized (COOKIES) {
            if (!COOKIES.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : COOKIES.entrySet()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(e.getKey()).append('=').append(e.getValue());
                }
                c.setRequestProperty("Cookie", sb.toString());
            }
        }
        absorb(c);
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + c.getResponseCode());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bo.toString("UTF-8");
    }

    private static void absorb(HttpURLConnection c) {
        try {
            Map<String, List<String>> h = c.getHeaderFields();
            if (h == null) return;
            for (Map.Entry<String, List<String>> e : h.entrySet()) {
                if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                for (String v : e.getValue()) {
                    if (v == null) continue;
                    int eq = v.indexOf('=');
                    int semi = v.indexOf(';');
                    if (eq <= 0) continue;
                    String name = v.substring(0, eq).trim();
                    String val = v.substring(eq + 1, semi > 0 ? semi : v.length()).trim();
                    if (name.isEmpty() || val.isEmpty()) continue;
                    synchronized (COOKIES) {
                        COOKIES.put(name, val);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 先摸一次首页拿 buvid3，否则搜索接口会 412。整个进程只需要一次 */
    static void warmUp() {
        synchronized (Wbi.class) {
            if (warmedOnce) return;
            warmedOnce = true;
        }
        try {
            get("https://www.bilibili.com/", "https://www.bilibili.com");
        } catch (Exception e) {
            Log.w("BiliSlicer", "warmUp fail", e);
        }
    }

    // ── 签名 ────────────────────────────────────────────────

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String mixinKey() throws Exception {
        long now = System.currentTimeMillis();
        if (mixinKey != null && now - mixinAt < 3600_000L) return mixinKey;

        String body = get("https://api.bilibili.com/x/web-interface/nav", "https://www.bilibili.com");
        org.json.JSONObject nav = new org.json.JSONObject(body);
        org.json.JSONObject img = nav.optJSONObject("data") == null ? null
                : nav.getJSONObject("data").optJSONObject("wbi_img");
        if (img == null) throw new Exception("拿不到 wbi 签名密钥（B站接口可能变了）");
        String imgKey = baseName(img.optString("img_url"));
        String subKey = baseName(img.optString("sub_url"));
        if (imgKey.isEmpty() || subKey.isEmpty()) throw new Exception("wbi 密钥为空");

        String raw = imgKey + subKey;
        StringBuilder sb = new StringBuilder();
        for (int idx : MIXIN) {
            if (idx < raw.length()) sb.append(raw.charAt(idx));
        }
        mixinKey = sb.substring(0, Math.min(32, sb.length()));
        mixinAt = now;
        return mixinKey;
    }

    private static String baseName(String url) {
        if (url == null) return "";
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 给参数加上 wts 和 w_rid，返回可直接拼到 URL 后面的 query */
    static String sign(Map<String, String> params) throws Exception {
        String key = mixinKey();
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().replaceAll("[!'()*]", "");
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
             .append(URLEncoder.encode(v, "UTF-8"));
        }
        String wRid = md5(q + "&" + key);

        StringBuilder out = new StringBuilder(q);
        out.append("&w_rid=").append(wRid);
        return out.toString();
    }

    /**
     * 签名并请求，返回 JSON。
     * B站的风控有两种表现：错误码 -352/-403/-412，以及 code=0 但只返回 v_voucher（人机验证挑战）。
     * 两种都当作「重试信号」：刷新密钥、等一下再来，最多 3 次。
     */
    static org.json.JSONObject signedGet(String base, Map<String, String> params) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(attempt == 1 ? 2000 : 4000);
            String body = get(base + "?" + sign(params), "https://www.bilibili.com");
            org.json.JSONObject o;
            try {
                o = new org.json.JSONObject(body);
            } catch (Exception e) {
                // 返回了非 JSON（多半是风控页），也重试一次
                if (attempt < 1) {
                    mixinKey = null;
                    continue;
                }
                throw new Exception("B站返回了非预期内容（可能被风控）");
            }

            int code = o.optInt("code");
            boolean voucher = false;
            if (code == 0) {
                org.json.JSONObject data = o.optJSONObject("data");
                if (data != null && !data.optString("v_voucher").isEmpty()
                        && data.optJSONArray("result") == null) {
                    voucher = true;
                } else {
                    return o;
                }
            }

            boolean retryable = voucher || code == -352 || code == -403 || code == -412;
            if (attempt < 2 && retryable) {
                // 签名类错误才需要刷新密钥；人机验证只要等一下再来
                if (!voucher) mixinKey = null;
                continue;
            }
            if (voucher) {
                throw new Exception("B站要人机验证（风控），等几分钟再试，"
                        + "或直接粘贴 UP 主空间链接 space.bilibili.com/数字");
            }
            throw new Exception(BiliApi.friendly(code, o.optString("message")));
        }
        throw new Exception("请求失败（重试 3 次仍被风控）");
    }
}
