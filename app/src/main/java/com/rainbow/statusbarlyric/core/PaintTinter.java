package com.rainbow.statusbarlyric.core;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import com.rainbow.statusbarlyric.view.RainbowController;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 给<b>自绘歌词 View</b> 上色，并在歌词切换时触发消散。
 * <p>
 * 网易云的桌面歌词为了实现卡拉 OK 逐字染色，很可能是继承 View 自己
 * 在 {@code onDraw} 里 {@code Canvas.drawText} 画的 —— 这种 View 不走
 * {@code TextView.setText}，只 hook setText 完全命中不了。
 * <p>
 * 依赖 {@link DrawHook} 提供「当前绘制中的 View」，
 * 判定严格走 {@link OverlayTracker}（只认系统悬浮窗），
 * 所以 Activity / Dialog / PopupWindow 里的文字绝对不会被碰到。
 */
public final class PaintTinter implements DrawHook.Callback {

    private static final PaintTinter INSTANCE = new PaintTinter();

    private static final Matrix MATRIX = new Matrix();

    private volatile Config config;
    private volatile LinearGradient gradient;
    private volatile int[] lastColors;

    /** 当前帧拼出来的文本，与上一帧比对来判断歌词是否换了 */
    private final StringBuilder frameText = new StringBuilder(64);
    /** 上一次真正触发放飞（消散）的归一化文本：保证同一句只放飞一次 */
    private String lastBurstText = "";
    /** 上一次放飞的时刻 */
    private long lastBurstMs = 0L;
    /** 两次放飞最小间隔，防止逐字分段抖动把粒子反复重置、肉眼看不到飘散 */
    private static final long BURST_COOLDOWN_MS = 250L;
    /** 上次重新读配置的时刻，用于节流 */
    private volatile long lastRefreshMs = 0L;
    /** 上次记录到诊断日志里的开关状态，避免每帧重复写 */
    private volatile String lastStateLog = "";

    private PaintTinter() {
    }

    public static PaintTinter get() {
        return INSTANCE;
    }

    /** 在 hook 安装时调用一次 */
    public static void install(Config cfg) {
        INSTANCE.config = cfg;
        DrawHook.setCallback(INSTANCE);
    }

    public void setConfig(Config cfg) {
        config = cfg;
        lastColors = null;
    }

    public void invalidateShader() {
        gradient = null;
        lastColors = null;
    }

    // ------------------------------------------------------------------
    // DrawHook 回调
    // ------------------------------------------------------------------

    @Override
    public void onDrawStart(View view, boolean isRoot) {
        if (!isRoot) {
            return;
        }
        // 新的一帧：清空文本缓冲，重新累积
        frameText.setLength(0);

        // 节流重新读配置（最多每秒一次）。
        // XSharedPreferences 只在 reload() 时才重新读盘，
        // 配置变更广播没送达时，靠这里兜底，保证改设置后能自动生效。
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs > 1000L) {
            lastRefreshMs = now;
            Config cfg = config;
            if (cfg != null) {
                cfg.refresh();
            }
        }

        Config cfg = config;
        if (cfg == null) {
            return;
        }
        if (OverlayTracker.isInOverlay(view)) {
            logStateOnce(cfg);
        }
        if (cfg.needsAnimation() && OverlayTracker.isInOverlay(view)) {
            advance(cfg);
        }
    }

    /** 开关状态变化时记一笔，排查「关了还是彩色」时直接看这里 */
    private void logStateOnce(Config cfg) {
        String state = "enabled=" + cfg.enabled()
                + " desktop=" + cfg.desktopEnabled()
                + " status=" + cfg.statusEnabled()
                + " warp=" + cfg.warpEnabled()
                + " dissolve=" + cfg.dissolveEnabled()
                + " src=" + cfg.debugSource()
                + " " + cfg.debugStamp();
        if (!state.equals(lastStateLog)) {
            lastStateLog = state;
            XLog.i("state: " + state);
            DiagLog.put("state", state);
        }
    }

    @Override
    public void onDrawEnd(View view, boolean isRoot) {
        Config cfg = config;
        if (cfg == null) {
            return;
        }
        if (isRoot && OverlayTracker.isInOverlay(view)) {
            // 总开关关闭：清掉离屏位图和残留粒子，并停止驱动重绘。
            // 不做这一步的话，View 会一直在合成残留的旧帧，
            // 表现正是「关了开关却还是彩色的」。
            if (!cfg.enabled() || !cfg.anyTargetEnabled()) {
                LyricRenderer.release(view);
                return;
            }
            boolean dissolveActive = DissolveEffect.isActive(view);
            boolean needsFrame = cfg.flowSpeed() > 0 || cfg.breathing()
                    || cfg.warpEnabled() || cfg.tiltEnabled() || dissolveActive;
            if (needsFrame
                    && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE) {
                // 驱动下一帧，形成流光、呼吸、扭曲、消散动画
                view.postInvalidateOnAnimation();
            }
        }
    }

    @Override
    public void onDrawText(Object[] args, View current) {
        Config cfg = config;
        if (cfg == null || !cfg.enabled() || !cfg.anyTargetEnabled()) {
            return;
        }
        if (!OverlayTracker.isInOverlay(current)) {
            return;
        }
        // 先把这一段的文本记下来：逐字染色的歌词会拆成很多次 drawText，
        // 只有整帧拼起来才能正确判断「歌词换了没」
        appendText(args);

        if (DrawHook.handledByController(current)) {
            RainbowController controller = RainbowController.of(current);
            if (controller != null && !controller.isRunning()) {
                controller.start();
            }
            return; // TextView 已由 RainbowController 接管，避免双重改 alpha
        }
        Paint paint = paintOf(args);
        if (paint == null) {
            return;
        }
        // 注意：这里复制一份再改，而不是直接改宿主传进来的 Paint。
        // 宿主很可能复用同一个 Paint 去画描边、背景、图标，
        // 直接 setShader 会把那些元素也一起染成彩色 —— 这是误伤的另一大来源。
        Paint target = safePaint(paint);
        if (target == null) {
            return;
        }
        ensureGradient(cfg);
        LinearGradient shader = gradient;
        if (shader == null) {
            return;
        }
        target.setShader(shader);
        if (cfg.breathing()) {
            target.setAlpha((int) (255 * breathValue(cfg)));
        } else if (target.getAlpha() < 255) {
            target.setAlpha(255);
        }
        // 关键：把本次 drawText 入参里的原 Paint 换成副本，宿主这一次绘制才真用上渐变。
        // 之前只改副本不回写，宿主仍拿原 Paint 绘制，自绘歌词会完全不上色，
        // 离屏位图里也采不到彩色像素，消散自然没东西可飞。
        replacePaintArg(args, paint, target);
    }

    /** 把 args 里原来的 Paint 替换成改过的副本，只影响这一次绘制调用 */
    private static void replacePaintArg(Object[] args, Paint original, Paint replacement) {
        if (args == null || original == null || replacement == null || original == replacement) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == original) {
                args[i] = replacement;
                return;
            }
        }
    }

    /**
     * 取宿主 Paint 的副本，拿到后随便改，宿主的原对象不受影响。
     * <p>
     * 用 WeakHashMap：Paint 生命周期跟宿主走，宿主不要了我们也能回收。
     * 副本只在首次遇到时创建一次，之后每帧复用，没有额外分配。
     */
    private static Paint safePaint(Paint original) {
        if (original == null) {
            return null;
        }
        Paint copy = SAFE_PAINTS.get(original);
        if (copy == null) {
            try {
                copy = new Paint(original);
            } catch (Throwable t) {
                return null;
            }
            SAFE_PAINTS.put(original, copy);
        }
        return copy;
    }

    /** 宿主 Paint -> 我们自己的副本 */
    private static final Map<Paint, Paint> SAFE_PAINTS =
            new WeakHashMap<Paint, Paint>();

    /** 把 drawText 的几种重载里的文本取出来累积 */
    private void appendText(Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        Object first = args[0];
        try {
            if (first instanceof String) {
                frameText.append((String) first);
            } else if (first instanceof CharSequence) {
                // drawText(CharSequence, start, end, ...)
                CharSequence cs = (CharSequence) first;
                int start = 0;
                int end = cs.length();
                if (args.length > 2 && args[1] instanceof Integer
                        && args[2] instanceof Integer) {
                    start = (Integer) args[1];
                    end = (Integer) args[2];
                }
                frameText.append(cs.subSequence(Math.max(0, start),
                        Math.min(cs.length(), end)));
            } else if (first instanceof char[]) {
                char[] chars = (char[]) first;
                int index = 0;
                int len = chars.length;
                if (args.length > 2 && args[1] instanceof Integer
                        && args[2] instanceof Integer) {
                    index = (Integer) args[1];
                    len = (Integer) args[2];
                }
                frameText.append(chars, Math.max(0, index), Math.min(len, chars.length - index));
            }
        } catch (Throwable ignored) {
            // 文本提取失败不影响上色
        }
    }

    @Override
    public Canvas onReplaceCanvas(View view, Canvas real, boolean isRoot) {
        return LyricRenderer.begin(view, real, isRoot, config);
    }

    @Override
    public void onComposite(View view, Canvas real, boolean isRoot) {
        Config cfg = config;
        if (cfg == null || !isRoot) {
            return;
        }
        // 整帧文本拼完后归一化（去掉所有空白），只在「真正换成另一句」时判定一次变化。
        // 逐字染色歌词每帧 drawText 的分段/顺序可能轻微抖动，若逐帧比对会反复误判，
        // 使 DissolveEffect 粒子被反复重置、肉眼看不到飘散 —— 所以用「上次文本 + 冷却」双重防抖。
        String text = normalize(frameText);
        long nowMs = System.currentTimeMillis();
        boolean changed = !text.isEmpty()
                && !text.equals(lastBurstText)
                && nowMs - lastBurstMs > BURST_COOLDOWN_MS;
        if (changed) {
            lastBurstText = text;
            lastBurstMs = nowMs;
            XLog.d("lyric changed -> " + text);
        }
        LyricRenderer.composite(view, real, cfg, changed);
    }

    /** 去掉全部空白字符，避免逐字分段拼接时的空白/顺序抖动误判换句 */
    private static String normalize(CharSequence cs) {
        if (cs == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(cs.length());
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Paint paintOf(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Paint) {
                return (Paint) arg;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 流光 / 呼吸
    // ------------------------------------------------------------------

    /** 按绝对时间推进流光偏移（掉帧也连续，多 View 之间严格同步、不割裂） */
    private void advance(Config cfg) {
        LinearGradient shader = gradient;
        if (shader == null || cfg.flowSpeed() <= 0) {
            return;
        }
        FlowShader.advance(shader, MATRIX, cfg.flowSpeed());
    }

    private static float breathValue(Config cfg) {
        float min = cfg.breathMin();
        float seconds = System.nanoTime() / 1_000_000_000f;
        double phase = (seconds / Math.max(0.4f, cfg.breathPeriodMs() / 1000f)) % 1.0;
        return min + (1f - min) * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * phase));
    }

    /**
     * 流光渐变只随「颜色配置」重建，周期宽度固定（见 {@link FlowShader}），
     * 不再依赖 View 宽度 —— 桌面/状态栏两个宽度不同的悬浮窗、以及一句话拆出的
     * 众多 drawText 分段共用同一稳定色带，从根上消除宽度来回跳变造成的割裂。
     */
    private void ensureGradient(Config cfg) {
        int[] colors = cfg.colors();
        int[] last = lastColors;
        if (gradient != null && last != null && sameColors(last, colors)) {
            return;
        }
        lastColors = colors;
        gradient = FlowShader.create(colors);
    }

    private static boolean sameColors(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
