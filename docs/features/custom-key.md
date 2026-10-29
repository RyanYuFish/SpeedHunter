# 自定义键映射（功能码 1..31，默认 7 = 切换信息组）

> 状态：**可用（v4.7.2 起支持任选功能码）** —— 机制移植自 DashProbe v0.6.0，
> 2026-10-28 实车验证通过；v4.7.2 把固定 7 扩展为官方枚举的 23 项。
> 本模块内 **opt-in，默认关**，开启前对官方 App 零影响。

| 配置 | 默认 | 作用 |
|---|---|---|
| `custom_key_force` | 关 | 总开关；开启后保存自定义键时改写 `commandCode` |
| `custom_key_target` | `7` | 目标功能码（1/2/3/4/5/6/7/8/9/10/11/12/13/14/15/16/17/18/19/20/21/28/31） |

- **默认用途**：目标 7 = 切换信息组 —— 仪表主页按自定义键即在 **里程组 ⇄ 胎压组** 间切换，
  首页显示前后轮胎压 / 胎温。
- **不改固件、不产 OTA**：完全不碰仪表二进制，无 Launcher / ABI / 异常展开风险。
- 来源与实测证据：见 `DashProbe/README.md` §9、`dist/tirepressuer/`。

## 功能码真实性（2026-10-29 复核）

23 项码表与官方 App「自定义按键 → 选择按键功能」列表逐项一致，并有下列实证：

| 证据 | 覆盖码 | 结论 |
|---|---|---|
| **AE5i 云端 functionConfig 实测**（本车 VIN …678，DashProbe v0.5.0） | 1 / 3 / 19 / 31 | `SAY_HELLO` / `CUSHION_UNLOCK` / `STEEP_SLOPE_DESCENT` / `NO_OPERATION`，名称与码表完全一致 |
| **仪表固件 `BaseWidget::CustomKeyFunctionSet`** | 7 / 10 / 20 | 7 = `switchToolBar`（切信息组）、10 = `slots_muteSound`（静音）、20 = 音量增减 |
| **固件无操作判定** `cmp sl,#0x1f` | 31 | 31 = 无操作 |
| **官方 App `res/values/strings.xml`** | 11 / 12 / 17 / 19 | 动能回收 / BSD 盲区监测 / 最高车速限制 / 陡坡缓降，名称可查 |
| **AE5i 固件名字表**（`MenuPicInfo` 显示用，非函数码空间） | 1 / 2 / 9 / 10 / 12 / 13 / 14 | 名称出现，但**不能**据此认定函数码等价 |

⚠ **不是 23 项都在 AE5i 生效**：
- 云端只为 AE5i 下发 4 项（1/3/19/31）；
- 仪表只处理 7/10/20（15 有分支但语义未定名）；
- 其余（2/4/5/6/8/9/11/12/13/14/16/17/18/21/28）落 `sig_CustomKey` 交 MCU，
  本车型**未验证**，可能无响应。UI 会明确标注「AE5i 未验证」。

## 机制

| 层 | 位置 | 本功能行为 |
|---|---|---|
| App | `com.cfmoto.ui.carsetting.CustomKeySelectActivity#handleSave` | **前置**把 `CustomButtonCode.commandCode` 反射改写成目标码 |
| 车端 MCU | `sig_CustomKey`（1/3/19/31 等） | 不碰 |
| 仪表 UI | `libdashclustoractivity.so` `CustomKeyFunctionSet`（7/10/20） | 不碰 |

- hook 锚点 = Manifest 组件类（加固不影响），`handleSave` 为无参方法。
- 只改 `commandCode` 一个 int 字段；`buttonType` / `functionCode`（名字串）不动，
  App 界面仍显示用户点的卡片。
- **仪表不做映射**：`button_click_CustomL/R` 把 SetItem4 结构 `+0x0f/+0x10` 原样当
  function 传入，写多少就是多少。
- 官方 App 此设置项**从不走 BLE**（实测 `cmd=0x0A` 写帧 0 条），恒走 4G 云端。

## 安全边界

- 默认关：关闭时 hook 为纯 no-op，不触碰任何字段、不拦截任何调用。
- 目标码非法 / 未知 ⇒ `CustomKeyOptions.targetValue()` 回落 7，不把脏值写进官方 App。
- 整段包 try/catch：任何异常退化为「原样放行」，绝不让保存失败。
- 锚点类找不到 / `handleSave` 签名不符 → 安静跳过并记一行 `skip`。

## 使用

1. 模块 UI → 骑行记录页 → 「自定义键映射」打开开关。
2. 点「按键功能」→ 在 23 项里选择目标（默认「切换仪表信息组 · 7」）。
3. 打开官方 App → 车辆设置 → 自定义键 → **随便选一个功能 → 保存**。
   hook 会在保存前把功能码改写为目标值。
4. 车端：仪表上电后首页按自定义键 → 执行目标功能（目标 7 时即里程组 ⇄ 胎压组切换）。
5. App 里该页显示「尚未选择按键功能」是**成功标志**（目标值不在 App 当前云端列表里，
   回落该文案）。

## 恢复

- 关掉开关，App 里选回原功能（默认回滚目标 = 3 解锁座垫）保存即可。
- 不要在「尚未选择按键功能」状态下关开关后随手保存（会把 `-1` 冲掉目标值）；
  先拨回原功能再存。

## 源码

`customkey/CustomKeyOptions.java`（开关 + 目标码读取）、
`customkey/CustomKeyFunction.java`（23 项码表 + 证据等级）、
`customkey/CustomKeyForce.java`（hook + 反射改写）、
`core/Keys.java`（`KEY_CUSTOM_KEY_FORCE` / `KEY_CUSTOM_KEY_TARGET`）、
`core/RemoteSettings.java`（广播落盘 / `getInt`）、
`core/ConfigSender.java`（UI → hook 广播）、
`ui/RideFragment.java`（卡片 + 单选列表）、`MainHook.java`（installAll）。
