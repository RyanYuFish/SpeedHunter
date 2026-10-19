package com.github.zeehospeedhunter.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;


import com.github.zeehospeedhunter.R;

/**
 * 底栏（5 个 tab）的装配：每个通过底栏进入的 Activity 都 {@code include} 同一份
 * {@code view_bottom_nav}，并在这里装好「选中态 + 跳转 + insets」。
 *
 * <h3>为什么每个页面都放一份底栏</h3>
 * <p>底栏要始终可见 —— 切 tab 时换的是下面的内容，不是整块屏幕。
 * 需求是「底栏进入的 Activity 同级而非上下层」，所以跳转用
 * {@link Intent#FLAG_ACTIVITY_REORDER_TO_FRONT}：已在栈里的实例直接提到前台，
 * 不新建、不成父子关系；配 {@link Intent#FLAG_ACTIVITY_NO_ANIMATION} 让切换
 * 像切页签而不是开新窗口。</p>
 *
 * <h3>insets</h3>
 * <ul>
 *   <li>底栏本身：手势区高度用 <b>margin</b> 补（{@code EdgeToEdge.applyBottomMargin}），
 *       背景正好停在 tab 内容底部，不会多出「遮罩层高一截」的那段底色；</li>
 *   <li>内容区：底部 padding 补「底栏高度 + 手势区」，最后一行不被压住。</li>
 * </ul>
 */
public final class NavBar {

    private NavBar() {
    }

    /**
     * 回到本页时把底栏选中态拉回本页对应的 tab —— 每个 tab 页的 {@code onResume} 都要调。
     *
     * <p>★ 为什么必须补这一步：切 tab 时当前页的底栏已经把所点 tab 选中了（要让药丸
     * 立刻跟手），而这些 Activity 是 {@code REORDER_TO_FRONT} <b>复用</b>的 ——
     * 回到某页时，它那份「上一次点击」的选中态还留在那儿，于是高亮药丸停在上次点的
     * tab 上、不跟着当前页面走。本页自己的 id 重新选中一次即可。</p>
     */
    public static void reselect(Activity activity, int selectedId) {
        com.google.android.material.bottomnavigation.BottomNavigationView nav =
                activity.findViewById(R.id.bottom_nav);
        if (nav != null) {
            nav.setSelectedItemId(selectedId);   // 同 id 也会走 setChecked(true)，顺带把药丸重画
        }
    }

    /**
     * @param activity    当前 Activity
     * @param selectedId  本页对应的 tab id（点它 = 已在本页，不重复跳转）
     * @param contentId   本页滚动容器 id（要避开底栏的那个 View）
     */
    public static void install(Activity activity, int selectedId, int contentId) {
        com.google.android.material.bottomnavigation.BottomNavigationView nav =
                activity.findViewById(R.id.bottom_nav);
        if (nav == null) {
            return;
        }
        nav.setSelectedItemId(selectedId);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == selectedId) {
                return true;   // 已在本页
            }
            Class<?> target = id == R.id.nav_ride ? RideActivity.class
                    : id == R.id.nav_ota ? OtaActivity.class
                    : id == R.id.nav_wallpaper ? WallpaperActivity.class
                    : id == R.id.nav_mirror ? MirrorActivity.class
                    : DiagActivity.class;
            Intent intent = new Intent(activity, target);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try {
                activity.startActivity(intent);
                // 兜底压掉残余转场（主题里已清 windowAnimationStyle，这里再保一道）
                activity.overridePendingTransition(0, 0);
            } catch (Throwable t) {
                android.widget.Toast.makeText(activity, "打不开对应页面：" + t,
                        android.widget.Toast.LENGTH_LONG).show();
            }
            return true;   // 选中态留在所点 tab
        });

        // 系统导航条（手势区）沉浸：刷成底栏同一个色（colorSurfaceContainer）。
        // edge-to-edge 下它是透明的 ⇒ 露的是页面底色，跟底栏有色差、看着不沉浸。
        try {
            int barColor = com.google.android.material.color.MaterialColors.getColor(
                    activity, com.google.android.material.R.attr.colorSurfaceContainer,
                    android.graphics.Color.TRANSPARENT);
            activity.getWindow().setNavigationBarColor(barColor);
        } catch (Throwable ignored) {
        }

        EdgeToEdge.applyBottomMargin(nav);

        // 内容区：底部 padding = 手势区 + 底栏实际高度（底栏一布局好就重算）
        View content = activity.findViewById(contentId);
        if (content != null) {
            EdgeToEdge.applyBottomAvoid(content, nav);
        }
    }
}
