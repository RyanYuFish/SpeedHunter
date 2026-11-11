package com.github.speedhunter.diag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ZEEHO RS485 诊断操作集(2026-11-10 官方 App Java 层抓取对齐版)。
 * 证据: obd-diag/采集 zscan-capture-20261110-00xxxx-{删卡,前轮排气,读取版本}
 * (LSPosed xposed-capture 模块逐字节记录官方 App 的 BluetoothSocket 写入,
 *  与 UI 点击关联;CF 校验和全部验证通过) + 2026-11-06/08 btsnoop 佐证。
 *
 * ★ 核心认知(2026-11-10 官方抓取):
 * - 官方从不发送 cmd 6A 触发器;L3 前导 = 60×3 + 69(0101) + 69(02002C00190019) + 69(0101)
 * - 主从切换 = CF→0x89(15 00…20,64B)×5 + CF→0x71(byte6=0x20) — 删卡会话实证,
 *   仪表黑屏后自动重启(节点 0x17 广播 WiFi AP 信息 = 唤醒标志)
 * - 主从退出 = CF→0x71(byte6=0x04);读取版本官方流程还有 CF→0x80(全零,VIN 查询)
 * - 0x89 写帧官方节奏: #1→#2 158ms, #2→#3 112ms, #3→#4 1105ms, #4→#5 1107ms,
 *   #5→0x71 1108ms;每帧写前 2~3ms 先发 69 0101 轮询
 * - 操作执行前(删卡 3A 之前)官方会把 60 配置 #3 的超时从 0226 提到 07D0
 * - ABS 前轮排气走 07e1/07e9 UDS 样协议,双通道 96/C8,安全访问 key 由 VCI
 *   经 L2 fff9/fff7 计算(官方不内置算法)
 */
public final class ZeehoRs485Ops {
    private ZeehoRs485Ops() {}

    // ===== EOL 认证(0xF4 子命令的前置) =====
    public static final byte[] EOL_AUTH_1 = Rs485.encode(0xF4, hex("01E33A2D47D5576C76"));
    public static final byte[] EOL_AUTH_2 = Rs485.encode(0xF4, hex("02AFC04B0FE9A69FDF"));

    public static List<byte[]> eolAuthSequence() {
        return Arrays.asList(EOL_AUTH_1, EOL_AUTH_2);
    }

    public static int eolProof(int challenge) {
        return challenge * 2023;
    }

    // ===== NFC(EOL 子命令 0x39/0x3A) =====
    public static byte[] nfcLearnSwitch(int mode) {
        if (mode != 0 && mode != 1) throw new IllegalArgumentException("mode 0/1");
        return Rs485.encode(0xF4, new byte[]{0x39, (byte) mode});
    }

    public static byte[] nfcDelete(int type) {
        if (type < 0 || type > 2) throw new IllegalArgumentException("type 0/1/2");
        return Rs485.encode(0xF4, new byte[]{0x3A, (byte) type});
    }

    public static String nfcStatusOf(byte[] cfPayload) {
        if (cfPayload == null || cfPayload.length < 2) return "UNKNOWN";
        int p0 = cfPayload[0] & 0xFF, p1 = cfPayload[1] & 0xFF;
        if (p0 == 0x82 && p1 == 0) return "AUTH_ACK (不代表主从切换成功)";
        if (p0 == 0xBA && p1 == 0) return "DELETE_ACK (需核对官方提示和卡状态)";
        if (p0 == 0xB9) return (p1 == 0x09) ? "READY" : "WAIT_CARD";
        if (p0 == 0x81) return "CHALLENGE";
        return "UNKNOWN";
    }

    // ===== 版本读取(UDS 22F193/F195) =====

    public static final int[] VERSION_NODES = {0x22, 0x21, 0x27, 0x14, 0x17};

    public static byte[] udsReadDid(int node, int did) {
        return Rs485.encode(node, new byte[]{0x22, (byte) (did >> 8), (byte) did});
    }

    // ===== 里程 =====

    public static byte[] mileageWriteDid(int tenthOfKm) {
        if (tenthOfKm < 0 || tenthOfKm > 0xFFFFFF) throw new IllegalArgumentException("里程越界");
        return Rs485.encode(0x14, new byte[]{0x2E, 0x03, 0x09,
                (byte) (tenthOfKm >> 16), (byte) (tenthOfKm >> 8), (byte) tenthOfKm});
    }

    public static byte[] mileageWriteEol(int tenthOfKm) {
        if (tenthOfKm < 0 || tenthOfKm > 0xFFFFFFFFL) throw new IllegalArgumentException("里程越界");
        return Rs485.encode(0xF4, new byte[]{0x4F, 0x00,
                (byte) tenthOfKm, (byte) (tenthOfKm >> 8), (byte) (tenthOfKm >> 16), (byte) (tenthOfKm >> 24)});
    }

    // ===== DTC =====

    public static byte[] dtcReadCan() { return hex("190209"); }
    public static byte[] dtcClearCan() { return hex("14FFFFFF"); }

    public static byte[] dtcReadEol() {
        return Rs485.encode(0xF4, new byte[]{0x51, 0x00});
    }

    public static List<byte[]> dtcClearEolSequence() {
        List<byte[]> out = new ArrayList<>(eolAuthSequence());
        out.add(Rs485.encode(0xF4, concat(new byte[]{0x33}, zeros(20))));
        out.add(Rs485.encode(0xF4, concat(new byte[]{0x36}, zeros(10))));
        out.add(Rs485.encode(0xF4, concat(new byte[]{0x38}, zeros(10))));
        return out;
    }

    // ===== 主从切换帧(2026-11-10 00:20 官方删卡会话逐字节) =====
    /** CF→0x89: 15 + 00×5 + 20 + 57×00(64B)。无时间戳,可安全重放。 */
    public static final byte[] MASTER_SLAVE_89 = Rs485.encode(0x89,
            hex("15000000000020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"));
    /** CF→0x71 byte6=0x20: 切换确认(官方在 0x89×5 后 1.1s 发,节点 0x79 应答)。 */
    public static final byte[] MASTER_SLAVE_CONFIRM_71 = Rs485.encode(0x71,
            hex("00000000000020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"));
    /** CF→0x71 byte6=0x04: 主从退出(操作完成后发)。 */
    public static final byte[] MASTER_SLAVE_EXIT_71 = Rs485.encode(0x71,
            hex("00000000000004000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"));
    /** CF→0x80 全零: 官方读版本流程在 0x71(04) 后发,触发节点 0x88 回 "000F01"+VIN。 */
    public static final byte[] VERSION_QUERY_80 = Rs485.encode(0x80, zeros(64));
    /** 旧 btsnoop 解出的 0x89 变体(读版本会话用,含疑似时间戳,默认不使用)。 */
    public static final byte[] MASTER_SLAVE_89_VERSION = Rs485.encode(0x89, hex(
        "2B22100C050F12501330001E0000006000001000CC6440750E00000000000080000000000000009000000000000000A000CC0000000000B0CCCCCCCC000000C0"));

    /** 官方 0x89×5 节奏(每次发送后 sleep): 158/112/1105/1107,之后 1108ms 发 0x71。 */
    public static final int[] MASTER_SLAVE_DELAYS_MS = {158, 112, 1105, 1107};
    public static final int MASTER_SLAVE_CONFIRM_DELAY_MS = 1108;

    // ===== VCI L3 会话前导(官方: 60×3 + 69 0101 + 69 02 002C00190019 + 69 0101) =====

    public static List<byte[]> sessionSetupCommands() {
        List<byte[]> out = new ArrayList<>();
        out.add(l3(VciLink.CMD_CONFIG, hex("01207F0C004000000000000000000001")));
        out.add(l3(VciLink.CMD_CONFIG, hex("02207F0001C200000801")));
        out.add(l3(VciLink.CMD_CONFIG, hex("03000F02260096000003E8")));
        out.add(l3(VciLink.CMD_POLL, hex("0101")));
        out.add(l3(VciLink.CMD_POLL, hex("02002C00190019")));
        out.add(l3(VciLink.CMD_POLL, hex("0101")));
        return out;
    }

    /** 操作执行前的 60 配置变体: 超时 0226→07D0(官方在删卡 3A / 执行段前重发)。 */
    public static byte[] sessionTimeoutRaise() {
        return l3(VciLink.CMD_CONFIG, hex("03000F07D00096000003E8"));
    }

    /** RS485 写帧 → VCI cmd68 负载(纯 data: 前缀010096+CF)。 */
    public static byte[] wrapRs485ForVci(byte[] cfFrame) {
        return concat(new byte[]{0x01, 0x00, (byte) 0x96}, cfFrame);
    }

    // ===== ABS 前轮排气(2026-11-10 00:22 官方会话逐字节;07e1/07e9 UDS 样) =====

    /** ABS 总线通道: 0x96 与 0xC8 双通道(cmd68 第三字节)。 */
    public static final int BUS_CH_A = 0x96;
    public static final int BUS_CH_B = 0xC8;

    public static byte[] busWrite(int channel, String payloadHex) {
        return concat(new byte[]{0x01, 0x00, (byte) channel}, hex(payloadHex));
    }

    /** L2 fff9: 请 VCI 计算 ABS 安全访问 key(官方逐字节重放;VCI 以 fff7 回 4B key)。 */
    public static final byte[] L2_FFF9_KEY_QUERY = hex("55aa000a0006fff901000360fe0069");

    /** 官方收到 seed 后回执的 L2 fff1(含 seed;保持官方流程完整)。 */
    public static byte[] l2Fff1Ack(byte[] seed4) {
        byte[] data = concat(hex("01000B60DB80010005"), seed4);
        byte[] full = new byte[6 + 2 + data.length + 1];
        full[0] = 0x55; full[1] = (byte) 0xAA;
        full[2] = 0x00; full[3] = 0x0B;                       // L2 seq 0x000B
        full[4] = (byte) ((data.length >> 8) & 0xFF);          // len BE
        full[5] = (byte) (data.length & 0xFF);
        full[6] = (byte) 0xFF; full[7] = (byte) 0xF1;          // cmd fff1
        System.arraycopy(data, 0, full, 8, data.length);
        int x = 0;
        for (int i = 2; i < full.length - 1; i++) x ^= full[i] & 0xFF;
        full[full.length - 1] = (byte) (0xFF ^ x);             // tail = FF^xor(seq..data)
        return full;
    }

    /** 从 L2 fff7 帧(|aa55|seq|len|fff7|01 00 04|key×4|tail|)提取 key;不符返回 null。 */
    public static byte[] parseFff7Key(byte[] frame) {
        if (frame == null || frame.length < 15) return null;
        if ((frame[0] & 0xFF) != 0xAA || (frame[1] & 0xFF) != 0x55) return null;
        int len = ((frame[4] & 0xFF) << 8) | (frame[5] & 0xFF);
        if (len != 8 || (frame[6] & 0xFF) != 0xFF || (frame[7] & 0xFF) != 0xF7) return null;
        if ((frame[8] & 0xFF) != 0x01 || (frame[9] & 0xFF) != 0x00 || (frame[10] & 0xFF) != 0x04) return null;
        return java.util.Arrays.copyOfRange(frame, 11, 15);
    }

    // ABS 命令负载(07 e1 08 = 请求头)
    public static byte[] absTesterPresent()  { return busWrite(BUS_CH_A, "07E108023E8000000000"); }
    public static byte[] absSession1003()    { return busWrite(BUS_CH_A, "07E1080210030000000000"); }
    public static byte[] absSeedRequest()    { return busWrite(BUS_CH_A, "07E1080227010000000000"); }
    public static byte[] absSendKey(byte[] key4) {
        return busWrite(BUS_CH_A, Hex.format(concat(hex("07E108062702"), key4)).replace(" ", "") + "00");
    }
    public static byte[] absLoopPump()       { return busWrite(BUS_CH_A, "07E108100B2FE0DB030000"); }
    public static byte[] absLoopValve(boolean open) {
        return busWrite(BUS_CH_B, open ? "07E1082164640000FC0000" : "07E1082100000000FC0000");
    }
    public static byte[] absLoopStatus()     { return busWrite(BUS_CH_B, "07E1083008140000000000"); }
    public static byte[] absStopPump()       { return busWrite(BUS_CH_A, "07E108052FE0DA03010000"); }
    public static byte[] absValveOff()       { return busWrite(BUS_CH_A, "07E108052FE0DA03000000"); }
    public static byte[] absClearDtc()       { return busWrite(BUS_CH_A, "07E1080414FFFFFF000000"); }
    public static byte[] absDefaultSession() { return busWrite(BUS_CH_A, "07E1080210010000000000"); }
    public static byte[] absEcuReset()       { return busWrite(BUS_CH_A, "07E1080211010000000000"); }

    /** ABS 排气会话配置(官方 4 条 60 + 2 条 VCI 内部 68)。 */
    public static List<byte[]> absBleedSetupCommands() {
        List<byte[]> out = new ArrayList<>();
        out.add(l3(VciLink.CMD_CONFIG, hex("01246E0C020800")));
        out.add(l3(VciLink.CMD_CONFIG, hex("02246E0007A120000801")));
        out.add(l3(VciLink.CMD_CONFIG, hex("03000000C8009600000BB8")));
        out.add(l3(VciLink.CMD_CONFIG, hex("0801000007E90000FFFF")));
        out.add(l3(VciLink.CMD_RS485_WRITE, hex("8201")));
        out.add(l3(VciLink.CMD_RS485_WRITE, hex("8200")));
        return out;
    }

    public static final int ABS_LOOP_ITERATIONS = 60;
    public static final long ABS_LOOP_PERIOD_MS = 1100;

    // ===== 兼容别名(DiagSelfTest 引用) =====
    public static List<byte[]> nfcStudySequence(int mode) {
        List<byte[]> out = new ArrayList<>();
        out.add(nfcLearnSwitch(mode));
        out.add(nfcLearnSwitch(mode));
        return out;
    }

    /** 删卡执行序列(EOL认证 + 3A);主从切换由 DiagFragment 官方流程负责。 */
    public static List<byte[]> nfcDeleteSequence() {
        List<byte[]> out = new ArrayList<>(eolAuthSequence());
        out.add(nfcDelete(0));
        return out;
    }

    public static byte[] dtcReadStatus() { return dtcReadEol(); }
    public static List<byte[]> dtcReadRs485Sequence() { return eolAuthSequence(); }
    public static List<byte[]> dtcClearRs485Sequence() { return dtcClearEolSequence(); }

    public static List<byte[]> mileageEolSequence(int tenthOfKm) {
        List<byte[]> out = new ArrayList<>(eolAuthSequence());
        out.add(mileageWriteEol(tenthOfKm));
        return out;
    }

    // ===== 工具 =====
    static final int CMD_RS485_WRITE = VciLink.CMD_RS485_WRITE;

    static byte[] l3(int cmd, byte[] data) {
        byte[] out = new byte[2 + data.length];
        out[1] = (byte) cmd;
        System.arraycopy(data, 0, out, 2, data.length);
        return out;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] zeros(int n) { return new byte[n]; }

    static String zerosHex(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append("00");
        return sb.toString();
    }

    static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
