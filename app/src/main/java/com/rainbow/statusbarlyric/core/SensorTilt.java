package com.rainbow.statusbarlyric.core;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 手机姿态：把重力/加速度读数换算成平滑的左右、前后倾斜角，供歌词随手机小幅摆动。
 * <p>
 * 优先用 TYPE_GRAVITY（系统已分离重力，更稳），没有就回退加速度计并自己做低通。
 * 输出角度统一以「手机竖直、屏幕朝自己」为零点：
 * <ul>
 *   <li>{@link #roll()}  左右倾斜（绕屏幕竖直轴），手机顶部向左/右偏时为负/正，弧度。</li>
 *   <li>{@link #pitch()} 前后倾斜（绕屏幕水平轴），平躺/后仰偏离竖直时变化，弧度。</li>
 * </ul>
 * 本类不引用任何 Xposed API，只在 Hook 进程被加载，设置界面不会碰到。
 */
public final class SensorTilt {

    private static volatile SensorTilt instance;

    public static SensorTilt get() {
        if (instance == null) {
            synchronized (SensorTilt.class) {
                if (instance == null) {
                    instance = new SensorTilt();
                }
            }
        }
        return instance;
    }

    /** 低通后的重力向量 */
    private final float[] gravity = new float[3];
    private boolean hasGravity;
    private boolean registered;
    private SensorManager manager;

    /** 平滑后的结果（弧度），绘制线程读、传感器线程写，用 volatile */
    private volatile float roll;
    private volatile float pitch;

    /** 角度平滑系数：越小越跟手、越大越柔，取偏柔避免歌词发颤 */
    private static final float SMOOTH = 0.18f;

    private SensorTilt() {
    }

    /** 在 Hook 安装时注册一次即可，跟随宿主进程生命周期，无需注销 */
    public synchronized void start(Context context) {
        if (registered || context == null) {
            return;
        }
        manager =
                (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) {
            return;
        }
        Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        boolean isGravity = sensor != null;
        if (!isGravity) {
            sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if (sensor == null) {
            XLog.d("no tilt sensor available");
            return;
        }
        this.isGravitySensor = isGravity;
        boolean ok = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME);
        registered = ok;
        XLog.i("tilt sensor started (" + (isGravity ? "gravity" : "accelerometer") + ")=" + ok);
    }

    /** 摆动关闭时停止监听；下次开启再重新注册 */
    public synchronized void stop() {
        if (!registered || manager == null) {
            return;
        }
        manager.unregisterListener(listener);
        registered = false;
        hasGravity = false;
        roll = 0f;
        pitch = 0f;
    }

    private boolean isGravitySensor;

    private final SensorEventListener listener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            if (!isGravitySensor) {
                // 加速度计自己做低通，隔离手抖与移动加速度
                float alpha = 0.12f;
                gravity[0] = alpha * x + (1 - alpha) * gravity[0];
                gravity[1] = alpha * y + (1 - alpha) * gravity[1];
                gravity[2] = alpha * z + (1 - alpha) * gravity[2];
                x = gravity[0];
                y = gravity[1];
                z = gravity[2];
            } else if (!hasGravity) {
                gravity[0] = x;
                gravity[1] = y;
                gravity[2] = z;
                hasGravity = true;
            } else {
                // 重力传感器本身较稳，再轻平滑一层让旋转不生硬
                gravity[0] += (x - gravity[0]) * 0.5f;
                gravity[1] += (y - gravity[1]) * 0.5f;
                gravity[2] += (z - gravity[2]) * 0.5f;
                x = gravity[0];
                y = gravity[1];
                z = gravity[2];
            }

            double norm = Math.sqrt(x * x + y * y + z * z);
            if (norm < 0.1) {
                return;
            }
            float nx = (float) (x / norm);
            float ny = (float) (y / norm);
            float nz = (float) (z / norm);

            // 竖屏零点：左右倾 roll；前后倾以竖直(ny≈1)为零点
            float targetRoll = (float) Math.atan2(nx, Math.sqrt(ny * ny + nz * nz));
            float targetPitch = (float) (Math.atan2(ny, Math.sqrt(nx * nx + nz * nz)) - Math.PI / 2);

            roll += (targetRoll - roll) * SMOOTH;
            pitch += (targetPitch - pitch) * SMOOTH;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /** 左右倾斜角（弧度），竖屏朝自己为 0 */
    public float roll() {
        return roll;
    }

    /** 前后倾斜角（弧度），竖直为 0 */
    public float pitch() {
        return pitch;
    }
}
