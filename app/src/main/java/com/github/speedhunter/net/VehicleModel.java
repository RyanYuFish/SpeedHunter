package com.github.speedhunter.net;

import java.util.Iterator;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.core.ModelProfile;
import com.github.speedhunter.core.RemoteSettings;

/**
 * 运行时车型判定（AE7 类 / AE5i 类）。
 *
 * <h3>为什么需要</h3>
 * <p>模块的注入本质是「给 AE5i 补 AE7 才有的功能」。在 AE7 上这些功能原生就有，
 * 再 hook 一遍会与官方实现冲突（实测闪退）；同时 G1 把 {@code vehicleKinds}
 * 写死 2，在 AE7（原生 3）上是<b>降级</b>。所以每次改写前必须先知道车型。</p>
 *
 * <h3>判定顺序</h3>
 * <ol>
 *   <li>响应里出现 {@link ModelProfile} 的 AE7 独有 functionCode ⇒ AE7（强证据）；</li>
 *   <li>出现 AE5i 独有码且无 AE7 独有码 ⇒ AE5i（强证据）；</li>
 *   <li>否则用 VIN 兜底（{@code LCETHGD} = AE7 车系、{@code 3581225} = 本机 AE5i）；</li>
 *   <li>都没有就沿用上次落盘的结果。</li>
 * </ol>
 *
 * <p>只在 {@code vehicleSet/items}、{@code vehicleSetInfo} 这类整车能力响应上判定，
 * 避免局部响应（例如只有一条子功能的对象）误判。</p>
 */
public final class VehicleModel {

    private static final String TAG = HookLog.NET;

    /** 本进程缓存；{@code null} = 尚未读过。 */
    private static volatile String sModel;

    private VehicleModel() {
    }

    /**
     * 观察一份响应并更新车型判定。
     *
     * @param url  请求 URL（含 vinNo 时可兜底）
     * @param root 已解析的响应 JSON
     * @return 当前车型（{@link ModelProfile#MODEL_UNKNOWN} 表示仍未识别）
     */
    public static String observe(String url, Object root) {
        try {
            if (!isModelSource(url)) {
                return current();
            }
            String found = null;
            if (root != null) {
                if (scan(root, true)) {
                    found = ModelProfile.MODEL_AE7;
                } else if (scan(root, false)) {
                    found = ModelProfile.MODEL_AE5I;
                }
            }
            if (found == null) {
                found = fromVin(url);
            }
            if (found != null && !found.equals(sModel)) {
                sModel = found;
                HookLog.log(TAG + " vehicle model -> " + ModelProfile.label(found)
                        + "  @ " + shortUrl(url));
                RemoteSettings.rememberString(ModelProfile.KEY_VEHICLE_MODEL, found);
            }
            return current();
        } catch (Throwable t) {
            return current();
        }
    }

    /** 当前车型（进程缓存 → 落盘配置）。 */
    public static String current() {
        String m = sModel;
        if (m != null && !m.isEmpty()) {
            return m;
        }
        try {
            m = RemoteSettings.getString(ModelProfile.KEY_VEHICLE_MODEL,
                    ModelProfile.MODEL_UNKNOWN);
        } catch (Throwable ignored) {
            m = ModelProfile.MODEL_UNKNOWN;
        }
        sModel = m;
        return m;
    }

    public static boolean isAe7() {
        return ModelProfile.MODEL_AE7.equals(current());
    }

    public static boolean isAe5i() {
        return ModelProfile.MODEL_AE5I.equals(current());
    }

    /** 只在整车能力/车辆信息接口上判定。 */
    private static boolean isModelSource(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("vehicleset/items")
                || u.contains("vehiclesetinfo")
                || u.contains("vehicle/list")
                || u.contains("vehiclehomepage");
    }

    /** VIN 兜底：本机两车的车系前缀（新车可以靠功能码判定，不依赖此表）。 */
    private static String fromVin(String url) {
        if (url == null) return null;
        if (url.contains("LCETHGD")) return ModelProfile.MODEL_AE7;
        if (url.contains("3581225")) return ModelProfile.MODEL_AE5I;
        return null;
    }

    /**
     * 递归找「AE7 独有 / AE5i 独有」的 functionCode。
     *
     * @param ae7 true=找 AE7 独有；false=找 AE5i 独有
     */
    private static boolean scan(Object node, boolean ae7) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Object code = obj.opt("functionCode");
            if (code instanceof String) {
                String c = (String) code;
                if (ae7 ? ModelProfile.isAe7Only(c) : ModelProfile.isAe5iOnly(c)) {
                    return true;
                }
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                Object child = obj.opt(keys.next());
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    if (scan(child, ae7)) return true;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    if (scan(child, ae7)) return true;
                }
            }
        }
        return false;
    }

    private static String shortUrl(String url) {
        if (url == null) return "?";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
