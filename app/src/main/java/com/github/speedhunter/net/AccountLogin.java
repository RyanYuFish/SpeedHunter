package com.github.speedhunter.net;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import com.github.speedhunter.core.HookKit;
import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.core.Keys;
import com.github.speedhunter.core.RemoteSettings;
import com.github.speedhunter.ride.RideFetch;

/**
 * 引导登录 —— 把车主录入的 {@code token + 账号 ID} 注入官方 App 的云端请求。
 *
 * <h3>机制（与几何面板同一套登录参数）</h3>
 * <p>几何面板从官方 App 的 H5 签到页 URL / Cookie / Header 里抓
 * {@code token} 与 {@code userId}，之后所有账号侧请求都带
 * {@code Authorization: Bearer <token>} + {@code userId=<账号ID>}。
 * 本模块反过来：由车主手动录入这两个值，在
 * {@code okhttp3.Request$Builder#build()} 之后重建请求，注入同样的两项，
 * 使挂载了 SpeedHunter 的官方 App 以该凭证访问云端 —— 多台设备填同一份
 * 凭证即可共同登录同一账号。</p>
 *
 * <h3>为什么改完 URL 要重算签名</h3>
 * <p>官方 App（及几何面板）的 {@code cfmoto-x-sign} = MD5(SHA-1(
 * sortedQuery + body + cfmoto-x-param + secret))，签名串含 sortedQuery。
 * 替换 {@code userId} 会让旧签名失效，所以复用请求已带的
 * {@code cfmoto-x-param}（nonce/timestamp 由官方 App 生成）重算签名；
 * 若此刻请求还没带签名头（由后续 Interceptor 添加），则不动，让它按新 URL 算。</p>
 *
 * <h3>本地登录态：为什么必须动态定位存储层</h3>
 * <p>官方 App 用爱加密（ijiami）把 {@code cfmoto.xml} 加密，读登录态走的是爱加密的
 * SharedPreferences <b>包装类</b>（不是标准 {@code android.app.SharedPreferencesImpl}），
 * 所以静态 hook {@code SharedPreferencesImpl} / {@code MMKV} 根本够不到真实读取层 ——
 * 这正是旧实现「网络注入成功、App 却仍显示未登录」的根因。</p>
 * <p>本实现改为：hook {@code Context.getSharedPreferences}，在它返回时拿到真实的 SP 实现类，
 * 动态给该类的 {@code getString/getBoolean/getInt/getLong} 挂上覆盖；
 * 同时对 {@code MMKV} 保留静态 hook。覆盖发生在「爱加密解密之后、App 取到值之前」，
 * 直接替换成明文即可。</p>
 *
 * <h3>边界</h3>
 * <p>opt-in，默认关；只处理 {@code zeehoev.com} 请求；不碰 VIN / 车辆身份 /
 * 请求体；不伪造登录响应。本地 MMKV/SP 登录态注入见 {@link #installPrefs()}。</p>
 */
public final class AccountLogin {

    private static final String TAG = HookLog.NET;

    /** URL 里的账号 ID 参数（大小写不敏感，兼容 userId / userid / user_id）。 */
    private static final Pattern USER_ID_PARAM = Pattern.compile(
            "(?i)([?&])(userId|userid|user_id|uid|accountId)=([^&#]*)");

    /** H5 页面 URL 里承载登录态的 token 参数（几何面板即从此抓取）。 */
    private static final Pattern TOKEN_PARAM = Pattern.compile(
            "(?i)([?&])(token|access_token|satoken|h5_token|h5Token)=([^&#]*)");

    private static final Set<String> LOGGED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    /** 防止 newBuilder().build() 递归进自己的 hook。 */
    private static final ThreadLocal<Boolean> REBUILDING = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private AccountLogin() {
    }

    public static void installAll() {
        for (final String className : RideFetch.REQUEST_BUILDERS) {
            HookKit.hookIfExists(TAG, className, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(REBUILDING.get())) return;
                    try {
                        if (!AccountOptions.ready()) return;
                        patch(param);
                    } catch (Throwable t) {
                        logOnce("inject failed: " + t);
                    }
                }
            });
        }
        installPrefs();
        AccountSession.install();
        HookLog.log(TAG + " account login installed (on=" + AccountOptions.ready() + ")");
    }

    // ==================== 本地登录态：动态定位真实存储层并覆盖 ====================

    /** 已挂过钩子的 SP 实现类（按类名去重，避免重复 hook）。 */
    private static final Set<String> SP_HOOKED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** token 类 key（注入 Bearer token）。 */
    private static final Set<String> TOKEN_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "token", "accesstoken", "access_token", "authorization", "authorization_info",
            "usertoken", "user_token", "authtoken", "auth_token", "bearer", "bearer_token",
            "logintoken", "login_token", "tokenstr", "token_raw", "satoken", "sa_token"));
    /** userId 类 key（注入账号 ID）。 */
    private static final Set<String> USER_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "userid", "user_id", "accountid", "uid", "account_id",
            "useraccount", "memberid", "member_id", "openid"));
    /**
     * 登录闸门 boolean 类 key。官方 App 冷启动靠 {@code IS_LOGIN} 一类的 boolean 决定
     * 进主界面还是登录页；旧实现只 hook 了字符串读取，所以 App 冷启动仍判定「未登录」。
     * 这里只做「让闸门通过」，不构造 userInfo 对象 —— 那些是服务端数据，
     * 硬造会让「保持原有功能不受影响」失效（显示空账号甚至空指针）。
     */
    private static final Set<String> LOGIN_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "is_login", "islogin", "isLogin", "login", "isLoginIn", "IS_LOGIN",
            "isLoggedIn", "isloggedin", "haslogin", "hasLogin", "loggedIn",
            "loginstatus", "login_status", "loginstate", "isLoginState"));

    private static void installPrefs() {
        installSpDiscovery();
        installMmkv();
    }

    /**
     * 通过 {@code getSharedPreferences} 的返回值反推 App 真实的 SP 实现类
     * （含爱加密包装类，它不继承标准 {@code SharedPreferencesImpl}）。
     */
    private static void installSpDiscovery() {
        String[] ctxClasses = {"android.content.ContextWrapper", "android.app.ContextImpl"};
        for (final String ctx : ctxClasses) {
            HookKit.hookIfExists(TAG, ctx, "getSharedPreferences", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object sp = p.getResult();
                    if (sp == null) return;
                    Class<?> c = sp.getClass();
                    String cn = c.getName();
                    if (SP_HOOKED.add(cn)) {
                        HookLog.log(TAG + " [SpDisc] sp impl=" + cn
                                + " file=" + (p.args.length > 0 ? p.args[0] : "?"));
                        hookSpImpl(c);
                    }
                }
            }, String.class, int.class);
        }
    }

    /** 给某个 SP 实现类挂上读取覆盖（同一个类只挂一次）。 */
    private static void hookSpImpl(Class<?> c) {
        hookSpMethod(c, "getString", String.class, String.class);
        hookSpMethod(c, "getBoolean", String.class, boolean.class);
        hookSpMethod(c, "getInt", String.class, int.class);
        hookSpMethod(c, "getLong", String.class, long.class);
    }

    private static void hookSpMethod(Class<?> c, String name, Class<?> k, Class<?> v) {
        try {
            XposedHelpers.findAndHookMethod(c, name, k, v, SP_READ);
        } catch (Throwable t) {
            // 某些自定义 SP 只实现了部分方法，缺失即跳过
        }
    }

    /** MMKV 是具体类，直接静态 hook。 */
    private static void installMmkv() {
        HookKit.hookIfExists(TAG, "com.tencent.mmkv.MMKV", "decodeString", SP_READ,
                String.class, String.class);
        HookKit.hookIfExists(TAG, "com.tencent.mmkv.MMKV", "decodeBool", SP_READ,
                String.class, boolean.class);
        HookKit.hookIfExists(TAG, "com.tencent.mmkv.MMKV", "decodeInt", SP_READ,
                String.class, int.class);
        HookKit.hookIfExists(TAG, "com.tencent.mmkv.MMKV", "decodeLong", SP_READ,
                String.class, long.class);
    }

    /**
     * 统一的 SP/MMKV 读取钩子：把登录态相关的 key 覆盖成本模块录入的凭证。
     * 覆盖发生在「爱加密解密之后、App 取到值之前」，直接替换明文。
     */
    private static final XC_MethodHook SP_READ = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            Object keyObj = p.args.length > 0 ? p.args[0] : null;
            if (!(keyObj instanceof String)) return;
            if (!AccountOptions.ready()) return;
            String key = ((String) keyObj).toLowerCase();
            String method = p.method.getName();
            if (method.equals("getString") || method.equals("decodeString")) {
                if (TOKEN_KEYS.contains(key)) {
                    p.setResult(AccountOptions.token());
                } else if (USER_KEYS.contains(key)) {
                    p.setResult(AccountOptions.userId());
                }
            } else if (method.equals("getBoolean") || method.equals("decodeBool")) {
                if (LOGIN_KEYS.contains(key)) {
                    p.setResult(Boolean.TRUE);
                }
            } else if (method.equals("getLong") || method.equals("decodeLong")) {
                if (USER_KEYS.contains(key)) {
                    try { p.setResult(Long.valueOf(AccountOptions.userId())); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
    };

    private static void patch(XC_MethodHook.MethodHookParam param) {
        Object request = param.getResult();
        if (request == null) return;
        Object urlObj = XposedHelpers.callMethod(request, "url");
        if (urlObj == null) return;
        String url = urlObj.toString();
        if (!url.contains("zeehoev.com")) return;

        String token = AccountOptions.token();
        String userId = AccountOptions.userId();

        // 「我的APP首页」：保存时 App 会 PUT baseInfo/others/{N}，N = 页签序号 + 1
        // （1=极车 2=服务 3=极客 4=极市 5=我的，实机双向验证）。请求体在 build 时还是
        // 0 字节、发送点也读不到明文，所以只认路径末段。
        if (url.contains("baseInfo/others")) {
            rememberHomepage(trailingSegment(url));
        }

        String newUrl = replaceParam(url, USER_ID_PARAM, userId);
        // 几何面板从 H5 签到页 URL 的 token 参数抓登录态；这里反向补写，
        // 让官方 App 打开的 H5 页面也以录入凭证加载。
        if (url.contains("h5.zeehoev.com") && isAccountPage(url)) {
            newUrl = replaceParam(newUrl, TOKEN_PARAM, token);
            if (!TOKEN_PARAM.matcher(newUrl).find()) {
                newUrl += (newUrl.contains("?") ? "&" : "?") + "token=" + token;
            }
        }
        Object builder = XposedHelpers.callMethod(request, "newBuilder");
        XposedHelpers.callMethod(builder, "header", "Authorization", "Bearer " + token);
        if (!newUrl.equals(url)) {
            XposedHelpers.callMethod(builder, "url", newUrl);
            resignIfNeeded(request, builder, newUrl);
        }
        REBUILDING.set(Boolean.TRUE);
        try {
            param.setResult(XposedHelpers.callMethod(builder, "build"));
        } finally {
            REBUILDING.set(Boolean.FALSE);
        }
        // 回执计数：UI 靠它区分「本进程保存成功」和「hook 真的注入过」
        com.github.speedhunter.core.RemoteSettings.noteAccountInjection();
        logOnce("account inject: uid=" + mask(userId)
                + " urlChanged=" + !newUrl.equals(url) + "  @ " + shortUrl(url));
    }

    /** URL 末段若是 1–5 的纯数字就返回它（{@code baseInfo/others/3} → {@code "3"}）。 */
    private static String trailingSegment(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        if (slash < 0) return null;
        String tail = path.substring(slash + 1);
        return tail.matches("[1-5]") ? tail : null;
    }

    /** 记住「我的APP首页」的选择值；与已存值相同则不动，避免无谓写盘。 */
    private static void rememberHomepage(String value) {
        if (value == null || !value.matches("[1-5]")) return;
        if (value.equals(RemoteSettings.getString(Keys.KEY_ACCOUNT_HOMEPAGE, ""))) return;
        HookLog.log(TAG + " homepage learned: " + value);
        RemoteSettings.rememberAccountString(Keys.KEY_ACCOUNT_HOMEPAGE, value);
    }

    /** 已有签名头就按新 URL 重算；没有则交给官方 App 的 Interceptor。 */    private static void resignIfNeeded(Object request, Object builder, String newUrl) {
        try {
            Object oldSign = XposedHelpers.callMethod(request, "header", "cfmoto-x-sign");
            Object paramHead = XposedHelpers.callMethod(request, "header", "cfmoto-x-param");
            if (oldSign == null || paramHead == null) return;
            String resign = AccountApi.reSign(newUrl, paramHead.toString(), "");
            if (resign != null && !resign.isEmpty()) {
                XposedHelpers.callMethod(builder, "header", "cfmoto-x-sign", resign);
            }
        } catch (Throwable ignored) {
            // 反射失败时保留原签名，不阻断请求
        }
    }

    /** 只对账号/签到类 H5 路径补 token，避免污染图片、JS 等静态资源请求。 */
    private static boolean isAccountPage(String url) {
        String p = url.toLowerCase();
        return p.contains("signin") || p.contains("integral")
                || p.contains("/baseinfo") || p.contains("/h5/");
    }

    /** 把 URL 里匹配 {@code pattern} 的参数值统一替换为 {@code value}；无匹配则原样返回。 */
    static String replaceParam(String url, Pattern pattern, String value) {
        Matcher m = pattern.matcher(url);
        StringBuffer sb = new StringBuffer();
        boolean hit = false;
        while (m.find()) {
            hit = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    m.group(1) + m.group(2) + "=" + value));
        }
        m.appendTail(sb);
        return hit ? sb.toString() : url;
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 4) return "****";
        return s.substring(0, 2) + "****" + s.substring(s.length() - 2);
    }

    private static String shortUrl(String url) {
        int scheme = url.indexOf("://");
        String path = scheme >= 0 ? url.substring(scheme + 3) : url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return path.length() <= 80 ? path : path.substring(0, 80) + "...";
    }

    private static void logOnce(String message) {
        if (LOGGED.add(message)) HookLog.log(TAG + " " + message);
    }
}
