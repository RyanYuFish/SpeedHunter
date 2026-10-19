package com.github.zeehospeedhunter.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.github.zeehospeedhunter.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单宿主 Activity —— 整个 App 只有一个入口、常驻底栏，5 个 tab 都是 Fragment。
 *
 * <p>★ 这是彻底修复底栏抖动 / 无波纹 / 快速点击闪烁的根因方案：
 * 底栏只在此处创建一次，切换 tab 时只换 Fragment（{@code show()/hide()}），
 * 底栏实例<b>不被重建</b>，于是 Material You 的 active indicator 动画、点击波纹（ripple）、
 * 首次点击的指示器测量都连续且稳定。</p>
 *
 * <h3>外部入口路由</h3>
 * <ul>
 *   <li>{@link OtaPushService} / {@link com.github.zeehospeedhunter.mirror.StandaloneMirrorService}
 *       的「点通知回 App」用 {@link #EXTRA_TAB} 指定初始 tab；</li>
 *   <li>远程驱动参数（{@code auto} / {@code file} / {@code path} 等）由宿主转发给当前 Fragment 的
 *       {@link BaseTabFragment#onExternalIntent(Intent)}。</li>
 * </ul>
 */
public final class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    /** 外部入口（通知 / Service PendingIntent）用它指定要打开的 tab（传 {@code R.id.nav_*}）。 */
    public static final String EXTRA_TAB = "extra_tab";

    private BottomNavigationView nav;
    private View content;
    private final Map<Integer, Fragment> pages = new LinkedHashMap<>();
    private int currentTab = R.id.nav_ride;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.applyWindow(this);
        // ★ 手势区刷成底栏同色（colorSurfaceContainer）⇒ 底栏+手势区连成一整块，导航栏才沉浸。
        //   必须在 applyWindow 之后（后者刚把导航条设成透明，这里覆盖回同色）。
        EdgeToEdge.applyNavBarTint(this);
        setContentView(R.layout.activity_main);

        nav = findViewById(R.id.bottom_nav);
        content = findViewById(R.id.content);
        EdgeToEdge.applyBottomMargin(nav);
        EdgeToEdge.applyBottomAvoid(content, nav);

        currentTab = getIntent().getIntExtra(EXTRA_TAB, R.id.nav_ride);
        if (!isTab(currentTab)) {
            currentTab = R.id.nav_ride;
        }

        pages.put(R.id.nav_ride, new RideFragment());
        pages.put(R.id.nav_ota, new OtaFragment());
        pages.put(R.id.nav_wallpaper, new WallpaperFragment());
        pages.put(R.id.nav_mirror, new MirrorFragment());
        pages.put(R.id.nav_diag, new DiagFragment());

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        for (Map.Entry<Integer, Fragment> e : pages.entrySet()) {
            ft.add(R.id.content, e.getValue(), tagFor(e.getKey()));
            if (e.getKey() != currentTab) {
                ft.hide(e.getValue());
            }
        }
        ft.commitNow();

        nav.setSelectedItemId(currentTab);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == currentTab) {
                return true;
            }
            switchTo(id);
            return true;
        });

        // 初始页的「切回本页」逻辑（PING / 刷新等）在其视图建好后触发一次。
        // ★ 必须 post：commitNow() 刚跑完时 Fragment 的 onViewCreated 可能还没执行，
        //   此时调 onPageShown() 会摸到 null 的视图/prefs。
        content.post(() -> {
            BaseTabFragment init = currentFragment();
            if (init != null) {
                init.onPageShown();
            }
        });
        // 外部入口带来的参数（auto push 等）转发给初始页（同样等视图就绪）
        content.post(() -> forwardIntent(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int tab = intent.getIntExtra(EXTRA_TAB, -1);
        if (isTab(tab) && tab != currentTab) {
            nav.setSelectedItemId(tab);   // 触发上面的 listener → switchTo
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
