package com.mineways.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.mineways.AiClient
import com.mineways.AiMemory
import com.mineways.AiOps
import com.mineways.AiTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import java.net.HttpURLConnection
import java.net.URL

/**
 * 首页右上角「三个点」→ 液态玻璃水波纹 + 全屏 AI 对话。
 *
 * ## 产品约束
 *
 * **人设在 {@link AiClient} 里统一带**：软件端通道（{@code /api/app/ai/chat}）与网页版隔离，
 * 服务端不给这个通道内置提示词，不传 {@code system} 模型会自称「Agnes-3.0-flash，由 Sapiens AI
 * 开发」。文案可由云控 {@code FLAG key=ai_system to=…} 覆盖，写空串则不发。
 *
 * ## 界面为什么长这样
 *
 * 上一版把「标题 + 副标题 + 输入框 + 三个快捷词 + 发送键」塞进 180dp 小卡片，挤且平。
 * 现在是整屏三段式：玻璃顶栏 / 消息区 / 玻璃输入条，空状态只放问候 + 3 条建议。
 *
 * ## AI 感从哪来（这一版新增）
 *
 * 全部是「小面积、可组合」的动效，不做全屏逐帧重绘：
 *  · **环境光**：两团 300dp 的青色/紫色径向光晕，用 `graphicsLayer` 平移漂移 ——
 *    图层只画一次，之后只动 transform，不重新光栅化；
 *  · **光环球**：AI 标识改成「旋转的 sweep 渐变环 + 呼吸的内核 + 外发光」，
 *    只在顶栏/空状态各一枚，消息气泡里用静态版，避免十几个无限动画；
 *  · **流式微光**：顶栏与最后一条回答下方各有一条扫过的渐变光带；
 *  · **打字点**：等待首 token 时三个错峰呼吸的点；
 *  · **入场**：每条气泡淡入 + 上移 14dp + 0.98→1 缩放，一次播完不重播；
 *  · **输入条**：获焦时青色→紫色渐变描边亮起，可发送时发送键后面有一圈光晕。
 *
 * 动效总开关仍是 `spec.motion`（云控 ui_motion=0）与 FLAT 档：关掉后全部退化为静态，
 * 光晕不画、环不转、点不动。
 *
 * ## 云控
 *
 * ```
 * FLAG key=ui_ai to=0          # 关掉 AI 入口，三个点退回「关于」
 * FLAG key=ui_ai_ripple to=0   # 只关水波纹
 * FLAG key=ui_ai_hint to=...   # 输入框占位文案
 * ```
 */

private val AiCyan = Color(0xFF6EE7FF)
private val AiViolet = Color(0xFFA78BFA)

/**
 * 一条会话记录。
 *
 * @param role `user` / `assistant` / `tool`（工具调用卡片）
 * @param image `generate_image` 或正文 markdown 里的图片地址
 * @param toolLabel 工具卡片显示的一行摘要（role=tool 时才有）
 * @param ok 工具执行结果
 * @param internal 只喂模型、不上屏（工具结果回喂那条 JSON 就是这种）
 * @param running 这条工具卡片还在执行（异步作业没回来之前一直是它）
 * @param suggests 这条回答末尾的「下一步建议」，画成能点的胶囊
 */
data class MwAiMessage(
        val role: String,
        val text: String,
        val image: String? = null,
        val toolLabel: String? = null,
        val ok: Boolean = true,
        val internal: Boolean = false,
        val at: Long = 0L,
        val running: Boolean = false,
        val suggests: List<String> = emptyList(),
        /** 这条回答已经收尾（onDone 处理过）：之后再来的增量一律不再追加，见 [MwAiChatState.send]。 */
        val done: Boolean = false,
)

/**
 * AI 面板的全局开关。
 *
 * 放静态而不是 `rememberSaveable`，是为了让 [MainActivity] 的返回键能**先收面板**：
 * 否则按返回会直接切页 / 退出，用户以为"卡住了"。宿主 Activity 也用它同步导航栏显隐。
 */
object MwAiBus {
    @JvmField
    val open = mutableStateOf(false)

    /**
     * 各入口带进来的预填文字（卡片长按「问 AI」、搜索无结果、功能页顶栏）。
     * 面板打开时取一次就清空 —— 留着会在下次打开时莫名其妙地又冒出来。
     */
    @JvmField
    val prefill = mutableStateOf("")
}

/**
 * 会话状态。挂在首页（`MwHomeScreen`）而不是面板里 —— 关掉面板再打开，
 * 对话还在；否则每次收起都把上下文丢了。
 */
class MwAiChatState {

    val messages = mutableStateListOf<MwAiMessage>()

    var streaming by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var model by mutableStateOf("")
        private set

    private var session: AiClient.Session? = null
    private var lastAsk = ""

    /** 工具调用回喂的跳数：一条用户消息最多让 AI 连着做几步（云控 ai_hops），防打转。 */
    private var hops = 0
    private var hopLimit = 3

    /**
     * 宿主 Activity。工具执行要拿它起 Intent、ask_page 要读它的屏幕文字，
     * 所以**必须是 Activity**，不是 applicationContext（那样 `instanceof MainActivity` 永远为假）。
     * 面板关闭时由 [detach] 交回去，不留活引用。
     */
    private var host: Context? = null

    /** 落盘用 application context，与宿主无关。 */
    private var app: Context? = null
    private var restored = false

    /** 界面第一次组合时调用：记下宿主、把上次的对话读回来（含工具卡片），上下文跨重启还在。 */
    fun attach(c: Context) {
        app = c.applicationContext
        host = c
        AiMemory.load(app)
        AiTools.setPageContext(pageNote(c))
        if (restored) {
            return
        }
        restored = true
        if (messages.isNotEmpty()) {
            return                      // 面板重开不重复灌
        }
        try {
            val f = file() ?: return
            if (!f.isFile) {
                return
            }
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val img: String? = o.optString("image", "").ifEmpty { null }
                val tl: String? = o.optString("tool", "").ifEmpty { null }
                val sg = ArrayList<String>()
                val sa = o.optJSONArray("suggests")
                if (sa != null) {
                    for (k in 0 until sa.length()) {
                        val s = sa.optString(k)
                        if (s.isNotEmpty()) {
                            sg.add(s)
                        }
                    }
                }
                messages.add(MwAiMessage(
                        o.optString("role"), o.optString("text"), img, tl,
                        o.optBoolean("ok", true), o.optBoolean("internal", false),
                        o.optLong("at"), false, sg))
            }
        } catch (t: Throwable) {
            // 读档失败不能把面板带崩：接着聊就是，别丢人地空屏
        }
    }

    /** 面板关闭：只有"这个页面就是当前宿主"时才清掉，否则会把别人刚设的宿主抹掉。 */
    fun detach(c: Context) {
        if (host === c) {
            host = null
            AiTools.setPageContext("")
        }
    }

    /** 提示词里那句「当前在哪」，用页面标题而不是类名，模型才说得出人话。 */
    private fun pageNote(c: Context): String = when (c) {
        is com.mineways.MainActivity -> "用户此刻正在「首页（工具箱列表）」这一屏"
        is Activity -> "用户此刻正在「${MwPageGlass.pageTitle(c)}」这一屏"
        else -> ""
    }

    private fun file(): File? {
        val c = app ?: return null
        return File(c.filesDir, "ai/chat.json")
    }

    /** 异步落盘（一次会话几十 KB，主线程不碰磁盘）。 */
    private fun persist() {
        val c = app ?: return
        val snapshot = messages.toList()
        Thread {
            try {
                val arr = JSONArray()
                val from = if (snapshot.size > KEEP) snapshot.size - KEEP else 0
                for (i in from until snapshot.size) {
                    val m = snapshot[i]
                    if (m.running) {
                        continue        // 「正在执行」是瞬时态：崩了重开不该留一条永远算不完的作业
                    }
                    val sg = JSONArray()
                    for (s in m.suggests) {
                        sg.put(s)
                    }
                    arr.put(JSONObject().put("role", m.role).put("text", m.text)
                            .put("image", m.image ?: "").put("tool", m.toolLabel ?: "")
                            .put("ok", m.ok).put("internal", m.internal).put("at", m.at)
                            .put("suggests", sg))
                }
                val f = File(c.filesDir, "ai")
                if (!f.isDirectory) {
                    f.mkdirs()
                }
                File(f, "chat.json").writeText(arr.toString(), Charsets.UTF_8)
            } catch (t: Throwable) {
            }
        }.start()
    }

    companion object {
        /** 本地最多留 200 条；发给模型时另外按接口上限截到最近 30 条。 */
        private const val KEEP = 200
    }

    /** 发一条。历史最多带 29 条（加上本轮正好 30，接口上限）。 */
    fun send(ask: String, quiet: Boolean = false) {
        val text = ask.trim()
        if (text.isEmpty() || streaming) {
            return
        }
        error = null
        lastAsk = text
        if (!quiet) {
            hops = 0
            // 每次开口现读云控：改了 ai_hops 立刻生效，不用重编 App
            hopLimit = AiTools.hopLimit()
        }
        // 工具卡片只给人看；喂模型的是历史里的 user/assistant 与回喂的 mw_result
        val prior = messages.filter { !it.internal && it.role != "tool" && it.text.isNotBlank() }
                .takeLast(29)
                .map { AiClient.Msg(it.role, it.text) }
        val now = System.currentTimeMillis()
        if (quiet) {
            messages.add(MwAiMessage("user", text, internal = true, at = now))
        } else {
            messages.add(MwAiMessage("user", text, at = now))
        }
        messages.add(MwAiMessage("assistant", "", at = now))
        streaming = true
        persist()

        val req = ArrayList<AiClient.Msg>(prior.size + 1)
        req.addAll(prior)
        req.add(AiClient.Msg("user", text))

        // system 人设由 AiClient 内部加，这里只管历史 + 本轮
        session = AiClient.chat(req, object : AiClient.Callback {
            override fun onDelta(piece: String) {
                val i = messages.lastIndex
                if (i < 0) {
                    return
                }
                val cur = messages[i]
                // 收尾之后不再追加：服务端是"先排增量、后完成"，晚到的片段会把已剥干净的
                // 正文重新糊上 ```mw / mw_suggest 原文 —— 真机截图里那两段协议就是这么来的。
                if (cur.done || cur.role != "assistant") {
                    return
                }
                messages[i] = cur.copy(text = cur.text + piece)
            }

            override fun onDone(full: String, modelName: String, totalTokens: Int) {
                streaming = false
                if (modelName.isNotEmpty()) {
                    model = modelName
                }
                // AI 要求的工具调用藏在正文末尾的 ```mw 块里：剥掉再显示，人话归人话；
                // 最后那行 mw_suggest 同理，换成能点的胶囊
                val calls = AiTools.parse(full)
                val shown = AiTools.stripSuggest(AiTools.strip(full))
                val sug = AiTools.suggest(full)
                val i = messages.lastIndex
                if (i >= 0 && messages[i].role == "assistant") {
                    val img = messages[i].image ?: extractImage(shown)
                    if (messages[i].text.isBlank()) {
                        if (shown.isBlank() && img == null && calls.isEmpty()) {
                            messages.removeAt(i)
                            // 流正常结束却什么都没有：多半是网关把错误包成了空流
                            error = "没有收到回复，请重试"
                        } else {
                            messages[i] = messages[i].copy(text = shown, image = img,
                                    suggests = sug, done = true)
                        }
                    } else {
                        messages[i] = messages[i].copy(image = img, suggests = sug, done = true)
                    }
                }
                persist()
                if (calls.isNotEmpty()) {
                    runTools(calls)
                }
            }

            override fun onImage(url: String) {
                // generate_image 的工具事件比正文 markdown 更可靠，先落在这里
                val i = messages.lastIndex
                if (i >= 0 && messages[i].role == "assistant") {
                    messages[i] = messages[i].copy(image = url)
                }
            }

            override fun onError(message: String, code: String, httpStatus: Int,
                                 retryAfterSec: Int) {
                streaming = false
                error = if (retryAfterSec > 0) "$message（$retryAfterSec 秒后可重试）" else message
                val i = messages.lastIndex
                if (i >= 0 && messages[i].role == "assistant" && messages[i].text.isBlank()) {
                    messages.removeAt(i)
                }
            }
        })
    }

    /**
     * 执行 AI 要的工具调用：每个都出一张卡片，结果按 `mw_result` 回喂让它接着说完。
     * 一条问题最多走 `ai_hops`（默认 3）步，防它自己打转烧配额（IP 限流 10 次/分）。
     */
    private fun runTools(calls: List<AiTools.Call>) {
        val ctx = host
        val max = if (hopLimit > 0) hopLimit else 3
        if (hops >= max) {
            messages.add(MwAiMessage("tool", "一条问题最多让 AI 连着做 $max 步，这次到此为止；请把问题说得更具体一点。",
                    toolLabel = "已达步数上限", ok = false, at = System.currentTimeMillis()))
            persist()
            return
        }
        hops++
        val results = ArrayList<String>()
        // 打开界面是排他的：AI 一口气要开三个时只执行第一个，其余如实回「没执行」——
        // 否则返回栈被一次压满，用户看到的只剩最后一个页面。
        var launched = false
        for (c in calls) {
            val label = AiTools.label(c) + if (c.why.isNullOrEmpty()) "" else "（${c.why}）"
            val launching = c.tool == "open" || c.tool == "open_tool" || c.tool == "open_site"
            if (ctx == null) {
                results.add("{\"ok\":false,\"error\":\"界面还没准备好\"}")
                messages.add(MwAiMessage("tool", "界面还没准备好", toolLabel = label, ok = false,
                        at = System.currentTimeMillis()))
                continue
            }
            if (launching && launched) {
                results.add("{\"ok\":false,\"error\":\"一次只打开一个界面，这条没执行；先问用户接下来要哪个\"}")
                messages.add(MwAiMessage("tool", "一次只打开一个界面，这条没执行",
                        toolLabel = label, ok = false, at = System.currentTimeMillis()))
                continue
            }
            val pending = try {
                AiTools.execPending(ctx, c)
            } catch (t: Throwable) {
                AiOps.Pending.now("{\"ok\":false,\"error\":\"" + t.javaClass.simpleName + "\"}")
            }
            if (pending.done()) {
                val res = pending.result()
                if (launching) {
                    launched = !res.contains("\"ok\":false")
                }
                results.add(card(res, label))
            } else {
                // 真活可能跑十几秒：先画「正在执行」，算完再原位换成结果 ——
                // 绝不能让人对着一个不动的界面猜是不是崩了。
                val at = System.currentTimeMillis()
                messages.add(MwAiMessage("tool", "正在执行，这可能要十几秒…",
                        toolLabel = label, ok = true, running = true, at = at))
                persist()
                val pos = messages.lastIndex
                pending.onSettled(AiOps.Pending.OnSettled { json ->
                    val i = if (pos <= messages.lastIndex) pos else messages.size
                    val o = messages.getOrElse(i) { null }
                    if (o != null && o.running) {
                        messages[i] = o.copy(text = briefOf(json), ok = okOf(json), running = false)
                        persist()
                    }
                    // 异步作业的结果也要回喂，否则模型永远不知道它转成功了
                    val feed = ArrayList<String>(1)
                    feed.add(json)
                    send(AiTools.wrapResults(feed), quiet = true)
                })
            }
        }
        if (results.isNotEmpty()) {
            persist()
            send(AiTools.wrapResults(results), quiet = true)
        }
    }

    /** 卡片画一行、JSON 留给回喂 —— 同一条结果两个用途，别各算各的。 */
    private fun card(json: String, label: String): String {
        messages.add(MwAiMessage("tool", briefOf(json), toolLabel = label, ok = okOf(json),
                at = System.currentTimeMillis()))
        return json
    }

    private fun okOf(json: String): Boolean = try {
        JSONObject(json).optBoolean("ok", false)
    } catch (t: Throwable) {
        false
    }

    private fun briefOf(json: String): String = try {
        val o = JSONObject(json)
        when {
            o.has("result") -> o.optString("result")
            o.has("matches") -> "找到 " + o.optInt("count") + " 个相近的：" + o.optJSONArray("matches")
            o.has("categories") -> "已列出全部功能与分类"
            o.has("out") -> "已产出：" + o.optString("out")
            o.has("error") -> o.optString("error")
            else -> o.toString()
        }
    } catch (t: Throwable) {
        json
    }

    /** 出错后重发上一句。先摘掉末尾那条用户消息，避免重复。 */
    fun retry() {
        if (lastAsk.isEmpty()) {
            return
        }
        error = null
        if (messages.isNotEmpty() && messages.last().role == "user") {
            messages.removeAt(messages.lastIndex)
        }
        send(lastAsk)
    }

    fun cancel() {
        session?.cancel()
        session = null
        streaming = false
    }

    fun clear() {
        cancel()
        messages.clear()
        error = null
        lastAsk = ""
        hops = 0
        try {                        // 顶栏「新对话」= 连档一起清，否则重启又被灌回来
            file()?.delete()
        } catch (t: Throwable) {
        }
        // 本机记忆（最近产物路径、开过的功能）也一起清 —— 用户清了对话，
        // 就不该下一条还在「刚才那个包」这种话里被接上
        try {
            host?.let { AiMemory.clear(it.applicationContext) }
        } catch (t: Throwable) {
        }
    }
}

@Composable
fun MwAiOverlay(
        visible: Boolean,
        onDismiss: () -> Unit,
        backdrop: Backdrop,
        spec: MwTokens.Spec,
        tier: MwTier,
        hint: String,
        ripple: Boolean,
        dev: Boolean,
        chat: MwAiChatState,
) {
    val scrim by animateFloatAsState(
            targetValue = if (visible) 0.72f else 0f,
            animationSpec = tween(if (visible) 240 else 180),
            label = "ai-scrim")

    val wave = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible && ripple) {
            wave.snapTo(0f)
            wave.animateTo(1f, tween(760, easing = FastOutSlowInEasing))
        }
    }

    if (!visible && scrim <= 0.002f) {
        return
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))

        if (ripple) {
            MwLiquidRipple(wave.value, spec.accent)
        }

        AnimatedVisibility(
                visible = visible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(200, delayMillis = 40)) +
                        scaleIn(tween(280, delayMillis = 20, easing = FastOutSlowInEasing),
                                initialScale = 0.96f,
                                transformOrigin = TransformOrigin(1f, 0f)),
                exit = fadeOut(tween(140)) +
                        scaleOut(tween(160, easing = FastOutSlowInEasing),
                                targetScale = 0.97f,
                                transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            MwAiChat(backdrop, spec, tier, hint, chat, dev, onDismiss)
        }
    }
}

/** 整屏对话页：环境光 / 顶栏 / 消息区 / 输入条。 */
@Composable
private fun MwAiChat(
        backdrop: Backdrop,
        spec: MwTokens.Spec,
        tier: MwTier,
        hint: String,
        chat: MwAiChatState,
        dev: Boolean,
        onDismiss: () -> Unit,
) {
    val motion = spec.motion && tier != MwTier.FLAT
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    // 入口带进来的预填文字（卡片长按「问 AI」/ 搜索兜底 / 功能页顶栏）：取一次就清，
    // 否则下次打开面板会莫名其妙又把那句塞进输入框。
    val pre = MwAiBus.prefill.value
    LaunchedEffect(pre) {
        if (pre.isNotEmpty()) {
            input = pre
            MwAiBus.prefill.value = ""
            focus.requestFocus()
        }
    }

    // 有新内容就贴底。用 scrollToItem（瞬时）而不是 animate，避免每来一个 token 重启动画。
    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.text?.length) {
        if (chat.messages.isNotEmpty()) {
            listState.scrollToItem(chat.messages.lastIndex)
        }
    }

    // 键盘：Activity 是 adjustResize（经典窗口缩放），这里**不能**再叠 imePadding，
    // 否则窗口已缩一次、内边距再让一次，输入条会飘到键盘上方一大截。
    Box(Modifier.fillMaxSize()) {
        MwAiAmbient(motion)

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            MwAiTopBar(backdrop, spec, tier, chat, motion, dev, onDismiss)
            Spacer(Modifier.height(6.dp))

            Box(Modifier.weight(1f)) {
                // 落盘/回灌都要 Context，顺手把上次的对话读回来
                val host = LocalContext.current
                LaunchedEffect(host) { chat.attach(host) }
                // 面板关掉就把宿主交回去：会话状态是共享的，不清掉会拿着已经销毁的页面去起 Intent
                DisposableEffect(host) {
                    onDispose { chat.detach(host) }
                }
                if (chat.messages.isEmpty()) {
                    MwAiEmpty(motion, onPick = {
                        input = it
                        focus.requestFocus()
                    })
                } else {
                    // 回喂给模型的 mw_result 不上屏，界面上只出人话与工具卡片
                    val shown = chat.messages.filterNot { it.internal }
                    LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 10.dp, bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(shown, key = { i, _ -> i }) { index, m ->
                            MwAiBubble(
                                    m = m,
                                    // 只让最新两条播入场：上滚看到的老消息不再重播
                                    animateIn = motion && index >= shown.size - 2,
                                    streaming = chat.streaming && index == shown.size - 1 &&
                                            m.role == "assistant",
                                    onAsk = { s -> chat.send(s) })
                        }
                        val err = chat.error
                        if (err != null) {
                            item(key = "err") {
                                MwAiError(err) { chat.retry() }
                            }
                        }
                    }
                }
            }

            MwAiComposer(
                    backdrop = backdrop,
                    spec = spec,
                    tier = tier,
                    hint = hint,
                    value = input,
                    onValueChange = { input = it },
                    streaming = chat.streaming,
                    motion = motion,
                    focus = focus,
                    onSend = {
                        val t = input.trim()
                        if (t.isNotEmpty()) {
                            chat.send(t)
                            input = ""
                            keyboard?.hide()
                        }
                    })

            // 云控关掉或改动了什么，直接写在这行里：出了问题看一眼就知道，不用抓 log 也不用猜
            val note = remember(chat.messages.size) { AiTools.policyNote() }
            BasicText(if (note.startsWith("无（")) "AI 生成内容仅供参考"
            else "AI 生成内容仅供参考 · 云控：$note",
                    style = MwType.caption.copy(fontSize = 10.5.sp,
                            color = Color.White.copy(alpha = 0.30f),
                            textAlign = TextAlign.Center),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp))
        }
    }
}

// ================================================================ 环境光

/**
 * 两团缓慢漂移的径向光晕。
 *
 * 关键在 `graphicsLayer`：渐变只光栅化一次，之后每帧只改 transform，不重画整屏 ——
 * 这是「有 AI 氛围」和「待机发烫」之间那条线。FLAT / ui_motion=0 时整块不画。
 */
@Composable
private fun MwAiAmbient(motion: Boolean) {
    if (!motion) {
        return
    }
    val t = rememberInfiniteTransition(label = "ai-ambient")
    val driftA by t.animateFloat(0f, 1f,
            infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Reverse),
            label = "driftA")
    val driftB by t.animateFloat(0f, 1f,
            infiniteRepeatable(tween(31000, easing = LinearEasing), RepeatMode.Reverse),
            label = "driftB")

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.size(320.dp).align(Alignment.TopEnd)
                .offset(x = 90.dp, y = (-110).dp)
                .graphicsLayer {
                    translationX = -46.dp.toPx() * driftA
                    translationY = 34.dp.toPx() * driftA
                    alpha = 0.20f
                }
                .background(Brush.radialGradient(listOf(
                        AiCyan.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
        Box(Modifier.size(280.dp).align(Alignment.BottomStart)
                .offset(x = (-90).dp, y = 90.dp)
                .graphicsLayer {
                    translationX = 52.dp.toPx() * driftB
                    translationY = -30.dp.toPx() * driftB
                    alpha = 0.16f
                }
                .background(Brush.radialGradient(listOf(
                        AiViolet.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
    }
}

// ================================================================ 顶栏

@Composable
private fun MwAiTopBar(
        backdrop: Backdrop,
        spec: MwTokens.Spec,
        tier: MwTier,
        chat: MwAiChatState,
        motion: Boolean,
        dev: Boolean,
        onDismiss: () -> Unit,
) {
    val closeSrc = remember { MutableInteractionSource() }
    val newSrc = remember { MutableInteractionSource() }
    val subtitle = when {
        chat.streaming -> "正在回复…"
        chat.error != null -> "连接异常"
        chat.model.isNotEmpty() -> chat.model
        else -> "MinewaysMobile 智能助手"
    }

    MwGlassPanel(backdrop, spec, tier, Modifier.fillMaxWidth(),
            quality = MwGlassQuality.FULL, radius = 100.dp, chromatic = true) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
            MwAiOrb(28.dp, motion)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("AI 助手", style = MwType.headline.copy(fontSize = 15.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (dev) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.clip(RoundedRectangle(5.dp))
                                .background(Color(0x33E09551))
                                .padding(horizontal = 5.dp, vertical = 1.dp)) {
                            BasicText("DEV", style = MwType.badge.copy(
                                    fontSize = 9.sp,
                                    color = Color(0xFFE09551),
                                    letterSpacing = 0.4.sp))
                        }
                    }
                }
                BasicText(subtitle,
                        style = MwType.caption.copy(fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.50f)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (chat.messages.isNotEmpty()) {
                Box(Modifier.clip(Capsule())
                        .clickable(interactionSource = newSrc, indication = null) {
                            chat.clear()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)) {
                    BasicText("新对话", style = MwType.caption.copy(fontSize = 11.5.sp,
                            color = Color.White.copy(alpha = 0.55f)))
                }
            }
            Box(Modifier.size(40.dp).clip(Capsule())
                    .clickable(interactionSource = closeSrc,
                            indication = null, onClick = onDismiss),
                    contentAlignment = Alignment.Center) {
                IconVec(Icons.Rounded.Close, Color.White.copy(alpha = 0.60f), 17.dp)
            }
        }
        if (chat.streaming) {
            MwAiShimmerBar(Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth(0.86f).height(1.5.dp))
        }
    }
}

// ================================================================ 空状态

@Composable
private fun MwAiEmpty(motion: Boolean, onPick: (String) -> Unit) {
    val suggestions = listOf(
            "帮我找一个视频格式转换工具",
            "云控配置怎么写？",
            "首页展示栏怎么加视频？")
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.Center) {
        MwAiOrb(48.dp, motion)
        Spacer(Modifier.height(20.dp))
        BasicText("有什么可以帮你？",
                style = MwType.title.copy(fontSize = 21.sp, lineHeight = 28.sp))
        Spacer(Modifier.height(7.dp))
        BasicText("找工具、写云控配置、解释功能",
                style = MwType.body.copy(fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.52f)))
        Spacer(Modifier.height(30.dp))
        suggestions.forEachIndexed { i, s ->
            MwAiSuggestion(s, i, motion) { onPick(s) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun MwAiSuggestion(text: String, index: Int, motion: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    // 错峰入场：只播一次，不随重组重播
    val appear = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) {
            delay(120L + index * 70L)
            appear.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    Row(Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 12.dp.toPx()
            }
            .clip(RoundedRectangle(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedRectangle(16.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .then(Modifier.mwPress(src, motion))
            .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, style = MwType.body.copy(fontSize = 13.5.sp,
                color = Color.White.copy(alpha = 0.86f)), modifier = Modifier.weight(1f))
        IconVec(Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                Color.White.copy(alpha = 0.32f), 18.dp)
    }
}

// ================================================================ 消息

@Composable
private fun MwAiBubble(m: MwAiMessage, animateIn: Boolean, streaming: Boolean,
        onAsk: (String) -> Unit) {
    val appear = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animateIn) {
            appear.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        }
    }
    val entrance = Modifier.graphicsLayer {
        alpha = appear.value
        translationY = (1f - appear.value) * 14.dp.toPx()
        val sc = 0.98f + 0.02f * appear.value
        scaleX = sc
        scaleY = sc
    }

    if (m.role == "tool") {
        // AI 调用工具的记录：一行摘要 + 执行结果；成功青色，失败橙色，一眼看得出错在哪
        Row(Modifier.fillMaxWidth().then(entrance), horizontalArrangement = Arrangement.Start) {
            Column(Modifier.widthIn(max = 304.dp)
                    .clip(RoundedRectangle(14.dp))
                    .background(if (m.ok) AiCyan.copy(alpha = 0.075f)
                    else Color(0xFFFFB4AB).copy(alpha = 0.10f))
                    .border(1.dp, if (m.ok) AiCyan.copy(alpha = 0.22f)
                    else Color(0xFFFFB4AB).copy(alpha = 0.28f), RoundedRectangle(14.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp)) {
                BasicText("⚙ " + (m.toolLabel ?: "调用工具")
                        + if (m.running) " · 执行中" else "",
                        style = MwType.caption.copy(fontSize = 11.5.sp,
                                color = if (m.ok) AiCyan else Color(0xFFFFB4AB)))
                if (m.text.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    BasicText(m.text, style = MwType.body.copy(fontSize = 12.5.sp,
                            lineHeight = 18.sp, color = Color.White.copy(alpha = 0.78f)))
                }
                if (m.running) {
                    // 作业还在跑：给一条扫过的光带，证明它没死
                    Spacer(Modifier.height(6.dp))
                    MwAiShimmerBar(Modifier.fillMaxWidth(0.6f).height(2.dp))
                }
            }
        }
        return
    }

    if (m.role == "user") {
        Row(Modifier.fillMaxWidth().then(entrance), horizontalArrangement = Arrangement.End) {
            Box(Modifier.widthIn(max = 304.dp)
                    .clip(RoundedRectangle(18.dp))
                    .background(Brush.linearGradient(listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.10f))))
                    .padding(horizontal = 15.dp, vertical = 11.dp)) {
                BasicText(m.text, style = MwType.body.copy(fontSize = 14.sp,
                        lineHeight = 21.sp, color = Color.White.copy(alpha = 0.95f)))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().then(entrance), horizontalArrangement = Arrangement.Start) {
            MwAiOrb(24.dp, false)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 304.dp)
                    .clip(RoundedRectangle(18.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedRectangle(18.dp))
                    .padding(horizontal = 15.dp, vertical = 12.dp)) {
                // 流式期间也绝不显示协议：模型是逐字吐的，围栏与 JSON 半路就在屏上了
                val body = stripImages(if (m.done) m.text else AiTools.visible(m.text))
                if (body.isEmpty() && m.image == null) {
                    MwAiTypingDots()
                } else {
                    if (body.isNotEmpty()) {
                        BasicText(body, style = MwType.body.copy(fontSize = 14.sp,
                                lineHeight = 22.sp, color = Color.White.copy(alpha = 0.90f)))
                    }
                    val img = m.image
                    if (img != null) {
                        if (body.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                        }
                        MwAiRemoteImage(img)
                    }
                    if (streaming && img == null) {
                        Spacer(Modifier.height(9.dp))
                        MwAiShimmerBar(Modifier.fillMaxWidth(0.72f).height(2.dp))
                    }
                    // 下一步建议：点一下就接着问，不用用户自己想措辞
                    if (m.suggests.isNotEmpty() && !streaming) {
                        Spacer(Modifier.height(10.dp))
                        for (s in m.suggests) {
                            Box(Modifier.padding(bottom = 6.dp)
                                    .clip(Capsule())
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .border(1.dp, AiCyan.copy(alpha = 0.30f), Capsule())
                                    .clickable(interactionSource = remember {
                                        MutableInteractionSource()
                                    }, indication = null) { onAsk(s) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)) {
                                BasicText(s, style = MwType.caption.copy(fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.82f)))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val AiImageRe = Regex("!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)")

private fun extractImage(text: String): String? =
        AiImageRe.find(text)?.groupValues?.getOrNull(1)

/** 去掉 markdown 图片语法，正文只留文字；图片单独用卡片渲染。 */
private fun stripImages(text: String): String =
        AiImageRe.replace(text, "").trim()

/**
 * AI 生成的图片：异步下载 + 圆角卡。
 *
 * 图片是 1024×1024 的 PNG（约 1.5MB），解码后 4MB —— 只保留当前气泡的一张，
 * 不做全局缓存，避免聊天久了内存一直涨。失败可点按重试，成功点按看原图。
 */
@Composable
private fun MwAiRemoteImage(url: String) {
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var attempt by remember(url) { mutableStateOf(0) }
    val uriHandler = LocalUriHandler.current
    val shape = RoundedRectangle(12.dp)

    LaunchedEffect(url, attempt) {
        bmp = null
        failed = false
        val b = withContext(Dispatchers.IO) { loadBitmap(url) }
        if (b != null) bmp = b else failed = true
    }

    val b = bmp
    if (b != null) {
        Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "AI 生成的图片",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
                        .aspectRatio(b.width.toFloat() / b.height.coerceAtLeast(1))
                        .clip(shape)
                        .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                        .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null) {
                            try {
                                uriHandler.openUri(url)
                            } catch (t: Throwable) {
                            }
                        })
    } else {
        Box(Modifier.fillMaxWidth().height(168.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null) {
                    if (failed) {
                        attempt += 1
                    }
                },
                contentAlignment = Alignment.Center) {
            BasicText(
                    if (failed) "图片加载失败 · 点按重试" else "正在生成图片…",
                    style = MwType.caption.copy(fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.45f)))
        }
    }
}

private fun loadBitmap(url: String): Bitmap? {
    var conn: HttpURLConnection? = null
    return try {
        val c = URL(url).openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 12000
        c.readTimeout = 25000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MinewaysMobile/AI")
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (t: Throwable) {
        null
    } finally {
        try {
            conn?.disconnect()
        } catch (t: Throwable) {
        }
    }
}

@Composable
private fun MwAiError(message: String, onRetry: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth()
            .clip(RoundedRectangle(14.dp))
            .background(MwTokens.danger.copy(alpha = 0.12f))
            .border(1.dp, MwTokens.danger.copy(alpha = 0.28f), RoundedRectangle(14.dp))
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
        BasicText(message, style = MwType.caption.copy(fontSize = 12.sp,
                color = Color(0xFFFFD9D9)), modifier = Modifier.weight(1f))
        Box(Modifier.clip(Capsule())
                .clickable(interactionSource = src, indication = null, onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
            BasicText("重试", style = MwType.caption.copy(fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.92f)))
        }
    }
}

// ================================================================ 输入条

@Composable
private fun MwAiComposer(
        backdrop: Backdrop,
        spec: MwTokens.Spec,
        tier: MwTier,
        hint: String,
        value: String,
        onValueChange: (String) -> Unit,
        streaming: Boolean,
        motion: Boolean,
        focus: FocusRequester,
        onSend: () -> Unit,
) {
    val canSend = value.isNotBlank() && !streaming
    var focused by remember { mutableStateOf(false) }
    val rim by animateFloatAsState(if (focused) 0.60f else 0.10f, tween(220), label = "rim")
    val halo by animateFloatAsState(if (canSend) 0.55f else 0f, tween(240), label = "halo")
    val sendBg by animateColorAsState(
            if (canSend) Color.White else Color.White.copy(alpha = 0.10f),
            tween(160), label = "ai-send-bg")
    val sendFg by animateColorAsState(
            if (canSend) Color.Black else Color.White.copy(alpha = 0.30f),
            tween(160), label = "ai-send-fg")
    val src = remember { MutableInteractionSource() }

    // 获焦时描边从白 10% 亮成青→紫渐变；失焦退回去
    Box(Modifier.fillMaxWidth()
            .border(1.dp,
                    Brush.linearGradient(listOf(
                            AiCyan.copy(alpha = rim),
                            AiViolet.copy(alpha = rim))),
                    RoundedRectangle(26.dp))
            .padding(1.dp)) {
        MwGlassPanel(backdrop, spec, tier, Modifier.fillMaxWidth(),
                quality = MwGlassQuality.FULL, radius = 25.dp, chromatic = true) {
            Row(Modifier.fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (value.isEmpty()) {
                        BasicText(hint, style = MwType.body.copy(fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.34f)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = MwType.body.copy(fontSize = 14.sp,
                                    lineHeight = 21.sp, color = Color.White.copy(alpha = 0.95f)),
                            cursorBrush = SolidColor(AiCyan),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { onSend() }),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 21.dp)
                                    .focusRequester(focus)
                                    .onFocusChanged { focused = it.isFocused })
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(38.dp)) {
                    // 可发送时按钮后面亮起一圈青色光晕
                    Box(Modifier.matchParentSize()
                            .graphicsLayer { alpha = halo }
                            .background(Brush.radialGradient(listOf(
                                    AiCyan.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
                    Box(Modifier.matchParentSize().clip(Capsule())
                            .background(sendBg)
                            .clickable(interactionSource = src, indication = null,
                                    enabled = canSend, onClick = onSend)
                            .then(Modifier.mwPress(src, motion)),
                            contentAlignment = Alignment.Center) {
                        IconVec(Icons.Rounded.ArrowUpward, sendFg, 19.dp)
                    }
                }
            }
        }
    }
}

// ================================================================ AI 标识

/**
 * 光环球：外发光 + 旋转的 sweep 渐变环 + 呼吸内核。
 *
 * 消息气泡里传 `motion = false` 用静态版 —— 一屏十几条消息就是十几个无限动画，
 * 没必要，也赔不起。
 */
@Composable
private fun MwAiOrb(size: Dp, motion: Boolean) {
    val spin = rememberSpin(motion, 7000)
    val pulse = rememberPulse(motion, 2400)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        // 外发光
        Box(Modifier.size(size * 1.45f)
                .background(Brush.radialGradient(listOf(
                        AiCyan.copy(alpha = 0.16f), Color.Transparent)), CircleShape))
        // 旋转的渐变环
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin }) {
            val w = 1.6.dp.toPx()
            drawCircle(
                    brush = Brush.sweepGradient(listOf(AiCyan, AiViolet, AiCyan)),
                    radius = this.size.minDimension / 2f - w / 2f,
                    center = center,
                    style = Stroke(w))
        }
        // 呼吸内核
        Box(Modifier.size(size * 0.60f)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
                .background(Brush.radialGradient(listOf(
                        Color.White.copy(alpha = 0.95f),
                        AiCyan.copy(alpha = 0.55f),
                        Color.Transparent)), CircleShape))
    }
}

@Composable
private fun rememberSpin(motion: Boolean, durationMs: Int): Float {
    if (!motion) {
        return 0f
    }
    val t = rememberInfiniteTransition(label = "orb-spin")
    val v by t.animateFloat(0f, 360f,
            infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = "spin")
    return v
}

@Composable
private fun rememberPulse(motion: Boolean, durationMs: Int): Float {
    if (!motion) {
        return 1f
    }
    val t = rememberInfiniteTransition(label = "orb-pulse")
    val v by t.animateFloat(0.88f, 1.02f,
            infiniteRepeatable(tween(durationMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse")
    return v
}

/** 等待首 token 的三点呼吸。画在 Canvas 里：只重绘，不重组。 */
@Composable
private fun MwAiTypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    Canvas(Modifier.size(width = 23.dp, height = 5.dp)) {
        val r = 2.5.dp.toPx()
        val step = 9.dp.toPx()
        val cy = size.height / 2f
        repeat(3) { i ->
            val p = (phase + i * 0.22f) % 1f
            val a = 0.22f + 0.78f * (1f - abs(p * 2f - 1f))
            drawCircle(Color.White.copy(alpha = a), r, Offset(r + i * step, cy))
        }
    }
}

/** 一条扫过的渐变光带（顶栏 / 流式回答下方）。 */
@Composable
private fun MwAiShimmerBar(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val p by t.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "shimmer-p")
    Canvas(modifier) {
        val w = size.width
        if (w <= 0f) {
            return@Canvas
        }
        val cx = -w * 0.3f + p * (w * 1.6f)
        drawRect(
                brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent,
                                AiCyan.copy(alpha = 0.85f),
                                AiViolet.copy(alpha = 0.85f),
                                Color.Transparent),
                        start = Offset(cx - w * 0.25f, 0f),
                        end = Offset(cx + w * 0.25f, 0f)),
                size = size)
    }
}

// ================================================================ 水波纹

/**
 * 一滴水落进玻璃：三条带谐波抖动的环 + 色散 + 中心水珠。
 *
 * 环的半径用 `sin(5θ)` 与 `sin(9θ)` 叠加做形变，振幅随进度衰减 —— 起始是圆的，
 * 扩散开时像被玻璃表面张力拉出细微波纹，最后完全平滑地消失。
 */
@Composable
private fun MwLiquidRipple(progress: Float, accent: Color) {
    if (progress <= 0f || progress >= 1f) {
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width - 44.dp.toPx()
        val cy = 38.dp.toPx()
        val maxR = hypot(size.width, size.height) * 0.95f
        val steps = 132

        for (ring in 0 until 3) {
            val t = ((progress - ring * 0.13f) / (1f - ring * 0.13f)).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) {
                continue
            }
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            val r = 16.dp.toPx() + (maxR - 16.dp.toPx()) * ease
            val a = 1f - t

            val path = Path()
            for (i in 0..steps) {
                val ang = i.toFloat() / steps * (2f * PI.toFloat())
                val wobble = 1f +
                        0.050f * (1f - t) * sin(ang * 5f + ring * 1.7f) +
                        0.022f * (1f - t) * sin(ang * 9f - ring * 2.1f)
                val rr = r * wobble
                val x = cx + cos(ang) * rr
                val y = cy + sin(ang) * rr
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            drawPath(path, accent.copy(alpha = a * 0.07f), style = Stroke(10.dp.toPx()))
            drawPath(path, accent.copy(alpha = a * 0.26f), style = Stroke(1.3.dp.toPx()))
            drawPath(path, AiCyan.copy(alpha = a * 0.12f), style = Stroke(1.8.dp.toPx()))
        }

        val drop = (progress / 0.50f).coerceIn(0f, 1f)
        if (drop < 1f) {
            val br = 12.dp.toPx() + 118.dp.toPx() * (1f - (1f - drop) * (1f - drop))
            drawCircle(
                    brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = (1f - drop) * 0.30f),
                                    Color.Transparent),
                            center = Offset(cx, cy),
                            radius = br),
                    radius = br,
                    center = Offset(cx, cy))
        }
    }
}
