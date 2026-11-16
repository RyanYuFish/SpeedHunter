package com.github.speedhunter.ride;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


/**
 * 急刹检测。
 *
 * <p><b>核心思路：逐段平均减速度</b>，而不是旧版的「双窗口 Δv 扫描」。</p>
 *
 * <ol>
 *   <li><b>逐段算物理减速度</b>：{@code a = (v[i+1] - v[i]) / 3.6 / dt}（m/s²），
 *       {@code dt} 由相邻点的毫秒时间戳得出；</li>
 *   <li><b>四道闸</b>：起点速度 ≥ {@link RideConfig#BRAKE_V_MIN}（蠕行停车不算）、
 *       {@code dt ∈ (0, BRAKE_GAP_MS_MAX]}（丢数据不算）、
 *       {@code a <= -BRAKE_A_MIN(1.04 m/s²)}（够急）、{@code a >= -BRAKE_A_MAX}（GPS 跳点不算）；</li>
 *   <li><b>按时间合并</b>：命中点的间隔 ≤ {@link RideConfig#BRAKE_MERGE_SEC} 视为同一脚
 *       刹车，保留最急的那一段；</li>
 *   <li>结果再乘 {@link RideConfig#brakeCalib()}（默认 1.0，仅供手动微调）。</li>
 * </ol>
 *
 * <h3>★ 为什么推翻旧实现（2026-11-16）</h3>
 * <p>旧实现是两个「点数窗口」扫描 Δv（5 点 / 3 点、Δv ≥ 15 / 20），然后按 5 点合并，
 * 最后乘 2.5。三个问题叠加：</p>
 * <ul>
 *   <li><b>窗口单位错</b>：车机上传的轨迹是 <b>10 秒/点</b>，注释却按「5 秒」写。
 *       5 点实际是 50 秒，等于把两次独立急刹合成一次。</li>
 *   <li><b>a_min 太松</b>：0.5 m/s² 只有 0.05 g，正常收油都能过。</li>
 *   <li><b>calib 2.5 凭空放大</b>：双窗口的重复本该合并，乘倍数只会让结果 ×2.5。</li>
 * </ul>
 * <p>在 AE7 Pro 47.2 km / 732 点 / 121.7 min 的真实样本上：车机真值 <b>5 次</b>，
 * 旧算法算出 <b>70 次</b>（14 倍虚高）。改为本实现后同一份轨迹输出 <b>5 次</b>，与车机一致。</p>
 *
 * <p><b>边界</b>：车机用 IMU（加速度计）统计，GPS 只用速度点差分，10 秒平均会把
 * 瞬时峰值抹平 —— 目标是量级一致，不追求逐次相等。</p>
 *
 * <p>复杂度 O(n)，无排序。1000 点 &lt; 2 ms。</p>
 */
public final class BrakeDetector {

    /** 一个急刹命中段：时刻（毫秒）+ 平均减速度（m/s²，负值）+ 起止速度。 */
    private static final class Hit {
        final double t;
        final double a;
        final double v0;
        final double v1;

        Hit(double t, double a, double v0, double v1) {
            this.t = t;
            this.a = a;
            this.v0 = v0;
            this.v1 = v1;
        }
    }

    private BrakeDetector() {
    }

    public static int count(List<TrackCalculator.Pt> pts) {
        List<Hit> merged = collect(pts);
        long r = Math.round(merged.size() * RideConfig.brakeCalib());
        return r < 0 ? 0 : (int) r;
    }

    /** 逐个命中段打印「哪里算出来、为什么被丢」，只在 {@link RideConfig#debugBend()} 打开时跑。 */
    static String explain(List<TrackCalculator.Pt> pts, String rideId) {
        if (pts == null || pts.size() < 2) {
            return "pts<2";
        }
        List<Hit> merged = collect(pts);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < merged.size() && i < 40; i++) {
            Hit h = merged.get(i);
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(String.format(Locale.ROOT, "H%d@%.0fs a=%.2f %.0f->%.0f",
                    i, (h.t - pts.get(0).t) / 1000.0, h.a, h.v0, h.v1));
        }
        if (sb.length() > 900) {
            sb.setLength(900);
            sb.append("...");
        }
        return "cnt=" + (int) Math.round(merged.size() * RideConfig.brakeCalib())
                + " pts=" + pts.size() + " hits=" + merged.size()
                + (sb.length() == 0 ? "" : " [" + sb + "]")
                + (rideId == null ? "" : (" ride=" + rideId));
    }

    /**
     * 扫出全部命中段并按时间合并 —— {@link #count} 与 {@link #explain} 共用。
     *
     * <p>抽出来是为了诊断输出与真实计数用的是<b>同一份</b>结果，否则「H3 被合并」
     * 这类信息会指向不存在的段。</p>
     */
    private static List<Hit> collect(List<TrackCalculator.Pt> pts) {
        List<Hit> out = new ArrayList<>();
        int n = pts == null ? 0 : pts.size();
        if (n < 2) {
            return out;
        }
        double aMin = RideConfig.BRAKE_A_MIN;
        double aMax = RideConfig.BRAKE_A_MAX;
        double vMin = RideConfig.BRAKE_V_MIN;
        double gapMax = RideConfig.BRAKE_GAP_MS_MAX;
        double mergeSec = RideConfig.BRAKE_MERGE_SEC;

        for (int i = 0; i < n - 1; i++) {
            TrackCalculator.Pt p0 = pts.get(i);
            TrackCalculator.Pt p1 = pts.get(i + 1);
            double dt = (p1.t - p0.t) / 1000.0;
            if (dt <= 0 || dt * 1000.0 > gapMax) {
                continue;                       // 时间倒流 / 中间丢数据
            }
            if (p0.v < vMin) {
                continue;                       // 起步蠕行、停车前最后几米
            }
            double a = (p1.v - p0.v) / 3.6 / dt;
            if (a > -aMin) {
                continue;                       // 不够急
            }
            if (a < -aMax) {
                continue;                       // GPS 跳点（实测出现过 -19 m/s²）
            }
            double t = p1.t;
            if (out.isEmpty() || (t - out.get(out.size() - 1).t) / 1000.0 > mergeSec) {
                out.add(new Hit(t, a, p0.v, p1.v));
            } else {
                Hit last = out.get(out.size() - 1);
                if (a < last.a) {               // 同一脚刹车里取最急的一段
                    out.set(out.size() - 1, new Hit(t, a, p0.v, p1.v));
                }
            }
        }
        return out;
    }
}
