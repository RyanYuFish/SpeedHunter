package com.github.zeehospeedhunter.diag;

/**
 * 诊断（DoIP/UDS + RS485）区块的开关与常量表 —— 调整行为只改这里。
 * 风格对齐 {@code ota/OtaOptions}：集中放常量 + 红线说明。
 *
 * <h3>两条总线、两套协议（不要混）</h3>
 * <ul>
 *   <li><b>WiFi / DoIP（UDS）</b>：TCP {@code 192.168.49.1:13400}，跑 ISO 13400 + UDS
 *       （0x22 读 DID / 0x19 读故障码 / 0x14 清码 / 0x11 复位 / 0x31 例程）。
 *       连上车机 AP（{@code ZEEHO-xxxx}）即可达；源 IP 绑定由 {@link DiagClient} 自动找同网段地址。</li>
 *   <li><b>RS485 私协议</b>：OBD 针 7/15 @115200 8N1，跑 {@code 43 46 … 45 44}
 *       （见 {@link Rs485}）。本 App 不做 RS485 实时读写，只提供<b>离线抓包解码</b>。</li>
 * </ul>
 *
 * <h3>红线（与 AGENTS.md 一致）</h3>
 * <ul>
 *   <li>只读操作（车辆信息 / 故障码 / DID 扫描 / 数据流 / RS485 解码）随时可跑；</li>
 *   <li><b>写类操作默认硬禁</b>：清故障码 / ECU 复位 / 例程控制必须显式 {@code allowWrite=true}
 *       （见 {@link Routines}），且 UI 要弹确认；</li>
 *   <li>不臆造 ABS 排气 / 胎压匹配的 routine ID，由用户填实测抓到的值；</li>
 *   <li>刷写相关红线（电量 / 断电 / Ready）不属本区块，但复位/清码仍可能影响车辆状态，慎用。</li>
 * </ul>
 */
public final class DiagOptions {

    private DiagOptions() {
    }

    // ==================== DoIP 目标 ====================

    /** 默认 DoIP 网关（ZeeCare 实证）。OTA 推送那条是 192.168.0.1:10950，不要混。 */
    public static final String DOIP_HOST = "192.168.49.1";
    public static final int DOIP_PORT = 13400;

    /** 备用主机：个别固件/组网下 DoIP 也可能落在 0.1（与 OtaSocketPusher.CAR_HOSTS 对齐）。 */
    public static final String DOIP_HOST_ALT = "192.168.0.1";

    // ==================== 写类操作总闸 ====================

    /** 默认禁止一切写操作；UI 的「维修操作」需用户显式勾选 allowWrite 才会放行。 */
    public static final boolean ALLOW_WRITE_DEFAULT = false;

    // ==================== DID 扫描默认值 ====================

    public static final String SCAN_START = "0100";
    public static final String SCAN_END = "0FFF";
    public static final int SCAN_STEP = 1;
    public static final int SCAN_TIMEOUT_MS = 400;

    // ==================== 数据流默认值 ====================

    /** 默认数据流组（见 {@link DataStreams}）。 */
    public static final String DEFAULT_GROUP = "bms";
    public static final int STREAM_TIMEOUT_MS = 800;
}
