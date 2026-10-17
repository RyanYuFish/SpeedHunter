package com.github.zeehospeedhunter.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.ota.CarNet;
import com.github.zeehospeedhunter.ota.CarWifi;


/**
 * 车机连接条的共享控制器 —— 「系统 Wi-Fi 直连（主） + App 内直连（兜底） + 网络诊断」。
 *
 * <p>三页（OTA 推送 / 仪表投屏 / 车端诊断）共用 {@code res/layout/view_carnet.xml} 与本类，
 * 不再各自维护一套「手填 SSID + 密码」表单。行为依据第三方「极核仪表 OTA 工具」：连接动作
 * 交给<b>系统设置</b>（优先 5GHz），本模块只做「引导 + 探测组主地址 + 回填」。</p>
 *
 * <h3>三种动作</h3>
 * <ol>
 *   <li><b>系统 Wi-Fi 直连</b>：断开当前 Wi-Fi → 拉起系统 WLAN 面板 → 用户连 {@code ZEEHO-xxxx}（5GHz）
 *       → 回到本页点「网络诊断」。组主地址靠诊断得出，不写死 0.1 / 49.1。</li>
 *   <li><b>App 内直连（兜底）</b>：保留原 {@link CarWifi} 的 WifiNetworkSpecifier / addNetwork 路径。</li>
 *   <li><b>网络诊断</b>：TCP 握手判定组主地址与端口（10950 / 13400 / 5555）、识别连接模式与 5GHz，
 *       结果回填到宿主页（{@link Callback#onDiagnosed}）。</li>
 * </ol>
 *
 * <p><b>红线</b>：诊断只做 TCP 握手判断端口是否监听，<b>不发任何非本协议帧</b>，不改网络配置。</p>
 */
public final class CarNetController {

    /** 宿主页回调（诊断出组主地址后回填、状态刷新）。 */
    public interface Callback {
        /** 网络诊断完成。{@code groupOwner} 为探测到的组主地址（可能为空）。 */
        void onDiagnosed(CarNet.DiagReport report, String groupOwner);

        /** 连接动作的阶段提示（供日志区）。 */
        void onStage(String s);
    }

    private final Activity act;
    private final Callback cb;
    private final SharedPreferences prefs;

    private View rowAppDirect;
    private EditText etSsid, etPw;
    private TextView tvStatus;

    /** 「App 内直连」用 WifiNetworkSpecifier 需要附近设备/定位权限（宿主页各自也有，这里统一兜一份）。 */
    private final ActivityResultLauncher<String> reqWifiPerm;
    private Runnable afterPerm;

    /** 最近一次诊断出的组主地址（宿主可据此回填硬编码目标）。 */
    private String lastGroupOwner = "";

    public CarNetController(Activity act, View root, Callback cb, SharedPreferences prefs) {
        this.act = act;
        this.cb = cb;
        this.prefs = prefs;

        reqWifiPerm = ((androidx.activity.ComponentActivity) act).registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    Runnable a = afterPerm;
                    afterPerm = null;
                    if (a != null) {
                        if (granted) a.run();
                        else cb.onStage("⚠ 未授予附近设备/定位权限，App 内直连不可用；"
                                + "可改用「系统 Wi-Fi 直连」");
                    }
                });

        tvStatus = root.findViewById(R.id.tv_carnet_status);
        rowAppDirect = root.findViewById(R.id.row_app_direct);
        etSsid = root.findViewById(R.id.et_carnet_ssid);
        etPw = root.findViewById(R.id.et_carnet_pw);

        // 兜底表单初值：优先用已存车机 SSID，否则默认
        String savedSsid = prefs.getString("car_ssid", CarWifi.DEFAULT_SSID);
        String savedPw = prefs.getString("car_pw", "");
        etSsid.setText(savedSsid);
        etPw.setText(savedPw);

        // ① 系统 Wi-Fi 直连（主）
        root.findViewById(R.id.btn_system_wifi).setOnClickListener(v -> systemDirect());

        // ② 网络诊断
        root.findViewById(R.id.btn_netdiag).setOnClickListener(v -> runDiagnose());

        // ③ App 内直连（兜底）：折叠/展开
        root.findViewById(R.id.btn_app_direct_toggle).setOnClickListener(v -> {
            boolean vis = rowAppDirect.getVisibility() == View.VISIBLE;
            rowAppDirect.setVisibility(vis ? View.GONE : View.VISIBLE);
        });
        root.findViewById(R.id.btn_app_direct).setOnClickListener(v -> appDirect());
    }

    // ==================== ① 系统 Wi-Fi 直连 ====================

    /**
     * 系统 WLAN 直连：先断开当前 Wi-Fi（系统才能干净连上车机热点），再拉起系统 WLAN 面板。
     * 连上后回本页点「网络诊断」回填组主地址。
     */
    private void systemDirect() {
        String ssid = etSsid.getText().toString().trim();
        // 只要用户填了非空 SSID 就记住（DEFAULT_SSID 已是空串，不再有「等于默认值」的特例）
        if (!TextUtils.isEmpty(ssid)) {
            prefs.edit().putString("car_ssid", ssid).apply();
        }
        setStatus("① 已断开当前 Wi-Fi，正在打开系统 WLAN 设置…\n"
                + "② 请在系统设置里连车机热点 ZEEHO-xxxx（优先 5GHz），手机热点请先关。\n"
                + "③ 连上后回到本页点「网络诊断」，自动探测并回填组主地址。");
        cb.onStage("系统直连：断开当前 Wi-Fi → 打开系统 WLAN 设置");
        CarNet.disconnectCurrentWifi(act);
        // 位置服务对部分系统的 Wi-Fi 扫描/直连是前置，顺带提示
        if (!CarNet.isLocationOn(act)) {
            cb.onStage("⚠ 系统定位未开，部分机型连热点会失败（可手动开：设置→定位）");
        }
        CarNet.openWifiPanel(act);
    }

    // ==================== ② App 内直连（兜底） ====================

    /** 保留原 CarWifi（WifiNetworkSpecifier / addNetwork）路径，作为系统直连不可用时的兜底。 */
    private void appDirect() {
        final String ssid = etSsid.getText().toString().trim();
        final String pw = etPw.getText().toString();
        if (TextUtils.isEmpty(ssid)) {
            setStatus("SSID 不能为空");
            return;
        }
        prefs.edit().putString("car_ssid", ssid).putString("car_pw", pw).apply();
        setStatus("App 内直连中… " + ssid);
        cb.onStage("App 内直连（兜底）：" + ssid);
        ensureWifiPermThen(() -> {
            // 扫到的车机 AP 若存在，回填密码为空的开销（open 网络直接连）
            CarWifi.connect(act, ssid, pw, new CarWifi.ConnectResult() {
                @Override
                public void onResult(boolean ok, String msg) {
                    cb.onStage(msg);
                    if (ok) {
                        setStatus("✔ App 内已连 " + ssid + "\n点「网络诊断」确认组主地址与端口。");
                    } else {
                        setStatus("✖ App 内直连失败：" + msg);
                    }
                }
            });
        });
    }

    /** 缺权限先申请（API33+ 附近设备；29–32 精确定位），已有则直接跑 action。 */
    private void ensureWifiPermThen(Runnable action) {
        String need = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(act, android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                need = android.Manifest.permission.NEARBY_WIFI_DEVICES;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(act, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                need = android.Manifest.permission.ACCESS_FINE_LOCATION;
            }
        }
        if (need == null) {
            action.run();
        } else {
            afterPerm = action;
            reqWifiPerm.launch(need);
        }
    }

    // ==================== ③ 网络诊断 ====================

    /** 跑一次网络诊断（异步），结果写状态区 + 回调回填。 */
    public void runDiagnose() {
        setStatus("网络诊断中…（TCP 握手判定，不发业务数据）");
        CarNet.diagnoseAsync(act, new CarNet.DiagListener() {
            @Override
            public void onStage(String s) {
                cb.onStage(s);
            }

            @Override
            public void onResult(final CarNet.DiagReport report) {
                lastGroupOwner = report.groupOwner == null ? "" : report.groupOwner;
                setStatus(report.summary);
                cb.onStage("网络诊断完成：组主=" + (report.hasCar() ? report.groupOwner : "未探到")
                        + " 模式=" + report.mode);
                HookLog.log(HookLog.DIAG + " 网络诊断 " + report.summary.replace('\n', ' '));
                cb.onDiagnosed(report, lastGroupOwner);
            }
        });
    }

    public String lastGroupOwner() {
        return lastGroupOwner;
    }

    /** 供宿主页把扫到的/解析出的车机凭据回填进兜底表单（如投屏二维码里的 ssid/pwd）。 */
    public void setSsidPw(String ssid, String pw) {
        if (etSsid != null && !TextUtils.isEmpty(ssid)) etSsid.setText(ssid);
        if (etPw != null) etPw.setText(pw == null ? "" : pw);
        if (prefs != null) prefs.edit().putString("car_ssid", ssid).putString("car_pw", pw == null ? "" : pw).apply();
    }

    /** 当前车机 SSID（优先连接条输入，其次 prefs）。 */
    public String getSsid() {
        if (etSsid != null && !TextUtils.isEmpty(etSsid.getText().toString().trim())) {
            return etSsid.getText().toString().trim();
        }
        return prefs == null ? "" : prefs.getString("car_ssid", CarWifi.DEFAULT_SSID);
    }

    /** 当前车机密码。 */
    public String getPw() {
        if (etPw != null) return etPw.getText().toString();
        return prefs == null ? "" : prefs.getString("car_pw", "");
    }

    private void setStatus(final String s) {
        if (tvStatus == null) return;
        tvStatus.post(() -> tvStatus.setText(s));
    }
}