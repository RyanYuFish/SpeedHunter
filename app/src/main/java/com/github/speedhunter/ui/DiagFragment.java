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
import com.github.speedhunter.diag.BleRs485Link;
import com.github.speedhunter.diag.DiagLink;
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


    /** 4.9.4 诊断:pollAwaitNode 超时后逐帧说明未命中原因(mark/size/每帧节点与前缀判定),仅超时路径调用。 */
    private void scanNodeSinceDebug(int mark, int node, int[] payloadPrefix) {
        synchronized (responseLock) {
            int size = pendingResponses.size();
            int trimmed = pendingTotal - size;
            int from = pendingIdx(mark);
            log("SCANDBG", "want node=" + Integer.toHexString(node) + " mark=" + mark + "(虚拟) size=" + size + " 已裁剪=" + trimmed + " 扫描起点=" + from);
            int shown = 0;
            for (int i = from; i < size && shown < 12; i++) {
                VciLink.DataFrame f = pendingResponses.get(i);
                if (!f.fromVehicle) { log("SCANDBG", "#" + i + " cmd=" + Integer.toHexString(f.cmd) + " 本机帧(跳过)"); shown++; continue; }
                java.util.List<byte[]> cfs = VciLink.extractCfFrames(f);
                if (cfs.isEmpty()) { log("SCANDBG", "#" + i + " cmd=" + Integer.toHexString(f.cmd) + " 无CF"); shown++; continue; }
                for (byte[] raw : cfs) {
                    try {
                        Rs485.Frame fr = Rs485.decode(raw);
                        String why = fr.node != node ? ("node=" + Integer.toHexString(fr.node) + " 不匹配")
                                : (payloadPrefix != null && !hasPrefix(fr.payload, payloadPrefix) ? "前缀不符 payload=" + Hex.format(fr.payload) : "??应命中却未命中??");
                        log("SCANDBG", "#" + i + " cmd=" + Integer.toHexString(f.cmd) + " CF node=" + Integer.toHexString(fr.node) + " → " + why);
                    } catch (IllegalArgumentException e) {
                        log("SCANDBG", "#" + i + " CF解码异常:" + e.getMessage());
                    }
                    shown++;
                }
            }
        }
    }

    /** 版本值渲染:无应答 / 空正响应(0x17 对 F193/F195 回空 62F193)/ 正常 ASCII。 */
    private static String verVal(String hit) {
        if (hit == null) return "无应答";
        StringBuilder sb = new StringBuilder();
        for (int i = 6; i + 1 < hit.length(); i += 2) { // 跳过 62 F1 xx 三字节正响应头
            int c = Integer.parseInt(hit.substring(i, i + 2), 16);
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : "(空应答)";
    }

    private DiagLink link;
    // ===== 蓝牙模块选择(2026-11-18):官方 VCI(SPP) 之外新增 BLE-RS485 透传(TD5322A 等) =====
    private static final String KIND_VCI = "vci";
    private static final String KIND_BLE = "ble";
    private static final String PREFS_DIAG = "diag_transport";
    private static final String PREF_KIND = "kind";
    private static final String PREF_MAC = "mac";
    private static final String PREF_NAME = "name";
    private static final String PREF_SVC_UUID = "svc_uuid";
    private static final String PREF_WR_UUID = "wr_uuid";
    private static final String PREF_ND_UUID = "nd_uuid";
    /** BLE 扫描运行时权限请求码(31+ SCAN / 30- 定位)。 */
    private static final int REQUEST_BT_SCAN = 402;
    /** 串口透传模块常见命名关键字(命中加 ★ 提示)。 */
    private static final java.util.regex.Pattern BLE_NAME_HINT = java.util.regex.Pattern.compile(
            "TD|5322|485|RS.?485|BLE|JDY|HM-|UART|串口", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 当前传输类型;picker/SharedPreferences 可改,连接工厂 createLink() 消费。 */
    private String linkKind = KIND_VCI;
    private android.bluetooth.le.BluetoothLeScanner bleScanner;
    private android.bluetooth.le.ScanCallback bleScanCb;
    private final java.util.LinkedHashMap<String, android.bluetooth.BluetoothDevice> scanFound = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String, String> scanNames = new java.util.LinkedHashMap<>();
    private final DiagLogger logger = new DiagLogger();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private TextView linkState;
    private android.widget.ScrollView logScroll;
    /** 诊断结果区(读版本/读 DTC/写里程读数,对齐官方 zscan 诊断界面结果展示)。 */
    private TextView resultView;
    private android.widget.ScrollView resultScroll;
    /** 用户是否处于日志底部(true 时新日志自动滚到底;用户上翻则暂停,回底自动恢复)。 */
    private volatile boolean logAtBottom = true;
    private final Object responseLock = new Object();
    private final List<VciLink.DataFrame> pendingResponses = new ArrayList<>();
    /** 累计入队总数(单调递增);mark/scanFrom 一律存此虚拟游标,环形 remove(0) 裁剪不再使游标漂移(5.2.10 修复)。 */
    private int pendingTotal = 0;
    /** L2 fff7(VCI 计算 ABS 安全 key)最近一次结果。 */
    private volatile byte[] lastFff7Key;
    private boolean flowBusy;
    // 写里程等长流程的进度框(m01165:主从切换后无任何进度反馈,车主无法确认成败)
    private android.app.Dialog flowProgress;
    private android.widget.TextView flowStageView;
    private long flowStartMs;

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
        resultView = root.findViewById(R.id.tv_diag_result);
        resultScroll = root.findViewById(R.id.sv_diag_result);
        resultScroll.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN
                    || event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        root.findViewById(R.id.btn_diag_result_clear).setOnClickListener(v ->
                resultView.setText("暂无结果(读版本/读 DTC/写里程后显示)"));
        // 恢复上次选择的传输/地址/UUID 覆盖(2026-11-18 双传输)
        android.content.SharedPreferences sp = requireContext().getSharedPreferences(PREFS_DIAG, Context.MODE_PRIVATE);
        linkKind = sp.getString(PREF_KIND, KIND_VCI);
        String savedMac = sp.getString(PREF_MAC, null);
        if (savedMac != null && !savedMac.isEmpty()) {
            EditText macInit = root.findViewById(R.id.et_diag_mac);
            macInit.setText(savedMac);
        }
        applyUuidOverridesFromPrefs(sp);
        root.findViewById(R.id.btn_diag_connect).setOnClickListener(v -> connectVci());
        root.findViewById(R.id.btn_diag_pick).setOnClickListener(v -> showModulePicker());
        root.findViewById(R.id.btn_diag_disconnect).setOnClickListener(v -> closeLink());
        root.findViewById(R.id.btn_diag_nfc_on).setOnClickListener(v -> runWithLink("学卡", this::nfcStudyOfficialFlow));
        root.findViewById(R.id.btn_diag_nfc_del).setOnClickListener(v -> runWithLink("删卡", this::beginDeleteCard));
        root.findViewById(R.id.btn_diag_version).setOnClickListener(v -> runWithLink("读版本", this::beginVersionRead));
        root.findViewById(R.id.btn_diag_dtc_read).setOnClickListener(v -> runWithLink("读DTC", this::beginDtcRead));
        root.findViewById(R.id.btn_diag_dtc_clear).setOnClickListener(v -> runWithLink("清DTC", this::beginDtcClear));
        root.findViewById(R.id.btn_diag_ext).setOnClickListener(v -> runWithLink("扩展读取", this::beginExtendedRead));
        root.findViewById(R.id.btn_diag_abs).setOnClickListener(v -> {
            if (!requireVciForKeyOps("ABS 排气")) return;
            runWithLink("ABS排气", this::beginAbsBleed);
        });
        root.findViewById(R.id.btn_diag_restore).setOnClickListener(v -> runWithLink("恢复主机", this::beginRestoreMaster));
        root.findViewById(R.id.btn_diag_odo_eol).setOnClickListener(v -> runWithLink("写里程", () -> writeMileage()));
        root.findViewById(R.id.btn_diag_can_version).setOnClickListener(v -> runWithLink("CAN读版本", this::beginAe7CanVersion));
        root.findViewById(R.id.btn_diag_can_odo).setOnClickListener(v -> runWithLink("CAN写里程", this::beginAe7CanMileage));
        root.findViewById(R.id.btn_diag_share).setOnClickListener(v -> shareLog());
        root.findViewById(R.id.btn_diag_export).setOnClickListener(v -> exportLog());
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

    // ===== 连接获取已合并进各操作按钮(4.8.5):未连接时自动连接并顺延执行操作;
    // "保持连接"不再是开关——流程进行中(flowBusy)/连接进行中(connecting)不自动释放,其余照旧释放。 =====
    private volatile boolean connecting;
    private volatile boolean connectAborted;
    private volatile android.widget.TextView connectStageView;
    private Runnable pendingOpAfterConnect;

    private void connectVci() {
        connectWithDialog(null, null);
    }

    /** 需要已验证 L3 通道的操作入口:未连接/未就绪时自动连接,成功后自动执行该操作。 */
    private void runWithLink(String opName, Runnable op) {
        if (link != null && link.isConnected() && link.isDataReady()) { op.run(); return; }
        if (connecting) { toast("正在连接 VCI…"); return; }
        if (flowBusy) { toast("有流程进行中"); return; }
        if (link != null) {
            log("LINK", "连接未就绪(" + (link.isConnected() ? "L3 数据通道未验证" : "链路已断") + "),自动重连");
            closeLink();
        }
        connectWithDialog(opName, op);
    }

    private void connectWithDialog(String opName, Runnable onReady) {
        if (!btReady()) return;
        EditText macEdit = requireView().findViewById(R.id.et_diag_mac);
        String mac = macEdit.getText().toString().trim().toUpperCase();
        if (!mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) { toast("MAC 格式不对"); return; }
        final boolean ble = KIND_BLE.equals(linkKind);
        if (connecting) { toast(ble ? "正在连接 BLE 模块,请稍候" : "正在连接 VCI,请稍候"); return; }
        if (link != null) closeLink();
        pendingOpAfterConnect = onReady;
        connectAborted = false;
        android.widget.TextView stage = new android.widget.TextView(requireContext());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        stage.setPadding(pad, pad, pad, pad);
        stage.setText(ble ? "① 连接 BLE 透传模块(GATT,最长 15s)…" : "① 停官方 App / 建立 SPP 连接…(每通道 8s 超时)");
        connectStageView = stage;
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        dlg[0] = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(opName == null ? (ble ? "连接 BLE 模块" : "连接 VCI") : (ble ? "连接 BLE 模块(完成后自动「" + opName + "」)" : "连接 VCI(完成后自动「" + opName + "」)"))
                .setView(stage)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> abortConnect())
                .show();
        connecting = true;
        if (logView != null) logView.setText(""); // 5.2.10:界面日志同样只保留本次会话
        logger.startSession("SpeedHunter diag | " + (ble ? "BLE " : "VCI ") + mac + (opName != null ? " | auto:" + opName : ""));
        setState("连接中 " + mac);
        new Thread(() -> {
            String fail = null;
            try {
                createLink();
                link.connectAndHandshake(requireContext(), mac, true);
                if (!link.isDataReady()) fail = ble
                        ? "总线探针无应答(模块已连接,但 RS485 总线无数据;检查车辆上电/模块 A-B 是否接总线)"
                        : "L3 数据通道无应答(VCI 已连接,但车辆数据未建立;可重试)";
            } catch (Exception e) {
                fail = e.getMessage();
            }
            final String err = fail;
            ui.post(() -> {
                connecting = false;
                connectStageView = null;
                try { dlg[0].dismiss(); } catch (Throwable ignored) { }
                if (connectAborted) {
                    pendingOpAfterConnect = null;
                    setState("已取消连接");
                    log("LINK", "连接已取消(用户)");
                    return;
                }
                if (err == null) {
                    link.startPolling(); // VCI:持续 69 轮询保活 L3 转发(2026-11-15 修复);BLE:无操作
                    log("OP", ble ? "透传通道已就绪(总线探针有应答)" : "L3 通道已验证(前导有应答),已启动持续轮询");
                    setState("已连接 " + mac);
                    Runnable op = pendingOpAfterConnect;
                    pendingOpAfterConnect = null;
                    if (op != null) {
                        if (flowBusy) toast("有流程进行中,自动操作未执行");
                        else op.run();
                    }
                    return;
                }
                setState("连接失败: " + err);
                log("ERR", "connect: " + err);
                pendingOpAfterConnect = null;
                if (!isAdded()) return;
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("连接失败")
                        .setMessage(err + "\n\n常见原因:" + (KIND_BLE.equals(linkKind)
                                ? "模块未上电 / 距离过远 / 已被其他手机占用(BLE 透传模块通常只允许一个主机) / RS485 未接车辆总线。\n本次已自动重试一次。"
                                : "官方 ZSCAN App 正占用 VCI(请先手动彻底关闭它) / VCI 已休眠(按键唤醒后再试) / 距离过远 / VCI 需断电重上电。\n本次已自动重试一次。"))
                        .setPositiveButton("重试", (d, w) -> connectWithDialog(opName, onReady))
                        .setNegativeButton("取消", null)
                        .show();
            });
        }, "vci-connect").start();
    }

    private void abortConnect() {
        connectAborted = true;
        pendingOpAfterConnect = null;
        if (link != null) { link.close(); link = null; }
        setState("未连接");
        log("LINK", "连接已取消");
    }

    private void createLink() {
        final DiagLink.Listener listener = new DiagLink.Listener() {
            @Override public void onConnected(String device) {
                ui.post(() -> {
                    setState("已连接 " + device + ",握手中");
                    log("LINK", "connected " + device);
                    if (connectStageView != null) connectStageView.setText("① 蓝牙已连接 " + device + (KIND_BLE.equals(linkKind) ? ",发现服务/订阅通知中…" : ",开始 L2/L3 握手…"));
                });
            }
            @Override public void onHandshake(String mode, String detail) {
                ui.post(() -> {
                    log("HS", "[" + mode + "] " + detail);
                    setState("已连接(" + mode + " 握手)");
                    if (connectStageView != null) connectStageView.setText("② 握手: [" + mode + "] " + detail);
                });
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
                    pendingTotal++;
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
        };
        // 2026-11-18 双传输:官方 VCI(SPP) 或 BLE-RS485 透传模块(TD5322A 等);监听器同一套
        link = KIND_BLE.equals(linkKind) ? new BleRs485Link(listener) : new VciSppLink(listener);
    }

    private void closeLink() {
        if (link != null) {
            link.close();
            link = null;
            log("LINK", "已断开(释放链路)");
        }
        setState("未连接");
    }

    /** ABS 排气等依赖 VCI 计算 L2 key 的操作,透传模块做不到 → 明确拒绝。 */
    private boolean requireVciForKeyOps(String op) {
        if (KIND_BLE.equals(linkKind)) {
            toast(op + " 需官方 VCI:ABS 安全 key 由 VCI 内部计算,BLE 透传模块无法提供");
            return false;
        }
        return true;
    }

    /** picker 扫描前置权限:CONNECT 必需;31+ 还要 SCAN,30- 要定位(仅 BLE 列表需要)。 */
    private boolean pickerPermsReady(boolean needScan) {
        if (!btReady()) return false;
        if (!needScan) return true;
        java.util.List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_SCAN);
        else want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        for (String p : want) {
            if (ContextCompat.checkSelfPermission(requireContext(), p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(want.toArray(new String[0]), REQUEST_BT_SCAN);
                toast("需要蓝牙扫描权限,授权后再点一次扫描");
                return false;
            }
        }
        return true;
    }

    /** 4-hex 短 UUID(如 FFE0)→ 标准 128 位;空/非法返回 null。 */
    private static java.util.UUID expandUuid(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        if (s.matches("(?i)^[0-9a-f]{4}$")) {
            return java.util.UUID.fromString("0000" + s.toLowerCase() + "-0000-1000-8000-00805f9b34fb");
        }
        try { return java.util.UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private void applyUuidOverridesFromPrefs(android.content.SharedPreferences sp) {
        BleRs485Link.overrideService = expandUuid(sp.getString(PREF_SVC_UUID, null));
        BleRs485Link.overrideWrite = expandUuid(sp.getString(PREF_WR_UUID, null));
        BleRs485Link.overrideNotify = expandUuid(sp.getString(PREF_ND_UUID, null));
    }

    /** 蓝牙模块选择器:传输切换 + 设备列表(SPP 已配对 / BLE 扫描) + 高级 UUID 覆盖。 */
    private void showModulePicker() {
        final Context ctx = requireContext();
        final android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS_DIAG, Context.MODE_PRIVATE);
        final String[] kind = {sp.getString(PREF_KIND, KIND_VCI)};
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        // 传输切换
        com.google.android.material.button.MaterialButtonToggleGroup tg =
                new com.google.android.material.button.MaterialButtonToggleGroup(ctx);
        tg.setSingleSelection(true);
        tg.setSelectionRequired(true);
        final com.google.android.material.button.MaterialButton btnVci =
                new com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnVci.setText("官方 VCI(SPP)");
        final com.google.android.material.button.MaterialButton btnBle =
                new com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnBle.setText("BLE 透传(TD5322A 等)");
        tg.addView(btnVci);
        tg.addView(btnBle);
        (KIND_BLE.equals(kind[0]) ? btnBle : btnVci).setChecked(true);
        box.addView(tg);

        final TextView status = new TextView(ctx);
        status.setPadding(0, pad / 2, 0, pad / 2);
        box.addView(status);

        final android.widget.ListView list = new android.widget.ListView(ctx);
        final java.util.List<android.bluetooth.BluetoothDevice> devices = new ArrayList<>();
        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (240 * getResources().getDisplayMetrics().density));
        list.setLayoutParams(lp);
        box.addView(list);

        final com.google.android.material.button.MaterialButton action =
                new com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        box.addView(action);

        // 高级 UUID 覆盖(BLE;留空=自动 profile/自动发现)
        final TextView advLabel = new TextView(ctx);
        advLabel.setPadding(0, pad / 2, 0, 0);
        advLabel.setText("高级:自定义 service / write / notify UUID(留空自动识别)");
        box.addView(advLabel);
        android.widget.LinearLayout row1 = new android.widget.LinearLayout(ctx);
        row1.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        final EditText svcEdit = new EditText(ctx);
        svcEdit.setHint("service(如 FFE0)");
        svcEdit.setText(sp.getString(PREF_SVC_UUID, ""));
        svcEdit.setSingleLine();
        svcEdit.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final EditText wrEdit = new EditText(ctx);
        wrEdit.setHint("write(如 FFE1)");
        wrEdit.setText(sp.getString(PREF_WR_UUID, ""));
        wrEdit.setSingleLine();
        wrEdit.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(svcEdit); row1.addView(wrEdit);
        box.addView(row1);
        final EditText ndEdit = new EditText(ctx);
        ndEdit.setHint("notify(如 FFE1,可与 write 相同)");
        ndEdit.setText(sp.getString(PREF_ND_UUID, ""));
        ndEdit.setSingleLine();
        box.addView(ndEdit);

        // 状态刷新 + 列表加载
        final Runnable[] reload = new Runnable[1];
        reload[0] = () -> {
            boolean ble = KIND_BLE.equals(kind[0]);
            adapter.clear();
            devices.clear();
            action.setText(ble ? "扫描 BLE 设备(12 秒)" : "刷新已配对设备");
            advLabel.setVisibility(ble ? View.VISIBLE : View.GONE);
            svcEdit.setVisibility(ble ? View.VISIBLE : View.GONE);
            wrEdit.setVisibility(ble ? View.VISIBLE : View.GONE);
            ndEdit.setVisibility(ble ? View.VISIBLE : View.GONE);
            if (ble) {
                status.setText("点「扫描」搜索附近的 BLE 透传模块;★=疑似串口透传模块");
            } else {
                BluetoothAdapter bt = btAdapter();
                if (bt == null) { status.setText("蓝牙不可用"); return; }
                java.util.Set<android.bluetooth.BluetoothDevice> bonded = bt.getBondedDevices();
                if (bonded == null || bonded.isEmpty()) { status.setText("无已配对设备;先用系统蓝牙设置配对 VCI,或直接手输 MAC"); return; }
                status.setText("已配对设备(" + bonded.size() + ");点选一行回填地址");
                java.util.List<android.bluetooth.BluetoothDevice> sorted = new ArrayList<>(bonded);
                java.util.Collections.sort(sorted, (a, b) -> safeName(a).compareToIgnoreCase(safeName(b)));
                for (android.bluetooth.BluetoothDevice d : sorted) {
                    devices.add(d);
                    adapter.add(safeName(d) + "\n" + d.getAddress());
                }
            }
        };
        tg.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            kind[0] = id == btnVci.getId() ? KIND_VCI : KIND_BLE;
            reload[0].run();
        });
        reload[0].run();

        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        action.setOnClickListener(v -> {
            if (KIND_BLE.equals(kind[0])) {
                if (!pickerPermsReady(true)) return;
                BluetoothAdapter bt = btAdapter();
                bleScanner = bt == null ? null : bt.getBluetoothLeScanner();
                if (bleScanner == null) { toast("BLE 扫描不可用(蓝牙未开启?)"); return; }
                scanFound.clear();
                scanNames.clear();
                adapter.clear();
                devices.clear();
                status.setText("扫描中…(12 秒;★=疑似串口透传模块)");
                bleScanCb = new android.bluetooth.le.ScanCallback() {
                    @Override public void onScanResult(int callbackType, android.bluetooth.le.ScanResult r) {
                        android.bluetooth.BluetoothDevice d = r.getDevice();
                        String name = safeName(d);
                        String mac = d.getAddress();
                        boolean isNew = !scanFound.containsKey(mac);
                        scanFound.put(mac, d);
                        scanNames.put(mac, name);
                        if (isNew) {
                            devices.add(d);
                            ui.post(() -> adapter.add((BLE_NAME_HINT.matcher(name + " " + mac).find() ? "★ " : "")
                                    + (name.isEmpty() ? "(无名)" : name) + "\n" + mac + "  " + r.getRssi() + "dBm"));
                        }
                    }
                    @Override public void onScanFailed(int errorCode) {
                        ui.post(() -> status.setText("扫描失败 code=" + errorCode + "(检查定位/扫描权限)"));
                    }
                };
                try {
                    bleScanner.startScan(bleScanCb);
                } catch (SecurityException | IllegalStateException se) {
                    status.setText("无法启动扫描:" + se.getMessage());
                    return;
                }
                ui.postDelayed(() -> stopBleScan(status, scanFound.size()), 12000);
            } else {
                reload[0].run();
            }
        });

        list.setOnItemClickListener((parent, view, position, id2) -> {
            if (position < 0 || position >= devices.size()) return;
            android.bluetooth.BluetoothDevice d = devices.get(position);
            String mac = d.getAddress();
            String name = KIND_BLE.equals(kind[0]) ? scanNames.getOrDefault(mac, safeName(d)) : safeName(d);
            sp.edit().putString(PREF_KIND, kind[0]).putString(PREF_MAC, mac).putString(PREF_NAME, name).apply();
            linkKind = kind[0];
            applyUuidOverrides(svcEdit, wrEdit, ndEdit, sp);
            EditText macEdit = requireView().findViewById(R.id.et_diag_mac);
            macEdit.setText(mac);
            stopBleScan(null, 0);
            toast("已选择 " + (name.isEmpty() ? mac : name) + "(" + (KIND_BLE.equals(kind[0]) ? "BLE 透传" : "官方 VCI") + ")");
            if (dlg[0] != null) dlg[0].dismiss();
        });

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        scroll.addView(box);
        dlg[0] = new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("选择蓝牙模块")
                .setView(scroll)
                .setPositiveButton("完成", (d, w) -> {
                    sp.edit().putString(PREF_KIND, kind[0]).apply();
                    linkKind = kind[0];
                    applyUuidOverrides(svcEdit, wrEdit, ndEdit, sp);
                    stopBleScan(null, 0);
                })
                .setOnDismissListener(d -> stopBleScan(null, 0))
                .show();
    }

    private void applyUuidOverrides(EditText svcEdit, EditText wrEdit, EditText ndEdit, android.content.SharedPreferences sp) {
        java.util.UUID s = expandUuid(svcEdit.getText().toString());
        java.util.UUID w = expandUuid(wrEdit.getText().toString());
        java.util.UUID n = expandUuid(ndEdit.getText().toString());
        BleRs485Link.overrideService = s;
        BleRs485Link.overrideWrite = w;
        BleRs485Link.overrideNotify = n;
        sp.edit().putString(PREF_SVC_UUID, svcEdit.getText().toString().trim())
                .putString(PREF_WR_UUID, wrEdit.getText().toString().trim())
                .putString(PREF_ND_UUID, ndEdit.getText().toString().trim())
                .apply();
    }

    private void stopBleScan(TextView status, int found) {
        try {
            if (bleScanner != null && bleScanCb != null) bleScanner.stopScan(bleScanCb);
        } catch (SecurityException | IllegalStateException ignored) { }
        bleScanner = null;
        bleScanCb = null;
        if (status != null) status.setText(found > 0 ? ("扫描结束,发现 " + found + " 台;点选一行回填") : "扫描结束,未发现设备(确认模块上电)");
    }

    private static String safeName(android.bluetooth.BluetoothDevice d) {
        try { return d.getName() == null ? "" : d.getName(); } catch (SecurityException e) { return ""; }
    }

    private BluetoothAdapter btAdapter() {
        BluetoothManager bm = (BluetoothManager) requireContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
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
    // 官方主从切换(2026-11-10 00:20 删卡会话逐字节对齐;2026-11-15 失败复盘修正)
    // 根因:心跳 FFA 复用握手 seq=0000 → VCI 回 00F000C0 blob 后停止 L3 转发
    // (11-15 02:33/35/37 与 11-09 03:53 会话实证;官方会话 FFA 序号递增 56/54s)。
    // 修正:阶段开道 FFA×2(递增 seq)→ 前导 → 挂起心跳+停背景轮询 →
    // [poll+0x89]×5 → 0x71(20) → 边轮询边等 0x79/0x17(车辆数据只随 69 应答下发)
    // → 恢复心跳/轮询 → 用户确认。
    // =====================================================================

    private boolean opGate(String op) {
        if (!requireLink()) return false;
        if (!link.isDataReady()) { toast(KIND_BLE.equals(linkKind) ? "总线通道未就绪,先重新连接模块" : "L3 通道未验证,先重新连接 VCI"); return false; }
        if (flowBusy) { toast("有流程进行中"); return false; }
        if (!KIND_BLE.equals(linkKind)) {
            long age = link.lastDataRxAgeMs();
            if (age > 5000) log("WARN", "L3 通道疑似失活(最后数据 " + (age / 1000) + "s 前);若操作无应答请断开重连 VCI");
        }
        flowBusy = true;
        log("OP", op + ":流程开始(按官方流程)");
        return true;
    }

    private void releaseGate() { flowBusy = false; }

    /** 可抛受检异常的操作阶段(官方流程续段)。 */
    private interface DiagStep { void run() throws Exception; }

    /** 官方主从切换;切换后弹确认,通过后回调 onSwitched(后台线程执行)。 */
    private void officialMasterSlave(DiagLink active, String op, DiagStep onSwitched) {
        new Thread(() -> {
            try {
                stageOpen(active, op + " 切换开道");
                active.suspendKeepAlive();
                active.stopPolling(); // 0x89 突发窗口复刻官方节奏(仅 poll+write 对)
                int preTraffic = probeBusTraffic(active, 1500); // 官方从任意状态诱导切换;零 TX 探测仅记录状态
                log("OP", "总线探测: " + preTraffic + " 帧车流" + (preTraffic > 0 ? "(主机广播在线)" : "(静默:可能从机/休眠,0x89 诱导照发)"));
                flowStage(op + " ①/③ 主从切换:0x89×5 发送中(官方节奏 158/112/1105/1107ms)");
                log("OP", "开始 0x89×5(官方节奏 158/112/1105/1107ms)");
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
                flowStage("①/③ 被动等待 0x79(≤4s)/0x17 广播(≤8-12s),期间零 TX(官方同款静默)");
                String confirm79 = pollAwaitNode(active, 0x79, null, 4000);
                boolean got79 = confirm79 != null;
                String wake17 = pollAwaitNode(active, 0x17, null, got79 ? 8000 : 8000); // 从机超时预算~1min,总等待收紧
                // 黄金抓取:0x71 之后到下一次有意写入之间官方完全静默,VCI 主动推送应答;
                // 此处不恢复心跳/轮询(此前在此恢复导致总线被扰死亡→仪表重启),
                // 用户确认后由执行段/退出帧(exitMasterSlaveQuiet)/catch 恢复。
                boolean wake = wake17 != null;
                log("OP", (got79 ? "0x79 已应答(官方同款)" : "0x79 未应答")
                        + (wake ? ";检测到 0x17 广播(从机上线信号)" : ";未检测到 0x17 广播"));
                final boolean ok = got79 || wake;
                ui.post(() -> {
                    if (!isAdded()) { releaseGate(); return; }
                    dismissFlowProgress(); // 让位给切换结果确认框(执行段会重新弹进度框)
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(ok ? "主从切换完成" : "主从切换状态未知")
                            .setMessage(ok ? "已收到 0x79/0x17 应答,仪表应已进入从机模式。\n注意:从机模式下长时间不写入,仪表会超时自动恢复(黑屏,约1分钟自愈)——请尽快点「继续」完成「" + op + "」执行段。"
                                    : "未检测到 0x79/0x17 应答,但切换可能已生效(实车观察:状态未知时仪表也已切从机)。\n请观察仪表尽快确认;从机下长时间不写入会超时自动恢复(黑屏,约1分钟自愈);中止后可用「恢复主机」按钮退出并验证。")
                            .setNegativeButton("中止", (d, w) -> { log("OP", op + " 用户中止(切换后)"); exitMasterSlaveQuiet(active); releaseGate(); })
                            .setPositiveButton("继续", (d, w) -> new Thread(() -> {
                                try { onSwitched.run(); }
                                catch (Exception e) {
                                    log("ERR", op + " 执行段失败: " + e.getMessage());
                                    exitMasterSlaveQuiet(active);
                                    releaseGate();
                                    ui.post(() -> {
                                        dismissFlowProgress();
                                        if (!isAdded()) return;
                                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                                .setTitle(op + " 执行失败")
                                                .setMessage("执行段出错:" + e.getMessage() + "\n已尝试发送退出帧并恢复心跳。请导出日志排查。")
                                                .setPositiveButton("知道了", null).show();
                                    });
                                }
                            }, "diag-op-stage2").start())
                            .setCancelable(false).show();
                });
            } catch (Exception e) {
                log("ERR", op + " 主从切换失败: " + e.getMessage());
                active.resumeKeepAlive();
                active.startPolling();
                releaseGate();
                ui.post(() -> {
                    dismissFlowProgress();
                    if (!isAdded()) return;
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(op + " 失败")
                            .setMessage("主从切换阶段出错:" + e.getMessage() + "\n已尝试发送退出帧并恢复心跳。请导出日志排查。")
                            .setPositiveButton("知道了", null).show();
                });
            }
        }, "diag-master").start();
    }

    /** 发送官方退出帧 0x71(byte6=0x40,删卡会话 16:20:13.918 实证)并恢复心跳/轮询(失败不打断流程)。 */
    private void exitMasterSlaveQuiet(DiagLink active) {
        try {
            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
            active.sendRs485(ZeehoRs485Ops.MASTER_SLAVE_EXIT_71);
            log("TX", "主从退出 0x71(byte6=0x40)");
        } catch (Exception e) {
            log("ERR", "主从退出失败: " + e.getMessage());
        } finally {
            active.resumeKeepAlive();
            active.startPolling();
        }
    }

    /** 虚拟游标(mark 存 pendingTotal)→ 当前真实下标;被环形裁剪的旧帧不可达则钳 0。须持 responseLock。 */
    private int pendingIdx(int mark) {
        int i = mark - (pendingTotal - pendingResponses.size());
        return i < 0 ? 0 : i;
    }

    /** 被动探测总线车流(等待期零 TX):统计 mark 后来自车辆方向的帧数;>0 = 主机广播在线。 */
    private int probeBusTraffic(DiagLink active, long ms) {
        int mark;
        synchronized (responseLock) { mark = pendingTotal; }
        long deadline = android.os.SystemClock.elapsedRealtime() + ms;
        while (android.os.SystemClock.elapsedRealtime() < deadline && active.isConnected()) sleepMs(150);
        synchronized (responseLock) {
            int n = 0;
            for (int i = pendingIdx(mark); i < pendingResponses.size(); i++) {
                if (pendingResponses.get(i).fromVehicle) n++;
            }
            return n;
        }
    }

    /** 恢复主机:官方退出帧 0x71(byte6=0x40)→0x79 确认→0x80→0x88(VIN)验证主机寻址;从机超时 ~1min 自动恢复兜底。 */
    private void beginRestoreMaster() {
        if (!opGate("恢复主机")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("恢复主机状态")
                .setMessage("发送官方退出帧 0x71(byte6=0x40)并用 0x80→0x88(VIN)验证主机寻址是否恢复。" + "\n\n" + "从机模式下长时间不写入,仪表也会超时自动恢复(约1分钟,期间可能黑屏)。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final DiagLink active = link;
                    new Thread(() -> {
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        try {
                            active.suspendKeepAlive();
                            active.stopPolling();
                            showFlowProgress("恢复主机", "①/③ 总线探测+开道…");
                            int traffic = probeBusTraffic(active, 1500);
                            log("OP", "总线探测: " + traffic + " 帧车流" + (traffic > 0 ? "(主机广播在线:可能本就主机态)" : "(静默:可能从机/休眠)"));
                            stageOpen(active, "恢复主机");
                            flowStage("①/③ 退出帧 0x71(40) + 0x79 确认(≤2.5s)…");
                            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                            active.sendRs485(ZeehoRs485Ops.MASTER_SLAVE_EXIT_71);
                            log("TX", "恢复主机 退出 0x71(byte6=0x40)");
                            String r79 = pollAwaitNode(active, 0x79, null, 2500);
                            log("OP", "0x79 " + (r79 != null ? "已应答(总线控制帧在线)" : "无应答") + " " + secSince(t0));
                            flowStage("②/③ 0x80 主机寻址验证(0x88 VIN,被动等待 ≤25s;官方首批应答可延迟 ~21s)…");
                            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                            active.sendRs485(ZeehoRs485Ops.VERSION_QUERY_80);
                            log("TX", "恢复主机 0x80(VIN 查询)");
                            String vinPayload = pollAwaitNode(active, 0x88, null, 25000);
                            String vin = vinPayload != null ? asciiAfter(vinPayload, 3) : null;
                            flowStage("③/③ 完成");
                            log("OP", "0x88 " + (vinPayload != null ? ("已应答 VIN=" + vin) : "无应答") + " " + secSince(t0));
                            final String title = vinPayload != null ? "主机已恢复" : "恢复结果";
                            final String msg = vinPayload != null
                                    ? "主机寻址已恢复(0x80→0x88 应答,VIN=" + vin + ")。" + "\n" + "仪表已回到主机(正常)状态,可直接使用车辆功能。"
                                    : r79 != null
                                    ? "退出帧已确认(0x79 应答),但 25s 内 0x88 未应答。" + "\n" + "从机超时自动恢复约 1 分钟(期间可能黑屏);稍后可再点本按钮验证,或断电重启。"
                                    : "0x79/0x88 均无应答:仪表可能本就在主机态,或总线静默。" + "\n" + "若仪表黑屏,等待 ~1 分钟自动恢复;仍未恢复可断电重启。";
                            ui.post(() -> {
                                dismissFlowProgress();
                                if (!isAdded()) return;
                                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(title).setMessage(msg)
                                        .setPositiveButton("知道了", null).show();
                            });
                        } catch (Exception e) {
                            log("ERR", "恢复主机失败: " + e.getMessage());
                            ui.post(() -> {
                                dismissFlowProgress();
                                if (!isAdded()) return;
                                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("恢复主机失败").setMessage(e.getMessage() + "\n" + "可断开重连后再试;仪表从机超时会自动恢复(约1分钟)。")
                                        .setPositiveButton("知道了", null).show();
                            });
                        } finally {
                            active.resumeKeepAlive();
                            active.startPolling();
                            releaseGate();
                        }
                    }, "diag-restore").start();
                }).setCancelable(false).show();
    }

    /** 发送 L3 会话前导(60×3 + 69×3,帧间隔 15ms)。 */
    private void sendPreamble(DiagLink active) throws java.io.IOException {
        for (byte[] cmd : ZeehoRs485Ops.sessionSetupCommands()) {
            active.send(cmd[1] & 0xFF, java.util.Arrays.copyOfRange(cmd, 2, cmd.length));
            sleepMs(15);
        }
    }

    /** 官方阶段开道:FFA×2(递增 L2 seq)→ 45ms → 前导 60×3+69×3。
     *  官方在 0x89 突发、0x71、0x80、每节点版本/DID 查询、退出前都重复整套开道。 */
    private void stageOpen(DiagLink active, String label) throws java.io.IOException {
        active.sendFfaPair();
        sleepMs(45);
        sendPreamble(active);
        log("OP", label + ":阶段开道完成(FFA×2+前导 60×3+69×3)");
    }

    /** 被动等待指定节点 CF 应答(黄金抓取实证:VCI 主动推送全部应答,官方等待期零 TX;我方等待期轮询与官方零 TX 行为不符,实车曾致总线静默)。
     *  应答节点 = 查询节点|0x80(如 0x14→0x94)。prefix 可选 payload 前缀;命中返回 payload hex,超时 null。 */
    private String pollAwaitNode(DiagLink active, int node, int[] payloadPrefix, long timeoutMs) {
        int mark;
        synchronized (responseLock) { mark = pendingTotal; }
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (active.isConnected()) {
            String hit = scanNodeSince(mark, node, payloadPrefix);
            if (hit != null) return hit;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                scanNodeSinceDebug(mark, node, payloadPrefix); // 4.9.4: 超时逐帧诊断(定位"帧已在列表却未命中")
                return null;
            }
            sleepMs(120);
        }
        return null;
    }

    /** 扫描 mark 之后来自指定节点(可选 payload 前缀)的 CF payload hex;无命中返回 null。 */
    private String scanNodeSince(int mark, int node, int[] payloadPrefix) {
        synchronized (responseLock) {
            for (int i = pendingIdx(mark); i < pendingResponses.size(); i++) {
                VciLink.DataFrame f = pendingResponses.get(i);
                if (!f.fromVehicle) continue;
                for (byte[] raw : VciLink.extractCfFrames(f)) {
                    try {
                        Rs485.Frame fr = Rs485.decode(raw);
                        if (fr.node != node) continue;
                        if (payloadPrefix != null && !hasPrefix(fr.payload, payloadPrefix)) continue;
                        return Hex.format(fr.payload).replace(" ", "");
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        return null;
    }

    private static boolean hasPrefix(byte[] payload, int[] prefix) {
        if (payload.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((payload[i] & 0xFF) != (prefix[i] & 0xFF)) return false;
        }
        return true;
    }

    /** 从 payload hex 的第 skipBytes 字节起提取可打印 ASCII(版本串等);空则原样返回。 */
    private static String asciiAfter(String hex, int skipBytes) {
        if (hex == null) return "无应答";
        StringBuilder sb = new StringBuilder();
        for (int i = skipBytes * 2; i + 1 < hex.length(); i += 2) {
            int c = Integer.parseInt(hex.substring(i, i + 2), 16);
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : hex;
    }

    /** "A1B2C3D4E5F6" → "A1:B2:C3:D4:E5:F6"(不足 12 位原样返回)。 */
    private static String formatMac(String hex) {
        if (hex == null || hex.length() < 12) return hex == null ? "无应答" : hex;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 2 <= 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex.substring(i, i + 2).toUpperCase(java.util.Locale.US));
        }
        return sb.toString();
    }

    // ===== EOL 动态两步认证(协议总汇 §3.2.1;静态官方对仅作回退) =====

    /**
     * 帧①(challenge=1,proof=0x7E7)→ 等 FC 81 00 <车辆挑战4B> → 帧②(回传挑战+challenge×2023)→ 等 FC 82。
     * 2.5s 内无 FC 81 挑战则回退官方静态对(E33A…/AFC0…,删卡会话逐字节,两步挑战互不联动)。
     */
    private void authorizeEol(DiagLink active) throws Exception {
        // 黄金删卡会话实证:官方直接发静态对(F4 09 01 E33A…/F4 09 02 AFC0…),车辆均回 FC 81/82;
        // FC 81 内 4B 挑战是随机值(实见 2f985e45),与静态对无关,仅记录不参与计算。
        int mark;
        synchronized (responseLock) { mark = pendingTotal; }
        try {
            requestResponse(active, ZeehoRs485Ops.EOL_AUTH_1, 0x81);
            byte[] challenge = scanFcChallengeSince(mark);
            if (challenge != null) log("OP", "EOL 授权 FC 81 挑战(信息性记录): " + Hex.format(challenge).replace(" ", ""));
            requestResponse(active, ZeehoRs485Ops.EOL_AUTH_2, 0x82);
            log("OP", "EOL 授权完成(静态对,FC 81/82 应答)");
            return;
        } catch (java.io.IOException e) {
            // 跨车型兼容(AE7 Pro 等 Gemini 同架构车型):静态厂钥可能随车型不同被拒,
            // 回退协议总汇 §3.2.1 动态挑战授权(帧① F4 09 01 0100… → FC 81 00 <挑战> → 帧② 02+挑战+×2023),
            // proof 由车辆自身挑战计算,车型无关。
            log("OP", "静态对未获 FC 应答(" + e.getMessage() + "),回退动态挑战授权(跨车型兼容)");
        }
        int mark2;
        synchronized (responseLock) { mark2 = pendingTotal; }
        requestResponse(active, ZeehoRs485Ops.eolAuth1Dynamic(), 0x81);
        byte[] dynChallenge = scanFcChallengeSince(mark2);
        if (dynChallenge == null) throw new java.io.IOException("动态授权:FC 81 应答未携带 4B 挑战");
        log("OP", "动态授权挑战: " + Hex.format(dynChallenge).replace(" ", ""));
        requestResponse(active, ZeehoRs485Ops.eolAuth2For(dynChallenge), 0x82);
        log("OP", "EOL 授权完成(动态挑战,FC 82 应答)");
    }

    /** 扫描 mark 后 FC 81 00 <challenge×4> 应答;返回 4B 挑战或 null。 */
    private byte[] scanFcChallengeSince(int mark) {
        synchronized (responseLock) {
            for (int i = pendingIdx(mark); i < pendingResponses.size(); i++) {
                VciLink.DataFrame f = pendingResponses.get(i);
                if (!f.fromVehicle) continue;
                for (byte[] raw : VciLink.extractCfFrames(f)) {
                    try {
                        Rs485.Frame fr = Rs485.decode(raw);
                        if (fr.node == 0xFC && fr.payload.length >= 6
                                && (fr.payload[0] & 0xFF) == 0x81 && (fr.payload[1] & 0xFF) == 0x00) {
                            return java.util.Arrays.copyOfRange(fr.payload, 2, 6);
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        return null;
    }

    /** 先标记后发送再等待(消除 pollAndSend→pollAwaitNode 两步间的应答竞态)。超时返回 null。 */
    private String pollSendAwait(DiagLink active, String title, byte[] cf, int node, int[] payloadPrefix, long timeoutMs) {
        int mark;
        synchronized (responseLock) { mark = pendingTotal; }
        pollAndSend(active, title, cf);
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (active.isConnected()) {
            String hit = scanNodeSince(mark, node, payloadPrefix);
            if (hit != null) return hit;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return null;
            sleepMs(120); // 被动等待:VCI 主动推送应答,等待期不发轮询(黄金抓取同款)
        }
        return null;
    }

    // ===== 扩展读取(协议总汇 §3.4 DID 全表 + §3.2 EOL 读族) =====

    private void beginExtendedRead() {
        if (!opGate("扩展读取")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("扩展读取(协议总汇)")
                .setMessage("主从切换(进入从机模式;超时未写入会自动恢复)→ EOL 授权 → 节点 0x14 DID 全表(F187/F18A/F197/F193/F195/0203/0309/0312)→ F4 50 电池电压 / F4 3D 保养里程 / F4 6F 状态位图 0-2 页。\n全部只读;帧式按文档【汇】,子码后 00 填充为【推】。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final DiagLink active = link;
                    officialMasterSlave(active, "扩展读取", () -> extendedReadRun(active));
                }).show();
    }

    private void extendedReadRun(DiagLink active) {
        try {
            authorizeEol(active);
            byte[] cfg = ZeehoRs485Ops.sessionTimeoutRaise();
            active.send(cfg[1] & 0xFF, java.util.Arrays.copyOfRange(cfg, 2, cfg.length));
            sleepMs(50);
            StringBuilder sb = new StringBuilder();
            // ① DID 全表(节点 0x14 → 应答 0x94;正向应答前缀 62 + DID)
            for (Object[] d : ZeehoRs485Ops.DID_TABLE) {
                int did = (Integer) d[0];
                String name = (String) d[1];
                String title = "DID " + name;
                stageOpen(active, title);
                int[] prefix = {0x62, (did >> 8) & 0xFF, did & 0xFF};
                String resp = pollSendAwait(active, title, ZeehoRs485Ops.udsReadDid(0x14, did), 0x94, prefix, 2500);
                String line;
                if (resp == null) {
                    line = name + ": 无应答";
                } else if (did == 0x0309 && resp.length() >= 12) {
                    int v = Integer.parseInt(resp.substring(6, 12), 16);
                    line = name + ": " + String.format(java.util.Locale.US, "%.1f km", v / 10.0) + "  [" + resp + "]";
                } else if (did == 0xF197) {
                    line = name + ": " + (resp.length() >= 18
                            ? formatMac(resp.substring(6, 18)) : asciiAfter(resp, 3)) + "  [" + resp + "]";
                } else {
                    line = name + ": " + asciiAfter(resp, 3) + "  [" + resp + "]";
                }
                sb.append(line).append('\n');
                log("OP", "扩展读取 " + line);
                sleepMs(150);
            }
            // ② EOL 读族(F4 子命令,应答节点 0xFC)
            sb.append(readEolLine(active, "F4 50 电池电压", ZeehoRs485Ops.eolBatteryVoltage())).append('\n');
            sb.append(readEolLine(active, "F4 3D 保养里程", ZeehoRs485Ops.eolMaintMileage())).append('\n');
            for (int page = 0; page <= 2; page++) {
                sb.append(readEolLine(active, "F4 6F 状态位图 页" + page, ZeehoRs485Ops.eolStatusBitmap(page))).append('\n');
            }
            exitMasterSlaveQuiet(active);
            releaseGate();
            showResult("扩展读取(协议总汇)", sb.toString());
        } catch (Exception e) {
            log("ERR", "扩展读取失败: " + e.getMessage());
            exitMasterSlaveQuiet(active);
            releaseGate();
            showResult("扩展读取(协议总汇)", "失败: " + e.getMessage());
        }
    }

    /** EOL 单条只读:F4 子命令 → 等 0xFC 应答,返回展示行(原始 hex,格式未在文档定义)。 */
    private String readEolLine(DiagLink active, String title, byte[] cf) {
        String resp = pollSendAwait(active, title, cf, 0xFC, null, 2500);
        return title + ": " + (resp != null ? resp : "无应答");
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

    private void startDeleteFlow(DiagLink active, int deleteType) {
        officialMasterSlave(active, "删卡", () -> authorizeDelete(active, deleteType));
    }

    private void authorizeDelete(DiagLink active, int deleteType) throws Exception {
        authorizeEol(active);
        log("OP", "删卡授权完成");
        ui.post(() -> {
            if (!isAdded()) { releaseGate(); return; }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("授权完成")
                    .setMessage("继续将实际删除原厂卡(不可恢复)。执行?")
                    .setNegativeButton("取消", (d, w) -> { exitMasterSlaveQuiet(active); releaseGate(); })
                    .setPositiveButton("执行删卡", (d, w) -> new Thread(() -> executeDelete(active, deleteType), "diag-del-write").start())
                    .setCancelable(false).show();
        });
    }

    private void executeDelete(DiagLink active, int deleteType) {
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

    private void startStudyFlow(DiagLink active, int cardType) {
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

    private void studyAuth(DiagLink active, int studyCardType) {
        try {
            authorizeEol(active);
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
                .setMessage("官方流程为总线寻址扫描:0x89 寻址载荷×5(1107ms 均匀节奏)→ 0x71(04) → 0x80 读 VIN(节点 0x88),再逐节点读 22F193/F195(重试×3)。\n本流程不做主从切换、仪表不重启;结果显示在本页「诊断结果」区。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final DiagLink active = link;
                    new Thread(() -> {
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        try {
                            active.suspendKeepAlive();
                            active.stopPolling();
                            showFlowProgress("读版本", "①/③ 开道(FFA×2+前导)…");
                            stageOpen(active, "读版本");
                            flowStage("①/③ 寻址扫描 0x89×5(官方 1107ms 均匀节奏)…");
                            for (int i = 0; i < 5; i++) {
                                if (link != active || !active.isConnected()) throw new java.io.IOException("连接已断开");
                                active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                                active.sendRs485(ZeehoRs485Ops.VERSION_SCAN_89);
                                log("TX", "读版本 寻址 0x89 #" + (i + 1));
                                sleepMs(ZeehoRs485Ops.VERSION_SCAN_DELAY_MS);
                            }
                            flowStage("①/③ 确认帧 0x71(04)(0x79 应答最晚 ~21s:改被动收取)…");
                            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                            active.sendRs485(ZeehoRs485Ops.VERSION_CONFIRM_71);
                            log("TX", "读版本 确认 0x71(byte6=0x04)");
                            int vinMark;
                            synchronized (responseLock) { vinMark = pendingTotal; }
                            flowStage("②/③ 0x80 VIN 查询(官方首批应答延迟可达 ~21s:不阻塞,流程末被动收取)…");
                            pollAndSend(active, "版本 0x80(VIN 查询)", ZeehoRs485Ops.VERSION_QUERY_80);
                            StringBuilder sbNodes = new StringBuilder();
                            int silentNodes = 0;
                            int nodeIdx = 0, nodeTotal = ZeehoRs485Ops.VERSION_NODES.length;
                            for (int node : ZeehoRs485Ops.VERSION_NODES) {
                                nodeIdx++;
                                flowStage("③/③ 节点 " + Integer.toHexString(node) + "(" + nodeIdx + "/" + nodeTotal + ") 22F193/F195(重试×3)…");
                                stageOpen(active, "读版本 node " + Integer.toHexString(node));
                                String hw = versionQueryDid(active, node, 0xF193);
                                String sw = versionQueryDid(active, node, 0xF195);
                                String line = "node " + Integer.toHexString(node) + nodeName(node) + ": HW=" + verVal(hw) + "  SW=" + verVal(sw);
                                log("OP", "版本 " + line + " " + secSince(t0));
                                if (hw == null && sw == null) silentNodes++;
                                sbNodes.append(line).append('\n');
                            }
                            String vinPayload = scanNodeSince(vinMark, 0x88, null);
                            String vin = vinPayload != null ? asciiAfter(vinPayload, 3) : null;
                            String confirm79 = scanNodeSince(vinMark, 0x79, null);
                            log("OP", "0x80 应答(节点 0x88): " + (vinPayload != null ? ("已收 " + vin) : "无(被动窗内未见)") + " " + secSince(t0));
                            log("OP", "确认帧 0x79: " + (confirm79 != null ? "已应答(被动收取)" : "未见"));
                            StringBuilder sb = new StringBuilder();
                            if (vinPayload != null) sb.append("VIN(0x88): ").append(vin).append('\n');
                            sb.append(sbNodes);
                            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                            active.sendRs485(ZeehoRs485Ops.VERSION_EXIT_71);
                            log("TX", "读版本 退出 0x71(byte6=0x40,官方对齐)");
                            log("OP", "读版本完成,总耗时 " + secSince(t0));
                            active.resumeKeepAlive();
                            active.startPolling();
                            releaseGate();
                            dismissFlowProgress();
                            if (vinPayload == null && silentNodes == nodeTotal) {
                                sb.append("[全部节点无应答]").append('\n')
                                  .append("0x80 与 5 个节点 22F193/F195 全部超时 —— 总线静默。常见原因:").append('\n')
                                  .append("· 上一流程残留从机模式 —— 断电重上电车辆后重试;").append('\n')
                                  .append("· L3 通道已失活 —— 断开 VCI 重连后再读;").append('\n')
                                  .append("· 节点确实无应答(0x22/0x21 官方读取也常静默)。");
                                log("WARN", "读版本:0x80+全部节点无应答(总线静默),已附排查提示");
                            }
                            // 结果固定显示在页面"诊断结果"区(对齐官方 zscan 诊断界面),不再弹窗
                            showResult("版本读取", sb.toString());
                        } catch (Exception e) {
                            log("ERR", "读版本失败: " + e.getMessage());
                            try { active.resumeKeepAlive(); active.startPolling(); } catch (Exception ignored) { }
                            releaseGate();
                            ui.post(() -> {
                                dismissFlowProgress();
                                if (!isAdded()) return;
                                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("读版本失败")
                                        .setMessage("出错:" + e.getMessage() + "\n请导出日志排查。")
                                        .setPositiveButton("知道了", null).show();
                            });
                        }
                    }, "diag-version").start();
                }).show();
    }

    /** 读版本单 DID 查询:poll+22F1xx,被动等 62F1xx(应答节点=查询节点|0x80),最多 3 次尝试(官方对静默节点也重试×3)。 */
    private String versionQueryDid(DiagLink active, int node, int did) throws java.io.IOException {
        int[] prefix = {0x62, (did >> 8) & 0xFF, did & 0xFF};
        for (int attempt = 1; attempt <= 3; attempt++) {
            pollAndSend(active, "版本 node " + Integer.toHexString(node) + " 22F1" + Integer.toHexString(did & 0xFF),
                    ZeehoRs485Ops.udsReadDid(node, did));
            // 4.9.4:0x27 首应答实测 1.34s
            String hit = pollAwaitNode(active, node | 0x80, prefix, 1500);
            if (hit != null) return hit;
        }
        return null;
    }

    private String nodeName(int node) {
        if (node == 0x27) return "(控制器)";
        if (node == 0x14) return "(仪表)";
        return "";
    }

    // ===== 读 DTC(主从+授权段=官方删卡会话同构;执行帧 F4 51 00 来自旧黄金抓取) =====

    private void beginDtcRead() {
        if (!opGate("读DTC")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("读故障码")
                .setMessage("主从切换(进入从机模式)→ EOL 授权 → F4 51 00。\n协议总汇:F4 51 = 读 46B 诊断状态;33/36/38 = 写 NVM18/19/20(DTC_0/1/2,20/10/10B),故 46B 推断为 6B 状态头 + 20+10+10 三块。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final DiagLink active = link;
                    officialMasterSlave(active, "读DTC", () -> {
                        authorizeEol(active);
                        log("OP", "读DTC: EOL 授权完成");
                        byte[] cfg = ZeehoRs485Ops.sessionTimeoutRaise();
                        active.send(cfg[1] & 0xFF, java.util.Arrays.copyOfRange(cfg, 2, cfg.length));
                        sleepMs(50);
                        pollAndSend(active, "读DTC F4 51 00", ZeehoRs485Ops.dtcReadEol());
                        String payload = pollAwaitNode(active, 0xFC, null, 3000);
                        String result;
                        if (payload != null) {
                            log("OP", "读DTC 应答 FC payload=" + payload);
                            result = describeDtcPayload(payload);
                            log("OP", "读DTC 解读: " + result);
                        } else {
                            result = "未收到 0xFC 应答(已发送主从切换+授权+51 00,导出日志核查)";
                            log("OP", "读DTC: 3s 内未收到节点 0xFC 应答(导出日志核查)");
                        }
                        exitMasterSlaveQuiet(active);
                        releaseGate();
                        showResult("读 DTC", result + "\n原始: " + (payload != null ? payload : "无"));
                    });
                }).show();
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
                    final DiagLink active = link;
                    officialMasterSlave(active, "清DTC", () -> {
                        authorizeEol(active);
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

    // ===== 写里程(协议总汇 §3.2.2 唯一路线:EOL 0x4F mode0;DID 直写已按车主要求取消) =====

    private void writeMileage() {
        if (!requireLink()) return;
        EditText kmEdit = requireView().findViewById(R.id.et_diag_km);
        String raw = kmEdit.getText().toString().trim();
        if (raw.isEmpty()) { toast("输入里程"); return; }
        double km;
        try { km = Double.parseDouble(raw); } catch (NumberFormatException e) { toast("里程格式不对"); return; }
        if (km < 0 || km > 999999) { toast("里程 0–999999 km"); return; }
        int tenth = (int) Math.round(km * 10);
        // 协议总汇 §3.2.2:EOL 0x4F mode0 = 43 46 F4 06 4F 00 [4B LE] SUM 45 44,raw=km×10,
        // 上限 999999.0(clamp),必须先过 §3.2.1 EOL 认证。流程:
        // 主从切换 → EOL 授权(官方静态对 F4 09 01/02 → FC 81/82)→ 07D0 提时 → 0x4F 写入 → DID 0309 回读核对 → 退出。
        if (!opGate("写里程")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("写里程(EOL 0x4F)")
                .setMessage("协议总汇 §3.2.2 流程:\n①主从切换(进入从机模式,约8-20s+确认;从机下超过约1分钟不写入会超时自动恢复——确认框请尽快点)→ ②EOL 授权(官方静态对,≤6s)→ ③07D0+写前回读(≤3s)→ ④0x4F mode0 写入 " + raw + " km(小端,km×10,≤3s)→ ⑤写后回读(≤3s)→ ⑥退出。\n全程典型 15-30s,每阶段有进度框与已用秒数;结束弹成功与否确认。\n建议先 ±几 km 小步实测(§十.3),断电回读确认不回跳后再写真实值。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final DiagLink active = link;
                    showFlowProgress("写里程 " + raw + " km", "①/③ 主从切换准备(开道+挂起心跳)…");
                    officialMasterSlave(active, "写里程", () -> {
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        showFlowProgress("写里程 " + raw + " km(续)", "②/⑥ EOL 授权(官方静态对 F4 09 01/02 → FC 81/82)…");
                        authorizeEol(active);
                        log("OP", "写里程: EOL 授权完成 " + secSince(t0));
                        flowStage("③/⑥ 07D0 提时 + 写前 DID 0309 回读…");
                        byte[] cfg = ZeehoRs485Ops.sessionTimeoutRaise();
                        active.send(cfg[1] & 0xFF, java.util.Arrays.copyOfRange(cfg, 2, cfg.length));
                        sleepMs(50);
                        String before = readOdoViaDid(active);
                        log("OP", "写前里程回读: " + (before != null ? before : "无应答(继续写入)") + " " + secSince(t0));
                        flowStage("④/⑥ 0x4F mode0 写入(小端 km×10)…");
                        pollAndSend(active, "写里程 0x4F mode0", ZeehoRs485Ops.mileageWriteEol(tenth));
                        String ack = pollAwaitNode(active, 0xFC, null, 3000);
                        log("OP", "0x4F 应答: " + (ack != null ? ack : "无(观察仪表/回读核实)") + " " + secSince(t0));
                        flowStage("⑤/⑥ 写后 DID 0309 回读核对…");
                        sleepMs(500);
                        String after = readOdoViaDid(active);
                        log("OP", "写后里程回读: " + (after != null ? after : "无应答(观察仪表核实)") + " " + secSince(t0));
                        flowStage("⑥/⑥ 发送退出帧 0x71 并恢复心跳…");
                        log("OP", "写里程 执行段完成,总耗时 " + secSince(t0));
                        exitMasterSlaveQuiet(active);
                        releaseGate();
                        dismissFlowProgress();
                        final String beforeF = before, afterF = after, ackF = ack;
                        showResult("写里程 EOL 0x4F", "目标 " + raw + " km\n写前 " + (before != null ? before : "无应答")
                                + "\n0x4F 应答 " + (ack != null ? ack : "无")
                                + "\n写后 " + (after != null ? after : "无应答"));
                        final String target = String.format(java.util.Locale.US, "%.1f", km) + " km";
                        ui.post(() -> {
                            if (!isAdded()) return;
                            String verdict = afterF != null
                                    ? (afterF.equals(target) ? "成功(回读=目标)" : "已写入,回读值不符")
                                    : (ackF != null ? "已发送(回读无应答,看仪表核实)" : "未确认(无应答)");
                            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("写里程:" + verdict)
                                    .setMessage("目标 " + raw + " km\n写前 " + (beforeF != null ? beforeF : "无应答")
                                            + "\n0x4F 应答 " + (ackF != null ? ackF : "无")
                                            + "\n写后回读 " + (afterF != null ? afterF : "无应答")
                                            + "\n\n§十.3 验证:断电重上电后再回读一次,数值不回跳才算持久化成功。")
                                    .setPositiveButton("知道了", null).show();
                        });
                    });
                }).show();
    }

    // ===== AE7 Pro CAN(仪表 0x714/0x794;11-19/11-20 官方会话逐字节;27 密钥算法=Uds.keyLevel1 实证) =====

    /** 在 L3 数据帧里定位 CAN RX 头(07 94 08),返回其后数据起始偏移;无则 -1。 */
    private static int canRxOffset(byte[] d) {
        for (int j = 0; j + 3 <= d.length; j++) {
            if ((d[j] & 0xFF) == 0x07 && (d[j + 1] & 0xFF) == 0x94 && (d[j + 2] & 0xFF) == 0x08) return j + 3;
        }
        return -1;
    }

    /**
     * CAN UDS 交换: 发单帧请求,等待并重组 ISO-TP 应答(SF 直取;FF→发一次流控(官方 01 00 00 前缀)→收 CF)。
     * 返回完整 UDS 应答载荷(含服务字节,如 50 03 … / 62 F1 90 … / 7F …);7F 78 挂起顺延等待;超时 null。
     * 等待期零额外 TX(仅 FF 时发一次流控,与官方一致)。
     */
    private byte[] canExchange(DiagLink active, String udsHex, long timeoutMs) throws Exception {
        int scanFrom;
        synchronized (responseLock) { scanFrom = pendingTotal; }
        active.sendBus(ZeehoRs485Ops.canUds(udsHex));
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        byte[] ffHead = null;
        int total = 0;
        java.util.List<byte[]> cfs = new java.util.ArrayList<>();
        boolean fcSent = false;
        while (active.isConnected() && android.os.SystemClock.elapsedRealtime() < deadline) {
            synchronized (responseLock) {
                for (int i = pendingIdx(scanFrom); i < pendingResponses.size(); i++) {
                    byte[] d = pendingResponses.get(i).data;
                    int off = canRxOffset(d);
                    if (off < 0 || off >= d.length) continue;
                    int b0 = d[off] & 0xFF;
                    if (ffHead == null && (b0 & 0xF0) == 0x10 && off + 1 < d.length) {
                        total = ((b0 & 0x0F) << 8) | (d[off + 1] & 0xFF);
                        int avail = Math.min(6, d.length - off - 2);
                        if (avail > 0) ffHead = java.util.Arrays.copyOfRange(d, off + 2, off + 2 + avail);
                    } else if (ffHead == null && b0 > 0 && (b0 & 0xF0) == 0x00) {
                        int len = b0 & 0x0F;
                        if (off + 1 + len <= d.length) {
                            byte[] sf = java.util.Arrays.copyOfRange(d, off + 1, off + 1 + len);
                            if (sf.length >= 3 && (sf[0] & 0xFF) == 0x7F && (sf[2] & 0xFF) == 0x78) {
                                deadline = android.os.SystemClock.elapsedRealtime() + 800; // pending 顺延
                                continue;
                            }
                            return sf;
                        }
                    } else if (ffHead != null && b0 >= 0x20 && b0 <= 0x2F && off + 1 < d.length) {
                        int avail = Math.min(7, d.length - off - 1);
                        cfs.add(java.util.Arrays.copyOfRange(d, off + 1, off + 1 + avail));
                    }
                }
                scanFrom = pendingTotal;
            }
            if (ffHead != null && !fcSent) {
                active.sendBus(ZeehoRs485Ops.canFlowControl());
                fcSent = true;
            }
            if (ffHead != null && total > 0) {
                int got = ffHead.length;
                for (byte[] c : cfs) got += c.length;
                if (got >= total) {
                    byte[] outp = new byte[total];
                    System.arraycopy(ffHead, 0, outp, 0, ffHead.length);
                    int pos = ffHead.length;
                    for (byte[] c : cfs) {
                        int n = Math.min(c.length, total - pos);
                        System.arraycopy(c, 0, outp, pos, n);
                        pos += n;
                    }
                    return outp;
                }
            }
            sleepMs(80);
        }
        return null;
    }

    /** NRC 负应答 7F <sid> <code> 转人话;非 7F 返回十六进制。 */
    private static String nrcText(byte[] r) {
        if (r == null) return "无应答";
        if (r.length < 3 || (r[0] & 0xFF) != 0x7F) return Hex.format(r);
        int sid = r[1] & 0xFF, code = r[2] & 0xFF;
        String c;
        switch (code) {
            case 0x22: c = "0x22 条件不满足(F2xx 工厂通道防篡改:里程>49km 拒写;0309 出现则重开 10 03+27 再试)"; break;
            case 0x31: c = "0x31 请求超范围(DID/值非法)"; break;
            case 0x33: c = "0x33 安全访问拒绝"; break;
            case 0x35: c = "0x35 密钥无效"; break;
            case 0x36: c = "0x36 超过尝试次数(错 4 次锁,首次约 10s)"; break;
            case 0x72: c = "0x72 编程失败"; break;
            default: c = String.format("0x%02X", code);
        }
        return String.format("7F %02X %s", sid, c);
    }

    /** 62 <did…> 正应答转 ASCII(从 offset 起);非 62 或空返回 null。 */
    private static String canAscii(byte[] r, int off) {
        if (r == null || r.length <= off || (r[0] & 0xFF) != 0x62) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < r.length; i++) {
            int ch = r[i] & 0xFF;
            if (ch < 0x20 || ch > 0x7E) break;
            sb.append((char) ch);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 62 03 09 + 3B BE(0.1km)→ "x.x";非正应答 null。 */
    private static String canOdo(byte[] r) {
        if (r == null || r.length < 6 || (r[0] & 0xFF) != 0x62) return null;
        int raw = ((r[3] & 0xFF) << 16) | ((r[4] & 0xFF) << 8) | (r[5] & 0xFF);
        return String.format(java.util.Locale.US, "%.1f", raw / 10.0);
    }

    /** CAN 读版本(AE7):配置→10 03→22 F190/F193/F195/0309;官方 CAN 流程无主从切换。 */
    private void beginAe7CanVersion() {
        if (!opGate("CAN读版本")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("CAN 读版本(AE7)")
                .setMessage("CAN 通道(仪表 0x714/0x794,官方滤镜配置)读取 VIN / F193 硬件 / F195 软件 / 0309 当前里程。\n官方 CAN 流程无主从切换帧,仪表不重启。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final DiagLink active = link;
                    showFlowProgress("CAN 读版本", "① CAN 配置(60×4 + 82 01/82 00)…");
                    new Thread(() -> {
                        try {
                            active.sendFfaPair();
                            sleepMs(45);
                            for (byte[] cmd : ZeehoRs485Ops.ae7CanSetupCommands()) {
                                active.send(cmd[1] & 0xFF, java.util.Arrays.copyOfRange(cmd, 2, cmd.length));
                                sleepMs(25);
                            }
                            active.sendBus(Hex.parse("1001"));
                            sleepMs(30);
                            flowStage("② 10 03 扩展会话");
                            byte[] ses = canExchange(active, "1003", 2000);
                            if (ses == null || (ses[0] & 0xFF) != 0x50)
                                throw new java.io.IOException("10 03 " + (ses == null ? "无应答(检查 VCI→车辆 CAN/点火)" : nrcText(ses)));
                            flowStage("③ 读 VIN / F193 / F195 / 里程");
                            byte[] vinR = canExchange(active, "22F190", 3000);
                            byte[] hwR = canExchange(active, "22F193", 2500);
                            byte[] swR = canExchange(active, "22F195", 2500);
                            byte[] odoR = canExchange(active, "220309", 2500);
                            active.sendBus(ZeehoRs485Ops.canUds("1001"));
                            final String v = canAscii(vinR, 3), h = canAscii(hwR, 3), s = canAscii(swR, 3), o = canOdo(odoR);
                            log("OP", "CAN 读版本完成 VIN=" + v + " HW=" + h + " SW=" + s + " 里程=" + o);
                            ui.post(() -> {
                                dismissFlowProgress();
                                if (!isAdded()) { releaseGate(); return; }
                                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("CAN 读版本结果")
                                        .setMessage("VIN " + (v != null ? v : "无应答")
                                                + "\n硬件 F193 " + (h != null ? h : "无应答")
                                                + "\n软件 F195 " + (s != null ? s : "无应答")
                                                + "\n里程 0309 " + (o != null ? o + " km" : "无应答"))
                                        .setPositiveButton("知道了", null).show();
                            });
                        } catch (Exception e) {
                            log("ERR", "CAN 读版本中止: " + e.getMessage());
                            ui.post(() -> {
                                dismissFlowProgress();
                                if (isAdded()) new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("CAN 读版本失败").setMessage(String.valueOf(e.getMessage()))
                                        .setPositiveButton("知道了", null).show();
                            });
                        } finally {
                            releaseGate();
                        }
                    }, "diag-ae7ver").start();
                }).show();
    }

    /** CAN 写里程(AE7):0309 路径(AE7-25款 实车验证;zscan 走 F2xx 工厂通道被 NRC22 防篡改拒)。 */
    private void beginAe7CanMileage() {
        EditText kmEdit = requireView().findViewById(R.id.et_diag_km);
        String raw = kmEdit.getText().toString().trim();
        if (raw.isEmpty()) { toast("输入里程"); return; }
        double km;
        try { km = Double.parseDouble(raw); } catch (NumberFormatException e) { toast("里程格式不对"); return; }
        if (km < 0 || km > 999999) { toast("里程 0–999999 km"); return; }
        final int tenth = (int) Math.round(km * 10);
        final String th = String.format("%06X", tenth);
        if (!opGate("CAN写里程")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("CAN 写里程(AE7,2E 03 09)")
                .setMessage("AE7-25款线束UDS协议(实车验证)路径:\n①CAN 配置 → ②10 03 扩展会话 → ③读 VIN+写前里程 → ④27 01/27 02 安全访问(L1 算法,已对官方三组种子实证) → ⑤2E 03 09 写入(3B 大端,0.1km) → ⑥22 03 09 回读核对 → ⑦回默认会话。\n无需主从切换(官方 CAN 流程无切换帧,仪表不重启)。\n注意:zscan 原生写里程走 F2xx 工厂通道,里程>49km 时被防篡改 NRC22 拒绝——本流程走 0309 不受该限。\n写后请断电重上电回读,数值不回跳才算持久化。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final DiagLink active = link;
                    showFlowProgress("CAN 写里程 " + raw + " km", "① CAN 配置(60×4 + 82 01/82 00)…");
                    new Thread(() -> {
                        try {
                            active.sendFfaPair();
                            sleepMs(45);
                            for (byte[] cmd : ZeehoRs485Ops.ae7CanSetupCommands()) {
                                active.send(cmd[1] & 0xFF, java.util.Arrays.copyOfRange(cmd, 2, cmd.length));
                                sleepMs(25);
                            }
                            active.sendBus(Hex.parse("1001"));
                            sleepMs(30);
                            flowStage("② 10 03 扩展会话");
                            byte[] ses = canExchange(active, "1003", 2000);
                            if (ses == null || (ses[0] & 0xFF) != 0x50)
                                throw new java.io.IOException("10 03 " + (ses == null ? "无应答" : nrcText(ses)));
                            flowStage("③ VIN + 写前里程");
                            byte[] vinR = canExchange(active, "22F190", 3000);
                            final String vin = canAscii(vinR, 3);
                            byte[] beforeR = canExchange(active, "220309", 2500);
                            final String before = canOdo(beforeR);
                            log("OP", "CAN 写里程: VIN=" + vin + " 写前=" + before);
                            flowStage("④ 安全访问 27 01 → 27 02");
                            byte[] seedR = canExchange(active, "2701", 2500);
                            if (seedR == null || (seedR[0] & 0xFF) != 0x67 || seedR.length < 6)
                                throw new java.io.IOException("27 01 " + nrcText(seedR));
                            int seed = ((seedR[2] & 0xFF) << 24) | ((seedR[3] & 0xFF) << 16) | ((seedR[4] & 0xFF) << 8) | (seedR[5] & 0xFF);
                            byte[] seedBytes = java.util.Arrays.copyOfRange(seedR, 2, 6);
                            // 官方顺序:27 01 种子 → L2 fff9 请 VCI 算 key → fff7 回 4B key → fff1 回执 → 27 02;
                            // 本地 L1 算法已对官方 5 组种子-密钥实证一致(11-22 两对再验证),作 fff7 超时回退。
                            String keyHex;
                            if (active.supportsVciKey()) {
                                lastFff7Key = null;
                                active.sendRaw(ZeehoRs485Ops.L2_FFF9_KEY_QUERY);
                                byte[] vkey = awaitFff7Key(3000);
                                if (vkey == null) {
                                    keyHex = String.format("%08X", com.github.speedhunter.diag.Uds.keyLevel1(seed));
                                    log("OP", "CAN seed=" + Hex.format(seedBytes) + " fff7 超时,回退本地 L1 key=" + keyHex);
                                } else {
                                    keyHex = Hex.format(vkey);
                                    active.sendRaw(ZeehoRs485Ops.l2Fff1Ack(seedBytes));
                                    log("OP", "CAN seed=" + Hex.format(seedBytes) + " VCI fff7 key=" + keyHex);
                                }
                            } else {
                                keyHex = String.format("%08X", com.github.speedhunter.diag.Uds.keyLevel1(seed));
                                log("OP", "CAN seed=" + Hex.format(seedBytes) + " 本地 L1 key=" + keyHex);
                            }
                            byte[] keyR = canExchange(active, "2702" + keyHex, 2500);
                            if (keyR == null || keyR.length < 2 || (keyR[0] & 0xFF) != 0x67 || (keyR[1] & 0xFF) != 0x02)
                                throw new java.io.IOException("27 02 " + nrcText(keyR));
                            flowStage("⑤ 2E 03 09 写入 " + raw + " km");
                            byte[] wr = canExchange(active, "2E0309" + th, 3000);
                            if (wr == null) throw new java.io.IOException("2E 03 09 无应答");
                            if ((wr[0] & 0xFF) == 0x7F) throw new java.io.IOException("2E 03 09 " + nrcText(wr));
                            final String ack = Hex.format(wr);
                            flowStage("⑥ 回读核对");
                            byte[] afterR = canExchange(active, "220309", 2500);
                            final String after = canOdo(afterR);
                            active.sendBus(ZeehoRs485Ops.canUds("1001"));
                            boolean ok = after != null && (int) Math.round(Double.parseDouble(after) * 10) == tenth;
                            final String verdict = ok ? "成功" : (after == null ? "已写入,回读无应答" : "已写入,回读不符");
                            log("OP", "CAN 写里程 " + verdict + " ack=" + ack + " 回读=" + after);
                            ui.post(() -> {
                                dismissFlowProgress();
                                if (!isAdded()) { releaseGate(); return; }
                                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("CAN 写里程:" + verdict)
                                        .setMessage("目标 " + raw + " km\nVIN " + (vin != null ? vin : "无应答")
                                                + "\n写前 " + (before != null ? before : "无应答")
                                                + "\n应答 " + ack
                                                + "\n回读 " + (after != null ? after + " km" : "无应答")
                                                + "\n\n持久化验证:断电重上电后再回读,数值不回跳才算成功。")
                                        .setPositiveButton("知道了", null).show();
                            });
                        } catch (Exception e) {
                            log("ERR", "CAN 写里程中止: " + e.getMessage());
                            ui.post(() -> {
                                dismissFlowProgress();
                                if (isAdded()) new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("CAN 写里程失败").setMessage(String.valueOf(e.getMessage()))
                                        .setPositiveButton("知道了", null).show();
                            });
                        } finally {
                            releaseGate();
                        }
                    }, "diag-ae7odo").start();
                }).show();
    }

    // ===== ABS 前轮排气(官方 2026-11-10 00:22 会话逐字节) =====

    private void beginAbsBleed() {
        if (!opGate("ABS排气")) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("ABS 前轮排气(官方流程)")
                .setMessage("按官方流程连接 ABS 模块:双通道配置 → 扩展会话 → 安全访问(key 由 VCI 计算)→ 60 轮泵阀循环(约 66s)→ 清码 + ECU 复位。\n请按官方提示操作刹车油壶/卡钳。")
                .setNegativeButton("取消", (d, w) -> releaseGate())
                .setPositiveButton("开始", (d, w) -> {
                    final DiagLink active = link;
                    new Thread(() -> {
                        try {
                            active.sendFfaPair(); // 阶段开道;心跳保持(官方 ABS 期间 FFA ~1/s 连续)
                            sleepMs(45);
                            for (byte[] cmd : ZeehoRs485Ops.absBleedSetupCommands()) {
                                active.send(cmd[1] & 0xFF, java.util.Arrays.copyOfRange(cmd, 2, cmd.length));
                                sleepMs(25);
                            }
                            log("OP", "ABS 配置完成(60×4 + 82 01/82 00)");
                            ui.post(() -> absStage2Dialog(active));
                        } catch (Exception e) {
                            log("ERR", "ABS 配置失败: " + e.getMessage());
                            releaseGate();
                        }
                    }, "diag-abs1").start();
                }).show();
    }

    private void absStage2Dialog(DiagLink active) {
        if (!isAdded()) { releaseGate(); return; }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("进入诊断会话")
                .setMessage("配置完成。继续进入 ABS 扩展会话(10 03)?")
                .setNegativeButton("中止", (d, w) -> { releaseGate(); })
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
                        releaseGate();
                    }
                }, "diag-abs2").start())
                .setCancelable(false).show();
    }

    private void absStage3Dialog(DiagLink active) {
        if (!isAdded()) { releaseGate(); return; }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("开始排气动作")
                .setMessage("会话已请求。继续将执行安全访问(27 01/27 02)并开始 60 轮泵阀循环。\n循环期间勿断开蓝牙。")
                .setNegativeButton("中止", (d, w) -> { releaseGate(); })
                .setPositiveButton("开始排气", (d, w) -> new Thread(() -> absBleedRun(active), "diag-abs3").start())
                .setCancelable(false).show();
    }

    private void absBleedRun(DiagLink active) {
        try {
            active.sendBus(ZeehoRs485Ops.absSeedRequest());
            byte[] seed = awaitAbsSeed(active, 3000);
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
                if (!isAdded()) { releaseGate(); return; }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("排气完成")
                        .setMessage("循环与清码已发送。按官方流程最后执行 ABS ECU 复位(11 01)?")
                        .setNegativeButton("跳过", (d, w) -> { releaseGate(); })
                        .setPositiveButton("复位", (d, w) -> new Thread(() -> {
                            try {
                                active.sendBus(ZeehoRs485Ops.absEcuReset());
                                log("TX", "ABS ECU 复位(11 01)");
                            } catch (Exception e) {
                                log("ERR", "ECU 复位失败: " + e.getMessage());
                            } finally {
                                releaseGate();
                            }
                        }, "diag-abs-reset").start())
                        .setCancelable(false).show();
            });
        } catch (Exception e) {
            log("ERR", "ABS 排气中止: " + e.getMessage());
            releaseGate();
        }
    }

    /** 被动等待 ABS 27 01 种子(数据帧含 07E9 08 06 67 01 <seed4B>;VCI 主动推送,等待期不轮询)。 */
    private byte[] awaitAbsSeed(DiagLink active, long timeoutMs) {
        int mark;
        synchronized (responseLock) { mark = pendingTotal; }
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (active.isConnected()) {
            byte[] seed = scanAbsSeedSince(mark);
            if (seed != null) return seed;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return null;
            sleepMs(120);
        }
        return null;
    }

    private byte[] scanAbsSeedSince(int mark) {
        synchronized (responseLock) {
            for (int i = pendingIdx(mark); i < pendingResponses.size(); i++) {
                byte[] d = pendingResponses.get(i).data;
                for (int j = 0; j + 9 <= d.length; j++) {
                    if ((d[j] & 0xFF) == 0x07 && (d[j + 1] & 0xFF) == 0xE9 && (d[j + 2] & 0xFF) == 0x08
                            && (d[j + 3] & 0xFF) == 0x06 && (d[j + 4] & 0xFF) == 0x67 && (d[j + 5] & 0xFF) == 0x01) {
                        return java.util.Arrays.copyOfRange(d, j + 6, j + 10);
                    }
                }
            }
        }
        return null;
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

    private void sendCfTo(DiagLink active, String title, byte[] cf) {
        log("TX", title + " CF " + Hex.format(cf).replace(" ", ""));
        try {
            active.sendRs485(cf);
        } catch (Exception e) {
            log("ERR", "send: " + e.getMessage());
        }
    }

    /** 官方节奏:每帧 CF 写前先发 69 0101 轮询。 */
    private void pollAndSend(DiagLink active, String title, byte[] cf) {
        try {
            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
        } catch (Exception e) {
            log("ERR", "poll: " + e.getMessage());
        }
        sendCfTo(active, title, cf);
    }

    /** 官方请求-应答:poll+CF 写入,然后被动等节点 0xFC 指定应答码
     *  (黄金抓取:FC 81/82/BA 均由 VCI 主动推送,写入后 30-60ms 到达,等待期零 TX)。超时抛异常。 */
    private void requestResponse(DiagLink active, byte[] cf, int response) throws Exception {
        int mark;
        int seq;
        synchronized (responseLock) {
            mark = pendingTotal;
            active.send(VciLink.CMD_POLL, new byte[]{1, 1});
            seq = active.sendWithSeq(VciLink.CMD_RS485_WRITE, ZeehoRs485Ops.wrapRs485ForVci(cf));
        }
        log("TX", "CF(seq=" + seq + ") " + Hex.format(cf).replace(" ", ""));
        long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
        while (active.isConnected()) {
            boolean hit = false;
            synchronized (responseLock) {
                for (int i = pendingIdx(mark); i < pendingResponses.size(); i++) {
                    if (DeleteCardResponses.matchesAnySeq(pendingResponses.get(i), response)) { hit = true; break; }
                }
            }
            if (hit) return;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) break;
            sleepMs(120); // 被动等待:FC 应答由 VCI 主动推送(黄金抓取 30-60ms),不轮询
        }
        throw new java.io.IOException("等待 FC " + Integer.toHexString(response) + " 超时或连接断开");
    }

    /** DID 0309 读里程(22 03 09 → 节点 0x94 应答 62 03 09 + 3B 大端 0.1km;150ms 轮询拉取)。超时返回 null。 */
    private String readOdoViaDid(DiagLink active) {
        int mark;
        synchronized (responseLock) {
            mark = pendingTotal;
            try {
                active.send(VciLink.CMD_POLL, new byte[]{1, 1});
                active.sendRs485(Rs485.encode(0x14, new byte[]{0x22, 0x03, 0x09}));
            } catch (Exception e) {
                log("ERR", "读里程发送失败: " + e.getMessage());
                return null;
            }
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 2500;
        while (active.isConnected()) {
            String r = scanOdoSince(mark);
            if (r != null) return r;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return null;
            sleepMs(120); // 被动等待:0x94 应答由 VCI 主动推送
        }
        return null;
    }

    private String scanOdoSince(int mark) {
        synchronized (responseLock) {
            for (int i = pendingIdx(mark); i < pendingResponses.size(); i++) {
                VciLink.DataFrame f = pendingResponses.get(i);
                if (!f.fromVehicle) continue;
                for (byte[] raw : VciLink.extractCfFrames(f)) {
                    try {
                        Rs485.Frame fr = Rs485.decode(raw);
                        // 应答节点 = 查询节点|0x80(0x14→0x94,z_1115 会话实证)
                        if (fr.node == 0x94 && fr.payload.length >= 6
                                && (fr.payload[0] & 0xFF) == 0x62
                                && (fr.payload[1] & 0xFF) == 0x03 && (fr.payload[2] & 0xFF) == 0x09) {
                            int v = ((fr.payload[3] & 0xFF) << 16) | ((fr.payload[4] & 0xFF) << 8) | (fr.payload[5] & 0xFF);
                            return String.format(java.util.Locale.US, "%.1f", v / 10.0) + " km";
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
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

    /** 导出完整日志为 .log 文件(API≥29 入公共 Download/SpeedHunter;旧版回退 app 外部目录),并弹分享。 */
    private void exportLog() {
        android.net.Uri uri = logger.exportLogFile(requireContext());
        if (uri == null) {
            toast("导出失败(日志为空或存储不可用)");
            log("LOG", "导出失败");
            return;
        }
        String shown = uri.getPath() != null ? uri.getPath() : uri.toString();
        log("LOG", "已导出 .log: " + shown);
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            send.putExtra(android.content.Intent.EXTRA_TEXT, logger.dump());
        }
        startActivity(android.content.Intent.createChooser(send, "诊断日志 .log"));
        toast("日志已导出:\n" + shown);
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

    /** 流程进度框(非模态、不可取消):indeterminate 进度条 + 分阶段文案 + 已用秒数。 */
    private void showFlowProgress(final String title, final String firstStage) {
        ui.post(() -> {
            if (!isAdded()) return;
            dismissFlowProgress();
            android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad / 2, pad, 0);
            android.widget.ProgressBar bar = new android.widget.ProgressBar(requireContext());
            bar.setIndeterminate(true);
            box.addView(bar);
            flowStageView = new android.widget.TextView(requireContext());
            flowStageView.setText(firstStage);
            flowStageView.setPadding(0, pad / 2, 0, 0);
            box.addView(flowStageView);
            flowStartMs = android.os.SystemClock.elapsedRealtime();
            flowProgress = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(title).setView(box).setCancelable(false).show();
        });
    }

    /** 更新阶段文案(带已用秒);后台线程可直接调用,无进度框时为 no-op。 */
    private void flowStage(final String text) {
        final android.widget.TextView v = flowStageView;
        if (v == null) return;
        ui.post(() -> {
            if (v != flowStageView || !isAdded()) return;
            v.setText(text + "  · 已用 " + ((android.os.SystemClock.elapsedRealtime() - flowStartMs) / 1000) + "s");
        });
    }

    private void dismissFlowProgress() {
        final android.app.Dialog d = flowProgress;
        flowProgress = null;
        flowStageView = null;
        if (d != null) ui.post(() -> { try { d.dismiss(); } catch (Throwable ignored) { } });
    }

    /** 自 t0(毫秒单调钟)以来经过的秒数,用于流程各阶段耗时打点。 */
    private static String secSince(long t0) {
        return String.format(java.util.Locale.US, "(+%.1fs)",
                (android.os.SystemClock.elapsedRealtime() - t0) / 1000.0);
    }

    /** 诊断结果区追加一条结果(线程安全,自动滚底,8KB 截断)——对齐官方 zscan 诊断界面的结果展示。 */
    private void showResult(String title, String body) {
        ui.post(() -> {
            if (!isAdded() || resultView == null) return;
            String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            String entry = "【" + title + "】" + stamp + "\n" + body + "\n----\n";
            String cur = resultView.getText().toString();
            if (cur.startsWith("暂无结果")) cur = "";
            String next = cur + entry;
            if (next.length() > 8000) next = next.substring(next.length() - 8000);
            resultView.setText(next);
            final android.widget.ScrollView sv = resultScroll;
            if (sv != null) sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN));
        });
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
        if (flowBusy || connecting) {
            // 4.8.5:保持连接不再是开关——操作/连接进行中自动保持,结束后恢复"离开即释放"
            log("LINK", reason + ",操作进行中,保持连接(操作结束后离开页面即释放)");
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
