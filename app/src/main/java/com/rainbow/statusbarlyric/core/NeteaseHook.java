package com.rainbow.statusbarlyric.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.rainbow.statusbarlyric.view.RainbowController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 网易云音乐 Hook：接管它自己的桌面歌词 / 状态栏歌词配色。
 * <p>
 * <b>只 hook 网易云音乐，不碰系统框架，不自己画悬浮窗。</b>
 * <p>
 * 两条上色路径，覆盖歌词的两种实现：
 * <ol>
 *   <li><b>TextView 路径</b>（{@link RainbowController}）：hook {@code setText}，
 *       剥掉逐字染色 span 后给 Paint 挂渐变。</li>
 *   <li><b>自绘 View 路径</b>（{@link PaintTinter} + {@link DrawHook}）：
 *       hook 实际的 {@code draw} 与 {@code Canvas.drawText}，
 *       直接给绘制用的 Paint 挂渐变。桌面歌词很可能走这条。</li>
 * </ol>
 * <p>
 * <b>上色判定统一走 {@link OverlayTracker}，只认窗口类型在 [2000,2999]
 * 的系统悬浮窗。</b>Activity(1)/Dialog(2)/PopupWindow(1000+) 全部小于 2000，
 * 天然排除，界面里的文字不会被误伤。
 */
public final class NeteaseHook {

    /** 诊断日志里的键名，便于排查「窗口类型没被认出来」 */
    private static final String DIAG_SEEN_TYPES = "seen_window_types";
    private static final String DIAG_LYRIC_TYPE = "lyric_window_type";
    private static final String DIAG_LYRIC_VIEW = "lyric_view_class";

    /** 见过的所有窗口类型，去重后写进诊断日志 */
    private static final Set<Integer> SEEN_TYPES =
            Collections.synchronizedSet(new HashSet<Integer>());

    private static volatile Config config;
    private static volatile boolean hooked;

    private NeteaseHook() {
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam, Config cfg) {
        if (hooked) {
            return;
        }
        hooked = true;
        config = cfg;

        PaintTinter.install(cfg);
        updateTiltSensor(cfg);
        hookAddView(lpparam);
        hookTextViewSetText();
        hookLyricSourceByName(lpparam);
        registerConfigReceiver();

        XLog.i("netease hooks installed");
    }

    // ------------------------------------------------------------------
    // 1. 追踪悬浮窗
    // ------------------------------------------------------------------

    /**
     * hook 所有 addView 重载（跨版本兼容）。
     * 歌词窗口 / 状态栏歌词都是网易云自己用 WindowManager 加的。
     */
    private static void hookAddView(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] classes = {
                "android.view.WindowManagerImpl",
                "android.view.WindowManagerGlobal",
        };
        int hooked = 0;
        for (String className : classes) {
            Class<?> clazz;
            try {
                clazz = XposedHelpers.findClass(className, lpparam.classLoader);
            } catch (Throwable t) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"addView".equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                boolean hasView = false;
                boolean hasParams = false;
                for (Class<?> type : types) {
                    if (type == View.class) {
                        hasView = true;
                    }
                    if (type == ViewGroup.LayoutParams.class) {
                        hasParams = true;
                    }
                }
                if (!hasView || !hasParams) {
                    continue;
                }
                method.setAccessible(true);
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                onViewAdded(param.args);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
        }
        XLog.i("hooked addView overloads: " + hooked);
    }

    /** addView 之后：记录所有窗口类型（便于排查），只上色真正的悬浮窗 */
    private static void onViewAdded(Object[] args) {
        if (args == null) {
            return;
        }
        View root = null;
        for (Object arg : args) {
            if (arg instanceof View) {
                root = (View) arg;
                break;
            }
        }
        if (root == null) {
            return;
        }
        int type = OverlayTracker.typeOf(root);
        rememberType(type);
        XLog.i("addView type=" + type + " view=" + root.getClass().getName());

        Config cfg = config;
        if (cfg == null || !cfg.enabled() || !cfg.anyTargetEnabled()) {
            return;
        }
        if (!OverlayTracker.track(root)) {
            return;
        }
        // 记录被认定为歌词悬浮窗的窗口类型，供模块界面直接显示
        DiagLog.put(DIAG_LYRIC_TYPE, String.valueOf(type));
        DiagLog.put(DIAG_LYRIC_VIEW, root.getClass().getName());
        XLog.i(">>> tracked lyric overlay (total " + OverlayTracker.trackedCount() + ")");
        // 注册绘制链路：hook 这个 View 树里真正会被调用的 draw
        DrawHook.register(root);
        scanAndPaint(root);
    }

    /** 记录见过的所有窗口类型，去重后落盘 */
    private static void rememberType(int type) {
        if (type < 0) {
            return;
        }
        synchronized (SEEN_TYPES) {
            if (SEEN_TYPES.add(type)) {
                DiagLog.put(DIAG_SEEN_TYPES, join(SEEN_TYPES));
            }
        }
    }

    private static String join(Set<Integer> types) {
        List<Integer> sorted = new ArrayList<>(types);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(sorted.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 2. TextView 路径
    // ------------------------------------------------------------------

    private static void hookTextViewSetText() {
        Method target = null;
        for (Method method : TextView.class.getDeclaredMethods()) {
            if ("setText".equals(method.getName())
                    && method.getParameterTypes().length == 4) {
                target = method;
                break;
            }
        }
        if (target == null) {
            XLog.e("TextView#setText(4 args) not found");
            return;
        }
        target.setAccessible(true);
        try {
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Config cfg = config;
                    if (cfg == null || !cfg.enabled() || !cfg.anyTargetEnabled()) {
                        return;
                    }
                    if (!(param.thisObject instanceof TextView)) {
                        return;
                    }
                    // 只处理悬浮窗内的 TextView，Activity 里的直接放过
                    if (!OverlayTracker.isInOverlay((TextView) param.thisObject)) {
                        return;
                    }
                    Object arg = param.args[0];
                    if (!(arg instanceof CharSequence)) {
                        return;
                    }
                    // 关键：先剥掉逐字染色 span，否则 span 优先级高于
                    // Paint 的 Shader，渐变会被盖住
                    if (cfg.stripSpans()) {
                        CharSequence stripped = SpanStripper.strip((CharSequence) arg);
                        if (stripped != arg) {
                            param.args[0] = stripped;
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof TextView)) {
                            return;
                        }
                        Config cfg = config;
                        if (cfg == null || !cfg.enabled() || !cfg.anyTargetEnabled()) {
                            return;
                        }
                        paintIfLyrics((TextView) param.thisObject, cfg);
                    } catch (Throwable t) {
                        XLog.d("setText after hook error: " + t);
                    }
                }
            });
            XLog.i("hooked TextView#setText");
        } catch (Throwable t) {
            XLog.e("hook setText failed: " + t);
        }
    }

    private static void paintIfLyrics(TextView tv, Config cfg) {
        // 严格限定：必须在已追踪的悬浮窗内
        if (!OverlayTracker.isInOverlay(tv)) {
            return;
        }
        CharSequence text = tv.getText();
        if (text == null || text.length() == 0) {
            return;
        }
        RainbowController controller = RainbowController.of(tv);
        if (controller == null) {
            RainbowController.attach(tv, cfg);
            XLog.i(">>> painted lyric TextView: " + text);
            return;
        }
        // TextView 可能因暂时隐藏、清空文本或脱离窗口而停过动画。
        // 它仍留在 ATTACHED 表里，后续 setText 必须把停掉的动画拉起来。
        if (!controller.isRunning()) {
            controller.apply(cfg);
            controller.start();
            XLog.i(">>> resumed lyric TextView: " + text);
        }
    }

    // ------------------------------------------------------------------
    // 3. 遍历悬浮窗 View 树补挂
    // ------------------------------------------------------------------

    private static void scanAndPaint(View root) {
        Config cfg = config;
        if (cfg == null || !cfg.enabled() || !cfg.anyTargetEnabled()) {
            return;
        }
        for (TextView tv : collectTextViews(root, 0)) {
            paintIfLyrics(tv, cfg);
        }
    }

    private static List<TextView> collectTextViews(View view, int depth) {
        List<TextView> out = new java.util.ArrayList<>();
        collect(view, out, depth);
        return out;
    }

    private static void collect(View view, List<TextView> out, int depth) {
        if (view == null || depth > 12) {
            return;
        }
        if (view instanceof TextView) {
            out.add((TextView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. 类名匹配：仅用于日志确认歌词源
    // ------------------------------------------------------------------

    private static void hookLyricSourceByName(XC_LoadPackage.LoadPackageParam lpparam) {
        List<String> names = DexScanner.findClassNames(lpparam.classLoader,
                "statuslyric", "status_lyric", "statusbar_lyric",
                "desktoplyric", "desktop_lyric", "floatlyric");
        for (String className : names) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className, false, lpparam.classLoader);
            } catch (Throwable t) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 1 || types[0] != String.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object arg = param.args[0];
                                if (arg instanceof String && !((String) arg).isEmpty()) {
                                    XLog.i("lyric <- " + arg);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    XLog.i("hooked lyric source: " + className + "#" + method.getName());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 5. 配置热更新
    // ------------------------------------------------------------------

    private static void registerConfigReceiver() {
        Context context = AppContext.get();
        if (context == null) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(LyricBus.ACTION_CONFIG);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        // 优先用广播直接带来的配置（即时、不依赖跨进程文件）；
                        // 没带才回退到 XSharedPreferences / 公共文件。
                        Map<String, String> mem = extrasToMap(intent);
                        Config cfg = (mem != null && !mem.isEmpty())
                                ? new Config(mem) : ConfigHost.host();
                        config = cfg;
                        PaintTinter.get().setConfig(cfg);
                        updateTiltSensor(cfg);
                        if (!cfg.enabled() || !cfg.anyTargetEnabled()) {
                            RainbowController.detachAll();
                            LyricRenderer.releaseAll();
                        } else {
                            // 扭曲、消散、摆动都关闭时才释放离屏位图和粒子
                            if (!cfg.warpEnabled() && !cfg.dissolveEnabled()
                                    && !cfg.tiltEnabled()) {
                                LyricRenderer.releaseAll();
                            }
                            RainbowController.refreshAll(cfg);
                            for (View root : OverlayTracker.snapshotRoots()) {
                                if (root != null) {
                                    DrawHook.register(root);
                                    scanAndPaint(root);
                                }
                            }
                        }
                        XLog.i("config refreshed via " + (mem != null && !mem.isEmpty()
                                ? "intent: enabled=" + cfg.enabled()
                                : "file: enabled=" + cfg.enabled()));
                    } catch (Throwable t) {
                        XLog.e("refresh config failed: " + t);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            XLog.e("register config receiver failed: " + t);
        }
    }

    /** 把广播 extras 里的字符串配置取成 Map；没有就返回 null */
    private static Map<String, String> extrasToMap(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            Bundle bundle = intent.getExtras();
            if (bundle == null || bundle.isEmpty()) {
                return null;
            }
            Map<String, String> map = new HashMap<>();
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                if (value != null) {
                    map.put(key, String.valueOf(value));
                }
            }
            return map;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 陀螺仪只在模块和摆动效果都开启时监听，避免后台空耗传感器 */
    private static void updateTiltSensor(Config cfg) {
        if (cfg != null && cfg.enabled() && cfg.anyTargetEnabled() && cfg.tiltEnabled()) {
            SensorTilt.get().start(AppContext.get());
        } else {
            SensorTilt.get().stop();
        }
    }
}
