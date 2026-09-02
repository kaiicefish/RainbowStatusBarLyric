package com.rainbow.statusbarlyric.core;

import android.content.SharedPreferences;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 跨进程配置。
 * <p>
 * 取值优先级：/data/local/tmp 上的公共副本 -> XSharedPreferences -> 内置默认值。
 * 所有值统一以 String 形式存储，避免类型解析差异。
 * <p>
 * 注意：本类<b>不允许引用任何 Xposed API</b>。Xposed 依赖是 compileOnly，
 * 不会打进 APK，而模块自己的设置界面会加载 Config —— 一旦引用就会
 * NoClassDefFoundError 直接闪退。需要 XSharedPreferences 请走 {@link ConfigHost}。
 */
public class Config {

    public static final String PKG = "com.rainbow.statusbarlyric";
    public static final String PREF_NAME = "rainbow_config";
    /** 便于被网易云进程读取的公共副本 */
    public static final String TMP_PATH = "/data/local/tmp/rainbow_lyric_prefs.xml";

    public static final String KEY_ENABLED = "enabled";
    /** 桌面歌词（自绘 / TextView 都覆盖） */
    public static final String KEY_DESKTOP = "desktop";
    /** 状态栏歌词 */
    public static final String KEY_STATUS = "status";
    public static final String KEY_FLOW_SPEED = "flow_speed";
    public static final String KEY_BREATH_RANGE = "breath_range";
    public static final String KEY_BREATH_PERIOD = "breath_period";
    /** 0=关 1=热浪 2=水波 3=抖动 */
    public static final String KEY_WARP_EFFECT = "warp_effect";
    public static final String KEY_WARP_AMP = "warp_amp";
    public static final String KEY_WARP_FREQ = "warp_freq";
    public static final String KEY_WARP_SPEED = "warp_speed";
    public static final String KEY_DISSOLVE = "dissolve";
    /** 采样间隔，越小粒子越密 */
    public static final String KEY_DISSOLVE_STEP = "dissolve_step";
    /** 消散时长，实际 /10 秒 */
    public static final String KEY_DISSOLVE_LIFE = "dissolve_life";
    public static final String KEY_DISSOLVE_RISE = "dissolve_rise";
    public static final String KEY_DISSOLVE_SPREAD = "dissolve_spread";
    /** 粒子边长，实际 /10 px */
    public static final String KEY_DISSOLVE_SIZE = "dissolve_size";
    /** 陀螺仪摆动开关 */
    public static final String KEY_TILT = "tilt";
    /** 摆动灵敏度 0~10，映射到最大摆动角度 */
    public static final String KEY_TILT_SENS = "tilt_sens";
    /**
     * 配置写入时间戳。两份副本写同一份值，读取时比大小决定用哪份 ——
     * 防止写不进去的那份变成陈旧数据、永久盖住新配置。
     */
    public static final String KEY_STAMP = "stamp";
    /** 渐变色值，#RRGGBB 逗号分隔，至少 2 个 */
    public static final String KEY_COLORS = "colors";
    /** 剥掉网易云自带的逐字染色 span，否则它会盖掉我们的渐变 */
    public static final String KEY_STRIP_SPANS = "strip_spans";

    /** 默认色值：一整套彩虹，首尾同色保证流光循环无跳变 */
    public static final String DEFAULT_COLORS =
            "#FF4D6D,#FF8A3D,#FFD84D,#5BE59A,#4CC9F0,#9D7BFF,#FF4D6D";

    /**
     * 配置来源列表，按顺序取第一个有值的。
     * <p>
     * 之所以要多个：LSPosed 下模块界面在独立进程，配置得用 XSharedPreferences
     * 跨进程读；LSPatch 下模块被 patch 进宿主 APK，界面和 hook 在同一个进程，
     * 直接读宿主的普通 SP 就行。两种都要支持，所以做成多源。
     */
    private final SharedPreferences[] sources;
    private final Reloader reloader;
    /**
     * 内存配置（最高优先）。来自设置界面通过广播 Intent extras 直接推过来的值，
     * 不依赖 XSharedPreferences / 公共文件，改完开关即时生效。
     */
    private final Map<String, String> memory;
    private Map<String, String> tmp;
    /** tmp 是否比 SP 新（决定用哪份） */
    private boolean tmpWins;
    /** 上一次取值命中的来源，用于诊断 */
    private int hitIndex = -3;

    /** 负责重新加载底层存储，屏蔽 XSharedPreferences 这一层实现细节 */
    public interface Reloader {
        void reload();
    }

    /** 模块界面进程使用：只读私有 SP，没有 reload 能力 */
    public Config(SharedPreferences sp) {
        this(sp == null ? null : new SharedPreferences[]{sp}, null, null);
    }

    /** Hook 进程使用：由 {@link ConfigHost} 传入可 reload 的实现 */
    public Config(SharedPreferences sp, Reloader reloader) {
        this(sp == null ? null : new SharedPreferences[]{sp}, reloader, null);
    }

    /** 多来源构造，由 {@link ConfigHost} 使用 */
    public Config(SharedPreferences[] sources, Reloader reloader) {
        this(sources, reloader, null);
    }

    /**
     * 直接用一份内存里的键值对构造（来自配置变更广播携带的 extras）。
     * 优先级最高、完全不碰跨进程文件，是开关「点保存就生效」的主通道。
     */
    public Config(Map<String, String> memory) {
        this(null, null, memory);
    }

    private Config(SharedPreferences[] sources, Reloader reloader, Map<String, String> memory) {
        this.sources = sources;
        this.reloader = reloader;
        this.memory = memory;
        resolveSource();
    }

    public void refresh() {
        if (reloader != null) {
            reloader.reload();
        }
        resolveSource();
    }

    /**
     * 决定这一轮用哪份配置。
     * <p>
     * <b>关键是比时间戳，而不是无脑优先 tmp。</b>
     * tmp（{@code /data/local/tmp}）在很多 ROM 上应用进程根本写不进去，
     * 写失败时它会一直停留在旧内容 —— 如果无脑优先 tmp，
     * 这份陈旧数据就会永久盖住新存的配置，表现正是
     * 「改了设置、甚至强制停止重开都不生效」。
     * 两份都带同一份时间戳，谁新用谁；只有一份可读就用那份。
     */
    private void resolveSource() {
        tmp = TmpPrefs.read();
        long tmpStamp = tmp == null ? -1L : stampOf(tmp.get(KEY_STAMP));

        long spStamp = -1L;
        if (sources != null) {
            for (SharedPreferences sp : sources) {
                if (sp == null) {
                    continue;
                }
                String rawStamp = null;
                try {
                    rawStamp = sp.getString(KEY_STAMP, null);
                } catch (Throwable ignored) {
                    rawStamp = null;
                }
                long parsed = stampOf(rawStamp);
                if (parsed > spStamp) {
                    spStamp = parsed;
                }
            }
        }
        // 时间戳相等时优先 tmp：它不依赖 world-readable 权限，更可靠
        tmpWins = tmpStamp >= 0 && tmpStamp >= spStamp;
    }

    private static long stampOf(String value) {
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    private String raw(String key, String def) {
        String v = null;
        // 广播推来的内存配置最高优先：它就是用户刚点「保存」的那份值
        if (memory != null) {
            v = memory.get(key);
            if (v != null) {
                hitIndex = -4;
                return v;
            }
        }
        if (tmpWins && tmp != null) {
            v = tmp.get(key);
            if (v != null) {
                hitIndex = -2;
                return v;
            }
        }
        if (sources != null) {
            for (int i = 0; i < sources.length; i++) {
                SharedPreferences sp = sources[i];
                if (sp == null) {
                    continue;
                }
                try {
                    v = sp.getString(key, null);
                } catch (Throwable ignored) {
                    v = null;
                }
                if (v != null) {
                    hitIndex = i;
                    return v;
                }
            }
        }
        // 首选来源没有这个值，再回头看另一份，最后才是默认值
        if (!tmpWins && tmp != null) {
            v = tmp.get(key);
            if (v != null) {
                hitIndex = -2;
                return v;
            }
        }
        hitIndex = -3;
        return def;
    }

    /**
     * 上一次取值命中的来源，排查「改了配置没生效」时很有用。
     * 索引对应 {@link ConfigHost} 里传入的顺序。
     */
    public String debugSource() {
        if (hitIndex == -4) {
            return "intent";
        }
        if (hitIndex == -2) {
            return "tmp";
        }
        if (hitIndex >= 0) {
            return "sp[" + hitIndex + "]";
        }
        return "default";
    }

    /** 当前实际采用哪份配置，以及各自的时间戳 */
    public String debugStamp() {
        long tmpStamp = tmp == null ? -1L : stampOf(tmp.get(KEY_STAMP));
        long spStamp = -1L;
        if (sources != null) {
            for (SharedPreferences sp : sources) {
                if (sp == null) {
                    continue;
                }
                try {
                    long v = stampOf(sp.getString(KEY_STAMP, null));
                    if (v > spStamp) {
                        spStamp = v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return "use=" + (tmpWins ? "tmp" : "sp") + " tmp=" + tmpStamp + " sp=" + spStamp;
    }

    public boolean enabled() {
        return raw(KEY_ENABLED, "true").equals("true");
    }

    public boolean desktopEnabled() {
        return raw(KEY_DESKTOP, "true").equals("true");
    }

    public boolean statusEnabled() {
        return raw(KEY_STATUS, "true").equals("true");
    }

    public boolean anyTargetEnabled() {
        return desktopEnabled() || statusEnabled();
    }

    /** 流光速度，px/s */
    public int flowSpeed() {
        return clamp(getInt(KEY_FLOW_SPEED, 80), 0, 300);
    }

    /** 呼吸幅度 0~90，值越大明暗变化越明显 */
    public int breathRange() {
        return clamp(getInt(KEY_BREATH_RANGE, 40), 0, 90);
    }

    /** 呼吸周期，ms */
    public int breathPeriodMs() {
        return clamp(getInt(KEY_BREATH_PERIOD, 2600), 800, 8000);
    }

    /** 热浪：逐行正弦位移，相位随时间向上流动 */
    public static final int WARP_OFF = 0;
    public static final int WARP_HEAT = 1;
    public static final int WARP_RIPPLE = 2;
    public static final int WARP_JITTER = 3;

    public int warpEffect() {
        return clamp(getInt(KEY_WARP_EFFECT, WARP_HEAT), WARP_OFF, WARP_JITTER);
    }

    /** 扭曲位移的最大像素数 */
    public float warpAmplitude() {
        return clamp(getInt(KEY_WARP_AMP, 3), 0, 24);
    }

    /** 空间频率，实际值 = 配置 / 100 */
    public float warpFrequency() {
        return clamp(getInt(KEY_WARP_FREQ, 9), 1, 40) / 100f;
    }

    /** 时间流速，实际值 = 配置 / 10 */
    public float warpSpeed() {
        return clamp(getInt(KEY_WARP_SPEED, 12), 0, 60) / 10f;
    }

    public boolean warpEnabled() {
        return warpEffect() != WARP_OFF && warpAmplitude() > 0;
    }

    // ---- 消散 ----

    public boolean dissolveEnabled() {
        return raw(KEY_DISSOLVE, "true").equals("true");
    }

    /** 采样间隔（px），越小粒子越密 */
    public int dissolveStep() {
        return clamp(getInt(KEY_DISSOLVE_STEP, 2), 1, 8);
    }

    /** 消散时长，秒；实际值 = 配置 / 10 */
    public float dissolveLifeS() {
        return clamp(getInt(KEY_DISSOLVE_LIFE, 18), 3, 30) / 10f;
    }

    /** 上升初速度 px/s */
    public float dissolveRise() {
        return clamp(getInt(KEY_DISSOLVE_RISE, 60), 0, 160);
    }

    /** 水平扩散强度 px/s */
    public float dissolveSpread() {
        return clamp(getInt(KEY_DISSOLVE_SPREAD, 42), 0, 100);
    }

    /** 粒子直径 px；实际值 = 配置 / 10 */
    public float dissolveSize() {
        return clamp(getInt(KEY_DISSOLVE_SIZE, 26), 6, 60) / 10f;
    }

    // ---- 陀螺仪摆动 ----

    public boolean tiltEnabled() {
        return raw(KEY_TILT, "true").equals("true");
    }

    /** 灵敏度 0~10 */
    public int tiltSensitivity() {
        return clamp(getInt(KEY_TILT_SENS, 6), 0, 10);
    }

    /**
     * 最大摆动角度（度）。灵敏度 0~10 线性映射到 0~12°，
     * 数值刻意压小 —— 需求是「小幅摆动」，太大会像歌词没固定住。
     */
    public float tiltMaxAngle() {
        return tiltSensitivity() / 10f * 12f;
    }

    public String colorsRaw() {
        return raw(KEY_COLORS, DEFAULT_COLORS);
    }

    public boolean stripSpans() {
        return raw(KEY_STRIP_SPANS, "true").equals("true");
    }

    /** 呼吸时的最低亮度 0~1 */
    public float breathMin() {
        return 1f - breathRange() / 100f;
    }

    public boolean breathing() {
        return breathMin() < 1f;
    }

    /**
     * 是否需要持续重绘（流光、呼吸、扭曲、消散、摆动任一开启）。
     * <p>
     * 消散必须算进来：粒子是按帧推进位置的，不驱动下一帧粒子就会冻在原地；
     * 摆动也要持续重绘，否则只有传感器事件本身不会让歌词 View 重新 onDraw。
     */
    public boolean needsAnimation() {
        return flowSpeed() > 0 || breathing() || warpEnabled()
                || dissolveEnabled() || tiltEnabled();
    }

    /**
     * 最终使用的渐变色数组。
     * 解析失败时退回默认彩虹，保证任何时候都有可见效果。
     */
    public int[] colors() {
        int[] parsed = parseColors(colorsRaw());
        if (parsed != null && parsed.length >= 2) {
            return parsed;
        }
        return parseColors(DEFAULT_COLORS);
    }

    /** 解析 "#RRGGBB,#RRGGBB,..." 或 "#AARRGGBB,..."，支持中英文逗号/分号/空格分隔 */
    public static int[] parseColors(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] parts = raw.split("[,，;；\\s]+");
        int[] out = new int[parts.length];
        int n = 0;
        for (String part : parts) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            Integer color = parseSingleColor(s);
            if (color == null) {
                return null;
            }
            out[n++] = color;
        }
        if (n < 2) {
            return null;
        }
        int[] result = new int[n];
        System.arraycopy(out, 0, result, 0, n);
        return result;
    }

    private static Integer parseSingleColor(String s) {
        try {
            String hex = s.startsWith("#") ? s.substring(1) : s;
            if (hex.length() != 6 && hex.length() != 8) {
                return null;
            }
            long value = Long.parseLong(hex, 16);
            if (hex.length() == 6) {
                value |= 0xFF000000L;
            }
            return (int) value;
        } catch (Throwable t) {
            return null;
        }
    }

    private int getInt(String key, int def) {
        try {
            return Integer.parseInt(raw(key, String.valueOf(def)));
        } catch (Throwable t) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** /data/local/tmp 上的 SharedPreferences 风格 XML 读写 */
    static final class TmpPrefs {

        static Map<String, String> read() {
            File f = new File(TMP_PATH);
            if (!f.exists() || !f.canRead()) {
                return null;
            }
            Map<String, String> map = new HashMap<>();
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(in, "UTF-8");
                int event = parser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && "string".equals(parser.getName())) {
                        String name = parser.getAttributeValue(null, "name");
                        String value = parser.getAttributeValue(null, "value");
                        if (name != null) {
                            map.put(name, value == null ? "" : value);
                        }
                    }
                    event = parser.next();
                }
                return map;
            } catch (Throwable t) {
                return map.isEmpty() ? null : map;
            } finally {
                close(in);
            }
        }

        static boolean write(Map<String, String> values) {
            File f = new File(TMP_PATH);
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n");
                for (Map.Entry<String, String> e : values.entrySet()) {
                    sb.append("    <string name=\"").append(e.getKey()).append("\" value=\"")
                            .append(escape(e.getValue())).append("\" />\n");
                }
                sb.append("</map>\n");
                FileOutputStream out = new FileOutputStream(f, false);
                OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                writer.write(sb.toString());
                writer.flush();
                writer.close();
                // 让网易云进程有权限读取
                f.setReadable(true, false);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private static String escape(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("&", "&amp;").replace("\"", "&quot;")
                    .replace("<", "&lt;").replace(">", "&gt;");
        }

        private static void close(InputStream in) {
            if (in == null) {
                return;
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
