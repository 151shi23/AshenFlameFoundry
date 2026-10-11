package com.mineways;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AshenFlame Foundry（AFF）· 工具箱外壳 —— 纯 UI，不含任何转换/导出逻辑。
 *
 * <p><b>分类机制（标签驱动，不手写归属）</b>：每个工具只登记一次，带一组标签；
 * 页面按标签自动收集卡片 —— 新增工具 = 表里加一行，各页自动出现。
 * <pre>
 *   local  = 本机实现（内置 Activity）
 *   web    = 网页工具（WebToolActivity 容器）
 *   free   = 免费
 *   paid   = 白名单功能（登录白名单账号后开放）
 * </pre>
 *
 * <p>原导出界面（导出 / 网易 / 转换 / 工具页）仍是 {@link ExportActivity}，内核算法一行未改。
 */
public class MainActivity extends AppCompatActivity {

    // ---------------------------------------------------------------- 色板（唯一强调色：熔炉橙）

    private static final int BG = 0xFF0E0F11;
    private static final int PANEL = 0xFF15171A;
    private static final int LINE = 0x14FFFFFF;
    private static final int TEXT = 0xFFEDEDED;
    private static final int DIM = 0xFF8A9099;
    private static final int DIM2 = 0xFF5C6268;
    private static final int ACCENT = 0xFFD9603A;

    private static final int KIND_FREE = 0;
    private static final int KIND_PAID = 1;
    private static final int KIND_LIMIT = 2;

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

    private static class Tool {
        final String title;
        final String sub;
        final int icon;
        final int kind;
        final Intent intent;
        final String tags;
        /** 分类 key：本地工具为 "local"，网页工具为 WEB_CATS 里的 key。 */
        final String cat;

        /** 功能标识：本地激活记录按它区分（一张码绑一个功能）。 */
        String featureKey() {
            return title;
        }

        Tool(String title, String sub, int icon, int kind, Intent intent, String tags, String cat) {
            this.title = title;
            this.sub = sub;
            this.icon = icon;
            this.kind = kind;
            this.intent = intent;
            this.tags = tags == null ? "" : tags;
            this.cat = cat == null ? "" : cat;
        }
    }

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
        WatermarkView.attach(this);
        ActivationStore.flushPending(this);   // 补发上次没发完的 confirm / release（幂等）
        AvatarLoader.init(getCacheDir());     // 社区头像的磁盘缓存目录（cacheDir/avatar）
        AnnouncementCenter.checkAndShow(this);  // 仓库根目录 ovo.txt 公告：命中条件就弹，没有就算了
        // 启动不再弹任何问答窗：登录改成「关于」页手动登录，白名单只认登录账号
        // 起始目的地由 NavHost 自己落位，栏状态在目的地回调里同步
    }

    @Override
    public void onBackPressed() {
        if (currentTab != 0) {
            selectTab(0, true);
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 骨架

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 内容 + 悬浮玻璃导航栏叠在同一层（玄戒也是 MainScreen = Box(fillMaxSize) + 悬浮栏）：
        // 导航栏压住内容底部，玻璃才有得透；页面靠自身 paddingBottom 让位。
        // 内容区走安卓官方的 Navigation：NavHost 承载 5 个 Fragment 目的地。
        FrameLayout content = new FrameLayout(this);
        FragmentContainerView host = new FragmentContainerView(this);
        host.setId(R.id.nav_host);          // 固定 id：进程恢复时能找回原来的 NavHost
        content.addView(host, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 先把玻璃栏建好，NavHost 落位时目的地回调就能同步它
        glassNav = new LiquidGlassNav(this);
        glassNav.setBackdropSource(content);
        glassNav.setColors(ACCENT, DIM2);     // 玄戒取 ColorScheme.primary，这里取主题强调色 + 未选中色
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
                for (View p : pageViews) {
                    p.setPadding(0, 0, 0, pad);
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
        return root;
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

    /** 第 index 个页签：按 TAB_TAGS 收集卡片，自动分组。 */
    private View pageTagged(int index) {
        ScrollView sv = newPage();
        LinearLayout col = column();
        String tag = TAB_TAGS[index];
        String title = TAB_TITLES[index];

        List<Tool> local = byTag(tag, "local");
        List<Tool> web = byTag(tag, "web");

        if (index == 0 || index == 1) {
            boolean freePage = index == 1;
            col.addView(sectionTitle(title, null));   // 原来这里挂着「一共 99 个：本地 13 个，网页 86 家」，已删
            col.addView(buildFilterBar());            // 搜索框 + 分类胶囊
            addSection(col, freePage ? "开源移植" : "本地工具", local,
                    freePage ? "Mineways、Chunker、three.js、Blockbench、Snowstorm"
                             : "不联网也能用", "local");
            for (String[] c : WEB_CATS) {
                List<Tool> inCat = ofCat(web, c[0]);
                addSection(col, c[1], inCat, c[2] + "，" + inCat.size() + " 家", c[0]);
            }
            sv.addView(col);
            return sv;
        }

        // 限制页（激活码）
        col.addView(sectionTitle(title,
                "每个功能用 1 个激活码，在社区网页签到领取，粘到 App 里就能用"));
        addSection(col, "需要激活码", local, null, "local");
        sv.addView(col);
        return sv;
    }

    private void addSection(LinearLayout col, String title, List<Tool> tools, String subtitle, String cat) {
        if (tools.isEmpty()) {
            return;
        }
        View header = sectionTitle(title, subtitle);
        col.addView(header);
        LinearLayout grid = grid();
        int n = 1;
        for (Tool t : tools) {
            addCard(grid, t, n++, header, cat);
        }
        padLastRow(grid);
        col.addView(grid);
    }

    private View pagePaid() {
        ScrollView sv = newPage();
        LinearLayout col = column();
        final boolean unlocked = ProAuth.isAuthorized(this);
        col.addView(sectionTitle("白名单功能", unlocked
                ? "已解锁：这一页的功能全部开放，直接用"
                : "用白名单账号登录后解锁（到「关于」页登录：密码或邮件验证码都行）"));
        List<Tool> paid = byTag("paid", null);
        if (paid.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("白名单功能正在整理，稍后再来。");
            t.setTextSize(13);
            t.setTextColor(DIM);
            col.addView(t);
        } else {
            addSection(col, unlocked ? "已解锁" : "需要白名单账号", paid, null, "paid");
        }
        // 原来漏了这一行：col 里灌好了标题和卡片，却没挂进 sv，白名单页整页空白
        sv.addView(col);
        return sv;
    }

    private View pageAbout() {
        ScrollView sv = newPage();
        LinearLayout col = column();
        col.addView(sectionTitle("关于", "版本 " + versionName()));

        // 账号：论坛 OAuth 授权登录（Mode B，手动授权码）
        final OAuthStore.Account acc = OAuthStore.load(this);
        LinearLayout accountRow = new LinearLayout(this);
        accountRow.setOrientation(LinearLayout.HORIZONTAL);
        accountRow.setGravity(Gravity.CENTER_VERTICAL);
        accountRow.setPadding(dp(16), dp(16), dp(16), dp(16));
        accountRow.setBackground(round(PANEL, 14));

        // 头像：登录后显示社区头像（圆裁），未登录 / 下载中显示首字母占位
        ImageView accAvatar = new ImageView(this);
        int avSize = dp(46);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(avSize, avSize);
        avLp.rightMargin = dp(12);
        accAvatar.setLayoutParams(avLp);
        accountRow.addView(accAvatar);
        AvatarLoader.load(accAvatar, acc.loggedIn() ? acc.avatar : "", acc.username);

        LinearLayout accTexts = new LinearLayout(this);
        accTexts.setOrientation(LinearLayout.VERTICAL);
        accTexts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView accName = new TextView(this);
        accName.setTextSize(14);
        accName.setTypeface(Typeface.DEFAULT_BOLD);
        accName.setTextColor(TEXT);
        accName.setText(acc.loggedIn()
                ? ("已登录：" + (acc.username.length() > 0 ? acc.username : "社区用户"))
                : "未登录");
        accTexts.addView(accName);

        TextView accSub = new TextView(this);
        accSub.setTextSize(11);
        accSub.setTextColor(DIM2);
        accSub.setPadding(0, dp(6), 0, 0);
        accSub.setText(acc.loggedIn()
                ? (acc.joinedAt.length() > 0 ? ("注册于 " + acc.joinedAt) : "社区身份已绑定到本机")
                : "授权后可同步社区身份（签到 / 激活码）");
        accTexts.addView(accSub);
        accountRow.addView(accTexts);

        TextView accBtn = textButton(acc.loggedIn() ? "退出" : "授权登录", true);
        accBtn.setOnClickListener(v -> {
            if (OAuthStore.load(this).loggedIn()) {
                OAuthStore.logout(this);
                toast("已退出登录");
                recreate();
            } else {
                OAuthDialog.show(this, account -> {
                    toast("登录成功");
                    recreate();
                });
            }
        });
        accountRow.addView(accBtn);
        col.addView(accountRow);

        // 授权登录后，用户名 / 头像在登录时就已经存到本地了。若这份资料里没有头像
        // （老版本登录过、或论坛后来才补上头像），顺手拉一次 /api/oauth/userinfo 补齐并刷新这一行。
        if (acc.loggedIn() && acc.avatar.length() == 0) {
            new Thread(() -> {
                final OAuthClient.User u = OAuthClient.userInfo(acc.token);
                if (u == null || (u.username.length() == 0 && u.avatar.length() == 0)) {
                    return;
                }
                OAuthStore.updateProfile(this, u);
                runOnUiThread(() -> {
                    if (u.username.length() > 0) {
                        accName.setText("已登录：" + u.username);
                    }
                    if (u.joinedAt.length() > 0) {
                        accSub.setText("注册于 " + u.joinedAt);
                    }
                    AvatarLoader.load(accAvatar, u.avatar,
                            u.username.length() > 0 ? u.username : acc.username);
                });
            }, "oauth-profile").start();
        }

        // ---- 白名单账号：关于页手动登录（账号 + 密码）----
        final ProAuth.Record pr = ProAuth.load(this);
        final boolean wl = ProAuth.isAuthorized(this);
        final boolean loggedIn = pr.hasToken() && !pr.expired();
        LinearLayout accRow = new LinearLayout(this);
        accRow.setOrientation(LinearLayout.HORIZONTAL);
        accRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView accState = new TextView(this);
        accState.setText(wl ? ("白名单已解锁：" + prName(pr))
                : (loggedIn ? "已登录，但不在白名单：" + prName(pr)
                : "未登录 —— 白名单功能还没解锁"));
        accState.setTextSize(14);
        accState.setTextColor(wl ? ACCENT : 0xFFD6DEE6);
        accRow.addView(accState, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView wlBtn = textButton(loggedIn ? "退出登录" : "账号登录", true);
        LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        accLp.leftMargin = dp(12);
        wlBtn.setLayoutParams(accLp);
        wlBtn.setOnClickListener(v -> {
            if (loggedIn) {
                ProAuth.clear(MainActivity.this);
                toast("已退出登录");
                recreate();
            } else {
                showAccountLogin();
            }
        });
        accRow.addView(wlBtn);
        col.addView(accRow);

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
        body.setTextColor(0xFFD6DEE6);
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

    /** 收录网站清单折起在按钮后面：124 家全列会把关于页拉得过长，点开时才构造这些行。 */
    private void addSiteCredits(LinearLayout col) {
        col.addView(sectionTitle("收录网站鸣谢",
                WEB_TOOLS.length + " 家站点被收进工具箱，内容、服务与许可均由对方提供，点击直接打开原站。"));

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
        final String collapsed = "展开收录网站 · " + WEB_TOOLS.length + " 家";
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

    private void buildSiteCredits(LinearLayout col) {
        for (String[] cat : WEB_CATS) {
            LinearLayout panel = card();
            int n = 0;
            for (String[] w : WEB_TOOLS) {
                if (!cat[0].equals(w.length > 4 ? w[4] : "")) {
                    continue;
                }
                divider(panel, n);
                panel.addView(creditRow(w[0], hostOf(w[2]), null, w[2], w[1]));
                n++;
            }
            if (n == 0) {
                continue;
            }
            col.addView(siteCatTitle(cat[1], n));
            col.addView(panel);
        }
    }

    private void addThanks(LinearLayout col) {
        col.addView(sectionTitle("其它鸣谢", null));
        LinearLayout panel = card();
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setTextColor(0xFFD6DEE6);
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

    // ---------------------------------------------------------------- 标签检索

    /** 全部工具（本地 + 网页），唯一数据源。 */
    private List<Tool> allTools() {
        List<Tool> all = new ArrayList<>();
        for (String[] r : LOCAL_TOOLS) {
            int icon = iconOf(r[2]);
            boolean paid = r[3].contains("paid");
            boolean limited = r[3].contains("limit");
            Intent it;
            if ("png2model".equals(r[2])) {
                it = new Intent(this, PngToModelActivity.class);
            } else if ("realworld".equals(r[2])) {
                it = new Intent(this, RealWorldActivity.class);
            } else if ("preview".equals(r[2])) {
                it = new Intent(this, ObjPreviewActivity.class);
            } else if ("bb2obj".equals(r[2])) {
                it = new Intent(this, BbToObjActivity.class);
            } else if ("particle".equals(r[2])) {
                it = new Intent(this, SnowstormActivity.class);
            } else if ("blockbench".equals(r[2])) {
                it = new Intent(this, BlockbenchActivity.class);
            } else if ("ai".equals(r[2])) {
                it = new Intent(this, AiAnimActivity.class);
            } else if ("seedmap".equals(r[2])) {
                it = new Intent(this, SeedMapActivity.class);
            } else if ("pixelmap".equals(r[2])) {
                it = new Intent(this, PixelmapActivity.class);
            } else if ("colorcode".equals(r[2])) {
                it = new Intent(this, ColorCodeActivity.class);
            } else if ("gif".equals(r[2])) {
                it = new Intent(this, GifActivity.class);
            } else if ("landscape".equals(r[2])) {
                it = new Intent(this, LandscapeEnhanceActivity.class);
            } else if ("game".equals(r[2])) {
                it = new Intent(this, MiniGameActivity.class);
            } else if ("phrase".equals(r[2])) {
                it = new Intent(this, PhraseToolboxActivity.class);
            } else if ("p3d".equals(r[2])) {
                it = new Intent(this, P3DConvertActivity.class);
            } else if ("repair".equals(r[2])) {
                it = new Intent(this, FileRepairActivity.class);
            } else if ("fmtconv".equals(r[2])) {
                it = new Intent(this, com.mineways.conv.FormatConvertActivity.class);
            } else if ("blender".equals(r[2])) {
                it = new Intent(this, com.mineways.blender.BlenderActivity.class);
            } else if ("rhinelab".equals(r[2])) {
                it = new Intent(this, RhineLabActivity.class);
            } else if ("cloudtrain".equals(r[2])) {
                it = new Intent(this, CloudTrainActivity.class);
            } else if ("opencut".equals(r[2])) {
                it = new Intent(this, OpenCutActivity.class);
            } else if ("scrub".equals(r[2])) {
                it = new Intent(this, BrandScrubActivity.class);
            } else if ("mineimator".equals(r[2])) {
                // 内置引擎（libmineimator.so + Qt5）自绘界面；架构不支持时由该 Activity 自己提示
                it = new Intent(this, com.mineimator.app.MainActivity.class);
            } else {
                it = exportPage(r.length > 4 ? r[4] : "");
            }
            all.add(new Tool(r[0], r[1], icon, paid ? KIND_PAID : (limited ? KIND_LIMIT : KIND_FREE), it, r[3], "local"));
        }
        for (String[] r : WEB_TOOLS) {
            String cat = r.length > 4 ? r[4] : "";
            all.add(new Tool(r[0], r[1], R.drawable.ic_preview, KIND_FREE, web(r[0], r[2]), r[3], cat));
        }
        return all;
    }

    /** 某分类下的网页工具。 */
    private List<Tool> ofCat(List<Tool> tools, String cat) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : tools) {
            if (cat.equals(t.cat)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 分类 key → 中文名。 */
    private String catNameOf(String cat) {
        for (String[] c : WEB_CATS) {
            if (c[0].equals(cat)) {
                return c[1];
            }
        }
        return "local".equals(cat) ? "本地工具" : "";
    }

    /** 标签筛选：tag 为空=全部，group 为空=不限实现方式。 */
    private List<Tool> byTag(String tag, String group) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : allTools()) {
            if (tag != null && tag.length() > 0 && !t.tags.contains(tag)) {
                continue;
            }
            if (group != null && group.length() > 0 && !t.tags.contains(group)) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private int iconOf(String key) {
        if ("opencut".equals(key)) {
            return R.drawable.ic_opencut;
        }
        if ("scrub".equals(key)) {
            return R.drawable.ic_scrub;
        }
        if ("rhinelab".equals(key)) {
            return R.drawable.ic_preview;
        }
        if ("cloudtrain".equals(key)) {
            return R.drawable.ic_cloudtrain;
        }
        if ("export".equals(key)) {
            return R.drawable.ic_export;
        }
        if ("world".equals(key)) {
            return R.drawable.ic_world;
        }
        if ("realworld".equals(key)) {
            return R.drawable.ic_realworld;
        }
        if ("tools".equals(key)) {
            return R.drawable.ic_tools;
        }
        if ("preview".equals(key)) {
            return R.drawable.ic_preview;
        }
        if ("png2model".equals(key)) {
            return R.drawable.ic_png2model;
        }
        if ("bb2obj".equals(key)) {
            return R.drawable.ic_bb2obj;
        }
        if ("particle".equals(key)) {
            return R.drawable.ic_particle;
        }
        if ("blockbench".equals(key)) {
            return R.drawable.ic_blockbench;
        }
        if ("ai".equals(key)) {
            return R.drawable.ic_ai;
        }
        if ("seedmap".equals(key)) {
            return R.drawable.ic_world;
        }
        if ("pixelmap".equals(key)) {
            return R.drawable.ic_realworld;
        }
        if ("colorcode".equals(key)) {
            return R.drawable.ic_particle;
        }
        if ("gif".equals(key)) {
            return R.drawable.ic_preview;
        }
        if ("landscape".equals(key)) {
            return R.drawable.ic_landscape;
        }
        if ("game".equals(key)) {
            return R.drawable.ic_game;
        }
        if ("phrase".equals(key)) {
            return R.drawable.ic_phrase;
        }
        if ("mineimator".equals(key)) {
            return R.drawable.mim_logo;
        }
        if ("fmtconv".equals(key)) {
            return R.drawable.ic_tools;
        }
        if ("blender".equals(key)) {
            return R.drawable.ic_blender;
        }
        if ("repair".equals(key)) {
            return R.drawable.ic_tools;
        }
        return R.drawable.ic_tools;
    }

    private Intent exportPage(String page) {
        Intent it = new Intent(this, ExportActivity.class);
        if (page != null && page.length() > 0) {
            it.putExtra("page", page);
        }
        return it;
    }

    /** 网页工具：把卡片标题也带进去，WebView 顶栏就显示站点名而不是「网页工具」。 */
    private Intent web(String title, String url) {
        Intent it = new Intent(this, WebToolActivity.class);
        it.putExtra(WebToolActivity.EXTRA_URL, url);
        it.putExtra(WebToolActivity.EXTRA_TITLE, title);
        return it;
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

    private void addCard(LinearLayout grid, Tool tool, int number, View header, String cat) {
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
        cardIndex.add(new CardRef(card, grid, header,
                (tool.title + " " + tool.sub + " " + catNameOf(cat)).toLowerCase(Locale.US), cat));
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
        for (String[] c : WEB_CATS) {
            chips.addView(chip(c[1], c[0]));
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
            t.setTextColor(on ? 0xFF15171A : DIM);
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

    private View card(Tool tool, int number) {
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

        if (tool.kind != KIND_FREE) {
            boolean open = ProAuth.isAuthorized(this);
            TextView kind = new TextView(this);
            kind.setText(open ? "已解锁" : (tool.kind == KIND_PAID ? "白名单" : "限制"));
            kind.setTextSize(10);
            kind.setLetterSpacing(0.08f);
            kind.setTextColor(open || tool.kind == KIND_PAID ? ACCENT : DIM);
            kind.setPadding(dp(8), 0, dp(8), 0);
            top.addView(kind);
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

    private void launch(final Tool tool) {
        if (tool.intent == null) {
            toast("该功能尚未上架");
            return;
        }
        if (tool.kind == KIND_LIMIT) {
            // 白名单账号直接放行：卡片角标显示的是「已解锁」，这里不放行就成了假解锁。
            // 不带 activation_token（目标页对 null token 已兼容，ActivationDialog.finish 会直接忽略）
            if (ProAuth.isAuthorized(this)) {
                try {
                    startActivity(tool.intent);
                } catch (ActivityNotFoundException e) {
                    toast("打不开这个功能");
                }
                return;
            }
            // 激活码：每次使用扣 1 次机会
            final String feature = tool.featureKey();
            ActivationDialog.ensureReady(this, tool.title, feature, () ->
                    ActivationDialog.beginUse(this, feature, new ActivationDialog.UseCallback() {
                        @Override
                        public void onToken(String operationToken) {
                            Intent it = tool.intent;
                            it.putExtra("activation_token", operationToken);
                            it.putExtra("activation_feature", feature);
                            try {
                                startActivity(it);
                            } catch (ActivityNotFoundException e) {
                                ActivationDialog.finish(MainActivity.this, feature, operationToken, false);
                                toast("打不开这个功能");
                            }
                        }

                        @Override
                        public void onDenied() {
                        }
                    }));
            return;
        }
        if (tool.kind == KIND_PAID && !ProAuth.isAuthorized(this)) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("需要白名单账号")
                    .setMessage("这个功能只对白名单开放。到「关于」页登录即可解锁 —— "
                            + "有密码用密码登录，没设置过密码就用「验证码登录」（邮箱收码）。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        try {
            startActivity(tool.intent);
        } catch (ActivityNotFoundException e) {
            toast("打不开这个功能");
        }
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

    /** PageFragment 用：按标签索引造页面，复用下面这些原有的页面构造方法。 */
    View buildPage(int index) {
        switch (index) {
            case 1:
                return pageTagged(1);
            case 2:
                return pagePaid();
            case 3:
                return pageTagged(3);
            case 4:
                return pageAbout();
            default:
                return pageTagged(0);
        }
    }

    /** 页面建好时登记：底部让位高度一算出来就补上。 */
    void registerPageView(View page) {
        if (page == null || pageViews.contains(page)) {
            return;
        }
        pageViews.add(page);
        if (pageBottomPad > 0) {
            page.setPadding(0, 0, 0, pageBottomPad);
        }
    }

    /** 页面销毁时注销。 */
    void unregisterPageView(View page) {
        pageViews.remove(page);
    }

    // ---------------------------------------------------------------- 白名单账号

    private String prName(ProAuth.Record r) {
        if (r.username != null && r.username.length() > 0) {
            return r.username;
        }
        if (r.email != null && r.email.length() > 0) {
            return r.email;
        }
        return "（未取到用户名）";
    }

    /**
     * 白名单账号登录：弹出双模式登录框（密码 / 邮件验证码）。
     * 没设置过密码的账号用验证码登录；登录成功且命中白名单才写入本地凭证。
     */
    private void showAccountLogin() {
        ProLoginDialog.show(this, record -> {
            toast("登录成功，白名单功能已解锁");
            recreate();
        });
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
