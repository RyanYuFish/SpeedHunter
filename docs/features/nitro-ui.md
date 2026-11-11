# 氮气加速入口（v4 · 实车验证通过）

> **2026-11-08 归档**：车主观察 AE7 实车氮气加速没有什么特别的动画效果；App 行的显示不证明车端功能生效。综合证据见 [研究归档](../../../docs/71-AE7氮气与原生菜单研究归档.md)。

> 状态（2026-11-07 03:41）：**AE5i Pro 实车验证通过** —— 骑行偏好设置页出现「氮气加速」行
> （氮气图标 + 标题 + 完整 `nitro_tip` 文案 + 右侧开关，默认关；动能回收 / eTCS / 电子制动等原行完好）。
> 机制 = 网络层「克隆注入」+ 车型档位置 3，opt-in 默认关，关掉即完全回退。

## 实车证据链（AE7 账号直采 + 双车差分）

| # | 事实 | 证据 |
|---|---|---|
| 1 | AE7（vehicleKind(s)=3、nitro 字段在）在 v3.0.5 **也不渲染**氮气行 | 实机扫设置首页/骑行参数页/仪表页；云端 newest=3.0.3，3.0.5 已最新 |
| 2 | 渲染器按设置**列表**建行；两车列表均无 NITRO 条目 | netdump：vehicleSet/items（48/53 项）、itemsV2 vehicleTypeDetailFunctionVOList（52 项）、vehicleSettingList |
| 3 | 仅 kinds→3 + 补 nitro 字段（v4.7.8）不够 | 03:26–03:30 实测 AE5i 无行 |
| 4 | **克隆注入 vehicleSet/items 后行出现** | 03:40 hook 日志 `inject=1 @ vehicleSet/items?…vinNo=3581…`；03:41 用户实机确认 + uiautomator dump（t1.xml）|
| 5 | 关开关 → 行消失（回退干净） | T4 实测 |

## 机制（`net/NitroUnlock` v4）

```
任意响应 → okhttp Response$Builder#build()
  └─ 开关开启时（递归遍历 JSON）：
       ① vehicleKinds / vehicleKind（int≠3）→ 3
       ② 带 vehicleKind 的对象缺 nitro 键 → 补 "nitro": 0（默认关的诚实状态）
       ③ 设置列表（元素带 functionCode+isSupportSetting；命中 vehicleSet/items 等）
          缺 NITRO → 深拷贝首元素改写注入：functionCode=NITRO、isSupport/isSupportSetting=true、
          blueToothServiceCodeList/functionConfig 清空（防误写真实 serviceCode）
       ④ DRIVING_MODE_SETTING 分类 subFunctions 缺 NITRO → 克隆注入
          （functionName=氮气加速、describe=nitro_tip 原文）——设置首页分类芯片
```

不碰：身份字段（vehicleType 系）、deviceType。克隆条目清空 serviceCode；这不证明 App 的其他云端写入路径不会发请求。

## 使用（重要）

1. `adb install -r release/speedhunter-v4.7.9.apk` → LSPosed 作用域 `com.cfmoto`；
2. **在 SpeedHunter 骑行记录页拨开「氮气加速入口」开关**（★ 不能只发广播：冷启动时模块 UI 的
   反向同步会用 UI prefs 覆盖 hook 侧配置，把广播值冲掉——实测踩过）；
3. 官方 App → 车辆设置 → 骑行偏好设置 → 「氮气加速」行在页面下部（电子制动之后）；
4. 回退：拨回开关，重进页面即消失。

## 当前边界与下一步（2026-11-07）

- 实车验证覆盖入口显示与回退，冷重启后经正常导航仍可见；**没有证明氮气功能已启用**。
- 设置项 161 的云端请求曾两次约 12 秒返回失败；应答方、失败原因及 AE5i 仪表对请求的处理能力均未确认，不能将超时直接归因于仪表。
- 仿真器已让原厂软件接收配置字；首次人工检查仪表设置页未出现氮气开关。后续默认值修正后是否出现仍待验收。
- 下一步在仪表仿真器中验证 App 云端下发配置：先确认云端、TBOX、MCU 与仿真仪表之间各段是否实际连通，再记录请求、应答、配置变化及设置页结果。直接注入配置字只能作为对照，不能当作云端链路成功。
- 实车写入仍需单独授权；实验期间保留默认关闭及原有车型身份。

## 版本时间线

| 版本 | 路线 | 结果 |
|---|---|---|
| 4.7.4 | 设置列表按猜测形状注入 | 无效（形状/列表不对） |
| 4.7.5 | +nitro 键位盲翻 | 撤回 |
| 4.7.6 | 全部撤回，仅留取证 | 换 AE7 账号直采成功 |
| 4.7.8 | kinds→3 + 补 nitro 字段（AE7 实采） | 无效（渲染器按列表建行） |
| **4.7.9** | **克隆注入列表 + 分类子项 + kinds→3** | **✅ 实车行出现，可回退** |
