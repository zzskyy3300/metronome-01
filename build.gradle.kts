// 顶层构建文件：声明插件，apply false 表示在子模块中应用。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
