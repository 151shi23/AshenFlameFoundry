package com.mineways.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 机身姿态 → 光源方向：背景流光与玻璃高光都跟着它走，取代「彩色光斑自己飘」那套装饰。
 *
 * 为什么用加速度计而不是陀螺仪（TYPE_GYROSCOPE）：陀螺仪给的是**角速度**，要积分才会漂；
 * 加速度计静止时直接给出重力向量，一步就能算出光源方位角，不漂、代价更低、无需权限。
 *
 * 三个实测约束：
 *  · 采样用 SENSOR_DELAY_GAME（≈20ms）：再快（FASTEST）肉眼看不出差别，却会把重绘拖得更密；
 *  · 原始数据做低通（[MwTilt.ALPHA]）：传感器噪声会让高光"抖"，看着像 bug 不像光；
 *  · 注册跟随生命周期：ON_PAUSE 注销，切页/退后台不再空转耗电。
 *
 * 云控 `ui_motion=0` 时不注册传感器（返回恒为平放的实例）——嫌晃或低端机一句话关掉，不用重编包。
 */
class MwTilt internal constructor() {

    /** 左右倾斜分量，-1..1（右为正）。 */
    var x by mutableFloatStateOf(0f)
        internal set

    /** 前后倾斜分量，-1..1。 */
    var y by mutableFloatStateOf(0f)
        internal set

    /** 倾斜量：接近 0 视为平放。 */
    val magnitude: Float get() = hypot(x, y)

    /** 光源方位角（弧度）。平放时用固定左上打光，避免 atan2(0,0) 抖来抖去。 */
    val angleRad: Float
        get() = if (magnitude < FLAT) DEFAULT_ANGLE else atan2(y, x)

    companion object {
        /** 认为"平放"的阈值。 */
        const val FLAT = 0.06f

        /** 平放时的高光角度：-135°，左上打光。 */
        const val DEFAULT_ANGLE = -2.356f

        /** 低通系数：0.22 ≈ 3~4 个样本跟上，跟手但不抖。 */
        const val ALPHA = 0.22f
    }
}

/**
 * 记住一个跟随机身的 tilt 状态。
 *
 * 用 `LocalContext as? LifecycleOwner` 而不是 `lifecycle-compose` 的 `LocalLifecycleOwner`：
 * 后者要新增 lifecycle-runtime-compose 依赖，为一个小传感器把依赖面扩大不值得 ——
 * 这些页面本来就挂在 Activity 上，LocalContext 就是这个 Activity 本身。
 */
@Composable
fun rememberMwTilt(enabled: Boolean = true): MwTilt {
    val context = LocalContext.current
    val tilt = remember { MwTilt() }
    val owner = context as? LifecycleOwner
    val manager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val sensor = remember(manager) { manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    DisposableEffect(enabled, owner, manager, sensor) {
        if (!enabled || manager == null || sensor == null || owner == null) {
            return@DisposableEffect onDispose { }
        }
        val rate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            SensorManager.SENSOR_DELAY_GAME
        } else {
            SensorManager.SENSOR_DELAY_UI
        }
        var registered = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
                    return
                }
                // 只取水平两轴并归一化；z 不参与光源方向
                val nx = (event.values[0] / 9.81f).coerceIn(-1f, 1f)
                val ny = (event.values[1] / 9.81f).coerceIn(-1f, 1f)
                tilt.x = tilt.x + (nx - tilt.x) * MwTilt.ALPHA
                tilt.y = tilt.y + (ny - tilt.y) * MwTilt.ALPHA
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            }
        }

        fun enable() {
            if (!registered) {
                manager.registerListener(listener, sensor, rate)
                registered = true
            }
        }

        fun disable() {
            if (registered) {
                manager.unregisterListener(listener)
                registered = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> enable()
                Lifecycle.Event.ON_PAUSE -> disable()
                else -> {
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        // Compose 比生命周期晚建时（进页面就已经 RESUMED）补一次，否则首屏不亮
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            enable()
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            disable()
        }
    }
    return tilt
}
