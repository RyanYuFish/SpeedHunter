package com.github.zeehospeedhunter.ui;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;

import com.github.zeehospeedhunter.R;
import com.github.zeehospeedhunter.core.Keys;

/**
 * 骑行记录页（Material You）—— 底栏 5 个 tab 之一，与其余 4 个平级。
 *
 * <p>展示当前对 ZEEHO App 的挂载方式（网络层 / 视图层）并允许开关切换。
 * 开关写入本模块的 SharedPreferences，hook 进程经
 * {@code ConfigProvider}（导出的 ContentProvider）读取 —— 改完冷重启 ZEEHO App 生效。</p>
 */
public final class RideActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    private MaterialSwitch swWeb;
    private MaterialSwitch swG1;
    private MaterialSwitch swG2;
    private MaterialSwitch swView;
    private MaterialSwitch swRideFill;

    private TextView tvStatusDetail;
    private TextView tvStatusTitle;
    private ImageView icStatus;
    private TextView tvModeChip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ★ 只做窗口设置：给 view 挂 insets 必须等 setContentView 之后，
        //   早调 findViewById 只会拿到 null（标题飘到状态栏就是这么来的）
        EdgeToEdge.applyWindow(this);
        setContentView(R.layout.activity_ride);
        BackGestureCompat.install(this);
        applyInsets();

        prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);

        TextView version = findViewById(R.id.tv_version);
        version.setText("v" + versionName() + " · com.cfmoto（LSPosed 作用域需勾选）");

        swWeb = findViewById(R.id.sw_web);
        swG1 = findViewById(R.id.sw_g1);
        swG2 = findViewById(R.id.sw_g2);
        swView = findViewById(R.id.sw_view);
        swRideFill = findViewById(R.id.sw_ride_fill);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        tvStatusTitle = findViewById(R.id.tv_status_title);
        icStatus = findViewById(R.id.ic_status);
        tvModeChip = findViewById(R.id.tv_mode_chip);

        swWeb.setChecked(prefs.getBoolean(Keys.KEY_WEB_LAYER, true));
        swG1.setChecked(prefs.getBoolean(Keys.KEY_GATE_ANALYSE, true));
        swG2.setChecked(prefs.getBoolean(Keys.KEY_GATE_EVENT, true));
        swView.setChecked(prefs.getBoolean(Keys.KEY_VIEW_LAYER, false));
        swRideFill.setChecked(prefs.getBoolean(Keys.KEY_RIDE_FILL, true));

        // 首次打开就把默认值落盘 —— 否则 prefs 文件不存在，
        // hook 进程的 XSharedPreferences 兜底通道拿不到任何键
        if (!prefs.contains(Keys.KEY_WEB_LAYER)) {
            persist();
        }

        SwitchListener listener = new SwitchListener();
        swWeb.setOnCheckedChangeListener(listener);
        swG1.setOnCheckedChangeListener(listener);
        swG2.setOnCheckedChangeListener(listener);
        swView.setOnCheckedChangeListener(listener);
        swRideFill.setOnCheckedChangeListener(listener);

        // 压弯标定：8 个阈值实时可调，每项带「调它会发生什么」说明
        findViewById(R.id.btn_tune).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            RideActivity.this, TuneActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(RideActivity.this, "打不开标定台：" + t,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        setupTabs();
        updateStatus();
    }

    // ==================== 底栏 tab ====================

    /**
     * 5 个 tab：骑行记录 / OTA 推送 / 壁纸推送 / 仪表投屏 / 车辆诊断。
     * 装配与跳转统一在 {@link NavBar}（底栏常驻 + 同级切换）。
     */
    private void setupTabs() {
        NavBar.install(this, R.id.nav_ride, R.id.ride_scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 底栏选中态拉回本页（切 tab 时上一页留下的选中态会带回来）
        NavBar.reselect(this, R.id.nav_ride);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /**
     * 沉浸式 insets：顶栏吃状态栏高度（标题不再飘到状态栏上）。
     * 底栏与内容区的避让由 {@link NavBar#install} 统一处理。
     *
     * <p>★ 必须在 {@code setContentView} <b>之后</b>调 —— 之前 {@code findViewById}
     * 只拿得到 null，insets 谁都没挂，标题就压在状态栏上了。</p>
     */
    private void applyInsets() {
        EdgeToEdge.applyTop(findViewById(R.id.app_bar));
    }


    /**
     * 状态卡三态：
     * <ul>
     *   <li><b>已激活</b>（网络层 / 视图层任一开）—— 标题「已激活」，第二行写实现方式，
     *       图标 ✅（{@code ic_check_circle}）；</li>
     *   <li><b>未激活</b>（两者全关）—— 标题「未激活」，图标 ✖（{@code ic_clear}）。</li>
     * </ul>
     * 修复点：以前全关时标题仍是固定的「当前挂载方式」且图标恒为 ✅，看起来像已经生效。
     */
    private void updateStatus() {
        boolean web = swWeb.isChecked();
        boolean view = swView.isChecked();
        boolean active = web || view;

        String detail;
        String chip;
        if (web && view) {
            detail = "网络层 + 视图层兜底（叠加）——能力位改写 + 控件强制点亮";
            chip = "WEB+VIEW";
        } else if (web) {
            detail = "网络层（推荐）——改写车型能力位，App 自己显示骑行分析与四项指标";
            chip = "WEB";
        } else if (view) {
            detail = "视图层兜底——直接把控件沿祖先链点亮（旧方案，App 换判定源时才用）";
            chip = "VIEW";
        } else {
            detail = "未挂载——不修改 ZEEHO App";
            chip = "OFF";
        }
        tvStatusTitle.setText(active ? "已激活" : "未激活");
        tvStatusDetail.setText(detail);
        tvModeChip.setText(chip);

        icStatus.setImageResource(active ? R.drawable.ic_check_circle : R.drawable.ic_clear);

        // chip 底色跟状态走：激活用 colorPrimary，关闭用 onSurfaceVariant
        int color = ContextCompat.getColor(this,
                active ? R.color.chip_active : R.color.chip_off);
        tvModeChip.getBackground().mutate().setTint(color);
        tvModeChip.setTextColor(Color.WHITE);

        // G1/G2 依赖网络层，关掉网络层时置灰
        boolean gatesEnabled = web;
        swG1.setEnabled(gatesEnabled);
        swG2.setEnabled(gatesEnabled);
        float alpha = gatesEnabled ? 1f : 0.45f;
        swG1.setAlpha(alpha);
        swG2.setAlpha(alpha);
    }

    private void persist() {
        try {
            prefs.edit()
                    .putBoolean(Keys.KEY_WEB_LAYER, swWeb.isChecked())
                    .putBoolean(Keys.KEY_GATE_ANALYSE, swG1.isChecked())
                    .putBoolean(Keys.KEY_GATE_EVENT, swG2.isChecked())
                    .putBoolean(Keys.KEY_VIEW_LAYER, swView.isChecked())
                    .putBoolean(Keys.KEY_RIDE_FILL, swRideFill.isChecked())
                    .putBoolean(Keys.KEY_RIDE_FULLSCAN, true)
                    .commit();
        } catch (Throwable ignored) {
            // prefs 可能被 LSPosed 重定向或只读 —— 真正的通道是下面的广播
        }

        // 主通道：显式广播给 com.cfmoto 里 hook 注册的动态接收器
        // （ZEEHO App 运行中 → 内存即时生效 + hook 自己落盘；冷重启后从落盘文件恢复）
        try {
            android.content.Intent intent = new android.content.Intent(Keys.ACTION_CONFIG);
            intent.setPackage("com.cfmoto");
            intent.putExtra(Keys.KEY_WEB_LAYER, swWeb.isChecked());
            intent.putExtra(Keys.KEY_GATE_ANALYSE, swG1.isChecked());
            intent.putExtra(Keys.KEY_GATE_EVENT, swG2.isChecked());
            intent.putExtra(Keys.KEY_VIEW_LAYER, swView.isChecked());
            intent.putExtra(Keys.KEY_RIDE_FILL, swRideFill.isChecked());
            intent.putExtra(Keys.KEY_RIDE_FULLSCAN, true);
            intent.setPackage(Keys.TARGET_PKG);   // ★ 见 Keys#TARGET_PKG：不加这行 Android 8+ 会拦掉
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        makePrefsWorldReadable();
    }

    /**
     * XSharedPreferences 兜底通道要求配置文件可被其他进程读。
     * 主通道是 ContentProvider，不依赖这里；这一步是尽力而为（失败不影响主链路）。
     */
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

    private final class SwitchListener implements MaterialSwitch.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(android.widget.CompoundButton button, boolean isChecked) {
            // 关掉网络层时保持 G1/G2 视觉跟随（值保留，重新打开即恢复）
            persist();
            updateStatus();
        }
    }
}
