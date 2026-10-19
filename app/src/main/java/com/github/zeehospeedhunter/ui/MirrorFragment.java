package com.github.zeehospeedhunter.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.Keys;
import com.github.zeehospeedhunter.mirror.StandaloneMirrorService;
import com.github.zeehospeedhunter.ota.MirrorRequest;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.UUID;

/** Standalone P2P / EC projection, with local QR camera entry. */
public final class MirrorFragment extends BaseTabFragment {

    @Override
    protected int getLayoutId() {
        return R.layout.activity_mirror;
    }

    private TextView tvLog, tvStatus, tvConnectionInfo;
    private android.view.View scanButton;
    private boolean scanning;
    private EditText etUrl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private String requestId;
    private boolean registered;
    private P2pAccess access;
    private String pendingUrl;
    private boolean pendingSmooth;
    private int pendingFps = 15;
    private String[] names = new String[0], macs = new String[0];

    private final ActivityResultLauncher<Intent> capturePermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != android.app.Activity.RESULT_OK
                        || result.getData() == null || pendingUrl == null) {
                    tvStatus.setText("已取消屏幕授权，未启动投屏");
                    return;
                }
                Intent start = new Intent(requireContext(), StandaloneMirrorService.class)
                        .setAction(StandaloneMirrorService.START)
                        .putExtra("targetFps", pendingFps)
                        .putExtra("smooth", pendingSmooth)
                        .putExtra("url", pendingUrl)
                        .putExtra("result", result.getResultCode())
                        .putExtra("consent", result.getData());
                pendingUrl = null;
                try {
                    ContextCompat.startForegroundService(requireContext(), start);
                } catch (Exception e) {
                    append("启动失败：" + e.getMessage());
                }
            });

    private final ActivityResultLauncher<ScanOptions> qrScanner =
            registerForActivityResult(new ScanContract(), result -> {
                scanning = false;
                if (scanButton != null) scanButton.setEnabled(true);
                if (result.getContents() == null) {
                    append("已取消扫码，原连接信息已保留");
                    return;
                }
                try {
                    // Validate before replacing a manual/previous QR. Never launch a scanned URL.
                    MirrorRequest qr = MirrorRequest.parse(result.getContents());
                    etUrl.setText(qr.url);
                    parseUrl();
                    tvStatus.setText("扫码成功，连接信息已填入；可点击开始投屏");
                } catch (IllegalArgumentException e) {
                    tvStatus.setText("不是有效的仪表投屏二维码");
                    append("扫码内容未通过投屏校验：" + e.getMessage() + "；原连接信息已保留");
                }
            });

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchQrCamera();
                else {
                    scanning = false;
                    scanButton.setEnabled(true);
                    tvStatus.setText("相机权限未授予，仍可手动粘贴二维码 URL");
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("需要相机权限")
                            .setMessage("请在应用设置中允许相机权限后重试；也可以继续手动粘贴二维码 URL。")
                            .setPositiveButton("应用设置", (dialog, which) -> {
                                try {
                                    startActivity(new Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package",
                                                    requireContext().getPackageName(), null)));
                                } catch (Throwable e) {
                                    append("请在系统设置中给 SpeedHunter 授予相机权限");
                                }
                            }).setNegativeButton("取消", null).show();
                }
            });

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent it) {
            String perf = it.getStringExtra("perf");
            View root = getView();
            if (perf != null && root != null) {
                ((TextView) root.findViewById(R.id.tv_mirror_perf)).setText(perf);
            }
            String message = it.getStringExtra("message");
            if (message != null) {
                tvStatus.setText(message);
                append(message);
            }
            String[] n = it.getStringArrayExtra("names"), m = it.getStringArrayExtra("macs");
            if (n != null && m != null && n.length == m.length) {
                names = n;
                macs = m;
            }
        }
    };

    @Override
    public void onViewCreated(View root, Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);
        access = new P2pAccess(requireActivity(),
                message -> {
                    tvStatus.setText(message);
                    append(message);
                });
        BackGestureCompat.install(requireActivity());
        EdgeToEdge.applyTop(root);

        tvLog = root.findViewById(R.id.tv_log);
        tvStatus = root.findViewById(R.id.tv_status);
        etUrl = root.findViewById(R.id.et_mirror_url);
        tvConnectionInfo = root.findViewById(R.id.tv_mirror_info);
        scanButton = root.findViewById(R.id.btn_scan_mirror_qr);
        scanButton.setOnClickListener(v -> scanQr());
        tvLog.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        requestId = savedInstanceState == null
                ? null : savedInstanceState.getString("requestId");
        if (requestId == null) {
            requestId = requireContext().getSharedPreferences("mirror", android.content.Context.MODE_PRIVATE)
                    .getString("requestId", null);
        }
        if (requestId == null) requestId = UUID.randomUUID().toString();
        ((com.google.android.material.switchmaterial.SwitchMaterial) root.findViewById(R.id.switch_smooth))
                .setChecked(requireContext().getSharedPreferences("mirror", android.content.Context.MODE_PRIVATE)
                        .getBoolean("smooth", true));
        android.widget.Spinner quality = root.findViewById(R.id.spinner_mirror_quality);
        quality.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"平衡：15fps / 800kbps（横屏优先）", "流畅：20fps / 1200kbps", "低负载：10fps / 500kbps"}));
        quality.setSelection(requireContext().getSharedPreferences("mirror", android.content.Context.MODE_PRIVATE)
                .getInt("quality", 0));
        etUrl.setText(requireContext().getSharedPreferences("mirror", android.content.Context.MODE_PRIVATE)
                .getString("qrUrl", ""));
        try {
            showConnectionInfo(MirrorRequest.parse(etUrl.getText().toString()));
        } catch (IllegalArgumentException ignored) {
        }
        root.findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            log.setLength(0);
            tvLog.setText("");
        });
        root.findViewById(R.id.btn_parse_url).setOnClickListener(v -> parseUrl());
        root.findViewById(R.id.btn_start_mirror).setOnClickListener(v -> startMirror());
        root.findViewById(R.id.btn_stop_mirror)
                .setOnClickListener(v -> command(StandaloneMirrorService.STOP));
        root.findViewById(R.id.btn_mirror_test).setOnClickListener(
                v -> startActivity(new Intent(requireContext(),
                        com.github.zeehospeedhunter.mirror.MirrorTestActivity.class)));
        root.findViewById(R.id.btn_ec_probe).setOnClickListener(v -> choosePeer());
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(StandaloneMirrorService.STATE);
        ContextCompat.registerReceiver(requireContext(), receiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
        command(StandaloneMirrorService.QUERY);
    }

    @Override
    public void onStop() {
        if (registered) {
            try {
                requireContext().unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            registered = false;
        }
        main.removeCallbacksAndMessages(null);
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        out.putString("requestId", requestId);
        super.onSaveInstanceState(out);
    }

    @Override
    public void onPageShown() {
        if (access != null) access.onResume();
    }

    @Override
    public void onDestroy() {
        if (access != null) access.close();
        super.onDestroy();
    }

    private MirrorRequest parseUrl() {
        try {
            MirrorRequest qr = MirrorRequest.parse(etUrl.getText().toString());
            requireContext().getSharedPreferences("mirror", android.content.Context.MODE_PRIVATE)
                    .edit().putString("qrUrl", qr.url).apply();
            showConnectionInfo(qr);
            append("二维码校验通过：含 name 和 action=" + qr.action + "；完整 URL 已保留，本页不显示密码");
            return qr;
        } catch (IllegalArgumentException e) {
            append(e.getMessage());
            tvStatus.setText(e.getMessage());
            return null;
        }
    }

    private void showConnectionInfo(MirrorRequest qr) {
        tvConnectionInfo.setText("P2P 设备：" + qr.name + "\n连接模式 action：" + qr.action
                + "\nSSID：" + (qr.ssid == null || qr.ssid.isEmpty() ? "未提供" : qr.ssid)
                + "\nMAC：" + (qr.mac == null || qr.mac.isEmpty() ? "未提供" : qr.mac)
                + "\n密码：" + (qr.hasPassword ? "已读取（不在此显示）" : "未提供"));
    }

    private void scanQr() {
        if (scanning) return;
        if (!requireContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            append("设备没有可用相机，请手动粘贴二维码 URL");
            return;
        }
        scanning = true;
        scanButton.setEnabled(false);
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchQrCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchQrCamera() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("请对准仪表投屏二维码");
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        options.setOrientationLocked(false);
        options.setCaptureActivity(MirrorCaptureActivity.class);
        try {
            qrScanner.launch(options);
        } catch (Throwable e) {
            scanning = false;
            scanButton.setEnabled(true);
            tvStatus.setText("无法打开相机，请检查权限或继续手动粘贴 URL");
        }
    }

    private void startMirror() {
        MirrorRequest qr = parseUrl();
        if (qr == null) return;
        access.run(() -> {
            pendingUrl = qr.url;
            int quality = ((android.widget.Spinner) requireView()
                    .findViewById(R.id.spinner_mirror_quality)).getSelectedItemPosition();
            pendingFps = new int[]{15, 20, 10}[Math.max(0, Math.min(2, quality))];
            requireContext().getSharedPreferences("mirror", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("quality", quality).apply();
            pendingSmooth = ((com.google.android.material.switchmaterial.SwitchMaterial)
                    requireView().findViewById(R.id.switch_smooth)).isChecked();
            requireContext().getSharedPreferences("mirror", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("smooth", pendingSmooth).apply();
            capturePermission.launch(requireContext()
                    .getSystemService(MediaProjectionManager.class).createScreenCaptureIntent());
        });
    }

    private void command(String action) {
        try {
            requireContext().startService(
                    new Intent(requireContext(), StandaloneMirrorService.class).setAction(action));
        } catch (Exception e) {
            append("操作失败：" + e.getMessage());
        }
    }

    private void choosePeer() {
        if (names.length == 0) {
            append("尚无可选设备，请先开始投屏并等待扫描");
            return;
        }
        final String[] selectedMacs = macs.clone();
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("选择自己的仪表")
                .setItems(names.clone(), (d, n) -> requireContext().startService(
                        new Intent(requireContext(), StandaloneMirrorService.class)
                                .setAction(StandaloneMirrorService.SELECT)
                                .putExtra("mac", selectedMacs[n])))
                .setNegativeButton("取消", null).show();
    }

    private void append(String text) {
        HookLog.log(HookLog.MIRROR + " UI " + text);
        log.append(text).append('\n');
        if (log.length() > 40000) log.delete(0, log.indexOf("\n") + 1);
        tvLog.setText(log.toString());
        tvLog.post(() -> {
            android.text.Layout layout = tvLog.getLayout();
            if (layout != null) {
                tvLog.scrollTo(0, Math.max(0, layout.getHeight() - tvLog.getHeight()));
            }
        });
    }
}
