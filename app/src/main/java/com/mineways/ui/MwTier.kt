package com.mineways.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mineways.cloud.CloudCatalog

/**
 * 设备档位：决定同一套液态玻璃在这台机器上跑到哪一档。
 *
 * 为什么要分档而不是"一刀切全开"：库作者自己在 best-practices 里写了玻璃图层数量有硬件上限
 * （超了就"部分设备崩溃/效果丢失"），而色散（chromaticAberration）是把 RGB 各采一次的
 * 那最贵一档。旗舰机全开没问题，老机型全开就是掉帧 + 图层超限。
 *
 * 判据（保守，宁低不高）：
 *  · API 33+ 才有 RuntimeShader → 折射/色散/重力高光；
 *  · API 31+ 才有 RenderEffect → 模糊；
 *  · 再叠加 `largeMemory`（系统内存类）与屏幕像素密度做一道粗筛，密度极高的小屏老机器
 *    填充率开销最大，最容易掉帧；
 *  · 云控 `ui_tier=high|balanced|off` 可以强行指定，**下发优先级最高** —— 真机上观感或
 *    帧率不对，一句话调档，不用重编包。
 */
enum class MwTier {
    /** 33+ 且不是小内存机：折射 + 色散（只给大格）+ 重力高光 + 错峰入场。 */
    HIGH,

    /** 31/32 或内存紧的 33+：折射但**没有色散**，高光固定不跟机身。 */
    BALANCED,

    /** 31 以下：没有 RenderEffect，玻璃退成半透底 + 一道描边。 */
    FLAT;

    /** 能不能画模糊。 */
    val blur: Boolean get() = this != FLAT

    /** 能不能画折射（lens）。 */
    val refract: Boolean get() = this != FLAT

    /** 能不能上色散（最贵那一档）。 */
    val chromatic: Boolean get() = this == HIGH

    /** 重力驱动的高光/流光。 */
    val sensor: Boolean get() = this == HIGH

    /** 逐格错峰入场（低档只做一次整体淡入）。 */
    val stagger: Boolean get() = this != FLAT
}

object MwDevice {

    @Volatile
    private var cached: MwTier? = null

    /** 只算一次（每次建页面读云控，档位本身不该反复变）。 */
    @JvmStatic
    fun tier(context: Context): MwTier {
        cached?.let { return it }
        val detected = detect(context)
        cached = detected
        return detected
    }

    private fun detect(context: Context): MwTier {
        val api = Build.VERSION.SDK_INT
        if (api < 31) {
            return MwTier.FLAT
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memClassMb = am?.memoryClass ?: 0
        val large = am?.isLowRamDevice == false && memClassMb >= 200
        val dm: DisplayMetrics = context.resources.displayMetrics
        // 超高密度小屏的填充率最吃 GPU：像素数 = w*h*density²，这里只做一个粗筛上界
        val huge = dm.widthPixels * dm.heightPixels * dm.densityDpi / 160 > 3_200_000
        return when {
            api >= 33 && large && !huge -> MwTier.HIGH
            api >= 33 && large -> MwTier.HIGH
            else -> MwTier.BALANCED
        }
    }

    /**
     * 云控覆盖：`ui_tier=high|balanced|flat|off`。写在配置里就赢，没写就用检测结果。
     * `off` 与 `flat` 同义（都是关掉 RenderEffect 那套）。
     *
     * 之后再叠一层**按机型**的策略（`ui_glass_models=K70*:low,K60*:off,K80*:ask`）：
     * 通用档位是给所有人调的，机型档位是给个别机器兜底的，后者只能往低走、不能把 FLAT 抬上来。
     */
    @JvmStatic
    fun tierWithCloud(context: Context): MwTier {
        val raw = CloudCatalog.gate().flag("ui_tier", "").trim().lowercase()
        val base = when {
            raw == "high" -> MwTier.HIGH
            raw == "balanced" || raw == "mid" -> MwTier.BALANCED
            raw == "flat" || raw == "off" || raw == "low" -> MwTier.FLAT
            else -> tier(context)
        }
        return com.mineways.GlassPolicy.tier(context, base)
    }
}

/** Compose 侧取档位（带云控）。 */
@Composable
fun rememberMwTier(context: Context): MwTier = remember(context) { MwDevice.tierWithCloud(context) }
