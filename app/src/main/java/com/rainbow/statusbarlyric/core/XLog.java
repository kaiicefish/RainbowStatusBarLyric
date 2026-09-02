package com.rainbow.statusbarlyric.core;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * 统一日志。
 * <p>
 * 关键：XposedBridge 是 <b>compileOnly</b> 依赖，不会打进 APK，
 * 而本类会被模块自己的界面进程（没有 Xposed 环境）使用。
 * 所以这里<b>用反射</b>调用 XposedBridge.log，而不是直接引用——
 * 直接引用的话，ART 校验方法时找不到类，一执行就 NoClassDefFoundError 闪退。
 * <p>
 * 行为：
 * - 被 Hook 的进程（网易云音乐）：同时输出到 logcat 和 LSPosed 日志；
 * - 模块自己的进程：只输出 logcat。
 */
public final class XLog {

    private static final String TAG = "RainbowLyric";
    private static final boolean VERBOSE = true;

    private static final Method BRIDGE_LOG;

    static {
        Method method = null;
        try {
            Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
            method = bridge.getDeclaredMethod("log", String.class);
            method.setAccessible(true);
        } catch (Throwable ignored) {
            // 没有 Xposed 环境（模块自己的界面进程），只走 logcat
            method = null;
        }
        BRIDGE_LOG = method;
    }

    private XLog() {
    }

    public static void i(String msg) {
        write("I", msg);
    }

    public static void e(String msg) {
        write("E", msg);
    }

    public static void d(String msg) {
        if (VERBOSE) {
            write("D", msg);
        }
    }

    private static void write(String level, String msg) {
        String line = "[" + level + "] " + msg;
        switch (level) {
            case "E":
                Log.e(TAG, line);
                break;
            case "D":
                Log.d(TAG, line);
                break;
            default:
                Log.i(TAG, line);
                break;
        }
        if (BRIDGE_LOG != null) {
            try {
                BRIDGE_LOG.invoke(null, TAG + ": " + line);
            } catch (Throwable ignored) {
                // 日志失败不能影响业务
            }
        }
        // 同时落盘：LSPosed 日志页面不一定看得到，文件最稳，
        // 而且能被模块界面直接读出来显示
        DiagLog.write(line);
    }
}
