package com.rainbow.statusbarlyric.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃日志捕获：把未捕获异常写进 /sdcard/Android/data/包名/files/crash.log。
 * <p>
 * 模块跑在被 Hook 的进程里，出问题时往往看不到界面，
 * 存一份文件比让用户去捞 logcat 省事得多。
 */
public final class CrashLogger {

    private static final String TAG = "RainbowLyric";
    private static final String FILE_NAME = "crash.log";
    private static final int MAX_KEEP = 200_000;

    private CrashLogger() {
    }

    public static void install(Context context) {
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler original =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                String text = format(thread, throwable);
                Log.e(TAG, text);
                append(appContext, text);

                if (original != null) {
                    original.uncaughtException(thread, throwable);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    private static String format(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("========== CRASH ==========");
        pw.println("time   : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()));
        pw.println("process: " + (thread == null ? "?" : thread.getName()));
        pw.println("---------------------------");
        throwable.printStackTrace(pw);
        pw.println("===========================");
        pw.println();
        return sw.toString();
    }

    private static void append(Context context, String text) {
        OutputStreamWriter writer = null;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, FILE_NAME);
            long existing = file.exists() ? file.length() : 0;
            // 超过上限就从头覆盖，避免日志无限膨胀
            boolean append = existing <= MAX_KEEP;

            writer = new OutputStreamWriter(
                    new FileOutputStream(file, append), StandardCharsets.UTF_8);
            writer.write(text);
            writer.flush();
        } catch (Throwable ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
