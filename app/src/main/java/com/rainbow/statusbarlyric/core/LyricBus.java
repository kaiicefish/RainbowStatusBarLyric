package com.rainbow.statusbarlyric.core;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.Map;

/** 模块界面 -> 网易云进程 的配置变更通知（显式广播）。 */
public final class LyricBus {

    public static final String ACTION_CONFIG = "com.rainbow.statusbarlyric.action.CONFIG_CHANGED";
    public static final String NETEASE = "com.netease.cloudmusic";

    private LyricBus() {
    }

    /** 兼容旧调用：不带值，接收端回退到读文件。 */
    public static void sendConfigChanged(Context context) {
        sendConfigChanged(context, null);
    }

    /**
     * 通知网易云进程刷新配置，省去重启。
     * <p>
     * 直接把整份配置塞进 Intent extras 一起带过去 —— 走 Binder，
     * 不依赖 XSharedPreferences 或公共文件的可读性，是开关即时生效的主通道。
     * 接收端优先用这份值，读不到时再回退到 {@link ConfigHost}。
     */
    public static void sendConfigChanged(Context context, Map<String, String> values) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION_CONFIG);
            intent.setPackage(NETEASE);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            if (values != null && !values.isEmpty()) {
                Bundle bundle = new Bundle();
                for (Map.Entry<String, String> e : values.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        bundle.putString(e.getKey(), e.getValue());
                    }
                }
                intent.putExtras(bundle);
            }
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            XLog.e("sendConfigChanged failed: " + t);
        }
    }
}
