package com.github.speedhunter.net;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.github.speedhunter.core.HookLog;

/**
 * 全量响应取证（被动、常开、只读）：每个接口（去 query）的第一份 JSON 响应原文
 * 完整落盘 {@code speedhunter_netdump.log}（≤256KB/份，8MB 轮转，不截断）。
 *
 * <h3>用途：AE7 设置表单直采（2026-11-06 定案）</h3>
 * <p>氮气入口的盲改探测（v4.7.4/v4.7.5 的 flip/inject/键位盲翻）已<b>撤回</b>。
 * 新路线：手机登录带 AE7 车主权限的账号 → 打开车辆设置页（及各子页）→
 * 本类自动把 AE7 的 {@code vehicleSet/vehicleSetInfo/<VIN>}、{@code vehicleSet/items|itemsV2}
 * 等响应原文存档 —— 里面就有 AE7「氮气加速」的 functionConfig / serviceCode / 取值域，
 * 作为后续精确下发的模板。</p>
 *
 * <p>背景情报（车主）：AE7 与 AE5i 同属 <b>gemini 硬件平台</b>，固件存在互通的设置项，
 * 找到对应云端指令即可。已知设置接口家族（2026-11-06 本机日志实测）：
 * {@code vehicleSet/vehicleSetInfo/<VIN>}（MotoSettingRsp 来源，390 次/日）、
 * {@code vehicleSet/items}、{@code vehicleSet/itemsV2}、{@code vehicleSet/propertyOne/one}。</p>
 *
 * <p>读取：
 * {@code adb pull /sdcard/Android/data/com.cfmoto/files/speedhunter_netdump.log}</p>
 */
public final class NetDump {

    private static final String TAG = HookLog.NET;

    /** 同一接口（去 query）只存一份 —— dbpoint/send 之类一天上千次调用不能刷屏。 */
    private static final Set<String> DUMPED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    /** 单份上限（字符）；ridetrack_v2 之类的 MB 级响应直接跳过。 */
    private static final int MAX_DUMP_CHARS = 256 * 1024;

    private NetDump() {
    }

    /**
     * 每个 JSON 响应的第一份原文落盘。任何失败静默 —— 取证绝不能影响响应。
     */
    public static void maybeDump(String url, String body) {
        try {
            if (body == null || body.length() < 2 || body.length() > MAX_DUMP_CHARS) {
                return;
            }
            char c0 = body.charAt(0);
            if (c0 != '{' && c0 != '[') {
                return;     // 只存 JSON
            }
            String key = stripQuery(url);
            if (!DUMPED.add(key)) {
                return;
            }
            HookLog.dump(TAG + " ===== " + key + " (" + body.length() + " chars) =====\n"
                    + body + "\n");
        } catch (Throwable ignored) {
        }
    }

    /** 已记录过的 token 前缀（同 token 不重复刷屏；换新 token 再记一条）。 */
    private static final Set<String> AUTH_SEEN = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    /**
     * v4.7.11：从 Response 反查其 Request 的 Authorization 头，首次见到的
     * token 完整记入本地日志（车主自己的登录态，用于 tools/ota 拉官方固件包，
     * docs/50/65；只写本机文件，不外发）。任何失败静默。
     */
    public static void maybeCaptureAuth(Object response) {
        try {
            Object req = com.github.speedhunter.net.NetPatch.readRequest(response);
            if (req == null) return;
            // v4.7.12：全量请求头（Cfmoto-X-Sign 等签名头一并记录，用于 ota 工具重放）
            Object headers = req.getClass().getMethod("headers").invoke(req);
            java.lang.reflect.Method names = headers.getClass().getMethod("names");
            java.lang.reflect.Method get = headers.getClass().getMethod("get", String.class);
            StringBuilder sb = new StringBuilder();
            for (Object n : (java.util.Set<?>) names.invoke(headers)) {
                Object v = get.invoke(headers, n);
                sb.append(n).append(": ").append(v).append("\n");
            }
            Object u = req.getClass().getMethod("url").invoke(req);
            String key = "[hdr] " + stripQuery(String.valueOf(u));
            if (DUMPED.add(key)) {
                HookLog.dump(TAG + " [req-headers] " + String.valueOf(u) + "\n" + sb);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 去掉 query（? 及其后）作为按接口去重的键；空 URL 归一为 "?"。 */
    private static String stripQuery(String url) {
        if (url == null || url.isEmpty()) {
            return "?";
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
