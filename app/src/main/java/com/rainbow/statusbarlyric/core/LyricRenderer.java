package com.rainbow.statusbarlyric.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 离屏渲染统一调度：扭曲 / 消散 / 陀螺仪摆动都需要「先把整句歌词画到离屏位图、
 * 处理后再贴回真实画布」，共用同一套离屏，避免多层离屏叠加。
 * <p>
 * 一个宿主 View 对应一份 {@link State}（桌面歌词、状态栏歌词各一份）。
 * 流程（由 {@link PaintTinter} 在 {@link DrawHook} 回调里驱动）：
 * <pre>
 *   begin：返回离屏 canvas 让宿主正常绘制（不需要离屏就原样返回 real）
 *   end  ：宿主画完后，按配置扭曲合成 / 直接贴回 / 摆动旋转，并推进消散粒子
 * </pre>
 * 「歌词是否换句」由 {@link PaintTinter} 比对文本后以 changed 传入，
 * 粒子状态（{@link DissolveEffect}）全局共享，{@link State} 只存离屏位图。
 */
public final class LyricRenderer {

    private LyricRenderer() {
    }

    /** 每个宿主 View 的离屏状态 */
    static final class State {
        Bitmap bitmap;
        Canvas canvas;
        /** 上一帧快照，消散放飞的是旧句而不是刚画好的新句 */
        Bitmap prevBitmap;
        Canvas prevCanvas;
        int pad;
        /** View 自身宽高（不含 pad），摆动以它为旋转中心 */
        int vw;
        int vh;
    }

    private static final Map<View, State> STATES = new WeakHashMap<>();

    /** 是否需要离屏：总开关开、命中目标窗口、且任一离屏特效开启 */
    static boolean needed(View view, Config cfg) {
        if (cfg == null || !cfg.enabled() || view == null) {
            return false;
        }
        if (!OverlayTracker.isInOverlay(view)) {
            return false;
        }
        return cfg.warpEnabled() || cfg.dissolveEnabled() || cfg.tiltEnabled();
    }

    private static State stateOf(View view) {
        State s = STATES.get(view);
        if (s == null) {
            s = new State();
            STATES.put(view, s);
        }
        return s;
    }

    static void remove(View view) {
        State s = STATES.remove(view);
        recycle(s);
        DissolveEffect.clear(view);
    }

    /** 开关关闭时由 PaintTinter 调用：停掉该 View 的离屏与残留粒子 */
    static void release(View view) {
        remove(view);
    }

    /** 全部离屏效果关闭 / 配置重置时，释放所有离屏位图与粒子 */
    static void releaseAll() {
        clear();
    }

    static void clear() {
        for (State s : STATES.values()) {
            recycle(s);
        }
        STATES.clear();
        DissolveEffect.clear();
    }

    private static void recycle(State s) {
        if (s == null) {
            return;
        }
        if (s.bitmap != null) {
            s.bitmap.recycle();
            s.bitmap = null;
        }
        if (s.prevBitmap != null) {
            s.prevBitmap.recycle();
            s.prevBitmap = null;
        }
        s.canvas = null;
        s.prevCanvas = null;
    }

    /**
     * draw 前：需要离屏就返回离屏画布，否则原样返回 real。
     */
    static Canvas begin(View view, Canvas real, boolean isRoot, Config cfg) {
        if (real == null || !isRoot || !needed(view, cfg)) {
            return real;
        }
        int vw = view.getWidth();
        int vh = view.getHeight();
        if (vw <= 0 || vh <= 0) {
            return real;
        }

        // 扭曲分块要读边缘外像素，四周留边；摆动旋转也需要额外边距避免转出裁边
        int pad = 4;
        if (cfg.warpEnabled()) {
            pad = (int) Math.ceil(cfg.warpAmplitude() * 1.6) + 4;
            pad = Math.min(pad, 48);
        }
        if (cfg.tiltEnabled()) {
            double maxRad = Math.toRadians(cfg.tiltMaxAngle());
            // 扁长的歌词行旋转时，横向半宽在竖直方向的投影最大
            int tiltPad = (int) Math.ceil(vw * Math.sin(maxRad) / 2f) + 6;
            pad = Math.max(pad, tiltPad);
        }

        int bw = vw + pad * 2;
        int bh = vh + pad * 2;

        State s = stateOf(view);
        boolean sizeChanged = s.bitmap == null
                || s.bitmap.getWidth() != bw || s.bitmap.getHeight() != bh;
        if (sizeChanged) {
            if (s.bitmap != null) {
                s.bitmap.recycle();
            }
            try {
                s.bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                s.canvas = new Canvas(s.bitmap);
                // software 离屏 Canvas 不会自动走 RecordingCanvas 的 hook，
                // 必须显式挂一次 drawText，否则画到离屏上的文字挂不上渐变
                DrawHook.ensureCanvasHooked(s.canvas);
            } catch (Throwable t) {
                XLog.e("offscreen alloc failed " + bw + "x" + bh + ": " + t);
                s.bitmap = null;
                s.canvas = null;
                return real;
            }
        }

        // 新帧绘制前先把上一帧（旧句）备份下来，供消散放飞
        if (cfg.dissolveEnabled()) {
            boolean prevChanged = s.prevBitmap == null
                    || s.prevBitmap.getWidth() != bw || s.prevBitmap.getHeight() != bh;
            if (prevChanged) {
                if (s.prevBitmap != null) {
                    s.prevBitmap.recycle();
                }
                try {
                    s.prevBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    s.prevCanvas = new Canvas(s.prevBitmap);
                } catch (Throwable t) {
                    XLog.e("prev offscreen alloc failed: " + t);
                    s.prevBitmap = null;
                    s.prevCanvas = null;
                }
            }
            if (s.prevCanvas != null && !sizeChanged) {
                s.prevBitmap.eraseColor(0);
                s.prevCanvas.drawBitmap(s.bitmap, 0f, 0f, null);
            }
        }

        s.pad = pad;
        s.vw = vw;
        s.vh = vh;
        s.bitmap.eraseColor(0);
        // 每帧从单位矩阵开始，避免上一帧的 translate 逐帧累积偏移
        s.canvas.setMatrix(null);
        s.canvas.translate(pad, pad);
        return s.canvas;
    }

    /**
     * 真正的合成，real 为真实画布（由 PaintTinter.onComposite 传入）；
     * changed 表示本帧歌词相对上一句发生变化（已做防抖）。
     */
    static void composite(View view, Canvas real, Config cfg, boolean changed) {
        if (real == null) {
            return;
        }
        State s = STATES.get(view);
        if (s == null || s.bitmap == null) {
            return;
        }

        boolean warpEnabled = cfg.warpEnabled();
        boolean dissolveEnabled = cfg.dissolveEnabled();
        boolean tiltEnabled = cfg.tiltEnabled();

        // 消散：歌词换句时把上一帧（旧句）炸成粒子
        if (dissolveEnabled && changed && s.prevBitmap != null) {
            DissolveEffect.burst(view, s.prevBitmap, s.pad, cfg);
        }

        // 陀螺仪摆动：以 View 中心为锚整体小幅旋转（粒子也在同一坐标系内）
        boolean tilted = false;
        if (tiltEnabled) {
            float maxAngle = cfg.tiltMaxAngle();
            SensorTilt tilt = SensorTilt.get();
            // 手机倾斜约 15° 时摆到设定的最大角度，并限幅
            float angle = (float) Math.toDegrees(tilt.roll()) * (maxAngle / 15f);
            angle = Math.max(-maxAngle, Math.min(maxAngle, angle));
            float ty = (float) (-Math.toDegrees(tilt.pitch()) * (maxAngle / 15f) * 0.35f);
            if (Math.abs(angle) > 0.05f || Math.abs(ty) > 0.05f) {
                float cx = s.vw / 2f;
                float cy = s.vh / 2f;
                real.save();
                real.translate(cx, cy);
                real.rotate(angle);
                real.translate(-cx, -cy + ty);
                tilted = true;
            }
        }

        if (warpEnabled) {
            WarpEffect.composite(real, s.bitmap, s.pad, cfg);
        } else {
            real.drawBitmap(s.bitmap, -s.pad, -s.pad, null);
        }
        if (dissolveEnabled) {
            DissolveEffect.draw(view, real, cfg);
        }
        if (tilted) {
            real.restore();
        }
    }
}
