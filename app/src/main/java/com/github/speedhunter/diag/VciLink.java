package com.github.speedhunter.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * 官方 ZSCAN VCI 私有协议层（手机↔VCI，SPP RFCOMM DLCI=2 字节流）。
 * 2026-11-06 台架实测逆向，证据：obd-diag/采集/20261106_zscan_s1/frames_*.jsonl。
 *
 * 两层封装：
 * L2 配置/鉴权  App→VCI: 55 AA + seq(u16 BE) + len(u16 BE) + payload + 尾3B
 *               VCI→App: AA 55 镜像（seq 回显）。命令 FFFA 状态/FFEF 设备信息/FEF2 鉴权/FFB2 许可。
 * L3 数据       App→VCI: 56 00 + len(u16 LE) + seq(1B) + cmd(1B) + data + crc
 *               VCI→App: AB 00 + len(u16 LE) + …。cmd: 68=RS485 写(内嵌 CF/ED)、
 *               69=轮询(690101)、60=通道配置、6A=触发(如 6A1031)。
 *
 * 说明：L2 尾 3B 与 L3 crc 字段算法未定标（SpeedHunter 首版仅实现 L3 数据层的
 * 解析与构造参考；发送侧由传输后端按抓包逐字节回放）。
 */
public final class VciLink {
    private VciLink() {}

    public static final int DATA_MAGIC_OUT = 0x56; // 'V' + 0x00
    public static final int DATA_MAGIC_IN = 0xAB; // + 0x00
    public static final int CMD_RS485_WRITE = 0x68;
    public static final int CMD_POLL = 0x69;
    public static final int CMD_CONFIG = 0x60;
    public static final int CMD_TRIGGER = 0x6A;

    /** Incremental decoder for the interleaved L2 and L3 RFCOMM stream. */
    public static final class StreamDecoder {
        private byte[] pending = new byte[0];

        public interface Sink {
            void onL2(byte[] frame);
            void onData(DataFrame frame);
        }

        public void accept(byte[] bytes, int length, Sink sink) {
            if (length < 0 || length > bytes.length) throw new IllegalArgumentException("length");
            byte[] joined = java.util.Arrays.copyOf(pending, pending.length + length);
            System.arraycopy(bytes, 0, joined, pending.length, length);
            int i = 0;
            while (joined.length - i >= 2) {
                int a = joined[i] & 0xff, b = joined[i + 1] & 0xff;
                boolean l2 = a == 0xaa && b == 0x55;
                boolean l3 = (a == 0xab || a == 0x56) && b == 0;
                if (!l2 && !l3) { i++; continue; }
                int header = l2 ? 6 : 4;
                if (joined.length - i < header) break;
                int size = l2 ? 9 + ((joined[i + 4] & 0xff) << 8) + (joined[i + 5] & 0xff)
                        : 4 + (joined[i + 2] & 0xff) + ((joined[i + 3] & 0xff) << 8);
                if (size < (l2 ? 9 : 7) || size > 8192) { i++; continue; }
                if (joined.length - i < size) break;
                byte[] frame = java.util.Arrays.copyOfRange(joined, i, i + size);
                if (l2) sink.onL2(frame);
                else {
                    List<DataFrame> parsed = parseStream(frame);
                    if (!parsed.isEmpty()) sink.onData(parsed.get(0));
                }
                i += size;
            }
            pending = java.util.Arrays.copyOfRange(joined, i, joined.length);
        }
    }

    /** L3 数据帧（方向无关的解析结果）。 */
    public static final class DataFrame {
        public final boolean fromVehicle; // true=VCI→App(AB); may be a VCI status
        public final int seq;
        public final int cmd;
        public final byte[] data;
        DataFrame(boolean fromVehicle, int seq, int cmd, byte[] data) {
            this.fromVehicle = fromVehicle;
            this.seq = seq;
            this.cmd = cmd;
            this.data = data;
        }
    }

    /** 从字节流中提取全部完整 L3 数据帧（容错：跳过无法解析的字节）。 */
    public static List<DataFrame> parseStream(byte[] stream) {
        List<DataFrame> out = new ArrayList<>();
        int i = 0;
        while (i + 4 <= stream.length) {
            int m0 = stream[i] & 0xFF, m1 = stream[i + 1] & 0xFF;
            boolean in;
            if (m0 == DATA_MAGIC_OUT && m1 == 0x00) in = false;
            else if (m0 == DATA_MAGIC_IN && m1 == 0x00) in = true;
            else { i++; continue; }
            int len = (stream[i + 2] & 0xFF) | ((stream[i + 3] & 0xFF) << 8); // LE
            int body = i + 4 + len;
            if (len < 3 || body > stream.length) { i++; continue; }
            int checksum = 0;
            for (int j = i; j < body; j++) checksum ^= stream[j] & 0xff;
            if (checksum != 0) { i++; continue; }
            int seq = stream[i + 4] & 0xFF;
            int cmd = stream[i + 5] & 0xFF; // AB response byte is timestamp high, not a command
            // len = seq(1)+cmd(1)+data+crc(1),data 不含末位 crc
            byte[] data = new byte[Math.max(0, len - 3)];
            System.arraycopy(stream, i + 6, data, 0, data.length);
            out.add(new DataFrame(in, seq, cmd, data));
            i = body;
        }
        return out;
    }

    /** 组 L3 发送帧：56 00 + len(u16 LE=seq+cmd+data+1) + seq + cmd + data + XOR(全帧)。2026-11-08 定标。 */
    public static byte[] buildFrame(int seq, int cmd, byte[] data) {
        int payload = 2 + data.length + 1; // seq+cmd+data+crc
        byte[] f = new byte[4 + payload];
        f[0] = 0x56; f[1] = 0x00;
        f[2] = (byte) (payload & 0xFF); f[3] = (byte) (payload >> 8); // LE
        f[4] = (byte) seq; f[5] = (byte) cmd;
        System.arraycopy(data, 0, f, 6, data.length);
        int x = 0;
        for (byte b : f) x ^= b;
        f[f.length - 1] = (byte) x;
        return f;
    }

    /** 从数据帧负载中提取内嵌的 CF/ED 帧（cmd 68/应答）。 */
    public static List<byte[]> extractCfFrames(DataFrame f) {
        List<byte[]> out = new ArrayList<>();
        byte[] d = f.data;
        int i = 0;
        while (i + 4 <= d.length) {
            if ((d[i] & 0xFF) != 0x43 || (d[i + 1] & 0xFF) != 0x46) { i++; continue; }
            int len = d[i + 3] & 0xFF;
            int end = i + 4 + len + 3;
            if (end > d.length
                    || (d[i + 4 + len + 1] & 0xFF) != 0x45
                    || (d[i + 4 + len + 2] & 0xFF) != 0x44) { i++; continue; }
            byte[] cf = new byte[end - i];
            System.arraycopy(d, i, cf, 0, cf.length);
            try { Rs485.decode(cf); }
            catch (IllegalArgumentException invalid) { i++; continue; }
            out.add(cf);
            i = end;
        }
        return out;
    }
}
