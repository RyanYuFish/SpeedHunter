package com.github.speedhunter.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.github.speedhunter.R;
import com.github.speedhunter.core.Keys;
import com.github.speedhunter.customkey.CustomKeyFunction;

/**
 * 骑行记录页（Material You）—— 底栏 5 个 tab 之一，与其余 4 个平级。
 *
 * <p>展示当前对官方 App 的挂载方式（网络层 / 视图层）并允许开关切换。
 * 开关写入本模块的 SharedPreferences，hook 进程经
 * {@code ConfigProvider}（导出的 ContentProvider）读取 —— 改完冷重启官方 App 生效。</p>
 */
public final class RideFragment extends BaseTabFragment {

    @Override
    protected int getLayoutId() {
        return R.layout.activity_ride;
    }

    private SharedPreferences prefs;

    private MaterialSwitch swWeb;
    private MaterialSwitch swG1;
    private MaterialSwitch swG2;
    private MaterialSwitch swView;
    private MaterialSwitch swRideFill;
    private TextView tvCustomKeySummary;
    private MaterialSwitch swNitro;
    private TextView tvNitroHint;
    private MaterialSwitch swAbs;
    private TextView tvAbsHint;
    private MaterialSwitch swVehicleArt;
    private TextView tvVehicleArtHint;
    private TextView tvVehicleArtModelValue;
    private TextView tvVehicleArtValue;
    private TextView tvVehicleArtColorValue;

    private TextView tvStatusDetail;
    private TextView tvStatusTitle;
    private ImageView icStatus;
    private TextView tvModeChip;
    private com.google.android.material.card.MaterialCardView cardStatus;

    // ---- 模块挂载（作用域）检测 ----
    // 只反映「Xposed 管理器是否激活了 com.cfmoto 作用域」这一<b>配置事实</b>，
    // 与官方 App 当前是否在前台 / 被后台清理无关。机制：发 PING 给 com.cfmoto 里的
    // hook，能回 PONG 即证明「模块确实被注入过 com.cfmoto」(= 作用域已激活)。
    // 一旦确认就把 hookMountedEver 持久化 —— 之后即使官方 App 被后台清掉、PING 收不到回执，
    // 也不再翻回「未挂载」。只有「从未确认过」(模块未启用 / 未勾作用域) 才显示未挂载。
    private static final String PREF_MOUNTED = "hook_mounted_ever";
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean hookAlive;
    private boolean pongReceiverRegistered;

    private final Runnable pongTimeoutTask = new Runnable() {
        @Override
        public void run() {
            // 超时没等到 PONG：从未确认过 ⇒ 落回「未挂载」；已确认过 ⇒ 保持挂载（不翻车）
            updateStatus();
        }
    };

    private final BroadcastReceiver hookPongReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            hookAlive = true;
            prefs.edit().putBoolean(PREF_MOUNTED, true).apply();   // 持久记下：作用域已激活
            uiHandler.removeCallbacks(pongTimeoutTask);
            updateStatus();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // prefs 与视图无关，提前拿好：宿主可能在视图建好前就触发 onPageShown（发 PING）。
        prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());

        // 车模目录预取（v4.8.1）：进页即读盘/后台拉取，点「3D 车模」时零等待
        try {
            com.github.speedhunter.net.ModelCatalog.load(requireContext(),
                    new com.github.speedhunter.net.ModelCatalog.Callback() {
                        @Override
                        public void onCatalog(
                                java.util.List<com.github.speedhunter.net.ModelCatalog.Entry> entries,
                                String error) {
                            // 仅预热缓存，无需 UI
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // ★ 只做窗口设置：给 view 挂 insets 必须等 onViewCreated 之后，
        //   早调 findViewById 只会拿到 null（标题飘到状态栏就是这么来的）
        EdgeToEdge.applyTop(view);
        BackGestureCompat.install(requireActivity());

        if (prefs == null) {
            prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
        }

        TextView version = view.findViewById(R.id.tv_version);
        version.setText("v" + versionName() + " · com.cfmoto（LSPosed 作用域需勾选）");

        swWeb = view.findViewById(R.id.sw_web);
        swG1 = view.findViewById(R.id.sw_g1);
        swG2 = view.findViewById(R.id.sw_g2);
        swView = view.findViewById(R.id.sw_view);
        swRideFill = view.findViewById(R.id.sw_ride_fill);
        tvCustomKeySummary = view.findViewById(R.id.tv_custom_key_summary);
        swNitro = view.findViewById(R.id.sw_nitro);
        tvNitroHint = view.findViewById(R.id.tv_nitro_hint);
        swAbs = view.findViewById(R.id.sw_abs);
        tvAbsHint = view.findViewById(R.id.tv_abs_hint);
        swVehicleArt = view.findViewById(R.id.sw_vehicle_art);
        tvVehicleArtHint = view.findViewById(R.id.tv_vehicle_art_hint);
        tvVehicleArtModelValue = view.findViewById(R.id.tv_vehicle_art_model_value);
        tvVehicleArtValue = view.findViewById(R.id.tv_vehicle_art_value);
        tvVehicleArtColorValue = view.findViewById(R.id.tv_vehicle_art_color_value);
        view.findViewById(R.id.row_custom_key_entry).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new android.content.Intent(
                                    requireActivity(), CustomKeyActivity.class));
                        } catch (Throwable t) {
                            Toast.makeText(requireContext(), "打不开自定义按键页：" + t,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
        tvStatusDetail = view.findViewById(R.id.tv_status_detail);
        tvStatusTitle = view.findViewById(R.id.tv_status_title);
        icStatus = view.findViewById(R.id.ic_status);
        tvModeChip = view.findViewById(R.id.tv_mode_chip);
        cardStatus = view.findViewById(R.id.card_status);

        swWeb.setChecked(prefs.getBoolean(Keys.KEY_WEB_LAYER, true));
        swG1.setChecked(prefs.getBoolean(Keys.KEY_GATE_ANALYSE, true));
        swG2.setChecked(prefs.getBoolean(Keys.KEY_GATE_EVENT, true));
        swView.setChecked(prefs.getBoolean(Keys.KEY_VIEW_LAYER, false));
        swRideFill.setChecked(prefs.getBoolean(Keys.KEY_RIDE_FILL, true));
        swVehicleArt.setChecked(prefs.getBoolean(Keys.KEY_VEHICLE_ART, false));
        updateVehicleArtHint();
        view.findViewById(R.id.row_vehicle_art_model).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showModelPicker();
                    }
                });
        view.findViewById(R.id.row_vehicle_art_edit).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        show2dPicker();
                    }
                });
        view.findViewById(R.id.row_vehicle_art_color).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showColorPicker();
                    }
                });
        swNitro.setChecked(prefs.getBoolean(Keys.KEY_NITRO_UI, false));
        swAbs.setChecked(prefs.getBoolean(Keys.KEY_ABS_UI, false));
        updateCustomKeySummary();
        updateNitroHint();
        updateAbsHint();

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
        swNitro.setOnCheckedChangeListener(listener);
        swAbs.setOnCheckedChangeListener(listener);
        swVehicleArt.setOnCheckedChangeListener(listener);

        // 账号引导登录：token + 账号 ID 外置录入、签到与积分任务
        view.findViewById(R.id.btn_account).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            requireActivity(), AccountActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(requireContext(), "打不开账号页：" + t,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        // 压弯标定：8 个阈值实时可调，每项带「调它会发生什么」说明
        view.findViewById(R.id.btn_tune).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            requireActivity(), TuneActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(requireContext(), "打不开标定台：" + t,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        // 已确认过作用域 ⇒ 直接按「已挂载」渲染（不被后台清理官方 App 影响）
        hookAlive = prefs.getBoolean(PREF_MOUNTED, false);
        updateStatus();
    }

    @Override
    public void onPageShown() {
        // 每次回到本页都发一次 PING：确认过作用域就保持「已挂载」，未确认过则尝试确认
        registerHookPong();
        pingHook();
    }

    @Override
    public void onPageHidden() {
        unregisterHookPong();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterHookPong();
    }

    private void unregisterHookPong() {
        if (pongReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(hookPongReceiver);
            } catch (Throwable ignored) {
            }
            pongReceiverRegistered = false;
        }
        uiHandler.removeCallbacks(pongTimeoutTask);
    }

    private void registerHookPong() {
        if (pongReceiverRegistered) {
            return;
        }
        try {
            IntentFilter f = new IntentFilter(Keys.ACTION_HOOK_PONG);
            // 发送方是 com.cfmoto（另一个 App）⇒ 必须 EXPORTED，与 TuneActivity 收
            // ACTION_STORE_STATE 同一套规矩
            if (Build.VERSION.SDK_INT >= 33) {
                requireContext().registerReceiver(hookPongReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                requireContext().registerReceiver(hookPongReceiver, f);
            }
            pongReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 确认模块是否注入过 com.cfmoto（即作用域已激活）：发 {@link Keys#ACTION_HOOK_PING}
     * 给 com.cfmoto 里的 hook，能回 {@link Keys#ACTION_HOOK_PONG} 就把挂载标记持久化。
     * 注意：超时收不到只把<b>从未确认过</b>的判成「未挂载」；已确认过则保持挂载——
     * 官方 App 被后台清理时 PING 自然收不到回执，不能据此翻成未挂载（那是错误的）。
     */
    private void pingHook() {
        try {
            Intent ping = new Intent(Keys.ACTION_HOOK_PING);
            ping.setPackage(Keys.TARGET_PKG);
            requireContext().sendBroadcast(ping);
        } catch (Throwable ignored) {
        }
        hookAlive = prefs.getBoolean(PREF_MOUNTED, false);   // 乐观沿用持久标记，不主动翻成未挂载
        uiHandler.removeCallbacks(pongTimeoutTask);
        uiHandler.postDelayed(pongTimeoutTask, 800);
    }

    private String versionName() {
        try {
            return requireContext().getPackageManager().getPackageInfo(
                    requireContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /**
     * 状态卡三态（SukiSU 式大字状态）：
     * <ul>
     *   <li><b>未挂载</b> —— PING 超时没等到 PONG：hook 没在 com.cfmoto 里跑。</li>
     *   <li><b>未修改</b> —— 已挂载但四个开关全关：不碰官方 App，文案按用户要求叫「未修改」；</li>
     *   <li><b>已激活</b> —— 已挂载且任一开关打开，第二行写实现方式。</li>
     * </ul>
     */
    private void updateStatus() {
        if (swWeb == null || cardStatus == null) {
            return;   // 视图还没建好（宿主可能在 onViewCreated 之前就发了 PING）
        }
        boolean web = swWeb.isChecked();
        boolean view = swView.isChecked();
        boolean active = web || view;

        String title;
        String detail;
        String chip;
        boolean mounted = hookAlive;

        if (!mounted) {
            title = "未挂载";
            chip = "未挂载";
            detail = "本模块还没有挂入过官方 App。常见原因：Xposed 管理器没有启用本模块，"
                    + "或没有勾选 ZEEHO（com.cfmoto）作用域。\n"
                    + "① 打开 Xposed 管理器 → 模块 → 启用 Speed Hunter；\n"
                    + "② 在模块作用域里勾选 ZEEHO（com.cfmoto）；\n"
                    + "③ 打开一次官方 App（让模块随它一起加载），回到本页即显示「已激活」。";
        } else if (active) {
            title = "已激活";
            if (web && view) {
                detail = "网络层 + 视图层（叠加）——能力位改写 + 控件强制点亮";
                chip = "WEB+VIEW";
            } else if (web) {
                detail = "网络层（推荐）——改写车型能力位，App 自己显示骑行分析与四项指标";
                chip = "WEB";
            } else {
                detail = "视图层——直接把控件沿祖先链点亮（旧方案，App 换判定源时才用）";
                chip = "VIEW";
            }
        } else {
            title = "未修改";
            chip = "未修改";
            detail = "全部开关已关闭——不修改官方 App，一切保持原厂行为。";
        }

        tvStatusTitle.setText(title);
        tvStatusDetail.setText(detail);
        tvModeChip.setText(chip);

        // 三态全部走 Material You tonal spot 取色：同一套 Tonal Spot 方案的容器色角色，
        // 不混用硬编码色。已激活=primary 容器、未修改=secondary 容器、未挂载=error 容器。
        int bgAttr, fgAttr, chipTintAttr;
        if (!mounted) {
            bgAttr = com.google.android.material.R.attr.colorErrorContainer;
            fgAttr = com.google.android.material.R.attr.colorOnErrorContainer;
            chipTintAttr = com.google.android.material.R.attr.colorError;
        } else if (active) {
            bgAttr = com.google.android.material.R.attr.colorPrimaryContainer;
            fgAttr = com.google.android.material.R.attr.colorOnPrimaryContainer;
            chipTintAttr = com.google.android.material.R.attr.colorPrimary;
        } else {
            bgAttr = com.google.android.material.R.attr.colorSecondaryContainer;
            fgAttr = com.google.android.material.R.attr.colorOnSecondaryContainer;
            chipTintAttr = com.google.android.material.R.attr.colorSecondary;
        }
        int bg = com.google.android.material.color.MaterialColors.getColor(cardStatus, bgAttr, Color.WHITE);
        int fg = com.google.android.material.color.MaterialColors.getColor(cardStatus, fgAttr, Color.BLACK);
        int chipTint = com.google.android.material.color.MaterialColors.getColor(cardStatus, chipTintAttr, Color.BLACK);
        cardStatus.setCardBackgroundColor(bg);
        tvStatusTitle.setTextColor(fg);
        tvStatusDetail.setTextColor(fg);
        icStatus.setColorFilter(fg);
        // ★ 未挂载：卡片本身已是 error 容器色调（见上方三态取色），
        //   配 Material Symbols 的 error 图标，语义与色调一致。
        if (mounted) {
            icStatus.setVisibility(View.VISIBLE);
            icStatus.setImageResource(active ? R.drawable.ic_check_circle : R.drawable.ic_info);
        } else {
            icStatus.setVisibility(View.VISIBLE);
            icStatus.setImageResource(R.drawable.ic_error);
        }

        // chip 徽章：跟随当前 tone（不再是写死的 chip_active / chip_off）
        tvModeChip.getBackground().mutate().setTint(chipTint);
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
                    // KEY_CUSTOM_KEY_FORCE 由 CustomKeyActivity 持有并写入（本页已无该开关）
                    .putBoolean(Keys.KEY_NITRO_UI, swNitro != null && swNitro.isChecked())
                    .putBoolean(Keys.KEY_ABS_UI, swAbs != null && swAbs.isChecked())
                    .putBoolean(Keys.KEY_VEHICLE_ART, swVehicleArt != null && swVehicleArt.isChecked())
                    .putBoolean(Keys.KEY_RIDE_FULLSCAN, true)
                    .commit();
        } catch (Throwable ignored) {
            // prefs 可能被 LSPosed 重定向或只读 —— 真正的通道是下面的广播
        }

        // 主通道：经共享 ConfigSender 把当前 prefs 显式广播给 com.cfmoto 里 hook 注册的动态接收器
        // （官方 App 运行中 → 内存即时生效 + hook 自己落盘；冷重启后从落盘文件恢复）
        com.github.speedhunter.core.ConfigSender.pushFromPrefs(requireContext(), prefs);
        makePrefsWorldReadable();
    }

    /**
     * XSharedPreferences 兜底通道要求配置文件可被其他进程读。
     * 主通道是 ContentProvider，不依赖这里；这一步是尽力而为（失败不影响主链路）。
     */
    private void makePrefsWorldReadable() {
        try {
            File dir = new File(requireContext().getApplicationInfo().dataDir, "shared_prefs");
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
            updateNitroHint();
            updateAbsHint();
            updateVehicleArtHint();
        }
    }

    /**
     * 自定义键卡片的状态行与当前功能值：
     * <ul>
     *   <li>开 → 「已开启：保存时改写为「名称」（码）」</li>
     *   <li>关 → 「关闭：自定义键功能按照 ZEEHO App 的选项为准」</li>
     * </ul>
     */
    private void updateCustomKeySummary() {
        if (tvCustomKeySummary == null) {
            return;
        }
        boolean on = prefs != null && prefs.getBoolean(Keys.KEY_CUSTOM_KEY_FORCE, false);
        int target = prefs == null
                ? com.github.speedhunter.customkey.CustomKeyOptions.TARGET
                : prefs.getInt(Keys.KEY_CUSTOM_KEY_TARGET,
                        com.github.speedhunter.customkey.CustomKeyOptions.TARGET);
        String name = CustomKeyFunction.nameOf(
                CustomKeyFunction.isKnown(target) ? target
                        : com.github.speedhunter.customkey.CustomKeyOptions.TARGET);
        tvCustomKeySummary.setText(on ? "已开启 · " + name : "未开启 · " + name);
    }

    /**
     * 氮气卡片状态行。
     */
    private void updateNitroHint() {
        if (tvNitroHint == null || swNitro == null) {
            return;
        }
        tvNitroHint.setText(swNitro.isChecked()
                ? "已开启：骑行偏好中显示氮气加速开关"
                : "关闭：云端数据保持原样");
    }

    /** ABS 开关卡片状态行。 */
    private void updateAbsHint() {
        if (tvAbsHint == null || swAbs == null) {
            return;
        }
        tvAbsHint.setText(swAbs.isChecked()
                ? "已开启：设置中显示 ABS 开关"
                : "关闭：云端数据保持原样");
    }

    /** 车模替换卡片状态行 + 三行当前值摘要（3D / 2D / 配色）。 */
    private void updateVehicleArtHint() {
        if (tvVehicleArtHint == null || swVehicleArt == null) {
            return;
        }
        boolean on = swVehicleArt.isChecked();
        String pic = prefs == null ? "" : prefs.getString(Keys.KEY_VEHICLE_ART_PIC, "");
        String pic3d = prefs == null ? "" : prefs.getString(Keys.KEY_VEHICLE_ART_PIC3D, "");
        String rgb = prefs == null ? "" : prefs.getString(Keys.KEY_VEHICLE_ART_RGB, "");
        String modelName = prefs == null ? "" : prefs.getString(Keys.KEY_VEHICLE_ART_MODEL_NAME, "");
        int color = prefs == null ? -1 : prefs.getInt(Keys.KEY_VEHICLE_ART_COLOR, -1);
        tvVehicleArtHint.setText(on
                ? "已开启：主页车辆按下方选择显示（重启官方 App 生效）"
                : "关闭：主页车辆保持原样");
        if (tvVehicleArtModelValue != null) {
            if (pic3d.isEmpty()) {
                tvVehicleArtModelValue.setText("未设置（跟随云端，点此选择）");
            } else if (!modelName.isEmpty()) {
                tvVehicleArtModelValue.setText(modelName + "（目录模型，点此重选）");
            } else {
                tvVehicleArtModelValue.setText("自定义：" + tail(pic3d) + "（点此重选）");
            }
        }
        if (tvVehicleArtValue != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(pic.isEmpty() ? "2D未设" : "2D√ " + tail(pic));
            if (!rgb.isEmpty()) {
                sb.append(" · 主题色 ").append(rgb);
            }
            tvVehicleArtValue.setText(sb.toString());
        }
        if (tvVehicleArtColorValue != null) {
            tvVehicleArtColorValue.setText(color < 0
                    ? "关闭（按模型默认色）"
                    : "配色 " + color + "（模型加载后应用，越界自动忽略）");
        }
    }

    private static String tail(String url) {
        if (url == null) return "";
        int slash = url.lastIndexOf('/');
        String t = slash >= 0 ? url.substring(slash + 1) : url;
        return t.length() > 34 ? t.substring(0, 34) + "…" : t;
    }

    /**
     * 3D 车模选择器：目录列表（lswlc33/zeeho-3d-models，多 CDN 回退）+
     * 跟随云端 + 自定义 URL 三个入口。
     *
     * <p>目录拉取在后台线程；失败时仍弹出（只有跟随云端 / 自定义 URL 两项），
     * 不阻塞其他卡片功能。选中目录模型 = URL+名称落盘，经 ConfigSender 广播
     * 给 hook（hook 侧只认 URL —— vehiclePic3dUrl 全树替换）。</p>
     */
    private void showModelPicker() {
        if (prefs == null) {
            return;
        }
        final Context ctx = requireContext();
        // 先弹层（v4.8.1）：跟随云端/本地/自定义 URL 立即可选；目录命中缓存同步就绪，
        // 首次联网拉取则占位一行，回调后原位刷新 —— 不再「点了等好几秒才弹」。占位行不落盘。
        final List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"跟随云端", "不替换 3D 车模"});
        rows.add(new String[]{"本地 glb…", "上传到官方 App 进程，离线可用"});
        rows.add(new String[]{"自定义 glb URL…", "直接填写远程地址"});
        rows.add(new String[]{"目录加载中…", "首次联网获取，稍后自动刷新"});
        final TwoLineAdapter adapter = new TwoLineAdapter(ctx, rows, 0);
        final BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        @SuppressWarnings("unchecked")
        final java.util.List<com.github.speedhunter.net.ModelCatalog.Entry>[] box =
                new java.util.List[1];
        com.github.speedhunter.net.ModelCatalog.load(requireContext(),
                new com.github.speedhunter.net.ModelCatalog.Callback() {
                    @Override
                    public void onCatalog(
                            final java.util.List<com.github.speedhunter.net.ModelCatalog.Entry> entries,
                            String error) {
                        if (!isAdded()) {
                            return;
                        }
                        box[0] = entries;
                        String head = prefs.getString(Keys.KEY_VEHICLE_ART_PIC3D, "");
                        String savedName = prefs.getString(Keys.KEY_VEHICLE_ART_MODEL_NAME, "");
                        final boolean hasCatalog = entries != null && !entries.isEmpty();
                        int checked = 0;
                        if (!head.isEmpty()) {
                            checked = savedName.isEmpty() ? 2 : -1;
                            if (checked == -1 && hasCatalog) {
                                for (int i = 0; i < entries.size(); i++) {
                                    if (entries.get(i).url.equals(head)) {
                                        checked = i + 3;
                                        break;
                                    }
                                }
                            }
                            if (checked == -1 && head.startsWith("http://127.0.0.1:")) {
                                checked = 1;   // 本地上传的 URL
                            }
                        }
                        while (rows.size() > 3) {
                            rows.remove(rows.size() - 1);
                        }
                        if (hasCatalog) {
                            for (com.github.speedhunter.net.ModelCatalog.Entry e : entries) {
                                rows.add(new String[]{e.name, e.note + " · "
                                        + String.format(java.util.Locale.US, "%.1f",
                                                e.sizeBytes / 1024f / 1024f) + " MB"});
                            }
                        } else {
                            rows.add(new String[]{"目录暂不可用",
                                    error == null ? "拉取失败，可稍后重试" : error});
                        }
                        adapter.setChecked(Math.max(checked, 0));
                    }
                });
        showListSheet(ctx, dialog, "3D 车模", adapter,
                (android.widget.AdapterView<?> parent, View v, int which, long id) -> {
                    java.util.List<com.github.speedhunter.net.ModelCatalog.Entry> entries = box[0];
                    boolean hasCatalog = entries != null && !entries.isEmpty();
                    if (which == 0) {
                        prefs.edit()
                                .remove(Keys.KEY_VEHICLE_ART_PIC3D)
                                .remove(Keys.KEY_VEHICLE_ART_MODEL_NAME)
                                .commit();
                    } else if (which == 1) {
                        dialog.dismiss();
                        pickLocalArt(true);
                        return;
                    } else if (which == 2) {
                        dialog.dismiss();
                        showCustomModelUrlEditor();
                        return;
                    } else if (hasCatalog && which >= 3) {
                        com.github.speedhunter.net.ModelCatalog.Entry e = entries.get(which - 3);
                        prefs.edit()
                                .putString(Keys.KEY_VEHICLE_ART_PIC3D, e.url)
                                .putString(Keys.KEY_VEHICLE_ART_MODEL_NAME, e.name)
                                .commit();
                    } else {
                        return;   // 占位行不响应
                    }
                    dialog.dismiss();
                    persist();
                    updateVehicleArtHint();
                });
    }

    /** M3 双行单选列表 adapter：标题 + 次行说明，选中行标题用主题色；支持异步原位刷新。 */
    private static final class TwoLineAdapter extends android.widget.BaseAdapter {
        private final Context ctx;
        private final List<String[]> rows;
        private int checked;

        TwoLineAdapter(Context ctx, List<String[]> rows, int checked) {
            this.ctx = ctx;
            this.rows = rows;
            this.checked = checked;
        }

        void setChecked(int value) {
            checked = value;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public String[] getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                int padH = (int) (20 * ctx.getResources().getDisplayMetrics().density);
                int padV = (int) (12 * ctx.getResources().getDisplayMetrics().density);
                row.setPadding(padH, padV, padH, padV);
                // Material 触摸波纹（ListView 按下态自动触发）
                try {
                    android.content.res.TypedArray ta = ctx.obtainStyledAttributes(
                            new int[]{android.R.attr.selectableItemBackground});
                    int res = ta.getResourceId(0, 0);
                    ta.recycle();
                    if (res != 0) {
                        row.setBackgroundResource(res);
                    }
                } catch (Throwable ignored) {
                }
                TextView title = new TextView(ctx);
                title.setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
                TextView sub = new TextView(ctx);
                sub.setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                row.addView(title);
                row.addView(sub);
            }
            TextView title = (TextView) row.getChildAt(0);
            TextView sub = (TextView) row.getChildAt(1);
            String[] item = getItem(position);
            title.setText(position == checked ? item[0] + "  ✓" : item[0]);
            int color = position == checked
                    ? com.google.android.material.color.MaterialColors.getColor(
                            row, com.google.android.material.R.attr.colorPrimary,
                            android.graphics.Color.BLUE)
                    : com.google.android.material.color.MaterialColors.getColor(
                            row, com.google.android.material.R.attr.colorOnSurface,
                            android.graphics.Color.BLACK);
            title.setTextColor(color);
            sub.setText(item[1]);
            sub.setVisibility(item[1] == null || item[1].length() == 0
                    ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    /** M3 底部弹层列表（v4.8.1 选单统一入口）：拖拽把手 + 标题 + 双行列表。 */
    private void showListSheet(Context ctx, BottomSheetDialog dialog, String title,
            TwoLineAdapter adapter,
            android.widget.AdapterView.OnItemClickListener click) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        View handle = new View(ctx);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams((int) (28 * d), (int) (4 * d));
        hp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        hp.topMargin = (int) (14 * d);
        handle.setLayoutParams(hp);
        handle.setBackgroundResource(R.drawable.bg_drag_handle);
        root.addView(handle);
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        tvTitle.setPadding((int) (24 * d), (int) (10 * d), (int) (24 * d), (int) (6 * d));
        root.addView(tvTitle);
        ListView list = new ListView(ctx);
        list.setDividerHeight(0);
        list.setPadding(0, (int) (4 * d), 0, (int) (12 * d));
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener(click);
        root.addView(list, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(root);
        dialog.show();
    }

    /** 自定义 3D glb URL 录入（目录外的模型走这里）。 */
    private void showCustomModelUrlEditor() {
        final Context ctx = requireContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText etUrl = artField(ctx,
                prefs.getString(Keys.KEY_VEHICLE_ART_PIC3D, ""),
                "3D 车模 URL（glb，替换 vehiclePic3dUrl；留空 = 跟随云端）");
        box.addView(etUrl);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("自定义 3D 车模")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = etUrl.getText().toString().trim();
                        prefs.edit()
                                .putString(Keys.KEY_VEHICLE_ART_PIC3D, url)
                                .putString(Keys.KEY_VEHICLE_ART_MODEL_NAME, "")
                                .commit();
                        persist();
                        updateVehicleArtHint();
                        Toast.makeText(ctx, "已保存。重启官方 App 后主页生效",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 车漆配色选择器：关闭 + 0..9。
     *
     * <p>有效范围 = 运行时 carColorMaterials.size()（车型相关，静态不可知）；
     * hook 侧有界保护越界只记日志。首次开启建议看 hook 日志确认槽位数。</p>
     */
    private void showColorPicker() {
        if (prefs == null) {
            return;
        }
        final Context ctx = requireContext();
        final int current = prefs.getInt(Keys.KEY_VEHICLE_ART_COLOR, -1);
        final List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"关闭（模型默认色）", ""});
        for (int i = 0; i < 10; i++) {
            rows.add(new String[]{"配色 " + i, ""});
        }
        final BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        showListSheet(ctx, dialog, "车漆配色（索引，超出槽位自动忽略）",
                new TwoLineAdapter(ctx, rows, current + 1),
                (android.widget.AdapterView<?> parent, View v, int which, long id) -> {
                    prefs.edit()
                            .putInt(Keys.KEY_VEHICLE_ART_COLOR, which - 1)
                            .commit();
                    dialog.dismiss();
                    persist();
                    updateVehicleArtHint();
                });
    }

    /**
     * 2D 车图选择器（v4.7.69）：跟随云端 / 官方素材目录 / 本地图片 / 自定义 URL。
     *
     * <p>官方目录 = {@link com.github.speedhunter.net.OfficialArt} 里实捕的 3 条；
     * 选官方素材同时写入配套主题色 #131415。vehicleScalePicUrl 自动跟随
     * （保留原 OSS resize 参数）。</p>
     */
    private void show2dPicker() {
        if (prefs == null) {
            return;
        }
        final Context ctx = requireContext();
        String pic = prefs.getString(Keys.KEY_VEHICLE_ART_PIC, "");
        final List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"跟随云端", "不替换 2D 车图与主题色"});
        for (com.github.speedhunter.net.OfficialArt.Entry e :
                com.github.speedhunter.net.OfficialArt.ENTRIES) {
            rows.add(new String[]{e.label, e.note + " · " + e.rgb});
        }
        final int officialBase = 1;
        final int localRow = officialBase + com.github.speedhunter.net.OfficialArt.ENTRIES.length;
        rows.add(new String[]{"本地图片…", "上传到官方 App 进程，离线可用"});
        rows.add(new String[]{"自定义 URL…", "手动填 2D 图 + 主题色"});
        final int customRow = localRow + 1;
        int checked = 0;
        if (!pic.isEmpty()) {
            checked = customRow;
            for (int i = 0; i < com.github.speedhunter.net.OfficialArt.ENTRIES.length; i++) {
                if (com.github.speedhunter.net.OfficialArt.ENTRIES[i].pic.equals(pic)) {
                    checked = officialBase + i;
                    break;
                }
            }
            if (checked == customRow && pic.startsWith("http://127.0.0.1:")) {
                checked = localRow;
            }
        }
        final int checkedFinal = checked;
        final BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        showListSheet(ctx, dialog, "2D 车图", new TwoLineAdapter(ctx, rows, checkedFinal),
                (android.widget.AdapterView<?> parent, View v, int which, long id) -> {
                    if (which == 0) {
                        prefs.edit()
                                .remove(Keys.KEY_VEHICLE_ART_PIC)
                                .remove(Keys.KEY_VEHICLE_ART_RGB)
                                .commit();
                    } else if (which >= officialBase && which < localRow) {
                        com.github.speedhunter.net.OfficialArt.Entry e =
                                com.github.speedhunter.net.OfficialArt.ENTRIES[
                                        which - officialBase];
                        prefs.edit()
                                .putString(Keys.KEY_VEHICLE_ART_PIC, e.pic)
                                .putString(Keys.KEY_VEHICLE_ART_RGB, e.rgb)
                                .commit();
                    } else if (which == localRow) {
                        dialog.dismiss();
                        pickLocalArt(false);
                        return;
                    } else if (which == customRow) {
                        dialog.dismiss();
                        show2dCustomEditor();
                        return;
                    }
                    dialog.dismiss();
                    persist();
                    updateVehicleArtHint();
                    Toast.makeText(ctx, "已保存。重启官方 App 后主页生效",
                            Toast.LENGTH_SHORT).show();
                });
    }

    /** 旧版 2D URL + 主题色编辑框（自定义路径保留）。 */
    private void show2dCustomEditor() {
        if (prefs == null) {
            return;
        }
        final Context ctx = requireContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.EditText etPic = artField(ctx,
                prefs.getString(Keys.KEY_VEHICLE_ART_PIC, ""),
                "2D 车图 URL（PNG，替换 vehiclePicUrl；留空不替换）");
        final android.widget.EditText etRgb = artField(ctx,
                prefs.getString(Keys.KEY_VEHICLE_ART_RGB, ""),
                "主题色（可选，如 #131415，替换 appThemeRgb；留空不替换）");
        box.addView(etPic);
        box.addView(etRgb);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle("2D 车图 / 主题色")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit()
                                .putString(Keys.KEY_VEHICLE_ART_PIC, etPic.getText().toString().trim())
                                .putString(Keys.KEY_VEHICLE_ART_RGB, etRgb.getText().toString().trim())
                                .commit();
                        persist();
                        updateVehicleArtHint();
                        Toast.makeText(ctx, "已保存。重启官方 App 后主页生效", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private android.widget.EditText artField(Context ctx, String value, String hint) {
        android.widget.EditText et = new android.widget.EditText(ctx);
        et.setText(value);
        et.setHint(hint);
        et.setTextSize(13);
        return et;
    }

    // ==================== 本地车模上传 ====================

    /** 上传目标：true=3D glb（KEY_VEHICLE_ART_PIC3D），false=2D 图片（KEY_VEHICLE_ART_PIC）。 */
    private volatile boolean uploadFor3d;

    private final ActivityResultLauncher<String> pickArt = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    uploadArt(uri, uploadFor3d);
                }
            });

    /**
     * 选本地文件：glb（image/* 或任意，glb 选好后走文件管理器）。AssetServer 挂在官方
     * App 进程里 —— 没连上就提示先打开官方 App。
     */
    private void pickLocalArt(boolean for3d) {
        uploadFor3d = for3d;
        pickArt.launch(for3d ? "*/*" : "image/*");
    }

    private void uploadArt(Uri uri, boolean for3d) {
        final Context ctx = requireContext();
        Toast.makeText(ctx, "开始上传…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String name = sanitizedName(uri);
            java.io.File staged = null;
            int port = -1;
            String uploadedUrl = null;
            String error = null;
            try {
                // ① 先把 content:// 拷到 cacheDir（HTTP 流式上传不能依赖 ContentResolver 生命周期）
                String ext = name.lastIndexOf('.') >= 0
                        ? name.substring(name.lastIndexOf('.')) : for3d ? ".glb" : ".png";
                staged = new java.io.File(ctx.getCacheDir(), "art_upload" + ext);
                try (InputStream is = ctx.getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(staged)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = is.read(buf)) >= 0) {
                        os.write(buf, 0, r);
                    }
                }
                // ② 依次探测官方 App 进程里的资产服务器
                for (int p = com.github.speedhunter.net.AssetServer.PORT_FIRST;
                        p <= com.github.speedhunter.net.AssetServer.PORT_LAST; p++) {
                    try {
                        HttpURLConnection ping = (HttpURLConnection)
                                new URL("http://127.0.0.1:" + p + "/ping").openConnection();
                        ping.setConnectTimeout(400);
                        ping.setReadTimeout(800);
                        if (ping.getResponseCode() == 200) {
                            port = p;
                        }
                        ping.disconnect();
                        if (port > 0) {
                            break;
                        }
                    } catch (Exception ignore) {
                        // 未开
                    }
                }
                if (port < 0) {
                    error = "官方 App 未运行（找不到资产服务器）。请先打开官方 App 再上传";
                } else {
                    // ③ POST /upload
                    HttpURLConnection up = (HttpURLConnection) new URL(
                            "http://127.0.0.1:" + port + "/upload?name=" + name)
                            .openConnection();
                    up.setDoOutput(true);
                    up.setConnectTimeout(3000);
                    up.setReadTimeout(30000);
                    up.setRequestMethod("POST");
                    up.setRequestProperty("Content-Type", "application/octet-stream");
                    up.setFixedLengthStreamingMode(staged.length());
                    try (OutputStream os = up.getOutputStream();
                         InputStream is = new java.io.FileInputStream(staged)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = is.read(buf)) >= 0) {
                            os.write(buf, 0, r);
                        }
                    }
                    int code = up.getResponseCode();
                    if (code == 200) {
                        try (InputStream is = up.getInputStream()) {
                            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[1024];
                            int r;
                            while ((r = is.read(buf)) >= 0) {
                                bos.write(buf, 0, r);
                            }
                            uploadedUrl = bos.toString("UTF-8").trim();
                        }
                    } else {
                        error = "上传失败 HTTP " + code;
                    }
                    up.disconnect();
                }
            } catch (Exception e) {
                error = "上传失败：" + e;
            } finally {
                if (staged != null && !staged.delete()) {
                    staged.deleteOnExit();
                }
            }
            final String okUrl = uploadedUrl;
            final String err = error;
            final java.io.File stagedF = null;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (okUrl != null && !okUrl.isEmpty()) {
                    if (for3d) {
                        prefs.edit()
                                .putString(Keys.KEY_VEHICLE_ART_PIC3D, okUrl)
                                .putString(Keys.KEY_VEHICLE_ART_MODEL_NAME,
                                        "本地·" + name)
                                .commit();
                    } else {
                        prefs.edit().putString(Keys.KEY_VEHICLE_ART_PIC, okUrl).commit();
                    }
                    persist();
                    updateVehicleArtHint();
                    Toast.makeText(ctx, "上传成功。重启官方 App 后主页生效",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, err == null ? "上传失败" : err,
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "speedhunter-art-upload").start();
    }

    /** 从 Uri 推一个资产服务器可接受的安全文件名（白名单 [A-Za-z0-9._-]）。 */
    private String sanitizedName(Uri uri) {
        String raw = uri.getLastPathSegment();
        if (raw == null) {
            raw = "art";
        }
        int slash = raw.lastIndexOf('/');
        if (slash >= 0) {
            raw = raw.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 60; i++) {
            char ch = raw.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
            sb.append(ok ? ch : '_');
        }
        if (sb.length() == 0) {
            sb.append("art");
        }
        // 防撞名：带时间戳前缀
        return System.currentTimeMillis() + "_" + sb;
    }
}
