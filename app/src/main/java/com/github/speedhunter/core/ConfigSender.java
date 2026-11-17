package com.github.speedhunter.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 把当前 UI 端的 SharedPreferences 经 {@link Keys#ACTION_CONFIG} 推给 hook 进程。
 *
 * <p>UI 侧两处需要发这条广播（保持单一数据源）：</p>
 * <ul>
 *   <li>{@code ui.RideFragment} 用户拨动开关时（主路径）；</li>
 *   <li>接到 hook 的 {@link Keys#ACTION_CONFIG_SYNC} 反向拉取时
 *       （覆盖「先拨开关、后开官方 App」丢广播的时序）。</li>
 * </ul>
 *
 * <p>★ {@code intent.setPackage(Keys.TARGET_PKG)} 不可省：Android 8（API 26）起
 * 隐式广播被系统限制，跨进程动态接收器收不到不带包名的广播。</p>
 */
public final class ConfigSender {

    private ConfigSender() {
    }

    public static void pushFromPrefs(Context context, SharedPreferences prefs) {
        pushFromPrefs(context, prefs, false, false);
    }

    /** 清除凭证 / 登出时用：强制推账号三键（含空值）。 */
    public static void pushFromPrefs(Context context, SharedPreferences prefs,
                                     boolean forceAccount) {
        pushFromPrefs(context, prefs, forceAccount, false);
    }

    /**
     * @param forceAccount 强制下发账号三键（含空值）。仅「清除凭证 / 登出」用：
     *     需要显式把 hook 侧的 token 与 login 清掉；普通同步一律 false，
     *     让 hook 侧继承落盘值，避免 UI 空 prefs 冲掉有效凭证。
     */
    /**
     * @param skipAccount 不推账号三键。反向同步（CONFIG_SYNC）专用：
     *     UI 的 prefs 可能与 hook 落盘值不同步（重装、多进程、旧版本残留），
     *     让同步广播带上账号键会把有效凭证冲成过期的本地值。
     *     账号三键只由 AccountActivity.save() / clear() 显式推送。
     */
    public static void pushFromPrefs(Context context, SharedPreferences prefs,
                                     boolean forceAccount, boolean skipAccount) {
        try {
            Intent intent = new Intent(Keys.ACTION_CONFIG);
            intent.putExtra(Keys.KEY_WEB_LAYER, prefs.getBoolean(Keys.KEY_WEB_LAYER, true));
            intent.putExtra(Keys.KEY_GATE_ANALYSE, prefs.getBoolean(Keys.KEY_GATE_ANALYSE, true));
            intent.putExtra(Keys.KEY_GATE_EVENT, prefs.getBoolean(Keys.KEY_GATE_EVENT, true));
            intent.putExtra(Keys.KEY_VIEW_LAYER, prefs.getBoolean(Keys.KEY_VIEW_LAYER, false));
            intent.putExtra(Keys.KEY_RIDE_FILL, prefs.getBoolean(Keys.KEY_RIDE_FILL, true));
            intent.putExtra(Keys.KEY_CUSTOM_KEY_FORCE,
                    prefs.getBoolean(Keys.KEY_CUSTOM_KEY_FORCE, false));
            // 默认 7 = 切换信息组（与 CustomKeyOptions.TARGET 一致；core 不反向依赖 customkey）
            intent.putExtra(Keys.KEY_CUSTOM_KEY_TARGET,
                    prefs.getInt(Keys.KEY_CUSTOM_KEY_TARGET, 7));
            intent.putExtra(Keys.KEY_NITRO_UI, prefs.getBoolean(Keys.KEY_NITRO_UI, false));
            intent.putExtra(Keys.KEY_ABS_UI, prefs.getBoolean(Keys.KEY_ABS_UI, false));
            // 车模替换：开关 + 三个用户输入值（2D / 3D / 主题色）
            intent.putExtra(Keys.KEY_VEHICLE_ART,
                    prefs.getBoolean(Keys.KEY_VEHICLE_ART, false));
            putIfPresent(intent, Keys.KEY_VEHICLE_ART_PIC,
                    prefs.getString(Keys.KEY_VEHICLE_ART_PIC, ""));
            putIfPresent(intent, Keys.KEY_VEHICLE_ART_PIC3D,
                    prefs.getString(Keys.KEY_VEHICLE_ART_PIC3D, ""));
            putIfPresent(intent, Keys.KEY_VEHICLE_ART_RGB,
                    prefs.getString(Keys.KEY_VEHICLE_ART_RGB, ""));
            // 车漆配色索引（-1 = 关闭；hook 侧按 carColorMaterials.size() 有界保护）
            intent.putExtra(Keys.KEY_VEHICLE_ART_COLOR,
                    prefs.getInt(Keys.KEY_VEHICLE_ART_COLOR, -1));
            // ★ 账号三键：只有 UI 侧【真的存过凭证】才显式下发。
            //   ConfigReceiver 对账号键是「extra 存在才覆盖，否则继承 hook 落盘值」——
            //   若 prefs 为空（用户刚重装/清过模块数据）还硬推 token="" 与 login=false，
            //   就会把 hook 侧已经落盘的有效凭证冲掉，表现为「引导登录突然失效」。
            //   AccountActivity.save() 存完凭证后 prefs 非空，仍会正常下发，不受影响。
            String uiToken = prefs.getString(Keys.KEY_ACCOUNT_TOKEN, "");
            String uiUserId = prefs.getString(Keys.KEY_ACCOUNT_USERID, "");
            if (!skipAccount && (forceAccount || (uiToken != null && !uiToken.isEmpty()
                    && uiUserId != null && !uiUserId.isEmpty()))) {
                intent.putExtra(Keys.KEY_ACCOUNT_LOGIN,
                        prefs.getBoolean(Keys.KEY_ACCOUNT_LOGIN, false));
                intent.putExtra(Keys.KEY_ACCOUNT_TOKEN, uiToken);
                intent.putExtra(Keys.KEY_ACCOUNT_USERID, uiUserId);
            }
            intent.putExtra(Keys.KEY_RIDE_FULLSCAN, prefs.getBoolean(Keys.KEY_RIDE_FULLSCAN, true));
            intent.setPackage(Keys.TARGET_PKG);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
            // 真正的通道是 hook 自己的落盘文件；这里失败不影响主链路
        }
    }

    /** putExtra(String) 的空值保护：null 会让 bundle 序列化崩，统一转空串。 */
    private static void putIfPresent(Intent intent, String key, String value) {
        intent.putExtra(key, value == null ? "" : value);
    }
}
