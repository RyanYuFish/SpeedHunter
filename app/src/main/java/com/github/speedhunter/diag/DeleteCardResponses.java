package com.github.speedhunter.diag;

/** Response checks observed in two AE5i Pro ZSCAN delete-card sessions. */
public final class DeleteCardResponses {
    private DeleteCardResponses() {}

    public static boolean matches(VciLink.DataFrame frame, int requestSeq, int response) {
        if (frame.seq != (requestSeq & 0xff)) return false;
        return matchesAnySeq(frame, response);
    }

    /** 不依赖 TX seq 回显的应答匹配(2026-11-15:官方会话中 FC 应答骑在 poll 响应帧上,
     *  回显 seq 是 poll 的而非 CF 写入的,因此请求-应答匹配只看节点 0xFC + 应答码 + 长度)。 */
    public static boolean matchesAnySeq(VciLink.DataFrame frame, int response) {
        if (!frame.fromVehicle) return false;
        for (byte[] bytes : VciLink.extractCfFrames(frame)) {
            Rs485.Frame cf = Rs485.decode(bytes);
            if (cf.node != 0xfc || cf.payload.length < 2) continue;
            int code = cf.payload[0] & 0xff;
            int result = cf.payload[1] & 0xff;
            if (response == 0x81 && code == 0x81 && result == 0 && cf.payload.length == 6) return true;
            if ((response == 0x82 || response == 0xba) && code == response
                    && result == 0 && cf.payload.length == 2) return true;
        }
        return false;
    }
}
