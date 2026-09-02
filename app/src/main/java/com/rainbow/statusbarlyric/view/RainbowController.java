package com.rainbow.statusbarlyric.view;

import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;
import android.widget.TextView;

import com.rainbow.statusbarlyric.core.Config;
import com.rainbow.statusbarlyric.core.FlowShader;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * 彩虹流光 + 呼吸控制器，作用于 <b>TextView 形态</b>的歌词。
 * <p>
 * 原理：给 TextView 的 Paint 设置一个 REPEAT 模式的 {@link LinearGradient}，
 * 每帧用 Matrix 平移 shader 形成流光；同时周期性改 alpha 形成呼吸。
 * <p>
 * <b>为什么能盖住网易云自己的颜色：</b>
 * Paint 上挂了 Shader 之后，文字绘制走 Shader 取色，
 * {@code setTextColor} 设的 color 不再生效。而控制器每帧都会重新 setShader，
 * 所以网易云后续无论怎么改颜色，都会被下一帧覆盖回来。
 * <p>
 * 自绘形态的歌词不走这里，由 {@code PaintTinter} 处理。
 */
public class RainbowController {

    private static final WeakHashMap<View, RainbowController> ATTACHED = new WeakHashMap<>();

    private final View target;
    private final Paint paint;
    private final Matrix matrix = new Matrix();

    private LinearGradient gradient;
    private double breathPhase;

    private int[] colors;
    private float flowSpeed = 80f;      // px/s
    private float breathMin = 0.6f;     // 呼吸最低亮度
    private float breathPeriod = 2.6f;  // 秒
    private boolean breathEnabled = true;

    private boolean running;
    private long lastNanos;
    /** 最近一次 apply 的配置，用于每帧自查总开关 */
    private Config currentConfig;

    private RainbowController(View target) {
        this.target = target;
        this.paint = target instanceof TextView ? ((TextView) target).getPaint() : null;
        this.colors = new int[]{0xFFFF4D6D, 0xFF4CC9F0, 0xFFFF4D6D};
    }

    /** 给一个 TextView 附加效果（已附加则复用） */
    public static RainbowController attach(View view, Config cfg) {
        if (!(view instanceof TextView) || view.getContext() == null) {
            return null;
        }
        RainbowController controller = ATTACHED.get(view);
        if (controller == null) {
            controller = new RainbowController(view);
            ATTACHED.put(view, controller);
        }
        controller.apply(cfg);
        controller.start();
        return controller;
    }

    public static RainbowController of(View view) {
        return view == null ? null : ATTACHED.get(view);
    }

    public static boolean isAttached(View view) {
        return view != null && ATTACHED.containsKey(view);
    }

    /** 应用（刷新）参数，可在运行中调用 */
    public void apply(Config cfg) {
        if (cfg == null || paint == null) {
            return;
        }
        cfg.refresh();
        currentConfig = cfg;
        colors = cfg.colors();
        flowSpeed = cfg.flowSpeed();
        breathMin = cfg.breathMin();
        breathEnabled = cfg.breathing();
        breathPeriod = Math.max(0.4f, cfg.breathPeriodMs() / 1000f);
        gradient = null;
        ensureGradient();
        paint.setShader(gradient);
        lastNanos = System.nanoTime();
        target.invalidate();
    }

    public void start() {
        if (paint == null) {
            return;
        }
        lastNanos = System.nanoTime();
        if (running) {
            return;
        }
        running = true;
        target.postOnAnimation(tick);
    }

    public void stop() {
        running = false;
        try {
            target.removeCallbacks(tick);
        } catch (Throwable ignored) {
        }
        target.setAlpha(1f);
    }

    public boolean isRunning() {
        return running;
    }

    public void onSizeChanged() {
        // 流光周期固定、与宽度无关（见 FlowShader），尺寸变化无需重建 shader
    }

    /** 配置变更广播到达时，刷新所有已附加的歌词 View */
    public static void refreshAll(Config cfg) {
        for (View v : new ArrayList<>(ATTACHED.keySet())) {
            if (v == null) {
                continue;
            }
            RainbowController c = ATTACHED.get(v);
            if (c != null) {
                c.apply(cfg);
            }
        }
    }

    /** 设置项整体关闭时，把所有 View 恢复成网易云原本的样子 */
    public static void detachAll() {
        for (View v : new ArrayList<>(ATTACHED.keySet())) {
            if (v == null) {
                continue;
            }
            RainbowController c = ATTACHED.get(v);
            if (c != null) {
                c.restore();
            }
        }
        ATTACHED.clear();
    }

    /** 移除渐变与 alpha，交还给宿主控制 */
    private void restore() {
        stop();
        if (paint != null) {
            paint.setShader(null);
        }
        target.setAlpha(1f);
        target.invalidate();
        // 关键：从表里摘掉自己。
        // 不摘的话下次 attach 会复用这个已经 restore 过的实例，
        // isAttached() 恒为 true，导致重新启用后颜色再也回不来。
        synchronized (ATTACHED) {
            ATTACHED.remove(target);
        }
    }

    private void ensureGradient() {
        if (gradient != null) {
            return;
        }
        // 固定周期、首尾无缝的 REPEAT 渐变，与 PaintTinter 完全同一套，宽度无关
        gradient = FlowShader.create(colors);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || paint == null) {
                return;
            }
            // 总开关关掉时逐帧自查并复原：
            // 否则已经挂上的渐变会一直重绘下去，看着就是「关了也还是彩色」
            if (currentConfig != null
                    && (!currentConfig.enabled() || !currentConfig.anyTargetEnabled())) {
                restore();
                return;
            }
            if (!target.isAttachedToWindow() || target.getVisibility() != View.VISIBLE) {
                stop();
                return;
            }
            if (target instanceof TextView) {
                CharSequence text = ((TextView) target).getText();
                if (text == null || text.length() == 0) {
                    stop();
                    return;
                }
            }

            ensureGradient();

            // 流光：绝对时间驱动，掉帧/卡顿后相位依然连续，不会一顿一顿；
            // 每帧重设 shader 也保证把宿主自己改的颜色盖回去
            if (flowSpeed > 0) {
                FlowShader.advance(gradient, matrix, (int) flowSpeed);
            }
            paint.setShader(gradient);

            // 呼吸同样用绝对时间，避免帧间隔抖动造成的明暗顿挫
            if (breathEnabled) {
                float seconds = System.nanoTime() / 1_000_000_000f;
                breathPhase = (seconds / breathPeriod) % 1.0;
                float value = breathMin + (1f - breathMin)
                        * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * breathPhase));
                target.setAlpha(value);
            } else if (target.getAlpha() < 1f) {
                target.setAlpha(1f);
            }

            target.invalidate();
            target.postOnAnimation(tick);
        }
    };
}
