package com.github.speedhunter.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.speedhunter.R;
import com.github.speedhunter.core.Keys;
import com.github.speedhunter.customkey.CustomKeyFunction;
import com.github.speedhunter.customkey.CustomKeyOptions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;

/**
 * 「自定义按键」二级页 —— 从骑行记录页「自定义车控 · 自定义按键」进入。
 *
 * <p>业务逻辑自 RideFragment 平移：总开关 + 23 项功能码单选。
 * 选完只落盘 + 经 ConfigSender 广播给 hook；真正的「写车」发生在官方
 * App 的自定义键保存页（CustomKeySelectActivity#handleSave 前置改写），
 * 所以页内保留「去官方 App 再保存一次」的提示。</p>
 */
public final class CustomKeyActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private MaterialSwitch swCustomKey;
    private TextView tvCustomKeyHint;
    private TextView tvCustomKeyValue;
    private TextView tvCustomKeyCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_key);
        // 状态栏反色修复：与其他二级页同款 edge-to-edge（透明栏 + 图标明暗 + inset 补偿）
        EdgeToEdge.setup(this, findViewById(R.id.root_custom_key));

        prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        swCustomKey = findViewById(R.id.sw_custom_key);
        tvCustomKeyHint = findViewById(R.id.tv_custom_key_hint);
        tvCustomKeyValue = findViewById(R.id.tv_custom_key_value);
        tvCustomKeyCount = findViewById(R.id.tv_custom_key_count);

        tvCustomKeyCount.setText("按键功能 · 共 " + CustomKeyFunction.count() + " 项可选");
        swCustomKey.setChecked(prefs.getBoolean(Keys.KEY_CUSTOM_KEY_FORCE, false));
        updateHint();

        swCustomKey.setOnCheckedChangeListener(
                new MaterialSwitch.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton button, boolean isChecked) {
                        persist();
                        updateHint();
                    }
                });

        findViewById(R.id.row_custom_key_func).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker();
            }
        });
    }

    /** 当前目标功能码；未知值回落 7（与 hook 侧 CustomKeyOptions 保持一致）。 */
    private int customKeyTarget() {
        int value = prefs.getInt(Keys.KEY_CUSTOM_KEY_TARGET, CustomKeyOptions.TARGET);
        return CustomKeyFunction.isKnown(value) ? value : CustomKeyOptions.TARGET;
    }

    private void updateHint() {
        int target = customKeyTarget();
        String name = CustomKeyFunction.nameOf(target);
        tvCustomKeyValue.setText(name + " · " + target);
        tvCustomKeyHint.setText(swCustomKey.isChecked()
                ? "已开启：保存时改写为「" + name + "」"
                : "关闭：按键功能按官方 App 的选项为准");
    }

    private void showPicker() {
        final CustomKeyFunction.Entry[] all = CustomKeyFunction.all();
        String[] labels = new String[all.length];
        int checked = 0;
        int current = customKeyTarget();
        for (int i = 0; i < all.length; i++) {
            labels[i] = all[i].label();
            if (all[i].code == current) {
                checked = i;
            }
        }
        final Context ctx = this;
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("选择按键功能")
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        CustomKeyFunction.Entry picked = all[which];
                        prefs.edit()
                                .putInt(Keys.KEY_CUSTOM_KEY_TARGET, picked.code)
                                .commit();
                        persist();
                        updateHint();
                        Toast.makeText(ctx,
                                "已设为「" + picked.name + "」（" + picked.code + "）"
                                        + "\n请到官方 App 的自定义键页面再保存一次",
                                Toast.LENGTH_LONG).show();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void persist() {
        try {
            prefs.edit()
                    .putBoolean(Keys.KEY_CUSTOM_KEY_FORCE, swCustomKey.isChecked())
                    .commit();
        } catch (Throwable ignored) {
        }

        // 主通道：显式广播给 com.cfmoto 里 hook 注册的动态接收器（内存即时生效 + hook 落盘）
        com.github.speedhunter.core.ConfigSender.pushFromPrefs(this, prefs);
        makePrefsWorldReadable();
    }

    /** XSharedPreferences 兜底通道要求配置文件可被其他进程读（尽力而为）。 */
    private void makePrefsWorldReadable() {
        try {
            File dir = new File(getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.setReadable(true, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}