package com.github.zeehospeedhunter.ui;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.diag.DataStreams;
import com.github.zeehospeedhunter.diag.DiagClient;
import com.github.zeehospeedhunter.diag.DiagOptions;
import com.github.zeehospeedhunter.diag.DiagProtocol;
import com.github.zeehospeedhunter.diag.Ecus;
import com.github.zeehospeedhunter.diag.Routines;
import com.github.zeehospeedhunter.diag.Rs485;
import com.github.zeehospeedhunter.diag.VehicleService;
import com.github.zeehospeedhunter.ota.CarWifi;

/**
 * 车端诊断操作台 —— 把 zeeho-diag（Electron 桌面端）的诊断能力搬到手机。
 *
 * <h3>能力（对应 zeeho-diag lib/）</h3>
 * <ul>
 *   <li>DoIP/UDS：{@link DiagClient} + {@link DiagProtocol}（TCP 192.168.49.1:13400）</li>
 *   <li>车辆信息 / 故障码 / ECU 身份 / DID 扫描 / 数据流：{@link VehicleService} + {@link DataStreams}</li>
 *   <li>写类维修操作（默认硬禁 + allowWrite 闸门）：{@link Routines}</li>
 *   <li>RS485 私协议离线解码：{@link Rs485}</li>
 *   <li>ECU 全景表（已验证地址优先、未知地址回退 CAN 并标注 ⚠）：{@link Ecus}</li>
 * </ul>
 *
 * <p>连车机 AP 复用 {@link CarWifi}；阻塞式协议调用全部放 worker 线程，结果回主线程刷新，
 * 日志区套路与 {@link OtaActivity} 一致（固定高度 + 自滚 + 落 {@code zeeho_hook.log}）。</p>
 */
public final class DiagActivity extends AppCompatActivity {

    private static final int LOG_MAX_LINES = 400;

    private TextView tvLog;
    private TextView tvStatus;
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
    private String[] ecuKeys;
    private String[] groupKeys;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        com.github.zeehospeedhunter.ui.EdgeToEdge.setup(this, findViewById(R.id.root_diag));
        com.github.zeehospeedhunter.ui.BackGestureCompat.install(this);
        main = new Handler(Looper.getMainLooper());

        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_diag_status);
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

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());
        carNet = new CarNetController(this, findViewById(android.R.id.content), new CarNetController.Callback() {
            @Override
            public void onDiagnosed(com.github.zeehospeedhunter.ota.CarNet.DiagReport report, String groupOwner) {
                if (groupOwner != null && !groupOwner.isEmpty()) {
                    // 探测到的组主地址回填 DoIP 目标（组主可能是 0.1 而非硬编码 49.1）
                    etHost.setText(groupOwner);
                    prefs.edit().putString("host", groupOwner).apply();
                    append("网络诊断回填 DoIP 主机 = " + groupOwner);
                } else {
                    append("网络诊断未探到组主地址，DoIP 主机保持手动值 " + host());
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
        findViewById(R.id.btn_read_info).setOnClickListener(v -> run(this::doReadInfo, "读车辆信息"));
        findViewById(R.id.btn_read_dtc).setOnClickListener(v -> run(this::doReadDtc, "读故障码"));
        findViewById(R.id.btn_ecu_info).setOnClickListener(v -> run(this::doEcuInfo, "ECU 身份"));
        findViewById(R.id.btn_read_streams).setOnClickListener(v -> run(this::doReadStreams, "读数据流"));
        findViewById(R.id.btn_scan).setOnClickListener(v -> run(this::doScanDids, "DID 扫描"));
        findViewById(R.id.btn_rs485_decode).setOnClickListener(v -> doRs485Decode());
        findViewById(R.id.btn_clear_dtc).setOnClickListener(v -> confirmWrite("清除故障码", this::doClearDtc));
        findViewById(R.id.btn_ecu_reset).setOnClickListener(v -> confirmWrite("ECU 复位", this::doEcuReset));
        findViewById(R.id.btn_run_routine).setOnClickListener(v -> confirmWrite("例程控制(0x31)", this::doRunRoutine));

        append("诊断台就绪。先「连接」车机 AP，再点只读诊断。写操作需勾选 allowWrite。");
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

    private void doReadDtc(DiagClient c) {
        byte[] raw = VehicleService.readFaultCodes(c, this::append);
        append("—— 故障码（19 02 09）——");
        if (raw.length == 0) {
            append("（无响应或读取失败）");
        } else {
            append("原始: " + DiagProtocol.bytesToHexSpaced(raw));
            append("提示: 0x59 02 09 后按 DTC(3B)+状态(1B) 解析；全 00 = 无故障码");
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
    protected void onDestroy() {
        super.onDestroy();
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
