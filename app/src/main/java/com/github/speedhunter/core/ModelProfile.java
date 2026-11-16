package com.github.speedhunter.core;

/**
 * 车型能力表（AE5i Pro vs AE7 Pro）—— UI 与 hook 进程共用，纯常量。
 *
 * <h3>数据来源</h3>
 * <p>2026-11-16 官方 App 真实响应双车差分（NetDump 抓取）：
 * AE5i Pro VIN 358122500143678 的 {@code vehicleSet/items} 48 项、
 * AE7 Pro VIN LCETHGD17S6006301 54 项。详见
 * {@code docs/features/vehicle-model-settings.md}。</p>
 *
 * <h3>为什么要这张表</h3>
 * <p>模块的注入是「给 AE5i 补 AE7 才有的东西」。到了 AE7 上，这些功能
 * <b>原生就有</b>，再 hook 一遍会与官方实现打架（实测闪退）；而 AE5i 上
 * 硬件响应不了的项也不该显示。所以按车型决定 hook 生效与否、以及是否裁项。</p>
 *
 * <p>★ 注意 {@code STEEP_SLOPE_DESCENT}（陡坡缓降）<b>不</b>列入 AE7 独有：
 * itemsV2 明细里 AE5i 没有，但 AE5i 的 items 里确有该码（isSupport=true），
 * 说明 AE5i 支持，误裁会砍掉真实功能。</p>
 */
public final class ModelProfile {

    /** 配置键：已识别的车型（{@link #MODEL_UNKNOWN} = 尚未判定）。 */
    public static final String KEY_VEHICLE_MODEL = "vehicle_model";

    public static final String MODEL_UNKNOWN = "";
    public static final String MODEL_AE5I = "ae5i";
    public static final String MODEL_AE7 = "ae7";

    /**
     * AE7 Pro 独有的 15 项 functionCode —— AE5i 的 items 中<b>不存在</b>。
     *
     * <p>出现任一项即可判定当前车为 AE7 类；在 AE5i 上出现则应裁掉（防御性，
     * 正常服务端不会下发）。</p>
     */
    private static final String[] AE7_ONLY = {
            "AUTOMATIC_LOCK",
            "BATTERY_TEMP",
            "BMS_FIRMWARE",
            "CHARGING_POWER_AE8",
            "CUSTOMIZE_MAX_SPEED",
            "CUSTOM_BUTTON_HIGH",
            "EASY_RIDE",
            "INDUCTIVE_UNLOCKING_CUSTOM_DISTANCE",
            "LIGHTING_EFFECT",
            "POWER_MODE",
            "RADAR_ASSISTED_FCW",
            "SOH",
            "TBOX_FIRMWARE",
            "TCS_TRACTION_CONTROL",
            "TIRE_MONITORING_INTERNAL",
    };

    /** AE5i 独有的 9 项 —— 反向判定 AE5i 类用。 */
    private static final String[] AE5I_ONLY = {
            "BATTERY_LEVEL",
            "BLUETOOTH_ADDRESS",
            "CUSTOM_BUTTON_LOW",
            "E_TCS_TRACTION_CONTROL",
            "LABMS_FIRMWARE",
            "MAXIMUM_SPEED",
            "RADAR_BLIND_MONITOR",
            "SMART_PART",
            "TIRE_MONITORING",
    };

    private ModelProfile() {
    }

    /** functionCode 是否属于「AE7 独有」。 */
    public static boolean isAe7Only(String code) {
        if (code == null || code.isEmpty()) return false;
        for (String c : AE7_ONLY) {
            if (c.equals(code)) return true;
        }
        return false;
    }

    /** functionCode 是否属于「AE5i 独有」。 */
    public static boolean isAe5iOnly(String code) {
        if (code == null || code.isEmpty()) return false;
        for (String c : AE5I_ONLY) {
            if (c.equals(code)) return true;
        }
        return false;
    }

    /** 车型显示名。 */
    public static String label(String model) {
        if (MODEL_AE7.equals(model)) return "AE7 Pro";
        if (MODEL_AE5I.equals(model)) return "AE5i Pro";
        return "未识别";
    }

    /** 该车型是否原生支持某 functionCode（用于决定要不要注入）。 */
    public static boolean nativeSupports(String model, String code) {
        if (MODEL_AE7.equals(model)) return true;      // AE7 是能力全集
        return !isAe7Only(code);                      // AE5i 不支持 AE7 独有项
    }
}
