# 第三方组件与许可 / Third-Party Components and Licenses

本仓库把若干开源项目作为**源码或静态资源**内置（便于离线使用）。它们各自的权利归原作者，
许可以下方表格与各自源码头部为准。**如果你要再分发（尤其是商用），请逐项核对上游许可。**

This repository bundles several open-source projects as source code or static assets so the app
works fully offline. Each remains the property of its authors; the authoritative license text is
the one shipped in the upstream project and/or in the file headers listed below.

| 组件 / Component | 位置 / Location | 上游 / Upstream | 许可 / License |
|---|---|---|---|
| Mineways 核心（C++：读档、网格生成、OBJ/MTL 导出） | `app/src/main/cpp/core/**` | erich666/Mineways | 见上游仓库（本仓库按其条款使用）/ see upstream |
| Chunker（基岩版 → Java 版世界转换） | `chunker-core/**` | hivemc/chunker | GPL-3.0 |
| 网易存档解密适配 | `app/src/main/java/com/mineways/NeteaseDecryptor.java` | 本项目 / 源码内声明 | GPL-3.0（源码头部已声明） |
| three.js r134（OBJ 预览渲染）<br>+ OBJLoader / MTLLoader / OrbitControls | `app/src/main/assets/objviewer/lib/**` | mrdoob/three.js | MIT |
| Blockbench（内置网页版） | `app/src/main/assets/blockbench/**` | JannisX11/blockbench | MIT |
| Snowstorm（内置网页版，镜像自上游 master） | `app/src/main/assets/snowstorm/**` | JannisX11/snowstorm | GPL-3.0-or-later |
| Snowstorm 汉化字典与适配脚本（本项目自研，非上游）：`zh-dict.js`（英→中对照表）、`hans.js`（注入式安全翻译 + 导出接管），另 `index.html` 相对上游增加了两个脚本标签与中文标题 | `app/src/main/assets/snowstorm/zh-dict.js`、`hans.js`、`index.html` | MinewaysMobile | GPL-3.0（与本仓库一致）|
| lodepng（PNG 读写） | `app/src/main/cpp/core/lodepng.*` | lvandeve/lodepng | zlib |
| region.cpp（Minecraft region 文件读取） | `app/src/main/cpp/core/region.cpp` | Ryan Hitchman（文件头保留原版权声明） | BSD-2-Clause（见文件头） |
| 内置小游戏《寂零快跑》 | `app/src/main/assets/minigame/**` | 本项目原创 | 随本仓库 GPL-3.0 |
| AndroidX / Material Components | Gradle 依赖 | Google / AOSP | Apache-2.0 |
| Shizuku API | Gradle 依赖（`dev.rikka.shizuku`） | RikkaApps/Shizuku | 见上游仓库 / see upstream |
| Mine-imator 2.x 引擎（C++）+ 安卓壳层（GLSurfaceView / JNI） | `app/src/main/jniLibs/arm64-v8a/libmineimator.so`、`app/src/main/assets/{Data,Sprites,Compiled,Schematics,Particles,shader_*,world_*}`、`app/src/main/java/com/mineimator/app/**` | Mine-imator（原作者 David Norgren）；安卓移植/壳层为**第三方二进制**，来源未标注 | ⚠ **上游仓库未附许可（默认保留全部权利）**：仅按“免费软件”原样内置供自用/学习；若要再分发（尤其商用）请先取得作者授权 |
| Qt 5.15.2（Core / Gui / Network / Widgets，Android arm64） | `app/src/main/jniLibs/arm64-v8a/libQt5*_arm64-v8a.so`、`libc++_shared.so` | The Qt Company | LGPL-3.0 / GPL-2.0+（此处**动态链接**使用；再分发需随附许可文本并保留可替换库的能力） |
| Assimp（Open Asset Import Library）6.x | 构建期拉取（CMake FetchContent），编入 `libfmtconv.so` | assimp/assimp | **BSD-3-Clause**（可商用；保留版权与免责声明即可） |
| zstd（Zstandard）1.5.x | 构建期拉取（CMake FetchContent），仅用解压 | facebook/zstd | **BSD-3-Clause** / GPL-2.0 双许可（此处按 BSD 使用） |
| stb_image / stb_image_write | `app/src/main/cpp/third_party/stb/`（原文件未改） | nothings/stb | **公有领域（Public Domain）** / MIT 双许可 |
| 云间列车（单文件 WebGL2 动画 + 参数面板） | `app/src/main/assets/cloudtrain/**` | Rice-dog/code-codex（MIT）之「Cloud Train Background」插件的提取改编版 `incarnation-gem/sunset-cloud-train`；原始 shader 作者 **mdb** | ⚠ 上游提取版**无 LICENSE 文件**且注明「未提供再分发许可」；本仓库内置系**维护者确认已获授权**，文件头出处注释原样保留 |
| OpenCut（视频编辑器，Next.js 静态导出后整体内置） | `app/src/main/assets/opencut/**` | OpenCut-app/OpenCut | **MIT**（本项目另加：默认中文注入词典、手机强制横屏样式、21 个 canvas2d 特效、出入场动画可叠加与时长可调） |
| mediabunny（WebCodecs 封装，OpenCut 的 MP4/WebM 导出） | 打进 `assets/opencut/**` 的 JS bundle | vanruesc/mediabunny | **MPL-2.0**（仅以库形式链接使用；改动其源码需按 MPL 开放该文件） |
| onnxruntime-web（`.jsep.wasm` 20.6MB）+ transformers.js | `assets/opencut/_next/static/media/ort-wasm-*.wasm` | Microsoft/onnxruntime-web · huggingface/transformers.js | **MIT** · **Apache-2.0** |
| Arnis 内核（真实世界地图生成，v3.2.0 关掉桌面 GUI 后交叉编译） | `app/src/main/jniLibs/arm64-v8a/libarnis.so`、`RealWorldActivity.java` | louis-e/arnis | **Apache-2.0**（生成算法未改动，界面/参数在本项目侧） |
| 地图底图数据（非代码，但必须署名） | 运行期网络请求 | © OpenStreetMap contributors（Overpass API / OSM 瓦片）、Overture Maps、AWS Terrain Tiles | **ODbL** / 各家服务条款；离线包内**不含**任何底图数据 |
| PureLandscape 风景增强引擎（纯 Java，零第三方依赖） | `app/src/main/java/com/zeus/landscape/**`（33 个文件原样引入） | 用户提供的 `PureLandscape_java.zip`（包名 `com.zeus.landscape`，自述「手搓全部算法」） | ⚠ **压缩包内未附 LICENSE**（默认保留全部权利）：按用户提供源码原样内置；若要再分发/商用请先与权利人确认。本项目侧只补了安卓平台桥 `platform/AndroidLandscapeCodec.java`，并**未**引入桌面专属的 `Main/ImgIO/Watermark/Trace` |

### 云间列车（`app/src/main/assets/cloudtrain/`）

- **上游**：`Rice-dog/code-codex`（MIT，v0.2.15）里「Cloud Train Background」插件的独立提取改编版，对应仓库 `incarnation-gem/sunset-cloud-train`；**原始 shader 作者 mdb**（上游注释原文：*"Original supplied shader credited to mdb. No redistribution license was supplied."*）。
- **内置依据**：上游提取版没有 LICENSE 文件、并请求不要再分发；本仓库由**维护者确认已获授权**后内置。若你要**再分发或商用**，请先自行与权利人（mdb / 提取版作者）核对。
- **本项目只改了 3 处**，全部在文件内以 `移植补丁` 注释标出：① 手机（短边 ≤600 CSS px）默认 `resolution` 由 .75 降到 .5；② 同条件下 DPR 上限由 1.5 压到 1.0；③ 安全区与窄屏面板样式。**渲染核心与参数面板未作改动**。

## three.js 的 MIT 声明（必须保留）

```
three.js
Copyright © 2010-2021 three.js authors
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## 与 Mojang / Microsoft / 网易的关系

本项目**不是**官方产品，与 Mojang Studios、Microsoft、网易雷火均无隶属或背书关系。
Minecraft 相关商标与素材归各自权利人所有；请仅对你**合法拥有**的存档使用本工具，
并遵守对应版本的最终用户许可协议（EULA）。
