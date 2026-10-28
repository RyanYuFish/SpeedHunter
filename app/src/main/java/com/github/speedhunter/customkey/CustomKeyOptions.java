package com.github.speedhunter.customkey;

import com.github.speedhunter.core.Keys;
import com.github.speedhunter.core.RemoteSettings;

/**
 * 自定义键强制开关。
 *
 * <p>opt-in：默认关。开关经 {@link Keys#KEY_CUSTOM_KEY_FORCE} 由模块 UI / 广播下发，
 * 落盘到 com.cfmoto 外部 files，冷重启后恢复（见 {@code core.RemoteSettings}）。</p>
 *
 * <p>开启后，{@link CustomKeyForce} 会在 App 保存自定义键设置时，把功能码恒置为
 * {@link #TARGET}（7 = 切换信息组），让仪表主页按自定义键即在里程组 ⇄ 胎压组间切换。</p>
 */
public final class CustomKeyOptions {

    /** 目标功能码：7 = 切换信息组（里程组 ⇄ 胎压组）。目前固定为 7。 */
    public static final int TARGET = 7;

    private CustomKeyOptions() {
    }

    /** 是否强制自定义键 = 7。默认关。 */
    public static boolean enabled() {
        return RemoteSettings.getBool(Keys.KEY_CUSTOM_KEY_FORCE, false);
    }

    /** 目标功能码（目前固定 7）。 */
    public static int targetValue() {
        return TARGET;
    }
}
