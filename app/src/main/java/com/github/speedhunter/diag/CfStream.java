package com.github.speedhunter.diag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RS485 CF/ED 流式增量解码器（纯逻辑，无 Android 依赖，供 DiagSelfTest JVM 回归）。
 *
 * <p>BLE 透传模块的 notify 分段与 SPP 不同——没有 VCI L3 帧边界保证，
 * CF/ED 帧可能被任意拆分/粘包，中间还可能夹非协议垃圾字节。本类把任意分片流
 * 还原成完整校验通过的 43 46 node len data… cksum 45 44 帧。</p>
 *
 * <p>帧结构（与 {@link Rs485} 一致）：43 46 + node(1) + len(1) + data[len] +
 * checksum((node+len+Σdata)&0xFF)(1) + 45 44，总长 len+7。扫描策略：从头找 43 46，
 * 长度足够且校验+尾标记通过则整帧取出；否则右移一字节重试（可跨垃圾字节重同步）。</p>
 */
public final class CfStream {

    private static final int MAX_BUFFER = 4096;

    private byte[] buf = new byte[0];

    /** 喂入一段新收到的字节，返回其中还原出的完整 CF/ED 帧（可能为空）。 */
    public List<byte[]> feed(byte[] chunk, int len) {
        List<byte[]> out = new ArrayList<>();
        if (chunk == null || len <= 0) return out;
        byte[] nb = new byte[buf.length + len];
        System.arraycopy(buf, 0, nb, 0, buf.length);
        System.arraycopy(chunk, 0, nb, buf.length, len);
        buf = nb;
        int i = 0;
        while (i + 7 <= buf.length) {
            if ((buf[i] & 0xFF) != 0x43 || (buf[i + 1] & 0xFF) != 0x46) { i++; continue; }
            int flen = (buf[i + 3] & 0xFF) + 7;
            if (i + flen > buf.length) break; // 头部疑似帧但不完整，等下一段
            if (checkFrame(buf, i, flen)) {
                out.add(Arrays.copyOfRange(buf, i, i + flen));
                i += flen;
            } else {
                i++;
            }
        }
        if (i > 0) buf = Arrays.copyOfRange(buf, i, buf.length);
        if (buf.length > MAX_BUFFER) buf = new byte[0]; // 防御：持续垃圾流不撑爆缓冲
        return out;
    }

    /** 当前缓冲中尚未还原的字节数（诊断/测试用）。 */
    public int buffered() { return buf.length; }

    private static boolean checkFrame(byte[] b, int off, int flen) {
        if ((b[off + flen - 2] & 0xFF) != 0x45 || (b[off + flen - 1] & 0xFF) != 0x44) return false;
        int sum = (b[off + 2] & 0xFF) + (b[off + 3] & 0xFF);
        for (int k = 4; k < flen - 3; k++) sum += (b[off + k] & 0xFF);
        return (sum & 0xFF) == (b[off + flen - 3] & 0xFF);
    }
}
