package com.github.speedhunter.customkey;

import com.github.speedhunter.core.Keys;
import com.github.speedhunter.core.RemoteSettings;

/**
 * 自定义键映射配置。
 *
 * <p>opt-in：默认关。开关与目标功能码经 {@link Keys#KEY_CUSTOM_KEY_FORCE} /
 * {@link Keys#KEY_CUSTOM_KEY_TARGET} 由模块 UI / 广播下发，落盘到 com.cfmoto
 * 外部 files，冷重启后恢复（见 {@code core.RemoteSettings}）。</p>
 *
 * <p>开启后，{@link CustomKeyForce} 会在 App 保存自定义键设置时，把功能码改写成
 * {@link #targetValue()}（默认 {@link #TARGET} = 7 = 切换信息组，让仪表主页按自定义键
 * 即在里程组 ⇄ 胎压组间切换）。目标可从官方枚举的 23 项里任选，
 * 见 {@link CustomKeyFunction}。</p>
 */
public final class CustomKeyOptions {

    /** 默认目标功能码：7 = 切换信息组（里程组 ⇄ 胎压组）。 */
    public static final int TARGET = 7;

    private CustomKeyOptions() {
    }

    /** 是否启用自定义键映射。默认关。 */
    public static boolean enabled() {
        return RemoteSettings.getBool(Keys.KEY_CUSTOM_KEY_FORCE, false);
    }

    /**
     * 目标功能码。
     *
     * <p>读 {@link Keys#KEY_CUSTOM_KEY_TARGET}，非法 / 未知码一律回落 {@link #TARGET}，
     * 避免把脏值写进官方 App 的 {@code commandCode}。</p>
     */
    public static int targetValue() {
        int value = RemoteSettings.getInt(Keys.KEY_CUSTOM_KEY_TARGET, TARGET);
        return CustomKeyFunction.isKnown(value) ? value : TARGET;
    }
}
