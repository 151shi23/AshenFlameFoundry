package com.mineways;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentContainerView;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.NavOptions;

import com.mineways.cloud.FeatureGate;
import com.mineways.cloud.LoginPolicy;
import com.mineways.home.HomeIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AshenFlame Foundry（AFF）· 工具箱外壳 —— 纯 UI，不含任何转换/导出逻辑。
 *
 * <p><b>入口模型</b>：每个工具在 {@code LOCAL_TOOLS} / {@code WEB_TOOLS} 里只登记一次，
 * 页面取数一律经过 {@link HomeIndex} —— 它把内置登记表与云端配置合并成一份答案（档位、
 * 是否显示、是否可用、文案覆盖、图标、Intent），所以云端的一条指令能同时贯通
 * 全部页 / 免费页 / 白名单页 / 限制页 / 搜索 / 分类胶囊 / 关于页收录清单 / 点击放行。
 * <pre>
 *   local  = 本机实现（内置 Activity）
 *   web    = 网页工具（WebToolActivity 容器）
 *   free   = 免费
 *   paid   = 白名单功能（登录白名单账号后开放）
 *   limit  = 限制（每次用掉 1 个激活码）
 * </pre>
 *
 * <p>原导出界面（导出 / 网易 / 转换 / 工具页）仍是 {@link ExportActivity}，内核算法一行未改。
 */
public class MainActivity extends AppCompatActivity implements com.mineways.ui.MwUiHost {

    // ---------------------------------------------------------------- 色板（唯一强调色：熔炉橙）

    private static final int BG = 0xFF000000;
    private static final int PANEL = 0x14FFFFFF;
    private static final int LINE = 0x14FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0x99FFFFFF;
    private static final int DIM2 = 0x5CFFFFFF;
    private static final int ACCENT = 0xFFFFFFFF;

    /**
     * 档位常量只有 {@code FeatureGate} 那一份。这里以前另写了一套 {@code 1=白名单、2=限制}，
     * 与门面的 {@code 1=限制、2=白名单} 正好相反 —— 云端每改一次档位都会落到错误的页签上。
     */
    private static final int KIND_FREE = com.mineways.cloud.FeatureGate.KIND_FREE;
    private static final int KIND_LIMIT = com.mineways.cloud.FeatureGate.KIND_LIMIT;
    private static final int KIND_PAID = com.mineways.cloud.FeatureGate.KIND_PAID;

    private static final String QQ_GROUP = "https://qm.qq.com/q/6mxOaslq7e";
    private static final String REPO = "https://github.com/151shi23/AshenFlameFoundry";

    /** 页签：付费页已改为「白名单功能」，限制页（激活码）已移除。 */
    private static final String[] TAB_TITLES = {"全部", "免费", "白名单功能", "限制", "关于"};
    private static final int[] TAB_ICONS = {
            R.drawable.ic_nav_all, R.drawable.ic_nav_free, R.drawable.ic_nav_paid,
            R.drawable.ic_nav_limit, R.drawable.ic_about};

    /** 标签 → 页签。改这里就能改分类，不用碰卡片。 */
    private static final String[] TAB_TAGS = {"", "free", "paid", "limit", null};

    private LiquidGlassNav glassNav;
    /** 官方 Navigation 的控制器（每个标签一个 Fragment 目的地）。 */
    private NavController navController;
    /** 5 个标签目的地，与 TAB_TITLES 一一对应。 */
    private static final int[] DEST_IDS = {R.id.page_all, R.id.page_free, R.id.page_paid,
            R.id.page_limit, R.id.page_about};
    /** 当前活着的页面视图（Fragment 建好时登记，用于底部让位）。 */
    private final List<View> pageViews = new ArrayList<>();
    /** 页面底部让位高度（跟着悬浮胶囊的高度走）。 */
    private int pageBottomPad;
    private int currentTab = 0;

    // ---------------------------------------------------------------- 工具登记表（唯一数据源）

    /** 本机工具：{标题, 说明, 图标, 标签, 目标页面}；目标页面空串=主入口。 */
    private static final String[][] LOCAL_TOOLS = {
            {"存档导出", "把 Java、基岩、网易存档导成带贴图的 OBJ", "export", "free local", "export"},
            {"网易存档解密", "找存档、解密、导出，都在这一页", "world", "free local", "world"},
            {"真实世界生成", "拉真实地图数据，在手机里生成 Minecraft 存档", "realworld", "free local", ""},
            {"Mine-imator 动画", "内置动画工作室：建模、摆动作、渲染出片，全离线", "mineimator", "limit local", ""},
            {"文件修复", "修 P3D 工程包（2.0/3.0）、ZIP、图片、音频、BB 导出格式", "repair", "limit local", ""},
            {"格式转换", "图片 / 音频 / 模型互转；模型支持 .blend → GLB、OBJ、STL 等", "fmtconv", "free local", ""},
            {"Blender 渲染", "手机本地跑完整 Blender：装官方 Linux 版，渲染 .blend 工程出图", "blender", "free local", ""},
            {"莱茵生命终端", "明日方舟莱茵生命终端 3D 复刻，档案阵列与结构查看全离线", "rhinelab", "free local", ""},
            {"云间列车", "落日云海与穿云列车：WebGL 实时动画，可调速度/云量/染色/曝光", "cloudtrain", "free local", ""},
            {"视频剪辑", "OpenCut 剪辑器（离线）：多轨时间轴、文字描边阴影、出入场动画、导出 MP4", "opencut", "paid local", ""},
            {"剪辑标识清除", "抹掉剪映 / 快影 / 必剪 写进视频元数据的标识，不转码、画面音频一个字节不动，用 1 个激活码", "scrub", "limit local", ""},
            {"基岩 → Java 转换", "用 Chunker 把基岩世界转成 Java 版", "tools", "free local", "convert"},
            {"基岩材质包转 Java", "把 .mcpack 材质包离线转成 Java 版：贴图无损、动画拆帧、语言与声音，附降级清单", "packconv", "free local", ""},
            {"Java 材质包转基岩", "把 Java 材质包离线转成基岩 .mcpack：改名表反查、动画帧重排成竖条、lang 与声音反写，附降级清单", "packj2b", "free local", ""},
            {"3D 预览", "离线看 OBJ 和 MTL，不用装软件", "preview", "free local", ""},
            {"图片转模型", "像素图转 bbmodel，用 1 个激活码", "png2model", "limit local", ""},
            {"bb 模型转 OBJ", "离线跑 Blockbench 内核", "bb2obj", "free local", ""},
            {"粒子编辑器", "Snowstorm 离线版，界面已经汉化", "particle", "free local", ""},
            {"Blockbench", "方块建模，网页版打包在本地", "blockbench", "free local", ""},
            {"AI 动画助手", "说一句要什么，出动画、粒子、Molang", "ai", "free local", ""},
            {"离线种子地图", "查群系、结构、要塞、出生点、史莱姆区块，不用联网", "seedmap", "free local", ""},
            {"像素地图存档", "搜像素地图站的玩家地图，能下的地图包直接存进「下载」", "pixelmap", "free local", ""},
            {"颜色代码", "Java 和基岩的颜色、格式代码，点了就复制", "colorcode", "free local", ""},
            {"GIF 工具", "视频转 GIF、拆帧、合成、旋转、材质动画贴图", "gif", "free local", ""},
            {"风景增强", "一张风景照自动精修：多尺度分析算出逐块修改计划，去雾、提亮、提饱和、清晰度，全程离线", "landscape", "free local", ""},
            {"串词工具箱", "复制自动换谐音字、一键出表情包，可导入 txt 串词 / 谐音表，用 1 个激活码", "phrase", "limit local", ""},
            {"寂零快跑", "导出等进度时玩的小游戏", "game", "free local", ""},
            {"P3D 工程工具", "Prisma3D 工程包：识别版本、修头、解包、数据报告，用 1 个激活码", "p3d", "limit local", ""},
    };

    /** 网页工具分类：{分类 key, 中文名, 一行说明}。 */
    private static final String[][] WEB_CATS = {
            {"w_sinc", "Sincerity 工具箱", "自制工具站 + 素材、字体、音效、模型、壁纸、生成器"},
            {"w_stock", "视频 / 图片素材", "免版权视频、照片、音效，剪短片时补素材"},
            {"w_skin", "皮肤 / 头像", "皮肤编辑器、皮肤库、头像渲染"},
            {"w_model", "模型 / 粒子 / 3D", "方块建模、粒子、原版资源浏览"},
            {"w_tex", "材质 / 资源包", "材质包生成、资源与模组平台"},
            {"w_build", "建筑 / 规划", "种子地图、配色、圆和旗帜、标题字"},
            {"w_cmd", "命令 / 数据", "命令生成、JSON、ID 速查、维基"},
            {"w_art", "像素 / 美术", "地图画、图片转方块、像素编辑器"},
            {"w_music", "音乐 / 音符盒", "音符盒、红石音乐、MIDI 素材"},
            {"w_comm", "社区 / 下载", "中文社区、资源站、官网"},
            {"w_serv", "服务器 / 实用", "状态查询、服务器列表、插件站"},
    };

    /**
     * 网页工具：{标题, 说明, 链接, 标签, 分类}。新增一家 = 加一行，
     * 自动进「全部」「免费」两页并落到对应分类下。
     *
     * <p>国内链接已实测：手机浏览器直接打开，不需要梯子；说明里带「（外网）」的是
     * 境外站点，个别需要自备网络环境。个别站点对自动化抓取返回 403，但浏览器（含手机版）正常可开。</p>
     */
    private static final String[][] WEB_TOOLS = {
            // ── Sincerity 工具箱 / 素材站 ────────────────────────────────
            {"Sincerity 工具箱", "网站 sincerity：下界坐标、刻换算、种子查找、皮肤渲染等 11 个在线工具", "http://110.42.53.159:4321/", "free web", "w_sinc"},
            {"MC 图标素材库", "图标与素材站，做包装和红石显示板能用", "https://mcicons.ccleaf.com", "free web", "w_sinc"},
            {"MC 立体字生成", "在线做 3D 立体标题字，封面和 LOGO 好使", "https://3dt.easecation.net", "free web", "w_sinc"},
            {"MC 音效库", "原版音效在线试听与下载，做短片配音用", "https://o.xbottle.top/mcsounds", "free web", "w_sinc"},
            {"MC 短片资源 · 模型", "语雀分享页：短片用的模型与素材", "https://www.yuque.com/mhmzh/shares/files", "free web", "w_sinc"},
            {"手绘抖动生成", "像素手绘的抖动 / 混色效果工具（外网）", "https://internet-janitor.itch.io/", "free web", "w_sinc"},
            {"建筑调色（blockcolors）", "按方块真实颜色挑建筑配色，做建筑和材质参考", "https://blockcolors.app", "free web", "w_sinc"},
            {"咕噜素材网", "字体与设计素材下载，做封面和头像用", "https://www.gulusc.com", "free web", "w_sinc"},
            {"自定义纸模", "像素模型出图纸，打印就能折（外网）", "https://www.pixelpapercraft.com/", "free web", "w_sinc"},
            {"anime.js 动效", "网页动效引擎，看演示学动画节奏（外网）", "https://animejs.com", "free web", "w_sinc"},
            {"文字动画生成器", "Space Type Generator：打字与文字动效（外网）", "https://spacetypegenerator.com", "free web", "w_sinc"},
            {"Sketchfab 3D 模型", "海量 3D / AR 模型，可下载（外网）", "https://sketchfab.com", "free web", "w_sinc"},
            {"Sketchfab 手绘模型", "只筛可下载的手绘风模型，按点赞排（外网）", "https://sketchfab.com/search?features=downloadable&q=tag%3Ahandpainted&sort_by=-likeCount&type=models", "free web", "w_sinc"},
            {"MC 壁纸站", "minepix：Minecraft 壁纸下载", "https://www.minepix.app", "free web", "w_sinc"},
            {"MC 头像生成器", "mccag：在线生成 MC 风格头像", "https://mccag.cn", "free web", "w_sinc"},
            {"真实城市地图生成", "arnismc：真实地图一键生成城市存档", "https://arnismc.com/", "free web", "w_sinc"},

            // ── 视频 / 图片素材（剪短片时补素材）──────────────────────────
            {"Pixabay", "免版权图库 + 视频 + 音效，什么都能兜一点（外网）", "https://pixabay.com/", "free web", "w_stock"},
            {"Pexels", "免版权高清视频和照片，下载不用注册（外网）", "https://www.pexels.com/", "free web", "w_stock"},
            {"视频 520 · 模板", "国内视频素材站：短视频模板、背景视频", "https://shipin520.com/shipin-mb/", "free web", "w_stock"},
            {"Dareful", "4K / HD 免版权实拍素材，可商用（外网）", "https://dareful.com/", "free web", "w_stock"},
            {"Videezy", "视频素材加 AE 模板，分免费和 Pro（外网）", "https://www.videezy.com/", "free web", "w_stock"},
            {"Coverr", "实拍空镜视频，片头转场当背景好用（外网）", "https://coverr.co/", "free web", "w_stock"},
            {"爱给网", "国内综合素材：音效、配乐、视频、3D、图片", "https://aigei.com/", "free web", "w_stock"},
            {"光厂（VJ 网）", "国内视频素材站：片头、AE 模板、背景视频，部分要付费", "https://guangchang.com/", "free web", "w_stock"},

            // ── 皮肤 / 头像 ─────────────────────────────────────────────
            {"Mineskin.org", "皮肤上传，拿到链接能直接贴进启动器", "https://mineskin.org/", "free web", "w_skin"},
            {"Laby.net", "查玩家皮肤、披风和历史名字", "https://laby.net/", "free web", "w_skin"},
            {"MinecraftSkins.net", "另一家皮肤库，可以直接下 PNG", "https://www.minecraftskins.net/", "free web", "w_skin"},
            {"Mineskin.pro", "3D 皮肤编辑器，能调光照，界面是中文", "https://mineskin.pro/", "free web", "w_skin"},
            {"TDoGMC Studio", "国内皮肤编辑器，工程能存云端", "https://studio.tdogmc.cn/", "free web", "w_skin"},
            {"McFun 皮肤编辑器", "3D 上涂色，也能切到 2D 展开图改", "https://www.mcshuo.com/tools/skin-editor", "free web", "w_skin"},
            {"LittleSkin 皮肤站", "国内皮肤站，能存自己的皮肤", "https://littleskin.cn/", "free web", "w_skin"},
            {"MCSkinSearch", "搜皮肤，下下来就是 64×64 的 PNG", "https://mcskinsearch.com/", "free web", "w_skin"},
            {"SkinMC 皮肤站", "在皮肤库里翻别人的皮肤", "https://skinmc.net/", "free web", "w_skin"},
            {"MinecraftSkins 编辑器", "SkinDex 皮肤库，带在线编辑器", "https://www.minecraftskins.com/skin-editor/", "free web", "w_skin"},
            {"MCTools.gg 皮肤", "皮肤、披风、旗帜都能画", "https://mctools.gg/skin-editor", "free web", "w_skin"},
            {"mc-tools.net 皮肤", "直接在 3D 模型上涂色", "https://mc-tools.net/skin-editor", "free web", "w_skin"},
            {"Mojavatar 3D 头像", "把皮肤做成 3D 头像", "https://nogard.dev/tools/mojavatar-maker", "free web", "w_skin"},
            {"Q 版皮肤生成器", "迷你 Q 版 2D 皮肤", "https://nogard.dev/tools/minecraft-chibi-skin-maker", "free web", "w_skin"},
            {"NameMC", "查皮肤和披风", "https://namemc.com", "free web", "w_skin"},
            {"MC-Heads 头像", "给个头像图片链接", "https://mc-heads.net", "free web", "w_skin"},
            {"Crafatar 头像", "头像渲染，能指定尺寸和叠加层", "https://crafatar.com", "free web", "w_skin"},
            {"Minotar 头像", "一行网址就是头像", "https://minotar.net", "free web", "w_skin"},
            {"Planet Minecraft 皮肤", "老牌皮肤编辑器，网页直接用", "https://www.planetminecraft.com/skin-editor/", "free web", "w_skin"},

            // ── 模型 / 粒子 / 3D ────────────────────────────────────────
            {"Blockbench 官网", "桌面版下载，做模型材质的正主", "https://blockbench.net/", "free web", "w_model"},
            {"Blockbench 在线版", "官方网页版，方块建模和动画", "https://web.blockbench.net", "free web", "w_model"},
            {"Blockbench 中文站", "教程、下载、插件说明", "https://www.blockbench.com.cn/", "free web", "w_model"},
            {"Snowstorm 粒子", "官方网页版粒子编辑器", "https://snowstorm.app", "free web", "w_model"},
            {"MC 资源浏览器", "翻原版贴图、模型、音效，能下载", "https://mcasset.cloud", "free web", "w_model"},
            {"MCProfiles 工具集", "3D 皮肤、渲染、旗帜、命令生成", "https://mcprofiles.me/tools", "free web", "w_model"},
            {"MCTools.gg", "整套在线工具，皮肤旗帜资源包都有", "https://mctools.gg/", "free web", "w_model"},

            // ── 材质 / 资源包 ──────────────────────────────────────────
            {"Fabric 官网", "装模组用的加载器，下载和文档都在这", "https://fabricmc.net/", "free web", "w_tex"},
            {"NeoForge 官网", "1.20.2 之后主流的新加载器", "https://neoforged.net/", "free web", "w_tex"},
            {"Quilt 官网", "Fabric 的衍生加载器，多数 Fabric 模组能直接用", "https://quiltmc.org/", "free web", "w_tex"},
            {"Iris 光影", "光影加载器，配 Sodium 一起用", "https://irisshaders.dev/", "free web", "w_tex"},
            {"Modrinth 光影", "光影包下载，按版本和加载器筛", "https://modrinth.com/shaders", "free web", "w_tex"},
            {"材质工坊 mcsmi", "AI 生成材质包，支持 1.8 到 1.21", "https://mcsmi.com/", "free web", "w_tex"},
            {"CreateTextures", "在线做材质包、皮肤、图腾", "https://createtextures.com/", "free web", "w_tex"},
            {"Vanilla Tweaks", "勾选式生成原版优化资源包和数据包", "https://vanillatweaks.net", "free web", "w_tex"},
            {"MC Toolbox", "130 多个工具，命令、合成、皮肤、包编辑", "https://mctoolbox.net/zh-cn", "free web", "w_tex"},
            {"MC ToolHub", "65 个工具，命令、NBT、结构、资源包", "https://mctoolhub.com/", "free web", "w_tex"},
            {"Modrinth", "模组、资源包、光影、插件", "https://modrinth.com", "free web", "w_tex"},
            {"9Minecraft", "材质、地图、模组下载", "https://www.9minecraft.net/", "free web", "w_tex"},
            {"MCPEDL", "基岩版模组、地图、材质", "https://mcpedl.com", "free web", "w_tex"},
            {"CurseForge", "模组、材质、地图，量最大", "https://www.curseforge.com/minecraft", "free web", "w_tex"},

            // ── 建筑 / 规划 ────────────────────────────────────────────
            {"MCSeedMap", "种子地图，基岩版也能对得上", "https://mcseedmap.net/", "free web", "w_build"},
            {"SeedMap.org", "另一个种子地图，标记更全", "https://seedmap.org/", "free web", "w_build"},
            {"Chunkbase 种子地图", "查群系、结构、史莱姆区块", "https://www.chunkbase.com/apps/seed-map", "free web", "w_build"},
            {"方块配色板", "按方块实际颜色挑建筑配色", "https://blockcolors.app", "free web", "w_build"},
            {"BlockPalettes", "看别人的建筑配色，直接照着配", "https://www.blockpalettes.com/", "free web", "w_build"},
            {"像素圆生成器", "像素圆、椭圆、球体的建造参考", "https://donatstudios.com/PixelCircleGenerator", "free web", "w_build"},
            {"旗帜设计器", "在线画旗帜图案", "https://www.needcoolshoes.com/banner", "free web", "w_build"},
            {"TextCraft 标题字", "做 Minecraft 风格的标题和 LOGO", "https://textcraft.net/", "free web", "w_build"},
            {"Minecraft Tools", "附魔、药水、燃料、信标这些计算器", "https://minecraft.tools", "free web", "w_build"},

            // ── 命令 / 数据 ────────────────────────────────────────────
            {"Misode 工具集", "战利品表、进度、世界生成的数据包生成器", "https://misode.github.io/", "free web", "w_cmd"},
            {"bedrock.dev", "基岩版文档，命令和组件都在这", "https://bedrock.dev/", "free web", "w_cmd"},
            {"基岩版附加包教程", "行为包、资源包怎么写，例子能直接抄", "https://wiki.bedrock.dev/", "free web", "w_cmd"},
            {"Modrinth 数据包", "数据包下载，不装模组也能换玩法", "https://modrinth.com/datapacks", "free web", "w_cmd"},
            {"MCStacker 命令生成", "give、summon、setblock 图形化生成", "https://mcstacker.net", "free web", "w_cmd"},
            {"Minecraft JSON 生成", "模型、方块、配方的 JSON 生成器", "https://minecraftjson.com/", "free web", "w_cmd"},
            {"Item IDs 速查", "方块和物品 ID、配方查询", "https://minecraftitemids.com", "free web", "w_cmd"},
            {"MC 百科", "中文资料最全的模组和方块百科", "https://www.mcmod.cn", "free web", "w_cmd"},
            {"MC 百科工具", "计算器、合成表这类小工具", "https://www.mcmod.cn/tools/", "free web", "w_cmd"},
            {"中文 Minecraft Wiki", "方块、机制、教程都在这", "https://zh.minecraft.wiki", "free web", "w_cmd"},
            {"DigMinecraft", "图文教程，配合成和附魔工具", "https://www.digminecraft.com/", "free web", "w_cmd"},
            {"红石电路模拟", "在线搭逻辑电路（Falstad）", "https://www.falstad.com/circuit/", "free web", "w_cmd"},
            {"中文指令生成器", "give、tp、物品，图形化生成", "https://www.guoping123.com/wdsjzl/wap", "free web", "w_cmd"},
            {"中文指令大全", "指令表，每条都有说明", "https://www.minecraftzw.com/31270.html", "free web", "w_cmd"},
            {"中文 Wiki 命令页", "每个命令的参数和用法", "https://zh.minecraft.wiki/w/%E5%91%BD%E4%BB%A4", "free web", "w_cmd"},
            {"中文 Wiki 命令方块", "命令方块怎么用，有教程", "https://zh.minecraft.wiki/w/%E5%91%BD%E4%BB%A4%E6%96%B9%E5%9D%97", "free web", "w_cmd"},
            {"基岩版官方命令文档", "微软官方，基岩版全部命令", "https://learn.microsoft.com/en-us/minecraft/creator/commands/commands", "free web", "w_cmd"},
            {"国际 Wiki 命令页", "英文索引，能查版本差异", "https://minecraft.wiki/w/Commands", "free web", "w_cmd"},
            {"颜色代码表", "Java 和基岩的颜色、格式代码", "https://minecraftitemids.com/color-codes", "free web", "w_cmd"},
            {"颜色代码生成", "选颜色就出代码，能预览", "https://minecraft.tools/en/color-code.php", "free web", "w_cmd"},
            {"give 生成器", "生成 /give，能带 NBT", "https://www.gamergeeks.net/apps/minecraft/give-command-generator", "free web", "w_cmd"},

            // ── 像素 / 美术 ────────────────────────────────────────────
            {"Piskel", "在线像素画，画材质和皮肤都行", "https://piskelapp.com/", "free web", "w_art"},
            {"图片转像素画", "传图，出像素画和拼豆图纸", "https://i2tools.com/", "free web", "w_art"},
            {"我嘞个豆", "传图转像素画，做地图画或贴图参考", "https://www.ohmybead.cn/", "free web", "w_art"},
            {"Lospec 配色板", "像素画配色板，能直接取色", "https://lospec.com/palette-list", "free web", "w_art"},
            {"Pixilart", "在线画像素画，贴图图标都行", "https://www.pixilart.com", "free web", "w_art"},
            {"MC 像素画", "像素画、地图画、3D 雕塑、红石音乐", "https://www.mcpixelart.com/", "free web", "w_art"},
            {"MinecraftArt", "图片转方块，出地图画和原理图", "https://www.minecraftart.net/", "free web", "w_art"},
            {"MapartCraft", "地图画生成，出原理图和 map.dat", "https://rebane2001.com/mapartcraft/", "free web", "w_art"},

            // ── 社区 / 下载 ────────────────────────────────────────────
            {"苦力怕论坛", "国内社区，基岩版资源和教程", "https://klpbbs.com", "free web", "w_comm"},
            {"MineBBS", "国内社区，基岩版模组和地图", "https://www.minebbs.com", "free web", "w_comm"},
            {"我的世界中文网", "资讯、教程、工具合集", "https://www.mczfw.com/", "free web", "w_comm"},
            {"网易我的世界", "国内官网，资源和公告", "https://mc.163.com", "free web", "w_comm"},
            {"Minecraft 官网", "官方中文站", "https://www.minecraft.net/zh-hans", "free web", "w_comm"},
            {"OptiFine 官网", "Java 版优化和光影前置", "https://optifine.net/", "free web", "w_comm"},
            {"McFun 社区", "社区，皮肤、教程、工具", "https://www.mcshuo.com/", "free web", "w_comm"},
            {"MinecraftMaps", "建筑和冒险地图下载", "https://www.minecraftmaps.com/", "free web", "w_comm"},

            // ── 音乐 / 音符盒 ──────────────────────────────────────────
            {"Note Block Studio", "音符盒音乐编辑器，认 .nbs", "https://noteblock.studio/", "free web", "w_music"},
            {"OpenNBS 官网", "开源音符盒播放器", "https://opennbs.org/", "free web", "w_music"},
            {"Online Sequencer", "在线编曲，导 MIDI 做红石音乐", "https://onlinesequencer.net/", "free web", "w_music"},
            {"MidiShow MIDI", "中文 MIDI 下载站，红石音乐素材", "https://www.midishow.com/", "free web", "w_music"},

            // ── 服务器 / 实用 ──────────────────────────────────────────
            {"PaperMC", "Paper 服务端下载，联机开服常用它", "https://papermc.io/", "free web", "w_serv"},
            {"GeyserMC", "让基岩版连上 Java 服，互通插件", "https://geysermc.org/", "free web", "w_serv"},
            {"ViaVersion", "让高版本客户端进低版本服务器", "https://viaversion.com/", "free web", "w_serv"},
            {"Server.pro", "免费开服，网页里点几下就起来", "https://server.pro/", "free web", "w_serv"},
            {"MinecraftServers.org", "服务器列表，按版本和玩法筛", "https://minecraftservers.org/", "free web", "w_serv"},
            {"mclo.gs", "贴日志出诊断，报错看不懂就丢这", "https://mclo.gs/", "free web", "w_serv"},
            {"mcsrvstat 状态", "输入地址查服务器在线状态和人数", "https://mcsrvstat.us", "free web", "w_serv"},
            {"Minecraft-MP", "服务器列表，找服和开服信息", "https://minecraft-mp.com", "free web", "w_serv"},
            {"Hangar 插件站", "PaperMC 官方插件站", "https://hangar.papermc.io/", "free web", "w_serv"},
            {"mcstatus.io", "Java 和基岩服务器状态", "https://mcstatus.io/", "free web", "w_serv"},
            {"MC-Status", "中文的服务器 Ping 查询", "https://www.mc-status.com/", "free web", "w_serv"},
            {"MineChecker", "状态、在线人数、延迟", "https://minechecker.com/", "free web", "w_serv"},
            {"MinecraftStatus", "服务器状态，带历史记录", "https://minecraftserverstatus.com/", "free web", "w_serv"},
            {"MCStatus.org", "服务器玩家数曲线", "https://mcstatus.org/", "free web", "w_serv"},
            {"Aternos 免费开服", "免费开服面板，手机能管", "https://aternos.org/", "free web", "w_serv"},
            {"Minehut 免费开服", "免费的 Java 服务器", "https://minehut.com/", "free web", "w_serv"},
            {"exaroton 按小时开服", "按小时计费，手机管理", "https://exaroton.com/", "free web", "w_serv"},
            {"SpigotMC 插件站", "服务端插件站（浏览器可开）", "https://www.spigotmc.org/", "free web", "w_serv"},
    };

    /** 卡片索引：用于搜索 / 分类筛选（卡片 → 行 / 网格 / 小节标题）。 */
    private static final class CardRef {
        final View card;
        final LinearLayout grid;
        final View header;
        final String haystack;
        final String cat;

        CardRef(View card, LinearLayout grid, View header, String haystack, String cat) {
            this.card = card;
            this.grid = grid;
            this.header = header;
            this.haystack = haystack;
            this.cat = cat;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        sHome = new java.lang.ref.WeakReference<>(this);
        // 记下"这次建列表用的是哪一份云端配置"，onResume 里对照版本号+生效次数，不一致就重建
        builtWithCloudVersion = cloudStamp();
        WatermarkView.attach(this);
        ActivationStore.flushPending(this);   // 补发上次没发完的 confirm / release（幂等）
        AvatarLoader.init(getCacheDir());     // 社区头像的磁盘缓存目录（cacheDir/avatar）
        // 云端功能目录：先用上次有效缓存立刻生效，再后台拉 features.txt 刷新
        com.mineways.cloud.CloudCatalog.init(this);
        // 公告必须排在云控之后：notice_off / notice_ids 这些开关要读得到配置，
        // 否则首次启动时 gate 还是空的，云端配了「停发」也拦不住。
        AnnouncementCenter.checkAndShow(this);  // 仓库根目录 ovo.txt 公告：命中条件就弹，没有就算了
        // 订阅进度；顺便把当前状态画出来 —— 后台那一趟可能早就在跑了
        com.mineways.cloud.CloudCatalog.setProgressListener(this::renderCloudProgress);
        renderCloudProgress(com.mineways.cloud.CloudCatalog.progress());
        // 启动不再弹任何问答窗：登录改成「关于」页手动登录，白名单只认登录账号
        // 起始目的地由 NavHost 自己落位，栏状态在目的地回调里同步
    }

    /** 建列表时用的云端版本；回到前台时和当前生效版本比，不一样就重建界面。 */
    private String builtWithCloudVersion = "";

    /**
     * 这次界面是照着哪一份配置建的：<b>版本号 + 生效次数</b>。只用版本号不够 —— 同一版本号
     * 被重新下发（内容改了、版本没 bump）时比不出来，就会停在旧界面。
     */
    private String cloudStamp() {
        return com.mineways.cloud.CloudCatalog.appliedVersion()
                + "#" + com.mineways.cloud.CloudCatalog.appliedRevision();
    }

    /*
     * 后台拉取经常赶在 MainActivity 还没 resume 时完成，那种时候 CloudCatalog 的
     * recreate() 拿不到 Activity，界面就会停在旧档位（真机现象：提示说"生效 17 条"、
     * 主界面没变化）。所以界面自己每次回前台对照一次，担起这个责任。
     */
    @Override
    protected void onResume() {
        super.onResume();
        // AI 的放行人群/环境跟登录态有关：回前台重算一次（白名单可能刚登录/过期）
        com.mineways.AiAccess.refresh(this);
        String now = cloudStamp();
        if (!now.equals(builtWithCloudVersion)) {
            builtWithCloudVersion = now;
            HomeIndex.invalidate();
            recreate();
            return;
        }
        // 回前台：下载线程一直在跑，这里只是把它的当前状态接回界面
        renderCloudProgress(com.mineways.cloud.CloudCatalog.progress());
        // 进程被系统回收过的话那趟真的死了，回前台补一次（拿到过配置就不折腾）
        com.mineways.cloud.CloudCatalog.resumeIfNeeded(this);
    }

    /**
     * AI 用 {@code pick} 工具让用户自己选文件时，选择器是从这个 Activity 发出去的，
     * 结果也必须由它转交 —— 对话面板只是叠在界面上的一层 View，收不到 onActivityResult。
     */
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == com.mineways.ui.MwAi.REQ_PICK && com.mineways.AiOps.waitingForPick()) {
            com.mineways.ui.MwAi.handlePickResult(this, res, data);
        }
    }

    @Override
    protected void onDestroy() {        // 进度订阅必须摘掉：静态字段里留着一个 Activity 引用就是内存泄漏
        com.mineways.cloud.CloudCatalog.setProgressListener(null);
        if (sHome != null && sHome.get() == this) {
            sHome = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 全屏 AI 开着时，返回键先收面板 —— 否则会直接切页/退出，用户会以为"卡住了"
        if (com.mineways.ui.MwAiBus.open.getValue()) {
            com.mineways.ui.MwAiBus.open.setValue(false);
            return;
        }
        if (currentTab != 0) {
            selectTab(0, true);
            return;
        }
        super.onBackPressed();
    }

    /**
     * 隐藏/恢复底部玻璃导航栏。
     *
     * <p>AI 是全屏对话页，而 {@code glassNav} 是后加进 FrameLayout 的（见 buildRoot），
     * 会压在全屏输入条上 —— 必须让它让位。显隐都做位移+淡出，避免硬切。
     */
    private boolean navHidden = false;

    private void setNavHidden(boolean hidden) {
        if (glassNav == null || navHidden == hidden) {
            return;
        }
        navHidden = hidden;
        if (hidden) {
            float dy = (glassNav.getHeight() > 0 ? glassNav.getHeight() : dp(72)) * 0.7f;
            glassNav.animate().alpha(0f).translationY(dy).setDuration(180)
                    .withEndAction(() -> {
                        if (glassNav != null && navHidden) {
                            glassNav.setVisibility(View.GONE);
                        }
                    }).start();
        } else {
            glassNav.setVisibility(View.VISIBLE);
            glassNav.setAlpha(0f);
            glassNav.setTranslationY(dp(72) * 0.7f);
            glassNav.animate().alpha(1f).translationY(0f).setDuration(200).start();
        }
    }

    // ---------------------------------------------------------------- 骨架

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 内容 + 悬浮玻璃导航栏叠在同一层（玄戒也是 MainScreen = Box(fillMaxSize) + 悬浮栏）：
        // 导航栏压住内容底部，玻璃才有得透；页面靠自身 paddingBottom 让位。
        // 内容区走安卓官方的 Navigation：NavHost 承载 5 个 Fragment 目的地。
        // content 必须自己有底：玻璃导航栏每帧 draw 快照的就是这块视图。它没有底时，页面为
        // 导航栏让位的那条底部 padding 带会被采成 alpha=0，玻璃画出来就是带硬边的死黑
        // （真机反馈的「有黑边 / 不通透」）。色值取暖黑 #0E0F11，和页面渐变的最底色同一支，
        // 栏与屏才是同一个色温。
        FrameLayout content = new FrameLayout(this);
        // 与页面场景最底色同一支：万一有 1px 让位带露出，也不会是死黑
        content.setBackgroundColor(0xFF07080D);
        FragmentContainerView host = new FragmentContainerView(this);
        host.setId(R.id.nav_host);          // 固定 id：进程恢复时能找回原来的 NavHost
        content.addView(host, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 先把玻璃栏建好，NavHost 落位时目的地回调就能同步它
        glassNav = new LiquidGlassNav(this);
        // 背板源必须是 NavHost，不是 content：content 里还装着导航栏自己，
        // 把父容器整个画进快照 = 玻璃把自己的上一帧又采一遍，边缘会发暗、发死。
        glassNav.setBackdropSource(host);
        // ui_accent 也要管到导航栏：选中态用它，未选中仍是 60% 白
        glassNav.setColors(com.mineways.ui.MwViews.style(this).accent, DIM2);
        // 标签由玻璃栏自己用 Compose 画（图标 + 文字）——只有画在 Compose 层里，
        // 滑块的玻璃才能把图标文字录进背景，拖动时边缘扫过它们才会折射、模糊
        glassNav.setTabs(TAB_ICONS, TAB_TITLES);
        glassNav.setSelected(currentTab, false);
        // 手指拖动高亮块松手 → 切页（玄戒 onDragStopped → onTabSelected）
        glassNav.setOnTabSelectedListener(index -> selectTab(index, true));
        glassNav.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            int pad = v.getHeight() + dp(28);      // 栏高 + 一点余量，最后一行不会被胶囊压住
            if (pad != pageBottomPad) {
                pageBottomPad = pad;              // 胶囊悬浮：页面底距随时跟栏高走
                // Compose 页不能设 View padding（onMeasure 会扣掉，背景就画不到栏下面），
                // 改成写进 MwInsets，由页面自己作用在 LazyColumn 的 contentPadding 上。
                com.mineways.ui.MwInsets.bottomNavDp.setIntValue(
                        Math.round(pad / getResources().getDisplayMetrics().density));
                for (View p : pageViews) {
                    if (!(p instanceof androidx.compose.ui.platform.ComposeView)) {
                        p.setPadding(0, 0, 0, pad);
                    }
                }
            }
        });
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.gravity = Gravity.BOTTOM;
        content.addView(glassNav, nlp);

        // 官方 Navigation：NavHostFragment + nav_graph（每页一个 PageFragment，单实例 + 保状态）
        NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host);
        if (navHost == null) {
            navHost = NavHostFragment.create(R.navigation.nav_graph);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.nav_host, navHost, "navHost")
                    .setPrimaryNavigationFragment(navHost)
                    .commitNow();
        }
        navController = navHost.getNavController();
        navController.addOnDestinationChangedListener(this::onDestinationChanged);
        onDestinationChanged(navController, navController.getCurrentDestination(), null);

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // 云端配置的进度条放最上面：它比弹窗稳 —— 退后台、切页、重建都不会打断下载，
        // 回来时读一次状态就能接上显示（弹窗做不到这点，窗口所在的 Activity 一没就全丢了）
        root.addView(cloudBanner(), 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    // ---------------------------------------------------------------- 云端进度横幅

    private LinearLayout cloudBanner;
    private TextView cloudBannerText;

    private View cloudBanner() {
        if (cloudBanner != null) {
            return cloudBanner;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackgroundColor(0x14FFFFFF);
        row.setVisibility(View.GONE);

        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setTextColor(0xE6FFFFFF);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        cloudBannerText = t;
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleSmall);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(18), dp(18));
        blp.leftMargin = dp(10);
        row.addView(bar, blp);

        cloudBanner = row;
        return row;
    }

    /**
     * 把进度状态画到横幅上。回调可能在下载线程上，所以这里统一切回主线程；
     * 横幅只在 active 时出现，收尾后自动收起，不挡内容。
     */
    private void renderCloudProgress(com.mineways.cloud.CloudCatalog.Progress p) {
        runOnUiThread(() -> {
            View banner = cloudBanner();
            if (p == null || !p.active) {
                banner.setVisibility(View.GONE);
                return;
            }
            String text = p.text == null || p.text.length() == 0 ? "正在获取云端配置…" : p.text;
            if (p.total > 0 && p.done > 0 && p.done < p.total) {
                text = text + "　" + kbOf(p.done) + " / " + kbOf(p.total);
            }
            cloudBannerText.setText(text);
            banner.setVisibility(View.VISIBLE);
        });
    }

    private static String kbOf(int bytes) {
        return bytes < 1024 ? bytes + " B"
                : String.format(Locale.US, "%.1f KB", bytes / 1024.0);
    }


    // ---------------------------------------------------------------- 页面（全部按标签自动归类）

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(24));
        return col;
    }

    private ScrollView newPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        // 底部给悬浮的玻璃胶囊让位：真实值等导航栏量完高度后由 pageBottomPad 覆盖。
        // 兜底值给足，保证第一帧也压不到最后一行内容。
        sv.setPadding(0, 0, 0, pageBottomPad > 0 ? pageBottomPad : dp(150));
        return sv;
    }

    /**
     * 第 index 个页签。取数只有一条路：{@link HomeIndex} —— 页签按<b>现在生效的档位</b>收卡片，
     * 所以云端把「限制」改成「免费」之后它自己就会挪页；隐藏与停用也不可能被绕过，
     * 因为能进这张表的条目已经是门面算完的那一份（以前三条并行取数路径，正是「配置生效 17 条、
     * 界面毫无变化」的根源）。
     */
    private View pageTagged(int index) {
        ScrollView sv = newPage();
        LinearLayout col = column();
        List<HomeIndex.Item> pool = index == 0
                ? items() : HomeIndex.ofKind(kindForTab(index));

        col.addView(sectionTitle(TAB_TITLES[index], tabHint(index)));
        if (index <= 1) {
            col.addView(buildFilterBar());            // 搜索框 + 分类胶囊
        }
        if (pool.isEmpty()) {
            col.addView(emptyHint(index));
            sv.addView(col);
            return sv;
        }
        List<HomeIndex.Item> local = localOf(pool);
        addSection(col, index == 1 ? "开源移植" : "本地工具", local,
                index == 1 ? "Mineways、Chunker、three.js、Blockbench、Snowstorm"
                           : "不联网也能用", "local");
        for (HomeIndex.Cat c : HomeIndex.categories()) {
            List<HomeIndex.Item> inCat = inCategory(pool, c.id);
            addSection(col, c.name, inCat, c.desc + "，" + inCat.size() + " 家", c.id);
        }
        sv.addView(col);
        return sv;
    }

    /** 首页唯一的取数入口：内置登记表 + 云端新增条目，档位 / 显隐 / 文案都已经合并好。 */
    private List<HomeIndex.Item> items() {
        return HomeIndex.build(this, rows(), catRows(), cloudStamp());
    }

    /** 每个页签的副标题：说清这一页要用得满足什么条件。 */
    private String tabHint(int index) {
        if (index == 2) {
            return LoginAccess.whitelistUser(this)
                    ? "已解锁：这一页的功能全部开放，直接用"
                    : "这一页逐条由云端规定：有的要白名单账号，有的只要任一种登录 —— "
                    + "到「关于」页点「登录」选一种方式";
        }
        if (index == 3) {
            return "每个功能用 1 个激活码，在社区网页签到领取，粘到 App 里就能用";
        }
        return null;
    }

    /** 页签对应的档位（TAB_TAGS 里的 free / paid / limit）。 */
    private static int kindForTab(int index) {
        String tag = index >= 0 && index < TAB_TAGS.length ? TAB_TAGS[index] : "";
        if ("free".equals(tag)) {
            return KIND_FREE;
        }
        if ("paid".equals(tag)) {
            return KIND_PAID;
        }
        return KIND_LIMIT;
    }

    private static List<HomeIndex.Item> localOf(List<HomeIndex.Item> pool) {
        List<HomeIndex.Item> out = new ArrayList<>();
        for (HomeIndex.Item it : pool) {
            if ("local".equals(it.cat) || "cloud-local".equals(it.cat)) {
                out.add(it);
            }
        }
        return out;
    }

    private static List<HomeIndex.Item> inCategory(List<HomeIndex.Item> pool, String cat) {
        List<HomeIndex.Item> out = new ArrayList<>();
        for (HomeIndex.Item it : pool) {
            if (it.cat.equals(cat)) {
                out.add(it);
            }
        }
        return out;
    }

    private void addSection(LinearLayout col, String title, List<HomeIndex.Item> tools,
            String subtitle, String cat) {
        if (tools.isEmpty()) {
            return;
        }
        View header = sectionTitle(title, subtitle);
        col.addView(header);
        LinearLayout grid = grid();
        int n = 1;
        for (HomeIndex.Item t : tools) {
            addCard(grid, t, n++, header, cat);
        }
        padLastRow(grid);
        col.addView(grid);
    }

    /** 空页签给一句话，别让人以为页面坏了。 */
    private View emptyHint(int index) {
        TextView t = new TextView(this);
        t.setText(index == 2 ? "白名单功能正在整理，稍后再来。"
                : index == 3 ? "限制功能都已经解除了，这一页暂时是空的。"
                             : "这一页暂时没有内容。");
        t.setTextSize(13);
        t.setTextColor(DIM);
        t.setPadding(0, dp(12), 0, dp(12));
        return t;
    }

    private View pageAbout() {
        ScrollView sv = newPage();
        LinearLayout col = column();
        col.addView(sectionTitle("关于", "版本 " + versionName()));

        // 登录：三档身份（论坛 / Google / 微软）合成一个入口。
        // 旧版在这里并排两行 —— 社区授权登录、白名单账号登录，两个按钮各说各的登录态。
        LinearLayout loginRow = new LinearLayout(this);
        loginRow.setOrientation(LinearLayout.HORIZONTAL);
        loginRow.setGravity(Gravity.CENTER_VERTICAL);
        loginRow.setPadding(dp(16), dp(16), dp(16), dp(16));
        loginRow.setBackground(round(PANEL, 14));
        TextView loginState = new TextView(this);
        loginState.setTextSize(14);
        loginState.setTextColor(Identities.hasAny(this) ? ACCENT : 0xE6FFFFFF);
        loginState.setText(loginSummary());
        loginRow.addView(loginState, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView loginBtn = textButton(Identities.hasAny(this) ? "管理" : "登录", true);
        LinearLayout.LayoutParams loginLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loginLp.leftMargin = dp(12);
        loginBtn.setLayoutParams(loginLp);
        loginBtn.setOnClickListener(v -> LoginDialog.show(MainActivity.this, () -> {
            mwFx();
            recreate();
        }));
        loginRow.addView(loginBtn);
        col.addView(loginRow);

        addCloudStatus(col);

        View gap = new View(this);
        LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16));
        gap.setLayoutParams(gapLp);
        col.addView(gap);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(round(PANEL, 14));

        TextView body = new TextView(this);
        body.setText(getString(R.string.about_body));
        body.setTextSize(13);
        body.setTextColor(0xE6FFFFFF);
        body.setLineSpacing(dp(6), 1f);
        panel.addView(body);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(24), 0, 0);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView join = textButton(getString(R.string.about_join_btn), true);
        join.setOnClickListener(v -> openUrl(getString(R.string.about_join_url)));
        row.addView(join);

        TextView gh = textButton("GitHub", false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(16);
        gh.setLayoutParams(glp);
        gh.setOnClickListener(v -> openUrl(REPO));
        row.addView(gh);

        panel.addView(row);
        col.addView(panel);

        addCredits(col);
        addSiteCredits(col);
        addThanks(col);

        sv.addView(col);
        return sv;
    }

    /**
     * 本机留着的云端配置文件有多少、占多大。清理逻辑（只留生效链上的版本、换了编码就删旧的那份）
     * 光写进代码不算完，得在手机上看得见数，才知道老版本确实被清掉了。
     */
    private String cloudCacheLine() {
        java.io.File[] files = getFilesDir().listFiles();
        int count = 0;
        long bytes = 0L;
        boolean gz = false;
        if (files != null) {
            for (java.io.File f : files) {
                String name = f.getName();
                if (!name.matches("[0-9]+(\\.[0-9]+)*\\.(txt|txt\\.gz)")) {
                    continue;
                }
                count++;
                bytes += f.length();
                if (name.endsWith(".txt.gz")) {
                    gz = true;
                }
            }
        }
        if (count == 0) {
            return "本机没有缓存配置文件（每次现取）";
        }
        long freed = com.mineways.cloud.CloudCatalog.compactedBytes();
        long dropped = com.mineways.cloud.CloudCatalog.droppedBytes();
        String prune = com.mineways.cloud.CloudCatalog.pruneNote();
        return count + " 份 · " + (bytes / 1024L) + " KB · " + (gz ? "压缩存放" : "明文存放")
                + (freed > 0 ? "\n就地压缩腾出 " + (freed / 1024L) + " KB（内容未改一字）" : "")
                + (dropped > 0 ? "\n" + prune + "，腾出 " + (dropped / 1024L) + " KB"
                        : (prune.isEmpty() ? "" : "\n" + prune));
    }

    /**
     * 云端配置状态面板。它存在的理由是那条铁律：<b>没生效时必须能在手机上直接看到是哪一步、
     * 哪一行、什么原因</b>。以前配置"生效 17 条"却什么都看不出来，只能靠猜、靠捞 log。
     *
     * <p>三块内容：这份配置是谁（版本 / 链 / 作用域 / 来源 / 哈希）、动了什么（生效几条、
     * 隐藏停用改档各几条）、以及<b>每一条没执行的指令</b>是第几份文件第几行、为什么没执行。
     */
    private void addCloudStatus(LinearLayout col) {
        final com.mineways.cloud.FeatureGate gate = com.mineways.cloud.CloudCatalog.gate();
        final com.mineways.cloud.FeatureCatalog.Result raw = gate.raw();
        col.addView(sectionTitle("云端配置", null));

        LinearLayout panel = card();
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));

        StringBuilder sb = new StringBuilder();
        sb.append(com.mineways.cloud.CloudCatalog.status()).append('\n');
        // 压缩下发与旧版本清理都是"看不见的功能"，所以把数报出来：下了多少、还原多少、本机还占多少。
        if (com.mineways.cloud.CloudCatalog.wireBytes() > 0) {
            sb.append("本次下发：").append(com.mineways.cloud.CloudCatalog.transferNote()).append('\n');
        }
        sb.append("本机配置：").append(cloudCacheLine()).append('\n');
        List<String> chain = com.mineways.cloud.CloudCatalog.chainVersions();
        if (raw != null) {
            sb.append("版本 ").append(raw.fileVersion)
                    .append("　链上 ").append(chain.size()).append(" 份（")
                    .append(TextUtils.join(" → ", chain)).append("）\n")
                    .append("作用域：")
                    .append(com.mineways.cloud.FeatureCatalog.SCOPE_REPLACE.equals(raw.scope)
                            ? "只吃这一版" : "与上一版共同生效")
                    .append("　灰度桶：第 ").append(raw.bucket).append(" 号\n")
                    .append("生效 ").append(raw.applied).append(" 条　写错 ")
                    .append(raw.issues.size()).append(" 条　本机不适用 ")
                    .append(raw.notes.size()).append(" 条\n")
                    .append("隐藏 ").append(raw.hidden.size())
                    .append("　停用 ").append(raw.disabled.size())
                    .append("　改档 ").append(raw.tiers.size())
                    .append("　改文案 ").append(raw.labels.size() + raw.descs.size() + raw.icons.size() + raw.urls.size())
                    .append("　新增站点 ").append(raw.sites.size())
                    .append("　新增入口 ").append(raw.entries.size())
                    .append("　板块 ").append(raw.categories.size()).append(" 加 / ")
                    .append(raw.droppedCategories.size()).append(" 撤\n")
                    .append("下架站点 ").append(raw.droppedSites.size())
                    .append("　授权名单 +").append(raw.whitelistAdds.size())
                    .append(" −").append(raw.whitelistDrops.size())
                    .append("　开关 ").append(raw.flags.size()).append(" 个");
        } else {
            sb.append("当前没有生效的云端配置，首页按内置默认运行。");
        }

        TextView body = new TextView(this);
        body.setText(sb);
        body.setTextSize(12);
        body.setTextColor(0xE6FFFFFF);
        body.setLineSpacing(dp(4), 1f);
        body.setTextIsSelectable(true);
        panel.addView(body);

        // 写错的行与"本机本来就不该执行"的行分开列：前者要改配置，后者是正常现象
        if (raw != null && (!raw.issues.isEmpty() || !raw.notes.isEmpty())) {
            View line = new View(this);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            llp.setMargins(0, dp(12), 0, dp(12));
            panel.addView(line, llp);
            panel.addView(issueList(raw.issues, "这些行写错了，没有执行：", 0xFFE07B7B));
            panel.addView(issueList(raw.notes, "这些行在本机不适用（正常）：", DIM2));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, 0);

        TextView refresh = textButton("立即刷新", true);
        refresh.setOnClickListener(v -> {
            com.mineways.cloud.CloudCatalog.refresh(MainActivity.this);
            toast("正在拉取云端配置…");
        });
        row.addView(refresh);

        panel.addView(row);
        col.addView(panel);
    }

    /** 状态面板的一列问题（空列表返回一个不占位的 View，方便 add 时不用判空）。 */
    private View issueList(List<com.mineways.cloud.FeatureCatalog.Issue> list, String title, int color) {
        TextView t = new TextView(this);
        if (list.isEmpty()) {
            t.setVisibility(View.GONE);
            return t;
        }
        StringBuilder sb = new StringBuilder(title).append('\n');
        int shown = 0;
        for (com.mineways.cloud.FeatureCatalog.Issue it : list) {
            if (shown++ >= 20) {
                sb.append("…… 其余 ").append(list.size() - shown).append(" 条省略\n");
                break;
            }
            sb.append("第").append(it.file + 1).append("份 第").append(it.line)
                    .append("行：").append(it.reason).append('\n');
        }
        t.setText(sb);
        t.setTextSize(11);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- 鸣谢

    /** 内置的开源组件：{名称, 用在哪, 许可, 上游地址（空则不可点）}。与 THIRD_PARTY.md 同源维护。 */
    private static final String[][] CREDITS = {
            {"Mineways", "读存档 / 网格生成 / OBJ·MTL 导出内核（C++）", "见上游", "https://github.com/erich666/Mineways"},
            {"Chunker", "基岩版存档 → Java 版世界转换", "GPL-3.0", "https://github.com/hivemc/chunker"},
            {"Arnis v3.2.0", "真实世界地图生成内核（Rust，交叉编译成 libarnis.so）", "Apache-2.0", "https://github.com/louis-e/arnis"},
            {"OpenCut", "视频剪辑器（网页版整体内置，另加中文与手机横屏适配）", "MIT", "https://opencut.app"},
            {"Blockbench", "方块建模编辑器（内置网页版）", "MIT", "https://www.blockbench.net"},
            {"Snowstorm", "粒子编辑器（内置网页版）", "GPL-3.0-or-later", "https://github.com/JannisX11/snowstorm"},
            {"three.js r134", "模型预览渲染（含 OBJLoader / MTLLoader / OrbitControls）", "MIT", "https://threejs.org"},
            {"Mine-imator 2.x", "动画引擎与素材（libmineimator.so）", "上游未附许可", "https://www.mine-imator.com/"},
            {"Qt 5.15.2", "Mine-imator 的 GUI 运行时（动态链接）", "LGPL-3.0 / GPL-2.0+", "https://www.qt.io"},
            {"Assimp 6.x", "模型格式导入，编入 libfmtconv.so", "BSD-3-Clause", "https://assimp.org"},
            {"zstd 1.5.x", "压缩解压，编入 libfmtconv.so", "BSD-3-Clause", "https://github.com/facebook/zstd"},
            {"lodepng", "PNG 读写", "zlib", "https://lodev.org/lodepng/"},
            {"stb_image", "图片解码", "Public Domain / MIT", "https://github.com/nothings/stb"},
            {"region.cpp", "Minecraft region 文件读取（Ryan Hitchman, 2011）", "BSD-2-Clause", ""},
            {"mediabunny", "OpenCut 在浏览器内导出 MP4 / WebM", "MPL-2.0", "https://mediabunny.dev"},
            {"onnxruntime-web · transformers.js", "OpenCut 的 AI 能力（抠图等）", "MIT · Apache-2.0", "https://onnxruntime.ai/docs/tutorials/web"},
            {"Shizuku API", "系统级能力调用", "见上游", "https://shizuku.rikka.app"},
            {"AndroidX · Material Components", "界面基础库", "Apache-2.0", "https://developer.android.com/jetpack/androidx"},
            {"云间列车", "WebGL2 动画背景（原始 shader 作者 mdb）", "维护者已获授权", "https://github.com/Rice-dog/code-codex"},
    };

    private void addCredits(LinearLayout col) {
        col.addView(sectionTitle("开源项目鸣谢",
                "本应用把下面这些开源项目作为源码或静态资源内置，好让它离线可用。"
                        + "它们各自的权利归原作者，点击条目可打开上游。再分发（尤其商用）请逐项核对许可。"));
        LinearLayout panel = card();
        for (int i = 0; i < CREDITS.length; i++) {
            divider(panel, i);
            String[] c = CREDITS[i];
            panel.addView(creditRow(c[0], c[1], c[2], c[3], null));
        }
        col.addView(panel);
    }

    /** 收录网站清单折起在按钮后面：一百多家全列会把关于页拉得过长，点开时才构造这些行。 */
    private void addSiteCredits(LinearLayout col) {
        final int total = webSites().size();
        col.addView(sectionTitle("收录网站鸣谢",
                total + " 家站点被收进工具箱，内容、服务与许可均由对方提供，点击直接打开原站。"));

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);

        final TextView toggle = new TextView(this);
        toggle.setTextSize(13);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setTextColor(ACCENT);
        toggle.setGravity(Gravity.CENTER);
        toggle.setPadding(dp(16), dp(16), dp(16), dp(16));
        toggle.setBackground(round(PANEL, 14));
        final String collapsed = "展开收录网站 · " + total + " 家";
        toggle.setText(collapsed);
        toggle.setOnClickListener(v -> {
            boolean open = body.getVisibility() == View.VISIBLE;
            if (!open && body.getChildCount() == 0) {
                buildSiteCredits(body);
            }
            body.setVisibility(open ? View.GONE : View.VISIBLE);
            toggle.setText(open ? collapsed : "收起收录网站");
        });

        col.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        col.addView(body, blp);
    }

    /**
     * 关于页的收录清单也从同一份首页模型取。以前它是第三条独立遍历 {@code WEB_TOOLS} 的路径，
     * 云端下架过的站点在这里照样列出来 —— 现在不会再有这种不一致。
     */
    private void buildSiteCredits(LinearLayout col) {
        for (HomeIndex.Cat c : HomeIndex.categories()) {
            List<HomeIndex.Item> list = inCategory(items(), c.id);
            if (list.isEmpty()) {
                continue;
            }
            LinearLayout panel = card();
            int n = 0;
            for (HomeIndex.Item it : list) {
                divider(panel, n);
                panel.addView(creditRow(it.title, hostOf(urlOf(it)), null, urlOf(it), it.sub));
                n++;
            }
            col.addView(siteCatTitle(c.name, n));
            col.addView(panel);
        }
    }

    /** 网页类条目（内置的 + 云端新增的）。 */
    private List<HomeIndex.Item> webSites() {
        List<HomeIndex.Item> out = new ArrayList<>();
        for (HomeIndex.Item it : items()) {
            if ("web".equals(it.group) || "cloud".equals(it.group)) {
                out.add(it);
            }
        }
        return out;
    }

    /** 链接存在 Intent 的 extra 里，取出来只为显示域名与点击跳转。 */
    private static String urlOf(HomeIndex.Item it) {
        if (it.intent == null) {
            return "";
        }
        String u = it.intent.getStringExtra(WebToolActivity.EXTRA_URL);
        return u == null ? "" : u;
    }

    private void addThanks(LinearLayout col) {
        col.addView(sectionTitle("其它鸣谢", null));
        LinearLayout panel = card();
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setTextColor(0xE6FFFFFF);
        t.setLineSpacing(dp(5), 1f);
        t.setText("· 地图数据：© OpenStreetMap contributors（Overpass / OSM 瓦片）、Overture Maps、AWS Terrain Tiles"
                + " —— 「真实世界」生成的底图全部来自它们。\n"
                + "· 皮肤与头像接口：Mojang / 各皮肤站公开 API。\n"
                + "· 中文社区：MC 圈子里愿意把教程、模型、材质和踩坑记录公开分享的作者们。\n"
                + "· 你 —— 反馈 bug、提需求、帮忙验证真机效果的所有用户。\n\n"
                + "本项目不是官方产品，与 Mojang Studios、Microsoft、网易雷火均无隶属或背书关系。");
        panel.addView(t);
        col.addView(panel);
    }

    private LinearLayout card() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));
        panel.setBackground(round(PANEL, 14));
        return panel;
    }

    private void divider(LinearLayout panel, int index) {
        if (index == 0) {
            return;
        }
        View line = new View(this);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        line.setBackgroundColor(LINE);
        panel.addView(line);
    }

    private LinearLayout siteCatTitle(String name, int count) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(4), dp(18), dp(4), dp(8));
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        box.addView(t);
        TextView n = new TextView(this);
        n.setText("  " + count + " 家");
        n.setTextSize(12);
        n.setTextColor(DIM2);
        box.addView(n);
        return box;
    }

    /** 一行鸣谢：标题 + 右侧徽标（许可或域名）+ 说明；有 url 就可点。 */
    private View creditRow(String name, String sub, String badge, String url, String note) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(13);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(TEXT);
        head.addView(n, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (badge != null && badge.length() > 0) {
            TextView b = new TextView(this);
            b.setText(badge);
            b.setTextSize(10);
            b.setTextColor(ACCENT);
            head.addView(b);
        }
        row.addView(head);

        TextView s = new TextView(this);
        s.setText(note != null ? (sub + " —— " + note) : sub);
        s.setTextSize(11);
        s.setTextColor(DIM2);
        s.setPadding(0, dp(4), 0, 0);
        row.addView(s);

        if (url != null && url.length() > 0) {
            row.setOnClickListener(v -> openUrl(url));
        }
        return row;
    }

    /** 取站点主机名，用于鸣谢行的副标题。 */
    private static String hostOf(String url) {
        String s = url.startsWith("http") ? url.substring(url.indexOf("//") + 2) : url;
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : s;
    }

    // ---------------------------------------------------------------- 首页取数（判定在 HomeIndex 与门面里）

    /**
     * 内置登记表 → 首页模型要的原始行。这里<b>只搬数据、不做判断</b>：档位、显隐、文案覆盖、
     * 图标、Intent 全部由 {@link HomeIndex} 与云端门面决定，所以配置改动能一次贯通所有页面。
     */
    static List<HomeIndex.Row> rows() {
        List<HomeIndex.Row> out = new ArrayList<>();
        for (String[] r : LOCAL_TOOLS) {
            out.add(new HomeIndex.Row(r[0], r[1], r[2], r[3],
                    r.length > 4 ? r[4] : "", false, ""));
        }
        for (String[] r : WEB_TOOLS) {
            out.add(new HomeIndex.Row(r[0], r[1], "preview", r[3],
                    r.length > 4 ? r[4] : "", true, r[2]));
        }
        return out;
    }

    /** 内置板块（分类）。云端可以改名、可以整块下架、也可以加新板块。 */
    static List<HomeIndex.Cat> catRows() {
        List<HomeIndex.Cat> out = new ArrayList<>();
        for (String[] c : WEB_CATS) {
            out.add(new HomeIndex.Cat(c[0], c[1], c[2]));
        }
        return out;
    }

    // ---------------------------------------------------------------- 卡片

    private LinearLayout sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(0, dp(20), 0, dp(12));

        View bar = new View(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(2), dp(16));
        blp.topMargin = dp(3);
        bar.setLayoutParams(blp);
        bar.setBackground(round(ACCENT, 1));
        box.addView(bar);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        texts.setLayoutParams(tlp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.04f);
        t.setTextColor(TEXT);
        texts.addView(t);

        if (subtitle != null && subtitle.length() > 0) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(12);
            s.setTextColor(DIM2);
            s.setLineSpacing(dp(4), 1f);
            s.setPadding(0, dp(6), 0, 0);
            texts.addView(s);
        }
        box.addView(texts);
        return box;
    }

    private LinearLayout grid() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        return g;
    }

    private void addCard(LinearLayout grid, HomeIndex.Item tool, int number, View header, String cat) {
        View card = card(tool, number);
        LinearLayout row = null;
        if (grid.getChildCount() > 0) {
            View last = grid.getChildAt(grid.getChildCount() - 1);
            if (last instanceof LinearLayout && "row".equals(last.getTag())
                    && countCards((LinearLayout) last) < 2) {
                row = (LinearLayout) last;
            }
        }
        if (row == null) {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setTag("row");
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), dp(12));
        card.setLayoutParams(lp);
        row.addView(card);
        cardIndex.add(new CardRef(card, grid, header, tool.haystack(), cat));
    }

    /** 只数真卡片，占位空格子不算，保证稳定两列。 */
    private static int countCards(LinearLayout row) {
        int n = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            if ("card".equals(row.getChildAt(i).getTag())) {
                n++;
            }
        }
        return n;
    }

    private void padLastRow(LinearLayout grid) {
        if (grid.getChildCount() == 0) {
            return;
        }
        View last = grid.getChildAt(grid.getChildCount() - 1);
        if (last instanceof LinearLayout && "row".equals(last.getTag())
                && countCards((LinearLayout) last) == 1) {
            View ghost = new View(this);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, 1, 1f);
            glp.setMargins(dp(4), 0, dp(4), 0);
            ghost.setLayoutParams(glp);
            ((LinearLayout) last).addView(ghost);
        }
    }

    // ---------------------------------------------------------------- 搜索 / 分类筛选

    private final List<CardRef> cardIndex = new ArrayList<>();
    private final List<TextView> chipViews = new ArrayList<>();
    /** 与 chipViews 一一对应；null = 「全部」。 */
    private final List<String> chipCats = new ArrayList<>();
    private final List<android.widget.EditText> searchBoxes = new ArrayList<>();
    private String filterQuery = "";
    private String filterCat = null;
    private boolean syncingSearch = false;

    /** 搜索框 + 分类胶囊（「全部」「免费」两页各一份，状态自动联动）。 */
    private View buildFilterBar() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(2));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(new LiquidGlassDrawable(dp(22), 0, density()));   // 液态玻璃搜索框
        searchRow.setPadding(dp(14), dp(4), dp(10), dp(4));

        final android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("搜工具名、说明或者分类");
        search.setHintTextColor(DIM2);
        search.setTextColor(TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setPadding(0, dp(10), 0, dp(10));
        search.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (filterQuery.length() > 0) {
            search.setText(filterQuery);
        }
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (syncingSearch) {
                    return;
                }
                filterQuery = s == null ? "" : s.toString();
                syncSearchBoxes(search);
                applyFilter();
            }
        });
        searchRow.addView(search);
        searchBoxes.add(search);

        TextView clear = new TextView(this);
        clear.setText("清除");
        clear.setTextSize(12);
        clear.setTypeface(Typeface.DEFAULT_BOLD);
        clear.setTextColor(ACCENT);
        clear.setPadding(dp(10), dp(8), dp(2), dp(8));
        clear.setClickable(true);
        clear.setOnClickListener(v -> {
            filterQuery = "";
            syncSearchBoxes(null);
            applyFilter();
        });
        searchRow.addView(clear);
        box.addView(searchRow);

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(10), 0, dp(4));
        chips.addView(chip("全部", null));
        chips.addView(chip("本地工具", "local"));
        for (HomeIndex.Cat c : HomeIndex.categories()) {
            chips.addView(chip(c.name, c.id));
        }
        hs.addView(chips);
        box.addView(hs);
        styleChips();
        return box;
    }

    /** 一个分类小胶囊。 */
    private TextView chip(String label, final String cat) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            filterCat = cat;
            styleChips();
            applyFilter();
        });
        chipViews.add(t);
        chipCats.add(cat);
        return t;
    }

    private void styleChips() {
        for (int i = 0; i < chipViews.size(); i++) {
            String cat = chipCats.get(i);
            boolean on = cat == null ? filterCat == null : cat.equals(filterCat);
            TextView t = chipViews.get(i);
            // 液态玻璃筛选胶囊：选中的按强调色着色，未选中的是透明玻璃
            t.setBackground(new LiquidGlassDrawable(dp(20), on ? ACCENT : 0, density()));
            t.setTextColor(on ? 0xFF000000 : DIM);
        }
    }

    /** 两个页面的搜索框互相同步（避免递归，动一个就把另一个设成同样的值）。 */
    private void syncSearchBoxes(android.widget.EditText from) {
        syncingSearch = true;
        for (android.widget.EditText e : searchBoxes) {
            if (e != from && !filterQuery.equals(e.getText().toString())) {
                e.setText(filterQuery);
            }
        }
        syncingSearch = false;
    }

    /** 关键词 + 分类筛选：隐藏不匹配卡片，并把空了的小节（标题 + 网格）一起收起。 */
    private void applyFilter() {
        final String q = filterQuery == null ? "" : filterQuery.trim().toLowerCase(Locale.US);
        java.util.Set<LinearLayout> grids = new java.util.LinkedHashSet<>();
        java.util.Map<LinearLayout, View> headerOf = new java.util.LinkedHashMap<>();
        for (CardRef r : cardIndex) {
            boolean show = (q.length() == 0 || r.haystack.contains(q))
                    && (filterCat == null || filterCat.equals(r.cat));
            r.card.setVisibility(show ? View.VISIBLE : View.GONE);
            grids.add(r.grid);
            if (r.header != null) {
                headerOf.put(r.grid, r.header);
            }
        }
        for (LinearLayout g : grids) {
            boolean any = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                View row = g.getChildAt(i);
                if (!(row instanceof LinearLayout)) {
                    continue;
                }
                boolean rowAny = false;
                for (int j = 0; j < ((LinearLayout) row).getChildCount(); j++) {
                    View c = ((LinearLayout) row).getChildAt(j);
                    if ("card".equals(c.getTag()) && c.getVisibility() == View.VISIBLE) {
                        rowAny = true;
                        break;
                    }
                }
                row.setVisibility(rowAny ? View.VISIBLE : View.GONE);
                any = any || rowAny;
            }
            g.setVisibility(any ? View.VISIBLE : View.GONE);
            View h = headerOf.get(g);
            if (h != null) {
                h.setVisibility(any ? View.VISIBLE : View.GONE);
            }
        }
    }

    private View card(HomeIndex.Item tool, int number) {
        GradientDrawable bg = round(PANEL, 14);
        bg.setStroke(Math.max(1, dp(1) / 3), LINE);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x14FFFFFF), bg, null);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setMinimumHeight(dp(112));
        card.setTag("card");
        card.setBackground(ripple);
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        View bar = new View(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(2), dp(18)));
        bar.setBackground(round(tool.kind == KIND_LIMIT ? ACCENT : DIM2, 1));
        top.addView(bar);

        TextView t = new TextView(this);
        t.setText(tool.title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT);
        t.setMaxLines(2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        t.setLayoutParams(tlp);
        top.addView(t);

        // 角标：档位（白名单/限制）之外，云控只配了 gate= 的免费功能也要标出来 ——
        // 不标就是「点下去才告诉我要登录」，那是最差的一种提示。
        LoginAccess.Verdict lock = verdict(tool);
        if (tool.kind != KIND_FREE || lock.gated()) {
            TextView kind = new TextView(this);
            kind.setText(lock.allowed ? "已解锁"
                    : (lock.badge.length() > 0 ? lock.badge
                    : (tool.kind == KIND_PAID ? "白名单" : "限制")));
            kind.setTextSize(10);
            kind.setLetterSpacing(0.08f);
            kind.setTextColor(lock.allowed || tool.kind == KIND_PAID || lock.gated() ? ACCENT : DIM);
            kind.setPadding(dp(8), 0, dp(8), 0);
            top.addView(kind);
        }
        // 云端下发的、或者被云端停用的，卡片上直接写出来 —— 不让人猜"这功能哪来的 / 怎么点不动"
        if (tool.fromCloud || !tool.usable) {
            TextView tag = new TextView(this);
            tag.setText(!tool.usable ? "已停用" : "云端");
            tag.setTextSize(10);
            tag.setLetterSpacing(0.08f);
            tag.setTextColor(!tool.usable ? 0xFFE07B7B : ACCENT);
            tag.setPadding(dp(8), 0, 0, 0);
            top.addView(tag);
        }

        TextView num = new TextView(this);
        num.setText(String.format(Locale.US, "%02d", number));
        num.setTextSize(11);
        num.setTypeface(Typeface.MONOSPACE);
        num.setTextColor(DIM2);
        top.addView(num);
        card.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp2.topMargin = dp(16);
        bottom.setLayoutParams(blp2);

        ImageView ic = new ImageView(this);
        ic.setImageResource(tool.icon);
        ic.setColorFilter(DIM);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
        ilp.topMargin = dp(2);
        ic.setLayoutParams(ilp);
        bottom.addView(ic);

        TextView s = new TextView(this);
        s.setText(tool.sub);
        s.setTextSize(12);
        s.setTextColor(DIM);
        s.setLineSpacing(dp(4), 1f);
        s.setMaxLines(3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(8);
        s.setLayoutParams(slp);
        bottom.addView(s);
        card.addView(bottom);

        card.setOnClickListener(v -> launch(tool));
        return card;
    }

    /**
     * 点击放行。次序是算过的：<b>先问云端让不让用</b>（停用），再问要不要先确认（{@code mode=ask}），
     * 最后才谈激活码与白名单。以前限制分支在最前面 —— 一个已经被云端停用的限制功能会先扣掉用户
     * 1 个激活码，才告诉他打不开，这是会挨骂的那种顺序。
     */
    /**
     * AI 调用功能用：按名字找条目（精确键 → 去空格标题全等 → 包含匹配兜底），找不到返回 null。
     * {@code onlyWeb} 时只在云端收录的站点条目里找。
     */
    public HomeIndex.Item findTool(String name, boolean onlyWeb) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        HomeIndex.Item it = HomeIndex.find(name);
        if (it != null && (!onlyWeb || isSite(it))) {
            return it;
        }
        String want = com.mineways.cloud.FeatureCatalog.norm(name).replace(" ", "");
        if (want.isEmpty()) {
            return null;
        }
        HomeIndex.Item best = null;
        for (HomeIndex.Item c : HomeIndex.all()) {
            if (c == null || !c.visible || (onlyWeb && !isSite(c))) {
                continue;
            }
            String t = com.mineways.cloud.FeatureCatalog.norm(
                    c.title == null ? "" : c.title).replace(" ", "");
            if (t.equals(want)) {
                return c;
            }
            if (best == null && t.length() > 1 && (t.contains(want) || want.contains(t))) {
                best = c;
            }
        }
        return best;
    }

    private static boolean isSite(HomeIndex.Item it) {
        String g = it.group == null ? "" : it.group;
        return g.contains("web") || g.contains("cloud");
    }

    /**
     * AI 从功能页里调用 {@code open} 时要用首页那条放行路（首页这时还在返回栈里）。
     * 弱引用 + onDestroy 摘掉，绝不留活引用。
     */
    private static volatile java.lang.ref.WeakReference<MainActivity> sHome;

    /** 首页在不在前台；不在返回 null，调用方要如实告诉模型「回首页再试」。 */
    public static MainActivity home() {
        MainActivity m = sHome == null ? null : sHome.get();
        return m != null && !m.isFinishing() ? m : null;
    }

    /**
     * AI 调用功能用：走 {@link #launch} <b>同一条</b>路（激活码、专业版、云端「先问一句」一个都不跳），
     * 回一句给模型看的结论。绝不能给 AI 开第二条免检通道。
     */
    public String launchTool(HomeIndex.Item tool) {
        return launchTool(tool, "");
    }

    /**
     * 同上，多带一段 AI 给的内容（路径 / 文字）。只有 {@link AiHandoff} 登记过的页面收得到；
     * 收不到的页面照样打开，但会在结论里写清「内容没带过去」，路径不对则干脆不跳页。
     */
    public String launchTool(HomeIndex.Item tool, String input) {
        if (tool == null) {
            return "没找到这个功能";
        }
        if (tool.intent == null) {
            return "「" + tool.title + "」还没有可用入口";
        }
        if (!tool.usable) {
            return "「" + tool.title + "」当前不可用："
                    + (tool.notice == null || tool.notice.isEmpty() ? "云端已停用" : tool.notice);
        }
        Intent go = tool.intent;
        String note = "";
        String want = input == null ? "" : input.trim();
        if (want.length() > 0) {
            if (AiHandoff.accepts(tool) == null) {
                note = "（这个页面不接收外部内容，AI 给的内容没有带过去）";
            } else {
                Intent copy = new Intent(tool.intent);   // 首页缓存的那条 intent 不能被 AI 污染
                String handed = AiHandoff.apply(copy, tool, want);
                if (!AiHandoff.landed(copy, tool)) {
                    return "没有打开「" + tool.title + "」：" + handed;
                }
                go = copy;
                note = "（" + handed + "）";
            }
        }
        launch(tool, go);
        if (com.mineways.cloud.FeatureCatalog.MODE_ASK.equals(tool.mode)) {
            note = note + "（云端收录的站点会先弹一次确认，由用户点确认）";
        } else if (tool.kind == KIND_LIMIT) {
            note = note + "（这个功能要激活码，界面会引导用户输入）";
        }
        return "已打开「" + tool.title + "」" + note;
    }

    private void launch(final HomeIndex.Item tool) {
        launch(tool, tool.intent);
    }

    private void launch(final HomeIndex.Item tool, final Intent route) {
        if (route == null || route.getComponent() == null) {
            toast("该功能尚未上架");
            return;
        }
        if (!tool.usable) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("这个功能暂时不可用")
                    .setMessage(tool.notice.length() > 0 ? tool.notice : "云端已把它停用了，稍后再试。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        LoginAccess.Verdict lock = verdict(tool);
        if (!lock.allowed) {
            askLogin(tool, lock);
            return;
        }
        if (com.mineways.cloud.FeatureCatalog.MODE_ASK.equals(tool.mode) && !askedOk(tool.key)) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("要打开云端收录的网站")
                    .setMessage("「" + tool.title + "」是云端配置收进来的第三方站点，"
                            + "内容、服务与许可均由对方提供。确定打开吗？\n\n" + tool.sub)
                    .setNegativeButton("先不打开", null)
                    .setPositiveButton("打开", (d, w) -> {
                        markAsked(tool.key);
                        openItem(tool, route);
                    })
                    .show();
            return;
        }
        if (tool.kind == KIND_LIMIT) {
            // 白名单账号直接放行：卡片角标显示的是「已解锁」，这里不放行就成了假解锁。
            // 不带 activation_token（目标页对 null token 已兼容，ActivationDialog.finish 会直接忽略）
            if (LoginAccess.whitelistUser(this)) {
                openItem(tool, route);
                return;
            }
            // 激活码：每次使用扣 1 次机会。feature 是出厂标识，云端改名不会让用户和那张码脱钩
            final String feature = tool.feature;
            ActivationDialog.ensureReady(this, tool.title, feature, () ->
                    ActivationDialog.beginUse(this, feature, new ActivationDialog.UseCallback() {
                        @Override
                        public void onToken(String operationToken) {
                            Intent it = new Intent(route);
                            it.putExtra("activation_token", operationToken);
                            it.putExtra("activation_feature", feature);
                            try {
                                startActivity(it);
                            } catch (ActivityNotFoundException e) {
                                ActivationDialog.finish(MainActivity.this, feature, operationToken, false);
                                toast("打不开这个功能：" + tool.title);
                            }
                        }

                        @Override
                        public void onDenied() {
                        }
                    }));
            return;
        }
        openItem(tool, route);
    }

    /**
     * 这一条的登录门禁结论。现算不缓存：刚登录完那一路会重建界面，缓存会把「已经解锁」留在卡片上。
     */
    private LoginAccess.Verdict verdict(HomeIndex.Item tool) {
        return LoginAccess.check(this, tool.key, tool.gate, tool.kind);
    }

    /** 被门禁拦下来：说清缺什么，并给一个「去登录」的按钮，别只留一句「知道了」。 */
    private void askLogin(HomeIndex.Item tool, LoginAccess.Verdict lock) {
        String msg = (tool == null ? "" : "「" + tool.title + "」") + "\n\n" + lock.reason
                + (lock.need.length() > 0 ? ("\n\n这一条的门禁：" + lock.need
                + (lock.source.length() > 0 ? "（来自云端配置：" + lock.source + "）" : "")) : "");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("需要登录")
                .setMessage(msg)
                .setPositiveButton("去登录", (d, w) -> {
                    selectTab(4, true);
                    openLoginPanel();
                })
                .setNegativeButton("先不登录", null)
                .show();
    }

    /** 真正跳转；失败要说清是哪一个打不开（真机上只看得到界面，看不到 log）。 */
    private void openItem(HomeIndex.Item tool, Intent route) {
        try {
            startActivity(route);
        } catch (ActivityNotFoundException e) {
            toast("打不开这个功能：" + tool.title);
        }
    }

    /** {@code mode=ask} 的条目本机只问一次，记住用户已经点过「打开」。 */
    private boolean askedOk(String key) {
        return getSharedPreferences("cloud_ask", MODE_PRIVATE).getBoolean("ok_" + key, false);
    }

    private void markAsked(String key) {
        getSharedPreferences("cloud_ask", MODE_PRIVATE).edit()
                .putBoolean("ok_" + key, true).apply();
    }

    // ---------------------------------------------------------------- 底部导航

    // 标签格（图标 + 文字）已改由玻璃栏用 Compose 绘制：只有画在 Compose 层里的内容，
    // 滑块的玻璃才能把它录进背景，拖动时边缘扫过才有折射与模糊（原生 View 画在最上层，录不进去）。
    // 原来的 fillNav(...) 因此删除。

    /** 点标签 → 交给官方 NavController 导航（单实例 + 恢复状态，等同 nav-compose 的用法）。 */
    private void selectTab(int index, boolean animate) {
        if (navController == null || index < 0 || index >= DEST_IDS.length) {
            return;
        }
        NavDestination cur = navController.getCurrentDestination();
        if (cur != null && cur.getId() == DEST_IDS[index]) {
            return;                                  // 已经在这一页，不再压栈
        }
        navController.navigate(DEST_IDS[index], null, new NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)
                .setPopUpTo(R.id.nav_graph, false, true)
                .build());
    }

    /** 目的地变了：同步标签配色 + 玻璃块位置（对应玄戒观察 NavBackStackEntry 的做法）。 */
    private void onDestinationChanged(NavController controller, NavDestination destination,
                                      Bundle arguments) {
        if (destination == null) {
            return;
        }
        int idx = pageIndexOf(destination.getId());
        currentTab = idx;
        // 切页时导航栏显隐要跟 AI 状态一致（AI 开着就继续藏，关了就恢复）
        setNavHidden(com.mineways.ui.MwAiBus.open.getValue());
        // 标签配色（选中加粗 + 强调色）由玻璃栏自己刷，这里只同步选中项
        if (glassNav != null) {
            glassNav.setSelected(idx, true);
            glassNav.refreshBackdrop();
        }
    }

    // ---------------------------------------------------------------- 页面（供 PageFragment 调用）

    /** 目的地 id → 标签索引（找不到给 0）。PageFragment 与目的地回调都用它。 */
    int pageIndexOf(int destinationId) {
        for (int i = 0; i < DEST_IDS.length; i++) {
            if (DEST_IDS[i] == destinationId) {
                return i;
            }
        }
        return 0;
    }

    /**
     * PageFragment 用：按标签索引造页面。所有列表页都是同一个构造方法，只有档位不同。
     *
     * <p>界面重构后这里多一条岔路：新界面（Compose）是默认，云端一句 {@code FLAG set ui_home=legacy}
     * 就把整页退回下面这套旧 View 实现 —— 因为新观感只能靠真机定生死，回退必须不依赖重编包。
     */
    View buildPage(int index) {
        if (!spec().getLegacyHome()) {
            int pad = pageBottomPad > 0 ? pageBottomPad : dp(150);
            float density = getResources().getDisplayMetrics().density;
            return index == 4
                    ? com.mineways.ui.MwHost.aboutView(this, (int) (pad / density))
                    : com.mineways.ui.MwHost.tabView(this, index, (int) (pad / density));
        }
        if (index == 4) {
            return pageAbout();
        }
        return pageTagged(index);
    }

    /** 页面建好时登记：底部让位高度一算出来就补上。 */
    void registerPageView(View page) {
        if (page == null || pageViews.contains(page)) {
            return;
        }
        pageViews.add(page);
        // Compose 页的让位走 MwInsets（见上），View padding 会让它的背景缩到栏上沿
        if (pageBottomPad > 0 && !(page instanceof androidx.compose.ui.platform.ComposeView)) {
            page.setPadding(0, 0, 0, pageBottomPad);
        }
    }

    // ================================================================ 新界面（Compose）供数
    //
    // 这一整段只做一件事：把 {@link HomeIndex} 已经算好的答案换个形状交给 Compose。
    // <b>这里不许出现任何档位、显隐、停用、激活码、白名单判定</b> —— 那些全在门面与
    // {@link #launch(HomeIndex.Item)} 里，顺序仍然是「先停用 → 再 ask 确认 → 最后才谈扣码」。
    // 历史上出过「配置生效 17 条、界面毫无变化」，就是因为有人另开了一条取数路径。

    /** 分类底托色：按分类稳定取色，同一个板块每次都同一个颜色。 */
    private static final int[] MW_TINTS = {
            0xFFF2F2F2, 0xFFE3E3E3, 0xFFD6D6D6, 0xFFEDEDED, 0xFFDCDCDC, 0xFFE8E8E8, 0xFFCFCFCF,
    };

    @Override
    public com.mineways.ui.MwTokens.Spec spec() {
        return com.mineways.ui.MwTokens.INSTANCE.spec(this);
    }

    /**
     * 彩纸信号。用计数器而不是回调：登录成功那一路会重建界面，回调会随旧的一次组合一起丢掉，
     * 而"这之后第一次组合看到数字变了就放一次"不会漏。进程级静态，跨重建仍然有效。
     */
    private static int sFxToken = 0;

    @Override
    public int fxToken() {
        return sFxToken;
    }

    private void mwFx() {
        sFxToken++;
    }

    @Override
    public com.mineways.ui.MwTabMeta tabMeta(int index) {
        java.util.List<HomeIndex.Item> pool = mwPool(index);
        com.mineways.ui.MwTabMeta meta = new com.mineways.ui.MwTabMeta();
        meta.title = TAB_TITLES[index];
        String hint = tabHint(index);
        meta.hint = hint == null ? mwCountLine(pool) : hint;
        meta.stats = mwCountLine(pool);
        meta.searchHint = index == 0 ? "搜功能、站点、分类（如：皮肤 / 存档 / 光影）" : "";
        if (index <= 1) {
            java.util.List<String> cats = new ArrayList<>();
            cats.add("全部");
            cats.add("本地");
            for (HomeIndex.Cat c : HomeIndex.categories()) {
                cats.add(c.name);
            }
            meta.categories = cats;
        } else {
            meta.categories = java.util.Collections.emptyList();
        }
        return meta;
    }

    @Override
    public java.util.List<com.mineways.ui.MwBlock> blocks(int index) {
        return mwBlocks(index, 0);
    }

    @Override
    public java.util.List<com.mineways.ui.MwBlock> blocksFiltered(int index, int categoryIndex) {
        return mwBlocks(index, categoryIndex);
    }

    /** categoryIndex：0=全部，1=本地，2 起对应 {@link HomeIndex#categories()} 的每一类。 */
    private java.util.List<com.mineways.ui.MwBlock> mwBlocks(int index, int categoryIndex) {
        java.util.List<HomeIndex.Item> pool = mwPool(index);
        java.util.List<com.mineways.ui.MwBlock> out = new ArrayList<>();
        java.util.List<HomeIndex.Item> local = localOf(pool);
        if (categoryIndex <= 1 && !local.isEmpty()) {
            out.add(mwGroup(index == 1 ? "开源移植" : "本地工具",
                    index == 1 ? "Mineways、Chunker、three.js、Blockbench、Snowstorm"
                            : "不联网也能用", local));
        }
        int ci = 0;
        for (HomeIndex.Cat c : HomeIndex.categories()) {
            ci++;
            if (categoryIndex > 0 && categoryIndex != ci + 1) {
                continue;
            }
            java.util.List<HomeIndex.Item> inCat = inCategory(pool, c.id);
            if (!inCat.isEmpty()) {
                out.add(mwGroup(c.name, c.desc + "，共 " + inCat.size() + " 家", inCat));
            }
        }
        return out;
    }

    private java.util.List<HomeIndex.Item> mwPool(int index) {
        return index == 0 ? items() : HomeIndex.ofKind(kindForTab(index));
    }

    private String mwCountLine(java.util.List<HomeIndex.Item> pool) {
        int local = localOf(pool).size();
        int cloud = 0;
        int offline = 0;
        for (HomeIndex.Item it : pool) {
            if (it.fromCloud) {
                cloud++;
            }
            if (!it.usable) {
                offline++;
            }
        }
        StringBuilder sb = new StringBuilder("共 ").append(pool.size()).append(" 项 · 本机 ")
                .append(local).append(" · 在线 ").append(pool.size() - local);
        if (cloud > 0) {
            sb.append(" · 云端上架 ").append(cloud);
        }
        if (offline > 0) {
            sb.append(" · 已停用 ").append(offline);
        }
        return sb.toString();
    }

    private com.mineways.ui.MwBlock mwGroup(String title, String subtitle,
            java.util.List<HomeIndex.Item> tools) {
        com.mineways.ui.MwBlock block = new com.mineways.ui.MwBlock();
        block.title = title;
        block.subtitle = subtitle;
        block.trailing = tools.size() + " 项";
        block.grid = true;
        java.util.List<com.mineways.ui.MwCell> cells = new ArrayList<>();
        int n = 1;
        for (HomeIndex.Item it : tools) {
            cells.add(mwCell(it, n++));
        }
        block.cells = cells;
        return block;
    }

    /** 一个入口 → 一格。徽标与说明沿用旧卡片的判断（已解锁 / 白名单 / 限制 / 停用原因）。 */
    private com.mineways.ui.MwCell mwCell(HomeIndex.Item tool, int number) {
        com.mineways.ui.MwCell cell = new com.mineways.ui.MwCell();
        cell.title = tool.title;
        cell.sub = tool.sub;
        cell.iconRes = tool.icon;
        cell.index = number;
        cell.item = tool;
        cell.tintArgb = MW_TINTS[Math.abs(tool.cat == null ? 0 : tool.cat.hashCode()) % MW_TINTS.length];
        LoginAccess.Verdict lock = verdict(tool);
        if (tool.kind != KIND_FREE || lock.gated()) {
            cell.badge = lock.allowed ? "已解锁"
                    : (lock.badge.length() > 0 ? lock.badge
                    : (tool.kind == KIND_PAID ? "白名单" : "限制"));
            cell.badgeArgb = lock.allowed ? 0xFF4FBF7B
                    : (tool.kind == KIND_PAID || lock.gated() ? 0xFF59A8FF : 0xFFE09551);
        } else if (tool.pinned) {
            cell.badge = "常用";
            cell.badgeArgb = 0xFFE8E8E8;
        }
        if (!tool.usable) {
            cell.note = tool.notice.length() > 0 ? tool.notice : "云端已把它停用，稍后再试。";
            cell.noteArgb = 0xFFE07B7B;
        } else if (!lock.allowed) {
            cell.note = lock.reason;
            cell.noteArgb = 0xFF59A8FF;
        } else if (tool.fromCloud) {
            cell.note = "云端新上架，来自这份配置：" + tool.source;
            cell.noteArgb = 0xFF9AA1AB;
        }
        return cell;
    }

    @Override
    public com.mineways.ui.MwTabMeta aboutMeta() {
        com.mineways.ui.MwTabMeta meta = new com.mineways.ui.MwTabMeta();
        meta.title = "关于";
        meta.hint = "版本 " + versionName() + " · 内置 " + LOCAL_TOOLS.length
                + " 个本机功能 · 收录 " + WEB_TOOLS.length + " 个在线工具";
        meta.stats = com.mineways.cloud.CloudCatalog.status();
        meta.searchHint = "";
        meta.categories = java.util.Collections.emptyList();
        return meta;
    }

    @Override
    public java.util.List<com.mineways.ui.MwBlock> aboutBlocks() {
        java.util.List<com.mineways.ui.MwBlock> out = new ArrayList<>();

        // 登录：三档身份合成一个入口。以前「社区授权登录」和「白名单账号登录」并排两行，
        // 两个按钮各说各的登录态，用户要自己猜点哪个才算登录成功。
        java.util.List<com.mineways.ui.MwCell> accounts = new ArrayList<>();
        com.mineways.ui.MwCell login = new com.mineways.ui.MwCell();
        login.title = "登录";
        login.sub = loginSummary();
        login.actionLabel = Identities.hasAny(this) ? "管理" : "登录";
        login.action = com.mineways.ui.MwUiHost.ACTION_LOGIN;
        login.primary = true;
        accounts.add(login);
        out.add(mwTextBlock("登录与身份",
                "论坛 / Google / 微软 都在这一个入口里；哪个功能要哪种登录、要不要在名单里，由云端配置逐条规定",
                accounts));

        // 云控状态：以前"生效 17 条"却什么都看不出来，所以每一项都要成行成列
        java.util.List<com.mineways.ui.MwCell> cloud = new ArrayList<>();
        cloud.add(mwValue("当前状态", com.mineways.cloud.CloudCatalog.status()));
        java.util.List<String> chain = com.mineways.cloud.CloudCatalog.chainVersions();
        cloud.add(mwValue("生效链", chain.isEmpty() ? "（无）" : String.join(" → ", chain)));
        cloud.add(mwValue("本次下发", com.mineways.cloud.CloudCatalog.transferNote().isEmpty()
                ? "（本次还没重新下载）" : com.mineways.cloud.CloudCatalog.transferNote()));
        cloud.add(mwValue("本机配置", cloudCacheLine()));
        // 机型的两个身份与玻璃策略结果都摊开写：判错了用户/测试一眼能看出来，不用抓 log
        cloud.add(mwValue("本机机型", DeviceId.debugLine()));
        cloud.add(mwValue("玻璃机型策略", GlassPolicy.debugLine()));
        // 登录与门禁同理：判错了（名单没命中、档配错了）要在这页当场看得出来，不用抓 log
        cloud.add(mwValue("登录身份", Identities.summary(this)));
        cloud.add(mwValue("名单命中", LoginAccess.debugLine(this)));
        java.util.List<String> gated = LoginAccess.gatedKeys();
        cloud.add(mwValue("登录门禁", gated.isEmpty()
                ? "这一版云端没给任何功能配 gate（沿用档位判定）"
                : gated.size() + " 个功能配了门禁 · "
                + String.join("、", gated.subList(0, Math.min(6, gated.size())))
                + (gated.size() > 6 ? "… 其余看配置" : "")));
        if (com.mineways.cloud.CloudCatalog.pruneNote().length() > 0) {
            cloud.add(mwValue("旧版本清理", com.mineways.cloud.CloudCatalog.pruneNote()
                    + "，腾出 " + (com.mineways.cloud.CloudCatalog.droppedBytes() / 1024L) + " KB"));
        }
        com.mineways.cloud.FeatureCatalog.Result raw =
                com.mineways.cloud.CloudCatalog.gate().raw();
        if (raw != null) {
            cloud.add(mwValue("这份配置动了什么", "生效 " + raw.applied + " 条 · 写错 " + raw.issues.size()
                    + " 条 · 本机不适用 " + raw.notes.size() + " 条 · 隐藏 " + raw.hidden.size()
                    + " · 改档 " + raw.tiers.size() + " · 开关 " + raw.flags.size()
                    + " · 词条 " + com.mineways.cloud.CloudCatalog.gate().translationCount()));
        }
        if (raw != null && !raw.issues.isEmpty()) {
            for (int i = 0; i < raw.issues.size() && i < 20; i++) {
                com.mineways.cloud.FeatureCatalog.Issue it = raw.issues.get(i);
                cloud.add(mwValue("写错：第" + (it.file + 1) + "份 第" + it.line + "行", it.reason));
            }
        }
        com.mineways.ui.MwCell refresh = new com.mineways.ui.MwCell();
        refresh.title = "云端配置";
        refresh.sub = "每次启动都会拉清单核对；配置改了不用重装 App。";
        refresh.actionLabel = "立即刷新";
        refresh.action = com.mineways.ui.MwUiHost.ACTION_REFRESH;
        refresh.primary = true;
        cloud.add(refresh);
        if (GlassPolicy.hasChoice(this)) {
            com.mineways.ui.MwCell glass = new com.mineways.ui.MwCell();
            glass.title = "玻璃效果的机型选择";
            glass.sub = "你这台机器被云控命中过「询问是否降低」，已经选过一次了。"
                    + "想改主意就清掉，下次进页面会再问一次。";
            glass.actionLabel = "重新选择";
            glass.action = com.mineways.ui.MwUiHost.ACTION_GLASS_RESET;
            glass.primary = true;
            cloud.add(glass);
        }
        out.add(mwTextBlock("云端配置", "这一页的显示与功能都由它指挥；云控说退回旧界面也能退回", cloud));

        // 说明与社区
        java.util.List<com.mineways.ui.MwCell> links = new ArrayList<>();
        com.mineways.ui.MwCell body = new com.mineways.ui.MwCell();
        body.title = "这个 App";
        body.sub = getString(R.string.about_body).replace('\n', ' ');
        links.add(body);
        com.mineways.ui.MwCell join = new com.mineways.ui.MwCell();
        join.title = getString(R.string.about_join_btn);
        join.sub = "反馈、提需求、看更新";
        join.actionLabel = "加入";
        join.action = com.mineways.ui.MwUiHost.ACTION_JOIN;
        join.primary = true;
        join.url = getString(R.string.about_join_url);
        links.add(join);
        com.mineways.ui.MwCell repo = new com.mineways.ui.MwCell();
        repo.title = "开源仓库";
        repo.sub = REPO;
        repo.actionLabel = "打开";
        repo.action = com.mineways.ui.MwUiHost.ACTION_REPO;
        repo.url = REPO;
        links.add(repo);
        out.add(mwTextBlock("说明与社区", null, links));

        // 鸣谢
        java.util.List<com.mineways.ui.MwCell> credits = new ArrayList<>();
        for (String[] c : CREDITS) {
            com.mineways.ui.MwCell cell = new com.mineways.ui.MwCell();
            cell.title = c[0];
            cell.sub = c[1];
            cell.badge = c[2];
            cell.badgeArgb = 0xFF9AA1AB;
            cell.url = c[3];
            cell.action = com.mineways.ui.MwUiHost.ACTION_OPEN_URL;
            credits.add(cell);
        }
        out.add(mwTextBlock("开源项目鸣谢",
                "下面这些项目作为源码或静态资源内置，权利归原作者；点击可打开上游", credits));

        // 收录网站：一百多家全列会把关于页拉得看不到底，所以按板块分组、默认折叠，和旧版一致。
        // 取数同样只走 HomeIndex —— 云端下架过的站点不会再从这里冒出来。
        for (HomeIndex.Cat c : HomeIndex.categories()) {
            java.util.List<HomeIndex.Item> list = inCategory(items(), c.id);
            if (list.isEmpty()) {
                continue;
            }
            java.util.List<com.mineways.ui.MwCell> sites = new ArrayList<>();
            for (HomeIndex.Item it : list) {
                com.mineways.ui.MwCell cell = new com.mineways.ui.MwCell();
                cell.title = it.title;
                cell.sub = it.sub;
                cell.badge = hostOf(urlOf(it));
                cell.badgeArgb = 0xFF9AA1AB;
                cell.item = it;
                cell.url = urlOf(it);
                cell.action = com.mineways.ui.MwUiHost.ACTION_OPEN_ITEM;
                sites.add(cell);
            }
            com.mineways.ui.MwBlock block = mwTextBlock(c.name + " 收录",
                    "共 " + list.size() + " 家，内容、服务与许可均由对方提供，点击直接打开原站", sites);
            block.expandable = true;
            out.add(block);
        }

        com.mineways.ui.MwCell thanks = new com.mineways.ui.MwCell();
        thanks.paragraph = "· 地图数据：© OpenStreetMap contributors（Overpass / OSM 瓦片）、Overture Maps、"
                + "AWS Terrain Tiles —— 「真实世界」生成的底图全部来自它们。\n"
                + "· 皮肤与头像接口：Mojang / 各皮肤站公开 API。\n"
                + "· 中文社区：MC 圈子里愿意把教程、模型、材质和踩坑记录公开分享的作者们。\n"
                + "· 你 —— 反馈 bug、提需求、帮忙验证真机效果的所有用户。\n\n"
                + "本项目不是官方产品，与 Mojang Studios、Microsoft、网易雷火均无隶属或背书关系。";
        out.add(mwTextBlock("其它鸣谢", null,
                java.util.Collections.singletonList(thanks)));
        return out;
    }

    /** 可折叠的列表组（收录网站那种：默认只露前几项）。 */
    private com.mineways.ui.MwBlock mwExpandableBlock(String title, String subtitle,
            java.util.List<com.mineways.ui.MwCell> cells) {
        com.mineways.ui.MwBlock block = mwTextBlock(title, subtitle, cells);
        block.expandable = true;
        return block;
    }

    private com.mineways.ui.MwBlock mwTextBlock(String title, String subtitle,
            java.util.List<com.mineways.ui.MwCell> cells) {
        com.mineways.ui.MwBlock block = new com.mineways.ui.MwBlock();
        block.title = title;
        block.subtitle = subtitle;
        block.trailing = null;
        block.grid = false;
        block.cells = cells;
        return block;
    }

    private com.mineways.ui.MwCell mwValue(String label, String value) {
        com.mineways.ui.MwCell cell = new com.mineways.ui.MwCell();
        cell.title = label;
        cell.value = value;
        return cell;
    }

    /**
     * 打开统一登录面板。新界面（Compose）翻的是挂在关于页那棵树里的玻璃面板，
     * 云控 {@code ui_home=legacy} 退回旧 View 界面时才用 {@link LoginDialog} ——
     * 两条路都是同一个入口、同一份身份数据，只是画法和回退路径不同。
     */
    private void openLoginPanel() {
        if (spec().getLegacyHome()) {
            LoginDialog.show(this, () -> {
                mwFx();
                recreate();
            });
        } else {
            com.mineways.ui.MwLoginBus.open.setValue(true);
        }
    }

    @Override
    public void refreshAfterLogin() {
        mwFx();
        recreate();
    }

    @Override
    public void run(int action, HomeIndex.Item item, String url) {
        switch (action) {
            case com.mineways.ui.MwUiHost.ACTION_OPEN_ITEM:
                if (item != null) {
                    launch(item);
                }
                break;
            case com.mineways.ui.MwUiHost.ACTION_REFRESH:
                com.mineways.cloud.CloudCatalog.refresh(this);
                break;
            case com.mineways.ui.MwUiHost.ACTION_LOGIN:
                openLoginPanel();
                break;
            case com.mineways.ui.MwUiHost.ACTION_JOIN:
                openUrl(getString(R.string.about_join_url));
                break;
            case com.mineways.ui.MwUiHost.ACTION_REPO:
                openUrl(REPO);
                break;
            case com.mineways.ui.MwUiHost.ACTION_OPEN_URL:
                if (url != null && url.length() > 0) {
                    openUrl(url);
                }
                break;
            case com.mineways.ui.MwUiHost.ACTION_ABOUT:
                // 这里以前漏了分支：三个点绑 ACTION_ABOUT，点下去直接落 default —— 毫无反应。
                // 现在三个点默认是 AI 入口；云控 ui_ai=0 时仍从这条退回「关于」（第 5 个页签）。
                selectTab(4, true);
                break;
            case com.mineways.ui.MwUiHost.ACTION_AI_OPEN:
                // AI 全屏页展开/收起：让底部导航栏让位
                setNavHidden("1".equals(url));
                break;
            case com.mineways.ui.MwUiHost.ACTION_AI_ASK:
                // 长按卡片「问 AI」：开面板 + 输入框里预填这一项，用户补一句就发
                if (!com.mineways.AiAccess.allowed(this)) {
                    toast("AI 目前不对当前账号开放");
                } else {
                    com.mineways.ui.MwAiBus.prefill.setValue(
                            "关于「" + (url == null || url.isEmpty() ? "这个功能" : url) + "」：");
                    com.mineways.ui.MwAiBus.open.setValue(true);
                }
                break;
            case com.mineways.ui.MwUiHost.ACTION_GLASS_RESET:
                GlassPolicy.reset(this);
                toast("已清掉选择，下次进入功能页会再问一次");
                recreate();
                break;
            default:
                break;
        }
    }

    /** 页面销毁时注销。 */
    void unregisterPageView(View page) {
        pageViews.remove(page);
    }

    // ---------------------------------------------------------------- 登录

    /**
     * 关于页那一行的现状：三档身份各自是什么。
     * 名单命中情况也带一句 —— 判错了（比如论坛那边改了显示名、邮箱没回来）当场就能看出来。
     */
    private String loginSummary() {
        StringBuilder names = new StringBuilder();
        int live = 0;
        for (Identities.Account a : Identities.all(this)) {
            if (!a.live()) {
                continue;
            }
            live++;
            if (names.length() > 0) {
                names.append(" · ");
            }
            names.append(a.display()).append('（').append(LoginPolicy.scopeCn(a.scope)).append('）');
        }
        if (live == 0) {
            return "未登录 —— 点右侧「登录」选一种方式";
        }
        return "已登录 " + live + " 个身份：" + names + " · " + LoginAccess.debugLine(this);
    }

    // ---------------------------------------------------------------- 小工具

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private TextView textButton(String text, boolean primary) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.02f);
        tv.setTextColor(primary ? ACCENT : DIM);
        tv.setPadding(0, dp(8), 0, dp(8));
        return tv;
    }

    /** 屏幕密度（玻璃 Drawable 按 px 给半径和边光宽度）。 */
    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusDp >= 999 ? dp(100) : dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
