package com.github.speedhunter.core;

/**
 * UI 进程与 hook 进程共享的常量。
 *
 * <p>两个进程各自加载本类（static final String / int 会在编译期内联），所以这里
 * <b>绝对不要</b>引用任何 Android / Xposed 平台类，保持纯净。</p>
 *
 * <p>配置链路：UI 写 SharedPreferences（尽力而为）+ 发广播 →
 * hook 进程的动态接收器收下 → 内存即时生效 + 落盘到 com.cfmoto 自己的外部 files 目录 →
 * 冷重启后从该文件读回。</p>
 */
public final class Keys {

    /** UI 写的 SharedPreferences（尽力而为，LSPosed 可能重定向）。 */
    public static final String PREFS = "speedhunter_settings";

    /** UI → hook 的配置广播 action。 */
    public static final String ACTION_CONFIG = "com.github.speedhunter.CONFIG";

    /**
     * hook → UI：请求 UI 重新下发一遍配置（UI 收到后回 {@link #ACTION_CONFIG}）。
     *
     * <p>为什么需要这条反向广播：UI 侧「打开开关」发出的 {@link #ACTION_CONFIG} 可能早于
     * com.cfmoto 里的接收器就绪（用户在 SpeedHunter 里先拨开关、再打开官方 App 的典型顺序），
     * 那条广播会被静默丢掉，导致 hook 侧 {@code enabled()} 一直读到默认值 false。
     * 改为<b>挂钩后由 hook 主动来要</b>：接收器一旦注册就向 UI 拉一次最新配置，覆盖上述时序。</p>
     */
    public static final String ACTION_CONFIG_SYNC = "com.github.speedhunter.CONFIG_SYNC";

    /**
     * 广播的定向包名（= hook 所在的 App）。
     *
     * <p>★ 必须 {@code intent.setPackage(TARGET_PKG)}：Android 8（API 26）起
     * <b>隐式广播被系统限制</b>，{@code sendBroadcast(new Intent(action))} 收不到动态注册的
     * receiver（实测：UI 侧发 ACTION_CONFIG / CLEAR_DAY_STORE，hook 进程毫无反应；
     * 而 shell 的 {@code am broadcast} 因为有特权权限反而能送达，容易误判成「广播正常」）。
     * 带上 setPackage 后系统会在该包内匹配 receiver，不受该限制。</p>
     */
    public static final String TARGET_PKG = "com.cfmoto";

    /** UI → hook：请求清空按天账本（改完标定参数后要重算，旧账是旧参数算的）。 */
    public static final String ACTION_CLEAR_DAY_STORE =
            "com.github.speedhunter.CLEAR_DAY_STORE";

    /**
     * UI → hook：退出极核 App 内登录的全部账号（清掉它自己的登录态 SP / MMKV 并自重启）。
     *
     * <p>与 {@link #ACTION_CLEAR_DAY_STORE} 同理：登录态写在 {@code com.cfmoto} 自己的
     * 存储里，Android 11+ 别的 App 读不到也写不进，所以 UI 只能发指令，由 hook 进程
     * （同 UID）自己清、自己重启，从而得到一个干净的「未登录」环境，便于重新套用引导登录。</p>
     */
    public static final String ACTION_CLEAR_LOGIN =
            "com.github.speedhunter.CLEAR_LOGIN";

    /**
     * UI → hook：查询账本状态（已记几天 / 还差几天没重灌）。
     *
     * <p>为什么需要查询：day store 在 {@code com.cfmoto} 的 external files 下，
     * Android 11+ 不许别的 App 读（已实测 EACCES），所以 UI 进程只能<b>问</b>，
     * 由 hook 进程读出来用广播回传。这是 iOS 版 {@code [ClearStore] cleared N keys}
     * 那行日志的「有状态版」。</p>
     */
    public static final String ACTION_QUERY_STORE_STATE =
            "com.github.speedhunter.QUERY_STORE_STATE";

    /**
     * UI → hook：探测模块是否真的挂进了官方 App（hook 存活心跳）。
     *
     * <p>为什么需要探测：LSPosed 里「模块已启用 / 已勾选作用域」从 UI 进程读不到，
     * 而 hook 的动态接收器只存在于 com.cfmoto 进程里 —— 能收到并回 {@link #ACTION_HOOK_PONG}
     * 就说明模块确实挂上了（且官方 App 正在运行）；超时收不到就是「未挂载」。</p>
     */
    public static final String ACTION_HOOK_PING =
            "com.github.speedhunter.HOOK_PING";

    /** hook → UI：{@link #ACTION_HOOK_PING} 的回执，收到即视为已挂载。 */
    public static final String ACTION_HOOK_PONG =
            "com.github.speedhunter.HOOK_PONG";

    // ===== 账号引导登录：hook 侧真实状态回执（附在 ACTION_HOOK_PONG 上） =====
    /**
     * <p>为什么要有回执：账号页的「保存成功 / 查询成功」都发生在<b>模块 UI 进程</b>
     * ——{@code AccountApi} 是模块自己直连云端，跟 hook 有没有收到凭证毫无关系。
     * 曾出现「页面显示成功、官方 App 却完全没注入」的假成功：根因是配置广播把
     * 账号键冲成空。所以 PONG 必须带上 hook 进程<b>实际生效</b>的状态，UI 只认这个。</p>
     */
    /** hook 侧 {@code account_login && token 非空 && userId 非空}。 */
    public static final String EXTRA_ACCOUNT_READY = "account_ready";
    /** hook 侧 token 长度（只回长度，不回明文，避免跨进程广播泄露凭证）。 */
    public static final String EXTRA_ACCOUNT_TOKEN_LEN = "account_token_len";
    /** hook 侧账号 ID 长度。 */
    public static final String EXTRA_ACCOUNT_UID_LEN = "account_uid_len";
    /** hook 侧请求注入累计命中次数。 */
    public static final String EXTRA_ACCOUNT_INJECT = "account_inject";

    /** hook → UI：账本状态回传（只有 hook 进程能发，UI 用 setPackage 定向接收）。 */
    public static final String ACTION_STORE_STATE =
            "com.github.speedhunter.STORE_STATE";

    /** {@link #ACTION_STORE_STATE} 的 extra：hook 侧已记账的天数。 */
    public static final String EXTRA_DAYS_ON_FILE = "days_on_file";
    /** {@link #ACTION_STORE_STATE} 的 extra：待重灌的天数。 */
    public static final String EXTRA_DAYS_PENDING = "days_pending";
    /** {@link #ACTION_STORE_STATE} 的 extra：待重灌的月份，逗号分隔（{@code 2026.07,2026.08}）。 */
    public static final String EXTRA_MONTHS_PENDING = "months_pending";

    /**
     * UI → hook：索要压弯判定过程的日志（对应 iOS 侧 {@code C.DEBUG} 打出来的那批 {@code BENDS} 行）。
     *
     * <p>为什么要专门要一次：判定过程是 <b>hook 进程</b>（com.cfmoto）算出来的，
     * 落在它自己的 {@code /sdcard/Android/data/com.cfmoto/files/speedhunter_hook.log} 里；
     * Android 11+ 不许本模块进程读那个目录（已实测 EACCES），而本模块自己那份
     * {@code speedhunter_hook.log} 只记本进程的东西、根本没有 {@code BENDS}。
     * ⇒ 只能由 hook 进程读出来再广播回来。</p>
     */
    public static final String ACTION_QUERY_BEND_LOG =
            "com.github.speedhunter.QUERY_BEND_LOG";

    /** hook → UI：判定过程日志回传。extra 见 {@link #EXTRA_BEND_LOG}。 */
    public static final String ACTION_BEND_LOG = "com.github.speedhunter.BEND_LOG";

    /** {@link #ACTION_BEND_LOG} 的 extra：最近若干行 {@code BENDS}，用 {@code \n} 拼。 */
    public static final String EXTRA_BEND_LOG = "bend_log";

    /**
     * UI → hook：启动仪表投屏（EasyConnect 镜像）。
     *
     * <p>为什么需要这个广播：投屏的私有镜像编解码在 {@code libECSDK.so} 里，
     * 只在 {@code com.cfmoto} 进程里加载；模块 UI 进程没有这套库，无法自己推镜像帧。
     * ⇒ 模块 UI 只负责<b>手机侧网络层</b>（加入车机 AP，见 {@code ota.CarWifi}），
     * 真正「拉起投屏」由本广播交给 hook（同 UID，能启动 app 内部未 exported 的投屏 Activity）。</p>
     */
    public static final String ACTION_MIRROR_START =
            "com.github.speedhunter.MIRROR_START";

    /** UI → hook：断开投屏（释放车机 AP 的 specifier 请求由 UI 侧 CarWifi.release 做）。 */
    public static final String ACTION_MIRROR_STOP =
            "com.github.speedhunter.MIRROR_STOP";

    /** {@link #ACTION_MIRROR_START} 的 extra：车机 AP 的 SSID。 */
    public static final String EXTRA_MIRROR_SSID = "mirror_ssid";
    /** {@link #ACTION_MIRROR_START} 的 extra：车机 AP 的密码（开放网络可空）。 */
    public static final String EXTRA_MIRROR_PWD = "mirror_pwd";

    /** hook 进程持久化配置的文件名（在其自身外部 files 目录下）。 */
    public static final String CONFIG_FILE = "speedhunter_config.json";

    // ---- 四个开关 ----
    /** 主开关：网络层能力位改写（= iOS Shadowrocket 脚本做的事）。 */
    public static final String KEY_WEB_LAYER = "web_layer";
    /** G1：vehicleKinds = 2（骑行分析入口 + 极速）。 */
    public static final String KEY_GATE_ANALYSE = "gate_analyse";
    /** G2：cyclingEventStatisticFlag = true（急加速 / 急刹 / 压弯）。 */
    public static final String KEY_GATE_EVENT = "gate_event";
    /** 兜底：旧视图层四通道（把控件沿祖先链点亮）。 */
    public static final String KEY_VIEW_LAYER = "view_layer";
    /** 规避 MobTech SDK 在 Android 16 上的启动崩溃。 */
    public static final String KEY_MOB_GUARD = "mob_guard";
    /**
     * 骑行数据回填（急刹 / 压弯）—— 从 {@code ridetrack_v2} 的轨迹还原
     * {@code brakesTimes} / {@code bendingTimes}，默认<b>开</b>。
     */
    public static final String KEY_RIDE_FILL = "ride_fill";
    /**
     * 回填时顺带放大 {@code ridetrack_v2} 的 {@code pageSize}（默认开）——
     * 「历史轨迹」按月发请求且没有上拉加载，不放大就只剩每月最近 20 趟。
     */
    public static final String KEY_RIDE_FULLSCAN = "ride_fullscan";

    // ---- 压弯 / 急刹标定参数（「压弯标定」页滑块，运行时可调）----
    // 全部以字符串形式落在 speedhunter_config.json 里；缺失时 RideConfig 用 DEF_* 默认值。
    /** 压弯速度下限 km/h（默认 20，与 iOS 定稿一致；文档 v1.0 给的 10 实测召回过高）。 */
    public static final String KEY_BEND_V_MIN = "bend_v_min";
    /** 单步转角阈值 度（默认 5）。 */
    public static final String KEY_BEND_SEED = "bend_seed";
    /** 最小累计转角 度（默认 15，与 iOS v16 定稿一致）。 */
    public static final String KEY_BEND_CUM_MIN = "bend_cum_min";
    /** 累计转角上限 度（默认 90）。 */
    public static final String KEY_BEND_CUM_CAP = "bend_cum_cap";
    /** 压弯判定系数（默认 1.0，整体缩放）。 */
    public static final String KEY_BEND_COEF = "bend_coef";
    /** 压弯速度上限 km/h（默认 80）。 */
    public static final String KEY_BEND_V_MAX = "bend_v_max";
    /** 急刹标定系数（默认 2.5）。 */
    public static final String KEY_BRAKE_CALIB = "brake_calib";
    /** 最短行程门槛 km（默认 1.0；短于此里程的行程整趟记 0 次压弯）。 */
    public static final String KEY_BEND_MIN_KM = "bend_min_km";
    /** 是否把原始轨迹样本导出到文件（离线扫参数用，默认关）。 */
    public static final String KEY_TUNE_EXPORT = "tune_export";
    /**
     * 是否逐 run 打印压弯判定过程（默认关）。
     *
     * <p>对应 iOS 侧 {@code RideFill.js} 的 {@code C.DEBUG} + {@code debugPrint()}：
     * 打印每个候选 run 的 cum / 方向 / 段内最高速，以及被拒的原因。</p>
     */
    public static final String KEY_DEBUG_BEND = "debug_bend";

    /**
     * 手机端日志悬浮窗开关。
     *
     * <p>保留这个键只为<b>状态展示</b>：悬浮窗只在 {@code ui.OtaActivity}「最后一公里操作台」
     * 里挂（进页面 attachHud、退出 onDestroy 收起），模块主页与 {@code UiApp} 启动都不再挂。
     * 键本身仍可被 hook 进程读到，将来做「环境体检 → 直接上屏」那条路时能复用。</p>
     */
    public static final String KEY_HUD = "hud_enabled";

    /**
     * 自定义键映射总开关 —— opt-in，默认关。
     *
     * <p>来源：DashProbe v0.6.0（2026-10-28 实车验证通过）。开启后，在官方 App 的
     * {@code CustomKeySelectActivity#handleSave} 前置把 {@code CustomButtonCode.commandCode}
     * 反射改写成 {@link #KEY_CUSTOM_KEY_TARGET} 指定的功能码（默认 7 = 切换信息组），
     * 让仪表主页按自定义键即在里程组 ⇄ 胎压组间切换，从而把前后轮胎压/胎温显示出来。</p>
     *
     * <p>默认关：不开启时完全不触碰任何字段，等价于模块对该设置项无感知。</p>
     */
    public static final String KEY_CUSTOM_KEY_FORCE = "custom_key_force";

    /**
     * 自定义键目标功能码（int，默认 7 = 切换信息组）。
     *
     * <p>取值来自官方 App {@code CustomButtonCode.commandCode} 枚举的 23 项
     * （见 {@code customkey.CustomKeyFunction}）。仪表对 SetItem4 里的这个字节
     * <b>不做映射</b>：写进去多少就是多少。合法值 1/2/3/4/5/6/7/8/9/10/11/12/13/14/
     * 15/16/17/18/19/20/21/28/31。</p>
     */
    public static final String KEY_CUSTOM_KEY_TARGET = "custom_key_target";

    /**
     * 氮气加速入口总开关 —— opt-in，默认关。
     *
     * <p>依据 AE7 Pro 实采数据（2026-11-07）：氮气行由
     * {@code vehicleSetInfo.nitro} 状态字段的存在 + {@code vehicleKind(s)=3}
     * 驱动（AE7=3 / AE5i=1），<b>不走</b> items/itemsV2 设置列表。
     * 开启后网络层把 {@code vehicleKinds}/{@code vehicleKind} 置 3、并给
     * vehicleSetInfo.data 补 {@code nitro:0}。</p>
     *
     * <p>纯显示实验：AE5 车端无此功能，勿拨动 App 内该开关。证据见
     * {@code docs/features/nitro-ui.md}。</p>
     */
    public static final String KEY_NITRO_UI = "nitro_ui";

    /**
     * ABS 开关入口总开关 —— opt-in，默认关。AE5i Pro 云端配置表不下发
     * {@code ANTI_LOCK_BRAKE_SYSTEM} 条目，故 App 无「ABS开关」行。开启后网络层
     * 克隆注入设置列表 + 骑行偏好分类子项，让 App 自己渲染该行。
     * <p>纯显示：克隆条目清空 serviceCode，写入生效性未验证。证据见
     * {@code docs/features/abs-switch.md}。</p>
     */
    public static final String KEY_ABS_UI = "abs_ui";

    // ===== 账号引导登录（token + 账号 ID 外置录入） =====
    /**
     * 引导登录总开关 —— opt-in，默认关。开启后 hook 侧把官方 App 发出的
     * {@code zeehoev.com} 请求的 {@code Authorization} 换成录入的 token，
     * 并把 {@code userId}/{@code userid} 参数换成录入的账号 ID，让官方 App
     * 以该凭证访问云端（多设备共同登录同一账号）。
     */
    public static final String KEY_ACCOUNT_LOGIN = "account_login";

    /** 外部客户端取得的登录 token（Bearer）。 */
    public static final String KEY_ACCOUNT_TOKEN = "account_token";

    /** 外部客户端取得的账号 ID（URL 里的 userId/userid）。 */
    public static final String KEY_ACCOUNT_USERID = "account_userid";

    /**
     * 「我的APP首页」选择值（对应官方 {@code UserInfo.homepage}）。
     *
     * <p>为什么需要：引导登录伪造的 {@code UserInfo} 不带真实账号的 homepage 字段，
     * App 把它当空 → 首页落地回落到未登录默认的「极客」。这里持久化 App 自己保存过
     * 的值（点设置页「确定」时由 hook 学习写入），主页就能跟随设置项。</p>
     */
    public static final String KEY_ACCOUNT_HOMEPAGE = "account_homepage";

    /** 登录态最后校验结果（UI 展示，不参与 hook 决策）。 */
    public static final String KEY_ACCOUNT_STATUS = "account_status";

    /**
     * 诊断开关（默认关）：打开后 hook 会记录官方 App 读取登录态相关
     * SP/MMKV 键时的「调用方类名 + key」，用来反推它到底从哪个存储、
     * 哪个 key 读取 token / userId / isLogin —— 因为 cfmoto.xml 是加固加密的，
     * 盲目覆盖 SharedPreferencesImpl 的 getString 根本够不到真实的读取层。
     */
    public static final String KEY_ACCOUNT_DIAG = "account_diag";

    /**
     * 首页模式诊断开关（默认关）：打开后 hook 会记录官方 App 读取 / 写入
     * 与「首页显示模式 / 极客模式」相关的 SP / MMKV 键，用来反推：
     * ① 首页Fragment 到底从哪个 key 决定默认进「极客」；
     * ② 设置页那个滚轮选择器（wheelView）点「确定」时把值写到了哪个 key。
     * 这样就能让首页跟随用户在设置里选的值，而不是未登录时的默认「极客」。
     */

    public static final String EXTRA_MIRROR_QR_URL = "mirror_qr_url";
    public static final String EXTRA_MIRROR_REQUEST_ID = "mirror_request_id";
    public static final String EXTRA_MIRROR_STATE = "mirror_state";
    public static final String EXTRA_MIRROR_MESSAGE = "mirror_message";
    public static final String ACTION_MIRROR_STATUS = "com.github.speedhunter.MIRROR_STATUS";
    public static final String ACTION_MIRROR_QUERY = "com.github.speedhunter.MIRROR_QUERY";

    private Keys() {
    }
}
