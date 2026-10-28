package com.github.speedhunter.ui;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

import com.google.android.material.color.DynamicColors;

import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.core.Keys;
import com.github.speedhunter.core.ConfigSender;

/**
 * 模块 App 进程入口。
 *
 * <p>★ 2026-10-04 起<b>不再有日志悬浮窗</b>。原来 {@code LogHud} 只在
 * {@link OtaActivity} 里挂，实测两个问题：悬浮窗会盖在官方 App 上（而 OTA 这步本来就要
 * 来回切 App 看状态），而且平白多要一个 {@code SYSTEM_ALERT_WINDOW} 授权页。
 * 现在日志直接显示在 OTA 操作台页内（Tab 切「操作日志 / 车机日志」）。</p>
 *
 * <p>本进程仍然写 {@link HookLog}（落到本模块 files/speedhunter_hook.log），
 * 完整历史用 logcat tag {@code SpeedHunter} 或 adb 读文件都能拿到。</p>
 *
 * <p>本进程还注册一个 {@link ConfigSyncReceiver}：接住 hook 发来的
 * {@link Keys#ACTION_CONFIG_SYNC} 反向拉取请求，把当前 SharedPreferences 经
 * {@link Keys#ACTION_CONFIG} 重发给 com.cfmoto。这样即使用户「先拨开关、后开官方 App」
 * 导致最初那条 ACTION_CONFIG 被静默丢弃，hook 在 com.cfmoto 启动挂载后也能拿回最新配置。</p>
 */
public final class UiApp extends Application {

    private ConfigSyncReceiver configSyncReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        // 模块自己的进程也开日志（写进本模块 files/speedhunter_hook.log），persist 出问题能查到
        HookLog.init(getPackageName(), getApplicationInfo().dataDir);
        HookLog.log(HookLog.CFG + " module app process attached");
        DynamicColors.applyToActivitiesIfAvailable(this);

        registerConfigSyncReceiver();
    }

    private void registerConfigSyncReceiver() {
        try {
            configSyncReceiver = new ConfigSyncReceiver();
            IntentFilter filter = new IntentFilter(Keys.ACTION_CONFIG_SYNC);
            // 发送方是 com.cfmoto（另一个 App）⇒ 必须 EXPORTED 才能收到跨进程广播
            ContextCompat.registerReceiver(this, configSyncReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            HookLog.log(HookLog.CFG + " ui config-sync receiver registered");
        } catch (Throwable t) {
            HookLog.log(HookLog.CFG + " ui config-sync receiver failed: " + t);
        }
    }

    /**
     * 收到 hook 的 {@link Keys#ACTION_CONFIG_SYNC} 后，把当前 SharedPreferences 重发一遍
     * {@link Keys#ACTION_CONFIG} 给 com.cfmoto。本接收器只注册在模块自己的进程里，
     * 且发送方已 {@code setPackage("com.github.speedhunter")} 定向到本包，无跨 App 误收风险。
     */
    private static final class ConfigSyncReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                SharedPreferences prefs =
                        android.preference.PreferenceManager.getDefaultSharedPreferences(context);
                ConfigSender.pushFromPrefs(context, prefs);
                HookLog.log(HookLog.CFG + " config re-pushed on sync request");
            } catch (Throwable t) {
                HookLog.log(HookLog.CFG + " config re-push failed: " + t);
            }
        }
    }
}
