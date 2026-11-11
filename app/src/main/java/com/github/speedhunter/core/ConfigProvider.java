package com.github.speedhunter.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 把模块开关暴露给 hook 进程（com.cfmoto）读。
 *
 * <p>为什么不用共享文件：Android 10+ 之后 {@code MODE_WORLD_READABLE} 已废，
 * SharedPreferences 目录权限（0751）让别的进程根本走不进来。
 * 导出的 ContentProvider 没有这个问题 —— 目标进程直接 query，
 * 不需要文件权限，也不受分区存储影响。</p>
 *
 * <p>只导出 {@code query}，insert/update/delete 一律拒绝：
 * 配置只允许模块自己的 UI 写。</p>
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.github.speedhunter.settings";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/settings");
    public static final String COLUMN_KEY = "key";
    public static final String COLUMN_VALUE = "value";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        if (getContext() == null) return null;
        // ★ 与 UI 单一数据源保持一致：账号页 / 首页 / 标定页 / 反向同步都写
        //   默认 SharedPreferences。旧实现读 Keys.PREFS 那份独立文件，升级后
        //   这里会读到空表，兜底通道形同失效。
        Map<String, ?> all = android.preference.PreferenceManager
                .getDefaultSharedPreferences(getContext()).getAll();
        MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_KEY, COLUMN_VALUE});
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            Object value = entry.getValue();
            if (value == null) continue;
            // 登录凭证只走「广播 + hook 侧自落盘」通道，不从这个 exported provider 出去。
            // provider 未加权限保护，任何应用都能 query；账号三键全部排除，避免凭据外泄。
            // 这两条不参与 hook 决策以外的用途，排除不影响主链路。
            String key = entry.getKey();
            if (Keys.KEY_ACCOUNT_TOKEN.equals(key)
                    || Keys.KEY_ACCOUNT_USERID.equals(key)) continue;
            cursor.addRow(new Object[]{entry.getKey(), String.valueOf(value)});
        }
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;   // 只读
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;      // 只读
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;      // 只读
    }
}
