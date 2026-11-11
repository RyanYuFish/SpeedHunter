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
 * ABS 开关入口（AE5i Pro 云端配置过滤补显示），2026-11-12。
 *
 * <h3>问题</h3>
 * <p>官方 App 里 AE5i Pro 的车辆设置没有「ABS开关」行。实采证据：</p>
 * <ul>
 *   <li>App 资源已有全套文案：{@code abs}=ABS、{@code abs_tip}=ABS指防抱死制动系统…、
 *       {@code anti_lock_brake_system}=ABS开关（{@code res/values/strings.xml}）。</li>
 *   <li>脱壳 dex 已有枚举 {@code ANTI_LOCK_BRAKE_SYSTEM}、{@code CHANGE_ABS_SWITCH}、
 *       字段 {@code antiLockBrakeSystem}。</li>
 *   <li>云端 {@code vehicleSet/items}（48/53 项）与 {@code itemsV2} 的
 *       {@code vehicleTypeDetailFunctionVOList}（52 项）均无该条目；
 *       {@code DRIVING_MODE_SETTING}（骑行偏好设置）subFunctions 仅
 *       动能回收 / TCS / 最高车速 / 电子制动。</li>
 *   <li>AE7 实采 {@code vehicleSetInfo.antiLockBrakeSystem=1}（字段存在），AE5i 无。</li>
 * </ul>
 * ⇒ 与氮气入口同构：**渲染器按云端下发列表建行**，AE5i 被服务端过滤掉该功能。
 *
 * <h3>做法（复刻 {@link NitroUnlock} v4.7.9 实车验证路径）</h3>
 * <ol>
 *   <li>设置列表（元素带 {@code functionCode}+{@code isSupportSetting}）缺
 *       {@code ANTI_LOCK_BRAKE_SYSTEM} → 深拷贝首元素改写注入。</li>
 *   <li>{@code DRIVING_MODE_SETTING} 分类的 {@code subFunctions} 缺该项 →
 *       克隆兄弟子项（functionName=ABS开关、describe=abs_tip 原文）。</li>
 *   <li>带 {@code etcsTractionControl} 的 {@code vehicleSetInfo.data} 对象缺
 *       {@code antiLockBrakeSystem} → 补 1（AE7 实采稳态，默认开启的诚实状态）。</li>
 * </ol>
 *
 * <h3>红线</h3>
 * <p><b>纯显示，不构造任何车辆写帧</b>。克隆条目清空
 * {@code blueToothServiceCodeList}/{@code functionConfig} —— AE5i 的 ABS 写入
 * serviceCode 未取证（52 项列表无该条目），保留克隆来的兄弟 serviceCode 会误写到
 * 别的真实功能。拨动 App 内该开关的写入是否生效<b>未验证</b>，不应依赖。
 * 不改 identity/deviceType/vehicleKind(s)。opt-in，默认关。</p>
 *
 * <p>固件侧证据（另见 {@code docs/features/abs-switch.md}）：
 * {@code libmcucommodule.so} 有 {@code mcucom_set_ABS(int)}、
 * {@code mcucom_rx_RspABS(int)} / {@code carSettings.ABS}、
 * {@code MCUCOM_SIGNAL_ABS_MODE}；下行属性 {@code 0x21409308}（EMULATION §29.1）；
 * {@code libnipcmodule.so} 记 {@code m_vehsetitem2-1: ABS/MTC/ESS/DriMode}；
 * 配置字 {@code cfg[6] bits[6:5]} = 「ABS 可设置」（docs/70 §2）。</p>
 */
public final class AbsUnlock {

    private static final String TAG = HookLog.NET;

    /** 推定 functionCode（脱壳 dex 枚举常量名）。实车若行不出现，见文档 §证据等级。 */
    static final String FUNCTION = "ANTI_LOCK_BRAKE_SYSTEM";
    static final String FIELD_STATE = "antiLockBrakeSystem";
    static final String PARENT_CATEGORY = "DRIVING_MODE_SETTING";
    static final String NAME_CN = "ABS开关";
    static final String TIP_CN = "ABS指防抱死制动系统，紧急刹车时防车轮锁死以维持车辆转向与制动效果。";
    /** vehicleSetInfo.data 的特征字段（AE7 实采），用于限定补状态字段的对象。 */
    private static final String STATE_ANCHOR = "etcsTractionControl";

    private static final Set<String> MISS_LOGGED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private AbsUnlock() {
    }

    public static boolean enabled() {
        return RemoteSettings.getBool(Keys.KEY_ABS_UI, false);
    }

    public static final class Result {
        public int stateAdded;
        public int injected;
        public int injectedSub;

        public boolean changed() {
            return stateAdded > 0 || injected > 0 || injectedSub > 0;
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
                HookLog.log(TAG + " abs unlock: state=" + r.stateAdded
                        + " inject=" + r.injected + " sub=" + r.injectedSub
                        + "  @ " + shortUrl(url));
            } else if (MISS_LOGGED.add(stripQuery(url))) {
                HookLog.log(TAG + " abs unlock: nothing to do  @ " + shortUrl(url));
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " abs unlock failed: " + t);
        }
        return r;
    }

    private static void walk(Object node, String parentCode, Result r) throws Throwable {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has(STATE_ANCHOR) && !obj.has(FIELD_STATE)) {
                obj.put(FIELD_STATE, 1);
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
            if (hasFunction(array)) {
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

    private static boolean isSettingsList(JSONArray array) {
        JSONObject first = array.optJSONObject(0);
        return first != null && first.has("functionCode") && first.has("isSupportSetting");
    }

    private static boolean isSubFunctionList(JSONArray array) {
        JSONObject first = array.optJSONObject(0);
        return first != null && first.has("functionCode") && first.has("functionName")
                && !first.has("isSupportSetting");
    }

    private static boolean hasFunction(JSONArray array) {
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

    private static JSONObject cloneAs(JSONObject template, boolean setting) throws Throwable {
        if (template == null) {
            return null;
        }
        JSONObject o = new JSONObject(template.toString());
        o.put("functionCode", FUNCTION);
        if (setting) {
            // 清空所有可能携带真实写入码的字段，避免误写到兄弟功能。
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
