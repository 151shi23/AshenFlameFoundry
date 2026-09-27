<p align="center">
  <img src="docs/images/banner.jpg" width="440" alt="MinewaysMobile —— Convert your Minecraft worlds to 3D models on mobile">
</p>

# Mineways Mobile · 我的世界存档 → 3D 模型（Android）

> 把 Minecraft **Java / 基岩 / 网易**存档里的建筑导出成 **OBJ**（带贴图与材质），
> 全程在手机上离线完成：框选坐标 → 导出 → 内置 3D 预览 → 打包 ZIP 放进「下载」。
>
> Export Minecraft builds to **OBJ** (textures & materials included), entirely on-device:
> pick a region, export, preview in 3D, get a ZIP in your Downloads folder.

**本仓库不包含任何编译好的 APK / AAB。** 请按下文自行构建。
**This repository ships no prebuilt APK/AAB.** Build it yourself — see [构建 / Build](#构建--build).

> **特别鸣谢 / Special thanks**
> **蚩尤** —— 测试：找出多个重大 bug（导出选项失效、3D 预览、导出 0 方块、玻璃等方块漏导、维度错误、选择器三态…），并给出可复现的现象与可靠日志
> **ZERO寂灵** —— 策划与移植主导（功能取舍、与桌面版行为对齐、安卓端交互），**并参与测试，提供了同样可靠的日志**
>
> 两位的测试与日志，决定了这些问题是"无法复现"还是"已修复"。
>
> **蚩尤 (Chiyou)** — testing: surfaced several major bugs (broken export options, 3D preview, zero-block exports, missing block types such as glass, wrong dimension, the three-state selector…) with reproducible symptoms and reliable logs
> **ZERO寂灵 (ZERO Jiling)** — planning & porting lead (feature scoping, desktop-behaviour alignment, Android UX), **who also tested and supplied equally reliable logs**
>
> Their testing and logs are what turned these issues from "cannot reproduce" into "fixed".

---

## 目录 / Table of Contents

- [中文说明](#中文说明)
  - [功能](#功能)
  - [导出选项的三态：多选 / 单选 / 不选](#导出选项的三态多选--单选--不选)
  - [模组转换：模组地图 → OBJ](#模组转换模组地图--obj)
  - [环境要求](#环境要求)
  - [构建](#构建--build)
  - [目录结构](#目录结构)
  - [常见问题](#常见问题)
  - [鸣谢](#鸣谢)
- [English](#english)
  - [Features](#features)
  - [Export options: multi-select / single-select / none](#export-options-multi-select--single-select--none)
  - [Mod conversion: modded maps → OBJ](#mod-conversion-modded-maps--obj)
  - [Requirements](#requirements)
  - [Build](#build-1)
  - [Project layout](#project-layout)
  - [Troubleshooting](#troubleshooting)
  - [Acknowledgements](#acknowledgements)
- [许可 / License](#许可--license)
- [第三方组件 / Third-party](#第三方组件--third-party)
- [免责声明 / Disclaimer](#免责声明--disclaimer)

---

## 中文说明

### 转换效果：Minecraft 存档 → Prisma3D

左：存档里的建筑原样（主世界，黄黑条纹框架 + 内部小件）；右：**导出的 OBJ 直接在 Prisma3D（P3D）里打开** —— 几何、材质、贴图一一对应，没有错位、没有丢面。

<p align="center">
  <img src="docs/images/p3d-before.jpg" width="49%" alt="转换前：Minecraft 存档中的建筑">
  <img src="docs/images/p3d-after.jpg" width="49%" alt="转换后：在 Prisma3D 中打开 MinewaysMobile 导出的 OBJ">
</p>

<p align="center"><sub>转换前（Minecraft 存档）　→　转换后（Prisma3D 中打开导出模型）</sub></p>

### 功能

| 模块 | 说明 |
|---|---|
| **存档导入** | 选择存档文件夹（SAF）；可用 Shizuku 直接读取 `Android/data`；自动识别并就地解密**网易**存档；**基岩版**存档可用内置 Chunker 转成 Java 版后再导出 |
| **网易存档解密导出（独立页）** | 一页做完三件事：**① 找**——扫描网易版常见目录，三层兜底（直读 / **Shizuku 提权扫描**绕过 Android 11+ 对 `Android/data` 的隔离 / 手动指定文件夹）；**② 解密**——识别网易 XOR 加密头 `80 1D 30 01` 并推导密钥解密，**只作用于副本，绝不改动玩家原存档**；**③ 导出**——输出到 `下载/MinewaysMobile/网易存档/<世界名>/`，完成后该世界自动挂为「当前世界」，可直接转 OBJ |
| **框选导出** | 手填 / 粘贴 `X Y Z`（支持 `X: -7 Y: -53 Z: -7`、`/tp`、全角标点、小数，自动逐轴排序）|
| **维度** | 主世界 / 下界 / 末地 —— 建筑在哪个维度就选哪个（**选错维度会导出"另一张地图"**）|
| **OBJ 导出** | 材质模式 5 种（不导出材质 / 实体材质颜色 / 颜色噪点纹理 / 整幅大图 / 每方块一张 PNG）、纹理 RGB·A·RGBA、单独纹理目录名、Z 轴向上、旋转 0/90/180/270、围绕原点居中 |
| **网格与分块** | 简化网格（合并共面）、边界块面、实心树叶、按类型分割、创建分组对象、导出单个块、肥大块、花草/告示牌翻倍、合并平面块 |
| **3D 打印结构** | 掏空 / 超中空 / 壁厚、封闭入口、填侧隧道、填气泡、连接零件、连接转角、**焊接所有共享边**、删除浮动对象、融雪、复合叠加 |
| **3D 打印尺寸** | 按高度(cm) / 按壁厚最小化 / 每区块(mm) / 目标成本、模型单位、物理材料 |
| **面剔除** | 全显示 / 标准（隐藏 barrier、structure_void）/ 强剔除（再加 structure_block）|
| **3D 预览（独立界面）** | 开源 **three.js** 离线渲染 OBJ，自动读同目录 MTL 与贴图；单指旋转、双指缩放，可切线框/贴图/双面/自动旋转；也能直接挑任意 `.obj` 或先看 ZIP |
| **诊断报告** | 每次导出都生成可粘贴的报告：世界体检、选区命中、`[选区实测]`（读到的方块类型 / Y 分层 / 俯视高度图）、`[核心回执]`（核心自己记录的生效选项）、`[导出内容]`（实际写进 OBJ 的材质清单，含 glass 探测）、`[对等性自检]`（顶点包围盒 vs 核心声明尺寸：缩放比 / 是否越界 / 坐标轴方向）|
| **模组转换（实验性，默认关）** | 选区内**模组方块**（非 `minecraft:` 命名空间）也一并导出：可从模组 jar / 资源包解析方块模型与贴图，追加进核心导出的同一个 OBJ —— 详见 [模组转换](#模组转换模组地图--obj) |
| **PNG 转模型（独立界面）** | 把**纯色或全透明背景的像素图**（PNG）转成 Blockbench 立方体：前景像素 → 方块，UV 与像素一一对应，贴图内嵌；**一次导出四件套 `.bbmodel` + `.obj` + `.mtl` + `.png`**（转换器内置产物对账断言）。前景方块超过轻量预算时**自动按整数倍降采样重转**（外观不变、方块数降回预算），大图不再被拒绝。背景四角众数自动识别，容差可调（纯本地，无需联网） |
| **bb 模型转 OBJ（独立界面）** | 调用内置的**离线 Blockbench 内核**把 `.bbmodel` 转成 **OBJ + MTL + 贴图**：不必打开编辑器界面，转换后可一键保存三件套或打包 ZIP 分享；坐标按内核的 `model_export_scale` 还原，UV 直接沿用内核结果（不二次翻转） |
| **粒子编辑器（独立界面）** | 内置 **Snowstorm 离线版**（JannisX11，与 Blockbench 同一作者；GPL-3.0）：Minecraft 基岩版粒子效果的**可视化编辑**（时间轴、曲线、渐变、纹理/UV、事件触发、Molang）。**界面已汉化**（322 条对照表 + 11 条动态规则，只替换显示文本、不动任何逻辑值与表达式）；**导出**（Blob 下载被接管）直接写入 `下载/MinewaysMobile/粒子/`，**导入**走系统文件选择器读 `.particle.json`；全离线、无 CDN 依赖 |
| **内置工具** | 网页版 Blockbench（离线）、PNG 转模型、bb 模型转 OBJ、粒子编辑器（Snowstorm 离线 · 汉化）、小游戏《寂零快跑》、网易存档解密导出页、工具页 |

### 导出选项的三态：多选 / 单选 / 不选

「导出选项」面板（导出页的 **导出选项** 按钮，或长按「开始导出」）里的选择器具备三态语义：

| 状态 | 怎么操作 |
|---|---|
| **多选** | 开关（勾选框）随便勾，互不影响 |
| **单选** | 单选组内互斥，点谁是谁 |
| **不选** | 每个单选组**第一项就是「不选（用默认）」**；**再点一次已选中的那一项**也会回到「不选」 |

- **不选**以 `-1` 存盘，导出时按该键**默认值**发送 —— 行为等同于"从未设置过"，不会产生奇怪结果。
- 面板顶部另有 **全不选 / 全选 / 恢复默认** 三个快捷动作，改完立即生效（下次导出使用）。
- 所有选项**改动即保存**，无需额外确认。

### 模组转换：模组地图 → OBJ

**核心转换依旧是 Mineways 本身**，模组转换只是**叠在它后面的一层追加器**：先让 Mineways 核心照常把原版方块导出成 OBJ/MTL，再把选区内**模组方块**（不在 `minecraft:` 命名空间里的方块，如 `mymod:machine`）的几何与贴图**追加**进同一个 OBJ/MTL。核心代码一行未改。

#### 怎么用

1. 先**正常导出一次**（不勾模组转换）。报告里的 `[选区实测]` 会列出选区内读到的方块类型，模组方块就在里面。
2. 「导出选项」→ 打开 **模组转换（实验性，默认关）**。
3. 点 **选择模组文件**，可多选该存档用到的 **模组 jar** 或**资源包 zip**（要包含报告里那些方块名的模组）。
4. 再导出一次。报告末尾会出现 `[模组转换]` 段落，OBJ/MTL 里就多了模组几何。

#### 三步流程

| 步骤 | 实现 | 做什么 |
|---|---|---|
| ① 扫描 | `mod/ModBlockScanner.java` | **只读**扫选区覆盖的 `region/*.mca`（现代 Anvil 调色板格式）：取每个 section 的 `block_states.palette` 方块名 + `Properties`（键排序后拼成属性串，如 `facing=north,lit=true,`），再按调色板位宽解包 `data` 长整型数组还原每格。整段只有一种方块时 MC 会**省略 `data` 数组**，这种情况按 4096 格逐格铺开。名字带 `minecraft:` 的算原版（交给核心），其余计入模组。 |
| ② 解析 | `mod/ModModelResolver.java` | 从所选 jar/zip 索引 `assets/<命名空间>/models/block/*.json`、`blockstates/*.json`、`textures/*.png`；按 `variants` 条件打分选出模型，走 `parent` 继承链合并贴图（子覆盖父），把 `elements` 的 `from/to`（1/16 单位）与 `faces.uv` 展开成四边形，并解析 `#引用` 贴图链。 |
| ③ 输出 | `mod/ModOutput.java` | 核心导出的 OBJ/MTL 已就位后，把模组四边形**追加**进去（`mod_block_N_<方块状态>` 分组、材质名前缀 `mod_`）；坐标由「扫描器量到的原版方块世界包围盒」与「OBJ 实测顶点包围盒」反解，**不假设核心内部常量**。全程先写临时文件再拼接，失败时原版导出结果不受影响。 |

#### 坐标为什么是对的

Mineways 的**绝对坐标 OBJ** 在默认设置下是**与世界坐标同向**的：顶点 `objX = 世界X`、`objY = 世界Y`、`objZ = 世界Z`，**1 单位 = 1 方块**（`ObjFileManip.cpp` 里顶点是 `(anchor - gModel.center) * scale`，未居中时 `gModel.center` 取 `gWorld2BoxOffset = 1 - 选区min`，两式相减正好等于世界坐标本身，没有任何左右翻转）。模组段严格按同一条公式落地，并额外做两道校验：

- **实测标定**：用「原版方块在 OBJ 里的实际包围盒」与「扫描器量到的世界包围盒」反解出三轴平移量，而不是硬编码常量。正常情况偏移解出来就是 `0`（报告里写 `坐标标定：与世界坐标同向（核心不做 X 镜像），偏移=(0, 0, 0)`）；核心做「居中模型」这类**纯平移**改动时也能自动跟上。
- **跨度护栏**：把 OBJ 实测跨度与扫描器量到的原版方块跨度互相印证。两者出自同一批方块，正常必须相等；若相差超过 2.5（说明核心对模型做了**整体缩放或换轴旋转**，例如 3D 打印尺寸 / 旋转 / Z 向上），则**跳过合并并在报告里说明原因** —— 宁可不出模组段，也不把方块放歪。

另外**材质按「贴图」建、逐面切换 `usemtl`**：柱、工作台这类不同面用不同贴图的方块，不会所有面都贴上第一张图。

#### 纯模组选区

如果选区内**一个原版方块都没有**（纯模组建筑），Mineways 核心按设计会返回 `files=0 / err=512`（没有任何方块），连 OBJ 都不会产出。这时模组转换会**新建**一个 OBJ（并补上缺失的 `mtllib` 头，否则查看器不会去读 MTL、模组方块会变成白模），整个导出仍算成功并照常打包 ZIP，报告里会写明「核心没在选区内找到原版方块，已新建仅含模组方块的 OBJ」。

#### 已知限制

- **只支持绝对坐标 OBJ**：本版本输出格式固定为绝对坐标（`FILE_TYPE_WAVEFRONT_ABS_OBJ`），相对坐标模式未做适配。
- **只认现代 Anvil 调色板格式**（`block_states.palette` / `data`）的区块。
- **降级为占位色立方体**的情况：模组父模型继承链超过一级、`multipart` 方块（栅栏、楼梯、红石线一类）、连接性方块，以及所选 jar 里**找不到**对应模型/贴图的方块。报告会给出"解析成功 N 种，降级 M 种"。
- **只处理方块模型**（`models/block/`），实体与物品模型不处理。
- 扫描器把 `minecraft:` 命名空间一律算原版：若核心不认其中某个方块（例如水、或被面剔除/过滤掉的方块），跨度护栏会**直接跳过合并**并说明原因。

#### 在报告里核对

```
[模组转换] 选区内模组方块 3 种 / 148 个实例
坐标标定：与世界坐标同向（核心不做 X 镜像），偏移=(0, 0, 0)
解析成功 3 种，降级 0 种（缺模型/multipart/连接性方块 → 占位色立方体）
已合并进 OBJ/MTL（模组段独立成组，材质名前缀 mod_）
```

- `偏移` 正常应为 `(0, 0, 0)`；`与世界坐标同向` 是核心的默认约定，不是错误。
- 想确认哪些方块没贴上正确贴图，就在 MTL 里查 `mod_` 开头的材质；想确认哪一段是模组几何，就在 OBJ 里查 `mod_block_` 开头的分组。

### 环境要求

| 项 | 值 |
|---|---|
| JDK | **17**（`sourceCompatibility = 17`）|
| Android SDK | 平台 **34**、Build-Tools **34.x** |
| NDK | **26.3.11579264**（`ndkVersion`）|
| CMake | 随 SDK 安装即可（`app/src/main/cpp/CMakeLists.txt`）|
| Gradle | 使用仓库自带 wrapper（8.14.5）|
| 设备 | Android **8.0+（API 26）**；ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64` |

> **Windows 提示**：Android Gradle Plugin 拒绝**含非 ASCII 字符的工程路径**（中文目录会直接构建失败）。
> 克隆到纯英文路径（如 `C:\dev\MinewaysMobile`）再构建；仓库自带 `tools/build_release.ps1` 也会先把源码复制到 `C:\mmbuild` 这类纯 ASCII 目录再编译。

> 原生库已按 **16 KB 页**对齐（`arm64-v8a` / `x86_64`），可在 Android 15+ 的 16 KB 页设备上正常加载。

### 构建 / Build

```bash
# 1) 环境变量（换成你自己的路径）
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/path/to/jdk-17
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"

# 2) 写本机 SDK 路径（该文件已被 .gitignore 忽略）
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3) 调试包
./gradlew :app:assembleDebug
#    产物：app/build/outputs/apk/debug/app-debug.apk

# 4) 正式包（未签名，自行用 apksigner 签名）
./gradlew :app:assembleRelease
#    产物：app/build/outputs/apk/release/app-release-unsigned.apk

# 5) 只编译原生核心（可选，排查 C++ 问题）
./gradlew :app:externalNativeBuildRelease

# 6) 单元测试（验证「导出选项」选择器的多选 / 单选 / 不选三态）
./gradlew :app:testDebugUnitTest
#    报告：app/build/reports/tests/testDebugUnitTest/index.html
```

签名示例（**不要把 keystore 提交进仓库**）：

```bash
zipalign -f -P 16 4 app-release-unsigned.apk app-aligned.apk
apksigner sign --ks my-release.jks --ks-key-alias myalias \
  --out MinewaysMobile-release.apk app-aligned.apk
```

#### Windows 一键脚本

```powershell
powershell -ExecutionPolicy Bypass -File neteasemc\tools\build_release.ps1
```

参数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `-StorePass` | `Mineways2026` | 密钥库口令（store 与 key 同一口令）；留空时先读环境变量 `MINEWAYS_STOREPASS`，再交互输入 |
| `-Alias` | `mineways` | **必须与 keystore 里实际存在的 PrivateKeyEntry 别名一致**，否则报 `entry ... does not contain a key` |
| `-Sandbox` | `C:\mmbuild` | 纯 ASCII 构建沙箱 |
| `-SkipSign` | — | 只构建不签名 |
| `-Offline` | — | 强制 Gradle 离线（依赖已全部缓存时更快）|

脚本会依次做六件事：清空并重建沙箱 → `gradle :app:assembleDebug :app:assembleRelease` → `zipalign -P 16` + `apksigner` 签名 → 核验（证书指纹、`aapt2` 校验 Activity 注册与小游戏/Blockbench 资产是否在包内）→ **Java 9+ 类型自检**（扫 dex 里是否引用了旧 Android 上不存在的 `System$Logger` / `Module` / `HexFormat` / `StackWalker` 等）→ 把成品复制到工作区根目录与 `产物\`。

> **脚本文件必须保存为 UTF-8 with BOM。** Windows PowerShell 5.1 会按 ANSI 解析无 BOM 的文件，中文路径与提示会全部乱码，甚至报 `Error formatting a string`。编辑保存时请保留 BOM。
>
> 沙箱复制阶段可能出现 `robocopy ... being used by another process`：那是 IDE 或 Gradle 守护进程占用了 `C:\mmbuild\app\build` 下的 dex 文件，**不影响构建结果**；想消除告警就先关掉打开该目录的 IDE 或执行 `gradle --stop`。

### 目录结构

```
app/
  src/main/java/com/mineways/     # 界面与安卓侧逻辑（导出、预览、转换、解密…）
      mod/                        # 模组转换（扫描模组方块 / 解析模型贴图 / 追加进 OBJ）
  src/main/cpp/                   # JNI 入口 + 导出诊断
      core/                       # Mineways 核心（读档、网格生成、OBJ/MTL 写出）
  src/main/assets/
      objviewer/                  # 离线 3D 预览器（three.js + OBJLoader/MTLLoader/OrbitControls）
      blockbench/                 # 内置网页版 Blockbench
      bbengine/                   # bb 模型转 OBJ 的 WebView 转换引擎（复用上面的 Blockbench 内核）
      minigame/                   # 内置小游戏《寂零快跑》
      convert/                    # Chunker 转换所需映射资源
chunker-core/                     # 基岩版 → Java 版世界转换（Chunker）
tools/                            # 构建 / 审计 / 离线化脚本
```

### 常见问题

**导出 0 个方块？** 看报告里的 `[选区命中]` 与 `[选区实测]`：多半是 **Y 范围没盖住地形**，或**维度选错**（建筑在下界/末地却按主世界导）。报告会直接给出方块实际所在的 Y 范围。

**某个方块没导出（例如玻璃）？** 看 `[导出内容]`：它列出**实际写进 OBJ 的材质清单**，并单独探测名字含 `glass` 的材质。

- 清单里**有** → 已经导出了，看不到多半是查看器的**材质透明度/显示**问题（把该材质设为 Alpha Blend，或直接用 App 内置 3D 预览）。
- 清单里**没有** → 结合 `[选区实测]`（每种方块标注 `[会导出]` / `[被过滤]`）判断是被过滤，还是压根不在选区内。

**导出结果在哪里？** 勾选「导出后打包 ZIP 到「下载/MinewaysMobile」」后，ZIP 出现在公共「下载」目录（Android 10+ 走 MediaStore，无需权限）；Android 8/9 首次会申请存储权限，未授权则退回应用私有目录。

**怎么确认导出的 OBJ 与存档"完全对等"（无任何不对等）？** 看报告里的 `[对等性自检]`，它用**实测**给出三个判定：
① **缩放比** 应为 `1.000`（即 1 单位 = 1 方块，未被 3D 打印尺寸污染）；
② 模型**是否超出选区**（应为"未超出"）；
③ **坐标轴方向**（Mineways 绝对坐标 OBJ 与世界坐标同向，X/Y/Z 都是；核心本身不做镜像，自检只有在选区恰好对称于 `x = -0.5`、两种假设重合时才会显示"镜像"）。
再结合 `[导出内容]`（实际写进模型的材质清单）与 `[选区实测]`（读到的方块类型 / Y 分层），即可确认"读进来 → 写出去"全链路一致。

**为什么有的选项勾了看起来没变化？** 选项效果多发生在 OBJ 内部结构或几何细节上（分组、焊接、掏空…）。报告里的 `[核心回执]` 是**核心自己写的生效状态**，一眼可核对是否真的生效。

**开了模组转换，OBJ 里还是没有模组方块？** 按报告里的 `[模组转换]` 段落逐条看：

| 报告现象 | 原因与处理 |
|---|---|
| `选区内没有模组方块（原版方块由核心处理）` | 选区内确实只有 `minecraft:` 方块；确认框选真的盖住了模组建筑 |
| `未能从所选文件解析出任何模型/方块状态` | 选的文件不是模组 jar / 资源包；换上**包含这些方块名**的模组再导 |
| `已跳过合并：核心对模型做了整体缩放或换轴旋转…` | 跨度护栏生效：多半是开了 3D 打印尺寸 / 旋转 / Z 向上。关掉这些选项再导，模组段才能与原版对齐 |
| `解析成功 N 种，降级 M 种` | 降级的方块被画成**占位色立方体**（父模型链过深 / `multipart` / 缺贴图），几何位置仍然正确 |
| 报告正常但看不到模组方块 | 用 App 内置 3D 预览，或在 OBJ 里搜 `mod_block_` 分组 / 在 MTL 里搜 `mod_` 材质，确认是否只是查看器没加载贴图 |

**纯模组建筑（选区内没有原版方块）导出时报"没有找到方块"？** 3.9 起不会了：核心返回 `files=0` 时，模组转换会新建 OBJ 并补 `mtllib` 头，导出照常成功、ZIP 照常打包。但仍**必须先打开模组转换并选好模组文件**，否则确实拿不到任何几何。

### 鸣谢

本项目能走到今天，靠的是下面两位的持续投入 —— **同等重要，只是分工不同**：

| 贡献者 | 角色 | 具体贡献 |
|---|---|---|
| **蚩尤** | 测试 | 测出并推动修复了**多个重大 bug**：导出选项点了不生效、3D 预览打不开、导出 0 方块（`MW_NO_BLOCKS_FOUND`）、玻璃等方块漏导、维度不对导致导出"货不对版"、单选组选上了取消不了、导出选项选择器三态等。每次都给出可复现的现象与**可靠日志/导出报告**，这是问题能被定位的关键。|
| **ZERO寂灵** | 策划 / 移植 **+ 测试** | 主导项目的策划与移植：功能取舍与优先级、与桌面版 Mineways 行为对齐（选项语义、默认值、导出结果一致性）、安卓端交互与整体推进节奏；**同时参与测试，提供了同样可靠的日志**，与蚩尤的反馈一起构成完整的复现链路。|

**两位的测试同等重要**：一人负责的问题面、另一人负责的复现细节，缺一不可 ——
这些 bug 大多在手机上才会出现，没有可靠的日志与现象描述，就无法定位，更无法验证修复。

同时感谢上游开源项目：**Mineways**（Eric Haines）、**Chunker**（hivemc）、**three.js**、**Blockbench**、**lodepng** 等（许可见 [`THIRD_PARTY.md`](THIRD_PARTY.md)）；以及每一位提交反馈、帮忙测试的朋友。

---

## English

### Before / after: Minecraft save → Prisma3D

Left: the build as it stands in the save (Overworld — the hazard-striped frame plus everything inside it). Right: **the exported OBJ opened directly in Prisma3D (P3D)** — geometry, materials and textures line up one to one: nothing shifted, no missing faces.

<p align="center">
  <img src="docs/images/p3d-before.jpg" width="49%" alt="Before: the build inside the Minecraft save">
  <img src="docs/images/p3d-after.jpg" width="49%" alt="After: the exported OBJ opened in Prisma3D">
</p>

<p align="center"><sub>Before (Minecraft save)　→　After (exported model opened in Prisma3D)</sub></p>

### Features

| Module | What it does |
|---|---|
| **World import** | Pick a save folder (SAF); optional Shizuku access to `Android/data`; automatic in-place decryption of **NetEase** saves; **Bedrock** saves can be converted to Java with the bundled Chunker |
| **NetEase save decrypt & export (dedicated page)** | Three steps on one page: **① find** — scans the usual NetEase locations with three fallbacks (direct read / **Shizuku-elevated scan** that bypasses the Android 11+ `Android/data` isolation / manual folder pick via SAF); **② decrypt** — detects the NetEase XOR header `80 1D 30 01`, derives the key and decrypts, **working only on a copy so the player's save is never modified**; **③ export** — writes to `Download/MinewaysMobile/网易存档/<world>/` and then registers that world as the current one so it can be exported to OBJ right away |
| **Region export** | Type or paste `X Y Z` (`X: -7 Y: -53 Z: -7`, `/tp`, full-width punctuation, decimals — auto-sorted per axis) |
| **Dimension** | Overworld / Nether / The End — pick the one your build lives in (**a wrong dimension exports a different map**) |
| **OBJ export** | 5 material modes (none / solid colors / swatch texture / full-sheet mosaic / one PNG per block), RGB·A·RGBA flags, tile folder name, Z-up, rotation 0/90/180/270, centering |
| **Mesh & grouping** | Decimate (merge coplanar faces), border faces, solid leaves, split by block type, make groups/objects, individual blocks, fatten blocks, double billboards, merge flat-top blocks |
| **3D-print structure** | Hollow / super-hollow / wall thickness, seal entrances, seal side tunnels, fill bubbles, connect parts, connect corner tips, **connect all shared edges**, delete floaters, melt snow, composite overlay |
| **3D-print sizing** | By height (cm) / minimize by wall thickness / mm per block / target cost, units, physical material |
| **Culling schemes** | Show all / Standard (hide barrier & structure_void) / Aggressive (also structure_block) |
| **3D preview (dedicated screen)** | Open-source **three.js** renders the OBJ offline and auto-loads the sibling MTL + textures; one-finger orbit, pinch zoom, wireframe / texture / double-side / autorotate toggles; you can also pick any `.obj` directly or preview a ZIP |
| **Diagnostics report** | Every export yields a copy-pasteable report: world health check, selection hit stats, `[选区实测]` (block types read / Y bands / top-down height map), `[核心回执]` (the core's own record of effective options), `[导出内容]` (materials actually written to the OBJ, incl. a glass probe), `[对等性自检]` (vertex bounding box vs the core's declared size: scale ratio / out-of-selection / axis orientation) |
| **Mod conversion (experimental, off by default)** | Exports **modded blocks** (anything outside the `minecraft:` namespace) too: resolves their models and textures from mod jars / resource packs and appends them into the same OBJ the core produced — see [Mod conversion](#mod-conversion-modded-maps--obj) |
| **PNG to model (dedicated screen)** | Turns a **flat-background (or fully transparent) pixel-art PNG** into Blockbench cubes: foreground pixels → cubes, UVs map to pixels, texture embedded; **one export produces all four files `.bbmodel` + `.obj` + `.mtl` + `.png`** (the converter self-checks its output). When the cube count exceeds the lightweight budget it **automatically downsamples by an integer factor and re-converts** (same look, cubes back in budget) — large images are no longer rejected. Background is auto-detected from the corner colours; tolerance is adjustable (fully local) |
| **bbmodel to OBJ (dedicated screen)** | Drives the bundled **offline Blockbench kernel** to convert a `.bbmodel` into **OBJ + MTL + textures**: no editor UI involved; save the three files or share them as a ZIP. Vertex coordinates are restored by the kernel's `model_export_scale`, and UVs are taken from the kernel as-is (no second flip) |
| **Particle editor (dedicated screen)** | Bundles the **offline Snowstorm** (by JannisX11, same author as Blockbench; GPL-3.0), the visual editor for Minecraft Bedrock particle effects (timeline, curves, gradients, texture/UV, event triggers, Molang). The **UI is localised to Chinese** (322-entry lookup table + 11 dynamic rules that only replace displayed text — logic values and expressions are never touched). **Export** (the Blob download path is intercepted) writes straight into `Download/MinewaysMobile/粒子/`; **import** uses the system file picker for `.particle.json`. Fully offline, no CDN |
| **Built-ins** | Offline web Blockbench, PNG to model, bbmodel to OBJ, particle editor (offline Snowstorm, Chinese UI), the mini-game 《寂零快跑》, NetEase save decrypt/export page, tools page |

### Export options: multi-select / single-select / none

The **Export options** panel (button on the export page, or long-press *Start export*) uses three-state semantics:

| State | How |
|---|---|
| **Multi-select** | Checkboxes — any combination, including none |
| **Single-select** | Radio groups are mutually exclusive |
| **None** | Every radio group starts with **“不选（用默认） / none (use default)”**, and **tapping the already-selected item again clears it** |

- “None” is stored as `-1` and sent as **that key's default value** at export time — identical to “never set”.
- The panel header also offers **clear all / select all / restore defaults**, applied immediately.
- Changes are saved on the spot; the next export uses them.

### Mod conversion: modded maps → OBJ

**The core conversion is still Mineways itself.** Mod conversion is only an **append layer stacked on top of it**: the Mineways core exports the vanilla blocks to OBJ/MTL exactly as before, and then the modded blocks in the selection (anything not in the `minecraft:` namespace, e.g. `mymod:machine`) have their geometry and textures **appended** into that same OBJ/MTL. Not a single line of the core was changed.

#### How to use it

1. Export normally once (mod conversion off). The report's `[选区实测]` lists the block types found in the selection — your modded blocks are among them.
2. Open **Export options** → enable **Mod conversion (experimental, off by default)**.
3. Tap **Choose mod files** and multi-select the **mod jars** or **resource-pack zips** that contain those block names.
4. Export again. A `[模组转换]` block appears at the end of the report and the OBJ/MTL now includes the modded geometry.

#### The three stages

| Stage | Implementation | What it does |
|---|---|---|
| ① Scan | `mod/ModBlockScanner.java` | **Read-only** scan of the `region/*.mca` files covering the selection (modern Anvil palette format): reads each section's `block_states.palette` names plus `Properties` (keys sorted into a property string such as `facing=north,lit=true,`), then unpacks the `data` long array using the palette bit width to recover every cell. When a whole section holds a single block type, Minecraft **omits the `data` array** — that case is expanded across all 4096 cells. Names under `minecraft:` count as vanilla (left to the core); everything else is a modded block. |
| ② Resolve | `mod/ModModelResolver.java` | Indexes `assets/<namespace>/models/block/*.json`, `blockstates/*.json` and `textures/*.png` inside the chosen jars/zips; scores `variants` conditions to pick a model, walks the `parent` chain merging textures (child overrides parent), and expands `elements` (`from`/`to` in 1/16 units, `faces.uv`) into quads, resolving `#ref` texture chains. |
| ③ Output | `mod/ModOutput.java` | Once the core's OBJ/MTL exists, the modded quads are **appended** (`mod_block_N_<block state>` groups, `mod_` material prefix). Coordinates are solved from *“the vanilla world bounding box measured by the scanner”* vs *“the OBJ's measured vertex bounding box”* — **no assumption about core internals**. Everything goes through a temp file first, so a failure never damages the vanilla export. |

#### Why the coordinates are right

An **absolute-coordinate Mineways OBJ** is **axis-aligned with world coordinates** under the default settings: vertex `objX = worldX`, `objY = worldY`, `objZ = worldZ`, and **1 unit = 1 block** (in `ObjFileManip.cpp` a vertex is `(anchor - gModel.center) * scale`; when not centered, `gModel.center` is `gWorld2BoxOffset = 1 - selection min`, so the subtraction yields the world coordinate itself — there is no left/right flip). The modded geometry uses exactly the same formula, plus two extra checks:

- **Measured calibration**: the three-axis translation is solved from the vanilla blocks' *actual* bounding box in the OBJ together with the world bounding box the scanner measured, not hard-coded. In the normal case the offset solves to `0` (report line `坐标标定：与世界坐标同向（核心不做 X 镜像），偏移=(0, 0, 0)`); pure-translation core options such as "center model" are followed automatically.
- **Span guard**: the OBJ's measured span is cross-checked against the vanilla span the scanner measured. Both come from the same blocks, so they must match; if they differ by more than 2.5 (meaning the core **scaled or re-oriented the whole model** — 3D-print sizing, rotation, Z-up…), the merge is **skipped with an explanation in the report** — better no modded geometry than blocks placed wrongly.

Materials are also created **per texture, switching `usemtl` face by face**, so pillars, crafting tables and other multi-texture blocks don't end up with the first texture on every face.

#### Pure-mod selections

If the selection contains **no vanilla blocks at all** (a purely modded build), the Mineways core by design returns `files=0 / err=512` (no blocks) and writes no OBJ. Mod conversion then **creates** the OBJ (adding the `mtllib` line it would otherwise lack — without it viewers never load the MTL and the modded blocks render untextured), the export still counts as successful and still gets zipped, and the report states “核心没在选区内找到原版方块，已新建仅含模组方块的 OBJ”.

#### Known limitations

- **Absolute-coordinate OBJ only**: this version's output format is fixed to absolute coordinates (`FILE_TYPE_WAVEFRONT_ABS_OBJ`); relative mode is not adapted.
- **Modern Anvil palette format only** (`block_states.palette` / `data`).
- **Fall back to a placeholder-coloured cube**: mod models whose parent chain goes deeper than one level, `multipart` blocks (fences, stairs, redstone wire…), connectivity blocks, and blocks whose model/texture is **not found** in the chosen jars. The report prints “解析成功 N 种，降级 M 种”.
- **Block models only** (`models/block/`); entity and item models are not handled.
- The scanner treats everything under `minecraft:` as vanilla: if the core doesn't recognise one of those blocks (water, or a block removed by culling/filters), the span guard **skips the merge** and says why.

#### Verifying it from the report

```
[模组转换] 选区内模组方块 3 种 / 148 个实例
坐标标定：与世界坐标同向（核心不做 X 镜像），偏移=(0, 0, 0)
解析成功 3 种，降级 0 种（缺模型/multipart/连接性方块 → 占位色立方体）
已合并进 OBJ/MTL（模组段独立成组，材质名前缀 mod_）
```

- The `偏移` (offset) should normally be `(0, 0, 0)`; `与世界坐标同向` (axis-aligned with world coordinates) is the core's default convention, not a bug.
- To find textures that failed, search the MTL for materials starting with `mod_`; to find which geometry is modded, search the OBJ for `mod_block_` groups.

### Requirements

| Item | Value |
|---|---|
| JDK | **17** |
| Android SDK | Platform **34**, Build-Tools **34.x** |
| NDK | **26.3.11579264** |
| CMake | installed with the SDK (`app/src/main/cpp/CMakeLists.txt`) |
| Device | Android **8.0+ (API 26)**; ABIs `arm64-v8a`, `armeabi-v7a`, `x86_64` |

> **Windows note:** the Android Gradle Plugin refuses project paths containing **non-ASCII characters**
> (a CJK directory name fails the build outright). Clone into a plain-ASCII path such as
> `C:\dev\MinewaysMobile`. The bundled `tools/build_release.ps1` also copies sources into an ASCII
> sandbox (`C:\mmbuild`) before compiling.

> Native libraries are **16 KB-page aligned** for `arm64-v8a` / `x86_64`, so they load on Android 15+ 16 KB-page devices.

### Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/path/to/jdk-17
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
echo "sdk.dir=$ANDROID_HOME" > local.properties      # gitignored

./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # unsigned; sign it yourself with apksigner
./gradlew :app:testDebugUnitTest    # unit tests for the export-option selector
```

```bash
zipalign -f -P 16 4 app-release-unsigned.apk app-aligned.apk
apksigner sign --ks my-release.jks --ks-key-alias myalias \
  --out MinewaysMobile-release.apk app-aligned.apk
```

On Windows, a PowerShell pipeline is provided in `tools/build_release.ps1`:

```powershell
powershell -ExecutionPolicy Bypass -File neteasemc\tools\build_release.ps1
```

| Parameter | Default | Notes |
|---|---|---|
| `-StorePass` | `Mineways2026` | Keystore password (same for store and key); if empty, the `MINEWAYS_STOREPASS` env var is read, then it prompts |
| `-Alias` | `mineways` | **Must match an actual PrivateKeyEntry alias in the keystore**, otherwise apksigner fails with `entry ... does not contain a key` |
| `-Sandbox` | `C:\mmbuild` | Plain-ASCII build sandbox |
| `-SkipSign` | — | Build only, don't sign |
| `-Offline` | — | Force Gradle offline (faster when all dependencies are cached) |

The script does six things in order: wipe and rebuild the sandbox → `gradle :app:assembleDebug :app:assembleRelease` → `zipalign -P 16` + `apksigner` → verify (certificate fingerprint; `aapt2` checks that the Activities are registered and that the mini-game / Blockbench assets are inside the APK) → **Java 9+ type audit** (scans the dex files for references to `System$Logger` / `Module` / `HexFormat` / `StackWalker`, absent on older Android) → copy the artifacts to the workspace root and `产物\`.

> **The script file must be saved as UTF-8 with BOM.** Windows PowerShell 5.1 parses BOM-less files as ANSI, garbling every Chinese path and message and even failing with `Error formatting a string`. Keep the BOM when editing.
>
> You may see `robocopy ... being used by another process` during the sandbox copy: an IDE or Gradle daemon is holding dex files under `C:\mmbuild\app\build`. It **does not affect the build**; to silence it, close that directory in your IDE or run `gradle --stop`.

### Project layout

```
app/src/main/java/com/mineways/     # UI + Android-side logic (export, preview, convert, decrypt…)
app/src/main/java/com/mineways/mod/ # Mod conversion (scan modded blocks / resolve models / append to OBJ)
app/src/main/cpp/                   # JNI entry points + export diagnostics
app/src/main/cpp/core/              # Mineways core (world reading, meshing, OBJ/MTL writing)
app/src/main/assets/objviewer/      # Offline 3D viewer (three.js + OBJLoader/MTLLoader/OrbitControls)
app/src/main/assets/blockbench/     # Bundled web Blockbench
app/src/main/assets/minigame/       # Bundled mini-game
chunker-core/                       # Bedrock → Java world conversion (Chunker)
tools/                              # Build / audit / offline-asset scripts
```

### Troubleshooting

**Export produced 0 blocks?** Check `[选区命中]` and `[选区实测]`. Usually the **Y range misses the terrain** or the **dimension is wrong** (a Nether build exported as Overworld). The report prints the actual Y range of the blocks.

**A block is missing from the OBJ (e.g. glass)?** Look at `[导出内容]`: it lists the **materials actually written**, plus a dedicated probe for names containing `glass`.

- Present → it *was* exported; you probably can't see it because of the viewer's material transparency/display settings (switch that material to Alpha Blend, or just use the in-app 3D preview).
- Absent → combine with `[选区实测]` (each block type is tagged `[会导出]` / `[被过滤]`) to tell filtering apart from “not in the selection at all”.

**Where do exports go?** With “package a ZIP into Downloads/MinewaysMobile” enabled, the ZIP lands in the public Downloads folder (Android 10+ uses MediaStore, no permission needed). On Android 8/9 the app asks for storage permission on first use; if denied, results fall back to the app-private folder.

**How do I confirm the exported OBJ matches the save exactly (no discrepancy)?** Read `[对等性自检]` in the report — it *measures* three things:
① the **scale ratio**, which must be `1.000` (1 unit = 1 block, unaffected by 3D-print sizing);
② whether the model **stays inside the selection** (it should);
③ the **axis orientation** (an absolute-coordinate Mineways OBJ is axis-aligned with world coordinates, X/Y/Z — the diagnostic only flags a *mirror* when the selection happens to be symmetric about `x = -0.5`, in which case both hypotheses coincide; the core itself never mirrors).
Together with `[导出内容]` (what was actually written) and `[选区实测]` (what was read), this verifies the whole "read → write" chain.

**Why do some options look like they do nothing?** Most options affect OBJ internals or geometry details (grouping, welding, hollowing…). The `[核心回执]` section of the report is the **core's own record** of what actually took effect — check it there.

**Mod conversion is on, but the OBJ still has no modded blocks?** Read the `[模组转换]` section of the report line by line:

| What the report says | Cause and what to do |
|---|---|
| `选区内没有模组方块（原版方块由核心处理）` | The selection really only contains `minecraft:` blocks; make sure the box actually covers the modded build |
| `未能从所选文件解析出任何模型/方块状态` | The file is not a mod jar / resource pack; pick a mod that **contains these block names** and export again |
| `已跳过合并：核心对模型做了整体缩放或换轴旋转…` | The span guard tripped: usually 3D-print sizing / rotation / Z-up is enabled. Turn those off so the modded section can align with vanilla geometry |
| `解析成功 N 种，降级 M 种` | Degraded blocks are drawn as **placeholder colour cubes** (deep parent chain / `multipart` / missing texture); their position is still correct |
| Report looks fine but you can't see modded blocks | Use the in-app 3D preview, or search the OBJ for `mod_block_` groups / the MTL for `mod_` materials to confirm it is only the viewer failing to load textures |

**Mod-only build (no vanilla block in the selection) reports “no blocks found”?** Not since 3.9: when the core returns `files=0`, mod conversion creates the OBJ itself and adds the missing `mtllib` header, so the export still succeeds and the ZIP is still published. You do still have to **enable mod conversion and pick the mod files** first — otherwise there is genuinely no geometry to write.

### Acknowledgements

This project got where it is thanks to two people who kept at it — **equal credit, different roles**:

| Contributor | Role | Contribution |
|---|---|---|
| **蚩尤 (Chiyou)** | Testing | Found and drove fixes for **several major bugs**: export options that had no effect, the 3D preview not opening, exports producing 0 blocks (`MW_NO_BLOCKS_FOUND`), missing block types (e.g. glass), "wrong map" exports caused by the missing dimension switch, radio options that could not be cleared, and the three-state option selector. Every report came with reproducible symptoms and **reliable logs/export reports** — that is what made them fixable. |
| **ZERO寂灵 (ZERO Jiling)** | Planning / Porting **+ testing** | Led planning and the port: feature scoping and priorities, aligning behaviour with desktop Mineways (option semantics, defaults, output consistency), plus the Android UX and overall pace. **Also tested, supplying equally reliable logs** that completed the reproduction chain together with Chiyou's reports. |

**Both contributions to testing carry equal weight:** one covered a different surface of the app, the other
documented the reproduction details. Most of these bugs only appear on a real device — without reliable logs
and symptom descriptions they could neither be located nor verified as fixed.

Thanks as well to the upstream open-source projects — **Mineways** (Eric Haines), **Chunker** (hivemc), **three.js**, **Blockbench**, **lodepng** and others (see [`THIRD_PARTY.md`](THIRD_PARTY.md)) — and to everyone who reports issues or helps test.

---

## 许可 / License

本仓库以 **GNU General Public License v3.0（GPL-3.0）** 发布，全文见 [`LICENSE`](LICENSE)。

This repository is released under the **GNU General Public License v3.0 (GPL-3.0)** — see [`LICENSE`](LICENSE).

之所以选 GPL-3.0：仓库同时包含 **GPL-3.0** 的组件（Chunker 世界转换、网易存档解密适配），
而 GPL 具有传染性。**再分发（含修改后分发）请同样以 GPL-3.0 开源。**

GPL-3.0 was chosen because the repository also contains GPL-3.0 components (Chunker world
conversion and the NetEase save decryption adapter), and the GPL is copyleft. **Redistribution,
including modified versions, must remain GPL-3.0.**

## 第三方组件 / Third-party

详见 [`THIRD_PARTY.md`](THIRD_PARTY.md)：Mineways 核心、Chunker、three.js（MIT，含必须保留的声明）、
Blockbench（MIT）、lodepng（zlib）、region.cpp（BSD-2-Clause）、AndroidX/Material（Apache-2.0）等。

See [`THIRD_PARTY.md`](THIRD_PARTY.md) for the full list — Mineways core, Chunker, three.js (MIT,
notice required), Blockbench (MIT), lodepng (zlib), region.cpp (BSD-2-Clause), AndroidX/Material
(Apache-2.0), and others.

## 免责声明 / Disclaimer

本项目**不是**官方产品，与 Mojang Studios、Microsoft、网易均无隶属或背书关系。
Minecraft 相关商标与素材归各自权利人所有。请仅对你**合法拥有**的存档使用本工具，
并遵守对应版本的最终用户许可协议（EULA）。导出模型的使用请同样遵守相关协议。

This project is **not** affiliated with, endorsed by, or sponsored by Mojang Studios, Microsoft,
or NetEase. Minecraft-related trademarks and assets belong to their respective owners. Use this
tool only with saves you legally own, and comply with the applicable End User License Agreement.
