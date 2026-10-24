package com.github.speedhunter.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.github.speedhunter.ota.CarNet;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

/** Acquire discovery prerequisites in this module's own process before calling P2P APIs. */
final class P2pAccess {
    private final ComponentActivity activity;
    private final Consumer<String> status;
    private final ActivityResultLauncher<String[]> permissions;
    private Runnable pending;
    private boolean requesting;
    private boolean waitingForSettings;
    private boolean closed;

    P2pAccess(ComponentActivity activity, Consumer<String> status) {
        this.activity = activity;
        this.status = status;
        permissions = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    requesting = false;
                    if (closed || pending == null) return;
                    if (hasPermission()) {
                        proceed(true);
                    } else {
                        String label = Build.VERSION.SDK_INT >= 33 ? "附近设备" : "精确位置";
                        status.accept("未授予" + label + "权限，Wi-Fi Direct 扫描尚未启动。");
                        showSettings("需要" + label + "权限",
                                Build.VERSION.SDK_INT >= 33
                                        ? "请在应用权限中允许附近设备，返回后继续扫描。"
                                        : "请在应用权限中允许位置访问；Android 12 请同时开启精确位置。返回后继续扫描。",
                                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + activity.getPackageName())));
                    }
                });
    }

    void run(Runnable action) {
        if (closed || requesting || waitingForSettings) return;
        pending = action;
        proceed(true);
    }

    private boolean hasPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void proceed(boolean prompt) {
        if (closed || pending == null) return;
        if (!hasPermission()) {
            if (prompt) {
                requesting = true;
                status.accept("正在申请 Wi-Fi Direct 扫描权限…");
                permissions.launch(Build.VERSION.SDK_INT >= 33
                        ? new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}
                        : new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                      Manifest.permission.ACCESS_COARSE_LOCATION});
            } else {
                status.accept("扫描未启动：仍缺少" + (Build.VERSION.SDK_INT >= 33 ? "附近设备" : "精确位置")
                        + "权限。授权后再点扫描。");
                pending = null;
            }
            return;
        }
        WifiManager wifi = (WifiManager) activity.getApplicationContext()
                .getSystemService(android.content.Context.WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) {
            status.accept("扫描未启动：手机 WLAN 未开启。");
            if (prompt) showSettings("请开启 WLAN", "开启手机 WLAN 后返回，将继续扫描仪表。",
                    new Intent(Settings.ACTION_WIFI_SETTINGS));
            else pending = null;
            return;
        }
        if (Build.VERSION.SDK_INT < 33 && !CarNet.isLocationOn(activity)) {
            status.accept("扫描未启动：系统定位开关未开启（Android 6–12 的 Wi-Fi Direct 扫描要求）。");
            if (prompt) showSettings("请开启定位服务", "系统需要定位服务开启才能发现 Wi-Fi Direct 设备。开启后返回继续扫描。",
                    new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            else pending = null;
            return;
        }
        Runnable action = pending;
        pending = null;
        action.run();
    }

    private void showSettings(String title, String message, Intent intent) {
        new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
                .setPositiveButton("打开设置", (d, w) -> {
                    waitingForSettings = true;
                    try { activity.startActivity(intent); }
                    catch (RuntimeException e) {
                        waitingForSettings = false;
                        pending = null;
                        status.accept("无法打开设置：" + e.getMessage());
                    }
                }).setNegativeButton("取消", (d, w) -> pending = null)
                .setOnCancelListener(d -> pending = null).show();
    }

    void onResume() {
        if (waitingForSettings && !closed) {
            waitingForSettings = false;
            proceed(false);
        }
    }

    void close() {
        closed = true;
        pending = null;
    }
}
