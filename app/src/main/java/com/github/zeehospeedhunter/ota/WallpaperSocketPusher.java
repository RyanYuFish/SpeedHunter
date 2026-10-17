package com.github.zeehospeedhunter.ota;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Independent sender for the firmware's custom-wallpaper socket protocol. */
public final class WallpaperSocketPusher {
    private static final int PORT = 10950;
    private static final int CHUNK_SIZE = 4096;
    private static final long MAX_PACKAGE_BYTES = 64L * 1024 * 1024;
    private static final int CMD_START = 0x10000043;
    private static final int CMD_DATA = 0x10000044;
    private static final int CMD_END = 0x10000045;
    private static final int CMD_VERIFY = 0x10000046;
    private static final int CMD_VERIFY_ACK = 0x80000046;
    private static final int CMD_START_ACK = 0x80000043;

    public interface Callback {
        void onLog(String line);
        void onProgress(int percent);
        void onDone(boolean ok, String message);
    }

    private WallpaperSocketPusher() {}

    public static void push(final Context context, final Uri uri, final Callback callback) {
        new Thread(() -> {
            File zip = null;
            try {
                zip = copyToCache(context, uri);
                JSONObject descriptor = validatePackage(zip);
                transfer(zip, descriptor, callback);
                callback.onDone(true, "仪表已确认壁纸包校验；布局是否被当前固件完整解释仍需实车界面确认。");
            } catch (Exception e) {
                callback.onLog("失败：" + e.getMessage());
                callback.onDone(false, e.getMessage());
            } finally {
                if (zip != null) zip.delete();
            }
        }, "wallpaper-push").start();
    }

    private static File copyToCache(Context context, Uri uri) throws Exception {
        File out = File.createTempFile("dashboard-wallpaper-", ".zip", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream stream = new FileOutputStream(out)) {
            if (in == null) throw new IllegalArgumentException("无法读取所选文件");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.length() + n > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("壁纸 ZIP 超过 64 MiB");
                stream.write(buffer, 0, n);
            }
        }
        return out;
    }

    private static JSONObject validatePackage(File zip) throws Exception {
        JSONObject descriptor = null;
        Map<String, String> manifest = null;
        Map<String, byte[]> hashes = new HashMap<>();
        try (ZipFile archive = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!safeEntryName(name)) throw new IllegalArgumentException("ZIP 含不安全路径：" + name);
                if (entry.isDirectory()) continue;
                if (!(name.equals("bg.png") || name.equals("wallpaper.json") || name.equals("MANIFEST.txt")
                        || name.equals("README.txt") || name.matches("card-[A-Za-z0-9_.%~-]+\\.png"))) {
                    throw new IllegalArgumentException("ZIP 含非壁纸包文件：" + name);
                }
                if ("wallpaper.json".equals(name)) {
                    byte[] bytes = readLimited(archive.getInputStream(entry), 1024 * 1024);
                    descriptor = new JSONObject(new String(bytes, "UTF-8"));
                    hashes.put(name, digestEntry(archive, entry));
                } else if ("MANIFEST.txt".equals(name)) {
                    byte[] bytes = readLimited(archive.getInputStream(entry), 1024 * 1024);
                    manifest = parseManifest(new String(bytes, "UTF-8"));
                } else if ("README.txt".equals(name)) {
                    continue;
                } else {
                    hashes.put(name, digestEntry(archive, entry));
                }
            }
        }
        if (descriptor == null || !"zeeho-dashboard-wallpaper/1".equals(descriptor.optString("format"))) {
            throw new IllegalArgumentException("所选文件不是 Dashboard Studio 壁纸包");
        }
        if (manifest == null) throw new IllegalArgumentException("壁纸包缺少 MANIFEST.txt");
        String packageName = descriptor.optString("name", "");
        if (!packageName.matches("[A-Za-z0-9_\\-.\\u4e00-\\u9fff]{1,60}") || packageName.equals(".") || packageName.equals("..")) {
            throw new IllegalArgumentException("壁纸包名包含固件路径不支持的字符");
        }
        if (!manifest.keySet().equals(hashes.keySet())) throw new IllegalArgumentException("MANIFEST 与 ZIP 文件清单不一致");
        for (Map.Entry<String, byte[]> item : hashes.entrySet()) {
            String[] expected = manifest.get(item.getKey()).split(":", 2);
            if (expected.length != 2 || Long.parseLong(expected[0]) != item.getValue().length
                    || !expected[1].equals(hex(item.getValue()))) {
                throw new IllegalArgumentException("MANIFEST 校验失败：" + item.getKey());
            }
        }
        if (zip.length() <= 0 || zip.length() > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("壁纸 ZIP 大小无效");
        return descriptor;
    }

    private static boolean safeEntryName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) return false;
        for (String part : name.split("/")) if (part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static Map<String, String> parseManifest(String text) {
        Map<String, String> files = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] columns = line.trim().split("\\s+", 3);
            if (columns.length != 3 || !columns[0].matches("[0-9a-fA-F]{64}")
                    || !columns[1].matches("[0-9]+") || files.put(columns[2], columns[1] + ":" + columns[0].toLowerCase()) != null) {
                throw new IllegalArgumentException("MANIFEST 格式错误");
            }
        }
        return files;
    }

    private static byte[] digestEntry(ZipFile zip, ZipEntry entry) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("ZIP 解压后文件过大");
                digest.update(buffer, 0, n);
            }
        }
        if (entry.getSize() >= 0 && total != entry.getSize()) throw new IllegalArgumentException("ZIP 文件大小不符");
        return digest.digest();
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        try (InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IllegalArgumentException("wallpaper.json 过大");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void transfer(File zip, JSONObject descriptor, Callback cb) throws Exception {
        String host = OtaSocketPusher.carHost();
        String name = descriptor.optString("name", "zeeho-wallpaper");
        String md5 = md5(zip);
        cb.onLog("连接壁纸通道 " + host + ":" + PORT);
        try (Socket socket = new Socket()) {
            String local = OtaSocketPusher.localIpOnCarNet();
            if (local != null) socket.bind(new InetSocketAddress(local, 0));
            socket.connect(new InetSocketAddress(host, PORT), 8000);
            socket.setSoTimeout(12000);
            JSONObject start = new JSONObject()
                    .put("fileName", name)
                    .put("wallpaperNum", "0")
                    .put("displayWallpaperSet", "custom")
                    .put("md5", md5)
                    .put("fileSize", zip.length());
            sendFrame(socket.getOutputStream(), CMD_START, 1, start.toString().getBytes("UTF-8"));
            try {
                socket.setSoTimeout(2000);
                Frame response = readFrame(socket.getInputStream());
                if (response.cmd != CMD_START_ACK) {
                    throw new IllegalStateException(String.format("仪表拒绝壁纸启动帧 (0x%08X)", response.cmd));
                }
            } catch (java.net.SocketTimeoutException ignored) {
                cb.onLog("启动帧无同步回包，继续按固件异步模式发送数据");
            } finally {
                socket.setSoTimeout(12000);
            }
            cb.onLog("仪表接受包信息，" + zip.length() + " 字节，MD5 " + md5);

            try (InputStream in = new BufferedInputStream(new FileInputStream(zip))) {
                byte[] chunk = new byte[CHUNK_SIZE];
                long sent = 0;
                int n;
                while ((n = in.read(chunk)) != -1) {
                    byte[] payload = n == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, n);
                    sendFrame(socket.getOutputStream(), CMD_DATA, 0, payload);
                    sent += n;
                    cb.onProgress((int) Math.min(99, sent * 100 / zip.length()));
                }
            }
            JSONObject end = new JSONObject().put("recvWallpaperFileFinishedFlag", 1);
            sendFrame(socket.getOutputStream(), CMD_END, 1, end.toString().getBytes("UTF-8"));
            cb.onLog("数据发送完成，等待仪表文件校验请求…");
            Frame verify = readFrame(socket.getInputStream());
            if (verify.cmd != CMD_VERIFY) throw new IllegalStateException(String.format("未收到仪表壁纸校验请求 (0x%08X)", verify.cmd));
            sendFrame(socket.getOutputStream(), CMD_VERIFY_ACK, 1, new byte[0]);
            cb.onProgress(100);
            cb.onLog("已回复壁纸校验 ACK 0x80000046");
        }
    }

    static byte[] frame(int command, int channel, byte[] payload) {
        byte[] frame = new byte[10 + payload.length];
        frame[1] = (byte) channel;
        frame[2] = (byte) (command >>> 24);
        frame[3] = (byte) (command >>> 16);
        frame[4] = (byte) (command >>> 8);
        frame[5] = (byte) command;
        frame[6] = (byte) (payload.length >>> 24);
        frame[7] = (byte) (payload.length >>> 16);
        frame[8] = (byte) (payload.length >>> 8);
        frame[9] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 10, payload.length);
        return frame;
    }

    private static void sendFrame(OutputStream out, int command, int channel, byte[] payload) throws Exception {
        out.write(frame(command, channel, payload));
        out.flush();
    }

    private static Frame readFrame(InputStream in) throws Exception {
        byte[] header = readFully(in, 10);
        int length = ((header[6] & 255) << 24) | ((header[7] & 255) << 16) | ((header[8] & 255) << 8) | (header[9] & 255);
        if (length < 0 || length > 1024 * 1024) throw new IllegalStateException("仪表回包长度异常：" + length);
        int command = ((header[2] & 255) << 24) | ((header[3] & 255) << 16) | ((header[4] & 255) << 8) | (header[5] & 255);
        return new Frame(command, readFully(in, length));
    }

    private static byte[] readFully(InputStream in, int length) throws Exception {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(bytes, offset, length - offset);
            if (n < 0) throw new java.io.EOFException("仪表连接提前关闭");
            offset += n;
        }
        return bytes;
    }

    private static String md5(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    private static final class Frame {
        final int cmd;
        final byte[] payload;
        Frame(int command, byte[] body) { cmd = command; payload = body; }
    }
}
