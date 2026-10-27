// 节拍器 app 模块构建脚本。
// 依赖与插件版本与本地已验证环境 app-001 完全一致，不新增任何第三方 SDK。

import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 读取 release 签名配置（keystore.properties，位于项目根目录）。
// 仅在文件存在时启用 release 签名；不存在时 release 仍可构建但不签名。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.example.metronome"
    compileSdk {
        // AGP 9.x 新语法：复用本地已安装平台 36（含 minorApiLevel=1）。
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.metronome"
        minSdk = 26          // 最低 Android 8.0（满足用户硬约束）
        targetSdk = 34       // 目标 Android 14
        versionCode = 10
        versionName = "1.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release 签名配置：从 keystore.properties 注入
    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                // 用 rootProject.file 解析相对路径到工程根目录（keystore.properties 在根目录）
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile") as String)
                storePassword = keystoreProperties.getProperty("storePassword") as String
                keyAlias = keystoreProperties.getProperty("keyAlias") as String
                keyPassword = keystoreProperties.getProperty("keyPassword") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8 代码混淆压缩，控制包体
            isShrinkResources = true        // 资源压缩
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("proguard-rules.pro")
            )
            // 仅当 keystore 存在时签名；不存在则跳过签名配置（仍可产出未签名 release APK）
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // debug 不混淆、不压缩，便于调试
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ViewModel 的 Compose 集成（viewModels() 委托 + viewModel() composable）
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
