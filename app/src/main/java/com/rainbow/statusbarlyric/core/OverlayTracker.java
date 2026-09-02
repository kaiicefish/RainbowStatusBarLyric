package com.rainbow.statusbarlyric.core;

import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 记录「通过 WindowManager 添加的<b>真正的悬浮窗</b>」根节点。
 * <p>
 * <b>判定标准：窗口类型落在系统窗口区间 [2000, 2999]。</b>
 * <p>
 * 这个区间对应 {@code FIRST_SYSTEM_WINDOW ~ LAST_SYSTEM_WINDOW}，
 * 全部需要 SYSTEM_ALERT_WINDOW 权限才能添加 —— 也就是俗称的悬浮窗。
 * 而 Activity(1)、Dialog(2)、PopupWindow(1000+) 都 <b>小于 2000</b>，
 * 天然被排除，所以 App 界面里的任何文字都不可能被误伤。
 * <p>
 * 相比逐个枚举 type 常量，用区间判断更稳：
 * 无论网易云用 TYPE_APPLICATION_OVERLAY 还是 TYPE_PHONE / TYPE_TOAST，
 * 都落在区间内，不需要跟着它的实现改代码。
 */
public final class OverlayTracker {

    private static final WeakHashMap<View, Boolean> ROOTS = new WeakHashMap<>();

    private OverlayTracker() {
    }

    /** 是否属于需要悬浮窗权限的系统窗口 */
    public static boolean isOverlayWindowType(int type) {
        return type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                && type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW;
    }

    /**
     * 尝试记录一个悬浮窗根节点。
     *
     * @return 是否是真正的悬浮窗（true 才会被上色）
     */
    public static synchronized boolean track(View root) {
        if (root == null) {
            return false;
        }
        Object lp = root.getLayoutParams();
        if (!(lp instanceof WindowManager.LayoutParams)) {
            // 拿不到窗口参数，无法确认，宁可放过也不误伤
            return false;
        }
        if (!isOverlayWindowType(((WindowManager.LayoutParams) lp).type)) {
            return false;
        }
        ROOTS.put(root, Boolean.TRUE);
        return true;
    }

    /** 从 LayoutParams 里读出窗口类型，读不到返回 -1 */
    public static int typeOf(View root) {
        if (root == null) {
            return -1;
        }
        Object lp = root.getLayoutParams();
        if (lp instanceof WindowManager.LayoutParams) {
            return ((WindowManager.LayoutParams) lp).type;
        }
        return -1;
    }

    public static synchronized void untrack(View root) {
        if (root != null) {
            ROOTS.remove(root);
        }
    }

    /** 该 View 是否位于某个已记录的悬浮窗内 */
    public static boolean isInOverlay(View view) {
        if (view == null) {
            return false;
        }
        View root = rootOf(view);
        if (root == null) {
            return false;
        }
        synchronized (OverlayTracker.class) {
            return ROOTS.containsKey(root);
        }
    }

    /** 向上找到窗口根节点：父节点不再是 View 时的最后一个 View */
    private static View rootOf(View view) {
        View current = view;
        ViewParent parent = current.getParent();
        while (parent instanceof View) {
            current = (View) parent;
            parent = current.getParent();
        }
        return current;
    }

    public static synchronized List<View> snapshotRoots() {
        return new ArrayList<>(ROOTS.keySet());
    }

    public static synchronized int trackedCount() {
        return ROOTS.size();
    }
}
