package com.zhuanz.bilislicer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理记录：用来判断「这个视频是不是已经切过了」。
 * 存 SharedPreferences 里的一个 JSON 数组，按时间倒序，列表每页 50 条。
 */
public class History {

    public static final int PAGE_SIZE = 50;
    private static final String PREF = "bili_hist";
    private static final String KEY = "records";
    private static final int MAX_KEEP = 3000;

    public static class Rec {
        public String key = "";        // bvid / 抖音作品 id，去重靠它
        public String title = "";
        public int parts;
        public long size;
        public long time;
        public String platform = "";
        public String url = "";
        public boolean fail;      // 这次是失败记录
        public String note = "";  // 失败原因
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static synchronized List<Rec> all(Context c) {
        List<Rec> out = new ArrayList<>();
        try {
            String raw = sp(c).getString(KEY, "");
            if (raw == null || raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Rec r = new Rec();
                r.key = o.optString("k");
                r.title = o.optString("t");
                r.parts = o.optInt("p");
                r.size = o.optLong("s");
                r.time = o.optLong("ts");
                r.platform = o.optString("pf");
                r.url = o.optString("u");
                r.fail = o.optBoolean("f");
                r.note = o.optString("n");
                r.title = withPartPrefix(r.key, r.title);
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 多P 记录把 P 号放标题最前，避免标题太长被截掉看不出是第几集 */
    private static String withPartPrefix(String key, String title) {
        if (key == null || title == null) return title;
        java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("_p(\\d+)$").matcher(key);
        if (!pm.find()) return title;
        String no = pm.group(1);
        if (title.startsWith("P" + no + " · ")) return title;
        // 旧记录可能已经带了「 · Pn」后缀，先去掉
        String t = title.replaceFirst(" · P" + no + "$", "");
        return "P" + no + " · " + t;
    }

    private static void save(Context c, List<Rec> list) {
        try {
            JSONArray arr = new JSONArray();
            int n = 0;
            for (Rec r : list) {
                if (n++ >= MAX_KEEP) break;
                JSONObject o = new JSONObject();
                o.put("k", r.key);
                o.put("t", r.title);
                o.put("p", r.parts);
                o.put("s", r.size);
                o.put("ts", r.time);
                o.put("pf", r.platform);
                o.put("u", r.url);
                if (r.fail) {
                    o.put("f", true);
                    o.put("n", r.note);
                }
                arr.put(o);
            }
            sp(c).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 记录一条（同 key 覆盖，移到最前） */
    public static synchronized void add(Context c, Rec rec) {
        if (rec == null || rec.key == null || rec.key.isEmpty()) return;
        List<Rec> list = all(c);
        for (int i = 0; i < list.size(); i++) {
            if (rec.key.equals(list.get(i).key)) {
                list.remove(i);
                break;
            }
        }
        if (rec.time == 0) rec.time = System.currentTimeMillis();
        list.add(0, rec);
        save(c, list);
    }

    /** 只算成功的记录：失败过的视频应该允许直接重试，不该弹「已经处理过」 */
    public static synchronized boolean has(Context c, String key) {
        if (key == null || key.isEmpty()) return false;
        for (Rec r : all(c)) {
            if (!r.fail && key.equals(r.key)) return true;
        }
        return false;
    }

    public static synchronized Rec find(Context c, String key) {
        if (key == null) return null;
        for (Rec r : all(c)) {
            if (!r.fail && key.equals(r.key)) return r;
        }
        return null;
    }

    public static synchronized boolean remove(Context c, String key) {
        List<Rec> list = all(c);
        boolean removed = false;
        for (int i = 0; i < list.size(); i++) {
            if (key != null && key.equals(list.get(i).key)) {
                list.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) save(c, list);
        return removed;
    }

    /** 只清掉失败记录，成功记录保留 */
    public static synchronized int clearFails(Context c) {
        List<Rec> list = all(c);
        int before = list.size();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).fail) list.remove(i);
        }
        int removed = before - list.size();
        if (removed > 0) save(c, list);
        return removed;
    }

    public static synchronized void clear(Context c) {
        sp(c).edit().remove(KEY).apply();
    }

    /** 记一条失败（同一链接再次失败只保留最新一次） */
    public static synchronized void addFail(Context c, String url, String note) {
        Rec r = new Rec();
        String k = keyOf(url);
        r.key = k.isEmpty() ? ("url_" + Math.abs(url.hashCode())) : k;
        r.title = url;
        r.url = url;
        r.fail = true;
        r.note = note == null ? "" : note;
        r.time = System.currentTimeMillis();
        add(c, r);
    }

    /** 某页的记录（第 0 页是最新 50 条） */
    public static List<Rec> page(Context c, int pageIndex) {
        List<Rec> list = all(c);
        int from = Math.max(0, pageIndex) * PAGE_SIZE;
        if (from >= list.size()) return new ArrayList<>();
        int to = Math.min(list.size(), from + PAGE_SIZE);
        return new ArrayList<>(list.subList(from, to));
    }

    public static int pageCount(Context c) {
        int n = all(c).size();
        return Math.max(1, (n + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** 从链接里取去重用的 key（B站 bvid / 抖音快手作品 id） */
    public static String keyOf(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(url);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("(?:/video/|modal_id=)(\\d{6,})").matcher(url);
        if (m.find()) return "dy_" + m.group(1);
        m = java.util.regex.Pattern.compile("(?:photoId=|/photo/)([0-9A-Za-z_-]{6,})").matcher(url);
        if (m.find()) return "ks_" + m.group(1);
        m = java.util.regex.Pattern.compile("/av(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(url);
        if (m.find()) return "av" + m.group(1);
        return "";
    }
}
