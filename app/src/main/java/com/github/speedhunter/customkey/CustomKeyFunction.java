package com.github.speedhunter.customkey;

/**
 * 官方 App {@code CustomButtonCode.commandCode} 的功能码表（23 项）。
 *
 * <h3>来源与真实性</h3>
 * <p>码表与官方 App「自定义按键 → 选择按键功能」列表逐项一致，并有下列实证：</p>
 * <ul>
 *   <li><b>AE5i 云端实测</b>（DashProbe v0.5.0，本车 VIN 358122500143678）：
 *       {@code commandCode=1/3/19/31} 对应
 *       {@code SAY_HELLO / CUSHION_UNLOCK / STEEP_SLOPE_DESCENT / NO_OPERATION}，
 *       名称与本表完全一致。</li>
 *   <li><b>仪表固件静态分析</b>：{@code BaseWidget::CustomKeyFunctionSet} 只对
 *       7 / 10 / 15 / 20 有专属分支（7 = 切换信息组、10 = 静音、20 = 音量增减），
 *       其余落到 {@code sig_CustomKey} 交 MCU；31（{@code 0x1f}）= 无操作。</li>
 *   <li><b>官方 App 资源</b>：BSD 盲区监测 / 最高车速限制 / 陡坡缓降 / 动能回收
 *       等名称均可在 {@code res/values/strings.xml} 找到。</li>
 * </ul>
 *
 * <p>⚠ 并非 23 项都在 AE5i 上生效：云端只下发 1/3/19/31，仪表只处理 7/10/20，
 * 其余为 MCU 侧功能，本车型未验证。{@link Entry#source} 标注了证据等级，
 * UI 会把「AE5i 未验证」明确告诉用户。</p>
 */
public final class CustomKeyFunction {

    /** 证据等级：AE5i 云端 functionConfig 实测下发。 */
    public static final int SRC_CLOUD = 2;
    /** 证据等级：AE5i 仪表固件 {@code CustomKeyFunctionSet} 有专属分支。 */
    public static final int SRC_DASH = 1;
    /** 证据等级：仅见于官方枚举，落 {@code sig_CustomKey} 交 MCU，AE5i 行为未验证。 */
    public static final int SRC_MCU = 0;

    /** 一项功能：功能码 + 显示名 + 证据等级。 */
    public static final class Entry {
        public final int code;
        public final String name;
        public final int source;

        Entry(int code, String name, int source) {
            this.code = code;
            this.name = name;
            this.source = source;
        }

        /** 列表项文案：「名称 · 编码」。 */
        public String label() {
            return name + "  ·  " + code;
        }

        /** 证据短注（Toast / 副标题用）。 */
        public String sourceNote() {
            switch (source) {
                case SRC_CLOUD:
                    return "AE5i 云端可选";
                case SRC_DASH:
                    return "仪表侧已证";
                default:
                    return "AE5i 未验证";
            }
        }
    }

    /**
     * 与官方 App 列表同序（按功能码升序）。名称与编码不要随意改动 ——
     * 编码就是写进 {@code CustomButtonCode.commandCode} 的原值，仪表不做映射。
     */
    private static final Entry[] ALL = {
            new Entry(1, "Hello 音效 1", SRC_CLOUD),
            new Entry(2, "Hello 音效 2", SRC_MCU),
            new Entry(3, "解锁座垫", SRC_CLOUD),
            new Entry(4, "截图", SRC_MCU),
            new Entry(5, "TCS 设置", SRC_MCU),
            new Entry(6, "ACC 车距调节", SRC_MCU),
            new Entry(7, "切换仪表信息组", SRC_DASH),
            new Entry(8, "打开储物箱", SRC_MCU),
            new Entry(9, "车辆音效静音", SRC_MCU),
            new Entry(10, "媒体静音", SRC_DASH),
            new Entry(11, "动能回收设置", SRC_MCU),
            new Entry(12, "BSD 盲区监测", SRC_MCU),
            new Entry(13, "FCW 前方碰撞预警", SRC_MCU),
            new Entry(14, "RCW 后方碰撞预警", SRC_MCU),
            new Entry(15, "一键导航", SRC_MCU),
            new Entry(16, "录屏", SRC_MCU),
            new Entry(17, "最高车速限制", SRC_MCU),
            new Entry(18, "一键息屏", SRC_MCU),
            new Entry(19, "陡坡缓降设置", SRC_CLOUD),
            new Entry(20, "媒体音量调节", SRC_DASH),
            new Entry(21, "音乐切歌", SRC_MCU),
            new Entry(28, "音乐播放 / 暂停", SRC_MCU),
            new Entry(31, "无操作", SRC_CLOUD),
    };

    private CustomKeyFunction() {
    }

    /** 返回副本，避免调用方改动静态表。 */
    public static Entry[] all() {
        return ALL.clone();
    }

    public static int count() {
        return ALL.length;
    }

    public static Entry find(int code) {
        for (Entry e : ALL) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    public static boolean isKnown(int code) {
        return find(code) != null;
    }

    /** 名称；未知码回落「未知功能」。 */
    public static String nameOf(int code) {
        Entry e = find(code);
        return e != null ? e.name : "未知功能";
    }

    /** 证据短注；未知码返回空串。 */
    public static String noteOf(int code) {
        Entry e = find(code);
        return e != null ? e.sourceNote() : "";
    }
}
