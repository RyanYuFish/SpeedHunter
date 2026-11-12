package com.github.speedhunter.net;

import com.github.speedhunter.core.HookLog;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 车模本地上传的资产服务器：跑在官方 App（com.cfmoto）进程里的 loopback HTTP 服务。
 *
 * <p>模块 UI 与官方 App 是两个进程，用户在模块里选的本地图无法直接交给官方 App 的
 * 图片加载器。方案：官方 App 的 network_security_config 是
 * {@code cleartextTrafficPermitted="true"}（apktool-v3recovery 取证），127.0.0.1 明文
 * HTTP 可以被其加载器接受 ⇒ 模块把用户选的文件 POST 到本服务，落盘到官方 App 私有
 * 目录，再把 {@code http://127.0.0.1:PORT/f/<name>} 写进车模配置 —— VehicleArtUnlock
 * 照常把这个 URL 替换进 vehiclePicUrl/vehiclePic3dUrl，App 的加载器回头来拉。</p>
 *
 * <p>路由：{@code GET /ping} 探活；{@code POST /upload?name=X} 上传；
 * {@code GET /f/X} 取文件。只绑 127.0.0.1；文件名白名单 [A-Za-z0-9._-]{1,80}；
 * 单文件上限 80MB；端口 8979 起，被占则依次试 8980-8983（模块侧按同序探测）。</p>
 */
public final class AssetServer {

    public static final int PORT_FIRST = 8979;
    public static final int PORT_LAST = 8983;
    private static final long MAX_BYTES = 80L * 1024 * 1024;

    private static volatile ServerSocket server;
    private static File dir;
    private static int port = -1;

    private AssetServer() {
    }

    /** 幂等启动；dataDir = 官方 App 的 /data/data/com.cfmoto。 */
    public static void start(String dataDir) {
        if (server != null) {
            return;
        }
        try {
            dir = new File(dataDir, "files/speedhunter_art");
            if (!dir.exists() && !dir.mkdirs()) {
                HookLog.log("artserver: mkdirs failed: " + dir);
                return;
            }
            for (int p = PORT_FIRST; p <= PORT_LAST; p++) {
                try {
                    server = new ServerSocket(p, 8, InetAddress.getByName("127.0.0.1"));
                    port = p;
                    break;
                } catch (IOException ignore) {
                    // 端口被占，试下一个
                }
            }
            if (server == null) {
                HookLog.log("artserver: no free port " + PORT_FIRST + "-" + PORT_LAST);
                return;
            }
            final ServerSocket ss = server;
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket c = ss.accept();
                        Thread w = new Thread(() -> serve(c));
                        w.setDaemon(true);
                        w.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "speedhunter-asset");
            t.setDaemon(true);
            t.start();
            HookLog.log("artserver: on 127.0.0.1:" + port + " dir=" + dir);
        } catch (Throwable e) {
            HookLog.log("artserver: start failed: " + e);
        }
    }

    public static int port() {
        return port;
    }

    private static void serve(Socket c) {
        try {
            c.setSoTimeout(30000);
            BufferedInputStream in = new BufferedInputStream(c.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(c, 400, "bad request", null);
                return;
            }
            String method = parts[0];
            String target = parts[1];
            int q = target.indexOf('?');
            String path = q >= 0 ? target.substring(0, q) : target;
            Map<String, String> query = parseQuery(q >= 0 ? target.substring(q + 1) : "");

            long contentLength = 0;
            // 读掉请求头
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                    if (name.equals("content-length")) {
                        try {
                            contentLength = Long.parseLong(line.substring(colon + 1).trim());
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            }

            if ("GET".equals(method) && path.equals("/ping")) {
                respond(c, 200, "ok", "text/plain");
            } else if ("GET".equals(method) && path.startsWith("/f/")) {
                String name = path.substring(3);
                File f = safeFile(name);
                if (f == null || !f.isFile()) {
                    respond(c, 404, "not found", "text/plain");
                } else {
                    sendFile(c, f);
                }
            } else if ("POST".equals(method) && path.equals("/upload")) {
                String name = query.get("name");
                if (name == null || !nameValid(name)) {
                    respond(c, 400, "bad name", "text/plain");
                    return;
                }
                if (contentLength <= 0 || contentLength > MAX_BYTES) {
                    respond(c, 413, "bad size " + contentLength, "text/plain");
                    return;
                }
                File out = new File(dir, name);
                File tmp = new File(dir, name + ".part");
                try (OutputStream os = new java.io.FileOutputStream(tmp)) {
                    copyExactly(in, os, contentLength);
                }
                if (out.exists() && !out.delete()) {
                    HookLog.log("artserver: cannot replace " + out.getName());
                }
                if (!tmp.renameTo(out)) {
                    respond(c, 500, "rename failed", "text/plain");
                    return;
                }
                HookLog.log("artserver: stored " + name + " (" + contentLength + " B)");
                respond(c, 200, url(name), "text/plain");
            } else {
                respond(c, 404, "no route", "text/plain");
            }
        } catch (Throwable e) {
            HookLog.log("artserver: serve error: " + e);
        } finally {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }

    private static void copyExactly(InputStream in, OutputStream os, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        while (done < n) {
            int want = (int) Math.min(buf.length, n - done);
            int r = in.read(buf, 0, want);
            if (r < 0) {
                throw new IOException("early EOF at " + done);
            }
            os.write(buf, 0, r);
            done += r;
        }
    }

    private static void sendFile(Socket c, File f) throws IOException {
        byte[] head = ("HTTP/1.0 200 OK\r\nContent-Type: " + contentType(f.getName())
                + "\r\nContent-Length: " + f.length()
                + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII");
        OutputStream os = c.getOutputStream();
        os.write(head);
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) >= 0) {
                os.write(buf, 0, r);
            }
        }
        os.flush();
    }

    private static void respond(Socket c, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.0 " + code + " " + (code == 200 ? "OK" : "ERR")
                + "\r\nContent-Type: " + (type == null ? "text/plain" : type)
                + "; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream os = c.getOutputStream();
        os.write(head.getBytes("US-ASCII"));
        os.write(b);
        os.flush();
    }

    private static File safeFile(String name) {
        return nameValid(name) ? new File(dir, name) : null;
    }

    private static boolean nameValid(String name) {
        if (name == null || name.isEmpty() || name.length() > 80) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
            if (!ok) {
                return false;
            }
        }
        return !name.equals(".") && !name.equals("..") && !name.endsWith(".part");
    }

    private static String contentType(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".glb")) return "model/gltf-binary";
        return "application/octet-stream";
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new HashMap<>();
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                out.put(urlDecode(kv.substring(0, eq)), urlDecode(kv.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String urlDecode(String s) {
        return s.replace("%20", " ").replace("+", " ");
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') {
                break;
            }
            if (ch != '\r') {
                sb.append((char) ch);
            }
        }
        return ch < 0 && sb.length() == 0 ? null : sb.toString();
    }

    public static String url(String name) {
        return "http://127.0.0.1:" + port + "/f/" + name;
    }
}