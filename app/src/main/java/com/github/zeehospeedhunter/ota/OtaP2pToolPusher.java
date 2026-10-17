package com.github.zeehospeedhunter.ota;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * <b>10086 通道推送器</b>（{@code cfmoto_P2p_server} / p2pTool），
 * 用于 <b>OTA Update 隐藏模式</b>下写包。
 *
 * <h3>为什么需要这条通道</h3>
 * <p>10950（{@code socket_l}）的 {@code bind+listen} 只在车机
 * {@code OTAModule::initial()} 里创建（Ghidra 实证，见 {@code docs/55}），
 * 而 {@code ecModuleInit()}（投屏触发）只做 {@code QObject::connect}、<b>不启动 10950</b>。
 * 所以「投屏画面正常但 10950 一直 ECONNREFUSED」是固件层必然。</p>
 *
 * <p>10086 则由仪表 <b>Key OTA</b> 直接拉起（独立进程 {@code cfmoto_P2p_server}），
 * 在 OTA Update 模式下始终可用。第三方「极核仪表 OTA 工具」走的就是这条。</p>
 *
 * <h3>协议（对 {@code firmware/spsdk-root/bin/cfmoto_P2p_server} 反编译得出）</h3>
 * <pre>
 *   ① write("128094208")           十进制长度（C 字符串）
 *   ② write("GEMINI_PACK.BIN")     文件名（C 字符串）
 *   ③ write(&lt;文件体&gt;)              按 1MB 分块
 *   ④ write("OTA")                 3 字节命令 → VerifyChecksum
 * </pre>
 *
 * <p>★ <b>④ 之后车机不回包</b>（2026-10-17 22:47 实测）：发完 {@code OTA} 后车机
 * {@code VerifyChecksum} → {@code ioctl(0x4544)} 这条路径<b>全程零字节返回、连接也不关</b>
 * （观察 20s）。成功失败都如此。只有 {@code LOG} 才回传 {@code log_service.tar}，
 * 且仅"传输异常"时回 3 字节确认。
 * ⇒ <b>「车机没反应」不能判定成败</b>：包没收全、校验失败、ioctl 没触发，
 * 三种情况在手机侧长得一模一样。真要判定只能拉车机自己的日志（见 {@link #probeLog}）。
 * ⚠️ 也因此<b>不要往这条连接塞 10950 的 {@code 0x80000004} 二进制 ack</b> ——
 * 车机这里是 3 字节文本命令解析器，收到长度≠3 的报文会走错分支。</p>
 *
 * <p>★★ <b>① ② ③ 绝不能一次 write 连发</b>：车机把每次 {@code recv} 当一条独立报文，
 * 用 {@code atoll} 解析长度、{@code strlen} 解析文件名。若连成一串，
 * 路径会变成 {@code /media/flash/userdata/128094208GEMINI_PACK.BIN…} ⇒ <b>刷写必失败</b>。
 * 因此这里每步 {@code write} 后都 {@code flush} 并留间隔。</p>
 *
 * <p>注：该服务接收的是<b>裸 {@code GEMINI_PACK.BIN}（128MB）</b>，
 * 不是 10950 那种 ZIP（106MB）。调用方需先用
 * {@link OtaPackVerify#extractInnerPack(File)} 解出内层。</p>
 */
public final class OtaP2pToolPusher {

    /** 服务端口。 */
    public static final int PORT = CarNet.PORT_P2P_TOOL;

    /** 车机侧固定落盘名（服务自身拼路径 {@code /media/flash/userdata/} + 此名）。 */
    public static final String REMOTE_NAME = "GEMINI_PACK.BIN";

    /**
     * 每步 write 之间的间隔。
     *
     * <p>车机按「一次 recv = 一条报文」解析，必须让三段各自成为独立报文。
     * 200ms 在实测局域网里足够，且不影响 128MB 传输的总耗时。</p>
     */
    private static final int STEP_GAP_MS = 200;

    /**
     * ③ 文件体发完 → ④ {@code OTA} 之间的额外等待。
     *
     * <p>★ 必须足够长：车机收文件体的最后一次 {@code recv(1MB)} 可能把紧跟着的
     * {@code "OTA"} 一并读走并 {@code fwrite} 进文件，导致命令没被识别、永远不刷写。
     * 128MB 同步落盘通常 1~2s，这里给 3s 保守余量。</p>
     */
    private static final int FILE_DRAIN_WAIT_MS = 3000;

    /**
     * ② 文件名 → ③ 包体之间的间隔。
     *
     * <p>★★ 2026-10-17 22:55 <b>车机日志实证</b>：原来只隔 {@code STEP_GAP_MS=200}，
     * 结果车机把「文件名」和「包体」<b>合并进同一次 {@code recv}</b>，
     * 侧记下的 {@code recv fileName:} 是乱码（{@code \x15\x0b\x04\x0e\x7fZ}），
     * 于是 {@code fopen} 打开了错误的文件、传输卡在 89.6%、{@code OTA} 命令从未被识别。
     * ⇒ 200ms 不足以让车机的第二次 {@code recv} 返回，给足 2s。</p>
     */
    private static final int FILENAME_GAP_MS = 2000;

    /**
     * ④ 发出后被动观察车机反应的时长。
     *
     * <p>实测 10086 协议在此期间<b>零回包</b>，所以这只是留一个窗口
     * 捕捉「车机断开（= 开始刷写/重启）」或极小概率的回包，不是等 ack。</p>
     */
    private static final int OBSERVE_WAIT_MS = 15000;

    /**
     * {@code probeLog} 判定「车机已传完」的静默等待。
     *
     * <p>★ 车机发完 {@code log_service.tar} <b>不关连接</b>（和 {@code OTA} 一样 fire-and-forget），
     * 所以"读到 EOF"永远不会发生；必须靠"多久没新数据"来判结束。
     * 取 6s：实测 tar 是 1MB 级，几百毫秒就传完，6s 足够宽松又不至于让人等太久。</p>
     */
    private static final int LOG_TAIL_IDLE_MS = 6000;

    private static final int CHUNK = 1024 * 1024;

    private OtaP2pToolPusher() {
    }

    /** 进度与日志回调。 */
    public interface Callback {
        void onLog(String line);

        /** @param sent 已发送字节；@param total 总字节 */
        void onProgress(long sent, long total);
    }

    /**
     * <b>只读验证 + 取证</b>：发 3 字节 {@code LOG}，车机会把 {@code log_service.tar} 回传。
     *
     * <p>不写任何文件、不触发刷写，纯粹用来确认「10086 服务活着且协议对得上」。</p>
     *
     * <p>★★ <b>这不只是连通性探测，更是判定「刷写为何没发生」的唯一手段</b>：
     * 车机收到 {@code OTA} 后的 {@code VerifyChecksum} 失败时，只在<b>它自己的日志</b>里写
     * {@code No checksum found}，<b>手机侧一个字节都收不到</b>（实测：车机不回包、连接不关）。
     * 所以推完包必须拉一次日志，看服务侧记的 {@code totalFileSize} / {@code recv file size} /
     * {@code checksum} 三行，才能判断是「文件没收全」「校验没过」还是「ioctl 没触发」。</p>
     *
     * @return 收到的 tar 字节数；{@code <0} 表示失败
     */
    public static long probeLog(android.content.Context ctx, String host, Callback cb) {
        Socket s = new Socket();
        FileOutputStream tar = null;
        try {
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, PORT), 5000);
        // ★ 注意：这里设的 30000 只是「等首字节」的耐心；
        //   真正的「传完了」判定在下面改用 LOG_TAIL_IDLE_MS 静默超时。
        s.setSoTimeout(30000);
        cb.onLog("10086 已连接 → 发送 3 字节 LOG 做只读验证");
            OutputStream os = s.getOutputStream();
            os.write("LOG".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // 回传的 tar 落到应用私有目录，再用 extractLog 解析
            File dir = logOutDir(ctx);
            File out = new File(dir, "log_service.tar");
            tar = new FileOutputStream(out);
            InputStream in = s.getInputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            // ★ 车机发完 tar **不关连接**（和 OTA 一样是 fire-and-forget），
            //   所以不能用「读到 EOF」判结束，否则必然吃 SocketTimeoutException。
            //   这里改为：连续 LOG_TAIL_IDLE_MS 没新数据就视为传完（正常结束，不算失败）。
            s.setSoTimeout(LOG_TAIL_IDLE_MS);
            while (true) {
                int n;
                try {
                    n = in.read(buf);
                } catch (java.net.SocketTimeoutException e) {
                    cb.onLog("（" + LOG_TAIL_IDLE_MS / 1000 + "s 无新数据，视车机已传完 —— "
                            + "10086 不会主动关连接，这是正常结束，不是失败）");
                    break;
                }
                if (n < 0) {
                    break;                       // 车机关了连接
                }
                tar.write(buf, 0, n);
                total += n;
                if (cb != null && total % (1024 * 1024) < buf.length) {
                    cb.onLog("收到日志分片，累计 " + total + " 字节");
                }
                if (total > 64L * 1024 * 1024) {
                    break;                       // 安全上限
                }
            }
            tar.flush();
            cb.onLog("LOG 回传结束，共 " + total + " 字节");
            if (total > 0) {
                tar.close();
                tar = null;
                cb.onLog("已保存 → " + out.getAbsolutePath());
                extractLog(out, cb);
            } else {
                cb.onLog("✖ 一个字节都没收到 —— 确认 10086 是否 OPEN"
                        + "（P2P 已建组、仪表在 OTA Update 窗口）");
            }
            return total;
        } catch (Exception e) {
            cb.onLog("LOG 失败：" + e);
            return -1;
        } finally {
            if (tar != null) {
                try {
                    tar.close();
                } catch (Exception ignored) {
                }
            }
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 日志落盘目录：应用私有缓存（无需任何权限）。 */
    private static File logOutDir(android.content.Context ctx) throws Exception {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            base = ctx.getFilesDir();
        }
        File dir = new File(base, "p2plog");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("无法创建目录 " + dir);
        }
        return dir;
    }

    /**
     * 解开 {@code log_service.tar}，把<b>与刷写判定直接相关</b>的行打进日志。
     *
     * <p>★ 格式（2026-10-17 23:2x 实证，之前误判成"私有格式"）：
     * 就是 <b>26 字节文件头 + 标准 tar</b>。
     * 文件头 = {@code "LOG"} + 十进制数字 + {@code "log_service.tar"}。
     * 手机上验过 {@code tail -c +27 f.tar | tar tvf -} 能直接读出来。
     * ⇒ 所以先<b>跳过前缀</b>，后面就可以完全按标准 tar 走；
     * 跳多少不硬编码 26，而是扫描第一个 {@code ustar} 魔数再往前退 257（该魔数在 tar 头内偏移 257），
     * 这样即便前缀长度将来变了也能自己对上。</p>
     *
     * <p>只打这几类：{@code totalFileSize} / {@code recv file size}（文件是否收全）、
     * {@code recv fileName}（★ 文件名有没有乱码 —— 这是 ②③ 报文粘连的直接证据）、
     * {@code checksum} / {@code No checksum found}（校验结果）、
     * {@code ioctl} / {@code upgrade} / {@code flash}（刷写是否真的触发）。
     * 这样点一次按钮就能看出卡在哪一环。</p>
     */
    private static void extractLog(File tar, Callback cb) {
        // tar 标准格式：每 512 字节一个头，name 在 0..99，size 在 124..135（八进制），typeflag 在 156
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(tar, "r")) {
            long len = f.length();
            long start = findTarStart(f, len);      // ★ 跳过 "LOG<数字>log_service.tar" 前缀
            if (start < 0) {
                cb.onLog("  （没找到 tar 魔数，可能车机回的不是 log_service.tar）");
                return;
            }
            if (start > 0) {
                cb.onLog("  （车机回的包带 " + start + " 字节自定义前缀，已跳过）");
            }
            f.seek(start);
            int hits = 0;
            while (f.getFilePointer() + 512 <= len && hits < 400) {
                long pos = f.getFilePointer();
                byte[] hdr = new byte[512];
                f.readFully(hdr);
                if (hdr[0] == 0) {
                    break;                       // 空块 = tar 结束
                }
                // typeflag '5' = 目录，跳过
                String name = new String(hdr, 0, 100, StandardCharsets.ISO_8859_1)
                        .replace('\0', ' ').trim();
                long size;
                try {
                    size = parseTarOctal(hdr, 124, 12);
                } catch (Exception e) {
                    break;                       // 头字段不合法，别再往下猜
                }
                long dataPos = pos + 512;
                long skip = ((size + 511) / 512) * 512;      // 数据区也按 512 对齐
                if (dataPos + size > len) {
                    break;                       // 文件被截断
                }
                // 只扫 <2MB 的文本类文件，跳过二进制大文件
                if (size > 0 && size < 2 * 1024 * 1024 && looksTextual(name)) {
                    byte[] data = new byte[(int) size];
                    f.seek(dataPos);
                    f.readFully(data);
                    String content = new String(data, StandardCharsets.UTF_8);
                    for (String line : content.split("\n")) {
                        String t = line.trim();
                        if (t.isEmpty()) {
                            continue;
                        }
                        String lt = t.toLowerCase();
                        if (lt.contains("totalfilesize") || lt.contains("recv file size")
                                || lt.contains("recv filename") || lt.contains("checksum")
                                || lt.contains("ioctl") || lt.contains("upgrade")
                                || lt.contains("flash") || lt.contains("gemini_pack")
                                || lt.contains("cfcp") || lt.contains("p2ptool")) {
                            cb.onLog("  [" + shortName(name) + "] " + t);
                            hits++;
                            if (hits >= 400) {
                                break;
                            }
                        }
                    }
                    f.seek(dataPos + skip);
                } else {
                    f.seek(dataPos + skip);
                }
            }
            if (hits == 0) {
                cb.onLog("  （tar 内未命中 totalFileSize/recvFileName/checksum 等关键字 —— "
                        + "本次日志可能还没轮到写这些）");
            } else {
                cb.onLog("  —— 以上 " + hits + " 行是判定刷写卡点的关键 ——");
            }
        } catch (Exception e) {
            cb.onLog("解析 tar 失败：" + e + "（tar 仍在 " + tar.getAbsolutePath() + "）");
        }
    }

    /**
     * 找到标准 tar 的第一个记录头位置。
     *
     * <p>车机回的包前面可能有 {@code "LOG<数字>log_service.tar"} 前缀，长度不定。
     * tar 头内偏移 257 处是魔数 {@code ustar}，所以扫到它再往前退 257 就是标准头起点。</p>
     */
    private static long findTarStart(java.io.RandomAccessFile f, long len) throws Exception {
        long scan = Math.min(len, 4096);            // 前缀不会很长
        byte[] b = new byte[(int) scan];
        f.seek(0);
        int n = f.read(b);
        if (n <= 0) {
            return -1;
        }
        for (int i = 0; i + 5 <= n; i++) {
            if (b[i] == 'u' && b[i + 1] == 's' && b[i + 2] == 't'
                    && b[i + 3] == 'a' && b[i + 4] == 'r') {
                long head = i - 257L;
                if (head >= 0 && head + 512 <= len) {
                    return head;
                }
            }
        }
        return -1;
    }

    private static String shortName(String name) {
        int i = name.lastIndexOf('/');
        return i >= 0 ? name.substring(i + 1) : name;
    }

private static boolean looksTextual(String name) {
    String n = name.toLowerCase();
  return n.endsWith(".log") || n.endsWith(".txt") || n.endsWith(".cfg")
       || n.endsWith(".conf") || n.endsWith(".rc") || n.contains("log")
          || n.endsWith(".sh") || n.endsWith(".prop");
    }

    /** 解析 tar 头里的八进制数字字段（{@code long} 版，标准库无此方法）。 */
    private static long parseTarOctal(byte[] hdr, int off, int len) {
     long v = 0;
        for (int i = off; i < off + len; i++) {
    int c = hdr[i] & 0xFF;
            if (c == 0 || c == ' ') {
     break;
          }
            if (c < '0' || c > '7') {
            throw new NumberFormatException("非八进制: " + (char) c);
    }
     v = (v << 3) | (c - '0');          // <<3 = ×8
        }
        return v;
    }

    /**
     * 推送裸 {@code GEMINI_PACK.BIN}。
     *
     * @param host         车机组主地址（通常 {@code 192.168.0.1}）
     * @param pack         裸包（文件名必须是 {@code GEMINI_PACK.BIN}，长度按实际字节）
     * @param triggerFlash {@code true} 才在末尾发 {@code OTA} 真正触发刷写；
     *                     {@code false} 只把包写进车机 userdata（安全模式）
     */
    public static boolean push(String host, File pack, boolean triggerFlash, Callback cb) {
        Socket s = new Socket();
        try {
            long total = pack.length();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, PORT), 5000);
            s.setSoTimeout(15000);
            cb.onLog("10086 已连接：" + host + ":" + PORT);
            cb.onLog("以裸 GEMINI_PACK.BIN 推送 " + total + " 字节"
                    + (triggerFlash ? "（末尾会发 OTA，**将真正触发刷写**）"
                    : "（不发 OTA，只写 userdata，安全）"));

            OutputStream os = s.getOutputStream();
            if (triggerFlash && !recheckSix(pack, cb)) {
                return false;          // 六项没过 ⇒ 一个字节都不发
            }

            // ① 十进制长度
      os.write(String.valueOf(total).getBytes(StandardCharsets.ISO_8859_1));
 os.flush();
          cb.onLog("→ ① 长度报文：" + total);
       Thread.sleep(STEP_GAP_MS);

      // ② 文件名
    os.write(REMOTE_NAME.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
          cb.onLog("→ ② 文件名报文：" + REMOTE_NAME);
        // ★★★ 2026-10-17 22:55 车机日志实证：这里原来是 200ms，实测**车机把
   //   ②文件名 和 ③包体 合并进了同一次 recv**，
            //   车机侧 `recv fileName:` 记的是乱码 `\x15\x0b\x04\x0e\x7fZ`（不是 GEMINI_PACK.BIN），
            //   导致 fopen 写错文件、传输停在 114760856/128094208（89.6%）、OTA 从未被识别。
            //   ⇒ 必须留足让车机「第二次 recv」真正返回的时间。
            Thread.sleep(FILENAME_GAP_MS);

            // ③ 文件体
            long sent = 0;
            byte[] buf = new byte[CHUNK];
            try (InputStream in = new java.io.FileInputStream(pack)) {
                int n;
                long lastLog = 0;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    sent += n;
                    if (cb != null) {
                        cb.onProgress(sent, total);
                        long now = System.currentTimeMillis();
                        if (now - lastLog > 2000) {
                            lastLog = now;
                            cb.onLog("→ ③ 已发 " + sent + " / " + total
                                    + "（" + (sent * 100 / Math.max(1, total)) + "%）");
                        }
                    }
                }
            }
            os.flush();
            cb.onLog("→ ③ 文件体发送完成：" + sent + " 字节");
            if (sent != total) {
                cb.onLog("✖ 发送字节数与文件长度不符，放弃触发刷写");
                return false;
            }
            // ③ 发完后必须等车机把文件体读干净，再发 OTA。
            // ★ 车机侧收文件体的循环是 `recv(1MB) → fwrite`，靠「累计 ≥ totalFileSize」退出，
            //   最后一次 recv 可能把紧跟着的 "OTA" 一起读走并写进文件 ⇒ 文件多 3 字节、
            //   命令没被识别 ⇒ 永远不刷写。所以这里主动等一会，让车机先收完。
            //   等不到明确的结束信号就用固定长等待（车机是同步 fwrite 到 userdata，
            //   128MB 落盘通常在 1~2s 内完成）。
            cb.onLog("…等车机收完文件体（" + FILE_DRAIN_WAIT_MS + "ms）");
            Thread.sleep(FILE_DRAIN_WAIT_MS);

            // ④ 触发
            if (triggerFlash) {
                os.write("OTA".getBytes(StandardCharsets.ISO_8859_1));
                os.flush();
                cb.onLog("→ ④ 已发送 3 字节 OTA 命令 → 车机将 VerifyChecksum");
                // 车机 VerifyChecksum 失败只写自己的日志、不回包（实测：20s 内零字节、
                // 连接不关）。所以这里只做被动观察 + 全量原始日志；
                // 真正判定刷写为何没发生，要靠随后点「拉取车机日志(LOG)」。
                handleFlashAck(s, cb);
            } else {
                cb.onLog("（安全模式）未发送 OTA，包已写入车机 userdata/GEMINI_PACK.BIN");
            }
            return true;
        } catch (Exception e) {
            cb.onLog("推送失败：" + e);
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================ 刷写 ack 握手

    /** 车机 → 手机：校验请求。 */
    private static final int CMD_OTA_VERIFY = 0x10000004;
    /** 手机 → 车机：校验确认（控制帧，head1=1）。 */
    private static final int CMD_OTA_VERIFY_ACK = 0x80000004;
    /** ack 载荷，与 10950 路径 {@code OtaSocketPusher} 完全一致。 */
    private static final byte[] ACK_PAYLOAD =
            "{\"recvVerifyAckFlag\":1}".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * 发送 ④ OTA 之后的观察窗口。
     *
     * <p>★★ <b>2026-10-17 22:47 实测修正</b>：这一轮推完包、发完 OTA 之后，
     * 车机在<b>整整 20 秒内一个字节都没回，连接也一直没关</b>。
     * 结论：{@code cfmoto_P2p_server} 收 {@code OTA} 后的路径
     * （{@code VerifyChecksum} → {@code ioctl(0x4544)}）<b>本来就不回任何数据</b>，
     * 只有 {@code LOG} 才回传、且仅"传输异常"时回 3 字节。
     * ⇒ 「车机不回包」<b>不能</b>证明它失败了，也不能证明它在等 ack；
     * 它既不是 10950 那种等 {@code 0x80000004} 的握手，也不回校验请求帧。</p>
     *
     * <p>所以这里<b>不再盲发 ack</b>（往车机的 3 字节命令解析器里塞二进制帧只会添乱），
     * 只做被动观察：把收到的任何字节（文本或二进制帧）原样打出来。
     * 判定刷写为何没发生，去点「拉取车机日志(LOG）」看服务侧的
     * {@code totalFileSize} / {@code recv file size} / {@code checksum} / {@code ioctl}。</p>
     *
     * <p>仍然保留帧解析：万一某些固件版本确实会回 {@code 0x10000004}，
     * 识别到就回 ack，不浪费一次机会。</p>
     */
    private static void handleFlashAck(Socket s, Callback cb) {
        long deadline = System.currentTimeMillis() + OBSERVE_WAIT_MS;
        ByteArrayOutputStream rx = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int acks = 0;
        boolean gotAnything = false;
        cb.onLog("→ 观察车机反应（" + OBSERVE_WAIT_MS / 1000 + "s）。"
                + "★ 10086 这条正常情况不回包；刷写与否请随后点「拉取车机日志(LOG)」确认");
        try {
            s.setSoTimeout(500);
            InputStream in = s.getInputStream();
            while (System.currentTimeMillis() < deadline) {
                if (s.isClosed() || s.isInputShutdown()) {
                    cb.onLog("   ! 连接已断");
                    break;
                }
                int n;
                try {
                    n = in.read(buf);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (Exception e) {
                    cb.onLog("   ! 读异常：" + e);
                    break;
                }
                if (n < 0) {
                    cb.onLog("← 车机已断开连接（通常 = 已开始校验 / 刷写 / 重启）");
                    break;
                }
                gotAnything = true;
                rx.write(buf, 0, n);
                // 从 rx 里抽完整的 10 字节头帧（万一是二进制协议版本）
                byte[] all = rx.toByteArray();
                int off = 0;
                while (all.length - off >= 10) {
                    int cmd = ((all[off + 2] & 0xFF) << 24) | ((all[off + 3] & 0xFF) << 16)
                            | ((all[off + 4] & 0xFF) << 8) | (all[off + 5] & 0xFF);
                    int len = ((all[off + 6] & 0xFF) << 24) | ((all[off + 7] & 0xFF) << 16)
                            | ((all[off + 8] & 0xFF) << 8) | (all[off + 9] & 0xFF);
                    if (len < 0 || all.length - off - 10 < len) {
                        break;                  // 半帧，留到下次
                    }
                    byte[] p = Arrays.copyOfRange(all, off + 10, off + 10 + len);
                    cb.onLog(String.format("   ← 收到帧 cmd=0x%08X len=%d  %s",
                            cmd, len, pretty(p)));
                    if (cmd == CMD_OTA_VERIFY) {
                        acks++;
                        sendAck(s, cb);
                    }
                    off += 10 + len;
                }
                if (off > 0) {
                    byte[] rest = Arrays.copyOfRange(all, off, all.length);
                    rx.reset();
                    rx.write(rest, 0, rest.length);
                }
            }
        } catch (Exception e) {
            cb.onLog("   ! 观察异常：" + e);
        }
        if (!gotAnything) {
            cb.onLog("   ⚠ 车机 " + OBSERVE_WAIT_MS / 1000 + "s 内零字节、连接未断。"
                    + "这在 10086 上属正常（该协议不回包），但也意味着"
                    + "「包是否收全 / 校验是否通过 / ioctl 是否触发」手机侧一律看不到。");
            cb.onLog("   ⇒ 下一步：点「拉取车机日志(LOG）」，看服务侧 totalFileSize / recv file size / checksum 三行。");
        } else {
            cb.onLog("   （收到校验请求 " + acks + " 次）");
        }
    }

    /** 发 {@code 0x80000004} ack 帧（二进制 10 字节头 + payload）。 */
    private static void sendAck(Socket s, Callback cb) throws Exception {
        byte[] payload = ACK_PAYLOAD;
        byte[] f = new byte[10 + payload.length];
        f[0] = 0;
        f[1] = 1;                                // 控制帧 head1=1
        int cmd = CMD_OTA_VERIFY_ACK;
        f[2] = (byte) (cmd >>> 24);
        f[3] = (byte) (cmd >>> 16);
        f[4] = (byte) (cmd >>> 8);
        f[5] = (byte) cmd;
        f[6] = (byte) (payload.length >>> 24);
        f[7] = (byte) (payload.length >>> 16);
        f[8] = (byte) (payload.length >>> 8);
        f[9] = (byte) payload.length;
        System.arraycopy(payload, 0, f, 10, payload.length);
        s.getOutputStream().write(f);
        s.getOutputStream().flush();
        cb.onLog("   → ack 已发  cmd=0x80000004  payload=" + new String(payload, StandardCharsets.ISO_8859_1));
    }

    /** 把 payload 渲染成可读串：JSON 原样；否则可打印字符 + 其余用 '.'。 */
    private static String pretty(byte[] p) {
        if (p == null || p.length == 0) {
            return "";
        }
        if (p[0] == '{') {
            return new String(p, StandardCharsets.ISO_8859_1);
        }
        StringBuilder sb = new StringBuilder("[");
        for (byte b : p) {
            if (b >= 0x20 && b < 0x7E) {
                sb.append((char) b);
            } else {
                sb.append('.');
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * <b>触发刷写前的最后一道闸</b>：在推包线程内部重新跑一遍六项检查。
     *
     * <p>★ 为什么要在这里<b>再验一次</b>（UI 已经验过还会再验）：
     * UI 的 {@code pickedInfo} 是选包那一刻的结果，中间可能隔了很久、文件也可能被换掉。
     * 而 <b>10086 这条通道是 fire-and-forget</b> —— 车机不回包、手机看不到失败，
     * 一旦把不自洽的包写进去就是「只擦不写」的半刷砖。
     * 为刷自制固件铺路，这道闸必须放在真正要触发刷写的那一刻。</p>
     *
     * @return {@code true} 放行
     */
    private static boolean recheckSix(File pack, Callback cb) {
        cb.onLog("★ 触发刷写前重验六项检查（自制固件唯一的护栏）…");
        try {
            OtaPackVerify.Result r = OtaPackVerify.verifyPack(pack, line -> cb.onLog("    " + line));
            if (r == null) {
                cb.onLog("✖ 自检没跑出结果，为安全起见不推");
                return false;
            }
            cb.onLog(r.summary());
            if (!r.allOk()) {
                cb.onLog("✖✖ 六项检查未通过 —— 已放弃本次刷写，一个字节都没发。");
                cb.onLog("   不自洽的包刷进车机 = 只擦不写 = 变砖（10/06 教训）。");
                return false;
            }
            cb.onLog("✓ 六项检查通过，放行");
            return true;
        } catch (Throwable t) {
            cb.onLog("✖ 自检异常：" + t + " —— 为安全起见不推");
            return false;
        }
    }

    /** 把解出的内层包复制到指定目录并统一命名为 {@code GEMINI_PACK.BIN}。 */
    public static File normalizeName(File src, File dir) throws Exception {
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File dst = new File(dir, REMOTE_NAME);
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[CHUNK];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
        }
        return dst;
    }
}
