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
            } catch (Throwable t) {
                android.widget.Toast.makeText(activity, "打不开对应页面：" + t,
                        android.widget.Toast.LENGTH_LONG).show();
            }
            return true;   // 选中态留在所点 tab
        });

        EdgeToEdge.applyBottomMargin(nav);

        // 内容区：底部 padding = 手势区 + 底栏实际高度（底栏一布局好就重算）
        View content = activity.findViewById(contentId);
        if (content != null) {
            EdgeToEdge.applyBottomAvoid(content, nav);
        }
    }
}
