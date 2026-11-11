package com.github.speedhunter.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * ISO-TP（ISO 15765-2）单帧/多帧编解码。
 * 移植自 obd-diag/ZeehoDiag Protocol.kt:IsoTp。
 */
public final class IsoTp {
    private IsoTp() {}

    /** 把一段 UDS 载荷拆成 CAN 帧（每帧 8 字节，单帧 ≤7 字节载荷）。 */
    public static List<byte[]> encode(byte[] payload, byte padding) {
        if (payload.length > 4095) {
            throw new IllegalArgumentException("ISO-TP 单次载荷不能超过 4095 字节");
        }
        List<byte[]> frames = new ArrayList<>();
        if (payload.length <= 7) {
            byte[] f = new byte[8];
            f[0] = (byte) payload.length;
            System.arraycopy(payload, 0, f, 1, payload.length);
            for (int i = payload.length + 1; i < 8; i++) f[i] = padding;
            frames.add(f);
            return frames;
        }
        byte[] first = new byte[8];
        first[0] = (byte) (0x10 | ((payload.length >> 8) & 0x0F));
        first[1] = (byte) (payload.length & 0xFF);
        int copy = Math.min(6, payload.length);
        System.arraycopy(payload, 0, first, 2, copy);
        for (int i = copy + 2; i < 8; i++) first[i] = padding;
        frames.add(first);
        int offset = 6;
        int sequence = 1;
        while (offset < payload.length) {
            byte[] f = new byte[8];
            f[0] = (byte) (0x20 | (sequence & 0x0F));
            int count = Math.min(7, payload.length - offset);
            System.arraycopy(payload, offset, f, 1, count);
            for (int i = count + 1; i < 8; i++) f[i] = padding;
            frames.add(f);
            offset += count;
            sequence = (sequence + 1) & 0x0F;
        }
        return frames;
    }

    /** 把一串 CAN 帧重组回 UDS 载荷。 */
    public static byte[] decode(List<byte[]> frames) {
        if (frames.isEmpty()) throw new IllegalArgumentException("没有 ISO-TP 帧");
        byte[] first = frames.get(0);
        if (first.length < 2) throw new IllegalArgumentException("ISO-TP 帧长度不足");
        int type = first[0] & 0xF0;
        if (type == 0x00) {
            int length = first[0] & 0x0F;
            if (length > first.length - 1) throw new IllegalArgumentException("ISO-TP 单帧数据不完整");
            byte[] out = new byte[length];
            System.arraycopy(first, 1, out, 0, length);
            return out;
        } else if (type == 0x10) {
            int total = ((first[0] & 0x0F) << 8) | (first[1] & 0xFF);
            List<Byte> out = new ArrayList<>();
            int limit = Math.min(8, 2 + total);
            for (int i = 2; i < limit; i++) out.add(first[i]);
            int expected = 1;
            for (int fi = 1; fi < frames.size(); fi++) {
                byte[] f = frames.get(fi);
                if (f.length == 0 || (f[0] & 0xF0) != 0x20) {
                    throw new IllegalArgumentException("ISO-TP 连续帧类型错误");
                }
                if ((f[0] & 0x0F) != expected) {
                    throw new IllegalArgumentException("ISO-TP 连续帧序号错误");
                }
                for (int i = 1; i < f.length; i++) {
                    out.add(f[i]);
                    if (out.size() >= total) break;
                }
                expected = (expected + 1) & 0x0F;
                if (out.size() >= total) break;
            }
            if (out.size() < total) throw new IllegalArgumentException("ISO-TP 数据未收完整");
            byte[] result = new byte[total];
            for (int i = 0; i < total; i++) result[i] = out.get(i);
            return result;
        }
        throw new IllegalArgumentException(
                String.format("不支持的 ISO-TP 帧类型: %02X", first[0] & 0xFF));
    }
}
