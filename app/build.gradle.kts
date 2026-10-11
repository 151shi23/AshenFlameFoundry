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
    compileSdk = 36      // Compose 1.10.x / backdrop 1.0.6 的 AAR 要求（targetSdk 仍是 34）

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
                targets += "mineways_core"
                targets += "seedmap"
                targets += "fmtconv"
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
        ignoreAssetsPattern = "!.svn:!.git:!.hg:!.cvsignore:.*:!Thumbs.db:!desktop.ini:!*.iml"
    }

    packagingOptions {
        jniLibs {
            // 必须走 legacy 打包：false（默认）时 .so 不解压、直接从 APK 映射，nativeLibraryDir
            // 里空空如也；true 时系统安装会把 .so 解压到 /data/app/<包名>/lib/<abi>，
            // 那里是应用唯一有执行权限的位置，Arnis 内核（libarnis.so）要靠它才能跑起来。
            useLegacyPackaging = true
            // libarnis.so 是可执行文件不是共享库，别让 AGP 拿 strip 去动它（会破坏 ELF 入口）
            keepDebugSymbols += "**/libarnis.so"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // ── Compose + 液态玻璃原库（玄戒工具箱用的就是这套，效果由它的 AGSL 着色器算）──
    implementation("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.compose.ui:ui-graphics:1.10.3")
    implementation("androidx.compose.foundation:foundation:1.10.3")
    implementation("androidx.compose.animation:animation:1.10.3")
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:shapes:1.2.0")   // 胶囊形状 Capsule()，导航栏背板用

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity:1.8.2")
    // 页面路由用安卓官方的 Navigation（玄戒工具箱用的是它的 Compose 版 NavHost）
    implementation("androidx.navigation:navigation-fragment:2.7.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // WebViewAssetLoader / WebViewCompat：用于离线加载内置 Blockbench 网页版
    implementation("androidx.webkit:webkit:1.10.0")
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