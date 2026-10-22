package com.mineways.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.mineways.AiAccess
import com.mineways.AiOps

/**
 * AI 的**共享状态与宿主桥**。
 *
 * ## 为什么只剩这些
 * 以前这里还有一层"功能页里呼出的全屏对话"（顶栏胶囊 + 自绘页右上角小胶囊）。用户判定
 * 「又挡空间又没用」，**已经整块删掉** —— AI 的入口就是首页右上角三个点，不再往每个功能页
 * 的界面上塞东西。所以这个文件现在只留两件不会挡画面的事：
 *
 *  1. [chat]：全站共享的会话状态。入口只剩一个之后它仍然有用 —— 会话要跨重启、跨页返回
 *     保持同一条上下文（同一份 `filesDir/ai/chat.json`）。
 *  2. [requestPick] / [handlePickResult]：AI 的 `pick` 工具要让用户自己选文件。
 *     选择器必须由**宿主 Activity** 发起，结果也只能从 `onActivityResult` 回来 ——
 *     Compose 面板只是一层 View，自己没有收结果的能力，所以这里做那根桥。
 */
object MwAi {

    /** 共享的会话状态（首页面板用；同一份落盘文件、同一条上下文）。 */
    @JvmField
    val chat = MwAiChatState()

    /** 与 [com.mineways.MainActivity.onActivityResult] 约定好的请求码（Java 侧直接引用）。 */
    const val REQ_PICK: Int = 8941

    /** AI 这一整条是否对当前用户开放（人群 + 云控总闸）。 */
    @JvmStatic
    fun allowed(c: Context): Boolean = try {
        AiAccess.refreshAndAllowed(c)
    } catch (t: Throwable) {
        false
    }

    /**
     * 发起一次"让用户挑个文件"。
     *
     * @return 真的发出去了吗；false = 这台机器上没有可用的文件选择器
     */
    @JvmStatic
    fun requestPick(ctx: Context, what: String): Boolean = try {
        val it = Intent(Intent.ACTION_OPEN_DOCUMENT)
        it.addCategory(Intent.CATEGORY_OPENABLE)
        // 不能限定 MIME：.mcpack 这类扩展名在很多文件管理器里推不出 zip/octet-stream，
        // 一限定就是"整台机器的包都灰掉选不了"。选完之后由执行层判。
        it.type = "*/*"
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        (ctx as Activity).startActivityForResult(it, REQ_PICK)
        true
    } catch (t: Throwable) {
        false
    }

    /** 宿主 Activity 把 {@code ACTION_OPEN_DOCUMENT} 的结果递进来（取消也要递，AI 要知道）。 */
    @JvmStatic
    fun handlePickResult(act: Activity, resultCode: Int, data: Intent?): Boolean {
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            AiOps.resolvePick(null, null, act)
            return true
        }
        AiOps.resolvePick(uri.toString(), nameOf(act, uri), act)
        return true
    }

    private fun nameOf(act: Activity, u: Uri): String {
        try {
            act.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = if (i >= 0) c.getString(i) else null
                        if (!s.isNullOrBlank()) {
                            return s
                        }
                    }
                }
        } catch (t: Throwable) {
        }
        return u.lastPathSegment ?: ""
    }
}
