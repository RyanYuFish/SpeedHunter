package com.github.speedhunter;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import com.github.speedhunter.core.HookKit;
import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.core.MobGuard;
import com.github.speedhunter.core.Targets;
import com.github.speedhunter.ble.BleHooks;
import com.github.speedhunter.customkey.CustomKeyForce;
import com.github.speedhunter.net.MqttHooks;
import com.github.speedhunter.net.NetPatch;
import com.github.speedhunter.ota.OtaHooks;
import com.github.speedhunter.ota.OtaSocketProbe;
import com.github.speedhunter.ota.OtaSocketTrace;
import com.github.speedhunter.ride.RideFetch;
import com.github.speedhunter.ride.RideHooks;

/**
 * SpeedHunter —— LSPosed 模块入口。
 *
 * <p>作用域只有 {@code com.cfmoto}（官方 App）。目标 App 的业务代码被爱加密加固，
 * 反编译拿不到类名，因此所有 hook 都建立在「加固后依然稳定」的锚点上：
 * Manifest 里的组件类名、layout 里的控件资源名、公开库的类名。
 * 任何一个锚点失效只会让对应功能变成 no-op，不会让模块整体崩溃。</p>
 *
 * <p>主要能力：</p>
 * <ul>
 *   <li>{@code net.NetPatch} —— <b>主机制</b>：改网络层的车型能力位
 *       （vehicleKinds / cyclingEventStatisticFlag），让 App 自己显示骑行分析入口与
 *       极速/急加速/急刹/压弯；这条线来自 iOS 端 8 轮消融的结论，与 Shadowrocket
 *       脚本 {@code VehicleType.min.js} 处理的是同一组字段。</li>
 *   <li>{@link RideHooks} —— 视图层兜底（实时开关，默认关，见 {@code ride.RideOptions}）；</li>
 *   <li>{@link OtaHooks} —— OTA 界面解锁 + 全链路流量记录（只读，不伪造服务端数据）；</li>
 *   <li>{@link BleHooks} —— BLE 车控帧双向捕获。</li>
 *   <li>{@link com.github.speedhunter.customkey.CustomKeyForce} —— 自定义键强制为「切换信息组
 *       （功能码 7）」，让仪表主页按自定义键在里程组 ⇄ 胎压组间切换（opt-in，默认关；
 *       来源 DashProbe v0.6.0，2026-10-28 实车验证通过）。</li>
 *   <li>{@link OtaChannelProbe} —— 车机 OTA 通道知识表 + 只读探针。2026-10-05 车边实测
 *       确认：官方任务单是本模块注入伪造的，车机 TCP {@code 10950} 并不监听，
 *       真实通道是 {@code /dev/cfmoto_cfcp}（CFCP，见 {@link com.github.speedhunter.ota.OtaChannels}）。
 *       本探针只打印结论与探测类是否存在，<b>不下发任何指令</b>。</li>
 * </ul>
 *
 * <p>日志出口见 {@link HookLog} —— 这套 LSPosed 不把日志写进 logcat，所以要读文件。</p>
 */
public final class MainHook implements IXposedHookLoadPackage {

    /**
     * 落盘注入心跳：{@code "<pid>,<epochMillis>,<versionName>"}。
     *
     * <p>UI 侧据此判定「模块确实注入过 com.cfmoto」，不要求官方 App 此刻在运行 ——
     * 这正是「作用域勾对了、但 App 没开就显示未挂载」的修复点。</p>
     */
    private void writeAttachHeartbeat() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "Android/data/" + com.github.speedhunter.core.Keys.TARGET_PKG + "/files");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String version = "?";
            try {
                version = de.robv.android.xposed.XposedBridge.class.getPackage() != null
                        ? "xposed" : "?";
            } catch (Throwable ignored) {
            }
            String payload = Process.myPid() + "," + System.currentTimeMillis() + "," + version;
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new java.io.File(dir, com.github.speedhunter.core.Keys.HOOK_ALIVE_FILE));
            try {
                out.write(payload.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把依赖真实 dex 的安装推迟到首个 {@code com.cfmoto} Activity.onResume。
     *
     * <p>锚点与 {@code net.AccountSession} 一致（Activity.onResume 框架方法，爱加密
     * 不搬）。只触发一次；任一安装抛异常只记日志，不牵连队列里其它项。若框架
     * hook 本身装不上（理论不可能），兜底立即执行，行为退回 attach 期安装。</p>
     */
    private static void deferUntilRealDexReady(final String what, final Runnable install) {
        try {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    android.app.Activity.class, "onResume", new de.robv.android.xposed.XC_MethodHook() {
                private boolean fired;
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (fired) return;
                    android.app.Activity a = (android.app.Activity) p.thisObject;
                    if (!Targets.PACKAGE.equals(a.getPackageName())) return;
                    fired = true;
                    try {
                        HookLog.log("[Init] real-dex ready -> install " + what
                                + " @ " + a.getClass().getName());
                        install.run();
                    } catch (Throwable t) {
                        HookLog.log("[Init] deferred install failed (" + what + "): " + t);
                    }
                }
            });
        } catch (Throwable t) {
            HookLog.log("[Init] defer failed (" + what + "): " + t.getClass().getSimpleName());
            install.run();   // 兜底：仍按老时机试一次
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!Targets.PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        HookKit.setClassLoader(lpparam.classLoader);
        // 多进程防冲刷：只有主进程写 speedhunter_config.json，
        // 融云推送等附属进程只读（它们共享同一目录，写入会把有效凭证冲成默认值）。
        com.github.speedhunter.core.RemoteSettings.setConfigWriter(
                lpparam.packageName.equals(lpparam.processName));
        HookLog.init(lpparam.packageName,
                lpparam.appInfo == null ? null : lpparam.appInfo.dataDir);

        // 首行日志：pid 用来对齐进程，两个路径用来确认日志到底写去哪了
        HookLog.log("========== " + HookLog.MODULE + " attached: pid=" + Process.myPid()
                + " pkg=" + lpparam.packageName
                + " " + HookLog.describeWriters() + " ==========");

        writeAttachHeartbeat();

        // ★ 挂载检测（PING/PONG）必须最先装：ConfigReceiver 在 Application 就绪后才注册，
        //   而 primeConfigAtAttach 把 liveConfig 填满后 getString 不再走 fetch()，
        //   唯一主动注册点 MirrorTrigger 又只装在主进程 —— 两侧都漏就永远不回 PONG，
        //   UI 一直显示「未挂载」（即使作用域已勾对）。这里在所有进程、所有 Activity 之前
        //   挂 Application.attachBaseContext，拿到 Context 立刻注册。
        if (lpparam.packageName.equals(lpparam.processName)) {
            try { com.github.speedhunter.ota.MirrorTrigger.install(); }
            catch (Throwable t) { HookLog.log(HookLog.MIRROR + " bridge install failed: " + t.getClass().getSimpleName()); }
        }
        MobGuard.install(lpparam);   // 先保命：规避 MobTech SDK 的启动崩溃
        // ★ 爱加密 3.1.0（2026-11-18 真机取证）：真实 dex 要到首个 Activity.onResume
        //   才解密挂进 app classloader —— attach 期 Class.forName("okhttp3.*") /
        //   com.cfmoto.* 必 ClassNotFound，hookIfExists 记一行 skip 后<b>永不重试</b>，
        //   于是 NetPatch/RideFetch/OTA/MQTT/车漆全部永久漏挂：日志里 [Net] patched
        //   与 [Ride] fill 全零、回填从未执行（v5.2.0~v5.2.2「门控失效」的真正根因，
        //   三轮门控改的代码根本没跑到）。与 AccountSession 同锚点延迟安装
        //   （该锚点已实车验证稳定：AppContext 会话 hook 就是这么挂上的）。
        deferUntilRealDexReady("RideFetch", new Runnable() { public void run() {
            RideFetch.installAll();      // 请求侧：把 ridetrack_v2 的 pageSize 放大到 200
        }});
        deferUntilRealDexReady("NetPatch", new Runnable() { public void run() {
            NetPatch.installAll();       // 主机制：网络层能力位改写 + 急刹/压弯回填
        }});
        // 车模替换 v2：模型加载后应用用户选的车漆配色（ZeehoModelProcessor#onModelLoaded）
        deferUntilRealDexReady("ArtColor", new Runnable() { public void run() {
            com.github.speedhunter.net.VehicleArtUnlock.installColorHook();
        }});
        // 车模本地上传：官方 App 进程内的 loopback 资产服务器（模块 UI POST 上来，App 加载器来拉）
        if (lpparam.appInfo != null && lpparam.appInfo.dataDir != null) {
            com.github.speedhunter.net.AssetServer.start(lpparam.appInfo.dataDir);
        }
        // ★ 在 attach 阶段就把落盘配置读进内存，让 ready() 从 App 第一次读 is_login 起就是 true
        //   （否则引导登录覆盖要等 Application 就绪后约 4~5 秒才生效，App 早已据此判定未登录并缓存）
        com.github.speedhunter.core.RemoteSettings.primeConfigAtAttach(lpparam.packageName);
        com.github.speedhunter.net.AccountLogin.installAll();  // 引导登录（opt-in，默认关）
        // 从未官方登录过的设备：融云 IM 会话缺失会反复弹「登录已过期」，
        // 与 ZEEHO 主账号无关（主账号请求实测 200）。只拦这一条精确文案。
        if (lpparam.packageName.equals(lpparam.processName)) {
            com.github.speedhunter.net.ExpiredToastFilter.install();
        }
        deferUntilRealDexReady("Mqtt", new Runnable() { public void run() {
            MqttHooks.installAll();  // ★ MQTT broker/凭据/topic 捕获（只读；paho 被改包名）
        }});
        RideHooks.installAll();      // 视图层兜底（默认关；只挂框架类，attach 期安全）
        deferUntilRealDexReady("Ota", new Runnable() { public void run() {
            OtaHooks.installAll();   // OTA 流量记录也勾 okhttp3 响应体，同样要等真实 dex
        }});
        BleHooks.installAll();
        CustomKeyForce.installAll(); // 自定义键强制 = 切换信息组（opt-in，默认关）
        OtaSocketTrace.install();    // ★ 记录官方 EasyConnect 与车机 10950 的往来帧（只读）
        OtaSocketProbe.runAsync();   // ★ 主动 connect 192.168.0.1:10950（只握手，不发任何字节）

        // 2026-10-17 已移除（结论已固化在 docs/53~55，不再维护）：
        //   OtaChannelProbe / EcSdkConfigProbe —— 通道知识表与 FTP/P2C 参数，逆向已收尾
        //   OtaMdnsProbe / OtaMdnsRawProbe —— EC PXC 免投屏路已判死（NsdManager 在 API 36 被拒，
        //      车机只回连官方 App 投屏建立的端口，见 docs/52）
        //   PatchServer —— 手机侧 HTTP 等车机来拉包，协议换成 socket_l 10950 后无人调用
        //   OtaFlash + AdbPush —— P2P+ADB 推送已判死（adb 端口不监听，见 docs/33）
    }
}
