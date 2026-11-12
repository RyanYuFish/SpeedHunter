package com.github.speedhunter.net;

import org.json.JSONArray;
import org.json.JSONObject;

import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.core.Keys;
import com.github.speedhunter.core.RemoteSettings;

/**
 * 车模替换 v1（2026-11-12，DEX 字段级定案后实现）。
 *
 * <h3>依据（v3.0.5 app_full.dex 反查，dist/nitro-fw/dex_{fields,class,grep,ret}.py）</h3>
 * <ul>
 *   <li>{@code MyCarHomePageRsp}：getVehiclePicUrl / getVehiclePic3dUrl /
 *       getVehicleScalePicUrl / getVehicleParkingPhoto / getAppThemeRgb —— 主页车辆视觉
 *       全部由这几个 URL/颜色字段驱动，<b>无 carColorIndex / carColor 字段</b>。</li>
 *   <li>{@code CFFilamentView#loadUrl}：3D 模型按 URL 加载（= vehiclePic3dUrl）；
 *       {@code ZeehoModelProcessor} 持有 carColorMaterials[carColorIndex] 车漆材质表
 *       —— 颜色随 glb 内嵌（服务端按颜色下发不同 glb URL），App 端无独立颜色参数。</li>
 *   <li>APK assets/3d/ 只有 Filament 引擎材质（debug_ibl.ktx / debug_skybox.ktx /
 *       gradient_alpha.filamat），<b>无内置车模</b> —— 换 URL 即换车。</li>
 *   <li>实采（dist/nitro-ui/ae7/netdump.log）：AE5i=.../media/1758172630857.png、
 *       AE7=.../media/1757672587864.png；vehicleScalePicUrl = 同 PNG +
 *       "?x-oss-process=image/resize,m_fixed,w_500,h_315"；两车均无 vehiclePic3dUrl
 *       （服务端未配 3D 模型，当前主页显示 2D PNG）。</li>
 * </ul>
 *
 * <h3>改写</h3>
 * <ol>
 *   <li>{@code vehiclePicUrl} → 用户填的 2D URL（vehicle/list 的 item 同样带，全树替换）。</li>
 *   <li>{@code vehiclePic3dUrl} → 用户填的 glb URL。</li>
 *   <li>{@code vehicleScalePicUrl} → 用户 2D URL + 原 OSS resize 参数（无参则加默认
 *       w_500,h_315 —— 与实采一致），保证列表/缩略场景同步换车。</li>
 *   <li>可选 {@code appThemeRgb} → 用户填的主题色（主页氛围色跟随）。</li>
 * </ol>
 *
 * <p>与 NetPatch 同哲学：不按 URL 分流，任何层级出现目标键都改；
 * 空配置 = 完全不碰。纯显示实验，不写任何车辆身份字段。</p>
 */
public final class VehicleArtUnlock {

    private static final String TAG = HookLog.NET;

    static final String KEY_PIC = "vehiclePicUrl";
    static final String KEY_PIC_3D = "vehiclePic3dUrl";
    static final String KEY_SCALE = "vehicleScalePicUrl";
    static final String KEY_RGB = "appThemeRgb";
    /** 实采 vehicleScalePicUrl 的 OSS 缩放参数（AE5i/AE7 一致）。 */
    static final String DEFAULT_OSS_RESIZE =
            "?x-oss-process=image/resize,m_fixed,w_500,h_315";

    private VehicleArtUnlock() {
    }

    public static boolean enabled() {
        return RemoteSettings.getBool(Keys.KEY_VEHICLE_ART, false);
    }

    public static final class Result {
        public int pic;
        public int pic3d;
        public int scale;
        public int rgb;

        public boolean changed() {
            return pic > 0 || pic3d > 0 || scale > 0 || rgb > 0;
        }
    }

    /**
     * 在已解析的响应树上递归替换车模字段。
     *
     * @return 改写统计；未启用 / 无配置时返回 null（调用方按未改写处理）。
     */
    public static Result apply(JSONObject root, String url) {
        if (!enabled()) return null;
        String pic = RemoteSettings.getString(Keys.KEY_VEHICLE_ART_PIC, "");
        String pic3d = RemoteSettings.getString(Keys.KEY_VEHICLE_ART_PIC3D, "");
        String rgb = RemoteSettings.getString(Keys.KEY_VEHICLE_ART_RGB, "");
        if (pic.isEmpty() && pic3d.isEmpty() && rgb.isEmpty()) return null;

        Result r = new Result();
        try {
            walk(root, pic, pic3d, rgb, r);
            if (r.changed()) {
                HookLog.log(TAG + " art patched " + shortUrl(url)
                        + " pic=" + r.pic + " pic3d=" + r.pic3d
                        + " scale=" + r.scale + " rgb=" + r.rgb);
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " art walk failed: " + t);
        }
        return r.changed() ? r : null;
    }

    private static void walk(Object node, String pic, String pic3d, String rgb, Result r)
            throws org.json.JSONException {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (KEY_PIC.equals(key) && !pic.isEmpty()) {
                    if (!pic.equals(obj.opt(key))) {
                        obj.put(key, pic);
                        r.pic++;
                    }
                    continue;
                }
                if (KEY_PIC_3D.equals(key) && !pic3d.isEmpty()) {
                    if (!pic3d.equals(obj.opt(key))) {
                        obj.put(key, pic3d);
                        r.pic3d++;
                    }
                    continue;
                }
                if (KEY_SCALE.equals(key) && !pic.isEmpty()) {
                    String want = pic + ossSuffix(obj.opt(key));
                    if (!want.equals(obj.opt(key))) {
                        obj.put(key, want);
                        r.scale++;
                    }
                    continue;
                }
                if (KEY_RGB.equals(key) && !rgb.isEmpty()) {
                    if (!rgb.equals(obj.opt(key))) {
                        obj.put(key, rgb);
                        r.rgb++;
                    }
                    continue;
                }
                Object child = obj.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    walk(child, pic, pic3d, rgb, r);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    walk(child, pic, pic3d, rgb, r);
                }
            }
        }
    }

    /**
     * 保留原 vehicleScalePicUrl 上的 OSS 处理参数（?x-oss-process=...），
     * 没有则补默认缩放 —— 保证缩略图尺寸与 App 期望一致，不会因缺参被拉伸。
     */
    private static String ossSuffix(Object oldValue) {
        if (oldValue instanceof String) {
            String s = (String) oldValue;
            int q = s.indexOf('?');
            if (q >= 0) return s.substring(q);
        }
        return DEFAULT_OSS_RESIZE;
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        int slash = url.lastIndexOf('/');
        return slash >= 0 && url.length() - slash < 60
                ? url.substring(slash + 1) : url;
    }
}
