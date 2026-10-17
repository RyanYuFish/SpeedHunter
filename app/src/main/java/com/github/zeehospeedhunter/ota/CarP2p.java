package com.github.zeehospeedhunter.ota;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Public Android P2P APIs only. No remembered MAC, persistent group ID or guessed GO. */
public final class CarP2p implements AutoCloseable {
    public interface Listener {
        void onLine(String line);
        void onPeers(List<WifiP2pDevice> peers);
        void onGroup(WifiP2pInfo info, WifiP2pGroup group);
    }

    private final Context context;
    private final Handler main;
    private final Listener listener;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private boolean closed;
    private boolean scanning;
    private boolean connecting;
    private int rounds;
    /** 暴露连接态给 UI，避免在连接进行中把设备列表回退成「未扫描到设备」。 */
    public boolean isConnecting() {
        return connecting;
    }
    private final Runnable retry = this::discoverRound;
    private final Runnable connectTimeout = this::onConnectTimeout;
    private void onConnectTimeout() {
        if (!closed && connecting) {
            connecting = false;
            manager.cancelConnect(channel, action("取消超时连接"));
            listener.onLine("P2P 连接超时；请检查仪表 Key OTA 状态，然后重新扫描。");
            requestPeers();        // 刷新设备列表，便于重新点连接
        }
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            // Query framework state instead of trusting broadcast extras.
            if (closed) return;
            String a = intent.getAction();
            try {
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) requestPeers();
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) refreshGroup();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(a)) {
                    listener.onLine("Wi-Fi Direct 状态发生变化，请查看扫描/连接结果。");
                }
            } catch (SecurityException e) {
                listener.onLine("P2P 权限被撤销：" + e.getMessage());
                stopDiscovery();
            }
        }
    };

    public CarP2p(Context context, Handler main, Listener listener) {
        this.context = context;
        this.main = main;
        this.listener = listener;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) throw new IllegalStateException("手机不支持 Wi-Fi Direct");
        channel = manager.initialize(context, main.getLooper(), () -> {
            if (!closed) {
                stopDiscovery();
                connecting = false;
                listener.onLine("P2P 系统通道已断开，请退出 OTA 页面后重新进入。");
            }
        });
        if (channel == null) throw new IllegalStateException("无法初始化 P2P 系统通道");
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, f);
    }

    public void scan() {
        if (closed || connecting) return;
        stopDiscovery();
        scanning = true;
        rounds = 0;
        refreshGroup();
        listener.onLine("P2P 扫描开始（40 秒）；仪表 OTA Update 窗口常驻即可直接连。设备名与 AP SSID 是不同信息。");
        discoverRound();
    }

    private void discoverRound() {
        if (closed || !scanning) return;
        if (++rounds > 5) {
            stopDiscovery();
            listener.onLine("P2P 扫描结束；未发现设备时，检查仪表 Key OTA、手机 Wi-Fi 和定位开关。");
            return;
        }
        try {
            manager.discoverPeers(channel, action("扫描第 " + rounds + " 轮"));
            requestPeers();
            main.postDelayed(retry, 8000);
        } catch (SecurityException e) {
            stopDiscovery();
            listener.onLine("P2P 扫描缺少权限：" + e.getMessage());
        }
    }

    private void requestPeers() {
        manager.requestPeers(channel, devices -> {
            if (closed) return;
            List<WifiP2pDevice> peers = new ArrayList<>(devices.getDeviceList());
            peers.sort(Comparator.comparing((WifiP2pDevice d) -> !isCarName(d.deviceName))
                    .thenComparing(d -> d.deviceAddress == null ? "" : d.deviceAddress));
            listener.onPeers(peers);
        });
    }

    public static boolean isCarName(String name) {
        return name != null && (name.toUpperCase(java.util.Locale.ROOT).contains("ZEEHO")
                || name.toUpperCase(java.util.Locale.ROOT).contains("CFMOTO"));
    }

    public void connect(WifiP2pDevice device) {
        if (closed || connecting) return;
        if (device == null || device.deviceAddress == null) {
            listener.onLine("P2P 连接目标无效：缺少设备 MAC 地址，请重新扫描。");
            return;
        }
        // ★ 关键修正：连接前不要 stopPeerDiscovery。
        //   stopPeerDiscovery 紧接着 connect 会让 P2P 状态机在 DISCOVERING→IDLE→CONNECTING
        //   之间抢跑，很多机型上 connect 会被丢弃 / 报 BUSY，表现为「点了连接却一直未建组」。
        //   框架在 connect 时会自动停止扫描；这里只停掉本应用的自动重试轮询即可。
        stopScanning();
        doConnect(device, 0);
    }

    private void doConnect(final WifiP2pDevice device, final int attempt) {
        connecting = true;
        main.removeCallbacks(connectTimeout);
        main.postDelayed(connectTimeout, 45000);
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = 0;          // 手机当客户端，让仪表当组主（组主地址 192.168.0.1）
        config.wps.setup = WpsInfo.PBC;
        listener.onLine("P2P 请求连接 " + device.deviceName + " / " + device.deviceAddress
                + "（第 " + (attempt + 1) + " 次）；优先让仪表当组主。系统可能弹出配对确认。");
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    if (!closed) listener.onLine("系统已接受 P2P 连接请求，等待实际建组 / IP 回调。");
                }
                @Override public void onFailure(int reason) {
                    connecting = false;
                    main.removeCallbacks(connectTimeout);
                    if (!closed) listener.onLine("P2P 连接失败：" + reasonName(reason));
                    // 已知的 Android 抖动：BUSY / 内部错误时隔 2 秒自动重试一次
                    if (!closed && attempt < 1
                            && (reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR)) {
                        main.postDelayed(() -> doConnect(device, attempt + 1), 2000);
                    } else if (!closed) {
                        requestPeers();        // 刷新设备列表，便于重新点连接
                    }
                }
            });
        } catch (RuntimeException e) {
            connecting = false;
            main.removeCallbacks(connectTimeout);
            if (!closed) {
                listener.onLine("P2P 连接异常：" + e.getMessage());
                requestPeers();
            }
        }
    }

    public void refreshGroup() {
        if (closed) return;
        manager.requestConnectionInfo(channel, info -> {
            if (closed) return;
            if (info.groupFormed) {
                connecting = false;
                main.removeCallbacks(connectTimeout);
            }
            manager.requestGroupInfo(channel, group -> {
                if (!closed) listener.onGroup(info, group);
                // 连上了：顺手刷新设备行状态（标成「已连接」），不依赖下一次扫描
                if (!closed && info.groupFormed) requestPeers();
            });
        });
    }

    public void disconnect() {
        if (closed) return;
        stopDiscovery();
        connecting = false;
        main.removeCallbacks(connectTimeout);
        manager.cancelConnect(channel, action("取消 P2P 连接请求"));
        manager.removeGroup(channel, action("断开 P2P 组"));
    }

    private void stopDiscovery() {
        scanning = false;
        main.removeCallbacks(retry);
        if (!closed) {
            try { manager.stopPeerDiscovery(channel, null); } catch (RuntimeException ignored) { }
        }
    }

    /** 只停掉本应用的自动重试轮询，<b>不</b>停系统层面的扫描（连接前要用，避免与 connect 抢跑）。 */
    private void stopScanning() {
        scanning = false;
        main.removeCallbacks(retry);
    }

    private WifiP2pManager.ActionListener action(String operation) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                if (!closed) listener.onLine("P2P " + operation + "：请求已接受");
            }
            @Override public void onFailure(int reason) {
                if (!closed) listener.onLine("P2P " + operation + "：" + reasonName(reason));
            }
        };
    }

    public static String reasonName(int reason) {
        switch (reason) {
            case WifiP2pManager.ERROR: return "内部错误 (0)，检查权限、定位开关及 AP/P2P 占用";
            case WifiP2pManager.P2P_UNSUPPORTED: return "系统报告不支持 P2P (1)";
            case WifiP2pManager.BUSY: return "系统忙 (2)，等待或主动断开旧 P2P 组后重试";
            default: return "系统错误 " + reason;
        }
    }

    @Override public void close() {
        if (closed) return;
        stopDiscovery();
        if (connecting) {
            try { manager.cancelConnect(channel, null); } catch (RuntimeException ignored) { }
        }
        main.removeCallbacks(connectTimeout);
        closed = true;
        context.unregisterReceiver(receiver);
        // Keep an established group alive when leaving the page; never interrupt OTA here.
        channel.close();
    }
}
