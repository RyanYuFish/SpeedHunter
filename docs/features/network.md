# 网络直连与诊断

车机地址不固定，组主在 `192.168.0.1` ↔ `192.168.49.1` 间浮动 ⇒ 地址靠探测，不写死。三页共用。

## 动作

- **系统 Wi-Fi 直连（主）**：断当前 Wi-Fi → 系统 WLAN 面板 → 连 `车机热点`（优先 5GHz）→ 回页「网络诊断」回填组主
- **App 内直连（兜底）**：`WifiNetworkSpecifier` / `addNetwork`
- **网络诊断**：TCP 握手判组主与端口（10950 / 13400 / 5555 / 10086 / 10930），识别模式与 5GHz，回填推送通道与 DoIP 目标

诊断只做 TCP 握手，不发非本协议帧、不改网络配置。

## 探测

- 必须绑定本机源地址：多网络下 default network（蜂窝）会抢路；只绑与 host 同 `/24` 的本机地址，否则跳过
- AP 扫描需 `ACCESS_WIFI_STATE` + 定位（API 27+）
- Android 6–12 P2P discovery 需运行时定位；Android 12 起同时申请 COARSE + FINE

## 坑

- `WifiP2pInfo.groupOwnerAddress` 是 `InetAddress`，须 `.getHostAddress()`
- `CarP2p.scan()` 先 `refreshGroup()`，残留组会立即回调 `onGroup` ⇒ 无组主地址的组信息不认

源码：`ota/CarNet`、`ota/CarP2p`、`ota/CarWifi`、`ui/CarNetController`、`ui/P2pPanel`、`ui/P2pAccess`
