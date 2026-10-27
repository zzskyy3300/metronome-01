# 安卓节拍器 Metronome APP — 开发规划与任务清单

## Context（背景）
目标目录 `d:\zs\Android project\metronome-01` 当前为空，需从零搭建一个完整 Android 节拍器工程。需求文档即用户在对话中给出的规格：Kotlin + Jetpack Compose + AudioTrack 低延迟节拍 + MVVM极简 + SharedPreferences，最低 API26 / 目标 API34，包体 <10MB，禁止第三方埋点/广告/联网 SDK，息屏与后台持续播放并支持状态栏通知暂停/继续，长时间运行无漂移、音频延迟 <20ms。本规划遵循用户执行流程：先出规划与任务清单 → 等待确认 → 初始化工程 → 分层编码 → 自动编译修复 → Release 签名打包 → 编写 README。

## 技术栈与工具链（固定，匹配本地已验证环境）
来自同目录可正常编译的 `app-001` 工程：
- Gradle 9.4.1 / AGP 9.2.1 / Kotlin 2.2.10 / Compose BOM 2026.02.01
- `compileSdk { version = release(36) { minorApiLevel = 1 } }`（AGP 9.x 新语法，复用已安装的平台）
- `minSdk = 26`，`targetSdk = 34`（满足用户硬约束；compileSdk 用 36 以匹配本地已安装平台）
- Java source/target 11；AndroidX 依赖与 app-001 一致，**不新增任何外部依赖**（不加 media3）
- `local.properties` 的 `sdk.dir = C:\Users\zs\AppData\Local\Android\Sdk`
- 包名：`com.example.metronome`

## 工程结构
```
metronome-01/
├─ settings.gradle.kts          # 复用 app-001 仓库配置 + foojay toolchain
├─ build.gradle.kts             # 根：alias 插件 apply false
├─ gradle.properties            # -Xmx2048m, kotlin.code.style=official
├─ local.properties             # sdk.dir
├─ gradle/
│  ├─ libs.versions.toml        # 版本目录（agp/kotlin/composeBom 等，与 app-001 一致）
│  └─ wrapper/
│     ├─ gradle-wrapper.jar     # 从 app-001 复制（二进制，Write 无法生成）
│     └─ gradle-wrapper.properties  # Gradle 9.4.1
├─ gradlew / gradlew.bat        # 从 app-001 复制
├─ keystore.properties          # 签名配置（store/key 密码、alias）
├─ keystore/metronome.jks       # keytool 生成的 release 签名密钥
├─ proguard-rules.pro           # release 混淆 keep 规则
└─ app/
   ├─ build.gradle.kts          # signingConfigs.release(读 keystore.properties) + R8 minify
   └─ src/main/
      ├─ AndroidManifest.xml    # FGS + 权限 + service(mediaPlayback)
      ├─ res/values/strings.xml        # 默认中文
      │     /values-en/strings.xml     # 英文
      │     /values/themes.xml 等
      └─ java/com/example/metronome/
         ├─ MainActivity.kt
         ├─ data/{SettingsRepository.kt, Models.kt}      # SharedPreferences + 枚举(TS/Sound)
         ├─ audio/{MetronomeEngine.kt, WaveformSynthesizer.kt}  # AudioTrack 引擎
         ├─ service/MetronomeService.kt                  # 前台服务 + 通知 + MediaSession-free 自绘按钮
         ├─ vm/MetronomeViewModel.kt
         └─ ui/theme/{Color,Theme,Type}.kt + ui/screens/{MainScreen, components}
```

## 关键模块设计

### 1) 音频引擎 `audio/MetronomeEngine.kt`（零漂移、低延迟核心）
- 采样率：`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` 取硬件原生率，避免重采样降低延迟。
- 模式：`AudioTrack.MODE_STATIC`（整小节 PCM 一次性写入）。
- 拍号编码为 `(beatCount, subdivisionsPerBeat)`：简单拍号 2/4,3/4,4/4 beatCount=分子、subdiv=1；复合拍 6/8→beatCount=2、12/8→beatCount=4，beat 单位=附点四分（=3 个八分）。`samplesPerBeat = round(60/BPM * sampleRate)`，`barTotal = beatCount * samplesPerBeat`（整数采样数，循环天然对齐 → 零漂移）。
- 6 种音色（`WaveformSynthesizer.kt` 代码生成，**无外部音频文件**）：sine/square/woodblock/rimshot/clave/electronic-tick，均短包络衰减（~30-60ms）避免爆破音；重拍 louder/accented，弱拍 quieter。
- AudioTrack 构造：`USAGE_GAME + PERFORMANCE_MODE_LOW_LATENCY`（追求 <20ms 首拍延迟、且不主动打断其他音乐）。**关键流程**：`write(整小节缓冲)` → `setLoopPoints(0, barTotal, -1)`（注意：非 setLoopMode；MODE_STATIC 下必须先写满再设循环，否则静默失败）→ `play()`。若 `setLoopPoints` 返回非 0（部分 OEM 低延迟轨道拒绝大循环），则回退 `PERFORMANCE_MODE_NONE` 重建。
- 视觉同步（零漂移）：用 `AudioTrack.getTimestamp(AudioTimestamp)`（API24+）取 `(framePosition, nanoTime)` 锚点，HandlerThread ~60Hz 轮询，按 `currentFrame = ts.framePosition + (now-ts.nanoTime)/1e9 * sampleRate` 外推，得当前 beatIndex = (currentFrame mod barTotal)/samplesPerBeat；beatIndex 变化时经 `SharedFlow<BeatEvent>`（distinctUntilChanged）发出，驱动 Compose 闪烁与重/弱拍配色。`getTimestamp` 失败则退回 `getPlaybackHeadPosition()`。pause/resume 后守卫 `ts.nanoTime>0`。
- 参数变更策略（避免间隙）：volume 仅走 `audioTrack.setVolume(...)`（零开销）；BPM/拍号/音色变更 → 重生成缓冲，**在下一 beat 边界**重建轨道（不每次立即停）。
- 公共 API：`configure(MetronomeParams)`、`play()/pause()/stop()/release()`、`setMasterVolume(Float)`、`tapTempo():Float`、`val beatFlow: SharedFlow<BeatEvent>`、`val state: StateFlow<EngineState>`。

### 2) 前台服务 `service/MetronomeService.kt`（API34 后台保活 + 通知暂停/继续）
- Manifest 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`（API34 类型化 FGS 必需）、`POST_NOTIFICATIONS`（API33+，否则通知与 Pause/Resume 按钮被系统隐藏——这是用户明确要求的功能，非多余权限，为唯一运行时权限）。
- Service 声明 `android:foregroundServiceType="mediaPlayback"`、`exported="false"`。
- 启动 API：`ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`（API29+ 类型化 FGS 必须显式传 type，API26-28 自动忽略）。
- 命令双通道：通知按钮（跨进程）→ `PendingIntent → onStartCommand(action)`；ViewModel（同进程）→ Binder 直调；二者汇聚到私有 `applyCommand(Command)` 单点状态机。
- 通知：自定义频道 + `NotificationCompat`，主按钮随状态在 Pause↔Resume 间切换，通过 `NotificationManagerCompat.notify` 更新（**不**重复调 startForeground）。Stop 副按钮 → 退出。
- `POST_NOTIFICATIONS` 在 MainActivity 首次 play 前用 `ActivityCompat.requestPermissions` 请求；被拒仍启动 FGS（音频继续），仅提示用户通知按钮不可见。

### 3) 数据层 `data/`（SharedPreferences 持久化）
- `SettingsRepository`：bpm、timeSignatureIndex、soundTypeIndex、volume(0..1)、accentEnabled、practiceDurationMinutes、language(zh/en)。
- `Models.kt`：`enum TimeSignature(2/4,3/4,4/4,6/8,12/8)`、`enum SoundType(6 种)`，含 beatCount/subdiv 元数据。

### 4) ViewModel `vm/MetronomeViewModel.kt`
- 绑定 `MetronomeService`，`collect` 其 `state` 暴露 `UIState`（Compose 观察）。
- 命令：play/pause/stop/setBpm/setMeter/setSound/setVolume/toggleAccent/setPracticeMinutes/tapTempo/toggleLanguage。
- Tap Tempo：记录 `System.nanoTime()`，平均最近 N 次间隔，`BPM=60e9/avg`，clamp 30-220 取整。
- 练习计时器：到点经 service 停止 + 通知。
- 语言切换：写 prefs → `recreate()` Activity（attachBaseContext 读 prefs 覆盖 Locale，无需 appcompat）。

### 5) UI `ui/screens/`（深色极简、中英双语、大字体 BPM）
- 强制 `darkColorScheme()`（暗光练习场景，闪烁更醒目）。
- `MainScreen`：顶部语言切换；中央大字体 BPM（粗大、自适应）+ 上下加减按钮；其下 Tap Tempo 大圆按钮；节拍闪烁圆点行（重拍强色/弱拍弱色，按 beatCount 个数渲染）；拍号选择；音色选择；音量滑块；练习计时器设置+显示。
- 字符串全部走 `string.xml`（zh 默认 + en）。

## 编译 / 签名 / 打包流程（执行阶段）
1. **初始化工程文件**（Write 创建文本配置）：settings/build/gradle.properties/local.properties/libs.versions.toml/proguard-rules/AndroidManifest/strings/各 Kotlin 源文件。
2. **复制二进制 wrapper**（Shell 复制，因 Write 无法生成二进制）：从 `app-001` 复制 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle-wrapper.properties`。
3. **生成 release 签名**（Shell）：`keytool -genkeypair -keystore keystore/metronome.jks ...`；优先用 `keytool`（PATH / `$JAVA_HOME/bin` / Android Studio JBR `C:\Program Files\Android\Android Studio\jbr\bin`），找不到则回退 Android `~/.android/debug.keystore` 保底可产出可装 APK。写 `keystore.properties`。
4. **自动编译修复**：运行 `.\gradlew assembleDebug`；若报错则读取错误、定位修复、重试，直至通过。
5. **Release 打包**：`.\gradlew assembleRelease -PsignRelease=true`（或读取 keystore.properties）→ 产出 `app/build/outputs/apk/release/app-release.apk`；检查体积 <10MB。
6. **README**：根目录 `README.md`，写明环境要求（JDK17+、Android SDK、Gradle 9.4.1）、编译步骤、签名说明、APK 安装调试（`adb install`、logcat）。
7. 代码加详细中文注释，结构清晰便于迭代。

## 任务清单（对应用户 ②~⑧）
- [②] 初始化工程：gradle 配置、manifest、前台服务声明、wrapper 复制、local.properties
- [③a] 数据层：SharedPreferences Repository + 枚举模型
- [③b] 音频引擎：WaveformSynthesizer + MetronomeEngine（AudioTrack MODE_STATIC + setLoopPoints + getTimestamp 同步）
- [③c] 前台服务：MetronomeService + 通知（Pause/Resume/Stop）+ Binder
- [③d] ViewModel：MetronomeViewModel（状态聚合 + 命令 + Tap Tempo + 计时器）
- [③e] UI：Compose 主题 + MainScreen 及各组件 + 双语 string.xml
- [⑤] 自动编译 Debug、捕获错误、修复至通过
- [⑥] Release 签名打包、校验体积
- [⑦] README 编写
- [⑧] 注释与结构复查

## 验证方法
1. `.\gradlew assembleDebug` 通过 → 编译正确性。
2. `.\gradlew assembleRelease` 产出签名 APK；`Get-Item ...app-release.apk | Select Length` < 10MB。
3. 安装到设备/模拟器（API≥26）：启动后听节拍；切后台/息屏 5 分钟以上节拍不中断；状态栏通知显示 Pause/Resume/Stop 且可控制；长时间运行后对照秒表/另一节拍器验证无累积漂移；切换 BPM/拍号/音色/音量即时生效；Tap Tempo 准确；练习计时器到点停止；重启 App 参数保持；中英切换生效。

## 风险与备注
- `setLoopPoints` 在部分 OEM 低延迟轨道可能被拒：已设计回退到 `PERFORMANCE_MODE_NONE`。
- 极端 BPM（30/220）缓冲约 0.7-8MB，MODE_STATIC 可接受。
- 不引入任何第三方埋点/广告/联网 SDK；`POST_NOTIFICATIONS` 为满足用户"状态栏通知暂停/继续"硬需求而保留，非多余。
- 不擅自更换技术栈、不新增需求外功能；音频延迟/断拍问题优先修复。
