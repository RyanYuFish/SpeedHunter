package com.github.zeehospeedhunter.diag;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * ZEEHO 车端诊断传输层 —— 忠实移植自 zeeho-diag {@code lib/diagClient.js}
 * （还原自 ZeeCare {@code tcpSocketClient.uts}）。
 *
 * <p>TCP {@code 192.168.49.1:13400} DoIP；路由激活 → 心跳(2s) → 诊断消息收发。
 * Node 原版是事件驱动 + 单 {@code pendingDiag} 回调；Android 端改成阻塞套接字模型：
 * 读线程持续拆 PDU，每条诊断消息用「按正则匹配 + 带超时的阻塞队列」交付，
 * 语义与 JS 的 {@code matchPattern + timeout} 一致。</p>
 *
 * <h3>响应分发规则（按 PDU 十六进制前缀，与 JS 逐字一致）</h3>
 * <pre>
 *   03FC0006 路由激活响应 / 03FC0008 alive-check 响应（忽略）
 *   03FC80020000000700010E00003E80  tester present 响应（忽略）
 *   03FC8001 / 03FC8002 / 03FC8003  诊断正响应 / 多帧 → 交给等待者
 * </pre>
 *
 * <p>调用方（{@link VehicleService} / {@link Routines}）在 worker 线程里串行调用，
 * 与桌面端一致；本类不是线程安全的并发多请求容器，请勿并行 {@link #diagnosticMessage}。</p>
 */
public final class DiagClient {

    /** 默认目标：ZeeCare DoIP 网关。 */
    public static final String DEFAULT_HOST = "192.168.49.1";
    public static final int DEFAULT_PORT = 13400;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final long HEARTBEAT_INTERVAL_MS = 2000L;

    private final String host;
    private final int port;
    private final Consumer<String> log;

    private Socket socket;
    private volatile boolean connected;
    private final Object rxLock = new Object();
    private byte[] rxBuffer = new byte[0];

    private Thread reader;
    private ScheduledExecutorService heartbeat;

    // 当前等待的诊断响应（单消息模式）
    private final Object pendingLock = new Object();
    private ArrayBlockingQueue<byte[]> pendingQueue;
    private Pattern pendingPattern; // null = 任意诊断消息

    public DiagClient(String host, int port, Consumer<String> log) {
        this.host = (host == null || host.isEmpty()) ? DEFAULT_HOST : host;
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.log = (log == null) ? (s) -> {} : log;
    }

    public DiagClient(Consumer<String> log) {
        this(DEFAULT_HOST, DEFAULT_PORT, log);
    }

    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    // ---------- 连接 ----------

    /** 找到与目标主机同一 /24 的本机地址（车机网段），用于绑定源 IP；找不到返回 null。 */
    public static String localIpForHost(String host) {
        if (host == null) return null;
        int dot = host.lastIndexOf('.');
        if (dot < 0) return null;
        final String prefix = host.substring(0, dot + 1); // 形如 "192.168.49."
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    String ip = a.getHostAddress();
                    if (ip != null && ip.startsWith(prefix) && ip.indexOf('%') < 0) {
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public synchronized void connect() throws Exception {
        if (isConnected()) return;
        Socket s = new Socket();
        String local = localIpForHost(host);
        if (local != null) {
            try {
                s.bind(new InetSocketAddress(local, 0));
                log.accept("源地址绑 " + local);
            } catch (Exception e) {
                log.accept("⚠ 绑定源地址 " + local + " 失败，用系统默认路由（" + e + "）");
            }
        } else {
            log.accept("⚠ 未找到 " + host + " 同网段的本机地址，用系统默认路由");
        }
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        try {
            s.setTcpNoDelay(true);
        } catch (Exception ignored) {
        }
        socket = s;
        connected = true;
        synchronized (rxLock) {
            rxBuffer = new byte[0];
        }
        log.accept("TCP 已连接 " + host + ":" + port);
        startReader();
    }

    private void startReader() {
        reader = new Thread(this::readerLoop, "diag-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readerLoop() {
        final Socket s = this.socket;
        if (s == null) return;
        byte[] buf = new byte[65536];
        try {
            java.io.InputStream in = s.getInputStream();
            int n;
            while (connected && !s.isClosed() && (n = in.read(buf)) > 0) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                synchronized (rxLock) {
                    rxBuffer = DiagProtocol.concat(rxBuffer, chunk);
                    drainPdus();
                }
            }
        } catch (Exception e) {
            if (connected) log.accept("TCP 读取结束：" + e);
        } finally {
            onClosed();
        }
    }

    private void drainPdus() {
        DiagProtocol.ExtractResult ex = DiagProtocol.extractCompleteDoipPdus(rxBuffer);
        rxBuffer = ex.remaining;
        for (byte[] pdu : ex.packets) {
            dispatchPdu(pdu);
        }
    }

    private void dispatchPdu(byte[] pdu) {
        String hex = DiagProtocol.bytesToHex(pdu); // 大写
        if (hex.startsWith("03FC0006")) { // 路由激活响应
            log.accept("路由激活响应: " + hex);
            return;
        }
        if (hex.startsWith("03FC0008")) { // alive check 响应
            return;
        }
        if (hex.startsWith("03FC80020000000700010E00003E80")) { // tester present 响应
            return;
        }
        if (hex.startsWith("03FC8001") || hex.startsWith("03FC8003") || hex.startsWith("03FC8002")) {
            synchronized (pendingLock) {
                ArrayBlockingQueue<byte[]> q = pendingQueue;
                if (q != null && (pendingPattern == null || pendingPattern.matcher(hex).find())) {
                    q.offer(pdu);
                }
            }
        }
    }

    private void onClosed() {
        connected = false;
        stopHeartbeat();
        synchronized (pendingLock) {
            if (pendingQueue != null) {
                pendingQueue.offer(new byte[0]); // 唤醒等待者，按空响应处理
                pendingQueue = null;
                pendingPattern = null;
            }
        }
    }

    public synchronized void close() {
        connected = false;
        stopHeartbeat();
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        synchronized (rxLock) {
            rxBuffer = new byte[0];
        }
    }

    // ---------- 发送 ----------

    private void send(byte[] pdu) throws Exception {
        Socket s = socket;
        if (s == null || !connected) throw new Exception("TCP 未连接");
        OutputStream os = s.getOutputStream();
        os.write(pdu);
        os.flush();
    }

    /** 路由激活（每次诊断前调用，与官方一致；不等响应，匹配 JS 行为）。 */
    public void routerActivation() throws Exception {
        connect();
        byte[] pdu = DiagProtocol.buildRouteActivationReqPdu();
        log.accept("路由激活请求: " + DiagProtocol.bytesToHex(pdu));
        send(pdu);
    }

    // ---------- 心跳（alive check，2s） ----------

    public void startHeartbeat() {
        if (heartbeat != null && !heartbeat.isShutdown()) return; // 已在跑，别反复重建
        stopHeartbeat();
        heartbeat = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "diag-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            if (!isConnected()) return;
            try {
                send(DiagProtocol.buildRouteAliveCheckReqPdu());
            } catch (Exception ignored) {
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stopHeartbeat() {
        ScheduledExecutorService h = heartbeat;
        heartbeat = null;
        if (h != null) {
            try {
                h.shutdownNow();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 发送一条 UDS 诊断消息并等待响应。
     *
     * @param da          目标逻辑地址
     * @param udsData     UDS 字节
     * @param matchRegex  匹配整包 hex 的正则（null = 任意诊断消息）；大小写不敏感
     * @param timeoutMs   超时（毫秒）
     * @return 完整 DoIP PDU（含头）；超时/断链返回 null
     */
    public byte[] diagnosticMessage(int da, byte[] udsData, String matchRegex, int timeoutMs) throws Exception {
        connect();
        byte[] pdu = DiagProtocol.buildDiagnosticMessagePdu(DiagProtocol.DOIP_SOURCE_ADDRESS, da, udsData);
        log.accept("诊断请求: " + DiagProtocol.bytesToHex(pdu));

        ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<>(1);
        Pattern pat = (matchRegex == null || matchRegex.isEmpty())
                ? null : Pattern.compile(matchRegex, Pattern.CASE_INSENSITIVE);
        synchronized (pendingLock) {
            // 串行模型：丢弃上一个未取走的等待（与 JS 的「清掉上一个 pending」一致）
            pendingQueue = q;
            pendingPattern = pat;
        }
        try {
            send(pdu);
            byte[] resp = q.poll(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (resp != null && resp.length == 0) {
                return null; // 连接被关闭时唤醒
            }
            if (resp == null) {
                log.accept("诊断响应超时: " + DiagProtocol.bytesToHex(udsData));
            }
            return resp;
        } finally {
            synchronized (pendingLock) {
                if (pendingQueue == q) {
                    pendingQueue = null;
                    pendingPattern = null;
                }
            }
        }
    }

    public byte[] diagnosticMessage(int da, byte[] udsData) throws Exception {
        return diagnosticMessage(da, udsData, null, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 带重试的诊断（移植 diagnosticMessage 4 次重试 + 重新路由激活）。
     * 返回解析后的 {@link DiagProtocol.DiagData}；全部失败返回空 udsData 的对象。
     */
    public DiagProtocol.DiagData diagnosticMessageWithRetry(int da, byte[] udsData, String matchRegex,
                                                            int timeoutMs, int retries) throws Exception {
        for (int i = 0; i < retries; i++) {
            try {
                if (i > 0) {
                    Thread.sleep(500);
                    routerActivation();
                    startHeartbeat();
                }
                Thread.sleep(50);
                byte[] pdu = diagnosticMessage(da, udsData, matchRegex, timeoutMs);
                if (pdu == null) continue;
                DiagProtocol.DiagData diag = DiagProtocol.parseDiagData(
                        DiagProtocol.slice(pdu, DiagProtocol.DOIP_HEADER_LENGTH, pdu.length));
                if (diag.udsData.length == 0) continue;
                return diag;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.accept("诊断重试 " + (i + 1) + "/" + retries + ": " + e.getMessage());
                if (i == retries - 1) throw e;
            }
        }
        DiagProtocol.DiagData empty = new DiagProtocol.DiagData();
        return empty;
    }

    public DiagProtocol.DiagData diagnosticMessageWithRetry(int da, byte[] udsData) throws Exception {
        return diagnosticMessageWithRetry(da, udsData, null, DEFAULT_TIMEOUT_MS, 4);
    }
}
