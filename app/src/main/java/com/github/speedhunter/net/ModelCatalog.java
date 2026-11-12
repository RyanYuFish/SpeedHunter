package com.github.speedhunter.net;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 3D 车模目录加载器（v4.7.68）—— 列表选车型的数据源。
 *
 * <h3>目录来源（lswlc33/zeeho-3d-models，2026-11-12 实测评估）</h3>
 * <ul>
 *   <li>清单 {@code index.json}：{ver, updated, models:[{id,name,file,size,ver,note}]}。</li>
 *   <li>官方原版素材：AE4-MAX2plus.glb（6.2MB，服务端 vehiclePic3dUrl 同源格式）；
 *       其余为第三方趣味模型（适配过相机与居中）。</li>
 *   <li>分发走 CDN，浏览器/客户端均可直连（CORS *，实测 HEAD 200）。</li>
 * </ul>
 *
 * <h3>CDN 回退顺序（借 zeehowebnext 面板 App 的实现）</h3>
 * <ol>
 *   <li>jsDelivr（大陆可达性最好；单文件 &gt; 20MB 会被拒 —— 目录里 36MB 模型自动跳过）；</li>
 *   <li>raw.githubusercontent.com（GitHub 原始内容，无大小限制）；</li>
 *   <li>ghfast.top、gh-proxy.com（raw 的镜像代理，兜底）。</li>
 * </ol>
 *
 * <p>只做 GET + 超时 + 逐级回退，不带任何凭据；结果静态缓存 5 分钟。
 * 模块 UI 在后台线程拉取，回调统一回主线程弹选择列表。</p>
 */
public final class ModelCatalog {

    /** jsDelivr 单文件硬限制 20MB —— 超过的模型直接走 raw/镜像。 */
    static final long JSDELIVR_MAX_BYTES = 20L * 1024 * 1024;

    private static final String REPO_RAW_BASE =
            "https://raw.githubusercontent.com/lswlc33/zeeho-3d-models/main";

    private static final String[] INDEX_URLS = {
            "https://cdn.jsdelivr.net/gh/lswlc33/zeeho-3d-models@main/index.json",
            REPO_RAW_BASE + "/index.json",
            "https://ghfast.top/" + REPO_RAW_BASE + "/index.json",
            "https://gh-proxy.com/" + REPO_RAW_BASE + "/index.json",
    };

    static final long CACHE_MS = 5 * 60 * 1000L;

    /** 目录条目：id/name/note 来自清单，url 已按大小选好 CDN。 */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String note;
        public final String url;
        public final long sizeBytes;

        Entry(String id, String name, String note, String url, long sizeBytes) {
            this.id = id;
            this.name = name;
            this.note = note;
            this.url = url;
            this.sizeBytes = sizeBytes;
        }

        /** 选择列表的副标题：大小 + 备注（note 太长截断）。 */
        public String subtitle() {
            String size = sizeBytes >= 1024 * 1024
                    ? (sizeBytes / (1024 * 1024)) + "MB"
                    : (sizeBytes / 1024) + "KB";
            String tail = note == null ? "" : note;
            if (tail.length() > 40) {
                tail = tail.substring(0, 40) + "…";
            }
            return tail.isEmpty() ? size : size + " · " + tail;
        }
    }

    /** 结果回调（main 线程）：entries 为空列表 = 拉取失败，error 给原因。 */
    public interface Callback {
        void onCatalog(List<Entry> entries, String error);
    }

    private static volatile List<Entry> cache;
    private static volatile long cacheAt;
    private static volatile boolean fetching;

    private ModelCatalog() {
    }

    /** 磁盘缓存有效期：过期后台静默刷新（不阻塞回调）。 */
    static final long DISK_CACHE_MS = 12L * 60 * 60 * 1000;

    /** 兼容旧入口：无磁盘缓存。 */
    public static void load(final Callback callback) {
        load(null, callback);
    }

    /**
     * 带磁盘缓存的加载（v4.8.1 修「点开要等好几秒」）：内存 → 磁盘（立即回调）→ 网络。
     * 目录命中内存/磁盘时点开即达；磁盘过期只触发后台静默刷新，不再让 UI 干等。
     */
    public static void load(final android.content.Context ctx, final Callback callback) {
        List<Entry> cached = cache;
        if (cached != null && System.currentTimeMillis() - cacheAt < CACHE_MS) {
            callback.onCatalog(cached, null);
            return;
        }
        final java.io.File body = diskFile(ctx, "model_catalog.json");
        final java.io.File tsFile = diskFile(ctx, "model_catalog.ts");
        if (body != null && tsFile != null) {
            List<Entry> fromDisk = readDisk(body, tsFile);
            if (fromDisk != null && !fromDisk.isEmpty()) {
                cache = fromDisk;
                cacheAt = readTs(tsFile);
                callback.onCatalog(fromDisk, null);
                if (System.currentTimeMillis() - cacheAt >= DISK_CACHE_MS) {
                    startFetch(ctx, null);   // 静默刷新，无回调
                }
                return;
            }
        }
        startFetch(ctx, callback);
    }

    private static void startFetch(final android.content.Context ctx, final Callback callback) {
        if (fetching) {
            if (callback != null) {
                callback.onCatalog(Collections.emptyList(), "正在拉取目录，稍后再试");
            }
            return;
        }
        fetching = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Entry> entries = null;
                String error = null;
                String winBody = null;
                for (String indexUrl : INDEX_URLS) {
                    try {
                        String resp = httpGet(indexUrl, 8000);
                        List<Entry> parsed = parse(resp);
                        if (!parsed.isEmpty()) {
                            entries = parsed;
                            winBody = resp;
                            break;
                        }
                    } catch (Throwable t) {
                        error = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }
                fetching = false;
                if (entries != null) {
                    cache = entries;
                    cacheAt = System.currentTimeMillis();
                    writeDisk(diskFile(ctx, "model_catalog.json"), winBody);
                    java.io.File ts = diskFile(ctx, "model_catalog.ts");
                    if (ts != null) {
                        writeDisk(ts, String.valueOf(cacheAt));
                    }
                }
                if (callback == null) {
                    return;
                }
                final List<Entry> result = entries == null
                        ? Collections.<Entry>emptyList() : entries;
                final String err = result.isEmpty()
                        ? (error == null ? "目录为空" : error) : null;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onCatalog(result, err);
                    }
                });
            }
        }, "model-catalog").start();
    }

    private static java.io.File diskFile(android.content.Context ctx, String name) {
        if (ctx == null) {
            return null;
        }
        try {
            return new java.io.File(ctx.getFilesDir(), name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeDisk(java.io.File f, String content) {
        if (f == null || content == null) {
            return;
        }
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static long readTs(java.io.File f) {
        try {
            return Long.parseLong(readAll(f).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static List<Entry> readDisk(java.io.File bodyFile, java.io.File tsFile) {
        try {
            long ts = readTs(tsFile);
            if (ts <= 0) {
                return null;
            }
            return parse(readAll(bodyFile));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(java.io.File f) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > 1024 * 1024) {
                    throw new IllegalStateException("磁盘缓存超过 1MB，异常");
                }
            }
        } finally {
            in.close();
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 按文件大小选 CDN：&le;20MB 走 jsDelivr，否则 raw 的 ghfast 镜像。 */
    static String urlFor(String file, long sizeBytes) {
        if (sizeBytes > 0 && sizeBytes <= JSDELIVR_MAX_BYTES) {
            return "https://cdn.jsdelivr.net/gh/lswlc33/zeeho-3d-models@main/models/" + file;
        }
        return "https://ghfast.top/" + REPO_RAW_BASE + "/models/" + file;
    }

    private static List<Entry> parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray models = root.optJSONArray("models");
        if (models == null) {
            return Collections.emptyList();
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m == null) continue;
            String file = m.optString("file", "");
            if (file.isEmpty()) continue;
            long size = m.optLong("size", 0);
            out.add(new Entry(
                    m.optString("id", file),
                    m.optString("name", file),
                    m.optString("note", ""),
                    urlFor(file, size),
                    size));
        }
        return out;
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > 1024 * 1024) {
                    throw new IllegalStateException("index.json 超过 1MB，异常");
                }
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
