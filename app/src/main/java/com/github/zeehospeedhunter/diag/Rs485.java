package com.github.zeehospeedhunter.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ZEEHO RS485 私协议解码器 —— 忠实移植自 zeeho-diag {@code lib/rs485.js}
 * （源自 tools/rs485/decode-rs485log.py + docs/14 实证）。
 *
 * <p>两层结构：</p>
 * <ol>
 *   <li><b>链路帧</b>（固定 15 字节）：{@code AB 00 | 0B 00 | cmd q_hi q_lo 02 | 00 00 00 00 | 00 | D | XOR}；
 *       第 13 字节 D 是应用层字节流的一个字节；XOR = bytes[0..13] 异或，存于第 14 字节。</li>
 *   <li><b>应用帧</b>（把全部 D 拼起来）：{@code 43 46 | cmd | len | data[len] | chk | 45 44}；
 *       chk = (cmd + len + Σdata) &amp; 0xFF。</li>
 * </ol>
 *
 * <p>这是 RS485 总线（OBD 针 7/15 @115200 8N1）上跑的协议，与 WiFi/DoIP 的 UDS 完全不同。
 * 纯静态、无 I/O，可对离线抓包直接解码。</p>
 */
public final class Rs485 {

    private Rs485() {
    }

    public static final int LINK_LEN = 15;
    public static final int LINK_MAGIC = 0xAB;
    public static final byte[] APP_HEAD = {0x43, 0x46}; // "CF"
    public static final byte[] APP_TAIL = {0x45, 0x44}; // "ED"

    // 系统码（cmd 字节的语义分类）
    public static final Map<Integer, String> SYSTEM_CODES = new LinkedHashMap<>();
    // 已实测命令码（docs/14 §5.2 + README 合并）
    public static final Map<Integer, String> CMD_NAMES = new LinkedHashMap<>();

    static {
        SYSTEM_CODES.put(0x49, "计量器");
        SYSTEM_CODES.put(0x51, "BMS 电池");
        SYSTEM_CODES.put(0x59, "MCU 电机");
        SYSTEM_CODES.put(0x81, "仪表整车");
        SYSTEM_CODES.put(0x89, "TBOX");
        SYSTEM_CODES.put(0x8B, "后雷达");

        CMD_NAMES.put(0x17, "状态双字节(上行)");
        CMD_NAMES.put(0x41, "状态块(上行,含204dfd)");
        CMD_NAMES.put(0x49, "状态块(上行)");
        CMD_NAMES.put(0x51, "状态块(上行)");
        CMD_NAMES.put(0x59, "状态块(上行)");
        CMD_NAMES.put(0x71, "触发/写确认(下行)");
        CMD_NAMES.put(0x80, "逐块读取请求(下行)");
        CMD_NAMES.put(0x81, "状态块(上行)");
        CMD_NAMES.put(0x83, "回读块(上行)");
        CMD_NAMES.put(0x85, "状态块(上行,首4B 00 00 04 20)");
        CMD_NAMES.put(0x88, "VIN 块(上行)");
        CMD_NAMES.put(0x8B, "参数块(上行)");
        CMD_NAMES.put(0x8D, "参数块(上行)");
        CMD_NAMES.put(0x89, "诊断仪下发寄存器块(下行,64B)");
        CMD_NAMES.put(0x97, "UTF-8 中文地址(上行,129B)");
    }

    public static String describeCmd(int cmd) {
        String n = CMD_NAMES.get(cmd);
        if (n != null) return n;
        n = SYSTEM_CODES.get(cmd);
        if (n != null) return n;
        return "未知(0x" + Integer.toHexString(cmd).toUpperCase() + ")";
    }

    // ---------- 链路帧 ----------

    public static final class LinkFrame {
        public boolean valid;
        public boolean xorOk;
        public Integer cmd;
        public Integer q;
        public Integer d;
        public boolean headOk;
        public String reason = "";
    }

    public static LinkFrame parseLinkFrame(byte[] b) {
        LinkFrame f = new LinkFrame();
        if (b == null || b.length != LINK_LEN) {
            f.valid = false;
            f.reason = "长度 " + (b == null ? 0 : b.length) + " ≠ " + LINK_LEN;
            return f;
        }
        int xor = 0;
        for (int i = 0; i < LINK_LEN - 1; i++) xor ^= (b[i] & 0xFF);
        f.valid = true;
        f.xorOk = xor == (b[LINK_LEN - 1] & 0xFF);
        f.cmd = b[4] & 0xFF;
        f.q = ((b[5] & 0xFF) << 8) | (b[6] & 0xFF);
        f.d = b[13] & 0xFF;
        f.headOk = (b[0] & 0xFF) == LINK_MAGIC && (b[1] & 0xFF) == 0x00
                && (b[2] & 0xFF) == 0x0b && (b[3] & 0xFF) == 0x00;
        return f;
    }

    /** 把若干链路帧的第 13 字节(D)拼成应用层字节流。 */
    public static byte[] reassembleStream(List<byte[]> linkFrames) {
        byte[] out = new byte[linkFrames.size()];
        int n = 0;
        for (byte[] b : linkFrames) {
            if (b != null && b.length == LINK_LEN) out[n++] = b[13];
        }
        if (n == out.length) return out;
        byte[] trimmed = new byte[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    // ---------- 应用帧 ----------

    public static final class AppFrame {
        public int offset;
        public int cmd;
        public String cmdName;
        public int len;
        public byte[] data;
        public int chk;
        public boolean chkOk;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (haystack == null || needle == null) return -1;
        int last = haystack.length - needle.length;
        for (int i = Math.max(0, from); i <= last; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }

    private static int sum(byte[] buf, int from, int to) {
        int t = 0;
        for (int i = from; i < to && i < buf.length; i++) t += (buf[i] & 0xFF);
        return t;
    }

    /** 在字节流中切出 43 46 … 45 44 应用帧（允许误锚点，用长度+结束符+校验和消歧）。 */
    public static List<AppFrame> parseAppFrames(byte[] s) {
        List<AppFrame> frames = new ArrayList<>();
        if (s == null) return frames;
        int i = 0;
        int n = s.length;
        while (true) {
            int j = indexOf(s, APP_HEAD, i);
            if (j < 0 || j + 4 > n) break;
            int len = s[j + 3] & 0xFF;
            int end = j + 4 + len + 1 + 2; // hdr4 + data + chk1 + tail2
            if (len == 0 || end > n) { i = j + 2; continue; }
            boolean tailOk = (s[end - 2] & 0xFF) == APP_TAIL[0] && (s[end - 1] & 0xFF) == APP_TAIL[1];
            if (!tailOk) { i = j + 2; continue; }
            int cmd = s[j + 2] & 0xFF;
            byte[] data = DiagProtocol.slice(s, j + 4, j + 4 + len);
            int chk = s[j + 4 + len] & 0xFF;
            int expect = (cmd + len + sum(data, 0, data.length)) & 0xFF;
            AppFrame fr = new AppFrame();
            fr.offset = j;
            fr.cmd = cmd;
            fr.cmdName = describeCmd(cmd);
            fr.len = len;
            fr.data = data;
            fr.chk = chk;
            fr.chkOk = chk == expect;
            frames.add(fr);
            i = end;
        }
        return frames;
    }

    // ---------- 离线日志解码 ----------

    public static final class DecodeResult {
        public int linkFrames;
        public int xorErrors;
        public int streamBytes;
        public int appFrames;
        public Map<String, Integer> cmdDistribution = new LinkedHashMap<>();
        public List<AppFrame> frames = new ArrayList<>();
    }

    /** *_RS485.txt 格式：每行一帧 15 字节（ts | hex）。 */
    public static DecodeResult decodeRs485Log(String text) {
        List<byte[]> linkFrames = new ArrayList<>();
        int xorErrors = 0;
        if (text != null) {
            for (String line : text.split("\r?\n")) {
                String s = line.trim();
                if (!s.contains("|")) continue;
                int bar = s.indexOf('|');
                String hexPart = s.substring(bar + 1);
                String hx = hexPart.replaceAll("[^0-9A-Fa-f]", "");
                if (hx.length() != LINK_LEN * 2) continue;
                byte[] bytes = DiagProtocol.hexToBytes(hx);
                LinkFrame f = parseLinkFrame(bytes);
                if (!f.xorOk) xorErrors++;
                linkFrames.add(bytes);
            }
        }
        return finish(linkFrames, xorErrors);
    }

    /** 把「连续的链路帧十六进制」（每 30 hex = 一帧）直接解码。 */
    public static DecodeResult decodeRs485LinkHex(String hexString) {
        String hx = (hexString == null ? "" : hexString).replaceAll("[^0-9A-Fa-f]", "");
        List<byte[]> linkFrames = new ArrayList<>();
        int xorErrors = 0;
        for (int i = 0; i + LINK_LEN * 2 <= hx.length(); i += LINK_LEN * 2) {
            byte[] bytes = DiagProtocol.hexToBytes(hx.substring(i, i + LINK_LEN * 2));
            LinkFrame f = parseLinkFrame(bytes);
            if (!f.xorOk) xorErrors++;
            linkFrames.add(bytes);
        }
        return finish(linkFrames, xorErrors);
    }

    private static DecodeResult finish(List<byte[]> linkFrames, int xorErrors) {
        byte[] stream = reassembleStream(linkFrames);
        List<AppFrame> appFrames = parseAppFrames(stream);
        DecodeResult r = new DecodeResult();
        r.linkFrames = linkFrames.size();
        r.xorErrors = xorErrors;
        r.streamBytes = stream.length;
        r.appFrames = appFrames.size();
        r.frames = appFrames;
        for (AppFrame fr : appFrames) {
            String k = "0x" + Integer.toHexString(fr.cmd).toUpperCase();
            r.cmdDistribution.merge(k, 1, Integer::sum);
        }
        return r;
    }

    /** 生成人类可读的解码报告（供 UI 展示）。 */
    public static String formatReport(DecodeResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("链路帧 ").append(r.linkFrames).append(" 个（XOR 错 ").append(r.xorErrors).append("）\n");
        sb.append("重组字节流 ").append(r.streamBytes).append(" B\n");
        sb.append("应用帧 ").append(r.appFrames).append(" 个\n");
        if (!r.cmdDistribution.isEmpty()) {
            sb.append("命令分布：");
            boolean first = true;
            for (Map.Entry<String, Integer> e : r.cmdDistribution.entrySet()) {
                sb.append(first ? "" : "  ").append(e.getKey()).append("×").append(e.getValue());
                first = false;
            }
            sb.append("\n");
        }
        int shown = 0;
        for (AppFrame fr : r.frames) {
            if (shown++ >= 40) {
                sb.append("…（其余 ").append(r.frames.size() - 40).append(" 帧略）\n");
                break;
            }
            sb.append(String.format("  @%d cmd=0x%02X %s len=%d chk=%s data=%s%n",
                    fr.offset, fr.cmd, fr.cmdName, fr.len,
                    fr.chkOk ? "OK" : "BAD", DiagProtocol.bytesToHexSpaced(fr.data)));
        }
        return sb.toString();
    }
}
