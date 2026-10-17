package com.github.zeehospeedhunter.ui;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.ota.WallpaperSocketPusher;
import com.google.android.material.button.MaterialButton;

public final class WallpaperActivity extends AppCompatActivity {
    private Uri selected;
    private TextView fileLabel;
    private TextView status;
    private TextView log;
    private ProgressBar progress;
    private MaterialButton send;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> pick = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                selected = uri;
                fileLabel.setText(uri == null ? "未选择文件" : uri.getLastPathSegment());
                send.setEnabled(uri != null);
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallpaper);
        BackGestureCompat.install(this);
        findViewById(R.id.wallpaper_back).setOnClickListener(v -> finish());
        fileLabel = findViewById(R.id.wallpaper_file);
        status = findViewById(R.id.wallpaper_status);
        log = findViewById(R.id.wallpaper_log);
        progress = findViewById(R.id.wallpaper_progress);
        send = findViewById(R.id.wallpaper_send);
        findViewById(R.id.wallpaper_pick).setOnClickListener(v -> pick.launch("application/zip"));
        send.setEnabled(false);
        send.setOnClickListener(v -> startPush());
    }

    private void startPush() {
        if (selected == null) return;
        send.setEnabled(false);
        findViewById(R.id.wallpaper_pick).setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("正在校验并连接壁纸通道…");
        log.setText("");
        WallpaperSocketPusher.push(this, selected, new WallpaperSocketPusher.Callback() {
            @Override public void onLog(String line) {
                main.post(() -> log.append(line + "\n"));
            }
            @Override public void onProgress(int percent) {
                main.post(() -> progress.setProgress(percent));
            }
            @Override public void onDone(boolean ok, String message) {
                main.post(() -> {
                    status.setText(message);
                    send.setEnabled(true);
                    findViewById(R.id.wallpaper_pick).setEnabled(true);
                });
            }
        });
    }
}
