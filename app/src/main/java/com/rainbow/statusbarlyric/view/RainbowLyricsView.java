package com.rainbow.statusbarlyric.view;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.TextView;

import com.rainbow.statusbarlyric.core.Config;

/**
 * 设置界面里的效果预览 View。
 * <p>
 * 只用于展示流光/呼吸效果，<b>不参与任何 Hook</b> —— 模块不自己画歌词悬浮窗，
 * 改的是网易云自己的 View。
 */
public class RainbowLyricsView extends TextView {

    private RainbowController controller;
    private Config config;

    public RainbowLyricsView(Context context) {
        super(context);
        init();
    }

    public RainbowLyricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RainbowLyricsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        setIncludeFontPadding(false);
        setTextSize(16f);
        // 预览里先用渐变的第一个色起头，Shader 挂上后由它接管
        int[] colors = Config.parseColors(Config.DEFAULT_COLORS);
        if (colors != null && colors.length > 0) {
            setTextColor(colors[0]);
        }
    }

    public void setConfig(Config cfg) {
        this.config = cfg;
    }

    public void applyConfig(Config cfg) {
        if (cfg == null) {
            return;
        }
        setConfig(cfg);
        if (controller != null) {
            controller.apply(cfg);
        }
    }

    public void setLyric(String text) {
        final String value = text == null ? "" : text.trim();
        post(new Runnable() {
            @Override
            public void run() {
                if (value.isEmpty()) {
                    if (controller != null) {
                        controller.stop();
                    }
                    setText("");
                    return;
                }
                if (!value.equals(getText() == null ? "" : getText().toString())) {
                    setText(value);
                }
                // 本 View 会被设置界面 inflate，必须走无 Xposed 依赖的兜底配置
                Config cfg = config != null ? config
                        : new Config((android.content.SharedPreferences) null);
                controller = RainbowController.attach(RainbowLyricsView.this, cfg);
                if (controller != null) {
                    controller.start();
                }
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (controller != null) {
            controller.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (controller != null) {
            controller.stop();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (controller != null) {
            controller.onSizeChanged();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 颜色完全交给 Paint 上的 LinearGradient
        super.onDraw(canvas);
    }
}
