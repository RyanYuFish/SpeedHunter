package com.github.speedhunter.ota;

import android.content.Context;
import android.net.Uri;

import com.github.speedhunter.core.HookLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.json.JSONObject;

/**
 * 把 OTA 固件包推给车机 —— {@code socket_l} / {@code 192.168.0.1:10950} 的 Java 实现。
 *
 * <p>Python 原型：{@code tools/ota/push-ota-socket.py}（协议证据见 docs/42、docs/44）。
 * 本类按 <b>2026-10-06 唯一一次真机成功推送</b>的实测序列重写，那次序列是：</p>
 * <pre>
 *   ① 触发 10950（官方投屏 init 主机 → 退出投屏）—— 本类不碰，只负责后续
 *   ② 版本协商 0x10000011  → 必须收到 0x80000011 才继续（硬门控）
 *   ③ OTA 启动  0x10000001  {fileName,fileSize,vehicleType,version,md5} —— fileSize 必须精确
 *   ④ 数据      0x10000005  ×N（head[1]=0）
 *   ⑤ 结束      0x10000003  带 {"recvOtaFileFinishedFlag":1}
 *   ⑥ 等校验请求 0x10000004 → 立刻回 0x80000004（缺这一步车机永远不刷）
 * </pre>
 *
 * <h3>三个历史坑（10/06 实证，成败就在这三处）</h3>
 * <ol>
 *   <li><b>包内分区 MD5（{@code MD5SUM*}）正确</b> —— 否则车机 md5 校验直接失败。
 *       现在由 {@link #inspectPackage} 在推送前体检，自制包缺校验会被拦下。</li>
 *   <li><b>结束帧必须带 {@code recvOtaFileFinishedFlag:1}</b> —— 否则车机静默不回应，
 *       后面 MD5/解压/校验请求一步都不跑。</li>
 *   <li><b>必须自动回 {@code 0x80000004}</b> —— 车机侧只有拿到这个 ack 才
 *       {@code recvVerifyAckFlag,start upgradePack}，否则就是「传完不刷」。</li>
 * </ol>
 *
 * <h3>本版新增的可靠性措施</h3>
 * <ul>
 *   <li><b>持久接收缓冲</b>（10/17 修）：旧实现每次 {@code read} 各自解析，尾部半帧直接丢弃 ——
 *       车机把两个控制帧拆到两个 TCP 段发时，第二帧就凭空消失，表现正是「缺一枪」。</li>
 *   <li><b>版本协商硬门控</b>：没收到 {@code 0x80000011} 就不发 otaStart，
 *       避免白等 280 秒才失败。</li>
 *   <li><b>传输期非阻塞抽水</b>：106MB 传输期间每 2MB 读一次，防止车机回包把
 *       接收缓冲塞满导致 TCP 窗口归零。</li>
 *   <li><b>降级方向校验</b>：车机版本 ≥ 目标 v15 时拒绝推送（除非显式
 *       {@link PushOptions#allowDowngrade}），旧仪表刷新版本是本次的目的，反向不行。</li>
 *   <li><b>守候即推</b>（{@link #pushWhenReady}）：10950 是短命窗口（投屏退出后可能几十秒），
 *       探测到就连同推送在同一连接内完成，不给用户「探测完窗口已关」的竞态留机会。</li>
 * </ul>
 *
 * <p><b>红线</b>：只走 {@code 192.168.0.x:10950}，不碰其它端口；不发送任何非本协议帧；
 * 传输一旦被打断<b>不续传</b>（长度不符车机会拒收尾）。刷写期间不动车机、不关 Wi-Fi。</p>
 */
public final class OtaSocketPusher {

    private static final String TAG = HookLog.OTA;

    public static final String CAR_IP = "192.168.0.1";
    public static final int CAR_PORT = 10950;

    /** 车机侧缓冲上限未知，4096 稳妥（10/06 实测 0.4~0.5 MB/s，全程无异常）。 */
    private static final int CHUNK = 4096;

    /** 传输期每累积这么多字节就抽一次水（读车机回包），防止接收缓冲塞满。 */
    private static final long DRAIN_EVERY_BYTES = 2L * 1024 * 1024;

    private static final int CMD_START = 0x10000001;
    private static final int CMD_PREPARE = 0x10000002;
    private static final int CMD_END = 0x10000003;
    private static final int CMD_DATA = 0x10000005;
    private static final int CMD_VERSION = 0x10000011;

    /**
     * ★★ 车机 → 手机「校验请求」与手机 → 车机「校验确认」（2026-10-06 固件实证）。
     *
     * <p>约定：<b>{@code 0x1xxxxxxx} = 手机发，{@code 0x8xxxxxxx} = 车机应答/主动下发</b>。</p>
     * <pre>
     *   otaVerifyMessage     里出现 0x10000004   （★ 车机主动下发，等手机确认）
     *   CmdObserver::rectData 比较表里有 0x80000004（★ 手机回的确认）
     * </pre>
     *
     * <p>不回这个 ack 的后果：车机侧 {@code slots_recvVerifyAckTimeout} 只会一遍遍重发
     * 「cannot recv verify message ack」，永远走不到 {@code recvVerifyAckFlag,start upgradePack}
     * —— 也就是<b>传完不刷</b>。</p>
     */
    private static final int CMD_OTA_VERIFY = 0x10000004;      // 车机 → 手机
    private static final int CMD_OTA_VERIFY_ACK = 0x80000004;  // 手机 → 车机

    /**
     * 车型。★ 2026-10-06 车机回包实测：
     * {@code ← cmd=0x80000011 {"versionCode":"SW.1.01.15AE5I",...}}
     * ⇒ 这台车是 <b>AE5I（全大写）</b>，写 "AE5i" 会让
     * {@code checkUpgradeRequirements()} 返回 2 ⇒ 仪表收不到进度信号。
     */
    private static final String VEHICLE_TYPE = "AE5I";
    private static final String VERSION = "1.0.1";

    /**
     * 本次目标版本（{@code versionCode} 里的数字段）。10/06 实测车机为 {@code SW.1.01.15AE5I}。
     * 车机版本 &gt; 本值时按「降级」拒绝，避免把新仪表刷回旧固件。
     */
    private static final int TARGET_SW = 15;

    /** 车机候选地址：车机 AP 一般是 192.168.0.1；本机当 P2P GO 时车机是 192.168.49.1。 */
    private static final String[] CAR_HOSTS = {"192.168.0.1", "192.168.49.1"};

    /**
     * 官方投屏会触发 SoftAP↔P2P 切换，10950 的建立/拆除可能晚于界面动作。
     * 10/06 成功记录依赖投屏断开后的短窗口；把守候延长到 10 分钟，覆盖
     * 网络接口重建、EasyConnect 释放槽位和主机服务延迟启动。
     */
    public static final long VEHICLE_WATCH_MS = 600000L;
    private static final int VEHICLE_WATCH_CONNECT_TIMEOUT_MS = 600;
    private static final long VEHICLE_WATCH_RETRY_DELAY_MS = 500L;

    /** 收尾等校验请求的最长时间。10/06 实测 ack 在结束后 ~85s 内到达，留足余量。 */
    public static final long VERIFY_WINDOW_MS = 600000L;

    /** 探测到的车型（车机回包里解析出来的），非空时优先用于 otaStart。 */
    private static volatile String sDetectedVehicleType = null;

    /** 网络诊断探测到的组主地址；非空时优先用它做推送/探测。 */
    private static volatile String sResolvedCarHost = null;

    public static void setResolvedCarHost(String host) {
        sResolvedCarHost = (host == null || host.isEmpty()) ? null : host;
    }

    public static String carHost() {
        return sResolvedCarHost != null ? sResolvedCarHost : CAR_IP;
    }

    /** 探测/连接时的候选顺序：诊断结果优先，再退回两个默认候选。 */
    private static String[] carHostCandidates() {
        String r = sResolvedCarHost;
        if (r == null || r.isEmpty()) {
            return CAR_HOSTS;
        }
        for (int i = 0; i < CAR_HOSTS.length; i++) {
            if (CAR_HOSTS[i].equals(r)) {
                return i == 0 ? new String[]{CAR_HOSTS[0], CAR_HOSTS[1]}
                        : new String[]{CAR_HOSTS[1], CAR_HOSTS[0]};
            }
        }
        return new String[]{r, CAR_HOSTS[0], CAR_HOSTS[1]};
    }

    private OtaSocketPusher() {
    }

    // ============================================================ 包自检

    /**
     * 包的放行结论。
     *
     * <p>★ <b>2026-10-17 重大修正</b>：这一版之前用的是「zip MD5 是否命中已知官方基线」的白名单，
     * 只有 {@code OFFICIAL / CUSTOM / BROKEN} 三档。<b>那是错的</b> ——
     * 白名单对「刷自制固件」这个目标<b>毫无用处</b>：自制包的 MD5 永远不在白名单里，
     * 于是永远判CUSTOM，而CUSTOM 只是个提示、不拦，于是 10/06 那次照样把一个
     * <b>内部不自洽的包</b>推了进去 ⇒ U-Boot 刷到一半 exit -1 ⇒ 变砖。</p>
     *
     * <p>现在改成 {@link OtaPackVerify} 的<b>结构化六项检查</b>（车机/U-Boot 真正会验的那几道）：
     * 内层 {@code MD5SUM*} → main 脚本 uImage 的 dataCRC+headerCRC → 分区表 19 项 MD5
     * → 脚本内 74 个硬编码 MD5。<b>六项全过才放行</b>，与包是不是原厂无关 ——
     * 自制包只要改对了就能刷，原厂包改错了照样拦。</p>
     */
    public enum Grade {
        /** 六项检查全过 —— 包内部自洽，U-Boot 能逐分区回读通过，可以推。 */
        SELF_CONSISTENT,
        /** zip/结构有问题，或某道校验不过 —— 会导致半刷变砖，禁止推送。 */
        UNSAFE,
        /** 与已知原版基线一致（仅作提示，不影响放行判定）。 */
        MATCHES_FACTORY
    }

    /** 一次包自检的结果。 */
    public static final class PkgInfo {
        public File file;
        public long size;
        public String zipMd5 = "";
        public String packMd5 = "";
        public long packSize;
        public Grade grade = Grade.UNSAFE;
        /** 与之匹配到的原版基线名（MATCHES_FACTORY 时有值，仅提示用）。 */
        public String matchedBaseline = "";
        /** 结构化六项检查的完整结论；未跑成功时为 null。 */
        public OtaPackVerify.Result verify;
        public String note = "";

        /** 只有六项检查全过才允许推送。 */
        public boolean pushable() {
            return grade == Grade.SELF_CONSISTENT || grade == Grade.MATCHES_FACTORY;
        }

        public String gradeLabel() {
            switch (grade) {
                case SELF_CONSISTENT:
                    return "✔ 六项检查全过";
                case MATCHES_FACTORY:
                    return "✔ 六项检查全过 · 与原版基线一致";
                default:
                    return "✖ 不可推送（会半刷变砖）";
            }
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(file == null ? "—" : file.getName()).append('\n');
            sb.append(size).append(" 字节   MD5 ").append(zipMd5).append('\n');
            // 内层包大小/MD5 由 verify.summary() 统一给，这里不重复
            if (verify != null) {
                sb.append(verify.summary());
            }
            sb.append("\n判定：").append(gradeLabel());
            if (!matchedBaseline.isEmpty()) {
                sb.append("（").append(matchedBaseline).append("）");
            }
            if (!note.isEmpty()) {
                sb.append('\n').append(note);
            }
            return sb.toString();
        }
    }

    /**
     * 已知<b>原版</b>基线：{@code 内层 GEMINI_PACK.BIN MD5 -> 说明}。
     *
     * <p>★ 只作提示用，<b>不参与放行判定</b>。特别是
     * {@code 1787815351970919959.zip} <b>虽然叫「原厂整车包」，
     * 但它的 dataCRC 与 MD5SUM* 出厂就是错的</b>（实测：
     * MD5SUM* 存 {@code fd2c3809…} 应为 {@code 3b8ac91a…}；
     * dataCRC 存 {@code b171d4d2…} 实际 {@code 67b45acf…}）⇒ 它同样会被六项检查拦下。</p>
     */
    private static final String[][] FACTORY_BASELINES = {
            {"480a44d023cc0d658b592abc0eee0cc4",
             "内层与原厂恢复基准 firmware/gemini_pack/GEMINI_PACK.BIN 逐字节相同"},
            {"f9f9c03de2a60092bbaa0dc918b5ba65", "OSS 整车包的内层（改过 spapp，且校验不自洽）"},
    };

    /**
     * 包自检：解出内层 {@code GEMINI_PACK.BIN}，跑 {@link OtaPackVerify} 的六项检查。
     *
     * <p><b>阻塞</b>（要读 340MB 量级），放 worker 线程。</p>
     */
    public static PkgInfo inspectPackage(File f) {
        return inspectPackage(f, null);
    }

    /** 同上，带阶段回调（界面用来显示进度，避免看起来像卡死）。 */
    public static PkgInfo inspectPackage(File f, OtaPackVerify.Progress stage) {
        PkgInfo info = new PkgInfo();
        info.file = f;
        if (f == null || !f.isFile()) {
            info.note = "文件不存在";
            return info;
        }
        info.size = f.length();
        if (info.size < 1024) {
            info.note = "体积异常小（" + info.size + " 字节），几乎不可能是升级包";
            return info;
        }
        info.zipMd5 = md5Of(f);
        if (info.zipMd5.startsWith("md5fail:")) {
            info.note = "读不出包的 MD5（" + info.zipMd5 + "）";
            return info;
        }
        try {
            info.verify = OtaPackVerify.verifyZip(f, stage);
            info.packSize = info.verify.packSize;
            info.packMd5 = info.verify.packMd5;
        } catch (Exception e) {
            info.note = "包解析失败：" + e;
            return info;
        }
        for (String[] row : FACTORY_BASELINES) {
            if (row[0].equalsIgnoreCase(info.packMd5)) {
                info.matchedBaseline = row[1];
                break;
            }
        }
        if (info.verify.allOk()) {
            info.grade = info.matchedBaseline.isEmpty()
                    ? Grade.SELF_CONSISTENT : Grade.MATCHES_FACTORY;
            return info;
        }
        info.grade = Grade.UNSAFE;
        info.note = "✖ " + info.verify.failedCount() + " 项检查不通过。"
                + "U-Boot 会在写完若干分区后 exit -1，留下半刷状态 —— "
                + "这正是 10/06 变砖的机制。必须先用 tools/flashpack/fix_pack_md5.py "
                + "和 fix_script_crc.py 把包改自洽。";
        return info;
    }

    // ============================================================ 车辆信息

    /** 车辆探测结果。 */
    public static final class VehicleInfo {
        public boolean ok;
        public String host = CAR_IP;
        public String localIp;      // 本机在车机网段的地址
        public boolean tcpOk;       // 10950 是否可连
        public String versionCode;  // 如 SW.1.01.15AE5I
        public String vin;
        public String vehicleType;  // 从 versionCode 推出的车型，如 AE5I
        public int sw = -1;         // versionCode 里的软件版本号（15）
        public String code;         // 车机回包的 code
        public String error;
        public String hint;

        public String summary() {
            if (!ok) {
                return "✖ " + error + "\n" + hint;
            }
            return "✔ 已连上 " + host + ":" + CAR_PORT
                    + "（本机 " + localIp + "）\n"
                    + "车型 : " + nz(vehicleType) + "\n"
                    + "版本 : " + nz(versionCode) + "\n"
                    + "VIN  : " + nz(vin);
        }

        /** 是否已经是目标版本或更新（再刷就是降级）。 */
        public boolean isNewerThanTarget() {
            return sw > TARGET_SW;
        }

        private static String nz(String s) {
            return (s == null || s.isEmpty()) ? "—" : s;
        }

        /** 用车机回包 JSON 填自己。 */
        void applyVersion(JSONObject o) {
            if (o == null) {
                return;
            }
            versionCode = o.optString("versionCode", "");
            vin = o.optString("vin", "");
            code = o.optString("code", "");
            vehicleType = guessVehicleType(versionCode);
            sw = parseSw(versionCode);
        }
    }

    /** 车辆探测回调。 */
    public interface VehicleProbeListener {
        void onStage(String s);

        void onResult(VehicleInfo info);
    }

    /**
     * <b>持续探测车辆</b>：在 10 分钟窗口内反复检查活动网卡与 10950，发现通道后再做版本协商。
     *
     * <p>车机回包（实测）：{@code ← cmd=0x80000011
     * （示例值为脱敏占位；VIN 属车辆敏感信息，不写进代码。）</p>
     */
    public static void probeVehicle(final VehicleProbeListener l) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final VehicleInfo info = new VehicleInfo();
                long deadline = System.currentTimeMillis() + VEHICLE_WATCH_MS;
                long lastStatusLog = 0;
                String lastStatus = "";
                String latestProbe = "尚未探测";
                boolean attemptedHost = false;
        l.onStage("开始持续探测 10950（最多 " + (VEHICLE_WATCH_MS / 1000)
                + " 秒；只做连接与版本查询，不推送 OTA）。"
                        + "★ 固件事实（Ghidra 实证 libotamodule.so）：10950 的 bind+listen "
                        + "只在 OTAModule::initial() 里创建，而 initial() 是 spapp 模块生命周期函数；"
                        + "投屏(EasyConnect)只走到 ecModuleInit() 连信号槽，**不会**拉起 10950。"
                        + "所以「投屏正常但 10950 一直拒连」是固件层必然，不是操作问题。"
                        + "★ 另一条已验证可用的通道是 10086（由仪表 Key OTA 拉起 cfmoto_P2p_server），"
                        + "它收裸 GEMINI_PACK.BIN + 3 字节命令并经 ioctl 触发刷写（见 docs/55）。"
                        + "★ 任何网络抖动（开关 Wi-Fi、拆 P2P 组）都会经 slots_otaWifiDisconnectedState "
                        + "推动 disConnectPhone，把 10950 拆掉，所以探测期间不要动网络。");

                // ★ 把网络形态打到日志上：投屏能不能成，先看这一句
                String shape = networkShapeHint();
                if (!shape.isEmpty()) {
                    l.onStage(shape);
                }

                while (System.currentTimeMillis() < deadline && !info.tcpOk) {
                    String network = carNetworkSummary();
                    StringBuilder pass = new StringBuilder();
                    for (String h : carHostCandidates()) {
                        String sourceIp = localIpForHost(h);
                        if (sourceIp == null) {
                            if (pass.length() > 0) pass.append("；");
                            pass.append(h).append(" 跳过(无同 /24 网卡)");
                            continue;
                        }
                        attemptedHost = true;
                        Link link = null;
                        try {
                            link = Link.connect(h, sourceIp, VEHICLE_WATCH_CONNECT_TIMEOUT_MS);
                            info.host = h;
                            info.localIp = sourceIp;
                            info.tcpOk = true;
                            l.onStage("发现 " + h + ":" + CAR_PORT + " 可连接，源地址 "
                                    + sourceIp + "；进行只读版本协商 …");
                            if (negotiateQuiet(link, h, 2500, info)) {
                                break;
                            }
                        } catch (Exception e) {
                            latestProbe = h + ": " + summarizeConnectError(e);
                            if (pass.length() > 0) pass.append("；");
                            pass.append(latestProbe);
                            info.tcpOk = false;
                            info.host = CAR_IP;
                            info.localIp = null;
                        } finally {
                            if (link != null) {
                                link.close();
                            }
                        }
                    }

                    long now = System.currentTimeMillis();
                    String status = network + " | " + (pass.length() == 0 ? "等待接口" : pass.toString());
                    if (!status.equals(lastStatus) || now - lastStatusLog >= 5000) {
                        l.onStage("监听中：" + status);
                        lastStatus = status;
                        lastStatusLog = now;
                    }
                    if (!info.tcpOk && now < deadline) {
                        try {
                            Thread.sleep(VEHICLE_WATCH_RETRY_DELAY_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (!info.tcpOk) {
                    NetInfo ni = localNetOnCarNet();
                    info.localIp = ni != null ? ni.ip : null;
                    info.error = attemptedHost
                            ? "持续探测 " + (VEHICLE_WATCH_MS / 1000)
                                    + " 秒仍未发现 10950 监听（最近：" + latestProbe + "）"
                            : "持续探测期间手机没有 192.168.0.x / 192.168.49.x 活动地址";
                    info.hint = "10950 只有两条来路（10/17 逆向闭环）：① 仪表/主机上电时 OTAModule::initial "
                            + "② EasyConnect 会话 Connected 后 ecModuleInit()。\n"
                            + "① 先按 Set+下 10 秒进 OTA Update 窗口（窗口不唤起 EasyConnect）。\n"
                            + "②官方 App 投屏一次并干净退出 —— 只有 ecModuleInit 能拉起 10950。\n"
                            + "另外确认手机连的是车机 AP 且所在网段与所选车机地址同 /24。";
                    l.onResult(info);
                    return;
                }
                if (info.versionCode == null || info.versionCode.isEmpty()) {
                    info.ok = true;   // 通道是通的，只是车机没回版本
                    info.hint = "10950 已连接但未收到版本帧；推送会被版本协商门控拦住，先让车机回包";
                    l.onResult(info);
                    return;
                }
                if (info.vehicleType != null) {
                    sDetectedVehicleType = info.vehicleType;
                }
                info.ok = true;
                l.onResult(info);
            }
        }, "vehicle-probe").start();
    }

    /** 把 P2P 与 SoftAP 地址列出来，便于持续探测时看到网卡是否在切换。 */
    private static String carNetworkSummary() {
        StringBuilder summary = new StringBuilder();
        try {
            Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String ip = addrs.nextElement().getHostAddress();
                    if (ip != null && (ip.startsWith("192.168.0.")
                            || ip.startsWith("192.168.49.")) && ip.indexOf('%') < 0) {
                        if (summary.length() > 0) summary.append(", ");
                        summary.append(ni.getName()).append('=').append(ip);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return summary.length() > 0 ? summary.toString() : "无车机网段网卡";
    }

    /**
     * 当前是否有<b>任一</b>落在车机网段（192.168.0.x / 192.168.49.x）的网卡。
     *
     * <p>用它来决定「要不要替用户自动建 P2P」：一个都没有，说明手机既没连 AP、
     * 也没建成 P2P 组，此时连 192.168.0.1 一定失败。</p>
     */
    public static boolean hasCarNetIface() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String ip = addrs.nextElement().getHostAddress();
                    if (ip != null && ip.indexOf('%') < 0
                            && (ip.startsWith("192.168.0.") || ip.startsWith("192.168.49."))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 当前网络<b>形态</b>判断 —— 投屏能不能成功就取决于它。
     *
     * <p>★★ 2026-10-17 现场实证（dumpsys wifip2p 铁证）：<strong>官方 App 的投屏
     * 走 Wi-Fi Direct P2P，不是 AP</strong>：
     * {@code DISCOVER_PEERS → CONNECT → P2P_GROUP_STARTED_EVENT → IPC_PROVISIONING_SUCCESS}
     * 全部来自 {@code com.cfmoto}。所以投屏期间<strong>绝不能拆 P2P 组</strong> ——
     * 我曾按「先断开好投屏」的假设做了个守卫循环，结果在官方 App 建组成功 1 秒后
     * 就把它 REMOVE_GROUP 掉，EC 会话被掐断。</p>
     *
     * @return 给日志用的一句话形态描述 + 建议
     */
    public static String networkShapeHint() {
        boolean hasP2p = false;
        boolean hasWlanOnCar = false;
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName();
                boolean onCarNet = false;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String ip = addrs.nextElement().getHostAddress();
                    if (ip != null && ip.indexOf('%') < 0
                            && (ip.startsWith("192.168.0.") || ip.startsWith("192.168.49."))) {
                        onCarNet = true;
                    }
                }
                if (!onCarNet) {
                    continue;
                }
                if (name != null && name.startsWith("p2p")) {
                    hasP2p = true;
                } else if (name != null && name.startsWith("wlan")) {
                    hasWlanOnCar = true;
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        if (hasP2p && !hasWlanOnCar) {
            return "当前在 P2P 组上（p2p0）"
                    + " ⇒官方 App 20:18 实测走的是 Wi-Fi Direct（自己建组，全程不碰 wlan0），"
                    + "**投屏期间不要拆这个组**。"
                    + "⚠ 但 10/06 唯一成功的那次，投屏前手机走的是 **wlan0（连车机 AP）**，"
                    + "退出后才落到 p2p0。"
                    + "若本轮 P2P 投屏后 10950 仍拒连，请改走 AP 路：断开 P2P → 系统 WLAN 连车机 AP"
                    + " → 再投屏，看官方 App 是否复用 wlan0。";
        }
        if (hasWlanOnCar && !hasP2p) {
            return "当前连在车机 AP 上（wlan0）"
                    + " ⇒官方 App 投屏要求先断开这个手动连接（否则和它自己的 P2P 抢），"
                    + "断开后让它自己建 P2P 组即可。";
        }
        if (hasWlanOnCar) {
            return "当前 AP 与 P2P 同时在线 ⇒ 投屏/推送都应可用。";
        }
        return "★ 没有任何 192.168.0.x / 192.168.49.x 网卡 ⇒ 手机还没连上车机网络。";
    }

    /** 移除异常里的临时源端口，防止持续探测把每次 TCP 源端口当成新状态刷屏。 */
    private static String summarizeConnectError(Exception e) {
        String message = e == null ? "" : e.getMessage();
        if (message != null && message.contains("Connection refused")) {
            return "ECONNREFUSED";
        }
        if (message != null && (message.contains("timed out") || message.contains("timeout"))) {
            return "连接超时";
        }
        if (message != null && message.contains("No route to host")) {
            return "无路由";
        }
        return e == null ? "连接失败" : e.getClass().getSimpleName();
    }

    // ============================================================ 链路

    /**
     * 一条 socket 链路 + <b>持久接收缓冲</b>。
     *
     * <p>★ 10/17 修的正是这里：旧实现是「每次 read 各自解析一遍，尾部不足一整帧的字节丢掉」。
     * 车机完全可能把两个控制帧拆进两个 TCP 段（{@code 0x10000004} 只有几十字节，
     * 和后面的大包体很容易分在两次写里），那样第二帧就凭空消失 ——
     * 现象就是 10/06 之前那个「校验请求偶尔不来、缺一枪」。</p>
     */
    private static final class Link {
        final Socket s;
        final ByteArrayOutputStream rx = new ByteArrayOutputStream();

        private Link(Socket s) {
            this.s = s;
        }

        static Link connect(String host, String srcIp, int timeoutMs) throws Exception {
            Socket s = new Socket();
            if (srcIp != null) {
                // ★ 必须绑源地址：Android 多网络下 default network（蜂窝）会抢路
                s.bind(new InetSocketAddress(srcIp, 0));
            }
            s.connect(new InetSocketAddress(host, CAR_PORT), timeoutMs);
            // 小包别攒在 Nagle 里：控制帧都是几十字节，等一个 RTT 会让协议显得「没回包」
            try {
                s.setTcpNoDelay(true);
                s.setSendBufferSize(1 << 20);
                s.setReceiveBufferSize(1 << 20);
            } catch (Exception ignored) {
            }
            return new Link(s);
        }

        void send(int cmd, byte[] payload, int head1) throws Exception {
            s.getOutputStream().write(frame(cmd, payload, head1));
            s.getOutputStream().flush();
        }

        void send(int cmd, byte[] payload) throws Exception {
            send(cmd, payload, cmd == CMD_DATA ? 0 : 1);
        }

        /**
         * 读最多 {@code waitMs}，把收到的帧全部返回。半帧留在 {@link #rx} 里等下次。
         * 返回值可能为空（超时且没数据），也可能有多帧。
         */
        List<Rx> read(int waitMs) {
            List<Rx> out = new ArrayList<>();
            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[65536];
            try {
                s.setSoTimeout(300);
            } catch (Exception ignored) {
            }
            while (System.currentTimeMillis() < end) {
                int n;
                try {
                    n = s.getInputStream().read(buf);
                } catch (SocketTimeoutException e) {
                    if (!out.isEmpty()) {
                        break;      // 已经读到东西了，不必空等到窗口末端
                    }
                    continue;
                } catch (Exception e) {
                    break;          // 连接断：把 rx 里剩下的解析完就收工
                }
                if (n <= 0) {
                    break;
                }
                rx.write(buf, 0, n);
                drainRxInto(out);
                if (!out.isEmpty()) {
                    break;
                }
            }
            drainRxInto(out);
            return out;
        }

        /** 不阻塞地抽一次水（传输期用，防止车机回包塞满接收缓冲把窗口顶死）。 */
        void readQuick() {
            try {
                s.setSoTimeout(1);
            } catch (Exception ignored) {
                return;
            }
            byte[] buf = new byte[16384];
            try {
                int n = s.getInputStream().read(buf);
                if (n > 0) {
                    rx.write(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
        }

        /** rx 里每有一帧就调一次 handler；留下的半帧不动。 */
        void drainRxInto(List<Rx> out) {
            byte[] all = rx.toByteArray();
            int off = 0;
            while (all.length - off >= 10) {
                int cmd = ((all[off + 2] & 0xFF) << 24) | ((all[off + 3] & 0xFF) << 16)
                        | ((all[off + 4] & 0xFF) << 8) | (all[off + 5] & 0xFF);
                int len = ((all[off + 6] & 0xFF) << 24) | ((all[off + 7] & 0xFF) << 16)
                        | ((all[off + 8] & 0xFF) << 8) | (all[off + 9] & 0xFF);
                if (len < 0 || all.length - off - 10 < len) {
                    break;      // 半帧，留到下次
                }
                byte[] p = new byte[len];
                System.arraycopy(all, off + 10, p, 0, len);
                out.add(new Rx(cmd, p));
                off += 10 + len;
            }
            if (off > 0) {
                byte[] rest = new byte[all.length - off];
                System.arraycopy(all, off, rest, 0, rest.length);
                rx.reset();
                rx.write(rest, 0, rest.length);
            }
        }

        boolean isDead() {
            return s.isClosed() || s.isInputShutdown() || !s.isConnected();
        }

        void close() {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 一个收到的帧。 */
    private static final class Rx {
        final int cmd;
        final byte[] payload;

        Rx(int cmd, byte[] payload) {
            this.cmd = cmd;
            this.payload = payload;
        }

        String text() {
            if (payload == null || payload.length == 0) {
                return "";
            }
            if (payload[0] == '{') {
                return "  " + new String(payload);
            }
            // 非 JSON（比如车机那句中文「服务器忙碌…」）也打出来，否则只看到 len=NN 不知道车机说了什么
            return "  [" + new String(payload).replaceAll("[^\\x20-\\x7e]", ".") + "]";
        }

        JSONObject json() {
            if (payload == null || payload.length == 0 || payload[0] != '{') {
                return null;
            }
            try {
                return new JSONObject(new String(payload));
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ============================================================ 协议基础

    /**
     * 帧头第 2 字节 = <b>通道</b>（2026-10-06 从 {@code CmdObserver::rectData} 的分派表坐实）：
     * <pre>
     *   head[1] == 0 → 只认 0x10000005(数据) / 0x10000006(进度) / 0x10000044
     *   head[1] != 0 → 认 0x10000001 start / 02 prepare / 03 end / 07 cancel
     *                   0x10000011 version / 0x80000004 校验ack / 0x10000043/45 壁纸
     * </pre>
     * 也就是说：<b>控制帧必须 head[1]=1，数据帧必须 head[1]=0</b>。
     * 一直全填 0 ⇒ start/end/version 全被丢进「不认」的分支，FILE* 没开、门控没开
     * ⇒ 106MB 数据被 {@code wirteOtaData} 静默丢弃 —— 车机从头到尾零反应。
     */
    private static byte[] frame(int cmd, byte[] payload, int head1) {
        byte[] f = new byte[10 + payload.length];
        f[0] = 0;
        f[1] = (byte) head1;
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

    private static byte[] json(JSONObject o) {
        return o.toString().getBytes();
    }

    /**
     * 打日志，但<b>绝不让日志本身把协议流程打断</b>。
     *
     * <p>★ 2026-10-06 车边实测：{@code String.format("%.0fs", long)} 抛
     * {@code IllegalFormatConversionException}，把 100% 之后本该发出的「结束传输」帧一并带没了
     * —— 数据其实发完了，日志却报失败。</p>
     */
    private static void safeLog(Callback cb, String fmt, Object... args) {
        try {
            cb.onLog(String.format(fmt, args));
        } catch (Exception e) {
            try {
                cb.onLog(fmt + "（日志格式化出错：" + e + "）");
            } catch (Exception ignored) {
            }
        }
    }

    /** 回调：日志实时输出 + 进度 + 结束时汇总。 */
    public interface Callback {
        void onLog(String line);

        /** 传输进度。{@code percent<0} 表示阶段日志（不是百分比）。 */
        default void onProgress(int percent, long sent, long total) {
        }

        void onDone(boolean ok, String summary);
    }

    /**
     * 静默版本协商（探测用）：发一帧、解析回包，不打印。
     * 拿不到 {@code 0x80000011} 就返回 false。
     */
    private static boolean negotiateQuiet(Link link, String host, int waitMs, VehicleInfo out) {
        List<Rx> got;
        try {
            link.send(CMD_VERSION, json(new JSONObject()
                    .put("versionCode", 1)
                    .put("SP_CONFIG_MODEL_GEMINI_PROJECT_CODE", host)));
            got = link.read(waitMs);
        } catch (Exception e) {
            return false;
        }
        boolean saw = false;
        for (Rx r : got) {
            if (r.cmd == 0x80000011) {
                saw = true;
                out.host = host;
                out.applyVersion(r.json());
            }
        }
        return saw && out.versionCode != null && !out.versionCode.isEmpty();
    }

    /** 版本协商 + 打印 + 填 VehicleInfo；失败返回 false。 */
    private static boolean negotiateInto(Link link, String host, Callback cb, VehicleInfo out) {
        List<Rx> got;
        try {
            cb.onLog(String.format("→ 版本协商  cmd=0x%08X", CMD_VERSION));
            link.send(CMD_VERSION, json(new JSONObject()
                    .put("versionCode", 1)
                    .put("SP_CONFIG_MODEL_GEMINI_PROJECT_CODE", host)));
            got = link.read(3000);
        } catch (Exception e) {
            cb.onLog("   ! 版本协商发送失败：" + e);
            return false;
        }
        boolean ok = false;
        for (Rx r : got) {
            cb.onLog(String.format("   ← cmd=0x%08X len=%d%s", r.cmd, r.payload.length, r.text()));
            if (r.cmd == 0x80000011) {
                out.host = host;
                out.applyVersion(r.json());
                ok = true;
            }
        }
        if (!ok) {
            cb.onLog("   ✖ 没收到 0x80000011 版本回包 —— 车机不在 OTA 状态，"
                    + "此时往下推 106MB 只是白等 5 分钟。");
            cb.onLog("     · 确认仪表 OTA Update 浮窗开着、电量 ≥20%、车机没在忙升级");
            cb.onLog("     · 10950 若为 CLOSED：官方 App 投屏一次再干净退出（窗口/Key OTA 都不拉 10950）");
            return false;
        }
        if (out.code != null && !out.code.isEmpty() && !"1".equals(out.code)) {
            cb.onLog("   ✖ 车机版本回包 code=" + out.code + "（期望 1）—— 拒绝继续");
            return false;
        }
        if (out.vehicleType != null) {
            sDetectedVehicleType = out.vehicleType;
        }
        cb.onLog("   ✔ 车型 " + out.vehicleType + " / 版本 " + out.versionCode
                + " / VIN " + (out.vin.isEmpty() ? "—" : out.vin));
        return true;
    }

    /** 从 versionCode 推车型：{@code SW.1.01.15AE5I} → {@code AE5I}。 */
    private static String guessVehicleType(String versionCode) {
        if (versionCode == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Za-z]{2}[0-9][A-Za-z0-9]*)$").matcher(versionCode);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** 从 versionCode 抽软件版本号：{@code SW.1.01.15AE5I} → {@code 15}。 */
    private static int parseSw(String versionCode) {
        if (versionCode == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^SW\\.[0-9]+\\.[0-9]+\\.([0-9]+)").matcher(versionCode);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** otaStart 用的车型：探测到的优先，兜底 AE5I。 */
    private static String vehicleType() {
        return sDetectedVehicleType != null ? sDetectedVehicleType : VEHICLE_TYPE;
    }

    /**
     * 结束帧的负载。
     *
     * <p>★ 2026-10-06 第二个坑：车机是从<b>这个 JSON</b> 里读
     * {@code recvOtaFileFinishedFlag} 的（固件串：
     * {@code recvOtaFileFinishedFlag false,should not verify file}）。
     * 只发 {@code {"s_code":0,"code":0}} ⇒ 车机判 false ⇒ 直接返回，
     * 后面的 MD5 校验 / 解压 / 校验请求<b>一步都不会跑</b>。</p>
     */
    private static byte[] endPayload() throws Exception {
        return json(new JSONObject()
                .put("recvOtaFileFinishedFlag", 1)
                .put("s_code", 0)
                .put("code", 0));
    }

    /** 发一帧、收回包。<b>没回包不中断</b>（宽松模式）。 */
    private static boolean step(Link link, int cmd, byte[] payload, String label,
                                int waitMs, Callback cb, int tries) {
        for (int attempt = 1; attempt <= Math.max(1, tries); attempt++) {
            String extra = tries > 1 ? "（第 " + attempt + " 次）" : "";
            cb.onLog(String.format("→ %s%s  cmd=0x%08X  %d B", label, extra, cmd, payload.length));
            if (payload.length > 0 && payload[0] == '{') {
                cb.onLog("  " + new String(payload));
            }
            try {
                link.send(cmd, payload);
            } catch (Exception e) {
                cb.onLog("   ! 发送失败：" + e);
                return false;
            }
            List<Rx> got = link.read(waitMs);
            for (Rx r : got) {
                cb.onLog(String.format("   ← cmd=0x%08X len=%d%s", r.cmd, r.payload.length, r.text()));
            }
            if (!got.isEmpty()) {
                return true;
            }
            cb.onLog("   （无回包，继续）");
        }
        return false;
    }

    /**
     * 收尾监听：车机任何下发帧都打出来；遇到 {@code 0x10000004}（otaVerifyMessage）
     * 按 {@code autoFlash} 决定要不要回 {@code 0x80000004}。
     *
     * <p>★ <b>只有收到这个 ack，车机才会 {@code start upgradePack}</b>（车机侧日志串
     * {@code recvVerifyAckFlag,start upgradePack}，来自 {@code slots_recvVerifyAckTimeout}）。
     * 所以 {@code autoFlash=false} 时<b>照样听、只是不回 ack</b> ——
     * 包已经完整落到 {@code /media/flash/userdata/update.zip}，但车机停在等确认，
     * 不会进 U-Boot。这是「先送包、后决定刷不刷」的那一档。</p>
     *
     * @param autoFlash 是否回 ack（＝是否触发刷写）
     * @return 发出的 ack 次数
     */
    private static int waitVerifyAndAck(Link link, Callback cb, long timeoutMs,
                                        boolean autoFlash) {
        int acks = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        if (autoFlash) {
            cb.onLog("→ 等车机校验请求（最多 " + timeoutMs / 1000 + "s），收到就回 ack …");
        } else {
            cb.onLog("→ 等车机校验请求（最多 " + timeoutMs / 1000
                    + "s）；★ 本次不回 ack —— 包会留在 userdata，但不会触发刷写");
        }
        while (System.currentTimeMillis() < deadline) {
            if (link.isDead()) {
                cb.onLog("   ! 连接已断，停止等待");
                break;
            }
            List<Rx> got = link.read(3000);
            for (Rx r : got) {
                cb.onLog(String.format("   ← cmd=0x%08X len=%d%s", r.cmd, r.payload.length, r.text()));
                if (r.cmd != CMD_OTA_VERIFY) {
                    continue;
                }
                acks++;
                if (!autoFlash) {
                    cb.onLog("   ★ 收到校验请求，但「传完自动触发刷写」是关的 → 不回 ack");
                    cb.onLog("     车机会一直重发校验请求并停在 OTA Update，不会进 U-Boot。");
                    cb.onLog("     若确认要刷，把开关打开再推一次，或用「补 ack」补发。");
                    continue;
                }
                cb.onLog("   ★ 收到校验请求，回 ack（车机靠这个才 start upgradePack）");
                try {
                    link.send(CMD_OTA_VERIFY_ACK, json(new JSONObject().put("recvVerifyAckFlag", 1)));
                    cb.onLog("   → ack 已发  cmd=0x80000004");
                } catch (Exception e) {
                    cb.onLog("   ! ack 发送失败：" + e);
                }
            }
            if (acks > 0 && !autoFlash) {
                // 已确认车机在等 ack 且我们不回，继续等没有新信息量，提前收
                cb.onLog("   （已确认车机在等确认且本档不触发刷写，提前结束等待）");
                break;
            }
        }
        if (acks == 0) {
            cb.onLog("   ⚠ 没等到车机的校验请求 —— 检查车机是否停在 OTA Update、电量 ≥20%");
        }
        return acks;
    }

    // ============================================================ 工具

    /** 本机在车机网段的一张网卡：接口名 + IP。 */
    public static final class NetInfo {
        public String iface;   // 如 wlan0 / p2p0 / wlan1
        public String ip;      // 192.168.0.x
    }

    /** 挑一张车机网段上的本机网卡（投屏/P2P 时是 p2p0，连 SoftAP 时是 wlan0）。 */
    public static NetInfo localNetOnCarNet() {
        NetInfo fallback = null;
        try {
            Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String ip = addrs.nextElement().getHostAddress();
                    if (ip != null && (ip.startsWith("192.168.0.") || ip.startsWith("192.168.49."))) {
                        NetInfo info = new NetInfo();
                        info.iface = ni.getName();
                        info.ip = ip;
                        if (info.iface.startsWith("p2p")) return info;
                        if (fallback == null) fallback = info;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** 找与指定车机地址同 /24 的活动本机地址，避免混用 SoftAP 与 P2P 网卡。 */
    private static String localIpForHost(String host) {
        if (host == null) return null;
        int dot = host.lastIndexOf('.');
        if (dot < 0) return null;
        String prefix = host.substring(0, dot + 1);
        String fallback = null;
        try {
            Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String ip = addrs.nextElement().getHostAddress();
                    if (ip != null && ip.startsWith(prefix) && ip.indexOf('%') < 0) {
                        if (ni.getName().startsWith("p2p")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** 兼容旧调用：只返回 IP。 */
    public static String localIpOnCarNet() {
        NetInfo n = localNetOnCarNet();
        return n != null ? n.ip : null;
    }

    /**
     * 本机是不是当前 P2P 组的组主。
     *
     * <p>用来在界面上说明「本机角色」：不是组主就意味着仪表是组主、
     * 仪表地址就是组主地址。反之（手机当组主）推送要连手机自己那张网卡的地址，
     * 写错就连不上。</p>
     */
    public static boolean isLocalGroupOwner() {
        return sLocalGroupOwner;
    }

    private static volatile boolean sLocalGroupOwner;

    /** 由 {@link #setGroupOwner} 顺带更新，供界面显示「本机角色」。 */
    public static void setGroupOwner(String host, boolean localIsOwner) {
        if (host != null && !host.isEmpty()) {
            setResolvedCarHost(host);
        }
        sLocalGroupOwner = localIsOwner;
    }

    /** 探一下 10950 是否真的在监听（纯 TCP connect，不发任何帧）。 */
    public static boolean probeLink() {
        Link link = null;
        try {
            String ip = localIpOnCarNet();
            if (ip == null) {
                return false;
            }
            link = Link.connect(CAR_IP, ip, 2500);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (link != null) {
                link.close();
            }
        }
    }

    /** 纯 TCP 可达性探测，供 P2P 裸服务（10086）等只读诊断使用。 */
    public static boolean probeTcp(String host, int port, int timeoutMs) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 算文件 MD5。<b>不抛异常</b> —— 读失败时返回 {@code md5fail:…}，
     * 让调用方统一按「拿不到可信MD5」处理，而不是各自 try/catch。
     */
    private static String md5Of(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return md5Of(in);
        } catch (Exception e) {
            return "md5fail:" + e;
        } finally {
            closeQuietly(in);
        }
    }

    private static String md5Of(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================ 对外

    /** 只发版本协商，探一下通不通。 */
    public static void probe(final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                cb.onLog("=== 探测 " + CAR_IP + ":" + CAR_PORT + " ===");
                cb.onLog("本机网卡：" + localIpOnCarNet());
                Link link = null;
                try {
                    String ip = localIpOnCarNet();
                    cb.onLog("源地址绑 " + (ip != null ? ip : "（无同网段地址，用默认路由）"));
                    link = Link.connect(carHost(), ip, 8000);
                    cb.onLog("已连接 " + link.s.getLocalSocketAddress()
                            + " → " + link.s.getRemoteSocketAddress());
                    step(link, CMD_VERSION, json(new JSONObject()
                                    .put("versionCode", 1)
                                    .put("SP_CONFIG_MODEL_GEMINI_PROJECT_CODE", carHost())),
                            "版本协商", 3000, cb, 1);
                } catch (Exception e) {
                    cb.onLog("连不上 —— " + e);
                    cb.onLog("  · 超时/拒绝 ⇒ 车机 EasyConnect 没起来（先投屏，或用隐藏组合键进 OTA Update）");
                    cb.onLog("  · 连上后收到中文「服务器忙碌…」⇒ 被官方 App 占用，先断官方投屏");
                } finally {
                    if (link != null) {
                        link.close();
                    }
                }
                cb.onDone(true, "探测结束");
            }
        }, "ota-probe").start();
    }

    /**
     * <b>纯监听 + 自动 ack</b>：连上之后<b>一个字节都不主动发</b>，只听车机下发。
     *
     * <p>用途：车机可能还在慢慢算 MD5 / 解压 106MB，等它缓过来才发
     * {@code 0x10000004}；此时新开一条连接<b>什么都不发</b>、只等它开口，
     * 收到立刻回 ack —— 比再推一遍 4.5 分钟划算得多。</p>
     */
    public static void listenOnly(final Callback cb, final long ms) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                cb.onLog("=== 纯监听 " + (ms / 1000) + "s（不发任何帧，只等车机要确认）===");
                Link link = null;
                try {
                    link = Link.connect(carHost(), localIpOnCarNet(), 8000);
                    waitVerifyAndAck(link, cb, ms, true);
                } catch (Exception e) {
                    cb.onLog("连不上 —— " + e);
                } finally {
                    if (link != null) {
                        link.close();
                    }
                }
                cb.onDone(true, "监听结束");
            }
        }, "ota-listen").start();
    }

    /**
     * <b>只补发「结束传输」帧</b>（{@code 0x10000003}）—— 数据已发完、车机却没动静时的补救动作。
     *
     * <p>★ 为什么安全：不发 {@code otaStart}，车机就不会再
     * {@code fopen64("/media/flash/userdata/update.zip","wb")}，
     * 已写满的那个文件<b>不会被截断、不会被覆盖</b>。</p>
     */
    public static void endOnly(final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                cb.onLog("=== 补发结束帧 ===");
                Link link = null;
                try {
                    link = Link.connect(carHost(), localIpOnCarNet(), 8000);
                    step(link, CMD_VERSION, json(new JSONObject()
                                    .put("versionCode", 1)
                                    .put("SP_CONFIG_MODEL_GEMINI_PROJECT_CODE", carHost())),
                            "版本协商", 1500, cb, 1);
                    step(link, CMD_END, endPayload(), "结束传输", 25000, cb, 1);
                    waitVerifyAndAck(link, cb, 60000, true);
                } catch (Exception e) {
                    cb.onLog("连不上 —— " + e);
                } finally {
                    if (link != null) {
                        link.close();
                    }
                }
                cb.onDone(true, "结束帧已发，看车机屏幕");
            }
        }, "ota-end").start();
    }

    // ============================================================ 推送

    /** 推送参数。 */
    public static final class PushOptions {
        /** 传 {@code 0x10000002} 准备传输帧。10/06 成功那次没发，默认关。 */
        public boolean withPrepare = false;
        /**
         * 允许「降级」推送（车机版本高于包内版本）。
         * 默认关 —— 旧仪表刷 v15 是本次目的，反向（把新仪表刷回旧版）必须显式开。
         */
        public boolean allowDowngrade = false;
        /** 收尾等校验请求的窗口；默认 {@link #VERIFY_WINDOW_MS}。 */
        public long verifyWindowMs = VERIFY_WINDOW_MS;
        /** 已知包体检结论；非空时会打印，推送前再确认一次。 */
        public PkgInfo pkg;
        /**
         * 传完是否<b>自动触发刷写</b>（默认关）。
         *
         * <p>对齐参考工具的同名开关。关掉时只把包写到
         * {@code /media/flash/userdata/update.zip} 并停在等 ack，
         * <b>不回 {@code 0x80000004}</b> —— 车机因此不会 {@code start upgradePack}，
         * 也就不会进 U-Boot。这一档适合「先把包送过去、确认落盘了再决定要不要刷」。
         * 开掉时才回 ack（＝10/06 那条会真的触发刷写的路）。</p>
         */
        public boolean autoFlash = false;
    }

    /** 推送指定包（默认参数）。 */
    public static void push(final File file, final boolean withPrepare, final Callback cb) {
        PushOptions opt = new PushOptions();
        opt.withPrepare = withPrepare;
        push(file, opt, cb);
    }

    /** 推送指定包。 */
    public static void push(final File file, final PushOptions opt, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                runPush(file, opt, cb);
            }
        }, "ota-push").start();
    }

    /**
     * <b>守候即推</b>：在窗口内持续探测 10950，一连上就<b>在同一条连接里</b>
     * 把版本协商 → otaStart → 数据 → 结束帧 → ack 全走完。
     *
     * <p>为什么必须这样：10/06 记录显示 10950 是<b>短命窗口</b> ——官方 App 投屏 init 主机后
     * 才有，退出投屏释放槽位但网络一切换（AP↔P2P）就会被 {@code disConnectPhone} 拆掉。
     * 旧流程「先探测（最长 600s）→ 回到界面 → 用户点推送」中间隔着人的反应时间，
     * 窗口早就关了；现在没有这个缝隙。</p>
     */
    public static void pushWhenReady(final File file, final PushOptions opt, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                cb.onLog("=== 守候即推：持续探测 10950，连上即在同一连接内完成整条推送 ===");
                cb.onLog("触发顺序（★ 顺序是硬的）：上电 → Set+下 10 秒进 OTA Update 窗口"
                        + " →官方 App 投屏一次并干净退出 → 守候连上即推");
                cb.onLog("  · 窗口/Key OTA 只动 10086，不拉 10950");
                cb.onLog("  · 10950 只在退出投屏那一刻由 ecModuleInit 拉起（投屏期间是关的）");
                cb.onLog("  · 推前确认投屏已断开：10950 单客户端，占用时会回「服务器繁忙」");
                long deadline = System.currentTimeMillis() + VEHICLE_WATCH_MS;
                long lastLog = 0;
                String lastStatus = "";
                while (System.currentTimeMillis() < deadline) {
                    String[] hosts = carHostCandidates();
                    for (String h : hosts) {
                        String src = localIpForHost(h);
                        if (src == null) {
                            continue;
                        }
                        Link link = null;
                        try {
                            link = Link.connect(h, src, VEHICLE_WATCH_CONNECT_TIMEOUT_MS);
                        } catch (Exception e) {
                            long now2 = System.currentTimeMillis();
                            if (now2 - lastLog > 5000) {
                                cb.onLog("   等待 10950 … " + h + " " + summarizeConnectError(e));
                                lastLog = now2;
                            }
                            continue;
                        }
                        try {
                            cb.onLog("★ " + h + ":" + CAR_PORT + " 已连上（源 " + src
                                    + "）—— 立即开始推送，不再返回界面");
                            runPushOnLink(link, h, file, opt, cb);
                            return;
                        } catch (Exception e) {
                            cb.onLog("   ! 推送异常：" + e + "（" + (e.getMessage() == null
                                    ? "" : e.getMessage()) + "）");
                            cb.onDone(false, "守候推送中断：" + e);
                            return;
                        } finally {
                            link.close();
                        }
                    }
                    try {
                        Thread.sleep(VEHICLE_WATCH_RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 5000) {
                        cb.onLog("   等待 10950 … 本机 " + carNetworkSummary()
                                + "（" + lastStatus + "）");
                        lastLog = now;
                    }
                }
                cb.onDone(false, "守候 " + (VEHICLE_WATCH_MS / 1000)
                        + " 秒仍未发现 10950。按上面 A/B 两条先让 10950 起来再试。");
            }
        }, "ota-watch-push").start();
    }

    /** 推送主流程（自行建立连接）。 */
    private static void runPush(File file, PushOptions opt, final Callback cb) {
        Link link = null;
        try {
            PkgInfo info = preflight(file, opt, cb);
            if (info == null) {
                cb.onDone(false, "包体检未通过，未发送任何数据");
                return;
            }
            String ip = localIpOnCarNet();
            cb.onLog(ip != null ? "源地址绑 " + ip : "⚠ 未找到车机网段本机地址，用系统默认路由");
            link = Link.connect(carHost(), ip, 8000);
            cb.onLog("已连接 " + link.s.getLocalSocketAddress()
                    + " → " + link.s.getRemoteSocketAddress());
            runPushOnLink(link, carHost(), file, opt, cb);
        } catch (Exception e) {
            cb.onDone(false, "推送失败：" + e);
        } finally {
            if (link != null) {
                link.close();
            }
        }
    }

    /**
     * 包自检 + 打印。返回 null 表示<b>不该推</b>（已通过 cb 告知原因）。
     *
     * <p>★ 这是 10/06 变砖教训的直接落地：协议层全通、车机真的进了刷写，
     * 但包内部不自洽 ⇒ U-Boot 逐分区回读 md5 时 exit -1 ⇒ 主机起不来。
     * <b>车机侧只提示最前面那道 MD5SUM*，后三道失败时仪表毫无信息</b>
     * （进度条停住、没有报错），所以必须在推送前把六项全验完。</p>
     */
    private static PkgInfo preflight(File file, PushOptions opt, Callback cb) {
        if (file == null || !file.isFile()) {
            cb.onLog("✖ 找不到包");
            return null;
        }
        long size = file.length();
        cb.onLog("=== 推送前自检（六项检查，与 U-Boot 同一套）===");
        cb.onLog("  包     : " + file.getAbsolutePath());
        cb.onLog("  大小   : " + size + " 字节");
        if (size < 1024) {
            cb.onLog("  ✖ 体积异常小，未推送");
            return null;
        }
        PkgInfo info = opt.pkg;
        if (info == null) {
            cb.onLog("  算 MD5 并跑六项检查（约 30~90 秒）…");
            info = inspectPackage(file, new OtaPackVerify.Progress() {
                @Override
                public void onStage(String line) {
                    cb.onLog("  " + line);
                }
            });
        }
        if (info == null || info.verify == null) {
            cb.onLog("  ✖ " + (info == null ? "自检未完成" : info.note) + " —— 拒绝推送");
            return null;
        }
        for (OtaPackVerify.Check c : info.verify.checks) {
            cb.onLog(c.line());
        }
        for (String n : info.verify.notes) {
            cb.onLog("  " + n);
        }
        if (!info.matchedBaseline.isEmpty()) {
            cb.onLog("  参考   : 内层与 " + info.matchedBaseline);
        }
        cb.onLog("  判定   : " + info.gradeLabel());
        if (!info.pushable()) {
            cb.onLog("  ✖ " + info.note);
            cb.onLog("  ✖ 拒绝推送（未发送任何数据）。先修包，别拿仪表试。");
            return null;
        }
        // 以本次实算为准，保证 otaStart 里的 md5 与实际发出的字节完全一致
        info.zipMd5 = md5Of(file);
        return info;
    }

    /**
     * 在<b>已建立的连接</b>上跑完整推送。
     *
     * <p>10/06 实测序列，一步不多一步不少：
     * 版本协商 → OTA 启动 → 数据 → 结束帧（带 flag） → 等 0x10000004 回 0x80000004。</p>
     */
    private static void runPushOnLink(Link link, String host, File file, PushOptions opt,
                                      Callback cb) throws Exception {
        long sent = 0;
        long size = file.length();
        PkgInfo info = preflight(file, opt, cb);
        if (info == null) {
            cb.onDone(false, "包体检未通过，未发送任何数据");
            return;
        }
        String name = "update.zip";
        String digest = info.zipMd5;
        cb.onLog("  落盘名 : " + name + "（车机写死 /media/flash/userdata/update.zip）");
        cb.onLog("  块长   : " + CHUNK + "（共 " + (-(-size / CHUNK)) + " 帧）");

        // ② 版本协商（硬门控）
        VehicleInfo car = new VehicleInfo();
        if (!negotiateInto(link, host, cb, car)) {
            cb.onDone(false, "版本协商未通过，未推送数据。"
                    + "车机多半不在 OTA 状态（OTA Update 浮窗 / 电量 / 10950 是否已被网络切换拆掉）。");
            return;
        }
        if (car.isNewerThanTarget() && !opt.allowDowngrade) {
            cb.onLog("✖ 车机版本 SW." + car.sw + " 已高于包内目标 v" + TARGET_SW
                    + " —— 这是降级推送，已拒绝。");
            cb.onDone(false, "拒绝降级：车机 v" + car.sw + " > 目标 v" + TARGET_SW);
            return;
        }
        if (car.isNewerThanTarget()) {
            cb.onLog("⚠ 已显式允许降级：车机 v" + car.sw + " → 目标 v" + TARGET_SW);
        }

        // ③ OTA 启动：车机据此 fopen64("/media/flash/userdata/update.zip","wb")
        step(link, CMD_START, json(new JSONObject()
                        .put("fileName", name)
                        .put("fileSize", size)
                        .put("vehicleType", vehicleType())
                        .put("version", VERSION)
                        .put("md5", digest)),
                "OTA 启动", 6000, cb, 2);

        if (opt.withPrepare) {
            step(link, CMD_PREPARE, json(new JSONObject().put("recvOtaMsgFlag", 1)),
                    "准备传输", 4000, cb, 1);
        }

        // ④ 数据
        cb.onLog("→ 传输数据 …");
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[CHUNK];
            int last = 0;
            long t0 = System.currentTimeMillis();
            long sinceDrain = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] payload = new byte[n];
                System.arraycopy(buf, 0, payload, 0, n);
                link.send(CMD_DATA, payload);
                sent += n;
                sinceDrain += n;
                if (sinceDrain >= DRAIN_EVERY_BYTES) {
                    // 车机在传输期也会回帧（进度/心跳）；不读会让接收缓冲塞满、TCP 窗口归零
                    link.readQuick();
                    sinceDrain = 0;
                }
                int pct = (int) (sent * 100 / size);
                if (pct >= last + 5) {
                    last = pct;
                    double dt = Math.max(System.currentTimeMillis() - t0, 1) / 1000.0;
                    cb.onProgress(pct, sent, size);
                    safeLog(cb, "   %3d%%  %d/%d  %.2f MB/s  ETA %ds",
                            pct, sent, size, sent / dt / 1048576.0,
                            (long) ((size - sent) / Math.max(sent / dt, 1)));
                }
            }
            link.s.getOutputStream().flush();
            safeLog(cb, "   100%%  %d/%d  用时 %.0fs", sent, size,
                    (System.currentTimeMillis() - t0) / 1000.0);
        } finally {
            closeQuietly(in);
        }
        if (sent != size) {
            cb.onDone(false, "实际发出 " + sent + " ≠ 文件 " + size
                    + " 字节。不要续传（长度不符车机会拒收尾），整包重推。");
            return;
        }

        // ⑤ 结束帧（★ 必须带 recvOtaFileFinishedFlag:1）
        step(link, CMD_END, endPayload(), "结束传输", 20000, cb, 1);

        // ⑥ 等 0x10000004，按 autoFlash 决定回不回 0x80000004（不回 = 不触发刷写）
        int requests = waitVerifyAndAck(link, cb, opt.verifyWindowMs, opt.autoFlash);
        int acks = opt.autoFlash ? requests : 0;

        String verdict;
        if (!opt.autoFlash) {
            verdict = requests > 0
                    ? "包已完整落到 /media/flash/userdata/update.zip，车机也已来要确认，"
                    + "但「传完自动触发刷写」是关的 → 未回 ack，★ 不会进 U-Boot。"
                    + "确认要刷时打开开关再推，或用「补 ack」单独补发。"
                    : "包已完整落到 /media/flash/userdata/update.zip；未触发刷写"
                    + "（「传完自动触发刷写」为关，且车机未发校验请求）。";
        } else {
            verdict = acks > 0
                    ? "已回校验确认 " + acks + " 次 —— 车机应已 start upgradePack，进入 U-Boot/ISP 刷写。"
                    : "未收到校验请求，车机多半不会自动开刷。"
                    + "可先盯仪表屏幕；若停在 OTA Update 不动，用「补发结束帧」或「补 ack」再试。";
        }
        cb.onDone(opt.autoFlash ? acks > 0 : true,
                String.format("已发出 %d 字节 / %d 帧。%s", sent, -(-size / CHUNK), verdict));
    }

    // ============================================================ 选包（只用 SAF）

    /**
     * ★ <b>2026-10-17 移除自动定位包</b>
     *
     * <p>原先会扫一批硬编码路径自动预填（含官方 App 的
     * {@code /sdcard/Android/data/com.cfmoto/files/oss/}）。这条设计有两个问题：</p>
     * <ol>
     *   <li><b>Android 11+ 本来就读不到</b> —— 别的应用的 {@code Android/data}
     *       连 {@code stat()} 都不允许，所以它只会误导（界面上「没找到包」，
     *       或像实测那次那样选中一个能 stat 不能 open 的文件）。</li>
     *   <li><b>自动预填 = 推错包的风险</b> —— 刷写是<b>不可逆</b>操作，
     *       「界面自己填的那个包」不该成为默认被推送的对象。</li>
     * </ol>
     *
     * <p><b>现在只走应用内 SAF</b>：用户明确选哪个包就推哪个包，
     * 不猜、不预填、不碰任何固定路径。见 {@link #copyFromUri}。</p>
     */

    /**
     * 把用户在系统文件选择器里选的 content:// URI 拷到私有缓存，返回一个能直读的 File。
     *
     * <p>Android 11+（实测 Android 16 / targetSdk 34）用文件路径读
     * {@code /sdcard/Download/xxx.zip} 会抛 {@code EACCES} —— 非媒体文件不走 MediaStore，
     * 只能走 SAF 或「所有文件访问」。SAF 这条路不需要任何权限。</p>
     */
    public static File copyFromUri(Context ctx, Uri uri) throws Exception {
        File out = new File(ctx.getCacheDir(), "push_pkg.zip");
        if (out.exists()) {
            out.delete();
        }
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new java.io.FileNotFoundException("打不开 " + uri);
        }
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
            if (total == 0) {
                throw new java.io.IOException("选到的文件是空的");
            }
        } finally {
            closeQuietly(os);
            closeQuietly(in);
        }
        return out;
    }
}
