package com.github.speedhunter.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * 故障码（DTC）解码：把 UDS {@code 19 02 09} 响应体解析为「码 + 状态」。
 * 移植自 obd-diag/ZeehoDiag Protocol.kt:DtcCodec。
 *
 * <p>本类只做「原始字节 → 码文本/状态位」的离线解码，不含 DTC 文本库查询
 * （文本库在 obd-diag 的 zeeho_diag.db，需车型/ECU 路由，列为后续阶段）。</p>
 */
public final class DtcCodec {
    private DtcCodec() {}

    public static final class DecodedDtc {
        public final String code;
        public final int status;

        public DecodedDtc(String code, int status) {
            this.code = code;
            this.status = status;
        }

        @Override
        public String toString() {
            return code + " (status=0x" + String.format("%02X", status) + ")";
        }
    }

    /** 输入为去头后的 {@code 59 02} 之后的记录区；每条 4 字节：3 字节码 + 1 字节状态位。 */
    public static List<DecodedDtc> decode(byte[] payload) {
        if (payload.length < 3
                || (payload[0] & 0xFF) != 0x59
                || (payload[1] & 0xFF) != 0x02) {
            throw new IllegalArgumentException("不是 UDS 19 02 读 DTC 响应");
        }
        byte[] records = new byte[payload.length - 3];
        System.arraycopy(payload, 3, records, 0, records.length);
        if (records.length % 4 != 0) {
            throw new IllegalArgumentException("DTC 响应记录长度错误");
        }
        List<DecodedDtc> out = new ArrayList<>();
        for (int i = 0; i < records.length; i += 4) {
            String code = String.format("%02X%02X%02X",
                    records[i] & 0xFF, records[i + 1] & 0xFF, records[i + 2] & 0xFF);
            out.add(new DecodedDtc(code, records[i + 3] & 0xFF));
        }
        return out;
    }
}
