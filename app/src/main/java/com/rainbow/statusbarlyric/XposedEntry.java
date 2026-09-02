package com.rainbow.statusbarlyric;

import android.app.Application;

import com.rainbow.statusbarlyric.core.AppContext;
import com.rainbow.statusbarlyric.core.Config;
import com.rainbow.statusbarlyric.core.ConfigHost;
import com.rainbow.statusbarlyric.core.CrashLogger;
import com.rainbow.statusbarlyric.core.LyricBus;
import com.rainbow.statusbarlyric.core.NeteaseHook;
import com.rainbow.statusbarlyric.core.XLog;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口，见 assets/xposed_init。
 * <p>
 * <b>只 hook 网易云音乐一个普通应用，不碰系统框架。</b>
 * 不注入 com.android.systemui，也不用悬浮窗自己画歌词 ——
 * 全部改动都在网易云自己的歌词 View 上完成。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!LyricBus.NETEASE.equals(lpparam.packageName)) {
            return;
        }
        if (!lpparam.isFirstApplication) {
            return;
        }
        // 配置等 Application 就绪后再读：LSPatch 下需要宿主 Context 才能读到
        // 界面写进去的那份 SP，过早创建会漏掉这个来源。
        hookApplicationCreate(lpparam);
    }

    /** 等 Application 就绪后再做真正的 Hook，确保拿到了可用的 Context */
    private void hookApplicationCreate(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                    "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onAppReady((Application) param.args[0], lpparam);
                        }
                    });
        } catch (Throwable t) {
            XLog.d("Instrumentation hook failed, fallback: " + t);
            try {
                XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        onAppReady((Application) param.thisObject, lpparam);
                    }
                });
            } catch (Throwable ignored) {
                XLog.e("cannot hook application create");
            }
        }
    }

    private void onAppReady(Application app, XC_LoadPackage.LoadPackageParam lpparam) {
        if (app == null || AppContext.get() != null) {
            return;
        }
        AppContext.set(app);

        // LSPatch 下模块被 patch 进宿主 APK，AndroidManifest 里声明的
        // RainbowApp 不会被执行，所以崩溃捕获得在这里补装一次。
        CrashLogger.install(app);

        final Config cfg = ConfigHost.host();
        XLog.i("rainbow lyric ready | enabled=" + cfg.enabled()
                + " desktop=" + cfg.desktopEnabled()
                + " status=" + cfg.statusEnabled()
                + " configSource=" + cfg.debugSource());

        // 即使模块被禁用也装上 hook：
        // 这样在设置里重新启用后，靠配置广播就能热生效，不用重启网易云。
        try {
            NeteaseHook.hook(lpparam, cfg);
        } catch (Throwable t) {
            XLog.e("hook netease failed: " + t);
        }
    }
}
