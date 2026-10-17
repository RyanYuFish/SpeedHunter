package com.github.zeehospeedhunter.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UDS 故障码（DTC）解码 —— 把 {@code 19 02} 的原始响应字节解成人能读懂的条目。
 *
 * <p><b>为何需要</b>：{@link VehicleService#readFaultCodes} 只能返回 {@code 19 02} 的原始 UDS 字节。
 * 早期 UI 只把 hex 打出来并提示「自己按 DTC(3B)+状态(1B) 解析」，等于把活儿推给用户。
 * 这里补齐 ISO 14229-1 的解析与 SAE J2012 的码表映射。</p>
 *
 * <h3>帧结构（ISO 14229-1 · 19 02 ReadDTCInformation）</h3>
 * <pre>
 *   59 02 &lt;DTCStatusAvailabilityMask&gt; [ &lt;DTC(3B)&gt; &lt;DTCStatus(1B)&gt; ]*
 * </pre>
 * <ul>
 *   <li>跳过服务号 {@code 59} 与子功能 {@code 02}（请求回的是 +0x40 = 0x59）；</li>
 *   <li>再跳过 1 字节 {@code DTCStatusAvailabilityMask}；</li>
 *   <li>其后每 4 字节一条记录：3 字节 DTC + 1 字节状态。</li>
 * </ul>
 *
 * <h3>3 字节 DTC 的位域</h3>
 * <pre>
 *   byte0 bit7 testFailed
 *   byte0 bit6 testFailedThisOperationCycle
 *   byte0 bit5..0 ┐
 *   byte1        ┴→ 14 位 DTC 号（低 14 位有效）
 *   byte2        → 故障类型字节 FMV（Failure Mode Value，全 8 位）
 * </pre>
 *
 * <h3>DTCStatus 字节位域</h3>
 * <pre>
 *   bit7 testFailed                    bit3 testNotCompletedSinceLastClear
 *   bit6 testFailedThisOperationCycle  bit2 testFailedSinceLastClear
 *   bit5 pendingDTC                    bit1 testNotCompletedThisOperationCycle
 *   bit4 confirmedDTC                  bit0 warningIndicatorRequested
 * </pre>
 *
 * <h3>码表策略（不臆造）</h3>
 * <p>字母前缀按 SAE J2012：{@code P}=动力总成 {@code C}=底盘 {@code B}=车身 {@code U}=网络。
 * 命中 {@link #COMMON} 内置常见码表给中文说明；未命中时按前缀 + 标准范围输出中性描述，
 * <b>不猜具体含义</b>。电子/三电专属码多在厂商码段（第二数字非 0），这类一律标注「厂商码，需查该车型维修手册」。</p>
 */
public final class Dtc {

    private Dtc() {
    }

    /** 一条解码后的故障码。 */
    public static final class Entry {
        /** 形如 {@code P0A1F00} 的归一化 DTC（6 位，含字母前缀）。 */
        public String code = "";
        /** 原始 3 字节 hex。 */
        public String raw = "";
        /** 故障类型字节 FMV（SAE J2012 故障类型表）。 */
        public int failureType;
        /** 高 2 位（byte0 bit7/6）状态。 */
        public boolean testFailed;
        public boolean testFailedThisCycle;
        /** DTCStatus 字节的 8 个状态位。 */
        public boolean pending;
        public boolean confirmed;
        public boolean testNotCompletedSinceClear;
        public boolean testFailedSinceClear;
        public boolean testNotCompletedThisCycle;
        public boolean warningIndicator;
        /** 中文说明；未命中码表时为 null（不臆造）。 */
        public String desc;
        /** 是否来自厂商码段（第二数字非 0）。 */
        public boolean manufacturerCode;

        /** 当前是否「真正影响驾驶」——testFailed 或 confirmed。 */
        public boolean isActive() {
            return testFailed || confirmed;
        }

        /** 状态标签，用于 UI 直接展示。 */
        public String statusLabel() {
            if (testFailed) return "故障";
            if (confirmed) return "已确认";
            if (pending) return "待定";
            if (testFailedThisCycle || testFailedSinceClear) return "本轮故障";
            if (testNotCompletedThisCycle) return "本轮未完成";
            return "历史";
        }

        /** 单行摘要，格式：{@code [P0A1F00] 动力电池 · 状态：待定}。 */
        public String oneLine() {
            return "[" + code + "] " + (desc == null ? "（未收录说明）" : desc)
                    + " · 状态：" + statusLabel();
        }
    }

    /** 整批解码结果。 */
    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();
        /** 有效记录总数（已剔除全 0 填充）。 */
        public int total;
        /** testFailed 或 confirmed 的条数。 */
        public int activeCount;
        public boolean parsed;

        /** 全部为 0 / 无有效记录 → 无故障码。 */
        public boolean isClean() {
            return entries.isEmpty();
        }
    }

    /**
     * 解析 {@code 19 02} 的 UDS 响应（不含 DoIP 8 字节头，即 {@code DiagData.udsData}）。
     *
     * @param udsData 诊断响应数据（应为 {@code 59 02 ...}）
     * @param out     结果容器（复用传入对象，避免额外分配）
     */
    public static Result parse(byte[] udsData, Result out) {
        Result r = (out == null) ? new Result() : out;
        r.entries.clear();
        r.total = 0;
        r.activeCount = 0;
        r.parsed = false;

        // 至少要有 59 02 + 状态掩码 = 3 字节
        if (udsData == null || udsData.length < 3) {
            return r;
        }
        // 服务号校验：0x59(ReadDTCInformation 回响应)。宽松一点，也允许某些网关回 0x7F(否定响应)
        int off = 0;
        if (udsData[0] == 0x7F) {
            // 否定响应：31 02 <NRC>。NRC 0x31 = requestOutOfRange，常见于不支持 02 子功能
            if (udsData.length >= 3) {
                int nrc = udsData[2] & 0xFF;
                r.parsed = true;
                r.entries.clear();
                // 用一条「否定响应」占位，让 UI 能显示原因
                Entry e = new Entry();
                e.code = "NRC" + String.format("%02X", nrc);
                e.raw = "—";
                e.desc = negateResponseText(nrc);
                e.confirmed = false;      // 否定响应不代表「车有故障」
                r.entries.add(e);
                r.total = 1;
                r.activeCount = 0; // 否定响应不是「车有故障」
            }
            return r;
        }
        if ((udsData[0] & 0xFF) != 0x59) {
            return r; // 不是 19 02 的响应，不硬解
        }
        r.parsed = true;
        off = 1; // udsData[1] = 子功能 02
        off++;    // udsData[2] = DTCStatusAvailabilityMask

        for (int i = off; i + 3 < udsData.length; i += 4) {
            int b0 = udsData[i] & 0xFF;
            int b1 = udsData[i + 1] & 0xFF;
            int b2 = udsData[i + 2] & 0xFF;
            int status = (i + 3 < udsData.length) ? (udsData[i + 3] & 0xFF) : 0;

            // 全 0 = 填充/无故障（SAE 规定 DTC 000000 恒为「无故障」）
            if (b0 == 0 && b1 == 0 && b2 == 0) {
                continue;
            }

            Entry e = new Entry();
            e.raw = String.format("%02X%02X%02X", b0, b1, b2);
            // 14 位 DTC 号 = (b0 低 6 位 << 8) | b1
            int num = ((b0 & 0x3F) << 8) | b1;
            e.testFailed = (b0 & 0x80) != 0;
            e.testFailedThisCycle = (b0 & 0x40) != 0;
            e.failureType = b2;

            e.pending = (status & 0x20) != 0;
            e.confirmed = (status & 0x10) != 0;
            e.testNotCompletedSinceClear = (status & 0x08) != 0;
            e.testFailedSinceClear = (status & 0x04) != 0;
            e.testNotCompletedThisCycle = (status & 0x02) != 0;
            e.warningIndicator = (status & 0x01) != 0;

            e.code = formatCode(num);
            e.desc = lookup(e.code);
            e.manufacturerCode = !isStandardRange(e.code);

            r.entries.add(e);
        }

        r.total = r.entries.size();
        for (Entry e : r.entries) {
            if (e.isActive()) r.activeCount++;
        }
        return r;
    }

    public static Result parse(byte[] udsData) {
        return parse(udsData, null);
    }

    /**
     * 14 位 DTC 号 → SAE J2012 的字母 + 4 位数字形式。
     * 映射规则：首位十六进制 nibble 决定字母，其余拼成数字部分。
     */
    public static String formatCode(int num) {
        int n = num & 0x3FFF;
        int first = (n >> 12) & 0x0F;
        char letter;
        switch (first) {
            case 0x0: letter = 'P'; break;
            case 0x1: letter = 'C'; break;
            case 0x2: letter = 'B'; break;
            case 0x3: letter = 'U'; break;
            default: letter = 'U'; break; // 0x4~0xF 属保留区间
        }
        return String.format(Locale.ROOT, "%c%04X", letter, n & 0x0FFF);
    }

    /**
     * 是否标准/强制范围（第二数字为 0）。
     * 第二数字非 0 = 厂商专属码，其含义必须查该车型维修手册，本类不猜。
     */
    public static boolean isStandardRange(String code) {
        return code != null && code.length() >= 2 && code.charAt(1) == '0';
    }

    /** 按前缀给中性分类描述（未命中码表时的兜底，不臆造具体含义）。 */
    public static String categoryOf(String code) {
        if (code == null || code.isEmpty()) return "未知";
        switch (code.charAt(0)) {
            case 'P': return "动力总成";
            case 'C': return "底盘";
            case 'B': return "车身";
            case 'U': return "网络通讯";
            default: return "未知";
        }
    }

    /** 码表查询：命中返回中文说明，未命中返回 null。 */
    public static String lookup(String code) {
        if (code == null) return null;
        String d = COMMON.get(code.toUpperCase(Locale.ROOT));
        if (d != null) return d;
        // 未收录：只给分类，不编造具体含义
        String cat = categoryOf(code);
        return isStandardRange(code)
                ? cat + "（标准码，未收录明细）"
                : cat + "（厂商码，需查该车型维修手册）";
    }

    /** UDS 否定响应码（Negative Response Code）常见项。 */
    private static String negateResponseText(int nrc) {
        switch (nrc) {
            case 0x10: return "否定响应：通用拒绝";
            case 0x11: return "否定响应：服务不支持";
            case 0x12: return "否定响应：子功能不支持（该 ECU 可能不支持按状态掩码读 DTC）";
            case 0x13: return "否定响应：长度超范围";
            case 0x22: return "否定响应：条件不满足（需先进扩展会话 / 点火状态）";
            case 0x31: return "否定响应：请求超出范围";
            case 0x33: return "否定响应：安全等级不足（该服务需解锁）";
            case 0x78: return "否定响应：请求正确但处理中（ECU 忙，稍后重试）";
            default: return "否定响应 NRC=" + String.format("%02X", nrc);
        }
    }

    /** 结果格式化：多行文本，直接喂给日志区/结果区。 */
    public static String format(Result r) {
        StringBuilder sb = new StringBuilder();
        if (r == null) return "";
        if (!r.parsed) {
            return "✖ 响应不是 19 02（ReadDTCInformation）格式，无法解码";
        }
        if (r.entries.isEmpty()) {
            return "✔ 无故障码（响应为全 0 填充）";
        }
        sb.append("共 ").append(r.total).append(" 条，故障 ").append(r.activeCount).append(" 条\n");
        for (Entry e : r.entries) {
            sb.append("  ").append(e.oneLine());
            // 故障类型字节对定位短路/开路/信号异常很有用
            if (e.failureType != 0) {
                sb.append("  [FMV=").append(String.format(Locale.ROOT, "%02X", e.failureType)).append("]");
            }
            if (e.warningIndicator) sb.append(" ⚠告警灯");
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 常见故障码表（SAE J2012 标准码中与电动三电/车身电子相关的条目）。
     *
     * <p>只收录标准码段（第二数字为 0）中含义明确的条目；厂商码一律不收录，
     * 避免把别家车型的码义张冠李戴。</p>
     */
    private static final java.util.Map<String, String> COMMON = new java.util.HashMap<>();

    static {
        // ===== P 动力总成 =====
        COMMON.put("P0016", "曲轴/凸轮轴位置传感器电路");
        COMMON.put("P0087", "燃油轨/系统压力过低");
        COMMON.put("P0101", "空气流量计电路范围/性能");
        COMMON.put("P0112", "进气温度传感器电路低输入");
        COMMON.put("P0113", "进气温度传感器电路高输入");
        COMMON.put("P0128", "冷却液温度低于节温器调节温度");
        COMMON.put("P0129", "冷却液温度高于节温器调节温度");
        COMMON.put("P0171", "系统过稀（未达闭环）");
        COMMON.put("P0172", "系统过浓");
        COMMON.put("P0300", "多缸随机失火");
        COMMON.put("P0301", "1 缸失火");
        COMMON.put("P0302", "2 缸失火");
        COMMON.put("P0303", "3 缸失火");
        COMMON.put("P0304", "4 缸失火");
        COMMON.put("P0420", "催化系统效率低于阈值");
        COMMON.put("P0442", "蒸发排放系统小泄漏");
        COMMON.put("P0455", "蒸发排放系统大泄漏");
        COMMON.put("P0500", "车速传感器电路");
        COMMON.put("P0562", "系统电压低");
        COMMON.put("P0563", "系统电压高");
        COMMON.put("P0606", "ECU 内部故障（处理器/校验和）");
        COMMON.put("P0700", "变速器控制电路");
        COMMON.put("P0715", "输入/涡轮转速传感器电路");
        COMMON.put("P0720", "发动机转速传感器电路");
        COMMON.put("P0730", "换挡挡位不正确 / 挡位信号异常");
        COMMON.put("P0740", "液力离合器/变矩器电路");
        COMMON.put("P1000", "只读存储器 ROM 故障");

        // 三电高压（电动车专用标准码）
        COMMON.put("P0A08", "DC-DC 转换器状态电路");
        COMMON.put("P0A09", "DC-DC 转换器性能");
        COMMON.put("P0A0D", "高压系统互锁回路");
        COMMON.put("P0A0F", "DC-DC 转换器低温/高温保护");
        COMMON.put("P0A1F", "电池电压/容量传感器电路");
        COMMON.put("P0A80", "更换驱动电机");
        COMMON.put("P0A94", "DC-DC 转换器性能");
        COMMON.put("P0A9C", "驱动电机/发电机电压");
        COMMON.put("P0AC0", "电池温度传感器 A 电路");
        COMMON.put("P0BD9", "电池电压过低，SOC 异常");
        COMMON.put("P0C73", "电机 A 位置传感器电路");
        COMMON.put("P0C76", "电机 A 相电流/温度异常");
        COMMON.put("P1AF0", "高压互锁（HVIL）回路断开");
        COMMON.put("P1B12", "电池包温度传感器电路");

        // ===== C 底盘 =====
        COMMON.put("C0035", "左前轮速传感器电路");
        COMMON.put("C0036", "右前轮速传感器电路");
        COMMON.put("C0040", "右后轮速传感器电路");
        COMMON.put("C0044", "左后轮速传感器电路");
        COMMON.put("C0050", "左前轮速传感器齿圈");
        COMMON.put("C0051", "右前轮速传感器齿圈");
        COMMON.put("C0072", "左前 ABS 阀/电磁阀");
        COMMON.put("C0073", "右前 ABS 阀/电磁阀");
        COMMON.put("C0088", "ABS 液压压力传感器电路");
        COMMON.put("C0135", "转向角传感器电路");
        COMMON.put("C1145", "右前轮速传感器信号");
        COMMON.put("C1146", "左前轮速传感器信号");
        COMMON.put("C1165", "右后轮速传感器信号");
        COMMON.put("C1166", "左后轮速传感器信号");
        COMMON.put("C1234", "轮速传感器信号丢失");
        COMMON.put("C1260", "电液助力泵继电器");
        COMMON.put("C1300", "ABS 控制模块内部故障");

        // ===== B 车身 =====
        COMMON.put("B0010", "驾驶员安全带预紧装置");
        COMMON.put("B0028", "副驾预紧装置回路");
        COMMON.put("B10A2", "驾驶员安全带 buckle 开关");
        COMMON.put("B1318", "电池电压低（车身控制）");
        COMMON.put("B1A61", "钥匙less 认证/启动授权");
        COMMON.put("B10A6", "智能钥匙不在车内");
        COMMON.put("B2445", "BCM 配置不一致（编程未完成）");
        COMMON.put("B25A2", "遥控解锁/上锁执行器");
        COMMON.put("B2886", "门锁执行器（驾驶员侧）");
        COMMON.put("B28C0", "右后门锁执行器");
        COMMON.put("B3000", "巡航控制开关");

        // ===== U 网络通讯 =====
        COMMON.put("U0100", "与 ECM/PCM 的控制单元丢失通讯");
        COMMON.put("U0101", "与 TCM 的控制单元丢失通讯");
        COMMON.put("U0111", "与 BMS 的控制单元丢失通讯");
        COMMON.put("U0121", "与 ABS/ESC 的控制单元丢失通讯");
        COMMON.put("U0140", "与 VCDS/网关的通讯丢失（总线断）");
        COMMON.put("U0155", "与仪表 ECU 的控制单元丢失通讯");
        COMMON.put("U0160", "与 HVAC 的控制单元丢失通讯");
        COMMON.put("U0170", "与气囊/安全带控制单元丢失通讯");
        COMMON.put("U0293", "与混合动力控制单元丢失通讯");
        COMMON.put("U0401", "与网关模块控制单元丢失通讯");
        COMMON.put("U1100", "与 ECU 无通讯（本地 CAN 链路）");
    }
}
