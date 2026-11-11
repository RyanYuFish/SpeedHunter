package com.github.speedhunter.diag;

/**
 * UDS（ISO 14229）服务构造与响应解析，含等级 1 seed-key 算法。
 * 移植自 obd-diag/ZeehoDiag Protocol.kt:Uds。
 *
 * <p>注意：本类只负责「字节 ↔ 语义」的纯逻辑，不含任何传输（CAN/蓝牙/车机通道）。
 * 真实诊断需要把构造出的请求经 ISO-TP 封装后送到 ECU，并把 ECU 应答回灌进
 * {@link #parseResponse}。等级 1 seed-key 的已知向量见
 * obd-diag/知识库/AE6研究库/03_诊断指令与功能.md（seed 00000000 → key 8036194E）。</p>
 */
public final class Uds {
    private Uds() {}

    public static final class Response {
        public final boolean positive;
        public final int service;
        /** 否定响应时为负响应码（NRC），正响应时为 0。 */
        public final int code;
        public final byte[] data;

        private Response(boolean positive, int service, int code, byte[] data) {
            this.positive = positive;
            this.service = service;
            this.code = code;
            this.data = data;
        }

        public static Response positive(int service, byte[] data) {
            return new Response(true, service, 0, data);
        }

        public static Response negative(int service, int code, byte[] data) {
            return new Response(false, service, code, data);
        }
    }

    public static byte[] session(int mode) {
        return new byte[]{0x10, (byte) mode};
    }

    public static byte[] keepAlive() {
        return new byte[]{0x3E, (byte) 0x80};
    }

    public static byte[] readDtc() {
        return new byte[]{0x19, 0x02, 0x09};
    }

    public static byte[] clearDtc() {
        return new byte[]{(byte) 0x14, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    }

    public static byte[] readDid(int did) {
        return new byte[]{0x22, (byte) (did >> 8), (byte) did};
    }

    public static byte[] writeDid(int did, byte[] value) {
        byte[] out = new byte[3 + value.length];
        out[0] = 0x2E;
        out[1] = (byte) (did >> 8);
        out[2] = (byte) did;
        System.arraycopy(value, 0, out, 3, value.length);
        return out;
    }

    public static byte[] routineStart(int routine, byte[] option) {
        byte[] out = new byte[4 + option.length];
        out[0] = 0x31;
        out[1] = 0x01;
        out[2] = (byte) (routine >> 8);
        out[3] = (byte) routine;
        System.arraycopy(option, 0, out, 4, option.length);
        return out;
    }

    public static byte[] ioControl(int identifier, byte[] parameter) {
        byte[] out = new byte[3 + parameter.length];
        out[0] = 0x2F;
        out[1] = (byte) (identifier >> 8);
        out[2] = (byte) identifier;
        System.arraycopy(parameter, 0, out, 3, parameter.length);
        return out;
    }

    public static Response parseResponse(int requestService, byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("UDS 响应为空");
        }
        int first = payload[0] & 0xFF;
        if (first == 0x7F) {
            if (payload.length < 3) throw new IllegalArgumentException("UDS 否定响应长度不足");
            int service = payload[1] & 0xFF;
            if (service != requestService) {
                throw new IllegalArgumentException("UDS 否定响应服务号不匹配");
            }
            int nrc = payload[2] & 0xFF;
            byte[] rest = new byte[payload.length - 3];
            System.arraycopy(payload, 3, rest, 0, rest.length);
            return Response.negative(service, nrc, rest);
        }
        int expected = (requestService + 0x40) & 0xFF;
        if (first != expected) {
            throw new IllegalArgumentException(
                    String.format("UDS 正响应服务号不匹配: 0x%02X", first));
        }
        byte[] rest = new byte[payload.length - 1];
        System.arraycopy(payload, 1, rest, 0, rest.length);
        return Response.positive(first, rest);
    }

    /**
     * 等级 1 seed-key 算法（XOR 0x8D2E6634 + 位拼接置换）。
     * 已知向量：seed=0x00000000 → key=0x8036194E。
     * 移植自 obd-diag/ZeehoDiag Protocol.kt:Uds.keyLevel1。
     */
    public static int keyLevel1(int seed) {
        int s0 = ((seed >>> 24) & 0xFF) ^ 0x34;
        int s1 = ((seed >>> 16) & 0xFF) ^ 0x66;
        int s2 = ((seed >>> 8) & 0xFF) ^ 0x2E;
        int s3 = (seed & 0xFF) ^ 0x8D;
        int k0 = s3 & 0xF0;
        int k1 = ((s3 & 0x2F) << 2) | (s1 & 0x03);
        int k2 = ((s0 & 0xC0) | (s1 >>> 2)) & 0xFF;
        int k3 = (s2 & 0x0F) | ((s0 & 0x0F) << 4);
        return (k0 << 24) | (k1 << 16) | (k2 << 8) | k3;
    }
}
