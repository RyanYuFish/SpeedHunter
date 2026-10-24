package com.github.speedhunter.ui;

import android.content.Intent;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.github.speedhunter.R;
import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.ota.CarNet;
import com.github.speedhunter.ota.CarP2p;
import com.github.speedhunter.ota.EcPxc;
import com.github.speedhunter.ota.OtaP2pToolPusher;
import com.github.speedhunter.ota.OtaPackVerify;
import com.github.speedhunter.ota.OtaPushService;
import com.github.speedhunter.ota.OtaSocketPusher;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

/**
 * OTA 操作台 —— 信息结构对齐参考工具（仪表 OTA 工具）的五段式：
 *
 * <pre>
 *   ① 选择升级包  SAF 选包 + 六项检查自检（不过就禁推）
 *   ② 连接仪表    Tab：Wi-Fi Direct 扫描 / 手动指定 IP
 *   ③ 车机信息    设备名·组主地址·本机角色·10950 状态
 *   ④ 推送与刷写  进度 + 开始OTA流程 + 「传完自动触发刷写」开关
 *   ⑤ 运行日志    带时间戳，可复制
 * </pre>
 *
 * <p>★ 2026-10-06 那次<b>变砖</b>：协议层全通、车机确实进了刷写，但包内部不自洽
 * ⇒ U-Boot 逐分区回读 md5 时 exit -1 ⇒ 主机起不来，仪表报废返厂。
 * 车机只提示最前面那道 MD5SUM*，后三道失败时仪表毫无信息（进度条停住、不报错）
 * ⇒ 六项检查必须在手机上跑完才允许推送。这是刷自制固件唯一的护栏。</p>
 */
public final class OtaFragment extends BaseTabFragment
        implements EcPxc.LineSink, EcPxc.Progress, EcPxc.DoneCallback, CarNet.DiagListener,
        OtaPushService.Progress, OtaPushService.ProbeProgress {

    @Override
    protected int getLayoutId() {
        return R.layout.activity_ota;
    }

    private static final int LOG_MAX_CHARS = 60000;

    private TextView tvLog;
    private TextView tvPkg;
    private TextView tvPkgInspect;
    private TextView tvLinkStatus;
    private TextView tvStatus;
    private TextView tvProgress;
    private TextView tvWifi;
    private TextView tvP2pStatus;
    private TextView tvLinkHelp;
    private TextView tvAutoflashHint;
    private TextView tvSubtitle;
    private com.google.android.material.progressindicator.LinearProgressIndicator pb;
    private View btnPick, btnSha256, btnOpenWifi, btnDiag;
    /** 守候即推按钮 —— 连上仪表后自动等服务 OPEN 并推包（主通道的一键形态）。 */
    private View btnP2pToolWatch;
    /** 10086 LOG 只读取证按钮 —— 判定「刷写为何没发生」的唯一手段。 */
    private View btnP2pToolLog;
    private View btnP2pScan, btnP2pDisconnect;
    private RadioGroup rgHost;
    private View boxP2p, boxManual;
    private LinearLayout boxPeers, boxCarInfo;
    private com.google.android.material.tabs.TabLayout tabLink;
    private com.google.android.material.materialswitch.MaterialSwitch swAutoflash;

    private CarP2p carP2p;
    private P2pAccess p2pAccess;
    private WifiP2pDevice selectedP2p;
    /** P2P 建组后的组主地址；非空时优先于手动单选。 */
    private volatile String p2pGroupOwner;
    private volatile boolean p2pConnected;
    /** 0=未探测 1=OPEN 2=CLOSED/拒连。10086 与 10950 必须分开显示。 */
    private volatile int keyOtaPortState;
    private volatile int otaChannelState;
    /** 防止 onGroup 反复回调时重复探测 10086。 */
    private volatile boolean probingKeyOta;

    private Handler main;
    private final StringBuilder log = new StringBuilder();
    private volatile File picked;
    private volatile OtaSocketPusher.PkgInfo pickedInfo;
    private volatile boolean busy;
    private volatile boolean probeActive;
    /** 10086 守候即推进行中（连上仪表后轮询 10086 OPEN → 自动推包）。 */
    private volatile boolean p2pToolWatching;

    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPicked);

    @Override
    public void onViewCreated(View root, Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);
        BackGestureCompat.install(requireActivity());
        EdgeToEdge.applyTop(root);
        main = new Handler(Looper.getMainLooper());

        tvLog = root.findViewById(R.id.tv_log);
        tvPkg = root.findViewById(R.id.tv_pkg);
        tvPkgInspect = root.findViewById(R.id.tv_pkg_inspect);
        tvLinkStatus = root.findViewById(R.id.tv_link_status);
        tvStatus = root.findViewById(R.id.tv_status);
        tvProgress = root.findViewById(R.id.tv_progress);
        tvWifi = root.findViewById(R.id.tv_wifi);
        tvP2pStatus = root.findViewById(R.id.tv_p2p_status);
        tvLinkHelp = root.findViewById(R.id.tv_link_help);
        tvAutoflashHint = root.findViewById(R.id.tv_autoflash_hint);
        tvSubtitle = root.findViewById(R.id.tv_subtitle);
        pb = root.findViewById(R.id.pb_ota);
        btnPick = root.findViewById(R.id.btn_pick);
        btnSha256 = root.findViewById(R.id.btn_sha256);
        btnOpenWifi = root.findViewById(R.id.btn_open_wifi);
        btnDiag = root.findViewById(R.id.btn_diag);
        btnP2pToolWatch = root.findViewById(R.id.btn_p2ptool_watch);
        btnP2pToolLog = root.findViewById(R.id.btn_p2ptool_log);
        btnP2pScan = root.findViewById(R.id.btn_p2p_scan);
        btnP2pDisconnect = root.findViewById(R.id.btn_p2p_disconnect);
        rgHost = root.findViewById(R.id.rg_host);
        boxP2p = root.findViewById(R.id.box_p2p);
        boxManual = root.findViewById(R.id.box_manual);
        boxPeers = root.findViewById(R.id.box_peers);
        boxCarInfo = root.findViewById(R.id.box_carinfo);
        tabLink = root.findViewById(R.id.tab_link);
        swAutoflash = root.findViewById(R.id.sw_autoflash);

        probeActive = OtaPushService.isProbeRunning();
        if (probeActive) {
            OtaPushService.setProbeSink(this);
        }

        setupLogScrolling(tvLog, root);
        setupLinkTabs();
        renderCarInfo();      // 先渲染占位行，避免"连接了但信息区空白"

        root.findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());
        root.findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());
        root.findViewById(R.id.btn_bluetooth).setOnClickListener(v -> openBluetoothSettings());

        // ① 选包
        btnPick.setOnClickListener(v -> pickFile.launch("*/*"));
        btnSha256.setOnClickListener(v -> calcSha256());

        // ② 连接仪表
        if (rgHost != null) {
            rgHost.setOnCheckedChangeListener((g, id) -> {
                append("车机地址 → " + carHost());
                renderCarInfo();
            });
        }
        btnOpenWifi.setOnClickListener(v -> CarNet.openSystemWifiSettings(requireActivity()));
        btnDiag.setOnClickListener(v -> {
            append("=== 网络诊断 ===");
            CarNet.diagnoseAsync(requireContext(), this);
        });
        p2pAccess = new P2pAccess(requireActivity(), line -> {
            append("P2P：" + line);
            if (!p2pConnected) tvP2pStatus.setText(line);
        });
        btnP2pScan.setOnClickListener(v -> requestP2pScan());
        btnP2pDisconnect.setOnClickListener(v -> {
            if (carP2p != null) {
                carP2p.disconnect();
            }
        });

        // ④ 推送与刷写 —— p2pTool 是实测唯一可用通道，socket_l 入口已全部移除
        if (btnP2pToolWatch != null) {
            btnP2pToolWatch.setOnClickListener(v -> confirmP2pToolWatchPush());
        }
        if (btnP2pToolLog != null) {
            btnP2pToolLog.setOnClickListener(v -> p2pToolLog());
        }
        swAutoflash.setOnCheckedChangeListener((b, on) -> {
            tvAutoflashHint.setText(on
                    ? "开启：传完自动触发刷写（不可逆）"
                    : "关闭：仅推送 OTA 包到车机，不触发刷写");
            append((on ? "已开启" : "已关闭") + "「传完自动触发刷写」");
        });

        initCarP2p();

        // ★ 不自动预填任何包（2026-10-17 移除）：刷写不可逆，必须由用户明确 SAF 选包。
        tvPkg.setText("尚未选择升级包");
        // 远程驱动（车边调试 / adb 一键跑）由宿主经 onExternalIntent 转发进来
    }

    // ==================== 连接：Tab 与设备列表 ====================

    /** 两个 Tab 只切「面板可见性」，不重建视图，扫描结果不会因切换而丢。 */
    private void setupLinkTabs() {
        tabLink.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout
                .OnTabSelectedListener() {
            @Override
            public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                boolean p2p = tab.getPosition() == 0;
                boxP2p.setVisibility(p2p ? View.VISIBLE : View.GONE);
                boxManual.setVisibility(p2p ? View.GONE : View.VISIBLE);
                tvLinkHelp.setText(p2p
                        ? "仪表 OTA Update 窗口常驻时可直接扫描连接（10086 服务随之常开）。"
                        + "设备名与 AP 的 SSID 是两套命名，扫到即可连。"
                        : "先在系统设置连仪表 Wi-Fi（车机 AP），再回到这里选地址。"
                        + "本车 Realtek 组主是 192.168.0.1，不是标准的 192.168.49.1。");
                // P2P 连上后就不再用手动地址，避免两套地址互相打架
                if (p2pConnected && p2p) {
                    append("P2P 已建组（组主 " + p2pGroupOwner + "），推送会用组主地址，忽略手动单选");
                }
            }

            @Override
            public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {
            }
        });
    }

    private void requestP2pScan() {
        if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
        if (p2pAccess == null) return;
        p2pAccess.run(() -> {
            if (carP2p == null) initCarP2p();
            if (carP2p != null) {
                append("=== 扫描仪表 Wi-Fi Direct（只建网络，不发 OTA） ===");
                tvP2pStatus.setText("扫描中，等待仪表发现结果…");
                // ★ 状态必须三处一起刷：只刷 P2P 状态行的话，下面「连接仪表」与
                //   「车机信息」两张卡会一直停在旧值 —— 表现就是「点了扫描但状态没动」。
                if (tvLinkStatus != null && !p2pConnected) {
                    tvLinkStatus.setText("扫描中…");
                }
                renderCarInfo();
                carP2p.scan();
            }
        });
    }

    private void initCarP2p() {
        try {
            carP2p = new CarP2p(requireContext(), main, new CarP2p.Listener() {
                @Override
                public void onLine(String line) {
                    append("P2P：" + line);
                    if (!p2pConnected && (line.contains("缺少权限") || line.contains("扫描结束")
                            || line.contains("扫描失败") || line.contains("通道已断开"))) {
                        tvP2pStatus.setText(line);
                    }
                }

                @Override
                public void onPeers(List<WifiP2pDevice> peers) {
                    renderPeers(peers);
                }

                @Override
                public void onGroup(WifiP2pInfo info, WifiP2pGroup group) {
                    if (!info.groupFormed) {
                        p2pConnected = false;
                        p2pGroupOwner = null;
                        keyOtaPortState = 0;
                        refreshDisconnectButton();
                        renderCarInfo();
                        return;
                    }
                    String owner = info.groupOwnerAddress == null
                            ? null : info.groupOwnerAddress.getHostAddress();
                    boolean sameGroup = p2pConnected && p2pGroupOwner != null
                            && p2pGroupOwner.equals(owner);
                    p2pConnected = true;
                    p2pGroupOwner = owner;
                    OtaSocketPusher.setGroupOwner(owner, info.isGroupOwner);
                    refreshDisconnectButton();
                    renderCarInfo();

                    // ★ 把「已建组」明确写到状态行上。
                    String net = group == null || group.getNetworkName() == null
                            ? "" : "（" + group.getNetworkName() + "）";
                    tvP2pStatus.setText("✔ 已建组 " + net + " · 组主 " + owner
                            + (info.isGroupOwner ? "（手机）" : "（仪表）"));
                    tvLinkStatus.setText("已连接，仪表地址 " + owner);

                    if (sameGroup) {
                        // 系统会重复回调，别重复打日志/重复探测
                        return;
                    }
                    append("P2P：建组成功，组主 " + owner
                            + "（" + (info.isGroupOwner ? "手机" : "仪表") + "当组主）");
                    probeKeyOtaOnce(owner);
                }
            });
            // ★ 不要在这里把「断开」置灰：P2P 组可能早就存在（冷启动系统自动恢复）。
            //   真正的状态由 onGroup 按 groupFormed 维护。
        } catch (Throwable t) {
            carP2p = null;
            append("P2P 初始化失败：" + t.getMessage() + " —— 可改用「手动指定 IP」页签");
        }
    }

    /**
     * 渲染设备列表。<b>整行可点</b>，并把设备名/MAC/状态分开显示。
     */
    private void renderPeers(List<WifiP2pDevice> peers) {
        if (boxPeers == null) {
            return;
        }
        boxPeers.removeAllViews();
        if (peers.isEmpty()) {
            if (carP2p != null && carP2p.isConnecting()) {
                // ★ 连接进行中：系统可能短暂回空列表，此时不能回退成「未扫描到设备」
                tvP2pStatus.setText("正在连接仪表，请稍候…");
                renderCarInfo();
                return;
            }
            if (p2pConnected) return;
            tvP2pStatus.setText(carP2p != null && carP2p.isScanning()
                    ? "扫描中，等待仪表发现结果…"
                    : "未发现设备；查看下方扫描结果，确认仪表处于 OTA Update。");
            selectedP2p = null;
            renderCarInfo();
            return;
        }
        selectedP2p = null;
        for (WifiP2pDevice d : peers) {
            if (CarP2p.isCarName(d.deviceName)) {
                selectedP2p = d;
                break;
            }
        }
        if (selectedP2p == null) {
            selectedP2p = peers.get(0);
        }
        final WifiP2pDevice target = selectedP2p;
        for (final WifiP2pDevice d : peers) {
            View row = getLayoutInflater().inflate(R.layout.view_peer_row, boxPeers, false);
            ((TextView) row.findViewById(R.id.peer_name)).setText(
                    d.deviceName == null ? "(无名设备)" : d.deviceName);
            ((TextView) row.findViewById(R.id.peer_mac)).setText(
                    d.deviceAddress == null ? "MAC 未知" : d.deviceAddress);
            boolean isCar = CarP2p.isCarName(d.deviceName);
            ((TextView) row.findViewById(R.id.peer_state)).setText(
                    isCar ? "仪表设备 · 优先连接"
                            : (d.status == WifiP2pDevice.CONNECTED
                            ? "已连接" : "可用"));
            View btn = row.findViewById(R.id.peer_connect);
            if (d == target) {
                ((com.google.android.material.button.MaterialButton) btn).setText("连接");
            } else {
                ((com.google.android.material.button.MaterialButton) btn).setText("选择");
            }
            View.OnClickListener act = v -> {
                selectedP2p = d;
                if (carP2p != null && p2pAccess != null) {
                    p2pAccess.run(() -> {
                        carP2p.connect(d);
                        tvP2pStatus.setText("已请求连接 " + (d.deviceName == null ? d.deviceAddress : d.deviceName)
                                + "，等待系统建组…");
                    });
                }
                // 连接中要允许「断开」来取消
                main.postDelayed(this::refreshDisconnectButton, 300);
            };
            row.setOnClickListener(act);
            btn.setOnClickListener(act);
            boxPeers.addView(row);
        }
        tvP2pStatus.setText("发现 " + peers.size() + " 台，已选 "
                + (selectedP2p.deviceName == null ? selectedP2p.deviceAddress
                : selectedP2p.deviceName));
        // ★ 同步刷新另外两处状态（发现设备那一刻状态就要动，不能等建组回调）
        if (tvLinkStatus != null && !p2pConnected) {
            tvLinkStatus.setText("发现 " + peers.size() + " 台设备；点设备行的「连接」建组");
        }
        renderCarInfo();
    }

    // ==================== 车机信息表 ====================

    /** 往信息表里塞一行（key/value）。 */
    private void infoRow(String key, String value) {
        View row = getLayoutInflater().inflate(R.layout.view_info_row, boxCarInfo, false);
        ((TextView) row.findViewById(R.id.info_key)).setText(key);
        ((TextView) row.findViewById(R.id.info_value)).setText(
                value == null || value.isEmpty() ? "—" : value);
        boxCarInfo.addView(row);
    }

    /** 探一次 Key OTA 服务（10086），结果写进 {@link #keyOtaPortState}。 */
    private void probeKeyOtaOnce(final String host) {
        if (host == null || probingKeyOta) {
            return;
        }
        probingKeyOta = true;
        new Thread(() -> {
            final boolean open = OtaSocketPusher.probeTcp(host, CarNet.PORT_P2P_TOOL, 1500);
            main.post(() -> {
                probingKeyOta = false;
                keyOtaPortState = open ? 1 : 2;
                renderCarInfo();
                append("P2P：" + host + ":" + CarNet.PORT_P2P_TOOL
                        + (open ? " OPEN（Key OTA 服务已就绪）" : " CLOSED（Keys OTA 服务未启动）"));
                if (!open) {
                    append("  ⇒ 仪表不在 OTA Update 模式：请确认 OTA Update 窗口已开启后再扫描/连接");
                }
            });
        }, "keyota-probe").start();
    }

    /** 端口状态 → 展示文案。 */
    private static String portLabel(int state, String what) {
        switch (state) {
            case 1:
                return "OPEN · " + what;
            case 2:
                return "拒连 · " + what;
            default:
                return "未探测";
        }
    }

    /** 渲染「车机信息」表。 */
    private void renderCarInfo() {
        if (boxCarInfo == null) {
            return;
        }
        boxCarInfo.removeAllViews();
        WifiP2pDevice d = selectedP2p;
        infoRow("设备名", d == null ? null : d.deviceName);
        infoRow("P2P MAC", d == null ? null : d.deviceAddress);
        if (d != null && d.deviceAddress != null && d.deviceAddress.length() >= 12) {
            infoRow("网卡尾号", d.deviceAddress.substring(d.deviceAddress.length() - 6));
        }
        infoRow("主类型", d == null ? null : d.primaryDeviceType);
        infoRow("发现状态", p2pConnected ? "已连接"
                : (carP2p != null && carP2p.isScanning() ? "扫描中…"
                : (d == null ? "未扫描" : "待连接")));
        infoRow("推送地址", carHost() + ":" + OtaSocketPusher.CAR_PORT);
        infoRow("本机角色", p2pConnected
                ? (OtaSocketPusher.isLocalGroupOwner() ? "组主（手机）" : "客户端（仪表是组主）")
                : "未建组");
        // ★ 两条通道分开写：10086=Key OTA 服务，10950=OTA 推送通道。
        infoRow("Key OTA · 10086", portLabel(keyOtaPortState, "P2P 服务"));
        infoRow("OTA 通道 · 10950", portLabel(otaChannelState, "推送通道"));
        if (tvSubtitle != null) {
            tvSubtitle.setText("socket_l · " + carHost() + ":" + OtaSocketPusher.CAR_PORT);
        }
    }

    @Override
    public void onExternalIntent(Intent intent) {
        handleAuto(intent);
    }

    /** 处理远程驱动 extras（probe / push；push 可带 --ez dry true 只联调不推包）。 */
    private void handleAuto(Intent intent) {
        String auto = intent == null ? null : intent.getStringExtra("auto");
        if ("probe".equals(auto)) {
            append("=== 远程驱动：探测车机 " + carHost() + ":10930 ===");
            new Thread(() -> {
                EcPxc.ProbeResult r = EcPxc.probe(carHost(), CarNet.PORT_EC, 3000, OtaFragment.this);
                append("探测结果：" + r.summary());
            }, "ecpxc-probe").start();
        } else if ("push".equals(auto)) {
            EcPxc.DRY_RUN = intent.getBooleanExtra("dry", false);
            long hold = intent.getLongExtra("hold", 0);
            if (hold > 0) {
                EcPxc.DRY_HOLD_MS = hold;
            }
            append("=== 远程驱动：推送（dry=" + EcPxc.DRY_RUN
                    + "，hold=" + (EcPxc.DRY_HOLD_MS / 1000) + "s） ===");
            main.post(this::startPush);
        } else if ("sockpush".equals(auto)) {
            append("=== 远程驱动：socket_l 直推 ===");
            main.post(this::startSocketPush);
        } else if ("watchpush".equals(auto)) {
            append("=== 远程驱动：守候即推 ===");
            main.post(this::startWatchRemote);
        } else if ("verify".equals(auto)) {
            String path = intent.getStringExtra("path");
            append("=== 远程驱动：只读自检（不推送任何数据） " + path + " ===");
            main.post(() -> verifyRemote(path));
        }
    }

    /**
     * 远程只读自检：对指定路径跑六项检查，<b>不碰网络、不发任何帧</b>。
     */
    private void verifyRemote(String path) {
        if (path == null || path.isEmpty()) {
            append("用法：--es auto verify --es path <可读路径>");
            return;
        }
        final File f = new File(path);
        if (!f.isFile()) {
            append("✖ 读不到文件（路径不存在，或落在本应用无权限的位置）：" + path);
            return;
        }
        append("自检中（要读约 340MB，30~90 秒）…");
        new Thread(() -> {
            final OtaSocketPusher.PkgInfo info =
                    OtaSocketPusher.inspectPackage(f, line -> append("  " + line));
            main.post(() -> {
                picked = f;
                pickedInfo = info;
                tvPkg.setText(String.format("%s  (%d 字节)", f.getName(), f.length()));
                tvPkgInspect.setText(info.summary());
                append("=== 自检结论 ===\n" + info.summary());
                refreshPushButtons();
            });
        }, "ota-verify-remote").start();
    }

    /** 远程驱动用：跳过确认弹窗直接守候推送（车边 adb 一键跑）。 */
    private void startWatchRemote() {
        final File pkg = picked;
        if (pkg == null) {
            append("没有已选的包 —— 远程驱动不支持猜路径，请先用界面 SAF 选包");
            return;
        }
        if (pickedInfo != null && pickedInfo.pushable()) {
            startWatchPush(pkg, pickedInfo);
            return;
        }
        append("先跑六项检查 …");
        new Thread(() -> {
            final OtaSocketPusher.PkgInfo info =
                    OtaSocketPusher.inspectPackage(pkg, line -> append("  " + line));
            main.post(() -> {
                pickedInfo = info;
                append("=== 包自检 ===\n" + info.summary());
                if (info.pushable()) {
                    startWatchPush(pkg, info);
                } else {
                    append("✖ 未通过六项检查，拒绝推送。先修包。");
                }
            });
        }, "ota-inspect-remote").start();
    }

    // ==================== 选包 / SHA-256 ====================

    private void onPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        append("已选 " + uri.getLastPathSegment() + "，拷到私有缓存 …");
        final Uri u = uri;
        setBusy(true);
        new Thread(() -> {
            try {
                File fg = OtaSocketPusher.copyFromUri(requireContext(), u);
                picked = fg;
                main.post(() -> {
                    showPkg(fg);
                    inspectPicked(fg);
                });
            } catch (Exception e) {
                main.post(() -> setBusy(false));
                append("拷贝失败 —— " + e);
            }
        }, "ota-pick").start();
    }

    private void showPkg(File f) {
        tvPkg.setText(String.format("%s  (%d 字节)", f.getName(), f.length()));
        btnSha256.setEnabled(true);
    }

    /**
     * 包自检：跑 {@link OtaPackVerify} 的六项检查（车机/U-Boot 真正会验的那几道）。
     *
     * <p>★ 这是 10/06 变砖唯一的护栏，必须在推送前在手机上验完。</p>
     */
    private void inspectPicked(final File f) {
        pickedInfo = null;
        setBusy(true);
        tvPkgInspect.setText("自检中：解包并跑六项检查\n"
                + "① 内层 MD5SUM*　② main 脚本 dataCRC+headerCRC\n"
                + "③ 分区表 19 项 MD5　④ 脚本内 74 个硬编码 MD5\n"
                + "要读约 340MB，约 30~90 秒");
        new Thread(() -> {
            final OtaSocketPusher.PkgInfo info = OtaSocketPusher.inspectPackage(f, line ->
                    main.post(() -> tvPkgInspect.setText("自检中…\n" + line)));
            main.post(() -> {
                setBusy(false);
                pickedInfo = info;
                StringBuilder sb = new StringBuilder();
                sb.append(info.gradeLabel()).append('\n');
                if (info.verify != null) {
                    for (OtaPackVerify.Check c : info.verify.checks) {
                        sb.append(c.line()).append('\n');
                    }
                    for (String n : info.verify.notes) {
                        sb.append(n).append('\n');
                    }
                }
                if (!info.matchedBaseline.isEmpty()) {
                    sb.append("参考：").append(info.matchedBaseline).append('\n');
                }
                if (!info.note.isEmpty()) {
                    sb.append(info.note).append('\n');
                }
                if (info.grade == OtaSocketPusher.Grade.UNSAFE) {
                    sb.append("\n★ 已禁用推送按钮。先把包改自洽再刷 —— ")
                            .append("拿仪表试错的代价是一次变砖（10/06 就是这么坏的，返厂了）。");
                }
                tvPkgInspect.setText(sb.toString().trim());
                append("=== 包自检 ===\n" + info.summary());
                refreshPushButtons();
            });
        }, "ota-inspect").start();
    }

    private void refreshPushButtons() {
        boolean hasPkg = picked != null && pickedInfo != null;
        boolean ok = hasPkg && pickedInfo.pushable() && !busy;
        if (btnP2pToolWatch != null) {
            btnP2pToolWatch.setEnabled(ok && !busy);
        }
        if (hasPkg && !pickedInfo.pushable()) {
            append("（推包已禁用：六项检查未过，见上方包自检结论）");
        }
    }

    /** 「断开」按钮的可用性完全由<b>系统报告的组状态</b>决定。 */
    private void refreshDisconnectButton() {
        if (btnP2pDisconnect == null) {
            return;
        }
        boolean formed = p2pConnected || (carP2p != null);
        btnP2pDisconnect.setEnabled(formed);
    }

    /** 拉取车机日志（p2pTool + 3 字节 {@code LOG}），<b>只读</b>，不推包、不刷写。 */
    private void p2pToolLog() {
        final String host = carHost();
        append("=== 拉取车机日志（LOG · 只读）" + host + ":" + OtaP2pToolPusher.PORT + " ===");
        append("不推包、不触发刷写；tar 会落盘并自动解出关键行");
        new Thread(() -> {
            final long n = OtaP2pToolPusher.probeLog(requireContext(), host,
                    new OtaP2pToolPusher.Callback() {
                        @Override
                        public void onLog(String line) {
                            main.post(() -> append("  " + line));
                        }

                        @Override
                        public void onProgress(long sent, long total) {
                            // LOG 协议无进度语义，不处理
                        }
                    });
            main.post(() -> {
                if (n > 0) {
                    append("LOG 取证完成，共 " + n + " 字节");
                    append("★ 看上面带 [文件名] 的行：recv file size 是否等于 totalFileSize、"
                            + "有没有 No checksum found、有没有 ioctl/upgrade 触发记录");
                } else {
                    append("✖ LOG 拉取失败 —— 确认写包服务已就绪（P2P 已建组、"
                            + "OTA Update 窗口常驻时它应OPEN）");
                }
            });
        }, "p2ptool-log").start();
    }

    // ==================== p2pTool 守候即推 ====================

    /** 守候即推的确认闸：六项检查 + 红线提示。 */
    private void confirmP2pToolWatchPush() {
        final File pkg = picked;
        if (pkg == null) {
            append("还没选包 —— 先「选择文件」并用六项检查确认包自洽");
            return;
        }
        if (pickedInfo == null) {
            append("开始OTA流程前必须先过六项检查（点「选择文件」后会自动跑）");
            return;
        }
        if (!pickedInfo.pushable()) {
            append("✖ 六项检查没过，禁止推包（会半刷变砖）");
            append(pickedInfo.summary());
            return;
        }
        final String host = carHost();
        final boolean flash = swAutoflash != null && swAutoflash.isChecked();
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("开始OTA流程 确认")
                .setMessage("通道：p2pTool\n"
                        + "地址：自动用组主地址（默认 " + host + "）\n"
                        + "包名：" + pkg.getName() + "\n"
                        + "格式：裸 GEMINI_PACK.BIN（会先从 zip 解出内层）\n"
                        + "流程：确保 P2P 连上仪表 → 等服务就绪 → 自动推包\n\n"
                        + (flash
                        ? "★ 推送完成后会发送 3 字节 OTA ⇒ 车机校验后**真正触发刷写**，"
                        + "不可逆。确认电量≥25%、非充电、非 Ready。"
                        : "当前「传完自动触发刷写」= 关 ⇒ 只把包写进车机，"
                        + "不发送 OTA、不会刷写。"))
                .setNegativeButton("取消", null)
                .setPositiveButton(flash ? "确认刷写" : "只送包",
                        (d, w) -> doP2pToolWatchPush(pkg, host, flash))
                .show();
    }

    /** 10086 守候即推：后台轮询直到 10086 OPEN 再推，最多约 5 分钟。 */
    private void doP2pToolWatchPush(final File zip, final String fallbackHost, final boolean flash) {
        append("=== 开始OTA流程 " + zip.getName() + " ===");
        append("「传完自动触发刷写」=" + (flash ? "开（会真的刷写）" : "关（仅推送 OTA 包）"));
        setBusy(true);
        tvStatus.setText("开始OTA流程：确保 P2P 连上并等服务就绪");
        pb.setProgress(0);
        tvProgress.setText("0%");

        if (!p2pConnected && carP2p != null) {
            append("尚未建 P2P 组，先扫描/连接仪表（让仪表进 OTA Update 模式）…");
            requestP2pScan();
        }

        p2pToolWatching = true;
        new Thread(() -> {
            // ① 解包一次
            File named;
            try {
                append("解出内层 GEMINI_PACK.BIN …");
                File inner = OtaPackVerify.extractInnerPack(zip);
                named = OtaP2pToolPusher.normalizeName(inner,
                        new File(requireContext().getCacheDir(), "p2ptool"));
                append("内层包：" + named.length() + " 字节");
            } catch (Throwable t) {
                main.post(() -> {
                    setBusy(false);
                    append("✖ 解内层包失败：" + t);
                    tvStatus.setText("解包失败");
                });
                p2pToolWatching = false;
                return;
            }
            final File pack = named;
            final long total = pack.length();
            final OtaP2pToolPusher.Callback cb = new OtaP2pToolPusher.Callback() {
                @Override
                public void onLog(String line) {
                    main.post(() -> append("  " + line));
                }

                @Override
                public void onProgress(long sent, long tot) {
                    final int pct = (int) (sent * 100 / Math.max(1, tot));
                    main.post(() -> {
                        pb.setProgress(pct);
                        tvProgress.setText(pct + "%");
                    });
                }
            };

            int tries = 0;
            int consecutiveFail = 0;
            while (p2pToolWatching && tries < 60) {           // ~5 分钟上限
                tries++;
                final String host = (p2pConnected && p2pGroupOwner != null && !p2pGroupOwner.isEmpty())
                        ? p2pGroupOwner : fallbackHost;
                if (p2pConnected && OtaSocketPusher.probeTcp(host, CarNet.PORT_P2P_TOOL, 1500)) {
                    main.post(() -> {
                        tvStatus.setText("服务就绪 · 推送中…");
                        append("服务就绪 @ " + host + " → 开始推包");
                    });
                    final boolean ok = OtaP2pToolPusher.push(host, pack, flash, cb);
                    if (ok) {
                        p2pToolWatching = false;
                        main.post(() -> {
                            setBusy(false);
                            append(flash ? "✔ OTA 流程完成，车机将校验并刷写"
                                    : "✔ OTA 流程完成（仅推送 OTA 包，未触发刷写）");
                            tvStatus.setText(flash ? "已推完 · 看车机屏幕：校验 → 升级 → 重启"
                                    : "已送包 · 未触发刷写（开关为关）");
                        });
                        return;
                    }
                    consecutiveFail++;
                    final int cf = consecutiveFail;
                    main.post(() -> append("✖ 本轮推包失败（第 " + cf + " 次）"));
                    if (consecutiveFail >= 3) {
                        p2pToolWatching = false;
                        main.post(() -> {
                            setBusy(false);
                            append("✖ 连续 3 次推包失败，停止守候。检查六项是否全过、写包服务是否仍就绪。");
                            tvStatus.setText("OTA 流程失败");
                        });
                        return;
                    }
                } else {
                    consecutiveFail = 0;
                    if (tries % 4 == 1) {
                        final int tr = tries;
                        main.post(() -> append("等服务就绪…（" + host + "，第 " + tr + " 轮）"));
                    }
                }
                // 退避 5 秒后重试（interrupted 不致命）
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            p2pToolWatching = false;
            final int finalTries = tries;
            main.post(() -> {
                setBusy(false);
                if (finalTries >= 60) {
                    append("✖ 守候 5 分钟仍没推成（写包服务没就绪或推包失败）。"
                            + "确认仪表 OTA Update 窗口常驻、P2P 已连上仪表。");
                    tvStatus.setText("守候超时");
                }
            });
        }, "p2ptool-watch").start();
    }

    /** 后台算 SHA-256，算完写回 tvPkg（保留文件名与大小信息）。 */
    private void calcSha256() {
        final File f = picked;
        if (f == null) {
            append("还没选包");
            return;
        }
        btnSha256.setEnabled(false);
        append("计算 SHA-256 …（" + f.length() + " 字节）");
        new Thread(() -> {
            String sha;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                InputStream in = new FileInputStream(f);
                try {
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        md.update(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) {
                    sb.append(String.format("%02x", b));
                }
                sha = sb.toString();
            } catch (Exception e) {
                sha = "计算失败: " + e;
            }
            final String s = sha;
            main.post(() -> {
                tvPkg.setText(String.format("%s  (%d 字节)\nSHA-256 %s", f.getName(), f.length(), s));
                btnSha256.setEnabled(true);
                append("SHA-256 = " + s);
            });
        }, "ota-sha256").start();
    }

    // ==================== 连接仪表 ====================

    /** 刷新「当前 Wi-Fi」显示：一眼确认手机是不是连在仪表 AP（车机 AP）上。 */
    private void refreshWifi() {
        if (tvWifi == null) {
            return;
        }
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) requireContext()
                            .getSystemService(android.content.Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String ssid = wi == null ? null : wi.getSSID();
            if (ssid == null || "<unknown ssid>".equals(ssid)) {
                tvWifi.setText("当前 Wi-Fi：未连接");
                return;
            }
            String bssid = wi.getBSSID() == null ? "" : " / " + wi.getBSSID();
            boolean isCar = com.github.speedhunter.ota.CarWifi.isCarSsid(ssid);
            tvWifi.setText("当前 Wi-Fi：" + ssid.replace("\"", "") + bssid
                    + (isCar ? "  ← 仪表 AP ✔" : "  ← 不是仪表 AP ⚠"));
        } catch (Exception e) {
            if (tvWifi != null) {
                tvWifi.setText("当前 Wi-Fi：读取失败");
            }
        }
    }

    @Override
    public void onPageShown() {
        refreshWifi();
        if (p2pAccess != null) p2pAccess.onResume();
        // ★ P2P 若早已建组，系统不会因为「回到页面」再发一次 onGroup 回调，
        //   这里主动拉一次组信息，让 10086 自动重探、表格立即有内容。
        if (carP2p != null) {
            try {
                carP2p.refreshGroup();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 车机地址。
     *
     * <p>优先级：<b>P2P 已建组 → 用组主地址</b>；否则用界面单选。</p>
     */
    private String carHost() {
        if (p2pConnected && p2pGroupOwner != null && !p2pGroupOwner.isEmpty()) {
            return p2pGroupOwner;
        }
        if (rgHost != null && rgHost.getCheckedRadioButtonId() == R.id.rb_host_49) {
            return "192.168.49.1";
        }
        return "192.168.0.1";
    }

    /** 打开系统无线设置（蓝牙 / WLAN）。 */
    private void openBluetoothSettings() {
        append("打开系统无线设置：P2P 连接需要蓝牙可被发现，且 Wi-Fi 需保持开启");
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            CarNet.openSystemWifiSettings(requireActivity());
        }
    }

    // ==================== 推送 ====================

    private void startPush() {
        File pkg = picked;
        if (pkg == null && !EcPxc.DRY_RUN) {
            tvStatus.setText("还没选包");
            append("没选包 —— 点「选择文件」用 SAF 挑一个升级包");
            return;
        }
        if (pkg == null) {
            append("（dry-run：未选包，只联调协议）");
        }
        String host = carHost();
        append("=== 推送 " + (pkg == null ? "(dry-run 无包)" : pkg.getName())
                + " → " + host + "（EC PXC 免投屏） ===");
        tvStatus.setText(pkg == null ? "联调中… 等车机回连" : "推送中… 等车机回连");
        pb.setProgress(0);
        tvProgress.setText("0%");
        setBusy(true);
        EcPxc.push(host, pkg, 90000, this, this, this);
    }

    private void setBusy(boolean b) {
        busy = b;
        btnPick.setEnabled(!b);
        btnSha256.setEnabled(!b);
        btnDiag.setEnabled(!b);
        btnOpenWifi.setEnabled(!b);
        refreshPushButtons();
    }

    // ==================== 退出投屏后自动接手建 P2P ====================

    private void startAutoP2pWatch() {
        main.removeCallbacks(autoP2pTick);
        main.postDelayed(autoP2pTick, AUTO_P2P_TICK_MS);
    }

    private static final long AUTO_P2P_TICK_MS = 6000L;
    /** 两次自动建组的最小间隔，避免把 WifiP2pManager 打爆。 */
    private static final long AUTO_P2P_MIN_GAP_MS = 20000L;
    /**
     * 连续多少轮 tick「完全没有车机网段网卡」才允许自动建 P2P。
     *
     * <p>★ 必须给足延迟（10 轮 × 6s = 60s），否则用户刚为投屏断开 P2P，
     * 6 秒后本页又把 P2P 建回来 →官方 App 抢不到 Wi-Fi、投屏失败（实测撞过）。</p>
     */
    private static final int AUTO_P2P_QUIET_TICKS = 10;
    private long lastAutoP2pAt;
    private int noNetTicks;
    private boolean autoP2pNoticed;

    /**官方 App 是否在运行 —— 它一跑起来就是要投屏，此时绝不能抢 Wi-Fi。 */
    private static boolean officialAppRunning() {
        try {
            java.io.File dir = new java.io.File("/proc");
            java.io.File[] pids = dir.listFiles();
            if (pids == null) {
                return false;
            }
            for (java.io.File p : pids) {
                if (!p.getName().matches("\\d+")) {
                    continue;
                }
                java.io.File cmd = new java.io.File(p, "cmdline");
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(cmd);
                    byte[] buf = new byte[256];
                    int n = in.read(buf);
                    in.close();
                    if (n > 0) {
                        String s = new String(buf, 0, n, "latin1");
                        if (s.contains("com.cfmoto")) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private final Runnable autoP2pTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (com.github.speedhunter.mirror.StandaloneMirrorService.isActive()) {
                    noNetTicks = 0; return; // 独立投屏持有 Wi-Fi Direct，禁止后台抢建组。
                }
                if (!probeActive) {
                    return;                     // 探测结束就停
                }
                // ★ 投屏准备模式期间绝不能建组，否则会把官方 App 的 Wi-Fi 抢走
                if (OtaSocketPusher.hasCarNetIface()) {
                    noNetTicks = 0;
                    if (!autoP2pNoticed) {
                        autoP2pNoticed = true;
                        append("已获得车机网段网卡 → " + OtaSocketPusher.networkShapeHint());
                    }
                    return;
                }
                noNetTicks++;
                // ★官方 App 在跑 = 正在投屏，绝不能抢 Wi-Fi
                if (officialAppRunning()) {
                    if (noNetTicks == 1) {
                        append("检测到官方 App 正在运行 → 暂停自动建组，让投屏先把 AP 建起来");
                    }
                    return;
                }
                if (noNetTicks < AUTO_P2P_QUIET_TICKS) {
                    if (noNetTicks == 1) {
                        append("当前没有车机网段网卡；等 "
                                + (AUTO_P2P_QUIET_TICKS * AUTO_P2P_TICK_MS / 1000)
                                + " 秒仍没有才自动建 P2P（避免和投屏抢 Wi-Fi）");
                    }
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastAutoP2pAt < AUTO_P2P_MIN_GAP_MS) {
                    return;
                }
                if (carP2p == null) {
                    append("★ 当前没有车机网段网卡，且 P2P 不可用 —— "
                            + "只能手动连车机 AP 或重启 Wi-Fi 后再试");
                    return;
                }
                lastAutoP2pAt = now;
                append("★ 已连续 " + (AUTO_P2P_QUIET_TICKS * AUTO_P2P_TICK_MS / 1000)
                        + " 秒没有任何车机网段网卡，且官方 App 未运行"
                        + " → 自动发起 Wi-Fi Direct 扫描建组（你不需要去手动连 AP）");
                requestP2pScan();
            } catch (Throwable t) {
                append("自动建 P2P 异常：" + t);
            } finally {
                if (probeActive) {
                    main.postDelayed(this, AUTO_P2P_TICK_MS);
                }
            }
        }
    };

    @Override
    public void onProbeStage(String line) {
        append(line);
    }

    @Override
    public void onProbeResult(final OtaSocketPusher.VehicleInfo info) {
        main.post(() -> {
            probeActive = false;
            main.removeCallbacks(autoP2pTick);
            otaChannelState = info.ok ? 1 : 2;
            refreshPushButtons();
            renderCarInfo();
            append("探测结果：" + info.summary());
            if (info.ok && info.versionCode != null && !info.versionCode.isEmpty()) {
                tvLinkStatus.setText("10950 已连通 · " + info.host
                        + "\n版本 " + info.versionCode
                        + "\nVIN " + (info.vin.isEmpty() ? "—" : info.vin));
                tvStatus.setText("10950 已连通 · 可推送");
                append("★ 10950 只是短命窗口，随时可能被网络切换拆掉。"
                        + "推荐直接点「开始OTA流程」：连上即在同一连接内推完，不回界面。");
            } else if (info.ok) {
                tvLinkStatus.setText("10950 已连通 · 但没读到版本");
                tvStatus.setText("10950 已连通 · 但没读到版本");
                append(info.hint);
            } else {
                tvLinkStatus.setText("10950 未连通 · " + info.error);
                tvStatus.setText("10950 未连通 · " + info.error);
                append(info.hint);
            }
        });
    }

    /**
     * 推送前确认弹窗：把「刷的是哪个包、六项检查结论、哪些红线」摊开给用户看。
     */
    private void confirmThen(final boolean watch) {
        final File pkg = picked;
        final OtaSocketPusher.PkgInfo info = pickedInfo;
        if (pkg == null) {
            tvStatus.setText("还没选包");
            append("没找到包 —— 点「选择文件」用 SAF 选一个升级包");
            return;
        }
        if (info == null || info.verify == null) {
            tvStatus.setText("包自检还没跑完");
            append("等六项检查跑完再推送（要先确认推的是哪一个包、以及它是否自洽）");
            return;
        }
        if (!info.pushable()) {
            append("✖ 包未通过六项检查，禁止推送：" + info.note);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("包　　：").append(pkg.getName()).append('\n');
        sb.append("大小　：").append(pkg.length()).append(" 字节\n");
        sb.append("zip MD5：").append(info.zipMd5).append('\n');
        sb.append("内层包　：").append(info.packSize).append(" 字节\n");
        sb.append("内层 MD5：").append(info.packMd5).append('\n');
        sb.append("\n六项检查（U-Boot 同一套）：\n");
        for (OtaPackVerify.Check c : info.verify.checks) {
            sb.append(c.line()).append('\n');
        }
        sb.append("\n结论　　：").append(info.gradeLabel()).append('\n');
        if (!info.matchedBaseline.isEmpty()) {
            sb.append("参考　　：").append(info.matchedBaseline).append('\n');
        }
        sb.append("\n车机　　：").append(OtaSocketPusher.carHost()).append(":10950\n");
        sb.append("方式　　：").append(watch ? "守候即推（探测到就在同一连接内推完）" : "直接推送")
                .append('\n');
        boolean flash = swAutoflash.isChecked();
        sb.append("自动刷写：").append(flash ? "★ 开（推送并刷写）" : "关（仅推送 OTA 包）")
                .append('\n');
        sb.append("\n⚠ 推送后不可逆。刷写中断会导致仪表报废。\n");
        if (!flash) {
            sb.append("★ 这次开关是关的：包会落到 userdata 就停，车机停在 OTA Update 等确认。\n");
        }
        sb.append("逐条确认：\n");
        sb.append("· 电量 ≥ 25%，且不在充电\n");
        sb.append("· 车辆非 Ready 状态、已停车\n");
        sb.append("· 仪表 OTA Update 浮窗已打开\n");
        sb.append("· 传输全程不动车机、不关 Wi-Fi、不关本 App\n");
        sb.append("· 车机起不来且疑在刷写中时，不反复上电\n");
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(watch ? "确认：守候即推" : "确认：推送 OTA")
                .setMessage(sb.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton(flash ? "我已确认，推送并刷写" : "确认，只送包", (d, w) -> {
                    if (watch) {
                        startWatchPush(pkg, info);
                    } else {
                        startPushNow(pkg, info);
                    }
                })
                .show();
    }

    /**
     * 守候即推：持续探测 10950，一连上就在同一连接内完成整条推送。
     */
    private void startWatchPush(File pkg, OtaSocketPusher.PkgInfo info) {
        append("=== 守候即推 " + pkg.getName() + " (" + pkg.length() + "B) ===");
        append("=== 10950 的真实启动条件（Ghidra 实证 libotamodule.so）===");
        append("  · 10950 的 bind+listen 只在 OTAModule::initial() 里创建一次（VA 0x17ddc）");
        append("  · initial() 是 spapp 模块生命周期函数，由 ModuleManager 经 vtable 调用");
        append("  · 投屏(EasyConnect) 只走到 ecModuleInit() 去 connect 信号槽，**不启动 10950**");
        append("  · 所以「投屏画面正常、10950 却拒连」是固件层必然，不是操作错");
        append("  · 任何断网事件(slots_otaWifiDisconnectedState → disConnectPhone)都会拆掉它");
        append("=== 现在真正可用的通道 ===");
        append("  · p2pTool 写包服务，由仪表 OTA Update 窗口拉起，现在已就绪");
        append("  · 它收裸 GEMINI_PACK.BIN + 3 字节命令，经 ioctl 触发刷写");
        append("  · 第三方「仪表 OTA 工具」走的就是这条（见 docs/55）");
        append("⇒ socket_l（10950）始终拒连是固件层必然，继续等是等不到的。");
        append("「传完自动触发刷写」=" + (swAutoflash.isChecked() ? "开（会真的刷写）" : "关（仅推送 OTA 包）"));
        OtaSocketPusher.setResolvedCarHost(carHost());
        tvStatus.setText("守候推送…连上即自动推送");
        pb.setProgress(0);
        tvProgress.setText("0%");
        setBusy(true);
        OtaPushService.setSink(this);
        OtaPushService.startWatchPush(requireContext(), pkg.getAbsolutePath(), false,
                swAutoflash.isChecked());
        // 守候期间同样自动接手建 P2P（用户退出投屏后不必碰 Wi-Fi 设置）
        probeActive = true;
        startAutoP2pWatch();
    }

    /** 直接推送（通道已确认在听时用）。 */
    private void startPushNow(File pkg, OtaSocketPusher.PkgInfo info) {
        append("=== socket_l 直推 " + pkg.getName() + " (" + pkg.length() + "B) ===");
        append("「传完自动触发刷写」=" + (swAutoflash.isChecked() ? "开（会真的刷写）" : "关（仅推送 OTA 包）"));
        OtaSocketPusher.setResolvedCarHost(CarNet.resolveCarHost(OtaSocketPusher.CAR_PORT));
        tvStatus.setText("socket_l 推送中…");
        pb.setProgress(0);
        tvProgress.setText("0%");
        setBusy(true);
        OtaPushService.setSink(this);
        OtaPushService.start(requireContext(), pkg.getAbsolutePath(), swAutoflash.isChecked());
    }

    /** 旧入口（远程驱动 / 兼容），走直接推送。 */
    private void startSocketPush() {
        if (probeActive) {
            append("持续探测结束后再手动推送，或直接用「守候即推」。当前没有发送 OTA 数据。");
            return;
        }
        confirmThen(false);
    }

    // ==================== 日志 ====================

    private void clearLog() {
        log.setLength(0);
        tvLog.setText("（日志已清空）");
    }

    /** 把整段日志复制到剪贴板 —— 车边要贴到笔记 / 提工单时省事。 */
    private void copyLog() {
        if (log.length() == 0) {
            append("日志是空的，没什么可复制");
            return;
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                append("拿不到剪贴板服务");
                return;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "SpeedHunter OTA 日志", log.toString()));
            append("已复制 " + log.length() + " 字日志到剪贴板");
        } catch (Exception e) {
            append("复制失败：" + e);
        }
    }

    private void setupLogScrolling(final TextView tv, View root) {
        tv.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tv.setVerticalScrollBarEnabled(true);
        final androidx.core.widget.NestedScrollView parent =
                root.findViewById(R.id.page_scroll);
        tv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent ev) {
                // 日志区自己滚动时，别让外层 NestedScrollView 抢走手势
                int a = ev.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    parent.requestDisallowInterceptTouchEvent(true);
                } else if (a == android.view.MotionEvent.ACTION_UP
                        || a == android.view.MotionEvent.ACTION_CANCEL) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        });
    }

    private static final java.text.SimpleDateFormat LOG_TS =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);

    /** 追加一行日志，<b>带时间戳</b>；按字符数截断（106MB 推送会刷几百行长日志）。 */
    private void append(final String s) {
        HookLog.log(HookLog.OTA + " UI " + s);
        main.post(() -> {
            String ts;
            synchronized (LOG_TS) {
                ts = LOG_TS.format(new java.util.Date());
            }
            log.append(ts).append("  ").append(s).append('\n');
            int overflow = log.length() - LOG_MAX_CHARS;
            if (overflow > 0) {
                int nl = log.indexOf("\n", overflow);
                log.delete(0, nl >= 0 ? nl + 1 : overflow);
            }
            tvLog.setText(log.toString());
            tvLog.post(() -> {
                android.text.Layout l = tvLog.getLayout();
                if (l == null) {
                    return;
                }
                int bottom = l.getHeight() - tvLog.getHeight();
                if (bottom < 0) {
                    bottom = 0;
                }
                tvLog.scrollTo(0, bottom);
            });
        });
    }

    // ==================== EcPxc.LineSink / Progress / DoneCallback ====================

    @Override
    public void onLine(String line) {
        append(line);
    }

    @Override
    public void onProgress(long sent, long total) {
        main.post(() -> {
            int pct = total > 0 ? (int) (sent * 100 / total) : 0;
            pb.setProgress(Math.max(0, Math.min(100, pct)));
            tvProgress.setText(pct + "%  " + sent / 1048576L + "/" + total / 1048576L + " MB");
            tvStatus.setText(String.format("推送中… %d%%", pct));
        });
    }

    @Override
    public void onDone(final boolean ok, final String summary) {
        main.post(() -> {
            setBusy(false);
            if (ok) {
                pb.setProgress(100);
                tvProgress.setText("100%");
                tvStatus.setText("已送达 · 看车机屏幕：校验 → 升级 → 重启");
                append("[完成] " + summary);
            } else {
                tvStatus.setText("推送失败");
                append("[失败] " + summary);
            }
        });
    }

    // ==================== OtaPushService.Progress（socket_l 直推） ====================

    @Override
    public void onPushLog(String line) {
        append(line);
    }

    @Override
    public void onPushProgress(final int percent, final long sent, final long total,
                               final String note) {
        main.post(() -> {
            pb.setProgress(Math.max(0, Math.min(100, percent)));
            tvProgress.setText(percent + "%  " + sent / 1048576L + "/" + total / 1048576L + " MB");
            tvStatus.setText("socket_l 传输中… " + percent + "%");
        });
    }

    @Override
    public void onPushDone(final boolean ok, final String summary) {
        main.post(() -> {
            setBusy(false);
            if (!swAutoflash.isChecked()) {
                pb.setProgress(100);
                tvProgress.setText("100%");
                tvStatus.setText("已送包 · 未触发刷写（开关为关）");
                append("[完成] " + summary);
                append("车机现在停在 OTA Update 等确认，没有进 U-Boot —— 这是预期行为。");
                append("要真的刷：打开「传完自动触发刷写」再推一次。");
                return;
            }
            if (ok) {
                pb.setProgress(100);
                tvProgress.setText("100%");
                tvStatus.setText("已回校验确认 · 车机应已进刷写");
                append("[完成] " + summary);
                append("接下来（10/06 实测，勿误判为失败）：");
                append("· 车机会丢 P2P / 10950 CLOSED /车机 AP 消失 = 已重启进 U-Boot/ISP 刷写");
                append("· 刷写中 Android 协议栈不跑，中控屏全黑属正常；进度条画在仪表屏上");
                append("· 判据：等车机自动重启上电后看仪表实际显示，别用手机这侧判断");
                append("· 期间不要反复上电 —— 若车机起不来，重复上电可能扩大损坏");
            } else {
                tvStatus.setText("推送未完成");
                append("[失败] " + summary);
            }
        });
    }

    // ==================== CarNet.DiagListener ====================

    @Override
    public void onStage(String s) {
        append(s);
    }

    @Override
    public void onResult(final CarNet.DiagReport report) {
        main.post(() -> {
            append(report.summary);
            if (report.hasCar()) {
                tvLinkStatus.setText("网络诊断：组主 " + report.groupOwner
                        + "（经 :" + report.groupOwnerPort + " 判定）\n模式：" + report.mode);
                tvStatus.setText("诊断到车机 · " + report.groupOwner);
                OtaSocketPusher.setResolvedCarHost(report.groupOwner);
                renderCarInfo();
            } else {
                tvStatus.setText("诊断未探到车机");
            }
        });
    }

    @Override
    public void onDestroy() {
        p2pToolWatching = false;          // 退出界面即停 10086 守候轮询
        if (p2pAccess != null) p2pAccess.close();
        if (carP2p != null) {
            try { carP2p.close(); } catch (Throwable ignored) { }
            carP2p = null;
        }
        // Service 还在跑就不要抢 sink（推送中切走界面，进度回报会掉，但传输不中断）
        OtaPushService.setSink(null);
        OtaPushService.setProbeSink(null);
        super.onDestroy();
    }
}
