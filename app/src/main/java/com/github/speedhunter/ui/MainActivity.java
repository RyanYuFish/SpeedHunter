package com.github.speedhunter.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.github.speedhunter.R;
import com.google.android.material.navigation.NavigationBarView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单宿主 Activity —— 整个 App 只有一个入口、常驻导航栏，5 个 tab 都是 Fragment。
 *
 * <p>★ 这是彻底修复底栏抖动 / 无波纹 / 快速点击闪烁的根因方案：
 * 导航栏只在此处创建一次，切换 tab 时只换 Fragment（{@code show()/hide()}），
 * 导航栏实例<b>不被重建</b>，于是 Material You 的 active indicator 动画、点击波纹（ripple）、
 * 首次点击的指示器测量都连续且稳定。</p>
 *
 * <h3>M3 响应式导航（横竖屏）</h3>
 * <ul>
 *   <li>竖屏（compact）：底部 {@code BottomNavigationView}（{@code R.id.bottom_nav}）；</li>
 *   <li>横屏（medium width class）：侧边 {@code NavigationRailView}（{@code R.id.nav_rail}）——
 *       底栏在横屏会吃掉竖向空间，M3 规范在此档位改用 rail。</li>
 * </ul>
 * 两者都是 {@link NavigationBarView}，共用 {@code @menu/main_nav}，宿主只绑定存在的那个。
 *
 * <h3>★ 配置变更（横竖屏切换 / 折叠屏展开）</h3>
 * <p>Activity 会被重建。若不处理：{@code currentTab} 重新从 Intent 取默认值 ⇒
 * <b>每次转屏都被拉回第一个页面</b>；同时 FragmentManager 已恢复了一份 Fragment，
 * 再 {@code add()} 一份新的会变成两套叠在一起（页面错乱）。
 * 所以这里：① 用 {@code onSaveInstanceState} 记住当前 tab；
 * ② 重建时按 tag 取回 FragmentManager 已恢复的实例，而不是无脑 new + add。</p>
 *
 * <h3>外部入口路由</h3>
 * <ul>
 *   <li>{@code OtaPushService} / {@code StandaloneMirrorService} 的「点通知回 App」用
 *       {@link #EXTRA_TAB} 指定初始 tab；</li>
 *   <li>远程驱动参数（{@code auto} / {@code file} / {@code path} 等）由宿主转发给当前 Fragment 的
 *       {@link BaseTabFragment#onExternalIntent(Intent)}。</li>
 * </ul>
 */
public final class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    /** 外部入口（通知 / Service PendingIntent）用它指定要打开的 tab（传 {@code R.id.nav_*}）。 */
    public static final String EXTRA_TAB = "extra_tab";

    /** 记住当前 tab，供配置变更后恢复。 */
    private static final String KEY_TAB = "main.current_tab";

    private static final int[] TABS = {
            R.id.nav_ride, R.id.nav_ota, R.id.nav_wallpaper, R.id.nav_mirror, R.id.nav_diag
    };

    private NavigationBarView navBar;
    private View content;
    private final Map<Integer, Fragment> pages = new LinkedHashMap<>();
    private int currentTab = R.id.nav_ride;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.applyWindow(this);
        // ★ 手势区刷成导航栏同色（colorSurfaceContainer）⇒ 栏+手势区连成一整块，导航栏才沉浸。
        //   必须在 applyWindow 之后（后者刚把导航条设成透明，这里覆盖回同色）。
        EdgeToEdge.applyNavBarTint(this);
        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);

        // 竖屏底栏 / 横屏 rail，只会存在一个
        View bottom = findViewById(R.id.bottom_nav);
        View rail = findViewById(R.id.nav_rail);
        if (bottom instanceof NavigationBarView) {
            navBar = (NavigationBarView) bottom;
            EdgeToEdge.applyBottomMargin(navBar);
            EdgeToEdge.applyBottomAvoid(content, navBar);
        } else if (rail instanceof NavigationBarView) {
            navBar = (NavigationBarView) rail;
            // rail 在侧边：内容左侧由布局本身让位，这里只补系统顶部（状态栏）与底部（手势区）
            EdgeToEdge.applyTop(rail);
            EdgeToEdge.applyBottomAvoid(content, null);
        }

        // ★ 恢复优先：转屏回来沿用重建前的 tab；只有真正冷启动才看 Intent。
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.nav_ride);
        } else {
            currentTab = getIntent().getIntExtra(EXTRA_TAB, R.id.nav_ride);
        }
        if (!isTab(currentTab)) {
            currentTab = R.id.nav_ride;
        }

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            for (int id : TABS) {
                pages.put(id, newFragment(id));
            }
            FragmentTransaction ft = fm.beginTransaction();
            for (Map.Entry<Integer, Fragment> e : pages.entrySet()) {
                ft.add(R.id.content, e.getValue(), tagFor(e.getKey()));
                if (e.getKey() != currentTab) {
                    ft.hide(e.getValue());
                }
            }
            ft.commitNow();
        } else {
            // ★ 重建：取回 FragmentManager 已恢复的实例（缺则补 add），
            //   再把可见性校准到「只显示当前 tab」。
            FragmentTransaction ft = fm.beginTransaction();
            for (int id : TABS) {
                Fragment f = fm.findFragmentByTag(tagFor(id));
                if (f == null) {
                    f = newFragment(id);
                    ft.add(R.id.content, f, tagFor(id));
                }
                pages.put(id, f);
            }
            for (Map.Entry<Integer, Fragment> e : pages.entrySet()) {
                if (e.getKey() == currentTab) {
                    ft.show(e.getValue());
                } else {
                    ft.hide(e.getValue());
                }
            }
            ft.commitNow();
        }

        if (navBar != null) {
            navBar.setSelectedItemId(currentTab);
            navBar.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == currentTab) {
                    return true;
                }
                switchTo(id);
                return true;
            });
        }

        // 初始页的「切回本页」逻辑（PING / 刷新等）在其视图建好后触发一次。
        // ★ 必须 post：commitNow() 刚跑完时 Fragment 的 onViewCreated 可能还没执行，
        //   此时调 onPageShown() 会摸到 null 的视图/prefs。
        content.post(() -> {
            BaseTabFragment init = currentFragment();
            if (init != null) {
                init.onPageShown();
            }
        });
        // 外部入口带来的参数（auto push 等）只在冷启动转发一次（转屏不重放，避免重复触发推送）
        if (savedInstanceState == null) {
            content.post(() -> forwardIntent(getIntent()));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_TAB, currentTab);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int tab = intent.getIntExtra(EXTRA_TAB, -1);
        if (isTab(tab) && tab != currentTab) {
            if (navBar != null) {
                navBar.setSelectedItemId(tab);   // 触发 listener → switchTo
            } else {
                switchTo(tab);
            }
        }
        forwardIntent(intent);
    }

    private void switchTo(int id) {
        Fragment prev = pages.get(currentTab);
        Fragment next = pages.get(id);
        if (prev == next || next == null) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        if (prev != null) {
            ft.hide(prev);
        }
        ft.show(next);
        ft.commitNow();
        currentTab = id;
        if (prev instanceof BaseTabFragment) {
            ((BaseTabFragment) prev).onPageHidden();
        }
        if (next instanceof BaseTabFragment) {
            ((BaseTabFragment) next).onPageShown();
        }
    }

    private BaseTabFragment currentFragment() {
        Fragment f = pages.get(currentTab);
        return f instanceof BaseTabFragment ? (BaseTabFragment) f : null;
    }

    private void forwardIntent(Intent intent) {
        BaseTabFragment f = currentFragment();
        if (f != null && intent != null) {
            f.onExternalIntent(intent);
        }
    }

    private static Fragment newFragment(int id) {
        if (id == R.id.nav_ride) return new RideFragment();
        if (id == R.id.nav_ota) return new OtaFragment();
        if (id == R.id.nav_wallpaper) return new WallpaperFragment();
        if (id == R.id.nav_mirror) return new MirrorFragment();
        return new DiagFragment();
    }

    private static boolean isTab(int id) {
        return id == R.id.nav_ride || id == R.id.nav_ota || id == R.id.nav_wallpaper
                || id == R.id.nav_mirror || id == R.id.nav_diag;
    }

    private static String tagFor(int id) {
        if (id == R.id.nav_ride) return "ride";
        if (id == R.id.nav_ota) return "ota";
        if (id == R.id.nav_wallpaper) return "wallpaper";
        if (id == R.id.nav_mirror) return "mirror";
        return "diag";
    }
}
