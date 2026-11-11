package com.github.speedhunter.net;

import com.github.speedhunter.core.Keys;
import com.github.speedhunter.core.RemoteSettings;

/**
 * 引导登录的 hook 侧配置读取。与 {@code customkey.CustomKeyOptions} 同一套
 * {@link RemoteSettings} 通道：模块 UI 写入 → 广播 → hook 进程内存即时生效 +
 * 落盘冷启动恢复。
 *
 * <p>凭证来源：其他客户端（几何面板 / Shadowrocket 日志）取得的
 * {@code token} + {@code userId}，由车主手动录入模块 UI。</p>
 */
public final class AccountOptions {

    private AccountOptions() {
    }

    public static boolean enabled() {
        return RemoteSettings.getBool(Keys.KEY_ACCOUNT_LOGIN, false);
    }

    public static String token() {
        return trim(RemoteSettings.getString(Keys.KEY_ACCOUNT_TOKEN, ""));
    }

    public static String userId() {
        return trim(RemoteSettings.getString(Keys.KEY_ACCOUNT_USERID, ""));
    }

    /** 两个凭证都齐才算可用；缺一不注入，避免半套身份把官方 App 请求打歪。 */
    public static boolean ready() {
        return enabled() && !token().isEmpty() && !userId().isEmpty();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
