package com.github.speedhunter.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;

import com.github.speedhunter.R;
import com.github.speedhunter.core.Keys;

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
    private MaterialSwitch swCustomKey;
    private TextView tvCustomKeyHint;

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
        swCustomKey = view.findViewById(R.id.sw_custom_key);
        tvCustomKeyHint = view.findViewById(R.id.tv_custom_key_hint);
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
        swCustomKey.setChecked(prefs.getBoolean(Keys.KEY_CUSTOM_KEY_FORCE, false));
        updateCustomKeyHint();

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
        swCustomKey.setOnCheckedChangeListener(listener);

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
                    .putBoolean(Keys.KEY_CUSTOM_KEY_FORCE, swCustomKey.isChecked())
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
            updateCustomKeyHint();
        }
    }

    /**
     * 自定义键开关的提示文案（压弯数据卡下方的状态行）：
     * <ul>
     *   <li>开 → 「自定义键设置全映射为切换信息组」（里程组 ⇄ 胎压组）</li>
     *   <li>关 → 「自定义键功能按照 ZEEHO App 的选项为准」</li>
     * </ul>
     */
    private void updateCustomKeyHint() {
        if (tvCustomKeyHint == null || swCustomKey == null) {
            return;
        }
        tvCustomKeyHint.setText(swCustomKey.isChecked()
                ? "已开启：自定义键设置全映射为切换信息组（里程组 ⇄ 胎压组，首页显示前后轮胎压/胎温）"
                : "关闭：自定义键功能按照 ZEEHO App 的选项为准");
    }
}
