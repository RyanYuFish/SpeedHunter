package com.github.zeehospeedhunter.ui;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.diag.DataStreams;
import com.github.zeehospeedhunter.diag.DiagClient;
import com.github.zeehospeedhunter.diag.DiagOptions;
import com.github.zeehospeedhunter.diag.DiagProtocol;
import com.github.zeehospeedhunter.diag.Dtc;
import com.github.zeehospeedhunter.diag.Ecus;
import com.github.zeehospeedhunter.diag.Routines;
import com.github.zeehospeedhunter.diag.Rs485;
import com.github.zeehospeedhunter.diag.VehicleRoutines;
import com.github.zeehospeedhunter.diag.VehicleService;
import com.github.zeehospeedhunter.ota.CarNet;

/**
 * 车端诊断操作台 —— 把 zeeho-diag（Electron 桌面端）的诊断能力搬到手机。
 *
 * <h3>能力（对应 zeeho-diag lib/）</h3>
 * <ul>
 *   <li>DoIP/UDS：{@link DiagClient} + {@link DiagProtocol}（TCP 13400）</li>
 *   <li>车辆信息 / 故障码 / ECU 身份 / DID 扫描 / 数据流：{@link VehicleService} + {@link DataStreams}</li>
 *   <li>故障码解码：{@link Dtc}（19 02 响应 → SAE J2012 码表，中文可读）</li>
 *   <li>写类维修操作（默认硬禁 + allowWrite 闸门）：{@link Routines}</li>
 *   <li>RS485 私协议离线解码：{@link Rs485}</li>
 *   <li>ECU 全景表（已知地址优先、未知地址回退 CAN 并标注 ⚠）：{@link Ecus}</li>
 * </ul>
 *
 * <h3>UI 布局</h3>
 * <p>参照第三方 zeeho-diag 的信息层次重排：顶栏三态<b>状态胶囊</b> → <b>一键连接</b>主操作 →
 * <b>2 列功能宫格</b>（图标+标题+副标题，按使用场景而非协议分层组织）→ 默认折叠的
 * 「高级设置」与 RS485 → 写操作红卡（allowWrite 硬禁）→ 日志区。
 * 连接条 {@code res/layout/view_carnet.xml} 与 {@link CarNetController} 为三页共用。</p>
 */
public final class DiagActivity extends AppCompatActivity {

    private static final int LOG_MAX_LINES = 400;

    private TextView tvLog;
    private TextView tvStatus;
    private TextView tvBadge;
    private TextView tvRs485Out;
    private EditText etHost, etPort, etScanStart, etScanEnd, etRs485, etRoutineId, etRoutineSub;
    private Spinner spEcu, spGroup;
    private CheckBox cbAllowWrite;

    private SharedPreferences prefs;
    private Handler main;
    private final StringBuilder log = new StringBuilder();

    private DiagClient client;
    private String clientHost;
    private int clientPort = -1;
    private CarNetController carNet;
    /** Wi-Fi Direct 连接面板（扫仪表 / 建组 / 断开），与「连车机热点」互为兜底。 */
    private P2pPanel p2pPanel;
    private String[] ecuKeys;
    private String[] groupKeys;

    /** 车辆链路（BLE 透传 RS485 / CAN / Wi-Fi DoIP 三态，移植 zeeho-diag iOS 版 linkMode）。 */
    private VehicleLink vlink;
    private TextView tvLinkState;
    private TextView tvLinkModeDesc;
    private TextView tvAbsStatus;
    private TextView tvNfcTip;
    private View rowCan;
    private View rowRs485Send;
    private com.google.android.material.button.MaterialButton btnAbsStart;
    private com.google.android.material.button.MaterialButton btnAbsStop;
    /** ABS 排气时序的当前线程，用于中止。 */
    private Thread absThread;
    /** RS485 系统码表（顺序与 spinner 一致）。 */
    private static final String[] RS485_SYS = {"49", "51", "59", "81", "89", "8B"};
    /** Android 12+ BLE 运行时权限申请器。 */
    private androidx.activity.result.ActivityResultLauncher<String[]> reqBlePerm;

    /** 连接状态三态，驱动顶栏胶囊。 */
    private enum Link { OFF, PROBING, READY }

    private Link link = Link.OFF;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        EdgeToEdge.applyWindow(this);
        EdgeToEdge.applyTop(findViewById(R.id.root_diag));
        NavBar.install(this, R.id.nav_diag, R.id.diag_scroll);
        BackGestureCompat.install(this);
        main = new Handler(Looper.getMainLooper());

        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_diag_status);
        tvBadge = findViewById(R.id.tv_link_badge);
        tvRs485Out = findViewById(R.id.tv_rs485_out);
        etHost = findViewById(R.id.et_host);
        etPort = findViewById(R.id.et_port);
        etScanStart = findViewById(R.id.et_scan_start);
        etScanEnd = findViewById(R.id.et_scan_end);
        etRs485 = findViewById(R.id.et_rs485);
        etRoutineId = findViewById(R.id.et_routine_id);
        etRoutineSub = findViewById(R.id.et_routine_sub);
        spEcu = findViewById(R.id.sp_ecu);
        spGroup = findViewById(R.id.sp_group);
        cbAllowWrite = findViewById(R.id.cb_allow_write);

        prefs = getSharedPreferences("diag", MODE_PRIVATE);
        etHost.setText(prefs.getString("host", DiagOptions.DOIP_HOST));
        etPort.setText(String.valueOf(prefs.getInt("port", DiagOptions.DOIP_PORT)));
        etScanStart.setText(DiagOptions.SCAN_START);
        etScanEnd.setText(DiagOptions.SCAN_END);
        etRoutineSub.setText("01");

        setupLogScrolling(tvLog);

        // ECU / 数据流组 Spinner
        ecuKeys = Ecus.spinnerKeys();
        spEcu.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Ecus.spinnerLabels()));
        groupKeys = DataStreams.groupKeys();
        spGroup.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, DataStreams.groupTitles()));

        findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());

        setupCollapsibles();
        setupTiles();
        setupVehicleLink();

        // 共享连接条（系统 Wi-Fi 直连 / App 内直连 / 网络诊断）
        carNet = new CarNetController(this, findViewById(android.R.id.content), new CarNetController.Callback() {
            @Override
            public void onDiagnosed(CarNet.DiagReport report, String groupOwner) {
                if (groupOwner != null && !groupOwner.isEmpty()) {
                    // 探测到的组主地址回填 DoIP 目标（组主可能是 0.1 而非硬编码 49.1）
                    etHost.setText(groupOwner);
                    prefs.edit().putString("host", groupOwner).apply();
                    append("网络诊断回填 DoIP 主机 = " + groupOwner);
                    setLink(Link.READY, "已就绪 · " + report.mode);
                } else {
                    append("网络诊断未探到组主地址，DoIP 主机保持手动值 " + host());
                    setLink(Link.OFF, "未探到车机");
                }
                tvStatus.setText(report.hasCar()
                        ? ("组主 " + report.groupOwner + " · " + report.mode)
                        : ("未探到组主 · " + report.mode));
            }

            @Override
            public void onStage(String s) {
                append(s);
            }
        }, prefs);

        // 一键连接：自动探测 3 秒（对标参考图「一键连接（自动搜索 3 秒）」）
        findViewById(R.id.btn_one_tap_connect).setOnClickListener(v -> oneTapConnect());
        findViewById(R.id.btn_disconnect).setOnClickListener(v -> doDisconnect());

        // ★ Wi-Fi Direct 通道（模块内已有）：扫仪表 → 建组 → 组建成后自动探测并回填 DoIP 主机
        p2pPanel = new P2pPanel(this, findViewById(R.id.p2p_peer_list), new P2pPanel.Callback() {
            @Override
            public void onLog(String s) {
                append(s);
            }

            @Override
            public void onStatus(String s) {
                tvStatus.setText(s);
            }

            @Override
            public void onGroup(String go) {
                if (go == null || go.isEmpty() || "?".equals(go)) return;
                // 组主可能不是标准的 49.1，以探测到的为准（不能写死）
                etHost.setText(go);
                prefs.edit().putString("host", go).apply();
                append("P2P 组主 " + go + " 已回填 DoIP 主机，接着做端口探测确认…");
                // 端口通 ≠ 路由激活能过，所以只把状态置为探测中，由诊断结果决定最终态
                setLink(Link.PROBING, "探测中…");
                carNet.runDiagnose();
            }
        });
        findViewById(R.id.btn_p2p_scan).setOnClickListener(v -> p2pPanel.scan());
        findViewById(R.id.btn_p2p_disconnect).setOnClickListener(v -> {
            if (p2pPanel == null) return;
            p2pPanel.disconnect();
            setLink(Link.OFF, "未连接");
            tvStatus.setText("已请求断开 P2P。");
        });

        // 写操作：allowWrite 闸门 + 二次确认
        findViewById(R.id.btn_clear_dtc).setOnClickListener(v -> confirmWrite("清除故障码", this::doClearDtc));
        findViewById(R.id.btn_ecu_reset).setOnClickListener(v -> confirmWrite("ECU 复位", this::doEcuReset));
        findViewById(R.id.btn_run_routine).setOnClickListener(v -> confirmWrite("例程控制(0x31)", this::doRunRoutine));
        findViewById(R.id.btn_rs485_decode).setOnClickListener(v -> doRs485Decode());

        setLink(Link.OFF, "未连接");
        append("诊断台就绪。先点「一键连接」或连上车机热点，再点功能宫格里的只读项。写操作需勾选 allowWrite。");
    }

    // ==================== 车辆链路（BLE / CAN / Wi-Fi） ====================

    /**
     * 绑定「车辆链路」卡：模式切换 + 一键连接 + CAN/RS485 专属区。
     *
     * <p>移植自 zeeho-diag iOS 版 {@code diag.html} 的 {@code linkMode} 三态。
     * RS485 与 CAN <b>共用同一条 BLE 连接</b>，切换不断连；WIFI 才换链路。</p>
     */
    private void setupVehicleLink() {
        tvLinkState = findViewById(R.id.tv_link_state);
        tvLinkModeDesc = findViewById(R.id.tv_link_mode_desc);
        tvAbsStatus = findViewById(R.id.tv_abs_status);
        tvNfcTip = findViewById(R.id.tv_nfc_tip);
        rowCan = findViewById(R.id.row_can);
        rowRs485Send = findViewById(R.id.row_rs485_send);
        btnAbsStart = findViewById(R.id.btn_abs_start);
        btnAbsStop = findViewById(R.id.btn_abs_stop);

        vlink = new VehicleLink(this, new VehicleLink.Callback() {
            @Override
            public void onLinkState(String stateText, boolean ready) {
                tvLinkState.setText(stateText);
                setLink(ready ? Link.READY : Link.OFF, ready ? "已就绪" : stateText);
            }

            @Override
            public void onLinkLog(String line) {
                append(line);
            }

            @Override
            public void onLinkData(String hex) {
                // 上行原始帧也进日志区，便于复现「发了没回」类问题
                append("↑ " + hex);
            }
        }, prefs);

        // Android 12+ BLE 需要运行时「附近设备」权限；授权后才允许一键连接
        reqBlePerm = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
                granted -> {
                    boolean all = true;
                    for (Boolean ok : granted.values()) all &= (ok != null && ok);
                    if (all) {
                        vlink.autoConnect();
                    } else {
                        append("⚠ 未授予附近设备权限，BLE 链路不可用。"
                                + "可改用 WIFI 模式走车机 DoIP");
                        Toast.makeText(this, "缺「附近设备」权限，已切不回 BLE", Toast.LENGTH_LONG).show();
                    }
                });

        // 模式切换
        com.google.android.material.button.MaterialButtonToggleGroup tg =
                findViewById(R.id.tg_link_mode);
        tg.check(R.id.btn_mode_rs485);
        tg.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            VehicleLink.Mode m;
            if (checkedId == R.id.btn_mode_can) m = VehicleLink.Mode.CAN;
            else if (checkedId == R.id.btn_mode_wifi) m = VehicleLink.Mode.WIFI;
            else m = VehicleLink.Mode.RS485;
            applyMode(m);
        });
        applyMode(VehicleLink.Mode.RS485);

        // 一键连接 / 扫描（先过权限闸门）
        findViewById(R.id.btn_link_connect).setOnClickListener(v -> startBleConnect());
        findViewById(R.id.btn_link_scan).setOnClickListener(v -> {
            if (!ensureBlePerm()) return;
            vlink.ble().startScan(null);
            append("BLE 扫描中…（手动连：先扫到设备再点「一键连接」按最强信号选）");
        });

        // ---- CAN 专属 ----
        findViewById(R.id.btn_can_send).setOnClickListener(v -> {
            String uds = etCanUds().getText().toString().trim();
            if (uds.isEmpty()) {
                Toast.makeText(this, "先填 UDS（如 220802）", Toast.LENGTH_SHORT).show();
                return;
            }
            vlink.sendCan(canId(), uds);
        });

        // 一键 ABS 排气：泵/阀会真动，必须走写操作闸门 + 二次确认
        btnAbsStart.setOnClickListener(v -> {
            if (!cbAllowWrite.isChecked()) {
                append("⛔ 安全拦截：一键 ABS 排气需先勾选「允许写操作 (allowWrite)」。"
                        + "该操作会驱动 ABS 泵与阀真实动作。");
                Toast.makeText(this, "请先勾选 allowWrite", Toast.LENGTH_LONG).show();
                return;
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("开始一键 ABS 排气？")
                    .setMessage("ABS 泵会真实转动 10 秒，请务必盯住放气螺丝并按车型规范操作。\n"
                            + "确认车辆已举升/安全停放、已排除轮胎与轮毂干扰。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认开始", (d, w) -> startAbsBleed())
                    .show();
        });
        btnAbsStop.setOnClickListener(v -> {
            if (absThread != null) {
                absThread.interrupt();
                absThread = null;
                tvAbsStatus.setText("已请求停止…");
                btnAbsStart.setEnabled(true);
                btnAbsStop.setEnabled(false);
            }
        });

        // 胎压匹配（写）
        findViewById(R.id.btn_tpms_read).setOnClickListener(v ->
                confirmWriteOp("读胎压传感器 ID", () ->
                        vlink.sendCan(VehicleRoutines.TPMS_CAN_ID, VehicleRoutines.tpmsReadId().udsHex)));
        findViewById(R.id.btn_tpms_front).setOnClickListener(v ->
                confirmWriteOp("前轮匹配", () -> {
                    vlink.sendCan(VehicleRoutines.TPMS_CAN_ID, VehicleRoutines.tpmsFrontStart().udsHex);
                    main.postDelayed(() -> vlink.sendCan(VehicleRoutines.TPMS_CAN_ID,
                            VehicleRoutines.tpmsFrontQuery().udsHex), 3000);
                }));
        findViewById(R.id.btn_tpms_rear).setOnClickListener(v ->
                confirmWriteOp("后轮匹配", () -> {
                    vlink.sendCan(VehicleRoutines.TPMS_CAN_ID, VehicleRoutines.tpmsRearStart().udsHex);
                    main.postDelayed(() -> vlink.sendCan(VehicleRoutines.TPMS_CAN_ID,
                            VehicleRoutines.tpmsRearQuery().udsHex), 3000);
                }));
        findViewById(R.id.btn_tpms_stop).setOnClickListener(v -> {
            vlink.sendCan(VehicleRoutines.TPMS_CAN_ID, VehicleRoutines.tpmsFrontStop().udsHex);
            main.postDelayed(() -> vlink.sendCan(VehicleRoutines.TPMS_CAN_ID,
                    VehicleRoutines.tpmsRearStop().udsHex), 150);
        });

        // ---- RS485 专属 ----
        Spinner spSys = findViewById(R.id.sp_rs485_sys);
        String[] sysLabels = new String[RS485_SYS.length];
        for (int i = 0; i < RS485_SYS.length; i++) {
            sysLabels[i] = RS485_SYS[i] + " · " + VehicleLink.describeSys(RS485_SYS[i]);
        }
        spSys.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sysLabels));
        findViewById(R.id.btn_rs485_send).setOnClickListener(v -> {
            int p = spSys.getSelectedItemPosition();
            String sys = (p >= 0 && p < RS485_SYS.length) ? RS485_SYS[p] : "89";
            vlink.sendRs485Frame(sys, etRs485Payload().getText().toString());
        });
        findViewById(R.id.btn_nfc_init).setOnClickListener(v ->
                confirmWriteOp("NFC 初始化", this::sendNfcInit));
        findViewById(R.id.btn_rs485_tpms_init).setOnClickListener(v ->
                confirmWriteOp("胎压匹配初始化", () -> {
                    sendRs485Seq(VehicleRoutines.rs485TpmsInitMsgs(), "胎压匹配-初始化");
                    main.postDelayed(() -> {
                        vlink.sendRaw(VehicleRoutines.rs485TpmsFrame());
                        append("TX 胎压匹配帧（4346 17 02 3808…）已发送");
                    }, 1500);
                }));
        tvNfcTip.setText(VehicleRoutines.nfcTip());
    }

    /** 切换链路模式：CAN 显专属区、RS485 显专属区、WIFI 交给 DoIP 卡片。 */
    private void applyMode(VehicleLink.Mode m) {
        vlink.setMode(m);
        rowCan.setVisibility(m == VehicleLink.Mode.CAN ? View.VISIBLE : View.GONE);
        rowRs485Send.setVisibility(m == VehicleLink.Mode.RS485 ? View.VISIBLE : View.GONE);
        tvLinkModeDesc.setText(m.desc);
        com.google.android.material.button.MaterialButton btnConnect =
                findViewById(R.id.btn_link_connect);
        btnConnect.setText(m == VehicleLink.Mode.WIFI ? "一键连接（DoIP）" : "一键连接（BLE）");
        // DoIP 那条路走「连接设备」卡的 oneTapConnect，BLE 那条走透传模块自动搜索
        btnConnect.setOnClickListener(m == VehicleLink.Mode.WIFI
                ? v -> oneTapConnect()
                : v -> startBleConnect());
    }

    /** BLE 一键连接入口：缺权限先申请（Android 12+）。 */
    private void startBleConnect() {
        if (ensureBlePerm()) vlink.autoConnect();
    }

    /**
     * 检查 BLE 权限；缺则发起申请并返回 false。
     *
     * <p>Android 12+ 的 BLUETOOTH_SCAN/CONNECT 是运行时权限，缺它们时
     * {@code startScan} 直接抛 SecurityException，现象与「模块没上电」完全一样。</p>
     */
    private boolean ensureBlePerm() {
        if (com.github.zeehospeedhunter.diag.BleRs485.hasPermission(this)) return true;
        reqBlePerm.launch(com.github.zeehospeedhunter.diag.BleRs485.requiredPermissions());
        return false;
    }

    private int canId() {
        String s = canIdText().getText().toString().trim();
        try {
            String clean = s.replaceAll("[^0-9A-Fa-f]", "");
            return clean.isEmpty() ? VehicleRoutines.TPMS_CAN_ID : Integer.parseInt(clean, 16);
        } catch (Exception e) {
            return VehicleRoutines.TPMS_CAN_ID;
        }
    }

    private EditText etCanUds() {
        return findViewById(R.id.et_can_uds);
    }

    private EditText canIdText() {
        return findViewById(R.id.et_can_id);
    }

    private EditText etRs485Payload() {
        return findViewById(R.id.et_rs485_payload);
    }

    /** 跑一键 ABS 排气（泵/阀真实动作）。 */
    private void startAbsBleed() {
        if (!vlink.ble().isReady()) {
            Toast.makeText(this, "先连上透传模块（CAN 模式）", Toast.LENGTH_SHORT).show();
            return;
        }
        btnAbsStart.setEnabled(false);
        btnAbsStop.setEnabled(true);
        VehicleRoutines.SeqPlan plan = VehicleRoutines.absBleedPlan(canId());
        // 抽一个可中止的运行：用线程 + interrupt
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < plan.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        main.post(() -> {
                            tvAbsStatus.setText("已停止（泵/阀保持当前状态）");
                            btnAbsStart.setEnabled(true);
                            btnAbsStop.setEnabled(false);
                        });
                        return;
                    }
                    VehicleRoutines.SeqStep st = plan.steps.get(i);
                    final String txt = "第 " + (i + 1) + "/" + plan.size() + " 步：" + st.label
                            + (st.isManualHold ? "（泵运行中 10 秒，请配合放气）" : "");
                    main.post(() -> tvAbsStatus.setText(txt));
                    vlink.sendCan(st.canId, st.udsHex);
                    Thread.sleep(st.waitMs);
                }
                main.post(() -> {
                    tvAbsStatus.setText("✅ 排气完成：关闭放气螺丝 → 补足刹车油 → 低速路试刹车");
                    btnAbsStart.setEnabled(true);
                    btnAbsStop.setEnabled(false);
                });
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                main.post(() -> tvAbsStatus.setText("✖ 排气失败：" + e.getMessage()));
            }
        }, "abs-bleed");
        absThread = t;
        t.start();
    }

    private void sendNfcInit() {
        sendRs485Seq(VehicleRoutines.rs485InitMsgs(), "NFC 初始化");
    }

    /** 依次发一串预置 RS485 帧（间隔 450ms，与 iOS 版 speSendFrames 一致）。 */
    private void sendRs485Seq(java.util.List<String> frames, String label) {
        for (int i = 0; i < frames.size(); i++) {
            final String f = frames.get(i);
            main.postDelayed(() -> vlink.sendRaw(f), i * 450L);
        }
        append("已发送特殊序列：" + label + "（" + frames.size() + " 帧）");
    }

    /** 写操作二次确认（allowWrite 已在外层闸门，这里补一次确认）。 */
    private void confirmWriteOp(String action, Runnable op) {
        if (!cbAllowWrite.isChecked()) {
            append("⛔ 安全拦截：" + action + " 需先勾选 allowWrite");
            Toast.makeText(this, "请先勾选 allowWrite", Toast.LENGTH_LONG).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("确认" + action + "？")
                .setMessage("该操作会直接改变车辆状态。确认车辆已安全停放、已上电、不在充电、非 Ready。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定执行", (d, w) -> op.run())
                .show();
    }

    // ==================== 折叠区 ====================

    /** 高级设置 / RS485 默认折叠，避免首屏被工程参数淹没。 */
    private void setupCollapsibles() {
        bindCollapse(R.id.btn_toggle_advanced, R.id.row_advanced, "展开", "收起");
        bindCollapse(R.id.btn_rs485_toggle, R.id.row_rs485, "展开", "收起");
    }

    private void bindCollapse(int btnId, int rowId, String textClosed, String textOpen) {
        View btn = findViewById(btnId);
        final View row = findViewById(rowId);
        btn.setOnClickListener(v -> {
            boolean vis = row.getVisibility() == View.VISIBLE;
            row.setVisibility(vis ? View.GONE : View.VISIBLE);
            ((TextView) btn).setText(vis ? textClosed : textOpen);
        });
    }

    // ==================== 功能宫格 ====================

    /**
     * 绑定 2 列功能宫格。每格：图标 + 标题 + 副标题。
     * 分组依据「用户想做什么」而非「走哪条协议」——与参考图的场景化组织一致。
     */
    private void setupTiles() {
        bindTile(R.id.tile_vehicle_info, "🚗", "车辆信息", "VIN / 版本 / 里程", () -> run(this::doReadInfo, "读车辆信息"));
        bindTile(R.id.tile_fault_code, "⚠️", "读故障码", "DTC 列表 + 中文解码", () -> run(this::doReadDtc, "读故障码"));
        bindTile(R.id.tile_ecu_info, "🔧", "ECU 身份", "零件号 / 版本 / 供应商", () -> run(this::doEcuInfo, "ECU 身份"));
        bindTile(R.id.tile_data_stream, "📈", "看数据流", "电压 / 电流 / 温度 / SOC", () -> run(this::doReadStreams, "读数据流"));
        bindTile(R.id.tile_scan_did, "🔍", "扫 DID", "枚举支持的 DID", () -> run(this::doScanDids, "DID 扫描"));
        bindTile(R.id.tile_rs485, "🧩", "RS485 解码", "抓包离线解析", this::expandRs485);
    }

    private void bindTile(int tileId, String icon, String title, String sub, Runnable action) {
        View tile = findViewById(tileId);
        if (tile == null) return;
        ((TextView) tile.findViewById(R.id.diag_tile_icon)).setText(icon);
        ((TextView) tile.findViewById(R.id.diag_tile_title)).setText(title);
        ((TextView) tile.findViewById(R.id.diag_tile_sub)).setText(sub);
        tile.setOnClickListener(v -> action.run());
    }

    /** 点 RS485 宫格 → 自动展开折叠区并聚焦输入框。 */
    private void expandRs485() {
        View row = findViewById(R.id.row_rs485);
        if (row.getVisibility() != View.VISIBLE) {
            row.setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.btn_rs485_toggle)).setText("收起");
        }
        etRs485.requestFocus();
        Toast.makeText(this, "粘贴 RS485 抓包后点「解码」", Toast.LENGTH_SHORT).show();
    }

    // ==================== 连接状态 ====================

    /** 更新顶栏状态胶囊：填充色 + 描边色随三态变化。 */
    private void setLink(Link state, String label) {
        link = state;
        if (tvBadge == null) return;
        tvBadge.setText(label);
        int stroke, text;
        switch (state) {
            case READY:
                stroke = Color.parseColor("#2E7D32");   // 深绿
                text = Color.parseColor("#2E7D32");
                break;
            case PROBING:
                stroke = Color.parseColor("#EF6C00");   // 橙
                text = Color.parseColor("#EF6C00");
                break;
            default:
                stroke = resolveAttr(android.R.attr.textColorSecondary, Color.GRAY);
                text = resolveAttr(android.R.attr.textColorSecondary, Color.GRAY);
                break;
        }
        android.graphics.drawable.Drawable bg = tvBadge.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            android.graphics.drawable.GradientDrawable g = (android.graphics.drawable.GradientDrawable) bg.mutate();
            g.setStroke(1, stroke);
            g.setColor(withAlpha(text, 0.14f));   // 淡填充
            tvBadge.setBackground(g);
        }
        tvBadge.setTextColor(text);
    }

    private int resolveAttr(int attr, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return fallback;
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * 一键连接：网络诊断（探测组主与端口）→ 回填 DoIP 目标 → DoIP 通道自检。
     *
     * <p>刻意做了两段而不是「端口通 = 成功」：TCP 13400 开着不代表路由激活能过，
     * 只有 {@link DiagClient#verifyChannel()} 拿到 03FC0006 才把徽章亮绿。</p>
     */
    private void oneTapConnect() {
        setLink(Link.PROBING, "探测中…");
        append("一键连接：开始网络诊断（TCP 握手，不发业务数据）");
        carNet.runDiagnose();
    }

    /** 断开：关掉 DoIP 客户端，徽章复位。 */
    private void doDisconnect() {
        if (client != null) {
            client.close();
            client = null;
            clientHost = null;
            clientPort = -1;
            append("已断开 DoIP 连接");
        } else {
            append("当前没有已建立的 DoIP 连接");
        }
        setLink(Link.OFF, "未连接");
        tvStatus.setText("已断开。点「一键连接」重新探测。");
    }

    // ==================== 选择项 ====================

    private String selectedEcuKey() {
        int p = spEcu.getSelectedItemPosition();
        return (ecuKeys != null && p >= 0 && p < ecuKeys.length) ? ecuKeys[p] : "vcdm";
    }

    private String selectedGroupKey() {
        int p = spGroup.getSelectedItemPosition();
        return (groupKeys != null && p >= 0 && p < groupKeys.length) ? groupKeys[p] : "bms";
    }

    private String host() {
        String h = etHost.getText().toString().trim();
        return h.isEmpty() ? DiagOptions.DOIP_HOST : h;
    }

    private int port() {
        try {
            int p = Integer.parseInt(etPort.getText().toString().trim());
            return p > 0 ? p : DiagOptions.DOIP_PORT;
        } catch (Exception e) {
            return DiagOptions.DOIP_PORT;
        }
    }

    private DiagClient ensureClient() {
        String h = host();
        int p = port();
        // 网络诊断回填了新的组主地址（或改了端口）→ 重建客户端，连到新目标
        if (client == null || !h.equals(clientHost) || p != clientPort) {
            if (client != null) client.close();
            client = new DiagClient(h, p, this::append);
            clientHost = h;
            clientPort = p;
        }
        return client;
    }

    // ==================== 只读操作 ====================

    private interface Op { void run(DiagClient c) throws Exception; }

    /** 统一：worker 线程跑阻塞协议，异常/完成回主线程。 */
    private void run(Op op, String title) {
        append("=== " + title + "（ECU " + selectedEcuKey() + "） ===");
        tvStatus.setText(title + "…");
        final DiagClient c = ensureClient();
        new Thread(() -> {
            try {
                op.run(c);
                main.post(() -> tvStatus.setText(title + " 完成"));
            } catch (Exception e) {
                append("✖ " + title + " 失败：" + e.getMessage());
                main.post(() -> tvStatus.setText(title + " 失败"));
            }
        }, "diag-op").start();
    }

    private void doReadInfo(DiagClient c) throws Exception {
        VehicleService.VehicleInfo info = VehicleService.readVehicleInfo(c, this::append);
        append("—— 车辆信息 ——");
        append("VIN        : " + info.vin);
        append("软件版本   : " + info.softwareVersion);
        append("零件号     : " + info.partNumber);
        append("供应商     : " + info.supplierName);
        append("总里程(km) : " + (info.totalMileage != null ? String.format("%.1f", info.totalMileage) : "—"));
        append("IMEI       : " + info.imei);
        append("NFC 卡数   : " + (info.cardCount != null ? String.valueOf(info.cardCount) : "—"));
    }

    /** 读故障码：拿到 19 02 原始字节后交给 {@link Dtc} 解成可读条目。 */
    private void doReadDtc(DiagClient c) {
        byte[] raw = VehicleService.readFaultCodes(c, this::append);
        if (raw.length == 0) {
            append("（无响应或读取失败）");
            return;
        }
        Dtc.Result r = Dtc.parse(raw);
        append("原始: " + DiagProtocol.bytesToHexSpaced(raw));
        append("—— 故障码解码 ——");
        append(Dtc.format(r));
        if (r.activeCount > 0) {
            tvStatus.setText("检出 " + r.activeCount + " 条故障码");
        }
    }

    private void doEcuInfo(DiagClient c) throws Exception {
        VehicleService.EcuInfo info = VehicleService.readEcuInfo(c, selectedEcuKey(), this::append);
        append("—— ECU 身份：" + info.label + "（DA=" + info.da + (info.verified ? " 已验证" : " ⚠未验证") + "）——");
        append("VIN      : " + (info.vin.isEmpty() ? "—" : info.vin));
        append("零件号   : " + (info.partNumber.isEmpty() ? "—" : info.partNumber));
        append("软件版本 : " + (info.softwareVersion.isEmpty() ? "—" : info.softwareVersion));
        append("供应商   : " + (info.supplierName.isEmpty() ? "—" : info.supplierName));
    }

    private void doReadStreams(DiagClient c) throws Exception {
        DataStreams.Group g = DataStreams.byKey(selectedGroupKey());
        Ecus.Resolved r = Ecus.resolveDa(selectedEcuKey());
        append("—— 数据流：" + g.title + " → DA=" + r.da + " ——");
        java.util.List<DataStreams.Entry> entries = DataStreams.readDataStreams(c, g, r.da,
                DiagOptions.STREAM_TIMEOUT_MS, e -> append(String.format("  %-18s = %s %s   [%s]",
                        e.name, e.value, e.unit, e.did)), this::append);
        long ok = 0;
        for (DataStreams.Entry e : entries) if (e.ok) ok++;
        append("数据流完成：命中 " + ok + "/" + entries.size());
    }

    private void doScanDids(DiagClient c) throws Exception {
        String start = etScanStart.getText().toString().trim();
        String end = etScanEnd.getText().toString().trim();
        if (start.isEmpty()) start = DiagOptions.SCAN_START;
        if (end.isEmpty()) end = DiagOptions.SCAN_END;
        final String s = start, e2 = end;
        append("—— DID 扫描 " + s + " ~ " + e2 + " ——");
        java.util.List<VehicleService.Found> found = VehicleService.scanDids(c, s, e2,
                selectedEcuKey(), null, DiagOptions.SCAN_STEP, DiagOptions.SCAN_TIMEOUT_MS,
                f -> append("  ✔ DID " + f.did + "  " + f.hex),
                (scanned, total, hit) -> append(String.format("  …%d/%d  命中 %d", scanned, total, hit)),
                this::append);
        append("扫描完成：命中 " + found.size() + " 个 DID");
    }

    // ==================== RS485 离线解码 ====================

    private void doRs485Decode() {
        final String text = etRs485.getText().toString();
        if (text.trim().isEmpty()) {
            tvRs485Out.setText("（先粘贴 RS485 抓包：每行 ts | 30位hex，或连续 hex）");
            return;
        }
        new Thread(() -> {
            Rs485.DecodeResult r = text.contains("|")
                    ? Rs485.decodeRs485Log(text) : Rs485.decodeRs485LinkHex(text);
            final String report = Rs485.formatReport(r);
            main.post(() -> tvRs485Out.setText(report));
            append("RS485 解码：链路帧 " + r.linkFrames + "，应用帧 " + r.appFrames + "，XOR 错 " + r.xorErrors);
        }, "rs485-decode").start();
    }

    // ==================== 写类操作（allowWrite 闸门 + 确认） ====================

    private void confirmWrite(String action, Op op) {
        if (!cbAllowWrite.isChecked()) {
            append("⛔ 安全拦截：" + action + " 需先勾选「允许写操作 (allowWrite)」。写操作会直接改变车辆状态。");
            Toast.makeText(this, "请先勾选 allowWrite 确认风险", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认" + action + "？")
                .setMessage("该写操作会直接改变车辆状态（ECU " + selectedEcuKey() + "）。确认车辆已上电、不在充电、非 Ready。确定继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定执行", (DialogInterface d, int w) -> run(op, action))
                .show();
    }

    private void doClearDtc(DiagClient c) throws Exception {
        Routines.Result r = Routines.clearFaultCodes(c, true, selectedEcuKey(), null, 1500, this::append);
        append("清故障码：" + (r.ok ? "✔ " : "✖ ") + r.message + (r.raw.isEmpty() ? "" : "  [" + r.raw + "]"));
    }

    private void doEcuReset(DiagClient c) throws Exception {
        Routines.Result r = Routines.ecuReset(c, true, selectedEcuKey(), null, 1500, this::append);
        append("ECU 复位：" + (r.ok ? "✔ " : "✖ ") + r.message + (r.raw.isEmpty() ? "" : "  [" + r.raw + "]"));
    }

    private void doRunRoutine(DiagClient c) throws Exception {
        String rid = etRoutineId.getText().toString().trim();
        String sub = etRoutineSub.getText().toString().trim();
        if (sub.isEmpty()) sub = "01";
        Routines.Result r = Routines.runRoutine(c, true, rid, sub, selectedEcuKey(), null, null, 2000, this::append);
        append("例程 " + rid + "：" + (r.ok ? "✔ " : "✖ ") + r.message + (r.raw.isEmpty() ? "" : "  [" + r.raw + "]"));
    }

    // ==================== 日志 ====================

    private void clearLog() {
        log.setLength(0);
        tvLog.setText("（日志已清空）");
    }

    private void setupLogScrolling(TextView tv) {
        tv.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tv.setVerticalScrollBarEnabled(true);
        tv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (a == android.view.MotionEvent.ACTION_UP
                        || a == android.view.MotionEvent.ACTION_CANCEL) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        });
    }

    private void append(final String s) {
        HookLog.log(HookLog.DIAG + " " + s);
        main.post(() -> {
            log.append(s).append('\n');
            if (log.length() > LOG_MAX_LINES * 100) {
                int nl = log.indexOf("\n");
                if (nl >= 0) log.delete(0, nl + 1);
            }
            tvLog.setText(log.toString());
            tvLog.post(() -> {
                android.text.Layout l = tvLog.getLayout();
                if (l == null) return;
                int bottom = l.getHeight() - tvLog.getHeight();
                if (bottom < 0) bottom = 0;
                tvLog.scrollTo(0, bottom);
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统 WLAN / 定位设置返回时继续排队中的 P2P 扫描
        if (p2pPanel != null) p2pPanel.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (p2pPanel != null) {
            p2pPanel.close();
            p2pPanel = null;
        }
        if (absThread != null) {
            absThread.interrupt();
            absThread = null;
        }
        if (vlink != null) {
            vlink.disconnect();
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
