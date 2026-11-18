package com.github.speedhunter.ride;

import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.core.Keys;
import com.github.speedhunter.core.RemoteSettings;

/**
 * 骑行指标计算的全部可调参数（对应技术文档里的 {@code Config.java}）。
 *
 * <p><b>为什么全部改成运行时可读</b>：压弯 / 急刹的阈值是<b>标定值</b>，不是物理常数。
 * 实测把 {@code BEND_V_MIN} 从 25 降到 10 之后召回高得离谱（7 月整月 1211 次压弯，
 * 而人肉体感一天只有个位数到十几次）—— 这种东西必须能在车上直接调、当场看效果，
 * 而不是改一个常量、编译、装机、重灌一遍。
 * 现在每个参数都能在模块首页的「压弯标定」页里拖动滑块，实时生效（走
 * {@code RemoteSettings} 广播，hook 进程内存即时生效 + 落盘，冷重启也还在）。</p>
 *
 * <p><b>红线</b>：参数只影响模块自己算出来的数，不影响请求/响应的任何原始字段。
 * 服务端给了真值的地方永远以服务端为准（见 {@code RideFill#isZero} 的调用点）。</p>
 *
 * <p>改参数后记得点「清空 day store 再回填」—— 已经落盘的汇总是用旧参数算的，
 * 不会因为改了参数而自动重算（原始轨迹不常驻，只留汇总）。</p>
 */
public final class RideConfig {

    // ==================== 参数默认值 ====================
    // ↓↓↓ 下面这些 DEFAULT_* 同时也是 UI 滑块的初始位置，改默认值只要改这里 ↓↓↓

    /**
     * 压弯速度下限默认值（km/h）。
     *
     * <p>★ 2026-11-18 多样本标定后<b>维持 20</b>：以权威评估器
     * （{@code tools/ride/eval_traj.py}，Java 严格复刻）在 5 趟 ≥3km 长骑上
     * 扫描，v_min=20/cum=50 是总误差最小组合（|err|=15）；v_min=25 反而
     * 把 6.2 km 趟从 2 算成 1。早先 /tmp 扫描脚本变体得出的 25/45 在权威
     * 实现下 |err|=18，已废弃。低速弯的排除由 CUM_MIN 50 与 MIN_KM 3.0 承担。</p>
     */
    public static final double DEF_BEND_V_MIN = 20;
    /** 单步转角阈值默认值（度）。 */
    public static final double DEF_BEND_SEED = 5;
    /**
     * 最小累计转角默认值（度）。
     *
     * <p>★ 2026-11-16 单样本标定取 60；<b>2026-11-18 多样本标定下调为 50</b>
     * （见 {@code docs/features/ride-calibration.md} 多样本复核一节）。</p>
     *
     * <p>单样本 60 的问题：47.2 km 那趟恰好命中，但同账号另外几趟长骑系统性
     * <b>少算</b>——26.3 km 趟真值 13 只算出 5、24.5 km 夜骑真值 23 只算出 10。
     * 权威评估器在 5 趟 ≥3km 样本上的 CUM_MIN 扫描（v_min=20，cap=90）：</p>
     * <pre>
     *   cum=40 → 总|err|=22    cum=45 → 18    cum=48 → 17
     *   cum=50 → 15（最优）    cum=55 → 20    cum=60 → 23（旧值）
     * </pre>
     * <p>取 50 后分趟预测 15/3/1/11/17 vs 真值 23/3/2/13/13：24.5 / 26.3 km
     * 两趟接近命中（少 8 / 少 2）；47.2 km 多算 4（其 >60° 的大弯段多）、
     * 6.2 km 少算 1 —— 结构性偏差属 GPS 10 s 采样的固有边界，单阈值族无法
     * 同摊最优（早先变体扫描里 24.5 km 要 ≤40、47.2 km 要 60，50 是权威
     * 实现下的总误差最小点）。</p>
     *
     * <p><b>边界</b>：真值来自车机 IMU（倾斜角 + 加速度计），GPS 只能近似 ——
     * 目标是量级一致，不是逐条相等。物理模型（横向加速度 v²·κ 判弯）在 10 s
     * 采样下已证伪（平滑后逐点曲率过小，6 趟总 |err| ≥ 50），累计转角法是
     * 该采样率下可用的最优族。</p>
     */
    public static final double DEF_BEND_CUM_MIN = 50;
    /** 累计转角上限默认值（度）。 */
    public static final double DEF_BEND_CUM_CAP = 90;
    /** 压弯判定系数默认值。 */
    public static final double DEF_BEND_COEF = 1.0;
    /**
     * 急刹标定系数默认值 —— <b>恒为 1.0</b>，不再放大。
     *
     * <p>★ 旧值是 2.5，理由是「双窗口会把同一脚刹车数两次，乘回去」。
     * 这个理由在 2026-11-16 的 IMU 对标中被证伪：双窗口的重复应当<b>合并</b>，
     * 乘一个倍数只会让结果凭空变 2.5 倍（47.2 km 样本：真实 5 次，旧算法 70 次）。
     * 现在 {@link BrakeDetector} 已改为逐段物理减速度 + 时间合并，本系数只作
     * 用户手动微调用，默认 1.0。</p>
     */
    public static final double DEF_BRAKE_CALIB = 1.0;
    /** 压弯速度上限默认值（km/h）。 */
    public static final double DEF_BEND_V_MAX = 80;
    /**
     * 最短行程门槛默认值（km）—— 短于此里程的行程整趟记 0 次压弯。
     *
     * <p>★ 原为 1.0；2026-11-16 IMU 对标提到 2.0；<b>2026-11-18 多样本提到
     * 3.0</b>：AE7 Pro 账号 11 月的 1.6 / 1.7 / 2.0 km 三趟短程车机真值全是
     * 0 次压弯，而 2.0 km 那趟在 cum=45 下 GPS 仍会算出 2 个 60°+ 的假弯
     * （小区内 U 形掉头）。1–3 km 的行程几乎都是「取快递/接送」级别的短途，
     * 其中的大角度转向是路口掉头而非压弯，整趟判 0 比逐弯硬判更贴近体感。</p>
     *
     * <p>更早的量级参考（16 趟真实轨迹）：</p>
     * <pre>
     *   &lt;0.5km  → 787 次/100km   ← 纯噪声，GPS 在停车状态下乱飘
     *   0.5-1km  →   0 次/100km
     *   1-3km    → 198 次/100km
     *   3-10km   →  90 次/100km   ← 正常
     *   ≥10km    →  98 次/100km   ← 正常
     * </pre>
     */
    public static final double DEF_BEND_MIN_KM = 3.0;

    /** 急刹：速度下限（km/h）—— 起点低于此值的减速不算急刹（停车蠕行）。 */
    public static final double BRAKE_V_MIN = 15;
    /**
     * 急刹：平均减速度阈值（m/s²，≈0.12 g）。
     *
     * <p>★ 2026-11-16 单样本标定 1.04；<b>2026-11-18 多样本标定上调为 1.20</b>。
     * 1.04 在 47.2 km 那趟恰好 5 次，但在同账号其它长骑上系统性<b>多算</b>：
     * 26.3 km 趟真值 5 算出 10、24.5 km 趟真值 0 算出 2。5 趟 ≥3km 样本细扫：</p>
     * <pre>
     *   a_min=1.04 → 总|err|=9（0,2,1,10,5 vs 真值 0,0,1,5,5）
     *   a_min=1.19–1.22 → 总|err|=5（0,1,0,5,2）★ 平台区，取中点 1.20
     *   a_min=1.05–1.18 → 6–11；a_min≥1.23 → 6（47.2 km 趟继续掉）
     * </pre>
     * <p>1.20 时 26.3 km 趟精确命中 5；47.2 km 趟少算 3（其五个真值候选里最弱
     * 的三个只有 -1.08/-1.07/-1.05，10 s 采样把尖峰摊平了，任何阈值都无法
     * 同时满足两趟 —— 1.20 是总误差最小点）。</p>
     *
     * <p><b>边界</b>：经验值，不是物理常数；场景差异大时可用
     * {@link #brakeCalib()} 微调。iOS 侧 {@code ZeehoRideFill.js#C.A_MIN} 同步为 1.20。</p>
     */
    public static final double BRAKE_A_MIN = 1.20;
    /**
     * 急刹：减速度上限（m/s²）—— 超过视为 GPS 跳点，直接丢弃。
     *
     * <p>实测样本里出现过 {@code 31.6→25.6 km/h / 10 s} 却算出 -19 m/s² 的
     * 异常段（坐标错位），不设上限会被当成一次「超级急刹」。</p>
     */
    public static final double BRAKE_A_MAX = 5.0;
    /**
     * 同一脚刹车的合并间隔（秒）。
     *
     * <p>★ 旧实现用 {@code BRAKE_MERGE_GAP=5 点}，注释写的是「5 点」但含义被当成
     * 5 秒。实际轨迹是 <b>10 秒/点</b>（车机上传频率），5 点 = 50 秒，
     * 会把两次独立急刹合并掉。改为按秒计。</p>
     */
    public static final double BRAKE_MERGE_SEC = 10.0;
    /** 相邻点时间差超过这个就断段（毫秒）—— 与压弯共用同一采样连续性判据。 */
    public static final double BRAKE_GAP_MS_MAX = 25000;

    /** 压弯：单步转角超过这个就不信（GPS 抖动 / 掉点）（度）。 */
    public static final double BEND_STEP_MAX = 45;
    /** 压弯：一个弯最少占几个点。 */
    public static final int BEND_RUN_MIN = 1;
    /** 压弯：同向弯合并间隔（点数），0 = 不合并。 */
    public static final int BEND_MERGE_GAP = 0;
    /** 压弯：异向弯之间允许空几个点仍算同一个弯，0 = 过渡点直接断开。 */
    public static final int BEND_ALLOW_GAP = 0;
    /** 短于此距离的相邻点对不参与航向计算（米）。 */
    public static final double BEND_SEG_DIST_MIN = 6;
    /** 相邻点时间差超过这个就断段（毫秒）。 */
    public static final double BEND_GAP_MS_MAX = 25000;

    /** 移动时长判定速度阈值（km/h）。 */
    public static final double MOVING_V_MIN = 5;
    /** 判定「有效采样点」的最少点数。 */
    public static final int MIN_POINTS = 4;
    /** 地球半径（米）。 */
    public static final double EARTH_R = 6371000;

    /** 导出轨迹样本时每趟最多写多少个点（避免把 external files 撑爆）。 */
    public static final int EXPORT_MAX_POINTS = 1500;

    private RideConfig() {
    }

    // ==================== 运行时参数（UI 可调） ====================

    /**
     * 压弯速度下限（km/h）。
     *
     * <p>★ 最影响召回的参数：25→10 会把市区低速转弯、小区掉头全算进来
     * （实测 7 月从「6 天 20 条」变成「21 天 81 条、整月 1211 次」，明显偏多）。
     * 调到 15~20 比较接近人肉体感。</p>
     */
    public static double bendVMin() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_V_MIN, DEF_BEND_V_MIN), 1, 60);
    }

    /** 单步转角阈值（度）：调大 → 只认明显的转弯，漏掉小弯。 */
    public static double bendSeed() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_SEED, DEF_BEND_SEED), 1, 20);
    }

    /**
     * 最小累计转角（度）：★ 最有效的旋钮，调大 → 只认完整的明显弯道。
     *
     * <p>默认 60 是 IMU 对标结论（见 {@link #DEF_BEND_CUM_MIN}）。
     * 调到 30 以下会把路口小转弯算进来，密度回升到 100+ 次/100km。</p>
     */
    public static double bendCumMin() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_CUM_MIN, DEF_BEND_CUM_MIN), 5, 90);
    }

    /** 累计转角上限（度）：超过就不算弯（原地画圈）。 */
    public static double bendCumCap() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_CUM_CAP, DEF_BEND_CUM_CAP), 20, 180);
    }

    /** 压弯判定系数：最终结果 = 原始计数 × 本值，纯粹的整体缩放。 */
    public static double bendCoef() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_COEF, DEF_BEND_COEF), 0.1, 2.0);
    }

    /**
     * 最短行程门槛（km）：短于此里程的行程整趟记 0 次压弯。
     *
     * <p>★ 实测最有效的旋钮 —— 短行程的压弯密度是长途的 8 倍（见 {@link #DEF_BEND_MIN_KM}）。</p>
     */
    public static double bendMinKm() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_MIN_KM, DEF_BEND_MIN_KM), 0, 20);
    }

    /** 压弯速度上限（km/h）：超过整段丢弃（高速过弯不算压弯）。 */
    public static double bendVMax() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_V_MAX, DEF_BEND_V_MAX), 30, 120);
    }

    /**
     * 急刹标定系数：仅作手动微调，默认 1.0。
     *
     * <p>★ 旧默认 2.5 已废除 —— 见 {@link #DEF_BRAKE_CALIB}。现在
     * {@link BrakeDetector} 用时间合并去重，不再需要「乘回去」。</p>
     */
    public static double brakeCalib() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BRAKE_CALIB, DEF_BRAKE_CALIB), 0.5, 5.0);
    }

    /** 是否把原始轨迹样本导出到文件（标定用；导出后可在电脑上离线扫参数）。 */
    public static boolean exportTrack() {
        return RemoteSettings.getBool(Keys.KEY_TUNE_EXPORT, false);
    }

    /**
     * 是否逐 run 打印压弯判定过程（<b>复刻 iOS 侧 {@code C.DEBUG} + {@code debugPrint()}</b>）。
     *
     * <p>打开后每趟行程的日志里会多一行：候选 run 的下标区间、累计转角、方向、段内最高速，
     * 以及被拒的原因（{@code cum<min / cum>cap / speed>max / short / merged}）。</p>
     *
     * <p><b>默认关</b>：每趟一行、每行最多 900 字符，7 月 81 趟就是 81 行，
     * 翻日志会被刷屏。调参时用 {@code tune_export} 那个开关一起打开即可。</p>
     */
    public static boolean debugBend() {
        return RemoteSettings.getBool(Keys.KEY_DEBUG_BEND, false);
    }

    /** 诊断开关的出厂默认（UI 侧滑块初始位置用；恒为关）。 */
    public static boolean debugBendDefault() {
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || v <= 0) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    /** 一行摘要，进日志方便对账。 */
    public static String describe() {
        return String.format(java.util.Locale.ROOT,
                "bend{vMin=%.0f seed=%.0f cum=%.0f..%.0f coef=%.2f vMax=%.0f minKm=%.2f}"
                        + " brake{calib=%.2f}",
                bendVMin(), bendSeed(), bendCumMin(), bendCumCap(),
                bendCoef(), bendVMax(), bendMinKm(), brakeCalib());
    }

    static void logConfig() {
        HookLog.log(HookLog.RIDE + " config " + describe());
    }
}
