package com.rainbow.statusbarlyric.core;

import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;

/**
 * 流光渐变的统一工厂，解决旧实现「割裂、一顿一顿」的问题。
 * <p>
 * 旧代码各自 new {@link LinearGradient}，周期宽度取 View 宽度，而桌面歌词、
 * 状态栏歌词宽度不同，一句话又会拆成很多段 drawText，导致 shader 被反复重建、
 * 周期来回跳变，肉眼就是色带割裂。这里统一三点：
 * <ol>
 *   <li><b>固定周期</b>：{@link #SPAN} 与 View 宽度无关，所有 View、所有分段共用
 *       同一宽度的色带，永远不会因宽度变化重建。</li>
 *   <li><b>首尾无缝</b>：用户给的颜色首尾若不一致，自动把首色补到末尾，
 *       配合 {@link Shader.TileMode#REPEAT}，接缝处颜色相同、循环无跳变。</li>
 *   <li><b>绝对时间相位</b>：偏移量由系统时间直接算出（{@link #offset}），
 *       不依赖上一帧累加，掉帧/卡顿后相位依然连续，不会出现步进抖动。</li>
 * </ol>
 */
public final class FlowShader {

    /** 一个完整彩虹周期的像素宽度，固定值，跨 View 一致 */
    public static final float SPAN = 360f;

    private FlowShader() {
    }

    /** 生成横向、可水平平移的无缝 REPEAT 渐变 */
    public static LinearGradient create(int[] colors) {
        int[] seamless = seamless(colors);
        return new LinearGradient(0f, 0f, SPAN, 0f, seamless, null, Shader.TileMode.REPEAT);
    }

    /** 保证首尾颜色一致，REPEAT 拼接处才不会有硬接缝 */
    private static int[] seamless(int[] colors) {
        if (colors == null || colors.length < 2) {
            return new int[]{0xFFFF4D6D, 0xFF9D7BFF, 0xFFFF4D6D};
        }
        if (colors[0] == colors[colors.length - 1]) {
            return colors;
        }
        int[] out = new int[colors.length + 1];
        System.arraycopy(colors, 0, out, 0, colors.length);
        out[out.length - 1] = colors[0];
        return out;
    }

    /** 绝对时间驱动的水平相位，范围 [0, SPAN)，掉帧也连续 */
    public static float offset(int flowSpeedPxPerSecond) {
        if (flowSpeedPxPerSecond <= 0) {
            return 0f;
        }
        float t = System.nanoTime() / 1_000_000_000f;
        return (t * flowSpeedPxPerSecond) % SPAN;
    }

    /** 把 shader 平移到当前相位（复用 Matrix，零分配） */
    public static void advance(Shader shader, Matrix scratch, int flowSpeedPxPerSecond) {
        if (shader == null || scratch == null) {
            return;
        }
        scratch.setTranslate(offset(flowSpeedPxPerSecond), 0f);
        shader.setLocalMatrix(scratch);
    }
}
