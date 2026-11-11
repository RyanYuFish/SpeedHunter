package com.github.speedhunter.diag;

/** 十六进制编解码工具。移植自 obd-diag/ZeehoDiag Protocol.kt:Hex。 */
public final class Hex {
    private Hex() {}

    public static byte[] parse(String value) {
        String clean = value.replaceAll("(?i)0x", "")
                .replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制数据长度必须为偶数");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String format(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
