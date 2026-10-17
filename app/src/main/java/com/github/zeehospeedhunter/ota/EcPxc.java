package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookLog;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>EasyConnect（Carbit ECSDK）PXC 协议客户端 + 「免投屏」OTA 推送编排。</b>
 *
 * <h3>协议事实来源</h3>
 * <ul>
 *   <li><b>帧格式</b>（{@code firmware/spsdk-root/lib/libECSDK.so} Ghidra 反编译；
 *     {@code PXCService::sendCMD} @0x107b30 / {@code recvCMD} @0x1076ec）：
 *     <pre>
 *     [0:4]   cmdType   (uint32 LE)
 *     [4:8]   totalLen  = 16 + payloadLen (uint32 LE)
 *     [8:12]  cmdType ^ totalLen (uint32 LE)
 *     [12:16] reserved (0)
 *     [16:]   payload
 *     </pre>
 *     错误帧 {@code 0x80000020}（ECP_PXC_CMD_ERR）。<b>2026-10-17 真车双向验证通过。</b></li>
 *   <li><b>10930 = 车机探测口</b>（{@code WifiManager::openWifiService} @0x1357bd）：
 *     每连接只读一帧、payload 须为含 {@code "phoneType"} 的 JSON，
 *     回 {@code 0x70000011}+{@code {"status":bool}} 后<b>关闭</b>。</li>
 *   <li><b>★ 回连端口（2026-10-17 官方投屏抓包实证）</b>：投屏会话期间
 *     <b>车机（192.168.0.1）用临时端口主动连手机的 10920 / 10921 / 10922</b>，
 *     三条均为 ESTABLISHED 长连接（车机是 EC 客户端，手机是服务端）。
 *     ⇒ 模块必须监听这三个端口，车机才连得进来。</li>
 *   <li><b>推包命令</b>（{@code P2CService::handleCMD} 派发表 @0x1051c5，81 条）：
 *     {@code 0x20360}（OTA 文件，回 {@code 0x20361}）、{@code 0x20290}（结果上报，回 {@code 0x20291}）。
 *     payload 布局沿用 SDK 的 {@code C2PService::sendOTAFile} @0xf440c：
 *     {@code [uint32 nameLen][name][data]}。</li>
 *   <li><b>握手</b>（{@code P2CService::start} @0x1065b5 / {@code C2PService::sendClientInfo} @0xf75a9）：
 *     车机先发 {@code 0x20000}（connectType）等 {@code 0x20001}，再发 {@code 0x10010}（clientInfo）等 {@code 0x10011}。</li>
 * </ul>
 *
 * <h3>为什么这是「免投屏」的路</h3>
 * <p>socket_l 10950 需要官方 App 投屏把 EC 会话建起来；而 EC SDK 自带一条
 * <b>P2C OTA 推送</b>通道（0x20360），跑在车机回连手机的 PXC 长连接上，与 10950 无关。
 * 第三方「极核仪表 OTA 工具」正是走这条路。</p>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>只连车机网段候选主机。</li>
 *   <li>每一帧收发都打 hex + 文本日志，实车一次会话即可校准。</li>
 *   <li>{@link #DRY_RUN} 为真时只建会话不推包，避免联调时把不完整包刷进仪表。</li>
 * </ul>
 */
public final class EcPxc {

    private EcPxc() {
    }

    private static final String TAG = HookLog.OTA + " EcPxc";

    // ==================== 帧格式常量 ====================

    public static final int HEAD_LEN = 16;

    /** 车机错误帧（ECP_PXC_CMD_ERR）。 */
    public static final int CMD_ERR = 0x80000020;

    /** 探测请求（车机侧不校验 cmdType，取与回复配对的 0x70000010）。 */
    public static final int CMD_PHONE_TYPE_REQ = 0x70000010;
    /** 探测回复（sendPhoneRly 实证）：payload = {"status":true/false}。 */
    public static final int CMD_PHONE_TYPE_RLY = 0x70000011;

    /**
     * ★ 车机回连后的首帧（2026-10-17 真车实测：车机连上 10922 立刻发 cmd=0x10000，
     * 16 字节空载荷；不应答它会每 2.2s 重连一次）。应答 0x10001。
     */
    public static final int CMD_CONNECT_TYPE = 0x10000;
    public static final int CMD_CONNECT_TYPE_RLY = 0x10001;

    /** 静态代码里 P2CService::start 那一跳（真车没在这一步出现，保留兜底）。 */
    public static final int CMD_CONNECT_TYPE_ALT = 0x20000;
    public static final int CMD_CONNECT_TYPE_ALT_RLY = 0x20001;
    /** clientInfo（车机→手机）。 */
    public static final int CMD_CLIENT_INFO = 0x10010;
    public static final int CMD_CLIENT_INFO_RLY = 0x10011;
    /** 心跳（派发表 0x70000000；回复取配对的 0x70000001）。 */
    public static final int CMD_HEARTBEAT = 0x70000000;
    public static final int CMD_HEARTBEAT_RLY = 0x70000001;

    /** OTA 文件下发（手机→车机），回 0x20361。 */
    public static final int CMD_OTA_DOWNLOAD = 0x20360;
    public static final int CMD_OTA_DOWNLOAD_RLY = 0x20361;
    /** OTA 结果上报（手机→车机），回 0x20291。 */
    public static final int CMD_OTA_RESULT = 0x20290;
    public static final int CMD_OTA_RESULT_RLY = 0x20291;

    /**
     * 本机监听端口（等车机回连）。
     *
     * <p>★ 前三个是 <b>2026-10-17 官方投屏抓包实测</b>：车机连手机的 10920/10921/10922
     * （三条 ESTABLISHED 长连接）。之前监听 10930-10935 全不命中，车机回连被 RST，
     * 表现就是「车机未回连」。</p>
     */
    public static final int[] LISTEN_PORTS = {
            10920, 10921, 10922,                                    // ★ 实测命中
            10923, 10924, 10925, 10930, 10931, 10932, 10933,         // SDK 端口族兜底
            8090, 20500, 20501,
    };

    /** 单帧 payload 上限。<b>不是包大小上限</b>，是「一帧装多少」——
     * 车机 clientInfo 里 {@code socketTimeoutPeriodWifi:9}（9 秒读超时），
     * 106MB 单帧传几分钟必被车机超时断开（实测 Broken pipe），
     * 所以必须分片，每片远小于 9 秒能传完的量。1MB 在 P2P 上约 1~3 秒，安全。
     */
    public static final int CHUNK_SIZE = 1024 * 1024;

    /**
     * 流水线深度：最多几片同时在途（未 ack）。
     * 4 片 = 4MB，够喂饱车机（实测它有积压时 450ms 连 ack 3 片），
     * 又不至于把车机事件队列撑爆（每片一个事件、各挂 1MB ABuffer）。
     */
    public static final int PIPELINE_DEPTH = 4;

    /**
     * dry-run：只建会话、握手、观测，<b>不发 0x20360、不上报 0x20290</b>，
     * 避免车机真回连时把（联调用的）包推过去触发升级。
     */
    public static volatile boolean DRY_RUN = false;

    /** dry-run 时握手后继续观测车机来帧的时长（默认 30s，可用 {@code --el hold} 调整）。 */
    public static volatile long DRY_HOLD_MS = 30000;

    /** 车机上报链路速率（真车实测：clientInfo 之后必发；不回它会每 2.2s 重连一次）。 */
    public static final int CMD_SPEED_REPORT = 0x10690;
    public static final int CMD_SPEED_REPORT_RLY = 0x10691;

    /**
     * 未知帧自动按「cmdType+1」回一个空帧 —— 仅用于协议联调探测。
     * 真推时保持 false（乱应答可能污染车机状态机）。
     */
    public static volatile boolean AUTO_ACK_UNKNOWN = false;

    // ==================== 对外类型 ====================

    public interface LineSink {
        void onLine(String line);
    }

    public interface DoneCallback {
        void onDone(boolean ok, String summary);
    }

    /** 推送进度（字节级）。 */
    public interface Progress {
        void onProgress(long sent, long total);
    }

    /** 一次探测的结果。 */
    public static final class ProbeResult {
        public boolean tcpOk;
        public boolean replied;
        public boolean statusOk;
        public String replyCmd;
        public String replyText;
        public String error;

        public boolean ok() {
            return tcpOk && replied && statusOk;
        }

        public String summary() {
            if (!tcpOk) {
                return "TCP 不通：" + error;
            }
            if (!replied) {
                return "TCP 通但无回包";
            }
            return statusOk
                    ? "EC 探测 OK（" + replyCmd + " " + replyText + "）"
                    : "EC 探测回包异常（" + replyCmd + " " + replyText + "）";
        }
    }

    // ==================== 帧编解码 ====================

    /** 组一帧：16B 头 + payload。 */
    public static byte[] frame(int cmdType, byte[] payload) {
        int len = HEAD_LEN + (payload == null ? 0 : payload.length);
        ByteBuffer bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmdType);
        bb.putInt(len);
        bb.putInt(cmdType ^ len);
        bb.putInt(0);
        if (payload != null && payload.length > 0) {
            bb.put(payload);
        }
        return bb.array();
    }

    public static byte[] frame(int cmdType, String payload) {
        return frame(cmdType, payload == null ? null : payload.getBytes(StandardCharsets.UTF_8));
    }

    /** OTA 文件帧 payload：[uint32 nameLen][name][data]。 */
    public static byte[] otaFilePayload(String name, byte[] data) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + nb.length + data.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(nb.length);
        bb.put(nb);
        bb.put(data);
        return bb.array();
    }

    public static final class Frame {
        public int cmdType;
        public int totalLen;
        public byte[] payload;
    }

    /** 读一整帧；流结束/超时/非法返回 null。 */
    public static Frame readFrame(InputStream in, int soTimeoutMs) {
        try {
            DataInputStream dis = new DataInputStream(in);
            byte[] head = new byte[HEAD_LEN];
            dis.readFully(head);
            ByteBuffer bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            Frame f = new Frame();
            f.cmdType = bb.getInt();
            f.totalLen = bb.getInt();
            int xor = bb.getInt();
            bb.getInt();
            if ((f.cmdType ^ f.totalLen) != xor) {
                HookLog.log(TAG + " 帧头校验不符: cmd=0x" + Integer.toHexString(f.cmdType)
                        + " len=" + f.totalLen + " xor=0x" + Integer.toHexString(xor));
                return null;
            }
            int plen = f.totalLen - HEAD_LEN;
            if (plen < 0 || plen > 512 * 1024 * 1024) {
                HookLog.log(TAG + " 帧长非法: totalLen=" + f.totalLen);
                return null;
            }
            f.payload = plen > 0 ? new byte[plen] : new byte[0];
            if (plen > 0) {
                dis.readFully(f.payload);
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    public static String describe(Frame f) {
        if (f == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("cmd=0x").append(Integer.toHexString(f.cmdType))
                .append(" len=").append(f.totalLen);
        if (f.payload != null && f.payload.length > 0) {
            String txt = printable(f.payload, 512);
            if (txt != null) {
                sb.append(" data=\"").append(txt).append('"');
            } else {
                sb.append(" data=<").append(f.payload.length).append("B 二进制>");
            }
        }
        return sb.toString();
    }

    public static String printable(byte[] b, int max) {
        int n = Math.min(b.length, max);
        StringBuilder sb = new StringBuilder();
        int printableCount = 0;
        for (int i = 0; i < n; i++) {
            char c = (char) (b[i] & 0xFF);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
                printableCount++;
            } else if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
                printableCount++;
            } else {
                sb.append('.');
            }
        }
        if (printableCount * 2 < n) {
            return null;
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ==================== 探测（车机 10930） ====================

    /**
     * 探测握手：发 {@value #CMD_PHONE_TYPE_REQ}+{@code {"phoneType":"Android"}}，
     * 等 {@value #CMD_PHONE_TYPE_RLY}+{@code {"status":true}}。
     * 这一帧同时把本机 IP 注册进车机（{@code DeviceManager::addWifiDevice}）。
     */
    public static ProbeResult probe(String host, int port, int timeoutMs, LineSink log) {
        ProbeResult r = new ProbeResult();
        Socket s = null;
        try {
            s = new Socket();
            String src = CarNet.localIpForHost(host);
            if (src != null) {
                s.bind(new InetSocketAddress(src, 0));
            }
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            r.tcpOk = true;
            log("探测 " + host + ":" + port + " TCP 通，发 phoneType …", log);

            JSONObject jo = new JSONObject();
            jo.put("phoneType", "Android");
            s.getOutputStream().write(frame(CMD_PHONE_TYPE_REQ, jo.toString()));
            s.getOutputStream().flush();

            s.setSoTimeout(timeoutMs);
            Frame f = readFrame(s.getInputStream(), timeoutMs);
            if (f == null) {
                r.error = "无回包/流结束";
                log("  探测：无回包", log);
                return r;
            }
            r.replied = true;
            r.replyCmd = "0x" + Integer.toHexString(f.cmdType);
            r.replyText = printable(f.payload, 256);
            log("  探测回包 " + describe(f), log);
            if (f.cmdType == CMD_PHONE_TYPE_RLY && f.payload != null) {
                try {
                    JSONObject back = new JSONObject(new String(f.payload, StandardCharsets.UTF_8));
                    r.statusOk = back.optBoolean("status", false);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            r.error = String.valueOf(e);
            log("  探测失败 " + e, log);
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    // ==================== 推送编排 ====================

    /**
     * 免投屏推送主流程：
     * <ol>
     *   <li>候选端口（含实测的 10920/10921/10922）全部监听，等车机回连；</li>
     *   <li>探测车机 10930（注册本机 + 触发车机回连）；</li>
     *   <li>拿到车机连接后做握手（0x20000/0x10010 应答）；</li>
     *   <li>推包 0x20360（分片）→ 等 0x20361；上报 0x20290 → 等 0x20291。</li>
     * </ol>
     *
     * @param waitMs 等车机回连的时长
     */
    public static void push(final String carHost, final File pkg, final long waitMs,
                            final LineSink log, final Progress progress, final DoneCallback done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                pushInternal(carHost, pkg, waitMs, log, progress, done);
            }
        }, "ecpxc-push").start();
    }

    private static void pushInternal(String carHost, File pkg, long waitMs,
                                     LineSink log, Progress progress, DoneCallback done) {
        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<ServerSocket> servers = new ArrayList<>();
        final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        final List<Session> sessions = Collections.synchronizedList(new ArrayList<Session>());
        final AtomicInteger connCount = new AtomicInteger(0);

        boolean ok = false;
        String summary = "";
        try {
            if (pkg == null || !pkg.isFile()) {
                if (!DRY_RUN) {
                    done.onDone(false, "包不存在 —— 先用界面「选择文件」（SAF）选升级包");
                    return;
                }
                log("dry-run：未选包，只联调握手与协议观测（不推任何数据）", log);
            } else {
                log("=== EC PXC 免投屏推送 " + pkg.getName() + " (" + pkg.length() + "B) ===", log);
            }
            log("dry-run = " + DRY_RUN + (DRY_RUN ? "（只联调，不推包）" : "（真推）"), log);

            // ① 候选端口全监听（车机会连 10920/10921/10922，实测）
            final LineSink sink = log;
            for (final int port : LISTEN_PORTS) {
                try {
                    final ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(port));
                    servers.add(ss);
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ss.setSoTimeout(2000);
                                while (!stop.get()) {
                                    Socket c;
                                    try {
                                        c = ss.accept();
                                    } catch (Exception e) {
                                        continue; // 超时轮询 stop
                                    }
                                    int idx = connCount.incrementAndGet();
                                    log("★ 车机回连 #" + idx + " 本机:" + port
                                            + "（对端 " + c.getRemoteSocketAddress() + "）", sink);
                                    sockets.add(c);
                                    Session s = new Session(c, port, idx, sink, onHandshakeDone());
                                    sessions.add(s);
                                    new Thread(s, "ecpxc-sess-" + port + "-" + idx).start();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }, "ecpxc-accept-" + port);
                    t.start();
                } catch (Exception e) {
                    log("  监听 :" + port + " 失败（" + e + "）", log);
                }
            }
            log("EC 服务端已监听 " + servers.size() + " 个候选端口：" + Arrays.toString(LISTEN_PORTS), log);

            // ② 探测车机（注册本机 + 触发回连）
            ProbeResult pr = probe(carHost, CarNet.PORT_EC, 3000, log);
            log("探测结果：" + pr.summary(), log);
            if (!pr.tcpOk) {
                summary = "车机 10930 不通（EC 服务没起？先连车机网络）";
                done.onDone(false, summary);
                return;
            }

            // ③ 等会话就绪：优先拿到 P2C 命令通道（车机发 0x20000 的那条），
            //    它才是手机→车机命令（含 0x20360 推包）该走的通道；
            //    C2P 通道（0x10000/0x10010 握手 + 心跳）只说明链路活着。
            log("等车机回连并完成握手（最多 " + (waitMs / 1000) + "s）……", log);
            long deadline = System.currentTimeMillis() + waitMs;
            Session control = null;
            while (control == null && System.currentTimeMillis() < deadline) {
                synchronized (sessions) {
                    for (Session s : sessions) {
                        if (s.p2cReady) {          // 拿到 0x20000 → P2C 命令通道
                            control = s;
                            break;
                        }
                    }
                    if (control == null) {
                        for (Session s : sessions) {
                            if (s.handshakeDone) {  // 兜底：C2P 通道
                                control = s;
                                break;
                            }
                        }
                    }
                }
                if (control == null) {
                    Thread.sleep(200);
                }
            }
            if (control == null) {
                int n = connCount.get();
                summary = "车机未回连或未完成握手（收到连接 " + n + " 条）。"
                        + "确认：① 手机与车机同网段（P2P/AP）② 仪表处于 OTA Update 窗口"
                        + " ③ 官方 App 没占着 10920/10921/10922（投屏中会独占）";
                log("✖ " + summary, log);
                done.onDone(false, summary);
                return;
            }
            log("✔ 控制会话 = 连接 #" + control.index + "（本机 :" + control.localPort
                    + (control.p2cReady ? "，P2C 命令通道" : "，C2P 通道（未见 P2C，兜底）") + "）", log);

            // ④ 推包 / dry-run
            if (DRY_RUN) {
                log("dry-run：握手已完成，链路 OK；不推包、不上报。"
                        + "继续观测车机来帧 " + (DRY_HOLD_MS / 1000) + "s", log);
                control.holdAndLog(DRY_HOLD_MS);
                summary = "dry-run 完成：车机回连 + 握手成功（本机 :" + control.localPort
                        + "）。真推前用界面「选择文件」（SAF）选包，再点「开始推送」";
                ok = true;
            } else {
                ok = control.runPush(pkg, progress);
                summary = control.summary;
            }
        } catch (Exception e) {
            summary = "推送异常：" + e;
            log("✖ " + summary, log);
        } finally {
            stop.set(true);
            for (ServerSocket ss : servers) {
                try {
                    ss.close();
                } catch (Exception ignored) {
                }
            }
            synchronized (sockets) {
                for (Socket s : sockets) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            done.onDone(ok, summary.isEmpty() ? (ok ? "完成" : "失败") : summary);
        }
    }

    /** 握手完成的回调（目前仅记日志）。 */
    private static Session.HandshakeListener onHandshakeDone() {
        return new Session.HandshakeListener() {
            @Override
            public void onHandshake(Session s) {
                HookLog.log(TAG + " 会话 #" + s.index + "（:" + s.localPort + "）握手完成");
            }
        };
    }

    // ==================== 会话 ====================

    /** 一条车机回连上的会话：握手应答 +（可选）推包。 */
    private static final class Session implements Runnable {
        interface HandshakeListener {
            void onHandshake(Session s);
        }

        private final Socket sock;
        private final int localPort;
        private final int index;
        private final LineSink log;
        private final HandshakeListener hsListener;
        private final OutputStream os;
        private final InputStream is;

        volatile boolean handshakeDone;
        /** P2C 命令通道就绪（收到车机 0x20000 并已应答）——推包走这条。 */
        volatile boolean p2cReady;
        /** 已收到的 0x20361 个数（JSON 帧 + 每个包体片各一个）。 */
        volatile int otaAcks;
        /** 是否收到 0x20291（结果上报回执）。 */
        volatile boolean resultAcked;
        String summary = "";
        private volatile int pendingRly;

        Session(Socket sock, int localPort, int index, LineSink log, HandshakeListener l)
                throws Exception {
            this.sock = sock;
            this.localPort = localPort;
            this.index = index;
            this.log = log;
            this.hsListener = l;
            this.os = sock.getOutputStream();
            this.is = sock.getInputStream();
            sock.setSoTimeout(2000);
        }

        @Override
        public void run() {
            try {
                while (!sock.isClosed()) {
                    Frame f = readFrame(is, 2000);
                    if (f == null) {
                        if (sock.isClosed()) {
                            break;
                        }
                        continue; // 读超时，继续等
                    }
                    log("←#" + index + " 车机 " + describe(f), log);
                    if (!handle(f)) {
                        break;
                    }
                }
            } catch (Exception e) {
                log("  会话 #" + index + " 结束：" + e, log);
            }
        }

        /** 处理车机来帧；false = 终止。 */
        private boolean handle(Frame f) {
            switch (f.cmdType) {
                case CMD_CONNECT_TYPE:
                    log("→#" + index + " 手机 0x" + Integer.toHexString(CMD_CONNECT_TYPE_RLY)
                            + "（connectType 应答）", log);
                    write(CMD_CONNECT_TYPE_RLY, null);
                    return true;
                case CMD_CONNECT_TYPE_ALT:
                    log("→#" + index + " 手机 0x" + Integer.toHexString(CMD_CONNECT_TYPE_ALT_RLY)
                            + "（connectType 应答，此通道为 P2C 命令通道）", log);
                    write(CMD_CONNECT_TYPE_ALT_RLY, null);
                    p2cReady = true;
                    return true;
                case CMD_CLIENT_INFO:
                    // 车机 clientInfo 全量落盘（协议文档用；界面日志只显示前 512 字）
                    if (f.payload != null && f.payload.length > 0) {
                        HookLog.log(TAG + " ★ 车机 clientInfo 全量 JSON: "
                                + new String(f.payload, StandardCharsets.UTF_8));
                    }
                    log("→#" + index + " 手机 0x" + Integer.toHexString(CMD_CLIENT_INFO_RLY)
                            + "（clientInfo 应答）", log);
                    write(CMD_CLIENT_INFO_RLY, clientInfoRly());
                    handshakeDone = true;
                    if (hsListener != null) {
                        hsListener.onHandshake(this);
                    }
                    return true;
                case CMD_HEARTBEAT:
                    write(CMD_HEARTBEAT_RLY, null);
                    return true;
                case CMD_OTA_DOWNLOAD_RLY:
                case CMD_OTA_RESULT_RLY: {
                    if (f.cmdType == CMD_OTA_DOWNLOAD_RLY) {
                        otaAcks++;
                    } else {
                        resultAcked = true;
                    }
                    pendingRly = 0;
                    return true;
                }
                case CMD_ERR:
                    summary = "车机报错误帧 0x80000020（可能：未授权 / 命令不被支持 / 需要加密）";
                    log("✖ " + summary + " " + describe(f), log);
                    return false;
                case CMD_SPEED_REPORT:
                    log("→#" + index + " 手机 0x" + Integer.toHexString(CMD_SPEED_REPORT_RLY)
                            + "（链路速率应答）", log);
                    write(CMD_SPEED_REPORT_RLY, speedReportRly());
                    return true;
                default:
                    log("  #" + index + " 未处理的车机帧" + (AUTO_ACK_UNKNOWN ? "（自动回+1）" : ""), log);
                    if (AUTO_ACK_UNKNOWN) {
                        write(f.cmdType + 1, null);
                    }
                    return true;
            }
        }

        /** 链路速率应答（回我方速率，单位未知，先回 0 表示不限制）。 */
        private String speedReportRly() {
            try {
                JSONObject o = new JSONObject();
                o.put("usbSpeed", 0);
                o.put("wifiSpeed", 0);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** dry-run 用：继续读车机帧一会儿，把观测做足。 */
        void holdAndLog(long ms) {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end && !sock.isClosed()) {
                Frame f = readFrame(is, 1500);
                if (f == null) {
                    continue;
                }
                log("←#" + index + " 车机 " + describe(f), log);
                if (!handle(f)) {
                    break;
                }
            }
        }

        /**
         * ⚠️⚠️ 当前实现只完成「文件投递」，<b>不能触发车机升级</b>（2026-10-17 静态+实测结论）。
         *
         * <p>车机侧 OTA 管理器对这两个命令的消费者期望的都是<b>软件列表 JSON</b>，不是文件字节：
         * <ul>
         *   <li>{@code OTAManager::addOTAPhoneDownloadResult}（0x20360 的消费者）解析
         *       {@code {"cnt":N,"data":[{sid,vc,vcDesc,vcTitle,vcDetail,isFullDist,name,icon,
         *       size,package,modifyTime,createTime}],"remote":{…},"code":0}}；</li>
         *   <li>{@code OTAManager::addOTAResult}（0x20290 的消费者）同一套 schema。</li>
         * </ul>
         * 我们发裸 zip 字节 ⇒ 协议层照常 ack（0x20361/0x20291，所以小包测试"全绿"），
         * 但 JSON 解析失败 ⇒ 车机什么都不做。<b>不要用当前版本期待刷机。</b></p>
         *
         * <p>★ 正确的 EC OTA 全流程（尚未实现）：
         * <ol>
         *   <li>手机发<b>软件列表 JSON</b>（0x20290 addOTAResult）→ 仪表 OTA 界面列出更新；</li>
         *   <li>用户在仪表上确认（或车机自动）→ {@code OTAManager::startOTAUpdate}；
         *     {@code notifyDownloading} → 仪表出进度；</li>
         *   <li>车机从<b>手机 FTP</b> 下载包体（{@code otaDownloadLoop → downloadOTAResultFile/
         *       FtpDownloadProgress}；手机需先开 FTP 并用 0x20280 handleOTAFtpServerOpened 通知地址）；
         *       {@code moveOTAResultFile} → 校验 → 刷写。</li>
         * </ol>
         * ADB-shell-over-EC 捷径（0x20410 handleAppAdbShellCmd）<b>不可用</b>：
         * 该处理器硬性要求 {@code transportType==0}（USB ADB），Wi-Fi(2) 下直接拒绝。</p>
         */
        boolean runPush(File pkg, Progress progress) {
            try {
                sock.setSoTimeout(30000);
                long total = pkg.length();
                String md5 = md5(pkg);

                // ① upgrade JSON（小帧）
                // 注：字段照抄官方 UpgradeJsonGenerator，但 sign_code/file_size 用本包真值
                //     （抓包里那两个是硬编码常量，对应官方的另一版包）。
                String json = buildUpgradeJson(pkg, md5);
                byte[] jsonFrame = frame(CMD_OTA_DOWNLOAD,
                        otaFilePayload("upgrade_json_file.txt", json.getBytes(StandardCharsets.UTF_8)));
                long t0 = System.currentTimeMillis();
                os.write(jsonFrame);
                os.flush();
                log("→ 手机 0x" + Integer.toHexString(CMD_OTA_DOWNLOAD)
                        + " upgrade_json_file.txt（" + json.length() + "B, sign_code=" + md5
                        + "，帧写耗时 " + (System.currentTimeMillis() - t0) + "ms）", log);
                int expected = 1;   // JSON 帧的 ack

                // ② 包体分片 + 有界流水线
                byte[] buf = new byte[CHUNK_SIZE];
                long sent = 0;
                int idx = 0;
                long lastPct = -1;
                FileInputStream fis = new FileInputStream(pkg);
                try {
                    int n;
                    while ((n = fis.read(buf)) > 0) {
                        byte[] chunk = n == buf.length ? buf : Arrays.copyOf(buf, n);
                        long ts = System.currentTimeMillis();
                        os.write(frame(CMD_OTA_DOWNLOAD, otaFilePayload(pkg.getName(), chunk)));
                        os.flush();
                        long sendMs = System.currentTimeMillis() - ts;
                        sent += n;
                        idx++;
                        expected++;
                        if (progress != null) {
                            progress.onProgress(sent, total);
                        }
                        long pct = total > 0 ? sent * 100 / total : 0;
                        if (pct / 5 != lastPct / 5) {
                            lastPct = pct;
                            log("  传输 " + pct + "%（第" + idx + "片，写耗时 " + sendMs
                                    + "ms，在途 " + (expected - otaAcks) + "，已 ack " + otaAcks + "）", log);
                        }
                        // 在途太多就等车机消化（有界，防止车机事件队列积压）
                        long dl = System.currentTimeMillis() + 120000;
                        while (expected - otaAcks >= PIPELINE_DEPTH
                                && System.currentTimeMillis() < dl) {
                            if (sock.isClosed()) {
                                summary = "连接在传输中断开（第" + idx + "片）";
                                log("✖ " + summary, log);
                                return false;
                            }
                            Thread.sleep(100);
                        }
                        if (expected - otaAcks >= PIPELINE_DEPTH) {
                            summary = "车机 120s 没消化包体片（第" + idx + "片，已 ack " + otaAcks
                                    + "/" + expected + "）";
                            log("✖ " + summary, log);
                            return false;
                        }
                    }
                } finally {
                    fis.close();
                }
                if (sent != total) {
                    summary = "读包不完整（" + sent + "/" + total + "）";
                    log("✖ " + summary, log);
                    return false;
                }

                // ③ 等全部 ack（JSON + 每片）
                log("✔ 包体已发完（" + sent + "B / " + idx + " 片），等车机 ack（" + otaAcks
                        + "/" + expected + "）…", log);
                long dl = System.currentTimeMillis() + 300000;
                while (otaAcks < expected && System.currentTimeMillis() < dl) {
                    if (sock.isClosed()) {
                        summary = "连接在等 ack 时断开（" + otaAcks + "/" + expected + "）";
                        log("✖ " + summary, log);
                        return false;
                    }
                    Thread.sleep(200);
                }
                if (otaAcks < expected) {
                    summary = "ack 不足（" + otaAcks + "/" + expected + "）——车机没收齐";
                    log("✖ " + summary, log);
                    return false;
                }
                log("✔ 车机已 ack 全部 " + expected + " 帧", log);

                JSONObject rjo = new JSONObject();
                rjo.put("result", "success");
                rjo.put("fileName", pkg.getName());
                rjo.put("fileSize", total);
                log("→ 手机 0x" + Integer.toHexString(CMD_OTA_RESULT) + " " + rjo, log);
                os.write(frame(CMD_OTA_RESULT, rjo.toString()));
                os.flush();
                long dl2 = System.currentTimeMillis() + 60000;
                while (!resultAcked && System.currentTimeMillis() < dl2) {
                    if (sock.isClosed()) {
                        summary = "连接在上报结果时断开";
                        log("✖ " + summary, log);
                        return false;
                    }
                    Thread.sleep(200);
                }
                if (!resultAcked) {
                    summary = "等 0x20291 回包失败";
                    log("✖ " + summary, log);
                    return false;
                }
                summary = "包已送达车机（" + total + "B）。车机自行 VerifyChecksum → 刷写 → 重启；"
                        + "刷写结果以仪表屏幕/串口为准（协议不回传）";
                log("✔ " + summary, log);
                return true;
            } catch (Exception e) {
                summary = "推包异常：" + e;
                log("✖ " + summary, log);
                return false;
            }
        }

        private boolean waitRly(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Frame f = readFrame(is, 1500);
                if (f == null) {
                    if (pendingRly == 0) {
                        return true;
                    }
                    if (sock.isClosed()) {
                        return false;
                    }
                    continue;
                }
                log("←#" + index + " 车机 " + describe(f), log);
                if (f.cmdType == pendingRly) {
                    pendingRly = 0;
                    return true;
                }
                if (!handle(f)) {
                    return false;
                }
            }
            return pendingRly == 0;
        }

        private void write(int cmdType, String payload) {
            try {
                os.write(frame(cmdType, payload));
                os.flush();
            } catch (Exception e) {
                log("  发送失败 0x" + Integer.toHexString(cmdType) + " " + e, log);
            }
        }

        /** clientInfo 应答 JSON（字段取自 sendClientInfo 回包解析侧）。 */
        private String clientInfoRly() {
            try {
                JSONObject o = new JSONObject();
                o.put("pxcVersion", "1.0.2");
                o.put("token", 0);
                o.put("pubkey", "");
                o.put("phoneOs", "Android");
                o.put("phoneUUID", "");
                o.put("phoneModel", android.os.Build.MODEL);
                o.put("phoneBrand", android.os.Build.BRAND);
                o.put("package", "com.github.zeehospeedhunter");
                o.put("versionCode", 32);
                o.put("mirrorRequestReconnect", false);
                o.put("disableAoaHID", false);
                o.put("bluetoothName", "");
                o.put("bluetoothAddress", "");
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    private static void log(String s, LineSink log) {
        HookLog.log(TAG + " " + s);
        if (log != null) {
            log.onLine(s);
        }
    }

    /** 文件 MD5（EC 路自带一份，避免依赖已删除的 OtaFlash）。 */
    private static String md5(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 20];
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "md5fail:" + e;
        }
    }

    /**
     * 生成 {@code upgrade_json_file.txt}。
     *
     * <p>字段照抄官方诊断 App 的 {@code UpgradeJsonGenerator#generateUpgradeJson}，
     * 但 {@code sign_code}/{@code file_size} 用<b>本包</b>真值。</p>
     */
    private static String buildUpgradeJson(File zip, String md5) {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("id", "123");
            json.put("code", "");
            json.put("task_id", "");
            json.put("sign_method", "md5");
            json.put("VehSOC", "1");                 // 升级前置位：电量
            json.put("VehSpd", "0");                 // 升级前置位：车速
            json.put("CheckKl15", "0");              // 钥匙电（0 = 允许）
            json.put("CheckCharge", "0");            // 充电中（0 = 允许）
            json.put("CheckBMS", "0");
            json.put("VehGear", "0");                // 挡位
            json.put("precondition", "0");
            json.put("forced_upgrade_flg", "0");
            json.put("module", "DASH");
            json.put("single_task_id", "");
            json.put("sign_code", md5);
            json.put("update_url", "file://" + OTA_DIR + "/" + zip.getName());
            json.put("version", "1.0.1");
            json.put("retry_count", "0");
            json.put("rollback_config", "");
            json.put("rollback_url", "");
            json.put("is_diff", "0");
            json.put("sort", "0");
            json.put("file_size", String.valueOf(zip.length()));
            json.put("upgrade_content", "车辆仪表升级");
            json.put("flashing_type", "local");
            json.put("upgrade_minutes", "15");
            json.put("upgrade_type", "local");
            json.put("key1", "");
            json.put("value1", "");
            json.put("key2", "");
            json.put("value2", "");
            json.put("_package_udi", "{\"ota_notice\":\"\"}");
            json.put("ext_data", "");
            json.put("total_upgrade_minutes", "15");
            json.put("data", "");
            org.json.JSONArray files = new org.json.JSONArray();
            org.json.JSONObject one = new org.json.JSONObject();
            one.put("fileName", zip.getName());
            one.put("fileSize", String.valueOf(zip.length()));
            one.put("fileMd5", md5);
            one.put("filePath", OTA_DIR + "/" + zip.getName());
            files.put(one);
            json.put("files", files);
            return json.toString();
        } catch (Exception e) {
            return "{\"code\":\"" + e + "\"}";
        }
    }

    /** 车机侧 OTA 工作目录（沿用官方诊断 App 的约定）。 */
    private static final String OTA_DIR = "/datacache/ota";
}
