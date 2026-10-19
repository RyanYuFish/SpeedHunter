package com.github.zeehospeedhunter.ota;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.HookLog;

/**
 * 推送期间的前台服务。
 *
 * <h3>为什么必须前台</h3>
 * <p>{@code socket_l} 推送是 <b>106 MB / 约 26000 帧</b>，在 Wi-Fi 上要跑几十秒到几分钟。
 * Android 8.0 起后台进程随时会被回收，一旦进程死了 socket 断开，
 * 车机侧 {@code this+0x138}（已收）与 {@code this+0x90}（总长）永远不相等，
 * {@code endOta()} 不触发、文件停在半截 —— 这是<b>不可回退</b>的状态。</p>
 *
 * <p>前台服务 + 常驻通知是唯一能保证长传输不被杀的方式（也是 Android 14+ 的硬要求：
 * {@code dataSync} 类型必须声明 {@code FOREGROUND_SERVICE_DATA_SYNC}）。</p>
 */
public final class OtaPushService extends android.app.Service {

    private static final String CHANNEL_ID = "ota_push";
    private static final int NOTIF_ID = 0x0A7A;

    public static final String ACTION_START = "com.github.zeehospeedhunter.ota.PUSH_START";
    public static final String ACTION_PROBE = "com.github.zeehospeedhunter.ota.PROBE_10950";
    public static final String ACTION_WATCH_PUSH = "com.github.zeehospeedhunter.ota.WATCH_PUSH";
    public static final String ACTION_STOP = "com.github.zeehospeedhunter.ota.PUSH_STOP";

    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_ALLOW_DOWNGRADE = "allowDowngrade";
    public static final String EXTRA_AUTO_FLASH = "autoFlash";

    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_STATUS = "status";

    /** 进度回报给界面的回调（Activity 注册，Service 销毁时清空）。 */
    public interface Progress {
        void onPushLog(String line);

        void onPushProgress(int percent, long sent, long total, String note);

        void onPushDone(boolean ok, String summary);
    }

    /** 10950 持续探测回报；前台服务可在用户切到官方投屏时继续工作。 */
    public interface ProbeProgress {
        void onProbeStage(String line);

        void onProbeResult(OtaSocketPusher.VehicleInfo info);
    }

    private static volatile Progress sink;
    private static volatile ProbeProgress probeSink;
    private static volatile boolean sProbeRunning;

    private volatile boolean running;
    private volatile boolean probeRunning;
    private volatile boolean watchMode;

    private String watchTitle() {
        return watchMode ? "守候 10950 · 连上即推送" : "正在推送固件到车机";
    }

    public static void setSink(Progress p) {
        sink = p;
    }

    public static void setProbeSink(ProbeProgress p) {
        probeSink = p;
    }

    public static boolean isProbeRunning() {
        return sProbeRunning;
    }

    /** 启动前台服务并开始推送。 */
    public static void start(Context ctx, String filePath, boolean autoFlash) {
        Intent i = new Intent(ctx, OtaPushService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_FILE, filePath);
        i.putExtra(EXTRA_AUTO_FLASH, autoFlash);
        ctx.startForegroundService(i);
    }

    /**
     * <b>守候即推</b>：前台服务持续探测 10950，连上即在同一连接内完成整条推送。
     *
     * <p>10950 是短命窗口（投屏 init 主机后才有，网络一切换就被拆掉），
     * 「先探测再回界面点推送」中间隔着人的反应时间，窗口早关了。这个模式消掉那道缝。</p>
     */
    public static void startWatchPush(Context ctx, String filePath, boolean allowDowngrade,
                                      boolean autoFlash) {
        Intent i = new Intent(ctx, OtaPushService.class);
        i.setAction(ACTION_WATCH_PUSH);
        i.putExtra(EXTRA_FILE, filePath);
        i.putExtra(EXTRA_ALLOW_DOWNGRADE, allowDowngrade);
        i.putExtra(EXTRA_AUTO_FLASH, autoFlash);
        ctx.startForegroundService(i);
    }

    /** 用前台服务保活 10950 监听窗口，允许用户切换到官方投屏再退出。 */
    public static void startProbe(Context ctx, ProbeProgress progress) {
        probeSink = progress;
        sProbeRunning = true;
        Intent i = new Intent(ctx, OtaPushService.class);
        i.setAction(ACTION_PROBE);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, OtaPushService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        makeChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            running = false;
            probeRunning = false;
            sProbeRunning = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PROBE.equals(action)) {
            if (probeRunning) {
                return START_NOT_STICKY;
            }
            probeRunning = true;
            sProbeRunning = true;
            startForeground(NOTIF_ID, probeNotification("正在持续扫描 10950", "请用官方 App 投屏一次并干净退出", true));
            startProbe();
            return START_NOT_STICKY;
        }

        final String path = intent == null ? null : intent.getStringExtra(EXTRA_FILE);
        final boolean watch = ACTION_WATCH_PUSH.equals(action);
        watchMode = watch;
        if (watch) {
            startForeground(NOTIF_ID, notify(0, 0, "守候 10950…连上即自动推送"));
        } else {
            startForeground(NOTIF_ID, notify(0, 0, "准备中…"));
        }

        if (path == null) {
            done(false, "没有指定包");
            return START_NOT_STICKY;
        }
        final java.io.File f = new java.io.File(path);
        if (!f.isFile()) {
            done(false, "找不到包：" + path);
            return START_NOT_STICKY;
        }

        final OtaSocketPusher.PkgInfo known = null;
        final boolean allowDowngrade =
                intent != null && intent.getBooleanExtra(EXTRA_ALLOW_DOWNGRADE, false);
        final boolean autoFlash =
                intent != null && intent.getBooleanExtra(EXTRA_AUTO_FLASH, false);

        running = true;
        startPush(f, watch, known, allowDowngrade, autoFlash);
        return START_NOT_STICKY;
    }

    private void startProbe() {
        OtaSocketPusher.probeVehicle(new OtaSocketPusher.VehicleProbeListener() {
            @Override
            public void onStage(String line) {
                HookLog.log(HookLog.OTA + " probe-service " + line);
                ProbeProgress p = probeSink;
                if (p != null) {
                    p.onProbeStage(line);
                }
                updateProbeNotification("扫描中", line, true);
            }

            @Override
            public void onResult(OtaSocketPusher.VehicleInfo info) {
                HookLog.log(HookLog.OTA + " probe-service result " + info.summary());
                ProbeProgress p = probeSink;
                if (p != null) {
                    p.onProbeResult(info);
                }
                String title = info.ok ? "10950 已连接" : "未发现 10950";
                String text = info.ok
                        ? "已读到车机版本，可回 SpeedHunter 推送（也可直接用守候即推）"
                        : info.error;
                updateProbeNotification(title, text, false);
                probeRunning = false;
                sProbeRunning = false;
                probeSink = null;
                stopForeground(false);
                stopSelf();
            }
        });
    }

    private void startPush(final java.io.File f, final boolean watch,
                            final OtaSocketPusher.PkgInfo known, final boolean allowDowngrade,
                            final boolean autoFlash) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final OtaSocketPusher.Callback cb = new OtaSocketPusher.Callback() {
                    @Override
                    public void onLog(final String line) {
                        updateNotif(-1, -1, line);
                        Progress p = sink;
                        if (p != null) {
                            p.onPushLog(line);
                        }
                    }

                    @Override
                    public void onProgress(final int percent, final long sent, final long total) {
                        updateNotif(percent, sent, "传输中…");
                        Progress p = sink;
                        if (p != null) {
                            p.onPushProgress(percent, sent, total, "传输中…");
                        }
                    }

                    @Override
                    public void onDone(final boolean ok, final String summary) {
                        done(ok, summary);
                    }
                };
                final OtaSocketPusher.PushOptions opt = new OtaSocketPusher.PushOptions();
                opt.pkg = known;
                opt.allowDowngrade = allowDowngrade;
                opt.autoFlash = autoFlash;
                if (watch) {
                    OtaSocketPusher.pushWhenReady(f, opt, cb);
                } else {
                    OtaSocketPusher.push(f, opt, cb);
                }
            }
        }, watch ? "ota-watch-svc" : "ota-push-svc").start();
    }

    private void done(boolean ok, String summary) {
        running = false;
        Progress p = sink;
        if (p != null) {
            p.onPushDone(ok, summary);
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        probeRunning = false;
        sProbeRunning = false;
        super.onDestroy();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ 通知

    private void makeChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "OTA 固件推送",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("向车机 10950 端口推送固件包");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notify(int pct, long sent, String text) {
        Intent open = new Intent(this, com.github.zeehospeedhunter.ui.MainActivity.class);
        open.putExtra(com.github.zeehospeedhunter.ui.MainActivity.EXTRA_TAB, R.id.nav_ota);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String t = text;
        if (pct >= 0) {
            t = pct + "%  " + sent / 1048576 + "/" + (sent + (100 - pct) * 1048576L) / 1048576
                    + " MB  " + text;
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(watchTitle())
                .setContentText(t)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private Notification probeNotification(String title, String text, boolean ongoing) {
        Intent open = new Intent(this, com.github.zeehospeedhunter.ui.MainActivity.class);
        open.putExtra(com.github.zeehospeedhunter.ui.MainActivity.EXTRA_TAB, R.id.nav_ota);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(ongoing)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateProbeNotification(String title, String text, boolean ongoing) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, probeNotification(title, text, ongoing));
            } catch (Throwable ignored) {
            }
        }
    }

    private void updateNotif(int pct, long sent, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, notify(pct, sent, text));
            } catch (Throwable ignored) {
            }
        }
    }
}
