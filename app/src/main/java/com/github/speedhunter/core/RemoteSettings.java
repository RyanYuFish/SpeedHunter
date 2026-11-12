package com.github.speedhunter.core;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import com.github.speedhunter.core.HookLog;

/**
 * hook 进程侧的配置读取。
 *
 * <p>实测（Nothing A024 / Android 16 / LSPosed IT v2.2.0）：</p>
 * <ul>
 *   <li>SharedPreferences 会被 LSPosed 重定向到 {@code /data/misc/apexdata/.../prefs/}，
 *       但目标进程用 {@link XSharedPreferences} 读回为空（文件 0660，目标 uid 读不了）；</li>
 *   <li>ContentProvider 被 Android 11+ 包可见性拦住（com.cfmoto 没有声明对本模块的 queries）。</li>
 * </ul>
 * <p>所以实际可用的链路是「广播 + 自落盘」：</p>
 * <ol>
 *   <li>hook 进程懒注册一个动态接收器（{@link #ACTION_CONFIG} 需换成 {@link Keys#ACTION_CONFIG}），
 *       收到 UI 发来的配置 → 内存立即生效（G1/G2 是每个响应实时读的，不用重启）；</li>
 *   <li>同时把配置写成 JSON 落到 <b>com.cfmoto 自己的外部 files 目录</b> —— 本进程写过
 *       （HookLog 就在这），一定可写；</li>
 *   <li>冷重启后从该文件读回，恢复上次配置。</li>
 * </ol>
 * <p>XSharedPreferences / ContentProvider 保留为兜底通道（其他 ROM 上可能反而好使）。</p>
 */
public final class RemoteSettings {

    private static final String TAG = HookLog.CFG;
    private static final long TTL_MS = 5000;

    /** UI → hook 的配置广播 action（= {@link Keys#ACTION_CONFIG}）。 */
    public static final String ACTION_CONFIG = Keys.ACTION_CONFIG;

    private static final Object LOCK = new Object();

    /**
     * 最近一次广播送达的值（内存，即时生效，优先级最高）。
     *
     * <p>★ 值类型统一用 String：这套 key 里既有开关（true/false）也有标定参数（浮点），
     * 用一个 {@code Map<String,Boolean>} 装不下浮点，所以全部按字符串存、
     * {@link #getBool} / {@link #getDouble} 各自解析。</p>
     */
    private static volatile Map<String, String> liveConfig;

    /** 持久化文件解析出的值（冷启动恢复用）。 */
    private static volatile Map<String, String> persisted;

    /** hook 侧引导登录请求注入的累计命中次数（供 PONG 回执，UI 据此确认真的生效了）。 */
    private static final java.util.concurrent.atomic.AtomicInteger accountInjections =
            new java.util.concurrent.atomic.AtomicInteger();

    private static long fetchedAt;
    private static String source = "<init>";
    private static String lastLoggedSource = "";
    private static boolean receiverRegistered;

    private RemoteSettings() {
    }

    public static boolean getBool(String key, boolean defaultValue) {
        return "true".equals(getString(key, String.valueOf(defaultValue)));
    }

    /**
     * 读一个浮点配置（标定参数用）。
     *
     * <p>走同一套链路：广播的 liveConfig 里存的是字符串，持久化文件里也是字符串。
     * 解析失败（NaN / 空 / 非法）一律回退默认值 —— 参数写错不该让回填整体失效。</p>
     */
    public static double getDouble(String key, double defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return (Double.isNaN(v) || Double.isInfinite(v)) ? defaultValue : v;
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    /**
     * 读一个整数配置（自定义键目标功能码等）。
     *
     * <p>与 {@link #getDouble} 同链路：解析失败 / 空值 / 非法一律回退默认值，
     * 避免脏值被写进官方 App 的字段。</p>
     */
    public static int getInt(String key, int defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    /** 读一个字符串配置；liveConfig 优先，其次持久化文件，都没有则返回 {@code null}。 */
    public static String getString(String key, String defaultValue) {
        Map<String, String> live = liveConfig;
        if (live != null && live.containsKey(key)) {
            String text = live.get(key);
            return text == null || text.isEmpty() ? defaultValue : text;
        }
        Map<String, String> map = fetch();
        String value = map.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    public static String describeSource() {
        fetch();
        return liveConfig != null ? "broadcast" : source;
    }

    /** 引导登录成功注入一次请求时由 {@code net.AccountLogin} 调用。 */
    public static void noteAccountInjection() {
        accountInjections.incrementAndGet();
    }

    /** hook 侧注入累计命中次数（PONG 回执用）。 */
    public static int accountInjectionCount() {
        return accountInjections.get();
    }

    /**
     * hook 侧「自学习」：把 App 自己保存的账号相关值记进配置（内存立即生效 + 落盘）。
     *
     * <p>典型用途：用户点设置页「我的APP首页」的「确定」时，App 会调用
     * {@code UserInfo#setHomepage(值)}；hook 借机把这个值记下来，
     * 下次冷启动伪造 {@code UserInfo} 时就能原样填回去，
     * 从而让首页落地跟随用户选的设置项，而不是回落到未登录默认的「极客」。</p>
     */
    public static void rememberAccountString(String key, String value) {
        if (key == null || value == null || value.isEmpty()) return;
        try {
            Map<String, String> live = liveConfig;
            live = live == null ? new HashMap<String, String>() : new HashMap<>(live);
            live.put(key, value);
            liveConfig = live;
            Context ctx = currentApplication();
            if (ctx != null) persistConfigToFile(ctx, new HashMap<>(live));
            HookLog.log(TAG + " remember " + key + "=" + value + " (persisted=" + (ctx != null) + ")");
        } catch (Throwable t) {
            HookLog.log(TAG + " remember " + key + " failed: " + t.getClass().getSimpleName());
        }
    }

    // ==================== 广播接收（主通道） ====================

    /**
     * 主动把配置接收器注册上（在 {@code MainHook} attach 时调用）。
     *
     * <p>默认是懒注册（第一次读配置时才注册），但投屏广播（{@link Keys#ACTION_MIRROR_START}）
     * 可能在 App 还没读过任何配置时就被用户发出来，所以 attach 阶段就先注册好，
     * 保证这类一次性指令不被漏掉。</p>
     */
    public static void ensureReceiver() {
        registerReceiverIfNeeded();
    }

    private static void registerReceiverIfNeeded() {
        if (receiverRegistered) return;
        Application app = currentApplication();
        if (app == null) return;   // attach 早期拿不到，等第一个响应时再试
        synchronized (LOCK) {
            if (receiverRegistered) return;
            try {
                IntentFilter filter = new IntentFilter(ACTION_CONFIG);
                filter.addAction(com.github.speedhunter.core.Keys.ACTION_CLEAR_DAY_STORE);
                filter.addAction(com.github.speedhunter.core.Keys.ACTION_CLEAR_LOGIN);
                filter.addAction(com.github.speedhunter.core.Keys.ACTION_QUERY_STORE_STATE);
                filter.addAction(com.github.speedhunter.core.Keys.ACTION_HOOK_PING);
                filter.addAction(com.github.speedhunter.core.Keys.ACTION_QUERY_BEND_LOG);
                filter.addAction(com.github.speedhunter.core.Keys.ACTION_MIRROR_START);
                filter.addAction(com.github.speedhunter.core.Keys.ACTION_MIRROR_STOP);
                filter.addAction(Keys.ACTION_MIRROR_QUERY);
                androidx.core.content.ContextCompat.registerReceiver(app, new ConfigReceiver(), filter,
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
                receiverRegistered = true;
                HookLog.log(TAG + " config receiver registered");
                // 反向拉取：接收器刚注册就向 UI 要一次最新配置。
                // 覆盖「用户在 SpeedHunter 里先拨开关、后打开官方 App」的时序——
                // 那条 ACTION_CONFIG 早于本接收器就绪会被静默丢弃，导致 enabled() 读默认值 false。
                requestConfigSync(app);
            } catch (Throwable t) {
                HookLog.log(TAG + " register receiver failed: " + t);
            }
        }
    }

    /**
     * hook → UI：请求 UI 把当前 SharedPreferences 经 {@link Keys#ACTION_CONFIG} 重发一遍。
     *
     * <p>必须 {@code setPackage(模块包)}：UI 进程是 {@code com.github.speedhunter}，
     * 跨进程广播不加定向包名会被 Android 8+ 的隐式广播限制拦掉。</p>
     */
    private static void requestConfigSync(Context context) {
        try {
            Intent sync = new Intent(Keys.ACTION_CONFIG_SYNC);
            sync.setPackage("com.github.speedhunter");
            context.sendBroadcast(sync);
            HookLog.log(TAG + " config sync requested (pull latest from UI)");
        } catch (Throwable t) {
            HookLog.log(TAG + " config sync request failed: " + t);
        }
    }

    private static final class ConfigReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (com.github.speedhunter.core.Keys.ACTION_CLEAR_DAY_STORE.equals(action)) {
                clearDayStore();
                return;
            }
            if (com.github.speedhunter.core.Keys.ACTION_CLEAR_LOGIN.equals(action)) {
                clearAppLogin(context);
                return;
            }
            if (com.github.speedhunter.core.Keys.ACTION_QUERY_STORE_STATE.equals(action)) {
                replyStoreState(context);
                return;
            }
            if (com.github.speedhunter.core.Keys.ACTION_HOOK_PING.equals(action)) {
                replyPong(context);
                return;
            }
            if (com.github.speedhunter.core.Keys.ACTION_QUERY_BEND_LOG.equals(action)) {
                replyBendLog(context);
                return;
            }
            if (com.github.speedhunter.core.Keys.ACTION_MIRROR_START.equals(action)) {
                com.github.speedhunter.ota.MirrorTrigger.start(context,
                        intent.getStringExtra(Keys.EXTRA_MIRROR_QR_URL),
                        intent.getStringExtra(Keys.EXTRA_MIRROR_REQUEST_ID));
                return;
            }
            if (com.github.speedhunter.core.Keys.ACTION_MIRROR_STOP.equals(action)) {
                com.github.speedhunter.ota.MirrorTrigger.stop(context, intent.getStringExtra(Keys.EXTRA_MIRROR_REQUEST_ID));
                return;
            }
            if (Keys.ACTION_MIRROR_QUERY.equals(action)) {
                com.github.speedhunter.ota.MirrorTrigger.query(context, intent.getStringExtra(Keys.EXTRA_MIRROR_REQUEST_ID));
                return;
            }
            if (!ACTION_CONFIG.equals(action)) return;
            try {
                Map<String, String> config = new HashMap<>();
                // 开关按 boolean 收，标定参数按 float 收，统一存成字符串
                put(config, intent, Keys.KEY_WEB_LAYER, true);
                put(config, intent, Keys.KEY_GATE_ANALYSE, true);
                put(config, intent, Keys.KEY_GATE_EVENT, true);
                put(config, intent, Keys.KEY_VIEW_LAYER, false);
                put(config, intent, Keys.KEY_RIDE_FILL, true);
                put(config, intent, Keys.KEY_RIDE_FULLSCAN, true);
                put(config, intent, Keys.KEY_TUNE_EXPORT, false);
                put(config, intent, Keys.KEY_CUSTOM_KEY_FORCE, false);
                putInt(config, intent, Keys.KEY_CUSTOM_KEY_TARGET, 7);
                put(config, intent, Keys.KEY_NITRO_UI, false);
                put(config, intent, Keys.KEY_ABS_UI, false);
                put(config, intent, Keys.KEY_VEHICLE_ART, false);
                // 车模 URL/主题色：extra 缺失时继承（与账号键同规矩 ——
                // 局部广播不得把用户填好的 URL 冲掉）
                putAccountString(config, intent, Keys.KEY_VEHICLE_ART_PIC);
                putAccountString(config, intent, Keys.KEY_VEHICLE_ART_PIC3D);
                putAccountString(config, intent, Keys.KEY_VEHICLE_ART_RGB);
                // 配色索引同 URL 键：extra 缺失时继承（压弯标定页等局部广播不得把
                // 用户选好的车漆配色冲回 -1）
                putAccountInt(config, intent, Keys.KEY_VEHICLE_ART_COLOR, -1);
                // ★ 账号三键必须「按 extra 存在与否」决定覆盖 or 继承：
                //   ConfigReceiver 末尾是 liveConfig = config 的【全量替换】，而
                //   TuneActivity.apply() / sendDebugSwitch() 发的是只带局部参数的
                //   ACTION_CONFIG（不带账号键）。旧实现无条件 put 默认值，任何一次
                //   压弯标定保存都会把 token / userId 冲成空、开关冲成 false ——
                //   这正是「账号页显示成功、官方 App 却没引导登录」的根因。
                putAccountBool(config, intent, Keys.KEY_ACCOUNT_LOGIN);
                putAccountString(config, intent, Keys.KEY_ACCOUNT_TOKEN);
                putAccountString(config, intent, Keys.KEY_ACCOUNT_USERID);
                // 「我的APP首页」是自学习值（保存设置时从 PUT baseInfo/others/{N} 学到）：
                // 同样必须继承，否则任何一次局部广播（开关/标定保存）都会把它冲掉，
                // 下一次冷启动首页又回落极客。
                putAccountString(config, intent, Keys.KEY_ACCOUNT_HOMEPAGE);
                put(config, intent, Keys.KEY_DEBUG_BEND, false);
                // 标定参数：extra 里缺失时不覆盖（让 RideConfig 走 DEF_* 默认值）
                putNum(config, intent, Keys.KEY_BEND_V_MIN);
                putNum(config, intent, Keys.KEY_BEND_SEED);
                putNum(config, intent, Keys.KEY_BEND_CUM_MIN);
                putNum(config, intent, Keys.KEY_BEND_CUM_CAP);
                putNum(config, intent, Keys.KEY_BEND_COEF);
                putNum(config, intent, Keys.KEY_BEND_V_MAX);
                putNum(config, intent, Keys.KEY_BEND_MIN_KM);
                putNum(config, intent, Keys.KEY_BRAKE_CALIB);
                liveConfig = config;

                // 落盘到自己的外部 files 目录（本进程一定可写，HookLog 已验证）
                File dir = context.getExternalFilesDir(null);
                if (dir != null) {
                    JSONObject json = new JSONObject();
                    for (Map.Entry<String, String> e : config.entrySet()) {
                        json.put(e.getKey(), e.getValue());
                    }
                    File file = new File(dir, Keys.CONFIG_FILE);
                    FileOutputStream out = new FileOutputStream(file);
                    try {
                        out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                    } finally {
                        out.close();
                    }
                    // ★ 日志脱敏：config 里含 Bearer token 明文，不能原样落日志
                    HookLog.log(TAG + " config via broadcast, saved " + redact(json)
                            + " -> " + file.getAbsolutePath());
                } else {
                    HookLog.log(TAG + " config via broadcast (external dir unavailable)");
                }
            } catch (Throwable t) {
                HookLog.log(TAG + " config receive failed: " + t);
            }
        }
    }

    /**
     * 回 {@link Keys#ACTION_HOOK_PONG} —— UI 用它判断「模块是否真的挂进了官方 App」。
     *
     * <p>收到 PING 本身就是证据：这条接收器只注册在 com.cfmoto 进程里，
     * 而 hook 只有被 LSPosed 注入（模块启用 + 勾选作用域）才会随进程跑起来。
     * 回执沿用反向定向广播的老规矩：{@code setPackage(模块包)}，否则 Android 8+ 收不到。</p>
     */
    private static void replyPong(Context context) {
        try {
            Intent pong = new Intent(Keys.ACTION_HOOK_PONG);
            pong.setPackage("com.github.speedhunter");
            // ★ 附上 hook 进程【实际生效】的引导登录状态。UI 只认这个，
            //   不再拿「本进程保存成功 / AccountApi 查询成功」冒充登录成功。
            String token = getString(Keys.KEY_ACCOUNT_TOKEN, "");
            String userId = getString(Keys.KEY_ACCOUNT_USERID, "");
            boolean on = getBool(Keys.KEY_ACCOUNT_LOGIN, false);
            pong.putExtra(Keys.EXTRA_ACCOUNT_READY,
                    on && !token.isEmpty() && !userId.isEmpty());
            pong.putExtra(Keys.EXTRA_ACCOUNT_TOKEN_LEN, token.length());
            pong.putExtra(Keys.EXTRA_ACCOUNT_UID_LEN, userId.length());
            pong.putExtra(Keys.EXTRA_ACCOUNT_INJECT, accountInjectionCount());
            context.sendBroadcast(pong);
            HookLog.log(TAG + " hook ping -> pong (account ready="
                    + (on && !token.isEmpty() && !userId.isEmpty())
                    + " tokenLen=" + token.length() + " inject=" + accountInjectionCount() + ")");
        } catch (Throwable t) {
            HookLog.log(TAG + " pong failed: " + t);
        }
    }

    private static void put(Map<String, String> map, Intent intent, String key, boolean def) {
        map.put(key, String.valueOf(intent.getBooleanExtra(key, def)));
    }

    /**
     * 账号键：只有这条广播<b>显式携带</b> extra 才覆盖，否则继承当前生效值。
     *
     * <p>{@code ConfigSender.pushFromPrefs()} 总是带上账号三键（清除凭证时是空串），
     * 所以保存 / 清除 / 反向同步路径都能正常改值；而压弯标定页、调试开关等
     * 只带局部参数的广播不再把凭证冲掉。</p>
     */
    private static void putAccountString(Map<String, String> map, Intent intent, String key) {
        if (intent.hasExtra(key)) {
            String value = intent.getStringExtra(key);
            map.put(key, value == null ? "" : value);
        } else {
            map.put(key, currentAccountValue(key));
        }
    }

    private static void putAccountBool(Map<String, String> map, Intent intent, String key) {
        if (intent.hasExtra(key)) {
            map.put(key, String.valueOf(intent.getBooleanExtra(key, false)));
        } else {
            map.put(key, String.valueOf("true".equals(currentAccountValue(key))));
        }
    }

    /** 账号键的 int 版：extra 缺失时继承当前生效值（缺省 def）。 */
    private static void putAccountInt(Map<String, String> map, Intent intent, String key, int def) {
        if (intent.hasExtra(key)) {
            map.put(key, String.valueOf(intent.getIntExtra(key, def)));
        } else {
            String current = currentAccountValue(key);
            int value = def;
            if (!current.isEmpty()) {
                try {
                    value = Integer.parseInt(current.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            map.put(key, String.valueOf(value));
        }
    }

    /** 当前已生效的账号值：内存优先，其次落盘文件（冷启动恢复）。 */
    private static String currentAccountValue(String key) {
        Map<String, String> live = liveConfig;
        if (live != null && live.containsKey(key)) {
            String value = live.get(key);
            return value == null ? "" : value;
        }
        return getString(key, "");
    }

    /** 日志脱敏：把 token 换成长度标记，其余键原样保留便于排查。 */
    private static String redact(JSONObject json) {
        try {
            JSONObject copy = new JSONObject(json.toString());
            String token = copy.optString(Keys.KEY_ACCOUNT_TOKEN, "");
            copy.put(Keys.KEY_ACCOUNT_TOKEN,
                    token.isEmpty() ? "" : "<redacted len=" + token.length() + ">");
            return copy.toString();
        } catch (Throwable t) {
            return "{<redacted>}";
        }
    }

    /**
     * 收一个字符串配置（登录 token / 账号 ID）。空串也写入 —— 清空凭证时
     * 必须让 hook 侧显式拿到空值，否则会残留上次落盘的 token。
     */
    private static void putString(Map<String, String> map, Intent intent, String key) {
        String value = intent.getStringExtra(key);
        map.put(key, value == null ? "" : value);
    }

    /** 收一个整数配置（自定义键目标功能码）。与前两者一样统一存成字符串。 */
    private static void putInt(Map<String, String> map, Intent intent, String key, int def) {
        map.put(key, String.valueOf(intent.getIntExtra(key, def)));
    }

    /**
     * 收一个浮点标定参数。
     *
     * <p>★ 缺失就<b>不 put</b>：这样 {@code liveConfig} 里没有这个 key，
     * {@code RideConfig} 会落到 {@code DEF_*} 默认值，而不是被 0 覆盖掉。</p>
     */
    private static void putNum(Map<String, String> map, Intent intent, String key) {
        if (!intent.hasExtra(key)) {
            return;
        }
        map.put(key, String.valueOf(intent.getFloatExtra(key, 0f)));
    }

    /**
     * 清空按天账本 —— 只由「压弯标定」页的按钮触发。
     *
     * <p>为什么必须走广播：day store 在 {@code com.cfmoto} 的 external files 下，
     * Android 11+ 不许别的 App 读那个目录（已实测 EACCES），所以 UI 进程只能发请求，
     * 由本进程（能写进去）自己删。</p>
     */
    private static void clearDayStore() {
        int n = com.github.speedhunter.ride.RideDayStore.clearAll();
        HookLog.log(TAG + " day store cleared: " + n + " files, pending="
                + com.github.speedhunter.ride.RideDayStore.pendingDayCount()
                + " days (requested by UI)");
    }

    /**
     * 退出极核 App 内登录的全部账号 —— 由「账号引导登录」页的「清除App登录状态」按钮触发。
     *
     * <p>为什么必须走广播：登录态写在 {@code com.cfmoto} 自己的存储里，别的 App 既读不到
     * 也写不进（Android 11+ 跨 App 限制，已实测 EACCES）。所以 UI 只发指令，由本进程
     * （同 UID）清掉它自己的登录 SP / MMKV，并强制把 {@code account_login} 落盘为 false，
     * 再杀掉自身让极核 App 干净重启 —— 重启后 {@code is_login} 读不到、引导登录也未启用，
     * 于是停在未登录态，便于重新套用引导登录。</p>
     *
     * <p>★ 必须 {@code commit()}（同步）而非 {@code apply()}：紧接着就 kill 进程，
     * 异步的 apply 可能来不及落盘；且 kill 前要把 {@code account_login=false} 也写进
     * 持久化 JSON，否则重启后 primeConfigAtAttach 读回的还是 true，会被引导登录立刻顶回已登录。</p>
     */
    private static void clearAppLogin(Context context) {
        try {
            Application app = currentApplication();
            if (app != null) {
                // 1) 清掉极核 App 自己的登录 SP（diag 已证实 is_login / session_id /
                //    auth02 / token_map / secret_key 等都在这份 file="cfmoto" 里）
                try {
                    android.content.SharedPreferences sp =
                            app.getSharedPreferences("cfmoto", android.content.Context.MODE_PRIVATE);
                    sp.edit().clear().commit();
                    HookLog.log(TAG + " app login SP 'cfmoto' cleared (commit)");
                } catch (Throwable t) {
                    HookLog.log(TAG + " app login SP clear failed: " + t);
                }
                // 2) best-effort：清默认 MMKV（可能有缓存的 userInfo / 登录态）
                try {
                    Class<?> mmkv = Class.forName("com.tencent.mmkv.MMKV");
                    Object def = mmkv.getMethod("defaultMMKV", int.class, String.class)
                            .invoke(null, 1, (Object) null); // SINGLE_PROCESS = 1
                    mmkv.getMethod("clearAll").invoke(def);
                    HookLog.log(TAG + " app login MMKV default cleared");
                } catch (Throwable t) {
                    HookLog.log(TAG + " app login MMKV clear skipped: "
                            + t.getClass().getSimpleName());
                }
            } else {
                HookLog.log(TAG + " app login clear skipped: application null");
            }
            // 3) 强制把 account_login=false 落盘，保证重启后引导登录不再顶回已登录
            Map<String, String> map = new HashMap<>();
            Map<String, String> src = liveConfig != null ? liveConfig : persisted;
            if (src != null) map.putAll(src);
            map.put(Keys.KEY_ACCOUNT_LOGIN, "false");
            persistConfigToFile(context, map);
            HookLog.log(TAG + " app login cleared; account_login forced false for next launch");
        } catch (Throwable t) {
            HookLog.log(TAG + " clearAppLogin failed: " + t);
        } finally {
            // 给广播/落盘一点时间，再杀掉自身让极核 App 干净重启
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /** 把一份配置 Map 序列化成 JSON 写进本进程外部 files 目录（冷启动恢复用）。 */
    private static void persistConfigToFile(Context context, Map<String, String> config) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> e : config.entrySet()) {
                json.put(e.getKey(), e.getValue());
            }
            File file = new File(dir, Keys.CONFIG_FILE);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " persistConfigToFile failed: " + t);
        }
    }

    /**
     * 回传账本状态给 UI 进程。
     *
     * <p>UI 进程读不到 {@code com.cfmoto} 的 external files（Android 11+ 跨 App 限制，
     * 已实测 EACCES），所以只能由本进程读出来再广播回去。
     * {@code setPackage} 指向模块包 —— 反向投递必须显式指定目标包，
     * 否则 Android 8+ 的隐式广播限制会让 UI 收不到（正向已经因为这个踩过坑）。</p>
     */
    private static void replyStoreState(Context context) {
        try {
            int onFile = com.github.speedhunter.ride.RideDayStore.dayCount();
            int pending = com.github.speedhunter.ride.RideDayStore.pendingDayCount();
            java.util.Set<String> months =
                    com.github.speedhunter.ride.RideDayStore.pendingMonths();
            StringBuilder sb = new StringBuilder();
            for (String m : months) {
                if (sb.length() > 0) sb.append(',');
                sb.append(m);
            }
            Intent reply = new Intent(com.github.speedhunter.core.Keys.ACTION_STORE_STATE);
            reply.putExtra(com.github.speedhunter.core.Keys.EXTRA_DAYS_ON_FILE, onFile);
            reply.putExtra(com.github.speedhunter.core.Keys.EXTRA_DAYS_PENDING, pending);
            reply.putExtra(com.github.speedhunter.core.Keys.EXTRA_MONTHS_PENDING,
                    sb.toString());
            reply.setPackage("com.github.speedhunter");
            context.sendBroadcast(reply);
            HookLog.log(TAG + " store state replied: onFile=" + onFile
                    + " pending=" + pending + " months=" + sb);
        } catch (Throwable t) {
            HookLog.log(TAG + " store state reply failed: " + t);
        }
    }

    /**
     * 把本进程日志里的 {@code BENDS} 行捞出来回传给「压弯标定」页显示。
     *
     * <p><b>为什么必须由本进程读</b>：{@code BENDS} 是 {@code BendDetector.explain()} 在
     * hook 进程里打的，落在 {@code /sdcard/Android/data/com.cfmoto/files/speedhunter_hook.log}。
     * Android 11+ 不许别的 App 读那个目录（已实测 EACCES），所以只能本进程读出来再发回去。</p>
     *
     * <p>只保留尾部若干行：判定过程每趟一行、每行最多 900 字符，一次刷一个月就是几十行。</p>
     */
    private static void replyBendLog(Context context) {
        try {
            Application app = currentApplication();
            if (app == null) {
                return;
            }
            java.io.File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            java.util.LinkedList<String> keep = new java.util.LinkedList<>();
            // .old 是轮转后的上一份，也要扫：4MB 轮转很快，BENDS 可能在旧文件里
            for (String name : new String[]{"speedhunter_hook.log", "speedhunter_hook.log.old"}) {
                java.io.File f = new java.io.File(dir, name);
                if (!f.exists() || f.length() == 0) {
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f),
                                java.nio.charset.StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("] BENDS ")) {
                            if (line.length() > 500) {
                                line = line.substring(0, 500) + "…";
                            }
                            keep.add(line);
                            if (keep.size() > 40) {
                                keep.removeFirst();
                            }
                        }
                    }
                } finally {
                    r.close();
                }
            }
            if (keep.isEmpty()) {
                return;     // 还没产生过：让 UI 保持「还没有判定日志」的占位文案
            }
            Intent reply = new Intent(com.github.speedhunter.core.Keys.ACTION_BEND_LOG);
            reply.putExtra(com.github.speedhunter.core.Keys.EXTRA_BEND_LOG,
                    String.join("\n", keep));
            reply.setPackage("com.github.speedhunter");
            context.sendBroadcast(reply);
            HookLog.log(TAG + " bend log replied: " + keep.size() + " lines");
        } catch (Throwable t) {
            HookLog.log(TAG + " bend log reply failed: " + t);
        }
    }

    // ==================== 持久化文件（冷启动恢复） ====================

    /**
     * 在 {@code handleLoadPackage} 阶段就把落盘配置读进来 —— 不依赖 {@link Application}。
     *
     * <p>★ 为什么必须这么早：官方 App 在启动极早期（Application / 登录检查）就会读
     * {@code is_login} 这个闸门 boolean；而 {@link #loadViaOwnFile()} 依赖
     * {@code ActivityThread.currentApplication()}，在 attach 阶段还是 null，
     * 于是首 4~5 秒 {@link #getBool} 只能拿到默认值 false，引导登录覆盖根本没生效，
     * App 据此判定「未登录」并把结果缓存，之后再覆盖也救不回来。</p>
     * <p>本方法用 {@link android.os.Environment#getExternalStorageDirectory()} 直接拼出外部
     * files 目录（本进程就是 {@code com.cfmoto} 的 UID，有权读自己的外部目录），让
     * {@link #ready()} 从 App 第一次读 {@code is_login} 起就是 true，覆盖立即生效。</p>
     */
    public static void primeConfigAtAttach(String packageName) {
        try {
            File dir = new File(android.os.Environment.getExternalStorageDirectory(),
                    "Android/data/" + packageName + "/files");
            File file = new File(dir, Keys.CONFIG_FILE);
            Map<String, String> map = parseConfigFile(file);
            if (map == null || map.isEmpty()) {
                HookLog.log(TAG + " prime at attach: empty/parse-fail "
                        + file.getAbsolutePath());
                return;
            }
            synchronized (LOCK) {
                persisted = map;
                liveConfig = map;   // getString 优先读 liveConfig，立即生效
                fetchedAt = SystemClock.elapsedRealtime();
            }
            HookLog.log(TAG + " prime at attach: OK size=" + map.size()
                    + " account_login=" + getBool(Keys.KEY_ACCOUNT_LOGIN, false)
                    + " tokenLen=" + getString(Keys.KEY_ACCOUNT_TOKEN, "").length()
                    + " uidLen=" + getString(Keys.KEY_ACCOUNT_USERID, "").length());
        } catch (Throwable t) {
            HookLog.log(TAG + " prime at attach failed: " + t);
        }
    }

    /** 把一个落盘 JSON 配置解析成 {@code Map<String,String>}；失败返回 null。 */
    private static Map<String, String> parseConfigFile(File file) {
        if (file == null || !file.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] bytes;
            try {
                bytes = new byte[(int) file.length()];
                int read = in.read(bytes);
                if (read <= 0) return null;
            } finally {
                in.close();
            }
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Map<String, String> map = new HashMap<>();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, String.valueOf(json.opt(key)));
            }
            return map;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, String> loadViaOwnFile() {
        try {
            Application app = currentApplication();
            if (app == null) return null;
            File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                HookLog.log(TAG + " own file: external dir null");
                return null;
            }
            File file = new File(dir, Keys.CONFIG_FILE);
            return parseConfigFile(file);
        } catch (Throwable t) {
            HookLog.log(TAG + " own file read failed: " + t);
            return null;
        }
    }

    // ==================== 兜底通道 ====================

    private static Map<String, String> loadViaXSharedPreferences() {
        try {
            XSharedPreferences prefs = new XSharedPreferences("com.github.speedhunter");
            prefs.reload();
            Map<String, ?> all = prefs.getAll();
            if (all == null || all.isEmpty()) return null;
            Map<String, String> map = new HashMap<>();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                Object value = entry.getValue();
                if (value != null) map.put(entry.getKey(), String.valueOf(value));
            }
            return map;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, String> loadViaProvider() {
        try {
            Application app = currentApplication();
            if (app == null) return null;
            ContentResolver resolver = app.getContentResolver();
            Cursor cursor = resolver.query(ConfigProvider.CONTENT_URI,
                    null, null, null, null);
            if (cursor == null) return null;
            Map<String, String> map = new HashMap<>();
            try {
                int keyIdx = cursor.getColumnIndexOrThrow(ConfigProvider.COLUMN_KEY);
                int valIdx = cursor.getColumnIndexOrThrow(ConfigProvider.COLUMN_VALUE);
                while (cursor.moveToNext()) {
                    map.put(cursor.getString(keyIdx), cursor.getString(valIdx));
                }
            } finally {
                cursor.close();
            }
            return map;
        } catch (Throwable t) {
            return null;   // Android 11+ 包可见性会拦住这条通道
        }
    }

    // ==================== 汇总 ====================

    private static Map<String, String> fetch() {
        registerReceiverIfNeeded();
        long now = SystemClock.elapsedRealtime();
        Map<String, String> snapshot = persisted;
        if (snapshot != null && now - fetchedAt < TTL_MS) return snapshot;

        synchronized (LOCK) {
            now = SystemClock.elapsedRealtime();
            snapshot = persisted;
            if (snapshot != null && now - fetchedAt < TTL_MS) return snapshot;

            Map<String, String> fresh = loadViaOwnFile();
            if (fresh != null) {
                source = "file";
            } else {
                fresh = loadViaXSharedPreferences();
                if (fresh != null) {
                    source = "xsharedprefs";
                } else {
                    fresh = loadViaProvider();
                    if (fresh != null) source = "provider";
                }
            }
            if (fresh != null) {
                persisted = fresh;
            } else if (persisted == null) {
                persisted = new HashMap<>();   // 空表：key 缺失时按 defaultValue 走
                source = "default";
            }
            // Application 还没起来（attach 早期）时所有通道都拿不到东西，
            // 只缓存 1 秒 —— 等它就绪后马上重读，别把「未就绪」的结果缓存 5 秒
            boolean appReady = currentApplication() != null;
            fetchedAt = appReady ? now : now - TTL_MS + 1000;
            if (!source.equals(lastLoggedSource)) {
                lastLoggedSource = source;
                HookLog.log(TAG + " config source = " + source
                        + " size=" + persisted.size());
            }
            return persisted;
        }
    }

    /** hook 进程里拿 Application（不需要 Context 参数）。 */
    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
