package com.rainbow.statusbarlyric.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** 设置界面使用的写入端。 */
public final class ConfigStore {

    private static final String[] ALL_KEYS = {
            Config.KEY_ENABLED,
            Config.KEY_DESKTOP,
            Config.KEY_STATUS,
            Config.KEY_FLOW_SPEED,
            Config.KEY_BREATH_RANGE,
            Config.KEY_BREATH_PERIOD,
            Config.KEY_COLORS,
            Config.KEY_STRIP_SPANS,
            Config.KEY_WARP_EFFECT,
            Config.KEY_WARP_AMP,
            Config.KEY_WARP_FREQ,
            Config.KEY_WARP_SPEED,
            Config.KEY_DISSOLVE,
            Config.KEY_DISSOLVE_STEP,
            Config.KEY_DISSOLVE_LIFE,
            Config.KEY_DISSOLVE_RISE,
            Config.KEY_DISSOLVE_SPREAD,
            Config.KEY_DISSOLVE_SIZE,
            Config.KEY_TILT,
            Config.KEY_TILT_SENS,
            Config.KEY_STAMP,
    };

    private ConfigStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 各配置项的默认值 */
    public static Map<String, String> defaults() {
        Map<String, String> d = new HashMap<>();
        d.put(Config.KEY_ENABLED, "true");
        d.put(Config.KEY_DESKTOP, "true");
        d.put(Config.KEY_STATUS, "true");
        d.put(Config.KEY_FLOW_SPEED, "80");
        d.put(Config.KEY_BREATH_RANGE, "40");
        d.put(Config.KEY_BREATH_PERIOD, "2600");
        d.put(Config.KEY_COLORS, Config.DEFAULT_COLORS);
        d.put(Config.KEY_STRIP_SPANS, "true");
        d.put(Config.KEY_WARP_EFFECT, String.valueOf(Config.WARP_HEAT));
        d.put(Config.KEY_WARP_AMP, "3");
        d.put(Config.KEY_WARP_FREQ, "9");
        d.put(Config.KEY_WARP_SPEED, "12");
        d.put(Config.KEY_DISSOLVE, "true");
        d.put(Config.KEY_DISSOLVE_STEP, "2");
        d.put(Config.KEY_DISSOLVE_LIFE, "18");
        d.put(Config.KEY_DISSOLVE_RISE, "60");
        d.put(Config.KEY_DISSOLVE_SPREAD, "42");
        d.put(Config.KEY_DISSOLVE_SIZE, "26");
        d.put(Config.KEY_TILT, "true");
        d.put(Config.KEY_TILT_SENS, "6");
        return d;
    }

    /** 读取当前值（模块自身进程内） */
    public static Map<String, String> load(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        Map<String, String> out = defaults();
        Map<String, ?> stored = sp.getAll();
        for (String key : ALL_KEYS) {
            if (stored.containsKey(key)) {
                out.put(key, String.valueOf(stored.get(key)));
            }
        }
        return out;
    }

    /**
     * 保存到私有 SP，并同步一份到 /data/local/tmp 供网易云进程读取。
     * 统一按 String 存储，和 {@link Config#raw} 的读取方式保持一致。
     *
     * @return tmp 副本是否写入成功；失败不致命（还有 SP 兜底），但要让用户知道
     */
    public static boolean save(Context ctx, Map<String, String> values) {
        Map<String, String> merged = defaults();
        merged.putAll(values);
        // 两份副本写同一个时间戳，读取时比大小决定用哪份
        merged.put(Config.KEY_STAMP, String.valueOf(System.currentTimeMillis()));

        SharedPreferences.Editor editor = prefs(ctx).edit();
        editor.clear();
        for (String key : ALL_KEYS) {
            editor.putString(key, merged.get(key));
        }
        // 必须同步落盘：紧接着就发配置广播，apply() 是异步写盘，
        // 接收端（或冷启动）去读时文件可能还没更新，表现就是「开关不生效」。
        editor.commit();

        boolean tmpOk = Config.TmpPrefs.write(merged);
        // LSPatch 下模块就在宿主进程里，配置本来就能读到，
        // 没必要放开网易云数据目录的权限；只有 LSPosed 才需要。
        // tmp 写不进去时，world-readable 的 SP 就是唯一通道，更要确保放开。
        if (!isRunningInsideHost(ctx)) {
            makeWorldReadable(ctx);
        }
        return tmpOk;
    }

    /** 当前进程是不是宿主（网易云）进程 */
    private static boolean isRunningInsideHost(Context ctx) {
        try {
            return LyricBus.NETEASE.equals(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 恢复默认参数 */
    public static boolean reset(Context ctx) {
        Map<String, String> d = defaults();
        d.put(Config.KEY_STAMP, String.valueOf(System.currentTimeMillis()));

        SharedPreferences.Editor editor = prefs(ctx).edit();
        editor.clear();
        for (String key : ALL_KEYS) {
            editor.putString(key, d.get(key));
        }
        editor.commit();

        boolean tmpOk = Config.TmpPrefs.write(d);
        if (!isRunningInsideHost(ctx)) {
            makeWorldReadable(ctx);
        }
        return tmpOk;
    }

    /** Android 高版本私有目录不可读，放开 shared_prefs 目录与文件权限做兜底。 */
    private static void makeWorldReadable(Context ctx) {
        try {
            File dir = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
            if (dir.exists()) {
                dir.setExecutable(true, false);
                dir.setReadable(true, false);
            }
            File f = new File(dir, Config.PREF_NAME + ".xml");
            if (f.exists()) {
                f.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }
}
