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

    public void startSession(String info) {
        String head = "\n===== DIAG SESSION " + HDR.format(new Date()) + " | " + info + " =====";
        append(head);
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

    public synchronized void clearUi() {
        buffer.clear();
    }
}
