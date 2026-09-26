plugins {
    id("com.android.application")
}

android {
    namespace = "com.mineways"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mineways"
        minSdk = 26
        targetSdk = 34
        versionCode = 45
        versionName = "3.14"
        // 显式锁定已安装的 NDK 版本（r26.3），避免 AGP 自动下载其它 NDK
        ndkVersion = "26.3.11579264"
        ndk {
            // 机型覆盖：64 位 ARM（主流）+ 32 位 ARM（老机/低价机/Android Go）+ x86_64（模拟器/Intel 平板）
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                targets += "mineways_core"
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

    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // WebViewAssetLoader / WebViewCompat：用于离线加载内置 Blockbench 网页版
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 本地 Chunker 世界转换引擎
    implementation(project(":chunker-core"))

    // 单元测试（纯 JVM）：覆盖「导出选项」选择器的三态逻辑（多选 / 单选 / 不选）
    testImplementation("junit:junit:4.13.2")
    // Core library desugaring 运行时依赖
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}