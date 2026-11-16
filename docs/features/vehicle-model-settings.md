# 车型差异化设置项（AE5i vs AE7 Pro）

日期：2026-11-16。数据来源：官方 App 真实响应（NetDump 抓取，`web_layer=true` 临时开启）。

## 1. 数据来源与抓取方法

| 接口 | 说明 |
|---|---|
| `GET /v1.0/app/cfmotoserverapp/vehicleSet/items?vinNo=<VIN>` | 功能项扁平表（含 `functionCode` / `isSupport` / `isSupportSetting` / `isSupportBlueTooth` / `blueToothServiceCodeList` / `functionConfig`） |
| `GET …/vehicleSet/itemsV2` | 设置页渲染源：`vehicleSetFunctionVOList`（9 个分类）+ `vehicleTypeDetailFunctionVOList`（功能明细，含中文名与子功能） |
| `GET …/vehicleSet/chargePower/options` | 充电功率选项（AE7 原生充电信息） |
| `GET …/vehicleSet/vehicleSetInfo/<VIN>` | 当前值 / 状态字段 |

抓取步骤：
1. 临时把 `speedhunter_config.json` 的 `web_layer` 置 `true`（NetDump 的 `maybeDump` 在 `NetPatch.tryPatch` 内，受该开关门控）。
2. 冷启动 `com.cfmoto`，进车辆设置页。
3. `adb pull /sdcard/Android/data/com.cfmoto/files/speedhunter_netdump.log`。
4. **抓完必须恢复原配置**（本次已恢复 `web_layer=false`）。

实测两车：
- AE5i Pro `358122500143678`：items 48 项 / itemsV2 明细 49 项。
- AE7 Pro `LCETHGD17S6006301`：items 54 项 / itemsV2 明细 56 项。

## 2. AE7 Pro 独有 functionCode（16 项）

| functionCode | 中文/说明 | 用户口径 |
|---|---|---|
| `LIGHTING_EFFECT` | 灯效 | 灯效设置 |
| `CUSTOM_BUTTON_HIGH` | 单键-仪表信息（AE5i 为 `CUSTOM_BUTTON_LOW` 单键-Hello音效） | 独立/组合自定义按键 |
| `POWER_MODE` | 双电动力模式 | 双电动力模式 |
| `CUSTOMIZE_MAX_SPEED` | 各模式最高速度（AE5i 用 `MAXIMUM_SPEED`） | 各模式最高速度 |
| `EASY_RIDE` | 简易骑行 | 简易骑行 |
| `CHARGING_POWER_AE8` | 充电功率 1900w + `chargePower/options` | 原生充电信息（电流、剩余时长） |
| `AUTOMATIC_LOCK` | 自动锁车 | — |
| `INDUCTIVE_UNLOCKING_CUSTOM_DISTANCE` | 感应解锁自定义距离 | — |
| `TCS_TRACTION_CONTROL` | TCS（AE5i 为 `E_TCS_TRACTION_CONTROL`） | — |
| `RADAR_ASSISTED_FCW` | 前碰撞预警（AE5i 为 `RADAR_BLIND_MONITOR`） | — |
| `TIRE_MONITORING_INTERNAL` | 前轮胎压（单位 bar） | — |
| `BATTERY_TEMP` / `SOH` | 电池温度 / 健康度 | — |
| `BMS_FIRMWARE` / `TBOX_FIRMWARE` | 固件信息 | — |
| `STEEP_SLOPE_DESCENT` | 陡坡缓降（AE5i 该码在 items 有、itemsV2 明细无） | — |

## 3. AE5i 独有 functionCode（9 项）

`BATTERY_LEVEL`、`BLUETOOTH_ADDRESS`、`CUSTOM_BUTTON_LOW`、`E_TCS_TRACTION_CONTROL`、`LABMS_FIRMWARE`、`MAXIMUM_SPEED`、`RADAR_BLIND_MONITOR`、`SMART_PART`、`TIRE_MONITORING`。

## 4. 渲染结构

`itemsV2.data`：

```
{
  vehicleSetFunctionVOList: [ { functionCode, functionName, describe, subFunctions[], type } ... ],  // 9 个分类
  vehicleTypeDetailFunctionVOList: [ { functionCode, sortNum, isSupport, isSupportSetting,
                                       isSupportBlueTooth, blueToothServiceCodeList[], functionConfig[] } ... ]
}
```

`blueToothServiceCodeList[].serviceCode` 是写入用的服务码（如 `CRUISE_CONTROL_HOLD=603980085`、`EASY_RIDE=603980168`）。`functionConfig[]` 带 `functionRestrictVersion` 等版本门控。

分类（两车同序）：防盗与安全、音效设置、钥匙与共享、骑行偏好设置、便捷骑行、辅助驾驶、伴我回家、自动驻车/陡坡缓降、电池信息与设置、功能许愿池。

## 5. 车型判定建议（数据驱动，不依赖 VIN 白名单）

`items` / `itemsV2` 响应中出现 `POWER_MODE` 或 `LIGHTING_EFFECT` ⇒ AE7 类；否则 AE5i 类。
比 VIN 前缀更稳（新车型 VIN 不同，功能码是能力事实）。

## 6. 与现有功能的交叉

- 用户补充：AE7 Pro 自带极速/加速/急刹/压弯统计 ⇒ AE5i 才需要 `G1/G2 + ride_fill`；AE7 上这些增强项应隐藏。
- 现有 `NitroUnlock` / `AbsUnlock` 属同类「AE7 有、AE5i 无」的注入，可归入同一份车型能力表统一管理。

## 7. 实现（v4.8.9 / vc133）

### 7.1 车型能力表 `core/ModelProfile`

- `AE7_ONLY` = 15 项（上表 16 项去掉 `STEEP_SLOPE_DESCENT`）。
  **为什么去掉**：陡坡缓降在 AE5i 的 `items` 里确有该码且 `isSupport=true`，
  自定义键 19 = 陡坡缓降设置也有 AE5i 云端实测；它只是没进 AE5i 的 itemsV2 明细，
  属于「有功能、不在设置页」而非「AE7 独有」，裁掉会砍真实功能。
- `AE5I_ONLY` = 9 项，用于反向判定。
- `nativeSupports(model, code)`：AE7 视为能力全集，AE5i 不支持 AE7 独有项。

### 7.2 运行时判定 `net/VehicleModel`

判定顺序：
1. 响应里出现 `AE7_ONLY` 任一码 ⇒ AE7（强证据）；
2. 出现 `AE5I_ONLY` 且无 AE7 独有码 ⇒ AE5i；
3. 否则用 VIN 兜底（`LCETHGD` = AE7 车系、`3581225` = 本机 AE5i）；
4. 都没有就沿用上次落盘。

只在 `vehicleSet/items|itemsV2|vehicleSetInfo`、`vehicle/list`、`vehicleHomePageV2`
这些整车能力响应上判定，避免局部响应误判。判定结果落盘 `vehicle_model`，
供后续冷启动/多进程直接读取。

### 7.3 hook 门控（核心：原生已有就不 hook）

| 位置 | 规则 |
|---|---|
| `NetPatch.walk`（G1/G2） | `nativeAe7` 时直接 return。**关键：G1 写死 `vehicleKinds=2`，在 AE7（原生 3）上是降级** |
| `NetPatch.tryPatch` | `nativeAe7` 时跳过 `NitroUnlock` / `AbsUnlock` |
| `NitroUnlock.enabled()` | 追加 `&& !VehicleModel.isAe7()` 双保险 |
| `AbsUnlock.enabled()` | 同上 |
| `VehicleArtUnlock` | 与车型无关，保留 |

### 7.4 AE5i 裁剪 `net/UnsupportedFilter`

非 AE7 车型上，递归从**数组**里移除 `functionCode ∈ AE7_ONLY` 的条目
（只删数组元素，不删对象字段）。正常服务端不会下发这些项，属防御性兜底，
防止模块自己的克隆注入（如 ABS 复制分类子项）把 AE7 项带到 AE5i 上。

## 8. 验证（2026-11-16，真机 AE7 Pro `LCETHGD17S6006301`）

条件：`web_layer=true`、`nitro_ui=true`、`abs_ui=true`、`gate_analyse/event=true`。

| 检查 | 结果 |
|---|---|
| 车型判定 | `[Net] vehicle model -> AE7 Pro`，落盘 `vehicle_model=ae7` |
| G1/G2 改写 | 无 `G1 OLD` / `G2 OLD` 日志 ⇒ `vehicleKinds` 保持原生 3，未被降级 |
| 氮气/ABS 注入 | 无 `nitro unlock` / `abs unlock` 注入日志（`enabled()` 提前返回） |
| 崩溃 | crash buffer 空；进程存活 |
| 界面 | 首页正常；「灯效设置」「感应解锁 2.0」等 AE7 原生项可见 |

边界：AE5i 路径未实机验证（当前账号只有 AE7 车）；`STEEP_SLOPE_DESCENT` 按数据事实不裁。

