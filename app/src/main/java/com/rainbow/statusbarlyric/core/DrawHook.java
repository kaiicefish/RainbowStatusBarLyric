package com.rainbow.statusbarlyric.core;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.rainbow.statusbarlyric.view.RainbowController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 绘制栈：回答「当前这行字是哪个 View 画的」。
 * <p>
 * <b>为什么不能直接 hook {@code View.draw}：</b>
 * Java 是虚拟方法分派。自绘 View 通常<b>重写</b>了 {@code draw(Canvas)}，
 * 这时 {@code view.draw(canvas)} 直接分派到子类实现，
 * {@code View.draw} 的方法体一次都不会执行 —— 只 hook 基类会完全失效。
 * 这正是上一版桌面歌词没反应的根本原因。
 * <p>
 * 正确做法：拿到具体 View 实例后，<b>从它的类沿继承链往上找第一个真正声明了
 * {@code draw(Canvas)} 的方法</b>，hook 那个。子类重写了就 hook 子类的。
 * <p>
 * {@code Canvas} 同理：硬件加速下真实对象是 {@code RecordingCanvas}，
 * 它重写了 {@code drawTextRun}，所以必须在拿到 canvas 实例后
 * <b>动态 hook 它的整个继承链</b>，而不是事先只 hook {@code Canvas}。
 */
public final class DrawHook {

    public interface Callback {
        /** 某个 View 开始绘制；isRoot 表示它是这一帧的根节点 */
        void onDrawStart(View view, boolean isRoot);

        void onDrawEnd(View view, boolean isRoot);

        /** args 是 drawText* 的入参，current 是栈顶 View */
        void onDrawText(Object[] args, View current);

        /**
         * 返回一个 canvas 替代真实的 canvas。
         * 不需要离屏就直接返回 real，想做后处理就返回离屏 canvas ——
         * View 照常画完，我们在 {@link #onComposite} 里再加工后贴回真实 canvas。
         */
        Canvas onReplaceCanvas(View view, Canvas real, boolean isRoot);

        /** 一帧绘制结束，把离屏内容加工后合成回真实 canvas；real 是真实 canvas */
        void onComposite(View view, Canvas real, boolean isRoot);
    }

    private static final int MAX_DEPTH = 10;
    private static final long RESCAN_INTERVAL_MS = 1500L;

    /** 绘制栈（线程隔离，处理 ViewGroup -> child 嵌套） */
    private static final ThreadLocal<ArrayList<View>> STACK =
            new ThreadLocal<ArrayList<View>>() {
                @Override
                protected ArrayList<View> initialValue() {
                    return new ArrayList<>();
                }
            };
    /** 记录「这一帧的根节点」，用于成对弹出 */
    private static final ThreadLocal<ArrayList<View>> ROOT_MARK =
            new ThreadLocal<ArrayList<View>>() {
                @Override
                protected ArrayList<View> initialValue() {
                    return new ArrayList<>();
                }
            };
    /**
     * 与 STACK 平行的「真实 canvas」栈。
     * before 里可能把 args[0] 换成离屏 canvas，after 里得靠它还原。
     */
    private static final ThreadLocal<ArrayList<Canvas>> REAL_STACK =
            new ThreadLocal<ArrayList<Canvas>>() {
                @Override
                protected ArrayList<Canvas> initialValue() {
                    return new ArrayList<>();
                }
            };

    private static final Set<Method> HOOKED_DRAW =
            Collections.synchronizedSet(new HashSet<Method>());
    private static final Set<Class<?>> HOOKED_CANVAS =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    private static volatile Callback callback;
    private static volatile boolean baseHooked;
    private static volatile boolean rescanScheduled;
    private static Handler mainHandler;

    private DrawHook() {
    }

    public static void setCallback(Callback cb) {
        callback = cb;
    }

    /** 当前正在绘制的 View（栈顶），不在绘制中返回 null */
    public static View current() {
        ArrayList<View> stack = STACK.get();
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    /**
     * 注册一个悬浮窗根节点：hook 它的绘制链路，并周期性重扫
     * （歌词 View 可能晚于 addView 才加进树里）。
     */
    public static void register(View root) {
        if (root == null) {
            return;
        }
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        // 基类兜底：任何「没重写 draw」的 View 都会走到这里
        if (!baseHooked) {
            baseHooked = true;
            ensureDrawHooked(View.class);
        }
        scanTree(root, 0);
        startRescan();
    }

    /** 周期性重扫已注册的悬浮窗，捕获后加进来的子 View */
    private static void startRescan() {
        if (mainHandler == null || rescanScheduled) {
            return;
        }
        rescanScheduled = true;
        mainHandler.postDelayed(rescanTask, RESCAN_INTERVAL_MS);
    }

    private static final Runnable rescanTask = new Runnable() {
        @Override
        public void run() {
            boolean keepGoing = false;
            try {
                List<View> roots = OverlayTracker.snapshotRoots();
                for (View root : roots) {
                    if (root == null) {
                        continue;
                    }
                    if (root.isAttachedToWindow()) {
                        scanTree(root, 0);
                        keepGoing = true;
                    } else {
                        // 窗口已移除：释放离屏位图和粒子，避免残留状态拖住 View。
                        OverlayTracker.untrack(root);
                        LyricRenderer.release(root);
                    }
                }
            } catch (Throwable ignored) {
                keepGoing = true;
            } finally {
                // 没有悬浮窗就停掉轮询，下次 register 会重新拉起。
                // 一直空转会白白占用主线程消息队列。
                if (keepGoing && mainHandler != null) {
                    mainHandler.postDelayed(rescanTask, RESCAN_INTERVAL_MS);
                } else {
                    rescanScheduled = false;
                }
            }
        }
    };

    /**
     * 遍历 View 树，给每个 View 实际会调用的 draw 方法挂钩。
     * 只 hook 非 framework 的类 —— framework 的靠基类兜底，
     * 免得全局 hook 拖慢绘制。
     */
    private static void scanTree(View view, int depth) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }
        String name = view.getClass().getName();
        if (!name.startsWith("android.") && !name.startsWith("androidx.")
                && !name.startsWith("java.") && !name.startsWith("kotlin.")
                && !name.startsWith("com.google.")) {
            ensureDrawHooked(view.getClass());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scanTree(group.getChildAt(i), depth + 1);
            }
        }
    }

    /** hook 该类继承链上第一个真正声明 draw(Canvas) 的方法 */
    private static void ensureDrawHooked(Class<?> clazz) {
        Method method = findDrawMethod(clazz);
        if (method == null) {
            return;
        }
        if (!HOOKED_DRAW.add(method)) {
            return; // 同一个方法已经 hook 过
        }
        method.setAccessible(true);
        try {
            XposedBridge.hookMethod(method, drawCallback);
            XLog.i("hooked draw -> " + method.getDeclaringClass().getName());
        } catch (Throwable t) {
            XLog.e("hook draw failed " + method.getDeclaringClass().getName() + ": " + t);
        }
    }

    /** 从 clazz 往上找，返回第一个声明了 draw(Canvas) 的方法 */
    private static Method findDrawMethod(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod("draw", Canvas.class);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static final XC_MethodHook drawCallback = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object self = param.thisObject;
            if (!(self instanceof View)) {
                return;
            }
            View view = (View) self;
            ArrayList<View> stack = STACK.get();
            boolean isRoot = stack.isEmpty();
            stack.add(view);
            ROOT_MARK.get().add(isRoot ? view : null);

            Object canvasArg = param.args.length > 0 ? param.args[0] : null;
            final Canvas realCanvas = canvasArg instanceof Canvas ? (Canvas) canvasArg : null;
            REAL_STACK.get().add(realCanvas);
            if (canvasArg instanceof Canvas) {
                // 动态 hook canvas 真实实现类的整个继承链，
                // 覆盖 RecordingCanvas 等子类重写的 drawTextRun
                ensureCanvasHooked((Canvas) canvasArg);
            }
            Callback cb = callback;
            if (cb != null) {
                cb.onDrawStart(view, isRoot);
                if (canvasArg instanceof Canvas) {
                    // 让 View 画到离屏 canvas 上，结束后再合成
                    Canvas replacement = cb.onReplaceCanvas(view, (Canvas) canvasArg, isRoot);
                    if (replacement != null && replacement != canvasArg) {
                        param.args[0] = replacement;
                    }
                }
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object self = param.thisObject;
            ArrayList<View> stack = STACK.get();
            ArrayList<View> marks = ROOT_MARK.get();
            boolean isRoot = false;
            if (!marks.isEmpty()) {
                View mark = marks.remove(marks.size() - 1);
                isRoot = mark != null && mark == self;
            }
            if (!stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            }
            Canvas realCanvas = null;
            ArrayList<Canvas> reals = REAL_STACK.get();
            if (!reals.isEmpty()) {
                realCanvas = reals.remove(reals.size() - 1);
            }
            if (self instanceof View) {
                Callback cb = callback;
                if (cb != null) {
                    // 合成放在 onDrawEnd 之前：确认这一帧真的画完了
                    cb.onComposite((View) self, (Canvas) realCanvas, isRoot);
                    cb.onDrawEnd((View) self, isRoot);
                }
            }
            // 把 canvas 还原成调用方传进来的那个，避免影响外层逻辑
            if (realCanvas != null && param.args.length > 0
                    && param.args[0] != realCanvas) {
                param.args[0] = realCanvas;
            }
        }
    };

    /**
     * hook 某个 Canvas 继承链上的所有 drawText* 重载。
     * <p>
     * 一般由内部在拿到真实 canvas 时自动调用。
     * 离屏 canvas（software 的 {@code Canvas} 基类实例）需要外部显式调一次，
     * 否则画到离屏上的文字挂不上渐变 —— 见 {@code LyricRenderer#begin}。
     */
    public static void ensureCanvasHooked(Canvas canvas) {
        if (canvas == null) {
            return;
        }
        Class<?> current = canvas.getClass();
        while (current != null && current != Object.class) {
            hookCanvasClass(current);
            current = current.getSuperclass();
        }
    }

    private static void hookCanvasClass(Class<?> canvasClass) {
        if (canvasClass == null || !Canvas.class.isAssignableFrom(canvasClass)) {
            return;
        }
        if (!HOOKED_CANVAS.add(canvasClass)) {
            return;
        }
        int count = 0;
        for (Method method : canvasClass.getDeclaredMethods()) {
            if (!method.getName().startsWith("drawText")) {
                continue;
            }
            boolean hasPaint = false;
            for (Class<?> type : method.getParameterTypes()) {
                if (type == Paint.class) {
                    hasPaint = true;
                    break;
                }
            }
            if (!hasPaint) {
                continue;
            }
            method.setAccessible(true);
            try {
                XposedBridge.hookMethod(method, textCallback);
                count++;
            } catch (Throwable ignored) {
            }
        }
        if (count > 0) {
            XLog.i("hooked " + canvasClass.getSimpleName() + " drawText x" + count);
        }
    }

    private static final XC_MethodHook textCallback = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Callback cb = callback;
                if (cb == null) {
                    return;
                }
                View current = current();
                if (current == null) {
                    return;
                }
                cb.onDrawText(param.args, current);
            } catch (Throwable ignored) {
            }
        }
    };

    /** 是否被 RainbowController 接管（避免双重上色） */
    static boolean handledByController(View view) {
        return view instanceof TextView && RainbowController.isAttached(view);
    }
}
