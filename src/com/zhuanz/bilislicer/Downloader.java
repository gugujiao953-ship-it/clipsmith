package com.zhuanz.bilislicer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 带 Referer 的下载器（B站 CDN 校验 Referer） */
public class Downloader {

    public interface Progress {
        void onProgress(long got, long total);
    }

    public static void download(String url, File dest, Progress cb) throws IOException {
        download(url, dest, BiliApi.UA, "https://www.bilibili.com", cb);
    }

    public static void download(String url, File dest, String ua, String referer,
                                Progress cb) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                once(url, dest, ua, referer, cb);
                return;
            } catch (IOException e) {
                last = e;
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                if (attempt == 0) cb.onProgress(0, 0);      // 进度归零，重下一次
            }
        }
        throw last == null ? new IOException("下载失败") : last;
    }

    /** 从已有文件末尾继续下（Range），用来补齐被 CDN 截断的部分 */
    public static void append(String url, File dest, String ua, String referer,
                              Progress cb) throws IOException {
        long have = dest.exists() ? dest.length() : 0;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", ua == null ? BiliApi.UA : ua);
        c.setRequestProperty("Referer", referer == null ? "https://www.bilibili.com" : referer);
        c.setRequestProperty("Range", "bytes=" + have + "-");
        int code = c.getResponseCode();
        if (code != 206 && code != 200) {
            throw new IOException("续传被拒绝 HTTP " + code);
        }
        InputStream in = c.getInputStream();
        java.io.FileOutputStream out = new java.io.FileOutputStream(dest, code == 206);
        byte[] buf = new byte[1 << 16];
        int n;
        long got = code == 206 ? have : 0;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (cb != null) cb.onProgress(got, 0);
            }
        } finally {
            try {
                out.close();
            } catch (Exception ignored) {
            }
            try {
                in.close();
            } catch (Exception ignored) {
            }
            c.disconnect();
        }
    }

    private static void once(String url, File dest, String ua, String referer,
                             Progress cb) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", ua == null ? BiliApi.UA : ua);
        c.setRequestProperty("Referer", referer == null ? "https://www.bilibili.com" : referer);
        c.setInstanceFollowRedirects(true);

        int code = c.getResponseCode();
        if (code >= 400) {
            throw new IOException("下载被拒绝 HTTP " + code + "（链接可能过期，重试一次）");
        }
        long total = c.getContentLengthLong();

        InputStream in = c.getInputStream();
        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 1 << 16);
        byte[] buf = new byte[1 << 16];
        long got = 0;
        int n;
        long last = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                long now = System.currentTimeMillis();
                if (now - last > 400 && cb != null) {
                    last = now;
                    cb.onProgress(got, total > 0 ? total : 0);
                }
            }
        } finally {
            try { out.close(); } catch (Exception ignored) { }
            try { in.close(); } catch (Exception ignored) { }
            c.disconnect();
        }
        // 断流时 read() 会正常返回 -1，必须自己比对长度，否则会拿到截断的文件却不报错
        if (total > 0 && got != total) {
            throw new IOException("下载不完整（" + got + "/" + total + " 字节）");
        }
        if (cb != null) cb.onProgress(got, total > 0 ? total : got);
    }
}
