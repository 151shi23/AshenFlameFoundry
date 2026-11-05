import java.util.Properties

plugins {
    id("com.android.application")
    // Kotlin + Compose：为了直接使用玄戒工具箱所用的那个库（io.github.kyant0:backdrop，它是 Compose 库）
    // 版本跟着 backdrop / shapes 走：它们是用 Kotlin 2.3.x 编的，低级编译器读不了它们的元数据
    id("org.jetbrains.kotlin.android") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

android {
    namespace = "com.mineways"
    compileSdk = 36      // backdrop 2.0.0 的 AAR metadata 想要 37，见下面 checkReleaseAarMetadata 的说明
                         // targetSdk 仍是 34，minSdk 仍是 26 —— 只动依赖，不动编译面

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // AGP 8.7 的 lint 与 Kotlin 2.3 不兼容（NonNullableMutableLiveDataDetector 会抛
    // IncompatibleClassChangeError，把 release 构建直接打断）。出包不靠 lint，关掉它。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    defaultConfig {
        // 先读本地配置（不进版本库）：SDK 路径、激活后端凭证、专业版开关、版本号覆盖
        val affProps = Properties()
        val affFile = rootProject.file("local.properties")
        if (affFile.exists()) {
            affFile.inputStream().use { affProps.load(it) }
        }

        applicationId = "com.mineways"
        minSdk = 26
        targetSdk = 34
        // 版本号：默认值 = 公开版；本机 local.properties 里可以覆盖成专业版号段（不进版本库）
        versionCode = affProps.getProperty("AFF_VERSION_CODE", "114").trim().toInt()
        versionName = affProps.getProperty("AFF_VERSION_NAME", "3.83").trim()

        // 激活码体系与专业版登录墙已移除；白名单功能只认「关于」页的账号密码登录。
        // 激活码后端凭证：缺省为空串，App 仍可编译（值来自上面读的 local.properties）
        buildConfigField("String", "AFF_BASE_URL", "\"${affProps.getProperty("AFF_BASE_URL", "")}\"")
        buildConfigField("String", "AFF_APP_ID", "\"${affProps.getProperty("AFF_APP_ID", "")}\"")
        buildConfigField("String", "AFF_APP_SECRET", "\"${affProps.getProperty("AFF_APP_SECRET", "")}\"")
        buildConfigField("String", "AFF_OAUTH_APP_KEY", "\"${affProps.getProperty("AFF_OAUTH_APP_KEY", "")}\"")
        buildConfigField("String", "AFF_OAUTH_APP_SECRET", "\"${affProps.getProperty("AFF_OAUTH_APP_SECRET", "")}\"")
        buildConfigField("String", "AFF_OAUTH_REDIRECT", "\"${affProps.getProperty("AFF_OAUTH_REDIRECT", "")}\"")
        // 显式锁定已安装的 NDK 版本（r26.3），避免 AGP 自动下载其它 NDK
        ndkVersion = "26.3.11579264"
        ndk {
            // 机型覆盖：64 位 ARM（主流）+ 32 位 ARM（老机/低价机/Android Go）+ x86_64（模拟器/Intel 平板）
            // 开发期可用 -Pabi=arm64-v8a 只编一个 ABI（原生依赖多时能省十几分钟）
            val onlyAbi = (project.findProperty("abi") as String?)?.trim().orEmpty()
            abiFilters += if (onlyAbi.isNotEmpty()) listOf(onlyAbi)
            else listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // mineways_core：导出内核；seedmap：离线种子地图内核（cubiomes，MIT）
                // fmtconv：格式转换内核（Assimp + zstd + stb）
                // packcore：基岩→Java 材质包转换内核（纯 C 算子 + 内置映射表）
                targets += "mineways_core"
                targets += "seedmap"
                targets += "fmtconv"
                targets += "packcore"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 开启核心库 desugaring：支持 Java 17 的 record / java.time.Instant 等。
        isCoreLibraryDesugaringEnabled = true
    }

    androidResources {
        // aapt 默认的忽略规则里有 <dir>_* —— 任何下划线开头的目录都不进包。
        // Next.js 静态导出的整个 _next/（全部 JS/CSS/wasm/字体）和页面旁的
        // __next.*.txt 正好全中招：实测静默少了 74 个文件 / 29.9MB，其中含
        // Tailwind 那个 131KB 的 CSS，于是 WebView 里渲染出的是无样式空壳，
        // 控件全挤成一堆，表现就是「手机上按钮点不到」。
        // 这里覆盖默认规则：只继续屏蔽隐藏文件与版本控制目录，放行下划线目录。
        //
        // 另外把 opencut 排除掉：那套视频剪辑素材有 28606 个文件 / 344 MB，
        // 只有真用剪辑的人才需要，不该让所有下载者一起扛。改由
        // com.mineways.assets.AssetPacks 在第一次打开剪辑页时按需下载
        // （仓库 151shi23/AshenFlameAssets，压缩后 108 MB）。
        // 调试内置版：加 -PkeepOpencut=true
        val keepOpencut = (project.findProperty("keepOpencut") as String?) == "true"
        val assetRules = "!.svn:!.git:!.hg:!.cvsignore:.*:!Thumbs.db:!desktop.ini:!*.iml"
        ignoreAssetsPattern = if (keepOpencut) assetRules
                else "$assetRules:!opencut:!minigame:!rhinelab"
        // 动捕模型（MediaPipe .task）必须**不压缩**：tasks-vision 用 AAssetManager 的
        // 文件描述符直接 mmap 模型，deflate 过的 asset 拿不到 fd → 加载直接失败。
        // 实测默认打包是 method 8（30.6MB → 28.5MB），模型必然"启动不起来"。
        noCompress += "task"
    }

    packagingOptions {
        jniLibs {
            // 必须走 legacy 打包：false（默认）时 .so 不解压、直接从 APK 映射，nativeLibraryDir
            // 里空空如也；true 时系统安装会把 .so 解压到 /data/app/<包名>/lib/<abi>，
            // 那里是应用唯一有执行权限的位置，Arnis 内核（libarnis.so）要靠它才能跑起来。
            useLegacyPackaging = true
            // libarnis.so 是可执行文件不是共享库，别让 AGP 拿 strip 去动它（会破坏 ELF 入口）
            keepDebugSymbols += "**/libarnis.so"
            // YSM 官方预编译 JNI：已经 strip，别再让 AGP 动它
            keepDebugSymbols += "**/libYSMParserJNI.so"
            // libphotocraft_android.so 是 108 MB 的 Rust 引擎（NDK r28 构建、LOAD 段 16 KB 对齐），
            // 导出符号 android_main / GameActivity_onCreate 由 Java 胶水按名字查找；
            // 让 AGP 别重写这份 ELF，既省一次整库 strip，也避免破坏页对齐。
            keepDebugSymbols += "**/libphotocraft_android.so"
        }
    }
}

/*
 * backdrop 2.0.0 的 AAR metadata 写着 minCompileSdk=37，但同一个文件里
 * minAndroidGradlePluginVersion=1.0.0 —— 它对 AGP 没有版本要求，37 只是建议值。
 * 本机 AGP 8.7.3 不认「带次版本号」的平台包名：实测 DSL 里没有 compileSdkMinor 这个属性，
 * 而 SDK 里装的是 platforms;android-37.0，AGP 按 platforms;android-37 去找，找不到就报错。
 * 所以这里只关掉这一条建议性检查，不动资源链接、不动编译：
 * 真需要 API 37 的东西会在 processReleaseResources 或 kotlin/javac 上直接炸出来，
 * 那时把两行依赖改回 1.0.6 / compose 1.10.3 就完整退回（导航栏源码本来就没动）。
 */
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }
    .configureEach { enabled = false }

kotlin {
    jvmToolchain(17)
}

dependencies {
    // YSM（Yes Steve Model）解密：OpenYSM/YSMParser 官方 JNI 封装 + arm64-v8a 预编译库
    implementation(files("libs/YSMParserJNI.jar"))

    // ── Compose + 液态玻璃原库（玄戒工具箱用的就是这套，效果由它的 AGSL 着色器算）──
    // 2026-10-17 按 liquid-glass 技能的基线升到 backdrop 2.0.0（KMP 包，Android 侧把
    // org.jetbrains.compose.* 映射到 androidx compose 1.11.0，所以核心跟着从 1.10.3 进 1.11.0）。
    // 2.0 才有跨平台 RuntimeShader 与 exportedBackdrop，技能目录那套成品组件按它写。
    // 前提：compileSdk 37（backdrop-android 的 AAR metadata 硬要求，本机已装 platforms;android-37.0）。
    // 底部导航栏（LiquidGlassNav + com.mineways.glass）的源码一行没改 —— 1.x 到 2.0 的
    // drawBackdrop 调用签名逐个参数对得上，这条栏保持原样是靠哈希比对证明的，不是靠嘴说。
    implementation("androidx.compose.ui:ui:1.11.0")
    implementation("androidx.compose.ui:ui-graphics:1.11.0")
    implementation("androidx.compose.foundation:foundation:1.11.0")
    implementation("androidx.compose.animation:animation:1.11.0")
    implementation("io.github.kyant0:backdrop:2.0.0")
    implementation("io.github.kyant0:shapes:1.2.0")   // 胶囊形状 Capsule()，导航栏背板用

    // ── 界面重构用的库（2026-10-17，全部走 Maven Central / Google Maven）────────────
    implementation("androidx.compose.material3:material3:1.4.0")
    // 矢量线条图标，替掉"安卓默认味"的自绘图标
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // 冷启动品牌画面，不再是一块黑屏
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Lottie 矢量动画：首页头图、加载、解锁成功的动效（.json 打进 assets）
    implementation("com.airbnb.android:lottie-compose:6.7.1")
    // 彩纸粒子（解锁成功的正反馈）：konfetti 是 Kotlin 多平台包，这台机器的镜像缺它的
    // konfetti-core JVM 产物（--refresh-dependencies 也拿不到），所以不引库——
    // 粒子效果自己用 Canvas 写（颜色、重力、散开角度全都可控，反而更贴这套设计）。
    implementation("com.google.accompanist:accompanist-placeholder:0.36.0")
    implementation("com.google.accompanist:accompanist-flowlayout:0.36.0")
    // 卡片式弹出菜单：动画是路径变形，长按工具卡的「更多」用它
    implementation("me.saket.cascade:cascade-compose:2.3.0")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity:1.8.2")
    // 页面路由用安卓官方的 Navigation（玄戒工具箱用的是它的 Compose 版 NavHost）
    implementation("androidx.navigation:navigation-fragment:2.7.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // WebViewAssetLoader / WebViewCompat：用于离线加载内置 Blockbench 网页版
    implementation("androidx.webkit:webkit:1.10.0")

    // 动捕：MediaPipe Pose Landmarker（Apache-2.0）+ CameraX。
    // 模型随包放 assets/pose_landmarker_heavy.task（29MB），代码按 heavy>full>lite 自动取最高档。
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // PhotoCraft 修图：整套界面与引擎都是 Rust + egui + wgpu 的原生实现
    // （libphotocraft_android.so 里的 android_main 在独立线程自绘），
    // Java 侧只需要 androidx.games 的 GameActivity 生命周期胶水。
    // 注意：不要开 prefab / 再链一份 GameActivity C++ 胶水，会与 .so 内的符号重复定义。
    implementation("androidx.games:games-activity:4.4.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Blender 离线渲染：.deb / Blender 官方包都是 .tar.xz，java.util.zip 只有 deflate/gzip，
    // 用纯 Java 的 xz 解码器（XZ for Java）。BlenderEnv/ArReader 用反射调用它，
    // 万一离线构建没拉到依赖，也会自动回退到设备上的 xz 命令，不会编译失败。
    implementation("org.tukaani:xz:1.9")

    // 本地 Chunker 世界转换引擎
    implementation(project(":chunker-core"))

    // 单元测试（纯 JVM）：覆盖「导出选项」选择器的三态逻辑（多选 / 单选 / 不选）
    testImplementation("junit:junit:4.13.2")
    // Core library desugaring 运行时依赖
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}