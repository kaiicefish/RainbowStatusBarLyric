package com.rainbow.statusbarlyric.core;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断日志：写文件，供模块界面直接读取显示。
 * <p>
 * 为什么不用 LSPosed 的日志页面：
 * 不同版本的 LSPosed 对 {@code XposedBridge.log} 的处理不一致，
 * 有的需要开「详细日志」才会显示，有的会被随时清掉，排查时很不好使。
 * 写文件最稳，而且能跨进程 —— 网易云进程写，模块界面读。
 * <p>
 * 会依次尝试多个路径，挑第一个真正能写的。
 * 注意：本类<b>不得引用任何 Xposed API</b>，它会被模块界面进程加载。
 */
public final class DiagLog {

    private static final String TAG = "RainbowLyricDiag";
    private static final String NAME = "rainbow_lyric_diag.txt";

    /** 按优先级尝试；不同 ROM / Android 版本的可写性差别很大 */
    private static final String[] CANDIDATES = {
            "/data/local/tmp/" + NAME,
            "/sdcard/Documents/" + NAME,
            "/sdcard/Download/" + NAME,
            "/sdcard/" + NAME,
    };

    private static final int MAX_BYTES = 64 * 1024;

    private static volatile String path;
    private static volatile boolean resolved;

    private DiagLog() {
    }

    /** 挑一个真正可写的路径（只探测一次） */
    private static String resolve() {
        if (resolved) {
            return path;
        }
        synchronized (DiagLog.class) {
            if (resolved) {
                return path;
            }
            for (String candidate : CANDIDATES) {
                FileOutputStream out = null;
                try {
                    File f = new File(candidate);
                    File parent = f.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        continue;
                    }
                    // append 模式试打开，成功就说明目录可写
                    out = new FileOutputStream(f, true);
                    out.close();
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                    path = candidate;
                    break;
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(out);
                }
            }
            if (path == null) {
                path = CANDIDATES[0]; // 都写不了，留个路径好排查
            }
            resolved = true;
            Log.i(TAG, "diag path = " + path);
        }
        return path;
    }

    public static synchronized void write(String msg) {
        String p = resolve();
        FileOutputStream fos = null;
        try {
            File f = new File(p);
            // 超过上限就从头覆盖，避免无限膨胀
            boolean append = f.exists() && f.length() < MAX_BYTES;
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            fos = new FileOutputStream(f, append);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write("[" + time + "] " + msg + "\n");
            w.flush();
            w.close();
            fos = null;
            f.setReadable(true, false);
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(fos);
        }
    }

    /** 记录一个键值对，模块界面会解析显示（覆盖同名旧值） */
    public static void put(String key, String value) {
        write("#" + key + "=" + value);
    }

    public static String path() {
        return resolve();
    }

    /** 读回全部内容；所有候选路径都不可用返回 null */
    public static String read() {
        for (String candidate : CANDIDATES) {
            FileInputStream in = null;
            try {
                File f = new File(candidate);
                if (!f.exists() || !f.canRead()) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                in = new FileInputStream(f);
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(in);
            }
        }
        return null;
    }

    public static void clear() {
        for (String candidate : CANDIDATES) {
            try {
                File f = new File(candidate);
                if (f.exists()) {
                    FileOutputStream out = new FileOutputStream(f, false);
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从日志正文里取出最后一次记录的键值 */
    public static String valueOf(String content, String key) {
        if (content == null || key == null) {
            return null;
        }
        String marker = "#" + key + "=";
        String found = null;
        for (String line : content.split("\n")) {
            int idx = line.indexOf(marker);
            if (idx >= 0) {
                found = line.substring(idx + marker.length()).trim();
            }
        }
        return found;
    }

    /** 取最后若干行日志（去掉键值行） */
    public static String tail(String content, int maxLines) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = lines.length - 1; i >= 0 && count < maxLines; i--) {
            String line = lines[i];
            if (line.contains("#") && line.contains("=")) {
                continue;
            }
            sb.insert(0, line + "\n");
            count++;
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
