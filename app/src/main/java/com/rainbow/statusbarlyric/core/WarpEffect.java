package com.rainbow.statusbarlyric.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * 文字扭曲效果（热浪 / 水波 / 抖动）。
 * <p>
 * <b>原理：拿到已渲染好的位图，分块位移后重绘。</b>
 * 离屏渲染由 {@link LyricRenderer} 统一负责 ——
 * View 的一切绘制（渐变、描边、阴影、背景）已经落在那张位图上，
 * 这里只负责「怎么把它贴回去」。
 * <p>
 * <b>横向白条/断笔是怎么来的、怎么消：</b>位图被切成一条条水平切片分别位移，
 * 相邻切片的垂直位移一旦不同，中间就会露出一条没被画到的缝，底色透上来，
 * 看着就是「横着的白条把字切开」。修法是让每个切片在垂直方向<b>上下多取
 * {@link #OVERLAP}px 与相邻切片重叠</b>，缝隙被相邻切片互相盖住；同时给
 * drawBitmap 开双线性过滤，错位边缘是柔和过渡而不是硬锯齿。
 */
public final class WarpEffect {

    /** 热浪/抖动的切片高度（px），越小越平滑、开销越大 */
    private static final int SLICE_HEAT = 2;
    private static final int SLICE_JITTER = 3;
    /** 水波的网格：固定列数，行数按高度算 */
    private static final int RIPPLE_COLS = 10;
    private static final int SLICE_RIPPLE = 8;
    /** 相邻切片重叠的像素数，用来盖住位移产生的缝隙 */
    private static final int OVERLAP = 2;

    private static final Rect SRC = new Rect();
    private static final RectF DST = new RectF();
    /** 双线性过滤：切片错位时边缘柔和，不出现硬边/白线 */
    private static final Paint FILTER_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);

    private WarpEffect() {
    }

    /** 把位图扭曲后画到真实 canvas */
    public static void composite(Canvas real, Bitmap bmp, int pad, Config cfg) {
        if (real == null || bmp == null || cfg == null) {
            return;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        float t = System.nanoTime() / 1_000_000_000f;
        float amp = cfg.warpAmplitude();
        float freq = cfg.warpFrequency();
        float phase = t * cfg.warpSpeed();

        switch (cfg.warpEffect()) {
            case Config.WARP_RIPPLE:
                compositeRipple(real, bmp, w, h, pad, amp, freq, phase);
                break;
            case Config.WARP_JITTER:
                compositeJitter(real, bmp, w, h, pad, amp, t);
                break;
            default:
                compositeHeat(real, bmp, w, h, pad, amp, freq, phase);
                break;
        }
    }

    /**
     * 热浪：逐行水平正弦位移，相位随时间向上流动。
     * 包络让上下边缘抖动大、中间稳，更接近热空气上升的观感。
     * 只做水平位移（不再加垂直 dy），从源头避免水平条带上下错开露缝。
     */
    private static void compositeHeat(Canvas real, Bitmap bmp, int w, int h, int pad,
                                      float amp, float freq, float phase) {
        for (int y = 0; y < h; y += SLICE_HEAT) {
            int bottom = Math.min(y + SLICE_HEAT, h);
            float ny = y / (float) h;
            float env = 0.35f + 0.65f * Math.abs(ny - 0.5f) * 2f;
            float dx = (float) Math.sin(y * freq - phase) * amp * env;
            blitRow(real, bmp, 0, w, y, bottom, w, h, pad, dx, 0f);
        }
    }

    /** 水波：从中心向外的径向位移，按网格分块，四向重叠消缝 */
    private static void compositeRipple(Canvas real, Bitmap bmp, int w, int h, int pad,
                                        float amp, float freq, float phase) {
        float cx = w / 2f;
        float cy = h / 2f;
        int stepX = Math.max(8, w / RIPPLE_COLS);
        for (int y = 0; y < h; y += SLICE_RIPPLE) {
            int bottom = Math.min(y + SLICE_RIPPLE, h);
            float my = (y + SLICE_RIPPLE / 2f) - cy;
            for (int x = 0; x < w; x += stepX) {
                int right = Math.min(x + stepX, w);
                float mx = (x + stepX / 2f) - cx;
                float dist = (float) Math.sqrt(mx * mx + my * my);
                float k = (float) Math.sin(dist * freq * 2f - phase * 1.6f);
                float norm = dist > 0.5f ? dist : 0.5f;
                float dx = k * amp * mx / norm;
                float dy = k * amp * my / norm;
                blitCell(real, bmp, x, right, y, bottom, w, h, pad, dx, dy);
            }
        }
    }

    /** 抖动：每行伪随机偏移，按帧刷新，火焰跳动感 */
    private static void compositeJitter(Canvas real, Bitmap bmp, int w, int h, int pad,
                                        float amp, float t) {
        int frame = (int) (t * 24);
        for (int y = 0; y < h; y += SLICE_JITTER) {
            int bottom = Math.min(y + SLICE_JITTER, h);
            float r1 = hash(y, frame);
            float r2 = hash(y + 9973, frame);
            float dx = (r1 - 0.5f) * 2f * amp;
            float dy = (r2 - 0.5f) * 2f * amp * 0.35f;
            blitRow(real, bmp, 0, w, y, bottom, w, h, pad, dx, dy);
        }
    }

    /** 水平整行条带：垂直方向上下重叠 OVERLAP，盖住相邻条带间的横缝 */
    private static void blitRow(Canvas real, Bitmap bmp, int left, int right,
                                int top, int bottom, int w, int h, int pad,
                                float dx, float dy) {
        int srcTop = Math.max(0, top - OVERLAP);
        int srcBottom = Math.min(h, bottom + OVERLAP);
        blit(real, bmp, left, right, srcTop, srcBottom, pad, dx, dy);
    }

    /** 水波网格块：四向都重叠 OVERLAP */
    private static void blitCell(Canvas real, Bitmap bmp, int left, int right,
                                 int top, int bottom, int w, int h, int pad,
                                 float dx, float dy) {
        int srcLeft = Math.max(0, left - OVERLAP);
        int srcRight = Math.min(w, right + OVERLAP);
        int srcTop = Math.max(0, top - OVERLAP);
        int srcBottom = Math.min(h, bottom + OVERLAP);
        blit(real, bmp, srcLeft, srcRight, srcTop, srcBottom, pad, dx, dy);
    }

    /** 把位图的一块按位移画到真实 canvas（源/目同尺寸对齐） */
    private static void blit(Canvas real, Bitmap bmp, int left, int right,
                             int top, int bottom, int pad, float dx, float dy) {
        SRC.set(left, top, right, bottom);
        DST.set(left + dx - pad, top + dy - pad,
                right + dx - pad, bottom + dy - pad);
        real.drawBitmap(bmp, SRC, DST, FILTER_PAINT);
    }

    /** 经典 hash 伪随机，返回 [0,1) */
    private static float hash(float a, float b) {
        float v = (float) (Math.sin(a * 12.9898f + b * 78.233f) * 43758.5453f);
        return v - (float) Math.floor(v);
    }
}
