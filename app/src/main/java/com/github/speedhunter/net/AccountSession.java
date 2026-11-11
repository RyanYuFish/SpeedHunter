package com.github.speedhunter.net;

import com.github.speedhunter.core.HookKit;
import com.github.speedhunter.core.HookLog;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Presents the selected external account through the official app's session API. */
final class AccountSession {
    private static final String TAG = HookLog.NET;
    private static final Object LOCK = new Object();
    private static volatile Session current;

    private AccountSession() { }

    static void install() {
        hook("getUserInfo");
        hook("getUserId");
        hook("getToken");
        hook("isLogin");
        hook("isLoginAndToLogin");
    }

    private static void hook(final String name) {
        HookKit.hookIfExists(TAG, "com.cfmoto.AppContext", name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Session session = session();
                if (session == null) return;
                if ("getUserInfo".equals(name)) {
                    session.awaitProfile();
                    p.setResult(session.user);
                } else if ("getUserId".equals(name)) {
                    p.setResult(session.id);
                } else if ("getToken".equals(name)) {
                    p.setResult(session.token);
                } else {
                    p.setResult(true);
                }
            }
        });
    }

    private static Session session() {
        if (!AccountOptions.ready()) return null;
        String id = AccountOptions.userId();
        String token = AccountOptions.token();
        Session old = current;
        if (old != null && old.matches(id, token)) return old;
        synchronized (LOCK) {
            old = current;
            if (old != null && old.matches(id, token)) return old;
            try {
                long numericId = Long.parseLong(id);
                if (numericId <= 0) return null;
                Class<?> type = HookKit.findClassIfExists("com.cfmoto.dbmgr.entity.UserInfo");
                if (type == null) return null;
                Object user = type.getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(user, "setId", Long.valueOf(numericId));
                XposedHelpers.callMethod(user, "setUserId", numericId);
                XposedHelpers.callMethod(user, "setToken", token);
                // ★ 关键修复：SettingsActivity.setMyhomeText 会对 province/city/home
                // 等 String 字段调 .hashCode()，而这些字段在引导登录的伪造 UserInfo
                // 里是 null（profile 没返回）→ NPE 闪退。构造后把全部 String 字段
                // 兜底成 ""，杜绝任何「对 null String 调方法」的崩溃；primitive/boxed
                // 字段保持默认（Mine 页已验证无问题），profile 回填时再覆盖真实值。
                Session.fillDefaultStrings(user, type);
                // ★ 「我的APP首页」：把 App 自己保存过的选择值填回伪造对象。
                //   字段名 homepage 由 [UserInfoFields] 实机确认；空值表示尚未学习到，
                //   此时保持 ""（首页会按 App 默认走，不影响其它功能）。
                String homepage = com.github.speedhunter.core.RemoteSettings.getString(
                        com.github.speedhunter.core.Keys.KEY_ACCOUNT_HOMEPAGE, "");
                if (homepage != null && !homepage.isEmpty()) {
                    try {
                        XposedHelpers.setObjectField(user, "homepage", homepage);
                    } catch (Throwable t) {
                        HookLog.log(TAG + " apply homepage failed: "
                                + t.getClass().getSimpleName());
                    }
                }
                Session created = new Session(id, numericId, token, user);
                current = created;
                created.load();
                return created;
            } catch (Throwable t) {
                HookLog.log(TAG + " account session unavailable: " + t.getClass().getSimpleName());
                return null;
            }
        }
    }

    private static final class Session {
        final String idText;
        final long id;
        final String token;
        final Object user;
        final CountDownLatch loaded = new CountDownLatch(1);

        Session(String idText, long id, String token, Object user) {
            this.idText = idText;
            this.id = id;
            this.token = token;
            this.user = user;
        }

        boolean matches(String otherId, String otherToken) {
            return idText.equals(otherId) && token.equals(otherToken);
        }

        void load() {
            Thread worker = new Thread(() -> {
                try {
                    AccountApi.Result result = AccountApi.profile(token);
                    if (!result.ok()) {
                        HookLog.log(TAG + " account profile unavailable: HTTP " + result.http
                                + " code=" + result.code);
                        return;
                    }
                    JSONObject profile = new JSONObject(result.data);
                    if (!idText.equals(profile.optString("id"))) {
                        HookLog.log(TAG + " account profile identity mismatch");
                        return;
                    }
                    if (current != this || !AccountOptions.ready()
                            || !matches(AccountOptions.userId(), AccountOptions.token())) return;
                    // 回填 profile 里返回的全部字段（不止固定几项），每个独立 try，
                    // 避免某个字段没对应 setter 时中断其余字段的回填。
                    java.util.Iterator<String> keys = profile.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (profile.isNull(key)) continue;
                        String setter = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
                        try {
                            copy(profile, key, setter);
                        } catch (Throwable ignored) {
                            // 该字段没有对应 setter（如嵌套对象），跳过
                        }
                    }
                    HookLog.log(TAG + " account profile loaded");
                } catch (Throwable t) {
                    HookLog.log(TAG + " account profile failed: " + t.getClass().getSimpleName());
                } finally {
                    loaded.countDown();
                }
            }, "speedhunter-account-profile");
            worker.setDaemon(true);
            worker.start();
        }

        /**
         * 把 UserInfo 全部 {@code String} 字段兜底成空串，避免「对 null String 调方法」
         * 引发 NPE（如 SettingsActivity.setMyhomeText 对 province/city 调 .hashCode()）。
         * 只动 String（且非 final）；primitive / boxed 字段维持构造默认值（已验证 Mine 页正常）。
         * 同时遍历父类字段——UserInfo 可能继承自 BaseEntity。
         */
        static void fillDefaultStrings(Object user, Class<?> type) {
            try {
                Class<?> c = type;
                while (c != null && c != Object.class) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (!f.getType().equals(String.class)) continue;
                        if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) continue;
                        f.setAccessible(true);
                        if (f.get(user) == null) f.set(user, "");
                    }
                    c = c.getSuperclass();
                }
            } catch (Throwable t) {
                HookLog.log(TAG + " fillDefaultStrings failed: " + t.getClass().getSimpleName());
            }
        }

        void awaitProfile() {
            try {
                loaded.await(1800, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void copy(JSONObject profile, String field, String setter) {
            if (!profile.has(field) || profile.isNull(field)) return;
            XposedHelpers.callMethod(user, setter, profile.optString(field));
        }
    }
}
