package com.github.speedhunter.net;

import de.robv.android.xposed.XC_MethodHook;

import com.github.speedhunter.core.HookLog;

/**
 * 拦截官方 App 的「登录已过期，请重新登录」浮层。
 *
 * <p><b>为什么不能 hook Toast</b>：2026-11-18 在 com.cfmoto 3.1.0（联想 L78011）
 * 实测，这条提示来自 App 内置的 XToast 库（重打包名 {@code com.zeeho.constant.toast}）：</p>
 * <pre>
 *   com.zeeho.constant.toast.ToastStrategy.handleMessage(ToastStrategy.java:129)
 *     → com.zeeho.constant.toast.ActivityToast.setText(ActivityToast.java:67)
 *       → WindowManager#addView(TextView)
 * </pre>
 * <p>它绕开 {@code android.widget.Toast}，直接用 WindowManager 贴一个 TextView，
 * 所以 {@code Toast#show/makeText/setText} 三条 hook 全部不命中。</p>
 *
 * <p><b>为什么在 setText 层拦</b>：ActivityToast 复用同一个 TextView，
 * 首次 addView 之后再次提示只更新文本、不再挂窗口 —— 只在 addView 拦会漏掉后续。
 * 这里把命中目标文案的参数替换成空串，浮层即便出现也是空白。</p>
 *
 * <p><b>边界</b>：ZEEHO 主账号本身是通的（注入 Bearer 后 {@code baseInfo_v2}
 * 返回 200、{@code account profile loaded}）。这条提示来自 App 对「本地会话不完整」
 * 的自我判断（该设备从未走过官方登录，缺 {@code token_map.RONG} 等派生凭证），
 * 属展示层问题。只遮这一条精确文案，其它提示全部放行。</p>
 */
public final class ExpiredToastFilter {

    private static final String TAG = HookLog.NET;
    private static final String TEXT = "登录已过期";
    private static boolean installed;
    private static volatile boolean reported;

    private ExpiredToastFilter() {
    }

    public static void install() {
        if (installed) return;
        installed = true;
        // 系统类可以立即挂
        hookSetText();
        hookAddView();
        // ★ XToast 的业务类（com.zeeho.constant.toast.*）在爱加密下要到
        //   Activity 起来后才可解析，attach 阶段 Class.forName 必失败。
        deferStrategyHook();
        HookLog.log(TAG + " expired-toast filter installed");
    }

    /** 延后到官方 App 第一个 Activity.onResume，再挂 XToast 的入口。 */
    private static void deferStrategyHook() {
        try {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    android.app.Activity.class, "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam p) {
                            if (strategyHooked) return;
                            Object a = p.thisObject;
                            if (!(a instanceof android.app.Activity)) return;
                            if (!"com.cfmoto".equals(((android.app.Activity) a).getPackageName())) {
                                return;
                            }
                            strategyHooked = true;
                            hookStrategy();
                        }
                    });
            HookLog.log(TAG + " toast strategy hook deferred to onResume");
        } catch (Throwable t) {
            HookLog.log(TAG + " defer strategy failed: " + t.getClass().getSimpleName());
        }
    }

    private static volatile boolean strategyHooked;

    /**
     * 首选拦截点：XToast 的 {@code ToastStrategy#handleMessage(Message)}。
     *
     * <p>这是整条提示的唯一入口 —— 拦住它就完全不会创建/更新浮层，
     * 不会有「文字没了但留一个灰色空胶囊」的残留。文本在 {@code Message.obj}
     * （XToast 的 ToastParams，字段名 {@code text}）或直接就是 CharSequence，
     * 两种都试。</p>
     */
    private static void hookStrategy() {
        try {
            // ★ 必须用目标 App 的 ClassLoader：模块自己的 loader 里没有
            //   com.zeeho.constant.toast.*（这是 App 侧重打包的 XToast）。
            Class<?> c = Class.forName("com.zeeho.constant.toast.ToastStrategy", false,
                    com.github.speedhunter.core.HookKit.classLoader());
            de.robv.android.xposed.XposedBridge.hookAllMethods(c, "handleMessage",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam p) {
                            try {
                                if (p.args == null || p.args.length == 0) return;
                                Object msg = p.args[0];
                                if (!(msg instanceof android.os.Message)) return;
                                Object obj = ((android.os.Message) msg).obj;
                                String text = textOf(obj);
                                if (text == null || !text.contains(TEXT)) return;
                                p.setResult(null);
                                if (!reported) {
                                    reported = true;
                                    HookLog.log(TAG + " blocked expired toast at strategy: " + text);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            HookLog.log(TAG + " toast strategy hooked");
        } catch (Throwable t) {
            HookLog.log(TAG + " toast strategy hook failed: " + t.getClass().getSimpleName());
        }
    }

    /** 从 Message.obj 里抠出文本：可能是 CharSequence，也可能是带 text 字段的 POJO。 */
    private static String textOf(Object obj) {
        if (obj == null) return null;
        if (obj instanceof CharSequence) return obj.toString();
        try {
            Object t = de.robv.android.xposed.XposedHelpers.getObjectField(obj, "text");
            if (t instanceof CharSequence) return t.toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 主拦截点：ActivityToast#setText（XToast 复用 view，每次提示都走这里）。 */
    private static void hookSetText() {
        try {
            de.robv.android.xposed.XposedBridge.hookAllMethods(
                    android.widget.TextView.class, "setText", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam p) {
                            try {
                                if (p.args == null || p.args.length == 0) return;
                                Object a = p.args[0];
                                if (!(a instanceof CharSequence)) return;
                                String text = a.toString();
                                if (!text.contains(TEXT)) return;
                                p.args[0] = "";
                                if (!reported) {
                                    reported = true;
                                    HookLog.log(TAG + " suppressed expired text: " + text
                                            + caller());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            HookLog.log(TAG + " setText hook failed: " + t.getClass().getSimpleName());
        }
    }

    /** 兜底：首次 addView 时文本已是目标文案，直接不挂窗口。 */
    private static void hookAddView() {
        try {
            Class<?> wmi = Class.forName("android.view.WindowManagerImpl");
            de.robv.android.xposed.XposedBridge.hookAllMethods(wmi, "addView",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam p) {
                            try {
                                if (p.args == null || p.args.length == 0) return;
                                Object v = p.args[0];
                                if (!(v instanceof android.view.View)) return;
                                String text = collect((android.view.View) v);
                                if (text == null || !text.contains(TEXT)) return;
                                p.setResult(null);
                                HookLog.log(TAG + " blocked expired overlay at addView: " + text);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            HookLog.log(TAG + " addView hook failed: " + t.getClass().getSimpleName());
        }
    }

    private static String collect(android.view.View v) {
        try {
            if (v instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) v).getText();
                if (cs != null && cs.toString().contains(TEXT)) return cs.toString();
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    String s = collect(g.getChildAt(i));
                    if (s != null) return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String caller() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.contains("Toast") || c.startsWith("com.cfmoto") || c.startsWith("com.zeeho")) {
                sb.append("\n    at ").append(c).append('.').append(e.getMethodName())
                        .append('(').append(e.getFileName()).append(':')
                        .append(e.getLineNumber()).append(')');
                if (++n >= 5) break;
            }
        }
        return sb.toString();
    }
}
