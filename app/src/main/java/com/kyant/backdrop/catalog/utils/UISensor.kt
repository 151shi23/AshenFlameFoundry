/*
 * Copyright (c) 2026
 *
 * This file is derived from AndroidLiquidGlass (Backdrop):
 * https://github.com/Kyant0/AndroidLiquidGlass
 * Copyright (c) Kyant0 and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kyant.backdrop.catalog.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * UI 传感器接口
 *
 * 用于获取设备方向信息，实现动态高光角度。
 * 在 ControlCenter 等组件中使用。
 */
interface UISensor {
    val gravityAngle: Float
    val gravity: Offset
    fun start()
    fun stop()
}

/**
 * 记住 UI 传感器
 *
 * @return UI 传感器实例
 */
@Composable
fun rememberUISensor(): UISensor {
    val context = LocalContext.current
    val uiSensor = remember { UISensorImpl(context) }

    DisposableEffect(Unit) {
        uiSensor.start()
        onDispose { uiSensor.stop() }
    }

    return uiSensor
}

private class UISensorImpl(context: android.content.Context) : UISensor {

    override var gravityAngle: Float by mutableFloatStateOf(45f)
        private set

    override var gravity: Offset by mutableStateOf(Offset.Zero)
        private set

    private val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
    private val listener = object : android.hardware.SensorEventListener {

        override fun onSensorChanged(event: android.hardware.SensorEvent?) {
            if (event == null) return
            if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val norm = sqrt(x * x + y * y + 9.81f * 9.81f)

                val alpha = 0.5f
                gravityAngle = gravityAngle * (1f - alpha) + atan2(y, x) * (180f / PI).toFloat() * alpha
                gravity = gravity * (1f - alpha) + Offset(x / norm, y / norm) * alpha
            }
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
    }

    override fun start() {
        sensorManager.registerListener(listener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
