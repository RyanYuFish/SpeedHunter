package com.github.zeehospeedhunter.ui;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.activity.ComponentActivity;

import com.github.zeehospeedhunter.ota.CarP2p;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * Wi-Fi Direct 连接面板（扫描 / 设备列表 / 连接 / 断开）—— 三页（OTA / 投屏 / 车端诊断）共用。
 *
 * <h3>为什么抽出来</h3>
 * <p>车机地址不固定（组主在 {@code 192.168.0.1} 与 {@code 192.168.49.1} 之间浮动），
 * 「先建 P2P 组、再按组主地址探测端口」是模块内<b>已有的</b>连接渠道（OTA 推包走的就是它）。
 * 诊断页原先只有「去系统设置连热点」一条路，这里把它补齐成第二条路，两条路互为兜底。</p>
 *
 * <p>权限与 WLAN / 定位开关的前置检查交给 {@link P2pAccess}，扫描与建组交给 {@link CarP2p}；
 * 本类只负责渲染设备列表并把结果回吐给宿主。</p>
 *
 * <h3>红线</h3>
 * <p>只做发现与建组，<b>不发任何业务帧</b>；是否可用仍以端口握手 / 业务响应为准。</p>
 */
public final class P2pPanel {

    /** 宿主回调。 */
    public interface Callback {
        /** 日志一行（宿主自己的日志区）。 */
        void onLog(String s);

        /** 状态区文案（扫描中 / 已连接 / 失败原因）。 */
        void onStatus(String s);

        /** P2P 组已建成；{@code go} 为组主地址（拿不到时为 "?"）。 */
        void onGroup(String go);
    }

    private final ComponentActivity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout list;
    private final Callback cb;
    private final P2pAccess access;
    private CarP2p p2p;
    private boolean closed;

    public P2pPanel(ComponentActivity act, LinearLayout peerList, Callback cb) {
        this.act = act;
        this.list = peerList;
        this.cb = cb;
        this.access = new P2pAccess(act, s -> {
            cb.onStatus(s);
            cb.onLog(s);
        });
    }

    /** 扫描附近车机（走 P2pAccess 的权限 / WLAN / 定位前置检查）。 */
    public void scan() {
        if (closed) return;
        access.run(() -> {
            cb.onStatus("Wi-Fi Direct 扫描中…");
            cb.onLog("=== Wi-Fi Direct 扫描仪表（约 40 秒）===");
            ensureP2p().scan();
        });
    }

    /** 断开当前 P2P 组。 */
    public void disconnect() {
        if (closed) return;
        if (p2p == null) {
            cb.onStatus("尚未做过 P2P 扫描，无组可断。");
            return;
        }
        p2p.disconnect();
        cb.onStatus("已请求断开 P2P。");
        cb.onLog("已请求断开 Wi-Fi Direct 组。");
    }

    /** 宿主页 {@code onResume} 里转调（从系统设置返回时继续排队中的扫描）。 */
    public void onResume() {
        if (!closed) access.onResume();
    }

    /** 宿主页 {@code onDestroy} 里转调。 */
    public void close() {
        closed = true;
        access.close();
        if (p2p != null) {
            p2p.close();
            p2p = null;
        }
    }

    private CarP2p ensureP2p() {
        if (p2p == null) {
            p2p = new CarP2p(act, main, new CarP2p.Listener() {
                @Override
                public void onLine(String line) {
                    cb.onLog(line);
                }

                @Override
                public void onPeers(List<WifiP2pDevice> peers) {
                    showPeers(peers);
                }

                @Override
                public void onGroup(WifiP2pInfo info, WifiP2pGroup group) {
                    // ① CarP2p.scan() 会先 refreshGroup()，系统里若留着上一次的组会立刻回调这里；
                    //    没有组主地址就说明组没真正成型，不能报「已连接」（否则界面会误导）。
                    if (info == null || info.groupOwnerAddress == null) {
                        cb.onLog("收到 P2P 组信息但没有组主地址（可能是上次的残留组），"
                                + "继续等待本次连接结果。");
                        return;
                    }
                    // ★ groupOwnerAddress 是 InetAddress，不是 String（直接三元会编译失败）
                    final String go = info.groupOwnerAddress.getHostAddress();
                    cb.onStatus("P2P 已连接 · 组主 " + go);
                    cb.onLog("P2P 组已建成，组主 " + go + "。");
                    // ② 端口探测是阻塞的 TCP 握手，**不能在这里做**（此处是主线程）。
                    //    交给宿主页自己跑异步的网络诊断，由诊断结果决定是否可用。
                    cb.onGroup(go);
                }
            });
        }
        return p2p;
    }

    private void showPeers(List<WifiP2pDevice> peers) {
        list.removeAllViews();
        if (peers == null || peers.isEmpty()) return;
        int shown = 0;
        for (final WifiP2pDevice d : peers) {
            // 只显示车机（名称匹配），避免把邻居的打印机/电视列出来
            if (!CarP2p.isCarName(d.deviceName)) continue;
            shown++;
            MaterialButton b = new MaterialButton(act, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(d.deviceName + (d.deviceAddress == null ? "" : "\n" + d.deviceAddress));
            b.setGravity(Gravity.CENTER);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                cb.onLog("连接 " + d.deviceName + " …");
                cb.onStatus("正在连接 " + d.deviceName + " …");
                ensureP2p().connect(d);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (6 * act.getResources().getDisplayMetrics().density);
            list.addView(b, lp);
        }
        if (shown == 0 && !peers.isEmpty()) {
            cb.onLog("扫到 " + peers.size() + " 个 P2P 设备，但没有名称匹配车机的；"
                    + "确认仪表处在 OTA Update / 投屏等待界面。");
        }
    }
}
