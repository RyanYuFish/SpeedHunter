package com.github.speedhunter.ota;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/** Keep the original QR URL; EC's parser needs name/action, not just SSID. */
public final class MirrorRequest {
    public final String url, name, action, ssid, mac;
    public final boolean hasPassword;
    private MirrorRequest(String url, String name, String action, Map<String, String> query) {
        this.url = url; this.name = name; this.action = action;
        this.ssid = query.get("ssid"); this.mac = query.get("mac");
        this.hasPassword = query.containsKey("pwd") && !query.get("pwd").isEmpty();
    }
    public static MirrorRequest parse(String input) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("请粘贴仪表完整投屏二维码 URL");
        String raw = input.trim();
        if (raw.length() > 8192) throw new IllegalArgumentException("二维码 URL 过长");
        try {
            URI uri = new URI(raw);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawQuery() == null)
                throw new IllegalArgumentException("需要带参数的 http/https 投屏二维码 URL");
            Map<String, String> query = new HashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                int equal = part.indexOf('=');
                if (equal < 0) continue;
                // Same value decoding as official QrResult.parseResult.
                String key = part.substring(0, equal);
                if (query.containsKey(key)) throw new IllegalArgumentException("二维码存在重复参数：" + key);
                query.put(key, URLDecoder.decode(part.substring(equal + 1), "UTF-8"));
            }
            String name = query.get("name"), action = query.get("action");
            if (name == null || name.trim().isEmpty() || name.length() > 128)
                throw new IllegalArgumentException("二维码缺少有效 name，SSID 不能替代 P2P 设备名称");
            if (action == null) throw new IllegalArgumentException("二维码缺少 action");
            if (!"wifi".equalsIgnoreCase(action)) {
                int bits;
                try { bits = Integer.parseInt(action); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("二维码 action 无效"); }
                if (bits <= 0 || (bits & 8) == 0)
                    throw new IllegalArgumentException("二维码未声明 Wi-Fi P2P 能力，请使用仪表投屏二维码");
            }
            return new MirrorRequest(raw, name, action, query);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("投屏二维码 URL 格式错误"); }
    }
}
