package com.github.zeehospeedhunter.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * 车辆专属功能序列 —— 移植自 zeeho-diag iOS 版 {@code diag.html} 的
 * {@code CAN_PRESETS} / {@code SPE_RS485} / {@code absFlow} 三段。
 *
 * <p>这些是「照着厂商序列发帧」型的功能，**协议层做不了，只能照抄时序**：
 * 一键排气要控制 ABS 泵和阀的开关节奏，胎压匹配要走特定例程，NFC 学卡有固定的命令序列。</p>
 *
 * <h3>红线（与项目既有约定一致）</h3>
 * <ul>
 *   <li>所有序列<b>默认不发</b>，必须由 UI 显式触发；</li>
 *   <li>{@code ABS_PUMP_CYCLE_MS} 那 10 秒是<b>必须真排气</b>的物理过程，不是等待动画
 *       —— 用户必须在这段时间盯着放气螺丝，不能缩短；</li>
 *   <li>中途「停止」只停止<b>后续步骤</b>，泵/阀保持当前状态（与 iOS 版一致），
 *       因为半途停泵可能让刹车管路留残压；</li>
 *   <li>胎压匹配是写操作，沿用 {@code DiagOptions} 的 allowWrite 闸门。</li>
 * </ul>
 */
public final class VehicleRoutines {

    private VehicleRoutines() {
    }

    /** 一步动作：一条 CAN 请求 + 之后的等待时长。 */
    public static final class CanStep {
        public final String label;
        public final String udsHex;
        /** 该步之后等待毫秒。 */
        public final long waitMs;

        public CanStep(String label, String udsHex, long waitMs) {
            this.label = label;
            this.udsHex = udsHex;
            this.waitMs = waitMs;
        }
    }

    // ==================== CAN 预设 ====================

    /**
     * CAN 预设动作（与 iOS 版 {@code CAN_PRESETS.abs} 逐条一致）。
     *
     * <p>目标 ID 由调用方给（iOS 版默认让用户在 {@code canIdSel} 里选，
     * 因为不同车型/不同 ECU 的诊断 ID 不同，这里不写死）。</p>
     */
    public static List<CanStep> canPresets() {
        List<CanStep> l = new ArrayList<>();
        l.add(new CanStep("进入扩展会话", "1003", 400));
        l.add(new CanStep("泵 开", "2FE0DA0301", 400));
        l.add(new CanStep("泵 关", "2FE0DA0300", 300));
        l.add(new CanStep("阀 开（前轮组）", "2FE0DB03000064640000FC", 300));
        l.add(new CanStep("阀 全关", "2FE0DB03000000000000FC", 300));
        l.add(new CanStep("读轮速（31 03 B071）", "3103B071", 400));
        l.add(new CanStep("清故障码", "14FFFFFF", 300));
        l.add(new CanStep("退会话", "1001", 200));
        l.add(new CanStep("ECU 复位", "1101", 200));
        return l;
    }

    /** ABS 泵循环时长（ms）。物理排气时间，<b>不要调小</b>。 */
    public static final long ABS_PUMP_CYCLE_MS = 10_000L;

    /**
     * 一键 ABS 排气流程（与 iOS 版 {@code absFlow} 的 8 步时序逐条一致）。
     *
     * <p>时序：进扩展会话 → 开前轮阀 → 开泵 → <b>泵循环 10 秒（用户在此放气）</b>
     * → 关泵 → 关阀 → 清故障码 → 退会话。</p>
     *
     * <p>用 {@link SeqPlan} 而不是直接把序列写死成 Runnable 链，是为了：
     * UI 能据此渲染「第 N/8 步」的进度文案，且能随时中止。</p>
     *
     * @param canId 目标 CAN 诊断 ID（由用户选，不写死）
     */
    public static SeqPlan absBleedPlan(int canId) {
        List<SeqStep> steps = new ArrayList<>();
        steps.add(new SeqStep("进入扩展会话", canId, "1003", 400));
        steps.add(new SeqStep("开前轮阀", canId, "2FE0DB03000064640000FC", 500));
        steps.add(new SeqStep("开 ABS 泵", canId, "2FE0DA0301", ABS_PUMP_CYCLE_MS, true));
        steps.add(new SeqStep("关 ABS 泵", canId, "2FE0DA0300", 300));
        steps.add(new SeqStep("关阀", canId, "2FE0DB03000000000000FC", 300));
        steps.add(new SeqStep("清除故障码", canId, "14FFFFFF", 300));
        steps.add(new SeqStep("退出会话", canId, "1001", 200));
        return new SeqPlan("一键 ABS 排气", steps);
    }

    // ==================== 胎压匹配（CAN） ====================

    /** 胎压传感器诊断 ID（与 iOS 版一致，0x714）。 */
    public static final int TPMS_CAN_ID = 0x714;

    /** 读胎压传感器 ID。 */
    public static CanStep tpmsReadId() {
        return new CanStep("读胎压传感器 ID", "220802", 600);
    }

    /** 前轮匹配：启动 → 3s 后查结果。 */
    public static CanStep tpmsFrontStart() {
        return new CanStep("前轮匹配-启动", "31015390", 3000);
    }

    public static CanStep tpmsFrontQuery() {
        return new CanStep("前轮匹配-查结果", "31035390", 600);
    }

    /** 后轮匹配：启动 → 3s 后查结果。 */
    public static CanStep tpmsRearStart() {
        return new CanStep("后轮匹配-启动", "31015391", 3000);
    }

    public static CanStep tpmsRearQuery() {
        return new CanStep("后轮匹配-查结果", "31035391", 600);
    }

    /** 停止前轮例程 5390。 */
    public static CanStep tpmsFrontStop() {
        return new CanStep("停止例程 5390", "31025390", 150);
    }

    /** 停止后轮例程 5391。 */
    public static CanStep tpmsRearStop() {
        return new CanStep("停止例程 5391", "31025391", 150);
    }

    // ==================== RS485 特殊序列 ====================

    /** NFC / 初始化 帧序列（与 iOS 版 {@code SPE_RS485.initMsgs} 逐条一致）。 */
    public static List<String> rs485InitMsgs() {
        List<String> l = new ArrayList<>();
        l.add("4346894015000000000020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000FE4544");
        l.add("4346714000000000000020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000D14544");
        l.add("4346714000000000000040000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000F14544");
        return l;
    }

    /** 胎压匹配-初始化 帧序列（与 iOS 版 {@code SPE_RS485.tpmsInitMsgs} 逐条一致）。 */
    public static List<String> rs485TpmsInitMsgs() {
        List<String> l = new ArrayList<>();
        l.add("4346894015000000000020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000FE4544");
        l.add("4346714000000000000024000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000D54544");
        l.add("4346714000000000000040000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000F14544");
        return l;
    }

    /** 胎压匹配帧（与 iOS 版 {@code SPE_RS485.tpmsFrame} 一致）。 */
    public static String rs485TpmsFrame() {
        return "434617023808594544";
    }

    /**
     * NFC 学卡/删卡操作码提示（与 iOS 版 {@code SPE_RS485.nfcTip} 同义改写）。
     *
     * <p>学卡：{@code 3900} → {@code 0D0100} → 放卡 → {@code 0D0103}；
     * 删卡：{@code 3A00} → {@code 0D010103}。</p>
     *
     * <p>⚠️ iOS 版明确标注「成帧方式需实车实测」——这里<b>原样保留该警示</b>，
     * 不臆造已验证的成帧规则。</p>
     */
    public static String nfcTip() {
        return "NFC 学卡/删卡为短命令（操作码：学卡 3900→0D0100→放卡→0D0103；"
                + "删卡 3A00→0D010103），成帧方式需实车实测；"
                + "可先用「自定义帧」或原始帧输入框发送实验。";
    }

    // ==================== 序列计划 ====================

    /** 序列中的一步。 */
    public static final class SeqStep {
        public final String label;
        public final int canId;
        public final String udsHex;
        /** 该步之后等待毫秒。 */
        public final long waitMs;
        /** 是否是需要用户物理操作的长等待步（如泵循环）。 */
        public final boolean isManualHold;

        public SeqStep(String label, int canId, String udsHex, long waitMs) {
            this(label, canId, udsHex, waitMs, false);
        }

        public SeqStep(String label, int canId, String udsHex, long waitMs, boolean isManualHold) {
            this.label = label;
            this.canId = canId;
            this.udsHex = udsHex;
            this.waitMs = waitMs;
            this.isManualHold = isManualHold;
        }
    }

    /** 一个可中止的多步序列。 */
    public static final class SeqPlan {
        public final String title;
        public final List<SeqStep> steps;

        public SeqPlan(String title, List<SeqStep> steps) {
            this.title = title;
            this.steps = steps;
        }

        public int size() {
            return steps.size();
        }
    }
}
