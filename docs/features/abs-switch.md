# ABS 开关：固件入口取证 + Xposed 勾出（AE5i Pro）

> 2026-11-12。状态：**v4.7.41 已实现 UI 注入，待实车验证**。
> 车主观察：ZEEHO App 里 AE5i Pro 车型没有 ABS 开关项；目前关闭 ABS 只能走 OBD 接口发指令。
> 本文件汇总两条线的证据：① TBOX 配置字远程设置开关；② 仪表固件中的 ABS 开关入口；③ App 侧 Xposed 勾出实现。

## 1. 结论速览

| 问题 | 答案 |
|---|---|
| 仪表固件有没有 ABS 开关？ | **有完整下行链路**：`mcucom_set_ABS(int)` / `carSettings.ABS` / `MCUCOM_SIGNAL_ABS_MODE`，CFCP 属性 `0x21409308` |
| TBOX 配置字里有没有 ABS？ | **有**：`tbox_config_list.set_abs`；`SetItem2` 的 `m_vehsetitem2-1: ABS/MTC/ESS/DriMode`；配置字 `cfg[6] bits[6:5]` = 「ABS 可设置」 |
| App 为什么没有这一行？ | **云端配置表过滤**：`vehicleSet/items` 与 `itemsV2` 均无 `ANTI_LOCK_BRAKE_SYSTEM` 条目；渲染器按列表建行 |
| Xposed 怎么勾出？ | 复刻 `NitroUnlock` v4.7.9 实车验证路径：克隆注入设置列表 + `DRIVING_MODE_SETTING` 分类子项 |
| 拨开关能真的关 ABS 吗？ | **未验证**。AE5i 的 ABS 写入 serviceCode 未取证，注入条目已清空 serviceCode 防误写 |

## 2. 仪表固件侧证据（全部来自 `virtual/spapp-root/lib/**/*.so` 明文符号）

### 2.1 `lib/module/default/libmcucommodule.so` —— 下行设置主链路

| 符号 / 字符串 | 角色 |
|---|---|
| `_ZN12MCUCOMModule14mcucom_set_ABSEi` (`mcucom_set_ABS(int)`) | **ABS 设置下发入口**，与 `mcucom_set_ESS/MTC/DRIVEMODE/AUTO_HEADLIGHT` 同族 |
| `mcucom_rx_RspABS` / `carSettings.ABS = ` | ABS 设置应答回读 |
| `MCUCOM_SIGNAL_ABS_MODE` / `car_control_state.abs_mode` | ABS 模式状态信号 |
| `MCUCOM_SIGNAL_ABS_INVALID` / `DialogControl::slots_abs_invalid` / `slots_abs_fault` | ABS 无效/故障弹框 |
| `MCUCOM_SIGNAL_LAMP_ABS_off` / `LAMP_ABS_fault` | 指示灯信号（对应 `indicator_lamp` byte0/byte1） |
| `m_vehsetitem2-1: ABS/MTC/ESS/DriMode`（`libnipcmodule.so`） | **SetItem2 第 1 字节位域就含 ABS** |
| `tbox_config_list.set_abs` / `set_brs` / `car_type`（`libdashclustoractivity.so` `.rodata 0x18a367`） | **TBOX 配置表的 ABS 键** |
| `mcucom_rx_tbox_config_list_handle` / `MCUCOM_SIGNAL_VEHICLE_TBOX_CONFIG_LIST`（mcucom） | TBOX 配置表接收链 |
| `ipc_get_update_cfgword_tbox_config_list` / `Api_VeData::api_logic_tbox_config_list(TBOX_CONFIG_LIST)`（nipc） | TBOX 配置表 IPC 解析链 |

**CFCP 属性 ID**（`Gemini-Dummy/docs/EMULATION.md` §29.1 已归位）：

| ID | 函数 |
|---|---|
| `0x21409305` | `mcucom_set_AUTO_HEADLIGHT` |
| **`0x21409308`** | **`mcucom_set_ABS`** |
| `0x21409309` | `mcucom_set_MTC` |
| `0x21709302` | `mcucom_tx_setItem2`（ABS/MTC/ESS/DriMode 位域所在） |

`docs/06-BLE协议.md` §5 亦记：仪表→MCU 的 tx 仅 `setItem1-6 / ABS / ESS / MTC / BLEAddr` —— ABS 是**独立下行写通道**，不是 setItem2 的附属。

### 2.2 原生设置页（`libdashclustoractivity.so`）

| 类 | 用途 |
|---|---|
| `DrivingAssistanceSet`（`ModesubOperator`/`ModeaddOperator`/`slot_setItem3_updata`/`slot_setItem4_updata`） | 驾驶辅助设置页 |
| `MotoSet`（`VHAL_Motoset`/`ipc_set_setItem3`/`ipc_set_setItem4`） | 车辆设置页，设置项由 `OperatorWidget` 左右键改值 |
| `RideSet` / `SystemSet` / `AmbientLight` / `UnitSet` | 同族原生设置页 |
| `FnClickDialog`（`autoLightOn`/`changeAutoLight`/`setting_item2`/`ipc_set_setItem2`） | 快捷设置弹窗，通过 `ipc_set_setItem2` 下发 |
| `BaseWidget::ABS_close_show` / `slots_lamp_abs_off` / `slots_lamp_abs_fault` | ABS 关闭/故障图标显示 |

`docs/70` §5.15 补证：`AE4iMenu` 图标名表（`.rodata 0x1970xx–0x1974xx`）里 **ABS / TC / 超时关机均有对应 `*-开启.png`** —— 原生菜单资源存在。

### 2.3 配置字能力位（`docs/70` §2，16B 位图首次完整定案）

| 字段 | 位置 |
|---|---|
| **ABS 可设置** | **`cfg[6] bits[6:5]`** |
| TC | `cfg[6] bits[4:3]` |
| **ABS 指示灯** | **`cfg[13] bit5`** |
| 龙头锁 | `cfg[13] bit6` |

⇒ 仪表**是否提供 ABS 设置菜单**由配置字 `cfg[6] bits[6:5]` 门控。这与云端 App 的过滤是两层独立机制。

### 2.4 车端物理层

`docs/30` §10.2：GD32 固件有 `ABSConfig=%d,%d @0x08052344`（`BRSConfig`/`VehTypConfig` 同族）。
AE7 实采 `vehicleTypeInfo`：`absConfig:0`。AE5i 实采同值。
`docs/17` §ECU 地址表：ABS `0x7e1/0x7e9`（CAN 诊断）。
`docs/25`：诊断机数据库有 `ABS_BOSCH` 节点。

## 3. App 侧证据（v3.0.5 脱壳 dex + 实采）

### 3.1 文案与枚举都在

| 位置 | 内容 |
|---|---|
| `res/values/strings.xml:34/35/107` | `abs`=ABS、`abs_tip`=ABS指防抱死制动系统…、`anti_lock_brake_system`=**ABS开关** |
| `res/values/public.xml` | `abs`=0x7f13001f、`abs_tip`=0x7f130020、`anti_lock_brake_system`=0x7f130068 |
| `app_full.dex` | `ANTI_LOCK_BRAKE_SYSTEM`、`CHANGE_ABS_SWITCH`、`antiLockBrakeSystem` |
| AE7 实采 `vehicleSetInfo` | `antiLockBrakeSystem=1`（状态字段存在） |

⇒ **App 已实现 ABS 开关 UI 与状态模型**，只是 AE5i Pro 的云端配置不下发该条目。

### 3.2 云端过滤确证

| 响应 | 条目数 | ABS 相关 |
|---|---|---|
| `vehicleSet/items`（AE7 实采） | 48/53 | 无 `ANTI_LOCK_BRAKE_SYSTEM`；有 `BRAKE_POWER`(电子制动) |
| `itemsV2.vehicleTypeDetailFunctionVOList` | 52（含 serviceCode） | 无 ABS 条目 |
| `vehicleSetFunctionVOList` → `DRIVING_MODE_SETTING`（骑行偏好设置） | subFunctions 4 项 | `KINETIC_ENERGY_RECOVERY` / `TCS_TRACTION_CONTROL` / `MAX_SPEED` / `BRAKE_POWER` |
| AE5i 实采 `hook-2251.log` | 97 个 functionCode | **无** `ANTI_LOCK_BRAKE_SYSTEM`；`vehicleSetInfo` 无 `antiLockBrakeSystem` 字段 |

与氮气完全同构（`docs/features/nitro-ui.md`）：**渲染器按云端下发列表建行**，v4.7.8 只改能力位/补状态字段无效，v4.7.9 克隆注入列表后行才出现。

## 4. Xposed 实现（v4.7.41）

### 4.1 `net/AbsUnlock.java`（新增）

开关 `Keys.KEY_ABS_UI = "abs_ui"`，opt-in **默认关**，`NetPatch.tryPatch` 里与氮气同树处理，一次重建响应体。

1. **设置列表注入**：元素带 `functionCode`+`isSupportSetting` 的数组（覆盖 `vehicleSet/items`、`itemsV2.vehicleTypeDetailFunctionVOList`、`vehicleSettingList`）缺 `ANTI_LOCK_BRAKE_SYSTEM` → 深拷贝首元素改写注入。
2. **分类子项注入**：`DRIVING_MODE_SETTING` 的 `subFunctions` 缺该项 → 克隆兄弟子项，`functionName=ABS开关`、`describe=abs_tip` 原文。
3. **状态字段**：带 `etcsTractionControl` 的 `vehicleSetInfo.data` 对象缺 `antiLockBrakeSystem` → 补 `1`（AE7 实采稳态，默认开启的诚实状态）。

### 4.2 红线

- 克隆条目**清空** `blueToothServiceCodeList` / `functionConfig`。AE5i 的 ABS 写入 serviceCode 未取证（52 项列表无该条目），保留兄弟条目的真实 serviceCode 会误写到别的功能。
- **不改** `vehicleKind(s)` / `deviceType` / 任何身份字段（氮气需要 kinds→3，ABS 不需要）。
- **不构造任何车辆写帧**，不 Hook BLE/云端写入路径。
- 拨动 App 内该开关的写入是否生效**未验证**，不应依赖；本功能定位是「勾出入口」。
- 关掉模块开关 → 行消失，回退干净。

### 4.3 接线清单

| 文件 | 改动 |
|---|---|
| `net/AbsUnlock.java` | 新增 |
| `net/NetPatch.java` | `AbsUnlock.apply(root,url)` + `absChanged` + 日志 |
| `core/Keys.java` | `KEY_ABS_UI` |
| `core/ConfigSender.java` | 广播 `abs_ui` |
| `core/RemoteSettings.java` | 接收 `abs_ui` |
| `ui/RideFragment.java` | `swAbs`/`tvAbsHint` + persist + hint |
| `res/layout/activity_ride.xml` | 「ABS 开关入口」卡片 |
| `app/build.gradle` | vc93→94 / 4.7.40→4.7.41 |

## 5. 验证步骤（需车主实机）

1. 装 `release/speedhunter-v4.7.41.apk`，LSPosed 作用域 `com.cfmoto`。
2. **在 SpeedHunter 骑行记录页拨开「ABS 开关入口」**（不能只发广播，冷启动会被 UI prefs 反向同步冲掉，氮气实测踩过）。
3. 官方 App → 车辆设置 → 骑行偏好设置 → 页面下部应出现「ABS开关」行（电子制动之后）。
4. 看 `speedhunter_netdump.log` / HookLog：`abs unlock: inject=1 sub=1` 且 URL 命中 `vehicleSet/items`。
5. 关开关 → 行消失。
6. **不要拨动 App 内 ABS 开关**做车辆写入（未验证）。若要验证写入，先抓 `vehicleSet/addVehicleSet` 请求确认 `vehicleSetEnum` / `serviceCode`，并与 §2 的 `0x21409308` / `SetItem2` 位域对照后再单独授权。

## 6. 证据等级与未竟

| 结论 | 等级 |
|---|---|
| App 有 ABS 文案/枚举/状态字段 | 一手（apktool res + 脱壳 dex + AE7 实采） |
| AE5i 云端列表无 ABS 条目 | 一手（AE5i `hook-2251.log`、AE7 `netdump.log`） |
| 固件 `mcucom_set_ABS` / `0x21409308` / SetItem2 ABS 位 | 一手符号 + EMULATION §29.1 属性归位；**未实车复测** |
| `cfg[6] bits[6:5]` = ABS 可设置 | `docs/70` §2 构造函数 QDebug dump 与 .rodata 描述表逐项对齐 |
| `ANTI_LOCK_BRAKE_SYSTEM` 就是 App 渲染用的 functionCode | **推定**（枚举名 + 资源名一致，但无「某车型真实下发了该行」的实采对照）。若实车行不出现，下一步用 AE8/AE6 高配账号抓一份含该行的 `vehicleSet/items` 定案 |
| ABS 写入 serviceCode | **未取证**，故注入条目清空 |
| ABS 开关显示（本次实现） | **待实车验证** |

## 7. 相关

- `docs/features/nitro-ui.md`（同构机制，v4.7.9 实车验证通过）
- `docs/70-仪表氮气加速与氮气表情彩蛋逆向.md` §2 配置字位图
- `Gemini-Dummy/docs/EMULATION.md` §29.1（60 个属性 id）、§42.7（indicator_lamp 位）
- `docs/06-BLE协议.md` §5（仪表→MCU tx 清单）
- `docs/30-总结-OBD读写与BikeType现行方法.md` §10.2（GD32 `ABSConfig`）
