package com.github.zeehospeedhunter.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.github.zeehospeedhunter.diag.BleRs485;
import com.github.zeehospeedhunter.diag.CanLink;
import com.github.zeehospeedhunter.diag.Rs485;
import com.github.zeehospeedhunter.diag.VehicleRoutines;

import java.util.List;
import java.util.function.Consumer;

/**
 * 车辆链路控制器 —— 把三条互斥的物理链路统一成一套接口，对齐 zeeho-diag iOS 版
 * {@code diag.html} 的 {@code linkMode} 设计。
 *
 * <h3>三种链路模式</h3>
 * <pre>
 *   RS485   蓝牙透传 → 车辆 RS485 针脚（OBD 7/15）    私协议 43 46…45 44
 *   CAN     蓝牙透传 → 车辆 CAN 总线                  ISO-TP（15765-2）分帧
 *   WIFI    Wi-Fi → 车机 DoIP 网关 TCP 13400           ISO 13400 + UDS
 * </pre>
 * <p>RS485 与 CAN <b>共用同一条 BLE 连接</b>（同一个透传模块，只是发出去的帧格式不同），
 * 所以切换这两者不会断连重连；切到 WIFI 才换链路。</p>
 *
 * <h3>为什么值得做（iOS 版带来的认知）</h3>
 * <p>桌面版只有 DoIP 一条路，必须连车机热点才能诊断。iOS 版证明了 <b>BLE 透传直连总线</b>
 * 这条路可行，于是「换胎压传感器」「清 ABS 泵」「NFC 学卡」这些<b>不经过车机网关</b>
 * 的操作成为可能 —— 车机网关根本不转发这类总线级例程。</p>
 *
 * <p>本类只管链路与状态机，不碰 UI；所有阻塞/回调都切回主线程。</p>
 */
public final class VehicleLink {

    /** 链路模式（与 iOS 版 linkMode 同名同义）。 */
    public enum Mode {
        /** 蓝牙透传 → RS485（私协议）。 */
        RS485("RS485", "蓝牙 → 车辆 RS485（OBD 7/15 针）"),
        /** 蓝牙透传 → CAN 总线（ISO-TP）。 */
        CAN("CAN", "蓝牙 → CAN 总线（ESP32-CANE）"),
        /** Wi-Fi → 车机 DoIP。 */
        WIFI("WIFI", "Wi-Fi → 车机 DoIP 13400");

        public final String tag;
        public final String desc;

        Mode(String tag, String desc) {
            this.tag = tag;
            this.desc = desc;
        }
    }

    /** 状态变化回调（全部在主线程）。 */
    public interface Callback {
        /** 链路状态。 */
        void onLinkState(String stateText, boolean ready);

        /** 一条日志（可直接进日志区）。 */
        void onLinkLog(String line);

        /** 收到上游数据（hex）。 */
        void onLinkData(String hex);
    }

    /** 一键连接搜索时长（与 iOS 版 autoConnect 的 3.5s 对齐）。 */
    private static final long AUTO_SCAN_MS = 3500L;

    private final Context ctx;
    private final Callback cb;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final BleRs485 ble;
    private final CanLink.Reassembler canReassembler = new CanLink.Reassembler();

    private Mode mode = Mode.RS485;
    /** 上一个请求的 CAN ID，收到首帧要回流控（必须记在请求侧）。 */
    private int lastCanReqId;
    /** 上一个请求的 CAN ID（用于 CAN 模式的日志标注）。 */
    private volatile boolean scanning;

    public VehicleLink(Context ctx, Callback cb, SharedPreferences prefs) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
        this.prefs = prefs;
        this.ble = new BleRs485(this.ctx, this::onBleEvent);
    }

    public Mode mode() {
        return mode;
    }

    public BleRs485 ble() {
        return ble;
    }

    public void setMode(Mode m) {
        this.mode = m;
        cb.onLinkLog("链路模式切到 " + m.tag + "（" + m.desc + "）");
        if (m != Mode.WIFI) {
            canReassembler.reset();
        }
    }

    public boolean isReady() {
        if (mode == Mode.WIFI) return false;   // WIFI 侧由 DiagClient 负责，不在此判
        return ble.isReady();
    }

    // ==================== 一键连接（移植 autoConnect） ====================

    /**
     * 一键连接：扫 3.5 秒 → 优先选名含 {@code ZEEHO-CAN}/{@code 6328} 的设备，
     * 否则取信号最强（RSSI 最高）的一个 → 连接。
     *
     * <p>挑选规则与 iOS 版 {@code autoConnect} 逐条一致。这两个名字前缀对应
     * ESP32-CANE（CAN 总线模块）与 I6328A/VG6328A（RS485 串口模块）。</p>
     *
     * <p><b>与 WIFI 模式互斥</b>：WIFI 走车机热点，不需要蓝牙；此时调用本方法
     * 会提示切回 RS485/CAN。</p>
     */
    public void autoConnect() {
        if (mode == Mode.WIFI) {
            cb.onLinkLog("当前是 WIFI 模式（连车机热点），不需要蓝牙连接");
            return;
        }
        if (!BleRs485.hasPermission(ctx)) {
            cb.onLinkState("缺蓝牙权限", false);
            cb.onLinkLog("⚠ 缺「附近设备」权限，BLE 扫描不可用。"
                    + "Android 12+ 需在系统设置里授予 BLUETOOTH_SCAN/CONNECT");
            return;
        }
        String st = ble.stateName();
        if (!"poweredOn".equals(st)) {
            cb.onLinkState("蓝牙" + st, false);
            cb.onLinkLog("✖ 蓝牙未就绪：" + st + "（请打开手机蓝牙）");
            return;
        }
        cb.onLinkState("搜索中…", false);
        cb.onLinkLog("一键连接：搜索 BLE 透传模块（3.5 秒）…");
        scanning = true;
        ble.startScan(null);
        main.postDelayed(this::finishAutoConnect, AUTO_SCAN_MS);
    }

    private void finishAutoConnect() {
        if (!scanning) return;
        scanning = false;
        ble.stopScan();
        BleRs485.Device pick = ble.pickBest();
        if (pick == null) {
            cb.onLinkState("未发现设备", false);
            cb.onLinkLog("未发现设备：确认模块已上电（通电 + 指示灯闪），"
                    + "或用「扫描设备列表」手动连");
            return;
        }
        String why = pick.preferred ? "命中已知模块名" : "取信号最强";
        cb.onLinkLog("选中设备：" + pick.name + "（" + pick.id + "，RSSI " + pick.rssi + "，" + why + "）");
        cb.onLinkState("正在连接 " + pick.name + "…", false);
        ble.connect(pick.id);
    }

    public void disconnect() {
        scanning = false;
        ble.disconnect();
        canReassembler.reset();
        cb.onLinkState("已断开", false);
    }

    /** 请求 BLE 运行时权限（Android 12+）。 */
    public static String[] permissions() {
        return BleRs485.requiredPermissions();
    }

    // ==================== 发送 ====================

    /**
     * RS485 模式：发一条应用帧（自动补 {@code 4346…4544} 头尾与校验）。
     *
     * <p>与 iOS 版 {@code buildFrame} 逐字节一致：{@code 43 46 + sys + 40 + payload + CK + 45 44}，
     * 校验为 {@code sys..payload} 全部字节之和取低 8 位。</p>
     *
     * @param sysHex   系统码 2 位 hex（如 {@code 89} = TBOX/配置）
     * @param payloadHex 负载 hex（可空）
     */
    public boolean sendRs485Frame(String sysHex, String payloadHex) {
        if (!ble.isReady()) {
            cb.onLinkLog("✖ 未连接透传模块，先点「一键连接」");
            return false;
        }
        String s = sysHex == null ? "" : sysHex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (s.length() != 2) {
            cb.onLinkLog("✖ 系统码需为 2 位十六进制");
            return false;
        }
        String p = payloadHex == null ? "" : payloadHex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        cb.onLinkLog("TX " + buildFrame(s, p) + "  [" + describeSys(s) + "]");
        return ble.sendHex(buildFrame(s, p));
    }

    /**
     * 构建 RS485 应用帧：{@code 4346 + sys + 40 + payload + CK + 4544}。
     *
     * <p>与 iOS 版 {@code buildFrame} 完全一致（逐字对照）。</p>
     */
    public static String buildFrame(String sysHex, String payloadHex) {
        String body = sysHex + "40" + payloadHex;
        int ck = 0;
        byte[] bytes = BleRs485.hexToBytes(body);
        for (byte b : bytes) ck = (ck + (b & 0xFF)) & 0xFF;
        return "4346" + body + String.format("%02X", ck) + "4544";
    }

    /** 系统码语义（与 iOS 版 SYS 表一致；另补 iOS 版没有但 Android Rs485 已收录的码）。 */
    public static String describeSys(String sysHex) {
        switch (sysHex.toUpperCase()) {
            case "49": return "计量器/BMS";
            case "51": return "BMS 电池";
            case "59": return "MCU 电机";
            case "81": return "仪表/整车";
            case "89": return "TBOX/配置";
            case "8B": return "后雷达";
            default: return "未知系统码";
        }
    }

    /** RS485 模式：发一整条已成帧的 hex（如预置的 VIN 三步帧）。 */
    public boolean sendRaw(String hex) {
        if (!ble.isReady()) {
            cb.onLinkLog("✖ 未连接透传模块，先点「一键连接」");
            return false;
        }
        return ble.sendHex(hex);
    }

    /**
     * CAN 模式：发一条 UDS 请求（自动 ISO-TP 分帧 + 逐帧间隔 30ms）。
     *
     * <p>与 iOS 版 {@code canSend} 一致：帧间隔 30ms（给 ESP32 模块留出串口转发时间，
     * 间隔太小会丢帧）。</p>
     *
     * @param canId 目标诊断 ID
     * @param udsHex UDS 请求
     */
    public boolean sendCan(int canId, String udsHex) {
        if (!ble.isReady()) {
            cb.onLinkLog("✖ 未连接透传模块，先点「一键连接」");
            return false;
        }
        List<String> frames = CanLink.isotpFrames(udsHex);
        if (frames.isEmpty()) {
            cb.onLinkLog("CAN: UDS 无效（空或超 4095 字节）");
            return false;
        }
        lastCanReqId = canId;
        canReassembler.reset();
        cb.onLinkLog(String.format("TX CAN ID=%04X UDS=%s（分 %d 帧）",
                canId, udsHex.toUpperCase(), frames.size()));
        for (int i = 0; i < frames.size(); i++) {
            final String f8 = frames.get(i);
            main.postDelayed(() -> ble.sendHex(CanLink.frame(canId, f8)), i * 30L);
        }
        return true;
    }

    /** CAN 模式：回一个流控帧（收到首帧时必须立刻回，否则 ECU 停住不发后续帧）。 */
    private void sendFlowControl() {
        ble.sendHex(CanLink.frame(lastCanReqId, CanLink.FLOW_CONTROL));
        cb.onLinkLog("TX CAN 流控（BS=8 STmin=20ms）");
    }

    // ==================== 场景序列 ====================

    /** 跑一个多步序列（可中止）。 */
    public void runPlan(VehicleRoutines.SeqPlan plan, Consumer<String> status) {
        new Thread(() -> {
            try {
                for (int i = 0; i < plan.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        status.accept("已停止（泵/阀保持当前状态）");
                        cb.onLinkLog("⛔ 序列已中止：" + plan.title + "（泵/阀保持当前状态，"
                                + "如需复位请到 CAN 预设手动补发「泵 关 / 阀 全关」）");
                        return;
                    }
                    VehicleRoutines.SeqStep st = plan.steps.get(i);
                    status.accept("第 " + (i + 1) + "/" + plan.size() + " 步：" + st.label);
                    sendCan(st.canId, st.udsHex);
                    if (st.isManualHold) {
                        status.accept("第 " + (i + 1) + "/" + plan.size()
                                + " 步：" + st.label + " —— 请配合放气螺丝操作");
                    }
                    Thread.sleep(st.waitMs);
                }
                status.accept("✅ " + plan.title + "完成");
                cb.onLinkLog("✅ " + plan.title + "完成");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                status.accept("✖ " + plan.title + "失败：" + e.getMessage());
                cb.onLinkLog("✖ " + plan.title + " 失败：" + e.getMessage());
            }
        }, "seq-plan").start();
    }

    // ==================== BLE 事件 ====================

    /** BLE 事件统一入口：驱动状态机 + CAN 重组。 */
    private void onBleEvent(String type, String payload) {
        switch (type) {
            case BleRs485.EV_STATE:
                cb.onLinkState("蓝牙" + payload, false);
                break;
            case BleRs485.EV_SCAN_STARTED:
                cb.onLinkState("搜索中…", false);
                break;
            case BleRs485.EV_DEVICE: {
                // payload: mac|name|rssi[|★]
                String[] p = payload.split("\\|", -1);
                cb.onLinkLog("发现设备 " + p[1] + "（" + p[0] + "，RSSI " + p[2] + "）"
                        + (p.length > 3 ? " ★优先" : ""));
                break;
            }
            case BleRs485.EV_CONNECTED:
                if (payload.endsWith("connecting")) {
                    cb.onLinkState("正在连接…", false);
                } else {
                    cb.onLinkState("已连接，发现服务中…", false);
                }
                break;
            case BleRs485.EV_SERVICES:
                cb.onLinkLog("GATT 服务：" + payload);
                break;
            case BleRs485.EV_READY: {
                // payload: writeUuid|notifyUuid|ready
                String[] p = payload.split("\\|", -1);
                boolean ok = p.length > 2 && "true".equals(p[2]);
                cb.onLinkState(ok ? "已就绪 · " + mode.tag : "特征不匹配", ok);
                if (ok) {
                    cb.onLinkLog("✔ 透传模块就绪：写 " + shortUuid(p[0])
                            + " / 通知 " + shortUuid(p[1]) + "，串口默认 115200 8N1");
                } else {
                    cb.onLinkLog("✖ 找不到可写+可通知特征，这可能不是透传模块");
                }
                break;
            }
            case BleRs485.EV_DATA:
                handleUpstream(payload);
                break;
            case BleRs485.EV_TX: {
                String[] p = payload.split("\\|", -1);
                cb.onLinkLog("↑ 已发 " + (p.length > 1 ? p[1] : "?") + " 字节");
                break;
            }
            case BleRs485.EV_DISCONNECTED:
                canReassembler.reset();
                cb.onLinkState("已断开", false);
                cb.onLinkLog("透传模块已断开" + (payload.isEmpty() ? "" : "（" + payload + "）"));
                break;
            case BleRs485.EV_CONNECT_FAILED:
            case BleRs485.EV_ERROR:
                cb.onLinkLog("⚠ " + payload);
                break;
            default:
                break;
        }
    }

    private static String shortUuid(String uuid) {
        if (uuid == null || uuid.isEmpty() || uuid.equals("-")) return "-";
        int dash = uuid.indexOf('-');
        return dash > 0 ? uuid.substring(0, dash) : uuid;
    }

    /** 处理上行数据：CAN 模式做 ISO-TP 重组并解码；RS485 模式走既有解码器。 */
    private void handleUpstream(String payload) {
        String[] p = payload.split("\\|", -1);
        String hex = p[0];
        cb.onLinkData(hex);

        if (mode == Mode.CAN) {
            CanLink.Frame f = CanLink.parseFrame(BleRs485.hexToBytes(hex));
            if (f == null) return;
            if (f.isFlowControl()) {
                cb.onLinkLog("RX CAN 流控（ECU 请求发送）");
                return;
            }
            if (f.isFirstFrame()) {
                // 关键：必须先回流控，ECU 才会继续发连续帧
                sendFlowControl();
            }
            byte[] uds = canReassembler.feed(f);
            if (uds != null) {
                String txt = CanLink.decodeUds(uds);
                cb.onLinkLog("RX CAN ID=" + String.format("%04X", f.id) + " → " + txt);
            }
            return;
        }

        // RS485 模式：用既有解码器把上行流还原成应用帧
        Rs485.DecodeResult r = Rs485.decodeRs485LinkHex(hex);
        if (r.linkFrames > 0) {
            cb.onLinkLog("RX RS485：链路帧 " + r.linkFrames + "，应用帧 " + r.appFrames
                    + (r.xorErrors > 0 ? "，XOR 错 " + r.xorErrors : ""));
        }
    }
}
