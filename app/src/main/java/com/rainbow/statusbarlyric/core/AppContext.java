package com.rainbow.statusbarlyric.core;

import android.app.Application;
import android.content.Context;

/** 保存 Hook 所在进程的 Application 上下文。 */
public final class AppContext {

    private static volatile Application sApp;

    private AppContext() {
    }

    public static void set(Application app) {
        sApp = app;
    }

    public static Context get() {
        return sApp;
    }
}
