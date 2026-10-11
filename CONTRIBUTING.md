# 怎么参与 / Contributing

先说结论：**欢迎直接提 PR**。测试、策划、汉化、文档、素材、报 bug 都算贡献，不写代码也一样 ——
这类贡献记在 [CONTRIBUTORS.md](CONTRIBUTORS.md) 里，与提交记录同等有效。

本项目是 **GPL-3.0** 许可（见 [LICENSE](LICENSE)），提交即表示你同意你的贡献以同一许可发布。

---

## 1. 你需要知道的三件事

1. **这是一个安卓 App**，不是纯 Java 库。跑起来需要 Android SDK + NDK，见下面的环境要求。
2. **仓库里没有编译好的 APK，也没有签名密钥**，这是刻意的（见 [`.gitignore`](.gitignore)）。
3. **有一部分资源树不随仓库分发**（体积或第三方版权原因），列在第 4 节。缺了它们 App 仍能编译，
   但对应功能页会是空壳 —— 这些功能需要你自己准备资源。

## 2. 环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | 必需，Gradle 与 `sourceCompatibility=17` 都按它来 |
| Android SDK Platform | 34 / 35 / 36 | `compileSdk = 36`（Compose 与 backdrop AAR 要求），`targetSdk = 34` |
| Build-Tools | 34.0.0 或 36.1.0 | `zipalign` / `apksigner` / `aapt2` 用 36.1.0 |
| NDK | **26.3.11579264** | 版本被显式锁定，换版本会触发 CMake 重新配置 |
| minSdk | 26 | 原生代码里用到的 API 以此为下限 |

```bash
# 本机 SDK 路径与后端凭证都写在这里（该文件已被 .gitignore 忽略，绝不提交）
cp local.properties.example local.properties
$EDITOR local.properties
```

`local.properties` 里除 `sdk.dir` 外的键都是**可选**的（激活后端地址、App 凭证、版本号覆盖）；
全部留空时 App 仍可编译，白名单/激活码相关功能会走"未配置"分支。

## 3. 构建与测试

```bash
./gradlew :app:assembleDebug              # 调试包
./gradlew :app:assembleRelease            # 正式包（未签名，三 ABI 全量）
./gradlew :app:assembleRelease -Pabi=arm64-v8a   # 只编一个 ABI，开发期快很多
./gradlew :app:testDebugUnitTest          # 单元测试（报告在 app/build/reports/tests/）
./gradlew :app:compileReleaseJavaWithJavac       # 只校验 Java/Kotlin 能否编过
```

几条会救你时间的规矩：

- **离线构建**：依赖已全部缓存在本机，CI/内网环境请加 `--offline`。
- **同一个构建目录里不要并行跑两个 Gradle**：CMake 会锁死 `configure_stdout.log`，然后逼你重编三套 ABI。
- **`lint` 已关闭**（AGP 8.7 的 lint 与 Kotlin 2.3 不兼容，会直接把 release 构建打断），别指望它报错来兜底。
- 原生依赖（assimp / zstd）由 CMake `FetchContent` 从 GitHub 拉取，**网络不通时构建会失败**，
  可用 `GIT_CONFIG_*` 走镜像，细节见 `app/src/main/cpp/CMakeLists.txt` 注释。

## 4. 仓库里没有的东西（以及怎么拿到）

| 路径 | 为什么不入库 | 怎么补 |
|---|---|---|
| `app/src/main/assets/opencut/` | 视频剪辑器的**构建产物**（约 340MB，含 53MB 单文件模型权重），产物不该进版本库 | 用 OpenCut 前端仓库 `bun run build` 后把 `out/` 放进来；不需要这个功能可以留空，其余功能照常编译 |
| `app/src/main/assets/{Data,Sprites,Compiled,Particles,Schematics}`、`shader_*` | Mine-imator 引擎的**第三方资源与二进制**，上游未附再分发许可 | 自行获取；缺省时该功能页会提示不可用 |
| `app/src/main/assets/cloudtrain/`、`rhinelab/` | 含第三方版权素材，按各自授权单独分发 | 同上 |
| `app/src/main/jniLibs/**/*.so` | 第三方预编译二进制 | 自行获取或用 `:app:externalNativeBuild*` 编自己那份 |
| `产物/`、`*.jks`、`local.properties` | **签名密钥与凭证，永远不入库** | 本地自备 |

> 加了新资源树请**同时**更新本表和 [`.gitignore`](.gitignore)，别让下一个人在 CI 里才发现仓库少了东西。

## 5. 提 PR 的流程

1. 从 `main` 切分支：`feat/<简短英文>` / `fix/<简短英文>`。
2. 一个 PR 只做一件事。修 bug 顺手改格式请分成两个提交。
3. **本地至少跑过** `:app:assembleDebug`；改到原生代码就再跑一次完整 `assembleRelease`。
4. 提交信息用中文，写清**为什么**而不是"改了什么"（仓库历史就是这个风格）。
5. 开 PR 时按模板勾完检查项；没有跑过的项**直接写"没验"**，不要留空也不要勾上。

### 提交信息风格

```
修「选完字体后内容栏打不进字」：文本框漏了草稿态，受控值每次按键被回滚

数字框早就走 usePropertyDraft 躲开这个坑，文本框漏了。
```

### 绝对不要提交

- 密钥、token、激活码、`local.properties`、keystore、签名口令 —— **任何形式的字面值**
- APK / AAB / 构建产物 / 日志 / 抓包结果
- 大于 50MB 的单文件（GitHub 会直接拒收 100MB 以上）
- 别人没授权再分发的素材、字体、模型、音频

## 6. 报 bug

走 [Issue 模板](../../issues/new/choose)。这个 App 的缺陷大多**只在真机上出现**，所以请尽量给全：

- 机型 + 安卓版本 + App 版本号（「关于」页最下面那行，形如 `5.12 (522)`）
- **截图**（有截图才有定位方向，纯文字描述经常对不上现象）
- 复现步骤：从哪一屏点进来的，点了什么
- 崩溃的话把「崩溃报告」页的内容一起贴上

## 7. 把自己加进名单

改 [CONTRIBUTORS.md](CONTRIBUTORS.md) 里那张表，加一行你的名字/昵称 + 做了什么，提 PR 就行。
不需要先有代码提交 —— 测出过一个真 bug、翻译过一屏文案、指出过一处行为跟桌面版不一致，都算。

如果你的提交没出现在 GitHub 的 Contributors 图里，多半是**提交邮箱没在账号里验证**；
本仓库用 [`.mailmap`](.mailmap) 归并身份，需要调整就在里面加一行。

## 8. 行为准则

讨论只针对代码与现象，不针对人。详见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
发现安全问题请按 [SECURITY.md](SECURITY.md) 私密上报，**不要**直接开公开 Issue。
