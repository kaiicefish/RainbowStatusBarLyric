package com.rainbow.statusbarlyric.core;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;

/**
 * 剥掉文本里自带的前景色 span。
 * <p>
 * 网易云的桌面歌词常用 {@link ForegroundColorSpan} 做「逐字染色」进度效果。
 * 而 span 的优先级高于 Paint 上的 Shader —— 不剥掉的话，
 * 我们设的渐变会被网易云自己的颜色盖住，看起来就是「改了没生效」。
 */
public final class SpanStripper {

    private SpanStripper() {
    }

    /**
     * 移除所有影响文字颜色的 span，只保留纯文本。
     * 非 Spanned 或本来就没颜色 span 时直接返回原对象，避免无谓的拷贝。
     */
    public static CharSequence strip(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return text;
        }
        Spanned spanned = (Spanned) text;
        Object[] spans = spanned.getSpans(0, spanned.length(), Object.class);
        if (spans == null || spans.length == 0) {
            return text;
        }

        boolean found = false;
        for (Object span : spans) {
            if (isColorSpan(span)) {
                found = true;
                break;
            }
        }
        if (!found) {
            return text;
        }

        SpannableStringBuilder builder = new SpannableStringBuilder(spanned.toString());
        Object[] cloned = builder.getSpans(0, builder.length(), Object.class);
        for (Object span : cloned) {
            if (isColorSpan(span)) {
                builder.removeSpan(span);
            }
        }
        return builder;
    }

    /** 是否会影响文字颜色 */
    private static boolean isColorSpan(Object span) {
        if (span instanceof ForegroundColorSpan) {
            return true;
        }
        // 逐字染色常用自定义的 CharacterStyle 子类，这里按类名兜底识别，
        // 覆盖不到时也不影响主效果，只是那部分字符保持网易云的颜色。
        if (span instanceof CharacterStyle) {
            String name = span.getClass().getName();
            return name.contains("Color") || name.contains("color")
                    || name.contains("Karaoke") || name.contains("karaoke")
                    || name.contains("Highlight");
        }
        return false;
    }

    /** 该文本是否带颜色 span，用于日志排查 */
    public static boolean hasColorSpan(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return false;
        }
        Spanned spanned = (Spanned) text;
        Object[] spans = spanned.getSpans(0, spanned.length(), Object.class);
        if (spans == null) {
            return false;
        }
        for (Object span : spans) {
            if (isColorSpan(span)) {
                return true;
            }
        }
        return false;
    }
}
