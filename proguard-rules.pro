# R8/ProGuard 规则（release）。
# AndroidX、Compose 的 consumer rules 已自带，无需额外 keep。
# Manifest 中声明的组件（Activity/Service）由 AGP 自动 keep。

# 保留 Kotlin 元数据，避免反射相关运行时问题。
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# 保留 Parcelable（如有）。
-keepclassmembers class * implements android.os.Parcelable {
    public static final *** CREATOR;
}

# 保留 Service（前台服务），保险起见。
-keep class com.example.metronome.service.MetronomeService { *; }
