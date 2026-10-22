package com.mineways.ui

import com.mineways.home.HomeIndex

/**
 * Java ↔ Compose 的唯一接缝。
 *
 * 为什么这么切：入口模型、档位判定、点击放行、激活码与白名单流程**全部留在 Java 原地不动**，
 * Compose 只负责画。这样重构界面不可能顺手改坏云控接线（历史上「配置生效 17 条、界面毫无变化」
 * 就是有人另开了一条取数路径），也不可能把点击顺序写错（先停用、再 ask 确认、最后才谈扣激活码）。
 *
 * 数据用带公有字段的简单类 + ARGB int，Java 侧构造不用写 Kotlin 泛型。
 */

/** 一个可点/可展示的单元格。 */
class MwCell {
    @JvmField var title: String = ""
    @JvmField var sub: String = ""

    /** 右上角徽标（"白名单"、"云端"、"已停用"…），null 就不画。 */
    @JvmField var badge: String? = null
    @JvmField var badgeArgb: Int = 0x99FFFFFF.toInt()

    /** 右侧值文本（关于页那种「标签 — 值」行），null 就不画。 */
    @JvmField var value: String? = null

    /** 整段说明文字（多行、正文宽度）。和 title 同时给就是「小标题 + 一段话」。 */
    @JvmField var paragraph: String? = null

    /** 行尾按钮文案（"授权登录"、"立即刷新"）；null 表示整行按下走 [action]。 */
    @JvmField var actionLabel: String? = null
    @JvmField var primary: Boolean = false

    /** 图标资源与底色（首页/收录清单用；关于页文字行为 0 就不画底托）。 */
    @JvmField var iconRes: Int = 0
    @JvmField var tintArgb: Int = 0xFFE8E8E8.toInt()

    /** 整行点击的动作号；-1 = 不可点。见 [MwUiHost.ACTION_*]。 */
    @JvmField var action: Int = -1

    /** 这一格对应的入口（只有 ACTION_OPEN_ITEM 需要）；点击原样交回 Java 放行。 */
    @JvmField var item: HomeIndex.Item? = null

    /** 组内序号（0 = 不画）。保留原来 01/02 的秩序感，但压到角落。 */
    @JvmField var index: Int = 0

    /** 行点开后要打开的链接（鸣谢、社群这种）。 */
    @JvmField var url: String? = null

    /** 提示行（停用原因、灰度说明），画在标题下方。 */
    @JvmField var note: String? = null
    @JvmField var noteArgb: Int = 0xE07B7B
}

/** 一组单元格 + 组头。 */
class MwBlock {
    @JvmField var title: String? = null
    @JvmField var subtitle: String? = null

    /** 组尾计数（"共 12 项"）。 */
    @JvmField var trailing: String? = null

    /** true = 两列网格卡；false = 列表行。 */
    @JvmField var grid: Boolean = false
    @JvmField var cells: List<MwCell> = emptyList()

    /**
     * true = 默认只露前几项，其余点「展开」再看。
     * 收录网站一百多家，全列会把关于页拉得看不到底 —— 旧版就是这么折叠的，新版必须保住。
     */
    @JvmField var expandable: Boolean = false
}

/** 页签头部信息。 */
class MwTabMeta {
    @JvmField var title: String = ""
    @JvmField var hint: String = ""
    @JvmField var stats: String = ""
    @JvmField var searchHint: String = "搜索功能、站点、分类"
    @JvmField var categories: List<String> = emptyList()
}

/**
 * 悬浮导航栏的占位高度（dp）。
 *
 * 为什么不让 Java 给 ComposeView 设 paddingBottom：`AbstractComposeView.onMeasure` 会
 * 扣掉 padding（实测 1.11.1 的字节码里明确调了 getPaddingBottom），于是页面背景只画到
 * 导航栏上沿，下面那条让位带露出宿主 FrameLayout 的黑底 —— 真机上就是"导航栏与主页之间
 * 有个黑框"。让位必须发生在 Compose 内部（LazyColumn 的 contentPadding），背景才能铺满。
 */
object MwInsets {
    /** 0 = 还没量出来，界面用创建时传进来的兜底值。 */
    @JvmField
    val bottomNavDp = androidx.compose.runtime.mutableIntStateOf(0)
}

interface MwUiHost {

    companion object {
        /** 动作号是 Java 与 Compose 之间的枚举，加新的只在这两处加。 */
        const val ACTION_OPEN_ITEM = 1        // 打开通道里的条目（走 Java 原有放行次序）
        const val ACTION_REFRESH = 2          // 立即刷新云端配置
        const val ACTION_LOGIN = 3            // 统一登录入口（论坛 / Google / 微软 都在这一个面板里）
        const val ACTION_JOIN = 6             // 加入社群（QQ 群）
        const val ACTION_REPO = 7             // 打开仓库页
        const val ACTION_OPEN_URL = 8         // 打开 cell 自带 url
        const val ACTION_ABOUT = 9            // 跳到「关于」页签
        const val ACTION_RETRY = 10           // 重试取数
        /** AI 全屏页展开/收起（url = "1"/"0"）：宿主据此隐藏/恢复底部玻璃导航栏。 */
        const val ACTION_AI_OPEN = 11

        /** 长按功能卡「问 AI」（url = 功能标题）：打开对话并把这一项作为话题带进去。 */
        const val ACTION_AI_ASK = 12

        /** 清掉「玻璃效果」的机型询问选择，下次进页面会重新问一次。 */
        const val ACTION_GLASS_RESET = 13
    }

    /** 界面参数（云控可覆盖）。 */
    fun spec(): MwTokens.Spec

    /** 第 index 个页签的头部信息。 */
    fun tabMeta(index: Int): MwTabMeta

    /** 第 index 个页签的分组内容；取数必须由 HomeIndex 出来，不许另开路径。 */
    fun blocks(index: Int): List<MwBlock>

    /** 关于页内容。 */
    fun aboutBlocks(): List<MwBlock>

    /** 关于页头部（版本、档位、云控来源等一句话）。 */
    fun aboutMeta(): MwTabMeta

    /**
     * 执行一个动作。条目与链接按原样带回，判定与放行全在 Java 侧，界面不参与决策。
     */
    fun run(action: Int, item: HomeIndex.Item?, url: String?)

    /**
     * 登录态变了（成功 / 退出）：Java 侧重建界面。
     * 身份、名单命中、门禁结论都是现读的，界面这边一份都不缓存 —— 缓存就会出现在
     * 「已经登录了，卡片还写着需登录」那种撒谎的画面上。
     */
    fun refreshAfterLogin()

    /**
     * 「刚刚解锁/登录成功」的计数信号：Java 在成功那一刻 +1，界面看到数字变了就放一次彩纸。
     * 用计数器而不是回调，是因为登录后整页会重建，回调会随旧组合丢掉。
     */
    fun fxToken(): Int = 0

    /** 分类导轨选中某一项时的过滤；返回过滤后的分组。 */
    fun blocksFiltered(index: Int, categoryIndex: Int): List<MwBlock>
}
