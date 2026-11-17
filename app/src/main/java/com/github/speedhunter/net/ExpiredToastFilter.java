package com.github.speedhunter.net;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import com.github.speedhunter.core.HookLog;

/**
 * 拦截官方 App 的「登录已过期，请重新登录」toast。
 *
 * <p>2026-11-18 联想平板（com.cfmoto 3.1.0，从未官方登录过）实测：
 * ZEEHO 主账号注入完全正常（baseInfo_v2 用注入的 Bearer + 签名请求返回 200），
 * 但 App 仍反复弹这条 toast。来源是融云 IM（io.rong.push 独立进程）会话缺失——
 * 融云 token 与 ZEEHO token 是两套，未登录过的设备没有融云本地会话，
 * IM 连接被拒后 App 弹「登录已过期」。曾官方登录过的设备有融云缓存，故不弹。</p>
 *
 * <p>本 hook 只过滤<b>这一条精确文案</b>，其它 toast（含真正的鉴权失败提示）全部放行。
 * 融云 SDK 与 ZEEHO 主账号无关，拦掉不会掩盖真实登录问题。</p>
 */
public final class ExpiredToastFilter {

    private static final String TAG = HookLog.NET;
    private static final String TEXT = "登录已过期";
    private static boolean installed;

    private ExpiredToastFilter() {
    }

    public static void install() {
        if (installed) return;
        installed = true;
        // ★ 只 hook Toast#show 拦不住：官方 App 3.1.0 走的是自定义浮层
        //   （WindowManager 加 TextView），Toast#show 从未被调用过。
        //   所以同时挂三条：makeText / setText / show，任一命中都记录文案来源。
        try {
            XposedHelpers.findAndHookMethod(android.widget.Toast.class, "show",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam p) {
                            check(p, "show");
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            de.robv.android.xposed.XposedBridge.hookAllMethods(
                    android.widget.Toast.class, "setText", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam p) {
                            check(p, "setText");
                        }
                    });
        } catch (Throwable ignored) {
        }
        HookLog.log(TAG + " expired-toast filter installed");
    }

    private static void check(XC_MethodHook.MethodHookParam p, String where) {
        try {
            CharSequence cs = null;
            Object o = XposedHelpers.getObjectField(p.thisObject, "mText");
            if (o instanceof CharSequence) cs = (CharSequence) o;
            if (cs == null && p.args != null && p.args.length > 0
                    && p.args[0] instanceof CharSequence) {
                cs = (CharSequence) p.args[0];
            }
            if (cs == null) return;
            String text = cs.toString();
            if (!text.contains(TEXT)) return;
            HookLog.log(TAG + " suppress expired toast(" + where + "): " + text
                    + " @ " + caller(p));
            p.setResult(null);
        } catch (Throwable ignored) {
        }
    }

    private static String caller(XC_MethodHook.MethodHookParam p) {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("io.rong") || c.startsWith("com.cfmoto")) {
                    return c + "." + e.getMethodName();
                }
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }
}
