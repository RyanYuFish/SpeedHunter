package com.github.speedhunter.net;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ZEEHO 账号侧「签到 + 积分任务」只读/签到客户端。
 *
 * <h3>来源</h3>
 * <p>设备上已安装的第三方 App「几何面板」v1.8.40（{@code io.github.lswlc33.zeehowebnext}）
 * 的现有实现：请求头、接口路径、参数名、签名算法均由其 smali 静态反汇编定案。
 * 本类只用 Android/JDK 内置 API（{@link HttpURLConnection} / {@link MessageDigest} /
 * {@link JSONObject}），不引入任何新依赖。</p>
 *
 * <h3>登录参数</h3>
 * <ul>
 *   <li>{@code Authorization: Bearer <token>} —— 车主从其他客户端取得后录入。</li>
 *   <li>{@code userId} —— 同一来源的账号 ID，作为所有账号侧接口的查询参数。</li>
 * </ul>
 *
 * <h3>签名（cfmoto-x-sign）</h3>
 * <pre>
 * param = "appId=&lt;id&gt;&amp;nonce=&lt;随机&gt;&amp;timestamp=&lt;毫秒&gt;"
 * sign  = MD5( hex(SHA-1( sortedQuery + body + param + secret )) )
 * </pre>
 * 其中 {@code sortedQuery} 为按 key 排序的 {@code k=v&k=v}（不含 '?'）；
 * GET 的 {@code body} 为空串，H5 签到 POST 的 {@code body} 为 {@code "{}"}。
 * 两组 appId/secret 分别对应 {@code tapi.zeehoev.com} 与 {@code h5.zeehoev.com}。
 *
 * <p><b>边界</b>：签名常量来自设备端已安装 App 的现有实现，仅本地自用，勿外传；
 * 未做实车应答验证。服务端若返回签名/鉴权错误，{@link Result} 会原样带回响应体，
 * 便于按日志调整，不伪造成功。</p>
 */
public final class AccountApi {

    private static final String BASE_TAPI = "https://tapi.zeehoev.com";
    private static final String BASE_H5 = "https://h5.zeehoev.com/cfmotoservermine/H5";

    /** 车机/app 侧接口参数。见几何面板 {@code w.c()} / {@code w.g()}。 */
    private static final String APP_ID_TAPI = "S7qPWPU1";
    private static final String SECRET_TAPI = "c5e0da7f4da28df805694ec3dd1fc6792e9df99d";

    /** H5 签到页接口参数。见几何面板 {@code z0} / {@code p}。 */
    private static final String APP_ID_H5 = "AiTXmBrm";
    private static final String SECRET_H5 = "70c2c7458ab88ca9504ad0521f170075bc91f2f7";

    private static final int TIMEOUT_MS = 10000;

    private AccountApi() {
    }

    /** 一次接口调用的结果；body 原样保留，便于鉴权/签名失败时定位。 */
    public static final class Result {
        public final int http;
        public final String code;
        public final String message;
        public final String data;
        public final String body;

        Result(int http, String body) {
            this.http = http;
            this.body = body == null ? "" : body;
            String c = "";
            String m = "";
            String d = "";
            try {
                JSONObject j = new JSONObject(this.body.length() == 0 ? "{}" : this.body);
                c = j.optString("code", "");
                m = j.optString("message", "");
                Object raw = j.opt("data");
                d = raw == null ? "" : raw.toString();
            } catch (Throwable ignored) {
                // 非 JSON（网关错误页）时保留原文
            }
            this.code = c;
            this.message = m;
            this.data = d;
        }

        public boolean ok() {
            return http >= 200 && http < 300 && "10000".equals(code);
        }

        public String summary() {
            if (ok()) return "OK  " + brief(data);
            return "HTTP " + http + "  code=" + (code.isEmpty() ? "-" : code)
                    + "  " + (message.isEmpty() ? brief(body) : message);
        }

        private static String brief(String s) {
            if (s == null) return "";
            String t = s.replace('\n', ' ').trim();
            return t.length() <= 220 ? t : t.substring(0, 220) + "…";
        }
    }

    /** 当前账号的签到次数 / 连续天数（只读）。 */
    public static Result profile(String token) {
        return tapi(token, "", "/v1.0/mine/cfmotoservermine/baseInfo_v2",
                new TreeMap<String, String>(), "GET", "");
    }

    /** 当前账号的签到次数 / 连续天数（只读）。 */
    public static Result signInCount(String token, String userId) {
        return tapi(token, userId, "/v1.0/mine/cfmotoservermine/signin/count",
                new TreeMap<String, String>(), "GET", "");
    }

    /** 当前账号的积分明细（只读）。 */
    public static Result integral(String token, String userId) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("userId", userId);
        return tapi(token, userId, "/v1.0/mine/cfmotoservermine/integral/getByUserId",
                q, "GET", "");
    }

    /** 当前账号的积分总数（只读）。 */
    public static Result totalIntegral(String token, String userId) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("userId", userId);
        return tapi(token, userId, "/v1.0/mine/cfmotoservermine/integral/totalIntegral",
                q, "GET", "");
    }

    /** 社区/每日任务列表（只读，含任务完成状态）。 */
    public static Result taskList(String token, String userId) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("page", "1");
        q.put("pageSize", "20");
        q.put("postModule", "3");
        q.put("slidingType", "1");
        return tapi(token, userId,
                "/v1.0/social/cfmotoserversocial/community/qbTzInfoNewV2", q, "GET", "");
    }

    /** 当月签到状态（只读，含已签日期 / 奖品）。 */
    public static Result signInInfo(String token, String userId, String yearMonth) {
        TreeMap<String, String> q = new TreeMap<>();
        q.put("userId", userId);
        q.put("month", yearMonth);
        q.put("server_name", "SMART");
        return h5(token, userId, "/signin/info", q, "GET", "");
    }

    /** 执行签到（写操作 —— 仅由 UI 按钮显式触发，无自动执行）。 */
    public static Result signIn(String token, String userId) {
        return h5(token, userId, "/signin", new TreeMap<String, String>(), "POST", "{}");
    }

    // ==================== 请求与签名 ====================

    private static Result tapi(String token, String userId, String path,
                               TreeMap<String, String> query, String method, String body) {
        return call(BASE_TAPI + path, path, query, method, body,
                APP_ID_TAPI, SECRET_TAPI, token);
    }

    private static Result h5(String token, String userId, String path,
                             TreeMap<String, String> query, String method, String body) {
        return call(BASE_H5 + path, path, query, method, body,
                APP_ID_H5, SECRET_H5, token);
    }

    private static Result call(String baseUrl, String path, TreeMap<String, String> query,
                               String method, String body,
                               String appId, String secret, String token) {
        HttpURLConnection conn = null;
        try {
            String sorted = sortedQuery(query);
            String url = baseUrl + (sorted.isEmpty() ? "" : "?" + sorted);
            String param = "appId=" + appId + "&nonce=" + UUID.randomUUID()
                    + "&timestamp=" + System.currentTimeMillis();
            String sign = sign(sorted, body, param, secret);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("Accept-Language", "zh-CN");
            conn.setRequestProperty("interfaceversion", "2");
            conn.setRequestProperty("cfmoto-x-param", param);
            conn.setRequestProperty("cfmoto-x-sign", sign);
            conn.setRequestProperty("cfmoto-x-sign-type", "0");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (!"GET".equals(method)) {
                conn.setDoOutput(true);
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(out);
                } finally {
                    os.close();
                }
            }
            int http = conn.getResponseCode();
            InputStream in = http >= 200 && http < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            return new Result(http, readAll(in));
        } catch (Throwable t) {
            return new Result(-1, "{\"message\":\"" + safe(t.toString()) + "\"}");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 按 key 排序拼 {@code k=v&k=v}；与几何面板 {@code TreeMap} 遍历一致。 */
    static String sortedQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(query).entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    /** {@code MD5(hex(SHA-1(sortedQuery + body + param + secret)))}。 */
    static String sign(String sortedQuery, String body, String param, String secret) {
        String raw = (sortedQuery == null ? "" : sortedQuery)
                + (body == null ? "" : body) + param + secret;
        return md5Hex(sha1Hex(raw));
    }

    /**
     * 请求侧注入改了 URL 里的 {@code userId} 后，用官方 App 已生成的
     * {@code cfmoto-x-param} 重算 {@code cfmoto-x-sign}（签名串含 sortedQuery，
     * 不重算会被服务端拒绝）。按 host 选 appId 对应的 secret。
     *
     * @param url   注入后的完整 URL
     * @param param 请求头里原有的 {@code cfmoto-x-param}
     * @param body  GET 传空串；POST 由调用方决定是否重算
     */
    public static String reSign(String url, String param, String body) {
        if (url == null || param == null || param.isEmpty()) return null;
        try {
            String query = "";
            int q = url.indexOf('?');
            if (q >= 0 && q < url.length() - 1) {
                TreeMap<String, String> map = new TreeMap<>();
                for (String pair : url.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) continue;
                    map.put(pair.substring(0, eq),
                            pair.substring(eq + 1));
                }
                query = sortedQuery(map);
            }
            String secret = url.contains("h5.zeehoev.com") ? SECRET_H5 : SECRET_TAPI;
            return sign(query, body, param, secret);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha1Hex(String s) {
        return digest("SHA-1", s);
    }

    private static String md5Hex(String s) {
        return digest("MD5", s);
    }

    private static String digest(String algo, String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) {
                sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 64 * 1024) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'").replace('\n', ' ');
    }
}
