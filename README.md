# ZEEHO Speed Hunter

LSPosed 模块，作用于 ZEEHO App（`com.cfmoto`），用于个人设备上的兼容性研究与界面恢复。
**与 ZEEHO / CFMOTO 无任何隶属或授权关系。**


| # | 能力 | 说明 |
|---|---|---|
| 1 | **骑行记录恢复** | 解开被服务端隐藏的骑行统计：骑行分析入口、极速、急加速、急刹、压弯 |
| 2 | **OTA 全链路流量记录** | 只读 hook，用于研究车辆 OTA 链路；不伪造、不改写任何服务端数据 |
| 3 | **OTA 推送控制台** | 走 `cfmoto_P2p_server`（TCP 10086）写包，**推送前强制六项自洽性检查** |
| 4 | **仪表壁纸推送** | 自定义壁纸推仪表（socket_l 10950，SAF 选包 + 选图 1/2）；协议真车实测全绿 |
| 5 | **仪表投屏（独立）** | 扫码 → Wi-Fi Direct 配对 → App 自身抓屏编码推给仪表，**不依赖官方 App / LSPosed** |
| 6 | **车端诊断** | DoIP/UDS + BLE 透传（RS485 / CAN）+ 离线解码；写类操作默认硬禁 |
| 7 | **系统 Wi-Fi 直连 + 网络诊断** | 连接交给系统设置，组主地址靠 TCP 握手探测而非硬编码 |

---

## 骑行记录恢复

| gate | 字段 | 原值 | 改成 | 控制 |
|---|---|---|---|---|
| G1 | `vehicleKinds` | `1` | `2` | 骑行分析入口 + 极速 |
| G2 | `cyclingEventStatisticFlag` | `false` | `true` | 急加速 / 急刹 / 压弯 |

- hook okhttp `Response$Builder#build()`，**不按 URL 过滤**，按「响应体里有没有这两个 key」判定，递归不限层级；
- 显示的数值是服务端真值；
- **不改车型标识**（`vehicleType` 等）—— 冗余，且会让 App 按别的车型发控制指令。

旧方案（点亮被 GONE 的控件）保留为兜底，默认关闭。

## OTA 流量记录（只读）

| 开关 | 默认 | 作用 |
|---|---|---|
| `LOG_HTTP` | 开 | 打印所有 okhttp 请求 |
| `LOG_RESPONSE` | 开 | 响应体捕获，OTA 接口族全打 |
| `LOG_DOWNLOAD` | 开 | 固件包探针：URL + 体积 + Content-Type |
| `LOG_WEBVIEW` | 开 | 抓 WebView 加载的 OTA 说明页地址 |
| `LOG_SERVICE` | 开 | 打印 OTA 服务 Intent extras |

**只读，不改写任何响应** —— 升级包是否存在由服务端决定，OTA 红点接口的 `fileInfoList` 为 `null` 时就不会有下载动作。

## OTA 推送控制台（TCP 10086）

### 流程

```
进入 OTA Update 窗口（10086 随之常开）
  → Wi-Fi Direct 建组，取组主地址
  → SAF 选包（唯一入口，不猜路径）
  → 六项检查 ── 任何一项不过 ⇒ 禁止推送
  → 确认弹窗（摊开包身份 + 六项结论 + 红线）
  → 推裸 GEMINI_PACK.BIN 到 10086
       ① 十进制长度
       ② 文件名        ★ 与①之间、②之间必须有秒级间隔
       ③ 文件体（1MB 分块）
       ④ 3 字节 "OTA"  ★ 与③之间同样必须隔开
  → 仪表跑进度条 → 写完关机 → 上电
```

### 六项检查

车机提示的「升级包校验失败」**只覆盖第 ① 项**，其余几项由 U-Boot 执行，
**失败时仪表毫无信息**（进度条停住、不报错）⇒ 只能推送前在手机上验完。

| # | 检查 | 失败后果 |
|---|---|---|
| ① | 内层 `MD5SUM*` = `md5(pack[0x400:])` | 车机提示校验失败，**根本没开始刷**（最轻） |
| ② | main 脚本 uImage `dataCRC` + `headerCRC` | U-Boot 拒执行 main ⇒ init 照跑擦了分区 ⇒ **只擦不写** |
| ③ | 分区表 MD5（核对识别数量） | 刷到该分区后 `exit -1`，**半刷** |
| ④ | main 脚本内硬编码 MD5（核对识别数量） | 同上，半刷 |
| ⑤ | SPAPP SquashFS superblock | Android 挂载失败 / 可执行文件失 +x |
| ⑥ | GD32_APP S-record 记录校验 | 仪表 MCU 编程拿到垃圾数据 |

**判定只看六项是否全过，与包是否原厂无关** —— 自制包改对了就放行，原厂包改错了照样拦。




## 仪表壁纸推送（socket_l 10950）

独立推送自定义壁纸到仪表，**不依赖官方 App**。

- 实现：`ota/WallpaperSocketPusher.java`（协议见下）；远程驱动
  `am start -n com.github.zeehospeedhunter/.ui.WallpaperActivity --es auto push --es file <zip> --es num 2 [--ez dry true]`。

### 协议

| 方向 | 命令 | 说明 |
|---|---|---|
| 手机→车机 | `0x10000011` versionMessage | 版本协商；车机**会**回 `0x80000011 {"versionCode","vin","code"}` |
| 手机→车机 | `0x10000043` wallpaperStartMessage | `{fileName, wallpaperNum, displayWallpaperSet, md5, fileSize}` |
| 车机→手机 | `0x80000043 {"code":N}` | **N=1 = 条件校验通过**（`checkWallpaperSetRequirements`），随后 `startWallpaperByIndex()` |
| 手机→车机 | `0x10000044` wirtewallpaperData | 数据帧（head[1]=0），1MB/片 |
| 手机→车机 | `0x10000045` wallpaperEndMessage | `{recvWallpaperFileFinishedFlag:1}`（★ 必带） |
| 车机→手机 | `0x80000045 {"code":N}` / `0x10000046 {"code":N}` | 收尾回执 / 校验请求 |
| 手机→车机 | `0x80000046` wallpaperVerifyMessageAck | ★ 必须回，否则车机反复重发 |

车机收到 end 后：`md5_file` 校验 → `unZIPWallPaperFile()` → `signalUseWallpaper(name)` → DASH 写 `persist.sys.wallpapername`。

### 壁纸包格式（官方 App 实推的包解剖所得）

```
wallpaper.zip
├── 1.png  307×460  RGBA   小图 / card   （DASH slot_Wallpaper_update_cardsize）
└── 2.png  1024×600 RGBA   大图 / 背景    （DASH slot_Wallpaper_update_bgsize）
```

- `wallpaperNum` **只能是 1 或 2**（固件 `stoi` + `(v-1U)<2` 校验）= 选哪张图；
- `displayWallpaperSet` 固件要求非空（空则 `displayWallpaperSet null or fileSize == 0, not process this req`），
  固件里无对应字面量，默认 `"1"`，界面可调。

### 选图 → 裁切 → 打包（uCrop）

界面「用图片生成壁纸」支持直接用相册图片生成壁纸包：

1. **选大图** → uCrop 按 `1024×600` 比例裁切 → `2.png`
2. **选小图** → uCrop 按 `307×460` 比例裁切 → `1.png`
3. **打包并推送** → 自动打成 `wallpaper.zip`（`1.png` + `2.png`）→ 直接走上面的推送流程

### 边界

- 车机 10950 是**单逻辑客户端**：官方 App 占用时本工具连不上（先 force-stop 或退出投屏）；
- 仪表不回报应用结果，成功与否以屏幕为准（车机只回 code，不回"已应用"）；
- 不涉及任何 OTA 包，只推壁纸 zip。

## 仪表投屏（独立实现，v3.7 起）

不依赖官方 App，来自社区基于本项目 `a0bb0b9` 的 3.6.x 系列改进。

| 模块 | 文件 | 作用 |
|---|---|---|
| 抓屏编码 | `mirror/ScreenEncoder` | Surface 输入 H.264；编码器能力枚举 + profile/帧率/码率回退 + 换编码器重试 |
| AVC 封包 | `mirror/AvcFrames` | IDR 判定、只发完整帧，避免丢帧后预测链断裂 |
| 前台服务 | `mirror/StandaloneMirrorService` | 持有 MediaProjection 令牌（`foregroundServiceType=mediaProjection`） |
| 协议加载 | `app/libs/ridedaemon.aar` | Go 协议库预编译产物（`native/ridedaemon` 为源码） |
| 二维码 | `ota/MirrorRequest` + `ui/MirrorCaptureActivity` | 相机扫码（ZXing）或粘贴完整 URL，含 P2P 名称/SSID/action 解析 |
| P2P | `ui/P2pAccess` + `ota/CarP2p` | 定位权限预检、BUSY 退避重试、40 秒扫描窗口、poll 刷新列表 |
| 自测 | `mirror/MirrorTestActivity` | 移动条测试画面 + 实时统计，用来区分「导航 App 低刷新」与「投屏链路问题」 |

### 投屏优化要点

- **画质档位**：默认「平衡」15 fps / 800 kbps，另有 20 fps / 1200 kbps 与 10 fps / 500 kbps；
- **不再周期性丢 P 帧**：短 GOP（约 500 ms 一次 IDR），启动、旋转、拥堵恢复时额外请求关键帧；Android 10+ 在 Surface 输入端限帧，避免屏幕高刷让编码器空转；
- **去掉 4 KB / 3 ms 的固定写入等待**，改由 TCP 背压和仪表拉流节奏控制；
- **有界队列（4 帧）**：不能丢掉一张 P 帧后继续发依赖它的帧——队列满就丢弃待发链、等新 IDR；
- **修了会自己断开的缺陷**：旧实现只在发送非空视频帧时设写入截止时间，之后既不清除也不刷新，空闲久了下一次回复沿用过期截止时间必然失败（`i/o timeout`）；现在每个完整拉流请求都刷新截止时间；
- **监听主屏旋转**：转向后清掉待发预测帧等新 IDR，复用同一个 MediaProjection / VirtualDisplay，不重复消耗屏幕授权令牌；
- **性能区每约 2 秒刷新**：编码输出率 / 提交率 / 发送率 / 仪表请求频率 / TCP 写入耗时 / 队列深度 / 累计丢弃 / 编码器名称。
- **保留兼容退路**：关掉「流畅模式」即回到关键帧兼容路径（通常更卡）；另修复了 EC 握手与「上次停止原因」被覆盖的问题。

> 统计是**应用端采样**：发送 FPS 不是仪表解码/显示 FPS，没有仪表端时间戳就无法测真实端到端延迟。
> 目标帧率不是实车性能保证，档位选择仍需按实车观感调整。

### 使用

1. 打开「仪表投屏」→ 扫码（相机权限）或粘贴仪表二维码完整 URL → 解析；
2. Wi-Fi Direct 扫描 → 点设备配对（需系统定位开启）；
3. 保持「流畅模式」开启 → 开始 → 允许**整个屏幕**共享；
4. 卡顿排查：先开「打开流畅度测试画面」，看编码率 vs 发送率 vs 仪表请求频率的差在哪一环。


## 车端诊断（DoIP/UDS + BLE 透传 + RS485 解码）

界面：**顶栏三态状态胶囊 →「一键连接」→ 2 列功能宫格 → 车辆链路切换 → 折叠高级设置 → 写操作红卡 → 日志**。

| 链路 | 物理路径 | 协议 |
|---|---|---|
| RS485 | 手机 → BLE 透传模块 → 车辆 RS485 | 私协议 `43 46 … 45 44` |
| CAN | 手机 → BLE 透传模块 → 车辆 CAN | ISO-TP（ISO 15765-2） |
| WIFI | 手机 → 车机热点 → DoIP 网关 | ISO 13400 + UDS |

能力：车辆信息 / 故障码（SAE J2012 中文码表）/ ECU 全景 / DID 扫描 / 数据流 / RS485 离线解码 /
ABS 排气 / 胎压匹配 / NFC 学车。RS485 与 CAN 共用同一条 BLE 连接，切换不断连。

**红线**：只读随时可跑；写类默认硬禁，需勾选 `allowWrite` 并二次确认；ABS 泵会真实转动 10 秒，需盯住放气螺丝。
不臆造 ECU 地址与 routine ID，由用户填实际抓到的值。



## 系统 Wi-Fi 直连 + 网络诊断

车机地址**并不固定**，组主随模式浮动（以网络诊断显示为准）。三个动作（OTA / 投屏 / 诊断三页共用）：

- **系统 Wi-Fi 直连（主）**：断开当前 Wi-Fi → 拉起系统 WLAN 面板 → 用户连车机热点 → 回页点「网络诊断」；
- **App 内直连（兜底）**：保留 `WifiNetworkSpecifier` / `addNetwork` 路径；
- **网络诊断**：TCP 握手判定组主地址与端口，结果回填推送通道与 DoIP 目标。

诊断只做 TCP 握手判断端口是否监听，**不发任何非本协议帧**、不改网络配置。

---

## 日志

日志写进目标 App 的文件：

```
/sdcard/Android/data/com.cfmoto/files/zeeho_hook.log
```

> ⚠️ **日志可能含个人信息**：会记录接口参数、响应体与固件线索，可能包含车辆识别码、骑行记录、账号字段等。

## 安装

1. 在 XPosed 中安装发布包，作用域限定为 `com.cfmoto`；
2. 启用模块后强制停止并重新打开 ZEEHO；
3. 进入「我的骑行」或「历史轨迹」查看结果。

## 从源码构建

包名 `com.github.zeehospeedhunter`，要求 JDK 17、Android SDK 35。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew assembleRelease
# 产物在 release/
```

> ★ **独立投屏依赖 `app/libs/ridedaemon.aar`**（Go 协议库预编译产物，含 `libgojni.so`）。
> 本仓库 `.gitignore` 里有 `*.aar`，入库要用 `git add -f app/libs/ridedaemon.aar`；
> 若不想带二进制，可用 Go 1.25.3 + NDK r27c 从 `native/ridedaemon` 源码自行编译（`native/build-android.sh`）。
> 不改 `native/` 下 Go 源码时不需要 Go / NDK。
> 此外本机只装了 build-tools **34.0.0**，所以不要照社区文档写死 `buildToolsVersion "35.0.0"`。

## 隐私与安全

- 模块网络权限仅用作投屏 / 推送 OTA，不上传骑行记录、车辆信息、位置或账号数据；
- 不修改 ZEEHO APK，不绕过账号权限，不改变车辆控制逻辑；
- 速度数据只用于界面显示，不能作为仪表或安全决策的替代品；
- **请勿在骑行过程中操作手机或依赖本模块读数**；
- 刷写固件有变砖风险，推送前务必确认六项检查全过，并保证电量充足、非充电、非 Ready；
- LSPosed、Root、加固 App 和第三方模块存在兼容性风险，使用者应自行备份并承担风险。

## 非官方声明与许可证

本项目与 ZEEHO、CFMOTO 及其关联公司没有隶属、授权、赞助或合作关系。相关名称、图标归各自权利人所有。
本项目仅用于个人设备上的兼容性研究和界面恢复，不提供任何官方支持，也不保证持续兼容。
使用者应确保对目标设备具有合法使用权，并自行遵守所在地法律与厂商保修政策。

原创代码及文档以 AGPL-3.0 授权；该许可不适用于 ZEEHO/CFMOTO 的 APK、商标及通过其接口获取的数据内容。
