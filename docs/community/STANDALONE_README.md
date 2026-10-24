# SpeedHunter 3.6.1-encoderfix（独立投屏测试版）

基于原项目 a0bb0b925e69218d6faaacdd419f523d868ba811，保留此前 3.5.3 的相机扫码及 P2P 扫描修复。

## 使用

1. 安装 APK，打开 SpeedHunter → 仪表投屏；无需官方 App、LSPosed、Root。
2. 仪表停留在投屏二维码页，用手机相机扫码（或粘贴完整 URL）。手机开启 WLAN、关闭个人热点。
3. 点击「开始独立 Wi-Fi Direct 投屏」，允许附近设备权限；Android 12 及以下需位置权限及系统定位开启。允许系统屏幕共享，选择整个屏幕。
4. 自动按二维码 name / MAC 匹配设备；未匹配时点「选择仪表」选择扫描列表里的自己的仪表，在系统或仪表上确认配对。
5. 看到视频帧发送计数后，切换到导航等应用。返回 SpeedHunter 或通知栏点「停止投屏」结束。

独立版由本应用完成 P2P、EC/PXC、MediaProjection、H.264 和视频传输。没有调用官方 App 的广播、SDK 或私有页面；其他车辆参数功能仍保留原有的官方 App hook，并不因此独立化。

## 兼容性边界

**已完成编译及模拟仪表协议测试；没有连接 AE5i 实车或真机测试，不能保证所有仪表固件出画面。**

- Android 7+，ARM64 / ARMv7；目前不包含 x86。
- 当前实现屏幕视频投影，不提供声音、仪表触控回传或官方专属导航界面。
- 需要仪表作为 Wi-Fi Direct 组主，并在标准 10930 端口提供 EasyConn；无法定位组主或协议拒绝会显示错误，不自动判定投屏成功。
- 手机蜂窝上网可保留：EC 初始化 socket 绑定 P2P 本机地址，10922/10921/10920 反向服务仅绑定 P2P 接口地址。
- 最高编码分辨率 1920×1920 且总像素不超过 1920×1080，采集 20/15 fps、2/1 Mbps（按编码器能力调整），H.264 Baseline 优先 / 默认 profile 回退，发送 IDR 关键帧（每 100 ms 请求一次，忽略请求时可能仅每秒一帧）；实际显示尺寸按仪表请求向下对齐 16 像素。
- 120 秒未开始发送视频会自动停止。Android 14 每次启动需要重新授予屏幕共享；重新协商分辨率复用同一个 VirtualDisplay。
- 「P2P 已连接」「EC/PXC 握手完成」「已发送 N 帧」含义不同；发送计数只证明成功写入仪表拉流连接，不能证明仪表已解码。最终看仪表画面。
- 如失败，请提供本页日志（尤其配对后/EC 后停在哪一行）、手机 Android 版本及仪表固件版本。无需提供二维码密码。

## 协议来源及修改

使用 GPL-3.0 的 https://github.com/charliecharlieO-o/ridedaemon-lib ，固定提交 d6c9294ee65a94a1632da536d0317de72dedbc40。
其 Go 源码及许可证位于 native/ridedaemon。保留原作者权利及许可证。

本次适配：com.cfmoto 握手包名、真实手机品牌/型号、P2P 地址绑定、视频发送计数、启动取消及端口释放、修正初始化错误被 Close() 结果覆盖、补充模拟仪表测试。没有包含官方 App 私有二进制，也没有发送 OTA/升级命令或更改车辆固件。

## 构建

Go 1.25.3、JDK 17、Android SDK 35 / Build-tools 35.0.0、NDK r27c、Gradle 8.7 / AGP 8.6.1。
设置 ANDROID_HOME、ANDROID_NDK_HOME、JAVA_HOME，运行 `native/build-android.sh` 生成 app/libs/ridedaemon.aar，再运行 `./gradlew :app:assembleRelease :app:lintRelease`。

补丁包包含基于上述原项目提交的完整累计文本补丁、可直接使用的 AAR、全部 native Go 源码和测试。将补丁应用到该提交，拷贝 AAR 到 app/libs/ 后即可构建。签名私钥未附带；自行编译请使用自己的密钥。

## 验证结果

编码器兼容修复及新增 10 项框架模拟测试详见 ENCODERFIX_README.md。

- Go 协议库 23 个测试通过，包括模拟仪表的真实 TCP 反向握手、视频拉取、停止取消及端口释放；已运行 race detector。
- 二维码 URL 17 项、实际 QR 编解码 9 项、AVC 封包 / IDR 检测 14 项通过。
- P2P 扫描 10 项、权限和系统设置流程 11 项通过（Android framework fakes，并非无线电/手机实测）。
- Release APK 构建成功。完整 Lint 仍有 14 项原项目问题；本次新增投屏类没有 Lint 错误。完整报告随补丁包提供。
- 未连接手机及 AE5i 仪表，屏幕采集、实际射频配对及最终解码画面仍待设备验证。
- 原有 `verify_host_contract.py` 仅用于旧版官方桥接 API 的静态分析，独立投屏不使用该桥接。
