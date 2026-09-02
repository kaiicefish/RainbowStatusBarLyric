package com.rainbow.statusbarlyric.core;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import dalvik.system.DexFile;
import de.robv.android.xposed.XposedHelpers;

/** 在宿主 dex 中按类名关键字搜索类，用于适配网易云各版本。 */
public final class DexScanner {

    private DexScanner() {
    }

    public static List<String> findClassNames(ClassLoader loader, String... keywords) {
        List<String> result = new ArrayList<>();
        long start = System.currentTimeMillis();
        try {
            Object pathList = XposedHelpers.getObjectField(loader, "pathList");
            Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            for (Object element : elements) {
                DexFile dexFile = dexFileOf(element);
                if (dexFile == null) {
                    continue;
                }
                Enumeration<String> entries = dexFile.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    String lower = name.toLowerCase(Locale.US);
                    for (String keyword : keywords) {
                        if (lower.contains(keyword.toLowerCase(Locale.US))) {
                            result.add(name);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XLog.e("dex scan failed: " + t);
        }
        XLog.d("dex scan cost " + (System.currentTimeMillis() - start) + "ms, hits=" + result.size());
        return result;
    }

    private static DexFile dexFileOf(Object element) {
        try {
            Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
            if (dexFile instanceof DexFile) {
                return (DexFile) dexFile;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object path = XposedHelpers.getObjectField(element, "path");
            if (path instanceof String) {
                return new DexFile((String) path);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
