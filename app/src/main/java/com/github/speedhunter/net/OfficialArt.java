package com.github.speedhunter.net;

/**
 * 官方 2D 车图素材目录（证据驱动，非完整枚举）。
 *
 * <p>官方 2D URL 形如 media/&lt;上传时间戳&gt;.png —— 车型/颜色无法从命名推导，
 * 目录只能靠实捕收集。当前 3 条全部来自 2026-11-12 取证：</p>
 * <ul>
 *   <li>AE5i Pro 白 / AE7 Pro 白 —— 本机账号双车 netdump 的 vehiclePicUrl，
 *       appThemeRgb 均 #131415（1125×708）。</li>
 *   <li>AE5i Pro 深色 —— 社区面板库 zeeho-3d-models mock.json「虚拟车辆1」，
 *       与白色款同批上传（时间戳差 ~11s）、同尺寸、PIL 直方图深色主调。</li>
 * </ul>
 *
 * <p>扩充方式：另一辆车的 App 主页 netdump（vehiclePicUrl）或模块 NetDump 抓到的
 * vehicleHomePageV2 响应，往 ENTRIES 里加行即可。</p>
 */
public final class OfficialArt {

    private OfficialArt() {
    }

    /** 一条官方素材：显示名 / 来源注记 / 2D 图 URL / 主题色。 */
    public static final class Entry {
        public final String label;
        public final String note;
        public final String pic;
        public final String rgb;

        public Entry(String label, String note, String pic, String rgb) {
            this.label = label;
            this.note = note;
            this.pic = pic;
            this.rgb = rgb;
        }
    }

    public static final Entry[] ENTRIES = {
        new Entry("AE5i Pro · 白", "本机账号嗅探",
                "https://oss-cfmoto.zeehoev.com/uploads/zeeho-client-pc/media/1758172630857.png",
                "#131415"),
        new Entry("AE5i Pro · 深色", "社区面板库同款",
                "https://oss-cfmoto.zeehoev.com/uploads/zeeho-client-pc/media/1758172641684.png",
                "#131415"),
        new Entry("AE7 Pro · 白", "本机账号嗅探",
                "https://oss-cfmoto.zeehoev.com/uploads/zeeho-client-pc/media/1757672587864.png",
                "#131415"),
    };
}