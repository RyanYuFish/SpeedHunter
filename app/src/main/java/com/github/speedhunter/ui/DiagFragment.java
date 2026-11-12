package com.github.speedhunter.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import com.github.speedhunter.R;
import com.github.speedhunter.diag.DiagLogger;
import com.github.speedhunter.diag.DeleteCardResponses;
import com.github.speedhunter.diag.DiagSelfTest;
import com.github.speedhunter.diag.Hex;
import com.github.speedhunter.diag.Rs485;
import com.github.speedhunter.diag.VciLink;
import com.github.speedhunter.diag.VciSppLink;
import com.github.speedhunter.diag.ZeehoRs485Ops;

/**
 * 车辆诊断板块。
 *
 * <p>v4.7.33:主从切换/全部操作按 2026-11-10 官方 App Java 层抓取逐字节对齐
 * (zscan-capture 删卡/前轮排气/读取版本);新增 ABS 前轮排气(官方流程)。
 * 写操作一律弹确认;所有收发记录到 speedhunter_diag.log。
 * 协议证据见 docs/features/diagnostics.md。</p>
 */
public final class DiagFragment extends BaseTabFragment {

    private VciSppLink link;
    private final DiagLogger logger = new DiagLogger();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private TextView linkState;
    private android.widget.ScrollView logScroll;
    /** 用户是否处于日志底部(true 时新日志自动滚到底;用户上翻则暂停,回底自动恢复)。 */
    private volatile boolean logAtBottom = true;
    private final Object responseLock = new Object();
    private final List<VciLink.DataFrame> pendingResponses = new ArrayList<>();
    /** L2 fff7(VCI 计算 ABS 安全 key)最近一次结果。 */
    private volatile byte[] lastFff7Key;
    private boolean flowBusy;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_diag;
    }

    @Override
    public void onViewCreated(View root, android.os.Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);
        logger.attach(requireContext());
        EdgeToEdge.applyTop(root);
        final TextView report = root.findViewById(R.id.tv_diag_report);
        final TextView brief = root.findViewById(R.id.tv_diag_selftest_brief);
        final String[] full = {DiagSelfTest.run()};
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("结果：(\\d+) 通过 / (\\d+) 失败").matcher(full[0]);
        brief.setText(m.find() && m.group(2).equals("0")
                ? "▸ 协议自检 " + m.group(1) + "/" + m.group(1) + " 全部通过(点击展开)"
                : "▸ 协议自检存在失败(点击展开)");
        brief.setOnClickListener(v -> report.setVisibility(report.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        report.setText(full[0]);

        logView = root.findViewById(R.id.tv_diag_log);
        linkState = root.findViewById(R.id.tv_diag_link_state);
        logScroll = root.findViewById(R.id.sv_diag_log);
        // 日志窗口可手动上下滚动:① 禁止外层 NestedScrollView 拦截竖向手势;
        // ② 用户上翻时暂停自动置底,滚回底部后自动恢复。
        logScroll.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN
                    || event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        logScroll.setOnScrollChangeListener(
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    android.view.ViewGroup group = (android.view.ViewGroup) v;
                    android.view.View child = group.getChildAt(0);
                    logAtBottom = child == null
                            || scrollY + v.getHeight() >= child.getHeight() - v.getPaddingBottom() - 8;
                });
        root.findViewById(R.id.btn_diag_connect).setOnClickListener(v -> connectVci());
        root.findViewById(R.id.btn_diag_disconnect).setOnClickListener(v -> closeLink());
        root.findViewById(R.id.btn_diag_nfc_on).setOnClickListener(v -> nfcStudyOfficialFlow());
        root.findViewById(R.id.btn_diag_nfc_done).setOnClickListener(v ->
                toast("学卡退出包含在各流程末段(39 00 OFF + 0x71 退出),无单独退出按钮"));
        root.findViewById(R.id.btn_diag_nfc_del).setOnClickListener(v -> beginDeleteCard());
        root.findViewById(R.id.btn_diag_version).setOnClickListener(v -> beginVersionRead());
        root.findViewById(R.id.btn_diag_dtc_read).setOnClickListener(v -> beginDtcRead());
        root.findViewById(R.id.btn_diag_dtc_clear).setOnClickListener(v -> beginDtcClear());
        root.findViewById(R.id.btn_diag_abs).setOnClickListener(v -> beginAbsBleed());
        root.findViewById(R.id.btn_diag_odo_did).setOnClickListener(v -> writeMileage(false));
        root.findViewById(R.id.btn_diag_odo_eol).setOnClickListener(v -> writeMileage(true));
        root.findViewById(R.id.btn_diag_share).setOnClickListener(v -> shareLog());
        root.findViewById(R.id.btn_diag_clear).setOnClickListener(v -> {
            logger.clearUi();
            logView.setText("等待操作…");
        });
        root.findViewById(R.id.btn_diag_copy).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("diag_log", logger.dump()));
            toast("日志已复制到剪贴板");
        });
        setState("未连接");
    }

    private static final int REQUEST_BLUETOOTH_CONNECT = 401;

    private boolean btReady() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
            return false;
        }
        return true;
    }

    private void connectVci() {
        if (!btReady()) return;
        if (link != null) { closeLink(); }
        EditText macEdit = requireView().findViewById(R.id.et_diag_mac);
        String mac = macEdit.getText().toString().trim().toUpperCase();
        if (!mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
            toast("MAC 格式不对"); return;
        }
        link = new VciSppLink(new VciSppLink.Listener() {
            @Override public void onConnected(String device) {
                ui.post(() -> { setState("已连接 " + device + ",握手中"); log("LINK", "connected " + device); });
            }
            @Override public void onHandshake(String mode, String detail) {
                ui.post(() -> { log("HS", "[" + mode + "] " + detail); setState("已连接(" + mode + " 握手)"); });
            }
            @Override public void onL2(byte[] frame) {
                byte[] key = ZeehoRs485Ops.parseFff7Key(frame);
                if (key != null) {
                    lastFff7Key = key;
                    synchronized (responseLock) { responseLock.notifyAll(); }
                }
                String h = Hex.format(frame).replace(" ", "");
                ui.post(() -> log("L2RX", h + (key != null ? " | ABS key=" + Hex.format(key) : "")));
            }
            @Override public void onFrame(VciLink.DataFrame f, List<byte[]> cf) {
                synchronized (responseLock) {
                    pendingResponses.add(f);
                    if (pendingResponses.size() > 256) pendingResponses.remove(0);
                    responseLock.notifyAll();
                }
                StringBuilder sb = new StringBuilder("RX seq=" + f.seq + " ts=" + Hex.format(new byte[]{(byte) f.cmd}) + " data=" + Hex.format(f.data));
                for (byte[] c : cf) {
                    Rs485View v = viewCf(c);
                    sb.append(" | CF ").append(v.hex);
                    if (v.status != null) sb.append(" → ").append(v.status);
                }
                ui.post(() -> log("VCI", sb.toString()));
            }
            @Override public void onRaw(byte[] chunk, int n) {
                log("RXRAW", "bytes=" + n + " prefix=" + Hex.format(java.util.Arrays.copyOf(chunk, Math.min(n, 4))));
            }
            @Override public void onError(String message) {
                ui.post(() -> { log("ERR", message); setState("错误: " + message); });
            }
            @Override public void onDisconnected() {
                ui.post(() -> { setState("已断开"); log("LINK", "disconnected"); });
            }
        });
        logger.startSession("SpeedHunter diag | VCI " + mac);
        setState("连接中 " + mac);
        new Thread(() -> {
            try {
                link.connectAndHandshake(requireContext(), mac, true);
                log("OP", link.isDataReady() ? "L3 通道已验证(前导有应答)"
                        : "仅蓝牙/L2 有连接,L3 前导无应答;重新连接或检查 VCI");
            } catch (Exception e) {
                String m = e.getMessage();
                ui.post(() -> { setState("连接失败: " + m); log("ERR", "connect: " + m); });
            }
        }, "vci-connect").start();
    }

    private void closeLink() {
        if (link != null) {
            link.close();
            link = null;
            log("LINK", "已断开(释放 VCI)");
        }
        setState("未连接");
    }

    private static final class Rs485View {
        final String hex; final String status;
        Rs485View(String hex, String status) { this.hex = hex; this.status = status; }
    }

    private Rs485View viewCf(byte[] frame) {
        String h = Hex.format(frame).replace(" ", "");
        String status = null;
        try {
            com.github.speedhunter.diag.Rs485.Frame fr = com.github.speedhunter.diag.Rs485.decode(frame);
            if (fr.node == 0xFC) status = ZeehoRs485Ops.nfcStatusOf(fr.payload);
        } catch (Exception ignored) { }
        return new Rs485View(h, status);
    }

    // =====================================================================
    // 官方主从切换(2026-11-10 00:20 删卡会话逐字节对齐)
    // FFA×2 → 60×3+69×3 → [poll+0x89]×5(158/112/1105/1107ms) → 0x71(0x20)
    // → 等 0x79 应答或节点 0x17 广播(仪表唤醒标志) → 用户确认
    // =====================================================================

    private boolean opGate(String op) {
        if (!requireLink()) return false;
        if (!link.isDataReady()) { toast("L3 通道未验证,先重新连接 VCI"); return false; }
        if (flowBusy) { toast("有流程进行中"); return false; }
        flowBusy = true;
        log("OP", op + ":阶段1 主从切换(官方对齐)");
        return true;
    }

    private void releaseGate() { flowBusy = false; }

    /** 可抛受检异常的操作阶段(官方流程续段)。 */
    private interface DiagStep { void run() throws Exception; }

    /** 官方主从切换;切换后弹确认,通过后回调 onSwitched(后台线程执行)。 */
    private void officialMasterSlave(VciSppLink active, String op, DiagStep onSwitched) {
        new Thread(() -> {
            try {
                active.suspendKeepAlive();
                active.sendDeleteEntryQueries();
                sleepMs(45);
                for (byte[] cmd : ZeehoRs485Ops.sessionSetupCommands()) {
                    active.send(cmd[1] & 0xFF, java.util.Arrays.copyOfRange(cmd, 2, cmd.length));
                    sleepMs(15);
                }
                log("OP", "前导完成(60×3+69×3),开始 0x89×5(官方节奏 158/112/1105/1107ms)");
                for (int i = 0; i < 5; i++) {
                    if (link != active || !active.isConnected()) throw new java.io.IOException("连接已断开");
                    active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                    active.sendRs485(ZeehoRs485Ops.MASTER_SLAVE_89);
                    log("TX", op + " 主从 0x89 #" + (i + 1));
                    if (i < 4) sleepMs(ZeehoRs485Ops.MASTER_SLAVE_DELAYS_MS[i]);
                }
                sleepMs(ZeehoRs485Ops.MASTER_SLAVE_CONFIRM_DELAY_MS);
                active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                int seq = active.sendWithSeq(VciLink.CMD_RS485_WRITE, ZeehoRs485Ops.wrapRs485ForVci(ZeehoRs485Ops.MASTER_SLAVE_CONFIRM_71));
                log("TX", op + " 主从确认 0x71(byte6=0x20) seq=" + seq);
                boolean got79 = awaitNode(active, seq, 0x79, 4000);
                boolean wake = got79 || awaitNodeAny(active, 0x17, 12000);
                log("OP", (got79 ? "0x79 已应答(官方同款)" : "0x79 未应答")
                        + (wake ? ";检测到 0x17 广播,仪表已唤醒" : ";未检测到 0x17 广播"));
                final boolean ok = wake;
                ui.post(() -> {
                    if (!isAdded()) { releaseGate(); return; }
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(ok ? "主从切换完成" : "主从切换状态未知")
                            .setMessage(ok ? "仪表应已黑屏并自动重启唤醒。\n确认后继续「" + op + "」执行段。"
                                    : "未检测到仪表唤醒广播。\n请观察仪表:若未重启请中止并导出日志;若已重启可继续。")
                            .setNegativeButton("中止", (d, w) -> { log("OP", op + " 用户中止(切换后)"); exitMasterSlaveQuiet(active); releaseGate(); })
                            .setPositiveButton("继续", (d, w) -> new Thread(() -> {
                                try { onSwitched.run(); }
                                catch (Exception e) { log("ERR", op + " 执行段失败: " + e.getMessage()); releaseGate(); }
                            }, "diag-op-stage2").start())
                            .setCancelable(false).show();
                });
            } catch (Exception e) {
                log("ERR", op + " 主从切换失败: " + e.getMessage());
                active.resumeKeepAlive();
                releaseGate();
            }
        }, "diag-master").start();
    }

    /** 发送官方退出帧 0x71(byte6=0x04)并恢复心跳(失败不打断流程)。 */
    private void exitMasterSlaveQuiet(VciSppLink active) {
        try {
            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
            active.sendRs485(ZeehoRs485Ops.MASTER_SLAVE_EXIT_71);
            log("TX", "主从退出 0x71(byte6=0x04)");
        } catch (Exception e) {
            log("ERR", "主从退出失败: " + e.getMessage());
        } finally {
            active.resumeKeepAlive();
        }
    }

    // ===== 删卡(官方 2026-11-10 00:20 会话) =====

    private void beginDeleteCard() {
        if (!opGate("删卡")) return;
        // 固件逆向:F4 3A = NVM 清零(删卡),0/1/2 三类型(具体对应未细分;官方抓取值=0)
        final int[] type = {0};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("原厂 NFC 删卡 — 类型")
                .setSingleChoiceItems(new String[]{"类型 0(官方抓取值)", "类型 1", "类型 2"}, 0,
                        (d, which) -> type[0] = which)
                .setMessage("固件逆向:F4 3A 为 NVM 清零(删卡),类型 0/1/2(对应未细分,官方抓取值=0)。\n选定后:主从切换 → EOL 授权 → 再次确认才执行。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> startDeleteFlow(link, type[0]))
                .show();
    }

    private void startDeleteFlow(VciSppLink active, int deleteType) {
        officialMasterSlave(active, "删卡", () -> authorizeDelete(active, deleteType));
    }

    private void authorizeDelete(VciSppLink active, int deleteType) throws Exception {
        requestResponse(active, ZeehoRs485Ops.EOL_AUTH_1, 0x81);
        requestResponse(active, ZeehoRs485Ops.EOL_AUTH_2, 0x82);
        log("OP", "EOL 授权完成(FC 81/82 均已应答)");
        ui.post(() -> {
            if (!isAdded()) { releaseGate(); return; }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("授权完成")
                    .setMessage("继续将实际删除原厂卡(不可恢复)。执行?")
                    .setNegativeButton("取消", (d, w) -> { exitMasterSlaveQuiet(active); releaseGate(); })
                    .setPositiveButton("执行删卡", (d, w) -> new Thread(() -> executeDelete(active, deleteType), "diag-del-write").start())
                    .setCancelable(false).show();
        });
    }

    private void executeDelete(VciSppLink active, int deleteType) {
        try {
            byte[] cfg = ZeehoRs485Ops.sessionTimeoutRaise();
            active.send(cfg[1] & 0xFF, java.util.Arrays.copyOfRange(cfg, 2, cfg.length));
            sleepMs(50);
            requestResponse(active, ZeehoRs485Ops.nfcDelete(deleteType), 0xba);
            log("OP", "删卡执行完成(FC BA 00),物理卡状态请刷卡核对");
            ui.post(() -> {
                if (!isAdded()) { releaseGate(); return; }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("收到删卡响应")
                        .setMessage("FC BA 00 已收到。完成后发送官方退出帧。")
                        .setPositiveButton("完成", (d, w) -> new Thread(() -> {
                            exitMasterSlaveQuiet(active);
                            releaseGate();
                        }, "diag-del-exit").start())
                        .setCancelable(false).show();
            });
        } catch (Exception e) {
            log("ERR", "删卡执行失败: " + e.getMessage());
            exitMasterSlaveQuiet(active);
            releaseGate();
        }
    }

    // ===== 学卡(主从切换 + 39 00 开关;执行段无官方抓取,标注未实车对齐) =====

    private void nfcStudyOfficialFlow() {
        if (!opGate("学卡")) return;
        // 固件逆向(AE5_全通讯协议总汇 §3.2):F4 39 学习开关 0=系统卡 / 1=用户卡
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("原厂 NFC 学卡 — 卡类型")
                .setMessage("固件逆向:F4 39 学习开关 0=系统卡 / 1=用户卡。\n选择要学习的卡类型(ON/OFF 使用同值);主从切换+授权段为官方会话同构。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setNeutralButton("系统卡(0·抓取值)", (d, w) -> startStudyFlow(link, 0))
                .setPositiveButton("用户卡(1)", (d, w) -> startStudyFlow(link, 1))
                .show();
    }

    private void startStudyFlow(VciSppLink active, int cardType) {
        officialMasterSlave(active, "学卡", () -> {
            sendCfTo(active, "学卡·开关ON(39 0" + cardType + ")", ZeehoRs485Ops.nfcLearnSwitch(cardType));
                        ui.post(() -> {
                            if (!isAdded()) { releaseGate(); return; }
                            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("请刷卡")
                                    .setMessage("仪表应已进入学卡窗口(类型 " + cardType + ")。\n把卡贴到 NFC 感应区,仪表显示成功后点[完成授权]。")
                                    .setPositiveButton("完成授权", (d2, w2) -> new Thread(() -> studyAuth(active, cardType), "diag-study3").start())
                                    .setNegativeButton("取消", (d2, w2) -> { log("OP", "学卡取消(刷卡段)"); exitMasterSlaveQuiet(active); releaseGate(); })
                                    .show();
                        });
                    });
    }

    private void studyAuth(VciSppLink active, int studyCardType) {
        try {
            requestResponse(active, ZeehoRs485Ops.EOL_AUTH_1, 0x81);
            requestResponse(active, ZeehoRs485Ops.EOL_AUTH_2, 0x82);
            byte[] cfg = ZeehoRs485Ops.sessionTimeoutRaise();
            active.send(cfg[1] & 0xFF, java.util.Arrays.copyOfRange(cfg, 2, cfg.length));
            sleepMs(50);
            pollAndSend(active, "学卡·退出(39 0" + studyCardType + ")", ZeehoRs485Ops.nfcLearnSwitch(studyCardType));
            sleepMs(200);
            exitMasterSlaveQuiet(active);
            log("OP", "学卡流程完成(仪表应显示成功)");
        } catch (Exception e) {
            log("ERR", "学卡授权失败: " + e.getMessage());
            exitMasterSlaveQuiet(active);
        } finally {
            releaseGate();
        }
    }

    // ===== 读版本(官方 2026-11-10 00:23 会话:主从切换 + 0x80 + 逐节点 22F193/F195) =====

    private void beginVersionRead() {
        if (!opGate("读版本")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("读版本(官方流程)")
                .setMessage("官方读版本同样先做主从切换(仪表将黑屏重启),再逐节点读 22F193/F195。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final VciSppLink active = link;
                    officialMasterSlave(active, "读版本", () -> {
                        active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                        active.sendRs485(ZeehoRs485Ops.VERSION_QUERY_80);
                        log("TX", "版本 0x80(VIN 查询,观察 0x88 应答)");
                        sleepMs(300);
                        for (int node : ZeehoRs485Ops.VERSION_NODES) {
                            pollAndSend(active, "版本 node=" + Integer.toHexString(node), ZeehoRs485Ops.udsReadDid(node, 0xF193));
                            sleepMs(200);
                            pollAndSend(active, "版本 node=" + Integer.toHexString(node), ZeehoRs485Ops.udsReadDid(node, 0xF195));
                            sleepMs(200);
                        }
                        log("OP", "读版本发送完毕(日志核对 62 F1 93/95 应答)");
                        exitMasterSlaveQuiet(active);
                        releaseGate();
                    });
                }).show();
    }

    // ===== 读 DTC(主从+授权段=官方删卡会话同构;执行帧 F4 51 00 来自旧黄金抓取) =====

    private void beginDtcRead() {
        if (!opGate("读DTC")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("读故障码")
                .setMessage("主从切换(仪表将黑屏重启)→ EOL 授权 → F4 51 00。\n固件逆向:F4 51 = 读 46B 诊断状态(推测 6B 状态头 + DTC_0/1/2 三块,与清码 33/36/38 对应)。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final VciSppLink active = link;
                    officialMasterSlave(active, "读DTC", () -> {
                        requestResponse(active, ZeehoRs485Ops.EOL_AUTH_1, 0x81);
                        requestResponse(active, ZeehoRs485Ops.EOL_AUTH_2, 0x82);
                        byte[] cfg = ZeehoRs485Ops.sessionTimeoutRaise();
                        active.send(cfg[1] & 0xFF, java.util.Arrays.copyOfRange(cfg, 2, cfg.length));
                        sleepMs(50);
                        int mark;
                        synchronized (responseLock) {
                            mark = pendingResponses.size();
                            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                            active.sendRs485(ZeehoRs485Ops.dtcReadEol());
                        }
                        log("TX", "读DTC F4 51 00 已发,收集应答…");
                        sleepMs(3000);
                        List<String> payloads = collectFcPayloadsSince(mark);
                        if (payloads.isEmpty()) {
                            log("OP", "读DTC: 3s 内未收到节点 0xFC 应答(导出日志核查)");
                        } else {
                            for (String pl : payloads) {
                                log("OP", "读DTC 应答 FC payload=" + pl);
                                log("OP", "读DTC 解读: " + describeDtcPayload(pl));
                            }
                        }
                        final List<String> result = payloads;
                        ui.post(() -> {
                            if (!isAdded()) { releaseGate(); return; }
                            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("读 DTC 完成")
                                    .setMessage(result.isEmpty()
                                            ? "未收到 0xFC 应答。已发送主从切换+授权+51 00,请导出日志分析。"
                                            : "收到 " + result.size() + " 条 FC 应答(payload 见日志)。\n确认后发送主从退出帧。")
                                    .setPositiveButton("完成", (d2, w2) -> new Thread(() -> {
                                        exitMasterSlaveQuiet(active);
                                        releaseGate();
                                    }, "diag-dtc-exit").start())
                                    .setCancelable(false).show();
                        });
                    });
                }).show();
    }

    /** 收集 mark 之后所有来自节点 0xFC 的 CF payload(hex)。 */
    private List<String> collectFcPayloadsSince(int mark) {
        List<String> out = new ArrayList<>();
        synchronized (responseLock) {
            for (int i = mark; i < pendingResponses.size(); i++) {
                VciLink.DataFrame f = pendingResponses.get(i);
                if (!f.fromVehicle) continue;
                for (byte[] raw : VciLink.extractCfFrames(f)) {
                    try {
                        Rs485.Frame fr = Rs485.decode(raw);
                        if (fr.node == 0xFC && fr.payload.length > 0) {
                            out.add(Hex.format(fr.payload).replace(" ", ""));
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        return out;
    }

    /**
     * F4 51 应答解读。固件逆向(AE5_全通讯协议总汇 §3.2):F4 51 = 读 46B 诊断状态【汇】;
     * 结构拆分 [6B 状态头][20B DTC_0(NVM18)][10B DTC_1(NVM19)][10B DTC_2(NVM20)] 为【推】
     * (6+20+10+10=46 与清码写入的 33/36/38 三块完全对应)。
     */
    private static String describeDtcPayload(String hex) {
        String h = hex;
        if (h.length() >= 94 && h.substring(0, 2).equalsIgnoreCase("D1")) h = h.substring(2);
        int len = h.length() / 2;
        if (len < 46) return h + "(" + len + "B,非 46B 诊断状态结构,按原始字节记录)";
        String head = h.substring(0, 12);
        String d0 = h.substring(12, 52), d1 = h.substring(52, 72), d2 = h.substring(72, 92);
        return "状态头 " + head + " | DTC_0 " + d0 + "(非零" + countNonZeroHexBytes(d0)
                + "B) | DTC_1 " + d1 + "(非零" + countNonZeroHexBytes(d1)
                + "B) | DTC_2 " + d2 + "(非零" + countNonZeroHexBytes(d2) + "B)";
    }

    private static int countNonZeroHexBytes(String hex) {
        int n = 0;
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            if (hex.charAt(i) != '0' || hex.charAt(i + 1) != '0') n++;
        }
        return n;
    }

    // ===== 清 DTC(EOL 授权 + 33/36/38 写零;官方删卡会话同构) =====

    private void beginDtcClear() {
        if (!opGate("清DTC")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("清 DTC")
                .setMessage("主从切换后执行 EOL 授权 + 33/36/38 写零三块。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final VciSppLink active = link;
                    officialMasterSlave(active, "清DTC", () -> {
                        requestResponse(active, ZeehoRs485Ops.EOL_AUTH_1, 0x81);
                        requestResponse(active, ZeehoRs485Ops.EOL_AUTH_2, 0x82);
                        byte[] cfg = ZeehoRs485Ops.sessionTimeoutRaise();
                        active.send(cfg[1] & 0xFF, java.util.Arrays.copyOfRange(cfg, 2, cfg.length));
                        sleepMs(50);
                        for (byte[] fr : ZeehoRs485Ops.dtcClearEolSequence()) {
                            if (ZeehoRs485Ops.eolAuthSequence().contains(fr)) continue; // 授权已发
                            pollAndSend(active, "清DTC", fr);
                            sleepMs(200);
                        }
                        log("OP", "清 DTC 发送完毕");
                        exitMasterSlaveQuiet(active);
                        releaseGate();
                    });
                }).show();
    }

    // ===== 写里程 =====

    private void writeMileage(boolean eolRoute) {
        if (!requireLink()) return;
        EditText kmEdit = requireView().findViewById(R.id.et_diag_km);
        String raw = kmEdit.getText().toString().trim();
        if (raw.isEmpty()) { toast("输入里程"); return; }
        double km;
        try { km = Double.parseDouble(raw); } catch (NumberFormatException e) { toast("里程格式不对"); return; }
        if (km < 0 || km > 999999) { toast("里程 0–999999 km"); return; }
        int tenth = (int) Math.round(km * 10);
        if (eolRoute) {
            if (!opGate("写里程EOL")) return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("写里程(EOL)")
                    .setMessage("主从切换 + 授权 + 0x4F 写入 " + raw + " km(小端)。")
                    .setNegativeButton("取消", (d, w) -> releaseGate())
                    .setPositiveButton("开始", (d, w) -> {
                        final VciSppLink active = link;
                        officialMasterSlave(active, "写里程EOL", () -> {
                            requestResponse(active, ZeehoRs485Ops.EOL_AUTH_1, 0x81);
                            requestResponse(active, ZeehoRs485Ops.EOL_AUTH_2, 0x82);
                            byte[] cfg = ZeehoRs485Ops.sessionTimeoutRaise();
                            active.send(cfg[1] & 0xFF, java.util.Arrays.copyOfRange(cfg, 2, cfg.length));
                            sleepMs(50);
                            pollAndSend(active, "写里程EOL", ZeehoRs485Ops.mileageWriteEol(tenth));
                            sleepMs(800);
                            String after = readOdoViaDid(active);
                            log("OP", "写后里程回读: " + (after != null ? after : "无应答(观察仪表核实)"));
                            log("OP", "写里程EOL 发送完毕");
                            exitMasterSlaveQuiet(active);
                            releaseGate();
                        });
                    }).show();
        } else {
            confirm("写里程 " + raw + " km(DID 0x0309 直写,无主从切换),确认?", () -> {
                new Thread(() -> {
                    VciSppLink active = link;
                    if (active == null) return;
                    for (byte[] l3 : ZeehoRs485Ops.sessionSetupCommands()) {
                        try {
                            active.send(l3[1] & 0xFF, java.util.Arrays.copyOfRange(l3, 2, l3.length));
                        } catch (Exception e) { log("ERR", "preamble: " + e.getMessage()); return; }
                        sleepMs(15);
                    }
                    sleepMs(200);
                    String before = readOdoViaDid(active);
                    log("OP", "写前里程读取: " + (before != null ? before : "无应答(继续写入)"));
                    sleepMs(300);
                    sendCfTo(active, "写里程DID", ZeehoRs485Ops.mileageWriteDid(tenth));
                    sleepMs(800);
                    String after = readOdoViaDid(active);
                    log("OP", "写后里程回读: " + (after != null ? after : "无应答(观察仪表核实)"));
                }, "diag-odo").start();
            });
        }
    }

    // ===== ABS 前轮排气(官方 2026-11-10 00:22 会话逐字节) =====

    private void beginAbsBleed() {
        if (!opGate("ABS排气")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("ABS 前轮排气(官方流程)")
                .setMessage("按官方流程连接 ABS 模块:双通道配置 → 扩展会话 → 安全访问(key 由 VCI 计算)→ 60 轮泵阀循环(约 66s)→ 清码 + ECU 复位。\n请按官方提示操作刹车油壶/卡钳。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final VciSppLink active = link;
                    new Thread(() -> {
                        try {
                            active.suspendKeepAlive();
                            active.sendDeleteEntryQueries();
                            sleepMs(45);
                            for (byte[] cmd : ZeehoRs485Ops.absBleedSetupCommands()) {
                                active.send(cmd[1] & 0xFF, java.util.Arrays.copyOfRange(cmd, 2, cmd.length));
                                sleepMs(25);
                            }
                            log("OP", "ABS 配置完成(60×4 + 82 01/82 00)");
                            ui.post(() -> absStage2Dialog(active));
                        } catch (Exception e) {
                            log("ERR", "ABS 配置失败: " + e.getMessage());
                            active.resumeKeepAlive();
                            releaseGate();
                        }
                    }, "diag-abs1").start();
                }).show();
    }

    private void absStage2Dialog(VciSppLink active) {
        if (!isAdded()) { releaseGate(); return; }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("进入诊断会话")
                .setMessage("配置完成。继续进入 ABS 扩展会话(10 03)?")
                .setNegativeButton("中止", (d, w) -> { active.resumeKeepAlive(); releaseGate(); })
                .setPositiveButton("继续", (d, w) -> new Thread(() -> {
                    try {
                        active.sendBus(Hex.parse("1001"));
                        sleepMs(30);
                        active.sendBus(ZeehoRs485Ops.absSession1003());
                        log("TX", "ABS 会话 10 03(观察 50 03 应答)");
                        sleepMs(400);
                        ui.post(() -> absStage3Dialog(active));
                    } catch (Exception e) {
                        log("ERR", "ABS 会话失败: " + e.getMessage());
                        active.resumeKeepAlive();
                        releaseGate();
                    }
                }, "diag-abs2").start())
                .setCancelable(false).show();
    }

    private void absStage3Dialog(VciSppLink active) {
        if (!isAdded()) { releaseGate(); return; }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("开始排气动作")
                .setMessage("会话已请求。继续将执行安全访问(27 01/27 02)并开始 60 轮泵阀循环。\n循环期间勿断开蓝牙。")
                .setNegativeButton("中止", (d, w) -> { active.resumeKeepAlive(); releaseGate(); })
                .setPositiveButton("开始排气", (d, w) -> new Thread(() -> absBleedRun(active), "diag-abs3").start())
                .setCancelable(false).show();
    }

    private void absBleedRun(VciSppLink active) {
        try {
            active.sendBus(ZeehoRs485Ops.absSeedRequest());
            byte[] seed = awaitAbsSeed(3000);
            if (seed == null) throw new java.io.IOException("未收到 27 01 种子(07e9 08 06 67 01)");
            log("OP", "ABS seed=" + Hex.format(seed));
            lastFff7Key = null;
            active.sendRaw(ZeehoRs485Ops.L2_FFF9_KEY_QUERY);
            byte[] key = awaitFff7Key(3000);
            if (key == null) throw new java.io.IOException("VCI 未回 fff7 key");
            active.sendRaw(ZeehoRs485Ops.l2Fff1Ack(seed));
            active.sendBus(ZeehoRs485Ops.absSendKey(key));
            log("OP", "ABS 安全访问 key=" + Hex.format(key) + " 已发(27 02)");
            sleepMs(30);
            for (int i = 0; i < ZeehoRs485Ops.ABS_LOOP_ITERATIONS; i++) {
                if (link != active || !active.isConnected()) throw new java.io.IOException("连接已断开");
                long t0 = android.os.SystemClock.elapsedRealtime();
                active.sendBus(ZeehoRs485Ops.absLoopPump());
                active.sendBus(ZeehoRs485Ops.absLoopValve(i % 2 == 0));
                active.sendBus(ZeehoRs485Ops.absLoopStatus());
                if (i % 5 == 0 || i == ZeehoRs485Ops.ABS_LOOP_ITERATIONS - 1) {
                    log("OP", "排气循环 " + (i + 1) + "/" + ZeehoRs485Ops.ABS_LOOP_ITERATIONS
                            + (i % 2 == 0 ? "(阀开 64 64)" : "(阀关 00 00)"));
                }
                long left = ZeehoRs485Ops.ABS_LOOP_PERIOD_MS - (android.os.SystemClock.elapsedRealtime() - t0);
                if (left > 0) sleepMs(left);
            }
            // 官方收尾:停泵 → 3E 80 → 10 01 → 阀关 → 清码 → 默认会话
            active.sendBus(ZeehoRs485Ops.absStopPump());
            sleepMs(2000);
            active.sendBus(ZeehoRs485Ops.absTesterPresent());
            sleepMs(50);
            active.sendBus(Hex.parse("1001"));
            active.sendBus(ZeehoRs485Ops.absValveOff());
            sleepMs(40);
            active.sendBus(ZeehoRs485Ops.absClearDtc());
            sleepMs(40);
            active.sendBus(ZeehoRs485Ops.absDefaultSession());
            log("OP", "排气循环完成,已停泵/清码/回默认会话");
            ui.post(() -> {
                if (!isAdded()) { active.resumeKeepAlive(); releaseGate(); return; }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("排气完成")
                        .setMessage("循环与清码已发送。按官方流程最后执行 ABS ECU 复位(11 01)?")
                        .setNegativeButton("跳过", (d, w) -> { active.resumeKeepAlive(); releaseGate(); })
                        .setPositiveButton("复位", (d, w) -> new Thread(() -> {
                            try {
                                active.sendBus(ZeehoRs485Ops.absEcuReset());
                                log("TX", "ABS ECU 复位(11 01)");
                            } catch (Exception e) {
                                log("ERR", "ECU 复位失败: " + e.getMessage());
                            } finally {
                                active.resumeKeepAlive();
                                releaseGate();
                            }
                        }, "diag-abs-reset").start())
                        .setCancelable(false).show();
            });
        } catch (Exception e) {
            log("ERR", "ABS 排气中止: " + e.getMessage());
            active.resumeKeepAlive();
            releaseGate();
        }
    }

    /** 等待 ABS 27 01 种子(任意 L3 帧数据中含 07E9 08 06 67 01 <seed4B>)。 */
    private byte[] awaitAbsSeed(long timeoutMs) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (responseLock) {
            while (true) {
                for (VciLink.DataFrame f : pendingResponses) {
                    byte[] d = f.data;
                    for (int i = 0; i + 9 <= d.length; i++) {
                        if ((d[i] & 0xFF) == 0x07 && (d[i + 1] & 0xFF) == 0xE9 && (d[i + 2] & 0xFF) == 0x08
                                && (d[i + 3] & 0xFF) == 0x06 && (d[i + 4] & 0xFF) == 0x67 && (d[i + 5] & 0xFF) == 0x01) {
                            return java.util.Arrays.copyOfRange(d, i + 6, i + 10);
                        }
                    }
                }
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) return null;
                responseLock.wait(left);
            }
        }
    }

    private byte[] awaitFff7Key(long timeoutMs) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (responseLock) {
            while (lastFff7Key == null) {
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) return null;
                responseLock.wait(left);
            }
            return lastFff7Key;
        }
    }

    // ===== 底层发送/等待 =====

    private void sendCfTo(VciSppLink active, String title, byte[] cf) {
        log("TX", title + " CF " + Hex.format(cf).replace(" ", ""));
        try {
            active.sendRs485(cf);
        } catch (Exception e) {
            log("ERR", "send: " + e.getMessage());
        }
    }

    /** 官方节奏:每帧 CF 写前先发 69 0101 轮询。 */
    private void pollAndSend(VciSppLink active, String title, byte[] cf) {
        try {
            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
        } catch (Exception e) {
            log("ERR", "poll: " + e.getMessage());
        }
        sendCfTo(active, title, cf);
    }

    private void requestResponse(VciSppLink active, byte[] cf, int response) throws Exception {
        active.send(VciLink.CMD_POLL, new byte[]{1, 1});
        long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
        synchronized (responseLock) {
            pendingResponses.clear();
            int seq = active.sendWithSeq(VciLink.CMD_RS485_WRITE, ZeehoRs485Ops.wrapRs485ForVci(cf));
            log("TX", "CF(seq=" + seq + ") " + Hex.format(cf).replace(" ", ""));
            while (active.isConnected()) {
                for (VciLink.DataFrame frame : pendingResponses) {
                    if (DeleteCardResponses.matches(frame, seq, response)) return;
                }
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) break;
                responseLock.wait(left);
            }
        }
        throw new java.io.IOException("等待 FC " + Integer.toHexString(response) + " 超时或连接断开");
    }

    private boolean awaitNode(VciSppLink active, int seq, int node, long timeout) throws InterruptedException {
        return awaitNodeInternal(active, seq, node, timeout, true);
    }

    private boolean awaitNodeAny(VciSppLink active, int node, long timeout) throws InterruptedException {
        return awaitNodeInternal(active, -1, node, timeout, false);
    }

    private boolean awaitNodeInternal(VciSppLink active, int seq, int node, long timeout, boolean seqEcho) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
        synchronized (responseLock) {
            while (active.isConnected()) {
                for (VciLink.DataFrame frame : pendingResponses) {
                    if (!frame.fromVehicle) continue;
                    if (seqEcho && frame.seq != (seq & 0xff)) continue;
                    for (byte[] raw : VciLink.extractCfFrames(frame)) {
                        try {
                            if (Rs485.decode(raw).node == node) return true;
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) break;
                responseLock.wait(left);
            }
        }
        return false;
    }

    /** DID 0309 读里程(22 03 09 → 62 03 09 + 3B 大端 0.1km)。超时返回 null。 */
    private String readOdoViaDid(VciSppLink active) {
        int mark;
        synchronized (responseLock) {
            mark = pendingResponses.size();
            try {
                active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                active.sendRs485(Rs485.encode(0x14, new byte[]{0x22, 0x03, 0x09}));
            } catch (Exception e) {
                log("ERR", "读里程发送失败: " + e.getMessage());
                return null;
            }
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 2500;
        synchronized (responseLock) {
            while (active.isConnected()) {
                String r = scanOdoSince(mark);
                if (r != null) return r;
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) break;
                try { responseLock.wait(left); } catch (InterruptedException ignored) { return null; }
            }
        }
        return null;
    }

    private String scanOdoSince(int mark) {
        for (int i = mark; i < pendingResponses.size(); i++) {
            VciLink.DataFrame f = pendingResponses.get(i);
            if (!f.fromVehicle) continue;
            for (byte[] raw : VciLink.extractCfFrames(f)) {
                try {
                    Rs485.Frame fr = Rs485.decode(raw);
                    if (fr.node == 0x14 && fr.payload.length >= 6
                            && (fr.payload[0] & 0xFF) == 0x62
                            && (fr.payload[1] & 0xFF) == 0x03 && (fr.payload[2] & 0xFF) == 0x09) {
                        int v = ((fr.payload[3] & 0xFF) << 16) | ((fr.payload[4] & 0xFF) << 8) | (fr.payload[5] & 0xFF);
                        return String.format(java.util.Locale.US, "%.1f", v / 10.0) + " km";
                    }
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }

    private boolean requireLink() {
        if (link == null || !link.isConnected()) { toast("先连接 VCI"); return false; }
        return true;
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void confirm(String message, Runnable action) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("确认操作")
                .setMessage(message)
                .setPositiveButton("执行", (d, w) -> {
                    new Thread(action::run, "diag-op").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void shareLog() {
        String dump = logger.dump();
        String path = logger.externalPath();
        log("LOG", "导出: " + path);
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_TEXT, dump);
        startActivity(android.content.Intent.createChooser(send, "诊断日志"));
        toast("日志已保存:\n" + path);
    }

    private void log(String tag, String message) {
        logger.log(tag, message);
        ui.post(() -> {
            if (logView != null) {
                String cur = logView.getText().toString();
                String line = tag + " " + message;
                String newText = TextUtils.isEmpty(cur) || cur.equals("等待操作…") || cur.equals("(日志已清)")
                        ? line : cur + "\n" + line;
                if (newText.length() > 60000) newText = newText.substring(newText.length() - 60000);
                logView.setText(newText);
                final android.widget.ScrollView sv = logScroll;
                if (sv != null && logAtBottom) sv.post(() -> {
                    if (logAtBottom) sv.fullScroll(android.view.View.FOCUS_DOWN);
                });
            }
        });
    }

    private void setState(String s) {
        if (linkState != null) linkState.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPageHidden() {
        releaseIfAllowed("离开诊断页");
    }

    @Override
    public void onStop() {
        releaseIfAllowed("应用切后台");
        super.onStop();
    }

    private void releaseIfAllowed(String reason) {
        if (link == null || !link.isConnected()) return;
        android.widget.CheckBox keep = getView() != null ? getView().findViewById(R.id.cb_diag_keep) : null;
        if (keep != null && keep.isChecked()) {
            log("LINK", reason + ",保持连接(开关开启)");
            return;
        }
        log("LINK", reason + ",释放 VCI");
        closeLink();
    }

    @Override
    public void onDestroyView() {
        closeLink();
        super.onDestroyView();
    }
}
