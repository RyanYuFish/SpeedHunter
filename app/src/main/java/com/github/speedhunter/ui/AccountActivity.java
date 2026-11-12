package com.github.speedhunter.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

import com.github.speedhunter.R;
import com.github.speedhunter.core.ConfigSender;
import com.github.speedhunter.core.Keys;
import com.github.speedhunter.net.AccountApi;

/**
 * 账号引导登录页 —— 从骑行记录页进入，与「压弯标定」同为独立二级页。
 *
 * <p>录入从其他客户端取得的 {@code token + 账号 ID}，保存到本模块的
 * <b>默认 SharedPreferences</b>，并经 {@link ConfigSender} 广播给 hook 进程
 * （{@code com.cfmoto}）即时生效 + 落盘冷启动恢复。</p>
 *
 * <h3>★ 为什么必须用默认 SharedPreferences</h3>
 * <p>本页旧实现用 {@code getSharedPreferences(Keys.PREFS, ...)}，而首页、压弯标定页
 * 和 {@code UiApp.ConfigSyncReceiver} 用的都是
 * {@code PreferenceManager.getDefaultSharedPreferences()} —— <b>两个不同的文件</b>。
 * 结果是：本页保存的凭证，在 hook 进程冷启动发出 {@code ACTION_CONFIG_SYNC} 反向拉取时，
 * UI 用另一份空配置重发，把刚送达的 token 覆盖成空串 ⇒ 页面显示「保存成功」，
 * 官方 App 却完全没有注入。现统一到默认 prefs，并在首次进入时把旧文件里的
 * 凭证迁移过来，保证升级不丢数据。</p>
 *
 * <h3>★ 为什么要有 hook 回执</h3>
 * <p>「保存成功」与「{@link AccountApi} 查询成功」都发生在<b>模块 UI 进程</b>，
 * 跟 hook 有没有收到凭证毫无关系。因此本页通过既有的
 * {@link Keys#ACTION_HOOK_PING} / {@link Keys#ACTION_HOOK_PONG} 心跳，
 * 让 hook 进程回传它<b>实际生效</b>的状态（凭证是否送达、注入命中次数），
 * 状态区只认这个回执，不再拿本进程的保存结果冒充登录成功。</p>
 *
 * <p>签到 / 积分 / 任务查询由本页直接发起（{@link AccountApi}），全部带上当前录入的
 * 凭证并引用当前账号 ID；「立即签到」是唯一写操作，需二次确认，无自动执行。</p>
 */
public final class AccountActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private EditText etToken;
    private EditText etUserId;
    private MaterialSwitch swLogin;
    private TextView tvHint;
    private TextView tvStatus;
    private TextView tvHook;
    private TextView tvSummary;
    private TextView tvDetail;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String signState = "未查询";
    private String integralState = "未查询";
    private String taskState = "未查询";

    /** hook 侧回执：null = 本次还没收到回执。 */
    private Boolean hookReady;
    private int hookTokenLen;
    private int hookUidLen;
    private int hookInject;
    private boolean pongReceiverRegistered;
    /** save() 里程序化 setChecked 时抑制监听器的落盘，避免推出一条旧值的中间广播。 */
    private boolean suppressSwitchPersist;

    private final BroadcastReceiver hookPongReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Keys.ACTION_HOOK_PONG.equals(intent.getAction())) return;
            hookReady = intent.getBooleanExtra(Keys.EXTRA_ACCOUNT_READY, false);
            hookTokenLen = intent.getIntExtra(Keys.EXTRA_ACCOUNT_TOKEN_LEN, 0);
            hookUidLen = intent.getIntExtra(Keys.EXTRA_ACCOUNT_UID_LEN, 0);
            hookInject = intent.getIntExtra(Keys.EXTRA_ACCOUNT_INJECT, 0);
            updateStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        BackGestureCompat.install(this);
        EdgeToEdge.setup(this, findViewById(R.id.root_account));

        prefs = openPrefs();

        etToken = findViewById(R.id.et_account_token);
        etUserId = findViewById(R.id.et_account_userid);
        swLogin = findViewById(R.id.sw_account_login);
        tvHint = findViewById(R.id.tv_account_hint);
        tvStatus = findViewById(R.id.tv_account_status);
        tvHook = findViewById(R.id.tv_account_hook);
        tvSummary = findViewById(R.id.tv_account_summary);
        tvDetail = findViewById(R.id.tv_account_detail);

        etToken.setText(prefs.getString(Keys.KEY_ACCOUNT_TOKEN, ""));
        etUserId.setText(prefs.getString(Keys.KEY_ACCOUNT_USERID, ""));
        swLogin.setChecked(prefs.getBoolean(Keys.KEY_ACCOUNT_LOGIN, false));
        updateHint();
        updateStatus();

        swLogin.setOnCheckedChangeListener((button, checked) -> {
            updateHint();
            // 手动拨开关也要立即落盘 + 重推：旧实现只改文案，用户关掉开关后
            // hook 侧仍按上次广播的 true 继续注入，等于「关不掉」。
            if (suppressSwitchPersist) return;
            prefs.edit().putBoolean(Keys.KEY_ACCOUNT_LOGIN, checked).commit();
            ConfigSender.pushFromPrefs(this, prefs);
            updateStatus();
            pingHook();
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_account_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_account_clear).setOnClickListener(v -> clear());
        findViewById(R.id.btn_account_clear_app).setOnClickListener(v -> clearAppLoginState());
        findViewById(R.id.btn_account_refresh).setOnClickListener(v -> {
            refresh();
            pingHook();
        });
        findViewById(R.id.btn_account_signin).setOnClickListener(v -> confirmSignIn());
        // 与「压弯标定」日志区同一套：固定高度 TextView 必须自己能滚，
        // 且按下时要拦住外层 ScrollView，否则手指划的是整页。
        setupDetailScrolling(tvDetail);
        renderSummary();
    }

    private void setupDetailScrolling(TextView view) {
        view.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        view.setVerticalScrollBarEnabled(true);
        view.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;   // 别吃掉事件，TextView 还要靠它驱动滚动
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerPong();
        // 进页面就推一次全量配置（把默认 prefs 里的账号键显式带给 hook），再问状态。
        // 覆盖「先在账号页保存、后打开官方 App」以及「反向同步曾把凭证冲空」两种时序。
        ConfigSender.pushFromPrefs(this, prefs);
        pingHook();
        main.postDelayed(this::pingHook, 1200);
    }

    @Override
    protected void onPause() {
        try {
            if (pongReceiverRegistered) {
                unregisterReceiver(hookPongReceiver);
                pongReceiverRegistered = false;
            }
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    private void registerPong() {
        if (pongReceiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter(Keys.ACTION_HOOK_PONG);
            // 发送方是 com.cfmoto（另一个 App）⇒ 必须 EXPORTED
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(hookPongReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(hookPongReceiver, f);
            }
            pongReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    /** 问 hook 进程要它在自己进程里实际生效的账号状态。 */
    private void pingHook() {
        hookReady = null;
        try {
            Intent ping = new Intent(Keys.ACTION_HOOK_PING);
            ping.setPackage(Keys.TARGET_PKG);
            sendBroadcast(ping);
        } catch (Throwable ignored) {
        }
        main.postDelayed(this::updateStatus, 900);
    }

    /**
     * 统一用默认 SharedPreferences（与首页 / 压弯标定页 / UiApp 反向同步一致）。
     * 旧版账号页写在 {@code Keys.PREFS} 里，首次升级做一次性迁移，避免凭证丢失。
     */
    private SharedPreferences openPrefs() {
        SharedPreferences current =
                android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences legacy = getSharedPreferences(Keys.PREFS, MODE_PRIVATE);
        if (!current.contains(Keys.KEY_ACCOUNT_TOKEN)
                && legacy.contains(Keys.KEY_ACCOUNT_TOKEN)) {
            current.edit()
                    .putString(Keys.KEY_ACCOUNT_TOKEN,
                            legacy.getString(Keys.KEY_ACCOUNT_TOKEN, ""))
                    .putString(Keys.KEY_ACCOUNT_USERID,
                            legacy.getString(Keys.KEY_ACCOUNT_USERID, ""))
                    .putBoolean(Keys.KEY_ACCOUNT_LOGIN,
                            legacy.getBoolean(Keys.KEY_ACCOUNT_LOGIN, false))
                    .putString(Keys.KEY_ACCOUNT_STATUS,
                            legacy.getString(Keys.KEY_ACCOUNT_STATUS, ""))
                    .commit();
        }
        return current;
    }

    private void save() {
        String token = etToken.getText().toString().trim();
        String userId = etUserId.getText().toString().trim();
        if (token.isEmpty() || userId.isEmpty()) {
            Toast.makeText(this, "token 与账号 ID 都要填", Toast.LENGTH_SHORT).show();
            return;
        }
        // ★ 按钮字面是「保存并登录」，就必须真的开启引导登录。
        //   旧实现写的是 swLogin.isChecked()，而该开关默认 false —— 用户填完凭证
        //   直接点按钮，保存下来的却是 account_login=false，hook 侧永远不 ready，
        //   表现就是「页面显示保存成功、官方 App 却毫无变化」。要停用请手动关开关。
        suppressSwitchPersist = true;
        swLogin.setChecked(true);
        suppressSwitchPersist = false;
        prefs.edit()
                .putString(Keys.KEY_ACCOUNT_TOKEN, token)
                .putString(Keys.KEY_ACCOUNT_USERID, userId)
                .putBoolean(Keys.KEY_ACCOUNT_LOGIN, true)
                .putString(Keys.KEY_ACCOUNT_STATUS, "尚未校验")
                .commit();
        ConfigSender.pushFromPrefs(this, prefs);
        updateHint();
        updateStatus();
        Toast.makeText(this, "凭证已推送 —— 请看下方的「模块生效状态」确认送达",
                Toast.LENGTH_LONG).show();
        refresh();
        pingHook();
        // 引导登录已开启 ⇒ 顺势把极核 App 桌面启动项拉起来，省得再手动切过去。
        launchZeehoApp();
    }

    /** 拉起极核 App 的桌面启动项（Launcher Activity）。失败静默忽略。 */
    private void launchZeehoApp() {
        try {
            android.content.Intent launch = getPackageManager()
                    .getLaunchIntentForPackage(Keys.TARGET_PKG);
            if (launch == null) {
                Toast.makeText(this, "未找到极核 App，请确认已安装并在 LSPosed 勾选作用域",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
        } catch (Throwable t) {
            // 极核 App 没装 / 被停用都算正常失败，不打断主流程
        }
    }

    /**
     * 「清除App登录状态」：让极核 App 退出它内部登录的全部账号，得到一个干净的未登录环境。
     *
     * <p>与右上角「清除凭证」的区别：后者清的是<b>本模块</b>保存的引导登录来源
     * （token / 账号ID）；本按钮清的是<b>极核 App 自己</b>的登录态（is_login / session /
     * token 等），由 hook 进程在其自身存储里执行并自重启。本机录入的凭证<b>保留</b>，
     * 便于稍后重新开启引导登录直接套用。</p>
     */
    private void clearAppLoginState() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("清除 App 登录状态")
                .setMessage("将退出极核 App 内登录的全部账号，并暂时关闭「启用引导登录」，让 App 回到干净未登录态（本机录入的凭证会保留，可随时重新开启引导登录）。极核 App 会随后自动重启。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除并重启", (d, w) -> {
                    // 1) 关闭本模块引导登录（本进程 prefs + 广播给 hook），与「清除凭证」隔离：
                    //    这里只是<b>暂时</b>关掉覆盖，凭证不删，方便重新开启。
                    suppressSwitchPersist = true;
                    swLogin.setChecked(false);
                    suppressSwitchPersist = false;
                    prefs.edit().putBoolean(Keys.KEY_ACCOUNT_LOGIN, false).commit();
                    ConfigSender.pushFromPrefs(this, prefs);
                    // 2) 让 hook 进程清空极核 App 登录态并自重启
                    try {
                        Intent intent = new Intent(Keys.ACTION_CLEAR_LOGIN);
                        intent.setPackage(Keys.TARGET_PKG);
                        sendBroadcast(intent);
                    } catch (Throwable ignored) {
                    }
                    Toast.makeText(this, "已向极核 App 发送登出指令，即将自动重启",
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void clear() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("清除凭证")
                .setMessage("将删除本机保存的 token 与账号 ID，并关闭引导登录。官方 App 恢复原登录态。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, w) -> {
                    prefs.edit()
                            .remove(Keys.KEY_ACCOUNT_TOKEN)
                            .remove(Keys.KEY_ACCOUNT_USERID)
                            .putBoolean(Keys.KEY_ACCOUNT_LOGIN, false)
                            .remove(Keys.KEY_ACCOUNT_STATUS)
                            .commit();
                    ConfigSender.pushFromPrefs(this, prefs);
                    etToken.setText("");
                    etUserId.setText("");
                    suppressSwitchPersist = true;
                    swLogin.setChecked(false);
                    suppressSwitchPersist = false;
                    signState = integralState = taskState = "未查询";
                    tvDetail.setText("");
                    updateHint();
                    updateStatus();
                    renderSummary();
                    Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
                    pingHook();
                })
                .show();
    }

    private void updateHint() {
        tvHint.setText(swLogin.isChecked()
                ? "已开启：官方 App 的云端请求以录入凭证发出"
                : "关闭：官方 App 保持原登录态");
    }

    private void updateStatus() {
        String userId = prefs.getString(Keys.KEY_ACCOUNT_USERID, "");
        boolean on = prefs.getBoolean(Keys.KEY_ACCOUNT_LOGIN, false);
        if (userId.isEmpty()) {
            tvStatus.setText("本机凭证：未录入");
        } else {
            tvStatus.setText("本机凭证：" + (on ? "已录入 · 引导登录开启" : "已录入 · 未启用")
                    + "\n当前账号：" + userId
                    + "\n云端校验：" + prefs.getString(Keys.KEY_ACCOUNT_STATUS, "尚未校验"));
        }
        // ★ 状态区只认 hook 回执：本进程保存成功 / AccountApi 查询成功都不作数
        if (hookReady == null) {
            tvHook.setText("模块生效状态：等待回执…\n"
                    + "（若一直是「未挂载」，请先打开一次极核 App 让模块随进程注入）");
        } else if (!hookReady) {
            tvHook.setText("模块生效状态：已挂载，但凭证未送达 hook\n"
                    + "tokenLen=" + hookTokenLen + "  userIdLen=" + hookUidLen
                    + "\n请点「保存并登录」重推配置；仍无效则重启极核 App。");
        } else {
            tvHook.setText("模块生效状态：✓ 凭证已送达 hook，引导登录已就绪\n"
                    + "tokenLen=" + hookTokenLen + "  userIdLen=" + hookUidLen
                    + "  请求注入命中 " + hookInject + " 次"
                    + (hookInject == 0 ? "\n（官方 App 尚未发出账号请求，进一次「我的 / 签到」页即可）" : ""));
        }
    }

    /** 只读刷新：签到次数 / 积分 / 任务列表，全部带当前凭证。 */
    private void refresh() {
        final String token = prefs.getString(Keys.KEY_ACCOUNT_TOKEN, "");
        final String userId = prefs.getString(Keys.KEY_ACCOUNT_USERID, "");
        if (token.isEmpty() || userId.isEmpty()) {
            Toast.makeText(this, "先填入并保存 token 与账号 ID", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true, "查询中…");
        new Thread(() -> {
            AccountApi.Result sign = AccountApi.signInCount(token, userId);
            AccountApi.Result total = AccountApi.totalIntegral(token, userId);
            AccountApi.Result integral = AccountApi.integral(token, userId);
            AccountApi.Result tasks = AccountApi.taskList(token, userId);

            final String sSign = "签到：" + (sign.ok() ? brief(sign.data) : sign.summary());
            final String sIntegral = "积分：" + (total.ok()
                    ? brief(total.data) : integral.ok() ? brief(integral.data)
                    : total.summary() + " / " + integral.summary());
            final String sTask = "任务：" + (tasks.ok() ? brief(tasks.data) : tasks.summary());
            final String detail = "signin/count → " + sign.body + "\n\n"
                    + "totalIntegral → " + total.body + "\n\n"
                    + "getByUserId → " + integral.body + "\n\n"
                    + "taskList → " + tasks.body;
            final boolean ok = sign.ok() || total.ok() || integral.ok() || tasks.ok();
            // ★ 措辞：这是模块自己直连云端的校验，不等于官方 App 已登录
            final String status = ok ? "token 有效（模块直连云端查询成功）"
                    : "查询未成功（" + sign.summary() + "）";
            main.post(() -> {
                signState = sSign;
                integralState = sIntegral;
                taskState = sTask;
                tvDetail.setText(cap(detail));
                prefs.edit().putString(Keys.KEY_ACCOUNT_STATUS, status).commit();
                updateStatus();
                setBusy(false, null);
                renderSummary();
            });
        }, "account-refresh").start();
    }

    /** 签到为写操作：显式二次确认，结果关联当前账号展示。 */
    private void confirmSignIn() {
        final String userId = prefs.getString(Keys.KEY_ACCOUNT_USERID, "");
        if (userId.isEmpty()) {
            Toast.makeText(this, "先填入并保存凭证", Toast.LENGTH_SHORT).show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("立即签到")
                .setMessage("将使用当前账号 " + userId + " 执行签到（写操作）。确认继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("签到", (d, w) -> doSignIn())
                .show();
    }

    private void doSignIn() {
        final String token = prefs.getString(Keys.KEY_ACCOUNT_TOKEN, "");
        final String userId = prefs.getString(Keys.KEY_ACCOUNT_USERID, "");
        setBusy(true, "签到中…");
        new Thread(() -> {
            AccountApi.Result r = AccountApi.signIn(token, userId);
            final String state = "签到：" + (r.ok()
                    ? "成功 " + brief(r.data) : r.summary());
            main.post(() -> {
                signState = state;
                tvDetail.setText("H5/signin → " + cap(r.body));
                setBusy(false, null);
                renderSummary();
                Toast.makeText(AccountActivity.this,
                        r.ok() ? "签到完成" : "签到未成功，见详情", Toast.LENGTH_LONG).show();
            });
        }, "account-signin").start();
    }

    private void setBusy(boolean busy, String text) {
        findViewById(R.id.btn_account_refresh).setEnabled(!busy);
        findViewById(R.id.btn_account_signin).setEnabled(!busy);
        if (text != null) tvStatus.setText(text);
        else updateStatus();
    }

    private void renderSummary() {
        tvSummary.setText(signState + "\n" + integralState + "\n" + taskState);
    }

    private static String brief(String s) {
        if (s == null || s.isEmpty()) return "无数据";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 90 ? t : t.substring(0, 90) + "…";
    }

    private static String cap(String s) {
        return s == null ? "" : (s.length() <= 4000 ? s : s.substring(0, 4000) + "…");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
    }
}
