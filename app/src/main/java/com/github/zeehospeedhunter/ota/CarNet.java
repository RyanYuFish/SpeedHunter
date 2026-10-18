package com.github.zeehospeedhunter.ota;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车机网络中枢 —— 连接与网络诊断（依据第三方「极核仪表 OTA 工具」实况）。
 *
 * <h3>为什么要这个类</h3>
 * <p>此前全模块各处硬编码车机地址（推包 {@code 192.168.0.1}、诊断 {@code 192.168.49.1}），
 * 且每个页面各写一套「手填 SSID + 密码」表单。而车机地址<b>并不固定</b>：组主（GO）
 * 随 P2P 模式在 {@code 192.168.0.1} 与 {@code 192.168.49.1} 之间浮动。第三方工具的实测结论是
 * 「本车组主是 {@code 192.168.0.1}，不是标准的 {@code 192.168.49.1}，以<b>网络诊断</b>显示的为准」。
 * 所以地址必须靠<b>探测</b>，不能写死。</p>
 *
 * <h3>连接模式</h3>
 * <p>两种，按优先级：</p>
 * <ol>
 *   <li><b>Wi-Fi Direct（P2P）</b>：官方 ZeeCare 与第三方工具主用；车机当组主时地址在组网段。</li>
 *   <li><b>系统 WLAN 直连</b>：连车机 SoftAP {@code ZEEHO-xxxx}（5GHz），连接动作<b>交给系统设置</b>做
 *       （{@link #openSystemWifiSettings}），本模块只负责「探测地址 + 引导」。Direct 报「内部错误」时的兜底。</li>
 * </ol>
 *
 * <h3>红线</h3>
 * <p>只做「连网 + 探测」：只读 TCP 握手判断端口是否监听，<b>不发送任何非本协议帧</b>；
 * 也不改网络配置、不动已连网络。真正推包/刷写仍走各自的门禁。</p>
 */
public final class CarNet {

    private CarNet() {
    }

    /**
     * 车机候选组主地址。<b>不要只看 192.168.49.1</b> —— 第三方工具实测本车组主是
     * {@code 192.168.0.1}；这里两个都当候选，靠端口握手判定谁在。
     */
    public static final String[] CAR_HOSTS = {"192.168.0.1", "192.168.49.1"};

    /** 车机侧已知端口（只用来握手探测，不发业务数据）。 */
    public static final int PORT_SOCKET_L = 10950;  // OTA 推送
    public static final int PORT_EC = 10930;        // EasyConnect mDNS/控制服务（车机广播 _EasyConn._tcp.local.）
    public static final int PORT_DOIP = 13400;      // 车端诊断
    public static final int PORT_ADB = 5555;        // P2P ADB
    /** cfmoto_P2p_server：仪表 Key OTA 启动的独立裸包服务（固件静态证据）。 */
    public static final int PORT_P2P_TOOL = 10086;

    // ==================== 本机在车机网段？ ====================

    public static final class NetInfo {
        public String iface;   // wlan0 / p2p0 / wlan1 ...
        public String ip;      // 形如 192.168.0.51
    }

    /** 列出本机所有「像车机/局域网」的 IPv4（非回环、192.168.*）。 */
    public static List<NetInfo> localPrivateIps() {
        List<NetInfo> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    String ip = a.getHostAddress();
                    if (ip != null && ip.startsWith("192.168.") && ip.indexOf('%') < 0) {
                        NetInfo n = new NetInfo();
                        n.iface = ni.getName();
                        n.ip = ip;
                        out.add(n);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 本机是否已在某个车机网段（任意 192.168.* 网卡）。 */
    public static boolean onCarNet() {
        return !localPrivateIps().isEmpty();
    }

    /** 找与目标主机同一 /24 的本机地址（连接时用它做源地址绑定）。 */
    public static String localIpForHost(String host) {
        if (host == null) return null;
        int dot = host.lastIndexOf('.');
        if (dot < 0) return null;
        final String prefix = host.substring(0, dot + 1);
        String fallback = null;
        for (NetInfo n : localPrivateIps()) {
            if (n.ip.startsWith(prefix)) {
                if (n.iface != null && n.iface.startsWith("p2p")) return n.ip;
                if (fallback == null) fallback = n.ip;
            }
        }
        return fallback;
    }

    // ==================== 组主地址探测（网络诊断的核心） ====================

    /** 单个端口在某主机上的握手结果。 */
    public static final class PortProbe {
        public final String host;
        public final int port;
        public boolean open;
        public String detail = "";

        PortProbe(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** 网络诊断汇总（供「网络诊断」按钮输出）。 */
    public static final class DiagReport {
        public boolean onCarNet;
        public String localInfo = "";                 // 本机网卡一览
        public final List<PortProbe> probes = new ArrayList<>();
        public String groupOwner = "";                // 判定出的组主地址
        public int groupOwnerPort = -1;               // 用来判定的端口
        public String mode = "";                      // Wi-Fi Direct / 系统 WLAN / 未连
        public boolean prefer5GHz;                    // 车机 AP 首选 5GHz
        public String summary = "";

        public boolean hasCar() {
            return groupOwner != null && !groupOwner.isEmpty();
        }
    }

    /** 某主机的某个端口是否可连（纯 TCP 握手，短超时，不发数据）。 */
    public static boolean portOpen(String host, int port, int timeoutMs, String[] detailOut) {
        Socket s = new Socket();
        try {
            // ★ 必须绑本机源地址：Android 多网络下 default network（蜂窝）会抢路，
            //   裸 connect 会从蜂窝口出、到不了车机网段（日志实证 from /10.x.x.x）。
            //   只绑与 host 同 /24 子网的本机地址；否则明确跳过，不能误走蜂窝网络。
            String src = pickSourceFor(host);
            if (src == null) {
                if (detailOut != null) {
                    detailOut[0] = "跳过：手机没有与 " + host + " 同 /24 的活动网卡";
                }
                return false;
            }
            s.bind(new InetSocketAddress(src, 0));
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            if (detailOut != null) detailOut[0] = "TCP " + host + ":" + port + " 可连";
            return true;
        } catch (Exception e) {
            if (detailOut != null) detailOut[0] = String.valueOf(e.getMessage());
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** 选一个与 host 同 /24 子网的本机地址做 bind 源；没有时返回 null。 */
    private static String pickSourceFor(String host) {
        return localIpForHost(host);
    }

    /**
     * 网络诊断：探测组主地址与已知端口，并识别连接模式与 5GHz。
     * <b>阻塞</b>，放 worker 线程。
     */
    public static DiagReport diagnose(Context ctx) {
        DiagReport r = new DiagReport();

        // 本机网卡
        List<NetInfo> ips = localPrivateIps();
        StringBuilder li = new StringBuilder();
        boolean p2p = false;
        for (NetInfo n : ips) {
            if (li.length() > 0) li.append("  ");
            li.append(n.iface).append("=").append(n.ip);
            if (n.iface != null && n.iface.startsWith("p2p")) p2p = true;
        }
        r.onCarNet = !ips.isEmpty();
        r.localInfo = r.onCarNet ? li.toString() : "（未拿到 192.168.* 地址）";
        r.mode = r.onCarNet ? (p2p ? "Wi-Fi Direct（P2P）" : "系统 WLAN 直连（SoftAP）") : "未连车机";

        // 车机 AP 是否 5GHz
        r.prefer5GHz = carApIs5G(ctx);

        // 端口握手探测：任一候选主机在任一端口可连 → 判定为组主
        int[] ports = {PORT_SOCKET_L, PORT_P2P_TOOL, PORT_EC, PORT_DOIP, PORT_ADB};
        for (String host : CAR_HOSTS) {
            for (int port : ports) {
                PortProbe pp = new PortProbe(host, port);
                String[] detail = new String[1];
                pp.open = portOpen(host, port, 1200, detail);
                pp.detail = detail[0];
                r.probes.add(pp);
                if (pp.open && !r.hasCar()) {
                    r.groupOwner = host;
                    r.groupOwnerPort = port;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("模式：").append(r.mode).append("　本机：").append(r.localInfo).append('\n');
        sb.append("车机 AP：").append(r.prefer5GHz ? "5GHz 优先" : "2.4GHz/未知").append('\n');
        for (PortProbe pp : r.probes) {
            sb.append(pp.open ? "  ✔ " : "  ✖ ").append(pp.host).append(':').append(pp.port)
                    .append(' ').append(pp.detail).append('\n');
        }
        if (r.hasCar()) {
            sb.append("★ 组主地址：").append(r.groupOwner).append("（经 :").append(r.groupOwnerPort)
                    .append(" 判定）");
        } else {
            sb.append("★ 未探到组主 —— 确认已连车机热点 / P2P，且车机已上电。");
        }
        r.summary = sb.toString();
        return r;
    }

    /**
     * 解析该用哪个车机地址：先按端口握手候选主机，返回第一个可连的；
     * 都连不上则返回默认（{@link #CAR_HOSTS}[0]，即 192.168.0.1）作为兜底。
     * <b>阻塞</b>。port 传该业务实际要用的端口。
     */
    public static String resolveCarHost(int port) {
        for (String host : CAR_HOSTS) {
            if (portOpen(host, port, 1200, null)) {
                return host;
            }
        }
        return CAR_HOSTS[0];
    }

    /** 网络诊断接口（放 worker 线程调用）。 */
    public interface DiagListener {
        void onStage(String s);

        void onResult(DiagReport report);
    }

    /**
     * 异步跑一次网络诊断。
     *
     * <p>★ 回调一律切回<b>主线程</b>派发：诊断本身阻塞（TCP 握手），必须放 worker 线程，
     * 但宿主回调里全是 UI 操作（setText / 回填输入框 / 状态胶囊）。
     * 若回调留在 {@code car-net-diag} 线程，宿主一碰 View 就抛
     * {@code ViewRootImpl$CalledFromWrongThreadException} 并**直接闪退**
     * （2026-10-19 诊断页点「一键连接」的崩溃实证：DiagActivity.setLink 被 worker 线程调用）。
     * 线程切换放在这里，所有调用方（OTA / 投屏 / 诊断三页）一次修好。
     */
    public static void diagnoseAsync(final Context ctx, final DiagListener l) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                main.post(new Runnable() {
                    @Override public void run() {
                        l.onStage("网络诊断中…（TCP 握手，不发业务数据）");
                    }
                });
                final DiagReport r = diagnose(ctx);
                main.post(new Runnable() {
                    @Override public void run() {
                        l.onResult(r);
                    }
                });
            }
        }, "car-net-diag").start();
    }

    // ==================== 系统 WLAN 直连（连接交给系统设置） ====================

    /**
     * 打开系统 WLAN 设置 —— 「系统 Wi-Fi 直连」的动作入口。
     * 用户在系统里连车机 SoftAP（{@code ZEEHO-xxxx}，优先 5GHz），
     * 回本模块后点「网络诊断」回填地址即可。
     */
    public static void openSystemWifiSettings(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception ignored) {
            try {
                a.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored2) {
            }
        }
    }

    /**
     * 系统 WLAN 面板（API 29+）。{@link #openSystemWifiSettings} 的快捷版，
     * 某些系统上直接落到 Wi-Fi 列表，步数更少。
     */
    public static void openWifiPanel(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Intent i = new Intent(Settings.Panel.ACTION_WIFI);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        openSystemWifiSettings(a);
    }

    /**
     * 断开当前 Wi-Fi，让系统能干净地连上车机热点（截图流程的第一步「断开所有 Wi-Fi」）。
     *
     * <p>只调 {@link WifiManager#disconnect()} 断开<b>当前</b>连接，不断开也忘记其它网络
     * （不破坏用户其它 Wi-Fi 配置）；断开后系统会自行连上优先级更高的车机热点。</p>
     */
    public static boolean disconnectCurrentWifi(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return false;
            return wm.disconnect();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统定位服务是否开启（Android 9+ 扫 Wi-Fi / 部分直连需要定位）。 */
    public static boolean isLocationOn(Context ctx) {
        try {
            int mode = Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.LOCATION_MODE);
            return mode != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Throwable t) {
            return true; // 读不到就不拦
        }
    }

    /** 打开系统定位设置（引导用）。 */
    public static void openLocationSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    // ==================== 车机 AP 扫描（含 5GHz 识别） ====================

    /** 一个车机 AP 的扫描信息。 */
    public static final class ApInfo {
        public String ssid;
        public int frequency;   // MHz
        public boolean is5GHz;

        ApInfo(String ssid, int frequency) {
            this.ssid = ssid;
            this.frequency = frequency;
            this.is5GHz = frequency >= 4900;
        }
    }

    /** 5GHz 判定（5180/5500 等；4900 以上即 5GHz 段）。 */
    public static boolean is5G(int freqMhz) {
        return freqMhz >= 4900;
    }

    /**
     * 扫描附近 {@code ZEEHO-*} 车机 AP，同 SSID 多 BSSID 时保留频段更高的那个（优先 5GHz）。
     * 需要 {@code ACCESS_WIFI_STATE} + 位置权限（API 27+ 起扫描结果要定位）。
     */
    public static List<ApInfo> scanCarAps(Context ctx) {
        Map<String, ApInfo> best = new LinkedHashMap<>();
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return new ArrayList<>();
            try { wm.startScan(); } catch (Exception ignored) { }
            List<ScanResult> res = wm.getScanResults();
            if (res != null) {
                for (ScanResult r : res) {
                    String ssid = r.SSID;
                    if (ssid == null || !ssid.startsWith(CarWifi.SSID_PREFIX)) continue;
                    ApInfo info = new ApInfo(ssid, r.frequency);
                    ApInfo prev = best.get(ssid);
                    // 同 SSID 保留频段更高（5GHz 优先）的那条
                    if (prev == null || info.frequency > prev.frequency) {
                        best.put(ssid, info);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(best.values());
    }

    /** 附近是否扫到 5GHz 的车机 AP（系统直连时优先连 5GHz）。 */
    public static boolean carApIs5G(Context ctx) {
        for (ApInfo a : scanCarAps(ctx)) {
            if (a.is5GHz) return true;
        }
        return false;
    }
}
