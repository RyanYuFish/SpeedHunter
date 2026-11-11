# 账号引导登录 · 签到与积分任务

> 状态：**v4.7.48 已实现会话资料桥接；未登录账号清空数据后的冷启动仍需单独验收**。
> 参考实现：设备上已安装的第三方 App「几何面板」v1.8.40
> （`io.github.lswlc33.zeehowebnext`），登录参数与任务交互以其现有实现为准。
> iOS 配套：`../../SpeedHunter-iOS/scripts/ZeehoAccountToken.js`。

## 0. v4.7.48：冷启动账号会话与「我的」资料

账号注入除请求凭证外，还接管官方 `AppContext` 的 `getUserId()`、`getToken()`、
`isLogin()`、`isLoginAndToLogin()` 和 `getUserInfo()`。首次读取用户对象时，模块用外置
token 请求官方 `baseInfo_v2`，要求响应 `data.id` 与录入 userId 完全一致，再用服务端资料
构造官方 `UserInfo`（头像、昵称、电话、省市）。资料加载异步执行；首次读取最多等待
1.8 秒。请求失败或账号 ID 不符时不伪造资料对象。凭据关闭后这些 hook 立即回到官方行为。

登录闸门仍由本地 SharedPreferences/MMKV 的精确 key 兜底，因此设计目标是无已有账号的
冷启动直接进入预设账号；需要在清除/登出后的设备状态验证，普通 force-stop 不等于冷启动。
该会话桥接自身的日志只报告成功/失败状态，不写凭证或个人资料字段。

`NetPatch` 同时跳过 Retrofit `NoContentResponseBody` 转换哨兵，避免读取已转换响应时抛出
`Cannot read raw response body of a converted body.`；原始可读响应仍走现有处理。

## 0.1 v4.7.43 配置送达修复

### 0.1 设备实证（Nothing Phone / Android 16 / LSPosed）

```
hook 日志：[Net] account login installed (on=false)
hook 日志：account inject 命中 0 次
hook 落盘 speedhunter_config.json：
    account_login=false   account_token=""   account_userid=""
```

而页面显示「保存成功 / token 有效」——因为 `AccountApi` 是**模块 UI 进程自己**
直连云端查询，跟 hook 有没有收到凭证毫无关系。这是典型的**假成功**。

### 0.2 三个叠加根因

| # | 根因 | 后果 |
|---|---|---|
| A | `AccountActivity` 用 `getSharedPreferences(Keys.PREFS /* speedhunter_settings */)`，而首页 / 压弯标定页 / `UiApp.ConfigSyncReceiver` 全用 `getDefaultSharedPreferences()` —— **两份不同文件** | hook 进程 attach 时发 `ACTION_CONFIG_SYNC` 反向拉取，UI 从另一份空 prefs 重推，把刚送达的 token 覆盖成空串 |
| B | `ConfigReceiver` 末尾是 `liveConfig = config` 的**全量替换**，且对账号三键无条件写默认值 | `TuneActivity.apply()` / `sendDebugSwitch()` 等只带局部参数的广播，任何一次都把 token/userId 冲空、开关冲成 false |
| C | 按钮叫「保存并登录」，`save()` 却写 `swLogin.isChecked()`，而开关**默认关** | 填完凭证直接点按钮，落盘的仍是 `account_login=false`，hook 侧 `ready()` 永远为假 |

设备日志时间线完全吻合：`config receiver registered` → `config sync requested`
→ `config via broadcast, saved {…account_login=false, tokenLen=0, uidLen=0}`。

### 0.3 修复

| 处 | 修复 |
|---|---|
| `ui/AccountActivity.java` | 统一用 `getDefaultSharedPreferences()`；首次进入从旧 `Keys.PREFS` **一次性迁移**凭证，升级不丢数据 |
| `ui/AccountActivity.java` | 「保存并登录」强制 `account_login=true`（履行按钮字面语义）；停用请手动关开关 |
| `ui/AccountActivity.java` | 手动拨开关也立即落盘 + 重推（旧实现只改文案，关掉开关后 hook 仍按上次广播继续注入） |
| `core/RemoteSettings.java` | 账号三键改为 `putAccountString/putAccountBool`：**仅当 intent 显式带该 extra 才覆盖，否则继承**当前 `liveConfig`（冷启动则读落盘文件）。`ConfigSender` 总带全量，局部广播不再冲刷 |
| `core/RemoteSettings.java` | 配置落盘日志 `redact()` 脱敏（旧实现把 token 明文打进日志） |
| `core/ConfigProvider.java` | 改读默认 prefs 与 UI 同源；exported 无权限，账号三键全部排除 |
| `net/AccountLogin.java` | 补 `getBoolean/decodeBool` 对 `IS_LOGIN` 的精确白名单；框架 SP hook 加 `ThreadLocal` 防递归 |
| `core/RemoteSettings.java` + `Keys.java` | **PONG 回执**附带 hook 侧真实状态 |

### 0.4 hook 状态回执（消除假成功）

复用既有 `ACTION_HOOK_PING` / `ACTION_HOOK_PONG` 心跳，PONG 附带：

| extra | 含义 |
|---|---|
| `EXTRA_ACCOUNT_READY` | hook 侧 `account_login && token 非空 && userId 非空` |
| `EXTRA_ACCOUNT_TOKEN_LEN` | token **长度**（不回明文，避免跨进程广播泄露） |
| `EXTRA_ACCOUNT_UID_LEN` | 账号 ID 长度 |
| `EXTRA_ACCOUNT_INJECT` | 请求注入累计命中次数（`AccountLogin` 注入成功时 `noteAccountInjection()`） |

账号页状态区**只认这个回执**：「等待回执 / 已挂载但凭证未送达 / ✓ 凭证已送达 +
注入命中 N 次」。本进程的保存结果和 `AccountApi` 查询成功只显示为
「云端校验：token 有效」，**不再冒充登录成功**。

## 1. 目标与边界

| 目标 | 实现 |
|---|---|
| 手动登录（外置凭证） | 模块 UI 录入 `token + 账号 ID`，保存并维持登录态 |
| 引导官方 App 登录该账号 | hook 请求侧注入 `Authorization` + 替换 `userId` 参数；本地登录态闸门兜底 |
| 多设备共同登录同一账号 | 多台设备填同一份凭证，均不以「新登录」方式挤掉彼此 |
| 签到 + 积分任务 | 模块内可执行签到、查询签到/积分/任务状态并展示 |

**不做**：不实现短信/密码登录流程、不抓取或上传车主凭证、不伪造登录成功响应、
不自动执行签到（唯一写操作由按钮 + 二次确认触发）。

## 2. 逆向依据（几何面板 v1.8.40）

APK 经 `apktool` 反编译（`smali` 554 个类），核心证据：

| 项 | 值 | 出处 |
|---|---|---|
| 登录 token | `Authorization: Bearer <token>` | `w.g()`：`Bearer ` + token 写入 header |
| 账号 ID | URL 参数 `userId` / `userid` / `user_id` / `uid` | `i2`/`xf` 正则、`p.smali` |
| 账号备份格式 | JSON 数组 `{token, userid}` | `strings.xml: recovery_invalid_input` |
| 账号池 | `zeeho.accounts.v1`（`accountId`/`token`/`uid`/`active`/`rev`） | `j.smali` |
| 通用请求头 | `Content-Type: application/json;charset=UTF-8`、`Accept-Language: zh-CN`、`interfaceversion: 2`、`cfmoto-x-param`、`cfmoto-x-sign`、`cfmoto-x-sign-type: 0` | `w.g()` |
| `cfmoto-x-param` | `appId=S7qPWPU1&nonce=<随机>&timestamp=<毫秒>`（tapi）<br>`appId=AiTXmBrm&nonce=<UUID>&timestamp=<毫秒>`（H5） | `w.c()` / `z0` / `p` |
| `cfmoto-x-sign` | `MD5( hex( SHA-1( sortedQuery + body + param + secret ) ) )` | `w.c()` 3690–3740、`z0` 175–240 |
| secret | tapi `c5e0da7f…` / H5 `70c2c745…` | 同上（**见代码常量，勿外传**） |
| 签到 | `POST https://h5.zeehoev.com/cfmotoservermine/H5/signin`（body `{}`） | `z0` |
| 签到状态 | `GET …/H5/signin/info?userId=&month=&server_name=SMART` | `p` |
| 签到次数 | `GET /v1.0/mine/cfmotoservermine/signin/count` | `z0` |
| 积分 | `GET /v1.0/mine/cfmotoservermine/integral/getByUserId?userId=`、`/totalIntegral` | `p` / `hd` |
| 任务列表 | `GET /v1.0/social/cfmotoserversocial/community/qbTzInfoNewV2` | `z0` |

签名串里的 `sortedQuery` 是按 key 排序的 `k=v&k=v`（不含 `?`）；GET 的 `body`
为空串，H5 签到 POST 的 `body` 为 `{}`。

### 官方 App 本地登录态（v4.7.48）

设备实证确认官方会话由 `com.cfmoto.AppContext` 暴露 `getUserInfo()`、`getUserId()`、
`getToken()`、`isLogin()` 和 `isLoginAndToLogin()`。仅覆盖 preferences 能放行主界面，
但“我的”仍可从数据库读取之前账号的 `UserInfo`。`AccountSession` 统一接管这些会话读取；
外置凭据启用时用 token 请求 `baseInfo_v2`，仅在 `data.id` 与配置 ID 一致后创建并返回
官方 `com.cfmoto.dbmgr.entity.UserInfo`，提供真实头像、昵称、电话和省市。账号不匹配或
请求失败不会拼接其他账号的资料。

`installPrefs()` 仍为冷启动早期的 SP/MMKV token、用户 ID 和登录闸门兜底。外置开关关闭后，
AppContext 与存储读取均恢复原值。profile API 仅用于显示资料，不持久化 token 或个人资料。

## 3. SpeedHunter 实现

### 3.1 配置通道（复用既有结构）

| 文件 | 职责 |
|---|---|
| `core/Keys.java` | `KEY_ACCOUNT_*` 四个键 + PONG 回执四常量 |
| `core/ConfigSender.java` | 广播携带账号三键（全量，UI 侧单一数据源） |
| `core/RemoteSettings.java` | 账号键按 `hasExtra` 覆盖 or 继承；PONG 回执；注入计数；日志脱敏 |
| `net/AccountOptions.java` | hook 侧 `enabled()/token()/userId()/ready()` |
| `core/ConfigProvider.java` | 读默认 prefs；exported 无权限，账号三键全部排除 |

> 日志边界：模块自身日志不打 token 明文、账号 ID 打码。
> `NetDump` 的「全量请求头落盘」是 `tools/ota` 重放官方固件包所需的既有能力
> （`docs/50`、`docs/65`），**保持不变**——它会把 `Authorization` 写进本机文件
> （不外发）；车主需自行保管该文件。

### 3.2 引导登录（`net/AccountLogin.java`）

复用 `RideFetch.REQUEST_BUILDERS` 与 `ThreadLocal REBUILDING` 防递归模式，
hook `okhttp3.Request$Builder#build()`（after）：

1. 只处理 `zeehoev.com`；`AccountOptions.ready()` 为假直接返回。
2. `header("Authorization", "Bearer " + token)`。
3. 正则替换 URL 里的 `userId|userid|user_id|uid|accountId` 参数值为录入账号 ID。
4. **若请求已带 `cfmoto-x-sign`**：用其原有 `cfmoto-x-param` 重算签名并覆盖
   （`AccountApi.reSign()`），否则签名串含 sortedQuery 会失效。
   若签名由后续 Interceptor 添加（此时无签名头），不动，让它按新 URL 自然算。
5. H5 账号类页面 URL（signin / integral / baseinfo / /h5/）补 `token` 参数，
   不污染图片、JS 等静态资源请求。
6. 注入成功调用 `noteAccountInjection()` 供 PONG 回执。
7. 本地登录态兜底见 §2。

`MainHook.installAll()` 注册；总开关默认关，关掉或清凭证即完全恢复原行为。

### 3.3 签到与积分（`net/AccountApi.java`）

纯 JDK/Android 内置 API（`HttpURLConnection` + `org.json` + `MessageDigest`），
无新依赖。所有接口都带当前录入的 `token`，账号侧接口显式带 `userId`：

- 只读：`signInCount` / `integral` / `totalIntegral` / `taskList` / `signInInfo`
- 写：`signIn`（`H5/signin` POST）

`Result` 保留 HTTP 码、`code`/`message`/`data` 与原始响应体，失败原样展示。

### 3.4 UI（`ui/AccountActivity.java` + `activity_account.xml`）

**v4.7.43 按「压弯标定」二级页范式重做**：`BackGestureCompat` +
`EdgeToEdge.setup(root_account)`、顶栏圆形返回按钮 + 右上角「清除凭证」文字按钮、
`PageTitle` 大标题、`CardBlock` 卡片、`CardBlockTitle/Body`、
`ParamTitle/ParamHelp/ParamDivider` 统一排版、底部「原理与用法」色卡。
控件 id 全部沿用旧版，业务逻辑不因排版改动。

内容：① 登录凭证（两个输入框 + 启用开关 + 保存并登录 + 本机状态 + **hook 回执状态区**）；
② 签到与积分任务（刷新状态 / 立即签到 + 三行摘要 + 可滚动的应答详情）；
底部原理与用法。

## 4. iOS 配套（SpeedHunter-iOS）

`scripts/ZeehoAccountToken.js`（`type=http-request`，只读不改写）：

- 从请求 URL 的 `token`/`access_token`/`satoken`/`h5_token` 或
  `Authorization: Bearer`/`x-token`/`access-token` 头取 token；
  从 `userId`/`userid`/`user_id`/`uid`/`accountId` 参数取账号 ID。
- 写入 `$persistentStore`（`zeeho_token` / `zeeho_userid`）并打印可复制的一行，
  **替代原先手工翻 `logs/successPacketTunnel-*.log` 找 URL 字段再复制**。
- 日志默认打码；`PRINT_FULL_TOKEN=true` 才输出明文。禁用脚本即零采集。
- `conf/Zeeho.conf` + `.example` 加脚本条目，`[MITM] hostname` 追加
  `h5.zeehoev.com`（H5 签到页 URL 要解密才读得到 token）。

## 5. 使用步骤

1. 在 iOS（Shadowrocket）或其他客户端取得 `token + userid`。
2. SpeedHunter 骑行记录页 → 「账号引导登录」→ 填两项 → 点「保存并登录」（自动启用）。
3. 看页面下方**「模块生效状态」**出现 `✓ 凭证已送达 hook` 才算成功；
   若显示「已挂载但凭证未送达」，重推配置或重启极核 App。
   若一直「等待回执」，说明 LSPosed 作用域没勾 / 官方 App 没启动过。
4. 冷重启官方极核 App，其云端请求即以该凭证发出；进一次「我的 / 签到」页，
   回执里的「请求注入命中 N 次」会从 0 变正数。
5. 「刷新状态」看签到/积分/任务；「立即签到」需二次确认。
6. 回退：关开关或右上角「清除凭证」→ 重进官方 App。

## 6. 验证状态与已知边界

| 项 | 状态 |
|---|---|
| v4.7.43 编译 / 签名 / 资源 / 安装启动 | ✅ 已验证 |
| 配置送达（hook 落盘 `account_login=true`、token 非空） | ✅ 设备可复查；修复前实测为空 |
| PONG 回执 / 注入计数 | 静态实现 + 编译验证，待实车读回 |
| 请求侧注入（Authorization + userId + 重签名） | 静态实现，**待实车抓包确认** |
| 本地登录态 `IS_LOGIN` 兜底 | 静态实现（dex 字符串证据），加固重定位时为 no-op |
| 签到/积分/任务接口应答 | **未实车验证**；签名/参数来自 smali 静态反汇编 |
| 多设备共登同账号 | 机制成立，**待车主实测确认云端是否挤下线** |

若某接口返回鉴权或签名错误，UI 的「原始应答详情」会显示完整 body，
据此调整 `AccountApi` 的 `sign()` 拼接或 `sortedQuery` 组成即可，不影响其余功能。
签名常量来自设备端已安装 App 的现有实现，仅本地自用，勿外传。

## 7. 启动首页跟随「我的APP首页」设置（v4.7.64/vc117）

### 问题
引导登录后，官方 App 冷启动总是落在**极客**（社区页），而设置页里
「我的APP首页」选的是**极车**。也就是说，首页落地没有跟随账号里的这项设置。

### 根因
App 的落地页读的是 `com.cfmoto.dbmgr.entity.UserInfo#homepage`（反射 dump 实机确认字段存在）。
引导登录必须**伪造**这个 `UserInfo`（`AccountSession` 用反射 new 一个），
伪造对象里 `homepage` 只能给 `""`，于是 App 按默认值落到极客。
真实值既不在 `SharedPreferences`（爱加密）、也不在 MMKV（逐 key 扫描过），
数据库 `CHUNFENGDB` 是加密的，无法本地解。**唯一可靠来源是云端请求本身**。

### 取值编码（实机双向验证）
| 设置项 | 页签序号 | 请求路径 | `homepage` |
|---|---|---|---|
| 极车 | 0 | `PUT /v1.0/mine/cfmotoservermine/baseInfo/others/1` | `1` |
| 服务 | 1 | `…/others/2` | `2` |
| 极客 | 2 | `…/others/3` | `3` |
| 极市 | 3 | `…/others/4` | `4` |
| 我的 | 4 | `…/others/5` | `5` |

即 **N = 页签序号 + 1**。实测：`account_homepage=1` → 冷启动落极车；
`=4` → 落极市；清空 → 回落极客（复现原 bug）。

### 实现
1. **自学习**：在已有的 `okhttp3.Request$Builder#build()` after-hook
   （`AccountLogin#patch`）里，凡是 `PUT …/baseInfo/others/{N}` 就用
   `trailingSegment` 取路径末段（只认 1–5）→
   `rememberHomepage()` → `RemoteSettings.rememberAccountString(KEY_ACCOUNT_HOMEPAGE, N)` 落盘。
   不额外挂 okhttp 内部类，只借已经稳定工作的 build 锚点。
2. **回填**：`AccountSession.session()` 构造伪造 `UserInfo` 后，
   把 `homepage` 设为学习到的值；`Session` 按 (userId, token) 缓存，
   新值在下一次冷启动生效（落地本来只发生在冷启动）。
3. **无需开关**：学习是常开行为（仅在引导登录开启时生效），没有单独的诊断开关。
   学习到新值只记一行 `[Net] homepage learned: N`。

### 为什么不用请求体
签名拦截器在 `Request$Builder#build()` **之后**才序列化 body，
build 时是 0 字节；即便在发送点（`CallServerInterceptor`）读，body 也是
Kotlin lambda 体，`writeTo`/字段反射都拿不到明文（实测 `body=null`）。路径才是稳定信号。

> 排查期临时挂过的探针（SP/MMKV 读写窗口、`setMyhomeText` 打点、
> `CallServerInterceptor` 请求/响应体嗅探、`UserInfo` 字段转储、`home_diag` 开关）
> 都已在 v4.7.66 清理，仅保留上面这条路径学习。

### 边界
- 只在**用户点「确定」保存**时学习；换机/网页端改设置不会被感知，
  需在 App 内再保存一次。
- `setting/other` 响应只含社交资料（nickname/签名/粉丝数…），**没有 homepage**，
  响应侧嗅探仅作兜底。
- `baseInfo/others/{1..5}` 目前只观测到用于首页设置；若后续版本把别的
  baseInfo 子项挂到同一路径，可能误学——届时改用 body 或响应交叉校验。
- 首次安装且从未在本机保存过：`account_homepage` 为空 → 仍落极客，
  保存一次即永久记住。

### 防止被局部广播冲掉（v4.7.65/vc118）
`RemoteSettings.ConfigReceiver` 对 `ACTION_CONFIG` 是**全量替换** `liveConfig` 并重写落盘文件，
而压弯标定保存、单开关下发等场景发的是**只带局部 extra** 的广播。若不为新键做继承，
任何一次这样的广播都会把 `account_homepage` 抹掉，下次冷启动又回极客。
修复：与「账号三键」同法，用 `putAccountString(config, intent, KEY_ACCOUNT_HOMEPAGE)`——
extra 存在才覆盖，否则从内存/落盘继承。

实测：`am broadcast -a com.github.speedhunter.CONFIG --ez web_layer true --ez ride_fill true`
（故意不带 homepage）→ 文件被重写（mtime 更新）且 `account_homepage` 仍为 `1`。
