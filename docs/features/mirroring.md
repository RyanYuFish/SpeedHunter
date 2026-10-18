# 仪表投屏

独立运行，不依赖官方 App / LSPosed。来自社区基于 `a0bb0b9` 的 3.6.x 改进，见 [`community/`](../community/)。

## 模块

| 模块 | 文件 | 作用 |
|---|---|---|
| 抓屏编码 | `mirror/ScreenEncoder` | Surface 输入 H.264；能力枚举 + profile/帧率/码率回退 + 换编码器重试 |
| AVC 封包 | `mirror/AvcFrames` | IDR 判定，只发完整帧 |
| 前台服务 | `mirror/StandaloneMirrorService` | 持 MediaProjection 令牌 |
| 协议库 | `app/libs/ridedaemon.aar` | Go 协议库预编译（`native/ridedaemon` 为源码） |
| 二维码 | `ota/MirrorRequest` + `ui/MirrorCaptureActivity` | ZXing 扫码或粘贴 URL |
| P2P | `ui/P2pAccess` + `ota/CarP2p` | 权限预检、BUSY 退避、40s 窗口、poll 刷新 |
| 自测 | `mirror/MirrorTestActivity` | 移动条 + 实时统计 |

## 流畅度

- 档位：平衡 15 fps / 800 kbps（默认）；20 fps / 1200 kbps；10 fps / 500 kbps
- 短 GOP（约 500 ms 一次 IDR）；启动 / 旋转 / 拥堵恢复额外请求关键帧
- Android 10+ 输入端限帧（避免屏幕高刷让编码器空转）
- 去掉 4 KB / 3 ms 固定等待，走 TCP 背压 + 仪表拉流节奏
- 有界队列 4 帧；队满丢待发链等新 IDR
- 每个完整拉流请求刷新写入截止时间（旧实现只在非空帧设置且不刷新 ⇒ 空闲回复沿用过期截止时间 ⇒ `i/o timeout`）
- 旋转复用同一 MediaProjection / VirtualDisplay，不重复消耗授权令牌
- 性能区约 2 s 刷新：编码 / 提交 / 发送率、仪表请求频率、TCP 写入耗时、队列深度、累计丢弃、编码器名

统计为应用端采样：发送 FPS ≠ 仪表解码/显示 FPS；无车端时间戳测不到端到端延迟。

## 使用

1. 扫码或粘贴 URL → 解析
2. Wi-Fi Direct 扫描 → 配对（需系统定位）
3. 流畅模式开 → 开始 → 允许**整个屏幕**共享
4. 卡顿：开「流畅度测试画面」，比编码率 / 发送率 / 仪表请求频率

| 现象 | 排查 |
|---|---|
| 编码率远低于目标 | 手机采集 / 编码器 / 占用 / 温控 |
| 编码正常、仪表请求低 | 仪表拉流节奏 / 协议调度 |
| 写入慢且队列积压 | 链路或接收端背压 |
| 编码发送达标仍卡 | 仪表解码 / 显示缓冲 |
| 测试条流畅、导航卡 | 导航 App 刷新率 |
