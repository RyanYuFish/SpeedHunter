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
        try {
            Intent intent = new Intent(Keys.ACTION_CONFIG);
            intent.putExtra(Keys.KEY_WEB_LAYER, prefs.getBoolean(Keys.KEY_WEB_LAYER, true));
            intent.putExtra(Keys.KEY_GATE_ANALYSE, prefs.getBoolean(Keys.KEY_GATE_ANALYSE, true));
            intent.putExtra(Keys.KEY_GATE_EVENT, prefs.getBoolean(Keys.KEY_GATE_EVENT, true));
            intent.putExtra(Keys.KEY_VIEW_LAYER, prefs.getBoolean(Keys.KEY_VIEW_LAYER, false));
            intent.putExtra(Keys.KEY_RIDE_FILL, prefs.getBoolean(Keys.KEY_RIDE_FILL, true));
            intent.putExtra(Keys.KEY_CUSTOM_KEY_FORCE,
                    prefs.getBoolean(Keys.KEY_CUSTOM_KEY_FORCE, false));
            intent.putExtra(Keys.KEY_RIDE_FULLSCAN, prefs.getBoolean(Keys.KEY_RIDE_FULLSCAN, true));
            intent.setPackage(Keys.TARGET_PKG);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
            // 真正的通道是 hook 自己的落盘文件；这里失败不影响主链路
        }
    }
}
