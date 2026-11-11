package com.github.speedhunter.diag;

/** Response checks observed in two AE5i Pro ZSCAN delete-card sessions. */
public final class DeleteCardResponses {
    private DeleteCardResponses() {}

    public static boolean matches(VciLink.DataFrame frame, int requestSeq, int response) {
        if (!frame.fromVehicle || frame.seq != (requestSeq & 0xff)) return false;
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
