## 4.7.67 (vc120) — 2026-11-12
- **车模替换（主页车辆图片 / 3D 模型自由切换）**：新增 net.VehicleArtUnlock + 骑行页
  「车模替换」卡片（开关 + 三项编辑对话框）。DEX 定案（v3.0.5 app_full.dex，
  dist/nitro-fw/dex_*.py 反查）：主页车辆视觉全部来自 MyCarHomePageRsp 的
  vehiclePicUrl（2D PNG）/ vehiclePic3dUrl（3D glb，CFFilamentView#loadUrl 按 URL
  加载）/ vehicleScalePicUrl（同 PNG + OSS resize）；App 内**无 carColor 字段**——
  颜色随 glb 内嵌材质（服务端按颜色下发不同 glb）；APK assets/3d 只有 Filament 材质、
  无内置车模 ⇒ 换 URL 即换车。开启后在 vehicleHomePageV2 / vehicle/list 响应全树
  替换这三个键（scalePic 自动保留原 OSS 参数），可选 appThemeRgb 跟随主题色。
  配置三键与账号键同法继承式写入（局部广播不冲掉 URL）。
  iOS 对应 ZeehoVehicleModel.js（脚本顶部两个变量手动填，conf 默认关）。
  纯显示；不碰 vehicleType 等车辆身份字段。
## 4.7.66 (vc119) — 2026-11-12
- **启动首页跟随「我的APP首页」**：引导登录后冷启动固定落「极客」的根因是落地页读
  `UserInfo#homepage`，而伪造的 `UserInfo` 只能给空值。真实值不在 SP（爱加密）/MMKV/
  加密数据库中，因此改为**从云端请求自学习**：保存设置时 App 会
  `PUT /v1.0/mine/cfmotoservermine/baseInfo/others/{N}`，`N` = 底部页签序号 + 1
  （1=极车 2=服务 3=极客 4=极市 5=我的，实机双向验证），在既有的
  `Request$Builder#build()` 钩子里取路径末段记住，冷启动回填伪造对象。
- **配置抗冲刷**：`ConfigReceiver` 对 `ACTION_CONFIG` 是全量替换，局部广播（压弯标定、
  单开关）会把新键抹掉；`account_homepage` 改为与账号三键同法的继承式写入。
- **清理排查期探针**：移除 SP/MMKV 读写窗口打点、`setMyhomeText` 打点、
  `CallServerInterceptor` 请求/响应体嗅探、`UserInfo` 字段转储、`home_diag` 开关，
  以及随之变成死代码的 `hookAllMethodsIfExists`。学习逻辑收敛为「路径末段 + 一行日志」。

## 4.7.49–4.7.65 — 2026-11-12
- 账号页新增「清除 App 登录状态」（清官方 App 自身 `cfmoto` SP + 自重启）；
  「保存并登录」后自动拉起极核 App。
- 修复「我的 → 设置」闪退：`SettingsActivity.setMyhomeText` 对伪造 `UserInfo` 的 null
  String 字段调 `hashCode()`，改为构造时把全部 String 字段兜底成 `""`。
- 首页跟随设置的多轮调试版本（4.7.51–4.7.65）：先走请求体/响应嗅探，最终定案为路径学习。

## 4.7.48 (vc101) — 2026-11-12
- **账号会话**：`AccountSession` 接管官方 `AppContext` 登录态与用户对象读取；以外置 token
  调用 `baseInfo_v2`，核对服务端用户 ID 后提供真实 `UserInfo`，使“我的”资料与注入账号一致，
  并覆盖未登录启动时的原生会话判定。API 失败或 ID 不符不混用用户资料。
- **网络响应**：跳过 Retrofit `NoContentResponseBody` 转换哨兵，避免尝试 peek 已转换对象时
  抛出 raw response body 异常。
- **隐私**：账号 session 诊断只在诊断开关启用时记录，且仅输出值类型/长度与 key，不记录值。

## 4.7.43 (vc96) — 2026-11-12
- **修复：引导登录「显示成功但官方 App 没登录」**：根因有二。
  ① 账号页旧实现写 `Keys.PREFS` 独立文件，而首页/压弯标定页/UiApp 反向同步都用
  `getDefaultSharedPreferences()` —— 两套文件；`AccountActivity` 保存后广播走默认
  prefs，hook 侧 `ConfigProvider` 反向同步却读默认 prefs 的空值，把凭证冲掉。
  ② `ConfigReceiver` 对 `ACTION_CONFIG` 做**全量替换** `liveConfig`，而压弯标定保存、
  调试开关等局部广播不带账号键，旧实现无条件 `put(...,false)` 把 `account_token/
  userid` 冲成空、`account_login` 冲成 false —— 设备实测落盘 `account_login=false`、
  `account_token=""`、`account inject` 命中 **0 次**。
  **修复**：账号页与 `ConfigProvider` 统一回默认 prefs；账号三键改为「extra 存在才覆盖、
  否则继承当前值」(`putAccountBool`/`putAccountString`)；「保存并登录」按钮履行字面语义
  （直接置 `account_login=true` 并推送），不再依赖默认关的开关；开关手动拨动即落盘+重推。
- **补强：hook 侧真实状态回执**：官方 App 靠 `IS_LOGIN`(boolean) 判断登录闸门，旧实现只
  hook 了 `getString`，冷启动仍判未登录停在登录页，根本不发出待注入请求。新增
  `installBoolPrefs` 精确白名单(`is_login`)兜底；PING→PONG 回执附带 hook 进程实际生效的
  `account_ready`(on&&token&&userid)/tokenLen/uidLen/injectCount，UI「模块生效状态」
  只认这个，不再用「本进程保存成功 / AccountApi 查询成功」冒充登录成功。
- **界面：按「压弯标定」二级页范式重做** `activity_account.xml`：圆形返回按钮 + 右上角
  文字按钮 + PageTitle + 凭证卡片（Token/账号ID 输入 + 开关 + 保存并登录/清除凭证）+
  签到卡片（刷新状态/立即签到）+ 原理说明卡；`AccountActivity` 接 `BackGestureCompat`/
  `EdgeToEdge`/`btn_back`，详情区固定高度可滚动（沿用压弯标定踩过的坑）。

## 4.7.42 (vc95) — 2026-11-12
- **账号引导登录（手动登录）**:仿设备上第三方 App「几何面板」v1.8.40 的登录参数
  与任务交互,新增 `ui/AccountActivity`（骑行记录页「账号引导登录」入口）——
  录入从其他客户端取得的 `token + 账号 ID`,保存到模块 prefs 并经既有
  `ConfigSender` 广播 + `RemoteSettings` 落盘,供 hook 进程即时/冷启动恢复。
  `net/AccountLogin` hook `okhttp3.Request$Builder#build()`,对 `zeehoev.com`
  请求注入 `Authorization: Bearer <token>` 并把 `userId/userid/user_id/uid`
  参数换成录入账号 ID;改了 URL 就复用请求已有的 `cfmoto-x-param` 重算
  `cfmoto-x-sign`(签名串含 sortedQuery);另对 `SharedPreferencesImpl.getString` /
  MMKV 读取做白名单兜底,让官方 App 本地也认为已登录。**opt-in 默认关**,
  多台设备填同一份凭证即共同登录同一账号;关开关或清凭证即恢复原登录态。
- **签到 + 积分任务**:`net/AccountApi`(仅 `HttpURLConnection` + `org.json` +
  `MessageDigest`,无新依赖)复刻几何面板接口与签名
  (`MD5(SHA-1(sortedQuery + body + cfmoto-x-param + secret))`):
  `signin/count` / `integral/getByUserId` / `integral/totalIntegral` /
  `qbTzInfoNewV2` / `H5/signin/info` 只读查询 + `H5/signin` 签到。
  UI「刷新状态」展示签到/积分/任务状态,「立即签到」为唯一写操作且需二次确认,
  全部请求关联当前录入账号,服务端原始应答原样展示、不伪造成功。
- **安全**:`ConfigProvider`(exported=true)排除 `account_token`,避免登录凭据
  被任意应用 query 到;模块自身日志不打 token 明文(账号 ID 打码)。
  `NetDump` 的「全量请求头落盘」是 OTA 工具重放所需的既有行为,保持不变
  (含 `Authorization`,仍只写本机文件)。
- **iOS 同步**:`SpeedHunter-iOS/scripts/ZeehoAccountToken.js` 自动从
  Shadowrocket 请求 URL/头提取 `token + userid` 写入 `$persistentStore`,
  替代原先手工翻日志找 URL 字段;conf 两文件加脚本条目 +
  `[MITM] hostname` 追加 `h5.zeehoev.com`。详见 `docs/features/account-login.md`。
- **边界**:签名/接口来自几何面板 smali 静态反汇编,**未做实车应答验证**;
  若返回鉴权/签名错误,UI 原样展示应答便于按日志调整。凭证需车主自行从
  其他合法客户端取得。

## 4.7.41 (vc94) — 2026-11-12
- **ABS 开关入口(Xposed 勾出,待实车验证)**:AE5i Pro 云端配置表过滤掉 ABS 行,
  复刻 `NitroUnlock` v4.7.9 已实车验证的「克隆注入」路径新增 `net/AbsUnlock`——
  设置列表(functionCode+isSupportSetting)注入 `ANTI_LOCK_BRAKE_SYSTEM`、
  `DRIVING_MODE_SETTING` 分类 subFunctions 克隆注入(文案取官方 `abs` / `abs_tip` /
  `anti_lock_brake_system`=ABS开关)、带 `etcsTractionControl` 的 vehicleSetInfo.data
  补 `antiLockBrakeSystem=1`(AE7 实采稳态)。开关 `abs_ui`、opt-in 默认关,
  关掉即回退干净。骑行记录页新增「ABS 开关入口」卡片。
- **红线**:克隆条目清空 `blueToothServiceCodeList` / `functionConfig`(AE5i 的 ABS
  写入 serviceCode 未取证,保留兄弟条目真实码会误写到别的功能);不改
  vehicleKind(s)/deviceType/身份字段;不构造任何车辆写帧。**拨动 App 内该开关的
  写入生效性未验证,不应依赖**——本功能定位是「勾出入口」。
- 取证(详见 `docs/features/abs-switch.md`):App res 已有 ABS 全套文案 +
  脱壳 dex 有 `ANTI_LOCK_BRAKE_SYSTEM`/`CHANGE_ABS_SWITCH`/`antiLockBrakeSystem`;
  AE5i 实采 `hook-2251.log` 97 个 functionCode 无该项、items/itemsV2 均无 ABS 条目,
  `DRIVING_MODE_SETTING` subFunctions 仅动能回收/TCS/最高车速/电子制动。
  固件侧 `libmcucommodule.so` 有 `mcucom_set_ABS(int)`/`carSettings.ABS`/
  `MCUCOM_SIGNAL_ABS_MODE`,属性 `0x21409308`;`libnipcmodule.so` 记
  `m_vehsetitem2-1: ABS/MTC/ESS/DriMode`;TBOX 配置表有 `tbox_config_list.set_abs`;
  配置字 `cfg[6] bits[6:5]`=「ABS 可设置」、`cfg[13] bit5`=ABS 指示灯(docs/70 §2)。
- 构建 `speedhunter-v4.7.41.apk`(20MB,签名 4b3fc29b…),资源 `sw_abs`/`tv_abs_hint`
  已入包,`adb install -r` 成功、启动 0 crash。**显示未实车验证**:需在模块 UI 拨开关后
  进官方 App → 车辆设置 → 骑行偏好设置查看,并核对 HookLog `abs unlock: inject=1`。

## 4.7.40 (vc93) — 2026-11-10
- **写里程自校验(两条路线均加)**:DID 0309 可读(22 03 09 → 62 03 09 + 3B 大端 0.1km,
  固件 DID 表"读写"确证)→ 直写路线=写前读+写后回读;EOL 路线=写后回读;
  回读值打日志("写前/写后里程读取"),无应答时提示观察仪表
- 测试建议(协议总汇实车清单第 3 条):先写 ±1km 小偏差 → 看仪表 → 12V 断电重启
  → 确认不回跳;EOL 0x4F(认证写)为产线专用通道(clamp 999999.0km),优先使用

## 4.7.39 (vc92) — 2026-11-10
- **按《AE5_全通讯协议总汇》(GD32 固件逆向)修正诊断语义**:
  ① 读 DTC:F4 51 = 读 46B 诊断状态【汇】;应答结构化解读
  [6B 状态头][20B DTC_0(NVM18)][10B DTC_1(NVM19)][10B DTC_2(NVM20)]
  (6+20+10+10=46 恰与清码 33/36/38 三块对应,拆分为【推】;自动剥离响应码 D1 前缀)
  ② 学卡:F4 39 学习开关 **0=系统卡 / 1=用户卡**【汇】→ 新增卡类型选择
  (系统卡=抓取值 / 用户卡),ON/OFF 同类型贯穿
  ③ 删卡:F4 3A = NVM 清零 **0/1/2 三类型**【汇】→ 单选列表(默认 0=官方抓取值)
  ④ 确认既有实现与文档一致:清码 33/36/38=写 DTC NVM 三块、EOL 4F mode0=写里程(LE,
  clamp 999999km)、DID 0309 大端、F193=HW_VER、auth proof=challenge×2023(抓取两对
  常量均复核通过)、UDS L1 seed-key 三向量(E33A… 旧黄金与文档公式一致)
- 说明:此前误引的《AE5_OTA固件解包…》为开机画面文档,与诊断无关;本版起以协议总汇
  为语义权威、zscan-capture 为字节/时序权威

## 4.7.38 (vc91) — 2026-11-10
- **新功能:读故障码(读 DTC)入口恢复**:官方删卡会话同构(主从切换+EOL 授权+60 07D0
  超时提升)后发 F4 51 00,收集 3s 内节点 0xFC 全部应答(原始字节,官方语义未解码),
  完成后发 0x71(04) 退出;执行帧 51 00 证据=旧黄金抓取+社区文档(官方三会话无 DTC 读)
- **🔴 修复:运行日志窗口不能上下滚动**(两项根因):
  ① 外层 NestedScrollView 拦截竖向手势 → 日志 ScrollView 手势期
  requestDisallowInterceptTouchEvent(true)
  ② 每条新日志强制置底打断上翻 → 智能置底:用户上翻暂停自动滚动,
  滚回底部自动恢复(OnScrollChangeListener 判定 atBottom)
- **学卡执行段对齐官方模式**:授权后先发 60(07D0) 超时提升再发 39 00 OFF
  (官方删卡执行段同构:授权→07D0→执行帧)
- 读版本按钮独立(btn_diag_version),诊断操作行=读版本/读 DTC/清 DTC 三键
- 至此诊断六功能全部有入口:学卡/删卡/读版本/读 DTC/清 DTC/里程双路线/ABS 前轮排气

## 4.7.37 (vc90) — 2026-11-10
- **🔴 主从切换按官方逐字节重实现(xposed-capture 三会话取证)**:
  ① 根因——旧实现 0x89 payload 用了读版本会话变体(2B22100C05…,含疑似时间戳),
  且节奏(150/1100×4)与官方(158/112/1105/1107)不符,第 3 帧晚 1s;
  删卡会话实际用无时间戳变体 **15 00×5 20 00×57**(64B,可安全重放)
  ② 官方切换 = 0x89×5 + **0x71(byte6=0x20)**(非 71×3+80);退出 = 0x71(byte6=0x04);
  唤醒判据 = 0x79 应答 / 节点 0x17 广播(仪表重启后发 modelid/SSID)
  ③ 每帧 CF 写前先发 69 0101(官方同款);操作执行段前重发 60 配置超时 0226→07D0
  ④ 操作期间挂起 FFA 心跳(官方主从 11s 窗口内零 L2)
  ⑤ 前导补齐第 6 帧 69 0101(60×3+0101+02002C00190019+0101)
- **新功能:ABS 前轮排气(官方 00:22 会话逐字节,实车待验证)**:
  60×4 双通道配置(96/C8) → 82 01/82 00 → 10 01+UDS 10 03 → 安全访问
  **27 01 种子 → L2 fff9 → VCI fff7 回 key → 27 02**(key 由 VCI 计算,App 不内置算法)
  → 60 轮泵阀循环(10 0B 2F E0DB 03 / 21 6464|0000 / 30 08 14,周期 1.1s)
  → 2F E0DA 03 01 停泵 → 清码 14FFFFFF → 默认会话 → 11 01 ECU 复位(分步确认)
- 学卡/清DTC/写里程EOL/读版本 统一走官方主从切换框架;读版本增加 0x80 VIN 查询
- 自检 +1(主从帧/fff7 向量)= 14 项;「读 DTC」按钮更名「读版本」

## 4.7.12 (vc65) — 2026-11-07
- **[net] 全量请求头捕获**：[auth] 升级为 [req-headers]（Authorization + Cfmoto-X-Sign 等全部头，
  每接口一次），供 tools/ota 重放签名端点（AE7 仪表 V1.4.0 包获取）。

## 4.7.11 (vc64) — 2026-11-07
- **[net] Authorization 捕获**：NetDump 新增 maybeCaptureAuth（NetPatch.readRequest 反查），
  每个新 token 首次完整记入 speedhunter_netdump.log（本地文件；用于 tools/ota 拉官方
  固件包，docs/50/65；AE7 固件获取 = 氮气原生实现分析的前置）。
- 其余同 4.7.10。

# 版本记录

| 版本 | 内容 |
|---|---|
| **4.7.10**（vc 63） | **MQTT 下行报文全量落盘（云端配置下发取证）**：车主判定诊断口写入会被 TBOX 云端覆盖，氮气配置须走**云端下发**（自定义键同款路径）。MqttHooks 新增 `hookMessageArrived`：hook Paho `MqttConnection/CommsCallback/MqttAndroidClient` 的 `messageArrived`，把 `repub/to/property/change/{pk}/{device}`（★下行指令通道）等所有下行报文的 topic+payload 全量落盘（单条截 2048 字符）——车辆上电时 TBOX 从云端拉取的配置字下发（vehicleTypeInfo / setItem provision）若经此通道，即可拿到云端配置的**原始格式**，作为「以自定义键同款路径下发氮气配置」的前提证据。红线不变：只读落盘，不发布、不订阅新 topic。验收：装本版 → App 选中 AE5i → 车辆 12V 断电重启 → `grep "mqtt MSG" speedhunter_hook.log` 分析下行报文 |
| **4.7.26**（vc 79） | **🔴 P0 修复:wrapRs485ForVci 双重 cmd 头 bug + 日志滚动**:① 实车日志定位——所有功能失效根因 = `wrapRs485ForVci` 返回值含 seq 占位(0)+cmd(0x68),而 `VciSppLink.send()` 又追加 seq+cmd ⇒ VCI 收到 `…0e 68 00 68 01 00 96…`(多出 `00 68` 两个字节),RS485 写帧被 VCI 静默丢弃(0xFC 全程零应答;车辆节点周期数据正常 ⇒ 链路本身通) ② 修复:wrap 只返回纯 data(`01 00 96`+CF),seq/cmd 由 `buildFrame` 统一加 ③ 日志区不能滚动修复:TextView 自动 scrollTo 底部 + 60KB 截断防无限增长;自检 12 的 wrap 断言同步 |
| **4.7.25**（vc 78） | **诊断页视觉对齐其他 tab(车主要求)**:① 标题改 `PageTitle`/`ParamHelp` 样式(30sp 大标题,与其他页一致) ② 顶栏右上角 `TopIconButton`(分享 ic_share + 清空 ic_delete_log,与 OTA 页同款结构) ③ 日志区改 OTA 页同款卡片(`bg_card` 容器 + ic_analytics 图标 + 「运行日志」标题 + 右侧「复制」TextButton + `bg_log_pane` 等宽面板 260dp 固定高) ④ 底部大按钮(分享/清空日志)移除,功能并入顶栏;新增剪贴板复制 |
| **4.7.24**（vc 77） | **UI 三项精简(车主要求)**:① 删除氮气加速入口卡下「依据(AE7 Pro 实采)」整段说明(含分隔线) ② 诊断页标题结构与其他 tab 统一(大标题+副标题描述;页面主标题「车辆诊断」,内部小节改「蓝牙模块连接」「里程校正」) ③ 按钮按实际功能命名:写DID→「直写里程」、写EOL→「认证写里程」 |
| **4.7.23**（vc 76） | **诊断页精简(车主要求)**:① 协议自检折叠为一行状态(`▸ 协议自检 12/12 全部通过(点击展开)`,点击展开/收起完整报告;默认收起) ② 删除「查看已配对蓝牙设备」区(配对在系统设置做) ③ 删除「已内置协议能力/待接入」静态清单(功能按钮就在眼前) ④ **VCI 实车测试区提到页顶**(标题→自检行→VCI 区,打开即用)。学卡按钮更名「学卡」+「学卡·退出」 |
| **4.7.22**（vc 75） | **主从切换确认统一到所有操作(车主定案)**:新增 `runWithMasterSlaveConfirm` 通用框架——主从切换(基础前导+6A1031,仪表重启)→**弹窗等用户确认**→执行操作。学卡/删卡/读 DTC/清 DTC/写EOL 全部走此框架;写DID 保持无切换直写(0x14 服务免切换不重启仪表)。前导拆分:基础(60×3+69×3)与主从切换(+6A)分离 |
| **4.7.21**（vc 74） | **学卡改官方三阶段流程(车主描述对齐)**:① 主从切换(前导+6A1031)→ 仪表重启 → **弹窗等用户确认**(官方:主从切换成功→是) ② 学习开关 ON(39 00)→ 仪表学卡窗口 → **弹窗等刷卡确认** ③ 授权写入(EOL 认证×2)+ 退出(39 00)。删卡保持一键序列(官方无中途确认)。按钮重命名:学卡(官方流程)/学卡·手动退出 |
| **4.7.20**（vc 73） | **「保持连接」开关(VCI 状态机修复配套)**:05:03 实测暴露——用户点「学卡·开」后切页,自动释放(onPageHidden)5s 后断线打断长操作。新增 CheckBox「保持连接」:勾选时离页/切后台不释放(日志明示);默认不勾(单连接互斥保持)。另:VCI 应用层卡死(RFCOMM 通但 L2 沉默,FFA/FEF2 均无应答,蓝牙重置无效)须**断电重启 VCI**恢复 |
| **4.7.19**（vc 72） | **原厂 App SN 报错根因修复 + 主从切换入前导**:① 「没有获取到sn」根因=logcat 实证 **ForegroundServiceStartNotAllowedException**(force-stop 后冷启,VciService 前台服务被 Android 拒启→崩→getVciSN 空→LoginActivity 抛错;第二次启动恢复)。修复:SpeedHunter 抢占改 **am kill 优先**(温和杀,无 force-stop 惩罚),仅前台杀不掉时才 force-stop(并警告原厂 App 或需开两次) ② smali 定位错误链:LoginActivity.lambda$login$6→getVciSN()空→lambda$login$7 抛 not_sn ③ **主从切换(6A 1031)加入操作前导**——官方每任务周期(写卡/删卡等)先主从切换一次(台架 05:51:15 实证 60×3+69×2+6A1031→「主从切换成功」),现 sendPreamble=60×3+69×3+6A1031 |
| **4.7.18**（vc 71） | **VCI 独占治理(用户实测三态修复)**:用户反馈「开原厂 App 报未找到 VCI SN;杀掉后 SpeedHunter 也连不上」⇒ 根因=我方后台心跳霸占 VCI(原厂连不上)+ 各方僵尸连接卡死 VCI。① **离开诊断页(onPageHidden)/应用切后台(onStop)自动断开释放 VCI**——心跳只在页面活跃期持有 ② 连接失败自动兜底:**root 重启蓝牙撕断 ACL(VCI 端僵尸清理)后重试一次**(btReset,实测有效手法固化) ③ 断开带日志。使用约定:SpeedHunter 与原厂 App 互斥使用,切换前先断开/离页 |
| **4.7.17**（vc 70） | **连接闭环:官方握手全通 + FFA 心跳**:① v4.7.16 实测——BT 重启清掉僵尸 RFCOMM(被强停的原厂 App 遗留,VCI 端被占)后连接成功,官方握手全链应答(FFA×2→FEF2→FFEF 设备信息 SN×2→seq1-3,与 12:38 实拍逐帧一致),L3 会话建立通过 ② **22s 后被 VCI 掐线 → 补 FFA 心跳定时器(每 10s,官方节奏)** ③ 根因链定案:原厂 App 前台独占/僵尸 socket 占 VCI ⇒ 连接前强停原厂 App+必要时 BT 重启。实测日志 04:45:54 会话 |
| **4.7.16**（vc 69） | **连接修复:官方时序复刻(2026-11-08 12:38-39 实时抓取)**:用户实测连接失败,root 抓原厂 App 实连全过程定案两根因——① **FEF2 必须紧跟 FFA 应答(官方 +2ms;旧实现 sleep 700ms → VCI ~400ms 掐线)** ② 原厂 App 前台独占 VCI 会反抢(00:58:53 连上 400ms 被掐实证)。修复:① `connectAndHandshake` 重写为官方逐帧时序:FFA(c854)→应答即发 FEF2→1.1s→FFA×5(c15d/c559/seq1-3,40ms 间隔)→AT 串窗口;**FFB2 不再发**(本轮实抓无此帧,VCI 已许可) ② 连接前 root 强停原厂 App(`su -c am force-stop com.obdstar.x300dp`) ③ 会话前导对齐无车版本读取实拍:60×3+69×3(无 6A;6A 另列 triggerScan)且**每次功能操作前自动重发**(官方行为) ④ 清码/里程序列统一走带前导通道。日志证据:`btsnoop_official_hs/novehicle.log` |
| **4.7.15**（vc 68） | **诊断页布局修复 + 官方握手工序**：① 布局对齐其他 tab:根布局挂 `EdgeToEdge.applyTop`(状态栏 inset,此前标题顶到状态栏底下) ② `VciSppLink.connectAndHandshake()` 复刻 ZSCAN 连接工序(实抓逆向):SPP → **FFFA 状态查询**→收到 aa55 即完成(最小路径,已许可 VCI;会话2 重连实证免 FEF2/FFB2);无应答回退**完整链 FEF2 鉴权(269B 常量)+FFB2 许可(86B 常量)+FFFA 验证**;随后自动发 **L3 会话建立**(60×3+69×2+6A1031,官方进诊断时的序列) ③ L2(aa55)应答观测日志(L2RX 行) ④ 握手模式/结果实时显示在连接状态行。所有握手常量逐字节取自 2026-11-06 台架实抓 |
| **4.7.14**（vc 67） | **诊断页 VCI 实车测试台 + 日志导出**：① `DiagFragment` 新增「VCI 实车测试」区——MAC 输入(预填 DC:0D:30:81:F1:E9)/连接/断开;功能按钮:学卡·开(0x39 开,贴卡后看 0xFC 状态)/学卡·完成(EOL 认证×2+0x39 关)/删卡(开+认证+0x3A,确认弹窗)/读 DTC(认证+0x51)/清 DTC(认证+33/36/38 写零,确认弹窗)/里程输入+写DID(14 06 2E 03 09 大端)/写EOL(F4 06 4F 00 小端,确认弹窗) ② 实时日志区(mono,全部 TX CF/RX 帧/0xFC 状态解码/错误) ③ `DiagLogger`:全量落盘 filesDir+外部 app 目录(`speedhunter_diag.log`,adb pull 路径显示在 UI)+ 分享按钮(ACTION_SEND 全文)+ 会话头 ④ 发送一律后台线程(帧间 180ms);写操作全部 AlertDialog 确认。**边界**:ABS 无按钮(命令未定位);L2 握手未实现(直发 L3,首测验证);0x51/33/36/38 帧长推定,首测小步 |
| **4.7.13**（vc 66） | **官方 VCI 适配 + 五项诊断功能协议定稿(EOL 修正版)**：接入社区固件逆向文档(instruction/AE5_全通讯协议总汇.md)后重大修正与新增。① **EOL 认证正名**：v4.7.7 的「NFC 16B 厂钥」实为 **EOL 认证两帧**(内部=挑战+挑战×2023 u32LE 回绕,双向量数学复核通过;4 会话恒同⇒可重放;成功应答 FC 02 82 0080)——NFC 学卡/删卡其实是 EOL 子命令 **0x39(学习开关 0=系统卡/1=用户卡)/0x3A(NVM 清零=删卡,0/1/2 三类型)** ② **里程双路线**(社区 3.2.2):RS485 DID 直写 `14 06 2E 03 09 <3B 大端 0.1km>`(推断免认证)+EOL `F4 06 4F 00 <4B 小端>`(需认证,clamp 999999.0) ③ **DTC**:读=EOL 0x51(46B 诊断状态)/清=EOL 33/36/38 写零 DTC NVM 三块(20/10/10B)【帧长模式推定,待实测】 ④ **VCI L3 crc 定标 = XOR(全帧)**(7/7 干净帧)——`VciLink.buildFrame` 组帧(56 00+len LE+seq+cmd+data+XOR),`VciSppLink`(SPP RFCOMM 00001101)连接/收发/CF 提取回调,Manifest 补蓝牙权限 ⑤ ABS 开关/排气仍未实现(CAN DASH_MMI_AbsFctSet@0x402 字节布局待反汇编,显式抛异常) ⑥ DiagSelfTest 10→12 项全绿(EOL 数学/NFC 帧/双里程/L3 组帧对照实抓/wrap+DTC)。**边界**:传输后端未实车;EOL 0x51 读与 33/36/38 清码的帧数据长度按 39/3A 模式推定,首次上车按小步验证;ABS 待定位。证据:台架实抓+社区文档+`docs/features/diagnostics.md` §1.7(已修正)。注:4.7.10–4.7.12 为并行 net 捕获线,本条目版本顺延为 4.7.13 |
| **4.7.9**（vc 62） | **氮气加速入口 v4：克隆注入 —— 实车验证通过（2026-11-07 03:41）**：v4.7.8（kinds→3+补 nitro 字段）实测 AE5i 仍无行；实机扫描另证 **AE7 Pro 本车在 v3.0.5 也不渲染氮气**（设置首页/骑行参数页/仪表页全扫；云端 newest=3.0.3，3.0.5 已最新）⇒ 渲染器按设置**列表**建行，而两车 vehicleSet/items、itemsV2(vehicleTypeDetailFunctionVOList)、vehicleSettingList 均无 NITRO。v4：① 设置列表（元素带 functionCode+isSupportSetting）缺 NITRO → **深拷贝首元素**改写注入（blueToothServiceCodeList/functionConfig 清空防误写、isSupport/isSupportSetting=true）② DRIVING_MODE_SETTING 分类 subFunctions 缺 NITRO → 克隆注入（functionName=氮气加速、describe=nitro_tip 原文）③ kinds→3、补 nitro:0 保留。**实车**：AE5i Pro 骑行偏好设置页出现「氮气加速」行（图标+标题+完整 tip+右侧开关，默认关），动能回收/eTCS/电子制动完好；生效通路=vehicleSet/items 注入（日志 inject=1）；关开关行消失（回退干净）。**要点**：开关必须拨模块 UI 里的「氮气加速入口」（纯广播会被冷启动反向同步覆盖）；勿拨 App 内氮气开关（写入不生效） |
| **4.7.8**（vc 61） | **氮气加速入口 v3：按 AE7 Pro 实采数据精确 hook**（AE7 车主账号已上车，netdump 双车差分定案）：AE7 Pro（VIN LCETHGD13S6002391）与 AE5i Pro（358122500143678）对比实测——① `vehicleHomePageV2.vehicleKinds`：AE7=**3** / AE5i=1（G1 已证 1→2 开骑行分析，3=AE7 档）② `vehicleSetInfo.vehicleKind`：AE7=**3** ③ `vehicleSetInfo.data.nitro`：AE7 **字段存在**（0=关）——氮气开关状态字段 ④ AE7 的 items(53)/itemsV2(52 项 vehicleTypeDetailFunctionVOList)/vehicleSettingList 里**均无 NITRO 条目** ⇒ 氮气行不走设置列表，v4.7.4/4.7.5 列表注入路线据此作废。**实现**：重引 `net/NitroUnlock`（v3，opt-in 默认关，`KEY_NITRO_UI`）——① 任意对象 `vehicleKinds`/`vehicleKind` int≠3 → 3（G1 先置 2、本类随后覆盖为 3，关掉即回 G1 的 2）② 带 `vehicleKind` 的对象（=vehicleSetInfo.data）缺 `nitro` 键 → 补 `"nitro":0`（保持关的诚实状态）；身份字段（vehicleType 系）/deviceType 一律不碰。NetPatch 集成：nitro 与 G1/G2/回填共树一次重建，patched 行带 `nitro{kinds,state}`；UI 卡片「氮气加速入口」回归（依据+边界说明）。**边界**：仅显示实验，AE5 控制器无此功能，勿拨 App 内开关 |
| **4.7.7**（vc 60） | **六项诊断功能接入预备（协议层落地,2026-11-06 台架+逆向全链路）**：为「学卡(原厂流程)/删卡/校正里程/读故障码/消故障码/ABS 开关」准备协议基座。① 新增 `diag/VciLink`：官方 ZSCAN VCI 私有协议 L3 数据层(56/ab 帧,len u16 LE 含 crc;cmd 68=RS485 写/69=轮询/60=配置/6A=触发),流式解析+CF/ED 提取,全部向量取自台架实抓 ② 新增 `diag/ZeehoRs485Ops`:六项功能的帧序列定义——学卡 4 帧(进模式 `F4 02 3900`+厂钥块1/2+退模式)、删卡(`F4 02 3A00`)、**NFC 16B 厂钥 = E33A2D47D5576C76 + AFC04B0FE9A69FDF**(Diag.so rodata 硬编码,V33.21/V33.23 同串,逐字节与实抓一致)、诊断会话建立组(60×3+69×2+6A1031,两种模式 0x000F/0x0001)、CAN 线里程(`2E 0309`)与 DTC 读(`19 02 09`)清(`14 FF FF FF`);RS485 里程/清码、ABS 命令显式 `UnsupportedOperationException`(证据未齐不盲发,红线) ③ `DiagSelfTest` 5→10 项:新增学卡/删卡帧向量逐字节断言、流程序列完整性、VciLink L3 解析、cmd68→CF/ED 提取(厂钥块还原)、里程 CAN 帧 ④ 校验和算法定标:CF/ED `sum(msgId+len+data)&0xFF` 实抓 231/231 全命中(既有 `Rs485` 实现一致,无需改)。**边界**:全部为离线协议常量+自检,未接传输后端/未发真实车辆;RS485 清码需 MCU 台架、ABS 与 RS485 里程命令待逆向补齐后开放。证据:`obd-diag/采集/20261106_zscan_s1/`+`docs/features/diagnostics.md` §1.6/§1.7 |
| **4.7.6**（vc 59） | **撤回氮气盲改探测，保留全量取证（AE7 账号直采路线）**：车主定案 —— AE7 与 AE5i 同属 gemini 硬件平台、固件设置项互通，直接登录带 AE7 车主权限的账号捕获 AE7 设置表单即可，无需盲猜 gate。① **删除 `net/NitroUnlock`**（v4.7.4 设置列表 flip/inject、v4.7.5 nitro 键位盲翻全部撤回；`KEY_NITRO_UI`、UI 卡片「氮气加速入口（实验）」、ConfigSender/RemoteSettings 接线同步移除）② **保留并改名 `net/NetDump`**：被动全量取证（常开无开关）——每个接口（去 query）第一份 JSON 响应原文 ≤256KB 落盘 `speedhunter_netdump.log`（8MB 轮转），即 AE7 直采工具；用途与读法写在类注释与 `docs/features/nitro-ui.md` ③ `patch failed` 带 URL 的改进保留。**行为**：对官方 App 的响应改写回到 v4.7.3 语义（仅 G1/G2/回填），新增的只有只读取证落盘 |
| **4.7.5**（vc 58） | **氮气加速入口 v2：nitro 键位盲翻 + 全量取证**（v4.7.4 实测未出入口，判读另有类似 G1/G2 的车型能力位门控）：① `NitroUnlock` 新增**名字驱动盲翻**——任意响应里键名含 `nitro`（忽略大小写）的 Boolean false→true、Integer 0→1（字符串不动防改坏文案），覆盖 nitroFlag/isNitro/supportNitro 等任意命名，与 G1 vehicleKinds/G2 cyclingEventStatisticFlag 同族；设置列表 flip/inject 保留 ② **dump 全量化**：每个接口（去 query）第一份 JSON 响应原文（≤256KB）落盘 `speedhunter_netdump.log`（上限 2→8MB）——盲翻未命中也能凭此文件定位真实 gate ③ 设置接口家族实测定位：`vehicleSet/vehicleSetInfo/<VIN>`（390 次/日，MotoSettingRsp 来源）、`vehicleSet/items|itemsV2|propertyOne/one`（hook 日志 URL 统计） ④ 日志去重：无命中按 URL 只记一次（v4.7.4 每响应一行被 dbpoint/send 刷屏）；`patch failed` 带 URL（识别 peekBody 失败是否命中设置接口）。**待测**：实车后回传 netdump + nitro 日志行 |
| **4.7.4**（vc 57） | **氮气加速入口（纯 UI 实验）**：官方 App v3.0.5 一包多车型、已内置全套氮气 UI（`nitro`/`nitro_tip` 字串、设置页图标 `ic_setting_nitro`、自定义键图标 `ic_key_nitrogen(+_bg)`），AE5i 上休眠；设置页图标命名规律 `ic_setting_<functionCode小写>` + 布局零静态引用（数据驱动页面）⇒ functionCode=`NITRO`，网络层改写有效。① 新增 `net/NitroUnlock`：**常开 dump**（命中 `vehicleSettingList` / `functionCode`+`isSupportSetting` 特征或设置类 URL 的响应，完整原文落盘 `speedhunter_netdump.log`；同接口去 query 只存一份、256KB/份）+ **opt-in 改写**（`NITRO` 条目 flip `isSupport`/`isSupportSetting`=true；列表缺省则按 AE5i 云端实测 VehicleSetting 序列化形状注入一条，空列表防 null），先 flip 后 inject、单响应只走其一 ② `HookLog` 新增 `dump()` 独立落盘通道（2MB 轮转、与主日志分开）；`Keys.KEY_NITRO_UI`（默认关）经 RemoteSettings/ConfigSender 下发；骑行记录页新卡片「氮气加速入口（实验）」（开关 + 边界与取证说明）③ `NetPatch` 集成：nitro 与 G1/G2/回填共用同一棵 JSON 树、只重建一次响应体，`patched` 日志带 `nitro{flip,inject}` 计数。**边界**：仅显示实验 —— AE5 仪表配置字「氮气加速 0:无, 1:有」且本车为无、MCU/控制器无此功能，**勿拨动 App 内该开关**（写入不会生效）；NITRO 在 49 项列表中「缺省还是 false」待 netdump 确认（DashProbe 旧 model dump 被 HookLog 截断，48 项后不可见）。文档 `docs/features/nitro-ui.md` |
| **4.7.3**（vc 56） | **车辆诊断板块复活（离线内核落地）**：把 `obd-diag`（ZEEHO 原厂蓝牙诊断模块与 App 分析记录）的协议核心移植进 SpeedHunter，作为后续接入 Xposed 模块诊断功能的地基。① 新增 `diag` 包（纯 Java、零硬件依赖、编译期可验证）：`Hex`/`IsoTp`/`Uds`/`DtcCodec`/`Rs485` + `DiagSelfTest`，覆盖 ISO-TP 单/多帧拆组、UDS 服务构造与应答解析、等级1 seed-key（seed 0→key 0x8036194E）、DTC(19 02 09) 解码、RS485 CF/ED 帧编解码 ② `DiagFragment` 由「🚧Under Construction」占位改为**诊断工具箱**：标题 + 状态说明 + 「重新运行协议自检」按钮 + 等宽自检报告（5 项全绿即通过）+ 已内置/待接入能力清单 ③ `docs/features/diagnostics.md` 重写为接入方案：资产盘点 + 两条传输路线（A.VCI 硬件 BLE/SPP / B.Hook 原厂 App 的 CFCP/MCP2 通道）+ 5 阶段计划 + 开放问题。**边界**：仅为离线算法 + 自检，未连接任何 ECU/硬件；AE6 PRO E015 `0x723`、AE7 `0x714` 链路为来源声明、本项目未复测。版本号升 v4.7.3（versionCode 56） |
| **4.7.2**（vc 55） | **自定义键映射：固定 7 扩展为 23 项官方功能码可选**：① 骑行记录页把「自定义键 = 切换信息组」卡改为 **「自定义键映射」**：总开关 + **「按键功能」**选择行（显示「名称 · 编码」，点击弹出 23 项单选列表）+ 提示「保存配置后，前往官方 App 的自定义键页面再次保存。」② 新增 `customkey/CustomKeyFunction`：与官方 App「选择按键功能」列表逐项一致的 23 项码表（1/2/3/4/5/6/7/8/9/10/11/12/13/14/15/16/17/18/19/20/21/28/31），每项带证据等级（AE5i 云端实测 / 仪表固件分支 / MCU 未验证）③ `CustomKeyOptions.targetValue()` 改读新键 `custom_key_target`（默认 7），未知码回落 7；`Keys` 新增 `KEY_CUSTOM_KEY_TARGET`；`RemoteSettings` 新增 `getInt`/`putInt`；`ConfigSender` 同步推送目标码；`CustomKeyForce` 日志按功能名输出。**真实性复核**：本车云端实测 `commandCode=1/3/19/31` = `SAY_HELLO`/`CUSHION_UNLOCK`/`STEEP_SLOPE_DESCENT`/`NO_OPERATION`，与码表完全一致；固件 `CustomKeyFunctionSet` 对 7/10/20 有专属分支、31 = 无操作；其余为 MCU 侧、AE5i 未验证（UI 明确标注）。**默认行为不变**（7 = 切换信息组，2026-10-28 实车验证） |
| **4.7.1**（vc 54） | **自定义键配置可靠性修复（反向拉取）**：修复「用户在 SpeedHunter 里先拨开关、后打开官方 App」时开关不生效的时序缺陷。原 `ACTION_CONFIG` 广播若早于 com.cfmoto 内 hook 接收器就绪会被静默丢弃，导致 `CustomKeyOptions.enabled()` 一直读默认值 false。v4.7.1 改为**挂钩后由 hook 主动反向拉取**：hook 在 `MainHook` attach 注册接收器成功后，立即发 `ACTION_CONFIG_SYNC`（定向 `com.github.speedhunter`）向 UI 要一次最新配置；UI 进程（`UiApp`）的 `ConfigSyncReceiver` 收到后把当前 SharedPreferences 经 `ACTION_CONFIG` 重发给 com.cfmoto，覆盖上述丢广播时序。新增共享 `core/ConfigSender`（UI 拨开关与反向重发共用同一套广播构造，消除重复逻辑）。**注意**：本机若此前测失败是 LSPosed 未重载旧模块（v4.6.6 仍挂载）所致；安装新 APK 后务必 `am force-stop com.cfmoto` 让其重新加载模块。版本号升 v4.7.1（versionCode 54） |
| **4.7.0**（vc 53） | **自定义键 = 切换信息组（功能码 7）· UI 开关上线**：骑行记录页「急刹/压弯回填」与「版本」两张卡之间新增开关（opt-in，默认关）。开启 ⇒ 提示「自定义键设置全映射为切换信息组（里程组 ⇄ 胎压组）」；关闭 ⇒ 提示「自定义键功能按照 ZEEHO App 的选项为准」。底层沿用 `customkey` 包：在官方 App 的 `CustomKeySelectActivity#handleSave` 前置把 `CustomButtonCode.commandCode` 反射改写成 7，App 用自身 4G 通道发出（实测全文件 `cmd=0x0A` 写帧 0 条、恒走 4G、从不走 BLE），车端 `SetItem4` 落入 7，仪表主页按自定义键即在里程组 ⇄ 胎压组间切换，从而在首页显示前后轮胎压/胎温。**移植自 DashProbe v0.6.0（2026-10-28 实车验证通过：切组成功，且重启/投屏/来电后功能不丢）**。版本号升 v4.7（versionCode 53）。源码 `customkey/CustomKeyOptions` + `customkey/CustomKeyForce`，UI `ui/RideFragment` + `res/layout/activity_ride.xml`，文档 `docs/features/custom-key.md` |
| **4.6.6**（vc 52） | **应用内图标换用 Material Symbols**：① **未挂载状态图标** = `error`（此前是隐藏不显示；卡片本身已是 `colorErrorContainer` 色调，图标语义与配色一致）② **运行日志分区图标** = `analytics`（原来是 `ic_tune` 滑块，语义不符）③ **OTA 推送页分区图标**：选择升级包 `file_open`（原 `ic_info`）、连接仪表 `connect_without_contact`（原 `ic_visibility`）、车机信息 `mobile_info`（原 `ic_check_circle`）、推送与刷写 `transition_push`（原 `ic_public`）④ 新增 6 个 drawable，取自 Material Symbols Outlined 官方源（960 视口；SVG 的 viewBox y 轴为负，用 `<group translateY="960">` 平移进可视区）；OTA 分区图标沿用既有 `app:tint="?attr:colorPrimary"`，仍跟随主题取色 ⑤ 各图标形状已逐栅格化验证（无裁切、可辨认） |
| **4.6.5**（vc 51） | **图标底色 Monet 动态取色（按手机内已安装的 MMRL 实现机制）**：从设备拉取 `com.dergoogler.mmrl`（v34346-playstore）的 APK 逐项 dump 其图标资源，发现其 `color/launcher_background` 带 **`v31` 变体指向 `@android:color/system_accent1_600`**（框架运行时按壁纸生成的 Monet 动态色）。**这才是动态取色底色的正确机制**——自适应图标的 `<background>` 层直接引用系统动态色，图标底色自动跟随壁纸变色，**完全不依赖启动器是否支持「主题图标」**（`<monochrome>` 层要启动器配合才生效，此前几轮全耗在这条路上）。本版照此实现：① 新增 `values-v31/colors.xml`：`ic_launcher_background = @android:color/system_accent1_600` ② `values/colors.xml` 的静态兜底色改深中性蓝灰 `#37474F`（仅 API<31 老系统可见）③ 前景改**白色** `#FFFFFF`（落在 Monet 动态色底上才清晰，与 MMRL 的白图形同做法；几何不变，仍是「骑行记录」自行车、scale 1.8、≤60%）④ adaptive-icon 结构不变：`<background>` = `@color`、`<monochrome>` = foreground、无 roundIcon。**运行时实证**（临时探针，已删）：`background=ColorDrawable color=0xff336940` —— 当前壁纸生成的 Monet 绿，而非静态兜底色，证明动态取色生效。拉取的 MMRL / framework-res APK 已清理 |
| **4.6.4**（vc 50） | **启动器图标 Monet 取色修复（按 com.dergoogler.mmrl 可用实现对齐）**：**根因** = `<background>` 层用了 `@drawable` 矢量图。启动器实现主题图标的做法是「取背景层纯色 → 换成壁纸 Monet 动态色，再给 monochrome 剪影重新着色」；背景是矢量图时取不到纯色 ⇒ 直接放弃主题化、原样显示背景+前景（即用户看到的「白底黑车、不跟随取色」）。**修复**：① `<background>` 改 `@color/ic_launcher_background`（#FFFFFFFF → ColorDrawable）② `<monochrome>` 改指向 `@drawable/ic_launcher_foreground`（与 MMRL 相同，monochrome 不再用独立 drawable）③ 删除 `drawable/ic_launcher_background.xml`、`drawable/ic_launcher_monochrome.xml`（此前后台轮的错误实现全部清掉）④ 造型不变：仍复用「骑行记录」tab 图标 `ic_tab_ride` 的自行车字形（scale 1.8、深色 #212121、约占安全区 54% ≤ 60%），回退态 = 白底深车 ⑤ 无 `roundIcon`（与 MMRL 一致）⑥ 运行时探针实证：`background=ColorDrawable`、`monochrome=VectorDrawable`、`AdaptiveIconDrawable` 三图层齐全。**附：历轮图标迭代排查结论**——`<group>` 变换、独立 monochrome 资产、`@drawable` 背景、`roundIcon`、启动器缓存（多次升版 + force-stop）均逐一排除；设备为 Nothing Phone（Metroid / Nothing OS 4.x，启动器 `com.nothing.launcher` 4.0.21） |
| **4.6.1**（vc 47） | **7 项修复 + 3 项清理**：① **主题图标**：`<monochrome>` 改用铺满 66dp 安全区的新资产 `ic_launcher_monochrome`；另补 `android:roundIcon`（见 ⑩）。已核对 APK 内 `adaptive-icon` 确实带 `monochrome` 且指向该资产 —— 主题图标最终由启动器渲染，启动器侧需开启「主题图标」且支持圆形图标请求 ② **诊断页透底**：占位布局补 `?attr/colorSurface` 背景 —— 此前它是唯一没背景的 Fragment 根，show/hide 切换时上一页透出来 ③ **横竖屏**：重建后用 `onSaveInstanceState` 恢复当前 tab、按 tag 复用 FragmentManager 已恢复的 Fragment（不再重复 add 导致双份页面）；横屏（M3 medium width class）改用侧边 `NavigationRailView`（新增 `layout-land/activity_main.xml`），宿主统一绑 `NavigationBarView` ④ **OTA 分区图标**：五处 `ImageView` 加 `app:tint="?attr/colorPrimary"`（原 drawable 硬编码黑/白填充，深色模式下不可见）⑤ **扫描状态及时刷新**：开始扫描 / 发现设备时同步刷新「连接仪表」状态行与「车机信息」卡（此前只刷 P2P 状态行，另两处停在旧值）；「发现状态」新增「扫描中…」档 ⑥ **SSID 兼容 CFMOTO-**：`CarWifi.SSID_PREFIXES` + `isCarSsid()`（剥 `WifiInfo` 的引号后判前缀），`CarNet` 扫描与 OTA 页「当前 Wi-Fi」判断同步 ⑦ **壁纸页扫描列表重做**：复用 `view_peer_row`（名称 / MAC / 状态 + 整行可点 + 连接按钮），仪表设备标「优先连接」并自动预选，扫描中 / 空结果均有状态反馈，与 OTA 页同一设计 ⑧ **移除未挂载时的无作用 ✗ 图标**：状态卡右上角那枚 `ic_clear` 是纯装饰、点击没有任何行为 ⇒ 未挂载时整枚图标 `GONE`，已挂载才显示 ✓ / ⓘ ⑨ **压弯标定页移除「为什么默认是这组数」整张卡**（含 `@string/tune_why`）—— 一段实验数据解释，对调参没有操作价值且数字会过期；保留「原理与用法」卡 ⑩ **补 `android:roundIcon`**：新增 `mipmap-anydpi-v26/ic_launcher_round.xml`（与方形同内容、含同一 `monochrome`），供会**单独请求圆形图标**的启动器保留 Material You 主题图标能力 —— 只给方形时启动器会自己裁圆，那一步用的是 background+foreground 合成图，`monochrome` 用不上 |
| **4.6**（vc 46） | **合规化改名**：① 项目 / 应用名统一为 **SpeedHunter**（本地目录、`settings.gradle`、`app_name`、产物名 `speedhunter-v<version>.apk`）；② 包名 `com.github.zeehospeedhunter` → **`com.github.speedhunter`**（命名空间 / applicationId / 源码目录 / `xposed_init` / Provider authority / 广播目标包名同步更换）；③ **移除文档与界面中的 ZEEHO / CFMOTO / 极核 品牌字段**，仅保留功能性必要项（Xposed 作用域 `com.cfmoto`、车机 SSID 前缀 `ZEEHO-`、BLE/P2P 设备名匹配、`/dev/cfmoto_cfcp`、`cfmoto_P2p_server` 固件组件名等）；④ 内部标识同步中性化：日志 tag `ZeehoHook`→`SpeedHunter`、`[ZeehoXxx]`→`[Xxx]`、落盘文件 `zeeho_hook.log`→`speedhunter_hook.log`、`zeeho_config.json`→`speedhunter_config.json` 等；⑤ 版本号 4.5 → 4.6。**无功能行为变化**；因包名变更，旧包需卸载后全新安装，并在 LSPosed 重新勾选作用域 |
| **4.5**（vc 45） | ① **移除 5555 ADB 直刷通道**：删除 `AdbWifiClient` / `OtaAdbPusher` 及 OTA 页对应入口，恢复为单一 p2pTool 推送通道（原 P2P 组主 10086 路径）。② **车辆诊断板块转入「施工中」**：移除诊断界面与底层引擎（`diag/` 包、`VehicleLink`、`CarNetController`、`P2pPanel`、`view_carnet.xml`），`DiagFragment` 仅保留「🚧Under Construction.」占位，后续版本合并大改动后重做。③ 两项调整均不含运行期行为变化，骑行记录 / OTA 推送 / 壁纸 / 投屏功能不受影响 |
| **4.0**（vc 44） | **重构为单 Activity + Fragment（Material Design 规范范式）**：① **架构**：5 个平级 Activity（`RideActivity`/`OtaActivity`/`WallpaperActivity`/`MirrorActivity`/`DiagActivity`）合并为**单宿主 `MainActivity` + 常驻底栏 + 5 个 Fragment**（`RideFragment`/`OtaFragment`/`WallpaperFragment`/`MirrorFragment`/`DiagFragment`，基类 `BaseTabFragment`）。底栏只在宿主创建一次，切 tab 用 `show()/hide()` 换 Fragment、**底栏实例不重建**。这是「无点击波纹 / 快速连点闪烁 / 冷启动首次点击图标抖动」三个症状的**根因修复**——它们同源于「每个 Activity 各持一份底栏」，切 tab 时底栏被重建（波纹被打断、指示器重新测量）。② 底栏高度钉死 `80dp`（M3 规范值），各页布局移除各自的底栏 `<include>`。③ 保留 Material You active indicator 动画与点击波纹（不再有 Activity 切换去掐断动画）。④ 外部入口路由：通知 / Service 的 `PendingIntent` 改指 `MainActivity` 并带 `EXTRA_TAB`（`R.id.nav_*`），宿主据此选中初始 tab 并把远程驱动 extras 转发给对应 Fragment。⑤ 原各页 `onResume` 里的「切回本页」逻辑改为宿主在选中/取消选中时调 `onPageShown()`/`onPageHidden()`（如发一次 hook PING、刷新 Wi-Fi / P2P 组信息）。⑥ 骑行记录页标题与状态卡、诊断页标题、Tonal Spot 着色沿用 4.0 既定设计。⑦ `NavBar.java` 与 5 个 Activity 删除；`OtaPushService`/`StandaloneMirrorService` 通知入口改指宿主。⑧ **系统导航条（手势区）沉浸**：底栏被抬到手势区上方，其下方那一段靠窗口导航条颜色填；新增 `EdgeToEdge.applyNavBarTint()` 把导航条刷成底栏同色（`colorSurfaceContainer`）并关掉 API29+ 对比度蒙版，使底栏 + 手势区连成一整块 surface（此前该逻辑在旧 `NavBar` 里，重构时遗漏导致手势区透出窗口底色 `colorSurface`、与底栏有色差） |
| **3.7**（vc 43） | **自定义壁纸 / 投屏优化 / UI 重构**：① 选图裁 307×460 / 1024×600 → 自动打包推仪表（P2P 复用 OTA 的 `CarP2p`）；② 合入社区 3.6.x 编码器回退、连续帧流畅模式、平衡 15 fps / 800 kbps、写入截止时间刷新（修自动断开）、旋转复用投屏授权、实时性能统计与测试画面；③ 主页改 MD3 底栏 5 tab（骑行记录 / OTA 推送 / 壁纸推送 / 仪表投屏 / 车辆诊断），状态卡区分「已激活 / 未激活」 |
| 3.5 | OTA 推送控制台 + 六项检查、车端诊断（DoIP/UDS/BLE/RS485） |
