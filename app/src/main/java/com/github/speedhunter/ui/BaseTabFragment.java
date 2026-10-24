package com.github.speedhunter.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.fragment.app.Fragment;

/**
 * 底栏 tab 页的统一基类。
 *
 * <p>★ 重构为「单宿主 Activity + 常驻底栏 + 5 个 Fragment」后，底栏
 * （{@code BottomNavigationView}）只存在于宿主 {@link MainActivity}，永不随 tab 切换而重建 ——
 * 这正是 Material Design 的规范范式，也从根本上消除了原来「每切一个 tab 重建一份底栏」带来的
 * 三个症状：① 没有点击波纹（ripple 被瞬间的 Activity 切换打断）；
 * ② 快速连点闪烁（底栏反复重建）；③ 冷启动首次点击图标抖动（新建底栏首帧重新测量 active indicator）。</p>
 *
 * <p>宿主用 {@code show()/hide()} 切换 Fragment（而非 {@code replace()}），
 * 因此 Fragment 的视图与状态跨 tab 切换保留。与「切 tab 才重跑」相关的逻辑
 * （如回到本页发一次 hook PING、刷新 Wi-Fi / P2P 状态）移到 {@link #onPageShown()}，
 * 宿主在每次选中本页时调用它；离开本页时调用 {@link #onPageHidden()}。</p>
 */
public abstract class BaseTabFragment extends Fragment {

    /** 本页对应的布局（即原先各 Activity 的 {@code activity_xxx.xml}，已移除其中的底栏 include）。 */
    @LayoutRes
    protected abstract int getLayoutId();

    @Override
    public final View onCreateView(LayoutInflater inflater, ViewGroup container,
                                   Bundle savedInstanceState) {
        return inflater.inflate(getLayoutId(), container, false);
    }

    /** 本页成为可见 tab 时调用（初始 + 每次选中）。替代原 Activity 的 onResume 中的「切回本页」逻辑。 */
    public void onPageShown() {
    }

    /** 本页被切走（另一个 tab 选中）时调用。 */
    public void onPageHidden() {
    }

    /** 宿主从外部入口（通知 / Service 的 PendingIntent / 远程驱动）转发来的 Intent。 */
    public void onExternalIntent(Intent intent) {
    }
}
