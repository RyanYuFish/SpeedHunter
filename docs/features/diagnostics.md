# 车端诊断 · 施工中

> ⚠️ **状态：施工中（Under Construction）**
> 诊断界面与底层引擎已从当前版本移除，板块入口仅保留占位文案「🚧Under Construction.」。
> 后续版本将合并一次较大的改动后重新开放，届时再补回本节详细文档。

## 范围

- **移除项（本版本）**：诊断操作台 UI、DoIP/UDS/BLE/RS485 引擎（`diag/` 包）、车辆链路层
  （`VehicleLink`）、网络诊断条（`CarNetController`）、Wi-Fi Direct 面板（`P2pPanel`）、
  共享连接条布局 `view_carnet.xml`。
- **保留项**：底部导航「车辆诊断」入口仍存在，点击进入即显示占位页。

## 红线（待重开时沿用）

- 只读随时可跑；写类需 `allowWrite` + 二次确认
- ABS 泵真实转动 10 秒，盯住放气螺丝
- 不臆造 ECU 地址与 routine ID
- 网络诊断只做 TCP 握手，不发非本协议帧、不改网络配置
