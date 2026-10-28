# 自定义键 = 切换信息组（功能码 7）

> 状态：**已准备（prepared）** —— 代码移植自 DashProbe v0.6.0，2026-10-28 实车验证通过。
> 本模块内 **opt-in，默认关**，开启前对官方 App 零影响。

| 开关 | 默认 | 作用 |
|---|---|---|
| `custom_key_force` | 关 | 自定义键保存时恒置功能码 7（切换信息组） |

- **目的**：仪表主页按自定义键即在 **里程组 ⇄ 胎压组** 间切换，从而在首页显示前后轮胎压 / 胎温。
- **不改固件、不产 OTA**：完全不碰仪表二进制，无 Launcher / ABI / 异常展开风险。
- 来源与实测证据：见 `DashProbe/README.md` §9、`dist/tirepressuer/`。

## 机制

| 层 | 位置 | 本功能行为 |
|---|---|---|
| App | `com.cfmoto.ui.carsetting.CustomKeySelectActivity#handleSave` | **前置**把 `CustomButtonCode.commandCode` 反射改写成 7 |
| 车端 MCU | `sig_CustomKey`（1/3/19/31） | 不碰 |
| 仪表 UI | `libdashclustoractivity.so` keymap / `keyClickXxx` | 不碰 |

- hook 锚点 = Manifest 组件类（加固不影响），`handleSave` 为无参方法。
- 只改 `commandCode` 一个 int 字段；`buttonType` / `functionCode`（名字串）不动，App 界面仍显示用户点的卡片。
- 官方 App 此设置项**从不走 BLE**（实测 `cmd=0x0A` 写帧 0 条），恒走 4G 云端；本功能只移植模型层改写，BLE 帧兜底不移植。

## 安全边界

- 默认关：关闭时 hook 为纯 no-op，不触碰任何字段、不拦截任何调用。
- 整段包 try/catch：任何异常退化为「原样放行」，绝不让保存失败。
- 锚点类找不到 / `handleSave` 签名不符 → 安静跳过并记一行 `skip`。

## 使用

1. 模块 UI / 广播下发 `custom_key_force = true`（经 `com.github.speedhunter.CONFIG`，落盘到 com.cfmoto 外部 files，冷重启恢复）。
2. 打开 ZEEHO App → 车辆设置 → 自定义键 → 随便选一个功能 → 保存。
3. 车端：仪表上电后首页按自定义键 → 里程组 ⇄ 胎压组切换（实车已验，重启 / 投屏 / 来电后不丢）。
4. App 里该页显示「尚未选择按键功能」是**成功标志**（云端值 7 不在 App 自己的列表里，回落该文案）。

## 恢复

- 关掉开关，App 里选回原功能（默认回滚目标 = 3 解锁座垫）保存即可。
- 不要在「尚未选择按键功能」状态下关开关后随手保存（会把 `-1` 冲掉 7）；先拨回原功能再存。

## 源码

`customkey/CustomKeyOptions.java`（开关）、`customkey/CustomKeyForce.java`（hook + 反射改写）、
`core/Keys.java`（`KEY_CUSTOM_KEY_FORCE`）、`core/RemoteSettings.java`（广播落盘）、`MainHook.java`（installAll）。
