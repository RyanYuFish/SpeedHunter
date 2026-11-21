package com.github.speedhunter.diag;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 诊断日志:全量落盘(内部 filesDir + 外部 app 目录,方便 adb pull)+ 内存环形缓冲供 UI。
 * 文件 speedhunter_diag.log,追加写;startSession() 打一次会话头(时间/VCI/版本)。
 */
public final class DiagLogger {
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat HDR = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final Deque<String> buffer = new ArrayDeque<>();
    private final StringBuilder session = new StringBuilder();
    private File internalFile;
    private File externalFile;

    public void attach(Context context) {
        internalFile = new File(context.getFilesDir(), "speedhunter_diag.log");
        File ext = context.getExternalFilesDir(null);
        if (ext != null) externalFile = new File(ext, "speedhunter_diag.log");
    }

    private long sessionStartOffset = 0L;

    /** 新会话:记录文件内偏移(导出只取本次会话),清空内存缓冲(界面/分享只见本次会话)。 */
    public void startSession(String info) {
        sessionStartOffset = fileLength(internalFile);
        buffer.clear();
        String head = "\n===== DIAG SESSION " + HDR.format(new Date()) + " | " + info + " =====";
        append(head);
    }

    private static long fileLength(File f) {
        try { return f != null && f.exists() ? f.length() : 0L; } catch (Exception e) { return 0L; }
    }

    /** 本次会话内容(会话头起的文件尾段;内部文件不可读时回退内存缓冲)。 */
    public synchronized byte[] sessionBytes() {
        try {
            if (internalFile != null && internalFile.exists()) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(internalFile, "r")) {
                    long len = raf.length();
                    long off = Math.min(Math.max(sessionStartOffset, 0L), len);
                    byte[] b = new byte[(int) (len - off)];
                    raf.seek(off);
                    raf.readFully(b);
                    return b;
                }
            }
        } catch (Exception ignored) { }
        return dump().getBytes();
    }

    public synchronized void log(String tag, String message) {
        String line = TS.format(new Date()) + " " + tag + " " + message;
        append(line);
    }

    private void append(String line) {
        buffer.addLast(line);
        while (buffer.size() > 2000) buffer.pollFirst();
        session.append(line).append('\n');
        write(internalFile);
        write(externalFile);
    }

    private void write(File f) {
        if (f == null) return;
        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            fos.write(session.toString().getBytes());
            session.setLength(0);
        } catch (IOException ignored) {
        }
    }

    /** 供 ACTION_SEND 分享的全文(内存缓冲,最多 2000 行)。 */
    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : buffer) sb.append(l).append('\n');
        return sb.toString();
    }

    public String externalPath() {
        return externalFile == null ? "(无外部存储)" : externalFile.getAbsolutePath();
    }

    /**
     * 导出完整日志为 .log 文件(2026-11-22 车主令:日志过长剪贴板放不下,直接导出文件)。
     * API≥29 经 MediaStore 写入公共 Download/(无权限);旧版本回退 app 外部目录带时间戳副本。
     * 返回可直接告知/分享的 URI 字符串;失败返回 null。
     */
    public android.net.Uri exportLogFile(Context context) {
        byte[] all = sessionBytes(); // 5.2.10:仅本次诊断会话(车主令:日志过长,只保留/导出当前会话)
        if (all == null || all.length == 0) return null;
        String name = "speedhunter_diag_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".log";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/SpeedHunter");
                android.net.Uri uri = context.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                try (java.io.OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                    if (os == null) return null;
                    os.write(all);
                    os.flush();
                }
                return uri;
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext == null) return null;
            File out = new File(ext, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(all);
                fos.flush();
            }
            return android.net.Uri.fromFile(out);
        } catch (Exception ignored) {
            return null;
        }
    }



    public synchronized void clearUi() {
        buffer.clear();
    }
}
