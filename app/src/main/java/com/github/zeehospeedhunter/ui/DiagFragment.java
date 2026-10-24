package com.github.zeehospeedhunter.ui;

import com.github.zeehospeedhunter.R;

/**
 * 车辆诊断板块 —— 当前为「施工中」占位。
 *
 * <p>诊断界面与底层引擎（原 {@code diag/} 包、{@code VehicleLink}、{@code CarNetController}、
 * {@code P2pPanel}）已从本版本移除；后续版本将合并一次较大的改动后重新开放。
 * 这里只保留板块入口与占位文案，避免空 tab。功能细节见
 * {@code docs/features/diagnostics.md}（状态：施工中）。</p>
 */
public final class DiagFragment extends BaseTabFragment {

    @Override
    protected int getLayoutId() {
        return R.layout.activity_diag;
    }
}
