package com.github.zeehospeedhunter.diag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ZEEHO ECU 全景登记表 —— 忠实移植自 zeeho-diag {@code lib/ecus.js}（汇集本项目 research 成果）。
 *
 * <p>来源：ZeeCare 逆向（VCDM=1 / MCU=2 为 DoIP 逻辑地址，已真机验证）+
 * {@code tools/diagpc/doip.py} + docs/10、docs/14 的各 ECU CAN 逻辑地址。</p>
 *
 * <h3>关键事实（docs/14 实证）</h3>
 * <ul>
 *   <li>RS485 总线（OBD 针 7/15 @115200 8N1）是主通道；CAN 500k 是旁路。</li>
 *   <li>RS485 上跑私协议 {@code 43 46 … 45 44}（见 {@link Rs485}），不是 UDS；
 *       WiFi/DoIP 通道才是 UDS。本表同时收录两条路径的地址。</li>
 * </ul>
 *
 * <p>寻址模型：{@code doipDa} = ZeeCare DoIP 诊断目标逻辑地址（仅 VCDM/MCU 已验证）；
 * {@code canReq/canRes} = CAN 侧逻辑地址。其它 ECU 的 {@code doipDa} 未知 ——
 * {@link #resolveDa} 会回退 canId 并打 {@code verified:false} 警告，绝不臆造地址。</p>
 */
public final class Ecus {

    private Ecus() {
    }

    public static final class Ecu {
        public final String key;
        public final String label;
        public final String role;
        public final Integer doipDa;   // null = 未知
        public final Integer canReq;   // null
        public final Integer canRes;   // null
        public final boolean verified;
        public final String note;

        Ecu(String key, String label, String role, Integer doipDa, Integer canReq, Integer canRes,
            boolean verified, String note) {
            this.key = key;
            this.label = label;
            this.role = role;
            this.doipDa = doipDa;
            this.canReq = canReq;
            this.canRes = canRes;
            this.verified = verified;
            this.note = note;
        }
    }

    public static final class Resolved {
        public int da;
        public Integer canReq;
        public Integer canRes;
        public boolean verified;
        public String label;
        public String key;   // 可能为 null（按裸地址下发时）
        public String warn;  // 非空 = 该研究地址未经真机验证
    }

    // ---------- 登记表（与 ecus.js 逐条一致） ----------

    public static final Ecu[] LIST = {
            new Ecu("vcdm", "VCDM 整车控制器", "车辆中央诊断网关/身份聚合",
                    1, null, null, true,
                    "ZeeCare DoIP 逻辑地址 1；承载 VIN/软件版本/零件号/供应商/里程/IMEI/NFC卡数 等身份 DID。"),
            new Ecu("mcu", "MCU 电机控制器", "电机驱动/FOC/扭矩",
                    2, 0x721, 0x7A1, true,
                    "ZeeCare DoIP 逻辑地址 2；CAN 0x721/0x7A1（docs/10）。datastreams 的 mcu/mcuLite 组落在此 ECU。"),
            new Ecu("dash", "DASH 仪表", "仪表/整车显示",
                    null, 0x714, 0x794, false,
                    "CAN 0x714/0x794（doip.py）。DoIP 逻辑地址未知，未验证网关是否接受。"),
            new Ecu("labms", "LABMS 低压电池管理", "12V 蓄电池管理",
                    null, 0x722, 0x7A2, false,
                    "CAN 0x722/0x7A2（doip.py）。DoIP 逻辑地址未知。"),
            new Ecu("bms", "BMS 动力电池管理", "高压电池包/单体/SOC/SOH",
                    null, null, null, false,
                    "datastreams 的 bms/bms2/vcu 组（DID 0D37~0D63）落在此类 ECU。具体 CAN 地址 docs 未单列，需扫描确认。"),
            new Ecu("bms2", "BMS2 副电池管理", "第二电池管理节点",
                    null, 0x723, null, false,
                    "CAN 0x723（doip.py）。"),
            new Ecu("chg", "CHG 充电机", "车载充电机/充电协商",
                    null, 0x724, null, false,
                    "CAN 0x724（doip.py）。"),
            new Ecu("abs", "ABS 防抱死制动", "制动防抱死/ABS排气",
                    null, 0x7E1, 0x7E9, false,
                    "CAN 0x7E1/0x7E9（doip.py）。ABS 排气例程(0x31)落在此 ECU；routine ID 需实测抓取，未硬编码。"),
            new Ecu("tbox", "TBOX 联网盒", "蜂窝联网/远程",
                    null, 0x717, null, false,
                    "CAN 0x717（doip.py）。"),
            new Ecu("brs", "BRS 后雷达", "倒车雷达",
                    null, 0x727, null, false,
                    "CAN 0x727（doip.py）。对应 RS485 应用 cmd 0x8B（后雷达）。"),
    };

    private static final Map<String, Ecu> BY_KEY = new LinkedHashMap<>();
    private static final Map<Integer, Ecu> BY_DOIP = new LinkedHashMap<>();

    static {
        for (Ecu e : LIST) BY_KEY.put(e.key, e);
        for (Ecu e : LIST) if (e.doipDa != null) BY_DOIP.put(e.doipDa, e);
    }

    public static Ecu byKey(String key) {
        return key == null ? null : BY_KEY.get(key.trim().toLowerCase());
    }

    /**
     * 解析目标地址（移植 resolveDa）。
     * 接受：ECU key（如 "abs"）、数字 DoIP 逻辑地址（如 2）、或 0x 字符串。
     */
    public static Resolved resolveDa(Object input) {
        Resolved def = defaultResolved();
        if (input == null) return def;

        Integer da = null;
        Ecu ecu = null;
        if (input instanceof String) {
            String s = ((String) input).trim().toLowerCase();
            ecu = BY_KEY.get(s);
            if (ecu == null) {
                if (s.startsWith("0x")) {
                    try { da = Integer.parseInt(s.substring(2), 16); } catch (NumberFormatException ignored) {}
                } else if (s.matches("\\d+")) {
                    da = Integer.parseInt(s, 10);
                }
            }
        } else if (input instanceof Number) {
            da = ((Number) input).intValue();
        }

        if (ecu != null) {
            Resolved r = new Resolved();
            r.key = ecu.key;
            r.label = ecu.label;
            r.canReq = ecu.canReq;
            r.canRes = ecu.canRes;
            r.verified = ecu.verified;
            r.da = ecu.doipDa != null ? ecu.doipDa : (ecu.canReq != null ? ecu.canReq : 1);
            if (ecu.doipDa == null) {
                r.warn = "「" + ecu.label + "」的 DoIP 逻辑地址未知（仅 CAN 地址 0x"
                        + Integer.toHexString(ecu.canReq != null ? ecu.canReq : 0).toUpperCase()
                        + " 已知，未验证网关是否接受此地址）";
            } else {
                r.warn = "";
            }
            return r;
        }

        if (da != null) {
            Ecu known = BY_DOIP.get(da);
            Resolved r = new Resolved();
            r.da = da;
            if (known != null) {
                r.canReq = known.canReq;
                r.canRes = known.canRes;
                r.verified = true;
                r.label = known.label;
                r.key = known.key;
                r.warn = "";
            } else {
                r.canReq = null;
                r.canRes = null;
                r.verified = false;
                r.label = "DA=" + da;
                r.key = null;
                r.warn = "DoIP 逻辑地址 " + da + " 不在已验证表(1=VCDM,2=MCU)内，按原始地址下发，可能无响应。";
            }
            return r;
        }
        return def;
    }

    private static Resolved defaultResolved() {
        Resolved r = new Resolved();
        r.da = 1;
        r.canReq = null;
        r.canRes = null;
        r.verified = true;
        r.label = "VCDM(默认)";
        r.key = "vcdm";
        r.warn = "";
        return r;
    }

    /** Spinner 展示用：key + 概要一行。 */
    public static String[] spinnerLabels() {
        String[] out = new String[LIST.length];
        for (int i = 0; i < LIST.length; i++) {
            Ecu e = LIST[i];
            String addr = e.doipDa != null
                    ? ("DoIP " + e.doipDa)
                    : (e.canReq != null ? ("CAN 0x" + Integer.toHexString(e.canReq).toUpperCase() + " ⚠") : "地址未知 ⚠");
            out[i] = e.label + "  ·  " + addr;
        }
        return out;
    }

    public static String[] spinnerKeys() {
        String[] out = new String[LIST.length];
        for (int i = 0; i < LIST.length; i++) out[i] = LIST[i].key;
        return out;
    }
}
