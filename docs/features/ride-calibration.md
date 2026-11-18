# 骑行指标标定（压弯 / 急刹）—— 用原厂 IMU 真值对标

> 2026-11-16 · SpeedHunter v4.9.0
> 触发：车主提供同路段对照数据，要求「用原厂数据优化压弯回填」。
> ★ 2026-11-18 多样本复核：单样本结论已被 §10 修订（\`CUM_MIN\` 60→50、
> \`A_MIN\` 1.04→1.20、\`MIN_KM\` 2.0→3.0；\`V_MIN\` 20 不变），并新增车型门控 §11。

## 1. 结论先行

| 指标 | 改前 | 改后 | 原厂真值 | 说明 |
|---|---|---|---|---|
| 压弯（47.2 km 样本） | 66 次 | **13 次** | **13 次** | `BEND_CUM_MIN` 15 → 60 |
| 压弯密度（61 条样本 332.8 km） | 120 次/100km | **30 次/100km** | ~27.5 次/100km | 与 IMU 同档 |
| 急刹（47.2 km 样本） | 70 次 | **5 次** | **5 次** | 算法重写 + 阈值 0.5 → 1.04 m/s² |
| 加速 | 不回填 | 不回填 | 3 次 | 服务端/车机给真值，模块不碰 |

**压弯与急刹均已与车机 IMU 真值精确一致（bend 13 / brake 5）。**
> 车主明示：此后一律以链接接口数据（ridetrack_v2 服务端原值）为真值；App 界面显示可能被回填污染，不再采信。

## 2. 一个必须先讲清的事实：原厂值不是从 GPS 轨迹算的

车主给的 h5 分享链接（`h5.zeehoev.com/trajectory?...`）背后接口
`/cfmotoserverapp/H5/share/merge/ridetrack` 与 App 用的
`/cfmotoserverapp/ridetrack_v2` **对同一份轨迹给出的统计值不同**：

| 记录 | 服务端 `ridetrack_v2` | h5 分享接口 |
|---|---|---|
| AE7 Pro 47.2 km | acc=3, brake=**5**, bend=**13** | — |
| AE7 Pro 1.2 km | acc=0, brake=0, bend=0 | 0/0/0 |
| AE5i 1.2 km | acc=6, brake=0, bend=0 | 0/0/0 |

规律：

- **`cyclingEventStatisticFlag=true` 的车（AE7 Pro，`vehicleKinds=3`）服务端才写入
  brake/bend**；AE5i（`false` / `kinds=1`）恒为 0 —— 这正是需要回填的原因。
- **`accelerationTimes` 与服务端轨迹在算法上无关**：58 条样本里
  ref=26 次的记录，用任何加速度阈值（0.5–2.0 m/s²）只能算出 3–16 次。
  说明它是**车机 IMU 直接统计并上报**的事件数，不是 GPS 派生量。
- 因此 `bendingTimes` / `brakesTimes` 的 AE7 真值也来自**车机 IMU（倾斜角 + 加速度计）**，
  GPS 算法**无法逐条复刻**，只能对齐量级与密度。

> 这也解释了旧文档里那条「服务端在有真值那天密度 126 次/100km」的记录 ——
> 那是 GPS 口径，与 IMU 不可直接比较。

## 3. 数据获取（可复现）

```bash
# token 从设备配置里读，不落盘、不打印
python3 tools/ride/fetch_ridetrack.py --vin LCETHGD17S6006301 --month 2026-11 \
        --config /tmp/cfg_before_netdump.json --out /tmp/rt2_ae7.json
```

要点（踩过的坑）：

1. 签名 `sign = MD5(hex(SHA-1(sortedQuery + body + param + secret)))`，
   **`sortedQuery` 用未 URL 编码的原值** —— 编码后一律 430 `permit error`。
2. `ridetrack_v2` 的 `vinNo` / `userId` / `startTime(YYYY-MM)` 全部用**明文**；
   传 base64 会返回 `code 40000 网络开小差了`。
3. `startTime` 必填，格式 `2026-11`；不传返回空数组。
4. h5 分享接口用 `startTimeFrom=startTimeTo=<unix 秒>` 定位单条，但返回的
   `brakesTimes/bendingTimes` 恒 0（不做统计），只能拿轨迹。

## 4. 校准样本

同路段对照（车主 2026-11-16 夜间连骑两趟，相差 5 分钟）：

| 车 | 时间 | 里程 | 点数 | 采样 | 极速 | 原厂 acc/brake/bend |
|---|---|---|---|---|---|---|
| AE5i | 23:00:52–23:03:48 | 1.2 km | 18 | 10 s | 51 | 6 / 0 / 0（flag=false，不算） |
| AE7 Pro | 23:06:03–23:08:32 | 1.2 km | 15 | 10 s | 69 | 0 / 0 / 0 |
| AE7 Pro | **18:02:46–20:04:33** | **47.2 km** | **732** | 10 s | 88 | **3 / 5 / 13** |

**唯一可作真值的长骑样本是 47.2 km 那条**（AE7 Pro 当天另一段长途）。
两条 1.2 km 短程的原厂 0 只作「不得虚增」的负约束。

## 5. 压弯：`BEND_CUM_MIN` 15 → 60

47.2 km / 732 点，115 个候选 run 的累计转角分布：

| cum 阈值 | 15 | 20 | 25 | 30 | 40 | 45 | 50 | **60** | 70 | 80 |
|---|---|---|---|---|---|---|---|---|---|---|
| 通过数 | 66 | 59 | 50 | 40 | 28 | 24 | 17 | **13** | 10 | 4 |

`cum >= 60` 恰好 13，且命中的 13 个 run 累计转角全在 **62–89°**
（`#1 89.2° / #2 88.1° / #3 84.5° … #13 62.4°`），全部是「转过一个完整大弯」；
被排除的 11 个在 45–60°，属中等路口转弯。

**为什么此前的 15 是错的**：15° 连小路口转弯、车道并线都算。61 条真实轨迹合计
120 次/100km，等于「每 800 米压一次弯」，与体感明显不符。旧值的依据是 iOS 侧
人工目视（「7/12 压了 4 次」），而目视分不清 30° 与 60°。

### 附带：`BEND_MIN_KM` 1.0 → 2.0

AE7 Pro 1.2 km 短程在原厂是 0 次，`cum=60` 下 GPS 仍给 1 次（小区内 U 形掉头）。
把短程门槛提到 2 km 后整趟判 0，与真值一致。

### 全量复核（61 条真实轨迹 / 332.8 km）

| | 旧参数 | 新参数 |
|---|---|---|
| 压弯合计 | 400 次（120/100km） | **99 次（29.7/100km）** |
| 急刹合计 | — | **73 次（21.9/100km）** |

唯一有原厂真值的长骑（47.2 km）落在 `bend=13`（真值 13）、`brake=5`（真值 5）。

## 6. 急刹：算法重写

### 旧实现的三处错误

| 项 | 旧值 | 问题 |
|---|---|---|
| 扫描窗口 | `BRAKE_WIN_LARGE=5`、`SMALL=3`，**按点数** | 轨迹 10 秒/点，5 点 = 50 秒；注释按「5 秒」写的 |
| `BRAKE_A_MIN` | 0.5 m/s² | 只有 0.05 g，正常收油都能过 |
| `BRAKE_CALIB` | 2.5 | 「双窗口重复计数乘回去」—— 重复该合并，乘倍数只会凭空 ×2.5 |

47.2 km 样本旧算法 **70 次**，真值 5 次，**14 倍虚高**。

### 新实现

逐段算物理减速度 `a = Δv / 3.6 / Δt`，四道闸（起点 ≥15 km/h、`Δt ∈ (0,25s]`、
`a ≤ -1.04`、`a ≥ -5.0` 排除 GPS 跳点），命中点按 **10 秒**间隔合并，
不再乘倍数。

同一份 732 点轨迹：

| `A_MIN` | 0.8 | 0.9 | 1.0 | 1.02 | **1.04** | 1.05 | 1.06 | 1.10 |
|---|---|---|---|---|---|---|---|---|
| 次数 | 18 | 14 | 6 | 6 | **5** | 5 | 4 | 2 |

**最终取 1.04**（车主 23:33 追加要求「以此次长记录为目标优化」后定案）：
1.0 多算的那 1 次是 `a=-1.028`（37→0 km/h 的低速刹停），1.04 将其排除，
同时保留最弱的真值候选 `a=-1.051`（38→0 km/h）；1.06 又会掉到 4 次。
样本里 6 个候选的实测减速度为 −1.078 / −1.028 / −1.386 / −1.072 / −1.312 / −1.051。

> ⚠️ 1.04 是**单样本经验标定值**，不是物理常数。场景差异大时用 `brakeCalib` 微调。

## 7. 加速：不回填（保持现状）

`RideFill` 从未写 `accelerationTimes`，是正确的 —— 该字段是车机 IMU 事件的
上报值（AE5i 今日 7 条记录分别 6/11/8/8/26/26/26），GPS 复刻不出来，硬填只会更差。

## 8. 边界与风险

- **单样本标定**。可作真值的长骑只有 1 条（AE7 Pro 47.2 km）。`cum=60`
  精确命中 13 但 45–60° 之间没有自然断点，说明是拟合值而非数据分界。
  后续有新的「长骑 + 原厂值」对照时应复核。
- **IMU ≠ GPS**。车机用倾斜角判压弯，GPS 只能靠航向变化。低速大角度掉头
  （无倾斜）会漏判或误判，这是原理性上限。
- **只在设置里改参数不影响已算过的账**。day store 只存汇总，原始轨迹不常驻；
  改参数后必须在模块里「清空 day store 再回填」才会重算。
- ~~iOS 侧未同步~~ → **已同步**（2026-11-16 23:33）：两端算法逐行等价，
  同一真值样本均输出 `brake=5 / bend=13`。桩测 `node .../test_ride_calibration.js`。

## 9. 改动清单

| 文件 | 改动 |
|---|---|
| `ride/RideConfig.java` | `DEF_BEND_CUM_MIN` 15→60；`DEF_BEND_MIN_KM` 1.0→2.0；`DEF_BRAKE_CALIB` 2.5→1.0；`BRAKE_A_MIN` 0.5→1.0；新增 `BRAKE_A_MAX`/`BRAKE_MERGE_SEC`/`BRAKE_GAP_MS_MAX`；删除 `BRAKE_WIN_*`/`BRAKE_DV_*`/`BRAKE_MERGE_GAP`/`BRAKE_MIN_DUR` |
| `ride/BrakeDetector.java` | 重写为「逐段物理减速度 + 时间合并」，新增 `explain()` |
| `ride/BendDetector.java` | 仅注释（算法未动） |
| `ride/RideFill.java` | 诊断时同时输出 `BRAKES ...` |
| `tools/ride/fetch_ridetrack.py` | 新增：签名拉取 ridetrack_v2 |
| `tools/ride/eval_traj.py` | 新增：离线复刻两端算法并对照真值 |
| `SpeedHunter-iOS/scripts/ZeehoRideFill.js` | **同步**：`BEND_CUM_MIN` 15→60、`BEND_MIN_KM` 新增 2.0、`CALIB_FACTOR` 2.5→1.0、`countBrakes` 改为逐段减速度（A_MIN=1.04 / A_MAX=5 / MERGE_SEC=10 / GAP_MS_MAX=25000） |
| `SpeedHunter-iOS/scripts/debug/test_ride_calibration.js` | 新增：Shadowrocket 全局桩测，喂 47.2 km 记录验证 5/13 |


## 10. 2026-11-18 多样本复核：单样本参数过拟合的修正

> 触发：车主令「根据账号内已有的几次长距离骑行记录，继续优化算法」。
> 当月账号已积累 **6 趟 ≥2 km** 长骑（2.0 / 6.2 / 8.7 / 24.5 / 26.3 / 47.2 km，
> 含一次夜骑与一次晨骑），全部有服务端真值 —— 单样本时代结束。

### 10.1 方法修订：以权威评估器为唯一标尺

早先的参数扫描用 /tmp 下的临时脚本变体跑，其 run 合并细节与
`BendDetector.java` 有 1–2 次的出入（47.2 km 趟 21 vs 22）。**本次起一律以
`tools/ride/eval_traj.py`（Java 严格复刻）的输出为定参依据**；两端实现
（Java / JS）与该评估器在同一轨迹上逐值一致后才算数。

### 10.2 压弯：`CUM_MIN` 60→50、`MIN_KM` 2.0→3.0（`V_MIN` 20 不变）

5 趟 ≥3 km 样本，权威评估器网格扫描（cap=90、seed=5）：

| v_min\cum | 40 | 42 | 45 | 48 | **50** | 55 | 60（旧） |
|---|---|---|---|---|---|---|---|
| 20 | 22 | 20 | 18 | 17 | **15** | 20 | 23 |
| 25 | 21 | 20 | 18 | 20 | 19 | 24 | 29 |

取 **v_min=20 / cum=50**：分趟预测 **15/3/1/11/17** vs 真值 23/3/2/13/13，
总 |err| **23→15**。结构性矛盾仍在且被如实接受：24.5 km 夜骑真值 23 需要
cum≤40 才能逼近（GPS 连续 S 弯欠采样），47.2 km 趟 cum=60 才精确命中 13 ——
单阈值族无法同摊最优，50 是权威实现下的总误差最小点。

`MIN_KM` 2.0→3.0：11 月账号 1.6 / 1.7 / 2.0 km 三趟短程真值全是 0 次压弯，
而 2.0 km 趟 GPS 仍会算出 2 个 60°+ 假弯（小区 U 形掉头）。

### 10.3 急刹：`A_MIN` 1.04→1.20

同 5 趟样本细扫（起点 ≥15 km/h、合并 10 s）：

| a_min | 1.04（旧） | 1.15 | **1.19–1.21** | 1.25 |
|---|---|---|---|---|
| 总\|err\| | 9 | 7 | **5（平台）** | 6 |

取平台中点 **1.20**：预测 0/1/0/5/2 vs 真值 0/0/1/5/5；26.3 km 趟精确命中 5。
47.2 km 趟少算 3 的真值候选只有 -1.08/-1.07/-1.05（10 s 采样摊平尖峰），
8.7 km 趟多算 1 的假阳性段是 68.2→12.3 km/h/10 s（真急刹形态，阈值无法剔除）。

### 10.4 旧参数的系统性偏差（为什么要改）

| 趟 | 真值 bend/brake | 旧参数算出 | 新参数算出 |
|---|---|---|---|
| 26.3 km | 13 / 5 | 5 / 10 | 11 / **5** |
| 24.5 km 夜骑 | 23 / 0 | 10 / 2 | 15 / 0 |
| 8.7 km | 3 / 0 | 2 / 1 | 3 / 1 |
| 6.2 km | 2 / 1 | 1 / 0 | 1 / 0 |
| 47.2 km（原标定样本） | 13 / 5 | **13 / 5** | 17 / 2 |

旧参数只精确命中它的标定样本（47.2 km），其余趟全部少算压弯、多算急刹。

### 10.5 已证伪的替代方案（勿再尝试）

- **横向加速度模型**（`alat = v²·κ` 判弯）：10 s 采样平滑后逐点曲率过小，
  6 趟总 |err| ≥ 50（阈值 2.0 以上全 0）。
- **混合模型**（cum + alat 双判据）：同样 |err| ≥ 50。
- **run 合并容差 / seed 微调**（30–80 m、seed 4/6）：对平台无增益。

### 10.6 桩测期望值更新

`test_ride_calibration.js` 的 47.2 km 期望从 5/13 改为 **brake=2 / bend=17**
（该样本让位于 5 趟总误差最小）；用途是锁两端实现一致，不是锁真值命中。

## 11. 回填门控：按车型隔离，逐响应判定（2026-11-18，三次修订定稿）

> 触发一（上午）：车主令「压弯、急刹回填按车型使能：服务器报文不存在这几项的
>（比如 AE5i Pro）才主动回填」。触发二（16:01）：v5.2.0 实机 AE5i Pro
> 回填失效，车主令「按云端是否下发总压弯次数决定是否回填，撤回 iOS 门控」。
> 触发三（16:26）：车主账号**同时拥有 AE7 Pro（云端有压弯数据）与 AE5i Pro
>（云端恒 0）**，v5.2.1 仍不回填 —— 任何账号级信号都会被 AE7 毒化。车主令
> 「不要让云端已有数据的车型影响无急刹/压弯数据的车辆回填功能，彻底修复」。

### 病根：账号级信号在多车型账号必然被毒化

- v5.2.0 `scanStatsFlag` / v5.2.1 `scanCloudBendTotal` 都是**全树扫描 +
  跨响应记忆**：首页/车型列表等响应里混着两辆车的 flag/总数，先扫到 AE7 的
  真值就把全局信号置成「云端在统计」，切到 AE5i Pro 后仍不回填。
- 上午版 `VehicleModel` 车型兜底同理：共享车型列表里 AE7 的特征先命中缓存。

### v5.2.2 判据（`RideFill.serverManaged`，定稿）

- **零全局状态**：删除跨响应记忆与全树扫描，不看任何非目标响应。
- **逐 fill 点、只看当前响应自己的数据**：ridetrack_v2 / myRideInfo /
  analyse 都是按当前车辆查询的接口，响应天然单车 —— 用它自己的数据判定，
  其它车型的影响在结构上不可能混进来。
- 判定条件（满足任一 ⇒ 云端在统计**这辆车**，事件字段保持服务端值）：
  - 节点自带 `cyclingEventStatisticFlag=true`（ridetrack 逐趟条目自带，
    恰好是那一趟所属车型的开关；AE5i Pro 实测为 null/0）；
  - 计数 **>0** 真值：ridetrack 逐趟 `bendingTimes/brakesTimes`，myRideInfo
    `bendingTimesTotal/brakesTimesTotal`，analyse 周期 `bendingTimes/brakesTimes`。
    「字段在」不代表「云端在统计」—— AE5i Pro 这些字段在但恒 0。
- 全 0 / 缺失 ⇒ 回填（AE5i Pro 形态）。门控作用面：ridetrack b/d 写入、
  myRideInfo 日汇总与总量覆盖、analyse 月汇总、day store 累计（按趟跳过）；
  里程/极速的 isZero 补零不受门控。
- 决策表已用实车抓包核验：AE5i Pro ridetrack 6 趟全 unmanaged ⇒ 填；
  AE7 形态（计数>0 / flag=true）⇒ 保服务端值。

### iOS：门控整体撤回（2026-11-18 16:01 车主令，维持）

`ZeehoRideFill.js` 删除 `detectNativeStats`/`scanStatsFlag`，`fillEvents`
恒 `true`：急刹/压弯一律回填。`$persistentStore` 的 `zeeho_native_stats`
遗留键不再读写。

### 2026-11-18 二次修订：急刹/压弯必须逐事件判（2026-07-02 前的压弯）

> 车主令：「2026-07-02 之前 AE5i Pro 仍然会提供急刹数据，从未提供压弯数据；
> SpeedHunter 不会回填 2026-07-02 前的压弯数据。按这个逻辑优化。」

**接口实测（AE5i Pro `358122500143678`，token 直连 `ridetrack_v2` / `myRideInfo` / `analyse`）**：

| 数据源 | 2026-06 | 2026-07-01 | 2026-07-02 | 2026-07-03 起 |
|---|---|---|---|---|
| ridetrack 逐趟 `brakesTimes` | 169 趟中 153 趟 > 0 | 合计 67 | 合计 116 | 全 0 |
| ridetrack 逐趟 `bendingTimes` | 全 0 | 0 | 0 | 全 0 |
| `cyclingEventStatisticFlag` | null | null | null | null |
| myRideInfo `brakesTimesDay` | 73 / 46 / 37 / 39 … | 67 | 116 | 0 |
| myRideInfo `bendingTimesTotal` | "0" | "0" | "0" | "0" |
| analyse `brakesTimes` | 1814 | — | — | 7 月合计 202 |
| analyse `bendingTimes` | 0 | — | — | 0 |

**病根**：v5.2.2 的 `serverManaged(node, "bendingTimes", "brakesTimes")` 把两个计数
放进同一个「云端在统计」判定。AE5i Pro 在 2026-07-02 及之前急刹有真值 ⇒ 判定为
true ⇒ 整趟跳过：既不回填 `bendingTimes`，也不进 day store（连带 myRideInfo /
analyse 的月度压弯都没有数据源）。2026-07-03 起急刹也变 0，门控才放行，所以
「7 月 2 日前的压弯永远是 0」。

**修复**：`serverManaged` 拆成 `eventManaged(node, countKey)`，逐事件调用。

| 事件 | 保持服务端值（不回填） | 允许回填 |
|---|---|---|
| 压弯 | flag=true，或 `bendingTimes` / `bendingTimesTotal` / 月 `bendingTimes` > 0 | 否则一律回填（云端从未下发过） |
| 急刹 | flag=true，或计数 > 0，**或日期 ≤ 2026.07.02** | 其余（2026-07-03 起） |

- 日期兜底只作用于急刹的 0 值：2026-07-02 前某趟恰好 0 次急刹是**真 0**，不能被
  GPS 估算改成正数。常量 `RideFill.SERVER_BRAKES_LAST_DAY = "2026.07.02"`。
- ridetrack 只要**任一**事件归我们就进 day store（`!brakesManaged || !bendsManaged`），
  两项都归云端（AE7 形态）才跳过 —— 这是 7 月 2 日前压弯能进月度汇总的前提。
- myRideInfo：压弯用响应级 `bendingTimesTotal`；急刹逐日判 + 日期兜底（响应里
  没有 `brakesTimesTotal` 字段）。急刹还额外受**车型级信号**保护：`bendsManaged`
  （`bendingTimesTotal > 0`，AE7=1376）为 true 时急刹一律不动，否则 AE7 那些
  真 0 的天会被 GPS 估算注入假急刹。
- analyse：`brakesTimes` / `bendingTimes` 各自判，不再合并；同样用 `bendsManaged`
  兜住急刹的车型级判定。

**离线预估**（用 `tools/ride/eval_traj.py` 复刻 Java 参数跑真实轨迹）：
2026-06 可回填压弯 **534 次 / 29 天**；2026-07 可回填 **287 次 / 21 天**
（其中 7/1–7/2 共 50 次）。修前这些天全部为 0。

### 边界与已知取舍

- 服务端在统计的车型（AE7 类），其个别 0 值趟/月份会被我们的 GPS 估算回填
  ——「0 分不清是没统计还是真 0」，车主指令明确取「宁可回填」一侧。
- day store 按天聚合，不按车辆分键：服务端在统计的趟在新门控下**不进 store**
  （按趟跳过），因此 AE7 数据不会经 store 污染 AE5i 的日汇总/月汇总；仅当
  AE7 出现 0 值趟时才会以 GPS 估算入账（同上一条取舍）。
- AE5i Pro 若哪天服务端开始真发总数（>0），该响应自动切到「保服务端值」，
  无需升级模块。

## 12. 真机根因补充：爱加密壳与 hook 延迟安装（2026-11-18 v5.2.3）

§11 定稿后真机仍显示 0。取证结论：**三轮门控代码在真机上从未执行** ——
ZEEHO App 3.1.0 为爱加密加固壳，`handleLoadPackage` 期业务类未解密，
attach 期安装的 NetPatch/RideFetch 等 hook 全部 ClassNotFound 后永不重试。

修复：`MainHook.deferUntilRealDexReady()` 以 `Activity#onResume`（框架类，
壳不搬）为锚，首个 com.cfmoto Activity 出现时一次性安装全部依赖真实 dex
的 hook（RideFetch/NetPatch/ArtColor/Mqtt/Ota）。

真机端到端验证（AE5i Pro 当前车）：

- ridetrack_v2：85 趟 / 16 天，全部回填进 day store（持久化
  `speedhunter_days/`），patched 响应 1,010,443 B 交付 App。
- myRideInfo：`filled 33 over 18 records, total: bendingTimesTotal=170`；
  页面实际显示 **压弯 170 次**，总里程 222 km 服务端真值保持。
- 重入（App 对 patched 响应再 build 再进 hook）触发 `serverManaged`
  跳过重填，值保持，属良性。

首次建账时序：统计页先拉 myRideInfo、后（进历史轨迹 tab 才）拉
ridetrack_v2，故装新版后第一次进统计页可能先显示 0；进一次历史轨迹
或下次冷启动即持久生效。

## 13. 总压弯口径：本车全生命周期（2026-11-18 v5.2.4）

17:27 车主令：总里程旁的压弯 = **本车记录总共的压弯次数**（对照 AE7
官方显示逻辑），不能只加当前响应（当月）—— 换月查看时单月和会把总数
缩回。

- `RideDayStore.totals()`：遍历 day store 目录全部 `*.json`，返回
  `[急刹总和, 压弯总和]`。
- `RideFill.fillMyRideInfo`：`bendingTimesTotal` 写
  `max(dayStore 全店压弯和, 响应当月和)` —— store 有账本即全生命周期
  值，store 空（未建账）退回当月和；guard 不变（`!serverManaged &&
  值>0 && 与服务端不同才写`）。
- iOS 同步：`totalBendsAllTime()` 枚举 `$persistentStore` 的
  `zeeho_day_*` 键累加 `st.d`；无 `allKeys` 的环境退回当月和（边界：
  需逐月重刷后全量才准）。
- 真机效果：v5.2.3 验证时的 170 次即来自 day store 汇总路径；v5.2.4
  起换月查看不再缩数。
