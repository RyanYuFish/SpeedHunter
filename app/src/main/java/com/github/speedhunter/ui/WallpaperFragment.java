package com.github.speedhunter.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.github.speedhunter.R;
import com.github.speedhunter.ota.CarNet;
import com.github.speedhunter.ota.CarP2p;
import com.github.speedhunter.ota.OtaSocketPusher;
import com.github.speedhunter.ota.WallpaperSocketPusher;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.yalantis.ucrop.UCrop;

/**
 * 仪表壁纸推送。
 *
 * <p>① 连接：Wi-Fi Direct 扫描/连接（{@link CarP2p}，跨车辆通用）+ 系统 WLAN + 探测 10950；<br>
 * ② 裁切：uCrop（官方 README 用法），裁了哪张就推哪张；<br>
 * ③ 打包并推送，或直接选现成 ZIP。</p>
 *
 * <p>车机地址只有 192.168.0.1 / 192.168.49.1（外部已验证），用 {@link CarNet#resolveCarHost}
 * 在两者间做 TCP 握手选择，<b>不做任何推导</b>。</p>
 */
public final class WallpaperFragment extends BaseTabFragment {

    @Override
    protected int getLayoutId() {
        return R.layout.activity_wallpaper;
    }

    private File staged;
    private File croppedSmall;
    private File croppedBig;
    private Uri pendingSrc;
    private boolean cropBigNext;
    private CarP2p p2p;

    private TextView fileLabel;
    private TextView linkStatus;
    private TextView progressText;
    private TextView log;
    private LinearProgressIndicator progress;
    private MaterialButton send;
    private MaterialButton packPush;
    private LinearLayout peerList;
    private ImageView pvSmall;
    private ImageView pvBig;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::onCropResult);

    private final ActivityResultLauncher<String> pickZip = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    stageZip(uri);
                }
            });

    private final ActivityResultLauncher<String> pickImage = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingSrc = uri;
                    startCrop();
                }
            });

    @Override
    public void onViewCreated(View root, Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);
        BackGestureCompat.install(requireActivity());
        EdgeToEdge.applyTop(root);

        fileLabel = root.findViewById(R.id.wallpaper_file);
        linkStatus = root.findViewById(R.id.wp_link_status);
        progressText = root.findViewById(R.id.wallpaper_progress_text);
        progress = root.findViewById(R.id.wallpaper_progress);
        log = root.findViewById(R.id.wallpaper_log);
        send = root.findViewById(R.id.wallpaper_send);
        packPush = root.findViewById(R.id.wallpaper_pack_push);
        peerList = root.findViewById(R.id.wp_peer_list);
        pvSmall = root.findViewById(R.id.wallpaper_preview_small);
        pvBig = root.findViewById(R.id.wallpaper_preview_big);

        root.findViewById(R.id.wallpaper_clear_log).setOnClickListener(v -> log.setText(""));

        // ① 连接
        root.findViewById(R.id.wp_p2p_scan).setOnClickListener(v -> p2pScan());
        root.findViewById(R.id.wp_p2p_disconnect).setOnClickListener(v -> {
            if (p2p != null) {
                p2p.disconnect();
                append("已请求断开 P2P");
            }
        });
        root.findViewById(R.id.wp_probe).setOnClickListener(v -> probeLink());
        root.findViewById(R.id.wp_open_wifi).setOnClickListener(
                v -> CarNet.openSystemWifiSettings(requireActivity()));

        // ② 裁切
        root.findViewById(R.id.wallpaper_crop_big).setOnClickListener(v -> {
            cropBigNext = true;
            pickImage.launch("image/*");
        });
        root.findViewById(R.id.wallpaper_crop_small).setOnClickListener(v -> {
            cropBigNext = false;
            pickImage.launch("image/*");
        });
        packPush.setOnClickListener(v -> packAndPush());

        // ③ ZIP
        root.findViewById(R.id.wallpaper_pick).setOnClickListener(v -> pickZip.launch("application/zip"));
        send.setEnabled(false);
        send.setOnClickListener(v -> pushStaged());

        append("① Wi-Fi Direct 扫描连接仪表（跨车辆通用），或用系统 WLAN 直连\n"
                + "② 「选大图 / 选小图」裁切（定尺 1024×600 与 307×460）\n"
                + "③ 「打包并推送」—— 只裁了哪张就只推哪张\n"
                + "★ 车机 10950 是单客户端，官方 App 占用时连不上\n"
                + "结果以仪表屏幕为准（静默 ≠ 失败）");
        refreshState();
    }

    @Override
    public void onExternalIntent(Intent intent) {
        handleAuto(intent);
    }

    @Override
    public void onDestroy() {
        if (p2p != null) {
            p2p.close();
            p2p = null;
        }
        super.onDestroy();
    }

    // ==================== ① Wi-Fi Direct ====================

    private void p2pScan() {
        if (p2p == null) {
            p2p = new CarP2p(requireContext(), main, new CarP2p.Listener() {
                @Override
                public void onLine(String line) {
                    append(line);
                }

                @Override
                public void onPeers(List<WifiP2pDevice> peers) {
                    showPeers(peers);
                }

                @Override
                public void onGroup(WifiP2pInfo info, WifiP2pGroup group) {
                    String go = (info != null && info.groupOwnerAddress != null)
                            ? info.groupOwnerAddress.getHostAddress() : "?";
                    linkStatus.setText("P2P 已连接\n组主：" + go
                            + "\n车机地址：" + CarNet.resolveCarHost(WallpaperSocketPusher.PORT));
                    append("P2P 已连接，组主 " + go + "；车机地址探测："
                            + CarNet.resolveCarHost(WallpaperSocketPusher.PORT));
                }
            });
        }
        linkStatus.setText("扫描中…");
        append("=== Wi-Fi Direct 扫描车机设备 ===");
        p2p.scan();
    }

    private void showPeers(List<WifiP2pDevice> peers) {
        peerList.removeAllViews();
        if (peers == null || peers.isEmpty()) {
            return;
        }
        for (final WifiP2pDevice d : peers) {
            if (!CarP2p.isCarName(d.deviceName)) {
                continue;
            }
            MaterialButton b = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(d.deviceName + (d.deviceAddress == null ? "" : "\n" + d.deviceAddress));
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> {
                append("连接 " + d.deviceName + " …");
                p2p.connect(d);
            });
            peerList.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void probeLink() {
        String host = CarNet.resolveCarHost(WallpaperSocketPusher.PORT);
        OtaSocketPusher.setResolvedCarHost(host);
        append("探测 " + host + ":" + WallpaperSocketPusher.PORT + " …");
        linkStatus.setText("探测中… " + host);
        new Thread(() -> {
            boolean ok = CarNet.portOpen(host, WallpaperSocketPusher.PORT, 2000, null);
            main.post(() -> {
                linkStatus.setText(ok
                        ? "✔ " + host + ":" + WallpaperSocketPusher.PORT + " 已监听，可推送"
                        : "✖ " + host + ":" + WallpaperSocketPusher.PORT
                        + " 未监听（官方 App 占用？或未连接仪表）");
            });
        }, "wp-probe").start();
    }

    // ==================== ② 裁切（uCrop 官方用法） ====================

    private void startCrop() {
        try {
            int w = cropBigNext ? WallpaperSocketPusher.BIG_W : WallpaperSocketPusher.SMALL_W;
            int h = cropBigNext ? WallpaperSocketPusher.BIG_H : WallpaperSocketPusher.SMALL_H;
            File dst = File.createTempFile(cropBigNext ? "wp2_" : "wp1_", ".png",
                    requireContext().getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(dst)) {
                fos.flush();
            }
            UCrop.of(pendingSrc, Uri.fromFile(dst))
                    .withAspectRatio((float) w / h, 1f)
                    .withMaxResultSize(w, h)
                    .start(requireActivity(), cropLauncher);
            linkStatus.setText("裁切中（" + w + "×" + h + "）…");
        } catch (Exception e) {
            linkStatus.setText("启动裁切失败：" + e);
        }
    }

    private void onCropResult(ActivityResult result) {
        Intent data = result.getData();
        if (result.getResultCode() == UCrop.RESULT_ERROR) {
            Throwable err = UCrop.getError(data);
            append("裁切失败：" + (err == null ? "未知错误" : err.getMessage()));
            return;
        }
        if (result.getResultCode() != android.app.Activity.RESULT_OK) {
            append("已取消裁切");
            return;
        }
        Uri out = UCrop.getOutput(data);
        if (out == null || out.getPath() == null) {
            append("裁切未返回图片（resultCode=" + result.getResultCode() + "）");
            return;
        }
        File f = new File(out.getPath());
        Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bm == null) {
            append("裁切输出读取失败：" + out.getPath());
            return;
        }
        if (cropBigNext) {
            croppedBig = f;
            pvBig.setImageBitmap(bm);
        } else {
            croppedSmall = f;
            pvSmall.setImageBitmap(bm);
        }
        refreshState();
    }

    private void refreshState() {
        boolean hasSmall = croppedSmall != null;
        boolean hasBig = croppedBig != null;
        packPush.setEnabled(hasSmall || hasBig);
        if (!hasSmall && !hasBig) {
            return;   // 保留连接状态文案
        }
        if (hasSmall && hasBig) {
            linkStatus.setText("小图 + 大图 就绪，可打包推送");
        } else if (hasSmall) {
            linkStatus.setText("只有小图，将只推 1.png");
        } else {
            linkStatus.setText("只有大图，将只推 2.png");
        }
    }

    // ==================== ③ 打包 / 推送 ====================

    private void packAndPush() {
        new Thread(() -> {
            try {
                File out = new File(requireContext().getCacheDir(), "wallpaper.zip");
                WallpaperSocketPusher.pack(croppedSmall, croppedBig, out);
                main.post(() -> {
                    staged = out;
                    fileLabel.setText(out.getName() + "  " + out.length() / 1024 + " KB\n"
                            + WallpaperSocketPusher.describe(out));
                    pushStaged();
                });
            } catch (Exception e) {
                main.post(() -> append("打包失败：" + e));
            }
        }, "wp-pack").start();
    }

    private void stageZip(Uri uri) {
        new Thread(() -> {
            try {
                File f = OtaSocketPusher.copyFromUri(requireContext(), uri);
                String desc = WallpaperSocketPusher.describe(f);
                main.post(() -> {
                    staged = f;
                    fileLabel.setText(f.getName() + "  " + f.length() / 1024 + " KB"
                            + (desc.isEmpty() ? "" : "\n" + desc));
                    send.setEnabled(true);
                });
            } catch (Exception e) {
                main.post(() -> append("拷贝失败：" + e));
            }
        }, "wp-stage").start();
    }

    private void pushStaged() {
        final File zip = staged;
        if (zip == null) {
            append("还没选包");
            return;
        }
        String err = WallpaperSocketPusher.validate(zip);
        if (err != null) {
            append(err);
            return;
        }
        String host = CarNet.resolveCarHost(WallpaperSocketPusher.PORT);
        OtaSocketPusher.setResolvedCarHost(host);
        String num = WallpaperSocketPusher.describe(zip).contains("2.png") ? "2" : "1";
        send.setEnabled(false);
        packPush.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        progressText.setText("推送中 0%");
        log.setText("");
        append("推送 " + zip.getName() + " → " + host + ":" + WallpaperSocketPusher.PORT
                + "（wallpaperNum=" + num + "）");
        WallpaperSocketPusher.push(zip, num, WallpaperSocketPusher.DEFAULT_DISPLAY_SET, false,
                new WallpaperSocketPusher.Callback() {
                    @Override
                    public void onLog(String line) {
                        append(line);
                    }

                    @Override
                    public void onProgress(int percent) {
                        main.post(() -> {
                            progress.setProgress(Math.max(0, Math.min(100, percent)));
                            progressText.setText("推送中 " + percent + "%");
                        });
                    }

                    @Override
                    public void onDone(boolean ok, String message) {
                        main.post(() -> {
                            progress.setProgress(100);
                            progressText.setText(ok ? "✔ 100%" : "✖ 已中断");
                            send.setEnabled(true);
                            packPush.setEnabled(croppedSmall != null || croppedBig != null);
                            append((ok ? "[完成] " : "[失败] ") + message);
                        });
                    }
                });
    }

    private void append(String line) {
        main.post(() -> {
            log.append(line + "\n");
            int n = log.getLineCount();
            if (n > 400) {
                log.setText("");
            }
        });
    }

    // ==================== 远程驱动（无头实测） ====================

    private void handleAuto(Intent intent) {
        if (intent == null || !"push".equals(intent.getStringExtra("auto"))) {
            return;
        }
        String path = intent.getStringExtra("file");
        final String num = intent.getStringExtra("num");
        final boolean dry = intent.getBooleanExtra("dry", false);
        if (path == null) {
            return;
        }
        new Thread(() -> {
            File f = new File(path);
            if (!f.isFile()) {
                main.post(() -> append("文件不存在：" + path));
                return;
            }
            main.post(() -> {
                staged = f;
                fileLabel.setText(f.getName() + "  " + f.length() / 1024 + " KB\n"
                        + WallpaperSocketPusher.describe(f));
                send.setEnabled(true);
            });
            WallpaperSocketPusher.push(f, num == null ? "1" : num,
                    WallpaperSocketPusher.DEFAULT_DISPLAY_SET, dry,
                    new WallpaperSocketPusher.Callback() {
                        @Override
                        public void onLog(String line) {
                            append(line);
                        }

                        @Override
                        public void onProgress(int percent) {
                            main.post(() -> {
                                progress.setProgress(percent);
                                progressText.setText("推送中 " + percent + "%");
                            });
                        }

                        @Override
                        public void onDone(boolean ok, String message) {
                            main.post(() -> append((ok ? "[完成] " : "[失败] ") + message));
                        }
                    });
        }, "wp-auto").start();
    }
}
