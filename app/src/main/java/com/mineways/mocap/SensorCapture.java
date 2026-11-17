package com.mineways.mocap;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 单设备传感器采集：注册手机**全部相关传感器**，旋转矢量定绝对姿态、陀螺做高频预测，
 * 加速度 / 磁力计 / 重力 / 线性加速度一并登记并计入健康状态（RV 本身已融合前两者）。
 *
 * <p>流程：开始 → 保持静止 2 秒（自动标定）→ 自动进入采集 → 停止。
 * 标定只记相对旋转，所以手机怎么绑都行；只需长边沿着肢体。
 */
public final class SensorCapture implements SensorEventListener {

    public interface Listener {
        void onTick(String phase, int frames, long gyroFrames);
    }

    public static final class Health {
        public boolean rv, gyro, accel, mag, gravity, linear;
        public long rvFrames, gyroFrames, accelFrames, magFrames;

        public int score() {
            int s = 0;
            if (rv) s += 2;
            if (gyro) s += 1;
            if (accel) s += 1;
            if (mag) s += 1;
            return s;
        }

        public String text() {
            return "旋转矢量" + (rv ? "✓" : "✗")
                    + " · 陀螺" + (gyro ? "✓" : "✗")
                    + " · 加速度" + (accel ? "✓" : "✗")
                    + " · 磁力计" + (mag ? "✓" : "✗")
                    + " · 重力" + (gravity ? "✓" : "✗")
                    + " · 线性加速度" + (linear ? "✓" : "✗")
                    + "\n采样：RV " + rvFrames + " · 陀螺 " + gyroFrames;
        }
    }

    public static final class Capture {
        public final double[] q0;
        public final List<SensorMocap.Rv> rv;
        public final List<SensorMocap.Gyro> gyro;
        public final Health health;
        public final int calibSamples;

        Capture(double[] q0, List<SensorMocap.Rv> rv, List<SensorMocap.Gyro> gyro,
                Health health, int calibSamples) {
            this.q0 = q0;
            this.rv = rv;
            this.gyro = gyro;
            this.health = health;
            this.calibSamples = calibSamples;
        }
    }

    private static final long CALIB_MS = 2000;
    private static final long TICK_MS = 200;

    private final List<SensorMocap.Rv> rv = new ArrayList<SensorMocap.Rv>();
    private final List<SensorMocap.Gyro> gyro = new ArrayList<SensorMocap.Gyro>();
    private final List<SensorMocap.Rv> calib = new ArrayList<SensorMocap.Rv>();
    private final Health health = new Health();

    private SensorManager sm;
    private HandlerThread thread;
    private Handler sensorHandler;
    private Handler main;
    private Sensor rvSensor, gyroSensor;
    private Listener listener;

    private volatile boolean calibrating;
    private volatile boolean recording;
    private volatile boolean sampling;
    private long baseNs = -1;
    private double[] q0;
    private int calibSamples;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!calibrating && !recording) {
                return;
            }
            Listener l = listener;
            if (l != null) {
                if (calibrating) {
                    l.onTick("校准中：保持静止不动…", calib.size(), health.gyroFrames);
                } else {
                    l.onTick("采集中：可以开始做动作", rv.size(), health.gyroFrames);
                }
            }
            if (main != null && (calibrating || recording)) {
                main.postDelayed(this, TICK_MS);
            }
        }
    };
    private final Runnable finishCalibration = new Runnable() {
        @Override
        public void run() {
            if (!calibrating) {
                return;
            }
            synchronized (SensorCapture.this) {
                q0 = SensorMocap.calibrate(new ArrayList<SensorMocap.Rv>(calib));
                calibSamples = calib.size();
                calib.clear();
                rv.clear();
                gyro.clear();
                recording = true;
                calibrating = false;
            }
            Listener l = listener;
            if (l != null) {
                l.onTick("采集中：可以开始做动作", 0, health.gyroFrames);
            }
        }
    };

    public boolean available(Context c) {
        SensorManager m = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        return m != null && (m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
                || m.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null);
    }

    public void start(Context c, Listener l) {
        stopInternal();
        listener = l;
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) {
            throw new IllegalStateException("这台设备没有传感器服务");
        }
        rvSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rvSensor == null) {
            rvSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        }
        gyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Sensor grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        Sensor lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

        health.rv = rvSensor != null;
        health.gyro = gyroSensor != null;
        health.accel = accel != null;
        health.mag = mag != null;
        health.gravity = grav != null;
        health.linear = lin != null;
        if (!health.rv) {
            throw new IllegalStateException("设备缺少旋转矢量传感器，无法确定肢体朝向");
        }

        synchronized (this) {
            rv.clear();
            gyro.clear();
            calib.clear();
            q0 = null;
            calibSamples = 0;
            baseNs = -1;
            health.rvFrames = health.gyroFrames = health.accelFrames = health.magFrames = 0;
            calibrating = true;
            recording = false;
            sampling = true;
        }
        thread = new HandlerThread("sensor-mocap");
        thread.start();
        sensorHandler = new Handler(thread.getLooper());
        int rate = SensorManager.SENSOR_DELAY_GAME;
        if (rvSensor != null) {
            sm.registerListener(this, rvSensor, rate, sensorHandler);
        }
        if (gyroSensor != null) {
            sm.registerListener(this, gyroSensor, rate, sensorHandler);
        }
        if (accel != null) {
            sm.registerListener(this, accel, rate, sensorHandler);
        }
        if (mag != null) {
            sm.registerListener(this, mag, rate, sensorHandler);
        }
        if (grav != null) {
            sm.registerListener(this, grav, rate, sensorHandler);
        }
        if (lin != null) {
            sm.registerListener(this, lin, rate, sensorHandler);
        }
        main = new Handler(Looper.getMainLooper());
        main.postDelayed(tick, TICK_MS);
        main.postDelayed(finishCalibration, CALIB_MS);
    }

    public boolean recording() {
        return recording;
    }

    public boolean calibrating() {
        return calibrating;
    }

    public String phaseText() {
        if (calibrating) {
            return "校准中：保持静止 2 秒…";
        }
        if (recording) {
            return "采集中";
        }
        return "未开始";
    }

    public synchronized Capture stop() {
        Capture out = new Capture(q0, new ArrayList<SensorMocap.Rv>(rv),
                new ArrayList<SensorMocap.Gyro>(gyro), health, calibSamples);
        stopInternal();
        return out;
    }

    private void stopInternal() {
        sampling = false;
        calibrating = false;
        recording = false;
        if (main != null) {
            main.removeCallbacks(tick);
            main.removeCallbacks(finishCalibration);
            main = null;
        }
        if (sm != null) {
            try {
                sm.unregisterListener(this);
            } catch (Throwable ignored) {
            }
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            sensorHandler = null;
        }
        listener = null;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!sampling) {
            return;
        }
        if (baseNs < 0) {
            baseNs = e.timestamp;
        }
        double t = (e.timestamp - baseNs) / 1e6;
        switch (e.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GAME_ROTATION_VECTOR: {
                float[] v = e.values;
                double w = v.length >= 4 ? v[3]
                        : Math.sqrt(Math.max(0, 1 - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]));
                SensorMocap.Rv s = new SensorMocap.Rv(t, w, v[0], v[1], v[2]);
                synchronized (this) {
                    health.rvFrames++;
                    if (calibrating) {
                        calib.add(s);
                    } else if (recording) {
                        rv.add(s);
                    }
                }
                break;
            }
            case Sensor.TYPE_GYROSCOPE:
                synchronized (this) {
                    health.gyroFrames++;
                    if (recording) {
                        gyro.add(new SensorMocap.Gyro(t, e.values[0], e.values[1], e.values[2]));
                    }
                }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                health.accelFrames++;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                health.magFrames++;
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}