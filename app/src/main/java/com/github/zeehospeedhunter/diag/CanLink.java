package com.github.zeehospeedhunter.diag;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * CAN 直连链路（ISO-TP over 蓝牙透传）—— 移植自 zeeho-diag iOS 版 {@code diag.html} 的 CAN 段。
 *
 * <p>与 DoIP 的本质区别：DoIP 走车机做网关（TCP 13400），CAN 直连是<b>手机经蓝牙透传模块
 * 直接挂到车辆 CAN 总线上收发 11/29 位帧</b>，不经过车机、也不需要连车机热点。
 * 换胎压传感器、清 ABS 泵这类只有总线层能做的事走这条链路。</p>
 *
 * <h3>透传模块的 BLE 帧格式（与 iOS 版一致）</h3>
 * <pre>
 *   ID_H ID_L DLC D0 D1 D2 D3 D4 D5 D6 D7
 *   └ 11/29 位 ID ─┘ └DLC┘ └── 8 字节数据场 ──┘
 * </pre>
 * <p>DLC 固定 8（经典 CAN 帧最大数据长度）。</p>
 *
 * <h3>ISO-TP（ISO 15765-2）分帧</h3>
 * <p>UDS 请求超过 7 字节必须拆帧，否则总线只发前 7 字节、ECU 收到残缺请求直接丢弃：</p>
 * <ul>
 *   <li>单帧（≤7 字节）：<code>0x0N | D0..D6</code>，N = 数据长度，低位补 0；</li>
 *   <li>首帧（FF，&gt;7）：<code>0x1N | (len&gt;&gt;8 &amp; 0x0F) | len &amp; 0xFF | 前 6 字节</code>；</li>
 *   <li>流控帧（FC）：<b>收到首帧后必须立即回一帧</b>，否则 ECU 不会继续发；</li>
 *   <li>连续帧（CF）：<code>0x2N | 每帧 7 字节</code>，N 从 1 递增，<b>循环回绕（&amp; 0x0F）</b>。</li>
 * </ul>
 *
 * <h3>流控帧参数</h3>
 * <p>固定发 {@code 30 08 14 00 00 00 00 00}：</p>
 * <ul>
 *   <li>{@code 0x30} = FC，序列号固定 0（首帧后的第一个流控帧）；</li>
 *   <li>{@code 0x08} = BS(Block Size)，每次连续帧间隔最多再收 8 帧；</li>
 *   <li>{@code 0x14} = STmin = 20ms（≥10ms 的十六进制表示），帧间最小间隔；</li>
 *   <li>剩余全 0。</li>
 * </ul>
 *
 * <p>与 iOS 版完全一致。收到错误方向的流控（超时的 FC）会导致 ECU 停在首帧等待，
 * 表现为「发了请求但永远没回包」，是这块最容易踩的坑。</p>
 */
public final class CanLink {

    private CanLink() {
    }

    /** 单帧最大数据长度。 */
    public static final int SF_MAX = 7;
    /** 数据场固定 8 字节。 */
    public static final int DATA_LEN = 8;
    /** ISO-TP 多帧总长上限（0x0FFF）。 */
    public static final int TP_MAX = 4095;
    /** 发给 ECU 的流控帧（BS=8, STmin=20ms）。 */
    public static final byte[] FLOW_CONTROL = {
            (byte) 0x30, (byte) 0x08, (byte) 0x14, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /** UDS 否定响应码中文表（与 iOS 版 NRC_TEXT 一致）。 */
    public static final java.util.Map<Integer, String> NRC_TEXT = new java.util.LinkedHashMap<>();

    static {
        NRC_TEXT.put(0x10, "通用拒绝");
        NRC_TEXT.put(0x11, "服务不支持");
        NRC_TEXT.put(0x12, "子功能不支持");
        NRC_TEXT.put(0x13, "长度错误");
        NRC_TEXT.put(0x22, "条件不满足");
        NRC_TEXT.put(0x31, "请求超出范围");
        NRC_TEXT.put(0x33, "安全拒绝（需先解锁）");
        NRC_TEXT.put(0x35, "无效密钥");
        NRC_TEXT.put(0x78, "响应挂起（ECU 忙，稍后重试）");
    }

    /**
     * UDS 请求 → ISO-TP 帧序列（每个元素是 8 字节数据场的 hex）。
     *
     * <p>与 iOS 版 {@code isotpFrames} 逐行等价：≤7 字节发单帧，&gt;7 字节发首帧 + N 个连续帧。
     * 「&gt;7 字节必须分帧」不是优化而是硬要求 —— 不拆的话 ECU 只拿到前 7 字节，
     * 会以 NRC 0x13（长度错误）或干脆静默丢弃回应答。</p>
     *
     * @return 每帧的 8 字节数据场 hex；输入非法时返回空列表
     */
    public static List<String> isotpFrames(byte[] uds) {
        List<String> out = new ArrayList<>();
        if (uds == null || uds.length == 0 || uds.length > TP_MAX) return out;

        if (uds.length <= SF_MAX) {
            // 单帧：[len] + 数据 + 补 0 到 8
            byte[] d = new byte[DATA_LEN];
            d[0] = (byte) uds.length;
            System.arraycopy(uds, 0, d, 1, uds.length);
            out.add(BleRs485.bytesToHex(d));
            return out;
        }

        // 首帧 FF：[0x1|lenHi][lenLo][前 6 字节]
        byte[] ff = new byte[DATA_LEN];
        ff[0] = (byte) (0x10 | ((uds.length >> 8) & 0x0F));
        ff[1] = (byte) (uds.length & 0xFF);
        System.arraycopy(uds, 0, ff, 2, 6);
        out.add(BleRs485.bytesToHex(ff));

        // 连续帧 CF：每帧 7 字节，序列号从 1 开始并循环（& 0x0F）
        int idx = 6;
        byte seq = 1;
        while (idx < uds.length) {
            byte[] cf = new byte[DATA_LEN];
            cf[0] = (byte) (0x20 | (seq & 0x0F));
            int n = Math.min(7, uds.length - idx);
            System.arraycopy(uds, idx, cf, 1, n);
            out.add(BleRs485.bytesToHex(cf));
            idx += 7;
            seq++;
        }
        return out;
    }

    public static List<String> isotpFrames(String udsHex) {
        return isotpFrames(BleRs485.hexToBytes(udsHex));
    }

    /**
     * 组装透传模块的 BLE 帧：{@code ID_H ID_L DLC D0..D7}。
     *
     * @param canId 11/29 位 CAN ID
     * @param data8  8 字节数据场（长度不足自动补 0，超长截断）
     */
    public static String frame(int canId, byte[] data8) {
        String idHex = String.format("%04X", canId & 0xFFFF);
        byte[] d = new byte[DATA_LEN];
        if (data8 != null) System.arraycopy(data8, 0, d, 0, Math.min(DATA_LEN, data8.length));
        return idHex + "08" + BleRs485.bytesToHex(d);
    }

    /** 组装透传帧（数据场用 hex 串传入）。 */
    public static String frame(int canId, String data8Hex) {
        return frame(canId, BleRs485.hexToBytes(data8Hex));
    }

    /** 一条解析出的 CAN 帧。 */
    public static final class Frame {
        public final int id;
        public final byte[] data;
        public final int dlc;

        Frame(int id, byte[] data, int dlc) {
            this.id = id;
            this.data = data;
            this.dlc = dlc;
        }

        /** UDS 未分帧的数据场（不含 PCI 首字节）。 */
        public byte[] singleFrameUds() {
            int pci = data[0] & 0xFF;
            int len = pci & 0x0F;
            if (len == 0 || len > data.length - 1) return new byte[0];
            byte[] o = new byte[Math.min(len, data.length - 1)];
            System.arraycopy(data, 1, o, 0, o.length);
            return o;
        }

        public boolean isFirstFrame() {
            return data.length > 0 && (data[0] & 0xF0) == 0x10;
        }

        public boolean isConsecutiveFrame() {
            return data.length > 0 && (data[0] & 0xF0) == 0x20;
        }

        public boolean isSingleFrame() {
            return data.length > 0 && (data[0] & 0xF0) == 0x00;
        }

        public boolean isFlowControl() {
            return data.length > 0 && (data[0] & 0xF0) == 0x30;
        }

        /** 首帧声明的总长度。 */
        public int declaredTotal() {
            if (!isFirstFrame() || data.length < 2) return 0;
            return ((data[0] & 0x0F) << 8) | (data[1] & 0xFF);
        }
    }

    /**
     * 解析透传模块上行的一帧。
     *
     * @return 解析结果；字节不足 3 字节（DLC 位缺失）时返回 null
     */
    public static Frame parseFrame(byte[] raw) {
        if (raw == null || raw.length < 3) return null;
        int id = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
        int dlc = raw[2] & 0xFF;
        int n = Math.min(dlc, DATA_LEN);
        byte[] data = new byte[n];
        System.arraycopy(raw, 3, data, 0, Math.min(n, Math.max(0, raw.length - 3)));
        return new Frame(id, data, dlc);
    }

    /** ISO-TP 重组器：把 SF/FF/CF 拼回完整 UDS 报文。 */
    public static final class Reassembler {
        private int expected;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean active;

        /** 复位（换请求或连接断开时调用，避免上一条残留污染下一条）。 */
        public void reset() {
            expected = 0;
            buf.reset();
            active = false;
        }

        public boolean isActive() {
            return active;
        }

        /**
         * 喂一帧进去。
         *
         * @return 若本次喂入后重组完成，返回完整 UDS 字节；否则 null
         */
        public byte[] feed(Frame f) {
            if (f == null || f.data == null || f.data.length == 0) return null;
            if (f.isSingleFrame()) {
                reset();
                return f.singleFrameUds();
            }
            if (f.isFirstFrame()) {
                reset();
                expected = f.declaredTotal();
                int take = Math.min(6, f.data.length - 2);
                buf.write(f.data, 2, take);
                active = true;
                return buf.size() >= expected ? finish() : null;
            }
            if (f.isConsecutiveFrame()) {
                if (!active) return null;   // 没收到首帧就收到 CF：丢弃（总线上可能有噪声）
                buf.write(f.data, 1, Math.min(7, f.data.length - 1));
                active = true;
                return buf.size() >= expected ? finish() : null;
            }
            // 流控帧（上行偶有 ECU 主动发的 FC），对重组无意义，忽略
            return null;
        }

        private byte[] finish() {
            byte[] out = buf.toByteArray();
            reset();
            return out;
        }
    }

    /**
     * UDS 响应解码为一句人话（与 iOS 版 {@code canUdsDecode} 同覆盖）。
     *
     * @return 解码文本；无法识别服务号时返回原始 hex
     */
    public static String decodeUds(byte[] uds) {
        if (uds == null || uds.length == 0) return "(空响应)";
        int svc = uds[0] & 0xFF;
        if (svc == 0x7F) {
            int requestSvc = uds.length > 1 ? (uds[1] & 0xFF) : 0;
            int nrc = uds.length > 2 ? (uds[2] & 0xFF) : 0;
            String t = NRC_TEXT.get(nrc);
            return "否定响应: 服务 " + String.format("%02X", requestSvc)
                    + "  NRC " + String.format("%02X", nrc) + " " + (t == null ? "" : t);
        }
        if (svc == 0x6F) {
            return "写 DID " + String.format("%02X%02X", uds[1] & 0xFF, uds[2] & 0xFF) + " 成功";
        }
        if (svc == 0x62) {
            return "读 DID " + String.format("%02X%02X", uds[1] & 0xFF, uds[2] & 0xFF)
                    + " = " + BleRs485.bytesToHex(sliceFrom(uds, 3));
        }
        if (svc == 0x50) {
            return "会话变更成功（0x" + String.format("%02X", uds.length > 1 ? uds[1] : 0) + "）";
        }
        if (svc == 0x54) {
            return "清码成功";
        }
        if (svc == 0x71) {
            return "例程控制响应 " + BleRs485.bytesToHex(sliceFrom(uds, 1));
        }
        if (svc == 0x22) {
            return "由 0x22 升级数据响应（服务 0x22 的子功能号在第 2 字节："
                    + String.format("%02X", uds.length > 1 ? uds[1] : 0) + "）";
        }
        return "UDS 响应: " + BleRs485.bytesToHex(uds);
    }

    private static byte[] sliceFrom(byte[] a, int from) {
        if (from >= a.length) return new byte[0];
        byte[] o = new byte[a.length - from];
        System.arraycopy(a, from, o, 0, o.length);
        return o;
    }
}
