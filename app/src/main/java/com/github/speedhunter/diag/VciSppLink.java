package com.github.speedhunter.diag;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 官方 ZSCAN VCI 的 SPP 传输后端(经典蓝牙 RFCOMM,UUID 00001101)。
 * 2026-11-06 台架实测定格:VCI 蓝牙地址 DC:0D:30:81:F1:E9(SN 501400009070),
 * 数据通道 = RFCOMM DLCI=2;L2(55aa 配置/鉴权)与 L3(56/ab 数据)同流共存。
 *
 * 发送:L3 组帧 = 56 00 + len(u16 LE=seq+cmd+data+1) + seq + cmd + data + XOR(全帧)。
 * 接收:后台线程读流 → VciLink.parseStream(56/ab) → 回调(含 CF/ED 提取)。
 *
 * L2(55aa 握手 FFFA/FFEF/FEF2/FFB2)尾部 3B 算法未定标 —— 当前实现先直发 L3,
 * L2 握手按需以实抓常量重放(台架验证通过后再固化)。
 * 权限:API31+ 需 BLUETOOTH_CONNECT(已在 Manifest 声明)。
 */
public final class VciSppLink {
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public static final String DEFAULT_VCI_MAC = "DC:0D:30:81:F1:E9";

    // ===== L2 握手常量(2026-11-06 官方 App 1.7.7 台架实抓,逐字节重放) =====
    /** FFFA 状态查询(会话1 seq=0000)。 */
    public static final byte[] L2_FFFA_QUERY = hex("55aa00000005fffa01000260c854");
    /** 官方握手完整 FFA 序列(2026-11-08 12:38 实抓,含 tail 变体;FEF2 必须紧跟首个应答 +2ms)。 */
    public static final byte[] L2_FFFA_A = hex("55aa00000005fffa01000260c854");
    public static final byte[] L2_FFFA_B = hex("55aa00000005fffa01000260c15d");
    public static final byte[] L2_FFFA_C = hex("55aa00000005fffa01000260c559");
    public static final byte[] L2_FFFA_S1 = hex("55aa00010005fffa01000260ff62");
    public static final byte[] L2_FFFA_S2 = hex("55aa00020005fffa01000260c759");
    public static final byte[] L2_FFFA_S3 = hex("55aa00030005fffa01000260c956");

    /** FEF2 鉴权(269B blob,首连发送;VCI 已许可的重连不发——会话2 实证)。 */
    public static final byte[] L2_FEF2_AUTH = hex("55aa0000010dfef201010a60d00801040000000100b58df2df013136dbfe50a54bdec7987409f82f85d30d1a1aec15b8b5a88d6718ec969ded9f3950f0c5b91a7a5d3a9acd4158532e7cbe975fc5fcffc9a40d5cdf9231dfb03c52333b0fc849c602d5e9bf6e67ea9f407624c55ee1810a646aabd334e99726f2e54d9c471d97b646a677e8dbb39b040e0c72e6b6a6b38f1d6bc76a77f6b27b973fcc82e1957e17edf9772e64b9a400d0c10d236cb5fe3a64c7f6ecbd875d384d4ea33d2b4391405d9ff23146b18e163447df1e8fcbce268d7d2a20327aaad827450a5ea3030097c1a54026189691419d1bd701611e8b7c295fb0a144643f302b96e797a6878715003cbfd18f969f6c29f4dfcf7597c2a8e74bc56c5a");
    /** FFB2 许可(86B,内含 ASCII "CFMOTO"+"hello";首连发送)。 */
    public static final byte[] L2_FFB2_LICENSE = hex("55aa0004004dffb201004a60d80000000000001c8043464d4f544ff61cfa7a57ff2026072068656c6f7249f4e748ac5972b95e72da6c8c186cf6e67d80ea50516f529ef17bde5f65651c087f17440f2920795da5d39b");

    public interface Listener {
        void onConnected(String device);
        void onHandshake(String mode, String detail);
        void onL2(byte[] frame);
        void onFrame(VciLink.DataFrame frame, List<byte[]> cfFrames);
        void onRaw(byte[] chunk, int len);
        void onError(String message);
        void onDisconnected();
    }

    private volatile boolean sawL2Reply = false;
    private volatile boolean sawDataReply = false;

    public boolean isDataReady() { return running && sawDataReply; }

    public void sendDeleteEntryQueries() throws IOException {
        sendRaw(hex("55aa00080005fffa01000260ff6b"));
        sleepQuiet(40);
        sendRaw(hex("55aa00090005fffa01000260c752"));
    }
    private android.os.Handler keepAlive; // FFA 心跳(官方 App 每 ~10s 一发,无心跳 ~22s 被 VCI 掐线)

    private static byte[] hex(String x) {
        int n = x.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) Integer.parseInt(x.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private final Listener listener;
    private BluetoothSocket socket;
    private OutputStream out;
    private Thread rxThread;
    private volatile boolean running;
    private int nextSeq = 0;

    public VciSppLink(Listener listener) {
        this.listener = listener;
    }

    /** 组 L3 发送帧(委托 VciLink.buildFrame,纯逻辑可 JVM 测试)。 */
    public static byte[] buildL3Frame(int seq, int cmd, byte[] data) {
        return VciLink.buildFrame(seq, cmd, data);
    }

    @SuppressLint("MissingPermission")
    public void connect(Context context, String mac) throws IOException {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("蓝牙未开启");
        BluetoothDevice device = adapter.getRemoteDevice(mac == null ? DEFAULT_VCI_MAC : mac);
        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
        socket.connect();
        out = socket.getOutputStream();
        running = true;
        listener.onConnected(device.getAddress());
        rxThread = new Thread(this::rxLoop, "vci-rx");
        rxThread.start();
        startKeepAlive();
    }

    /**
     * 连接 + 官方握手工序(复刻 ZSCAN 流程):
     * ① SPP 连接 ② FFFA 状态查询 → 收到 aa55 应答即完成(最小路径,VCI 已许可)
     * ③ 无应答则完整链:FEF2 鉴权(269B) → FFB2 许可(86B) → 再 FFFA 验证
     * ④ withSessionSetup=true 时自动发 L3 会话前导(60×3+69×3;官方从不发 6A 触发器)。
     */
    public void connectAndHandshake(Context context, String mac, boolean withSessionSetup) throws IOException {
        stopOfficialApp();
        try {
            connectOnce(context, mac, withSessionSetup);
        } catch (IOException first) {
            // 僵尸连接(VCI 端被占)兜底:root 重启蓝牙撕断 ACL,再试一次
            listener.onHandshake("reset", "首次连接失败(" + first.getMessage() + "),蓝牙重置后重试…");
            btReset();
            connectOnce(context, mac, withSessionSetup);
        }
    }

    private void connectOnce(Context context, String mac, boolean withSessionSetup) throws IOException {
        sawL2Reply = false;
        sawDataReply = false;
        connect(context, mac);
        // ★ 官方时序(2026-11-08 12:38 实测):FFA→应答后 +2ms 发 FEF2;慢于 ~400ms 会被 VCI 掐线
        sendRaw(L2_FFFA_A);
        waitForL2Reply(1000);
        if (!sawL2Reply) {
            listener.onHandshake("warn", "FFFA 无应答(检查 VCI 电源/配对);仍继续发 FEF2…");
        }
        sendRaw(L2_FEF2_AUTH);            // 立即,不 sleep
        sleepQuiet(1100);                 // 官方在 FEF2 应答后等 ~1.1s(12:38:34 实测无 FFB2,已许可不再发)
        sendRaw(L2_FFFA_B);
        sleepQuiet(60);
        sendRaw(L2_FFFA_C);
        sleepQuiet(40);
        sendRaw(L2_FFFA_S1);
        sleepQuiet(40);
        sendRaw(L2_FFFA_S2);
        sleepQuiet(40);
        sendRaw(L2_FFFA_S3);
        // 2026-11-09 official successful delete session includes a second L2 phase
        // and FFB2 before any L3 command. L2 replies alone do not establish data mode.
        sleepQuiet(700);
        String[] phase = {
                "55aa00000005fffa01000260ff63",
                "55aa00010005fffa01000260c15c",
                "55aa00020005fffa01000260c05e",
                "55aa00030005fffa01000260cd52"
        };
        for (String query : phase) { sendRaw(hex(query)); sleepQuiet(40); }
        sendRaw(L2_FFB2_LICENSE);
        sleepQuiet(100);
        sendRaw(hex("55aa00050005fffa01000260c75e"));
        sleepQuiet(2100);
        sendRaw(hex("55aa00060005fffa01000260ff65"));
        sleepQuiet(40);
        sendRaw(hex("55aa00070005fffa01000260c75c"));
        sleepQuiet(15);
        listener.onHandshake("L2", sawL2Reply ? "L2 有应答，等待 L3 数据通道验证" : "L2 未见应答");
        if (withSessionSetup) {
            // 官方前导 60×3+69×3(seq 1..6);正式操作时 DiagFragment 再完整重发一遍
            for (byte[] l3 : ZeehoRs485Ops.sessionSetupCommands()) {
                send(l3[1] & 0xFF, java.util.Arrays.copyOfRange(l3, 2, l3.length));
                sleepQuiet(20);
            }
            long end = System.currentTimeMillis() + 2500;
            while (running && !sawDataReply && System.currentTimeMillis() < end) sleepQuiet(20);
            listener.onHandshake(sawDataReply ? "L3" : "warn", sawDataReply
                    ? "已收到 L3 应答；尚不代表车辆主从切换成功"
                    : "未收到 L3 应答，车辆操作已阻止；请导出日志");
        }
    }

    /** root 重启蓝牙(撕断两端所有 ACL,清掉 VCI 端僵尸占用)。 */
    public void btReset() {
        runSu("svc bluetooth disable");
        sleepQuiet(2500);
        runSu("svc bluetooth enable");
        sleepQuiet(5000);
    }

    private static void runSu(String cmd) {
        try {
            Process su = new ProcessBuilder("su", "-c", cmd).start();
            su.waitFor();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 抢占修复:原厂 App 会独占 VCI(00:58 实证)。优先 am kill(温和后台杀,无副作用);
     * 不得已才 force-stop —— force-stop 会让原厂 App 下次冷启动崩
     * ForegroundServiceStartNotAllowedException(VciService 起不来 ⇒ 报「没有获取到sn」,
     * 04:36/04:55 两次实证;第二次启动才恢复)。
     */
    public void stopOfficialApp() {
        runSu("am kill com.obdstar.x300dp");
        sleepQuiet(300);
        if (stillAlive()) {
            listener.onHandshake("warn", "原厂 App 仍在(前台?),force-stop 兜底;其下次冷启或需开两次");
            runSu("am force-stop com.obdstar.x300dp");
            sleepQuiet(300);
        }
    }

    private static boolean stillAlive() {
        try {
            Process p = new ProcessBuilder("su", "-c", "pidof com.obdstar.x300dp").start();
            p.waitFor();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64];
            int n = p.getInputStream().read(buf);
            return n > 0 && buf[0] != '\n';
        } catch (Throwable t) {
            return false;
        }
    }

    private void waitForL2Reply(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!sawL2Reply && System.currentTimeMillis() < end) {
            sleepQuiet(20);
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void rxLoop() {
        byte[] buf = new byte[4096];
        VciLink.StreamDecoder decoder = new VciLink.StreamDecoder();
        try {
            InputStream in = socket.getInputStream();
            while (running) {
                int n = in.read(buf);
                if (n < 0) break;
                listener.onRaw(buf, n);
                decoder.accept(buf, n, new VciLink.StreamDecoder.Sink() {
                    @Override public void onL2(byte[] frame) {
                        sawL2Reply = true;
                        listener.onL2(frame);
                    }
                    @Override public void onData(VciLink.DataFrame frame) {
                        sawDataReply = true;
                        List<byte[]> cfs = (frame.cmd == VciLink.CMD_RS485_WRITE || frame.fromVehicle)
                                ? VciLink.extractCfFrames(frame) : new ArrayList<byte[]>();
                        listener.onFrame(frame, cfs);
                    }
                });
            }
        } catch (IOException e) {
            if (running) listener.onError("RX: " + e.getMessage());
        } finally {
            listener.onDisconnected();
        }
    }

    /** 发送 L3 命令(自动递增 seq)。 */
    public synchronized void send(int cmd, byte[] data) throws IOException {
        sendWithSeq(cmd, data);
    }

    public synchronized int sendWithSeq(int cmd, byte[] data) throws IOException {
        if (out == null) throw new IOException("未连接");
        byte[] frame = buildL3Frame((nextSeq = (nextSeq + 1) & 0xFF), cmd, data);
        out.write(frame);
        out.flush();
        return nextSeq;
    }

    /** 发送 RS485 CF/ED 帧(经 cmd68 前缀 010096 包装)。 */
    public synchronized void sendRs485(byte[] cfFrame) throws IOException {
        send(VciLink.CMD_RS485_WRITE, ZeehoRs485Ops.wrapRs485ForVci(cfFrame));
    }

    /** 发送总线裸负载(ABS 07e1 协议等,cmd68 前缀 01 00 <channel>)。 */
    public synchronized void sendBus(byte[] cmd68Data) throws IOException {
        send(VciLink.CMD_RS485_WRITE, cmd68Data);
    }


    public synchronized void sendRaw(byte[] bytes) throws IOException {
        if (out == null) throw new IOException("未连接");
        out.write(bytes);
        out.flush();
    }

    /** FFA 心跳:每 10s 一发(实拍官方节奏),维持 VCI 连接。 */
    /** RS485 轮询保持(官方行为:操作期间持续 69 0101 激活 VCI 转发)。 */
    private android.os.Handler pollHandler;
    private volatile boolean polling = false;

    public void startPolling() {
        stopPolling();
        polling = true;
        pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!polling || !running || pollHandler == null) return;
                try {
                    send(VciLink.CMD_POLL, new byte[]{0x01, 0x01});
                } catch (Exception e) {
                    return;
                }
                pollHandler.postDelayed(this, 200);
            }
        };
        pollHandler.post(tick);
    }

    public void stopPolling() {
        polling = false;
        if (pollHandler != null) {
            pollHandler.removeCallbacksAndMessages(null);
            pollHandler = null;
        }
    }

    /** 官方在操作序列期间不发 FFA 心跳(2026-11-10 抓取:主从切换 11s 窗口内零 L2);
     *  操作期间挂起心跳,避免 L2 打断关键时序。 */
    public void suspendKeepAlive() { keepAliveSuspended = true; }
    public void resumeKeepAlive() { keepAliveSuspended = false; }
    private volatile boolean keepAliveSuspended;

    private void startKeepAlive() {
        stopKeepAlive();
        keepAlive = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running || keepAlive == null) return;
                if (!keepAliveSuspended) {
                try {
                    sendRaw(L2_FFFA_A);
                } catch (Exception e) {
                    listener.onError("keepalive: " + e.getMessage());
                    return;
                }
                }
                keepAlive.postDelayed(this, 10000);
            }
        };
        keepAlive.postDelayed(tick, 10000);
    }

    private void stopKeepAlive() {
        if (keepAlive != null) {
            keepAlive.removeCallbacksAndMessages(null);
            keepAlive = null;
        }
    }

    @SuppressLint("MissingPermission")
    public void close() {
        running = false;
        stopPolling();
        stopKeepAlive();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }
}
