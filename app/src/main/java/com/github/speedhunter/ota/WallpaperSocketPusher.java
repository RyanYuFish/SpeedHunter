package com.github.speedhunter.ota;

import com.github.speedhunter.core.HookLog;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <b>仪表自定义壁纸推送（socket_l 10950 通道）</b>。
 *
 * <h3>协议（2026-10-18 固件逆向 +官方 App 脱壳交叉确认）</h3>
 * <pre>
 * 手机 → 车机：
 *   0x10000011 versionMessage            版本协商（无回包属正常）
 *   0x10000043 wallpaperStartMessage     {fileName, wallpaperNum, displayWallpaperSet, md5, fileSize}
 *   0x10000044 wirtewallpaperData        数据帧（head[1]=0）
 *   0x10000045 wallpaperEndMessage       {recvWallpaperFileFinishedFlag:1}
 * 车机 → 手机：
 *   0x80000043 {"code":N}                start 回执（N=1 表示 checkWallpaperSetRequirements 通过）
 *   0x10000046 wallpaperVerifyMessage    校验请求（★ 必须回，否则车机反复重发）
 * 手机 → 车机：
 *   0x80000046 wallpaperVerifyMessageAck
 * </pre>
 *
 * <h3>包格式（官方 App 实推的包解剖所得）</h3>
 * <pre>
 *   wallpaper.zip
 *     1.png  307×460  RGBA   小图 / card   （DASH: slot_Wallpaper_update_cardsize）
 *     2.png  1024×600 RGBA   大图 / 背景    （DASH: slot_Wallpaper_update_bgsize）
 * </pre>
 * 车机收到 end 后：{@code md5_file} 校验 → {@code unZIPWallPaperFile()} →
 * {@code signalUseWallpaper(name)} → DASH 写 {@code persist.sys.wallpapername}。
 *
 * <h3>字段取值</h3>
 * <ul>
 *   <li>{@code wallpaperNum}：固件对其做 {@code stoi}(base 10) 且要求 <b>1 或 2</b>
 *       （{@code if ((int)v - 1U < 2)}）⇒ 只能 "1"/"2"，即选哪张图。</li>
 *   <li>{@code displayWallpaperSet}：固件要求<b>非空</b>（空则
 *       {@code displayWallpaperSet null or fileSize == 0, not process this req}），
 *       固件里没有对应字面量，默认 {@link #DEFAULT_DISPLAY_SET}，可调。</li>
 * </ul>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>只推壁纸 zip，不涉及任何 OTA 包。</li>
 *   <li>仪表不回报结果，成功与否以仪表屏幕为准（静默 ≠ 失败）。</li>
 *   <li>单连接：车机 10950 是<b>单逻辑客户端</b>，官方 App 占用时本工具连不上（属预期）。</li>
 * </ul>
 */
public final class WallpaperSocketPusher {

    private static final String TAG = HookLog.OTA + " WallPaper";

    /** 车机侧 socket_l 端口（与 OTA 推送同一条通道）。 */
    public static final int PORT = 10950;

    private static final int CMD_VERSION = 0x10000011;
    private static final int CMD_WP_START = 0x10000043;
    private static final int CMD_WP_DATA = 0x10000044;
    private static final int CMD_WP_END = 0x10000045;
    private static final int CMD_WP_VERIFY = 0x10000046;
    private static final int CMD_WP_VERIFY_ACK = 0x80000046;
    private static final int CMD_WP_START_ACK = 0x80000043;

    /** 数据帧 1MB。 */
    private static final int CHUNK = 1024 * 1024;

    /** {@code displayWallpaperSet} 默认值（固件只要求非空）。 */
    public static final String DEFAULT_DISPLAY_SET = "1";

    /** 壁纸图规格（官方包实测）。 */
    public static final String IMG_SMALL = "1.png";
    public static final String IMG_BIG = "2.png";
    public static final int SMALL_W = 307, SMALL_H = 460;
    public static final int BIG_W = 1024, BIG_H = 600;

    public interface Callback {
        void onLog(String line);

        void onProgress(int percent);

        void onDone(boolean ok, String message);
    }

    private WallpaperSocketPusher() {
    }

    /** 同时写 HookLog（logcat/文件）与界面回调，便于无头实测时看全链路。 */
    private static void log(Callback cb, String line) {
        HookLog.log(TAG + " " + line);
        if (cb != null) {
            cb.onLog(line);
        }
    }

    /**
     * 校验壁纸包：至少含 1.png / 2.png 其中之一。
     *
     * <p>官方流程允许<b>只推小图、只推大图、或两张都推</b>，所以单张也算合法包。</p>
     */
    public static String validate(File zip) {
        if (zip == null || !zip.isFile() || zip.length() == 0) {
            return "壁纸包不存在";
        }
        try (ZipFile z = new ZipFile(zip)) {
            boolean hasSmall = z.getEntry(IMG_SMALL) != null;
            boolean hasBig = z.getEntry(IMG_BIG) != null;
            if (!hasSmall && !hasBig) {
                return "壁纸包需含 " + IMG_SMALL + "(307×460) 或 " + IMG_BIG + "(1024×600)，当前条目："
                        + String.join(",", z.stream().map(ZipEntry::getName).limit(8).toArray(String[]::new));
            }
            return null;
        } catch (Exception e) {
            return "壁纸包读取失败：" + e;
        }
    }

    /** 描述包内图片尺寸（给界面看）。 */
    public static String describe(File zip) {
        try (ZipFile z = new ZipFile(zip)) {
            StringBuilder sb = new StringBuilder();
            for (String n : new String[]{IMG_SMALL, IMG_BIG}) {
                ZipEntry e = z.getEntry(n);
                if (e != null) {
                    sb.append(n).append(" ").append(pngSize(z, e)).append("  ");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String pngSize(ZipFile z, ZipEntry e) throws Exception {
        try (InputStream in = z.getInputStream(e)) {
            byte[] h = new byte[33];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            if (n < 24) {
                return "?";
            }
            int w = ((h[16] & 0xFF) << 24) | ((h[17] & 0xFF) << 16) | ((h[18] & 0xFF) << 8) | (h[19] & 0xFF);
            int hh = ((h[20] & 0xFF) << 24) | ((h[21] & 0xFF) << 16) | ((h[22] & 0xFF) << 8) | (h[23] & 0xFF);
            return w + "×" + hh;
        }
    }

    /**
     * 推送壁纸。
     *
     * @param num    wallpaperNum，1 或 2（选哪张图）
     * @param dws    displayWallpaperSet（需非空）
     * @param dryRun true = 只走握手/start，不发数据与 end（联调用）
     */
    public static void push(final File zip, final String num, final String dws,
                            final boolean dryRun, final Callback cb) {
        new Thread(() -> {
            Socket s = null;
            try {
                String err = validate(zip);
                if (err != null) {
                    HookLog.log(TAG + " 校验失败: " + err);
                    cb.onDone(false, err);
                    return;
                }
                String host = OtaSocketPusher.carHost();
                String md5 = md5(zip);
                String name = zip.getName();
                String n = (num == null || num.trim().isEmpty()) ? "1" : num.trim();
                String set = (dws == null || dws.trim().isEmpty()) ? DEFAULT_DISPLAY_SET : dws.trim();

                log(cb, String.format("=== 壁纸推送 ===%n  包   : %s%n  大小 : %d 字节%n  内容 : %s%n  MD5  : %s%n  num=%s  displayWallpaperSet=%s",
                        name, zip.length(), describe(zip), md5, n, set));

                s = new Socket();
                String local = OtaSocketPusher.localIpOnCarNet();
                if (local != null) {
                    s.bind(new InetSocketAddress(local, 0));
                }
                s.connect(new InetSocketAddress(host, PORT), 8000);
                s.setSoTimeout(15000);
                log(cb, "已连 " + host + ":" + PORT);

                // ① 版本协商（车机通常不回，宽松模式）
                send(s, CMD_VERSION, 1,
                        ("{\"versionCode\":1,\"SP_CONFIG_MODEL_GEMINI_PROJECT_CODE\":\"" + host + "\"}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Thread.sleep(400);
                drain(s, cb, 600);

                // ② start
                String start = "{\"fileName\":\"" + name + "\",\"wallpaperNum\":\"" + n
                        + "\",\"displayWallpaperSet\":\"" + set + "\",\"md5\":\"" + md5
                        + "\",\"fileSize\":" + zip.length() + "}";
                send(s, CMD_WP_START, 1, start.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                log(cb, "→ 0x10000043 start（num=" + n + " set=" + set + "）");

                drain(s, cb, 2000);

                if (dryRun) {
                    log(cb, "dry-run：握手完成，不发数据与 end");
                    HookLog.log(TAG + " dry-run 完成");
                    cb.onDone(true, "dry-run 完成（握手 OK，未推数据）");
                    return;
                }

                // ③ 数据帧
                byte[] buf = new byte[CHUNK];
                long sent = 0;
                int idx = 0;
                try (InputStream in = new BufferedInputStream(new FileInputStream(zip))) {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        byte[] payload = r == buf.length ? buf : java.util.Arrays.copyOf(buf, r);
                        send(s, CMD_WP_DATA, 0, payload);
                        sent += r;
                        idx++;
                        cb.onProgress((int) Math.min(99, sent * 100 / zip.length()));
                        if (idx % 8 == 0) {
                            log(cb, String.format("  %d%%  %d/%d B（%d 片）",
                                    sent * 100 / zip.length(), sent, zip.length(), idx));
                        }
                    }
                }
                cb.onProgress(100);
                log(cb, String.format("✔ 数据发完 %d B / %d 片", sent, idx));

                // ④ end（必须带 finished 标志）
                send(s, CMD_WP_END, 1, "{\"recvWallpaperFileFinishedFlag\":1,\"s_code\":0,\"code\":0}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                log(cb, "→ 0x10000045 end（带 recvWallpaperFileFinishedFlag）");

                // ⑤ 等车机校验请求并回 ack
                long deadline = System.currentTimeMillis() + 180000;
                boolean acked = false;
                while (System.currentTimeMillis() < deadline) {
                    Frame f = tryRead(s, 3000);
                    if (f == null) {
                        if (s.isClosed()) {
                            break;
                        }
                        continue;
                    }
                    log(cb, "← " + f);
                    if (f.cmd == CMD_WP_VERIFY) {
                        send(s, CMD_WP_VERIFY_ACK, 1, new byte[0]);
                        log(cb, "→ 0x80000046 已回校验 ACK");
                        acked = true;
                        break;
                    }
                }
                HookLog.log(TAG + " " + (acked ? "推送完成" : "未收到校验请求") + " ack=" + acked);
                cb.onDone(acked, acked
                        ? "壁纸已送达，车机校验请求已 ACK。看仪表屏幕确认壁纸是否变更（静默≠失败）"
                        : "未收到车机 0x10000046 校验请求（可能 md5 不符被拒 / 车机未进入壁纸接收态）");
            } catch (Exception e) {
                HookLog.log(TAG + " 推送异常: " + e);
                cb.onDone(false, "壁纸推送异常：" + e);
            } finally {
                close(s);
            }
        }, "wp-push").start();
    }


    // ==================== 由两张图打包成壁纸 zip ====================

    /**
     * 把两张裁切好的图打成壁纸包：{@code 1.png}（小图 307×460）+ {@code 2.png}（大图 1024×600）。
     *
     * @param small 1.png 的文件（307×460）
     * @param big   2.png 的文件（1024×600）
     * @param out   输出 zip
     * @return 输出文件
     */
    public static java.io.File pack(java.io.File small, java.io.File big, java.io.File out)
            throws Exception {
        // 官方支持只推一张：small/big 允许为 null，但至少要有一张
        boolean okSmall = small != null && small.isFile();
        boolean okBig = big != null && big.isFile();
        if (!okSmall && !okBig) {
            throw new IllegalArgumentException("小图（" + SMALL_W + "×" + SMALL_H + "）与大图（"
                    + BIG_W + "×" + BIG_H + "）至少要有一张");
        }
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out))) {
            if (okSmall) {
                writeEntry(zos, small, IMG_SMALL);
            }
            if (okBig) {
                writeEntry(zos, big, IMG_BIG);
            }
        }
        return out;
    }

    private static void writeEntry(java.util.zip.ZipOutputStream zos, java.io.File f, String name)
            throws Exception {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }

    // ==================== 帧 ====================

    /** socket_l 帧：10B 头 = {@code [00][channel][cmd BE 4B][len BE 4B]} + payload。 */
    static byte[] frame(int cmd, int channel, byte[] payload) {
        byte[] f = new byte[10 + payload.length];
        f[0] = 0;
        f[1] = (byte) channel;
        f[2] = (byte) (cmd >>> 24);
        f[3] = (byte) (cmd >>> 16);
        f[4] = (byte) (cmd >>> 8);
        f[5] = (byte) cmd;
        f[6] = (byte) (payload.length >>> 24);
        f[7] = (byte) (payload.length >>> 16);
        f[8] = (byte) (payload.length >>> 8);
        f[9] = (byte) payload.length;
        System.arraycopy(payload, 0, f, 10, payload.length);
        return f;
    }

    private static void send(Socket s, int cmd, int ch, byte[] payload) throws Exception {
        OutputStream o = s.getOutputStream();
        o.write(frame(cmd, ch, payload));
        o.flush();
    }

    static final class Frame {
        final int cmd;
        final byte[] payload;

        Frame(int c, byte[] p) {
            cmd = c;
            payload = p;
        }

        String text() {
            return payload == null ? "" : new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return String.format("cmd=0x%08X %s", cmd, text());
        }
    }

    private static Frame tryRead(Socket s, int timeoutMs) {
        try {
            s.setSoTimeout(timeoutMs);
            byte[] head = readFully(s.getInputStream(), 10);
            int len = ((head[6] & 0xFF) << 24) | ((head[7] & 0xFF) << 16)
                    | ((head[8] & 0xFF) << 8) | (head[9] & 0xFF);
            int cmd = ((head[2] & 0xFF) << 24) | ((head[3] & 0xFF) << 16)
                    | ((head[4] & 0xFF) << 8) | (head[5] & 0xFF);
            byte[] p = len > 0 ? readFully(s.getInputStream(), len) : new byte[0];
            return new Frame(cmd, p);
        } catch (Exception e) {
            return null;
        }
    }

    private static void drain(Socket s, Callback cb, int ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Frame f = tryRead(s, Math.max(200, (int) (end - System.currentTimeMillis())));
            if (f == null) {
                continue;
            }
            log(cb, "  ← " + f);
        }
    }

    private static byte[] readFully(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new java.io.EOFException("车机连接提前关闭");
            }
            off += r;
        }
        return b;
    }

    static String md5(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                d.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : d.digest()) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void close(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        HookLog.log(TAG + " done");
    }
}