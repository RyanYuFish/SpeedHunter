package com.github.speedhunter.ride;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.ride.RideDayStore.Day;

/**
 * ★ 骑行指标回填编排层（对应技术文档里的 {@code ResponseInjector.java} + {@code FillHook.java}）。
 *
 * <h3>四个接口各自的角色（本车 com.cfmoto v3.0.5 实测）</h3>
 * <table border="1">
 *   <tr><th>接口</th><th>带轨迹？</th><th>能补什么</th></tr>
 *   <tr><td><b>homeRideInfo</b><br>首页骑行卡片</td>
 *       <td>✗ 只有 10 天里程/时长数组</td>
 *       <td>{@code lastRidingTime}（服务端给 "0.0"）、{@code rideMileageDay}</td></tr>
 *   <tr><td><b>ridetrack_v2</b><br>历史轨迹</td>
 *       <td><b>✓ 唯一带 trajectory 的接口</b></td>
 *       <td>急刹 / 压弯 / 里程 / 时长 / 极速（算完落盘）</td></tr>
 *   <tr><td><b>myRideInfo</b><br>我的骑行（按月）</td>
 *       <td>✗</td>
 *       <td>每天的 {@code brakesTimesDay} / {@code bendingTimesDay} / {@code maxSpeed}
 *           / {@code rideMileage}，以及顶部 {@code bendingTimesTotal}</td></tr>
 *   <tr><td><b>analyse/analyse</b><br>骑行分析（按月）</td>
 *       <td>✗</td>
 *       <td>月汇总 {@code brakesTimes} / {@code bendingTimes} / {@code rideMileage}
 *           / {@code ridingTime} / {@code maxSpeed}</td></tr>
 * </table>
 *
 * <p><b>为什么 ridetrack_v2 仍然必须保留</b>：文档说 27.0 已用 homeRideInfo 取代它，
 * 但本车实测 homeRideInfo 里<b>根本没有轨迹字段</b>（只有 {@code mileages} /
 * {@code ridingTimes} 两个 10 元素数组），没有轨迹就算不出急刹和压弯。
 * 「历史轨迹」页依然在用 ridetrack_v2，所以它仍是唯一的轨迹来源。</p>
 *
 * <h3>联动关系</h3>
 * <pre>
 *   ridetrack_v2   逐条算 b/d/里程/时长/极速 → 写回该条 → 按 dayTime 落盘
 *   homeRideInfo   修 lastRidingTime / rideMileageDay 的 0 值
 *   myRideInfo     按 rec.date 读 store → 补 0 值 → bendingTimesTotal = day store 全店累计（本车全生命周期总压弯，2026-11-18 车主令）
 *   analyse        按 rideMonth 聚 31 天 store → 补 0 值
 * </pre>
 *
 * <p><b>红线</b>：只补 0 值 —— 服务端给的真值一律不动（服务端比我们准）。
 * 整个类静默失败：任何异常都不许冒泡到 {@code net.NetPatch}。</p>
 *
 * <h3>云端数据门控 · 按车型 + 按事件类型隔离（2026-11-18 定稿，同日夜修订）</h3>
 * <p>急刹 / 压弯回填<b>只对「云端实际没在统计这辆车」的车型生效</b>，且判定
 * <b>逐 fill 点、只看当前响应自己的数据</b> —— 不做任何跨响应记忆，不扫
 * 非目标响应的全树。原因：本账号同时拥有 AE7 Pro（云端有压弯数据）和
 * AE5i Pro（云端恒 0），任何账号级 / 进程级信号都会被 AE7 的 flag/总数
 * 毒化（v5.2.0 的全树扫描、v5.2.1 的跨响应记忆都栽在这）。而这四个接口
 * 都是<b>按当前车辆查询</b>的，响应本身天然单车，用它自己的数据判定就
 * 不会有别的车型混进来。</p>
 *
 * <p>★ <b>急刹与压弯必须分开判</b>（2026-11-18 车主令 + 实车接口复核）。AE5i Pro
 * 的真实形态是<b>只发急刹、不发压弯</b>：</p>
 * <pre>
 *   2026-07-02 及之前：brakesTimes 有真值（7/1 合计 67、7/2 合计 116），bendingTimes 恒 0
 *   2026-07-03 起    ：两者全 0（云端不再统计这辆车）
 *   2026-06 全月     ：169 趟里 153 趟 brakesTimes &gt; 0，bendingTimes &gt; 0 的 0 趟
 * </pre>
 * <p>旧实现把两个计数塞进同一个 {@code serverManaged(node, "bendingTimes", "brakesTimes")}：
 * 急刹 &gt;0 就把整趟判成「云端在统计」⇒ <b>2026-07-02 之前的压弯永远不回填</b>，
 * 这些天也不会进 day store（连带 myRideInfo / analyse 的月度压弯都补不上）。
 * 现改为逐事件判定（{@link #eventManaged}）：</p>
 * <ul>
 *   <li><b>压弯</b>：{@code bendingTimes} / {@code bendingTimesTotal} / 月
 *       {@code bendingTimes} &gt;0 或 flag=true ⇒ 保持服务端值，否则回填。</li>
 *   <li><b>急刹</b>：{@code brakesTimes} / 逐日 {@code brakesTimesDay} / 月
 *       {@code brakesTimes} &gt;0、flag=true，或日期 ≤
 *       {@link #SERVER_BRAKES_LAST_DAY}（云端在那之前确实在统计急刹）⇒ 保持
 *       服务端值，否则回填。</li>
 * </ul>
 * <p>日期兜底只作用于急刹的「0 值」：2026-07-02 前某趟恰好 0 次急刹是<b>真 0</b>，
 * 不该被 GPS 估算改成正数；压弯不受该日期影响（云端从来没发过）。</p>
 * <p>里程 / 极速的「只补 0 值」不受门控影响。</p>
 */
public final class RideFill {

    private static final String TAG = HookLog.RIDE;

    /** 本次回填改了多少东西 —— 决定要不要重建 ResponseBody。 */
    public static final class Result {
        public boolean changed;
        public int rides;
        public int days;
        public int fields;
    }

    private RideFill() {
    }

    // ==================== 入口 ====================

    /** 廉价判断：这个响应有没有可能是回填对象（避免给每个响应都做一次 JSON 解析）。 */
    public static boolean mayFill(String url, String body) {
        if (url == null || body == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        boolean byUrl = u.contains("homerideinfo") || u.contains("ridetrack_v2")
                || u.contains("myrideinfo") || u.contains("analyse");
        if (!byUrl) {
            return false;
        }
        return body.contains("trajectory")
                || body.contains("rideRecordList")
                || body.contains("rideMonth")
                || body.contains("dayTime")
                || body.contains("lastRidingTime");
    }

    /** 按 URL 分派回填（各 handler 独立 try-catch，互不牵连）。 */
    public static Result fill(final JSONObject root, final String url) {
        Result out = new Result();
        try {
            Object d = root.opt("data");
            // ★ 云端数据门控 · 按车型隔离（2026-11-18 16:26 车主令）：不再做
            //   跨响应记忆 / 全树扫描（多车型账号会被 AE7 的数据毒化账号级
            //   信号）；逐 fill 点用当前响应自己的数据判定 —— 这些接口都按
            //   当前车辆查询，响应天然单车。计数 >0 / flag=true ⇒ 云端在统计
            //   这辆车，跳过事件写入；全 0 / 缺失（AE5i Pro 形态）⇒ 回填。
            logOnce("fill <- " + shortUrl(url)
                    + "  data=" + (d instanceof JSONArray ? "array" : d instanceof JSONObject ? "object" : "?"));
            if (d instanceof JSONArray && url != null && url.contains("ridetrack_v2")) {
                // ★ ridetrack_v2 的 data 是数组（按天分组），必须先判 array
                fillRidetrack((JSONArray) d, out);
                return out;
            }
            if (!(d instanceof JSONObject)) {
                return out;
            }
            JSONObject data = (JSONObject) d;
            if (url == null) {
                return out;
            }
            if (url.contains("myRideInfo") && data.has("rideRecordList")) {
                fillMyRideInfo(data, out);
            }
            if (url.contains("analyse") && data.has("rideMonth")) {
                fillAnalyse(data, out);
            }
            if (url.contains("homeRideInfo")) {
                fillHomeRideInfo(data, out);
            }
        } catch (Throwable t) {
            log("handler error: " + t);
        }
        return out;
    }

    /**
     * 云端为 AE5i Pro 统计急刹的最后一天（含当天）。
     *
     * <p>实车接口复核（AE5i Pro `358122500143678`）：</p>
     * <pre>
     *   2026-07-01  brakesTimes 合计 67
     *   2026-07-02  brakesTimes 合计 116
     *   2026-07-03 起 全 0（当月 7/10 之后每趟都是 0）
     *   2026-06     169 趟里 153 趟 &gt; 0
     * </pre>
     * <p>所以这一天及之前，{@code brakesTimes} 为 0 是<b>真 0</b>（那趟确实没急刹），
     * 不能拿 GPS 派生值去覆盖；之后的 0 才是「云端没统计」，才允许回填。
     * 压弯没有这个日期概念 —— 云端从未下发过，见类注释。</p>
     */
    static final String SERVER_BRAKES_LAST_DAY = "2026.07.02";

    /**
     * 云端在这辆车/这条数据上是否统计<b>某一种</b>事件。
     *
     * <p>★ 必须逐事件调用（压弯、急刹各一次）：AE5i Pro 的急刹有真值而压弯恒 0，
     * 合在一起判会把压弯一起跳过 —— 这正是「2026-07-02 前的压弯不回填」的根因。</p>
     *
     * @param node     当前响应里的节点（逐趟记录 / 月汇总 / 日列表响应）
     * @param countKey 该事件的计数字段（{@code bendingTimes}、{@code brakesTimesDay} …）
     */
    private static boolean eventManaged(JSONObject node, String countKey) {
        try {
            Object flag = node.opt("cyclingEventStatisticFlag");
            if (flag instanceof Boolean && (Boolean) flag) {
                return true;
            }
            if (flag instanceof Number && ((Number) flag).intValue() != 0) {
                return true;
            }
            if (flag instanceof String && "true".equalsIgnoreCase((String) flag)) {
                return true;
            }
            return toDouble(node.opt(countKey)) > 0;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 这一天是否仍在「云端统计急刹」的区间内（含 {@link #SERVER_BRAKES_LAST_DAY}）。 */
    static boolean serverBrakesAvailable(String day) {
        return day != null && day.length() >= 10 && day.compareTo(SERVER_BRAKES_LAST_DAY) <= 0;
    }

    // ==================== 1. ridetrack_v2（唯一轨迹源） ====================

    /** 一次响应最多导出几趟样本（标定用；再多文件就太大了）。 */
    private static final int EXPORT_RIDES = 3;
    /**
     * 一次响应最多打印几趟的压弯判定过程。
     *
     * <p>比 {@link #EXPORT_RIDES} 大：逐 run 诊断正是为了「看清哪一趟不对劲」，
     * 只看 3 趟不够定位；但也不能全打（7 月 81 趟 × 每行 900 字符会刷爆日志）。</p>
     */
    private static final int DEBUG_RIDES = 8;
    /** 轨迹样本落盘文件名（在 com.cfmoto 的 external files 下）。 */
    private static final String EXPORT_FILE = "speedhunter_traj.txt";

    private static String dayStr(String ownDay, String fallback) {
        String s = normDay(ownDay == null || ownDay.isEmpty() ? fallback : ownDay);
        return s == null ? "?" : s;
    }

    /**
     * 把一趟行程的原始轨迹点写出来，供电脑上离线扫参数。
     *
     * <p>格式：{@code # ride=<id> day=<day> pts=<n> vmax=<v>} 头行 + 每行一个
     * {@code lon,lat,v,t}。之所以必须导出原文：day store 只留汇总，
     * 想知道「换个阈值会算成几次」就必须拿原始点重跑。</p>
     */
    private static void exportSample(String rideId, String day, List<TrackCalculator.Pt> pts) {
        try {
            java.io.File dir = RideDayStore.exportDir();
            if (dir == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# ride=").append(rideId).append(" day=").append(day)
                    .append(" pts=").append(pts.size());
            double vmax = 0;
            for (TrackCalculator.Pt p : pts) {
                if (p.v > vmax) {
                    vmax = p.v;
                }
            }
            sb.append(String.format(Locale.ROOT, " vmax=%.1f%n", vmax));
            int n = Math.min(pts.size(), RideConfig.EXPORT_MAX_POINTS);
            for (int i = 0; i < n; i++) {
                TrackCalculator.Pt p = pts.get(i);
                sb.append(String.format(Locale.ROOT, "%.6f,%.6f,%.2f,%.0f%n",
                        p.lon, p.lat, p.v, p.t));
            }
            appendFile(new java.io.File(dir, EXPORT_FILE), sb.toString());
            log("track sample exported: " + rideId + " " + pts.size() + " pts -> " + EXPORT_FILE);
        } catch (Throwable t) {
            log("export failed: " + t);
        }
    }

    private static void appendFile(java.io.File file, String text) throws Exception {
        java.io.FileOutputStream out = new java.io.FileOutputStream(file, true);
        try {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static void fillRidetrack(JSONArray days, Result out) {
        int filled = 0;
        int noTraj = 0;
        int tooShort = 0;
        int shortRides = 0;
        int dayCount = 0;
        int dayTouched = 0;
        int serverSkipped = 0;
        long t0 = System.currentTimeMillis();
        StringBuilder detail = new StringBuilder();

        for (int i = 0; i < days.length(); i++) {
            Object dayObj = days.opt(i);
            if (!(dayObj instanceof JSONObject)) {
                continue;
            }
            JSONObject day = (JSONObject) dayObj;
            dayCount++;
            int dayFilled = 0;
            int dayB = 0;
            int dayD = 0;
            String dayTime = day.optString("dayTime", "");
            JSONArray history = day.optJSONArray("historyList");
            if (history == null) {
                continue;
            }
            for (int j = 0; j < history.length(); j++) {
                Object itemObj = history.opt(j);
                if (!(itemObj instanceof JSONObject)) {
                    continue;
                }
                JSONObject r = (JSONObject) itemObj;
                // ★ 先算日期：急刹门控要用它区分「云端在统计」与「云端已停统计」。
                String own = r.optString("dayTime", "");
                String dayStr = normDay(own.isEmpty() ? dayTime : own);

                // ★ 逐事件门控（2026-11-18 修订）：急刹与压弯分开判。
                //   旧实现 `serverManaged(r, "bendingTimes", "brakesTimes")` 把两者绑在一起，
                //   AE5i Pro 在 2026-07-02 及之前急刹有真值（>0）而压弯恒 0 ⇒ 整趟被判成
                //   「云端在统计」⇒ 压弯不回填、也不进 day store。这正是本次要修的问题。
                //   · 压弯：flag=true 或 bendingTimes>0 ⇒ 保服务端值；否则回填。
                //   · 急刹：flag=true 或 brakesTimes>0 ⇒ 保服务端值；
                //           另外 2026-07-02 及之前即使为 0 也是真 0（云端当时在统计），
                //           不能用 GPS 估算覆盖，见 SERVER_BRAKES_LAST_DAY。
                boolean brakesManaged = eventManaged(r, "brakesTimes")
                        || serverBrakesAvailable(dayStr);
                boolean bendsManaged = eventManaged(r, "bendingTimes");
                if (brakesManaged && bendsManaged) {
                    serverSkipped++;
                }
                String traj = r.optString("trajectory", "");
                if (traj == null || traj.isEmpty()) {
                    noTraj++;
                    continue;
                }
                List<TrackCalculator.Pt> pts = TrackCalculator.parse(traj);
                if (pts.size() < RideConfig.MIN_POINTS) {
                    tooShort++;
                    continue;
                }
                int b = BrakeDetector.count(pts);
                int bd = BendDetector.count(pts);
                TrackCalculator.Metrics m = TrackCalculator.compute(pts);

                // ★ 短行程整趟判 0：实测 <0.5km 的行程压弯密度是长途的 8 倍（787 vs 95 次/100km），
                //   那是停车状态下 GPS 乱飘造成的假弯，不是真压弯。
                //   7 月有 16 趟、8 月 38 趟、9 月 27 趟属于 <1km，不滤掉的话月度数字会虚高一大截。
                boolean belowMinKm = m != null && m.distanceKm < RideConfig.bendMinKm();
                if (belowMinKm) {
                    bd = 0;
                    shortRides++;
                }

                // ★ 逐事件写入：只写「云端没在统计」的那一项。
                //   AE5i Pro 2026-07-02 前 ⇒ 只补 bendingTimes，brakesTimes 保持真值。
                if (!brakesManaged) {
                    put(r, "brakesTimes", b);
                    filled++;
                    dayFilled++;
                    dayB += b;
                }
                if (!bendsManaged) {
                    put(r, "bendingTimes", bd);
                    filled++;
                    dayFilled++;
                    dayD += bd;
                }
                // 基础指标同样只补 0 值（服务端这两页多数是真值，但 7 月 2 日之后有 0）
                if (m != null) {
                    if (isZero(r.opt("rideMileage")) && m.distanceKm > 0) {
                        put(r, "rideMileage", round1(m.distanceKm));
                    }
                    if (isZero(r.opt("maxSpeed")) && m.maxSpeed > 0) {
                        put(r, "maxSpeed", round1(m.maxSpeed));
                    }
                }
                String rideId = rideIdOf(r);

                // 标定用：把原始轨迹样本落盘，可以在电脑上离线扫参数（默认关）
                if (RideConfig.exportTrack() && out.rides <= EXPORT_RIDES) {
                    exportSample(rideId, dayStr == null ? dayTime : dayStr, pts);
                }
                // 标定用：逐 run 打印判定过程与拒绝原因（默认关，对应 iOS 的 C.DEBUG）
                if (RideConfig.debugBend() && out.rides <= DEBUG_RIDES) {
                    log("BENDS " + BendDetector.explain(pts, rideId));
                    log("BRAKES " + BrakeDetector.explain(pts, rideId));
                }
                // ★ 只要有一项是我们算的就进 day store —— 2026-07-02 前的趟
                //   急刹归云端、压弯归我们，压弯必须落盘，否则 myRideInfo /
                //   analyse 的月度压弯没有数据源。两项都归云端（AE7 形态）才跳过。
                if ((!brakesManaged || !bendsManaged) && dayStr != null && m != null) {
                    RideDayStore.accum(dayStr, rideId, b, bd,
                            m.distanceKm, m.durationMin, m.maxSpeed);
                    // ★ 这一天灌过了，从「待重灌」清单里销账（清空后手工重灌的进度反馈）
                    RideDayStore.markFilled(dayStr);
                }
                out.rides++;
            }
            if (dayFilled > 0) {
                dayTouched++;
                out.days = dayTouched;
                if (detail.length() < 600) {
                    String norm = normDay(dayTime);
                    detail.append(' ').append(norm == null ? dayTime : norm)
                            .append("(b").append(dayB).append("/d").append(dayD).append(")");
                }
            }
        }
        out.changed = filled > 0;
        out.fields = filled;
        log("ridetrack_v2: days=" + dayCount + " rides=" + out.rides
                + (serverSkipped > 0 ? " bothManaged=" + serverSkipped : "")
                + " noTraj=" + noTraj + " shortTraj=" + tooShort
                + " shortRides(<" + RideConfig.bendMinKm() + "km)=" + shortRides
                + " dayStore=" + dayTouched + " " + (System.currentTimeMillis() - t0) + "ms |"
                + detail);
    }

    // ==================== 2. homeRideInfo ====================

    /**
     * 首页骑行卡片。
     *
     * <p>实测响应（本车）：</p>
     * <pre>
     * {"rideMileageDay":"9.7","lastRideMileage":"1.6","lastRidingTime":"0.0",
     *  "lastRidingTimeUnitMinute":"4","mileages":[...],"ridingTimes":[...]}
     * </pre>
     *
     * <p>唯一明显的坏值是 {@code lastRidingTime="0.0"} —— 它是「最近一次骑行的时长（小时）」，
     * 而同一份数据里的 {@code lastRidingTimeUnitMinute="4"}（分钟）是真的，
     * 说明服务端把小时字段算成了 0。用分钟换算回去即可，不涉及任何猜测。</p>
     */
    private static void fillHomeRideInfo(JSONObject data, Result out) {
        int fixed = 0;

        // ① lastRidingTime（小时）= lastRidingTimeUnitMinute / 60
        if (isZero(data.opt("lastRidingTime"))) {
            double minutes = toDouble(data.opt("lastRidingTimeUnitMinute"));
            if (minutes > 0) {
                put(data, "lastRidingTime", trim(minutes / 60.0));
                fixed++;
            }
        }

        // ② rideMileageDay = mileages 数组之和（数组是 10 天窗口，最后一项是今天）
        if (isZero(data.opt("rideMileageDay"))) {
            double sum = sumArray(data.optJSONArray("mileages"));
            if (sum > 0) {
                put(data, "rideMileageDay", round1(sum));
                fixed++;
            }
        }

        // ③ lastRideMileage 为 0 时，用 mileages 的最后一个非空项兜底
        if (isZero(data.opt("lastRideMileage"))) {
            JSONArray ms = data.optJSONArray("mileages");
            if (ms != null && ms.length() > 0) {
                double last = toDouble(ms.opt(ms.length() - 1));
                if (last <= 0) {
                    for (int i = ms.length() - 1; i >= 0; i--) {
                        double v = toDouble(ms.opt(i));
                        if (v > 0) {
                            last = v;
                            break;
                        }
                    }
                }
                if (last > 0) {
                    put(data, "lastRideMileage", round1(last));
                    fixed++;
                }
            }
        }

        if (fixed > 0) {
            out.changed = true;
            out.fields += fixed;
            log("homeRideInfo done: fixed " + fixed
                    + " (lastRidingTime=" + data.opt("lastRidingTime")
                    + " rideMileageDay=" + data.opt("rideMileageDay") + ")");
        }
    }

    // ==================== 3. myRideInfo ====================

    private static void fillMyRideInfo(JSONObject data, Result out) {
        JSONArray list = data.optJSONArray("rideRecordList");
        if (list == null) {
            return;
        }
        int filled = 0;
        // ★ 逐事件门控（2026-11-18 修订）：
        //   · 压弯：本响应（= 当前车辆）的 bendingTimesTotal > 0 ⇒ 云端在统计压弯
        //     （AE7 实测 1376），保持服务端所有 bendingTimesDay；
        //     AE5i Pro 实测恒 0（6 月 / 7 月 / 9 月全是 "0"）⇒ 按 day store 回填。
        //   · 急刹：这个接口没有 brakesTimesTotal，只能逐日判（见循环内）。
        //   旧实现把 bendingTimesTotal / brakesTimesTotal 合成一个 managed，且
        //   brakesTimesTotal 根本不存在 ⇒ 实际只按 bendingTimesTotal 判，压弯在
        //   AE5i 上一直是 false，本该能补 —— 真正卡住 2026-07-02 前数据的是
        //   ridetrack 的合并门控（旧账本里根本没有那些天的记录）。
        boolean bendsManaged = eventManaged(data, "bendingTimesTotal");

        // ★ 这里<b>不</b>走 rideId 内存缓存：myRideInfo 的记录是「一天一条」，
        //   实测响应里只有 date / rideMileage / ridingTimeDayUnitMinute /
        //   bendingTimesDay / maxSpeed / accelerationTimesDay / brakesTimesDay，
        //   压根没有 rideId 字段 ⇒ 任何按 rideId 查缓存的逻辑都 100% 空转。
        //   iOS 侧 v15 的主流程同样从不调用它的 fillFromCache，两边一致。
        for (int k = 0; k < list.length(); k++) {
            Object recObj = list.opt(k);
            if (!(recObj instanceof JSONObject)) {
                continue;
            }
            JSONObject rec = (JSONObject) recObj;
            String day2 = normDay(rec.optString("date", ""));
            if (day2 == null) {
                continue;
            }
            Day st = RideDayStore.read(day2);
            if (st == null) {
                continue;
            }
            // ★ 覆盖语义与 iOS 一致（st.b >= 0 就写），不是「只补 0 值」：
            //   myRideInfo 的这些字段是<b>服务端从未填充过</b>的派生量（实测 7/8/9 月全 0），
            //   唯一真相就是我们按天算出来的账本。若这里再套一层 isZero，
            //   就会遇到「服务端给了个脏非 0（如上个月的残留）→ 补不掉」的死角。
            //   服务端<b>真</b>值（10/3 的 bendingTimesDay=2）本来就该被我们的 6 覆盖，
            //   因为服务端那个 2 是它自己按残缺数据算的，比我们少。
            //
            // ★ 但急刹要逐日分开判（2026-11-18 修订）：AE5i Pro 在 2026-07-02 及
            //   之前<b>确实在统计急刹</b>（6 月逐日 73/46/37…，7/1=67、7/2=116），
            //   这些值是车机真值，绝不能被 GPS 估算覆盖（那天恰好 0 次也是真 0）；
            //   2026-07-03 起云端不再统计 ⇒ 0 才有回填意义。
            //   `bendsManaged` 是「这辆车云端在统计事件」的车型级信号
            //   （AE7 的 bendingTimesTotal=1376）：它为 true 时急刹也一律不动，
            //   否则 AE7 那些真 0 的天会被 GPS 估算注入假急刹。
            boolean brakesDayManaged = bendsManaged
                    || eventManaged(rec, "brakesTimesDay")
                    || serverBrakesAvailable(day2);
            if (!brakesDayManaged && st.b >= 0) {
                put(rec, "brakesTimesDay", String.valueOf(st.b));
                filled++;
            }
            if (!bendsManaged && st.d >= 0) {
                put(rec, "bendingTimesDay", String.valueOf(st.d));
                filled++;
            }
            // 基础指标：里程 / 极速同样以账本为准
            if (isZero(rec.opt("rideMileage"))) {
                double km = st.totalKm();
                if (km > 0) {
                    put(rec, "rideMileage", round1(km));
                    filled++;
                }
            }
            if (isZero(rec.opt("maxSpeed"))) {
                double v = st.totalMaxV();
                if (v > 0) {
                    put(rec, "maxSpeed", round1(v));
                    filled++;
                }
            }
        }

        // 顶部总计（总里程旁的压弯）：对照 AE7 官方显示逻辑，这个槽位是
        // <b>本车记录的全生命周期总压弯</b>（2026-11-18 车主令），不能只加
        // 当前响应（当月）—— 换月查看时只加响应月会把总数缩回单月。
        // 取整个 day store 的累计值；store 读不到/为空时退回响应累加值。
        // 服务端给的 bendingTimesTotal 是它自己按残缺数据算的（实测 10 月 = 2，
        // 而同月 10/3 单日账本就有 6），留着只会让列表页顶部和明细自相矛盾。
        int sumD = 0;
        for (int k = 0; k < list.length(); k++) {
            Object recObj = list.opt(k);
            if (recObj instanceof JSONObject) {
                sumD += (int) toDouble(((JSONObject) recObj).opt("bendingTimesDay"));
            }
        }
        int[] storeTot = RideDayStore.totals();
        int allD = storeTot[1] > 0 ? storeTot[1] : sumD;
        boolean wroteTotal = false;
        if (!bendsManaged && allD > 0 && !String.valueOf(data.opt("bendingTimesTotal")).equals(String.valueOf(allD))) {
            put(data, "bendingTimesTotal", String.valueOf(allD));
            wroteTotal = true;
            filled++;
        }
        if (wroteTotal || filled > 0) {
            out.changed = true;
            out.fields += filled;
        }
        log("myRideInfo done: filled " + filled + " over " + list.length()
                + " records, total: bendingTimesTotal=" + data.opt("bendingTimesTotal")
                + (bendsManaged ? "  [bends managed -> kept]" : ""));
    }

    // ==================== 4. analyse ====================

    private static void fillAnalyse(JSONObject data, Result out) {
        String prefix = monthPrefix(data.optString("rideMonth", ""));
        if (prefix == null) {
            return;
        }
        // ★ 逐事件门控（2026-11-18 修订）：AE5i Pro 6 月 brakesTimes=1814 而
        //   bendingTimes=0、7 月 brakesTimes=202 而 bendingTimes=0 —— 合成一个
        //   managed 会让「有急刹真值」把压弯一起挡掉，月度压弯永远补不上。
        //   AE7 11 月两项都 >0（brakes=13 / bends=56）⇒ 两项都保持服务端值。
        boolean bendsManaged = eventManaged(data, "bendingTimes");
        // ★ `bendsManaged` 同时是「这辆车云端在统计事件」的车型级信号
        //   （AE7 11 月 bends=56）：它为 true 时急刹也保持服务端值，
        //   避免把 AE7 真 0 的月份用 GPS 估算填成假急刹。
        boolean brakesManaged = bendsManaged || eventManaged(data, "brakesTimes");
        int sumB = 0;
        int sumD = 0;
        int found = 0;
        double sumKm = 0;
        double sumMin = 0;
        double maxV = 0;
        for (int dd = 1; dd <= 31; dd++) {
            String ds = String.format(Locale.ROOT, "%s.%02d", prefix, dd);
            Day st = RideDayStore.read(ds);
            if (st != null) {
                sumB += st.b;
                sumD += st.d;
                sumKm += st.totalKm();
                sumMin += st.totalMin();
                maxV = Math.max(maxV, st.totalMaxV());
                found++;
            }
        }
        if (found == 0) {
            log("analyse: no day store for " + prefix);
            return;
        }
        int fixed = 0;
        // ★ 覆盖语义与 iOS v15 一致：sumB/sumD > 0 就写，不再套 isZero。
        //   压弯：AE5i Pro 云端从未下发（bendingTimes 恒 0），账本是唯一真相，
        //   即使服务端给过脏非 0 也要覆盖。
        //   急刹：2026-07-02 及之前的月份服务端是真值（6 月 1814），只有
        //   brakesTimes=0 的月份（2026-08 起）才回填。
        if (!brakesManaged && sumB > 0) {
            put(data, "brakesTimes", String.valueOf(sumB));
            fixed++;
        }
        if (!bendsManaged && sumD > 0) {
            put(data, "bendingTimes", String.valueOf(sumD));
            fixed++;
        }
        // 基础指标：月度里程 / 时长 / 极速。服务端这几个是真值（10 月 ridingTime=71 对得上），
        // 所以保持「只补 0 值」—— 这是 iOS 侧没有、但本项目实测需要的一条。
        if (isZero(data.opt("rideMileage")) && sumKm > 0) {
            put(data, "rideMileage", round1(sumKm));
            fixed++;
        }
        if (isZero(data.opt("ridingTime")) && sumMin > 0) {
            put(data, "ridingTime", String.valueOf((long) Math.round(sumMin)));
            fixed++;
        }
        if (isZero(data.opt("ridingTimeUnitMinute")) && sumMin > 0) {
            put(data, "ridingTimeUnitMinute", String.valueOf((long) Math.round(sumMin)));
            fixed++;
        }
        if (isZero(data.opt("maxSpeed")) && maxV > 0) {
            put(data, "maxSpeed", round1(maxV));
            fixed++;
        }
        if (fixed > 0) {
            out.changed = true;
            out.fields += fixed;
        }
        log("analyse done: month=" + prefix + " days=" + found + "/31"
                + " sumB=" + sumB + " sumD=" + sumD + " km=" + round1(sumKm)
                + " fixed=" + fixed
                + (brakesManaged || bendsManaged
                        ? "  [kept: " + (brakesManaged ? "brakes " : "")
                        + (bendsManaged ? "bends" : "") + "]" : ""));
    }

    // ==================== 公共 ====================

    private static String rideIdOf(JSONObject r) {
        String rid = r.optString("rideId", "");
        if (rid.isEmpty()) {
            rid = r.optString("recordId", "");
        }
        if (rid.isEmpty()) {
            rid = r.optString("id", "");
        }
        return rid;
    }

    private static double sumArray(JSONArray a) {
        if (a == null) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < a.length(); i++) {
            sum += toDouble(a.opt(i));
        }
        return sum;
    }

    private static double toDouble(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 去掉浮点尾巴（0.0666666 → "0.067"），避免 UI 上出现一串小数。 */
    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ==================== 工具 ====================

    /** 把 {@code 2026-07-02 / 2026/07/02 / 20260702 / 2026.07.02} 统一成 {@code 2026.07.02}。 */
    static String normDay(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() == 8) {
            String head = t.substring(0, 4);
            String tail = t.substring(4);
            if (isDigits(head) && isDigits(tail)) {
                return head + "." + tail.substring(0, 2) + "." + tail.substring(2);
            }
        }
        t = t.replace('-', '.').replace('/', '.');
        String[] parts = t.split("\\.");
        if (parts.length == 3
                && isDigits(parts[0]) && isDigits(parts[1]) && isDigits(parts[2])) {
            return String.format(Locale.ROOT, "%s.%02d.%02d",
                    parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        return null;
    }

    /** {@code rideMonth} → {@code 2026.07}（兼容 {@code 202610} / {@code 2026.10}）。 */
    static String monthPrefix(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.length() < 6) {
            return null;
        }
        String head = t.substring(0, 4);
        if (!isDigits(head)) {
            return null;
        }
        String rest = t.charAt(4) == '.' ? t.substring(5, 7) : t.substring(4, 6);
        return isDigits(rest) ? head + "." + rest : null;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断「服务端没给数」，只有这种字段才允许被回填覆盖。
     *
     * <p>★ 必须按<b>数值</b>判，不能只比字符串：服务端同一份数据里
     * {@code lastRidingTime} 给的是 {@code "0.0"}、{@code brakingTimes} 给的是 {@code "0"}、
     * {@code rideMileage} 给的是 {@code 0.0}（Number）。只认字面量 {@code "0"} 的话，
     * {@code "0.0"} 会被当成「有真值」而永远补不上 —— 这是实测踩到的坑。</p>
     */
    static boolean isZero(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() == 0;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return true;
        }
        try {
            return Double.parseDouble(s) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@code JSONObject#put} 抛 checked {@code JSONException}，统一吞掉 —— 回填绝不能让 App 崩。 */
    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message) {
        HookLog.log(TAG + " " + message);
    }

    /** 同一 URL 只记一次 —— homeRideInfo 是首页高频接口，不去重会刷屏。 */
    private static void logOnce(String message) {
        if (ONCE.add(message)) {
            log(message);
        }
    }

    private static final java.util.Set<String> ONCE =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static String shortUrl(String url) {
        if (url == null) {
            return "?";
        }
        int i = url.indexOf("cfmotoserverapp/");
        String tail = i >= 0 ? url.substring(i + 16) : url;
        int q = tail.indexOf('?');
        if (q >= 0) {
            tail = tail.substring(0, q);
        }
        return tail;
    }
}
