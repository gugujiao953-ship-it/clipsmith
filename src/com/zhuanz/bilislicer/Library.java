package com.zhuanz.bilislicer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 片库：直接查 MediaStore 里 Download/B站切片/ 下的文件。
 * 不需要任何存储权限，App 被卸载重装后依然能看到自己下过的片子。
 */
public class Library {

    public static final String ROOT = "Download/B站切片";

    public static class Item {
        public long id;
        public Uri uri;
        public String name;
        public long size;
        public long durMs;      // 视频时长（MediaStore 提供）
        public long dateAdded;
        public String relPath;
        public String path;       // 真实路径，跨应用删除时要用
    }

    public static class Group {
        public String title;
        public String relPath;
        public long bytes;
        public long durMs;      // 整组时长合计
        public final List<Item> items = new ArrayList<>();
    }

    public static List<Group> load(Context ctx) {
        Map<String, Group> map = new LinkedHashMap<>();
        ContentResolver cr = ctx.getContentResolver();
        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATA
        };
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = {ROOT + "/%"};

        Cursor c = null;
        try {
            try {
                c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                        MediaStore.MediaColumns.DATE_ADDED + " DESC, " + MediaStore.MediaColumns.DISPLAY_NAME + " ASC");
            } catch (Exception e) {
                // 部分机型（如小米平板）的 downloads 集合不认识 DURATION 列，
                // 整个查询会直接抛异常 —— 退回不带时长的列，至少列表能出来
                android.util.Log.w("BiliSlicer", "library query retry without DURATION", e);
                proj = new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.RELATIVE_PATH
                };
                c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                        MediaStore.MediaColumns.DATE_ADDED + " DESC, " + MediaStore.MediaColumns.DISPLAY_NAME + " ASC");
            }
            if (c == null) {
                android.util.Log.w("BiliSlicer", "library cursor null");
                return new ArrayList<>();
            }
            android.util.Log.i("BiliSlicer", "library rows=" + c.getCount());
            int iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int iDur = c.getColumnIndex(MediaStore.MediaColumns.DURATION);
            int iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            int iData = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(iId);
                it.name = c.getString(iName);
                it.size = c.getLong(iSize);
                it.durMs = iDur >= 0 ? c.getLong(iDur) : 0;
                it.dateAdded = c.getLong(iDate);
                it.relPath = c.getString(iPath);
                it.path = iData >= 0 ? c.getString(iData) : null;
                if (it.relPath == null) it.relPath = ROOT + "/";
                it.uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.id);

                Group g = map.get(it.relPath);
                if (g == null) {
                    g = new Group();
                    g.relPath = it.relPath;
                    g.title = folderName(it.relPath);
                    map.put(it.relPath, g);
                }
                g.items.add(it);
        g.durMs += it.durMs;
                g.bytes += it.size;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return new ArrayList<>(map.values());
    }

    static String folderName(String relPath) {
        String p = relPath;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        String n = i >= 0 ? p.substring(i + 1) : p;
        return n.isEmpty() ? "未命名" : n;
    }

    public static boolean delete(Context ctx, Item it) {
        try {
            int rows = ctx.getContentResolver().delete(it.uri, null, null);
            Log.i("BiliSlicer", "delete rows=" + rows + " uri=" + it.uri);
            if (rows > 0) return true;
        } catch (Exception e) {
            Log.w("BiliSlicer", "delete via MediaStore fail", e);
        }
        // 别的应用写进来的文件，MediaStore 不让删；有「所有文件访问」时走文件路径
        try {
            if (it.path != null && !it.path.isEmpty()) {
                java.io.File f = new java.io.File(it.path);
                boolean ok = f.delete();
                Log.i("BiliSlicer", "delete via file=" + ok + " " + it.path);
                return ok;
            }
        } catch (Exception e) {
            Log.w("BiliSlicer", "delete via file fail", e);
        }
        return false;
    }

    /** 有没有「所有文件访问」权限（能删别处拷进来的文件） */
    public static boolean hasAllFiles(Context ctx) {
        try {
            return android.os.Environment.isExternalStorageManager();
        } catch (Exception e) {
            return false;
        }
    }

    /** 改名（自动补 .mp4） */
    public static boolean rename(Context ctx, Item it, String newName) {
        String n = newName == null ? "" : newName.trim();
        if (n.isEmpty()) return false;
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!n.toLowerCase(Locale.ROOT).endsWith(".mp4")) n = n + ".mp4";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, n);
            int rows = ctx.getContentResolver().update(it.uri, v, null, null);
            Log.i("BiliSlicer", "rename -> " + n + " rows=" + rows + " uri=" + it.uri);
            return rows > 0;
        } catch (Exception e) {
            Log.w("BiliSlicer", "rename fail", e);
            return false;
        }
    }

    public static String mb(long b) {
        return String.format(Locale.CHINA, "%.2f MB", b / 1000000.0);
    }
}
