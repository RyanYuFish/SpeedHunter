package com.github.speedhunter.customkey;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;

import com.github.speedhunter.core.HookKit;
import com.github.speedhunter.core.HookLog;

/**
 * 自定义键强制为「切换信息组（功能码 7）」。
 *
 * <h3>机制</h3>
 * <p>在官方 App 的 {@code com.cfmoto.ui.carsetting.CustomKeySelectActivity#handleSave()}
 * 执行<b>之前</b>，把该 Activity 里所有 {@code CustomButtonCode.commandCode} 反射改写成 7。
 * App 随后按自己的通道（实测恒为 4G 云端，<b>不走 BLE</b>）把 7 发出去，车端
 * {@code SetItem4+0x0f} 落入 7，仪表主页按自定义键即在<b>里程组 ⇄ 胎压组</b>间切换，
 * 从而把前后轮胎压 / 胎温显示出来。</p>
 *
 * <h3>来源与边界</h3>
 * <p>移植自 <b>DashProbe v0.6.0</b>（2026-10-28 实车验证通过：自定义键按一下即切组，
 * 且重启 / 投屏 / 来电后功能不丢）。本类只移植<b>模型层改写</b>——仪表侧行为对照见
 * DashProbe README §9.9；BLE 帧改写兜底在 App 此设置项上从不触发，故不移植。</p>
 *
 * <ul>
 *   <li>只改 {@code commandCode} 一个 int 字段；{@code buttonType} / {@code functionCode}
 *   （名字串）不动，App 界面仍显示用户点的那张卡片。</li>
 *   <li>opt-in 默认关：关闭时本 hook 是纯 no-op，不触碰任何字段、不拦截任何调用。</li>
 *   <li>整段包在 try/catch 里：任何异常退化为「原样放行」，绝不让保存失败。</li>
 *   <li>锚点类来自 AndroidManifest（加固不影响），找不到或 handleSave 签名不符时安静跳过。</li>
 * </ul>
 */
public final class CustomKeyForce {

    private static final String TAG = HookLog.KEY;

    /** 稳定锚点：Manifest 里的自定义键设置页组件类。 */
    private static final String ACTIVITY = "com.cfmoto.ui.carsetting.CustomKeySelectActivity";
    private static final String METHOD = "handleSave";
    private static final String FIELD_CODE = "commandCode";
    /** 按运行时类名判断（字段声明类型可能是接口/父类）。 */
    private static final String TYPE_HINT = "CustomButtonCode";

    private CustomKeyForce() {
    }

    /** 常驻安装 hook；开关在回调里实时判断，改完不必冷重启 App。 */
    public static void installAll() {
        try {
            HookKit.hookIfExists(TAG, ACTIVITY, METHOD, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    if (!CustomKeyOptions.enabled()) {
                        return;     // 关闭 = 完全放行
                    }
                    try {
                        Object activity = param.thisObject;
                        if (activity == null) {
                            return;
                        }
                        int target = CustomKeyOptions.targetValue();
                        int changed = applyForce(activity, target);
                        HookLog.log(TAG + " ★模型改写 target=" + target
                                + " 命中 " + changed + " 个对象"
                                + (changed == 0
                                    ? "（未找到 CustomButtonCode 字段，本次保存仍是原厂值）" : ""));
                    } catch (Throwable t) {
                        HookLog.log(TAG + " rewrite threw: " + t);
                    }
                }
            });
            HookLog.log(TAG + " custom-key force hooks installed (enabled="
                    + CustomKeyOptions.enabled() + ")");
        } catch (Throwable t) {
            HookLog.log(TAG + " install failed: " + t);
        }
    }

    /** 把 activity 内所有 CustomButtonCode 实例的 commandCode 改成 target。返回覆盖的对象数。 */
    private static int applyForce(Object activity, int target) {
        int changed = 0;
        for (Class<?> k = activity.getClass(); k != null && k != Object.class;
             k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(activity);
                    if (v == null) {
                        continue;
                    }
                    if (!f.getType().getSimpleName().contains(TYPE_HINT)
                            && !v.getClass().getSimpleName().contains(TYPE_HINT)) {
                        continue;
                    }
                    int before = getCode(v);
                    if (before == target) {
                        changed++;          // 已是目标值，仍计入"已覆盖"
                        continue;
                    }
                    if (setCode(v, target)) {
                        HookLog.log(TAG + "   commandCode " + before + " → " + target
                                + "（切换信息组）");
                        changed++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return changed;
    }

    private static int getCode(Object code) {
        try {
            Field cf = findField(code.getClass(), FIELD_CODE);
            if (cf == null) {
                return -1;
            }
            cf.setAccessible(true);
            return cf.getInt(code);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean setCode(Object code, int target) {
        try {
            Field cf = findField(code.getClass(), FIELD_CODE);
            if (cf == null) {
                return false;
            }
            cf.setAccessible(true);
            cf.setInt(code, target);
            return true;
        } catch (Throwable t) {
            HookLog.log(TAG + "   commandCode 写入被拒: " + t);
            return false;
        }
    }

    private static Field findField(Class<?> k, String name) {
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
