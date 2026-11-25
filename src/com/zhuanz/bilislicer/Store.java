package com.zhuanz.bilislicer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/** 把成品写进「手机存储/Download/B站切片/…」，不需要存储权限 */
public class Store {

    public static Uri saveToDownloads(Context ctx, File src, String subDir, String name)
            throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/B站切片/" + subDir);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("无法在下载目录创建文件");

        try (OutputStream os = cr.openOutputStream(uri);
             java.nio.channels.FileChannel in =
                     new FileInputStream(src).getChannel()) {
            java.nio.channels.WritableByteChannel out =
                    java.nio.channels.Channels.newChannel(os);
            long total = in.size(), done = 0;
            while (done < total) {
                long n = in.transferTo(done, total - done, out);
                if (n <= 0) break;
                done += n;
            }
        } catch (Exception e) {
            // 拷贝失败要把 IS_PENDING 记录删掉，否则媒体库里留下 0 字节幽灵条目
            try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    /**
     * 重新处理同一条视频时，把这个视频文件夹里的旧片段全清掉。
     * 不清的话 MediaStore 会自动改名成 xxx (1).mp4，同一个视频就存了两份。
     * 按「整个文件夹」清，是因为文件夹就是这条视频的归属；
     * 只按文件名匹配的话，用户改过名之后就匹配不到了。
     */
    /** 删掉「B站切片」下已经空了的子文件夹（只删空目录，绝不碰有内容的） */
    public static int purgeEmptyDirs(Context ctx) {
        int n = 0;
        try {
            File root = new File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "Download/B站切片");
            File[] kids = root.listFiles();
            if (kids == null) return 0;
            for (File d : kids) {
                if (!d.isDirectory()) continue;
                String[] inner = d.list();
                if (inner != null && inner.length == 0 && d.delete()) n++;
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** 某个子目录空了就顺手删掉（单个删除、清旧片段之后调用） */
    public static void dropIfEmpty(String subDir) {
        if (subDir == null || subDir.isEmpty()) return;
        try {
            String rel = subDir.startsWith("Download/") ? subDir : ("Download/B站切片/" + subDir);
            File d = new File(android.os.Environment.getExternalStorageDirectory(), rel);
            String[] inner = d.list();
            if (d.isDirectory() && inner != null && inner.length == 0) {
                //noinspection ResultOfMethodCallIgnored
                d.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public static int clearExisting(Context ctx, String subDir) {
        ContentResolver cr = ctx.getContentResolver();
        String rel = Environment.DIRECTORY_DOWNLOADS + "/B站切片/" + subDir + "/";
        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
        };
        int removed = 0;
        Cursor c = null;
        try {
            c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                    MediaStore.MediaColumns.RELATIVE_PATH + " = ?", new String[]{rel}, null);
            if (c == null) return 0;
            int iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            while (c.moveToNext()) {
                String name = c.getString(iName);
                if (name == null) continue;
                Uri u = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(iId));
                try {
                    if (cr.delete(u, null, null) > 0) removed++;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return removed;
    }

    /** 文件名里不能出现的字符 */
    public static String safe(String s) {
        if (s == null || s.isEmpty()) return "video";
        String r = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        // 按码点截断，避免把 emoji 的代理对切成半个字符
        if (r.codePointCount(0, r.length()) > 42) {
            r = r.substring(0, r.offsetByCodePoints(0, 42));
        }
        return r.isEmpty() ? "video" : r;
    }
}
