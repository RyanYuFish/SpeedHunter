package com.github.zeehospeedhunter.ota;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.Keys;
import com.github.zeehospeedhunter.core.RemoteSettings;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** In-process bridge to the official MotoPlay ViewModel. No guessed wire commands. */
public final class MirrorTrigger {
    private static final String MANAGER = "com.cfmoto.motoplay.global.MotoPlayViewModelMgr";
    private static final String PAGE = "com.cfmoto.motoplay.ui.MotoPlay2MapActivity";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<ClassLoader> OBSERVED = Collections.newSetFromMap(new WeakHashMap<>());
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static volatile Context app;
    private static volatile String requestId = "", state = "idle", message = "尚未发起投屏";
    private static MirrorRequest pending;
    private static volatile Object activeVm;
    private static volatile long generation;
    private static boolean installed, routed;

    private MirrorTrigger() { }

    public static void install() {
        if (installed) return;
        installed = true;
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                if (!Keys.TARGET_PKG.equals(a.getPackageName())) return;
                foreground = new WeakReference<>(a);
                app = a.getApplicationContext();
                RemoteSettings.ensureReceiver();
                if (pending != null) MAIN.post(() -> connectWhenReady(generation, 0));
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (foreground.get() == p.thisObject) foreground.clear();
            }
        });
    }

    public static void start(Context context, String qrUrl, String id) {
        if (!installed) return;
        MAIN.post(() -> {
            app = context.getApplicationContext();
            if (pending != null || activeVm != null) {
                publish("busy", "已有投屏会话，请先停止再重新开始", id); return;
            }
            requestId = id == null ? "" : id;
            try {
                pending = MirrorRequest.parse(qrUrl);
                checkHostPermissions();
                long g = ++generation;
                emit("accepted", "宿主已接收完整二维码；正在准备官方 MotoPlay");
                routed = false;
                // The module UI brings the exported launcher forward after this acknowledgement.
                // Navigate to the private page only from a resumed host Activity.
                MAIN.postDelayed(() -> connectWhenReady(g, 0), 500);
            } catch (Throwable t) {
                pending = null;
                emit("error", describe(t));
            }
        });
    }

    private static void connectWhenReady(long g, int attempt) {
        if (g != generation || pending == null) return;
        Activity a = foreground.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            retry(g, attempt); return;
        }
        if (!PAGE.equals(a.getClass().getName())) {
            if (!routed) {
                try {
                    Intent route = new Intent().setClassName(Keys.TARGET_PKG, PAGE)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    a.getPackageManager().getActivityInfo(route.getComponent(), 0);
                    a.startActivity(route);
                    routed = true;
                } catch (Throwable t) {
                    pending = null;
                    emit("error", "当前官方 App 缺少可用的 MotoPlay 页面（适配依据为 3.0.5）");
                    return;
                }
            }
            retry(g, attempt); return;
        }
        Object vm;
        try {
            Class<?> mgr = XposedHelpers.findClass(MANAGER, a.getClassLoader());
            Object singleton = XposedHelpers.getStaticObjectField(mgr, "INSTANCE");
            vm = XposedHelpers.callMethod(singleton, "getConnViewModel");
            // Do not construct an uninitialized ViewModel or replace the official owner.
            if (XposedHelpers.callMethod(vm, "getApplication") == null) {
                retry(g, attempt); return;
            }
        } catch (Throwable t) { retry(g, attempt); return; }
        try {
            checkHostPermissions();
            observe(a.getClassLoader());
            String url = pending.url;
            pending = null;
            activeVm = vm;
            emit("connecting", "已调用官方 P2P 连接；请确认系统配对及屏幕捕获授权");
            XposedHelpers.callMethod(vm, "connectDevice", url);
            MAIN.postDelayed(() -> {
                if (g == generation && activeVm != null && ("connecting".equals(state) || "device_found".equals(state)))
                    emit("timeout", "75秒内未收到 EC 会话确认；请检查仪表投屏页、配对及权限。可查看状态或停止重试");
            }, 75000);
        } catch (Throwable t) {
            pending = null;
            emit("error", describe(t) + "；如连接已部分启动，请点停止清理");
        }
    }

    private static void retry(long g, int attempt) {
        if (attempt >= 30) {
            pending = null;
            emit("error", "官方 MotoPlay 尚未初始化。请在极核 App 打开导航/互联页，授予权限后返回重试；未发送猜测协议");
        } else MAIN.postDelayed(() -> connectWhenReady(g, attempt + 1), 500);
    }

    private static void checkHostPermissions() {
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) throw new IllegalStateException("请开启手机 Wi-Fi");
        String permission = Build.VERSION.SDK_INT >= 33 && app.getApplicationInfo().targetSdkVersion >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("请给官方极核 App 授予" + (permission.equals(Manifest.permission.NEARBY_WIFI_DEVICES) ? "附近 Wi-Fi 设备" : "定位") + "权限");
        if (permission.equals(Manifest.permission.ACCESS_FINE_LOCATION) && Build.VERSION.SDK_INT >= 28) {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null || !lm.isLocationEnabled()) throw new IllegalStateException("请开启系统定位服务");
        }
    }

    private static void observe(ClassLoader loader) {
        if (OBSERVED.contains(loader)) return;
        Set<XC_MethodHook.Unhook> hooks = new java.util.HashSet<>();
        try {
        // Required callbacks: if this App version differs, fail clearly instead of claiming success.
        Class<?> pxc = XposedHelpers.findClass("com.cfmoto.motoplay.manager.MotoPlayConnViewModel$mPxcCallback$1", loader);
        Class<?> media = XposedHelpers.findClass("com.cfmoto.motoplay.manager.MotoPlayConnViewModel$mMediaProjectionListener$1", loader);
        hooks.addAll(requiredHook(pxc, "onReceiveClientInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (activeVm == null) return;
                if (p.hasThrowable()) event("error", "官方 EC 身份处理异常");
                else if (p.getResult() instanceof Number && ((Number) p.getResult()).intValue() == 0)
                    event("pxc_connected", "EC 车辆身份交换已完成；等待投屏通道和屏幕授权");
                else event("pairing", "EC 配对尚未确认，请处理官方 App 的车辆授权提示");
            }
        }));
        hooks.addAll(requiredHook(media, "onProjectionConnectStatusChange", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (activeVm == null) return;
                boolean on = p.args.length > 0 && Boolean.TRUE.equals(p.args[0]);
                event(on ? "projection_connected" : "disconnected", on
                        ? "官方投屏通道已连接；请以仪表实际画面确认成功"
                        : "官方投屏通道已断开，可停止后重试");
            }
        }));
        hooks.addAll(requiredHook(media, "requestMediaProjection", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (activeVm != null) event("permission", "官方已请求屏幕捕获；请在极核 App 完成系统授权");
            }
        }));
        Class<?> result = XposedHelpers.findClass("com.cfmoto.motoplay.manager.MotoPlayConnViewModel$connectDevice$resultListener$1", loader);
        hooks.addAll(requiredHook(result, "onError", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (activeVm == null) return;
                int code = p.args.length > 0 && p.args[0] instanceof Number ? ((Number) p.args[0]).intValue() : -1;
                event("error", "官方连接失败（" + code + "）；检查仪表投屏页及官方 App 的 Wi-Fi/定位权限，可停止后重试");
            }
        }));
        hooks.addAll(requiredHook(result, "onFindDevice", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (activeVm != null) event("device_found", "官方已发现二维码目标设备；正在建立连接");
            }
        }));
        OBSERVED.add(loader);
        } catch (Throwable failure) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            throw failure;
        }
    }

    private static Set<XC_MethodHook.Unhook> requiredHook(Class<?> type, String name, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(type, name, callback);
        if (hooks.isEmpty()) throw new IllegalStateException("官方回调版本不兼容");
        return hooks;
    }

    private static void event(String s, String m) {
        long g = generation;
        Object owner = activeVm;
        MAIN.post(() -> { if (g == generation && owner != null && owner == activeVm) emit(s, m); });
    }

    public static void query(Context context, String id) {
        if (!installed) return;
        MAIN.post(() -> { app = context.getApplicationContext(); publish(state, message, id); });
    }

    public static void stop(Context context, String id) {
        if (!installed) return;
        MAIN.post(() -> {
            app = context.getApplicationContext();
            requestId = id == null ? "" : id;
            ++generation; pending = null;
            Object vm = activeVm;
            Throwable stopError = null;
            if (vm != null) {
                try { XposedHelpers.callMethod(vm, "stopMirror"); }
                catch (Throwable t) { stopError = t; }
                try { XposedHelpers.callMethod(vm, "disconnectDevice", false); activeVm = null; }
                catch (Throwable t) {
                    emit("error", "官方断开失败：" + describe(t) + "；可再次点停止");
                    return;
                }
            }
            if (stopError == null) emit("stopped", "已调用官方停止及断开流程");
            else emit("stopped", "已调用官方断开；独立停止镜像失败，请确认官方 App 状态");
        });
    }

    private static String describe(Throwable t) {
        // Do not echo exception messages: official code can include QR credentials.
        Throwable cause = t.getCause() == null ? t : t.getCause();
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException)
            return cause.getMessage() != null && (cause.getMessage().startsWith("请") || cause.getMessage().startsWith("二维码") || cause.getMessage().startsWith("投屏二维码") || cause.getMessage().startsWith("需要带参数")) ? cause.getMessage() : "官方连接条件不满足";
        return "官方投屏调用失败（" + cause.getClass().getSimpleName() + "），请查看宿主日志及 App 版本";
    }
    private static void emit(String s, String m) {
        state = s; message = m;
        HookLog.log(HookLog.MIRROR + " " + s + " " + m);
        publish(s, m, requestId);
    }
    private static void publish(String s, String m, String id) {
        if (app == null) return;
        Intent reply = new Intent(Keys.ACTION_MIRROR_STATUS).setPackage("com.github.zeehospeedhunter");
        reply.putExtra(Keys.EXTRA_MIRROR_REQUEST_ID, id);
        reply.putExtra(Keys.EXTRA_MIRROR_STATE, s);
        reply.putExtra(Keys.EXTRA_MIRROR_MESSAGE, m);
        try { app.sendBroadcast(reply); } catch (Throwable ignored) { }
    }
}
