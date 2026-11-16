package com.github.speedhunter.net;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.github.speedhunter.core.HookLog;
import com.github.speedhunter.core.ModelProfile;

/**
 * AE5i 等「非 AE7」车型的显示裁剪：把响应里混入的 AE7 独有设置项删掉。
 *
 * <h3>为什么要裁</h3>
 * <p>AE5i 的硬件没有双电、灯效等能力，这些行就算显示出来也点不动/无响应。
 * 正常服务端不会下发，但模块自己的克隆注入（例如 ABS 复制分类子项）可能
 * 把模板里的 AE7 项带出来，所以在响应出口统一兜底。</p>
 *
 * <p>只从<b>数组</b>里移除整个对象，绝不删对象字段 —— 避免破坏结构。</p>
 */
public final class UnsupportedFilter {

    private UnsupportedFilter() {
    }

    /**
     * 递归裁剪。
     *
     * @return 被移除的条目数（0 = 无需改动）
     */
    public static int strip(Object root) {
        try {
            int n = stripIn(root);
            if (n > 0) {
                HookLog.log(HookLog.NET + " ae5i stripped " + n
                        + " unsupported (AE7-only) item(s)");
            }
            return n;
        } catch (Throwable t) {
            HookLog.log(HookLog.NET + " strip failed: " + t.getClass().getSimpleName());
            return 0;
        }
    }

    private static int stripIn(Object node) {
        int n = 0;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            // 先收集 key 再遍历：子数组会被修改，但对象本身不改
            List<String> keys = new ArrayList<String>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            for (String k : keys) {
                Object v = obj.opt(k);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    n += stripIn(v);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            // 倒序：移除元素不影响未遍历部分的下标
            for (int i = arr.length() - 1; i >= 0; i--) {
                Object v = arr.opt(i);
                if (v instanceof JSONObject) {
                    Object code = ((JSONObject) v).opt("functionCode");
                    if (code instanceof String && ModelProfile.isAe7Only((String) code)) {
                        arr.remove(i);
                        n++;
                        continue;
                    }
                }
                n += stripIn(v);
            }
        }
        return n;
    }
}
