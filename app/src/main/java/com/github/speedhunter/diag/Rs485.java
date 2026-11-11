package com.github.speedhunter.diag;

/**
 * RS485 CF/ED 帧编解码（极核六节点族）。帧格式见
 * obd-diag/知识库/AE6研究库/02_通信协议全集.md：
 * {@code 43 46 + node u8 + len u8 + data + checksum u8 + 45 44}，
 * checksum = (node + len + Σdata) & 0xFF。
 * 移植自 obd-diag/ZeehoDiag Protocol.kt:Rs485。
 */
public final class Rs485 {
    private Rs485() {}

    public static final class Frame {
        public final int node;
        public final byte[] payload;

        public Frame(int node, byte[] payload) {
            this.node = node;
            this.payload = payload;
        }
    }

    public static byte[] encode(int node, byte[] payload) {
        if (node < 0 || node > 0xFF) throw new IllegalArgumentException("node 越界");
        if (payload.length > 0xFF) throw new IllegalArgumentException("payload 过长");
        byte[] body = new byte[4 + payload.length];
        body[0] = 0x43;
        body[1] = 0x46;
        body[2] = (byte) node;
        body[3] = (byte) payload.length;
        System.arraycopy(payload, 0, body, 4, payload.length);
        int sum = 0;
        for (int i = 2; i < body.length; i++) sum = (sum + (body[i] & 0xFF)) & 0xFF;
        byte[] out = new byte[body.length + 3];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = (byte) sum;
        out[body.length + 1] = 0x45;
        out[body.length + 2] = 0x44;
        return out;
    }

    public static Frame decode(byte[] frame) {
        if (frame.length < 7
                || frame[0] != 0x43
                || frame[1] != 0x46
                || frame[frame.length - 2] != 0x45
                || frame[frame.length - 1] != 0x44) {
            throw new IllegalArgumentException("RS485 帧头/帧尾错误");
        }
        int length = frame[3] & 0xFF;
        if (frame.length != length + 7) throw new IllegalArgumentException("RS485 长度错误");
        int expected = 0;
        for (int i = 2; i < frame.length - 3; i++) {
            expected = (expected + (frame[i] & 0xFF)) & 0xFF;
        }
        if (expected != (frame[frame.length - 3] & 0xFF)) {
            throw new IllegalArgumentException("RS485 校验错误");
        }
        byte[] payload = new byte[length];
        System.arraycopy(frame, 4, payload, 0, length);
        return new Frame(frame[2] & 0xFF, payload);
    }
}
