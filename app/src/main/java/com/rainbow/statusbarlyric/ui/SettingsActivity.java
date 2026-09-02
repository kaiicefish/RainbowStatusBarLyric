package com.rainbow.statusbarlyric.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.rainbow.statusbarlyric.R;
import com.rainbow.statusbarlyric.core.Config;
import com.rainbow.statusbarlyric.core.ConfigStore;
import com.rainbow.statusbarlyric.core.DiagLog;
import com.rainbow.statusbarlyric.core.LyricBus;
import com.rainbow.statusbarlyric.view.RainbowController;
import com.rainbow.statusbarlyric.view.RainbowLyricsView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class SettingsActivity extends Activity {

    private CheckBox cbEnable;
    private CheckBox cbDesktop;
    private CheckBox cbStatus;
    private SeekBar sbFlow;
    private SeekBar sbBreath;
    private SeekBar sbPeriod;
    private EditText etColors;
    private LinearLayout rowSwatches;
    private TextView tvFlow;
    private TextView tvBreath;
    private TextView tvPeriod;
    private Spinner spWarp;
    private SeekBar sbWarpAmp;
    private SeekBar sbWarpFreq;
    private SeekBar sbWarpSpeed;
    private TextView tvWarpAmp;
    private TextView tvWarpFreq;
    private TextView tvWarpSpeed;
    private CheckBox cbDissolve;
    private SeekBar sbDissolveStep;
    private SeekBar sbDissolveLife;
    private SeekBar sbDissolveRise;
    private SeekBar sbDissolveSpread;
    private SeekBar sbDissolveSize;
    private TextView tvDissolveStep;
    private TextView tvDissolveLife;
    private TextView tvDissolveRise;
    private TextView tvDissolveSpread;
    private TextView tvDissolveSize;
    private CheckBox cbTilt;
    private SeekBar sbTiltSens;
    private TextView tvTiltSens;
    private RainbowLyricsView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViews();
        setupRanges();
        bindLabels();
        bindActions();
        showVersion();
        load();
    }

    /** 顶部显示版本号，装没装上新包一眼可辨 */
    private void showVersion() {
        TextView tv = findViewById(R.id.tv_version);
        if (tv == null) {
            return;
        }
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        tv.setText(getString(R.string.tips_header) + "\n当前版本：v" + ver
                + "");
    }

    private void findViews() {
        cbEnable = findViewById(R.id.cb_enable);
        cbDesktop = findViewById(R.id.cb_desktop);
        cbStatus = findViewById(R.id.cb_status);
        sbFlow = findViewById(R.id.sb_flow);
        sbBreath = findViewById(R.id.sb_breath);
        sbPeriod = findViewById(R.id.sb_period);
        etColors = findViewById(R.id.et_colors);
        rowSwatches = findViewById(R.id.row_swatches);
        tvFlow = findViewById(R.id.tv_flow);
        tvBreath = findViewById(R.id.tv_breath);
        tvPeriod = findViewById(R.id.tv_period);
        spWarp = findViewById(R.id.sp_warp);
        sbWarpAmp = findViewById(R.id.sb_warp_amp);
        sbWarpFreq = findViewById(R.id.sb_warp_freq);
        sbWarpSpeed = findViewById(R.id.sb_warp_speed);
        tvWarpAmp = findViewById(R.id.tv_warp_amp);
        tvWarpFreq = findViewById(R.id.tv_warp_freq);
        tvWarpSpeed = findViewById(R.id.tv_warp_speed);
        cbDissolve = findViewById(R.id.cb_dissolve);
        sbDissolveStep = findViewById(R.id.sb_dissolve_step);
        sbDissolveLife = findViewById(R.id.sb_dissolve_life);
        sbDissolveRise = findViewById(R.id.sb_dissolve_rise);
        sbDissolveSpread = findViewById(R.id.sb_dissolve_spread);
        sbDissolveSize = findViewById(R.id.sb_dissolve_size);
        tvDissolveStep = findViewById(R.id.tv_dissolve_step);
        tvDissolveLife = findViewById(R.id.tv_dissolve_life);
        tvDissolveRise = findViewById(R.id.tv_dissolve_rise);
        tvDissolveSpread = findViewById(R.id.tv_dissolve_spread);
        tvDissolveSize = findViewById(R.id.tv_dissolve_size);
        cbTilt = findViewById(R.id.cb_tilt);
        sbTiltSens = findViewById(R.id.sb_tilt_sens);
        tvTiltSens = findViewById(R.id.tv_tilt_sens);
        preview = findViewById(R.id.preview);
    }

    private void setupRanges() {
        sbFlow.setMax(300);
        sbBreath.setMax(90);
        sbPeriod.setMax(72);            // 800 + progress * 100

        spWarp.setAdapter(ArrayAdapter.createFromResource(this,
                R.array.warp_entries, android.R.layout.simple_spinner_dropdown_item));
        sbWarpAmp.setMax(24);           // px
        sbWarpFreq.setMax(40);          // 实际 /100
        sbWarpSpeed.setMax(60);         // 实际 /10

        // 密度：进度 0..7 -> step 8..1（进度越大越密）
        sbDissolveStep.setMax(7);
        sbDissolveLife.setMax(27);      // 0.3 + progress * 0.1
        sbDissolveRise.setMax(160);
        sbDissolveSpread.setMax(100);
        sbDissolveSize.setMax(54);      // 0.6 + progress * 0.1

        // 陀螺仪摆动灵敏度 0..10
        sbTiltSens.setMax(10);
    }

    private void bindLabels() {
        sbFlow.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvFlow.setText(getString(R.string.flow_speed) + "：" + progress + " px/s");
            }
        });
        sbBreath.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvBreath.setText(getString(R.string.breath_range) + "：" + progress + "%");
            }
        });
        sbPeriod.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvPeriod.setText(getString(R.string.breath_period) + "："
                        + (800 + progress * 100) + " ms");
            }
        });
        sbWarpAmp.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvWarpAmp.setText(getString(R.string.warp_amp) + "：" + progress + " px");
                setWarpControlsEnabled(spWarp.getSelectedItemPosition() != 0);
            }
        });
        sbWarpFreq.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvWarpFreq.setText(getString(R.string.warp_freq) + "："
                        + String.format(java.util.Locale.US, "%.2f", progress / 100f));
            }
        });
        sbWarpSpeed.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvWarpSpeed.setText(getString(R.string.warp_speed) + "："
                        + String.format(java.util.Locale.US, "%.1f", progress / 10f));
            }
        });
        sbDissolveStep.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvDissolveStep.setText(getString(R.string.dissolve_step) + "："
                        + densityText(progress));
            }
        });
        sbDissolveLife.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvDissolveLife.setText(getString(R.string.dissolve_life) + "："
                        + String.format(java.util.Locale.US, "%.1f", 0.3f + progress * 0.1f)
                        + " s");
            }
        });
        sbDissolveRise.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvDissolveRise.setText(getString(R.string.dissolve_rise) + "："
                        + progress + " px/s");
            }
        });
        sbDissolveSpread.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvDissolveSpread.setText(getString(R.string.dissolve_spread) + "："
                        + progress + " px/s");
            }
        });
        sbDissolveSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvDissolveSize.setText(getString(R.string.dissolve_size) + "："
                        + String.format(java.util.Locale.US, "%.1f", 0.6f + progress * 0.1f)
                        + " px");
            }
        });
        cbDissolve.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setDissolveControlsEnabled(isChecked);
            }
        });
        sbTiltSens.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                // 灵敏度 0..10 映射到最大摆角 0~12°
                tvTiltSens.setText(getString(R.string.tilt_sens) + "：" + progress
                        + "（最大约 " + String.format(java.util.Locale.US, "%.1f",
                        progress / 10f * 12f) + "°）");
            }
        });
        cbTilt.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sbTiltSens.setEnabled(isChecked);
            }
        });
        spWarp.setOnItemSelectedListener(new SimpleSelectListener() {
            @Override
            public void onSelected(int position) {
                setWarpControlsEnabled(position != Config.WARP_OFF);
            }
        });
        etColors.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshSwatches(s == null ? "" : s.toString());
                refreshPreview();
            }
        });
    }

    /** 色值实时预览：解析成功就显示一排色块 */
    private void refreshSwatches(String raw) {
        rowSwatches.removeAllViews();
        int[] colors = Config.parseColors(raw);
        if (colors == null) {
            TextView tip = new TextView(this);
            tip.setText("色值格式有误（需 #RRGGBB，至少 2 个）");
            tip.setTextSize(11f);
            tip.setTextColor(0xFFE57373);
            rowSwatches.addView(tip);
            return;
        }
        int size = (int) (28 * getResources().getDisplayMetrics().density + 0.5f);
        int margin = (int) (4 * getResources().getDisplayMetrics().density + 0.5f);
        for (int color : colors) {
            View swatch = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setColor(color);
            d.setCornerRadius(size / 2f);
            swatch.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(0, 0, margin, 0);
            rowSwatches.addView(swatch, lp);
        }
    }

    private void bindActions() {
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetDefaults();
            }
        });
        findViewById(R.id.btn_restart_netease)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        restartNetease();
                    }
                });
        cbEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setControlsEnabled(isChecked);
            }
        });
    }

    private void setControlsEnabled(boolean enabled) {
        int[] ids = {R.id.cb_desktop, R.id.cb_status, R.id.sb_flow, R.id.sb_breath,
                R.id.sb_period, R.id.et_colors, R.id.sp_warp,
                R.id.sb_warp_amp, R.id.sb_warp_freq, R.id.sb_warp_speed,
                R.id.cb_dissolve, R.id.sb_dissolve_step, R.id.sb_dissolve_life,
                R.id.sb_dissolve_rise, R.id.sb_dissolve_spread, R.id.sb_dissolve_size,
                R.id.cb_tilt, R.id.sb_tilt_sens};
        for (int id : ids) {
            View view = findViewById(id);
            if (view != null) {
                view.setEnabled(enabled);
            }
        }
    }

    // ---------------- 读取 / 保存 ----------------

    private void load() {
        Map<String, String> v = ConfigStore.load(this);
        cbEnable.setChecked(!"false".equals(v.get(Config.KEY_ENABLED)));
        cbDesktop.setChecked(!"false".equals(v.get(Config.KEY_DESKTOP)));
        cbStatus.setChecked(!"false".equals(v.get(Config.KEY_STATUS)));
        sbFlow.setProgress(intOf(v.get(Config.KEY_FLOW_SPEED), 80));
        sbBreath.setProgress(intOf(v.get(Config.KEY_BREATH_RANGE), 40));
        sbPeriod.setProgress((intOf(v.get(Config.KEY_BREATH_PERIOD), 2600) - 800) / 100);
        etColors.setText(v.get(Config.KEY_COLORS));
        spWarp.setSelection(intOf(v.get(Config.KEY_WARP_EFFECT), Config.WARP_HEAT), false);
        sbWarpAmp.setProgress(intOf(v.get(Config.KEY_WARP_AMP), 3));
        sbWarpFreq.setProgress(intOf(v.get(Config.KEY_WARP_FREQ), 9));
        sbWarpSpeed.setProgress(intOf(v.get(Config.KEY_WARP_SPEED), 12));
        cbDissolve.setChecked(!"false".equals(v.get(Config.KEY_DISSOLVE)));
        sbDissolveStep.setProgress(stepToProgress(intOf(v.get(Config.KEY_DISSOLVE_STEP), 2)));
        sbDissolveLife.setProgress(intOf(v.get(Config.KEY_DISSOLVE_LIFE), 18) - 3);
        sbDissolveRise.setProgress(intOf(v.get(Config.KEY_DISSOLVE_RISE), 60));
        sbDissolveSpread.setProgress(intOf(v.get(Config.KEY_DISSOLVE_SPREAD), 42));
        sbDissolveSize.setProgress(intOf(v.get(Config.KEY_DISSOLVE_SIZE), 26) - 6);
        cbTilt.setChecked(!"false".equals(v.get(Config.KEY_TILT)));
        sbTiltSens.setProgress(intOf(v.get(Config.KEY_TILT_SENS), 6));

        setDissolveControlsEnabled(cbDissolve.isChecked());
        sbTiltSens.setEnabled(cbTilt.isChecked());
        setWarpControlsEnabled(spWarp.getSelectedItemPosition() != Config.WARP_OFF);
        setControlsEnabled(cbEnable.isChecked());
        refreshLabels();
        refreshSwatches(etColors.getText().toString());
        refreshPreview();
    }

    /** 滑块不会主动触发 onChange，手动把文案刷一遍 */
    private void refreshLabels() {
        tvFlow.setText(getString(R.string.flow_speed) + "：" + sbFlow.getProgress() + " px/s");
        tvBreath.setText(getString(R.string.breath_range) + "：" + sbBreath.getProgress() + "%");
        tvPeriod.setText(getString(R.string.breath_period) + "："
                + (800 + sbPeriod.getProgress() * 100) + " ms");
        tvWarpAmp.setText(getString(R.string.warp_amp) + "：" + sbWarpAmp.getProgress() + " px");
        tvWarpFreq.setText(getString(R.string.warp_freq) + "："
                + String.format(java.util.Locale.US, "%.2f", sbWarpFreq.getProgress() / 100f));
        tvWarpSpeed.setText(getString(R.string.warp_speed) + "："
                + String.format(java.util.Locale.US, "%.1f", sbWarpSpeed.getProgress() / 10f));
        tvDissolveStep.setText(getString(R.string.dissolve_step) + "："
                + densityText(sbDissolveStep.getProgress()));
        tvDissolveLife.setText(getString(R.string.dissolve_life) + "："
                + String.format(java.util.Locale.US, "%.1f",
                0.3f + sbDissolveLife.getProgress() * 0.1f) + " s");
        tvDissolveRise.setText(getString(R.string.dissolve_rise) + "："
                + sbDissolveRise.getProgress() + " px/s");
        tvDissolveSpread.setText(getString(R.string.dissolve_spread) + "："
                + sbDissolveSpread.getProgress() + " px/s");
        tvDissolveSize.setText(getString(R.string.dissolve_size) + "："
                + String.format(java.util.Locale.US, "%.1f",
                0.6f + sbDissolveSize.getProgress() * 0.1f) + " px");
        tvTiltSens.setText(getString(R.string.tilt_sens) + "：" + sbTiltSens.getProgress()
                + "（最大约 " + String.format(java.util.Locale.US, "%.1f",
                sbTiltSens.getProgress() / 10f * 12f) + "°）");
    }

    /** 密度滑块：进度 0..7 映射成采样间隔 8..1，进度越大越密 */
    private static int progressToStep(int progress) {
        return 8 - progress;
    }

    private static int stepToProgress(int step) {
        int p = 8 - step;
        return Math.max(0, Math.min(7, p));
    }

    private String densityText(int progress) {
        switch (progress) {
            case 0:
                return "很稀疏";
            case 1:
                return "稀疏";
            case 2:
                return "偏稀";
            case 3:
                return "适中";
            case 4:
                return "偏密";
            case 5:
                return "密集";
            default:
                return "极密";
        }
    }

    /** 消散关闭时把相关滑块一并置灰 */
    private void setDissolveControlsEnabled(boolean enabled) {
        sbDissolveStep.setEnabled(enabled);
        sbDissolveLife.setEnabled(enabled);
        sbDissolveRise.setEnabled(enabled);
        sbDissolveSpread.setEnabled(enabled);
        sbDissolveSize.setEnabled(enabled);
    }

    /** 扭曲关闭时，振幅/频率/速度三个滑块一并置灰 */
    private void setWarpControlsEnabled(boolean enabled) {
        sbWarpAmp.setEnabled(enabled);
        sbWarpFreq.setEnabled(enabled);
        sbWarpSpeed.setEnabled(enabled);
    }

    private Map<String, String> collect() {
        Map<String, String> v = new HashMap<>();
        v.put(Config.KEY_ENABLED, String.valueOf(cbEnable.isChecked()));
        v.put(Config.KEY_DESKTOP, String.valueOf(cbDesktop.isChecked()));
        v.put(Config.KEY_STATUS, String.valueOf(cbStatus.isChecked()));
        v.put(Config.KEY_FLOW_SPEED, String.valueOf(sbFlow.getProgress()));
        v.put(Config.KEY_BREATH_RANGE, String.valueOf(sbBreath.getProgress()));
        v.put(Config.KEY_BREATH_PERIOD, String.valueOf(800 + sbPeriod.getProgress() * 100));
        v.put(Config.KEY_COLORS, etColors.getText().toString().trim());
        v.put(Config.KEY_WARP_EFFECT, String.valueOf(spWarp.getSelectedItemPosition()));
        v.put(Config.KEY_WARP_AMP, String.valueOf(sbWarpAmp.getProgress()));
        v.put(Config.KEY_WARP_FREQ, String.valueOf(sbWarpFreq.getProgress()));
        v.put(Config.KEY_WARP_SPEED, String.valueOf(sbWarpSpeed.getProgress()));
        v.put(Config.KEY_DISSOLVE, String.valueOf(cbDissolve.isChecked()));
        v.put(Config.KEY_DISSOLVE_STEP,
                String.valueOf(progressToStep(sbDissolveStep.getProgress())));
        v.put(Config.KEY_DISSOLVE_LIFE, String.valueOf(3 + sbDissolveLife.getProgress()));
        v.put(Config.KEY_DISSOLVE_RISE, String.valueOf(sbDissolveRise.getProgress()));
        v.put(Config.KEY_DISSOLVE_SPREAD, String.valueOf(sbDissolveSpread.getProgress()));
        v.put(Config.KEY_DISSOLVE_SIZE, String.valueOf(6 + sbDissolveSize.getProgress()));
        v.put(Config.KEY_TILT, String.valueOf(cbTilt.isChecked()));
        v.put(Config.KEY_TILT_SENS, String.valueOf(sbTiltSens.getProgress()));
        return v;
    }

    private void save() {
        if (Config.parseColors(etColors.getText().toString().trim()) == null) {
            Toast.makeText(this, "色值格式有误，已改用默认彩虹配色", Toast.LENGTH_SHORT).show();
        }
        Map<String, String> values = collect();
        boolean tmpOk = ConfigStore.save(this, values);
        // 广播带上补全默认值后的完整配置，网易云进程收到即用，无需读跨进程文件
        Map<String, String> broadcast = ConfigStore.defaults();
        broadcast.putAll(values);
        LyricBus.sendConfigChanged(this, broadcast);
        DiagLog.put("mode", runningInsideHost() ? "LSPatch(宿主进程)" : "LSPosed(独立进程)");
        // 先读一次值，debugSource 才会记录命中的来源
        Config probe = new Config(prefs());
        probe.enabled();
        DiagLog.put("config_source", probe.debugSource());
        DiagLog.put("tmp_write", tmpOk ? "ok" : "FAILED(仅靠SP)");
        refreshPreview();

        String hint = tmpOk
                ? "已保存\n配色与流光立即生效；若无变化请强制停止网易云重开"
                : "已保存到应用存储\n公共副本写入失败，已自动改用应用存储\n若仍无变化请强制停止网易云重开";
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
    }

    private void resetDefaults() {
        ConfigStore.reset(this);
        LyricBus.sendConfigChanged(this, ConfigStore.defaults());
        load();
        Toast.makeText(this, "已恢复默认参数", Toast.LENGTH_SHORT).show();
    }

    private void refreshPreview() {
        if (preview == null) {
            return;
        }
        // 预览用当前存储值，不读界面上未保存的改动
        Config cfg = new Config(prefs());
        preview.applyConfig(cfg);
        preview.setLyric("彩虹歌词 · 流光预览");
        RainbowController controller = RainbowController.of(preview);
        if (controller != null) {
            controller.start();
        }
    }

    /**
     * 重启网易云让改动生效。
     * <p>
     * <b>LSPatch 模式</b>：模块被 patch 进宿主 APK，设置界面和网易云在同一个进程，
     * 直接 {@code killProcess(myPid)} 就等于强制停止网易云 —— 不需要 Root。
     * <p>
     * <b>LSPosed 模式</b>：模块是独立安装的，得走 su；没有 Root 就降级到
     * {@code ActivityManager.killBackgroundProcesses}（只能杀后台进程），
     * 再不行就引导用户手动划掉。
     */
    private void restartNetease() {
        if (runningInsideHost()) {
            Toast.makeText(this, "正在重启网易云…", Toast.LENGTH_SHORT).show();
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 同进程，杀自己即杀网易云；系统会按栈重建，等同强制停止
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }, 600);
            return;
        }
        Toast.makeText(this, "正在强制停止网易云…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String pkg = LyricBus.NETEASE;
                String[] sus = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "su"};
                String[] cmds = {
                        "am force-stop " + pkg,
                        "killall " + pkg,
                        "pkill -f " + pkg,
                        "kill $(pidof " + pkg + ")",
                };
                boolean ok = false;
                String lastOut = "";
                for (String su : sus) {
                    for (String cmd : cmds) {
                        try {
                            Process process = Runtime.getRuntime()
                                    .exec(new String[]{su, "-c", cmd});
                            String out = readAll(process);
                            int code = process.waitFor();
                            if (code == 0) {
                                ok = true;
                                lastOut = su + " -c '" + cmd + "'";
                                break;
                            }
                            if (!out.isEmpty()) {
                                lastOut = out;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (ok) {
                        break;
                    }
                }
                final boolean success = ok;
                final String detail = lastOut;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            Toast.makeText(SettingsActivity.this, "已停止网易云，重新打开即可",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // 没有 Root：退而求其次
                        if (killBackground()) {
                            Toast.makeText(SettingsActivity.this,
                                    "已尝试停止后台进程，若无效请手动划掉网易云",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(SettingsActivity.this,
                                    "需要 Root 才能自动停止\n请手动从后台划掉网易云再打开\n" + detail,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 是否运行在宿主（网易云）进程里 —— 即 LSPatch 模式。
     * 只有设置界面会调这个方法，所以用它判断进程归属是准的。
     */
    private boolean runningInsideHost() {
        try {
            return LyricBus.NETEASE.equals(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 无 Root 时的降级方案：只能杀后台进程 */
    private boolean killBackground() {
        try {
            Object service = getSystemService(Context.ACTIVITY_SERVICE);
            if (service instanceof android.app.ActivityManager) {
                ((android.app.ActivityManager) service)
                        .killBackgroundProcesses(LyricBus.NETEASE);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String readAll(Process process) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && sb.length() < 500) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Config.PREF_NAME, MODE_PRIVATE);
    }

    private static int intOf(String value, int def) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable t) {
            return def;
        }
    }

    private static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    /** Spinner 选择监听的简化基类：只需实现 onSelected(position) */
    private static abstract class SimpleSelectListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        public abstract void onSelected(int position);

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                   int position, long id) {
            onSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
