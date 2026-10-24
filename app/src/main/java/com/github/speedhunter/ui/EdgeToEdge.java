package com.github.speedhunter.ui;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * edge-to-edge 的统一处理：内容画到状态栏 / 导航栏底下，栏本身透明。
 *
 * <h3>为什么抽出来</h3>
 * <p>原先只有 {@link RideActivity} 调，另外两页没调，
 * 于是<b>状态栏图标反色不对</b>：主页在浅色底上是深色图标（对），
 * 但 {@link OtaActivity} / {@link TuneActivity} 的浅色背景上仍是白色图标，
 * 白底白字 ⇒ 时间和电量「消失」了。</p>
 *
 * <p>根因是 {@code windowLightStatusBar} 这个 flag <b>是按窗口记的</b>，
 * 主题里 {@code android:statusBarColor=transparent} 只管颜色不管图标明暗，
 * 所以每个 Activity 都要显式设一次。</p>
 *
 * <h3>为什么还要处理 insets</h3>
 * <p>栏透明之后内容会顶到栏底下，标题和 Tab 就被状态栏压住了。
 * {@link #apply} 把系统栏占位以 padding 形式加回容器顶部，
 * 各页面在 {@code setContentView} 之后调一次即可。</p>
 */
public final class EdgeToEdge {

    private EdgeToEdge() {
    }

    /**
     * 开沉浸 + 修状态栏图标明暗。
     *
     * @param view 根内容视图（一般是 {@code setContentView} 传进去的那个）；
     *             传 {@code null} 表示只设窗口、insets 由各页面自己挂
     *             （{@link #applyTop} / {@link #applyBottom}）
     */
    public static void setup(android.app.Activity activity, android.view.View view) {
        applyWindow(activity);
        apply(view);
    }

    /**
     * 只做窗口设置（透明栏 + 图标明暗），不碰任何 view。
     *
     * <p>必须在 {@code setContentView} <b>之前</b>或之后调都行；但给 view 挂 insets
     * 只能在 {@code setContentView} 之后 —— 顺序反了 {@code findViewById} 拿到 null。</p>
     */
    public static void applyWindow(android.app.Activity activity) {
        try {
            android.view.Window window = activity.getWindow();
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setNavigationBarContrastEnforced(false);
            }
            // 按当前日/夜模式决定图标明暗（M3 DayNight 跟随系统）
            boolean night = (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            androidx.core.view.WindowInsetsControllerCompat c =
                    WindowCompat.getInsetsController(window, window.getDecorView());
            c.setAppearanceLightStatusBars(!night);
            c.setAppearanceLightNavigationBars(!night);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把系统导航条（手势区）刷成与底栏同一个色（{@code colorSurfaceContainer}）——沉浸式观感。
     *
     * <p>★ 为什么必须补这一段：{@link #applyWindow} 为了 edge-to-edge 把导航条设成<b>透明</b>，
     * 手势区于是直接「透」出窗口底色（{@code ?attr/colorSurface}）；而常驻底栏自身的背景是
     * {@code colorSurfaceContainer}。两者是<b>不同的 tonal 色</b>，于是底栏下方那条手势区
     * 与底栏有色差，看起来就像「导航栏没沉浸 / 贴了条异色带」。</p>
     *
     * <p>底栏用 {@link #applyBottomMargin} 被<b>抬到手势区上方</b>（背景正好停在 tab 内容
     * 底部，不会「遮罩层比 tab 高一截」），所以手势区那一段没人刷色 ⇒ 只能由窗口的导航条
     * 颜色来填。把它填成与底栏一致的 surface 色，底栏 + 手势区就拼成一整块连续 surface，
     * 达成沉浸观感。</p>
     *
     * <p>必须在 {@link #applyWindow} <b>之后</b>调（后者刚把导航条设成透明，会被这里覆盖）；
     * 且只改颜色，不动 {@code setAppearanceLightNavigationBars} 决定的图标明暗。</p>
     */
    public static void applyNavBarTint(android.app.Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            int barColor = com.google.android.material.color.MaterialColors.getColor(
                    activity, com.google.android.material.R.attr.colorSurfaceContainer,
                    Color.TRANSPARENT);
            activity.getWindow().setNavigationBarColor(barColor);
            // API29+ 系统会给导航条叠一层默认对比度蒙版，把同色填满又蒙一层灰 ⇒ 去掉。
            if (Build.VERSION.SDK_INT >= 29) {
                activity.getWindow().setNavigationBarContrastEnforced(false);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把系统栏高度补成根容器的顶部 padding。
     *
     * <p><b>为什么用 padding 而不是 margin</b>：margin 会把背景一起推出去，
     * 栏那块的底色就露出来了；padding 是「内容内缩」，栏底下仍是页面自己的底色。</p>
     */
    public static void apply(android.view.View view) {
        if (view == null) {
            return;
        }
        try {
            final int top = view.getPaddingTop();
            final int bottom = view.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                androidx.core.graphics.Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(v.getPaddingLeft(),
                        top + bars.top,
                        v.getPaddingRight(),
                        bottom + bars.bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(view);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只补<b>顶部</b> inset（状态栏 + 刘海）—— 给顶栏 / Toolbar 用，
     * 免得标题飘到状态栏上去。
     *
     * <p>★ 以前 {@link RideActivity} 在 {@code setContentView} <b>之前</b>调
     * {@code findViewById}，拿到 null，insets 谁都没挂上，标题于是压在状态栏上。</p>
     */
    public static void applyTop(android.view.View view) {
        if (view == null) {
            return;
        }
        try {
            final int base = view.getPaddingTop();
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                int top = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout()).top;
                v.setPadding(v.getPaddingLeft(), base + top,
                        v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            ViewCompat.requestApplyInsets(view);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只补<b>底部</b> inset（导航栏）—— 给底栏 / 内容区用。
     *
     * @param extra 额外再加多少（例如内容区要避开底栏高度时传底栏高度）
     */
    public static void applyBottom(android.view.View view, int extra) {
        if (view == null) {
            return;
        }
        try {
            final int base = view.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                int bottom = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                        v.getPaddingRight(), base + bottom + extra);
                return insets;
            });
            ViewCompat.requestApplyInsets(view);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把导航栏高度补成<b>底部外边距</b> —— 给底栏用。
     *
     * <p>★ 为什么是 margin 不是 padding：padding 会把底栏<b>背景</b>一起撑到屏幕最底，
     * 于是手势区那截也是底栏底色 ⇒ 看起来「遮罩层比 tab 高一截」。
     * margin 只把整个底栏抬到手势区上方，背景正好停在 tab 内容底部。</p>
     */
    public static void applyBottomMargin(android.view.View view) {
        if (view == null) {
            return;
        }
        try {
            final int base = bottomMargin(view);
            final int[] latch = {-1};
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                int bottom = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout()).bottom;
                // ★ 锁存非零值：切 tab / activity 切换瞬间，窗口 insets 可能瞬时报 0
                //   再补成真实手势区高度；若每次都 setLayoutParams，底栏会被「先下缩再弹回」
                //   抽动一下。过渡期的 0 沿用上一次非零值，底栏高度稳定不抖。
                if (bottom == 0 && latch[0] > 0) {
                    bottom = latch[0];
                } else if (bottom > 0) {
                    latch[0] = bottom;
                }
                int want = base + bottom;
                if (bottomMargin(v) != want) {
                    setBottomMargin(v, want);
                }
                return insets;
            });
            ViewCompat.requestApplyInsets(view);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 内容区底部 padding = 「手势区高度 + 底栏实际高度」。
     *
     * <p>★ 不能只在 insets 那一次算：首派常在底栏布局之前，此时
     * {@code avoid.getHeight()} 还是 0 ⇒ 内容会漏进底栏下方的手势区。
     * 所以额外挂一个布局监听，底栏一有高度就重算。</p>
     *
     * <p>★ 两次写入都要比对现值：值没变就别 setPadding —— 否则
     * 「setPadding → requestLayout → 布局监听 → setPadding」会一直转。</p>
     */
    public static void applyBottomAvoid(android.view.View view, android.view.View avoid) {
        if (view == null) {
            return;
        }
        try {
            // ★ 必须 clipToPadding=true：光加 padding 不管用 ——
            //   滚动容器设了 clipToPadding=false 时，子项会被画进 padding 区，
            //   于是内容照样出现在底栏下方的手势区里。true 表示「裁到 padding 内边缘」，
            //   内容最多画到底栏上沿就停，底栏与手势区都干净。
            if (view instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) view).setClipToPadding(true);
            }
            final int base = view.getPaddingBottom();
            final int[] inset = new int[1];
            final Runnable apply = () -> {
                int want = base + inset[0] + (avoid == null ? 0 : avoid.getHeight());
                if (view.getPaddingBottom() != want) {
                    view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                            view.getPaddingRight(), want);
                }
            };
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                int b = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout()).bottom;
                // ★ 同 applyBottomMargin：锁存非零值，过渡期瞬时报 0 时内容不下缩，
                //   否则内容区跟着底栏一起抖。
                if (!(b == 0 && inset[0] > 0)) {
                    inset[0] = b > 0 ? b : inset[0];
                }
                apply.run();
                return insets;
            });
            if (avoid != null) {
                avoid.getViewTreeObserver().addOnGlobalLayoutListener(apply::run);
            }
            ViewCompat.requestApplyInsets(view);
        } catch (Throwable ignored) {
        }
    }

    private static int bottomMargin(android.view.View view) {
        android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
        return lp instanceof android.view.ViewGroup.MarginLayoutParams
                ? ((android.view.ViewGroup.MarginLayoutParams) lp).bottomMargin : 0;
    }

    private static void setBottomMargin(android.view.View view, int margin) {
        android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
            ((android.view.ViewGroup.MarginLayoutParams) lp).bottomMargin = margin;
            view.setLayoutParams(lp);
        }
    }

    /**
     * 同上，但「额外避开的高度」在 insets 派发时<b>实时读</b>（传要避开的那
     * 个 View，例如底栏）——  onCreate 里拿不到它的高度（还没布局），
     * 写死 0 的话最后一行会被底栏压住。
     *
     * @param avoid 要避开高度的 View；传 {@code null} 等价于 {@link #applyBottom(View, int)}
     *              的 {@code extra = 0}
     */
    public static void applyBottom(android.view.View view, android.view.View avoid) {
        if (view == null) {
            return;
        }
        try {
            final int base = view.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                int bottom = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout()).bottom;
                int extra = avoid == null ? 0 : avoid.getHeight();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                        v.getPaddingRight(), base + bottom + extra);
                return insets;
            });
            ViewCompat.requestApplyInsets(view);
        } catch (Throwable ignored) {
        }
    }
}
