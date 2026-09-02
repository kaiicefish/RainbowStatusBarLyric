package com.rainbow.statusbarlyric.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 文字消散：歌词切换时，上一句从中心向两侧、向上「炸成粒子飞散」。
 * <p>
 * <b>素材从哪来：</b>
 * 自绘歌词不是 TextView，拿不到「文本」这个东西。但上一帧的离屏位图里
 * 就有上一句歌词的<b>像素</b> —— 直接按网格采样它，非透明的像素变成粒子，
 * 颜色连同渐变一起继承下来，所以飞出去的灰尘本身就是彩色的。
 * <p>
 * <b>飞散模型：</b>每个粒子带一个「以歌词中心为原点、向外侧」的水平初速度，
 * 叠加整体向上的初速度与轻微重力、湍流，形成向两边扬起再飘落的动势，
 * 而不是原地小幅抖动。数据结构用 SoA（并行 float 数组），零对象分配。
 */
public final class DissolveEffect {

    /** 粒子上限，超过就按 stride 抽稀 */
    private static final int MAX_PARTICLES = 1200;
    /** 采样下限：低于这个 alpha 的像素不值得变成粒子 */
    private static final int MIN_ALPHA = 36;
    /** 轻微回拉的重力，整体仍是向上飞，数值刻意偏小 */
    private static final float GRAVITY = 14f;

    private static final float[] px = new float[MAX_PARTICLES];
    private static final float[] py = new float[MAX_PARTICLES];
    private static final float[] vx = new float[MAX_PARTICLES];
    private static final float[] vy = new float[MAX_PARTICLES];
    private static final int[] pcolor = new int[MAX_PARTICLES];
    private static final float[] plife = new float[MAX_PARTICLES];
    private static final float[] pmax = new float[MAX_PARTICLES];
    private static final float[] pseed = new float[MAX_PARTICLES];

    private static int count = 0;
    private static long lastNanos = 0;
    /** 本批粒子的起始时刻，湍流相位以它为原点，避免 float 精度损失 */
    private static long startNanos = 0;
    private static int[] pixelBuf = null;
    /**
     * 粒子归属的 View。桌词和状态栏歌词可能同时存在，而粒子池全局共享，
     * 不记录归属会被两个悬浮窗各画一遍，凭空多出重复灰尘。
     */
    private static View owner = null;

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    private DissolveEffect() {
    }

    /** 从上一帧快照生成粒子 */
    static synchronized void burst(View view, Bitmap src, int pad, Config cfg) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return;
        }
        owner = view;
        // 新一批开始先清空，避免快照全透明提前 return 时残留上一批
        count = 0;
        int w = src.getWidth();
        int h = src.getHeight();
        int need = w * h;
        if (pixelBuf == null || pixelBuf.length < need) {
            try {
                pixelBuf = new int[need];
            } catch (Throwable t) {
                XLog.e("dissolve buffer alloc failed: " + t);
                return;
            }
        }
        try {
            src.getPixels(pixelBuf, 0, w, 0, 0, w, h);
        } catch (Throwable t) {
            XLog.e("dissolve getPixels failed: " + t);
            return;
        }

        // 第一遍：统计非透明像素，用来算抽稀步长
        int solid = 0;
        for (int i = 0; i < need; i++) {
            if ((pixelBuf[i] >>> 24) >= MIN_ALPHA) {
                solid++;
            }
        }
        if (solid == 0) {
            return;
        }
        int step = Math.max(1, cfg.dissolveStep());
        int stride = Math.max(1, (int) Math.ceil(solid / (double) MAX_PARTICLES));

        float life = Math.max(0.2f, cfg.dissolveLifeS());
        float rise = cfg.dissolveRise();
        float spread = cfg.dissolveSpread();
        // 歌词内容中心（View 坐标），粒子从这里向两侧飞散
        float cx = (w - pad * 2) / 2f;
        float cy = (h - pad * 2) / 2f;
        float halfW = Math.max(1f, cx);

        count = 0;
        int seen = 0;
        for (int y = 0; y < h && count < MAX_PARTICLES; y += step) {
            for (int x = 0; x < w && count < MAX_PARTICLES; x += step) {
                int argb = pixelBuf[y * w + x];
                if ((argb >>> 24) < MIN_ALPHA) {
                    continue;
                }
                if (seen++ % stride != 0) {
                    continue;
                }
                int idx = count++;
                // 位图带 padding，减掉才是 View 内真实坐标
                float posX = x - pad;
                float posY = y - pad;
                px[idx] = posX;
                py[idx] = posY;

                // -1..1，越靠两侧向外飞越快
                float dir = (posX - cx) / halfW;
                float r1 = (x % 17) / 17f - 0.5f;
                float r2 = (y % 13) / 13f - 0.5f;
                // 水平：向外侧飞 + 随机扰动，保证中间粒子也有方向
                float outward = spread * (0.55f + 0.9f * Math.abs(dir));
                vx[idx] = dir * outward + r1 * spread * 0.5f;
                // 垂直：整体向上扬起，带随机快慢
                vy[idx] = -rise * (0.6f + 0.95f * (0.5f - r2));

                pcolor[idx] = argb;
                pmax[idx] = life;
                plife[idx] = life;
                pseed[idx] = (x * 0.37f + y * 0.71f);
            }
        }
        startNanos = System.nanoTime();
        lastNanos = startNanos;
        XLog.d("dissolve burst: " + count + " particles");
    }

    /** 绘制并推进粒子；全部消亡后不再产生开销 */
    static synchronized void draw(View view, Canvas canvas, Config cfg) {
        if (canvas == null) {
            return;
        }
        // 不是这批粒子的主人就跳过
        if (count == 0 || owner != view) {
            return;
        }
        if (cfg == null || !cfg.dissolveEnabled()) {
            count = 0;
            return;
        }

        long now = System.nanoTime();
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        if (dt > 0.1f) {
            dt = 0.1f;
        }
        if (dt < 0f) {
            dt = 0f;
        }

        double t = (now - startNanos) / 1e9;
        float size = cfg.dissolveSize();

        int alive = 0;
        for (int i = 0; i < count; i++) {
            float life = plife[i] - dt;
            if (life <= 0f) {
                continue;
            }
            plife[i] = life;
            alive++;

            // 水平湍流 + 轻微空气阻力
            float wobble = (float) (Math.sin(t * 2.4 + pseed[i]) * 5.0);
            px[i] += (vx[i] + wobble) * dt;
            vy[i] += GRAVITY * dt;
            py[i] += vy[i] * dt;
            vx[i] *= (1f - 0.6f * dt);

            float k = life / pmax[i];                 // 1 -> 0
            // sqrt 让粒子前段保持明亮、尾段才快速淡出，整体更看得见
            int alpha = (int) (255 * Math.sqrt(k));
            if (alpha <= 2) {
                continue;
            }
            int argb = pcolor[i];
            PAINT.setColor((argb & 0x00FFFFFF) | (Math.min(255, alpha) << 24));
            // 圆形柔光粒子，半径随生命从大收到小
            float r = size * (0.35f + 0.65f * k) * 0.5f;
            canvas.drawCircle(px[i], py[i], r, PAINT);
        }

        if (alive == 0) {
            count = 0;
            owner = null;
        }
    }

    static synchronized void clear() {
        count = 0;
        owner = null;
    }

    /** 某个 View 销毁时清掉它名下的粒子 */
    static synchronized void clear(View view) {
        if (owner == view) {
            count = 0;
            owner = null;
        }
    }

    static synchronized int count() {
        return count;
    }

    /** 该 View 是否还有正在飘散的粒子；用于只在动画期间驱动下一帧 */
    static synchronized boolean isActive(View view) {
        return count > 0 && owner == view;
    }
}
