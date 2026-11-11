package com.github.speedhunter.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.core.Keys;
import com.github.speedhunter.core.RemoteSettings;

/**
 * 氮气加速入口 v4（AE7 实采 + 全数据通路覆盖，2026-11-07）。
 *
 * <h3>实测定案（AE7 Pro LCETHGD13S6002391 / AE5i Pro 358122500143678 双车差分）</h3>
 * <ul>
 *   <li>{@code vehicleKinds}（homepage）与 {@code vehicleKind}（setInfo）：AE7=3 / AE5i=1。</li>
 *   <li>{@code vehicleSetInfo.data.nitro}：AE7 存在（0）；实测 AE5i <b>也存在</b>（stateAdded=0）。</li>
 *   <li>两车的 items / itemsV2(vehicleTypeDetailFunctionVOList) / vehicleSettingList
 *       <b>均无 NITRO 条目</b>；App 端 AE7 不显示氮气行的原因是<b>智能服务到期</b>
 *       （入口点不进去）——早前「连 AE7 都不渲染 ⇒ App 不支持」判断<b>错误</b>
 *       （车主 2026-11-07 更正）：AE7 氮气 UI 存在，由服务订阅门控。</li>
 * </ul>
 *
 * <h3>v4 改写（opt-in，默认关）—— 覆盖 v3.0.5 渲染器可能读取的每条数据通路</h3>
 * <ol>
 *   <li>{@code vehicleKinds}/{@code vehicleKind}（int≠3）→ 3（G1 先置 2，这里随后覆盖）。</li>
 *   <li>带 {@code vehicleKind} 的对象缺 {@code nitro} 键 → 补 {@code "nitro":0}。</li>
 *   <li><b>克隆注入设置列表</b>：元素带 functionCode+isSupportSetting 的数组（覆盖
 *       vehicleTypeDetailFunctionVOList / vehicleSet/items / vehicleSettingList）缺
 *       NITRO 时，<b>克隆同列表首个元素</b>改写为 NITRO —— 字段形状与该列表 100% 一致；
 *       blueToothServiceCodeList/functionConfig 清空（防误写真实 serviceCode）、
 *       isSupport/isSupportSetting=true。</li>
 *   <li><b>克隆注入分类子项</b>：设置首页分类（vehicleSetFunctionVOList）里
 *       {@code functionCode=DRIVING_MODE_SETTING}（骑行偏好设置）的 {@code subFunctions}
 *       缺 NITRO 时克隆兄弟子项 → functionCode=NITRO、functionName=氮气加速
 *       （首页芯片文案）、describe=nitro_tip 原文。</li>
 * </ol>
 *
 * <h3>边界</h3>
 * <p>纯显示实验；勿拨 App 内该开关（AE5 车端无此功能，写入不会生效）。
 * 身份字段（vehicleType 系）/deviceType 不碰。克隆会清空 serviceCode —— 即使误拨，
 * 也不会打到别的功能的真实 serviceCode。</p>
 */
public final class NitroUnlock {

    private static final String TAG = HookLog.NET;

    static final int KINDS_ON = 3;
    static final String FIELD_STATE = "nitro";
    static final String KEY_KINDS = "vehicleKinds";
    static final String KEY_KIND = "vehicleKind";
    static final String FUNCTION = "NITRO";
    /** 氮气行归属的分类（设置首页「骑行偏好设置」）。 */
    static final String PARENT_CATEGORY = "DRIVING_MODE_SETTING";
    static final String NAME_CN = "氮气加速";
    static final String TIP_CN = "开启氮气加速可短时间提高车辆性能，但开启后会降低续航，请结合实际场景使用";

    private static final Set<String> MISS_LOGGED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private NitroUnlock() {
    }

    public static boolean enabled() {
        return RemoteSettings.getBool(Keys.KEY_NITRO_UI, false);
    }

    public static final class Result {
        public int kinds;
        public int stateAdded;
        public int injected;
        public int injectedSub;

        public boolean changed() {
            return kinds > 0 || stateAdded > 0 || injected > 0 || injectedSub > 0;
        }
    }

    public static Result apply(JSONObject root, String url) {
        if (!enabled() || root == null) {
            return null;
        }
        Result r = new Result();
        try {
            walk(root, null, r);
            if (r.changed()) {
                HookLog.log(TAG + " nitro unlock: kinds=" + r.kinds + " state=" + r.stateAdded
                        + " inject=" + r.injected + " sub=" + r.injectedSub
                        + "  @ " + shortUrl(url));
            } else if (MISS_LOGGED.add(stripQuery(url))) {
                HookLog.log(TAG + " nitro unlock: nothing to do  @ " + shortUrl(url));
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " nitro unlock failed: " + t);
        }
        return r;
    }

    /**
     * 递归遍历。parentCode = 直接父对象的 functionCode（用于定位
     * DRIVING_MODE_SETTING 的 subFunctions）。
     */
    private static void walk(Object node, String parentCode, Result r) throws Throwable {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Object kinds = obj.opt(KEY_KINDS);
            if (kinds instanceof Integer && ((Integer) kinds).intValue() != KINDS_ON) {
                obj.put(KEY_KINDS, KINDS_ON);
                r.kinds++;
            }
            Object kind = obj.opt(KEY_KIND);
            if (kind instanceof Integer && ((Integer) kind).intValue() != KINDS_ON) {
                obj.put(KEY_KIND, KINDS_ON);
                r.kinds++;
            }
            if (obj.has(KEY_KIND) && !obj.has(FIELD_STATE)) {
                obj.put(FIELD_STATE, 0);
                r.stateAdded++;
            }
            String code = obj.opt("functionCode") instanceof String
                    ? (String) obj.opt("functionCode") : null;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                Object child = obj.opt(keys.next());
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    walk(child, code, r);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    walk(child, parentCode, r);
                }
            }
            if (hasNitro(array)) {
                return;
            }
            if (isSettingsList(array)) {
                JSONObject made = cloneAs(array.optJSONObject(0), true);
                if (made != null) {
                    array.put(made);
                    r.injected++;
                }
            } else if (PARENT_CATEGORY.equals(parentCode) && isSubFunctionList(array)) {
                JSONObject made = cloneAs(array.optJSONObject(0), false);
                if (made != null) {
                    made.put("functionName", NAME_CN);
                    made.put("describe", TIP_CN);
                    array.put(made);
                    r.injectedSub++;
                }
            }
        }
    }

    /** 设置列表特征：元素同时带 functionCode 与 isSupportSetting。 */
    private static boolean isSettingsList(JSONArray array) {
        JSONObject first = array.optJSONObject(0);
        return first != null && first.has("functionCode") && first.has("isSupportSetting");
    }

    /** 分类子项特征：元素带 functionCode 与 functionName（且非设置列表）。 */
    private static boolean isSubFunctionList(JSONArray array) {
        JSONObject first = array.optJSONObject(0);
        return first != null && first.has("functionCode") && first.has("functionName")
                && !first.has("isSupportSetting");
    }

    private static boolean hasNitro(JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            Object e = array.opt(i);
            if (e instanceof JSONObject) {
                Object code = ((JSONObject) e).opt("functionCode");
                if (code instanceof String && FUNCTION.equalsIgnoreCase((String) code)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 深拷贝模板元素（toString→parse），改写为 NITRO。
     *
     * @param setting true=设置条目：清空 blueToothServiceCodeList/functionConfig（防误写
     *                真实 serviceCode）、isSupport/isSupportSetting=true、content="";
     *                false=分类子项：仅改 code（functionName/describe 由调用方覆写）。
     */
    private static JSONObject cloneAs(JSONObject template, boolean setting) throws Throwable {
        if (template == null) {
            return null;
        }
        JSONObject o = new JSONObject(template.toString());
        o.put("functionCode", FUNCTION);
        if (setting) {
            if (o.has("blueToothServiceCodeList")) {
                o.put("blueToothServiceCodeList", new JSONArray());
            }
            if (o.has("functionConfig")) {
                o.put("functionConfig", new JSONArray());
            }
            if (o.has("isSupport")) {
                o.put("isSupport", true);
            }
            if (o.has("isSupportSetting")) {
                o.put("isSupportSetting", true);
            }
            if (o.has("content")) {
                o.put("content", "");
            }
            if (o.has("settingFlag")) {
                o.put("settingFlag", "");
            }
        }
        return o;
    }

    private static String stripQuery(String url) {
        if (url == null || url.isEmpty()) {
            return "?";
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "?";
        }
        int scheme = url.indexOf("://");
        String path = scheme >= 0 ? url.substring(scheme + 3) : url;
        return path.length() <= 90 ? path : path.substring(0, 90) + "...";
    }
}
