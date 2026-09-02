package com.rainbow.statusbarlyric.core;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 只在被 Hook 的进程（网易云音乐）里加载的配置工厂。
 * <p>
 * 单独拆成一个类，是为了让 {@link Config} 本身不含任何 Xposed API 引用：
 * Xposed 依赖是 compileOnly，不会打进 APK，而模块自己的设置界面
 * 会加载 Config，一旦引用到就会 NoClassDefFoundError 直接闪退。
 * <p>
 * <b>同时兼容 LSPosed 与 LSPatch：</b>
 * <ul>
 *   <li>LSPosed：模块独立安装，界面在 {@code com.rainbow.statusbarlyric} 进程，
 *       写的是模块自己的 SP，这里必须用 {@link XSharedPreferences} 跨进程读。</li>
 *   <li>LSPatch：模块被 patch 进宿主 APK，界面和 hook <b>在同一个进程</b>，
 *       配置就写在宿主的普通 SP 里，直接读即可，而且比 XSharedPreferences 可靠得多
 *       （后者在高版本 Android 上常因权限问题读不到）。</li>
 * </ul>
 * 两个源一起传进去，Config 按顺序取第一个有值的 —— 不用判断当前是哪种环境，
 * 两种模式自动适配。
 */
public final class ConfigHost {

    private ConfigHost() {
    }

    public static Config host() {
        SharedPreferences[] sources = new SharedPreferences[2];
        int n = 0;

        // 源 0：当前（宿主）进程自己的 SP —— LSPatch 下模块界面写在这里
        Context ctx = AppContext.get();
        if (ctx != null) {
            try {
                SharedPreferences local =
                        ctx.getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE);
                if (local != null && local.getAll().size() > 0) {
                    sources[n++] = local;
                }
            } catch (Throwable ignored) {
            }
        }

        // 源 1：模块独立安装时的 SP —— LSPosed 下跨进程读
        final XSharedPreferences xsp;
        try {
            xsp = new XSharedPreferences(Config.PKG, Config.PREF_NAME);
        } catch (Throwable t) {
            XLog.e("XSharedPreferences unavailable: " + t);
            return new Config(trim(sources, n), null);
        }
        sources[n++] = xsp;

        return new Config(trim(sources, n), new Config.Reloader() {
            @Override
            public void reload() {
                xsp.reload();
            }
        });
    }

    private static SharedPreferences[] trim(SharedPreferences[] src, int length) {
        if (length <= 0) {
            return null;
        }
        SharedPreferences[] out = new SharedPreferences[length];
        System.arraycopy(src, 0, out, 0, length);
        return out;
    }
}
