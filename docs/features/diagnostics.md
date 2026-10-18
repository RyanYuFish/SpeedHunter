# 车端诊断

写类默认禁用。

界面：顶栏三态胶囊 → 一键连接 → 功能宫格 → 链路切换 → 高级设置（折叠）→ 写操作红卡 → 日志。

## 链路

| 链路 | 路径 | 协议 |
|---|---|---|
| RS485 | 手机 → BLE 透传 → 车辆 RS485 | 私协议 `43 46 … 45 44` |
| CAN | 手机 → BLE 透传 → 车辆 CAN | ISO-TP（ISO 15765-2） |
| WIFI | 手机 → 车机热点 → DoIP 网关 | ISO 13400 + UDS |

RS485 与 CAN 共用同一条 BLE 连接，切换不断连。

能力：车辆信息 / 故障码（J2012 中文码表）/ ECU 全景 / DID 扫描 / 数据流 / RS485 离线解码 / ABS 排气 / 胎压匹配 / NFC 学车。

## 建链（两条，互为兜底）

车机地址不固定（`192.168.0.1` ↔ `192.168.49.1` 浮动），一律以探测为准。

| 路 | 操作 | 适用 |
|---|---|---|
| Wi-Fi Direct | 扫描 → 点仪表 → 建组后自动回填组主并复跑诊断 | 仪表在 OTA Update / 投屏等待界面 |
| 系统 WLAN | 连车机热点 `ZEEHO-xxxx` → 回页「网络诊断」 | P2P 内部错误时 |

P2P 由 `ui/P2pPanel` 封装（扫描 / 列表 / 连接 / 断开 + 权限预检），三页复用。

## 红线

- 只读随时可跑；写类需 `allowWrite` + 二次确认
- ABS 泵真实转动 10 秒，盯住放气螺丝
- 不臆造 ECU 地址与 routine ID
- 网络诊断只做 TCP 握手，不发非本协议帧、不改网络配置

源码：`ui/DiagActivity`、`diag/DiagClient`、`diag/DiagProtocol`、`diag/Dtc`、`diag/VehicleService`、`diag/BleRs485`、`diag/Rs485`、`ui/VehicleLink`
