package com.rainbow.statusbarlyric;

import android.app.Application;

import com.rainbow.statusbarlyric.core.CrashLogger;

/**
 * 模块自身进程的 Application。
 * <p>
 * 只做一件事：装上崩溃日志捕获。
 * <p>
 * 注意：本进程<b>没有 Xposed 环境</b>（模块不会被注入到自己身上），
 * 所以这里以及它调用到的任何代码都<b>不能引用 Xposed API</b>，
 * 包括 XLog —— 那会直接 NoClassDefFoundError 闪退。
 * 同理，Hook 逻辑也不要放这里，一律走 XposedEntry。
 */
public class RainbowApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.install(this);
    }
}
